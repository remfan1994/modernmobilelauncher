package vip.thatiam.mml;

/** Nested policy for automatic rows in MML's session log. */
public enum LogVerbosity {
    NOTIFICATIONS_ONLY("notifications-only", "Notifications only", 0),
    IMPORTANT("important", "Important", 1),
    ACTIONS("actions", "Actions", 2),
    FULL("full", "Full", 3);

    public static final String PREFERENCE_KEY = "log-verbosity";
    public static final LogVerbosity DEFAULT = IMPORTANT;
    private static final String CAPTURED_NOTIFICATION_EVENT_TYPE = "notification_captured";

    private final String preferenceValue;
    private final String displayName;
    private final int level;

    LogVerbosity(String preferenceValue, String displayName, int level) {
        this.preferenceValue = preferenceValue;
        this.displayName = displayName;
        this.level = level;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Notification rows are the minimum automatic level; Comments are independent and always kept. */
    public boolean includesNotifications() {
        return true;
    }

    /**
     * Returns whether one stored event belongs in this level.
     *
     * User Comments are invariant across every level. Unknown machine types are deliberately full
     * only so a later release cannot make a new noisy type visible at a lower level by accident.
     */
    public boolean includesEventType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        if ("comment".equals(type) || "user_note".equals(type)) {
            return true;
        }
        if (CAPTURED_NOTIFICATION_EVENT_TYPE.equals(type)) {
            return true;
        }
        int requiredLevel = requiredLevel(type);
        return requiredLevel >= 0 && level >= requiredLevel;
    }

    public static LogVerbosity fromPreference(String storedValue) {
        if (storedValue != null) {
            for (LogVerbosity candidate : values()) {
                if (candidate.preferenceValue.equals(storedValue)) {
                    return candidate;
                }
            }
        }
        return DEFAULT;
    }

    private static int requiredLevel(String type) {
        return switch (type) {
            case "launch_failed",
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
                    "session_unclosed" -> IMPORTANT.level;
            case "launch_requested",
                    "app_details_requested",
                    "notification_action_opened",
                    "calendar_comments_saved",
                    "future_notification_opened",
                    "future_event_opened" -> ACTIONS.level;
            case "launcher_session_started",
                    "session_opened",
                    "panel_opened",
                    "home_resumed",
                    "home_requested",
                    "launcher_entry_requested" -> FULL.level;
            default -> FULL.level;
        };
    }
}
