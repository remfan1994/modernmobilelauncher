# MML Lifecycle Audit

## Session equation

```
one launcher session = one MML process in which MainActivity is created
```

The first `MainActivity.onCreate` in a process calls `startLauncherSession`; a listener-only process
can queue captures without creating a launcher session. A process-static
session identifier makes subsequent Activity creation in that process reload the same OPEN
record. This includes recreation for font scale, theme, locale, orientation, window size, keyboard,
or other configuration changes. MML lets Android recreate the Activity so the current resource
palette and large-font calendar policy are reevaluated.

The following do not start sessions:

- another HOME intent delivered to the single-task Activity;
- returning from an app or Android settings;
- Activity pause/resume;
- switching between Calendar, a date page, Current Session, App list and Settings;
- saving, editing or deleting a calendar Comments document;
- opening MML from its Future events notification;
- receiving a notification through the listener while the process session remains writable.

A HOME or ordinary launcher-entry request always selects Calendar. MML marks both the handled
Activity intent and saved Activity state with the current process-session identifier. That lets a
configuration relaunch or same-process stopped-Activity restoration be distinguished from a
genuinely fresh launcher request even if Android destroyed the prior Activity instance. The latter
also clears any pending external-return route before `onResume` runs.

If Android kills MML, its static identifier disappears. The first Activity in the next process
archives any surviving OPEN record and starts the new one. `onDestroy` is not used as a boundary,
because Android may omit it and may call it for reasons unrelated to a process ending.

Calendar Comments do not define or belong to a launcher session. They survive a process boundary,
the closure of the session in which they were written, and later session-history deletion.

## Evidence vocabulary

| Event | Direct evidence |
| --- | --- |
| `launcher_session_started` | This process established its writable session record. |
| `comment` | MML Send durably appended the exact supplied Comment text. |
| `user_note` | A compatible MML 1.2 or earlier Comment row was loaded. |
| `notification_captured` | One bounded NC1 notification snapshot was durably attached to this process session. |
| `calendar_comments_saved` | A date's independent Comments document was saved before this event was attempted. |
| `calendar_comments_deleted` | A date's independent Comments document was removed before this event was attempted. |
| `calendar_comments_cleared` | Direct confirmation removed all independent date Comments. |
| `panel_opened` | An internal panel or picker was requested. |
| `launch_requested` | The row was synchronized before MML called Android's launch API. |
| `launch_failed` | Android synchronously rejected or failed the request. |
| `app_details_requested` | MML requested Android application details for the selected App-list row. |
| `notification_action_opened` | The connected listener successfully sent the exact matching active notification's current contentIntent. |
| `future_notification_opened` | MML received its authenticated notification PendingIntent's Future events action; this does not prove a human tap. |
| `home_requested` | Android delivered another HOME intent to the existing Activity. |
| `launcher_entry_requested` | Android delivered a non-HOME launcher entry request. |
| `home_resumed` | MML later received a foreground resume. |
| `shutdown_observed` | The process received Android's shutdown signal. |
| `later_launcher_startup_observed` | A later process found and archived an older OPEN record. |

Invalid implications remain explicit:

```
launch_requested -> target_rendered                     false
home_resumed -> previous_application_closed             false
later_launcher_startup_observed -> prior_process_crashed false
shutdown signal absent -> abnormal shutdown              false
notification posted -> future Comment was individually scheduled false
future_notification_opened -> any event row viewed       false
notification_captured -> source notification opened      false
Notification Access granted -> capture preference active false
```

The later-start administrative event is visible in the full archived transcript, but its later
timestamp is excluded from calendar membership and retention age. Otherwise a dormant prior
session could be falsely assigned to the new day or kept indefinitely.

## Nested recording policy

Automatic evidence is conditional on the Settings choice:

```
Notifications only < Important < Actions < Full
```

`Important` is the default for a missing or unknown preference. Comments, legacy Comments and
captured notifications are admitted at every level. Important adds failures, destructive-history
outcomes, system-brightness changes and boundary evidence. Actions adds ordinary launches,
application-details requests, notification-action success, calendar saves and Future events opens.
Full adds routine session, panel, HOME, launcher-entry and resume rows. An unknown future machine
type is Full-only.

