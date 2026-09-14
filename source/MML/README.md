# Modern Mobile Launcher 1.6.6

Modern Mobile Launcher (MML) is the HOME application in the three-project suite.

- Application ID: `vip.thatiam.mml`
- Version: 1.6.6 (version code 15)
- Minimum Android version: Android 13 / API 33
- Compile and target SDK: 35
- Network access and analytics: none

## Calendar-first HOME

MML starts on its calendar instead of opening or focusing an editor. Each day count is:

```
entries = closed sessions + one saved Comments page, if present
```

The Calendar footer contains `Future events` at one-third width and `Current Session` at
two-thirds width, so Current Session is exactly twice as wide. Future events opens every saved
future Comments page in chronological order, soonest first; selecting a row opens that date.
Current Session opens the one live log. Back at the calendar root stays at the calendar, as a
HOME application should. MML hides only the status bar and preserves Android's
navigation bar; a swipe can reveal Android's status controls transiently. The current day uses
reverse video in both system themes.
Android 13's back dispatcher handles both gesture and three-button Back navigation without the
deprecated Activity callback.
The calculation uses the complete date in the selected calendar projection and the device's
current timezone. Calendar refreshes it on resume and while visible when Android announces a date,
clock or timezone change.

Gregorian, Hebrew, Islamic civil, Islamic Umm al-Qura and Chinese projections are available. A
closed session appears on each civil date containing its start, a real machine/user entry or a
legacy Comment. The administrative marker written by the next `MainActivity` startup does not
falsely place the old session on that later date. Every day is tappable, including an empty future
day. Its page has an editable Comments area and a separate Previous Session / Next Session pager.
Each closed-session page shows the entire session at full verbosity rather than filtering its
transcript to the tapped date. MML builds closed-session membership on a dedicated worker from a
process-cached compact day index that retains no transcript text. Month changes reuse the projected
index, and a date page loads and formats only its selected session off the UI thread.

The Calendar has no graphical brightness slider. A horizontal drag anywhere on the Calendar
screen changes Android's system brightness: right increases, left decreases, and movement across
75 percent of the screen width represents a 100-percentage-point adjustment before the result is
clamped to 1 through 100 percent. MML waits until the drag is clearly horizontal, so ordinary taps
and vertical Calendar scrolling remain child interactions. Android's Modify system settings access
is still required. MML never applies a window-only override and always returns its window to the
system value. The compact `Previous / month / Next` control is now the sole month-navigation path;
horizontal Calendar motion belongs only to brightness. Calendar carries no instructional paragraph
and uses 3dp side padding. Date numbers and entry totals use grayscale values separated by at least
50 channel levels in both ordinary and reverse-video cells. The paired light/night values remain
exact inversions and retain large-text contrast. Date labels are also 1.30 times the size of entry
totals, so meaning does not depend on gray alone.

At Android font scales of 150% and above, at 130% on phone widths through 360dp, or on a display
narrower than 320dp, the seven-column month changes to a full-width day list. Below that boundary,
grid cells use only a day number and a bare count capped at `99+`. Grid-day boxes have a 64dp
portrait and 56dp landscape minimum height, but retain wrap-content growth for Android large fonts.
The visible Previous and Next buttons change months. Horizontal drags anywhere on Calendar are
reserved for system brightness, while vertical scrolling and day taps remain available. Natural
Activity recreation supports portrait and landscape without forcing an orientation.

## Calendar Comments and planned days

A calendar date can hold one editable Comments document independently of launcher sessions. A new
or empty day opens directly in editing mode with a bottom `+ / input / Save` row. Its `+` opens the
application-shortcut picker directly; it is not a navigation or settings menu. A saved page can
be viewed, edited, deleted after confirmation, or replaced by Save. MML records the intended
Gregorian civil date and timezone separately from the instant and timezone when the page was
created. Switching to a Hebrew, Islamic or Chinese projection changes only where that intended
date is displayed; it does not change the saved date.

Android ICU supplies the Hebrew projection. Its civil dates change at local midnight so they remain
compatible with MML's local-noon canonical date mapping; this is not a traditional sunset boundary.
Month titles use Tishri through Elul and distinguish Adar I and Adar II in a leap year.

