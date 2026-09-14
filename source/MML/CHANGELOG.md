# MML change log

## 1.6.6

- Replaced the former unset/disabled external-log state with app-managed logging as the default.
  A blank directory field now means that MML manages the logs in application storage.
- Added the exact Settings controls `Specify user-managed log directory` and
  `Clear app-managed logs`. Erasing the selected-directory field returns to app-managed storage.
- Added crash-recoverable clearing of app-managed current/closed session logs and captured
  notification rows while preserving the open Current Session identity and preserving Calendar
  Comments/Future events.
- Made log deletion responsibility mutually exclusive. While a user-managed directory is selected,
  MML disables its log-clearing and age-deletion controls, leaves existing user-owned files alone,
  and directs the user to clear them with a file manager after unsetting the directory.
- Retained compatibility with both the current `MML USER-MANAGED LOG DIRECTORY` README ownership
  marker and the 1.6.2-1.6.5 legacy marker.
- Removed Calendar's graphical brightness slider and removed horizontal month swiping. A horizontal
  drag anywhere on Calendar now changes Android system brightness; right increases, left decreases,
  and 75 percent of screen width represents the complete 100-percentage-point range. Previous and
  Next remain the sole month-navigation controls, while vertical scrolling and taps remain intact.
- Advanced the application identity to version 1.6.6, version code 15.

## 1.6.5

- Added a fixed Calendar footer with `Future events` at width weight 1 and `Current Session` at
  width weight 2, making Current Session exactly twice as wide in loading, failure and completed
  Calendar states.
- Added a dedicated Future events screen. It loads all valid future calendar Comments off the UI
  thread, orders them by intended civil date with the soonest first, preserves full multiline text,
  uses a virtualized large-text list, and opens the selected event's calendar date.
- Replaced the count-only future notification with an expandable `Future events...` BigText
  notification. Expanded content contains one chronological date-prefixed event per line, retains
  at most 20 events, bounds each compact summary to 120 code points and the complete body to 3,200
  code points, and reports omitted events when space permits.
- Changed the authenticated notification action to open Future events rather than Calendar while
  retaining auto-cancel, one-resume suppression, dismissal and count-change repost boundaries.
- Extended the latest-only future-event worker to stream both the total count and the bounded
  notification summaries without retaining the full Comments aggregate on the UI thread.
- Advanced the application identity to version 1.6.5, version code 14.

## 1.6.4

- Removed the App-list row long-press action. App details now opens only from the fixed
  `App settings` button for the selected row. The list is explicitly non-long-clickable while
  single-tap selection, double-tap launch, the Launch button and keyboard Enter remain available.
- Corrected user-filespace initialization for Android filesystem providers. Every generated text
  filename now ends in `.txt`: session mirrors use names such as `<id>.events.txt`, and recovery
  copies use `.MML-pending.txt`. This prevents `text/plain` providers from silently appending an
  extra `.txt` and causing MML to reject its own newly created recovery document.
- Stopped requiring an exact provider-reported MIME string after creation. MML still requires the
  exact requested display name and the correct file-versus-directory kind, then verifies every
  stored byte before adoption.
- Advanced the application identity to version 1.6.4, version code 13.

## 1.6.3

- Changed the selected-app footer from `Launch / Preferences` to `Launch / App settings`.
  `App settings` opens Android's application-details screen for the selected package, using the
  selected launcher profile when available and the package-details intent as a fallback.
- Kept MML's own Settings reachable through a fixed `MML preferences` button above the app list.
- Replaced the two storage-mode radio choices and manual synchronization action with one BMB-style
  `Set log directory` control. The dialog can choose, change or disable the user-accessible log
  directory through Android's system folder picker.
- Made the unset state explicit as `not set - logging disabled`. With no selected directory MML
  writes no user-accessible log files and holds no active tree grant. The private crash-safe journal
  remains internal working state required by Calendar, Current Session, notification capture and
  process recovery.
- A selected tree receives an `MML Logs` subdirectory. Disabling user-accessible logging stops new
  mirror writes, clears the saved tree selection, releases the persisted grant and preserves files
  already owned by the user.
- Advanced the application identity to version 1.6.3, version code 12.

## 1.6.2

- Made maintenance completion handoff overwrite-safe: a retained result blocks new work until an
  exact Activity acknowledgment, confirmed count follow-ons begin only after consumption, and the
  day composer is disabled while Save/Delete is pending. Capture Clear generations are likewise
  observed only after window refresh and archive invalidation, preserving rotation handoff.
