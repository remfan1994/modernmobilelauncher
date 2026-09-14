import vip.thatiam.mml.MirrorIndexPolicy;

public final class MirrorIndexPolicySelfTest {
    public static void main(String[] arguments) {
        check(MirrorIndexPolicy.isFramedHeader("MML-MIRROR-4"), "v4 header recognized");
        check(MirrorIndexPolicy.isLegacyHeader("MML-MIRROR-3"), "v3 header recognized");
        check(!MirrorIndexPolicy.isComplete(true, false, -1, 0, false),
                "header-only framed index is truncated");
        check(!MirrorIndexPolicy.isComplete(true, true, 2, 1, false),
                "mismatched trailer count is invalid");
        check(MirrorIndexPolicy.isComplete(true, true, 1, 1, false),
                "matching framed trailer is complete");
        check(!MirrorIndexPolicy.isComplete(false, false, -1, 0, false),
                "header-only legacy index is invalid");
        check(MirrorIndexPolicy.isComplete(false, false, -1, 1, true),
                "complete legacy index retains README ownership proof");
        check(MirrorIndexPolicy.preferPending(true, true),
                "complete pending index supersedes old complete final");
        check(MirrorIndexPolicy.preferPending(true, false),
                "complete pending index heals a truncated final");
        check(!MirrorIndexPolicy.preferPending(false, true),
                "complete final wins when pending is truncated");
        System.out.println("Mirror index policy self-test passed.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
