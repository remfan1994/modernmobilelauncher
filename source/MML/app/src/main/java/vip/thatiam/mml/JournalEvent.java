package vip.thatiam.mml;

/** One immutable machine-written event in a launcher session. */
public final class JournalEvent {
    private final long epochMillis;
    private final String zoneId;
    private final String type;
    private final String detail;

    public JournalEvent(long epochMillis, String zoneId, String type, String detail) {
        this.epochMillis = epochMillis;
        this.zoneId = zoneId;
        this.type = type;
        this.detail = detail;
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    public String getZoneId() {
        return zoneId;
    }

    public String getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }
}

