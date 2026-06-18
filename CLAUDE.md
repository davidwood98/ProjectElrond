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
- **On-canvas popups/menus must stay fully on-screen.** Any menu, popup, toolbar, card, or
  indicator positioned relative to a location *in the note* (a stroke, a selection, a `/Q` trigger
  point, etc.) must be dynamically positioned so no part renders off-screen: measure the content and
  the container, then clamp the anchor to the visible bounds — shifting it left/right and flipping it
  above/below the target as needed. Never place such an element at raw note coordinates without an
  edge-aware clamp. Precedents: the unclear-request card centres on screen (FA-7); the lasso
  selection toolbar centres over the selection and clamps to both screen edges (FA-9, via the
  measured container + toolbar sizes).
- **Canvas rendering has three layers — know which to use.** The ink canvas (`canvas/InkCanvas.kt`)
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

## Full app development — bug-fix batch (2026-06-08)

Post-POC fixes. **148 JVM unit tests passing** (was 136). DB is now **v5** (`MIGRATION_4_5`).
All changes are JVM-tested; the new migration and the Compose gesture/redraw changes still
need instrumented coverage (FA-1). The generated `app/schemas/…/5.json` confirms `MIGRATION_4_5`
matches Room's expected schema.

- **Canvas first-stroke render fix** — `InkCanvas` read `finishedStrokes` only inside the draw
  lambda, so a StateFlow emission updated composition state but never invalidated the draw phase;
  the first stroke stayed invisible until an unrelated recompose (the pen/eraser toggle). The list
  is now read in composition scope, so each finished stroke repaints immediately.
- **AI-output interaction** — new `SettingsRepository.aiNoteSelectedOnCreate` pref (default on):
  freshly created `/Q` answers start in the selected/edit state. Auto-select is driven by a
  `CanvasViewModel.createdNoteEvents` SharedFlow (fires only for user-created notes, never loaded
  ones). Deselect by tapping **off** the box (a full-screen catcher present only while something is
  selected); tap-inside-to-deselect was removed. The ✨ AI indicator was removed from both the AI
  ink boxes and TODO items (colour alone identifies AI; the TODO source link is kept). The
  double-tap expand/collapse was removed — AI answers always render full content. (Supersedes the
  Phase 10 notes about the ✨ badge and double-tap.)
- **Relative-date resolution** — `TaskExtractor`/`CalendarEventExtractor` gained an optional
  `referenceDate: String?` (defaulted, so existing callers/tests compile). `CanvasViewModel`
  injects the device's current weekday + ISO date (system zone) into the **user** prompt (kept out
  of the cached system prompt to preserve prompt-caching). `ai.elrond.ai.RelativeDateResolver`
  deterministically resolves "today / tomorrow / this <weekday> / next <weekday>" against the device
  date — convention: "this Monday" = soonest upcoming Monday, "next Monday" = the following week;
  `toEpochMillisOrNull` tries it first, then falls back to ISO parsing. Unit-tested: ref 2026-06-05
  → "this Monday" = 2026-06-08, "next Monday" = 2026-06-15.
- **Calendar created-vs-edited** — new `page_edit_events` table (DB v5) records one row per page per
  local day on save, deduped via a unique `(pageId, editDay)` index + insert-IGNORE. `NoteRepository`
  writes an edit event from `saveStrokes`/`replaceStrokes`/`clearStrokes`/`renamePage` (not
  `createPage`). `NoteActivityMapper` now counts *created* on the creation day and *edited* on every
  other distinct edit day; `CalendarViewModel` combines `observeTimeline` + `observeEditEvents`. The
  "both" legend entry was removed; the "slow day" nudge moved into today's empty tile above the
  create-note shortcut (the grid now always renders); the day-sheet card labels "created" vs
  "edited" per the selected day. `MIGRATION_4_5` backfills one last-edit day per pre-existing page
  (best-effort, UTC epoch-day).

### TODO architecture notes (for auto-populate without /Q)

- `TaskExtractor` (`:aibackend`) takes plain note text and returns `List<ExtractedTask>` — no `/Q`, app, or Android coupling. To auto-populate on save, add a WorkManager job that recognizes a saved page's strokes → calls `TaskExtractor.extract` → `TodoRepository.addExtracted`. The confirm step is UI policy in `CanvasViewModel`, not in the extractor, so a background job can choose to skip it.
- Manual vs AI items differ by `TodoItem.isAiExtracted`; only AI items carry a source link.

## FA-1 — instrumented + Robolectric test suite (2026-06-08)

Fills the POC testing gap. Test deps added: **Robolectric** + `androidx.room:room-testing` +
`androidx.test:core` (testImplementation), `androidx.test:rules`/`runner` (androidTest);
`testOptions.unitTests.isIncludeAndroidResources = true`. The exported Room schemas are exposed to
`MigrationTestHelper` via the **debug** sourceSet assets (Robolectric reads the debug variant's
merged assets — debug-only, never shipped) and the **androidTest** sourceSet assets (on-device).
`app/src/test/resources/robolectric.properties` pins `sdk=34`.

Counts: **155 JVM-runnable tests** (`./gradlew test` / `:app:testDebugUnitTest` / `:aibackend:test`)
— app 131 (incl. the new Robolectric tests), aibackend 24, 0 failures — plus **6 instrumented tests**
(`./gradlew connectedDebugAndroidTest`, requires a device/emulator; not runnable from WSL).

JVM/Robolectric (run + verified locally):
- `RoomDaoTest` — real in-memory Room SQL the mock-DAO repository tests can't reach: FK cascade on
  page delete (strokes / ai_notes / page_edit_events), the `page_edit_events` one-row-per-day dedup
  (unique index + INSERT-IGNORE), `observeSuggested` filtering, atomic stroke replace, todo dedup.
- `ElrondMigrationTest` — `MigrationTestHelper` validates the full **v1→v5** chain against the
  exported schemas, and asserts the **v4→v5 backfill** seeds `page_edit_events` for pages edited
  after creation (but not for same-day-only pages).

Instrumented (`androidTest`, compile-verified here; **run on a device/emulator**):
- `DeviceCalendarProviderTest` — real `CalendarContract` CRUD via a throwaway local calendar
  (created/removed per test): create / read / update / delete + `getCalendars`.
- `TodoPanelTest` — add → complete (moves to the Done section) → delete, against a real in-memory DB.
- `CalendarScreenTest` — Month / Week / Events mode switching + the Events placeholder + legend.
- `NoteListScreenTest` — empty state, FAB-create reports the new page id, long-press → confirm delete.

Not yet covered (follow-up): the `NoteCanvasScreen` ink/`/Q` UI (trigger logic is unit-tested in
`QueryTriggerDetectorTest` / `StrokeLineGrouperTest` / `CanvasViewModelAiTest`; on-canvas rendering
needs manual/device verification) and `TaskExtractionSheet` (private to `NoteCanvasScreen`; covered
at the VM level by `CanvasViewModelExtractionTest`). The instrumented suite is what gives
`MIGRATION_4_5` and the FA-bug-fix Compose changes their first **on-device** validation.

## FA-2 — background auto-extraction (2026-06-09)

Removes the need to manually `/Q` for TODO/calendar extraction: after a note's debounced save a
WorkManager job recognizes the page and extracts items in the background. `/Q` still works for
instant questions — the two modes coexist.

Flow: `CanvasViewModel` autosave → `ExtractionScheduler.enqueue` (unique work per page, REPLACE,
5s delay, `NETWORK_TYPE_NOT_REQUIRED` + `requiresBatteryNotLow`) → `ExtractionWorker` (pulls deps
from `ElrondApplication`; gates in order: auto-extraction setting → no-API-key → battery saver;
closes the ML Kit recognizer in a `finally`; retries on offline/transient failure) →
`AutoExtractionRunner` (the JVM-testable core, decoupled from Android/ink/WorkManager: recognize
lines → run `TaskExtractor` + `CalendarEventExtractor` with the device reference date → de-dup →
route). The one ink/ML-Kit step (`buildRecognizedLines`) is an injected seam.

