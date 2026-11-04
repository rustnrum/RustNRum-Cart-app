# RNR Cart App v0.5.0 · Sensor Scout USB repair

This upgrades the **existing** RNR Cart app with a scanner that shares the same
working USB controller, interrupt IN+OUT endpoints and single serial executor
as the Drive/trip interface. It does not claim a second USB interface or send
HID SET_REPORT control transfers. No controller configuration writes are made.

## Build / upload
Upload `RustNRum-Cart-app-Android-v0.5.0-source.zip` (whole ZIP, do not extract)
to the existing GitHub repository root, then Commit. The repository's existing
`IMPORT.yml` workflow builds the APK. The original `RustNRum-Cart-app-Android-v0.3.0-source.zip`
must remain in the repository root (the importer uses it as the complete base).

## Operation
Connect to the controller in the main **Drive** screen, then tap **Sensors**
and **Scan known sensors**. Sensor Scout uses that same active session. Do not
unplug or change anything on the cart. Results are labeled plausible, invalid,
raw/unverified or unavailable and may be copied as JSON.

## Confirmed original v0.4.0 failure
All 10 blocks failed at `controlTransfer(... HID SET_REPORT ...)` with -1,
because the controller's verified transport instead uses a 64-byte HID interrupt
OUT endpoint and interrupt IN endpoint. This package routes scanner reads
through the existing `UsbController.read()` on the app's single I/O executor.

## Scope / limitations
Build-tested by the established project GitHub Actions workflow only after
upload. Here synthetic integration and static protocol checks pass, but no
on-cart hardware test has been performed. The scanner cannot infer battery AH
from motor current, and some values remain uncalibrated/unverified.
