package com.perdiem.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/**
 * Where Android delivers an arrival — including to a process it just started
 * for this alone, with no Activity and no React Native bridge.
 *
 * Everything done here is local and fast, so it fits inside the receiver's own
 * time budget with no background work.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return

    if (event.hasError()) {
      Log.e(GeofenceRegistrar.TAG, "geofence event error ${event.errorCode}")

      // Play Services dropped the fences, typically because location was
      // switched off. The next setRegions re-arms them.
      if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
        GeofenceStore.markStale(context)
      }

      return
    }

    if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

    for (fence in event.triggeringGeofences.orEmpty()) {
      Log.i(GeofenceRegistrar.TAG, "entered region ${fence.requestId}")
      GeofenceNotifier.onEntered(context, fence.requestId)
    }
  }
}
