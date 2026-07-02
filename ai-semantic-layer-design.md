# AI Semantic Layer — Recognition Cache Design (FA-22 Phase 3)

## Problem

Every AI feature re-derives "what this page says" from raw ink, every time:

| Feature | ML Kit `recognize()` calls today | When |
|---|---|---|
| `/Q` command trigger detection | 1 (candidates, last line) | per 900ms stroke debounce |
| `/Q` query submission | +N question lines +M context lines | per query |
| Circle-gesture query | 1 per enclosed line | per gesture |
| Prefix-mode scan | up to 1 per line on the page | per 900ms stroke debounce |
| Prefix-mode submission | question lines + **all** context lines | per query |
| Background extraction (`ExtractionWorker`) | **every line on the page** | **every autosave with new ink** (1.5s debounce) |

Nothing is reused (audit 2026-07-01: no in-memory or persisted recognition cache exists;
`NotePageEntity.contextSummary` exists but is never populated). A 30-line page pays ~30 ML Kit
calls (~50–150ms each) per extraction run and per `/Q` context assembly — seconds of on-device
CPU before the Anthropic request even starts, repeated after every writing pause. Beyond cost,
this blocks cheap future features (search over handwriting, cross-page AI context, page
summaries) because there is no queryable text model of the notes.

The user requirement: AI must stay **cheap and fast**, but **full page context must remain**
available to `/Q` — so the fix is to make context assembly free, not to trim context.

## Proposal — persistent, incrementally-invalidated `recognized_lines`

One new table (DB v17) as the single "what the page says" layer:

```
recognized_lines
  id            TEXT PK          -- deterministic: hash of (pageId + ordered stroke ids)
  pageId        TEXT FK → note_pages ON DELETE CASCADE, indexed
  strokeIds     TEXT             -- ordered stroke ids forming the line (the identity)
  text          TEXT             -- ML Kit result
  minX/minY/maxX/maxY REAL       -- line bounds in page space
  recognizedAt  INTEGER
```

**Line identity is the ordered stroke-id set.** Lines are transient today
(`StrokeLineGrouper` recomputes them per call), so the cache key must be derived from what IS
stable: `CanvasStroke.id` survives transforms and persistence. Any membership change (stroke
added to / erased from a line, lines merged/split by a new stroke between them) produces a
different key → automatic invalidation. This sidesteps line-tracking entirely: there is no
"update line" problem, only "this stroke-id-set has/hasn't been recognized before".

### Write path (the sync step)

After each successful stroke autosave (same trigger that enqueues extraction):

1. Group the page's strokes into lines (`StrokeLineGrouper` — pure geometry, no ML Kit, cheap).
2. Compute each line's key; diff against the page's `recognized_lines` rows.
3. Recognize **only the new/changed lines** (usually 1 — the line being written).
4. Upsert those rows; delete rows whose key no longer exists.

Transform-only changes (lasso move/scale) keep stroke ids ⇒ same key ⇒ text stays valid; only
bounds need recomputing from geometry — no re-recognition. Steady-state cost per writing pause:
**one** ML Kit call instead of page_lines.

Placement: `data/RecognitionCache` (repository + DAO) with the sync step running inside
`ExtractionWorker` **before** extraction (it already owns a recognizer, closes it in `finally`,
and is battery/settings-gated) — no new WorkManager job needed. `AutoExtractionRunner`'s
`buildRecognizedLines` seam then reads cache-hits and recognizes only misses, unchanged in shape
(it's already the injected recognition seam, so the runner stays pure and JVM-testable).

### Read paths (in adoption order)

1. **Background extraction** — biggest win, zero UX risk: page text comes from the cache;
   ML Kit runs only for dirty lines.
2. **Extraction skip-gate** — store a hash of the page's assembled text on the extraction run;
   if unchanged since the last run, **skip the two Anthropic calls entirely** (tasks + events).
   Today only ink-dirtiness gates the enqueue; a rewrite of the same content still pays tokens.
3. **`/Q` context assembly** — the question/trigger line is still recognized live (it's 1–2
   lines and must be fresh), but the "other notes on the page" context reads cached text.
   On a dense page this removes seconds of pre-network latency from every `/Q`.
4. **Prefix-mode scan** — lines with cached text can be checked for `isStandaloneTrigger`
   against the cached string at zero ML Kit cost; only uncached lines need `recognizeCandidates`.
5. **Later (enabled, not built now):** handwriting search in the Library, populating
   `note_pages.contextSummary`, cross-page AI context for notebook-level questions.

### What deliberately does NOT change

- **`/Q` keeps full page context** — same prompt envelope (`Handwritten question: … / Other
  notes on the page …`), just assembled from the cache.
- The Anthropic system prompt already carries `cache_control` (prefix caching) and the dynamic
  reference date is already kept in the user prompt — both stay as-is.
- The trigger/question line is always recognized fresh — a cache can never make the AI answer a
  stale question.
- Recognition stays on-device ML Kit; nothing new is sent to the network.

### Cheap-and-quick extras considered

- **Model tiering for extraction** (Haiku-class via `AnthropicConfig`): viable, config-only,
  but token spend there is already small; do after the skip-gate, which saves 100% of redundant
  calls rather than ~70% of their cost.
- **Anthropic-side caching of page context**: low value — context changes on every write, and
  the system prompt (the stable prefix) is already cached.
- **Trimming context**: rejected per requirement; the cache makes full context affordable.

## Cost / risk

- Storage: text rows only — negligible next to stroke blobs.
- The sync step runs post-save on a background worker, battery-gated like extraction today.
- Grouping instability is handled by keying on membership (worst case: a merged line
  re-recognizes once).
- Multi-page (FA-20): cache is per `pageId` — composes with the page model as-is.
- Failure mode is safe: a cache miss just means recognizing that line as today.

## Implementation sketch (one FA batch)

1. DB v17: `recognized_lines` + `MIGRATION_16_17` (create-only, no backfill — cache warms on
   first open/save); `RecognizedLineEntity`, DAO, `RecognitionCacheRepository`.
2. Sync step in `ExtractionWorker` + cache-aware `buildRecognizedLines`; extraction skip-gate
   (text hash on the run).
3. `/Q` + prefix read paths in `CanvasViewModel` (cache injected as a nullable seam, so all
   existing JVM tests construct unchanged).
4. Tests: cache diff/invalidation (pure JVM), worker skip-gate, migration chain → v17,
   `/Q`-context-from-cache VM test; device pass for end-to-end latency.
