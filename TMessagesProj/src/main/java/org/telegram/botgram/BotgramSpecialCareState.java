package org.telegram.botgram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The same synchronized revision/session state is used by Android and host tests.
 *
 * Suppression has two distinct kinds:
 * - pending, bound to one delete/block operation token and never persisted; it ends when the
 *   operation fails (rolling back) or succeeds (becoming confirmed);
 * - confirmed, persisted after the server accepted the delete/block, and dropped only when an
 *   authoritative snapshot no longer lists the user.
 * Pending suppression deliberately does not survive a process restart so an interrupted request
 * cannot suppress a still-existing contact forever; the next server refresh restores the truth.
 */
public final class BotgramSpecialCareState {
    private long generation;
    private long revision = -1;
    private long localMark;
    private long nextOperation;
    private String owner;
    private final Set<Long> users = new HashSet<>();
    private final Set<Long> confirmedSuppressed = new HashSet<>();
    private final Map<Long, Set<Long>> pendingSuppressed = new HashMap<>();

    public static String owner(int account, String server, String user, String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (account + "\n" + server + "\n" + user + "\n" + token).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public synchronized long bind(String value) {
        if (!Objects.equals(owner, value)) {
            owner = value;
            generation++;
            revision = -1;
            localMark = 0;
            nextOperation = 0;
            users.clear();
            confirmedSuppressed.clear();
            pendingSuppressed.clear();
        }
        return generation;
    }
    public synchronized long generation() { return generation; }
    public synchronized boolean active(long value) { return owner != null && value == generation; }
    public synchronized long revision() { return revision; }
    /** Local mutation counter captured before an asynchronous disk read. */
    public synchronized long localMark() { return localMark; }

    public synchronized boolean isSpecial(long id) {
        return owner != null && users.contains(id)
                && !confirmedSuppressed.contains(id) && !pendingSuppressed.containsKey(id);
    }
    public synchronized boolean isPending(long id) { return pendingSuppressed.containsKey(id); }
    public synchronized ArrayList<Long> list() {
        ArrayList<Long> result = new ArrayList<>();
        for (long id : users) {
            if (!confirmedSuppressed.contains(id) && !pendingSuppressed.containsKey(id)) result.add(id);
        }
        java.util.Collections.sort(result);
        return result;
    }
    /** Persisted confirmed removals only; pending operations are never written to disk. */
    public synchronized ArrayList<Long> suppressed() { return new ArrayList<>(confirmedSuppressed); }
    /** Cache the server snapshot, not list(), which also filters transient pending operations. */
    public synchronized ArrayList<Long> cachedUsers() { return new ArrayList<>(users); }

    /** Applies an authoritative server snapshot. Pending operations keep blocking this snapshot. */
    public synchronized boolean apply(long session, long version, Collection<Long> ids) {
        if (!active(session) || version < 0 || version < revision) return false;
        users.clear();
        for (long id : ids) if (id > 0) users.add(id);
        confirmedSuppressed.removeIf(id -> !users.contains(id));
        revision = version;
        return true;
    }

    /**
     * Restores the disk cache atomically. Every precondition is checked before any field changes,
     * so a rejected snapshot (stale revision, replaced session, or local mutation after the read
     * started) leaves users, revision and suppression untouched.
     */
    public synchronized boolean restoreDisk(long session, long mark, long version, Collection<Long> ids, Collection<Long> suppressed) {
        if (!active(session) || mark != localMark || version < 0 || revision >= 0) return false;
        users.clear();
        for (long id : ids) if (id > 0) users.add(id);
        confirmedSuppressed.clear();
        for (long id : suppressed) if (id > 0) confirmedSuppressed.add(id);
        revision = version;
        return true;
    }

    /** Records every valid operation, including targets not yet present in the cold-start cache. */
    public synchronized long beginSuppress(long session, long id) {
        if (!active(session) || id <= 0) return 0;
        long token = ++nextOperation;
        if (token <= 0) {
            nextOperation = 0;
            token = ++nextOperation;
        }
        pendingSuppressed.computeIfAbsent(id, ignored -> new HashSet<>()).add(token);
        localMark++;
        return token;
    }

    /** The backend accepted the delete/block: pending becomes the persisted confirmed cleanup. */
    public synchronized boolean confirmSuppress(long session, long id, long token) {
        if (!active(session) || token == 0) return false;
        if (!removePending(id, token)) return false;
        confirmedSuppressed.add(id);
        localMark++;
        return true;
    }

    /** The delete/block failed: only that operation's pending suppression is rolled back. */
    public synchronized boolean cancelSuppress(long session, long id, long token) {
        if (!active(session) || token == 0) return false;
        if (!removePending(id, token)) return false;
        localMark++;
        return true;
    }

    private boolean removePending(long id, long token) {
        Set<Long> operations = pendingSuppressed.get(id);
        if (operations == null || !operations.remove(token)) return false;
        if (operations.isEmpty()) pendingSuppressed.remove(id);
        return true;
    }

    /** Explicit re-enable wins over local suppression for the current session. */
    public synchronized boolean confirmedEnable(long session, long id) {
        if (!active(session) || !users.contains(id)) return false;
        pendingSuppressed.remove(id);
        confirmedSuppressed.remove(id);
        localMark++;
        return true;
    }
}
