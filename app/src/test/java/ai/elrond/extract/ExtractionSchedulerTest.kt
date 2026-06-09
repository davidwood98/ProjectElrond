package ai.elrond.extract

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Verifies FA-2 work scheduling: unique-per-page work with the right battery/network constraints. */
@RunWith(RobolectricTestRunner::class)
class ExtractionSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun `enqueue schedules unique work with not-required network and battery-not-low`() {
        ExtractionScheduler.enqueue(context, "page-1")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ExtractionWorker.uniqueName("page-1")).get()

        assertEquals(1, infos.size)
        val info = infos.first()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `re-enqueuing the same page coalesces into a single work`() {
        ExtractionScheduler.enqueue(context, "page-1")
        ExtractionScheduler.enqueue(context, "page-1")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ExtractionWorker.uniqueName("page-1")).get()

        assertEquals(1, infos.size)
    }
}
