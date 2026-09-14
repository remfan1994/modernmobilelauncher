package vip.thatiam.mml;

/** One legacy (MML 1.1 and earlier) local-date partition, now exposed read-only. */
public final class JournalNote {
    private final String partitionId;
    private final String localDate;
    private final long createdEpochMillis;
    private final String zoneId;
    private final String text;

    public JournalNote(
            String partitionId,
            String localDate,
            long createdEpochMillis,
            String zoneId,
            String text) {
        this.partitionId = partitionId;
        this.localDate = localDate;
        this.createdEpochMillis = createdEpochMillis;
        this.zoneId = zoneId;
        this.text = text;
    }

    public String getPartitionId() {
        return partitionId;
    }

    public String getLocalDate() {
        return localDate;
    }

    public long getCreatedEpochMillis() {
        return createdEpochMillis;
    }

    public String getZoneId() {
        return zoneId;
    }

    public String getText() {
        return text;
    }
}
