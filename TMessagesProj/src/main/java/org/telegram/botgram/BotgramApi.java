package org.telegram.botgram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Only used from background threads. Never forwards credentials to another origin. */
public final class BotgramApi {
    public static final class ApiError extends IOException {
        public final int status;
        ApiError(int status, String message) { super(message); this.status = status; }
    }

    public final String baseUrl;
    private final String token;

    public BotgramApi(String baseUrl, String token) {
        URI uri = URI.create(baseUrl.trim());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("服务器地址必须是 http(s)://主机[:端口]，不含路径或凭据");
        }
        if (!"https".equals(uri.getScheme()) && !org.telegram.messenger.BuildVars.DEBUG_VERSION) {
            throw new IllegalArgumentException("BOTGRAM_HTTPS_REQUIRED");
        }
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.token = token;
    }

    public JSONObject object(String method, String path, JSONObject body) throws Exception {
        String value = request(method, path, body);
        return value.isEmpty() ? new JSONObject() : new JSONObject(value);
    }

    public JSONArray array(String path) throws Exception { return new JSONArray(request("GET", path, null)); }

    public String request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = "";
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (buffer.size() + count > 8 * 1024 * 1024) throw new IOException("响应过大");
                        buffer.write(chunk, 0, count);
                    }
                    response = buffer.toString("UTF-8");
                }
            }
            if (status < 200 || status >= 300) {
                String message = "HTTP " + status;
                try { message = new JSONObject(response).optString("error", message); } catch (Exception ignored) { }
                throw new ApiError(status, message);
            }
            return response;
        } catch (javax.net.ssl.SSLPeerUnverifiedException error) {
            throw new ApiError(495, "BOTGRAM_TLS_PEER_UNVERIFIED");
        } catch (javax.net.ssl.SSLHandshakeException error) {
            throw new ApiError(495, "BOTGRAM_TLS_HANDSHAKE_FAILED");
        } finally { connection.disconnect(); }
    }

    public String token() { return token; }

    public static JSONObject json(Object... fields) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
}
