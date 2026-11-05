package org.telegram.botgram;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;

/** Uses authoritative terminal metadata, including calls received only after reconnecting. */
public final class BotgramMissedCallState {
    private BotgramMissedCallState() { }

    public static boolean isMissed(TL_phone.PhoneCall call, long ownId) {
        return call instanceof TL_phone.TL_phoneCallDiscarded
                && ownId != 0 && call.participant_id == ownId && call.admin_id != 0 && call.admin_id != ownId
                && call.start_date == 0 && call.duration == 0 && (call.g_b == null || call.g_b.length == 0)
                && (call.reason instanceof TLRPC.TL_phoneCallDiscardReasonMissed
                    || call.reason instanceof TLRPC.TL_phoneCallDiscardReasonHangup
                    || call.reason instanceof TLRPC.TL_phoneCallDiscardReasonDisconnect);
    }
}
