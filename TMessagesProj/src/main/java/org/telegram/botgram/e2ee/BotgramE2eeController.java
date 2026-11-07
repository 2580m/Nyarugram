package org.telegram.botgram.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.SparseArray;

import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator;
import org.telegram.botgram.BotgramProtobufIdentity;
import org.telegram.botgram.BotgramTransport;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Per-account E2EE state. Public metadata may be cached while locked; the vault,
 * sessions, private keys, PIN material and plaintext display history never persist in the clear.
 */
public final class BotgramE2eeController {
    public static final String DISPLAY_LOCKED = "\u0000E2EE_LOCKED";
    public static final String DISPLAY_UNDECRYPTABLE = "\u0000E2EE_UNDECRYPTABLE";
    public static final String DISPLAY_UNILATERAL = "\u0000E2EE_UNILATERAL";

    public interface Callback { void run(boolean success, String errorCode); }
    public interface StatusCallback { void run(BotgramRpc.E2eeChatStatus status, TLRPC.TL_error error); }
    public interface ListCallback { void run(List<BotgramRpc.E2eeChatStatus> chats, TLRPC.TL_error error); }
    public interface SessionCallback { void run(boolean success, String errorCode); }

    public static final class SendException extends Exception {
        public final String code;
        SendException(String code) { super(code); this.code = code; }
    }

