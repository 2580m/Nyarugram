package org.telegram.botgram.e2ee;

import com.google.protobuf.ByteString;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;
import org.telegram.botgram.protocol.v1.BotgramRpc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Thin wrapper around libsignal; Botgram only owns identity binding and the outer envelope. */
public final class BotgramE2eeCrypto {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEVICE_ID = 1;
    public static final int PREKEY_BATCH = 30;

    private BotgramE2eeCrypto() {}

    public static BotgramE2eeVault newVault() {
        BotgramE2eeVault vault = BotgramE2eeVault.create(IdentityKeyPair.generate(),
                KeyHelper.generateRegistrationId(false), DEVICE_ID);
        addSignedPreKey(vault);
        addKyberPreKey(vault);
        addOneTimePreKeys(vault, PREKEY_BATCH);
        return vault;
    }

    public static void addSignedPreKey(BotgramE2eeVault vault) {
        int id = vault.allocateSignedPreKeyId();
        ECKeyPair pair = ECKeyPair.generate();
        byte[] signature = vault.identityKeyPair().getPrivateKey().calculateSignature(pair.getPublicKey().serialize());
        vault.putSignedPreKeyRecord(id, new SignedPreKeyRecord(id, System.currentTimeMillis(), pair, signature));
    }

    public static void addKyberPreKey(BotgramE2eeVault vault) {
        int id = vault.allocateKyberPreKeyId();
        KEMKeyPair pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
        byte[] signature = vault.identityKeyPair().getPrivateKey().calculateSignature(pair.getPublicKey().serialize());
        vault.putKyberPreKeyRecord(id, new KyberPreKeyRecord(id, System.currentTimeMillis(), pair, signature));
    }

    public static void addOneTimePreKeys(BotgramE2eeVault vault, int count) {
        for (int i = 0; i < count; i++) {
            int id = vault.allocatePreKeyId();
            vault.putPreKeyRecord(id, new PreKeyRecord(id, ECKeyPair.generate()));
        }
    }

    public static BotgramRpc.E2eePublishDeviceRequest publishRequest(BotgramE2eeVault vault) {
        BotgramRpc.E2eePublishDeviceRequest.Builder out = BotgramRpc.E2eePublishDeviceRequest.newBuilder()
                .setDeviceId(vault.deviceId())
                .setRegistrationId(vault.registrationId())
                .setIdentityKey(ByteString.copyFrom(vault.identityPublicBytes()));
        Map<Integer, byte[]> signed = vault.signedPreKeyRecords();
        if (signed.isEmpty()) throw new IllegalStateException("missing signed pre key");
        int signedId = signed.keySet().stream().max(Integer::compareTo).orElseThrow();
        try {
            SignedPreKeyRecord record = new SignedPreKeyRecord(signed.get(signedId));
            out.setSignedPreKeyId(signedId)
                    .setSignedPreKey(ByteString.copyFrom(record.getKeyPair().getPublicKey().serialize()))
                    .setSignedPreKeySignature(ByteString.copyFrom(record.getSignature()));
        } catch (Exception error) {
            throw new IllegalStateException("signed pre key corrupt", error);
        }
        Map<Integer, byte[]> kyber = vault.kyberPreKeyRecords();
        if (kyber.isEmpty()) throw new IllegalStateException("missing kyber pre key");
        int kyberId = kyber.keySet().stream().max(Integer::compareTo).orElseThrow();
        try {
            KyberPreKeyRecord record = new KyberPreKeyRecord(kyber.get(kyberId));
            out.setKyberPreKeyId(kyberId)
                    .setKyberPreKey(ByteString.copyFrom(record.getKeyPair().getPublicKey().serialize()))
                    .setKyberPreKeySignature(ByteString.copyFrom(record.getSignature()));
        } catch (Exception error) {
            throw new IllegalStateException("kyber pre key corrupt", error);
        }
        for (Map.Entry<Integer, byte[]> entry : vault.preKeyRecords().entrySet()) {
            try {
                PreKeyRecord record = new PreKeyRecord(entry.getValue());
                out.addOneTimePreKeys(BotgramRpc.E2eeOneTimePreKey.newBuilder()
                        .setKeyId(entry.getKey())
                        .setPublicKey(ByteString.copyFrom(record.getKeyPair().getPublicKey().serialize())));
            } catch (Exception error) {
                throw new IllegalStateException("pre key corrupt", error);
            }
        }
        return out.build();
    }

