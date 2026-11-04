package be.lain.bloom.core

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Optional lock with the phone's fingerprint, face or screen lock. Re-locks after a short time away. */
@Singleton
class AppLock @Inject constructor(@ApplicationContext private val context: Context) {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var leftAt: Long? = null

    fun available(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /** Called when the app comes to the foreground. */
    fun onForeground(enabled: Boolean) {
        if (!enabled) { _locked.value = false; return }
        val away = leftAt?.let { SystemClock.elapsedRealtime() - it }
        if (away == null || away > GRACE_MS) _locked.value = true
    }

    fun onBackground() { leftAt = SystemClock.elapsedRealtime() }

    fun unlock(activity: FragmentActivity) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                _locked.value = false
                leftAt = SystemClock.elapsedRealtime()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Bloom")
                .setSubtitle("Your food, weight and progress are private")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    companion object {
        /** Switching apps briefly (e.g. to copy something) doesn't re-lock. */
        private const val GRACE_MS = 60_000L
    }
}
