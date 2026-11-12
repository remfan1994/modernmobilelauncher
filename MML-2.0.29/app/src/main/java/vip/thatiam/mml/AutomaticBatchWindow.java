package vip.thatiam.mml;

/** Timing policy for automatic machine-event transaction coalescing. */
final class AutomaticBatchWindow {
  static final long QUIET_MILLIS = 250L;
  static final long MAX_MILLIS = 1000L;
  static final int MAX_JOBS = 32;

  private final long hardDeadline;
  private long quietDeadline;

  AutomaticBatchWindow(long startedAtMillis) {
    hardDeadline = saturatingAdd(startedAtMillis, MAX_MILLIS);
    quietDeadline = Math.min(hardDeadline, saturatingAdd(startedAtMillis, QUIET_MILLIS));
  }

  void automaticArrived(long nowMillis) {
    quietDeadline = Math.min(hardDeadline, saturatingAdd(nowMillis, QUIET_MILLIS));
  }

  long remainingWaitMillis(long nowMillis) {
    long deadline = Math.min(quietDeadline, hardDeadline);
    return deadline <= nowMillis ? 0L : deadline - nowMillis;
  }

  boolean full(int jobs, long payloadBytes) {
    return jobs >= MAX_JOBS || payloadBytes >= StoragePolicy.MAX_TRANSACTION_BYTES;
  }

  private static long saturatingAdd(long a, long b) {
    return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
  }
}
