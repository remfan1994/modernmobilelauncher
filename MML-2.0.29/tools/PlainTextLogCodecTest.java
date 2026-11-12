package vip.thatiam.mml;

public final class PlainTextLogCodecTest {
  private static int checks;
  public static void main(String[] args) {
    String body = "Plain text stays plain.\nPackage-like text: vip.thatiam.rfs\nUnicode: \u2605";
    Entry e = Entry.user(body).withIdentity("test-user-entry");
    String encoded = PlainTextLogCodec.encode(e);
    check(PlainTextLogCodec.FORMAT_VERSION == 1, "format version");
    check(encoded.startsWith("MML LOG RECORD 1\n"), "header");
    check(encoded.contains("Kind: USER\n"), "kind");
    String[] ordered = {
      "UID: ", "Time: ", "Time-Millis: ", "Captured-Millis: ", "Date: ", "Timezone: ",
      "Session: ", "Kind: ", "Source: ", "Type: ", "Title: ", "Notification-Key-Hash: ",
      "Body-Characters: "
    };
    int previous = -1;
    for (String field : ordered) {
      int at = encoded.indexOf("\n" + field);
      check(at > previous, "stable field order: " + field);
      previous = at;
    }
    check(encoded.contains("Body-Characters: " + body.length()), "body length");
    check(encoded.contains("\n\n" + body + "\n\nEND MML LOG RECORD"), "body is literal plaintext");
    check(!encoded.contains("{\""), "not JSON");
    System.out.println("PlainTextLogCodecTest: " + checks + " checks passed");
  }
  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }
}
