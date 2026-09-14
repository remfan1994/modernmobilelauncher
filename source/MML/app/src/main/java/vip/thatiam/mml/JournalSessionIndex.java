package vip.thatiam.mml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight calendar membership for one journal session.
 *
 * <p>The index deliberately retains no event detail or legacy Comment text. Each observation is
 * one unique Gregorian civil date/timezone pair from the session, represented by an original
 * timestamp and timezone so every selectable calendar projection keeps its existing semantics.
 */
public final class JournalSessionIndex {
    private final String id;
    private final long startedEpochMillis;
    private final SessionStatus status;
    private final List<DateObservation> dateObservations;

    public JournalSessionIndex(
            String id,
            long startedEpochMillis,
            SessionStatus status,
            List<DateObservation> dateObservations) {
        this.id = id;
        this.startedEpochMillis = startedEpochMillis;
        this.status = status;
        this.dateObservations = Collections.unmodifiableList(
                new ArrayList<>(dateObservations));
    }

    public String getId() {
        return id;
    }

    public long getStartedEpochMillis() {
        return startedEpochMillis;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public List<DateObservation> getDateObservations() {
        return dateObservations;
    }

    /** One representative instant for a unique Gregorian civil date/timezone pair. */
    public static final class DateObservation {
        private final long epochMillis;
        private final String zoneId;

        public DateObservation(long epochMillis, String zoneId) {
            this.epochMillis = epochMillis;
            this.zoneId = zoneId;
        }

        public long getEpochMillis() {
            return epochMillis;
        }

        public String getZoneId() {
            return zoneId;
        }
    }
}
