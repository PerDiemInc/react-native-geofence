/**
 * Register places with a notification and let the operating system do the
 * watching.
 *
 * iOS hands the regions to `locationd` and Android to Play Services, and both
 * relaunch a terminated app to deliver an arrival. The native layer then shows
 * the region's notification and appends the arrival to a log on disk. No
 * JavaScript runs on that path, so the notification is delivered even when the
 * app has been closed. Read the log with {@link getEvents} the next time the
 * app is open, and drop what you have handled with {@link clearEvents}.
 *
 * Behaviour fixed in the native layer:
 *
 * - An arrival while the app is in the foreground is ignored and not logged.
 * - A second arrival at the same region within five minutes counts as the same
 *   arrival.
 * - Registering a set identical to the current one is a no-op, so calling
 *   {@link setRegions} on every app open is fine.
 * - iOS monitors at most 20 regions and Android 100. Extra regions are dropped.
 *
 * Every function catches internally and resolves to a safe default, so a
 * notification feature can never crash the host app.
 *
 * @packageDocumentation
 */
import { PermissionsAndroid, Platform } from "react-native";

import NativeGeofence from "./NativePerdiemGeofence";
import type { NativeRegion } from "./NativePerdiemGeofence";

/**
 * Location permission as the operating system reports it.
 *
 * Only `'always'` fires with the app closed. `'whenInUse'` covers the app
 * being open, and `'notDetermined'` means the user has not been asked yet.
 */
export type GeofencePermission =
  | "always"
  | "whenInUse"
  | "denied"
  | "restricted"
  | "notDetermined";

/** What the operating system shows when the device arrives at a region. */
export type GeofenceNotification = {
  /** Notification title, shown exactly as given. */
  title: string;
  /** Notification body, shown exactly as given. */
  body: string;
};

/** A circular area to watch, and what to show on arrival. */
export type GeofenceRegion = {
  /** Your identifier for the place. Echoed back in every {@link GeofenceEvent}. */
  id: string;
  /** Centre latitude in decimal degrees. */
  latitude: number;
  /** Centre longitude in decimal degrees. */
  longitude: number;
  /**
   * Radius in metres. Values under 100 are raised to 100, the floor below
   * which consumer GPS cannot detect an arrival reliably.
   */
  radius: number;
  /**
   * Shown on arrival. The strings are displayed verbatim, so substitute any
   * placeholders before passing them.
   */
  notification: GeofenceNotification;
};

/** An arrival the operating system showed a notification for. */
export type GeofenceEvent = {
  /** The `id` of the {@link GeofenceRegion} that was entered. */
  id: string;
  /** When the arrival was detected, as an ISO-8601 timestamp in UTC. */
  enteredAt: string;
};

const PERMISSIONS: readonly GeofencePermission[] = [
  "always",
  "whenInUse",
  "denied",
  "restricted",
  "notDetermined",
];

const toPermission = (value: unknown): GeofencePermission =>
  PERMISSIONS.find((permission) => permission === value) ?? "restricted";

const ANDROID_GRANTED = PermissionsAndroid.RESULTS.GRANTED;

const toNative = ({
  id,
  latitude,
  longitude,
  radius,
  notification,
}: GeofenceRegion): NativeRegion => ({
  id,
  latitude,
  longitude,
  radius,
  title: notification.title,
  body: notification.body,
});

/**
 * Checks whether the device can monitor regions at all.
 *
 * iOS reports CoreLocation region-monitoring availability. Android reports
 * whether Google Play Services is installed and up to date.
 *
 * @returns `true` when regions can be registered on this device, `false`
 *   otherwise or on any error.
 */
export const isSupported = (): Promise<boolean> =>
  NativeGeofence.isSupported().catch(() => false);

/**
 * Reads the current location permission without prompting.
 *
 * @returns The permission the operating system reports, or `'restricted'` on
 *   any error.
 */
export const getPermission = (): Promise<GeofencePermission> =>
  NativeGeofence.getPermission()
    .then(toPermission)
    .catch(() => "restricted");

/**
 * Requests the location permission monitoring needs, in the order each
 * platform demands.
 *
 * On iOS a call shows at most one prompt: "When In Use" when nothing has been
 * asked yet, or "Always" on a later call once "When In Use" is held. iOS shows
 * each prompt only once, so repeat calls are silent. On Android the call runs
 * the full sequence: notifications on Android 13 and later, then fine
 * location, then background location ("Allow all the time"), which the system
 * only offers once fine location is granted.
 *
 * @returns The permission held after the prompts. Only `'always'` fires with
 *   the app closed.
 */
export const requestPermission = async (): Promise<GeofencePermission> => {
  if (Platform.OS !== "android") {
    return NativeGeofence.requestPermission()
      .then(toPermission)
      .catch(() => "restricted");
  }

  if (Number(Platform.Version) >= 33) {
    await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
    ).catch(() => null);
  }

  const fine = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
  ).catch(() => null);

  if (fine !== ANDROID_GRANTED) {
    return "denied";
  }
  if (Number(Platform.Version) < 29) {
    return "always";
  }

  const background = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION,
  ).catch(() => null);

  return background === ANDROID_GRANTED ? "always" : "whenInUse";
};

/**
 * Replaces the set of monitored regions.
 *
 * The notification copy is stored natively with each region, so a relaunched
 * process can show it without JavaScript. Passing a set identical to the one
 * already registered is a no-op, which matters because re-registering makes
 * the operating system re-evaluate a region the device may already be inside.
 * Pass an empty array to stop monitoring everything.
 *
 * @param regions - The places to watch, each with the notification to show on
 *   arrival. iOS keeps the first 20 and Android the first 100.
 * @returns The ids actually registered, in the order given. Empty when the
 *   location permission is missing or registration failed.
 *
 * @example
 * ```ts
 * await setRegions([
 *   {
 *     id: 'store-1',
 *     latitude: 40.758,
 *     longitude: -73.985,
 *     radius: 161,
 *     notification: {
 *       title: 'Hello Midtown',
 *       body: 'Your usual is ready in two minutes.',
 *     },
 *   },
 * ]);
 * ```
 */
export const setRegions = (regions: GeofenceRegion[]): Promise<string[]> =>
  NativeGeofence.setRegions(regions.map(toNative)).catch(() => []);

/**
 * Stops monitoring every region this library registered.
 *
 * Logged arrivals are kept. Call {@link clearEvents} to drop those as well.
 *
 * @returns `true` once monitoring has stopped, `false` on any error.
 */
export const clearRegions = (): Promise<boolean> =>
  NativeGeofence.clearRegions().catch(() => false);

/**
 * Reads the arrivals the operating system showed a notification for since
 * they were last cleared.
 *
 * The native layer keeps the log on disk, so it survives the app being
 * closed. It holds at most 500 arrivals; beyond that the oldest are dropped.
 *
 * @returns The arrivals in the order they happened, oldest first. Empty on
 *   any error.
 */
export const getEvents = (): Promise<GeofenceEvent[]> =>
  NativeGeofence.getEvents().catch(() => []);

/**
 * Drops the oldest arrivals from the log.
 *
 * Call it once the arrivals have been handled, for example after a server has
 * accepted them, so anything logged in the meantime stays queued behind them.
 *
 * @param count - How many of the oldest arrivals to drop. Defaults to all of
 *   them.
 * @returns `true` once the log has been trimmed, `false` on any error.
 */
export const clearEvents = (
  count: number = Number.MAX_SAFE_INTEGER,
): Promise<boolean> => NativeGeofence.clearEvents(count).catch(() => false);
