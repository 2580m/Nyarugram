package org.telegram.botgram.e2ee;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;

/** Append-only authenticated history. All methods run on the E2EE worker. */
final class BotgramE2eeHistory {
    private static final int MAGIC = 0x42474831, VERSION = 1, KEY_HASH = 64;
    private static final int MAX_TEXT = 1024 * 1024;
    private final File file;
    private final byte[] key;
    private final HashMap<String, Long> offsets = new HashMap<>();
    private final LinkedHashMap<String, String> memory = new LinkedHashMap<String, String>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 256; }
    };
    private RandomAccessFile stream;
    private int recordCount;
    private boolean pending, closed;

    private BotgramE2eeHistory(File file, byte[] key) { this.file = file; this.key = key; }

    static BotgramE2eeHistory open(File file, byte[] key) throws Exception {
        BotgramE2eeAtomicFile.recover(file);
        BotgramE2eeHistory h = new BotgramE2eeHistory(file, key);
        try {
            if (!file.exists() || file.length() == 0) BotgramE2eeAtomicFile.write(file, header());
            boolean legacy;
            try (DataInputStream in = new DataInputStream(new FileInputStream(file))) { legacy = in.readInt() != MAGIC; }
            if (legacy) h.importLegacy();
            h.scanExisting();
            return h;
        } catch (Exception error) { h.close(); throw error; }
    }

    synchronized String get(String name) {
        if (pending || closed) throw new IllegalStateException("history transaction unavailable");
        String cached = memory.get(name);
        if (cached != null) return cached;
        Long offset = offsets.get(hash(name));
        if (offset == null) return null;
        try {
            String[] entry = readEntry(offset);
            if (!entry[0].equals(name)) throw new IOException("history name");
            memory.put(name, entry[1]);
            return entry[1];
        } catch (Exception error) { throw new IllegalStateException("history authentication", error); }
    }

    /** Old builds keyed the display cache by SHA-256 of the entire wire envelope. */
    synchronized String getLegacy(String wire) { return get(hash(wire)); }

    synchronized void put(String name, String text) throws Exception {
        if (pending || closed) throw new IOException("history unavailable");
        long offset = append(stream, name, text);
        stream.getFD().sync();
        offsets.put(hash(name), offset);
        memory.put(name, text);
        recordCount++;
        maybeCompact();
    }

    /** Staged records are invisible until the vault and journal have committed. */
    synchronized void stage(Map<String, String> entries) throws Exception {
        if (closed) throw new IOException("history closed");
        pending = true;
        for (Map.Entry<String, String> entry : entries.entrySet()) append(stream, entry.getKey(), entry.getValue());
        stream.getFD().sync();
    }

    synchronized void publish() throws Exception {
        if (closed) throw new IOException("history closed");
        scanExisting();
        pending = false;
    }

    synchronized void close() {
        closed = true;
        memory.clear();
        if (stream != null) { try { stream.close(); } catch (IOException ignored) {} stream = null; }
    }

    private void scanExisting() throws Exception {
        if (stream != null) stream.close();
        stream = new RandomAccessFile(file, "rw");
        if (stream.length() < 8 || stream.readInt() != MAGIC || stream.readInt() != VERSION)
            throw new IOException("history header");
        offsets.clear(); memory.clear(); recordCount = 0;
        long good = 8;
        while (good < stream.length()) {
            stream.seek(good);
            if (stream.length() - good < 4) break;
            int length = stream.readInt();
            if (length < 28 || length > 3 * 1024 * 1024) throw new IOException("history length");
            if (stream.getFilePointer() + length + 4 + KEY_HASH > stream.length()) break;
            stream.seek(stream.getFilePointer() + length);
            if (stream.readInt() != KEY_HASH) throw new IOException("history hash");
            byte[] hashBytes = new byte[KEY_HASH]; stream.readFully(hashBytes);
            offsets.put(new String(hashBytes, StandardCharsets.UTF_8), good);
            recordCount++; good = stream.getFilePointer();
        }
        // A crashed append may leave an incomplete record. Never append behind that tail.
        if (good != stream.length()) { stream.setLength(good); stream.getFD().sync(); }
    }

    private void importLegacy() throws Exception {
        byte[] bytes = readBytes(file);
        byte[] plain = BotgramE2eeVaultFile.aesGcm(Cipher.DECRYPT_MODE, key, bytes,
                "botgram-e2ee-display".getBytes(StandardCharsets.UTF_8));
        File replacement = new File(file.getPath() + ".rebuild");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
             RandomAccessFile out = new RandomAccessFile(replacement, "rw")) {
            out.setLength(0); out.write(header());
            int count = in.readInt();
            if (count < 0 || count > 100000) throw new IOException("legacy count");
            for (int i = 0; i < count; i++) append(out, readString(in), readString(in));
            if (in.read() != -1) throw new IOException("legacy trailing");
            out.getFD().sync();
        } finally { Arrays.fill(plain, (byte) 0); }
        // The complete replacement is built before the original is touched.
        BotgramE2eeAtomicFile.replacePrepared(replacement, file);
    }

    private long append(RandomAccessFile out, String name, String text) throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(plain)) { writeString(d, name); writeString(d, text); }
        String id = hash(name);
        byte[] sealed = BotgramE2eeVaultFile.aesGcm(Cipher.ENCRYPT_MODE, key, plain.toByteArray(), aad(id));
        long offset = out.length(); out.seek(offset);
        out.writeInt(sealed.length); out.write(sealed); out.writeInt(KEY_HASH);
        out.write(id.getBytes(StandardCharsets.UTF_8));
        return offset;
    }

    private String[] readEntry(long offset) throws Exception {
        stream.seek(offset); int length = stream.readInt();
        if (length < 28 || length > 3 * 1024 * 1024) throw new IOException("history length");
        byte[] sealed = new byte[length]; stream.readFully(sealed);
        if (stream.readInt() != KEY_HASH) throw new IOException("history hash");
        byte[] hashBytes = new byte[KEY_HASH]; stream.readFully(hashBytes);
        byte[] plain = BotgramE2eeVaultFile.aesGcm(Cipher.DECRYPT_MODE, key, sealed,
                aad(new String(hashBytes, StandardCharsets.UTF_8)));
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
            String[] entry = {readString(in), readString(in)};
            if (in.read() != -1 || !hash(entry[0]).equals(new String(hashBytes, StandardCharsets.UTF_8)))
                throw new IOException("history context");
            return entry;
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    private void maybeCompact() throws Exception {
        if (recordCount <= 1000 || recordCount < offsets.size() * 2) return;
        compact();
    }

    synchronized void compact() throws Exception {
        if (pending || closed) throw new IOException("history unavailable");
        File replacement = new File(file.getPath() + ".rebuild");
        try (RandomAccessFile out = new RandomAccessFile(replacement, "rw")) {
            out.setLength(0); out.write(header());
            for (Long offset : offsets.values()) {
                String[] entry = readEntry(offset); // Authentication errors must preserve the original.
                append(out, entry[0], entry[1]);
            }
            out.getFD().sync();
        }
        stream.close(); stream = null;
        try { BotgramE2eeAtomicFile.replacePrepared(replacement, file); }
        finally { scanExisting(); }
    }

    private static byte[] header() { return new byte[]{0x42,0x47,0x48,0x31,0,0,0,1}; }
    private static byte[] aad(String hash) { return ("botgram-e2ee-history-v1|" + hash).getBytes(StandardCharsets.UTF_8); }
    static String hash(String name) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64); for (byte b : d) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static byte[] readBytes(File file) throws Exception {
        if (file.length() > 128L * 1024 * 1024) throw new IOException("file size");
        byte[] bytes = new byte[(int) file.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) { in.readFully(bytes); }
        return bytes;
    }
    static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_TEXT) throw new IOException("text length"); out.writeInt(b.length); out.write(b);
    }
    static String readString(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 0 || n > MAX_TEXT) throw new IOException("text length");
        byte[] b = new byte[n]; in.readFully(b); return new String(b, StandardCharsets.UTF_8);
    }
}
