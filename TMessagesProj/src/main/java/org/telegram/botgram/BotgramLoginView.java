package org.telegram.botgram;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.SlideView;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Credentials slide inside LoginActivity's original layout and navigation. */
public final class BotgramLoginView extends SlideView {
    public interface Completion { void done(TLRPC.User user); }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BotgramRegistrationLocation location;
    private final ArrayList<OutlineTextContainerView> outlines = new ArrayList<>();
    private final ArrayList<EditTextBoldCursor> fields = new ArrayList<>();
    private final LinearLayout form;
    private final TextView title, subtitle, switchMode;
    private final EditTextBoldCursor server, username, displayName, password;
    private final Completion completion;
    private final Consumer<Boolean> loading;
    private final Consumer<String> showError;
    private final int account;
    private volatile boolean disposed;
    private volatile int attempt;
    private boolean registering, busy;
    private Future<?> request;

    public BotgramLoginView(Context context, Activity activity, int account, Completion completion,
                            Consumer<Boolean> loading, Consumer<String> showError) {
        super(context);
        this.account = account;
        this.completion = completion;
        this.loading = loading;
        this.showError = showError;
        location = new BotgramRegistrationLocation(activity);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        // Keep the fields reachable on small screens and above the system keyboard.
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        form = new LinearLayout(context);
        form.setOrientation(VERTICAL);
        form.setPadding(0, AndroidUtilities.dp(32), 0, AndroidUtilities.dp(80));
        scroll.addView(form);
        title = label(context, 18, true);
        form.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));
        subtitle = label(context, 14, false);
        form.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 20));
        server = field(context, R.string.BotgramServer, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(context.getSharedPreferences("botgram.session." + account, Context.MODE_PRIVATE).getString("server", "http://10.0.2.2:5080"));
        username = field(context, R.string.Username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        displayName = field(context, R.string.BotgramDisplayName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        password = field(context, R.string.LoginPassword, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        password.setTypeface(Typeface.DEFAULT);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            onNextPressed(null);
            return true;
        });
        password.setSaveEnabled(false);
        password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        switchMode = label(context, 15, false);
        switchMode.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16));
        form.addView(switchMode, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        switchMode.setOnClickListener(view -> {
            if (busy) return;
            registering = !registering;
            updateMode();
        });
        updateMode();
        updateColors();
    }

    private TextView label(Context context, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(AndroidUtilities.dp(2), 1f);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private EditTextBoldCursor field(Context context, int label, int inputType) {
        OutlineTextContainerView outline = new OutlineTextContainerView(context);
        outline.setText(LocaleController.getString(label));
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(18);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setInputType(inputType);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        int padding = AndroidUtilities.dp(16);
        field.setPadding(padding, padding, padding, padding);
        field.setOnFocusChangeListener((view, focused) -> outline.animateSelection(focused ? 1f : 0f));
        outline.attachEditText(field);
        outline.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));
        outlines.add(outline);
        fields.add(field);
        form.addView(outline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 62, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));
        return field;
    }

    private void updateMode() {
        title.setText(LocaleController.getString(registering ? R.string.BotgramSignUp : R.string.BotgramSignIn));
        subtitle.setText(LocaleController.getString(registering ? R.string.BotgramSignUpInfo : R.string.BotgramSignInInfo));
        ((View) displayName.getParent()).setVisibility(registering ? VISIBLE : GONE);
        switchMode.setText(LocaleController.getString(registering ? R.string.BotgramHaveAccount : R.string.BotgramCreateAccount));
    }

    @Override public void onNextPressed(String code) {
        if (busy || disposed) return;
        for (EditTextBoldCursor field : fields) {
            if (field == displayName && !registering) continue;
            if (field.getText().toString().trim().isEmpty()) {
                field.requestFocus();
                AndroidUtilities.shakeView(field);
                AndroidUtilities.showKeyboard(field);
                return;
            }
        }
        final String address = server.getText().toString().trim(), name = username.getText().toString().trim();
        final String secret = password.getText().toString(), display = displayName.getText().toString().trim();
        final boolean register = registering;
        final int id = ++attempt;
        setBusy(true);
        Consumer<String> authenticate = country -> {
            if (disposed || id != attempt) return;
            request = worker.submit(() -> {
                try {
                    BotgramApi api = new BotgramApi(address, null);
                    JSONObject response = api.object("POST", register ? "/api/auth/register" : "/api/auth/login",
                        BotgramApi.json("username", name, "password", secret, "displayName", display,
                            "locationCountry", country == null ? JSONObject.NULL : country));
                    JSONObject identity = new BotgramApi(api.baseUrl, response.getString("token")).object("GET", "/api/telegram/me", null);
                    TLRPC.User user = (TLRPC.User) BotgramTransport.decode(identity, TLRPC.User.class, 0);
                    if (disposed || id != attempt) return;
                    BotgramSession saved = new BotgramSession(getContext().getApplicationContext(), account);
                    if (!saved.save(api.baseUrl, response, () -> !disposed && id == attempt)) return;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (disposed || id != attempt) {
                            Utilities.globalQueue.postRunnable(saved::clearIfCurrent);
                            return;
                        }
                        BotgramTransport.get(account).authenticated(saved);
                        password.setText("");
                        setBusy(false);
                        completion.done(user);
                    });
                } catch (Exception error) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (disposed || id != attempt) return;
                        setBusy(false);
                        showError.accept(errorText(error));
                    });
                }
            });
        };
        if (register) {
            subtitle.setText(LocaleController.getString(R.string.BotgramLocatingRegion));
            location.resolve(authenticate);
        } else authenticate.accept(null);
    }

    private String errorText(Exception error) {
        if (error instanceof BotgramApi.ApiError) {
            if ("invalid_credentials".equals(error.getMessage())) return LocaleController.getString(R.string.BotgramInvalidCredentials);
            if ("username_taken".equals(error.getMessage())) return LocaleController.getString(R.string.BotgramUsernameTaken);
            return LocaleController.getString(R.string.BotgramAuthenticationFailed);
        }
        if (error instanceof IllegalArgumentException) return LocaleController.getString(R.string.BotgramInvalidServer);
        return LocaleController.getString(R.string.BotgramConnectionFailed);
    }

    private void setBusy(boolean value) {
        busy = value;
        for (EditTextBoldCursor field : fields) field.setEnabled(!value);
        switchMode.setEnabled(!value);
        if (!value) updateMode();
        loading.accept(value);
    }

    @Override public void onCancelPressed() {
        ++attempt;
        location.cancel();
        if (request != null) request.cancel(true);
        if (!disposed) setBusy(false);
    }

    @Override public boolean onBackPressed(boolean invoked) {
        if (busy) { if (invoked) onCancelPressed(); return false; }
        if (registering) {
            if (invoked) { registering = false; updateMode(); }
            return false;
        }
        return true;
    }

    public void dispose() {
        disposed = true;
        ++attempt;
        location.dispose();
        worker.shutdownNow();
    }
    @Override public void onDestroyActivity() { dispose(); }
    public void onLocationPermissionsResult(int[] results) { location.onPermissionsResult(results); }

    @Override public void updateColors() {
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        switchMode.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        switchMode.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        for (EditTextBoldCursor field : fields) {
            field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        }
        for (OutlineTextContainerView outline : outlines) outline.updateColor();
    }
}
