package vip.thatiam.mml;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded writer, transactional commits, independent readers. No folder access here. */
public final class Journal {
  public interface Completion {
    void done(boolean success, String message);
  }

  public interface Observer {
    void changed();
  }

  private static volatile Journal instance;
  private final Context context;
  private final Database helper;
  private final Thread writer;
  private Job deferredJob;
  private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(512);
  private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong storageRejected = new AtomicLong();
  private final AtomicLong queuedBytes = new AtomicLong();
  private final AtomicBoolean storageRefreshQueued = new AtomicBoolean();
  private final Object enqueueLock = new Object();
  private volatile String lastError = "", session = "";
  private volatile String storageStatus = "Checking automatic capture storage";
  private volatile boolean storagePaused;
  private volatile long storageBytes, storageAllocatedBytes, storageFreeBytes;
  private volatile long rolledAutomatic;
  private volatile boolean rollBlockedByMirror;
  private long lastStorageCheck;
  private volatile long lastCommit;
  private volatile boolean ready;
  private boolean sessionCommitted;
  private boolean changePosted;
  private String processBoundary;
  private final Object openingLock = new Object();
  private final Runnable finishOpening = this::finishSessionOpening;
  private boolean openingPending, openingInFlight, openingDone;
  private long openingDeadline;

  public static Journal get(Context context) {
    if (instance == null)
      synchronized (Journal.class) {
        if (instance == null) instance = new Journal(context.getApplicationContext());
      }
    return instance;
  }

  private Journal(Context context) {
    this.context = context;
    helper = new Database(context);
    writer = new Thread(this::writeLoop, "mml-journal");
    writer.start();
    // Resolve the existing boot/session even when automatic capture is already at its budget.
    appendBatch(Collections.emptyList(), null, null, null);
    record("SYSTEM", "MML", "process_started", "Logger started", "MML 2.0.29");
  }

  public void record(String kind, String source, String type, String title, String body) {
    submit(Entry.event(kind, source, type, title, body), null);
  }

  public void submit(Entry entry, Completion completion) {
    appendBatch(Collections.singletonList(entry), null, null, completion);
  }

  /** Delete committed rows by portable identity on the serialized writer. */
  public void deleteEntries(List<Entry> entries, Completion completion) {
    if (entries == null || entries.isEmpty())
      throw new IllegalArgumentException("A saved log entry is required.");
    ArrayList<String> uids = new ArrayList<>();
    for (Entry entry : entries) {
      if (entry == null || entry.uid.isEmpty())
        throw new IllegalArgumentException("A saved log entry is required.");
      uids.add(entry.uid);
    }
    enqueue(Job.delete(uids, completion));
  }

  /** Set or clear the durable notification state of one saved row. */
  public void markNotified(String uid, long notifiedMs, Completion completion) {
    if (uid == null || uid.isEmpty() || uid.length() > 256)
      throw new IllegalArgumentException("A saved log entry is required.");
    enqueue(Job.markNotified(uid, Math.max(0, notifiedMs), completion));
  }

  /** Clear the durable Notify state from every saved row. */
  public void clearAllNotified(Completion completion) {
    enqueue(Job.clearNotified(completion));
  }


  public void appendBatch(
      List<Entry> entries, String checkpointKey, String value, Completion completion) {
    if (entries.size() > 200)
      throw new IllegalArgumentException("Journal batch exceeds 200 entries.");
    Job job = new Job(new ArrayList<>(entries), checkpointKey, value, completion);
    enqueue(job);
  }

  private void enqueue(Job job) {
    boolean accepted = false;
    synchronized (enqueueLock) {
      if (job.payloadBytes <= StoragePolicy.MAX_BATCH_BYTES
          && StoragePolicy.queueFits(queuedBytes.get(), job.payloadBytes, job.protectedWrite)
          && (job.protectedWrite
              || queue.remainingCapacity() > StoragePolicy.USER_QUEUE_RESERVE_SLOTS)) {
        queuedBytes.addAndGet(job.payloadBytes);
        job.accounted = true;
        accepted = queue.offer(job);
      }
    }
    if (!accepted) {
      rejected.addAndGet(job.entries.size());
      lastError =
          job.payloadBytes > StoragePolicy.MAX_BATCH_BYTES
              ? "Journal batch exceeds the 1 MiB payload limit. Nothing in this batch was saved."
              : "Capture queue full. " + rejected.get() + " entries were not accepted.";
      finish(job, false, lastError);
      notifyObservers();
    } else if (!job.opening) {
      settleOpening();
    }
  }

  public void checkpoint(String key, String value, Completion completion) {
    appendBatch(Collections.emptyList(), key, value, completion);
  }

  public String readCheckpoint(String key, String fallback) {
    try (Cursor cursor =
        helper
            .getReadableDatabase()
            .rawQuery("SELECT value FROM metadata WHERE key=?", new String[] {key})) {
      return cursor.moveToFirst() ? cursor.getString(0) : fallback;
    }
  }

  public void sessionReminder() {
    synchronized (openingLock) {
      if (openingDone || openingPending || openingInFlight) return;
      openingPending = true;
      openingDeadline = SystemClock.uptimeMillis() + 3000;
      settleOpening();
    }
  }

