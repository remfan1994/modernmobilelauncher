package vip.thatiam.mml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Modern Mobile Launcher: calendar-first HOME with one live process-session chat log. */
public final class MainActivity extends Activity {
    private static final String PREFERENCES = "mml-settings";
    private static final String PREF_CALENDAR = "calendar-system";
    private static final String PREF_FUTURE_NOTIFICATION_ENABLED =
            "future-notification-enabled";
    private static final String PREF_FUTURE_NOTIFICATION_PERMISSION_REQUESTED =
            "future-notification-permission-requested";
    private static final String PREF_FUTURE_NOTIFICATION_TOKEN =
            "future-notification-token";
    private static final String FUTURE_NOTIFICATION_CHANNEL = "mml-future-entries";
    private static final int FUTURE_NOTIFICATION_ID = 1300;
    private static final int REQUEST_POST_NOTIFICATIONS = 1301;
    private static final int REQUEST_LOG_FILESPACE_TREE = 1302;
    private static final String ACTION_OPEN_CALENDAR_FROM_NOTIFICATION =
            "vip.thatiam.mml.action.OPEN_FUTURE_ENTRIES";
    private static final String EXTRA_HANDLED_PROCESS_SESSION =
            "vip.thatiam.mml.extra.HANDLED_PROCESS_SESSION";
    private static final String EXTRA_FUTURE_NOTIFICATION_TOKEN =
            "vip.thatiam.mml.extra.FUTURE_NOTIFICATION_TOKEN";
    private static final String STATE_PROCESS_SESSION = "mml-state-process-session";
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final long MAX_RETENTION_DAYS = 365_000L;
    private static final int LARGE_TEXT_SP = 20;
    private static final float CALENDAR_DATE_TEXT_SCALE = 1.30f;
    private static final int MAX_CURRENT_MACHINE_CODE_POINTS = 180;
    private static final int MAX_CURRENT_NOTIFICATION_CODE_POINTS = 240;
    private static final int FUTURE_NOTIFICATION_BODY_CODE_POINTS = 3_200;
    private static final int CURRENT_SESSION_EVENT_WINDOW = 160;
    private static final int CURRENT_SESSION_ENCODED_BYTE_WINDOW = 512 * 1024;
    private static final long CAPTURE_REFRESH_DEBOUNCE_MILLIS = 100L;
    private static final DateTimeFormatter EVENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss xxx '['VV']'", Locale.ROOT);
    private static final DateTimeFormatter COMPACT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /** Retains the one current-session identity for this Android process. */
    private static String processSessionId;
    private static boolean processAppManagedLogClearInProgress;
    private static Screen processScreen = Screen.CALENDAR;
    private static long processMonthAnchorEpochMillis;
    private static String processComposerDraft = "";
    private static CalendarDateKey processDisplayedDayKey;
    private static String processDisplayedDayTitle;
    private static String processDisplayedTargetLocalDate;
    private static String processDisplayedTargetZoneId;
    private static int processDisplayedSessionIndex;
    private static JournalArchiveCursor processDisplayedArchiveCursor =
            JournalArchiveCursor.first();
    private static boolean processEditingDayComments;
    private static final Map<String, String> processDayCommentDrafts =
            new LinkedHashMap<>(JournalTextPolicy.MAX_DAY_DRAFTS, 0.75f, true);
    private static final Object PROCESS_CALENDAR_INDEX_LOCK = new Object();
    private static volatile List<JournalSessionIndex> processArchivedSessionIndex;
    private static volatile ProjectedCalendarIndex processProjectedCalendarIndex;
    private static long processCalendarIndexEpoch;
    private static boolean processReturnHomeAfterExternalActivity;
    private static Screen processExternalReturnScreen;
    private static AppTargetKey processSelectedAppTargetKey;
    /** Completion consumed by this process; count confirmations remain pending through rotation. */
    private static long processHandledMaintenanceGeneration;
    /** Prevents rotation or notification-tap Activity replacement from replaying a count result. */
    private static long processObservedFutureEntryCountGeneration;
    /** Capture storage applied by an Activity; unfinished refreshes transfer across rotation. */
    private static long processObservedCaptureStorageGeneration;

    private enum Screen {
        CURRENT_SESSION,
        APP_LIST,
        CALENDAR,
        FUTURE_EVENTS,
        DAY,
        SETTINGS
    }

