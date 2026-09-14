import vip.thatiam.mml.MirrorOwnershipPolicy;

public final class MirrorOwnershipPolicySelfTest {
    public static void main(String[] arguments) {
        check(MirrorOwnershipPolicy.mayUseDirectory(true, false, false, false, false),
                "truly empty root may initialize");
        check(MirrorOwnershipPolicy.mayUseDirectory(false, true, false, false, false),
                "final README proves ownership");
        check(MirrorOwnershipPolicy.mayUseDirectory(false, false, true, false, false),
                "valid pending README proves interrupted ownership");
        check(MirrorOwnershipPolicy.mayUseDirectory(false, false, false, true, false),
                "final index proves ownership");
        check(MirrorOwnershipPolicy.mayUseDirectory(false, false, false, false, true),
                "valid pending index proves interrupted ownership");
        check(!MirrorOwnershipPolicy.mayUseDirectory(false, false, false, false, false),
                "markerless generated-looking content is never adopted");
        check(MirrorOwnershipPolicy.mayHealReservedControlDocument(true, false),
                "a valid final or pending marker authorizes healing a torn reserved control file");
        check(MirrorOwnershipPolicy.mayHealReservedControlDocument(false, true),
                "exact interrupted-first-sync control bytes may be adopted");
        check(!MirrorOwnershipPolicy.mayHealReservedControlDocument(false, false),
                "markerless mismatched README/index bytes are preserved as a conflict");
        check(MirrorOwnershipPolicy.mayManagePendingSidecar(true, false),
                "a sidecar matching current source bytes is independently proven");
        check(MirrorOwnershipPolicy.mayManagePendingSidecar(false, true),
                "a reserved sidecar's own valid marker is independent proof");
        check(!MirrorOwnershipPolicy.mayManagePendingSidecar(false, false),
                "base-path/index ownership cannot authorize a differing sidecar");
        check(MirrorOwnershipPolicy.hasPhysicalEntryCapacity(8_190, 8_192, 2),
                "8,190 finals leave pending and final slots for a new path");
        check(!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(8_191, 8_192, 2),
                "8,191 live paths preserve the final transient recovery slot");
        check(MirrorOwnershipPolicy.hasPhysicalEntryCapacity(8_191, 8_192, 1),
                "an existing final may admit one pending before its confirmed deletion");
        check(!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(8_192, 8_192, 1),
                "a full physical directory rejects creation even if few names were accepted");
        check(!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(-1, 8_192, 1),
                "unknown physical occupancy is never treated as spare capacity");
        check(MirrorOwnershipPolicy.requiredSlotsBeforePending(false) == 2,
                "a new path reserves concurrent pending and final slots");
        check(MirrorOwnershipPolicy.requiredSlotsBeforePending(true) == 1,
                "replacement needs only the pending slot before deleting its final");
        check(MirrorOwnershipPolicy.hasIndexEntryCapacity(8_191, 1, 8_192),
                "README plus 8,191 session files exactly fills the index");
        check(!MirrorOwnershipPolicy.hasIndexEntryCapacity(8_192, 1, 8_192),
                "README is reserved in the index before session mirroring");
        check(MirrorOwnershipPolicy.hasIndexEntryCapacity(8_189, 3, 8_192),
                "README plus both optional roots leaves 8,189 session entries");
        check(!MirrorOwnershipPolicy.hasIndexEntryCapacity(8_190, 3, 8_192),
                "optional root entries reduce the exact session boundary");
        check(MirrorOwnershipPolicy.isStableRefreshEntry(true, true, true),
                "capacity refresh retains the exact known URI and kind");
        check(!MirrorOwnershipPolicy.isStableRefreshEntry(true, false, false),
                "capacity refresh rejects a missing known identity");
        check(!MirrorOwnershipPolicy.isStableRefreshEntry(true, true, false),
                "capacity refresh rejects a replaced same-name identity");
        check(!MirrorOwnershipPolicy.isStableRefreshEntry(false, true, false),
                "capacity refresh rejects a concurrently added name");
        int cachedBeforeStaleDeletion = 8_191;
        int confirmedAfterRefresh = 1;
        check(!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                        cachedBeforeStaleDeletion, 8_192, 2)
                        && MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                        confirmedAfterRefresh, 8_192, 2),
                "refresh reclaims indexed stale rows before a small replacement set");
        simulateLongInitialMirror();
        System.out.println("Mirror ownership policy self-test passed.");
    }

    private static void simulateLongInitialMirror() {
        int actualPhysicalEntries = 0;
        int cachedPhysicalEntries = 0;
        for (int file = 0; file < 8_191; file++) {
            if (!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                    cachedPhysicalEntries, 8_192, 2)) {
                // A real refresh confirms successful pending deletion and reclaims its slot.
                cachedPhysicalEntries = actualPhysicalEntries;
            }
            check(MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                            cachedPhysicalEntries, 8_192, 2),
                    "initial mirror refresh retains capacity through file " + file);
            cachedPhysicalEntries += 2;
            actualPhysicalEntries++;
        }
        cachedPhysicalEntries = actualPhysicalEntries;
        check(actualPhysicalEntries == 8_191,
                "initial mirror reaches the maximum live-final boundary");
        check(!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                        cachedPhysicalEntries, 8_192, 2),
                "the 8,192nd new final is rejected before creating pending");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
