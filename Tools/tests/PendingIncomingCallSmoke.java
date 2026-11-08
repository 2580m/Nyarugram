package org.telegram.messenger.voip;

public final class PendingIncomingCallSmoke {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static TL_phone.PhoneCall call(long id) {
        TL_phone.PhoneCall call = new TL_phone.PhoneCall();
        call.id = id;
        return call;
    }

    public static void main(String[] args) {
        IncomingCallNotifications.sessions[0] = "account-A-session";
        IncomingCallNotifications.sessions[1] = "account-B-session";
        VoIPService.setPendingIncomingCall(0, call(42));
        check(VoIPService.matchesPendingIncomingCall(0, 42), "ringing call owns the pending slot");
        VoIPService.clearPendingIncomingCall(0, 42);
        check(VoIPService.callIShouldHavePutIntoIntent == null, "local rejection immediately releases dial gate");
        check(!VoIPService.matchesPendingIncomingCall(0, 42), "rejected call is no longer pending");
        VoIPService.clearPendingIncomingCall(0, 42);
        check(VoIPService.callIShouldHavePutIntoIntent == null, "duplicate terminal is harmless");
        VoIPService.setPendingIncomingCall(0, call(43));
        check(VoIPService.matchesPendingIncomingCall(0, 43), "next incoming call is admitted");
        VoIPService.clearPendingIncomingCall(0, 42);
        check(VoIPService.matchesPendingIncomingCall(0, 43), "late old call cannot clear new incoming call");
        VoIPService.setPendingIncomingCall(1, call(43));
        VoIPService.clearPendingIncomingCall(0, 43);
        check(VoIPService.matchesPendingIncomingCall(1, 43), "same numeric call id in another account is isolated");
        IncomingCallNotifications.sessions[1] = "replacement-session";
        check(!VoIPService.matchesPendingIncomingCall(1, 43), "changed login session invalidates old owner");
        VoIPService.clearPendingIncomingCall(1, 43);
        check(VoIPService.callIShouldHavePutIntoIntent != null, "mismatched session cannot mutate pending slot");
        VoIPService.setPendingIncomingCall(1, call(44));
        check(VoIPService.matchesPendingIncomingCall(1, 44), "new session can own a new call");
        VoIPService.setPendingIncomingCall(1, call(44));
        VoIPService.clearPendingIncomingCall(1, 44);
        check(VoIPService.callIShouldHavePutIntoIntent == null, "updated TL object is cleared by bound identity");
        IncomingCallNotifications.sessions[0] = null;
        VoIPService.setPendingIncomingCall(0, call(45));
        check(!VoIPService.matchesPendingIncomingCall(0, 45), "unbound account cannot claim ownership");
        System.out.println("PASS: pending incoming-call release (" + checks + " checks)");
    }
}

// Host-only stand-ins. The ownership methods are extracted from production VoIPService.
final class IncomingCallNotifications {
    static final String[] sessions = new String[2];
    static String session(int account) { return sessions[account]; }
}

final class TL_phone {
    static final class PhoneCall { long id; }
}
