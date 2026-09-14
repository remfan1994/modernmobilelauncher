package vip.thatiam.mml;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable private journal storage.
 *
 * Session events are append-only except for the user's explicit privacy redaction of captured
 * notification rows. The replaceable per-session notes file is retained solely so daily notes
 * written by MML 1.1 and earlier remain readable during migration. Calendar Comments are a
 * separate date-planning document and never become evidence inside a launcher session.
 */
public final class JournalStore {
    private static final String META_VERSION = "MML1";
    private static final String EVENT_MARKER = "E";
    private static final String NOTE_MARKER = "N";
    private static final String CALENDAR_COMMENT_MARKER = "C";
    private static final String CAPTURE_INBOX_MARKER = "Q";
    public static final String CAPTURED_NOTIFICATION_EVENT_TYPE = "notification_captured";
    private static final String DELETE_MARKER_SUFFIX = ".delete-pending";
    private static final String CLEAR_MARKER_NAME = ".clear-pending";
    private static final String APP_MANAGED_LOG_CLEAR_MARKER_NAME =
            ".app-managed-log-clear-pending";
    private static final String APP_MANAGED_LOG_CLEAR_MARKER =
            "CLEAR-APP-MANAGED-LOGS-1";
    private static final String CAPTURE_CLEAR_MARKER_NAME = ".notification-clear-pending";
    private static final int MAX_PENDING_CAPTURE_COUNT = 512;
    public static final int MAX_STORED_CAPTURE_COUNT = 512;
    public static final int MAX_ARCHIVE_PAGE_RECORDS = 160;
    public static final int MAX_ARCHIVE_PAGE_BYTES = 512 * 1024;
    public static final int MAX_CALENDAR_COMMENT_COUNT = 4_096;
    public static final int MAX_CALENDAR_COMMENT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PERSISTED_LINE_BYTES = 384 * 1024;
    private static final int MAX_PREFIX_LINE_BYTES = 16 * 1024;
    private static final long MAX_LEGACY_SESSION_SNAPSHOT_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_LEGACY_SESSION_LIST_BYTES = 16L * 1024L * 1024L;
    private static final Object PROCESS_STORAGE_LOCK = new Object();
    /** Calendar document rewrites never block foreground current-session appends. */
    private static final Object CALENDAR_COMMENTS_MUTATION_LOCK = new Object();
    private static final Object CAPTURE_ID_CACHE_LOCK = new Object();
    private static final Map<String, Set<String>> CAPTURE_ID_CACHE_BY_ROOT =
            new LinkedHashMap<>();
    /** Durable capture IDs appended while a complete archive scan is in flight. */
    private static final Map<String, Set<String>> CAPTURE_ID_CACHE_PENDING_BY_ROOT =
            new LinkedHashMap<>();
    /** Invalidation epoch prevents an old-file-descriptor scan from publishing stale IDs. */
    private static final Map<String, Long> CAPTURE_ID_CACHE_EPOCH_BY_ROOT =
            new LinkedHashMap<>();
    private static final Set<String> CAPTURE_ID_CACHE_INITIALIZING = new HashSet<>();
    private final File journalRoot;
    private final File sessionsDirectory;
    private final File calendarCommentsFile;
    private final File notificationInboxFile;
    private final Runnable durableMutationObserver;
    private final ScanObserver scanObserver;

    @FunctionalInterface
    interface ScanObserver {
        void onScanStarted(String scanName) throws IOException;
    }

    public enum CaptureDisposition {
        STORED,
        DUPLICATE,
        AT_CAPACITY,
        SESSION_CLOSED
    }

    /** Count plus the earliest bounded summaries retained for notification presentation. */
    public static final class FutureCalendarCommentScan {
        private final int count;
        private final List<CalendarComment> summaries;

        private FutureCalendarCommentScan(int count, List<CalendarComment> summaries) {
            this.count = count;
            this.summaries = Collections.unmodifiableList(new ArrayList<>(summaries));
        }

        public int getCount() {
            return count;
        }

        public List<CalendarComment> getSummaries() {
            return summaries;
        }
    }

    public JournalStore(File filesDirectory) throws IOException {
        this(filesDirectory, null, null);
    }

    /**
     * Opens the private journal and optionally observes completed durable mutations.
     *
     * <p>The observer is advisory and must return quickly. MML uses it only to coalesce an
     * asynchronous user-filespace mirror; observer failure can never roll back or invalidate the
     * already durable private journal.
     */
    public JournalStore(File filesDirectory, Runnable durableMutationObserver) throws IOException {
        this(filesDirectory, durableMutationObserver, null);
    }

    JournalStore(
            File filesDirectory,
            Runnable durableMutationObserver,
            ScanObserver scanObserver) throws IOException {
        this.durableMutationObserver = durableMutationObserver;
        this.scanObserver = scanObserver;
        journalRoot = new File(filesDirectory, "mml-journal");
        sessionsDirectory = new File(journalRoot, "sessions");
        calendarCommentsFile = new File(journalRoot, "calendar-comments");
        notificationInboxFile = new File(journalRoot, "notification-inbox");
        synchronized (PROCESS_STORAGE_LOCK) {
            if (!sessionsDirectory.isDirectory() && !sessionsDirectory.mkdirs()) {
                throw new IOException("Could not create the MML journal directory.");
            }
            completePendingClear();
            completePendingAppManagedLogClear();
            completePendingDeletions();
            completePendingNotificationClear();
            deleteOrphanedReplacementFiles(notificationInboxFile);
            deleteOrphanedReplacementFiles(calendarCommentsFile);
            deleteOrphanedEventReplacementFiles();
        }
        notifyDurableMutation();
    }

    /**
     * Starts exactly one new launcher-activity session.
     *
     * A prior OPEN record may have outlived its Android process, because Android does not
     * guarantee an activity-destruction callback. Observing the next MainActivity creation is
     * enough to archive that prior record, but it is not evidence of a crash or clean exit.
     */
    public synchronized JournalSession startLauncherSession(
            long bootIdentity,
            long now,
            String zoneId) throws IOException {
        return startLauncherSession(bootIdentity, now, zoneId, LogVerbosity.FULL);
    }

