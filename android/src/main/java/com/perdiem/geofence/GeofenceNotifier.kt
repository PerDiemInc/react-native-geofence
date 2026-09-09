package com.perdiem.geofence

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import java.time.Instant

/**
 * What happens at an arrival: show the region's notification, record that it
 * was shown.
 *
 * Runs in whatever process Android delivered the broadcast to — often one with
 * no Activity and no React Native bridge — so it reads everything from disk.
 */
object GeofenceNotifier {

  private const val TAG = GeofenceRegistrar.TAG
  private const val CHANNEL_ID = "perdiem_geofence"
  private const val CHANNEL_NAME = "Nearby"

  /** A second arrival at the same region inside this window is the same arrival. */
  private const val REPEAT_WINDOW_MS = 5 * 60 * 1000L

  fun onEntered(context: Context, id: String) {
    val region = GeofenceStore.loadRegions(context).firstOrNull { it.id == id }

    if (region == null) {
      Log.i(TAG, "entered $id but it is no longer armed")
      return
    }

    // The user is in the app already; a notification would only be noise, and
    // recording it would count a notification that was never shown.
    if (isForeground(context)) {
      Log.i(TAG, "entered $id with the app in front; nothing to show")
      return
    }

    val now = System.currentTimeMillis()

    if (GeofenceStore.isRepeatEntry(context, id, now, REPEAT_WINDOW_MS)) {
      Log.i(TAG, "entered $id again inside the repeat window")
      return
    }

    // Only a shown notification is logged, so the log is a record of what the
    // user actually saw.
    if (!present(context, region)) return

    GeofenceStore.appendEvent(
      context,
      GeofenceStore.Event(id, Instant.ofEpochMilli(now).toString()),
    )
  }

  /** True when an activity is in front. */
  private fun isForeground(context: Context): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false

    return manager.runningAppProcesses?.any {
      it.processName == context.packageName &&
        it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } == true
  }

  @SuppressLint("MissingPermission")
  private fun present(context: Context, region: GeofenceStore.Region): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      !GeofenceRegistrar.hasPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
    ) {
      Log.i(TAG, "notification permission not granted; arrival not shown")
      return false
    }

    val manager = ensureChannel(context) ?: return false

    if (!manager.areNotificationsEnabled()) {
      Log.i(TAG, "notifications disabled; arrival not shown")
      return false
    }

    manager.notify(
      // One id per region, so a repeat replaces rather than stacks.
      region.id.hashCode(),
      Notification.Builder(context, CHANNEL_ID)
        // A drawable, not a launcher mipmap: the status bar renders a small icon
        // as a tinted alpha silhouette, and an adaptive icon comes out blank.
        .setSmallIcon(R.drawable.ic_perdiem_geofence)
        .setContentTitle(region.title)
        .setContentText(region.body)
        .setStyle(Notification.BigTextStyle().bigText(region.body))
        .setContentIntent(openApp(context))
        .setAutoCancel(true)
        .build(),
    )

    Log.i(TAG, "notification presented for ${region.id}")

    return true
  }

  /** Launches the host app; it owns any deeper routing. */
  private fun openApp(context: Context): PendingIntent? {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: return null

    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun ensureChannel(context: Context): NotificationManager? {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return null

    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
      )
    }

    return manager
  }
}
