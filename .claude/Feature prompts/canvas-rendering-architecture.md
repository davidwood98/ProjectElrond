# Canvas Rendering Architecture — Performance Investigation & Fix

## Context

Project Elrond is an Android handwriting note app (Kotlin + Jetpack Compose, Samsung Galaxy Tab S /
S Pen target). Users are experiencing degrading stroke render performance as a page fills with
handwritten notes — slight delays on the first letter of new words, worsening linearly with stroke
count. Finger input has been ruled out as a cause.

A research pass against Apple PencilKit, Notability, Procreate, ISF, and Android ink best practices
identified three root causes. This document is a scoped investigation and implementation brief.

---

## Current Architecture (what exists today)

### Rendering path — DryStrokesView

`app/src/main/java/ai/elrond/ui/InkCanvas.kt`

`DryStrokesView.onDraw` iterates **every stroke on the page** on every repaint and calls
`CanvasStrokeRenderer.draw()` (a hardware `Canvas.drawMesh` call) for each one:

```kotlin
override fun onDraw(canvas: Canvas) {
    layers.forEach { layer ->
        layerMatrix.setTranslate(0f, layer.docTopPx)
        layer.strokes.forEach { cs ->
            if (cs.id in excludedIds) return@forEach
            renderer.draw(canvas, cs.stroke, layerMatrix)
        }
    }
}
```

Repaint is triggered by `setLayers()` → `invalidate()` every time `_finishedStrokes` emits (i.e.
after every single stroke finishes). On a half-filled page (~200 strokes) every stroke completion
fires 200 `drawMesh` calls. There is no frame cost bound — it grows O(n) with stroke count.

There is **no viewport culling**: strokes scrolled off-screen are still drawn every frame.

### Autosave path — replaceStrokes

`app/src/main/java/ai/elrond/presentation/CanvasViewModel.kt` — `startAutoSave` (line ~1147)
`app/src/main/java/ai/elrond/data/NoteRepository.kt` — `replaceStrokes` (line ~220)
`app/src/main/java/ai/elrond/data/StrokeSerialization.kt` — `toEntity`

The autosave debounce is **800ms** (`AUTOSAVE_DEBOUNCE_MILLIS`). On every fire it:

1. Re-serialises **every stroke on the page** to JSON (`json.encodeToString(points)` per stroke,
   iterating all input points)
2. Deletes all stroke rows for the page from Room, then re-inserts all of them
   (`strokeDao.replaceForPage` = DELETE + `insertAll`)
3. Generates a new thumbnail (reads all strokes from DB → renders to Bitmap → compresses to WebP
   → writes to disk)

This is O(total_strokes × points_per_stroke) work on every save. At 200 strokes × 200 points =
40,000 point-to-JSON operations per save, running on `Dispatchers.Default` every ~800ms during
active writing — which corresponds to between-word pauses.

### Storage format — JSON per stroke

`app/src/main/java/ai/elrond/data/StrokeSerialization.kt`
`app/src/main/java/ai/elrond/data/Entities.kt` — `StrokeEntity.inputsJson: String`

Each stroke is stored as a JSON array of objects with 7 fields per point:

```json
[{"x":123.4,"y":456.7,"t":1000,"pressure":0.8,"tilt":0.1,"orientation":0.0,"tool":"stylus"}, ...]
```

Cost: ~40–50 bytes per input point as JSON. A 200-point stroke = ~8–10KB. The same data as packed
IEEE 754 floats = ~1.6KB (5–6× smaller, and 10× faster to encode/decode with no GC pressure).

---

## What professional apps do instead

### Raster cache — the key insight (Procreate, PencilKit)

Procreate (Valkyrie engine) is a **raster** application. When a stroke finishes it is immediately
rasterised into the layer's bitmap. Per-frame rendering is a single GPU texture blit regardless of
how many strokes the page contains. There is no O(n) stroke loop anywhere in the render path.

Apple PencilKit (used by Apple Notes) uses a **tile-based raster cache**: the page is divided into
tiles (~256–512dp), each tile holds a pre-rendered Bitmap. When a stroke finishes, only the tiles
it intersects are re-rendered. Per-frame compositing blits only the visible tiles.

### Append-only saves (OneNote, Notability sync)

OneNote uses an append-only binary revision store with binary deltas — new strokes are appended,
not a full page rewrite. Notability uses relative encoding for sync, implying delta/append writes.
The full-rewrite pattern (`replaceForPage`) is only necessary after destructive operations
(erase, undo, lasso delete/move).

