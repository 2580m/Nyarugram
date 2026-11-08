package org.telegram.botgram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/** Enrollment follows Telegram's original two-step introduction as a separate page. */
public final class BotgramTotpActivity extends BaseFragment {
    private static final int SAVE_RECOVERY_REQUEST = 0xB072;
    private boolean enabled, loaded, busy, exporting;
    private volatile boolean destroyed;
    private volatile int generation;
    private int recoveryRemaining;
    private String secret = "", recovery = "", pendingExport = "";
    private TextView title, description, key, copyKey, regenerate, showRecovery;
    private ImageView qr;
    private RLottieImageView lock;
    private OutlineTextContainerView outline;
    private EditTextBoldCursor code;
    private ButtonWithCounterView primary;

    public BotgramTotpActivity(int account) {
        currentAccount = account;
    }

    @Override public View createView(Context context) {
        actionBar.setTitle(getString(R.string.BotgramAuthenticator));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        lock = new RLottieImageView(context);
        lock.setAnimation(R.raw.tsv_setup_intro, 120, 120);
        content.addView(lock, LayoutHelper.createLinear(120, 120, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));
        title = text(context, 24, Theme.key_windowBackgroundWhiteBlackText);
        content.addView(title, LayoutHelper.createLinear(-1, -2));
        description = text(context, 15, Theme.key_windowBackgroundWhiteGrayText6);
        description.setLineSpacing(dp(3), 1f);
        content.addView(description, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 24));
        qr = new ImageView(context);
        qr.setBackgroundColor(android.graphics.Color.WHITE);
        qr.setContentDescription(getString(R.string.BotgramAuthenticatorQr));
        content.addView(qr, LayoutHelper.createLinear(220, 220, Gravity.CENTER_HORIZONTAL));
        key = text(context, 17, Theme.key_windowBackgroundWhiteBlackText);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextIsSelectable(true);
        key.setPadding(dp(12), dp(16), dp(12), dp(16));
        key.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        content.addView(key, LayoutHelper.createLinear(-1, -2, 0, 16, 0, 0));
        copyKey = link(context, getString(R.string.BotgramAuthenticatorCopyKey), () -> copy(secret));
        content.addView(copyKey, LayoutHelper.createLinear(-1, 48));
        outline = new OutlineTextContainerView(context);
        code = new EditTextBoldCursor(context);
        code.setSingleLine(true);
        code.setTextSize(18);
        code.setBackground(null);
        code.setPadding(dp(16), dp(16), dp(16), dp(16));
        code.setSaveEnabled(false);
        code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        code.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        code.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        code.setOnFocusChangeListener((view, focused) -> outline.animateSelection(focused ? 1f : 0f));
        outline.attachEditText(code);
        outline.addView(code, LayoutHelper.createFrame(-1, 56, Gravity.TOP));
        content.addView(outline, LayoutHelper.createLinear(-1, 62, 0, 12, 0, 24));
        primary = new ButtonWithCounterView(context, getResourceProvider());
        primary.setOnClickListener(view -> {
            if (busy) return;
            if (!loaded) request("status");
            else if (enabled) confirmManagement("disable");
            else request("confirm");
        });
        content.addView(primary, LayoutHelper.createLinear(-1, 50));
        regenerate = link(context, getString(R.string.BotgramRecoveryCodesRegenerate), () -> confirmManagement("recovery"));
        content.addView(regenerate, LayoutHelper.createLinear(-1, 52, 0, 8, 0, 0));
        showRecovery = link(context, getString(R.string.BotgramRecoveryCodesShow), this::showRecoveryCodes);
        content.addView(showRecovery, LayoutHelper.createLinear(-1, 48));
        request("status");
        return fragmentView;
    }

    private TextView text(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(color));
        return view;
    }

    private TextView link(Context context, String label, Runnable action) {
        TextView view = text(context, 15, Theme.key_windowBackgroundWhiteBlueText4);
        view.setText(label);
        view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        view.setOnClickListener(v -> { if (!busy) action.run(); });
        return view;
    }

    private void render() {
        title.setText(getString(enabled ? R.string.BotgramAuthenticatorManage : R.string.BotgramAuthenticatorEnable));
        description.setText(enabled
                ? getString(R.string.BotgramAuthenticatorEnabled) + "\n\n" + LocaleController.formatString("BotgramRecoveryCodesRemaining", R.string.BotgramRecoveryCodesRemaining, recoveryRemaining)
                : getString(R.string.BotgramAuthenticatorEnrollInfo));
        lock.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) lock.playAnimation();
        qr.setVisibility(!secret.isEmpty() ? View.VISIBLE : View.GONE);
        key.setVisibility(qr.getVisibility());
        copyKey.setVisibility(qr.getVisibility());
        key.setText(secret);
        outline.setVisibility(loaded ? View.VISIBLE : View.GONE);
        outline.setText(getString(enabled ? R.string.BotgramAuthenticatorCode : R.string.BotgramAuthenticatorSixDigitCode));
        code.setInputType(enabled ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(enabled ? 40 : 6)});
        code.setEnabled(!busy);
        primary.setText(getString(!loaded ? R.string.Retry : enabled ? R.string.BotgramAuthenticatorDisable : R.string.BotgramAuthenticatorConfirm), false);
        primary.setLoading(busy);
        regenerate.setVisibility(loaded && enabled ? View.VISIBLE : View.GONE);
        showRecovery.setVisibility(!recovery.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmManagement(String operation) {
        if (!loaded || busy) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(operation.equals("disable") ? R.string.BotgramAuthenticatorDisable : R.string.BotgramRecoveryCodesRegenerate))
                .setMessage(getString(operation.equals("disable") ? R.string.BotgramAuthenticatorDisableInfo : R.string.BotgramRecoveryCodesReplaceInfo))
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.Continue), (dialog, which) -> request(operation)).create());
    }

    private void request(String operation) {
        if (busy || destroyed) return;
        final int owner = ++generation;
        String entered = code.getText().toString().trim();
        busy = true;
        render();
        BotgramTransport.get(currentAccount).accountRequest(api -> {
            org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse rpc = api.authRpc(
                    BotgramApi.request().setTotp(
                            org.telegram.botgram.protocol.v1.BotgramRpc.TotpRequest.newBuilder()
                                    .setOperation(operation).setCode(entered)).build(), true);
            org.telegram.botgram.protocol.v1.BotgramRpc.TotpResult response = rpc.getTotp();
            Bitmap bitmap = response.getUri().isEmpty()
                    ? null : new TelegramQRCodeWriter().encode(response.getUri(), 768, 768, null, null);
            return new Object[]{response, bitmap};
        }, () -> !destroyed && owner == generation, (value, error) -> {
            busy = false;
            if (error != null) {
                int message = R.string.BotgramAuthenticatorFailed;
                if ("TOTP_CODE_INVALID".equals(error.text)) message = R.string.BotgramAuthenticatorInvalid;
                if ("TOTP_TOO_MANY_ATTEMPTS".equals(error.text)) message = R.string.BotgramAuthenticatorWait;
                if ("TOTP_ENROLLMENT_EXPIRED".equals(error.text)) { loaded = false; secret = ""; }
                render();
                alert(getString(message));
                return;
            }
            org.telegram.botgram.protocol.v1.BotgramRpc.TotpResult response =
                    (org.telegram.botgram.protocol.v1.BotgramRpc.TotpResult) value[0];
            enabled = response.getEnabled();
            recoveryRemaining = response.getRecoveryCodesRemaining();
            if (operation.equals("status") && !enabled) { request("setup"); return; }
            loaded = true;
            secret = response.getSecret();
            qr.setImageBitmap((Bitmap) value[1]);
            code.setText("");
            java.util.List<String> codes = response.getRecoveryCodesList();
            if (!codes.isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (String item : codes) text.append(item).append('\n');
                recovery = text.toString().trim();
                enabled = true;
                recoveryRemaining = codes.size();
            } else if (operation.equals("disable")) recovery = "";
            render();
            AndroidUtilities.hideKeyboard(code);
            if (!codes.isEmpty()) showRecoveryCodes();
            if (operation.equals("disable")) finishFragment();
        });
    }

    private void copy(String value) {
        if (value.isEmpty()) return;
        if (AndroidUtilities.addToClipboard(value)) BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
    }

    private void showRecoveryCodes() {
        if (recovery.isEmpty() || getParentActivity() == null) return;
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView info = text(getContext(), 14, Theme.key_windowBackgroundWhiteGrayText6);
        info.setText(getString(R.string.BotgramRecoveryCodesInfo));
        content.addView(info, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 16));
        TextView numbers = text(getContext(), 20, Theme.key_windowBackgroundWhiteBlackText);
        numbers.setTypeface(Typeface.MONOSPACE);
        numbers.setLineSpacing(dp(8), 1f);
        numbers.setTextIsSelectable(true);
        numbers.setText(recovery);
        content.addView(numbers, LayoutHelper.createLinear(-1, -2));
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.BotgramRecoveryCodesShow)).setView(scroll)
                .setPositiveButton(getString(R.string.BotgramRecoveryCodesSaveTxt), null)
                .setNeutralButton(getString(R.string.Copy), null)
                .setNegativeButton(getString(R.string.Done), null).create();
        showDialog(dialog);
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> copy(recovery));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> saveRecoveryCodes());
    }

    private void saveRecoveryCodes() {
        if (exporting || !pendingExport.isEmpty() || recovery.isEmpty()) return;
        pendingExport = "Nyarugram\n\n" + getString(R.string.BotgramRecoveryCodesInfo) + "\n\n" + recovery + "\n";
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "Nyarugram-recovery-codes.txt");
            startActivityForResult(intent, SAVE_RECOVERY_REQUEST);
        } catch (Exception error) {
            pendingExport = "";
            alert(getString(R.string.BotgramRecoveryCodesSaveFailed));
        }
    }

    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != SAVE_RECOVERY_REQUEST) return;
        final String text = pendingExport;
        pendingExport = "";
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || text.isEmpty()) return;
        final Uri uri = data.getData();
        exporting = true;
        Utilities.globalQueue.postRunnable(() -> {
            boolean saved = false;
            try (OutputStream stream = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri, "w")) {
                if (stream != null) { stream.write(text.getBytes(StandardCharsets.UTF_8)); saved = true; }
            } catch (Exception ignored) { }
            final boolean success = saved;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                exporting = false;
                alert(getString(success ? R.string.BotgramRecoveryCodesSaved : R.string.BotgramRecoveryCodesSaveFailed));
            });
        });
    }

    private void alert(String message) {
        if (getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.TwoStepVerification)).setMessage(message)
                .setPositiveButton(getString(R.string.OK), null).create());
    }

    @Override public void onFragmentDestroy() {
        destroyed = true;
        generation++;
        secret = ""; recovery = ""; pendingExport = "";
        if (code != null) code.setText("");
        if (key != null) key.setText("");
        if (qr != null) qr.setImageDrawable(null);
        super.onFragmentDestroy();
    }
}
