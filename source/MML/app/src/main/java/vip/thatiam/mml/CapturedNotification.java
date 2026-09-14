package vip.thatiam.mml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Pure, bounded snapshot of user-presentable notification text. */
public final class CapturedNotification {
    public static final String DETAIL_MARKER = "NC1";
    public static final int CAPTURE_ID_HEX_LENGTH = 64;
    public static final int MAX_CAPTURE_ID_CODE_POINTS = 512;
    public static final int MAX_ZONE_ID_CODE_POINTS = 128;
    public static final int MAX_SOURCE_PACKAGE_CODE_POINTS = 256;
    public static final int MAX_APP_LABEL_CODE_POINTS = 256;
    public static final int MAX_TITLE_CODE_POINTS = 1_024;
    public static final int MAX_BODY_CODE_POINTS = 8_192;
    public static final int MAX_SUB_TEXT_CODE_POINTS = 1_024;
    public static final int MAX_COMPACT_CODE_POINTS = 320;

    private static final DateTimeFormatter POSTED_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss xxx '['VV']'", Locale.ROOT);

    private final String captureId;
    private final long postedEpochMillis;
    private final String capturedZoneId;
    private final String sourcePackage;
    private final String appLabel;
    private final String title;
    private final String body;
    private final String subText;

    private CapturedNotification(
            String captureId,
            long postedEpochMillis,
            String capturedZoneId,
            String sourcePackage,
            CharSequence appLabel,
            CharSequence title,
            CharSequence body,
            CharSequence subText) {
        this.captureId = sanitize(captureId, MAX_CAPTURE_ID_CODE_POINTS);
        this.postedEpochMillis = postedEpochMillis;
        this.capturedZoneId = sanitize(capturedZoneId, MAX_ZONE_ID_CODE_POINTS);
        this.sourcePackage = sanitize(sourcePackage, MAX_SOURCE_PACKAGE_CODE_POINTS);
        this.appLabel = sanitize(appLabel, MAX_APP_LABEL_CODE_POINTS);
        this.title = sanitize(title, MAX_TITLE_CODE_POINTS);
        this.body = sanitize(body, MAX_BODY_CODE_POINTS);
        this.subText = sanitize(subText, MAX_SUB_TEXT_CODE_POINTS);
        validateIdentity();
    }

    public static CapturedNotification create(
            String captureId,
            long postedEpochMillis,
            String capturedZoneId,
            String sourcePackage,
            CharSequence appLabel,
            CharSequence title,
            CharSequence body,
            CharSequence subText) {
        return new CapturedNotification(
                captureId,
                postedEpochMillis,
                capturedZoneId,
                sourcePackage,
                appLabel,
                title,
                body,
                subText);
    }

