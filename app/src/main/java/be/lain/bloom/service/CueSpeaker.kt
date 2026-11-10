package be.lain.bloom.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spoken cues (ducking other audio while speaking) and haptic pulses for workouts and runs.
 *
 * Sentences go through text-to-speech. The short words of a guided set ("down", "up", rep numbers…)
 * are recorded once with the same voice and played from memory, so they land exactly on the beat.
 */
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
    private val main = Handler(Looper.getMainLooper())
    private var clipFocusUntil = 0L
    private val releaseClipFocus = Runnable { if (tts?.isSpeaking != true) audio.abandonAudioFocusRequest(focus) }
    var enabled = true

    private var pool: SoundPool? = null
    private val clips = ConcurrentHashMap<String, Int>()
    private val loading = ConcurrentHashMap<Int, String>()
    private var clipDir: File? = null

    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.UK
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        if (id != null && id.startsWith(CLIP)) {
                            val w = id.removePrefix(CLIP)
                            clipDir?.let { d -> runCatching { trimSilence(File(d, "${w.replace(' ', '_')}.wav")) } }
                            main.post { loadClip(w) }
                        }
                        else if (tts?.isSpeaking != true && System.currentTimeMillis() > clipFocusUntil) audio.abandonAudioFocusRequest(focus)
                    }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                        if (id == null || !id.startsWith(CLIP)) audio.abandonAudioFocusRequest(focus)
                    }
                })
                while (pending.isNotEmpty()) speak(pending.removeFirst())
                prepareClips()
            }
        }
    }

    /** Interrupts anything being said and says [text]. */
    fun speak(text: String, flush: Boolean = true) {
        if (!enabled || text.isBlank()) return
        val t = tts
        if (t == null || !ready) { warmUp(); pending.addLast(text); return }
        audio.requestAudioFocus(focus)
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    /** Says one short guided-set word right now (from the recording when it's ready). */
    fun word(text: String) {
        if (!enabled) return
        val id = clips[text.lowercase()]
        val p = pool
        if (id == null || p == null) { speak(text); return }
        audio.requestAudioFocus(focus)
        clipFocusUntil = System.currentTimeMillis() + 1500
        main.removeCallbacks(releaseClipFocus)
        main.postDelayed(releaseClipFocus, 1500)
        p.play(id, 1f, 1f, 1, 0, 1f)
    }

    private fun prepareClips() {
        val t = tts ?: return
        if (pool == null) {
            pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attributes).build().apply {
                setOnLoadCompleteListener { _, sample, status -> loading.remove(sample)?.let { if (status == 0) clips[it] = sample } }
            }
        }
        val voice = runCatching { t.voice?.name }.getOrNull() ?: t.defaultEngine ?: "default"
        val dir = File(context.cacheDir, "voice2/" + voice.replace(Regex("[^A-Za-z0-9_.-]"), "_")).apply { mkdirs() }
        clipDir = dir
        for (w in CLIP_WORDS) {
            val f = File(dir, "${w.replace(' ', '_')}.wav")
            if (f.isFile && f.length() > 1000) loadClip(w)
            else t.synthesizeToFile(w, Bundle(), f, CLIP + w)
        }
    }

    private fun loadClip(word: String) {
        val dir = clipDir ?: return
        val p = pool ?: return
        if (clips.containsKey(word)) return
        val f = File(dir, "${word.replace(' ', '_')}.wav")
        if (!f.isFile) return
        loading[p.load(f.path, 1)] = word
    }

    /** Drops the silence TTS puts before a word, so it starts right on the beat. */
    private fun trimSilence(f: File) {
        val b = f.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF") return
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt(); val rate = bb.getInt(24); val bits = bb.getShort(34).toInt()
        if (bits != 16) return
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val len = bb.getInt(p + 4)
            if (id == "data") {
                val start = p + 8; val end = minOf(b.size, start + len)
                val frame = 2 * channels
                var first = start
                while (first + 1 < end && kotlin.math.abs(bb.getShort(first).toInt()) < 600) first += frame
                first = maxOf(start, first - rate / 100 * frame) // keep 10 ms before the word
                if (first <= start + frame) return
                val out = java.io.ByteArrayOutputStream()
                val head = b.copyOfRange(0, start)
                val newLen = end - first
                java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { putInt(4, head.size - 8 + newLen); putInt(p + 4, newLen) }
                out.write(head); out.write(b, first, newLen)
                f.writeBytes(out.toByteArray())
                return
            }
            p += 8 + len + (len and 1)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        pool?.autoPause()
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
        pool?.release(); pool = null; clips.clear(); loading.clear()
    }

    companion object {
        private const val CLIP = "clip:"
        /** Every word a guided set can say (see Tempos), plus counts. */
        val CLIP_WORDS: List<String> = listOf(
            "down", "up", "hold", "squeeze", "open", "close", "out", "in", "pull", "lower", "reach", "tap",
            "left", "right", "y", "t", "w", "go", "switch sides", "halfway", "forward", "cat", "cow", "switch direction",
        ) + (1..40).map { "$it" }
    }
}

enum class Buzz { TICK, START, DONE }
