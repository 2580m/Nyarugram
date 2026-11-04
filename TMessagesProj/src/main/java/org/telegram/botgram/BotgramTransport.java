package org.telegram.botgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.QuickAckDelegate;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Generic TL-object transport. All chat semantics and identity mapping belong to the backend. */
public final class BotgramTransport {
    private static final ConcurrentHashMap<Integer, BotgramTransport> instances = new ConcurrentHashMap<>();
    private static final ExecutorService logouts = Executors.newCachedThreadPool();
    public static BotgramTransport get(int account) { return instances.computeIfAbsent(account, BotgramTransport::new); }
    private final int account;
    private final SharedPreferences binding;
    // Preserve the original stage queue's send order (in particular InvokeAfter sends).
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, Job> pending = new ConcurrentHashMap<>();
    private volatile BotgramSession session;
    private volatile BotgramProtobufIdentity identities;
    private volatile BotgramSession identitySession;
    private BotgramApi sessionApi;
    private BotgramSession apiSession;
    private volatile BotgramSessionScope scope = new BotgramSessionScope();
    private volatile BotgramPremiumState premium = new BotgramPremiumState();
    private BotgramSession premiumLoadedSession;
    private BotgramSession premiumRefreshingSession;
    private long premiumNextRetry;
    private boolean resetting;
    private final ArrayList<Runnable> ready = new ArrayList<>();

    BotgramPremiumState premiumState() {
        return premium;
    }

    public Runnable bindRequest(Runnable task) {
        return enabled() ? scope.bind(task) : task;
    }

    public interface AccountRequest<T> {
        T run(BotgramApi api) throws Exception;
    }

    public interface WallpaperRequest<T> {
        T run(BotgramApi api, BotgramProtobufIdentity identities) throws Exception;
    }

    public <T> void wallpaperRequest(WallpaperRequest<T> operation,
                                     java.util.function.BiConsumer<T, TLRPC.TL_error> callback) {
        final BotgramSession credentials = session;
        final BotgramSessionScope owner = scope;
        accountRequest(api -> {
            String protocol = credentials.protocol();
            if (protocol == null) {
                protocol = api.negotiate();
                if (session != credentials || scope != owner) throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
                credentials.pinProtocol(protocol);
            }
            if (!"protobuf-v1".equals(protocol)) throw new BotgramApi.ApiError(501, "BOTGRAM_WALLPAPER_PROTOCOL_UNSUPPORTED");
            BotgramProtobufIdentity identity = identities;
            if (identity == null || identitySession != credentials) {
                identity = new BotgramProtobufIdentity(api, credentials.userId,
                        () -> session == credentials && scope == owner && !expired, premium);
                identities = identity;
                identitySession = credentials;
            }
            identity.initialize();
            return operation.run(api, identity);
        }, () -> true, callback);
    }

    void wallpaperChanged() {
        if (enabled() && !expired) wallpapers.changed();
    }

    public <T> void accountRequest(AccountRequest<T> operation, java.util.function.BooleanSupplier active,
                                   java.util.function.BiConsumer<T, TLRPC.TL_error> callback) {
        final BotgramSession credentials = session;
        final BotgramSessionScope owner = scope;
        requests.execute(() -> {
            if (session != credentials || scope != owner || !active.getAsBoolean()) return;
            T value = null;
            TLRPC.TL_error failure = null;
            try {
                if (!enabled() || expired || credentials.token == null)
                    throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
                value = operation.run(new BotgramApi(credentials.server, credentials.token));
            } catch (BotgramApi.ApiError error) {
                failure = error(error.status, error.getMessage());
                if (error.status == 401) expire(credentials);
            } catch (Exception error) {
                org.telegram.messenger.FileLog.e(error);
                failure = error(500, "BOTGRAM_TRANSPORT_ERROR");
            }
            final T result = value;
            final TLRPC.TL_error resultError = failure;
            AndroidUtilities.runOnUIThread(owner.bind(() -> {
                if (session == credentials && scope == owner && !expired && active.getAsBoolean())
                    callback.accept(result, resultError);
            }));
        });
    }

