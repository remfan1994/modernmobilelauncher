package vip.thatiam.mml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class JournalStoreSelfTest {
    public static void main(String[] arguments) throws Exception {
        Path temporary = Files.createTempDirectory("mml-journal-test-");
        JournalStore store = new JournalStore(temporary.toFile());

        JournalSession first = store.startLauncherSession(17L, 1_000L, "UTC");
        check(first.getStatus() == SessionStatus.OPEN, "first process session open");
        check(lastType(first).equals("launcher_session_started"), "startup event name");
        store.appendEvent(first.getId(), new JournalEvent(
                1_100L, "UTC", "launch_requested", "Example | example/.Main"));
        String exactUserNote = "  first line\nsecond line  ";
        store.appendEvent(first.getId(), new JournalEvent(
                1_150L, "UTC", "user_note", exactUserNote));
        store.saveDailyNote(first.getId(), "2026-08-18", 2_000L, "UTC", "legacy note");

        String futureText = "3:20 Highland Hospital\nhttps://example.org\n510-555-0123\n"
                + "app:vip.thatiam.nanoweb";
        CalendarComment future = store.saveCalendarComment(
                "2026-08-31",
                "America/Los_Angeles",
                1_200L,
                "UTC",
                futureText);
        check(future != null, "future Comments document created");
        check(future.getText().equals(futureText), "future Comment text retained exactly");
        check(future.getTargetLocalDate().equals("2026-08-31"), "intended date retained");
        check(future.getTargetZoneId().equals("America/Los_Angeles"),
                "intended timezone retained");
        check(future.getCreatedEpochMillis() == 1_200L, "creation timestamp retained");
        check(future.getCreatedZoneId().equals("UTC"), "creation timezone retained");
        check(store.listCalendarComments().size() == 1, "one Comments document per date");

        CalendarComment edited = store.saveCalendarComment(
                "2026-08-31",
                "Pacific/Honolulu",
                1_300L,
                "Pacific/Honolulu",
                futureText + "\nBring ID");
        check(edited != null && edited.getText().endsWith("Bring ID"),
                "future Comments document replaced");
        check(edited.getCreatedEpochMillis() == 1_200L,
                "edit preserves original creation timestamp");
        check(edited.getTargetZoneId().equals("America/Los_Angeles"),
                "edit preserves intended timezone");

        JournalStore reopenedWithComments = new JournalStore(temporary.toFile());
        check(reopenedWithComments.listCalendarComments().size() == 1,
                "future Comments survive store reconstruction");
        check(reopenedWithComments.listCalendarComments().get(0).getText()
                        .equals(futureText + "\nBring ID"),
                "reopened future Comments retain exact content");
        long futureListNow = Instant.parse("2026-08-27T19:00:00Z").toEpochMilli();
        List<CalendarComment> futureEvents =
                reopenedWithComments.listFutureCalendarComments(futureListNow);
        check(futureEvents.size() == 1
                        && futureEvents.get(0).getTargetLocalDate().equals("2026-08-31"),
                "full future-event list is chronological and date filtered");
        JournalStore.FutureCalendarCommentScan futureScan =
                reopenedWithComments.scanFutureCalendarComments(futureListNow, 20, 120);
        check(futureScan.getCount() == 1 && futureScan.getSummaries().size() == 1,
                "future scan returns count and retained notification summary");
        check(futureScan.getSummaries().get(0).getText().contains("Highland Hospital"),
                "future scan retains bounded readable event text");

        JournalSession second = store.startLauncherSession(17L, 3_000L, "UTC");
        JournalSession archivedFirst = store.loadSession(first.getId());
        check(!second.getId().equals(first.getId()), "new process startup creates new session");
        check(archivedFirst.getStatus() == SessionStatus.CLOSED, "prior session archived");
        check(lastType(archivedFirst).equals("later_launcher_startup_observed"),
                "startup boundary appended");
        check(lastDetail(archivedFirst).contains("does not prove a crash"),
                "boundary avoids crash overclaim");
        check(archivedFirst.getEvents().get(2).getDetail().equals(exactUserNote),
                "user note bytes retained exactly");
        check(archivedFirst.getDailyNotes().size() == 1,
                "legacy editable notes remain readable");

        store.appendEvent(second.getId(), new JournalEvent(
                3_100L, "UTC", "home_resumed", "Returned."));
        JournalSession third = store.startLauncherSession(18L, 4_000L, "America/Los_Angeles");
        check(store.loadSession(second.getId()).getStatus() == SessionStatus.CLOSED,
                "same or different boot both archive by process startup");
        JournalSession shutdownObserved = store.markShutdownObserved(
                third.getId(), 4_500L, "America/Los_Angeles", "Test shutdown.");
        check(shutdownObserved.getStatus() == SessionStatus.CLOSED, "shutdown closes session");
        check(lastType(shutdownObserved).equals("shutdown_observed"), "shutdown event");

        JournalSession active = store.startLauncherSession(18L, 5_000L, "UTC");
        CapturedNotification captured = CapturedNotification.create(
                "capture-1",
                5_100L,
                "UTC",
                "org.example.mail",
                "Example Mail",
                "Appointment confirmed",
                "August 31 at 3:20 PM",
                "Inbox");
        check(store.appendCapturedNotification(active.getId(), captured),
                "captured notification appended to current OPEN session");
        check(store.appendCapturedNotificationResult(active.getId(), captured)
                        == JournalStore.CaptureDisposition.DUPLICATE,
                "duplicate capture is identified without another append");
        check(store.countCapturedNotifications() == 1,
                "stable capture ID prevents duplicate log rows");
        CapturedNotification queued = CapturedNotification.create(
                "capture-2", 5_200L, "America/Los_Angeles", "org.example.calendar",
                "Calendar", "Dentist", "Highland Hospital", "3:20 PM");
        store.queueCapturedNotification(queued);
        store.queueCapturedNotification(queued);
        JournalStore concurrentStoreView = new JournalStore(temporary.toFile());
        check(concurrentStoreView.countCapturedNotifications() == 2,
                "pending inbox de-duplicates repeated callbacks across store instances");
        Path notificationInbox = temporary.resolve("mml-journal")
                .resolve("notification-inbox");
        Files.writeString(notificationInbox, "Q\\tmalformed\\trow\n", StandardOpenOption.APPEND);
        check(store.drainCapturedNotifications(active.getId()) == 1,
                "valid pending capture drains while malformed row is isolated");
        check(store.countCapturedNotifications() == 2,
                "direct and queued captures are both counted");
        check(store.countSessionsOlderThan(3_500L, active.getId()) == 2,
                "retention selects archived status and real durable age");
        int deleted = store.deleteSessionsOlderThan(3_500L, active.getId());
        check(deleted == 2, "two old archived sessions deleted");
        check(!recordFile(temporary, first.getId(), ".meta").toFile().exists(),
                "deleted metadata removed");
        check(!recordFile(temporary, second.getId(), ".events").toFile().exists(),
                "deleted events removed");

        Files.writeString(recordFile(temporary, "unreadable-record", ".meta"), "damaged\n");
        Files.writeString(recordFile(temporary, "unreadable-record", ".events"), "private\n");
        int cleared = store.clearArchivedSessions(active.getId());
        check(cleared == 2, "clear archived deletes closed and unreadable sessions");
        check(!recordFile(temporary, "unreadable-record", ".events").toFile().exists(),
                "clear archived removes unreadable private event data");
        check(store.listSessions().size() == 1, "clear archived preserves current OPEN session");
        check(store.loadSession(active.getId()).getStatus() == SessionStatus.OPEN,
                "current session remains writable");
        check(store.countCapturedNotifications() == 2,
                "session clear preserves captures in the current OPEN log");
        check(store.listCalendarComments().size() == 1,
                "session retention and clear preserve future Comments");

        Path calendarCommentsFile = temporary.resolve("mml-journal")
                .resolve("calendar-comments");
        Files.writeString(
                calendarCommentsFile,
                "C\\tmalformed\\trow\n",
                StandardOpenOption.APPEND);
        check(store.listCalendarComments().size() == 1,
                "one malformed future Comment row does not hide valid content");

        CalendarComment removed = store.saveCalendarComment(
                "2026-08-31",
                "America/Los_Angeles",
                5_500L,
                "UTC",
                "# \n#");
        check(removed == null, "prefix-only Comments delete the date document");
        check(store.listCalendarComments().isEmpty(), "future Comments document removed");
        store.saveCalendarComment(
                "2026-09-01", "America/Los_Angeles", 5_600L, "UTC", "next plan");

        int redactedCaptures = store.clearCapturedNotifications();
        check(redactedCaptures == 2, "explicit privacy clear reports captured rows");
        check(store.countCapturedNotifications() == 0,
                "explicit privacy clear removes captured rows and pending inbox");
        check(store.loadSession(active.getId()).getEvents().stream()
                        .noneMatch(event -> JournalStore.CAPTURED_NOTIFICATION_EVENT_TYPE
                                .equals(event.getType())),
                "capture clear preserves the session while redacting notification content");

        Path boundaryTemporary = Files.createTempDirectory("mml-boundary-age-test-");
        JournalStore boundaryStore = new JournalStore(boundaryTemporary.toFile());
        JournalSession boundaryFirst = boundaryStore.startLauncherSession(1L, 1_000L, "UTC");
        boundaryStore.appendEvent(boundaryFirst.getId(), new JournalEvent(
                43_201_000L, "UTC", "prior_boot_boundary_inferred", "Legacy 1.1 marker."));
        boundaryStore.appendEvent(boundaryFirst.getId(), new JournalEvent(
                64_801_000L, "UTC", "session_unclosed", "Legacy 1.0 marker."));
        JournalSession boundaryCurrent = boundaryStore.startLauncherSession(
                1L, 86_401_000L, "UTC");
        check(JournalStore.newestDurableTimestamp(
                        boundaryStore.loadSession(boundaryFirst.getId())) == 1_000L,
                "later-start administrative boundary does not change retention age");
        check(boundaryStore.countSessionsOlderThan(2_000L, boundaryCurrent.getId()) == 1,
                "administrative next-day boundary cannot protect old session from retention");

        store.clearAllSessions();
        check(store.listSessions().isEmpty(), "internal full clear removes every record");
        check(store.listCalendarComments().size() == 1,
                "internal session clear preserves independent calendar Comments");
        Path orphanedCalendarReplacement = temporary.resolve("mml-journal")
                .resolve("calendar-comments123.tmp");
        Files.writeString(orphanedCalendarReplacement, "private orphaned calendar text\n");
        store.clearCalendarComments();
        check(store.listCalendarComments().isEmpty(), "explicit Comments clear removes plans");
        check(!Files.exists(orphanedCalendarReplacement),
                "explicit Comments clear removes crash-orphaned replacement files");
        JournalSession fresh = store.startLauncherSession(19L, 6_000L, "UTC");
        Files.writeString(recordFile(temporary, fresh.getId(), ".delete-pending"), "DELETE1\n");
        JournalStore deletionRecovery = new JournalStore(temporary.toFile());
        check(deletionRecovery.listSessions().isEmpty(),
                "pending record deletion completes after reopening storage");

        JournalSession beforeInterruptedClear = deletionRecovery.startLauncherSession(
                20L, 7_000L, "UTC");
        deletionRecovery.appendEvent(beforeInterruptedClear.getId(), new JournalEvent(
                7_100L, "UTC", "test", "pending clear"));
        Path clearMarker = temporary.resolve("mml-journal").resolve("sessions")
                .resolve(".clear-pending");
        Files.writeString(clearMarker, "CLEAR1\n");
        JournalStore clearRecovery = new JournalStore(temporary.toFile());
        check(clearRecovery.listSessions().isEmpty(),
                "pending internal full clear completes after reopening storage");

        Path inboxTemporary = Files.createTempDirectory("mml-notification-inbox-test-");
        JournalStore inboxStore = new JournalStore(inboxTemporary.toFile());
        CapturedNotification beforeActivity = CapturedNotification.create(
                "capture-before-activity", 8_000L, "UTC", "org.example.messages",
                "Messages", "Hello", "Queued before launcher Activity", "");
        inboxStore.queueCapturedNotification(beforeActivity);
        JournalSession inboxSession = inboxStore.startLauncherSession(21L, 8_100L, "UTC");
        check(inboxStore.drainCapturedNotifications(inboxSession.getId()) == 1,
                "listener capture before Activity attaches to new process session");
        Files.writeString(
                inboxTemporary.resolve("mml-journal").resolve(".notification-clear-pending"),
                "CLEAR-NOTIFICATIONS-1\n");
        JournalStore notificationClearRecovery = new JournalStore(inboxTemporary.toFile());
        check(notificationClearRecovery.countCapturedNotifications() == 0,
                "interrupted notification privacy clear completes after reopening storage");
        check(notificationClearRecovery.listSessions().size() == 1,
                "notification privacy clear keeps non-notification session history");

        Path retryTemporary = Files.createTempDirectory("mml-notification-retry-test-");
        JournalStore retryQueueStore = new JournalStore(retryTemporary.toFile());
        JournalSession retrySession = retryQueueStore.startLauncherSession(22L, 9_000L, "UTC");
        CapturedNotification retried = CapturedNotification.create(
                "capture-retried", 9_100L, "UTC", "org.example.reminders",
                "Reminders", "Dentist", "Crash-safe queue retry", "");
        retryQueueStore.queueCapturedNotification(retried);
        JournalStore retryAppendStore = new JournalStore(retryTemporary.toFile());
        retryAppendStore.appendEvent(retrySession.getId(), new JournalEvent(
                retried.getPostedEpochMillis(),
                retried.getCapturedZoneId(),
                JournalStore.CAPTURED_NOTIFICATION_EVENT_TYPE,
                retried.encodeDetail()));
        check(retryQueueStore.drainCapturedNotifications(retrySession.getId()) == 0,
                "event-plus-inbox crash state retries without duplicating the event");
        check(retryQueueStore.countCapturedNotifications() == 1,
                "idempotent retry clears the stale inbox copy");

        CapturedNotification closedPending = CapturedNotification.create(
                "capture-closed-pending", 9_200L, "UTC", "org.example.tasks",
                "Tasks", "Later", "Wait for the next OPEN session", "");
        retryQueueStore.queueCapturedNotification(closedPending);
        JournalSession replacementSession = retryQueueStore.startLauncherSession(
                22L, 9_300L, "UTC");
        CapturedNotification afterClose = CapturedNotification.create(
                "capture-after-close", 9_350L, "UTC", "org.example.tasks",
                "Tasks", "Closed", "Do not append to the old session", "");
        check(retryQueueStore.appendCapturedNotificationResult(
                        retrySession.getId(), afterClose)
                        == JournalStore.CaptureDisposition.SESSION_CLOSED,
                "closed session reports that the listener must use the inbox");
        check(retryQueueStore.drainCapturedNotifications(retrySession.getId()) == 0,
                "a CLOSED target session cannot consume the pending inbox");
        check(retryQueueStore.countCapturedNotifications() == 2,
                "pending capture remains durable when the requested session is CLOSED");
        check(retryQueueStore.drainCapturedNotifications(replacementSession.getId()) == 1,
                "the next OPEN session consumes the retained pending capture");

        Path raceTemporary = Files.createTempDirectory("mml-notification-race-test-");
        JournalStore raceStoreOne = new JournalStore(raceTemporary.toFile());
        JournalSession raceSession = raceStoreOne.startLauncherSession(23L, 10_000L, "UTC");
        JournalStore raceStoreTwo = new JournalStore(raceTemporary.toFile());
        CapturedNotification raced = CapturedNotification.create(
                "capture-race", 10_100L, "UTC", "org.example.chat",
                "Chat", "Hello", "Two listener callbacks", "");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        Thread firstAppender = captureAppender(
                raceStoreOne, raceSession.getId(), raced, ready, start, threadFailure);
        Thread secondAppender = captureAppender(
                raceStoreTwo, raceSession.getId(), raced, ready, start, threadFailure);
        firstAppender.start();
        secondAppender.start();
        ready.await();
        start.countDown();
        firstAppender.join();
        secondAppender.join();
        check(threadFailure.get() == null, "two-store capture race completes without I/O failure");
        check(raceStoreOne.countCapturedNotifications() == 1,
                "process storage lock makes two-store check-and-append atomic");

        CapturedNotification queueDrainRace = CapturedNotification.create(
                "capture-queue-drain-race", 10_120L, "UTC", "org.example.chat",
                "Chat", "Queued", "Queue and drain overlap", "");
        CountDownLatch queueDrainReady = new CountDownLatch(2);
        CountDownLatch queueDrainStart = new CountDownLatch(1);
        Thread queueThread = new Thread(() -> {
            try {
                queueDrainReady.countDown();
                queueDrainStart.await();
                raceStoreOne.queueCapturedNotification(queueDrainRace);
            } catch (Throwable error) {
                threadFailure.compareAndSet(null, error);
            }
        });
        Thread drainThread = new Thread(() -> {
            try {
                queueDrainReady.countDown();
                queueDrainStart.await();
                raceStoreTwo.drainCapturedNotifications(raceSession.getId());
            } catch (Throwable error) {
                threadFailure.compareAndSet(null, error);
            }
        });
        queueThread.start();
        drainThread.start();
        queueDrainReady.await();
        queueDrainStart.countDown();
        queueThread.join();
        drainThread.join();
        check(threadFailure.get() == null, "queue-versus-drain race completes without I/O failure");
        raceStoreOne.drainCapturedNotifications(raceSession.getId());
        check(raceStoreOne.countCapturedNotifications() == 2,
                "queue-versus-drain race retains the capture exactly once");

        Path malformedEvent = recordFile(raceTemporary, raceSession.getId(), ".events");
        Files.writeString(
                malformedEvent,
                LineCodec.encode(
                        "E", "10150", "UTC",
                        JournalStore.CAPTURED_NOTIFICATION_EVENT_TYPE,
                        "private malformed detail") + "\n"
                        + LineCodec.encode(
                        "E", "10160", "UTC",
                        JournalStore.CAPTURED_NOTIFICATION_EVENT_TYPE,
                        "private extra-field detail", "extra") + "\n",
                StandardOpenOption.APPEND);
        check(raceStoreOne.countCapturedNotifications() == 4,
                "privacy count includes unreadable and extra-field capture rows");
        Path orphanedInboxReplacement = raceTemporary.resolve("mml-journal")
                .resolve("notification-inbox123.tmp");
        Path orphanedEventReplacement = recordFile(
                raceTemporary, raceSession.getId(), ".events456.tmp");
        Files.writeString(orphanedInboxReplacement, "private orphaned inbox text\n");
        Files.writeString(orphanedEventReplacement, "private orphaned event text\n");
        check(raceStoreOne.clearCapturedNotifications() == 4,
                "privacy clear remains available when capture payloads are malformed");
        check(!Files.readString(malformedEvent).contains("private"),
                "privacy clear conservatively removes malformed capture payload text");
        check(!Files.exists(orphanedInboxReplacement)
                        && !Files.exists(orphanedEventReplacement),
                "privacy clear removes crash-orphaned inbox and event replacement files");

        Path pendingClearTemporary = Files.createTempDirectory(
                "mml-notification-pending-clear-test-");
        JournalStore pendingClearStore = new JournalStore(pendingClearTemporary.toFile());
        CapturedNotification pendingDuringClear = CapturedNotification.create(
                "capture-pending-clear", 11_000L, "UTC", "org.example.private",
                "Private", "Sensitive", "Queued content", "");
        pendingClearStore.queueCapturedNotification(pendingDuringClear);
        pendingClearStore.clearAllSessions();
        check(pendingClearStore.countCapturedNotifications() == 1,
                "session-history clear preserves the independent pending capture inbox");
        Files.writeString(
                pendingClearTemporary.resolve("mml-journal")
                        .resolve(".notification-clear-pending"),
                "CLEAR-NOTIFICATIONS-1\n");
        Path recoveryInboxOrphan = pendingClearTemporary.resolve("mml-journal")
                .resolve("notification-inbox789.tmp");
        Path recoveryEventOrphan = pendingClearTemporary.resolve("mml-journal")
                .resolve("sessions").resolve("orphan.events789.tmp");
        Files.writeString(recoveryInboxOrphan, "private queued replacement\n");
        Files.writeString(recoveryEventOrphan, "private event replacement\n");
        JournalStore pendingClearRecovery = new JournalStore(pendingClearTemporary.toFile());
        check(pendingClearRecovery.countCapturedNotifications() == 0,
                "privacy-clear recovery also erases a pending private inbox");
        check(!Files.exists(recoveryInboxOrphan) && !Files.exists(recoveryEventOrphan),
                "privacy-clear recovery removes orphaned sensitive replacement files");

        Path boundedTemporary = Files.createTempDirectory("mml-notification-bound-test-");
        JournalStore boundedStore = new JournalStore(boundedTemporary.toFile());
        for (int index = 0; index <= 512; index++) {
            boundedStore.queueCapturedNotification(CapturedNotification.create(
                    "bounded-" + index,
                    12_000L + index,
                    "UTC",
                    "org.example.bounded",
                    "Bounded",
                    "Pending " + index,
                    "",
                    ""));
        }
        check(boundedStore.countCapturedNotifications() == 512,
                "pending capture inbox has a fixed private-storage bound");
        JournalSession boundedSession = boundedStore.startLauncherSession(
                24L, 13_000L, "UTC");
        check(boundedStore.drainCapturedNotifications(boundedSession.getId()) == 512,
                "every retained bounded-inbox capture drains once");
        check(captureIds(boundedStore.loadSession(boundedSession.getId()))
                        .contains("bounded-512"),
                "bounded inbox retains the newest pending capture");
        check(!captureIds(boundedStore.loadSession(boundedSession.getId()))
                        .contains("bounded-0"),
                "bounded inbox discards the oldest pending capture first");
        CapturedNotification beyondStoredLimit = CapturedNotification.create(
                "bounded-beyond-limit",
                14_000L,
                "UTC",
                "org.example.bounded",
                "Bounded",
                "Beyond limit",
                "",
                "");
        check(boundedStore.appendCapturedNotificationResult(
                        boundedSession.getId(), beyondStoredLimit)
                        == JournalStore.CaptureDisposition.AT_CAPACITY,
                "capture beyond the storage limit reports explicit backpressure");
        check(boundedStore.queueCapturedNotificationResult(beyondStoredLimit)
                        == JournalStore.CaptureDisposition.AT_CAPACITY,
                "full storage avoids an unnecessary empty-inbox rewrite");
        check(boundedStore.countCapturedNotifications() == 512,
                "session logs and pending inbox share one total capture bound");
        boundedStore.clearCapturedNotifications();
        check(boundedStore.appendCapturedNotification(
                        boundedSession.getId(), beyondStoredLimit),
                "capture resumes after explicit privacy clear frees storage capacity");
        check(boundedStore.countCapturedNotifications() == 1,
                "freed capture capacity accepts the next notification");

        Path verbosityTemporary = Files.createTempDirectory("mml-verbosity-store-test-");
        JournalStore verbosityStore = new JournalStore(verbosityTemporary.toFile());
        JournalSession quietFirst = verbosityStore.startLauncherSession(
                25L, 15_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        check(quietFirst.getEvents().isEmpty(),
                "notifications-only omits the automatic startup row");
        JournalSession quietSecond = verbosityStore.startLauncherSession(
                25L, 16_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        check(verbosityStore.loadSession(quietFirst.getId()).getStatus()
                        == SessionStatus.CLOSED,
                "quiet policy still closes the prior session metadata");
        check(verbosityStore.loadSession(quietFirst.getId()).getEvents().isEmpty(),
                "notifications-only omits the later-startup boundary row");
        JournalSession quietClosed = verbosityStore.markShutdownObserved(
                quietSecond.getId(),
                17_000L,
                "UTC",
                "Test quiet shutdown.",
                LogVerbosity.NOTIFICATIONS_ONLY);
        check(quietClosed.getStatus() == SessionStatus.CLOSED,
                "quiet policy still persists shutdown status");
        check(quietClosed.getEvents().isEmpty(),
                "notifications-only omits the automatic shutdown row");
        JournalSession importantSession = verbosityStore.startLauncherSession(
                25L, 18_000L, "UTC", LogVerbosity.IMPORTANT);
        check(importantSession.getEvents().isEmpty(),
                "important omits the full-only startup row");
        JournalSession importantClosed = verbosityStore.markShutdownObserved(
                importantSession.getId(),
                19_000L,
                "UTC",
                "Test important shutdown.",
                LogVerbosity.IMPORTANT);
        check(lastType(importantClosed).equals("shutdown_observed"),
                "important records its shutdown row");
        JournalSession fullSession = verbosityStore.startLauncherSession(
                25L, 20_000L, "UTC", LogVerbosity.FULL);
        check(lastType(fullSession).equals("launcher_session_started"),
                "full records its startup row");

        Path indexTemporary = Files.createTempDirectory("mml-calendar-index-test-");
        JournalStore indexStore = new JournalStore(indexTemporary.toFile());
        JournalSession indexed = indexStore.startLauncherSession(26L, 1_000L, "UTC");
        indexStore.appendEvent(indexed.getId(), new JournalEvent(
                86_401_000L, "UTC", "comment", "x".repeat(30_000)));
        indexStore.appendEvent(indexed.getId(), new JournalEvent(
                86_402_000L, "UTC", "launch_requested", "same civil date"));
        indexStore.appendEvent(indexed.getId(), new JournalEvent(
                172_801_000L, "UTC", "prior_boot_boundary_inferred",
                "administrative date must not count"));
        indexStore.saveDailyNote(
                indexed.getId(), "1970-01-04", 259_201_000L, "UTC", "legacy private text");
        JournalSession currentAfterIndex = indexStore.startLauncherSession(
                26L, 345_601_000L, "UTC");
        indexStore.appendEvent(currentAfterIndex.getId(), new JournalEvent(
                432_001_000L, "UTC", "comment", "current-" + "y".repeat(30_000)));
        indexStore.appendEvent(currentAfterIndex.getId(), new JournalEvent(
                432_002_000L,
                "UTC",
                "comment",
                "z".repeat(JournalTextPolicy.MAX_CODE_POINTS)));
        boolean oversizedUserTextRejected = false;
        try {
            indexStore.appendEvent(currentAfterIndex.getId(), new JournalEvent(
                    432_003_000L,
                    "UTC",
                    "comment",
                    "z".repeat(JournalTextPolicy.MAX_CODE_POINTS + 1)));
        } catch (java.io.IOException expected) {
            oversizedUserTextRejected = true;
        }
        check(oversizedUserTextRejected,
                "storage rejects user text above the shared character limit");
        List<JournalSessionIndex> calendarIndex = indexStore.listCalendarSessionIndex();
        JournalSessionIndex archivedIndex = indexFor(calendarIndex, indexed.getId());
        check(archivedIndex.getStatus() == SessionStatus.CLOSED,
                "calendar index retains closed status");
        check(archivedIndex.getDateObservations().size() == 3,
                "calendar index deduplicates civil days and excludes boundaries");
        check(indexStore.loadArchivedSessionSnapshot(indexed.getId()).getEvents().size() == 5,
                "archived display snapshot preserves the complete transcript");
        boolean rejectedOpenSnapshot = false;
        try {
            indexStore.loadArchivedSessionSnapshot(currentAfterIndex.getId());
        } catch (java.io.IOException expected) {
            rejectedOpenSnapshot = true;
        }
        check(rejectedOpenSnapshot, "archived display snapshot rejects OPEN session");
        JournalSessionIndex openIndex = indexFor(calendarIndex, currentAfterIndex.getId());
        check(openIndex.getStatus() == SessionStatus.OPEN,
                "calendar index exposes OPEN status for caller exclusion");
        check(openIndex.getDateObservations().size() == 1,
                "calendar index never scans the excluded OPEN transcript");
        boolean immutableIndex = false;
        try {
            calendarIndex.clear();
        } catch (UnsupportedOperationException expected) {
            immutableIndex = true;
        }
        check(immutableIndex, "calendar index result is immutable");

        Path observedTemporary = Files.createTempDirectory("mml-observed-store-test-");
        AtomicInteger mutationSignals = new AtomicInteger();
        JournalStore observedStore = new JournalStore(
                observedTemporary.toFile(), mutationSignals::incrementAndGet);
        int afterOpen = mutationSignals.get();
        check(afterOpen > 0, "opening store requests mirror reconciliation");
        JournalSession observedSession = observedStore.startLauncherSession(
                27L, 600_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        check(mutationSignals.get() > afterOpen,
                "quiet structural session mutation requests mirroring");
        int afterStart = mutationSignals.get();
        observedStore.listSessions();
        observedStore.listCalendarComments();
        check(mutationSignals.get() == afterStart,
                "read-only journal operations do not request mirroring");
        observedStore.appendEvent(observedSession.getId(), new JournalEvent(
                600_100L, "UTC", "comment", "mirror me"));
        check(mutationSignals.get() > afterStart,
                "append-only event requests mirroring");
        int afterEvent = mutationSignals.get();
        observedStore.saveCalendarComment(
                "1970-01-08", "UTC", 600_200L, "UTC", "portable plan");
        check(mutationSignals.get() > afterEvent,
                "calendar Comment replacement requests mirroring");

        Path windowTemporary = Files.createTempDirectory("mml-window-store-test-");
        JournalStore windowStore = new JournalStore(windowTemporary.toFile());
        JournalSession longOpen = windowStore.startLauncherSession(
                28L, 700_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        for (int index = 0; index < 500; index++) {
            windowStore.appendEvent(longOpen.getId(), new JournalEvent(
                    700_001L + index,
                    "UTC",
                    "comment",
                    "entry-" + index));
        }
        JournalSessionWindow recent = windowStore.loadSessionWindow(
                longOpen.getId(), 20, 8 * 1024);
        check(recent.hasEarlierEventsOmitted(), "long current session reports omitted prefix");
        check(recent.getSession().getEvents().size() == 20,
                "current session materializes only bounded event count");
        check(lastDetail(recent.getSession()).equals("entry-499"),
                "current session window retains newest event");
        check(windowStore.isSessionOpen(longOpen.getId()),
                "lightweight OPEN check preserves writable state");
        windowStore.appendEvent(longOpen.getId(), new JournalEvent(
                800_000L, "UTC", "comment", "x".repeat(20_000)));
        JournalSessionWindow byteBounded = windowStore.loadSessionWindow(
                longOpen.getId(), 20, 1_024);
        check(byteBounded.hasEarlierEventsOmitted(),
                "oversized newest row is omitted without losing durable storage");
        check(byteBounded.getSession().getEvents().isEmpty(),
                "oversized row is not materialized beyond byte window");

        windowStore.saveDailyNote(
                longOpen.getId(), "1970-01-10", 800_001L, "UTC", "legacy page note");
        windowStore.startLauncherSession(
                28L, 900_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        Path archivedEvents = recordFile(windowTemporary, longOpen.getId(), ".events");
        long beforeCorruptRow = Files.size(archivedEvents);
        Files.writeString(
                archivedEvents,
                "corrupt-" + "q".repeat(400_000) + "\n",
                StandardOpenOption.APPEND);
        long afterCorruptRow = Files.size(archivedEvents);
        check(afterCorruptRow > beforeCorruptRow,
                "test seeds an oversized legacy row without the bounded public writer");

        JournalArchiveCursor cursor = JournalArchiveCursor.first();
        JournalArchivePage firstPage = null;
        JournalArchivePage secondPage = null;
        int pagedEvents = 0;
        int pagedNotes = 0;
        int skippedOversized = 0;
        int pageCount = 0;
        while (cursor != null) {
            JournalArchivePage page = windowStore.loadArchivedSessionPage(
                    longOpen.getId(), cursor);
            if (pageCount == 0) {
                firstPage = page;
            } else if (pageCount == 1) {
                secondPage = page;
            }
            check(page.getEvents().size() <= JournalStore.MAX_ARCHIVE_PAGE_RECORDS,
                    "archive page event count stays bounded");
            check(page.getLegacyNotes().size() <= JournalStore.MAX_ARCHIVE_PAGE_RECORDS,
                    "archive page legacy-note count stays bounded");
            pagedEvents += page.getEvents().size();
            pagedNotes += page.getLegacyNotes().size();
            skippedOversized += page.getOversizedRowsSkipped();
            cursor = page.getNextCursor();
            pageCount++;
            check(pageCount < 20, "archive paging always makes bounded forward progress");
        }
        check(pagedEvents == 501, "archive paging exposes every valid durable event");
        check(pagedNotes == 1, "archive paging reaches the legacy Comments section");
        check(skippedOversized == 1,
                "archive paging reports one oversized corrupt row without materializing it");
        check(Files.size(archivedEvents) == afterCorruptRow,
                "archive paging never truncates durable source data");
        check(firstPage != null && secondPage != null
                        && secondPage.getPreviousCursor().equals(firstPage.getCursor()),
                "second archive page provides a usable previous-page cursor");
        JournalArchivePage previousPage = windowStore.loadArchivedSessionPage(
                longOpen.getId(), secondPage.getPreviousCursor());
        check(previousPage.getCursor().equals(firstPage.getCursor()),
                "previous-page cursor returns to the preceding archive page");

        Path retentionConcurrencyRoot = Files.createTempDirectory(
                "mml-retention-concurrency-test-");
        CountDownLatch retentionScanStarted = new CountDownLatch(1);
        CountDownLatch releaseRetentionScan = new CountDownLatch(1);
        JournalStore retentionConcurrencyStore = new JournalStore(
                retentionConcurrencyRoot.toFile(),
                null,
                scanName -> {
                    if ("retention-count".equals(scanName)) {
                        retentionScanStarted.countDown();
                        awaitScanRelease(releaseRetentionScan);
                    }
                });
        JournalSession retentionArchived = retentionConcurrencyStore.startLauncherSession(
                29L, 1_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        JournalSession retentionCurrent = retentionConcurrencyStore.startLauncherSession(
                29L, 2_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        AtomicReference<Throwable> retentionFailure = new AtomicReference<>();
        Thread retentionThread = new Thread(() -> {
            try {
                retentionConcurrencyStore.countSessionsOlderThan(
                        10_000L, retentionCurrent.getId());
            } catch (Throwable failure) {
                retentionFailure.set(failure);
            }
        });
        retentionThread.start();
        check(retentionScanStarted.await(2, TimeUnit.SECONDS),
                "retention scan reaches the deliberately slow out-of-lock phase");
        CountDownLatch retentionAppendDone = new CountDownLatch(1);
        Thread retentionAppend = new Thread(() -> {
            try {
                retentionConcurrencyStore.appendEvent(
                        retentionCurrent.getId(),
                        new JournalEvent(2_100L, "UTC", "comment", "foreground append"));
            } catch (Throwable failure) {
                retentionFailure.compareAndSet(null, failure);
            } finally {
                retentionAppendDone.countDown();
            }
        });
        try {
            retentionAppend.start();
            check(retentionAppendDone.await(2, TimeUnit.SECONDS),
                    "current-session append proceeds while retention scan is paused");
        } finally {
            releaseRetentionScan.countDown();
        }
        retentionAppend.join(2_000L);
        retentionThread.join(2_000L);
        check(retentionFailure.get() == null,
                "retention concurrency regression completes without worker failure");
        check(retentionConcurrencyStore.loadArchivedSessionSnapshot(
                        retentionArchived.getId()).getStatus() == SessionStatus.CLOSED,
                "paused retention count never mutates the archived record");

        Path captureConcurrencyRoot = Files.createTempDirectory(
                "mml-capture-index-concurrency-test-");
        CountDownLatch captureScanStarted = new CountDownLatch(1);
        CountDownLatch releaseCaptureScan = new CountDownLatch(1);
        JournalStore captureConcurrencyStore = new JournalStore(
                captureConcurrencyRoot.toFile(),
                null,
                scanName -> {
                    if ("capture-id-index".equals(scanName)) {
                        captureScanStarted.countDown();
                        awaitScanRelease(releaseCaptureScan);
                    }
                });
        JournalSession captureCurrent = captureConcurrencyStore.startLauncherSession(
                30L, 3_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        AtomicReference<Throwable> captureScanFailure = new AtomicReference<>();
        Thread captureScan = new Thread(() -> {
            try {
                captureConcurrencyStore.appendCapturedNotification(
                        captureCurrent.getId(),
                        CapturedNotification.create(
                                "concurrency-capture",
                                3_100L,
                                "UTC",
                                "org.example",
                                "Example",
                                "Title",
                                "Text",
                                "3:10"));
            } catch (Throwable failure) {
                captureScanFailure.set(failure);
            }
        });
        captureScan.start();
        check(captureScanStarted.await(2, TimeUnit.SECONDS),
                "capture ID index reaches the deliberately slow out-of-lock phase");
        CountDownLatch captureAppendDone = new CountDownLatch(1);
        Thread captureForegroundAppend = new Thread(() -> {
            try {
                captureConcurrencyStore.appendEvent(
                        captureCurrent.getId(),
                        new JournalEvent(3_200L, "UTC", "comment", "foreground append"));
            } catch (Throwable failure) {
                captureScanFailure.compareAndSet(null, failure);
            } finally {
                captureAppendDone.countDown();
            }
        });
        try {
            captureForegroundAppend.start();
            check(captureAppendDone.await(2, TimeUnit.SECONDS),
                    "current-session append proceeds while capture ID scan is paused");
        } finally {
            releaseCaptureScan.countDown();
        }
        captureForegroundAppend.join(2_000L);
        captureScan.join(2_000L);
        check(captureScanFailure.get() == null,
                "capture-index concurrency regression completes without worker failure");
        check(captureConcurrencyStore.countCapturedNotifications() == 1,
                "capture completes after the paused index scan resumes");

        Path appManagedClearRoot = Files.createTempDirectory(
                "mml-app-managed-clear-test-");
        JournalStore appManagedClearStore = new JournalStore(appManagedClearRoot.toFile());
        JournalSession appManagedArchived = appManagedClearStore.startLauncherSession(
                31L, 10_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        appManagedClearStore.appendEvent(
                appManagedArchived.getId(),
                new JournalEvent(10_100L, "UTC", "comment", "archived private note"));
        appManagedClearStore.saveDailyNote(
                appManagedArchived.getId(),
                "1970-01-01",
                10_200L,
                "UTC",
                "archived legacy note");
        appManagedClearStore.saveCalendarComment(
                "2099-12-31",
                "UTC",
                10_300L,
                "UTC",
                "Calendar plan must survive log clearing");
        JournalSession appManagedCurrent = appManagedClearStore.startLauncherSession(
                31L, 11_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        appManagedClearStore.appendEvent(
                appManagedCurrent.getId(),
                new JournalEvent(11_100L, "UTC", "comment", "current private note"));
        appManagedClearStore.saveDailyNote(
                appManagedCurrent.getId(),
                "1970-01-01",
                11_200L,
                "UTC",
                "current legacy note");
        appManagedClearStore.appendCapturedNotification(
                appManagedCurrent.getId(),
                CapturedNotification.create(
                        "clear-capture",
                        11_300L,
                        "UTC",
                        "org.example",
                        "Example",
                        "Captured title",
                        "Captured text",
                        "11:30"));
        int appManagedCleared = appManagedClearStore.clearAppManagedLogs(
                appManagedCurrent.getId());
        check(appManagedCleared == 2,
                "app-managed clear reports archived and current session logs");
        check(appManagedClearStore.listSessions().size() == 1,
                "app-managed clear retains only the Current Session record");
        JournalSession clearedCurrent = appManagedClearStore.loadSession(
                appManagedCurrent.getId());
        check(clearedCurrent.getId().equals(appManagedCurrent.getId()),
                "app-managed clear preserves the Current Session identity");
        check(clearedCurrent.getStatus() == SessionStatus.OPEN,
                "app-managed clear preserves the Current Session OPEN state");
        check(clearedCurrent.getEvents().isEmpty(),
                "app-managed clear empties Current Session event rows");
        check(clearedCurrent.getDailyNotes().isEmpty(),
                "app-managed clear empties Current Session legacy notes");
        check(!recordFile(appManagedClearRoot, appManagedArchived.getId(), ".meta")
                        .toFile().exists(),
                "app-managed clear deletes archived session metadata");
        check(appManagedClearStore.countCapturedNotifications() == 0,
                "app-managed clear removes captured-notification log rows");
        check(appManagedClearStore.listCalendarComments().size() == 1,
                "app-managed clear preserves Calendar Comments and Future events");
        appManagedClearStore.appendEvent(
                appManagedCurrent.getId(),
                new JournalEvent(11_400L, "UTC", "comment", "writable after clear"));
        check(lastDetail(appManagedClearStore.loadSession(appManagedCurrent.getId()))
                        .equals("writable after clear"),
                "preserved Current Session remains writable after app-managed clear");

        Path appManagedRecoveryRoot = Files.createTempDirectory(
                "mml-app-managed-clear-recovery-test-");
        JournalStore appManagedRecoveryStore = new JournalStore(
                appManagedRecoveryRoot.toFile());
        JournalSession recoveryArchived = appManagedRecoveryStore.startLauncherSession(
                32L, 20_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        appManagedRecoveryStore.appendEvent(
                recoveryArchived.getId(),
                new JournalEvent(20_100L, "UTC", "comment", "old log"));
        appManagedRecoveryStore.saveCalendarComment(
                "2099-12-30",
                "UTC",
                20_200L,
                "UTC",
                "Recovery must preserve this future event");
        JournalSession recoveryCurrent = appManagedRecoveryStore.startLauncherSession(
                32L, 21_000L, "UTC", LogVerbosity.NOTIFICATIONS_ONLY);
        appManagedRecoveryStore.appendEvent(
                recoveryCurrent.getId(),
                new JournalEvent(21_100L, "UTC", "comment", "current log"));
        appManagedRecoveryStore.appendCapturedNotification(
                recoveryCurrent.getId(),
                CapturedNotification.create(
                        "recovery-clear-capture",
                        21_200L,
                        "UTC",
                        "org.example",
                        "Example",
                        "Recovery capture",
                        "Recovery text",
                        "21:20"));
        Path appManagedClearMarker = appManagedRecoveryRoot.resolve("mml-journal")
                .resolve("sessions")
                .resolve(".app-managed-log-clear-pending");
        Files.writeString(
                appManagedClearMarker,
                LineCodec.encode(
                        "CLEAR-APP-MANAGED-LOGS-1",
                        recoveryCurrent.getId()) + "\n");
        JournalStore recoveredAppManagedStore = new JournalStore(
                appManagedRecoveryRoot.toFile());
        check(!Files.exists(appManagedClearMarker),
                "startup completes and retires pending app-managed clear marker");
        check(recoveredAppManagedStore.listSessions().size() == 1,
                "startup recovery removes every archived log");
        JournalSession recoveredCurrent = recoveredAppManagedStore.loadSession(
                recoveryCurrent.getId());
        check(recoveredCurrent.getId().equals(recoveryCurrent.getId())
                        && recoveredCurrent.getStatus() == SessionStatus.OPEN,
                "startup recovery preserves the named Current Session");
        check(recoveredCurrent.getEvents().isEmpty()
                        && recoveredCurrent.getDailyNotes().isEmpty(),
                "startup recovery empties the named Current Session log");
        check(recoveredAppManagedStore.countCapturedNotifications() == 0,
                "startup recovery removes captured-notification log data");
        check(recoveredAppManagedStore.listCalendarComments().size() == 1,
                "startup recovery preserves Calendar Comments and Future events");

        System.out.println("JournalStore self-test passed.");
    }

    private static Thread captureAppender(
            JournalStore store,
            String sessionId,
            CapturedNotification capture,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                ready.countDown();
                start.await();
                store.appendCapturedNotification(sessionId, capture);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    private static Path recordFile(Path root, String id, String suffix) {
        return root.resolve("mml-journal").resolve("sessions").resolve(id + suffix);
    }

    private static JournalSessionIndex indexFor(
            java.util.List<JournalSessionIndex> index,
            String sessionId) {
        for (JournalSessionIndex session : index) {
            if (sessionId.equals(session.getId())) {
                return session;
            }
        }
        throw new AssertionError("Missing calendar index session: " + sessionId);
    }

    private static java.util.Set<String> captureIds(JournalSession session) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (JournalEvent event : session.getEvents()) {
            if (!JournalStore.CAPTURED_NOTIFICATION_EVENT_TYPE.equals(event.getType())) {
                continue;
            }
            CapturedNotification capture = CapturedNotification.decodeDetail(event.getDetail());
            if (capture != null) {
                result.add(capture.getCaptureId());
            }
        }
        return result;
    }

    private static String lastType(JournalSession session) {
        return session.getEvents().get(session.getEvents().size() - 1).getType();
    }

    private static String lastDetail(JournalSession session) {
        return session.getEvents().get(session.getEvents().size() - 1).getDetail();
    }

    private static void awaitScanRelease(CountDownLatch release) throws java.io.IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new java.io.IOException("Timed out waiting to resume the test scan.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Test scan was interrupted.", interrupted);
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
