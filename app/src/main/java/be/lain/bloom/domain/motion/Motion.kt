package be.lain.bloom.domain.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Ease { LINEAR, IN_OUT, OUT, IN;
    fun apply(t: Double): Double = when (this) {
        LINEAR -> t
        IN_OUT -> 0.5 - 0.5 * cos(PI * t)
        OUT -> sin(PI / 2 * t)
        IN -> 1 - cos(PI / 2 * t)
    }
}

/**
 * One phase of a rep: move to (or hold) pose [to] over [seconds]. [word] is spoken as the phase starts
 * ("down", "up", "squeeze"…); a [count] beat says the rep number instead. [mirror] uses the other-side
 * version of the pose (for alternating moves).
 */
data class Beat(
    val to: String,
    val seconds: Double,
    val word: String? = null,
    val count: Boolean = false,
    val mirror: Boolean = false,
    val ease: Ease = Ease.IN_OUT,
)

enum class MotionStyle {
    /** One cycle = one rep. Per-side exercises do every rep on the left, then mirror for the right. */
    REPS,
    /** The cycle already works both sides (one cycle = one rep each side). */
    ALTERNATING,
    /** A rhythmic timed movement (warm-ups): the cycle just loops. */
    LOOP,
    /** A held position (planks, stretches): the start pose with gentle breathing. */
    HOLD,
}

/** The rhythm of an exercise — independent of the figure, so the workout engine can use it alone. */
class Tempo(val style: MotionStyle, val beats: List<Beat>) {
    val cycleSeconds: Double = beats.sumOf { it.seconds }.let { if (it <= 0) 4.0 else it }
    val cycleMs: Long = (cycleSeconds * 1000).toLong()

    /**
     * Applies a program note's timing ("3 s down", "Squeeze 2 s at the top", "Pause 1 s at the
     * bottom", "1 s pause at the top") so the voice and the figure follow what the set asks for.
     */
    fun withNote(note: String): Tempo {
        if (note.isBlank() || beats.isEmpty()) return this
        val n = note.lowercase()
        val num = "(\\d+(?:\\.\\d+)?)"
        fun secs(re: String) = Regex(re).find(n)?.groupValues?.get(1)?.toDoubleOrNull()
        var out = beats
        secs("$num ?s(?:econds?)? down")?.let { d -> out = out.map { if (it.word == "down" || it.word == "lower") it.copy(seconds = d) else it } }
        (secs("squeeze $num ?s") ?: secs("$num ?s squeeze"))?.let { d -> out = out.map { if (it.word == "squeeze") it.copy(seconds = d) else it } }
        val bottomPause = secs("pause $num ?s at the bottom") ?: if (n.contains("down")) secs(", $num ?s pause") else null
        bottomPause?.let { d ->
            val i = out.indexOfFirst { it.word == "down" && it.to == "bottom" }
            if (i >= 0) {
                val list = out.toMutableList()
                if (list.getOrNull(i + 1)?.to == "bottom") list[i + 1] = list[i + 1].copy(seconds = d, word = list[i + 1].word ?: "hold")
                else list.add(i + 1, Beat("bottom", d, "hold"))
                out = list
            }
        }
        secs("$num ?s pause at the top")?.let { d ->
            val i = out.indices.firstOrNull { k -> out[k].to == "top" && k > 0 && out[k - 1].to == "top" }
            out = if (i != null) out.toMutableList().also { it[i] = it[i].copy(seconds = d) }
            else out.map { if (it.count) it.copy(seconds = maxOf(it.seconds, d)) else it }
        }
        return if (out === beats) this else Tempo(style, out)
    }
}

