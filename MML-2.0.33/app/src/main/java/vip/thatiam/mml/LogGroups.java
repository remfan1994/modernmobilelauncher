package vip.thatiam.mml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A compact view of a page. This never changes, discards, reorders, or persists journal entries.
 */
public final class LogGroups {
  public static final long WINDOW_MS = 5000;

  private LogGroups() {}

  /** Input is in display order. A page boundary deliberately remains a group boundary. */
  public static List<Group> group(List<Entry> rows) {
    if (rows.isEmpty()) return Collections.emptyList();
    List<Group> result = new ArrayList<>();
    List<Entry> current = new ArrayList<>();
    for (Entry entry : rows) {
      if (!current.isEmpty() && !canAppend(current, entry)) {
        result.add(new Group(current));
        current = new ArrayList<>();
      }
      current.add(entry);
    }
    result.add(new Group(current));
    return Collections.unmodifiableList(result);
  }

  private static boolean canAppend(List<Entry> current, Entry next) {
    Entry first = current.get(0), last = current.get(current.size() - 1);
    if (!nativeDevice(first)
        || !nativeDevice(next)
        || !first.session.equals(next.session)
        || !first.day.equals(next.day)
        || !first.zone.equals(next.zone)
        || next.timeMs < last.timeMs) return false;
    long elapsed = next.timeMs - first.timeMs;
    // The lower bound also rejects signed overflow in imported timestamps.
    if (elapsed < 0 || elapsed > WINDOW_MS) return false;
    if (wake(first) && wake(next)) {
      // A new screen-on after user-present starts another wake, even if screen-off is absent.
      return !("USER_PRESENT".equals(last.type) && "SCREEN_ON".equals(next.type));
    }
    return repeatable(first.type)
        && first.type.equals(next.type)
        && first.title.equals(next.title)
        && first.body.equals(next.body);
  }

  private static boolean nativeDevice(Entry entry) {
    return "DEVICE".equals(entry.kind) && "Android".equals(entry.source);
  }

  private static boolean wake(Entry entry) {
    return ("SCREEN_ON".equals(entry.type) || "USER_PRESENT".equals(entry.type))
        && entry.body.isEmpty();
  }

  private static boolean repeatable(String type) {
    switch (type) {
      case "SCREEN_OFF":
      case "AIRPLANE_MODE":
      case "HEADSET_PLUG":
      case "RINGER_MODE_CHANGED":
      case "battery":
      case "network":
        return true;
      default:
        return false;
    }
  }

  public static final class Group {
    public final List<Entry> entries;
    public final Entry first, last;

    private Group(List<Entry> entries) {
      this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
      first = this.entries.get(0);
      last = this.entries.get(this.entries.size() - 1);
    }

    public int size() {
      return entries.size();
    }

    /** Appending a live event to this group does not change its ListView identity. */
    public long stableId() {
      return first.rowId;
    }

    /** Distinct original headings in their original order; member details remain in entries. */
    public String heading() {
      Set<String> titles = new LinkedHashSet<>();
      for (Entry entry : entries) titles.add(entry.title.isEmpty() ? entry.type : entry.title);
      StringBuilder result = new StringBuilder();
      for (String title : titles) {
        if (result.length() > 0) result.append(" + ");
        result.append(title);
      }
      return result.toString();
    }
  }
}
