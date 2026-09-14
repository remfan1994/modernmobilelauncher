# MML 1.6.6 Verification

## Host-side tests

From the MML project directory:

```
test_classes="$(mktemp -d)"
java com.sun.tools.javac.Main -d "$test_classes" \
  app/src/main/java/vip/thatiam/mml/BoundedInputStream.java \
  app/src/main/java/vip/thatiam/mml/LineCodec.java \
  app/src/main/java/vip/thatiam/mml/JournalEvent.java \
  app/src/main/java/vip/thatiam/mml/JournalNote.java \
  app/src/main/java/vip/thatiam/mml/CalendarComment.java \
  app/src/main/java/vip/thatiam/mml/CommentText.java \
  app/src/main/java/vip/thatiam/mml/FutureEntryPolicy.java \
  app/src/main/java/vip/thatiam/mml/FutureEntryRefreshPolicy.java \
  app/src/main/java/vip/thatiam/mml/JournalMaintenancePolicy.java \
  app/src/main/java/vip/thatiam/mml/CapturedNotification.java \
  app/src/main/java/vip/thatiam/mml/MmlProcessSession.java \
  app/src/main/java/vip/thatiam/mml/SessionStatus.java \
  app/src/main/java/vip/thatiam/mml/JournalSession.java \
  app/src/main/java/vip/thatiam/mml/JournalSessionIndex.java \
  app/src/main/java/vip/thatiam/mml/JournalSessionWindow.java \
  app/src/main/java/vip/thatiam/mml/JournalArchiveCursor.java \
  app/src/main/java/vip/thatiam/mml/JournalArchivePage.java \
  app/src/main/java/vip/thatiam/mml/JournalTextPolicy.java \
  app/src/main/java/vip/thatiam/mml/BoundedUtf8LineReader.java \
  app/src/main/java/vip/thatiam/mml/JournalStore.java \
  app/src/main/java/vip/thatiam/mml/CalendarSystem.java \
  app/src/main/java/vip/thatiam/mml/LogVerbosity.java \
  app/src/main/java/vip/thatiam/mml/LogStorageMode.java \
  app/src/main/java/vip/thatiam/mml/MirrorFilePolicy.java \
  app/src/main/java/vip/thatiam/mml/MirrorIndexPolicy.java \
  app/src/main/java/vip/thatiam/mml/MirrorOwnershipPolicy.java \
  app/src/main/java/vip/thatiam/mml/PersistedGrantState.java \
  app/src/main/java/vip/thatiam/mml/MmlPresentationPolicy.java \
  tools/BoundedInputStreamSelfTest.java \
  tools/LineCodecSelfTest.java \
  tools/CommentTextSelfTest.java \
  tools/FutureEntryPolicySelfTest.java \
  tools/FutureEntryRefreshPolicySelfTest.java \
  tools/JournalMaintenancePolicySelfTest.java \
  tools/CapturedNotificationSelfTest.java \
  tools/JournalStoreSelfTest.java \
  tools/CalendarSystemSelfTest.java \
  tools/LogVerbositySelfTest.java \
  tools/LogStorageModeSelfTest.java \
  tools/MirrorFilePolicySelfTest.java \
  tools/MirrorIndexPolicySelfTest.java \
  tools/MirrorOwnershipPolicySelfTest.java \
  tools/PersistedGrantStateSelfTest.java \
  tools/MmlPresentationPolicySelfTest.java
java -cp "$test_classes" vip.thatiam.mml.BoundedInputStreamSelfTest
java -cp "$test_classes" vip.thatiam.mml.LineCodecSelfTest
java -cp "$test_classes" vip.thatiam.mml.CommentTextSelfTest
java -cp "$test_classes" vip.thatiam.mml.FutureEntryPolicySelfTest
java -cp "$test_classes" vip.thatiam.mml.FutureEntryRefreshPolicySelfTest
java -cp "$test_classes" vip.thatiam.mml.JournalMaintenancePolicySelfTest
java -cp "$test_classes" vip.thatiam.mml.CapturedNotificationSelfTest
java -cp "$test_classes" vip.thatiam.mml.JournalStoreSelfTest
java -cp "$test_classes" vip.thatiam.mml.CalendarSystemSelfTest
java -cp "$test_classes" vip.thatiam.mml.LogVerbositySelfTest
java -cp "$test_classes" LogStorageModeSelfTest
java -cp "$test_classes" MirrorFilePolicySelfTest
java -cp "$test_classes" MirrorIndexPolicySelfTest
java -cp "$test_classes" MirrorOwnershipPolicySelfTest
java -cp "$test_classes" PersistedGrantStateSelfTest
java -cp "$test_classes" vip.thatiam.mml.MmlPresentationPolicySelfTest

java tools/JavaSyntaxSelfTest.java \
  $(find app/src/main/java tools -name '*.java' -type f | sort)
java tools/XmlSyntaxSelfTest.java \
  $(find app/src/main/res -name '*.xml' -type f | sort) \
  app/src/main/AndroidManifest.xml
java tools/MmlStructureSelfTest.java \
  app/build.gradle \
  app/src/main/AndroidManifest.xml \
  app/src/main/java/vip/thatiam/mml/MainActivity.java \
  app/src/main/java/vip/thatiam/mml/JournalStore.java \
  app/src/main/java/vip/thatiam/mml/CalendarProjection.java \
  app/src/main/java/vip/thatiam/mml/MmlPresentationPolicy.java
java tools/MmlThemeSelfTest.java \
  app/src/main/res/values/styles.xml \
  app/src/main/res/values/colors.xml \
  app/src/main/res/values-night/styles.xml \
  app/src/main/res/values-night/colors.xml \
  app/src/main/res/drawable/ic_launcher_background.xml \
  app/src/main/res/drawable/ic_launcher_foreground.xml \
  app/src/main/res/mipmap/ic_launcher.xml \
  app/src/main/res/mipmap/ic_launcher_round.xml
```

