package vip.thatiam.mml;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures every permitted notification type; folder availability is never a prerequisite. */
public class NotificationCapture extends NotificationListenerService {
  static final int CAPTURE_TEST_ID = 7002;
  public static volatile boolean connected;
  private static final Handler reconnectHandler = new Handler(Looper.getMainLooper());
  private static final AtomicBoolean reconnectQueued = new AtomicBoolean();
  private static volatile WeakReference<NotificationCapture> current = new WeakReference<>(null);
  private static Diagnostics diagnostics;
  private final Handler main = new Handler(Looper.getMainLooper());
  private SharedPreferences preferences;
  private StatusBarNotification[] snapshot;
  private Set<String> pendingSnapshotKeys;
  private int snapshotIndex;
  private boolean snapshotAgain, snapshotStartQueued;
  private final Runnable snapshotStart = this::beginSnapshot;
  private final Runnable snapshotStep = this::captureChunk;

  @Override
  public void onCreate() {
    super.onCreate();
    preferences = getSharedPreferences("mml2", MODE_PRIVATE);
    if (diagnostics == null) diagnostics = new Diagnostics(preferences);
    current = new WeakReference<>(this);
    connected = false;
  }

  @Override
  public void onListenerConnected() {
    if (current.get() != this) return;
    connected = true;
    diagnostics.error = "";
    preferences
        .edit()
        .putBoolean("notification_connected", true)
        .putLong("notification_connected_ms", System.currentTimeMillis())
        .putString("notification_error", "")
        .apply();
    safeJournalRecord(
        "listener_connected", "Notification capture connected", "");
    captureActive();
  }

  @Override
  public void onListenerDisconnected() {
    cancelSnapshot();
    if (current.get() != this) return;
    connected = false;
    diagnostics.error = "Android disconnected the notification listener.";
    preferences
        .edit()
        .putBoolean("notification_connected", false)
        .putString("notification_error", "Android disconnected the notification listener.")
        .apply();
    diagnostics.flushNow();
    safeJournalRecord(
        "listener_disconnected",
        "Notification capture disconnected",
        "Reconnection requested.");
    // This request must survive onDestroy; a delayed callback owned by this service would
    // otherwise be removed when Android tears down the disconnected listener.
    requestReconnect(getApplicationContext());
  }

  @Override
  public void onDestroy() {
    cancelSnapshot();
    main.removeCallbacksAndMessages(null);
    if (current.get() == this) {
      connected = false;
      current.clear();
      if (preferences != null)
        preferences.edit().putBoolean("notification_connected", false).apply();
    }
    if (diagnostics != null) diagnostics.flushNow();
    super.onDestroy();
  }

  @Override
  public void onNotificationPosted(StatusBarNotification notification) {
    markChanged(notification);
    capture(notification, "posted", 0);
  }

  @Override
  public void onNotificationRemoved(
      StatusBarNotification notification, RankingMap rankings, int reason) {
    markChanged(notification);
    capture(notification, "removed", reason);
  }

  private void markChanged(StatusBarNotification notification) {
    // Only keys in the still-unread snapshot matter. Unrelated live arrivals cannot grow this set.
    if (notification != null && pendingSnapshotKeys != null)
      pendingSnapshotKeys.remove(notification.getKey());
  }

  private void capture(StatusBarNotification notification, String type, int reason) {
    if (notification == null) return;
    // MML-owned notifications are controls or user-requested copies, not phone activity.
    // The explicit capture test is the only self-notification allowed into the journal.
    if (getPackageName().equals(notification.getPackageName())
        && notification.getId() != CAPTURE_TEST_ID)
      return;
    long received = System.currentTimeMillis();
    if (!"active".equals(type)) {
      diagnostics.callbacks++;
      diagnostics.received = received;
      diagnostics.lastPackage = notification.getPackageName();
      diagnostics.changed();
    }
    if (!preferences.getBoolean("capture_notifications", true)) return;
    try {
      String title = NotificationText.title(notification.getNotification());
      String body = NotificationText.read(notification.getNotification());
      boolean removed = "removed".equals(type);
      if (removed) body = "Removal reason: " + reason + "\n" + body;
      String keyHash = Entry.hash(notification.getKey());
      String signature =
          Entry.hash(
              (removed ? "removed:" + reason : "present")
                  + "\n"
                  + notification.getPostTime()
                  + "\n"
                  + title
                  + "\n"
                  + body
                  + "\nnotification-key:"
                  + keyHash);
      String key = "notification:" + keyHash;
      long occurrence = removed ? received : notification.getPostTime();
      if (occurrence <= 0) occurrence = received;
      Entry entry =
          Entry.at(occurrence, "NOTIFICATION", notification.getPackageName(), type, title, body)
              .withNotificationKeyHash(keyHash)
              .withState(key, signature);
      // The completion retains the process-wide counters, not a destroyed listener instance.
      Journal.get(this).submit(entry, diagnostics::acknowledge);
    } catch (RuntimeException error) {
      String message = "Notification extraction failed: " + error.getClass().getSimpleName();
      diagnostics.error = message;
      diagnostics.changed();
      // A malformed app notification must not terminate the system-bound listener.
      safeJournalRecord(
          "notification_error",
          message,
          notification.getPackageName() + ": " + String.valueOf(error.getMessage()));
    }
  }

