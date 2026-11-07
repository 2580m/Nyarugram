package org.telegram.botgram.e2ee;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.PasscodeActivity;

import java.util.ArrayList;
import java.util.List;

public final class BotgramE2eeChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int MENU_CHANGE_PIN = 1;
    private static final int MENU_RESET = 2;

    private final ArrayList<BotgramRpc.E2eeChatStatus> chats = new ArrayList<>();
    private RecyclerListView listView;
    private Adapter adapter;
    private FrameLayout emptyView;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        getNotificationCenter().addObserver(this, NotificationCenter.botgramE2eeStatusChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.botgramE2eeLockChanged);
        refresh();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.botgramE2eeStatusChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.botgramE2eeLockChanged);
        super.onFragmentDestroy();
    }

    private void refresh() {
        BotgramE2eeController.get(currentAccount).refreshList(true, (values, error) -> {
            chats.clear();
            if (values != null) {
                for (BotgramRpc.E2eeChatStatus value : values) {
                    if (BotgramE2eeSendPolicy.selfEnabled(value)) chats.add(value);
                }
            }
            if (error != null) BotgramE2eeUi.toastText(BotgramE2eeStrings.errorText(error.text));
            updateEmpty();
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    private void updateEmpty() {
        if (emptyView == null) return;
        emptyView.setVisibility(chats.isEmpty() ? View.VISIBLE : View.GONE);
        if (listView != null) listView.setVisibility(chats.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.BotgramE2eeTitle));
        ActionBarMenu menu = actionBar.createMenu();
        if (BotgramE2eeController.get(currentAccount).hasPin()) {
            ActionBarMenuItem item = menu.addItem(0, R.drawable.ic_ab_other);
            item.addSubItem(MENU_CHANGE_PIN, R.drawable.msg_pin_code, LocaleController.getString(R.string.BotgramE2eeChangePin));
            item.addSubItem(MENU_RESET, R.drawable.msg_delete, LocaleController.getString(R.string.BotgramE2eeResetKeys));
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CHANGE_PIN) {
                    BotgramE2eeFlow.requirePin(BotgramE2eeChatsActivity.this,
                            () -> presentFragment(PasscodeActivity.forBotgramE2eePinChange(null)));
                } else if (id == MENU_RESET) {
                    confirmReset(context);
                }
            }
        });

        FrameLayout frame = new FrameLayout(context);
        fragmentView = frame;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new Adapter());
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= chats.size()) return;
            long peer = chats.get(position).getTlUserId();
            BotgramE2eeFlow.requirePin(this, () -> presentFragment(ChatActivity.of(peer)));
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new FrameLayout(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView lock = new ImageView(context);
        lock.setImageResource(R.drawable.wallet_lock);
        lock.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        content.addView(lock, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));

        ImageView key = new ImageView(context);
        key.setImageResource(R.drawable.wallet_key);
        key.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        content.addView(key, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, -12, 0, 0));

        TextView text = new TextView(context);
        text.setText(LocaleController.getString(R.string.BotgramE2eeEmptyList));
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        text.setTextSize(17);
        text.setTypeface(Typeface.DEFAULT);
        text.setGravity(Gravity.CENTER);
        content.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 24, 16, 24, 0));

        emptyView.addView(content, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateEmpty();
        return fragmentView;
    }

    private void confirmReset(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.BotgramE2eeResetKeys));
        builder.setMessage(LocaleController.getString(R.string.BotgramE2eeResetInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.BotgramE2eeResetConfirm), (dialog, which) ->
                BotgramE2eeController.get(currentAccount).resetLocalVault((success, code) -> {
                    if (getParentActivity() == null) return;
                    BotgramE2eeUi.toastText(LocaleController.getString(success
                            ? R.string.BotgramE2eeResetDone : R.string.BotgramE2eeFailed));
                    refresh();
                }));
        builder.setNegativeButton(LocaleController.getString(R.string.BotgramE2eeCancel), null);
        builder.show();
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        @Override public int getItemCount() { return chats.size(); }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ManageChatUserCell cell = new ManageChatUserCell(parent.getContext(), 7, 6, false);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(cell);
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            BotgramRpc.E2eeChatStatus status = chats.get(position);
            long peer = status.getTlUserId();
            TLRPC.User user = getMessagesController().getUser(peer);
            ((ManageChatUserCell) holder.itemView).setData(user, null,
                    BotgramE2eeUi.statusText(status), position != chats.size() - 1);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.botgramE2eeStatusChanged
                || id == NotificationCenter.botgramE2eeLockChanged) {
            refresh();
        }
    }
}
