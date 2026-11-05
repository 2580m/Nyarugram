package org.telegram.botgram;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

public final class BotgramSpecialCareUi {
    public static String label(int account, long id) {
        return LocaleController.getString(BotgramSpecialCare.get(account).isSpecial(id)
                ? R.string.BotgramSpecialCareRemove : R.string.BotgramSpecialCareAdd);
    }
    public static void confirm(BaseFragment fragment, int account, TLRPC.User user) {
        if (fragment.getParentActivity() == null || fragment.isFinished) return;
        BotgramSpecialCare care = BotgramSpecialCare.get(account);
        if (user == null || (!care.isSpecial(user.id) && !care.eligible(user))) {
            fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.BotgramSpecialCareTitle))
                    .setMessage(LocaleController.getString(R.string.BotgramSpecialCareIneligible))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
            return;
        }
        final boolean enabled = !care.isSpecial(user.id);
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(label(account, user.id))
                .setMessage(LocaleController.getString(enabled ? R.string.BotgramSpecialCareConfirmAdd : R.string.BotgramSpecialCareConfirmRemove))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> care.set(user.id, enabled, success -> {
                    if (fragment.isFinished || fragment.getParentActivity() == null) return;
                    BulletinFactory.of(fragment).createSimpleBulletin(fragment.getParentActivity().getDrawable(R.drawable.msg_fave),
                            LocaleController.getString(success ? R.string.BotgramSpecialCareSaved : R.string.BotgramSpecialCareFailed)).show();
                })).create());
    }
}
