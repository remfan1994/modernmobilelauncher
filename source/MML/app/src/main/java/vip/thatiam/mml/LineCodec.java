package vip.thatiam.mml;

import java.util.ArrayList;
import java.util.List;

/** A small, deterministic, line-oriented codec for MML's private journal files. */
public final class LineCodec {
    private LineCodec() {
    }

    public static String encode(String... fields) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                result.append('\t');
            }
            appendEscaped(result, fields[i] == null ? "" : fields[i]);
        }
        return result.toString();
    }

    public static List<String> decode(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (escaped) {
                if (value == 'n') {
                    field.append('\n');
                } else if (value == 'r') {
                    field.append('\r');
                } else if (value == 't') {
                    field.append('\t');
                } else {
                    field.append(value);
                }
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '\t') {
                result.add(field.toString());
                field.setLength(0);
            } else {
                field.append(value);
            }
        }
        if (escaped) {
            field.append('\\');
        }
        result.add(field.toString());
        return result;
    }

    /** Counts encoded fields without allocating their decoded contents. */
    public static int encodedFieldCount(String line) {
        int count = 1;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '\t') {
                count++;
            }
        }
        return count;
    }

    /**
     * Decodes only the leading fields of a row.
     *
     * <p>This is used by compact indexes that need timestamps and types but must not allocate a
     * potentially large final text field. Callers can pair it with {@link #encodedFieldCount} when
     * exact row-shape validation is required.
     */
    public static List<String> decodePrefix(String line, int maximumFields) {
        if (maximumFields < 1) {
            throw new IllegalArgumentException("maximumFields must be positive");
        }
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (escaped) {
                if (value == 'n') {
                    field.append('\n');
                } else if (value == 'r') {
                    field.append('\r');
                } else if (value == 't') {
                    field.append('\t');
                } else {
                    field.append(value);
                }
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '\t') {
                result.add(field.toString());
                if (result.size() == maximumFields) {
                    return result;
                }
                field.setLength(0);
            } else {
                field.append(value);
            }
        }
        if (escaped) {
            field.append('\\');
        }
        result.add(field.toString());
        return result;
    }

    private static void appendEscaped(StringBuilder result, String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\') {
                result.append("\\\\");
            } else if (character == '\n') {
                result.append("\\n");
            } else if (character == '\r') {
                result.append("\\r");
            } else if (character == '\t') {
                result.append("\\t");
            } else {
                result.append(character);
            }
        }
    }
}
