package vip.thatiam.mml;

import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Latest-only process worker for the bounded but potentially large future-entry scan. */
public final class MmlFutureEntryCount {
    public static final String ACTION_COUNT_CHANGED =
            "vip.thatiam.mml.action.FUTURE_ENTRY_COUNT_CHANGED";
    private static final int NOTIFICATION_SUMMARY_ENTRY_LIMIT = 20;
    private static final int NOTIFICATION_SUMMARY_TEXT_CODE_POINTS = 120;
    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "MML-future-entry-count");
        worker.setDaemon(true);
        return worker;
    });
    private static JournalStore latestStore;
    private static Context applicationContext;
    private static long latestNow;
    private static long requestEpoch;
    private static boolean running;
    private static boolean latestRequestPermission;
    private static FutureEntryRefreshPolicy.Mode latestUpdatePolicy =
            FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY;
    private static int latestBaselineCount;
    private static int cachedCount;
    private static List<CalendarComment> cachedSummaries = Collections.emptyList();
    private static boolean hasValue;
    private static long generation;
    private static long acknowledgedGeneration;
    private static String lastError = "";
    private static boolean completedRequestPermission;
    private static FutureEntryRefreshPolicy.Mode completedUpdatePolicy =
            FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY;
    private static int completedBaselineCount;

    private MmlFutureEntryCount() {
    }

    public static void request(
            Context context,
            JournalStore store,
            long nowEpochMillis,
            boolean requestPermissionIfNeeded,
            FutureEntryRefreshPolicy.Mode updatePolicy,
            int baselineCount) {
        if (context == null || store == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            return;
        }
        synchronized (LOCK) {
            boolean preserveExistingIntent =
                    FutureEntryRefreshPolicy.shouldMergeExistingIntent(
                            running, generation, acknowledgedGeneration);
            FutureEntryRefreshPolicy.Request merged = FutureEntryRefreshPolicy.merge(
                    preserveExistingIntent,
                    latestUpdatePolicy,
                    latestBaselineCount,
                    updatePolicy,
                    baselineCount);
            applicationContext = app;
            latestStore = store;
            latestNow = nowEpochMillis;
            latestRequestPermission = FutureEntryRefreshPolicy.mergePermissionRequest(
                    preserveExistingIntent,
                    latestRequestPermission,
                    requestPermissionIfNeeded);
            latestUpdatePolicy = merged.getMode();
            latestBaselineCount = merged.getBaseline();
            requestEpoch++;
            if (running) {
                return;
            }
            running = true;
        }
        try {
            EXECUTOR.execute(MmlFutureEntryCount::runLatest);
        } catch (RejectedExecutionException rejected) {
            synchronized (LOCK) {
                running = false;
                lastError = "Could not schedule the Future events scan.";
                generation++;
            }
            notifyChanged(app);
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return snapshotLocked();
        }
    }

    /** Atomically transfers one completion to MainActivity, or leaves it mergeable by newer work. */
    public static Snapshot claimCompletedSnapshot(long previouslyConsumedGeneration) {
        synchronized (LOCK) {
            if (!FutureEntryRefreshPolicy.shouldClaimCompletion(
                    running,
                    generation,
                    previouslyConsumedGeneration,
                    acknowledgedGeneration)) {
                return null;
            }
            acknowledgedGeneration = generation;
            return snapshotLocked();
        }
    }

    private static Snapshot snapshotLocked() {
        return new Snapshot(
                running,
                hasValue,
                cachedCount,
                cachedSummaries,
                generation,
                lastError,
                completedRequestPermission,
                completedUpdatePolicy,
                completedBaselineCount);
    }

    private static void runLatest() {
        while (true) {
            JournalStore store;
            Context context;
            long now;
            long epoch;
            boolean requestPermission;
            FutureEntryRefreshPolicy.Mode updatePolicy;
            int baselineCount;
            synchronized (LOCK) {
                store = latestStore;
                context = applicationContext;
                now = latestNow;
                epoch = requestEpoch;
                requestPermission = latestRequestPermission;
                updatePolicy = latestUpdatePolicy;
                baselineCount = latestBaselineCount;
            }
            int count = 0;
            List<CalendarComment> summaries = Collections.emptyList();
            String error = "";
            try {
                JournalStore.FutureCalendarCommentScan scan =
                        store.scanFutureCalendarComments(
                                now,
                                NOTIFICATION_SUMMARY_ENTRY_LIMIT,
                                NOTIFICATION_SUMMARY_TEXT_CODE_POINTS);
                count = scan.getCount();
                summaries = scan.getSummaries();
            } catch (IOException | RuntimeException failure) {
                String detail = failure.getMessage();
                error = detail == null || detail.isBlank()
                        ? "Could not read future calendar Comments."
                        : bounded(detail, 600);
            }
            synchronized (LOCK) {
                if (epoch != requestEpoch) {
                    continue;
                }
                cachedCount = count;
                cachedSummaries = error.isEmpty()
                        ? Collections.unmodifiableList(new ArrayList<>(summaries))
                        : Collections.emptyList();
                hasValue = error.isEmpty();
                lastError = error;
                completedRequestPermission = requestPermission;
                completedUpdatePolicy = updatePolicy;
                completedBaselineCount = baselineCount;
                running = false;
                generation++;
            }
            notifyChanged(context);
            return;
        }
    }

    private static void notifyChanged(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.sendBroadcast(new Intent(ACTION_COUNT_CHANGED)
                    .setPackage(context.getPackageName()));
        } catch (RuntimeException ignored) {
            // The next Activity directly reconciles the retained process snapshot.
        }
    }

    private static String bounded(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    public static final class Snapshot {
        private final boolean running;
        private final boolean hasValue;
        private final int count;
        private final List<CalendarComment> summaries;
        private final long generation;
        private final String error;
        private final boolean requestPermission;
        private final FutureEntryRefreshPolicy.Mode updatePolicy;
        private final int baselineCount;

        private Snapshot(
                boolean running,
                boolean hasValue,
                int count,
                List<CalendarComment> summaries,
                long generation,
                String error,
                boolean requestPermission,
                FutureEntryRefreshPolicy.Mode updatePolicy,
                int baselineCount) {
            this.running = running;
            this.hasValue = hasValue;
            this.count = count;
            this.summaries = Collections.unmodifiableList(new ArrayList<>(summaries));
            this.generation = generation;
            this.error = error;
            this.requestPermission = requestPermission;
            this.updatePolicy = updatePolicy;
            this.baselineCount = baselineCount;
        }

        public boolean isRunning() { return running; }
        public boolean hasValue() { return hasValue; }
        public int getCount() { return count; }
        public List<CalendarComment> getSummaries() { return summaries; }
        public long getGeneration() { return generation; }
        public String getError() { return error; }
        public boolean shouldRequestPermission() { return requestPermission; }
        public FutureEntryRefreshPolicy.Mode getUpdatePolicy() { return updatePolicy; }
        public int getBaselineCount() { return baselineCount; }
    }
}