Comments resemble source-code comments. MML adds `#` to every displayed logical line without
inserting it into the stored text. A user may also type `#` explicitly; presentation remains
idempotent. A document containing only whitespace and optional `#` markers has no payload.

Web URLs and phone numbers become active only after an explicit tap on a displayed Comment. MML
hands `http` and `https` links to Android with `ACTION_VIEW` and phone numbers to the dialer with
`ACTION_DIAL`; it never places a call itself. The editor's `+` control directly opens a picker that
can insert a strict `app:package.name` shortcut chosen from Android's launcher applications. The
saved view renders it as an App link; tapping it still requires confirmation before launch. Missing
apps and rejected launches fail visibly. No Comment content opens automatically. The day page
relies on Android Back or Escape to return to Calendar and does not repeat Calendar or Current
Session buttons.

## Current Session chat log

The live view is chat-log shaped:

```
Current Session
readable entries growing downward

[ native Android input field, full screen width                     ]
[ Launcher                         ] [ Send                           ]
```

Each entry begins with its recorded `HH:mm` time. Comment rows preserve every logical line and grow
without ellipsis. Automatic machine rows are compacted to one logical line, bounded to 180 code
points and displayed in at most three wrapping lines; tapping one opens its full stored timestamp,
type and detail. Captured-notification summaries are similarly bounded to 240 code points and three
lines. A notification row tap attempts the still-active notification's primary tap action, while a
long press opens the saved fields. Explicit Comment newlines remain visible with one `#` marker per
logical line. The stored Comment retains the exact submitted text. `Send` performs a synchronized,
append-only write before clearing the composer. Empty or whitespace-only messages are rejected, and
a failed write leaves the text in place.

The full-width editor grows through up to four portrait lines or two landscape lines, then scrolls
internally. Its measured maximum also adapts to the height remaining above the active keyboard, so
the action row takes priority over an extra visible editor line. The weighted transcript gives up
height first, while Android IME and navigation insets keep `Launcher / Send` visible. Launcher opens
App list directly. The large, multiline selection list scrolls behind a fixed bottom
`Launch / App settings` row; Current Session is not duplicated there. A single tap selects an
application; a second tap on the same row within Android's double-tap interval opens it. Launch and
keyboard Enter remain available. `App settings` opens Android's application-details screen for the
selected row, including Android's Force stop control when the platform makes it available. Long
pressing an app row performs no MML action; application details is available only through the fixed
`App settings` button. Returning from application details restores App list.
The fixed `MML preferences` button above the app list opens MML's own surface titled `Settings`,
which places `Open Android settings` at its top and contains the log-directory selector. The
composer remains a normal Android `EditText`, so touch keyboards, hardware keyboards,
Nanopad/Nanoboard signals, Bluetooth input, accessibility input and voice-capable IMEs use
Android's ordinary text path. MML never automatically focuses the composer or asks Android to
display the keyboard.

## Machine log detail

Settings provides four nested levels for automatic journal rows:

```
Notifications only < Important < Actions < Full
```

User Comments and captured-notification rows remain included at every level. `Important`, the
default for a new or upgraded installation without a recognized preference, adds failures,
destructive-history results, system-brightness changes and session-boundary evidence. `Actions`
adds ordinary launches, app-details requests, successful notification-action dispatch, calendar
saves and future-summary opens. `Full` adds routine panel, HOME, launcher-entry and resume rows.
Unknown future machine-event types are Full-only.

The selected level is both a recording rule and a Current Session display filter. An excluded
automatic event does not block the underlying launch, settings, save or cleanup operation and is
not written. The rule also covers optional lifecycle rows written while a session starts or closes;
session identity, OPEN/CLOSED status and other structural metadata still persist even when such a
row is omitted. Raising the level later cannot reconstruct a skipped event. Lowering the level
immediately hides disallowed automatic rows in the live view, but does not delete rows already
stored by an earlier level or by a pre-1.5 version. Closed-session pages therefore show every row
that was actually stored. Comments and captured notifications are never removed by changing this
setting.

## Log storage management

MML has two mutually exclusive log-management states. The default is app-managed storage. In this
state the directory field is blank, logs remain in MML-managed application storage, no Storage
Access Framework tree grant is held, and Settings provides `Clear app-managed logs`. That operation
removes current and closed session log contents and captured-notification log rows while preserving
the Current Session identity and preserving Calendar Comments/Future events as separate planning
data. Android also removes MML-managed application data when app data is cleared or MML is
uninstalled.

