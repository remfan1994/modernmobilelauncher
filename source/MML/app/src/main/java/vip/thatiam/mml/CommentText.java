package vip.thatiam.mml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure text rules for code-style Comments and inert application-shortcut tokens. */
public final class CommentText {
    private static final String PACKAGE_NAME =
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+";
    private static final Pattern COMPLETE_PACKAGE = Pattern.compile(PACKAGE_NAME);
    private static final Pattern APP_SHORTCUT = Pattern.compile(
            "(?<![A-Za-z0-9_/:])app:(" + PACKAGE_NAME + ")(?![A-Za-z0-9_.])");

    private CommentText() {
    }

    /** Adds one code-comment marker to every displayed logical line without changing storage. */
    public static String forDisplay(String storedText) {
        String value = storedText == null ? "" : storedText;
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder displayed = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                displayed.append('\n');
            }
            String line = lines[index];
            if (line.startsWith("#")) {
                displayed.append(line);
            } else if (line.isEmpty()) {
                displayed.append('#');
            } else {
                displayed.append("# ").append(line);
            }
        }
        return displayed.toString();
    }

    /** A document containing only whitespace and optional code-comment markers is empty. */
    public static boolean hasPayload(String text) {
        String value = text == null ? "" : text;
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            String candidate = line.trim();
            if (candidate.startsWith("#")) {
                candidate = candidate.substring(1).trim();
            }
            if (!candidate.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static String appShortcutToken(String packageName) {
        String value = packageName == null ? "" : packageName.trim();
        if (!COMPLETE_PACKAGE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Android application package name.");
        }
        return "app:" + value;
    }

    public static List<AppShortcut> findAppShortcuts(String text) {
        String value = text == null ? "" : text;
        Matcher matcher = APP_SHORTCUT.matcher(value);
        List<AppShortcut> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(new AppShortcut(matcher.start(), matcher.end(), matcher.group(1)));
        }
        return Collections.unmodifiableList(result);
    }

    public static final class AppShortcut {
        private final int start;
        private final int end;
        private final String packageName;

        private AppShortcut(int start, int end, String packageName) {
            this.start = start;
            this.end = end;
            this.packageName = packageName;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public String getPackageName() {
            return packageName;
        }
    }
}
