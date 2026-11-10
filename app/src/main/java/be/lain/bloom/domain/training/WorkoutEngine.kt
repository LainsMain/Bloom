package be.lain.bloom.domain.training

import be.lain.bloom.domain.motion.GuideCue
import be.lain.bloom.domain.motion.GuideFrame
import be.lain.bloom.domain.motion.MotionStyle
import be.lain.bloom.domain.motion.RepGuide
import be.lain.bloom.domain.motion.Tempos
import kotlin.math.ceil

enum class StepType { WORK, REST, GET_READY }
enum class Effort(val label: String) { EASY("Easy"), GOOD("Good"), HARD("Hard") }

data class Step(
    val type: StepType,
    /** Exercise being done (WORK) or coming up next (REST / GET_READY). */
    val exerciseId: String,
    val slotKey: String,
    val block: BlockKind,
    val setIndex: Int,
    val setCount: Int,
    val side: String? = null,
    /** Countdown for timed work, rests and get-ready; 0 for rep-based work. */
    val seconds: Int,
    val repsMin: Int = 0,
    val repsMax: Int = 0,
    val note: String = "",
) {
    val timed: Boolean get() = seconds > 0
    val isRepWork: Boolean get() = type == StepType.WORK && !timed
}

data class SetResult(
    val stepIndex: Int,
    val exerciseId: String,
    val slotKey: String,
    val setIndex: Int,
    val side: String?,
    val reps: Int?,
    val seconds: Int?,
    val effort: Effort?,
    val skipped: Boolean,
)

object WorkoutPlan {
    /** Flattens a session into timed/rep steps. [choice] returns the variation chosen for a slot. */
    fun steps(
        session: Session,
        phase: Int,
        program: TrainingProgram = Program.program,
        choice: (slotKey: String, default: String) -> String = { _, d -> d },
    ): List<Step> {
        val out = mutableListOf<Step>()
        session.blocks.forEachIndexed { b, block ->
            block.items.forEachIndexed { i, p ->
                val slot = program.slotKey(phase, session.id, b, i)
                val exId = choice(slot, p.exerciseId)
                val ex = Exercises[exId]
                val circuit = block.kind == BlockKind.WARMUP || block.kind == BlockKind.COOLDOWN
                // Short get-ready before every new exercise (rests already show what's next in the main block).
                if (out.isEmpty() || circuit || out.last().type != StepType.REST) {
                    out += Step(StepType.GET_READY, exId, slot, block.kind, 1, p.sets, seconds = if (out.isEmpty()) 10 else 5)
                }
                for (s in 1..p.sets) {
                    val sides = if (ex.perSide && p.isTimed) listOf("Left", "Right") else listOf(if (ex.perSide) "each side" else null)
                    sides.forEach { side ->
                        out += Step(StepType.WORK, exId, slot, block.kind, s, p.sets, side, seconds = p.seconds,
                            repsMin = p.repsMin, repsMax = p.repsMax, note = p.note)
                    }
                    val lastOfBlock = i == block.items.lastIndex && s == p.sets
                    if (!circuit && p.restSec > 0 && !lastOfBlock) {
                        val next = if (s < p.sets) exId else choice(program.slotKey(phase, session.id, b, i + 1), block.items[i + 1].exerciseId)
                        out += Step(StepType.REST, next, slot, block.kind, s, p.sets, seconds = p.restSec)
                    }
                }
            }
        }
        return out
    }
}

/** How a work step begins: straight away after a counted-down rest, or with its own lead-in. */
enum class LeadIn {
    NONE,
    /** Entered by a tap (skip rest, start now, back): say what's next, then 3-2-1. */
    INTRO,
    /** Same exercise, other side (timed holds): "Switch sides", then 3-2-1. */
    SWITCH,
    /** Back from a pause mid-set: 3-2-1, then the interrupted rep again. */
    RESUME,
}