`appendMachineEvent` checks this rule before touching storage. If an automatic type is excluded it
returns success so the associated launch, settings, save or cleanup operation proceeds without an
audit row. JournalStore receives the same policy when a process session starts or closes and omits
excluded optional lifecycle rows. It still creates session identity and OPEN state, closes an older
OPEN session observed by the next process, and persists CLOSED state after a shutdown observation;
the level controls evidence rows, not structural metadata or status. This is not an authorization
gate. No later increase can reconstruct a skipped event. The same rule filters Current Session, so
lowering the setting immediately hides disallowed machine rows there but does not delete rows
stored under an earlier level or by a pre-1.5 release. Closed-session output intentionally ignores
the later preference and shows every row that was actually stored. Notification capture remains
admitted at every level and is governed separately by Notification Access and the capture
active/paused preference.

## State and recovery

```
new process session                    -> OPEN
observed Android shutdown              -> CLOSED
next MainActivity startup observes OPEN -> CLOSED + administrative boundary
legacy unmatched boot journal          -> UNCLOSED (migration only)
```

Calendar and retention operate on non-OPEN records only. The live session is excluded. A process
never silently creates a second record if its expected OPEN identifier becomes unavailable; the
operation fails visibly instead of violating the one-process/one-session invariant.

Activity recreation restores the process screen, calendar month, selected day, intended target
date and timezone, closed-session pager index, date-edit mode, pending external-return route, and
the appropriate unsent composer draft. Current Session has one process draft; date editors use a
separate process-local draft per target date and timezone. Only Send creates a durable session
Comment, and only Save creates or replaces a durable date Comments document. Process death can
therefore lose either kind of unsent draft, which is consistent with it not yet being logged.

A date page restores even when it has no closed sessions. Saved calendar Comments are reloaded
from their independent file after process death; edit state and an unsaved draft are not.

## Resource lifetime and heavy use

Every screen replacement releases references to the detached editor, Current Session transcript,
Settings status row, selected-day session list and App-list data before constructing the next
screen. The current content view is therefore the only rendered screen tree deliberately retained
by the Activity. Opening empty calendar dates does not add empty entries to the process-local draft
map; only a nonempty unsaved draft remains there.

Process startup finds stale OPEN records from their small metadata and state files without loading
all archived event transcripts. Notification capture identity discovery streams event files and
retains only the bounded capture-ID set. Explicit captured-notification clearing likewise streams
each event log to a synchronized temporary replacement, so redacting a long journal does not first
duplicate that complete log in a Java StringBuilder.

Calendar history work runs on one Activity-owned, interruptible worker with a one-entry queue; a
new request cancels the running Future, clears the queued predecessor and admits only the latest.
Its compact process cache retains session
identity/status/start order plus one timestamp/timezone representative for each unique Gregorian
civil day/timezone pair; it retains no event detail or legacy-Comment text and never scans the
excluded OPEN transcript. A selected projection is cached across month changes. A date page then
loads and formats only one 160-row/512-KiB closed-session page on that worker, so the UI thread never
parses all history and the date pager retains compact references plus one bounded visible page.
Previous/Next Log Page cursors provide explicit access to the complete durable record. Every
request carries a screen generation; screen replacement invalidates stale callbacks, and
`onDestroy` interrupts and purges the worker before releasing view/data references. History deletion and
captured-notification redaction invalidate both compact caches. Oversized or malformed physical
rows advance the cursor and are reported without being materialized or rewritten. Legacy whole-
session APIs are capped at 4 MiB per session and 16 MiB per list.

Age counting/deletion, Clear history, Calendar Comment save/delete and Clear all Comments share one
process-global single-flight coordinator. It retains application-safe storage arguments, broadcasts
package-scoped generations, and lets a replacement Activity reconcile busy/completed state once.
No later operation can start while a completed result is unconsumed; exact-generation
acknowledgment makes count-to-delete/clear handoff once-only. Nonzero count results remain pending
across rotation until the user confirms or cancels; zero/error results release disabled controls
without forcing navigation back to Settings. Day Comment controls stay disabled while their
mutation is busy or awaiting reconciliation, so post-Save typing cannot be cleared by an older
completion. Long retention and
capture-ID scans hold neither the JournalStore monitor nor the process storage lock; only validated
per-record deletion commits take the short mutation lock.

