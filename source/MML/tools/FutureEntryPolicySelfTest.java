package vip.thatiam.mml;

import java.time.Instant;
import java.util.List;

public final class FutureEntryPolicySelfTest {
    public static void main(String[] arguments) {
        long now = Instant.parse("2026-08-27T19:00:00Z").toEpochMilli();
        List<CalendarComment> comments = List.of(
                comment("2026-08-26", "America/Los_Angeles", "past"),
                comment("2026-08-27", "America/Los_Angeles", "today"),
                comment("2026-08-31", "America/Los_Angeles", "Second future event"),
                comment("2026-08-30", "America/Los_Angeles", "Future event one"),
                comment("2027-01-02", "UTC", "Next year"),
                comment("2026-09-01", "Invalid/Zone", "damaged"),
                comment("2026-09-02", "UTC", "# "));

        check(FutureEntryPolicy.countFutureEntries(comments, now) == 3,
                "future count uses intended dates, zones and payload");
        check(FutureEntryPolicy.countFutureEntries(null, now) == 0,
                "null list is empty");

        List<CalendarComment> ordered = FutureEntryPolicy.futureEntries(comments, now);
        check(ordered.size() == 3, "future list includes every valid event");
        check(ordered.get(0).getTargetLocalDate().equals("2026-08-30"),
                "soonest future event is first");
        check(ordered.get(2).getTargetLocalDate().equals("2027-01-02"),
                "latest future event is last");

        check(FutureEntryPolicy.notificationHeading().equals("Future events..."),
                "notification heading");
        check(FutureEntryPolicy.notificationTitle(1).equals("1 future event"),
                "singular count text");
        check(FutureEntryPolicy.notificationTitle(5).equals("5 future events"),
                "plural count text");

        String requestedBody = FutureEntryPolicy.notificationBody(
                List.of(
                        comment("2026-08-30", "America/Los_Angeles", "Future event one."),
                        comment("2026-08-31", "America/Los_Angeles", "Second future event")),
                2,
                now,
                1_000);
        check(requestedBody.equals(
                        "8/30. Future event one.\n8/31. Second future event"),
                "expanded notification is one chronological event per line");

        check(FutureEntryPolicy.compactEventText(
                        "# First line\n\n#   second   line\twith spacing", 200)
                        .equals("First line second line with spacing"),
                "notification summary removes display markers and joins logical lines");
        check(FutureEntryPolicy.compactEventText("abcdefghij", 7).equals("abcd..."),
                "notification summary is code-point bounded");
        check(FutureEntryPolicy.notificationDateLabel(
                        comment("2027-01-02", "UTC", "Next year"), now)
                        .equals("1/2/2027"),
                "cross-year notification dates remain unambiguous");
        check(FutureEntryPolicy.listLabel(
                        comment("2026-08-30", "America/Los_Angeles", "Line one\nLine two"))
                        .equals("August 30, 2026\nLine one\nLine two"),
                "in-app list preserves full multiline event text");

        String omitted = FutureEntryPolicy.notificationBody(
                List.of(comment("2026-08-30", "UTC", "First")),
                3,
                now,
                1_000);
        check(omitted.equals("8/30. First\n... 2 more future events"),
                "notification reports events omitted by its bounded summary");

        System.out.println("FutureEntryPolicy self-test passed.");
    }

    private static CalendarComment comment(String date, String zone, String text) {
        return new CalendarComment(date, zone, 1L, "UTC", text);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
