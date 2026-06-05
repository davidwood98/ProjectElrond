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
2. ~~Basic note canvas with S Pen ink input~~ (done — verified on emulator)
3. ~~Room schema for notes, pages, notebooks~~ (done)
4. ~~AI backend module with Anthropic API integration~~ (done)
5. ~~`/Q` trigger detection + ML Kit recognition~~ (done — strokes grouped into handwriting lines by vertical span; per debounce only the last-stroke line is recognized; `/Q` inline → that line is the question, bare `/Q` → line above is the question; other lines sent as labelled page context; response in a temporary card overlay; model pre-warmed at startup)
6. ~~AI response rendering on canvas~~ (done — `AiInkNote`s in Caveat handwriting font (bundled, OFL — `licenses/CAVEAT_OFL.txt`), purple AI ink vs navy user ink, placed below the trigger line, drag to move / corner-handle to resize / ✕ to remove; stroke undo/redo with per-eraser-gesture grouping, max 50 steps)
7. ~~Room persistence + note browser~~ (done — canvas auto-saves strokes (800ms debounce + onCleared flush) via `NoteRepository.replaceStrokes`; notes load on open; landing page grid with stroke-polyline thumbnails, timestamp titles, long-press delete with confirmation, FAB, empty state; navigation-compose `notes` ↔ `note/{pageId}`; manual DI via `ElrondApplication`)
8. ~~TODO list + AI task extraction~~ (done — `todo_items` table (DB v2, `MIGRATION_1_2`); `TodoRepository`; floating right-edge TODO panel reachable from the **canvas and the note browser**, toolbar count badge, manual add **pinned at the bottom**, **Done items in a separate section**, per-item **due-date chip** opening a date picker, AI-vs-manual visual distinction; `AiTaskExtractor` in `:aibackend` (provider-agnostic, JSON-array output, decoupled from `/Q` so a future WorkManager save-job can reuse it); on `/Q` success a **bottom sheet with per-item toggles** ("AI found these action items — add to your to-do list?") lets the user pick which to keep — on add, the chosen tasks are saved and the `/Q` echo note is discarded from the canvas; extracted items link back to their source note via `sourcePageId`/`sourcePageTitle` snapshot)
9. ~~AI response persistence + `/Q` box sizing + error states~~ (done — AI responses persist in the `ai_notes` table (DB v3, `MIGRATION_2_3`), loaded on open and auto-saved (debounce + onCleared flush) via `NoteRepository.replaceAiNotes`; `AiInkNote` now sized by `widthPx`/`heightPx` (unlocked aspect ratio), defaulting to a full line width minus margin from the live canvas size; `/Q` is one-shot — the system prompt forbids follow-ups and returns "I need more information, request unclear" when unclear; failures surface "I could not complete that request because of [reason]")
10. Calendar integration (NOTE: calendar permissions were removed from the manifest per security audit — re-add READ/WRITE_CALENDAR in the same change that ships this)

### TODO architecture notes (for auto-populate without /Q)

- `TaskExtractor` (`:aibackend`) takes plain note text and returns `List<ExtractedTask>` — no `/Q`, app, or Android coupling. To auto-populate on save, add a WorkManager job that recognizes a saved page's strokes → calls `TaskExtractor.extract` → `TodoRepository.addExtracted`. The confirm step is UI policy in `CanvasViewModel`, not in the extractor, so a background job can choose to skip it.
- Manual vs AI items differ by `TodoItem.isAiExtracted`; only AI items carry a source link.

## Security posture (audited 2026-06-04)

- Audit found: clean git history, TLS-only (https enforced via `AnthropicConfig` require + `usesCleartextTraffic=false`), no logging of note content, Room DB sandbox-only, `allowBackup=false`, only MainActivity exported.
- **Known accepted risk (POC only):** the Anthropic API key is embedded via BuildConfig — extractable from any distributed APK. Before any release: move to a server-side proxy holding the key, or per-user runtime keys in Android Keystore/EncryptedSharedPreferences.
- AI notes (`AiInkNote`) now persist in the `ai_notes` table (DB v3) alongside ink strokes.

## Environment Notes (build)

- Two SDKs are in play: Android Studio (Windows) uses `sdk.dir=C:\...\Android\Sdk` in `local.properties`; WSL builds use the Linux SDK at `~/android-sdk` (swap `sdk.dir` temporarily during WSL builds, then restore — never leave the WSL path in the file or Studio breaks).
- The Anthropic API key is read from `anthropic.apiKey` in `local.properties` into `BuildConfig.ANTHROPIC_API_KEY`. Without it the app runs with the AI disabled (a config-error card appears on `/Q`).
- ML Kit downloads the en-US handwriting model on first `/Q` use — first trigger needs network.
