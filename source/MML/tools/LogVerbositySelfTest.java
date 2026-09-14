package vip.thatiam.mml;

public final class LogVerbositySelfTest {
    public static void main(String[] arguments) {
        check(LogVerbosity.DEFAULT == LogVerbosity.IMPORTANT, "quiet upgrade default");
        for (LogVerbosity verbosity : LogVerbosity.values()) {
            check(LogVerbosity.fromPreference(verbosity.getPreferenceValue()) == verbosity,
                    "preference round trip for " + verbosity);
            check(!verbosity.getDisplayName().isBlank(), "visible label for " + verbosity);
            check(verbosity.includesEventType("comment"), "Comments included by " + verbosity);
            check(verbosity.includesEventType("user_note"),
                    "legacy Comments included by " + verbosity);
            check(verbosity.includesNotifications(),
                    "notification capability included by " + verbosity);
            check(verbosity.includesEventType("notification_captured"),
                    "notification rows included by " + verbosity);
        }

        check(LogVerbosity.fromPreference(null) == LogVerbosity.DEFAULT,
                "missing preference uses default");
        check(LogVerbosity.fromPreference("") == LogVerbosity.DEFAULT,
                "blank preference uses default");
        check(LogVerbosity.fromPreference("foreign") == LogVerbosity.DEFAULT,
                "unknown preference uses default");

        check(!LogVerbosity.NOTIFICATIONS_ONLY.includesEventType("launch_failed"),
                "notification-only excludes important rows");
        check(LogVerbosity.IMPORTANT.includesEventType("launch_failed"),
                "important includes failures");
        check(!LogVerbosity.IMPORTANT.includesEventType("launch_requested"),
                "important excludes ordinary launches");
        check(LogVerbosity.ACTIONS.includesEventType("launch_requested"),
                "actions includes ordinary launches");
        check(!LogVerbosity.ACTIONS.includesEventType("panel_opened"),
                "actions excludes routine panel rows");
        check(LogVerbosity.FULL.includesEventType("panel_opened"),
                "full includes routine panel rows");

        String[] importantTypes = {
                "journal_history_deleted",
                "journal_history_cleared",
                "calendar_comments_deleted",
                "calendar_comments_cleared",
                "device_brightness_changed",
                "system_brightness_changed",
                "shutdown_observed",
                "session_ended_cleanly",
                "later_launcher_startup_observed",
                "prior_boot_boundary_inferred",
                "session_unclosed"
        };
        for (String type : importantTypes) {
            check(LogVerbosity.IMPORTANT.includesEventType(type),
                    "important event included: " + type);
        }

        String[] actionTypes = {
                "app_details_requested",
                "notification_action_opened",
                "calendar_comments_saved",
                "future_notification_opened",
                "future_event_opened"
        };
        for (String type : actionTypes) {
            check(!LogVerbosity.IMPORTANT.includesEventType(type),
                    "important excludes action: " + type);
            check(LogVerbosity.ACTIONS.includesEventType(type),
                    "actions includes action: " + type);
        }

        check(!LogVerbosity.FULL.includesEventType(null), "null event rejected");
        check(!LogVerbosity.FULL.includesEventType(""), "blank event rejected");
        check(!LogVerbosity.ACTIONS.includesEventType("future_new_event"),
                "unknown event excluded below full");
        check(LogVerbosity.FULL.includesEventType("future_new_event"),
                "unknown event included by full");

        System.out.println("LogVerbosity self-test passed.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