User-authored journal text is limited to 32,768 code points and 128 KiB UTF-8 at UI and storage
boundaries. The process draft cache retains at most 16 nonempty bounded day drafts. Calendar
Comments retain at most 4,096 date rows/16 MiB total; write-limit failure leaves the source and
draft intact.

Portrait/landscape rotation remains an ordinary Activity recreation, not a forced orientation or
session boundary. The date composer stays horizontal. Current Session instead puts its full-width
field above a fixed Launcher/Send row; landscape reduces vertical padding and limits the field to
two visible, internally scrolling lines. Current-log rows re-render with `HH:mm`. Comments
retain explicit newlines and unlimited wrap-content height; machine and notification summaries use
at most three visual lines and expose full detail through tap or long press. Shared app/picker rows
likewise re-measure to their multiline content after recreation. App-list Launch and Preferences are
outside its scrolling ListView. Returning from an app launch routes to Current Session; returning
from Android application details restores App list; other Android panels restore the requesting
MML screen.

Window-inset changes also do not define a session. MML hides only the status bar, configures Android
to reveal it transiently by swipe, and preserves navigation-bar, display-cutout and IME insets. It
reapplies status-bar hiding when focus returns without hiding navigation controls. Opening the IME
therefore changes safe bottom padding but neither starts a session nor durably saves a draft.

Calendar creates no brightness widget. At pointer-down its outer gesture surface reads Android's
current system brightness; a clearly horizontal drag anywhere on Calendar writes
`SCREEN_BRIGHTNESS` proportionally when Modify system settings access is granted. A drag across 75
percent of screen width spans 100 percentage points, right increasing and left decreasing. Without
access, the completed gesture opens the permission explanation. MML clears any window brightness
override during creation and resume, so the window always follows Android. This setting operation
does not split the session. Android adaptive brightness can later alter the system value.

Administrative next-start types from MML 1.0, 1.1, and 1.2 are all excluded from calendar dates
and retention age: `session_unclosed`, `prior_boot_boundary_inferred`, and
`later_launcher_startup_observed`.

## Future events lifecycle

`MainActivity.onCreate` creates or reuses Android's low-importance `Future events` channel. MML
streams the total plus at most 20 compact chronological summaries on a latest-only process worker at
the first normal `MainActivity` startup in a process, permission completion, after calendar Comments
are saved, deleted or cleared, and on ordinary resume only when Android still reports the
notification active. No aggregate Comments scan runs on the UI thread. Coalescing keeps the strongest
pending posting intent and earliest unobserved mutation baseline. A completion stays unacknowledged
and mergeable until the process-global Activity generation consumes it, so a second scan cannot
overwrite an asynchronously delivered result. The policy includes nonempty documents whose intended
date is strictly after today in each document's recorded target timezone. It ignores one malformed
record rather than hiding the remaining count and summaries.

The dedicated Future events screen independently loads the complete bounded Comments collection on
the latest-only background history executor, filters and sorts it, then publishes a virtualized list
to the matching Activity generation. Rotation, navigation or another history request cancels or
invalidates stale delivery; no worker callback retains the replaced Activity or screen tree.

The preference defaults to enabled, but Android 13 will not display the notification until
`POST_NOTIFICATIONS` is granted. The first qualifying save can request permission once; the Settings
Enable control can request it again explicitly. Disable cancels MML's notification, and Android's
channel settings remain authoritative. Tapping the notification delivers an immutable one-shot,
single-top PendingIntent carrying an app-private random token that selects Future events once per
posted instance. An explicit custom-action launch without that token is ignored. Android auto-cancels
that instance, and a one-resume suppression guard prevents the resulting resume from reposting it.
Other resumes refresh a still-active notification, including after a date, timezone or Comments
change, but never resurrect a dismissed instance. A later future-event count change or normal fresh
process can post the current list again at its first `MainActivity` startup. The action does not
create a special session and does not depend on Notification Access.

