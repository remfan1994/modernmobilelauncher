package vip.thatiam.mml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

/** Digest identities must remain identical across the allocation optimization. */
public final class EntryHashTest {
  public static void main(String[] args) throws Exception {
    int checks = 0;
    check(
        Entry.hash("").equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    checks++;
    check(
        Entry.hash("abc")
            .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    checks++;
    String[] values = {"\u0000", "\ud83d\ude00\u0633\u0644\u0627\u0645", "\ud800", "\udc00"};
    for (String text : values) {
      check(Entry.hash(text).equals(oldHash(text)));
      checks++;
    }
    Random random = new Random(203);
    for (int i = 0; i < 256; i++) {
      char[] text = new char[i * 17];
      for (int j = 0; j < text.length; j++) text[j] = (char) random.nextInt(65536);
      String value = new String(text);
      check(Entry.hash(value).equals(oldHash(value)));
      checks++;
    }
    System.out.println("EntryHashTest: " + checks + " checks passed");
  }

  private static String oldHash(String text) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder(64);
    for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  private static void check(boolean condition) {
    if (!condition) throw new AssertionError("Digest identity changed");
  }
}