`Specify user-managed log directory` opens Android's system directory picker. MML requests only a
persisted read/write grant for the selected tree and requests no broad external-storage permission.
Inside the selected tree it creates an `MML Logs` directory containing ordinary UTF-8,
line-oriented session, Comments and pending-notification files plus a format README. The directory
field displays the effective selected location. Erasing that field returns logging to the default
app-managed state.

When a user-managed directory is active, MML does not offer its app-managed clear, age-deletion or
captured-log deletion controls. Existing files in the user-managed directory belong to the user.
MML never silently deletes them when the directory field is cleared or another directory is
selected. To copy, edit, archive or delete those files without a later synchronization pass, first
erase the directory field so MML stops writing there and releases its persisted grant, then use a
file manager. The Settings status reports the selected location, the last successful save time or
the latest failure.

MML retains bounded private crash-recovery working state while a user-managed directory is active
because Calendar, Current Session, notification capture and process recovery cannot transact safely
against every local, removable or cloud-backed document provider. This internal recovery state is
not presented as a second user-selected log destination. The user-managed directory remains the
readable, copyable and backup-capable form of the user's logs.

Before committing a new directory, a serialized background worker builds a complete `MML Logs`
copy. A failed initial write leaves the previous storage choice active and the working journal
untouched. Completed mutations from both the Activity and notification-listener path schedule the
same process-global worker. Requests are coalesced into passes spaced by at least five seconds,
unchanged files use sizes captured in the directory enumeration rather than one query per file, and
growing event logs use bounded incremental appends when the provider supports them. Repeated
provider failures back off to at most one retry per five minutes while new changes continue.
Enumeration and index counts are capped at 8,192 and index input at 512 KiB, with one bounded
entry map and failure-visible overflow instead of unbounded in-memory growth. All tree enumeration
and bulk copying remain off the UI thread.

Every replacement is first written, synchronized and validated as an `.MML-pending.txt` document.
All generated text and recovery filenames end in `.txt`, including session files such as
`<id>.events.txt`, because Android filesystem providers may append a normal extension when a
requested text filename ends in an unrecognized suffix. MML deliberately avoids provider rename:
it creates the exact final display name, verifies file-versus-directory kind and stored bytes, and
removes pending only after the final is proven. A complete pending copy is recovered before any
rewrite. Unknown provider sizes require streaming readback before MML trusts or indexes a document.

MML adopts `MML Logs` only when it is truly empty or a complete current/legacy README or index marker
proves MML ownership. Existing generated files are updated or removed only when the prior complete
index proves that exact path was managed. An unindexed same-name file is adopted only when a bounded
streaming comparison proves its bytes exactly match the current source; otherwise MML preserves it
and reports a visible conflict. Changing or clearing the selection releases obsolete grants without
deleting user-owned files. Pending grant acquisition and release are durably journaled so process
death cannot silently orphan an untracked permission.

## Session boundary

Since 1.2.0, a launcher session means one MML process in which `MainActivity` is created, not one
HOME key press or foreground resume. A listener-only process can queue captures without creating a
session. The first `MainActivity` creation in a process starts a new session. Activity recreation
within that same process reloads the same open identifier and restores the current screen, month
and unsent composer draft. `onNewIntent`, returning from an app, font/theme recreation and
ordinary resume do not split the session.

Android does not guarantee a callback when killing a process. Therefore the next `MainActivity`
startup in an MML process archives any prior open session and records only that it was observed.
It does not claim that the prior process crashed or ended cleanly. An observed Android shutdown
signal closes the current session on a best-effort basis.

## Closed-session verbosity and migration

The current session is intentionally a shortened live view. Closed-session pages expose complete
timestamps, stored event types and full details through Previous/Next Log Pages bounded to 160
physical rows and 512 KiB. Malformed or oversized legacy rows are reported without modifying the
durable journal. User Comments are immutable `comment` events
interleaved chronologically with machine events. MML continues to read the older `user_note` type
for storage compatibility. Editable daily notes created by MML 1.1.0 and earlier remain readable in
closed logs as explicitly labelled, read-only legacy Comments. Captured-notification rows expand to
their application, package, posted time, captured timezone, title, body and subtext. The stable
capture identifier remains internal for deduplication.

