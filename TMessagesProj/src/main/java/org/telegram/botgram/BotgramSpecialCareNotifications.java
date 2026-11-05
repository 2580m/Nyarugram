package org.telegram.botgram;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class BotgramSpecialCareNotifications {
    private BotgramSpecialCareNotifications() {}

    public static String channelId(int account) {
        return "botgram_special_messages_v1_" + account;
    }

    public static Uri defaultSound() {
        return Uri.parse("android.resource://" + ApplicationLoader.applicationContext.getPackageName()
                + "/raw/botgram_special_message");
    }

    public static long[] vibrationPattern() {
        return new long[]{0, 70, 65, 130};
    }

    public static String ensureSpecialCareChannel(int account) {
        if (Build.VERSION.SDK_INT < 26) return null;
        NotificationManager manager = manager();
        String id = channelId(account);
        // Never delete, hash, or recreate an existing channel to override user choices.
        if (manager.getNotificationChannel(id) == null) {
            NotificationChannel channel = new NotificationChannel(id,
                    LocaleController.getString(R.string.BotgramSpecialCareMessagesChannel),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(defaultSound(), attributes());
            channel.enableVibration(true);
            channel.setVibrationPattern(vibrationPattern());
            channel.setBypassDnd(false);
            manager.createNotificationChannel(channel);
        }
        return id;
    }

    private static NotificationManager manager() {
        return (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    }

    public static void playInChat(int account, boolean allowSound, boolean allowVibration,
                                  BooleanSupplier valid, BooleanSupplier soundValid, Consumer<Runnable> dispatch) {
        try {
            Context context = ApplicationLoader.applicationContext;
            NotificationManager manager = manager();
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (!valid.getAsBoolean() || !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                    || manager.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL
                    || audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return;
            Uri sound = defaultSound();
            long[] vibration = vibrationPattern();
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = manager.getNotificationChannel(ensureSpecialCareChannel(account));
                if (channel == null || channel.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return;
                sound = channel.getSound();
                allowVibration &= channel.shouldVibrate();
                if (channel.getVibrationPattern() != null) vibration = channel.getVibrationPattern();
            }
            if (BotgramSpecialCareMessagePolicy.canVibrate(allowVibration,
                    org.telegram.messenger.MessagesController.getNotificationsSettings(account)
                            .getBoolean("EnableInAppVibrate", true), valid.getAsBoolean())) {
                Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(vibration, -1), attributes());
                    else vibrator.vibrate(vibration, -1, attributes());
                }
            }
            if (!allowSound || sound == null || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
            final Uri expectedSound = sound;
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(attributes());
            player.setOnCompletionListener(MediaPlayer::release);
            player.setOnErrorListener((mp, what, extra) -> { mp.release(); return true; });
            player.setOnPreparedListener(mp -> dispatch.accept(() -> {
                try {
                    boolean enabled = valid.getAsBoolean() && soundValid.getAsBoolean()
                            && manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL
                            && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() && audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
                    if (Build.VERSION.SDK_INT >= 26) {
                        NotificationChannel channel = manager.getNotificationChannel(channelId(account));
                        enabled &= channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                                && expectedSound.equals(channel.getSound());
                    }
                    if (enabled) mp.start(); else mp.release();
                } catch (Exception error) { mp.release(); FileLog.e(error); }
            }));
            try {
                player.setDataSource(context, sound);
                player.prepareAsync();
            } catch (Exception error) { player.release(); FileLog.e(error); }
        } catch (Exception error) { FileLog.e(error); }
    }
}
