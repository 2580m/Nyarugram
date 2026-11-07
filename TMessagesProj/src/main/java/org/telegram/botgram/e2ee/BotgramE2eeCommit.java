package org.telegram.botgram.e2ee;

import java.util.LinkedHashMap;
import java.util.Map;
import org.telegram.botgram.protocol.v1.BotgramRpc;

/** Publishes candidate protocol state only after one recoverable storage transaction. */
public final class BotgramE2eeCommit {
    public interface TransactionPort {
        void persist(byte[] vaultBytes, Map<String, String> entries) throws Exception;
    }
    public interface EncryptOperation { SendResult run(BotgramE2eeVault candidate) throws Exception; }
    public static final class SendResult {
        public final BotgramRpc.E2eeEnvelope envelope;
        public final String text;
        public SendResult(BotgramRpc.E2eeEnvelope envelope, String text) { this.envelope = envelope; this.text = text; }
    }
    private BotgramE2eeCommit() {}

    public static SendResult send(BotgramE2eeVault live, TransactionPort store,
            String displayKey, EncryptOperation operation) throws Exception {
        BotgramE2eeVault candidate = live.copyForTransaction();
        SendResult result = operation.run(candidate);
        Map<String, String> entries = new LinkedHashMap<>();
        String wire = BotgramE2eeEnvelopeCodec.wrap(result.envelope);
        entries.put(displayKey, result.text);
        entries.put(displayKey + "|wire", wire); // Durable outbox for a retry of this random_id.
        entries.put(displayKey + "|" + BotgramE2eeHistory.hash(wire), result.text);
        store.persist(candidate.serialize(), entries);
        live.commitFrom(candidate);
        return result;
    }

    public static String receive(BotgramE2eeVault live, TransactionPort store,
            String displayKey, BotgramE2eeCrypto.Decrypted decrypted) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(displayKey, decrypted.text);
        store.persist(decrypted.candidate.serialize(), entries);
        live.commitFrom(decrypted.candidate);
        return decrypted.text;
    }
}
