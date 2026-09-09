package com.perdiem.geofence

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** Autolinked — the host app needs no MainApplication changes. */
class GeofencePackage : BaseReactPackage() {

  override fun getModule(name: String, context: ReactApplicationContext): NativeModule? =
    if (name == GeofenceModule.NAME) GeofenceModule(context) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      GeofenceModule.NAME to
        ReactModuleInfo(
          GeofenceModule.NAME,
          GeofenceModule.NAME,
          false, // canOverrideExistingModule
          false, // needsEagerInit
          false, // isCxxModule
          true, // isTurboModule
        )
    )
  }
}
