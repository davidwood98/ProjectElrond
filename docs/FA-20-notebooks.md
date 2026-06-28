# FA-20 — Notebooks (multi-page) — Implementation Plan

Status: **approved, not yet implemented**. Branch: `fa-20-notebooks`.

A note becomes a **Notebook** (a document) containing ordered **Pages** (sheets). Every
existing note would migrate to a notebook with one page — but in development we may **wipe
existing notes** (decision below), so no data-preservation work is required. The heavy data
(strokes, AI notes, todos, edit-events) already hangs off `pageId` and **does not move**; we
add a notebook layer above and change the filing + coordinate semantics.

## Locked decisions

| Area | Decision |
|---|---|
| **Model** | Each note → its own single-page notebook. Reuse `notebooks`/`note_pages` (the 1:many shape already exists). Strokes/ai_notes/todos/edit-events stay keyed on `pageId`. |
| **Existing data** | **Wipe-OK (dev).** No coordinate rescaling / no reference-width inference. Start fresh in logical page-space. |
| **Coordinates** | Device-independent **logical page space**, portrait A-ratio (`LOGICAL_W × LOGICAL_W·√2`). Screen/scroll/zoom/rotation = a `PageTransform` only; stored coords never change. Default render fits page width to screen width. |
| **Zoom** | Build interactions **transform-aware (zoom-ready)**; ship pinch **disabled**. Enable in Phase 5. |
| **Page flow** | Global default (DataStore) + **per-notebook override** (`notebooks.pageNavigationMode`): vertical-continuous ⇄ horizontal-turn. |
| **Paper style** | Global default + **per-notebook override** (`notebooks.paperStyle`): lines / dots / blank. Edited from Settings (global) and ⋮ More (this notebook). |
| **Orientation** | Page space fixed portrait; strokes **never reflow**. **Physical device rotation is the primary landscape-writing path.** A **per-notebook** view-orientation preference (`notebooks.viewOrientation`) also exists; "landscape" rigidly rotates the page 90° + fits. Both only change the `PageTransform`. |
| **New page** | Auto-create when scrolling/swiping past the last page; **prune trailing empty pages on close** (index/badge never fill with blanks). Plus a manual **+** in the page index. |
| **Finger nav** | Stylus draws/lassos; **finger swipe navigates only when finger-draw is OFF**. In finger-draw mode, swipe-nav is disabled (navigate via index / arrows). |
| **Open behaviour** | Open to last-viewed page = max `lastOpenedAt` within the notebook (no new column). |
| **Page limit** | **No hard cap, no soft warning** (windowed rendering makes large notebooks fine). |
| **Last-page delete** | **Clear-not-delete** — deleting a notebook's only page clears it, keeps the (empty) notebook. |
| **Move pages between notebooks** | **Deferred**, but implemented modularly (a clean reparent seam) so it drops in later. |
| **Title** | Per-notebook, in scroll content above page 1, scrolls away. Page 1 docks under chrome; slides behind on scroll. |
| **Page index** | Full-screen lightbox thumbnail grid. Tap = navigate; toolbar **Select** = multi-select (delete + bookmark); **press-hold-drag = reorder**; manual **+** to add. |
| **Browser** | Same card + **page-count badge**; thumbnail = page 1. |
| **Todo links** | Keep `sourcePageId`; **resolve the page number live** at display ("Notebook → Page N"); notebook-title snapshot as deleted-source fallback. |
| **Calendar** | `page_edit_events` (already per-page) → day-sheet drills Day → notebook → edited pages → opens at that page. |
| **Editor tabs / Recents** | Become **notebook-scoped** (currently per-page). |

## Schema (v10 → v11, `MIGRATION_10_11`)

- **`notebooks`** (reuse; `name` = title): add `pageNavigationMode TEXT`, `paperStyle TEXT`,
  `viewOrientation TEXT`, `templateId TEXT` (nullable placeholder), `modifiedAt INTEGER`.
- **`note_pages`**: add `pageNumber INTEGER` (mutable sort order — **not** identity) +
  `isBookmarked INTEGER`. (`lastOpenedAt` already present.)
