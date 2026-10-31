package org.telegram.botgram;
/** Binding fixture only: the test executes the real compiled ConnectionsManager. */
public final class BotgramTransport {
    private static final BotgramTransport[] accounts = {new BotgramTransport(), new BotgramTransport()};
    public boolean bound;
    public static BotgramTransport get(int account) { return accounts[account]; }
    public boolean enabled() { return bound; }
}