    private static final SparseArray<BotgramE2eeController> instances = new SparseArray<>();
    private static volatile boolean backgroundListenerRegistered;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "botgram-e2ee");
        thread.setDaemon(true);
        return thread;
    });

    public static BotgramE2eeController get(int account) {
        BotgramE2eeController value;
        synchronized (instances) {
            value = instances.get(account);
            if (value == null) {
                value = new BotgramE2eeController(account);
                instances.put(account, value);
            }
        }
        registerBackgroundListener();
        return value;
    }

    public static void lockAll() {
        synchronized (instances) {
            for (int i = 0; i < instances.size(); i++) instances.valueAt(i).lock();
        }
    }

    private static void registerBackgroundListener() {
        if (backgroundListenerRegistered) return;
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector == null) return;
        backgroundListenerRegistered = true;
        detector.addListener(new ForegroundDetector.Listener() {
            @Override public void onBecameForeground() { }
            @Override public void onBecameBackground() { lockAll(); }
        });
    }

    private final int account;
    private final Object stateLock = new Object();
    private final Object pinLock = new Object();
    /** Serializes every vault/history disk commit so no stale snapshot can roll back ratchet state. */
    private final Object commitLock = new Object();
    private final HashMap<Long, BotgramRpc.E2eeChatStatus> statuses = new HashMap<>();
    private final HashMap<Long, Long> statusRequests = new HashMap<>();
    private final HashMap<Long, Long> lastQuietRefresh = new HashMap<>();
    private final HashMap<Long, Integer> lastSystemMessage = new HashMap<>();
    private final HashSet<Long> preparing = new HashSet<>();
    private final HashMap<Long, BotgramE2eeSessionPolicy.Replacement> confirmedReplacements = new HashMap<>();
    private final SharedPreferences prefs;

    private BotgramE2eeVault vault;
    /** Version-2 (or not-yet-migrated version-1) wrapped file bytes used as rewrite base. */
    private byte[] vaultFile;
    private File vaultTarget;
    private byte[] vaultKey;
    private BotgramE2eeHistory history;
    private File historyTarget;
    private final java.util.LinkedHashMap<String, String> rendered = new java.util.LinkedHashMap<String, String>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 256; }
    };
    private final HashSet<String> rendering = new HashSet<>();
    private final java.util.WeakHashMap<MessageObject, Boolean> displayOwners = new java.util.WeakHashMap<>();
    private long renderRevision;
    private boolean publishing;
    private final ArrayList<Callback> publishWaiters = new ArrayList<>();
    private final BotgramE2eeSessionGate gate = new BotgramE2eeSessionGate();
    private long statusCounter;
    private String statusIdentity = "";
    private String vaultIdentity = "";

    private BotgramE2eeController(int account) {
        this.account = account;
        this.prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("botgram.e2ee." + account, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- identity

    private String identityHash() {
        String server = BotgramTransport.get(account).boundServer();
        String userId = BotgramTransport.get(account).boundUserId();
        if (server.isEmpty() || userId.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((server + "\n" + userId).getBytes(StandardCharsets.UTF_8));
            return hex(value, 16);
        } catch (Exception error) {
            return "";
        }
    }

    private String selfUuid() {
        return BotgramTransport.get(account).boundUserId();
    }

    private File vaultDir(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        return new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "botgram-e2ee"), hash);
    }

    private File vaultFile(String hash) {
        File dir = vaultDir(hash);
        return dir == null ? null : new File(dir, "vault.bin");
    }

    private File historyFile(String hash) {
        File dir = vaultDir(hash);
        return dir == null ? null : new File(dir, "display.bin");
    }

    /** Clears peer state cached for a different bound server or user. */
    private void pruneIdentity(String hash) {
        synchronized (stateLock) {
            if (!gate.observe(hash)) return;
            statuses.clear();
            statusRequests.clear();
            lastQuietRefresh.clear();
            lastSystemMessage.clear();
            preparing.clear();
            confirmedReplacements.clear();
            statusIdentity = hash;
        }
    }

    private long currentGeneration() {
        return gate.generation();
    }

    private boolean stillCurrent(String hash, long gen) {
        return gate.current(gen, hash);
    }

    private String pref(String key, String hash) { return prefs.getString(key + "." + hash, null); }
    private void putPref(String key, String value, String hash) {
        prefs.edit().putString(key + "." + hash, value).apply();
    }
    private void removePref(String key, String hash) {
        prefs.edit().remove(key + "." + hash).apply();
    }

    // -------------------------------------------------------------------- PIN

    public boolean hasPin() {
        String hash = identityHash();
        File file = vaultFile(hash);
        return file != null && ((file.isFile() && file.length() > 0)
                || new File(file.getPath() + ".bak").isFile());
    }

    public boolean isUnlocked() {
        synchronized (stateLock) { return vault != null; }
    }

    public long retryAfterMs() {
        return retryAfterMs(identityHash());
    }

    private long retryAfterMs(String hash) {
        long until = prefs.getLong("lockUntil." + hash, 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    public void createPin(String pin, Callback callback) {
        if (!BotgramE2eeVaultFile.validPin(pin)) { success(callback, false, "E2EE_PIN_INVALID"); return; }
        final String hash = identityHash();
        if (hash.isEmpty()) { success(callback, false, "SESSION_EXPIRED"); return; }
        pruneIdentity(hash);
        if (hasPin()) { success(callback, false, "E2EE_PIN_EXISTS"); return; }
        final File dir = vaultDir(hash);
        final File target = vaultFile(hash);
        final File historyPath = historyFile(hash);
        final long gen = currentGeneration();
        if (dir == null || target == null) { success(callback, false, "SESSION_EXPIRED"); return; }
        worker.execute(() -> {
            BotgramE2eeVault created = null;
            BotgramE2eeHistory createdHistory = null;
            byte[] createdKey = null;
            try {
                if (!stillCurrent(hash, gen)) { successIfCurrent(callback, hash, gen, false, "E2EE_LOCKED"); return; }
                created = BotgramE2eeCrypto.newVault();
                byte[] bytes = BotgramE2eeVaultFile.create(pin, created.serialize(), deviceCipher(hash));
                if (!stillCurrent(hash, gen)) {
                    successIfCurrent(callback, hash, gen, false, "E2EE_LOCKED");
                    return;
                }
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("vault directory");
                BotgramE2eeAtomicFile.write(target, bytes);
                BotgramE2eeVaultFile.Vault opened = BotgramE2eeVaultFile.open(pin, bytes, deviceCipher(hash));
                createdKey = opened.vaultKey;
                createdHistory = BotgramE2eeHistory.open(historyPath, opened.vaultKey);
                synchronized (stateLock) {
                    if (!stillValidLocked(hash, gen)) {
                        zero(createdKey);
                        createdHistory.close();
                        successIfCurrent(callback, hash, gen, false, "E2EE_LOCKED");
                        return;
                    }
                    install(created, bytes, opened.vaultKey, createdHistory, target, historyPath);
                }
                createdKey = null;
                createdHistory = null;
                clearFailures(hash);
                successIfCurrent(callback, hash, gen, true, null);
                afterUnlock();
            } catch (BotgramE2eeVaultFile.DeviceFailure deviceFailure) {
                FileLog.e("BOTGRAM_E2EE device key unavailable while creating vault");
                if (createdKey != null) zero(createdKey);
                if (createdHistory != null) createdHistory.close();
                if (stillCurrent(hash, gen) && !isUnlocked()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                successIfCurrent(callback, hash, gen, false, "E2EE_DEVICE_KEY_INVALID");
            } catch (Throwable error) {
                FileLog.e(error);
                if (createdKey != null) zero(createdKey);
                if (createdHistory != null) createdHistory.close();
                if (stillCurrent(hash, gen) && !isUnlocked()) //noinspection ResultOfMethodCallIgnored
                    target.delete();
                successIfCurrent(callback, hash, gen, false, "E2EE_VAULT_WRITE_FAILED");
            }
        });
    }

    public void unlock(String pin, Callback callback) {
        if (!BotgramE2eeVaultFile.validPin(pin)) { success(callback, false, "E2EE_PIN_INVALID"); return; }
        final String hash = identityHash();
        if (hash.isEmpty()) { success(callback, false, "SESSION_EXPIRED"); return; }
        pruneIdentity(hash);
        long retry = retryAfterMs(hash);
        if (retry > 0) { success(callback, false, "E2EE_RETRY:" + retry); return; }
        final File target = vaultFile(hash);
        final File historyPath = historyFile(hash);
        final long gen = currentGeneration();
        if (target == null || (!target.isFile() && !new File(target.getPath() + ".bak").isFile())) {
            success(callback, false, "E2EE_NO_PIN"); return;
        }
        worker.execute(() -> {
            BotgramE2eeHistory openedHistory = null;
            byte[] openedKey = null;
            try {
                // Re-check at execution time: queued attempts must respect backoff produced by
                // an earlier attempt instead of bypassing it.
                if (!stillCurrent(hash, gen)) { successIfCurrent(callback, hash, gen, false, "E2EE_LOCKED"); return; }
                long again = retryAfterMs(hash);
                if (again > 0) { successIfCurrent(callback, hash, gen, false, "E2EE_RETRY:" + again); return; }
                BotgramE2eeAtomicFile.recover(target);
                byte[] bytes = readAll(target);
                BotgramE2eeVaultFile.Vault opened = BotgramE2eeVaultFile.open(pin, bytes, deviceCipher(hash));
                if (opened.migrated != null) {
                    try {
                        BotgramE2eeAtomicFile.write(target, opened.migrated);
                        bytes = opened.migrated;
                    } catch (Throwable migration) {
                        // Keep the readable legacy file; retry the migration on a later unlock.
                        FileLog.e("BOTGRAM_E2EE vault migration deferred");
                    }
                }
                openedKey = opened.vaultKey;
                BotgramE2eeVault state = BotgramE2eeVault.parse(opened.bytes);
                openedHistory = BotgramE2eeHistory.open(historyPath, openedKey);
                if (new BotgramE2eeJournal(target, openedHistory, openedKey).recover()) {
                    bytes = readAll(target);
                    BotgramE2eeVaultFile.Vault recovered = BotgramE2eeVaultFile.open(pin, bytes, deviceCipher(hash));
                    state = BotgramE2eeVault.parse(recovered.bytes);
                    zero(recovered.vaultKey);
                }
                synchronized (stateLock) {
                    if (!stillValidLocked(hash, gen)) {
                        zero(openedKey);
                        openedHistory.close();
                        successIfCurrent(callback, hash, gen, false, "E2EE_LOCKED");
                        return;
                    }
                    install(state, bytes, openedKey, openedHistory, target, historyPath);
                }
                openedKey = null;
                openedHistory = null;
                clearFailures(hash);
                successIfCurrent(callback, hash, gen, true, null);
                afterUnlock();
            } catch (BotgramE2eeVaultFile.DeviceFailure deviceFailure) {
                FileLog.e("BOTGRAM_E2EE device key unavailable");
                if (openedKey != null) zero(openedKey);
                if (openedHistory != null) openedHistory.close();
                successIfCurrent(callback, hash, gen, false, "E2EE_DEVICE_KEY_INVALID");
            } catch (BotgramE2eeVaultFile.PinFailure pinFailure) {
                if (openedKey != null) zero(openedKey);
                if (openedHistory != null) openedHistory.close();
                if (stillCurrent(hash, gen)) recordFailure(hash);
                successIfCurrent(callback, hash, gen, false, wrongPinCode(hash));
            } catch (Throwable error) {
                FileLog.e("BOTGRAM_E2EE unlock failed: " + error.getClass().getSimpleName());
                if (openedKey != null) zero(openedKey);
                if (openedHistory != null) openedHistory.close();
                successIfCurrent(callback, hash, gen, false, "E2EE_VAULT_FAILED");
            }
        });
    }

    public void changePin(String oldPin, String newPin, Callback callback) {
        if (!BotgramE2eeVaultFile.validPin(newPin)) { success(callback, false, "E2EE_PIN_INVALID"); return; }
        final String hash = identityHash();
        if (hash.isEmpty()) { success(callback, false, "SESSION_EXPIRED"); return; }
        if (!isUnlocked()) { success(callback, false, "E2EE_LOCKED"); return; }
        final File target = vaultFile(hash);
        final long gen = currentGeneration();
        if (target == null) { success(callback, false, "SESSION_EXPIRED"); return; }
        worker.execute(() -> {
            try {
                if (!stillCurrent(hash, gen)) { successIfCurrent(callback, hash, gen, false, "E2EE_LOCKED"); return; }
                synchronized (commitLock) {
                    if (!stillCurrent(hash, gen)) throw new IOException("locked");
                    byte[] bytes = readAll(target);
                    byte[] changed = BotgramE2eeVaultFile.changePin(oldPin, newPin, bytes, deviceCipher(hash));
                    BotgramE2eeAtomicFile.write(target, changed);
                    synchronized (stateLock) {
                        if (stillValidLocked(hash, gen) && vaultTarget != null && vaultTarget.equals(target))
                            vaultFile = changed;
                    }
                }
                successIfCurrent(callback, hash, gen, true, null);
            } catch (BotgramE2eeVaultFile.PinFailure pinFailure) {
                if (stillCurrent(hash, gen)) recordFailure(hash);
                successIfCurrent(callback, hash, gen, false, wrongPinCode(hash));
            } catch (Throwable error) {
                FileLog.e(error);
                successIfCurrent(callback, hash, gen, false, "E2EE_PIN_CHANGE_FAILED");
            }
        });
    }

    /** Call only after the old PIN was verified through unlock; re-wraps the existing vault key. */
    public void changePinUnlocked(String newPin, Callback callback) {
        if (!BotgramE2eeVaultFile.validPin(newPin)) { success(callback, false, "E2EE_PIN_INVALID"); return; }
        final String hash = identityHash();
        final File target;
        final byte[] key;
        final long gen;
        synchronized (stateLock) {
            if (vault == null || vaultKey == null || vaultTarget == null) {
                success(callback, false, "E2EE_LOCKED"); return;
            }
            target = vaultTarget;
            key = vaultKey;
            gen = gate.generation();
        }
        worker.execute(() -> {
            try {
                if (!stillCurrent(hash, gen)) { successIfCurrent(callback, hash, gen, false, "E2EE_LOCKED"); return; }
                byte[] changed;
                synchronized (commitLock) {
                    changed = BotgramE2eeVaultFile.rewrapPin(newPin, readAll(target), key, deviceCipher(hash));
                    BotgramE2eeAtomicFile.write(target, changed);
                    synchronized (stateLock) {
                        if (stillValidLocked(hash, gen) && vaultTarget != null && vaultTarget.equals(target))
                            vaultFile = changed;
                    }
                }
                successIfCurrent(callback, hash, gen, true, null);
            } catch (Throwable error) {
                FileLog.e(error);
                successIfCurrent(callback, hash, gen, false, "E2EE_PIN_CHANGE_FAILED");
            }
        });
    }

    public void lock() {
        final byte[] key;
        final BotgramE2eeHistory oldHistory;
        final ArrayList<MessageObject> owners;
        synchronized (stateLock) {
            gate.invalidate();
            key = vaultKey;
            oldHistory = history;
            vault = null; vaultKey = null; vaultFile = null; vaultTarget = null;
            history = null; historyTarget = null;
            rendered.clear(); rendering.clear(); renderRevision++;
            owners = new ArrayList<>(displayOwners.keySet());
            displayOwners.clear();
        }
        AndroidUtilities.runOnUIThread(() -> { for (MessageObject object : owners) object.clearBotgramE2eeDisplay(); });
        // Every mutation was already committed. Never enqueue a final stale vault snapshot.
        worker.execute(() -> {
            synchronized (commitLock) {
                if (oldHistory != null) oldHistory.close();
                zero(key);
            }
        });
        refreshDisplayedMessages();
    }

    public void resetLocalVault(Callback callback) {
        final String hash = identityHash();
        final File dir = vaultDir(hash);
        if (hash.isEmpty() || dir == null) { success(callback, false, "SESSION_EXPIRED"); return; }
        lock();
        worker.execute(() -> {
            try {
                File[] files = dir.listFiles();
                if (files != null) for (File file : files) //noinspection ResultOfMethodCallIgnored
                    file.delete();
                removePref("published", hash);
                removePref("remaining", hash);
                clearFailures(hash);
                success(callback, true, null);
            } catch (Throwable error) {
                success(callback, false, "E2EE_RESET_FAILED");
            }
        });
    }

    private boolean stillValidLocked(String hash, long gen) {
        return gate.current(gen, hash);
    }

    private void install(BotgramE2eeVault state, byte[] file, byte[] key, BotgramE2eeHistory display,
            File vaultPath, File displayPath) {
        if (vaultKey != null) zero(vaultKey);
        vault = state;
        vaultFile = file;
        vaultTarget = vaultPath;
        vaultKey = key;
        history = display;
        historyTarget = displayPath;
        vaultIdentity = identityHash();
        state.setDirty(null); // Live state changes only through durable candidate commits.
    }

    private void afterUnlock() {
        refreshList(true, (chats, error) -> {
            if (error != null) {
                refreshDisplayedMessages();
                return;
            }
            prepareActiveSessions();
            refreshDisplayedMessages();
        });
    }

    private String wrongPinCode(String hash) {
        long retry = retryAfterMs(hash);
        return retry > 0 ? "E2EE_RETRY:" + retry : "E2EE_PIN_INCORRECT";
    }

    private void recordFailure(String hash) {
        synchronized (pinLock) {
            int tries = prefs.getInt("failed." + hash, 0) + 1;
            long delay = backoffMillis(tries);
            SharedPreferences.Editor editor = prefs.edit().putInt("failed." + hash, tries);
            if (delay > 0) editor.putLong("lockUntil." + hash, System.currentTimeMillis() + delay);
            editor.apply();
        }
    }

    private void clearFailures(String hash) {
        synchronized (pinLock) {
            prefs.edit().remove("failed." + hash).remove("lockUntil." + hash).apply();
        }
    }

    private static long backoffMillis(int tries) {
        if (tries < 5) return 0;
        if (tries == 5) return 30_000;
        if (tries == 6) return 60_000;
        if (tries == 7) return 120_000;
        if (tries == 8) return 300_000;
        if (tries == 9) return 900_000;
        return 3_600_000;
    }

    // --------------------------------------------------------------- keystore

    /**
     * Android Keystore generates the GCM IV itself (randomized encryption is required by the
     * platform); the IV is read back and stored with the ciphertext. Caller supplied IVs are
     * rejected by the Keystore and must never be used for encrypting device blobs.
     */
    private BotgramE2eeVaultFile.DeviceCipher deviceCipher(String hash) {
        return new BotgramE2eeVaultFile.DeviceCipher() {
            @Override public byte[] encrypt(byte[] plaintext) throws Exception {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(hash, true));
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length == 0) throw new IOException("keystore IV");
                byte[] value = cipher.doFinal(plaintext);
                byte[] out = new byte[iv.length + value.length];
                System.arraycopy(iv, 0, out, 0, iv.length);
                System.arraycopy(value, 0, out, iv.length, value.length);
                return out;
            }

            @Override public byte[] decrypt(byte[] payload) throws Exception {
                if (payload.length < 12 + 16) throw new IOException("device blob");
                byte[] iv = new byte[12];
                System.arraycopy(payload, 0, iv, 0, 12);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(hash, false), new GCMParameterSpec(128, iv));
                byte[] value = new byte[payload.length - 12];
                System.arraycopy(payload, 12, value, 0, value.length);
                return cipher.doFinal(value);
            }
        };
    }

    private SecretKey key(String hash, boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        String alias = "botgram-e2ee-" + hash;
        if (!store.containsAlias(alias)) {
            if (!create) throw new IOException("device key missing");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(alias, null)).getSecretKey();
    }

    // ------------------------------------------------------------- persistence

    /** All callers run on worker and serialize under commitLock. */
    private void persistVaultBytes(BotgramE2eeVault expected, byte[] bytes, long gen) throws Exception {
        synchronized (commitLock) {
            File target; byte[] base; byte[] key;
            synchronized (stateLock) {
                if (vault != expected || !gate.current(gen, vaultIdentity)) throw new IOException("locked");
                target = vaultTarget; base = vaultFile; key = vaultKey;
            }
            if (target == null || base == null || key == null) throw new IOException("locked");
            byte[] changed = BotgramE2eeVaultFile.rewriteVault(base, key, bytes);
            BotgramE2eeAtomicFile.write(target, changed);
            synchronized (stateLock) { if (vault == expected) vaultFile = changed; }
        }
    }

    private BotgramE2eeCommit.TransactionPort transactionPort(BotgramE2eeVault expected, long gen) {
        return (bytes, entries) -> {
            synchronized (commitLock) {
                File target; byte[] base; byte[] key; BotgramE2eeHistory h;
                synchronized (stateLock) {
                    if (vault != expected || !gate.current(gen, vaultIdentity)) throw new IOException("locked");
                    target = vaultTarget; base = vaultFile; key = vaultKey; h = history;
                }
                byte[] changed = BotgramE2eeVaultFile.rewriteVault(base, key, bytes);
                new BotgramE2eeJournal(target, h, key).commit(changed, entries);
                synchronized (stateLock) { if (vault == expected) vaultFile = changed; }
            }
        };
    }

    private void commitCandidate(BotgramE2eeVault live, BotgramE2eeVault candidate, long gen) throws Exception {
        synchronized (commitLock) {
            persistVaultBytes(live, candidate.serialize(), gen);
            live.commitFrom(candidate);
        }
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return bytes;
        }
    }

    // --------------------------------------------------------------- networking

    private interface RequestBuilder { BotgramRpc.RpcRequest build(BotgramProtobufIdentity identity) throws Exception; }

    private void rpc(RequestBuilder builder, BiConsumer<BotgramRpc.RpcResponse, TLRPC.TL_error> callback) {
        final String hash = identityHash();
        BotgramTransport.get(account).typedRequest((api, identity) -> api.rpc(builder.build(identity)),
                (response, error) -> {
                    if (!hash.equals(identityHash())) {
                        callback.accept(null, tlError(401, "SESSION_EXPIRED"));
                        return;
                    }
                    if (error == null && response != null && response.hasError()) {
                        error = tlError((int) response.getError().getHttpStatus(), response.getError().getCode());
                    }
                    callback.accept(response, error);
                });
    }

    private static BotgramRpc.RpcRequest.Builder request() {
        return BotgramRpc.RpcRequest.newBuilder().setProtocolMajor(1).setRequestId(UUID.randomUUID().toString());
    }

    private static TLRPC.TL_error tlError(int code, String text) {
        TLRPC.TL_error value = new TLRPC.TL_error();
        value.code = code;
        value.text = text;
        return value;
    }

    private void ensurePublished(boolean replenish, Callback callback) {
        if (!isUnlocked()) { success(callback, false, "E2EE_LOCKED"); return; }
        final String hash = identityHash();
        final long startGen = gate.generation();
        synchronized (stateLock) {
            if (publishing) {
                if (callback != null) publishWaiters.add(callback);
                return;
            }
            publishing = true;
        }
        worker.execute(() -> {
            try {
                BotgramE2eeVault expected;
                long gen;
                synchronized (stateLock) {
                    expected = vault;
                    gen = gate.generation();
                }
                if (expected == null) throw new IllegalStateException("locked");
                String fingerprint = sha256(expected.identityPublicBytes());
                String published = pref("published", hash);
                int remaining = prefs.getInt("remaining." + hash, 0);
                if (!replenish && published != null && published.equals(fingerprint) && remaining >= 10) {
                    donePublish(callback, true, null, hash, gen);
                    return;
                }
                BotgramRpc.E2eePublishDeviceRequest body;
                synchronized (commitLock) {
                    BotgramE2eeVault candidate = expected.copyForTransaction();
                    boolean replenished = remaining < 10;
                    if (replenished) BotgramE2eeCrypto.addOneTimePreKeys(candidate, 20);
                    body = BotgramE2eeCrypto.publishRequest(candidate);
                    if (replenished) {
                        byte[] bytes = candidate.serialize();
                        persistVaultBytes(expected, bytes, gen);
                        expected.commitFrom(candidate);
                    }
                }
                BotgramRpc.RpcRequest request = request().setE2EePublishDevice(body).build();
                BotgramTransport.get(account).typedRequest((api, identity) -> api.rpc(request), (response, error) -> {
                    if (!stillCurrent(hash, gen)) { donePublish(callback, false, "E2EE_LOCKED", hash, gen); return; }
                    if (error == null && response != null && response.hasError())
                        error = tlError((int) response.getError().getHttpStatus(), response.getError().getCode());
                    if (error != null) { donePublish(callback, false, error.text, hash, gen); return; }
                    BotgramRpc.E2eeDeviceInfo info = response.getE2EePublishDevice();
                    putPref("published", sha256(expected.identityPublicBytes()), hash);
                    prefs.edit().putInt("remaining." + hash,
                            info.getRemainingOneTimePreKeys()).apply();
                    donePublish(callback, true, null, hash, gen);
                });
            } catch (Throwable error) {
                FileLog.e(error);
                donePublish(callback, false, "E2EE_PUBLISH_FAILED", hash, startGen);
            }
        });
    }

    private void donePublish(Callback callback, boolean value, String code, String hash, long gen) {
        ArrayList<Callback> waiters;
        synchronized (stateLock) {
            publishing = false;
            waiters = new ArrayList<>(publishWaiters);
            publishWaiters.clear();
        }
        for (Callback waiter : waiters) {
            if (waiter != null) AndroidUtilities.runOnUIThread(() -> waiter.run(value, code));
        }
        successIfCurrent(callback, hash, gen, value, code);
    }

    // ------------------------------------------------------------------ status

    public BotgramRpc.E2eeChatStatus status(long peer) {
        pruneIdentity(identityHash());
        synchronized (stateLock) { return statuses.get(peer); }
    }

    public boolean statusKnown(long peer) {
        pruneIdentity(identityHash());
        synchronized (stateLock) { return statuses.containsKey(peer); }
    }

    private void note(BotgramRpc.E2eeChatStatus status) {
        if (status == null) return;
        long peer = status.getTlUserId();
        if (peer == 0 && status.hasPeer() && status.getPeer().hasUser()) return;
        BotgramRpc.E2eeChatStatus previous;
        boolean changed;
        synchronized (stateLock) {
            previous = statuses.put(peer, status);
            statusRequests.put(peer, ++statusCounter); // newer local state invalidates in-flight reads
            changed = previous == null || previous.getState() != status.getState()
                    || !previous.getPeerIdentityKey().equals(status.getPeerIdentityKey())
                    || previous.getPeerGeneration() != status.getPeerGeneration()
                    || !previous.getConversationId().equals(status.getConversationId());
        }
        if (changed) { synchronized (stateLock) { rendered.clear(); renderRevision++; } }
        if (changed) AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.botgramE2eeLockChanged));
        if (status.getState() == BotgramRpc.E2eeState.E2EE_ACTIVE
                || status.getState() == BotgramRpc.E2eeState.E2EE_CLOSING) {
            prepareSession(peer, null);
        }
        if (BotgramE2eeSendPolicy.selfEnabled(status)) {
            AndroidUtilities.runOnUIThread(() ->
                    org.telegram.messenger.MediaDataController.getInstance(account).cleanDraft(peer, 0, false));
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.botgramE2eeStatusChanged, peer);
    }

    public void refreshStatusQuiet(long peer) {
        long now = System.currentTimeMillis();
        synchronized (stateLock) {
            Long previous = lastQuietRefresh.get(peer);
            if (previous != null && now - previous < 5000) return;
            lastQuietRefresh.put(peer, now);
        }
        refreshStatus(peer, null);
    }

    public void onSystemMessage(long peer, int messageId) {
        synchronized (stateLock) {
            Integer previous = lastSystemMessage.get(peer);
            if (messageId <= 0 || (previous != null && previous >= messageId)) return;
            lastSystemMessage.put(peer, messageId);
        }
        // A real new boundary must not be swallowed by the quiet refresh throttle.
        refreshStatus(peer, null);
    }

    public void refreshStatus(long peer, StatusCallback callback) {
        final String hash = identityHash();
        if (hash.isEmpty()) { callbackError(callback, "SESSION_EXPIRED"); return; }
        pruneIdentity(hash);
        final long request;
        synchronized (stateLock) {
            request = ++statusCounter;
            statusRequests.put(peer, request);
        }
        rpc(identity -> request().setE2EeGetStatus(BotgramRpc.E2eeGetStatusRequest.newBuilder()
                        .addPeers(BotgramRpc.PeerRef.newBuilder().setUser(identity.userRef(peer))))
                .build(), (response, error) -> {
            if (error != null) { callbackError(callback, error.text); return; }
            long latest;
            synchronized (stateLock) { latest = statusRequests.containsKey(peer) ? statusRequests.get(peer) : 0; }
            if (latest != request) {
                // A newer action/refresh already wrote this peer; never let an old response win.
                if (callback != null) callback.run(status(peer), null);
                return;
            }
            BotgramRpc.E2eeChatStatus found = null;
            for (BotgramRpc.E2eeChatStatus item : response.getE2EeGetStatus().getChatsList()) {
                if (item.getTlUserId() == peer) found = item;
            }
            if (found == null) found = disabledStatus(peer);
            note(found);
            if (callback != null) callback.run(found, null);
        });
    }

    private static BotgramRpc.E2eeChatStatus disabledStatus(long peer) {
        return BotgramRpc.E2eeChatStatus.newBuilder()
                .setState(BotgramRpc.E2eeState.E2EE_DISABLED).setProtocolVersion(1)
                .setTlUserId(peer).setPeer(BotgramRpc.PeerRef.newBuilder()
                        .setUser(BotgramRpc.UserRef.newBuilder()
                                .setServerDomain("").setUserId("")).build())
                .build();
    }

    public void refreshList(boolean includePending, ListCallback callback) {
        final String hash = identityHash();
        if (hash.isEmpty()) {
            if (callback != null) callback.run(null, tlError(401, "SESSION_EXPIRED"));
            return;
        }
        pruneIdentity(hash);
        rpc(identity -> request().setE2EeList(BotgramRpc.E2eeListRequest.newBuilder()
                .setIncludePending(includePending).build()).build(), (response, error) -> {
            if (error != null) { if (callback != null) callback.run(null, error); return; }
            List<BotgramRpc.E2eeChatStatus> chats = response.getE2EeList().getChatsList();
            for (BotgramRpc.E2eeChatStatus item : chats) note(item);
            if (callback != null) callback.run(chats, null);
        });
    }

    private void prepareActiveSessions() {
        ArrayList<Long> peers = new ArrayList<>();
        synchronized (stateLock) {
            for (Map.Entry<Long, BotgramRpc.E2eeChatStatus> entry : statuses.entrySet()) {
                if (entry.getValue().getState() == BotgramRpc.E2eeState.E2EE_ACTIVE
                        || entry.getValue().getState() == BotgramRpc.E2eeState.E2EE_CLOSING)
                    peers.add(entry.getKey());
            }
        }
        for (Long peer : peers) prepareSession(peer, null);
    }

    public void prepareSession(long peer, SessionCallback callback) {
        if (!isUnlocked()) { session(callback, false, "E2EE_LOCKED"); return; }
        final String hash = identityHash();
        pruneIdentity(hash);
        BotgramRpc.E2eeChatStatus status;
        synchronized (stateLock) { status = statuses.get(peer); }
        if (status == null) { refreshStatusQuiet(peer); session(callback, false, "E2EE_STATUS_UNKNOWN"); return; }
        if (status.getState() != BotgramRpc.E2eeState.E2EE_ACTIVE
                && status.getState() != BotgramRpc.E2eeState.E2EE_CLOSING) {
            session(callback, false, "E2EE_NOT_ACTIVE"); return;
        }
        String peerUuid = status.getPeer().getUser().getUserId();
        int peerDevice = (int) status.getPeerDeviceId();
        if (peerUuid.isEmpty() || peerDevice == 0) { session(callback, false, "E2EE_PEER_DEVICE_MISSING"); return; }
        BotgramE2eeVault state;
        long gen;
        synchronized (stateLock) {
            state = vault;
            gen = gate.generation();
        }
        if (state == null) { session(callback, false, "E2EE_LOCKED"); return; }
        byte[] statusIdentity = status.getPeerIdentityKey().size() > 0
                ? status.getPeerIdentityKey().toByteArray() : null;
        byte[] trustedIdentity = state.peerIdentity(peerUuid, peerDevice);
        int trustedGeneration = trustedGeneration(peer, hash);
        BotgramE2eeSessionPolicy.Replacement confirmed;
        synchronized (stateLock) { confirmed = confirmedReplacements.get(peer); }
        if (confirmed == null) {
            String saved = prefs.getString("confirmed." + hash + "." + peer, null);
            if (saved != null) try {
                int colon = saved.indexOf(':');
                confirmed = new BotgramE2eeSessionPolicy.Replacement(BotgramE2eeBase64.decode(saved.substring(colon + 1)),
                        Integer.parseInt(saved.substring(0, colon)));
                synchronized (stateLock) { confirmedReplacements.put(peer, confirmed); }
            } catch (Exception corrupt) { /* Invalid confirmation never grants trust. */ }
        }
        BotgramRpc.E2eeChatStatus finalStatus = status;
        BotgramE2eeSessionPolicy.Decision decision = BotgramE2eeSessionPolicy.decide(
                state.hasSession(peerUuid, peerDevice), trustedIdentity, trustedGeneration,
                statusIdentity == null ? trustedIdentity : statusIdentity,
                (int) status.getPeerGeneration(), confirmed);
        if (decision == BotgramE2eeSessionPolicy.Decision.REQUIRE_CONFIRMATION) {
            refreshStatusQuiet(peer);
            session(callback, false, "E2EE_IDENTITY_CHANGED");
            return;
        }
        if (decision == BotgramE2eeSessionPolicy.Decision.REUSE) {
            session(callback, true, null);
            return;
        }
        synchronized (preparing) {
            if (preparing.contains(peer)) { session(callback, false, "E2EE_SESSION_PREPARING"); return; }
            preparing.add(peer);
        }
        rpc(identity -> request().setE2EeGetPeerBundle(BotgramRpc.E2eeGetPeerBundleRequest.newBuilder()
                        .setPeer(BotgramRpc.PeerRef.newBuilder().setUser(identity.userRef(peer))).build()).build(),
                (response, error) -> {
            if (error != null) { synchronized (preparing) { preparing.remove(peer); } session(callback, false, error.text); return; }
            BotgramRpc.E2eePeerBundle bundle = response.getE2EeGetPeerBundle();
            worker.execute(() -> {
                BotgramE2eeVault current;
                long currentGen;
                synchronized (stateLock) { current = vault; currentGen = gen; }
                if (current == null || !stillCurrent(hash, currentGen)) {
                    synchronized (preparing) { preparing.remove(peer); }
                    session(callback, false, "E2EE_LOCKED");
                    return;
                }
                // The status and bundle must agree; otherwise the identity changed between them.
                byte[] bundleIdentity = bundle.getIdentityKey().toByteArray();
                byte[] expectedStatus = finalStatus.getPeerIdentityKey().size() > 0
                        ? finalStatus.getPeerIdentityKey().toByteArray() : null;
                if (expectedStatus != null && !Arrays.equals(expectedStatus, bundleIdentity)) {
                    synchronized (preparing) { preparing.remove(peer); }
                    synchronized (stateLock) { confirmedReplacements.remove(peer); }
                    refreshStatusQuiet(peer);
                    session(callback, false, "E2EE_IDENTITY_CHANGED");
                    return;
                }
                BotgramE2eeSessionPolicy.Replacement replacement;
                synchronized (stateLock) { replacement = confirmedReplacements.get(peer); }
                if ((replacement != null && (replacement.generation != bundle.getGeneration()
                        || !Arrays.equals(replacement.identity, bundleIdentity))
                        || (trustedIdentity != null && !Arrays.equals(trustedIdentity, bundleIdentity)
                        && replacement == null))) {
                    synchronized (preparing) { preparing.remove(peer); }
                    synchronized (stateLock) { confirmedReplacements.remove(peer); }
                    refreshStatusQuiet(peer);
                    session(callback, false, "E2EE_IDENTITY_CHANGED");
                    return;
                }
                try {
                    synchronized (commitLock) {
                        BotgramE2eeVault candidate = current.copyForTransaction();
                        BotgramE2eeCrypto.prepareSession(candidate, peerUuid, bundle,
                                replacement == null ? null : replacement.identity,
                                replacement == null ? 0 : replacement.generation,
                                replacement != null);
                        commitCandidate(current, candidate, currentGen);
                    }
                    putTrustedGeneration(peer, (int) bundle.getGeneration(), hash);
                    synchronized (stateLock) { confirmedReplacements.remove(peer); }
                    synchronized (preparing) { preparing.remove(peer); }
                    session(callback, true, null);
                } catch (BotgramE2eeCrypto.IdentityChangedException changed) {
                    synchronized (preparing) { preparing.remove(peer); }
                    synchronized (stateLock) { confirmedReplacements.remove(peer); }
                    refreshStatusQuiet(peer);
                    session(callback, false, "E2EE_IDENTITY_CHANGED");
                } catch (UntrustedIdentityException untrusted) {
                    synchronized (preparing) { preparing.remove(peer); }
                    refreshStatusQuiet(peer);
                    session(callback, false, "E2EE_IDENTITY_CHANGED");
                } catch (Throwable failure) {
                    FileLog.e(failure);
                    synchronized (preparing) { preparing.remove(peer); }
                    session(callback, false, "E2EE_SESSION_FAILED");
                }
            });
        });
    }

    private int trustedGeneration(long peer, String hash) {
        return prefs.getInt("trustedGen." + hash + "." + peer, 0);
    }

    private void putTrustedGeneration(long peer, int value, String hash) {
        prefs.edit().putInt("trustedGen." + hash + "." + peer, value).apply();
    }

    // ----------------------------------------------------------------- actions

    public void action(long peer, BotgramRpc.E2eeActionKind action, StatusCallback callback) {
        action(peer, action, null, callback);
    }

    private void action(long peer, BotgramRpc.E2eeActionKind action, BotgramRpc.E2eeChatStatus shown, StatusCallback callback) {
        final String hash = identityHash();
        final long gen = currentGeneration();
        final BotgramE2eeSessionPolicy.Replacement captured;
        final byte[] confirmedIdentity;
        final long confirmedGeneration;
        final long confirmedEpoch;
        final String confirmedVersion;
        if (action == BotgramRpc.E2eeActionKind.E2EE_CONFIRM_IDENTITY) {
            BotgramRpc.E2eeChatStatus pending = shown == null ? status(peer) : shown;
            if (pending == null || pending.getPeerIdentityKey().size() == 0
                    || pending.getEpoch() <= 0 || pending.getIdentityVersion().isEmpty()) {
                // Without a complete snapshot the server must never confirm whatever
                // identity happens to be current now.
                callbackError(callback, "E2EE_IDENTITY_CHANGED");
                return;
            }
            captured = new BotgramE2eeSessionPolicy.Replacement(
                    pending.getPeerIdentityKey().toByteArray(), (int) pending.getPeerGeneration());
            confirmedIdentity = pending.getPeerIdentityKey().toByteArray();
            confirmedGeneration = pending.getPeerGeneration();
            confirmedEpoch = pending.getEpoch();
            confirmedVersion = pending.getIdentityVersion();
        } else {
            captured = null;
            confirmedIdentity = null;
            confirmedGeneration = 0;
            confirmedEpoch = 0;
            confirmedVersion = null;
        }
        rpc(identity -> {
            BotgramRpc.E2eeActionRequest.Builder actionRequest = BotgramRpc.E2eeActionRequest.newBuilder()
                    .setPeer(BotgramRpc.PeerRef.newBuilder().setUser(identity.userRef(peer)))
                    .setAction(action).setProtocolVersion(2);
            if (captured != null) {
                // Bind the request to the exact key, generation, epoch and two-sided
                // identity version the user saw. The server rejects any mismatch.
                actionRequest.setIdentityConfirmation(BotgramRpc.E2eeIdentityConfirmation.newBuilder()
                        .setPeerIdentityKey(com.google.protobuf.ByteString.copyFrom(confirmedIdentity))
                        .setPeerGeneration(confirmedGeneration)
                        .setEpoch(confirmedEpoch)
                        .setIdentityVersion(confirmedVersion)
                        .build());
            }
            return request().setE2EeAction(actionRequest).build();
        },
                (response, error) -> {
            if (error != null) { if (callback != null) callback.run(null, error); return; }
            if (!stillCurrent(hash, gen)) { callbackError(callback, "E2EE_LOCKED"); return; }
            BotgramRpc.E2eeChatStatus status = response.getE2EeAction();
            if (action == BotgramRpc.E2eeActionKind.E2EE_CONFIRM_IDENTITY && captured != null) {
                // The confirmation is bound to the exact identity and generation shown to the
                // user. A later replacement must not inherit that confirmation.
                if (status.getState() == BotgramRpc.E2eeState.E2EE_ACTIVE
                        || status.getState() == BotgramRpc.E2eeState.E2EE_SUSPENDED) {
                    if (!BotgramE2eeSessionPolicy.acceptsConfirmation(captured, status)) {
                        refreshStatusQuiet(peer);
                        callbackError(callback, "E2EE_IDENTITY_CHANGED");
                        return;
                    }
                    final String saved = captured.generation + ":" + BotgramE2eeBase64.encode(captured.identity);
                    worker.execute(() -> {
                        boolean stored = prefs.edit().putString("confirmed." + hash + "." + peer, saved).commit();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!stillCurrent(hash, gen)) { callbackError(callback, "E2EE_LOCKED"); return; }
                            if (!stored) { callbackError(callback, "E2EE_COMMIT_FAILED"); return; }
                            synchronized (stateLock) { confirmedReplacements.put(peer, captured); }
                            note(status);
                            if (callback != null) callback.run(status, null);
                        });
                    });
                    return;
                }
            }
            note(status);
            if (status.getState() == BotgramRpc.E2eeState.E2EE_ACTIVE) prepareSession(peer, null);
            if (callback != null) callback.run(status, null);
        });
    }

    public void invite(long peer, StatusCallback callback) {
        if (!isUnlocked()) { callbackError(callback, "E2EE_LOCKED"); return; }
        ensurePublished(false, (ok, code) -> {
            if (!ok && !"E2EE_PUBLISH_IN_PROGRESS".equals(code)) {
                callbackError(callback, code);
                return;
            }
            action(peer, BotgramRpc.E2eeActionKind.E2EE_INVITE, callback);
        });
    }

    public void accept(long peer, StatusCallback callback) {
        if (!isUnlocked()) { callbackError(callback, "E2EE_LOCKED"); return; }
        ensurePublished(false, (ok, code) -> {
            if (!ok && !"E2EE_PUBLISH_IN_PROGRESS".equals(code)) {
                callbackError(callback, code);
                return;
            }
            action(peer, BotgramRpc.E2eeActionKind.E2EE_ACCEPT, callback);
        });
    }

    public void decline(long peer, StatusCallback callback) { action(peer, BotgramRpc.E2eeActionKind.E2EE_DECLINE, callback); }
    public void cancel(long peer, StatusCallback callback) { action(peer, BotgramRpc.E2eeActionKind.E2EE_CANCEL, callback); }
    public void requestClose(long peer, StatusCallback callback) {
        action(peer, BotgramRpc.E2eeActionKind.E2EE_CLOSE_REQUEST, callback);
    }
    public void confirmClose(long peer, StatusCallback callback) {
        action(peer, BotgramRpc.E2eeActionKind.E2EE_CLOSE_CONFIRM, callback);
    }
    public void confirmIdentity(long peer, BotgramRpc.E2eeChatStatus shown, StatusCallback callback) {
        action(peer, BotgramRpc.E2eeActionKind.E2EE_CONFIRM_IDENTITY, shown, callback);
    }

    // ----------------------------------------------------------------- message

    /** Returns null when this chat is still plaintext; throws when encrypted sending is required or impossible. */
    private String encryptForSend(long peer, String text, long randomId) throws SendException {
        BotgramRpc.E2eeChatStatus status;
        synchronized (stateLock) { status = statuses.get(peer); }
        if (status == null) {
            refreshStatusQuiet(peer);
            throw new SendException("E2EE_STATUS_UNKNOWN");
        }
        switch (status.getState()) {
            case E2EE_DISABLED:
            case E2EE_CLOSED:
                return null;
            case E2EE_PENDING:
                if (status.getProtocolVersion() < 2) throw new SendException("E2EE_PROTOCOL_VERSION_UNSUPPORTED");
                if (!status.getSelfInitiator()) return null;
                break;
            case E2EE_SUSPENDED:
                throw new SendException("E2EE_IDENTITY_CONFIRMATION_REQUIRED");
            case E2EE_ACTIVE:
            case E2EE_CLOSING:
                break;
            default:
                throw new SendException("E2EE_STATE_INVALID");
        }
        String self = selfUuid();
        if (self.isEmpty()) throw new SendException("SESSION_EXPIRED");
        String conversation = status.getConversationId();
        String peerUuid = status.getPeer().getUser().getUserId();
        int peerDevice = (int) status.getPeerDeviceId();
        boolean unilateral = status.getState() == BotgramRpc.E2eeState.E2EE_PENDING;
        if (conversation.isEmpty() || peerUuid.isEmpty() || (!unilateral && peerDevice == 0))
            throw new SendException("E2EE_STATUS_INCOMPLETE");
        BotgramE2eeVault state;
        BotgramE2eeHistory history;
        long gen;
        synchronized (stateLock) {
            state = vault;
            history = this.history;
            gen = gate.generation();
        }
        if (state == null || history == null) throw new SendException("E2EE_LOCKED");
        if (!unilateral && !state.hasSession(peerUuid, peerDevice)) {
            prepareSession(peer, null);
            throw new SendException("E2EE_SESSION_PREPARING");
        }
        BotgramE2eeSessionPolicy.Replacement confirmed;
        synchronized (stateLock) { confirmed = confirmedReplacements.get(peer); }
        BotgramE2eeSessionPolicy.Decision decision = BotgramE2eeSessionPolicy.decide(true,
                state.peerIdentity(peerUuid, peerDevice), trustedGeneration(peer, identityHash()),
                status.getPeerIdentityKey().toByteArray(), (int) status.getPeerGeneration(), confirmed);
        if (!unilateral && decision != BotgramE2eeSessionPolicy.Decision.REUSE) {
            prepareSession(peer, null);
            throw new SendException("E2EE_IDENTITY_CHANGED");
        }
        String displayKey = displayKey(conversation, self, randomId);
        synchronized (commitLock) {
            try {
                String oldWire = history.get(displayKey + "|wire");
                if (oldWire != null) {
                    if (!text.equals(history.get(displayKey))) throw new IOException("random id conflict");
                    return oldWire;
                }
                BotgramE2eeCommit.SendResult result = BotgramE2eeCommit.send(state,
                        transactionPort(state, gen), displayKey, candidate ->
                                new BotgramE2eeCommit.SendResult(
                                        unilateral ? BotgramE2eeUnilateral.seal(vaultKey, self, peerUuid,
                                                conversation, candidate.deviceId(), status.getEpoch(), randomId, text)
                                                : BotgramE2eeCrypto.encrypt(candidate, self, conversation, peerUuid,
                                                peerDevice, status.getEpoch(), randomId, text), text));
                return BotgramE2eeEnvelopeCodec.wrap(result.envelope);
            } catch (Throwable error) {
                FileLog.e("BOTGRAM_E2EE send commit failed: " + error.getClass().getSimpleName());
                lock(); // Recovery is required before any history or ratchet can be reused.
                throw new SendException("E2EE_COMMIT_FAILED");
            }
        }
    }

    /** Returns null for a plaintext message, or the display text / a sentinel for an encrypted one. */
    public String decryptForDisplay(TLRPC.Message message, MessageObject target) {
        if (message == null || !BotgramE2eeEnvelopeCodec.isEnvelope(message.message)) return null;
        if (!message.out && BotgramE2eeUnilateral.isSealedMessage(message.message)) return DISPLAY_UNILATERAL;
        final String wire = message.message;
        final String cacheKey = message.dialog_id + "|" + message.out + "|" + wire;
        final String hash = identityHash();
        final long gen, revision;
        synchronized (stateLock) {
            if (vault == null) { target.clearBotgramE2eeDisplay(); return DISPLAY_LOCKED; }
            displayOwners.put(target, true);
            revision = renderRevision;
            String local = target.getBotgramE2eeDisplay(revision);
            if (local != null) return local;
            String hit = rendered.get(cacheKey);
            if (hit != null) return hit;
            if (!rendering.add(cacheKey)) return DISPLAY_LOCKED;
            gen = gate.generation();
        }
        TLRPC.Message copy = new TLRPC.TL_message();
        copy.message = wire; copy.dialog_id = message.dialog_id; copy.out = message.out;
        final java.lang.ref.WeakReference<MessageObject> owner = new java.lang.ref.WeakReference<>(target);
        worker.execute(() -> {
            String result = DISPLAY_LOCKED;
            try {
                if (stillCurrent(hash, gen)) result = decryptForDisplayBlocking(copy);
            } catch (Throwable error) {
                FileLog.e("BOTGRAM_E2EE display storage failed");
                lock();
            }
            synchronized (stateLock) {
                rendering.remove(cacheKey);
                if (!stillCurrent(hash, gen) || vault == null || renderRevision != revision) return;
                if (!DISPLAY_LOCKED.equals(result)) rendered.put(cacheKey, result);
            }
            final String value = result;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (stateLock) { if (!stillCurrent(hash, gen) || renderRevision != revision) return; }
                MessageObject object = owner.get();
                if (object != null) object.applyBotgramE2eeResult(revision, value);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.botgramE2eeLockChanged,
                        copy.dialog_id, message.id, revision, value);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            });
        });
        return DISPLAY_LOCKED;
    }

    public interface SendCallback { void run(String wire, String error); }
    public void encryptForSendAsync(long peer, String text, long randomId, SendCallback callback) {
        final String hash = identityHash();
        final long gen = currentGeneration();
        worker.execute(() -> {
            String wire = null, error = null;
            try {
                if (!stillCurrent(hash, gen)) throw new SendException("E2EE_LOCKED");
                wire = encryptForSend(peer, text, randomId);
            } catch (SendException e) { error = e.code; }
            final String result = wire, code = error;
            AndroidUtilities.runOnUIThread(() -> callback.run(stillCurrent(hash, gen) ? result : null,
                    stillCurrent(hash, gen) ? code : "E2EE_LOCKED"));
        });
    }

    private String decryptForDisplayBlocking(TLRPC.Message message) {
        if (message == null || !BotgramE2eeEnvelopeCodec.isEnvelope(message.message)) return null;
        long peer = message.dialog_id;
        if (peer <= 0) return DISPLAY_UNDECRYPTABLE;
        if (!isUnlocked()) return DISPLAY_LOCKED;
        BotgramRpc.E2eeChatStatus status;
        synchronized (stateLock) { status = statuses.get(peer); }
        if (status == null || status.getConversationId().isEmpty()) {
            refreshStatusQuiet(peer);
            return DISPLAY_UNDECRYPTABLE;
        }
        String self = selfUuid();
        if (self.isEmpty()) return DISPLAY_UNDECRYPTABLE;
        byte[] bytes;
        BotgramRpc.E2eeEnvelope envelope;
        try {
            bytes = BotgramE2eeEnvelopeCodec.unwrapBytes(message.message);
            envelope = BotgramRpc.E2eeEnvelope.parseFrom(bytes);
        } catch (Throwable error) {
            return DISPLAY_UNDECRYPTABLE;
        }
        String sender = message.out ? self : status.getPeer().getUser().getUserId();
        String baseKey = displayKey(status.getConversationId(), sender, envelope.getRandomId());
        String displayKey = baseKey + "|" + sha256(message.message.getBytes(StandardCharsets.UTF_8));
        BotgramE2eeHistory history;
        BotgramE2eeVault state;
        long gen;
        synchronized (stateLock) {
            history = this.history;
            state = vault;
            gen = gate.generation();
        }
        if (history == null || state == null) return DISPLAY_LOCKED;
        String cached = history.get(displayKey);
        if (cached == null) cached = history.getLegacy(message.message);
        // Read compatibility for the previous append-only format, never for new writes.
        if (cached == null) {
            String storedWire = history.get(baseKey + "|wire");
            if (storedWire == null || storedWire.equals(message.message)) cached = history.get(baseKey);
        }
        if (cached != null) return cached;
        if (message.out) return DISPLAY_UNDECRYPTABLE;
        try {
            BotgramE2eeCrypto.DecryptContext context = new BotgramE2eeCrypto.DecryptContext(self,
                    status.getConversationId(), status.getPeer().getUser().getUserId(),
                    (int) status.getPeerDeviceId(), status.getEpoch());
            synchronized (commitLock) {
                BotgramE2eeCrypto.Decrypted decrypted = BotgramE2eeCrypto.decrypt(state, bytes, context);
                return BotgramE2eeCommit.receive(state, transactionPort(state, gen),
                        displayKey, decrypted);
            }
        } catch (Throwable error) {
            FileLog.e("BOTGRAM_E2EE decrypt failed: " + error.getClass().getSimpleName());
            if (error instanceof IOException || error instanceof IllegalStateException) lock();
            return DISPLAY_UNDECRYPTABLE;
        }
    }

    public String replacementSafetyNumber(long peer, byte[] shownIdentity) {
        BotgramE2eeVault state;
        BotgramRpc.E2eeChatStatus status;
        synchronized (stateLock) { state = vault; status = statuses.get(peer); }
        if (state == null || status == null) return "";
        try {
            return new NumericFingerprintGenerator(2).createFor(1,
                    selfUuid().getBytes(StandardCharsets.UTF_8), state.identityPublic(),
                    status.getPeer().getUser().getUserId().getBytes(StandardCharsets.UTF_8),
                    new org.signal.libsignal.protocol.IdentityKey(shownIdentity))
                    .getDisplayableFingerprint().getDisplayText();
        } catch (Exception error) { return ""; }
    }

    public boolean requiresIdentityConfirmation(long peer, BotgramRpc.E2eeChatStatus status) {
        BotgramE2eeVault state;
        BotgramE2eeSessionPolicy.Replacement confirmed;
        synchronized (stateLock) { state = vault; confirmed = confirmedReplacements.get(peer); }
        if (state == null || status.getPeerIdentityKey().isEmpty()) return false;
        String uuid = status.getPeer().getUser().getUserId();
        int device = (int) status.getPeerDeviceId();
        return BotgramE2eeSessionPolicy.decide(state.hasSession(uuid, device), state.peerIdentity(uuid, device),
                trustedGeneration(peer, identityHash()), status.getPeerIdentityKey().toByteArray(),
                (int) status.getPeerGeneration(), confirmed) == BotgramE2eeSessionPolicy.Decision.REQUIRE_CONFIRMATION;
    }

    public String safetyNumber(long peer) {
        BotgramRpc.E2eeChatStatus status;
        synchronized (stateLock) { status = statuses.get(peer); }
        if (status == null || !status.hasPeer() || !status.getPeer().hasUser()) return "";
        if (status.getState() != BotgramRpc.E2eeState.E2EE_ACTIVE
                && status.getState() != BotgramRpc.E2eeState.E2EE_CLOSING
                && status.getState() != BotgramRpc.E2eeState.E2EE_SUSPENDED) return "";
        String peerUuid = status.getPeer().getUser().getUserId();
        int peerDevice = (int) status.getPeerDeviceId();
        if (peerUuid.isEmpty() || peerDevice == 0) return "";
        BotgramE2eeVault state;
        synchronized (stateLock) { state = vault; }
        if (state == null) return "";
        // The safety number reflects the locally trusted identity that the ratchet actually
        // uses, not merely the key the server returned in the latest status.
        byte[] peerIdentity = state.peerIdentity(peerUuid, peerDevice);
        if (peerIdentity == null) peerIdentity = status.getPeerIdentityKey().toByteArray();
        if (peerIdentity.length == 0) return "";
        try {
            Fingerprint fingerprint = new NumericFingerprintGenerator(2).createFor(1,
                    selfUuid().getBytes(StandardCharsets.UTF_8), state.identityPublic(),
                    peerUuid.getBytes(StandardCharsets.UTF_8),
                    new org.signal.libsignal.protocol.IdentityKey(peerIdentity));
            if (fingerprint == null) return "";
            return fingerprint.getDisplayableFingerprint().getDisplayText();
        } catch (Throwable error) {
            return "";
        }
    }

    // ----------------------------------------------------------------- helpers

    private static String displayKey(String conversationId, String senderUserId, long randomId) {
        return conversationId + "|" + senderUserId + "|" + randomId;
    }

    private void refreshDisplayedMessages() {
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            for (int i = 0; i < controller.dialogMessage.size(); i++) {
                ArrayList<MessageObject> values = controller.dialogMessage.valueAt(i);
                if (values == null) continue;
                for (int j = 0; j < values.size(); j++) values.get(j).refreshBotgramE2eeText();
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.botgramE2eeLockChanged);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        });
    }

    private void success(Callback callback, boolean value, String code) {
        if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.run(value, code));
    }

    private void successIfCurrent(Callback callback, String hash, long gen, boolean value, String code) {
        if (callback == null) return;
        AndroidUtilities.runOnUIThread(() -> {
            boolean valid = stillCurrent(hash, gen);
            callback.run(valid ? value : false, valid ? code : "E2EE_LOCKED");
        });
    }

    private static void session(SessionCallback callback, boolean value, String code) {
        if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.run(value, code));
    }

    private static void callbackError(StatusCallback callback, String code) {
        if (callback != null) callback.run(null, tlError(409, code));
    }

    private static void zero(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static String sha256(byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value), 32);
        } catch (Exception error) {
            return "";
        }
    }

    private static String hex(byte[] value, int bytes) {
        StringBuilder out = new StringBuilder(bytes * 2);
        for (int i = 0; i < bytes && i < value.length; i++) out.append(String.format("%02x", value[i]));
        return out.toString();
    }
}
