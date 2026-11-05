package org.telegram.messenger.voip;

/**
 * Publish plan shared by every incoming call notification path.
 *
 * Android only accepts Notification.CallStyle when the notification belongs to a real foreground
 * service, a user-initiated job, or carries a full-screen intent. A plain notify() from the
 * pre-notification stage must therefore use the ordinary template unless this call actually
 * received the single full-screen claim.
 */
public final class IncomingCallNotificationPlan {
    public final boolean group;
    public final boolean video;
    public final boolean publishedFromForegroundService;
    public final boolean fullScreenIntent;
    public final boolean callStyle;
    public final boolean answerAction = true;
    public final boolean declineAction = true;
    public final int channelImportance;
    public final String channelId;
    public final int priority;

    private IncomingCallNotificationPlan(IncomingCallPolicy.Decision decision, boolean group, boolean video,
                                         boolean publishedFromForegroundService, boolean fullScreenIntent) {
        this.group = group;
        this.video = video;
        this.publishedFromForegroundService = publishedFromForegroundService;
        this.fullScreenIntent = fullScreenIntent;
        this.callStyle = IncomingCallPolicy.callStyleLegal(publishedFromForegroundService, fullScreenIntent);
        this.channelImportance = decision.importance;
        this.channelId = decision.channelId;
        this.priority = decision.priority();
    }

    public static IncomingCallNotificationPlan create(IncomingCallPolicy.Decision decision, boolean group, boolean video,
                                                      boolean publishedFromForegroundService, boolean fullScreenIntent) {
        return new IncomingCallNotificationPlan(decision, group, video, publishedFromForegroundService, fullScreenIntent);
    }
}
