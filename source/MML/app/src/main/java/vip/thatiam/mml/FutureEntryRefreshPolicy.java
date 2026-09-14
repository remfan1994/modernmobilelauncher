package vip.thatiam.mml;

/** Pure merge policy for coalescing future-count scans without losing notification intent. */
public final class FutureEntryRefreshPolicy {
    private FutureEntryRefreshPolicy() {
    }

    public enum Mode {
        ALWAYS,
        ACTIVE_ONLY,
        MUTATION_BOUNDARY
    }

    public static Request merge(
            boolean requestAlreadyRunning,
            Mode existingMode,
            int existingBaseline,
            Mode incomingMode,
            int incomingBaseline) {
        Mode incoming = incomingMode == null ? Mode.ACTIVE_ONLY : incomingMode;
        if (!requestAlreadyRunning) {
            return new Request(incoming, Math.max(0, incomingBaseline));
        }
        Mode existing = existingMode == null ? Mode.ACTIVE_ONLY : existingMode;
        if (existing == Mode.ALWAYS || incoming == Mode.ALWAYS) {
            return new Request(Mode.ALWAYS, 0);
        }
        if (existing == Mode.MUTATION_BOUNDARY) {
            // Keep the earliest unobserved boundary so rapid N->N+1->N+2 mutations still repost.
            return new Request(Mode.MUTATION_BOUNDARY, Math.max(0, existingBaseline));
        }
        if (incoming == Mode.MUTATION_BOUNDARY) {
            return new Request(Mode.MUTATION_BOUNDARY, Math.max(0, incomingBaseline));
        }
        return new Request(Mode.ACTIVE_ONLY, Math.max(0, incomingBaseline));
    }

    public static boolean mergePermissionRequest(
            boolean requestAlreadyRunning,
            boolean existingRequest,
            boolean incomingRequest) {
        return requestAlreadyRunning
                ? existingRequest || incomingRequest
                : incomingRequest;
    }

    public static boolean shouldMergeExistingIntent(
            boolean requestAlreadyRunning,
            long completedGeneration,
            long acknowledgedGeneration) {
        return requestAlreadyRunning || completedGeneration > acknowledgedGeneration;
    }

    public static boolean shouldClaimCompletion(
            boolean requestRunning,
            long completedGeneration,
            long processConsumedGeneration,
            long acknowledgedGeneration) {
        return !requestRunning
                && completedGeneration > processConsumedGeneration
                && completedGeneration > acknowledgedGeneration;
    }

    public static final class Request {
        private final Mode mode;
        private final int baseline;

        private Request(Mode mode, int baseline) {
            this.mode = mode;
            this.baseline = baseline;
        }

        public Mode getMode() { return mode; }
        public int getBaseline() { return baseline; }
    }
}
