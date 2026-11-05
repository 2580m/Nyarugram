package org.telegram.botgram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.ui.LaunchActivity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class BotgramMissedCallNotifications {
    private static final String CHANNEL = "botgram_missed_calls_v1";
    private static final String TAG = "botgram.missed_call.";

    private BotgramMissedCallNotifications() { }

    private static String key(String owner) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return value.toString();
    }

    private static SharedPreferences history(int account, String owner) {
        return ApplicationLoader.applicationContext.getSharedPreferences("botgram.missed_calls." + account + "." + owner, Context.MODE_PRIVATE);
    }

    private static boolean handled(SharedPreferences preferences, long id) {
        return id <= preferences.getLong("prunedThrough", 0)
                || preferences.getStringSet("handled", Collections.emptySet()).contains(Long.toString(id));
    }

    private static void remember(SharedPreferences preferences, long id) {
        Set<String> ids = new HashSet<>(preferences.getStringSet("handled", Collections.emptySet()));
        ids.add(Long.toString(id));
        long floor = preferences.getLong("prunedThrough", 0);
        while (ids.size() > 512) {
            long oldest = Long.MAX_VALUE;
            for (String value : ids) oldest = Math.min(oldest, Long.parseLong(value));
            ids.remove(Long.toString(oldest));
            floor = Math.max(floor, oldest);
        }
        preferences.edit().putStringSet("handled", ids).putLong("prunedThrough", floor).apply();
    }

    public static void observe(int account, TL_phone.PhoneCall call) {
        if (!(call instanceof TL_phone.TL_phoneCallDiscarded) || call.id == 0) return;
        final String owner = BotgramTransport.get(account).notificationOwner();
        final long ownId = UserConfig.getInstance(account).getClientUserId();
        if (owner == null || !BotgramMissedCallState.isMissed(call, ownId)) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.equals(BotgramTransport.get(account).notificationOwner())
                    || ownId != UserConfig.getInstance(account).getClientUserId()) return;
            try {
                String scope = key(owner);
                SharedPreferences preferences = history(account, scope);
                if (handled(preferences, call.id)) return;
                Context context = ApplicationLoader.applicationContext;
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return;
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL,
                            LocaleController.getString(R.string.BotgramMissedCallNotification), NotificationManager.IMPORTANCE_DEFAULT);
                    channel.setSound(null, null);
                    channel.enableVibration(false);
                    manager.createNotificationChannel(channel);
                }
                String title = LocaleController.getString(call.video ? R.string.CallMessageVideoIncomingMissed : R.string.BotgramMissedCallNotification);
                TLRPC.User user = MessagesController.getInstance(account).getUser(call.admin_id);
                String caller = user == null ? title : ContactsController.formatName(user);
                Intent intent = new Intent(context, LaunchActivity.class)
                        .setAction("com.tmessages.openchat.botgram_missed." + scope + "." + call.id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("currentAccount", account).putExtra("userId", call.admin_id);
                PendingIntent open = PendingIntent.getActivity(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
                Notification publicNotification = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context))
                        .setSmallIcon(R.drawable.call).setContentTitle(title).build();
                builder.setSmallIcon(R.drawable.call).setContentTitle(title).setContentText(caller)
                        .setCategory(Notification.CATEGORY_MISSED_CALL).setAutoCancel(true)
                        .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setPublicVersion(publicNotification).setOnlyAlertOnce(true);
                if (call.date > 0) builder.setWhen(call.date * 1000L).setShowWhen(true);
                manager.notify(TAG + account + "." + scope + "." + call.id, 0, builder.build());
                remember(preferences, call.id);
            } catch (Exception error) { FileLog.e(error); }
        });
    }

    /** An explicit local rejection must remain suppressed even when its terminal update is replayed. */
    public static void suppress(int account, long callId) {
        String owner = BotgramTransport.get(account).notificationOwner();
        if (owner == null || callId == 0) return;
        try { remember(history(account, key(owner)), callId); }
        catch (Exception error) { FileLog.e(error); }
    }

    public static void clear(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationManager manager = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
                for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
                    if (notification.getTag() != null && notification.getTag().startsWith(TAG + account + "."))
                        manager.cancel(notification.getTag(), notification.getId());
                }
            } catch (Exception error) { FileLog.e(error); }
        });
    }
}
