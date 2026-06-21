package ai.elrond.data

import ai.elrond.presentation.CanvasViewModel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed note-card thumbnail cache: one WebP per page under
 * `<cacheDir>/thumbnails/<pageId>.webp`.
 *
 * The note browser used to redraw each card's stroke polylines from scratch on every recomposition,
 * decoding stroke JSON on the main thread for up to [ai.elrond.data.NoteRepository.PREVIEW_MAX_STROKES]
 * strokes per card — with a gridful of cards loading at once (the nav transition into the browser)
 * that stuttered. We now render the polylines to a bitmap ONCE, cache it as a WebP, and the browser
 * just decodes the file off the main thread.
 *
 * Generation deliberately renders the same normalized polylines the card already drew (see
 * [ThumbnailRenderer]) rather than capturing the live ink View: the dry-ink View draws via
 * [androidx.ink.rendering.android.canvas.CanvasStrokeRenderer], which uses `Canvas.drawMesh` and is
 * hardware-only — it throws "software rendering doesn't support meshes" on a software `Bitmap`
 * canvas, so it cannot be captured off-screen.
 */
class ThumbnailCache(cacheDir: File) {

    private val dir = File(cacheDir, "thumbnails").apply { mkdirs() }

    /** The cache file for [pageId] (may not exist yet). */
    fun file(pageId: String): File = File(dir, "$pageId.webp")

    fun exists(pageId: String): Boolean = file(pageId).exists()

    /** Compresses [bitmap] to the page's WebP file (off-thread). Errors are swallowed. */
    suspend fun write(pageId: String, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            runCatching {
                file(pageId).outputStream().use { out ->
                    @Suppress("DEPRECATION") // WEBP_LOSSY/LOSSLESS need API 30; minSdk is 29.
                    bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, out)
                }
            }
        }
    }

    /** Decodes the page's cached WebP, or null when there is none / it can't be read (off-thread). */
    suspend fun read(pageId: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(pageId)
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null
    }

    /** Removes the cached file (e.g. when its note is deleted or cleared), if present. */
    fun delete(pageId: String) {
        file(pageId).delete()
    }

    companion object {
        const val WEBP_QUALITY = 70
    }
}

/**
 * Renders normalized (0..1, aspect-preserved) stroke [polylines] — the exact data the on-card
 * `StrokeThumbnail` draws — into a bitmap for [ThumbnailCache]. Software canvas only: `drawPath` is
 * supported on a software `Bitmap` canvas (unlike the ink renderer's hardware-only `drawMesh`), so
 * this can run fully off the main thread without a live View.
 */
object ThumbnailRenderer {

    fun render(
        polylines: List<List<Pair<Float, Float>>>,
        widthPx: Int = THUMBNAIL_WIDTH_PX,
        heightPx: Int = THUMBNAIL_HEIGHT_PX,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        // RGB_565 is opaque (the legacy WEBP format carries no alpha) — fill an opaque background.
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND_COLOR)
        if (polylines.isEmpty()) return bitmap

        // Mirror StrokeThumbnail's layout: a single scale preserves the handwriting aspect ratio.
        val box = minOf(w, h).toFloat()
        val scale = box * 0.9f
        val pad = box * 0.05f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = STROKE_WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = CanvasViewModel.USER_INK_COLOR
            alpha = INK_ALPHA
        }
        val path = Path()
        polylines.forEach { line ->
            if (line.size < 2) return@forEach
            path.reset()
            line.forEachIndexed { i, (x, y) ->
                val px = pad + x * scale
                val py = pad + y * scale
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    /** ~16:9-ish, matching the card's fillMaxWidth × 120dp thumbnail (ContentScale.Crop handles the rest). */
    const val THUMBNAIL_WIDTH_PX = 480
    const val THUMBNAIL_HEIGHT_PX = 264

    private const val BACKGROUND_COLOR = Color.WHITE
    private const val STROKE_WIDTH = 2.5f
    private const val INK_ALPHA = 217 // ~0.85 * 255, matching StrokeThumbnail
}
