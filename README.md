# @perdieminc/react-native-geofence

Register places with a notification. The OS watches them and shows the
notification when the device arrives — **with the app closed** — and hands the
arrivals back to JavaScript the next time the app opens.

New architecture only (TurboModule). Autolinked: `npm install`, `pod install`,
nothing else on either platform.

```ts
import { requestPermission, setRegions } from '@perdieminc/react-native-geofence';

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

## Why the notification is native

JavaScript timers are suspended when a React Native app is backgrounded, and
the process is gone once it is closed. Real geofencing has to be registered with
the OS so *it* wakes your app, and in that window the JavaScript runtime may not
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
same arrival. Registering the same set twice is a no-op, so calling
`setRegions` on every app open is fine.

## API

| | |
| --- | --- |
| `isSupported()` | region monitoring usable on this device |
| `getPermission()` | `always` · `whenInUse` · `denied` · `restricted` · `notDetermined` |
| `requestPermission()` | one OS prompt per call on iOS (when-in-use, then always); the full Android sequence |
| `setRegions(regions)` | replace the monitored set; returns the ids armed |
| `clearRegions()` | stop monitoring everything this library registered |
| `getEvents()` | `{ id, enteredAt }[]` shown since last cleared, oldest first |
| `clearEvents(count?)` | drop the oldest `count` arrivals, or all of them |

Every function catches internally and resolves to a safe default.

Only `always` fires with the app closed. On Android that is "Allow all the
time", which the OS only offers once foreground location is granted, so
`requestPermission()` asks in that order.

## Permissions

Android: declared in the library's manifest and merged into your app. Nothing to
add. `ACCESS_BACKGROUND_LOCATION` is what lets a fence fire once the app is
closed; Google Play gates it behind a location permissions declaration in Play
Console.

iOS: add the usage strings to `Info.plist`. Monitoring only survives
backgrounding with **Always**.

```xml
<key>NSLocationWhenInUseUsageDescription</key><string>…</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key><string>…</string>
```

No background mode is required: region monitoring is not continuous location.

## Reporting arrivals to a server

The library never calls the network. On app open, read the log, send it, then
clear exactly what was accepted:

```ts
const events = await getEvents();
await api.report(events.slice(0, 50));
await clearEvents(Math.min(events.length, 50));
```

## License

MIT
