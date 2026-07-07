package ai.elrond.ui

import android.content.res.Resources
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.StockTextureBitmapStore
import androidx.ink.brush.TextureBitmapStore

/**
 * One shared texture store for every ink renderer (FA-23). The textured pencil family renders as
 * plain ink unless the renderer — and the wet-ink [androidx.ink.authoring.InProgressStrokesView] —
 * can resolve its texture id to a bitmap, so all `CanvasStrokeRenderer.create` call sites and the
 * wet view take this store. Bitmaps are cached inside the stock store; the pencil texture is
 * preloaded once so the first pencil stroke doesn't decode on the UI thread.
 */
object InkTextures {

    @Volatile
    private var cached: TextureBitmapStore? = null

    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun store(resources: Resources): TextureBitmapStore =
        cached ?: synchronized(this) {
            cached ?: StockTextureBitmapStore(resources)
                .also { it.preloadStockBrushesTextures(StockBrushes.pencilUnstable) }
                .also { cached = it }
        }
}
