# RNR Cart App v0.4.0 · Sensors integrated

**This is an upgrade to the existing Cart app, not a separate scanner APK.**

Upload the ZIP itself (do not unzip) at the root of the existing GitHub repository, then click Commit Changes. Existing `.github/workflows/IMPORT.yml` finds `RustNRum-Cart-app-Android-v0.4.0-source.zip`, imports it, and runs the Gradle APK build. During the first Gradle invocation, the included bootstrap loads your already-committed original `RustNRum-Cart-app-Android-v0.3.0-source.zip` and adds read-only Sensor Scout within that app. The original drive screens, trips, images and settings remain intact. It creates an in-app Sensors shortcut if the main Activity matches standard Android patterns, otherwise a second Sensors launcher icon from the same APK.

**Prerequisite:** Leave `RustNRum-Cart-app-Android-v0.3.0-source.zip` in the repository root. Its content supplies the full original app, which is not repeated in this compact upgrade bundle.

**Checks:** The bootstrap intentionally fails if the original ZIP isn't there or its app structure is wrong, rather than quietly publishing a standalone scanner.

No programming, motor, or flash commands are sent by the diagnostic. Unknown fields are labeled unverified; a successful USB packet alone doesn't prove engineering calibration.