- Added mutually exclusive `STORE LOGS WITH THE APP` and `STORE LOGS IN USER'S FILESPACE` placement
  choices. The default app mode uses only MML's private crash-safe journal and holds no SAF grant.
- Added an Android system-directory picker and a persisted, directory-scoped SAF mirror containing
  portable UTF-8 journal files. A complete background mirror must succeed before MML commits a new
  user-filespace selection; failures leave the prior mode and authoritative private journal intact.
- Routed Activity writes, calendar Comments, lifecycle/session state and background notification
  captures through one coalesced process-global mirror observer. Unchanged files are skipped and
  growing event logs attempt an incremental append, avoiding UI-thread bulk I/O and one full tree
  traversal per row during heavy notification traffic. Provider failures use bounded exponential
  backoff; directory, file-index and index-byte limits fail visibly while the private log remains
  authoritative.
- Added visible last-success/failure status, a manual synchronization action and safe directory
  replacement. Switching back to app storage releases the persisted directory grant without
  deleting files the user already owns.
- Made log-storage operations process-global and lifecycle-safe: no static worker callback retains
  an Activity, rotation cannot enqueue overlapping transitions, and a recreated Settings screen
  discovers in-progress/completed state. Pending SAF acquisition and obsolete-grant release are
  durably journaled and reconciled after process death.
- Made user-visible mirror replacement recoverable with validated `.MML-pending` documents,
  validated copy promotion while the pending recovery document remains intact, and a framed
  version-4 index with a complete
  pending-first recovery rule and entry-count trailer. Unindexed same-name files are preserved
  unless streaming equality proves interrupted MML output; stale deletion requires prior-index
  proof. Every full-copy pending and final receives byte-for-byte readback even when reported size
  matches, so same-length provider corruption cannot replace the recovery copy. Unknown
  sizes/append results likewise require byte readback; size validation is kept separate so an
  unknown size does not trigger a redundant second full read. Directory enumeration captures sizes
  once, uses one bounded entry map, and counts every physical provider row before it can declare a
  mirror root empty. Creation capacity likewise uses that physical-row count rather than the smaller
  accepted-name map. Directory/index entries are capped at 8,192 and index input at exactly 512 KiB.
  Generated directories reserve one transient recovery slot (8,191 live-document maximum); new
  paths preflight pending+final capacity, replacements preflight their pending slot, and pressure
  triggers a bounded identity-stable re-enumeration that confirms deletions without adopting raced
  names. Indexed stale sessions are removed before live creation. The complete local manifest is
  preflighted against index entry and encoded-byte budgets before any SAF mutation.
  Duplicate provider display names or document IDs now fail the pass without selecting either
  ambiguous path. Listing metadata has a one-million-character aggregate budget and 256-character
  MIME bound. Created documents are adopted only after exact name/type readback, with mismatched new
  creations removed when the provider permits it; provider rename is deliberately avoided so a
  numbered-name response cannot strand repeated recovery orphans.
  An indexed base path or owned root never authorizes a separately named pending sidecar. A pending
  session/optional file is promoted only when its bytes exactly match the current private source;
  reserved pending README/index files may additionally prove themselves with their own complete
  marker. Differing or stale unproven sidecars are preserved as visible conflicts. A sole valid
  pending control marker stays untouched until an independent final has been byte-validated.
  Sustained mirror passes remain spaced by at least five seconds.
- Bounded Current Session to 160 rows/512 KiB and changed live append to incremental view updates,
  avoiding lifetime transcript materialization. Notification callbacks now enqueue only bounded
  immutable snapshots and promptly clear raw active-notification arrays/RemoteViews graphs.
- Made persisted SAF recovery four-state and commit-ordered: unknown enumeration retains/blocks on
  the acquire marker, partial grants journal exact held-bit cleanup before retiring it, and every
  worker has an unchecked-failure fence. Capture/active-snapshot queue records no longer retain a
  listener service or Activity; inbox drain and Clear are background, generation-signaled typed
  operations, batched drain uses one durable flush/fsync, and legacy/tampered capture-ID indexing
  stops at the same 512-record durable capacity.
