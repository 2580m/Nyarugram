package org.telegram.messenger.voip;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public final class IncomingCallNotificationChannel {
    private IncomingCallNotificationChannel() { }

    public static synchronized String get(Context context, IncomingCallPolicy.Decision policy) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return policy.channelId;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return IncomingCallPolicy.ensureChannel(policy, new IncomingCallPolicy.Channels() {
            @Override public boolean exists(String id) {
                return manager.getNotificationChannel(id) != null;
            }
            @Override public void create(String id, int importance) {
                NotificationChannel channel = new NotificationChannel(id,
                        LocaleController.getString(R.string.IncomingCallsSystemSetting), importance);
                channel.setDescription(LocaleController.getString(R.string.IncomingCallsSystemSettingDescription));
                AudioAttributes audio = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_RING)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build();
                // The existing VoIP player owns the looping ringtone. Never inherit legacy DND overrides.
                channel.setSound(null, audio);
                channel.enableVibration(false);
                channel.enableLights(false);
                manager.createNotificationChannel(channel);
            }
        });
    }
}
