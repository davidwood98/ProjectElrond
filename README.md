# Project Elrond

AI-first handwriting note-taking app for Android tablets (Samsung Galaxy Tab S series with S Pen).

Handwritten notes are the primary input. An embedded AI assistant reads handwritten content and acts on it: answering questions written on the canvas (`/Q` trigger), extracting TODO items from meeting notes, suggesting calendar entries, and organising notes by topic.

## Modules

- **`:app`** — Android app (Kotlin, Jetpack Compose, MVVM, Room, WorkManager)
- **`:aibackend`** — Standalone pure-Kotlin AI module (Anthropic Claude integration), Android-free for future iOS reuse

## Getting Started

Open the project in Android Studio (Ladybug or newer, JDK 17) and sync. Deploy to a Galaxy Tab S series tablet or a tablet emulator.

```bash
./gradlew assembleDebug   # build
./gradlew test            # unit tests
```

See `CLAUDE.md` for full architecture documentation.
