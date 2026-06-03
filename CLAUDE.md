# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Project Elrond** is an AI-first handwriting note-taking app for Android tablets (primary target: Samsung Galaxy Tab S series with S Pen). Handwritten notes are the primary input; an embedded AI assistant reads handwritten content and acts on it (TODO extraction, calendar entries, organisation, Q&A).

## Build Commands

```bash
./gradlew assembleDebug                 # Build debug APK
./gradlew :app:installDebug             # Install on connected device/emulator
./gradlew test                          # All JVM unit tests
./gradlew :app:testDebugUnitTest        # App unit tests only
./gradlew :aibackend:test               # AI backend module tests only
./gradlew :app:testDebugUnitTest --tests "ai.elrond.notes.SomeTest"   # Single test class
./gradlew connectedDebugAndroidTest     # Instrumented tests (requires device)
./gradlew lint                          # Android lint
```

## Architecture

### Modules

- **`:app`** — Android application (Kotlin + Jetpack Compose, MVVM). All UI, canvas, and device integration.
- **`:aibackend`** — Standalone pure-Kotlin (JVM, no Android dependencies) module containing all AI logic and the Anthropic Claude API integration. Must stay Android-free so it can be reused for a future iOS port (candidate for Kotlin Multiplatform conversion later). All AI calls go through the `AIProvider` interface so the underlying model can be swapped.

### Package layout (`app/src/main/java/ai/elrond/`)

- `canvas/` — Ink rendering, stroke management, S Pen input (pressure, tilt, palm rejection). Low-latency rendering targeting 120Hz via Android Ink API / stylus MotionEvents. Eraser support (stylus eraser + drawn eraser tool).
- `notes/` — Note pages, notebooks, page management. Pages have auto-generated timestamp titles or custom titles, organised in notebook/folder structure.
- `ai/` — AI activation and response rendering:
  - Trigger detection: handwritten `/Q` command on canvas activates AI prompt mode
  - Prompt scope: selected/grouped handwriting around the trigger
  - Two input modes: ML Kit handwriting recognition → text, or raw image crop (base64) for image-based models
  - Responses rendered onto the canvas in a handwriting-style font (e.g. Caveat/Patrick Hand), in moveable/resizable text boxes, visually distinct from user ink (different colour)
- `todo/` — Persistent TODO panel accessible from any page. Items auto-extracted by AI from notes, linkable back to source page; manual CRUD, due dates, priority.
- `calendar/` — Device calendar via CalendarProvider. AI suggests entries from written content; **user must confirm before any calendar write**. Upcoming-events side panel.
- `ui/` — Compose UI components, screens, theme.
- `data/` — Room database, repositories, models. Page metadata: created, modified, tags, AI context summary. Supports "created X / last edited Y" timeline views.

### Architectural rules

- **MVVM** with clean separation: Compose UI → ViewModel → Repository → Room/`:aibackend`.
- Jetpack libraries throughout: Room for storage, WorkManager for background tasks (e.g. AI extraction jobs).
- `:app` never calls the Anthropic API directly — only through `:aibackend`'s `AIProvider` interface.
- AI model: Anthropic Claude, default `claude-sonnet-4-6` (the originally specified `claude-sonnet-4-20250514` is deprecated, retiring 2026-06-15). Model ID is configurable via `AnthropicConfig` — never hardcoded at call sites.
- Handwriting recognition: Google ML Kit Digital Ink Recognition.
- Calendar writes always require explicit user confirmation.

## Testing Conventions

- Unit tests: JUnit + kotlinx-coroutines-test; ViewModels and repositories tested at the JVM level. Room DAOs tested with in-memory database (instrumented or Robolectric).
- `:aibackend` tests must mock the HTTP layer — never hit the real Anthropic API in tests.
- Compose UI tests in `app/src/androidTest` using `createComposeRule`.
- Custom agents in `.claude/agents/`: `test-writer` (writes tests for new/changed code) and `test-debugger` (runs tests, diagnoses failures). Use them for test creation and fault-finding work.

## Environment Notes

- Development happens in WSL2; the Android SDK lives on the Windows side (`/mnt/c/Users/david/AppData/Local/Android/Sdk`). Builds/deploys are typically run from Android Studio on Windows. `local.properties` is machine-specific and gitignored.
- The Anthropic API key must come from `local.properties` or an environment variable — never committed.

## Roadmap (POC order)

1. ~~CLAUDE.md + project structure~~ (done)
2. Basic note canvas with S Pen ink input
3. Room schema for notes, pages, notebooks
4. AI backend module with Anthropic API integration
5. `/Q` trigger detection + ML Kit recognition
6. AI response rendering on canvas
7. TODO extraction, calendar integration
