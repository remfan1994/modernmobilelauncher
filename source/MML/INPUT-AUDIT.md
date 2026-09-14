# MML Input Audit

## Startup and focus

MML starts at Calendar. The calendar root, not a text field, owns initial focus, and the window
requests `stateAlwaysHidden`. MML never calls `showSoftInput` and never automatically focuses the
Current Session or date-Comments composer. The user explicitly taps or focuses a native field
before an IME can appear. Opening an empty date starts its editor, but focus remains on `+` and the
keyboard is not summoned.

## Current Session paths

The fixed bottom composer is ordered:

```
[ native EditText, full width, minimum width 0 ]
[ Launcher, width weight 1 ] [ Send, width weight 1 ]
```

All controls use wrap-content height. Buttons allow native line wrapping, use 20sp bold text and
have a 48dp minimum touch height rather than a fixed height. The editor occupies the complete width
above the actions and grows through four visible lines in portrait or two in landscape, then scrolls
internally. A layout-time maximum-height calculation reserves the measured action row and adapts to
the post-IME visible height, reducing the editor to one visible landscape line when necessary. The
weighted transcript shrinks before any composer control, so large fonts, wrapped input and Android's
IME inset preserve both action buttons.

`Launcher` opens App list directly. App list owns a fixed `MML preferences` button above the
scrolling `ListView` and a fixed bottom `Launch / App settings` row; scrolling moves only application
rows. Current Session is not duplicated on that screen. A tap or focus movement selects one
radio-style row; a second tap on the same row inside Android's double-tap interval opens it. Launch
or Enter remains an explicit alternative. `App settings` opens Android's application-details
screen for the selected row. Long-pressing an app row performs no MML action. Android, not MML,
owns controls such as Force stop.
Returning from details restores App list. `MML preferences` opens the surface titled `Settings`,
whose first action is `Open Android settings`.

`Send` rejects whitespace-only input. A successful synchronized append clears the field; a failed
append retains it. The IME Send action invokes the same operation. A successful user entry is
stored exactly as a `comment` event and presented with `#`; older `user_note` events remain
readable. Captured notifications use distinct bounded rows and are never presented as user
Comments.

The native field and JournalStore both enforce 32,768 Unicode characters and 128 KiB UTF-8. A
limit error stays attached to the focused field and no durable mutation occurs. The process keeps
only one bounded Current Session draft.

Each live-log event begins with `HH:mm`. A Comment has wrap-content height, unlimited visual lines,
no ellipsis and no horizontal scrolling. Explicit newlines remain visible, with one idempotent `#`
marker per logical line. An automatic machine event is reduced to one logical line of at most 180
code points and a captured-notification summary to at most 240; either occupies at most three
wrapping visual lines with an end ellipsis when necessary. Tapping a machine row opens the exact
stored timestamp, type and detail. Tapping a notification row attempts its still-active primary tap
action, while long press opens all saved notification fields.

The notification tap is capability-preserving and deliberately narrow. The connected listener
re-enumerates Android's active notifications, rebuilds their bounded snapshots, and requires an
exact match before sending the active notification's current `contentIntent` with no fill-in
Intent. The operation cannot work after listener disconnection, notification removal or content
change, and it cannot invoke action buttons or remote-input replies. MML stores no PendingIntent.
An auto-cancel source notification is canceled only after a successful send and only on a
best-effort basis. Expired, missing and no-action cases open details with status instead.

## Date Comments paths

Every calendar day opens, even when it has no saved Comments and no closed sessions. A new or
explicitly edited day uses:

```
[ + ] [ native EditText, width weight 1, minimum width 0 ] [ Save ]
```

`Save` creates or replaces the one Comments document for the intended Gregorian civil date.
Prefix-only input such as `#` or `# ` has no payload: it is rejected on a new date and routes an
existing page through confirmed deletion. A saved page exposes explicit Edit Comments and Delete
Comments controls. Delete never changes the closed-session logs on the same date.

The date editor's `+` control opens the launchable-app shortcut picker directly. The stored inert
token is strictly `app:package.name`; arbitrary `intent:` text is not accepted as an app shortcut.
It does not expose App list navigation, Calendar, Current Session or settings. The day page has no
duplicate Calendar or Current Session buttons; Android Back or Escape returns to Calendar. Date
drafts and the Current Session draft occupy separate process-local state.

Only nonempty day drafts are cached, each under the same text bound, in an access-ordered map of at
most 16 dates. Save/Delete runs on the process-global journal-maintenance lane; the field/draft is
cleared only after generation-matched durable success and is preserved on scheduling/storage
failure. The calendar document itself is limited to 4,096 dates and 16 MiB total.

## Code-style presentation and explicit actions

MML adds one `#` marker to every displayed logical line of a Comment. If the user already supplied
`#`, it is not doubled. This transformation is presentation only and never rewrites exact stored
text.

Saved calendar Comments and live Comments recognize web URLs, phone numbers and strict app
tokens. Recognition does not execute anything. A tap is required for each action:

