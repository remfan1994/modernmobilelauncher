package vip.thatiam.mml;

import java.util.Objects;

/** Stable byte cursor for one bounded archived-session page. */
public final class JournalArchiveCursor {
    public enum Section {
        EVENTS,
        LEGACY_NOTES
    }

    private final Section section;
    private final long offset;

    public JournalArchiveCursor(Section section, long offset) {
        this.section = Objects.requireNonNull(section);
        if (offset < 0L) {
            throw new IllegalArgumentException("Archive cursor offset cannot be negative.");
        }
        this.offset = offset;
    }

    public static JournalArchiveCursor first() {
        return new JournalArchiveCursor(Section.EVENTS, 0L);
    }

    public Section getSection() {
        return section;
    }

    public long getOffset() {
        return offset;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof JournalArchiveCursor)) {
            return false;
        }
        JournalArchiveCursor cursor = (JournalArchiveCursor) other;
        return section == cursor.section && offset == cursor.offset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(section, offset);
    }
}
