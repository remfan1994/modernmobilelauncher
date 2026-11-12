package vip.thatiam.mml;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Small device regression for MML's current log/share/app-launch model. */
public final class RegressionRunner extends Instrumentation {
  private int checks;

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      Journal journal = Journal.get(getTargetContext());
      awaitReady(journal);

      String marker = "instrumentation-" + UUID.randomUUID();
      Entry shared = Entry.sharedText(marker);
      check("shared_text".equals(shared.type), "Shared text entry type");
      CountDownLatch saved = new CountDownLatch(1);
      boolean[] saveOk = {false};
      journal.submit(shared, (ok, message) -> { saveOk[0] = ok; saved.countDown(); });
      check(saved.await(10, TimeUnit.SECONDS), "Shared entry save callback timed out");
      check(saveOk[0], "Shared entry save failed");
      Entry committed = waitFor(journal, shared.uid, true);
      check(committed != null, "Saved shared entry not readable");
      check(marker.equals(committed.body), "Saved shared body changed");

      Entry launch = Entry.appLaunch("vip.thatiam.mml", "MML");
      check("app_launch".equals(launch.type), "App launch entry type");
      check("vip.thatiam.mml".equals(launch.source), "App launch package");

      CountDownLatch deleted = new CountDownLatch(1);
      boolean[] deleteOk = {false};
      journal.deleteEntries(Collections.singletonList(committed),
          (ok, message) -> { deleteOk[0] = ok; deleted.countDown(); });
      check(deleted.await(10, TimeUnit.SECONDS), "Delete callback timed out");
      check(deleteOk[0], "Delete failed");
      check(waitFor(journal, shared.uid, false) == null, "Deleted entry still readable");
      check("ok".equalsIgnoreCase(journal.integrity()), "SQLite integrity failed");

      result.putString("result", "PASS: " + checks + " checks");
      finish(RESULT_OK, result);
    } catch (Throwable t) {
      result.putString("result", "FAIL after " + checks + " checks: " + t);
      finish(RESULT_CANCELED, result);
    }
  }

  private void awaitReady(Journal journal) throws Exception {
    long end = System.currentTimeMillis() + 10000;
    while (!journal.ready() && System.currentTimeMillis() < end) Thread.sleep(25);
    check(journal.ready(), "Journal did not become ready");
  }

  private Entry waitFor(Journal journal, String uid, boolean present) throws Exception {
    long end = System.currentTimeMillis() + 10000;
    Entry last = null;
    do {
      last = journal.findByUid(uid);
      if ((last != null) == present) return last;
      Thread.sleep(25);
    } while (System.currentTimeMillis() < end);
    return last;
  }

  private void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
  }
}
