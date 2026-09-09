#import <React/RCTBridgeModule.h>

/**
 * TurboModule entry point. Everything it does is forwarded to `GeofenceMonitor`
 * (Swift), which owns the CoreLocation delegate and outlives this object — iOS
 * relaunches a terminated app to deliver a region crossing, and there is no
 * React Native bridge at that moment.
 */
@interface PerdiemGeofence : NSObject <RCTBridgeModule>
@end
