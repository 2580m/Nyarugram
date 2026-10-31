package org.telegram.botgram;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Replaces authentication only; successful login uses LoginActivity's original completion path. */
public final class BotgramLoginView extends ScrollView {
    public interface Completion { void done(TLRPC.User user); }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean disposed;
    private final BotgramRegistrationLocation registrationLocation;

    public BotgramLoginView(Context context, android.app.Activity activity, int account, Completion completion) {
        super(context);
        registrationLocation = new BotgramRegistrationLocation(activity);
        setFillViewport(true);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout form = new LinearLayout(context); form.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(24); form.setPadding(padding, padding, padding, padding); addView(form);
        TextView title = new TextView(context); title.setText("登录聊天服务"); title.setTextSize(24);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)); form.addView(title);
        BotgramSession saved = new BotgramSession(context, account);
        EditText server = field(context, "服务器地址", false); server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); server.setText(saved.server); form.addView(server);
        EditText username = field(context, "用户名", false); form.addView(username);
        EditText display = field(context, "显示名称（注册时填写）", false); form.addView(display);
        EditText password = field(context, "密码", true); form.addView(password);
        TextView status = new TextView(context); status.setTextSize(14); status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        status.setText("模拟器：http://10.0.2.2:5080\n真机请填写服务端地址；公网使用 HTTPS。"); form.addView(status);
        LinearLayout actions = new LinearLayout(context); form.addView(actions);
        for (boolean register : new boolean[]{false, true}) {
            Button button = new Button(context); button.setText(register ? "注册" : "登录"); actions.addView(button);
            button.setOnClickListener(view -> {
                String address = server.getText().toString().trim(), name = username.getText().toString().trim();
                String secret = password.getText().toString(), displayName = display.getText().toString().trim();
                for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).setEnabled(false);
                status.setText("正在认证…");
                java.util.function.Consumer<String> authenticate = locationCountry -> worker.execute(() -> {
                    try {
                        BotgramApi anonymous = new BotgramApi(address, null);
                        JSONObject session = anonymous.object("POST", register ? "/api/auth/register" : "/api/auth/login",
                                BotgramApi.json("username", name, "password", secret, "displayName", displayName,
                                        "locationCountry", locationCountry == null ? JSONObject.NULL : locationCountry));
                        JSONObject identity = new BotgramApi(anonymous.baseUrl, session.getString("token")).object("GET", "/api/telegram/me", null);
                        TLRPC.User user = (TLRPC.User) BotgramTransport.decode(identity, TLRPC.User.class, 0);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (disposed) return;
                            try {
                                saved.save(anonymous.baseUrl, session);
                                BotgramTransport.get(account).authenticated();
                                password.setText(""); completion.done(user);
                            } catch (Exception error) { status.setText(error.getMessage()); enable(actions); }
                        });
                    } catch (Exception error) {
                        AndroidUtilities.runOnUIThread(() -> { if (!disposed) { status.setText(error.getMessage()); enable(actions); } });
                    }
                });
                if (register) {
                    status.setText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.BotgramLocatingRegion));
                    registrationLocation.resolve(authenticate);
                } else authenticate.accept(null);
            });
        }
    }
    private void enable(LinearLayout actions) { for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).setEnabled(true); }
    private EditText field(Context context, String hint, boolean password) {
        EditText input = new EditText(context); input.setHint(hint); input.setSingleLine(true);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)); input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        return input;
    }
    public void dispose() { disposed = true; registrationLocation.dispose(); worker.shutdownNow(); }
    public void onLocationPermissionsResult(int[] results) { registrationLocation.onPermissionsResult(results); }
    public void updateColors() {
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        colors(this);
    }
    private void colors(View view) {
        if (view instanceof TextView) ((TextView)view).setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        if (view instanceof EditText) ((EditText)view).setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) colors(group.getChildAt(i));
        }
    }
}
