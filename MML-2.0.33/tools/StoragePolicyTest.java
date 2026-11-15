package vip.thatiam.mml;

import java.util.Arrays;
import java.util.Collections;

/** Host checks for finite automatic-capture limits and reserved manual-entry capacity. */
public final class StoragePolicyTest {
  private static int checks;

  public static void main(String[] args) {
    check(StoragePolicy.DEFAULT_CAPTURE_LIMIT_MIB == 128, "Default journal budget");
    for (int limit : new int[] {32, 64, 128, 256, 512}) {
      check(StoragePolicy.validLimitMiB(limit), "Finite storage choice");
      check(StoragePolicy.limitMiB(limit) == limit, "Selected budget retained");
      check(
          StoragePolicy.blocked(limit * StoragePolicy.MIB, 2L * 1024 * StoragePolicy.MIB, 0, limit)
              == StoragePolicy.Blocked.JOURNAL_BUDGET,
          "A full journal refuses additional automatic input");
    }
    for (int invalid : new int[] {-1, 0, 1, 31, 33, 127, 513, Integer.MAX_VALUE}) {
      check(!StoragePolicy.validLimitMiB(invalid), "No implicit unlimited option");
      check(StoragePolicy.limitMiB(invalid) == 128, "Invalid stored limit uses finite default");
    }
    check(
        StoragePolicy.blocked(0, 64L * StoragePolicy.MIB, 0, 128)
            == StoragePolicy.Blocked.DEVICE_SPACE,
        "Free-space reserve");
    check(
        StoragePolicy.blocked(0, 64L * StoragePolicy.MIB + 65536, 0, 128)
            == StoragePolicy.Blocked.NONE,
        "Exact usable-space boundary");
    check(
        StoragePolicy.blocked(127L * StoragePolicy.MIB, 1024L * StoragePolicy.MIB,
            StoragePolicy.MIB, 128) == StoragePolicy.Blocked.JOURNAL_BUDGET,
        "Incoming payload reserved before acceptance");
    check(
        StoragePolicy.blocked(0, Long.MAX_VALUE, Long.MAX_VALUE, 128)
            != StoragePolicy.Blocked.NONE,
        "Overflow cannot bypass finite budget");

    check(StoragePolicy.ROLL_TRIGGER_PERCENT == 90, "Rolling starts before hard limit");
    check(StoragePolicy.ROLL_TARGET_PERCENT == 78, "Rolling leaves reusable headroom");
    check(StoragePolicy.rollTriggerBytes(128) == 115L * StoragePolicy.MIB + 209715L,
        "128 MiB trigger is exact integer percentage");
    check(StoragePolicy.rollTargetBytes(128) == 99L * StoragePolicy.MIB + 880803L,
        "128 MiB target is exact integer percentage");
    check(StoragePolicy.shouldRoll(StoragePolicy.rollTriggerBytes(64), 0, 64),
        "Trigger starts rolling");
    check(!StoragePolicy.shouldRoll(StoragePolicy.rollTriggerBytes(64) - 1, 0, 64),
        "Below trigger remains stable");
    check(StoragePolicy.shouldRoll(StoragePolicy.rollTriggerBytes(64) - 1024, 2048, 64),
        "Incoming automatic batch can trigger rolling early");

    long sevenMiB = 7L * StoragePolicy.MIB;
    check(!StoragePolicy.queueFits(sevenMiB, 1, false), "Automatic capture leaves user headroom");
    check(StoragePolicy.queueFits(sevenMiB, StoragePolicy.MIB, true), "USER entry may use reserve");
    check(!StoragePolicy.queueFits(8L * StoragePolicy.MIB, 1, true), "Total queue finite");

    char[] full = new char[Entry.MAX_USER_ENTRY];
    Arrays.fill(full, '\u4e00');
    Entry maximum = Entry.user(new String(full));
    check(StoragePolicy.entryBytes(maximum) < StoragePolicy.MAX_READ_BYTES,
        "One maximum USER entry can advance a bounded page");
    check(StoragePolicy.userOnly(Collections.singletonList(maximum)), "USER entry protected");
    check(StoragePolicy.machineCount(Collections.singletonList(maximum)) == 0,
        "USER entry is not an automatic capture-gap event");
    check(!StoragePolicy.userOnly(Collections.emptyList()), "Empty metadata job is not a USER entry");
    Entry automatic = Entry.event("DEVICE", "Fixture", "state", "State", "body");
    check(!StoragePolicy.userOnly(Arrays.asList(maximum, automatic)), "Mixed batch is automatic");
    check(StoragePolicy.machineCount(Arrays.asList(maximum, automatic)) == 1,
        "Machine count distinguishes manual entry");

    for (int chars = 0; chars <= 65536; chars += 1024) {
      Entry row = Entry.event("NOTIFICATION", "Fixture", "posted", "Payload", new String(new char[chars]));
      long estimate = StoragePolicy.entryBytes(row);
      check(estimate >= chars * 3L, "Estimate covers UTF-8 payload");
      check(estimate <= StoragePolicy.MAX_BATCH_ENTRY_BYTES, "Machine event fits bounded batch");
    }
    System.out.println("StoragePolicyTest: " + checks + " checks passed");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
  }
}
