<p align="center">
  <img src="docs/banner.svg" alt="AeroGrid — drone mapping missions for DJI Fly" width="100%">
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="MapLibre" src="https://img.shields.io/badge/Maps-MapLibre%20Native-396CB2">
  <img alt="DJI Fly KMZ" src="https://img.shields.io/badge/Export-DJI%20Fly%20KMZ-FFB300">
  <img alt="Tests" src="https://img.shields.io/badge/tests-44%20passing-2E7D32">
</p>

<p align="center">
  <b>Draw an area. Get a mapping grid. Fly it with DJI Fly.</b><br>
  A native Android planner for photogrammetry and orthomosaic missions on the <b>DJI Mini 5 Pro</b>,<br>
  with live overlap checks, a 3D preview of the flight and a safe hand-off into DJI Fly.
</p>

<p align="center">
  <img src="docs/screenshots/hero-3d.jpg" alt="AeroGrid 3D view: survey grid floating at 50 m with waypoint pillars and the camera footprint column" width="88%">
  <br><sub>3D view over Tavua, Fiji — the grid floats at flight height, pillars drop to the ground, and the yellow column is the camera's field of view showing photo overlap.</sub>
</p>

---

## ✈️ Three ways to plan

| ⚡ **Quick** | 📍 **Manual** | 🛠️ **Advanced** |
|:---:|:---:|:---:|
| <img src="docs/screenshots/quick-grid.jpg" width="240" alt="Quick mode with generated grid"> | <img src="docs/screenshots/manual-mode.jpg" width="240" alt="Manual mode with waypoint cards"> | <img src="docs/screenshots/advanced-mode.jpg" width="240" alt="Advanced mode with tool chips and tabs"> |
| Draw a boundary, tap **Generate**, transfer. Height, line spacing, overlap and speed sit on one-tap chips — everything else is automatic. | Plot your own waypoints by tapping the map, tune each one (height, speed, gimbal, heading, actions), save a DJI-compatible KMZ. | Every parameter: patterns, exclusion zones, corridors, terrain, camera sensor, waypoint table, mission blocks and all export formats. |

All three modes edit **the same mission** — switch at any time without losing a single waypoint or height override.

## 🧭 From boundary to DJI Fly

```mermaid
flowchart LR
    A([Draw boundary]) --> B[Generate grid]
    B --> C{Mission check}
    C -- overlap / limits OK --> D[Preview in 2D / 3D]
    C -- warning --> E[One-tap fix<br/>e.g. 'Use 18 m']
    E --> B
    D --> F[Export DJI KMZ]
    F --> G[[Transfer to DJI Fly<br/>backup → write → verify]]
    G --> H([Review in DJI Fly & fly])
```

## 📸 Tour

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/dashboard.jpg" width="200" alt="Dashboard"><br><sub><b>Dashboard</b><br>pick a mode or a recent mission</sub></td>
    <td align="center"><img src="docs/screenshots/draw-boundary.jpg" width="200" alt="Drawing a boundary"><br><sub><b>Draw</b><br>tap or freehand, live area</sub></td>
    <td align="center"><img src="docs/screenshots/quick-grid.jpg" width="200" alt="Generated grid"><br><sub><b>Generate</b><br>optimised lawnmower grid</sub></td>
    <td align="center"><img src="docs/screenshots/line-spacing-warning.jpg" width="200" alt="Line spacing scrubber with overlap warning"><br><sub><b>Smart spacing</b><br>overlap rating + one-tap fix</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/3d-view.jpg" width="200" alt="3D view"><br><sub><b>3D view</b><br>route at real height</sub></td>
    <td align="center"><img src="docs/screenshots/3d-flight-preview.jpg" width="200" alt="3D flight simulation"><br><sub><b>Flight preview</b><br>simulated drone & photos</sub></td>
    <td align="center"><img src="docs/screenshots/waypoint-editor.jpg" width="200" alt="Waypoint editor"><br><sub><b>Edit a waypoint</b><br>individual heights kept</sub></td>
    <td align="center"><img src="docs/screenshots/transfer-manager.jpg" width="200" alt="Transfer manager"><br><sub><b>Transfer</b><br>validated KMZ per mission block</sub></td>
  </tr>
</table>

## ✨ Highlights

**Planning that thinks about the photos**
- 📐 Real photogrammetry maths for the DJI Mini 5 Pro (1″ 50 MP, 84° DFOV, 4:3): footprint, GSD, photo & line spacing, required speed for your overlap.
- 🎚️ **Line spacing** in metres with a live rating — 🟢 good · 🟡 acceptable · 🟠 low · 🔴 gaps — and a one-tap "use recommended" fix.
- 🧩 Grids clip to any concave boundary, route **around** exclusion zones (never through them), and split automatically into battery-sized missions under the 200-waypoint limit.
- 🏔️ Optional terrain from SRTM `.hgt` / ESRI `.asc` DEMs: per-waypoint heights for true above-ground altitude, terrain profile and clearance warnings.

