package vip.thatiam.mml;

/** Pure state predicates for the process-global journal-maintenance handoff. */
public final class JournalMaintenancePolicy {
    private JournalMaintenancePolicy() {
    }

    public static boolean hasPendingCompletion(
            boolean hasCompletedKind,
            long generation,
            long acknowledgedGeneration) {
        return hasCompletedKind && generation > acknowledgedGeneration;
    }

    public static boolean canBegin(
            boolean busy,
            boolean hasCompletedKind,
            long generation,
            long acknowledgedGeneration) {
        return !busy && !hasPendingCompletion(
                hasCompletedKind, generation, acknowledgedGeneration);
    }

    public static boolean canAcknowledge(
            boolean busy,
            boolean hasCompletedKind,
            long generation,
            long requestedGeneration,
            long acknowledgedGeneration) {
        return !busy
                && hasCompletedKind
                && generation == requestedGeneration
                && generation > acknowledgedGeneration;
    }
}