- Replaced whole closed-session transcript rendering with durable Previous/Next log pages bounded
  to 160 physical rows and 512 KiB. Oversized or malformed rows are reported and skipped without
  altering the source journal; legacy whole-session compatibility reads now have hard byte caps.
- Made Calendar/history work truly latest-only with a one-slot, purged worker queue. Retention
  counts/deletes/clear and calendar-Comments save/delete/clear now run through a process-global
  single-flight coordinator that survives Activity recreation and preserves count-before-confirm.
- Bounded user-authored text to 32,768 Unicode characters/128 KiB UTF-8, retained at most 16
  nonempty day drafts, and capped the calendar-Comments aggregate at 4,096 dates/16 MiB. Future-date
  counting is streaming and coalesced off the UI thread while preserving dismissal semantics;
  completed posting intent remains mergeable until the process-global Activity observer consumes it.
- Made retention and capture-ID archive scans run outside foreground journal locks. Capture-ID
  cache epochs reject stale scans after clear/delete, capture batches use one durable write, and
  regressions prove current-session appends proceed during deliberately paused scans.

## 1.6.1

- Added double-tap launch to App list while preserving immediate single-tap radio selection, the
  fixed Launch button, keyboard activation, and long-press Android application details.
- Reverified that the entire current-day Calendar button uses the paired reverse-video palette in
  the grid and large-font list, with date and entry-total spans remaining legible in system light,
  system dark and Android color inversion.
- Released detached editor, transcript, Settings-status, day-session and App-list references on
  every screen replacement, preventing a large old screen tree from remaining live behind the
  current screen during long launcher processes. A posted transcript-scroll callback now ignores a
  replaced screen. Empty date editors also no longer accumulate empty process-draft map entries.
- Changed stale OPEN-session startup discovery to inspect only metadata and state instead of
  materializing every archived transcript. Captured-notification ID discovery now streams event
  rows instead of constructing all sessions and events.
- Changed captured-notification privacy redaction to stream retained rows through a synchronized
  temporary file and atomic replacement instead of building a complete event file in memory.
- Removed avoidable regular-expression and temporary-string allocations from repeated compact-log
  normalization and notification-summary construction.
- Moved closed-history Calendar loading and selected-session transcript materialization to one
  lifecycle-cancelled worker. Calendar now caches a compact session/day index that stores no event
  or legacy-Comment text, reuses one projected index across month changes, excludes the OPEN
  transcript before scanning it, and retains only the selected closed transcript on a date page.
  Index scans decode only the row prefix needed for date membership, so large detail fields are not
  decoded into a second string/list solely to calculate Calendar counts.

## 1.6.0

- Enlarged seven-column Calendar day boxes with large-font-safe minimum heights and made the date
  label 1.30 times the entry-total size while retaining the established darker-date grayscale
  contrast and reverse-video treatment for today.
- Added calendar-only horizontal month swipes: left advances and right returns. Recognition requires
  a 48dp-or-greater, strongly horizontal single-pointer gesture, leaves brightness dragging and
  vertical scrolling untouched, cancels an underlying day tap, and retains Previous/Next controls.
- Replaced Current Session `+` with `Launcher` and rebuilt its composer as a full-width expanding
  text field above a fixed equal-width `Launcher / Send` row. The transcript remains the flexible
  region, a measured-height cap reduces visible editor lines when the IME leaves little landscape
  height, and excess input scrolls internally so actions remain reachable.
- Relabeled the App-list footer action from `Settings` to `Preferences`; it still opens MML's clean
  `Settings` screen, distinct from its first `Open Android settings` action.
- Made notification capture recover from listener disconnection by requesting Android rebind,
  reconciling still-active notifications after connection, and reporting live listener connection
  separately from the Android access grant. Capture and reconciliation storage run on one bounded,
  serialized worker instead of Android's listener callback thread; repeated queued updates for the
  same notification are coalesced, Clear is an ordering fence, and rebind requests use a five-second
  process-local debounce.

## 1.5.1

- Replaced the non-public `UserHandle.getIdentifier()` calls with a collision-safe application
  target key built from the public `UserHandle` and `ComponentName` equality contracts. This fixes
  the 1.5.0 `MainActivity.java` compilation failure while preserving multi-profile app selection.
- Migrated custom Back handling to Android 13's `OnBackInvokedDispatcher`, covering gesture and
  three-button navigation without overriding the deprecated `Activity.onBackPressed()` method.
