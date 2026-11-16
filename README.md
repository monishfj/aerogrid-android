# AeroGrid — native Android waypoint & photogrammetry planner for DJI Fly

Kotlin · Jetpack Compose (Material 3) · MapLibre Native · JTS · Room · Coroutines/StateFlow. Package `com.aerogrid.app`, minSdk 26, targetSdk 35.

## Build
Requires JDK 17+ and an Android SDK with platform 36 (set `sdk.dir` in `local.properties`).
```
gradlew :app:testDebugUnitTest          # 40 JVM tests (engine, WPML/KMZ, formats, backup, transfer recovery, terrain)
gradlew :app:connectedDebugAndroidTest  # Room persistence test (device/emulator)
gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
```
Release signing reads `keystore.properties` in the project root (not committed):
```
storeFile=app/your-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```
Without it the release APK is built unsigned. Prebuilt APKs are attached to GitHub Releases.

## Layout
- `engine/` — local projection, photogrammetry, grid/corridor/perimeter generation with safe routing around exclusions, statistics, splitting, validation, simulation, terrain (SRTM `.hgt`, ESRI `.asc`).
- `kmz/` — DJI WPML writer (`wpmz/template.kml` + `wpmz/waylines.wpml`), reader and validator.
- `io/` — KML/KMZ/GeoJSON/GPX/CSV import/export, PDF/PNG reports, AeroGrid backup.
- `transfer/` — transfer engine (backup → write → SHA-256 verify → automatic restore), Shizuku shell access, SAF (Android ≤12L), Downloads/share.
- `ui/` — Dashboard, Quick/Advanced planner, Mission Library, Transfer Manager, Settings.
- `tools/djifly_transfer.ps1` — Windows/adb placeholder replacement with backup and hash verification.

## DJI Fly notes
- Default drone ID is `droneEnumValue 68 / sub 0` with namespace `http://www.uav.com/wpmz/1.0.2` (community-observed DJI Fly values, not official). Import a mission saved by DJI Fly on your aircraft in **Settings › Import DJI Fly reference KMZ** to copy the exact identifiers.
- Timed (interval) photography must be started manually in DJI Fly; the KMZ does not start it.
- Heights are written relative to takeoff. In terrain mode with a DEM, per-waypoint heights are pre-computed — this is not live terrain following.
- Always inspect the mission in DJI Fly before flight. Routes are not guaranteed collision-free.
