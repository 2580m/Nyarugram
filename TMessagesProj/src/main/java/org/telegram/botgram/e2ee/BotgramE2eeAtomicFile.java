package org.telegram.botgram.e2ee;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Crash-safe replacement for a single file.
 *
 * <p>The new bytes are written and synced to a sibling temporary file first. The target is
 * never deleted before a successful replacement exists: if the atomic rename is not
 * supported (some non-Linux file systems), the old file is moved to a backup, the temporary
 * file becomes the target, and only then is the backup removed. A failure at any point leaves
 * either the old file or a recoverable backup in place.
 */
public final class BotgramE2eeAtomicFile {
    private BotgramE2eeAtomicFile() {}

    /** Recover the Windows fallback if the process stopped between the two renames. */
    public static void recover(File target) throws IOException {
        File backup = new File(target.getPath() + ".bak");
        if (!target.exists() && backup.exists() && !backup.renameTo(target))
            throw new IOException("restore backup");
    }

    public static void write(File target, byte[] bytes) throws IOException {
        if (target == null) throw new IOException("path");
        recover(target);
        if (target.isDirectory()) throw new IOException("directory target");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("directory");
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        replacePrepared(tmp, target);
    }

    /** Source must be a fully written, fsynced sibling file. */
    public static void replacePrepared(File tmp, File target) throws IOException {
        recover(target);
        if (target.isDirectory()) throw new IOException("directory target");
        if (tmp.renameTo(target)) return;
        File backup = new File(target.getPath() + ".bak");
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
        if (target.exists() && !target.renameTo(backup)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("replace");
        }
        if (!tmp.renameTo(target)) {
            if (backup.exists()) //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("replace");
        }
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
    }
}