- Removed the deprecated Java `SOFT_INPUT_ADJUST_RESIZE` flag. MML already performs the supported
  edge-to-edge IME inset handling and retains the initially hidden keyboard state.

## 1.5.0

- Replaced MML's window-only brightness override with one Calendar-top Android system-brightness
  slider. MML now always follows system brightness, writes the system setting only after Android
  grants Modify system settings access, and keeps the access and Android Display controls in
  Settings rather than the Current Session `+` path.
- Added nested `Notifications only`, `Important`, `Actions` and `Full` automatic-log levels, with
  `Important` as the safe default. Comments and captured notifications remain at every level;
  excluded automatic rows, including optional lifecycle evidence, are neither written nor shown
  in Current Session, without blocking the operation that would have produced them or suppressing
  structural session identity and status.
- Made compact Current Session machine rows tappable for full timestamp, type and stored detail.
  Machine and notification summaries now use bounded one-line content in at most three visual
  lines, while Comments retain exact multiline, unlimited-height presentation.
- Made a Current Session notification-row tap perform the matching still-active notification's
  current `contentIntent` through the connected listener. MML persists no PendingIntent, requires
  an exact active-snapshot match, provides explicit unavailable/expired/no-action failure details,
  and keeps long press for the complete saved notification fields.
- Removed the Current Session `+` popup and made `+` open App list directly. App list now scrolls
  behind a fixed `Launch / Settings` row, contains no Current Session duplicate, and opens Android
  application details on long press, with package-settings fallback and App-list restoration.
- Renamed the internal settings surface to `Settings`, put `Open Android settings` at its top, and
  removed redundant instructional paragraphs from Current Session, date pages, App list and
  Settings while preserving operational status and confirmation text.
- Added Android ICU's Hebrew civil-midnight calendar projection with readable month names and
  correct Adar I/Adar II month arithmetic. Gregorian date identity, target timezone and local-noon
  projection remain canonical for saved date Comments.
- Increased the grayscale distance between calendar dates and entry totals to at least 50 channel
  levels in normal and reverse-video cells while retaining 3:1-or-better large-text contrast and
  exact paired inversion values for light/night and Android color-inversion use.

## 1.4.0

- Replaced single-line, ellipsized Current Session rows with unlimited-height wrapping rows using
  `HH:mm` timestamps. Live Comments preserve explicit logical newlines and their per-line `#`
  presentation; captured notifications retain their bounded summary.
- Put the window-local brightness slider at the top of Calendar, initialized it from Android's
  system brightness when no MML override is enabled, removed the explanatory paragraph, combined
  Previous, month title and Next into one compact control, and reduced Calendar side padding to
  3dp.
- Added separate grayscale resources for dates and entry totals in normal and reverse-video cells,
  keeping each date perceptually darker while preserving large-text contrast.
- Hid only Android's status bar, allowed it to appear transiently by swipe, and retained
  navigation-bar, display-cutout and IME insets. Natural portrait/landscape recreation remains
  enabled, with reduced landscape padding and a horizontal `+ / input / action` composer whose
  field scrolls after two visible lines.
- Removed redundant Calendar and Current Session buttons from date pages. The date-editor `+` now
  opens only the application-shortcut picker directly.
- Made every shared application-list row wrap to content height with multiline text and no
  ellipsis, covering the App list, shortcut insertion and multi-launcher target selection.

## 1.3.0

- Renamed user-facing notes to Comments and gave every displayed Comment line a code-style `#`
  prefix without changing exact stored text. New sends use `comment`; older `user_note` rows remain
  readable for storage compatibility.
- Made every calendar day openable, including an empty or future date, with one editable Comments
  document and a separate closed-session pager.
- Stored calendar Comments independently of launcher sessions, with an intended Gregorian civil
  date and timezone distinct from the creation instant, exact text persistence, atomic replacement,
  per-date deletion and a separate confirmed clear-all operation.
- Changed each day count to one saved Comments page plus each matching closed session, while
  preserving complete closed-session transcripts and the visual `99+` grid cap.
- Added reverse-video treatment for today in both grayscale themes and every calendar projection,
  with refresh on resume and while Calendar remains visible across Android date, clock or timezone
  changes.
- Made displayed web URLs, phone numbers and strict `app:package.name` shortcuts explicit tappable
  actions. URLs use Android `ACTION_VIEW`, phones use `ACTION_DIAL`, and app tokens are inserted from
  the launchable-app picker with confirmation before launch; nothing in a Comment launches
  automatically.
