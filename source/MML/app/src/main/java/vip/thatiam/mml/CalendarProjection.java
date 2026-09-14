package vip.thatiam.mml;

import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;
import android.icu.util.GregorianCalendar;
import android.icu.util.HebrewCalendar;
import android.icu.util.IslamicCalendar;
import android.icu.util.TimeZone;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Converts canonical instants to one of MML's selectable month projections. */
public final class CalendarProjection {
    private CalendarProjection() {
    }

    public static CalendarDateKey dateKey(
            CalendarSystem system,
            long epochMillis,
            String zoneId) {
        Calendar calendar = newCalendar(system, zoneId);
        calendar.setTimeInMillis(epochMillis);
        return keyFromCalendar(system, calendar);
    }

    /** Projects an intended Gregorian civil date at local noon in its recorded timezone. */
    public static CalendarDateKey dateKeyForGregorianLocalDate(
            CalendarSystem system,
            String localDate,
            String zoneId) {
        long representative = LocalDate.parse(localDate)
                .atTime(12, 0)
                .atZone(ZoneId.of(zoneId))
                .toInstant()
                .toEpochMilli();
        return dateKey(system, representative, zoneId);
    }

    /** Converts a selected projected day cell into MML's canonical Gregorian date identity. */
    public static String gregorianLocalDate(long representativeEpochMillis, String zoneId) {
        return Instant.ofEpochMilli(representativeEpochMillis)
                .atZone(ZoneId.of(zoneId))
                .toLocalDate()
                .toString();
    }

    public static MonthGrid monthGrid(
            CalendarSystem system,
            long anchorEpochMillis,
            String zoneId) {
        Calendar first = newCalendar(system, zoneId);
        first.setTimeInMillis(anchorEpochMillis);
        first.set(Calendar.DAY_OF_MONTH, 1);
        first.set(Calendar.HOUR_OF_DAY, 12);
        first.set(Calendar.MINUTE, 0);
        first.set(Calendar.SECOND, 0);
        first.set(Calendar.MILLISECOND, 0);

        int leadingBlankCount = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int maximumDay = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        List<DayCell> days = new ArrayList<>();
        for (int day = 1; day <= maximumDay; day++) {
            Calendar value = (Calendar) first.clone();
            value.set(Calendar.DAY_OF_MONTH, day);
            days.add(new DayCell(day, keyFromCalendar(system, value), value.getTimeInMillis()));
        }
        return new MonthGrid(
                title(system, first),
                leadingBlankCount,
                Collections.unmodifiableList(days));
    }

    public static long shiftMonth(
            CalendarSystem system,
            long anchorEpochMillis,
            String zoneId,
            int amount) {
        Calendar calendar = newCalendar(system, zoneId);
        calendar.setTimeInMillis(anchorEpochMillis);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.add(Calendar.MONTH, amount);
        return calendar.getTimeInMillis();
    }

