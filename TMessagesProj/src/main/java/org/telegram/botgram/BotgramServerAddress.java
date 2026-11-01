package org.telegram.botgram;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Endpoint parsing shared by the credentials form and saved-session migration. */
public final class BotgramServerAddress {
    public static final int DEFAULT_PORT = 8521;
    public static final String DEFAULT_HOST = "https://nyarugram.moe.autos";
    public static final String DEFAULT_ADDRESS = DEFAULT_HOST + ":" + DEFAULT_PORT;

    public final String host;
    public final String port;

    private BotgramServerAddress(String host, String port) {
        this.host = host;
        this.port = port;
    }

    public static BotgramServerAddress parse(String input) {
        URI uri = endpoint(input);
        return new BotgramServerAddress(origin(uri, -1),
                Integer.toString(uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort()));
    }

    public static String join(String host, String port) {
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid port");
        int value = Integer.parseInt(port);
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Invalid port");
        return origin(endpoint(host), value);
    }

    public static String migrateLegacy(String input) {
        try {
            URI uri = endpoint(input);
            // Only the previous Botgram port changes; explicit proxy/custom ports remain intact.
            if (uri.getPort() == 5080) return origin(uri, DEFAULT_PORT);
        } catch (IllegalArgumentException ignored) { }
        return input;
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
