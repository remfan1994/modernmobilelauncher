import vip.thatiam.mml.MirrorFilePolicy;

public final class MirrorFilePolicySelfTest {
    public static void main(String[] arguments) {
        require(MirrorFilePolicy.canReuseFinal(
                false, "12:34", "12:34", 12L, 12L, false));
        require(MirrorFilePolicy.canReuseFinal(
                false, "12:34", "12:34", -1L, 12L, true));
        reject(MirrorFilePolicy.canReuseFinal(
                false, "12:34", "12:34", -1L, 12L, false));
        reject(MirrorFilePolicy.canReuseFinal(
                true, "12:34", "12:34", 12L, 12L, false));
        reject(MirrorFilePolicy.canReuseFinal(
                false, "12:35", "12:34", 12L, 12L, false));
        reject(MirrorFilePolicy.canReuseFinal(
                false, "12:34", "12:34", 9L, 12L, false));

        require(MirrorFilePolicy.canAppendEventTail(
                false, true, "session.events", 12L, 20L, 12L));
        require(MirrorFilePolicy.canAppendEventTail(
                false, true, "session.events.txt", 12L, 20L, 12L));
        reject(MirrorFilePolicy.canAppendEventTail(
                true, true, "session.events", 12L, 20L, 12L));
        reject(MirrorFilePolicy.canAppendEventTail(
                false, true, "session.meta", 12L, 20L, 12L));
        reject(MirrorFilePolicy.canAppendEventTail(
                false, true, "session.events", 12L, 20L, 13L));
        reject(MirrorFilePolicy.canAppendEventTail(
                false, false, "session.events", 12L, 20L, 12L));

        require(MirrorFilePolicy.mayManageExisting(true, false));
        require(MirrorFilePolicy.mayManageExisting(false, true));
        reject(MirrorFilePolicy.mayManageExisting(false, false));
        require(MirrorFilePolicy.mayRemoveStale(true, false));
        reject(MirrorFilePolicy.mayRemoveStale(false, false));
        reject(MirrorFilePolicy.mayRemoveStale(true, true));
        require(MirrorFilePolicy.mayRecoverUnindexedPending(true, false, false));
        require(MirrorFilePolicy.mayRecoverUnindexedPending(true, true, true));
        reject(MirrorFilePolicy.mayRecoverUnindexedPending(true, true, false));
        reject(MirrorFilePolicy.mayRecoverUnindexedPending(false, false, false));
        System.out.println("Mirror file policy self-test passed.");
    }

    private static void require(boolean value) {
        if (!value) {
            throw new AssertionError("Expected policy decision to be true.");
        }
    }

    private static void reject(boolean value) {
        if (value) {
            throw new AssertionError("Expected policy decision to be false.");
        }
    }
}