**A map-first interface**
- 🗺️ Satellite, street and hybrid maps (MapLibre), offline street maps, location search, GPS.
- 🧊 **3D view** — elevated route ribbons, waypoint pillars, boundary walls, the camera's field-of-view pyramid and overlapping ground footprints.
- 🎛️ **Mission dock** — stat strip with an issues badge, a workflow rail that highlights your next step, and parameter chips with a drag-to-scrub ruler and haptic ticks.
- ↩️ Undo/redo everything, drag waypoints and vertices, multi-select and batch edit.

**Getting it into DJI Fly**
- 📦 Generates DJI WPML missions (`wpmz/template.kml` + `wpmz/waylines.wpml`) and validates every file before export.
- 🔁 **Transfer Manager** — with [Shizuku](https://shizuku.rikka.app/) (no root) it backs up your DJI Fly placeholder, writes the mission, verifies the SHA-256 and restores the original automatically if anything fails.
- 📤 Or save to Downloads / share, or use the included Windows script ([`tools/djifly_transfer.ps1`](tools/djifly_transfer.ps1)) over USB.
- 📄 Also exports KML, GeoJSON, GPX, CSV, PNG and a PDF mission summary; imports KML/KMZ/GeoJSON/GPX and existing DJI missions.

## 📲 Install

1. Open the latest **[Release](../../releases/latest)** on your phone and download `AeroGrid.apk`.
2. Open it and allow installs from your browser or Files app.
3. *(Optional, for direct transfer)* install **Shizuku**, start it via *Developer options › Wireless debugging*, then authorise AeroGrid in the Transfer Manager.

> [!WARNING]
> **Check every mission in DJI Fly before flight.** The KMZ format is not officially documented by DJI and AeroGrid has not yet been verified on every DJI Fly version. The aircraft ID defaults to `68 / 0` (community-observed); import a mission saved by your own DJI Fly under **Settings › Import DJI Fly reference KMZ** to copy the exact values. Timed photos must be started manually in DJI Fly. Routes are **not** guaranteed collision-free — you are responsible for airspace rules, obstacles and terrain.

## 🛠️ Build from source

Requires **JDK 17+** and an Android SDK with **platform 36** (`sdk.dir` in `local.properties`).

```bash
./gradlew :app:testDebugUnitTest          # 44 JVM tests
./gradlew :app:connectedDebugAndroidTest  # Room persistence test (device/emulator)
./gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
```

Release signing reads `keystore.properties` from the project root (never committed). Without it the release APK is built unsigned.

```properties
storeFile=app/your-key.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## 🧱 How it's built

```mermaid
flowchart TB
    UI["UI · Jetpack Compose<br/>Dashboard · Quick / Manual / Advanced · Library · Transfer · Settings"]
    VM["PlannerViewModel<br/>StateFlow · undo/redo · autosave"]
    ENG["engine/<br/>projection · photogrammetry · grid & safe routing (JTS)<br/>splitting · validation · simulation · terrain"]
    KMZ["kmz/<br/>DJI WPML writer · reader · validator"]
    IO["io/<br/>KML · GeoJSON · GPX · CSV · PDF · backup"]
    TR["transfer/<br/>backup → write → verify → restore<br/>Shizuku · SAF · Downloads"]
    DB[("Room<br/>mission library")]
    MAP["MapLibre Native<br/>2D layers + 3D fill-extrusions"]
    UI --> VM --> ENG
    VM --> KMZ & IO & TR & DB
    UI --> MAP
```

| Layer | Tech |
|---|---|
| Language & UI | Kotlin 2.0, Jetpack Compose, Material 3 (dynamic colour, light/dark) |
| Maps | MapLibre Native 11 — OpenFreeMap streets, Esri World Imagery, offline regions |
| Geometry | JTS Topology Suite — clipping, buffering, visibility-graph routing |
| Storage | Room + kotlinx.serialization |
| DJI transfer | Shizuku API, Storage Access Framework, MediaStore |
| Build | Gradle 8.13, AGP 8.13, R8 shrinking (25 MB APK, arm64 + x86_64) |

<details>
<summary><b>📁 Project layout</b></summary>

```
app/src/main/java/com/aerogrid/app/
├── engine/     Local projection, photogrammetry, RoutePlanner, splitting, validation, simulation, terrain
├── kmz/        DJI WPML writer, reader and validator
├── io/         KML/KMZ/GeoJSON/GPX/CSV import/export, PDF/PNG reports, backups
├── transfer/   TransferEngine + Shizuku, SAF and Downloads implementations
├── data/       Room database, repository, settings
└── ui/         Compose screens, mission dock, MapController, 3D scene builder
app/src/test/   44 unit tests (geometry, grids, exclusions, overlap, WPML, formats, transfer recovery, terrain)
tools/          djifly_transfer.ps1 — USB placeholder replacement with backup + hash check
```
</details>

## 🙏 Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors via [OpenFreeMap](https://openfreemap.org/) · Imagery © Esri, Maxar, Earthstar Geographics · Built with [MapLibre Native](https://maplibre.org/), [JTS](https://github.com/locationtech/jts) and [Shizuku](https://github.com/RikkaApps/Shizuku). DJI, DJI Fly and DJI Mini are trademarks of SZ DJI Technology Co., Ltd.; AeroGrid is an independent project and is not affiliated with DJI.
