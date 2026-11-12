package vip.thatiam.mml;

public final class AutomaticBatchWindowTest {
  private static int checks;
  public static void main(String[] args) {
    AutomaticBatchWindow w = new AutomaticBatchWindow(1000L);
    check(w.remainingWaitMillis(1000L) == 250L, "initial quiet window");
    check(w.remainingWaitMillis(1249L) == 1L, "quiet window countdown");
    check(w.remainingWaitMillis(1250L) == 0L, "quiet window expiry");

    w = new AutomaticBatchWindow(1000L);
    w.automaticArrived(1200L);
    check(w.remainingWaitMillis(1200L) == 250L, "arrival extends quiet window");
    w.automaticArrived(1900L);
    check(w.remainingWaitMillis(1900L) == 100L, "hard deadline clamps extension");
    check(w.remainingWaitMillis(2000L) == 0L, "one-second hard deadline");

    check(!w.full(31, StoragePolicy.MAX_TRANSACTION_BYTES - 1), "31 small jobs can continue");
    check(w.full(32, 1), "32 jobs flush early");
    check(w.full(1, StoragePolicy.MAX_TRANSACTION_BYTES), "full transaction flushes early");

    AutomaticBatchWindow huge = new AutomaticBatchWindow(Long.MAX_VALUE - 10L);
    check(huge.remainingWaitMillis(Long.MAX_VALUE - 10L) == 10L, "deadline saturates safely");

    System.out.println("AutomaticBatchWindowTest: " + checks + " checks passed");
  }

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }
}
