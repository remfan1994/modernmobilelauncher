package vip.thatiam.mml;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Removes one durable MML log notification without opening the launcher UI. */
public final class EntryNotificationReceiver extends BroadcastReceiver {
  static final String ACTION_REMOVE = "vip.thatiam.mml.REMOVE_LOG_NOTIFICATION";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !ACTION_REMOVE.equals(intent.getAction())) return;
    String uid = intent.getStringExtra(EntryNotifications.EXTRA_UID);
    String tag = intent.getStringExtra(EntryNotifications.EXTRA_TAG);
    if (uid == null || uid.isEmpty() || tag == null || tag.isEmpty()) return;
    PendingResult pending = goAsync();
    Journal.get(context).markNotified(
        uid,
        0,
        (ok, message) -> {
          if (ok) EntryNotifications.cancelTag(context.getApplicationContext(), tag);
          pending.finish();
        });
  }
}
