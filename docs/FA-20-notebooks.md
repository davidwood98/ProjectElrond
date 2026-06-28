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

**Phase 1 — Notebook-centric navigation + horizontal swipe.** The behaviour flip: open a notebook
(route `notebook/{notebookId}`), Library shows notebooks, **explode each note into its own notebook +
re-key `note_subjects` → notebookId** (its own migration), notebook-scoped editor tabs/Recents, and a
pager of per-page canvases wiring all interactions through `PageTransform`'s inverse (palm rejection,
lasso, `/Q`, AI-note placement, popup clamping; wet ink via
`InProgressStrokesView.motionEventToWorldTransform`). Stylus draws/lassos; finger swipe navigates only
when finger-draw is OFF. Auto-create a page past the last; prune trailing empties on close. Open to
last-viewed page. Zoom scale present but pinch gated off.

**Phase 2 — Page index + browser + link resolution.** Lightbox thumbnail grid (per-page WebP via
extended `ThumbnailCache`): tap = nav; toolbar Select = multi-select (delete + bookmark);
press-hold-drag = reorder (Select is a separate mode, so long-press isn't overloaded); manual +.
Browser page-count badge; page-1 thumbnail. Todo links resolve live page number; calendar
day-sheet drills to page.

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