    /** Starts a session while applying the selected recording policy to automatic log rows. */
    public synchronized JournalSession startLauncherSession(
            long bootIdentity,
            long now,
            String zoneId,
            LogVerbosity verbosity) throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            LogVerbosity recordingPolicy = verbosity == null ? LogVerbosity.DEFAULT : verbosity;
            for (String sessionId : listOpenSessionIds()) {
                if (recordingPolicy.includesEventType(
                        "later_launcher_startup_observed")) {
                    appendEvent(sessionId, new JournalEvent(
                            now,
                            zoneId,
                            "later_launcher_startup_observed",
                            "A later MML MainActivity startup established the next launcher "
                                    + "session. This boundary does not prove a crash or a clean exit."));
                }
                writeReplace(stateFile(sessionId), SessionStatus.CLOSED.name() + "\n");
            }
            return createSession(bootIdentity, now, zoneId, recordingPolicy);
        }
    }

    /** Legacy boot-journal migration helper retained for records written before MML 1.2.0. */
    public synchronized JournalSession openOrCreate(
            long bootIdentity,
            long now,
            String zoneId) throws IOException {
        List<JournalSession> sessions = listSessionMetadata();
        JournalSession newestOpenForBoot = null;
        for (JournalSession session : sessions) {
            if (session.getStatus() != SessionStatus.OPEN) {
                continue;
            }
            if (session.getBootIdentity() != bootIdentity) {
                markUnclosed(
                        session,
                        now,
                        zoneId,
                        "Closure was not observed before boot identity " + bootIdentity
                                + ". This is not proof of a crash.");
            } else if (newestOpenForBoot == null
                    || session.getStartedEpochMillis() > newestOpenForBoot.getStartedEpochMillis()) {
                newestOpenForBoot = session;
            }
        }

        if (newestOpenForBoot != null) {
            for (JournalSession session : sessions) {
                if (session.getStatus() == SessionStatus.OPEN
                        && session.getBootIdentity() == bootIdentity
                        && !session.getId().equals(newestOpenForBoot.getId())) {
                    markUnclosed(
                            session,
                            now,
                            zoneId,
                            "A later writable journal record superseded this one. "
                                    + "Finalization was not observed.");
                }
            }
            return loadSession(newestOpenForBoot.getId());
        }
        return createSession(bootIdentity, now, zoneId);
    }

    public synchronized JournalSession createSession(
            long bootIdentity,
            long now,
            String zoneId) throws IOException {
        return createSession(bootIdentity, now, zoneId, LogVerbosity.FULL);
    }

    private synchronized JournalSession createSession(
            long bootIdentity,
            long now,
            String zoneId,
            LogVerbosity verbosity) throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            String base = "boot-" + bootIdentity + "-" + now;
            String id = base;
            int suffix = 2;
            while (metaFile(id).exists()) {
                id = base + "-" + suffix;
                suffix++;
            }

            writeReplace(
                    metaFile(id),
                    LineCodec.encode(
                            META_VERSION,
                            Long.toString(bootIdentity),
                            Long.toString(now),
                            zoneId) + "\n");
            writeReplace(stateFile(id), SessionStatus.OPEN.name() + "\n");
            writeReplace(notesFile(id), "");
            if (verbosity.includesEventType("launcher_session_started")) {
                appendEvent(id, new JournalEvent(
                        now,
                        zoneId,
                        "launcher_session_started",
                        "MML established a writable current-session log for this MainActivity "
                                + "startup."));
            }
            JournalSession created = loadSession(id);
            notifyDurableMutation();
            return created;
        }
    }

    public synchronized void appendEvent(String sessionId, JournalEvent event) throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            validateId(sessionId);
            if (event == null) {
                throw new IOException("Missing journal event.");
            }
            JournalTextPolicy.requireWithinLimit(event.getDetail());
            if (!metaFile(sessionId).isFile()) {
                throw new IOException("Unknown journal record: " + sessionId);
            }
            appendEventRows(sessionId, Collections.singletonList(event));
            notifyDurableMutation();
        }
    }

    /** Appends a batch with one flush/fsync; callers hold PROCESS_STORAGE_LOCK. */
    private void appendEventRows(String sessionId, List<JournalEvent> events) throws IOException {
        if (events.isEmpty()) {
            return;
        }
        File target = eventsFile(sessionId);
        boolean needsBoundary = false;
        if (target.isFile() && target.length() > 0L) {
            try (RandomAccessFile existing = new RandomAccessFile(target, "r")) {
                existing.seek(existing.length() - 1L);
                needsBoundary = existing.read() != '\n';
            }
        }
        try (FileOutputStream output = new FileOutputStream(target, true);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     output, StandardCharsets.UTF_8))) {
            if (needsBoundary) {
                // Isolate a crash-torn final row before retrying its still-journaled source item.
                writer.newLine();
            }
            for (JournalEvent event : events) {
                JournalTextPolicy.requireWithinLimit(event.getDetail());
                writer.write(LineCodec.encode(
                        EVENT_MARKER,
                        Long.toString(event.getEpochMillis()),
                        event.getZoneId(),
                        event.getType(),
                        event.getDetail()));
                writer.newLine();
            }
            writer.flush();
            output.getFD().sync();
        } catch (IOException failure) {
            invalidateCapturedNotificationIds();
            throw failure;
        }
        synchronized (CAPTURE_ID_CACHE_LOCK) {
            String cacheKey = captureCacheKey();
            Set<String> cachedIds = CAPTURE_ID_CACHE_BY_ROOT.get(cacheKey);
            if (cachedIds == null && CAPTURE_ID_CACHE_INITIALIZING.contains(cacheKey)) {
                cachedIds = CAPTURE_ID_CACHE_PENDING_BY_ROOT.computeIfAbsent(
                        cacheKey, ignored -> new HashSet<>());
            }
            if (cachedIds != null) {
                for (JournalEvent event : events) {
                    if (!CAPTURED_NOTIFICATION_EVENT_TYPE.equals(event.getType())) {
                        continue;
                    }
                    CapturedNotification capture =
                            CapturedNotification.decodeDetail(event.getDetail());
                    if (capture != null
                            && cachedIds.size() < MAX_STORED_CAPTURE_COUNT) {
                        cachedIds.add(capture.getCaptureId());
                    }
                }
            }
        }
    }

    /** Legacy writer retained for storage compatibility tests; the 1.2 UI never calls it. */
    public synchronized void saveDailyNote(
            String sessionId,
            String localDate,
            long observedAt,
            String zoneId,
            String notes) throws IOException {
        validateId(sessionId);
        JournalTextPolicy.requireWithinLimit(notes);
        if (readStatus(sessionId) != SessionStatus.OPEN) {
            return;
        }
        String partitionId = localDate + "@" + zoneId;
        List<JournalNote> partitions = readNotes(sessionId);
        List<JournalNote> updated = new ArrayList<>();
        boolean replaced = false;
        for (JournalNote partition : partitions) {
            if (partition.getPartitionId().equals(partitionId)) {
                updated.add(new JournalNote(
                        partitionId,
                        localDate,
                        partition.getCreatedEpochMillis(),
                        zoneId,
                        notes == null ? "" : notes));
                replaced = true;
            } else {
                updated.add(partition);
            }
        }
        if (!replaced) {
            updated.add(new JournalNote(
                    partitionId,
                    localDate,
                    observedAt,
                    zoneId,
                    notes == null ? "" : notes));
        }
        updated.sort(Comparator.comparingLong(JournalNote::getCreatedEpochMillis));
        writeNotes(sessionId, updated);
        notifyDurableMutation();
    }

    /**
     * Creates, replaces or removes the one editable Comments document for a Gregorian civil date.
     * The target date is independent of the observation time and of every launcher session.
     */
    public CalendarComment saveCalendarComment(
            String targetLocalDate,
            String targetZoneId,
            long observedAt,
            String observedZoneId,
            String text) throws IOException {
        synchronized (CALENDAR_COMMENTS_MUTATION_LOCK) {
            validateLocalDate(targetLocalDate);
            validateZoneId(targetZoneId);
            validateZoneId(observedZoneId);
            JournalTextPolicy.requireWithinLimit(text);

            List<CalendarComment> comments = readCalendarComments();
            CalendarComment existing = null;
            List<CalendarComment> updated = new ArrayList<>();
            for (CalendarComment comment : comments) {
                if (comment.getTargetLocalDate().equals(targetLocalDate)) {
                    existing = comment;
                } else {
                    updated.add(comment);
                }
            }

            if (CommentText.hasPayload(text)) {
                if (existing == null && comments.size() >= MAX_CALENDAR_COMMENT_COUNT) {
                    throw new IOException(
                            "Calendar Comments reached the supported date count of "
                                    + MAX_CALENDAR_COMMENT_COUNT + ".");
                }
                CalendarComment replacement = existing == null
                        ? new CalendarComment(
                                targetLocalDate,
                                targetZoneId,
                                observedAt,
                                observedZoneId,
                                text == null ? "" : text)
                        : new CalendarComment(
                                existing.getTargetLocalDate(),
                                existing.getTargetZoneId(),
                                existing.getCreatedEpochMillis(),
                                existing.getCreatedZoneId(),
                                text == null ? "" : text);
                updated.add(replacement);
                updated.sort(Comparator.comparing(CalendarComment::getTargetLocalDate));
                writeCalendarComments(updated);
                notifyDurableMutation();
                return replacement;
            }

            updated.sort(Comparator.comparing(CalendarComment::getTargetLocalDate));
            writeCalendarComments(updated);
            notifyDurableMutation();
            return null;
        }
    }

    public List<CalendarComment> listCalendarComments() throws IOException {
        // Atomic replacement means a reader observes either the prior or next complete document.
        return readCalendarComments();
    }

    /** Loads every current future event for the explicit in-app chronological list. */
    public List<CalendarComment> listFutureCalendarComments(long nowEpochMillis)
            throws IOException {
        return FutureEntryPolicy.futureEntries(readCalendarComments(), nowEpochMillis);
    }

    /** Finds one date document while retaining at most one bounded text row. */
    public CalendarComment findCalendarComment(String targetLocalDate)
            throws IOException {
        validateLocalDate(targetLocalDate);
        if (!calendarCommentsFile.isFile()) {
            return null;
        }
        requireBoundedCalendarCommentsFile();
        int physicalRows = 0;
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(calendarCommentsFile), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                if (++physicalRows > MAX_CALENDAR_COMMENT_COUNT * 2) {
                    throw new IOException("Calendar Comments contains too many physical rows.");
                }
                if (boundedLine.isOversized()) {
                    throw new IOException("Calendar Comments contains an oversized row.");
                }
                String line = boundedLine.getRetainedText();
                List<String> prefix = LineCodec.decodePrefix(line, 2);
                if (prefix.size() != 2
                        || !CALENDAR_COMMENT_MARKER.equals(prefix.get(0))
                        || !targetLocalDate.equals(prefix.get(1))) {
                    continue;
                }
                List<String> row = LineCodec.decode(line);
                if (row.size() != 6) {
                    return null;
                }
                try {
                    LocalDate.parse(row.get(1));
                    ZoneId.of(row.get(2));
                    long created = Long.parseLong(row.get(3));
                    ZoneId.of(row.get(4));
                    return new CalendarComment(
                            row.get(1), row.get(2), created, row.get(4), row.get(5));
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public int countCalendarComments() throws IOException {
        if (!calendarCommentsFile.isFile()) {
            return 0;
        }
        requireBoundedCalendarCommentsFile();
        Set<String> dates = new HashSet<>();
        int physicalRows = 0;
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(calendarCommentsFile), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                if (++physicalRows > MAX_CALENDAR_COMMENT_COUNT * 2) {
                    throw new IOException("Calendar Comments contains too many physical rows.");
                }
                if (boundedLine.isOversized()) {
                    throw new IOException("Calendar Comments contains an oversized row.");
                }
                List<String> prefix = LineCodec.decodePrefix(
                        boundedLine.getRetainedText(), 2);
                if (prefix.size() == 2
                        && CALENDAR_COMMENT_MARKER.equals(prefix.get(0))) {
                    try {
                        LocalDate.parse(prefix.get(1));
                        dates.add(prefix.get(1));
                    } catch (RuntimeException ignored) {
                        // One malformed row does not hide later valid date documents.
                    }
                }
            }
        }
        return dates.size();
    }

    /** Streaming future count avoids retaining every Comments body in a foreground cache. */
    public int countFutureCalendarComments(long nowEpochMillis) throws IOException {
        return scanFutureCalendarComments(nowEpochMillis, 0, 0).getCount();
    }

    /**
     * Counts every future event while retaining only compact summaries. The source text remains
     * streaming and bounded even when the calendar document reaches its 16 MiB aggregate limit.
     */
    public FutureCalendarCommentScan scanFutureCalendarComments(
            long nowEpochMillis,
            int maximumSummaryEntries,
            int maximumSummaryTextCodePoints) throws IOException {
        if (maximumSummaryEntries < 0 || maximumSummaryTextCodePoints < 0) {
            throw new IllegalArgumentException("Future summary limits must not be negative.");
        }
        if (!calendarCommentsFile.isFile()) {
            return new FutureCalendarCommentScan(0, Collections.emptyList());
        }
        requireBoundedCalendarCommentsFile();
        Map<String, CalendarComment> futureByDate = new LinkedHashMap<>();
        int physicalRows = 0;
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(calendarCommentsFile), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                if (++physicalRows > MAX_CALENDAR_COMMENT_COUNT * 2) {
                    throw new IOException("Calendar Comments contains too many physical rows.");
                }
                if (boundedLine.isOversized()) {
                    throw new IOException("Calendar Comments contains an oversized row.");
                }
                List<String> row = LineCodec.decode(boundedLine.getRetainedText());
                if (row.size() != 6 || !CALENDAR_COMMENT_MARKER.equals(row.get(0))) {
                    continue;
                }
                try {
                    LocalDate target = LocalDate.parse(row.get(1));
                    ZoneId targetZone = ZoneId.of(row.get(2));
                    long createdEpochMillis = Long.parseLong(row.get(3));
                    ZoneId.of(row.get(4));
                    LocalDate todayAtTarget = Instant.ofEpochMilli(nowEpochMillis)
                            .atZone(targetZone)
                            .toLocalDate();
                    if (CommentText.hasPayload(row.get(5)) && target.isAfter(todayAtTarget)) {
                        String retainedText = maximumSummaryEntries == 0
                                ? ""
                                : FutureEntryPolicy.compactEventText(
                                        row.get(5), maximumSummaryTextCodePoints);
                        futureByDate.put(row.get(1), new CalendarComment(
                                row.get(1),
                                row.get(2),
                                createdEpochMillis,
                                row.get(4),
                                retainedText));
                    } else {
                        // Match readCalendarComments() last-row-wins behavior for damaged imports.
                        futureByDate.remove(row.get(1));
                    }
                } catch (RuntimeException ignored) {
                    // One malformed row must not hide the remaining future-entry count.
                }
            }
        }
        List<CalendarComment> summaries = new ArrayList<>(futureByDate.values());
        summaries.sort(Comparator
                .comparing(CalendarComment::getTargetLocalDate)
                .thenComparing(CalendarComment::getTargetZoneId));
        int count = summaries.size();
        if (summaries.size() > maximumSummaryEntries) {
            summaries = new ArrayList<>(summaries.subList(0, maximumSummaryEntries));
        }
        return new FutureCalendarCommentScan(count, summaries);
    }

    public void clearCalendarComments() throws IOException {
        synchronized (CALENDAR_COMMENTS_MUTATION_LOCK) {
            writeReplace(calendarCommentsFile, "");
            deleteOrphanedReplacementFiles(calendarCommentsFile);
            notifyDurableMutation();
        }
    }

    /**
     * Compatibility wrapper that handles one capture for an OPEN process session. It returns false
     * only when the session is no longer writable; duplicate and at-capacity posts are handled
     * without another append. New listener code uses the explicit disposition method below.
     */
    public boolean appendCapturedNotification(
            String sessionId,
            CapturedNotification capture) throws IOException {
        return appendCapturedNotificationResult(sessionId, capture)
                != CaptureDisposition.SESSION_CLOSED;
    }

    public CaptureDisposition appendCapturedNotificationResult(
            String sessionId,
            CapturedNotification capture) throws IOException {
        Set<String> scannedCaptureIds = capturedNotificationIds();
        synchronized (PROCESS_STORAGE_LOCK) {
            validateId(sessionId);
            if (capture == null) {
                throw new IOException("Missing captured notification.");
            }
            Set<String> knownCaptureIds = mergeCachedCaptureIds(scannedCaptureIds);
            LinkedHashMap<String, CapturedNotification> pending =
                    capturedNotificationInboxById();
            if (knownCaptureIds.contains(capture.getCaptureId())
                    || pending.containsKey(capture.getCaptureId())) {
                return CaptureDisposition.DUPLICATE;
            }
            if (knownCaptureIds.size() + pending.size() >= MAX_STORED_CAPTURE_COUNT) {
                return CaptureDisposition.AT_CAPACITY;
            }
            if (readStatus(sessionId) != SessionStatus.OPEN) {
                return CaptureDisposition.SESSION_CLOSED;
            }
            appendEvent(sessionId, new JournalEvent(
                    capture.getPostedEpochMillis(),
                    capture.getCapturedZoneId(),
                    CAPTURED_NOTIFICATION_EVENT_TYPE,
                    capture.encodeDetail()));
            return CaptureDisposition.STORED;
        }
    }

    /**
     * Stores a capture until a launcher Activity establishes the current process session.
     *
     * The complete, de-duplicated inbox is atomically replaced rather than appended. That makes
     * a power-loss tear isolate the old or new snapshot instead of leaving a partial final row.
     * When the private inbox reaches its bound, its oldest capture is discarded first.
     */
    public void queueCapturedNotification(CapturedNotification capture)
            throws IOException {
        queueCapturedNotificationResult(capture);
    }

    public CaptureDisposition queueCapturedNotificationResult(
            CapturedNotification capture) throws IOException {
        Set<String> scannedCaptureIds = capturedNotificationIds();
        synchronized (PROCESS_STORAGE_LOCK) {
            if (capture == null) {
                throw new IOException("Missing captured notification.");
            }
            Set<String> knownCaptureIds = mergeCachedCaptureIds(scannedCaptureIds);
            if (knownCaptureIds.contains(capture.getCaptureId())) {
                return CaptureDisposition.DUPLICATE;
            }
            LinkedHashMap<String, CapturedNotification> pending =
                    capturedNotificationInboxById();
            if (pending.containsKey(capture.getCaptureId())) {
                return CaptureDisposition.DUPLICATE;
            }
            int available = Math.max(
                    0, MAX_STORED_CAPTURE_COUNT - knownCaptureIds.size());
            int pendingLimit = Math.min(MAX_PENDING_CAPTURE_COUNT, available);
            if (pendingLimit == 0) {
                return CaptureDisposition.AT_CAPACITY;
            }
            pending.put(capture.getCaptureId(), capture);
            while (pending.size() > pendingLimit) {
                String oldestCaptureId = pending.keySet().iterator().next();
                pending.remove(oldestCaptureId);
            }
            writeCapturedNotificationInbox(new ArrayList<>(pending.values()));
            notifyDurableMutation();
            return CaptureDisposition.STORED;
        }
    }

    /**
     * Consolidates a listener rebind snapshot into one dedupe/capacity pass and one durable write.
     * Returns the number newly stored in either the OPEN session or the pending inbox.
     */
    public int storeCapturedNotificationsBatch(
            String sessionId,
            List<CapturedNotification> captures) throws IOException {
        if (captures == null || captures.isEmpty()) {
            return 0;
        }
        Set<String> scannedCaptureIds = capturedNotificationIds();
        synchronized (PROCESS_STORAGE_LOCK) {
            Set<String> knownCaptureIds = mergeCachedCaptureIds(scannedCaptureIds);
            LinkedHashMap<String, CapturedNotification> pending =
                    capturedNotificationInboxById();
            boolean sessionOpen = false;
            if (sessionId != null) {
                validateId(sessionId);
                sessionOpen = metaFile(sessionId).isFile()
                        && readStatus(sessionId) == SessionStatus.OPEN;
            }
            List<JournalEvent> acceptedEvents = new ArrayList<>();
            boolean pendingChanged = false;
            int stored = 0;
            for (CapturedNotification capture : captures) {
                if (capture == null
                        || knownCaptureIds.contains(capture.getCaptureId())
                        || pending.containsKey(capture.getCaptureId())
                        || knownCaptureIds.size() + pending.size()
                                >= MAX_STORED_CAPTURE_COUNT) {
                    continue;
                }
                if (sessionOpen) {
                    knownCaptureIds.add(capture.getCaptureId());
                    acceptedEvents.add(new JournalEvent(
                            capture.getPostedEpochMillis(),
                            capture.getCapturedZoneId(),
                            CAPTURED_NOTIFICATION_EVENT_TYPE,
                            capture.encodeDetail()));
                } else {
                    pending.put(capture.getCaptureId(), capture);
                    pendingChanged = true;
                }
                stored++;
            }
            appendEventRows(sessionId, acceptedEvents);
            if (pendingChanged) {
                writeCapturedNotificationInbox(new ArrayList<>(pending.values()));
            }
            if (stored > 0) {
                notifyDurableMutation();
            }
            return stored;
        }
    }

    /**
     * Moves every valid pending capture into the current OPEN session. Stable capture IDs make a
     * retry after process death idempotent; the inbox is cleared only after every append succeeds.
     */
    public int drainCapturedNotifications(String sessionId) throws IOException {
        Set<String> scannedCaptureIds = capturedNotificationIds();
        synchronized (PROCESS_STORAGE_LOCK) {
            validateId(sessionId);
            if (readStatus(sessionId) != SessionStatus.OPEN) {
                return 0;
            }
            List<CapturedNotification> pending =
                    new ArrayList<>(capturedNotificationInboxById().values());
            boolean inboxFileWasPresent = notificationInboxFile.isFile();
            Set<String> knownCaptureIds = mergeCachedCaptureIds(scannedCaptureIds);
            int appended = 0;
            List<JournalEvent> accepted = new ArrayList<>();
            for (CapturedNotification capture : pending) {
                if (!knownCaptureIds.add(capture.getCaptureId())) {
                    continue;
                }
                if (knownCaptureIds.size() > MAX_STORED_CAPTURE_COUNT) {
                    knownCaptureIds.remove(capture.getCaptureId());
                    continue;
                }
                accepted.add(new JournalEvent(
                        capture.getPostedEpochMillis(),
                        capture.getCapturedZoneId(),
                        CAPTURED_NOTIFICATION_EVENT_TYPE,
                        capture.encodeDetail()));
                appended++;
            }
            appendEventRows(sessionId, accepted);
            if (notificationInboxFile.isFile()) {
                writeReplace(notificationInboxFile, "");
            }
            if (appended > 0 || !pending.isEmpty() || inboxFileWasPresent) {
                notifyDurableMutation();
            }
            return appended;
        }
    }

    public int countCapturedNotifications() throws IOException {
        int count = 0;
        for (String id : recordIdsOnDisk()) {
            count += countCapturedNotificationRows(eventsFile(id));
        }
        if (notificationInboxFile.isFile()) {
            try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                    new FileInputStream(notificationInboxFile), MAX_PERSISTED_LINE_BYTES)) {
                while (reader.readLine() != null) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Explicit privacy redaction for captured notification rows; other session events remain. */
    public synchronized int clearCapturedNotifications() throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            // Establish recovery before any enumeration so malformed or unreadable data cannot
            // make the privacy action unavailable. The count is informational and best-effort.
            writeReplace(captureClearMarkerFile(), "CLEAR-NOTIFICATIONS-1\n");
            int count = 0;
            try {
                count = countCapturedNotifications();
            } catch (IOException ignored) {
                // completePendingNotificationClear still performs conservative raw redaction.
            }
            completePendingNotificationClear();
            notifyDurableMutation();
            return count;
        }
    }

    /** Records a best-effort Android shutdown observation; this is not a user workflow. */
    public synchronized JournalSession markShutdownObserved(
            String sessionId,
            long now,
            String zoneId,
            String reason) throws IOException {
        return markShutdownObserved(
                sessionId, now, zoneId, reason, LogVerbosity.FULL);
    }

    /** Closes the session even when the selected policy omits its automatic shutdown row. */
    public synchronized JournalSession markShutdownObserved(
            String sessionId,
            long now,
            String zoneId,
            String reason,
            LogVerbosity verbosity) throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            JournalSession session = readSessionMetadata(sessionId);
            if (session.getStatus() != SessionStatus.OPEN) {
                return loadSessionWindow(sessionId, 160, 512 * 1024).getSession();
            }
            LogVerbosity recordingPolicy = verbosity == null ? LogVerbosity.DEFAULT : verbosity;
            if (recordingPolicy.includesEventType("shutdown_observed")) {
                appendEvent(sessionId, new JournalEvent(
                        now, zoneId, "shutdown_observed", reason));
            }
            writeReplace(stateFile(sessionId), SessionStatus.CLOSED.name() + "\n");
            notifyDurableMutation();
            return loadSessionWindow(sessionId, 160, 512 * 1024).getSession();
        }
    }

    /**
     * Counts archived records whose newest durable timestamp is strictly before the cutoff.
     * The active process session is identified by {@code preservedSessionId} and never selected.
     */
    public int countSessionsOlderThan(
            long cutoffEpochMillis,
            String preservedSessionId) throws IOException {
        observeScan("retention-count");
        int count = 0;
        for (String id : recordIdsOnDisk()) {
            if (id.equals(preservedSessionId)) {
                continue;
            }
            try {
                RetentionRecord record = loadRetentionRecord(id);
                if (record.status != SessionStatus.OPEN
                        && record.newestDurableTimestamp < cutoffEpochMillis) {
                    count++;
                }
            } catch (IOException ignored) {
                // Match historical behavior: unreadable records are omitted from age selection.
            }
        }
        return count;
    }

    /**
     * Durably schedules and removes every selected record file, including events and notes.
     * An interrupted deletion is completed when JournalStore is opened again.
     */
    public int deleteSessionsOlderThan(
            long cutoffEpochMillis,
            String preservedSessionId) throws IOException {
        List<RetentionCandidate> candidates = new ArrayList<>();
        for (String id : recordIdsOnDisk()) {
            if (id.equals(preservedSessionId)) {
                continue;
            }
            try {
                RetentionFileState before = retentionFileState(id);
                RetentionRecord record = loadRetentionRecord(id);
                RetentionFileState after = retentionFileState(id);
                if (record.status != SessionStatus.OPEN
                        && record.newestDurableTimestamp < cutoffEpochMillis
                        && before.equals(after)) {
                    candidates.add(new RetentionCandidate(id, after));
                }
            } catch (IOException ignored) {
                // An unreadable record is never age-selected without explicit Clear History.
            }
        }
        int deleted = 0;
        for (RetentionCandidate candidate : candidates) {
            throwIfHistoryScanInterrupted();
            synchronized (PROCESS_STORAGE_LOCK) {
                if (candidate.id.equals(preservedSessionId)
                        || !metaFile(candidate.id).isFile()
                        || !candidate.fileState.equals(retentionFileState(candidate.id))) {
                    continue;
                }
                try {
                    if (readSessionMetadata(candidate.id).getStatus() == SessionStatus.OPEN) {
                        continue;
                    }
                } catch (IOException ignored) {
                    // Age deletion never removes a record that became unreadable after selection.
                    continue;
                }
                deleteSession(candidate.id);
                deleted++;
            }
        }
        if (deleted > 0) {
            notifyDurableMutation();
        }
        return deleted;
    }

    private RetentionFileState retentionFileState(String id) {
        return new RetentionFileState(
                FileState.capture(metaFile(id)),
                FileState.capture(stateFile(id)),
                FileState.capture(eventsFile(id)),
                FileState.capture(notesFile(id)),
                FileState.capture(deleteMarkerFile(id)));
    }

    /** Removes all journal record files. The caller must immediately establish a fresh record. */
    public synchronized void clearAllSessions() throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            writeReplace(clearMarkerFile(), "CLEAR1\n");
            completePendingClear();
            notifyDurableMutation();
        }
    }

    /**
     * Clears all app-managed session and captured-notification logs while preserving the current
     * OPEN session identity and the separate Calendar Comments document.
     *
     * <p>A durable marker names the session whose metadata and OPEN state must survive. Recovery
     * repeats the operation after process death. If that named session is already damaged or
     * missing, recovery conservatively clears every session record; MainActivity then establishes
     * a replacement writable session because the original identity can no longer be preserved.
     */
    public synchronized int clearAppManagedLogs(String preservedSessionId) throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            validateId(preservedSessionId);
            if (!metaFile(preservedSessionId).isFile()
                    || !stateFile(preservedSessionId).isFile()
                    || readSessionMetadata(preservedSessionId).getStatus() != SessionStatus.OPEN) {
                throw new IOException("The Current Session is not an open journal record.");
            }
            int affectedSessionCount = recordIdsOnDisk().size();
            writeReplace(
                    appManagedLogClearMarkerFile(),
                    LineCodec.encode(
                            APP_MANAGED_LOG_CLEAR_MARKER,
                            preservedSessionId) + "\n");
            completePendingAppManagedLogClear();
            notifyDurableMutation();
            return affectedSessionCount;
        }
    }

    /** Deletes archived sessions while preserving the one OPEN current session. */
    public int clearArchivedSessions(String preservedSessionId) throws IOException {
        List<String> candidates = new ArrayList<>();
        for (String id : recordIdsOnDisk()) {
            if (id.equals(preservedSessionId)) {
                continue;
            }
            boolean archivedOrUnreadable;
            try {
                archivedOrUnreadable = readSessionMetadata(id).getStatus()
                        != SessionStatus.OPEN;
            } catch (IOException error) {
                // Clear History must not leave an unreadable record hidden in private storage.
                archivedOrUnreadable = true;
            }
            if (archivedOrUnreadable) {
                candidates.add(id);
            }
        }
        int deleted = 0;
        for (String candidate : candidates) {
            throwIfHistoryScanInterrupted();
            synchronized (PROCESS_STORAGE_LOCK) {
                if (candidate.equals(preservedSessionId)) {
                    continue;
                }
                boolean archivedOrUnreadable;
                try {
                    archivedOrUnreadable = !metaFile(candidate).isFile()
                            || readSessionMetadata(candidate).getStatus() != SessionStatus.OPEN;
                } catch (IOException ignored) {
                    archivedOrUnreadable = true;
                }
                if (!archivedOrUnreadable) {
                    continue;
                }
                deleteSession(candidate);
                deleted++;
            }
        }
        if (deleted > 0) {
            notifyDurableMutation();
        }
        return deleted;
    }

    private List<String> recordIdsOnDisk() throws IOException {
        File[] files = sessionsDirectory.listFiles();
        if (files == null) {
            throw new IOException("Could not enumerate journal records.");
        }
        String[] suffixes = {".meta", ".events", ".notes", ".state"};
        Set<String> ids = new LinkedHashSet<>();
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            for (String suffix : suffixes) {
                if (file.getName().endsWith(suffix)) {
                    String id = file.getName().substring(
                            0, file.getName().length() - suffix.length());
                    if (id.matches("[A-Za-z0-9._-]+")) {
                        ids.add(id);
                    }
                    break;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /** Finds stale writable records without materializing every archived event log. */
    private List<String> listOpenSessionIds() throws IOException {
        List<String> result = new ArrayList<>();
        File[] metadataFiles = sessionsDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".meta"));
        if (metadataFiles == null) {
            throw new IOException("Could not enumerate journal records.");
        }
        for (File metadataFile : metadataFiles) {
            String name = metadataFile.getName();
            String id = name.substring(0, name.length() - ".meta".length());
            if (!id.matches("[A-Za-z0-9._-]+") || deleteMarkerFile(id).isFile()) {
                continue;
            }
            try {
                List<String> metadata = readFirstRow(metadataFile);
                if (metadata.size() == 4
                        && META_VERSION.equals(metadata.get(0))
                        && readStatus(id) == SessionStatus.OPEN) {
                    parseLong(metadata.get(1), "boot identity");
                    parseLong(metadata.get(2), "start timestamp");
                    result.add(id);
                }
            } catch (IOException ignored) {
                // Match listSessions: one damaged record must not hide the usable journal.
            }
        }
        return result;
    }

    /** Metadata-only compatibility scan for migration paths that need status and boot identity. */
    private List<JournalSession> listSessionMetadata() throws IOException {
        List<JournalSession> result = new ArrayList<>();
        File[] metadataFiles = sessionsDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".meta"));
        if (metadataFiles == null) {
            throw new IOException("Could not enumerate journal records.");
        }
        for (File metadataFile : metadataFiles) {
            String name = metadataFile.getName();
            String id = name.substring(0, name.length() - ".meta".length());
            if (!id.matches("[A-Za-z0-9._-]+") || deleteMarkerFile(id).isFile()) {
                continue;
            }
            try {
                result.add(readSessionMetadata(id));
            } catch (IOException ignored) {
                // One damaged record must not hide other usable migration metadata.
            }
        }
        result.sort(Comparator.comparingLong(JournalSession::getStartedEpochMillis));
        return result;
    }

    private RetentionRecord loadRetentionRecord(String id) throws IOException {
        JournalSession metadata = readSessionMetadata(id);
        long newest = metadata.getStartedEpochMillis();
        if (metadata.getStatus() != SessionStatus.OPEN) {
            newest = newestRetentionEventTimestamp(id, newest);
            newest = newestRetentionNoteTimestamp(id, newest);
        }
        return new RetentionRecord(id, metadata.getStatus(), newest);
    }

    private long newestRetentionEventTimestamp(String id, long newest) throws IOException {
        File file = eventsFile(id);
        if (!file.isFile()) {
            return newest;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PREFIX_LINE_BYTES)) {
            BoundedUtf8LineReader.Line line;
            while ((line = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                List<String> prefix = LineCodec.decodePrefix(line.getRetainedText(), 4);
                if (prefix.size() != 4
                        || !EVENT_MARKER.equals(prefix.get(0))
                        || isAdministrativeBoundaryType(prefix.get(3))) {
                    continue;
                }
                try {
                    newest = Math.max(newest, Long.parseLong(prefix.get(1)));
                } catch (NumberFormatException ignored) {
                    // Skip only the malformed event timestamp.
                }
            }
        }
        return newest;
    }

    private long newestRetentionNoteTimestamp(String id, long newest) throws IOException {
        File file = notesFile(id);
        if (!file.isFile()) {
            return newest;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PREFIX_LINE_BYTES)) {
            BoundedUtf8LineReader.Line line;
            while ((line = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                List<String> prefix = LineCodec.decodePrefix(line.getRetainedText(), 5);
                if (prefix.size() != 5 || !NOTE_MARKER.equals(prefix.get(0))) {
                    continue;
                }
                try {
                    newest = Math.max(newest, Long.parseLong(prefix.get(3)));
                } catch (NumberFormatException ignored) {
                    // Skip only the malformed legacy-Comment timestamp.
                }
            }
        }
        return newest;
    }

    static long newestDurableTimestamp(JournalSession session) {
        long newest = session.getStartedEpochMillis();
        for (JournalEvent event : session.getEvents()) {
            if (!isAdministrativeBoundaryType(event.getType())) {
                newest = Math.max(newest, event.getEpochMillis());
            }
        }
        for (JournalNote note : session.getDailyNotes()) {
            newest = Math.max(newest, note.getCreatedEpochMillis());
        }
        return newest;
    }

    /** Next-start observations are metadata about closure, not activity within the old session. */
    static boolean isAdministrativeBoundaryType(String type) {
        return "later_launcher_startup_observed".equals(type)
                || "prior_boot_boundary_inferred".equals(type)
                || "session_unclosed".equals(type);
    }

    public JournalSession loadSession(String id) throws IOException {
        return readSessionSnapshot(id);
    }

    /** Checks writable state without allocating event or legacy-note text. */
    public synchronized boolean isSessionOpen(String id) throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            validateId(id);
            return metaFile(id).isFile() && readStatus(id) == SessionStatus.OPEN;
        }
    }

    /** Loads only a bounded recent-event window for the long-lived Current Session UI. */
    public synchronized JournalSessionWindow loadSessionWindow(
            String id,
            int maximumEvents,
            int maximumEncodedBytes) throws IOException {
        synchronized (PROCESS_STORAGE_LOCK) {
            if (maximumEvents < 1 || maximumEncodedBytes < 1) {
                throw new IOException("Current-session window bounds must be positive.");
            }
            JournalSession metadata = readSessionMetadata(id);
            TailEvents tail = readTailEvents(id, maximumEvents, maximumEncodedBytes);
            JournalSession window = new JournalSession(
                    metadata.getId(),
                    metadata.getBootIdentity(),
                    metadata.getStartedEpochMillis(),
                    metadata.getStartedZoneId(),
                    metadata.getStatus(),
                    tail.events,
                    Collections.emptyList());
            return new JournalSessionWindow(window, tail.earlierEventsOmitted);
        }
    }

    /**
     * Reads one immutable archived transcript without holding this store instance's monitor.
     * Closed record files are never appended; deletion and privacy redaction use marker/atomic
     * replacement protocols, so a concurrent mutation yields either one complete snapshot or an
     * ordinary read failure without blocking foreground current-session writes.
     */
    public JournalSession loadArchivedSessionSnapshot(String id) throws IOException {
        validateId(id);
        if (deleteMarkerFile(id).isFile()) {
            throw new IOException("Journal record is being deleted: " + id);
        }
        JournalSession session = readSessionSnapshot(id);
        if (deleteMarkerFile(id).isFile()) {
            throw new IOException("Journal record was deleted while loading: " + id);
        }
        if (session.getStatus() == SessionStatus.OPEN) {
            throw new IOException("Journal record is still open: " + id);
        }
        return session;
    }

    /**
     * Loads one bounded archive page without materializing the complete transcript.
     *
     * <p>Paging is by physical UTF-8 rows so malformed or oversized legacy rows cannot cause an
     * allocation spike or make progress stall. Skipped rows are reported on the returned page;
     * the durable source file is never modified.
     */
    public JournalArchivePage loadArchivedSessionPage(
            String id,
            JournalArchiveCursor requestedCursor) throws IOException {
        return loadArchivedSessionPage(
                id,
                requestedCursor,
                MAX_ARCHIVE_PAGE_RECORDS,
                MAX_ARCHIVE_PAGE_BYTES);
    }

    public JournalArchivePage loadArchivedSessionPage(
            String id,
            JournalArchiveCursor requestedCursor,
            int maximumRecords,
            int maximumEncodedBytes) throws IOException {
        validateId(id);
        if (maximumRecords < 1 || maximumRecords > MAX_ARCHIVE_PAGE_RECORDS
                || maximumEncodedBytes < 1
                || maximumEncodedBytes > MAX_ARCHIVE_PAGE_BYTES) {
            throw new IOException("Archive page bounds exceed MML's supported limits.");
        }
        if (deleteMarkerFile(id).isFile()) {
            throw new IOException("Journal record is being deleted: " + id);
        }
        JournalSession metadata = readSessionMetadata(id);
        if (metadata.getStatus() == SessionStatus.OPEN) {
            throw new IOException("Journal record is still open: " + id);
        }

        JournalArchiveCursor cursor = requestedCursor == null
                ? JournalArchiveCursor.first() : requestedCursor;
        File source = cursor.getSection() == JournalArchiveCursor.Section.EVENTS
                ? eventsFile(id) : notesFile(id);
        long sourceLength = source.isFile() ? source.length() : 0L;
        if (cursor.getOffset() > sourceLength) {
            throw new IOException("Archived transcript cursor is past the durable file.");
        }
        if (cursor.getSection() == JournalArchiveCursor.Section.EVENTS
                && cursor.getOffset() == sourceLength
                && notesFile(id).isFile()
                && notesFile(id).length() > 0L) {
            cursor = new JournalArchiveCursor(JournalArchiveCursor.Section.LEGACY_NOTES, 0L);
            source = notesFile(id);
            sourceLength = source.length();
        }
        validateArchiveCursorBoundary(source, cursor.getOffset());

        List<JournalEvent> events = new ArrayList<>();
        List<JournalNote> notes = new ArrayList<>();
        int oversized = 0;
        int malformed = 0;
        int physicalRows = 0;
        long pageStart = cursor.getOffset();
        long pageEnd = pageStart;
        if (source.isFile() && pageStart < sourceLength) {
            try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
                input.seek(pageStart);
                while (input.getFilePointer() < sourceLength
                        && physicalRows < maximumRecords) {
                    throwIfHistoryScanInterrupted();
                    long rowStart = input.getFilePointer();
                    BoundedUtf8LineReader.Line line = BoundedUtf8LineReader.readLine(
                            input, MAX_PERSISTED_LINE_BYTES);
                    if (line == null) {
                        break;
                    }
                    long rowEnd = input.getFilePointer();
                    if (physicalRows > 0
                            && rowEnd - pageStart > maximumEncodedBytes) {
                        input.seek(rowStart);
                        break;
                    }
                    physicalRows++;
                    pageEnd = rowEnd;
                    if (line.isOversized()) {
                        oversized++;
                    } else if (cursor.getSection() == JournalArchiveCursor.Section.EVENTS) {
                        JournalEvent event = decodeArchivedEvent(line.getRetainedText());
                        if (event == null) {
                            malformed++;
                        } else {
                            events.add(event);
                        }
                    } else {
                        JournalNote note = decodeArchivedNote(line.getRetainedText());
                        if (note == null) {
                            malformed++;
                        } else {
                            notes.add(note);
                        }
                    }
                    if (rowEnd - pageStart >= maximumEncodedBytes) {
                        break;
                    }
                }
            }
        }

        JournalArchiveCursor previous = previousArchiveCursor(
                id, cursor, maximumRecords, maximumEncodedBytes);
        JournalArchiveCursor next = null;
        if (pageEnd < sourceLength) {
            next = new JournalArchiveCursor(cursor.getSection(), pageEnd);
        } else if (cursor.getSection() == JournalArchiveCursor.Section.EVENTS
                && notesFile(id).isFile()
                && notesFile(id).length() > 0L) {
            next = new JournalArchiveCursor(JournalArchiveCursor.Section.LEGACY_NOTES, 0L);
        }
        if (deleteMarkerFile(id).isFile()) {
            throw new IOException("Journal record was deleted while loading: " + id);
        }
        return new JournalArchivePage(
                metadata,
                cursor,
                previous,
                next,
                events,
                notes,
                oversized,
                malformed);
    }

    private JournalArchiveCursor previousArchiveCursor(
            String id,
            JournalArchiveCursor cursor,
            int maximumRecords,
            int maximumEncodedBytes) throws IOException {
        if (cursor.getOffset() > 0L) {
            File source = cursor.getSection() == JournalArchiveCursor.Section.EVENTS
                    ? eventsFile(id) : notesFile(id);
            return new JournalArchiveCursor(
                    cursor.getSection(),
                    previousArchivePageStart(
                            source,
                            cursor.getOffset(),
                            maximumRecords,
                            maximumEncodedBytes));
        }
        if (cursor.getSection() == JournalArchiveCursor.Section.LEGACY_NOTES) {
            File events = eventsFile(id);
            if (events.isFile() && events.length() > 0L) {
                return new JournalArchiveCursor(
                        JournalArchiveCursor.Section.EVENTS,
                        previousArchivePageStart(
                                events,
                                events.length(),
                                maximumRecords,
                                maximumEncodedBytes));
            }
        }
        return null;
    }

    private static long previousArchivePageStart(
            File source,
            long pageEnd,
            int maximumRecords,
            int maximumEncodedBytes) throws IOException {
        if (!source.isFile() || pageEnd <= 0L) {
            return 0L;
        }
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            long boundedEnd = Math.min(pageEnd, input.length());
            long candidateStart = boundedEnd;
            int rows = 0;
            long position = boundedEnd;
            byte[] buffer = new byte[8_192];
            boolean trailingDelimiterIgnored = false;
            while (position > 0L) {
                throwIfHistoryScanInterrupted();
                long blockStart = Math.max(0L, position - buffer.length);
                int count = (int) (position - blockStart);
                input.seek(blockStart);
                input.readFully(buffer, 0, count);
                for (int index = count - 1; index >= 0; index--) {
                    long absolute = blockStart + index;
                    if (buffer[index] != '\n') {
                        continue;
                    }
                    if (!trailingDelimiterIgnored && absolute == boundedEnd - 1L) {
                        trailingDelimiterIgnored = true;
                        continue;
                    }
                    long proposedStart = absolute + 1L;
                    if (proposedStart >= candidateStart) {
                        continue;
                    }
                    int proposedRows = rows + 1;
                    long proposedBytes = boundedEnd - proposedStart;
                    if (rows > 0 && (proposedRows > maximumRecords
                            || proposedBytes > maximumEncodedBytes)) {
                        return candidateStart;
                    }
                    candidateStart = proposedStart;
                    rows = proposedRows;
                    if (rows >= maximumRecords || proposedBytes >= maximumEncodedBytes) {
                        return candidateStart;
                    }
                }
                position = blockStart;
            }
            if (candidateStart == 0L) {
                return 0L;
            }
            int proposedRows = rows + 1;
            long proposedBytes = boundedEnd;
            if (rows > 0 && (proposedRows > maximumRecords
                    || proposedBytes > maximumEncodedBytes)) {
                return candidateStart;
            }
            return 0L;
        }
    }

    private static void validateArchiveCursorBoundary(File source, long offset)
            throws IOException {
        if (offset == 0L || !source.isFile()) {
            return;
        }
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            if (offset > input.length()) {
                throw new IOException("Archive cursor exceeds its durable file.");
            }
            input.seek(offset - 1L);
            if (input.read() != '\n') {
                throw new IOException("Archive cursor is not at a durable row boundary.");
            }
        }
    }

    private static JournalEvent decodeArchivedEvent(String line) {
        List<String> row = LineCodec.decode(line);
        if (row.size() != 5 || !EVENT_MARKER.equals(row.get(0))) {
            return null;
        }
        try {
            return new JournalEvent(
                    Long.parseLong(row.get(1)), row.get(2), row.get(3), row.get(4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static JournalNote decodeArchivedNote(String line) {
        List<String> row = LineCodec.decode(line);
        if (row.size() != 6 || !NOTE_MARKER.equals(row.get(0))) {
            return null;
        }
        try {
            return new JournalNote(
                    row.get(1),
                    row.get(2),
                    Long.parseLong(row.get(3)),
                    row.get(4),
                    row.get(5));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private JournalSession readSessionSnapshot(String id) throws IOException {
        long snapshotBytes = (eventsFile(id).isFile() ? eventsFile(id).length() : 0L)
                + (notesFile(id).isFile() ? notesFile(id).length() : 0L);
        if (snapshotBytes > MAX_LEGACY_SESSION_SNAPSHOT_BYTES) {
            throw new IOException(
                    "Journal record is too large for a whole-session snapshot; use archive paging.");
        }
        JournalSession metadata = readSessionMetadata(id);
        return new JournalSession(
                id,
                metadata.getBootIdentity(),
                metadata.getStartedEpochMillis(),
                metadata.getStartedZoneId(),
                metadata.getStatus(),
                readEvents(id),
                readNotes(id));
    }

    private JournalSession readSessionMetadata(String id) throws IOException {
        validateId(id);
        List<String> metadata = readFirstRow(metaFile(id));
        if (metadata.size() != 4 || !META_VERSION.equals(metadata.get(0))) {
            throw new IOException("Invalid MML journal metadata: " + id);
        }
        return new JournalSession(
                id,
                parseLong(metadata.get(1), "boot identity"),
                parseLong(metadata.get(2), "start timestamp"),
                metadata.get(3),
                readStatus(id),
                Collections.emptyList(),
                Collections.emptyList());
    }

    public List<JournalSession> listSessions() throws IOException {
        List<JournalSession> result = new ArrayList<>();
        long cumulativeSnapshotBytes = 0L;
        File[] metadataFiles = sessionsDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".meta"));
        if (metadataFiles == null) {
            return result;
        }
        for (File metadataFile : metadataFiles) {
            String name = metadataFile.getName();
            String id = name.substring(0, name.length() - ".meta".length());
            if (deleteMarkerFile(id).isFile()) {
                continue;
            }
            cumulativeSnapshotBytes += (eventsFile(id).isFile()
                    ? eventsFile(id).length() : 0L)
                    + (notesFile(id).isFile() ? notesFile(id).length() : 0L);
            if (cumulativeSnapshotBytes > MAX_LEGACY_SESSION_LIST_BYTES) {
                throw new IOException(
                        "Journal history is too large for a whole-session list; "
                                + "use metadata or archive paging.");
            }
            try {
                result.add(loadSession(id));
            } catch (IOException ignored) {
                // One damaged record must not hide all other calendar entries.
            }
        }
        result.sort(Comparator.comparingLong(JournalSession::getStartedEpochMillis));
        return result;
    }

    /**
     * Builds compact calendar membership without materializing event details or legacy Comment
     * text. The scan is intended for a background worker; its retained result is bounded by unique
    * session-day/timezone pairs rather than total transcript rows or bytes.
     */
    public List<JournalSessionIndex> listCalendarSessionIndex() throws IOException {
        List<JournalSessionIndex> result = new ArrayList<>();
        File[] metadataFiles = sessionsDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".meta"));
        if (metadataFiles == null) {
            throw new IOException("Could not enumerate journal records.");
        }
        for (File metadataFile : metadataFiles) {
            throwIfHistoryScanInterrupted();
            String name = metadataFile.getName();
            String id = name.substring(0, name.length() - ".meta".length());
            if (!id.matches("[A-Za-z0-9._-]+") || deleteMarkerFile(id).isFile()) {
                continue;
            }
            try {
                result.add(loadCalendarSessionIndex(id));
            } catch (IOException error) {
                throwIfHistoryScanInterrupted();
                // Match listSessions: one damaged record must not hide the usable journal.
            }
        }
        result.sort(Comparator.comparingLong(JournalSessionIndex::getStartedEpochMillis));
        return Collections.unmodifiableList(result);
    }

    private JournalSessionIndex loadCalendarSessionIndex(String id) throws IOException {
        List<String> metadata = readFirstRow(metaFile(id));
        if (metadata.size() != 4 || !META_VERSION.equals(metadata.get(0))) {
            throw new IOException("Invalid MML journal metadata: " + id);
        }
        parseLong(metadata.get(1), "boot identity");
        long started = parseLong(metadata.get(2), "start timestamp");
        String startedZoneId = metadata.get(3);
        SessionStatus status = readStatus(id);
        Map<String, JournalSessionIndex.DateObservation> observations = new LinkedHashMap<>();
        addCalendarObservation(observations, started, startedZoneId);
        if (status != SessionStatus.OPEN) {
            readEventCalendarObservations(id, observations);
            readNoteCalendarObservations(id, observations);
        }
        return new JournalSessionIndex(
                id,
                started,
                status,
                new ArrayList<>(observations.values()));
    }

    private void readEventCalendarObservations(
            String id,
            Map<String, JournalSessionIndex.DateObservation> observations) throws IOException {
        File file = eventsFile(id);
        if (!file.isFile()) {
            return;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PREFIX_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                String line = boundedLine.getRetainedText();
                if (LineCodec.encodedFieldCount(line) != 5) {
                    continue;
                }
                List<String> prefix = LineCodec.decodePrefix(line, 4);
                if (prefix.size() != 4
                        || !EVENT_MARKER.equals(prefix.get(0))
                        || isAdministrativeBoundaryType(prefix.get(3))) {
                    continue;
                }
                try {
                    addCalendarObservation(
                            observations, Long.parseLong(prefix.get(1)), prefix.get(2));
                } catch (NumberFormatException ignored) {
                    // Skip only the malformed event timestamp.
                }
            }
        }
    }

    private void readNoteCalendarObservations(
            String id,
            Map<String, JournalSessionIndex.DateObservation> observations) throws IOException {
        File file = notesFile(id);
        if (!file.isFile()) {
            return;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PREFIX_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                String line = boundedLine.getRetainedText();
                if (LineCodec.encodedFieldCount(line) != 6) {
                    continue;
                }
                List<String> prefix = LineCodec.decodePrefix(line, 5);
                if (prefix.size() != 5 || !NOTE_MARKER.equals(prefix.get(0))) {
                    continue;
                }
                try {
                    addCalendarObservation(
                            observations, Long.parseLong(prefix.get(3)), prefix.get(4));
                } catch (NumberFormatException ignored) {
                    // Skip only the malformed legacy-Comment timestamp.
                }
            }
        }
    }

    private static void addCalendarObservation(
            Map<String, JournalSessionIndex.DateObservation> observations,
            long epochMillis,
            String zoneId) {
        String civilDate;
        try {
            civilDate = Instant.ofEpochMilli(epochMillis)
                    .atZone(ZoneId.of(zoneId))
                    .toLocalDate()
                    .toString();
        } catch (DateTimeException error) {
            // Android ICU projects an unknown timezone as GMT; use UTC only for the dedupe key.
            civilDate = Instant.ofEpochMilli(epochMillis)
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDate()
                    .toString();
        }
        observations.putIfAbsent(
                zoneId + '\n' + civilDate,
                new JournalSessionIndex.DateObservation(epochMillis, zoneId));
    }

    private static void throwIfHistoryScanInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Calendar history scan was canceled.");
        }
    }

    private void markUnclosed(
            JournalSession session,
            long now,
            String zoneId,
            String reason) throws IOException {
        if (session.getStatus() != SessionStatus.OPEN) {
            return;
        }
        appendEvent(session.getId(), new JournalEvent(
                now, zoneId, "prior_boot_boundary_inferred", reason));
        writeReplace(stateFile(session.getId()), SessionStatus.UNCLOSED.name() + "\n");
        notifyDurableMutation();
    }

    private void deleteSession(String id) throws IOException {
        validateId(id);
        writeReplace(deleteMarkerFile(id), "DELETE1\n");
        deleteSessionFiles(id);
        deleteFileIfPresent(deleteMarkerFile(id));
    }

    private void completePendingDeletions() throws IOException {
        File[] markers = sessionsDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(DELETE_MARKER_SUFFIX));
        if (markers == null) {
            return;
        }
        for (File marker : markers) {
            String name = marker.getName();
            String id = name.substring(0, name.length() - DELETE_MARKER_SUFFIX.length());
            validateId(id);
            deleteSessionFiles(id);
            deleteFileIfPresent(marker);
        }
    }

    private void completePendingClear() throws IOException {
        File marker = clearMarkerFile();
        if (!marker.isFile()) {
            return;
        }
        File[] files = sessionsDirectory.listFiles();
        if (files == null) {
            throw new IOException("Could not enumerate journal records during clear.");
        }
        for (File file : files) {
            if (!file.equals(marker)) {
                deleteFileIfPresent(file);
            }
        }
        deleteFileIfPresent(marker);
        invalidateCapturedNotificationIds();
    }

    private void completePendingAppManagedLogClear() throws IOException {
        File marker = appManagedLogClearMarkerFile();
        if (!marker.isFile()) {
            return;
        }

        String preservedSessionId = null;
        try {
            List<String> markerRow = readFirstRow(marker);
            if (markerRow.size() == 2
                    && APP_MANAGED_LOG_CLEAR_MARKER.equals(markerRow.get(0))) {
                String candidate = markerRow.get(1);
                validateId(candidate);
                if (metaFile(candidate).isFile()
                        && stateFile(candidate).isFile()
                        && readSessionMetadata(candidate).getStatus() == SessionStatus.OPEN) {
                    preservedSessionId = candidate;
                }
            }
        } catch (IOException ignored) {
            // The durable marker still authorizes a conservative complete clear. A damaged marker
            // must never leave a mixture of pre-clear and post-clear session logs.
        }

        File[] files = sessionsDirectory.listFiles();
        if (files == null) {
            throw new IOException("Could not enumerate journal records during app-managed clear.");
        }
        String preservedMeta = preservedSessionId == null
                ? "" : metaFile(preservedSessionId).getName();
        String preservedState = preservedSessionId == null
                ? "" : stateFile(preservedSessionId).getName();
        String preservedEvents = preservedSessionId == null
                ? "" : eventsFile(preservedSessionId).getName();
        String preservedNotes = preservedSessionId == null
                ? "" : notesFile(preservedSessionId).getName();
        for (File file : files) {
            if (file.equals(marker)) {
                continue;
            }
            String name = file.getName();
            if (preservedSessionId != null
                    && (name.equals(preservedMeta)
                    || name.equals(preservedState)
                    || name.equals(preservedEvents)
                    || name.equals(preservedNotes))) {
                continue;
            }
            deleteFileIfPresent(file);
        }

        if (preservedSessionId != null) {
            writeReplace(eventsFile(preservedSessionId), "");
            writeReplace(notesFile(preservedSessionId), "");
            writeReplace(stateFile(preservedSessionId), SessionStatus.OPEN.name() + "\n");
            deleteOrphanedReplacementFiles(eventsFile(preservedSessionId));
            deleteOrphanedReplacementFiles(notesFile(preservedSessionId));
            deleteOrphanedReplacementFiles(stateFile(preservedSessionId));
            deleteOrphanedReplacementFiles(metaFile(preservedSessionId));
        }

        deleteFileIfPresent(notificationInboxFile);
        deleteOrphanedReplacementFiles(notificationInboxFile);
        deleteOrphanedEventReplacementFiles();
        deleteFileIfPresent(captureClearMarkerFile());
        invalidateCapturedNotificationIds();
        deleteFileIfPresent(marker);
    }

    private void completePendingNotificationClear() throws IOException {
        File marker = captureClearMarkerFile();
        if (!marker.isFile()) {
            return;
        }
        for (String id : recordIdsOnDisk()) {
            removeCapturedNotificationRows(eventsFile(id));
        }
        if (notificationInboxFile.isFile()) {
            writeReplace(notificationInboxFile, "");
        }
        deleteOrphanedReplacementFiles(notificationInboxFile);
        deleteOrphanedEventReplacementFiles();
        deleteFileIfPresent(marker);
        invalidateCapturedNotificationIds();
    }

    private static void removeCapturedNotificationRows(File file) throws IOException {
        if (!file.isFile()) {
            return;
        }
        File temporary = File.createTempFile(file.getName(), ".tmp", file.getParentFile());
        boolean moved = false;
        try {
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
                 FileOutputStream fileOutput = new FileOutputStream(temporary);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
                copyWithoutCapturedNotificationRows(input, output);
                output.flush();
                fileOutput.getFD().sync();
            }
            moveReplacement(temporary, file);
            moved = true;
        } finally {
            if (!moved && temporary.exists()) {
                temporary.delete();
            }
        }
    }

    /**
     * Streams raw physical rows. A captured prefix is discarded; an overlong/unknown prefix is
     * conservatively preserved byte-for-byte without materializing the complete row.
     */
    private static void copyWithoutCapturedNotificationRows(
            BufferedInputStream input,
            BufferedOutputStream output) throws IOException {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(256);
        boolean streamingRow = false;
        boolean keepStreamingRow = false;
        boolean escaped = false;
        int separators = 0;
        int value;
        while ((value = input.read()) >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Captured-notification clearing was canceled.");
            }
            if (streamingRow) {
                if (keepStreamingRow) {
                    output.write(value);
                }
                if (value == '\n') {
                    streamingRow = false;
                    keepStreamingRow = false;
                    escaped = false;
                    separators = 0;
                    prefix.reset();
                }
                continue;
            }

            if (prefix.size() >= MAX_PREFIX_LINE_BYTES) {
                // The event type was not established inside the bounded prefix. Preserve it.
                output.write(prefix.toByteArray());
                output.write(value);
                prefix.reset();
                streamingRow = value != '\n';
                keepStreamingRow = streamingRow;
                escaped = false;
                separators = 0;
                continue;
            }
            prefix.write(value);
            if (value == '\n') {
                output.write(prefix.toByteArray());
                prefix.reset();
                escaped = false;
                separators = 0;
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value != '\t') {
                continue;
            }
            separators++;
            if (separators < 4) {
                continue;
            }
            List<String> fields = LineCodec.decodePrefix(
                    new String(prefix.toByteArray(), StandardCharsets.UTF_8), 4);
            boolean captured = fields.size() == 4
                    && EVENT_MARKER.equals(fields.get(0))
                    && CAPTURED_NOTIFICATION_EVENT_TYPE.equals(fields.get(3));
            if (!captured) {
                output.write(prefix.toByteArray());
            }
            prefix.reset();
            streamingRow = true;
            keepStreamingRow = !captured;
        }
        if (!streamingRow && prefix.size() > 0) {
            output.write(prefix.toByteArray());
        }
    }

    private static int countCapturedNotificationRows(File file) throws IOException {
        if (!file.isFile()) {
            return 0;
        }
        int count = 0;
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                if (boundedLine.isOversized()) {
                    continue;
                }
                String line = boundedLine.getRetainedText();
                List<String> row = LineCodec.decode(line);
                if (row.size() >= 4
                        && EVENT_MARKER.equals(row.get(0))
                        && CAPTURED_NOTIFICATION_EVENT_TYPE.equals(row.get(3))) {
                    count++;
                }
            }
        }
        return count;
    }

    private void deleteSessionFiles(String id) throws IOException {
        deleteOrphanedReplacementFiles(eventsFile(id));
        deleteOrphanedReplacementFiles(notesFile(id));
        deleteOrphanedReplacementFiles(stateFile(id));
        deleteOrphanedReplacementFiles(metaFile(id));
        deleteFileIfPresent(eventsFile(id));
        deleteFileIfPresent(notesFile(id));
        deleteFileIfPresent(stateFile(id));
        deleteFileIfPresent(metaFile(id));
        invalidateCapturedNotificationIds();
    }

    private void deleteOrphanedEventReplacementFiles() throws IOException {
        File[] files = sessionsDirectory.listFiles(file -> file.isFile()
                && file.getName().contains(".events")
                && file.getName().endsWith(".tmp"));
        if (files == null) {
            throw new IOException("Could not enumerate notification-redaction temporary files.");
        }
        for (File file : files) {
            deleteFileIfPresent(file);
        }
    }

    private static void deleteOrphanedReplacementFiles(File target) throws IOException {
        File parent = target.getParentFile();
        File[] files = parent == null ? null : parent.listFiles(file -> file.isFile()
                && file.getName().startsWith(target.getName())
                && file.getName().endsWith(".tmp"));
        if (files == null) {
            throw new IOException("Could not enumerate replacement temporary files.");
        }
        for (File file : files) {
            deleteFileIfPresent(file);
        }
    }

    private static void deleteFileIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete journal data: " + file.getName());
        }
    }

    private List<JournalEvent> readEvents(String id) throws IOException {
        List<JournalEvent> result = new ArrayList<>();
        File file = eventsFile(id);
        if (!file.isFile()) {
            return result;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                if (boundedLine.isOversized()) {
                    continue;
                }
                String line = boundedLine.getRetainedText();
                List<String> row = LineCodec.decode(line);
                if (row.size() == 5 && EVENT_MARKER.equals(row.get(0))) {
                    try {
                        result.add(new JournalEvent(
                                Long.parseLong(row.get(1)), row.get(2), row.get(3), row.get(4)));
                    } catch (NumberFormatException ignored) {
                        // Preserve readable rows and skip only the malformed event.
                    }
                }
            }
        }
        return result;
    }

    private TailEvents readTailEvents(
            String id,
            int maximumEvents,
            int maximumEncodedBytes) throws IOException {
        File file = eventsFile(id);
        if (!file.isFile() || file.length() == 0L) {
            return new TailEvents(Collections.emptyList(), false);
        }
        long length = file.length();
        long lowerBound = Math.max(0L, length - maximumEncodedBytes);
        long start = lowerBound;
        boolean locatedEventBoundary = false;
        int newlineCount = 0;
        byte[] scanBuffer = new byte[8_192];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long cursor = length;
            while (cursor > lowerBound && !locatedEventBoundary) {
                throwIfHistoryScanInterrupted();
                long blockStart = Math.max(lowerBound, cursor - scanBuffer.length);
                int count = (int) (cursor - blockStart);
                input.seek(blockStart);
                input.readFully(scanBuffer, 0, count);
                for (int index = count - 1; index >= 0; index--) {
                    if (scanBuffer[index] == '\n') {
                        newlineCount++;
                        if (newlineCount == maximumEvents + 1) {
                            start = blockStart + index + 1L;
                            locatedEventBoundary = true;
                            break;
                        }
                    }
                }
                cursor = blockStart;
            }
        }

        boolean omitted = start > 0L;
        try (FileInputStream input = new FileInputStream(file)) {
            if (start > 0L) {
                skipFully(input, start);
                if (!locatedEventBoundary) {
                    // The byte cap landed inside an oversized/older row. Drop only that partial row.
                    int value;
                    while ((value = input.read()) >= 0 && value != '\n') {
                        // Stream to the next complete encoded row without materializing it.
                    }
                }
            }
            ArrayDeque<JournalEvent> events = new ArrayDeque<>(maximumEvents);
            try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                    input, MAX_PERSISTED_LINE_BYTES)) {
                BoundedUtf8LineReader.Line boundedLine;
                while ((boundedLine = reader.readLine()) != null) {
                    throwIfHistoryScanInterrupted();
                    if (boundedLine.isOversized()) {
                        omitted = true;
                        continue;
                    }
                    String line = boundedLine.getRetainedText();
                    List<String> row = LineCodec.decode(line);
                    if (row.size() != 5 || !EVENT_MARKER.equals(row.get(0))) {
                        continue;
                    }
                    try {
                        if (events.size() == maximumEvents) {
                            events.removeFirst();
                            omitted = true;
                        }
                        events.addLast(new JournalEvent(
                                Long.parseLong(row.get(1)),
                                row.get(2),
                                row.get(3),
                                row.get(4)));
                    } catch (NumberFormatException ignored) {
                        // Skip only the malformed tail row.
                    }
                }
            }
            return new TailEvents(new ArrayList<>(events), omitted);
        }
    }

    private static void skipFully(FileInputStream input, long byteCount) throws IOException {
        long remaining = byteCount;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new IOException("Current-session event file ended during bounded load.");
            }
            remaining--;
        }
    }

    private List<JournalNote> readNotes(String id) throws IOException {
        List<JournalNote> result = new ArrayList<>();
        File file = notesFile(id);
        if (!file.isFile()) {
            return result;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                if (boundedLine.isOversized()) {
                    continue;
                }
                String line = boundedLine.getRetainedText();
                List<String> row = LineCodec.decode(line);
                if (row.size() == 6 && NOTE_MARKER.equals(row.get(0))) {
                    try {
                        result.add(new JournalNote(
                                row.get(1),
                                row.get(2),
                                Long.parseLong(row.get(3)),
                                row.get(4),
                                row.get(5)));
                    } catch (NumberFormatException ignored) {
                        // Skip one malformed note partition without hiding the session.
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(JournalNote::getCreatedEpochMillis));
        return result;
    }

    private List<CalendarComment> readCalendarComments() throws IOException {
        Map<String, CalendarComment> byDate = new LinkedHashMap<>();
        if (!calendarCommentsFile.isFile()) {
            return new ArrayList<>();
        }
        requireBoundedCalendarCommentsFile();
        int physicalRows = 0;
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(calendarCommentsFile), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                throwIfHistoryScanInterrupted();
                if (++physicalRows > MAX_CALENDAR_COMMENT_COUNT * 2) {
                    throw new IOException("Calendar Comments contains too many physical rows.");
                }
                if (boundedLine.isOversized()) {
                    throw new IOException("Calendar Comments contains an oversized row.");
                }
                String line = boundedLine.getRetainedText();
                List<String> row = LineCodec.decode(line);
                if (row.size() != 6 || !CALENDAR_COMMENT_MARKER.equals(row.get(0))) {
                    continue;
                }
                try {
                    LocalDate.parse(row.get(1));
                    ZoneId.of(row.get(2));
                    long created = Long.parseLong(row.get(3));
                    ZoneId.of(row.get(4));
                    byDate.put(row.get(1), new CalendarComment(
                            row.get(1), row.get(2), created, row.get(4), row.get(5)));
                    if (byDate.size() > MAX_CALENDAR_COMMENT_COUNT) {
                        throw new IOException(
                                "Calendar Comments exceeds the supported date count of "
                                        + MAX_CALENDAR_COMMENT_COUNT + ".");
                    }
                } catch (RuntimeException ignored) {
                    // One malformed date Comment must not hide the remaining calendar.
                }
            }
        }
        List<CalendarComment> result = new ArrayList<>(byDate.values());
        result.sort(Comparator.comparing(CalendarComment::getTargetLocalDate));
        return result;
    }

    private void requireBoundedCalendarCommentsFile() throws IOException {
        if (calendarCommentsFile.length() > MAX_CALENDAR_COMMENT_BYTES) {
            throw new IOException(
                    "Calendar Comments exceeds the supported "
                            + MAX_CALENDAR_COMMENT_BYTES + "-byte aggregate limit.");
        }
    }

    private List<CapturedNotification> readCapturedNotificationInbox() throws IOException {
        List<CapturedNotification> result = new ArrayList<>();
        if (!notificationInboxFile.isFile() || notificationInboxFile.length() == 0L) {
            return result;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(notificationInboxFile), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while ((boundedLine = reader.readLine()) != null) {
                if (boundedLine.isOversized()) {
                    continue;
                }
                String line = boundedLine.getRetainedText();
                List<String> row = LineCodec.decode(line);
                if (row.size() != 2 || !CAPTURE_INBOX_MARKER.equals(row.get(0))) {
                    continue;
                }
                CapturedNotification capture = CapturedNotification.decodeDetail(row.get(1));
                if (capture != null) {
                    result.add(capture);
                }
            }
        }
        return result;
    }

    private LinkedHashMap<String, CapturedNotification> capturedNotificationInboxById()
            throws IOException {
        LinkedHashMap<String, CapturedNotification> byId = new LinkedHashMap<>();
        for (CapturedNotification capture : readCapturedNotificationInbox()) {
            byId.putIfAbsent(capture.getCaptureId(), capture);
        }
        return byId;
    }

    private void writeCapturedNotificationInbox(List<CapturedNotification> captures)
            throws IOException {
        StringBuilder rows = new StringBuilder();
        for (CapturedNotification capture : captures) {
            rows.append(LineCodec.encode(
                    CAPTURE_INBOX_MARKER,
                    capture.encodeDetail())).append('\n');
        }
        writeReplace(notificationInboxFile, rows.toString());
    }

    private Set<String> capturedNotificationIds() throws IOException {
        String cacheKey = captureCacheKey();
        long scanEpoch;
        synchronized (CAPTURE_ID_CACHE_LOCK) {
            while (true) {
                while (CAPTURE_ID_CACHE_INITIALIZING.contains(cacheKey)) {
                    try {
                        CAPTURE_ID_CACHE_LOCK.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Capture-ID scan was canceled.", interrupted);
                    }
                }
                Set<String> cached = CAPTURE_ID_CACHE_BY_ROOT.get(cacheKey);
                if (cached != null) {
                    return new HashSet<>(cached);
                }
                CAPTURE_ID_CACHE_INITIALIZING.add(cacheKey);
                scanEpoch = CAPTURE_ID_CACHE_EPOCH_BY_ROOT.getOrDefault(cacheKey, 0L);
                break;
            }
        }
        try {
            observeScan("capture-id-index");
        } catch (IOException failure) {
            releaseCaptureIdInitialization(cacheKey);
            throw failure;
        } catch (RuntimeException failure) {
            releaseCaptureIdInitialization(cacheKey);
            throw new IOException("Capture-ID scan setup failed.", failure);
        }
        while (true) {
            Set<String> result = new HashSet<>();
            try {
                for (String id : recordIdsOnDisk()) {
                    throwIfHistoryScanInterrupted();
                    collectCapturedNotificationIds(eventsFile(id), result);
                    if (result.size() >= MAX_STORED_CAPTURE_COUNT) {
                        break;
                    }
                }
            } catch (IOException failure) {
                releaseCaptureIdInitialization(cacheKey);
                throw failure;
            } catch (RuntimeException failure) {
                releaseCaptureIdInitialization(cacheKey);
                throw new IOException("Capture-ID scan failed.", failure);
            }
            synchronized (CAPTURE_ID_CACHE_LOCK) {
                long currentEpoch = CAPTURE_ID_CACHE_EPOCH_BY_ROOT.getOrDefault(cacheKey, 0L);
                if (currentEpoch != scanEpoch) {
                    // A clear/delete replaced files while this scan held old descriptors. Retain
                    // ownership of initialization and retry the new epoch without publishing.
                    scanEpoch = currentEpoch;
                    continue;
                }
                Set<String> pending = CAPTURE_ID_CACHE_PENDING_BY_ROOT.remove(cacheKey);
                if (pending != null) {
                    addCaptureIdsBounded(result, pending);
                }
                CAPTURE_ID_CACHE_BY_ROOT.put(cacheKey, new HashSet<>(result));
                CAPTURE_ID_CACHE_INITIALIZING.remove(cacheKey);
                CAPTURE_ID_CACHE_LOCK.notifyAll();
                return new HashSet<>(result);
            }
        }
    }

    private void observeScan(String scanName) throws IOException {
        if (scanObserver != null) {
            scanObserver.onScanStarted(scanName);
        }
    }

    private static void releaseCaptureIdInitialization(String cacheKey) {
        synchronized (CAPTURE_ID_CACHE_LOCK) {
            CAPTURE_ID_CACHE_INITIALIZING.remove(cacheKey);
            CAPTURE_ID_CACHE_BY_ROOT.remove(cacheKey);
            CAPTURE_ID_CACHE_LOCK.notifyAll();
        }
    }

    private Set<String> mergeCachedCaptureIds(Set<String> scanned) {
        synchronized (CAPTURE_ID_CACHE_LOCK) {
            Set<String> cached = CAPTURE_ID_CACHE_BY_ROOT.get(captureCacheKey());
            Set<String> merged = new HashSet<>();
            addCaptureIdsBounded(merged, scanned);
            if (cached != null) {
                addCaptureIdsBounded(merged, cached);
            }
            Set<String> pending = CAPTURE_ID_CACHE_PENDING_BY_ROOT.get(captureCacheKey());
            if (pending != null) {
                addCaptureIdsBounded(merged, pending);
            }
            return merged;
        }
    }

    private static void addCaptureIdsBounded(
            Set<String> destination,
            Iterable<String> source) {
        if (source == null) {
            return;
        }
        for (String captureId : source) {
            if (destination.size() >= MAX_STORED_CAPTURE_COUNT) {
                return;
            }
            if (captureId != null) {
                destination.add(captureId);
            }
        }
    }

    private static void collectCapturedNotificationIds(File file, Set<String> destination)
            throws IOException {
        if (!file.isFile()) {
            return;
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PERSISTED_LINE_BYTES)) {
            BoundedUtf8LineReader.Line boundedLine;
            while (destination.size() < MAX_STORED_CAPTURE_COUNT
                    && (boundedLine = reader.readLine()) != null) {
                if (boundedLine.isOversized()) {
                    continue;
                }
                String line = boundedLine.getRetainedText();
                List<String> row = LineCodec.decode(line);
                if (row.size() != 5
                        || !EVENT_MARKER.equals(row.get(0))
                        || !CAPTURED_NOTIFICATION_EVENT_TYPE.equals(row.get(3))) {
                    continue;
                }
                CapturedNotification existing = CapturedNotification.decodeDetail(row.get(4));
                if (existing != null) {
                    destination.add(existing.getCaptureId());
                }
            }
        }
    }

    private String captureCacheKey() {
        return journalRoot.getAbsolutePath();
    }

    private void invalidateCapturedNotificationIds() {
        synchronized (CAPTURE_ID_CACHE_LOCK) {
            String cacheKey = captureCacheKey();
            CAPTURE_ID_CACHE_BY_ROOT.remove(cacheKey);
            CAPTURE_ID_CACHE_PENDING_BY_ROOT.remove(cacheKey);
            CAPTURE_ID_CACHE_EPOCH_BY_ROOT.put(
                    cacheKey,
                    CAPTURE_ID_CACHE_EPOCH_BY_ROOT.getOrDefault(cacheKey, 0L) + 1L);
        }
    }

    private void writeNotes(String id, List<JournalNote> notes) throws IOException {
        StringBuilder rows = new StringBuilder();
        for (JournalNote note : notes) {
            JournalTextPolicy.requireWithinLimit(note.getText());
            rows.append(LineCodec.encode(
                    NOTE_MARKER,
                    note.getPartitionId(),
                    note.getLocalDate(),
                    Long.toString(note.getCreatedEpochMillis()),
                    note.getZoneId(),
                    note.getText())).append('\n');
        }
        writeReplace(notesFile(id), rows.toString());
    }

    private void writeCalendarComments(List<CalendarComment> comments) throws IOException {
        if (comments.size() > MAX_CALENDAR_COMMENT_COUNT) {
            throw new IOException(
                    "Calendar Comments exceeds the supported date count of "
                            + MAX_CALENDAR_COMMENT_COUNT + ".");
        }
        StringBuilder rows = new StringBuilder();
        long encodedBytes = 0L;
        for (CalendarComment comment : comments) {
            JournalTextPolicy.requireWithinLimit(comment.getText());
            String row = LineCodec.encode(
                    CALENDAR_COMMENT_MARKER,
                    comment.getTargetLocalDate(),
                    comment.getTargetZoneId(),
                    Long.toString(comment.getCreatedEpochMillis()),
                    comment.getCreatedZoneId(),
                    comment.getText());
            encodedBytes += row.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (encodedBytes > MAX_CALENDAR_COMMENT_BYTES) {
                throw new IOException(
                        "Calendar Comments exceeds the supported "
                                + MAX_CALENDAR_COMMENT_BYTES + "-byte aggregate limit.");
            }
            rows.append(row).append('\n');
        }
        writeReplace(calendarCommentsFile, rows.toString());
    }

    private static void validateLocalDate(String value) throws IOException {
        try {
            LocalDate.parse(value);
        } catch (RuntimeException error) {
            throw new IOException("Invalid calendar Comment date.", error);
        }
    }

    private static void validateZoneId(String value) throws IOException {
        try {
            ZoneId.of(value == null ? "" : value);
        } catch (RuntimeException error) {
            throw new IOException("Invalid calendar Comment timezone.", error);
        }
    }

    private SessionStatus readStatus(String id) throws IOException {
        String value = readText(stateFile(id)).trim();
        try {
            return SessionStatus.valueOf(value);
        } catch (IllegalArgumentException error) {
            return SessionStatus.UNCLOSED;
        }
    }

    private static List<String> readFirstRow(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("Missing file: " + file.getName());
        }
        try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(
                new FileInputStream(file), MAX_PREFIX_LINE_BYTES)) {
            BoundedUtf8LineReader.Line line = reader.readLine();
            if (line == null) {
                throw new IOException("Empty file: " + file.getName());
            }
            if (line.isOversized()) {
                throw new IOException("Oversized metadata file: " + file.getName());
            }
            return LineCodec.decode(line.getRetainedText());
        }
    }

    private static String readText(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        if (file.length() > MAX_PREFIX_LINE_BYTES) {
            throw new IOException("Oversized state file: " + file.getName());
        }
        int initialCapacity = (int) Math.min(Math.max(file.length(), 32L), 65_536L);
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long parseLong(String value, String field) throws IOException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IOException("Invalid " + field + ".", error);
        }
    }

    private static void writeReplace(File target, String text) throws IOException {
        File temporary = File.createTempFile(target.getName(), ".tmp", target.getParentFile());
        boolean moved = false;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            moveReplacement(temporary, target);
            moved = true;
        } finally {
            if (!moved && temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static void moveReplacement(File temporary, File target) throws IOException {
        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void validateId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Invalid journal record identifier.");
        }
    }

    private void notifyDurableMutation() {
        if (durableMutationObserver == null) {
            return;
        }
        try {
            durableMutationObserver.run();
        } catch (RuntimeException ignored) {
            // A mirror hint is never allowed to invalidate the private crash-safe journal.
        }
    }

    private File metaFile(String id) {
        return new File(sessionsDirectory, id + ".meta");
    }

    private File eventsFile(String id) {
        return new File(sessionsDirectory, id + ".events");
    }

    private File notesFile(String id) {
        return new File(sessionsDirectory, id + ".notes");
    }

    private File stateFile(String id) {
        return new File(sessionsDirectory, id + ".state");
    }

    private File deleteMarkerFile(String id) {
        return new File(sessionsDirectory, id + DELETE_MARKER_SUFFIX);
    }

    private File clearMarkerFile() {
        return new File(sessionsDirectory, CLEAR_MARKER_NAME);
    }

    private File appManagedLogClearMarkerFile() {
        return new File(sessionsDirectory, APP_MANAGED_LOG_CLEAR_MARKER_NAME);
    }

    private File captureClearMarkerFile() {
        return new File(journalRoot, CAPTURE_CLEAR_MARKER_NAME);
    }

    private static final class TailEvents {
        private final List<JournalEvent> events;
        private final boolean earlierEventsOmitted;

        private TailEvents(List<JournalEvent> events, boolean earlierEventsOmitted) {
            this.events = events;
            this.earlierEventsOmitted = earlierEventsOmitted;
        }
    }

    private static final class RetentionRecord {
        private final String id;
        private final SessionStatus status;
        private final long newestDurableTimestamp;

        private RetentionRecord(
                String id,
                SessionStatus status,
                long newestDurableTimestamp) {
            this.id = id;
            this.status = status;
            this.newestDurableTimestamp = newestDurableTimestamp;
        }
    }

    private static final class RetentionCandidate {
        private final String id;
        private final RetentionFileState fileState;

        private RetentionCandidate(String id, RetentionFileState fileState) {
            this.id = id;
            this.fileState = fileState;
        }
    }

    private static final class RetentionFileState {
        private final FileState metadata;
        private final FileState status;
        private final FileState events;
        private final FileState notes;
        private final FileState deleteMarker;

        private RetentionFileState(
                FileState metadata,
                FileState status,
                FileState events,
                FileState notes,
                FileState deleteMarker) {
            this.metadata = metadata;
            this.status = status;
            this.events = events;
            this.notes = notes;
            this.deleteMarker = deleteMarker;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RetentionFileState)) {
                return false;
            }
            RetentionFileState state = (RetentionFileState) other;
            return metadata.equals(state.metadata)
                    && status.equals(state.status)
                    && events.equals(state.events)
                    && notes.equals(state.notes)
                    && deleteMarker.equals(state.deleteMarker);
        }

        @Override
        public int hashCode() {
            int result = metadata.hashCode();
            result = 31 * result + status.hashCode();
            result = 31 * result + events.hashCode();
            result = 31 * result + notes.hashCode();
            return 31 * result + deleteMarker.hashCode();
        }
    }

    private static final class FileState {
        private final boolean exists;
        private final long length;
        private final long modified;

        private FileState(boolean exists, long length, long modified) {
            this.exists = exists;
            this.length = length;
            this.modified = modified;
        }

        private static FileState capture(File file) {
            return new FileState(file.isFile(), file.length(), file.lastModified());
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FileState)) {
                return false;
            }
            FileState state = (FileState) other;
            return exists == state.exists
                    && length == state.length
                    && modified == state.modified;
        }

        @Override
        public int hashCode() {
            int result = exists ? 1 : 0;
            result = 31 * result + Long.hashCode(length);
            return 31 * result + Long.hashCode(modified);
        }
    }
}
