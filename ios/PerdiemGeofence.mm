#import "PerdiemGeofence.h"

// Must precede the generated Swift header: it re-declares GeofenceMonitor's
// CLLocationManagerDelegate conformance and cannot import the framework itself.
#import <CoreLocation/CoreLocation.h>
#import <UserNotifications/UserNotifications.h>

#import <RNPerdiemGeofence/RNPerdiemGeofence.h>

#if __has_include("perdiem_react_native_geofence-Swift.h")
#import "perdiem_react_native_geofence-Swift.h"
#else
#import <perdiem_react_native_geofence/perdiem_react_native_geofence-Swift.h>
#endif

@interface PerdiemGeofence () <NativePerdiemGeofenceSpec>
@end

@implementation PerdiemGeofence

RCT_EXPORT_MODULE()

/// The monitor must exist before iOS can deliver a crossing to a freshly
/// relaunched process, so building it here — at module init — is the point.
- (instancetype)init
{
  if (self = [super init]) {
    [GeofenceMonitor.shared bootstrap];
  }
  return self;
}

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

/// CLLocationManager wants the thread it was created on, and that is main.
- (dispatch_queue_t)methodQueue
{
  return dispatch_get_main_queue();
}

- (void)isSupported:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(@(GeofenceMonitor.shared.isMonitoringAvailable));
}

- (void)getPermission:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(GeofenceMonitor.shared.permission);
}

- (void)requestPermission:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [GeofenceMonitor.shared requestPermission];

  // The prompt is asynchronous and iOS shows it at most once; report what we
  // know now and let JS re-read after the user answers.
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)),
                 dispatch_get_main_queue(), ^{
    resolve(GeofenceMonitor.shared.permission);
  });
}

- (void)setRegions:(NSArray *)regions
           resolve:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject
{
  resolve([GeofenceMonitor.shared setRegions:regions]);
}

- (void)clearRegions:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [GeofenceMonitor.shared clearRegions];
  resolve(@YES);
}

- (void)getEvents:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve([GeofenceMonitor.shared events]);
}

- (void)clearEvents:(double)count
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
  [GeofenceMonitor.shared clearEvents:(NSInteger)MIN(count, (double)NSIntegerMax)];
  resolve(@YES);
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativePerdiemGeofenceSpecJSI>(params);
}

@end
