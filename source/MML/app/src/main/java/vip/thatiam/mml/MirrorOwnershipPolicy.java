package vip.thatiam.mml;

/** Pure ownership decision for adopting or initializing the generated SAF mirror directory. */
public final class MirrorOwnershipPolicy {
    private MirrorOwnershipPolicy() {
    }

    public static boolean mayUseDirectory(
            boolean empty,
            boolean validReadme,
            boolean validPendingReadme,
            boolean validIndex,
            boolean validPendingIndex) {
        return empty
                || validReadme
                || validPendingReadme
                || validIndex
                || validPendingIndex;
    }

    /** Reserved README/index recovery may replace a torn copy only after root identity is proven. */
    public static boolean mayHealReservedControlDocument(
            boolean validOwnershipMarker,
            boolean exactCurrentBytes) {
        return validOwnershipMarker || exactCurrentBytes;
    }

    /** Base-path/index ownership never proves a sidecar; only its bytes or own control marker do. */
    public static boolean mayManagePendingSidecar(
            boolean exactlyMatchesCurrentBytes,
            boolean validOwnControlMarker) {
        return exactlyMatchesCurrentBytes || validOwnControlMarker;
    }

    /** Creation capacity is a provider-row property, including every concurrently required slot. */
    public static boolean hasPhysicalEntryCapacity(
            int physicalEntryCount,
            int maximumEntryCount,
            int requiredSlots) {
        return physicalEntryCount >= 0
                && maximumEntryCount > 0
                && requiredSlots > 0
                && physicalEntryCount <= maximumEntryCount - requiredSlots;
    }

    /** A new final needs pending+final slots; replacement can reuse the old final's deleted slot. */
    public static int requiredSlotsBeforePending(boolean finalAlreadyExists) {
        return finalAlreadyExists ? 1 : 2;
    }

    /** The framed index also contains the README and each currently present optional root file. */
    public static boolean hasIndexEntryCapacity(
            int sessionEntries,
            int rootEntries,
            int maximumIndexEntries) {
        return sessionEntries >= 0
                && rootEntries >= 0
                && maximumIndexEntries > 0
                && sessionEntries <= maximumIndexEntries - rootEntries;
    }

    /** Capacity refresh may confirm known identities, never adopt a concurrently changed path. */
    public static boolean isStableRefreshEntry(
            boolean presentBefore,
            boolean presentAfter,
            boolean sameIdentityAndKind) {
        return presentBefore == presentAfter
                && (!presentBefore || sameIdentityAndKind);
    }
}
