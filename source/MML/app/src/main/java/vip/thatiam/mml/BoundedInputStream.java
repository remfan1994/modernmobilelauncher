package vip.thatiam.mml;

import java.io.IOException;
import java.io.InputStream;

/** Input wrapper that accepts exactly its byte budget and probes once to reject any overflow. */
final class BoundedInputStream extends InputStream {
    private final InputStream delegate;
    private long remaining;

    BoundedInputStream(InputStream delegate, long maximumBytes) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        if (maximumBytes < 0L) {
            throw new IllegalArgumentException("The input byte limit cannot be negative.");
        }
        this.delegate = delegate;
        remaining = maximumBytes;
    }

    @Override
    public int read() throws IOException {
        if (remaining == 0L) {
            if (delegate.read() < 0) {
                return -1;
            }
            throw new IOException("An MML mirror metadata document exceeds its supported size.");
        }
        int value = delegate.read();
        if (value >= 0) {
            remaining--;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (offset < 0 || length < 0 || offset > buffer.length
                || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException("Invalid input-buffer range.");
        }
        if (length == 0) {
            return 0;
        }
        if (remaining == 0L) {
            return read();
        }
        int boundedLength = (int) Math.min(length, remaining);
        int count = delegate.read(buffer, offset, boundedLength);
        if (count > 0) {
            remaining -= count;
        }
        return count;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
