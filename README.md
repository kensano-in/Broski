# VERLYN

Native Android audio companion for JBL Tune 770NC.

## Version 0.1.2

- Dual-layer device state: classic A2DP/HEADSET profile state and BLE GATT state are tracked separately.
- BLE device discovery retains a 12-second scan window, deduplicates by device address, and pins JBL Tune 770NC devices.
- GATT connection management, standard Battery Service read (when exposed), RSSI polling, MTU negotiation, service discovery, characteristic reads, notifications/indications, confirmed hex writes, and a copyable diagnostics dump.
- Native DynamicsProcessing 10-band EQ, input preamp, limiter, SharedPreferences presets, global mix session 0, and optional media-session effect attachment.
- Android output-route reporting and media-key/stream-volume controls.

## Important capability notes

- A2DP/HEADSET profile proxies are state observers; Android's public consumer APIs do not let ordinary apps force a headset's proprietary Classic profile connection on demand.
- Battery is shown only when the headset exposes the standard GATT Battery Service (0x180F / 0x2A19). Otherwise it is reported as unavailable. Hidden/system-only Bluetooth battery broadcasts are not used.
- DynamicsProcessing is available from Android 9 (API 28). Android/OEM audio routing may bypass session 0 or media-session effects on some Bluetooth routes/codecs. The app reports effect attachment status but cannot guarantee OEM routing.
- The GATT inspector reports services and characteristics exposed to this GATT connection. It is not a passive capture of every Bluetooth air packet. Hex writes send only user-entered bytes, after confirmation; do not send undocumented commands unless you understand their effect.

## Build

GitHub Actions builds `app-debug.apk` on pushes to `main` and manual workflow dispatch. The first 0.1.2 migration run unpacks a temporary compressed source archive, builds it, then commits the normal Kotlin source files and removes the temporary archive. Subsequent builds use the regular source tree.

Package: `com.verlyn.audio`
Minimum Android: 8.0 (API 26)
Target/compile SDK: 35
