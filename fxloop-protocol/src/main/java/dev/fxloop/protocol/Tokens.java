package dev.fxloop.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Token generation, constant-time comparison and log redaction. */
public final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    /** Random 128-bit token, hex encoded. */
    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /** Replaces any occurrence of {@code token} in {@code text} so it never reaches a log. */
    public static String redact(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return text;
        }
        return text.replace(token, "<redacted>");
    }
}
