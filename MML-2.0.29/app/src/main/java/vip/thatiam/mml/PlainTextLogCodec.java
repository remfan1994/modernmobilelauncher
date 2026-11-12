package vip.thatiam.mml;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Human-readable UTF-8 log records. No JSON, binary envelope, or encoded body text. */
public final class PlainTextLogCodec {
  public static final int FORMAT_VERSION = 1;
  private static final String HEADER = "MML LOG RECORD " + FORMAT_VERSION;
  private static final String END = "END MML LOG RECORD";
  private static final int MAX_BODY_CHARS = 2 * 1024 * 1024;

  private PlainTextLogCodec() {}

  public static String encode(Entry e) {
    StringBuilder out = new StringBuilder(Math.min(4096 + e.body.length(), MAX_BODY_CHARS));
    out.append(HEADER).append('\n');
    field(out, "UID", e.uid);
    field(out, "Time", readableTime(e));
    out.append("Time-Millis: ").append(e.timeMs).append('\n');
    out.append("Captured-Millis: ").append(e.capturedMs).append('\n');
    field(out, "Date", e.day);
    field(out, "Timezone", e.zone);
    field(out, "Session", e.session);
    field(out, "Kind", e.kind);
    field(out, "Source", e.source);
    field(out, "Type", e.type);
    field(out, "Title", e.title);
    field(out, "Notification-Key-Hash", e.notificationKeyHash);
    out.append("Body-Characters: ").append(e.body.length()).append("\n\n");
    out.append(e.body);
    out.append("\n\n").append(END).append("\n\n");
    return out.toString();
  }

  private static String readableTime(Entry e) {
    try {
      return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
          Instant.ofEpochMilli(e.timeMs).atZone(ZoneId.of(e.zone)));
    } catch (RuntimeException badZone) {
      return Instant.ofEpochMilli(e.timeMs).toString();
    }
  }

  private static void field(StringBuilder out, String name, String value) {
    out.append(name).append(": ").append(escape(value)).append('\n');
  }

  private static String escape(String value) {
    StringBuilder out = new StringBuilder(value == null ? 0 : value.length());
    if (value == null) return "";
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\') out.append("\\\\");
      else if (c == '\n') out.append("\\n");
      else if (c == '\r') out.append("\\r");
      else out.append(c);
    }
    return out.toString();
  }

}