- **`note_subjects`**: **re-key `pageId → notebookId`** (filing is per-notebook). Recreate the
  table keyed by `notebookId`, mapping each old `(pageId, subjectId)` → `(owningNotebookId,
  subjectId)`, drop the old.
- **Migration**: create a `notebooks` row per existing `note_page` (inherit global defaults),
  repoint `notebookId`, set `pageNumber = 1`. No coordinate work (wipe-OK). The migration must
  still be valid for any rows present so `ElrondMigrationTest` (v10→v11) passes.

## Build phases

**Phase 0 — Data + coordinate foundation (DONE).** v11 *additive* migration (`MIGRATION_10_11`:
per-notebook columns `pageNavigationMode`/`paperStyle`/`viewOrientation`/`templateId`/`modifiedAt`,
page columns `pageNumber`/`isBookmarked`); pure JVM-testable `PageTransform` (`pageSpace ⇄ screen`,
fit-to-width, A-ratio dims) + `PageNavigationMode`/`PageViewOrientation` enums; threaded through
domain models + mappers. **Behaviour-preserving — no rendering or navigation change yet.** Verified
on the WSL SDK: `compileDebugKotlin` + migration-v11 + `PageTransform` tests green; `11.json` exported.

> **Boundary refinement (vs. the original table):** the `note_subjects` re-key, the "explode each
> note into its own notebook", and notebook-scoped tabs were **moved out of Phase 0 into Phase 1**.
> They are coupled to the navigation flip (Library-shows-notebooks / open-notebook); doing them while
> the UI is still page-centric would create an inconsistent half-state with no user benefit. Same end
> state, cleaner commits, app runnable at every step.

**Phase 1a — Notebook-per-note foundation (DONE).** `MIGRATION_11_12` (data-only) explodes each
existing note into its own notebook (`nb-<pageId>`) and drops the empty default; `NoteRepository.createNote()`
creates a notebook + first page, and both create-note call sites (NoteListViewModel, CalendarViewModel) use
it. **Behaviour-preserving** — the library still lists pages, the route stays `note/{pageId}`, the editor is
unchanged. Establishes the invariant "every note is its own notebook" that the multi-page editor needs.
Verified on the WSL SDK: compile + the migration-v12 explode test + create-note tests green; `12.json` exported.

**Phase 1b — Horizontal-swipe paged canvas.** The editor opens the (cover) page's notebook and renders its
pages as a pager; stylus draws/lassos, finger swipe navigates (disabled in finger-draw mode); auto-create a
page past the last, prune trailing empties on close; open to last-viewed page; **all interactions wired
through `PageTransform`'s inverse** (palm rejection, lasso, `/Q`, AI-note placement, popup clamping; wet ink
via `InProgressStrokesView.motionEventToWorldTransform`); notebook-scoped tabs. The notebook's title = its
cover page's title (becomes "the title above page 1"). Zoom scale present, pinch gated off. Device-verified.

