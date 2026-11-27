# VERLYN 0.2.0

Native Android audio companion for JBL Tune 770NC, built with Kotlin and Jetpack Compose.

## 0.2.0 capabilities

- Four-tab Control, Equalizer, Smart and Inspector experience in Pure Black, Dark Zinc and Electric Blue.
- Single-socket Classic Bluetooth RFCOMM read-only stream connector for paired JBL Tune 770NC SDP candidates.
- Google Fast Pair Message Stream parser for Device Information battery-update events (group 0x03, code 0x03); battery is unavailable until a valid update arrives.
- Classic SDP scanner and cached BLE GATT snapshot, including the passive Airoha RACE RX listener. Airoha TX writes remain blocked.
- Native 10-band DynamicsProcessing EQ with interactive spline graph, sliders and seven built-in presets.
- Unlimited custom EQ presets persisted through Preferences DataStore.
- Left/right balance through DynamicsProcessing per-channel input gain.
- Software stream-volume ceiling (75% of Android STREAM_MUSIC steps) plus native DSP limiter.
- Optional AccessibilityService volume-key Smart Actions and TextToSpeech with transient may-duck audio focus.

## Protocol and platform boundaries

- SDP UUID presence does not prove that an endpoint accepts an RFCOMM connection. Fast Pair, JBL vendor and custom/Airoha candidate endpoints can be tested separately; all RFCOMM sessions are read-only.
- Fast Pair battery is parsed only when a valid Message Stream event arrives.
- ANC/Ambient/TalkThru, VoiceAware, Smart Audio/Video, Voice Prompts, Auto Power Off, LE Audio and voice-assistant controls remain gated until JBL command/response behaviour is verified.
- Android volume steps cannot be calibrated to 85 dB SPL. The software ceiling and DSP limiter are not a certified sound-pressure cap.
- DynamicsProcessing and TTS depend on Android/OEM audio routing.
- AccessibilityService volume-key filtering is opt-in and may vary by OEM. Short presses are replayed after the double-press detection window.

## Build

GitHub Actions produces `app-debug.apk` as artifact `verlyn-debug-apk` on pushes to `main` and manual workflow dispatch.

Package: `com.verlyn.audio`
Minimum Android: 8.0 (API 26)
Target/compile SDK: 35
