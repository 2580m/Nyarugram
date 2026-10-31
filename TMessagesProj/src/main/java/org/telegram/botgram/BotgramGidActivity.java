package org.telegram.botgram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** GID editor built from Telegram's standard settings, input and region-list components. */
public final class BotgramGidActivity extends UniversalFragment {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private BotgramGeography.Region selected;
    private EditTextBoldCursor number;
    private TextView prefix;
    private LinearLayout input;
    private boolean saving, destroyed;

    @Override public View createView(Context context) {
        TLRPC.User user = getUserConfig().getCurrentUser();
        String gid = user == null || user.phone == null ? "" : user.phone;
        for (BotgramGeography.Region region : BotgramGeography.regions()) {
            if (gid.startsWith(region.code)) selected = region;
        }
        if (selected == null) selected = BotgramGeography.regions().get(0);
        input = new LinearLayout(context);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(21), dp(12), dp(21), dp(12));
        input.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        prefix = new TextView(context);
        prefix.setTextSize(18);
        prefix.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        prefix.setText("+" + selected.code + " ");
        input.addView(prefix, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48));
        number = new EditTextBoldCursor(context);
        number.setSingleLine(true);
        number.setTextSize(18);
        number.setInputType(InputType.TYPE_CLASS_NUMBER);
        number.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        number.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        number.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        number.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        number.setHint(getString(R.string.BotgramGid));
        if (gid.matches("[0-9]{11}")) number.setText(gid.substring(3));
        input.addView(number, LayoutHelper.createLinear(0, 48, 1));
        return super.createView(context);
    }
    @Override protected CharSequence getTitle() { return getString(R.string.BotgramChangeGid); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(1, getString(R.string.BotgramGeographicRegion), selected.abbreviation + " · " + selected.label() + "  +" + selected.code));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(getString(R.string.BotgramGid)));
        items.add(UItem.asCustom(input));
        items.add(UItem.asShadow(getString(R.string.BotgramGidInputInfo)));
        items.add(UItem.asButton(2, getString(saving ? R.string.Loading : R.string.Done)));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (saving) return;
        if (item.id == 1) {
            AndroidUtilities.hideKeyboard(number);
            presentFragment(new Regions(region -> {
                if (destroyed) return;
                selected = region; prefix.setText("+" + region.code + " ");
                listView.adapter.update(true);
            }));
        } else if (item.id == 2) save();
    }
    private void save() {
        String digits = number.getText().toString();
        if ("001".equals(selected.code)) { error(getString(R.string.BotgramSelectRegion)); return; }
        if (!digits.matches("[0-9]{8}") || "00000000".equals(digits)) { number.setError(getString(R.string.BotgramGidInvalid)); return; }
        BotgramSession session = new BotgramSession(ApplicationLoader.applicationContext, currentAccount);
        if (session.token == null) { error(getString(R.string.ErrorOccurred)); return; }
        String region = selected.code;
        saving = true; number.setEnabled(false); listView.adapter.update(true);
        worker.execute(() -> {
            TLRPC.User updated = null; String failure = null;
            try {
                JSONObject result = new BotgramApi(session.server, session.token).object("PUT", "/api/me/gid",
                        BotgramApi.json("regionCode", region, "number", digits));
                updated = (TLRPC.User) BotgramTransport.decode(result, TLRPC.User.class, 0);
            } catch (Exception e) {
                failure = e instanceof BotgramApi.ApiError && ((BotgramApi.ApiError)e).status == 409
                        ? getString(R.string.BotgramGidTaken) : getString(R.string.ErrorOccurred);
            }
            TLRPC.User result = updated; String message = failure;
            AndroidUtilities.runOnUIThread(() -> {
                BotgramSession active = new BotgramSession(ApplicationLoader.applicationContext, currentAccount);
                if (result != null && session.token.equals(active.token) && session.server.equals(active.server)) {
                    getMessagesController().putUser(result, false);
                    getUserConfig().setCurrentUser(result); getUserConfig().saveConfig(true);
                    ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(result);
                    getMessagesStorage().putUsersAndChats(users, null, true, true);
                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                    getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_PHONE);
                    if (!destroyed) finishFragment();
                } else if (!destroyed) {
                    saving = false; number.setEnabled(true); listView.adapter.update(true);
                    error(message == null ? getString(R.string.ErrorOccurred) : message);
                }
            });
        });
    }
    private void error(String message) {
        if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.BotgramChangeGid)).setMessage(message)
                .setPositiveButton(getString(R.string.OK), null).create());
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void onFragmentDestroy() { destroyed = true; worker.shutdownNow(); super.onFragmentDestroy(); }

    private static final class Regions extends UniversalFragment {
        private final ArrayList<BotgramGeography.Region> regions = new ArrayList<>();
        private final Consumer<BotgramGeography.Region> chosen;
        Regions(Consumer<BotgramGeography.Region> chosen) {
            this.chosen = chosen;
            for (BotgramGeography.Region region : BotgramGeography.regions()) if (!"001".equals(region.code)) regions.add(region);
        }
        @Override protected CharSequence getTitle() { return getString(R.string.BotgramGeographicRegion); }
        @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
            for (int i = 0; i < regions.size(); i++) {
                BotgramGeography.Region region = regions.get(i);
                items.add(UItem.asButton(i, region.abbreviation + "  " + region.label(), "+" + region.code));
            }
        }
        @Override protected void onClick(UItem item, View view, int position, float x, float y) {
            if (item.id >= 0 && item.id < regions.size()) { chosen.accept(regions.get(item.id)); finishFragment(); }
        }
        @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    }
}