    private static Calendar newCalendar(CalendarSystem system, String zoneId) {
        TimeZone timeZone = TimeZone.getTimeZone(zoneId);
        Locale locale = Locale.getDefault();
        if (system == CalendarSystem.HEBREW) {
            return new HebrewCalendar(timeZone, locale);
        }
        if (system == CalendarSystem.ISLAMIC_CIVIL) {
            IslamicCalendar calendar = new IslamicCalendar(timeZone, locale);
            calendar.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_CIVIL);
            return calendar;
        }
        if (system == CalendarSystem.ISLAMIC_UMM_AL_QURA) {
            IslamicCalendar calendar = new IslamicCalendar(timeZone, locale);
            calendar.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            return calendar;
        }
        if (system == CalendarSystem.CHINESE) {
            return new ChineseCalendar(timeZone, locale);
        }
        return new GregorianCalendar(timeZone, locale);
    }

    private static CalendarDateKey keyFromCalendar(CalendarSystem system, Calendar calendar) {
        boolean leap = system == CalendarSystem.CHINESE
                && calendar.get(Calendar.IS_LEAP_MONTH) == 1;
        return new CalendarDateKey(
                system,
                calendar.get(Calendar.EXTENDED_YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
                leap);
    }

    private static String title(CalendarSystem system, Calendar calendar) {
        int year = calendar.get(Calendar.EXTENDED_YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;
        if (system == CalendarSystem.CHINESE) {
            boolean leap = calendar.get(Calendar.IS_LEAP_MONTH) == 1;
            return "Chinese year " + year + ", " + (leap ? "leap month " : "month ") + month;
        }
        if (system == CalendarSystem.HEBREW) {
            return "Hebrew " + hebrewMonthName(calendar) + " " + year;
        }
        if (system == CalendarSystem.ISLAMIC_CIVIL) {
            return "Islamic civil " + year + "-" + twoDigits(month);
        }
        if (system == CalendarSystem.ISLAMIC_UMM_AL_QURA) {
            return "Islamic Umm al-Qura " + year + "-" + twoDigits(month);
        }
        return "Gregorian " + year + "-" + twoDigits(month);
    }

    private static String hebrewMonthName(Calendar calendar) {
        int month = calendar.get(Calendar.MONTH);
        if (month == HebrewCalendar.TISHRI) {
            return "Tishri";
        }
        if (month == HebrewCalendar.HESHVAN) {
            return "Heshvan";
        }
        if (month == HebrewCalendar.KISLEV) {
            return "Kislev";
        }
        if (month == HebrewCalendar.TEVET) {
            return "Tevet";
        }
        if (month == HebrewCalendar.SHEVAT) {
            return "Shevat";
        }
        if (month == HebrewCalendar.ADAR_1) {
            return "Adar I";
        }
        if (month == HebrewCalendar.ADAR) {
            return hebrewHasAdarI(calendar) ? "Adar II" : "Adar";
        }
        if (month == HebrewCalendar.NISAN) {
            return "Nisan";
        }
        if (month == HebrewCalendar.IYAR) {
            return "Iyar";
        }
        if (month == HebrewCalendar.SIVAN) {
            return "Sivan";
        }
        if (month == HebrewCalendar.TAMUZ) {
            return "Tammuz";
        }
        if (month == HebrewCalendar.AV) {
            return "Av";
        }
        if (month == HebrewCalendar.ELUL) {
            return "Elul";
        }
        return "month " + (month + 1);
    }

    /** Uses ICU month arithmetic because non-leap Hebrew years omit Adar I. */
    private static boolean hebrewHasAdarI(Calendar calendar) {
        Calendar probe = (Calendar) calendar.clone();
        probe.set(Calendar.DAY_OF_MONTH, 1);
        probe.set(Calendar.MONTH, HebrewCalendar.SHEVAT);
        probe.add(Calendar.MONTH, 1);
        return probe.get(Calendar.MONTH) == HebrewCalendar.ADAR_1;
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    public static final class DayCell {
        private final int displayedDay;
        private final CalendarDateKey key;
        private final long representativeEpochMillis;

        private DayCell(int displayedDay, CalendarDateKey key, long representativeEpochMillis) {
            this.displayedDay = displayedDay;
            this.key = key;
            this.representativeEpochMillis = representativeEpochMillis;
        }

        public int getDisplayedDay() {
            return displayedDay;
        }

        public CalendarDateKey getKey() {
            return key;
        }

        public long getRepresentativeEpochMillis() {
            return representativeEpochMillis;
        }
    }

    public static final class MonthGrid {
        private final String title;
        private final int leadingBlankCount;
        private final List<DayCell> days;

        private MonthGrid(String title, int leadingBlankCount, List<DayCell> days) {
            this.title = title;
            this.leadingBlankCount = leadingBlankCount;
            this.days = days;
        }

        public String getTitle() {
            return title;
        }

        public int getLeadingBlankCount() {
            return leadingBlankCount;
        }

        public List<DayCell> getDays() {
            return days;
        }
    }
}