/** "down 1.8 s · up 1 s · squeeze 1 s" (null for holds and warm-up loops). */
fun Tempo.label(): String? {
    if (style == MotionStyle.HOLD || style == MotionStyle.LOOP) return null
    val seen = LinkedHashMap<String, Double>()
    beats.forEach { b -> b.word?.let { w -> if (w !in seen && w.length > 1) seen[w] = b.seconds } }
    if (seen.isEmpty()) return null
    return seen.entries.joinToString(" · ") { (w, s) -> "$w ${if (s % 1.0 == 0.0) s.toInt().toString() else String.format(java.util.Locale.UK, "%.1f", s)} s" }
}

/** Where a guided set or demo is at a moment: which part of the cycle, which side, or mid side-switch. */
data class GuideFrame(
    /** Seconds into the rep cycle. */
    val t: Double,
    val mirrored: Boolean = false,
    /** 0..1 while switching sides (blend from left start pose to right start pose). */
    val switching: Double? = null,
    /** Seconds of breathing to add (holds, countdowns). */
    val breathe: Double? = null,
    /** The set's own timing when a note changed it (else the motion's). */
    val tempo: Tempo? = null,
)

enum class PropKind { BOX, MAT, TOWEL }

/** A prop placed in the scene; boxes are axis-aligned after [yaw] (degrees). */
data class Prop(val kind: PropKind, val center: Vec3, val size: Vec3, val yaw: Double = 0.0) {
    fun mirrored() = copy(center = center.mirrored(), yaw = -yaw)
}

/** Camera direction around the figure: yaw 0 looks at the figure's front, 90 at its left side. */
data class CameraSpec(val yaw: Double, val pitch: Double = 10.0)

class Motion(
    val id: String,
    val tempo: Tempo,
    /** Keyframes; "start" is where every cycle begins and ends. */
    val poses: Map<String, Pose>,
    val camera: CameraSpec,
    val props: List<Prop> = emptyList(),
) {
    val start: Pose get() = poses.getValue("start")

    private fun key(b: Beat?): Pose = if (b == null) start else poses.getValue(b.to).let { if (b.mirror) it.mirrored() else it }

    /** Pose at [t] seconds into one cycle. */
    fun cyclePose(t: Double, timing: Tempo = tempo): Pose {
        val beats = timing.beats
        if (beats.isEmpty()) return start
        val c = timing.cycleSeconds
        var u = ((t % c) + c) % c
        var prev: Beat? = null
        for (b in beats) {
            if (u <= b.seconds || b === beats.last()) {
                val f = if (b.seconds <= 0) 1.0 else (u / b.seconds).coerceIn(0.0, 1.0)
                return key(prev).lerp(key(b), b.ease.apply(f))
            }
            u -= b.seconds; prev = b
        }
        return start
    }

    fun pose(f: GuideFrame): Pose {
        var p = when {
            f.switching != null -> start.lerp(start.mirrored(), Ease.IN_OUT.apply(f.switching))
            tempo.style == MotionStyle.HOLD -> start
            else -> cyclePose(f.t, f.tempo ?: tempo)
        }
        if (f.switching == null && f.mirrored) p = p.mirrored()
        val br = f.breathe ?: if (tempo.style == MotionStyle.HOLD) f.t else null
        return if (br != null) breathe(p, br) else p
    }

    /** Free-running demo: loops the cycle (or holds and breathes). */
    fun loopFrame(seconds: Double, mirrored: Boolean = false) = GuideFrame(seconds, mirrored)

    fun props(mirrored: Boolean) = if (mirrored) props.map { it.mirrored() } else props

    companion object {
        /** Slow breathing: a little chest rise and shoulder lift every 4 s. */
        fun breathe(p: Pose, t: Double, amount: Double = 1.0): Pose {
            val s = sin(2 * PI * t / 4.0) * amount
            return p.copy(
                spineFlex = p.spineFlex - 1.2 * s,
                armL = p.armL.copy(clavElev = p.armL.clavElev + 1.5 * s),
                armR = p.armR.copy(clavElev = p.armR.clavElev + 1.5 * s),
            )
        }
    }
}

