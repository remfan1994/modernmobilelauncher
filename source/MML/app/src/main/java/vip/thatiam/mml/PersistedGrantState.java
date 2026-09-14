package vip.thatiam.mml;

/** Tri-state-plus-partial classification for Android persisted directory permission flags. */
public enum PersistedGrantState {
    FULL,
    PARTIAL,
    NONE,
    UNKNOWN;

    public static PersistedGrantState classify(int heldFlags, int requiredFlags) {
        if (heldFlags < 0) {
            return UNKNOWN;
        }
        int heldRequiredFlags = heldFlags & requiredFlags;
        if (heldRequiredFlags == requiredFlags) {
            return FULL;
        }
        return heldRequiredFlags == 0 ? NONE : PARTIAL;
    }
}
