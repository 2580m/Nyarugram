package org.telegram.botgram.e2ee;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * PIN and device protected vault file.
 *
 * <p>Version 2 nests the PIN wrapping inside the Android Keystore wrapping:
 * {@code outer = device.encrypt(AES-GCM(pbkdf2(pin), vaultKey))}. Opening therefore
 * requires both the correct PIN and the corresponding non-exportable device key;
 * neither layer alone can recover the vault key. Version 1 files had two independent
 * wrappings and are only accepted so that a successfully verified unlock can rewrite
 * them into version 2.
 *
 * <p>The four digit PIN is a local convenience lock, not a message key. File copies,
 * a compromised device and memory inspection are explicitly outside its guarantees.
 */
public final class BotgramE2eeVaultFile {
    public interface DeviceCipher {
        byte[] encrypt(byte[] plaintext) throws Exception;
        byte[] decrypt(byte[] payload) throws Exception;
    }

    public static final class Vault {
        public final byte[] bytes;
        public final byte[] vaultKey;
        /** Non-null only for a legacy version 1 file that can now be rewritten safely. */
        public final byte[] migrated;
        Vault(byte[] bytes, byte[] vaultKey, byte[] migrated) {
            this.bytes = bytes;
            this.vaultKey = vaultKey;
            this.migrated = migrated;
        }
    }

    /** The device layer could not be used; this must not be reported as a wrong PIN. */
    public static final class DeviceFailure extends Exception {
        DeviceFailure(String message, Throwable cause) { super(message, cause); }
    }

    /** Either PIN-derived authentication failed or the legacy PIN blob did not match. */
    public static final class PinFailure extends Exception {
        PinFailure() { super("PIN mismatch"); }
    }

    private static final int MAGIC = 0x42474532;
    private static final int VERSION_LEGACY = 1;
    private static final int VERSION = 2;
    public static final int ITERATIONS = 120_000;
    private static final int SALT = 16;
    private static final int IV = 12;
    private static final int MAX_BLOB = 64 * 1024 * 1024;

    private static final byte[] AAD_PIN_LEGACY = "botgram-e2ee-pin".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_VAULT_LEGACY = "botgram-e2ee-vault".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_VAULT = "botgram-e2ee-vault-v2".getBytes(StandardCharsets.UTF_8);
    private static final String AAD_PIN_PREFIX = "botgram-e2ee-pin-v2|";

    private BotgramE2eeVaultFile() {}

    public static byte[] create(String pin, byte[] vaultBytes, DeviceCipher device) throws Exception {
        if (!validPin(pin)) throw new IllegalArgumentException("PIN must be four digits");
        SecureRandom random = new SecureRandom();
        byte[] vaultKey = new byte[32];
        random.nextBytes(vaultKey);
        try {
            return createWithKey(pin, vaultKey, vaultBytes, device, random);
        } finally {
            Arrays.fill(vaultKey, (byte) 0);
        }
    }

    public static Vault open(String pin, byte[] file, DeviceCipher device) throws Exception {
        Header header = read(file);
        if (header.version == VERSION) {
            byte[] pinKey = derive(pin, header.salt, header.iterations);
            try {
                byte[] pinBlob = device(device, header.outerBlob, false);
                byte[] vaultKey = pinDecrypt(pinKey, pinBlob, header);
                byte[] vault = aesGcm(Cipher.DECRYPT_MODE, vaultKey, header.vaultBlob, AAD_VAULT);
                return new Vault(vault, vaultKey, null);
            } finally {
                Arrays.fill(pinKey, (byte) 0);
            }
        }
        // Legacy layout: two independent wrappings. Both are still verified before the
        // vault is exposed, but the file is immediately rewritten into version 2 when the
        // caller persists the migrated bytes.
        byte[] pinKey = deriveLegacy(pin, header.salt, header.iterations);
        byte[] deviceKey;
        try {
            deviceKey = device(device, header.deviceBlob, false);
        } finally {
            Arrays.fill(pinKey, (byte) 0);
        }
        byte[] pinKeyValue;
        try {
            pinKey = deriveLegacy(pin, header.salt, header.iterations);
            pinKeyValue = aesGcm(Cipher.DECRYPT_MODE, pinKey, header.pinBlob, AAD_PIN_LEGACY);
        } catch (Exception error) {
            throw new PinFailure();
        } finally {
            Arrays.fill(pinKey, (byte) 0);
        }
        if (!MessageDigest.isEqual(deviceKey, pinKeyValue)) throw new PinFailure();
        byte[] vault = aesGcm(Cipher.DECRYPT_MODE, deviceKey, header.vaultBlob, AAD_VAULT_LEGACY);
        byte[] migrated = null;
        try {
            migrated = createWithKey(pin, deviceKey, vault, device, new SecureRandom());
        } catch (Exception error) {
            // A deferred rewrite still leaves both legacy layers verified and readable.
            migrated = null;
        }
        return new Vault(vault, deviceKey, migrated);
    }