/** Something to say at a moment in a guided set. */
sealed interface GuideCue {
    data class Word(val text: String) : GuideCue
    data class Rep(val n: Int, val of: Int) : GuideCue
    data object SwitchSides : GuideCue
    data class Count(val n: Int) : GuideCue
}

data class TimedCue(val atMs: Long, val cue: GuideCue)

/**
 * A guided set of [reps] reps at the exercise's tempo. Per-side REPS exercises do the left side, a
 * switch-sides break with a 3-2-1, then the right side. Everything is a pure function of time, so
 * the voice and the 3-D figure stay in sync.
 */
class RepGuide(val tempo: Tempo, val reps: Int, perSide: Boolean) {
    val sides: Int = if (perSide && tempo.style == MotionStyle.REPS) 2 else 1
    private val cycleMs = tempo.cycleMs
    private val sideMs = cycleMs * reps
    val totalMs: Long = sideMs * sides + if (sides == 2) SWITCH_MS else 0
    val cues: List<TimedCue>

    init {
        val out = mutableListOf<TimedCue>()
        for (s in 0 until sides) {
            val base = s * (sideMs + SWITCH_MS)
            for (r in 0 until reps) {
                var t = base + r * cycleMs
                for (b in tempo.beats) {
                    when {
                        b.count -> out += TimedCue(t, GuideCue.Rep(r + 1, reps))
                        b.word != null -> out += TimedCue(t, GuideCue.Word(b.word))
                    }
                    t += (b.seconds * 1000).toLong()
                }
            }
            if (s == 0 && sides == 2) {
                val sw = base + sideMs
                out += TimedCue(sw + 300, GuideCue.SwitchSides)
                for (k in 3 downTo 1) out += TimedCue(sw + SWITCH_MS - k * 1000L, GuideCue.Count(k))
            }
        }
        cues = out.sortedBy { it.atMs }
    }

    private fun sideAt(ms: Long): Pair<Int, Long> =
        if (sides == 2 && ms >= sideMs + SWITCH_MS) 1 to (ms - sideMs - SWITCH_MS).coerceIn(0, sideMs)
        else 0 to ms.coerceAtLeast(0)

    /** Reps finished by [ms] on the current side. */
    fun repsDone(ms: Long): Int {
        if (ms >= totalMs) return reps
        val (_, local) = sideAt(ms)
        return (local / cycleMs).toInt().coerceIn(0, reps)
    }

    /** 0 = left (or only) side, 1 = right. */
    fun sideIndex(ms: Long): Int = sideAt(ms).first

    fun switchingAt(ms: Long): Boolean = sides == 2 && ms in sideMs until sideMs + SWITCH_MS

    /** Start of the rep in progress at [ms] (resuming after a pause restarts that rep). */
    fun repStartMs(ms: Long): Long {
        if (ms >= totalMs) return totalMs
        val (s, local) = sideAt(ms)
        val base = s * (sideMs + SWITCH_MS)
        if (s == 0 && local >= sideMs) return sideMs + SWITCH_MS // during the switch: start the right side
        return base + (local / cycleMs) * cycleMs
    }

    fun frame(ms: Long): GuideFrame {
        if (sides == 2) {
            if (ms in sideMs until sideMs + SWITCH_MS) {
                val f = ((ms - sideMs) / 1800.0).coerceIn(0.0, 1.0)
                return GuideFrame(0.0, switching = f, breathe = (ms - sideMs) / 1000.0, tempo = tempo)
            }
            if (ms >= sideMs + SWITCH_MS) return GuideFrame(((ms - sideMs - SWITCH_MS).coerceIn(0, sideMs - 1)) / 1000.0, mirrored = true, tempo = tempo)
        }
        return GuideFrame(ms.coerceIn(0, (sideMs - 1).coerceAtLeast(0)) / 1000.0, tempo = tempo)
    }

    companion object {
        const val SWITCH_MS = 5_000L
    }
}
