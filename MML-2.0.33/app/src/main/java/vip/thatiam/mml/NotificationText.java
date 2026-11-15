package vip.thatiam.mml;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;
import java.time.Instant;
import java.util.List;

/** Extracts text without inflating another application's RemoteViews or loading images. */
public final class NotificationText {
  public static final int LIMIT = 32768;
  private static final String TRUNCATED = "\n[Notification text shortened at 32768 characters]";

  private NotificationText() {}

  public static String title(Notification notification) {
    if (notification == null) return "Notification";
    String title = text(notification.extras, Notification.EXTRA_TITLE);
    if (title.isEmpty()) title = text(notification.extras, Notification.EXTRA_CONVERSATION_TITLE);
    return title.isEmpty() ? "Notification" : Entry.clip(title, 2048);
  }

  public static String read(Notification notification) {
    if (notification == null) return "[Notification content unavailable]";
    Text out = new Text();
    Bundle extras = notification.extras;
    addMessages(out, extras, Notification.EXTRA_MESSAGES, "Message");
    addMessages(out, extras, Notification.EXTRA_HISTORIC_MESSAGES, "Earlier message");
    out.add("Text", text(extras, Notification.EXTRA_TEXT));
    out.add("Expanded text", text(extras, Notification.EXTRA_BIG_TEXT));
    out.add("Expanded title", text(extras, Notification.EXTRA_TITLE_BIG));
    out.add("Conversation", text(extras, Notification.EXTRA_CONVERSATION_TITLE));
    out.add("Subtext", text(extras, Notification.EXTRA_SUB_TEXT));
    out.add("Summary", text(extras, Notification.EXTRA_SUMMARY_TEXT));
    out.add("Info", text(extras, Notification.EXTRA_INFO_TEXT));
    addLines(out, extras, Notification.EXTRA_TEXT_LINES, "Line");
    addLines(out, extras, Notification.EXTRA_REMOTE_INPUT_HISTORY, "Reply");
    out.add("Ticker", bounded(notification.tickerText));
    if (out.empty()) out.add("Content", "No text supplied by this notification.");
    try {
      if (extras != null && extras.containsKey(Notification.EXTRA_PROGRESS_MAX)) {
        out.add(
            "Progress",
            extras.getInt(Notification.EXTRA_PROGRESS)
                + "/"
                + extras.getInt(Notification.EXTRA_PROGRESS_MAX)
                + (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
                    ? " (indeterminate)"
                    : ""));
      }
    } catch (RuntimeException malformed) {
      out.add("Capture", "Progress could not be decoded.");
    }
    out.add("Channel", notification.getChannelId());
    out.add("Category", notification.category);
    out.add("Group", notification.getGroup());
    out.add("Flags", Integer.toString(notification.flags));
    return out.finish();
  }

  private static void addLines(Text out, Bundle extras, String key, String label) {
    try {
      CharSequence[] lines = extras == null ? null : extras.getCharSequenceArray(key);
      if (lines != null)
        for (CharSequence line : lines) {
          if (out.full()) break;
          out.add(label, bounded(line));
        }
    } catch (RuntimeException malformed) {
      out.add("Capture", "Notification text lines could not be decoded.");
    }
  }

  private static void addMessages(Text out, Bundle extras, String key, String label) {
    if (extras == null || out.full()) return;
    try {
      Parcelable[] bundles = extras.getParcelableArray(key);
      List<Notification.MessagingStyle.Message> messages =
          Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
      for (Notification.MessagingStyle.Message message : messages) {
        if (out.full()) break;
        Person sender = message.getSenderPerson();
        String who = sender == null ? "You" : bounded(sender.getName());
        String when =
            message.getTimestamp() > 0
                ? Instant.ofEpochMilli(message.getTimestamp()).toString()
                : "time unavailable";
        out.add(
            label + " (" + when + ", " + Entry.clip(who, 512) + ")", bounded(message.getText()));
        if (message.getDataMimeType() != null)
          out.add("Attachment type", message.getDataMimeType());
        if (message.getDataUri() != null)
          out.add("Attachment URI", message.getDataUri().toString());
      }
    } catch (RuntimeException malformed) {
      out.add("Capture", label + " list could not be decoded; other text is retained.");
    }
  }

  private static String text(Bundle extras, String key) {
    if (extras == null) return "";
    try {
      return bounded(extras.getCharSequence(key));
    } catch (RuntimeException malformed) {
      return "";
    }
  }

  private static String bounded(CharSequence value) {
    if (value == null) return "";
    int end = Math.min(value.length(), LIMIT);
    return value.subSequence(0, end).toString();
  }

  private static final class Text {
    private final StringBuilder value = new StringBuilder();
    private boolean shortened;

    boolean full() {
      return value.length() >= LIMIT - TRUNCATED.length();
    }

    boolean empty() {
      return value.length() == 0;
    }

    void add(String label, String content) {
      if (content == null || content.isEmpty()) return;
      int remaining = LIMIT - TRUNCATED.length() - value.length();
      if (remaining <= 0) {
        shortened = true;
        return;
      }
      String prefix = (value.length() == 0 ? "" : "\n") + label + ": ";
      append(prefix, remaining);
      remaining = LIMIT - TRUNCATED.length() - value.length();
      append(content, remaining);
    }

    private void append(String text, int remaining) {
      if (text.length() <= remaining) {
        value.append(text);
        return;
      }
      int end = Math.max(0, remaining);
      if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
      value.append(text, 0, end);
      shortened = true;
    }

    String finish() {
      if (shortened) value.append(TRUNCATED);
      return value.length() == 0 ? "[No text supplied by this notification]" : value.toString();
    }
  }
}