## Incoming-notification capture lifecycle

Android may start `MmlNotificationListener` before the launcher Activity. The listener is protected
by `BIND_NOTIFICATION_LISTENER_SERVICE` and receives posted notifications only after the user grants
MML Notification Access in Android's special-access settings. `POST_NOTIFICATIONS` is irrelevant to
this inbound path. The local capture preference defaults active, can be paused without revoking
Android access, and is checked before MML reads fields from each post.

The Android access grant and the live listener binding are independent state variables. On
`onListenerDisconnected`, MML clears its connected reference and calls Android's `requestRebind`.
MainActivity also requests a rebind when it resumes with capture active and access granted but no
connected listener. After `onListenerConnected`, MML enumerates `getActiveNotifications` and passes
each eligible snapshot through the same bounded, de-duplicated capture path. This recovers still-
active posts from a disconnected interval without duplicating stored snapshots. Android provides no
history for notifications already removed during that interval. A five-second process-local guard
coalesces repeated rebind requests. Posted captures and active-notification reconciliation execute
on one bounded, serialized worker, keeping package lookup and durable journal synchronization off
Android's listener callback thread. Repeated pending updates with the same Android notification key
are coalesced. An explicit storage fence places capture clearing after every capture already
accepted by the worker; later notification callbacks remain later in queue order. Each stored
capture or attempted Clear completion (including a partial-redaction failure) advances a process
generation and sends the package-scoped refresh. An
Activity advances its process-observed generation only after the bounded Current Session window
and any Clear-driven archive cache/cursor invalidation have been applied, so a replacement Activity
retries work canceled during receiver or rotation handoff.

Before queue admission, callbacks copy only bounded `CapturedNotification` values and clear raw
`StatusBarNotification` arrays. Typed static queue entries hold the application Context and bounded
data/process stores; no queued lambda captures the listener service or an Activity. Inbox drain and
Clear are typed operations on this same lane. Drain appends an accepted batch with one durable
flush/fsync and clears the inbox only after the batch succeeds. Clear publishes process-global
busy/result state. The Activity never drains or clears on its UI thread; completion generation
causes a latest-only, 100-ms-debounced background current-session-window refresh. Generation,
session and destroyed-Activity guards reject stale callbacks; capture Clear resets archived byte
cursors before DAY rerender. A replacement Activity discovers the same process result.

The listener ignores MML's own package and `FLAG_GROUP_SUMMARY` posts. It does not capture removal
callbacks. For other posts it obtains the visible application label with package fallback, source
package, title, body, subtext, Android post time and current capture timezone. Fields are normalized,
control-safe and code-point bounded before they become an NC1 detail. A fixed 64-character SHA-256
identity covers Android's notification identity, source package, post time and the exact bounded
title/body/subtext, making an identical retry idempotent while allowing changed content to be
recorded. App label and capture zone do not participate in identity.

`MmlProcessSession` exposes the current OPEN identifier only inside the MML process. If it is
available, the listener appends `notification_captured` directly. If no Activity has established a
session, or the identifier is no longer writable, the listener synchronizes the capture into the
private `notification-inbox`. Separate Activity and listener store instances share a process-wide
storage lock. The complete inbox snapshot is atomically replaced, de-duplicated by capture ID and
bounded to the newest pending entries within a total limit of 512 valid captures across session
logs and the inbox, discarding the oldest pending entry first when its available share is full. This
rotation lets a listener-only inbox retain the newest snapshots without exceeding the total. Once
those entries drain into an OPEN session, reaching 512 stored captures makes new unique posts
receive an at-capacity result; they are not written or broadcast for a live-log rebuild. Clear
captured notifications, or closed-history deletion that removes captures, frees slots. The next
`MainActivity` starts or reloads the process session and drains each valid pending capture exactly
once. A package-scoped
broadcast refreshes a visible Current Session only after a new direct session write; it does not
expose captured text in the Intent. That private action uses its own
`RECEIVER_NOT_EXPORTED` runtime receiver. Android's shutdown/date/clock/timezone broadcasts use a
separate system-only `RECEIVER_EXPORTED` receiver, so exporting system delivery never exposes the
private capture-refresh route.

