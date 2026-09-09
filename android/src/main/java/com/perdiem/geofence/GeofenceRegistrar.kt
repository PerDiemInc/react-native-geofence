package com.perdiem.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Registers fences with Play Services. Shared by the module and the boot receiver. */
object GeofenceRegistrar {

  const val TAG = "PerdiemGeofence"

  /** Play Services allows 100 fences per app. */
  private const val MAX_REGIONS = 100

  /**
   * Mutable is required: the system writes the transition into this intent.
   * Built from the application context everywhere so every caller gets the
   * same PendingIntent and a remove matches an earlier add.
   */
  private fun pendingIntent(context: Context): PendingIntent {
    val app = context.applicationContext
    val flags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }

    return PendingIntent.getBroadcast(
      app,
      0,
      Intent(app, GeofenceBroadcastReceiver::class.java),
      flags,
    )
  }

  fun hasPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  /**
   * Fine location arms a fence. From Android 10, background location is what
   * lets it fire with the app closed, and Play Services refuses to arm without it.
   */
  fun hasPermissions(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
      (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))

  /** Replace whatever is registered with [regions]. [onDone] receives whether that set is armed. */
  @SuppressLint("MissingPermission")
  fun register(context: Context, regions: List<GeofenceStore.Region>, onDone: (Boolean) -> Unit) {
    val app = context.applicationContext
    val client = LocationServices.getGeofencingClient(app)
    val intent = pendingIntent(app)

    if (regions.isNotEmpty() && !hasPermissions(app)) {
      Log.i(TAG, "location permission missing; nothing armed")
      onDone(false)
      return
    }

    val fences = regions.take(MAX_REGIONS).map { region ->
      Geofence.Builder()
        .setRequestId(region.id)
        .setCircularRegion(
          region.latitude,
          region.longitude,
          // Below the device's accuracy floor a fence never fires reliably.
          maxOf(region.radius, 100.0).toFloat(),
        )
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
        .build()
    }

    client.removeGeofences(intent).addOnCompleteListener {
      if (fences.isEmpty()) {
        onDone(true)
        return@addOnCompleteListener
      }

      val request = GeofencingRequest.Builder()
        // No initial trigger: regions are registered from inside the app, and
        // a user standing in one with the app open needs no notification.
        .setInitialTrigger(0)
        .addGeofences(fences)
        .build()

      client.addGeofences(request, intent)
        .addOnSuccessListener {
          Log.i(TAG, "armed ${fences.size} region(s)")
          onDone(true)
        }
        .addOnFailureListener { error ->
          Log.e(TAG, "arming failed: ${error.message}")
          onDone(false)
        }
    }
  }
}
