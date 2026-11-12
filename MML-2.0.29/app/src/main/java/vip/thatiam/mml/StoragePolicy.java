package vip.thatiam.mml;

import java.util.List;

/** Finite automatic-capture and transient-payload budgets with bounded rolling history. */
public final class StoragePolicy {
  public static final long MIB = 1024L * 1024L;
  public static final int DEFAULT_CAPTURE_LIMIT_MIB = 128;
  public static final String LIMIT_PREFERENCE = "capture_limit_mib";
  public static final long AUTOMATIC_FREE_RESERVE_BYTES = 64L * MIB;
  public static final long MAX_BATCH_BYTES = MIB;
  public static final long MAX_BATCH_ENTRY_BYTES = MAX_BATCH_BYTES - 8192L;
  public static final long MAX_QUEUED_BYTES = 8L * MIB;
  public static final long USER_QUEUE_RESERVE_BYTES = MIB;
  public static final int USER_QUEUE_RESERVE_SLOTS = 8;
  public static final long MAX_TRANSACTION_BYTES = 2L * MIB;
  public static final long MAX_READ_BYTES = 4L * MIB;
  public static final long WRITE_RESERVE_BYTES = 64L * 1024L;
  public static final int ROLL_TRIGGER_PERCENT = 90;
  public static final int ROLL_TARGET_PERCENT = 78;
  public static final int ROLL_DELETE_BATCH = 2048;
  public static final int MAX_ROLL_BATCHES_PER_PASS = 16;

  public enum Blocked {
    NONE,
    JOURNAL_BUDGET,
    DEVICE_SPACE
  }

  private StoragePolicy() {}

  public static boolean validLimitMiB(int value) {
    return value == 32 || value == 64 || value == 128 || value == 256 || value == 512;
  }

  public static int limitMiB(int value) {
    return validLimitMiB(value) ? value : DEFAULT_CAPTURE_LIMIT_MIB;
  }


  public static long budgetBytes(int limitMiB) {
    return (long) limitMiB(limitMiB) * MIB;
  }

  public static long rollTriggerBytes(int limitMiB) {
    return budgetBytes(limitMiB) * ROLL_TRIGGER_PERCENT / 100L;
  }

  public static long rollTargetBytes(int limitMiB) {
    return budgetBytes(limitMiB) * ROLL_TARGET_PERCENT / 100L;
  }

  public static boolean shouldRoll(long liveBytes, long incomingBytes, int limitMiB) {
    long trigger = rollTriggerBytes(limitMiB);
    long safeIncoming = Math.max(0, incomingBytes);
    return liveBytes >= trigger || safeIncoming >= Math.max(0, trigger - Math.max(0, liveBytes));
  }

  /** Conservative UTF-16 heap / UTF-8 payload estimate, including per-record metadata. */
  public static long entryBytes(Entry entry) {
    long characters =
        (long) entry.uid.length()
            + entry.day.length()
            + entry.zone.length()
            + entry.session.length()
            + entry.kind.length()
            + entry.source.length()
            + entry.type.length()
            + entry.title.length()
            + entry.body.length()
            + entry.stateKey.length()
            + entry.stateValue.length()
            + entry.notificationKeyHash.length();
    return 1024L + 3L * characters;
  }

  public static long batchBytes(List<Entry> entries) {
    long bytes = 256;
    for (Entry entry : entries) bytes += entryBytes(entry);
    return bytes;
  }

  public static boolean userOnly(List<Entry> entries) {
    if (entries.isEmpty()) return false;
    for (Entry entry : entries)
      if (!"USER".equals(entry.kind)) return false;
    return true;
  }

  public static long machineCount(List<Entry> entries) {
    long count = 0;
    for (Entry entry : entries)
      if (!"USER".equals(entry.kind)) count++;
    return count;
  }

  public static Blocked blocked(long journalBytes, long usableBytes, long payloadBytes, int limitMiB) {
    // Reserve space for SQLite pages/index/WAL bookkeeping as well as the input payload.
    long growth =
        WRITE_RESERVE_BYTES
            + Math.min(Math.max(0, payloadBytes), (Long.MAX_VALUE - WRITE_RESERVE_BYTES) / 2L)
                * 2L;
    if (usableBytes < AUTOMATIC_FREE_RESERVE_BYTES
        || growth > usableBytes - AUTOMATIC_FREE_RESERVE_BYTES) return Blocked.DEVICE_SPACE;
    long budget = budgetBytes(limitMiB);
    if (journalBytes >= budget || growth > budget - Math.max(0, journalBytes))
      return Blocked.JOURNAL_BUDGET;
    return Blocked.NONE;
  }

  public static boolean queueFits(long reservedBytes, long incomingBytes, boolean protectedWrite) {
    long ceiling = MAX_QUEUED_BYTES - (protectedWrite ? 0 : USER_QUEUE_RESERVE_BYTES);
    return reservedBytes >= 0
        && incomingBytes >= 0
        && incomingBytes <= ceiling - reservedBytes;
  }
}
