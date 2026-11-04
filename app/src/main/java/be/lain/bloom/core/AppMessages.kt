package be.lain.bloom.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (suspend () -> Unit)? = null,
)

/** App-wide snackbar messages (shown by the main shell), so screens can confirm after navigating away. */
@Singleton
class AppMessages @Inject constructor() {
    private val _flow = MutableSharedFlow<AppMessage>(extraBufferCapacity = 8)
    val flow: SharedFlow<AppMessage> = _flow.asSharedFlow()

    fun show(text: String, actionLabel: String? = null, onAction: (suspend () -> Unit)? = null) {
        _flow.tryEmit(AppMessage(text, actionLabel, onAction))
    }
}
