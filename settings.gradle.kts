// Bootstrap once from the existing v0.3 Cart archive, then attach Sensor Scout.
// The workflow's existing :app build remains unchanged; original Cart UI and trip data preserved.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
val integrate = ProcessBuilder("python3", "integrate_sensor_scout.py")
    .directory(settingsDir).inheritIO().start()
check(integrate.waitFor() == 0) { "RNR sensor integration failed; no APK produced" }
rootProject.name = "RustNRum-Cart-app"
include(":app")