data class PlayerState(
    val steps: List<Step>,
    val index: Int = 0,
    val paused: Boolean = false,
    /** Monotonic ms when the current timed step ends. */
    val deadlineMs: Long = 0,
    val remainingMs: Long = 0,
    val startedAtMs: Long = 0,
    val pausedTotalMs: Long = 0,
    val pausedAtMs: Long = 0,
    val results: Map<Int, SetResult> = emptyMap(),
    val finished: Boolean = false,
    val countdownSpoken: Set<Int> = emptySet(),
    val leadIn: LeadIn = LeadIn.NONE,
    /** Monotonic ms when the lead-in ends and the work starts (0 = no lead-in running). */
    val leadInEndMs: Long = 0,
    /** Monotonic ms at which a guided set's timeline is at 0. */
    val guideStartMs: Long = 0,
    /** Index of the next guided cue to say. */
    val guideCue: Int = 0,
    /** The "next up" announcement near the end of a rest has been made. */
    val announced: Boolean = false,
) {
    val current: Step get() = steps[index]
    val next: Step? get() = steps.getOrNull(index + 1)
    val progress: Float get() = if (steps.isEmpty()) 1f else index.toFloat() / steps.size
    /** The work step a rest/get-ready belongs to (for effort rating). */
    val previousWorkIndex: Int? get() = (index - 1 downTo 0).firstOrNull { steps[it].type == StepType.WORK }
    val inLeadIn: Boolean get() = leadInEndMs > 0
    /** The time the screen should treat as "now" (frozen while paused). */
    fun clock(nowMs: Long): Long = if (paused) pausedAtMs else nowMs
}

/** Guided sets for rep work: every rep exercise with a tempo gets one (cached). */
object Guides {
    private data class Key(val exercise: String, val reps: Int, val perSide: Boolean, val note: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<Key, RepGuide>()

    fun forStep(step: Step): RepGuide? {
        if (!step.isRepWork) return null
        val tempo = Tempos[step.exerciseId]
        if (tempo.style != MotionStyle.REPS && tempo.style != MotionStyle.ALTERNATING) return null
        val key = Key(step.exerciseId, step.repsMax.coerceAtLeast(1), step.side == "each side", step.note)
        return cache.getOrPut(key) { RepGuide(tempo.withNote(step.note), key.reps, key.perSide) }
    }
}

/** Live numbers for a guided set, for the screen. */
data class GuidedProgress(val reps: Int, val target: Int, val side: Int, val sides: Int, val switching: Boolean, val word: String?, val started: Boolean)

fun PlayerState.guided(nowMs: Long): GuidedProgress? {
    val g = Guides.forStep(current) ?: return null
    val pos = clock(nowMs) - guideStartMs
    val started = !inLeadIn && pos >= 0
    val word = if (!started) null else g.cues.lastOrNull { it.atMs <= pos && it.cue is GuideCue.Word }?.let { (it.cue as GuideCue.Word).text }
    return GuidedProgress(if (started) g.repsDone(pos) else 0, g.reps, if (started) g.sideIndex(pos) else 0, g.sides, started && g.switchingAt(pos), word, started)
}

/** What the 3-D figure shows at [nowMs]: which exercise and where in its movement. */
fun PlayerState.figure(nowMs: Long): Pair<String, GuideFrame> {
    val step = current
    val t = clock(nowMs)
    if (step.type != StepType.WORK) return step.exerciseId to GuideFrame(nowMs / 1000.0) // demo of what's next
    val guide = Guides.forStep(step)
    val mirrored = step.side == "Right"
    return when {
        inLeadIn || (guide != null && t < guideStartMs) -> step.exerciseId to GuideFrame(0.0, mirrored = mirrored, breathe = t / 1000.0)
        guide != null -> step.exerciseId to guide.frame(t - guideStartMs)
        step.timed -> step.exerciseId to GuideFrame(((t - (deadlineMs - step.seconds * 1000L)).coerceAtLeast(0)) / 1000.0, mirrored)
        else -> step.exerciseId to GuideFrame(nowMs / 1000.0, mirrored)
    }
}

sealed interface Cue {
    /** A new step. [next] is the work coming up (for rests / get-ready); [announce] says it now. */
    data class Enter(val step: Step, val leadIn: LeadIn = LeadIn.NONE, val next: Step? = null, val announce: Boolean = false, val afterWork: Boolean = false) : Cue
    data class Count(val n: Int) : Cue
    /** Timed work starts after its countdown. */
    data object Go : Cue
    /** Tempo calls during a guided set ("down", "up", rep numbers, switch sides). */
    data class Guide(val cue: GuideCue) : Cue
    /** Near the end of a rest: what's next. */
    data class Upcoming(val step: Step) : Cue
    data object Halfway : Cue
    data object Finished : Cue
}

/** Drift-free workout timer and guided-set clock. Pure: time comes from [now]. */
class WorkoutEngine(private val speechMs: (String) -> Long = WorkoutSpeech::estimateMs, private val now: () -> Long) {
    var state: PlayerState? = null
        private set

