package vip.thatiam.mml;

import java.util.List;

public final class CapturedNotificationSelfTest {
    public static void main(String[] arguments) {
        String exactTitle = "Dentist\tappointment";
        String exactBody = "First line\nSecond\tline";
        CapturedNotification original = CapturedNotification.create(
                "stable|capture|42",
                1_788_229_200_000L,
                "America/Los_Angeles",
                "org.example.calendar",
                new StringBuilder("Example Calendar"),
                exactTitle,
                exactBody,
                "Highland Hospital");

        String encoded = original.encodeDetail();
        List<String> encodedFields = LineCodec.decode(encoded);
        check(encodedFields.size() == 9, "NC1 field count");
        check(encodedFields.get(0).equals("NC1"), "NC1 marker");
        check(encodedFields.get(1).equals("stable|capture|42"), "stable capture ID encoded");
        CapturedNotification decoded = CapturedNotification.decodeDetail(encoded);
        check(decoded != null, "encoded detail decodes");
        check(decoded.getCaptureId().equals(original.getCaptureId()), "capture ID round trip");
        check(decoded.getPostedEpochMillis() == original.getPostedEpochMillis(),
                "posted timestamp round trip");
        check(decoded.getCapturedZoneId().equals("America/Los_Angeles"),
                "capture timezone round trip");
        check(decoded.getSourcePackage().equals("org.example.calendar"),
                "source package round trip");
        check(decoded.getAppLabel().equals("Example Calendar"), "application label round trip");
        check(decoded.getTitle().equals(exactTitle), "tab retained exactly");
        check(decoded.getBody().equals(exactBody), "newline and tab retained exactly");
        check(decoded.getSubText().equals("Highland Hospital"), "subtext round trip");

        String knownCaptureId = CapturedNotification.sha256CaptureId(
                "org.example", "key|1", 123L, "Title", "Body", "Sub");
        check(knownCaptureId.equals(
                        "d35abd09972dea72daa39cc3035f84a25cfa33c6d4173e3503dfd2b0582fb43a"),
                "SHA-256 capture ID fixed vector");
        check(knownCaptureId.length() == CapturedNotification.CAPTURE_ID_HEX_LENGTH,
                "SHA-256 capture ID has fixed length");
        check(knownCaptureId.matches("[0-9a-f]{64}"),
                "SHA-256 capture ID is lowercase hexadecimal");
        check(knownCaptureId.equals(CapturedNotification.sha256CaptureId(
                        "org.example", "key|1", 123L, "Title", "Body", "Sub")),
                "SHA-256 capture ID is deterministic");
        check(!knownCaptureId.equals(CapturedNotification.sha256CaptureId(
                        "org.example", "key|2", 123L, "Title", "Body", "Sub")),
                "notification identity participates in capture ID");
        check(!knownCaptureId.equals(CapturedNotification.sha256CaptureId(
                        "org.example", "key|1", 124L, "Title", "Body", "Sub")),
                "posted time participates in capture ID");
        check(!knownCaptureId.equals(CapturedNotification.sha256CaptureId(
                        "org.example", "key|1", 123L, "Title", "Changed", "Sub")),
                "visible content participates in capture ID");
        check(!CapturedNotification.sha256CaptureId(
                        "a", "bc", 1L, "", "", "")
                        .equals(CapturedNotification.sha256CaptureId(
                                "ab", "c", 1L, "", "", "")),
                "capture ID fields have unambiguous framing");
        String longIdentityPrefix = "k".repeat(
                CapturedNotification.MAX_CAPTURE_ID_CODE_POINTS + 32);
        check(!CapturedNotification.sha256CaptureId(
                        "org.example", longIdentityPrefix + "A", 1L, "", "", "")
                        .equals(CapturedNotification.sha256CaptureId(
                                "org.example", longIdentityPrefix + "B", 1L, "", "", "")),
                "notification identity is hashed before model ID bounds apply");

        String compact = decoded.compactDisplay();
        check(compact.equals(
                        "Example Calendar - Dentist appointment - First line Second line"
                                + " - Highland Hospital"),
                "compact display is complete and one line");
        check(compact.indexOf('\n') < 0 && compact.indexOf('\t') < 0,
                "compact display contains no line controls");

        String full = decoded.fullDisplay();
        check(full.contains("Application: Example Calendar"), "full application label");
        check(full.contains("Package: org.example.calendar"), "full source package");
        check(full.contains("Captured zone: America/Los_Angeles"), "full captured timezone");
        check(full.contains("Title: " + exactTitle), "full title");
        check(full.contains("Body:\n" + exactBody), "full body preserves layout");
        check(full.contains("Subtext: Highland Hospital"), "full subtext");
        check(!full.contains("stable|capture|42"), "internal capture ID stays out of display");

        CapturedNotification sanitized = CapturedNotification.create(
                "id\u0000\u0001",
                0L,
                "UTC",
                "org.example\u0000.source",
                "A\u0000B\u0001C\u007fD",
                "T\r\nU\rV",
                "A\u0000B\u0001C\nD\tE\r\nF\rG\u007fH",
                "bad\ud800surrogate");
        check(sanitized.getCaptureId().equals("id"), "capture ID controls removed");
        check(sanitized.getSourcePackage().equals("org.example.source"),
                "package controls removed");
        check(sanitized.getAppLabel().equals("ABCD"), "label controls removed");
        check(sanitized.getTitle().equals("T\nU\nV"), "carriage returns normalized");
        check(sanitized.getBody().equals("ABC\nD\tE\nF\nGH"),
                "body controls removed while newline and tab survive");
        check(sanitized.getSubText().equals("bad\uFFFDsurrogate"),
                "unpaired surrogate replaced safely");

        String longEmoji = "\ud83d\ude42".repeat(CapturedNotification.MAX_TITLE_CODE_POINTS + 10);
        CapturedNotification bounded = CapturedNotification.create(
                "x".repeat(CapturedNotification.MAX_CAPTURE_ID_CODE_POINTS + 20),
                1L,
                "UTC",
                "p".repeat(CapturedNotification.MAX_SOURCE_PACKAGE_CODE_POINTS + 20),
                "a".repeat(CapturedNotification.MAX_APP_LABEL_CODE_POINTS + 20),
                longEmoji,
                "b".repeat(CapturedNotification.MAX_BODY_CODE_POINTS + 20),
                "s".repeat(CapturedNotification.MAX_SUB_TEXT_CODE_POINTS + 20));
        check(codePoints(bounded.getCaptureId())
                        == CapturedNotification.MAX_CAPTURE_ID_CODE_POINTS,
                "capture ID bounded");
        check(codePoints(bounded.getSourcePackage())
                        == CapturedNotification.MAX_SOURCE_PACKAGE_CODE_POINTS,
                "source package bounded");
        check(codePoints(bounded.getAppLabel())
                        == CapturedNotification.MAX_APP_LABEL_CODE_POINTS,
                "application label bounded");
        check(codePoints(bounded.getTitle()) == CapturedNotification.MAX_TITLE_CODE_POINTS,
                "title bounded by code point without splitting emoji");
        check(codePoints(bounded.getBody()) == CapturedNotification.MAX_BODY_CODE_POINTS,
                "body bounded");
        check(codePoints(bounded.getSubText()) == CapturedNotification.MAX_SUB_TEXT_CODE_POINTS,
                "subtext bounded");
        check(codePoints(bounded.compactDisplay())
                        <= CapturedNotification.MAX_COMPACT_CODE_POINTS,
                "compact display bounded");
        check(bounded.compactDisplay().endsWith("..."), "compact truncation visible");

        CharSequence hostile = new CharSequence() {
            @Override
            public int length() {
                return 1;
            }

            @Override
            public char charAt(int index) {
                return 'x';
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return "x";
            }

            @Override
            public String toString() {
                throw new IllegalStateException("hostile CharSequence");
            }
        };
        CapturedNotification hostileSafe = CapturedNotification.create(
                "safe-id", 2L, "UTC", "org.example", hostile, null, hostile, null);
        check(hostileSafe.getAppLabel().equals("x") && hostileSafe.getBody().equals("x"),
                "bounded CharSequence scan does not materialize hostile toString");

        CharSequence hostileCharacters = new CharSequence() {
            @Override
            public int length() {
                return 10;
            }

            @Override
            public char charAt(int index) {
                throw new IllegalStateException("hostile character access");
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                throw new IllegalStateException("hostile subsequence");
            }
        };
        CapturedNotification hostileCharactersSafe = CapturedNotification.create(
                "safe-id-characters",
                2L,
                "UTC",
                "org.example",
                hostileCharacters,
                null,
                hostileCharacters,
                null);
        check(hostileCharactersSafe.getAppLabel().isEmpty()
                        && hostileCharactersSafe.getBody().isEmpty(),
                "hostile character access becomes an empty bounded field");

        CharSequence nullString = new CharSequence() {
            @Override
            public int length() {
                return 0;
            }

            @Override
            public char charAt(int index) {
                throw new IndexOutOfBoundsException();
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return "";
            }

            @Override
            public String toString() {
                return null;
            }
        };
        CapturedNotification nullStringSafe = CapturedNotification.create(
                "safe-id-2", 3L, "UTC", "org.example", nullString, null, null, null);
        check(nullStringSafe.getAppLabel().isEmpty(),
                "null-returning CharSequence becomes empty");

        check(CapturedNotification.decodeDetail(null) == null, "null detail isolated");
        check(CapturedNotification.decodeDetail("") == null, "empty detail isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "OTHER", "id", "1", "UTC", "org.example", "", "", "", "")) == null,
                "foreign marker isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "NC1", "id", "not-a-number", "UTC", "org.example", "", "", "", ""))
                        == null,
                "malformed timestamp isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "NC1", "id", "-1", "UTC", "org.example", "", "", "", "")) == null,
                "negative timestamp isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "NC1", "", "1", "UTC", "org.example", "", "", "", "")) == null,
                "blank capture ID isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "NC1", "id", "1", "", "org.example", "", "", "", "")) == null,
                "blank capture timezone isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "NC1", "id", "1", "Not/A_Real_Zone", "org.example", "", "", "", ""))
                        == null,
                "invalid capture timezone isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "NC1", "id", "1", "UTC", "", "", "", "", "")) == null,
                "blank source package isolated");
        check(CapturedNotification.decodeDetail(LineCodec.encode(
                        "NC1", "id", "1", "UTC", "org.example")) == null,
                "short row isolated");
        check(CapturedNotification.decodeDetail(encoded) != null,
                "valid row still decodes after malformed rows");

        System.out.println("CapturedNotification self-test passed.");
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
