package be.lain.bloom.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Spoken cues (ducking other audio while speaking) and haptic pulses for workouts and runs. */
@Singleton
class CueSpeaker @Inject constructor(@ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
    var enabled = true

    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.UK
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { if (tts?.isSpeaking != true) audio.abandonAudioFocusRequest(focus) }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
                })
                while (pending.isNotEmpty()) speak(pending.removeFirst())
            }
        }
    }

    /** Interrupts anything being said and says [text]. */
    fun speak(text: String, flush: Boolean = true) {
        if (!enabled) return
        val t = tts
        if (t == null || !ready) { warmUp(); pending.addLast(text); return }
        audio.requestAudioFocus(focus)
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun stopSpeaking() {
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    fun buzz(kind: Buzz) {
        val v = vibrator ?: return
        val effect = when (kind) {
            Buzz.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Buzz.START -> VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)
            Buzz.DONE -> VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300, 120, 500), -1)
        }
        runCatching { v.vibrate(effect) }
    }

    fun shutdown() {
        tts?.shutdown(); tts = null; ready = false
    }
}

enum class Buzz { TICK, START, DONE }
