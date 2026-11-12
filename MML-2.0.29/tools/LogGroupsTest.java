package vip.thatiam.mml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Host checks against the production display grouping code and immutable Entry model. */
public final class LogGroupsTest {
  private static int checks;

  public static void main(String[] args) {
    check(LogGroups.group(Collections.emptyList()).isEmpty(), "Empty page stays empty");
    Entry on = entry(1, 1000, "DEVICE", "Android", "SCREEN_ON", "screen on", "");
    Entry present = entry(2, 2000, "DEVICE", "Android", "USER_PRESENT", "user present", "");
    LogGroups.Group wake = group(on, present).get(0);
    check(group(on, present).size() == 1, "Adjacent screen-on and user-present share one row");
    check(wake.size() == 2 && wake.first == on && wake.last == present, "Both raw events retained");
    check(wake.heading().equals("screen on + user present"), "Both distinct headings retained");
    check(wake.stableId() == on.rowId, "Live append preserves the first event identity");
    check(group(on, at(present, 6000)).size() == 1, "Five-second boundary is inclusive");
    check(group(on, at(present, 6001)).size() == 2, "Events beyond the window stay separate");
    check(
        group(on, at(present, 5000), at(present, 9000)).size() == 2,
        "A burst cannot extend its window by chaining nearby events");
    check(group(present, at(on, 3000)).size() == 2, "Next screen-on begins a new wake");
    check(
        group(on, at(on, 1500), present, at(present, 2500)).get(0).size() == 4,
        "Repeated wake broadcasts retain all members");
    check(
        group(present, at(present, 3000)).get(0).heading().equals("user present"),
        "Repeated heading is displayed once");
    check(group(on, at(present, 500)).size() == 2, "Clock reversal is a group boundary");

    Entry off = entry(3, 1500, "DEVICE", "Android", "SCREEN_OFF", "screen off", "");
    check(group(on, off, present).size() == 3, "Screen-off separates wake events");
    check(group(off, at(off, 1600)).get(0).size() == 2, "Identical screen-off bursts combine");

    Entry network = entry(4, 1000, "DEVICE", "Android", "network", "Network Wi-Fi", "validated");
    check(
        group(network, at(network, 2000)).get(0).size() == 2,
        "Identical known device status may combine");
    check(
        group(network, body(at(network, 2000), "not validated")).size() == 2,
        "Different network states stay separate");
    check(
        group(network, title(at(network, 2000), "Default network unavailable")).size() == 2,
        "Changed device heading stays separate");
    check(
        group(on, body(present, "Unexpected detail")).size() == 2,
        "Unexpected wake detail is not hidden in a combined row");

    for (String kind : new String[] {"USER", "NOTIFICATION", "SYSTEM", "APP", "SMS", "CALL"}) {
      Entry other = entry(10, 1000, kind, "Android", "SCREEN_ON", "same", "");
      check(group(other, at(other, 2000)).size() == 2, kind + " records stay separate");
    }
    for (String type :
        new String[] {
          "capture_gap", "collector_error", "notification_error", "session_startup",
              "home_reminder",
          "TIME_SET", "TIMEZONE_CHANGED", "DATE_CHANGED", "screen_on", "unknown"
        }) {
      Entry other = entry(10, 1000, "DEVICE", "Android", type, "same", "");
      check(group(other, at(other, 2000)).size() == 2, "Unlisted type stays separate: " + type);
    }
    check(group(on, source(present, "import")).size() == 2, "Different source stays separate");
    check(
        group(source(on, "import"), source(present, "import")).size() == 2,
        "Arbitrary imported device events cannot become wake groups");
    check(group(on, present.withSession("other-session")).size() == 2, "Session boundary retained");
    check(group(on, present.withDay("2026-11-10")).size() == 2, "Date boundary retained");
    check(group(on, zone(present, "America/Los_Angeles")).size() == 2, "Zone boundary retained");
    check(
        group(at(on, Long.MIN_VALUE), at(present, Long.MAX_VALUE)).size() == 2,
        "Timestamp subtraction overflow cannot combine remote dates");

    List<Entry> original = new ArrayList<>(Arrays.asList(on, present));
    List<LogGroups.Group> frozen = LogGroups.group(original);
    original.clear();
    check(frozen.get(0).size() == 2, "Groups do not alias the mutable input list");
    immutable(() -> frozen.clear(), "Group list is immutable");
    immutable(() -> frozen.get(0).entries.clear(), "Member list is immutable");

    Entry message = entry(5, 2100, "NOTIFICATION", "org.messages", "posted", "Sender", "message");
    List<Entry> page =
        Arrays.asList(
            on,
            present,
            message,
            at(on, 3000),
            at(present, 4000),
            at(off, 4500),
            at(network, 5000),
            at(network, 5100));
    checkSameRows(
        page, flatten(LogGroups.group(page)), "Mixed page preserves each raw record once");
    for (int cut = 1; cut < page.size(); cut++) {
      List<Entry> paged = new ArrayList<>();
      paged.addAll(flatten(LogGroups.group(page.subList(0, cut))));
      paged.addAll(flatten(LogGroups.group(page.subList(cut, page.size()))));
      checkSameRows(page, paged, "Page boundary preserves record identity and order at " + cut);
    }
    List<Entry> reversed = new ArrayList<>(page);
    Collections.reverse(reversed);
    checkSameRows(reversed, flatten(LogGroups.group(reversed)), "Grouping never reorders input");
    System.out.println("LogGroupsTest: " + checks + " checks passed");
  }

  private static List<LogGroups.Group> group(Entry... entries) {
    return LogGroups.group(Arrays.asList(entries));
  }

  private static List<Entry> flatten(List<LogGroups.Group> groups) {
    List<Entry> result = new ArrayList<>();
    for (LogGroups.Group group : groups) result.addAll(group.entries);
    return result;
  }

  private static void checkSameRows(List<Entry> expected, List<Entry> actual, String message) {
    check(expected.size() == actual.size(), message + " count");
    for (int i = 0; i < expected.size(); i++) {
      if (expected.get(i) != actual.get(i)) throw new AssertionError(message + " row " + i);
    }
    checks++;
  }

  private static Entry entry(
      long row, long time, String kind, String source, String type, String title, String body) {
    return new Entry(
        row,
        "uid-" + row,
        time,
        time,
        "2026-11-09",
        "UTC",
        "session",
        kind,
        source,
        type,
        title,
        body,
        "",
        "");
  }

  private static Entry copy(
      Entry e, long time, String zone, String source, String title, String body) {
    return new Entry(
        e.rowId,
        e.uid,
        time,
        time,
        e.day,
        zone,
        e.session,
        e.kind,
        source,
        e.type,
        title,
        body,
        e.stateKey,
        e.stateValue);
  }

  private static Entry at(Entry e, long time) {
    return copy(e, time, e.zone, e.source, e.title, e.body);
  }

  private static Entry zone(Entry e, String zone) {
    return copy(e, e.timeMs, zone, e.source, e.title, e.body);
  }

  private static Entry source(Entry e, String source) {
    return copy(e, e.timeMs, e.zone, source, e.title, e.body);
  }

  private static Entry title(Entry e, String title) {
    return copy(e, e.timeMs, e.zone, e.source, title, e.body);
  }

  private static Entry body(Entry e, String body) {
    return copy(e, e.timeMs, e.zone, e.source, e.title, body);
  }

  private static void immutable(Runnable mutation, String message) {
    try {
      mutation.run();
      throw new AssertionError(message);
    } catch (UnsupportedOperationException expected) {
      checks++;
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
  }
}
