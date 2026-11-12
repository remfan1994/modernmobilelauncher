import vip.thatiam.mml.LogDetail;

public final class LogDetailTest {
  public static void main(String[] args) {
    check("Custom".equals(LogDetail.label(LogDetail.CUSTOM)), "custom label");
    check("Full".equals(LogDetail.label(LogDetail.FULL)), "full label");
    check(LogDetail.normalize(99) == LogDetail.CUSTOM, "unknown defaults custom");

    int expectedDefault = LogDetail.SHOW_NOTIFICATIONS | LogDetail.SHOW_CALLS | LogDetail.SHOW_SMS;
    check(LogDetail.DEFAULT_CUSTOM == expectedDefault, "quiet custom default");

    String custom = LogDetail.sqlPredicate(LogDetail.CUSTOM, LogDetail.DEFAULT_CUSTOM);
    check(custom.contains("kind='NOTIFICATION'"), "custom notifications");
    check(custom.contains("kind='CALL'"), "custom calls");
    check(custom.contains("kind='SMS'"), "custom sms");
    check(!custom.contains("usage_1"), "custom excludes usage by default");
    check(custom.contains("type='home_reminder'"), "Bloodguilt warning cannot be filtered");
    check(custom.contains("kind='USER'"), "manual log entries cannot be filtered");

    String full = LogDetail.sqlPredicate(LogDetail.FULL, 0);
    check("1=1".equals(full), "full contains every current log row");
    System.out.println("PASS log detail custom/full");
  }

  private static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
  }
}
