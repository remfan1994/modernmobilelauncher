# MML Privacy Audit

MML stores app labels/components, event timestamps, explicitly sent session Comments, editable
calendar Comments with their intended dates and timezones, and - after optional Android Notification
Access - captured notification metadata and visible text. A planned appointment, message preview,
URL, phone number or app token can reveal sensitive behavior, so version 1.6.6 applies the following
bounds:

- No `INTERNET` permission.
- No analytics or advertising library.
- No accessibility service.
- No Usage Access request.
- No contacts, location, microphone, camera or direct-call permission.
- `POST_NOTIFICATIONS` permits only MML's own optional Future events notification; it does not grant
  incoming-notification access.
- MML declares a non-exported listener service protected by
  `BIND_NOTIFICATION_LISTENER_SERVICE`. It remains unable to read incoming notifications until the
  user explicitly grants MML Android Notification Access, and that access can be revoked at any
  time in Android settings.
- `WRITE_SETTINGS` is declared only for Calendar's horizontal system-brightness gesture. MML
  applies no app-only override. Without Modify system settings access a completed horizontal drag
  opens the permission explanation and does not write; MML always follows the system setting.
- Android application backup is disabled with `android:allowBackup="false"`.
- App-managed logging is the default. Logs remain in MML-managed application storage until the
  user explicitly selects a directory, and Settings can clear those app-managed logs.
- With no selected user-managed directory, MML holds no user-directory grant.
- User-managed logging requires an explicit Android directory-picker choice and only a persisted
  read/write grant to that selected tree. MML requests no broad storage permission and performs no
  network or cloud synchronization itself.

Application launch records cover MML-originated requests only. MML neither promises nor
attempts covert collection of a complete foreground-application history.

## App-managed and user-managed logs

Settings exposes a blank-or-selected directory field with the exact controls `Specify user-managed
log directory` and `Clear app-managed logs`. Blank is the default app-managed state: MML holds no
user-directory grant, keeps the logs in MML-managed application storage and permits a confirmed
clear. The clear uses a durable recovery marker, removes current and closed session events, legacy
session notes and captured-notification rows, preserves the open Current Session identity, and
preserves Calendar Comments/Future events as separate planning data. Reopening storage completes an
interrupted clear before normal journal use.

Selecting a directory through Android's Storage Access Framework creates a generated `MML Logs`
folder below that tree. It contains ordinary line-oriented UTF-8 session, Comments and pending-
notification documents. Therefore private content becomes visible to the selected document
provider and to other software or people that can access that user-owned directory. The picker
choice and generated README make that boundary explicit.

Only one deletion responsibility is presented at a time. While a user-managed directory is active,
MML disables app-managed log clearing, age deletion and captured-log deletion. Erasing the directory
field first returns to app-managed storage, stops later writes to the selected tree and releases the
persisted grant. MML leaves every existing user-managed file untouched; the user may then copy,
edit, archive or delete those files with a file manager. Selecting another tree likewise never
silently deletes the old tree.

MML retains bounded private crash-recovery working state while user-managed logs are active because
a revoked, unavailable, removable or cloud-backed provider cannot safely be the only transactional
store for Calendar, Current Session and notification capture. This working state is not presented as
a second user-selected destination. A complete initial synchronization must succeed before MML
commits a new tree. Later failures remain visible in Settings and are retried after later mutations
or reselection. Activity and listener mutations feed one coalesced background worker; sustained
failures use bounded backoff, and provider enumeration or bulk copying never runs on the UI or
notification callback thread.

Directory grant transitions are crash-recoverable. MML durably records pending acquisition and
obsolete grants for release. Startup reconciliation either completes the synchronization or
releases the obsolete grant. Generated replacements use validated `.MML-pending.txt` recovery
documents and byte readback before a final becomes trusted. Directory enumeration is bounded at
8,192 physical rows, while generated directories admit at most 8,191 live documents so pending
recovery has a transient slot. The complete local manifest is checked against the 8,192-entry and
512-KiB encoded-index limits before SAF mutation. Duplicate display names/document IDs and
ambiguous new files are rejected rather than adopted.

All generated text and recovery filenames end in `.txt`, preventing common filesystem providers
from silently appending another extension. Exact requested display names and file-versus-directory
kind are required; provider-specific non-directory MIME labels are accepted only because stored
bytes are independently verified. MML accepts both the current `MML USER-MANAGED LOG DIRECTORY`
ownership marker and the legacy 1.6.2-1.6.5 marker. It refuses an unrelated nonempty `MML Logs`
directory. Existing generated paths are rewritten or removed only with prior-index ownership proof,
and an unindexed same-name document is adopted only when bounded streaming comparison proves its
bytes exactly match current generated data. Otherwise the document is preserved and the conflict is
reported.

