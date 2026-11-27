# VERLYN 0.3.0

Native Android audio companion for JBL Tune 770NC, built with Kotlin and Jetpack Compose.

## 0.3.0 capabilities

- Four-tab OLED-black Control, Equalizer, Smart and Inspector interface with a custom V acoustic/headphone adaptive launcher icon.
- Foreground Bluetooth connection monitor for bonded JBL Tune 770NC, ACL/A2DP/headset state updates, Fast Pair RFCOMM auto-connect and exponential retry after stream EOF/errors.
- Incremental Fast Pair Message Stream parser. It preserves split frames between reads, walks multiple concatenated frames, parses Battery Updated (group 0x03/code 0x03), masks the charging flag and filters unknown sentinels.
- Android native Bluetooth battery metadata reflection and battery-level broadcast fallback.
- Restricted Airoha RACE RFCOMM client: chip identity and noise-state reads, plus only allowlisted ANC mode writes. Chip-family detection gates Ambient Aware/TalkThru options, and every mode change must be acknowledged and read back by the headset.
- Persistent audio companion service, media effect session open/close listeners, 10-band DynamicsProcessing EQ, Android Equalizer fallback, BassBoost, Virtualizer, preamp, limiter and left/right balance.
- JBL-route DSP activation and saved JBL EQ profile restore; effects are bypassed when the JBL output route is inactive.
- Interactive cubic 10-band EQ graph, saved custom presets, bass/clarity controls, balance and output controls.
- Optional AccessibilityService volume-key Smart Actions, bottom-sheet action assignment and TTS test voice.

## Protocol and platform boundaries

- The Airoha RACE client reads the reported chip identity and status before enabling modes. It never exposes arbitrary opcodes or NV/RAM/flash writes. Hardware behaviour still requires testing with the exact headphone firmware.
- Battery fallback depends on Android/OEM support for the hidden Bluetooth battery metadata method or battery-level broadcast. If neither is exposed, the app can still show battery as unavailable.
- Android can apply audio effects only to sessions it exposes/accepts. Some third-party apps do not broadcast audio-effect session lifecycle events, and OEM effect policies can reject or override session/global effects. The UI reports the observed effect state; Spotify/YouTube playback must be tested on the target phone.
- Platform Spatializer is platform-managed and queried by Android; the in-session Virtualizer is VERLYN's controllable fallback.
- Volume limiter is a software stream-volume ceiling plus DSP peak limiting, not a calibrated or certified 85 dB SPL cap.
- AccessibilityService volume-key interception is opt-in and OEM-dependent.

## Build

GitHub Actions builds `assembleDebug` and uploads `app-debug.apk` as artifact `verlyn-0.3.0-debug-apk` on pushes to `main` and manual workflow dispatch.

Package: `com.verlyn.audio`
Minimum Android: 8.0 (API 26)
Target/compile SDK: 35
