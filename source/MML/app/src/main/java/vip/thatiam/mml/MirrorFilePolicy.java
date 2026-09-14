package vip.thatiam.mml;

/** Pure decision policy for bounded, recoverable user-filespace mirror reconciliation. */
public final class MirrorFilePolicy {
    private MirrorFilePolicy() {
    }

    public static boolean canReuseFinal(
            boolean pendingWriteExists,
            String currentFingerprint,
            String indexedFingerprint,
            long finalLength,
            long expectedLength,
            boolean unknownLengthVerified) {
        return !pendingWriteExists
                && currentFingerprint != null
                && currentFingerprint.equals(indexedFingerprint)
                && (finalLength == expectedLength
                        || finalLength < 0L && unknownLengthVerified);
    }

    public static boolean canAppendEventTail(
            boolean pendingWriteExists,
            boolean finalExists,
            String targetName,
            long indexedLength,
            long sourceLength,
            long finalLength) {
        return !pendingWriteExists
                && finalExists
                && targetName != null
                && (targetName.endsWith(".events")
                        || targetName.endsWith(".events.txt"))
                && indexedLength >= 0L
                && sourceLength > indexedLength
                && finalLength == indexedLength;
    }

    /** Existing mirror documents may only be mutated with manifest or byte-equality proof. */
    public static boolean mayManageExisting(boolean indexed, boolean exactlyMatchesCurrentSource) {
        return indexed || exactlyMatchesCurrentSource;
    }

    /** A stale document has no current bytes to verify, so prior-index proof is mandatory. */
    public static boolean mayRemoveStale(boolean indexed, boolean livePrivateSource) {
        return indexed && !livePrivateSource;
    }

    /** Interrupted first-sync pending output is recoverable only when every extant copy matches. */
    public static boolean mayRecoverUnindexedPending(
            boolean pendingExactlyMatches,
            boolean finalExists,
            boolean finalExactlyMatches) {
        return pendingExactlyMatches && (!finalExists || finalExactlyMatches);
    }
}
