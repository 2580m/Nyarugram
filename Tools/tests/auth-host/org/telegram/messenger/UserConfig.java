package org.telegram.messenger;
public final class UserConfig {
    public long userId = 42;
    public int clears;
    public long getClientUserId() { return userId; }
    public void clearConfig() { clears++; userId = 0; }
}
