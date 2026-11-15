package vip.thatiam.mml;

public final class UserEntryTest {
  public static void main(String[] args) {
    Entry shared = Entry.sharedText("remember this");
    check("USER".equals(shared.kind), "shared kind");
    check("shared_text".equals(shared.type), "shared type");
    check("Android share".equals(shared.source), "shared source");
    check("Shared text".equals(shared.title), "shared title");
    check("remember this".equals(shared.body), "shared body");

    Entry launch = Entry.appLaunch("vip.thatiam.rfs", "RFS");
    check("USER".equals(launch.kind), "launch kind");
    check("app_launch".equals(launch.type), "launch type");
    check("vip.thatiam.rfs".equals(launch.source), "launch package");
    check("App launch: RFS".equals(launch.title), "launch title");
    check(launch.body.isEmpty(), "launch body empty");
    System.out.println("UserEntryTest: 10 checks passed");
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
