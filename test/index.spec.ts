import { PermissionsAndroid, Platform } from "react-native";

jest.mock("../src/NativePerdiemGeofence", () => ({
	__esModule: true,
	default: {
		isSupported: jest.fn(),
		getPermission: jest.fn(),
		requestPermission: jest.fn(),
		setRegions: jest.fn(),
		clearRegions: jest.fn(),
		getEvents: jest.fn(),
		clearEvents: jest.fn(),
	},
}));

import {
	clearEvents,
	clearRegions,
	getEvents,
	getPermission,
	isSupported,
	requestPermission,
	setRegions,
} from "../src/index";
import NativeGeofence from "../src/NativePerdiemGeofence";

const native = NativeGeofence as jest.Mocked<typeof NativeGeofence>;

const REGION = {
	id: "store-1",
	latitude: 40.758,
	longitude: -73.985,
	radius: 161,
	notification: { title: "Hello Midtown", body: "Your usual is ready." },
};

const rejected = () => Promise.reject(new Error("native failure"));

const platformOS = Object.getOwnPropertyDescriptor(Platform, "OS");
const platformVersion = Object.getOwnPropertyDescriptor(Platform, "Version");

const onAndroid = (version: number) => {
	Object.defineProperty(Platform, "OS", { value: "android", configurable: true });
	Object.defineProperty(Platform, "Version", { get: () => version, configurable: true });
};

describe("@perdieminc/react-native-geofence", () => {
	beforeEach(() => {
		jest.clearAllMocks();
	});

	afterEach(() => {
		jest.restoreAllMocks();
		if (platformOS) Object.defineProperty(Platform, "OS", platformOS);
		if (platformVersion) Object.defineProperty(Platform, "Version", platformVersion);
	});

	describe("isSupported", () => {
		it("reports what the native layer says", async () => {
			native.isSupported.mockResolvedValue(true);
			await expect(isSupported()).resolves.toBe(true);
		});

		it("is false when the native call fails", async () => {
			native.isSupported.mockImplementation(rejected);
			await expect(isSupported()).resolves.toBe(false);
		});
	});

	describe("getPermission", () => {
		it("passes a known value through", async () => {
			native.getPermission.mockResolvedValue("whenInUse");
			await expect(getPermission()).resolves.toBe("whenInUse");
		});

		it("treats an unknown value as restricted", async () => {
			native.getPermission.mockResolvedValue("something-new");
			await expect(getPermission()).resolves.toBe("restricted");
		});

		it("is restricted when the native call fails", async () => {
			native.getPermission.mockImplementation(rejected);
			await expect(getPermission()).resolves.toBe("restricted");
		});
	});

	describe("requestPermission", () => {
		it("asks the native layer on iOS", async () => {
			native.requestPermission.mockResolvedValue("always");
			await expect(requestPermission()).resolves.toBe("always");
			expect(native.requestPermission).toHaveBeenCalledTimes(1);
		});

		it("asks for notifications, fine location, then background location on Android 13+", async () => {
			onAndroid(34);
			const request = jest.spyOn(PermissionsAndroid, "request").mockResolvedValue(PermissionsAndroid.RESULTS.GRANTED);

			await expect(requestPermission()).resolves.toBe("always");

			expect(request.mock.calls.map(([permission]) => permission)).toEqual([
				PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
				PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
				PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION,
			]);
			expect(native.requestPermission).not.toHaveBeenCalled();
		});

		it("stops at denied when fine location is refused", async () => {
			onAndroid(34);
			const request = jest
				.spyOn(PermissionsAndroid, "request")
				.mockResolvedValueOnce(PermissionsAndroid.RESULTS.GRANTED)
				.mockResolvedValueOnce(PermissionsAndroid.RESULTS.DENIED);

			await expect(requestPermission()).resolves.toBe("denied");
			expect(request).toHaveBeenCalledTimes(2);
		});

		it("reports whenInUse when background location is refused", async () => {
			onAndroid(34);
			jest
				.spyOn(PermissionsAndroid, "request")
				.mockResolvedValueOnce(PermissionsAndroid.RESULTS.GRANTED)
				.mockResolvedValueOnce(PermissionsAndroid.RESULTS.GRANTED)
				.mockResolvedValueOnce(PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN);

			await expect(requestPermission()).resolves.toBe("whenInUse");
		});

		it("needs only fine location before Android 10", async () => {
			onAndroid(28);
			const request = jest.spyOn(PermissionsAndroid, "request").mockResolvedValue(PermissionsAndroid.RESULTS.GRANTED);

			await expect(requestPermission()).resolves.toBe("always");
			expect(request.mock.calls.map(([permission]) => permission)).toEqual([
				PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
			]);
		});
	});

	describe("setRegions", () => {
		it("flattens the notification into the native shape and returns the ids armed", async () => {
			native.setRegions.mockResolvedValue(["store-1"]);

			await expect(setRegions([REGION])).resolves.toEqual(["store-1"]);
			expect(native.setRegions).toHaveBeenCalledWith([
				{
					id: "store-1",
					latitude: 40.758,
					longitude: -73.985,
					radius: 161,
					title: "Hello Midtown",
					body: "Your usual is ready.",
				},
			]);
		});

		it("arms nothing when the native call fails", async () => {
			native.setRegions.mockImplementation(rejected);
			await expect(setRegions([REGION])).resolves.toEqual([]);
		});
	});

	describe("clearRegions", () => {
		it("reports success from the native layer", async () => {
			native.clearRegions.mockResolvedValue(true);
			await expect(clearRegions()).resolves.toBe(true);
		});

		it("is false when the native call fails", async () => {
			native.clearRegions.mockImplementation(rejected);
			await expect(clearRegions()).resolves.toBe(false);
		});
	});

	describe("getEvents", () => {
		it("returns the arrivals the native layer logged", async () => {
			const events = [{ id: "store-1", enteredAt: "2026-09-09T21:05:35.000Z" }];
			native.getEvents.mockResolvedValue(events);
			await expect(getEvents()).resolves.toEqual(events);
		});

		it("is empty when the native call fails", async () => {
			native.getEvents.mockImplementation(rejected);
			await expect(getEvents()).resolves.toEqual([]);
		});
	});

	describe("clearEvents", () => {
		it("drops the given number of arrivals", async () => {
			native.clearEvents.mockResolvedValue(true);
			await expect(clearEvents(3)).resolves.toBe(true);
			expect(native.clearEvents).toHaveBeenCalledWith(3);
		});

		it("drops everything by default", async () => {
			native.clearEvents.mockResolvedValue(true);
			await clearEvents();
			expect(native.clearEvents).toHaveBeenCalledWith(Number.MAX_SAFE_INTEGER);
		});

		it("is false when the native call fails", async () => {
			native.clearEvents.mockImplementation(rejected);
			await expect(clearEvents(1)).resolves.toBe(false);
		});
	});
});