    /**
     * Derives a fixed-size ID from the notification identity and the text MML will retain.
     * App labels and capture zones are intentionally excluded because they may change with locale
     * or device settings without the source notification changing.
     */
    public static String sha256CaptureId(
            String sourcePackage,
            String notificationIdentity,
            long postedEpochMillis,
            String title,
            String body,
            String subText) {
        String canonical = LineCodec.encode(
                "CID1",
                sourcePackage,
                notificationIdentity,
                Long.toString(postedEpochMillis),
                title,
                body,
                subText);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
        StringBuilder result = new StringBuilder(CAPTURE_ID_HEX_LENGTH);
        for (byte value : digest) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    public String getCaptureId() {
        return captureId;
    }

    public long getPostedEpochMillis() {
        return postedEpochMillis;
    }

    public String getCapturedZoneId() {
        return capturedZoneId;
    }

    public String getSourcePackage() {
        return sourcePackage;
    }

    public String getAppLabel() {
        return appLabel;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getSubText() {
        return subText;
    }

    /** Encodes one journal-event detail without relying on Android framework classes. */
    public String encodeDetail() {
        return LineCodec.encode(
                DETAIL_MARKER,
                captureId,
                Long.toString(postedEpochMillis),
                capturedZoneId,
                sourcePackage,
                appLabel,
                title,
                body,
                subText);
    }

    /** Returns null for a damaged or foreign detail so one bad row cannot hide later rows. */
    public static CapturedNotification decodeDetail(String detail) {
        if (detail == null) {
            return null;
        }
        try {
            List<String> fields = LineCodec.decode(detail);
            if (fields.size() != 9 || !DETAIL_MARKER.equals(fields.get(0))) {
                return null;
            }
            long posted = Long.parseLong(fields.get(2));
            return create(
                    fields.get(1),
                    posted,
                    fields.get(3),
                    fields.get(4),
                    fields.get(5),
                    fields.get(6),
                    fields.get(7),
                    fields.get(8));
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** One bounded line suitable for MML's compact current-session log. */
    public String compactDisplay() {
        String source = appLabel.isBlank() ? sourcePackage : appLabel;
        StringBuilder result = new StringBuilder(source);
        appendCompactPart(result, title);
        appendCompactPart(result, body);
        appendCompactPart(result, subText);
        return truncate(oneLine(result), MAX_COMPACT_CODE_POINTS, true);
    }

    /** Complete readable content and capture context for an archived session log. */
    public String fullDisplay() {
        StringBuilder result = new StringBuilder();
        result.append("Application: ")
                .append(appLabel.isBlank() ? sourcePackage : appLabel)
                .append('\n')
                .append("Package: ").append(sourcePackage).append('\n')
                .append("Posted: ").append(postedTime()).append('\n')
                .append("Captured zone: ").append(capturedZoneId).append('\n');
        boolean hasContent = false;
        if (!title.isBlank()) {
            result.append("Title: ").append(title).append('\n');
            hasContent = true;
        }
        if (!body.isBlank()) {
            result.append("Body:").append('\n').append(body).append('\n');
            hasContent = true;
        }
        if (!subText.isBlank()) {
            result.append("Subtext: ").append(subText).append('\n');
            hasContent = true;
        }
        if (!hasContent) {
            result.append("Content: (no text)").append('\n');
        }
        return result.toString().stripTrailing();
    }

    private void validateIdentity() {
        if (captureId.isBlank()) {
            throw new IllegalArgumentException("Capture ID is required.");
        }
        if (postedEpochMillis < 0L) {
            throw new IllegalArgumentException("Posted timestamp cannot be negative.");
        }
        if (capturedZoneId.isBlank()) {
            throw new IllegalArgumentException("Captured timezone is required.");
        }
        try {
            ZoneId.of(capturedZoneId);
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("Captured timezone is invalid.", error);
        }
        if (sourcePackage.isBlank()) {
            throw new IllegalArgumentException("Source package is required.");
        }
    }

    private String postedTime() {
        try {
            return POSTED_TIME_FORMAT.format(
                    Instant.ofEpochMilli(postedEpochMillis).atZone(ZoneId.of(capturedZoneId)));
        } catch (DateTimeException error) {
            return Long.toString(postedEpochMillis) + " ms [" + capturedZoneId + "]";
        }
    }

    private static void appendCompactPart(StringBuilder destination, String value) {
        String part = oneLine(value);
        if (!part.isEmpty()) {
            destination.append(" - ").append(part);
        }
    }

    private static String oneLine(CharSequence value) {
        StringBuilder result = new StringBuilder();
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = Character.codePointAt(value, offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
            } else {
                if (pendingSpace) {
                    result.append(' ');
                    pendingSpace = false;
                }
                result.appendCodePoint(codePoint);
            }
        }
        return result.toString();
    }

    static String sanitize(CharSequence source, int maximumCodePoints) {
        if (source == null) {
            return "";
        }
        int sourceLength;
        try {
            sourceLength = source.length();
        } catch (RuntimeException error) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(sourceLength, maximumCodePoints));
        int retained = 0;
        try {
            for (int offset = 0; offset < sourceLength && retained < maximumCodePoints;) {
                char first = source.charAt(offset);
                int codePoint;
                if (first == '\r') {
                    codePoint = '\n';
                    offset++;
                    if (offset < sourceLength && source.charAt(offset) == '\n') {
                        offset++;
                    }
                } else if (Character.isHighSurrogate(first)) {
                    if (offset + 1 < sourceLength
                            && Character.isLowSurrogate(source.charAt(offset + 1))) {
                        codePoint = Character.toCodePoint(first, source.charAt(offset + 1));
                        offset += 2;
                    } else {
                        codePoint = 0xFFFD;
                        offset++;
                    }
                } else if (Character.isLowSurrogate(first)) {
                    codePoint = 0xFFFD;
                    offset++;
                } else {
                    codePoint = first;
                    offset++;
                }

                if (codePoint != '\n' && codePoint != '\t' && Character.isISOControl(codePoint)) {
                    continue;
                }
                result.appendCodePoint(codePoint);
                retained++;
            }
        } catch (RuntimeException error) {
            // Retain the already bounded prefix from a hostile/mutable CharSequence implementation.
        }
        return result.toString();
    }

    private static String truncate(String value, int maximumCodePoints, boolean addEllipsis) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) {
            return value;
        }
        int suffixLength = addEllipsis && maximumCodePoints >= 3 ? 3 : 0;
        int retained = Math.max(0, maximumCodePoints - suffixLength);
        int end = value.offsetByCodePoints(0, retained);
        return value.substring(0, end) + (suffixLength == 0 ? "" : "...");
    }
}
