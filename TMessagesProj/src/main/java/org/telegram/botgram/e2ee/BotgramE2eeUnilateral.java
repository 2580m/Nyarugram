package org.telegram.botgram.e2ee;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.google.protobuf.ByteString;
import org.telegram.botgram.protocol.v1.BotgramRpc;

/** Sender-only sealed message. No recipient key is created, uploaded or later released. */
public final class BotgramE2eeUnilateral {
    public static final int MESSAGE_TYPE = 4;
    private BotgramE2eeUnilateral() {}

    public static BotgramRpc.E2eeEnvelope seal(byte[] vaultKey, String self, String peer,
            String conversation, int device, long epoch, long randomId, String text) throws Exception {
        if (text == null || text.isEmpty() || text.length() > 16000) throw new IllegalArgumentException("text");
        BotgramRpc.E2eeInner inner = BotgramRpc.E2eeInner.newBuilder().setProtocolVersion(1)
                .setConversationId(conversation).setSenderUserId(self).setSenderDeviceId(device)
                .setRecipientUserId(peer).setRecipientDeviceId(0).setKeyEpoch(epoch).setRandomId(randomId)
                .setTimestampMs(System.currentTimeMillis()).setText(text).build();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(vaultKey, "HmacSHA256"));
        byte[] key = mac.doFinal("botgram-unilateral-message-key-v1".getBytes(StandardCharsets.UTF_8));
        try {
            byte[] ciphertext = BotgramE2eeVaultFile.aesGcm(Cipher.ENCRYPT_MODE, key, inner.toByteArray(),
                    ("botgram-unilateral-v1|" + conversation + "|" + self + "|" + peer + "|" + epoch + "|" + randomId)
                            .getBytes(StandardCharsets.UTF_8));
            return BotgramRpc.E2eeEnvelope.newBuilder().setProtocolVersion(1).setConversationId(conversation)
                    .setSenderUserId(self).setSenderDeviceId(device).setRecipientUserId(peer).setRecipientDeviceId(0)
                    .setKeyEpoch(epoch).setRandomId(randomId).setMessageType(MESSAGE_TYPE)
                    .setCiphertext(ByteString.copyFrom(ciphertext)).build();
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }

    public static boolean isSealedMessage(String wire) {
        try { return BotgramE2eeEnvelopeCodec.isEnvelope(wire)
                && BotgramE2eeEnvelopeCodec.unwrap(wire).getMessageType() == MESSAGE_TYPE; }
        catch (Exception invalid) { return false; }
    }
}