    fun start(steps: List<Step>): List<Cue> {
        require(steps.isNotEmpty())
        val t = now()
        val s = enter(PlayerState(steps, startedAtMs = t), 0, t, from = null, natural = false)
        state = s
        return listOf(enterCue(s, afterWork = false))
    }

    fun restore(s: PlayerState) { state = s }

    private fun enter(s: PlayerState, i: Int, t: Long, from: Step?, natural: Boolean): PlayerState {
        val step = s.steps[i]
        val leadIn = when {
            step.type != StepType.WORK -> LeadIn.NONE
            natural && from != null && from.type != StepType.WORK -> LeadIn.NONE
            from != null && from.type == StepType.WORK && from.exerciseId == step.exerciseId && step.timed -> LeadIn.SWITCH
            else -> LeadIn.INTRO
        }
        val leadMs = when (leadIn) {
            LeadIn.NONE -> 0L
            LeadIn.INTRO -> speechMs(WorkoutSpeech.intro(step)) + COUNTDOWN_MS
            LeadIn.SWITCH -> SWITCH_MS + COUNTDOWN_MS
            LeadIn.RESUME -> COUNTDOWN_MS
        }
        val start = t + leadMs
        // A short rest has no room for a separate "next up" announcement: it's said on entering.
        val next = s.steps.getOrNull(i + 1)
        val announceOnEnter = step.type == StepType.REST && next != null && step.seconds * 1000L < announceNeedMs(next) + MIN_REST_BEFORE_ANNOUNCE_MS
        return s.copy(
            index = i, leadIn = leadIn, leadInEndMs = if (leadMs > 0) start else 0,
            deadlineMs = if (step.timed) start + step.seconds * 1000L else 0, remainingMs = step.seconds * 1000L,
            guideStartMs = start, guideCue = 0, countdownSpoken = emptySet(),
            announced = step.type != StepType.REST || announceOnEnter,
        )
    }

    private fun enterCue(s: PlayerState, afterWork: Boolean): Cue.Enter {
        val step = s.current
        val announce = step.type == StepType.REST && s.announced
        return Cue.Enter(step, s.leadIn, next = if (step.type == StepType.WORK) null else s.next, announce = announce || step.type == StepType.GET_READY, afterWork = afterWork)
    }

    private fun announceNeedMs(next: Step) = speechMs(WorkoutSpeech.upcoming(next)) + 400 + COUNTDOWN_MS

    fun tick(): List<Cue> {
        var s = state ?: return emptyList()
        if (s.paused || s.finished) return emptyList()
        val t = now()
        val cues = mutableListOf<Cue>()
        var guard = 0
        while (!s.finished && guard++ < 10_000) {
            val step = s.current
            // Lead-in: the intro is said on entering; then 3-2-1.
            if (s.leadInEndMs > 0) {
                if (t < s.leadInEndMs) { s = countdown(s, s.leadInEndMs - t, cues); break }
                s = s.copy(leadInEndMs = 0, countdownSpoken = emptySet())
                if (step.timed) cues += Cue.Go
            }
            val guide = Guides.forStep(step)
            if (guide != null) {
                val pos = t - s.guideStartMs
                var k = s.guideCue
                while (k < guide.cues.size && guide.cues[k].atMs <= pos) {
                    // A late tick (screen off, busy phone) skips stale calls rather than blurting them all.
                    if (pos - guide.cues[k].atMs < STALE_MS) cues += Cue.Guide(guide.cues[k].cue)
                    k++
                }
                s = s.copy(guideCue = k)
                if (pos >= guide.totalMs) {
                    s = record(s, s.index, reps = guide.reps, seconds = null, effort = null, skipped = false)
                    s = advanceNatural(s, s.guideStartMs + guide.totalMs, t, cues)
                    continue
                }
                break
            }
            if (!step.timed) break // rep work without a tempo waits for Done
            if (t >= s.deadlineMs) {
                if (step.type == StepType.WORK) s = record(s, s.index, reps = null, seconds = step.seconds, effort = null, skipped = false)
                s = advanceNatural(s, s.deadlineMs, t, cues)
                continue
            }
            val remaining = s.deadlineMs - t
            s = s.copy(remainingMs = remaining.coerceAtMost(step.seconds * 1000L))
            if (step.type == StepType.WORK && step.seconds >= 20 && remaining <= step.seconds * 500L && HALF !in s.countdownSpoken) {
                cues += Cue.Halfway; s = s.copy(countdownSpoken = s.countdownSpoken + HALF)
            }
            val next = s.next
            if (!s.announced && next != null && remaining <= announceNeedMs(next)) {
                cues += Cue.Upcoming(next); s = s.copy(announced = true)
            }
            if (step.seconds > 3) s = countdown(s, remaining, cues)
            break
        }
        state = s
        return cues
    }

