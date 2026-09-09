package com.perdiem.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Play Services holds registered fences in GmsCore process state, so a reboot
 * or an app update silently drops them all. The regions are on disk, so re-arm
 * from there rather than wait for the user to happen to open the app.
 *
 * If that cannot happen now — permission revoked, Play Services not ready —
 * the store is marked stale and the next setRegions from the app re-arms.
 */
class GeofenceBootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
      intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }

    val regions = GeofenceStore.loadRegions(context)

    if (regions.isEmpty()) return

    GeofenceStore.markStale(context)

    // Play Services answers asynchronously; hold the receiver open until it does.
    val pending = goAsync()

    GeofenceRegistrar.register(context, regions) { armed ->
      if (armed) GeofenceStore.clearStale(context)

      Log.i(
        GeofenceRegistrar.TAG,
        if (armed) "re-armed ${regions.size} region(s) after ${intent.action}"
        else "could not re-arm after ${intent.action}; re-sync owed",
      )

      pending.finish()
    }
  }
}
