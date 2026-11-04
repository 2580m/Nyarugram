/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {

    private static final String CHANNEL_ID = "botgram_keep_alive";
    private static final int NOTIFICATION_ID = 0x0B07;

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.NotificationsService), NotificationManager.IMPORTANCE_MIN);
                channel.setDescription(LocaleController.getString(R.string.NotificationsServiceInfo));
                channel.setShowBadge(false);
                channel.enableLights(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_MIN);
        }
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.NotificationsService))
                .setContentText(LocaleController.getString(R.string.NotificationsServiceInfo))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false);
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
