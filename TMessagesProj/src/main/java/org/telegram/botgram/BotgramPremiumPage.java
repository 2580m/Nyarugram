package org.telegram.botgram;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/** Nyarugram's account activation inside the original Premium fragment. */
public final class BotgramPremiumPage {
    private final BaseFragment fragment;
    private final Runnable onChanged;
    private boolean loading, premium;
    private volatile int generation;

    public BotgramPremiumPage(BaseFragment fragment, Runnable onChanged) {
        this.fragment = fragment;
        this.onChanged = onChanged;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isPremium() {
        return premium;
    }

    public void activate() {
        if (!premium) request(true);
    }

    private void request(boolean activate) {
        if (loading) return;
        loading = true;
        onChanged.run();
        int account = fragment.getCurrentAccount();
        int owner = ++generation;
        BotgramTransport.get(account).premiumStatus(activate,
                () -> owner == generation && !fragment.isFinished && UserConfig.selectedAccount == account, (value, error) -> {
                    loading = false;
                    if (error == null) {
                        premium = value;
                    } else if (fragment.getParentActivity() != null) {
                        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                                .setTitle(getString(R.string.TelegramPremium))
                                .setMessage(getString(R.string.BotgramPremiumFailed))
                                .setPositiveButton(getString(R.string.OK), null).create());
                    }
                    onChanged.run();
                });
    }

    public void pause() {
        generation++;
        loading = false;
    }

    public void resume() {
        if (!loading) request(false);
    }
}
