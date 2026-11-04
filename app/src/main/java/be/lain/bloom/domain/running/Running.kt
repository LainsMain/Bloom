package be.lain.bloom.domain.running

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SegKind(val label: String, val spoken: String) {
    WARMUP("Warm-up walk", "Warm-up. Brisk walk"),
    RUN("Run", "Run"),
    WALK("Walk", "Walk"),
    COOLDOWN("Cool-down walk", "Cool-down. Easy walk"),
}

/** One interval: by time ([seconds]) or by distance ([meters]). */
@Serializable
data class Segment(val kind: SegKind, val seconds: Int? = null, val meters: Int? = null) {
    fun describe(): String = "${kind.label} ${seconds?.let { formatDuration(it) } ?: meters?.let { formatMeters(it) } ?: ""}".trim()
}

/** A plan step: a single segment, or a group repeated [times]. */
@Serializable
data class PlanBlock(val segment: Segment? = null, val times: Int = 1, val group: List<Segment> = emptyList()) {
    fun flatten(): List<Segment> = if (segment != null) listOf(segment) else (1..times).flatMap { group }
}

@Serializable
data class RunPlan(val name: String, val blocks: List<PlanBlock>) {
    fun segments(): List<Segment> = blocks.flatMap { it.flatten() }
    val totalSeconds: Int get() = segments().sumOf { it.seconds ?: 0 }
    fun summary(): String = blocks.joinToString(" · ") { b ->
        if (b.segment != null) b.segment.describe() else "${b.times} × (${b.group.joinToString(" / ") { it.describe() }})"
    }
}

fun formatDuration(sec: Int): String = when {
    sec < 60 -> "${sec}s"
    sec % 60 == 0 -> "${sec / 60} min"
    else -> "${sec / 60}:${"%02d".format(sec % 60)}"
}

fun formatMeters(m: Int): String = if (m >= 1000) "${"%.1f".format(m / 1000.0).removeSuffix(".0")} km" else "$m m"

/** "Run-walk to 30 minutes": a gentle 9-week beginner progression, 3 runs per week. */
object RunProgram {
    const val NAME = "Run-walk to 30 minutes"
    private val warm = PlanBlock(Segment(SegKind.WARMUP, seconds = 300))
    private val cool = PlanBlock(Segment(SegKind.COOLDOWN, seconds = 300))
    private fun rep(times: Int, vararg s: Segment) = PlanBlock(times = times, group = s.toList())
    private fun run(sec: Int) = Segment(SegKind.RUN, seconds = sec)
    private fun walk(sec: Int) = Segment(SegKind.WALK, seconds = sec)
    private fun one(s: Segment) = PlanBlock(s)

    private fun weekDays(week: Int, vararg days: List<PlanBlock>): List<RunPlan> =
        days.mapIndexed { i, b -> RunPlan("Week $week · Run ${i + 1}", listOf(warm) + b + cool) }

    val runs: List<RunPlan> = buildList {
        val w1 = listOf(rep(8, run(60), walk(90)))
        addAll(weekDays(1, w1, w1, w1))
        val w2 = listOf(rep(6, run(90), walk(120)))
        addAll(weekDays(2, w2, w2, w2))
        val w3 = listOf(rep(2, run(90), walk(90), run(180), walk(180)))
        addAll(weekDays(3, w3, w3, w3))
        val w4 = listOf(one(run(180)), one(walk(90)), one(run(300)), one(walk(150)), one(run(180)), one(walk(90)), one(run(300)))
        addAll(weekDays(4, w4, w4, w4))
        addAll(weekDays(5,
            listOf(rep(3, run(300), walk(180))),
            listOf(one(run(480)), one(walk(300)), one(run(480))),
            listOf(one(run(900))),
        ))
        addAll(weekDays(6,
            listOf(one(run(300)), one(walk(180)), one(run(480)), one(walk(180)), one(run(300))),
            listOf(one(run(600)), one(walk(180)), one(run(600))),
            listOf(one(run(1320))),
        ))
        val w7 = listOf(one(run(1500)))
        addAll(weekDays(7, w7, w7, w7))
        val w8 = listOf(one(run(1680)))
        addAll(weekDays(8, w8, w8, w8))
        val w9 = listOf(one(run(1800)))
        addAll(weekDays(9, w9, w9, w9))
    }

    fun at(index: Int): RunPlan = runs[index.coerceIn(0, runs.lastIndex)]
}

data class GeoPoint(val t: Long, val lat: Double, val lon: Double, val alt: Double?, val accuracy: Float, val segment: Int = 0)

object Geo {
    private const val R = 6_371_000.0

    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distance(a: GeoPoint, b: GeoPoint) = distance(a.lat, a.lon, b.lat, b.lon)

    /**
     * Accepts a fix if it's accurate enough and doesn't imply an impossible speed.
     * Returns the distance to add (0 for jitter while standing still) or null to reject.
     */
    fun accept(prev: GeoPoint?, p: GeoPoint, maxAccuracy: Float = 25f, maxSpeed: Double = 7.0): Double? {
        if (p.accuracy > maxAccuracy) return null
        if (prev == null) return 0.0
        val d = distance(prev, p)
        val dt = (p.t - prev.t) / 1000.0
        if (dt <= 0) return null
        if (d / dt > maxSpeed) return null
        // Ignore tiny moves inside the combined accuracy circle (GPS jitter).
        return if (d < max(2.0, (prev.accuracy + p.accuracy) / 4.0)) 0.0 else d
    }

