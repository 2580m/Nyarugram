package org.telegram.botgram.e2ee;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.groups.state.SenderKeyStore;
import org.signal.libsignal.protocol.state.IdentityKeyStore;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyStore;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyStore;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SessionStore;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PIN-unlocked protocol state. This object only exists in memory while unlocked;
 * the encrypted vault file is written through the dirty callback after every mutation.
 */
public final class BotgramE2eeVault implements SignalProtocolStore, SenderKeyStore {
    private static final int MAGIC = 0x42474556;
    private static final int VERSION = 1;

    private IdentityKeyPair identityKeyPair;
    private int registrationId;
    private int deviceId = 1;
    private int nextPreKeyId = 1;
    private int nextSignedPreKeyId = 1;
    private int nextKyberPreKeyId = 1;
    private final HashMap<Integer, byte[]> preKeys = new HashMap<>();
    private final HashMap<Integer, byte[]> signedPreKeys = new HashMap<>();
    private final HashMap<Integer, byte[]> kyberPreKeys = new HashMap<>();
    private final HashMap<String, byte[]> sessions = new HashMap<>();
    private final HashMap<String, byte[]> identities = new HashMap<>();
    private Runnable dirty;

    private BotgramE2eeVault() {}

    public static BotgramE2eeVault create(IdentityKeyPair identityKeyPair, int registrationId, int deviceId) {
        BotgramE2eeVault vault = new BotgramE2eeVault();
        vault.identityKeyPair = identityKeyPair;
        vault.registrationId = registrationId;
        vault.deviceId = deviceId;
        return vault;
    }