The live log decodes a capture to one bounded summary row of at most 240 code points and three visual
lines. Long press shows the complete saved application, package, posted time, captured timezone,
title, body and subtext. The capture identifier remains internal for deduplication. Malformed NC1
details remain isolated and do not prevent later rows from rendering.

An ordinary tap performs a live-only replay. The listener must still be connected; it enumerates
Android's active notifications, rebuilds each eligible bounded capture and requires equality of
capture ID, post time, source package, title, body and subtext. Only then does it send the active
notification object's current `contentIntent` without a fill-in Intent. No PendingIntent or raw key
is persisted or reconstructed from journal text. A successful send can add
`notification_action_opened` at Actions or Full level and best-effort cancels an active
`FLAG_AUTO_CANCEL` notification. Listener unavailable, no longer active, absent contentIntent,
canceled capability and dispatch failure are distinct results shown with saved details. This path
does not replay action buttons or remote input, and closed-session transcript text is inert.

## Storage durability

Each admitted event is an escaped UTF-8 row appended to `<id>.events`; the file descriptor is
flushed and synchronized before a successful Send, included launch audit or notification capture
returns. An automatic row excluded by the selected log level performs no write and does not delay
the underlying operation. Session metadata and status writes remain durable regardless of whether
their optional lifecycle event row is admitted. Exact newlines and surrounding spaces survive line
encoding. Captured rows are append-only except for the explicit privacy-redaction operation
described below. Files are:

```
<id>.meta    immutable session/start identity
<id>.events  append-only machine and user rows
<id>.notes   read-only-at-UI legacy daily-note partitions
<id>.state   OPEN, CLOSED or migration-only UNCLOSED
calendar-comments  atomically replaced independent date Comments rows
notification-inbox durable captures awaiting a writable process session
.notification-clear-pending  resumable capture-redaction marker
```

Age-based and clear-history deletion select archived status only and preserve the current OPEN
identifier. Each deletion uses a synchronized tombstone completed on the next store opening if
interrupted. Those session operations also preserve `calendar-comments`. Per-date Save/Delete and
the confirmed Clear all calendar Comments operation rewrite only that independent file through a
temporary file and atomic move when supported. Editing preserves a page's original target timezone
and creation metadata. One malformed Comments row is skipped rather than hiding the other dates;
a malformed session is likewise skipped rather than hiding all other calendar history.

Clear captured notifications is intentionally stronger than normal append-only history. After
confirmation it writes the privacy marker, rewrites every current and archived `.events` file
without `notification_captured` rows, clears the pending inbox, scrubs crash-orphaned inbox/event
replacement files, and then removes the marker. Opening the store completes an interrupted clear
and also removes stale inbox, calendar-Comments and event replacement files before normal use.
Every attempted Clear completion advances the clear generation, including a reported failure,
because marker recovery may already have rewritten part of an archive; the UI therefore discards
cached archive cursors/windows before showing the result.
Other event types, session identities, Comments and calendar plans remain. Pausing capture or
revoking Notification Access does not retroactively delete rows; this explicit clear is the
redaction boundary.

When a user-managed log directory is selected, every completed working-state mutation emits only a
constant-time mirror hint. One daemon `MML-filespace-mirror` worker waits at least five seconds
through a coalescing
interval, enumerates the selected SAF tree and reconciles generated UTF-8 files. A notification
burst therefore cannot place provider I/O on Android's listener callback or create an unbounded
mirror queue. If mutations continue during a pass, one later pass is scheduled after another
coalescing interval; repeated provider failures increase the interval up to five minutes. One
bounded directory snapshot includes each file's size, avoiding an N+1 query pass. Unchanged files
are skipped by length/last-modified fingerprints, and a normally growing event file appends
only its new range when the provider exposes a verifiable size and append behavior. Unknown-size,
replacement, redaction and rejected-append cases fall back to a bounded streaming full copy.

