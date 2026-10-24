**Download the `ELRS-Telemetry-v….apk` file below and install it on your Android phone or tablet (Android 10+).**

Ground station for ExpressLRS telemetry: the radio's TX Backpack (firmware 1.5.8+, *Telemetry = WiFi*)
sends the telemetry over WiFi; DJI Goggles N3 video comes in over USB-C. See the
[README](https://github.com/savas8008/elrs-telemetry-view#readme) for setup.

### Upgrading from an earlier test build
Earlier test builds were signed with a temporary key. **Uninstall the old app once** before installing
this version (Android shows "App not installed" otherwise). From this version on, new releases
install as normal updates and keep your settings.

### New in 1.5
- **Video and map side by side**: the goggles video takes 30 % or 70 % of the width and the map the rest,
  so they never overlap and the aircraft stays in the middle of the visible map. The button on the video
  swaps the sizes.
- **One telemetry panel** on the left instead of two; it is hidden when the goggles connect (the goggles'
  OSD shows the telemetry) and the telemetry button slides it in from the left.
- Fix: the "Receiving MAVLink — set Link Mode = Normal" notice appeared during flights although CRSF
  telemetry was arriving. It now appears only when MAVLink arrives and no CRSF telemetry does.
- Follow mode: on at the first GPS fix; taps and two-finger zooming on the map no longer turn it off.

### New in 1.4
- Fix: after using the offline map screen, the map did not follow the aircraft any more until the
  follow button was pressed. Follow mode is now restored when the offline map screen is closed.

### New in 1.3
- Offline maps: the panel shows how much of the framed area is already downloaded. Downloading again
  fetches only the missing tiles (**Download missing**); earlier downloads are never deleted.

### New in 1.2
- Settings: the keyboard shows **Done** for every value; it closes the keyboard instead of jumping
  through all the other inputs.

### New in 1.1
- **In-app updates**: the app checks GitHub for new versions when internet is available and offers
  to install them (never during a flight). Android asks once to allow "Install unknown apps" for
  ELRS Telemetry. ⚙ Settings › Updates › *Check now* checks manually.
- Install 1.1 manually once; later versions can be installed from inside the app.

### Highlights
- Live map with aircraft, track and home; attitude, altitude, speed, battery and link panels
- Alarms (altitude, speed, battery, link quality, telemetry lost) with voice and vibration
- Offline maps: search a place, frame your flying field and download it at home
- DJI Goggles N3 video over USB in a floating window (drag to move, pinch to resize) or full screen
- Flight log (CSV), works in the background with the screen off
- Smooth display at every telemetry ratio (1:2 … 1:128)

Experimental, unofficial software — never rely on it for flight safety.
