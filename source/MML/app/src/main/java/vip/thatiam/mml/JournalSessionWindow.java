package vip.thatiam.mml;

/** Bounded recent-event view of an OPEN session for the long-lived launcher UI. */
public final class JournalSessionWindow {
    private final JournalSession session;
    private final boolean earlierEventsOmitted;

    public JournalSessionWindow(JournalSession session, boolean earlierEventsOmitted) {
        this.session = session;
        this.earlierEventsOmitted = earlierEventsOmitted;
    }

    public JournalSession getSession() {
        return session;
    }

    public boolean hasEarlierEventsOmitted() {
        return earlierEventsOmitted;
    }
}
