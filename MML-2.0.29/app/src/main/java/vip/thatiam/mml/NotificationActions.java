package vip.thatiam.mml;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.os.Build;
import android.service.notification.StatusBarNotification;

/** Resolves a log tap against Android's live notifications without retaining historical intents. */
final class NotificationActions {
  private NotificationActions() {}

  static final class Result {
    final PendingIntent action;
    final String message;

    Result(PendingIntent action, String message) {
      this.action = action;
      this.message = message;
    }
  }

  /** Binder access and text matching belong on a reader, not the UI thread. */
  static Result resolve(Entry entry) {
    if (!eligible(entry)) return unavailable("This entry has no live notification action.");
    if (entry.notificationKeyHash.isEmpty())
      return unavailable("This older entry has no saved notification action identity.");
    NotificationCapture listener = NotificationCapture.currentListener();
    if (listener == null) return unavailable("Notification access is not connected.");
    try {
      return select(entry, listener.getActiveNotifications());
    } catch (RuntimeException error) {
      return unavailable("Android could not read the notification action. Try again.");
    }
  }

  static Result select(Entry entry, StatusBarNotification[] active) {
    if (!eligible(entry)) return unavailable("This entry has no live notification action.");
    if (entry.notificationKeyHash.isEmpty())
      return unavailable("This older entry has no saved notification action identity.");
    Notification match = null;
    if (active != null) {
      for (StatusBarNotification item : active) {
        if (Thread.currentThread().isInterrupted())
          return unavailable("Notification action cancelled.");
        if (item == null
            || !entry.source.equals(item.getPackageName())
            || entry.timeMs != item.getPostTime()
            || !entry.notificationKeyHash.equals(Entry.hash(item.getKey()))) continue;
        try {
          Notification notification = item.getNotification();
          if (notification == null
              || !entry.title.equals(NotificationText.title(notification))
              || !entry.body.equals(NotificationText.read(notification))) continue;
          // Reused notification IDs, changed messages and equally matching cards must never
          // silently route an old record to a different target.
          if (match != null)
            return unavailable("More than one active notification matches this entry.");
          match = notification;
        } catch (RuntimeException malformed) {
          // A malformed unrelated parcel must not prevent another live card from matching.
        }
      }
    }
    if (match == null) return unavailable("This notification is no longer active or has changed.");
    if (match.contentIntent == null) return unavailable("This notification has no tap action.");
    return new Result(match.contentIntent, "");
  }

  /** Called only for an explicit tap while the launcher is visible. */
  static String send(Activity activity, Result result) {
    if (result.action == null) return result.message;
    try {
      ActivityOptions options = ActivityOptions.makeBasic();
      if (Build.VERSION.SDK_INT >= 34)
        options.setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
      result.action.send(activity, 0, null, null, null, null, options.toBundle());
      return "";
    } catch (PendingIntent.CanceledException cancelled) {
      return "This notification action is no longer available.";
    } catch (RuntimeException error) {
      return "Android could not open this notification action.";
    }
  }

  static boolean eligible(Entry entry) {
    return entry != null
        && "NOTIFICATION".equals(entry.kind)
        && ("posted".equals(entry.type) || "active".equals(entry.type));
  }

  private static Result unavailable(String message) {
    return new Result(null, message);
  }
}