    /** Total ascent with a 3 m hysteresis to ignore barometric/GPS altitude noise. */
    fun elevationGain(alts: List<Double>, threshold: Double = 3.0): Double {
        if (alts.isEmpty()) return 0.0
        var gain = 0.0
        var ref = alts.first()
        for (a in alts) {
            if (a - ref >= threshold) { gain += a - ref; ref = a }
            else if (ref - a >= threshold) ref = a
        }
        return gain
    }

    /** Seconds per km for distance [m] in [sec]; null if not meaningful. */
    fun pace(sec: Double, m: Double): Double? = if (m < 20 || sec <= 0) null else sec / (m / 1000.0)

    fun formatPace(secPerKm: Double?): String = secPerKm?.let {
        val s = it.roundToInt().coerceAtMost(59 * 60)
        "${s / 60}:${"%02d".format(s % 60)}"
    } ?: "–:––"

    fun formatClock(sec: Int): String = if (sec >= 3600) "${sec / 3600}:${"%02d".format(sec % 3600 / 60)}:${"%02d".format(sec % 60)}"
    else "${sec / 60}:${"%02d".format(sec % 60)}"

    /**
     * Energy above rest: running ≈ 1.0 kcal/kg/km, brisk walking ≈ 0.6 kcal/kg/km.
     * Faster than 9 min/km counts as running.
     */
    fun kcal(weightKg: Double, km: Double, secPerKm: Double?): Int {
        val factor = if (secPerKm != null && secPerKm < 540) 1.0 else 0.6
        return (weightKg * km * factor).roundToInt()
    }
}

data class Split(val km: Int, val seconds: Int, val partial: Double = 1.0)

object Splits {
    /** Per-kilometre split times from (activeSeconds, cumulativeMeters) samples. */
    fun compute(samples: List<Pair<Double, Double>>): List<Split> {
        if (samples.size < 2) return emptyList()
        val out = mutableListOf<Split>()
        var nextKm = 1000.0
        var lastT = 0.0
        for (i in 1 until samples.size) {
            val (t0, d0) = samples[i - 1]
            val (t1, d1) = samples[i]
            while (d1 >= nextKm && d1 > d0) {
                val tAt = t0 + (t1 - t0) * (nextKm - d0) / (d1 - d0)
                out += Split((nextKm / 1000).toInt(), (tAt - lastT).roundToInt())
                lastT = tAt
                nextKm += 1000.0
            }
        }
        val (tEnd, dEnd) = samples.last()
        val rem = dEnd - (nextKm - 1000.0)
        if (rem >= 100) out += Split(out.size + 1, (tEnd - lastT).roundToInt(), rem / 1000.0)
        return out
    }
}

sealed interface RunCue {
    data class Segment(val index: Int, val segment: be.lain.bloom.domain.running.Segment, val next: be.lain.bloom.domain.running.Segment?) : RunCue
    data object Halfway : RunCue
    data class Count(val n: Int) : RunCue
    data object PlanDone : RunCue
}

/** Tracks progress through a run plan's segments using active time and distance. Pure. */
class IntervalTracker(val segments: List<Segment>) {
    var index = 0; private set
    private var startMs = 0L
    private var startM = 0.0
    private var halfway = false
    private val counted = mutableSetOf<Int>()
    var done = segments.isEmpty(); private set

    val current: Segment? get() = segments.getOrNull(index)

    fun begin(): List<RunCue> = current?.let { listOf(RunCue.Segment(0, it, segments.getOrNull(1))) } ?: emptyList()

    /** Remaining seconds (time segment) or meters (distance segment). */
    fun remaining(activeMs: Long, meters: Double): Double? {
        val s = current ?: return null
        return when {
            s.seconds != null -> (s.seconds - (activeMs - startMs) / 1000.0).coerceAtLeast(0.0)
            s.meters != null -> (s.meters - (meters - startM)).coerceAtLeast(0.0)
            else -> null
        }
    }

    fun update(activeMs: Long, meters: Double): List<RunCue> {
        if (done) return emptyList()
        val cues = mutableListOf<RunCue>()
        while (!done) {
            val s = current ?: break
            val finished = when {
                s.seconds != null -> activeMs - startMs >= s.seconds * 1000L
                s.meters != null -> meters - startM >= s.meters
                else -> true
            }
            if (!finished) break
            // Carry over exact boundaries so long runs don't drift.
            startMs += (s.seconds?.times(1000L) ?: (activeMs - startMs))
            startM = if (s.meters != null) startM + s.meters else meters
            index++
            halfway = false; counted.clear()
            if (index >= segments.size) { done = true; cues += RunCue.PlanDone }
            else cues += RunCue.Segment(index, segments[index], segments.getOrNull(index + 1))
        }
        val s = current
        if (!done && s?.seconds != null) {
            val left = s.seconds - ((activeMs - startMs) / 1000.0)
            if (s.seconds >= 240 && !halfway && left <= s.seconds / 2.0) { halfway = true; cues += RunCue.Halfway }
            val n = kotlin.math.ceil(left).toInt()
            if (s.seconds > 5 && n in 1..3 && n !in counted) { counted += n; cues += RunCue.Count(n) }
        }
        return cues
    }
}
