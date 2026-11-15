package vip.thatiam.mml;

/** Plain-language explanations for every Settings control and Custom log-detail category. */
public final class SettingsHelp {
  private SettingsHelp() {}

  public static String title(String raw) {
    String label = clean(raw);
    if (label.startsWith("Log view:")) return "Log view";
    if (label.startsWith("App-data log limit:")) return "App-data log limit";
    if (label.startsWith("Log storage:")) return "Log storage";
    if (label.startsWith("App picker view:")) return "App picker view";
    if (label.startsWith("Text size:")) return "Text size";
    if (label.startsWith("System brightness:")) return "System brightness";
    return label;
  }

  public static String explanation(String raw) {
    String label = clean(raw);
    if (label.startsWith("Log view:"))
      return "Selects the saved-record overview shown on Home. Tap the Home view label to cycle Notifications, Communications, Apps, Device, Full, and Custom. Long-press that label to edit Custom directly. Views do not change capture and do not delete hidden records.";
    if (label.startsWith("App-data log limit:"))
      return "Sets the automatic-log storage budget in MML's private app data. When the budget is reached, MML follows its rolling-retention safeguards. USER entries and the mandatory Bloodguilt warning are protected from automatic rolling.";
    if (label.startsWith("Log storage:"))
      return "MML always keeps its authoritative SQLite journal in app data. You can additionally select a folder for readable UTF-8 .log.txt copies. The folder copy is a mirror, not the database.";
    if (label.startsWith("App picker view:"))
      return "Chooses how installed apps are shown in MML's app picker. List uses one full-width row per app. Brick uses compact auto-fit cells. This changes presentation only.";
    if (label.startsWith("Text size:"))
      return "Changes MML's application text size. Android font scaling and script shaping still apply.";
    if (label.startsWith("System brightness:"))
      return "Controls Android system brightness, the same value used after another app opens. MML needs Android's Modify system settings special access to set an exact brightness value.";

    switch (label) {
      case "Default Home app":
        return "Opens Android's Home-app chooser so MML can be selected as the device launcher. Android, not MML, owns this role setting.";
      case "Capture status":
        return "Shows the current state of notification capture, journal writes, optional history collection, selected-folder mirroring, storage safeguards, and recent capture errors.";
      case "Last crash / process exit":
        return "Shows MML's most recent recorded crash or Android process-exit information when available. This is diagnostic information and does not change capture.";
      case "Capture notifications":
        return "Master switch for MML's NotificationListenerService journal input. Turning it off stops MML from intentionally recording notification callbacks. Notification access in Android is a separate permission-like system setting.";
      case "Notification access":
        return "Opens Android's Notification access screen. MML needs this access for incoming notification capture. Permission to show MML's own notifications is separate.";
      case "Notification access help":
        return "Explains Android restricted-settings behavior and how to enable MML's notification listener when Android blocks sideloaded notification access.";
      case "Test notification capture":
        return "Posts a deliberate MML test notification and verifies that the notification listener actually commits it to the journal. This test notification is intentionally allowed through MML's self-notification exclusion.";
      case "Log entry notification settings":
        return "Opens Android's notification settings for notifications that MML creates when you use Notify on a saved log entry.";
      case "App activity history":
        return "Master capture switch for Android Usage Access history. When enabled and Usage Access is granted, MML imports all supported UsageEvents. Per-type capture switches are intentionally not used; Custom log detail is the single place to decide which saved UsageEvent categories are normally visible.";
      case "Usage access":
        return "Opens Android's Usage Access screen. App activity history cannot be read until Android grants MML Usage Access. The first retained-history import is saved as history and is not inserted into the live Home session.";
      case "SMS history":
        return "Imports SMS-provider history when Android grants READ_SMS. This is separate from message notifications and can include received, sent, draft, queued, failed, and outbox SMS records exposed by Android.";
      case "Call history":
        return "Imports Android call-log provider history when READ_CALL_LOG is granted. Records can include incoming, outgoing, missed, rejected, blocked, voicemail, and other call types exposed by Android.";
      case "Notified logs":
        return "Shows journal rows currently marked as having an MML Android notification. You can open the source row, remove one notification, or clear all notification state without deleting the source logs.";
      case "Check journal":
        return "Runs MML's SQLite integrity check on a background worker and reports the result. It is a diagnostic check, not a cleanup or deletion command.";
      case "About MML":
        return "Shows MML version, purpose, authorship/publishing information, and the Bloodguilt curse notice.";
      case "App info":
        return "Opens Android's App info page for MML. Android exposes controls there such as permissions, storage, battery behavior, and restricted-settings approval when applicable.";
      case "Choose log folder":
      case "Change log folder":
        return "Opens Android's document-tree chooser for the optional readable .log.txt mirror. The private SQLite journal remains authoritative.";
      case "Save to log folder now":
        return "Requests an immediate foreground mirror pass for pending .log.txt output to the selected folder.";
      case "Stop saving to log folder":
        return "Stops future .log.txt mirroring to the selected folder. Existing files are not deleted, and MML continues using its private SQLite journal.";
      case "Storage status":
        return "Shows current private-journal usage, the configured automatic-log budget, free-space safeguards, and selected-folder mirror state.";

      case "Notifications":
        return "Shows notification records of type posted or active. This includes newly posted or changed notifications and active notifications recovered after the listener reconnects. Notification removals are controlled separately.";
      case "Notification removals":
        return "Shows notification records Android reported as removed. A removal can result from dismissal, cancellation by the source app, replacement, or another notification lifecycle action.";
      case "Calls":
        return "Shows records imported from Android's call-log provider. This category is separate from call-related notifications.";
      case "SMS":
        return "Shows records imported from Android's SMS provider. This category is separate from SMS or messaging-app notifications.";
      case "App resumed":
        return "Shows Android UsageEvent ACTIVITY_RESUMED records: an app activity became active or returned to the foreground.";
      case "App paused / stopped":
        return "Shows Android ACTIVITY_PAUSED and ACTIVITY_STOPPED UsageEvents: an app activity left its actively resumed state or was stopped.";
      case "Screen / lock state":
        return "Shows Usage Access screen/keyguard events: screen interactive, screen not interactive, keyguard shown, and keyguard hidden. These are retained UsageEvents. USER_PRESENT is also captured directly as a DEVICE record, independently of Usage Access; older journals may additionally contain direct SCREEN_ON and SCREEN_OFF rows.";
      case "App interaction":
        return "Shows Android USER_INTERACTION UsageEvents. Android reports that the user interacted with an app; the app does not have to have just opened.";
      case "Foreground services":
        return "Shows Android foreground-service start and stop UsageEvents. A foreground service can run without the app's visual interface being in front.";
      case "Device startup / shutdown":
        return "Shows Android UsageEvents for device startup and device shutdown when Usage Access history exposes them.";
      case "Other usage events":
        return "Shows UsageEvents that do not belong to MML's named app, screen, interaction, service, or device-lifecycle groups. These are the most technical and potentially noisy Usage Access records.";
      case "MML / system diagnostics":
        return "Shows MML SYSTEM records such as logger state, collector errors, cursor corrections, storage/mirror diagnostics, and similar internal events. The mandatory Bloodguilt startup warning remains visible even when this category is hidden.";
      case "Device records":
        return "Shows direct Android DEVICE records. USER_PRESENT is captured directly and independently of Usage Access. Older journals can also contain direct SCREEN_ON and SCREEN_OFF DEVICE rows. USER_PRESENT is recorded whenever Android emits it; MML does not require a preceding screen event.";
      default:
        return "This Settings control changes the MML behavior named above. Long-press help is intended to explain consequences before you change a setting; no long press itself changes the setting.";
    }
  }

  private static String clean(String raw) {
    String label = raw == null ? "Setting" : raw.trim();
    if (label.startsWith("[x] ") || label.startsWith("[ ] ")) label = label.substring(4);
    return label;
  }
}
