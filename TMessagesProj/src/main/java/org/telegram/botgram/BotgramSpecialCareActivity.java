package org.telegram.botgram;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ManageChatTextCell;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ContactsActivity;
import java.util.ArrayList;

/** Reuses the original privacy-list cells and contact picker. */
public final class BotgramSpecialCareActivity extends BaseFragment {
    private RecyclerListView listView;
    private Adapter adapter;
    private ArrayList<Long> users = new ArrayList<>();
    private final Runnable update = () -> {
        users = BotgramSpecialCare.get(currentAccount).list();
        if (adapter != null) adapter.notifyDataSetChanged();
    };
    @Override public boolean onFragmentCreate() {
        super.onFragmentCreate();
        BotgramSpecialCare.get(currentAccount).addListener(update);
        update.run();
        BotgramSpecialCare.get(currentAccount).refresh();
        return true;
    }
    @Override public void onFragmentDestroy() {
        BotgramSpecialCare.get(currentAccount).removeListener(update);
        super.onFragmentDestroy();
    }
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.BotgramSpecialCareTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout frame = new FrameLayout(context);
        fragmentView = frame;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new Adapter());
        frame.addView(listView, LayoutHelper.createFrame(-1, -1));
        listView.setOnItemClickListener((view, position) -> {
            if (position == 0) {
                Bundle args = new Bundle();
                args.putBoolean("onlyUsers", true);
                args.putBoolean("destroyAfterSelect", true);
                args.putBoolean("returnAsResult", true);
                ContactsActivity picker = new ContactsActivity(args);
                picker.setCurrentAccount(currentAccount);
                picker.setDelegate((user, param, activity) -> {
                    activity.finishFragment();
                    BotgramSpecialCareUi.confirm(this, currentAccount, user);
                });
                presentFragment(picker);
            } else if (position == 1) {
                Intent intent = new Intent(Build.VERSION.SDK_INT >= 26 ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                if (Build.VERSION.SDK_INT >= 26) intent.putExtra(Settings.EXTRA_CHANNEL_ID,
                        BotgramSpecialCareNotifications.ensureSpecialCareChannel(currentAccount));
                try { context.startActivity(intent); } catch (Exception ignored) { }
            } else if (position < users.size() + 2) {
                BotgramSpecialCareUi.confirm(this, currentAccount, getMessagesController().getUser(users.get(position - 2)));
            }
        });
        return fragmentView;
    }
    private final class Adapter extends RecyclerListView.SelectionAdapter {
        @Override public int getItemCount() { return users.size() + 3; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return holder.getAdapterPosition() < users.size() + 2; }
        @Override public int getItemViewType(int position) { return position < 2 ? 0 : position == users.size() + 2 ? 2 : 1; }
        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            View view = type == 0 ? new ManageChatTextCell(parent.getContext()) : type == 1
                    ? new ManageChatUserCell(parent.getContext(), 7, 6, false) : new TextInfoPrivacyCell(parent.getContext());
            if (type != 2) view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position < 2) {
                ((ManageChatTextCell) holder.itemView).setText(LocaleController.getString(position == 0
                        ? R.string.BotgramSpecialCareAdd : R.string.BotgramSpecialCareChannelSettings), null,
                        position == 0 ? R.drawable.msg_contact_add : R.drawable.msg_mute, true);
            } else if (position == users.size() + 2) {
                ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.BotgramSpecialCareInfo));
            } else {
                ((ManageChatUserCell) holder.itemView).setData(getMessagesController().getUser(users.get(position - 2)), null,
                        LocaleController.getString(R.string.BotgramSpecialCareTitle), position < users.size() + 1);
            }
        }
    }
}