    private fun countdown(s: PlayerState, remaining: Long, cues: MutableList<Cue>): PlayerState {
        val secs = ceil(remaining / 1000.0).toInt()
        if (secs in 1..3 && secs !in s.countdownSpoken) {
            cues += Cue.Count(secs)
            return s.copy(countdownSpoken = s.countdownSpoken + secs)
        }
        return s
    }

    /** The current step ran its course at [at]; move on (catching up if the tick was late). */
    private fun advanceNatural(s: PlayerState, at: Long, t: Long, cues: MutableList<Cue>): PlayerState {
        if (s.index == s.steps.lastIndex) { cues += Cue.Finished; return s.copy(finished = true, remainingMs = 0) }
        val from = s.current
        val n = enter(s, s.index + 1, at, from, natural = true)
        val step = n.current
        val expired = (step.timed && t >= n.deadlineMs) || Guides.forStep(step)?.let { t >= n.guideStartMs + it.totalMs } == true
        if (!expired) cues += enterCue(n, afterWork = from.type == StepType.WORK)
        return n
    }

    private fun record(s: PlayerState, i: Int, reps: Int?, seconds: Int?, effort: Effort?, skipped: Boolean): PlayerState {
        val step = s.steps[i]
        val prev = s.results[i]
        return s.copy(
            results = s.results + (i to SetResult(i, step.exerciseId, step.slotKey, step.setIndex, step.side, reps, seconds, effort ?: prev?.effort, skipped)),
        )
    }

    private fun advance(s: PlayerState): Pair<PlayerState, List<Cue>> {
        if (s.index == s.steps.lastIndex) return s.copy(finished = true) to listOf(Cue.Finished)
        val from = s.current
        val n = enter(s, s.index + 1, now(), from, natural = false)
        return n to listOf(enterCue(n, afterWork = from.type == StepType.WORK))
    }

    /**
     * Finish the current step now. Rep work logs [reps], or for a guided set the reps counted so far
     * (the full target if it's tapped before the set started); timed work may end early.
     */
    fun complete(reps: Int? = null): List<Cue> {
        // Acting while paused resumes first (so paused time isn't counted as active).
        if (state?.paused == true) setPaused(false)
        val s = state ?: return emptyList()
        if (s.finished) return emptyList()
        val step = s.current
        var n = s
        if (step.type == StepType.WORK) {
            val t = now()
            val doneSec = if (step.timed) (step.seconds - (s.remainingMs / 1000).toInt()).coerceAtLeast(0) else null
            val guide = Guides.forStep(step)
            val r = when {
                step.timed -> null
                reps != null -> reps
                guide != null && !s.inLeadIn && t >= s.guideStartMs -> guide.repsDone(t - s.guideStartMs)
                else -> step.repsMax
            }
            n = record(s, s.index, reps = r, seconds = doneSec, effort = null, skipped = false)
        }
        val (a, cues) = advance(n)
        state = a
        return cues
    }

    fun skip(): List<Cue> {
        if (state?.paused == true) setPaused(false)
        val s = state ?: return emptyList()
        if (s.finished) return emptyList()
        val n = if (s.current.type == StepType.WORK) record(s, s.index, null, null, null, skipped = true) else s
        val (a, cues) = advance(n)
        state = a
        return cues
    }

    fun previous(): List<Cue> {
        if (state?.paused == true) setPaused(false)
        val s = state ?: return emptyList()
        val target = (s.index - 1 downTo 0).firstOrNull { s.steps[it].type != StepType.REST } ?: return emptyList()
        val n = enter(s.copy(finished = false, results = s.results - target), target, now(), from = null, natural = false)
        state = n
        return listOf(enterCue(n, afterWork = false))
    }

