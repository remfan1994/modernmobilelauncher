import vip.thatiam.mml.PersistedGrantState;

public final class PersistedGrantStateSelfTest {
    public static void main(String[] arguments) {
        int read = 1;
        int write = 2;
        int required = read | write;
        check(PersistedGrantState.classify(-1, required) == PersistedGrantState.UNKNOWN,
                "enumeration failure remains unknown");
        check(PersistedGrantState.classify(0, required) == PersistedGrantState.NONE,
                "confirmed zero is none");
        check(PersistedGrantState.classify(read, required) == PersistedGrantState.PARTIAL,
                "read-only is partial");
        check(PersistedGrantState.classify(write, required) == PersistedGrantState.PARTIAL,
                "write-only is partial");
        check(PersistedGrantState.classify(required, required) == PersistedGrantState.FULL,
                "read/write is full");
        check(PersistedGrantState.classify(required | 4, required) == PersistedGrantState.FULL,
                "unrelated flags do not hide full access");
        System.out.println("Persisted grant state self-test passed.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
