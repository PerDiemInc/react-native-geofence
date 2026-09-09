import type { TurboModule } from "react-native";
import { TurboModuleRegistry } from "react-native";


export type NativeRegion = {
  id: string;
  latitude: number;
  longitude: number;
  radius: number;
  title: string;
  body: string;
};

export type NativeEvent = {
  id: string;
  enteredAt: string;
};

export interface Spec extends TurboModule {
  isSupported(): Promise<boolean>;

  getPermission(): Promise<string>;

  requestPermission(): Promise<string>;

  setRegions(regions: Array<NativeRegion>): Promise<string[]>;

  clearRegions(): Promise<boolean>;

  getEvents(): Promise<Array<NativeEvent>>;

  clearEvents(count: number): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>("PerdiemGeofence");
