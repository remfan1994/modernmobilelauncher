package vip.thatiam.mml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded page of a closed session; full durable files remain untouched. */
public final class JournalArchivePage {
    private final JournalSession metadata;
    private final JournalArchiveCursor cursor;
    private final JournalArchiveCursor previousCursor;
    private final JournalArchiveCursor nextCursor;
    private final List<JournalEvent> events;
    private final List<JournalNote> legacyNotes;
    private final int oversizedRowsSkipped;
    private final int malformedRowsSkipped;

    public JournalArchivePage(
            JournalSession metadata,
            JournalArchiveCursor cursor,
            JournalArchiveCursor previousCursor,
            JournalArchiveCursor nextCursor,
            List<JournalEvent> events,
            List<JournalNote> legacyNotes,
            int oversizedRowsSkipped,
            int malformedRowsSkipped) {
        this.metadata = metadata;
        this.cursor = cursor;
        this.previousCursor = previousCursor;
        this.nextCursor = nextCursor;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.legacyNotes = Collections.unmodifiableList(new ArrayList<>(legacyNotes));
        this.oversizedRowsSkipped = oversizedRowsSkipped;
        this.malformedRowsSkipped = malformedRowsSkipped;
    }

    public JournalSession getMetadata() {
        return metadata;
    }

    public JournalArchiveCursor getCursor() {
        return cursor;
    }

    public JournalArchiveCursor getPreviousCursor() {
        return previousCursor;
    }

    public JournalArchiveCursor getNextCursor() {
        return nextCursor;
    }

    public List<JournalEvent> getEvents() {
        return events;
    }

    public List<JournalNote> getLegacyNotes() {
        return legacyNotes;
    }

    public int getOversizedRowsSkipped() {
        return oversizedRowsSkipped;
    }

    public int getMalformedRowsSkipped() {
        return malformedRowsSkipped;
    }
}
