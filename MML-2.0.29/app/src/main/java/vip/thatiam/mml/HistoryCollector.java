package vip.thatiam.mml;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.UserManager;
import android.provider.CallLog;
import android.provider.Telephony;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional provider backfill. Cursors advance only with the transaction that saves their rows. */
public final class HistoryCollector {
  private static final int PAGE = 200;
  private static final long HOUR = 3600000L;
  private static final AtomicBoolean active = new AtomicBoolean();
  private static final AtomicBoolean scheduled = new AtomicBoolean();
  private static final AtomicBoolean again = new AtomicBoolean();
  private static final AtomicBoolean commitPending = new AtomicBoolean();
  private static final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          15,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(),
          r ->
              new Thread(
                  () -> {
                    try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
                    catch (RuntimeException ignored) {}
                    r.run();
                  },
                  "mml-history"));

  static {
    worker.allowCoreThreadTimeOut(true);
  }

  private HistoryCollector() {}

  /** Retained-provider catch-up is foreground work. Notification capture is independent. */
  public static void setActive(boolean value) {
    active.set(value);
    if (!value) again.set(false);
  }

  public static void request(Context context) {
    if (!active.get()) return;
    Context app = context.getApplicationContext();
    SharedPreferences enabled = app.getSharedPreferences("mml2", Context.MODE_PRIVATE);
    if (!enabled.getBoolean("capture_usage", false)
        && !enabled.getBoolean("capture_sms", false)
        && !enabled.getBoolean("capture_calls", false)) return;
    if (!scheduled.compareAndSet(false, true)) {
      again.set(true);
      return;
    }
    worker.execute(
        () -> {
          try {
            do {
              again.set(false);
              if (!active.get()) break;
              collect(app);
            } while (active.get() && again.get());
          } catch (RuntimeException error) {
            // Optional retained-history backfill is never allowed to terminate the app.
            try {
              app.getSharedPreferences("mml2", Context.MODE_PRIVATE)
                  .edit()
                  .putString("history_worker_error", Journal.describe(error))
                  .apply();
            } catch (RuntimeException ignored) {
              // Keep the failure isolated even if diagnostics cannot be saved.
            }
          } finally {
            scheduled.set(false);
            if (active.get() && again.getAndSet(false)) request(app);
            else if (!active.get()) again.set(false);
          }
        });
  }

  private static void collect(Context context) {
    if (!active.get()) return;
    // An unavailable disk can leave a writer transaction pending beyond our wait. Never
    // flood its bounded queue with repeat backfill batches while that transaction retries.
    if (commitPending.get()) return;
    UserManager users = context.getSystemService(UserManager.class);
    if (users != null && !users.isUserUnlocked()) return;
    Journal activeJournal = Journal.get(context);
    if (activeJournal.capturePaused()) {
      activeJournal.refreshStorageStatus();
      return;
    }
    SharedPreferences prefs = context.getSharedPreferences("mml2", Context.MODE_PRIVATE);
    if (active.get() && prefs.getBoolean("capture_usage", false)) {
      try {
        collectUsage(context);
      } catch (RuntimeException error) {
        failure(context, "usage", error);
      }
    }
    if (commitPending.get()) return;
    if (active.get() && prefs.getBoolean("capture_sms", false)) {
      if (context.checkSelfPermission(Manifest.permission.READ_SMS)
          == PackageManager.PERMISSION_GRANTED) {
        try {
          collectRows(context, false);
        } catch (RuntimeException error) {
          failure(context, "sms", error);
        }
      } else prefs.edit().putString("sms_error", "Android has not granted SMS access.").apply();
    }
    if (commitPending.get()) return;
    if (active.get() && prefs.getBoolean("capture_calls", false)) {
      if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)
          == PackageManager.PERMISSION_GRANTED) {
        try {
          collectRows(context, true);
        } catch (RuntimeException error) {
          failure(context, "calls", error);
        }
      } else
        prefs.edit().putString("calls_error", "Android has not granted call log access.").apply();
    }
  }

  public static boolean hasUsageAccess(Context context) {
    AppOpsManager manager = context.getSystemService(AppOpsManager.class);
    return manager != null
        && manager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
            == AppOpsManager.MODE_ALLOWED;
  }

  private static void collectUsage(Context context) {
    SharedPreferences prefs = context.getSharedPreferences("mml2", Context.MODE_PRIVATE);
    if (!hasUsageAccess(context)) {
      prefs.edit().putString("usage_error", "Android has not granted Usage Access.").apply();
      return;
    }
    UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
    if (usage == null) return;
    Journal journal = Journal.get(context);
    long now = System.currentTimeMillis();
    long saved = number(journal.readCheckpoint("usage_until", "0"));
    long earliest = Math.max(0, now - 3 * 24 * HOUR);
    // A small overlap catches events whose platform write arrived after our previous query.
    long begin = saved == 0 ? earliest : Math.max(earliest, Math.min(now, saved) - 120000);
    if (saved > now)
      journal.record(
          "SYSTEM",
          "MML",
          "usage_clock_reset",
          "Usage cursor adjusted",
          "The device clock moved backward.");
    for (int interval = 0; begin < now && interval < 12; interval++) {
      if (!active.get() || !prefs.getBoolean("capture_usage", false)) return;
      long end = Math.min(now, begin + 6 * HOUR);
      UsageEvents events = usage.queryEvents(begin, end);
      if (events == null) return;
      List<Entry> rows = new ArrayList<>(PAGE);
      long batchBytes = 256;
      UsageEvents.Event event = new UsageEvents.Event();
      while (events.hasNextEvent()) {
        if (!active.get()) return;
        events.getNextEvent(event);
        String packageName = value(event.getPackageName());
        String className = value(event.getClassName());
        int type = event.getEventType();
        if (!usageTypeEnabled(prefs, type)) continue;
        String identity =
            event.getTimeStamp() + "\n" + packageName + "\n" + className + "\n" + type;
        String label = usageName(type);
        Entry entry =
            Entry.at(
                    event.getTimeStamp(),
                    "USAGE",
                    packageName.isEmpty() ? "Android" : packageName,
                    "usage_" + type,
                    label,
                    className)
                .withIdentity("usage:" + Entry.hash(identity));
        long entryBytes = StoragePolicy.entryBytes(entry);
        if (!rows.isEmpty()
            && batchBytes + entryBytes > StoragePolicy.MAX_BATCH_ENTRY_BYTES) {
          if (!commit(journal, rows, null, null)) return;
          rows.clear();
          batchBytes = 256;
        }
        rows.add(entry);
        batchBytes += entryBytes;
        if (rows.size() == PAGE) {
          if (!commit(journal, rows, null, null)) return;
          rows.clear();
          batchBytes = 256;
        }
      }
      if (!commit(journal, rows, "usage_until", Long.toString(end))) return;
      begin = end;
    }
    prefs.edit().putString("usage_error", "").putLong("usage_last_checked_ms", now).apply();
    if (active.get() && begin < now) again.set(true);
  }

  private static void collectRows(Context context, boolean calls) {
    Journal journal = Journal.get(context);
    SharedPreferences prefs = context.getSharedPreferences("mml2", Context.MODE_PRIVATE);
    String source = calls ? "calls" : "sms";
    String cursorKey = source + "_last_id";
    long checkpoint = number(journal.readCheckpoint(cursorKey, "0"));
    Uri uri = calls ? CallLog.Calls.CONTENT_URI : Telephony.Sms.CONTENT_URI;
    if (checkpoint > 0 && highestId(context, uri) < checkpoint) {
      // Provider data can be cleared/restored independently of MML. Its new IDs must not
      // remain hidden behind the old cursor until they eventually reach the old maximum.
      List<Entry> reset = new ArrayList<>();
      reset.add(
          Entry.event(
              "SYSTEM",
              "MML",
              source + "_cursor_reset",
              calls ? "Call history cursor reset" : "SMS history cursor reset",
              "Provider IDs moved backward. Rescanning retained records."));
      if (!commit(journal, reset, cursorKey, "0")) return;
      checkpoint = 0;
    }
    // Revisiting recent IDs also records provider updates, such as a call's final duration.
    long after = Math.max(0, checkpoint - 100);
    String[] projection =
        calls
            ? new String[] {"_id", "date", "number", "type", "duration", "name"}
            : new String[] {"_id", "date", "address", "type", "body"};
    for (int page = 0; page < 20; page++) {
      if (!active.get() || !prefs.getBoolean(calls ? "capture_calls" : "capture_sms", false)) return;
      List<Entry> rows = new ArrayList<>(PAGE);
      long batchBytes = 256;
      long last = after;
      int seen = 0;
      // CursorWindow bounds returned data; only PAGE rows are consumed per query.
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(uri, projection, "_id > ?", new String[] {Long.toString(after)}, "_id ASC")) {
        if (cursor == null) throw new IllegalStateException("Provider returned no cursor");
        while (active.get() && seen < PAGE && cursor.moveToNext()) {
          seen++;
          long id = cursor.getLong(0);
          long when = cursor.getLong(1);
          String address = value(cursor.getString(2));
          int type = cursor.getInt(3);
          String title;
          String body;
          if (calls) {
            title = callName(type) + (address.isEmpty() ? "" : ": " + address);
            body =
                "Number: "
                    + address
                    + "\nName: "
                    + value(cursor.getString(5))
                    + "\nDuration: "
                    + cursor.getLong(4)
                    + " seconds\nProvider ID: "
                    + id;
          } else {
            title = smsName(type) + (address.isEmpty() ? "" : ": " + address);
            body = value(cursor.getString(4));
          }
          String signature = Entry.hash(when + "\n" + type + "\n" + title + "\n" + body);
          Entry entry =
              Entry.at(
                      when,
                      calls ? "CALL" : "SMS",
                      calls ? "Phone" : "Messages",
                      source + "_" + type,
                      title,
                      body)
                  .withState(source + ":" + id, signature);
          long entryBytes = StoragePolicy.entryBytes(entry);
          if (!rows.isEmpty()
              && batchBytes + entryBytes > StoragePolicy.MAX_BATCH_ENTRY_BYTES) {
            if (!commit(journal, rows, null, null)) return;
            rows.clear();
            batchBytes = 256;
          }
          rows.add(entry);
          batchBytes += entryBytes;
          last = id;
        }
      }
      if (rows.isEmpty()) break;
      checkpoint = Math.max(checkpoint, last);
      if (!commit(journal, rows, cursorKey, Long.toString(checkpoint))) return;
      after = last;
      if (seen < PAGE) break;
      if (active.get() && page == 19) again.set(true);
    }
    prefs
        .edit()
        .putString(source + "_error", "")
        .putLong(source + "_last_checked_ms", System.currentTimeMillis())
        .apply();
  }

  private static long highestId(Context context, Uri uri) {
    try (Cursor cursor =
        context.getContentResolver().query(uri, new String[] {"_id"}, null, null, "_id DESC")) {
      if (cursor == null) throw new IllegalStateException("Provider returned no cursor");
      return cursor.moveToFirst() ? cursor.getLong(0) : 0;
    }
  }

  private static boolean commit(Journal journal, List<Entry> entries, String key, String value) {
    if (!commitPending.compareAndSet(false, true)) return false;
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean();
    try {
      journal.appendBatch(
          entries,
          key,
          value,
          (saved, message) -> {
            success.set(saved);
            commitPending.set(false);
            done.countDown();
          });
    } catch (RuntimeException error) {
      commitPending.set(false);
      throw error;
    }
    try {
      // A failed/full disk leaves the journal batch pending. Retrying later is idempotent.
      // Catch-up work is optional. Do not keep a paused Activity alive behind a blocked writer.
      return active.get() && done.await(5, TimeUnit.SECONDS) && success.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void failure(Context context, String source, RuntimeException error) {
    String message =
        source
            + ": "
            + error.getClass().getSimpleName()
            + ": "
            + String.valueOf(error.getMessage());
    context.getSharedPreferences("mml2", 0).edit().putString(source + "_error", message).apply();
    try {
      Journal.get(context)
          .submit(
              Entry.event(
                      "SYSTEM", "MML", "history_error", "History source unavailable", message)
                  .withState("history:" + source, Entry.hash(message)),
              null);
    } catch (RuntimeException journalUnavailable) {
      context
          .getSharedPreferences("mml2", 0)
          .edit()
          .putString(source + "_error", message + "; journal: " + Journal.describe(journalUnavailable))
          .apply();
    }
  }

  private static long number(String value) {
    try {
      return Math.max(0, Long.parseLong(value));
    } catch (NumberFormatException malformed) {
      return 0;
    }
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  private static String callName(int type) {
    switch (type) {
      case CallLog.Calls.INCOMING_TYPE:
        return "Incoming call";
      case CallLog.Calls.OUTGOING_TYPE:
        return "Outgoing call";
      case CallLog.Calls.MISSED_TYPE:
        return "Missed call";
      case CallLog.Calls.REJECTED_TYPE:
        return "Rejected call";
      case CallLog.Calls.BLOCKED_TYPE:
        return "Blocked call";
      case CallLog.Calls.VOICEMAIL_TYPE:
        return "Voicemail";
      case CallLog.Calls.ANSWERED_EXTERNALLY_TYPE:
        return "Call answered elsewhere";
      default:
        return "Call type " + type;
    }
  }

  private static String smsName(int type) {
    switch (type) {
      case Telephony.Sms.MESSAGE_TYPE_INBOX:
        return "Received SMS";
      case Telephony.Sms.MESSAGE_TYPE_SENT:
        return "Sent SMS";
      case Telephony.Sms.MESSAGE_TYPE_DRAFT:
        return "SMS draft";
      case Telephony.Sms.MESSAGE_TYPE_OUTBOX:
        return "SMS outbox";
      case Telephony.Sms.MESSAGE_TYPE_FAILED:
        return "Failed SMS";
      case Telephony.Sms.MESSAGE_TYPE_QUEUED:
        return "Queued SMS";
      default:
        return "SMS type " + type;
    }
  }

  private static boolean usageTypeEnabled(SharedPreferences prefs, int type) {
    switch (type) {
      case UsageEvents.Event.ACTIVITY_RESUMED:
      case UsageEvents.Event.ACTIVITY_PAUSED:
      case UsageEvents.Event.ACTIVITY_STOPPED:
        return prefs.getBoolean("usage_apps", true);
      case UsageEvents.Event.SCREEN_INTERACTIVE:
      case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
      case UsageEvents.Event.KEYGUARD_SHOWN:
      case UsageEvents.Event.KEYGUARD_HIDDEN:
        return prefs.getBoolean("usage_screen", true);
      case UsageEvents.Event.USER_INTERACTION:
        return prefs.getBoolean("usage_interaction", true);
      case UsageEvents.Event.FOREGROUND_SERVICE_START:
      case UsageEvents.Event.FOREGROUND_SERVICE_STOP:
        return prefs.getBoolean("usage_services", true);
      case UsageEvents.Event.DEVICE_STARTUP:
      case UsageEvents.Event.DEVICE_SHUTDOWN:
        return prefs.getBoolean("usage_device", true);
      default:
        return prefs.getBoolean("usage_other", true);
    }
  }

  private static String usageName(int type) {
    switch (type) {
      case UsageEvents.Event.ACTIVITY_RESUMED:
        return "App resumed";
      case UsageEvents.Event.ACTIVITY_PAUSED:
        return "App paused";
      case UsageEvents.Event.ACTIVITY_STOPPED:
        return "App stopped";
      case UsageEvents.Event.SCREEN_INTERACTIVE:
        return "Screen interactive";
      case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
        return "Screen not interactive";
      case UsageEvents.Event.KEYGUARD_SHOWN:
        return "Screen locked";
      case UsageEvents.Event.KEYGUARD_HIDDEN:
        return "Screen unlocked";
      case UsageEvents.Event.DEVICE_STARTUP:
        return "Device startup";
      case UsageEvents.Event.DEVICE_SHUTDOWN:
        return "Device shutdown";
      case UsageEvents.Event.USER_INTERACTION:
        return "App interaction";
      case UsageEvents.Event.FOREGROUND_SERVICE_START:
        return "App foreground service started";
      case UsageEvents.Event.FOREGROUND_SERVICE_STOP:
        return "App foreground service stopped";
      default:
        return "Usage event " + type;
    }
  }
}