  private void safeJournalRecord(String type, String title, String body) {
    try {
      Journal.get(this).record("SYSTEM", "MML", type, title, body);
    } catch (RuntimeException error) {
      if (preferences != null)
        preferences
            .edit()
            .putString("notification_error", "Journal: " + Journal.describe(error))
            .apply();
      if (diagnostics != null) {
        diagnostics.error = "Journal: " + Journal.describe(error);
        diagnostics.changed();
      }
    }
  }

  private void captureActive() {
    if (!connected
        || current.get() != this
        || !preferences.getBoolean("capture_notifications", true)) return;
    if (snapshot != null) {
      snapshotAgain = true;
      return;
    }
    if (snapshotStartQueued) return;
    snapshotStartQueued = true;
    main.post(snapshotStart);
  }

  private void beginSnapshot() {
    snapshotStartQueued = false;
    if (!connected
        || current.get() != this
        || !preferences.getBoolean("capture_notifications", true)) return;
    try {
      StatusBarNotification[] active = getActiveNotifications();
      if (active != null) {
        snapshot = active;
        snapshotIndex = 0;
        pendingSnapshotKeys = new HashSet<>();
        for (StatusBarNotification item : active)
          if (item != null) pendingSnapshotKeys.add(item.getKey());
        diagnostics.activeSeen = active.length;
        diagnostics.changed();
        captureChunk();
      }
    } catch (RuntimeException error) {
      cancelSnapshot();
      diagnostics.error = "Active notifications: " + error.getClass().getSimpleName();
      diagnostics.changed();
    }
  }

  private void captureChunk() {
    if (!connected
        || current.get() != this
        || !preferences.getBoolean("capture_notifications", true)) {
      cancelSnapshot();
      return;
    }
    StatusBarNotification[] active = snapshot;
    if (active == null) return;
    int end = Math.min(snapshotIndex + 24, active.length);
    for (int i = snapshotIndex; i < end; i++) {
      StatusBarNotification item = active[i];
      active[i] = null; // Release each potentially large parcel as soon as this chunk is read.
      // A real callback received since the snapshot wins over that older snapshot.
      if (item != null && pendingSnapshotKeys.remove(item.getKey())) capture(item, "active", 0);
    }
    snapshotIndex = end;
    if (end < active.length) main.post(snapshotStep);
    else {
      snapshot = null;
      pendingSnapshotKeys = null;
      snapshotIndex = 0;
      if (snapshotAgain) {
        snapshotAgain = false;
        captureActive();
      }
    }
  }

  private void cancelSnapshot() {
    main.removeCallbacks(snapshotStart);
    main.removeCallbacks(snapshotStep);
    snapshot = null;
    pendingSnapshotKeys = null;
    snapshotIndex = 0;
    snapshotAgain = false;
    snapshotStartQueued = false;
  }

  static NotificationCapture currentListener() {
    return connected ? current.get() : null;
  }

  public static void requestReconnect(Context context) {
    Context app = context.getApplicationContext();
    SharedPreferences prefs = app.getSharedPreferences("mml2", MODE_PRIVATE);
    if (!prefs.getBoolean("capture_notifications", true)
        || !reconnectQueued.compareAndSet(false, true)) return;
    reconnectHandler.post(
        () -> {
          try {
            NotificationCapture listener = current.get();
            if (connected && listener != null) {
              listener.captureActive();
              return;
            }
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            ComponentName component = new ComponentName(app, MmlNotificationListener.class);
            if (manager != null && manager.isNotificationListenerAccessGranted(component)) {
              requestRebind(component);
            }
          } catch (RuntimeException error) {
            prefs
                .edit()
                .putString("notification_error", "Reconnect: " + error.getClass().getSimpleName())
                .apply();
          } finally {
            reconnectQueued.set(false);
          }
        });
  }

  /** Force a diagnostic checkpoint on the main thread; journal event commits are independent. */
  static void flushDiagnostics() {
    if (Looper.myLooper() != Looper.getMainLooper())
      throw new IllegalStateException("Flush notification diagnostics on the main thread.");
    if (diagnostics != null) diagnostics.flushNow();
  }

  /** Main-thread counters survive listener replacement and produce at most one regular write/s. */
  private static final class Diagnostics {
    final SharedPreferences preferences;
    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable flush = this::flushNow;
    long callbacks, acknowledged, received, committed;
    int activeSeen;
    String lastPackage, error;
    boolean scheduled;

    Diagnostics(SharedPreferences preferences) {
      this.preferences = preferences;
      callbacks = preferences.getLong("notification_callbacks", 0);
      acknowledged = preferences.getLong("notification_saved_callbacks", 0);
      received = preferences.getLong("notification_last_received_ms", 0);
      committed = preferences.getLong("notification_last_committed_ms", 0);
      activeSeen = preferences.getInt("notification_active_seen", 0);
      lastPackage = preferences.getString("notification_last_package", "");
      error = preferences.getString("notification_error", "");
    }

    void acknowledge(boolean success, String message) {
      if (success) {
        acknowledged++;
        committed = System.currentTimeMillis();
        if (connected) error = "";
      } else error = message;
      changed();
    }

    void changed() {
      if (scheduled) return;
      scheduled = true;
      handler.postDelayed(flush, 1000);
    }

    void flushNow() {
      handler.removeCallbacks(flush);
      scheduled = false;
      preferences
          .edit()
          .putLong("notification_callbacks", callbacks)
          .putLong("notification_saved_callbacks", acknowledged)
          .putLong("notification_last_received_ms", received)
          .putLong("notification_last_committed_ms", committed)
          .putInt("notification_active_seen", activeSeen)
          .putString("notification_last_package", lastPackage)
          .putString("notification_error", error)
          .apply();
    }
  }
}
