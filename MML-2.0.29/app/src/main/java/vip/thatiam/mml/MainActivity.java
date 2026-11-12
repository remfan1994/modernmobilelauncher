package vip.thatiam.mml;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Humble log-first Home: capture, inspect, launch apps, and accept shared text. */
public final class MainActivity extends Activity implements Journal.Observer {
  private static final int PICK_FOLDER = 10, PERMISSIONS = 12, ENTRY_NOTIFICATION_PERMISSION = 13;
  private static final int PAGE = 100;
  private static final ThreadPoolExecutor journalChecks =
      new ThreadPoolExecutor(
          0,
          1,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          task -> new Thread(task, "mml-journal-check"));

  private final ThreadPoolExecutor reader =
      new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
  private final Handler main = new Handler(Looper.getMainLooper());

  private Journal journal;
  private Ui ui;
  private SharedPreferences prefs;
  private LinearLayout root;
  private ListView list;
  private LogAdapter logAdapter;
  private Button latest;
  private TextView storageBanner, brightnessHeading;
  private CharSequence brightnessHeadingText = "";
  private LogGroups.Group detailGroup;
  private final ArrayList<String> detailUids = new ArrayList<>();
  private String appInfoPackage = "", appPickerQuery = "";

  private String screen = "HOME", pendingOpenEntryUid;
  private boolean mirrorSyncPending;
  private Entry[] pendingNotificationCurrent;
  private TextView pendingNotificationSurface;
  private Button pendingNotificationButton;
  private int logDetailLevel = LogDetail.CUSTOM, customLogMask = LogDetail.DEFAULT_CUSTOM;
  private long before;
  private final ArrayList<Long> pageBack = new ArrayList<>();
  private int pendingSystemBrightnessPercent = -1;
  private boolean brightnessAccessRequested;
  private int generation;
  private boolean live = true, logLoading, refreshPending, captureTesting, journalChecking, resumed;
  private int logScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
  private long logRequest, logScrollRevision, actionRequest, journalCheckRequest;
  private Future<?> queryTask, actionTask, journalCheckTask;
  private CancellationSignal queryCancellation, journalCheckCancellation;

  private final Runnable restoreBrightnessHeading =
      () -> {
        if (brightnessHeading != null) brightnessHeading.setText(brightnessHeadingText);
      };

  private final Runnable mirrorSync =
      () -> {
        mirrorSyncPending = false;
        if (resumed && !isDestroyed()) PlainTextMirror.request(this);
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    reader.allowCoreThreadTimeOut(true);
    prefs = getSharedPreferences("mml2", 0);
    logDetailLevel = LogDetail.normalize(prefs.getInt("log_detail_mode_v2", LogDetail.CUSTOM));
    customLogMask = prefs.getInt("custom_log_mask_v2", LogDetail.DEFAULT_CUSTOM);
    prefs.edit().remove("log_brightness_percent").apply();
    journal = Journal.get(this);
    EntryNotifications.removeLegacySettingsNotification(this);
    if (state == null) restoreEntryNotifications();
    if (state != null) {
      screen = state.getString("screen", "HOME");
      before = state.getLong("before", 0);
      appInfoPackage = state.getString("app_info_package", "");
      appPickerQuery = state.getString("app_picker_query", "");
      ArrayList<String> savedDetailUids = state.getStringArrayList("detail_uids");
      if (savedDetailUids != null) detailUids.addAll(savedDetailUids);
      long[] cursors = state.getLongArray("page_back");
      if (cursors != null) for (long cursor : cursors) pageBack.add(cursor);
    }
    if (!screen.matches("HOME|SETTINGS|APPS|DETAIL|APP_INFO|NOTIFIED")) screen = "HOME";
    if ("DETAIL".equals(screen) && detailUids.isEmpty()) screen = "HOME";
    if ("APP_INFO".equals(screen) && appInfoPackage.isEmpty()) screen = "APPS";
    render();
    if (state == null) handleIncomingIntent(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (handleIncomingIntent(intent)) return;
    if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) returnToHomeFromSystemHome();
  }

  @Override
  protected void onUserLeaveHint() {
    super.onUserLeaveHint();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    journal.observe(this);
    journal.refreshStorageStatus();
    journal.sessionReminder();
    HistoryCollector.setActive(true);
    PlainTextMirror.setActive(true);
    HistoryCollector.request(this);
    PlainTextMirror.request(this);
    if (brightnessAccessRequested) {
      brightnessAccessRequested = false;
      if (pendingSystemBrightnessPercent >= 1 && canWriteSystemBrightness()) {
        int pending = pendingSystemBrightnessPercent;
        pendingSystemBrightnessPercent = -1;
        setSystemBrightnessPercent(pending);
      } else if (!canWriteSystemBrightness()) pendingSystemBrightnessPercent = -1;
    }
    if ("SETTINGS".equals(screen) || "APP_INFO".equals(screen) || "NOTIFIED".equals(screen)) render();
    else if ("HOME".equals(screen)) refreshLog(false);
    consumePendingOpenEntry();
  }

  @Override
  protected void onPause() {
    resumed = false;
    main.removeCallbacks(mirrorSync);
    mirrorSyncPending = false;
    HistoryCollector.setActive(false);
    PlainTextMirror.setActive(false);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    journal.unobserve(this);
    cancelReads();
    reader.shutdownNow();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("screen", screen);
    state.putLong("before", before);
    state.putString("app_info_package", appInfoPackage);
    state.putString("app_picker_query", appPickerQuery);
    state.putStringArrayList("detail_uids", new ArrayList<>(detailUids));
    long[] cursors = new long[pageBack.size()];
    for (int i = 0; i < cursors.length; i++) cursors[i] = pageBack.get(i);
    state.putLongArray("page_back", cursors);
  }

  @Override
  public void onBackPressed() {
    switch (screen) {
      case "APP_INFO": show("APPS"); break;
      case "SETTINGS": show("APPS"); break;
      case "NOTIFIED": show("SETTINGS"); break;
      case "DETAIL":
      case "APPS": show("HOME"); break;
      default: break;
    }
  }

  @Override
  public void changed() {
    if (!resumed || isDestroyed()) return;
    scheduleMirrorSync();
    updateStorageStatus();
    if ("HOME".equals(screen) && before == 0 && live) {
      if (logScrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) refreshLog(false);
      else refreshPending = true;
    }
  }

  private void scheduleMirrorSync() {
    if (!resumed || mirrorSyncPending || prefs.getString("log_tree", "").isEmpty()) return;
    mirrorSyncPending = true;
    main.postDelayed(mirrorSync, 5000);
  }

  private void beginNewEntry() {
    appPickerQuery = "";
    show("APPS");
  }

  private void addBloodguiltWarning() {
    Entry warning =
        Entry.event(
            "SYSTEM",
            "MML",
            "home_reminder",
            "Bloodguilt curse",
            "Cows aren't killers, and beef is the bloodshed of the innocent.\n"
                + "www.bloodguiltcurse.net has the Bible study.");
    journal.submit(
        warning,
        (ok, message) -> {
          if (!ok) showText("Bloodguilt curse", message);
        });
  }

  private void show(String destination) {
    cancelBrightnessGesture();
    hideKeyboard();
    screen = destination;
    if ("HOME".equals(destination)) {
      before = 0;
      pageBack.clear();
      live = true;
      detailGroup = null;
      detailUids.clear();
    }
    render();
  }

