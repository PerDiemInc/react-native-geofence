# React Native Geofence

[![CI](https://github.com/PerDiemInc/react-native-geofence/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/PerDiemInc/react-native-geofence/actions/workflows/ci.yml)

Register places with a notification. The operating system watches them and
shows the notification when the device arrives, with the app closed, and hands
the arrivals back to JavaScript the next time the app opens.

New architecture only (TurboModule). Autolinked on both platforms.

## Installation

```bash
npm install @perdieminc/react-native-geofence
cd ios && pod install
```

Add the location usage strings to `Info.plist`. Monitoring only survives
backgrounding with **Always**. No background mode is required, because region
monitoring is not continuous location.

```xml
<key>NSLocationWhenInUseUsageDescription</key><string>…</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key><string>…</string>
```

Android needs nothing: the permissions and receivers are declared in the
library's manifest and merged into the app. `ACCESS_BACKGROUND_LOCATION` is
what lets a fence fire once the app is closed, and Google Play gates it behind
a location permissions declaration in Play Console.

## Usage

```typescript
import {
  clearEvents,
  getEvents,
  requestPermission,
  setRegions,
} from '@perdieminc/react-native-geofence';

// On app open: hand the arrivals shown while the app was away to your server,
// then drop exactly what it accepted.
const events = await getEvents();
await api.report(events.slice(0, 50));
await clearEvents(Math.min(events.length, 50));

// Then register the places to watch. Copy is shown verbatim, so substitute
// any placeholders first. Registering the same set again is a no-op.
await requestPermission();
await setRegions([
  {
    id: 'store-1',
    latitude: 40.758,
    longitude: -73.985,
    radius: 161,
    notification: { title: 'Hello Midtown', body: 'Your usual is ready in two minutes.' },
  },
]);
```

## How it works

JavaScript timers are suspended when a React Native app is backgrounded, and
the process is gone once it is closed. Real geofencing has to be registered with
the OS so *it* wakes the app, and in that window the JavaScript runtime may not
be up. So the native layer does the whole hot path itself: detect the arrival,
show the notification it was given, append the arrival to a log on disk.
JavaScript reads that log later.

| | iOS | Android |
| --- | --- | --- |
| API | `CLLocationManager` region monitoring | `GeofencingClient` + `PendingIntent` |
| Wakes a terminated app | yes | yes |
| Region limit | 20 | 100 |
| Survives reboot | yes | re-armed from disk by a boot receiver |
| Foreground service | no | no |

Arrivals while the app is in the foreground are ignored: the user is in the
app already. A second arrival at the same region within five minutes is the
same arrival. Only `always` permission fires with the app closed.

## API

Every function catches internally and resolves to a safe default, so a
notification feature can never crash the host app.

### `isSupported()`

Whether the device can monitor regions: CoreLocation availability on iOS,
Google Play Services on Android. Resolves `false` on any error.

### `getPermission()`

The current location permission without prompting: `'always'`, `'whenInUse'`,
`'denied'`, `'restricted'` or `'notDetermined'`. Resolves `'restricted'` on any
error.

### `requestPermission()`

Requests the permission monitoring needs, in the order each platform demands.

- iOS shows at most one prompt per call: "When In Use" when nothing has been
  asked yet, then "Always" on a later call. Each prompt is shown once.
- Android runs the full sequence: notifications on Android 13 and later, then
  fine location, then background location ("Allow all the time"), which the
  system only offers once fine location is granted.

Resolves the permission held afterwards.

### `setRegions(regions)`

Replaces the monitored set. Each region:

- `id`: your identifier, echoed back in every event
- `latitude`, `longitude`: the centre, in decimal degrees
- `radius`: metres; values under 100 are raised to 100
- `notification`: `{ title, body }`, shown verbatim on arrival

Resolves the ids actually registered. iOS keeps the first 20 regions and Android
the first 100. Pass an empty array to stop monitoring everything.

### `clearRegions()`

Stops monitoring everything this library registered. Logged arrivals are kept.

### `getEvents()`

The arrivals the OS showed a notification for since they were last cleared,
oldest first, as `{ id, enteredAt }` with an ISO-8601 UTC timestamp. The log is
capped at 500 entries.

### `clearEvents(count?)`

Drops the oldest `count` arrivals, or all of them when omitted. Call it once
they have been handled, so anything logged in the meantime stays queued.

## Development

```bash
npm install
npm run lint
npm run typecheck
npm test
```

Native code lives in `ios/` and `android/` and is compiled by the host app.
Link the package into an app with `npm install ../react-native-geofence` and
add the checkout to Metro's `watchFolders`.

## Releasing

Bump with `npm version <patch|minor|major>` and push with tags. The publish
workflow runs on any `v*` tag, checks the tag against `package.json`, builds,
tests and publishes to npm through the `npm-publish` environment.
