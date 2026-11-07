import com.google.protobuf.ByteString;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.telegram.botgram.BotgramProtobufIdentity;
import org.telegram.botgram.BotgramProtobufRequestMapper;
import org.telegram.botgram.BotgramProtobufResponseMapper;
import org.telegram.botgram.e2ee.BotgramE2eeEnvelopeCodec;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

/** Real production mapper -> original TL binary checks for the directional wire additions. */
public final class BotgramE2eeMappingTest {
    private static int checks;
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }

    private static TLRPC.Message roundTrip(BotgramProtobufResponseMapper mapper, BotgramRpc.Message message) throws Exception {
        BotgramRpc.RpcResponse response = BotgramRpc.RpcResponse.newBuilder().setProtocolMajor(1)
                .setGetHistory(BotgramRpc.MessagePage.newBuilder().addMessages(message)).build();
        TLRPC.messages_Messages mapped = (TLRPC.messages_Messages) mapper.map(response);
        SerializedData out = new SerializedData(); mapped.serializeToStream(out);
        byte[] wire = out.toByteArray();
        SerializedData in = new SerializedData(wire);
        TLRPC.messages_Messages parsed = (TLRPC.messages_Messages) new TLRPC.TL_messages_getHistory()
                .deserializeResponse(in, in.readInt32(true), true);
        check(parsed != null && in.remaining() == 0, "original TL history parsed");
        SerializedData repeated = new SerializedData(); parsed.serializeToStream(repeated);
        check(Arrays.equals(wire, repeated.toByteArray()), "TL binary stable");
        return parsed.messages.get(0);
    }

    public static void main(String[] args) throws Exception {
        String own = "11111111111111111111111111111111", peer = "22222222222222222222222222222222";
        BotgramRpc.UserRef ownRef = BotgramRpc.UserRef.newBuilder().setServerDomain("host.invalid").setUserId(own).build();
        BotgramRpc.UserRef peerRef = ownRef.toBuilder().setUserId(peer).build();
        BotgramProtobufIdentity identity = new BotgramProtobufIdentity(null, own, () -> true);
        // Seed the result of the authenticated identity bootstrap; this test performs no HTTP.
        Field domain = BotgramProtobufIdentity.class.getDeclaredField("domain"); domain.setAccessible(true); domain.set(identity, "host.invalid");
        identity.learn(List.of(BotgramRpc.UserSummary.newBuilder().setIdentity(ownRef).setTlUserId(101).setIsSelf(true).build(),
                BotgramRpc.UserSummary.newBuilder().setIdentity(peerRef).setTlUserId(102).build()), List.of());
        BotgramProtobufResponseMapper mapper = new BotgramProtobufResponseMapper(identity);
        BotgramRpc.E2eeEnvelope envelope = BotgramRpc.E2eeEnvelope.newBuilder().setProtocolVersion(1)
                .setConversationId("directional-test").setSenderUserId(peer).setSenderDeviceId(1)
                .setRecipientUserId(own).setRecipientDeviceId(0).setKeyEpoch(1).setRandomId(17)
                .setMessageType(4).setCiphertext(ByteString.copyFrom(new byte[48])).build();
        BotgramRpc.Message.Builder base = BotgramRpc.Message.newBuilder().setMessageId(17)
                .setPeer(BotgramRpc.PeerRef.newBuilder().setUser(peerRef)).setSender(peerRef).setCreatedAtMs(1700000000000L);
        TLRPC.Message incoming = roundTrip(mapper, base.clone().setE2Ee(envelope).build());
        check(incoming instanceof TLRPC.TL_message && !incoming.out, "incoming sealed message is a normal bubble carrier");
        check(BotgramE2eeEnvelopeCodec.unwrap(incoming.message).equals(envelope), "type4 and absent recipient key survive mapping and TL");
        check(!incoming.message.contains("private plaintext"), "carrier is ciphertext only");
        TLRPC.Message system = roundTrip(mapper, base.clone().setMessageId(18).setService(BotgramRpc.ServiceMessage.newBuilder()
                .setE2EeSystem(BotgramRpc.E2eeSystemAction.newBuilder().setText("e2ee:one_way_enabled"))).build());
        check(system instanceof TLRPC.TL_messageService, "directional notice uses native service row");
        check(system.action instanceof TLRPC.TL_messageActionCustomAction
                && "e2ee:one_way_enabled".equals(system.action.message), "service marker and actor preserved");
        TLRPC.TL_messages_sendMessage send = new TLRPC.TL_messages_sendMessage();
        TLRPC.TL_inputPeerUser target = new TLRPC.TL_inputPeerUser(); target.user_id = 102;
        send.peer = target; send.random_id = 17; send.message = incoming.message;
        BotgramRpc.SendMessageRequest request = new BotgramProtobufRequestMapper(identity).map(send, "directional-test").getSendMessage();
        check(request.hasE2Ee() && request.getText().isEmpty(), "request mapper never sends sealed carrier as plaintext");
        check(request.getE2Ee().getMessageType() == 4 && request.getE2Ee().getRecipientDeviceId() == 0,
                "directional type and no-key marker preserved on send");
        System.out.println("PASS: directional mapper and original TL binary, " + checks + " checks");
    }
}
