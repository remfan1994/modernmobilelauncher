import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MmlThemeSelfTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 8) {
            throw new AssertionError(
                    "Pass light styles/colors, night styles/colors, and four launcher icon sources.");
        }
        String lightStyle = Files.readString(Path.of(arguments[0]));
        String lightColors = Files.readString(Path.of(arguments[1]));
        String nightStyle = Files.readString(Path.of(arguments[2]));
        String nightColors = Files.readString(Path.of(arguments[3]));

        require(lightStyle, "Theme.Material.Light.NoActionBar");
        require(nightStyle, "Theme.Material.NoActionBar");
        require(lightStyle, "@color/mml_background");
        require(nightStyle, "@color/mml_background");
        require(lightStyle, "forceDarkAllowed\">false");
        require(nightStyle, "forceDarkAllowed\">false");
        require(lightStyle, "windowLightStatusBar\">true");
        require(nightStyle, "windowLightStatusBar\">false");
        if (count(lightStyle, "android:windowFullscreen\">true") < 2
                || count(nightStyle, "android:windowFullscreen\">true") < 2) {
            throw new AssertionError(
                    "Main and dialog themes must both suppress status-tray startup flashes.");
        }
        reject(lightStyle, "PopupMenu");
        reject(nightStyle, "PopupMenu");
        require(lightStyle, "Theme.MML.Dialog");
        require(lightStyle, "android:textSize\">20sp");
        require(lightStyle, "android:textStyle\">bold");
        require(lightStyle, "android:textColorLink\">@color/mml_link");
        require(nightStyle, "android:textColorLink\">@color/mml_link");
        String[] names = {
                "mml_background", "mml_surface", "mml_field", "mml_primary",
                "mml_secondary", "mml_rule", "mml_accent", "mml_link",
                "mml_calendar_date_text", "mml_calendar_entry_total_text",
                "mml_today_background", "mml_today_text",
                "mml_today_date_text", "mml_today_entry_total_text"
        };
        for (String name : names) {
            require(lightColors, "name=\"" + name + "\"");
            require(nightColors, "name=\"" + name + "\"");
        }
        reject(lightColors, "#000000");
        reject(lightColors, "#FFFFFF");
        reject(nightColors, "#000000");
        reject(nightColors, "#FFFFFF");
        checkReversedTodayPalette(lightColors, "light");
        checkReversedTodayPalette(nightColors, "night");
        checkCalendarTextHierarchy(lightColors, "light");
        checkCalendarTextHierarchy(nightColors, "night");
        checkCalendarInversionPairs(lightColors, nightColors);
        for (int index = 4; index < arguments.length; index++) {
            String icon = Files.readString(Path.of(arguments[index]));
            checkNoPureExtremes(icon);
        }
        Path notificationIconPath = Path.of(arguments[4]).resolveSibling("ic_notification.xml");
        String notificationIcon = Files.readString(notificationIconPath);
        require(notificationIcon, "<vector");
        require(notificationIcon, "android:pathData");
        checkNoPureExtremes(notificationIcon);
        System.out.println("MML theme self-test passed.");
    }

    private static void checkReversedTodayPalette(String colors, String paletteName) {
        String background = colorValue(colors, "mml_background");
        String primary = colorValue(colors, "mml_primary");
        String todayBackground = colorValue(colors, "mml_today_background");
        String todayText = colorValue(colors, "mml_today_text");
        if (!todayBackground.equalsIgnoreCase(primary)) {
            throw new AssertionError(paletteName + " today background must invert to primary");
        }
        if (!todayText.equalsIgnoreCase(background)) {
            throw new AssertionError(paletteName + " today text must invert to background");
        }
        if (todayBackground.equalsIgnoreCase(todayText)) {
            throw new AssertionError(paletteName + " today colors must remain distinguishable");
        }
    }

    private static void checkCalendarTextHierarchy(String colors, String paletteName) {
        String surface = colorValue(colors, "mml_surface");
        String date = colorValue(colors, "mml_calendar_date_text");
        String total = colorValue(colors, "mml_calendar_entry_total_text");
        String todayBackground = colorValue(colors, "mml_today_background");
        String todayDate = colorValue(colors, "mml_today_date_text");
        String todayTotal = colorValue(colors, "mml_today_entry_total_text");
        requireDarker(date, total, paletteName + " calendar date");
        requireDarker(todayDate, todayTotal, paletteName + " today date");
        requireGraySeparation(date, total, paletteName + " calendar date and total");
        requireGraySeparation(
                todayDate, todayTotal, paletteName + " today date and total");
        requireLargeTextContrast(date, surface, paletteName + " calendar date");
        requireLargeTextContrast(total, surface, paletteName + " calendar total");
        requireLargeTextContrast(todayDate, todayBackground, paletteName + " today date");
        requireLargeTextContrast(todayTotal, todayBackground, paletteName + " today total");
    }

    private static void checkCalendarInversionPairs(String lightColors, String nightColors) {
        requireInverses(
                colorValue(lightColors, "mml_calendar_date_text"),
                colorValue(nightColors, "mml_calendar_entry_total_text"),
                "normal date extreme");
        requireInverses(
                colorValue(lightColors, "mml_calendar_entry_total_text"),
                colorValue(nightColors, "mml_calendar_date_text"),
                "normal date-total distinction");
        requireInverses(
                colorValue(lightColors, "mml_today_date_text"),
                colorValue(nightColors, "mml_today_entry_total_text"),
                "today date-total distinction");
        requireInverses(
                colorValue(lightColors, "mml_today_entry_total_text"),
                colorValue(nightColors, "mml_today_date_text"),
                "today total extreme");
    }

    private static void requireGraySeparation(String darker, String lighter, String name) {
        int darkerChannel = grayChannel(darker, name + " darker");
        int lighterChannel = grayChannel(lighter, name + " lighter");
        if (lighterChannel - darkerChannel < 50) {
            throw new AssertionError(name + " must differ by at least 50 grayscale levels");
        }
    }

    private static void requireInverses(String first, String second, String name) {
        int firstColor = rgbValue(first, name + " first");
        int secondColor = rgbValue(second, name + " second");
        if (((~firstColor) & 0xFFFFFF) != secondColor) {
            throw new AssertionError(name + " must be an exact grayscale inversion pair");
        }
    }

    private static int grayChannel(String color, String name) {
        int value = rgbValue(color, name);
        int red = (value >> 16) & 0xFF;
        int green = (value >> 8) & 0xFF;
        int blue = value & 0xFF;
        if (red != green || green != blue) {
            throw new AssertionError(name + " must be grayscale");
        }
        return red;
    }

    private static int rgbValue(String color, String name) {
        if (color.length() != 7 || color.charAt(0) != '#') {
            throw new AssertionError(name + " must use #RRGGBB");
        }
        return Integer.parseInt(color.substring(1), 16);
    }

    private static void requireDarker(String darker, String lighter, String name) {
        if (!(relativeLuminance(darker) < relativeLuminance(lighter))) {
            throw new AssertionError(name + " must be perceptually darker than its entry total");
        }
    }

    private static void requireLargeTextContrast(String foreground, String background, String name) {
        double first = relativeLuminance(foreground);
        double second = relativeLuminance(background);
        double ratio = (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
        if (ratio < 3.0) {
            throw new AssertionError(name + " contrast is below 3:1: " + ratio);
        }
    }

    private static double relativeLuminance(String color) {
        int red = Integer.parseInt(color.substring(1, 3), 16);
        int green = Integer.parseInt(color.substring(3, 5), 16);
        int blue = Integer.parseInt(color.substring(5, 7), 16);
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static String colorValue(String source, String name) {
        Pattern pattern = Pattern.compile(
                "<color\\s+name=\\\"" + Pattern.quote(name)
                        + "\\\"\\s*>\\s*(#[0-9A-Fa-f]{6,8})\\s*</color>");
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            throw new AssertionError("Missing literal color value: " + name);
        }
        return matcher.group(1);
    }

    private static void checkNoPureExtremes(String source) {
        reject(source, "#000000");
        reject(source, "#FFFFFF");
        reject(source, "#FF000000");
        reject(source, "#FFFFFFFF");
    }

    private static void require(String source, String token) {
        if (!source.contains(token)) {
            throw new AssertionError("Missing required token: " + token);
        }
    }

    private static void reject(String source, String token) {
        if (source.contains(token)) {
            throw new AssertionError("Forbidden token: " + token);
        }
    }

    private static int count(String source, String token) {
        int total = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            total++;
            offset += token.length();
        }
        return total;
    }
}
