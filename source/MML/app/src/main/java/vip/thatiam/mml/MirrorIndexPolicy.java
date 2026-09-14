package vip.thatiam.mml;

/** Pure completeness/selection policy for the bounded SAF mirror ownership index. */
public final class MirrorIndexPolicy {
    public static final String FRAMED_HEADER = "MML-MIRROR-4";
    public static final String LEGACY_HEADER = "MML-MIRROR-3";

    private MirrorIndexPolicy() {
    }

    public static boolean isFramedHeader(String header) {
        return FRAMED_HEADER.equals(header);
    }

    public static boolean isLegacyHeader(String header) {
        return LEGACY_HEADER.equals(header);
    }

    public static boolean isComplete(
            boolean framed,
            boolean trailerSeen,
            int declaredEntryCount,
            int actualEntryCount,
            boolean legacyReadmeEntryPresent) {
        if (framed) {
            return trailerSeen
                    && declaredEntryCount >= 0
                    && declaredEntryCount == actualEntryCount;
        }
        return legacyReadmeEntryPresent && actualEntryCount > 0;
    }

    /** A complete pending index is newer recovery state than even a complete old final. */
    public static boolean preferPending(boolean pendingValid, boolean finalValid) {
        return pendingValid || !finalValid;
    }
}
