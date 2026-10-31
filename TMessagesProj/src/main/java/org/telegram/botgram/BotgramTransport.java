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
    public static BotgramTransport get(int account) { return instances.computeIfAbsent(account, BotgramTransport::new); }
    private final int account;
    private final SharedPreferences binding;
    // Preserve the original stage queue's send order (in particular InvokeAfter sends).
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<Integer, Job> pending = new ConcurrentHashMap<>();
    private volatile BotgramSession session;
    private volatile long reachable;
    private boolean polling;
    private boolean expired;
    private static final class Job { volatile boolean cancelled; volatile int guid; }

    private BotgramTransport(int account) {
        this.account = account;
        binding = ApplicationLoader.applicationContext.getSharedPreferences("botgram.transport." + account, Context.MODE_PRIVATE);
        session = new BotgramSession(ApplicationLoader.applicationContext, account);
    }

    // A bound but expired account must never fall through to native Telegram transport.
    public boolean enabled() { return binding.getBoolean("enabled", false); }
    public void authenticated() {
        for (Job job : pending.values()) job.cancelled = true;
        session = new BotgramSession(ApplicationLoader.applicationContext, account);
        binding.edit().putBoolean("enabled", true).commit();
        expired = false; reachable = System.currentTimeMillis();
        start();
    }
    public int state() { return System.currentTimeMillis() - reachable < 30_000 ? ConnectionsManager.ConnectionStateConnected : ConnectionsManager.ConnectionStateConnecting; }
    private void reachable(boolean online) {
        int before = state();
        reachable = online ? System.currentTimeMillis() : 0;
        if (before != state()) AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didUpdateConnectionState));
    }

    public void start() {
        AndroidUtilities.runOnUIThread(() -> {
            if (polling || !enabled()) return;
            polling = true;
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override public void run() {
                    if (!enabled()) { polling = false; return; }
                    if (UserConfig.getInstance(account).isClientActivated() && !ApplicationLoader.mainInterfacePaused && !expired) {
                        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).getDifference());
                    }
                    AndroidUtilities.runOnUIThread(this, 3000);
                }
            }, 3000);
        });
    }

    public void send(TLObject request, RequestDelegate completion, RequestDelegateTimestamp timestamp,
                     QuickAckDelegate quickAck, int token) {
        Job job = pending.computeIfAbsent(token, ignored -> new Job());
        final JSONObject payload;
        try { payload = (JSONObject) encode(request, 0); }
        catch (Exception error) { finish(token, job, completion, timestamp, null, error(400, "BOTGRAM_ENCODE_FAILED")); return; }
        final BotgramSession credentials = session;
        requests.execute(() -> {
            TLObject response = null; TLRPC.TL_error failure = null;
            try {
                if (job.cancelled) return;
                if (credentials.token == null) throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
                BotgramApi api = new BotgramApi(credentials.server, credentials.token);
                if (request instanceof TLRPC.TL_auth_logOut) {
                    api.request("POST", "/api/auth/logout", null);
                    response = (TLObject) decode(new JSONObject().put("$type", "org.telegram.tgnet.TLRPC$TL_auth_loggedOut"), TLObject.class, 0);
                    if (session == credentials) credentials.clear();
                } else response = (TLObject) decode(api.object("POST", "/api/telegram/rpc", payload), TLObject.class, 0);
                reachable(true);
            } catch (BotgramApi.ApiError error) {
                reachable(true);
                failure = error(error.status, error.getMessage());
                if (error.status == 401 && session == credentials && !expired) {
                    expired = true;
                    AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).performLogout(0));
                }
            } catch (Exception error) { reachable(false); failure = error(500, "BOTGRAM_TRANSPORT_ERROR"); }
            finally { finish(token, job, completion, timestamp, response, failure); }
        });
    }

    private void finish(int token, Job job, RequestDelegate completion, RequestDelegateTimestamp timestamp, TLObject response, TLRPC.TL_error failure) {
        Utilities.stageQueue.postRunnable(() -> {
            pending.remove(token);
            if (job.cancelled) return;
            if (completion != null) completion.run(response, failure);
            else if (timestamp != null) timestamp.run(response, failure, (int)(System.currentTimeMillis() / 1000));
            else if (response instanceof TLRPC.Updates) MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
        });
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