    fun addTime(sec: Int) {
        val s = state ?: return
        if (!s.current.timed || s.paused) return
        state = s.copy(deadlineMs = s.deadlineMs + sec * 1000L, remainingMs = s.remainingMs + sec * 1000L, announced = s.current.type != StepType.REST)
    }

    fun rateEffort(stepIndex: Int, effort: Effort) {
        val s = state ?: return
        val r = s.results[stepIndex] ?: return
        state = s.copy(results = s.results + (stepIndex to r.copy(effort = effort)))
    }

    /** Correct the reps logged for a finished set (e.g. during the rest). */
    fun setReps(stepIndex: Int, reps: Int) {
        val s = state ?: return
        val r = s.results[stepIndex] ?: return
        state = s.copy(results = s.results + (stepIndex to r.copy(reps = reps.coerceAtLeast(0), skipped = false)))
    }

    fun setPaused(paused: Boolean) {
        val s = state ?: return
        if (s.paused == paused || s.finished) return
        val t = now()
        val step = s.current
        state = if (paused) {
            s.copy(paused = true, pausedAtMs = t, remainingMs = if (step.timed) (s.deadlineMs - t).coerceAtLeast(0).coerceAtMost(step.seconds * 1000L) else 0)
        } else {
            val base = s.copy(paused = false, pausedTotalMs = s.pausedTotalMs + (t - s.pausedAtMs))
            val guide = Guides.forStep(step)
            val wasLeadIn = s.inLeadIn
            when {
                step.type == StepType.WORK && (wasLeadIn || guide != null) -> {
                    // Count back in, then redo the rep that was interrupted.
                    val pos = if (wasLeadIn || guide == null) 0L else (s.pausedAtMs - s.guideStartMs).coerceAtLeast(0)
                    val from = guide?.repStartMs(pos) ?: 0L
                    val end = t + COUNTDOWN_MS
                    base.copy(
                        leadIn = LeadIn.RESUME, leadInEndMs = end, countdownSpoken = emptySet(),
                        guideStartMs = end - from, guideCue = guide?.cues?.indexOfFirst { it.atMs >= from }?.takeIf { it >= 0 } ?: (guide?.cues?.size ?: 0),
                        deadlineMs = if (step.timed) end + step.seconds * 1000L else 0, remainingMs = step.seconds * 1000L,
                    )
                }
                step.timed -> base.copy(deadlineMs = t + s.remainingMs)
                else -> base
            }
        }
    }

    fun finishEarly(): List<Cue> {
        val s = state ?: return emptyList()
        state = s.copy(finished = true)
        return listOf(Cue.Finished)
    }

    fun activeMs(): Long {
        val s = state ?: return 0
        val t = if (s.paused) s.pausedAtMs else now()
        return (t - s.startedAtMs - s.pausedTotalMs).coerceAtLeast(0)
    }

    /** How long the caller can sleep before the next thing to say or do (5–200 ms). */
    fun msUntilNextEvent(): Long {
        val s = state ?: return 200
        if (s.paused || s.finished) return 200
        val t = now()
        var next = Long.MAX_VALUE
        fun at(time: Long) { if (time > t) next = minOf(next, time - t) }
        fun secondBoundary(end: Long) { val r = end - t; if (r > 0) next = minOf(next, (r - 1) % 1000 + 1) }
        if (s.inLeadIn) { at(s.leadInEndMs); secondBoundary(s.leadInEndMs) }
        else {
            val guide = Guides.forStep(s.current)
            if (guide != null) {
                guide.cues.getOrNull(s.guideCue)?.let { at(s.guideStartMs + it.atMs) }
                at(s.guideStartMs + guide.totalMs)
            } else if (s.current.timed) { at(s.deadlineMs); secondBoundary(s.deadlineMs) }
        }
        return next.coerceIn(5, 200)
    }

    companion object {
        const val COUNTDOWN_MS = 3_000L
        const val SWITCH_MS = 1_500L
        private const val STALE_MS = 1_500L
        private const val MIN_REST_BEFORE_ANNOUNCE_MS = 6_000L
        private const val HALF = -1
    }
}

/** Speakable text for cues. */
object WorkoutSpeech {
    fun target(step: Step): String =
        if (step.timed) "${step.seconds} seconds"
        else "${step.repsMax} reps${if (step.side == "each side") " each side" else ""}"

