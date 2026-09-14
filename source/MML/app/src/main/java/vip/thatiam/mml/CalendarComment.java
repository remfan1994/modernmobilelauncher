package vip.thatiam.mml;

/** One editable Comments document attached to an intended civil date. */
public final class CalendarComment {
    private final String targetLocalDate;
    private final String targetZoneId;
    private final long createdEpochMillis;
    private final String createdZoneId;
    private final String text;

    public CalendarComment(
            String targetLocalDate,
            String targetZoneId,
            long createdEpochMillis,
            String createdZoneId,
            String text) {
        this.targetLocalDate = targetLocalDate;
        this.targetZoneId = targetZoneId;
        this.createdEpochMillis = createdEpochMillis;
        this.createdZoneId = createdZoneId;
        this.text = text;
    }

    public String getTargetLocalDate() {
        return targetLocalDate;
    }

    public String getTargetZoneId() {
        return targetZoneId;
    }

    public long getCreatedEpochMillis() {
        return createdEpochMillis;
    }

    public String getCreatedZoneId() {
        return createdZoneId;
    }

    public String getText() {
        return text;
    }
}