### Binary stroke storage (Notability, Squid, ISF)

Notability stores ink as **packed arrays of 32-bit floats** (raw binary, no JSON):
- `curvespoints` — flat byte array of (x, y) float pairs
- `curvesnumpoints` — flat int array
- `curveswidth`, `curvescolors` — flat float/int arrays

Microsoft's ISF format adds first/second-derivative delta encoding + Huffman on top of binary
floats, achieving 8.2× compression vs raw storage. Squid (Android) uses Protocol Buffers.

---

## Three targeted fixes (in priority order)

### Fix 1 — Raster cache for dry ink (eliminates O(n) rendering)

**Goal:** `DryStrokesView.onDraw` becomes a single `canvas.drawBitmap(cache, ...)` call
regardless of stroke count. Per-frame cost becomes O(1).

**Approach:**
- Add a `cachedBitmap: Bitmap` to `DryStrokesView`, sized to the full page in page-space pixels.
- When `setLayers` is called with a stroke addition (the common case), draw only the new
  stroke(s) onto `cachedBitmap` using a `Canvas(cachedBitmap)` — but note that
  `CanvasStrokeRenderer.draw` requires a hardware canvas (it uses `drawMesh`). You must use a
  hardware-backed `Picture` or `RenderNode` to record the stroke draw commands, then replay
  them onto the bitmap using a hardware canvas.  
  Alternatively, use Android's `HardwareCanvas` approach: keep a hardware-backed `Bitmap`
  (`Bitmap.Config.HARDWARE`) and update it via `PixelCopy` or by keeping a software fallback
  for the cache and using `CanvasStrokeRenderer` only in `onDraw` with hardware canvas.
- `onDraw` blits `cachedBitmap` at the layer's `docTopPx` offset, then the excluded (selected)
  strokes are drawn on top via the existing loop (typically 0–20 strokes, not 200+).
- On erase or undo (destructive ops flagged by the VM), rebuild the cache from all strokes.
- On zoom/scale change, rebuild the cache (since it's in page-space pixels, a scale change
  requires a new bitmap at the new effective resolution — or use a vector-to-tile approach).

**Investigation needed first:**
- Confirm whether `CanvasStrokeRenderer.draw` can target a `Canvas` backed by a software
  `Bitmap` (it uses `drawMesh` which is documented as hardware-only — this is the critical
  constraint). Check `androidx.ink` source / docs.
- If hardware-only: explore `RenderNode` / `Picture` recording as the cache medium instead of
  a `Bitmap`. A `RenderNode` records draw commands and replays them onto a hardware canvas
  cheaply — this may be the correct approach for the ink mesh renderer.
- Alternatively: maintain a per-stroke display list and use `RenderNode` per stroke, combining
  them. This avoids the re-rasterise-on-zoom problem.

**Files to modify:** `InkCanvas.kt` (DryStrokesView), potentially `CanvasViewModel.kt` to signal
additive vs destructive updates.

---

### Fix 2 — Incremental/append-only autosave (eliminates O(n) serialisation)

**Goal:** Normal writing (new pen strokes) saves only the new strokes, not the entire page.
`replaceForPage` is reserved for destructive operations.

**Approach:**
- In `CanvasViewModel`, track a `strokesSinceLastSave: List<CanvasStroke>` that accumulates
  strokes added by `onStrokesFinished` since the last successful save.
- Add a boolean `needsFullReplace: Boolean` that is set `true` by any destructive operation
  (erase, undo/redo, lasso delete, lasso move/scale, paste, duplicate, clear page).
- In `startAutoSave`, on each debounce fire:
  - If `needsFullReplace`: call `repository.replaceStrokes(pageId, allStrokes)` as today, then
    reset both flags.
  - Otherwise: call a new `repository.appendStrokes(pageId, strokesSinceLastSave)` which just
    calls `strokeDao.insertAll(newEntities)` — no DELETE, no full rewrite.
- `appendStrokes` in `NoteRepository` is a new thin method alongside `replaceStrokes`:
  ```kotlin
  suspend fun appendStrokes(pageId: String, strokes: List<CanvasStroke>) {
      val now = clock()
      val entities = withContext(Dispatchers.Default) {
          strokes.map { it.toEntity(pageId, now, isAiInk = false) }
      }
      strokeDao.insertAll(entities)
      pageDao.touch(pageId, now)
      recordEdit(pageId, now)
  }
  ```
