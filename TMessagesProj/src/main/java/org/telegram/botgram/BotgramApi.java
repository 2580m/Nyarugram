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
    private String selectedProtocol;

    /** Resolve before any RPC, then pin for this API's bearer session. No error-triggered fallback. */
    public synchronized String negotiate() throws Exception {
        if (selectedProtocol != null) return selectedProtocol;
        JSONObject value = object("GET", "/api/protocol/capabilities", null);
        String selected = value.optString("selectedProtocol", "");
        JSONArray protocols = value.optJSONArray("protocols");
        JSONArray capabilities = value.optJSONArray("capabilities");
        boolean advertised = false, idempotent = false;
        if (protocols != null) for (int i = 0; i < protocols.length(); i++) {
            if (selected.equals(protocols.optString(i))) advertised = true;
        }
        if (capabilities != null) for (int i = 0; i < capabilities.length(); i++) {
            if ("send-random-id-idempotency-v1".equals(capabilities.optString(i))) idempotent = true;
        }
        if (value.optInt("negotiationVersion", 0) != 1 || !advertised || !idempotent
                || !("protobuf-v1".equals(selected) || "tl-json-v1".equals(selected))) {
            throw new ApiError(502, "BOTGRAM_NEGOTIATION_INVALID");
        }
        selectedProtocol = selected;
        return selected;
    }

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

    public org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse rpc(
            org.telegram.botgram.protocol.v1.BotgramRpc.RpcRequest request) throws Exception {
        byte[] bytes = request.toByteArray();
        if (bytes.length > 128 * 1024) throw new ApiError(413, "BOTGRAM_REQUEST_TOO_LARGE");
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/api/v1/rpc").openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/x-protobuf");
        connection.setRequestProperty("Content-Type", "application/x-protobuf");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String type = connection.getContentType();
            if (type == null || !"application/x-protobuf".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                throw new ApiError(status >= 400 ? status : 502, "BOTGRAM_PROTOBUF_MEDIA_TYPE_INVALID");
            }
            if (stream == null) throw new ApiError(status >= 400 ? status : 502, "BOTGRAM_PROTOBUF_RESPONSE_MISSING");
            byte[] responseBytes;
            try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (buffer.size() + count > 8 * 1024 * 1024) throw new ApiError(502, "BOTGRAM_RESPONSE_TOO_LARGE");
                    buffer.write(chunk, 0, count);
                }
                responseBytes = buffer.toByteArray();
            }
            com.google.protobuf.CodedInputStream input = com.google.protobuf.CodedInputStream.newInstance(responseBytes);
            input.setRecursionLimit(32);
            org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse response =
                    org.telegram.botgram.protocol.v1.BotgramRpc.RpcResponse.parseFrom(input);
            if (response.getProtocolMajor() != 1 || (!response.getRequestId().equals(request.getRequestId())
                    && !(status >= 400 && response.getRequestId().isEmpty()))) {
                throw new ApiError(502, "BOTGRAM_PROTOBUF_ENVELOPE_INVALID");
            }
            if (response.hasError()) {
                if (status < 400 || response.getError().getHttpStatus() != status) {
                    throw new ApiError(502, "BOTGRAM_PROTOBUF_STATUS_INVALID");
                }
                throw new ApiError(status, response.getError().getCode());
            }
            if (status < 200 || status >= 300) throw new ApiError(status, "BOTGRAM_PROTOBUF_HTTP_ERROR");
            if (response.getOutcomeCase().getNumber() != request.getOperationCase().getNumber()) {
                throw new ApiError(502, "BOTGRAM_PROTOBUF_OUTCOME_INVALID");
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
