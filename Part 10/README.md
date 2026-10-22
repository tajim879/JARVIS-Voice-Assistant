# JARVIS Voice Assistant

An Android voice assistant built with Kotlin, Gemini Live, on-device voice components, accessibility automation, screen capture, and a native Android UI.

## Project status

- Android application module: `app`
- Application ID: `com.jarvis.assistant`
- Minimum Android version: Android 8.0 (API 26)
- Compile and target SDK: 34
- Java/Kotlin target: 17
- Current app version: 1.8.7 (version code 143)

## Getting started

1. Open the project root in Android Studio and allow Gradle to sync.
2. Set the Android SDK path in your local `local.properties` file. The file is machine-specific and is ignored by Git.
3. Launch the app on an Android 8.0+ device or emulator.
4. Add a Gemini API key in the app's Settings and grant the requested runtime permissions.

The Gradle wrapper is included in this project. Use `gradlew.bat` on Windows or `./gradlew` on macOS/Linux.

## Build commands

```powershell
# Windows: compile and package a debug APK
.\gradlew.bat :app:assembleDebug

# Windows: compile and package a release APK
.\gradlew.bat :app:assembleRelease
```

```bash
# macOS/Linux
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Build outputs are written under `app/build/outputs/` and are generated files, not source files. The current release build type uses the debug signing configuration; use a dedicated release keystore and protected signing properties before distributing an APK publicly.

## Repository layout

```text
.
├── app/                         Android application module
│   └── src/main/
│       ├── java/com/jarvis/assistant/
│       │   ├── audio/           Audio capture, playback, and voice handling
│       │   ├── automation/      Accessibility, actions, and app integrations
│       │   ├── background/      Conversation foreground service
│       │   ├── data/            Preferences, persistence, and data models
│       │   ├── network/         Gemini Live transport
│       │   ├── ui/              Activities, fragments, and adapters
│       │   ├── util/            Shared app utilities
│       │   └── vision/          Screen capture and visual task execution
│       ├── assets/              Bundled web UI and offline voice models
│       └── res/                 Android layouts, drawables, values, and config
├── docs/
│   ├── architecture/            Design notes and research
│   └── diagnostics/             User-provided issue recordings and index
├── gradle/                      Gradle wrapper configuration
├── build.gradle.kts             Root Gradle plugin declarations
├── settings.gradle.kts          Project and repository configuration
└── gradle.properties             Gradle runtime settings
```

## Documentation

- [Documentation index](docs/README.md)
- [Voice architecture research](docs/architecture/voice-architecture-research.md)
- [Voice issue recordings](docs/diagnostics/voice-videos/README.md)

## Configuration and privacy

- Keep API keys, SDK paths, signing keys, and local machine settings out of source control.
- `local.properties` is local-only; start from `local.properties.example` when setting up a new machine.
- Screen sharing and accessibility automation require explicit Android user consent and permissions.
- Bundled Vosk model assets are runtime dependencies; do not remove or rename their directories without updating the model-loading code.

## What's new in Part 9 (v1.10.0)

- **Live web research popup:** ask Jarvis to search, research or analyse anything on the internet. It uses Google Search through Gemini, opens an answer card on top of whatever app you are in (themed to your app colours), shows sources, and speaks a short summary. Copy and Share buttons included.
- **Wake word fixes:** quicker response in noisy rooms, more tolerant of a short "Hey Jarvis", and a diagnostics view under Settings > Wake Words that shows what was heard and why.
- **Notes, to-dos and expenses** by voice, with expense totals converted through live currency rates.
- **Notification reader** (needs Notification access) and **turn-by-turn navigation** by voice.
- **Quick Settings tile** to open Jarvis from the notification shade.
- Everything from Part 8 (Currency tab, multilingual voice tools, 15 UI languages) is unchanged.

Credits: Tajim Uddin (@dev.tajim00), Architect and Lead Developer.

## What's new in Part 10 (v1.11.0)

- **Math tab (5th card on Home):** solves every kind of math (arithmetic, algebra, equations, geometry, trigonometry, calculus, statistics, word problems) from **typed text, a gallery picture or a live camera picture**. Plain calculations are answered instantly on the device; everything else is solved step by step with Gemini, with Copy and Share buttons.
- **Jarvis Eyes:** a new toggle on Home turns on the **front camera** so Jarvis can see you and your surroundings while the app is open, and it can use screen sharing too. Opt-in, off by default, nothing is saved. Say "solve this" while showing a page to the camera or screen.
- **Voice math tools:** exact `calculate`, step-by-step `open_math_solver`, and `solve_math_visual` for what the camera or screen shows.
- **Web search fix:** the research popup now tries several Gemini models (retired ids are skipped), falls back to plain web results, and shows the real reason if something fails.
- Everything from Parts 8 and 9 is unchanged.

Credits: Tajim Uddin (@dev.tajim00), Architect and Lead Developer.
