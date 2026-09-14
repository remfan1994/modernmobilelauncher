package vip.thatiam.mml;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Opt-in Android Notification Access bridge into MML's private current-session journal. */
public final class MmlNotificationListener extends NotificationListenerService {
    public static final String ACTION_NOTIFICATION_CAPTURED =
            "vip.thatiam.mml.action.NOTIFICATION_CAPTURED";
    public static final String ACTION_LISTENER_STATE_CHANGED =
            "vip.thatiam.mml.action.NOTIFICATION_LISTENER_STATE_CHANGED";
    public static final String PREFERENCES = "mml-settings";
    public static final String PREF_CAPTURE_ENABLED = "notification-capture-enabled";
    private static final Object CONNECTED_LISTENER_LOCK = new Object();
    private static final Object CAPTURE_TASK_LOCK = new Object();
    private static final long REBIND_RETRY_INTERVAL_MILLIS = 5_000L;
    private static final int MAX_ACTIVE_SNAPSHOT_COUNT = 512;
    private static final int MAX_REPLAY_SCAN_COUNT = 512;
    private static WeakReference<MmlNotificationListener> connectedListener =
            new WeakReference<>(null);
    private static boolean rebindRequested;
    private static long lastRebindRequestElapsedRealtime;
    private static long captureStorageGeneration;
    private static long lastCaptureClearGeneration;
    private static boolean captureMaintenanceBusy;
    private static String captureMaintenanceDescription = "";
    private static String lastCaptureMaintenanceResult = "";
    private static long captureMaintenanceGeneration;
    private static JournalStore processCaptureStore;
    private static final ThreadPoolExecutor CAPTURE_EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(JournalStore.MAX_STORED_CAPTURE_COUNT));

    public enum ReplayResult {
        OPENED,
        LISTENER_UNAVAILABLE,
        NO_LONGER_ACTIVE,
        NO_TAP_ACTION,
        CANCELED,
        FAILED
    }

    private volatile boolean listenerConnected;

    public static final class CaptureMaintenanceSnapshot {
        private final boolean busy;
        private final String description;
        private final String lastResult;
        private final long generation;

        private CaptureMaintenanceSnapshot(
                boolean busy,
                String description,
                String lastResult,
                long generation) {
            this.busy = busy;
            this.description = description;
            this.lastResult = lastResult;
            this.generation = generation;
        }

        public boolean isBusy() {
            return busy;
        }

        public String getDescription() {
            return description;
        }

        public String getLastResult() {
            return lastResult;
        }

        public long getGeneration() {
            return generation;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        synchronized (CONNECTED_LISTENER_LOCK) {
            listenerConnected = true;
            connectedListener = new WeakReference<>(this);
            rebindRequested = false;
        }
        List<CapturedNotification> activeSnapshot = activeNotificationSnapshots();
        Context applicationContext = getApplicationContext();
        if (applicationContext != null) {
            submitCaptureTask(CaptureStorageTask.captureBatch(
                    applicationContext, activeSnapshot));
        }
        notifyListenerStateChanged();
    }

    @Override
    public void onListenerDisconnected() {
        clearConnectedListener();
        notifyListenerStateChanged();
        super.onListenerDisconnected();
        requestListenerRebind(this);
    }

    @Override
    public void onDestroy() {
        clearConnectedListener();
        notifyListenerStateChanged();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification statusBarNotification) {
        CapturedNotification snapshot;
        try {
            snapshot = notificationSnapshot(statusBarNotification);
        } catch (RuntimeException ignored) {
            // A hostile/mutating notification graph or unavailable preferences must not crash the
            // listener process. Nothing was queued, so Android's original notification is intact.
            return;
        }
        if (snapshot == null) {
            return;
        }
        Context applicationContext = getApplicationContext();
        if (applicationContext != null) {
            submitCaptureTask(CaptureStorageTask.captureOne(
                    applicationContext, snapshot, captureTaskKey(snapshot)));
        }
    }

    /** Distinguishes a granted Android access switch from a live listener connection. */
    public static boolean isListenerConnected() {
        synchronized (CONNECTED_LISTENER_LOCK) {
            MmlNotificationListener listener = connectedListener.get();
            return listener != null && listener.listenerConnected;
        }
    }

    /** Requests Android's supported recovery path after the listener binding is lost. */
    public static void requestListenerRebind(Context context) {
        if (context == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (CONNECTED_LISTENER_LOCK) {
            MmlNotificationListener listener = connectedListener.get();
            if (listener != null && listener.listenerConnected) {
                rebindRequested = false;
                return;
            }
            if (rebindRequested
                    && now - lastRebindRequestElapsedRealtime
                    < REBIND_RETRY_INTERVAL_MILLIS) {
                return;
            }
            rebindRequested = true;
            lastRebindRequestElapsedRealtime = now;
        }
        try {
            requestRebind(new ComponentName(context, MmlNotificationListener.class));
        } catch (RuntimeException ignored) {
            synchronized (CONNECTED_LISTENER_LOCK) {
                rebindRequested = false;
            }
            // Android owns the special-access binding and may reject a request during revocation.
        }
    }

    /** Queues global capture redaction without retaining an Activity or service instance. */
    public static boolean enqueueCapturedNotificationClear(
            Context context,
            JournalStore store) {
        Context applicationContext = applicationContextOrNull(context);
        if (applicationContext == null || store == null) {
            return false;
        }
        synchronized (CAPTURE_TASK_LOCK) {
            if (captureMaintenanceBusy) {
                return false;
            }
            captureMaintenanceBusy = true;
            captureMaintenanceDescription = "Clearing captured notification data";
            lastCaptureMaintenanceResult = "";
            captureMaintenanceGeneration++;
        }
        boolean queued = enqueueStorageTask(
                CaptureStorageTask.clearCaptures(applicationContext, store));
        if (!queued) {
            finishCaptureMaintenance(
                    applicationContext,
                    "Could not schedule captured-notification clearing.",
                    false);
        }
        return queued;
    }

    /** Coalesces inbox drain/dedup on the serialized capture-storage lane. */
    public static boolean enqueueCapturedNotificationDrain(
            Context context,
            JournalStore store,
            String sessionId) {
        Context applicationContext = applicationContextOrNull(context);
        if (applicationContext == null || store == null
                || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return enqueueStorageTask(CaptureStorageTask.drainInbox(
                applicationContext, store, sessionId));
    }

    public static CaptureMaintenanceSnapshot captureMaintenanceSnapshot() {
        synchronized (CAPTURE_TASK_LOCK) {
            return new CaptureMaintenanceSnapshot(
                    captureMaintenanceBusy,
                    captureMaintenanceDescription,
                    lastCaptureMaintenanceResult,
                    captureMaintenanceGeneration);
        }
    }

    /** Lets a recreated Activity detect a storage notification sent during receiver handoff. */
    public static long captureStorageGeneration() {
        synchronized (CAPTURE_TASK_LOCK) {
            return captureStorageGeneration;
        }
    }

    /** Atomically returns {latest mutation, latest Clear} for Activity handoff recovery. */
    public static long[] captureStorageState() {
        synchronized (CAPTURE_TASK_LOCK) {
            return new long[] {captureStorageGeneration, lastCaptureClearGeneration};
        }
    }

    /** Announces a durable capture mutation without placing private notification text in Intent. */
    public static void notifyCaptureStorageChanged(Context context) {
        notifyCaptureStorageChanged(context, false);
    }

    /** Announces global capture redaction so archived Calendar views can also be rebuilt. */
    public static void notifyCapturedNotificationsCleared(Context context) {
        notifyCaptureStorageChanged(context, true);
    }

    private static void notifyCaptureStorageChanged(Context context, boolean captureClear) {
        if (context == null) {
            return;
        }
        synchronized (CAPTURE_TASK_LOCK) {
            captureStorageGeneration++;
            if (captureClear) {
                lastCaptureClearGeneration = captureStorageGeneration;
            }
        }
        try {
            Context applicationContext = context.getApplicationContext();
            Context sender = applicationContext == null ? context : applicationContext;
            sender.sendBroadcast(new Intent(ACTION_NOTIFICATION_CAPTURED)
                    .setPackage(context.getPackageName()));
        } catch (RuntimeException ignored) {
            // The generation still lets the next Activity lifecycle reconcile durable storage.
        }
    }

    private static void submitCaptureTask(CaptureStorageTask task) {
        // Capture work is bounded to the durable storage bound. Under a callback storm, an older
        // queued capture may be replaced, but explicit storage fences are never discarded.
        enqueueStorageTask(task);
    }

    private static boolean enqueueStorageTask(CaptureStorageTask task) {
        if (task == null) {
            return false;
        }
        synchronized (CAPTURE_TASK_LOCK) {
            BlockingQueue<Runnable> queue = CAPTURE_EXECUTOR.getQueue();
            if (task.coalescingKey != null) {
                removeQueuedCaptureWithKey(queue, task.coalescingKey);
            }
            try {
                CAPTURE_EXECUTOR.execute(task);
                return true;
            } catch (RejectedExecutionException full) {
                if (!removeOldestQueuedCapture(queue)) {
                    return false;
                }
                return queue.offer(task);
            }
        }
    }

    private static void removeQueuedCaptureWithKey(
            BlockingQueue<Runnable> queue,
            String coalescingKey) {
        for (Runnable queued : queue) {
            if (queued instanceof CaptureStorageTask) {
                CaptureStorageTask capture = (CaptureStorageTask) queued;
                if (!capture.storageFence && coalescingKey.equals(capture.coalescingKey)) {
                    queue.remove(queued);
                    return;
                }
            }
        }
    }

    private static boolean removeOldestQueuedCapture(BlockingQueue<Runnable> queue) {
        for (Runnable queued : queue) {
            if (queued instanceof CaptureStorageTask
                    && !((CaptureStorageTask) queued).storageFence
                    && queue.remove(queued)) {
                return true;
            }
        }
        return false;
    }

    private static String captureTaskKey(CapturedNotification notification) {
        if (notification == null) {
            return "null-notification";
        }
        return notification.getSourcePackage() + "|" + notification.getCaptureId();
    }

    private static final class CaptureStorageTask implements Runnable {
        private final TaskKind kind;
        private final Context applicationContext;
        private final CapturedNotification capture;
        private final List<CapturedNotification> captures;
        private final JournalStore store;
        private final String sessionId;
        private final boolean storageFence;
        private final String coalescingKey;

        private CaptureStorageTask(
                TaskKind kind,
                Context applicationContext,
                CapturedNotification capture,
                List<CapturedNotification> captures,
                JournalStore store,
                String sessionId,
                boolean storageFence,
                String coalescingKey) {
            this.kind = kind;
            this.applicationContext = applicationContext;
            this.capture = capture;
            this.captures = captures;
            this.store = store;
            this.sessionId = sessionId;
            this.storageFence = storageFence;
            this.coalescingKey = coalescingKey;
        }

        static CaptureStorageTask captureOne(
                Context context,
                CapturedNotification capture,
                String coalescingKey) {
            return new CaptureStorageTask(
                    TaskKind.CAPTURE_ONE,
                    context,
                    capture,
                    null,
                    null,
                    null,
                    false,
                    coalescingKey);
        }

        static CaptureStorageTask captureBatch(
                Context context,
                List<CapturedNotification> captures) {
            return new CaptureStorageTask(
                    TaskKind.CAPTURE_BATCH,
                    context,
                    null,
                    captures == null ? List.of() : List.copyOf(captures),
                    null,
                    null,
                    false,
                    "active-snapshot");
        }

        static CaptureStorageTask clearCaptures(Context context, JournalStore store) {
            return new CaptureStorageTask(
                    TaskKind.CLEAR,
                    context,
                    null,
                    null,
                    store,
                    null,
                    true,
                    null);
        }

        static CaptureStorageTask drainInbox(
                Context context,
                JournalStore store,
                String sessionId) {
            return new CaptureStorageTask(
                    TaskKind.DRAIN,
                    context,
                    null,
                    null,
                    store,
                    sessionId,
                    false,
                    "drain|" + sessionId);
        }

        @Override
        public void run() {
            switch (kind) {
                case CAPTURE_ONE:
                    captureOneOnWorker(applicationContext, capture);
                    break;
                case CAPTURE_BATCH:
                    captureBatchOnWorker(applicationContext, captures);
                    break;
                case CLEAR:
                    clearCapturesOnWorker(applicationContext, store);
                    break;
                case DRAIN:
                    drainInboxOnWorker(applicationContext, store, sessionId);
                    break;
                default:
                    break;
            }
        }
    }

    private enum TaskKind {
        CAPTURE_ONE,
        CAPTURE_BATCH,
        CLEAR,
        DRAIN
    }

    private List<CapturedNotification> activeNotificationSnapshots() {
        List<CapturedNotification> snapshots = new ArrayList<>();
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException ignored) {
            return snapshots;
        }
        if (active == null) {
            return snapshots;
        }
        try {
            int limit = Math.min(active.length, MAX_ACTIVE_SNAPSHOT_COUNT);
            for (int index = 0; index < limit; index++) {
                StatusBarNotification notification = active[index];
                active[index] = null;
                CapturedNotification snapshot;
                try {
                    snapshot = notificationSnapshot(notification);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
        } finally {
            // Do not keep unscanned RemoteViews/bitmap graphs alive until a later GC root walk.
            Arrays.fill(active, null);
        }
        return List.copyOf(snapshots);
    }

    private static void captureBatchOnWorker(
            Context applicationContext,
            List<CapturedNotification> active) {
        if (active == null || active.isEmpty()) {
            return;
        }
        try {
            JournalStore store = captureStore(applicationContext);
            int stored = store.storeCapturedNotificationsBatch(
                    MmlProcessSession.activeSessionId(), active);
            if (stored > 0) {
                notifyCaptureStorageChanged(applicationContext);
            }
        } catch (IOException | RuntimeException ignored) {
            // A listener has no foreground UI; a later rebind/repost safely retries by stable ID.
        }
    }

    private static void captureOneOnWorker(
            Context applicationContext,
            CapturedNotification capture) {
        if (captureNotificationOnWorker(applicationContext, capture)) {
            notifyCaptureStorageChanged(applicationContext);
        }
    }

    private static boolean captureNotificationOnWorker(
            Context applicationContext,
            CapturedNotification capture) {
        if (capture == null) {
            return false;
        }
        boolean storageChanged = false;
        try {
            JournalStore store = captureStore(applicationContext);
            String sessionId = MmlProcessSession.activeSessionId();
            JournalStore.CaptureDisposition disposition =
                    JournalStore.CaptureDisposition.SESSION_CLOSED;
            if (sessionId != null) {
                disposition = store.appendCapturedNotificationResult(sessionId, capture);
                storageChanged = disposition == JournalStore.CaptureDisposition.STORED;
            }
            if (sessionId == null
                    || disposition == JournalStore.CaptureDisposition.SESSION_CLOSED) {
                storageChanged |= store.queueCapturedNotificationResult(capture)
                        == JournalStore.CaptureDisposition.STORED;
            }
        } catch (IOException | RuntimeException ignored) {
            // A listener has no safe foreground UI. Leave Android's original notification alone.
        }
        return storageChanged;
    }

    private static void clearCapturesOnWorker(Context context, JournalStore store) {
        try {
            int removed = store.clearCapturedNotifications();
            finishCaptureMaintenance(
                    context,
                    removed > 0
                            ? "Cleared " + removed
                                    + (removed == 1
                                    ? " captured notification."
                                    : " captured notifications.")
                            : "Captured notification data cleared.",
                    true);
        } catch (IOException | RuntimeException error) {
            finishCaptureMaintenance(
                    context,
                    "Captured-notification clearing failed: " + safeMessage(error),
                    true);
        }
    }

    private static void drainInboxOnWorker(
            Context context,
            JournalStore store,
            String sessionId) {
        try {
            int appended = store.drainCapturedNotifications(sessionId);
            if (appended > 0) {
                notifyCaptureStorageChanged(context);
            }
        } catch (IOException | RuntimeException error) {
            synchronized (CAPTURE_TASK_LOCK) {
                lastCaptureMaintenanceResult =
                        "Notification inbox drain failed: " + safeMessage(error);
                captureMaintenanceGeneration++;
            }
            notifyCaptureStorageChanged(context);
        }
    }

    private static void finishCaptureMaintenance(
            Context context,
            String result,
            boolean captureClear) {
        synchronized (CAPTURE_TASK_LOCK) {
            captureMaintenanceBusy = false;
            captureMaintenanceDescription = "";
            lastCaptureMaintenanceResult = result == null ? "" : result;
            captureMaintenanceGeneration++;
        }
        notifyCaptureStorageChanged(context, captureClear);
    }

    private static JournalStore captureStore(Context context) throws IOException {
        if (processCaptureStore == null) {
            processCaptureStore = MmlLogStorage.openJournalStore(context);
        }
        return processCaptureStore;
    }

    private static Context applicationContextOrNull(Context context) {
        if (context == null) {
            return null;
        }
        return context.getApplicationContext();
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "unknown storage failure";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return CapturedNotification.sanitize(message, 300);
    }

    /** Extracts only bounded immutable text/identity before anything enters the static queue. */
    private CapturedNotification notificationSnapshot(StatusBarNotification statusBarNotification) {
        if (statusBarNotification == null) {
            return null;
        }
        android.content.SharedPreferences preferences =
                getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        LogVerbosity verbosity;
        try {
            verbosity = LogVerbosity.fromPreference(preferences.getString(
                    LogVerbosity.PREFERENCE_KEY,
                    LogVerbosity.DEFAULT.getPreferenceValue()));
        } catch (ClassCastException | IllegalArgumentException ignored) {
            verbosity = LogVerbosity.DEFAULT;
        }
        boolean captureEnabled;
        try {
            captureEnabled = preferences.getBoolean(PREF_CAPTURE_ENABLED, true);
        } catch (ClassCastException | IllegalArgumentException ignored) {
            captureEnabled = true;
        }
        if (!captureEnabled || !verbosity.includesNotifications()) {
            return null;
        }
        String sourcePackage = statusBarNotification.getPackageName();
        if (sourcePackage == null || sourcePackage.equals(getPackageName())) {
            // Never feed MML's future-events summary back into MML's own journal.
            return null;
        }
        Notification notification = statusBarNotification.getNotification();
        if (notification == null
                || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null;
        }

        CapturedNotification capture;
        try {
            capture = buildCapture(statusBarNotification, notification);
        } catch (RuntimeException ignored) {
            return null;
        }
        return capture;
    }

    private void notifyListenerStateChanged() {
        try {
            sendBroadcast(new Intent(ACTION_LISTENER_STATE_CHANGED).setPackage(getPackageName()));
        } catch (RuntimeException ignored) {
            // Static connection state remains discoverable when an Activity next resumes.
        }
    }

    /**
     * Performs only the live row-click operation supplied by the still-active notification.
     * No PendingIntent or raw notification key is reconstructed from journal text or persisted.
     */
    public static ReplayResult replayCapturedNotification(CapturedNotification captured) {
        if (captured == null) {
            return ReplayResult.FAILED;
        }
        MmlNotificationListener listener;
        synchronized (CONNECTED_LISTENER_LOCK) {
            listener = connectedListener.get();
            if (listener == null || !listener.listenerConnected) {
                return ReplayResult.LISTENER_UNAVAILABLE;
            }
        }
        return listener.replayActiveNotification(captured);
    }

    private ReplayResult replayActiveNotification(CapturedNotification captured) {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException error) {
            return ReplayResult.LISTENER_UNAVAILABLE;
        } catch (RuntimeException error) {
            return listenerConnected
                    ? ReplayResult.FAILED : ReplayResult.LISTENER_UNAVAILABLE;
        }
        if (active == null) {
            return ReplayResult.FAILED;
        }

        try {
            int scanLimit = Math.min(active.length, MAX_REPLAY_SCAN_COUNT);
            for (int index = 0; index < scanLimit; index++) {
                StatusBarNotification statusBarNotification = active[index];
                active[index] = null;
                if (statusBarNotification == null
                        || getPackageName().equals(statusBarNotification.getPackageName())) {
                    continue;
                }
                Notification notification = statusBarNotification.getNotification();
                if (notification == null
                        || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                    continue;
                }
                CapturedNotification current;
                try {
                    current = buildCapture(statusBarNotification, notification);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (!sameCapturedSnapshot(captured, current)) {
                    continue;
                }

                PendingIntent tapAction = notification.contentIntent;
                if (tapAction == null) {
                    return ReplayResult.NO_TAP_ACTION;
                }
                try {
                    // Supplying no fill-in Intent preserves the exact capability created by the
                    // notification's source instead of allowing journal text to influence dispatch.
                    tapAction.send();
                } catch (PendingIntent.CanceledException error) {
                    return ReplayResult.CANCELED;
                } catch (SecurityException error) {
                    return ReplayResult.FAILED;
                } catch (RuntimeException error) {
                    return ReplayResult.FAILED;
                }

                if ((notification.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                    try {
                        cancelNotification(statusBarNotification.getKey());
                    } catch (RuntimeException ignored) {
                        // The requested action already succeeded; cancellation is best effort.
                    }
                }
                return ReplayResult.OPENED;
            }
            return ReplayResult.NO_LONGER_ACTIVE;
        } finally {
            Arrays.fill(active, null);
        }
    }

    private static boolean sameCapturedSnapshot(
            CapturedNotification saved,
            CapturedNotification active) {
        return saved.getCaptureId().equals(active.getCaptureId())
                && saved.getPostedEpochMillis() == active.getPostedEpochMillis()
                && saved.getSourcePackage().equals(active.getSourcePackage())
                && saved.getTitle().equals(active.getTitle())
                && saved.getBody().equals(active.getBody())
                && saved.getSubText().equals(active.getSubText());
    }

    private void clearConnectedListener() {
        synchronized (CONNECTED_LISTENER_LOCK) {
            listenerConnected = false;
            if (connectedListener.get() == this) {
                connectedListener = new WeakReference<>(null);
            }
        }
    }

    private CapturedNotification buildCapture(
            StatusBarNotification statusBarNotification,
            Notification notification) {
        Bundle extras = notification.extras == null ? new Bundle() : notification.extras;
        CharSequence title = firstVisibleText(
                extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
                extras.getCharSequence(Notification.EXTRA_TITLE));
        CharSequence body = firstVisibleText(
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                extras.getCharSequence(Notification.EXTRA_TEXT),
                joinedTextLines(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)),
                notification.tickerText);
        CharSequence subText = firstVisibleText(
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
                extras.getCharSequence(Notification.EXTRA_INFO_TEXT),
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        long posted = Math.max(0L, statusBarNotification.getPostTime());
        String zoneId = ZoneId.systemDefault().getId();
        String appLabel = applicationLabel(statusBarNotification.getPackageName());
        CapturedNotification bounded = CapturedNotification.create(
                "pending-capture-id",
                posted,
                zoneId,
                statusBarNotification.getPackageName(),
                appLabel,
                title,
                body,
                subText);
        String captureId = CapturedNotification.sha256CaptureId(
                bounded.getSourcePackage(),
                stableNotificationIdentity(statusBarNotification),
                posted,
                bounded.getTitle(),
                bounded.getBody(),
                bounded.getSubText());
        return CapturedNotification.create(
                captureId,
                posted,
                zoneId,
                bounded.getSourcePackage(),
                bounded.getAppLabel(),
                bounded.getTitle(),
                bounded.getBody(),
                bounded.getSubText());
    }

    private String applicationLabel(String sourcePackage) {
        PackageManager manager = getPackageManager();
        try {
            ApplicationInfo info = manager.getApplicationInfo(
                    sourcePackage, PackageManager.ApplicationInfoFlags.of(0L));
            CharSequence label = manager.getApplicationLabel(info);
            return label == null
                    ? sourcePackage
                    : CapturedNotification.sanitize(
                            label, CapturedNotification.MAX_APP_LABEL_CODE_POINTS);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return sourcePackage;
        }
    }

    private static String stableNotificationIdentity(StatusBarNotification notification) {
        String key = notification.getKey();
        if (key != null && !key.isBlank()) {
            return CapturedNotification.sanitize(key, 512);
        }
        return CapturedNotification.sanitize(
                notification.getPackageName()
                + "|" + notification.getId()
                + "|" + String.valueOf(notification.getTag()),
                512);
    }

    private static CharSequence firstVisibleText(CharSequence... candidates) {
        for (CharSequence candidate : candidates) {
            if (hasVisibleText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static CharSequence joinedTextLines(CharSequence[] lines) {
        if (lines == null || lines.length == 0) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        int lineLimit = Math.min(lines.length, 256);
        for (int lineIndex = 0; lineIndex < lineLimit; lineIndex++) {
            CharSequence line = lines[lineIndex];
            if (!hasVisibleText(line)) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            appendBounded(
                    joined,
                    line,
                    CapturedNotification.MAX_BODY_CODE_POINTS
                            - joined.codePointCount(0, joined.length()));
            if (joined.codePointCount(0, joined.length())
                    >= CapturedNotification.MAX_BODY_CODE_POINTS) {
                break;
            }
        }
        return joined;
    }

    private static boolean hasVisibleText(CharSequence value) {
        if (value == null) {
            return false;
        }
        try {
            int limit = Math.min(value.length(), CapturedNotification.MAX_BODY_CODE_POINTS);
            for (int index = 0; index < limit; index++) {
                if (!Character.isWhitespace(value.charAt(index))) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static void appendBounded(
            StringBuilder destination,
            CharSequence source,
            int maximumCodePoints) {
        if (source == null || maximumCodePoints <= 0) {
            return;
        }
        try {
            int length = source.length();
            int retained = 0;
            for (int offset = 0; offset < length && retained < maximumCodePoints;) {
                char first = source.charAt(offset++);
                if (Character.isHighSurrogate(first)
                        && offset < length
                        && Character.isLowSurrogate(source.charAt(offset))) {
                    destination.append(first).append(source.charAt(offset++));
                } else {
                    destination.append(first);
                }
                retained++;
            }
        } catch (RuntimeException ignored) {
            // Keep only the safe prefix already copied from a hostile CharSequence.
        }
    }
}