## Device matrix

1. Select MML as Android Home and start a fresh process. Confirm Calendar owns focus, the IME stays
   hidden, the status bar is hidden without a startup flash, and Android's bottom navigation remains
   visible. Swipe the status area transiently, return focus, and confirm only the status bar hides.
2. Test Gregorian, Hebrew, Islamic civil, Islamic Umm al-Qura and Chinese in the seven-column grid
   and large-font list. Confirm today and selected dates use the complete system/year/month/day key,
   including Chinese leap-month identity.
3. In Hebrew, verify the non-leap sequence Shevat -> Adar -> Nisan and a leap sequence Shevat ->
   Adar I -> Adar II -> Nisan in both directions. Confirm readable month titles and that MML changes
   Hebrew dates at civil midnight, not sunset.
4. Save one intended date in each non-Gregorian projection, switch projections and return. Confirm
   one canonical Gregorian date and target timezone survive without drift or duplicate Comments.
5. In light, night and Android color inversion, verify date numbers are 1.30 times the total size,
   visibly darker than totals in ordinary and reverse-video cells, remain legible, and differ
   substantially rather than by a near-identical gray. Confirm date/time/timezone broadcasts and
   resume move today's reverse video.
6. Confirm Calendar has no graphical brightness slider and starts with one compact Previous /
   month / Next row, no helper paragraph and approximately 3dp side padding. Verify its footer
   contains `Future events` at width weight 1 and `Current Session` at width weight 2, so Current
   Session is exactly twice as wide, including Calendar loading and failure states. Verify 64dp
   portrait and 56dp landscape minimum grid-day heights expand instead of clipping with large text.
   Use Previous/Next for month changes and confirm horizontal drags no longer navigate months.
7. Revoke Modify system settings access. Drag horizontally anywhere on Calendar and confirm the
   access explanation opens without creating an MML-only level. Grant access; verify rightward drag
   increases and leftward drag decreases Android system brightness. A 75%-of-screen-width drag must
   span 100 percentage points before clamping to 1-100. Test the gesture over the grid, list,
   loading/failure content and footer; confirm vertical scrolling, diagonal movement, day taps and
   footer buttons remain correct. Change brightness externally and resume MML; confirm MML follows
   it. Repeat with adaptive brightness enabled.
8. Tap an empty future date. Confirm the uncluttered page has Comments, Closed Sessions and a bottom
   `+ / input / Save` row, but no tutorial paragraph, intended-date prose, Calendar button or Current
   Session button. Back or Escape returns to Calendar.
9. Save multiline `3:20 Highland Hospital`, a URL and phone number. Confirm display adds one
   idempotent `#` per logical line while Edit restores exact text. Whitespace or marker-only content
   is empty, and deleting an existing page requires confirmation.
10. Recreate during a date edit and confirm its process draft returns. Kill the process and confirm
    an unsaved draft is not durable. Edit a saved page and confirm its original target and creation
    metadata remain. Start Save/Delete, then immediately type/tap again, rotate, or navigate to
    Settings. Confirm the composer stays disabled until the retained result is consumed once, a
    failed write restores the bounded draft, and Settings controls return to idle after completion.
11. Confirm each count is closed sessions plus one saved Comments page. Verify `99+`, detailed
    accessibility/list text, the complete Previous/Next closed-session pager and exclusion of the
    OPEN session.
