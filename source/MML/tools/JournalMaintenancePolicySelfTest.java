package vip.thatiam.mml;

public final class JournalMaintenancePolicySelfTest {
    private JournalMaintenancePolicySelfTest() {
    }

    public static void main(String[] args) {
        check(JournalMaintenancePolicy.canBegin(false, false, 0L, 0L),
                "an idle coordinator without a result can start");
        check(!JournalMaintenancePolicy.canBegin(true, false, 1L, 0L),
                "a running operation is single-flight");
        check(!JournalMaintenancePolicy.canBegin(false, true, 2L, 0L),
                "a worker completion cannot be overwritten before Activity consumption");
        check(!JournalMaintenancePolicy.canAcknowledge(false, true, 2L, 1L, 0L),
                "a stale Activity cannot acknowledge another completion");
        check(JournalMaintenancePolicy.canAcknowledge(false, true, 2L, 2L, 0L),
                "the exact retained completion can be acknowledged");
        check(JournalMaintenancePolicy.canBegin(false, true, 2L, 2L),
                "a confirmed count permits its follow-on delete or clear only after consumption");
        check(!JournalMaintenancePolicy.canAcknowledge(false, true, 2L, 2L, 2L),
                "a completion is consumed at most once");

        System.out.println("Journal-maintenance policy self-test passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