    public static PreKeyBundle bundle(BotgramRpc.E2eePeerBundle input) throws Exception {
        return new PreKeyBundle(input.getRegistrationId(), (int) input.getDeviceId(), input.getPreKeyId(),
                new ECPublicKey(input.getPreKey().toByteArray()), input.getSignedPreKeyId(),
                new ECPublicKey(input.getSignedPreKey().toByteArray()),
                input.getSignedPreKeySignature().toByteArray(),
                new IdentityKey(input.getIdentityKey().toByteArray()), input.getKyberPreKeyId(),
                new KEMPublicKey(input.getKyberPreKey().toByteArray()),
                input.getKyberPreKeySignature().toByteArray());
    }

    public static void prepareSession(BotgramE2eeVault vault, String peerUuid, BotgramRpc.E2eePeerBundle input,
            boolean trustReplacement) throws Exception {
        prepareSession(vault, peerUuid, input, null, 0, trustReplacement);
    }

    /**
     * Establishes (or explicitly replaces) a session. When {@code expectedIdentity} is set,
     * the bundle must still describe the exact device identity and generation the user
     * confirmed; a later replacement is rejected instead of being silently trusted.
     */
    public static void prepareSession(BotgramE2eeVault vault, String peerUuid, BotgramRpc.E2eePeerBundle input,
            byte[] expectedIdentity, int expectedGeneration, boolean trustReplacement) throws Exception {
        byte[] bundleIdentity = input.getIdentityKey().toByteArray();
        if (expectedIdentity != null && (!java.util.Arrays.equals(expectedIdentity, bundleIdentity)
                || expectedGeneration != input.getGeneration()))
            throw new IdentityChangedException();
        SignalProtocolAddress address = new SignalProtocolAddress(peerUuid, (int) input.getDeviceId());
        if (trustReplacement) {
            vault.trustIdentity(peerUuid, (int) input.getDeviceId(), bundleIdentity);
            vault.deleteSession(address);
        }
        synchronized (vault) {
            new SessionBuilder(vault, address).process(bundle(input));
        }
    }

    public static final class IdentityChangedException extends Exception {
        IdentityChangedException() { super("identity changed"); }
    }

