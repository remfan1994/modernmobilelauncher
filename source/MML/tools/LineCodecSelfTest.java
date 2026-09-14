package vip.thatiam.mml;

import java.util.List;

public final class LineCodecSelfTest {
    public static void main(String[] arguments) {
        String[] values = {"plain", "tab\tvalue", "line\nvalue", "slash\\value", ""};
        String encoded = LineCodec.encode(values);
        List<String> decoded = LineCodec.decode(encoded);
        check(decoded.size() == values.length, "field count");
        for (int i = 0; i < values.length; i++) {
            check(values[i].equals(decoded.get(i)), "round trip field " + i);
        }
        check("trailing\\".equals(LineCodec.decode("trailing\\").get(0)), "trailing slash");
        check(LineCodec.encodedFieldCount(encoded) == values.length,
                "field count ignores escaped tab and newline text");
        List<String> prefix = LineCodec.decodePrefix(encoded, 4);
        check(prefix.size() == 4, "prefix field count");
        for (int i = 0; i < prefix.size(); i++) {
            check(values[i].equals(prefix.get(i)), "prefix field " + i);
        }
        String largeFinalField = LineCodec.encode("E", "1000", "UTC", "comment", "x".repeat(100_000));
        check(LineCodec.decodePrefix(largeFinalField, 4).equals(
                        List.of("E", "1000", "UTC", "comment")),
                "prefix skips large final field");
        boolean rejectedNonpositiveLimit = false;
        try {
            LineCodec.decodePrefix(encoded, 0);
        } catch (IllegalArgumentException expected) {
            rejectedNonpositiveLimit = true;
        }
        check(rejectedNonpositiveLimit, "prefix requires positive limit");
        System.out.println("LineCodec self-test passed.");
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
