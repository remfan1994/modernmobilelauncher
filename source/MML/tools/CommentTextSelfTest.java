package vip.thatiam.mml;

import java.util.List;

public final class CommentTextSelfTest {
    public static void main(String[] arguments) {
        check(CommentText.forDisplay("plan").equals("# plan"), "single line prefix");
        check(CommentText.forDisplay("# plan").equals("# plan"), "idempotent prefix");
        check(CommentText.forDisplay("first\nsecond\n").equals("# first\n# second\n#"),
                "each logical line resembles a code Comment");
        check(CommentText.hasPayload(" # appointment "), "prefixed payload accepted");
        check(!CommentText.hasPayload("# \n #\t"), "prefix-only document is empty");

        String token = CommentText.appShortcutToken("vip.thatiam.nanoweb");
        check(token.equals("app:vip.thatiam.nanoweb"), "app shortcut token");
        List<CommentText.AppShortcut> shortcuts = CommentText.findAppShortcuts(
                "Open app:vip.thatiam.nanoweb, not app:x or https://app:example.com");
        check(shortcuts.size() == 1, "strict application package token detection");
        check(shortcuts.get(0).getPackageName().equals("vip.thatiam.nanoweb"),
                "application package retained");

        boolean rejected = false;
        try {
            CommentText.appShortcutToken("intent://unsafe");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "arbitrary intent syntax rejected");
        System.out.println("CommentText self-test passed.");
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
