package vip.thatiam.mml;

import java.nio.charset.StandardCharsets;

/** Shared hard bounds for user-authored journal and calendar text. */
public final class JournalTextPolicy {
    public static final int MAX_INPUT_UTF16_UNITS = 32_768;
    public static final int MAX_CODE_POINTS = 32_768;
    public static final int MAX_UTF8_BYTES = 128 * 1024;
    public static final int MAX_DAY_DRAFTS = 16;

    private JournalTextPolicy() {
    }

    public static String validationError(String value) {
        String text = value == null ? "" : value;
        if (text.codePointCount(0, text.length()) > MAX_CODE_POINTS) {
            return "Text is limited to " + MAX_CODE_POINTS + " characters.";
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return "Text is limited to " + MAX_UTF8_BYTES + " UTF-8 bytes.";
        }
        return null;
    }

    public static void requireWithinLimit(String value) throws java.io.IOException {
        String error = validationError(value);
        if (error != null) {
            throw new java.io.IOException(error);
        }
    }
}
