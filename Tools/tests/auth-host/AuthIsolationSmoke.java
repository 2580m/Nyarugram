import org.telegram.botgram.BotgramTransport;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.ConnectionsManager;

public final class AuthIsolationSmoke {
    public static void main(String[] args) {
        BotgramTransport.get(0).bound = true;
        ConnectionsManager.onLogout(0);
        AndroidUtilities.drain();
        check(AccountInstance.getInstance(0).getUserConfig().getClientUserId() == 42, "HTTP identity survived native logout");
        check(AccountInstance.getInstance(0).getMessagesController().logouts == 0, "HTTP logout was suppressed");

        // An old native callback may already be queued when HTTP login completes.
        BotgramTransport.get(0).bound = false;
        ConnectionsManager.onLogout(0);
        BotgramTransport.get(0).bound = true;
        AndroidUtilities.drain();
        check(AccountInstance.getInstance(0).getUserConfig().clears == 0, "queued native logout cannot clear newly bound account");

        ConnectionsManager.onLogout(1);
        AndroidUtilities.drain();
        check(AccountInstance.getInstance(1).getUserConfig().getClientUserId() == 0, "unbound native account still logs out");
        check(AccountInstance.getInstance(1).getMessagesController().logouts == 1, "native logout forwarded once");
        check(AccountInstance.getInstance(0).getUserConfig().getClientUserId() == 42, "account slots remain isolated");
        System.out.println("PASS: 6 assertions against compiled ConnectionsManager native-logout callback");
    }
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
