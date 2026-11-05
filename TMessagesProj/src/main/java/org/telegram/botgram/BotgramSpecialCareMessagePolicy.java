package org.telegram.botgram;

/** Platform-independent admission rules shared by foreground and system reminders. */
public final class BotgramSpecialCareMessagePolicy {
    private BotgramSpecialCareMessagePolicy() {}

    public static boolean isDirect(long dialogId, long selfId, long senderId, boolean outgoing,
                                   boolean service, boolean synthetic, boolean special) {
        return dialogId > 0 && dialogId != selfId && senderId == dialogId && !outgoing
                && !service && !synthetic && special;
    }

    public static boolean isFresh(int id, int previousId, int date, int now) {
        return id > 0 && id > previousId && date > 0 && date >= now - 60 && date <= now + 30;
    }

    public static boolean routeSpecialAlert(boolean eligibleChild, boolean ordinaryPending, boolean supportsChildren) {
        return eligibleChild && !ordinaryPending && supportsChildren;
    }

    public static boolean silenceSummary(boolean specialAlert, boolean specialLast, boolean ordinaryPending) {
        return specialAlert || specialLast && !ordinaryPending;
    }

    public static boolean ownsChildAlert(Object candidate, Object displayedMessage) {
        return candidate != null && candidate == displayedMessage;
    }

    public static boolean canVibrate(boolean allowed, boolean currentSwitch, boolean valid) {
        return allowed && currentSwitch && valid;
    }

    public static boolean canAlert(boolean globalEnabled, boolean muted, boolean soundEnabled,
                                   boolean silent, boolean recording) {
        return globalEnabled && !muted && soundEnabled && !silent && !recording;
    }
}
