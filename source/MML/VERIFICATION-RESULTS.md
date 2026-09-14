# MML 1.6.6 verification results

Run date: 2026-08-27

The complete host verification suite passed from the isolated MML 1.6.6 source tree:

- 16 compiled pure-Java behavior and policy self-tests across 28 production classes.
- Java syntax validation for 55 source files.
- XML syntax validation for 13 resource and manifest files.
- MML structure and Android-signature checks.
- Paired light/night theme, launcher-icon and contrast checks.

The log-storage tests require app-managed storage to be the default and require the exact controls
`Specify user-managed log directory` and `Clear app-managed logs`. The directory field is blank in
app-managed mode. Erasing a selected directory returns MML to app-managed storage and releases the
persisted tree grant without deleting user-owned files.

The JournalStore tests exercise app-managed clearing with current and closed session rows, captured
notifications, Calendar Comments and process-death recovery. Clearing removes session-log contents
and captured-notification log rows, preserves the Current Session identity as an open writable
session, and preserves Calendar Comments/Future events as separate planning data. A durable pending
marker makes an interrupted clear finish on the next startup. App-managed clearing and other
MML-owned log-deletion controls are unavailable while a user-managed directory is active.

The user-managed directory policy retains the provider-safety corrections from MML 1.6.4. Recovery
and event filenames remain `.txt`-terminated, generated names and document kinds must match, and
MML verifies stored bytes before adopting a created document. A successful selection creates or
uses the `MML Logs` child directory and commits the new choice only after initialization succeeds.
The prior working state remains active after cancellation, provider rejection or incomplete setup.

The presentation-policy test requires Calendar to have no graphical brightness slider and no
horizontal month-swipe recognizer. A clearly horizontal drag anywhere on Calendar adjusts Android
system brightness. Right increases, left decreases, and 75 percent of screen width represents a
100-percentage-point adjustment before clamping to 1 through 100 percent. Vertical and insufficient
movement are not brightness gestures. Previous and Next remain the sole month controls.

The MML 1.6.5 Future events work remains covered: chronological filtering, full in-app event text,
the 1:2 `Future events / Current Session` footer, the virtualized list, `Future events...` expanded
notification text, bounded chronological notification summaries and date opening. The MML 1.6.4
App-list correction also remains covered: rows are not long-clickable and Android application
settings open only through the fixed `App settings` button.

A Gradle Android assembly was attempted with
`./gradlew --offline :app:compileDebugJavaWithJavac`, but the wrapper distribution was not present
locally and the
launcher attempted to resolve `services.gradle.org`. Network resolution was unavailable, so Gradle
8.13 could not be obtained. No APK generation, Android lint, emulator run, signing result or
installed-device test is claimed. The source archive is intended for Android Studio assembly and
handset verification.
