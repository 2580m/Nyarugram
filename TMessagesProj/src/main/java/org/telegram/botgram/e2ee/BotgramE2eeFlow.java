package org.telegram.botgram.e2ee;

import android.app.Activity;

import org.telegram.botgram.BotgramTransport;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.PasscodeActivity;

/** Native dialogs and PIN gates shared by profile, chat and the management list. */
public final class BotgramE2eeFlow {
    private BotgramE2eeFlow() {}

    public static void openProfile(BaseFragment fragment, long userId) {
        BotgramE2eeController controller = BotgramE2eeController.get(fragment.getCurrentAccount());
        controller.refreshStatus(userId, (status, error) -> {
            if (error != null) {
                BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                return;
            }
            dispatch(fragment, userId, status);
        });
    }

    public static void openChat(BaseFragment fragment, long userId) {
        BotgramE2eeController controller = BotgramE2eeController.get(fragment.getCurrentAccount());
        BotgramRpc.E2eeChatStatus status = controller.status(userId);
        if (status == null) {
            openProfile(fragment, userId);
        } else {
            dispatch(fragment, userId, status);
        }
    }

    private static void dispatch(BaseFragment fragment, long userId, BotgramRpc.E2eeChatStatus status) {
        if (fragment == null || fragment.getParentActivity() == null || status == null) return;
        switch (status.getState()) {
            case E2EE_DISABLED:
            case E2EE_CLOSED:
                showInvite(fragment, userId, status);
                break;
            case E2EE_PENDING:
                showPending(fragment, userId, status);
                break;
            case E2EE_ACTIVE:
            case E2EE_CLOSING:
            case E2EE_SUSPENDED:
                requirePin(fragment, () -> showManage(fragment, userId));
                break;
            default:
                break;
        }
    }

