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
    @Inject @AppScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Keeps reminder times right after updates, time changes or settings edits made while closed.
        scope.launch { runCatching { reminders.rescheduleAll() } }
    }
}