The live Current Session retains at most 160 recent rows and approximately 512 KiB of encoded event
content. New rows append incrementally to the visible view; bounded eviction does not rebuild or
materialize the lifetime transcript. Individually oversized rows remain durable and are reported by
the closed-session archive instead of forcing the long-lived Activity to retain them.

## Quiet Future events notification

MML can maintain one low-importance Android notification for saved future Comments pages. Its
collapsed title is `Future events...` and its collapsed text reports the total, such as
`5 future events`. Expanding the notification shows a chronological list with one event per line:

```
8/30. Future event one.
8/31. Second future event
```

One future event means one nonempty calendar Comments document whose intended date is strictly
after today in that document's recorded timezone. Today and past dates are excluded. For the
expanded presentation, MML retains at most the first 20 chronological events, compacts each
Comments body to one line of at most 120 Unicode code points, and bounds the complete body to 3,200
code points. A final `... N more future events` line is added when it fits. Full multiline Comments
remain available in MML's Future events screen.

Tapping the notification opens the chronological Future events screen and Android auto-cancels that
instance. MML does not immediately repost it on the resulting resume. If the notification remains
visibly active, an ordinary resume can refresh its count and lines after a date, timezone or
Comments change. A dismissed notification stays gone until a later future-event count change or a
normal fresh `MainActivity` startup posts the current list again.

On Android 13, posting requires the ordinary `POST_NOTIFICATIONS` runtime permission. MML's
`Settings` screen can enable or disable the notification, retry the permission request, and open
Android's `Future events` channel controls. Disabling it cancels MML's notification. This posting
permission is distinct from Android Notification Access for optional capture below. Android's own
lock-screen and channel settings determine where the expanded future Comments text is visible.

## Opt-in captured notifications

MML also declares a `NotificationListenerService`, protected by Android's
`BIND_NOTIFICATION_LISTENER_SERVICE` permission. It cannot receive another application's posted
notifications until the user explicitly grants MML Notification Access on Android's special-access
screen. This is separate from `POST_NOTIFICATIONS`: posting lets MML show its own summary, while
Notification Access lets MML read incoming notification fields.

When access is granted and capture is active, MML copies each new notification's visible app label,
source package, title, body, subtext, posted time and the capture timezone into the current-session
log. The live log uses one bounded summary row; after the session closes, the full captured
fields are readable. MML excludes its own Future events notification, ignores group-summary
duplicates, and uses stable capture identifiers to avoid writing the same captured content twice. A
capture that arrives before the launcher Activity establishes its current session waits in a durable
private inbox and is drained into that new session. The inbox is atomically replaced, de-duplicated
by a SHA-256 capture identifier and retains the newest pending entries within a total limit of 512
valid captures across session logs and the inbox. While no launcher session is writable, a full
pending share discards its oldest capture to retain the newest one. After the inbox drains into an
OPEN session, reaching the 512-capture total reports backpressure in Settings and rejects new unique
posts without repeatedly rebuilding the live log. Clearing captures or deleting closed history that
contains captures frees capacity.

Android can disconnect a notification-listener binding while leaving its special-access grant on.
MML reports the live connection separately from that grant, requests Android's supported rebind
path after a disconnect and while the foreground Activity observes a granted-but-disconnected
state, and reconciles Android's still-active notifications after connection. Exact capture IDs keep
that recovery idempotent. Rebind requests are debounced, and capture storage runs on one bounded,
serialized worker rather than Android's listener callback thread. Repeated updates for the same
queued notification are coalesced. Explicit clearing is ordered on that worker after already
accepted captures, so old queued work cannot restore data after Clear. Notifications already
removed during a disconnected interval cannot be reconstructed because Android does not expose
them as active.

Listener callbacks convert notifications and active arrays to bounded immutable snapshots before
queueing. Static queue records contain only application Context, bounded data and process-safe
stores--never a listener service, Activity or view tree. Inbox drain/dedup and privacy clearing run
on that same serialized background lane; a drained batch uses one flush/fsync rather than one per
row. Process-global generation/result state plus a package-scoped broadcast lets a recreated
Activity reload the bounded 160-row/512-KiB current-session window after completion. The Activity
marks a capture generation observed only after that window and any Clear-driven archive
invalidation are applied, so rotation transfers rather than drops an unfinished refresh. Broadcast
bursts collapse into one latest-generation, 100-ms-debounced background load; stale callbacks
cannot rebuild a screen, and capture redaction resets archived byte cursors before DAY rerender.