    public static BotgramE2eeVault parse(byte[] bytes) throws IOException {
        BotgramE2eeVault vault = new BotgramE2eeVault();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("vault format");
            vault.identityKeyPair = new IdentityKeyPair(readBytes(in));
            vault.registrationId = in.readInt();
            vault.deviceId = in.readInt();
            vault.nextPreKeyId = in.readInt();
            vault.nextSignedPreKeyId = in.readInt();
            vault.nextKyberPreKeyId = in.readInt();
            readIntMap(in, vault.preKeys);
            readIntMap(in, vault.signedPreKeys);
            readIntMap(in, vault.kyberPreKeys);
            readStringMap(in, vault.sessions);
            readStringMap(in, vault.identities);
            if (in.read() != -1) throw new IOException("vault trailing data");
        } catch (Exception error) {
            throw new IOException("vault corrupt", error);
        }
        return vault;
    }

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeBytes(out, identityKeyPair.serialize());
            out.writeInt(registrationId);
            out.writeInt(deviceId);
            out.writeInt(nextPreKeyId);
            out.writeInt(nextSignedPreKeyId);
            out.writeInt(nextKyberPreKeyId);
            writeIntMap(out, preKeys);
            writeIntMap(out, signedPreKeys);
            writeIntMap(out, kyberPreKeys);
            writeStringMap(out, sessions);
            writeStringMap(out, identities);
        }
        return buffer.toByteArray();
    }

    public void setDirty(Runnable dirty) { this.dirty = dirty; }
    private void markDirty() { Runnable value = dirty; if (value != null) value.run(); }

    /** Isolated candidate state for one encrypt/decrypt transaction; mutations stay private. */
    public synchronized BotgramE2eeVault copyForTransaction() {
        BotgramE2eeVault copy = new BotgramE2eeVault();
        copy.identityKeyPair = identityKeyPair;
        copy.registrationId = registrationId;
        copy.deviceId = deviceId;
        copy.nextPreKeyId = nextPreKeyId;
        copy.nextSignedPreKeyId = nextSignedPreKeyId;
        copy.nextKyberPreKeyId = nextKyberPreKeyId;
        copy.preKeys.putAll(preKeys);
        copy.signedPreKeys.putAll(signedPreKeys);
        copy.kyberPreKeys.putAll(kyberPreKeys);
        copy.sessions.putAll(sessions);
        copy.identities.putAll(identities);
        return copy;
    }

    /** Publishes a fully committed candidate over the live historical state. */
    public synchronized void commitFrom(BotgramE2eeVault candidate) {
        if (candidate == this) return;
        identityKeyPair = candidate.identityKeyPair;
        registrationId = candidate.registrationId;
        deviceId = candidate.deviceId;
        nextPreKeyId = candidate.nextPreKeyId;
        nextSignedPreKeyId = candidate.nextSignedPreKeyId;
        nextKyberPreKeyId = candidate.nextKyberPreKeyId;
        preKeys.clear();
        preKeys.putAll(candidate.preKeys);
        signedPreKeys.clear();
        signedPreKeys.putAll(candidate.signedPreKeys);
        kyberPreKeys.clear();
        kyberPreKeys.putAll(candidate.kyberPreKeys);
        sessions.clear();
        sessions.putAll(candidate.sessions);
        identities.clear();
        identities.putAll(candidate.identities);
        markDirty();
    }

    public int deviceId() { return deviceId; }
    public int registrationId() { return registrationId; }
    public IdentityKeyPair identityKeyPair() { return identityKeyPair; }
    public IdentityKey identityPublic() { return identityKeyPair.getPublicKey(); }
    public byte[] identityPublicBytes() { return identityKeyPair.getPublicKey().serialize(); }

    public synchronized int allocatePreKeyId() { return nextPreKeyId++; }
    public synchronized int allocateSignedPreKeyId() { return nextSignedPreKeyId++; }
    public synchronized int allocateKyberPreKeyId() { return nextKyberPreKeyId++; }

    public synchronized void putPreKeyRecord(int id, PreKeyRecord record) {
        try {
            preKeys.put(id, record.serialize());
            markDirty();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public synchronized void putSignedPreKeyRecord(int id, SignedPreKeyRecord record) {
        signedPreKeys.put(id, record.serialize());
        markDirty();
    }

    public synchronized void putKyberPreKeyRecord(int id, KyberPreKeyRecord record) {
        kyberPreKeys.put(id, record.serialize());
        markDirty();
    }

    public synchronized void trustIdentity(String peerUuid, int remoteDeviceId, byte[] identityBytes) {
        identities.put(addressKey(peerUuid, remoteDeviceId), identityBytes);
        markDirty();
    }

    public synchronized void clearSession(String peerUuid) {
        sessions.keySet().removeIf(key -> key.startsWith(peerUuid + "|"));
        markDirty();
    }

    public synchronized boolean hasSession(String peerUuid, int remoteDeviceId) {
        return sessions.containsKey(addressKey(peerUuid, remoteDeviceId));
    }

    public synchronized byte[] peerIdentity(String peerUuid, int remoteDeviceId) {
        return identities.get(addressKey(peerUuid, remoteDeviceId));
    }

    public synchronized Map<Integer, byte[]> preKeyRecords() { return new HashMap<>(preKeys); }
    public synchronized Map<Integer, byte[]> signedPreKeyRecords() { return new HashMap<>(signedPreKeys); }
    public synchronized Map<Integer, byte[]> kyberPreKeyRecords() { return new HashMap<>(kyberPreKeys); }

    private static String addressKey(String name, int deviceId) { return name + "|" + deviceId; }

    @Override public synchronized IdentityKeyPair getIdentityKeyPair() { return identityKeyPair; }
    @Override public synchronized int getLocalRegistrationId() { return registrationId; }

    @Override
    public synchronized IdentityChange saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
        String key = addressKey(address.getName(), address.getDeviceId());
        byte[] existing = identities.get(key);
        byte[] value = identityKey.serialize();
        identities.put(key, value);
        markDirty();
        if (existing == null || java.util.Arrays.equals(existing, value)) return IdentityChange.NEW_OR_UNCHANGED;
        return IdentityChange.REPLACED_EXISTING;
    }

    @Override
    public synchronized boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
        byte[] existing = identities.get(addressKey(address.getName(), address.getDeviceId()));
        return existing == null || java.util.Arrays.equals(existing, identityKey.serialize());
    }

    @Override
    public synchronized IdentityKey getIdentity(SignalProtocolAddress address) {
        byte[] value = identities.get(addressKey(address.getName(), address.getDeviceId()));
        if (value == null) return null;
        try {
            return new IdentityKey(value);
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public synchronized PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
        byte[] value = preKeys.get(preKeyId);
        if (value == null) throw new InvalidKeyIdException("No such pre key: " + preKeyId);
        try {
            return new PreKeyRecord(value);
        } catch (InvalidMessageException error) {
            throw new InvalidKeyIdException(error);
        }
    }

    @Override
    public synchronized void storePreKey(int preKeyId, PreKeyRecord record) {
        preKeys.put(preKeyId, record.serialize());
        markDirty();
    }

    @Override public synchronized boolean containsPreKey(int preKeyId) { return preKeys.containsKey(preKeyId); }

    @Override
    public synchronized void removePreKey(int preKeyId) {
        preKeys.remove(preKeyId);
        markDirty();
    }

    @Override
    public synchronized SessionRecord loadSession(SignalProtocolAddress address) {
        byte[] value = sessions.get(addressKey(address.getName(), address.getDeviceId()));
        if (value == null) return new SessionRecord();
        try {
            return new SessionRecord(value);
        } catch (InvalidMessageException error) {
            throw new IllegalStateException("session corrupt", error);
        }
    }

    @Override
    public synchronized List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses)
            throws NoSessionException {
        ArrayList<SessionRecord> result = new ArrayList<>();
        for (SignalProtocolAddress address : addresses) {
            byte[] value = sessions.get(addressKey(address.getName(), address.getDeviceId()));
            if (value == null) throw new NoSessionException("No session for " + address);
            try {
                result.add(new SessionRecord(value));
            } catch (InvalidMessageException error) {
                throw new IllegalStateException(error);
            }
        }
        return result;
    }

    @Override
    public synchronized List<Integer> getSubDeviceSessions(String name) {
        ArrayList<Integer> result = new ArrayList<>();
        String prefix = name + "|";
        for (String key : sessions.keySet()) {
            if (key.startsWith(prefix)) {
                try {
                    result.add(Integer.parseInt(key.substring(prefix.length())));
                } catch (NumberFormatException ignored) { }
            }
        }
        return result;
    }

    @Override
    public synchronized void storeSession(SignalProtocolAddress address, SessionRecord record) {
        sessions.put(addressKey(address.getName(), address.getDeviceId()), record.serialize());
        markDirty();
    }

    @Override
    public synchronized boolean containsSession(SignalProtocolAddress address) {
        return sessions.containsKey(addressKey(address.getName(), address.getDeviceId()));
    }

    @Override
    public synchronized void deleteSession(SignalProtocolAddress address) {
        sessions.remove(addressKey(address.getName(), address.getDeviceId()));
        markDirty();
    }

    @Override
    public synchronized void deleteAllSessions(String name) {
        String prefix = name + "|";
        sessions.keySet().removeIf(key -> key.startsWith(prefix));
        markDirty();
    }

    @Override
    public synchronized SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
        byte[] value = signedPreKeys.get(signedPreKeyId);
        if (value == null) throw new InvalidKeyIdException("No such signed pre key: " + signedPreKeyId);
        try {
            return new SignedPreKeyRecord(value);
        } catch (InvalidMessageException error) {
            throw new InvalidKeyIdException(error);
        }
    }

    @Override
    public synchronized List<SignedPreKeyRecord> loadSignedPreKeys() {
        ArrayList<SignedPreKeyRecord> result = new ArrayList<>();
        for (byte[] value : signedPreKeys.values()) {
            try {
                result.add(new SignedPreKeyRecord(value));
            } catch (InvalidMessageException ignored) { }
        }
        return result;
    }

    @Override
    public synchronized void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
        signedPreKeys.put(signedPreKeyId, record.serialize());
        markDirty();
    }

    @Override public synchronized boolean containsSignedPreKey(int signedPreKeyId) { return signedPreKeys.containsKey(signedPreKeyId); }

    @Override
    public synchronized void removeSignedPreKey(int signedPreKeyId) {
        signedPreKeys.remove(signedPreKeyId);
        markDirty();
    }

    @Override
    public synchronized KyberPreKeyRecord loadKyberPreKey(int kyberPreKeyId) throws InvalidKeyIdException {
        byte[] value = kyberPreKeys.get(kyberPreKeyId);
        if (value == null) throw new InvalidKeyIdException("No such kyber pre key: " + kyberPreKeyId);
        try {
            return new KyberPreKeyRecord(value);
        } catch (InvalidMessageException error) {
            throw new InvalidKeyIdException(error);
        }
    }

    @Override
    public synchronized List<KyberPreKeyRecord> loadKyberPreKeys() {
        ArrayList<KyberPreKeyRecord> result = new ArrayList<>();
        for (byte[] value : kyberPreKeys.values()) {
            try {
                result.add(new KyberPreKeyRecord(value));
            } catch (InvalidMessageException ignored) { }
        }
        return result;
    }

    @Override
    public synchronized void storeKyberPreKey(int kyberPreKeyId, KyberPreKeyRecord record) {
        kyberPreKeys.put(kyberPreKeyId, record.serialize());
        markDirty();
    }

    @Override public synchronized boolean containsKyberPreKey(int kyberPreKeyId) { return kyberPreKeys.containsKey(kyberPreKeyId); }

    @Override
    public synchronized void markKyberPreKeyUsed(int kyberPreKeyId, int deviceId,
            org.signal.libsignal.protocol.ecc.ECPublicKey basePublicKey) throws ReusedBaseKeyException {
        if (kyberPreKeys.remove(kyberPreKeyId) == null) throw new ReusedBaseKeyException();
        markDirty();
    }

    @Override
    public synchronized void storeSenderKey(SignalProtocolAddress sender, UUID distributionId, SenderKeyRecord record) {
        throw new UnsupportedOperationException("sender keys are not used");
    }

    @Override
    public synchronized SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distributionId) {
        return null;
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1 << 20) throw new IOException("length");
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static void writeIntMap(DataOutputStream out, Map<Integer, byte[]> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<Integer, byte[]> entry : map.entrySet()) {
            out.writeInt(entry.getKey());
            writeBytes(out, entry.getValue());
        }
    }

    private static void readIntMap(DataInputStream in, Map<Integer, byte[]> map) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10000) throw new IOException("count");
        for (int i = 0; i < count; i++) map.put(in.readInt(), readBytes(in));
    }

    private static void writeStringMap(DataOutputStream out, Map<String, byte[]> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<String, byte[]> entry : map.entrySet()) {
            byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
            writeBytes(out, key);
            writeBytes(out, entry.getValue());
        }
    }

    private static void readStringMap(DataInputStream in, Map<String, byte[]> map) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10000) throw new IOException("count");
        for (int i = 0; i < count; i++) {
            byte[] key = readBytes(in);
            map.put(new String(key, StandardCharsets.UTF_8), readBytes(in));
        }
    }
}