12. In the date editor, press `+`. Confirm only the app-shortcut picker opens. Insert a strict
    `app:package.name`, test one and several launcher targets, cancel, remove an app, and confirm
    launch always requires the explicit Open confirmation.
13. Tap saved `http`/`https` and phone links. Confirm `ACTION_VIEW` and `ACTION_DIAL`, never a direct
    call. Long-press/select ordinary Comment text and confirm Android's native copy controls remain.
14. Open Current Session. Confirm no redundant explanatory heading, `HH:mm` on each row, and a
    full-width editor above an equal-width `Launcher / Send` row. Confirm Launcher initially owns
    focus, so the IME remains hidden until the editor is explicitly selected.
15. Send exact spaces and explicit newlines. Confirm Comments remain unlimited-height, unellipsized,
    selectable and `#`-presented. Confirm whitespace-only and failed writes retain the draft.
16. Generate a long automatic event. Confirm its live summary is one logical line bounded into at
    most three visual lines with an ellipsis when necessary. Tap it and verify the full timestamp,
    stored type and complete detail dialog. Confirm subsequent log rows never overlap it.
17. On a fresh preference state, confirm machine log detail defaults to Important. Exercise all four
    nested choices: Notifications only, Important, Actions and Full. Confirm Comments and captures
    always remain; Important adds failures/destructive results/brightness/boundaries, Actions adds
    launches/app details/notification actions/calendar saves/future opens, and Full adds routine
    panel/HOME/resume rows.
18. For each level inspect storage and Current Session. Confirm an excluded automatic event is not
    written or shown, including optional start/next-start/shutdown evidence. Confirm session
    identity and OPEN/CLOSED status still persist when those rows are omitted. Raising the level
    does not reconstruct a skipped event; lowering it immediately hides disallowed stored rows live
    without deleting rows from an earlier level or a pre-1.5 install. Close the session and confirm
    the archive shows every row that was actually stored. Confirm unknown machine types are
    Full-only.
19. Simulate an unavailable/full journal while requesting an app, Comment app shortcut, web link or
    Android settings panel. Confirm best-effort audit failure never blocks the explicit action.
20. Press Current Session Launcher. Confirm it opens App list directly with no popup. Scroll a long
    list and verify only app rows move; `MML preferences` remains fixed above the list and the bottom
    `Launch / App settings` row remains fixed below it. Confirm there is no Current Session duplicate.
    Single tap selects. Double tap the same row and confirm it opens; tap two different rows or wait
    beyond Android's interval and confirm selection changes without launch. Confirm the fixed Launch
    button and Enter still open the selected app.
21. Select an app and press `App settings`. Confirm Android application details opens for that exact
    selected package/profile with its platform controls, including Force stop when Android provides
    it. Long-press several App-list rows and confirm MML performs no action and does not open
    settings. Return from the button-opened details screen and confirm App list is restored. Exercise
    the package-details fallback on a profile/device where LauncherApps rejects the direct request.
22. Press `MML preferences` above the app list. Confirm the title is exactly `Settings`, `Open Android
    settings` is the first action, and no brightness or Calendar navigation item exists in the
    Current Session `+` path. Confirm Settings uses compact headings/status rather than repeated
    tutorial paragraphs.
23. Launch an app and return; confirm Current Session returns. Return from application details to App
    list and from Android controls to their requesting MML screen. Press HOME from every screen and
    confirm Calendar appears without a new same-process session.
24. Recreate for theme, locale, font, orientation and window size. Confirm the same OPEN identifier,
    screen, month, date, pager, edit state, external-return route and relevant draft restore.
25. Kill the process and reopen. Confirm a new process session starts, the prior OPEN record closes
    only as later-start evidence, Calendar membership/retention ignore its later administrative
    timestamp, and independent calendar Comments survive.
26. Save several future Comments out of entry order, including multiline text and dates in another
    year. Press Calendar's `Future events` button. Confirm the screen lists every valid future page
    chronologically, soonest at the top and latest at the bottom, preserves full multiline text,
    remains responsive with a long list, and opens the selected event's calendar date. Today, past,
    empty and malformed records must not appear.
27. With posting permission absent, save a first future page. Confirm one Android 13 permission
    request and low-importance channel behavior. Confirm the collapsed title is exactly
    `Future events...`, collapsed text gives the singular/plural total, and expansion produces one
    chronological `M/d. event text` line per retained page. Verify multiline text is compacted, a
    cross-year date includes its year, no more than 20 event summaries are retained, each summary
    is at most 120 code points, the complete body is at most 3,200 code points, and omitted events
    are reported when the final line fits. Verify strict-after-today counting in each target
    timezone.
