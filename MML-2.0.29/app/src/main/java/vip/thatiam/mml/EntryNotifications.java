package vip.thatiam.mml;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

/** Persistent notification copy of any saved MML log entry. */
final class EntryNotifications {
  private static final String CHANNEL_ID = "mml-user-log-entry";
  private static final String LEGACY_SETTINGS_CHANNEL_ID = "mml-settings";
  static final int NOTIFICATION_ID = 7013;
  private static final int LEGACY_SETTINGS_NOTIFICATION_ID = 7001;
  static final String ACTION_OPEN_LOG = "vip.thatiam.mml.OPEN_NOTIFIED_LOG";
  static final String EXTRA_UID = "vip.thatiam.mml.extra.LOG_UID";
  static final String EXTRA_TAG = "vip.thatiam.mml.extra.NOTIFICATION_TAG";
  private static final int PREVIEW_LIMIT = 2048;

  private EntryNotifications() {}

  static String post(Context context, Entry entry) {
    if (entry == null) return "Log entry was not available.";
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) return "Android has not allowed MML notifications.";
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (manager == null) return "Android notification service is unavailable.";
      NotificationChannel channel =
          new NotificationChannel(CHANNEL_ID, "MML log entries", NotificationManager.IMPORTANCE_DEFAULT);
      channel.setDescription("MML log entries pushed to notifications");
      manager.createNotificationChannel(channel);

      Intent open = null;
      if ("app_launch".equals(entry.type) && !entry.source.isEmpty()) {
        open = context.getPackageManager().getLaunchIntentForPackage(entry.source);
        if (open != null)
          open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      }
      if (open == null)
        open =
            new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent tap =
          PendingIntent.getActivity(
              context,
              ("tap:" + entry.uid).hashCode(),
              open,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

      Intent logIntent =
          new Intent(context, MainActivity.class)
              .setAction(ACTION_OPEN_LOG)
              .putExtra(EXTRA_UID, entry.uid)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent log =
          PendingIntent.getActivity(
              context,
              ("log:" + entry.uid).hashCode(),
              logIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

      String tag = notificationTag(entry);
      Intent removeIntent =
          new Intent(context, EntryNotificationReceiver.class)
              .setAction(EntryNotificationReceiver.ACTION_REMOVE)
              .putExtra(EXTRA_UID, entry.uid)
              .putExtra(EXTRA_TAG, tag);
      PendingIntent remove =
          PendingIntent.getBroadcast(
              context,
              ("remove:" + entry.uid).hashCode(),
              removeIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

      String preview = Entry.clip(entry.body.isEmpty() ? entry.title : entry.body, PREVIEW_LIMIT);
      Notification notification =
          new Notification.Builder(context, CHANNEL_ID)
              .setSmallIcon(android.R.drawable.ic_menu_edit)
              .setContentTitle(entry.title.isEmpty() ? "MML log entry" : entry.title)
              .setContentText(preview)
              .setStyle(new Notification.BigTextStyle().bigText(preview))
              .setCategory(Notification.CATEGORY_REMINDER)
              .setContentIntent(tap)
              .addAction(android.R.drawable.ic_menu_view, "Log", log)
              .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Remove", remove)
              .setAutoCancel(false)
              .setOngoing(true)
              .setWhen(entry.timeMs)
              .setShowWhen(true)
              .build();
      // App-launch cards use one package-derived tag. Notifying another launch row for the
      // same app updates that launcher card instead of creating duplicate launcher cards.
      manager.notify(tag, NOTIFICATION_ID, notification);
      return "";
    } catch (RuntimeException error) {
      return "Could not push the log entry: " + Journal.describe(error);
    }
  }

  static void cancel(Context context, Entry entry) {
    if (entry == null || entry.uid.isEmpty()) return;
    cancelTag(context, notificationTag(entry));
  }

  static void cancelTag(Context context, String tag) {
    if (tag == null || tag.isEmpty()) return;
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (manager != null) manager.cancel(tag, NOTIFICATION_ID);
    } catch (RuntimeException ignored) {
      // Journal state remains authoritative. A later reconcile removes stale notifications.
    }
  }

  /** Remove only notifications created by the log-entry Notify action. */
  static void cancelAll(Context context) {
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (manager == null) return;
      for (StatusBarNotification active : manager.getActiveNotifications()) {
        if (active.getId() == NOTIFICATION_ID) manager.cancel(active.getTag(), active.getId());
      }
    } catch (RuntimeException ignored) {
      // Best effort. Reconciliation will retry on the next process start.
    }
  }

  /** Remove the obsolete 2.0.25/2.0.26 persistent Settings card and channel. */
  static void removeLegacySettingsNotification(Context context) {
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (manager == null) return;
      manager.cancel(LEGACY_SETTINGS_NOTIFICATION_ID);
      manager.deleteNotificationChannel(LEGACY_SETTINGS_CHANNEL_ID);
    } catch (RuntimeException ignored) {
      // Settings is available from the app picker even if Android refuses cleanup.
    }
  }

  static Intent settingsIntent(Context context) {
    return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
  }

  private static String notificationTag(Entry entry) {
    if ("USER".equals(entry.kind) && "app_launch".equals(entry.type) && !entry.source.isEmpty())
      return "app:" + Entry.hash(entry.source);
    return "log:" + entry.uid;
  }
}
