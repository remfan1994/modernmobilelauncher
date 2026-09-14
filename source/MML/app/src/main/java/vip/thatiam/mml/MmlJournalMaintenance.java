package vip.thatiam.mml;

import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Process-global single-flight coordinator for journal scans and destructive maintenance. */
public final class MmlJournalMaintenance {
    public static final String ACTION_OPERATION_CHANGED =
            "vip.thatiam.mml.action.JOURNAL_MAINTENANCE_CHANGED";
    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "MML-maintenance");
        worker.setDaemon(true);
        return worker;
    });
    private static boolean busy;
    private static String description = "";
    private static long generation;
    private static long acknowledgedGeneration;
    private static Kind completedKind;
    private static int completedValue;
    private static int completedAuxiliaryValue;
    private static long completedCutoff;
    private static long completedDays;
    private static String completedTargetLocalDate = "";
    private static String completedTargetZoneId = "";
    private static String completedError = "";

    private MmlJournalMaintenance() {
    }

    public enum Kind {
        COUNT_OLD("Counting archived journal records"),
        DELETE_OLD("Deleting selected archived journal records"),
        CLEAR_HISTORY("Clearing closed journal history"),
        CLEAR_APP_MANAGED_LOGS("Clearing app-managed logs"),
        COUNT_COMMENTS("Counting calendar Comments"),
        CLEAR_COMMENTS("Clearing calendar Comments"),
        SAVE_COMMENT("Saving calendar Comments");

        private final String description;

        Kind(String description) {
            this.description = description;
        }
    }

    public static boolean begin(
            Context context,
            JournalStore store,
            Kind kind,
            String preservedSessionId,
            long cutoff,
            long days) {
        return beginInternal(
                context,
                store,
                kind,
                preservedSessionId,
                cutoff,
                days,
                "",
                "",
                0L,
                "",
                "");
    }

    public static boolean beginAppManagedLogClear(
            Context context,
            JournalStore store,
            String preservedSessionId) {
        return begin(
                context,
                store,
                Kind.CLEAR_APP_MANAGED_LOGS,
                preservedSessionId,
                0L,
                0L);
    }

    public static boolean beginCalendarCommentMutation(
            Context context,
            JournalStore store,
            String targetLocalDate,
            String targetZoneId,
            long observedAt,
            String observedZoneId,
            String text) {
        return beginInternal(
                context,
                store,
                Kind.SAVE_COMMENT,
                null,
                0L,
                0L,
                targetLocalDate,
                targetZoneId,
                observedAt,
                observedZoneId,
                text);
    }

    private static boolean beginInternal(
            Context context,
            JournalStore store,
            Kind kind,
            String preservedSessionId,
            long cutoff,
            long days,
            String targetLocalDate,
            String targetZoneId,
            long observedAt,
            String observedZoneId,
            String text) {
        if (context == null || store == null || kind == null) {
            return false;
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            return false;
        }
        synchronized (LOCK) {
            if (!JournalMaintenancePolicy.canBegin(
                    busy,
                    completedKind != null,
                    generation,
                    acknowledgedGeneration)) {
                return false;
            }
            busy = true;
            description = kind.description;
            completedKind = null;
            completedError = "";
            generation++;
        }
        notifyChanged(applicationContext);
        try {
            EXECUTOR.execute(() -> run(
                    applicationContext,
                    store,
                    kind,
                    preservedSessionId,
                    cutoff,
                    days,
                    targetLocalDate,
                    targetZoneId,
                    observedAt,
                    observedZoneId,
                    text));
        } catch (RejectedExecutionException rejected) {
            finish(
                    applicationContext,
                    kind,
                    0,
                    0,
                    cutoff,
                    days,
                    targetLocalDate,
                    targetZoneId,
                    "Could not schedule journal maintenance.");
            return false;
        }
        return true;
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(
                    busy,
                    hasPendingCompletionLocked(),
                    description,
                    generation,
                    completedKind,
                    completedValue,
                    completedAuxiliaryValue,
                    completedCutoff,
                    completedDays,
                    completedTargetLocalDate,
                    completedTargetZoneId,
                    completedError);
        }
    }

    /**
     * Releases exactly the retained completion the Activity has finished consuming. A later
     * operation cannot overwrite a result between its worker completion and this acknowledgment.
     */
    public static boolean acknowledgeCompletion(long completedGeneration) {
        synchronized (LOCK) {
            if (!JournalMaintenancePolicy.canAcknowledge(
                    busy,
                    completedKind != null,
                    generation,
                    completedGeneration,
                    acknowledgedGeneration)) {
                return false;
            }
            acknowledgedGeneration = generation;
            return true;
        }
    }

    private static boolean hasPendingCompletionLocked() {
        return JournalMaintenancePolicy.hasPendingCompletion(
                completedKind != null, generation, acknowledgedGeneration);
    }

    private static void run(
            Context context,
            JournalStore store,
            Kind kind,
            String preservedSessionId,
            long cutoff,
            long days,
            String targetLocalDate,
            String targetZoneId,
            long observedAt,
            String observedZoneId,
            String text) {
        int value = 0;
        int auxiliary = 0;
        String error = "";
        try {
            if (kind == Kind.COUNT_OLD) {
                value = store.countSessionsOlderThan(cutoff, preservedSessionId);
            } else if (kind == Kind.DELETE_OLD) {
                value = store.deleteSessionsOlderThan(cutoff, preservedSessionId);
            } else if (kind == Kind.CLEAR_HISTORY) {
                value = store.clearArchivedSessions(preservedSessionId);
            } else if (kind == Kind.CLEAR_APP_MANAGED_LOGS) {
                if (MmlLogStorage.selectedMode(context) != LogStorageMode.WITH_APP) {
                    throw new IOException(
                            "App-managed logs cannot be cleared while a user-managed directory is active.");
                }
                value = store.clearAppManagedLogs(preservedSessionId);
            } else if (kind == Kind.COUNT_COMMENTS) {
                value = store.countCalendarComments();
            } else if (kind == Kind.CLEAR_COMMENTS) {
                // -1 distinguishes a failed pre-count from a mutation that may have committed
                // before a later cleanup/fsync error was reported.
                auxiliary = -1;
                auxiliary = store.countFutureCalendarComments(System.currentTimeMillis());
                store.clearCalendarComments();
            } else if (kind == Kind.SAVE_COMMENT) {
                auxiliary = -1;
                auxiliary = store.countFutureCalendarComments(System.currentTimeMillis());
                store.saveCalendarComment(
                        targetLocalDate,
                        targetZoneId,
                        observedAt,
                        observedZoneId,
                        text);
                value = CommentText.hasPayload(text) ? 1 : 0;
            }
        } catch (IOException | RuntimeException failure) {
            String detail = failure.getMessage();
            error = detail == null || detail.isBlank()
                    ? "Journal maintenance failed."
                    : bounded(detail, 600);
        }
        finish(
                context,
                kind,
                value,
                auxiliary,
                cutoff,
                days,
                targetLocalDate,
                targetZoneId,
                error);
    }

    private static void finish(
            Context context,
            Kind kind,
            int value,
            int auxiliary,
            long cutoff,
            long days,
            String targetLocalDate,
            String targetZoneId,
            String error) {
        synchronized (LOCK) {
            busy = false;
            description = "";
            completedKind = kind;
            completedValue = value;
            completedAuxiliaryValue = auxiliary;
            completedCutoff = cutoff;
            completedDays = days;
            completedTargetLocalDate = targetLocalDate == null ? "" : targetLocalDate;
            completedTargetZoneId = targetZoneId == null ? "" : targetZoneId;
            completedError = error == null ? "" : bounded(error, 600);
            generation++;
        }
        notifyChanged(context);
    }

    private static void notifyChanged(Context context) {
        try {
            context.sendBroadcast(new Intent(ACTION_OPERATION_CHANGED)
                    .setPackage(context.getPackageName()));
        } catch (RuntimeException ignored) {
            // A replacement Activity reconciles the process-global snapshot directly.
        }
    }

    private static String bounded(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    public static final class Snapshot {
        private final boolean busy;
        private final boolean completionPending;
        private final String description;
        private final long generation;
        private final Kind completedKind;
        private final int completedValue;
        private final int completedAuxiliaryValue;
        private final long completedCutoff;
        private final long completedDays;
        private final String completedTargetLocalDate;
        private final String completedTargetZoneId;
        private final String completedError;

        private Snapshot(
                boolean busy,
                boolean completionPending,
                String description,
                long generation,
                Kind completedKind,
                int completedValue,
                int completedAuxiliaryValue,
                long completedCutoff,
                long completedDays,
                String completedTargetLocalDate,
                String completedTargetZoneId,
                String completedError) {
            this.busy = busy;
            this.completionPending = completionPending;
            this.description = description;
            this.generation = generation;
            this.completedKind = completedKind;
            this.completedValue = completedValue;
            this.completedAuxiliaryValue = completedAuxiliaryValue;
            this.completedCutoff = completedCutoff;
            this.completedDays = completedDays;
            this.completedTargetLocalDate = completedTargetLocalDate;
            this.completedTargetZoneId = completedTargetZoneId;
            this.completedError = completedError;
        }

        public boolean isBusy() { return busy; }
        public boolean isCompletionPending() { return completionPending; }
        public String getDescription() { return description; }
        public long getGeneration() { return generation; }
        public Kind getCompletedKind() { return completedKind; }
        public int getCompletedValue() { return completedValue; }
        public int getCompletedAuxiliaryValue() { return completedAuxiliaryValue; }
        public long getCompletedCutoff() { return completedCutoff; }
        public long getCompletedDays() { return completedDays; }
        public String getCompletedTargetLocalDate() { return completedTargetLocalDate; }
        public String getCompletedTargetZoneId() { return completedTargetZoneId; }
        public String getCompletedError() { return completedError; }
    }
}
