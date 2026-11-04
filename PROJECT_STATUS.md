# Project status — 2026-11-04

- [x] Approved visual assets v1.1 incorporated, corrected launcher icon included.
- [x] Five-tab mobile Drive / Trips / Throttle / Diagnostics / Sensors UI created.
- [x] Native Kotlin USB Host (VID 23D4 PID 0001) read-only transport implemented.
- [x] Native Android GPS speed and trip distance implemented.
- [x] Native SQLite trip logging and charts implemented.
- [x] Read-only throttle flash screens and offline preset notes implemented.
- [x] 15 fault flags and expanded manufacturer-derived sensor table implemented.
- [x] Fixed-version Android Gradle project and GitHub Actions debug APK workflow.
- [x] Pure Kotlin protocol file compiles with the available local Kotlin compiler.
- [x] Offline JavaScript syntax check passes; static assets checked.
- [ ] Android SDK compilation **not performed here**; first actual build is via GitHub Actions.
- [ ] USB Host communication must be validated on a physical Android device.
- [ ] Verify sensor scaling per sensor and stored throttle configuration field offsets.
- [ ] Controller programming/write requires separate safety validation and is disabled.
- [ ] Stable signing key for upgradeable production APKs.


## v0.2.0 — Battery Health logging (2026-11-04)
- Added independent Battery page with 24h/7d/30d charts, min voltage, swing and peak **motor** current.
- Logs controller-reported pack voltage every 10 seconds while USB is connected, even without a trip. SQLite database v2 migrates without deleting prior trips. Retains 30 days.
- User-entered voltage after >=2h rest yields approximate 48V flooded lead-acid state-of-charge. Last recorded reading is explicitly historical, not live SOC. No invented range, battery amp-hours or battery health percentage.
- Full capacity testing requires actual battery current or dedicated capacity test; loaded voltage is not a reliable SOC measurement.
