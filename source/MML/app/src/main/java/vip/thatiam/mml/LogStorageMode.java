package vip.thatiam.mml;

/** Persisted compatibility values for MML's mutually exclusive log-management choices. */
public enum LogStorageMode {
    /** Default: MML retains the logs in its private application storage. */
    WITH_APP(
            "with-app",
            "APP-MANAGED LOGS"),
    /** A user-selected directory receives the readable log files. */
    USER_FILESPACE(
            "user-filespace",
            "USER-MANAGED LOG DIRECTORY");

    public static final String PREFERENCE_KEY = "log-storage-mode";
    public static final LogStorageMode DEFAULT = WITH_APP;

    private final String preferenceValue;
    private final String displayName;

    LogStorageMode(String preferenceValue, String displayName) {
        this.preferenceValue = preferenceValue;
        this.displayName = displayName;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static LogStorageMode fromPreference(String value) {
        for (LogStorageMode mode : values()) {
            if (mode.preferenceValue.equals(value)) {
                return mode;
            }
        }
        return DEFAULT;
    }
}
