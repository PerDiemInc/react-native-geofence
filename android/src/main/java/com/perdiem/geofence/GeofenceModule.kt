package com.perdiem.geofence

import android.Manifest
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** TurboModule over the Play Services geofencing API. */
class GeofenceModule(private val reactContext: ReactApplicationContext) :
  NativePerdiemGeofenceSpec(reactContext) {

  companion object {
    const val NAME = "PerdiemGeofence"
  }

  override fun getName() = NAME

  override fun isSupported(promise: Promise) {
    promise.resolve(
      GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(reactContext) ==
        ConnectionResult.SUCCESS
    )
  }

  /**
   * `always` needs ACCESS_BACKGROUND_LOCATION from API 29 — without it a fence
   * stops firing the moment the app closes, which is the whole point.
   */
  override fun getPermission(promise: Promise) {
    val fine = GeofenceRegistrar.hasPermission(reactContext, Manifest.permission.ACCESS_FINE_LOCATION)
    val background =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        GeofenceRegistrar.hasPermission(reactContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    promise.resolve(
      when {
        fine && background -> "always"
        fine -> "whenInUse"
        else -> "denied"
      }
    )
  }

  /**
   * A read, not a prompt. Both permissions are requested from JS via
   * PermissionsAndroid, because background location can only be asked for once
   * foreground location is already held.
   */
  override fun requestPermission(promise: Promise) = getPermission(promise)

  override fun setRegions(regions: ReadableArray, promise: Promise) {
    val next = parse(regions)
    val ids = next.map { it.id }

    // Re-registering an unchanged set is churn at best; skip it unless Play
    // Services is known to have dropped the fences.
    if (!GeofenceStore.isStale(reactContext) && next == GeofenceStore.loadRegions(reactContext)) {
      promise.resolve(Arguments.fromList(ids))
      return
    }

    GeofenceRegistrar.register(reactContext, next) { armed ->
      if (armed) {
        GeofenceStore.saveRegions(reactContext, next)
        GeofenceStore.clearStale(reactContext)
        promise.resolve(Arguments.fromList(ids))
      } else {
        promise.resolve(Arguments.createArray())
      }
    }
  }

  override fun clearRegions(promise: Promise) {
    GeofenceStore.saveRegions(reactContext, emptyList())
    GeofenceRegistrar.register(reactContext, emptyList()) { promise.resolve(true) }
  }

  override fun getEvents(promise: Promise) {
    val array = Arguments.createArray()

    for (event in GeofenceStore.loadEvents(reactContext)) {
      array.pushMap(
        Arguments.createMap().apply {
          putString("id", event.id)
          putString("enteredAt", event.enteredAt)
        }
      )
    }

    promise.resolve(array)
  }

  override fun clearEvents(count: Double, promise: Promise) {
    GeofenceStore.clearEvents(reactContext, count.toInt())
    promise.resolve(true)
  }

  private fun parse(regions: ReadableArray): List<GeofenceStore.Region> {
    val parsed = mutableListOf<GeofenceStore.Region>()

    for (index in 0 until regions.size()) {
      val map = regions.getMap(index) ?: continue
      val id = map.getString("id") ?: continue

      if (id.isEmpty()) continue

      parsed.add(
        GeofenceStore.Region(
          id,
          map.getDouble("latitude"),
          map.getDouble("longitude"),
          map.getDouble("radius"),
          map.getString("title") ?: "",
          map.getString("body") ?: "",
        )
      )
    }

    return parsed
  }
}
