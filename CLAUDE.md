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
 Provider (REAL)  Provider (stub)      Provider (stub)
 CalendarContract  Google Cal API v3    MS Graph API
                   + Google Sign-In     + MSAL
        ▲
 CalendarProviderFactory.create(type, context)  ← type from SettingsRepository.calendarProvider (DataStore)
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

### Outlook / Microsoft Graph OAuth setup (for when OutlookCalendarProvider is wired)
1. Azure Portal → **App registrations** → New registration (single or multi-tenant).
2. Add a **Mobile/desktop** redirect URI `msauth://ai.elrond/<base64 signature hash>`.
3. API permissions → Microsoft Graph → delegated **Calendars.ReadWrite**.
4. Put the application (client) id + tenant into `CalendarProviderFactory.outlookConfig`. Add the **MSAL** (`com.microsoft.identity.client`) + Microsoft Graph SDK dependencies.
5. Auth flow: MSAL `acquireToken` (scope `Calendars.ReadWrite`) → `GraphServiceClient`. Mapping documented in `OutlookCalendarProvider`.

### iOS port reuse
`CalendarProvider`, `CalendarEvent`, `DateRange`, and the AI extractor are pure/portable. An iOS port implements the same interface with EventKit (device) and the same Google/Graph REST APIs — the factory + DataStore preference pattern carries over unchanged.

### Known accepted risk (OAuth credentials)
OAuth client ids in `CalendarProviderFactory` are placeholders. For production, client ids/secrets must not ship in the APK — use a server-side token exchange or platform secure storage, mirroring the Anthropic-key risk below.

## Security posture (audited 2026-06-04)

- Audit found: clean git history, TLS-only (https enforced via `AnthropicConfig` require + `usesCleartextTraffic=false`), no logging of note content, Room DB sandbox-only, `allowBackup=false`, only MainActivity exported.
- **Known accepted risk (development only — a hard blocker for release/completion):** the Anthropic API key is embedded via BuildConfig — extractable from any distributed APK. This is accepted *only* during active development; it is **not** acceptable for completion/release. Before any release: move to a server-side proxy holding the key, or per-user runtime keys in Android Keystore/EncryptedSharedPreferences.
- AI notes (`AiInkNote`) persist in the `ai_notes` table; the schema is now at **v5** — `page_edit_events` was added in `MIGRATION_4_5` for per-day calendar created-vs-edited tracking.
- READ/WRITE_CALENDAR were re-added to the manifest in Phase 5 (the change that ships calendar) and are requested at runtime; `DeviceCalendarProvider` only acts on explicit user action.
- OAuth client ids in `CalendarProviderFactory` are placeholders — for production, do not embed them; use a server-side token exchange (same posture as the Anthropic key).

## Environment Notes (build)

- Two SDKs are in play: Android Studio (Windows) uses `sdk.dir=C:\...\Android\Sdk` in `local.properties`; WSL builds use the Linux SDK at `~/android-sdk` (swap `sdk.dir` temporarily during WSL builds, then restore — never leave the WSL path in the file or Studio breaks).
- The Anthropic API key is read from `anthropic.apiKey` in `local.properties` into `BuildConfig.ANTHROPIC_API_KEY`. Without it the app runs with the AI disabled (a config-error card appears on `/Q`).
- ML Kit downloads the en-US handwriting model on first `/Q` use — first trigger needs network.
