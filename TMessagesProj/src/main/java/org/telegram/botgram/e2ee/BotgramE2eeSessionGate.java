package org.telegram.botgram.e2ee;

/**
 * Serialized validity token for asynchronous PIN, vault and session work.
 *
 * <p>Every operation captures the generation and bound identity when it starts. Lock, logout,
 * account switch, reset and session replacement advance the generation, so a late result can
 * never install keys, write another account's file, clear another account's retry backoff or
 * navigate the UI as success for the old operation.
 */
public final class BotgramE2eeSessionGate {
    private long generation;
    private String identity = "";

    public synchronized long generation() { return generation; }

    /** Binds the gate to an identity; a new server/user invalidates all outstanding work. */
    public synchronized boolean observe(String hash) {
        if (hash.equals(identity)) return false;
        identity = hash == null ? "" : hash;
        generation++;
        return true;
    }

    /** Invalidates all outstanding work (background lock, logout, reset, session replacement). */
    public synchronized long invalidate() { return ++generation; }

    public synchronized boolean current(long token, String hash) {
        return token == generation && hash != null && hash.equals(identity);
    }
}