    public static BotgramRpc.E2eeEnvelope encrypt(BotgramE2eeVault vault, String ownUuid, String conversationId,
            String peerUuid, int peerDeviceId, long epoch, long randomId, String text) throws Exception {
        SignalProtocolAddress address = new SignalProtocolAddress(peerUuid, peerDeviceId);
        BotgramRpc.E2eeInner inner = BotgramRpc.E2eeInner.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION).setConversationId(conversationId)
                .setSenderUserId(ownUuid).setSenderDeviceId(vault.deviceId())
                .setRecipientUserId(peerUuid).setRecipientDeviceId(peerDeviceId)
                .setKeyEpoch(epoch).setRandomId(randomId)
                .setTimestampMs(System.currentTimeMillis()).setText(text).build();
        CiphertextMessage message;
        synchronized (vault) {
            message = new SessionCipher(vault, address).encrypt(inner.toByteArray());
        }
        return BotgramRpc.E2eeEnvelope.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION).setConversationId(conversationId)
                .setSenderUserId(ownUuid).setSenderDeviceId(vault.deviceId())
                .setRecipientUserId(peerUuid).setRecipientDeviceId(peerDeviceId)
                .setKeyEpoch(epoch).setRandomId(randomId)
                .setCiphertext(ByteString.copyFrom(message.serialize()))
                .setMessageType(message.getType()).build();
    }

    public static final class Decrypted {
        public final String text;
        public final String senderUserId;
        public final String conversationId;
        public final long epoch;
        public final long randomId;
        /** Candidate protocol state; commit only after history and vault persistence succeed. */
        public final BotgramE2eeVault candidate;
        Decrypted(String text, String senderUserId, String conversationId, long epoch, long randomId,
                BotgramE2eeVault candidate) {
            this.text = text;
            this.senderUserId = senderUserId;
            this.conversationId = conversationId;
            this.epoch = epoch;
            this.randomId = randomId;
            this.candidate = candidate;
        }
    }

    /** Routing context that an envelope must match before the ratchet is touched. */
    public static final class DecryptContext {
        public final String ownUuid;
        public final String conversationId;
        public final String peerUuid;
        public final int peerDeviceId;
        public final long maxEpoch;
        public DecryptContext(String ownUuid, String conversationId, String peerUuid, int peerDeviceId,
                long maxEpoch) {
            this.ownUuid = ownUuid;
            this.conversationId = conversationId;
            this.peerUuid = peerUuid;
            this.peerDeviceId = peerDeviceId;
            this.maxEpoch = maxEpoch;
        }
    }

    public static Decrypted decrypt(BotgramE2eeVault vault, byte[] envelopeBytes, DecryptContext context)
            throws Exception {
        if (envelopeBytes == null || envelopeBytes.length > 1 << 20)
            throw new BotgramE2eeException("E2EE_ENVELOPE_SIZE_INVALID");
        BotgramRpc.E2eeEnvelope envelope;
        try {
            envelope = BotgramRpc.E2eeEnvelope.parseFrom(envelopeBytes);
        } catch (Exception error) {
            throw new BotgramE2eeException("E2EE_ENVELOPE_INVALID");
        }
        // Every field that can be validated without touching protocol state is validated first.
        if (envelope.getProtocolVersion() != PROTOCOL_VERSION
                || envelope.getRecipientUserId().isEmpty()
                || !envelope.getRecipientUserId().equals(context.ownUuid)
                || envelope.getRecipientDeviceId() != vault.deviceId()
                || envelope.getSenderUserId().isEmpty()
                || envelope.getSenderDeviceId() <= 0
                || envelope.getConversationId().isEmpty()
                || !envelope.getConversationId().equals(context.conversationId)
                || !envelope.getSenderUserId().equals(context.peerUuid)
                || envelope.getSenderDeviceId() != context.peerDeviceId
                || envelope.getKeyEpoch() < 0
                || (context.maxEpoch >= 0 && envelope.getKeyEpoch() > context.maxEpoch)
                || envelope.getCiphertext().isEmpty()
                || envelope.getCiphertext().size() > 65536)
            throw new BotgramE2eeException("E2EE_ENVELOPE_CONTEXT_INVALID");
        int type = (int) envelope.getMessageType();
        if (type != CiphertextMessage.PREKEY_TYPE && type != CiphertextMessage.WHISPER_TYPE)
            throw new BotgramE2eeException("E2EE_MESSAGE_TYPE_INVALID");

        // Decrypt against a candidate copy: a failed inner/context check consumes neither a
        // pre-key nor any ratchet state, and the caller commits only after persistence.
        BotgramE2eeVault candidate = vault.copyForTransaction();
        SignalProtocolAddress address = new SignalProtocolAddress(envelope.getSenderUserId(),
                (int) envelope.getSenderDeviceId());
        byte[] plaintext;
        synchronized (candidate) {
            SessionCipher cipher = new SessionCipher(candidate, address);
            if (type == CiphertextMessage.PREKEY_TYPE) {
                plaintext = cipher.decrypt(new PreKeySignalMessage(envelope.getCiphertext().toByteArray()));
            } else {
                plaintext = cipher.decrypt(new SignalMessage(envelope.getCiphertext().toByteArray()));
            }
        }
        BotgramRpc.E2eeInner inner;
        try {
            inner = BotgramRpc.E2eeInner.parseFrom(plaintext);
        } catch (Exception error) {
            throw new BotgramE2eeException("E2EE_INNER_INVALID");
        }
        if (inner.getProtocolVersion() != PROTOCOL_VERSION ||
                !inner.getConversationId().equals(envelope.getConversationId()) ||
                !inner.getSenderUserId().equals(envelope.getSenderUserId()) ||
                inner.getSenderDeviceId() != envelope.getSenderDeviceId() ||
                !inner.getRecipientUserId().equals(context.ownUuid) ||
                inner.getRecipientDeviceId() != envelope.getRecipientDeviceId() ||
                inner.getKeyEpoch() != envelope.getKeyEpoch() ||
                inner.getRandomId() != envelope.getRandomId() ||
                inner.getText().isEmpty() || inner.getText().length() > 16000)
            throw new BotgramE2eeException("E2EE_INNER_CONTEXT_INVALID");
        return new Decrypted(inner.getText(), inner.getSenderUserId(), inner.getConversationId(),
                inner.getKeyEpoch(), inner.getRandomId(), candidate);
    }

    public static String safetyNumber(BotgramE2eeVault vault, String ownUuid, String peerUuid, int peerDeviceId) {
        byte[] peer = vault.peerIdentity(peerUuid, peerDeviceId);
        if (peer == null) return "";
        Fingerprint fingerprint = new NumericFingerprintGenerator(2).createFor(1,
                ownUuid.getBytes(StandardCharsets.UTF_8), vault.identityPublic(),
                peerUuid.getBytes(StandardCharsets.UTF_8), new IdentityKeyUnchecked(peer).key);
        return fingerprint.getDisplayableFingerprint().getDisplayText();
    }

    private static final class IdentityKeyUnchecked {
        final IdentityKey key;
        IdentityKeyUnchecked(byte[] bytes) {
            try {
                key = new IdentityKey(bytes);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
    }

    public static final class BotgramE2eeException extends Exception {
        BotgramE2eeException(String code) { super(code); }
    }
}

