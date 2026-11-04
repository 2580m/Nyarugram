import org.telegram.botgram.BotgramPremiumState;
import org.telegram.botgram.protocol.v1.BotgramRpc;

public final class BotgramPremiumSmoke {
    private static int checks;

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static boolean resolve(BotgramPremiumState state, long user, BotgramRpc.UserSummary summary) {
        return state.resolve(user, summary.hasNyarugramPremium() ? summary.getNyarugramPremium() : null);
    }

    public static void main(String[] args) throws Exception {
        BotgramRpc.UserSummary absent = BotgramRpc.UserSummary.getDefaultInstance();
        BotgramRpc.UserSummary disabled = BotgramRpc.UserSummary.parseFrom(
                BotgramRpc.UserSummary.newBuilder().setNyarugramPremium(false).build().toByteArray());
        BotgramRpc.UserSummary enabled = BotgramRpc.UserSummary.parseFrom(
                BotgramRpc.UserSummary.newBuilder().setNyarugramPremium(true).build().toByteArray());
        check(!absent.hasNyarugramPremium(), "old response has no presence");
        check(disabled.hasNyarugramPremium() && !disabled.getNyarugramPremium(), "explicit false survives wire");
        check(enabled.hasNyarugramPremium() && enabled.getNyarugramPremium(), "explicit true survives wire");
        BotgramPremiumState first = new BotgramPremiumState();
        check(!resolve(first, 7, absent), "new account defaults locked");
        check(resolve(first, 7, enabled), "server true unlocks");
        check(resolve(first, 7, absent), "old response cannot clear confirmed state");
        check(!resolve(first, 8, absent), "another user cannot inherit state");
        check(!resolve(first, 7, disabled), "server false revokes");
        check(!resolve(first, 7, absent), "absence preserves confirmed false");
        check(first.resolve(7, true), "REST activation confirms state");
        check(resolve(first, 7, absent), "old PB response preserves REST activation");
        check(first.resolve(7, null), "legacy GID response preserves confirmed Premium");
        check(!first.resolve(7, false), "explicit GID false revokes Premium");
        check(first.resolve(7, true), "explicit GID true restores Premium");
        check(!resolve(new BotgramPremiumState(), 7, absent), "new login cannot inherit old session");
        check(!resolve(new BotgramPremiumState(), 7, absent), "another slot cannot inherit state");
        System.out.println("Premium presence/session-state checks passed: " + checks);
    }
}
