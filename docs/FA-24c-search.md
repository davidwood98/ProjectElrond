# FA-24c: Notebook & Content Search

Third file in the FA-24 arc — depends on **FA-24a** (notebook cross-linking) and **FA-24b**
(notebook tagging), both in `claude_code_prompt_tagging_and_linking_feature.md`, plus the
`recognized_lines` cache proposed in `ai-semantic-layer-design.md`. Do not start this until
`recognized_lines` exists — search is a read-only consumer of it, not a reason to build it early
or duplicate recognition work.

## What to Build

A search system that works from three different entry points with three different scopes:

| Entry point | Scope |
|---|---|
| Library (All Notes) | every notebook |
| Subject view | notebooks **directly** in this subject by default; expands to include sub-folders **only when actively searching** |
| Notebook/page view | this notebook's pages only |

Search must match on **title, tags, and handwritten content** — three different underlying
sources merged into one result list, not one blob of text.

## Data model

### Content search: FTS over `recognized_lines`

Add a companion FTS5 virtual table — do not add search columns to `recognized_lines` itself,
which stays purely a recognition cache (its `strokeIds`-derived key and bounds are for
invalidation and on-canvas positioning, not search):

```
recognized_lines_fts (FTS5)
  text     -- mirrors recognized_lines.text
  -- linked back to recognized_lines.id via rowid, so a hit can be joined to pageId + bounds
```

Use **FTS5, not FTS4** — `bm25()` ranking and `snippet()` (a highlighted excerpt around the
match) are FTS5-only, and both matter for a usable results list (rank by relevance, show *why*
each result matched, not just that it did).

**Sync obligation:** every place `ai-semantic-layer-design.md`'s write path upserts or deletes a
`recognized_lines` row, mirror the same insert/delete into `recognized_lines_fts` in the same
transaction. This is the same discipline as keeping any cache and its index consistent — don't
let this drift into a second background sync job; it rides along with the existing upsert/delete
step.

### Block-level FTS (paragraph-spanning phrases)

Line-granularity FTS is precise for jump-to-hit but blind to a phrase that wraps across a line
break — and a tightly-packed handwritten paragraph (a short annotation squeezed into a small
area) is exactly the case most likely to wrap mid-phrase, since it has the least horizontal room.
`AutoExtractionRunner` sidesteps this today by concatenating a whole page's lines with `\n` before
handing text to the AI (`fullText = lines.joinToString("\n") { it.text }`), so extraction/tag
suggestions already read across line breaks fine — this gap is specific to line-by-line FTS search.

Add a second virtual table at coarser grain, grouped the same way `StrokeLineGrouper.blockAbove`
already groups a multi-line `/Q` prompt — a contiguous run of lines with no paragraph-sized
vertical gap between them (`DEFAULT_PARAGRAPH_GAP_FACTOR`):

```
recognized_blocks_fts (FTS5)
  text     -- concatenated text of a contiguous line-group (blockAbove-style), "\n"-joined
  -- linked back to the constituent recognized_lines ids, so a hit resolves to a union of bounds
```

Recomputed/synced at the same point `recognized_lines` is diffed (same write path) — regroup a
page's lines into blocks, diff against existing block rows, upsert/delete changed ones. A block's
identity is its ordered constituent line ids, mirroring how a line's identity is its ordered
stroke ids — same invalidation reasoning, one level up.

**Query behaviour:** always query both tables. If a query already matches at line granularity,
don't also surface the enclosing block as a separate result (dedupe by page + overlapping bounds)
— block-level results only surface when a phrase matches *across* a break that no single line
contains. A block match's on-canvas highlight is the union of its constituent lines' bounds (see
Result highlighting, below), not a single line's box.

### Title search

Query the notebook title field directly (already a plain column on the notebook/root-page
entity) — no new storage.

### Tag search

Query `tags`/`notebook_tags` from FA-24b directly — no new storage. This is the reason FA-24c
depends on FA-24b: tag search has nothing to query until that table exists.

### Subject scoping

Subject-view search needs "this subject id + all descendant subject ids." That subtree walk does
not exist yet — `SubjectTree` (in `ai.elrond.domain`) currently only supports `pathTo` (walking
**up** to an ancestor for the breadcrumb). Add a `descendantsOf(subjectId, byId)`-style pure
function alongside it, same shape/testability as `pathTo`. This is net-new code used **only** by
search — it must not change the existing subject-grid filter, which stays direct-children-only by
design (confirmed: showing all sub-folder notes by default was considered and rejected as
cluttered).

## Query shape

Three independent queries, merged and labelled by match type, not one UNION pretending they're
the same kind of data:

1. Title match — `LIKE`/simple contains against notebook titles, scoped by the level's notebook
   id set.
