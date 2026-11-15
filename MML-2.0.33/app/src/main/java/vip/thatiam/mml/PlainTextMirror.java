package vip.thatiam.mml;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Folder I/O never runs on the capture writer. Only verified batches advance the receipt. */
public final class PlainTextMirror {
  private static final int MAX_BATCHES_PER_REQUEST = 8;
  private static final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          15,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(),
          r -> {
            Thread t =
                new Thread(
                    () -> {
                      try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
                      catch (RuntimeException ignored) {}
                      r.run();
                    },
                    "mml-plaintext");
            t.setDaemon(true);
            return t;
          });

  static {
    worker.allowCoreThreadTimeOut(true);
  }
  private static final AtomicBoolean active = new AtomicBoolean();
  private static final AtomicBoolean running = new AtomicBoolean();
  private static final AtomicBoolean again = new AtomicBoolean();
  private static final int MAX_LISTINGS = 8, MAX_CHILDREN = 1024;
  private static final LinkedHashMap<String, Listing> listings =
      new LinkedHashMap<>(MAX_LISTINGS, 0.75f, true);
  public static volatile String status = "No log folder selected";
  private static final String DIRECTORY = "vnd.android.document/directory";

  private PlainTextMirror() {}

  public static void setActive(boolean value) {
    active.set(value);
    if (!value) again.set(false);
  }

  /**
   * Save a bounded amount of the readable folder mirror. The private SQLite journal is the
   * durable capture path; folder I/O is never required for notification capture and is not
   * self-rescheduled after MML leaves the foreground.
   */
  public static void request(Context context) {
    if (!active.get()) return;
    Context app = context.getApplicationContext();
    if (app.getSharedPreferences("mml2", 0).getString("log_tree", "").isEmpty()) return;
    if (!running.compareAndSet(false, true)) {
      again.set(true);
      return;
    }
    worker.execute(
        () -> {
          try {
            for (int pass = 0; active.get() && pass < MAX_BATCHES_PER_REQUEST; pass++) {
              boolean more = sync(app);
              if (!more) break;
            }
          } catch (Exception error) {
            listings.clear();
            status = "Folder pending: " + Journal.describe(error);
            try {
              app.getSharedPreferences("mml2", 0)
                  .edit()
                  .putString("log_folder_error", status)
                  .apply();
            } catch (RuntimeException ignored) {
              // Folder mirroring is optional and must never become a process failure path.
            }
          } finally {
            running.set(false);
            // Only an explicit request that arrived while this pass was running starts another
            // bounded pass. A backlog or provider failure never creates its own background loop.
            if (active.get() && again.getAndSet(false)) request(app);
            else if (!active.get()) again.set(false);
          }
        });
  }

  public static void retryNow(Context context) {
    request(context);
  }

  private static boolean sync(Context context) throws Exception {
    SharedPreferences prefs = context.getSharedPreferences("mml2", 0);
    String selected = prefs.getString("log_tree", "");
    if (selected.isEmpty()) {
      listings.clear();
      status = "No log folder selected";
      return false;
    }
    // A user-selected folder can also be on this phone. Do not produce additional copies
    // while its known local storage reserve is low; existing files/receipts remain intact.
    if (context.getFilesDir().getUsableSpace()
        < StoragePolicy.AUTOMATIC_FREE_RESERVE_BYTES + StoragePolicy.MAX_READ_BYTES)
      throw new IOException("Folder export paused to keep at least 64 MiB free on this device");
    Journal journal = Journal.get(context);
    String checkpointKey = "mirror:" + Entry.hash(selected);
    long after = Long.parseLong(journal.readCheckpoint(checkpointKey, "0"));
    String pendingKey = "mirror_pending:" + Entry.hash(selected);
    long pendingEnd = Long.parseLong(journal.readCheckpoint(pendingKey, "0"));
    List<Entry> rows = journal.after(after, 100);
    if (rows.isEmpty()) {
      status = "Folder up to date";
      return false;
    }
    String day = rows.get(0).day;
    long bucket = rows.get(0).rowId / 1024;
    List<Entry> batch = new ArrayList<>();
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (Entry row : rows) {
      if (pendingEnd > after && row.rowId > pendingEnd) break;
      if (!day.equals(row.day) || row.rowId / 1024 != bucket) break;
      byte[] encoded = PlainTextLogCodec.encode(row).getBytes(StandardCharsets.UTF_8);
      // New ranges stop before crossing the normal byte budget; one complete large user entry
      // remains a valid batch so export always makes progress without truncating text.
      if (pendingEnd <= after
          && !batch.isEmpty()
          && data.size() + encoded.length > 512 * 1024) break;
      data.write(encoded);
      batch.add(row);
    }
    byte[] bytes = data.toByteArray();
    long last = batch.get(batch.size() - 1).rowId;
    if (pendingEnd <= after) awaitCheckpoint(journal, pendingKey, Long.toString(last));
    else if (last != pendingEnd)
      throw new IOException("Pending export range is incomplete; local journal retained");
    String install = prefs.getString("log_install", "");
    if (install.isEmpty()) {
      install = UUID.randomUUID().toString();
      if (!prefs.edit().putString("log_install", install).commit())
        throw new IOException("Cannot save log folder identity");
    }
    Uri tree = Uri.parse(selected);
    Uri root =
        DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree));
    Uri logs = child(context, tree, root, "MML-Logs", DIRECTORY, true);
    Uri date = child(context, tree, logs, day, DIRECTORY, true);
    Uri part = child(context, tree, date, "part-" + bucket, DIRECTORY, true);
    String name = "mml2-" + install + "-" + batch.get(0).rowId + "-" + last + ".log.txt";
    Uri file = child(context, tree, part, name, "text/plain", true);
    // Providers need not implement atomic append. Rewriting one deterministic batch is retry-safe.
    if (!sameBytes(context, file, bytes)) {
      try (OutputStream out = context.getContentResolver().openOutputStream(file, "wt")) {
        if (out == null) throw new IOException("Folder returned no output stream");
        out.write(bytes);
        out.flush();
      }
      if (!sameBytes(context, file, bytes))
        throw new IOException("Folder verification failed; local records retained");
    }
    awaitCheckpoint(journal, checkpointKey, Long.toString(last));
    status = "Folder saved through record " + last;
    prefs
        .edit()
        .putString("log_folder_error", "")
        .putLong("log_folder_saved_ms", System.currentTimeMillis())
        .apply();
    return true;
  }

  private static boolean sameBytes(Context context, Uri file, byte[] expected) throws IOException {
    try (InputStream in = context.getContentResolver().openInputStream(file)) {
      if (in == null) throw new IOException("Folder returned no input stream");
      byte[] block = new byte[8192];
      int offset = 0, length;
      while ((length = in.read(block)) != -1) {
        if (offset + length > expected.length) return false;
        for (int i = 0; i < length; i++) if (block[i] != expected[offset + i]) return false;
        offset += length;
      }
      return offset == expected.length;
    }
  }

  static Uri child(Context context, Uri tree, Uri parent, String name, String mime, boolean create)
      throws IOException {
    String parentKey = parent.toString();
    Listing children = listings.get(parentKey);
    Document found = children == null ? null : children.get(name);
    if (found == null && (children == null || !children.complete)) {
      // A date/root directory may grow indefinitely. Stream it, retaining only a bounded cache.
      Listing previous = children;
      children = new Listing();
      Uri uri =
          DocumentsContract.buildChildDocumentsUriUsingTree(
              tree, DocumentsContract.getDocumentId(parent));
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(
                  uri,
                  new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                  },
                  null,
                  null,
                  null)) {
        if (cursor == null) throw new IOException("Folder cannot be listed");
        while (cursor.moveToNext()) {
          String childName = cursor.getString(1);
          Document document =
              new Document(
                  DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                  DIRECTORY.equals(cursor.getString(2)));
          if (name.equals(childName)) {
            if (found != null) throw new IOException("Duplicate log filename: " + childName);
            found = document;
          }
          if (children.complete) {
            if (children.containsKey(childName))
              throw new IOException("Duplicate log filename: " + childName);
            children.put(childName, document);
            // An incomplete scan cannot certify the uniqueness of all cached names. Only
            // explicitly requested names are retained once the complete listing exceeds the cap.
            if (!children.complete) children.clear();
          }
        }
      }
      if (!children.complete && previous != null) {
        previous.complete = false;
        children = previous;
      }
      // Keep the requested child hot even if it appeared early in a very large listing.
      if (found != null) children.put(name, found);
      if (!listings.containsKey(parentKey) && listings.size() >= MAX_LISTINGS)
        listings.remove(listings.keySet().iterator().next());
      listings.put(parentKey, children);
    }
    if (found != null) {
      if (found.directory != DIRECTORY.equals(mime))
        throw new IOException("Log path type changed: " + name);
      return found.uri;
    }
    if (!create) return null;
    Uri made = DocumentsContract.createDocument(context.getContentResolver(), parent, mime, name);
    if (made == null) throw new IOException("Cannot create " + name);
    children.put(name, new Document(made, DIRECTORY.equals(mime)));
    return made;
  }

  private static final class Listing extends LinkedHashMap<String, Document> {
    private static final long serialVersionUID = 1L;
    boolean complete = true;

    Listing() {
      super(MAX_CHILDREN, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Document> eldest) {
      if (size() <= MAX_CHILDREN) return false;
      complete = false;
      return true;
    }
  }

  private static final class Document {
    final Uri uri;
    final boolean directory;

    Document(Uri uri, boolean directory) {
      this.uri = uri;
      this.directory = directory;
    }
  }

  private static void awaitCheckpoint(Journal journal, String key, String value)
      throws IOException, InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    boolean[] ok = {false};
    journal.checkpoint(
        key,
        value,
        (success, message) -> {
          ok[0] = success;
          latch.countDown();
        });
    if (!latch.await(60, TimeUnit.SECONDS) || !ok[0])
      throw new IOException("Local folder receipt not committed");
  }

}
