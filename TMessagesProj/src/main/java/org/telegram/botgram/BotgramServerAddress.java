package org.telegram.botgram;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Endpoint parsing shared by the credentials form and saved-session migration. */
public final class BotgramServerAddress {
    public static final int DEFAULT_PORT = 8521;

    public final String host;
    public final String port;

    private BotgramServerAddress(String host, String port) {
        this.host = host;
        this.port = port;
    }

    public static BotgramServerAddress parse(String input) {
        URI uri = endpoint(input);
        return new BotgramServerAddress(origin(uri, -1),
                uri.getPort() == -1 ? "" : Integer.toString(uri.getPort()));
    }

    public static String join(String host, String port) {
        URI uri = endpoint(host);
        String enteredPort = port == null ? "" : port.trim();
        if (enteredPort.isEmpty()) return origin(uri, uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort());
        if (!enteredPort.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid port");
        int value = Integer.parseInt(enteredPort);
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Invalid port");
        return origin(uri, value);
    }

    public static String migrateLegacy(String input) {
        try {
            URI uri = endpoint(input);
            // Only the previous Botgram port changes; explicit proxy/custom ports remain intact.
            if (uri.getPort() == 5080) return origin(uri, DEFAULT_PORT);
        } catch (IllegalArgumentException ignored) { }
        return input;
    }

    /** Literal RFC 1918 IPv4 only; never resolves a hostname to decide whether to send credentials. */
    public static boolean isPrivateHttp(String input) {
        try {
            URI uri = endpoint(input);
            if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
            String[] parts = uri.getHost().split("\\.", -1);
            if (parts.length != 4) return false;
            int[] address = new int[4];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
                address[i] = Integer.parseInt(parts[i]);
                if (address[i] > 255) return false;
            }
            return address[0] == 10
                    || address[0] == 172 && address[1] >= 16 && address[1] <= 31
                    || address[0] == 192 && address[1] == 168;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    public static String eventEndpoint(String input, long cursor) {
        URI uri = endpoint(input);
        if (cursor < 0) throw new IllegalArgumentException("Invalid event cursor");
        String scheme;
        if ("https".equalsIgnoreCase(uri.getScheme())) scheme = "wss";
        else if (isPrivateHttp(input)) scheme = "ws";
        else throw new IllegalArgumentException("Events require HTTPS or a private IPv4 HTTP address");
        try {
            return new URI(scheme, null, uri.getHost(), uri.getPort(), "/api/ws", "after=" + cursor, null).toString();
        } catch (URISyntaxException error) { throw new IllegalArgumentException(error); }
    }

    private static URI endpoint(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("Missing host");
        String value = input.trim();
        if (!value.contains("://")) value = "https://" + value;
        URI uri = URI.create(value);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid server address");
        }
        return uri;
    }

    private static String origin(URI uri, int port) {
        try {
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), null, uri.getHost(), port, null, null, null).toString();
        } catch (URISyntaxException error) { throw new IllegalArgumentException(error); }
    }
}
