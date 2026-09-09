package com.perdiem.geofence

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything an arrival needs, on disk.
 *
 * A geofence broadcast can reach a process Android just started for that alone,
 * with no React Native bridge to ask — so the notification to show and the log
 * to append to both have to already be here. The iOS side does the same with
 * UserDefaults.
 */
object GeofenceStore {

  private const val PREFS = "com.perdiem.geofence"
  private const val KEY_REGIONS = "regions"
  private const val KEY_EVENTS = "events"
  private const val KEY_LAST_ENTERED = "last_entered"
  private const val KEY_STALE = "stale"

  /** A device whose app is never reopened must not grow its log without bound. */
  private const val MAX_EVENTS = 500

  data class Region(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    val title: String,
    val body: String,
  )

  data class Event(val id: String, val enteredAt: String)

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  @Synchronized
  fun saveRegions(context: Context, regions: List<Region>) {
    val array = JSONArray()

    for (region in regions) {
      array.put(
        JSONObject()
          .put("id", region.id)
          .put("latitude", region.latitude)
          .put("longitude", region.longitude)
          .put("radius", region.radius)
          .put("title", region.title)
          .put("body", region.body)
      )
    }

    prefs(context).edit().putString(KEY_REGIONS, array.toString()).commit()
  }

  @Synchronized
  fun loadRegions(context: Context): List<Region> {
    val array = parseArray(prefs(context).getString(KEY_REGIONS, null)) ?: return emptyList()
    val regions = mutableListOf<Region>()

    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      val id = item.optString("id")

      if (id.isEmpty()) continue

      regions.add(
        Region(
          id,
          item.optDouble("latitude"),
          item.optDouble("longitude"),
          item.optDouble("radius"),
          item.optString("title"),
          item.optString("body"),
        )
      )
    }

    return regions
  }

  @Synchronized
  fun loadEvents(context: Context): List<Event> {
    val array = parseArray(prefs(context).getString(KEY_EVENTS, null)) ?: return emptyList()
    val events = mutableListOf<Event>()

    for (index in 0 until array.length()) {
      val item = array.optJSONObject(index) ?: continue
      val id = item.optString("id")
      val enteredAt = item.optString("enteredAt")

      if (id.isEmpty() || enteredAt.isEmpty()) continue

      events.add(Event(id, enteredAt))
    }

    return events
  }

  /** Durable before returning: the process that records an arrival may not live long. */
  @Synchronized
  fun appendEvent(context: Context, event: Event) {
    val all = loadEvents(context).toMutableList()
    all.add(event)

    while (all.size > MAX_EVENTS) all.removeAt(0)

    saveEvents(context, all)
  }

  @Synchronized
  fun clearEvents(context: Context, count: Int) {
    val all = loadEvents(context)
    saveEvents(context, all.drop(count.coerceIn(0, all.size)))
  }

  private fun saveEvents(context: Context, events: List<Event>) {
    val array = JSONArray()

    for (event in events) {
      array.put(JSONObject().put("id", event.id).put("enteredAt", event.enteredAt))
    }

    prefs(context).edit().putString(KEY_EVENTS, array.toString()).commit()
  }

  /**
   * True when the same region was entered inside [windowMs]; records the
   * arrival otherwise. One physical arrival can surface as more than one OS event.
   */
  @Synchronized
  fun isRepeatEntry(context: Context, id: String, nowMs: Long, windowMs: Long): Boolean {
    val last = prefs(context).getString(KEY_LAST_ENTERED, null)
      ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
      ?: JSONObject()
    val previous = last.optLong(id, Long.MIN_VALUE)

    if (previous != Long.MIN_VALUE && nowMs - previous < windowMs) return true

    last.put(id, nowMs)
    prefs(context).edit().putString(KEY_LAST_ENTERED, last.toString()).commit()

    return false
  }

  /** Set when Play Services dropped the registered fences; the next setRegions re-arms. */
  fun markStale(context: Context) {
    prefs(context).edit().putBoolean(KEY_STALE, true).commit()
  }

  fun clearStale(context: Context) {
    prefs(context).edit().putBoolean(KEY_STALE, false).commit()
  }

  fun isStale(context: Context): Boolean = prefs(context).getBoolean(KEY_STALE, false)

  private fun parseArray(raw: String?): JSONArray? =
    raw?.let { runCatching { JSONArray(it) }.getOrNull() }
}
