package vip.thatiam.mml;

public final class SettingsHelpTest {
  public static void main(String[] args) {
    int checks = 0;
    String[] labels = {
      "Notifications",
      "Notification removals",
      "Calls",
      "SMS",
      "App resumed",
      "App paused / stopped",
      "Screen / lock state",
      "App interaction",
      "Foreground services",
      "Device startup / shutdown",
      "Other usage events",
      "MML / system diagnostics",
      "Device records"
    };
    for (String label : labels) {
      check(SettingsHelp.explanation(label).length() > 40, "missing explanation for " + label);
      checks++;
    }
    check("Capture notifications".equals(SettingsHelp.title("[x] Capture notifications")), "toggle title");
    checks++;
    check("Log view".equals(SettingsHelp.title("Log view: Custom")), "dynamic log-detail title");
    checks++;
    check(SettingsHelp.explanation("Device records").contains("USER_PRESENT"), "device help names USER_PRESENT");
    checks++;
    System.out.println("SettingsHelpTest: " + checks + " checks passed");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
