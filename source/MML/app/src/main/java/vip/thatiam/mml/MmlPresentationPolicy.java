package vip.thatiam.mml;

/** Pure layout and gesture decisions that protect MML from clipping and gesture ambiguity. */
public final class MmlPresentationPolicy {
    private static final float ACCESSIBLE_CALENDAR_LIST_SCALE = 1.5f;
    private static final float NARROW_CALENDAR_LIST_SCALE = 1.3f;
    public static final float BRIGHTNESS_FULL_RANGE_WIDTH_FRACTION = 0.75f;

    private MmlPresentationPolicy() {
    }

    /** Moves to a full-width list before large text can collide in seven narrow columns. */
    public static boolean useCalendarDayList(float fontScale, int widthDp) {
        return fontScale >= ACCESSIBLE_CALENDAR_LIST_SCALE
                || widthDp < 320
                || (fontScale >= NARROW_CALENDAR_LIST_SCALE && widthDp <= 360);
    }

    /** True only after a clear single-axis horizontal Calendar gesture has begun. */
    public static boolean isCalendarBrightnessGesture(
            float horizontalDistance,
            float verticalDistance,
            float minimumDistance) {
        if (minimumDistance < 0f) {
            throw new IllegalArgumentException("minimumDistance must not be negative");
        }
        float horizontalMagnitude = Math.abs(horizontalDistance);
        return horizontalMagnitude >= minimumDistance
                && horizontalMagnitude > Math.abs(verticalDistance);
    }

    /**
     * Maps a horizontal Calendar drag to system brightness. Moving through 75 percent of the
     * screen width changes the requested level by 100 percentage points.
     */
    public static int calendarBrightnessPercent(
            int startingPercent,
            float horizontalDistance,
            float screenWidth) {
        if (!(screenWidth > 0f) || Float.isInfinite(screenWidth)) {
            throw new IllegalArgumentException("screenWidth must be finite and positive");
        }
        int clampedStart = clampBrightnessPercent(startingPercent);
        float fullRangeDistance = screenWidth * BRIGHTNESS_FULL_RANGE_WIDTH_FRACTION;
        int percentagePointDelta = Math.round(horizontalDistance * 100f / fullRangeDistance);
        return clampBrightnessPercent(clampedStart + percentagePointDelta);
    }

    private static int clampBrightnessPercent(int percent) {
        return Math.max(1, Math.min(100, percent));
    }
}
