package org.telegram.botgram.e2ee;

/** Minimal URL-safe Base64 without padding; avoids API-level differences on old Android. */
final class BotgramE2eeBase64 {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private BotgramE2eeBase64() {}

    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 4 / 3 + 2);
        for (int i = 0; i < data.length; i += 3) {
            int a = data[i] & 0xff;
            int b = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int c = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            out.append(ALPHABET[a >> 2]);
            out.append(ALPHABET[((a & 3) << 4) | (b >> 4)]);
            if (i + 1 < data.length) out.append(ALPHABET[((b & 15) << 2) | (c >> 6)]);
            if (i + 2 < data.length) out.append(ALPHABET[c & 63]);
        }
        return out.toString();
    }

    static byte[] decode(String text) {
        int length = text.length();
        if (length == 0) return new byte[0];
        int padding = 0;
        if (length % 4 == 2) padding = 2;
        else if (length % 4 == 3) padding = 1;
        else if (length % 4 != 0) throw new IllegalArgumentException("base64 length");
        byte[] out = new byte[length / 4 * 3 + (padding == 2 ? 1 : padding == 1 ? 2 : 0)];
        int index = 0;
        int i = 0;
        while (i < length) {
            int remaining = length - i;
            int a = value(text.charAt(i));
            int b = remaining > 1 ? value(text.charAt(i + 1)) : 0;
            int c = remaining > 2 ? value(text.charAt(i + 2)) : 0;
            int d = remaining > 3 ? value(text.charAt(i + 3)) : 0;
            out[index++] = (byte) ((a << 2) | (b >> 4));
            if (remaining > 2) out[index++] = (byte) (((b & 15) << 4) | (c >> 2));
            if (remaining > 3) out[index++] = (byte) (((c & 3) << 6) | d);
            i += 4;
        }
        return out;
    }

    private static int value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '-') return 62;
        if (c == '_') return 63;
        throw new IllegalArgumentException("base64 character");
    }
}
