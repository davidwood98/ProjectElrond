# FA-24d: Tag Suggestions

Fourth file in the FA-24 arc. Builds directly on the already-implemented FA-24 Phase 2
(`TagRepository`, `TagRow`/`TagPill`, `notebook_tags` — all live in code today) and on FA-24a's
`notebook_links` graph. Sequenced against **FA-24b** (`recognized_lines`, the semantic-layer
cache — the next feature scheduled for implementation, not yet built): part of this doc does not
need FA-24b at all and can ship before/alongside it; the rest hard-depends on it and must follow.
Do not build the FA-24b-dependent half early or duplicate recognition work — same rule as FA-24c.

This formalizes what was previously just "Phase 3 — AI suggestions" in
`claude_code_prompt_tagging_and_linking_feature.md` (deferred, out of scope at the time). That
phase becomes **Level 2** below; **Level 1** is new and was not previously specced.

## What to Build

Two tiers of tag suggestion, both surfaced in the same `TagRow`/`TagPill` UI that already exists,
reusing its existing grey "pending" rendering rather than inventing a new visual state (see UI
reuse, below).

### Level 1 — existing-tag suggestions (no model call, mostly no FA-24b dependency)

Candidate tags pulled from signals against data that already exists, in priority order:

1. **Same-Subject co-occurrence** — tags already used on sibling notebooks in this notebook's
   Subject. Cheapest, most obviously relevant signal; needs nothing beyond `TagRepository` +
   `SubjectRepository`.
2. **Link-graph co-occurrence** — tags used on notebooks this one links to via FA-24a's
   `notebook_links`. Reuses the link graph as a relevance signal for a purpose it wasn't
   originally built for.
3. **Frequency/recency fallback** — most-used or most-recently-created tags across the whole
   `tags` table, used when the two signals above return nothing (e.g. an unfiled, unlinked
   notebook).
4. **Content-word match (gated on FA-24b)** — does an existing tag's `name` literally appear as a
   word in this notebook's cached `recognized_lines` text? A plain string/token containment check
   against the cache — **not** a model call, still "Level 1" in spirit, but it can only exist once
   `recognized_lines` does. Ship signals 1–3 first; add this one the moment FA-24b lands, as a
   pure addition (no schema change of its own — it only reads FA-24b's table).

Level 1 suggestions are **live queries, not persisted rows** — no `PendingSuggestion` involved.
There is nothing to dedupe against previously-handled suggestions the way `TODO`/`EVENT` extraction
needs, because nothing is being extracted from content (except signal 4, which is a cheap read,
not an extraction run). See Known Unknowns for whether a dismissal should be remembered.

### Level 2 — AI-generated new-tag suggestions (hard-depends on FA-24b)

This is the previously-deferred Phase 3, now confirmed as this doc's Level 2, unchanged in shape:

- Extend `SuggestionType` (currently `TODO, EVENT` in `domain/PendingSuggestion.kt`) with `TAG`.
- Notebook-level aggregation of `recognized_lines` text across all of a notebook's pages — the
  same concatenation pattern `AutoExtractionRunner` already uses per-page
  (`lines.joinToString("\n") { it.text }`), just scoped to a whole notebook instead of one page,
  since tag relevance needs more context than a single page.
- On accept, the suggestion's content (a tag name) flows through `TagRepository.createTag` (which
  is already get-or-create — trimmed name, unique) then `assignTag`. This is exactly the same path
  manual tag entry already uses, so an accepted Level 2 suggestion is indistinguishable from a
  manually created tag afterward — no parallel write path to maintain.
- Do not start this until FA-24b's `recognized_lines` exists, per the same rule FA-24c already
  follows.

## UI reuse

`TagPill`/`TagRow` (`ui/TagComponents.kt`) already implement exactly the grey/muted rendering this
feature wants: `TagPill(pendingRemoval: Boolean, ...)` renders `Neutral200` background /
`Neutral500` text when true, and `TagRow` already threads a `pendingRemovalTagIds: Set<String>`
alongside the confirmed tags. Reuse this directly — add a parallel `suggestedTagIds`/suggested-tag
list rendered through the same `TagPill(pendingRemoval = true)` styling, with tap wired to *commit*
the tag (`createTag`/`assignTag`) instead of *cancel a removal*.

**Deliberately reusing the same visual for two opposite meanings, per instruction:** a grey pill
today means "just untagged, tap to undo within 2s"; this adds "not yet added, tap to add" using
the identical style. This was flagged as a possible source of confusion (same look, opposite
actions) and the decision for now is to keep it exactly as-is rather than invent a second style —
revisit only if real usage shows it's actually confusing, not preemptively.

## Sequencing against FA-24b

| Piece | Depends on FA-24b? | When to build |
|---|---|---|
| Level 1, signals 1–3 (Subject, link-graph, frequency) | No | Now — independent of FA-24b, can ship first |
| Level 1, signal 4 (content-word match) | Yes (reads `recognized_lines`) | The moment FA-24b lands — pure addition, no new schema |
| Level 2 (AI-generated new tags) | Yes (hard dependency, per original Phase 3 scoping) | After FA-24b, as its own follow-up pass |

## Known Unknowns

1. **Dismissal memory.** If a user dismisses a Level 1 suggested pill, should it be remembered so
   it doesn't reappear next time the tag picker opens for that notebook, or is re-suggesting the
   same candidate acceptable since there's no AI cost to repeating a live query? This decides
   whether Level 1 needs any persisted state at all — right now it's specced as stateless.
2. **Level 2 dedup.** Once `TAG` is added to `SuggestionType`, does it reuse the exact same
   type-namespaced de-dup `PendingSuggestion` already gives `TODO`/`EVENT` (handled-once, never
   re-proposed)? Assumed yes for consistency; flag if tag suggestions need different lifecycle
   rules (e.g. a tag suggestion becoming stale if the notebook's content changes substantially).

## Tests

- Pure JVM: each Level 1 signal's query logic in isolation (same-Subject candidates, link-graph
  candidates, frequency fallback ordering) and the content-word-match string logic once FA-24b
  exists.
- `TagRepositoryTest`: suggestion-accept path produces the same end state as manual tag entry
  (same `createTag`/`assignTag` calls, no divergent code path).
- ViewModel/Compose: suggested pills render via the existing `pendingRemoval` style; tapping one
  commits it (moves it out of the suggested set into the confirmed `TagRow`); tapping a genuinely
  pending-removal pill still cancels the removal — the two meanings don't cross-trigger each
  other's action.
- Once Level 2 lands: extend `AutoExtractionRunnerTest`-style coverage for `TAG` through the same
  de-dup path as `TODO`/`EVENT`, and a notebook-level (not page-level) aggregation test.

## Acceptance criteria

- ✅ Level 1 suggestions (Subject co-occurrence, link-graph co-occurrence, frequency fallback) ship
  without waiting on FA-24b, using only `TagRepository`, `SubjectRepository`, and
  `notebook_links`.
- ✅ Content-word-match (Level 1, signal 4) is added as a pure read the moment FA-24b lands, with
  no new schema.
- ✅ Level 2 stays gated behind FA-24b and reuses `TagRepository.createTag`/`assignTag` on accept —
  no second write path for AI-originated tags.
- ✅ Suggested pills reuse the existing `pendingRemoval` grey style verbatim (no new visual state
  introduced in this pass).
- ✅ Tapping a suggested pill and tapping a pending-removal pill remain distinguishable in behavior
  (commit vs. cancel) even though they look identical — verified by a test, not just visual review.
