package org.telegram.messenger.voip;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.telegram.botgram.BotgramSpecialCare;
import org.telegram.botgram.BotgramTransport;
import org.telegram.messenger.UserConfig;

public final class IncomingCallNotifications {
    private static final IncomingCallPolicy policies = new IncomingCallPolicy();
    private static final String SESSION = "botgram_call_session";
    private static final String CALL = "botgram_call_id";

    private IncomingCallNotifications() { }

    public static String session(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return null;
        if (BotgramTransport.get(account).enabled()) {
            return BotgramSpecialCare.get(account).sessionIdentity();
        }
        UserConfig config = UserConfig.getInstance(account);
        return config.isClientActivated() ? "native:" + config.getClientUserId() + ":" + config.loginTime
                + ":" + System.identityHashCode(config.getAccountSessionMarker()) : null;
    }

    public static IncomingCallPolicy.Decision begin(int account, long call, long caller, boolean group, Intent intent) {
        String session = session(account);
        IncomingCallPolicy.Decision result = policies.begin(session, account, call, group,
                !group && caller > 0 && BotgramSpecialCare.get(account).isSpecial(caller));
        if (intent != null) {
            intent.putExtra(SESSION, session);
            intent.putExtra(CALL, call);
        }
        return result;
    }

    public static void stamp(Intent intent, int account, long call) {
        intent.putExtra("currentAccount", account);
        intent.putExtra("call_id", call);
        intent.putExtra(SESSION, session(account));
    }

    public static boolean current(int account, Intent intent) {
        if (intent == null) return false;
        String expected = intent.getStringExtra(SESSION);
        return expected != null && expected.equals(session(account));
    }

    public static boolean dispatched(int account, long call) {
        return policies.contains(session(account), account, call, false);
    }

    public static IncomingCallPolicy.Decision recover(int account, long call, boolean group) {
        return policies.recover(session(account), account, call, group);
    }

    public static void finish(int account, long call, boolean group) {
        policies.finish(session(account), account, call, group);
    }

    static void finish(String session, int account, long call, boolean group) {
        policies.finish(session, account, call, group);
    }

    public static void finish(int account, Intent intent, boolean group) {
        if (intent != null) policies.finish(intent.getStringExtra(SESSION), account, intent.getLongExtra(CALL, 0), group);
    }

    public static boolean fullScreenAllowed(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent();
    }
}