    /** Re-wraps only the random vault key; protocol identity and sessions are unchanged. */
    public static byte[] changePin(String oldPin, String newPin, byte[] file, DeviceCipher device)
            throws Exception {
        if (!validPin(newPin)) throw new IllegalArgumentException("PIN must be four digits");
        Vault opened = open(oldPin, file, device);
        try {
            // rewrapPin also converts a legacy vault blob to the version 2 AAD.
            return rewrapPin(newPin, file, opened.vaultKey, device);
        } finally {
            Arrays.fill(opened.vaultKey, (byte) 0);
        }
    }

    /** Persists changed protocol state without touching PIN/device wrapping. */
    public static byte[] rewriteVault(byte[] file, byte[] vaultKey, byte[] vaultBytes) throws Exception {
        Header header = read(file);
        byte[] vaultBlob = aesGcm(Cipher.ENCRYPT_MODE, vaultKey, vaultBytes,
                header.version == VERSION ? AAD_VAULT : AAD_VAULT_LEGACY);
        return write(header, vaultBlob);
    }

    /** Re-wraps an already unlocked vault under a new PIN; the old PIN was verified before unlock. */
    public static byte[] rewrapPin(String newPin, byte[] file, byte[] vaultKey, DeviceCipher device)
            throws Exception {
        if (!validPin(newPin)) throw new IllegalArgumentException("PIN must be four digits");
        Header header = read(file);
        byte[] vaultKeyValue;
        byte[] vaultBlob;
        if (header.version == VERSION) {
            // Prove the same device key still protects this file before replacing its PIN layer.
            byte[] pinBlob = device(device, header.outerBlob, false);
            vaultKeyValue = vaultKey;
            vaultBlob = header.vaultBlob;
        } else {
            byte[] deviceKey = device(device, header.deviceBlob, false);
            if (!MessageDigest.isEqual(deviceKey, vaultKey)) throw new PinFailure();
            vaultKeyValue = vaultKey;
            vaultBlob = aesGcm(Cipher.ENCRYPT_MODE, vaultKeyValue,
                    aesGcm(Cipher.DECRYPT_MODE, vaultKeyValue, header.vaultBlob, AAD_VAULT_LEGACY), AAD_VAULT);
        }
        return writeV2(newPin, vaultKeyValue, null, vaultBlob, device, new SecureRandom());
    }

    public static boolean validPin(String pin) {
        if (pin == null || pin.length() != 4) return false;
        for (int i = 0; i < 4; i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        return true;
    }

    /** Standard PBKDF2-HMAC-SHA256; platform implementation when available. */
    static byte[] derive(String pin, byte[] salt, int iterations) throws Exception {
        byte[] password = pin.getBytes(StandardCharsets.UTF_8);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
            try {
                byte[] value = factory.generateSecret(spec).getEncoded();
                if (value != null && value.length == 32) return value;
            } finally {
                spec.clearPassword();
            }
        } catch (java.security.NoSuchAlgorithmException ignored) {
            // Android below API 26 has no PBKDF2WithHmacSHA256 provider; use the verified fallback.
        } finally {
            Arrays.fill(password, (byte) 0);
        }
        return deriveFallback(pin, salt, iterations);
    }

