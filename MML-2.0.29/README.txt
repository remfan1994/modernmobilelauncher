MML 2.0.29 - Modern Mobile Launcher

Application ID: vip.thatiam.mml
Version: 2.0.29 / versionCode 20029
Minimum Android: 13 (API 33)

PURPOSE

MML is a log-first Android Home screen. The Home screen is the current activity journal plus
one deliberate action: Make a log entry. App launching, notification capture, shared text, and
selected Android history all feed the same journal rather than separate launcher subsystems.

HOME

The reverse-video date sits above the live log. Tap the date to append another Bloodguilt curse
warning. The Bloodguilt entry is protected from filtering/automatic rolling and its detail action asks before leaving MML for the internet, then opens https://www.bloodguiltcurse.net/ only after Continue.

Actionable single-entry rows keep the normal dark background and use MML's two-gray checkered
border. Tap a row to inspect it; the Home-row tap itself never executes the action.

MAKE A LOG ENTRY / APP PICKER

Make a log entry opens the searchable installed-app picker as a full-screen MML surface. Find an
app is at the top. A single full-width Settings button spans the bottom; this is the only direct
MML Settings entry point.

Normal tap saves one USER/app_launch row, waits for journal acknowledgement, returns MML's
internal state to Home, then launches that app. Long-press opens a full-screen App info page with
the package name, version name/code, and a link to Android's package App info page (including
Force stop).

Settings > Display chooses List or Brick view. List is one-line app names. Brick is an auto-fit
multi-column grid whose compact cells show at most the first seven Unicode code points of each app
name. The picker itself has no List/Brick toggle.

ENTRY DETAIL

Entry detail is a full-screen MML surface. Long-press is Android text selection. A normal tap on
the detail text or blank detail viewport performs the contextual action when one exists: launch an
app, request confirmation before opening the Bloodguilt URL, or resolve a current live notification action. A tap while text
remains selected cannot activate the entry.

Small trailing metadata includes Source, Type, Provenance, and Notification: posted when the row
is currently notified.

NOTIFY / UNNOTIFY

Notify gives one saved log entry a persistent Android notification. The source row remains in its
original journal position and the control becomes Unnotify. Unnotify removes the Android card but
keeps the log entry. Grouped display rows do not have synthetic Notify.

Every MML log notification has:
- main tap: perform the useful action;
- Log: open the exact source journal entry;
- Remove: unnotify it directly from the notification area.

Pushed cards are ongoing/non-auto-canceling. A fresh MML process reconciles Android cards from
durable notified journal rows. At most one app-launch card is durable per package: notifying a
newer RFS launch row transfers the RFS notification to that newer row rather than creating another
RFS launcher card. Different apps and ordinary log notifications remain independent.

Settings > Logs > Notified logs lists current notified rows with source-log access, per-entry
Remove, and Clear all. Clear all does not delete journal rows.

MML never journals its own ordinary log-entry notifications. The explicit notification-capture
test is the only MML-owned notification intentionally admitted to capture.

TEXT LOGS

The working journal is Android-private SQLite/WAL. MML stores logs in app data by default. A user
may additionally choose a directory for readable UTF-8 .log.txt copies. Choosing a directory does
not replace the app-data journal.

The .log.txt format is a stable versioned text interface suitable for grep, sed, awk, shell tools,
ordinary editors, and archives. See TEXT-LOG-FORMAT.txt. Version-1 field order and semantics remain
unchanged in 2.0.29.

STORAGE / RESOURCE MODEL

Automatic machine history uses a configurable 32-512 MiB app-data budget and rolls old automatic
rows while retaining USER rows and Bloodguilt warnings. Machine-event journal transactions use a
250 ms quiet window with a 1000 ms hard batch age and early flush for large bursts. Deliberate USER
writes remain immediate.

MML has one persistent system-bound NotificationListenerService, no wake lock, no network
permission, no foreground collector, and no periodic polling loop. Optional Usage/SMS/call catch-up
and selected-folder output run only while MML is foreground-visible and time out when idle.

COLORS

MML uses only dark gray #2B2B2B and light gray #AAAAAA. Action cues use the checkered border so
large Home-row backgrounds remain dark and compatible with display color inversion.

BUILD

The source targets compileSdk 35.

    ./gradlew :app:assembleDebug

Or:

    python3 tools/build_apk.py . --sdk /absolute/path/to/android-sdk

Use the same signing key for an in-place update.
