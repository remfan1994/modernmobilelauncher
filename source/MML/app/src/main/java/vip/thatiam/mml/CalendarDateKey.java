package vip.thatiam.mml;

import java.util.Objects;

/** A date in one selected calendar system, independent of display formatting. */
public final class CalendarDateKey {
    private final CalendarSystem system;
    private final int extendedYear;
    private final int month;
    private final int day;
    private final boolean leapMonth;

    public CalendarDateKey(
            CalendarSystem system,
            int extendedYear,
            int month,
            int day,
            boolean leapMonth) {
        this.system = system;
        this.extendedYear = extendedYear;
        this.month = month;
        this.day = day;
        this.leapMonth = leapMonth;
    }

    public CalendarSystem getSystem() {
        return system;
    }

    public int getExtendedYear() {
        return extendedYear;
    }

    public int getMonth() {
        return month;
    }

    public int getDay() {
        return day;
    }

    public boolean isLeapMonth() {
        return leapMonth;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CalendarDateKey)) {
            return false;
        }
        CalendarDateKey key = (CalendarDateKey) other;
        return extendedYear == key.extendedYear
                && month == key.month
                && day == key.day
                && leapMonth == key.leapMonth
                && system == key.system;
    }

    @Override
    public int hashCode() {
        return Objects.hash(system, extendedYear, month, day, leapMonth);
    }
}

