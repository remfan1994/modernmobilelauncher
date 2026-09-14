package vip.thatiam.mml;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SyncFailedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates MML's app-managed default logs with an optional user-managed directory.
 *
 * <p>Exactly one management choice is active in Settings. With no selected directory, the log is
 * app-managed and remains under MML's private storage. Selecting a directory makes the readable
 * UTF-8 files user-managed: MML writes them through Android's Storage Access Framework, never
 * deletes them when the selection is cleared, and leaves their eventual removal to the user and
 * the user's file manager. MML retains private crash-recovery working state because Calendar,
 * Current Session and notification capture cannot operate transactionally on every document
 * provider. That implementation state is not a second user-selected log location.
 *
 * <p>The user-managed writer is process-global, serialized and coalesced so a notification burst
 * cannot create one directory traversal per captured row.
 */
public final class MmlLogStorage {
    public static final String PREFERENCES = "mml-settings";
    public static final String ACTION_LOG_STORAGE_OPERATION_CHANGED =
            "vip.thatiam.mml.action.LOG_STORAGE_OPERATION_CHANGED";
    public static final String TREE_URI_PREFERENCE_KEY = "log-storage-tree-uri";
    private static final String LAST_SUCCESS_PREFERENCE_KEY = "log-mirror-last-success";
    private static final String LAST_ERROR_PREFERENCE_KEY = "log-mirror-last-error";
    private static final String PENDING_ACQUIRE_URI_PREFERENCE_KEY =
            "log-storage-pending-acquire-uri";
    private static final String PENDING_RELEASE_URIS_PREFERENCE_KEY =
            "log-storage-pending-release-uris";
    private static final String MIRROR_DIRECTORY_NAME = "MML Logs";
    private static final String SESSION_DIRECTORY_NAME = "sessions";
    private static final String README_NAME = "README.txt";
    private static final String USER_MANAGED_README_MARKER =
            "MML USER-MANAGED LOG DIRECTORY";
    private static final String LEGACY_README_MARKER =
            "MML USER FILESPACE LOG MIRROR";
    private static final String INDEX_NAME = "MML Mirror Index.txt";
    private static final String PENDING_SUFFIX = ".MML-pending.txt";
    private static final String SESSION_MIRROR_TEXT_SUFFIX = ".txt";
    private static final String PARTIAL_RELEASE_PREFIX = "partial-grant:";
    private static final long MIRROR_DEBOUNCE_MILLIS = 5_000L;
    private static final long MAX_FAILURE_RETRY_MILLIS = 5L * 60_000L;
    private static final long GRANT_RETRY_BASE_MILLIS = 30_000L;
    private static final long MAX_INDEX_BYTES = 512L * 1024L;
    private static final int MAX_DIRECTORY_ENTRIES = 8_192;
    private static final int MAX_DIRECTORY_METADATA_CHARACTERS = 1_048_576;
    private static final int MAX_MIME_TYPE_CHARACTERS = 256;
    private static final int MAX_INDEX_ENTRIES = 8_192;
    private static final int MAX_PENDING_GRANT_URIS = 32;
    private static final int MAX_GRANT_URI_CHARACTERS = 8_192;
    private static final int REQUIRED_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int COPY_BUFFER_BYTES = 32 * 1024;
    private static final Object COORDINATOR_LOCK = new Object();
    private static final ScheduledExecutorService MIRROR_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread worker = new Thread(runnable, "MML-filespace-mirror");
                worker.setDaemon(true);
                return worker;
            });
    private static long requestedGeneration;
    private static boolean mirrorRunnerScheduled;
    private static int consecutiveMirrorFailures;
    private static long retryNotBeforeElapsedRealtime;
    private static boolean userOperationBusy;
    private static UserOperationKind userOperationKind = UserOperationKind.NONE;
    private static String userOperationDescription = "";
    private static String lastUserOperationResult = "";
    private static long userOperationGeneration;
    private static boolean grantReconciliationRunning;
    private static boolean grantReconciliationRetryScheduled;
    private static boolean unresolvedGrantRecovery;
    private static int consecutiveGrantReconciliationFailures;

    private MmlLogStorage() {
    }

    public static final class OperationSnapshot {
        private final boolean busy;
        private final String description;
        private final String lastResult;
        private final long generation;

        private OperationSnapshot(
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

    /** Opens the authoritative private store and observes every completed durable mutation. */
    public static JournalStore openJournalStore(Context context) throws IOException {
        Context applicationContext = applicationContext(context);
        try {
            reconcilePendingGrantTransitions(applicationContext);
        } catch (RuntimeException unexpected) {
            handleUnexpectedUserWorkerFailure(
                    applicationContext,
                    true,
                    "Directory-grant startup recovery",
                    unexpected);
        }
        return new JournalStore(
                applicationContext.getFilesDir(),
                () -> requestMirror(applicationContext));
    }

    public static LogStorageMode selectedMode(Context context) {
        try {
            return LogStorageMode.fromPreference(preferences(context).getString(
                    LogStorageMode.PREFERENCE_KEY,
                    LogStorageMode.DEFAULT.getPreferenceValue()));
        } catch (ClassCastException | IllegalArgumentException ignored) {
            return LogStorageMode.DEFAULT;
        }
    }

    public static Uri selectedTreeUri(Context context) {
        String encoded;
        try {
            encoded = preferences(context).getString(TREE_URI_PREFERENCE_KEY, "");
        } catch (ClassCastException | IllegalArgumentException ignored) {
            return null;
        }
        if (encoded == null || encoded.isBlank()
                || encoded.length() > MAX_GRANT_URI_CHARACTERS) {
            return null;
        }
        try {
            return Uri.parse(encoded);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** True only when a user-managed directory is the active log-management choice. */
    public static boolean isUserManagedLoggingConfigured(Context context) {
        return selectedMode(context) == LogStorageMode.USER_FILESPACE
                && selectedTreeUri(context) != null;
    }

    /** True whenever no valid user-managed directory is the active management choice. */
    public static boolean isAppManagedLoggingActive(Context context) {
        return !isUserManagedLoggingConfigured(context);
    }

    /** Source-compatibility alias retained for callers built against MML 1.6.5. */
    public static boolean isUserAccessibleLoggingConfigured(Context context) {
        return isUserManagedLoggingConfigured(context);
    }

    /** Returns a filesystem-style description of the actual generated log directory. */
    public static String selectedLogDirectoryDisplayName(Context context) {
        Uri treeUri = selectedTreeUri(context);
        if (treeUri == null) {
            return "not set";
        }
        String selectedRoot = treeDisplayName(treeUri);
        if (selectedRoot.endsWith("/")) {
            return selectedRoot + MIRROR_DIRECTORY_NAME;
        }
        return selectedRoot + "/" + MIRROR_DIRECTORY_NAME;
    }

    private static String treeDisplayName(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId != null && !documentId.isBlank()) {
                int separator = documentId.indexOf(':');
                if (separator >= 0) {
                    String volume = documentId.substring(0, separator);
                    String relativePath = documentId.substring(separator + 1);
                    String root = "primary".equalsIgnoreCase(volume)
                            ? "/storage/emulated/0"
                            : "/storage/" + volume;
                    return relativePath.isBlank() ? root : root + "/" + relativePath;
                }
                return documentId;
            }
        } catch (RuntimeException ignored) {
            // A non-filesystem document provider may not expose a conventional tree ID.
        }
        return "selected directory";
    }

    public static long lastSuccessfulMirrorTime(Context context) {
        try {
            return preferences(context).getLong(LAST_SUCCESS_PREFERENCE_KEY, 0L);
        } catch (ClassCastException | IllegalArgumentException ignored) {
            return 0L;
        }
    }

    public static String lastMirrorError(Context context) {
        try {
            String value = preferences(context).getString(LAST_ERROR_PREFERENCE_KEY, "");
            return value == null ? "" : bounded(value, 600);
        } catch (ClassCastException | IllegalArgumentException ignored) {
            return "";
        }
    }

    public static OperationSnapshot operationSnapshot() {
        synchronized (COORDINATOR_LOCK) {
            return new OperationSnapshot(
                    userOperationBusy || unresolvedGrantRecovery,
                    unresolvedGrantRecovery && userOperationDescription.isEmpty()
                            ? "Recovering an interrupted directory grant"
                            : userOperationDescription,
                    lastUserOperationResult,
                    userOperationGeneration);
        }
    }

    /** Claims the one process-global picker/transition slot before Android UI is launched. */
    public static boolean beginDirectorySelection(Context context) {
        return beginUserOperation(
                context,
                UserOperationKind.DIRECTORY_SELECTION,
                "Choosing a log directory");
    }

    public static void cancelDirectorySelection(Context context, String reason) {
        synchronized (COORDINATOR_LOCK) {
            if (!userOperationBusy
                    || userOperationKind != UserOperationKind.DIRECTORY_SELECTION) {
                return;
            }
        }
        finishUserOperation(
                applicationContext(context),
                reason == null || reason.isBlank()
                        ? "Directory selection canceled; log storage is unchanged."
                        : reason);
    }

    /**
     * Coalesces a best-effort mirror after a private mutation. A later request always causes one
     * more complete reconciliation before the worker becomes idle.
     */
    public static void requestMirror(Context context) {
        Context applicationContext = applicationContext(context);
        try {
            if (selectedMode(applicationContext) != LogStorageMode.USER_FILESPACE
                    || selectedTreeUri(applicationContext) == null) {
                return;
            }
        } catch (RuntimeException unexpected) {
            IOException failure = asIOException(
                    "Could not inspect the configured MML log mirror.", unexpected);
            synchronized (COORDINATOR_LOCK) {
                recordCoordinatorResult(failure);
            }
            recordMirrorFailureBestEffort(applicationContext, failure);
            return;
        }
        long delayMillis;
        synchronized (COORDINATOR_LOCK) {
            requestedGeneration++;
            if (mirrorRunnerScheduled) {
                return;
            }
            delayMillis = nextMirrorDelayMillis();
            // Claim only after every fallible delay calculation has completed.
            mirrorRunnerScheduled = true;
        }
        scheduleConfiguredMirrorRunner(applicationContext, delayMillis);
    }

    /** Runs an explicit reconciliation without retaining an Activity callback on the worker. */
    public static boolean synchronizeNow(Context context) {
        Context applicationContext = applicationContext(context);
        if (!beginUserOperation(
                applicationContext,
                UserOperationKind.SYNCHRONIZE,
                "Saving user-managed logs")) {
            return false;
        }
        return submitUserWorker(applicationContext, false, "Log synchronization", () -> {
            IOException failure = synchronizeConfigured(applicationContext);
            synchronized (COORDINATOR_LOCK) {
                recordCoordinatorResult(failure);
            }
            finishUserOperation(
                    applicationContext,
                    failure == null
                            ? "User-managed logs saved."
                            : "User-managed log save failed; MML recovery data is safe: "
                                    + safeFailureMessage(failure));
        });
    }

    /**
     * Builds a complete initial mirror before committing the new preference. A failed provider
     * write therefore leaves the prior placement policy active and the private journal untouched.
     */
    public static IOException activateUserFilespace(
            Context context,
            Uri treeUri,
            int grantFlags) {
        Context applicationContext = applicationContext(context);
        if (!claimDirectoryActivation(applicationContext)) {
            return new IOException("Another log-storage operation is already running.");
        }
        if (treeUri == null) {
            IOException failure = new IOException("No log directory was selected.");
            finishUserOperation(applicationContext, failure.getMessage());
            return failure;
        }
        String encodedTreeUri = treeUri.toString();
        if (encodedTreeUri.length() > MAX_GRANT_URI_CHARACTERS) {
            IOException failure = new IOException(
                    "The selected directory identifier is too large to store safely.");
            finishUserOperation(applicationContext, failure.getMessage());
            return failure;
        }
        if ((grantFlags & REQUIRED_GRANT_FLAGS) != REQUIRED_GRANT_FLAGS) {
            IOException failure = new IOException(
                    "The selected provider did not grant durable read/write directory access.");
            finishUserOperation(applicationContext, failure.getMessage());
            return failure;
        }
        boolean pendingSaved;
        try {
            pendingSaved = preferences(applicationContext).edit()
                    .putString(PENDING_ACQUIRE_URI_PREFERENCE_KEY, encodedTreeUri)
                    .commit();
        } catch (RuntimeException error) {
            IOException failure = new IOException(
                    "Could not journal the pending directory grant before requesting it.",
                    error);
            finishUserOperation(applicationContext, failure.getMessage());
            return failure;
        }
        if (!pendingSaved) {
            IOException failure = new IOException(
                    "Could not journal the pending directory grant before requesting it.");
            finishUserOperation(applicationContext, failure.getMessage());
            return failure;
        }
        try {
            applicationContext.getContentResolver().takePersistableUriPermission(
                    treeUri, grantFlags & REQUIRED_GRANT_FLAGS);
        } catch (RuntimeException error) {
            // A provider may throw after persisting all or part of the grant. Do not guess and
            // retire the acquire journal here: the worker classifies Android's durable state and
            // either continues activation, releases exactly confirmed partial bits, or keeps the
            // marker for a visible retry when enumeration is temporarily unavailable.
            updateUserOperation(
                    applicationContext,
                    "Checking Android's persisted directory grant");
            boolean queued = submitUserWorker(
                    applicationContext,
                    true,
                    "Directory-grant classification",
                    () -> {
                        int heldFlags = persistedPermissionFlags(applicationContext, treeUri);
                        PersistedGrantState grantState = PersistedGrantState.classify(
                                heldFlags, REQUIRED_GRANT_FLAGS);
                        if (grantState == PersistedGrantState.FULL) {
                            completeUserFilespaceActivation(applicationContext, treeUri);
                        } else {
                            finishIncompletePendingAcquire(
                                    applicationContext, treeUri, heldFlags, grantState);
                        }
                    });
            return queued ? null : new IOException(
                    "Could not schedule persisted directory-grant classification.");
        }
        updateUserOperation(applicationContext, "Preparing the selected log directory");
        boolean queued = submitUserWorker(
                applicationContext,
                true,
                "Log-directory activation",
                () -> completeUserFilespaceActivation(applicationContext, treeUri));
        return queued ? null : new IOException(
                "Could not schedule log-directory activation.");
    }

    /**
     * Stops external writes, removes the persisted SAF grant and leaves existing user files alone.
     */
    public static boolean selectAppManagedLogging(Context context) {
        Context applicationContext = applicationContext(context);
        if (!beginUserOperation(
                applicationContext,
                UserOperationKind.SELECT_APP_MANAGED,
                "Returning logs to app-managed storage")) {
            return false;
        }
        return submitUserWorker(applicationContext, true, "Select app-managed logging", () -> {
            IOException failure = selectAppManagedLoggingOnMirrorWorker(applicationContext);
            boolean committed = selectedMode(applicationContext) == LogStorageMode.WITH_APP;
            finishUserOperation(
                    applicationContext,
                    failure == null
                            ? "Logs are app-managed. Existing user-managed files were kept."
                            : committed
                                    ? "Logs are app-managed. Android will retry releasing "
                                            + "an obsolete directory grant: "
                                            + safeFailureMessage(failure)
                                    : "Could not return logs to app-managed storage: "
                                            + safeFailureMessage(failure));
        });
    }

    /** Source-compatibility alias retained for MML 1.6.5 call sites. */
    public static boolean disableUserAccessibleLogging(Context context) {
        return selectAppManagedLogging(context);
    }

    private static IOException selectAppManagedLoggingOnMirrorWorker(Context applicationContext) {
        if (pendingAcquireUri(applicationContext) != null) {
            return new IOException(
                    "An interrupted directory grant must be classified before logs can return "
                            + "to app-managed storage.");
        }
        Uri oldTreeUri = selectedTreeUri(applicationContext);
        Set<String> pendingReleases = pendingReleaseUris(applicationContext);
        IOException journalFailure = addPendingReleaseUri(pendingReleases, oldTreeUri);
        if (journalFailure != null) {
            return journalFailure;
        }
        boolean saved = preferences(applicationContext).edit()
                .putString(
                        LogStorageMode.PREFERENCE_KEY,
                        LogStorageMode.WITH_APP.getPreferenceValue())
                .remove(TREE_URI_PREFERENCE_KEY)
                .remove(LAST_SUCCESS_PREFERENCE_KEY)
                .remove(LAST_ERROR_PREFERENCE_KEY)
                .putString(
                        PENDING_RELEASE_URIS_PREFERENCE_KEY,
                        encodePendingReleaseUris(pendingReleases))
                .commit();
        if (!saved) {
            return new IOException("Could not save the app-managed logging preference.");
        }
        synchronized (COORDINATOR_LOCK) {
            requestedGeneration++;
            recordCoordinatorResult(null);
        }
        return processPendingReleases(applicationContext);
    }

    private static void completeUserFilespaceActivation(Context context, Uri treeUri) {
        int heldFlags = persistedPermissionFlags(context, treeUri);
        PersistedGrantState grantState = PersistedGrantState.classify(
                heldFlags, REQUIRED_GRANT_FLAGS);
        if (grantState != PersistedGrantState.FULL) {
            finishIncompletePendingAcquire(context, treeUri, heldFlags, grantState);
            return;
        }
        IOException failure = null;
        IOException releaseFailure = null;
        boolean committed = false;
        Uri oldTreeUri = selectedTreeUri(context);
        try {
            synchronizeToTree(context, treeUri);
            Set<String> pendingReleases = pendingReleaseUris(context);
            // A newly confirmed full grant supersedes cleanup of an older partial/obsolete grant
            // for the exact same URI. Retiring those records in the activation commit prevents a
            // later cleanup pass from releasing the just-selected active grant.
            removePendingReleasesForUri(pendingReleases, treeUri);
            if (oldTreeUri != null && !oldTreeUri.equals(treeUri)) {
                IOException journalFailure = addPendingReleaseUri(
                        pendingReleases, oldTreeUri);
                if (journalFailure != null) {
                    throw journalFailure;
                }
            }
            boolean saved = preferences(context).edit()
                    .putString(
                            LogStorageMode.PREFERENCE_KEY,
                            LogStorageMode.USER_FILESPACE.getPreferenceValue())
                    .putString(TREE_URI_PREFERENCE_KEY, treeUri.toString())
                    .putLong(LAST_SUCCESS_PREFERENCE_KEY, System.currentTimeMillis())
                    .remove(LAST_ERROR_PREFERENCE_KEY)
                    .remove(PENDING_ACQUIRE_URI_PREFERENCE_KEY)
                    .putString(
                            PENDING_RELEASE_URIS_PREFERENCE_KEY,
                            encodePendingReleaseUris(pendingReleases))
                    .commit();
            if (!saved) {
                throw new IOException("Could not atomically save the selected log directory.");
            }
            committed = true;
            releaseFailure = processPendingReleases(context);
            synchronized (COORDINATOR_LOCK) {
                recordCoordinatorResult(null);
            }
            requestMirror(context);
        } catch (IOException | RuntimeException error) {
            failure = asIOException("Could not initialize the selected log directory.", error);
            recordMirrorFailure(context, failure);
            if (!committed) {
                journalFailedAcquireForRelease(context, treeUri, oldTreeUri);
                processPendingReleases(context);
            }
        } finally {
            setUnresolvedGrantRecovery(pendingAcquireUri(context) != null);
            markGrantReconciliationFinished(failure != null || releaseFailure != null);
        }
        finishUserOperation(
                context,
                failure == null
                        ? releaseFailure == null
                                ? "The selected directory is now the user-managed log directory."
                                : "The selected directory is now the user-managed log directory. Android will "
                                        + "retry releasing an obsolete directory grant: "
                                        + safeFailureMessage(releaseFailure)
                        : committed
                                ? "The selected directory is now user-managed; recovery will "
                                        + "retry a cleanup step: " + safeFailureMessage(failure)
                                : "The log directory was not changed: "
                                        + safeFailureMessage(failure));
        if (pendingAcquireUri(context) != null || !pendingReleaseUris(context).isEmpty()) {
            scheduleGrantReconciliationRetry(context);
        }
    }

    private static void finishIncompletePendingAcquire(
            Context context,
            Uri treeUri,
            int heldFlags,
            PersistedGrantState grantState) {
        boolean retry = false;
        String result;
        if (grantState == PersistedGrantState.UNKNOWN) {
            retry = true;
            result = "Android's persisted directory-grant state is temporarily unavailable. "
                    + "The pending selection was kept and recovery will retry automatically.";
        } else if (grantState == PersistedGrantState.NONE) {
            if (clearPendingAcquire(context, treeUri)) {
                result = "Android confirmed that the interrupted directory selection holds no "
                        + "persisted grant. The prior logging setting remains active.";
            } else {
                retry = true;
                result = "MML could not durably clear a confirmed-empty pending directory "
                        + "selection. Its recovery marker was kept and recovery will retry.";
            }
        } else {
            IOException failure = journalAndReleasePartialAcquire(context, treeUri, heldFlags);
            retry = failure != null;
            result = failure == null
                    ? "Android supplied only a partial directory grant. MML released exactly the "
                            + "held permission and kept the prior logging setting."
                    : "Android supplied only a partial directory grant. Its recovery marker was "
                            + "kept and cleanup will retry: " + safeFailureMessage(failure);
        }
        setUnresolvedGrantRecovery(retry && pendingAcquireUri(context) != null);
        markGrantReconciliationFinished(retry);
        finishUserOperation(context, result);
        if (retry) {
            scheduleGrantReconciliationRetry(context);
        }
    }

    private static IOException journalAndReleasePartialAcquire(
            Context context,
            Uri treeUri,
            int heldFlags) {
        Set<String> pendingReleases = pendingReleaseUris(context);
        IOException journalFailure = addPendingPartialRelease(
                pendingReleases, treeUri, heldFlags & REQUIRED_GRANT_FLAGS);
        if (journalFailure != null) {
            return journalFailure;
        }
        // First make release intent durable while the original acquire marker still survives.
        boolean releaseJournalSaved = preferences(context).edit()
                .putString(
                        PENDING_RELEASE_URIS_PREFERENCE_KEY,
                        encodePendingReleaseUris(pendingReleases))
                .commit();
        if (!releaseJournalSaved) {
            return new IOException(
                    "Could not journal cleanup of the partial directory grant.");
        }
        // Only a second successful commit may retire the acquire marker.
        if (!clearPendingAcquire(context, treeUri)) {
            return new IOException(
                    "Could not retire the partial grant's pending-acquire marker.");
        }
        releasePersistedPermission(context, treeUri, heldFlags & REQUIRED_GRANT_FLAGS);
        return processPendingReleases(context);
    }

    private static void journalFailedAcquireForRelease(
            Context context,
            Uri acquiredTreeUri,
            Uri activeTreeUri) {
        Set<String> pendingReleases = pendingReleaseUris(context);
        if (acquiredTreeUri != null && !acquiredTreeUri.equals(activeTreeUri)) {
            IOException journalFailure = addPendingReleaseUri(
                    pendingReleases, acquiredTreeUri);
            if (journalFailure != null) {
                // Keep the already-durable pending-acquire record. Startup recovery can retry the
                // activation or later convert that same grant into a release journal entry.
                return;
            }
        }
        preferences(context).edit()
                .remove(PENDING_ACQUIRE_URI_PREFERENCE_KEY)
                .putString(
                        PENDING_RELEASE_URIS_PREFERENCE_KEY,
                        encodePendingReleaseUris(pendingReleases))
                .commit();
    }

    private static boolean beginUserOperation(
            Context context,
            UserOperationKind kind,
            String description) {
        Context applicationContext = applicationContext(context);
        if (pendingAcquireUri(applicationContext) != null) {
            setUnresolvedGrantRecovery(true);
            scheduleGrantReconciliationRetry(applicationContext);
            return false;
        }
        synchronized (COORDINATOR_LOCK) {
            if (userOperationBusy || unresolvedGrantRecovery) {
                return false;
            }
            userOperationBusy = true;
            userOperationKind = kind;
            userOperationDescription = description == null ? "Log storage operation" : description;
            lastUserOperationResult = "";
            userOperationGeneration++;
        }
        notifyOperationChanged(applicationContext);
        return true;
    }

    private static boolean claimDirectoryActivation(Context context) {
        if (pendingAcquireUri(context) != null) {
            setUnresolvedGrantRecovery(true);
            scheduleGrantReconciliationRetry(context);
            return false;
        }
        synchronized (COORDINATOR_LOCK) {
            if (unresolvedGrantRecovery
                    || userOperationBusy
                    && userOperationKind != UserOperationKind.DIRECTORY_SELECTION) {
                return false;
            }
            if (!userOperationBusy) {
                userOperationBusy = true;
            }
            userOperationKind = UserOperationKind.ACTIVATE_USER_FILESPACE;
            userOperationDescription = "Preparing the selected log directory";
            lastUserOperationResult = "";
            userOperationGeneration++;
        }
        notifyOperationChanged(applicationContext(context));
        return true;
    }

    private static void updateUserOperation(Context context, String description) {
        synchronized (COORDINATOR_LOCK) {
            if (!userOperationBusy) {
                userOperationBusy = true;
            }
            userOperationDescription = description == null ? "Log storage operation" : description;
            userOperationGeneration++;
        }
        notifyOperationChanged(applicationContext(context));
    }

    private static void finishUserOperation(Context context, String result) {
        synchronized (COORDINATOR_LOCK) {
            userOperationBusy = false;
            userOperationKind = UserOperationKind.NONE;
            userOperationDescription = "";
            lastUserOperationResult = result == null ? "" : bounded(result, 600);
            userOperationGeneration++;
        }
        notifyOperationChanged(applicationContext(context));
    }

    private static void notifyOperationChanged(Context context) {
        try {
            context.sendBroadcast(new Intent(ACTION_LOG_STORAGE_OPERATION_CHANGED)
                    .setPackage(context.getPackageName()));
        } catch (RuntimeException ignored) {
            // Static operation state lets a replacement Activity discover the completion.
        }
    }

    private static void setUnresolvedGrantRecovery(boolean unresolved) {
        synchronized (COORDINATOR_LOCK) {
            unresolvedGrantRecovery = unresolved;
            userOperationGeneration++;
        }
    }

    private static void markGrantReconciliationFinished(boolean retryNeeded) {
        synchronized (COORDINATOR_LOCK) {
            grantReconciliationRunning = false;
            if (retryNeeded) {
                consecutiveGrantReconciliationFailures = Math.min(
                        consecutiveGrantReconciliationFailures + 1, 8);
            } else {
                consecutiveGrantReconciliationFailures = 0;
            }
        }
    }

    private static void scheduleGrantReconciliationRetry(Context context) {
        Context applicationContext = applicationContext(context);
        long delayMillis;
        synchronized (COORDINATOR_LOCK) {
            if (grantReconciliationRetryScheduled) {
                return;
            }
            grantReconciliationRetryScheduled = true;
            int shift = Math.min(consecutiveGrantReconciliationFailures, 4);
            delayMillis = Math.min(
                    MAX_FAILURE_RETRY_MILLIS,
                    GRANT_RETRY_BASE_MILLIS * (1L << shift));
        }
        try {
            MIRROR_EXECUTOR.schedule(() -> {
                synchronized (COORDINATOR_LOCK) {
                    grantReconciliationRetryScheduled = false;
                }
                try {
                    reconcilePendingGrantTransitions(applicationContext);
                } catch (RuntimeException unexpected) {
                    handleUnexpectedUserWorkerFailure(
                            applicationContext,
                            true,
                            "Directory-grant retry",
                            unexpected);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            synchronized (COORDINATOR_LOCK) {
                grantReconciliationRetryScheduled = false;
            }
            throw rejected;
        }
    }

    private static boolean submitUserWorker(
            Context context,
            boolean grantSensitive,
            String operation,
            Runnable action) {
        Context applicationContext = applicationContext(context);
        Runnable guarded = () -> {
            try {
                action.run();
            } catch (RuntimeException unexpected) {
                handleUnexpectedUserWorkerFailure(
                        applicationContext, grantSensitive, operation, unexpected);
            }
        };
        try {
            MIRROR_EXECUTOR.execute(guarded);
            return true;
        } catch (RuntimeException rejected) {
            handleUnexpectedUserWorkerFailure(
                    applicationContext, grantSensitive, operation, rejected);
            return false;
        }
    }

    private static void handleUnexpectedUserWorkerFailure(
            Context context,
            boolean grantSensitive,
            String operation,
            RuntimeException failure) {
        boolean pendingAcquireMaySurvive = grantSensitive;
        try {
            pendingAcquireMaySurvive = pendingAcquireUri(context) != null;
        } catch (RuntimeException ignored) {
            // Conservatively block new grant transitions until a retry can enumerate state.
        }
        setUnresolvedGrantRecovery(pendingAcquireMaySurvive);
        markGrantReconciliationFinished(true);
        IOException wrapped = asIOException(
                operation + " stopped after an unexpected Android storage failure.", failure);
        try {
            recordMirrorFailure(context, wrapped);
        } catch (RuntimeException ignored) {
            // The process-global result remains visible even if preferences are unavailable.
        }
        finishUserOperation(
                context,
                operation + " stopped safely; the private journal is intact and recovery will "
                        + "retry: " + safeFailureMessage(wrapped));
        if (grantSensitive) {
            try {
                scheduleGrantReconciliationRetry(context);
            } catch (RuntimeException ignored) {
                // A later startup invokes reconciliation from openJournalStore as a second gate.
            }
        }
    }

    private static void reconcilePendingGrantTransitions(Context context) {
        Context applicationContext = applicationContext(context);
        Uri pendingAcquire = pendingAcquireUri(applicationContext);
        Set<String> pendingReleases = pendingReleaseUris(applicationContext);
        if (pendingAcquire == null && pendingReleases.isEmpty()) {
            setUnresolvedGrantRecovery(false);
            synchronized (COORDINATOR_LOCK) {
                consecutiveGrantReconciliationFailures = 0;
            }
            return;
        }
        if (pendingAcquire != null) {
            setUnresolvedGrantRecovery(true);
        }
        boolean retryBecauseBusy = false;
        synchronized (COORDINATOR_LOCK) {
            if (grantReconciliationRunning) {
                return;
            }
            if (userOperationBusy) {
                retryBecauseBusy = true;
            } else {
                grantReconciliationRunning = true;
            }
            if (retryBecauseBusy) {
                // Schedule outside the coordinator lock.
            } else {
                userOperationBusy = true;
                userOperationKind = UserOperationKind.RECONCILE_GRANTS;
                userOperationDescription = "Recovering an interrupted log-storage transition";
                lastUserOperationResult = "";
                userOperationGeneration++;
            }
        }
        if (retryBecauseBusy) {
            scheduleGrantReconciliationRetry(applicationContext);
            return;
        }
        notifyOperationChanged(applicationContext);
        submitUserWorker(
                applicationContext,
                true,
                "Directory-grant recovery",
                () -> {
                    Uri acquire = pendingAcquireUri(applicationContext);
                    Set<String> releases = pendingReleaseUris(applicationContext);
                    if (acquire != null && containsPartialReleaseForUri(releases, acquire)) {
                        if (!clearPendingAcquire(applicationContext, acquire)) {
                            setUnresolvedGrantRecovery(true);
                            markGrantReconciliationFinished(true);
                            finishUserOperation(
                                    applicationContext,
                                    "A partial-grant cleanup marker remains durable, but its "
                                            + "acquire marker could not be retired. Recovery will "
                                            + "retry.");
                            scheduleGrantReconciliationRetry(applicationContext);
                            return;
                        }
                        IOException transitionFailure =
                                processPendingReleases(applicationContext);
                        boolean retry = transitionFailure != null;
                        setUnresolvedGrantRecovery(
                                pendingAcquireUri(applicationContext) != null);
                        markGrantReconciliationFinished(retry);
                        finishUserOperation(
                                applicationContext,
                                retry
                                        ? "Android will retry interrupted partial-grant cleanup: "
                                                + safeFailureMessage(transitionFailure)
                                        : "Interrupted partial directory-grant cleanup completed.");
                        if (retry) {
                            scheduleGrantReconciliationRetry(applicationContext);
                        }
                        return;
                    }
                    IOException releaseFailure = processPendingReleases(applicationContext);
                    acquire = pendingAcquireUri(applicationContext);
                    if (acquire != null) {
                        completeUserFilespaceActivation(applicationContext, acquire);
                        return;
                    }
                    boolean retry = releaseFailure != null;
                    markGrantReconciliationFinished(retry);
                    finishUserOperation(
                            applicationContext,
                            releaseFailure == null
                                    ? "Interrupted directory-grant release recovery completed."
                                    : "Android will retry an interrupted directory-grant release: "
                                            + safeFailureMessage(releaseFailure));
                    if (retry) {
                        scheduleGrantReconciliationRetry(applicationContext);
                    }
                });
    }

    private static Uri pendingAcquireUri(Context context) {
        String encoded;
        try {
            encoded = preferences(context).getString(PENDING_ACQUIRE_URI_PREFERENCE_KEY, "");
        } catch (ClassCastException | IllegalArgumentException ignored) {
            return null;
        }
        if (encoded == null || encoded.isBlank()
                || encoded.length() > MAX_GRANT_URI_CHARACTERS) {
            return null;
        }
        try {
            return Uri.parse(encoded);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean clearPendingAcquire(Context context, Uri expected) {
        Uri current = pendingAcquireUri(context);
        if (expected == null || expected.equals(current)) {
            return preferences(context).edit()
                    .remove(PENDING_ACQUIRE_URI_PREFERENCE_KEY)
                    .commit();
        }
        return false;
    }

    private static Set<String> pendingReleaseUris(Context context) {
        String encoded;
        try {
            encoded = preferences(context).getString(PENDING_RELEASE_URIS_PREFERENCE_KEY, "");
        } catch (ClassCastException | IllegalArgumentException ignored) {
            return new LinkedHashSet<>();
        }
        Set<String> result = new LinkedHashSet<>();
        if (encoded == null || encoded.isEmpty()
                || encoded.length()
                        > (MAX_PENDING_GRANT_URIS * (MAX_GRANT_URI_CHARACTERS + 48))) {
            return result;
        }
        for (String line : encoded.split("\\n", MAX_PENDING_GRANT_URIS + 1)) {
            List<String> row = LineCodec.decode(line);
            if (row.size() == 2 && "R".equals(row.get(0))
                    && !row.get(1).isBlank()
                    && row.get(1).length() <= MAX_GRANT_URI_CHARACTERS + 32
                    && result.size() < MAX_PENDING_GRANT_URIS) {
                result.add(row.get(1));
            }
        }
        return result;
    }

    private static String encodePendingReleaseUris(Set<String> values) {
        StringBuilder encoded = new StringBuilder();
        int count = 0;
        for (String value : values) {
            if (value == null || value.isBlank()
                    || value.length() > MAX_GRANT_URI_CHARACTERS + 32
                    || count >= MAX_PENDING_GRANT_URIS) {
                continue;
            }
            encoded.append(LineCodec.encode("R", value)).append('\n');
            count++;
        }
        return encoded.toString();
    }

    private static IOException addPendingReleaseUri(Set<String> values, Uri uri) {
        if (uri == null) {
            return null;
        }
        String encoded = uri.toString();
        if (encoded.isBlank() || encoded.length() > MAX_GRANT_URI_CHARACTERS) {
            return new IOException(
                    "An obsolete directory grant has an invalid recovery identifier.");
        }
        if (!values.contains(encoded) && values.size() >= MAX_PENDING_GRANT_URIS) {
            return new IOException(
                    "Too many obsolete directory grants are awaiting Android cleanup.");
        }
        values.add(encoded);
        return null;
    }

    private static IOException addPendingPartialRelease(
            Set<String> values,
            Uri uri,
            int heldFlags) {
        if (uri == null || heldFlags <= 0) {
            return new IOException("The partial directory grant has no releasable permission.");
        }
        String encodedUri = uri.toString();
        if (encodedUri.isBlank() || encodedUri.length() > MAX_GRANT_URI_CHARACTERS) {
            return new IOException(
                    "The partial directory grant has an invalid recovery identifier.");
        }
        String value = PARTIAL_RELEASE_PREFIX + heldFlags + ":" + encodedUri;
        if (!values.contains(value) && values.size() >= MAX_PENDING_GRANT_URIS) {
            return new IOException(
                    "Too many obsolete directory grants are awaiting Android cleanup.");
        }
        values.add(value);
        return null;
    }

    private static Uri pendingReleaseUri(String value) {
        String encoded = value;
        if (isPartialRelease(value)) {
            int separator = value.indexOf(':', PARTIAL_RELEASE_PREFIX.length());
            if (separator < 0 || separator + 1 >= value.length()) {
                return null;
            }
            encoded = value.substring(separator + 1);
        }
        try {
            return Uri.parse(encoded);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isPartialRelease(String value) {
        return value != null && value.startsWith(PARTIAL_RELEASE_PREFIX);
    }

    private static int partialReleaseFlags(String value) {
        if (!isPartialRelease(value)) {
            return 0;
        }
        int separator = value.indexOf(':', PARTIAL_RELEASE_PREFIX.length());
        if (separator < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(PARTIAL_RELEASE_PREFIX.length(), separator))
                    & REQUIRED_GRANT_FLAGS;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean containsPartialReleaseForUri(Set<String> values, Uri uri) {
        if (uri == null) {
            return false;
        }
        for (String value : values) {
            Uri candidate = pendingReleaseUri(value);
            if (isPartialRelease(value) && uri.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static void removePendingReleasesForUri(Set<String> values, Uri uri) {
        if (uri == null) {
            return;
        }
        for (String value : new ArrayList<>(values)) {
            if (uri.equals(pendingReleaseUri(value))) {
                values.remove(value);
            }
        }
    }

    private static IOException processPendingReleases(Context context) {
        Set<String> remaining = pendingReleaseUris(context);
        Uri active = selectedTreeUri(context);
        Uri pendingAcquire = pendingAcquireUri(context);
        IOException firstFailure = null;
        for (String encoded : new ArrayList<>(remaining)) {
            Uri candidate = pendingReleaseUri(encoded);
            if (candidate == null) {
                remaining.remove(encoded);
                if (!persistPendingReleaseUris(context, remaining) && firstFailure == null) {
                    firstFailure = new IOException(
                            "Could not update the obsolete directory-grant recovery journal.");
                }
                continue;
            }
            boolean partialRelease = isPartialRelease(encoded);
            if (candidate.equals(pendingAcquire)) {
                // The acquire marker must remain the recovery source until a separately durable
                // partial-release intent lets reconciliation retire it in a second commit.
                continue;
            }
            if (candidate.equals(active) && !partialRelease) {
                remaining.remove(encoded);
                if (!persistPendingReleaseUris(context, remaining) && firstFailure == null) {
                    firstFailure = new IOException(
                            "Could not update the obsolete directory-grant recovery journal.");
                }
                continue;
            }
            int exactFlags = partialReleaseFlags(encoded);
            if (partialRelease && exactFlags == 0) {
                if (firstFailure == null) {
                    firstFailure = new IOException(
                            "A partial directory-grant cleanup record is invalid; no grant was "
                                    + "released.");
                }
                continue;
            }
            boolean released = exactFlags == 0
                    ? releasePersistedPermission(context, candidate)
                    : releasePersistedPermission(context, candidate, exactFlags);
            if (released) {
                remaining.remove(encoded);
            } else if (firstFailure == null) {
                firstFailure = new IOException(
                        "Android did not release an obsolete MML directory grant.");
            }
            boolean journalSaved = persistPendingReleaseUris(context, remaining);
            if (!journalSaved && firstFailure == null) {
                firstFailure = new IOException(
                        "Could not update the obsolete directory-grant recovery journal.");
            }
        }
        return firstFailure;
    }

    private static boolean persistPendingReleaseUris(Context context, Set<String> remaining) {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (remaining.isEmpty()) {
            editor.remove(PENDING_RELEASE_URIS_PREFERENCE_KEY);
        } else {
            editor.putString(
                    PENDING_RELEASE_URIS_PREFERENCE_KEY,
                    encodePendingReleaseUris(remaining));
        }
        return editor.commit();
    }

    private static String safeFailureMessage(IOException failure) {
        return failure == null || failure.getMessage() == null
                ? "unknown storage failure" : bounded(failure.getMessage(), 500);
    }

    private static void scheduleConfiguredMirrorRunner(Context context, long delayMillis) {
        try {
            MIRROR_EXECUTOR.schedule(
                    () -> runConfiguredMirrorLoopGuarded(context),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            IOException failure = asIOException(
                    "Could not schedule the MML log mirror worker.", rejected);
            synchronized (COORDINATOR_LOCK) {
                // A later journal mutation must be able to claim a replacement runner.
                mirrorRunnerScheduled = false;
                recordCoordinatorResult(failure);
            }
            recordMirrorFailureBestEffort(context, failure);
        }
    }

    private static void runConfiguredMirrorLoopGuarded(Context context) {
        try {
            runConfiguredMirrorLoop(context);
        } catch (RuntimeException unexpected) {
            IOException failure = asIOException(
                    "The MML log mirror stopped after an unexpected Android storage failure.",
                    unexpected);
            boolean retryConfigured = true;
            try {
                retryConfigured = selectedMode(context) == LogStorageMode.USER_FILESPACE
                        && selectedTreeUri(context) != null;
            } catch (RuntimeException ignored) {
                // The configuration itself could not be read. Retain a spaced retry rather than
                // abandoning a potentially active user-filespace mirror.
            }
            long delayMillis = MIRROR_DEBOUNCE_MILLIS;
            synchronized (COORDINATOR_LOCK) {
                // Reset before any preference/provider reporting so an unexpected secondary
                // failure can never strand the process-global runner claim.
                mirrorRunnerScheduled = false;
                try {
                    recordCoordinatorResult(failure);
                    delayMillis = nextMirrorDelayMillis();
                } catch (RuntimeException ignored) {
                    // The minimum debounce remains a safe retry delay.
                }
                if (retryConfigured) {
                    mirrorRunnerScheduled = true;
                }
            }
            recordMirrorFailureBestEffort(context, failure);
            if (retryConfigured) {
                scheduleConfiguredMirrorRunner(context, delayMillis);
            }
        }
    }

    private static void runConfiguredMirrorLoop(Context context) {
        long handledGeneration;
        synchronized (COORDINATOR_LOCK) {
            handledGeneration = requestedGeneration;
        }
        IOException failure = synchronizeConfigured(context);
        long delayMillis;
        synchronized (COORDINATOR_LOCK) {
            recordCoordinatorResult(failure);
            if (handledGeneration == requestedGeneration) {
                mirrorRunnerScheduled = false;
                return;
            }
            // Keep the runner claim while spacing sustained notification traffic into batches.
            delayMillis = nextMirrorDelayMillis();
        }
        scheduleConfiguredMirrorRunner(context, delayMillis);
    }

    private static void recordCoordinatorResult(IOException failure) {
        if (failure == null) {
            consecutiveMirrorFailures = 0;
            retryNotBeforeElapsedRealtime = 0L;
            return;
        }
        consecutiveMirrorFailures = Math.min(16, consecutiveMirrorFailures + 1);
        long multiplier = 1L << Math.min(6, consecutiveMirrorFailures - 1);
        long delay = Math.min(
                MAX_FAILURE_RETRY_MILLIS,
                MIRROR_DEBOUNCE_MILLIS * multiplier);
        retryNotBeforeElapsedRealtime = SystemClock.elapsedRealtime() + delay;
    }

    private static long nextMirrorDelayMillis() {
        long remainingBackoff = retryNotBeforeElapsedRealtime - SystemClock.elapsedRealtime();
        return Math.max(MIRROR_DEBOUNCE_MILLIS, remainingBackoff);
    }

    private static IOException synchronizeConfigured(Context context) {
        if (selectedMode(context) != LogStorageMode.USER_FILESPACE) {
            return null;
        }
        Uri treeUri = selectedTreeUri(context);
        if (treeUri == null) {
            IOException failure = new IOException(
                    "Logging is enabled but no selected directory is available. Choose the directory again.");
            recordMirrorFailure(context, failure);
            return failure;
        }
        try {
            synchronizeToTree(context, treeUri);
            preferences(context).edit()
                    .putLong(LAST_SUCCESS_PREFERENCE_KEY, System.currentTimeMillis())
                    .remove(LAST_ERROR_PREFERENCE_KEY)
                    .apply();
            return null;
        } catch (IOException | RuntimeException error) {
            IOException failure = asIOException("Could not synchronize the MML log mirror.", error);
            recordMirrorFailure(context, failure);
            return failure;
        }
    }

    private static void recordMirrorFailure(Context context, IOException failure) {
        String detail = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        preferences(context).edit()
                .putString(LAST_ERROR_PREFERENCE_KEY, bounded(detail, 600))
                .apply();
    }

    private static void recordMirrorFailureBestEffort(Context context, IOException failure) {
        try {
            recordMirrorFailure(context, failure);
        } catch (RuntimeException ignored) {
            // The private journal remains authoritative; another mutation or app launch retries.
        }
    }

    private static void synchronizeToTree(Context context, Uri treeUri) throws IOException {
        File journalRoot = new File(context.getFilesDir(), "mml-journal");
        File calendarComments = new File(journalRoot, "calendar-comments");
        File notificationInbox = new File(journalRoot, "notification-inbox");
        List<File> orderedSessionFiles = orderedManagedSessionFiles(journalRoot);
        preflightLocalMirrorIndex(
                calendarComments, notificationInbox, orderedSessionFiles);

        ContentResolver resolver = context.getContentResolver();
        Uri selectedRoot = treeDocumentUri(treeUri);
        DirectorySnapshot selectedChildren = listChildren(resolver, selectedRoot);
        Uri mirrorRoot = requireDirectory(
                resolver, selectedRoot, selectedChildren, MIRROR_DIRECTORY_NAME, 1);
        DirectorySnapshot mirrorChildren = listChildren(resolver, mirrorRoot);
        ensureOwnedMirrorDirectory(resolver, mirrorChildren);

        IndexSnapshot previousIndexSnapshot = selectReadableIndex(
                resolver, mirrorChildren);
        Map<String, String> previousIndex = previousIndexSnapshot.entries;
        Map<String, String> nextIndex = new LinkedHashMap<>();

        byte[] readme = mirrorReadme().getBytes(StandardCharsets.UTF_8);
        mirrorBytes(
                resolver,
                mirrorRoot,
                mirrorChildren,
                README_NAME,
                readme,
                "static-5",
                previousIndex,
                nextIndex);

        Uri sessionRoot = requireDirectory(
                resolver, mirrorRoot, mirrorChildren, SESSION_DIRECTORY_NAME, 2);
        DirectorySnapshot sessionChildren = listChildren(resolver, sessionRoot);

        mirrorOptionalFile(
                resolver,
                mirrorRoot,
                mirrorChildren,
                calendarComments,
                "calendar-comments.txt",
                "root/calendar-comments",
                previousIndex,
                nextIndex);
        mirrorOptionalFile(
                resolver,
                mirrorRoot,
                mirrorChildren,
                notificationInbox,
                "notification-inbox.txt",
                "root/notification-inbox",
                previousIndex,
                nextIndex);

        Map<String, File> liveSessionFiles = new LinkedHashMap<>();
        for (File source : orderedSessionFiles) {
            liveSessionFiles.put(mirrorSessionFileName(source.getName()), source);
        }
        // Reclaim only prior-index-proven stale rows before admitting new pending files. The
        // capacity helper re-enumerates on pressure, confirming each provider deletion rather than
        // decrementing the cached physical count optimistically.
        removeStaleManagedSessions(
                resolver, sessionChildren, liveSessionFiles, previousIndex);
        for (File source : orderedSessionFiles) {
            String mirrorName = mirrorSessionFileName(source.getName());
            mirrorFile(
                    resolver,
                    sessionRoot,
                    sessionChildren,
                    source,
                    mirrorName,
                    "sessions/" + mirrorName,
                    previousIndex,
                    nextIndex);
        }

        String indexText = encodeIndex(nextIndex);
        writeOwnedIndexBytes(
                resolver,
                mirrorRoot,
                mirrorChildren,
                indexText.getBytes(StandardCharsets.UTF_8));
    }

    private static List<File> orderedManagedSessionFiles(File journalRoot) throws IOException {
        File sessionsDirectory = new File(journalRoot, "sessions");
        List<File> orderedSessionFiles = new ArrayList<>();
        if (sessionsDirectory.isDirectory()) {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(
                    sessionsDirectory.toPath())) {
                for (Path path : paths) {
                    if (!Files.isRegularFile(path)
                            || !isManagedSessionName(path.getFileName().toString())) {
                        continue;
                    }
                    if (orderedSessionFiles.size() >= MAX_DIRECTORY_ENTRIES) {
                        throw new IOException(
                                "The private journal exceeds the supported mirror file count of "
                                        + MAX_DIRECTORY_ENTRIES + ".");
                    }
                    orderedSessionFiles.add(path.toFile());
                }
            }
        }
        orderedSessionFiles.sort((left, right) -> left.getName().compareTo(right.getName()));
        return orderedSessionFiles;
    }

    private static void preflightLocalMirrorIndex(
            File calendarComments,
            File notificationInbox,
            List<File> orderedSessionFiles) throws IOException {
        int rootEntries = 1;
        if (calendarComments.isFile()) {
            rootEntries++;
        }
        if (notificationInbox.isFile()) {
            rootEntries++;
        }
        if (!MirrorOwnershipPolicy.hasIndexEntryCapacity(
                orderedSessionFiles.size(), rootEntries, MAX_INDEX_ENTRIES)) {
            throw new IOException(
                    "The private journal plus its root mirror files exceeds the supported index "
                            + "entry count of " + MAX_INDEX_ENTRIES + ".");
        }
        Map<String, String> localManifest = new LinkedHashMap<>();
        localManifest.put(README_NAME, "static-5");
        if (calendarComments.isFile()) {
            localManifest.put("root/calendar-comments", fileFingerprint(calendarComments));
        }
        if (notificationInbox.isFile()) {
            localManifest.put("root/notification-inbox", fileFingerprint(notificationInbox));
        }
        for (File source : orderedSessionFiles) {
            localManifest.put(
                    "sessions/" + mirrorSessionFileName(source.getName()),
                    fileFingerprint(source));
        }
        // Encode before any SAF traversal or mutation so both entry and exact byte budgets fail
        // against the bounded local manifest rather than after a nearly complete first mirror.
        encodeIndex(localManifest);
    }

    private static IndexSnapshot selectReadableIndex(
            ContentResolver resolver,
            DirectorySnapshot mirrorChildren) throws IOException {
        DocumentEntry index = mirrorChildren.byName.get(INDEX_NAME);
        DocumentEntry pending = mirrorChildren.byName.get(INDEX_NAME + PENDING_SUFFIX);
        IndexSnapshot pendingSnapshot = readIndex(resolver, pending);
        IndexSnapshot finalSnapshot = readIndex(resolver, index);
        if (MirrorIndexPolicy.preferPending(
                pendingSnapshot.valid, finalSnapshot.valid)
                && pendingSnapshot.valid) {
            // A complete pending index is the newer recovery copy when promotion was interrupted.
            return pendingSnapshot;
        }
        return finalSnapshot.valid ? finalSnapshot : IndexSnapshot.invalid();
    }

    private static void ensureOwnedMirrorDirectory(
            ContentResolver resolver,
            DirectorySnapshot mirrorChildren) throws IOException {
        DocumentEntry readme = mirrorChildren.byName.get(README_NAME);
        DocumentEntry pendingReadme = mirrorChildren.byName.get(README_NAME + PENDING_SUFFIX);
        DocumentEntry index = mirrorChildren.byName.get(INDEX_NAME);
        DocumentEntry pendingIndex = mirrorChildren.byName.get(INDEX_NAME + PENDING_SUFFIX);
        boolean validReadme = readme != null && hasOwnedReadmeMarker(
                resolver, readme.uri);
        boolean validPendingReadme = pendingReadme != null && hasOwnedReadmeMarker(
                resolver, pendingReadme.uri);
        boolean validIndex = readIndex(resolver, index).valid;
        boolean validPendingIndex = readIndex(resolver, pendingIndex).valid;
        if (MirrorOwnershipPolicy.mayUseDirectory(
                mirrorChildren.physicalEntryCount == 0,
                validReadme,
                validPendingReadme,
                validIndex,
                validPendingIndex)) {
            return;
        }
        throw new IOException(
                "An unrelated nonempty MML Logs folder already exists in that location. "
                        + "Choose another parent directory or rename that folder.");
    }


    /** Accepts both the current marker and the 1.6.2-1.6.5 ownership marker. */
    private static boolean hasOwnedReadmeMarker(
            ContentResolver resolver,
            Uri document) throws IOException {
        return firstLineStartsWith(resolver, document, USER_MANAGED_README_MARKER)
                || firstLineStartsWith(resolver, document, LEGACY_README_MARKER);
    }

    private static boolean firstLineStartsWith(
            ContentResolver resolver,
            Uri document,
            String marker) throws IOException {
        InputStream stream = resolver.openInputStream(document);
        if (stream == null) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BoundedInputStream(stream, 256L), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            return first != null && first.startsWith(marker);
        }
    }

    private static void mirrorOptionalFile(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            File source,
            String targetName,
            String indexPath,
            Map<String, String> previousIndex,
            Map<String, String> nextIndex) throws IOException {
        if (!source.isFile()) {
            String pendingName = targetName + PENDING_SUFFIX;
            if (children.byName.containsKey(pendingName)) {
                throw unindexedConflict(pendingName);
            }
            if (!MirrorFilePolicy.mayRemoveStale(
                    previousIndex.containsKey(indexPath), false)) {
                return;
            }
            DocumentEntry existing = children.byName.get(targetName);
            if (existing != null) {
                deleteDocument(resolver, existing.uri);
                children.byName.remove(targetName);
            }
            return;
        }
        mirrorFile(
                resolver,
                parent,
                children,
                source,
                targetName,
                indexPath,
                previousIndex,
                nextIndex);
    }

    private static void mirrorFile(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            File source,
            String targetName,
            String indexPath,
            Map<String, String> previousIndex,
            Map<String, String> nextIndex) throws IOException {
        long sourceLength = source.length();
        String fingerprint = fileFingerprint(source);
        DocumentEntry existing = children.byName.get(targetName);
        boolean pendingWriteExists = children.byName.containsKey(targetName + PENDING_SUFFIX);
        String previousFingerprint = previousIndex.get(indexPath);
        if (previousFingerprint == null && (existing != null || pendingWriteExists)) {
            recoverUnindexedFile(
                    resolver,
                    parent,
                    children,
                    source,
                    targetName,
                    sourceLength,
                    existing,
                    pendingWriteExists);
            nextIndex.put(indexPath, fingerprint);
            return;
        }
        DocumentEntry pending = children.byName.get(targetName + PENDING_SUFFIX);
        if (pending != null && storedFileMatches(
                resolver,
                pending.uri,
                source,
                sourceLength,
                targetName + PENDING_SUFFIX)) {
            promoteVerifiedPendingFile(
                    resolver,
                    parent,
                    children,
                    source,
                    targetName,
                    sourceLength);
            nextIndex.put(indexPath, fingerprint);
            return;
        }
        if (pending != null) {
            // Ownership of the indexed base never proves who created a differently named sidecar.
            throw unindexedConflict(targetName + PENDING_SUFFIX);
        }
        long externalLength = existing == null ? -1L : existing.size;
        boolean unknownLengthVerified = externalLength < 0L
                && existing != null
                && !pendingWriteExists
                && fingerprint.equals(previousFingerprint)
                && storedFileMatches(
                        resolver, existing.uri, source, sourceLength, targetName);
        if (MirrorFilePolicy.canReuseFinal(
                pendingWriteExists,
                fingerprint,
                previousFingerprint,
                externalLength,
                sourceLength,
                unknownLengthVerified)) {
            nextIndex.put(indexPath, fingerprint);
            return;
        }

        long previousLength = fingerprintLength(previousFingerprint);
        boolean appended = false;
        if (MirrorFilePolicy.canAppendEventTail(
                pendingWriteExists,
                existing != null,
                targetName,
                previousLength,
                sourceLength,
                externalLength)) {
            appended = appendFileRange(
                    resolver,
                    existing.uri,
                    source,
                    previousLength,
                    sourceLength - previousLength);
            if (appended) {
                long updatedLength = querySize(resolver, existing.uri);
                appended = updatedLength < 0L
                        ? storedFileMatches(
                                resolver,
                                existing.uri,
                                source,
                                sourceLength,
                                targetName)
                        : updatedLength == sourceLength;
                existing.size = updatedLength < 0L ? sourceLength : updatedLength;
            }
        }
        if (!appended) {
            writeFile(resolver, parent, children, targetName, source, sourceLength);
        }
        nextIndex.put(indexPath, fingerprint);
    }

    private static String fileFingerprint(File source) {
        return source.length() + ":" + source.lastModified();
    }

    private static void mirrorBytes(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            byte[] value,
            String fingerprint,
            Map<String, String> previousIndex,
            Map<String, String> nextIndex) throws IOException {
        if (README_NAME.equals(targetName)
                && establishIndependentReadmeMarkerIfRequired(
                        resolver, parent, children, value)) {
            nextIndex.put(targetName, fingerprint);
            return;
        }
        DocumentEntry existing = children.byName.get(targetName);
        boolean pendingWriteExists = children.byName.containsKey(targetName + PENDING_SUFFIX);
        String previousFingerprint = previousIndex.get(targetName);
        if (previousFingerprint == null && (existing != null || pendingWriteExists)) {
            boolean manifestOwned = README_NAME.equals(targetName)
                    && MirrorOwnershipPolicy.mayHealReservedControlDocument(
                            hasValidOwnershipMarker(resolver, children), false);
            if (!manifestOwned) {
                recoverUnindexedBytes(
                        resolver,
                        parent,
                        children,
                        targetName,
                        value,
                        existing,
                        pendingWriteExists);
                nextIndex.put(targetName, fingerprint);
                return;
            }
        }
        DocumentEntry pending = children.byName.get(targetName + PENDING_SUFFIX);
        if (pending != null && storedBytesMatch(
                resolver,
                pending.uri,
                value,
                targetName + PENDING_SUFFIX)) {
            promoteVerifiedPendingBytes(
                    resolver, parent, children, targetName, value);
            nextIndex.put(targetName, fingerprint);
            return;
        }
        if (pending != null) {
            throw unindexedConflict(targetName + PENDING_SUFFIX);
        }
        long externalLength = existing == null ? -1L : existing.size;
        boolean unknownLengthVerified = externalLength < 0L
                && existing != null
                && !pendingWriteExists
                && fingerprint.equals(previousFingerprint)
                && storedBytesMatch(resolver, existing.uri, value, targetName);
        if (MirrorFilePolicy.canReuseFinal(
                pendingWriteExists,
                fingerprint,
                previousFingerprint,
                externalLength,
                value.length,
                unknownLengthVerified)) {
            nextIndex.put(targetName, fingerprint);
            return;
        }
        writeBytes(resolver, parent, children, targetName, value);
        nextIndex.put(targetName, fingerprint);
    }

    /**
     * Never truncates a differing pending control document. Its own valid README marker proves it
     * is an interrupted MML sidecar; write and byte-validate the final while that proof remains
     * untouched, then remove the obsolete pending copy.
     */
    private static boolean establishIndependentReadmeMarkerIfRequired(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            byte[] currentReadme) throws IOException {
        DocumentEntry pendingReadme = children.byName.get(README_NAME + PENDING_SUFFIX);
        if (pendingReadme == null) {
            return false;
        }
        boolean validPendingReadme = hasOwnedReadmeMarker(
                resolver, pendingReadme.uri);
        boolean pendingMatchesCurrent = validPendingReadme && storedBytesMatch(
                resolver,
                pendingReadme.uri,
                currentReadme,
                README_NAME + PENDING_SUFFIX);
        if (pendingMatchesCurrent || !MirrorOwnershipPolicy.mayManagePendingSidecar(
                false, validPendingReadme)) {
            return false;
        }
        writeFinalControlBytesWhilePreservingPending(
                resolver,
                parent,
                children,
                README_NAME,
                README_NAME + PENDING_SUFFIX,
                currentReadme);
        return true;
    }

    private static void writeFinalControlBytesWhilePreservingPending(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            String pendingName,
            byte[] value) throws IOException {
        DocumentEntry pending = children.byName.get(pendingName);
        if (pending == null) {
            throw new IOException("The proven pending " + targetName + " document disappeared.");
        }
        DocumentEntry independentFinal = requireFile(
                resolver, parent, children, targetName);
        try (InputStream input = new java.io.ByteArrayInputStream(value)) {
            writeStream(resolver, independentFinal.uri, input, value.length);
        }
        verifyStoredBytes(resolver, independentFinal.uri, value, targetName);
        independentFinal.size = value.length;
        removeDocumentIfPresent(resolver, children, pendingName);
    }

    private static void recoverUnindexedFile(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            File source,
            String targetName,
            long sourceLength,
            DocumentEntry existing,
            boolean pendingWriteExists) throws IOException {
        boolean finalMatches = existing == null;
        if (existing != null) {
            finalMatches = storedFileMatches(
                    resolver, existing.uri, source, sourceLength, targetName);
        }
        DocumentEntry pending = children.byName.get(targetName + PENDING_SUFFIX);
        boolean pendingMatches = pending == null;
        if (pending != null) {
            pendingMatches = storedFileMatches(
                    resolver, pending.uri, source, sourceLength, targetName + PENDING_SUFFIX);
        }
        if (existing != null
                && !MirrorFilePolicy.mayManageExisting(false, finalMatches)) {
            throw unindexedConflict(targetName);
        }
        if (pendingWriteExists
                && !MirrorFilePolicy.mayRecoverUnindexedPending(
                        pendingMatches, existing != null, finalMatches)) {
            throw unindexedConflict(targetName + PENDING_SUFFIX);
        }
        if (pending != null && existing == null) {
            DocumentVerifier verifier = target -> verifyStoredFile(
                    resolver, target, source, sourceLength, targetName);
            promotePending(
                    resolver,
                    parent,
                    children,
                    targetName,
                    targetName + PENDING_SUFFIX,
                    sourceLength,
                    verifier,
                    target -> {
                        try (FileInputStream input = new FileInputStream(source)) {
                            writeStream(resolver, target, input, sourceLength);
                        }
                    });
        } else if (pending != null) {
            removeDocumentIfPresent(resolver, children, targetName + PENDING_SUFFIX);
        }
    }

    private static void recoverUnindexedBytes(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            byte[] value,
            DocumentEntry existing,
            boolean pendingWriteExists) throws IOException {
        boolean finalMatches = existing == null
                || storedBytesMatch(resolver, existing.uri, value, targetName);
        DocumentEntry pending = children.byName.get(targetName + PENDING_SUFFIX);
        boolean pendingMatches = pending == null
                || storedBytesMatch(resolver, pending.uri, value, targetName + PENDING_SUFFIX);
        if (existing != null
                && !MirrorFilePolicy.mayManageExisting(false, finalMatches)) {
            throw unindexedConflict(targetName);
        }
        if (pendingWriteExists
                && !MirrorFilePolicy.mayRecoverUnindexedPending(
                        pendingMatches, existing != null, finalMatches)) {
            throw unindexedConflict(targetName + PENDING_SUFFIX);
        }
        if (pending != null && existing == null) {
            DocumentVerifier verifier = target -> verifyStoredBytes(
                    resolver, target, value, targetName);
            promotePending(
                    resolver,
                    parent,
                    children,
                    targetName,
                    targetName + PENDING_SUFFIX,
                    value.length,
                    verifier,
                    target -> {
                        try (InputStream input = new java.io.ByteArrayInputStream(value)) {
                            writeStream(resolver, target, input, value.length);
                        }
                    });
        } else if (pending != null) {
            removeDocumentIfPresent(resolver, children, targetName + PENDING_SUFFIX);
        }
    }

    private static void promoteVerifiedPendingFile(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            File source,
            String targetName,
            long sourceLength) throws IOException {
        DocumentVerifier verifier = target -> verifyStoredFile(
                resolver, target, source, sourceLength, targetName);
        promotePending(
                resolver,
                parent,
                children,
                targetName,
                targetName + PENDING_SUFFIX,
                sourceLength,
                verifier,
                target -> {
                    try (FileInputStream input = new FileInputStream(source)) {
                        writeStream(resolver, target, input, sourceLength);
                    }
                });
    }

    private static void promoteVerifiedPendingBytes(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            byte[] value) throws IOException {
        DocumentVerifier verifier = target -> verifyStoredBytes(
                resolver, target, value, targetName);
        promotePending(
                resolver,
                parent,
                children,
                targetName,
                targetName + PENDING_SUFFIX,
                value.length,
                verifier,
                target -> {
                    try (InputStream input = new java.io.ByteArrayInputStream(value)) {
                        writeStream(resolver, target, input, value.length);
                    }
                });
    }

    private static boolean storedFileMatches(
            ContentResolver resolver,
            Uri document,
            File source,
            long sourceLength,
            String targetName) {
        try {
            verifyStoredFile(resolver, document, source, sourceLength, targetName);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean storedBytesMatch(
            ContentResolver resolver,
            Uri document,
            byte[] value,
            String targetName) {
        try {
            verifyStoredBytes(resolver, document, value, targetName);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static IOException unindexedConflict(String targetName) {
        return new IOException(
                "User filespace already contains unindexed " + targetName
                        + " with bytes MML cannot prove it created. The file was preserved; "
                        + "move or rename it before synchronizing.");
    }

    private static void writeOwnedIndexBytes(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            byte[] value) throws IOException {
        DocumentEntry existing = children.byName.get(INDEX_NAME);
        DocumentEntry pending = children.byName.get(INDEX_NAME + PENDING_SUFFIX);
        boolean pendingMatchesCurrent = pending != null && storedBytesMatch(
                resolver,
                pending.uri,
                value,
                INDEX_NAME + PENDING_SUFFIX);
        if (pendingMatchesCurrent) {
            promoteVerifiedPendingBytes(
                    resolver, parent, children, INDEX_NAME, value);
            return;
        }
        if (pending != null) {
            boolean validPendingIndex = readIndex(resolver, pending).valid;
            if (!MirrorOwnershipPolicy.mayManagePendingSidecar(false, validPendingIndex)) {
                throw unindexedConflict(INDEX_NAME + PENDING_SUFFIX);
            }
            writeFinalControlBytesWhilePreservingPending(
                    resolver,
                    parent,
                    children,
                    INDEX_NAME,
                    INDEX_NAME + PENDING_SUFFIX,
                    value);
            return;
        }
        boolean validOwnershipMarker = hasValidOwnershipMarker(resolver, children);
        boolean exactExistingControlBytes = existing != null
                && storedBytesMatch(resolver, existing.uri, value, INDEX_NAME);
        if (!MirrorOwnershipPolicy.mayHealReservedControlDocument(
                validOwnershipMarker,
                exactExistingControlBytes)) {
            if (existing != null
                    && !storedBytesMatch(resolver, existing.uri, value, INDEX_NAME)) {
                throw unindexedConflict(INDEX_NAME);
            }
        }
        writeBytes(resolver, parent, children, INDEX_NAME, value);
    }

    private static boolean hasValidOwnershipMarker(
            ContentResolver resolver,
            DirectorySnapshot children) throws IOException {
        DocumentEntry readme = children.byName.get(README_NAME);
        DocumentEntry pendingReadme = children.byName.get(README_NAME + PENDING_SUFFIX);
        DocumentEntry index = children.byName.get(INDEX_NAME);
        DocumentEntry pendingIndex = children.byName.get(INDEX_NAME + PENDING_SUFFIX);
        return readme != null && hasOwnedReadmeMarker(resolver, readme.uri)
                || pendingReadme != null && hasOwnedReadmeMarker(
                        resolver, pendingReadme.uri)
                || readIndex(resolver, index).valid
                || readIndex(resolver, pendingIndex).valid;
    }

    private static void writeFile(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            File source,
            long maximumBytes) throws IOException {
        String pendingName = targetName + PENDING_SUFFIX;
        preflightPendingCreation(
                resolver, parent, children, targetName, pendingName);
        DocumentEntry pending = requireFile(resolver, parent, children, pendingName);
        try (FileInputStream input = new FileInputStream(source)) {
            writeStream(resolver, pending.uri, input, maximumBytes);
        }
        DocumentVerifier verifier = target -> verifyStoredFile(
                resolver, target, source, maximumBytes, targetName);
        validateStoredDocument(
                resolver, pending, maximumBytes, targetName);
        // COLUMN_SIZE is only a length claim. Read every byte before this recovery copy is allowed
        // to replace an older final, including when the provider reports the expected size.
        verifier.verify(pending.uri);
        promotePending(
                resolver,
                parent,
                children,
                targetName,
                pendingName,
                maximumBytes,
                verifier,
                target -> {
                    try (FileInputStream input = new FileInputStream(source)) {
                        writeStream(resolver, target, input, maximumBytes);
                    }
                });
    }

    private static void writeBytes(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            byte[] value) throws IOException {
        String pendingName = targetName + PENDING_SUFFIX;
        preflightPendingCreation(
                resolver, parent, children, targetName, pendingName);
        DocumentEntry pending = requireFile(resolver, parent, children, pendingName);
        try (InputStream input = new java.io.ByteArrayInputStream(value)) {
            writeStream(resolver, pending.uri, input, value.length);
        }
        DocumentVerifier verifier = target -> verifyStoredBytes(
                resolver, target, value, targetName);
        validateStoredDocument(
                resolver, pending, value.length, targetName);
        // README/index byte arrays are bounded control documents. Exact readback prevents a
        // same-length provider corruption from replacing their durable ownership proof.
        verifier.verify(pending.uri);
        promotePending(
                resolver,
                parent,
                children,
                targetName,
                pendingName,
                value.length,
                verifier,
                target -> {
                    try (InputStream input = new java.io.ByteArrayInputStream(value)) {
                        writeStream(resolver, target, input, value.length);
                    }
                });
    }

    private static void validateStoredDocument(
            ContentResolver resolver,
            DocumentEntry entry,
            long expectedBytes,
            String targetName) throws IOException {
        long size = querySize(resolver, entry.uri);
        if (size >= 0L && size != expectedBytes) {
            throw new IOException("The filespace provider stored an incomplete " + targetName + ".");
        }
        entry.size = size < 0L ? expectedBytes : size;
    }

    private static void preflightPendingCreation(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            String pendingName) throws IOException {
        DocumentEntry pending = children.byName.get(pendingName);
        if (pending != null) {
            throw unindexedConflict(pendingName);
        }
        DocumentEntry existingFinal = children.byName.get(targetName);
        requireFileKind(existingFinal, targetName);
        int requiredSlots = MirrorOwnershipPolicy.requiredSlotsBeforePending(
                existingFinal != null);
        if (MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, requiredSlots)) {
            return;
        }

        refreshDirectorySnapshot(resolver, parent, children);
        pending = children.byName.get(pendingName);
        if (pending != null) {
            throw unindexedConflict(pendingName);
        }
        existingFinal = children.byName.get(targetName);
        requireFileKind(existingFinal, targetName);
        requiredSlots = MirrorOwnershipPolicy.requiredSlotsBeforePending(
                existingFinal != null);
        if (!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, requiredSlots)) {
            throw new IOException(
                    "The filespace mirror cannot reserve both recovery and final slots for "
                            + targetName + ". At most " + (MAX_DIRECTORY_ENTRIES - 1)
                            + " live documents are supported per directory.");
        }
    }

    private static void requireFileKind(DocumentEntry entry, String name) throws IOException {
        if (entry != null && DocumentsContract.Document.MIME_TYPE_DIR.equals(entry.mimeType)) {
            throw new IOException(name + " exists in the mirror but is a folder.");
        }
    }

    private static void verifyStoredFile(
            ContentResolver resolver,
            Uri target,
            File source,
            long expectedBytes,
            String targetName) throws IOException {
        try (FileInputStream expected = new FileInputStream(source);
             InputStream actual = requireInputStream(resolver, target, targetName)) {
            verifyEqualStreams(expected, actual, expectedBytes, targetName);
        }
    }

    private static void verifyStoredBytes(
            ContentResolver resolver,
            Uri target,
            byte[] expectedBytes,
            String targetName) throws IOException {
        try (InputStream expected = new java.io.ByteArrayInputStream(expectedBytes);
             InputStream actual = requireInputStream(resolver, target, targetName)) {
            verifyEqualStreams(expected, actual, expectedBytes.length, targetName);
        }
    }

    private static InputStream requireInputStream(
            ContentResolver resolver,
            Uri target,
            String targetName) throws IOException {
        InputStream input = resolver.openInputStream(target);
        if (input == null) {
            throw new IOException(
                    "The filespace provider did not reopen " + targetName + " for validation.");
        }
        return input;
    }

    private static void verifyEqualStreams(
            InputStream expected,
            InputStream actual,
            long expectedBytes,
            String targetName) throws IOException {
        byte[] expectedBuffer = new byte[COPY_BUFFER_BYTES];
        byte[] actualBuffer = new byte[COPY_BUFFER_BYTES];
        long remaining = expectedBytes;
        while (remaining > 0L) {
            int requested = (int) Math.min(COPY_BUFFER_BYTES, remaining);
            int expectedCount = readSome(expected, expectedBuffer, requested);
            int actualCount = readSome(actual, actualBuffer, requested);
            if (expectedCount != requested || actualCount != requested) {
                throw new IOException(
                        "The filespace provider stored an incomplete " + targetName + ".");
            }
            for (int index = 0; index < requested; index++) {
                if (expectedBuffer[index] != actualBuffer[index]) {
                    throw new IOException(
                            "The filespace provider changed bytes in " + targetName + ".");
                }
            }
            remaining -= requested;
        }
        if (expected.read() >= 0) {
            throw new IOException(
                    "The private source changed while validating " + targetName + ".");
        }
        if (actual.read() >= 0) {
            throw new IOException(
                    "The filespace provider stored unexpected trailing bytes in "
                            + targetName + ".");
        }
    }

    private static int readSome(InputStream input, byte[] buffer, int requested)
            throws IOException {
        int offset = 0;
        while (offset < requested) {
            int count = input.read(buffer, offset, requested - offset);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                buffer[offset++] = (byte) value;
            } else {
                offset += count;
            }
        }
        return offset;
    }

    private static void promotePending(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String targetName,
            String pendingName,
            long expectedBytes,
            DocumentVerifier verifier,
            DocumentWriter fallbackWriter) throws IOException {
        DocumentEntry pending = children.byName.get(pendingName);
        if (pending == null) {
            throw new IOException("The complete pending " + targetName + " document disappeared.");
        }

        // Do not ask a provider to rename the recovery document. Some providers silently choose a
        // numbered display name, which can strand a succession of owned but unrecognized copies.
        // Keep the already-validated pending bytes intact until an exact-identity final validates.
        removeDocumentIfPresent(resolver, children, targetName);
        DocumentEntry target = requireFile(resolver, parent, children, targetName);
        fallbackWriter.write(target.uri);
        validateStoredDocument(resolver, target, expectedBytes, targetName);
        // A matching provider size cannot prove content. Keep the sole complete pending recovery
        // copy until the newly written final also passes an unconditional byte-for-byte readback.
        verifier.verify(target.uri);
        removeDocumentIfPresent(resolver, children, pendingName);
    }

    private static void writeStream(
            ContentResolver resolver,
            Uri target,
            InputStream input,
            long maximumBytes) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(target, "wt");
        if (descriptor == null) {
            throw new IOException("The filespace provider did not open an output document.");
        }
        try (ParcelFileDescriptor ownedDescriptor = descriptor;
             FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
            copyLimited(input, output, maximumBytes);
            output.flush();
            syncIfSupported(output);
        }
    }

    private static boolean appendFileRange(
            ContentResolver resolver,
            Uri target,
            File source,
            long offset,
            long count) {
        try (FileInputStream input = new FileInputStream(source)) {
            skipFully(input, offset);
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(target, "wa");
            if (descriptor == null) {
                return false;
            }
            try (ParcelFileDescriptor ownedDescriptor = descriptor;
                 FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                copyLimited(input, output, count);
                output.flush();
                syncIfSupported(output);
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void copyLimited(InputStream input, OutputStream output, long byteLimit)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long remaining = byteLimit;
        while (remaining > 0L) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("The private journal changed while it was being mirrored.");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static void skipFully(InputStream input, long byteCount) throws IOException {
        long remaining = byteCount;
        byte[] discard = new byte[COPY_BUFFER_BYTES];
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            int count = input.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (count < 0) {
                throw new IOException("The private event log ended before its mirror offset.");
            }
            remaining -= count;
        }
    }

    private static void syncIfSupported(FileOutputStream output) throws IOException {
        try {
            output.getFD().sync();
        } catch (SyncFailedException ignored) {
            // Pipe- and cloud-backed providers commit on close and may not expose fsync.
        }
    }

    private static IndexSnapshot readIndex(
            ContentResolver resolver,
            DocumentEntry indexEntry)
            throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (indexEntry == null) {
            return IndexSnapshot.invalid();
        }
        long size = indexEntry.size;
        if (size > MAX_INDEX_BYTES) {
            return IndexSnapshot.invalid();
        }
        InputStream stream = resolver.openInputStream(indexEntry.uri);
        if (stream == null) {
            return IndexSnapshot.invalid();
        }
        long total = 0L;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BoundedInputStream(stream, MAX_INDEX_BYTES), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            boolean framed = MirrorIndexPolicy.isFramedHeader(line);
            boolean legacy = MirrorIndexPolicy.isLegacyHeader(line);
            if (!framed && !legacy) {
                // Version 2 predated recoverable pending-document promotion. Rebuild once so an
                // unknown-size provider cannot preserve a truncated legacy final by fingerprint.
                return IndexSnapshot.invalid();
            }
            total = line.length() + 1L;
            boolean trailerSeen = false;
            int declaredEntryCount = -1;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1L;
                if (total > MAX_INDEX_BYTES) {
                    return IndexSnapshot.invalid();
                }
                List<String> row = LineCodec.decode(line);
                if (framed && row.size() == 2 && "END".equals(row.get(0))) {
                    try {
                        declaredEntryCount = Integer.parseInt(row.get(1));
                    } catch (NumberFormatException ignored) {
                        return IndexSnapshot.invalid();
                    }
                    if (reader.readLine() != null) {
                        return IndexSnapshot.invalid();
                    }
                    trailerSeen = true;
                    break;
                }
                if (row.size() == 3
                        && "F".equals(row.get(0))
                        && row.get(1).length() <= 1_024
                        && row.get(2).length() <= 128) {
                    if (result.size() >= MAX_INDEX_ENTRIES
                            || result.containsKey(row.get(1))) {
                        return IndexSnapshot.invalid();
                    }
                    result.put(row.get(1), row.get(2));
                } else {
                    return IndexSnapshot.invalid();
                }
            }
            // Every complete v3 mirror included README; a header-only/truncated legacy file must
            // not be mistaken for an intentionally empty ownership manifest. V4 uses an explicit
            // count trailer, so a complete pending index can safely supersede an old valid final.
            if (!MirrorIndexPolicy.isComplete(
                    framed,
                    trailerSeen,
                    declaredEntryCount,
                    result.size(),
                    legacy && result.containsKey(README_NAME))) {
                return IndexSnapshot.invalid();
            }
        }
        return new IndexSnapshot(true, result);
    }

    private static String encodeIndex(Map<String, String> index) throws IOException {
        if (index.size() > MAX_INDEX_ENTRIES) {
            throw new IOException(
                    "The MML mirror index exceeds its supported entry count of "
                            + MAX_INDEX_ENTRIES + ".");
        }
        StringBuilder result = new StringBuilder(MirrorIndexPolicy.FRAMED_HEADER + "\n");
        for (Map.Entry<String, String> entry : index.entrySet()) {
            String row = LineCodec.encode("F", entry.getKey(), entry.getValue());
            if ((long) result.length() + row.length() + 1L > MAX_INDEX_BYTES) {
                throw new IOException(
                        "The MML mirror index exceeds its supported size of "
                                + MAX_INDEX_BYTES + " bytes.");
            }
            result.append(row).append('\n');
        }
        String trailer = LineCodec.encode("END", Integer.toString(index.size()));
        if ((long) result.length() + trailer.length() + 1L > MAX_INDEX_BYTES) {
            throw new IOException(
                    "The MML mirror index has no room for its completeness trailer.");
        }
        result.append(trailer).append('\n');
        return result.toString();
    }

    private static long fingerprintLength(String fingerprint) {
        if (fingerprint == null) {
            return -1L;
        }
        int separator = fingerprint.indexOf(':');
        if (separator <= 0) {
            return -1L;
        }
        try {
            return Long.parseLong(fingerprint.substring(0, separator));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static void removeStaleManagedSessions(
            ContentResolver resolver,
            DirectorySnapshot sessionChildren,
            Map<String, File> liveFiles,
            Map<String, String> previousIndex) throws IOException {
        // Resolve every sidecar before deleting any stale indexed base. A live sidecar must already
        // match its exact private source so mirrorFile can promote it later; without a live source,
        // no byte comparison can prove ownership and the sidecar is preserved as a conflict.
        for (String name : sessionChildren.byName.keySet()) {
            if (!name.endsWith(PENDING_SUFFIX)) {
                continue;
            }
            String baseName = name.substring(0, name.length() - PENDING_SUFFIX.length());
            if (isManagedSessionName(baseName)) {
                File liveSource = liveFiles.get(baseName);
                if (liveSource == null || !liveSource.isFile() || !storedFileMatches(
                        resolver,
                        sessionChildren.byName.get(name).uri,
                        liveSource,
                        liveSource.length(),
                        name)) {
                    throw unindexedConflict(name);
                }
            }
        }
        for (Map.Entry<String, DocumentEntry> child
                : new ArrayList<>(sessionChildren.byName.entrySet())) {
            String name = child.getKey();
            String baseName = name;
            boolean live = liveFiles.containsKey(baseName);
            boolean indexed = previousIndex.containsKey("sessions/" + baseName);
            if (isManagedSessionName(baseName)
                    && MirrorFilePolicy.mayRemoveStale(indexed, live)) {
                deleteDocument(resolver, child.getValue().uri);
                sessionChildren.byName.remove(child.getKey());
            }
        }
    }

    private static void removeDocumentIfPresent(
            ContentResolver resolver,
            DirectorySnapshot children,
            String name) throws IOException {
        DocumentEntry child = children.byName.get(name);
        if (child != null) {
            deleteDocument(resolver, child.uri);
            children.byName.remove(name);
        }
    }

    private static String mirrorSessionFileName(String privateName) {
        return privateName + SESSION_MIRROR_TEXT_SUFFIX;
    }

    private static boolean isManagedSessionName(String name) {
        if (name == null) {
            return false;
        }
        String candidate = name.endsWith(SESSION_MIRROR_TEXT_SUFFIX)
                ? name.substring(0, name.length() - SESSION_MIRROR_TEXT_SUFFIX.length())
                : name;
        String[] suffixes = {".meta", ".events", ".notes", ".state"};
        for (String suffix : suffixes) {
            if (candidate.endsWith(suffix)) {
                String id = candidate.substring(0, candidate.length() - suffix.length());
                return !id.isEmpty() && id.matches("[A-Za-z0-9._-]+");
            }
        }
        return false;
    }

    private static Uri treeDocumentUri(Uri treeUri) throws IOException {
        try {
            return DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        } catch (RuntimeException error) {
            throw new IOException("The selected directory URI is not a usable document tree.", error);
        }
    }

    private static DirectorySnapshot listChildren(ContentResolver resolver, Uri parent)
            throws IOException {
        Map<String, DocumentEntry> byName = new LinkedHashMap<>();
        Set<String> seenDocumentIds = new LinkedHashSet<>();
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    parent, DocumentsContract.getDocumentId(parent));
        } catch (RuntimeException error) {
            throw new IOException("The filespace provider rejected directory enumeration.", error);
        }
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        int scannedEntries = 0;
        long scannedMetadataCharacters = 0L;
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("The filespace provider returned no directory listing.");
            }
            while (cursor.moveToNext()) {
                scannedEntries++;
                if (scannedEntries > MAX_DIRECTORY_ENTRIES) {
                    throw new IOException(
                            "A filespace directory exceeds the supported entry count of "
                                    + MAX_DIRECTORY_ENTRIES + ".");
                }
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                if (documentId == null
                        || name == null
                        || name.length() > 1_024
                        || documentId.length() > MAX_GRANT_URI_CHARACTERS
                        || mime == null
                        || mime.length() > MAX_MIME_TYPE_CHARACTERS) {
                    throw new IOException(
                            "The filespace provider returned a malformed directory entry.");
                }
                long rowMetadataCharacters = (long) documentId.length()
                        + name.length() + mime.length();
                if (rowMetadataCharacters
                        > MAX_DIRECTORY_METADATA_CHARACTERS - scannedMetadataCharacters) {
                    throw new IOException(
                            "A filespace directory exceeds the supported metadata size of "
                                    + MAX_DIRECTORY_METADATA_CHARACTERS + " characters.");
                }
                scannedMetadataCharacters += rowMetadataCharacters;
                if (!seenDocumentIds.add(documentId)) {
                    throw new IOException(
                            "The filespace provider returned one document identity under multiple "
                                    + "display names; MML preserved the ambiguous document.");
                }
                if (byName.containsKey(name)) {
                    throw new IOException(
                            "The filespace provider returned the duplicate display name "
                                    + name + "; MML preserved both ambiguous documents.");
                }
                byName.put(
                        name,
                        new DocumentEntry(
                                DocumentsContract.buildDocumentUriUsingTree(parent, documentId),
                                mime,
                                size));
            }
        } catch (SecurityException | IllegalArgumentException error) {
            throw new IOException("MML no longer has access to the selected directory.", error);
        }
        return new DirectorySnapshot(byName, scannedEntries);
    }

    private static Uri requireDirectory(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String name,
            int requiredSlots) throws IOException {
        DocumentEntry existing = children.byName.get(name);
        if (existing != null) {
            if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(existing.mimeType)) {
                throw new IOException(name + " exists in the selected location but is not a folder.");
            }
            return existing.uri;
        }
        if (!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, requiredSlots)) {
            refreshDirectorySnapshot(resolver, parent, children);
            existing = children.byName.get(name);
            if (existing != null) {
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(existing.mimeType)) {
                    throw new IOException(
                            name + " exists in the selected location but is not a folder.");
                }
                return existing.uri;
            }
            if (!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                    children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, requiredSlots)) {
                throw new IOException(
                        "The filespace directory does not have " + requiredSlots
                                + " confirmed physical slot"
                                + (requiredSlots == 1 ? "" : "s") + " required to create "
                                + name + ".");
            }
        }
        Uri created;
        try {
            created = DocumentsContract.createDocument(
                    resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (RuntimeException error) {
            throw new IOException("Could not create the " + name + " folder.", error);
        }
        if (created == null) {
            throw new IOException("The filespace provider did not create the " + name + " folder.");
        }
        validateCreatedIdentity(
                resolver,
                created,
                name,
                DocumentsContract.Document.MIME_TYPE_DIR);
        children.byName.put(
                name,
                new DocumentEntry(created, DocumentsContract.Document.MIME_TYPE_DIR, -1L));
        children.physicalEntryCount++;
        return created;
    }

    private static DocumentEntry requireFile(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children,
            String name) throws IOException {
        DocumentEntry existing = children.byName.get(name);
        if (existing != null) {
            requireFileKind(existing, name);
            return existing;
        }
        if (!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, 1)) {
            refreshDirectorySnapshot(resolver, parent, children);
            existing = children.byName.get(name);
            if (existing != null) {
                requireFileKind(existing, name);
                return existing;
            }
            if (!MirrorOwnershipPolicy.hasPhysicalEntryCapacity(
                    children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, 1)) {
                throw new IOException(
                        "The filespace mirror has no confirmed physical entry slot for " + name
                                + ".");
            }
        }
        Uri created;
        try {
            created = DocumentsContract.createDocument(
                    resolver, parent, "text/plain", name);
        } catch (RuntimeException error) {
            throw new IOException("Could not create " + name + " in user filespace.", error);
        }
        if (created == null) {
            throw new IOException("The filespace provider did not create " + name + ".");
        }
        validateCreatedIdentity(resolver, created, name, "text/plain");
        DocumentEntry entry = new DocumentEntry(created, "text/plain", -1L);
        children.byName.put(name, entry);
        children.physicalEntryCount++;
        return entry;
    }

    private static void refreshDirectorySnapshot(
            ContentResolver resolver,
            Uri parent,
            DirectorySnapshot children) throws IOException {
        DirectorySnapshot refreshed = listChildren(resolver, parent);
        for (Map.Entry<String, DocumentEntry> known : children.byName.entrySet()) {
            DocumentEntry fresh = refreshed.byName.get(known.getKey());
            boolean sameIdentityAndKind = fresh != null
                    && known.getValue().uri.equals(fresh.uri)
                    && known.getValue().mimeType.equals(fresh.mimeType);
            if (!MirrorOwnershipPolicy.isStableRefreshEntry(
                    true, fresh != null, sameIdentityAndKind)) {
                throw new IOException(
                        "The filespace entry " + known.getKey()
                                + " changed identity during synchronization. MML preserved it and "
                                + "will retry from a fresh snapshot.");
            }
            known.getValue().size = fresh.size;
        }
        for (String freshName : refreshed.byName.keySet()) {
            if (!MirrorOwnershipPolicy.isStableRefreshEntry(
                    children.byName.containsKey(freshName), true, true)) {
                throw new IOException(
                        "The filespace entry " + freshName
                                + " appeared during synchronization. MML preserved it and will "
                                + "retry from a fresh snapshot.");
            }
        }
        children.physicalEntryCount = refreshed.physicalEntryCount;
    }

    private static long querySize(ContentResolver resolver, Uri documentUri) {
        String[] projection = {DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cursor = resolver.query(documentUri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException ignored) {
            // Some remote providers do not expose a stable byte count.
        }
        return -1L;
    }

    private static void validateCreatedIdentity(
            ContentResolver resolver,
            Uri created,
            String expectedName,
            String expectedMimeType) throws IOException {
        try {
            requireExactDocumentIdentity(
                    resolver, created, expectedName, expectedMimeType);
        } catch (IOException identityFailure) {
            try {
                deleteDocument(resolver, created);
            } catch (IOException cleanupFailure) {
                identityFailure.addSuppressed(cleanupFailure);
            }
            throw identityFailure;
        }
    }

    private static void requireExactDocumentIdentity(
            ContentResolver resolver,
            Uri document,
            String expectedName,
            String expectedMimeType) throws IOException {
        String[] projection = {
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(document, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException(
                        "The filespace provider did not identify the created mirror document.");
            }
            String actualName = cursor.getString(0);
            String actualMimeType = cursor.getString(1);
            boolean expectedDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(
                    expectedMimeType);
            boolean actualDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(
                    actualMimeType);
            if (!expectedName.equals(actualName)
                    || expectedDirectory != actualDirectory
                    || cursor.moveToNext()) {
                throw new IOException(
                        "The filespace provider created a different name or document kind for "
                                + expectedName + "; MML did not adopt that document.");
            }
        } catch (RuntimeException error) {
            throw new IOException(
                    "The filespace provider would not verify the created display name.", error);
        }
    }

    private static void deleteDocument(ContentResolver resolver, Uri documentUri)
            throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(resolver, documentUri)) {
                throw new IOException("The filespace provider did not remove a stale mirror file.");
            }
        } catch (RuntimeException error) {
            throw new IOException("Could not remove a stale mirror file.", error);
        }
    }

    private static boolean releasePersistedPermission(Context context, Uri treeUri) {
        if (treeUri == null) {
            return true;
        }
        int flags = persistedPermissionFlags(context, treeUri);
        if (flags == 0) {
            return true;
        }
        if (flags < 0) {
            return false;
        }
        return releasePersistedPermission(context, treeUri, flags);
    }

    private static boolean releasePersistedPermission(
            Context context,
            Uri treeUri,
            int exactFlags) {
        if (treeUri == null) {
            return true;
        }
        int flags = exactFlags & REQUIRED_GRANT_FLAGS;
        if (flags == 0) {
            return persistedPermissionFlags(context, treeUri) == 0;
        }
        try {
            context.getContentResolver().releasePersistableUriPermission(treeUri, flags);
        } catch (RuntimeException ignored) {
            // Confirm below whether Android removed the exact bits despite the exception.
        }
        int remaining = persistedPermissionFlags(context, treeUri);
        return remaining >= 0 && (remaining & flags) == 0;
    }

    private static int persistedPermissionFlags(Context context, Uri treeUri) {
        int flags = 0;
        try {
            for (UriPermission permission
                    : context.getContentResolver().getPersistedUriPermissions()) {
                if (!treeUri.equals(permission.getUri())) {
                    continue;
                }
                if (permission.isReadPermission()) {
                    flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                }
                if (permission.isWritePermission()) {
                    flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                }
            }
        } catch (RuntimeException ignored) {
            return -1;
        }
        return flags;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static Context applicationContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("A Context is required for MML log storage.");
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            throw new IllegalStateException(
                    "Android did not provide an application Context for log storage.");
        }
        return applicationContext;
    }

    private static IOException asIOException(String prefix, Throwable error) {
        if (error instanceof IOException) {
            IOException input = (IOException) error;
            String detail = input.getMessage();
            return new IOException(
                    detail == null || detail.isBlank()
                            ? prefix : prefix + " " + bounded(detail, 500),
                    input);
        }
        return new IOException(prefix, error);
    }

    private static String bounded(String value, int maximumCodePoints) {
        if (value == null) {
            return "";
        }
        int offset = 0;
        int count = 0;
        while (offset < value.length() && count < maximumCodePoints) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            count++;
        }
        if (offset == value.length()) {
            return value;
        }
        return value.substring(0, offset);
    }

    private static String mirrorReadme() {
        return USER_MANAGED_README_MARKER + "\n"
                + "\n"
                + "These UTF-8 text files are the user-visible MML logs for the directory "
                + "selected in Settings. MML keeps private crash-recovery working state so a "
                + "provider outage, revoked grant or interrupted write cannot destroy the "
                + "active launcher record.\n"
                + "\n"
                + "All generated documents, including recovery copies, end in .txt so Android "
                + "filesystem providers preserve their requested names. Session files use one "
                + "escaped, tab-separated record per line. Backslash-n, "
                + "backslash-r and backslash-t represent embedded line breaks, carriage returns "
                + "and tabs. The files remain ordinary text and can be copied or archived.\n"
                + "\n"
                + "MML reserves one of each generated directory's 8,192 physical rows for a "
                + "pending recovery copy, so at most 8,191 live documents are mirrored there. "
                + "Entry and encoded-index limits are checked before synchronization writes.\n"
                + "\n"
                + "MML updates generated files while this directory remains selected. To edit or "
                + "delete them without a later MML update, first clear the directory field in "
                + "Settings. MML then stops writing here, "
                + "releases its grant, and leaves every existing user-managed file untouched. "
                + "The user is responsible for deleting those files with a file manager.\n";
    }

    private static final class DirectorySnapshot {
        private final Map<String, DocumentEntry> byName;
        private int physicalEntryCount;

        private DirectorySnapshot(Map<String, DocumentEntry> byName, int physicalEntryCount) {
            this.byName = byName;
            this.physicalEntryCount = physicalEntryCount;
        }
    }

    private static final class IndexSnapshot {
        private final boolean valid;
        private final Map<String, String> entries;

        private IndexSnapshot(boolean valid, Map<String, String> entries) {
            this.valid = valid;
            this.entries = entries;
        }

        private static IndexSnapshot invalid() {
            return new IndexSnapshot(false, new LinkedHashMap<>());
        }
    }

    private static final class DocumentEntry {
        private final Uri uri;
        private final String mimeType;
        private long size;

        private DocumentEntry(Uri uri, String mimeType, long size) {
            this.uri = uri;
            this.mimeType = mimeType;
            this.size = size;
        }
    }

    @FunctionalInterface
    private interface DocumentWriter {
        void write(Uri target) throws IOException;
    }

    @FunctionalInterface
    private interface DocumentVerifier {
        void verify(Uri target) throws IOException;
    }

    private enum UserOperationKind {
        NONE,
        DIRECTORY_SELECTION,
        ACTIVATE_USER_FILESPACE,
        SELECT_APP_MANAGED,
        SYNCHRONIZE,
        RECONCILE_GRANTS
    }

}
