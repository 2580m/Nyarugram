package org.telegram.botgram;

import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

/** Foreground-only event hints. The event cursor is never a Telegram pts. */
final class BotgramEventSocket {
    interface Listener {
        void wake(BotgramSession credentials);
        void unauthorized(BotgramSession credentials);
    }

    private static final ExecutorService network = Executors.newCachedThreadPool();
    private final Listener listener;
    // State belongs to the UI thread; generation also guards work queued on the stage thread.
    private volatile long generation;
    private BotgramSession credentials;
    private String server;
    private String token;
    private long cursor;
    private boolean active;
    private boolean rejected;
    private WebSocket socket;
    private Runnable retry;
    private int backoff = 1000;

    BotgramEventSocket(Listener listener) { this.listener = listener; }

    void update(BotgramSession next, boolean foreground) {
        boolean changed = credentials != next || !java.util.Objects.equals(server, next.server)
                || !java.util.Objects.equals(token, next.token);
        if (changed) {
            stop();
            credentials = next;
            server = next.server;
            token = next.token;
            cursor = 0;
            rejected = false;
            backoff = 1000;
        }
        // An HTTP binding may still use polling, but must never send Bearer over ws://.
        boolean wanted = foreground && token != null && server != null && server.startsWith("https://") && !rejected;
        if (!wanted) { if (active) stop(); return; }
        if (!active) {
            active = true;
            connect();
        }
    }

    void stop() {
        active = false;
        generation++;
        if (retry != null) AndroidUtilities.cancelRunOnUIThread(retry);
        retry = null;
        WebSocket previous = socket;
        socket = null;
        if (previous != null) close(previous);
    }

    private static void close(WebSocket ws) {
        network.execute(() -> {
            ws.disconnect();
            try { if (ws.getSocket() != null) ws.getSocket().close(); }
            catch (java.io.IOException ignored) { }
        });
    }

    private boolean current(long attempt) { return active && generation == attempt; }

    private void connect() {
        final long attempt = ++generation;
        final String origin = server;
        final String bearer = token;
        final long after = cursor;
        network.execute(() -> {
            try {
                // Validate the origin exactly as RPC does. No user info, query, or redirect target is accepted.
                String base = new BotgramApi(origin, bearer).baseUrl;
                SSLContext tls = SSLContext.getInstance("TLS");
                tls.init(null, null, null); // System trust managers only.
                WebSocket ws = new WebSocketFactory().setConnectionTimeout(10_000).setSocketTimeout(20_000)
                        .setSSLSocketFactory(tls.getSocketFactory()).setVerifyHostname(true)
                        .createSocket("wss://" + base.substring("https://".length()) + "/api/ws?after=" + after);
                ws.addHeader("Authorization", "Bearer " + bearer);
                ws.setMaxPayloadSize(8 * 1024 * 1024);
                ws.setPingInterval(15_000);
                ws.addListener(new WebSocketAdapter() {
                    @Override public void onConnected(WebSocket connected, Map<String, List<String>> headers) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!current(attempt)) { close(connected); return; }
                            backoff = 1000;
                            listener.wake(credentials);
                        });
                    }
                    @Override public void onTextMessage(WebSocket connected, String message) {
                        try {
                            JSONObject batch = new JSONObject(message);
                            long nextCursor = batch.getLong("cursor");
                            if (nextCursor < 0 || batch.optJSONArray("events") == null) return;
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!current(attempt)) return;
                                cursor = Math.max(cursor, nextCursor);
                                listener.wake(credentials);
                            });
                        } catch (Exception ignored) { /* Polling remains authoritative. */ }
                    }
                    @Override public void onDisconnected(WebSocket disconnected, WebSocketFrame serverClose,
                                                         WebSocketFrame clientClose, boolean byServer) {
                        AndroidUtilities.runOnUIThread(() -> failed(attempt, null));
                    }
                });
                AndroidUtilities.runOnUIThread(() -> {
                    if (!current(attempt)) { close(ws); return; }
                    socket = ws;
                    network.execute(() -> {
                        try {
                            if (generation != attempt) { close(ws); return; }
                            // nv 2.14 rejects non-101 handshakes, including redirects; never follow Location.
                            ws.connect();
                        } catch (Exception error) {
                            close(ws);
                            AndroidUtilities.runOnUIThread(() -> failed(attempt, error));
                        }
                    });
                });
            } catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> failed(attempt, error));
            }
        });
    }

    private void failed(long attempt, Exception error) {
        if (!current(attempt)) return;
        if (error instanceof OpeningHandshakeException
                && ((OpeningHandshakeException) error).getStatusLine().getStatusCode() == 401) {
            rejected = true;
            stop();
            listener.unauthorized(credentials);
            return;
        }
        generation++;
        WebSocket previous = socket;
        socket = null;
        if (previous != null) close(previous);
        retry = () -> {
            retry = null;
            if (active) connect();
        };
        AndroidUtilities.runOnUIThread(retry, backoff);
        backoff = Math.min(30_000, backoff * 2);
    }

    long generation() { return generation; }
}
