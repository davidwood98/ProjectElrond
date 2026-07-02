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
- **Geometry split (test seams).** Pure JVM-testable `domain/StrokeSelection.kt` (`expandToGroups`,
  `union`, `enclosedIds`, `scaleTransform`, `LiveTransform`, `SelectionState`, `ClipboardState`,
  `Corner`); ink-native `domain/StrokeTransforms.kt` (move/scale/clone/bounds, mirrors
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
  kotlinx-serialization (`data/GraphDtos.kt`), the same HTTP stack `:aibackend` uses for Anthropic
  — so it is fully unit-testable with `ktor-client-mock` and stays light. Endpoints: `GET /me/calendars`,
  `GET /me/calendarView?startDateTime=…&endDateTime=…` (ISO-8601; `Prefer: outlook.timezone="UTC"` so
  the offset-less `dateTime` parses as UTC), `POST /me/events` (or `/me/calendars/{id}/events`),
  `PATCH`/`DELETE /me/events/{id}`. `CalendarEvent.sourceNoteId` round-trips as a Graph
  **singleValueExtendedProperty** (`String {GUID} Name ElrondSourceNoteId`), written on create and read
  via `$expand`. Epoch-millis ↔ Graph `{dateTime,timeZone}` conversion is the pure, tested
  `OutlookTimeMapper` (always UTC). All Graph calls stay inside the provider — nothing Graph-specific
  reaches a ViewModel (same rule as the Anthropic API).
- **Auth seam keeps MSAL out of everything else.** `OutlookAuthProvider` (`data/OutlookAuth.kt`)
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
- **Events tab.** `EventsViewModel` (`presentation/EventsViewModel.kt`, `@HiltViewModel` with the same
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

## FA-12 — performance: incremental dry-layer render + WebP thumbnail cache (2026-06-19)

Two targeted performance fixes for device stutter (a performance audit + on-device confirmation:
16 notes, several full of strokes, 6 with to-do links, background auto-extraction running). **256 app
+ 24 aibackend JVM/Robolectric tests pass** (0 failures; was 250+24) plus an extended instrumented
test; `:app:testDebugUnitTest`, `:aibackend:test`, and `:app:assembleDebugAndroidTest` build on the
WSL Linux SDK. **No schema change — DB stays v7.** Device-confirmed on a Galaxy Tab S (2026-06-19):
writing stutter and note-browser/nav-back stutter are gone.

- **Incremental dry-layer render (writing stutter as a page fills).** `InkCanvas`'s dry-layer
  collector called `DryStrokesView.setStrokes()` — which `invalidate()`s and repaints every dry
  stroke via `CanvasStrokeRenderer.draw` — on *every* `combine(finishedStrokes, selection)` emission.
  The combine also fires on every transform-only emission (each lasso-drag frame mutates
  `selection.transform` but not the stroke list or the selected ids), so a full-page mesh repaint ran
  per frame and cost grew linearly with stroke count. `setStrokes()` is now **inside** the
  change-detection `if` (the FA-9 cache-key check), so the dry layer repaints only when the stroke
  list or selected-id set actually changes — never per drag frame. First-stroke render is preserved
  (the first emission still trips the `if`).
- **Serialization off the autosave thread.** `NoteRepository.replaceStrokes` JSON-encoded every
  stroke's points on the caller's context — the Main-dispatched `viewModelScope` autosave. The
  `strokes.map { it.toEntity(...) }` is now wrapped in `withContext(Dispatchers.Default)`. (Safe for
  tests: the VM tests mock the repository; `NoteRepositoryTest` awaits it sequentially.)
- **File-backed WebP thumbnail cache (nav-back + browser load stutter).** The note browser used to
  decode stroke JSON for up to `PREVIEW_MAX_STROKES` (60) strokes per card on the **main thread** (a
  `produceState` calling `viewModel.preview`) and redraw the polylines from scratch on every
  recomposition — a gridful at once stuttered the nav transition. New **`data/ThumbnailCache.kt`**:
  one WebP per page under `<cacheDir>/thumbnails/<pageId>.webp` (`write`/`read`/`exists`/`delete`,
  all off-thread), plus **`ThumbnailRenderer`** which renders the *same normalized polylines the card
  drew* onto a software `Canvas` (`drawPath`) — NOT the live ink View. (The dry-ink View draws via
  `CanvasStrokeRenderer.drawMesh`, which is **hardware-only** and throws on a software `Bitmap`
  canvas, so it cannot be captured off-screen — the original audit's `view.draw(softwareCanvas)` plan
  would have crashed.) Legacy `Bitmap.CompressFormat.WEBP` on an opaque `RGB_565` bitmap (minSdk 29 <
  the API-30 `WEBP_LOSSLESS`).
  - `CanvasViewModel` takes a `thumbnailGenerator` seam (the real impl — `loadStrokePreview` → render
    → `cache.write`; empty page → `cache.delete` — is built in the `@Inject` ctor from an injected
    `ThumbnailCache`; null/fake in JVM tests). It regenerates on autosave-success and on the
    `onCleared` flush (ordered **after** the stroke write, so a quick back-press still leaves a fresh
    thumbnail), gated by `thumbnailDirty` (set on a finished stroke / any persisted change, cleared by
    `generateThumbnail`).
  - `NoteCard` shows the cached `Image` when present, else the polyline `StrokeThumbnail` fallback;
    both `produceState`s run off-main and are keyed on `modifiedAt`, so a freshly generated thumbnail
    replaces the fallback on the next visit. `NoteRepository.loadStrokePreview`'s decode + normalize
    also moved to `withContext(Dispatchers.Default)` — so the browser does **zero main-thread work**
    whether or not a cached thumbnail exists yet. `NoteListViewModel.deleteNote` now also deletes the
    cached file.
  - `ThumbnailCache` is a Hilt `@Singleton` (`AppModule`, `context.cacheDir`); injected into
    `CanvasViewModel` (`@Inject` secondary ctor) and `NoteListViewModel`.
- **Deliberately skipped** the audit's `onStrokesFinished` ArrayDeque change: it's O(n) at emit time
  either way (StateFlow + the combine `!==` check both need a fresh reference) and a mutable backing
  list would desync from the ~10 other sites that set `_finishedStrokes`.

Thumbnails render on an **opaque white** background (legacy WebP carries no alpha at minSdk 29) — fine
on a light Card; a follow-up for dark-theme correctness would be `ARGB_8888` + `WEBP_LOSSLESS` behind
an API-30 guard. New/updated tests: `ThumbnailCacheTest` (Robolectric — write/read round-trip, delete,
missing→null), `CanvasViewModelThumbnailTest` (dirty-flag set on a finished stroke, autosave
regenerates + clears it, an unchanged page neither saves nor generates), `NoteListViewModelTest`
(+delete drops the cached thumbnail), and the instrumented `NoteListScreenTest`
(+cached note shows the `Image`, uncached shows the polyline fallback — via test tags). The on-canvas
render fidelity stays device-verified (software canvas can't host the ink meshes).

## FA-13 — Leap design system: toolbar restyle + app theme (2026-06-20)

First **Claude Design handoff** brought into the app (`Claude Design/Note app toolbar icons-handoff.zip`
→ "Note Tool Icons" + the Leap AI design system). Re-skins the note toolbar to the Leap look with a
user-selectable selected-tool style, and introduces an app-wide Leap theme. **258 app + 24 aibackend
JVM/Robolectric tests pass** (0 failures; was 256+24); `:app:testDebugUnitTest`, `:aibackend:test`, and
`:app:assembleDebugAndroidTest` build on the WSL Linux SDK. **No schema change — DB stays v7** (the
treatment is a DataStore pref). The visuals are **device/manual-verified** like the other Compose flows.
Scope was confirmed with the user up front (handoff README asks to): restyle existing tools only (no
new functional tools) + app-wide recolour.

- **Icon set → vector drawables.** All 14 handoff line icons recreated as `res/drawable/ic_*.xml`
  (pen, highlighter, pencil, eraser, eraser_pencil, text, lasso, import, record, hand, close,
  more_vert, undo, redo) — 24dp, 2px stroke, rounded caps, black stroke/fill so Compose `Icon` tints
  them. (`ic_pen` replaces the FA-pre placeholder.) The lasso loop is drawn solid — VectorDrawable has
  no stroke-dash. Registered in `ui/icons/ElrondIcons.kt` (typed `@DrawableRes` accessors) — one place
  to swap artwork on a future handoff. The full set is registered incl. tools not yet wired
  (highlighter/pencil/text/import/record) so they drop in instantly.
- **Design-token layer (`ui/theme/`).** `Color.kt` (the Leap palette, canonical hex from the brand
  guide), `LeapTokens.kt` (a `LeapTokens` data class behind `LocalLeapTokens`/`LeapTheme.tokens` for
  what Material3 doesn't capture — toolbar surface/border, the selected accent + its derived
  `accentSoft`/`accentStrong`, tile/container radii — plus `mixSrgb`, a CSS-`color-mix(in srgb,…)`
  match), and `Theme.kt` (`ElrondTheme`: a light Material3 scheme mapped from Leap tokens + brand
  radii, provides the tokens). `MainActivity` now wraps the app in `ElrondTheme` — so the **main menu
  (and the whole app) is recoloured to Leap** (Leap Blue accent/FAB, Leap Grey text, neutral
  surfaces, soft shadows). This is the reusable layer the "restructure to adopt future design updates"
  ask called for: a future handoff is a one-place token edit. Leap **typography** is wired (`Type.kt`
  → `LeapTypography`): Poppins 700/800 for display/headlines, Albert Sans (variable) for
  titles/body/UI, bundled as full OFL `.ttf` in `res/font`. The handoff `.woff2` are subset to the
  mockup's glyphs (unusable for real text), so the full faces were fetched from Google Fonts. The AI
  'handwritten' style (Caveat / `HandwritingFontFamily`) is applied at its call sites and untouched.
- **Follow-up theming fixes (same pass).** AI ink recoloured violet → **Leap Pink** (`AiInkColor`,
  distinct from navy user ink + cyan toolbar). `ElrondTheme` now sets the full **`surfaceContainer*`
  ramp + `inverseSurface`** to Leap neutrals + transparent `surfaceTint` — without them the bottom
  `NavigationBar`, the `ModalDrawerSheet`, and the on-canvas pill fell back to baseline Material purple.
- **Toolbar restyle (`ui/CanvasToolbar.kt`).** Reusable `LeapToolbarContainer` (white, hairline
  border, soft shadow, rounded), `ToolbarDivider`, and `ToolbarButton` (46dp tile, 26dp icon; takes a
  `Painter` so it serves both the bespoke drawables and Material chrome icons; optional `badge` slot).
  `NoteCanvasScreen`'s three Material `Surface`/`FilterChip` pods became: **left** = exit (close icon);
  **centre** = Pen / Eraser / Lasso / Hand(finger-draw toggle) │ Undo / Redo; **right** = to-do list
  (badge) + a **More** kebab menu holding **Clear page** (the old toolbar's Clear/finger-draw chips
  moved into icon tiles / the kebab). Undo/Redo grey out via `enabled` (handoff disabled state).
- **Selected-tool style (A/B/C), user-selectable.** New `ToolSelectedTreatment { SOFT_TILE, FILLED,
  UNDERLINE }` (`domain/`). **A · soft tile is the default**; B · filled accent and C · underline are
  chosen in **Settings → Selected tool style**, which renders the real `ToolbarButton` in each
  treatment as a live, tappable preview. Persisted via `SettingsRepository.toolSelectedTreatment`
  (DataStore string, default SOFT_TILE) → `SettingsViewModel`; `NoteCanvasScreen` collects it and
  passes it to each tool tile. The accent is **Leap Blue** (`--acc-soft`/`--acc-strong` derived via
  `mixSrgb`, matching the handoff).
- New/updated tests: `LeapTokensTest` (Robolectric — `mixSrgb` endpoints + the soft/strong shades
  match the handoff `color-mix` for Leap Blue) and `SettingsRepositoryTest` (+treatment round-trip,
  default SOFT_TILE → FILLED → UNDERLINE). The toolbar/settings Compose visuals are device-verified.
- Handoff source kept under `Claude Design/` (the zip); extracted assets are not committed beyond the
  recreated drawables. Future handoffs: drop new `ic_*` glyphs in `res/drawable` + register in
  `ElrondIcons`, and adjust `LeapTokens`/`Color.kt` for any palette change.

## FA-14 — full Leap-design app re-shell (2026-06-21)

The second, far larger Claude Design handoff (`Claude Design/Note app toolbar icons-handoff.zip`
→ the `Canvas` / `Canvas-Portrait` / `Note Tool Icons` prototypes + the Leap design system) brought
in as a **whole-app redesign**, on branch **`ui_upgrade`**. **DB is now v8** (`MIGRATION_7_8`).
**264 app + 24 aibackend JVM/Robolectric tests pass** (0 failures; was 258+24); `:app:testDebugUnitTest`,
`:aibackend:test`, `:app:assembleDebug` and `:app:assembleDebugAndroidTest` all build on the WSL Linux
SDK. The Compose visuals (the new Library shell, editor overlays, Kanban) are **device/manual-verified**
like the other Compose flows. Scope decisions were confirmed with the user up front (see the three
clarifications below).

**Three user-confirmed decisions** drove ambiguous parts: (1) the to-do Kanban gets a real 3-state
**workflow status** (not a binary map); (2) **all four** design "tweaks" become settings (pen Body/Tip,
accent colour, paper style, note-tab mode); (3) the new Calendar "connect" screen is **wired to the real**
Device/Outlook provider integration, not a placeholder.

- **New home — `LibraryScreen` (responsive).** Replaces the bottom-nav `HomeScreen` (deleted). A left
  sidebar (Leap logo, **Notes / Files / Calendar / To-do** nav with counts, a **Subjects** placeholder
  list, a "synced" footer, Settings) that is a **persistent rail in landscape and a slide-out
  `ModalNavigationDrawer` in portrait** (`BoxWithConstraints`, 720dp breakpoint). Main content
  (`LibraryContent.kt`) routes by nav: **Notes** (search/import/avatar placeholders + All/Recents/
  **Timeline**/Favorites/Unfiled tabs; a real card grid with long-press delete; Timeline embeds
  `CalendarScreen(showEvents=false)`), **Files** (placeholder), **Calendar** (provider connect cards
  wired to `SettingsRepository.calendarProvider` + the live `EventsTab`/Outlook sign-in), **To-do**
  (**List + Kanban** by workflow status, with move-status menus). FAB creates a note. `MainActivity`'s
  `notes` route now opens `LibraryScreen`.
- **Editor restyle (`NoteCanvasScreen` + `EditorChrome.kt`).** Toolbar reordered to the handoff
  (pen / highlighter / pencil / eraser / text / lasso │ undo / redo + **import + mic placeholders** +
  hand); the **left pod adds Pages + Library** buttons; the **⋮ More menu** adds Page style / Export /
  Favourite placeholders beside Clear page. New `EditorChrome.kt`: **PaperBackground** (Ruled/Plain/Dots,
  drawn behind the transparent ink layers), **EditorHeader** (note title + date, **tap-to-rename** inline,
  + a note-tab placeholder styled by `NoteTabsMode`), **PagesOverlay** (centred dialog) and
  **LibraryOverlay** (left drawer; subjects placeholder + **live** note navigation). All existing
  flows are untouched: AI `/Q` + circle-gesture + lasso-AI, the lasso selection toolbar, AI ink/error
  notes, extraction sheets, autosave, thumbnails. `CanvasViewModel` gained `pageTitle`/`pageDateLabel`
  + `renamePage`.
- **To-do workflow status (Kanban backing).** New `TodoStatus { TODO, IN_PROGRESS, DONE }`. `TodoItem`
  gains `status` and **derives `isCompleted` from it** (DONE ⇔ completed), so every binary call-site keeps
  working; the data layer keeps the `todo_items.status` INTEGER column **in sync** with `isCompleted`
  (`TodoRepository.setCompleted`/`setStatus`, `TodoDao`, the mappers' legacy guard). **`MIGRATION_7_8`**
  adds the column and backfills completed rows to DONE. The to-do **panel** also gained a status chip
  (`TodoViewModel.setStatus`); the **Kanban** groups by status with per-card move menus. Priority + due
  date + AI source-link are all retained.
- **Appearance settings (the "tweaks").** Four new DataStore prefs → `SettingsViewModel` → a new
  **Appearance** section in `SettingsScreen`: **Pen icon Body/Tip** (live `ToolbarButton` preview;
  `ElrondIcons.penToolIcon` swaps pen/highlighter/pencil ↔ their `*_tip` drawables), **Accent colour**
  (Blue/Navy/Green/Pink swatches), **Paper style** (Ruled/Plain/Dots chips), **Note-tab mode**
  (Attached/Separate chips). The accent is **app-wide**: `ElrondTheme(accent)` rebuilds the Material
  `primary`/container roles + the `LeapTokens` accent from the chosen colour (readable on-accent
  foreground via luminance), driven from `MainActivity` collecting `SettingsRepository.appAccent`.
  Selected-tool style (SOFT_TILE/FILLED/UNDERLINE, FA-13) is unchanged.
- **Domain enums stay Compose-free** (`PenIconStyle`, `AppAccent`, `PaperStyle`, `NoteTabsMode`,
  `TodoStatus` in `ai.elrond.domain`); the `AppAccent → Color` bridge lives in `ui/theme/Theme.kt`,
  honouring the by-layer rule.
- **Icons.** 4 new bespoke drawables (`ic_pen_tip`/`ic_highlighter_tip`/`ic_pencil_tip` + the rounded
  import box `ic_add`) + the `leap_mark.png` logo; registered in `ElrondIcons`. Everything else (folder,
  search, calendar, checklist, chevrons…) uses the new **`material-icons-extended`** dependency — generic
  chrome per the CLAUDE.md convention; branded tool artwork stays bespoke.
- **Placeholders (no backend, per scope):** Subjects, Files, the search field, the favourite star,
  Pages (one page per note still), note tabs, and the highlighter/pencil/text/import(record) tools.
  `NoteListScreen` is **superseded** by `LibraryScreen` but kept (its instrumented test still drives it);
  its thumbnail helpers were extracted to the shared `NoteThumbnail.kt`.
- New/updated tests: `TodoRepositoryTest` (setCompleted now 4-arg + a `setStatus` test),
  `ElrondMigrationTest` (chain extended to **v8** + a v7→v8 status-backfill test), `SettingsRepositoryTest`
  (+the four appearance round-trips), new `AppearanceEnumsTest` (defaults + `fromName`/`fromInt`), and a
  new instrumented `LibraryScreenTest` (empty state / FAB-create / long-press-delete — it replaced the
  deleted `NoteListScreenTest`). The new Compose screens (Library shell, editor overlays, Kanban) are
  device-verified.
- **Adversarial review pass (4 dimensions: bugs / Compose / architecture / fidelity) + fixes.** The
  confirmed findings were applied: (1) the editor title is **no longer a wide clickable** over the
  canvas (it swallowed S Pen strokes) — rename is now a small **edit-icon** button; (2) the inline
  rename field gets a `FocusRequester` (auto-focus + keyboard) and **commits on focus-loss**, not only
  on IME Done; (3) the unclear-request error pop-up is now a **tap-consuming scrim** so writing can't
  fall through to the canvas behind the modal; the dead `NoteListScreen`/its test were removed (the
  shared `NoteThumbnail.kt` is the only survivor) and the `TodoStatus → label/colour` map was hoisted
  to a single `ui/TodoStatusStyle.kt`. **Accepted (low):** opening a note from the editor's Library
  drawer pushes onto the nav back-stack without de-dup — back returns to the previous note, which is
  acceptable; not changed to avoid altering back semantics.

## FA-15 — Leap design fidelity pass (visual tweaks from device testing, 2026-06-22)

Acts on a device-test feedback list against the FA-14 re-shell, plus a re-read of the handoff
`Canvas.dc.html` / `Canvas-Portrait.dc.html` / `Note Tool Icons.dc.html` prototypes (exact px/hex
extracted from the CSS). On branch **`ui_upgrade`**. **No schema change — DB stays v8.** All JVM/
Robolectric tests pass and `:app:assembleDebug` + `:app:assembleDebugAndroidTest` build on the WSL
Linux SDK. The Compose visuals are **device/manual-verified** like the other Compose flows.

Key spec facts pulled from the prototypes (now the source of truth for these numbers): toolbar tile
**46dp / 26dp icon / 4dp gap**, container radius 16 / border `#e4e5e6` / shadow `0 6px 18px`
(already matched in `CanvasToolbar.kt` — unchanged); **portrait = the same toolbar at ~0.75 scale**;
the side-menu pull-out is a **Library** feature (272dp drawer, `translateX -272` when closed, a
30×76 rounded pull-tab + chevron toggle + scrim); home tabs use an **accent underline**
(`inset 0 -2px acc`), not pills; the editor header is a grey band `rgba(38,38,38,0.045)` radius 13
holding tabs + bold-Poppins title + **created** date; default tabs mode = **Separate**, paper = Dots,
accent = Leap Blue.

- **Home (`LibraryScreen` + `LibraryContent`).** Page titles removed (the redundant "Notes/Files/
  Calendar/To-do" headers) — content pulls up under a shared **`LibraryActionBar`** (chevron sidebar
  toggle in portrait · search · import · sort/view-options kebab · **DW avatar → Settings**), all
  placeholders except the avatar. The **New-note FAB** (bespoke `ic_new_note` notepad+pen, soft-accent
  rounded-square) now shows on **every** section, lower-right. Note cards restyled to white +
  hairline border (no elevated Card). The Notes tabs (All Notes / Recents / Timeline / Favorites /
  Unfiled) are now a `UnderlineTabRow` — bold label + accent underline when selected, no chip
  fill/border — and the tab state is `rememberSaveable` so it **survives rotation** (the reported
  "rotate → jumps to All Notes" bug). Sidebar: logo + "Leap Notes" → placeholder title **"Elrond"**;
  the top-right gear → a minimal **sun** icon mapped to a dark-mode toggle (no-op placeholder); To-do
  nav uses the bespoke `ic_checklist`. Portrait sidebar is now a **slide-out drawer** (offset
  animation + pull-tab + scrim) per the handoff, not a `ModalNavigationDrawer`.
- **Timeline (`CalendarScreen`).** Tightened: fixed-height day tiles (month 46dp / week 64dp), the
  month/week **toggle + prev/next arrows are inline on one row**, and tapping a day now shows that
  day's notes **inline at the bottom** as reduced home-style thumbnail cards with a created/edited dot
  label — replacing the bottom-sheet pull-up. The dead **Events mode was removed from CalendarScreen**
  (Events lives under the Calendar nav via `EventsTab`); `showEvents` is retained for source-compat.
- **To-do (board + canvas overlay).** List + Kanban restyled to the handoff. A **priority dot sits
  directly under the done tick box** (tap → priority menu); the **due date** now shows in both List
  (right side) and Kanban (card footer), tappable to a date picker; status is a dot+label pill (tap →
  move). Kanban keeps priority in the card **kebab** (plus move-status). The canvas `TodoPanel` rows
  match the board. Shared `TodoPriorityDot` / `TodoStatusPill` / `TodoPriorityColors` / `todoDueLabel`
  hoisted into `ui/TodoStatusStyle.kt` so panel and board render identically.
- **Editor chrome (`NoteCanvasScreen` + `EditorChrome`).** The note title + tabs now live in a
  distinct **grey header band** below the toolbar: title in **bolder Poppins** (`headlineSmall` +
  ExtraBold), **created** date on the right (already `page.createdAt`), and a tab row styled by the
  Attached/Separate setting (Attached = equal-width segmented row + underline divider; Separate =
  individually-rounded floating pills) — recent notes appear as inactive tabs that navigate. In
  **portrait** the three toolbar pods scale to ~0.78 via `graphicsLayer` (origin per pod: TL/TC/TR)
  with tighter padding; landscape keeps full size. The editor **to-do button uses `ic_checklist`**
  (matching the home sidebar), replacing the auto-mirrored List icon.
- **Lasso selection (`SelectionLayer`).** The dotted lasso trace, the bounding box, and the resize
  handles now use the **user's accent colour** (`LeapTheme.tokens.accent`) instead of a hardcoded
  blue.
- New bespoke drawables: `ic_checklist` (handoff `ic-checklist` path) and `ic_new_note` (notepad+pen),
  registered in `ElrondIcons`. `CalendarScreenTest` updated (Month/Week toggle + legend; the Outlook
  sign-in is now asserted by rendering `EventsTab` directly). No new unit tests — the changes are
  Compose-visual and covered by the existing instrumented suite + device verification.

## FA-15 — Leap design fidelity pass (device-feedback iterations, 2026-06-22)

Iterative on-device polish of the FA-14 re-shell, on branch **`ui_upgrade`**. **DB is now v9**
(`MIGRATION_8_9`). **266 app + 24 aibackend JVM/Robolectric tests pass** (0 failures);
`:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:aibackend:test`, `:app:assembleDebug` and
`:app:assembleDebugAndroidTest` all build on the WSL Linux SDK. The Compose visuals are
device/manual-verified. The exact px/hex came from re-reading the handoff prototypes
(`Canvas.dc.html` / `Canvas-Portrait.dc.html` / `Note Tool Icons.dc.html`); **the
`Note Tool Icons.dc.html` spec sheet is the source of truth for the tool glyphs** (the canvas
prototypes lag it). Two adversarial-review workflows (rotation fix; tabs+recent) backed the trickier
changes; their confirmed findings were applied.

- **Home (`LibraryScreen` / `LibraryContent`).** Page titles removed; a shared top action row
  (search · **import (arrow-into-tray)** · DW avatar→**Settings**) replaces them. The **New-note FAB**
  (bespoke `ic_new_note` notepad+pen, soft-accent) shows on every section. Note cards are white +
  hairline border. The Notes tabs use a **bold label + accent underline** drawn with `drawBehind`
  (a `fillMaxWidth` underline collapses to 0 inside the tabs' `horizontalScroll` row); the
  **view-options/“sort by”** kebab sits at the right end of the tab row (not the search bar). Sidebar
  shows **“Elrond”** (no logo) + a **sun/dark-mode placeholder** (top-right). **Portrait sidebar is a
  slide-out drawer** — gated on **orientation** (`maxWidth > maxHeight`), NOT a width breakpoint (a
  portrait tablet is still ~800dp wide), opened by the chevron next to the search bar **and a
  right-swipe** on the page (no edge pull-tab).
- **Rotation-safe tab/board state.** The Library content sits at different composition positions per
  orientation and the Activity recreates on rotation, so auto-keyed `rememberSaveable` stored a
  *separate* selection per orientation. Fixed with **explicit shared keys** (`library.notesTab`,
  `library.todoKanban`, `calendar.mode|anchor|selectedDay`) so the current tab/mode persists across
  rotation.
- **Timeline (`CalendarScreen`).** Taller tiles (month **68dp**, week **94dp**); inline month/week
  toggle + arrows; created/edited **dot + count** per day. Tapping a day shows its notes inline: in
  **landscape month** one horizontal row that **fills the height to the screen bottom** (tiles run
  behind the FAB, scroll off the right edge); in **week (either orientation) and portrait month** a
  vertical-scrolling adaptive grid. `DayNoteThumb`'s thumbnail uses `weight(1f)` so tiles fill their
  (bounded) height. The old Events mode was dropped from this screen (Events lives under the Calendar
  nav via `EventsTab`).
- **To-do.** Canvas to-do panel: the status pill was removed for a compact tile — **in-progress** gets
  a light accent wash (cleared once done); a **priority dot sits under the checkbox**; the **due date
  is inline with the source-note link**; the **title is single-line + ellipsised** so every tile is a
  standard height; stronger tile border (`Neutral300`). The main To-do board keeps List + Kanban (with
  the status pill) and gains a **manual add-task row pinned at the bottom** (shared `TodoAddRow`,
  end-inset to clear the FAB). Priority-dot/status-pill/due-label hoisted to `ui/TodoStatusStyle.kt`.
- **Editor chrome (`NoteCanvasScreen` / `EditorChrome`).** A distinct **grey header band** holds the
  note **tabs** (top), a **bold-Poppins title** (`headlineSmall` ExtraBold), and the **created** date.
  In **portrait** the three toolbar pods scale ~0.78 (`graphicsLayer`). The to-do button uses the
  **checklist** glyph. The **lasso** trace + bounding box + handles use the **user accent colour**.
- **Note tabs = “Recent”.** New `note_pages.lastOpenedAt` (DB **v9**, `MIGRATION_8_9`, backfilled from
  `modifiedAt`); opening a note records it (`NoteRepository.markOpened` from `CanvasViewModel`).
  `NoteListViewModel.recentNotes` = notes opened in the last **24h**, most-recently-opened first —
  drives both the home Recents tab and the editor tabs. `NoteTabPills` **always renders the current
  note first as the active tab** (independent of the DB round-trip), so the active tab is never
  missing.
- **Attached tab mode removed (pending redesign).** The Attached-in-toolbar rendering and its
  Settings option were deleted at the user's request; the editor always shows tabs in the grey band.
  The `NoteTabsMode` enum + `SettingsRepository`/`SettingsViewModel` plumbing are left **dormant** for
  the planned redesign.
- **Toolbar icons → latest handoff.** Tool glyphs are at the spec-sheet **1.2** stroke weight (lighter
  than the canvas prototype's 2). `ic_highlighter` regenerated to the latest **chisel-marker** (the
  one glyph still on the old diamond). Bespoke `ic_import` (tray), `ic_new_note` (notepad+pen),
  `ic_checklist`, **`ic_pages`** (grid) + **`ic_folder`** (Library) — the last two replace the heavier
  Material `GridView`/`Folder` so the left pod matches the 1.2 weight. Tile/icon **sizing already
  matched** the spec (46dp tile / 26dp icon / 4dp gap / 12dp tile / 16dp container).
- New/updated tests: `ElrondMigrationTest` (chain → **v9** + a v8→v9 `lastOpenedAt` backfill test),
  `NoteListViewModelTest` (+`recentNotes` 24h window + descending order), `CalendarScreenTest` updated.

## FA-16 — Subjects (folder hierarchy) (2026-06-23)

First post-FA-15 *feature*: a hierarchical **Subjects** (folder) system to organise notes, on branch
**`fa-16-subject-implementation`**. **DB is now v10** (`MIGRATION_9_10`). **295 app + 24 aibackend
JVM/Robolectric tests pass** (0 failures; was 266+24); `:app:test`, `:app:assembleDebug` and
`:app:assembleDebugAndroidTest` build on the WSL Linux SDK. The Compose surfaces (sidebar tree, colour
picker, breadcrumb, Quick Nav) are device/manual-verified like the other Compose flows.

**Three product decisions confirmed with the user up front** (the spec's "known unknowns"):
1. **Single-subject, file-explorer model** — a note is **unfiled or filed into exactly one subject**
   (NOT multi-subject). The breadcrumb shows the *ancestry path* of that one subject: ancestor
   **dots** left-to-right, then the containing subject as a named **pill**.
2. **Cascade delete** — deleting a subject deletes its descendant subfolders too (with a confirm);
   **notes are never deleted**, they just become unfiled (FK cascades clear the membership rows).
3. **Colour palette** — a generated **66-colour pastel spectrum** (`SubjectPalette`), not the Leap
   brand colours.

- **Schema (DB v10, `MIGRATION_9_10`).** Two tables: `subjects` (id, parentId → self-ref FK
  `ON DELETE CASCADE`, name, colorId, **sortOrder**, createdAt, modifiedAt — `sortOrder` added beyond
  the spec's column list because drag-reorder needs a persistent order) and `note_subjects`
  (**pageId PRIMARY KEY** + subjectId, both FKs `ON DELETE CASCADE`). pageId-as-PK is what enforces
  **≤1 subject per note** at the DB level; no row = unfiled. No backfill (tree starts empty, every
  note starts unfiled). The migration SQL was verified column-for-column against the exported
  `10.json` (incl. both cascade FKs + the `index_subjects_parentId` / `index_note_subjects_subjectId`
  indices).
- **Domain (pure JVM).** `Subject` (data class); `SubjectPalette` (66 = `HUE_COUNT` 11 × `SHADE_COUNT`
  6 pastel ARGB ints via a pure HSL→RGB, indexed by a stable `colorId`, `normalize` wraps any int);
  `SubjectTree` (pure `build` with orphan-rescue + **cycle rescue** so no subject ever silently
  vanishes, `pathTo` ancestry/breadcrumb, `flatten` pre-order, `reorder` index-move + `move`
  direction-step). All Compose/Android-free — the `colorId → Color` bridge is `subjectColor()` in the
  ui layer (per the `AppAccent` precedent).
- **Data.** `SubjectRepository` (CRUD + `assignNote` single-subject upsert/`REPLACE`/unfile + `reorder`
  by sortOrder; injectable clock/id/colour seams like `NoteRepository`); `SubjectDao` + `NoteSubjectDao`;
  mapper. `SettingsRepository` gained DataStore-persisted **sidebar state**: `expandedSubjectIds`
  (`stringSetPreferencesKey`) + `selectedSubjectId`. **`SessionNotesTracker`** (a `@Singleton` in-memory
  `StateFlow<List<String>>`, placed in `data/` alongside the analogous in-memory `OutlookAuthProvider`)
  records notes opened **this foreground session** for the editor tabs.
- **Presentation.** `SubjectViewModel` (`@HiltViewModel`): `tree`/`subjectsById`/`noteSubjects`/
  `expandedIds`/`selectedSubjectId` (a stale id resolves to null) / `selectedPath` StateFlows + CRUD,
  `toggleExpanded`, `selectSubject`, `moveSubject(id, up)`, `assignNote`. **`deleteSubject` and
  `moveSubject` read a fresh `subjectRepository.observeSubjects().first()` snapshot — NOT the
  `WhileSubscribed` `.value` caches** (which return their empty initial value when no collector is
  active, which would silently defeat the delete-selection-clear guard and no-op a reorder; caught in
  the adversarial review). `NoteListViewModel` gained `sessionNotes` (session ids → pages) + `renameNote`.
- **Canvas tabs = this session (not 24h).** Per spec, the editor note tabs now show notes opened in the
  **current foreground session only** (in-memory `SessionNotesTracker`), distinct from the persisted
  24h `recentNotes` that still backs the home **Recents** tab. `CanvasViewModel` records each open;
  **`MainActivity.onStop` clears the session guarded by `!isChangingConfigurations`** (survives rotation,
  resets on real background).
- **UI.** `SubjectTreeView` (one reusable recursive tree, `editable` vs read-only): tap = select/filter,
  chevron = expand/collapse (persisted), colour dot = picker, inline **+** = add child, **long-press** =
  context menu (rename / add subfolder / change colour / delete), **drag handle** = reorder. *Reorder is
  a direction-based **single-step move** (drag up/down past a small threshold → move one position),
  computed in the VM by id from a fresh snapshot — NOT pixel-distance ÷ row-height, which the review
  showed mis-targets once a sibling is expanded (its descendants render between siblings); the handle
  also does not translate, so the gesture source stays under the finger.* The home **sidebar**
  (`LibraryScreen`) renders the editable tree (scrollable, with an add-root **+**); selecting a subject
  jumps to the now-**subject-filtered** Notes grid with a tappable **breadcrumb tab bar**
  (`All Notes › S1 › S2`). Note **cards** gained a ⋮ menu (Rename / Move to subject / Delete) + the
  ancestry **breadcrumb** (`SubjectBreadcrumb`). The canvas **Library overlay → "Quick Nav"**: a
  **read-only** subject tree (expand/collapse only) + an **"Unfiled"** notes list. The colour picker is
  a pastel swatch grid (`SubjectColorPicker`); "Move to subject" is an indented `SubjectPickerDialog`.
  The Notes **UNFILED** tab now shows notes with no subject (was all notes).
- **Reviewed + simplified.** An adversarial `/code-review` (correctness, Compose, regressions) and a
  `/simplify` pass (reuse/simplification/efficiency/altitude) ran over the diff; confirmed findings were
  applied (the `WhileSubscribed.value` staleness fixes, the reorder rework, a bigger colour-dot tap
  target, a deduped callback, a redundant cycle-guard, stale KDoc). The two-mechanism "recent" overlap
  (`recentNotes` vs `sessionNotes`) was reviewed and **kept** — the spec mandates session tabs be
  in-memory and session-scoped, distinct from the 24h home Recents tab.
- New/updated tests: `SubjectTreeTest` (build/orphan/cycle, pathTo, flatten, reorder, move),
  `SubjectPaletteTest` (66 colours, opaque/distinct, normalize), `SessionNotesTrackerTest`,
  `SubjectRepositoryTest` (Robolectric — CRUD/sortOrder, single-subject reassign, cascade delete of
  descendants + memberships, note-delete cascade, reorder), `SubjectViewModelTest` (tree, stale-id→null,
  path, delete-clears-selection, move, create-expands-parent), `ElrondMigrationTest` (chain → **v10** + a
  v9→v10 table-creation test), `SettingsRepositoryTest` (+sidebar state), `NoteListViewModelTest`
  (+`sessionNotes`, +`renameNote`), and instrumented `SubjectTreeViewTest` (expand/collapse, colour
  picker, breadcrumb pill tap); `LibraryScreenTest` updated to supply the `SubjectViewModel`.

### FA-16 device-feedback follow-ups (2026-06-25)

Three fixes from device testing (296 app + 24 aibackend tests pass; `:app:test`/`assembleDebug`/
`assembleDebugAndroidTest` build):
- **Quick Nav shows notes nested in the subject tree** (was folders-only). `LibraryOverlay` now renders
  a unified file-explorer tree: each subject expands to reveal its child subjects **then its notes**
  (`QuickNavSubject`/`QuickNavNote` in `EditorChrome.kt`, fed a `notesBySubject: Map<String?,
  List<NotePage>>` — null key = unfiled, rendered at root under an "Unfiled" label). Tapping a note
  opens it in a canvas tab. Inline with the "SUBJECTS" header is a **"current note" locator** (a
  `MyLocation` line icon) that expands the path to and highlights the open note —
  `SubjectViewModel.expandToSubject` (fresh-snapshot `pathTo` → `SettingsRepository.expandSubjects`
  batch). The read-only `SubjectTreeView` is no longer used by Quick Nav (still used by the editable
  home sidebar).
- **Note tabs survive background/foreground; reset only on process death.** Removed
  `MainActivity.onStop`'s `SessionNotesTracker.clear()` — the `@Singleton` in-memory tracker now persists
  across open/close (process lifetime) and resets naturally only when the app is swiped away / killed
  (process death). (Supersedes the original FA-16 "reset on background" behaviour.)
- **Editor ✕ is a Home button, not Back.** `NoteCanvasScreen`'s close action (`onHome`) now
  `popBackStack(ROUTE_NOTES, inclusive = false)` — note→note→note hops all land back on the library home
  in one tap, instead of popping one note at a time.
- **Editor tabs keep a stable order.** `SessionNotesTracker.recordOpened` now appends new notes and
  leaves an already-open note's position unchanged (was move-to-front), and `NoteTabPills` renders the
  session list in order and only moves the active highlight (no longer force-renders the current note
  first). Re-selecting a tab no longer reshuffles the bar.
- Tests: `SubjectViewModelTest` (+`expandToSubject` path), `SettingsRepositoryTest` (+`expandSubjects`
  batch). The Quick Nav tree + locator are device/manual-verified Compose.

## FA-17 — organic loaders + AI logo (2026-06-25)

Brings in the **`organic-loaders`** Claude Design handoff (`Claude Design/organic-loaders/`): the
AI "thinking" indicator becomes a user-selectable **organic loader**, and a static **AI logo** (the
handoff's icon 02c-04) marks AI items. On branch **`fa-17-organic-loaders`**. **No schema change —
DB stays v10** (both settings are DataStore prefs). `:app:compileDebugKotlin`, `:app:testDebugUnitTest`,
`:aibackend:test`, `:app:assembleDebug` and `:app:assembleDebugAndroidTest` build on the WSL Linux
SDK. The loader/logo visuals are **device/manual-verified** like the other Compose canvas work (the
goo `RenderEffect` needs a hardware canvas — it can't be unit-tested). **Scope was confirmed with the
user up front** (the four clarifications below).

**User-confirmed decisions:** (1) the loader picker offers the **7 loaders the design's selection
grid showcases** — `2 orbit · 5 split · 7 comet · 11 lava · 14 pinch · 15 rings · 17 cluster`, rebuilt
as Compose animations, **default 17** (the other CSS loaders / `d` dark variants are not offered);
(2) a **single colour setting** (`Colour`/`Black`, default Colour) drives **both** the loader palette
and the logo drawable (the handoff `white` variant is dropped); (3) the to-do source-note link drops
the `🔗` and shows the **AI logo inline at text height before the link**, only when a link exists
(no link → a plain tile, no "AI" text — that fallback was already unreachable dead code); (4) the
lasso AI button shows the **logo only**.

- **Loaders rebuilt in Compose (`ui/loaders/OrganicLoaders.kt`).** The handoff loaders are CSS
  metaball ("gooey") animations — blurred circles passed through an alpha-threshold so overlapping
  blobs fuse. Recreated with a **`RenderEffect`** (blur → alpha-threshold `ColorMatrix`, the handoff's
  `feColorMatrix` `A' = 22·A − 10` scaled to 0..255, RGB untouched so the Leap colours survive) applied
  via `Modifier.graphicsLayer { renderEffect = … }`. **The goo is Android 12+ (`RenderEffect`, API 31);
  below that the `goo` modifier is a no-op and the circles simply overlap** — a graceful degrade
  (`minSdk = 29`; fine on the Galaxy Tab S target). Each loader animates colored `Box` circles with
  `rememberInfiniteTransition`, geometry ported from the CSS keyframes as fractions of the loader size;
  the animated state is read **inside** the `graphicsLayer` lambda (per the FA-10 rule — layer re-reads
  per frame, no recomposition/mesh redraw). None of the 7 chosen loaders need SVG path-morphing (all are
  circle/blob based). `OrganicLoader(style, colorMode, size)` is the dispatcher.
- **AI logo (`ui/AiLogo.kt`).** The handoff icon 02c-04 bundled as `res/drawable-nodpi/ai_logo_color.png`
  + `ai_logo_black.png` (white ignored), registered in `ElrondIcons`. `AiLogo` picks the drawable from
  the colour mode (a multi-colour brand mark, so **not** tinted at the call site). `AiSourceLink` is the
  inline logo + note-title link that replaced the `🔗`-prefixed `Text` at all three to-do label sites
  (`TodoPanel`, `LibraryContent` ×2), sized to the label's line height.
- **App-wide appearance via CompositionLocals (`ui/AiAppearance.kt`).** `LocalAiColorMode` +
  `LocalAiLoaderStyle` are provided once in `MainActivity` from the persisted settings (the same
  top-level approach as the app accent) so the logo and loader render consistently across screens
  without per-ViewModel plumbing. `NoteCanvasScreen`'s on-canvas thinking indicator (`AiLoadingIndicator`)
  now renders `OrganicLoader` from the locals (the old 3-ink-dots composable is gone); the lasso AI
  button (`SelectionLayer`) and the to-do links read `LocalAiColorMode` through `AiLogo`.
- **Domain enums (Compose-free):** `AiLoaderStyle(number, label)` (the 7 loaders, default `CLUSTER`/17)
  and `AiColorMode { COLOR, BLACK }` (default COLOR) in `ai.elrond.domain`; the colour→colours /
  drawable bridges live in `ui`. New DataStore prefs `ai_loader_style` / `ai_color_mode` in
  `SettingsRepository`, surfaced via `SettingsViewModel`.
- **Settings → "AI assistant" section.** A **Colour** toggle (Colour/Black, each a live `AiLogo`
  preview) and a **Loader style** `FlowRow` of all 7 loaders as live, tappable `OrganicLoader` preview
  tiles (in the current colour mode), selected highlighted — mirrors the FA-13/14 tool-style/accent
  preview pattern.
- New/updated tests: `SettingsRepositoryTest` (+`aiLoaderStyle`/`aiColorMode` round-trips + defaults),
  `AppearanceEnumsTest` (+FA-17 defaults/`fromName` + the seven loader numbers). The loaders, the goo
  effect, and the logo placements are device-verified.

## Editor window-insets fix (2026-06-26)

Small post-FA-17 UI fix on `main` (commit "UI toolbar tweaking") — no schema/test change, Compose-visual
and device-verified on a Galaxy Tab S in both orientations. The note editor (`NoteCanvasScreen`) is a
full-bleed `BoxWithConstraints` that ignored window insets even though the app runs `enableEdgeToEdge()`,
so its floating chrome was pinned with hardcoded, orientation-split padding (`tabsTop` 14/28 top,
`sidePad` 16/48 side). Result: the toolbar + title/tabs tucked under the notification bar in portrait,
floated low in landscape, and the side gap drifted between rotations. (`LibraryScreen` was unaffected — it
uses a `Scaffold`, which applies system-bar insets for free.)

- **Chrome now anchors to real insets + a constant gap, identical in both orientations** (see the
  edge-to-edge architectural rule above): `topGap = WindowInsets.statusBars top + N.dp`,
  `leftPad/rightPad = (displayCutout ∪ navigationBars) side + M.dp` (direction-aware). The canvas itself
  stays full-bleed (paper + ink still cover the whole screen, including behind the status bar). The grey
  header band's vertical offset (`headerTop`) derives from `topGap`, so the toolbar→title spacing is
  preserved. The two tunables are named **`topGap`** ("top gap") and **`leftPad`/`rightPad`** ("side
  spacing") for device-feedback tweaking. As tuned on device: top gap `+0.dp`, side spacing `+14.dp` (so
  the toolbar pods line up with the header band, which keeps its own 14dp margin).
- **Toolbar scale is now constant `0.78×` in both orientations** (was `0.78f` portrait / `1f` landscape) —
  landscape no longer enlarges the toolbar/icons on rotation. The `portrait` flag (formerly only used to
  pick the scale) was removed.
- **Opaque editor header band.** `EditorChrome.HeaderBandColor` changed from a translucent
  `Color(0xFF262626).copy(alpha = 0.045f)` to the opaque palette token `Neutral100` (`#F2F3F3`) — same
  apparent grey over white paper, but the paper texture (ruled lines / dots) no longer bleeds through the
  title + tabs band.

## FA-18 — prefix `/Q` trigger mode (2026-06-26)

Adds a third AI activation mode alongside the suffix `/Q` command and the circle gesture: write the
command **first** on its own line, then write the question — a pause (inactivity) signals the end and
sends it. A listening indicator rises from the bottom of the canvas while it waits. On `main`.
**304 app + 24 aibackend JVM/Robolectric tests pass** (0 failures; was 296+24);
`:app:testDebugUnitTest` + `:aibackend:test` build on the WSL Linux SDK. **No schema change — DB stays
v10** (both new prefs are DataStore). The on-canvas indicator + slide-out→hand-off transition are
device/manual-verified like the other Compose canvas flows. Scope (indicator behaviour, `/Q`-ink
retention, context inclusion) was confirmed with the user up front.

- **`TriggerMode.PREFIX_COMMAND`** (third enum value; `fromName` handles it via `valueOf`). The
  Settings "AI activation" selector is now three chips — **After prompt** (COMMAND) / **Before prompt**
  (PREFIX_COMMAND) / **Circle gesture** (GESTURE) — and the validated trigger-char field is shared by
  the two command modes.
- **`PrefixTriggerState`** (`domain/`, in-memory only): `Idle` / `Listening(triggerStrokeIds,
  promptStrokeIds)` / `Processing`. Exposed by `CanvasViewModel.prefixTriggerState`.
- **`QueryTriggerDetector.isStandaloneTrigger` + `firstStandaloneTriggerCandidate`** — true only when a
  recognized line IS the trigger and nothing else (a prompt either side makes it false). The candidate
  form scans the top-N ML Kit guesses with the same rank cap as `firstTriggerCandidate`, so a `/Q` the
  best guess garbled still fires.
- **Detection scans, doesn't just check the last line.** Unlike the COMMAND path (which only recognizes
  the last-drawn line), `CanvasViewModel.handlePrefixTrigger` (only while `Idle`) scans the recognized
  lines for a standalone-trigger line — skipping over-long lines (`MAX_PREFIX_TRIGGER_STROKES = 6`) and
  any line whose strokes are already in `consumedPrefixTriggerIds`. This is what makes the listening
  indicator appear even when the user flows straight from the command into the question (the 900ms
  detection debounce resets on every stroke, so by the time it fires the `/Q` is no longer the last
  line). The trigger line's stroke ids are recorded (for cancel) and marked consumed so a `/Q` left on
  the canvas (fired or abandoned) is never re-detected; anything already written below it pre-seeds the
  prompt.
- **The AI waits until writing has genuinely stopped (and always gets the full page context).**
  `runPrefixQuery` recognizes the prompt strokes as the question **plus every other stroke on the page
  as context** (the chosen behaviour — same `Handwritten question: … / Other notes on the page …`
  envelope as the COMMAND path), placing the answer **below the question** with both the `/Q` and the
  question ink kept (also chosen), via `submitQuery(..., bypassDedup = true)`. Two timers gate the send:
  - an **inactivity** timer (`prefixTriggerDelayMs`, default 0.5s) restarted on each *finished* prompt
    stroke; on fire → `Processing` → `runPrefixQuery` → back to `Idle`.
  - **Pen-down holds off the send.** `CanvasViewModel.onWritingStarted()` (called from `InkCanvas` on the
    pen `ACTION_DOWN`) cancels the pending inactivity job while the pen is engaged, so the timer only ever
    elapses when the pen is **lifted and idle** — it can't fire mid-stroke or between the strokes of the
    question, so the AI never answers on a partial question. (The earlier risk: the timer only restarted
    on stroke *finish*, so a short delay could elapse during a long stroke / pause.)
  - a **no-prompt** timeout (`prefixNoPromptTimeoutMs`, default 2s) started when listening begins with no
    prompt yet: if nothing is written in time, the session is quietly abandoned and the `/Q` is **left on
    the canvas as normal ink** (no stroke removal).
- **Cancel (✕).** `cancelPrefixTrigger()` removes the `/Q` strokes AND everything written after them (the
  in-progress question) as one undoable step; strokes before `/Q` (and any drawn after a finished block)
  are untouched.
- **Indicator + hand-off.** `NoteCanvasScreen.PrefixListeningIndicator` (bottom-centre, `AnimatedVisibility`
  slide-in/out) shows the user's organic loader + a ✕ cancel **only while `Listening`**. When the query
  starts (`Processing`) it slides out and the existing note-position thinking loader (driven by `aiState`)
  takes over — the loader appears to move up to where the answer lands.
- **Settings.** New **Long** DataStore prefs (the app's first `longPreferencesKey`s) on
  `SettingsRepository`: `prefixTriggerDelayMs` (default 500, clamp 200–3000) and `prefixNoPromptTimeoutMs`
  (default 2000, clamp 1000–10000), surfaced via `SettingsViewModel`. The **Before prompt** mode shows a
  **Listening delay** slider (0.2–3.0s) and a **No-prompt timeout** slider (1–10s).
- **DI / test seams.** `CanvasViewModel` gains nullable `prefixTriggerDelayMsFlow` /
  `prefixNoPromptTimeoutMsFlow` constructor params (collected into vars, defaulted to 500/2000 so JVM
  tests are deterministic with `advanceTimeBy`); the `@Inject` secondary ctor wires them from
  `SettingsRepository`.
- New/updated tests: `QueryTriggerDetectorTest` (+`isStandaloneTrigger` standalone/suffix/empty/custom,
  `firstStandaloneTriggerCandidate` recovery), `CanvasViewModelAiTest` (+standalone `/Q` → Listening,
  prompt-tracking + inactivity fires the query, **pen-down holds the send until writing finishes**, cancel
  removes trigger+prompt but not earlier ink, 2s no-prompt timeout leaves the ink), `SettingsRepositoryTest`
  (+both Long prefs: defaults, round-trip, clamp). The indicator/slider Compose visuals are device-verified.

## FA-19 — configurable finger gestures + S Pen button (2026-06-27)

User-configurable canvas gestures: multi-finger taps and the S Pen side button, each bound to a
canvas action in Settings. On branch **`fa-19-finger-commands`**. **323 app + 24 aibackend
JVM/Robolectric tests pass** (0 failures; was 304+24); `:app:testDebugUnitTest` + `:aibackend:test`
build on the WSL Linux SDK. **No schema change — DB stays v10** (all new prefs are DataStore). The raw
touch/`MotionEvent` detection (the trackers, the button observer) is **device/manual-verified** like
the other ink flows; the binding/dispatch logic + settings round-trips are JVM-tested. Scope (which
gestures, defaults, gating, hold semantics, detection mechanism) was confirmed with the user up front.
**Device-confirmed on a Galaxy Tab S (2026-06-28):** all finger-tap and S Pen-button gestures pass —
including the double-click tool toggle and hold-to-erase **from the Lasso tool** (the shared-tracker +
top-level button observer routing) and the 150ms hold threshold (clicks register cleanly).

- **Shared action enum.** `domain/FingerGestureAction` (Compose-free, `fromName`): `NONE / UNDO /
  REDO / LAST_TOOL_SWAP / SELECT_PEN / SELECT_ERASER / SELECT_LASSO / SELECT_HAND`. `SELECT_HAND`
  toggles finger-draw (`setStylusOnly(false)`) — "finger draw" is the palm-rejection setting inverted,
  not a `CanvasTool`. Reused by both finger taps and stylus clicks. `CanvasViewModel.performGestureAction`
  is the one dispatch site (a `toggleTool` flag, see double-click below); `selectTool` now records
  `previousTool` so `LAST_TOOL_SWAP` works.
- **Finger gestures (`domain/FingerGesture` sealed: Two/Three-finger × single/double tap).** Defaults
  (notation *taps × fingers*): **1×2 Undo, 1×3 Redo, 2×2 last-tool swap, 2×3 unbound**. A master
  `fingerGesturesEnabled` switch (default on) heads its own Settings section with four action dropdowns.
  **Fully decoupled from palm rejection** — a deliberate 2-/3-finger tap is detected whether stylus-only
  is on or off (it's distinguishable from a resting palm), and multi-finger taps never draw; in
  finger-draw mode a nascent finger stroke is cancelled the instant a 2nd finger lands. Single taps fire
  instantly when that finger-count's double-tap is unbound, else wait the ~300ms double-tap window.
  Detected by `InkCanvas.FingerGestureTracker` (manual multi-pointer state machine — max-finger-count +
  duration + movement gates; `GestureDetector` can't do multi-pointer tap counts).
- **S Pen side button (`domain/StylusHoldTool`: NONE/PEN/ERASER/LASSO + `toCanvasTool`).** Standard
  Android stylus button (`BUTTON_STYLUS_PRIMARY`) — **no new dependency**; works while the pen is on or
  hovering just above the screen. Master `stylusButtonEnabled` (default on) + three bindings:
  **press-and-hold → tool (default Eraser, momentary** — springs to the tool while held, reverts on
  release via `toolBeforeHold`, so it binds to a tool not the full action list); **double-click →
  action (default Lasso)**; **single-click → action (default off)**. The **double-click toggles** a tool
  binding: clicking again while already on the bound tool cycles back to the previous tool
  (Lasso → previous → Lasso). Detected by `InkCanvas.StylusButtonTracker` (edge-detects the button on
  `buttonState`, robust to `ACTION_BUTTON_PRESS` vs hover/move reporting; hold threshold **150ms** so
  erase engages quickly; a pending click never arms a hold so a short threshold can't swallow a
  double-click; ends a stuck hold on hover-exit/detach).
- **Works in every tool mode (incl. Lasso).** In Lasso mode the full-screen `SelectionLayer` overlay
  owns input and the InkCanvas touch listener early-returns, so the button tracker would miss events.
  Fix: **one shared `StylusButtonTracker`** (hoisted to `NoteCanvasScreen`, owned via `DisposableEffect`)
  is fed by **both** InkCanvas **and** an always-present transparent top-level observer `View` that
  watches the button via generic-motion (hover) events — a real `View`, so it carries the true
  `BUTTON_STYLUS_PRIMARY` bits (a Compose-reconstructed event would not). The observer handles generic
  motion only (returns false), so drawing / lasso / toolbar touch passes through. Edge-detection on
  `buttonState` dedupes the two feed paths. The finger + button trackers are also fed before the lasso
  early-return.
- **Settings.** Removed the duplicate **stylus-only (palm rejection) row** — the toolbar finger-draw
  button stays the live control (the underlying `stylusOnly` pref + logic are untouched). New **Finger
  gestures** and **S Pen button** sections; the action picker is a generalised `GestureDropdownRow`
  (reused for finger taps, stylus clicks, and the hold-tool). New DataStore prefs:
  `fingerGesturesEnabled` + four finger-action keys + `stylusButtonEnabled` + `stylusHoldTool` +
  `stylus{Double,Single}ClickAction` (string-backed enums, mirroring `triggerMode`).
- **DI / test seams.** `CanvasViewModel` gains nullable flow params for all of the above (collected into
  `MutableStateFlow`/vars, defaulted to the documented defaults so JVM tests are deterministic without
  flows); the `@Inject` secondary ctor wires them from `SettingsRepository`. Public `fingerGesturesEnabled`
  / `stylusButtonEnabled` StateFlows + `isDoubleTapBound` / `isStylusDoubleClickBound` are read by the
  InkCanvas trackers to gate detection.
- New/updated tests: `FingerGestureActionTest` (`FingerGestureAction` + `StylusHoldTool` parsing/mapping),
  `CanvasViewModelGestureTest` (finger dispatch incl. default bindings + a SELECT_HAND/SELECT_LASSO flow;
  stylus click dispatch, double-click tool toggle, momentary hold spring+revert, None-hold no-op),
  `SettingsRepositoryTest` (+all nine new pref round-trips). The trackers' raw-`MotionEvent` detection and
  the Lasso-mode observer routing are device-verified.

## FA-21 — lasso & AI-box tweaks (2026-06-29)

Lasso/selection + AI-response-box improvements, **merged into `main`** (branch
`fa-21-lasso-ai-box-tweaks` deleted after merge). **DB is now v15** (`MIGRATION_14_15`). All
JVM/Robolectric unit tests pass and `:app:testDebugUnitTest`,
`:aibackend:test`, `:app:compileDebugKotlin` + `:app:compileDebugAndroidTestKotlin` build on the WSL
Linux SDK. The Compose/canvas visuals + gesture flows (handles, hold-to-select, write-over, zoom
scaling) are **device/manual-verify pending** like the other ink flows. Scope was confirmed with the
user up front (4 questions): **fully-mixed** selection, AI-box menu layout, resize semantics, tight
width. Ran `/simplify` (4 cleanup agents) and a high-effort `/code-review` workflow over the diff;
confirmed findings were applied (below).

- **AI boxes are first-class selectable objects, unified into the stroke `SelectionState`.**
  `SelectionState` gains `aiNoteIds` (+ `hasAiNote` / `isSingleAiNote` / `count`), and **`lockRatio`
  now defaults ON** (app-wide, incl. pure-stroke lasso). A lasso (`selectByLasso` now hit-tests AI
  box centres too) **or a 1.5s press-and-hold** selects strokes and AI boxes together; one
  `commitTransform` moves/scales both through the same path. In `transformAiNote` a **pure move**
  never resizes the box, while a **scale** grows an AI box's width/height **and font** (`fontScale`)
  together so it stays proportional (no page-edge clamp — see the device-feedback note below).
  Duplicate/Delete/Copy/Cut/Paste all
  operate on both collections; the clipboard holds AI boxes too. The old UI-only `selectedNoteId`
  state + off-box tap-catcher in `NoteCanvasScreen` were removed.
- **Undo now covers AI boxes (FA-21 unification fix).** The history stack is a `HistorySnapshot`
  (strokes **+** persistable AI notes); every lasso edit (move/scale/delete/cut/paste/reflow, alone
  or mixed) pushes one snapshot and `undo`/`redo` restore both (live error notes preserved). Before
  this, a mixed delete left the AI box irrecoverable.
- **AI box is a tight, content-hugging box that scales with zoom (`AiInkNoteView`).** It renders
  **below `InkCanvas`** so handwriting strokes always sit in FRONT of the AI text, and is **passive**
  (no pointer input) so the pen draws straight over it; selection chrome is drawn separately on top.
  Width hugs the text up to the full-line cap (`widthIn(min, max)`); width/height/font
  all multiply by `pageTransform.scale` so the text zooms with the grid (`PageTransform.safeScale`
  guards the divide). The view reports its measured page-space size back
  (`reportAiNoteMeasuredSize`) so the selection box hugs it. The legacy ✕ remove control is gone —
  Delete in the new menu replaces it.
- **Resize semantics.** Corner handles (now centred ON the corner) scale ratio-locked and grow/shrink
  the **font**; a lone AI box also gets **left/right edge handles** that reflow the width at a
  constant font size (narrower = more lines = taller, `reflowAiNoteWidth`, undoable via
  `beginAiNoteReflow`). The dashed box maps **both** corners page→screen so it's correctly sized at
  any zoom.
- **Selection chrome split out (`SelectionDecorations`).** `SelectionLayer` is now only the LASSO
  **input** layer (catcher + clipboard bar); the box/handles/toolbar render in `SelectionDecorations`
  for **any** selection in **any** tool (so a held-selected AI box shows chrome in pen mode). It owns
  no full-screen input — empty areas fall through to the canvas. **Toolbar variants:** a stroke
  selection shows Duplicate / Delete / **AI (logo 2× bigger, 40dp)** + a ⋮ kebab (Copy / Cut /
  **Unlock↔Lock aspect** toggle / Group); a selection **including an AI box** drops the AI button and
  **promotes Copy** to the top row (`[Copy][Delete]` + kebab Duplicate / Cut — **no aspect toggle**:
  an AI box is always ratio-locked, corner scales the font and edge handles reflow the width). The ⋮
  glyph is enlarged (`KEBAB_GLYPH_SP`).
- **Write-over + hold-to-select live in `InkCanvas`.** A deselected AI note has zero pointer input,
  so the pen writes over it. `InkCanvas`'s touch listener arms a 1.5s `Handler` hold over an AI box
  (`aiNoteAt`); movement past slop cancels it (a real stroke → write-over), a fired hold cancels the
  nascent stroke and `selectAiNote`s. A DOWN on the current selection's box/handles (screen-space
  margin `SELECTION_TOUCH_MARGIN_PX` covers the edge-straddling handles) is declined so the chrome's
  drag works; a DOWN elsewhere deselects — except the **eraser never declines** (it erases ink under
  the box).
- **Persistence.** `MIGRATION_14_15` adds `ai_notes.fontScale REAL NOT NULL DEFAULT 1.0`
  (entity/mapper round-trip it; in-memory `isError`/`sourceQuestion`/`suggestedQuestion` unchanged).
- New/updated tests: `StrokeSelectionTest` (+`SelectionState` helpers / lock default),
  `CanvasViewModelAiTest` (select→commit-move→delete, ratio-scale grows font+width, reflow+remove,
  measured-size hugs bounds, **delete-undo restores the AI box**), `CanvasViewModelSelectionTest`
  (lock default ON), `CanvasViewModelPersistenceTest` (commit-move + reflow autosave),
  `ElrondMigrationTest` (chain → **v15** + the fontScale-default backfill test).
- **Known / device-verify follow-ups (from the review, not fixed this pass):** a fresh auto-selected
  /Q answer flashes a fallback-sized box for one frame before the first measure; the selection box is
  one frame stale right after a bake until the view re-measures; a hold shows a stationary wet dot for
  the 1.5s before it's cancelled and the box isn't draggable until the finger lifts; edge-reflow
  rebuilds the AI-notes list per drag frame; and a tap on the floating toolbar *may* (Compose-interop
  dependent) deselect before firing — all low-severity, flagged for the device pass.

### FA-21 device-feedback round (2026-06-30)

First on-device pass; fixes committed and merged to `main`:
- **Strokes render in front of AI text.** AI answer notes are drawn **below `InkCanvas`** (they were
  a Compose overlay above the ink), so handwriting always sits on top — and write-over is now
  automatic (the pen hits the ink layer first).
- **Move/scale regression fixed.** `transformAiNote` had applied a page-edge width clamp using the
  wrap **cap** (`widthPx`, usually far wider than the hugged box), which (a) shrank the font on every
  plain **move** and (b) cancelled a corner **scale** near the page edge ("scale resets"). A pure
  move now never touches size/font; a scale grows width + font together with no cap-based clamp.
- **AI-box menu: no aspect toggle.** Removed **Unlock aspect** from the AI-response kebab — an AI box
  is always ratio-locked (corner scales the font; edge handles reflow the width). The toggle +
  grouping stay stroke-only.

### FA-20/FA-21 AI-box follow-ups (2026-06-30)

Two small post-merge fixes on `main`, both device-verified on a Galaxy Tab S; Compose/touch-only
(no schema/test change). `:app:compileDebugKotlin` clean on the WSL Linux SDK.

- **AI text box zoom scaling — render once at base size, scale via `graphicsLayer`** (`ui/AiInkNoteView.kt`).
  FA-20 made the AI answer box keep its page position + width when pinch-zooming, but it recomputed
  `fontSize`/`widthPx`/`heightPx`/`lineHeight` by `× transform.scale` on **every** zoom step, so the
  text's vertical metrics drifted from the page grid: line spacing stretched zoomed out (text spread
  to a full page) and cramped zoomed in (~13 lines). Width + font looked right; only line spacing
  drifted. Fix: lay the text out **once at base (page-space) size** (base font `22 × fontScale`, base
  width/height, base `lineHeight`) and apply the page zoom as a **`graphicsLayer` scale about the
  top-left** — the same mechanism (and the same layer) as the lasso corner-drag `liveTransform`
  preview, now `scaleX/scaleY = scale × liveTransform.scaleX/Y`. The layout (wrapping, line count,
  line spacing) is frozen and the whole layer scales 1:1 with the grid across the full 0.5×–4× range.
  `onMeasured` now reports the raw measured size directly (already page-space at base size) instead of
  dividing out the zoom; the `reportAiNoteMeasuredSize` page-space contract is unchanged (no VM
  change). The text rasterises at base size then scales (an accepted, device-confirmed-crisp tradeoff,
  same as the corner-drag preview); ink strokes still re-rasterise crisply via the mesh renderer.
  (Reinforces the FA-10 rule: scale via a layer modifier, never recompute inside the layout/draw.)
- **Finger select/deselect of AI boxes + 800ms hold** (`ui/InkCanvas.kt`). A finger could move/scale a
  *selected* box (via the Compose selection chrome) but could never **select** one — in scroll mode
  (palm rejection on) a finger DOWN was swallowed straight into the scroll path. Now selection is
  gated on the input mode, not the tool: **scroll mode** = a clean finger **tap on a box selects**, a
  **tap on empty page deselects** (a new `scrollDownNoteId` hit-test on DOWN, resolved on UP only when
  no scroll axis locked — a drag past the slop still scrolls and leaves the selection alone);
  **finger-draw mode** = unchanged press-and-hold to select, tap-off deselects on the outside DOWN;
  **stylus** always holds. The hold duration is cut **1500ms → 800ms** (`AI_NOTE_HOLD_MS`, shared by
  the stylus + finger-draw hold). The **eraser stays purely destructive** (no hold-to-select — it
  erases ink under the box). Raw-`MotionEvent` touch-listener change, so device/manual-verified like
  the other ink flows (no JVM test path).

## FA-22 — storage format finish + progressive page load + AI semantic-layer design (2026-07-01)

Architecture-optimisation pass on branch **FA-22**, scoped against `canvas-rendering-architecture.md`.
A code audit first confirmed that doc's Fix 1 (RenderNode incremental bake) and Fix 2 (append-only
autosave, 1.5s debounce) were **already delivered** by the merged `perf-stroke-fill-degradation` PR —
so FA-22 targets what remained. **DB is now v16** (`MIGRATION_15_16`). **381 app + 24 aibackend
JVM/Robolectric tests pass** (0 failures; was 378+24); `:app:assembleDebug` +
`:app:assembleDebugAndroidTest` build on the WSL Linux SDK. **Device-confirmed on a Galaxy Tab S
(SM-X510, 2026-07-02):** the v15→v16 migration ran clean on real data, and reopening a
**1,003-stroke page** logged `query=113ms firstChunk=86ms reconstruct=219ms` — vs the pre-FA-22
baseline of ~0.4–0.6s query + 1.5–2.9s *sequential* reconstruct on a 793-stroke page, i.e. **first
ink in ~0.2s instead of ~2–3.5s**. All FA-22 instrumented tests pass on-device (incl. the parallel
reconstruction test). The same session measured the **flatten cost curve** (`ElrondPerf`): ~10ms @
100 strokes, ~25ms @ 300, ~50ms @ 1,000 — linear, one flatten per destructive/selection mutation on
the UI thread. Normal writing is unaffected (appends fold at O(tail)), but a dense-page eraser drag
repeats that cost per clipped stroke. **Erase was device-tested on the 1,003-stroke page and feels
fine** (2026-07-02), so the segmented/chunked bake is **parked** — revisit if pages grow well past
~1,000 strokes or when FA-20 multi-page needs the same structure (per-page nodes + viewport culling).

- **Stroke storage finished: `strokes.inputsJson TEXT` → `strokes.inputs BLOB` (v16).** The compact
  binary points now store raw (25 bytes/point, no Base64 text layer — ~25% smaller rows, no
  encode/decode per save/load). `MIGRATION_15_16` rebuilds the table and converts **every existing
  row** — Base64-compact rows decode straight to bytes; pre-compact legacy-JSON rows parse + pack —
  via `StrokeSerialization.storedTextToBlob` (frozen formats, migration-only), row-by-row with a
  compiled statement so a large DB migrates without holding all strokes in memory. Because the
  migration converts everything, the legacy-JSON decode branch, `NoteRepository.recompactStrokes`
  (the on-open migration), and its three `StrokeDao` helpers were **deleted** — less code on the
  hot path. `StrokeEntity` overrides equals/hashCode (`ByteArray` needs content equality).
- **`ElrondPerf` instrumentation gated to debug builds** (`NoteRepository.perfLog` lazy-message
  helper + a `BuildConfig.DEBUG` guard on the `InkCanvas` flatten log) — release builds skip the
  logging and the string formatting.
- **Progressive + parallel page load** (the audit's biggest remaining user-facing cost: ~0.5s query
  + 1.5–2.9s sequential mesh rebuild on a dense page before any ink appeared).
  `NoteRepository.loadStrokesProgressive(pageId, spacing, chunkSize=64)` emits reconstructed
  strokes as ordered chunks; chunks are **built in parallel** across `Dispatchers.Default` workers
  and **emitted in stroke order**. `loadStrokes` is reimplemented on top (ExtractionWorker
  unchanged). `CanvasViewModel` collects chunks and **inserts them at the loaded prefix** (never
  assigns) — which also fixes a pre-existing bug where a stroke drawn during the load window was
  silently clobbered by the atomic `_finishedStrokes.value = loaded`. Safety around partial loads:
  `lastPersisted` accumulates per chunk (so a mid-load exit flush can never re-insert
  already-persisted rows) and the undo/redo stacks are cleared while chunks land (an undo to a
  partially-loaded page would delete the not-yet-loaded ink from the DB). The layer-rebuild
  collector now starts **before** the load so each chunk renders as it arrives (the dry layer's
  incremental fold absorbs chunk-sized appends).
- **New instrumented test** `parallelChunkedReconstruction_isLosslessAndOrdered`
  (StrokeSerializationInstrumentedTest) — 200 strokes rebuilt in parallel 16-stroke chunks, asserts
  order + zero point loss; mirrors the repository's exact fan-out (concurrent ink-native mesh
  construction is the one thing JVM tests can't see). Run via `connectedDebugAndroidTest`.
- **AI semantic layer — designed, not yet implemented: see `ai-semantic-layer-design.md`.** Audit
  finding: no recognition result is ever reused — `ExtractionWorker` re-recognizes the whole page
  on every autosave and every `/Q` re-recognizes all context lines pre-network. The doc proposes a
  `recognized_lines` cache (DB v17, keyed by ordered stroke-id sets so invalidation is automatic),
  cache-fed extraction + `/Q` context (full page context retained), and an extraction skip-gate
  that avoids redundant Anthropic calls. Implementation is a follow-up FA batch.
- New/updated tests: `ElrondMigrationTest` (chain → **v16** + a both-formats data-conversion test),
  `SerializedStrokeInputTest` (ByteArray round-trip, `storedTextToBlob`, empty payloads),
  `NoteRepositoryTest` (+progressive chunk order/flatten, empty page),
  `CanvasViewModelPersistenceTest` (+mid-load stroke preserved, history dropped, first autosave
  appends only the user stroke); the `loadStrokes` stubs across 4 VM test files became
  `loadStrokesProgressive` flow stubs.

`canvas-rendering-architecture.md` is retained as the investigation record; its three fixes are all
now implemented (1–2 pre-FA-22, 3 completed here).

## Calendar architecture (Phase 5 — data/provider layer; view UI added in Phase 6)

Swappable calendar integration behind `CalendarProvider` (`app/.../data/`):

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
**Pre-release step (required — do this last, before release):** the FA-11 code is complete and shipped,
but Outlook is **non-functional until an Azure app is registered** and its client id / signature hash
are wired in. Until then Outlook stays NotConfigured (the Events tab shows a sign-in prompt that does
nothing and the calendar falls back to the device provider) — that's expected, not a bug. Registering
the Azure app is deliberately deferred to a final step before release. To enable it:
1. Azure Portal → **App registrations** → New registration. Under **Authentication → Add platform →
   Android**, enter package `ai.elrond` and your **signature hash** (base64 SHA-1 of the signing cert)
   — the portal then shows the exact redirect URI `msauth://ai.elrond/<URL-encoded signature hash>` and
   a manifest snippet. Get the hash for the **debug** keystore (use the release keystore for release; you
   can register both redirect URIs on one app):
   ```bash
   # macOS/Linux/WSL  (Windows debug keystore: %USERPROFILE%\.android\debug.keystore)
   keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore \
     -storepass android -keypass android | openssl sha1 -binary | openssl base64
   ```
   The base64 output is `outlook.signatureHash` below. NOTE: the build URL-encodes it for the MSAL
   redirect_uri while the manifest uses it raw — both are derived from this one value, so they always
   match as long as the value you register in Azure equals `outlook.signatureHash`.
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
- AI notes (`AiInkNote`) persist in the `ai_notes` table; the schema is now at **v16** — most recently `strokes.inputs` became a raw binary BLOB in `MIGRATION_15_16` (FA-22 storage-format finish); `ai_notes.fontScale` was added in `MIGRATION_14_15` for the FA-21 AI-box ratio-locked resize (FA-20 added the notebook/page-layer columns through `MIGRATION_11_12`…`13_14`; `subjects` + `note_subjects` in `MIGRATION_9_10` for FA-16; `note_pages.lastOpenedAt` in `MIGRATION_8_9` for FA-15; `todo_items.status` in `MIGRATION_7_8` for FA-14; `strokes.groupId` in `MIGRATION_6_7` for FA-9).
- READ/WRITE_CALENDAR were re-added to the manifest in Phase 5 (the change that ships calendar) and are requested at runtime; `DeviceCalendarProvider` only acts on explicit user action.
- OAuth: the Outlook client id is sourced from `local.properties` → `BuildConfig` (FA-11, not committed); Google's is still a placeholder. For production, don't embed client ids — use a server-side token exchange (same posture as the Anthropic key). MSAL scopes are read-only-ish `Calendars.ReadWrite` (delegated); calendar writes still require explicit user confirmation (CalendarViewModel).
- **Outlook Azure app registration — required final step before release.** FA-11's Outlook integration is fully coded but ships **inert** until an Azure app is registered and `outlook.clientId` / `outlook.tenantId` / `outlook.signatureHash` are set (see *Outlook / Microsoft Graph OAuth setup*). This is intentionally deferred to a final pre-release task; until done, Outlook stays NotConfigured and the calendar falls back to the device provider (not a bug). Pairs with the Anthropic-key release blocker above.
- **16 KB page size — resolved (FA-7).** All native libs, incl. ML Kit's `libdigitalink.so`, are 16 KB (`0x4000`) LOAD-segment aligned (verified in the built APK), via `digital-ink-recognition` 19.0.0 + `useLegacyPackaging = false`. No longer a release blocker. (The earlier FA-6 "accepted risk" framing was based on stale data — an aligned ML Kit build shipped Aug 2025.)

## Environment Notes (build)

- Two SDKs are in play: Android Studio (Windows) uses `sdk.dir=C:\...\Android\Sdk` in `local.properties`; WSL builds use the Linux SDK at `~/android-sdk` (swap `sdk.dir` temporarily during WSL builds, then restore — never leave the WSL path in the file or Studio breaks).
- The Anthropic API key is read from `anthropic.apiKey` in `local.properties` into `BuildConfig.ANTHROPIC_API_KEY`. Without it the app runs with the AI disabled (a config-error card appears on `/Q`).
- Outlook (FA-11): `outlook.clientId` / `outlook.tenantId` / `outlook.signatureHash` in `local.properties` → `BuildConfig.OUTLOOK_*` + the `msalRedirectPath` manifest placeholder. Without `outlook.clientId` the app runs with Outlook disabled (the Events tab shows a "not configured" sign-in prompt; the calendar falls back to the device provider). MSAL's `com.microsoft.device.display:display-mask` resolves only from Microsoft's **Duo SDK Maven feed** (added to `settings.gradle.kts`) — a clean Gradle cache needs network to fetch it.
- ML Kit downloads the en-US handwriting model on first `/Q` use — first trigger needs network.
