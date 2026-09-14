package vip.thatiam.mml;

public final class MmlPresentationPolicySelfTest {
    public static void main(String[] arguments) {
        check(!MmlPresentationPolicy.useCalendarDayList(1.0f, 320), "320dp normal grid");
        check(!MmlPresentationPolicy.useCalendarDayList(1.29f, 320),
                "320dp below threshold grid");
        check(MmlPresentationPolicy.useCalendarDayList(1.3f, 320),
                "320dp 130 percent list");
        check(MmlPresentationPolicy.useCalendarDayList(1.3f, 360),
                "360dp 130 percent list");
        check(!MmlPresentationPolicy.useCalendarDayList(1.3f, 600),
                "600dp 130 percent grid");
        check(!MmlPresentationPolicy.useCalendarDayList(1.49f, 600),
                "wide below global threshold");
        check(MmlPresentationPolicy.useCalendarDayList(1.5f, 360),
                "large font list");
        check(MmlPresentationPolicy.useCalendarDayList(2.0f, 320),
                "200 percent list");
        check(MmlPresentationPolicy.useCalendarDayList(1.0f, 319),
                "narrow screen list");

        check(MmlPresentationPolicy.isCalendarBrightnessGesture(48f, 4f, 48f),
                "horizontal gesture accepted at threshold");
        check(MmlPresentationPolicy.isCalendarBrightnessGesture(-80f, 20f, 48f),
                "leftward horizontal gesture accepted");
        check(!MmlPresentationPolicy.isCalendarBrightnessGesture(47f, 0f, 48f),
                "short movement remains a tap or scroll");
        check(!MmlPresentationPolicy.isCalendarBrightnessGesture(80f, 80f, 48f),
                "equal diagonal movement remains vertical interaction");
        check(!MmlPresentationPolicy.isCalendarBrightnessGesture(30f, 80f, 10f),
                "vertically dominant movement remains scrolling");

        check(MmlPresentationPolicy.calendarBrightnessPercent(50, 0f, 400f) == 50,
                "zero travel preserves brightness");
        check(MmlPresentationPolicy.calendarBrightnessPercent(1, 300f, 400f) == 100,
                "75 percent rightward travel spans full brightness range");
        check(MmlPresentationPolicy.calendarBrightnessPercent(100, -300f, 400f) == 1,
                "75 percent leftward travel spans full brightness range");
        check(MmlPresentationPolicy.calendarBrightnessPercent(50, 75f, 400f) == 75,
                "quarter full-range travel adds 25 percentage points");
        check(MmlPresentationPolicy.calendarBrightnessPercent(50, -75f, 400f) == 25,
                "leftward quarter travel subtracts 25 percentage points");
        check(MmlPresentationPolicy.calendarBrightnessPercent(75, 600f, 400f) == 100,
                "rightward brightness clamps at 100");
        check(MmlPresentationPolicy.calendarBrightnessPercent(25, -600f, 400f) == 1,
                "leftward brightness clamps at one");
        check(MmlPresentationPolicy.calendarBrightnessPercent(-50, 0f, 400f) == 1,
                "invalid low starting brightness clamps");
        check(MmlPresentationPolicy.calendarBrightnessPercent(150, 0f, 400f) == 100,
                "invalid high starting brightness clamps");

        boolean rejectedNegativeThreshold = false;
        try {
            MmlPresentationPolicy.isCalendarBrightnessGesture(100f, 0f, -1f);
        } catch (IllegalArgumentException expected) {
            rejectedNegativeThreshold = true;
        }
        check(rejectedNegativeThreshold, "negative gesture threshold rejected");

        boolean rejectedZeroWidth = false;
        try {
            MmlPresentationPolicy.calendarBrightnessPercent(50, 10f, 0f);
        } catch (IllegalArgumentException expected) {
            rejectedZeroWidth = true;
        }
        check(rejectedZeroWidth, "zero screen width rejected");

        System.out.println("MML presentation policy self-test passed.");
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
