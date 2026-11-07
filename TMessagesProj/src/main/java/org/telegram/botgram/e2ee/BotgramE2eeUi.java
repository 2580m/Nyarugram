package org.telegram.botgram.e2ee;

import android.widget.Toast;

import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public final class BotgramE2eeUi {
    private BotgramE2eeUi() {}

    public static void toast(int resource) {
        toastText(LocaleController.getString(resource));
    }

    public static void toastText(String text) {
        if (text == null || text.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
        });
    }

    public static String statusText(BotgramRpc.E2eeChatStatus status) {
        if (status == null) return LocaleController.getString(R.string.BotgramE2eeOff);
        switch (status.getState()) {
            case E2EE_ACTIVE: return LocaleController.getString(R.string.BotgramE2eeStatusActive);
            case E2EE_PENDING: return LocaleController.getString(status.getSelfInitiator()
                    ? R.string.BotgramE2eeOn : R.string.BotgramE2eeOff);
            case E2EE_CLOSING: return LocaleController.getString(R.string.BotgramE2eeStatusClosing);
            case E2EE_SUSPENDED: return LocaleController.getString(R.string.BotgramE2eeStatusSuspended);
            case E2EE_CLOSED:
            case E2EE_DISABLED:
            default: return LocaleController.getString(R.string.BotgramE2eeOff);
        }
    }

    public static String profileValue(BotgramRpc.E2eeChatStatus status) {
        if (status == null) return LocaleController.getString(R.string.BotgramE2eeOff);
        switch (status.getState()) {
            case E2EE_ACTIVE:
                return LocaleController.getString(R.string.BotgramE2eeOn);
            case E2EE_PENDING:
                return LocaleController.getString(status.getSelfInitiator()
                        ? R.string.BotgramE2eeOn : R.string.BotgramE2eeOff);
            case E2EE_CLOSING:
                return LocaleController.getString(status.getAwaitingSelf()
                        ? R.string.BotgramE2eeStatusClosing : R.string.BotgramE2eeWaiting);
            case E2EE_SUSPENDED:
                return LocaleController.getString(R.string.BotgramE2eeStatusSuspended);
            default:
                return LocaleController.getString(R.string.BotgramE2eeOff);
        }
    }

    public static boolean isActiveOrPending(BotgramRpc.E2eeChatStatus status) {
        if (status == null) return false;
        switch (status.getState()) {
            case E2EE_ACTIVE:
            case E2EE_CLOSING:
            case E2EE_PENDING:
            case E2EE_SUSPENDED:
                return true;
            default:
                return false;
        }
    }
}
