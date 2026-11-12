package vip.thatiam.mml;

/** Display-only log filter. Capture is unchanged; SQLite returns only visible rows. */
public final class LogDetail {
  public static final int CUSTOM = 0;
  public static final int FULL = 1;

  public static final int SHOW_NOTIFICATIONS = 1 << 0;
  public static final int SHOW_NOTIFICATION_REMOVALS = 1 << 1;
  public static final int SHOW_CALLS = 1 << 2;
  public static final int SHOW_SMS = 1 << 3;
  public static final int SHOW_APP_RESUMED = 1 << 4;
  public static final int SHOW_APP_TRANSITIONS = 1 << 5;
  public static final int SHOW_SCREEN = 1 << 6;
  public static final int SHOW_INTERACTION = 1 << 7;
  public static final int SHOW_SERVICES = 1 << 8;
  public static final int SHOW_DEVICE_LIFECYCLE = 1 << 9;
  public static final int SHOW_OTHER_USAGE = 1 << 10;
  public static final int SHOW_SYSTEM = 1 << 11;
  public static final int SHOW_DEVICE = 1 << 12;

  /** Quiet default: user-facing communications plus notifications. */
  public static final int DEFAULT_CUSTOM = SHOW_NOTIFICATIONS | SHOW_CALLS | SHOW_SMS;

  private LogDetail() {}

  public static int normalize(int value) {
    return value == FULL ? FULL : CUSTOM;
  }

  public static String label(int value) {
    return normalize(value) == FULL ? "Full" : "Custom";
  }

  public static int maskForPreset(int value, int customMask) {
    return normalize(value) == FULL ? -1 : customMask;
  }

  /** SQL uses fixed literals only; no user text is concatenated into this expression. */
  public static String sqlPredicate(int level, int customMask) {
    if (normalize(level) == FULL)
      return "1=1";
    int mask = customMask;
    StringBuilder sql = new StringBuilder("(");
    // The Bloodguilt warning is part of MML's startup log and is never suppressible.
    append(sql, true, "kind='SYSTEM' AND type='home_reminder'");
    append(sql, true, "kind='USER'");
    append(sql, (mask & SHOW_NOTIFICATIONS) != 0,
        "kind='NOTIFICATION' AND type IN ('posted','active')");
    append(sql, (mask & SHOW_NOTIFICATION_REMOVALS) != 0,
        "kind='NOTIFICATION' AND type='removed'");
    append(sql, (mask & SHOW_CALLS) != 0, "kind='CALL'");
    append(sql, (mask & SHOW_SMS) != 0, "kind='SMS'");
    append(sql, (mask & SHOW_APP_RESUMED) != 0, "kind='USAGE' AND type='usage_1'");
    append(sql, (mask & SHOW_APP_TRANSITIONS) != 0,
        "kind='USAGE' AND type IN ('usage_2','usage_23')");
    append(sql, (mask & SHOW_SCREEN) != 0,
        "kind='USAGE' AND type IN ('usage_15','usage_16','usage_17','usage_18')");
    append(sql, (mask & SHOW_INTERACTION) != 0, "kind='USAGE' AND type='usage_7'");
    append(sql, (mask & SHOW_SERVICES) != 0,
        "kind='USAGE' AND type IN ('usage_19','usage_20')");
    append(sql, (mask & SHOW_DEVICE_LIFECYCLE) != 0,
        "kind='USAGE' AND type IN ('usage_26','usage_27')");
    append(sql, (mask & SHOW_OTHER_USAGE) != 0,
        "kind='USAGE' AND type NOT IN ('usage_1','usage_2','usage_7','usage_15','usage_16',"
            + "'usage_17','usage_18','usage_19','usage_20','usage_23','usage_26','usage_27')");
    append(sql, (mask & SHOW_SYSTEM) != 0, "kind='SYSTEM'");
    append(sql, (mask & SHOW_DEVICE) != 0, "kind='DEVICE'");
    return sql.append(')').toString();
  }

  private static void append(StringBuilder sql, boolean include, String condition) {
    if (!include) return;
    if (sql.length() > 1) sql.append(" OR ");
    sql.append('(').append(condition).append(')');
  }

  public static String[] customLabels() {
    return new String[] {
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
  }

  public static int[] customBits() {
    return new int[] {
      SHOW_NOTIFICATIONS,
      SHOW_NOTIFICATION_REMOVALS,
      SHOW_CALLS,
      SHOW_SMS,
      SHOW_APP_RESUMED,
      SHOW_APP_TRANSITIONS,
      SHOW_SCREEN,
      SHOW_INTERACTION,
      SHOW_SERVICES,
      SHOW_DEVICE_LIFECYCLE,
      SHOW_OTHER_USAGE,
      SHOW_SYSTEM,
      SHOW_DEVICE
    };
  }
}
