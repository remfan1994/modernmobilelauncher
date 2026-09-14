package vip.thatiam.mml;

public final class FutureEntryRefreshPolicySelfTest {
    private FutureEntryRefreshPolicySelfTest() {
    }

    public static void main(String[] args) {
        FutureEntryRefreshPolicy.Request fresh = FutureEntryRefreshPolicy.merge(
                false,
                FutureEntryRefreshPolicy.Mode.ALWAYS,
                99,
                FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                3);
        check(fresh.getMode() == FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                "an idle worker accepts the incoming policy");
        check(fresh.getBaseline() == 3, "an idle worker accepts the incoming baseline");

        FutureEntryRefreshPolicy.Request startupSuperseded = FutureEntryRefreshPolicy.merge(
                true,
                FutureEntryRefreshPolicy.Mode.ALWAYS,
                0,
                FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                0);
        check(startupSuperseded.getMode() == FutureEntryRefreshPolicy.Mode.ALWAYS,
                "a resume refresh cannot weaken a pending startup post");

        FutureEntryRefreshPolicy.Request mutationSuperseded = FutureEntryRefreshPolicy.merge(
                true,
                FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                4,
                FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                5);
        check(mutationSuperseded.getMode()
                        == FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                "coalesced mutations preserve boundary behavior");
        check(mutationSuperseded.getBaseline() == 4,
                "coalesced mutations retain the earliest unobserved count");

        FutureEntryRefreshPolicy.Request mutationThenResume = FutureEntryRefreshPolicy.merge(
                true,
                FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                7,
                FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                8);
        check(mutationThenResume.getBaseline() == 7,
                "a resume scan cannot erase a pending mutation boundary");

        FutureEntryRefreshPolicy.Request activeThenMutation = FutureEntryRefreshPolicy.merge(
                true,
                FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                2,
                FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                2);
        check(activeThenMutation.getMode()
                        == FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                "a mutation strengthens an in-flight active-only refresh");
        check(FutureEntryRefreshPolicy.mergePermissionRequest(true, true, false),
                "a resume coalescing behind a future mutation preserves permission intent");
        check(!FutureEntryRefreshPolicy.mergePermissionRequest(false, true, false),
                "an idle worker accepts the incoming permission intent");
        check(FutureEntryRefreshPolicy.shouldMergeExistingIntent(false, 11L, 10L),
                "a completed but unacknowledged request retains its posting intent");
        FutureEntryRefreshPolicy.Request completedThenSuperseded =
                FutureEntryRefreshPolicy.merge(
                        FutureEntryRefreshPolicy.shouldMergeExistingIntent(
                                false, 11L, 10L),
                        FutureEntryRefreshPolicy.Mode.ALWAYS,
                        0,
                        FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                        0);
        check(completedThenSuperseded.getMode() == FutureEntryRefreshPolicy.Mode.ALWAYS,
                "a new scan cannot overwrite an unconsumed completed ALWAYS result");
        check(!FutureEntryRefreshPolicy.shouldMergeExistingIntent(false, 11L, 11L),
                "an acknowledged idle completion does not affect a later request");
        check(FutureEntryRefreshPolicy.shouldClaimCompletion(false, 12L, 11L, 11L),
                "an idle unconsumed completion can be atomically claimed");
        check(!FutureEntryRefreshPolicy.shouldClaimCompletion(true, 12L, 11L, 11L),
                "a newer running scan prevents consumption of its inherited predecessor");
        check(!FutureEntryRefreshPolicy.shouldClaimCompletion(false, 12L, 11L, 12L),
                "an acknowledged completion cannot be claimed twice");

        System.out.println("Future-entry refresh-policy self-test passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
