package org.telegram.messenger;
public final class AccountInstance {
    private static final AccountInstance[] accounts = {new AccountInstance(), new AccountInstance()};
    private final UserConfig config = new UserConfig();
    private final MessagesController controller = new MessagesController();
    public static AccountInstance getInstance(int account) { return accounts[account]; }
    public UserConfig getUserConfig() { return config; }
    public MessagesController getMessagesController() { return controller; }
}
