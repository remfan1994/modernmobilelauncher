package vip.thatiam.mml;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Reads newline-delimited UTF-8 without ever allocating an unbounded physical line. */
public final class BoundedUtf8LineReader implements Closeable {
    private final BufferedInputStream input;
    private final int maximumRetainedBytes;

    public BoundedUtf8LineReader(InputStream input, int maximumRetainedBytes) {
        if (input == null || maximumRetainedBytes < 1) {
            throw new IllegalArgumentException("A stream and positive line bound are required.");
        }
        this.input = new BufferedInputStream(input);
        this.maximumRetainedBytes = maximumRetainedBytes;
    }

    /** Returns null only at clean EOF; oversized rows are consumed and marked. */
    public Line readLine() throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(
                Math.min(maximumRetainedBytes, 8_192));
        long encodedBytes = 0L;
        boolean sawByte = false;
        boolean oversized = false;
        int value;
        while ((value = input.read()) >= 0) {
            if ((encodedBytes & 8_191L) == 0L && Thread.currentThread().isInterrupted()) {
                throw new IOException("Bounded line scan was canceled.");
            }
            sawByte = true;
            if (value == '\n') {
                break;
            }
            encodedBytes++;
            if (encodedBytes <= maximumRetainedBytes) {
                retained.write(value);
            } else {
                oversized = true;
            }
        }
        if (!sawByte && value < 0) {
            return null;
        }
        byte[] bytes = retained.toByteArray();
        int length = bytes.length;
        if (!oversized && length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new Line(
                new String(bytes, 0, length, StandardCharsets.UTF_8),
                oversized,
                encodedBytes);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    /** Reads one row from the current random-access position and leaves it after the delimiter. */
    public static Line readLine(RandomAccessFile input, int maximumRetainedBytes)
            throws IOException {
        if (input == null || maximumRetainedBytes < 1) {
            throw new IllegalArgumentException("A file and positive line bound are required.");
        }
        ByteArrayOutputStream retained = new ByteArrayOutputStream(
                Math.min(maximumRetainedBytes, 8_192));
        byte[] scan = new byte[8_192];
        long cursor = input.getFilePointer();
        long fileLength = input.length();
        if (cursor >= fileLength) {
            return null;
        }
        long encodedBytes = 0L;
        boolean oversized = false;
        boolean complete = false;
        while (cursor < fileLength && !complete) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Archive page load was canceled.");
            }
            input.seek(cursor);
            int count = input.read(scan, 0, (int) Math.min(scan.length, fileLength - cursor));
            if (count < 0) {
                break;
            }
            int contentCount = count;
            for (int index = 0; index < count; index++) {
                if (scan[index] == '\n') {
                    contentCount = index;
                    complete = true;
                    break;
                }
            }
            int retainedCapacity = Math.max(0, maximumRetainedBytes - retained.size());
            int retainedCount = Math.min(contentCount, retainedCapacity);
            if (retainedCount > 0) {
                retained.write(scan, 0, retainedCount);
            }
            encodedBytes += contentCount;
            if (contentCount > retainedCount) {
                oversized = true;
            }
            cursor += contentCount + (complete ? 1L : 0L);
        }
        input.seek(cursor);
        byte[] bytes = retained.toByteArray();
        int length = bytes.length;
        if (!oversized && length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new Line(
                new String(bytes, 0, length, StandardCharsets.UTF_8),
                oversized,
                encodedBytes);
    }

    public static final class Line {
        private final String retainedText;
        private final boolean oversized;
        private final long encodedBytes;

        private Line(String retainedText, boolean oversized, long encodedBytes) {
            this.retainedText = retainedText;
            this.oversized = oversized;
            this.encodedBytes = encodedBytes;
        }

        public String getRetainedText() {
            return retainedText;
        }

        public boolean isOversized() {
            return oversized;
        }

        public long getEncodedBytes() {
            return encodedBytes;
        }
    }
}