2. Tag match — join `notebook_tags` → `tags` on name, scoped the same way.
3. Content match — query `recognized_lines_fts` **and** `recognized_blocks_fts`, join back to
   `recognized_lines` for `pageId` + bounds, then to `note_pages`/notebook, scoped the same way,
   deduping block hits that a line hit already covers.

Scoping (the notebook id set each query filters against) is computed once per search — all
notebooks for Library, `{subjectId} ∪ descendantsOf(subjectId)` for Subject view, or a single
notebook id for Notebook view — and reused across all three queries.

## UX

- Tapping a **content** result opens the notebook in **search-result mode** (see Result
  highlighting, below), landing on the page containing that result.
- Tapping a **title** or **tag** result opens the notebook normally (no highlight mode — there's no
  specific on-canvas position to point at).
- When a Subject-view search surfaces a result from outside the current (direct) subject — i.e.
  from a descendant picked up only because search expands scope — show a small breadcrumb chip on
  that result row (e.g. "in Meeting Notes / Q3") so it's clear why it appeared outside what the
  grid normally shows there.
- Results list should show which field matched (title/tag/content) and, for content matches, the
  `snippet()` excerpt.

## Result highlighting

Opening a content result puts the notebook into a **search-result mode**, not a one-shot jump:

- Every matching line (or block, for a paragraph-spanning phrase) gets a **transparent accent-fill
  bounding box** drawn over it, positioned from the stored bounds the same way `AiInkNoteView`/
  `LinkBoxView` position themselves (`pageToScreen` + the page's live transform) — a new passive
  overlay layer, not interactive itself beyond existing).
- **This is not a single-result jump-and-forget.** All of a search's matching pages keep their
  highlights live for as long as search-result mode is active. The user flicks through the
  notebook with normal page-turn navigation (no separate stepper/next-prev control) and any page
  they land on shows its own matches highlighted, if it has any.
- **Each occurrence gets its own highlight box** — if a page has three matches, that's three boxes,
  not one merged region (a block-level match is the one exception: its box is the union of its
  constituent lines' bounds, since the phrase itself spans them).
- **Exiting search-result mode:** while active, a standalone pill appears positioned under the
  title (`EditorHeader`'s title sits top-left, `widthIn(max = 320.dp)` — this pill sits directly
  below it, not inline with the title/date row and separate from the tag row from FA-24b). Content
  reads **"Search | ✕"** — the whole pill is a label, only the **✕** segment is pressable. Tapping
  it clears all highlight state for the notebook and returns the canvas to normal; this is the only
  way out, there's no auto-dismiss/fade.
- **Centering:** if the page currently on screen has exactly one match, the viewport centers on
  that match's bounds when the page first opens. (Multiple matches on one page: centering target
  is unspecified — pick a sensible default such as the union of that page's matches — and flag it
  back for confirmation rather than assuming silently, since more than one interpretation is
  reasonable.)

## Tests

- Pure JVM: `descendantsOf` (empty subject → itself only; nested tree → all levels; cycles can't
  occur given FK-enforced tree, but test a multi-level chain).
- Pure JVM: block-grouping regrouping/diffing logic (mirrors the line-level diff test below, one
  level up) and the line↔block dedupe rule (a query matching at line level suppresses the
  enclosing block result; a phrase spanning a break surfaces only the block result).
- Repository: FTS sync stays consistent with `recognized_lines` upsert/delete (insert a line,
  assert it's searchable; delete it, assert it's gone from FTS too) — this is the one part of this
  feature most likely to silently drift, so cover it directly rather than trusting the "same
  transaction" discipline alone. Extend the same coverage to `recognized_blocks_fts`.
- Query-merge logic: given fixture rows across all three sources, assert correct scoping per level
  (Library/Subject/Notebook) and correct labelling of match type.
- Instrumented: tapping a content result opens search-result mode with the correct page and
  highlight boxes; flicking to a different page with matches shows that page's highlights;
  pressing the corner ✕ clears all highlight state; a block-level match's box unions its
  constituent lines' bounds.

## Acceptance criteria

- ✅ Search from Library, Subject, and Notebook views all work, each scoped correctly.
- ✅ Subject view's default grid is unchanged (direct notebooks only); only an active search
  expands to sub-folders, with a breadcrumb chip marking out-of-scope hits.
- ✅ A phrase spanning a wrapped line within a tightly-packed paragraph/annotation still matches,
  via the block-level FTS table, without duplicating a result the line-level table already found.
- ✅ Content search results open search-result mode: every matching page shows its own highlight
  box(es) when navigated to via normal page-turning, persisting until the corner ✕ is pressed;
  title/tag results just open the notebook.
- ✅ `recognized_lines_fts` and `recognized_blocks_fts` never drift from `recognized_lines`
  (covered by a direct test, not assumed from the write-path discipline alone).
- ✅ No new recognition work — this phase is a pure consumer of the existing `recognized_lines`
  cache and FA-24b's tag tables.