  private void settleOpening() {
    synchronized (openingLock) {
      if (!openingPending) return;
      // Let the initial collector/listener callbacks arrive without delaying their writes.
      // The fixed deadline prevents continuous notifications from postponing the notice.
      main.removeCallbacks(finishOpening);
      main.postAtTime(finishOpening, Math.min(SystemClock.uptimeMillis() + 500, openingDeadline));
    }
  }

  private void finishSessionOpening() {
    SharedPreferences prefs = context.getSharedPreferences("mml2", 0);
    synchronized (openingLock) {
      if (!openingPending) return;
      openingPending = false;
      openingInFlight = true;
    }
    String access;
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      access =
          manager != null
                  && manager.isNotificationListenerAccessGranted(
                      new ComponentName(context, MmlNotificationListener.class))
              ? "granted"
              : "not granted";
    } catch (RuntimeException error) {
      access = "unavailable: " + describe(error);
    }
    List<Entry> entries = new ArrayList<>();
    entries.add(
        Entry.event(
            "SYSTEM",
            "MML",
            "session_startup",
            "Session diagnostics",
            "Notification capture: "
                + (prefs.getBoolean("capture_notifications", true) ? "enabled" : "disabled")
                + "\nNotification access: "
                + access
                + "\nListener: "
                + (NotificationCapture.connected ? "connected" : "not connected")
                + "\nBackground model: Android notification listener only"
                + "\nStorage: "
                + (prefs.getString("log_tree", "").isEmpty()
                    ? "app data"
                    : "app data + log folder")));
    entries.add(
        Entry.event(
                "SYSTEM",
                "MML",
                "home_reminder",
                "Bloodguilt curse",
                "Cows aren't killers, and beef is the bloodshed of the innocent.\n"
                    + "www.bloodguiltcurse.net has the Bible study.")
            .withState("home-reminder", "CURRENT_SESSION"));
    enqueue(
        new Job(
            entries,
            null,
            null,
            (success, message) -> {
              synchronized (openingLock) {
                openingInFlight = false;
                if (success) openingDone = true;
              }
              // The optional Home reminder is never a background retry loop. A later Home
              // activation may request it again if this save failed.
            },
            true));
  }

  public String session() {
    return session;
  }

  public String error() {
    return lastError;
  }

  public long lastCommit() {
    return lastCommit;
  }

  public int pending() {
    return queue.size() + (deferredJob == null ? 0 : 1);
  }

  public long pendingBytes() {
    return queuedBytes.get();
  }

  /** Cached storage state; safe for labels and status banners on the main thread. */
  public String captureStorageStatus() {
    return storageStatus;
  }

  public boolean capturePaused() {
    return storagePaused;
  }

  public int captureLimitMiB() {
    try {
      return StoragePolicy.limitMiB(
          context
              .getSharedPreferences("mml2", 0)
              .getInt(StoragePolicy.LIMIT_PREFERENCE, StoragePolicy.DEFAULT_CAPTURE_LIMIT_MIB));
    } catch (ClassCastException oldPreference) {
      return StoragePolicy.DEFAULT_CAPTURE_LIMIT_MIB;
    }
  }

  public void setCaptureLimitMiB(int value) {
    if (!StoragePolicy.validLimitMiB(value))
      throw new IllegalArgumentException("Choose 32, 64, 128, 256 or 512 MiB.");
    context
        .getSharedPreferences("mml2", 0)
        .edit()
        .putInt(StoragePolicy.LIMIT_PREFERENCE, value)
        .apply();
    refreshStorageStatus();
  }

  /** One coalesced writer request. No filesystem work is performed by the caller. */
  public void refreshStorageStatus() {
    if (!storageRefreshQueued.compareAndSet(false, true)) return;
    enqueue(new Job(Collections.emptyList(), null, null, null, false, true));
  }

  public boolean ready() {
    return ready;
  }

  public void observe(Observer observer) {
    observers.addIfAbsent(observer);
  }

  public void unobserve(Observer observer) {
    observers.remove(observer);
  }

  private void writeLoop() {
    try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
    catch (RuntimeException ignored) { /* Best-effort CPU scheduling only. */ }
    while (!Thread.currentThread().isInterrupted()) {
      List<Job> jobs = new ArrayList<>();
      try {
        Job first = deferredJob;
        if (first != null) deferredJob = null;
        else first = queue.take();
        jobs.add(first);
        long payload = first.payloadBytes;

        if (!first.protectedWrite && !first.storageRefresh) {
          // Automatic machine events use a quiet-window debounce rather than a fixed sleep.
          // A newly queued USER/protected write wakes queue.poll() immediately and closes the
          // batch, so deliberate actions are not forced to wait for the automatic timer.
          AutomaticBatchWindow window = new AutomaticBatchWindow(SystemClock.uptimeMillis());
          while (!window.full(jobs.size(), payload)) {
            long wait = window.remainingWaitMillis(SystemClock.uptimeMillis());
            if (wait <= 0) break;
            Job next = queue.poll(wait, TimeUnit.MILLISECONDS);
            if (next == null) break;
            if (payload + next.payloadBytes > StoragePolicy.MAX_TRANSACTION_BYTES) {
              deferredJob = next;
              break;
            }
            jobs.add(next);
            payload += next.payloadBytes;
            if (next.protectedWrite || next.storageRefresh) break;
            window.automaticArrived(SystemClock.uptimeMillis());
          }
        } else {
          // Explicit USER/checkpoint/storage work is never delayed merely to grow a batch.
          // It may absorb work that was already queued, preserving the existing efficient
          // single-transaction path without introducing any timer.
          while (jobs.size() < AutomaticBatchWindow.MAX_JOBS) {
            Job next = queue.peek();
            if (next == null || payload + next.payloadBytes > StoragePolicy.MAX_TRANSACTION_BYTES)
              break;
            next = queue.poll();
            if (next == null) break;
            jobs.add(next);
            payload += next.payloadBytes;
          }
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        boolean forced = false;
        for (Job job : jobs) forced |= job.storageRefresh;
        updateStorageSnapshot(db, forced);
        boolean automaticPending = false;
        long incomingAutomaticBytes = 0;
        for (Job job : jobs) {
          if (!job.storageRefresh && !job.protectedWrite) {
            automaticPending = true;
            incomingAutomaticBytes += job.payloadBytes;
          }
        }
        if ((forced || automaticPending)
            && storageFreeBytes >= StoragePolicy.AUTOMATIC_FREE_RESERVE_BYTES
            && StoragePolicy.shouldRoll(storageBytes, incomingAutomaticBytes, captureLimitMiB())) {
          rollAutomaticHistory(db);
          updateStorageSnapshot(db, true);
        }
        long reserved = 0;
        for (int i = 0; i < jobs.size(); ) {
          Job job = jobs.get(i);
          if (job.storageRefresh) {
            finish(job, true, "Storage checked");
            jobs.remove(i);
            continue;
          }
          StoragePolicy.Blocked blocked =
              job.protectedWrite
                  ? StoragePolicy.Blocked.NONE
                  : StoragePolicy.blocked(
                      storageBytes + reserved,
                      Math.max(0, storageFreeBytes - reserved),
                      job.payloadBytes,
                      captureLimitMiB());
          if (blocked != StoragePolicy.Blocked.NONE) {
            storageRejected.addAndGet(StoragePolicy.machineCount(job.entries));
            setStorageStatus(blocked);
            finish(job, false, storageStatus);
            jobs.remove(i);
            notifyObservers();
            continue;
          }
          reserved += StoragePolicy.WRITE_RESERVE_BYTES + 2L * job.payloadBytes;
          i++;
        }
        if (jobs.isEmpty()) continue;
        db.beginTransaction();
        boolean canReportGap =
            StoragePolicy.blocked(
                    storageBytes + reserved,
                    Math.max(0, storageFreeBytes - reserved),
                    4096,
                    captureLimitMiB())
                == StoragePolicy.Blocked.NONE;
        long lost = canReportGap ? rejected.get() : 0;
        long paused = canReportGap ? storageRejected.get() : 0;
        try {
          ensureSession(db);
          for (Job job : jobs) writeJob(db, job);
          if (lost > 0 || paused > 0)
            insert(
                db,
                Entry.event(
                        "SYSTEM",
                        "MML",
                        "capture_gap",
                        "Capture gap",
                        lost
                            + " submissions could not enter the bounded capture queue.\n"
                            + paused
                            + " automatic capture submissions were refused by storage safeguards"
                            + " or failed writes. They were not acknowledged as saved."
                            + " Retained provider history may be recovered later.")
                    .withSession(session));
          db.setTransactionSuccessful();
        } finally {
          db.endTransaction();
        }
        if (lost > 0) rejected.addAndGet(-lost);
        if (paused > 0) storageRejected.addAndGet(-paused);
        sessionCommitted = true;
        lastCommit = System.currentTimeMillis();
        lastError = "";
        ready = true;
        updateStorageSnapshot(db, true);
        for (Job job : jobs) finish(job, true, "Saved");
        notifyObservers();
      } catch (InterruptedException stop) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException error) {
        lastError = "Journal write failed: " + describe(error);
        notifyObservers();
        // Automatic writes must not retain the head of the only writer forever. Their
        // transaction rolled back, so state/checkpoints remain unchanged and collectors
        // can replay retained history. Explicit user entries retain their durable-ACK rule.
        for (int i = 0; i < jobs.size(); ) {
          Job job = jobs.get(i);
          if (job.finished) {
            jobs.remove(i);
            continue;
          }
          if (!job.userOnly && job.deleteUids == null) {
            storageRejected.addAndGet(StoragePolicy.machineCount(job.entries));
            finish(job, false, lastError);
            jobs.remove(i);
          } else i++;
        }
        retry(jobs);
      }
    }
  }

  private void retry(List<Job> jobs) {
    if (jobs.isEmpty()) return;
    // Only explicit user writes reach this path. Retry briefly, then fail the entry.
    // An endless writer thread is worse than asking the user to submit again.
    RuntimeException last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        Thread.sleep(1000L << attempt);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
          ensureSession(db);
          for (Job job : jobs) writeJob(db, job);
          db.setTransactionSuccessful();
        } finally {
          db.endTransaction();
        }
        sessionCommitted = true;
        ready = true;
        lastError = "";
        lastCommit = System.currentTimeMillis();
        updateStorageSnapshot(db, true);
        for (Job job : jobs) finish(job, true, "Saved after retry");
        notifyObservers();
        return;
      } catch (InterruptedException stop) {
        Thread.currentThread().interrupt();
        lastError = "Journal write interrupted";
        break;
      } catch (RuntimeException error) {
        last = error;
        lastError = "Journal write retry: " + describe(error);
        notifyObservers();
      }
    }
    if (last != null) lastError = "Journal write stopped after 3 retries: " + describe(last);
    for (Job job : jobs) finish(job, false, lastError);
    notifyObservers();
  }

  private void updateStorageSnapshot(SQLiteDatabase db, boolean force) {
    long now = SystemClock.uptimeMillis();
    if (!force && now - lastStorageCheck < 5000) return;
    lastStorageCheck = now;
    try {
      File database = new File(db.getPath());
      long mainAllocated = database.length();
      long walAllocated = new File(db.getPath() + "-wal").length();
      long shmAllocated = new File(db.getPath() + "-shm").length();
      storageAllocatedBytes = mainAllocated + walAllocated + shmAllocated;
      long pageSize = pragmaLong(db, "PRAGMA page_size");
      long pageCount = pragmaLong(db, "PRAGMA page_count");
      long freePages = pragmaLong(db, "PRAGMA freelist_count");
      storageBytes = Math.max(0, pageCount - freePages) * Math.max(1, pageSize);
      File parent = database.getParentFile();
      storageFreeBytes = parent == null ? 0 : parent.getUsableSpace();
      setStorageStatus(
          StoragePolicy.blocked(storageBytes, storageFreeBytes, 0, captureLimitMiB()));
    } catch (RuntimeException unavailable) {
      storagePaused = true;
      storageFreeBytes = 0;
      storageStatus =
          "Automatic capture paused: storage availability could not be checked. Existing records"
              + " are retained. "
              + describe(unavailable);
    }
  }

  private static long pragmaLong(SQLiteDatabase db, String pragma) {
    try (Cursor cursor = db.rawQuery(pragma, null)) {
      if (!cursor.moveToFirst()) throw new SQLiteException("No result for " + pragma);
      return cursor.getLong(0);
    }
  }

  private void rollAutomaticHistory(SQLiteDatabase db) {
    long target = StoragePolicy.rollTargetBytes(captureLimitMiB());
    long ceiling = mirrorPruneCeiling(db);
    rollBlockedByMirror = false;
    int batches = 0;
    long removed = 0;
    while (storageBytes > target && batches < StoragePolicy.MAX_ROLL_BATCHES_PER_PASS) {
      long lastRow = 0;
      long cutoffCaptured = 0;
      try (Cursor rows =
          db.rawQuery(
              "SELECT row_id,captured_ms FROM entries WHERE row_id<=? AND kind!='USER'"
                  + " AND type!='home_reminder' ORDER BY row_id LIMIT ?",
              new String[] {
                Long.toString(ceiling), Integer.toString(StoragePolicy.ROLL_DELETE_BATCH)
              })) {
        while (rows.moveToNext()) {
          lastRow = rows.getLong(0);
          cutoffCaptured = Math.max(cutoffCaptured, rows.getLong(1));
        }
      }
      if (lastRow == 0) {
        if (ceiling != Long.MAX_VALUE) rollBlockedByMirror = true;
        break;
      }
      db.beginTransaction();
      try {
        int changed =
            db.delete(
                "entries",
                "row_id<=? AND kind!='USER' AND type!='home_reminder'",
                new String[] {Long.toString(lastRow)});
        if (cutoffCaptured > 0)
          db.delete(
              "states",
              "seen_ms<=? AND key NOT LIKE 'home-reminder:%'",
              new String[] {Long.toString(cutoffCaptured)});
        db.setTransactionSuccessful();
        removed += Math.max(0, changed);
      } finally {
        db.endTransaction();
      }
      batches++;
      long pageSize = pragmaLong(db, "PRAGMA page_size");
      long pageCount = pragmaLong(db, "PRAGMA page_count");
      long freePages = pragmaLong(db, "PRAGMA freelist_count");
      storageBytes = Math.max(0, pageCount - freePages) * Math.max(1, pageSize);
    }
    if (removed > 0) rolledAutomatic += removed;
  }

  private long mirrorPruneCeiling(SQLiteDatabase db) {
    String selected = context.getSharedPreferences("mml2", 0).getString("log_tree", "");
    if (selected.isEmpty()) return Long.MAX_VALUE;
    String key = "mirror:" + Entry.hash(selected);
    try (Cursor cursor =
        db.rawQuery("SELECT value FROM metadata WHERE key=?", new String[] {key})) {
      if (!cursor.moveToFirst()) return 0;
      try { return Math.max(0, Long.parseLong(cursor.getString(0))); }
      catch (NumberFormatException invalid) { return 0; }
    }
  }

  private void setStorageStatus(StoragePolicy.Blocked reason) {
    boolean paused = reason != StoragePolicy.Blocked.NONE;
    StringBuilder text = new StringBuilder();
    if (reason == StoragePolicy.Blocked.DEVICE_SPACE)
      text.append("Automatic capture paused: keep at least 64 MiB free on the device.");
    else if (reason == StoragePolicy.Blocked.JOURNAL_BUDGET) {
      text.append("Automatic logging paused: the ")
          .append(captureLimitMiB())
          .append(" MiB app-data log limit is full.");
      if (rollBlockedByMirror)
        text.append(" The selected log folder must catch up before older automatic records can roll off.");
    } else text.append("App-data log ready; old automatic records roll off as needed.");
    text.append(" Journal used: ")
        .append((storageBytes + StoragePolicy.MIB - 1) / StoragePolicy.MIB)
        .append(" MiB / ")
        .append(captureLimitMiB())
        .append(" MiB. Allocated: ")
        .append((storageAllocatedBytes + StoragePolicy.MIB - 1) / StoragePolicy.MIB)
        .append(" MiB. Device free: ")
        .append(storageFreeBytes / StoragePolicy.MIB)
        .append(" MiB.");
    if (rolledAutomatic > 0)
      text.append(" Old automatic records rolled off this process: ")
          .append(rolledAutomatic)
          .append('.');
    if (paused)
      text.append(" USER entries and the Bloodguilt warning are retained; manual log entries can still be saved if space allows.");
    if (storageRejected.get() > 0)
      text.append(" Unsaved automatic submissions in this process: ")
          .append(storageRejected.get())
          .append('.');
    String next = text.toString();
    boolean resumed = storagePaused && !paused;
    boolean changed = paused != storagePaused || !next.equals(storageStatus);
    storagePaused = paused;
    storageStatus = next;
    if (resumed) main.post(this::sessionReminder);
    if (changed) notifyObservers();
  }

  private void ensureSession(SQLiteDatabase db) {
    if (sessionCommitted) return;
    String boot = bootNumber();
    if (processBoundary == null) {
      processBoundary = "";
      try {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 1);
        if (!exits.isEmpty()) {
          ApplicationExitInfo exit = exits.get(0);
          if (exit.getReason() == ApplicationExitInfo.REASON_USER_REQUESTED
              || exit.getReason() == ApplicationExitInfo.REASON_USER_STOPPED) {
            processBoundary = Long.toString(exit.getTimestamp());
          }
        }
      } catch (RuntimeException ignored) {
        /* Boot identity still preserves normal process loss. */
      }
    }
    // Keep the latest explicit boundary in metadata so later ordinary process loss reuses it.
    String boundaryKey = "session_boundary:" + boot;
    if (!processBoundary.isEmpty()) setMetadata(db, boundaryKey, processBoundary);
    String boundary = "";
    try (Cursor c =
        db.rawQuery("SELECT value FROM metadata WHERE key=?", new String[] {boundaryKey})) {
      if (c.moveToFirst()) boundary = c.getString(0);
    }
    String key = "boot_session:" + boot + ":" + boundary;
    String saved;
    try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key=?", new String[] {key})) {
      saved = c.moveToFirst() ? c.getString(0) : "";
    }
    if (saved.isEmpty()) {
      saved = UUID.randomUUID().toString();
      setMetadata(db, key, saved);
    }
    session = saved;
  }

  private Entry prepare(Entry entry) {
    Entry value = entry.session.isEmpty() ? entry.withSession(session) : entry;
    if ("home-reminder".equals(value.stateKey))
      value = value.withState("home-reminder:" + session, "shown");
    return value;
  }

  private void writeJob(SQLiteDatabase db, Job job) {
    if (job.opening) {
      // Guard the complete opening batch, reusing the previous version's session marker.
      // The marker is written by the final notice in this same transaction.
      try (Cursor c =
          db.rawQuery(
              "SELECT value FROM states WHERE key=?", new String[] {"home-reminder:" + session})) {
        if (c.moveToFirst() && "shown".equals(c.getString(0))) return;
      }
    }
    if (job.deleteUids != null) {
      for (String uid : job.deleteUids) db.delete("entries", "uid=?", new String[] {uid});
    }
    if (job.clearNotified) {
      ContentValues values = new ContentValues();
      values.put("notified_ms", 0);
      db.update("entries", values, "notified_ms>0", null);
    }
    if (job.notifiedUid != null) {
      if (job.notifiedMs > 0) {
        try (Cursor current =
            db.rawQuery(
                "SELECT kind,type,source FROM entries WHERE uid=?",
                new String[] {job.notifiedUid})) {
          if (!current.moveToFirst())
            throw new SQLiteException("Could not update log notification state");
          String kind = current.getString(0);
          String type = current.getString(1);
          String source = current.getString(2);
          if ("USER".equals(kind) && "app_launch".equals(type) && source != null && !source.isEmpty()) {
            ContentValues clear = new ContentValues();
            clear.put("notified_ms", 0);
            db.update(
                "entries",
                clear,
                "notified_ms>0 AND uid<>? AND kind='USER' AND type='app_launch' AND source=?",
                new String[] {job.notifiedUid, source});
          }
        }
      }
      ContentValues values = new ContentValues();
      values.put("notified_ms", job.notifiedMs);
      if (db.update("entries", values, "uid=?", new String[] {job.notifiedUid}) != 1)
        throw new SQLiteException("Could not update log notification state");
    }
    for (Entry e : job.entries) insert(db, prepare(e));
    if (job.key != null) setMetadata(db, job.key, job.value);
  }


  private String bootNumber() {
    return Integer.toString(
        Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
  }

  private static void setMetadata(SQLiteDatabase db, String key, String value) {
    ContentValues fields = new ContentValues();
    fields.put("key", key);
    fields.put("value", value == null ? "" : value);
    if (db.insertWithOnConflict("metadata", null, fields, SQLiteDatabase.CONFLICT_REPLACE) < 0)
      throw new SQLiteException("Could not commit journal metadata");
  }

  private static void insert(SQLiteDatabase db, Entry e) {
    if (!e.stateKey.isEmpty()) {
      try (Cursor c =
          db.rawQuery("SELECT value FROM states WHERE key=?", new String[] {e.stateKey})) {
        if (c.moveToFirst() && e.stateValue.equals(c.getString(0))) return;
      }
    }

    ContentValues v = new ContentValues();
    v.put("uid", e.uid);
    v.put("time_ms", e.timeMs);
    v.put("captured_ms", e.capturedMs);
    v.put("day", e.day);
    v.put("zone", e.zone);
    v.put("session", e.session);
    v.put("kind", e.kind);
    v.put("source", e.source);
    v.put("type", e.type);
    v.put("title", e.title);
    v.put("body", e.body);
    v.put("notification_key_hash", e.notificationKeyHash);
    v.put("notified_ms", e.notifiedMs);
    long row = db.insertWithOnConflict("entries", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    if (row < 0) {
      try (Cursor c = db.rawQuery("SELECT 1 FROM entries WHERE uid=?", new String[] {e.uid})) {
        if (c.moveToFirst()) return;
      }
      throw new SQLiteException("Could not commit journal entry");
    }
    if (!e.stateKey.isEmpty()) {
      ContentValues state = new ContentValues();
      state.put("key", e.stateKey);
      state.put("value", e.stateValue);
      state.put("seen_ms", e.capturedMs);
      if (db.insertWithOnConflict("states", null, state, SQLiteDatabase.CONFLICT_REPLACE) < 0)
        throw new SQLiteException("Could not commit journal state");
    }
  }

  public static final class EntryPage {
    public final List<Entry> entries;
    public final boolean hasMore;

    EntryPage(List<Entry> entries, boolean hasMore) {
      this.entries = entries;
      this.hasMore = hasMore;
    }
  }

  public List<Entry> homePage(long before, int limit) {
    return homePage(before, limit, null);
  }

  public List<Entry> homePage(long before, int limit, CancellationSignal cancellation) {
    return homeWindow(before, limit, cancellation).entries;
  }

  public EntryPage homeWindow(long before, int limit, CancellationSignal cancellation) {
    return homeWindow(before, limit, LogDetail.FULL, LogDetail.DEFAULT_CUSTOM, cancellation);
  }

  public EntryPage homeWindow(
      long before,
      int limit,
      int detailLevel,
      int customMask,
      CancellationSignal cancellation) {
    return readWindow(
        "session=? AND row_id<? AND " + LogDetail.sqlPredicate(detailLevel, customMask),
        new String[] {session, Long.toString(before <= 0 ? Long.MAX_VALUE : before)},
        "row_id DESC",
        limit,
        cancellation);
  }

  public List<Entry> after(long after, int limit) {
    return readRows(
        "row_id > ?", new String[] {Long.toString(after)}, "row_id ASC", Math.min(limit, 200));
  }

  /** Return a bounded page of durable Notify rows after a row ID. Call from a reader. */
  public List<Entry> notifiedEntriesAfter(long rowId, int limit) {
    return readRows(
        "notified_ms>0 AND row_id>?",
        new String[] {Long.toString(Math.max(0, rowId))},
        "row_id ASC",
        Math.min(limit, 200));
  }

  /** Resolve a saved portable identity. Call from a reader, never the UI thread. */
  public Entry findByUid(String uid) {
    if (uid == null || uid.isEmpty() || uid.length() > 256) return null;
    List<Entry> matches = readRows("uid=?", new String[] {uid}, "row_id ASC", 1);
    return matches.isEmpty() ? null : matches.get(0);
  }

  private List<Entry> readRows(String where, String[] args, String order, int limit) {
    return readRows(where, args, order, limit, null);
  }

  private List<Entry> readRows(
      String where, String[] args, String order, int limit, CancellationSignal cancellation) {
    return readWindow(where, args, order, limit, cancellation).entries;
  }

  private EntryPage readWindow(
      String where, String[] args, String order, int limit, CancellationSignal cancellation) {
    if (cancellation != null) cancellation.throwIfCanceled();
    List<Entry> rows = new ArrayList<>();
    int bounded = Math.max(0, Math.min(limit, 200));
    if (bounded == 0) return new EntryPage(rows, false);
    long payload = 0;
    boolean more = false;
    try (Cursor c =
        helper
            .getReadableDatabase()
            .query(
                false,
                "entries",
                null,
                where,
                args,
                null,
                null,
                order,
                Integer.toString(bounded + 1),
                cancellation)) {
      while (c.moveToNext()) {
        if (cancellation != null) cancellation.throwIfCanceled();
        if (rows.size() == bounded) {
          more = true;
          break;
        }
        Entry entry = fromCursor(c);
        long bytes = StoragePolicy.entryBytes(entry);
        if (!rows.isEmpty() && payload + bytes > StoragePolicy.MAX_READ_BYTES) {
          more = true;
          break;
        }
        rows.add(entry);
        payload += bytes;
      }
    }
    return new EntryPage(rows, more);
  }

  public long countAfter(long row) {
    try (Cursor c =
        helper
            .getReadableDatabase()
            .rawQuery(
                "SELECT count(*) FROM entries WHERE row_id>?", new String[] {Long.toString(row)})) {
      c.moveToFirst();
      return c.getLong(0);
    }
  }

  public long maxRow() {
    try (Cursor c =
        helper
            .getReadableDatabase()
            .rawQuery("SELECT coalesce(max(row_id),0) FROM entries", null)) {
      c.moveToFirst();
      return c.getLong(0);
    }
  }

  /** A cheap watermark for checks whose answer must come from newly committed events. */
  public long latestRowId() {
    return maxRow();
  }

  public boolean hasNotificationTextAfter(long rowId, String text) {
    if (text == null || text.isEmpty()) return false;
    try (Cursor c =
        helper
            .getReadableDatabase()
            .rawQuery(
                "SELECT 1 FROM entries WHERE row_id>? AND kind='NOTIFICATION'"
                    + " AND (instr(title,?)>0 OR instr(body,?)>0) LIMIT 1",
                new String[] {Long.toString(Math.max(0, rowId)), text, text})) {
      return c.moveToFirst();
    }
  }

  public String integrity() {
    return integrity(null);
  }

  public String integrity(CancellationSignal cancellation) {
    if (cancellation != null) cancellation.throwIfCanceled();
    try (Cursor c =
        helper.getReadableDatabase().rawQuery("PRAGMA quick_check", null, cancellation)) {
      return c.moveToFirst() ? c.getString(0) : "No result";
    }
  }

  static Entry fromCursor(Cursor c) {
    return new Entry(
        c.getLong(c.getColumnIndexOrThrow("row_id")),
        c.getString(c.getColumnIndexOrThrow("uid")),
        c.getLong(c.getColumnIndexOrThrow("time_ms")),
        c.getLong(c.getColumnIndexOrThrow("captured_ms")),
        c.getString(c.getColumnIndexOrThrow("day")),
        c.getString(c.getColumnIndexOrThrow("zone")),
        c.getString(c.getColumnIndexOrThrow("session")),
        c.getString(c.getColumnIndexOrThrow("kind")),
        c.getString(c.getColumnIndexOrThrow("source")),
        c.getString(c.getColumnIndexOrThrow("type")),
        c.getString(c.getColumnIndexOrThrow("title")),
        c.getString(c.getColumnIndexOrThrow("body")),
        "",
        "",
        c.getString(c.getColumnIndexOrThrow("notification_key_hash")),
        c.getLong(c.getColumnIndexOrThrow("notified_ms")));
  }

  private void finish(Job job, boolean ok, String message) {
    synchronized (enqueueLock) {
      if (job.finished) return;
      job.finished = true;
      if (job.accounted) {
        queuedBytes.addAndGet(-job.payloadBytes);
        job.accounted = false;
      }
    }
    if (job.storageRefresh) storageRefreshQueued.set(false);
    if (job.completion != null) main.post(() -> job.completion.done(ok, message));
  }

  private void notifyObservers() {
    synchronized (this) {
      if (changePosted) return;
      changePosted = true;
    }
    main.postDelayed(
        () -> {
          synchronized (Journal.this) {
            changePosted = false;
          }
          for (Observer observer : observers) {
            try {
              observer.changed();
            } catch (RuntimeException error) {
              // An observer is UI/status convenience. A bad observer must never kill the
              // logger process or interrupt notification capture.
              try {
                context
                    .getSharedPreferences("mml2", 0)
                    .edit()
                    .putString("observer_error", describe(error))
                    .apply();
              } catch (RuntimeException ignored) {
                // Diagnostics are best-effort only.
              }
            }
          }
        },
        200);
  }

  static String describe(Throwable error) {
    String text = error.getMessage();
    return error.getClass().getSimpleName() + (text == null ? "" : ": " + text);
  }

  private static final class Job {
    final List<Entry> entries;
    final String key, value;
    final List<String> deleteUids;
    final String notifiedUid;
    final long notifiedMs;
    final Completion completion;
    final boolean opening, storageRefresh, clearNotified, userOnly, protectedWrite;
    final long payloadBytes;
    boolean accounted, finished;

    Job(List<Entry> entries, String key, String value, Completion completion) {
      this(entries, key, value, null, null, 0, completion, false, false, false);
    }

    Job(List<Entry> entries, String key, String value, Completion completion, boolean opening) {
      this(entries, key, value, null, null, 0, completion, opening, false, false);
    }

    Job(
        List<Entry> entries,
        String key,
        String value,
        Completion completion,
        boolean opening,
        boolean storageRefresh) {
      this(entries, key, value, null, null, 0, completion, opening, storageRefresh, false);
    }

    private Job(
        List<Entry> entries,
        String key,
        String value,
        List<String> deleteUids,
        String notifiedUid,
        long notifiedMs,
        Completion completion,
        boolean opening,
        boolean storageRefresh,
        boolean clearNotified) {
      this.entries = entries;
      this.key = key;
      this.value = value;
      this.deleteUids = deleteUids;
      this.notifiedUid = notifiedUid;
      this.notifiedMs = notifiedMs;
      this.completion = completion;
      this.opening = opening;
      this.storageRefresh = storageRefresh;
      this.clearNotified = clearNotified;
      this.userOnly = notifiedUid != null || clearNotified || StoragePolicy.userOnly(entries);
      this.protectedWrite =
          deleteUids != null || notifiedUid != null || clearNotified || entries.isEmpty() || userOnly;
      this.payloadBytes =
          StoragePolicy.batchBytes(entries)
              + 3L * (key == null ? 0 : key.length())
              + 3L * (value == null ? 0 : value.length())
              + 64L * (deleteUids == null ? 0 : deleteUids.size())
              + 3L * (notifiedUid == null ? 0 : notifiedUid.length());
    }

    static Job delete(List<String> uids, Completion completion) {
      return new Job(
          Collections.emptyList(), null, null, new ArrayList<>(uids), null, 0,
          completion, false, false, false);
    }

    static Job markNotified(String uid, long notifiedMs, Completion completion) {
      return new Job(
          Collections.emptyList(), null, null, null, uid, notifiedMs,
          completion, false, false, false);
    }

    static Job clearNotified(Completion completion) {
      return new Job(
          Collections.emptyList(), null, null, null, null, 0,
          completion, false, false, true);
    }

  }

  private static final class Database extends SQLiteOpenHelper {
    Database(Context context) {
      super(
          context,
          "mml2.db",
          null,
          6,
          db -> {
            throw new SQLiteException("Journal corruption detected. Original database retained.");
          });
      setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
      db.execSQL("PRAGMA synchronous=FULL");
      // busy_timeout returns a result row even when assigning a value.
      try (Cursor cursor = db.rawQuery("PRAGMA busy_timeout=5000", null)) {
        if (!cursor.moveToFirst()) throw new SQLiteException("Busy timeout configuration failed");
      }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
      db.execSQL(
          "CREATE TABLE entries(row_id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL"
              + " UNIQUE,time_ms INTEGER NOT NULL,captured_ms INTEGER NOT NULL,day TEXT NOT"
              + " NULL,zone TEXT NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT"
              + " NULL,type TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,"
              + "notification_key_hash TEXT NOT NULL DEFAULT '',"
              + "notified_ms INTEGER NOT NULL DEFAULT 0)");
      db.execSQL("CREATE INDEX entries_session ON entries(session,row_id)");
      db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
      db.execSQL(
          "CREATE TABLE states(key TEXT PRIMARY KEY,value TEXT NOT NULL,seen_ms INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      if (newVersion != 6 || oldVersion < 1 || oldVersion > 5)
        throw new SQLiteException("Unsupported journal upgrade. Database retained.");
      if (oldVersion <= 3) {
        if (oldVersion == 1)
          db.execSQL("ALTER TABLE entries ADD COLUMN notification_key_hash TEXT NOT NULL DEFAULT ''");
        // MML 2.0.13 removed the Notes/Archive subsystems. Keep ordinary log rows while
        // discarding obsolete private indexing/mutation machinery.
        db.execSQL("DROP TRIGGER IF EXISTS archive_entry_insert");
        db.execSQL("DROP TRIGGER IF EXISTS archive_entry_delete");
        db.execSQL("DROP TRIGGER IF EXISTS archive_entry_update");
        db.execSQL("DROP TABLE IF EXISTS archive_days");
        db.execSQL("DROP TABLE IF EXISTS note_state");
        db.execSQL("DROP INDEX IF EXISTS entries_day");
        db.execSQL("DROP INDEX IF EXISTS entries_notes");
        db.execSQL("DROP INDEX IF EXISTS entries_kind_order");
        db.execSQL("DELETE FROM entries WHERE kind IN ('NOTE','NOTE_EDIT','NOTE_DELETE')");
        db.execSQL("CREATE INDEX IF NOT EXISTS entries_session ON entries(session,row_id)");
      }
      if (oldVersion <= 4)
        db.execSQL("ALTER TABLE entries ADD COLUMN notified_ms INTEGER NOT NULL DEFAULT 0");
      // MML-owned notifications are controls/copies, not phone activity worth journaling.
      // Remove historical self-notification noise once when upgrading to schema 6.
      db.execSQL("DELETE FROM entries WHERE kind='NOTIFICATION' AND source='vip.thatiam.mml'");

    }
  }
}
