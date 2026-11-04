package org.telegram.botgram;

import java.util.HashMap;

/** Confirmed values belong to one authenticated transport generation, never an account slot. */
public final class BotgramPremiumState {
    private final HashMap<Long, Boolean> users = new HashMap<>();

    public synchronized boolean resolve(long userId, Boolean serverValue) {
        if (serverValue != null) users.put(userId, serverValue);
        return Boolean.TRUE.equals(users.get(userId));
    }
}