    public void premiumStatus(boolean activate, java.util.function.BooleanSupplier active,
                              java.util.function.BiConsumer<Boolean, TLRPC.TL_error> callback) {
        final long userId = UserConfig.getInstance(account).getClientUserId();
        final BotgramPremiumState confirmed = premium;
        accountRequest(api -> {
            JSONObject result = api.object(activate ? "POST" : "GET",
                    activate ? "/api/me/premium/activate" : "/api/me/premium",
                    activate ? new JSONObject() : null);
            Object value = result.get("active");
            if (!(value instanceof Boolean) || userId == 0)
                throw new BotgramApi.ApiError(502, "BOTGRAM_PREMIUM_RESPONSE_INVALID");
            boolean enabled = (Boolean) value;
            if (activate && !enabled) throw new BotgramApi.ApiError(502, "BOTGRAM_PREMIUM_NOT_ACTIVATED");
            confirmed.resolve(userId, enabled);
            return enabled;
        }, () -> true, (enabled, failure) -> {
            TLRPC.TL_error resultError = failure;
            if (failure == null) {
                TLRPC.User current = UserConfig.getInstance(account).getCurrentUser();
                if (current == null || current.id != userId) {
                    resultError = error(409, "BOTGRAM_PREMIUM_USER_CHANGED");
                } else {
                    org.telegram.tgnet.SerializedData bytes = new org.telegram.tgnet.SerializedData();
                    current.serializeToStream(bytes);
                    org.telegram.tgnet.SerializedData input = new org.telegram.tgnet.SerializedData(bytes.toByteArray());
                    TLRPC.User updated = TLRPC.User.TLdeserialize(input, input.readInt32(true), true);
                    input.cleanup();
                    bytes.cleanup();
                    updated.premium = enabled;
                    updated.flags = enabled ? updated.flags | (1 << 28) : updated.flags & ~(1 << 28);
                    UserConfig.getInstance(account).setCurrentUser(updated);
                    UserConfig.getInstance(account).saveConfig(true);
                    MessagesController.getInstance(account).putUser(updated, false);
                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    users.add(updated);
                    org.telegram.messenger.MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                            MessagesController.UPDATE_MASK_ALL);
                }
            }
            if (active.getAsBoolean()) callback.accept(enabled, resultError);
            else if (resultError != null) org.telegram.messenger.FileLog.e("Nyarugram Premium request failed: " + resultError.text);
        });
    }

    public void whenReady(Runnable task) {
        if (resetting) ready.add(task);
        else task.run();
    }

    public void logout(boolean revoke, Runnable cleanup) {
        if (resetting) return;
        resetting = true;
        logout(revoke);
        scope.close(() -> BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            BotgramSessionScope.drain(cleanup, () -> BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
                releaseReady();
            })));
        })));
    }
    private void releaseReady() {
        resetting = false;
        ArrayList<Runnable> tasks = new ArrayList<>(ready);
        ready.clear();
        for (Runnable task : tasks) whenReady(task);
    }

    private volatile long reachable;
    private volatile boolean networkAvailable = ApplicationLoader.isNetworkOnlineRealtime();
    private int publishedState = -1;
    private boolean polling;
    private BotgramWallpaperSync wallpapers;
    private volatile boolean expired;
    private final BotgramEventSocket events = new BotgramEventSocket(new BotgramEventSocket.Listener() {
        @Override public void wake(BotgramSession credentials) {
            if (session == credentials && UserConfig.selectedAccount == account) {
                wakeDifference(true);
            }
        }
        @Override public void wallpaper(BotgramSession credentials, long revision) {
            if (session == credentials && foreground()) wallpapers.hint(revision);
        }
        @Override public void unauthorized(BotgramSession credentials) { expire(credentials); }
    });

    private boolean foreground() {
        return enabled() && !expired && session.token != null && !ApplicationLoader.mainInterfacePaused
                && UserConfig.getInstance(account).isClientActivated();
    }

    // Difference polling also runs while the UI is paused so incoming calls reach VoIPService
    // and can ring/show the call screen; read receipts and presence stay untouched.
    private boolean alertsActive() {
        return enabled() && !expired && session.token != null
                && UserConfig.getInstance(account).isClientActivated();
    }

    private void updateEvents() { events.update(session, foreground() && UserConfig.selectedAccount == account); }

    private void wakeDifference(boolean socketHint) {
        final BotgramSession credentials = session;
        final long generation = events.generation();
        final BotgramSessionScope owner = scope;
        if (!alertsActive()) return;
        Utilities.stageQueue.postRunnable(owner.bind(() -> {
            if (session == credentials && events.generation() == generation && alertsActive()
                    && (!socketHint || UserConfig.selectedAccount == account)) {
                MessagesController.getInstance(account).getDifference();
            }
        }));
    }

    private void expire(BotgramSession credentials) {
        AndroidUtilities.runOnUIThread(() -> {
            if (session != credentials || expired) return;
            expired = true;
            events.stop();
            MessagesController.getInstance(account).performLogout(0);
        });
    }
    private final class Job {
        final BotgramSessionScope owner = scope;
        volatile boolean cancelled;
        volatile int guid;
    }

    private BotgramTransport(int account) {
        this.account = account;
        wallpapers = new BotgramWallpaperSync(account);
        binding = ApplicationLoader.applicationContext.getSharedPreferences("botgram.transport." + account, Context.MODE_PRIVATE);
        session = new BotgramSession(ApplicationLoader.applicationContext, account);
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().addObserver(
                (id, currentAccount, args) -> {
                    updateEvents();
                    if (foreground()) wallpapers.refresh(true);
                }, NotificationCenter.activeAccountChanged));
        ((android.app.Application) ApplicationLoader.applicationContext).registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    private void refresh() { ApplicationLoader.applicationHandler.post(() -> {
                        updateEvents();
                        networkChanged(ApplicationLoader.isNetworkOnlineRealtime());
                        if (foreground()) wallpapers.refresh(true);
                    }); }
                    @Override public void onActivityResumed(android.app.Activity activity) { refresh(); }
                    @Override public void onActivityPaused(android.app.Activity activity) { refresh(); }
                    @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
                    @Override public void onActivityStarted(android.app.Activity activity) { }
                    @Override public void onActivityStopped(android.app.Activity activity) { refresh(); }
                    @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
                    @Override public void onActivityDestroyed(android.app.Activity activity) { }
                });
    }

    // A bound but expired account must never fall through to native Telegram transport.
    public boolean enabled() { return binding.getBoolean("enabled", false); }
    public void logout(boolean revoke) {
        final BotgramSession credentials = session;
        final String server = credentials.server;
        final String token = credentials.token;
        expired = true;
        identities = null;
        premium = new BotgramPremiumState();
        wallpapers = new BotgramWallpaperSync(account);
        AndroidUtilities.runOnUIThread(() -> {
            if (session == credentials && expired) events.stop();
        });
        reachable = 0;
        for (Job job : pending.values()) job.cancelled = true;
        pending.clear();
        // Keep the binding: logged-out slots must not fall back to MTProto.
        // apply() clears preferences in memory now and writes them off the UI thread.
        credentials.clear();
        if (revoke && token != null) {
            // Do not queue revocation behind slow chat requests or delay local logout.
            logouts.execute(() -> {
                try { new BotgramApi(server, token).request("POST", "/api/auth/logout", null); }
                catch (Exception ignored) { /* Local logout also works while offline. */ }
            });
        }
    }
    public void authenticated(BotgramSession credentials, java.util.function.BooleanSupplier active, Runnable completion) {
        if (!active.getAsBoolean()) {
            Utilities.globalQueue.postRunnable(credentials::clearIfCurrent);
            return;
        }
        if (resetting) {
            whenReady(() -> authenticated(credentials, active, completion));
            return;
        }
        if (UserConfig.getInstance(account).isClientActivated()) {
            MessagesController.getInstance(account).performLogout(0);
            whenReady(() -> authenticated(credentials, active, completion));
            return;
        }
        resetting = true;
        scope.close(() -> BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            try {
                if (!active.getAsBoolean()) {
                    Utilities.globalQueue.postRunnable(credentials::clearIfCurrent);
                    return;
                }
                activate(credentials);
                completion.run();
            } finally {
                releaseReady();
            }
        })));
    }

    private void activate(BotgramSession credentials) {
        for (Job job : pending.values()) job.cancelled = true;
        pending.clear();
        scope = new BotgramSessionScope();
        premium = new BotgramPremiumState();
        premiumLoadedSession = null;
        premiumRefreshingSession = null;
        premiumNextRetry = 0;
        wallpapers = new BotgramWallpaperSync(account);
        session = credentials;
        identities = null;
        binding.edit().putBoolean("enabled", true).apply();
        expired = false; reachable = System.currentTimeMillis();
        start();
    }
    public int state() {
        if (!networkAvailable) return ConnectionsManager.ConnectionStateWaitingForNetwork;
        return !expired && reachable != 0 && System.currentTimeMillis() - reachable < 30_000
                ? ConnectionsManager.ConnectionStateConnected : ConnectionsManager.ConnectionStateConnecting;
    }
    public void networkChanged(boolean available) {
        if (networkAvailable != available) reachable = 0;
        networkAvailable = available;
        publishState(false);
    }
    private void publishState(boolean force) {
        BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            if (!enabled()) return;
            int current = state();
            if (force || publishedState != current) {
                publishedState = current;
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didUpdateConnectionState);
            }
        }));
    }
    private void reachable(boolean online) {
        reachable = online && networkAvailable ? System.currentTimeMillis() : 0;
        publishState(false);
    }

    private void refreshPremiumIfNeeded() {
        final BotgramSession credentials = session;
        UserConfig config = UserConfig.getInstance(account);
        if (!enabled() || expired || credentials.token == null || !config.isClientActivated()
                || config.getCurrentUser() == null || premiumLoadedSession == credentials
                || premiumRefreshingSession == credentials
                || android.os.SystemClock.elapsedRealtime() < premiumNextRetry) return;
        premiumRefreshingSession = credentials;
        premiumStatus(false, () -> true, (value, failure) -> {
            if (premiumRefreshingSession == credentials) premiumRefreshingSession = null;
            if (failure == null) {
                premiumLoadedSession = credentials;
            } else {
                premiumNextRetry = android.os.SystemClock.elapsedRealtime() + 30_000;
                org.telegram.messenger.FileLog.e("Nyarugram Premium refresh failed: " + failure.text);
            }
        });
    }

    public void start() {
        BotgramSessionScope.detached(() -> AndroidUtilities.runOnUIThread(() -> {
            updateEvents();
            networkChanged(ApplicationLoader.isNetworkOnlineRealtime());
            publishState(true);
            if (!enabled()) return;
            if (foreground()) wallpapers.refresh(true);
            refreshPremiumIfNeeded();
            if (polling) return;
            polling = true;
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override public void run() {
                    updateEvents();
                    if (!enabled()) { polling = false; return; }
                    publishState(false);
                    refreshPremiumIfNeeded();
                    wakeDifference(false);
                    if (foreground()) wallpapers.refresh(false);
                    AndroidUtilities.runOnUIThread(this, foreground() ? 3000 : 8000);
                }
            }, 3000);
        }));
    }

    public void send(TLObject request, RequestDelegate completion, RequestDelegateTimestamp timestamp,
                     QuickAckDelegate quickAck, int token) {
        if (expired) return;
        Job job = pending.computeIfAbsent(token, ignored -> new Job());
        final BotgramSession credentials = session;
        final String server = credentials.server;
        final String bearer = credentials.token;
        requests.execute(() -> {
            TLObject response = null; TLRPC.TL_error failure = null;
            try {
                if (job.cancelled || session != credentials || scope != job.owner) return;
                if (bearer == null) throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
                if (BotgramGiftPolicy.isDisabled() && BotgramGiftPolicy.isGiftRequest(request)) {
                    if (!(request instanceof TLRPC.TL_payments_assignPlayMarketTransaction)) {
                        AndroidUtilities.runOnUIThread(job.owner.bind(() -> {
                            if (!job.cancelled && session == credentials && scope == job.owner && !expired) {
                                BotgramGiftPolicy.notifyBlocked(account);
                            }
                        }));
                    }
                    throw new BotgramApi.ApiError(403, "NYARUGRAM_GIFTS_UNAVAILABLE");
                }
                if (sessionApi == null || apiSession != credentials) {
                    sessionApi = new BotgramApi(server, bearer);
                    apiSession = credentials;
                }
                BotgramApi api = sessionApi;
                if (request instanceof TLRPC.TL_auth_logOut) {
                    api.request("POST", "/api/auth/logout", null);
                    response = new TLRPC.TL_auth_loggedOut();
                    if (session == credentials) {
                        expired = true;
                        credentials.clear();
                        AndroidUtilities.runOnUIThread(() -> {
                            if (session == credentials && expired) events.stop();
                        });
                    }
                } else {
                    if (job.owner != scope || session != credentials) return;
                    String protocol = credentials.protocol();
                    if (protocol == null) {
                        protocol = api.negotiate();
                        if (job.cancelled || session != credentials || scope != job.owner) return;
                        credentials.pinProtocol(protocol);
                    }
                    if (job.cancelled || session != credentials || scope != job.owner) return;
                    if ("tl-json-v1".equals(protocol)) {
                        JSONObject result = api.object("POST", "/api/telegram/rpc", (JSONObject) encode(request, 0));
                        if (job.cancelled || session != credentials || scope != job.owner) return;
                        response = (TLObject) decode(result, TLObject.class, 0);
                    } else if ("protobuf-v1".equals(protocol)) {
                        BotgramProtobufIdentity identity = identities;
                        if (identity == null || identitySession != credentials) {
                            identity = new BotgramProtobufIdentity(api, credentials.userId,
                                    () -> session == credentials && scope == job.owner && !expired, premium);
                            if (session == credentials && scope == job.owner) {
                                identities = identity;
                                identitySession = credentials;
                            }
                        }
                        identity.initialize();
                        org.telegram.botgram.protocol.v1.BotgramRpc.RpcRequest typed =
                                new BotgramProtobufRequestMapper(identity).map(request, java.util.UUID.randomUUID().toString());
                        if (job.cancelled || session != credentials || scope != job.owner) return;
                        org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse result = api.rpc(typed);
                        if (job.cancelled || session != credentials || scope != job.owner) return;
                        response = new BotgramProtobufResponseMapper(identity).map(result);
                    } else throw new BotgramApi.ApiError(502, "BOTGRAM_NEGOTIATION_INVALID");
                }
                if (!job.cancelled && session == credentials) reachable(true);
            } catch (BotgramApi.ApiError error) {
                if (!job.cancelled && session == credentials) reachable(error.status != 495);
                failure = error(error.status, error.getMessage());
                if (error.status == 401 && !job.cancelled && session == credentials) expire(credentials);
            } catch (Exception error) {
                if (!job.cancelled && session == credentials) reachable(false);
                failure = error(500, "BOTGRAM_TRANSPORT_ERROR");
            }
            finally { finish(token, job, completion, timestamp, response, failure); }
        });
    }

    private void finish(int token, Job job, RequestDelegate completion, RequestDelegateTimestamp timestamp, TLObject response, TLRPC.TL_error failure) {
        Utilities.stageQueue.postRunnable(job.owner.bind(() -> {
            pending.remove(token, job);
            if (job.cancelled) return;
            if (completion != null) completion.run(response, failure);
            else if (timestamp != null) timestamp.run(response, failure, (int)(System.currentTimeMillis() / 1000));
            else if (response instanceof TLRPC.Updates) MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
        }));
    }
    private static TLRPC.TL_error error(int code, String text) {
        TLRPC.TL_error result = new TLRPC.TL_error(); result.code = code; result.text = text; return result;
    }
    public void cancel(int token) { pending.computeIfAbsent(token, ignored -> new Job()).cancelled = true; }
    public void bind(int token, int guid) { if (guid != 0) pending.computeIfAbsent(token, ignored -> new Job()).guid = guid; }
    public void cancelGuid(int guid) { for (Job job : pending.values()) if (job.guid == guid) job.cancelled = true; }

    public static Object encode(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("TL nesting limit");
        if (value == null) return JSONObject.NULL;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof byte[]) return new JSONObject().put("$bytes", Base64.encodeToString((byte[])value, Base64.NO_WRAP));
        if (value instanceof Iterable) {
            JSONArray array = new JSONArray(); for (Object item : (Iterable<?>)value) array.put(encode(item, depth + 1)); return array;
        }
        if (!(value instanceof TLObject)) return JSONObject.NULL;
        JSONObject result = new JSONObject().put("$type", value.getClass().getName());
        for (Field field : value.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object member = field.get(value);
            if (member != null) result.put(field.getName(), encode(member, depth + 1));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object decode(Object value, Class<?> expected, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("TL nesting limit");
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            if (object.has("$bytes")) return Base64.decode(object.getString("$bytes"), Base64.NO_WRAP);
            String name = object.getString("$type");
            if (!name.startsWith("org.telegram.tgnet.")) throw new IllegalArgumentException("Unknown TL namespace");
            Class<?> type = Class.forName(name);
            if (!TLObject.class.isAssignableFrom(type) || !expected.isAssignableFrom(type)) throw new IllegalArgumentException("Unexpected TL type");
            Object result = type == Vector.class ? new Vector<>(null) : type.getDeclaredConstructor().newInstance();
            for (Field field : type.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !object.has(field.getName()) || object.isNull(field.getName())) continue;
                Object item = object.get(field.getName());
                if (item instanceof JSONArray && List.class.isAssignableFrom(field.getType())) {
                    List items = (List) field.get(result);
                    if (items == null) { items = new ArrayList<>(); field.set(result, items); }
                    items.clear();
                    Class<?> element = Object.class;
                    if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType) {
                        java.lang.reflect.Type argument = ((java.lang.reflect.ParameterizedType)field.getGenericType()).getActualTypeArguments()[0];
                        if (argument instanceof Class) element = (Class<?>)argument;
                    }
                    JSONArray array = (JSONArray)item;
                    for (int i = 0; i < array.length(); i++) items.add(decode(array.get(i), element, depth + 1));
                } else field.set(result, decode(item, field.getType(), depth + 1));
            }
            return result;
        }
        if (value instanceof Number) {
            Number number = (Number)value;
            if (expected == int.class || expected == Integer.class) return Math.toIntExact(number.longValue());
            if (expected == long.class || expected == Long.class) return number.longValue();
            if (expected == double.class || expected == Double.class) return number.doubleValue();
        }
        return value;
    }
}