Displayed `http` and `https` links are handed to an Android-selected external application with
`ACTION_VIEW`; MML has no `INTERNET` permission and does not fetch them. Displayed phone numbers are
handed to the dialer with `ACTION_DIAL`; MML has no `CALL_PHONE` permission and cannot place the
call directly. A stored app shortcut contains only a strict launcher package token. All three
actions require a tap, and their destination receives only the value needed for that explicit
handoff. Comment content never launches automatically.

The low-importance `Future events` notification deliberately exposes a bounded presentation of
user-authored future calendar Comments. Its collapsed title is `Future events...`; its expanded body
can reveal intended dates and compacted Comment text, including words derived from a saved phone
number, URL or app token. It retains at most the first 20 chronological events, at most 120 Unicode
code points per compact event and at most 3,200 code points in the complete body. Full multiline
Comments are not copied into the notification; they remain available in MML's Future events screen.

Android controls permission, channel importance, expansion and lock-screen presentation. MML's
`Settings` screen can enable or disable this notification and open Android's channel controls;
disabling it cancels the posted instance without deleting Comments. Its immutable one-shot Future
events PendingIntent includes an application-private random token, so another app cannot manufacture the
public custom action alone and create false `future_notification_opened` evidence.

## Notification Access and captured content

Granting Android Notification Access is sensitive: while capture is active and the listener is
connected, MML receives new notification posts from other applications and stores a bounded
snapshot in its private journal. On a new or recovered connection, it also enumerates Android's
still-active notifications and admits eligible snapshots through the same de-duplicated path. This
can include a notification posted before access was granted or while the listener was disconnected;
Android does not provide already-removed notification history.
The snapshot can contain:

```
application label and source package
title, visible body and subtext
Android posted time and MML capture timezone
```

MML constructs this bounded value synchronously inside the listener callback before queueing work;
the static queue never retains `StatusBarNotification`, `RemoteViews` or bitmap graphs. Active and
replay enumeration scan at most 512 entries and clear the raw Android arrays after snapshot/use.

These fields may contain message previews, correspondents, account information, appointments,
authentication information or other private text. MML normalizes carriage returns, removes most
control characters, replaces malformed surrogates and imposes code-point bounds before storage.
The stable capture identifier remains internal for deduplication and is not shown in the archived
human-readable display. It is a fixed SHA-256 digest over source package, Android notification
identity, post time and bounded title/body/subtext rather than a copy of an app-controlled
notification key. App label and capture zone are intentionally excluded from identity.

MML ignores its own package so its Future events notification cannot feed back into the journal. It also
ignores group-summary posts while allowing individual child notifications, and it does not capture
notification removals. An identical notification identity, post time and visible content is stored
once; changed visible content can form another capture. MML never saves an action PendingIntent or
raw Android notification key. A later explicit Current Session row tap may invoke only the matching
still-active notification's current primary `contentIntent`, as bounded below.

At tap time a connected listener enumerates Android's active notifications and rebuilds each
eligible bounded snapshot. Source package, capture ID, post time, title, body and subtext must all
match the saved row. MML then calls that active notification's `contentIntent.send()` without a
fill-in Intent. It cannot reconstruct a capability from journal text, replay action buttons or
remote-input replies, or act after listener disconnection, removal, content change or source
cancellation. It best-effort cancels the source notification only after a successful send and only
when the source set `FLAG_AUTO_CANCEL`. This approximates the notification's primary tap capability
but cannot promise every status-shade side effect. Closed-session transcript text is inert.

If a listener post arrives before the launcher Activity establishes a writable session, MML stores
the bounded snapshot in an application-private `notification-inbox` and drains it into the next current
session. This inbox is atomically replaced, de-duplicated and limited to the newest 512 pending
captures only when no session captures use those slots. MML retains at most 512 valid captures total
across current/archived logs and the inbox. A listener-only inbox at its available share discards
the oldest pending snapshot to retain the newest one. After pending captures drain into an OPEN
session, reaching the total limit rejects new unique posts, reports the limit in Settings and sends
no redundant live-log refresh; explicit capture clear or deletion of closed history containing
captures frees capacity. A compact live row exposes app/text summary; the
later closed log exposes the captured fields in full. A package-scoped refresh broadcast carries no
captured text and is received only by MML's non-exported runtime receiver.

