package be.lain.bloom

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.lain.bloom.core.designsystem.BloomTheme
import be.lain.bloom.core.designsystem.isDark
import be.lain.bloom.data.AppSettings
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.ui.BloomRoot
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
/** A FragmentActivity (still a ComponentActivity) because the biometric prompt needs one. */
class MainActivity : androidx.fragment.app.FragmentActivity() {
    @Inject lateinit var appLock: be.lain.bloom.core.AppLock
    @Inject lateinit var updater: be.lain.bloom.data.update.Updater
    private var lockEnabled: Boolean? = null
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var healthSync: be.lain.bloom.data.health.HealthSync
    @Inject lateinit var deepLinks: be.lain.bloom.service.DeepLinks
    @Inject lateinit var activeDay: be.lain.bloom.core.ActiveDay
    private var lastSync = 0L

    private fun handle(intent: android.content.Intent?) {
        intent?.getStringExtra(be.lain.bloom.service.DeepLinks.EXTRA)?.let {
            deepLinks.pending.value = it
            intent.removeExtra(be.lain.bloom.service.DeepLinks.EXTRA)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Read synchronously once so private data never flashes before the lock appears.
        val enabled = lockEnabled ?: kotlinx.coroutines.runBlocking { settingsRepository.settings.first().appLock }.also { lockEnabled = it }
        appLock.onForeground(enabled)
        activeDay.refresh()
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!enabled)
    }

    override fun onStop() {
        super.onStop()
        appLock.onBackground()
        activeDay.markSeen()
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastSync > 10 * 60_000) { lastSync = now; healthSync.syncSoon() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        lifecycleScope.launch { settingsRepository.settings.collect { lockEnabled = it.appLock } }
        if (savedInstanceState == null) updater.checkOnLaunch()
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            val dark = isDark(settings)
            // Keep status/navigation bar icons readable when the app theme differs from the system.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            BloomTheme(settings) {
                val locked by appLock.locked.collectAsStateWithLifecycle()
                androidx.compose.foundation.layout.Box {
                    BloomRoot()
                    if (locked) be.lain.bloom.ui.LockScreen(onUnlock = { appLock.unlock(this@MainActivity) })
                }
            }
        }
    }
}
