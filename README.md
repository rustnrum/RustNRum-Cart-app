# Rust N Rum CART — Android app v0.1.0

Native Android application with a bundled, offline, mobile-first web UI (Android WebView) using the **approved Rust N Rum CART visual assets v1.1**. The phone connects directly to the **Alltrax SR48400** over **USB OTG** and uses its own GPS, trip database and touchscreen. **No laptop, network connection, Python, local HTTP server, or external service is required during use.**

## Build on GitHub (no Android Studio on phone)

This is a standard Android Gradle project for repository `rustnrum/RustNRum-Cart-app`:

- AGP **8.9.2**, Kotlin Android plugin **2.0.21**, Gradle **8.11.1**, Java **17**.
- `compileSdk=35`, `targetSdk=35`, `minSdk=26`; pinned stable Android build tools **35.0.0**.
- `.github/workflows/android.yml` invokes `gradle :app:testDebugUnitTest :app:assembleDebug` on each push to `main` (and via **Run workflow**).
- On success, go to **Actions → latest green Android APK run → Artifacts → RustNRum-Cart-Android-debug** to download the installable APK.
- **The APK cannot be built or validated locally in ChatGPT's current environment** (Android SDK is not installed). The GitHub Actions run is the first real Android compilation test, and any error should be addressed by its exact job log.
- GitHub build uses **no Python scripts, generated-source patches, runtime replacement, or ZIP extraction during Android app runtime**. GitHub Actions itself uses official setup actions.
- **Important for updates:** The initial artifact is a development/debug APK signed with the ephemeral GitHub runner's debug key. Future builds may not install over a prior build. Establish a persistent, properly managed signing key via repository secrets before distributing regular update APKs. Never commit a signing private key.

## Features already implemented in source

| Area | Scope |
|---|---|
| **Drive** | One-button USB connect, controller model, live pack V, motor A, throttle MU/estimated %, controller °F, current fault count, and **phone GPS speed (MPH)**. No fake demo values. |
| **Trips & Battery** | Local SQLite start/finish trip, sampling roughly every 2 seconds, GPS distance, battery start/end/min V, peak **motor** A, full sensor snapshot and fault names per sample, trip history and measured plots. |
| **Throttle** | Read-only stored throttle settings at SR flash address 0x08000860 and read-only curve tables at 0x08000900/940/980. Offline draft/profile notes. Controller **write is disabled** until verified. |
| **Diagnostics** | 15 SR fault flags, raw controller fault names, KSI/relay readouts, restricted **read-only** flash/SRAM memory inspector. |
| **Sensors** | Manufacturer-derived live fields grouped in 8 read blocks, including raw ADC, averaged, controller state, motor output, temperatures, fault history. Nonvalidated fields labeled unverified. |

## USB protocol implementation

- VID `23D4`, PID `0001`, **not** XCT PID `0002`.
- Requires USB Host/OTG support, USB-C/USB-B data cable (or appropriate adapter), and Android permission for the HID device.
- HID **interrupt** endpoints (on the previously tested Linux controller, IN 0x81 / OUT 0x01), 64-byte reports.
- Uses Android [`UsbRequest`](https://developer.android.com/reference/android/hardware/usb/UsbRequest), not `bulkTransfer` for interrupt endpoints.
- Read report: `01 [length] [checksum_le16] [address_be32] [zero padding]`, where checksum sums report ID, count and address bytes. The known SR power read should begin `01 12 84 00 20 00 41 10`.
- Every received report is checked for ID 4, type 1, status 0, and requested payload length.
- Flash/SRAM reads are the **only** USB operations. No configuration write, reset, or firmware command is implemented.
- The USB transport has **not yet been exercised on your exact Android phone**, so first on-device testing may require targeted diagnostics.

## Sensor validity and battery-health caution

- The previously proven SR48400 values were pack voltage (~52.4 V observed), zero stationary motor amps, pedal position, temperature sensors and current fault flags.
- Most additional manufacturer definitions have **not** been validated individually on this controller; the app uses labels `reported`, `unverified`, `unavailable` to distinguish them. An unverified 0 is **not** proof a sensor is working.
- Output motor amps **are not battery amps**. No amp-hour, remaining capacity, or battery health percent is computed without a validated source for battery current. Voltage sag/recovery alone is informative but does not directly yield a capacity percent.
- GPS location requires an Android permission and a location fix. No Internet is required if phone hardware supports GNSS; some devices are poor at standalone fixes.
- Cart controller settings are **read-only**. Staged profile choices are conceptual and not sent to hardware. We need a validated safe write/verify/revert procedure before allowing programming.

## Safety and testing

Do first USB and telemetry checks with the cart parked, the pedal released, and the wheels on the ground. Confirm the controller's overcurrent/shutdown flags before load testing. Do not run the series motor unloaded. Do not change controller mappings while driving.

## App structure

```
app/src/main/java/com/rustnrum/cart/
  MainActivity.kt          Native bridge, USB permissions, GNSS, UI events
  UsbController.kt        Android UsbRequest interrupt I/O
  AlltraxProtocol.kt      Pure Kotlin SR read report framing/validation
  SensorDecoder.kt        SR variables, scaling, full sensor array
  TripDatabase.kt         Native SQLite sessions and samples
app/src/main/assets/
  index.html              Offline five-tab interface
  style.css               Approved visual palette/layout
  app.js                  Live UI logic and charts
  img/                    Approved Android brand and icons
app/src/main/res/         Approved Android vectors, launcher icons and colors
app/src/test/...          Read protocol unit tests
.github/workflows/android.yml  Deterministic Gradle APK build
```

## What is next after the initial green build

1. Install debug APK, grant USB permission, verify SR48400 identification.
2. Check the same known bytes already observed on Linux. Confirm Android asynchronous HID transfer behavior.
3. Check live V, amps, throttle, faults, GPS location and offline persistence.
4. Verify throttle flash fields against the official Toolkit before enabling changes.
5. Establish reproducible app signing, error logging/export, and precise battery-performance metrics.

### No source rewriting on CI

The Actions file only sets up the official toolchain, compiles, runs unit tests and publishes the APK. It never modifies the project or downloads unknown code to replace source.