    /** "Glute bridge. Set 2 of 3. 12 reps." */
    fun intro(step: Step): String = buildString {
        append(Exercises[step.exerciseId].name)
        if (step.setCount > 1) append(". Set ${step.setIndex} of ${step.setCount}")
        if (step.side == "Left" || step.side == "Right") append(". ${step.side} side")
        append(". ").append(target(step)).append('.')
    }

    fun upcoming(next: Step): String = "Next: " + intro(next)

    fun enter(c: Cue.Enter): String {
        val step = c.step
        return when (step.type) {
            StepType.GET_READY -> {
                val next = c.next?.takeIf { it.type == StepType.WORK }
                val head = if (next != null) "Next: " + intro(next) else "Get ready. ${Exercises[step.exerciseId].name}."
                // A longer get-ready has room for the first form tip.
                val tip = Exercises[step.exerciseId].cues.firstOrNull()?.takeIf { step.seconds >= 8 }
                if (tip != null) "$head $tip." else head
            }
            StepType.REST -> buildString {
                if (c.afterWork) append("Nice work. ")
                append("Rest.")
                val next = c.next
                if (c.announce && next != null && next.type == StepType.WORK) append(' ').append(upcoming(next))
            }
            StepType.WORK -> when (c.leadIn) {
                LeadIn.NONE -> if (step.timed) "Go!" else ""
                LeadIn.INTRO -> intro(step)
                LeadIn.SWITCH -> "Switch sides." + if (step.side == "Left" || step.side == "Right") " ${step.side} side." else ""
                LeadIn.RESUME -> ""
            }
        }
    }

    /** Rough speaking time, for fitting announcements before a countdown. */
    fun estimateMs(text: String): Long = 400L + text.split(Regex("\\s+")).count { it.isNotBlank() } * 330L
}

enum class SuggestionKind { HARDER, EASIER, MORE, HOLD }
data class Suggestion(val kind: SuggestionKind, val text: String, val exerciseId: String? = null)

object Progression {
    /**
     * Double progression: hit the top of the range on every set without it feeling hard → progress
     * (harder variation if one exists, else more reps/time). Struggling → hold or step down.
     */
    fun suggest(p: Prescription, exerciseId: String, last: List<SetResult>): Suggestion? {
        val done = last.filter { !it.skipped }
        if (done.isEmpty()) return null
        val hard = done.count { it.effort == Effort.HARD }
        val harder = Exercises.harder(exerciseId)
        val easier = Exercises.easier(exerciseId)
        return if (p.isTimed) {
            val full = done.all { (it.seconds ?: 0) >= p.seconds }
            when {
                full && hard == 0 && harder != null && p.seconds >= 30 -> Suggestion(SuggestionKind.HARDER, "Ready for ${Exercises[harder].name}", harder)
                full && hard == 0 -> Suggestion(SuggestionKind.MORE, "Try holding ${p.seconds + 5} s")
                !full && easier != null && done.all { (it.seconds ?: 0) < p.seconds / 2 } -> Suggestion(SuggestionKind.EASIER, "Try ${Exercises[easier].name} for now", easier)
                else -> Suggestion(SuggestionKind.HOLD, "Same again — aim for the full ${p.seconds} s")
            }
        } else {
            val reps = done.mapNotNull { it.reps }
            if (reps.isEmpty()) return null
            when {
                reps.all { it >= p.repsMax } && hard == 0 && done.size >= p.sets ->
                    if (harder != null) Suggestion(SuggestionKind.HARDER, "Ready for ${Exercises[harder].name}", harder)
                    else Suggestion(SuggestionKind.MORE, "Go for ${p.repsMax + 2} reps or slow the lowering to 3 s")
                reps.all { it >= p.repsMax } -> Suggestion(SuggestionKind.HOLD, "Top of the range — once it feels easier, level up")
                reps.max() < p.repsMin - 2 && easier != null -> Suggestion(SuggestionKind.EASIER, "Try ${Exercises[easier].name} to build up", easier)
                else -> Suggestion(SuggestionKind.MORE, "Aim for ${(reps.max() + 1).coerceAtMost(p.repsMax)} reps")
            }
        }
    }

    /** Energy estimate for calisthenics: MET 3.8 (moderate effort). */
    fun kcal(weightKg: Double, activeMinutes: Double): Int = (3.8 * weightKg * activeMinutes / 60.0).toInt()
}
