# Claude Code Prompt: Notebook Cross-Linking + Tagging Feature

## What to Build

Two new organizational primitives on top of the existing Subjects hierarchy (FA-16), plus an AI
suggestion layer that feeds both:

1. **Notebook cross-linking** — an on-canvas "link" object that references another notebook as a
   whole (not a specific page), placeable/movable like an AI answer box.
2. **Notebook tagging** — flat, many-to-many labels on notebooks, visually distinct from and
   independent of Subjects (folders).
3. **AI-driven suggestions** for both, built on top of the (separately scoped)
   `recognized_lines` cache from `ai-semantic-layer-design.md`.

Resulting data shape: `Subject (hierarchy) > Notebook -- Tags (flat, many-to-many) -- Links (many-to-many, notebook-to-notebook) > Pages`.

Build in the three phases below, in order — each is independently shippable and phase 3 depends
on phase 1 and 2's tables existing.

---

## Phase 1 — Notebook cross-linking

### Data model

New table `notebook_links` (own migration, do not fold into `note_subjects` — that table's
`pageId` primary key deliberately enforces single-parent-subject and is the wrong shape here):

```
notebook_links
  id              TEXT PK
  sourcePageId    TEXT FK -> note_pages ON DELETE CASCADE   -- which page's canvas holds the link box
  targetNotebookId TEXT FK -> note_pages ON DELETE SET NULL, nullable  -- null = broken link
  targetPageId    TEXT, nullable                             -- reserved, unused for now (see below)
  x, y, widthPx, heightPx  REAL                               -- canvas placement, same shape as ai_notes
  linkText        TEXT                                        -- cached target title, shown even if target is later renamed
  createdAt       INTEGER
```

**Add `targetPageId` now even though it's unused.** The feature is scoped to notebook-level links
only (confirmed), but adding the nullable column today means "link to a specific page" is a future
read-path change, not a schema migration touching live link rows. Do not skip this.

**Migration numbering:** DB is currently v16. `ai-semantic-layer-design.md` has already reserved
v17 for `recognized_lines`. If that lands first, this is `MIGRATION_17_18`; if this ships first,
renumber `recognized_lines` to follow. Check `ElrondDatabase.kt`'s `version =` and the latest
`MIGRATION_n_n+1` before writing the migration — don't hardcode a number from this doc.

### Domain / selection integration

Do not build a parallel selection mechanism. `ai.elrond.domain.StrokeSelection.kt`'s
`SelectionState` already unifies strokes (`ids`) and AI boxes (`aiNoteIds`) into one
select/move/scale/duplicate/delete pipeline (FA-21). Add a third set, e.g. `linkIds: Set<String>
= emptySet()`, following the exact pattern `aiNoteIds` used: extend `count`, add
`hasLink`/`isSingleLink` helpers, and thread it through `commitTransform`,
`SelectionDecorations`, duplicate/copy/cut/paste in `CanvasViewModel`. A link box moves/scales
like an AI box (position + size only, no font concerns).

### UI: creating a link

The toolbar's existing **Add (+)** button (`ElrondIcons.Add`, currently the "Import (coming
soon)" placeholder in `NoteCanvasScreen.kt` — the user has confirmed this button is being
repurposed as a general "add-in" menu, not import specifically) opens a dropdown with at least
two entries:

- **Link notebook** — opens the picker described below.
- **Add page** — placeholder wiring only for now (mirrors how Import/Record are currently no-ops);
  do not implement page-adding in this pass, just reserve the menu slot so Link Note isn't sharing
  a single overloaded icon.

### UI: picking a target

Reuse Quick Nav (`EditorChrome.kt`'s `LibraryOverlay`/`QuickNavSubject`/`QuickNavNote`, FA-16 +
its follow-up) rather than building a second search surface. Add a search field to the top of
Quick Nav's overlay (this is a standalone improvement worth landing even before the rest of this
phase — it's a real gap today). Selecting a notebook in this search-augmented Quick Nav, while in
"link picking" mode, creates the link box at a default position/size on the current page instead
of navigating.

### UI: the link box + backlinks