28. Tap the Future events notification. Confirm authenticated one-shot opening of the Future events
    screen and auto-cancel without immediate repost. Dismiss manually, recreate, edit text without
    changing the count while the notification is active, then change the count and verify the
    documented refresh/repost boundaries. A forged public action without the private token must
    not open Future events or create evidence.
29. Confirm `POST_NOTIFICATIONS` is independent from Notification Access. Enable/Pause capture,
    grant/revoke component-specific access, use general-list fallback, and verify Settings status.
30. Post eligible notifications with empty, multiline, control-containing and maximum-length fields.
    Confirm app/package fallback, title, body, subtext, post time and capture timezone; exclude MML,
    group summaries and removals; deduplicate an identical snapshot and retain changed content.
31. In Current Session, verify a capture summary is at most 240 code points and three visual lines.
    Long press for Application, Package, Posted, Captured zone, Title, Body and Subtext. Confirm the
    stable capture ID remains internal and malformed rows do not break later rows.
32. Tap a capture whose original notification is active and has a primary `contentIntent`. Confirm
    the exact source action opens. For a USB notification, confirm File transfer opens only if that
    device's active notification actually supplies that contentIntent. Verify Actions/Full can store
    `notification_action_opened`; lower levels still perform the action without that row.
33. Test listener disconnected with Android access still granted. Confirm Settings distinguishes the
    disconnected state, MML requests rebind, reconnection reconciles still-active notifications
    without duplicates, and removed notifications are not manufactured. Then test notification text
    changed, no contentIntent, source-canceled PendingIntent and dispatch failure. Confirm no
    reconstructed action runs and the saved details dialog identifies the status. Verify action
    buttons and replies are not replayed.
34. On a successful auto-cancel notification action, confirm cancellation is best effort after send.
    For a non-auto-cancel notification, confirm MML does not cancel it. Close the session and confirm
    archived notification text is selectable but not actionable.
35. Post before any launcher Activity, then open MML and confirm inbox drain exactly once. Test a
    malformed row, more than 512 pending posts, newest-first retention within available capacity,
    OPEN-session backpressure at 512 and capacity recovery after clear/history deletion.
36. Clear captured notifications with rows in current/archived sessions and inbox. Interrupt at the
    recovery marker and confirm restart completes redaction and orphan cleanup while preserving
    session identity, other machine rows, Comments and calendar plans. Also rotate between Clear
    completion and its Activity refresh, and inject a failure after partial redaction; confirm the
    replacement invalidates archived Calendar membership/cursors and reloads the bounded window.
37. At 100%, 130%, 150% and 200% font scale on 320dp/360dp, test all screens and dialogs in portrait
    and landscape. Confirm app/picker rows wrap without clipping, Calendar selects list mode at its
    thresholds, the date composer remains horizontal, Current Session uses a full-width editor above
    Launcher/Send, and IME/navigation/display-cutout insets keep both fixed actions reachable as the
    editor grows, adapts down to one landscape line when required, and internally scrolls.
38. Confirm age deletion and Clear journal history remove only closed sessions. Per-date Delete and
    Clear all calendar Comments must not alter sessions; capture clear must not alter non-capture
    rows. Verify Future events notification updates after each relevant Comments mutation.
39. Confirm backup is disabled. The manifest may request `WRITE_SETTINGS` and `POST_NOTIFICATIONS`
    and declares one non-exported listener protected by
    `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`. It requests no Internet, direct-call,
    contacts, location, microphone, camera or accessibility-service access.
40. In one long process, alternate hundreds of times among Calendar, empty dates, Current Session,
    App list and Settings while recording a heap profile. Confirm detached screen trees are
    collectible, empty day visits do not grow the draft map, and only nonempty unsaved drafts remain
    process-local. Repeat after building a long Current Session transcript. Confirm the live view
    retains no more than 160 rows/512 KiB, appends without lifetime transcript reloads, and reports
    individually oversized rows as durable archive-only content rather than retaining them.
41. Seed many closed sessions and large event files. Confirm Calendar shows its loading state while
    a single cancellable worker streams a compact session/date index, month changes reuse the
    process cache, and stale callbacks cannot replace another screen or a newer month. Confirm each
    count and date membership matches the full records in all five projections, including legacy
    Comments and multi-timezone sessions, while administrative next-start rows remain excluded.
    Open a heavily populated date and page repeatedly; confirm each Previous/Next Log Page reads at
    most 160 physical rows/512 KiB off the UI thread, ordering remains oldest-first, and malformed
    or oversized rows are reported without changing the durable file. Confirm prior pages become
    collectible after paging or leaving the date. Recreate/destroy the Activity during both
    index and transcript loading and confirm the worker stops or its result is ignored.
