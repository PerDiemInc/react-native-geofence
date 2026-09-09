import CoreLocation
import Foundation
import UIKit
import UserNotifications
import os

/// OS-level region monitoring that notifies with the app closed.
///
/// iOS relaunches a terminated app to deliver a region crossing, and the React
/// Native runtime is not guaranteed to be up in that window. So everything an
/// arrival needs lives here and in UserDefaults: the notification to show, and
/// the log of what was shown. JavaScript reads that log on the next open.
///
/// Every entry point runs on the main thread — the module's method queue is
/// main, and CoreLocation delivers there because the manager was created there.
@objc(GeofenceMonitor)
public final class GeofenceMonitor: NSObject {

  @objc public static let shared = GeofenceMonitor()

  /// iOS monitors at most this many regions per app.
  private static let maxRegions = 20

  /// One physical arrival can surface as more than one OS event. A second
  /// arrival at the same region inside this window is the same arrival.
  private static let repeatWindow: TimeInterval = 5 * 60

  /// A device whose app is never reopened must not grow its log without bound.
  private static let maxEvents = 500

  private static let iso8601: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter
  }()

  private struct Region: Codable, Equatable {
    let id: String
    let latitude: Double
    let longitude: Double
    let radius: Double
    let title: String
    let body: String

    init?(_ raw: [String: Any]) {
      guard
        let id = raw["id"] as? String, !id.isEmpty,
        let latitude = raw["latitude"] as? Double,
        let longitude = raw["longitude"] as? Double,
        let radius = raw["radius"] as? Double,
        let title = raw["title"] as? String,
        let body = raw["body"] as? String
      else { return nil }

      self.id = id
      self.latitude = latitude
      self.longitude = longitude
      self.radius = radius
      self.title = title
      self.body = body
    }
  }

  private struct Event: Codable {
    let id: String
    let enteredAt: String
  }

  private enum Key {
    static let regions = "perdiem.geofence.regions"
    static let events = "perdiem.geofence.events"
    static let lastEntered = "perdiem.geofence.lastEntered"
    static let regionPrefix = "perdiem.geofence."
  }

  private let manager = CLLocationManager()
  private let defaults = UserDefaults.standard
  private let lock = NSLock()
  private let log = Logger(subsystem: "com.perdiem.geofence", category: "monitor")

  private override init() {
    super.init()
    manager.delegate = self
  }

  /// Attaches the delegate. Called at module init so it is in place before iOS
  /// can deliver an event to a process it relaunched for that purpose.
  @objc public func bootstrap() {}

  // MARK: - Permission

  @objc public var permission: String {
    switch manager.authorizationStatus {
    case .authorizedAlways: return "always"
    case .authorizedWhenInUse: return "whenInUse"
    case .denied: return "denied"
    case .restricted: return "restricted"
    default: return "notDetermined"
    }
  }

  /// Monitoring only survives backgrounding with "Always". iOS requires asking
  /// for "When In Use" first and escalating, and shows each prompt once — a
  /// repeat call is a silent no-op, never an error.
  @objc public func requestPermission() {
    switch manager.authorizationStatus {
    case .notDetermined: manager.requestWhenInUseAuthorization()
    case .authorizedWhenInUse: manager.requestAlwaysAuthorization()
    default: break
    }
  }

  @objc public var isMonitoringAvailable: Bool {
    CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self)
  }

  // MARK: - Regions

  /// Replace the monitored set.
  ///
  /// Restarting monitoring makes iOS re-evaluate a region the device may be
  /// inside, so an unchanged set the OS still knows about is left alone.
  ///
  /// Returns what it registered rather than reading `monitoredRegions` back:
  /// `startMonitoring(for:)` is asynchronous, so an immediate read under-reports.
  @objc public func setRegions(_ regions: [[String: Any]]) -> [String] {
    let next = regions.prefix(Self.maxRegions).compactMap(Region.init)
    let ids = next.map(\.id)

    if next == storedRegions(), monitoredIds() == Set(ids) {
      return ids
    }

    stopMonitoring()

    for region in next {
      let circle = CLCircularRegion(
        center: CLLocationCoordinate2D(latitude: region.latitude, longitude: region.longitude),
        // Below the device's own accuracy floor a region never fires reliably.
        radius: max(region.radius, 100),
        identifier: Key.regionPrefix + region.id
      )
      circle.notifyOnEntry = true
      circle.notifyOnExit = false

      manager.startMonitoring(for: circle)
    }

    store(regions: next)

    // This feature is invisible when it fails, so say what was actually armed.
    log.info("armed \(next.count, privacy: .public) region(s)")

    return ids
  }

  @objc public func clearRegions() {
    stopMonitoring()
    store(regions: [])
  }

  private func stopMonitoring() {
    for region in manager.monitoredRegions where region.identifier.hasPrefix(Key.regionPrefix) {
      manager.stopMonitoring(for: region)
    }
  }

  private func monitoredIds() -> Set<String> {
    Set(manager.monitoredRegions.compactMap { region in
      region.identifier.hasPrefix(Key.regionPrefix)
        ? String(region.identifier.dropFirst(Key.regionPrefix.count))
        : nil
    })
  }

  // MARK: - Events

  @objc public func events() -> [[String: String]] {
    lock.withLock {
      storedEvents().map { ["id": $0.id, "enteredAt": $0.enteredAt] }
    }
  }

  @objc public func clearEvents(_ count: Int) {
    lock.withLock {
      var remaining = storedEvents()
      remaining.removeFirst(min(max(count, 0), remaining.count))
      store(events: remaining)
    }
  }

  private func append(_ event: Event) {
    lock.withLock {
      var all = storedEvents()
      all.append(event)
      if all.count > Self.maxEvents {
        all.removeFirst(all.count - Self.maxEvents)
      }
      store(events: all)
    }
  }

  /// True when the same region was entered inside the repeat window; records
  /// the arrival otherwise.
  private func isRepeat(id: String, at now: Date) -> Bool {
    lock.withLock {
      var last = defaults.dictionary(forKey: Key.lastEntered) as? [String: Double] ?? [:]
      let stamp = now.timeIntervalSince1970

      if let previous = last[id], stamp - previous < Self.repeatWindow {
        return true
      }

      last[id] = stamp
      defaults.set(last, forKey: Key.lastEntered)
      return false
    }
  }

  // MARK: - Arrival

  fileprivate func didEnter(id: String) {
    guard let region = storedRegions().first(where: { $0.id == id }) else {
      log.info("entered \(id, privacy: .public) but it is no longer armed")
      return
    }

    // The user is in the app already; a banner would only be noise, and
    // recording it would count a notification that was never shown.
    if UIApplication.shared.applicationState == .active {
      log.info("entered \(id, privacy: .public) with the app in front; nothing to show")
      return
    }

    let now = Date()

    if isRepeat(id: id, at: now) {
      log.info("entered \(id, privacy: .public) again inside the repeat window")
      return
    }

    // A relaunched app gets only a few seconds; hold a background task so the
    // notification lands before the process is suspended.
    var task = UIBackgroundTaskIdentifier.invalid
    task = UIApplication.shared.beginBackgroundTask(withName: "perdiem-geofence") {
      UIApplication.shared.endBackgroundTask(task)
      task = .invalid
    }

    present(region) { [weak self] shown in
      if shown {
        self?.append(Event(id: id, enteredAt: Self.iso8601.string(from: now)))
      }

      if task != .invalid {
        UIApplication.shared.endBackgroundTask(task)
        task = .invalid
      }
    }
  }

  /// Show the region's notification. Only a shown notification is logged, so
  /// the log is a record of what the user actually saw.
  private func present(_ region: Region, completion: @escaping (Bool) -> Void) {
    let center = UNUserNotificationCenter.current()

    center.getNotificationSettings { [weak self] settings in
      guard settings.authorizationStatus == .authorized
        || settings.authorizationStatus == .provisional
      else {
        self?.log.info("notifications not authorized; arrival not shown")
        completion(false)
        return
      }

      let content = UNMutableNotificationContent()
      content.title = region.title
      content.body = region.body
      content.sound = .default
      content.userInfo = ["perdiem_geofence": ["id": region.id]]

      // One identifier per region, so a repeat replaces rather than stacks.
      let request = UNNotificationRequest(
        identifier: Key.regionPrefix + region.id,
        content: content,
        trigger: nil
      )

      center.add(request) { error in
        if let error {
          self?.log.error("notification failed: \(error.localizedDescription, privacy: .public)")
          completion(false)
        } else {
          self?.log.info("notification presented for \(region.id, privacy: .public)")
          completion(true)
        }
      }
    }
  }

  // MARK: - Storage

  private func storedRegions() -> [Region] {
    decode([Region].self, forKey: Key.regions) ?? []
  }

  private func store(regions: [Region]) {
    encode(regions, forKey: Key.regions)
  }

  private func storedEvents() -> [Event] {
    decode([Event].self, forKey: Key.events) ?? []
  }

  private func store(events: [Event]) {
    encode(events, forKey: Key.events)
  }

  private func decode<T: Decodable>(_ type: T.Type, forKey key: String) -> T? {
    guard let data = defaults.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(type, from: data)
  }

  private func encode<T: Encodable>(_ value: T, forKey key: String) {
    if let data = try? JSONEncoder().encode(value) {
      defaults.set(data, forKey: key)
    }
  }
}

// MARK: - CLLocationManagerDelegate

extension GeofenceMonitor: CLLocationManagerDelegate {

  public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
    guard region.identifier.hasPrefix(Key.regionPrefix) else { return }

    let id = String(region.identifier.dropFirst(Key.regionPrefix.count))
    log.info("entered region \(id, privacy: .public)")

    didEnter(id: id)
  }

  public func locationManager(
    _ manager: CLLocationManager,
    monitoringDidFailFor region: CLRegion?,
    withError error: Error
  ) {
    log.error(
      "monitoring failed for \(region?.identifier ?? "-", privacy: .public): \(error.localizedDescription, privacy: .public)"
    )
  }
}
