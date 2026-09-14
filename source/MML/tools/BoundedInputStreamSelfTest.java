package vip.thatiam.mml;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public final class BoundedInputStreamSelfTest {
    private static final int INDEX_BYTE_LIMIT = 512 * 1024;

    public static void main(String[] arguments) throws Exception {
        byte[] exact = new byte[INDEX_BYTE_LIMIT];
        try (BoundedInputStream input = new BoundedInputStream(
                new ByteArrayInputStream(exact), INDEX_BYTE_LIMIT)) {
            check(input.readAllBytes().length == INDEX_BYTE_LIMIT,
                    "exactly 524,288 bytes must be accepted");
        }

        byte[] oversized = new byte[INDEX_BYTE_LIMIT + 1];
        boolean rejected = false;
        try (BoundedInputStream input = new BoundedInputStream(
                new ByteArrayInputStream(oversized), INDEX_BYTE_LIMIT)) {
            input.readAllBytes();
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "524,289 bytes must be rejected by the overflow probe");
        System.out.println("Bounded input stream self-test passed.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
