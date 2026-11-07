package org.telegram.botgram.e2ee;

import com.google.protobuf.ByteString;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.telegram.botgram.protocol.v1.BotgramRpc;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

/** Host regression for the Android-independent E2EE core. */
public final class BotgramE2eeCoreTest {
    private static int passed;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++;
    }

    private static final class FixedDevice implements BotgramE2eeVaultFile.DeviceCipher {
        private final SecretKeySpec key;

        FixedDevice() {
            this(new byte[]{
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        }

        FixedDevice(byte[] value) {
            key = new SecretKeySpec(value, "AES");
        }

        public byte[] encrypt(byte[] plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] value = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + value.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(value, 0, out, iv.length, value.length);
            return out;
        }

        public byte[] decrypt(byte[] payload) throws Exception {
            byte[] iv = new byte[12];
            System.arraycopy(payload, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] value = new byte[payload.length - 12];
            System.arraycopy(payload, 12, value, 0, value.length);
            return cipher.doFinal(value);
        }
    }

    private static final class MissingDevice implements BotgramE2eeVaultFile.DeviceCipher {
        public byte[] encrypt(byte[] plaintext) throws Exception {
            throw new java.io.IOException("device key missing");
        }
        public byte[] decrypt(byte[] payload) throws Exception {
            throw new java.io.IOException("device key missing");
        }
    }

    private static BotgramRpc.E2eePeerBundle bundleFor(BotgramE2eeVault vault) throws Exception {
        Map<Integer, byte[]> signed = vault.signedPreKeyRecords();
        int signedId = signed.keySet().stream().max(Integer::compareTo).orElseThrow();
        SignedPreKeyRecord spk = new SignedPreKeyRecord(signed.get(signedId));
        Map<Integer, byte[]> kyber = vault.kyberPreKeyRecords();
        int kyberId = kyber.keySet().stream().max(Integer::compareTo).orElseThrow();
        KyberPreKeyRecord kpk = new KyberPreKeyRecord(kyber.get(kyberId));
        Map<Integer, byte[]> pre = vault.preKeyRecords();
        int preId = pre.keySet().stream().min(Integer::compareTo).orElseThrow();
        PreKeyRecord pk = new PreKeyRecord(pre.get(preId));
        byte[] identity = vault.identityPublicBytes();
        return BotgramRpc.E2eePeerBundle.newBuilder()
                .setDeviceId(1).setRegistrationId(vault.registrationId())
                .setPreKeyId(preId).setPreKey(ByteString.copyFrom(pk.getKeyPair().getPublicKey().serialize()))
                .setSignedPreKeyId(signedId).setSignedPreKey(ByteString.copyFrom(spk.getKeyPair().getPublicKey().serialize()))
                .setSignedPreKeySignature(ByteString.copyFrom(spk.getSignature()))
                .setIdentityKey(ByteString.copyFrom(identity))
                .setKyberPreKeyId(kyberId).setKyberPreKey(ByteString.copyFrom(kpk.getKeyPair().getPublicKey().serialize()))
                .setKyberPreKeySignature(ByteString.copyFrom(kpk.getSignature()))
                .setGeneration(1)
                .build();
    }

    private static byte[] flip(byte[] input) {
        byte[] copy = input.clone();
        copy[copy.length / 2] ^= 1;
        return copy;
    }

    public static void main(String[] args) throws Exception {
        kdf();
        vaultWrapping();
        legacyMigration();
        messageFlow();
        contextHasNoSideEffects();
        commitBoundaries();
        historyBeyondMemoryLimit();
        sessionPolicySequence();
        sendPolicySequence();
        unilateralMessages();
        sessionGateSequence();
        atomicReplacement();
        System.out.println("Botgram E2EE core checks passed: " + passed);
    }

    private static void kdf() throws Exception {
        // RFC 8018 PBKDF2-HMAC-SHA256 known vectors.
        String pin = "password";
        byte[] salt = "salt".getBytes(StandardCharsets.UTF_8);
        check(hex(BotgramE2eeVaultFile.deriveFallback(pin, salt, 1)).equals(
                "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"), "PBKDF2 c=1 vector");
        check(hex(BotgramE2eeVaultFile.deriveFallback(pin, salt, 2)).equals(
                "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"), "PBKDF2 c=2 vector");
        check(hex(BotgramE2eeVaultFile.deriveFallback(pin, salt, 4096)).equals(
                "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"), "PBKDF2 c=4096 vector");
        SecureRandom random = new SecureRandom();
        byte[] randomSalt = new byte[16];
        random.nextBytes(randomSalt);
        check(Arrays.equals(BotgramE2eeVaultFile.derive("0123", randomSalt, 120_000),
                BotgramE2eeVaultFile.deriveFallback("0123", randomSalt, 120_000)),
                "platform PBKDF2 matches fallback for leading zero PIN");
    }

    private static void vaultWrapping() throws Exception {
        BotgramE2eeVault seeded = BotgramE2eeCrypto.newVault();
        byte[] vaultBytes = seeded.serialize();
        byte[] file = BotgramE2eeVaultFile.create("0123", vaultBytes, new FixedDevice());
        BotgramE2eeVaultFile.Vault open = BotgramE2eeVaultFile.open("0123", file, new FixedDevice());
        check(open.migrated == null, "new file is version 2");
        check(Arrays.equals(vaultBytes, open.bytes), "PIN plus device restores vault");
        try {
            BotgramE2eeVaultFile.open("1234", file, new FixedDevice());
            throw new AssertionError("wrong PIN accepted");
        } catch (BotgramE2eeVaultFile.PinFailure expected) {
            passed++;
        }
        try {
            BotgramE2eeVaultFile.open("0123", file, new MissingDevice());
            throw new AssertionError("PIN alone bypassed the device layer");
        } catch (BotgramE2eeVaultFile.DeviceFailure expected) {
            passed++;
        }
        byte[] changed = BotgramE2eeVaultFile.changePin("0123", "0000", file, new FixedDevice());
        check(Arrays.equals(vaultBytes, BotgramE2eeVaultFile.open("0000", changed, new FixedDevice()).bytes),
                "PIN change keeps vault");
        byte[] rewrapped = BotgramE2eeVaultFile.rewrapPin("9999", changed, open.vaultKey, new FixedDevice());
        check(Arrays.equals(vaultBytes, BotgramE2eeVaultFile.open("9999", rewrapped, new FixedDevice()).bytes),
                "unlocked rewrap keeps vault");
        try {
            BotgramE2eeVaultFile.open("0000", rewrapped, new FixedDevice());
            throw new AssertionError("old PIN accepted after rewrap");
        } catch (BotgramE2eeVaultFile.PinFailure expected) {
            passed++;
        }
        check(!BotgramE2eeVaultFile.validPin("12a4"), "PIN digits only");
        zero(open.vaultKey);
    }

    private static void legacyMigration() throws Exception {
        BotgramE2eeVault seeded = BotgramE2eeCrypto.newVault();
        byte[] vaultBytes = seeded.serialize();
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] vaultKey = new byte[32];
        new SecureRandom().nextBytes(vaultKey);
        byte[] pinKey = legacyDerive("0123", salt, 120_000);
        byte[] pinBlob = BotgramE2eeVaultFile.aesGcm(Cipher.ENCRYPT_MODE, pinKey,
                vaultKey, "botgram-e2ee-pin".getBytes(StandardCharsets.UTF_8));
        byte[] deviceBlob = new FixedDevice().encrypt(vaultKey);
        byte[] vaultBlob = BotgramE2eeVaultFile.aesGcm(Cipher.ENCRYPT_MODE, vaultKey,
                vaultBytes, "botgram-e2ee-vault".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(0x42474532);
            out.writeInt(1);
            out.writeInt(120_000);
            out.write(salt);
            writeBytes(out, deviceBlob);
            writeBytes(out, pinBlob);
            writeBytes(out, vaultBlob);
        }
        byte[] legacy = buffer.toByteArray();
        BotgramE2eeVaultFile.Vault opened = BotgramE2eeVaultFile.open("0123", legacy, new FixedDevice());
        check(Arrays.equals(vaultBytes, opened.bytes), "legacy file opens with both layers");
        check(opened.migrated != null, "legacy file offers a version 2 rewrite");
        byte[] migrated = opened.migrated;
        check(Arrays.equals(vaultBytes, BotgramE2eeVaultFile.open("0123", migrated, new FixedDevice()).bytes),
                "migrated file keeps protocol state");
        try {
            BotgramE2eeVaultFile.open("0123", legacy, new MissingDevice());
            throw new AssertionError("legacy PIN alone bypassed device layer");
        } catch (BotgramE2eeVaultFile.DeviceFailure expected) {
            passed++;
        }
        zero(pinKey);
        zero(vaultKey);
        zero(opened.vaultKey);
    }

    private static void messageFlow() throws Exception {
        BotgramE2eeVault alice = BotgramE2eeCrypto.newVault();
        BotgramE2eeVault bob = BotgramE2eeCrypto.newVault();
        BotgramE2eeCrypto.prepareSession(alice, "bob-uuid", bundleFor(bob), false);
        BotgramRpc.E2eeEnvelope out = BotgramE2eeCrypto.encrypt(alice, "alice-uuid", "conv-1",
                "bob-uuid", 1, 0L, 42L, "hello e2ee");
        String wire = BotgramE2eeEnvelopeCodec.wrap(out);
        check(BotgramE2eeEnvelopeCodec.isEnvelope(wire), "marker");
        BotgramE2eeCrypto.Decrypted in = BotgramE2eeCrypto.decrypt(bob,
                BotgramE2eeEnvelopeCodec.unwrapBytes(wire), context("bob-uuid", "alice-uuid", 0));
        check("hello e2ee".equals(in.text), "decrypt text");
        check(in.randomId == 42L && in.epoch == 0L, "inner metadata");
        bob.commitFrom(in.candidate);

        String aliceSafety = BotgramE2eeCrypto.safetyNumber(alice, "alice-uuid", "bob-uuid", 1);
        String bobSafety = BotgramE2eeCrypto.safetyNumber(bob, "bob-uuid", "alice-uuid", 1);
        check(aliceSafety.equals(bobSafety) && aliceSafety.length() > 30, "safety numbers match");

        BotgramRpc.E2eeEnvelope tampered = out.toBuilder()
                .setCiphertext(ByteString.copyFrom(flip(out.getCiphertext().toByteArray()))).build();
        try {
            BotgramE2eeCrypto.decrypt(bob, tampered.toByteArray(), context("bob-uuid", "alice-uuid", 0));
            throw new AssertionError("tampered ciphertext accepted");
        } catch (Exception expected) {
            passed++;
        }
        try {
            BotgramE2eeCrypto.decrypt(bob, out.toByteArray(), context("carol-uuid", "alice-uuid", 0));
            throw new AssertionError("wrong recipient accepted");
        } catch (Exception expected) {
            passed++;
        }
        try {
            BotgramE2eeCrypto.decrypt(bob, out.toByteArray(), context("bob-uuid", "alice-uuid", 0));
            throw new AssertionError("replay accepted");
        } catch (Exception expected) {
            passed++;
        }

        BotgramE2eeVault aliceReloaded = BotgramE2eeVault.parse(alice.serialize());
        BotgramRpc.E2eeEnvelope second = BotgramE2eeCrypto.encrypt(aliceReloaded, "alice-uuid", "conv-1",
                "bob-uuid", 1, 0L, 43L, "second");
        BotgramE2eeCrypto.Decrypted secondIn = BotgramE2eeCrypto.decrypt(bob, second.toByteArray(),
                context("bob-uuid", "alice-uuid", 0));
        check("second".equals(secondIn.text), "session survives serialize/parse");
        bob.commitFrom(secondIn.candidate);

        BotgramE2eeVault carol = BotgramE2eeCrypto.newVault();
        BotgramRpc.E2eePeerBundle carolBundle = bundleFor(carol);
        try {
            BotgramE2eeCrypto.prepareSession(aliceReloaded, "bob-uuid", carolBundle, false);
            throw new AssertionError("replaced identity silently trusted");
        } catch (UntrustedIdentityException expected) {
            passed++;
        }
        BotgramE2eeCrypto.prepareSession(aliceReloaded, "bob-uuid", carolBundle, true);
        BotgramRpc.E2eeEnvelope afterTrust = BotgramE2eeCrypto.encrypt(aliceReloaded, "alice-uuid", "conv-1",
                "bob-uuid", 1, 0L, 44L, "after trust");
        BotgramE2eeCrypto.Decrypted afterIn = BotgramE2eeCrypto.decrypt(carol, afterTrust.toByteArray(),
                context("bob-uuid", "alice-uuid", 0));
        check("after trust".equals(afterIn.text), "confirmed identity establishes a new session");
    }

    private static void contextHasNoSideEffects() throws Exception {
        BotgramE2eeVault alice = BotgramE2eeCrypto.newVault();
        BotgramE2eeVault bob = BotgramE2eeCrypto.newVault();
        BotgramE2eeCrypto.prepareSession(alice, "bob-uuid", bundleFor(bob), false);
        BotgramRpc.E2eeEnvelope out = BotgramE2eeCrypto.encrypt(alice, "alice-uuid", "conv-1",
                "bob-uuid", 1, 0L, 50L, "no side effects");
        byte[] before = bob.serialize();
        BotgramRpc.E2eeEnvelope wrongConversation = out.toBuilder().setConversationId("conv-2").build();
        try {
            BotgramE2eeCrypto.decrypt(bob, wrongConversation.toByteArray(),
                    context("bob-uuid", "alice-uuid", 0));
            throw new AssertionError("replaced outer conversation accepted");
        } catch (Exception expected) {
            passed++;
        }
        check(Arrays.equals(before, bob.serialize()), "outer context failure leaves vault unchanged");
        BotgramE2eeCrypto.Decrypted valid = BotgramE2eeCrypto.decrypt(bob, out.toByteArray(),
                context("bob-uuid", "alice-uuid", 0));
        check("no side effects".equals(valid.text), "original envelope still decrypts after rejection");
        bob.commitFrom(valid.candidate);
        try {
            BotgramE2eeCrypto.decrypt(bob, out.toByteArray(), context("bob-uuid", "alice-uuid", 0));
            throw new AssertionError("replay accepted after valid commit");
        } catch (Exception expected) {
            passed++;
        }
    }

    private static void commitBoundaries() throws Exception {
        BotgramE2eeVault alice = BotgramE2eeCrypto.newVault(), bob = BotgramE2eeCrypto.newVault();
        BotgramE2eeCrypto.prepareSession(alice, "bob-uuid", bundleFor(bob), false);
        byte[] before = alice.serialize();
        BotgramE2eeCommit.TransactionPort broken = (bytes, entries) -> { throw new java.io.IOException("disk full"); };
        try {
            BotgramE2eeCommit.send(alice, broken, "conv-1|alice-uuid|60", candidate ->
                    new BotgramE2eeCommit.SendResult(BotgramE2eeCrypto.encrypt(candidate, "alice-uuid", "conv-1",
                            "bob-uuid", 1, 0, 60, "cannot commit"), "cannot commit"));
            throw new AssertionError("failed transaction accepted");
        } catch (java.io.IOException expected) { passed++; }
        check(Arrays.equals(before, alice.serialize()), "failed send leaves live state unchanged");
        BotgramE2eeCommit.SendResult sent = BotgramE2eeCommit.send(alice, (bytes, entries) -> {},
                "conv-1|alice-uuid|62", candidate -> new BotgramE2eeCommit.SendResult(
                        BotgramE2eeCrypto.encrypt(candidate, "alice-uuid", "conv-1", "bob-uuid", 1, 0, 62, "committed"), "committed"));
        BotgramE2eeCrypto.Decrypted received = BotgramE2eeCrypto.decrypt(bob, sent.envelope.toByteArray(), context("bob-uuid", "alice-uuid", 0));
        byte[] original = bob.serialize();
        try { BotgramE2eeCommit.receive(bob, broken, "key", received); throw new AssertionError("receive accepted"); }
        catch (java.io.IOException expected) { passed++; }
        check(Arrays.equals(original, bob.serialize()), "receive failure leaves live state unchanged");
        for (String stage : new String[]{"intent", "history", "vault"}) {
            java.io.File dir = Files.createTempDirectory("e2ee-redo").toFile();
            java.io.File target = new java.io.File(dir, "vault.bin"), display = new java.io.File(dir, "display.bin");
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            BotgramE2eeAtomicFile.write(target, new byte[]{1});
            BotgramE2eeHistory history = BotgramE2eeHistory.open(display, key);
            BotgramE2eeJournal journal = new BotgramE2eeJournal(target, history, key,
                    step -> { if (step.equals(stage)) throw new java.io.IOException("power loss"); });
            try { journal.commit(new byte[]{2}, java.util.Collections.singletonMap("first", "text"));
                throw new AssertionError("fault not reached"); }
            catch (java.io.IOException expected) { passed++; }
            try { history.get("first"); throw new AssertionError("uncommitted history exposed"); }
            catch (IllegalStateException expected) { passed++; }
            history.close();
            BotgramE2eeHistory reopened = BotgramE2eeHistory.open(display, key);
            check(new BotgramE2eeJournal(target, reopened, key).recover(), "redo found at " + stage);
            check(Arrays.equals(new byte[]{2}, Files.readAllBytes(target.toPath())), "vault recovered at " + stage);
            check("text".equals(reopened.get("first")), "history recovered at " + stage);
            check(!new BotgramE2eeJournal(target, reopened, key).recover(), "redo idempotent at " + stage);
            reopened.close();
        }
        historyRecovery();
    }

    private static void historyRecovery() throws Exception {
        java.io.File dir = Files.createTempDirectory("e2ee-tail").toFile();
        java.io.File file = new java.io.File(dir, "display.bin");
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        BotgramE2eeHistory h = BotgramE2eeHistory.open(file, key);
        h.put("before", "one"); h.close();
        try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(file, "rw")) {
            out.seek(out.length()); out.writeInt(12345); out.write(new byte[]{1,2,3});
        }
        h = BotgramE2eeHistory.open(file, key); h.put("after", "two"); h.close();
        h = BotgramE2eeHistory.open(file, key);
        check("one".equals(h.get("before")), "tail recovery keeps earlier records");
        check("two".equals(h.get("after")), "append after tail survives restart");
        h.compact(); h.close(); h = BotgramE2eeHistory.open(file, key);
        check("two".equals(h.get("after")), "complete compacted history reopens"); h.close();
        // Reproduce the real old key format, not a synthetic new-format name.
        String wire = "synthetic-original-envelope";
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(plain)) {
            out.writeInt(1); writeString(out, BotgramE2eeHistory.hash(wire)); writeString(out, "legacy text");
        }
        java.io.File legacy = new java.io.File(dir, "legacy.bin");
        BotgramE2eeAtomicFile.write(legacy, BotgramE2eeVaultFile.aesGcm(Cipher.ENCRYPT_MODE, key, plain.toByteArray(),
                "botgram-e2ee-display".getBytes(StandardCharsets.UTF_8)));
        h = BotgramE2eeHistory.open(legacy, key);
        check("legacy text".equals(h.getLegacy(wire)), "real legacy envelope hash is readable"); h.close();
        h = BotgramE2eeHistory.open(legacy, key);
        check("legacy text".equals(h.getLegacy(wire)), "legacy alias survives restart"); h.close();
        // A damaged authenticated record must never silently disappear during compaction.
        h = BotgramE2eeHistory.open(file, key);
        try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(file, "rw")) {
            out.seek(28); int value = out.readUnsignedByte(); out.seek(28); out.writeByte(value ^ 1);
        }
        byte[] damaged = Files.readAllBytes(file.toPath());
        try { h.compact(); throw new AssertionError("corrupt record compacted away"); }
        catch (Exception expected) { passed++; }
        check(Arrays.equals(damaged, Files.readAllBytes(file.toPath())), "failed compaction preserves source"); h.close();
        // Recover an interrupted two-rename replacement on file systems without overwrite rename.
        java.io.File backup = new java.io.File(file.getPath() + ".bak");
        check(file.renameTo(backup), "simulate interrupted atomic replace");
        BotgramE2eeAtomicFile.recover(file);
        check(Arrays.equals(damaged, Files.readAllBytes(file.toPath())), "replacement backup restored");
    }

    private static void historyBeyondMemoryLimit() throws Exception {
        java.io.File directory = Files.createTempDirectory("botgram-e2ee-history").toFile();
        java.io.File file = new java.io.File(directory, "display.bin");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        BotgramE2eeHistory history = BotgramE2eeHistory.open(file, key);
        for (int i = 0; i < 6000; i++) history.put("conv|peer|" + i, "text-" + i);
        history.close();
        BotgramE2eeHistory reopened = BotgramE2eeHistory.open(file, key);
        check("text-0".equals(reopened.get("conv|peer|0")), "oldest history survives 6000 appends");
        check("text-5999".equals(reopened.get("conv|peer|5999")), "newest history reloads");
        check(reopened.get("conv|peer|2500") != null, "middle history is pageable");
        check(reopened.get("conv|peer|unknown") == null, "missing key stays missing");
        reopened.close();
        check(file.length() > 0, "history remains encrypted on disk");

        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(plain)) {
            out.writeInt(1);
            writeString(out, "conv|peer|1");
            writeString(out, "legacy text");
        }
        byte[] sealed = BotgramE2eeVaultFile.aesGcm(Cipher.ENCRYPT_MODE, key, plain.toByteArray(),
                "botgram-e2ee-display".getBytes(StandardCharsets.UTF_8));
        java.io.File legacyFile = new java.io.File(directory, "legacy.bin");
        BotgramE2eeAtomicFile.write(legacyFile, sealed);
        BotgramE2eeHistory migrated = BotgramE2eeHistory.open(legacyFile, key);
        check("legacy text".equals(migrated.get("conv|peer|1")), "old whole-file display cache migrates");
        migrated.close();
    }

    private static void sessionPolicySequence() {
        byte[] oldIdentity = new byte[]{1, 2, 3};
        byte[] newIdentity = new byte[]{4, 5, 6};
        check(BotgramE2eeSessionPolicy.decide(true, oldIdentity, 1, oldIdentity, 1, null)
                == BotgramE2eeSessionPolicy.Decision.REUSE, "matching session is reused");
        check(BotgramE2eeSessionPolicy.decide(true, oldIdentity, 1, newIdentity, 2, null)
                == BotgramE2eeSessionPolicy.Decision.REQUIRE_CONFIRMATION,
                "replaced identity pauses existing session");
        BotgramE2eeSessionPolicy.Replacement stale = new BotgramE2eeSessionPolicy.Replacement(newIdentity, 2);
        BotgramRpc.E2eeChatStatus firstConfirmation = BotgramRpc.E2eeChatStatus.newBuilder()
                .setState(BotgramRpc.E2eeState.E2EE_SUSPENDED).setSelfConfirmedIdentity(true)
                .setPeerGeneration(2).setPeerIdentityKey(ByteString.copyFrom(newIdentity)).build();
        check(BotgramE2eeSessionPolicy.acceptsConfirmation(stale, firstConfirmation), "first confirmer is retained while suspended");
        check(BotgramE2eeSessionPolicy.acceptsConfirmation(stale,
                firstConfirmation.toBuilder().setState(BotgramRpc.E2eeState.E2EE_ACTIVE).build()), "last confirmer is retained when active");
        check(!BotgramE2eeSessionPolicy.acceptsConfirmation(stale,
                firstConfirmation.toBuilder().setPeerGeneration(3).build()), "confirmation cannot cover changed generation");
        check(!BotgramE2eeSessionPolicy.acceptsConfirmation(stale,
                firstConfirmation.toBuilder().setSelfConfirmedIdentity(false).build()), "unconfirmed status grants no replacement trust");
        BotgramE2eeSessionPolicy.Replacement later = new BotgramE2eeSessionPolicy.Replacement(
                new byte[]{7, 8, 9}, 3);
        check(BotgramE2eeSessionPolicy.decide(true, oldIdentity, 1, later.identity, 3, stale)
                == BotgramE2eeSessionPolicy.Decision.REQUIRE_CONFIRMATION,
                "stale confirmation does not cover a later replacement");
        check(BotgramE2eeSessionPolicy.decide(true, oldIdentity, 1, newIdentity, 2, new BotgramE2eeSessionPolicy.Replacement(newIdentity, 2))
                == BotgramE2eeSessionPolicy.Decision.ESTABLISH, "confirmed replacement establishes new session");
        check(BotgramE2eeSessionPolicy.decide(false, oldIdentity, 1, oldIdentity, 1, null)
                == BotgramE2eeSessionPolicy.Decision.ESTABLISH, "missing session establishes");
        check(BotgramE2eeSessionPolicy.decide(false, null, 0, newIdentity, 1, null)
                == BotgramE2eeSessionPolicy.Decision.ESTABLISH, "first use establishes");
    }

    private static void sendPolicySequence() {
        check(BotgramE2eeSendPolicy.applies(123, false, 7, true), "bound direct user applies");
        check(!BotgramE2eeSendPolicy.applies(-100, false, 7, true), "group does not apply");
        check(!BotgramE2eeSendPolicy.applies(7, false, 7, true), "saved messages do not apply");
        check(!BotgramE2eeSendPolicy.applies(123, false, 7, false), "unbound account does not apply");
        check(!BotgramE2eeSendPolicy.applies(123, true, 7, true), "secret chat does not apply");
        check(BotgramE2eeSendPolicy.decide(false, null, false)
                == BotgramE2eeSendPolicy.Action.SKIP, "non-applicable peer skips E2EE");
        check(BotgramE2eeSendPolicy.decide(true, null, false)
                == BotgramE2eeSendPolicy.Action.BLOCK_UNKNOWN, "unknown status blocks");
        check(BotgramE2eeSendPolicy.decide(true, state(BotgramRpc.E2eeState.E2EE_DISABLED), false)
                == BotgramE2eeSendPolicy.Action.PLAINTEXT, "disabled sends plaintext");
        check(BotgramE2eeSendPolicy.decide(true, state(BotgramRpc.E2eeState.E2EE_CLOSED), false)
                == BotgramE2eeSendPolicy.Action.PLAINTEXT, "closed sends plaintext");
        check(BotgramE2eeSendPolicy.decide(true, state(BotgramRpc.E2eeState.E2EE_PENDING), false)
                == BotgramE2eeSendPolicy.Action.PLAINTEXT, "disabled direction stays ordinary");
        BotgramRpc.E2eeChatStatus oneWay = state(BotgramRpc.E2eeState.E2EE_PENDING).toBuilder().setSelfInitiator(true).build();
        check(BotgramE2eeSendPolicy.selfEnabled(oneWay), "one-way sender is enabled immediately");
        check(!BotgramE2eeSendPolicy.selfEnabled(state(BotgramRpc.E2eeState.E2EE_PENDING)), "one-way receiver stays disabled");
        check(BotgramE2eeSendPolicy.decide(true, oneWay, false) == BotgramE2eeSendPolicy.Action.ENCRYPT,
                "one-way protection needs no recipient device or signal session");
        check(BotgramE2eeSendPolicy.decide(true, state(BotgramRpc.E2eeState.E2EE_SUSPENDED), true)
                == BotgramE2eeSendPolicy.Action.BLOCK_SUSPENDED, "suspended blocks");
        check(BotgramE2eeSendPolicy.decide(true, state(BotgramRpc.E2eeState.E2EE_ACTIVE), true)
                == BotgramE2eeSendPolicy.Action.ENCRYPT, "active with session encrypts");
        check(BotgramE2eeSendPolicy.decide(true, state(BotgramRpc.E2eeState.E2EE_ACTIVE), false)
                == BotgramE2eeSendPolicy.Action.BLOCK_NOT_READY, "active without session blocks");
    }

    private static BotgramRpc.E2eeChatStatus state(BotgramRpc.E2eeState value) {
        return BotgramRpc.E2eeChatStatus.newBuilder().setState(value).build();
    }

    private static void unilateralMessages() throws Exception {
        byte[] senderKey = new byte[32]; new SecureRandom().nextBytes(senderKey);
        BotgramRpc.E2eeEnvelope sealed = BotgramE2eeUnilateral.seal(senderKey, "alice-uuid", "bob-uuid",
                "conv-1", 1, 1, 987, "sender private message");
        check(sealed.getMessageType() == 4, "one-way mode is explicit");
        check(sealed.getRecipientDeviceId() == 0, "no recipient key or device is assigned");
        check(!new String(sealed.toByteArray(), StandardCharsets.UTF_8).contains("sender private message"), "one-way wire contains no plaintext");
        String wire = BotgramE2eeEnvelopeCodec.wrap(sealed);
        check(BotgramE2eeUnilateral.isSealedMessage(wire), "placeholder classification survives wire encoding");
        BotgramE2eeVault recipient = BotgramE2eeCrypto.newVault();
        byte[] before = recipient.serialize();
        try {
            BotgramE2eeCrypto.decrypt(recipient, sealed.toByteArray(), context("bob-uuid", "alice-uuid", 2));
            throw new AssertionError("later recipient key unlocked old one-way message");
        } catch (Exception expected) { passed++; }
        check(Arrays.equals(before, recipient.serialize()), "later enable cannot consume or recover old one-way ciphertext");
        BotgramRpc.E2eeEnvelope another = BotgramE2eeUnilateral.seal(senderKey, "alice-uuid", "bob-uuid",
                "conv-1", 1, 1, 988, "sender private message");
        check(!sealed.getCiphertext().equals(another.getCiphertext()), "sender-only messages use random authenticated nonces");
    }

    private static void sessionGateSequence() {
        BotgramE2eeSessionGate gate = new BotgramE2eeSessionGate();
        check(gate.observe("account-a"), "first identity observed");
        long derive = gate.generation();
        check(gate.current(derive, "account-a"), "running operation is current");
        check(gate.observe("account-b"), "account switch observed");
        check(!gate.current(derive, "account-a"), "switch invalidates late unlock");
        check(gate.current(gate.generation(), "account-b"), "new account token is current");
        long background = gate.generation();
        gate.invalidate();
        check(!gate.current(background, "account-b"), "background lock invalidates late unlock");
        long reset = gate.generation();
        gate.invalidate();
        check(!gate.current(reset, "account-b"), "reset invalidates late unlock");
        check(gate.current(gate.generation(), "account-b"), "only the newest generation may install");
    }

    private static void atomicReplacement() throws Exception {
        java.io.File directory = Files.createTempDirectory("botgram-e2ee-atomic").toFile();
        java.io.File file = new java.io.File(directory, "vault.bin");
        BotgramE2eeAtomicFile.write(file, new byte[]{1, 2, 3});
        BotgramE2eeAtomicFile.write(file, new byte[]{4, 5, 6});
        check(Arrays.equals(Files.readAllBytes(file.toPath()), new byte[]{4, 5, 6}), "atomic replace");
        java.io.File blocked = new java.io.File(directory, "blocked");
        check(blocked.mkdirs(), "blocked directory");
        try {
            BotgramE2eeAtomicFile.write(blocked, new byte[]{7});
            throw new AssertionError("directory target replaced");
        } catch (java.io.IOException expected) {
            passed++;
        }
        check(blocked.isDirectory(), "failed replacement leaves original target");
    }

    private static BotgramE2eeCrypto.DecryptContext context(String own, String peer, long epoch) {
        return new BotgramE2eeCrypto.DecryptContext(own, "conv-1", peer, 1, epoch);
    }

    private static byte[] legacyDerive(String pin, byte[] salt, int iterations) throws Exception {
        byte[] password = pin.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        byte[] u = mac.doFinal(salt);
        byte[] result = new byte[32];
        System.arraycopy(u, 0, result, 0, 32);
        for (int round = 1; round < iterations; round++) {
            u = mac.doFinal(u);
            for (int i = 0; i < result.length; i++) result[i] ^= u[i];
        }
        return result;
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws Exception {
        out.writeInt(value.length);
        out.write(value);
    }

    private static void writeString(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format("%02x", item));
        return out.toString();
    }

    private static void zero(byte[] value) {
        Arrays.fill(value, (byte) 0);
    }
}