- Renders as a compact "link-style" text box (title of the target notebook as the label) —
  reuse `AiInkNoteView`'s rendering approach (passive/no pointer input when deselected, so the pen
  can write over it; selection chrome drawn separately via `SelectionDecorations`) rather than
  inventing a new view type.
- **Tap** (while not in selection mode) opens the target notebook. Use the existing
  `SessionNotesTracker` tab mechanism — opening via a link is just another `recordOpened` call, no
  new "tab" concept needed.
- **Press-and-hold** enters select mode (same `AI_NOTE_HOLD_MS` = 800ms threshold already used for
  AI boxes in `InkCanvas.kt`, for interaction consistency).
- **Broken link** (target notebook deleted, `targetNotebookId` nulled by the FK): render the box
  text as `Reference not found` and make it **non-interactive on tap** (matches the existing dead
  todo-source-link fallback pattern from FA-5 — don't leave a "looks tappable, does nothing"
  state). Press-and-hold still works and opens a small menu with exactly two options:
  **Redefine** (re-run the target picker, replacing `targetNotebookId`/`linkText` in place) and
  **Delete**.
- **Backlinks.** Add a "Backlinks" entry to the notebook's existing ⋮ more-vert menu (the same
  menu that already holds Page style / Export / Favourite placeholders per FA-14) showing every
  `notebook_links` row where `targetNotebookId` = this notebook — i.e. "referenced by". Each row is
  tappable and opens the source notebook (symmetric with the on-canvas link tap).

### Tests

- Pure JVM: link-selection helpers in a `StrokeSelectionTest`-style test (mirrors the existing
  `aiNoteIds` coverage).
- Repository: `NotebookLinkRepositoryTest` (Robolectric) — create, redefine, delete, cascade
  behavior when target is deleted (assert it goes to the broken-link state, not a crash), backlink
  query.
- `CanvasViewModelSelectionTest`-style additions: link box participates in move/scale/duplicate/
  copy/cut/paste/undo alongside strokes and AI notes (this is exactly the class of bug FA-21 had to
  fix retroactively for AI boxes — don't repeat it; get link boxes into the unified
  `HistorySnapshot` undo path from the start).
- Migration test: chain extended to the new version + column assertions.

---

## Phase 2 — Notebook tagging

### Data model

