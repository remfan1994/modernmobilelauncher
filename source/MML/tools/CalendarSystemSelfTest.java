package vip.thatiam.mml;

public final class CalendarSystemSelfTest {
    public static void main(String[] arguments) {
        check(CalendarSystem.values().length == 5, "five projections");
        check(CalendarSystem.fromPreference("gregorian") == CalendarSystem.GREGORIAN,
                "Gregorian preference");
        check(CalendarSystem.fromPreference("hebrew") == CalendarSystem.HEBREW,
                "Hebrew preference");
        check(CalendarSystem.fromPreference("islamic_civil") == CalendarSystem.ISLAMIC_CIVIL,
                "Islamic civil preference");
        check(CalendarSystem.fromPreference("islamic_umm_al_qura")
                        == CalendarSystem.ISLAMIC_UMM_AL_QURA,
                "Umm al-Qura preference");
        check(CalendarSystem.fromPreference("chinese") == CalendarSystem.CHINESE,
                "Chinese preference");
        check(CalendarSystem.fromPreference("invalid") == CalendarSystem.GREGORIAN,
                "safe fallback");
        System.out.println("CalendarSystem self-test passed.");
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
