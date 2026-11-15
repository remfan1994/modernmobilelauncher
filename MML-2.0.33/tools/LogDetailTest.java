import vip.thatiam.mml.LogDetail;

public final class LogDetailTest {
  public static void main(String[] args) {
    check("Custom".equals(LogDetail.label(LogDetail.CUSTOM)), "custom label");
    check("Full".equals(LogDetail.label(LogDetail.FULL)), "full label");
    check("Notifications".equals(LogDetail.label(LogDetail.NOTIFICATIONS)), "notifications label");
    check("Communications".equals(LogDetail.label(LogDetail.COMMUNICATIONS)), "communications label");
    check("Apps".equals(LogDetail.label(LogDetail.APPS)), "apps label");
    check("Device".equals(LogDetail.label(LogDetail.DEVICE)), "device label");
    check(LogDetail.normalize(99) == LogDetail.CUSTOM, "unknown defaults custom");

    int expectedDefault = LogDetail.SHOW_NOTIFICATIONS | LogDetail.SHOW_CALLS | LogDetail.SHOW_SMS;
    check(LogDetail.DEFAULT_CUSTOM == expectedDefault, "quiet custom default");

    int mode = LogDetail.NOTIFICATIONS;
    mode = LogDetail.next(mode); check(mode == LogDetail.COMMUNICATIONS, "cycle communications");
    mode = LogDetail.next(mode); check(mode == LogDetail.APPS, "cycle apps");
    mode = LogDetail.next(mode); check(mode == LogDetail.DEVICE, "cycle device");
    mode = LogDetail.next(mode); check(mode == LogDetail.FULL, "cycle full");
    mode = LogDetail.next(mode); check(mode == LogDetail.CUSTOM, "cycle custom");
    mode = LogDetail.next(mode); check(mode == LogDetail.NOTIFICATIONS, "cycle wraps");

    String notifications = LogDetail.sqlPredicate(LogDetail.NOTIFICATIONS, 0);
    check(notifications.contains("kind='NOTIFICATION'"), "notifications view includes notifications");
    check(!notifications.contains("kind='CALL'"), "notifications view excludes calls");

    String communications = LogDetail.sqlPredicate(LogDetail.COMMUNICATIONS, 0);
    check(communications.contains("kind='NOTIFICATION'"), "communications notifications");
    check(communications.contains("kind='CALL'"), "communications calls");
    check(communications.contains("kind='SMS'"), "communications sms");

    String apps = LogDetail.sqlPredicate(LogDetail.APPS, 0);
    check(apps.contains("usage_1"), "apps resumed");
    check(apps.contains("usage_23"), "apps stopped");
    check(apps.contains("usage_7"), "apps interaction");
    check(apps.contains("usage_19"), "apps foreground service");
    check(!apps.contains("usage_15"), "apps excludes screen state");

    String device = LogDetail.sqlPredicate(LogDetail.DEVICE, 0);
    check(device.contains("usage_15"), "device screen state");
    check(device.contains("usage_26"), "device lifecycle");
    check(device.contains("kind='DEVICE'"), "device direct records");

    String custom = LogDetail.sqlPredicate(LogDetail.CUSTOM, LogDetail.DEFAULT_CUSTOM);
    check(custom.contains("kind='NOTIFICATION'"), "custom notifications");
    check(custom.contains("kind='CALL'"), "custom calls");
    check(custom.contains("kind='SMS'"), "custom sms");
    check(!custom.contains("usage_1"), "custom excludes usage by default");
    check(custom.contains("type='home_reminder'"), "Bloodguilt warning cannot be filtered");
    check(custom.contains("kind='USER'"), "manual log entries cannot be filtered");

    String full = LogDetail.sqlPredicate(LogDetail.FULL, 0);
    check("1=1".equals(full), "full contains every current log row");
    System.out.println("PASS named log views");
  }

  private static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
  }
}
