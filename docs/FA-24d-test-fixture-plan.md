# FA-24d Tag Suggestion — Repeatable Test Fixture Plan

Grounded against the actual current implementation (DB v22), not the original design docs, which
are now partially stale — notably **Level 1's "frequency/recency fallback" signal was removed**
(device round 2: it was dumping the entire tag registry onto blank/unfiled notebooks) and is
**not** part of this fixture. Live Level 1 signals are: same-Subject co-occurrence, link-graph
co-occurrence, and content/title whole-word match (`TagSuggestionEngine.kt`). Level 2 is the AI
path (`TagSuggestionRunner` → `TagSuggestionExtractor`), which can both endorse an existing tag
(`AI_EXISTING`) and propose new ones (`AI`).

## test surfaces

1. **Manual device pass** — real handwriting, real ML Kit recognition, real Anthropic call for
   Level 2. This is the only way to see actual AI wording quality, but Level 2's exact output
   text is never guaranteed word-for-word (see per-notebook notes).


## scaffolding

**Subject:** one flat subject, `QA Sandbox` (no nesting needed).

**Pre-existing tag registry** (create before seeding notebooks, none pre-assigned yet except where
noted per-notebook): `budget`, `work`, `personal`, `urgent`.

## The notebook set

| # | Notebook name | Subject | Pre-assigned tags | Links | Content (write/seed this text) | Expected Level 1 | Expected Level 2 |
|---|---|---|---|---|---|---|---|
| 1 | `QA - Source A` | QA Sandbox | `budget`, `work` | — | "Q3 budget review notes." | n/a (this is the seed for #2's test, not itself under test) | n/a |
| 2 | `QA - Target Subject Match` | QA Sandbox | none | — | "Team standup notes for Monday." (no tag-name overlap) | Suggests `budget` + `work` via **same-Subject co-occurrence only** | None expected (content isn't distinctive) |
| 3 | `QA - Link Target` | unfiled | `personal` | — | "Personal reflections." | n/a (seed for #4) | n/a |
| 4 | `QA - Target Link Match` | unfiled | none | → links to #3 | "Random doodle page." (no tag-name overlap) | Suggests `personal` via **link-graph co-occurrence only** | None expected |
| 5 | `QA - Content Word Match` | unfiled | none | — | "Need to finalize the household budget this week." | Suggests `budget` via **content/title word match only** — must NOT suggest `work`/`personal`/`urgent` | None expected |
| 6 | `QA - Blank Regression Guard` | unfiled | none | — | **blank page, no ink at all** | **Empty** — this is the explicit regression test for the removed fallback-signal bug | None (empty aggregate text — confirm this path doesn't crash and doesn't call the API) |
| 7 | `QA - AI New Tag` | unfiled | none | — | "Buy milk, eggs, bread. Plan meals for the week: pasta Monday, tacos Tuesday, stir fry Wednesday. Check pantry for rice and beans." | None (no Subject/link signal, no literal tag-name overlap) | AI (`origin = AI`) proposes at least one new tag reflecting groceries/meal-planning — exact wording (e.g. "groceries" vs "meal-planning") is not guaranteed |
| 8 | `QA - AI Existing Endorsement` | unfiled | none | — | "This needs to be done right away, no time to spare, top priority today." (never uses the literal word "urgent") | None (literal word "urgent" is absent, so the content-match signal correctly does *not* fire) | AI endorses the existing `urgent` tag (`origin = AI_EXISTING`) — this is the case that specifically distinguishes semantic AI matching from Level 1's literal-word matching |

Notebooks #1 and #3 aren't "under test" themselves — they exist purely to give #2 and #4 something
to co-occur with. Keep all titles free of the four tag words (double-checked above) so nothing
accidentally leaks into the content/title-match signal.

## Optional 9th case — exploratory, not asserted

A notebook in `QA Sandbox` (co-occurrence would suggest `work`) whose content *also* literally
contains "work," and is rich enough that Level 2 might independently endorse `work` too. Don't
assert a specific expected output here — the exact dedupe/merge behavior across tiers when the
*same* tag is reachable from both Level 1 and Level 2 isn't confirmed (see Known Unknowns below).
Use this one to observe actual behavior rather than to check a predicted answer.
went with "When I get into the office on Monday I need to chat with John about work for the coming week. There is a couple of work packages that need doing and the team needs to all get into the workplace office to finish them." 

## Resetting a single notebook

Deleting `NotebookEntity` cascades through `NotePageEntity` → `recognized_lines` /
`notebook_tags` / links sourced from its pages, so recreating one notebook fresh is mostly just
"delete the row, re-insert." **One thing to verify before relying on this:** whether
`pending_suggestions.notebookId` has an enforced cascading FK, or is just a plain nullable column.
If it's not cascaded, a stale `TAG` `PendingSuggestion` row from a previous run could survive a
notebook delete+recreate and reappear in `TagSuggestionProvider.observe(...)` even though the
notebook looks "fresh" — manually clear `PendingSuggestion` rows for that `notebookId` as part of
the reset until this is confirmed.

## Known Unknowns to confirm, not assume

1. **Cross-tier dedupe.** If the same tag is reachable via both Level 1 (e.g. Subject
   co-occurrence) and Level 2 (AI endorsement), does it appear once (with which `origin`?) or
   twice? The "AI-first" merge order is confirmed; whether it also dedupes by tag identity is not.
2. **`pending_suggestions.notebookId` cascade behavior**, per the reset procedure above.
3. **Level 2 on empty aggregate text** (notebook #6) — confirm this returns cleanly with zero
   suggestions and zero API calls rather than erroring or vacuously "succeeding" with garbage.

## Manual device-pass notes

For notebooks #7 and #8 specifically, remember exact AI wording isn't a fixed target — verify
*that* a semantically appropriate suggestion appears (new tag for #7, endorsement of `urgent` for
#8), not an exact string match. Everything else in the table (#2, #4, #5, #6) has a fully
deterministic expected answer regardless of which test surface you use, since none of those
depend on live model output.
