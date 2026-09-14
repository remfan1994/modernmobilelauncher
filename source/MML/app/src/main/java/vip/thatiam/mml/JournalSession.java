package vip.thatiam.mml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of one MML launcher-process session. */
public final class JournalSession {
    private final String id;
    private final long bootIdentity;
    private final long startedEpochMillis;
    private final String startedZoneId;
    private final SessionStatus status;
    private final List<JournalEvent> events;
    private final List<JournalNote> dailyNotes;

    public JournalSession(
            String id,
            long bootIdentity,
            long startedEpochMillis,
            String startedZoneId,
            SessionStatus status,
            List<JournalEvent> events,
            List<JournalNote> dailyNotes) {
        this.id = id;
        this.bootIdentity = bootIdentity;
        this.startedEpochMillis = startedEpochMillis;
        this.startedZoneId = startedZoneId;
        this.status = status;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.dailyNotes = Collections.unmodifiableList(new ArrayList<>(dailyNotes));
    }

    public String getId() {
        return id;
    }

    public long getBootIdentity() {
        return bootIdentity;
    }

    public long getStartedEpochMillis() {
        return startedEpochMillis;
    }

    public String getStartedZoneId() {
        return startedZoneId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public List<JournalEvent> getEvents() {
        return events;
    }

    public List<JournalNote> getDailyNotes() {
        return dailyNotes;
    }
}