```
http or https -> Android ACTION_VIEW
phone number  -> Android ACTION_DIAL
app token     -> LauncherApps.startMainActivity after package resolution
```

Other URI schemes are rejected. MML does not use `ACTION_CALL`, does not place a call, and does not
automatically open a Comment when its day appears. If a package has several launcher activities,
the user chooses one; a single or selected target still requires Open confirmation. If no matching
app remains, the failure is visible and journaled.

Comment views remain selectable and use Android's link movement and text-selection machinery.
MML installs no global or custom long-press menu, so copying ordinary text is not reassigned to a
launcher command.

## Generic input

The composer uses Android's normal editable-text connection:

```
text input = touch IME + hardware keys + compatible assistive input
```

MML does not identify a keyboard by vendor, Bluetooth name, key count or assumed layout. Normal
Nanopad/Nanoboard, full keyboard, numberpad, switch, mouse, stylus and accessibility focus signals
remain ordinary Android input. Voice entry is available through a user-selected voice-capable IME;
MML itself requests no microphone permission.

Tab/Shift+Tab and D-pad navigation use native focus. Enter, Numpad Enter or D-pad Center opens the
selected app-list row or selected Future events row. Escape/Back follows the visible hierarchy;
Future events returns to Calendar, and at Calendar Back remains on the HOME root. Page Up/Page
Down changes month. Closed-session paging uses visible Previous Session and Next Session controls
rather than globally seizing D-pad Left/Right.

## Two notification control paths

MML's `Settings` screen keeps posting and capture visibly separate:

```
Future events notification:
  Enable notification / Disable notification / Manage notification channel

Captured notifications:
  Enable capture / Pause capture / Manage Android Notification Access
  Clear captured notifications
```

Android owns the `POST_NOTIFICATIONS` runtime dialog for MML's own summary. If special access has
not been granted, Enable capture first shows MML's explanation, then opens Android's
component-specific Notification Access screen when available, falling back to Android's general
Notification Access list if necessary. Pause changes only MML's local capture preference and does
not pretend to revoke Android access; the Manage control is the path to grant or revoke that access.
Clear reports the stored count and requires confirmation before redacting captures from current and
archived logs plus the pending inbox. Status explicitly reports when an OPEN session has reached the
512-capture total storage limit, rejects new unique capture writes, and points to Clear or
closed-history deletion to resume. Before a launcher session is writable, the bounded inbox instead
rotates out its oldest pending snapshot so it retains the newest available set.

The listener receives notification posts only while Android access is granted and its service is
connected. MML reports these as separate states, requests an Android rebind after disconnection and
on a foreground granted-but-disconnected observation, and reconciles still-active notifications
after connection. It ignores posts when capture is paused, excludes MML's own package and
group-summary duplicates, and never treats a
notification post as a tap, launcher command or Comment. Only a later explicit tap on a matching
Current Session row can request the active notification's primary `contentIntent`; archived logs
remain inert text.

## Machine-log level input

Settings exposes one radio group in increasing order:

```
Notifications only < Important < Actions < Full
```

`Important` is the default. Comments and captured notifications remain included at every level.
Selecting a level changes both subsequent automatic-event writes and the filtering of the live
Current Session view. Excluded audit rows, including optional lifecycle evidence, never prevent
their associated operation or structural session metadata/status changes. Increasing the level
cannot recreate an event that was not written. Lowering it immediately hides disallowed live rows
but does not delete rows stored at an earlier level or by a pre-1.5 release. Closed-session text
shows every event that was actually stored, independent of the later setting.

## Large-font behavior

All application-created text is at least 20sp and bold. No control has an exact pixel/dp height.
At Android font scale 150% or above, at 130% on phone widths through 360dp, or below 320dp screen
width, Calendar replaces its seven-column grid with one full-width button per day. The smaller-scale
grid contains only a day number and bare total count (`99+` maximum display), preventing old
Doallpad-style letter/label clipping. Its date label is 1.30 times the total size, and each day box
has only a minimum height (64dp portrait, 56dp landscape), never a clipping fixed height. Calendar
has no brightness widget and no horizontal month-swipe action. A clearly horizontal one-pointer
drag anywhere on Calendar adjusts Android system brightness: right increases, left decreases, and
75 percent of screen width spans the full 100-percentage-point adjustment before clamping. Vertical
scrolling, diagonal movement, day taps and footer controls remain native child interactions;
Previous and Next are the sole month controls. Native dialogs, text selection and clipboard
behavior are not globally replaced by long-press handlers. App-list rows have no long-press action;
notification long press remains scoped to a notification row. Dialog/list text uses the same 20sp
bold floor. Shared
rows in App list, shortcut insertion and multi-profile target selection use wrap-content height,
multiline text and no ellipsis so long labels remain readable in portrait and landscape. App-list
Launch and App settings remain fixed and reachable while the list scrolls.
