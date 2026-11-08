package org.telegram.botgram.e2ee;

import org.telegram.botgram.protocol.v1.BotgramRpc;

/**
 * Pure send decision used by the client before any E2EE check runs. Non-applicable peers keep
 * their normal plaintext path; applicable peers distinguish unknown, pending, suspended,
 * closed and active states instead of inferring "disabled" from a failed request.
 */
public final class BotgramE2eeSendPolicy {
    public enum Action {
        /** Not a bound, supported one-to-one chat: original send path. */
        SKIP,
        /** Known disabled or closed: send plaintext. */
        PLAINTEXT,
        /** Active encrypted range: encrypt through the ratchet. */
        ENCRYPT,
        BLOCK_UNKNOWN,
        BLOCK_PENDING,
        BLOCK_SUSPENDED,
        BLOCK_NOT_READY
    }

    private BotgramE2eeSendPolicy() {}

    public static boolean selfEnabled(BotgramRpc.E2eeChatStatus status) {
        if (status == null) return false;
        switch (status.getState()) {
            case E2EE_PENDING: return status.getSelfInitiator();
            case E2EE_ACTIVE:
            case E2EE_CLOSING:
            case E2EE_SUSPENDED: return true;
            default: return false;
        }
    }

    public static boolean applies(long peer, boolean encryptedDialog, long selfUserId,
            boolean boundSession) {
        return boundSession && peer > 0 && peer != selfUserId && !encryptedDialog;
    }

    public static Action decide(boolean applies, BotgramRpc.E2eeChatStatus status, boolean hasSession) {
        if (!applies) return Action.SKIP;
        if (status == null) return Action.BLOCK_UNKNOWN;
        switch (status.getState()) {
            case E2EE_DISABLED:
            case E2EE_CLOSED:
                return Action.PLAINTEXT;
            case E2EE_PENDING:
                return status.getSelfInitiator() ? Action.ENCRYPT : Action.PLAINTEXT;
            case E2EE_SUSPENDED:
                return Action.BLOCK_SUSPENDED;
            case E2EE_ACTIVE:
            case E2EE_CLOSING:
                return hasSession ? Action.ENCRYPT : Action.BLOCK_NOT_READY;
            default:
                return Action.BLOCK_NOT_READY;
        }
    }
}