Routing per the confirmation setting:
- **Confirmation on** → a `pending_suggestions` row (DB **v6**, `MIGRATION_5_6`); the canvas observes
  it and shows an on-canvas **Yes/No popup** ("Add to To-Do" / "Create event") anchored near the
  detected text (fuzzy line-match) and **clamped to stay fully on-screen** (shifts inward at edges,
  floored below the toolbar). Yes → commits to `todo_items` / `calendar_events`(`isAiSuggested`);
  No → marks the row `dismissed` (kept so the same item isn't re-suggested on the next save).
- **Confirmation off** → committed directly; auto-added TODOs set a flag that flairs the to-do tab
  with a "+" badge (on the canvas and note browser; cleared when the TODO panel opens).

De-dup is **type-namespaced** ("TODO:"/"EVENT:") across existing todos, calendar suggestions, and the
page's pending rows (incl. dismissed) — so a task and an event with the same text never collide.
Settings (DataStore, all default on, nested in the UI): `autoExtractionEnabled` →
`extractionConfirmationEnabled` (global) → `confirmTodoExtraction` / `confirmCalendarExtraction`
(the worker computes per-type = global && per-type).

Tests — suite now **171 JVM/Robolectric** (0 failures): `AutoExtractionRunnerTest` (routing,
type-namespaced de-dup, calendar creation, event-needs-a-time), `ExtractionSchedulerTest` (unique
work + NOT_REQUIRED network + battery-not-low via `WorkManagerTestInitHelper`),
`SuggestionRepositoryTest`, `SettingsRepositoryTest` (preference round-trips),
`CanvasViewModelSuggestionTest` (accept/reject + enqueue-after-save), migration test extended to
v1→v6. The on-canvas popup UI itself is device/manual-verified (like the other Compose flows).

DI note: `ExtractionWorker` reads its dependencies from `ElrondApplication` (manual DI) — FA-3 (Hilt)
will inject them via a `HiltWorker` + `WorkerFactory`.

## FA-3 — Hilt dependency injection (2026-06-09)

Replaces the manual `ElrondApplication` lazy container with Hilt — a pure refactor (no behaviour
change); all 171 JVM/Robolectric tests pass unchanged.

- **`@HiltAndroidApp ElrondApplication`** now also implements `Configuration.Provider`, supplying the
  WorkManager config with the Hilt `HiltWorkerFactory`. The manifest removes WorkManager's default
  `InitializationProvider` (`tools:node="remove"`) so the Hilt factory is used (on-demand init).
- **Modules** (`ai.elrond.di`): `AppModule` (Room db + repositories as `@Singleton`, the
  trigger-command `Flow`, and the WorkManager extraction enqueuer) and `AiModule`
  (`AIProvider?`/`TaskExtractor?`/`CalendarEventExtractor?`/`HandwritingRecognizer`) — AI bindings are
  separate so tests can replace them. The recognizer is intentionally **unscoped** (one per consumer,
  each owns its `close()`). The `(String) -> Unit` enqueuer binding uses `@JvmSuppressWildcards` to
  avoid the Kotlin function-type variance mismatch.
- **All ViewModels are `@HiltViewModel`.** The ones with test-only knobs (`CanvasViewModel`,
  `CalendarViewModel`) keep their full primary constructor and add a thin secondary `@Inject`
  constructor that delegates to it (production deps only), so the existing tests still construct them
  directly with fakes. `CanvasViewModel`'s `pageId` comes from the nav `SavedStateHandle`.
- **`ExtractionWorker` is a `@HiltWorker`** (`@AssistedInject`) — dependencies injected, not pulled
  from the app.
- **Composables** get ViewModels via `hiltViewModel()` (screen VM params default to it, overridable in
  tests); `MainActivity` is `@AndroidEntryPoint`. The manual `*ViewModelFactory` helpers were removed.
  hiltViewModel() scopes each VM to its nav back-stack entry — Notes/Calendar share the `notes` entry
  (survive tab switches); each `note/{pageId}` gets its own `CanvasViewModel`.
- **Test infra** (FA-3 deliverable "test modules for replacing real implementations"): `HiltTestRunner`
  (swaps in `HiltTestApplication`), `HiltGraphTest` (`@HiltAndroidTest` — asserts the graph constructs
  and provides the repositories), and `FakeAiModule` (`@TestInstallIn(replaces = [AiModule::class])`)
  so instrumented tests run without ML Kit / Anthropic.

The Hilt graph is validated at compile time by KSP (main + androidTest); the instrumented test APK
builds. Runtime graph construction + the HiltWorker are exercised on-device by the instrumented suite
(`HiltGraphTest` + the FA-1 Compose/CalendarProvider tests) — not runnable from WSL.

## FA-4 — trigger & recognition hardening (2026-06-09)

Hardens how the `/Q` assistant is triggered and how handwriting is recognized. **192 JVM/Robolectric
tests pass** (was 171 — app 168 + aibackend 24, 0 failures); `assembleDebug` + `assembleDebugAndroidTest`
both build (Hilt graph KSP-validated in both variants). All recognition/gesture/segmentation logic is
pure-JVM tested; the on-canvas gesture flow and palm tuning are device/manual-verified like the other
Compose flows.

- **Top-N candidate recognition** — `HandwritingRecognizer` gained `recognizeCandidates()` returning
  ranked `RecognitionCandidate(text, score?)` (best-first; ML Kit `score` may be null, so callers rely
  on **rank**, not score). `recognize()` is now the single-result convenience (MlKit delegates it to
  `recognizeCandidates`; the interface default wraps `recognize` so fakes that override only one method
  still work). `QueryTriggerDetector.firstTriggerCandidate(candidates, trigger, maxCandidates)` returns
  the best candidate that ends with the trigger — so a `/Q` the top guess garbled still fires.
- **Confidence floor (rank-based)** — `MAX_TRIGGER_CANDIDATES = 5` caps how deep a candidate may be and
  still trigger, so a low-confidence deep guess can't spuriously fire the assistant. (Score-based
  thresholds were avoided: ML Kit's score semantics are ambiguous/optional; rank ordering is guaranteed.)
- **Multi-line `/Q` segmentation** — `StrokeLineGrouper.blockAbove(spans, triggerIndex, gapFactor=1.2)`
  gathers the contiguous block of lines directly above a **bare** trigger (stopping at a "paragraph" gap
  — a vertical gap > gapFactor × the line's own height) as one multi-line question; lines above the gap
  stay as page context. `CanvasViewModel` uses an injected `questionLineSelector` seam (default
  `selectQuestionLines`, span-based via `lineSpan`) so the VM stays unit-testable with fakes. Inline `/Q`
  is unchanged (question = text before the trigger).
- **Gesture (lasso) trigger** — `GestureTriggerDetector` (pure geometry): `isLasso` (≥8 points, closed
  loop via start/end gap < `CLOSURE_RATIO`×diagonal, real enclosed area via shoelace ≥ `MIN_AREA_RATIO`×
  diagonal², min size `MIN_DIAGONAL_PX`), `contains` (even-odd ray cast), `enclosedIndices`. New
  `TriggerMode { COMMAND, GESTURE }` enum. In GESTURE mode `CanvasViewModel.handleGestureTrigger` treats
  the last stroke as a lasso (injected `lassoOf`/`centroidOf` seams → `strokeLoopOrNull`/`strokeCentroid`
  in StrokeLines.kt), recognizes the enclosed strokes as the question, **removes the lasso stroke** (it's
  a gesture, not ink), and answers. `detectAndHandleTrigger` now dispatches COMMAND vs GESTURE; both share
  `submitQuery` (de-dupe → provider guard → Thinking → extract-first → answer/error).
- **Trigger setting promoted** — `SettingsScreen` restructured: an **AI activation** section with a
  mode selector (Written command / Circle gesture), the validated trigger-char field (≤2 chars, retained),
  and a **live preview** box of what to write/draw; sections are now scrollable. The "Debugging" framing
  was dropped.
- **Palm-rejection tuning** — the decision is extracted to pure, tested `PalmRejection.shouldReject(
  isFinger, stylusOnly)` (used by `InkCanvas`), and **stylus-only is now a persisted setting**
  (`SettingsRepository.stylusOnly`, default true) surfaced under a **Canvas input** settings section.
  `CanvasViewModel` collects `stylusOnlyFlow` and `setStylusOnly` write-throughs via an injected
  `persistStylusOnly` seam, so the canvas toolbar's "Finger draw" chip now persists too.
- **DI** — `CanvasViewModel`'s `@Inject` (secondary) constructor now takes `SettingsRepository` directly
  and derives the trigger-command / trigger-mode / stylus-only flows + the stylus write-through from it;
  `AppModule.provideTriggerCommandFlow` (the standalone `Flow<String>` binding) was removed. The full
  primary constructor keeps all test seams (recognizer, splitter, placer, `questionLineSelector`,
  `lassoOf`, `centroidOf`, the flows, debounce/timeout) defaulted, so existing JVM tests construct it
  directly with fakes unchanged.

New/updated tests: `GestureTriggerDetectorTest` (lasso/contains/enclosure), `PalmRejectionTest`,
`HandwritingRecognizerTest` (candidate-bridge default), `firstTriggerCandidate` cases in
`QueryTriggerDetectorTest`, `blockAbove` cases in `StrokeLineGrouperTest`, multi-line + lasso +
candidate-recovery cases in `CanvasViewModelAiTest`, and trigger-command/mode + stylus round-trips in
`SettingsRepositoryTest`.

## FA-5 — bug-fix batch 2 (2026-06-13)

Second post-POC bug-fix pass against a user-reported list. **198 JVM/Robolectric tests pass**
(app 174 + aibackend 24, 0 failures; was 192). **No schema change — DB stays v6.** The logic-heavy
fixes are JVM/Robolectric-tested; the Compose/gesture pieces (first-stroke palm handling, the error
note UI, the collated sheet, calendar dots) are device/manual-verified like the other Compose flows.

- **First-stroke loss on finger interrupt** — `InkCanvas`'s palm rejection returned `false` for a
  finger pointer, which makes the framework withdraw the whole gesture and cancel an in-progress (or
  about-to-start) stylus stroke. It now **consumes** finger pointers (returns true) and ignores them,
  keeping the gesture alive so the stylus still draws; `ACTION_MOVE`/`ACTION_UP` only ever act on the
  tracked stylus pointer. The same fix is expected to cover the "swipe away & return" case (a lingering
  finger from the foreground gesture) — **still needs on-device confirmation** (not reproducible from WSL).
- **Auto-extraction re-firing after accept** — accepting a background suggestion used to `remove()` its
  `pending_suggestions` row, leaving only a fragile to-do-text match to block re-suggestion. Accept now
  **marks the row handled (kept)** via `SuggestionRepository.markHandled` (reuses the `dismissed`
  column), so the type-namespaced per-page de-dup (`existingTypedContents`, which spans handled rows)
  permanently prevents re-suggesting an accepted *or* dismissed item. Workflow now holds: write a
  to-do, accept, leave, re-enter, write more → the accepted item never re-pops.
- **"Request unclear" error response** — the one-shot "I need more information, request unclear" reply
  is detected in `CanvasViewModel.submitQuery` and rendered as a distinct **error note** (`AiInkNote`
  gains `isError` + `sourceQuestion`, both in-memory only — error notes are **never persisted**, so no
  migration; autosave/`onCleared` filter them out). New `AiErrorNoteView` shows **Edit prompt** +
  **Okay** (Okay == the ✕ dismiss on a normal note); Edit prompt reveals an editable, pre-filled copy
  of the recognized question with a **Re-send** button also bound to the keyboard Send/Enter action
  (`CanvasViewModel.resendQuery`, which resets the de-dupe guard). Independent of the
  `aiNoteSelectedOnCreate` setting, since it's an error affordance, not a normal answer.
- **AI box constrained to page width** — new-note width, drag (`moveAiNote`) and resize
  (`resizeAiNote`) are clamped so the box can't run off the right/left edge (`CanvasViewModel.maxWidthAt`,
  gated on a known canvas width so unit tests with no reported size are unaffected); `MathText` now lays
  tokens out in a `FlowRow`, so a long/two-part expression wraps instead of overflowing horizontally.
- **Calendar indicators** — the ✨ (created) / 📝 (edited) emoji on day tiles and in the legend are
  replaced by a muted-green dot (`0xFF66BB6A`) / dark-grey dot (`0xFF616161`) via
  `CalendarScreen.ActivityDot`; per-day counts are kept.
- **TODO "from [note]" link permanence** — the entity/mapper already round-trip
  `sourcePageId`/`sourcePageTitle` correctly across a restart (now proven by the file-backed
  `TodoSourceLinkTest`, which closes and reopens the DB). The real defect was the **dead link after the
  source note is deleted**: the FK `SET_NULL` nulls `sourcePageId` while the title snapshot remains, so
  `TodoPanel` rendered a non-tappable "from […]" label. It now shows the link only while `hasSourceLink`
  is true and falls back to a plain "AI" label otherwise. Net behaviour: the link is permanent across
  restarts and disappears only when the note is actually deleted.
- **Collated extraction confirmation** — the per-item on-canvas `AutoExtractPopup`s are replaced by a
  single `SuggestionExtractionSheet` (modal bottom sheet, one checkbox per detected item, "Add selected"
  / "Dismiss"); `CanvasViewModel.resolveSuggestions(acceptIds, dismissIds)` commits the checked items and
  dismisses the rest in one pass. **Supersedes the FA-2 on-canvas Yes/No popup** (the `x`/`y` anchor
  fields on `PendingSuggestion`/`pending_suggestions` are now unused by the UI but retained in the data).

New/updated tests: `TodoSourceLinkTest` (restart round-trip + delete-clears-link), `AutoExtractionRunnerTest`
(+2: full accept→re-save cycle, handled-row de-dup without a matching to-do), `CanvasViewModelAiTest`
(+2: unclear→error note carrying the recognized question, re-send drops the note and submits the edited
text), `CanvasViewModelSuggestionTest` (accept now asserts `markHandled`, never `remove`), and
`CalendarScreenTest` (dot legend instead of emoji).

## FA-6 — bug-fix batch 3 (2026-06-14)

Third post-POC bug-fix pass against a second device-test report. **203 JVM/Robolectric tests pass**
(app 179 + aibackend 24, 0 failures; was 198). **No schema change — DB stays v6.** Logic is JVM/Robolectric
-tested; the Compose/canvas pieces (first-stroke rendering, the clarify note, the transient toast) are
device/manual-verified like the other Compose flows. `assembleDebug` + `assembleDebugAndroidTest` both build.

- **First-stroke render — dry layer moved off Compose recomposition.** The dry-ink layer used to be a
  Compose `Canvas` reading `finishedStrokes` via `collectAsStateWithLifecycle`; a bare `StateFlow` emission
  (a finished stroke) didn't reliably wake a recomposition, so the *first* stroke of a freshly opened page
  stayed invisible until some unrelated recompose (e.g. hovering/pressing a toolbar button) forced a redraw.
  `InkCanvas` now renders the dry layer in a custom `DryStrokesView` (a plain `View` inside the same
  `FrameLayout`, below the wet `InProgressStrokesView`) that collects `finishedStrokes` directly — via a
  `repeatOnLifecycle(STARTED)` job started on attach / cancelled on detach — and calls `View.invalidate()`
  on each emission, scheduling a draw through the Choreographer regardless of Compose. The Compose `Canvas`
  dry layer is gone. (Pairs with the FA-5 finger-consume fix; **needs on-device confirmation** — not
  reproducible from WSL.)
- **"Request unclear" → confirm-the-guess workflow.** The system prompt is stricter: answer only when
  confident, never guess or hedge in the answer. When unclear it now returns EXACTLY two lines — the literal
  `I need more information, request unclear` then `Did you mean: <best-guess full question>?` (or just the
  first line if it truly can't guess). `CanvasViewModel.submitQuery` parses the guess (`DID_YOU_MEAN_REGEX`),
  strips it from the body, and stores it as `AiInkNote.suggestedQuestion` (new in-memory-only field).
  `AiErrorNoteView` shows a **Did you mean: …?** clarifier with **Yes** (re-sends the guess via `resendQuery`
  → normal answer) / **No** (falls back to the existing **Edit prompt** / **Okay** flow). No initial
  best-guess answer is ever rendered. Supersedes the FA-5 unclear-note behaviour (which rendered the model's
  raw reply and only offered Edit/Okay).
- **Circle (lasso) gesture re-triggers; dup items get a toast.** A lasso is a deliberate, explicit request,
  so `handleGestureTrigger` now calls `submitQuery(..., bypassDedup = true)` — re-circling the same selection
  always produces a fresh answer (the `lastHandledPrompt` guard is for the debounced *command* detector, not
  explicit gestures). When a triggered selection's only actionable content is items that **already exist**,
  `offerExtraction` returns the new `ExtractionOffer.ALL_EXISTING` and the VM shows a self-clearing
  notification (`transientMessage` StateFlow, auto-cleared after `TRANSIENT_MESSAGE_MILLIS` = 1.5s; rendered
  as a bottom-center pill in `NoteCanvasScreen`) instead of answering. (`offerExtraction` now returns a
  3-state enum instead of a Boolean: `NEW_ITEMS` / `ALL_EXISTING` / `NONE`.)
- **Manual `/Q` ↔ background auto-extraction de-dup.** Doing a manual `/Q` extraction before the background
  run fired used to propose the same to-do twice. `offerExtraction` now (a) de-dups proposed tasks against
  *both* the to-do list AND this page's existing suggestions (`SuggestionRepository.existingContents`), and
  (b) records the items it proposes as **handled** suggestions via the new
  `SuggestionRepository.recordHandled` (inserts `pending_suggestions` rows with `dismissed = true` — they
  de-dup future runs but never surface in the popup). `AutoExtractionRunner` already de-dups against this
  page's typed suggestion contents, so neither path can re-propose the other's items, in either order.
- **16 KB page-size warning (`libdigitalink.so`).** _(SUPERSEDED by FA-7 — this "accepted risk" was based
  on stale data; an aligned ML Kit build (`digital-ink-recognition` 19.0.0, Aug 2025) shipped and is now
  adopted, so `libdigitalink.so` is 16 KB-aligned. The conclusion below is kept only as the historical record.)_
  Added `packaging { jniLibs { useLegacyPackaging = false } }`
  so every native lib ships uncompressed + page-aligned in the APK. Verified in the built APK: `libink.so`,
  `libgraphics-core.so`, `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so` all have 16 KB
  (`0x4000`) LOAD-segment alignment. Only **ML Kit's prebuilt `libdigitalink.so` stays at 4 KB (`0x1000`)** —
  its internal segment alignment is baked in at Google's build time and cannot be realigned app-side; this is
  an **unresolved upstream issue** (googlesamples/mlkit#938, not fixed even in the latest release, native lib
  unchanged since 2023). **Accepted release risk** (same posture as the Anthropic key): the Studio warning
  persists for that one lib until Google rebuilds it, but it is **not** a development blocker — the app
  installs and runs. Before release, revisit when an aligned ML Kit build ships, or bundle an alternative
  recognizer.

Test/infra notes:
- `AiInkNote` gains `suggestedQuestion` (in-memory only, alongside `isError`/`sourceQuestion`; mapper untouched
  → no migration). `offerExtraction` returns `ExtractionOffer`; `submitQuery` takes `bypassDedup`.
- **`./gradlew test` is now fully green.** `ElrondMigrationTest` is `@Before`-guarded with `Assume.assumeTrue`
  to **skip in the release unit-test variant** (the exported Room schemas are bundled into the *debug* variant
  assets only, so `testReleaseUnitTest` can't read them — it was failing with `FileNotFoundException`). Full
  migration validation still runs in debug (`:app:testDebugUnitTest`) and on-device; schemas stay debug-only
  (never shipped). Release variant: 179 tests, 2 skipped.
- New/updated tests: `CanvasViewModelAiTest` (+2: Did-you-mean clarification carries the guess; re-lassoing
  the same selection fires twice), `CanvasViewModelExtractionTest` (already-existing → notification not answer;
  manual extraction records handled suggestions; a task already suggested isn't re-offered),
  `SuggestionRepositoryTest` (+1: `recordHandled` de-dups without ever showing).

## FA-7 — device-feedback round (2026-06-15)

Second on-device pass over the FA-6 batch. Device results: **issues 1 and 3 fixed; issue 2 NOT
fixed; the issue-2 attempt (androidx.ink upgrade) introduced a stroke-persistence REGRESSION.**
203 JVM/Robolectric tests pass + `assembleDebug`/`assembleDebugAndroidTest` build — but those tests
do **not** cover the regression (see below). No schema change — DB stays v6. **Nothing committed.**

- **16 KB page size — FIXED (device-confirmed; supersedes the FA-6 "accepted risk").** The FA-6
  conclusion that `libdigitalink.so` was unfixable was based on stale 2023 data. ML Kit shipped
  **`digital-ink-recognition` 19.0.0 (Aug 2025)** with `libdigitalink.so` rebuilt for 16 KB. Bumped
  `mlkitDigitalInk` 18.1.0 → 19.0.0; 19.0.0 also **repackaged the API** from
  `com.google.mlkit.vision.digitalink.*` to `com.google.mlkit.vision.digitalink.recognition.*`
  (pure repackage, same class names) — `HandwritingRecognizer.kt` imports updated accordingly.
  Verified in the built APK (`readelf -l`): every native lib, incl. `libdigitalink.so`, has `0x4000`
  (16 KB) alignment. `useLegacyPackaging = false` retained. **This part is good and should be kept.**
- **Unclear pop-up always on-screen (issue 3) — FIXED (device-confirmed).** A `/Q` near a page edge
  placed the clarify/error card (and its Yes/No / Edit / Okay controls) partly off-screen with no way
  to move it. `AiErrorNoteView` no longer self-positions at the trigger coordinates; `NoteCanvasScreen`
  renders the most recent error note as a **top-most, centred overlay** (`Alignment.Center`, max 480 dp).
  The resulting answer (on Yes / Re-send) still lands **inline** at the trigger. **Keep.**
- **First S Pen interaction lost (issue 2) — STILL NOT FIXED.** The FA-6 dry-layer `invalidate()` fix
  helped (always-broken → intermittent) but didn't resolve it. As an attempted fix we upgraded
  **`androidx.ink 1.0.0-alpha04` → `1.0.0` stable** (the residual — the *first* stylus interaction on a
  freshly shown screen dropped entirely, even on buttons, revealed by a second interaction — points at
  the front-buffer / first-frame / input init that ink owns). **On device the upgrade did NOT fix it.**
- **⚠️ REGRESSION introduced by the ink 1.0.0 upgrade — strokes don't survive a note reopen.** After
  closing and reopening a note, saved strokes render as just **a single dot at the first-contact point**
  (the rest of each stroke is lost). Root-cause lead (next session, start here):
  - The only persistence change was in `StrokeSerialization.toStroke`: `MutableStrokeInputBatch.addOrIgnore(...)`
    → `add(...)` wrapped in per-point `runCatching`. **Parameter order is confirmed identical** between
    alpha04 `addOrIgnore` and 1.0.0 `add` (both `(InputToolType, x, y, elapsedTimeMillis, pressure,
    tiltRadians, orientationRadians)`) — so it is NOT a mis-mapping. The difference is behavioural:
    alpha04 `addOrIgnore` *skips* an invalid point and continues; 1.0.0 `add` *throws*. The
    per-point `runCatching` was meant to replicate skip-invalid but evidently does not — strokes collapse
    to one point on reload, so almost every point after the first is being rejected by `add` and swallowed.
  - **Why tests didn't catch it:** `toStroke`/`toEntity` touch ink natives (`MutableStrokeInputBatch`,
    `Stroke`), which don't load under JVM/Robolectric (`isReturnDefaultValues = true`), so there is **no
    JVM test of the real ink round-trip** — it's device-only. `SerializedStrokeInputTest` only covers the
    pure-data JSON shape, not the ink reconstruction.
  - **Likely true cause to investigate:** 1.0.0 `add` probably enforces stricter validation than alpha04
    (e.g. strictly-increasing `elapsedTimeMillis`, or first-input must be t=0, or min point separation),
    rejecting our stored points after the first. Inspect the 1.0.0 `add` KDoc/validation; consider
    rebuilding via explicit `StrokeInput` objects + the `add(Collection<StrokeInput>)` overload, or
    normalising timestamps on load.

**Recommended next-session plan (issue 2 + regression):**
1. **Most likely best: REVERT the androidx.ink upgrade to `1.0.0-alpha04`** — it did not fix issue 2 and
   it caused the stroke-loss regression. Reverting restores the known-good `addOrIgnore` persistence.
   Files to revert: `gradle/libs.versions.toml` (`ink`), `StrokeSerialization.kt` (`add`+runCatching →
   `addOrIgnore`; `StockBrushes.x()` → `xLatest`), `CanvasViewModel.penBrush` (`pressurePen()` →
   `pressurePenLatest`). **Issues 1 (ML Kit) and 3 (centred pop-up) are independent of the ink version —
   the revert does not affect them, and all libs incl. `libink.so` were already 16 KB-aligned on alpha04.**
2. Then tackle issue 2 fresh via an **in-place mitigation** (the upgrade was the wrong lever): warm the
   `InProgressStrokesView` front-buffer surface and/or request focus on screen entry so the first stylus
   event/frame on a freshly-shown screen isn't dropped; investigate whether the nav-transition or surface
   creation is eating the first input. The "even buttons don't register the first interaction" clue points
   to a window/surface-level first-event drop, not pure rendering.
   (Alternative to step 1: keep ink 1.0.0 but FIX the serialization regression first, then add the issue-2
   mitigation — only if there's a concrete reason to want 1.0.0.)

`androidx.ink` is currently left at **1.0.0** with the regression present (uncommitted); ML Kit
digital-ink is at **19.0.0** (keep). **(SUPERSEDED by FA-8 — the ink revert + issue-2 fix below
were applied; ink is now back at `1.0.0-alpha04`.)**

## FA-8 — ink revert + first-stroke fix (2026-06-15)

Acts on the FA-7 device feedback: reverts the regression-causing androidx.ink upgrade and fixes
issue 2 in place. **All four FA-6/FA-7 issues are now resolved and device-confirmed** (16 KB, the
unclear pop-up, the stroke-reopen regression, and the lost first stylus touch). **203 JVM/Robolectric
tests pass** (app 179 + aibackend 24, 0 failures — unchanged from FA-7) **plus a new instrumented
round-trip test**; main + androidTest both compile (verified on the WSL Linux SDK). **No schema change
— DB stays v6.** Both device-only behaviours were verified on a Galaxy Tab S device on 2026-06-15:
strokes survive a close/reopen, and the first stylus tap on a freshly opened note registers.

**Process:** the entire (previously uncommitted) FA-5/6/7 working tree was first committed as a
checkpoint (`0dac198`) on branch **`fa-8-ink-fixes`** (`main` stays at `b2f0141`), as a restore point
before touching ink. The FA-8 changes below sit on that branch.

- **Stroke-persistence regression — FIXED by reverting androidx.ink `1.0.0` → `1.0.0-alpha04`.**
  Root cause confirmed: 1.0.0's `MutableStrokeInputBatch.add` *throws* on a point that alpha04's
  `addOrIgnore` silently *skipped* (e.g. a non-increasing timestamp relative to the batch); the
  per-point `runCatching` swallowed those throws, so every point after the first was rejected and the
  reloaded stroke collapsed to a single dot. `StrokeSerialization.toStroke` is back to `addOrIgnore`
  (skip-invalid) and the `StockBrushes.*Latest` accessors; `CanvasViewModel.penBrush` is back to
  `pressurePenLatest`; `gradle/libs.versions.toml` pins `ink = "1.0.0-alpha04"`. The upgrade was
  reverted (not fixed-forward) because it had **also failed to fix issue 2** — carrying its risk
  bought nothing. **ML Kit `digital-ink-recognition` 19.0.0 (issue 1, 16 KB) and the centred unclear
  pop-up (issue 3) are independent of the ink version and are retained.**
- **Regression coverage added (the gap that let FA-7 ship).** New
  `app/src/androidTest/.../data/StrokeSerializationInstrumentedTest` builds a real multi-point ink
  `Stroke`, runs it through `toEntity` → `toStroke`, and asserts the input count **and** endpoints
  survive (plus a brush-param round-trip). `toStroke`/`toEntity` touch ink natives that don't load
  under JVM/Robolectric, so this is **on-device** coverage (`connectedDebugAndroidTest`) — exactly the
  collapse-to-a-dot case the pure-data `SerializedStrokeInputTest` could never see.
- **First S Pen interaction lost (issue 2) — fix via `InProgressStrokesView.eagerInit()`.** `InkCanvas`
  now calls `eagerInit()` when the canvas attaches to the window, warming the front-buffered rendering
  surface up front instead of letting it initialise lazily on the first `startStroke`. Lazy init on the
  first touch creates the surface and relayouts the window, dropping that first input event across the
  **whole** window — which matches the FA-7 clue that *even Compose buttons* missed the first
  interaction and only the second registered. `eagerInit()` is a documented, idempotent, UI-thread-only
  API present in alpha04 (confirmed by inspecting the AAR). **Device-confirmed fixed (2026-06-15):** the
  first stylus tap on a freshly opened note now registers. (Had it persisted, the next step would have
  been touch-event instrumentation — action codes only, no coordinates, per the no-content-logging rule —
  to confirm whether the first `ACTION_DOWN` reached the listener at all.)

Re-verify on the next built APK: with ink back on alpha04, confirm `libink.so` is still 16 KB
(`0x4000`) LOAD-aligned (`readelf -l`). Per the FA-6 record alpha04's native libs were already
16 KB-aligned, and `useLegacyPackaging = false` is retained, so this is expected to hold — but it
swapped a native lib, so it's worth one `readelf` check.

### Future: upgrading androidx.ink to 1.0.0 (stable) safely

We are intentionally pinned to `1.0.0-alpha04`. A later move to stable `1.0.0` is still desirable,
but it must be done deliberately — the FA-7 regression happened because the upgrade was treated as a
drop-in. The exact API delta (verified by inspecting both AARs with `javap`) is:

- **`MutableStrokeInputBatch`**: alpha04 has three families — `addOrIgnore(...)` (skip-invalid),
  `addOrThrow(...)` (throw-on-invalid), and `Collection<StrokeInput>` overloads. **1.0.0 renamed
  `addOrThrow` → `add` and REMOVED `addOrIgnore` entirely.** So on 1.0.0 there is *no* skip-invalid
  variant: `add` throws on any point invalid relative to the batch (the prime suspect is a
  non-strictly-increasing `elapsedTimeMillis`, which our high-rate capture can produce). Wrapping a
  per-point `add` in `runCatching` is NOT a substitute for `addOrIgnore` — a thrown point is dropped,
  and because validation is relative to the last *accepted* point, one bad early point cascades and
  collapses the stroke to a dot (the FA-7 bug).
- **`StockBrushes`**: alpha04 exposes the `*Latest` properties (`pressurePenLatest`, `markerLatest`,
  `highlighterLatest`); 1.0.0 exposes them as functions (`pressurePen()`, `marker()`, `highlighter()`).

The upgrade must therefore not rely on skip-invalid behaviour at all. Target architecture:

1. **Sanitise stored points at the (de)serialization boundary, so `add` never throws.** Add a pure,
   JVM-testable `StrokeInputSanitizer` that takes the decoded `List<SerializedStrokeInput>` and returns
   a list guaranteed valid for ink reconstruction: force strictly-increasing `elapsedTimeMillis` (bump
   any `t <= prev` to `prev + 1`), drop non-finite x/y, clamp `pressure` to `[0,1]` (and tilt/orientation
   to their valid ranges), and drop exact-coincident consecutive points. Because it operates on the
   pure-data list (no ink natives), it is unit-testable under JVM/Robolectric — closing the original
   coverage gap on the *logic*, while the instrumented round-trip test covers the actual reconstruction.
2. **Keep one ink seam.** `StrokeSerialization.toStroke` should call the sanitizer, then build the batch
   in a single private helper (`fun inkBatchFrom(points): StrokeInputBatch`) that is the ONLY place
   touching the batch-builder API. Switching `addOrIgnore` ↔ `add(Collection<…>)` then touches exactly
   that helper plus the version pin and the `StockBrushes.*` call sites — nothing else.
3. **Belt-and-braces at capture time.** `toEntity` already reads points straight from `Stroke.inputs`,
   which ink keeps monotonic; the load-time sanitizer is the real guard because it also fixes data
   already persisted by older builds. Don't skip it on the assumption capture is clean.
4. **The instrumented round-trip test is the merge gate.** `StrokeSerializationInstrumentedTest` already
   asserts no point loss on a device; before any 1.0.0 merge, extend it with adversarial fixtures
   (duplicate timestamps, decreasing timestamps, NaN coords, out-of-range pressure) and require
   `connectedDebugAndroidTest` green on a real device. After sanitising, prefer `add` over `runCatching`
   so a *genuinely* invalid point surfaces as a test failure rather than being silently dropped.

**Upgrade checklist:** branch → bump `ink` to `1.0.0` → flip `StockBrushes.*Latest` → `*()` and the
seam's `addOrIgnore` → sanitised `add` → confirm `InProgressStrokesView.eagerInit()` still exists in
1.0.0 (the issue-2 fix) → run the (extended) instrumented round-trip + first-touch checks on a device →
`readelf -l` the APK for 16 KB alignment → only then merge. Do NOT upgrade and revert in the same pass
again: if 1.0.0 still doesn't fix a separate issue, that's not a reason to carry the migration cost.

## FA-9 — lasso selection tool (2026-06-16)

First post-POC *feature* (not a bug-fix batch): a real **lasso selection tool**, distinct from the
optional AI circle-gesture trigger (`TriggerMode.GESTURE`, untouched). **DB is now v7**
(`MIGRATION_6_7`). **208 app + 24 aibackend JVM/Robolectric tests pass** (0 failures; was 203) plus a
new instrumented test; `assembleDebug` + `assembleDebugAndroidTest` build. Device-verified on a
Galaxy Tab S (2026-06-16): select / move / scale / duplicate / delete / AI / copy / cut / tap-to-paste
/ group / ungroup, and a group survives a close/reopen.

- **Tool + interaction.** New `CanvasTool.LASSO` (toolbar chip). Drag a closed loop to select the
  enclosed ink (centroid-in-polygon, reusing `GestureTriggerDetector`), **expanded to whole groups**.
  The selection shows a dashed box: drag the body to **move**, corner handles to **scale** (about the
  opposite corner; uniform when *lock ratio* is on). Floating toolbar: **Duplicate / Delete / AI**;
  **⋮ kebab**: **Copy / Cut / Lock ratio / Group | Ungroup**. With the clipboard armed, a bottom
  **clipboard bar** ("N copied — tap to place" + **Clear clipboard**) shows and **tapping empty canvas
  pastes** at the tap point (auto-selected, repeatable); Clear clipboard wipes it, deselects, and
  resets the tool. AI prompt recognizes the selection and routes through the one-shot AI path
  (`submitQuery(..., bypassDedup = true)`).
- **In-memory model.** `finishedStrokes` is now `List<CanvasStroke>` where
  `CanvasStroke(id, stroke, groupId)` — a stable id that survives transforms (selection tracks ids,
  not stroke refs; a transform rebuilds the underlying immutable `Stroke`) and carries group
  membership to/from storage. `NoteRepository` round-trips it; `StrokeSerialization` carries `groupId`.
- **Group persistence (DB v7).** `strokes.groupId` column + `MIGRATION_6_7`
  (`ALTER TABLE strokes ADD COLUMN groupId TEXT`). Strokes sharing a non-null `groupId` form one group.
- **Geometry split (test seams).** Pure JVM-testable `canvas/StrokeSelection.kt` (`expandToGroups`,
  `union`, `enclosedIds`, `scaleTransform`, `LiveTransform`, `SelectionState`, `ClipboardState`,
  `Corner`); ink-native `canvas/StrokeTransforms.kt` (move/scale/clone/bounds, mirrors
  `StrokeSerialization.toStroke`) injected into `CanvasViewModel` as `strokeTransformer`/
  `strokeBoundsOf` seams (defaulted), so VM unit tests run with `mockk<Stroke>` + fakes and the ink
  reconstruction is covered on-device.
- **Live render, bake once.** `InkCanvas` adds a `SelectionStrokesView` (sibling of `DryStrokesView`)
  that draws the selected strokes with a live transform `Matrix` (no per-frame mesh rebuild); the dry
  layer skips the selected ids. On gesture end the VM bakes the transform into real strokes once.
- **No extraction re-run for lasso edits.** Pasted/duplicated ink reuses existing content, so it must
  not re-run the FA-2 background auto-extraction: `enqueueExtraction` is gated on a
  `contentDirtyForExtraction` flag set **only** by genuine pen strokes (`onStrokesFinished`). It also
  still passes the same type-namespaced de-dup (the runner de-dups within a run + against handled
  `pending_suggestions`), so a duplicate can never produce a doubled suggestion in either order.
- **On-screen menu positioning + project rule.** The selection toolbar is **dynamically positioned**:
  centred over the selection and clamped to both screen edges (flips above↔below, clamps vertically),
  using the measured container + toolbar sizes. Codified as an architectural rule (see *Architectural
  rules*): any on-canvas popup/menu positioned at a note location must clamp/flip to stay fully
  on-screen.

New/updated tests: `StrokeSelectionTest` (group expansion, bounds union, scale math, `LiveTransform`),
`CanvasViewModelSelectionTest` (lasso → selection + group expansion, duplicate/delete/copy/cut/paste/
clear-clipboard, group/ungroup, lock-ratio, move-commit + undo, AI-prompt routing, and the
pen-enqueues-but-lasso-edit-does-not extraction gate), `AutoExtractionRunnerTest` (+1: duplicated
identical content yields one suggestion), `RoomDaoTest` (+1: `groupId` round-trip), `ElrondMigrationTest`
(v1→v7 + the `strokes.groupId` column assertion). New instrumented `StrokeTransformsInstrumentedTest`
(real ink move/scale/clone/bounds, no point loss). The existing VM tests took mechanical
`finishedStrokes.value.map { it.stroke }` updates for the `CanvasStroke` element type. The
`SelectionLayer` gestures are device/manual-verified like the other Compose canvas flows.

## FA-10 — lasso move: live ghost preview + snap-back (2026-06-18)

Second lasso *feature* increment (after FA-9's tool): a faded "ghost" of where a selection is
moving **from**, plus a configurable **snap-back** when a move is released near its origin.
**230 app + 24 aibackend JVM/Robolectric tests pass** (0 failures; was 208+24) plus new instrumented
tests; `:app:testDebugUnitTest`, `:aibackend:test`, and `:app:assembleDebugAndroidTest` all build on
the WSL Linux SDK. **No schema change — DB stays v7** (both new settings are DataStore preferences,
so no Room migration). **Device-confirmed on a Galaxy Tab S (SM-X510, Android 16) 2026-06-18:** lasso
move now drags the ink live, the faded origin ghost shows, and snap-back works. The live-move render
took **two failed device passes** first (see the lesson below). Instrumented suite: **15/16 pass** —
the FA-10 tests (incl. the on-screen `SelectionStrokesRenderTest`) and the rest are green; the lone
failure is the **pre-existing** `DeviceCalendarProviderTest` (unrelated to FA-10 — on this device's
calendar provider a deleted event is still returned by the follow-up query; see the instrumented-suite
action plan, not an app regression).

- **Live move + ghost render in the Compose `SelectionLayer`, transformed by a `graphicsLayer`.**
  This is the hard-won lesson of FA-10 (two device passes failed first). The selected strokes' live
  move/scale (full colour) and the faded origin **ghost** (own colour at 30% alpha) are drawn by
  `SelectionLayer.SelectionStrokes` — a Compose `Canvas` calling the same `CanvasStrokeRenderer` via
  `drawIntoCanvas`/`nativeCanvas`. The **transform is applied as a `Modifier.graphicsLayer`, never
  inside the draw lambda.** Two dead ends and why:
  1. *View layer* (FA-9 / FA-10 v1): a `SelectionStrokesView` sibling under the wet-ink
     `InProgressStrokesView` (a `setZOrderOnTop(true)` front-buffer `SurfaceView`). Didn't render the
     live move on-device. Removed.
  2. *Compose Canvas with the transform inside the draw lambda* (FA-10 v2): still frozen at origin,
     ghost never appeared. Root cause (device-confirmed): **Compose does not re-run a draw lambda
     just because a captured value changed** — so the ink rasterised once at the origin and the
     per-frame transform updates never reached the screen. The dashed selection box moved the whole
     time because it uses a **layout modifier** (`absoluteOffset`), which re-applies every frame.
  The fix mirrors the box: apply the transform as a **`graphicsLayer`** (same modifier class), which
  re-applies every frame AND is GPU-cheap — the strokes rasterise **once** and the layer is just
  re-composited, so there's **no per-frame mesh redraw** (`CanvasStrokeRenderer` uses `Canvas.drawMesh`,
  which is hardware-only — a plain off-screen software Bitmap throws "software rendering doesn't
  support meshes"). The strokes `Canvas` is `key`ed on the stroke list so a new/just-baked selection
  re-rasterises while a drag (same list) does not; the ghost is a separate conditionally-composed
  layer (`StrokeTransforms.recolorStroke`, alpha-only, reuses the immutable input batch). `InkCanvas`
  keeps only the dry + wet layers and excludes the selected ids so they aren't drawn twice.
  **Architectural rule:** to move/scale content live during a gesture, drive it with a **layer/layout
  modifier** (`graphicsLayer` / `offset`) reading the gesture state — NOT a transform recomputed
  inside a `Canvas`/`drawBehind` lambda, which Compose won't re-invalidate on a value change.
- **Snap-back.** Pure `StrokeSelection.shouldSnapBack(transform, canvasWidth, canvasHeight, threshold)`:
  normalised travel `sqrt((dx/w)² + (dy/h)²)`, **strict `<` threshold** (a release exactly at the
  threshold commits), **moves only** (a scale never snaps), and **off** when threshold ≤ 0 or the
  canvas size is unknown. Enforced in `CanvasViewModel.commitTransform()` *before* the bake: a snap
  reverts the transform to identity with **no bake and no undo step**; otherwise it bakes as before.
  Needs the canvas height, so `setCanvasSize(widthPx, heightPx)` gained the height arg (the
  `NoteCanvasScreen` `onSizeChanged` caller passes it). Gating on a known size keeps the FA-9 VM tests
  (which report no size) committing unchanged.
- **Settings.** `SettingsRepository` adds `lassoSnapBackThreshold: Flow<Float>` (default **0.025** =
  2.5%, clamped 0–`MAX_LASSO_SNAP_BACK_THRESHOLD` = 0.10) and `lassoSnapBackEnabled: Flow<Boolean>`
  (default on) — the first `floatPreferencesKey` in the app. `SettingsViewModel` exposes both and
  **couples** them per the spec: setting the threshold to 0% auto-disables; toggling on while at 0%
  restores the 0.025 default. New **"Interaction"** section in `SettingsScreen` (after "Canvas input"):
  a Switch, a `Slider` (0–10%, 0.5% steps — the app's first Slider; greyed when off), and an
  interactive `SnapBackPreview` (drag a dot, release inside the dashed threshold ring to see it snap).
  `CanvasViewModel` collects both flows in its `@Inject` secondary constructor (the existing
  `stylusOnly` pattern); the primary constructor keeps them as defaulted optional params so the JVM
  tests construct it directly with `MutableStateFlow` fakes.
- New/updated tests: `StrokeSelectionTest` (+10: `shouldSnapBack` within/beyond/boundary/zero/
  unknown-size/scale-only/per-axis, `shouldShowGhost` none/idle/moving), `CanvasViewModelSelectionTest`
  (snap-back: within = no bake, beyond = bake, exact boundary commits, 0% disables, toggle off
  commits, no-canvas-size commits; plus a **strengthened** move-commit test that captures the baked
  transform via the `onTransform(LiveTransform)` seam and asserts preview-doesn't-bake, the box
  `displayBounds` tracks the live transform, the **final** transform bakes **once per stroke**, and a
  cancel bakes nothing), `SettingsRepositoryTest` (+threshold/enabled round-trip + clamp), and new
  `SettingsSnapBackCouplingTest` (+5: pure 0%→off / re-enable→restore-default coupling rules,
  extracted to testable `SettingsViewModel` companion functions).
- Instrumented (device-run; the VM tests can't see on-screen compositing — the gap that let the
  broken live move ship twice): new **`SelectionStrokesRenderTest`** renders the real
  `SelectionStrokes` over a white background and **pixel-asserts** the rasterised (navy) ink shifts by
  the drag distance, and that the ghost renders at origin only while transforming — the on-screen
  check no off-screen/VM test could make; `StrokeTransformsInstrumentedTest` (+`recolorStroke` keeps
  points/geometry, changes only colour). (An earlier off-screen-Bitmap perf test was **removed**: it
  crashed with "software rendering doesn't support meshes" — `CanvasStrokeRenderer` is hardware-only —
  and, more to the point, `graphicsLayer` does no per-frame mesh redraw, so the 60fps budget is met by
  construction rather than by a fragile off-screen timing test.)

## FA-11 — Outlook calendar integration (2026-06-18)

First real cloud-calendar backend: the stubbed `OutlookCalendarProvider` is now a working Microsoft
Graph client authenticated with MSAL, wired into the factory and surfaced in a populated **Events**
tab. **274 app + aibackend JVM/Robolectric tests pass** (0 failures; was 254 — app 250 + aibackend
24) plus a new instrumented test; `:app:compileDebugKotlin` and `:app:assembleDebugAndroidTest` build
on the WSL Linux SDK. **No schema change — DB stays v7** (the provider preference is a DataStore
pref; no calendar-event persistence change). The MSAL/Graph network paths are **device-validated**,
not unit-tested (MSAL needs a real Activity + registered Azure app); the Graph mapping, the auth
*seam*, and the Events-tab state machine are JVM-tested.

- **Transport: Ktor REST, not the Graph SDK.** `OutlookCalendarProvider` calls Graph v1.0 over Ktor +
  kotlinx-serialization (`calendar/GraphDtos.kt`), the same HTTP stack `:aibackend` uses for Anthropic
  — so it is fully unit-testable with `ktor-client-mock` and stays light. Endpoints: `GET /me/calendars`,
  `GET /me/calendarView?startDateTime=…&endDateTime=…` (ISO-8601; `Prefer: outlook.timezone="UTC"` so
  the offset-less `dateTime` parses as UTC), `POST /me/events` (or `/me/calendars/{id}/events`),
  `PATCH`/`DELETE /me/events/{id}`. `CalendarEvent.sourceNoteId` round-trips as a Graph
  **singleValueExtendedProperty** (`String {GUID} Name ElrondSourceNoteId`), written on create and read
  via `$expand`. Epoch-millis ↔ Graph `{dateTime,timeZone}` conversion is the pure, tested
  `OutlookTimeMapper` (always UTC). All Graph calls stay inside the provider — nothing Graph-specific
  reaches a ViewModel (same rule as the Anthropic API).
- **Auth seam keeps MSAL out of everything else.** `OutlookAuthProvider` (`calendar/OutlookAuth.kt`)
  is a small MSAL-free interface — `state: StateFlow<OutlookAuthState>` (NotConfigured / SignedOut /
  SignedIn), `currentToken()` (silent), `signIn(activity)` (interactive), `signOut()`. The **only** file
  importing `com.microsoft.identity.client.*` is `MsalOutlookAuthProvider` (single-account mode); the
  calendar provider takes a `tokenProvider: suspend () -> Result<String>` lambda, so tests and the rest
  of the app never touch MSAL. `NoOpOutlookAuthProvider` is the fallback when unconfigured (or in
  tests). Per the spec: `currentToken` is silent-first; a `MsalUiRequiredException` (or any silent
  failure) returns a failed Result **without** showing UI, and the Events tab offers interactive
  sign-in; `MsalException`s are caught into Results, never thrown.
- **Config never committed.** Azure **client id / tenant / signature hash** come from `local.properties`
  → `BuildConfig.OUTLOOK_*` (same posture as the Anthropic key). The MSAL JSON config is **generated at
  runtime** into the app cache (so no `res/raw` file carries the client id). Blank client id ⇒
  `CalendarProviderFactory.isConfigured(OUTLOOK)` is false ⇒ `CalendarModule` binds the No-op provider,
  so the Hilt graph (and instrumented tests) construct **without ever loading MSAL**.
- **Graceful degradation.** `CalendarProviderFactory.create` builds the right provider;
  `createOrDeviceFallback` returns `DeviceCalendarProvider` when the requested provider can't work in
  this build (Google still a stub; Outlook with no client id) — so a misconfigured preference reads the
  device calendar instead of crashing. Outlook *with* a client id is returned even when signed out: the
  Events tab shows the sign-in prompt rather than silently falling back. `CalendarProviders` (a
  `@Singleton`) caches one provider (and its Ktor client) per type.
- **Events tab.** `EventsViewModel` (`notes/EventsViewModel.kt`, `@HiltViewModel` with the same
  test-seam + `@Inject`-secondary pattern as CanvasViewModel) resolves the selected provider + Outlook
  auth state into an `EventsUiState` (Loading / NotConfigured / NeedsSignIn / Events / Error) and loads
  the next 30 days. The tab (`ui/CalendarScreen.kt`) renders a standard **"Sign in with Microsoft"**
  button (four-square logo drawn inline) when Outlook isn't connected, the upcoming-events list (with
  "Signed in as … / Sign out") when it is, and a retry on error.
- **Settings.** A new **Calendar** section in `SettingsScreen` exposes the Device / Google / Outlook
  picker (the `calendarProvider` DataStore pref existed but was never surfaced); connecting Outlook
  happens from Calendar → Events.
- **Manifest / deps.** MSAL `BrowserTabActivity` redirect handler added (`scheme=msauth`,
  `host=${applicationId}`, `path=/${msalRedirectPath}` from `outlook.signatureHash`); deps add
  `com.microsoft.identity.client:msal` (pinned `7.0.0`; MS recommends `8.+`) + Ktor client on `:app`.
  MSAL's transitive `com.microsoft.device.display:display-mask` is only on Microsoft's **Duo SDK Maven
  feed**, added to `settings.gradle.kts` (scoped to `com.microsoft.device.*`).

New/updated tests: `OutlookCalendarProviderTest` (12; ktor-client-mock — endpoints, headers,
event↔Graph mapping incl. the source-note extended property, calendar-scoped create, missing-token
short-circuit, non-auth token-failure propagation, Graph-error mapping, `OutlookTimeMapper` round-trip
with a literal UTC assertion), `EventsViewModelTest` (7; the
NotConfigured/NeedsSignIn/Events/Error state machine + sign-in transition + device path),
`CalendarProviderTest` (factory routing, `createOrDeviceFallback`, NoOp auth), `SettingsRepositoryTest`
(+calendar-provider round-trip), and the instrumented `CalendarScreenTest`
(+`events_tab_shows_microsoft_sign_in_when_outlook_not_connected`; the old placeholder assertion is
gone). The MSAL flow itself is device/manual-verified like the other on-device pieces.

## Calendar architecture (Phase 5 — data/provider layer; view UI added in Phase 6)

Swappable calendar integration behind `CalendarProvider` (`app/.../calendar/`):

```
              ┌─────────────────────┐
              │  CalendarProvider   │  getCalendars / getEvents /
              │     (interface)     │  createEvent / updateEvent / deleteEvent
              └─────────┬───────────┘
        ┌───────────────┼────────────────────┐
        ▼               ▼                     ▼
 DeviceCalendar   GoogleCalendar       OutlookCalendar
 Provider (REAL)  Provider (stub)      Provider (REAL — FA-11)
 CalendarContract  Google Cal API v3    MS Graph v1.0 (Ktor REST)
                   + Google Sign-In     + MSAL (OutlookAuthProvider seam)
        ▲
 CalendarProviderFactory.create(type, context, outlookAuth)  ← type from SettingsRepository.calendarProvider (DataStore)
 CalendarProviderFactory.createOrDeviceFallback(...)         ← degrades unconfigured providers to DEVICE
```

- **CalendarEvent** model: id, title, description, startTime, endTime, location, attendees, calendarId, `sourceNoteId` (links to originating note), `isAiSuggested`. Persisted in the `calendar_events` Room table (DB v4, `MIGRATION_3_4`) — AI suggestions (`isAiSuggested=1, isConfirmed=0`) and confirmed events. **No write to any real calendar without explicit user confirmation.**
- **DeviceCalendarProvider** is fully functional via `CalendarContract` (injected `ContentResolver`); requires READ/WRITE_CALENDAR (re-added to the manifest in this change). Attendee writes are out of POC scope. Its CRUD is verified by **instrumented tests** (CalendarContract isn't available to JVM unit tests); JVM tests cover the factory, stubs, repository, and AI extraction.
- **AI extraction**: `CalendarEventExtractor`/`AiCalendarEventExtractor` (`:aibackend`, provider-agnostic, JSON-array output) detect dated events → stored as `isAiSuggested` suggestions pending confirmation. Same decoupled seam as `TaskExtractor`.

### Google Calendar OAuth setup (for when GoogleCalendarProvider is wired)
1. Google Cloud Console → create/select a project → enable the **Google Calendar API**.
2. Configure the **OAuth consent screen** (External; add the `…/auth/calendar` scope).
3. Create an **OAuth 2.0 Client ID** of type **Android**: package `ai.elrond`, plus your debug + release **SHA-1** fingerprints.
4. Put the client id into `CalendarProviderFactory.googleConfig` (or, better, inject from a secured config). Add the Google Sign-In + `com.google.api-client`/`google-api-services-calendar` dependencies.
5. Auth flow: GoogleSignIn (scope `CalendarScopes.CALENDAR`) → `GoogleAccountCredential` → `Calendar` service. Method-to-API mapping is documented in `GoogleCalendarProvider`.

### Outlook / Microsoft Graph OAuth setup (wired in FA-11)
The code is complete; it only needs a real Azure app registration to function (placeholder ⇒ Outlook
stays NotConfigured and the Events tab explains it). To enable it:
1. Azure Portal → **App registrations** → New registration. Under **Authentication → Add platform →
   Android**, enter package `ai.elrond` and your debug/release **signature hash** (base64) — the portal
   then shows the exact redirect URI `msauth://ai.elrond/<base64 signature hash>` and a manifest snippet.
2. API permissions → Microsoft Graph → delegated **Calendars.ReadWrite** (and grant consent).
3. Add to `local.properties` (gitignored — never committed):
   ```
   outlook.clientId=<application (client) id>
   outlook.tenantId=common        # or your tenant id; "common" = personal + work/school accounts
   outlook.signatureHash=<base64 package-signature hash from the Android platform registration>
   ```
   `app/build.gradle.kts` feeds these into `BuildConfig.OUTLOOK_CLIENT_ID/TENANT_ID/REDIRECT_URI` and the
   `msalRedirectPath` manifest placeholder (the `BrowserTabActivity` intent-filter). `CalendarProviderFactory.outlookConfig`
   reads them — nothing is hardcoded.
4. Dependencies are already added: **MSAL** (`com.microsoft.identity.client:msal`, pinned `7.0.0`; MS
   recommends `8.+`) + Ktor client; MSAL's `com.microsoft.device.display:display-mask` needs Microsoft's
   **Duo SDK Maven feed** (already in `settings.gradle.kts`).
5. Auth flow: `MsalOutlookAuthProvider` (single-account) does silent `acquireTokenSilentAsync` first,
   interactive `signIn` on demand (scope `Calendars.ReadWrite`); `OutlookCalendarProvider` calls Graph
   v1.0 over Ktor REST. The MSAL config JSON is generated at runtime from BuildConfig — no committed
   `res/raw` file holds the client id. Method-to-endpoint mapping is documented in `OutlookCalendarProvider`.

### iOS port reuse
`CalendarProvider`, `CalendarEvent`, `DateRange`, and the AI extractor are pure/portable. An iOS port implements the same interface with EventKit (device) and the same Google/Graph REST APIs — the factory + DataStore preference pattern carries over unchanged.

### Known accepted risk (OAuth credentials)
The Outlook (Azure) client id now comes from `local.properties` → `BuildConfig.OUTLOOK_CLIENT_ID`
(FA-11) — never committed, but still extractable from a distributed APK (same posture as the Anthropic
key). Google's client id is still a placeholder in `CalendarProviderFactory`. For production, OAuth
client ids must not ship in the APK — use a server-side token exchange or platform secure storage.
(A public-client OAuth *client id* is not itself a secret, but treat it with the same release
discipline.)

## Security posture (audited 2026-06-04)

- Audit found: clean git history, TLS-only (https enforced via `AnthropicConfig` require + `usesCleartextTraffic=false`), no logging of note content, Room DB sandbox-only, `allowBackup=false`, only MainActivity exported.
- **Known accepted risk (development only — a hard blocker for release/completion):** the Anthropic API key is embedded via BuildConfig — extractable from any distributed APK. This is accepted *only* during active development; it is **not** acceptable for completion/release. Before any release: move to a server-side proxy holding the key, or per-user runtime keys in Android Keystore/EncryptedSharedPreferences.
- AI notes (`AiInkNote`) persist in the `ai_notes` table; the schema is now at **v7** — most recently `strokes.groupId` was added in `MIGRATION_6_7` for FA-9 lasso-selection groups (and `pending_suggestions` in `MIGRATION_5_6` for FA-2 background extraction).
- READ/WRITE_CALENDAR were re-added to the manifest in Phase 5 (the change that ships calendar) and are requested at runtime; `DeviceCalendarProvider` only acts on explicit user action.
- OAuth: the Outlook client id is sourced from `local.properties` → `BuildConfig` (FA-11, not committed); Google's is still a placeholder. For production, don't embed client ids — use a server-side token exchange (same posture as the Anthropic key). MSAL scopes are read-only-ish `Calendars.ReadWrite` (delegated); calendar writes still require explicit user confirmation (CalendarViewModel).
- **16 KB page size — resolved (FA-7).** All native libs, incl. ML Kit's `libdigitalink.so`, are 16 KB (`0x4000`) LOAD-segment aligned (verified in the built APK), via `digital-ink-recognition` 19.0.0 + `useLegacyPackaging = false`. No longer a release blocker. (The earlier FA-6 "accepted risk" framing was based on stale data — an aligned ML Kit build shipped Aug 2025.)

## Environment Notes (build)

- Two SDKs are in play: Android Studio (Windows) uses `sdk.dir=C:\...\Android\Sdk` in `local.properties`; WSL builds use the Linux SDK at `~/android-sdk` (swap `sdk.dir` temporarily during WSL builds, then restore — never leave the WSL path in the file or Studio breaks).
- The Anthropic API key is read from `anthropic.apiKey` in `local.properties` into `BuildConfig.ANTHROPIC_API_KEY`. Without it the app runs with the AI disabled (a config-error card appears on `/Q`).
- Outlook (FA-11): `outlook.clientId` / `outlook.tenantId` / `outlook.signatureHash` in `local.properties` → `BuildConfig.OUTLOOK_*` + the `msalRedirectPath` manifest placeholder. Without `outlook.clientId` the app runs with Outlook disabled (the Events tab shows a "not configured" sign-in prompt; the calendar falls back to the device provider). MSAL's `com.microsoft.device.display:display-mask` resolves only from Microsoft's **Duo SDK Maven feed** (added to `settings.gradle.kts`) — a clean Gradle cache needs network to fetch it.
- ML Kit downloads the en-US handwriting model on first `/Q` use — first trigger needs network.