- Added one optional low-importance `Future entries` notification summarizing the number of
  nonempty Comments documents dated strictly after today in their recorded timezones. Tapping it
  opens Calendar through an authenticated immutable PendingIntent without exposing Comment content.
  Ordinary resume refreshes only an already-active summary, so tap/dismiss auto-cancellation is not
  immediately undone.
- Added MML controls to enable or disable the future-entry summary, request Android 13's
  `POST_NOTIFICATIONS` permission, and open the Android channel settings.
- Added an opt-in `NotificationListenerService` behind Android's separate Notification Access. It
  captures new notifications into the current-session log with app label, source package, title,
  body, subtext, posted time and captured timezone, while excluding MML itself and group summaries.
- Added a durable, atomically replaced and de-duplicated pre-Activity notification inbox, a total
  512-capture storage/backpressure limit across logs and inbox, SHA-256 capture IDs, bounded
  control-safe field sanitation, compact live rows and full archived-session capture details.
- Added Settings status and controls for Enable capture, Pause capture, Android Notification Access,
  and confirmed crash-resumable clearing of captured rows from current and archived sessions plus
  the pending inbox and crash-orphaned replacement files without deleting other events, Comments or
  plans.
- Preserved calendar Comments across closed-session age deletion, journal-history clearing and
  internal session clearing; those Comments are removed only by their date control or the separate
  confirmed calendar-Comments clear.
- Added pure `FutureEntryPolicy` and `CapturedNotification` rules plus host tests for Comment
  presentation, app-token validation, future-date/timezone counting, singular/plural notification
  titles, exact capture-field coding, bounds, sanitation, inbox draining, deduplication, privacy
  clearing, malformed-row isolation and the separation between session retention and calendar
  Comments.

## 1.2.0

- Made Calendar the startup and HOME-request surface, with a bottom Current Session control.
- Replaced the root editor with a current-session chat log and `+ / input / Send` composer.
- Stored every sent note as an exact, timestamped, append-only event interleaved with machine
  records; live rows are compact while closed-session pages show the complete session.
- Defined one session per MML Android process startup. Same-process Activity recreation reloads
  the existing session and preserves screen, month and unsent draft.
- Limited calendar counts and paging to closed sessions, excluded next-start administrative
  boundaries from date/age calculations, and preserved legacy editable notes as read-only history.
- Added a native `+` popup for apps, calendar, brightness, MML settings and Android settings.
- Removed automatic editor focus and keyboard requests.
- Added paired light/night grayscale palettes for app, popup/dialog, system-bar, and launcher-icon
  surfaces, and disabled forced dark conversion.
- Added a large-font full-width calendar mode at 150% globally and 130% on phone widths; headers
  and navigation scroll so essential footer/composer controls remain reachable in landscape.
- Kept focus in the composer after Send by using pixel scrolling rather than focus-moving scroll.
- Preserved external-return routing across Activity recreation and excluded legacy 1.0/1.1
  administrative closure markers from calendar dates and retention age.
- Made fresh HOME/launcher requests select Calendar even when Android had destroyed only the
  Activity while retaining the MML process.
- Changed age deletion and confirmed clear-history behavior to preserve the current open session.

## 1.1.0

- Made the launcher journal continuously writable with automatic editor focus and IME request.
- Removed user-facing Start, End, OPEN, CLOSED, and UNCLOSED session controls and terminology.
- Added a direct Brightness home control, MML-window brightness, permission-gated device-wide
  brightness, and Android Display settings.
- Added confirmed Clear journal history and integer-day retention deletion using each record's
  newest durable timestamp while preserving the active boot journal.
- Added recoverable deletion markers and immediate fresh-journal creation after a full clear.
- Standardized large bold text, including 20sp calendar day cells and large dialog content.
- Marked touchscreen and faketouch hardware optional for keyboard-, D-pad-, and controller-only
  launcher devices.

## 1.0.1

- Replaced the unavailable `Files.readString` call with an Android-compatible buffered UTF-8
  reader and added an API-boundary regression check.

## 1.0.0

- Created Modern Mobile Launcher as the independent `vip.thatiam.mml` HOME application.
- Added the editable boot journal, app list, Gregorian/Islamic/Chinese calendar projections,
  and private append-only launcher event storage.