    private static void showInvite(BaseFragment fragment, long userId, BotgramRpc.E2eeChatStatus status) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.BotgramE2eeTitle));
        builder.setMessage(LocaleController.getString(R.string.BotgramE2eeInviteInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeConfirmEnable),
                (dialog, which) -> requirePin(fragment, () -> invite(fragment, userId)));
        builder.setNegativeButton(LocaleController.getString(R.string.BotgramE2eeCancel), null);
        builder.show();
    }

    private static void invite(BaseFragment fragment, long userId) {
        BotgramE2eeController.get(fragment.getCurrentAccount()).invite(userId, (status, error) -> {
            if (error != null) {
                BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                return;
            }
            dispatch(fragment, userId, status);
        });
    }

    private static void showPending(BaseFragment fragment, long userId, BotgramRpc.E2eeChatStatus status) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.BotgramE2eeTitle));
        if (!status.getSelfInitiator()) {
            builder.setMessage(LocaleController.getString(R.string.BotgramE2eePeerOnlyInfo));
            builder.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeConfirmEnable),
                    (dialog, which) -> requirePin(fragment, () -> accept(fragment, userId)));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        } else {
            builder.setMessage(LocaleController.getString(R.string.BotgramE2eeSelfOnlyInfo));
            builder.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeTurnOff),
                    (dialog, which) -> requirePin(fragment, () -> BotgramE2eeController.get(fragment.getCurrentAccount())
                            .cancel(userId, (value, error) -> {
                                if (error != null) BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                            })));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        }
        builder.show();
    }

    private static void accept(BaseFragment fragment, long userId) {
        BotgramE2eeController.get(fragment.getCurrentAccount()).accept(userId, (status, error) -> {
            if (error != null) {
                BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                return;
            }
            dispatch(fragment, userId, status);
        });
    }

    private static void showManage(BaseFragment fragment, long userId) {
        BotgramE2eeController controller = BotgramE2eeController.get(fragment.getCurrentAccount());
        BotgramRpc.E2eeChatStatus status = controller.status(userId);
        if (status == null) {
            controller.refreshStatus(userId, (value, error) -> {
                if (error != null) BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                else dispatch(fragment, userId, value);
            });
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.BotgramE2eeTitle));
        BotgramRpc.E2eeState displayState = status.getState();
        if (displayState == BotgramRpc.E2eeState.E2EE_ACTIVE && controller.requiresIdentityConfirmation(userId, status))
            displayState = BotgramRpc.E2eeState.E2EE_SUSPENDED;
        switch (displayState) {
            case E2EE_SUSPENDED:
                builder.setMessage(LocaleController.getString(R.string.BotgramE2eeIdentityConfirmInfo)
                        + "\n\n" + LocaleController.getString(R.string.BotgramE2eeSafetyCode) + ": "
                        + controller.replacementSafetyNumber(userId, status.getPeerIdentityKey().toByteArray()));
                builder.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeConfirmIdentity),
                        (dialog, which) -> controller.confirmIdentity(userId, status, (value, error) -> {
                            if (error != null) BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                            else dispatch(fragment, userId, value);
                        }));
                builder.setNeutralButton(LocaleController.getString(R.string.BotgramE2eeSafetyCode),
                        (dialog, which) -> showSafety(fragment, userId));
                break;
            case E2EE_CLOSING:
                if (status.getAwaitingSelf()) {
                    builder.setMessage(LocaleController.getString(R.string.BotgramE2eeTurnOffInfo));
                    builder.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeTurnOffConfirm),
                            (dialog, which) -> controller.confirmClose(userId, (value, error) -> {
                                if (error != null) BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                            }));
                } else {
                    builder.setMessage(LocaleController.getString(R.string.BotgramE2eeWaitingPeer));
                    builder.setNegativeButton(LocaleController.getString(R.string.BotgramE2eeCancel), null);
                }
                builder.setNeutralButton(LocaleController.getString(R.string.BotgramE2eeSafetyCode),
                        (dialog, which) -> showSafety(fragment, userId));
                break;
            case E2EE_ACTIVE:
            default:
                builder.setMessage(LocaleController.getString(R.string.BotgramE2eeSafetyCode) + ": "
                        + safety(controller, status));
                builder.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeTurnOff),
                        (dialog, which) -> {
                            AlertDialog.Builder confirm = new AlertDialog.Builder(fragment.getParentActivity());
                            confirm.setTitle(LocaleController.getString(R.string.BotgramE2eeTurnOff));
                            confirm.setMessage(LocaleController.getString(R.string.BotgramE2eeTurnOffInfo));
                            confirm.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeTurnOffConfirm),
                                    (inner, whichButton) -> controller.requestClose(userId, (value, error) -> {
                                        if (error != null) BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
                                    }));
                            confirm.setNegativeButton(LocaleController.getString(R.string.BotgramE2eeCancel), null);
                            confirm.show();
                        });
                builder.setNeutralButton(LocaleController.getString(R.string.BotgramE2eeSafetyCode),
                        (dialog, which) -> showSafety(fragment, userId));
                break;
        }
        builder.show();
    }

    private static void showSafety(BaseFragment fragment, long userId) {
        BotgramE2eeController controller = BotgramE2eeController.get(fragment.getCurrentAccount());
        BotgramRpc.E2eeChatStatus status = controller.status(userId);
        String code = status == null ? "" : safety(controller, status);
        if (code.isEmpty()) code = "…";
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.BotgramE2eeSafetyCode));
        builder.setMessage(code + "\n\n" + LocaleController.getString(R.string.BotgramE2eeSafetyCodeInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        final String copy = code;
        builder.setNeutralButton(LocaleController.getString(R.string.Copy),
                (dialog, which) -> {
                    AndroidUtilities.addToClipboard(copy);
                    BotgramE2eeUi.toastText(LocaleController.getString(R.string.BotgramE2eeSafetyCode));
                });
        builder.show();
    }

    private static String safety(BotgramE2eeController controller, BotgramRpc.E2eeChatStatus status) {
        String value = controller.safetyNumber(status.getTlUserId());
        return value == null ? "" : value;
    }

    public static void requirePin(BaseFragment fragment, Runnable action) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        BotgramE2eeController controller = BotgramE2eeController.get(fragment.getCurrentAccount());
        if (controller.hasPin()) {
            if (controller.isUnlocked()) {
                action.run();
            } else {
                fragment.presentFragment(PasscodeActivity.forBotgramE2eePinVerify(action));
            }
        } else {
            fragment.presentFragment(PasscodeActivity.forBotgramE2eePinSetup(action));
        }
    }

    public static void openManager(BaseFragment fragment) {
        if (fragment == null) return;
        fragment.presentFragment(new BotgramE2eeChatsActivity());
    }

    public static boolean isBoundAccount(int account) {
        return BotgramTransport.get(account).hasActiveSession();
    }
}