    /** Verbatim RFC 8018 PBKDF2-HMAC-SHA256 used only where the platform has no provider. */
    static byte[] deriveFallback(String pin, byte[] salt, int iterations) throws Exception {
        if (iterations < 1) throw new IllegalArgumentException("iterations");
        byte[] password = pin.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            byte[] block = new byte[32];
            byte[] u = new byte[salt.length + 4];
            System.arraycopy(salt, 0, u, 0, salt.length);
            u[salt.length + 3] = 1; // single block: INT(1), big endian
            byte[] first = mac.doFinal(u);
            System.arraycopy(first, 0, block, 0, block.length);
            byte[] previous = first;
            for (int round = 1; round < iterations; round++) {
                previous = mac.doFinal(previous);
                for (int i = 0; i < block.length; i++) block[i] ^= previous[i];
            }
            return block;
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    private static byte[] deriveLegacy(String pin, byte[] salt, int iterations) throws Exception {
        // Old file format only; not used for new wrapping.
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
        Arrays.fill(password, (byte) 0);
        return result;
    }

    static byte[] aesGcm(int mode, byte[] key, byte[] input, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[IV];
        if (mode == Cipher.ENCRYPT_MODE) {
            new SecureRandom().nextBytes(iv);
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(input);
            byte[] payload = new byte[IV + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, IV);
            System.arraycopy(encrypted, 0, payload, IV, encrypted.length);
            return payload;
        }
        if (input.length < IV + 16) throw new IOException("ciphertext");
        System.arraycopy(input, 0, iv, 0, IV);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        byte[] payload = new byte[input.length - IV];
        System.arraycopy(input, IV, payload, 0, payload.length);
        return cipher.doFinal(payload);
    }

    private static byte[] createWithKey(String pin, byte[] vaultKey, byte[] vaultBytes,
            DeviceCipher device, SecureRandom random) throws Exception {
        byte[] salt = new byte[SALT];
        random.nextBytes(salt);
        byte[] pinKey = derive(pin, salt, ITERATIONS);
        try {
            byte[] pinBlob = aesGcm(Cipher.ENCRYPT_MODE, pinKey, vaultKey,
                    pinAad(salt, ITERATIONS, true));
            byte[] outer = device(device, pinBlob, true);
            byte[] vaultBlob = aesGcm(Cipher.ENCRYPT_MODE, vaultKey, vaultBytes, AAD_VAULT);
            return writeV2(salt, ITERATIONS, outer, vaultBlob);
        } finally {
            Arrays.fill(pinKey, (byte) 0);
        }
    }

    private static byte[] writeV2(String pin, byte[] vaultKey, byte[] vaultBytes, byte[] vaultBlob,
            DeviceCipher device, SecureRandom random) throws Exception {
        byte[] salt = new byte[SALT];
        random.nextBytes(salt);
        byte[] pinKey = derive(pin, salt, ITERATIONS);
        try {
            byte[] pinBlob = aesGcm(Cipher.ENCRYPT_MODE, pinKey, vaultKey,
                    pinAad(salt, ITERATIONS, true));
            byte[] outer = device(device, pinBlob, true);
            byte[] blob = vaultBlob != null ? vaultBlob
                    : aesGcm(Cipher.ENCRYPT_MODE, vaultKey, vaultBytes, AAD_VAULT);
            return writeV2(salt, ITERATIONS, outer, blob);
        } finally {
            Arrays.fill(pinKey, (byte) 0);
        }
    }

    private static byte[] writeV2(byte[] salt, int iterations, byte[] outer, byte[] vaultBlob)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(iterations);
            out.write(salt);
            writeBytes(out, outer);
            writeBytes(out, vaultBlob);
        }
        return buffer.toByteArray();
    }

    private static byte[] device(DeviceCipher device, byte[] input, boolean encrypt) throws Exception {
        try {
            return encrypt ? device.encrypt(input) : device.decrypt(input);
        } catch (Exception error) {
            throw new DeviceFailure("device key unavailable", error);
        }
    }

    private static byte[] pinDecrypt(byte[] pinKey, byte[] pinBlob, Header header) throws Exception {
        try {
            return aesGcm(Cipher.DECRYPT_MODE, pinKey, pinBlob,
                    pinAad(header.salt, header.iterations, true));
        } catch (Exception error) {
            throw new PinFailure();
        }
    }

    private static byte[] pinAad(byte[] salt, int iterations, boolean v2) {
        if (!v2) return AAD_PIN_LEGACY;
        return (AAD_PIN_PREFIX + iterations + "|" + hex(salt)).getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format("%02x", item));
        return out.toString();
    }

    private static byte[] write(Header header, byte[] vaultBlob) throws IOException {
        if (header.version == VERSION) return writeV2(header.salt, header.iterations, header.outerBlob, vaultBlob);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION_LEGACY);
            out.writeInt(header.iterations);
            out.write(header.salt);
            writeBytes(out, header.deviceBlob);
            writeBytes(out, header.pinBlob);
            writeBytes(out, vaultBlob);
        }
        return buffer.toByteArray();
    }

    private static final class Header {
        int version; byte[] salt; int iterations;
        byte[] outerBlob; byte[] vaultBlob;
        byte[] deviceBlob; byte[] pinBlob;
    }

    private static Header read(byte[] file) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(file))) {
            if (in.readInt() != MAGIC) throw new IOException("vault format");
            Header header = new Header();
            header.version = in.readInt();
            if (header.version != VERSION && header.version != VERSION_LEGACY)
                throw new IOException("vault format");
            header.iterations = in.readInt();
            if (header.iterations < 10_000 || header.iterations > 1_000_000) throw new IOException("iterations");
            header.salt = new byte[SALT];
            in.readFully(header.salt);
            if (header.version == VERSION) {
                header.outerBlob = readBytes(in);
                header.vaultBlob = readBytes(in);
            } else {
                header.deviceBlob = readBytes(in);
                header.pinBlob = readBytes(in);
                header.vaultBlob = readBytes(in);
            }
            if (in.read() != -1) throw new IOException("trailing data");
            return header;
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BLOB) throw new IOException("length");
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }
}