- Also raise `AUTOSAVE_DEBOUNCE_MILLIS` from 800ms to 1500ms. This aligns with end-of-sentence
  pauses rather than between-word pauses, reducing save frequency without data loss risk (the
  `onCleared` flush is already in place as a safety net).

**Files to modify:** `CanvasViewModel.kt`, `NoteRepository.kt`, `Daos.kt` (no schema change
needed — `insertAll` already exists).

**Tests to update/add:** `CanvasViewModelThumbnailTest`, `NoteRepositoryTest`. Verify that
after an erase + undo cycle, a full replace is triggered not an append.

---

### Fix 3 — Binary stroke storage (eliminates JSON overhead)

**Goal:** Replace `StrokeEntity.inputsJson: String` with `inputsBlob: ByteArray`, cutting
storage and serialisation cost by 5–10×.

**This is a schema change (DB migration required)** and should be done as a dedicated pass after
Fixes 1 and 2 are stable. Flag for a separate session.

**Approach:**
- Encode points as a flat `ByteBuffer` of packed IEEE 754 floats + a single `tool` byte per
  point. 8 values × 4 bytes (treating `tool` as a byte not a string) = ~29 bytes per point vs
  ~45 bytes JSON.
- Add `inputsBlob: ByteArray` column to `StrokeEntity`, migration drops `inputsJson` or keeps
  both during a transition window.
- `StrokeSerialization.toEntity` uses `ByteBuffer.allocate(points.size * 29).put(...)`.
- `StrokeSerialization.toStroke` uses `ByteBuffer.wrap(blob)` to read back.
- No new dependencies — `java.nio.ByteBuffer` is available everywhere.

**Files to modify:** `StrokeSerialization.kt`, `Entities.kt`, `Daos.kt`, new Room migration,
`ElrondMigrationTest`.

---

## Investigation checklist before writing any code

Before starting implementation, answer these questions from the codebase and androidx.ink docs:

1. **Can `CanvasStrokeRenderer.draw` target a software-backed `Canvas`?**  
   If yes → Bitmap cache is straightforward.  
   If no (hardware-only) → investigate `RenderNode` / `Picture` as the cache medium.

2. **Where exactly does the VM signal additive vs destructive stroke changes?**  
   Map `onStrokesFinished`, `eraseAt`, `undo`, `redo`, `commitTransform` (lasso),
   `deleteSelected`, `pasteClipboard` — confirm which are additive vs destructive.

3. **Does `strokeDao.insertAll` use `OnConflictStrategy.IGNORE` or `REPLACE`?**  
   Check `Daos.kt` — ensure an append of new strokes with fresh UUIDs can't conflict with
   existing rows.

4. **What is the current `_finishedStrokes` StateFlow emission pattern for erase/undo?**  
   Confirm whether `needsFullReplace` tracking needs to be in the StateFlow collector or in the
   operation methods themselves.

5. **Does the thumbnail generator need updating for the raster cache approach?**  
   `ThumbnailCache` renders polylines from decoded JSON points — this remains valid regardless
   of Fix 1 and Fix 2. Fix 3 (binary storage) requires `ThumbnailRenderer` to read the new
   format.

---

## Expected outcomes

After Fix 1: per-frame `onDraw` cost becomes O(1) — no degradation as the page fills.  
After Fix 2: autosave cost during writing drops from O(total_strokes) to O(new_strokes) ≈ O(1).  
After Fix 3: stroke serialisation is 5–10× faster; DB storage 5–6× smaller per page.

The "first letter of word" delay should disappear after Fix 2 alone (the serialisation backpressure
during between-word pauses). Fix 1 prevents the rendering from ever degrading as the page fills.

---

## Related files summary

| File | Role |
|---|---|
| `ui/InkCanvas.kt` | DryStrokesView.onDraw — the O(n) render loop |
| `presentation/CanvasViewModel.kt` | startAutoSave, AUTOSAVE_DEBOUNCE_MILLIS, onStrokesFinished |
| `data/NoteRepository.kt` | replaceStrokes, appendStrokes (to be added) |
| `data/StrokeSerialization.kt` | toEntity (JSON encoding), toStroke (JSON decoding) |
| `data/Daos.kt` | StrokeDao.replaceForPage, insertAll |
| `data/Entities.kt` | StrokeEntity.inputsJson (to become inputsBlob in Fix 3) |
| `data/ThumbnailCache.kt` | Thumbnail generation — update in Fix 3 |

DB is currently v15. Fix 2 requires no migration. Fix 3 requires migration to v16.  
Test suite: 323 app + 24 aibackend JVM/Robolectric tests must remain green.
