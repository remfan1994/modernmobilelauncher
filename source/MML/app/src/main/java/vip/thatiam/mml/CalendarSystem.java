package vip.thatiam.mml;

/** Calendar projections offered by the MML month table. */
public enum CalendarSystem {
    GREGORIAN("gregorian", "Gregorian"),
    HEBREW("hebrew", "Hebrew"),
    ISLAMIC_CIVIL("islamic_civil", "Islamic civil"),
    ISLAMIC_UMM_AL_QURA("islamic_umm_al_qura", "Islamic Umm al-Qura"),
    CHINESE("chinese", "Chinese");

    private final String preferenceValue;
    private final String displayName;

    CalendarSystem(String preferenceValue, String displayName) {
        this.preferenceValue = preferenceValue;
        this.displayName = displayName;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CalendarSystem fromPreference(String value) {
        for (CalendarSystem system : values()) {
            if (system.preferenceValue.equals(value)) {
                return system;
            }
        }
        return GREGORIAN;
    }
}
