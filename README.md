# VERLYN 0.3.1

Native Android audio companion for JBL Tune 770NC, built with Kotlin and Jetpack Compose.

## 0.3.1 capabilities

- OLED-black four-tab interface and custom V adaptive launcher icon.
- Foreground Bluetooth monitor and independent Classic RFCOMM clients for Fast Pair battery/metadata and Airoha RACE noise controls.
- Verified Tune 770NC RACE commands from Xeo23's `anc.py`: GET mode 0x0901, SET mode 0x0E06, and power-off 0x1101. RACE socket fallback uses the RACE UUID, PRIM UUID, then RFCOMM channel 21.
- Incremental Fast Pair TLV decoding for battery events, firmware version (group 03/code 09) and model ID (group 03/code 01).
- Manifest + dynamic media audio-session receiver, active playback session discovery, package/session reporting, and per-session DynamicsProcessing with Equalizer fallback.
- True pre-EQ/post-EQ band chain, preamp, limiter, opposing channel attenuation, BassBoost, and Virtualizer session effects with control/enabled checks.
- Synthesized AudioTrack test tone bound to its own audio session so the DSP can be checked independently of other apps.
- TTS status and media-route speech output, accessibility key filter flags, phone volume-key long-hold/double-press gestures, and Inspector event logging.
- Versioned diagnostic dump with battery source, firmware/model metadata, active effects/session binding state, TTS status and raw RACE TX/RX log.
- Classic SDP scan guard: one in-flight request and a five-second cooldown.

## Protocol and platform boundaries

- RACE power-off is the reference's one-way 0x1101 [18 00] command; the headset does not provide an acknowledgement in the source implementation. VERLYN reports it as sent, not confirmed.
- Airoha mode state is considered current only after the headset returns a 0x0901 response. If no candidate endpoint responds, the selected mode remains unconfirmed.
- Android only allows audio effects to bind to sessions exposed by the media app / platform. The test AudioTrack always supplies a VERLYN-owned session; Spotify/YouTube binding depends on their session callbacks or Android playback-configuration visibility and OEM policy.
- Fallback Android Equalizer may not provide independent left/right channel control on every OEM implementation.
- Text-to-speech follows Android's media output route and system TTS engine.
- Volume-key gestures depend on Android delivering phone key events to an enabled accessibility service. Earcup AVRCP volume controls may not appear as phone KeyEvents.

## Build

GitHub Actions builds `assembleDebug` and uploads `app-debug.apk` as artifact `verlyn-0.3.1-debug-apk`.

Package: `com.verlyn.audio`
Minimum Android: 8.0 (API 26)
Target/compile SDK: 35