New tables (own migration, sequenced after Phase 1's):

```
tags
  id       TEXT PK
  name     TEXT UNIQUE
  colorArgb INTEGER   -- see colour rule below; stored, not recomputed at read time

notebook_tags
  notebookId TEXT FK -> note_pages ON DELETE CASCADE
  tagId      TEXT FK -> tags ON DELETE CASCADE
  PRIMARY KEY (notebookId, tagId)
```

Genuinely many-to-many on both sides — do not reuse or extend `note_subjects` or `subjects`.
Subjects are singular/hierarchical; tags are flat/multi-valued. **Do not auto-create a tag when a
Subject is created.** This was explicitly considered and rejected: it creates a rename/delete sync
problem between two independently-lifecycled entities for no real benefit. If a "tag with current
subject" convenience is wanted later, it belongs in the AI-suggestion UI (Phase 3) as a one-tap
suggested chip, not as persisted duplicate identity.

**Page-level tags are out of scope for this pass but the schema must not block them.** Since
`notebook_tags` is already a plain many-to-many junction keyed by notebook id, extending to pages
later is a new `page_tags` junction table (or an added nullable `pageId` column) — not a rework of
this one. No extra scaffolding needed now beyond keeping this junction table separate from any
future page table.

### Colour rule

**Deterministic per tag name, not random per assignment.** Hash the tag's name to an index into
`ai.elrond.domain.SubjectPalette` (already a 66-colour pastel spectrum, pure/Compose-free,
`SubjectPalette.argb(colorId)`/`normalize`) at tag-creation time, store the resulting ARGB in
`tags.colorArgb`, and never recompute it. The same tag name must render the same colour everywhere
it's used — that's the entire value of colouring tags in the first place. Do not reuse `Subject`'s
`colorId`-into-palette live-lookup pattern verbatim; store the resolved colour so a future palette
change doesn't silently reflow every tag's colour.

### UI: placement

Two entry points, both calling one shared `TagRepository` method (don't let the two surfaces drift):

- **Library** — notebook cards' existing ⋮ menu (the same one that has Rename / Move to subject /
  Delete per FA-16) gets a **Tags** entry opening a tag-assignment picker.
- **Canvas** — inline in the editor header band (`EditorChrome.kt`), positioned just right of the
  created-date text. A `+` icon adds a tag (opens the same picker/creation UI as Library).

### UI: layout and interaction (this is the fiddly part — follow exactly)

- The tag row occupies a **fixed position and fixed width** in the header band. It never grows,
  shrinks, or shifts based on how many tags exist or how long the title is — this is the same
  layout-shift problem already solved for the title/tag question earlier in this project's design,
  and the same fix applies: fixed regions, internal overflow handling, never mutual encroachment.
- **Title** has its own max-width, truncating to `…` past that width.
- **Tag row** independently truncates via **horizontal scroll**, not wrapping or reflow. When the
  row's content would overflow, add a **fade-out gradient** on whichever edge has more content
  hidden (there is no other affordance telling the user there's more to scroll to — don't skip
  this, a silently-clipped scrollable row hides content).
- **Each tag pill** has its own max-width and truncates to `…` independently of the row — so one
  long tag name can't dominate or crowd out the others.
- **Tap behavior is uniform regardless of truncation state** (this was explicitly corrected during
  design — do not make the gesture's meaning depend on whether the label happens to be cut off,
  since the user can't reliably predict that from a glance):
  1. First tap on a pill → shows a preview/selected state (full untruncated name, e.g. a small
     popover or an expanded pill) — happens for *every* tag, truncated or not.
  2. Second tap within a short window (reuse the app's existing ~300ms double-tap-style window
     constant if one exists, else define one alongside it) on the same pill → untags it.
  3. On untag: the pill **greys out and stays in its position** for **2 seconds** before actually
     being removed from the DB and the row. Tapping the greyed-out pill again during that window
     **cancels the removal** (re-selects it as still-tagged, reverses the grey-out). This is an
     undo-window pattern — implement it as a coroutine delay + cancellation token per pill, not a
     blocking UI state; the row must still be interactive elsewhere during the window.
  4. When the 2 seconds elapse uncancelled, animate the pill's removal as a **width collapse**,
     not a jump-cut, so the row's content doesn't visibly snap.

### Tests

- Pure JVM: name→colour hashing determinism (same name always → same colorId), truncation-length
  boundary logic if it's implemented as pure functions.
- `TagRepositoryTest` (Robolectric): CRUD, many-to-many assignment/removal, cascade on notebook
  delete, cascade on tag delete (tag deleted from one notebook doesn't affect others).
- ViewModel test for the untag-then-cancel-within-window flow (this is the one genuinely stateful
  piece — needs explicit coverage of: untag → cancel before 2s → still tagged; untag → no cancel →
  removed from DB after 2s).
- Compose/instrumented: fade-edge visibility, tag row never encroaching on the title regardless of
  content length (this is exactly the regression class this design avoided — a device/instrumented
  check earns its keep here), pill truncation + preview-tap.

---

## Phase 3 — AI-driven tag/link suggestions

**Hard dependency: this phase assumes `ai-semantic-layer-design.md`'s `recognized_lines` cache
already exists.** Do not build a third independent ML Kit recognition pass for this — that repeats
exactly the "nothing is reused" problem that design doc's own audit flagged. If that cache isn't
built yet, implement Phases 1–2 only and stop; revisit Phase 3 once it lands.

### Suggestion pipeline

Extend the existing `ai.elrond.domain.PendingSuggestion` / `SuggestionType` enum (`TODO, EVENT`)
with `TAG` and `LINK` rather than inventing a new suggestion table — the existing
`pending_suggestions` infrastructure (type-namespaced de-dup, the `SuggestionExtractionSheet`
checkbox UI, `SuggestionRepository.markHandled`/`recordHandled`) already solves exactly the
"propose, let the user accept/dismiss per item, never re-propose a handled one" problem this needs.
`PendingSuggestion.content` holds the tag name or target notebook id/title as appropriate.

### Extraction scope: notebook-level, not page-level

Unlike `TODO`/`EVENT` (page-scoped, triggered per-page autosave), tag/link suggestions need:
breadcrumb (subject path), existing todos, and **all pages in the notebook** — so the trigger point
is a notebook-level edit event, not the existing per-page `ExtractionWorker` enqueue. Add a
notebook-level aggregation step that concatenates `recognized_lines` text across the notebook's
pages before calling the extractor; this is new plumbing, not a parameter change to the existing
per-page runner.

### Where suggestions surface

Pre-filled, one-tap suggestions inside the *same* UI the manual flow uses — the tag picker (Phase
2) shows suggested tags as tappable chips above/alongside manual entry, and the link target picker
(Phase 1's Quick Nav search) shows suggested notebooks at the top of results. Don't build a
separate suggestions panel; surfacing them in the exact place the user would otherwise manually
search keeps this cheap and low-risk.

### Tests

- Pure JVM: notebook-level aggregation of cached line text (given N pages' cached
  `recognized_lines`, produces the expected concatenated context).
- Extend `AutoExtractionRunnerTest`-style coverage for the new `TAG`/`LINK` suggestion types
  through the same type-namespaced de-dup path as `TODO`/`EVENT`.
- No new Anthropic-call tests beyond what `:aibackend`'s existing extractor test pattern already
  covers (mock the HTTP layer, per project convention — never hit the real API in tests).

---

## Cross-cutting notes

- Follow the by-layer package convention throughout: new domain types (`SelectionState` additions,
  the `Tag`/`NotebookLink` data classes, any pure aggregation logic) go in `ai.elrond.domain` and
  stay Android/Compose-free; repositories/DAOs/entities in `ai.elrond.data`; ViewModel state in
  `ai.elrond.presentation`; Compose surfaces in `ai.elrond.ui`.
- All three phases are additive — no existing table loses columns, no existing behavior changes
  except the Add(+) button gaining real menu entries (it is already a visual no-op placeholder, so
  this isn't a regression risk).
- Confirm the actual next `MIGRATION_n_n+1` number and current `version =` in
  `ElrondDatabase.kt` at implementation time rather than trusting the numbers implied by this doc
  or by `ai-semantic-layer-design.md` — whichever feature is built first claims the next version.
- Run `./gradlew test`, `:app:testDebugUnitTest`, `:aibackend:test` after each phase; don't start
  the next phase on a red build.
- This doc assumes device verification for anything Compose/gesture-heavy (the link box's
  press-and-hold, the tag row's fade edge and collapse animation, the untag-cancel window) the same
  way the rest of this project's FA batches do — flag these explicitly as "device-verify pending"
  in whatever summary follows implementation, don't claim them fixed from unit tests alone.

---

## Acceptance criteria

- ✅ A notebook can be linked from another notebook's canvas via the Add(+) menu → Quick Nav search
  (with the search box added to Quick Nav); the link box opens the target on tap, supports
  press-and-hold select/move/scale/duplicate/delete/copy/cut/paste through the unified
  `SelectionState`, and survives undo/redo.
- ✅ Deleting a linked-to notebook turns the link box into a non-interactive "Reference not found"
  state whose press-and-hold menu offers Redefine and Delete.
- ✅ A notebook's ⋮ menu has a working Backlinks list of every notebook linking to it, each tappable.
- ✅ Notebooks can be tagged from both the Library card ⋮ menu and the canvas header, via one
  shared repository method; tag colour is deterministic per name.
- ✅ The tag row never overlaps the title regardless of tag count or title length; overflow scrolls
  with a fade edge; pills truncate independently with tap-to-preview, tap-again-to-untag, and a
  2-second cancellable grey-out before removal.
- ✅ No page-level tag support is built, but the schema (a plain many-to-many junction) does not
  block adding it later.
- ✅ Subjects auto-creating tags was explicitly rejected — confirm this was not implemented.
- ✅ Phase 3 is either fully skipped (if `recognized_lines` doesn't exist yet) or built strictly as
  a consumer of it — confirm no redundant ML Kit recognition pass was added.
- ✅ All JVM/Robolectric tests green; migration chain test extended; no regressions to existing
  AI-note, subject, or lasso-selection behavior.
