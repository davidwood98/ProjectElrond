package ai.elrond.canvas

import ai.elrond.data.ThumbnailCache
import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip + lifecycle for [ThumbnailCache]. Robolectric supplies the Android `Bitmap` /
 * `BitmapFactory` shadows; the file layout uses a real temp dir. (The rendered ink fidelity is
 * device-verified — see the instrumented suite — since a software canvas can't host the ink
 * renderer's meshes.)
 */
@RunWith(RobolectricTestRunner::class)
class ThumbnailCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = ThumbnailCache(tmp.root)

    @Test
    fun `read returns null and exists is false when nothing is cached`() = runTest {
        val cache = cache()
        assertFalse(cache.exists("missing"))
        assertNull(cache.read("missing"))
    }

    @Test
    fun `write then read round-trips a non-empty thumbnail file`() = runTest {
        val cache = cache()
        cache.write("p1", Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565))

        assertTrue(cache.exists("p1"))
        assertTrue(cache.file("p1").length() > 0)
        assertNotNull(cache.read("p1"))
    }

    @Test
    fun `delete clears the cached file`() = runTest {
        val cache = cache()
        cache.write("p1", Bitmap.createBitmap(4, 4, Bitmap.Config.RGB_565))
        assertTrue(cache.exists("p1"))

        cache.delete("p1")

        assertFalse(cache.exists("p1"))
        assertNull(cache.read("p1"))
    }
}