Settings separately reports whether Android access is granted, whether the listener is connected,
whether MML capture is active or paused, the fixed retention bound and capture-maintenance status.
It does not synchronously scan every archive merely to render Settings. MML requests
Android's supported rebind path after a disconnect and reconciles still-active notifications after
connection. Pause stops future copying but deliberately does not
pretend to revoke Android's special access. The Manage control opens Android's access screen for
grant or revocation. Posting permission and the Future events channel remain independent.

Unsent Current Session and per-date drafts are held only for the current Android process. They are
not written until Send or Save succeeds and can be lost with that process. Sent and saved text is
stored exactly; code-style `#` prefixes are presentation only, and explicit Comment newlines remain
visible in the live log. Machine summaries are bounded to 180 code points and notification summaries
to 240, each with at most three visual lines and an end ellipsis; tap or long press exposes the full
stored detail. Legacy 1.1 daily notes remain locally readable but cannot be edited by the current
interface.

The machine-log setting is a data-minimization control with nested levels: Notifications only,
Important, Actions and Full. Important is the default. Comments and captured notifications remain
included at every level. An automatic type excluded at write time is not stored, including optional
lifecycle rows handled directly by JournalStore, and the requested app, settings, save or cleanup
action still proceeds. Session identity and OPEN/CLOSED status remain structural records even when
the related optional row is omitted. The same selection immediately hides disallowed stored machine
rows in Current Session. Changing it does not retroactively erase rows written under an earlier
level or by a pre-1.5 release, and raising it cannot reconstruct an omitted event. Closed-session
pages expose every event that was actually stored.

Calendar Comments use a separate application-private file and do not belong to a launcher session. Each
row records the intended Gregorian date and target timezone, the original creation instant and
timezone, and exact text. Editing preserves the target and creation metadata. One malformed row is
ignored without exposing or hiding the other valid pages.

User-authored session/calendar text is rejected above 32,768 Unicode characters or 128 KiB UTF-8.
The calendar document is rejected above 4,096 dates or 16 MiB before replacement, and the process
retains at most 16 nonempty bounded day drafts. Calendar display/count scans use bounded physical
lines; future counting and aggregate rewrites run off the UI thread. Failure preserves the prior
durable document and unsent draft.

Settings separates session-history deletion from calendar-Comments deletion. Age-based deletion
removes a closed session only when its newest real event or legacy-note timestamp is older than the
cutoff. The next-start administrative closure timestamp is ignored. Clear journal history deletes
closed sessions. Both paths preserve the current OPEN process session and every independent
calendar Comments page. Resumable session-deletion markers ensure associated metadata, event, note
and state files are not left as a partially visible record; confirmed internal session clearing
also enumerates unreadable/orphan record families.

Counting, confirmation and mutation are a process-global single-flight sequence: rotation cannot
start a second deletion or silently turn a count into deletion. A completed result blocks another
operation until exact, once-only Activity acknowledgment; positive count confirmation acknowledges
before admitting its follow-on delete/clear. Archive selection scans outside the
foreground append lock, then revalidates record file state/status under a short per-record commit.

`Clear captured notifications` is a separate confirmed privacy operation. It writes a recovery
marker, rewrites current and archived session event files without `notification_captured` NC1 rows,
clears the pending inbox, scrubs any crash-orphaned inbox/event replacement files, and removes the
marker. The operation is fenced behind capture work already accepted by the bounded listener
worker, preventing an older queued callback from restoring a capture after Clear reports success.
Its package-scoped refresh and process generation also make a recreated Activity reload the cleared
state even if receiver registration changes during success or a potentially partial failure.
Observation advances only after
the bounded window and archive invalidation apply, so canceled Activity work remains transferable.
An attempted Clear that reports failure still advances the clear generation: recovery may have
partially redacted archives before the error, so no stale decoded page/cursor remains trusted.
Reopening storage completes an
interrupted clear and startup removes stale replacement
files for the inbox, calendar Comments and events before normal use. Session identities, machine
events, user Comments and calendar plans are retained. Pausing or revoking access does not erase
already captured text; Clear is the explicit redaction operation.

A date page can delete its own Comments after confirmation. `Clear all calendar Comments` is a
separate confirmed Settings operation that rewrites only the independent Comments file, leaves all
session logs unchanged, removes any crash-orphaned Comments replacement file, and updates or
cancels the Future events notification. Session age policy never silently expires a planned date.

Any future one-shot export design should remain separate from continuous user-filespace mirroring,
require a direct user action and state exactly which sessions will be copied. It must not silently
enable platform backup.
