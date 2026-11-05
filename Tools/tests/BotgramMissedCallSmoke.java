package org.telegram.botgram;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import android.content.SharedPreferences;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;

/** Actual protobuf mapper plus missed-call classification, including terminal-only replay. */
public final class BotgramMissedCallSmoke {
    private static int assertions;
    private static final long SELF = 100001, CALLER = 100002;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static TL_phone.PhoneCall map(BotgramRpc.PhoneCall call) throws Exception {
        Method method = BotgramProtobufResponseMapper.class.getDeclaredMethod("phoneCall", BotgramRpc.PhoneCall.class);
        method.setAccessible(true);
        return (TL_phone.PhoneCall) method.invoke(new BotgramProtobufResponseMapper(null), call);
    }
    private static SharedPreferences preferences(Map<String, Object> disk) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (object, method, values) -> {
            if (method.getName().startsWith("get")) return disk.getOrDefault(values[0], values[1]);
            if (!method.getName().equals("edit")) throw new AssertionError(method.getName());
            Map<String, Object> changes = new HashMap<>();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, args) -> {
                if (operation.getName().startsWith("put")) {
                    changes.put((String)args[0], args[1] instanceof Set ? new HashSet<>((Set<?>) args[1]) : args[1]);
                    return editor;
                }
                if (operation.getName().equals("apply")) { disk.putAll(changes); return null; }
                throw new AssertionError(operation.getName());
            });
        });
    }
    public static void main(String[] args) throws Exception {
        BotgramRpc.PhoneCall.Builder base = BotgramRpc.PhoneCall.newBuilder().setCallId(1000000001)
                .setState(BotgramRpc.PhoneCallState.PHONE_CALL_STATE_DISCARDED)
                .setAdminTlUserId(CALLER).setParticipantTlUserId(SELF).setDateMs(1793881200000L);
        for (BotgramRpc.PhoneCallDiscardReason reason : new BotgramRpc.PhoneCallDiscardReason[]{
                BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_MISSED,
                BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_HANGUP,
                BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_DISCONNECT}) {
            TL_phone.PhoneCall call = map(base.clone().setDiscardReason(reason).build());
            check(BotgramMissedCallState.isMissed(call, SELF), "unanswered incoming call " + reason);
            check(!BotgramMissedCallState.isMissed(call, CALLER), "outgoing call never notifies");
            check(!BotgramMissedCallState.isMissed(call, SELF + 10), "another account never notifies");
        }
        base.setDiscardReason(BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_MISSED);
        TL_phone.PhoneCall terminal = map(base.clone().setVideo(true).build());
        check(terminal.video && terminal.admin_id == CALLER && terminal.participant_id == SELF
                && terminal.date == 1793881200, "terminal-only update preserves caller and video metadata");
        check(!BotgramMissedCallState.isMissed(map(base.clone().setGB(com.google.protobuf.ByteString.copyFrom(new byte[256])).build()), SELF),
                "answered on another device, even with zero duration, is not missed");
        check(!BotgramMissedCallState.isMissed(map(base.clone().setStartDateMs(1793881201000L).build()), SELF),
                "connected call ending in the same second is not missed");
        check(!BotgramMissedCallState.isMissed(map(base.clone().setDuration(1).build()), SELF), "positive duration is not missed");
        check(!BotgramMissedCallState.isMissed(map(base.clone().setDiscardReason(
                BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_BUSY).build()), SELF), "busy/rejected is not missed");
        check(!BotgramMissedCallState.isMissed(map(base.clone().setAdminTlUserId(0).build()), SELF), "unknown caller never notifies");
        check(!BotgramMissedCallState.isMissed(terminal, 0), "logged-out account never notifies");
        check(!BotgramMissedCallState.isMissed(new TL_phone.phoneCallRequested(), SELF), "ringing is not yet missed");
        check(!BotgramMissedCallState.isMissed(null, SELF), "absent call never notifies");
        Method handled = BotgramMissedCallNotifications.class.getDeclaredMethod("handled", SharedPreferences.class, long.class);
        Method remember = BotgramMissedCallNotifications.class.getDeclaredMethod("remember", SharedPreferences.class, long.class);
        handled.setAccessible(true); remember.setAccessible(true);
        Map<String, Object> disk = new HashMap<>();
        SharedPreferences history = preferences(disk);
        check(!(Boolean)handled.invoke(null, history, 100L), "first missed call is eligible");
        remember.invoke(null, history, 100L);
        check((Boolean)handled.invoke(null, history, 100L), "replayed terminal event is suppressed");
        remember.invoke(null, history, 100L);
        check(((Set<?>)disk.get("handled")).size() == 1, "duplicate replay does not add history entries");
        check((Boolean)handled.invoke(null, preferences(new HashMap<>(disk)), 100L), "deduplication survives history reload");
        check(!(Boolean)handled.invoke(null, preferences(new HashMap<>()), 100L), "separate account history does not collide");
        for (long id = 2000; id < 2600; id++) remember.invoke(null, history, id);
        check(((Set<?>)disk.get("handled")).size() == 512, "history remains bounded");
        check((Boolean)handled.invoke(null, history, 100L), "pruned old calls cannot notify again");
        check(!(Boolean)handled.invoke(null, history, 3000L), "new calls remain eligible after pruning");
        System.out.println("PASS: " + assertions + " missed-call classification, real protobuf metadata and replay/history assertions");
    }
}
