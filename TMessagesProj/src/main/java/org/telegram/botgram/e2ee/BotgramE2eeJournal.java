package org.telegram.botgram.e2ee;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;

/** Encrypted redo log: a durable intent is rolled forward before any history is exposed. */
final class BotgramE2eeJournal {
    interface Fault { void after(String step) throws Exception; }
    private static final byte[] AAD = "botgram-e2ee-transaction-v1".getBytes(StandardCharsets.UTF_8);
    private final File vault, journal;
    private final BotgramE2eeHistory history;
    private final byte[] key;
    private final Fault fault;

    BotgramE2eeJournal(File vault, BotgramE2eeHistory history, byte[] key) {
        this(vault, history, key, step -> {});
    }
    BotgramE2eeJournal(File vault, BotgramE2eeHistory history, byte[] key, Fault fault) {
        this.vault = vault; this.history = history; this.key = key; this.fault = fault;
        journal = new File(vault.getPath() + ".txn");
    }

    synchronized void commit(byte[] wrappedVault, Map<String, String> entries) throws Exception {
        BotgramE2eeAtomicFile.recover(journal);
        if (journal.exists()) throw new IOException("transaction recovery required");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(1); out.writeInt(wrappedVault.length); out.write(wrappedVault);
            out.writeInt(entries.size());
            for (Map.Entry<String, String> e : entries.entrySet()) {
                BotgramE2eeHistory.writeString(out, e.getKey());
                BotgramE2eeHistory.writeString(out, e.getValue());
            }
        }
        byte[] plain = buffer.toByteArray();
        try {
            BotgramE2eeAtomicFile.write(journal,
                    BotgramE2eeVaultFile.aesGcm(Cipher.ENCRYPT_MODE, key, plain, AAD));
        } finally { Arrays.fill(plain, (byte) 0); }
        // Fail closed even if the fault is immediately after persisting the intent.
        history.stage(Collections.emptyMap());
        fault.after("intent");
        finish(wrappedVault, entries);
    }

    synchronized boolean recover() throws Exception {
        BotgramE2eeAtomicFile.recover(vault);
        BotgramE2eeAtomicFile.recover(journal);
        if (!journal.exists()) return false;
        byte[] plain = BotgramE2eeVaultFile.aesGcm(Cipher.DECRYPT_MODE, key,
                BotgramE2eeHistory.readBytes(journal), AAD);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
            if (in.readInt() != 1) throw new IOException("transaction version");
            int n = in.readInt();
            if (n < 1 || n > 64 * 1024 * 1024) throw new IOException("transaction vault size");
            byte[] next = new byte[n]; in.readFully(next);
            int count = in.readInt();
            if (count < 0 || count > 16) throw new IOException("transaction entry count");
            Map<String, String> entries = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) entries.put(BotgramE2eeHistory.readString(in), BotgramE2eeHistory.readString(in));
            if (in.read() != -1) throw new IOException("transaction trailing data");
            finish(next, entries);
        } finally { Arrays.fill(plain, (byte) 0); }
        return true;
    }

    private void finish(byte[] wrappedVault, Map<String, String> entries) throws Exception {
        history.stage(entries);
        fault.after("history");
        BotgramE2eeAtomicFile.write(vault, wrappedVault);
        fault.after("vault");
        if (!journal.delete()) throw new IOException("transaction cleanup");
        history.publish();
    }
}
