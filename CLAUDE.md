# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Project Elrond** is an AI-first handwriting note-taking app for Android tablets (primary target: Samsung Galaxy Tab S series with S Pen). Handwritten notes are the primary input; an embedded AI assistant reads handwritten content and acts on it (TODO extraction, calendar entries, organisation, Q&A).

## Rules

Always use canvas application best practices when developing. 
Android optimisation and SDK simplication, storage reduction and task repetition should always be minimised where possible.
Examples:
-The best code for a performance-critical path is less code
-Write the instrumented test before you claim the fix works. 
-Your storage format is part of your architecture.
-The main thread is for UI decisions, not work.
-Distinguish additive from destructive operations.

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

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

The packages are organised **by layer** (the convention + naming rule are in "Package
layout — by layer" just below). What each layer holds, and where the old feature areas now
live:

- **(root)** — app entry points only: `ElrondApplication`, `MainActivity`.
- **`ui/`** — everything you see (front end): Compose screens/components, the ink `View`s,
  theme, icons. The note canvas + low-latency S Pen ink rendering (`InkCanvas`: pressure,
  tilt, palm rejection, 120Hz via the Android Ink API / stylus MotionEvents; stylus + drawn
  eraser), the lasso `SelectionLayer`, `NoteCanvasScreen`, AI response views, `TodoPanel`,
  `CalendarScreen`, `NoteListScreen`, `SettingsScreen`, and `ui/theme` (Leap design system)
  + `ui/icons`.
- **`presentation/`** — the bridge: ViewModels + the UI-state they expose (`CanvasViewModel`,
  `Calendar`/`Events`/`NoteList`/`Settings`/`Todo` `ViewModel`, `AiUiState`).
- **`domain/`** — the app's own pure logic & in-memory models. `/Q` trigger + recognition
  grouping (`QueryTriggerDetector`, `GestureTriggerDetector`, `StrokeLineGrouper`,
  `MathDetector`, `RelativeDateResolver`) — handwritten `/Q` (or a circle gesture) activates
  AI prompt mode over the selected/grouped handwriting; stroke geometry/selection
  (`CanvasStroke`, `CanvasTool`, `StrokeSelection`, `StrokeTransforms`, `PalmRejection`); the
  background auto-extraction core (`AutoExtractionRunner`); and the models — `AiInkNote`,
  page/notebook `Models`, `TodoItem` (auto-extracted, linkable back to source page),
  `PendingSuggestion`, `NoteActivity`, `TriggerMode`, `ToolSelectedTreatment`.
- **`data/`** — storage + network + device/cloud integrations (back end): Room
  (`ElrondDatabase`, DAOs, entities, converters, mappers — page metadata + "created X / last
  edited Y" timeline), the repositories (notes, todo, suggestions, calendar,
  `SettingsRepository`), stroke serialization, the WebP `ThumbnailCache`, the ML Kit
  `HandwritingRecognizer`, the WorkManager extraction `ExtractionWorker` /
  `ExtractionScheduler` / `LineRecognition`, and the calendar providers (`CalendarProvider` +
  Device/Google/Outlook implementations, MSAL auth, Graph DTOs — **a calendar write always
  requires explicit user confirmation**).
- **`di/`** — Hilt modules (`AppModule`, `AiModule`, `CalendarModule`).

### Package layout — by layer

The `:app` packages follow a strict **by-layer** convention — the clean dependency flow
(UI → ViewModel → Repository → Room/`:aibackend`) made visible in the package tree:

- **`ai.elrond`** (root) — app entry points only: `ElrondApplication`, `MainActivity`.
- **`ai.elrond.ui`** — **front end**: everything you see — Compose screens/components, the ink
  `View`s, theme, icons.
- **`ai.elrond.presentation`** — the **bridge**: ViewModels + the UI-state classes they expose.
  Holds screen state and turns user actions into calls on the layer below. No drawing, no storage.
- **`ai.elrond.domain`** — the app's own **pure logic & in-memory models**: detectors, stroke
  geometry, date resolution, the extraction runner, models, enums. No storage/network/Compose.
- **`ai.elrond.data`** — **back end**: storage + network + device/cloud integrations — Room (db,
  DAOs, entities, repositories), serialization, the thumbnail cache, the ML Kit recognizer, the
  WorkManager extraction worker/scheduler, the calendar providers.
- **`ai.elrond.di`** — Hilt modules (cross-cutting wiring).
- **`:aibackend`** (separate module) — AI logic + the Anthropic API, zero Android. The true back end.

**Naming rule of thumb:** `*Screen` / `*View` / `@Composable` ⇒ `ui`; `*ViewModel` ⇒
`presentation`; `*Repository` / `*Dao` / `*Entity` / `*Provider` / `*Worker` ⇒ `data`;
detectors / mappers / models / enums ⇒ `domain`.

### UI design assets (`app/src/main/res/`)

Home for design elements — pen/eraser symbols, loading-indicator animations, etc. (Material
`ImageVector` icons are still used for generic chrome; this is for custom/branded artwork.)

- **`res/drawable/`** — vector symbols as `VectorDrawable` XML (`ic_<name>.xml`, e.g. `ic_pen.xml`)
  and simple animations as `AnimatedVectorDrawable` (`anim_<name>.xml`). Import SVGs via Android
  Studio → *New → Vector Asset*. Author with a single `fillColor` and tint at the call site:
  `Icon(painterResource(R.drawable.ic_pen), contentDescription = "Pen")`. `ic_pen.xml` is a
  deletable example/template.
- **`res/raw/`** — richer animation files (Lottie JSON, `loading_*.json`). Lottie needs the
  `com.airbnb.android:lottie-compose` dependency (not yet added — a deliberate dep decision); until
  then prefer an `AnimatedVectorDrawable` in `res/drawable/` or a Compose-coded animation (as the
  on-canvas loading dots already are).
- `res/` subdirs are **flat** (no nested folders) — organize by the `ic_*` / `anim_*` naming
  prefixes above. Each folder keeps a `.gitkeep` so it survives even when it holds no assets.

### Architectural rules

- **MVVM** with clean separation: Compose UI → ViewModel → Repository → Room/`:aibackend`.
- Jetpack libraries throughout: Room for storage, WorkManager for background tasks (e.g. AI extraction jobs).
- `:app` never calls the Anthropic API directly — only through `:aibackend`'s `AIProvider` interface.
- AI model: Anthropic Claude, default `claude-sonnet-4-6` (the originally specified `claude-sonnet-4-20250514` is deprecated, retiring 2026-06-15). Model ID is configurable via `AnthropicConfig` — never hardcoded at call sites.
- Handwriting recognition: Google ML Kit Digital Ink Recognition.
- Calendar writes always require explicit user confirmation.
- **On-canvas popups/menus must stay fully on-screen.** Any menu, popup, toolbar, card, or
  indicator positioned relative to a location *in the note* (a stroke, a selection, a `/Q` trigger
  point, etc.) must be dynamically positioned so no part renders off-screen: measure the content and
  the container, then clamp the anchor to the visible bounds — shifting it left/right and flipping it
  above/below the target as needed. Never place such an element at raw note coordinates without an
  edge-aware clamp. Precedents: the unclear-request card centres on screen (FA-7); the lasso
  selection toolbar centres over the selection and clamps to both screen edges (FA-9, via the
  measured container + toolbar sizes).
- **Canvas rendering has three layers — know which to use.** The ink canvas (`ui/InkCanvas.kt`)
  is a `FrameLayout` of: (1) **dry ink** — finished strokes in `DryStrokesView` (a plain
  hardware-accelerated `View`, repainted via `invalidate()` off a StateFlow); (2) **wet ink** —
  `InProgressStrokesView`, a front-buffered `SurfaceView` composited **on top of the whole window**
  (`setZOrderOnTop`, warmed by `eagerInit()`); (3) **Compose overlays above the `AndroidView`** — the
  lasso selection box/handles and the moving selected ink (`ui/SelectionLayer.kt`), and AI notes
  (`ui/NoteCanvasScreen.kt`). `CanvasStrokeRenderer` (used by all ink drawing) calls `Canvas.drawMesh`
  and is **hardware-only** — it throws "software rendering doesn't support meshes" on a software
  Bitmap canvas (so don't unit-test ink rendering off-screen; use an instrumented Compose
  `captureToImage` test).
- **To move/scale content live during a gesture, use a layer/layout modifier reading the gesture
  state — NOT a transform recomputed inside a draw lambda.** Compose does not re-run a `Canvas`/
  `drawBehind` draw lambda just because a value it captured changed, so a transform applied *inside*
  the draw stays frozen at its first value (FA-10 burned two device passes on exactly this: the
  selection box moved via `absoluteOffset` but the ink, transformed inside the draw, was frozen at the
  origin). The fix is `Modifier.graphicsLayer { translationX/scaleX/transformOrigin = … }` (or
  `Modifier.offset { … }`) — same mechanism as the box, re-applied every frame, and GPU-cheap (the
  strokes rasterise once; the layer is just re-composited — no per-frame mesh redraw). See FA-10.
- **A custom Material3 `lightColorScheme` must set the full `surfaceContainer*` ramp +
  `inverseSurface`** — unset roles fall back to the baseline (purple) Material palette, which leaks
  into `NavigationBar` / `ModalDrawerSheet` / badges / pills. (Adjacent traps: Compose variable fonts
  need `Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(n)))` under
  `@OptIn(ExperimentalTextApi::class)`; `BadgedBox(badge = …)` takes a `BoxScope.()->Unit`, so pass
  `badge = { x() }`, not a bare `() -> Unit`.) See FA-13.
- **Edge-to-edge screens must offset floating chrome by window insets, not hardcoded padding.** The app
  runs `enableEdgeToEdge()`, so content draws under the status/navigation bars. `Scaffold`-based screens
  (e.g. `LibraryScreen`) get this for free via the scaffold's content insets, but a full-bleed screen
  built on a raw `BoxWithConstraints` does **not** — any toolbar/header/title pinned with a fixed
  `top`/`start` padding ignores the system bars, so it tucks under the notification bar (and the gap
  drifts between orientations). The fix: keep the canvas full-bleed but anchor the floating chrome to
  the real insets plus a **constant** gap that is identical in both orientations —
  `top = WindowInsets.statusBars top + topGap`, `start/end =
  (WindowInsets.displayCutout ∪ navigationBars) side + sideSpacing` (direction-aware via
  `calculateLeftPadding(LocalLayoutDirection.current)`). Derive dependent offsets (e.g. a header band
  below the toolbar) from the same anchor so inter-element spacing stays constant. See the editor
  window-insets fix (2026-06-26).

## Testing Conventions

- Unit tests: JUnit + kotlinx-coroutines-test; ViewModels and repositories tested at the JVM level. Room DAOs tested with in-memory database (instrumented or Robolectric).
- `:aibackend` tests must mock the HTTP layer — never hit the real Anthropic API in tests.
- Compose UI tests in `app/src/androidTest` using `createComposeRule`.
- Custom agents in `.claude/agents/`: `test-writer` (writes tests for new/changed code) and `test-debugger` (runs tests, diagnoses failures). Use them for test creation and fault-finding work.

## Bug Reporting Convention
Logcat files for runtime bugs are stored in /logcat/
Named by date and issue: e.g. logcat_2026-06-05_white-screen.txt
Always provide the logcat path when reporting a runtime 
issue that cannot be reproduced by unit tests alone.

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
10. ~~AI response polish + Settings~~ (done — AI box selected on create (border+shadow+AI ✨ indicator+✕+resize handle); tap-off deselects into the note flow, long-press re-selects; double-tap expand/collapse for long answers; `MathText` renders fractions/exponents/basic calculus when `MathDetector` flags math; on-canvas animated loading dots while thinking; inline **red handwriting** "Could not connect — try again" on failure or **15s timeout**; home side-drawer → **Settings** with a debug **configurable activation command** (≤2 chars, DataStore) consumed by `QueryTriggerDetector`/`CanvasViewModel`)
11. ~~Calendar architecture~~ (done — see "Calendar architecture" section below; providers + factory + DataStore preference + `calendar_events` table (DB v4) + AI event extraction)
12. ~~Calendar view UI (POC final)~~ (done — home **bottom nav** Notes ↔ Calendar (scroll retained); **monthly + weekly** grids with ✨ created / 📝 edited indicators + counts, today highlighted, empty tiles distinct, month/week arrows; tap an active day → bottom sheet of that day's notes → open; **legend**; "slow day" empty state with a create-note shortcut on today's tile; **Events** placeholder tab for the future integrated calendar; reads only existing note timestamps — no new data)

**POC COMPLETE.** All six development phases delivered; 136 JVM unit tests passing.

## Memory feature saving
Save new memory context, change, updates etc into `the Projec_memory.md`. Continuing the full application feature naming convention in the log book (FA-XXX).