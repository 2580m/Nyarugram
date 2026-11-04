import org.telegram.botgram.BotgramServerAddress;

public final class BotgramServerAddressSmoke {
    private static int checks;
    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    private static void invalid(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid endpoint accepted");
    }
    public static void main(String[] args) {
        equal("", BotgramServerAddress.parse("example.com").port);
        equal("https://example.com", BotgramServerAddress.parse("example.com").host);
        equal("", BotgramServerAddress.parse("https://example.com").port);
        equal("https://example.com:8521", BotgramServerAddress.join(BotgramServerAddress.parse("https://example.com").host, BotgramServerAddress.parse("https://example.com").port));
        equal("https://example.com", BotgramServerAddress.parse("https://example.com:443/").host);
        equal("443", BotgramServerAddress.parse("https://example.com:443/").port);
        equal("http://192.168.2.200:8521", BotgramServerAddress.join("http://192.168.2.200", "8521"));
        equal("https://example.com:443", BotgramServerAddress.join("https://example.com", "443"));
        equal("http://[::1]", BotgramServerAddress.parse("http://[::1]:8521").host);
        equal("http://[::1]:8521", BotgramServerAddress.join("http://[::1]", "8521"));
        equal("https://example.com:65535", BotgramServerAddress.join("example.com", "65535"));
        equal("https://example.com:1", BotgramServerAddress.join("example.com", "1"));
        equal("https://example.com:8521", BotgramServerAddress.join("example.com", ""));
        equal("https://example.com:8521", BotgramServerAddress.join("example.com", null));
        equal("https://example.com:8521", BotgramServerAddress.join("example.com", "  "));
        equal("http://example.com:8521", BotgramServerAddress.join("http://example.com", ""));
        equal("https://example.com:8443", BotgramServerAddress.join("https://example.com:8443", ""));
        equal("http://[::1]:18524", BotgramServerAddress.join("http://[::1]:18524", ""));
        equal("https://example.com:8443", BotgramServerAddress.join("https://example.com:443", "8443"));
        for (String endpoint : new String[]{"", "ftp://example.com", "http://u:p@example.com", "https://example.com/path",
                "http://example.com?token=x", "http://example.com#fragment", "http://example.com:0", "http://example.com:65536"}) {
            invalid(() -> BotgramServerAddress.parse(endpoint));
        }
        for (String port : new String[]{"0", "65536", "-1", "abc", "8521/path", "999999999999"}) {
            invalid(() -> BotgramServerAddress.join("localhost", port));
        }
        equal("http://10.0.2.2:8521", BotgramServerAddress.migrateLegacy("http://10.0.2.2:5080"));
        equal("http://192.168.2.200:8521", BotgramServerAddress.migrateLegacy("http://192.168.2.200:5080"));
        equal("https://example.com:443", BotgramServerAddress.migrateLegacy("https://example.com:443"));
        equal("http://example.com:8443", BotgramServerAddress.migrateLegacy("http://example.com:8443"));
        equal("https://example.com", BotgramServerAddress.migrateLegacy("https://example.com"));
        equal("http://[::1]:8521", BotgramServerAddress.migrateLegacy("http://[::1]:5080"));
        System.out.println("PASS: " + checks + " endpoint, port, IPv6, validation and migration checks");
    }
}
