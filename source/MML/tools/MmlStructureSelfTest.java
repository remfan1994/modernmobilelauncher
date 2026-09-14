import java.nio.file.Files;
import java.nio.file.Path;

public final class MmlStructureSelfTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5 && arguments.length != 6) {
            throw new AssertionError(
                    "Pass build.gradle, manifest, MainActivity, JournalStore, "
                            + "CalendarProjection and MmlPresentationPolicy.");
        }
        String build = Files.readString(Path.of(arguments[0]));
        String manifest = Files.readString(Path.of(arguments[1]));
        String activity = Files.readString(Path.of(arguments[2]));
        String store = Files.readString(Path.of(arguments[3]));
        String calendar = Files.readString(Path.of(arguments[4]));
        Path javaSourceDirectory = Path.of(arguments[2]).getParent();
        String calendarComment = Files.readString(
                javaSourceDirectory.resolve("CalendarComment.java"));
        String commentText = Files.readString(javaSourceDirectory.resolve("CommentText.java"));
        String capturedNotification = Files.readString(
                javaSourceDirectory.resolve("CapturedNotification.java"));
        String futureEntryPolicy = Files.readString(
                javaSourceDirectory.resolve("FutureEntryPolicy.java"));
        String futureRefreshPolicy = Files.readString(
                javaSourceDirectory.resolve("FutureEntryRefreshPolicy.java"));
        String futureEntryCount = Files.readString(
                javaSourceDirectory.resolve("MmlFutureEntryCount.java"));
        String journalMaintenance = Files.readString(
                javaSourceDirectory.resolve("MmlJournalMaintenance.java"));
        String journalMaintenancePolicy = Files.readString(
                javaSourceDirectory.resolve("JournalMaintenancePolicy.java"));
        String textPolicy = Files.readString(
                javaSourceDirectory.resolve("JournalTextPolicy.java"));
        String boundedLineReader = Files.readString(
                javaSourceDirectory.resolve("BoundedUtf8LineReader.java"));
        String notificationListener = Files.readString(
                javaSourceDirectory.resolve("MmlNotificationListener.java"));
        String logVerbosity = Files.readString(
                javaSourceDirectory.resolve("LogVerbosity.java"));
        String logStorageMode = Files.readString(
                javaSourceDirectory.resolve("LogStorageMode.java"));
        String logStorage = Files.readString(
                javaSourceDirectory.resolve("MmlLogStorage.java"));
        String mirrorPolicy = Files.readString(
                javaSourceDirectory.resolve("MirrorFilePolicy.java"));
        String mirrorOwnershipPolicy = Files.readString(
                javaSourceDirectory.resolve("MirrorOwnershipPolicy.java"));
        String persistedGrantState = Files.readString(
                javaSourceDirectory.resolve("PersistedGrantState.java"));
        String processSession = Files.readString(
                javaSourceDirectory.resolve("MmlProcessSession.java"));
        String sessionIndex = Files.readString(
                javaSourceDirectory.resolve("JournalSessionIndex.java"));
        String currentLog = section(
                activity, "private void populateCurrentLog(", "private static boolean isCommentEvent(");
        String calendarUi = section(
                activity, "private void renderCalendar()", "private Button makeCalendarDayButton(");
        String futureEventsUi = section(
                activity, "private void renderFutureEvents()", "private void renderCalendar()");
        String calendarHomeActions = section(
                activity, "private LinearLayout calendarHomeActions()", "private void renderCalendar()");
        String futureNotificationUi = section(
                activity,
                "private void updateFutureEntryNotification(",
                "private String futureNotificationToken()");
        String dayUi = section(
                activity, "private void renderDay(", "private void pageDisplayedSession(");
        String appListUi = section(
                activity, "private void openAppList()", "private List<AppTarget> loadAppTargets()");
        String settingsUi = section(
                activity, "private void openMmlSettings()", "private void openSystemPanel(");
        String historyClear = section(
                activity, "private void clearJournalHistory()", "private void confirmClearCalendarComments(");
        String archivedLog = section(
                activity, "private static String archivedJournalText(",
                "private static String compactMachineEntry(");
        String systemBars = section(
                activity, "private void configureSystemBars()", "private boolean isLandscapeLayout()");
        String currentSessionComposer = section(
                activity, "private void addCurrentSessionComposer(", "private void addDayComposer(");
        String dayComposer = section(
                activity, "private void addDayComposer(", "private void setCalendarContentView(");
        String calendarBrightness = section(
                activity, "private void setCalendarContentView(", "private LinearLayout verticalRoot()");
        String largeTextAdapter = section(
                activity, "private final class LargeTextAdapter", "\n    }\n}");
        String captureClear = section(
                activity,
                "private void clearCapturedNotifications()",
                "private void followSystemBrightness()");
        String captureRefresh = section(
                activity,
                "private void refreshCapturedNotifications()",
                "private void reloadCurrentSessionWindow(");
        String maintenanceError = section(
                activity,
                "if (!result.getCompletedError().isEmpty()) {",
                "if (kind == MmlJournalMaintenance.Kind.COUNT_OLD) {");
        String saveCommentCompletion = section(
                activity,
                "if (kind == MmlJournalMaintenance.Kind.SAVE_COMMENT) {",
                "\n        invalidateArchivedCalendarIndex();");
        String listenerConnectedCallback = section(
                notificationListener,
                "public void onListenerConnected()",
                "public void onListenerDisconnected()");
        String listenerPostedCallback = section(
                notificationListener,
                "public void onNotificationPosted(",
                "/** Distinguishes a granted Android access switch");
        String captureClearWorker = section(
                notificationListener,
                "private static void clearCapturesOnWorker(",
                "private static void drainInboxOnWorker(");
        String partialGrantRecovery = section(
                logStorage,
                "private static IOException journalAndReleasePartialAcquire(",
                "private static void journalFailedAcquireForRelease(");
        Path presentationPath = arguments.length == 6
                ? Path.of(arguments[5])
                : Path.of(arguments[2]).resolveSibling("MmlPresentationPolicy.java");
        String presentation = Files.readString(presentationPath);

        require(build, "namespace \"vip.thatiam.mml\"");
        require(build, "applicationId \"vip.thatiam.mml\"");
        require(build, "minSdk 33");
        require(build, "targetSdk 35");
        require(build, "versionCode 15");
        require(build, "versionName \"1.6.6\"");

        require(manifest, "android.intent.category.HOME");
        require(manifest, "android.intent.category.DEFAULT");
        require(manifest, "android.intent.category.LAUNCHER");
        require(manifest, "android:allowBackup=\"false\"");
        require(manifest, "android:enableOnBackInvokedCallback=\"true\"");
        require(manifest, "android.hardware.touchscreen");
        require(manifest, "android.hardware.faketouch");
        if (count(manifest, "android:required=\"false\"") < 2) {
            throw new AssertionError("Touchscreen and faketouch must both be optional.");
        }
        require(manifest, "android.permission.WRITE_SETTINGS");
        require(manifest, "android.permission.POST_NOTIFICATIONS");
        require(manifest, "stateAlwaysHidden");
        reject(manifest, "stateAlwaysVisible");
        reject(manifest, "adjustResize");
        reject(manifest, "android:configChanges");
        reject(manifest, "android.permission.INTERNET");
        reject(manifest, "android.permission.CALL_PHONE");
        reject(manifest, "BIND_ACCESSIBILITY_SERVICE");
        require(manifest, "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE");
        require(manifest, "android.service.notification.NotificationListenerService");
        require(manifest, "android:name=\".MmlNotificationListener\"");
        require(manifest, "android:exported=\"false\"");

        require(activity, "private static String processSessionId");
        require(activity, "private static boolean processReturnHomeAfterExternalActivity");
        require(activity, "private static AppTargetKey processSelectedAppTargetKey");
        require(activity, "private final OnBackInvokedCallback backInvokedCallback");
        require(activity, "registerOnBackInvokedCallback");
        require(activity, "unregisterOnBackInvokedCallback");
        require(activity, "private void navigateBack()");
        reject(activity, "void onBackPressed()");
        reject(activity, "SOFT_INPUT_ADJUST_RESIZE");
        require(activity, "EXTRA_HANDLED_PROCESS_SESSION");
        require(activity, "STATE_PROCESS_SESSION");
        require(activity, "restoredSameProcessActivity");
        require(activity, "onSaveInstanceState(Bundle outState)");
        require(activity, "freshLauncherEntry");
        require(activity, "firstActivityForProcess || isFutureEntryNotificationActive()");
        require(activity, "isLauncherEntryIntent");
        require(activity, "markLauncherIntentHandled");
        require(activity, "MmlLogStorage.openJournalStore(this)");
        require(activity, "journalStore.startLauncherSession(\n"
                + "                        bootIdentity, now, currentZoneId(), selectedLogVerbosity())");
        require(activity, "reloadCurrentSessionWindow(processSessionId)");
        require(activity, "CURRENT_SESSION_EVENT_WINDOW = 160");
        require(activity, "CURRENT_SESSION_ENCODED_BYTE_WINDOW = 512 * 1024");
        require(activity, "appendToCurrentSessionWindow(appended)");
        require(activity, "appendVisibleCurrentLogRow(appended, windowAppend)");
        require(activity, "currentSessionWindowEncodedBytes");
        require(activity, "individually oversized entries remain");
        reject(activity, "@Override\n    @Override");
        require(activity, "renderRestoredProcessScreen()");
        require(activity, "processReturnHomeAfterExternalActivity = false");
        require(activity, "renderCalendar()");
        require(activity, "Current Session");
        require(activity, "FUTURE_EVENTS");
        require(activity, "renderFutureEvents()");
        reject(activity, "Current log - incomplete until this session closes");
        require(activity, "COMPACT_TIME_FORMAT =\n"
                + "            DateTimeFormatter.ofPattern(\"HH:mm\"");
        // New entries are Comments, while user_note remains readable for 1.2 compatibility.
        require(activity, "appendMachineEvent(\"comment\", message");
        require(activity, "user_note");
        require(activity, "isCommentEvent");
        require(activity, "Write a Comment");
        require(activity, "CommentText.forDisplay");
        reject(activity, "User note");
        reject(activity, "Write a note");
        require(activity, "messageInput.setText(processComposerDraft)");
        require(activity, "processComposerDraft = \"\"");
        require(activity, "releaseRenderedScreenReferences()");
        require(activity, "currentLogContainer = null");
        require(activity, "currentLogScroll = null");
        require(activity, "notificationCaptureStatusView = null");
        require(activity, "if (draft.isEmpty())");
        require(activity, "processDayCommentDrafts.remove(key)");
        require(activity, "if (scroll != currentLogScroll)");
        require(activity, "Button launcher = makeButton(\"Launcher\", this::openAppList)");
        require(activity, "launcher.setContentDescription(\"Open app list\")");
        require(activity, "launcher.setFocusableInTouchMode(true)");
        reject(activity, "PopupMenu");
        require(activity, "messageInput.setMinLines(currentSessionComposerMinimumLines())");
        require(activity, "private int currentSessionComposerMinimumLines() {");
        require(activity, "return 1;");
        require(currentSessionComposer, "input.setMinWidth(0)");
        require(currentSessionComposer, "input.setMinimumWidth(0)");
        require(currentSessionComposer, "composer.setOrientation(LinearLayout.VERTICAL)");
        require(currentSessionComposer, "input.setHorizontallyScrolling(false)");
        require(currentSessionComposer, "composer.addView(input, matchWrap())");
        require(currentSessionComposer, "actions.setOrientation(LinearLayout.HORIZONTAL)");
        require(currentSessionComposer, "actions.addView(launcher, weightedWrap())");
        require(currentSessionComposer, "actions.addView(submit, weightedWrap())");
        require(currentSessionComposer, "root.addOnLayoutChangeListener");
        require(currentSessionComposer, "constrainCurrentSessionInputHeight(");
        require(currentSessionComposer, "input.getLineHeight()");
        require(currentSessionComposer, "composerMaximumLines() * input.getLineHeight()");
        require(currentSessionComposer, "actions.getMeasuredHeight()");
        require(currentSessionComposer, "input.setMaxHeight(maximumInputHeight)");
        requireBefore(currentSessionComposer, "composer.addView(input, matchWrap())",
                "actions.addView(launcher, weightedWrap())");
        require(dayComposer, "composer.setOrientation(LinearLayout.HORIZONTAL)");
        require(dayComposer, "composer.addView(plus, wrapWrap())");
        require(dayComposer, "composer.addView(input, weightedWrap())");
        require(dayComposer, "composer.addView(submit, wrapWrap())");
        require(activity, "launcher.requestFocus()");
        require(activity, "if (scroll != currentLogScroll)");
        require(activity, "scroll.scrollTo(");
        require(activity, "LinearLayout transcriptPage");
        require(activity, "LinearLayout calendarPage");
        require(calendarHomeActions, "makeButton(\"Future events\", this::openFutureEvents)");
        require(calendarHomeActions, "makeButton(\"Current Session\", this::renderCurrentSession)");
        require(calendarHomeActions, "row.addView(futureEvents, weightedWrap(1f))");
        require(calendarHomeActions, "row.addView(currentSession, weightedWrap(2f))");
        if (count(calendarUi, "calendarHomeActions()") < 3) {
            throw new AssertionError(
                    "Calendar loading, failure and completed views must retain the 1:2 footer.");
        }
        require(futureEventsUi, "journalStore.listFutureCalendarComments(requestedNow)");
        require(futureEventsUi, "new FutureEventAdapter(this, futureEvents)");
        require(futureEventsUi, "list.setOnItemClickListener");
        require(futureEventsUi, "openFutureEvent(futureEvents.get(position))");
        require(futureEventsUi, "FutureEntryPolicy.longDateLabel(event)");
        require(futureEventsUi, "No future events.");
        require(appListUi, "root.addView(textView(\"App list\", 27), matchWrap())");
        require(appListUi, "makeButton(\"MML preferences\", this::openMmlSettings)");
        require(appListUi, "new LargeTextAdapter(this, labels, true)");
        require(appListUi, "target.component.flattenToShortString()");
        require(appListUi, "selectedAppIndex = resolveSelectedAppIndex()");
        require(appListUi, "list.setSelection(selectedAppIndex)");
        require(appListUi, "root.addView(list, listParameters)");
        require(appListUi, "\"Launch\", this::launchSelectedApp");
        require(appListUi, "\"App settings\", this::openSelectedAppDetails");
        requireBefore(appListUi, "root.addView(list, listParameters)",
                "\"Launch\", this::launchSelectedApp");
        reject(appListUi, "Current Session");
        reject(appListUi, "list.addHeaderView");
        reject(appListUi, "list.addFooterView");
        require(activity, "list.setOnItemSelectedListener");
        require(activity, "list.getSelectedItemPosition()");
        require(appListUi, "list.setLongClickable(false)");
        reject(appListUi, "list.setOnItemLongClickListener");
        require(appListUi, "handleAppListTap(list, position)");
        require(activity, "new GestureDetector(this");
        require(activity, "onDoubleTap(MotionEvent event)");
        require(activity, "onDoubleTapEvent(MotionEvent event)");
        require(activity, "list.pointToPosition((int) event.getX(), (int) event.getY())");
        require(activity, "getScaledTouchSlop()");
        require(activity, "> doubleTapTouchSlop * doubleTapTouchSlop");
        require(activity, "appTargetKey(appTargets.get(position))");
        require(activity, "onSingleTapUp(MotionEvent event)");
        require(activity, "lastAppListTapTargetKey = appTargetKey(appTargets.get(position))");
        require(activity, "pendingAppListDoubleTapPosition = position");
        require(activity, "cancel.setAction(MotionEvent.ACTION_CANCEL)");
        require(activity, "list.onTouchEvent(cancel)");
        require(activity, "if (!list.isInTouchMode())");
        require(activity, "resetAppListTapSequence()");
        require(activity, "launcherApps.startAppDetailsActivity");
        require(activity, "Settings.ACTION_APPLICATION_DETAILS_SETTINGS");
        require(activity, "processExternalReturnScreen = Screen.APP_LIST");
        require(activity, "new AppTargetKey(profile, component)");
        require(activity, "return user.equals(key.user) && component.equals(key.component)");
        require(activity, "return 31 * user.hashCode() + component.hashCode()");
        reject(activity, "profile.getIdentifier()");
        reject(activity, "user.getIdentifier()");
        if (count(activity, "new LargeTextAdapter(this, labels, false)") < 2) {
            throw new AssertionError(
                    "Immediate app-shortcut pickers must not display persistent radio controls.");
        }
        require(currentLog, "CommentText.forDisplay(event.getDetail())");
        reject(currentLog, "CommentText.forDisplay(oneLogicalLine(");
        require(currentLog, "row.setSingleLine(false)");
        require(currentLog, "row.setMaxLines(commentEvent ? Integer.MAX_VALUE : 3)");
        require(currentLog,
                "row.setEllipsize(commentEvent ? null : TextUtils.TruncateAt.END)");
        require(currentLog, "row.setHorizontallyScrolling(false)");
        reject(currentLog, "row.setMaxLines(1)");
        require(currentLog, "row.setOnClickListener(ignored -> showMachineEventDetails(event))");
        require(currentLog, "row.setOnClickListener(ignored -> replayCapturedNotification(event))");
        require(currentLog, "row.setOnLongClickListener");
        require(currentLog, "showCapturedNotificationDetails(event, null)");
        require(activity, "MAX_CURRENT_MACHINE_CODE_POINTS = 180");
        require(activity, "MAX_CURRENT_NOTIFICATION_CODE_POINTS = 240");
        require(activity, "destination.indexOf(value) < 0");
        reject(activity, "replaceAll(\"\\\\s+\"");
        require(activity, "private final ThreadPoolExecutor historyExecutor");
        require(activity, "new ArrayBlockingQueue<>(1)");
        require(activity, "historyExecutor.getQueue().clear()");
        require(activity, "historyExecutor.purge()");
        require(activity, "new ThreadPoolExecutor.DiscardOldestPolicy()");
        require(activity, "historyExecutor.shutdownNow()");
        require(activity, "requestGeneration != historyRequestGeneration");
        require(activity, "private List<JournalSessionIndex> archivedCalendarIndex()");
        require(activity, "private ProjectedCalendarIndex projectedCalendarIndex(");
        require(activity, "processProjectedCalendarIndex.system == system");
        require(activity, "journalStore.listCalendarSessionIndex()");
        require(activity, "invalidateArchivedCalendarIndex()");
        require(calendarUi, "submitLatestHistoryTask(() -> {");
        require(calendarUi, "loadCalendarPage(system, requestedMonthAnchor, zoneId)");
        reject(calendarUi, "journalStore.listSessions()");
        require(dayUi, "journalStore.loadArchivedSessionPage(");
        reject(dayUi, "journalStore.loadArchivedSessionSnapshot(");
        require(dayUi, "journalStore.findCalendarComment(targetLocalDate)");
        reject(activity, "safeListCalendarComments");
        require(activity, "session.getStatus() != SessionStatus.OPEN");
        require(activity, "archivedJournalText(archivePage)");
        require(activity, "Previous Log Page");
        require(activity, "Next Log Page");
        require(archivedLog, "page.getOversizedRowsSkipped()");
        reject(archivedLog, "[full session log]");
        require(store, "isAdministrativeBoundaryType(prefix.get(3))");
        require(activity, "CalendarProjection.dateKeyForGregorianLocalDate");
        require(activity, "CalendarProjection.gregorianLocalDate");
        require(activity, "closedCount + (comment == null ? 0 : 1)");
        require(activity, "No closed sessions belong to this date.");
        require(activity, "processDisplayedTargetLocalDate");
        require(activity, "processDisplayedTargetZoneId");
        require(activity, "processEditingDayComments");
        reject(activity, "No closed sessions on this date.");
        require(activity, "R.color.mml_today_background");
        require(activity, "R.color.mml_today_text");
        require(activity, "R.color.mml_calendar_date_text");
        require(activity, "R.color.mml_calendar_entry_total_text");
        require(activity, "R.color.mml_today_date_text");
        require(activity, "R.color.mml_today_entry_total_text");
        require(activity, "ForegroundColorSpan");
        require(activity, "RelativeSizeSpan");
        require(activity, "CALENDAR_DATE_TEXT_SCALE = 1.30f");
        require(activity, "ColorStateList.valueOf");
        require(activity, "Intent.ACTION_DATE_CHANGED");
        require(activity, "Intent.ACTION_TIME_CHANGED");
        require(activity, "Intent.ACTION_TIMEZONE_CHANGED");
        require(activity, "systemBroadcastReceiver");
        require(activity, "notificationCaptureReceiver");
        require(activity, "ACTION_LISTENER_STATE_CHANGED");
        require(activity, "Context.RECEIVER_EXPORTED");
        require(activity, "Context.RECEIVER_NOT_EXPORTED");
        require(activity, "MmlPresentationPolicy.useCalendarDayList");
        require(calendarUi, "root.setPadding(\n                dp(3)");
        require(calendarUi, "calendarPage.addView(calendarMonthControl(month.getTitle())");
        require(calendarUi, "calendarPage.addView(calendarContents, matchWrap())");
        require(calendarUi, "ScrollView calendarScroll = new ScrollView(this)");
        require(calendarUi, "setCalendarContentView(root)");
        require(calendarUi, "makeButton(\"Previous\"");
        require(calendarUi, "makeButton(\"Next\"");
        require(calendarUi, "int minimumDayHeight = dp(isLandscapeLayout() ? 56 : 64)");
        require(calendarUi, "button.setMinimumHeight(minimumDayHeight)");
        require(calendarBrightness,
                "private final class CalendarBrightnessFrame extends FrameLayout");
        require(calendarBrightness,
                "ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()");
        require(calendarBrightness, "onInterceptTouchEvent(MotionEvent event)");
        require(calendarBrightness, "requestDisallowInterceptTouchEvent(true)");
        require(calendarBrightness, "MmlPresentationPolicy.isCalendarBrightnessGesture(");
        require(calendarBrightness, "MmlPresentationPolicy.calendarBrightnessPercent(");
        require(calendarBrightness, "Settings.System.canWrite(MainActivity.this)");
        require(calendarBrightness, "writeSystemBrightnessPercent(requestedPercent)");
        require(calendarBrightness, "finishBrightnessGesture()");
        reject(calendarUi, "Entry count = one saved Comments page");
        reject(calendarUi, "makeButton(\"Previous month\"");
        reject(calendarUi, "makeButton(\"Next month\"");
        reject(activity, "private SeekBar calendarBrightnessControl()");
        reject(activity, "CalendarSwipeFrame");
        require(activity, "private void setCalendarContentView(View calendarRoot)");
        require(activity, "explainSystemBrightnessPermission()");
        require(activity, "followSystemBrightness()");
        require(activity, "removeRetiredWindowBrightnessPreferences()");
        require(activity, ".remove(\"window-brightness-enabled\")");
        require(activity, ".remove(\"window-brightness-percent\")");
        require(activity, "WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE");
        require(activity, "Settings.System.putInt(");
        reject(activity, "PREF_WINDOW_BRIGHTNESS_ENABLED");
        reject(activity, "PREF_WINDOW_BRIGHTNESS_PERCENT");
        reject(activity, "MML window brightness");
        require(activity, "private View calendarMonthControl(String monthTitle)");
        require(activity, "private boolean isLandscapeLayout()");
        require(activity, "Configuration.ORIENTATION_LANDSCAPE");
        require(activity, "return isLandscapeLayout() ? 2 : 4");
        require(activity, "LARGE_TEXT_SP = 20");
        require(activity, "Math.max(LARGE_TEXT_SP, sizeSp)");
        require(activity, "button.setSingleLine(false)");
        require(activity, "Typeface.DEFAULT, Typeface.BOLD");
        require(activity, "Settings.System.canWrite");
        require(activity, "boolean accessGranted = Settings.System.canWrite(this)");
        require(settingsUi, "textView(\"Settings\", 27)");
        require(settingsUi, "textView(\"Log storage\", 23)");
        require(settingsUi,
                "\"Specify user-managed log directory\", this::chooseLogDirectory");
        require(settingsUi,
                "\"Clear app-managed logs\", this::confirmClearAppManagedLogs");
        require(settingsUi, "Blank: app-managed logs");
        require(settingsUi, "Clear this field to return to app-managed logs.");
        require(settingsUi, "private String logStorageStatus()");
        require(settingsUi, "Log storage: app-managed.");
        require(settingsUi, "Log storage: user-managed at ");
        require(settingsUi, "private void chooseLogDirectory()");
        require(settingsUi, "private void useAppManagedLogStorage()");
        require(settingsUi, "MmlLogStorage.selectAppManagedLogging(this)");
        reject(settingsUi, "private void showLogDirectoryDialog()");
        reject(settingsUi, "private void disableLogDirectory()");
        reject(settingsUi, "not set - logging disabled");
        reject(settingsUi, "for (LogStorageMode mode : LogStorageMode.values())");
        reject(settingsUi, "confirmStoreLogsWithApp()");
        reject(settingsUi, "STORE LOGS WITH THE APP");
        reject(settingsUi, "STORE LOGS IN USER'S FILESPACE");
        require(settingsUi, "\"Open Android settings\"");
        require(settingsUi, "Settings.ACTION_SETTINGS");
        requireBefore(settingsUi, "textView(\"Settings\", 27)",
                "textView(\"Machine log detail\", 20)");
        requireBefore(settingsUi, "\"Open Android settings\"",
                "textView(\"Machine log detail\", 20)");
        reject(settingsUi, "MML settings");
        reject(activity, "Future-entry notification: enabled");
        reject(activity, "Notification capture: ");
        reject(activity, "This application shortcut was saved in the date's Comments.");
        require(activity, "Delete records older than N days");
        require(journalMaintenance, "store.clearArchivedSessions(preservedSessionId)");
        require(activity, "\"journal_history_cleared\"");
        reject(historyClear, "journalStore.appendEvent");
        require(journalMaintenance, "store.saveCalendarComment(");
        require(journalMaintenance, "store.clearCalendarComments()");
        require(activity, "CommentText.findAppShortcuts");
        require(activity, "openCommentAppShortcut");
        require(activity, "confirmLaunchCommentApp");
        require(activity, "LauncherApps");
        require(activity, "startMainActivity");
        if (count(activity, "appendMachineEvent(\"launch_requested\", detail, false);") < 3) {
            throw new AssertionError(
                    "App, Comment-app and Comment-URI launches must treat logging as best effort.");
        }
        reject(activity, "if (!appendMachineEvent(\"launch_requested\"");
        require(activity, "Linkify.WEB_URLS | Linkify.PHONE_NUMBERS");
        require(activity, "\"tel\".equals(scheme)");
        require(activity, "\"http\".equals(scheme) || \"https\".equals(scheme)");
        require(activity, "Intent.ACTION_DIAL");
        require(activity, "Intent.ACTION_VIEW");
        require(activity, "ActivityNotFoundException | SecurityException");
        reject(activity, "Intent.ACTION_CALL");
        reject(activity, "Intent.parseUri");
        reject(activity, "NotificationListenerService");
        require(activity, "NotificationChannel");
        require(activity, "NotificationManager.IMPORTANCE_LOW");
        require(activity, "Manifest.permission.POST_NOTIFICATIONS");
        require(activity, "PendingIntent.FLAG_IMMUTABLE");
        require(activity, "PendingIntent.FLAG_ONE_SHOT");
        require(activity, "PREF_FUTURE_NOTIFICATION_TOKEN");
        require(activity, "EXTRA_FUTURE_NOTIFICATION_TOKEN");
        require(activity, "isAuthenticatedFutureNotificationIntent");
        require(activity, "UUID.randomUUID()");
        require(activity, "setOnlyAlertOnce(true)");
        require(activity, "setAutoCancel(true)");
        require(activity, "setShowWhen(false)");
        require(futureEntryCount, "store.scanFutureCalendarComments(");
        require(futureEntryCount, "scan.getSummaries()");
        require(futureNotificationUi, "Notification.BigTextStyle");
        require(futureNotificationUi, "FutureEntryPolicy.notificationHeading()");
        require(futureNotificationUi, "FutureEntryPolicy.notificationBody(");
        require(futureNotificationUi, "snapshot.getSummaries()");
        require(futureNotificationUi, ".setStyle(expandedStyle)");
        require(futureNotificationUi, ".setContentTitle(FutureEntryPolicy.notificationHeading())");
        require(activity, "The future-event notification opened Future events.");
        require(logVerbosity, "future_event_opened");
        reject(futureNotificationUi, "Open MML Calendar");
        require(activity, "updateFutureEntryNotificationAfterCalendarMutation");
        require(activity, "snapshot.getCount() != snapshot.getBaselineCount()");
        require(activity, "isNotificationListenerAccessGranted");
        require(activity, "ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS");
        require(activity, "ACTION_NOTIFICATION_LISTENER_SETTINGS");
        require(activity, "PREF_CAPTURE_ENABLED");
        require(activity, "rebindNotificationCaptureIfNeeded");
        require(activity, "MmlNotificationListener.isListenerConnected()");
        require(activity, "MmlNotificationListener.requestListenerRebind(this)");
        require(activity, "refreshCapturedNotifications");
        require(activity, "clearCapturedNotifications");
        require(captureClear, "MmlNotificationListener.enqueueCapturedNotificationClear(");
        require(captureRefresh, "MmlNotificationListener.enqueueCapturedNotificationDrain(");
        require(captureRefresh, "scheduleCapturedNotificationWindowRefresh(false, 0L)");
        require(activity, "CAPTURE_REFRESH_DEBOUNCE_MILLIS = 100L");
        require(activity, "newSingleThreadScheduledExecutor");
        require(activity, "store.loadSessionWindow(");
        require(activity, "requestGeneration != captureRefreshRequestGeneration");
        require(activity, "displayedArchiveCursor = JournalArchiveCursor.first()");
        require(activity, "processDisplayedArchiveCursor = JournalArchiveCursor.first()");
        require(activity, "captureRefreshExecutor.shutdownNow()");
        require(activity, "private static long processObservedCaptureStorageGeneration");
        require(captureRefresh,
                "requestedStorageGeneration > processObservedCaptureStorageGeneration");
        require(captureRefresh, "captureRefreshTargetGeneration = Math.max(");
        require(activity,
                "storageState[1] > processObservedCaptureStorageGeneration");
        reject(activity, "private long observedCaptureStorageGeneration");
        reject(captureClear, "enqueueAfterPendingCaptures");
        reject(captureClear, "runOnUiThread");
        reject(captureClear, "loadSession(");
        reject(captureRefresh, "journalStore.drainCapturedNotifications(");
        require(activity, "reconcileCaptureStorageChanges");
        require(activity, "CapturedNotification.decodeDetail");
        require(activity, "bounded to \" + JournalStore.MAX_STORED_CAPTURE_COUNT");
        require(activity, "notificationCaptureStatusView");
        require(activity, "notification_action_opened");
        reject(activity, "No captured notifications are stored.");
        reject(activity, "setOngoing(true)");
        reject(activity, "journalStore.openOrCreate");
        reject(activity, "showSoftInput");
        reject(activity, "notesView.requestFocus");
        reject(activity, "fullScroll(");
        reject(activity, "Color.rgb");
        reject(activity, "setHeight(dp(");
        reject(activity, "dp(66)");
        reject(activity, "textView(weekday, 16)");
        reject(activity, "Previous record");
        reject(activity, "Next record");
        reject(dayUi, "\"Calendar\"");
        reject(dayUi, "\"Current Session\"");
        reject(dayUi, "\"MML settings\"");
        reject(dayUi, "\"Android settings\"");
        require(dayUi, "plus.setOnClickListener(ignored -> showAppShortcutPicker())");
        reject(activity, "openDayCommentActions");
        require(systemBars, "WindowInsets.Type.navigationBars()"
                + " | WindowInsets.Type.displayCutout()");
        require(systemBars, "WindowInsets.Type.ime()");
        require(systemBars, "Math.max(safeInsets.bottom, imeInsets.bottom)");
        require(systemBars,
                "WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE");
        require(systemBars, "controller.hide(WindowInsets.Type.statusBars())");
        require(activity, "onWindowFocusChanged(boolean hasFocus)");
        require(activity, "if (hasFocus) {\n            hideStatusBar();");
        reject(systemBars, "controller.hide(WindowInsets.Type.navigationBars())");
        reject(systemBars, "controller.hide(WindowInsets.Type.systemBars())");
        require(largeTextAdapter, "label.setSingleLine(false)");
        require(largeTextAdapter, "android.R.layout.simple_list_item_single_choice");
        require(largeTextAdapter, "android.R.layout.simple_list_item_1");
        require(largeTextAdapter, "label.setMaxLines(Integer.MAX_VALUE)");
        require(largeTextAdapter, "label.setEllipsize(null)");
        require(largeTextAdapter, "label.setHorizontallyScrolling(false)");
        require(largeTextAdapter, "parameters.height = ViewGroup.LayoutParams.WRAP_CONTENT");
        reject(activity, "FLAG_FULLSCREEN");
        reject(activity, "SYSTEM_UI_FLAG_HIDE_NAVIGATION");

        require(store, "startLauncherSession");
        require(store, "later_launcher_startup_observed");
        require(store, "does not prove a crash or a clean exit");
        require(store, "LogVerbosity recordingPolicy = verbosity == null ? LogVerbosity.DEFAULT : verbosity");
        require(store, "recordingPolicy.includesEventType(\n"
                + "                        \"later_launcher_startup_observed\")");
        require(store, "return createSession(bootIdentity, now, zoneId, recordingPolicy)");
        require(store, "verbosity.includesEventType(\"launcher_session_started\")");
        require(store, "recordingPolicy.includesEventType(\"shutdown_observed\")");
        require(store, "writeReplace(stateFile(sessionId), SessionStatus.CLOSED.name() + \"\\n\")");
        require(activity, "journalStore.markShutdownObserved(\n"
                + "                    currentSession.getId(),");
        require(activity, "\"Android shutdown signal was observed while MML was alive.\",\n"
                + "                    selectedLogVerbosity())");
        require(store, "FileOutputStream(target, true)");
        require(store, "appendEventRows(sessionId, accepted)");
        require(store, "storeCapturedNotificationsBatch(");
        require(store, "session.getStatus() != SessionStatus.OPEN");
        require(store, "clearArchivedSessions");
        require(store, "newestDurableTimestamp");
        require(store, "isAdministrativeBoundaryType");
        require(store, "prior_boot_boundary_inferred");
        require(store, "session_unclosed");
        require(store, "recordIdsOnDisk");
        require(store, "Set<String> ids = new LinkedHashSet<>()");
        require(store, "listOpenSessionIds()");
        require(store, "without materializing every archived event log");
        require(store, "listCalendarSessionIndex()");
        require(store, "public JournalSession loadArchivedSessionSnapshot(String id)");
        require(store, "MAX_LEGACY_SESSION_SNAPSHOT_BYTES");
        require(store, "MAX_LEGACY_SESSION_LIST_BYTES");
        require(store, "public JournalArchivePage loadArchivedSessionPage(");
        require(store, "MAX_ARCHIVE_PAGE_RECORDS = 160");
        require(store, "MAX_ARCHIVE_PAGE_BYTES = 512 * 1024");
        require(store, "BoundedUtf8LineReader.readLine(");
        require(store, "public synchronized JournalSessionWindow loadSessionWindow(");
        require(store, "readTailEvents(id, maximumEvents, maximumEncodedBytes)");
        require(store, "public synchronized boolean isSessionOpen(String id)");
        require(store, "LineCodec.decodePrefix(line, 4)");
        require(store, "LineCodec.decodePrefix(line, 5)");
        require(store, "isAdministrativeBoundaryType(prefix.get(3))");
        require(store, "DELETE_MARKER_SUFFIX");
        require(store, "CALENDAR_COMMENT_MARKER");
        require(store, "new File(journalRoot, \"calendar-comments\")");
        require(store, "saveCalendarComment");
        require(store, "listCalendarComments");
        require(store, "clearCalendarComments");
        require(store, "MAX_CALENDAR_COMMENT_COUNT = 4_096");
        require(store, "MAX_CALENDAR_COMMENT_BYTES = 16 * 1024 * 1024");
        require(store, "CALENDAR_COMMENTS_MUTATION_LOCK");
        reject(store, "public synchronized int countCalendarComments");
        reject(store, "public synchronized int countFutureCalendarComments");
        require(store, "CommentText.hasPayload");
        require(store, "writeReplace(calendarCommentsFile");
        require(store, "appendCapturedNotification");
        require(store, "appendCapturedNotificationResult");
        require(store, "queueCapturedNotification");
        require(store, "queueCapturedNotificationResult");
        require(store, "CaptureDisposition");
        require(store, "AT_CAPACITY");
        require(store, "drainCapturedNotifications");
        require(store, "CAPTURED_NOTIFICATION_EVENT_TYPE");
        require(store, "clearCapturedNotifications");
        require(store, "CAPTURE_CLEAR_MARKER_NAME");
        require(store, "MAX_PENDING_CAPTURE_COUNT = 512");
        require(store, "MAX_STORED_CAPTURE_COUNT = 512");
        require(store, "PROCESS_STORAGE_LOCK");
        require(store, "Runnable durableMutationObserver");
        require(store, "notifyDurableMutation()");
        require(store, "capturedNotificationInboxById");
        require(store, "collectCapturedNotificationIds(eventsFile(id), result)");
        require(store, "if (result.size() >= MAX_STORED_CAPTURE_COUNT)");
        require(store, "while (destination.size() < MAX_STORED_CAPTURE_COUNT");
        require(store, "addCaptureIdsBounded(result, pending)");
        require(store, "CAPTURE_ID_CACHE_EPOCH_BY_ROOT");
        require(store, "currentEpoch != scanEpoch");
        require(store, "CAPTURE_ID_CACHE_PENDING_BY_ROOT");
        require(store, "writeCapturedNotificationInbox");
        require(store, "int pendingLimit = Math.min(MAX_PENDING_CAPTURE_COUNT, available)");
        require(store, "while (pending.size() > pendingLimit)");
        require(store, "row.size() >= 4");
        require(store, "deleteOrphanedReplacementFiles(notificationInboxFile)");
        require(store, "deleteOrphanedEventReplacementFiles");
        require(store, "BufferedWriter writer = new BufferedWriter");
        require(store, "moveReplacement(temporary, file)");
        reject(store, "StringBuilder retained = new StringBuilder()");
        require(store, "readStatus(sessionId) != SessionStatus.OPEN");
        reject(store, "FileOutputStream(notificationInboxFile, true)");
        reject(store, "Files.readString");

        require(calendarComment, "final class CalendarComment");
        require(calendarComment, "targetLocalDate");
        require(calendarComment, "targetZoneId");
        require(calendarComment, "createdEpochMillis");
        require(calendarComment, "createdZoneId");
        require(calendarComment, "text");

        require(commentText, "final class CommentText");
        require(commentText, "forDisplay");
        require(commentText, "hasPayload");
        require(commentText, "appShortcutToken");
        require(commentText, "findAppShortcuts");
        require(commentText, "COMPLETE_PACKAGE.matcher");
        require(commentText, "return \"app:\" + value");

        require(capturedNotification, "DETAIL_MARKER = \"NC1\"");
        require(capturedNotification, "CAPTURE_ID_HEX_LENGTH = 64");
        require(capturedNotification, "sha256CaptureId");
        require(capturedNotification, "MessageDigest.getInstance(\"SHA-256\")");
        require(capturedNotification, "encodeDetail");
        require(capturedNotification, "decodeDetail");
        require(capturedNotification, "compactDisplay");
        require(capturedNotification, "fullDisplay");

        require(futureEntryPolicy, "countFutureEntries");
        require(futureEntryPolicy, "futureEntries");
        require(futureEntryPolicy, "target.isAfter(todayAtTarget)");
        require(futureEntryPolicy, "CommentText.hasPayload");
        require(futureEntryPolicy, "notificationTitle");
        require(futureEntryPolicy, "notificationHeading");
        require(futureEntryPolicy, "notificationBody");
        require(futureEntryPolicy, "compactEventText");
        require(store, "listFutureCalendarComments");
        require(store, "scanFutureCalendarComments");
        require(store, "FutureCalendarCommentScan");

        require(futureRefreshPolicy, "enum Mode");
        require(futureRefreshPolicy, "existing == Mode.ALWAYS || incoming == Mode.ALWAYS");
        require(futureRefreshPolicy, "existing == Mode.MUTATION_BOUNDARY");
        require(futureRefreshPolicy, "existingRequest || incomingRequest");
        require(futureRefreshPolicy, "completedGeneration > acknowledgedGeneration");
        require(futureRefreshPolicy, "shouldClaimCompletion(");
        require(futureEntryCount, "if (running) {");
        require(futureEntryCount, "requestEpoch++");
        require(futureEntryCount, "FutureEntryRefreshPolicy.merge(");
        require(futureEntryCount, "claimCompletedSnapshot(long previouslyConsumedGeneration)");
        require(futureEntryCount, "acknowledgedGeneration = generation;");
        require(activity, "private static long processObservedFutureEntryCountGeneration");
        require(activity, "MmlFutureEntryCount.claimCompletedSnapshot(");
        reject(activity, "MmlFutureEntryCount.acknowledge(");
        reject(activity, "private long observedFutureEntryCountGeneration");
        require(activity, "MmlFutureEntryCount.ACTION_COUNT_CHANGED");
        reject(activity, "MmlFutureEntryCount.UpdatePolicy");

        require(journalMaintenance, "Process-global single-flight coordinator");
        require(journalMaintenance, "private static boolean busy");
        require(journalMaintenance, "ACTION_OPERATION_CHANGED");
        require(journalMaintenance, "beginCalendarCommentMutation(");
        require(journalMaintenance, "beginAppManagedLogClear(");
        require(journalMaintenance, "CLEAR_APP_MANAGED_LOGS");
        require(journalMaintenance, "store.clearAppManagedLogs(preservedSessionId)");
        require(journalMaintenance, "private static long acknowledgedGeneration");
        require(journalMaintenance, "JournalMaintenancePolicy.canBegin(");
        require(journalMaintenance, "acknowledgeCompletion(long completedGeneration)");
        require(journalMaintenancePolicy, "generation > acknowledgedGeneration");
        require(activity, "processHandledMaintenanceGeneration");
        require(activity, "MmlJournalMaintenance.begin(");
        require(activity, "MmlJournalMaintenance.beginCalendarCommentMutation(");
        require(activity, "MmlJournalMaintenance.beginAppManagedLogClear(");
        require(activity, "private static boolean processAppManagedLogClearInProgress");
        require(activity, "private void confirmClearAppManagedLogs()");
        require(activity, "private void clearAppManagedLogs()");
        require(activity, "restoreWritableSessionAfterAppManagedLogClear()");
        require(store, "public synchronized int clearAppManagedLogs(");
        require(store, "completePendingAppManagedLogClear()");
        require(store, "CLEAR-APP-MANAGED-LOGS-1");
        reject(store, "resetAppManagedLogs(");
        reject(store, "class AppManagedLogReset");
        require(activity, "consumeMaintenanceCompletion(");
        require(activity, "confirmation\n                && result.getCompletedError().isEmpty()\n"
                + "                && result.getCompletedValue() > 0");
        require(activity, "setDayCommentMutationControlsEnabled(false)");
        require(activity, "Wait for journal maintenance to finish; the draft is preserved.");
        require(dayUi, "!maintenance.isBusy() && !maintenance.isCompletionPending()");
        require(maintenanceError, "boolean archiveMayHaveChanged");
        require(maintenanceError, "boolean commentsMayHaveChanged");
        require(maintenanceError, "renderRestoredProcessScreen()");
        require(maintenanceError, "processDisplayedArchiveCursor = JournalArchiveCursor.first()");
        require(maintenanceError, "result.getCompletedAuxiliaryValue() >= 0");
        require(maintenanceError, "FutureEntryRefreshPolicy.Mode.MUTATION_BOUNDARY");
        require(journalMaintenance, "auxiliary = -1;");
        require(saveCommentCompletion, "screen == Screen.SETTINGS");
        require(saveCommentCompletion, "refreshMmlSettingsWithoutAuditEvent()");
        require(activity,
                "The user may leave Settings while the process-global mutation is still running.");
        require(activity, "Journal maintenance continues if this screen rotates.");
        reject(activity, "maintenanceExecutor");
        reject(activity, "WeakReference<MainActivity>");

        require(textPolicy, "MAX_INPUT_UTF16_UNITS = 32_768");
        require(textPolicy, "MAX_UTF8_BYTES = 128 * 1024");
        require(textPolicy, "MAX_DAY_DRAFTS = 16");
        require(boundedLineReader, "byte[] scan = new byte[8_192]");
        require(boundedLineReader, "input.read(scan");

        require(notificationListener, "extends NotificationListenerService");
        require(notificationListener, "MmlLogStorage.openJournalStore(context)");
        require(notificationListener, "onNotificationPosted");
        require(notificationListener, "onListenerDisconnected");
        require(notificationListener, "requestListenerRebind(this)");
        require(notificationListener, "requestRebind(new ComponentName(context, MmlNotificationListener.class))");
        require(notificationListener, "REBIND_RETRY_INTERVAL_MILLIS = 5_000L");
        require(notificationListener, "SystemClock.elapsedRealtime()");
        require(notificationListener, "rebindRequested");
        require(notificationListener, "new ThreadPoolExecutor(");
        require(notificationListener, "new ArrayBlockingQueue<>(JournalStore.MAX_STORED_CAPTURE_COUNT)");
        require(notificationListener, "List<CapturedNotification> activeSnapshot = activeNotificationSnapshots()");
        require(notificationListener, "CaptureStorageTask.captureBatch(");
        require(notificationListener, "CaptureStorageTask.captureOne(");
        require(notificationListener, "CAPTURE_EXECUTOR.execute(task)");
        require(notificationListener, "enqueueCapturedNotificationClear");
        require(notificationListener, "enqueueCapturedNotificationDrain");
        require(notificationListener, "enum TaskKind");
        require(notificationListener, "CaptureMaintenanceSnapshot");
        reject(notificationListener, "private final Runnable action");
        reject(notificationListener, "enqueueAfterPendingCaptures");
        reject(listenerConnectedCallback, "->");
        reject(listenerPostedCallback, "->");
        require(notificationListener, "captureStorageGeneration()");
        require(notificationListener, "captureStorageState()");
        require(notificationListener, "notifyCapturedNotificationsCleared(Context context)");
        require(notificationListener, "notifyCaptureStorageChanged(Context context)");
        require(notificationListener, "removeQueuedCaptureWithKey");
        require(notificationListener, "removeOldestQueuedCapture");
        require(notificationListener, "!((CaptureStorageTask) queued).storageFence");
        require(notificationListener, "captureBatchOnWorker(");
        require(notificationListener, "captureNotificationOnWorker(");
        require(notificationListener, "MAX_ACTIVE_SNAPSHOT_COUNT = 512");
        require(notificationListener, "MAX_REPLAY_SCAN_COUNT = 512");
        require(notificationListener, "snapshot = notificationSnapshot(");
        require(notificationListener, "active[index] = null");
        require(notificationListener, "getActiveNotifications()");
        require(notificationListener, "sourcePackage.equals(getPackageName())");
        require(notificationListener, "Notification.FLAG_GROUP_SUMMARY");
        require(notificationListener, "CapturedNotification.sha256CaptureId");
        require(notificationListener, "stableNotificationIdentity");
        require(notificationListener, "appendCapturedNotification");
        require(notificationListener, "queueCapturedNotification");
        require(notificationListener,
                "storageChanged |= store.queueCapturedNotificationResult(capture)");
        require(notificationListener, "CaptureDisposition.STORED");
        require(notificationListener, "ACTION_NOTIFICATION_CAPTURED");
        require(notificationListener, "ACTION_LISTENER_STATE_CHANGED");
        require(notificationListener, "notifyListenerStateChanged()");
        require(notificationListener, "WeakReference<MmlNotificationListener>");
        require(notificationListener, "enum ReplayResult");
        require(notificationListener, "replayCapturedNotification");
        require(notificationListener, "getActiveNotifications()");
        require(notificationListener, "sameCapturedSnapshot");
        require(captureClearWorker,
                "Captured-notification clearing failed: \" + safeMessage(error),\n"
                        + "                    true);");
        reject(captureClearWorker,
                "Captured-notification clearing failed: \" + safeMessage(error),\n"
                        + "                    false);");
        require(notificationListener, "PendingIntent tapAction = notification.contentIntent");
        require(notificationListener, "tapAction.send();");
        require(notificationListener, "Notification.FLAG_AUTO_CANCEL");
        require(notificationListener, "cancelNotification(statusBarNotification.getKey())");
        require(notificationListener, "No PendingIntent or raw notification key is reconstructed");
        reject(notificationListener, "Objects.hash");
        reject(notificationListener, "contentFingerprint");
        reject(capturedNotification, "PendingIntent");
        reject(store, "PendingIntent");

        require(logVerbosity, "NOTIFICATIONS_ONLY");
        require(logVerbosity, "IMPORTANT");
        require(logVerbosity, "ACTIONS");
        require(logVerbosity, "FULL");
        require(logVerbosity, "DEFAULT = IMPORTANT");
        require(logVerbosity, "PREFERENCE_KEY = \"log-verbosity\"");
        require(logVerbosity, "includesEventType");
        require(logVerbosity, "includesNotifications");
        require(logVerbosity, "\"comment\".equals(type) || \"user_note\".equals(type)");
        require(logVerbosity, "CAPTURED_NOTIFICATION_EVENT_TYPE.equals(type)");
        require(activity, "selectedLogVerbosity().includesEventType(type)");
        require(currentLog, "verbosity.includesEventType(event.getType())");
        require(settingsUi, "for (LogVerbosity verbosity : LogVerbosity.values())");
        reject(archivedLog, "selectedLogVerbosity");

        require(logStorageMode, "APP-MANAGED LOGS");
        require(logStorageMode, "USER-MANAGED LOG DIRECTORY");
        require(logStorageMode, "DEFAULT = WITH_APP");
        require(logStorage, "public static boolean isUserManagedLoggingConfigured(");
        require(logStorage, "public static boolean isAppManagedLoggingActive(");
        require(logStorage, "public static boolean isUserAccessibleLoggingConfigured(");
        require(logStorage, "selectedMode(context) == LogStorageMode.USER_FILESPACE");
        require(logStorage, "public static String selectedLogDirectoryDisplayName(");
        require(logStorage, "return selectedRoot + \"/\" + MIRROR_DIRECTORY_NAME");
        require(logStorage, "public static boolean selectAppManagedLogging(");
        require(logStorage, "public static boolean disableUserAccessibleLogging(");
        require(logStorage, "UserOperationKind.SELECT_APP_MANAGED");
        reject(logStorage, "public static boolean activateWithApp(");
        require(logStorage, "Intent.FLAG_GRANT_READ_URI_PERMISSION");
        require(logStorage, "Intent.FLAG_GRANT_WRITE_URI_PERMISSION");
        require(logStorage, "releasePersistableUriPermission");
        require(logStorage, "new File(context.getFilesDir(), \"mml-journal\")");
        require(logStorage, "MIRROR_DEBOUNCE_MILLIS = 5_000L");
        require(logStorage, "MML-filespace-mirror");
        require(logStorage, "runConfiguredMirrorLoopGuarded(context)");
        require(logStorage, "scheduleConfiguredMirrorRunner(context, delayMillis)");
        require(logStorage, "recordMirrorFailureBestEffort(context, failure)");
        requireBefore(
                section(
                        logStorage,
                        "private static void runConfiguredMirrorLoopGuarded(",
                        "private static void runConfiguredMirrorLoop("),
                "mirrorRunnerScheduled = false;",
                "mirrorRunnerScheduled = true;");
        reject(logStorage, "() -> runConfiguredMirrorLoop(context)");
        reject(logStorage, "() -> runConfiguredMirrorLoop(applicationContext)");
        require(logStorage, "MAX_DIRECTORY_ENTRIES = 8_192");
        require(logStorage, "MAX_DIRECTORY_METADATA_CHARACTERS = 1_048_576");
        require(logStorage, "MAX_MIME_TYPE_CHARACTERS = 256");
        require(logStorage, "MAX_INDEX_BYTES = 512L * 1024L");
        require(logStorage, "MAX_INDEX_ENTRIES = 8_192");
        require(logStorage, "new BoundedInputStream(stream, MAX_INDEX_BYTES)");
        reject(logStorage, "new BoundedInputStream(stream, MAX_INDEX_BYTES + 1L)");
        require(logStorage, "new DirectorySnapshot(byName, scannedEntries)");
        require(logStorage, "mirrorChildren.physicalEntryCount == 0");
        reject(logStorage, "mirrorChildren.byName.isEmpty()");
        String directoryListing = section(
                logStorage,
                "private static DirectorySnapshot listChildren(",
                "private static Uri requireDirectory(");
        require(directoryListing, "byName.containsKey(name)");
        require(directoryListing, "duplicate display name");
        requireBefore(directoryListing, "duplicate display name", "byName.put(");
        reject(directoryListing, "|| byName.containsKey(name))");
        require(directoryListing, "seenDocumentIds.add(documentId)");
        require(directoryListing, "one document identity under multiple");
        require(directoryListing, "scannedMetadataCharacters");
        require(directoryListing, "MAX_DIRECTORY_METADATA_CHARACTERS");
        require(directoryListing, "mime.length() > MAX_MIME_TYPE_CHARACTERS");
        String directoryCreation = section(
                logStorage,
                "private static Uri requireDirectory(",
                "private static DocumentEntry requireFile(");
        String fileCreation = section(
                logStorage,
                "private static DocumentEntry requireFile(",
                "private static long querySize(");
        requireBefore(directoryCreation, "validateCreatedIdentity(", "children.byName.put(");
        requireBefore(fileCreation, "validateCreatedIdentity(", "children.byName.put(");
        require(directoryCreation,
                "children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, requiredSlots");
        require(fileCreation,
                "children.physicalEntryCount, MAX_DIRECTORY_ENTRIES, 1");
        require(directoryCreation, "refreshDirectorySnapshot(resolver, parent, children)");
        require(fileCreation, "refreshDirectorySnapshot(resolver, parent, children)");
        require(logStorage, "private static void requireExactDocumentIdentity(");
        require(logStorage, "DocumentsContract.Document.COLUMN_MIME_TYPE");
        if (count(logStorage, "MirrorOwnershipPolicy.hasPhysicalEntryCapacity(") < 2) {
            throw new AssertionError(
                    "Directory and file creation must both use physical-row capacity.");
        }
        if (count(logStorage, "children.physicalEntryCount++;") < 2) {
            throw new AssertionError(
                    "Every successful provider create must consume one physical-row slot.");
        }
        reject(logStorage, "children.byName.size() >= MAX_DIRECTORY_ENTRIES");
        reject(logStorage, "physicalEntryCount--");
        require(logStorage, "recordCoordinatorResult(failure)");
        if (count(logStorage, "catch (ClassCastException | IllegalArgumentException ignored)") < 4) {
            throw new AssertionError("All log-storage preference reads must be type tolerant.");
        }
        require(mirrorPolicy, "sourceLength > indexedLength");
        require(mirrorPolicy, "targetName.endsWith(\".events.txt\")");
        require(logStorage, "MirrorFilePolicy.canAppendEventTail(");
        require(logStorage, "MirrorFilePolicy.canReuseFinal(");
        require(logStorage, "DocumentsContract.Document.COLUMN_SIZE");
        require(logStorage, "existing.size");
        require(logStorage, "PENDING_SUFFIX = \".MML-pending.txt\"");
        require(logStorage, "SESSION_MIRROR_TEXT_SUFFIX = \".txt\"");
        require(logStorage, "mirrorSessionFileName(source.getName())");
        require(logStorage, "expectedDirectory != actualDirectory");
        reject(logStorage, "!expectedMimeType.equals(actualMimeType)");
        reject(logStorage, "DocumentsContract.renameDocument");
        require(logStorage, "promotePending(");
        require(logStorage, "pendingWriteExists");
        require(mirrorOwnershipPolicy, "requiredSlotsBeforePending");
        require(mirrorOwnershipPolicy, "hasIndexEntryCapacity");
        require(mirrorOwnershipPolicy, "isStableRefreshEntry");
        String synchronizeMirror = section(
                logStorage,
                "private static void synchronizeToTree(",
                "private static IndexSnapshot selectReadableIndex(");
        requireBefore(
                synchronizeMirror,
                "preflightLocalMirrorIndex(",
                "treeDocumentUri(treeUri)");
        require(synchronizeMirror, "encodeIndex(localManifest)");
        require(synchronizeMirror, "MirrorOwnershipPolicy.hasIndexEntryCapacity(");
        require(synchronizeMirror,
                "resolver, selectedRoot, selectedChildren, MIRROR_DIRECTORY_NAME, 1");
        require(synchronizeMirror,
                "resolver, mirrorRoot, mirrorChildren, SESSION_DIRECTORY_NAME, 2");
        requireBefore(
                synchronizeMirror,
                "removeStaleManagedSessions(",
                "mirrorFile(\n                    resolver,\n                    sessionRoot");
        reject(logStorage, "removePendingDocuments(");
        require(logStorage, "PENDING_ACQUIRE_URI_PREFERENCE_KEY");
        require(logStorage, "PENDING_RELEASE_URIS_PREFERENCE_KEY");
        require(logStorage, "takePersistableUriPermission");
        require(logStorage, "reconcilePendingGrantTransitions(applicationContext)");
        require(logStorage, "persistedPermissionFlags(context, treeUri)");
        require(logStorage, "return -1;");
        require(logStorage, "PersistedGrantState.UNKNOWN");
        require(logStorage, "setUnresolvedGrantRecovery(true)");
        require(logStorage, "userOperationBusy || unresolvedGrantRecovery");
        require(logStorage,
                "pendingAcquireUri(context) != null || !pendingReleaseUris(context).isEmpty()");
        reject(logStorage, "hasPersistedPermission");
        require(persistedGrantState, "UNKNOWN");
        require(persistedGrantState, "PARTIAL");
        requireBefore(
                partialGrantRecovery,
                ".putString(\n                        PENDING_RELEASE_URIS_PREFERENCE_KEY",
                "if (!clearPendingAcquire(context, treeUri))");
        require(partialGrantRecovery, "heldFlags & REQUIRED_GRANT_FLAGS");
        require(logStorage, "OperationSnapshot operationSnapshot()");
        require(logStorage, "UserOperationKind.DIRECTORY_SELECTION");
        require(activity, "MmlLogStorage.operationSnapshot()");
        require(activity, "ACTION_LOG_STORAGE_OPERATION_CHANGED");
        reject(logStorage, "interface ActivationCallback");
        reject(activity, "failure -> runOnUiThread");
        reject(logStorage, "containsOnlyGeneratedRootEntries");
        require(logStorage, "MirrorOwnershipPolicy.mayUseDirectory(");
        require(logStorage, "MirrorFilePolicy.mayRemoveStale(");
        require(logStorage, "previousIndex.containsKey(\"sessions/\" + baseName)");
        require(logStorage, "recoverUnindexedFile(");
        require(logStorage, "recoverUnindexedBytes(");
        require(logStorage, "unindexedConflict(");
        require(logStorage, "storedFileMatches(");
        require(logStorage, "storedBytesMatch(");
        require(logStorage, "writeOwnedIndexBytes(");
        require(logStorage, "hasValidOwnershipMarker(");
        require(logStorage, "updatedLength < 0L");
        require(mirrorPolicy, "unknownLengthVerified");
        require(mirrorPolicy, "mayRecoverUnindexedPending");
        require(mirrorPolicy, "mayRemoveStale");
        require(mirrorOwnershipPolicy, "mayHealReservedControlDocument");
        require(mirrorOwnershipPolicy, "mayManagePendingSidecar");
        String optionalMirror = section(
                logStorage,
                "private static void mirrorOptionalFile(",
                "private static void mirrorFile(");
        require(optionalMirror, "children.byName.containsKey(pendingName)");
        require(optionalMirror, "throw unindexedConflict(pendingName)");
        reject(optionalMirror, "removeDocumentIfPresent(");
        String mirrorFileSidecars = section(
                logStorage,
                "private static void mirrorFile(",
                "private static void mirrorBytes(");
        require(mirrorFileSidecars, "storedFileMatches(");
        requireBefore(
                mirrorFileSidecars,
                "throw unindexedConflict(targetName + PENDING_SUFFIX)",
                "writeFile(resolver, parent, children, targetName");
        String mirrorByteSidecars = section(
                logStorage,
                "private static void mirrorBytes(",
                "private static boolean establishIndependentReadmeMarkerIfRequired(");
        require(mirrorByteSidecars, "storedBytesMatch(");
        requireBefore(
                mirrorByteSidecars,
                "throw unindexedConflict(targetName + PENDING_SUFFIX)",
                "writeBytes(resolver, parent, children, targetName");
        String solePendingReadmeRecovery = section(
                logStorage,
                "private static boolean establishIndependentReadmeMarkerIfRequired(",
                "private static void recoverUnindexedFile(");
        require(solePendingReadmeRecovery, "validPendingReadme");
        require(solePendingReadmeRecovery, "MirrorOwnershipPolicy.mayManagePendingSidecar(");
        require(solePendingReadmeRecovery, "writeFinalControlBytesWhilePreservingPending(");
        require(solePendingReadmeRecovery, "writeStream(resolver, independentFinal.uri");
        require(solePendingReadmeRecovery, "verifyStoredBytes(");
        requireBefore(
                solePendingReadmeRecovery,
                "verifyStoredBytes(",
                "removeDocumentIfPresent(");
        String ownedIndexWrite = section(
                logStorage,
                "private static void writeOwnedIndexBytes(",
                "private static boolean hasValidOwnershipMarker(");
        require(ownedIndexWrite, "readIndex(resolver, pending).valid");
        require(ownedIndexWrite, "MirrorOwnershipPolicy.mayManagePendingSidecar(");
        require(ownedIndexWrite, "writeFinalControlBytesWhilePreservingPending(");
        String lowLevelFileWrite = section(
                logStorage, "private static void writeFile(", "private static void writeBytes(");
        String lowLevelByteWrite = section(
                logStorage,
                "private static void writeBytes(",
                "private static void validateStoredDocument(");
        String storedDocumentValidation = section(
                logStorage,
                "private static void validateStoredDocument(",
                "private static void verifyStoredFile(");
        requireBefore(
                lowLevelFileWrite,
                "preflightPendingCreation(",
                "requireFile(resolver, parent, children, pendingName)");
        requireBefore(
                lowLevelFileWrite,
                "validateStoredDocument(\n                resolver, pending",
                "verifier.verify(pending.uri)");
        requireBefore(
                lowLevelFileWrite,
                "verifier.verify(pending.uri)",
                "promotePending(");
        require(lowLevelByteWrite, "verifier.verify(pending.uri)");
        reject(storedDocumentValidation, "DocumentVerifier verifier");
        reject(storedDocumentValidation, "verifier.verify(");
        require(storedDocumentValidation, "querySize(resolver, entry.uri)");
        requireBefore(
                lowLevelByteWrite,
                "preflightPendingCreation(",
                "requireFile(resolver, parent, children, pendingName)");
        String pendingCapacity = section(
                logStorage,
                "private static void preflightPendingCreation(",
                "private static void requireFileKind(");
        require(pendingCapacity, "requiredSlotsBeforePending(");
        require(pendingCapacity, "refreshDirectorySnapshot(resolver, parent, children)");
        if (count(pendingCapacity, "throw unindexedConflict(pendingName)") < 2) {
            throw new AssertionError(
                    "Pending creation must reject sidecars before and after a capacity refresh.");
        }
        require(pendingCapacity, "MAX_DIRECTORY_ENTRIES - 1");
        String snapshotRefresh = section(
                logStorage,
                "private static void refreshDirectorySnapshot(",
                "private static long querySize(");
        require(snapshotRefresh, "MirrorOwnershipPolicy.isStableRefreshEntry(");
        require(snapshotRefresh, "changed identity during synchronization");
        require(snapshotRefresh, "appeared during synchronization");
        reject(snapshotRefresh, "children.byName.clear()");
        reject(snapshotRefresh, "children.byName.putAll(");
        String pendingPromotion = section(
                logStorage,
                "private static void promotePending(",
                "private static void writeStream(");
        require(pendingPromotion, "Keep the already-validated pending bytes intact");
        reject(pendingPromotion, "renameDocument");
        requireBefore(
                pendingPromotion,
                "validateStoredDocument(resolver, target",
                "verifier.verify(target.uri)");
        requireBefore(
                pendingPromotion,
                "verifier.verify(target.uri)",
                "removeDocumentIfPresent(resolver, children, pendingName)");
        String staleSessionCleanup = section(
                logStorage,
                "private static void removeStaleManagedSessions(",
                "private static void removeDocumentIfPresent(");
        require(staleSessionCleanup, "File liveSource = liveFiles.get(baseName)");
        require(staleSessionCleanup, "storedFileMatches(");
        requireBefore(
                staleSessionCleanup,
                "storedFileMatches(",
                "deleteDocument(resolver, child.getValue().uri)");
        requireBefore(
                staleSessionCleanup,
                "throw unindexedConflict(name)",
                "deleteDocument(resolver, child.getValue().uri)");
        String optionalMirrorCleanup = section(
                logStorage,
                "private static void mirrorOptionalFile(",
                "private static void mirrorFile(");
        reject(optionalMirrorCleanup,
                "children.byName.remove(targetName);\n"
                        + "                children.byName.remove(targetName);");
        require(logStorage, "clear the directory field in");
        require(logStorage,
                "The user is responsible for deleting those files with a file manager.");
        reject(manifest, "android.permission.MANAGE_EXTERNAL_STORAGE");
        reject(manifest, "android.permission.READ_EXTERNAL_STORAGE");
        reject(manifest, "android.permission.WRITE_EXTERNAL_STORAGE");

        require(processSession, "static volatile String activeSessionId");
        require(processSession, "static void activate");

        require(sessionIndex, "final class JournalSessionIndex");
        require(sessionIndex, "List<DateObservation> dateObservations");
        reject(sessionIndex, "JournalEvent");
        reject(sessionIndex, "JournalNote");
        reject(sessionIndex, "getDetail()");

        require(presentation, "ACCESSIBLE_CALENDAR_LIST_SCALE = 1.5f");
        require(presentation, "NARROW_CALENDAR_LIST_SCALE = 1.3f");
        require(presentation, "useCalendarDayList");
        require(presentation, "BRIGHTNESS_FULL_RANGE_WIDTH_FRACTION = 0.75f");
        require(presentation, "isCalendarBrightnessGesture");
        require(presentation, "calendarBrightnessPercent");
        require(calendar, "ISLAMIC_CIVIL");
        require(calendar, "ISLAMIC_UMALQURA");
        require(calendar, "HebrewCalendar");
        require(calendar, "CalendarSystem.HEBREW");
        require(calendar, "HebrewCalendar.ADAR_1");
        require(calendar, "\"Adar II\"");
        require(calendar, "probe.add(Calendar.MONTH, 1)");
        require(calendar, "ChineseCalendar");
        require(calendar, "Calendar.IS_LEAP_MONTH");
        require(calendar, "dateKeyForGregorianLocalDate");
        require(calendar, "atTime(12, 0)");
        require(calendar, "gregorianLocalDate");
        System.out.println("MML structure self-test passed.");
    }

    private static void require(String source, String token) {
        if (!source.contains(token)) {
            throw new AssertionError("Missing required token: " + token);
        }
    }

    private static void reject(String source, String token) {
        if (source.contains(token)) {
            throw new AssertionError("Forbidden token: " + token);
        }
    }

    private static int count(String source, String token) {
        int total = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            total++;
            offset += token.length();
        }
        return total;
    }

    private static void requireBefore(String source, String firstToken, String secondToken) {
        int first = source.indexOf(firstToken);
        int second = source.indexOf(secondToken);
        if (first < 0 || second < 0 || first >= second) {
            throw new AssertionError(
                    "Required token order not found: " + firstToken + " before " + secondToken);
        }
    }

    private static String section(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        if (start < 0) {
            throw new AssertionError("Missing section start: " + startToken);
        }
        int end = source.indexOf(endToken, start + startToken.length());
        if (end < 0) {
            throw new AssertionError("Missing section end: " + endToken);
        }
        return source.substring(start, end);
    }
}
