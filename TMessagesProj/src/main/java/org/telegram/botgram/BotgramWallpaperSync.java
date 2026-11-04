package org.telegram.botgram;

import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.ChatThemeController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.UUID;

/** UI-owned revision watermark. Replaced on every authenticated session generation. */
final class BotgramWallpaperSync {
    private final int account;
    private long revision = -1;
    private long hintedRevision = -1;
    private long hash;
    private long nextPoll;
    private long stateVersion;
    private boolean loading;
    private boolean retryForced;

    BotgramWallpaperSync(int account) { this.account = account; }

    void hint(long value) {
        if (value >= 0 && (value == revision || value == hintedRevision)) return;
        if (value >= 0) hintedRevision = value;
        stateVersion++;
        nextPoll = 0;
        if (loading) {
            retryForced = true;
            return;
        }
        refresh(value < 0);
    }

    void changed() {
        stateVersion++;
        nextPoll = 0;
        if (loading) retryForced = true;
        else refresh(true);
    }

    private static final class Snapshot {
        long stateVersion;
        long revision;
        long hash;
        boolean changed;
        TLRPC.WallPaper installed;
        final ArrayList<TL_update.TL_updatePeerWallpaper> peers = new ArrayList<>();
    }

    void refresh(boolean force) {
        if (UserConfig.selectedAccount != account) return;
        if (loading) {
            retryForced |= force;
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now < nextPoll) return;
        final long previousRevision = revision;
        final long previousHash = hash;
        final long snapshotVersion = stateVersion;
        final long[] peers = MessagesController.getInstance(account).getWallpaperPeerIds();
        loading = true;
        nextPoll = now + 30_000;
        BotgramTransport.get(account).wallpaperRequest((api, identities) -> {
            BotgramRpc.RpcResponse response = api.rpc(BotgramRpc.RpcRequest.newBuilder()
                    .setProtocolMajor(1).setRequestId(UUID.randomUUID().toString())
                    .setGetWallpapers(BotgramRpc.GetWallpapersRequest.newBuilder().setHash(previousHash)).build());
            BotgramProtobufResponseMapper mapper = new BotgramProtobufResponseMapper(identities);
            mapper.map(response);
            BotgramRpc.WallpaperList list = response.getGetWallpapers();
            Snapshot snapshot = new Snapshot();
            snapshot.stateVersion = snapshotVersion;
            snapshot.revision = list.getRevision();
            snapshot.hash = list.getHash();
            snapshot.changed = force || list.getRevision() != previousRevision;
            snapshot.installed = BotgramProtobufWallpaper.state(list.getInstalled());
            return snapshot;
        }, (snapshot, error) -> {
            if (error != null) finish(null, error);
            else if (!snapshot.changed) finish(snapshot, null);
            else refreshPeer(snapshot, peers, 0);
        });
    }

    private void refreshPeer(Snapshot snapshot, long[] peers, int index) {
        if (UserConfig.selectedAccount != account || snapshot.stateVersion != stateVersion || index == peers.length) {
            finish(snapshot, null);
            return;
        }
        final long dialogId = peers[index];
        BotgramTransport.get(account).wallpaperRequest((api, identities) -> {
            TLObject query;
            if (dialogId > 0) {
                TLRPC.TL_users_getFullUser user = new TLRPC.TL_users_getFullUser();
                TLRPC.TL_inputUser input = new TLRPC.TL_inputUser();
                input.user_id = dialogId;
                input.access_hash = 1;
                user.id = input;
                query = user;
            } else {
                TLRPC.TL_messages_getFullChat group = new TLRPC.TL_messages_getFullChat();
                group.chat_id = -dialogId;
                query = group;
            }
            BotgramRpc.RpcResponse full = api.rpc(new BotgramProtobufRequestMapper(identities)
                    .map(query, UUID.randomUUID().toString()));
            if (dialogId > 0 ? !full.getGetFullUser().hasWallpaper() : !full.getGetFullGroup().hasWallpaper())
                throw new BotgramApi.ApiError(502, "BOTGRAM_WALLPAPER_STATE_MISSING");
            new BotgramProtobufResponseMapper(identities).map(full);
            BotgramRpc.WallpaperState state = dialogId > 0 ? full.getGetFullUser().getWallpaper() : full.getGetFullGroup().getWallpaper();
            TL_update.TL_updatePeerWallpaper update = new TL_update.TL_updatePeerWallpaper();
            if (dialogId > 0) {
                TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                peer.user_id = dialogId;
                update.peer = peer;
            } else {
                TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
                peer.chat_id = -dialogId;
                update.peer = peer;
            }
            update.wallpaper = BotgramProtobufWallpaper.state(state);
            update.wallpaper_overridden = state.getOverridden();
            update.flags = (update.wallpaper == null ? 0 : 1) | (update.wallpaper_overridden ? 2 : 0);
            return update;
        }, (update, error) -> {
            if (error != null) finish(null, error);
            else {
                snapshot.peers.add(update);
                refreshPeer(snapshot, peers, index + 1);
            }
        });
    }

    private void finish(Snapshot snapshot, TLRPC.TL_error error) {
        loading = false;
        if (error != null) {
            FileLog.e("Wallpaper refresh failed: " + error.text);
            nextPoll = android.os.SystemClock.elapsedRealtime() + 10_000;
        } else if (UserConfig.selectedAccount == account && snapshot.stateVersion == stateVersion) {
            if (snapshot.changed) {
                for (TL_update.TL_updatePeerWallpaper peer : snapshot.peers)
                    ChatThemeController.getInstance(account).processUpdate(peer);
                Theme.getActiveTheme().setOverrideWallpaper(BotgramWallpaperActions.override(snapshot.installed));
                Theme.reloadWallpaper(true);
            }
            revision = snapshot.revision;
            hash = snapshot.hash;
        }
        if (retryForced) {
            retryForced = false;
            refresh(true);
        }
    }
}
