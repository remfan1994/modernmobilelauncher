import vip.thatiam.mml.LogStorageMode;

public final class LogStorageModeSelfTest {
    public static void main(String[] arguments) {
        check(LogStorageMode.DEFAULT == LogStorageMode.WITH_APP, "app-managed logging default");
        check(
                LogStorageMode.fromPreference("with-app") == LogStorageMode.WITH_APP,
                "app-managed logging preference");
        check(
                LogStorageMode.fromPreference("user-filespace")
                        == LogStorageMode.USER_FILESPACE,
                "user-managed directory preference");
        check(
                LogStorageMode.fromPreference("unknown") == LogStorageMode.WITH_APP,
                "unknown preference fallback");
        check(
                "APP-MANAGED LOGS".equals(LogStorageMode.WITH_APP.getDisplayName()),
                "app-managed label");
        check(
                "USER-MANAGED LOG DIRECTORY".equals(
                        LogStorageMode.USER_FILESPACE.getDisplayName()),
                "user-managed directory label");
        check(
                !LogStorageMode.WITH_APP.getPreferenceValue().equals(
                        LogStorageMode.USER_FILESPACE.getPreferenceValue()),
                "mutually exclusive preference values");
        System.out.println("Log storage mode self-test passed.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
