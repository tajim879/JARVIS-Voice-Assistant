# JARVIS Voice Assistant

Production-ready Android voice assistant powered by Google Gemini Live (bidiGenerateContent WebSocket).

## Setup
1. Open this project in Android Studio (Koala/Ladybug or newer, AGP 8.7.2, Kotlin 2.0.20).
2. Let Gradle sync (or run `./gradlew assembleDebug` from a terminal — a full `gradle-wrapper.jar` will need to be generated via `gradle wrapper` once if not already present).
3. Install on a device running Android 8.0 (API 26) or newer.
4. Open the app → tap the Settings gear → paste your **Google Gemini API key** → Save.
5. Tap the center power button, grant microphone permission, and start talking.

## Key implementation notes
- Uses `gemini-2.5-flash-native-audio-preview-12-2025` over the `v1beta` BidiGenerateContent WebSocket.
- Mic capture: 16kHz mono PCM via `AudioSource.VOICE_COMMUNICATION` (hardware echo cancellation) in 40ms/1280-byte chunks, sent as `realtimeInput.audio` (never the deprecated `mediaChunks` field).
- Playback: 24kHz mono PCM via a low-latency streaming `AudioTrack`.
- Barge-in: `serverContent.interrupted` flushes the AudioTrack queue instantly and resets state to LISTENING.
- Session renewal: proactively reconnects at the 9-minute mark to stay ahead of Gemini Live's ~10-minute session cap.
- Visualizer: a dependency-free HTML5 Canvas orb (`assets/orb/index.html`) loaded in a transparent WebView, driven by `window.setOrbState(state, level)` / `window.setAudioLevel(level)`.

See the full PRD for architecture details, wire-protocol spec, and the antipattern checklist used to avoid the common Gemini Live integration pitfalls (deprecated `media_chunks`, wrong sample rates, missing AEC, client-side JSON pings, etc.).