    private final BroadcastReceiver systemBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                closeForShutdown();
            } else if (Intent.ACTION_DATE_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                if (screen == Screen.CALENDAR) {
                    renderCalendar();
                } else if (screen == Screen.FUTURE_EVENTS) {
                    renderFutureEvents();
                }
                requestFutureEntryCountRefresh(
                        false,
                        FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                        futureEntryCount());
            }
        }
    };

    private final BroadcastReceiver notificationCaptureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MmlNotificationListener.ACTION_NOTIFICATION_CAPTURED.equals(
                    intent.getAction())) {
                reconcileCaptureStorageChanges();
            } else if (MmlNotificationListener.ACTION_LISTENER_STATE_CHANGED.equals(
                    intent.getAction())
                    && screen == Screen.SETTINGS
                    && notificationCaptureStatusView != null) {
                notificationCaptureStatusView.setText(notificationCaptureStatus());
            } else if (MmlLogStorage.ACTION_LOG_STORAGE_OPERATION_CHANGED.equals(
                    intent.getAction())
                    && screen == Screen.SETTINGS
                    && !activityDestroyed) {
                refreshMmlSettingsWithoutAuditEvent();
            } else if (MmlJournalMaintenance.ACTION_OPERATION_CHANGED.equals(
                    intent.getAction())
                    && !activityDestroyed) {
                reconcileJournalMaintenance();
            } else if (MmlFutureEntryCount.ACTION_COUNT_CHANGED.equals(intent.getAction())
                    && !activityDestroyed) {
                reconcileFutureEntryCount();
            }
        }
    };

    private final OnBackInvokedCallback backInvokedCallback = this::navigateBack;

    private JournalStore journalStore;
    private JournalSession currentSession;
    private boolean currentSessionEarlierEventsOmitted;
    private int currentSessionWindowEncodedBytes;
    private long bootIdentity;
    private int androidBootCount = -1;
    private EditText messageInput;
    private Button dayCommentShortcutButton;
    private Button dayCommentSaveButton;
    private LinearLayout currentLogContainer;
    private ScrollView currentLogScroll;
    private TextView notificationCaptureStatusView;
    private boolean systemReceiverRegistered;
    private boolean captureReceiverRegistered;
    private boolean hasResumed;
    private boolean restoringProcessScreen;
    private boolean suppressNextFutureNotificationResumeRefresh;
    private boolean brightnessPermissionDialogShowing;
    private Screen screen = Screen.CALENDAR;
    private long monthAnchorEpochMillis;
    private CalendarDateKey displayedDayKey;
    private String displayedDayTitle;
    private String displayedTargetLocalDate;
    private String displayedTargetZoneId;
    private List<JournalSessionIndex> displayedDaySessions = new ArrayList<>();
    private int displayedSessionIndex;
    private JournalArchiveCursor displayedArchiveCursor = JournalArchiveCursor.first();
    private CalendarComment displayedCalendarComment;
    private boolean editingDayComments;
    private List<AppTarget> appTargets = new ArrayList<>();
    private int selectedAppIndex = -1;
    private AppTargetKey lastAppListTapTargetKey;
    private boolean matchingAppListDoubleTap;
    private int pendingAppListDoubleTapPosition = AdapterView.INVALID_POSITION;
    private final ThreadPoolExecutor historyExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread worker = new Thread(runnable, "MML-history");
                worker.setDaemon(true);
                return worker;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ScheduledExecutorService captureRefreshExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread worker = new Thread(runnable, "MML-capture-refresh");
                worker.setDaemon(true);
                return worker;
            });
    private int historyRequestGeneration;
    private Future<?> historyFuture;
    private long displayedMaintenanceConfirmationGeneration;
    private int captureRefreshRequestGeneration;
    private Future<?> captureRefreshFuture;
    private boolean captureClearRefreshPending;
    private long captureRefreshTargetGeneration;
    private boolean activityDestroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
        configureSystemBars();
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        removeRetiredWindowBrightnessPreferences();
        followSystemBrightness();
        createFutureNotificationChannel();
        monthAnchorEpochMillis = processMonthAnchorEpochMillis == 0L
                ? System.currentTimeMillis() : processMonthAnchorEpochMillis;

        long now = System.currentTimeMillis();
        try {
            androidBootCount = Settings.Global.getInt(
                    getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException ignored) {
            androidBootCount = -1;
        }
        bootIdentity = androidBootCount >= 0
                ? androidBootCount
                : derivedBootIdentity(now, SystemClock.elapsedRealtime());

        Intent creationIntent = getIntent();
        boolean existingProcessSession = processSessionId != null;
        boolean firstActivityForProcess = !existingProcessSession;
        boolean restoredSameProcessActivity = existingProcessSession
                && savedInstanceState != null
                && processSessionId.equals(savedInstanceState.getString(STATE_PROCESS_SESSION));
        boolean handledForCurrentProcess = existingProcessSession
                && creationIntent != null
                && processSessionId.equals(creationIntent.getStringExtra(
                        EXTRA_HANDLED_PROCESS_SESSION));
        boolean isFutureNotificationIntent =
                isAuthenticatedFutureNotificationIntent(creationIntent);
        boolean openedFromFutureNotification =
                isFutureNotificationIntent && !handledForCurrentProcess;
        suppressNextFutureNotificationResumeRefresh = isFutureNotificationIntent;
        boolean freshLauncherEntry = existingProcessSession
                && !restoredSameProcessActivity
                && isLauncherEntryIntent(creationIntent)
                && !processSessionId.equals(creationIntent.getStringExtra(
                        EXTRA_HANDLED_PROCESS_SESSION));
        try {
            journalStore = MmlLogStorage.openJournalStore(this);
            if (firstActivityForProcess) {
                currentSession = journalStore.startLauncherSession(
                        bootIdentity, now, currentZoneId(), selectedLogVerbosity());
                currentSessionEarlierEventsOmitted = false;
                processSessionId = currentSession.getId();
                processScreen = Screen.CALENDAR;
                processMonthAnchorEpochMillis = monthAnchorEpochMillis;
            } else {
                reloadCurrentSessionWindow(processSessionId);
            }
            MmlProcessSession.activate(
                    processAppManagedLogClearInProgress ? null : currentSession.getId());
            refreshCapturedNotifications();
        } catch (IOException error) {
            renderFatalStorageError(error);
            return;
        }

        IntentFilter systemFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        systemFilter.addAction(Intent.ACTION_DATE_CHANGED);
        systemFilter.addAction(Intent.ACTION_TIME_CHANGED);
        systemFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(systemBroadcastReceiver, systemFilter, Context.RECEIVER_EXPORTED);
        systemReceiverRegistered = true;
        IntentFilter captureFilter = new IntentFilter(
                MmlNotificationListener.ACTION_NOTIFICATION_CAPTURED);
        captureFilter.addAction(MmlNotificationListener.ACTION_LISTENER_STATE_CHANGED);
        captureFilter.addAction(MmlLogStorage.ACTION_LOG_STORAGE_OPERATION_CHANGED);
        captureFilter.addAction(MmlJournalMaintenance.ACTION_OPERATION_CHANGED);
        captureFilter.addAction(MmlFutureEntryCount.ACTION_COUNT_CHANGED);
        registerReceiver(
                notificationCaptureReceiver, captureFilter, Context.RECEIVER_NOT_EXPORTED);
        captureReceiverRegistered = true;
        markLauncherIntentHandled(creationIntent);
        if (freshLauncherEntry) {
            processReturnHomeAfterExternalActivity = false;
            processExternalReturnScreen = null;
            processScreen = Screen.CALENDAR;
            appendMachineEvent(
                    creationIntent.hasCategory(Intent.CATEGORY_HOME)
                            ? "home_requested" : "launcher_entry_requested",
                    "Android started a fresh MML Activity for a launcher entry request.",
                    false);
        }
        if (openedFromFutureNotification) {
            processReturnHomeAfterExternalActivity = false;
            processExternalReturnScreen = null;
            processScreen = Screen.FUTURE_EVENTS;
            appendMachineEvent(
                    "future_notification_opened",
                    "The future-event notification opened Future events.",
                    false);
        }
        renderRestoredProcessScreen();
        reconcileCaptureStorageChanges();
        reconcileJournalMaintenance();
        if (!isFutureNotificationIntent
                && (firstActivityForProcess || isFutureEntryNotificationActive())) {
            requestFutureEntryCountRefresh(
                    false,
                    firstActivityForProcess
                            ? FutureEntryRefreshPolicy.Mode.ALWAYS
                            : FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                    futureEntryCount());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideStatusBar();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        followSystemBrightness();
        rebindNotificationCaptureIfNeeded();
        if (journalStore == null) {
            return;
        }
        refreshCapturedNotifications();
        reconcileCaptureStorageChanges();
        reconcileJournalMaintenance();
        reconcileFutureEntryCount();
        boolean resumedBefore = hasResumed;
        boolean returningFromExternal = processReturnHomeAfterExternalActivity;
        if ((hasResumed || returningFromExternal) && currentSession != null
                && currentSession.getStatus() == SessionStatus.OPEN) {
            appendMachineEvent("home_resumed", "MML returned to the foreground.", false);
        }
        hasResumed = true;
        if (suppressNextFutureNotificationResumeRefresh) {
            suppressNextFutureNotificationResumeRefresh = false;
        } else if (resumedBefore && isFutureEntryNotificationActive()) {
            // Refresh a still-visible summary after timezone/date changes without resurrecting a
            // summary that the user tapped or dismissed.
            requestFutureEntryCountRefresh(false);
        }
        if (returningFromExternal) {
            Screen returnScreen = processExternalReturnScreen;
            processReturnHomeAfterExternalActivity = false;
            processExternalReturnScreen = null;
            processScreen = returnScreen == null ? Screen.CURRENT_SESSION : returnScreen;
            renderRestoredProcessScreen();
        } else if (screen == Screen.CURRENT_SESSION) {
            refreshCurrentSessionLog();
        } else if (screen == Screen.CALENDAR && resumedBefore) {
            // Refresh today's reverse-video cell after a date or timezone change.
            renderCalendar();
        } else if (screen == Screen.FUTURE_EVENTS && resumedBefore) {
            renderFutureEvents();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean handledForCurrentProcess = processSessionId != null
                && processSessionId.equals(intent.getStringExtra(EXTRA_HANDLED_PROCESS_SESSION));
        if (journalStore != null
                && isAuthenticatedFutureNotificationIntent(intent)
                && !handledForCurrentProcess) {
            suppressNextFutureNotificationResumeRefresh = true;
            appendMachineEvent(
                    "future_notification_opened",
                    "The future-event notification opened Future events.",
                    false);
            processReturnHomeAfterExternalActivity = false;
            processExternalReturnScreen = null;
            renderFutureEvents();
        } else if (journalStore != null && isLauncherEntryIntent(intent)) {
            appendMachineEvent(
                    intent.hasCategory(Intent.CATEGORY_HOME)
                            ? "home_requested" : "launcher_entry_requested",
                    "Android delivered a launcher entry request to MML.",
                    false);
            processReturnHomeAfterExternalActivity = false;
            processExternalReturnScreen = null;
            renderCalendar();
        }
        markLauncherIntentHandled(intent);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                preferences().edit()
                        .putBoolean(PREF_FUTURE_NOTIFICATION_ENABLED, true)
                        .apply();
                requestFutureEntryCountRefresh(
                        false,
                        FutureEntryRefreshPolicy.Mode.ALWAYS,
                        futureEntryCount());
            } else {
                Toast.makeText(
                        this,
                        "Android did not grant notification posting permission. "
                                + "You can retry in Settings.",
                        Toast.LENGTH_LONG).show();
            }
            if (screen == Screen.SETTINGS) {
                openMmlSettings();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOG_FILESPACE_TREE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            MmlLogStorage.cancelDirectorySelection(
                    this,
                    "Directory selection canceled; log storage is unchanged.");
            if (screen == Screen.SETTINGS) {
                refreshMmlSettingsWithoutAuditEvent();
            }
            return;
        }
        Uri treeUri = data.getData();
        int requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        int returnedFlags = data.getFlags() & requiredFlags;
        if (returnedFlags != requiredFlags) {
            MmlLogStorage.cancelDirectorySelection(
                    this,
                    "The selected provider did not grant durable read/write directory access.");
            Toast.makeText(
                    this,
                    "The selected provider did not grant durable read/write directory access.",
                    Toast.LENGTH_LONG).show();
            if (screen == Screen.SETTINGS) {
                refreshMmlSettingsWithoutAuditEvent();
            }
            return;
        }
        MmlJournalMaintenance.Snapshot maintenance = MmlJournalMaintenance.snapshot();
        if (maintenance.isBusy() || maintenance.isCompletionPending()) {
            MmlLogStorage.cancelDirectorySelection(
                    this,
                    "A journal operation is active; log storage was not changed.");
            Toast.makeText(
                    this,
                    "Finish the current journal operation before changing log storage.",
                    Toast.LENGTH_LONG).show();
            reconcileJournalMaintenance();
            return;
        }
        IOException failure = MmlLogStorage.activateUserFilespace(
                this, treeUri, returnedFlags);
        Toast.makeText(
                this,
                failure == null
                        ? "Preparing the user-managed log directory in the background..."
                        : "The user-managed log directory was not changed: "
                                + failure.getMessage(),
                Toast.LENGTH_LONG).show();
        if (screen == Screen.SETTINGS) {
            refreshMmlSettingsWithoutAuditEvent();
        }
    }

    private static boolean isLauncherEntryIntent(Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && (intent.hasCategory(Intent.CATEGORY_HOME)
                || intent.hasCategory(Intent.CATEGORY_LAUNCHER));
    }

    private boolean isAuthenticatedFutureNotificationIntent(Intent intent) {
        if (intent == null
                || !ACTION_OPEN_CALENDAR_FROM_NOTIFICATION.equals(intent.getAction())) {
            return false;
        }
        String expected = preferences().getString(PREF_FUTURE_NOTIFICATION_TOKEN, "");
        String received = intent.getStringExtra(EXTRA_FUTURE_NOTIFICATION_TOKEN);
        return !expected.isEmpty() && expected.equals(received);
    }

    private void markLauncherIntentHandled(Intent intent) {
        if (intent != null && processSessionId != null) {
            intent.putExtra(EXTRA_HANDLED_PROCESS_SESSION, processSessionId);
            setIntent(intent);
        }
    }

    @Override
    protected void onPause() {
        preserveComposerDraft();
        processScreen = screen;
        processMonthAnchorEpochMillis = monthAnchorEpochMillis;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        preserveComposerDraft();
        if (processSessionId != null) {
            outState.putString(STATE_PROCESS_SESSION, processSessionId);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        historyRequestGeneration++;
        if (historyFuture != null) {
            historyFuture.cancel(true);
            historyFuture = null;
        }
        historyExecutor.getQueue().clear();
        historyExecutor.purge();
        captureRefreshRequestGeneration++;
        historyExecutor.shutdownNow();
        captureRefreshExecutor.shutdownNow();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        if (systemReceiverRegistered) {
            unregisterReceiver(systemBroadcastReceiver);
            systemReceiverRegistered = false;
        }
        if (captureReceiverRegistered) {
            unregisterReceiver(notificationCaptureReceiver);
            captureReceiverRegistered = false;
        }
        releaseRenderedScreenReferences();
        super.onDestroy();
    }

    private void navigateBack() {
        if (screen == Screen.DAY || screen == Screen.FUTURE_EVENTS) {
            renderCalendar();
        } else if (screen == Screen.CURRENT_SESSION) {
            appendMachineEvent("panel_opened", "Calendar view opened.", false);
            renderCalendar();
        } else if (screen == Screen.CALENDAR) {
            // Calendar is the HOME root; Back must not finish the launcher activity.
            renderCalendar();
        } else {
            renderCurrentSession();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                navigateBack();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU
                    && screen == Screen.CURRENT_SESSION) {
                openMmlSettings();
                return true;
            }
            if (screen == Screen.CALENDAR
                    && event.getKeyCode() == KeyEvent.KEYCODE_PAGE_UP) {
                shiftCalendarMonth(-1);
                return true;
            }
            if (screen == Screen.CALENDAR
                    && event.getKeyCode() == KeyEvent.KEYCODE_PAGE_DOWN) {
                shiftCalendarMonth(1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void renderRestoredProcessScreen() {
        restoringProcessScreen = true;
        try {
            if (processScreen == Screen.CURRENT_SESSION) {
                renderCurrentSession();
            } else if (processScreen == Screen.APP_LIST) {
                openAppList();
            } else if (processScreen == Screen.SETTINGS) {
                openMmlSettings();
            } else if (processScreen == Screen.FUTURE_EVENTS) {
                renderFutureEvents();
            } else if (processScreen == Screen.DAY
                    && processDisplayedDayKey != null
                    && processDisplayedTargetLocalDate != null
                    && processDisplayedTargetZoneId != null) {
                renderDay(
                        processDisplayedDayKey,
                        processDisplayedDayTitle,
                        processDisplayedTargetLocalDate,
                        processDisplayedTargetZoneId,
                        null,
                        processDisplayedSessionIndex,
                        processEditingDayComments,
                        processDisplayedArchiveCursor);
            } else {
                renderCalendar();
            }
        } finally {
            restoringProcessScreen = false;
        }
    }

    private void setScreen(Screen next) {
        historyRequestGeneration++;
        if (historyFuture != null) {
            historyFuture.cancel(true);
            historyFuture = null;
        }
        historyExecutor.getQueue().clear();
        historyExecutor.purge();
        releaseRenderedScreenReferences();
        screen = next;
        processScreen = next;
    }

    /** Replaces stale history work and interrupts the running scan before admitting the latest. */
    private void submitLatestHistoryTask(Runnable task) {
        if (historyFuture != null) {
            historyFuture.cancel(true);
        }
        historyExecutor.getQueue().clear();
        historyExecutor.purge();
        try {
            historyFuture = historyExecutor.submit(task);
        } catch (RejectedExecutionException ignored) {
            historyFuture = null;
            // Activity destruction deliberately rejects stale history work.
        }
    }

    /** Drops detached view trees and screen data before replacing the Activity content view. */
    private void releaseRenderedScreenReferences() {
        messageInput = null;
        dayCommentShortcutButton = null;
        dayCommentSaveButton = null;
        currentLogContainer = null;
        currentLogScroll = null;
        notificationCaptureStatusView = null;
        displayedDaySessions = new ArrayList<>();
        displayedCalendarComment = null;
        appTargets = new ArrayList<>();
        selectedAppIndex = -1;
        resetAppListTapSequence();
    }

    private void renderCurrentSession() {
        preserveComposerDraft();
        setScreen(Screen.CURRENT_SESSION);
        ensureWritableCurrentJournal();

        LinearLayout root = verticalRoot();
        LinearLayout transcriptPage = new LinearLayout(this);
        transcriptPage.setOrientation(LinearLayout.VERTICAL);
        TextView header = textView(currentSessionHeader(), 24);
        header.setPadding(dp(4), dp(4), dp(4), dp(10));
        transcriptPage.addView(header, matchWrap());

        currentLogContainer = new LinearLayout(this);
        currentLogContainer.setOrientation(LinearLayout.VERTICAL);
        currentLogContainer.setPadding(dp(10), dp(8), dp(10), dp(8));
        currentLogContainer.setBackgroundResource(R.color.mml_surface);
        populateCurrentLog(currentLogContainer, currentSession);

        currentLogScroll = new ScrollView(this);
        currentLogScroll.setFillViewport(true);
        transcriptPage.addView(currentLogContainer, matchWrap());
        currentLogScroll.addView(transcriptPage, matchWrap());
        LinearLayout.LayoutParams scrollParameters = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(currentLogScroll, scrollParameters);

        Button launcher = makeButton("Launcher", this::openAppList);
        launcher.setContentDescription("Open app list");
        launcher.setFocusableInTouchMode(true);
        Button send = makeButton("Send", this::sendCurrentMessage);

        messageInput = new EditText(this);
        messageInput.setTextSize(LARGE_TEXT_SP);
        messageInput.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setMinLines(currentSessionComposerMinimumLines());
        messageInput.setMaxLines(composerMaximumLines());
        messageInput.setSingleLine(false);
        messageInput.setHorizontallyScrolling(false);
        messageInput.setVerticalScrollBarEnabled(true);
        messageInput.setHint("Write a Comment");
        messageInput.setContentDescription("Current Session Comment");
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        messageInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(
                JournalTextPolicy.MAX_INPUT_UTF16_UNITS)});
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });
        messageInput.setText(processComposerDraft);
        messageInput.setSelection(messageInput.length());

        addCurrentSessionComposer(root, messageInput, launcher, send);

        setContentView(root);
        launcher.requestFocus();
        scrollCurrentLogToEnd();
    }

    private void refreshCurrentSessionLog() {
        if (screen != Screen.CURRENT_SESSION || currentSession == null) {
            return;
        }
        try {
            reloadCurrentSessionWindow(currentSession.getId());
        } catch (IOException error) {
            showStorageError(error);
            return;
        }
        if (currentLogContainer != null) {
            populateCurrentLog(currentLogContainer, currentSession);
            scrollCurrentLogToEnd();
        }
    }

    private void sendCurrentMessage() {
        if (messageInput == null) {
            return;
        }
        String message = messageInput.getText().toString();
        if (message.trim().isEmpty()) {
            messageInput.setError("Type a Comment before sending.");
            return;
        }
        String limitError = JournalTextPolicy.validationError(message);
        if (limitError != null) {
            messageInput.setError(limitError);
            messageInput.requestFocus();
            return;
        }
        if (appendMachineEvent("comment", message, true)) {
            processComposerDraft = "";
            messageInput.setText("");
        }
    }

    private void scrollCurrentLogToEnd() {
        ScrollView scroll = currentLogScroll;
        if (scroll != null) {
            scroll.post(() -> {
                if (scroll != currentLogScroll) {
                    return;
                }
                View content = scroll.getChildAt(0);
                if (content != null) {
                    scroll.scrollTo(
                            0,
                            Math.max(0, content.getHeight() - scroll.getHeight()));
                }
            });
        }
    }

    private void preserveComposerDraft() {
        if (messageInput != null) {
            if (screen == Screen.CURRENT_SESSION) {
                processComposerDraft = boundedDraftText(messageInput.getText().toString());
            } else if (screen == Screen.DAY && displayedTargetLocalDate != null) {
                String key = dayDraftKey(displayedTargetLocalDate, displayedTargetZoneId);
                rememberDayCommentDraft(key, messageInput.getText().toString());
            }
        }
    }

    private static String boundedDraftText(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= JournalTextPolicy.MAX_INPUT_UTF16_UNITS) {
            return text;
        }
        int end = JournalTextPolicy.MAX_INPUT_UTF16_UNITS;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static void rememberDayCommentDraft(String key, String value) {
        String draft = boundedDraftText(value);
        if (draft.isEmpty()) {
            processDayCommentDrafts.remove(key);
            return;
        }
        processDayCommentDrafts.put(key, draft);
        while (processDayCommentDrafts.size() > JournalTextPolicy.MAX_DAY_DRAFTS) {
            String eldest = processDayCommentDrafts.keySet().iterator().next();
            processDayCommentDrafts.remove(eldest);
        }
    }

    private void openAppList() {
        preserveComposerDraft();
        if (!restoringProcessScreen) {
            appendMachineEvent("panel_opened", "App list opened.", false);
        }
        setScreen(Screen.APP_LIST);
        resetAppListTapSequence();

        LinearLayout root = verticalRoot();
        appTargets = loadAppTargets();
        List<String> labels = new ArrayList<>();
        for (AppTarget target : appTargets) {
            labels.add(target.label + "\n" + target.component.flattenToShortString());
        }
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setLongClickable(false);
        root.addView(textView("App list", 27), matchWrap());
        root.addView(makeButton("MML preferences", this::openMmlSettings), matchWrap());
        if (appTargets.isEmpty()) {
            root.addView(textView(
                    "No launchable applications were returned by Android.", 20), matchWrap());
        }

        list.setAdapter(new LargeTextAdapter(this, labels, true));
        selectedAppIndex = resolveSelectedAppIndex();
        if (selectedAppIndex >= 0) {
            selectAppTarget(list, selectedAppIndex);
        }
        GestureDetector appListGestures = appListGestureDetector(list);
        list.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_CANCEL) {
                resetAppListTapSequence();
            }
            appListGestures.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP
                    && pendingAppListDoubleTapPosition != AdapterView.INVALID_POSITION) {
                int launchPosition = pendingAppListDoubleTapPosition;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                list.onTouchEvent(cancel);
                cancel.recycle();
                resetAppListTapSequence();
                selectAppTarget(list, launchPosition);
                launchSelectedApp();
                return true;
            }
            return false;
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= appTargets.size()) {
                return;
            }
            handleAppListTap(list, position);
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < appTargets.size()) {
                    if (!list.isInTouchMode()) {
                        resetAppListTapSequence();
                    }
                    selectAppTarget(list, position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        list.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                int selectedPosition = list.getSelectedItemPosition();
                if (selectedPosition >= 0 && selectedPosition < appTargets.size()) {
                    selectAppTarget(list, selectedPosition);
                }
                launchSelectedApp();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams listParameters = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(list, listParameters);
        root.addView(twoButtonRow(
                "Launch", this::launchSelectedApp,
                "App settings", this::openSelectedAppDetails), matchWrap());
        setContentView(root);
        if (selectedAppIndex >= 0) {
            list.setSelection(selectedAppIndex);
        }
        list.requestFocus();
    }

    private int resolveSelectedAppIndex() {
        if (processSelectedAppTargetKey != null) {
            for (int index = 0; index < appTargets.size(); index++) {
                if (processSelectedAppTargetKey.equals(appTargetKey(appTargets.get(index)))) {
                    return index;
                }
            }
        }
        return appTargets.isEmpty() ? -1 : 0;
    }

    private void selectAppTarget(ListView list, int position) {
        if (position < 0 || position >= appTargets.size()) {
            return;
        }
        selectedAppIndex = position;
        processSelectedAppTargetKey = appTargetKey(appTargets.get(position));
        list.setItemChecked(position, true);
    }

    private void handleAppListTap(ListView list, int position) {
        selectAppTarget(list, position);
        lastAppListTapTargetKey = appTargetKey(appTargets.get(position));
    }

    private GestureDetector appListGestureDetector(ListView list) {
        int doubleTapTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        return new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private float secondTapDownX;
            private float secondTapDownY;

            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                int position = list.pointToPosition((int) event.getX(), (int) event.getY());
                if (position >= 0 && position < appTargets.size()) {
                    lastAppListTapTargetKey = appTargetKey(appTargets.get(position));
                } else {
                    resetAppListTapSequence();
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                // Android reports the first DOWN here; the second DOWN arrives below.
                matchingAppListDoubleTap = lastAppListTapTargetKey != null;
                return matchingAppListDoubleTap;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    secondTapDownX = event.getX();
                    secondTapDownY = event.getY();
                    int position = list.pointToPosition(
                            (int) event.getX(), (int) event.getY());
                    matchingAppListDoubleTap = matchingAppListDoubleTap
                            && position >= 0
                            && position < appTargets.size()
                            && appTargetKey(appTargets.get(position))
                                    .equals(lastAppListTapTargetKey);
                    if (!matchingAppListDoubleTap) {
                        resetAppListTapSequence();
                    }
                    return matchingAppListDoubleTap;
                }
                if (!matchingAppListDoubleTap) {
                    return false;
                }
                float horizontalMovement = event.getX() - secondTapDownX;
                float verticalMovement = event.getY() - secondTapDownY;
                if (horizontalMovement * horizontalMovement
                        + verticalMovement * verticalMovement
                        > doubleTapTouchSlop * doubleTapTouchSlop) {
                    resetAppListTapSequence();
                    return false;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    resetAppListTapSequence();
                    return false;
                }
                if (action != MotionEvent.ACTION_UP) {
                    return true;
                }
                int position = list.pointToPosition((int) event.getX(), (int) event.getY());
                if (position < 0 || position >= appTargets.size()
                        || !appTargetKey(appTargets.get(position))
                                .equals(lastAppListTapTargetKey)) {
                    resetAppListTapSequence();
                    return false;
                }
                pendingAppListDoubleTapPosition = position;
                return true;
            }
        });
    }

    private void resetAppListTapSequence() {
        lastAppListTapTargetKey = null;
        matchingAppListDoubleTap = false;
        pendingAppListDoubleTapPosition = AdapterView.INVALID_POSITION;
    }

    private static AppTargetKey appTargetKey(AppTarget target) {
        return new AppTargetKey(target.user, target.component);
    }

    private List<AppTarget> loadAppTargets() {
        LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        Map<AppTargetKey, AppTarget> uniqueTargets = new LinkedHashMap<>();
        try {
            List<UserHandle> profiles = launcherApps.getProfiles();
            for (UserHandle profile : profiles) {
                for (LauncherActivityInfo info : launcherApps.getActivityList(null, profile)) {
                    ComponentName component = info.getComponentName();
                    if (getPackageName().equals(component.getPackageName())) {
                        continue;
                    }
                    AppTargetKey key = new AppTargetKey(profile, component);
                    uniqueTargets.put(key, new AppTarget(
                            info.getLabel().toString(), component, profile));
                }
            }
        } catch (RuntimeException error) {
            Toast.makeText(this, "Android did not provide the application list.", Toast.LENGTH_LONG).show();
        }
        List<AppTarget> result = new ArrayList<>(uniqueTargets.values());
        result.sort(Comparator
                .comparing((AppTarget target) -> target.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(target -> target.component.flattenToString()));
        return result;
    }

    private void launchSelectedApp() {
        resetAppListTapSequence();
        if (selectedAppIndex < 0 || selectedAppIndex >= appTargets.size()) {
            Toast.makeText(this, "Select an application first.", Toast.LENGTH_SHORT).show();
            return;
        }
        AppTarget target = appTargets.get(selectedAppIndex);
        String detail = target.label + " | " + target.component.flattenToShortString();
        appendMachineEvent("launch_requested", detail, false);
        LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            launcherApps.startMainActivity(target.component, target.user, null, null);
            processReturnHomeAfterExternalActivity = true;
            processExternalReturnScreen = Screen.CURRENT_SESSION;
        } catch (RuntimeException error) {
            appendMachineEvent(
                    "launch_failed",
                    detail + " | " + error.getClass().getSimpleName(),
                    true);
            Toast.makeText(this, "Android did not launch the selected application.", Toast.LENGTH_LONG).show();
        }
    }

    private void openSelectedAppDetails() {
        if (selectedAppIndex < 0 || selectedAppIndex >= appTargets.size()) {
            Toast.makeText(this, "Select an application first.", Toast.LENGTH_SHORT).show();
            return;
        }
        AppTarget target = appTargets.get(selectedAppIndex);
        String detail = target.label + " | " + target.component.flattenToShortString();
        appendMachineEvent("app_details_requested", detail, true);
        LauncherApps launcherApps =
                (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            if (launcherApps == null) {
                throw new IllegalStateException("LauncherApps unavailable");
            }
            launcherApps.startAppDetailsActivity(target.component, target.user, null, null);
            processReturnHomeAfterExternalActivity = true;
            processExternalReturnScreen = Screen.APP_LIST;
        } catch (RuntimeException error) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + target.component.getPackageName()));
            if (!tryOpenSystemIntent(fallback, "Android application settings", false)) {
                appendMachineEvent(
                        "launch_failed",
                        detail + " | app details | " + error.getClass().getSimpleName(),
                        true);
                Toast.makeText(
                        this,
                        "Android did not open this application's settings.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openFutureEvents() {
        if (!restoringProcessScreen) {
            appendMachineEvent("panel_opened", "Future events opened.", false);
        }
        renderFutureEvents();
    }

    private void renderFutureEvents() {
        preserveComposerDraft();
        setScreen(Screen.FUTURE_EVENTS);
        renderFutureEventsLoading();
        long requestedNow = System.currentTimeMillis();
        int requestGeneration = historyRequestGeneration;
        submitLatestHistoryTask(() -> {
            List<CalendarComment> loaded = Collections.emptyList();
            IOException failure = null;
            try {
                loaded = journalStore.listFutureCalendarComments(requestedNow);
            } catch (IOException error) {
                failure = error;
            }
            List<CalendarComment> result = loaded;
            IOException error = failure;
            runOnUiThread(() -> {
                if (activityDestroyed
                        || isDestroyed()
                        || screen != Screen.FUTURE_EVENTS
                        || requestGeneration != historyRequestGeneration) {
                    return;
                }
                if (error != null) {
                    showStorageError(error);
                    renderFutureEventsLoadFailure();
                } else {
                    renderFutureEventsPage(result);
                }
            });
        });
    }

    private void renderFutureEventsLoading() {
        LinearLayout root = verticalRoot();
        root.addView(textView("Future events", 27), matchWrap());
        TextView loading = textView("Loading future events...", 24);
        loading.setGravity(Gravity.CENTER);
        root.addView(loading, matchWeightedHeight());
        root.addView(makeButton("Calendar", this::renderCalendar), matchWrap());
        setContentView(root);
        root.requestFocus();
    }

    private void renderFutureEventsLoadFailure() {
        LinearLayout root = verticalRoot();
        root.addView(textView("Future events are unavailable.", 24), matchWeightedHeight());
        root.addView(twoButtonRow(
                "Retry", this::renderFutureEvents,
                "Calendar", this::renderCalendar), matchWrap());
        setContentView(root);
        root.requestFocus();
    }

    private void renderFutureEventsPage(List<CalendarComment> futureEvents) {
        LinearLayout root = verticalRoot();
        ListView eventList = null;
        root.addView(textView("Future events", 27), matchWrap());
        if (futureEvents.isEmpty()) {
            TextView empty = textView("No future events.", 24);
            empty.setGravity(Gravity.CENTER);
            root.addView(empty, matchWeightedHeight());
        } else {
            ListView list = new ListView(this);
            eventList = list;
            list.setAdapter(new FutureEventAdapter(this, futureEvents));
            list.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < futureEvents.size()) {
                    openFutureEvent(futureEvents.get(position));
                }
            });
            LinearLayout.LayoutParams listParameters = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(list, listParameters);
        }
        root.addView(makeButton("Calendar", this::renderCalendar), matchWrap());
        setContentView(root);
        if (eventList != null) {
            eventList.requestFocus();
        } else {
            root.requestFocus();
        }
    }

    private void openFutureEvent(CalendarComment event) {
        try {
            CalendarDateKey key = CalendarProjection.dateKeyForGregorianLocalDate(
                    selectedCalendarSystem(),
                    event.getTargetLocalDate(),
                    event.getTargetZoneId());
            appendMachineEvent(
                    "future_event_opened",
                    event.getTargetLocalDate() + " | " + event.getTargetZoneId(),
                    false);
            renderDay(
                    key,
                    FutureEntryPolicy.longDateLabel(event),
                    event.getTargetLocalDate(),
                    event.getTargetZoneId(),
                    null,
                    0,
                    false);
        } catch (RuntimeException error) {
            Toast.makeText(
                    this,
                    "This future event has an unreadable date or timezone.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout calendarHomeActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button futureEvents = makeButton("Future events", this::openFutureEvents);
        futureEvents.setContentDescription("Open future events");
        Button currentSession = makeButton("Current Session", this::renderCurrentSession);
        currentSession.setContentDescription("Open Current Session");
        row.addView(futureEvents, weightedWrap(1f));
        row.addView(currentSession, weightedWrap(2f));
        return row;
    }

    private void renderCalendar() {
        preserveComposerDraft();
        setScreen(Screen.CALENDAR);
        renderCalendarLoading();
        CalendarSystem system = selectedCalendarSystem();
        String zoneId = currentZoneId();
        long requestedMonthAnchor = monthAnchorEpochMillis;
        int requestGeneration = historyRequestGeneration;
        submitLatestHistoryTask(() -> {
                CalendarPageData loaded = null;
                IOException failure = null;
                try {
                    loaded = loadCalendarPage(system, requestedMonthAnchor, zoneId);
                } catch (IOException error) {
                    failure = error;
                }
                CalendarPageData result = loaded;
                IOException error = failure;
                runOnUiThread(() -> {
                    if (activityDestroyed
                            || isDestroyed()
                            || screen != Screen.CALENDAR
                            || requestGeneration != historyRequestGeneration
                            || monthAnchorEpochMillis != requestedMonthAnchor
                            || selectedCalendarSystem() != system) {
                        return;
                    }
                    if (error != null) {
                        showStorageError(error);
                        renderCalendarLoadFailure();
                    } else {
                        renderCalendarPage(result);
                    }
                });
        });
    }

    private void renderCalendarLoading() {
        LinearLayout root = verticalRoot();
        TextView loading = textView("Loading calendar history...", 24);
        loading.setGravity(Gravity.CENTER);
        root.addView(loading, weightedWrap());
        root.addView(calendarHomeActions(), matchWrap());
        setCalendarContentView(root);
        root.requestFocus();
    }

    private void renderCalendarLoadFailure() {
        LinearLayout root = verticalRoot();
        root.addView(textView("Calendar history is unavailable.", 24), weightedWrap());
        root.addView(makeButton("Retry", this::renderCalendar), matchWrap());
        root.addView(calendarHomeActions(), matchWrap());
        setCalendarContentView(root);
        root.requestFocus();
    }

    private CalendarPageData loadCalendarPage(
            CalendarSystem system,
            long requestedMonthAnchor,
            String zoneId) throws IOException {
        CalendarProjection.MonthGrid month = CalendarProjection.monthGrid(
                system, requestedMonthAnchor, zoneId);
        Set<CalendarDateKey> monthKeys = new HashSet<>();
        for (CalendarProjection.DayCell day : month.getDays()) {
            monthKeys.add(day.getKey());
        }
        Map<CalendarDateKey, List<JournalSessionIndex>> sessionsByDate = new HashMap<>();
        Map<CalendarDateKey, List<JournalSessionIndex>> projectedSessions =
                projectedCalendarIndex(system).sessionsByDate;
        for (CalendarDateKey key : monthKeys) {
            List<JournalSessionIndex> sessions = projectedSessions.get(key);
            if (sessions != null) {
                sessionsByDate.put(key, sessions);
            }
        }
        Map<CalendarDateKey, CalendarComment> commentPages = new HashMap<>();
        for (CalendarComment comment : journalStore.listCalendarComments()) {
            try {
                CalendarDateKey key = CalendarProjection.dateKeyForGregorianLocalDate(
                        system,
                        comment.getTargetLocalDate(),
                        comment.getTargetZoneId());
                commentPages.putIfAbsent(key, comment);
            } catch (RuntimeException ignored) {
                // A damaged timezone must not prevent the remaining calendar from rendering.
            }
        }
        return new CalendarPageData(
                month,
                sessionsByDate,
                commentPages,
                CalendarProjection.dateKey(system, System.currentTimeMillis(), zoneId),
                zoneId);
    }

    private void renderCalendarPage(CalendarPageData data) {
        CalendarProjection.MonthGrid month = data.month;

        LinearLayout root = verticalRoot();
        int calendarVerticalPadding = isLandscapeLayout() ? 2 : 4;
        root.setPadding(
                dp(3),
                dp(calendarVerticalPadding),
                dp(3),
                dp(calendarVerticalPadding));
        LinearLayout calendarPage = new LinearLayout(this);
        calendarPage.setOrientation(LinearLayout.VERTICAL);

        boolean accessibleList = MmlPresentationPolicy.useCalendarDayList(
                getResources().getConfiguration().fontScale,
                getResources().getConfiguration().screenWidthDp);
        View calendarContents;
        if (accessibleList) {
            LinearLayout dayList = new LinearLayout(this);
            dayList.setOrientation(LinearLayout.VERTICAL);
            for (CalendarProjection.DayCell day : month.getDays()) {
                List<JournalSessionIndex> daySessions = data.sessionsByDate.getOrDefault(
                        day.getKey(), Collections.emptyList());
                int closedCount = daySessions.size();
                CalendarComment comment = data.commentPages.get(day.getKey());
                int totalCount = closedCount + (comment == null ? 0 : 1);
                boolean today = day.getKey().equals(data.todayKey);
                Button button = makeCalendarDayButton(
                        day, totalCount, closedCount, comment, today,
                        month, daySessions, data.zoneId);
                setCalendarDayText(
                        button,
                        (today ? "Today - " : "Day ") + day.getDisplayedDay(),
                        (comment == null ? "no Comments" : "Comments saved")
                                + ", " + closedCount
                                + (closedCount == 1
                                ? " closed session" : " closed sessions"),
                        today);
                dayList.addView(button, matchWrap());
            }
            calendarContents = dayList;
        } else {
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(7);
            String[] weekdayNames = {"Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"};
            for (String weekday : weekdayNames) {
                TextView heading = textView(weekday, 20);
                heading.setGravity(Gravity.CENTER);
                addGridCell(grid, heading);
            }
            for (int blank = 0; blank < month.getLeadingBlankCount(); blank++) {
                addGridCell(grid, textView("", 20));
            }
            for (CalendarProjection.DayCell day : month.getDays()) {
                List<JournalSessionIndex> daySessions = data.sessionsByDate.getOrDefault(
                        day.getKey(), Collections.emptyList());
                int closedCount = daySessions.size();
                CalendarComment comment = data.commentPages.get(day.getKey());
                int totalCount = closedCount + (comment == null ? 0 : 1);
                boolean today = day.getKey().equals(data.todayKey);
                Button button = makeCalendarDayButton(
                        day, totalCount, closedCount, comment, today,
                        month, daySessions, data.zoneId);
                setCalendarDayText(
                        button,
                        Integer.toString(day.getDisplayedDay()),
                        displayCalendarCount(totalCount),
                        today);
                button.setMinWidth(0);
                button.setMinimumWidth(0);
                button.setPadding(dp(1), dp(2), dp(1), dp(2));
                int minimumDayHeight = dp(isLandscapeLayout() ? 56 : 64);
                button.setMinHeight(minimumDayHeight);
                button.setMinimumHeight(minimumDayHeight);
                addGridCell(grid, button);
            }
            calendarContents = grid;
        }
        calendarPage.addView(calendarMonthControl(month.getTitle()), matchWrap());
        calendarPage.addView(calendarContents, matchWrap());
        ScrollView calendarScroll = new ScrollView(this);
        calendarScroll.addView(calendarPage, matchWrap());
        LinearLayout.LayoutParams calendarParameters = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(calendarScroll, calendarParameters);
        // Add the new text notice between calendar and buttons
        TextView notice = textView("Not vegetarian? www.bloodguiltcurse.net will change your mind", 14);
        notice.setPadding(dp(8), dp(4), dp(8), dp(4));
        notice.setGravity(Gravity.CENTER);
        notice.setSingleLine(false);
        notice.setMaxLines(3); // Allow wrapping to multiple lines
        root.addView(notice, matchWrap());
        root.addView(calendarHomeActions(), matchWrap());
        setCalendarContentView(root);
        root.requestFocus();
    }

    private View calendarMonthControl(String monthTitle) {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        Button previous = makeButton("Previous", () -> shiftCalendarMonth(-1));
        previous.setContentDescription("Previous month");
        Button next = makeButton("Next", () -> shiftCalendarMonth(1));
        next.setContentDescription("Next month");
        TextView title = textView(monthTitle, 24);
        title.setGravity(Gravity.CENTER);
        title.setContentDescription("Displayed month: " + monthTitle);

        controls.addView(previous, wrapWrap());
        controls.addView(title, weightedWrap());
        controls.addView(next, wrapWrap());
        return controls;
    }

    private Button makeCalendarDayButton(
            CalendarProjection.DayCell day,
            int totalCount,
            int closedCount,
            CalendarComment comment,
            boolean today,
            CalendarProjection.MonthGrid month,
            List<JournalSessionIndex> sessions,
            String zoneId) {
        Button button = makeButton("", () -> {
            String targetLocalDate = comment == null
                    ? CalendarProjection.gregorianLocalDate(
                            day.getRepresentativeEpochMillis(), zoneId)
                    : comment.getTargetLocalDate();
            String targetZoneId = comment == null ? zoneId : comment.getTargetZoneId();
            String dayTitle = month.getTitle() + " day " + day.getDisplayedDay();
            renderDay(
                    day.getKey(), dayTitle, targetLocalDate, targetZoneId,
                    sessions, 0, comment == null);
        });
        button.setTextSize(LARGE_TEXT_SP);
        button.setContentDescription(
                (today ? "Today, " : "") + "day " + day.getDisplayedDay()
                        + ", " + (comment == null ? "no saved Comments" : "Comments saved")
                        + ", " + closedCount + " closed sessions, "
                        + totalCount + " total entries");
        if (today) {
            button.setBackgroundTintList(ColorStateList.valueOf(
                    getColor(R.color.mml_today_background)));
            button.setTextColor(getColor(R.color.mml_today_text));
        }
        return button;
    }

    private void setCalendarDayText(
            Button button,
            String dateText,
            String entryTotalText,
            boolean today) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        text.append(dateText);
        int entryTotalStart = text.length() + 1;
        text.append('\n').append(entryTotalText);
        int dateColor = today
                ? R.color.mml_today_date_text : R.color.mml_calendar_date_text;
        int entryTotalColor = today
                ? R.color.mml_today_entry_total_text
                : R.color.mml_calendar_entry_total_text;
        text.setSpan(
                new ForegroundColorSpan(getColor(dateColor)),
                0,
                dateText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(
                new RelativeSizeSpan(CALENDAR_DATE_TEXT_SCALE),
                0,
                dateText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(
                new ForegroundColorSpan(getColor(entryTotalColor)),
                entryTotalStart,
                text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text, TextView.BufferType.SPANNABLE);
    }

    private static String displayCalendarCount(int count) {
        return count > 99 ? "99+" : Integer.toString(count);
    }

    private void renderDay(
            CalendarDateKey key,
            String dayTitle,
            String targetLocalDate,
            String targetZoneId,
            List<JournalSessionIndex> sessions,
            int index,
            boolean editComments) {
        renderDay(
                key,
                dayTitle,
                targetLocalDate,
                targetZoneId,
                sessions,
                index,
                editComments,
                JournalArchiveCursor.first());
    }

    private void renderDay(
            CalendarDateKey key,
            String dayTitle,
            String targetLocalDate,
            String targetZoneId,
            List<JournalSessionIndex> sessions,
            int index,
            boolean editComments,
            JournalArchiveCursor archiveCursor) {
        preserveComposerDraft();
        setScreen(Screen.DAY);
        displayedDayKey = key;
        displayedDayTitle = dayTitle;
        displayedTargetLocalDate = targetLocalDate;
        displayedTargetZoneId = targetZoneId;
        displayedDaySessions = sessions == null
                ? new ArrayList<>() : new ArrayList<>(sessions);
        displayedSessionIndex = displayedDaySessions.isEmpty()
                ? 0 : Math.max(0, Math.min(index, displayedDaySessions.size() - 1));
        displayedArchiveCursor = archiveCursor == null
                ? JournalArchiveCursor.first() : archiveCursor;
        processDisplayedDayKey = key;
        processDisplayedDayTitle = dayTitle;
        processDisplayedTargetLocalDate = targetLocalDate;
        processDisplayedTargetZoneId = targetZoneId;
        processDisplayedSessionIndex = displayedSessionIndex;
        processDisplayedArchiveCursor = displayedArchiveCursor;
        processEditingDayComments = editComments;

        renderDayLoading(dayTitle);
        CalendarSystem system = selectedCalendarSystem();
        List<JournalSessionIndex> suppliedSessions = sessions == null
                ? null : new ArrayList<>(sessions);
        JournalArchiveCursor requestedArchiveCursor = displayedArchiveCursor;
        int requestGeneration = historyRequestGeneration;
        submitLatestHistoryTask(() -> {
                List<JournalSessionIndex> matching = suppliedSessions;
                String transcript = null;
                JournalArchivePage archivePage = null;
                CalendarComment calendarComment = null;
                IOException failure = null;
                try {
                    calendarComment = journalStore.findCalendarComment(targetLocalDate);
                    if (matching == null) {
                        matching = new ArrayList<>(projectedCalendarIndex(system)
                                .sessionsByDate.getOrDefault(key, Collections.emptyList()));
                    }
                    int selectedIndex = matching.isEmpty()
                            ? 0 : Math.max(0, Math.min(index, matching.size() - 1));
                    if (!matching.isEmpty()) {
                        archivePage = journalStore.loadArchivedSessionPage(
                                matching.get(selectedIndex).getId(),
                                requestedArchiveCursor);
                        transcript = archivedJournalText(archivePage);
                    }
                } catch (IOException error) {
                    failure = error;
                    if (matching == null) {
                        matching = new ArrayList<>();
                    }
                }
                int selectedIndex = matching.isEmpty()
                        ? 0 : Math.max(0, Math.min(index, matching.size() - 1));
                DayPageData result = new DayPageData(
                        matching,
                        selectedIndex,
                        transcript,
                        archivePage,
                        calendarComment,
                        failure,
                        editComments);
                runOnUiThread(() -> {
                    if (activityDestroyed
                            || isDestroyed()
                            || screen != Screen.DAY
                            || requestGeneration != historyRequestGeneration
                            || !key.equals(displayedDayKey)) {
                        return;
                    }
                    displayedDaySessions = new ArrayList<>(result.sessions);
                    displayedSessionIndex = result.selectedIndex;
                    processDisplayedSessionIndex = displayedSessionIndex;
                    if (result.archivePage != null) {
                        displayedArchiveCursor = result.archivePage.getCursor();
                        processDisplayedArchiveCursor = displayedArchiveCursor;
                    }
                    displayedCalendarComment = result.calendarComment;
                    if (result.failure != null) {
                        showStorageError(result.failure);
                    }
                    renderDayPage(result);
                });
        });
    }

    private void renderDayLoading(String dayTitle) {
        LinearLayout root = verticalRoot();
        root.addView(textView(dayTitle, 25), matchWrap());
        TextView loading = textView("Loading closed session history...", 24);
        loading.setGravity(Gravity.CENTER);
        root.addView(loading, weightedWrap());
        setContentView(root);
        root.requestFocus();
    }

    private void renderDayPage(DayPageData data) {
        CalendarComment calendarComment = data.calendarComment;
        editingDayComments = data.editComments || calendarComment == null;
        processEditingDayComments = editingDayComments;

        LinearLayout root = verticalRoot();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(textView(displayedDayTitle, 25), matchWrap());

        page.addView(textView("Comments", 24), matchWrap());
        if (calendarComment != null && editingDayComments) {
            page.addView(twoButtonRow(
                    "Cancel Edit", this::cancelDisplayedDayEdit,
                    "Delete Comments", this::confirmDeleteDisplayedDayComments), matchWrap());
        } else if (calendarComment != null) {
            TextView comments = interactiveCommentView(
                    CommentText.forDisplay(calendarComment.getText()));
            comments.setPadding(dp(8), dp(12), dp(8), dp(12));
            comments.setBackgroundResource(R.color.mml_field);
            page.addView(comments, matchWrap());
            page.addView(textView(
                    "Created " + formatTimestamp(
                            calendarComment.getCreatedEpochMillis(),
                            calendarComment.getCreatedZoneId()),
                    LARGE_TEXT_SP), matchWrap());
            page.addView(twoButtonRow(
                    "Edit Comments", this::editDisplayedDayComments,
                    "Delete Comments", this::confirmDeleteDisplayedDayComments), matchWrap());
        }

        page.addView(textView("Closed Sessions", 24), matchWrap());
        if (data.failure != null && data.transcript == null) {
            page.addView(textView(
                    "Closed session history is unavailable.", LARGE_TEXT_SP), matchWrap());
        } else if (displayedDaySessions.isEmpty()) {
            page.addView(textView(
                    "No closed sessions belong to this date.", LARGE_TEXT_SP), matchWrap());
        } else {
            page.addView(textView(
                    "Closed session " + (displayedSessionIndex + 1) + " of "
                            + displayedDaySessions.size(),
                    20), matchWrap());
            page.addView(twoButtonRow(
                    "Previous Session", () -> pageDisplayedSession(-1),
                    "Next Session", () -> pageDisplayedSession(1)), matchWrap());

            TextView journal = textView(data.transcript, LARGE_TEXT_SP);
            journal.setTextIsSelectable(true);
            journal.setPadding(dp(8), dp(12), dp(8), dp(12));
            page.addView(journal, matchWrap());
            if (data.archivePage != null
                    && (data.archivePage.getPreviousCursor() != null
                    || data.archivePage.getNextCursor() != null)) {
                Button previousPage = makeButton(
                        "Previous Log Page",
                        () -> pageDisplayedArchive(data.archivePage.getPreviousCursor()));
                previousPage.setEnabled(data.archivePage.getPreviousCursor() != null);
                Button nextPage = makeButton(
                        "Next Log Page",
                        () -> pageDisplayedArchive(data.archivePage.getNextCursor()));
                nextPage.setEnabled(data.archivePage.getNextCursor() != null);
                LinearLayout pageControls = new LinearLayout(this);
                pageControls.setOrientation(LinearLayout.HORIZONTAL);
                pageControls.addView(previousPage, weightedWrap());
                pageControls.addView(nextPage, weightedWrap());
                page.addView(pageControls, matchWrap());
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page, matchWrap());
        LinearLayout.LayoutParams scrollParameters = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParameters);

        if (editingDayComments) {
            Button plus = makeButton("+", () -> { });
            plus.setOnClickListener(ignored -> showAppShortcutPicker());
            plus.setContentDescription("Insert application shortcut");
            Button save = makeButton("Save", this::saveDisplayedDayComments);
            dayCommentShortcutButton = plus;
            dayCommentSaveButton = save;

            messageInput = new EditText(this);
            messageInput.setTextSize(LARGE_TEXT_SP);
            messageInput.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            messageInput.setGravity(Gravity.TOP | Gravity.START);
            messageInput.setMinLines(1);
            messageInput.setMaxLines(composerMaximumLines());
            messageInput.setSingleLine(false);
            messageInput.setVerticalScrollBarEnabled(true);
            messageInput.setHint("Comments for this date");
            messageInput.setContentDescription("Comments for " + displayedTargetLocalDate);
            messageInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            messageInput.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            messageInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(
                    JournalTextPolicy.MAX_INPUT_UTF16_UNITS)});
            messageInput.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    saveDisplayedDayComments();
                    return true;
                }
                return false;
            });
            String draftKey = dayDraftKey(
                    displayedTargetLocalDate, displayedTargetZoneId);
            String draft = processDayCommentDrafts.get(draftKey);
            if (draft == null) {
                draft = calendarComment == null ? "" : calendarComment.getText();
            }
            messageInput.setText(draft);
            messageInput.setSelection(messageInput.length());

            MmlJournalMaintenance.Snapshot maintenance = MmlJournalMaintenance.snapshot();
            setDayCommentMutationControlsEnabled(
                    !maintenance.isBusy() && !maintenance.isCompletionPending());

            addDayComposer(root, plus, messageInput, save);
            setContentView(root);
            plus.requestFocus();
        } else {
            messageInput = null;
            dayCommentShortcutButton = null;
            dayCommentSaveButton = null;
            setContentView(root);
            root.requestFocus();
        }
    }

    private void pageDisplayedSession(int delta) {
        if (screen != Screen.DAY || displayedDaySessions.isEmpty()) {
            return;
        }
        int next = displayedSessionIndex + delta;
        if (next < 0 || next >= displayedDaySessions.size()) {
            Toast.makeText(this, "Calendar session boundary.", Toast.LENGTH_SHORT).show();
            return;
        }
        renderDay(
                displayedDayKey,
                displayedDayTitle,
                displayedTargetLocalDate,
                displayedTargetZoneId,
                displayedDaySessions,
                next,
                editingDayComments);
    }

    private void pageDisplayedArchive(JournalArchiveCursor cursor) {
        if (screen != Screen.DAY || displayedDaySessions.isEmpty() || cursor == null) {
            Toast.makeText(this, "Log page boundary.", Toast.LENGTH_SHORT).show();
            return;
        }
        renderDay(
                displayedDayKey,
                displayedDayTitle,
                displayedTargetLocalDate,
                displayedTargetZoneId,
                displayedDaySessions,
                displayedSessionIndex,
                editingDayComments,
                cursor);
    }

    private void editDisplayedDayComments() {
        renderDay(
                displayedDayKey,
                displayedDayTitle,
                displayedTargetLocalDate,
                displayedTargetZoneId,
                displayedDaySessions,
                displayedSessionIndex,
                true,
                displayedArchiveCursor);
    }

    private void cancelDisplayedDayEdit() {
        MmlJournalMaintenance.Snapshot maintenance = MmlJournalMaintenance.snapshot();
        if (maintenance.isBusy() || maintenance.isCompletionPending()) {
            Toast.makeText(
                    this,
                    "Wait for journal maintenance to finish; the draft is preserved.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        processDayCommentDrafts.remove(dayDraftKey(
                displayedTargetLocalDate, displayedTargetZoneId));
        if (messageInput != null) {
            messageInput.setText("");
            messageInput = null;
        }
        renderDay(
                displayedDayKey,
                displayedDayTitle,
                displayedTargetLocalDate,
                displayedTargetZoneId,
                displayedDaySessions,
                displayedSessionIndex,
                false,
                displayedArchiveCursor);
    }

    private void saveDisplayedDayComments() {
        if (messageInput == null || displayedTargetLocalDate == null) {
            return;
        }
        String text = messageInput.getText().toString();
        String limitError = JournalTextPolicy.validationError(text);
        if (limitError != null) {
            messageInput.setError(limitError);
            messageInput.requestFocus();
            rememberDayCommentDraft(
                    dayDraftKey(displayedTargetLocalDate, displayedTargetZoneId), text);
            return;
        }
        if (!CommentText.hasPayload(text)) {
            if (displayedCalendarComment != null) {
                confirmDeleteDisplayedDayComments();
            } else {
                messageInput.setError("Type a Comment before saving.");
            }
            return;
        }
        rememberDayCommentDraft(
                dayDraftKey(displayedTargetLocalDate, displayedTargetZoneId), text);
        startCalendarCommentMutation(text);
    }

    private void confirmDeleteDisplayedDayComments() {
        if (displayedTargetLocalDate == null
                || displayedCalendarComment == null) {
            Toast.makeText(this, "No saved Comments to delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete Comments for " + displayedTargetLocalDate + "?")
                .setMessage(
                        "This removes the date's editable Comments document. "
                                + "Closed session logs are not changed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (ignoredDialog, ignoredWhich) ->
                        deleteDisplayedDayComments())
                .create();
        showLargeDialog(dialog);
    }

    private void deleteDisplayedDayComments() {
        startCalendarCommentMutation("");
    }

    private void startCalendarCommentMutation(String text) {
        String targetLocalDate = displayedTargetLocalDate;
        String targetZoneId = displayedTargetZoneId;
        if (targetLocalDate == null || targetZoneId == null || journalStore == null) {
            showStorageError(new IOException("The calendar Comments store is unavailable."));
            return;
        }
        if (!MmlJournalMaintenance.beginCalendarCommentMutation(
                getApplicationContext(),
                journalStore,
                targetLocalDate,
                targetZoneId,
                System.currentTimeMillis(),
                currentZoneId(),
                text == null ? "" : text)) {
            MmlJournalMaintenance.Snapshot snapshot = MmlJournalMaintenance.snapshot();
            if (snapshot.isCompletionPending()) {
                reconcileJournalMaintenance();
            }
            Toast.makeText(
                    this,
                    snapshot.isBusy()
                            ? "Journal maintenance is already running; the draft was preserved."
                            : "A completed journal operation must be applied first; "
                                    + "the draft was preserved.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        setDayCommentMutationControlsEnabled(false);
        Toast.makeText(
                this,
                CommentText.hasPayload(text) ? "Saving Comments..." : "Deleting Comments...",
                Toast.LENGTH_SHORT).show();
    }

    private void setDayCommentMutationControlsEnabled(boolean enabled) {
        if (messageInput != null) {
            messageInput.setEnabled(enabled);
        }
        if (dayCommentShortcutButton != null) {
            dayCommentShortcutButton.setEnabled(enabled);
        }
        if (dayCommentSaveButton != null) {
            dayCommentSaveButton.setEnabled(enabled);
        }
    }

    private void showAppShortcutPicker() {
        Map<String, AppTarget> firstByPackage = new LinkedHashMap<>();
        for (AppTarget target : loadAppTargets()) {
            firstByPackage.putIfAbsent(target.component.getPackageName(), target);
        }
        List<AppTarget> targets = new ArrayList<>(firstByPackage.values());
        if (targets.isEmpty()) {
            Toast.makeText(this, "No applications are available.", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (AppTarget target : targets) {
            labels.add(target.label + "\napp:" + target.component.getPackageName());
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Insert application shortcut")
                .setAdapter(new LargeTextAdapter(this, labels, false), (ignoredDialog, which) -> {
                    if (which >= 0 && which < targets.size()) {
                        insertAppShortcut(targets.get(which).component.getPackageName());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        showLargeDialog(dialog);
    }

    private void insertAppShortcut(String packageName) {
        if (messageInput == null || screen != Screen.DAY || !editingDayComments) {
            return;
        }
        String token;
        try {
            token = CommentText.appShortcutToken(packageName);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        int start = Math.max(0, messageInput.getSelectionStart());
        int end = Math.max(0, messageInput.getSelectionEnd());
        int lower = Math.min(start, end);
        int upper = Math.max(start, end);
        String before = lower > 0 && !Character.isWhitespace(
                messageInput.getText().charAt(lower - 1)) ? " " : "";
        String after = upper < messageInput.length() && !Character.isWhitespace(
                messageInput.getText().charAt(upper)) ? " " : "";
        messageInput.getText().replace(lower, upper, before + token + after);
        messageInput.setSelection(
                lower + before.length() + token.length() + after.length());
    }

    private void shiftCalendarMonth(int delta) {
        monthAnchorEpochMillis = CalendarProjection.shiftMonth(
                selectedCalendarSystem(),
                monthAnchorEpochMillis,
                currentZoneId(),
                delta);
        renderCalendar();
    }

    private void openMmlSettings() {
        preserveComposerDraft();
        if (!restoringProcessScreen) {
            appendMachineEvent("panel_opened", "Settings opened.", false);
        }
        setScreen(Screen.SETTINGS);

        LinearLayout content = verticalRoot();
        content.addView(textView("Settings", 27), matchWrap());
        content.addView(makeButton(
                "Open Android settings",
                () -> openSystemPanel(Settings.ACTION_SETTINGS, "Android settings")), matchWrap());

        content.addView(textView("Machine log detail", 20), matchWrap());
        RadioGroup verbosityChoices = new RadioGroup(this);
        LogVerbosity selectedVerbosity = selectedLogVerbosity();
        Map<Integer, LogVerbosity> verbosityById = new HashMap<>();
        for (LogVerbosity verbosity : LogVerbosity.values()) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(verbosity.getDisplayName());
            choice.setTextSize(LARGE_TEXT_SP);
            choice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            choice.setPadding(dp(4), dp(7), dp(4), dp(7));
            choice.setChecked(verbosity == selectedVerbosity);
            verbosityById.put(choice.getId(), verbosity);
            verbosityChoices.addView(choice, matchWrap());
        }
        verbosityChoices.setOnCheckedChangeListener((group, checkedId) -> {
            LogVerbosity verbosity = verbosityById.get(checkedId);
            if (verbosity != null) {
                preferences().edit()
                        .putString(LogVerbosity.PREFERENCE_KEY, verbosity.getPreferenceValue())
                        .apply();
                Toast.makeText(
                        this,
                        "Machine log detail: " + verbosity.getDisplayName(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(verbosityChoices, matchWrap());

        content.addView(textView("Log storage", 23), matchWrap());
        MmlLogStorage.OperationSnapshot storageOperation =
                MmlLogStorage.operationSnapshot();
        MmlJournalMaintenance.Snapshot logMaintenance =
                MmlJournalMaintenance.snapshot();
        boolean logControlsBusy = storageOperation.isBusy()
                || logMaintenance.isBusy()
                || logMaintenance.isCompletionPending();
        boolean userManagedLogs = MmlLogStorage.isUserManagedLoggingConfigured(this);
        String selectedLogDirectory = userManagedLogs
                ? MmlLogStorage.selectedLogDirectoryDisplayName(this) : "";
        content.addView(textView(logStorageStatus(), LARGE_TEXT_SP), matchWrap());

        EditText logDirectorySpecification = new EditText(this);
        logDirectorySpecification.setTextSize(LARGE_TEXT_SP);
        logDirectorySpecification.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logDirectorySpecification.setSingleLine(true);
        logDirectorySpecification.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        logDirectorySpecification.setHint("Blank: app-managed logs");
        logDirectorySpecification.setContentDescription(
                "User-managed log directory. Clear this field to return to app-managed logs.");
        logDirectorySpecification.setText(selectedLogDirectory);
        logDirectorySpecification.setSelection(logDirectorySpecification.length());
        logDirectorySpecification.setEnabled(!logControlsBusy);
        boolean[] directoryFieldActionStarted = {false};
        logDirectorySpecification.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(
                    CharSequence value, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable value) {
                if (!userManagedLogs
                        || directoryFieldActionStarted[0]
                        || !value.toString().trim().isEmpty()) {
                    return;
                }
                directoryFieldActionStarted[0] = true;
                logDirectorySpecification.setEnabled(false);
                useAppManagedLogStorage();
            }
        });
        logDirectorySpecification.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus || directoryFieldActionStarted[0]) {
                return;
            }
            String entered = logDirectorySpecification.getText().toString().trim();
            if (!entered.isEmpty() && !entered.equals(selectedLogDirectory)) {
                logDirectorySpecification.setText(selectedLogDirectory);
                logDirectorySpecification.setSelection(logDirectorySpecification.length());
                Toast.makeText(
                        this,
                        "Use Specify user-managed log directory to grant access to a folder.",
                        Toast.LENGTH_LONG).show();
            }
        });
        content.addView(logDirectorySpecification, matchWrap());
        content.addView(textView(
                userManagedLogs
                        ? "Clear the directory field to return to app-managed logs. MML leaves "
                                + "the existing user-managed files in place; clear them with your "
                                + "file manager."
                        : "With the field blank, MML stores logs in MML-managed application "
                                + "storage and manages their deletion. Use the directory selector "
                                + "to make readable files available to your file manager.",
                LARGE_TEXT_SP), matchWrap());

        Button specifyLogDirectory = makeButton(
                "Specify user-managed log directory", this::chooseLogDirectory);
        specifyLogDirectory.setEnabled(!logControlsBusy);
        content.addView(specifyLogDirectory, matchWrap());
        Button clearAppManagedLogs = makeButton(
                "Clear app-managed logs", this::confirmClearAppManagedLogs);
        clearAppManagedLogs.setEnabled(!logControlsBusy && !userManagedLogs);
        content.addView(clearAppManagedLogs, matchWrap());

        content.addView(textView("Calendar projection", 20), matchWrap());

        RadioGroup calendarChoices = new RadioGroup(this);
        CalendarSystem selected = selectedCalendarSystem();
        Map<Integer, CalendarSystem> choiceById = new HashMap<>();
        for (CalendarSystem system : CalendarSystem.values()) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(system.getDisplayName());
            choice.setTextSize(LARGE_TEXT_SP);
            choice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            choice.setPadding(dp(4), dp(7), dp(4), dp(7));
            choice.setChecked(system == selected);
            choiceById.put(choice.getId(), system);
            calendarChoices.addView(choice, matchWrap());
        }
        calendarChoices.setOnCheckedChangeListener((group, checkedId) -> {
            CalendarSystem system = choiceById.get(checkedId);
            if (system != null) {
                preferences().edit()
                        .putString(PREF_CALENDAR, system.getPreferenceValue())
                        .apply();
                monthAnchorEpochMillis = System.currentTimeMillis();
            }
        });
        content.addView(calendarChoices, matchWrap());

        content.addView(textView("Future events notification", 23), matchWrap());
        content.addView(textView(futureNotificationStatus(), LARGE_TEXT_SP), matchWrap());
        content.addView(twoButtonRow(
                "Enable notification", this::enableFutureEntryNotification,
                "Disable notification", this::disableFutureEntryNotification), matchWrap());
        content.addView(makeButton(
                "Manage notification channel",
                this::openFutureNotificationSettings), matchWrap());

        content.addView(textView("Captured notifications", 23), matchWrap());
        notificationCaptureStatusView = textView(
                notificationCaptureStatus(), LARGE_TEXT_SP);
        content.addView(notificationCaptureStatusView, matchWrap());
        content.addView(twoButtonRow(
                "Enable capture", this::enableNotificationCapture,
                "Pause capture", this::pauseNotificationCapture), matchWrap());
        content.addView(makeButton(
                "Manage Android Notification Access",
                this::openNotificationAccessSettings), matchWrap());
        Button clearCapturedNotifications = makeButton(
                "Clear captured notifications",
                this::confirmClearCapturedNotifications);
        clearCapturedNotifications.setEnabled(!userManagedLogs);
        content.addView(clearCapturedNotifications, matchWrap());
        if (userManagedLogs) {
            content.addView(textView(
                    "Captured notification rows are part of the user-managed logs. Clear the "
                            + "directory field before using MML's deletion controls; MML does not "
                            + "delete files from the user-managed directory.",
                    LARGE_TEXT_SP), matchWrap());
        }

        content.addView(textView("System brightness", 23), matchWrap());
        content.addView(textView(
                Settings.System.canWrite(this)
                        ? "Modify system settings access: granted"
                        : "Modify system settings access: required",
                LARGE_TEXT_SP), matchWrap());
        content.addView(makeButton(
                "Manage system brightness access",
                this::explainSystemBrightnessPermission), matchWrap());
        content.addView(makeButton(
                "Open Android display settings",
                () -> openSystemPanel(Settings.ACTION_DISPLAY_SETTINGS, "Android display settings")),
                matchWrap());

        content.addView(textView(
                userManagedLogs ? "User-managed log history" : "App-managed log history",
                23), matchWrap());
        MmlJournalMaintenance.Snapshot maintenance = MmlJournalMaintenance.snapshot();
        boolean maintenanceUnavailable =
                maintenance.isBusy() || maintenance.isCompletionPending();
        String maintenanceStatus;
        if (maintenance.isBusy()) {
            maintenanceStatus = maintenance.getDescription()
                    + ". Journal maintenance continues if this screen rotates.";
        } else if (maintenance.isCompletionPending()) {
            maintenanceStatus = "A completed journal operation is awaiting review.";
        } else if (!maintenance.getCompletedError().isEmpty()
                && maintenance.getGeneration() > processHandledMaintenanceGeneration) {
            maintenanceStatus = "Last journal maintenance failed: "
                    + maintenance.getCompletedError();
        } else {
            maintenanceStatus = "Journal maintenance is idle.";
        }
        content.addView(textView(maintenanceStatus, LARGE_TEXT_SP), matchWrap());
        if (userManagedLogs) {
            content.addView(textView(
                    "MML does not clear or age-delete user-managed log files. Clear the directory "
                            + "field to stop MML writes, then copy, edit, archive or delete those "
                            + "files with your file manager.",
                    LARGE_TEXT_SP), matchWrap());
        } else {
            EditText retentionDays = new EditText(this);
            retentionDays.setTextSize(LARGE_TEXT_SP);
            retentionDays.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            retentionDays.setInputType(InputType.TYPE_CLASS_NUMBER);
            retentionDays.setSingleLine(true);
            retentionDays.setHint("Days");
            retentionDays.setContentDescription("Age in whole days for journal record deletion");
            retentionDays.setEnabled(!maintenanceUnavailable);
            content.addView(retentionDays, matchWrap());
            Button deleteOldButton = makeButton(
                    "Delete records older than N days",
                    () -> confirmDeleteOldRecords(retentionDays));
            deleteOldButton.setEnabled(!maintenanceUnavailable);
            content.addView(deleteOldButton, matchWrap());
            Button clearHistoryButton = makeButton(
                    "Clear journal history",
                    this::confirmClearJournalHistory);
            clearHistoryButton.setEnabled(!maintenanceUnavailable);
            content.addView(clearHistoryButton, matchWrap());
        }

        content.addView(textView("Calendar Comments", 23), matchWrap());
        content.addView(textView(
                "Calendar Comments and Future events are planning data, not session logs.",
                LARGE_TEXT_SP), matchWrap());
        Button clearCommentsButton = makeButton(
                "Clear all calendar Comments",
                this::confirmClearCalendarComments);
        clearCommentsButton.setEnabled(!maintenanceUnavailable);
        content.addView(clearCommentsButton, matchWrap());

        Button homeSelection = makeButton(
                "Choose Android Home app",
                () -> openSystemPanel(Settings.ACTION_HOME_SETTINGS, "Android Home selection"));
        content.addView(homeSelection, matchWrap());

        Button inputMethod = makeButton("Choose Android input method", () -> {
            appendMachineEvent(
                    "panel_opened", "Android input method picker requested.", false);
            InputMethodManager manager =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.showInputMethodPicker();
        });
        content.addView(inputMethod, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, matchMatch());
        setContentView(scroll);
        reconcileJournalMaintenance();
    }

    private String logStorageStatus() {
        MmlLogStorage.OperationSnapshot operation = MmlLogStorage.operationSnapshot();
        if (operation.isBusy()) {
            return operation.getDescription() + ".";
        }
        String lastResult = operation.getLastResult().isEmpty()
                ? "" : " Last operation: " + operation.getLastResult();
        if (!MmlLogStorage.isUserManagedLoggingConfigured(this)) {
            return "Log storage: app-managed. Logs are kept in MML-managed application "
                    + "storage and can be deleted with Clear app-managed logs." + lastResult;
        }
        String location = MmlLogStorage.selectedLogDirectoryDisplayName(this);
        String failure = MmlLogStorage.lastMirrorError(this);
        if (!failure.isEmpty()) {
            return "Log storage: user-managed at " + location
                    + ". Last save failed: " + failure + lastResult;
        }
        long lastSuccess = MmlLogStorage.lastSuccessfulMirrorTime(this);
        String synchronizedAt = lastSuccess <= 0L
                ? "Initial save is pending."
                : "Last saved " + formatTimestamp(lastSuccess, currentZoneId()) + ".";
        return "Log storage: user-managed at " + location + ". "
                + synchronizedAt + lastResult;
    }

    private void chooseLogDirectory() {
        MmlJournalMaintenance.Snapshot maintenance = MmlJournalMaintenance.snapshot();
        if (maintenance.isBusy() || maintenance.isCompletionPending()) {
            Toast.makeText(
                    this,
                    "Finish the current journal operation before changing log storage.",
                    Toast.LENGTH_LONG).show();
            reconcileJournalMaintenance();
            return;
        }
        if (!MmlLogStorage.beginDirectorySelection(this)) {
            Toast.makeText(
                    this, "A log-directory change is already running.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        appendMachineEvent(
                "launch_requested", "User-managed log directory picker requested.", true);
        try {
            startActivityForResult(intent, REQUEST_LOG_FILESPACE_TREE);
        } catch (ActivityNotFoundException | SecurityException error) {
            MmlLogStorage.cancelDirectorySelection(
                    this,
                    "Android did not provide a writable directory picker; log storage is unchanged.");
            Toast.makeText(
                    this,
                    "Android did not provide a writable directory picker.",
                    Toast.LENGTH_LONG).show();
            refreshMmlSettingsWithoutAuditEvent();
        }
    }

    private void useAppManagedLogStorage() {
        if (!MmlLogStorage.selectAppManagedLogging(this)) {
            Toast.makeText(
                    this,
                    "A log-directory change is already running.",
                    Toast.LENGTH_LONG).show();
        }
        refreshMmlSettingsWithoutAuditEvent();
    }

    private void refreshMmlSettingsWithoutAuditEvent() {
        boolean wasRestoring = restoringProcessScreen;
        restoringProcessScreen = true;
        try {
            openMmlSettings();
        } finally {
            restoringProcessScreen = wasRestoring;
        }
    }

    private void openSystemPanel(String action, String label) {
        openSystemIntent(new Intent(action), label);
    }

    private void openSystemIntent(Intent intent, String label) {
        tryOpenSystemIntent(intent, label, true);
    }

    private boolean tryOpenSystemIntent(Intent intent, String label, boolean showFailure) {
        String action = intent.getAction() == null ? "unknown action" : intent.getAction();
        String detail = label + " | " + action;
        // Access to Android permission and settings controls must remain available even when the
        // private journal cannot accept the best-effort audit event (for example, on a full disk).
        appendMachineEvent("launch_requested", detail, true);
        try {
            startActivity(intent);
            processReturnHomeAfterExternalActivity = true;
            processExternalReturnScreen = screen;
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            appendMachineEvent(
                    "launch_failed", detail + " | " + error.getClass().getSimpleName(), true);
            if (showFailure) {
                Toast.makeText(
                        this,
                        "Android did not open this settings panel.",
                        Toast.LENGTH_LONG).show();
            }
            return false;
        }
    }

    private void createFutureNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                FUTURE_NOTIFICATION_CHANNEL,
                "Future events",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                "A quiet expandable list of dates that contain future MML Comments.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private int futureEntryCount() {
        MmlFutureEntryCount.Snapshot snapshot = MmlFutureEntryCount.snapshot();
        return snapshot.hasValue() ? snapshot.getCount() : 0;
    }

    private void requestFutureEntryCountRefresh(boolean requestPermissionIfNeeded) {
        requestFutureEntryCountRefresh(
                requestPermissionIfNeeded,
                FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY,
                futureEntryCount());
    }

    private void requestFutureEntryCountRefresh(
            boolean requestPermissionIfNeeded,
            FutureEntryRefreshPolicy.Mode updatePolicy,
            int baselineCount) {
        if (journalStore == null) {
            return;
        }
        MmlFutureEntryCount.request(
                getApplicationContext(),
                journalStore,
                System.currentTimeMillis(),
                requestPermissionIfNeeded,
                updatePolicy,
                baselineCount);
    }

    private void reconcileFutureEntryCount() {
        MmlFutureEntryCount.Snapshot snapshot = MmlFutureEntryCount.claimCompletedSnapshot(
                processObservedFutureEntryCountGeneration);
        if (snapshot == null) {
            return;
        }
        processObservedFutureEntryCountGeneration = snapshot.getGeneration();
        if (!snapshot.getError().isEmpty()) {
            if (screen == Screen.SETTINGS) {
                Toast.makeText(
                        this, snapshot.getError(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        boolean shouldUpdate = snapshot.getUpdatePolicy()
                == FutureEntryRefreshPolicy.Mode.ALWAYS
                || (snapshot.getUpdatePolicy() == FutureEntryRefreshPolicy.Mode.ACTIVE_ONLY
                && isFutureEntryNotificationActive())
                || (snapshot.getUpdatePolicy()
                == FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY
                && (isFutureEntryNotificationActive()
                || snapshot.getCount() != snapshot.getBaselineCount()));
        if (shouldUpdate) {
            updateFutureEntryNotification(
                    snapshot,
                    snapshot.shouldRequestPermission());
        }
        if (screen == Screen.SETTINGS) {
            refreshMmlSettingsWithoutAuditEvent();
        }
    }

    private void updateFutureEntryNotification(
            MmlFutureEntryCount.Snapshot snapshot,
            boolean requestPermissionIfNeeded) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        boolean enabled = preferences().getBoolean(PREF_FUTURE_NOTIFICATION_ENABLED, true);
        int count = snapshot != null && snapshot.hasValue()
                ? snapshot.getCount() : futureEntryCount();
        if (!enabled || count == 0) {
            manager.cancel(FUTURE_NOTIFICATION_ID);
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            manager.cancel(FUTURE_NOTIFICATION_ID);
            if (requestPermissionIfNeeded
                    && !preferences().getBoolean(
                    PREF_FUTURE_NOTIFICATION_PERMISSION_REQUESTED, false)) {
                preferences().edit()
                        .putBoolean(PREF_FUTURE_NOTIFICATION_PERMISSION_REQUESTED, true)
                        .apply();
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
            }
            return;
        }

        String notificationToken = futureNotificationToken();
        if (notificationToken.isEmpty()) {
            manager.cancel(FUTURE_NOTIFICATION_ID);
            return;
        }
        Intent openFutureEvents = new Intent(this, MainActivity.class)
                .setAction(ACTION_OPEN_CALENDAR_FROM_NOTIFICATION)
                .putExtra(EXTRA_FUTURE_NOTIFICATION_TOKEN, notificationToken)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openFutureEvents,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE);
        List<CalendarComment> summaries = snapshot == null
                ? Collections.emptyList() : snapshot.getSummaries();
        String body = FutureEntryPolicy.notificationBody(
                summaries,
                count,
                System.currentTimeMillis(),
                FUTURE_NOTIFICATION_BODY_CODE_POINTS);
        Notification.BigTextStyle expandedStyle = new Notification.BigTextStyle()
                .setBigContentTitle(FutureEntryPolicy.notificationHeading())
                .bigText(body)
                .setSummaryText(FutureEntryPolicy.notificationTitle(count));
        Notification notification = new Notification.Builder(this, FUTURE_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(FutureEntryPolicy.notificationHeading())
                .setContentText(FutureEntryPolicy.notificationTitle(count))
                .setStyle(expandedStyle)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
        manager.notify(FUTURE_NOTIFICATION_ID, notification);
    }

    private String futureNotificationToken() {
        String existing = preferences().getString(PREF_FUTURE_NOTIFICATION_TOKEN, "");
        if (!existing.isEmpty()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        boolean saved = preferences().edit()
                .putString(PREF_FUTURE_NOTIFICATION_TOKEN, generated)
                .commit();
        return saved ? generated : "";
    }

    private void updateFutureEntryNotificationAfterCalendarMutation(
            int previousFutureEntryCount,
            boolean requestPermissionIfNeeded) {
        requestFutureEntryCountRefresh(
                requestPermissionIfNeeded,
                FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                previousFutureEntryCount);
    }

    private boolean isFutureEntryNotificationActive() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (notification.getId() == FUTURE_NOTIFICATION_ID
                    && getPackageName().equals(notification.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void enableFutureEntryNotification() {
        preferences().edit()
                .putBoolean(PREF_FUTURE_NOTIFICATION_ENABLED, true)
                .putBoolean(PREF_FUTURE_NOTIFICATION_PERMISSION_REQUESTED, true)
                .apply();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            requestFutureEntryCountRefresh(
                    false,
                    FutureEntryRefreshPolicy.Mode.ALWAYS,
                    futureEntryCount());
            Toast.makeText(this, "Future events notification enabled.", Toast.LENGTH_LONG).show();
            openMmlSettings();
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void disableFutureEntryNotification() {
        preferences().edit()
                .putBoolean(PREF_FUTURE_NOTIFICATION_ENABLED, false)
                .apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(FUTURE_NOTIFICATION_ID);
        }
        Toast.makeText(this, "Future events notification disabled.", Toast.LENGTH_LONG).show();
        openMmlSettings();
    }

    private void openFutureNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, FUTURE_NOTIFICATION_CHANNEL);
        openSystemIntent(intent, "Future events notification settings");
    }

    private String futureNotificationStatus() {
        boolean enabled = preferences().getBoolean(PREF_FUTURE_NOTIFICATION_ENABLED, true);
        boolean permission = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (!enabled) {
            return "Disabled";
        }
        if (!permission) {
            return "Android permission needed";
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && !manager.areNotificationsEnabled()) {
            return "Disabled for MML in Android settings";
        }
        NotificationChannel channel = manager == null
                ? null : manager.getNotificationChannel(FUTURE_NOTIFICATION_CHANNEL);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return "Disabled in Android settings";
        }
        return "Enabled - " + FutureEntryPolicy.notificationTitle(futureEntryCount());
    }

    private ComponentName notificationListenerComponent() {
        return new ComponentName(this, MmlNotificationListener.class);
    }

    private boolean notificationCaptureAccessGranted() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationListenerAccessGranted(
                notificationListenerComponent());
    }

    private String notificationCaptureStatus() {
        boolean active = preferences().getBoolean(
                MmlNotificationListener.PREF_CAPTURE_ENABLED, true);
        boolean accessGranted = notificationCaptureAccessGranted();
        String access = accessGranted
                ? "Android access granted" : "Android access not granted";
        String connection = accessGranted
                ? (MmlNotificationListener.isListenerConnected()
                        ? " - listener connected" : " - listener disconnected")
                : "";
        MmlNotificationListener.CaptureMaintenanceSnapshot maintenance =
                MmlNotificationListener.captureMaintenanceSnapshot();
        String maintenanceStatus = maintenance.isBusy()
                ? " - " + maintenance.getDescription()
                : maintenance.getLastResult().isEmpty()
                        ? ""
                        : " - " + maintenance.getLastResult();
        return access + connection + " - " + (active ? "active" : "paused")
                + " - bounded to " + JournalStore.MAX_STORED_CAPTURE_COUNT
                + " retained captures" + maintenanceStatus;
    }

    private void rebindNotificationCaptureIfNeeded() {
        boolean active = preferences().getBoolean(
                MmlNotificationListener.PREF_CAPTURE_ENABLED, true);
        if (active && notificationCaptureAccessGranted()
                && !MmlNotificationListener.isListenerConnected()) {
            MmlNotificationListener.requestListenerRebind(this);
        }
    }

    private void enableNotificationCapture() {
        preferences().edit()
                .putBoolean(MmlNotificationListener.PREF_CAPTURE_ENABLED, true)
                .apply();
        if (notificationCaptureAccessGranted()) {
            rebindNotificationCaptureIfNeeded();
            Toast.makeText(this, "Notification capture enabled.", Toast.LENGTH_LONG).show();
            openMmlSettings();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Allow MML Notification Access?")
                .setMessage(
                        "Android will show a special-access screen. If you grant access, MML can "
                                + "read visible notification text from other applications and save "
                                + "it in the private current-session log. You can pause capture "
                                + "inside MML or revoke access in Android at any time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open Android access", (ignoredDialog, ignoredWhich) ->
                        openNotificationAccessSettings())
                .create();
        showLargeDialog(dialog);
    }

    private void pauseNotificationCapture() {
        preferences().edit()
                .putBoolean(MmlNotificationListener.PREF_CAPTURE_ENABLED, false)
                .apply();
        Toast.makeText(
                this,
                "Notification capture paused. Android access was not revoked.",
                Toast.LENGTH_LONG).show();
        openMmlSettings();
    }

    private void openNotificationAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        notificationListenerComponent().flattenToString());
        if (!tryOpenSystemIntent(intent, "Android Notification Access for MML", false)) {
            openSystemIntent(
                    new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    "Android Notification Access");
        }
    }

    private void confirmClearCapturedNotifications() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear captured notifications?")
                .setMessage(
                        "This permanently removes all captured notification data, including any "
                                + "unreadable pending or log records. "
                                + "Other events, Comments and calendar plans remain.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear captures", (ignoredDialog, ignoredWhich) ->
                        clearCapturedNotifications())
                .create();
        showLargeDialog(dialog);
    }

    private void clearCapturedNotifications() {
        if (journalStore == null || currentSession == null) {
            showStorageError(new IOException("The current journal is unavailable."));
            return;
        }
        boolean queued = MmlNotificationListener.enqueueCapturedNotificationClear(
                getApplicationContext(), journalStore);
        if (!queued) {
            Toast.makeText(
                    this,
                    "Captured-notification maintenance is already running.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(
                    this,
                    "Clearing captured notifications in the background...",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void followSystemBrightness() {
        WindowManager.LayoutParams parameters = getWindow().getAttributes();
        if (parameters.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            parameters.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(parameters);
        }
    }

    private void removeRetiredWindowBrightnessPreferences() {
        SharedPreferences settings = preferences();
        if (settings.contains("window-brightness-enabled")
                || settings.contains("window-brightness-percent")) {
            settings.edit()
                    .remove("window-brightness-enabled")
                    .remove("window-brightness-percent")
                    .apply();
        }
    }

    private int readSystemBrightnessPercent() {
        int level = Settings.System.getInt(
                getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        return clampBrightnessPercent(Math.round(level * 100f / 255f));
    }

    private static int clampBrightnessPercent(int percent) {
        return Math.max(1, Math.min(100, percent));
    }

    private boolean writeSystemBrightnessPercent(int percent) {
        int clampedPercent = clampBrightnessPercent(percent);
        int systemLevel = 1 + Math.round((clampedPercent - 1) * 254f / 99f);
        try {
            return Settings.System.putInt(
                    getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, systemLevel);
        } catch (SecurityException error) {
            return false;
        }
    }

    private void explainSystemBrightnessPermission() {
        if (brightnessPermissionDialogShowing) {
            return;
        }
        brightnessPermissionDialogShowing = true;
        boolean accessGranted = Settings.System.canWrite(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("System brightness access")
                .setMessage(
                        accessGranted
                                ? "Horizontal swiping on the Calendar has Modify system settings "
                                        + "access. Open Android access to review or revoke it."
                                : "Android requires Modify system settings access before a "
                                        + "horizontal Calendar swipe can change system brightness.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open access", (ignoredDialog, ignoredWhich) -> {
                    Intent permission = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    permission.setData(Uri.parse("package:" + getPackageName()));
                    openSystemIntent(permission, "Modify system settings permission");
                })
                .create();
        dialog.setOnDismissListener(ignored -> brightnessPermissionDialogShowing = false);
        showLargeDialog(dialog);
    }

    private void confirmDeleteOldRecords(EditText retentionDays) {
        String value = retentionDays.getText().toString().trim();
        long days;
        try {
            days = Long.parseLong(value);
        } catch (NumberFormatException error) {
            retentionDays.setError("Enter a whole number of days.");
            retentionDays.requestFocus();
            return;
        }
        if (days < 1L || days > MAX_RETENTION_DAYS) {
            retentionDays.setError("Enter an integer from 1 through " + MAX_RETENTION_DAYS + ".");
            retentionDays.requestFocus();
            return;
        }
        if (!ensureWritableCurrentJournal()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY;
        startMaintenance(MmlJournalMaintenance.Kind.COUNT_OLD, cutoff, days);
    }

    private void deleteOldRecords(long cutoff, long days) {
        if (!ensureWritableCurrentJournal()) {
            return;
        }
        startMaintenance(MmlJournalMaintenance.Kind.DELETE_OLD, cutoff, days);
    }

    private void confirmClearAppManagedLogs() {
        if (MmlLogStorage.isUserManagedLoggingConfigured(this)) {
            Toast.makeText(
                    this,
                    "User-managed logs must be cleared with your file manager.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        MmlLogStorage.OperationSnapshot storageOperation =
                MmlLogStorage.operationSnapshot();
        MmlJournalMaintenance.Snapshot maintenance =
                MmlJournalMaintenance.snapshot();
        if (storageOperation.isBusy()
                || maintenance.isBusy()
                || maintenance.isCompletionPending()) {
            Toast.makeText(
                    this,
                    "Another log or journal operation is already running.",
                    Toast.LENGTH_LONG).show();
            reconcileJournalMaintenance();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear app-managed logs?")
                .setMessage(
                        "This permanently deletes every app-managed current and closed session "
                                + "log, including session Comments, machine events and captured "
                                + "notification log data. The open Current Session remains the "
                                + "Current Session, but its log is emptied. Calendar Comments and "
                                + "Future events are preserved. Files in a user-managed directory "
                                + "are never deleted by this button.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Clear app-managed logs",
                        (ignoredDialog, ignoredWhich) -> clearAppManagedLogs())
                .create();
        showLargeDialog(dialog);
    }

    private void clearAppManagedLogs() {
        if (MmlLogStorage.isUserManagedLoggingConfigured(this)) {
            Toast.makeText(
                    this,
                    "User-managed logs must be cleared with your file manager.",
                    Toast.LENGTH_LONG).show();
            refreshMmlSettingsWithoutAuditEvent();
            return;
        }
        if (MmlLogStorage.operationSnapshot().isBusy()) {
            Toast.makeText(
                    this,
                    "A log-storage change is already running.",
                    Toast.LENGTH_LONG).show();
            refreshMmlSettingsWithoutAuditEvent();
            return;
        }
        if (!ensureWritableCurrentJournal()) {
            showStorageError(new IOException("The current app-managed log is unavailable."));
            return;
        }
        String sessionId = currentSession.getId();
        if (!MmlJournalMaintenance.beginAppManagedLogClear(
                getApplicationContext(),
                journalStore,
                sessionId)) {
            MmlJournalMaintenance.Snapshot snapshot = MmlJournalMaintenance.snapshot();
            if (snapshot.isBusy()) {
                Toast.makeText(
                        this,
                        "Journal maintenance is already running.",
                        Toast.LENGTH_LONG).show();
            } else if (snapshot.isCompletionPending()) {
                reconcileJournalMaintenance();
                Toast.makeText(
                        this,
                        "The previous journal result must be applied before retrying.",
                        Toast.LENGTH_LONG).show();
            } else {
                showStorageError(new IOException(
                        snapshot.getCompletedError().isEmpty()
                                ? "Could not schedule app-managed log clearing."
                                : snapshot.getCompletedError()));
            }
            return;
        }
        processAppManagedLogClearInProgress = true;
        MmlProcessSession.activate(null);
        Toast.makeText(this, "Clearing app-managed logs...", Toast.LENGTH_SHORT).show();
        if (screen == Screen.SETTINGS) {
            refreshMmlSettingsWithoutAuditEvent();
        }
    }

    private void confirmClearJournalHistory() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear closed session history?")
                .setMessage(
                        "This permanently deletes every closed session, including all machine "
                                + "events and session Comments. The Current Session and separate "
                                + "calendar Comments are preserved.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear closed", (ignoredDialog, ignoredWhich) ->
                        clearJournalHistory())
                .create();
        showLargeDialog(dialog);
    }

    private void clearJournalHistory() {
        if (!ensureWritableCurrentJournal()) {
            return;
        }
        startMaintenance(MmlJournalMaintenance.Kind.CLEAR_HISTORY, 0L, 0L);
    }

    private void confirmClearCalendarComments() {
        startMaintenance(MmlJournalMaintenance.Kind.COUNT_COMMENTS, 0L, 0L);
    }

    private void clearCalendarComments() {
        startMaintenance(MmlJournalMaintenance.Kind.CLEAR_COMMENTS, 0L, 0L);
    }

    private void startMaintenance(
            MmlJournalMaintenance.Kind kind,
            long cutoff,
            long days) {
        String sessionId = currentSession == null ? null : currentSession.getId();
        if ((kind == MmlJournalMaintenance.Kind.COUNT_OLD
                || kind == MmlJournalMaintenance.Kind.DELETE_OLD
                || kind == MmlJournalMaintenance.Kind.CLEAR_HISTORY
                || kind == MmlJournalMaintenance.Kind.CLEAR_APP_MANAGED_LOGS)
                && sessionId == null) {
            showStorageError(new IOException("The current journal is unavailable."));
            return;
        }
        if (!MmlJournalMaintenance.begin(
                getApplicationContext(), journalStore, kind, sessionId, cutoff, days)) {
            MmlJournalMaintenance.Snapshot snapshot = MmlJournalMaintenance.snapshot();
            if (snapshot.isBusy()) {
                Toast.makeText(
                        this,
                        "Journal maintenance is already running.",
                        Toast.LENGTH_LONG).show();
            } else if (snapshot.isCompletionPending()) {
                reconcileJournalMaintenance();
                Toast.makeText(
                        this,
                        "The previous journal result must be applied before retrying.",
                        Toast.LENGTH_LONG).show();
            } else {
                showStorageError(new IOException(
                        snapshot.getCompletedError().isEmpty()
                                ? "Could not schedule journal maintenance."
                                : snapshot.getCompletedError()));
            }
            return;
        }
        MmlJournalMaintenance.Snapshot snapshot = MmlJournalMaintenance.snapshot();
        Toast.makeText(
                this, snapshot.getDescription() + "...", Toast.LENGTH_SHORT).show();
        if (screen == Screen.SETTINGS) {
            refreshMmlSettingsWithoutAuditEvent();
        }
    }

    /** Reattaches any Activity instance to the one process-global maintenance operation/result. */
    private void reconcileJournalMaintenance() {
        if (activityDestroyed || isDestroyed() || journalStore == null) {
            return;
        }
        MmlJournalMaintenance.Snapshot result = MmlJournalMaintenance.snapshot();
        if (result.isBusy() || result.getCompletedKind() == null) {
            return;
        }
        if (result.getGeneration() <= processHandledMaintenanceGeneration) {
            // Repairs the coordinator if this Activity was replaced after the process marker was
            // advanced but before the retained completion could be acknowledged.
            MmlJournalMaintenance.acknowledgeCompletion(result.getGeneration());
            return;
        }
        MmlJournalMaintenance.Kind kind = result.getCompletedKind();
        boolean confirmation = kind == MmlJournalMaintenance.Kind.COUNT_OLD
                || kind == MmlJournalMaintenance.Kind.COUNT_COMMENTS;
        if (confirmation
                && result.getCompletedError().isEmpty()
                && result.getCompletedValue() > 0
                && screen != Screen.SETTINGS) {
            // A replacement Settings page consumes the still-retained count result.
            return;
        }
        if (!result.getCompletedError().isEmpty()) {
            if (!consumeMaintenanceCompletion(result.getGeneration())) {
                return;
            }
            boolean archiveMayHaveChanged =
                    kind == MmlJournalMaintenance.Kind.DELETE_OLD
                            || kind == MmlJournalMaintenance.Kind.CLEAR_HISTORY
                            || kind == MmlJournalMaintenance.Kind.CLEAR_APP_MANAGED_LOGS;
            boolean commentsMayHaveChanged =
                    kind == MmlJournalMaintenance.Kind.CLEAR_COMMENTS
                            || kind == MmlJournalMaintenance.Kind.SAVE_COMMENT;
            if (kind == MmlJournalMaintenance.Kind.CLEAR_APP_MANAGED_LOGS) {
                processAppManagedLogClearInProgress = false;
                try {
                    restoreWritableSessionAfterAppManagedLogClear();
                } catch (IOException recoveryFailure) {
                    renderFatalStorageError(new IOException(
                            result.getCompletedError()
                                    + " The Current Session could not be re-established: "
                                    + recoveryFailure.getMessage(),
                            recoveryFailure));
                    return;
                }
            }
            if (archiveMayHaveChanged
                    || kind == MmlJournalMaintenance.Kind.CLEAR_COMMENTS) {
                // Per-record deletion can make durable progress before a later record fails.
                invalidateArchivedCalendarIndex();
            }
            if (archiveMayHaveChanged) {
                displayedArchiveCursor = JournalArchiveCursor.first();
                processDisplayedArchiveCursor = JournalArchiveCursor.first();
            }
            if (commentsMayHaveChanged
                    && result.getCompletedAuxiliaryValue() >= 0) {
                // Atomic replacement may be durable even if later cleanup reports failure. Recount
                // without prompting for new permission, preserving the pre-mutation boundary.
                requestFutureEntryCountRefresh(
                        false,
                        FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY,
                        result.getCompletedAuxiliaryValue());
            }
            showStorageError(new IOException(result.getCompletedError()));
            if (screen == Screen.DAY) {
                // Every operation disables the day composer while busy; rebuild it from the
                // bounded process draft once this failure has been consumed.
                renderRestoredProcessScreen();
            } else if (screen == Screen.CALENDAR
                    && (archiveMayHaveChanged || commentsMayHaveChanged)) {
                renderCalendar();
            } else if (screen == Screen.SETTINGS) {
                refreshMmlSettingsWithoutAuditEvent();
            }
            return;
        }
        if (kind == MmlJournalMaintenance.Kind.COUNT_OLD) {
            if (displayedMaintenanceConfirmationGeneration == result.getGeneration()) {
                return;
            }
            if (result.getCompletedValue() == 0) {
                if (!consumeMaintenanceCompletion(result.getGeneration())) {
                    return;
                }
                Toast.makeText(
                        this,
                        "No archived journal records are older than "
                                + result.getCompletedDays() + " days.",
                        Toast.LENGTH_LONG).show();
                if (screen == Screen.SETTINGS) {
                    refreshMmlSettingsWithoutAuditEvent();
                } else if (screen == Screen.DAY) {
                    renderRestoredProcessScreen();
                }
                return;
            }
            displayedMaintenanceConfirmationGeneration = result.getGeneration();
            final boolean[] decisionMade = {false};
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Delete " + result.getCompletedValue() + " journal records?")
                    .setMessage(
                            "This permanently deletes each selected record's metadata, machine "
                                    + "events and session Comments. The Current Session and "
                                    + "separate calendar Comments are preserved. Cutoff: "
                                    + formatTimestamp(
                                    result.getCompletedCutoff(), currentZoneId()) + ".")
                    .setNegativeButton("Cancel", (ignoredDialog, ignoredWhich) -> {
                        decisionMade[0] = true;
                        consumeMaintenanceConfirmation(result.getGeneration());
                        refreshMmlSettingsWithoutAuditEvent();
                    })
                    .setPositiveButton("Delete", (ignoredDialog, ignoredWhich) -> {
                        decisionMade[0] = true;
                        if (consumeMaintenanceConfirmation(result.getGeneration())) {
                            deleteOldRecords(
                                    result.getCompletedCutoff(), result.getCompletedDays());
                        }
                    })
                    .create();
            dialog.setOnDismissListener(ignored -> {
                if (!decisionMade[0] && !activityDestroyed && !isChangingConfigurations()) {
                    consumeMaintenanceConfirmation(result.getGeneration());
                    if (screen == Screen.SETTINGS) {
                        refreshMmlSettingsWithoutAuditEvent();
                    }
                }
            });
            showLargeDialog(dialog);
            return;
        }
        if (kind == MmlJournalMaintenance.Kind.COUNT_COMMENTS) {
            if (displayedMaintenanceConfirmationGeneration == result.getGeneration()) {
                return;
            }
            if (result.getCompletedValue() == 0) {
                if (!consumeMaintenanceCompletion(result.getGeneration())) {
                    return;
                }
                Toast.makeText(
                        this, "No calendar Comments are saved.", Toast.LENGTH_LONG).show();
                if (screen == Screen.SETTINGS) {
                    refreshMmlSettingsWithoutAuditEvent();
                } else if (screen == Screen.DAY) {
                    renderRestoredProcessScreen();
                }
                return;
            }
            displayedMaintenanceConfirmationGeneration = result.getGeneration();
            final boolean[] decisionMade = {false};
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Clear all calendar Comments?")
                    .setMessage(
                            "This permanently removes " + result.getCompletedValue()
                                    + (result.getCompletedValue() == 1
                                    ? " date's Comments document. "
                                    : " dates' Comments documents. ")
                                    + "Current and closed session logs are not changed.")
                    .setNegativeButton("Cancel", (ignoredDialog, ignoredWhich) -> {
                        decisionMade[0] = true;
                        consumeMaintenanceConfirmation(result.getGeneration());
                        refreshMmlSettingsWithoutAuditEvent();
                    })
                    .setPositiveButton("Clear Comments", (ignoredDialog, ignoredWhich) -> {
                        decisionMade[0] = true;
                        if (consumeMaintenanceConfirmation(result.getGeneration())) {
                            clearCalendarComments();
                        }
                    })
                    .create();
            dialog.setOnDismissListener(ignored -> {
                if (!decisionMade[0] && !activityDestroyed && !isChangingConfigurations()) {
                    consumeMaintenanceConfirmation(result.getGeneration());
                    if (screen == Screen.SETTINGS) {
                        refreshMmlSettingsWithoutAuditEvent();
                    }
                }
            });
            showLargeDialog(dialog);
            return;
        }

        if (!consumeMaintenanceCompletion(result.getGeneration())) {
            return;
        }
        displayedMaintenanceConfirmationGeneration = 0L;
        if (kind == MmlJournalMaintenance.Kind.CLEAR_APP_MANAGED_LOGS) {
            processAppManagedLogClearInProgress = false;
            try {
                restoreWritableSessionAfterAppManagedLogClear();
            } catch (IOException recoveryFailure) {
                renderFatalStorageError(recoveryFailure);
                return;
            }
            invalidateArchivedCalendarIndex();
            displayedArchiveCursor = JournalArchiveCursor.first();
            processDisplayedArchiveCursor = JournalArchiveCursor.first();
            Toast.makeText(
                    this,
                    result.getCompletedValue() == 1
                            ? "App-managed log cleared."
                            : "Cleared " + result.getCompletedValue()
                                    + " app-managed session logs.",
                    Toast.LENGTH_LONG).show();
            if (screen == Screen.SETTINGS) {
                refreshMmlSettingsWithoutAuditEvent();
            } else if (screen == Screen.CURRENT_SESSION) {
                renderCurrentSession();
            } else if (screen == Screen.DAY || screen == Screen.CALENDAR) {
                renderCalendar();
            }
            return;
        }
        if (kind == MmlJournalMaintenance.Kind.SAVE_COMMENT) {
            String targetLocalDate = result.getCompletedTargetLocalDate();
            String targetZoneId = result.getCompletedTargetZoneId();
            boolean saved = result.getCompletedValue() == 1;
            processDayCommentDrafts.remove(dayDraftKey(targetLocalDate, targetZoneId));
            if (targetLocalDate.equals(displayedTargetLocalDate)
                    && targetZoneId.equals(displayedTargetZoneId)
                    && messageInput != null) {
                messageInput.setText("");
                messageInput = null;
            }
            appendMachineEvent(
                    saved ? "calendar_comments_saved" : "calendar_comments_deleted",
                    (saved ? "Saved" : "Deleted")
                            + " the editable Comments document for " + targetLocalDate
                            + " [" + targetZoneId + "].",
                    false);
            updateFutureEntryNotificationAfterCalendarMutation(
                    result.getCompletedAuxiliaryValue(), saved);
            Toast.makeText(
                    this, saved ? "Comments saved." : "Comments deleted.",
                    Toast.LENGTH_SHORT).show();
            if (screen == Screen.DAY
                    && targetLocalDate.equals(displayedTargetLocalDate)
                    && targetZoneId.equals(displayedTargetZoneId)) {
                renderDay(
                        displayedDayKey,
                        displayedDayTitle,
                        displayedTargetLocalDate,
                        displayedTargetZoneId,
                        displayedDaySessions,
                        displayedSessionIndex,
                        !saved,
                        displayedArchiveCursor);
            } else if (screen == Screen.CALENDAR) {
                renderCalendar();
            } else if (screen == Screen.SETTINGS) {
                refreshMmlSettingsWithoutAuditEvent();
            }
            return;
        }

        invalidateArchivedCalendarIndex();
        if (kind == MmlJournalMaintenance.Kind.DELETE_OLD) {
            appendMachineEvent(
                    "journal_history_deleted",
                    "Deleted " + result.getCompletedValue()
                            + " archived journal records older than "
                            + result.getCompletedDays()
                            + " days, including their events and session Comments.",
                    false);
            Toast.makeText(
                    this,
                    "Deleted " + result.getCompletedValue() + " archived journal records.",
                    Toast.LENGTH_LONG).show();
        } else if (kind == MmlJournalMaintenance.Kind.CLEAR_HISTORY) {
            appendMachineEvent(
                    "journal_history_cleared",
                    "Deleted " + result.getCompletedValue()
                            + " closed sessions by direct user confirmation; "
                            + "the Current Session was preserved.",
                    false);
            Toast.makeText(
                    this,
                    "Deleted " + result.getCompletedValue() + " closed sessions.",
                    Toast.LENGTH_LONG).show();
        } else if (kind == MmlJournalMaintenance.Kind.CLEAR_COMMENTS) {
            processDayCommentDrafts.clear();
            appendMachineEvent(
                    "calendar_comments_cleared",
                    "Cleared all editable date Comments by direct user confirmation.",
                    false);
            updateFutureEntryNotificationAfterCalendarMutation(
                    result.getCompletedAuxiliaryValue(), false);
            Toast.makeText(this, "Calendar Comments cleared.", Toast.LENGTH_LONG).show();
        }
        if (screen == Screen.SETTINGS) {
            refreshMmlSettingsWithoutAuditEvent();
        } else if (screen == Screen.DAY) {
            renderCalendar();
        } else if (screen == Screen.CALENDAR) {
            // The user may leave Settings while the process-global mutation is still running.
            renderCalendar();
        } else if (kind == MmlJournalMaintenance.Kind.CLEAR_HISTORY
                && screen == Screen.CURRENT_SESSION) {
            refreshCurrentSessionLog();
        }
    }

    /** Reloads the preserved Current Session after its app-managed log was emptied. */
    private void restoreWritableSessionAfterAppManagedLogClear()
            throws IOException {
        String candidate = processSessionId == null ? "" : processSessionId;
        if (candidate.isEmpty()
                || !journalStore.isSessionOpen(candidate)) {
            JournalSession replacement = journalStore.startLauncherSession(
                    bootIdentity,
                    System.currentTimeMillis(),
                    currentZoneId(),
                    selectedLogVerbosity());
            candidate = replacement.getId();
        }
        processSessionId = candidate;
        reloadCurrentSessionWindow(candidate);
        MmlProcessSession.activate(candidate);
        refreshCapturedNotifications();
    }

    private boolean consumeMaintenanceConfirmation(long generation) {
        boolean consumed = consumeMaintenanceCompletion(generation);
        if (consumed && displayedMaintenanceConfirmationGeneration == generation) {
            displayedMaintenanceConfirmationGeneration = 0L;
        }
        return consumed;
    }

    private boolean consumeMaintenanceCompletion(long generation) {
        if (generation <= processHandledMaintenanceGeneration) {
            MmlJournalMaintenance.acknowledgeCompletion(generation);
            return false;
        }
        if (!MmlJournalMaintenance.acknowledgeCompletion(generation)) {
            return false;
        }
        processHandledMaintenanceGeneration = generation;
        return true;
    }

    private void closeForShutdown() {
        if (journalStore == null || currentSession == null
                || currentSession.getStatus() != SessionStatus.OPEN) {
            return;
        }
        try {
            currentSession = journalStore.markShutdownObserved(
                    currentSession.getId(),
                    System.currentTimeMillis(),
                    currentZoneId(),
                    "Android shutdown signal was observed while MML was alive.",
                    selectedLogVerbosity());
        } catch (IOException ignored) {
            // ACTION_SHUTDOWN is best effort and there may be no time for user interaction.
        }
    }

    private boolean appendMachineEvent(String type, String detail, boolean reportFailure) {
        if (processAppManagedLogClearInProgress) {
            // The clear worker owns the durable log boundary. Routine lifecycle and panel
            // evidence is intentionally skipped until that boundary has committed.
            return true;
        }
        if (!selectedLogVerbosity().includesEventType(type)) {
            // Log detail is a recording policy, never an authorization gate for the requested
            // launch, save, settings or cleanup operation.
            return true;
        }
        if (!ensureWritableCurrentJournal()) {
            if (reportFailure) {
                Toast.makeText(this, "The MML journal is unavailable.", Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        JournalEvent appended = new JournalEvent(
                System.currentTimeMillis(), currentZoneId(), type, detail);
        try {
            journalStore.appendEvent(currentSession.getId(), appended);
        } catch (IOException error) {
            if (reportFailure) {
                showStorageError(error);
            }
            return false;
        }

        CurrentWindowAppend windowAppend = appendToCurrentSessionWindow(appended);
        if (currentLogContainer != null && screen == Screen.CURRENT_SESSION) {
            appendVisibleCurrentLogRow(appended, windowAppend);
            scrollCurrentLogToEnd();
        }
        return true;
    }

    /** Keeps one writable session for the lifetime of this MML process. */
    private boolean ensureWritableCurrentJournal() {
        if (journalStore == null || processAppManagedLogClearInProgress) {
            return false;
        }
        if (currentSession == null && processSessionId != null) {
            try {
                reloadCurrentSessionWindow(processSessionId);
            } catch (IOException ignored) {
                return false;
            }
        } else if (currentSession != null) {
            try {
                if (!journalStore.isSessionOpen(currentSession.getId())) {
                    currentSession = null;
                    return false;
                }
            } catch (IOException ignored) {
                currentSession = null;
                return false;
            }
        }
        if (currentSession == null || currentSession.getStatus() != SessionStatus.OPEN) {
            return false;
        }
        return true;
    }

    /** Attaches listener entries queued before Activity startup and refreshes any direct append. */
    private void refreshCapturedNotifications() {
        if (journalStore == null || currentSession == null) {
            return;
        }
        String sessionId = currentSession.getId();
        MmlNotificationListener.enqueueCapturedNotificationDrain(
                getApplicationContext(), journalStore, sessionId);
        scheduleCapturedNotificationWindowRefresh(false, 0L);
    }

    private void scheduleCapturedNotificationWindowRefresh(
            boolean captureClearOccurred,
            long targetStorageGeneration) {
        if (journalStore == null || currentSession == null || activityDestroyed) {
            return;
        }
        JournalStore store = journalStore;
        String sessionId = currentSession.getId();
        int requestGeneration = ++captureRefreshRequestGeneration;
        captureClearRefreshPending |= captureClearOccurred;
        captureRefreshTargetGeneration = Math.max(
                captureRefreshTargetGeneration, targetStorageGeneration);
        long requestedStorageGeneration = captureRefreshTargetGeneration;
        if (captureRefreshFuture != null) {
            captureRefreshFuture.cancel(false);
        }
        try {
            captureRefreshFuture = captureRefreshExecutor.schedule(() -> {
                JournalSessionWindow window = null;
                IOException failure = null;
                try {
                    window = store.loadSessionWindow(
                            sessionId,
                            CURRENT_SESSION_EVENT_WINDOW,
                            CURRENT_SESSION_ENCODED_BYTE_WINDOW);
                } catch (IOException error) {
                    failure = error;
                } catch (RuntimeException error) {
                    failure = new IOException(
                            "Captured-notification refresh failed.", error);
                }
                JournalSessionWindow completedWindow = window;
                IOException completedFailure = failure;
                runOnUiThread(() -> finishCapturedNotificationWindowRefresh(
                        requestGeneration,
                        requestedStorageGeneration,
                        sessionId,
                        completedWindow,
                        completedFailure));
            }, CAPTURE_REFRESH_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Activity destruction owns and stops this instance-only executor.
        }
    }

    private void finishCapturedNotificationWindowRefresh(
            int requestGeneration,
            long requestedStorageGeneration,
            String expectedSessionId,
            JournalSessionWindow window,
            IOException failure) {
        if (activityDestroyed
                || requestGeneration != captureRefreshRequestGeneration
                || currentSession == null
                || !currentSession.getId().equals(expectedSessionId)) {
            return;
        }
        boolean captureClearOccurred = captureClearRefreshPending;
        captureClearRefreshPending = false;
        captureRefreshTargetGeneration = 0L;
        if (captureClearOccurred) {
            displayedArchiveCursor = JournalArchiveCursor.first();
            processDisplayedArchiveCursor = JournalArchiveCursor.first();
            invalidateArchivedCalendarIndex();
        }
        if (failure != null) {
            if (hasResumed) {
                showStorageError(failure);
            }
        } else {
            applyCurrentSessionWindow(window);
            if (requestedStorageGeneration > processObservedCaptureStorageGeneration) {
                processObservedCaptureStorageGeneration = requestedStorageGeneration;
            }
            if (screen == Screen.CURRENT_SESSION && currentLogContainer != null) {
                populateCurrentLog(currentLogContainer, currentSession);
                scrollCurrentLogToEnd();
            } else if (screen == Screen.SETTINGS && notificationCaptureStatusView != null) {
                notificationCaptureStatusView.setText(notificationCaptureStatus());
            }
        }
        if (captureClearOccurred) {
            if (screen == Screen.CALENDAR) {
                renderCalendar();
            } else if (screen == Screen.DAY) {
                renderRestoredProcessScreen();
            }
        }
    }

    private void reloadCurrentSessionWindow(String sessionId) throws IOException {
        applyCurrentSessionWindow(journalStore.loadSessionWindow(
                sessionId,
                CURRENT_SESSION_EVENT_WINDOW,
                CURRENT_SESSION_ENCODED_BYTE_WINDOW));
    }

    private void applyCurrentSessionWindow(JournalSessionWindow window) {
        if (window == null) {
            return;
        }
        JournalSession loaded = window.getSession();
        currentSessionEarlierEventsOmitted = window.hasEarlierEventsOmitted();
        List<JournalEvent> boundedEvents = new ArrayList<>();
        currentSessionWindowEncodedBytes = 0;
        for (JournalEvent event : loaded.getEvents()) {
            int weight = currentWindowEventWeight(event);
            boundedEvents.add(event);
            currentSessionWindowEncodedBytes += weight;
            while (!boundedEvents.isEmpty()
                    && currentSessionWindowEncodedBytes > CURRENT_SESSION_ENCODED_BYTE_WINDOW) {
                JournalEvent removed = boundedEvents.remove(0);
                currentSessionWindowEncodedBytes = Math.max(
                        0,
                        currentSessionWindowEncodedBytes - currentWindowEventWeight(removed));
                currentSessionEarlierEventsOmitted = true;
            }
        }
        currentSession = new JournalSession(
                loaded.getId(),
                loaded.getBootIdentity(),
                loaded.getStartedEpochMillis(),
                loaded.getStartedZoneId(),
                loaded.getStatus(),
                boundedEvents,
                Collections.emptyList());
    }

    private CurrentWindowAppend appendToCurrentSessionWindow(JournalEvent event) {
        if (currentSession == null || event == null) {
            return new CurrentWindowAppend(false, Collections.emptyList());
        }
        List<JournalEvent> events = new ArrayList<>(currentSession.getEvents());
        List<JournalEvent> evicted = new ArrayList<>();
        int eventWeight = currentWindowEventWeight(event);
        if (eventWeight > CURRENT_SESSION_ENCODED_BYTE_WINDOW) {
            currentSessionEarlierEventsOmitted = true;
            return new CurrentWindowAppend(false, evicted);
        }
        while (!events.isEmpty()
                && (events.size() >= CURRENT_SESSION_EVENT_WINDOW
                        || currentSessionWindowEncodedBytes
                                > CURRENT_SESSION_ENCODED_BYTE_WINDOW - eventWeight)) {
            JournalEvent removed = events.remove(0);
            currentSessionWindowEncodedBytes = Math.max(
                    0, currentSessionWindowEncodedBytes - currentWindowEventWeight(removed));
            evicted.add(removed);
            currentSessionEarlierEventsOmitted = true;
        }
        events.add(event);
        currentSessionWindowEncodedBytes += eventWeight;
        currentSession = new JournalSession(
                currentSession.getId(),
                currentSession.getBootIdentity(),
                currentSession.getStartedEpochMillis(),
                currentSession.getStartedZoneId(),
                currentSession.getStatus(),
                events,
                Collections.emptyList());
        return new CurrentWindowAppend(true, evicted);
    }

    private static int currentWindowEventWeight(JournalEvent event) {
        if (event == null) {
            return 0;
        }
        long total = 64L;
        total = addEncodedTextWeight(total, event.getZoneId());
        total = addEncodedTextWeight(total, event.getType());
        total = addEncodedTextWeight(total, event.getDetail());
        return total > CURRENT_SESSION_ENCODED_BYTE_WINDOW
                ? CURRENT_SESSION_ENCODED_BYTE_WINDOW + 1 : (int) total;
    }

    private static long addEncodedTextWeight(long initial, String value) {
        long total = initial;
        if (value == null) {
            return total;
        }
        for (int offset = 0;
                offset < value.length() && total <= CURRENT_SESSION_ENCODED_BYTE_WINDOW;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\\' || codePoint == '\n'
                    || codePoint == '\r' || codePoint == '\t') {
                total += 2L;
            } else if (codePoint <= 0x7f) {
                total++;
            } else if (codePoint <= 0x7ff) {
                total += 2L;
            } else if (codePoint <= 0xffff) {
                total += 3L;
            } else {
                total += 4L;
            }
        }
        return total;
    }

    private void reconcileCaptureStorageChanges() {
        long[] storageState = MmlNotificationListener.captureStorageState();
        long currentGeneration = storageState[0];
        if (currentGeneration <= processObservedCaptureStorageGeneration) {
            return;
        }
        boolean captureClearOccurred =
                storageState[1] > processObservedCaptureStorageGeneration;
        if (journalStore != null && currentSession != null) {
            MmlNotificationListener.enqueueCapturedNotificationDrain(
                    getApplicationContext(), journalStore, currentSession.getId());
        }
        scheduleCapturedNotificationWindowRefresh(captureClearOccurred, currentGeneration);
    }

    private List<JournalSessionIndex> archivedCalendarIndex() throws IOException {
        long requestedEpoch;
        synchronized (PROCESS_CALENDAR_INDEX_LOCK) {
            if (processArchivedSessionIndex != null) {
                return processArchivedSessionIndex;
            }
            requestedEpoch = processCalendarIndexEpoch;
        }
        List<JournalSessionIndex> archived = new ArrayList<>();
        String preservedSessionId = processSessionId;
        for (JournalSessionIndex session : journalStore.listCalendarSessionIndex()) {
            if (session.getStatus() != SessionStatus.OPEN
                    && (preservedSessionId == null
                    || !session.getId().equals(preservedSessionId))) {
                archived.add(session);
            }
        }
        List<JournalSessionIndex> immutable = Collections.unmodifiableList(archived);
        synchronized (PROCESS_CALENDAR_INDEX_LOCK) {
            if (requestedEpoch == processCalendarIndexEpoch) {
                processArchivedSessionIndex = immutable;
            }
        }
        return immutable;
    }

    private ProjectedCalendarIndex projectedCalendarIndex(
            CalendarSystem system) throws IOException {
        long requestedEpoch;
        synchronized (PROCESS_CALENDAR_INDEX_LOCK) {
            if (processProjectedCalendarIndex != null
                    && processProjectedCalendarIndex.system == system) {
                return processProjectedCalendarIndex;
            }
            requestedEpoch = processCalendarIndexEpoch;
        }
        Map<CalendarDateKey, List<JournalSessionIndex>> byDate = new HashMap<>();
        for (JournalSessionIndex session : archivedCalendarIndex()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Calendar history projection was canceled.");
            }
            for (CalendarDateKey key : sessionDateKeys(session, system)) {
                byDate.computeIfAbsent(key, ignored -> new ArrayList<>()).add(session);
            }
        }
        Map<CalendarDateKey, List<JournalSessionIndex>> immutableByDate = new HashMap<>();
        for (Map.Entry<CalendarDateKey, List<JournalSessionIndex>> entry : byDate.entrySet()) {
            immutableByDate.put(
                    entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        ProjectedCalendarIndex projected = new ProjectedCalendarIndex(
                system, Collections.unmodifiableMap(immutableByDate));
        synchronized (PROCESS_CALENDAR_INDEX_LOCK) {
            if (requestedEpoch == processCalendarIndexEpoch) {
                processProjectedCalendarIndex = projected;
            }
        }
        return projected;
    }

    private static void invalidateArchivedCalendarIndex() {
        synchronized (PROCESS_CALENDAR_INDEX_LOCK) {
            processCalendarIndexEpoch++;
            processArchivedSessionIndex = null;
            processProjectedCalendarIndex = null;
        }
    }

    private static String dayDraftKey(String targetLocalDate, String targetZoneId) {
        return (targetLocalDate == null ? "" : targetLocalDate)
                + "@" + (targetZoneId == null ? "" : targetZoneId);
    }

    private String currentSessionHeader() {
        if (currentSession == null) {
            return "Current Session\nLog unavailable";
        }
        return "Current Session\nStarted " + formatTimestamp(
                currentSession.getStartedEpochMillis(), currentSession.getStartedZoneId());
    }

    private TextView interactiveCommentView(String displayedText) {
        return interactiveCommentView(displayedText, 0);
    }

    private TextView interactiveCommentView(String displayedText, int linkStart) {
        SpannableStringBuilder linked = new SpannableStringBuilder(
                displayedText == null ? "" : displayedText);
        List<CommentText.AppShortcut> shortcuts = CommentText.findAppShortcuts(linked.toString());
        for (int index = shortcuts.size() - 1; index >= 0; index--) {
            CommentText.AppShortcut shortcut = shortcuts.get(index);
            String packageName = shortcut.getPackageName();
            String replacement = "[App: " + appLabelForPackage(packageName) + "]";
            linked.replace(shortcut.getStart(), shortcut.getEnd(), replacement);
            linked.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    openCommentAppShortcut(packageName);
                }
            }, shortcut.getStart(), shortcut.getStart() + replacement.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        Linkify.addLinks(linked, Linkify.WEB_URLS | Linkify.PHONE_NUMBERS);
        for (URLSpan span : linked.getSpans(0, linked.length(), URLSpan.class)) {
            int start = linked.getSpanStart(span);
            int end = linked.getSpanEnd(span);
            int flags = linked.getSpanFlags(span);
            String url = span.getURL();
            linked.removeSpan(span);
            boolean overlapsAppShortcut = false;
            for (ClickableSpan clickable : linked.getSpans(start, end, ClickableSpan.class)) {
                if (!(clickable instanceof URLSpan)) {
                    overlapsAppShortcut = true;
                    break;
                }
            }
            if (start < Math.max(0, linkStart) || overlapsAppShortcut) {
                continue;
            }
            linked.setSpan(new URLSpan(url) {
                @Override
                public void onClick(View widget) {
                    openCommentUri(url);
                }
            }, start, end, flags);
        }

        TextView view = textView("", LARGE_TEXT_SP);
        view.setText(linked, TextView.BufferType.SPANNABLE);
        view.setTextIsSelectable(true);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
        view.setLinkTextColor(getColor(R.color.mml_link));
        return view;
    }

    private String appLabelForPackage(String packageName) {
        for (AppTarget target : loadAppTargets()) {
            if (packageName.equals(target.component.getPackageName())) {
                return target.label;
            }
        }
        return packageName;
    }

    private void openCommentAppShortcut(String packageName) {
        List<AppTarget> matches = new ArrayList<>();
        for (AppTarget target : loadAppTargets()) {
            if (packageName.equals(target.component.getPackageName())) {
                matches.add(target);
            }
        }
        if (matches.isEmpty()) {
            appendMachineEvent(
                    "launch_failed",
                    "Calendar Comment app shortcut is unavailable | " + packageName,
                    false);
            Toast.makeText(
                    this, "This app shortcut is no longer available.", Toast.LENGTH_LONG).show();
            return;
        }
        if (matches.size() == 1) {
            confirmLaunchCommentApp(matches.get(0));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (AppTarget target : matches) {
            labels.add(target.label + "\n" + target.component.flattenToShortString());
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Open app shortcut")
                .setAdapter(new LargeTextAdapter(this, labels, false), (ignoredDialog, which) -> {
                    if (which >= 0 && which < matches.size()) {
                        confirmLaunchCommentApp(matches.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        showLargeDialog(dialog);
    }

    private void confirmLaunchCommentApp(AppTarget target) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Open " + target.label + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (ignoredDialog, ignoredWhich) ->
                        launchCommentApp(target))
                .create();
        showLargeDialog(dialog);
    }

    private void launchCommentApp(AppTarget target) {
        String detail = "Calendar Comment app shortcut | " + target.label
                + " | " + target.component.flattenToShortString();
        appendMachineEvent("launch_requested", detail, false);
        LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            launcherApps.startMainActivity(target.component, target.user, null, null);
        } catch (RuntimeException error) {
            appendMachineEvent(
                    "launch_failed",
                    detail + " | " + error.getClass().getSimpleName(),
                    true);
            Toast.makeText(
                    this, "Android did not launch this app shortcut.", Toast.LENGTH_LONG).show();
        }
    }

    private void openCommentUri(String value) {
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        Intent intent;
        String label;
        if ("tel".equals(scheme)) {
            intent = new Intent(Intent.ACTION_DIAL, uri);
            label = "Calendar Comment phone number";
        } else if ("http".equals(scheme) || "https".equals(scheme)) {
            intent = new Intent(Intent.ACTION_VIEW, uri);
            label = "Calendar Comment URL";
        } else {
            Toast.makeText(this, "This Comment link type is not allowed.", Toast.LENGTH_LONG).show();
            return;
        }
        String safeTarget = "tel".equals(scheme)
                ? "number omitted"
                : (uri.getHost() == null ? "web host omitted" : "host " + uri.getHost());
        String detail = label + " | " + safeTarget;
        appendMachineEvent("launch_requested", detail, false);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            appendMachineEvent(
                    "launch_failed", detail + " | " + error.getClass().getSimpleName(), true);
            Toast.makeText(this, "Android could not open this Comment link.", Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void populateCurrentLog(LinearLayout container, JournalSession session) {
        container.removeAllViews();
        if (session == null) {
            container.addView(textView("Log unavailable", LARGE_TEXT_SP), matchWrap());
            return;
        }
        addCurrentLogWindowNoticeIfNeeded(container);
        LogVerbosity verbosity = selectedLogVerbosity();
        for (JournalEvent event : session.getEvents()) {
            if (!verbosity.includesEventType(event.getType())) {
                continue;
            }
            addCurrentLogRow(container, event);
        }
    }

    private void appendVisibleCurrentLogRow(
            JournalEvent event,
            CurrentWindowAppend windowAppend) {
        LinearLayout container = currentLogContainer;
        if (container == null || event == null || windowAppend == null) {
            return;
        }
        addCurrentLogWindowNoticeIfNeeded(container);
        for (JournalEvent evicted : windowAppend.evicted) {
            for (int childIndex = 0; childIndex < container.getChildCount(); childIndex++) {
                View child = container.getChildAt(childIndex);
                if (child.getTag() == evicted) {
                    container.removeViewAt(childIndex);
                    break;
                }
            }
        }
        if (windowAppend.retained
                && selectedLogVerbosity().includesEventType(event.getType())) {
            addCurrentLogRow(container, event);
        }
        int noticeCount = currentSessionEarlierEventsOmitted ? 1 : 0;
        while (container.getChildCount() > CURRENT_SESSION_EVENT_WINDOW + noticeCount) {
            container.removeViewAt(noticeCount);
        }
    }

    private void addCurrentLogWindowNoticeIfNeeded(LinearLayout container) {
        if (!currentSessionEarlierEventsOmitted || container == null) {
            return;
        }
        if (container.getChildCount() > 0
                && Boolean.TRUE.equals(container.getChildAt(0).getTag())) {
            return;
        }
        TextView notice = textView(
                "Showing a bounded recent window of up to " + CURRENT_SESSION_EVENT_WINDOW
                        + " entries. Earlier or individually oversized entries remain in the "
                        + "durable journal and appear in the closed-session archive.",
                LARGE_TEXT_SP);
        notice.setTag(Boolean.TRUE);
        notice.setPadding(dp(6), dp(7), dp(6), dp(7));
        container.addView(notice, 0, matchWrap());
    }

    private void addCurrentLogRow(LinearLayout container, JournalEvent event) {
        StringBuilder text = new StringBuilder();
        text.append(compactTimestamp(event.getEpochMillis(), event.getZoneId())).append("  ");
        int commentLinkStart = text.length();
        boolean commentEvent = isCommentEvent(event.getType());
        boolean capturedNotification = isCapturedNotificationEvent(event.getType());
        if (commentEvent) {
            text.append(CommentText.forDisplay(event.getDetail()));
        } else if (capturedNotification) {
            text.append(compactCapturedNotification(event));
        } else {
            text.append(compactMachineEntry(event));
        }
        TextView row = commentEvent
                ? interactiveCommentView(text.toString(), commentLinkStart)
                : textView(text.toString(), LARGE_TEXT_SP);
        row.setTag(event);
        row.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.setSingleLine(false);
        row.setMaxLines(commentEvent ? Integer.MAX_VALUE : 3);
        row.setEllipsize(commentEvent ? null : TextUtils.TruncateAt.END);
        row.setHorizontallyScrolling(false);
        row.setPadding(dp(6), dp(7), dp(6), dp(7));
        row.setMinHeight(dp(48));
        if (commentEvent) {
            row.setBackgroundResource(R.color.mml_field);
        }
        if (capturedNotification) {
            row.setOnClickListener(ignored -> replayCapturedNotification(event));
            row.setOnLongClickListener(ignored -> {
                showCapturedNotificationDetails(event, null);
                return true;
            });
            row.setContentDescription(
                    text + ". Activate notification action. Long press for details.");
        } else if (!commentEvent) {
            row.setOnClickListener(ignored -> showMachineEventDetails(event));
            row.setContentDescription(text + ". Activate for full details.");
        }
        container.addView(row, matchWrap());
    }

    private static final class CurrentWindowAppend {
        private final boolean retained;
        private final List<JournalEvent> evicted;

        private CurrentWindowAppend(boolean retained, List<JournalEvent> evicted) {
            this.retained = retained;
            this.evicted = evicted;
        }
    }

    private void showMachineEventDetails(JournalEvent event) {
        String detail = "Time: " + formatTimestamp(event.getEpochMillis(), event.getZoneId())
                + "\nType: " + displayEventType(event.getType())
                + "\n\n" + event.getDetail();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Log entry")
                .setMessage(detail)
                .setPositiveButton("Close", null)
                .create();
        showLargeDialog(dialog);
    }

    private void replayCapturedNotification(JournalEvent event) {
        CapturedNotification capture = CapturedNotification.decodeDetail(event.getDetail());
        if (capture == null) {
            showCapturedNotificationDetails(event, "This saved notification is unreadable.");
            return;
        }
        MmlNotificationListener.ReplayResult result =
                MmlNotificationListener.replayCapturedNotification(capture);
        if (result == MmlNotificationListener.ReplayResult.OPENED) {
            appendMachineEvent(
                    "notification_action_opened",
                    "Performed the active notification action for "
                            + notificationSource(capture) + ".",
                    false);
            return;
        }
        showCapturedNotificationDetails(event, switch (result) {
            case LISTENER_UNAVAILABLE -> "Android Notification Access is not connected.";
            case NO_LONGER_ACTIVE -> "This notification is no longer active.";
            case NO_TAP_ACTION -> "This notification has no tap action.";
            case CANCELED -> "The source canceled this notification action.";
            case FAILED -> "Android could not perform this notification action.";
            case OPENED -> null;
        });
    }

    private void showCapturedNotificationDetails(JournalEvent event, String status) {
        CapturedNotification capture = CapturedNotification.decodeDetail(event.getDetail());
        String detail = capture == null
                ? "Time: " + formatTimestamp(event.getEpochMillis(), event.getZoneId())
                        + "\n\n(Unreadable captured-notification row)"
                : capture.fullDisplay();
        if (status != null && !status.isBlank()) {
            detail = status + "\n\n" + detail;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Notification")
                .setMessage(detail)
                .setPositiveButton("Close", null)
                .create();
        showLargeDialog(dialog);
    }

    private static boolean isCommentEvent(String type) {
        return "comment".equals(type) || "user_note".equals(type);
    }

    private static boolean isCapturedNotificationEvent(String type) {
        return JournalStore.CAPTURED_NOTIFICATION_EVENT_TYPE.equals(type);
    }

    private static String archivedJournalText(JournalArchivePage page) {
        JournalSession session = page.getMetadata();
        StringBuilder text = new StringBuilder();
        text.append("Session began: ").append(formatTimestamp(
                session.getStartedEpochMillis(), session.getStartedZoneId()) + "\n"
                + "Boot identity: " + session.getBootIdentity() + "\n\n");
        text.append(page.getCursor().getSection() == JournalArchiveCursor.Section.EVENTS
                ? "[bounded session-log page]\n\n"
                : "[bounded legacy Comments page; read-only]\n\n");
        if (page.getOversizedRowsSkipped() > 0) {
            text.append("Safety notice: ")
                    .append(page.getOversizedRowsSkipped())
                    .append(" oversized durable row(s) were not materialized on this page.\n\n");
        }
        if (page.getMalformedRowsSkipped() > 0) {
            text.append("Notice: ")
                    .append(page.getMalformedRowsSkipped())
                    .append(" unreadable durable row(s) were skipped on this page.\n\n");
        }
        for (JournalEvent event : page.getEvents()) {
            boolean commentEvent = isCommentEvent(event.getType());
            boolean capturedNotification = isCapturedNotificationEvent(event.getType());
            String heading;
            String detail;
            if (commentEvent) {
                heading = "Comment";
                detail = CommentText.forDisplay(event.getDetail());
            } else if (capturedNotification) {
                heading = "Captured Notification";
                CapturedNotification capture =
                        CapturedNotification.decodeDetail(event.getDetail());
                detail = capture == null
                        ? "(Unreadable captured-notification row)" : capture.fullDisplay();
            } else {
                heading = "Machine event: " + displayEventType(event.getType());
                detail = event.getDetail();
            }
            text.append(formatTimestamp(event.getEpochMillis(), event.getZoneId())).append('\n')
                    .append(heading).append('\n')
                    .append(detail)
                    .append("\n\n");
        }
        for (JournalNote note : page.getLegacyNotes()) {
            text.append("Legacy Comment date: ").append(note.getLocalDate())
                    .append(" [").append(note.getZoneId()).append("]\n")
                    .append(CommentText.forDisplay(note.getText())).append("\n\n");
        }
        if (page.getEvents().isEmpty() && page.getLegacyNotes().isEmpty()
                && page.getOversizedRowsSkipped() == 0
                && page.getMalformedRowsSkipped() == 0) {
            text.append("No readable rows are present on this page.\n\n");
        }
        text.append("Full durable data remains in the journal. Use Previous Log Page and "
                + "Next Log Page to move through it without loading the whole session.");
        return text.toString();
    }

    private static String compactMachineEntry(JournalEvent event) {
        String type = displayEventType(event.getType()).replace('_', ' ');
        String detail = oneLogicalLine(event.getDetail());
        return boundedOneLogicalLine(
                detail.isEmpty() ? type : detail,
                MAX_CURRENT_MACHINE_CODE_POINTS);
    }

    private static String compactCapturedNotification(JournalEvent event) {
        CapturedNotification capture = CapturedNotification.decodeDetail(event.getDetail());
        if (capture == null) {
            return "Unreadable captured-notification row";
        }
        String title = oneLogicalLine(capture.getTitle());
        String body = oneLogicalLine(capture.getBody());
        String subText = oneLogicalLine(capture.getSubText());
        String source = notificationSource(capture);
        StringBuilder text = new StringBuilder();
        appendDistinctCompactPart(text, title);
        appendDistinctCompactPart(text, body);
        appendDistinctCompactPart(text, subText);
        if (text.length() == 0) {
            text.append(source);
        } else if (!source.isBlank()
                && !source.equals(title)
                && !source.equals(body)
                && !source.equals(subText)) {
            text.append(" [").append(source).append(']');
        }
        return boundedOneLogicalLine(text.toString(), MAX_CURRENT_NOTIFICATION_CODE_POINTS);
    }

    private static void appendDistinctCompactPart(StringBuilder destination, String value) {
        if (!value.isBlank() && destination.indexOf(value) < 0) {
            if (destination.length() > 0) {
                destination.append(" - ");
            }
            destination.append(value);
        }
    }

    private static String notificationSource(CapturedNotification capture) {
        return capture.getAppLabel().isBlank()
                ? capture.getSourcePackage() : capture.getAppLabel();
    }

    private static String boundedOneLogicalLine(String value, int maximumCodePoints) {
        String oneLine = oneLogicalLine(value);
        if (oneLine.codePointCount(0, oneLine.length()) <= maximumCodePoints) {
            return oneLine;
        }
        int retainedCodePoints = Math.max(0, maximumCodePoints - 3);
        int end = oneLine.offsetByCodePoints(0, retainedCodePoints);
        return oneLine.substring(0, end) + "...";
    }

    private static String oneLogicalLine(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private static String displayEventType(String storedType) {
        if ("session_opened".equals(storedType)) {
            return "boot_journal_started";
        }
        if ("session_ended_cleanly".equals(storedType)) {
            return "shutdown_observed";
        }
        if ("session_unclosed".equals(storedType)) {
            return "prior_boot_boundary_inferred";
        }
        return storedType;
    }

    private Set<CalendarDateKey> sessionDateKeys(
            JournalSessionIndex session,
            CalendarSystem system) {
        Set<CalendarDateKey> keys = new HashSet<>();
        for (JournalSessionIndex.DateObservation observation : session.getDateObservations()) {
            keys.add(CalendarProjection.dateKey(
                    system, observation.getEpochMillis(), observation.getZoneId()));
        }
        return keys;
    }

    private static String formatTimestamp(long epochMillis, String zoneId) {
        try {
            return EVENT_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zoneId)));
        } catch (DateTimeException error) {
            return EVENT_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("UTC")));
        }
    }

    private static String compactTimestamp(long epochMillis, String zoneId) {
        try {
            return COMPACT_TIME_FORMAT.format(
                    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zoneId)));
        } catch (DateTimeException error) {
            return COMPACT_TIME_FORMAT.format(
                    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("UTC")));
        }
    }

    private CalendarSystem selectedCalendarSystem() {
        return CalendarSystem.fromPreference(
                preferences().getString(PREF_CALENDAR, CalendarSystem.GREGORIAN.getPreferenceValue()));
    }

    private LogVerbosity selectedLogVerbosity() {
        return LogVerbosity.fromPreference(preferences().getString(
                LogVerbosity.PREFERENCE_KEY,
                LogVerbosity.DEFAULT.getPreferenceValue()));
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFERENCES, MODE_PRIVATE);
    }

    private static String currentZoneId() {
        return ZoneId.systemDefault().getId();
    }

    private static long derivedBootIdentity(long now, long elapsedRealtime) {
        long approximateBootMinute = (now - elapsedRealtime) / 60_000L;
        return -1L - approximateBootMinute;
    }

    private void configureSystemBars() {
        getWindow().setDecorFitsSystemWindows(false);
        View decor = getWindow().getDecorView();
        decor.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets safeInsets = windowInsets.getInsets(
                    WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
            Insets imeInsets = windowInsets.getInsets(WindowInsets.Type.ime());
            view.setPadding(
                    safeInsets.left,
                    safeInsets.top,
                    safeInsets.right,
                    Math.max(safeInsets.bottom, imeInsets.bottom));
            hideStatusBar();
            return windowInsets;
        });
        decor.requestApplyInsets();
        hideStatusBar();
    }

    private void hideStatusBar() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.statusBars());
        }
    }

    private boolean isLandscapeLayout() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int composerMaximumLines() {
        return isLandscapeLayout() ? 2 : 4;
    }

    private int currentSessionComposerMinimumLines() {
        return 1;
    }

    private void addCurrentSessionComposer(
            LinearLayout root,
            EditText input,
            Button launcher,
            Button submit) {
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(0, dp(5), 0, 0);
        input.setMinWidth(0);
        input.setMinimumWidth(0);
        input.setHorizontallyScrolling(false);
        composer.addView(input, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(4), 0, 0);
        actions.addView(launcher, weightedWrap());
        actions.addView(submit, weightedWrap());
        composer.addView(actions, matchWrap());
        root.addView(composer, matchWrap());
        root.addOnLayoutChangeListener((
                view,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom) -> constrainCurrentSessionInputHeight(
                        root, composer, actions, input));
    }

    private void constrainCurrentSessionInputHeight(
            LinearLayout root,
            LinearLayout composer,
            LinearLayout actions,
            EditText input) {
        int contentHeight = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (contentHeight <= 0 || actions.getMeasuredHeight() <= 0) {
            return;
        }
        int composerPadding = composer.getPaddingTop() + composer.getPaddingBottom();
        int minimumInputHeight = input.getLineHeight()
                + input.getCompoundPaddingTop() + input.getCompoundPaddingBottom();
        int lineLimitedInputHeight = composerMaximumLines() * input.getLineHeight()
                + input.getCompoundPaddingTop() + input.getCompoundPaddingBottom();
        int remainingAfterRequiredControls = contentHeight
                - actions.getMeasuredHeight()
                - composerPadding
                - minimumInputHeight;
        int transcriptReserve = Math.min(
                dp(48), Math.max(0, remainingAfterRequiredControls / 3));
        int maximumInputHeight = Math.min(
                lineLimitedInputHeight,
                Math.max(
                        minimumInputHeight,
                        contentHeight
                                - actions.getMeasuredHeight()
                                - composerPadding
                                - transcriptReserve));
        if (input.getMaxHeight() != maximumInputHeight) {
            input.setMaxHeight(maximumInputHeight);
        }
    }

    private void addDayComposer(
            LinearLayout root,
            Button plus,
            EditText input,
            Button submit) {
        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(0, dp(5), 0, 0);
        input.setMinWidth(0);
        input.setMinimumWidth(0);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.addView(plus, wrapWrap());
        composer.addView(input, weightedWrap());
        composer.addView(submit, wrapWrap());
        root.addView(composer, matchWrap());
    }

    /** Installs the Calendar inside the one gesture surface that controls system brightness. */
    private void setCalendarContentView(View calendarRoot) {
        CalendarBrightnessFrame brightnessFrame = new CalendarBrightnessFrame();
        brightnessFrame.addView(calendarRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(brightnessFrame);
    }

    /**
     * Converts a horizontal drag anywhere on the Calendar screen into system brightness.
     * Vertical drags remain available to the Calendar ScrollView and taps remain child actions.
     */
    private final class CalendarBrightnessFrame extends FrameLayout {
        private final int touchSlop;
        private float downX;
        private float downY;
        private int startingBrightnessPercent;
        private int lastRequestedBrightnessPercent;
        private int lastAppliedBrightnessPercent;
        private boolean tracking;
        private boolean intercepting;
        private boolean verticalGesture;
        private boolean changed;
        private boolean writeFailed;
        private boolean permissionRequired;

        CalendarBrightnessFrame() {
            super(MainActivity.this);
            touchSlop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
            setClickable(true);
            setContentDescription(
                    "Calendar. Swipe horizontally anywhere to adjust Android system brightness.");
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && event.getPointerCount() == 1) {
                beginBrightnessGesture(event);
                return false;
            }
            if (!tracking || event.getPointerCount() != 1
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                resetBrightnessGesture();
                return false;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                classifyBrightnessGesture(event);
                if (intercepting) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    applyBrightnessGesture(event);
                    return true;
                }
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetBrightnessGesture();
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && event.getPointerCount() == 1) {
                beginBrightnessGesture(event);
                return true;
            }
            if (!tracking || event.getPointerCount() != 1
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                boolean handled = intercepting;
                finishBrightnessGesture();
                return handled;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                classifyBrightnessGesture(event);
                if (intercepting) {
                    applyBrightnessGesture(event);
                }
                return intercepting;
            }
            if (action == MotionEvent.ACTION_UP) {
                boolean handled = intercepting;
                if (intercepting) {
                    applyBrightnessGesture(event);
                }
                finishBrightnessGesture();
                return handled;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                boolean handled = intercepting;
                finishBrightnessGesture();
                return handled;
            }
            return intercepting;
        }

        private void beginBrightnessGesture(MotionEvent event) {
            downX = event.getX();
            downY = event.getY();
            startingBrightnessPercent = readSystemBrightnessPercent();
            lastRequestedBrightnessPercent = startingBrightnessPercent;
            lastAppliedBrightnessPercent = startingBrightnessPercent;
            tracking = true;
            intercepting = false;
            verticalGesture = false;
            changed = false;
            writeFailed = false;
            permissionRequired = false;
        }

        private void classifyBrightnessGesture(MotionEvent event) {
            if (intercepting || verticalGesture) {
                return;
            }
            float horizontalDistance = event.getX() - downX;
            float verticalDistance = event.getY() - downY;
            if (MmlPresentationPolicy.isCalendarBrightnessGesture(
                    horizontalDistance, verticalDistance, touchSlop)) {
                intercepting = true;
                return;
            }
            if (Math.abs(verticalDistance) >= touchSlop
                    && Math.abs(verticalDistance) >= Math.abs(horizontalDistance)) {
                verticalGesture = true;
            }
        }

        private void applyBrightnessGesture(MotionEvent event) {
            if (!intercepting) {
                return;
            }
            int widthPixels = getWidth();
            if (widthPixels <= 0) {
                widthPixels = getResources().getDisplayMetrics().widthPixels;
            }
            if (widthPixels <= 0) {
                return;
            }
            int requestedPercent = MmlPresentationPolicy.calendarBrightnessPercent(
                    startingBrightnessPercent,
                    event.getX() - downX,
                    widthPixels);
            if (requestedPercent == lastRequestedBrightnessPercent) {
                return;
            }
            lastRequestedBrightnessPercent = requestedPercent;
            if (!Settings.System.canWrite(MainActivity.this)) {
                permissionRequired = true;
                return;
            }
            if (writeSystemBrightnessPercent(requestedPercent)) {
                lastAppliedBrightnessPercent = requestedPercent;
                changed = true;
                setContentDescription(
                        "Calendar. Android system brightness " + requestedPercent
                                + " percent. Swipe horizontally anywhere to adjust it.");
            } else {
                writeFailed = true;
            }
        }

        private void finishBrightnessGesture() {
            boolean showPermission = permissionRequired;
            boolean reportWriteFailure = writeFailed;
            boolean reportChange = changed;
            int finalPercent = lastAppliedBrightnessPercent;
            resetBrightnessGesture();
            if (showPermission) {
                post(MainActivity.this::explainSystemBrightnessPermission);
            }
            if (reportWriteFailure) {
                post(() -> Toast.makeText(
                        MainActivity.this,
                        "Android did not accept the system brightness level.",
                        Toast.LENGTH_LONG).show());
            }
            if (reportChange) {
                appendMachineEvent(
                        "system_brightness_changed",
                        "Android system brightness set to " + finalPercent
                                + "% by a horizontal Calendar swipe.",
                        false);
            }
        }

        private void resetBrightnessGesture() {
            tracking = false;
            intercepting = false;
            verticalGesture = false;
            changed = false;
            writeFailed = false;
            permissionRequired = false;
        }
    }

    private LinearLayout verticalRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int verticalPadding = isLandscapeLayout() ? 4 : 8;
        root.setPadding(dp(10), dp(verticalPadding), dp(10), dp(verticalPadding));
        root.setFocusableInTouchMode(true);
        return root;
    }

    private TextView textView(String text, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(Math.max(LARGE_TEXT_SP, sizeSp));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(dp(4), dp(5), dp(4), dp(5));
        return view;
    }

    private Button makeButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(LARGE_TEXT_SP);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setEllipsize(null);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void addGridCell(GridLayout grid, View view) {
        GridLayout.LayoutParams parameters = new GridLayout.LayoutParams();
        parameters.width = 0;
        parameters.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        parameters.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        parameters.setGravity(Gravity.FILL_HORIZONTAL);
        parameters.setMargins(dp(1), dp(1), dp(1), dp(1));
        grid.addView(view, parameters);
    }

    private LinearLayout twoButtonRow(
            String firstLabel,
            Runnable firstAction,
            String secondLabel,
            Runnable secondAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button first = makeButton(firstLabel, firstAction);
        Button second = makeButton(secondLabel, secondAction);
        row.addView(first, weightedWrap());
        row.addView(second, weightedWrap());
        return row;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams matchWeightedHeight() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private static LinearLayout.LayoutParams weightedWrap() {
        return weightedWrap(1f);
    }

    private static LinearLayout.LayoutParams weightedWrap(float weight) {
        return new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showLargeDialog(AlertDialog dialog) {
        dialog.setOnShowListener(ignored -> {
            TextView message = dialog.findViewById(android.R.id.message);
            styleLargeBold(message);
            if (message != null) {
                message.setTextIsSelectable(true);
            }
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            if (titleId != 0) {
                styleLargeBold(dialog.findViewById(titleId));
            }
            styleLargeBold(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            styleLargeBold(dialog.getButton(AlertDialog.BUTTON_NEUTRAL));
            styleLargeBold(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        });
        dialog.show();
    }

    private static void styleLargeBold(TextView view) {
        if (view != null) {
            view.setTextSize(LARGE_TEXT_SP);
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
    }

    private void showStorageError(IOException error) {
        Toast.makeText(
                this, "MML journal storage error: " + error.getMessage(), Toast.LENGTH_LONG).show();
    }

    private void renderFatalStorageError(IOException error) {
        TextView message = textView(
                "Modern Mobile Launcher could not open its private journal.\n\n"
                        + error.getMessage(),
                21);
        setContentView(message);
    }

    private static final class CalendarPageData {
        private final CalendarProjection.MonthGrid month;
        private final Map<CalendarDateKey, List<JournalSessionIndex>> sessionsByDate;
        private final Map<CalendarDateKey, CalendarComment> commentPages;
        private final CalendarDateKey todayKey;
        private final String zoneId;

        private CalendarPageData(
                CalendarProjection.MonthGrid month,
                Map<CalendarDateKey, List<JournalSessionIndex>> sessionsByDate,
                Map<CalendarDateKey, CalendarComment> commentPages,
                CalendarDateKey todayKey,
                String zoneId) {
            this.month = month;
            this.sessionsByDate = sessionsByDate;
            this.commentPages = commentPages;
            this.todayKey = todayKey;
            this.zoneId = zoneId;
        }
    }

    private static final class ProjectedCalendarIndex {
        private final CalendarSystem system;
        private final Map<CalendarDateKey, List<JournalSessionIndex>> sessionsByDate;

        private ProjectedCalendarIndex(
                CalendarSystem system,
                Map<CalendarDateKey, List<JournalSessionIndex>> sessionsByDate) {
            this.system = system;
            this.sessionsByDate = sessionsByDate;
        }
    }

    private static final class DayPageData {
        private final List<JournalSessionIndex> sessions;
        private final int selectedIndex;
        private final String transcript;
        private final JournalArchivePage archivePage;
        private final CalendarComment calendarComment;
        private final IOException failure;
        private final boolean editComments;

        private DayPageData(
                List<JournalSessionIndex> sessions,
                int selectedIndex,
                String transcript,
                JournalArchivePage archivePage,
                CalendarComment calendarComment,
                IOException failure,
                boolean editComments) {
            this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
            this.selectedIndex = selectedIndex;
            this.transcript = transcript;
            this.archivePage = archivePage;
            this.calendarComment = calendarComment;
            this.failure = failure;
            this.editComments = editComments;
        }
    }

    private static final class AppTarget {
        private final String label;
        private final ComponentName component;
        private final UserHandle user;

        private AppTarget(String label, ComponentName component, UserHandle user) {
            this.label = label;
            this.component = component;
            this.user = user;
        }
    }

    private static final class AppTargetKey {
        private final UserHandle user;
        private final ComponentName component;

        private AppTargetKey(UserHandle user, ComponentName component) {
            this.user = user;
            this.component = component;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof AppTargetKey)) {
                return false;
            }
            AppTargetKey key = (AppTargetKey) other;
            return user.equals(key.user) && component.equals(key.component);
        }

        @Override
        public int hashCode() {
            return 31 * user.hashCode() + component.hashCode();
        }
    }

    private final class LargeTextAdapter extends ArrayAdapter<String> {
        private LargeTextAdapter(
                Context context,
                List<String> labels,
                boolean singleChoice) {
            super(
                    context,
                    singleChoice
                            ? android.R.layout.simple_list_item_single_choice
                            : android.R.layout.simple_list_item_1,
                    labels);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            if (row instanceof TextView) {
                TextView label = (TextView) row;
                label.setTextSize(LARGE_TEXT_SP);
                label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                label.setSingleLine(false);
                label.setMaxLines(Integer.MAX_VALUE);
                label.setEllipsize(null);
                label.setHorizontallyScrolling(false);
                label.setMinHeight(dp(64));
                label.setPadding(dp(12), dp(10), dp(12), dp(10));
                ViewGroup.LayoutParams parameters = label.getLayoutParams();
                if (parameters != null) {
                    parameters.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    label.setLayoutParams(parameters);
                }
            }
            return row;
        }
    }

    /** Virtualized full-text rows keep a long future-event list from creating every view at once. */
    private final class FutureEventAdapter extends ArrayAdapter<CalendarComment> {
        private FutureEventAdapter(Context context, List<CalendarComment> events) {
            super(context, 0, events);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView label = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(MainActivity.this);
            CalendarComment event = getItem(position);
            label.setText(FutureEntryPolicy.listLabel(event));
            label.setTextSize(LARGE_TEXT_SP);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setSingleLine(false);
            label.setMaxLines(Integer.MAX_VALUE);
            label.setEllipsize(null);
            label.setHorizontallyScrolling(false);
            label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            label.setMinHeight(dp(64));
            label.setPadding(dp(12), dp(10), dp(12), dp(10));
            label.setContentDescription(event == null
                    ? "Unreadable future event"
                    : FutureEntryPolicy.longDateLabel(event) + ". "
                    + FutureEntryPolicy.compactEventText(event.getText(), 240)
                    + ". Open calendar date.");
            return label;
        }
    }
}