  private void render() {
    cancelBrightnessGesture();
    main.removeCallbacks(restoreBrightnessHeading);
    generation++;
    cancelReads();
    logLoading = false;
    refreshPending = false;
    ui = new Ui(this);
    root = ui.column();
    root.setFocusableInTouchMode(true);
    getWindow().setDecorFitsSystemWindows(false);
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          Insets edges =
              insets.getInsets(
                  WindowInsets.Type.systemBars()
                      | WindowInsets.Type.displayCutout()
                      | WindowInsets.Type.ime());
          view.setPadding(edges.left, edges.top, edges.right, edges.bottom);
          return WindowInsets.CONSUMED;
        });
    list = null;
    logAdapter = null;
    latest = null;
    storageBanner = brightnessHeading = null;
    getWindow().setBackgroundDrawable(new ColorDrawable(ui.background));
    getWindow().setStatusBarColor(Ui.DARK_GRAY);
    getWindow().setNavigationBarColor(Ui.DARK_GRAY);
    followSystemBrightness();
    setContentView(root);
    WindowInsetsController bars = getWindow().getInsetsController();
    if (bars != null) {
      int mask =
          WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
              | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
      bars.setSystemBarsAppearance(0, mask);
    }
    root.requestApplyInsets();
    switch (screen) {
      case "SETTINGS": settings(); break;
      case "APPS": apps(); break;
      case "DETAIL": detail(); break;
      case "APP_INFO": appInfo(); break;
      case "NOTIFIED": notifiedLogs(); break;
      default: home(); break;
    }
  }

  private void home() {
    TextView header =
        ui.text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM uuuu")), ui.size);
    ui.bold(header);
    ui.reverse(header);
    header.setGravity(Gravity.CENTER);
    header.setMinHeight(ui.dp(48));
    header.setOnClickListener(v -> addBloodguiltWarning());
    header.setContentDescription("Add another Bloodguilt curse warning");
    root.addView(header);
    brightnessHeading = header;
    brightnessHeadingText = header.getText();

    storageBanner = ui.text("", Math.max(16, ui.size - 6));
    ui.bold(storageBanner);
    storageBanner.setMinHeight(ui.dp(48));
    storageBanner.setOnClickListener(v -> showStorageStatus());
    root.addView(storageBanner);
    updateStorageStatus();

    if (before != 0) {
      LinearLayout paging = ui.row();
      Button latestPage =
          ui.button(
              "Latest",
              () -> {
                before = 0;
                pageBack.clear();
                render();
              });
      Button newer =
          ui.button(
              "Newer",
              () -> {
                before = pageBack.isEmpty() ? 0 : pageBack.remove(pageBack.size() - 1);
                render();
              });
      ui.equal(paging, latestPage);
      ui.equal(paging, newer);
      root.addView(paging);
    } else {
      latest = ui.button("Latest entries", this::followLatest);
      latest.setVisibility(View.GONE);
      root.addView(latest);
    }

    list = brightnessList();
    ui.styleList(list);
    list.setStackFromBottom(true);
    list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
    list.setOverScrollMode(View.OVER_SCROLL_NEVER);
    logAdapter = new LogAdapter();
    list.setAdapter(logAdapter);
    list.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (logAdapter.olderRow() && position == 0) {
            pageBack.add(before);
            before = logAdapter.minimum;
            render();
            return;
          }
          int index = position - logAdapter.offset();
          if (index >= 0 && index < logAdapter.groups.size()) openEntry(logAdapter.groups.get(index));
        });
    list.setOnScrollListener(
        new AbsListView.OnScrollListener() {
          @Override
          public void onScrollStateChanged(AbsListView view, int state) {
            logScrollState = state;
            if (state == SCROLL_STATE_TOUCH_SCROLL) logScrollRevision++;
            if (state == SCROLL_STATE_IDLE && refreshPending && !logLoading && before == 0 && live) {
              refreshPending = false;
              refreshLog(false);
            }
          }

          @Override
          public void onScroll(AbsListView view, int first, int visible, int total) {
            // Do not change transcript mode while a finger/flick is settling. Doing so at the
            // last row made ListView rebuild its anchor and caused the downward tail glitch.
            live = total == 0 || first + visible >= total;
            updateLatestControl();
          }
        });
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

    Button make = ui.button("Make a log entry", this::beginNewEntry);
    root.addView(make, new LinearLayout.LayoutParams(-1, -2));
    refreshLog(true);
  }

  private void apps() {
    boolean brick = prefs.getBoolean("app_picker_brick", false);
    EditText find = ui.input("Find an app");
    find.setSingleLine(true);
    find.setText(appPickerQuery);
    if (!appPickerQuery.isEmpty()) find.setSelection(find.length());
    root.addView(find, new LinearLayout.LayoutParams(-1, -2));

    AppPickerAdapter adapter = new AppPickerAdapter(brick);
    adapter.filter(appPickerQuery);
    android.widget.AdapterView<?> choices;
    View choicesView;
    if (brick) {
      GridView grid = new GridView(this);
      grid.setBackgroundColor(ui.background);
      grid.setSelector(new ColorDrawable(0x00000000));
      grid.setNumColumns(GridView.AUTO_FIT);
      grid.setColumnWidth(ui.dp(96));
      grid.setHorizontalSpacing(ui.dp(4));
      grid.setVerticalSpacing(ui.dp(4));
      grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
      grid.setGravity(Gravity.CENTER_HORIZONTAL);
      grid.setAdapter(adapter);
      choices = grid;
      choicesView = grid;
    } else {
      ListView appList = new ListView(this);
      ui.styleList(appList);
      appList.setAdapter(adapter);
      choices = appList;
      choicesView = appList;
    }
    root.addView(choicesView, new LinearLayout.LayoutParams(-1, 0, 1));

    Button settings = ui.button("Settings", () -> show("SETTINGS"));
    root.addView(settings, new LinearLayout.LayoutParams(-1, -2));

    choices.setOnItemClickListener(
        (parent, view, position, id) -> submitAppLaunch(adapter.visible.get(position)));
    choices.setOnItemLongClickListener(
        (parent, view, position, id) -> {
          openAppInfo(adapter.visible.get(position));
          return true;
        });

    find.addTextChangedListener(
        new TextWatcher() {
          @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
          @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
            appPickerQuery = text == null ? "" : text.toString();
            adapter.filter(appPickerQuery);
          }
          @Override public void afterTextChanged(Editable text) {}
        });

    final int request = generation;
    reader.execute(
        () -> {
          List<AppItem> result = loadApps();
          main.post(
              () -> {
                if (isDestroyed() || request != generation || !"APPS".equals(screen)) return;
                adapter.setAll(result);
              });
        });
  }

  private void openAppInfo(AppItem item) {
    if (item == null) return;
    appInfoPackage = item.packageName;
    show("APP_INFO");
  }

  private void appInfo() {
    if (appInfoPackage == null || appInfoPackage.isEmpty()) {
      show("APPS");
      return;
    }
    PackageManager packages = getPackageManager();
    String label = appInfoPackage;
    String version = "Version unavailable";
    try {
      ApplicationInfo info = packages.getApplicationInfo(appInfoPackage, 0);
      CharSequence loaded = packages.getApplicationLabel(info);
      if (loaded != null && loaded.length() > 0) label = loaded.toString();
      version = appVersion(packages.getPackageInfo(appInfoPackage, 0));
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}

    TextView heading = ui.heading(label);
    heading.setGravity(Gravity.CENTER_HORIZONTAL);
    root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

    TextView details =
        ui.text("Package\n" + appInfoPackage + "\n\n" + version, ui.size);
    ui.selectable(details);
    root.addView(details, new LinearLayout.LayoutParams(-1, 0, 1));

    Button androidSettings =
        ui.button(
            "Android app settings (Force stop)",
            () ->
                open(
                    new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + appInfoPackage))));
    root.addView(androidSettings, new LinearLayout.LayoutParams(-1, -2));
  }

  private void submitAppLaunch(AppItem item) {
    if (item == null) return;
    Entry entry = Entry.appLaunch(item.packageName, item.label);
    journal.submit(
        entry,
        (ok, message) -> {
          if (!ok) {
            showText("Log entry not saved", message);
            return;
          }
          show("HOME");
          launchApp(item.packageName);
        });
  }

  private void launchApp(String packageName) {
    if (packageName == null || packageName.trim().isEmpty()) {
      showText("App unavailable", "No package was recorded for this app.");
      return;
    }
    Intent launchIntent;
    try {
      launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
    } catch (RuntimeException error) {
      showText("App unavailable", Journal.describe(error));
      return;
    }
    if (launchIntent == null) {
      showText("App unavailable", "Android cannot find a launchable activity for " + packageName + ".");
      return;
    }
    try {
      startActivity(launchIntent);
    } catch (RuntimeException error) {
      showText("App unavailable", Journal.describe(error));
    }
  }

  private boolean handleIncomingIntent(Intent intent) {
    if (intent == null) return false;
    if (EntryNotifications.ACTION_OPEN_LOG.equals(intent.getAction())) {
      pendingOpenEntryUid = intent.getStringExtra(EntryNotifications.EXTRA_UID);
      setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN));
      if (!"HOME".equals(screen)) show("HOME");
      if (resumed) consumePendingOpenEntry();
      return true;
    }
    if (!Intent.ACTION_SEND.equals(intent.getAction())) return false;
    String type = intent.getType();
    if (type != null && !type.startsWith("text/")) return false;
    CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    String text = shared == null ? "" : shared.toString();
    if (text.trim().isEmpty()) return true;
    Entry entry = Entry.sharedText(text);
    journal.submit(
        entry,
        (ok, message) -> {
          if (!ok) showText("Shared text not logged", message);
        });
    // Replace the share intent so Activity recreation cannot append it a second time.
    setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN));
    if (!"HOME".equals(screen)) show("HOME");
    return true;
  }

  private void consumePendingOpenEntry() {
    String uid = pendingOpenEntryUid;
    if (uid == null || uid.isEmpty() || !resumed) return;
    pendingOpenEntryUid = null;
    openEntryByUid(uid);
  }

  private void openEntryByUid(String uid) {
    if (uid == null || uid.isEmpty()) {
      showText("Log entry", "This log entry is no longer available.");
      return;
    }
    final int request = generation;
    reader.execute(
        () -> {
          Entry entry = journal.findByUid(uid);
          main.post(
              () -> {
                if (!resumed || isDestroyed() || request != generation) return;
                if (entry == null) {
                  showText("Log entry", "This log entry is no longer available.");
                  return;
                }
                List<LogGroups.Group> groups =
                    LogGroups.group(Collections.singletonList(entry));
                if (!groups.isEmpty()) openEntry(groups.get(0));
              });
        });
  }

  private List<AppItem> loadApps() {
    PackageManager packages = getPackageManager();
    LinkedHashMap<String, AppItem> byPackage = new LinkedHashMap<>();
    try {
      List<ResolveInfo> resolved =
          packages.queryIntentActivities(
              new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
      for (ResolveInfo info : resolved) {
        if (Thread.currentThread().isInterrupted()) break;
        if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
        String packageName = info.activityInfo.packageName;
        if (byPackage.containsKey(packageName)) continue;
        String label = packageName;
        try {
          CharSequence loaded = info.loadLabel(packages);
          if (loaded != null && loaded.length() > 0) label = loaded.toString();
        } catch (RuntimeException ignored) {}
        byPackage.put(packageName, new AppItem(label, packageName));
      }
    } catch (RuntimeException error) {
      main.post(() -> showText("Apps could not load", Journal.describe(error)));
    }
    ArrayList<AppItem> result = new ArrayList<>(byPackage.values());
    Collator order = Collator.getInstance();
    result.sort(
        (a, b) -> {
          int byLabel = order.compare(a.label, b.label);
          return byLabel != 0 ? byLabel : a.packageName.compareTo(b.packageName);
        });
    return result;
  }

  private final class AppPickerAdapter extends BaseAdapter {
    final List<AppItem> all = new ArrayList<>(), visible = new ArrayList<>();
    final boolean brick;
    String query = "";

    AppPickerAdapter(boolean brick) {
      this.brick = brick;
    }

    void setAll(List<AppItem> values) {
      all.clear();
      all.addAll(values);
      filter(query);
    }

    void filter(String value) {
      query = value == null ? "" : value;
      visible.clear();
      String local = query.toLowerCase(Locale.getDefault());
      String packageQuery = query.toLowerCase(Locale.ROOT);
      for (AppItem item : all)
        if (local.isEmpty()
            || item.label.toLowerCase(Locale.getDefault()).contains(local)
            || item.packageName.toLowerCase(Locale.ROOT).contains(packageQuery))
          visible.add(item);
      notifyDataSetChanged();
    }

    @Override public int getCount() { return visible.size(); }
    @Override public Object getItem(int position) { return visible.get(position); }
    @Override public long getItemId(int position) { return visible.get(position).packageName.hashCode(); }
    @Override public boolean hasStableIds() { return true; }

    @Override
    public View getView(int position, View reusable, ViewGroup parent) {
      LinearLayout row;
      if (reusable instanceof LinearLayout && ((LinearLayout) reusable).getChildCount() == 1)
        row = (LinearLayout) reusable;
      else {
        row = ui.column();
        TextView name = ui.text("", brick ? Math.max(16, ui.size - 8) : ui.size);
        name.setGravity(brick ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        name.setSingleLine(true);
        name.setEllipsize(null);
        row.addView(name, new LinearLayout.LayoutParams(-1, -1));
      }
      AppItem item = visible.get(position);
      TextView name = (TextView) row.getChildAt(0);
      name.setText(brick ? brickLabel(item.label) : item.label);
      if (brick) name.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
      else name.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
      row.setMinimumHeight(ui.dp(brick ? 56 : 52));
      row.setPadding(brick ? ui.dp(3) : ui.dp(8), ui.dp(3), brick ? ui.dp(3) : ui.dp(8), ui.dp(3));
      ui.bindListRow(row, brick);
      return row;
    }
  }

  private static String brickLabel(String label) {
    String value = label == null ? "" : label.trim();
    if (value.isEmpty()) return "?";
    int count = value.codePointCount(0, value.length());
    if (count <= 7) return value;
    return value.substring(0, value.offsetByCodePoints(0, 7));
  }

  private static final class AppItem {
    final String label, packageName;
    AppItem(String label, String packageName) {
      this.label = label;
      this.packageName = packageName;
    }
  }

  private static String appVersion(PackageInfo installed) {
    String name = installed.versionName;
    String code = Long.toString(installed.getLongVersionCode());
    return name == null || name.trim().isEmpty()
        ? "Version " + code
        : "Version " + Entry.clip(name, 256) + " (" + code + ")";
  }

  private void refreshLog(boolean force) {
    if (logAdapter == null || list == null || isDestroyed()) return;
    if (!force && (before != 0 || !live)) return;
    if (logLoading) {
      if (!force) {
        refreshPending = true;
        return;
      }
      cancelQuery();
    }
    logLoading = true;
    refreshPending = false;
    final long read = ++logRequest;
    final int request = generation;
    final long cursor = before;
    final long scrollRevision = logScrollRevision;
    final boolean tail = force || live;
    final CancellationSignal cancellation = new CancellationSignal();
    queryCancellation = cancellation;
    queryTask =
        reader.submit(
            () -> {
              try {
                Journal.EntryPage window =
                    journal.homeWindow(cursor, PAGE, logDetailLevel, customLogMask, cancellation);
                List<Entry> rows = window.entries;
                boolean older = window.hasMore;
                long minimum = rows.isEmpty() ? 0 : rows.get(rows.size() - 1).rowId;
                Collections.reverse(rows);
                List<LogGroups.Group> groups = LogGroups.group(rows);
                main.post(
                    () -> {
                      if (isDestroyed()
                          || generation != request
                          || read != logRequest
                          || logAdapter == null) return;
                      logLoading = false;
                      boolean again = refreshPending;
                      refreshPending = false;
                      if (before != cursor) return;
                      if (logScrollRevision != scrollRevision
                          && logScrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        refreshPending = true;
                        return;
                      }
                      if (!force && !live) return;
                      logAdapter.rows = rows;
                      logAdapter.groups = groups;
                      logAdapter.older = older;
                      logAdapter.minimum = minimum;
                      // Keep transcript mode disabled. The tail is positioned explicitly below;
                      // changing transcript mode at the last row causes a visible ListView jump.
                      logAdapter.notifyDataSetChanged();
                      if (tail
                          && logScrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                          && logAdapter.getCount() > 0)
                        list.post(() -> list.setSelection(logAdapter.getCount() - 1));
                      if (again) refreshLog(false);
                    });
              } catch (OperationCanceledException ignored) {
                // New screen/read owns the UI.
              } catch (Exception error) {
                main.post(
                    () -> {
                      if (isDestroyed() || generation != request || read != logRequest) return;
                      logLoading = false;
                      boolean again = refreshPending;
                      refreshPending = false;
                      if (force) showText("Journal", Journal.describe(error));
                      if (again) refreshLog(false);
                    });
              }
            });
  }

  private final class LogAdapter extends BaseAdapter {
    List<Entry> rows = new ArrayList<>();
    List<LogGroups.Group> groups = new ArrayList<>();
    boolean older;
    long minimum;

    boolean olderRow() { return older; }
    int offset() { return older ? 1 : 0; }
    @Override public int getCount() { return groups.size() + offset(); }
    @Override public Object getItem(int position) { return older && position == 0 ? null : groups.get(position - offset()); }
    @Override public long getItemId(int position) { return older && position == 0 ? -1 : groups.get(position - offset()).stableId(); }
    @Override public boolean hasStableIds() { return true; }

    @Override
    public View getView(int position, View reusable, ViewGroup parent) {
      LinearLayout row;
      if (reusable instanceof LinearLayout && ((LinearLayout) reusable).getChildCount() == 4)
        row = (LinearLayout) reusable;
      else {
        row = ui.listRow();
        row.setPadding(0, ui.dp(5), 0, ui.dp(5));
        TextView heading = ui.text("", ui.size);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(ui.dp(12), ui.dp(3), ui.dp(12), 0);
        row.addView(heading);
        TextView time = ui.text("", Math.max(16, ui.size - 5));
        time.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        time.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        row.addView(time);
        TextView body = ui.text("", Math.max(18, ui.size - 2));
        body.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        body.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), 0);
        row.addView(body);
        TextView detail = ui.text("", Math.max(14, ui.size - 8));
        detail.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        detail.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2));
        row.addView(detail);
      }
      TextView heading = (TextView) row.getChildAt(0);
      TextView time = (TextView) row.getChildAt(1);
      TextView body = (TextView) row.getChildAt(2);
      TextView detail = (TextView) row.getChildAt(3);
      row.setMinimumHeight(ui.dp(48));
      if (older && position == 0) {
        heading.setText("Earlier entries");
        heading.setVisibility(View.VISIBLE);
        time.setVisibility(View.GONE);
        body.setVisibility(View.GONE);
        detail.setVisibility(View.GONE);
        ui.bindListRow(row, true);
        return row;
      }
      LogGroups.Group group = groups.get(position - offset());
      Entry e = group.first;
      boolean notice = "home_reminder".equals(e.type);
      String title = group.heading();
      if ("DEVICE".equals(e.kind) && !title.isEmpty())
        title = title.substring(0, 1).toUpperCase(Locale.ROOT) + title.substring(1);
      if (title.isEmpty()) title = e.type;
      heading.setText(title);
      heading.setMaxLines(notice ? Integer.MAX_VALUE : 3);
      heading.setEllipsize(notice ? null : android.text.TextUtils.TruncateAt.END);
      heading.setVisibility(View.VISIBLE);
      String when = entryTime(e);
      if (group.size() > 1 && group.last.timeMs != e.timeMs) when += "-" + entryTime(group.last);
      time.setText(when);
      time.setVisibility(View.VISIBLE);
      String content = e.body;
      body.setText(previewText(content));
      body.setVisibility(content.isEmpty() || content.equals(e.title) ? View.GONE : View.VISIBLE);
      body.setMaxLines(notice ? Integer.MAX_VALUE : 6);
      body.setEllipsize(notice ? null : android.text.TextUtils.TruncateAt.END);
      String source = e.source + " / " + e.type;
      if (e.notifiedMs > 0) source += " / notified";
      if (group.size() > 1) source += "  " + group.size() + " events";
      detail.setText(source);
      detail.setVisibility(source.trim().isEmpty() ? View.GONE : View.VISIBLE);
      boolean actionRow = hasDetailAction(group);
      ui.bindListRow(row, false, actionRow);
      ui.styleMeta(time, actionRow);
      ui.styleMeta(detail, actionRow);
      return row;
    }
  }

  private static boolean hasDetailAction(LogGroups.Group group) {
    if (group == null || group.entries.size() != 1) return false;
    Entry entry = group.first;
    if ("home_reminder".equals(entry.type)) return true;
    if ("USER".equals(entry.kind) && "app_launch".equals(entry.type)) return true;
    return NotificationActions.eligible(entry) && !entry.notificationKeyHash.isEmpty();
  }

  private void openEntry(LogGroups.Group group) {
    if (group == null || group.entries.isEmpty()) return;
    cancelBrightnessGesture();
    cancelNotificationAction();
    detailGroup = group;
    detailUids.clear();
    for (Entry entry : group.entries) detailUids.add(entry.uid);
    show("DETAIL");
  }

  private void detail() {
    cancelNotificationAction();
    if (detailGroup != null && detailMatches(detailGroup)) {
      renderDetail(detailGroup);
      return;
    }
    TextView loading = ui.text("Loading log entry...", ui.size);
    root.addView(loading, new LinearLayout.LayoutParams(-1, 0, 1));
    final int request = generation;
    final ArrayList<String> wanted = new ArrayList<>(detailUids);
    reader.execute(
        () -> {
          ArrayList<Entry> entries = new ArrayList<>();
          for (String uid : wanted) {
            Entry entry = journal.findByUid(uid);
            if (entry != null) entries.add(entry);
          }
          List<LogGroups.Group> groups = LogGroups.group(entries);
          LogGroups.Group loaded = groups.isEmpty() ? null : groups.get(0);
          main.post(
              () -> {
                if (isDestroyed() || request != generation || !"DETAIL".equals(screen)) return;
                if (loaded == null) {
                  detailGroup = null;
                  detailUids.clear();
                  show("HOME");
                  showText("Log entry", "This log entry is no longer available.");
                  return;
                }
                detailGroup = loaded;
                render();
              });
        });
  }

  private boolean detailMatches(LogGroups.Group group) {
    if (group == null || group.entries.size() != detailUids.size()) return false;
    for (int i = 0; i < detailUids.size(); i++)
      if (!detailUids.get(i).equals(group.entries.get(i).uid)) return false;
    return true;
  }

  private void renderDetail(LogGroups.Group group) {
    TextView heading = ui.heading(group.heading());
    heading.setGravity(Gravity.CENTER_HORIZONTAL);
    root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

    TextView surface = ui.text("", ui.size);
    surface.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    surface.setLineSpacing(0f, 1.08f);
    surface.setText(formatGroupDisplay(group));
    ui.selectable(surface);
    surface.setGravity(Gravity.TOP);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(ui.background);
    scroll.setFillViewport(true);
    scroll.addView(
        surface,
        new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout actions = ui.row();
    boolean protectedReminder =
        group.entries.size() == 1 && "home_reminder".equals(group.first.type);
    if (!protectedReminder) {
      String deleteLabel = group.entries.size() > 1
          ? "Delete " + group.entries.size() + " entries"
          : "Delete";
      Button delete = ui.button(deleteLabel, () -> confirmDeleteGroup(group));
      actions.addView(delete, new LinearLayout.LayoutParams(-2, ui.dp(56)));
    }
    actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    final Entry[] displayedEntry =
        group.entries.size() == 1 ? new Entry[] {group.first} : null;
    final Button[] notifyButton = new Button[1];
    if (displayedEntry != null) {
      notifyButton[0] =
          ui.button(
              displayedEntry[0].notifiedMs > 0 ? "Unnotify" : "Notify",
              () -> toggleEntryNotification(displayedEntry, surface, notifyButton[0]));
      notifyButton[0].setContentDescription(
          displayedEntry[0].notifiedMs > 0
              ? "Remove this log entry from notifications"
              : "Push this log entry to notifications");
      actions.addView(notifyButton[0], new LinearLayout.LayoutParams(-2, ui.dp(56)));
    }
    root.addView(actions, new LinearLayout.LayoutParams(-1, -2));

    if (group.entries.size() != 1) return;
    Entry entry = group.first;
    if ("home_reminder".equals(entry.type)) {
      bindDetailTap(
          surface,
          this::confirmBloodguiltWeb,
          "Open bloodguiltcurse.net");
    } else if ("USER".equals(entry.kind) && "app_launch".equals(entry.type)) {
      bindDetailTap(
          surface,
          () -> {
            show("HOME");
            launchApp(entry.source);
          },
          "Launch " + appSourceName(entry.source));
    } else if (NotificationActions.eligible(entry)) {
      bindDetailTap(surface, () -> activateNotificationEntry(entry), "Activate notification action");
    }
  }

  private void bindDetailTap(TextView surface, Runnable action, String description) {
    GestureDetector tap =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override public boolean onDown(MotionEvent event) { return true; }

              @Override
              public boolean onSingleTapUp(MotionEvent event) {
                // Android's selectable TextView consumes its ordinary click path. Observe the
                // gesture without consuming it so long-press selection/copy remains native.
                // If text is actively selected, the next tap only dismisses/adjusts selection.
                int start = surface.getSelectionStart();
                int end = surface.getSelectionEnd();
                if (start >= 0 && end >= 0 && start != end) return false;
                surface.post(action);
                return true;
              }
            });
    surface.setContentDescription(description);
    surface.setOnTouchListener(
        (view, event) -> {
          tap.onTouchEvent(event);
          return false;
        });
  }

  private void activateNotificationEntry(Entry entry) {
    cancelNotificationAction();
    final long request = actionRequest;
    final int current = generation;
    final String uid = entry.uid;
    actionTask =
        reader.submit(
            () -> {
              NotificationActions.Result result = NotificationActions.resolve(entry);
              main.post(
                  () -> {
                    if (!resumed
                        || isDestroyed()
                        || request != actionRequest
                        || current != generation
                        || !"DETAIL".equals(screen)
                        || !detailUids.contains(uid)) return;
                    actionTask = null;
                    if (result.action == null) {
                      showText("Notification action unavailable", result.message);
                      return;
                    }
                    activateResolved(result);
                  });
            });
  }

  private void toggleEntryNotification(Entry[] current, TextView surface, Button button) {
    if (current == null || current.length == 0 || current[0] == null || button == null) return;
    if (current[0].notifiedMs > 0) unnotifyEntry(current, surface, button);
    else postEntryNotification(current, surface, button);
  }

  private void postEntryNotification(Entry[] current, TextView surface, Button button) {
    Entry entry = current[0];
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {
      pendingNotificationCurrent = current;
      pendingNotificationSurface = surface;
      pendingNotificationButton = button;
      requestPermissions(
          new String[] {Manifest.permission.POST_NOTIFICATIONS}, ENTRY_NOTIFICATION_PERMISSION);
      return;
    }
    button.setEnabled(false);
    long notifiedMs = System.currentTimeMillis();
    journal.markNotified(
        entry.uid,
        notifiedMs,
        (ok, message) -> {
          button.setEnabled(true);
          if (!ok) {
            showText("Notification not saved", message);
            return;
          }
          String problem = EntryNotifications.post(this, entry);
          if (!problem.isEmpty()) {
            journal.markNotified(entry.uid, 0, null);
            showText("Notification not posted", problem);
            return;
          }
          Entry updated = entry.withNotifiedMs(notifiedMs);
          current[0] = updated;
          updateNotificationUi(updated, surface, button);
        });
  }

  private void unnotifyEntry(Entry[] current, TextView surface, Button button) {
    Entry entry = current[0];
    button.setEnabled(false);
    journal.markNotified(
        entry.uid,
        0,
        (ok, message) -> {
          button.setEnabled(true);
          if (!ok) {
            showText("Notification not removed", message);
            return;
          }
          EntryNotifications.cancel(this, entry);
          Entry updated = entry.withNotifiedMs(0);
          current[0] = updated;
          updateNotificationUi(updated, surface, button);
        });
  }

  private void updateNotificationUi(Entry entry, TextView surface, Button button) {
    if (surface != null) {
      LogGroups.Group updatedGroup =
          LogGroups.group(Collections.singletonList(entry)).get(0);
      surface.setText(formatGroupDisplay(updatedGroup));
    }
    if (button != null) {
      boolean notified = entry.notifiedMs > 0;
      button.setText(notified ? "Unnotify" : "Notify");
      button.setContentDescription(
          notified
              ? "Remove this log entry from notifications"
              : "Push this log entry to notifications");
    }
  }

  /** Rebuild durable Notify entries after process death, force-stop/relaunch or upgrade. */
  private void restoreEntryNotifications() {
    final Context app = getApplicationContext();
    reader.execute(
        () -> {
          EntryNotifications.cancelAll(app);
          if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              != PackageManager.PERMISSION_GRANTED) return;
          long afterRow = 0;
          while (true) {
            List<Entry> rows = journal.notifiedEntriesAfter(afterRow, 100);
            if (rows.isEmpty()) return;
            for (Entry entry : rows) EntryNotifications.post(app, entry);
            afterRow = rows.get(rows.size() - 1).rowId;
            if (rows.size() < 100) return;
          }
        });
  }

  private void confirmClearLogNotifications() {
    AlertDialog confirmation =
        dialog()
            .setTitle("Clear notifications?")
            .setMessage(
                "Remove every notification created with Notify?\n\n"
                    + "The log entries themselves are not deleted.")
            .setPositiveButton("Clear", (d, which) -> clearLogNotifications())
            .setNegativeButton("Cancel", null)
            .create();
    showDialogSafely(confirmation);
  }

  private void clearLogNotifications() {
    journal.clearAllNotified(
        (ok, message) -> {
          if (!ok) {
            showText("Notifications not cleared", message);
            return;
          }
          EntryNotifications.cancelAll(this);
          if ("NOTIFIED".equals(screen)) render();
        });
  }

  private void notifiedLogs() {
    TextView heading = ui.heading("Notified logs");
    heading.setGravity(Gravity.CENTER_HORIZONTAL);
    root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout rows = ui.column();
    rows.addView(ui.text("Loading...", ui.size), new LinearLayout.LayoutParams(-1, -2));
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(ui.background);
    scroll.addView(rows);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    Button clearAll = ui.button("Clear all", this::confirmClearLogNotifications);
    clearAll.setVisibility(View.GONE);
    root.addView(clearAll, new LinearLayout.LayoutParams(-1, -2));

    final int request = generation;
    reader.execute(
        () -> {
          ArrayList<Entry> entries = new ArrayList<>();
          boolean more = false;
          String error = "";
          try {
            long afterRow = 0;
            while (entries.size() < 512) {
              List<Entry> page = journal.notifiedEntriesAfter(afterRow, 100);
              if (page.isEmpty()) break;
              int remaining = 512 - entries.size();
              if (page.size() > remaining) {
                entries.addAll(page.subList(0, remaining));
                more = true;
                break;
              }
              entries.addAll(page);
              afterRow = page.get(page.size() - 1).rowId;
              if (page.size() < 100) break;
            }
            Collections.reverse(entries);
          } catch (RuntimeException failure) {
            error = Journal.describe(failure);
          }
          final boolean truncated = more;
          final String problem = error;
          main.post(
              () -> {
                if (isDestroyed() || request != generation || !"NOTIFIED".equals(screen)) return;
                rows.removeAllViews();
                if (!problem.isEmpty()) {
                  rows.addView(ui.text(problem, ui.size), new LinearLayout.LayoutParams(-1, -2));
                  return;
                }
                if (entries.isEmpty()) {
                  rows.addView(
                      ui.text("No log entries are currently in notifications.", ui.size),
                      new LinearLayout.LayoutParams(-1, -2));
                  clearAll.setVisibility(View.GONE);
                  return;
                }
                for (Entry entry : entries) {
                  LinearLayout row = ui.row();
                  String label = entry.title.isEmpty() ? entry.type : entry.title;
                  Button open = ui.button(label + "\n" + entryTime(entry), () -> openEntryByUid(entry.uid));
                  Button remove = ui.button("Remove", () -> removeNotifiedLog(entry));
                  LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, -2, 1);
                  openParams.setMargins(0, ui.dp(4), ui.dp(5), ui.dp(4));
                  LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(-2, -2);
                  removeParams.setMargins(ui.dp(5), ui.dp(4), 0, ui.dp(4));
                  row.addView(open, openParams);
                  row.addView(remove, removeParams);
                  rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                if (truncated)
                  rows.addView(
                      ui.text("Showing the newest 512 notified logs.", Math.max(14, ui.size - 8)),
                      new LinearLayout.LayoutParams(-1, -2));
                clearAll.setVisibility(View.VISIBLE);
              });
        });
  }

  private void removeNotifiedLog(Entry entry) {
    journal.markNotified(
        entry.uid,
        0,
        (ok, message) -> {
          if (!ok) {
            showText("Notification not removed", message);
            return;
          }
          EntryNotifications.cancel(this, entry);
          if ("NOTIFIED".equals(screen)) render();
        });
  }

  private CharSequence formatGroupDisplay(LogGroups.Group group) {
    SpannableStringBuilder text = new SpannableStringBuilder();
    for (Entry entry : group.entries) {
      if (text.length() > 0) {
        int start = text.length();
        text.append("\n----------------\n\n");
        styleDetailRange(text, start, text.length(), Math.max(14f, ui.size - 8f),
            "sans-serif-condensed", Typeface.NORMAL);
      }
      appendEntryDisplay(text, entry);
    }
    return text;
  }

  private void appendEntryDisplay(SpannableStringBuilder text, Entry entry) {
    int timestampStart = text.length();
    text.append(fullEntryTime(entry));
    styleDetailRange(text, timestampStart, text.length(), Math.max(14f, ui.size - 7f),
        "sans-serif-condensed", Typeface.NORMAL);

    DetailParts parts = detailParts(entry);
    if (!parts.content.isEmpty()) text.append("\n\n");
    for (int i = 0; i < parts.content.size(); i++) {
      DetailField field = parts.content.get(i);
      boolean plainPrimary = parts.content.size() == 1 && "Text".equals(field.label);
      if (!field.label.isEmpty() && !plainPrimary) {
        int labelStart = text.length();
        text.append(field.label);
        styleDetailRange(text, labelStart, text.length(), Math.max(16f, ui.size - 4f),
            "sans-serif-medium", Typeface.NORMAL);
        text.append('\n');
      }
      int bodyStart = text.length();
      text.append(field.value);
      styleDetailRange(text, bodyStart, text.length(), ui.size, "sans-serif", Typeface.NORMAL);
      if (i + 1 < parts.content.size()) text.append("\n\n");
    }

    if (!parts.metadata.isEmpty()) {
      text.append(parts.content.isEmpty() ? "\n" : "\n\n");
      for (int i = 0; i < parts.metadata.size(); i++) {
        DetailField field = parts.metadata.get(i);
        int metaStart = text.length();
        if (!field.label.isEmpty()) text.append(field.label).append(": ");
        text.append(field.value);
        styleDetailRange(text, metaStart, text.length(), Math.max(14f, ui.size - 8f),
            "sans-serif-condensed", Typeface.NORMAL);
        if (i + 1 < parts.metadata.size()) text.append('\n');
      }
    }
  }

  private void styleDetailRange(
      SpannableStringBuilder text, int start, int end, float sp, String family, int style) {
    if (start >= end) return;
    if (Math.abs(sp - ui.size) > 0.01f)
      text.setSpan(
          new RelativeSizeSpan(sp / ui.size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (family != null && !family.isEmpty())
      text.setSpan(new TypefaceSpan(family), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (style != Typeface.NORMAL)
      text.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private String formatGroup(LogGroups.Group group) {
    StringBuilder text = new StringBuilder();
    for (Entry entry : group.entries) {
      if (text.length() > 0) text.append("\n----------------\n");
      text.append(fullEntryTime(entry));
      DetailParts parts = detailParts(entry);
      for (DetailField field : parts.content) {
        text.append("\n\n");
        boolean plainPrimary = parts.content.size() == 1 && "Text".equals(field.label);
        if (!field.label.isEmpty() && !plainPrimary) text.append(field.label).append(": ");
        text.append(field.value);
      }
      for (DetailField field : parts.metadata) {
        text.append('\n');
        if (!field.label.isEmpty()) text.append(field.label).append(": ");
        text.append(field.value);
      }
    }
    return text.toString();
  }

  private DetailParts detailParts(Entry entry) {
    DetailParts parts = new DetailParts();
    if ("NOTIFICATION".equals(entry.kind)) {
      List<DetailField> fields = parseNotificationFields(entry.body);
      simplifyNotificationText(fields, entry.title);
      for (DetailField field : fields) {
        if (notificationMetadata(field.label)) parts.metadata.add(field);
        else if (!field.value.isEmpty()) parts.content.add(field);
      }
      parts.metadata.add(0, new DetailField("App", appSourceName(entry.source)));
    } else {
      if (!entry.body.isEmpty()) parts.content.add(new DetailField("", entry.body));
      if ("USER".equals(entry.kind) && "app_launch".equals(entry.type))
        parts.metadata.add(new DetailField("App", appSourceName(entry.source)));
      else if (!entry.source.isEmpty())
        parts.metadata.add(new DetailField("Source", entry.source));
    }
    if (!entry.type.isEmpty()) parts.metadata.add(new DetailField("Type", entry.type));
    if (entry.notifiedMs > 0)
      parts.metadata.add(new DetailField("Notification", "posted " + when(entry.notifiedMs)));
    parts.metadata.add(new DetailField("Provenance", provenance(entry)));
    return parts;
  }

  private static String provenance(Entry entry) {
    if (entry == null) return "MML journal";
    if ("USER".equals(entry.kind)) {
      if ("app_launch".equals(entry.type)) return "MML app picker";
      if ("shared_text".equals(entry.type)) return "Android Share to MML";
      if ("grouped_log".equals(entry.type)) return "MML grouped log";
      return "MML user action";
    }
    if ("NOTIFICATION".equals(entry.kind)) return "Android notification access";
    if ("USAGE".equals(entry.kind)) return "Android Usage history";
    if ("SMS".equals(entry.kind)) return "Android SMS provider";
    if ("CALL".equals(entry.kind)) return "Android call log provider";
    if ("DEVICE".equals(entry.kind)) return "Android system event";
    if ("SYSTEM".equals(entry.kind)) return "MML";
    return "MML journal";
  }

  private List<DetailField> parseNotificationFields(String body) {
    ArrayList<DetailField> fields = new ArrayList<>();
    if (body == null || body.isEmpty()) return fields;
    DetailField current = null;
    String[] lines = body.split("\n", -1);
    for (String line : lines) {
      int split = notificationFieldSplit(line);
      if (split >= 0) {
        current = new DetailField(line.substring(0, split), line.substring(split + 2));
        fields.add(current);
      } else if (current == null) {
        current = new DetailField("", line);
        fields.add(current);
      } else {
        current.value += "\n" + line;
      }
    }
    return fields;
  }

  private static int notificationFieldSplit(String line) {
    String[] labels = {
      "Removal reason", "Expanded text", "Expanded title", "Conversation", "Subtext",
      "Summary", "Info", "Text", "Line", "Reply", "Ticker", "Content", "Progress",
      "Capture", "Attachment type", "Attachment URI", "Channel", "Category", "Group", "Flags"
    };
    for (String label : labels) if (line.startsWith(label + ": ")) return label.length();
    if (line.startsWith("Message (") || line.startsWith("Earlier message (")) {
      int end = line.indexOf("): ");
      if (end >= 0) return end + 1;
    }
    return -1;
  }

  private static boolean notificationMetadata(String label) {
    return "Channel".equals(label)
        || "Category".equals(label)
        || "Group".equals(label)
        || "Flags".equals(label);
  }

  private static void simplifyNotificationText(List<DetailField> fields, String title) {
    DetailField ordinary = null, expanded = null;
    for (DetailField field : fields) {
      if ("Text".equals(field.label)) ordinary = field;
      else if ("Expanded text".equals(field.label)) expanded = field;
    }
    if (ordinary != null && expanded != null) {
      String normal = ordinary.value.trim(), large = expanded.value.trim();
      if (normal.equals(large)) fields.remove(expanded);
      else if (!normal.isEmpty() && large.length() >= normal.length() && large.contains(normal)) {
        ordinary.value = expanded.value;
        fields.remove(expanded);
      } else if (!large.isEmpty() && normal.length() > large.length() && normal.contains(large)) {
        fields.remove(expanded);
      }
    } else if (ordinary == null && expanded != null) {
      expanded.label = "Text";
    }
    for (int i = fields.size() - 1; i >= 0; i--) {
      DetailField field = fields.get(i);
      if ("Expanded title".equals(field.label) && field.value.trim().equals(title.trim()))
        fields.remove(i);
    }
  }

  private String appSourceName(String packageName) {
    if (packageName == null || packageName.isEmpty()) return "Unknown app";
    try {
      ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
      CharSequence label = getPackageManager().getApplicationLabel(info);
      if (label != null && label.length() > 0 && !packageName.contentEquals(label))
        return label + " (" + packageName + ")";
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}
    return packageName;
  }

  private static String fullEntryTime(Entry entry) {
    return Instant.ofEpochMilli(entry.timeMs)
        .atZone(entryZone(entry))
        .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
  }

  private static final class DetailParts {
    final List<DetailField> content = new ArrayList<>();
    final List<DetailField> metadata = new ArrayList<>();
  }

  private static final class DetailField {
    String label, value;
    DetailField(String label, String value) {
      this.label = label == null ? "" : label;
      this.value = value == null ? "" : value;
    }
  }

  private void confirmDeleteGroup(LogGroups.Group group) {
    boolean grouped = group.entries.size() > 1;
    boolean mirrorSelected = !prefs.getString("log_tree", "").isEmpty();
    if (!grouped && !mirrorSelected) {
      deleteGroup(group);
      return;
    }
    String noun = grouped ? group.entries.size() + " entries" : "this entry";
    String message = "Delete " + noun + " from MML's private journal?";
    if (mirrorSelected)
      message += "\n\nCopies already written to the log folder are not removed.";
    AlertDialog confirmation =
        dialog()
            .setTitle(grouped ? "Delete " + group.entries.size() + " entries?" : "Delete entry?")
            .setMessage(message)
            .setPositiveButton("Delete", (d, which) -> deleteGroup(group))
            .setNegativeButton("Cancel", null)
            .create();
    showDialogSafely(confirmation);
  }

  private void deleteGroup(LogGroups.Group group) {
    journal.deleteEntries(
        group.entries,
        (ok, message) -> {
          if (ok) {
            restoreEntryNotifications();
            detailGroup = null;
            detailUids.clear();
            show("HOME");
          } else showText("Log entry not deleted", message);
        });
  }

  private void activateResolved(NotificationActions.Result result) {
    show("HOME");
    String message = NotificationActions.send(this, result);
    if (!message.isEmpty()) showText("Notification action unavailable", message);
  }

  private static String entryTime(Entry entry) {
    return Instant.ofEpochMilli(entry.timeMs)
        .atZone(entryZone(entry))
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
  }

  private static ZoneId entryZone(Entry entry) {
    try { return ZoneId.of(entry.zone); }
    catch (java.time.DateTimeException invalid) { return ZoneId.systemDefault(); }
  }

  private static String previewText(String text) {
    if (text.length() <= 8192) return text;
    int end = 8192;
    if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
    return text.substring(0, end) + "\n[More in details]";
  }

  private void followLatest() {
    before = 0;
    pageBack.clear();
    live = true;
    updateLatestControl();
    refreshLog(true);
  }

  private void updateLatestControl() {
    if (latest != null) latest.setVisibility(before == 0 && !live ? View.VISIBLE : View.GONE);
  }

  private BrightnessListView brightnessList() {
    return new BrightnessListView(
        this,
        new BrightnessListView.Listener() {
          @Override public int currentPercent() { return currentSystemBrightnessPercent(); }
          @Override public void started() {
            cancelNotificationAction();
            logScrollRevision++;
            main.removeCallbacks(restoreBrightnessHeading);
          }
          @Override public void changed(int percent) {
            if (!canWriteSystemBrightness()) {
              pendingSystemBrightnessPercent = percent;
              if (brightnessHeading != null) brightnessHeading.setText("System brightness: allow access");
              return;
            }
            if (setSystemBrightnessPercent(percent) && brightnessHeading != null)
              brightnessHeading.setText("System brightness: " + percent + "%");
          }
          @Override public void finished(int percent) {
            if (canWriteSystemBrightness()) setSystemBrightnessPercent(percent);
            else if (resumed) {
              pendingSystemBrightnessPercent = percent;
              requestSystemBrightnessAccess();
            }
            main.removeCallbacks(restoreBrightnessHeading);
            main.postDelayed(restoreBrightnessHeading, 900);
          }
        });
  }

  private void cancelBrightnessGesture() {
    if (list instanceof BrightnessListView) ((BrightnessListView) list).cancelBrightnessGesture();
  }

  private void followSystemBrightness() {
    WindowManager.LayoutParams attributes = getWindow().getAttributes();
    if (attributes.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return;
    attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    getWindow().setAttributes(attributes);
  }

  private boolean canWriteSystemBrightness() {
    try { return Settings.System.canWrite(this); }
    catch (RuntimeException unavailable) { return false; }
  }

  private int currentSystemBrightnessPercent() {
    int raw = 128;
    try { raw = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS); }
    catch (Settings.SettingNotFoundException | RuntimeException ignored) {}
    raw = Math.max(1, Math.min(255, raw));
    return Math.max(1, Math.min(100, Math.round(raw * 100f / 255f)));
  }

  private boolean adaptiveBrightnessEnabled() {
    try {
      return Settings.System.getInt(
              getContentResolver(),
              Settings.System.SCREEN_BRIGHTNESS_MODE,
              Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
          == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    } catch (RuntimeException unavailable) { return false; }
  }

  private boolean setSystemBrightnessPercent(int percent) {
    if (!canWriteSystemBrightness()) return false;
    int clamped = Math.max(1, Math.min(100, percent));
    int raw = Math.max(1, Math.min(255, Math.round(clamped * 255f / 100f)));
    try {
      if (!Settings.System.putInt(
          getContentResolver(),
          Settings.System.SCREEN_BRIGHTNESS_MODE,
          Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)) return false;
      boolean saved = Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, raw);
      followSystemBrightness();
      return saved;
    } catch (RuntimeException unavailable) { return false; }
  }

  private void requestSystemBrightnessAccess() {
    brightnessAccessRequested = true;
    open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
  }

  private void chooseBrightness() {
    if (!canWriteSystemBrightness()) {
      AlertDialog window =
          dialog()
              .setTitle("System brightness")
              .setMessage(
                  "MML's Log swipe changes the phone's system brightness so it remains when another app opens. Android requires Modify system settings access.")
              .setPositiveButton("Open access", (d, which) -> requestSystemBrightnessAccess())
              .setNegativeButton("Cancel", null)
              .create();
      showDialogSafely(window);
      return;
    }
    String[] labels = {"Adaptive brightness", "1%", "10%", "25%", "50%", "75%", "100%"};
    int[] values = {-1, 1, 10, 25, 50, 75, 100};
    AlertDialog window =
        dialog()
            .setTitle("System brightness")
            .setItems(
                labels,
                (d, which) -> {
                  try {
                    if (values[which] < 0)
                      Settings.System.putInt(
                          getContentResolver(),
                          Settings.System.SCREEN_BRIGHTNESS_MODE,
                          Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                    else setSystemBrightnessPercent(values[which]);
                  } catch (RuntimeException unavailable) {
                    showText("System brightness unavailable", Journal.describe(unavailable));
                  }
                  followSystemBrightness();
                  render();
                })
            .setNegativeButton("Cancel", null)
            .create();
    showDialogSafely(window);
  }

  private void updateStorageStatus() {
    if (storageBanner != null) {
      storageBanner.setVisibility(journal.capturePaused() ? View.VISIBLE : View.GONE);
      storageBanner.setText("Automatic logging paused. Tap for storage status.");
    }
  }

  private void showStorageStatus() {
    journal.refreshStorageStatus();
    String folder = prefs.getString("log_tree", "").isEmpty() ? "not set" : "selected";
    AlertDialog window =
        dialog()
            .setTitle("Log storage status")
            .setMessage(
                "Logs are stored in app data.\n\n"
                    + journal.captureStorageStatus()
                    + "\n\nLog folder: " + folder + "."
                    + ("selected".equals(folder)
                        ? " Readable .log.txt copies are also saved there."
                        : " You can optionally choose a folder for readable .log.txt copies."))
            .setPositiveButton("Close", null)
            .create();
    showDialogSafely(window);
  }

  private void showLogStorage() {
    boolean folderSelected = !prefs.getString("log_tree", "").isEmpty();
    LinearLayout panel = ui.column();
    TextView explanation =
        ui.text(
            "MML stores logs in app data by default. You may also choose a folder for readable .log.txt copies.",
            Math.max(16f, ui.size - 4f));
    ui.styleMeta(explanation);
    panel.addView(explanation);
    final AlertDialog[] holder = new AlertDialog[1];
    add(
        panel,
        folderSelected ? "Change log folder" : "Choose log folder",
        () -> {
          if (holder[0] != null) holder[0].dismiss();
          pickTree(PICK_FOLDER);
        });
    if (folderSelected) {
      add(
          panel,
          "Save to log folder now",
          () -> {
            if (holder[0] != null) holder[0].dismiss();
            PlainTextMirror.retryNow(this);
            main.postDelayed(this::showStorageStatus, 250);
          });
      add(
          panel,
          "Stop saving to log folder",
          () -> {
            if (holder[0] != null) holder[0].dismiss();
            prefs.edit().remove("log_tree").apply();
            PlainTextMirror.status = "No log folder selected";
            journal.record("SYSTEM", "MML", "storage", "Log folder disabled", "");
            render();
          });
    }
    add(
        panel,
        "Storage status",
        () -> {
          if (holder[0] != null) holder[0].dismiss();
          main.post(this::showStorageStatus);
        });
    holder[0] =
        dialog().setTitle("Log storage").setView(panel).setNegativeButton("Close", null).create();
    showDialogSafely(holder[0]);
  }

  private void chooseStorageLimit() {
    String[] labels = {"32 MiB", "64 MiB", "128 MiB", "256 MiB", "512 MiB"};
    int[] values = {32, 64, 128, 256, 512};
    AlertDialog window =
        dialog()
            .setTitle("App-data log limit")
            .setItems(
                labels,
                (d, which) -> {
                  journal.setCaptureLimitMiB(values[which]);
                  render();
                })
            .setNegativeButton("Cancel", null)
            .create();
    showDialogSafely(window);
  }

  private void chooseLogDetail() {
    AlertDialog window =
        dialog()
            .setTitle("Log detail")
            .setItems(
                new String[] {"Custom", "Full"},
                (d, which) -> {
                  if (which == 0) {
                    main.post(this::chooseCustomLogDetail);
                    return;
                  }
                  logDetailLevel = LogDetail.FULL;
                  prefs.edit().putInt("log_detail_mode_v2", logDetailLevel).apply();
                  show("HOME");
                })
            .create();
    showDialogSafely(window);
  }

  private void chooseCustomLogDetail() {
    final String[] labels = LogDetail.customLabels();
    final int[] bits = LogDetail.customBits();
    final boolean[] checked = new boolean[bits.length];
    for (int i = 0; i < bits.length; i++) checked[i] = (customLogMask & bits[i]) != 0;
    LinearLayout panel = ui.column();
    ScrollView options = new ScrollView(this);
    LinearLayout choices = ui.column();
    for (int i = 0; i < bits.length; i++) {
      final int index = i;
      CheckBox box = new CheckBox(this);
      box.setText(labels[i]);
      box.setTextSize(ui.size);
      box.setTextColor(ui.foreground);
      box.setButtonTintList(
          new android.content.res.ColorStateList(
              new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
              new int[] {ui.foreground, ui.foreground}));
      box.setChecked(checked[i]);
      box.setMinHeight(ui.dp(48));
      box.setPadding(ui.dp(12), 0, ui.dp(12), 0);
      box.setOnCheckedChangeListener((button, value) -> checked[index] = value);
      choices.addView(box);
    }
    options.addView(choices);
    panel.addView(options, new LinearLayout.LayoutParams(-1, ui.dp(420)));
    final AlertDialog[] holder = new AlertDialog[1];
    LinearLayout actions = ui.row();
    Button cancel = ui.button("Cancel", () -> { if (holder[0] != null) holder[0].dismiss(); });
    Button save =
        ui.button(
            "Save",
            () -> {
              int mask = 0;
              for (int i = 0; i < bits.length; i++) if (checked[i]) mask |= bits[i];
              customLogMask = mask;
              logDetailLevel = LogDetail.CUSTOM;
              prefs.edit()
                  .putInt("custom_log_mask_v2", customLogMask)
                  .putInt("log_detail_mode_v2", logDetailLevel)
                  .apply();
              if (holder[0] != null) holder[0].dismiss();
              show("HOME");
            });
    actions.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
    actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    actions.addView(save, new LinearLayout.LayoutParams(-2, -2));
    panel.addView(actions);
    holder[0] = dialog().setTitle("Custom log detail").setView(panel).create();
    showDialogSafely(holder[0]);
  }

  private void settings() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout content = ui.column();
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    content.addView(ui.heading("MML settings"));
    add(
        content,
        "Default Home app",
        () -> {
          RoleManager roles = getSystemService(RoleManager.class);
          if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME))
            open(roles.createRequestRoleIntent(RoleManager.ROLE_HOME));
          else open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
    add(content, "Capture status", this::diagnostics);
    add(content, "Last crash / process exit", () -> showText("Last crash / process exit", CrashRecorder.report(this)));
    toggle(content, "Capture notifications", "capture_notifications", true, () -> NotificationCapture.requestReconnect(this));
    add(content, "Notification access", () -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
    add(content, "Notification access help", this::notificationAccessHelp);
    add(content, "Test notification capture", this::testNotification);
    add(content, "Log entry notification settings", () -> open(EntryNotifications.settingsIntent(this)));

    toggle(content, "App activity history", "capture_usage", false, () -> HistoryCollector.request(this));
    add(content, "Usage access", () -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
    if (prefs.getBoolean("capture_usage", false)) {
      content.addView(ui.heading("App activity detail"));
      toggle(content, "App resume / pause / stop", "usage_apps", true, () -> HistoryCollector.request(this));
      toggle(content, "Screen lock / unlock", "usage_screen", true, () -> HistoryCollector.request(this));
      toggle(content, "User interaction", "usage_interaction", true, () -> HistoryCollector.request(this));
      toggle(content, "Foreground services", "usage_services", true, () -> HistoryCollector.request(this));
      toggle(content, "Device start / shutdown", "usage_device", true, () -> HistoryCollector.request(this));
      toggle(content, "Other usage events", "usage_other", true, () -> HistoryCollector.request(this));
    }
    toggle(
        content,
        "SMS history",
        "capture_sms",
        false,
        () -> {
          if (prefs.getBoolean("capture_sms", false))
            requestPermissions(new String[] {Manifest.permission.READ_SMS}, PERMISSIONS);
          HistoryCollector.request(this);
        });
    toggle(
        content,
        "Call history",
        "capture_calls",
        false,
        () -> {
          if (prefs.getBoolean("capture_calls", false))
            requestPermissions(new String[] {Manifest.permission.READ_CALL_LOG}, PERMISSIONS);
          HistoryCollector.request(this);
        });

    content.addView(ui.heading("Logs"));
    add(content, "Log detail: " + LogDetail.label(logDetailLevel), this::chooseLogDetail);
    add(content, "App-data log limit: " + journal.captureLimitMiB() + " MiB", this::chooseStorageLimit);
    String logStorageLabel = prefs.getString("log_tree", "").isEmpty()
        ? "Log storage: app data"
        : "Log storage: app data + folder";
    add(content, logStorageLabel, this::showLogStorage);
    add(content, "Notified logs", () -> show("NOTIFIED"));
    add(content, "Check journal", this::checkJournal);

    content.addView(ui.heading("Display"));
    boolean brickPicker = prefs.getBoolean("app_picker_brick", false);
    add(
        content,
        "App picker view: " + (brickPicker ? "Brick" : "List"),
        () -> {
          prefs.edit().putBoolean("app_picker_brick", !brickPicker).apply();
          render();
        });
    add(
        content,
        "Text size: " + (int) ui.size,
        () -> {
          AlertDialog window =
              dialog()
                  .setItems(
                      new String[] {"22", "24", "28", "32"},
                      (d, which) -> {
                        prefs.edit().putInt("font_size", new int[] {22, 24, 28, 32}[which]).apply();
                        render();
                      })
                  .create();
          showDialogSafely(window);
        });
    String brightnessLabel;
    if (!canWriteSystemBrightness()) brightnessLabel = "System brightness: allow access";
    else if (adaptiveBrightnessEnabled()) brightnessLabel = "System brightness: adaptive";
    else brightnessLabel = "System brightness: " + currentSystemBrightnessPercent() + "%";
    add(content, brightnessLabel, this::chooseBrightness);
    add(
        content,
        "About MML",
        () ->
            showText(
                "MML 2.0.29",
                "Modern Mobile Launcher\n\n"
                    + "A logging Home screen. Manual entries, app launches and phone activity share one journal.\n\n"
                    + "Design by remfan1994\nPublished by ThatIAm.vip\n\n"
                    + "Cows aren't killers, and beef is the bloodshed of the innocent. www.bloodguiltcurse.net has the Bible study.\n\n"
                    + "Native Android. No libraries, accounts, or network service required."));
  }

  private void notificationAccessHelp() {
    LinearLayout content = ui.column();
    TextView text =
        ui.text(
            "If Android says notification access is restricted:\n\n"
                + "1. Open App info below.\n"
                + "2. If Android shows a top-right menu (three dots), choose Allow restricted settings and follow Android's instructions.\n"
                + "3. If that menu is absent, return and try Notification access directly; Android only shows the exception when it applies.\n"
                + "4. Enable MML notification journal.\n\n"
                + "Running Test notification capture after a failed listener can also make Android expose the restriction on App info. Permission to show MML notifications is separate.",
            ui.size);
    ui.selectable(text);
    content.addView(text);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(ui.background);
    scroll.addView(content);
    AlertDialog help = dialog().setTitle("Notification access help").setView(scroll).setPositiveButton("Close", null).create();
    add(content, "App info", () -> { help.dismiss(); open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); });
    add(content, "Notification access", () -> { help.dismiss(); open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); });
    showDialogSafely(help);
  }

  private void add(LinearLayout parent, String label, Runnable action) {
    parent.addView(ui.button(label, action), new LinearLayout.LayoutParams(-1, -2));
  }

  private void toggle(LinearLayout parent, String label, String key, boolean fallback, Runnable action) {
    add(
        parent,
        (prefs.getBoolean(key, fallback) ? "[x] " : "[ ] ") + label,
        () -> {
          prefs.edit().putBoolean(key, !prefs.getBoolean(key, fallback)).apply();
          action.run();
          render();
        });
  }

  private void diagnostics() {
    String tree = prefs.getString("log_tree", "");
    StringBuilder text = new StringBuilder();
    String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
    boolean allowed = false;
    if (enabled != null)
      for (String item : enabled.split(":")) {
        ComponentName name = ComponentName.unflattenFromString(item);
        if (name != null && getPackageName().equals(name.getPackageName())) allowed = true;
      }
    text.append("Notification access: ").append(allowed ? "granted" : "not granted");
    text.append("\nListener: ").append(NotificationCapture.connected ? "connected" : "not connected");
    text.append("\nBackground model: Android notification listener only");
    text.append("\nPending capture batches: ").append(journal.pending());
    text.append("\nLast journal commit: ").append(when(journal.lastCommit()));
    text.append("\nJournal: ").append(journal.error().isEmpty() ? "OK" : journal.error());
    text.append("\n\nLog folder: ").append(tree.isEmpty() ? "not set" : "selected");
    text.append("\n").append(PlainTextMirror.status);
    text.append("\nFolder last saved: ").append(when(prefs.getLong("log_folder_saved_ms", 0)));
    text.append("\n\n").append(journal.captureStorageStatus());
    text.append("\n\nCapture details:\n");
    for (Map.Entry<String, ?> item : prefs.getAll().entrySet()) {
      String key = item.getKey();
      if (key.endsWith("_error") || key.startsWith("notification_") || key.startsWith("history_"))
        text.append(key.replace('_', ' ')).append(": ").append(item.getValue()).append('\n');
    }
    text.append("\nSMS permission: ")
        .append(checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED ? "granted" : "not granted");
    text.append("\nCall permission: ")
        .append(checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED ? "granted" : "not granted");
    text.append("\n\nAndroid can stop delivery when force-stopped. Relaunch MML to resume. Text hidden by its source app cannot be reconstructed.");
    showText("Capture status", text.toString());
  }

  private static String when(long time) {
    return time == 0
        ? "none"
        : Instant.ofEpochMilli(time)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"));
  }

  private void testNotification() {
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, PERMISSIONS);
      showText("Test notification", "Allow notifications, then run this test again.");
      return;
    }
    if (captureTesting) return;
    captureTesting = true;
    reader.execute(
        () -> {
          try {
            long watermark = journal.latestRowId();
            main.post(() -> { if (!isDestroyed()) postCaptureTest(watermark); });
          } catch (RuntimeException error) {
            main.post(() -> finishCaptureTest(Journal.describe(error)));
          }
        });
  }

  private void postCaptureTest(long watermark) {
    String token = "MML capture test " + java.util.UUID.randomUUID();
    try {
      NotificationManager manager = getSystemService(NotificationManager.class);
      if (manager == null) throw new IllegalStateException("Notification service unavailable");
      manager.createNotificationChannel(new NotificationChannel("mml-test", "Capture test", NotificationManager.IMPORTANCE_DEFAULT));
      Notification notification =
          new Notification.Builder(this, "mml-test")
              .setSmallIcon(android.R.drawable.ic_menu_agenda)
              .setContentTitle("MML notification test")
              .setContentText(token)
              .setStyle(new Notification.BigTextStyle().bigText(token + "\nThis text must reach the journal."))
              .setAutoCancel(true)
              .build();
      manager.notify(NotificationCapture.CAPTURE_TEST_ID, notification);
    } catch (RuntimeException error) {
      finishCaptureTest(Journal.describe(error));
      return;
    }
    main.postDelayed(
        () -> {
          if (isDestroyed()) return;
          reader.execute(
              () -> {
                try {
                  boolean passed = journal.hasNotificationTextAfter(watermark, token);
                  main.post(
                      () ->
                          finishCaptureTest(
                              passed
                                  ? "PASS: the listener received this notification and the journal committed its text."
                                  : "No committed notification found yet. Check notification access and listener connection in Capture status."));
                } catch (Exception error) {
                  main.post(() -> finishCaptureTest(Journal.describe(error)));
                }
              });
        },
        2500);
  }

  private void finishCaptureTest(String message) {
    captureTesting = false;
    showText("Notification test", message);
  }

  private void checkJournal() {
    if (journalChecking) return;
    journalChecking = true;
    int g = generation;
    long request = ++journalCheckRequest;
    CancellationSignal cancellation = new CancellationSignal();
    journalCheckCancellation = cancellation;
    Journal selectedJournal = journal;
    Handler callbackHandler = main;
    WeakReference<MainActivity> owner = new WeakReference<>(this);
    try {
      journalCheckTask =
          journalChecks.submit(
              () -> runJournalCheck(selectedJournal, cancellation, owner, callbackHandler, g, request));
    } catch (RejectedExecutionException busy) {
      cancelJournalCheck();
    }
  }

  private static void runJournalCheck(
      Journal journal,
      CancellationSignal cancellation,
      WeakReference<MainActivity> owner,
      Handler callbackHandler,
      int generation,
      long request) {
    String result;
    try {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
      result = journal.integrity(cancellation);
    } catch (OperationCanceledException cancelled) { return; }
    catch (Exception error) { result = Journal.describe(error); }
    if (cancellation.isCanceled()) return;
    final String text = result;
    callbackHandler.post(
        () -> {
          MainActivity activity = owner.get();
          if (activity == null
              || activity.isFinishing()
              || activity.isDestroyed()
              || !activity.resumed
              || generation != activity.generation
              || request != activity.journalCheckRequest
              || cancellation.isCanceled()) return;
          activity.journalCheckTask = null;
          activity.journalCheckCancellation = null;
          activity.journalChecking = false;
          activity.showText("Journal check", text);
        });
  }

  private void pickTree(int request) {
    Intent picker =
        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    try { startActivityForResult(picker, request); }
    catch (RuntimeException error) { showText("Folder picker", Journal.describe(error)); }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri tree = data.getData();
    if (request == PICK_FOLDER) {
      try {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(tree, flags);
        if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
          throw new IllegalStateException("Folder did not grant write access");
        prefs.edit().putString("log_tree", tree.toString()).apply();
        PlainTextMirror.request(this);
        journal.record("SYSTEM", "MML", "storage", "Log folder selected", tree.toString());
        render();
      } catch (Exception error) {
        showText("Folder", Journal.describe(error));
      }
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == ENTRY_NOTIFICATION_PERMISSION) {
      Entry[] pendingCurrent = pendingNotificationCurrent;
      TextView pendingSurface = pendingNotificationSurface;
      Button pendingButton = pendingNotificationButton;
      pendingNotificationCurrent = null;
      pendingNotificationSurface = null;
      pendingNotificationButton = null;
      if (pendingCurrent != null
          && pendingCurrent.length > 0
          && pendingCurrent[0] != null
          && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              == PackageManager.PERMISSION_GRANTED)
        postEntryNotification(pendingCurrent, pendingSurface, pendingButton);
      return;
    }
    HistoryCollector.request(this);
  }

  private void cancelQuery() {
    if (queryCancellation != null) queryCancellation.cancel();
    queryCancellation = null;
    if (queryTask != null) queryTask.cancel(true);
    queryTask = null;
    reader.purge();
  }

  private void cancelNotificationAction() {
    actionRequest++;
    if (actionTask != null) actionTask.cancel(true);
    actionTask = null;
    reader.purge();
  }

  private void cancelJournalCheck() {
    journalCheckRequest++;
    if (journalCheckCancellation != null) journalCheckCancellation.cancel();
    journalCheckCancellation = null;
    if (journalCheckTask != null) journalCheckTask.cancel(true);
    journalCheckTask = null;
    journalChecking = false;
  }

  private void cancelReads() {
    cancelQuery();
    cancelNotificationAction();
    cancelJournalCheck();
  }

  private void returnToHomeFromSystemHome() {
    hideKeyboard();
    if (!"HOME".equals(screen) || before != 0) show("HOME");
    else {
      before = 0;
      pageBack.clear();
      live = true;
      if (resumed && list != null) refreshLog(false);
    }
  }

  private void confirmBloodguiltWeb() {
    AlertDialog confirmation =
        dialog()
            .setMessage("Goes to internet. Continue?")
            .setPositiveButton(
                "Continue",
                (d, which) -> {
                  show("HOME");
                  openWeb("https://www.bloodguiltcurse.net/");
                })
            .setNegativeButton("Cancel", null)
            .create();
    showDialogSafely(confirmation);
  }

  private void openWeb(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (RuntimeException error) {
      showText("Link unavailable", Journal.describe(error));
    }
  }

  private void open(Intent intent) {
    try { startActivity(intent); }
    catch (RuntimeException error) { showText("Android settings", Journal.describe(error)); }
  }

  private void hideKeyboard() {
    InputMethodManager keyboard = getSystemService(InputMethodManager.class);
    if (keyboard != null && root != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
  }

  private AlertDialog.Builder dialog() {
    return new AlertDialog.Builder(this, R.style.MmlDialog);
  }

  private boolean canShowUi() {
    return resumed && !isFinishing() && !isDestroyed() && root != null && root.isAttachedToWindow();
  }

  private AlertDialog showDialogSafely(AlertDialog window) {
    if (window == null || !canShowUi()) return null;
    try {
      window.show();
      styleDialogButton(window.getButton(AlertDialog.BUTTON_POSITIVE));
      styleDialogButton(window.getButton(AlertDialog.BUTTON_NEGATIVE));
      styleDialogButton(window.getButton(AlertDialog.BUTTON_NEUTRAL));
      return window;
    } catch (RuntimeException unavailable) {
      prefs.edit().putString("ui_window_error", Journal.describe(unavailable)).putLong("ui_window_error_ms", System.currentTimeMillis()).apply();
      return null;
    }
  }

  private void styleDialogButton(Button button) {
    if (button == null) return;
    button.setAllCaps(false);
    button.setTextColor(Ui.DARK_GRAY);
    button.setBackgroundColor(Ui.LIGHT_GRAY);
    button.setMinHeight(ui.dp(48));
    ViewGroup.LayoutParams raw = button.getLayoutParams();
    if (raw instanceof ViewGroup.MarginLayoutParams) {
      ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
      int horizontal = ui.dp(5);
      int vertical = ui.dp(4);
      margins.setMarginStart(Math.max(margins.getMarginStart(), horizontal));
      margins.setMarginEnd(Math.max(margins.getMarginEnd(), horizontal));
      margins.topMargin = Math.max(margins.topMargin, vertical);
      margins.bottomMargin = Math.max(margins.bottomMargin, vertical);
      button.setLayoutParams(margins);
    }
  }

  private AlertDialog showText(String title, String message) {
    if (!canShowUi()) return null;
    TextView text = ui.text(message, ui.size);
    ui.selectable(text);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(ui.background);
    scroll.addView(text);
    AlertDialog window = dialog().setTitle(title).setView(scroll).setPositiveButton("Close", null).create();
    return showDialogSafely(window);
  }
}
