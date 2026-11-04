package org.telegram.botgram;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatThemeController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.botgram.protocol.v1.BotgramRpc;

/** Applies persisted server state, never optimistic wallpaper changes. */
public final class BotgramWallpaperActions {
    private BotgramWallpaperActions() { }

    public static void error(int account, boolean unsupported) {
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null || fragment.getCurrentAccount() != account || fragment.getParentActivity() == null) return;
            fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                    .setTitle(getString(R.string.ChatBackground))
                    .setMessage(getString(unsupported ? R.string.BotgramWallpaperUnsupported : R.string.BotgramWallpaperFailed))
                    .setPositiveButton(getString(R.string.OK), null).create());
        });
    }

    public static int set(int account, long dialogId, TLRPC.InputWallPaper wallpaper,
                          TLRPC.WallPaperSettings settings, boolean forBoth, boolean revert, Runnable success) {
        return set(account, dialogId, wallpaper, settings, forBoth, revert,
                value -> { if (success != null) success.run(); }, null);
    }

    public static int set(int account, long dialogId, TLRPC.InputWallPaper wallpaper,
                          TLRPC.WallPaperSettings settings, boolean forBoth, boolean revert,
                          java.util.function.Consumer<TLRPC.WallPaper> success, Runnable finished) {
        TLRPC.TL_messages_setChatWallPaper request = new TLRPC.TL_messages_setChatWallPaper();
        request.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        request.for_both = forBoth;
        request.revert = revert;
        if (wallpaper != null) {
            request.flags |= 1;
            request.wallpaper = wallpaper;
        }
        if (settings != null) {
            request.flags |= 4;
            request.settings = settings;
        }
        return ConnectionsManager.getInstance(account).sendRequest(request, (response, failure) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (!(response instanceof TLRPC.Updates) || failure != null) {
                        if (finished != null) finished.run();
                        error(account, failure != null && failure.code == 501);
                        return;
                    }
                    TLRPC.Updates updates = (TLRPC.Updates) response;
                    boolean applied = false;
                    TLRPC.WallPaper confirmed = null;
                    for (TLRPC.Update update : updates.updates) {
                        if (update instanceof TL_update.TL_updatePeerWallpaper) {
                            TL_update.TL_updatePeerWallpaper changed = (TL_update.TL_updatePeerWallpaper) update;
                            if (DialogObject.getPeerDialogId(changed.peer) == dialogId) {
                                ChatThemeController.getInstance(account).processUpdate(changed);
                                confirmed = changed.wallpaper;
                                applied = true;
                            }
                        }
                    }
                    if (!applied) {
                        if (finished != null) finished.run();
                        error(account, false);
                        return;
                    }
                    BotgramTransport.get(account).wallpaperChanged();
                    if (finished != null) finished.run();
                    if (success != null) success.accept(confirmed);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.wallpaperSettedToUser);
                }));
    }

    public static TLRPC.TL_wallPaperSettings settings(Theme.OverrideWallpaperInfo info) {
        TLRPC.TL_wallPaperSettings settings = MessagesController.getWallpaperSetting(info);
        settings.flags |= 1;
        settings.background_color = info.color & 0xffffff;
        return settings;
    }

    public static Theme.OverrideWallpaperInfo override(TLRPC.WallPaper wallpaper) {
        if (wallpaper == null) return null;
        TLRPC.WallPaperSettings settings = wallpaper.settings;
        Theme.OverrideWallpaperInfo result = new Theme.OverrideWallpaperInfo();
        result.slug = Theme.COLOR_BACKGROUND_SLUG;
        result.wallpaperId = wallpaper.id;
        result.color = settings.background_color | 0xff000000;
        result.gradientColor1 = (settings.flags & 16) != 0 ? settings.second_background_color | 0xff000000 : 0;
        result.gradientColor2 = (settings.flags & 32) != 0 ? settings.third_background_color | 0xff000000 : 0;
        result.gradientColor3 = (settings.flags & 64) != 0 ? settings.fourth_background_color | 0xff000000 : 0;
        result.rotation = AndroidUtilities.getWallpaperRotation(settings.rotation, false);
        result.isBlurred = settings.blur;
        result.isMotion = settings.motion;
        result.intensity = settings.intensity / 100f;
        return result;
    }

    public static void install(int account, Theme.OverrideWallpaperInfo info, Runnable success, Runnable finished) {
        installWithResult(account, info, value -> { if (success != null) success.run(); }, finished);
    }

    public static void installWithResult(int account, Theme.OverrideWallpaperInfo info,
                                         java.util.function.Consumer<TLRPC.WallPaper> success, Runnable finished) {
        BotgramTransport.get(account).wallpaperRequest((api, identities) -> {
            BotgramRpc.InstallWallpaperRequest.Builder selection = BotgramRpc.InstallWallpaperRequest.newBuilder();
            if (info == null) selection.setClear(BotgramRpc.Empty.getDefaultInstance());
            else {
                TLRPC.TL_inputWallPaperNoFile input = new TLRPC.TL_inputWallPaperNoFile();
                input.id = info.wallpaperId;
                selection.setWallpaper(BotgramProtobufWallpaper.encode(input, settings(info)));
            }
            BotgramRpc.RpcResponse response = api.rpc(BotgramRpc.RpcRequest.newBuilder().setProtocolMajor(1)
                    .setRequestId(java.util.UUID.randomUUID().toString()).setInstallWallpaper(selection).build());
            new BotgramProtobufResponseMapper(identities).map(response);
            return BotgramProtobufWallpaper.state(response.getInstallWallpaper().getInstalled());
        }, (wallpaper, failure) -> {
            if (finished != null) finished.run();
            if (failure != null) {
                error(account, failure.code == 501);
                return;
            }
            if (org.telegram.messenger.UserConfig.selectedAccount == account) {
                Theme.getActiveTheme().setOverrideWallpaper(override(wallpaper));
                Theme.reloadWallpaper(true);
            }
            BotgramTransport.get(account).wallpaperChanged();
            if (success != null) success.accept(wallpaper);
        });
    }
}