42. Seed many closed sessions and large event files. Confirm the next launcher-process startup
    closes only stale OPEN records without materializing archived transcripts, notification-ID
    reconciliation stays bounded to its ID set, and Clear captured notifications completes by
    streaming replacements while retaining every non-capture row.
43. On a clean install, open MML Settings and confirm Log storage is app-managed, the directory
    field is blank, and the controls are exactly `Specify user-managed log directory` and
    `Clear app-managed logs`. Verify no persisted document-tree grant exists. Send Current Session
    notes and capture a notification; confirm they remain available from MML-managed application
    storage and Android removes that storage through Clear app data or uninstall as ordinarily
    specified.
44. Save current and closed session rows, captured notifications and at least one Calendar Comment.
    Press `Clear app-managed logs`, review the confirmation and execute it. Confirm current and
    closed session log contents and captured-notification rows are removed, the Current Session
    remains open/writable with the same identity, and Calendar Comments/Future events remain. Kill
    the process at the durable clear marker and confirm reopening completes the same result. While a
    user-managed directory is active, confirm this button and other session-log deletion controls
    are unavailable.
45. Press `Specify user-managed log directory`, cancel the picker, choose a read-only/inaccessible
    provider, and interrupt the initial copy. In every failure case confirm the prior storage choice
    remains active and working state stays complete. Then select a writable tree and confirm the
    choice changes only after a complete background `MML Logs` directory exists. Confirm the field
    identifies the selected tree plus `/MML Logs`. On Android's primary filesystem provider,
    confirm creation succeeds without changing `README.txt.MML-pending.txt`, the index recovery
    filename or any `<id>.events.txt` filename. Rotate while picker/copy work is active; confirm one
    process-global operation and correct result restoration.
46. Inspect the user-managed files after session events, multiline Comments, calendar Comments,
    pending notifications, capture draining and session closure. Confirm UTF-8/escaping
    documentation and exact generated-file reconciliation. Post a prolonged high-rate notification
    stream with the Activity absent and present; confirm listener writes schedule the same serialized
    worker, requests coalesce, `.events.txt` appends are bounded where supported, and callback/UI
    responsiveness and heap/queue use remain bounded. Add unrelated files and confirm MML never
    deletes or rewrites them.
47. Revoke the tree grant, unmount/disconnect the provider, fill it and kill MML during a write.
    Confirm Settings exposes the failure, internal recovery writes remain safe, and reselecting the
    directory heals files off the UI thread. Interrupt pending acquisition, final creation and
    pending-preserved copy. Confirm startup reconciles grants and complete README/index/data files
    without trusting a truncated final. Verify both the current `MML USER-MANAGED LOG DIRECTORY`
    marker and a valid legacy marker establish ownership. Exercise duplicate names/document IDs,
    altered create names/kinds, metadata limits, 8,190/8,191 live-file boundaries and the 512-KiB
    encoded-index bound; each ambiguous or over-capacity case must fail visibly before unsafe
    adoption or mutation.
48. Switch from one selected tree to another. Confirm the new complete files exist before preference
    commit and the obsolete grant is released without deleting the old folder. Erase the directory
    field and confirm status returns to app-managed, user-managed writes stop, and the saved tree URI
    and grant disappear while all existing user-owned files remain untouched. With the field erased,
    copy, edit, archive and delete the old files through a file manager. Confirm MML does not recreate
    them. Confirm app-managed clearing/retention controls become available again.
49. Populate calendar Comments near the 4,096-date/16-MiB aggregate limits and exercise
    32,768-character Save plus one-character-over rejection. Confirm Save/Delete, Future events notification scanning and Future events list loading
    never stall the UI, a failed write leaves the draft and durable document unchanged,
    and at most 16 nonempty day drafts remain process-local. Rotate during retention count,
    confirmed deletion, Clear history, Comment Save and Clear Comments. Confirm only one global
    operation runs, Settings disables its maintenance controls while busy, count confirmation is
    neither repeated nor lost, and each completed audit/toast/result is consumed once.

## Build requirement

The final compile requires JDK 17, Android SDK 35 and the declared Android Gradle plugin/wrapper.
Host tests do not substitute for installed-device HOME-role, process-death, IME, clickable-span,
runtime-permission, notification-listener special access, notification-channel, service/Activity
coordination, dialog/picker, font-scale, shutdown-broadcast or ICU-calendar testing.
