# ELRS Telemetry View — Android GCS

A ground control station (GCS) for Android phones and tablets that shows your ExpressLRS
radio's telemetry **on a map**. No MAVLink needed: it reads the CRSF telemetry that the
**TX Backpack** broadcasts over WiFi when **Telemetry = WiFi** is selected.

> [!IMPORTANT]
> **If the backpack WiFi does not come up after selecting "Telemetry = WiFi", update the TX Backpack firmware.**
> This mode does not work on Backpack **1.5.7 and older**; the fix shipped in **1.5.8**
> (Backpack repository, commit `2c863af` "Fix Normal TLM mode wifi"). Install at least **1.5.8**,
> preferably the latest release. Confirmed working on a RadioMaster TX15 after updating.
>
> Check your version: ELRS Lua → **Backpack → Version**.
> Update: **ExpressLRS Configurator → Backpack** tab → your manufacturer and module
> (e.g. *RadioMaster TX15 2.4/900 TX*) → method **EdgeTX Passthrough** (radio connected over USB
> in "USB Serial (VCP)" mode) or WiFi.

> [!NOTE]
> **The backpack WiFi has no internet.** While the phone is connected to it, the map can only show
> tiles stored on the device. **Download your flying area before you go** (see
> [Offline maps](#offline-maps)). Everything else — telemetry, alarms, voice, logging — works
> without internet.

## Features

- **Full-screen map**: Satellite, Topographic, Street (Esri) or OpenStreetMap
- **Offline maps**: download the visible area for use without internet
- **Aircraft icon** rotated to heading, **flight track**, **home** marker and a line to home
- **Follow mode**: the map tracks the aircraft; dragging the map turns it off, ⌖ turns it back on
- **Artificial horizon** with compass tape
- **All telemetry on one screen**: altitude, vario, speed, distance and direction to home, GPS altitude,
  voltage, cell voltage, current, consumed mAh, power, LQ, RSSI, SNR, TX power, downlink, antenna,
  RF mode, satellites, flight mode, arm state
- **Altitude and voltage charts** (last 5 minutes)
- **Configurable alarms** with on-screen banner, voice and vibration (see [Alarms](#alarms))
- **Live goggles video straight from DJI Goggles N3 over USB** — no DJI Fly needed — in a floating
  panel you can drag and resize, or replacing the map with telemetry overlaid (see [Goggles video](#goggles-video-dji-goggles-n3-over-usb))
- **Flight log (CSV)**: on by default, starts automatically when telemetry arrives
- **Flight summary**: duration, max altitude, max distance, max speed/current, min cell voltage
- Landscape, full screen, screen stays on

## Quick start

1. **Install the APK**: download the latest `ELRS-Telemetry-*.apk` from this repository's **Releases**
   and open it (allow "install unknown apps"). Requires Android 10 or newer.
2. **Download offline maps** for your flying area while you still have internet (⚙ Settings → Map & offline maps).
3. On the radio, set **Link Mode = Normal** (not MAVLink).
4. ELRS Lua → **Backpack → Telemetry → WiFi**. If it already shows WiFi, set it to Off and back to WiFi.
   The backpack restarts; wait a few seconds.
5. Connect the phone/tablet to the WiFi network **`ExpressLRS TX Backpack XXXXXX`**, password **`expresslrs`**.
   When Android warns that the network has no internet, choose to **stay connected**.
6. Open the app. Telemetry appears within a few seconds.

## Offline maps

The phone is on the backpack WiFi during flight, so there is no internet for map tiles.

1. At home (or anywhere with internet), open the app and pan/zoom the map to your flying field.
2. ⚙ **Settings → Map & offline maps**: pick the layer and a **detail level** (max zoom 15–18).
   The screen shows the number of tiles and the approximate size.
3. Tap **Download visible area**. Progress is shown in the settings screen and at the bottom of the left panel.

Notes:
- Downloads are limited to 30,000 tiles per area; zoom in or lower the detail level for larger areas.
- The cache holds up to 2 GB and is kept for a year. Delete it via Android Settings › Apps ›
  ELRS Telemetry › Storage › Clear storage.
- Satellite, Topographic and Street (Esri) can be downloaded. The **OpenStreetMap** layer is
  **online only** because the OpenStreetMap tile policy forbids bulk downloads.
- If mobile data happens to be available while on the backpack WiFi, the app uses it for map tiles
  automatically. The top bar shows the source: `map: mobile data`, `map: offline cache` or `map: WiFi internet`.

## Goggles video (DJI Goggles N3 over USB)

The app shows the live video directly from **DJI Goggles N3** over a USB-C cable — no DJI Fly, no
network streaming.

1. Tap **📷** in the bottom bar. The video panel opens.
2. If the goggles are not connected yet, the panel says so and offers **Connect**:
   - Turn on the goggles and link them to the aircraft (live view on the goggles).
   - Connect the goggles to this phone/tablet with a **USB-C data cable** (not charge-only).
   - When Android asks which app to open for the DJI accessory, choose **ELRS Telemetry**
     (tick "always" to skip the question next time). If the app is already open it connects automatically.
   - Tapping **Connect** without goggles attached explains these steps.
3. The video appears. How it is shown is up to you:
   - **Floating panel** (default): drag the panel to move it, drag the **◢** corner to resize it.
     Position and size are remembered. Preset sizes (Small 240×135, Medium 320×180, Large 480×270)
     are in ⚙ Settings → Goggles video.
   - **Replace the map**: the video fills the screen and the telemetry panels are overlaid on it.

Controls:
- **📷** in the bottom bar opens/closes the video panel (blue = open).
- **FULL / PIP** in the bottom bar (visible while the panel is open) switches between the floating
  panel and replacing the map.
- On the floating panel: **FULL** = replace the map, **✕** = close, **◢** = resize, drag anywhere else to move.

Telemetry still comes from the backpack WiFi, so one device does everything: WiFi for telemetry,
USB for video.

- ⚙ **Settings → Goggles video** shows the USB status and the goggles' identity, with a **Connect** button.
- If the video stops, the app resends the subscription after 3 s and reopens the USB connection after 8 s.
  The panel turns black (the last frame is never left on screen) and says what is happening.
- **Keep running in the background** (on by default) keeps video, telemetry, spoken alarms and logging
  running while the screen is off or another app is in front; use **Exit** in the notification to close the app.
- While this app uses the goggles' USB connection, DJI Fly cannot use it at the same time.
- Other goggles (Goggles 3, Goggles 2, Integra) are untested.

> [!WARNING]
> **Unofficial and experimental.** This uses the reverse-engineered N3 mobile USB protocol from the
> open-source project [DJI Unchained VOC](https://github.com/jtm2kfpv-git/DJI-Unchained-VOC)
> (Apache-2.0, based on [samuelsadok/dji_protocol](https://github.com/samuelsadok/dji_protocol)).
> Confirmed working with Goggles N3 + O4 Air Unit Lite; the original project tested N3 + O4 Air Unit Pro /
> Avata 2. A DJI firmware update may change the protocol. Never rely on it for flight safety.
> Not affiliated with DJI.

## Screen

| Area | Content |
|---|---|
| Top bar | Flight mode, arm state, flight time, LQ, RSSI, satellites, logging state (tap to toggle), source IP, packet rate, map source |
| Left panel | Artificial horizon + compass, altitude, vario, ground speed, home distance, direction to home (arrow relative to the nose), GPS altitude; coordinates, log file, download and video status |
| Right panel | Battery gauge, cell voltage, current, consumed mAh, power; SNR, TX power, downlink, antenna/RF; altitude and voltage charts |
| Top centre | Alarms (red) and notices — **tap to mute alarms for 2 minutes** |
| Bottom | Buttons |

Buttons: **⌖** follow · **🗺** map layer · **⌂** reset home · **⌫** clear track ·
**📷** goggles video panel · **FULL/PIP** video panel ↔ replace the map · **▣** show/hide panels · **📊** flight summary · **⚙** settings

- **Altitude**: barometric altitude if the FC sends it (zeroed at arming), otherwise GPS altitude minus home altitude.
- **Home**: taken automatically at the first GPS fix with 6 or more satellites.
- **Arm state**: Betaflight/INAV append `*` to the flight mode while disarmed. Flight controllers that
  don't do this show "ARM ?", and flight time runs while telemetry is received.
- Values older than 3 seconds turn grey.

## Alarms

Every alarm can be switched on/off and has an adjustable threshold in ⚙ **Settings → Alarms**.
An active alarm turns the related value red, shows a red banner, is spoken (on-device TTS, no
internet needed) and vibrates. It repeats at the configured interval (default 15 s).

| Alarm | Default | Triggers when |
|---|---|---|
| Low cell voltage | **on**, 3.50 V | cell voltage < threshold |
| Low battery capacity | **on**, 20 % | remaining % < threshold (only if the FC reports it) |
| Low link quality | **on**, 50 % | LQ < threshold |
| Low RSSI | off, −100 dBm | best antenna RSSI < threshold |
| Telemetry lost | **on**, 3 s | no telemetry for longer than the threshold |
| Minimum altitude | off, 20 m | altitude < threshold, after having been above it in flight |
| Maximum altitude | **on**, 120 m | altitude > threshold |
| Minimum speed | off, 25 km/h | ground speed < threshold, after having been above it in flight |
| Maximum speed | off, 150 km/h | ground speed > threshold |
| Maximum distance | off, 1000 m | distance from home > threshold |
| Low GPS satellites | **on**, 6 | satellites < threshold |

- A small hysteresis band prevents alarms from flickering around the threshold.
- Minimum altitude and minimum speed **arm only after the value has been above the threshold** during
  the current flight, so they stay quiet on the ground and reset when you disarm.
- **Reset alarms** in the settings screen restores the defaults.

## Flight log

Logs are written to **Downloads / ELRS-Telemetry** as `flight_YYYYMMDD_HHMMSS.csv` (no storage
permission needed). Open them with a file manager or import them into a spreadsheet.

- Logging starts **automatically** when telemetry arrives; the file is closed after 30 s without data
  and a new file is opened when data resumes.
- Tap **REC** in the top bar to stop/start logging manually.
- Columns: time, lat, lon, GPS/relative/baro altitude, vario, speed, heading, satellites,
  pitch/roll/yaw, voltage, cell voltage, current, mAh, remaining %, power, RSSI 1/2, LQ, SNR,
  TX power, downlink RSSI/LQ, flight mode, home distance, home bearing, active alarms.

## Default settings

| Setting | Default |
|---|---|
| Map layer | Satellite |
| Offline detail level | zoom 17 |
| Automatic logging | on |
| Log rate | 5 Hz (1 / 2 / 5 / 10 Hz) |
| Spoken alarms | on |
| Vibrate on alarm | on |
| Alarm repeat interval | 15 s |
| UDP port | 14550 |
| Goggles video | floating panel (Medium, above the buttons) |
| Keep running in the background | on |

**Settings → Show raw UDP data** shows incoming packets for troubleshooting.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Backpack WiFi network does not appear | Backpack firmware must be 1.5.8+ (see the note at the top). Set Telemetry Off → WiFi and wait a few seconds. |
| "Waiting for telemetry" | Is the device connected to the backpack WiFi? Android may have dropped it ("stay connected"). |
| "Receiving MAVLink" | Set Link Mode to **Normal** on the radio. |
| Map is empty/grey | The area was not downloaded. Download it before connecting to the backpack WiFi. |
| No position | Is GPS enabled on the aircraft with GPS telemetry, with enough satellites? |
| No voice | Install/enable an English voice in Android's text-to-speech settings. |
| No goggles video | Goggles linked to the aircraft? Data cable (not charge-only)? Choose ELRS Telemetry when Android asks; check ⚙ Settings → Goggles video → Status, then **Connect**. Close DJI Fly so it does not hold the USB connection. |

## Technical details (verified against the source code)

- **ELRS TX 3.5.0+**: the `Off;ESPNOW;WiFi` Lua option and forwarding CRSF telemetry to the backpack
  (`devBackpack.cpp`, `sendCRSFTelemetryToBackpack`). No CRSF is forwarded when Link Mode is MAVLink.
- **Backpack 1.5.8+**: selecting WiFi stores the setting and reboots the backpack, which starts WiFi as an
  access point (or joins a configured home network) and sends every CRSF frame inside MSP v2
  (`$X<`, function `0x0011`) as a **UDP broadcast** to port **14550**.
- Network name `ExpressLRS TX Backpack XXXXXX` (XXXXXX = last 3 bytes of the bind-phrase UID), password
  `expresslrs`, backpack IP `10.0.0.1`.

## Building

- **GitHub Actions**: every push to `main` runs the tests, builds the APK, renders screenshots and
  publishes them to **Releases**.
- **Android Studio**: open the folder and press ▶ Run.
- Tests: `./gradlew testDebugUnitTest` (the goggles video pipeline test pushes a real H.264 stream through
  the USB framing and decodes the result with ffmpeg; it is skipped if ffmpeg is not installed)

## Code layout

| File | Purpose |
|---|---|
| `CrsfParser.java` | CRSF frame scanning, CRC8 (0xD5), telemetry decoding |
| `TelemetryState.java` | Latest values, home position, distance/bearing |
| `AlarmSet.java` | Threshold alarms with hysteresis and arm-after-crossing logic |
| `FlightStats.java` | Flight time, min/max values, chart history |
| `FlightLogger.java` | CSV logging (MediaStore → Downloads) |
| `GcsMap.java`, `PlaneOverlay.java` | osmdroid map, layers, offline downloads, track, aircraft/home drawing |
| `NetRouter.java` | Binds telemetry to WiFi and routes map traffic to mobile data when available |
| `HorizonView.java`, `BatteryView.java`, `SparklineView.java` | Instruments |
| `Alerts.java` | Spoken alarms and vibration |
| `VideoPanel.java` | MediaCodec decoding of the goggles video onto a TextureView; Connect overlay |
| `VideoWindow.java` | Floating video window: drag to move, corner handle to resize, FULL/✕ buttons |
| `n3/*.java` | Goggles N3 USB video: logiclink decoder, H.264 access-unit assembler, subscription packets, USB accessory session (adapted from DJI Unchained VOC, Apache-2.0) |
| `TelemetryService.java` | Foreground service that keeps the app running while another app is in front |
| `SettingsDialog.java` | Settings screen |
| `MainActivity.java` | UDP receiver and GCS screen |

## Third-party code and licences

- `app/src/main/java/com/elrstelemetri/n3/` is adapted from
  [DJI Unchained VOC](https://github.com/jtm2kfpv-git/DJI-Unchained-VOC), Copyright 2026 JTM2k FPV,
  Apache License 2.0 — see [NOTICE](NOTICE) and [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt).
- [osmdroid](https://github.com/osmdroid/osmdroid), Apache License 2.0.

## Map data

Map library: [osmdroid](https://github.com/osmdroid/osmdroid) (Apache 2.0).
Satellite imagery © Esri, Maxar, Earthstar Geographics; topographic and street maps © Esri and
contributors; OpenStreetMap © OpenStreetMap contributors. Tile servers have their own terms of use;
this app is meant for personal, low-volume use and limits the size of offline downloads.
