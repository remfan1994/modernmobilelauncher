package vip.thatiam.mml;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct USER_PRESENT capture independent of Usage Access and screen-event pairing. */
public final class DeviceEventCapture {
  private static final AtomicBoolean registered = new AtomicBoolean();

  private static final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (intent == null || !Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
          Journal.get(context).record("DEVICE", "Android", "USER_PRESENT", "User present", "");
        }
      };

  private DeviceEventCapture() {}

  public static void register(Context context) {
    if (context == null || !registered.compareAndSet(false, true)) return;
    Context app = context.getApplicationContext();
    IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
    try {
      // USER_PRESENT is a protected system broadcast. Context registration avoids the
      // Android 8+ manifest restriction on most implicit broadcasts.
      app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
    } catch (RuntimeException error) {
      registered.set(false);
      Journal.get(app)
          .record(
              "SYSTEM",
              "MML",
              "device_receiver_error",
              "Device event receiver unavailable",
              Journal.describe(error));
    }
  }
}
