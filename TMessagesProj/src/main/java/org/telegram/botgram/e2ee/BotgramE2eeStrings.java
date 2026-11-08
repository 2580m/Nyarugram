package org.telegram.botgram.e2ee;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public final class BotgramE2eeStrings {
    private BotgramE2eeStrings() {}

    public static String systemText(String code) { return systemText(code, false); }

    public static String systemText(String code, boolean self) {
        if (code == null) return "";
        switch (code) {
            case "e2ee:one_way_enabled": return LocaleController.getString(self
                    ? R.string.BotgramE2eeSystemSelfEnabled : R.string.BotgramE2eeSystemPeerEnabled);
            case "e2ee:one_way_disabled": return LocaleController.getString(self
                    ? R.string.BotgramE2eeSystemSelfDisabled : R.string.BotgramE2eeSystemPeerDisabled);
            case "e2ee:invited": return LocaleController.getString(R.string.BotgramE2eeSystemInvited);
            case "e2ee:activated": return LocaleController.getString(R.string.BotgramE2eeSystemActivated);
            case "e2ee:declined": return LocaleController.getString(R.string.BotgramE2eeSystemDeclined);
            case "e2ee:cancelled": return LocaleController.getString(R.string.BotgramE2eeSystemCancelled);
            case "e2ee:close_requested": return LocaleController.getString(R.string.BotgramE2eeSystemCloseRequested);
            case "e2ee:closed": return LocaleController.getString(R.string.BotgramE2eeSystemClosed);
            case "e2ee:identity_changed": return LocaleController.getString(R.string.BotgramE2eeSystemIdentityChanged);
            case "e2ee:reactivated": return LocaleController.getString(R.string.BotgramE2eeSystemReactivated);
            default: return code;
        }
    }

    public static String errorText(String code) {
        if (code == null) return LocaleController.getString(R.string.BotgramE2eeFailed);
        if (code.startsWith("E2EE_RETRY:")) {
            long millis;
            try { millis = Long.parseLong(code.substring("E2EE_RETRY:".length())); }
            catch (NumberFormatException error) { millis = 0; }
            int seconds = Math.max(1, (int) Math.ceil(millis / 1000.0));
            return LocaleController.formatString("BotgramE2eePinRetry", R.string.BotgramE2eePinRetry, seconds);
        }
        switch (code) {
            case "E2EE_LOCKED": return LocaleController.getString(R.string.BotgramE2eeSendLocked);
            case "E2EE_PENDING": return LocaleController.getString(R.string.BotgramE2eeWaitingPeer);
            case "E2EE_STATUS_UNKNOWN":
            case "E2EE_STATUS_INCOMPLETE":
            case "E2EE_SESSION_PREPARING":
            case "E2EE_SESSION_NOT_READY":
                return LocaleController.getString(R.string.BotgramE2eeSendNotReady);
            case "E2EE_IDENTITY_CHANGED":
            case "E2EE_IDENTITY_CONFIRMATION_REQUIRED":
            case "E2EE_DEVICE_MISSING":
                return LocaleController.getString(R.string.BotgramE2eeIdentityChanged);
            case "E2EE_PEER_UNSUPPORTED":
            case "E2EE_PREKEYS_EXHAUSTED":
                return LocaleController.getString(R.string.BotgramE2eeUnsupportedPeer);
            case "E2EE_PIN_INCORRECT":
            case "E2EE_PIN_INVALID":
                return LocaleController.getString(R.string.BotgramE2eePinWrong);
            case "E2EE_DEVICE_KEY_INVALID":
                return LocaleController.getString(R.string.BotgramE2eeDeviceKeyInvalid);
            case "E2EE_COMMIT_FAILED":
                return LocaleController.getString(R.string.BotgramE2eeCommitFailed);
            case "E2EE_VAULT_FAILED":
            case "E2EE_VAULT_WRITE_FAILED":
                return LocaleController.getString(R.string.BotgramE2eeVaultCorrupt);
            case "E2EE_PUBLISH_FAILED":
            case "E2EE_RESET_FAILED":
            case "E2EE_PIN_CHANGE_FAILED":
                return LocaleController.getString(R.string.BotgramE2eeStorageFailed);
            default:
                return LocaleController.getString(R.string.BotgramE2eeFailed);
        }
    }
}