Only a Current Session notification row can attempt the source notification's primary tap action.
MML never persists a `PendingIntent`. At tap time the connected listener re-reads Android's active
notifications, requires an exact match to the saved bounded snapshot, and invokes that active
notification's current `contentIntent` without a fill-in Intent. Therefore the action works only
while Notification Access is connected, the notification is still active and unchanged, and the
source supplied a `contentIntent`. Action buttons and remote-input replies are not replayed. A
successful send records `notification_action_opened` only when the selected log level includes
Actions; an auto-cancel notification is canceled on a best-effort basis. Failure or expiry opens
the saved details with a precise status instead of manufacturing an action from journal text.

MML's `Settings` screen shows Android access, listener connection, active/paused state, the fixed
512-capture retention bound and any in-progress/completed capture-maintenance result without
synchronously scanning every archived event file.
`Enable capture` activates the local preference and, if necessary, explains and opens Android
Notification Access. `Pause capture` stops new copies without revoking Android access. `Manage
Android Notification Access` lets the user grant or revoke the special access itself. `Clear
captured notifications` uses confirmation and crash-resumable redaction to remove capture rows
from current and archived sessions plus the pending inbox while preserving all other events,
Comments and plans.

## Brightness, retention and privacy

Calendar's slider is exclusively system-wide. Without Android Modify system settings access it
returns to the actual system level and opens the permission explanation; after access, it writes
`Settings.System.SCREEN_BRIGHTNESS`. Adaptive brightness may subsequently change the system value.
Settings exposes the access control and Android Display settings, but contains no second brightness
slider or app-only override. Age deletion applies only to closed sessions and always preserves
the current open session and separate calendar Comments. The administrative next-start boundary
does not make an old session artificially young. `Clear journal history` deletes closed sessions
after confirmation while preserving the live session and calendar Comments. `Clear all calendar
Comments` is a separate confirmed operation. Per-session tombstones make interrupted session
deletion resumable, while the Comments document uses atomic replacement.

Retention counting and mutation run on one process-global single-flight worker. Rotation cannot
start an overlapping operation or lose the count-before-confirm decision, and Settings disables
maintenance controls while work or an unconsumed result remains active. Exact completion
acknowledgment precedes a confirmed follow-on delete/clear. Calendar Comment save/delete uses the
same lifecycle-safe lane, disables its composer until reconciliation, retains the draft on failure
and refreshes only a generation-matched screen after durable success.

Session Comments and calendar Comments accept at most 32,768 Unicode characters and 128 KiB UTF-8.
MML retains at most 16 nonempty unsaved day drafts in process memory. The independent calendar-
Comments document is limited to 4,096 dates and 16 MiB total; exceeding either limit fails visibly
before replacement. The Future events notification scan streams the total and only its bounded
summary rows on a coalesced process worker rather than parsing the aggregate on the UI thread.
The full in-app Future events list is also loaded and sorted on the existing background history
worker, then displayed through a virtualized Android list.

Journal data remains under MML's private files directory. Android backup is disabled, no Internet
or direct-call permission is requested, and no automatic export exists. `POST_NOTIFICATIONS`
governs MML's own optional Future events notification. The separately declared notification-
listener service remains inert until the user grants Android Notification Access and can be paused or
revoked. See the audit documents for exact input, lifecycle and privacy limits.

## E-ink presentation

Light and dark system themes select paired grayscale palettes. The light palette uses a medium
gray background and dark-gray text; the night palette uses the inverse relationship. There are no
pure-black or pure-white content colors. `forceDarkAllowed` is disabled so Android does not apply a
second transformation before the user's own color inversion. MML hides only the status bar by
default and allows Android to reveal it transiently by swipe; navigation, display-cutout and IME
insets keep controls out of Android-owned areas. The visible navigation bar, fields, native list and
dialog surfaces, controls, and launcher icons use the same grayscale family.

## Build

Open this directory in Android Studio with JDK 17 and Android SDK 35, or run:

```
./gradlew assembleDebug
```

After installation, use MML's `Settings` screen to choose the Android Home application. Android
retains control of the lock screen and authentication.
