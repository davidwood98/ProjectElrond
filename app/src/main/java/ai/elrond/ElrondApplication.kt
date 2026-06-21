package ai.elrond

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt application (FA-3). The object graph lives in Hilt modules (see [ai.elrond.di.AppModule]),
 * replacing the manual lazy container. Also supplies the WorkManager [Configuration] with the
 * Hilt-aware worker factory so `@HiltWorker`s (e.g. [ai.elrond.data.ExtractionWorker]) get
 * their dependencies injected.
 */
@HiltAndroidApp
class ElrondApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
