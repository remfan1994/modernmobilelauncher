package vip.thatiam.mml;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure rules for chronological future-event lists and their quiet notification summary. */
public final class FutureEntryPolicy {
    private static final DateTimeFormatter SHORT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("M/d", Locale.ROOT);
    private static final DateTimeFormatter SHORT_DATE_WITH_YEAR_FORMAT =
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.ROOT);
    private static final DateTimeFormatter LONG_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH);

    private FutureEntryPolicy() {
    }

    /** Counts nonempty date-Comments documents whose intended civil date is after today. */
    public static int countFutureEntries(List<CalendarComment> comments, long nowEpochMillis) {
        return futureEntries(comments, nowEpochMillis).size();
    }

    /** Returns valid future entries in civil-date order, soonest first. */
    public static List<CalendarComment> futureEntries(
            List<CalendarComment> comments,
            long nowEpochMillis) {
        List<CalendarComment> result = new ArrayList<>();
        if (comments == null) {
            return Collections.unmodifiableList(result);
        }
        for (CalendarComment comment : comments) {
            if (isFutureEntry(comment, nowEpochMillis)) {
                result.add(comment);
            }
        }
        result.sort(Comparator
                .comparing(CalendarComment::getTargetLocalDate)
                .thenComparing(CalendarComment::getTargetZoneId));
        return Collections.unmodifiableList(result);
    }

    public static boolean isFutureEntry(CalendarComment comment, long nowEpochMillis) {
        if (comment == null || !CommentText.hasPayload(comment.getText())) {
            return false;
        }
        try {
            LocalDate target = LocalDate.parse(comment.getTargetLocalDate());
            LocalDate todayAtTarget = Instant.ofEpochMilli(nowEpochMillis)
                    .atZone(ZoneId.of(comment.getTargetZoneId()))
                    .toLocalDate();
            return target.isAfter(todayAtTarget);
        } catch (DateTimeException ignored) {
            // One malformed imported record must not hide the remaining future entries.
            return false;
        }
    }

    public static String notificationHeading() {
        return "Future events...";
    }

    public static String notificationTitle(int count) {
        return count + (count == 1 ? " future event" : " future events");
    }

    /**
     * Builds one notification line per retained event. Each line is deliberately single-line so
     * Android's expanded notification remains a readable chronological list.
     */
    public static String notificationBody(
            List<CalendarComment> retainedEntries,
            int totalCount,
            long nowEpochMillis,
            int maximumBodyCodePoints) {
        if (totalCount <= 0) {
            return "";
        }
        int bodyLimit = Math.max(1, maximumBodyCodePoints);
        StringBuilder body = new StringBuilder();
        int shown = 0;
        if (retainedEntries != null) {
            for (CalendarComment comment : retainedEntries) {
                if (comment == null || shown >= totalCount) {
                    continue;
                }
                String eventText = compactEventText(comment.getText(), 120);
                if (eventText.isEmpty()) {
                    continue;
                }
                String line = notificationDateLabel(comment, nowEpochMillis)
                        + ". " + eventText;
                int separatorCodePoints = body.length() == 0 ? 0 : 1;
                int projectedCodePoints = body.codePointCount(0, body.length())
                        + separatorCodePoints
                        + line.codePointCount(0, line.length());
                if (projectedCodePoints > bodyLimit) {
                    break;
                }
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(line);
                shown++;
            }
        }

        int omitted = Math.max(0, totalCount - shown);
        if (omitted > 0) {
            String omittedLine = "... " + omitted
                    + (omitted == 1 ? " more future event" : " more future events");
            int separatorCodePoints = body.length() == 0 ? 0 : 1;
            int projectedCodePoints = body.codePointCount(0, body.length())
                    + separatorCodePoints
                    + omittedLine.codePointCount(0, omittedLine.length());
            if (projectedCodePoints <= bodyLimit) {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(omittedLine);
            }
        }
        return body.length() == 0 ? notificationTitle(totalCount) : body.toString();
    }

    public static String notificationDateLabel(
            CalendarComment comment,
            long nowEpochMillis) {
        if (comment == null) {
            return "Unknown date";
        }
        try {
            LocalDate target = LocalDate.parse(comment.getTargetLocalDate());
            int currentYearAtTarget = Instant.ofEpochMilli(nowEpochMillis)
                    .atZone(ZoneId.of(comment.getTargetZoneId()))
                    .getYear();
            return target.getYear() == currentYearAtTarget
                    ? SHORT_DATE_FORMAT.format(target)
                    : SHORT_DATE_WITH_YEAR_FORMAT.format(target);
        } catch (DateTimeException ignored) {
            return comment.getTargetLocalDate();
        }
    }

    public static String longDateLabel(CalendarComment comment) {
        if (comment == null) {
            return "Unknown date";
        }
        try {
            return LONG_DATE_FORMAT.format(LocalDate.parse(comment.getTargetLocalDate()));
        } catch (DateTimeException ignored) {
            return comment.getTargetLocalDate();
        }
    }

    /** Full-text list label used by the in-app Future events screen. */
    public static String listLabel(CalendarComment comment) {
        if (comment == null) {
            return "Unknown future event";
        }
        String text = normalizeLineEndings(comment.getText()).strip();
        return text.isEmpty() ? longDateLabel(comment) : longDateLabel(comment) + "\n" + text;
    }

    /**
     * Converts a potentially multiline Comment to one bounded readable line. Optional leading
     * code-comment markers are presentation syntax and are omitted from the summary.
     */
    public static String compactEventText(String text, int maximumCodePoints) {
        if (maximumCodePoints <= 0) {
            return "";
        }
        String[] lines = normalizeLineEndings(text).split("\n", -1);
        StringBuilder compact = new StringBuilder();
        for (String line : lines) {
            String candidate = line.strip();
            if (candidate.startsWith("#")) {
                candidate = candidate.substring(1).strip();
            }
            candidate = collapseWhitespace(candidate);
            if (candidate.isEmpty()) {
                continue;
            }
            if (compact.length() > 0) {
                compact.append(' ');
            }
            compact.append(candidate);
        }
        return truncateCodePoints(compact.toString(), maximumCodePoints);
    }

    private static String normalizeLineEndings(String text) {
        return (text == null ? "" : text)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String collapseWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
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

    private static String truncateCodePoints(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) {
            return value;
        }
        if (maximumCodePoints <= 3) {
            return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
        }
        int retained = maximumCodePoints - 3;
        int end = value.offsetByCodePoints(0, retained);
        return value.substring(0, end).stripTrailing() + "...";
    }
}
