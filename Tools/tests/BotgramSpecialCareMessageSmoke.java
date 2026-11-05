import org.telegram.botgram.BotgramSpecialCareMessagePolicy;

public final class BotgramSpecialCareMessageSmoke {
    private static int assertions;
    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        check(BotgramSpecialCareMessagePolicy.isDirect(20, 10, 20, false, false, false, true), "incoming contact");
        check(!BotgramSpecialCareMessagePolicy.isDirect(-30, 10, 20, false, false, false, true), "group mention not promoted");
        check(!BotgramSpecialCareMessagePolicy.isDirect(30, 10, 20, false, false, false, true), "forward does not change sender");
        check(!BotgramSpecialCareMessagePolicy.isDirect(10, 10, 10, false, false, false, true), "self excluded");
        check(!BotgramSpecialCareMessagePolicy.isDirect(20, 10, 20, true, false, false, true), "outgoing excluded");
        check(!BotgramSpecialCareMessagePolicy.isDirect(20, 10, 20, false, true, false, true), "service excluded");
        check(!BotgramSpecialCareMessagePolicy.isDirect(20, 10, 20, false, false, true, true), "reaction push excluded");
        check(!BotgramSpecialCareMessagePolicy.isDirect(20, 10, 20, false, false, false, false), "unknown account cache ordinary");
        int seen = 0;
        int alerts = 0;
        int[][] delivery = {{5, 999}, {5, 999}, {4, 998}, {6, 800}, {7, 1000}};
        for (int[] event : delivery) {
            if (BotgramSpecialCareMessagePolicy.isFresh(event[0], seen, event[1], 1000)) alerts++;
            seen = Math.max(seen, event[0]);
        }
        check(alerts == 2, "duplicate, out of order and history deliveries suppressed");
        check(!BotgramSpecialCareMessagePolicy.isFresh(7, seen, 1000, 1000), "persisted high water survives replay");
        check(!BotgramSpecialCareMessagePolicy.isFresh(8, seen, 1031, 1000), "future timestamp rejected");
        check(!BotgramSpecialCareMessagePolicy.isFresh(0, 0, 1000, 1000), "placeholder has no new identity");
        for (int switches = 0; switches < 32; switches++) {
            boolean global = (switches & 1) != 0, muted = (switches & 2) != 0;
            boolean sound = (switches & 4) != 0, silent = (switches & 8) != 0, recording = (switches & 16) != 0;
            boolean result = BotgramSpecialCareMessagePolicy.canAlert(global, muted, sound, silent, recording);
            check(result == (switches == 5), "all notification switches respected: " + switches);
        }
        Object newestSpecial = new Object(), olderSpecial = new Object();
        check(!BotgramSpecialCareMessagePolicy.ownsChildAlert(olderSpecial, newestSpecial),
                "same dialog older pending message cannot lend its alert to the displayed message");
        check(BotgramSpecialCareMessagePolicy.ownsChildAlert(newestSpecial, newestSpecial),
                "displayed pending message owns the child alert");
        check(!BotgramSpecialCareMessagePolicy.ownsChildAlert(null, null), "story child has no special alert");
        for (boolean summary : new boolean[]{false, true}) {
            for (boolean specialLast : new boolean[]{false, true}) {
                for (boolean ordinaryPending : new boolean[]{false, true}) {
                    for (boolean eligibleChild : new boolean[]{false, true}) {
                        boolean routed = BotgramSpecialCareMessagePolicy.routeSpecialAlert(eligibleChild, ordinaryPending, true);
                        boolean silenceSummary = BotgramSpecialCareMessagePolicy.silenceSummary(routed, specialLast, ordinaryPending);
                        // Ordinary children retain GROUP_ALERT_SUMMARY; only the special owner uses CHILDREN.
                        int specialAlerts = routed ? 1 : 0;
                        int ordinaryAlerts = summary && !silenceSummary ? 1 : 0;
                        check(specialAlerts + ordinaryAlerts <= 1, "aggregate and special child never both alert");
                        if (ordinaryPending) {
                            check(!silenceSummary && !routed, "ordinary batch retains original summary regardless of old special last");
                        }
                        if (!ordinaryPending && eligibleChild) check(routed && silenceSummary, "special-only batch delegates exactly once");
                    }
                }
            }
        }
        check(!BotgramSpecialCareMessagePolicy.routeSpecialAlert(true, false, false), "SDK19 has no child to own an alert");
        check(BotgramSpecialCareMessagePolicy.silenceSummary(false, true, false), "history-only special refresh stays silent");
        check(!BotgramSpecialCareMessagePolicy.canVibrate(true, false, true), "in-app vibration switched off before execution");
        check(!BotgramSpecialCareMessagePolicy.canVibrate(false, true, true), "channel vibration disabled");
        check(!BotgramSpecialCareMessagePolicy.canVibrate(true, true, false), "stale foreground callback cannot vibrate");
        check(BotgramSpecialCareMessagePolicy.canVibrate(true, true, true), "enabled foreground vibration");
        System.out.println("PASS " + assertions + " real message policy assertions");
    }
}
