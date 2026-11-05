package org.telegram.botgram;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Account-owned cache: reads never perform IO or initiate requests. */
public final class BotgramSpecialCare {
    private static final ConcurrentHashMap<Integer, BotgramSpecialCare> instances = new ConcurrentHashMap<>();
    public static BotgramSpecialCare get(int account) { return instances.computeIfAbsent(account, BotgramSpecialCare::new); }
    private final int account;
    private final BotgramSpecialCareState state = new BotgramSpecialCareState();
    private final HashSet<Long> pending = new HashSet<>();
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private BotgramSession credentials;
    private volatile String owner;
    private long nextPoll;
    private boolean loading;
    private boolean refreshAgain;
    private long invalidationVersion;
    private BotgramSpecialCare(int account) { this.account = account; }

    public String sessionIdentity() { return owner; }
    public boolean isSpecial(long userId) { return state.isSpecial(userId); }
    public ArrayList<Long> list() { return state.list(); }
    public boolean eligible(TLRPC.User user) {
        return user != null && user.id > 0 && user.id != UserConfig.getInstance(account).getClientUserId()
                && !user.self && !user.bot && !user.deleted && user.contact
                && MessagesController.getInstance(account).blockePeers.indexOfKey(user.id) < 0;
    }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                MessagesController.UPDATE_MASK_ALL);
    }

    void bind(BotgramSession session) {
        if (credentials == session) return;
        state.bind(null);
        credentials = session;
        owner = session.token == null ? null : BotgramSpecialCareState.owner(account, session.server, session.userId, session.token);
        final String key = owner;
        final long generation = state.bind(key);
        pending.clear();
        loading = false;
        refreshAgain = false;
        nextPoll = 0;
        if (key == null) return;
        final long mark = state.localMark();
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<Long> ids = new ArrayList<>();
            ArrayList<Long> suppressed = new ArrayList<>();
            long revision = -1;
            try {
                String text = ApplicationLoader.applicationContext.getSharedPreferences("botgram.specialcare." + account, Context.MODE_PRIVATE)
                        .getString(key, null);
                if (text != null) {
                    JSONObject data = new JSONObject(text);
                    revision = data.getLong("revision");
                    JSONArray removed = data.optJSONArray("suppressed");
                    if (removed != null) for (int i = 0; i < removed.length(); i++) suppressed.add(removed.getLong(i));
                    JSONArray array = data.getJSONArray("users");
                    for (int i = 0; i < array.length(); i++) ids.add(array.getLong(i));
                }
            } catch (Exception ignored) { }
            final long version = revision;
            AndroidUtilities.runOnUIThread(() -> {
                if (state.restoreDisk(generation, mark, version, ids, suppressed)) changed();
            });
        });
    }

    void clear() {
        credentials = null;
        owner = null;
        state.bind(null);
        pending.clear();
        loading = false;
        nextPoll = 0;
        Utilities.globalQueue.postRunnable(() -> ApplicationLoader.applicationContext
                .getSharedPreferences("botgram.specialcare." + account, Context.MODE_PRIVATE).edit().clear().commit());
        changed();
    }

    private void persist() {
        final String key = owner;
        final long revision = state.revision();
        final ArrayList<Long> ids = state.cachedUsers();
        final ArrayList<Long> suppressed = state.suppressed();
        if (key == null) return;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject data = new JSONObject().put("revision", revision).put("users", new JSONArray(ids)).put("suppressed", new JSONArray(suppressed));
                ApplicationLoader.applicationContext.getSharedPreferences("botgram.specialcare." + account, Context.MODE_PRIVATE)
                        .edit().putString(key, data.toString()).commit();
            } catch (Exception ignored) { }
        });
    }

    /** One delete/block operation. Pending suppression ends with its exact success or failure. */
    public static final class Suppression {
        final long generation;
        final long userId;
        final long token;
        private Suppression(long generation, long userId, long token) {
            this.generation = generation;
            this.userId = userId;
            this.token = token;
        }
    }

    /** Immediately hides the contact while the delete/block request is in flight. */
    public Suppression beginSuppress(long userId) {
        if (owner == null) return null;
        final long generation = state.generation();
        final long token = state.beginSuppress(generation, userId);
        if (token == 0) return null;
        invalidationVersion++;
        changed();
        return new Suppression(generation, userId, token);
    }

    /** Server accepted the delete/block: keep suppression until an authoritative snapshot confirms. */
    public void confirmSuppress(Suppression operation) {
        if (operation == null) return;
        if (!state.confirmSuppress(operation.generation, operation.userId, operation.token)) return;
        persist();
        changed();
    }

    /** Delete/block failed: roll back this operation only, then re-sync the authoritative state. */
    public void cancelSuppress(Suppression operation) {
        if (operation == null) return;
        if (!state.cancelSuppress(operation.generation, operation.userId, operation.token)) return;
        changed();
        refresh();
    }

    void poll() {
        if (android.os.SystemClock.elapsedRealtime() >= nextPoll) refresh();
    }
    public void refresh() {
        if (owner == null) return;
        if (loading) { refreshAgain = true; return; }
        loading = true;
        nextPoll = android.os.SystemClock.elapsedRealtime() + 30_000;
        final long generation = state.generation();
        request(0, false, (snapshot, error) -> {
            if (!state.active(generation)) return;
            loading = false;
            if (error == null) apply(generation, snapshot);
            if (refreshAgain) { refreshAgain = false; refresh(); }
        });
    }

    /** Callback is true only after a confirmed full snapshot; it is not tied to a page lifecycle. */
    public void set(long userId, boolean enabled, Consumer<Boolean> callback) {
        if (owner == null || pending.contains(userId) || (enabled && !eligible(MessagesController.getInstance(account).getUser(userId)))) {
            if (callback != null) callback.accept(false);
            return;
        }
        pending.add(userId);
        final long invalidation = invalidationVersion;
        final long generation = state.generation();
        request(userId, enabled, (snapshot, error) -> {
            if (!state.active(generation)) return;
            pending.remove(userId);
            if (error == null) {
                apply(generation, snapshot);
                if (enabled && invalidation == invalidationVersion && snapshot.revision >= state.revision()) {
                    state.confirmedEnable(generation, userId);
                    persist();
                    changed();
                }
            }
            if (callback != null) callback.accept(error == null);
        });
    }

    private static final class Snapshot {
        long revision;
        final ArrayList<Long> ids = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
    }
    private void apply(long generation, Snapshot snapshot) {
        if (state.apply(generation, snapshot.revision, snapshot.ids)) {
            MessagesController.getInstance(account).putUsers(snapshot.users, false);
            org.telegram.messenger.MessagesStorage.getInstance(account).putUsersAndChats(snapshot.users, null, true, true);
            persist();
            changed();
        }
    }
    private void request(long userId, boolean enabled, java.util.function.BiConsumer<Snapshot, TLRPC.TL_error> callback) {
        BotgramTransport.get(account).typedRequest((api, identities) -> {
            BotgramRpc.RpcRequest.Builder query = BotgramRpc.RpcRequest.newBuilder()
                    .setProtocolMajor(1).setRequestId(UUID.randomUUID().toString());
            if (userId == 0) query.setGetSpecialContacts(BotgramRpc.GetSpecialContactsRequest.newBuilder());
            else {
                TLRPC.TL_inputUser input = new TLRPC.TL_inputUser();
                input.user_id = userId;
                query.setSetSpecialContact(BotgramRpc.SetSpecialContactRequest.newBuilder()
                        .setUser(identities.user(input)).setEnabled(enabled));
            }
            BotgramRpc.RpcResponse response = api.rpc(query.build());
            if (userId == 0 ? !response.hasGetSpecialContacts() : !response.hasSetSpecialContact())
                throw new BotgramApi.ApiError(502, "BOTGRAM_SPECIAL_CONTACTS_RESPONSE_INVALID");
            BotgramRpc.SpecialContacts value = userId == 0 ? response.getGetSpecialContacts() : response.getSetSpecialContact();
            Snapshot snapshot = new Snapshot();
            snapshot.revision = value.getRevision();
            if (snapshot.revision < 0) throw new BotgramApi.ApiError(502, "BOTGRAM_SPECIAL_CONTACTS_REVISION_INVALID");
            if (value.getUsersCount() > 0) {
                BotgramRpc.GetUsersRequest.Builder users = BotgramRpc.GetUsersRequest.newBuilder();
                for (BotgramRpc.UserRef ref : value.getUsersList()) users.addUsers(BotgramRpc.UserSelector.newBuilder().setUser(ref));
                BotgramRpc.RpcResponse resolved = api.rpc(BotgramRpc.RpcRequest.newBuilder().setProtocolMajor(1)
                        .setRequestId(UUID.randomUUID().toString()).setGetUsers(users).build());
                Vector<TLRPC.User> mapped = (Vector<TLRPC.User>) new BotgramProtobufResponseMapper(identities).map(resolved);
                snapshot.users.addAll(mapped.objects);
                for (BotgramRpc.UserRef ref : value.getUsersList()) snapshot.ids.add(identities.userId(ref));
            }
            return snapshot;
        }, callback);
    }
}