Full-copy replacements first write and validate a target-specific `.MML-pending.txt` recovery document.
MML deliberately avoids provider rename: the pending document stays intact until a separately
created exact-name/type final validates, and only then is the recovery copy removed. Startup
reconciliation therefore heals a crash
that left a missing or truncated final, including the README ownership marker and mirror index. If
an older pending README is the only valid ownership proof, MML writes and byte-validates an
independent final while leaving that pending proof untouched, then removes the obsolete pending copy.
Ownership of the base path or mirror root never proves ownership of that separately named sidecar.
Generic sidecars must exactly match the current private source before promotion or cleanup; a
reserved pending README/index can instead prove itself with its own complete marker. A mismatched,
stale or otherwise unproven pending document is left untouched and synchronization fails visibly.
Both the pending full copy and its replacement final are streamed back byte-for-byte regardless of
known `COLUMN_SIZE`; equal length alone never authorizes promotion or removal of the recovery copy.

The private journal remains the restart source, so a process death before a scheduled mirror is
healed by the next store opening. Initial directory selection and switching between user trees are
serialized on the same worker and do not commit the new preference until a complete pass succeeds.
MML persists a pending-acquire URI before taking Android's grant, and commits pending release of an
obsolete grant atomically with the new or with-app mode. Recovery treats persisted-grant
enumeration as FULL, PARTIAL, NONE or UNKNOWN. UNKNOWN retains the acquire marker, blocks later mode
changes and schedules visible retry. PARTIAL first commits an exact-bit release record while the
acquire marker remains, then clears that marker in a second commit before releasing only held bits.
NONE clears only after a successful durable commit; FULL alone may activate user filespace. The
next store opening reconciles either journal after process death without deleting user files.
Process-global busy/result state prevents rotation from enqueuing an overlapping transition. Every
user/reconciliation entrypoint has a top-level unchecked-failure fence. Workers capture only the
application Context and never an Activity callback or view tree; every provider resource is scoped
to try-with-resources.

The generated mirror root is accepted only when the provider reports zero physical child rows or
when a complete README/index marker proves identity; malformed, oversized, duplicate or otherwise
unmapped cursor rows cannot make a nonempty folder appear empty. Duplicate display names and one
document ID exposed under multiple names abort the pass rather than selecting a provider-order-
dependent URI. Directory metadata is also capped at 1,048,576 aggregate characters and each MIME
value at 256 characters. Every newly created document must report the exact requested display name
and MIME/directory kind before MML adds it to the snapshot; a mismatched new creation is deleted when
possible. Provider rename is not used. The framed version-4 index has an
entry-count trailer, an exact 512 KiB byte cap, and complete pending state is
preferred over a torn/older final. Stale deletion and normal replacement require exact prior-index
proof. Only byte-for-byte equality with the current private source can recover an unindexed
interrupted-first-sync file; mismatches and unrelated generated-looking names are preserved and
reported. Prior-index proof for a base never authorizes rewriting or deleting its pending sidecar.
A verified complete pending document is promoted before any rewrite, and unknown provider
sizes/append results are accepted only after full streaming readback.

Each generated SAF directory reserves one of the 8,192 physical rows for a transient recovery
document, so live finals stop at 8,191. A new path requires two confirmed free rows before pending
creation; replacing an existing final requires one. Cached counts are never decremented on trust:
only a bounded re-enumeration with the identical name/URI/MIME set can reclaim successful-deletion
capacity. Any added, missing or replaced identity aborts visibly and is reconsidered from a fresh
pass, preventing a raced user file from becoming managed. Prior-index-proven stale sessions are
removed before live creation so retention can reclaim a nearly full directory. Before any such
deletion, every live pending sidecar must byte-match its exact private source and every pending
sidecar without a live source fails as a preserved conflict; live verified recovery then proceeds
through normal promotion. Before traversing
SAF, MML enumerates the bounded private manifest and validates README/optional/session entry count
plus the exact encoded 512-KiB index budget.

Current Session loads a backward-scanned tail of at most 160 events/512 KiB. Each new durable row
updates that bounded model and visible view incrementally; byte/count eviction is constant-bounded,
and individually oversized rows are left for the closed-session archive. Notification callbacks
likewise convert `StatusBarNotification`/RemoteViews graphs to bounded immutable values before the
static queue, cap active/replay scans at 512, and clear the raw arrays promptly.
