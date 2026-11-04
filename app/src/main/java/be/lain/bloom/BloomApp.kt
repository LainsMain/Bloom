package be.lain.bloom

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import be.lain.bloom.di.AppScope
import be.lain.bloom.service.Reminders
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class BloomApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var reminders: Reminders
    @Inject lateinit var runRepair: be.lain.bloom.data.training.RunRepair
    @Inject @AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var dietBreaks: be.lain.bloom.data.review.DietBreaks

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // KEEP: never cancel a reminder the system just started this process to deliver.
        scope.launch { runCatching { reminders.rescheduleAll(androidx.work.ExistingWorkPolicy.KEEP) } }
        scope.launch { runCatching { dietBreaks.finishIfDue() } }
        scope.launch { runCatching { runRepair.runOnce() } }
    }
}