> **Decomposition note (vs. the original table):** the `note_subjects` re-key and "browser shows notebooks
> with a page-count badge" moved from Phase 1 into **Phase 2**, matching the original plan's "browser badge +
> link resolution" grouping — they are not prerequisites for the paged canvas and would be invisible churn
> while every notebook holds exactly one page. The route deliberately stays `note/{pageId}` (the page to open;
> the editor loads *that page's notebook*), so deep links and "open notebook scrolled to page X" work for free.

**Phase 2 — Browser-as-notebooks + page index + link resolution.** Re-key `note_subjects`
pageId → notebookId (its own migration) and switch the Library grid/tabs/filter to notebooks with a
**page-count badge** + page-1 thumbnail (filing becomes per-notebook). Page-index lightbox grid (per-page
WebP via extended `ThumbnailCache`): tap = nav; toolbar Select = multi-select (delete + bookmark);
press-hold-drag = reorder (Select is a separate mode, so long-press isn't overloaded); manual +. Todo links
resolve the live page number; calendar day-sheet drills Day → notebook → edited pages.

**Phase 3 — Vertical scroll (hard part).** Windowed rendering (only on-screen ±1 pages get a live
ink layer); wet-ink front-buffer translated by scroll offset. Thin rule line between pages, paper
resets per page, title above page 1 scrolls away, page 1 docks under chrome and slides behind on
scroll. Highest-risk phase.

**Phase 4 — Per-notebook settings.** Global defaults (DataStore) for nav mode + paper;
per-notebook overrides via ⋮ More and Settings → Page style (nav mode, background, templates
placeholder). Per-notebook view-orientation toggle.

**Phase 5 — Zoom follow-up.** Enable pinch; harden the already-transform-aware interactions.

## Risks
1. **Vertical wet-ink scroll translation** (Phase 3) — biggest unknown; horizontal-first de-risks.
2. **Interaction transform-awareness** — broad surface; mitigated by routing everything through
   `PageTransform` + per-interaction tests.
3. **Reorder vs multi-select gesture collision** — resolved by an explicit Select mode.

## Deferred / out of scope
Pinch zoom (Phase 5), page templates (placeholder only), move-pages-between-notebooks (modular
seam now, UI later), hard page limits.

## Testing (per repo conventions)
- `PageTransform` + any pure geometry → JVM unit tests.
- `MIGRATION_10_11` → `ElrondMigrationTest` chain extended to v11.
- Repositories (notebook CRUD, page reorder, reparent seam, subjects re-key) → Robolectric.
- ViewModel page lifecycle → JVM.
- Rendering / scroll / swipe / reorder gestures → instrumented + device (Galaxy Tab S).

## Device-feedback batch 2 (2026-06-28) — 8 items, DONE

A second device-test round on the notebooks editor. **DB is now v14**
(`MIGRATION_13_14`). App + aibackend unit suites green; main + androidTest compile on the WSL
Linux SDK. Compose surfaces are device-verify-only. Committed in coherent batches (`ab5019b`,
`87626b0`, `50456ae`, `ad2429c`, `f1a8ed1`).

- **Todo/calendar source links → notebook title only** (no "→ Page N"); the link still opens the
  exact source page. `SourceNoteLabel` simplified.
- **Pages > 1 carry no title of their own.** The notebook's title is its cover (page 1); the editor
  header/date derive from the cover via the ordered-pages flow and rename targets the cover, so
  swapping pages never changes the header or tab name.
- **Lasso respects finger-draw.** With stylus-only on, a finger no longer starts a selection/paste —
  the lasso follows the same palm-rejection rule as the pen.
- **Page starts below the title band; the title scrolls away.** `CanvasViewModel.setPageTopInset`
  reports the band bottom; the page transform docks the page below it (`offsetY = pageTopInset −
  scroll`) and the band (composed before the toolbar so the toolbar stays on top) slides up with the
  page top.
- **Calendar day-sheet groups by notebook.** One tile per notebook: a *created* notebook shows
  "Created" and opens on tap; an *edited* notebook shows "Edited" + an "N pages" pill and taps to a
  per-page menu (`CalendarViewModel.notebooksForDay` / `DayNotebook`).
- **Pages index: drag-reorder + multi-select.** Press-hold-drag a page (it lifts, the page under it
  shows an accent drop indicator, release commits via `reorderPages`); a "Select" tick-box mode adds
  multi-delete (`deletePagesFromNotebook`, always keeps ≥1).
- **Schema v14 + per-notebook page style.** `MIGRATION_13_14` adds `notebooks.gridSpacing` +
  `paperColor`; new `PaperStyle.GRID` + `PaperColor` enum. The editor ⋮ → **Page style** dialog
  (was a disabled placeholder) sets, **per notebook**: paper style (Lines/Dots/Grid/Blank), a
  spacing-density slider (1–10, 5 = the old default), an orientation dropdown, and paper-colour dots.
  The VM resolves the effective style (per-notebook override else the global Settings default).
- **Per-notebook orientation + rotation prompt.** `viewOrientation` swaps the page *sheet's* aspect
  (LANDSCAPE = a wide A-ratio sheet) via `recomputePageSize`; strokes/toolbar stay upright, nothing
  reflows. On device rotation an unobtrusive corner button offers to switch the whole notebook to
  match the device. `PaperBackground` renders Grid + density + colour, sized for the orientation.

Tests: `SourceNoteLabelTest` updated; `ElrondMigrationTest` → v14 + a v13→v14 column test;
`CanvasViewModelPageTransformTest` unchanged-green. New page-style/orientation/reorder/timeline UI
is device-verify-only.

---

## Build status (commits on `fa-20-notebooks`)

Phase 0 (`9c7cb23`), 1a (`e6c6723`), 1b-i/ii/iii (`48e1aee`/`62aa52e`/`d43fda9`), page index
(`2a21d80`), notebook tabs + last-viewed (`77bc821`), browser-as-notebooks + `note_subjects`
re-key v13 (`2e0a5c8`). All compile; full app unit suite green; schemas v11/v12/v13 exported.

**Phase 2 substantive work done & committed:** page index, notebook tabs, open-to-last-viewed,
browser-as-notebooks, subjects re-key. **Phase 2 polish — DONE (this session):** the "Notebook → Page N"
label on todo source links (pure `domain/SourceNoteLabel` — cover-page title + live page number, shown
only for multi-page notebooks; `TodoViewModel.sourceLabels`, falls back to the stored snapshot for a
deleted source), and the calendar day-sheet `Day → notebook → page` label (`CalendarViewModel.notebookPageLabel`
subtitles each multi-page notebook's edited page on the day thumbnail; tapping already opens that page).

## Scroll + landscape rework (1b-i device feedback) — DONE (this session)

Fixed the three device-reported 1b-i issues + the `/simplify` altitude note. App unit suite green
(`:app:testDebugUnitTest` + `:aibackend:test`); main + androidTest compile on the WSL Linux SDK. **No
schema change.** Device-verify on a Galaxy Tab S (the render mechanism is device-only): dry ink scrolls
live with the paper, the eraser lands on the ink you see, and rotating to landscape centres a fixed
portrait sheet (margins both sides) without reflowing strokes.

- **Single `PageTransform` source of truth (the altitude note).** `CanvasViewModel` now exposes
  `pageTransform: StateFlow<PageTransform>` (scale=1, `offsetX` = horizontal centring, `offsetY` =
  −scroll). Every render/capture/hit-test site routes page↔screen through it (`pageToScreenX/Y`,
  `screenToPageX/Y`) instead of hand-threading `± scrollPx`: ink capture (`startStroke` world transform),
  the dry layer, the eraser, `SelectionLayer` (lasso capture, box/handles/toolbar/ghost/strokes),
  `AiInkNoteView`, `PaperBackground`, and the AI-state overlays. Pinch-zoom (Phase 5) is now a one-line
  `scale` change. `pageScrollPx` was removed; `scrollBy` updates the transform.
- **B2/B3 — dry ink scrolls live (per-frame GPU transform, the FA-10 lesson).** `DryStrokesView` no
  longer does `Matrix.setTranslate` + `invalidate()` in `onDraw` (which didn't repaint per-frame during
  the drag — ink stayed frozen and snapped on release). It is now laid out at the **full page size**
  (page-space units) and applies the page→screen transform as **GPU view properties** (`translationX/Y`,
  `scaleX/Y`, top-left pivot) — a cheap composite the framework re-applies every frame; the strokes
  rasterise once. Stroke-list changes still go through `setStrokes()` + `invalidate()` (off Compose, so
  the FA-6 first-stroke fix holds). Because the ink is now drawn where it's seen, the eraser (which
  hit-tests through the same transform) lands correctly — B3 resolved by B2.
- **B1 — fixed portrait sheet, centred (per the user's clarified model).** The page is a fixed portrait
  A-ratio sheet whose width = the device's shorter screen edge, so it never reflows on rotation; in
  landscape it stays that width and is **centred** with equal `margin–page–margin` (the margin is the
  transform's `offsetX`). **Scale stays 1** (strokes keep their size until pinch-zoom, Phase 5). Strokes
  are stored in **page space** (not screen px), so centring/scroll move the ink *with* the page. The
  90° view-orientation toggle stays deferred (Phase 4); device rotation just recomputes the transform.
  `PaperBackground` now renders the sheet as white-on-neutral-desk with a hairline border so the page
  edge reads (a bottom desk margin also appears in portrait when the A-ratio page is shorter than the
  screen).
- Tests: `SourceNoteLabelTest` (pure label resolution), `CanvasViewModelPageTransformTest` (portrait
  no-offset / landscape centring / scroll clamping). Render mechanism + on-device centring are
  device-verified (the mesh renderer is hardware-only).

**Device-feedback follow-up (same session):** B/C/D all pass; one landscape selection bug fixed.
`SelectionStrokes` baked the page offset into the **draw matrix**, which mis-placed the live selected
ink (it jumped left toward screen-centre while the box stayed put) and hid the ghost in landscape —
even though the box, capture, and dry layer were all correct. Fix: draw the selected ink + ghost at
**identity** and apply the page→screen mapping via **`graphicsLayer`** (the SAME GPU mechanism the dry
layer uses), with the live move/scale as a second inner layer composed as `page(live(stroke))`. So the
selection now lands exactly where the dry ink does in every orientation.

## ⚠️ Device-test feedback (1b-i scroll) — DIAGNOSIS (RESOLVED — see "Scroll + landscape rework" above)

> All three issues below were fixed in the "Scroll + landscape rework" section above. Kept as the
> diagnosis record. Note B1's resolution: the user clarified the model as a **centred fixed portrait
> sheet** (margin–page–margin, scale unchanged until zoom), not the originally-deferred fixed sheet —
> see that section.

On a Galaxy Tab S, section-A tests passed; **section B (single-page scroll) is broken**:

- **B2 (root cause): dry ink doesn't scroll live.** The Compose `PaperBackground` scrolls per-frame
  (it recomposes on `pageScrollPx`), but the dry-ink `DryStrokesView` (a plain `View` using
  `setScroll` → `Matrix.setTranslate` + `invalidate()`) stays **static during the drag and snaps to the
  scrolled position on release**. So `invalidate()` is not producing per-frame redraws during the
  gesture. **Fix direction (the FA-10 lesson):** drive the dry-layer scroll with a **per-frame GPU
  transform**, not redraw-on-invalidate — e.g. `dryStrokesView.translationY = -scroll` on a View laid
  out at the **full page height** (so translating reveals off-screen ink), or render the dry strokes in
  a Compose layer (`drawIntoCanvas` + `graphicsLayer { translationY = -scroll }`, sized to the page) as
  `SelectionStrokes` already does. The current screen-height View + `invalidate` is the wrong mechanism.
- **B3 (eraser hits the wrong place when scrolled): a consequence of B2.** The eraser hit-tests at
  `eventY + scroll` (the logical world position) while the ink is *drawn* at the static (unscrolled)
  position — so you erase where the ink *would* be, not where you see it. Fixing B2 (visual == logical)
  should resolve it; re-verify after.
- **B1 (landscape rotation rotates the page with the toolbar):** the agreed model — page stays a fixed
  portrait A-ratio sheet, **independent of device orientation**, with a separate per-notebook
  view-orientation toggle — was **not implemented** (deferred). The canvas is currently full-bleed and
  follows the device. Needs: lock the page to portrait logical space and only change the `PageTransform`
  on rotation / the view-toggle (don't reflow). See the FA-20 orientation decision.

**Recommendation:** fix B in a **fresh session** (this one is very deep). Start from this section + the
committed code; the scroll plumbing (`CanvasViewModel.pageScrollPx`/`scrollBy`, `InkCanvas`
`DryStrokesView`/touch listener, `PaperBackground`) is all in place — the fix is the dry-layer render
mechanism, not the data flow.

**Also (from the `/simplify` altitude review) — do this as part of the B rework:** the scroll is
currently hand-threaded as `± scrollPx` across ~10 sites (InkCanvas dry/wet, eraser, `SelectionLayer`
box/handles/ghost, `AiInkNoteView`, `PaperBackground`, the AI-state overlays) while `PageTransform`
(the type built to centralise exactly this) is used only for `ASPECT_RATIO`. While reworking the
render mechanism, route those conversions through one `PageTransform` (give it a `toMatrix()` and use
`pageToScreenY`/`screenToPageY`/`pageToScreenLength`) so the eventual pinch-zoom (Phase 5) is a one-line
`scale` change rather than a hunt across files.
