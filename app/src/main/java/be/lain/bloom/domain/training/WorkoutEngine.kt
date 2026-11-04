package be.lain.bloom.domain.training

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
    fun steps(session: Session, phase: Int, choice: (slotKey: String, default: String) -> String = { _, d -> d }): List<Step> {
        val out = mutableListOf<Step>()
        session.blocks.forEachIndexed { b, block ->
            block.items.forEachIndexed { i, p ->
                val slot = Program.slotKey(phase, session.id, b, i)
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
                        val next = if (s < p.sets) exId else choice(Program.slotKey(phase, session.id, b, i + 1), block.items[i + 1].exerciseId)
                        out += Step(StepType.REST, next, slot, block.kind, s, p.sets, seconds = p.restSec)
                    }
                }
            }
        }
        return out
    }
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
) {
    val current: Step get() = steps[index]
    val next: Step? get() = steps.getOrNull(index + 1)
    val progress: Float get() = if (steps.isEmpty()) 1f else index.toFloat() / steps.size
    /** The work step a rest/get-ready belongs to (for effort rating). */
    val previousWorkIndex: Int? get() = (index - 1 downTo 0).firstOrNull { steps[it].type == StepType.WORK }
}

sealed interface Cue {
    data class Enter(val step: Step) : Cue
    data class Count(val n: Int) : Cue
    data object Finished : Cue
}

/** Drift-free workout timer. Pure: time comes from [now]. */
class WorkoutEngine(private val now: () -> Long) {
    var state: PlayerState? = null
        private set

    fun start(steps: List<Step>): List<Cue> {
        require(steps.isNotEmpty())
        val t = now()
        state = enter(PlayerState(steps, startedAtMs = t), 0, t)
        return listOf(Cue.Enter(steps[0]))
    }

    fun restore(s: PlayerState) { state = s }

    private fun enter(s: PlayerState, i: Int, t: Long): PlayerState {
        val step = s.steps[i]
        return s.copy(index = i, deadlineMs = if (step.timed) t + step.seconds * 1000L else 0, remainingMs = step.seconds * 1000L, countdownSpoken = emptySet())
    }

    fun tick(): List<Cue> {
        var s = state ?: return emptyList()
        if (s.paused || s.finished) return emptyList()
        val t = now()
        val cues = mutableListOf<Cue>()
        // Advance through every expired timed step (catching up if a tick was late).
        while (s.current.timed && t >= s.deadlineMs && !s.finished) {
            val ended = s.current
            if (ended.type == StepType.WORK) s = record(s, s.index, reps = null, seconds = ended.seconds, effort = null, skipped = false)
            if (s.index == s.steps.lastIndex) {
                s = s.copy(finished = true, remainingMs = 0); cues += Cue.Finished
            } else {
                val deadline = s.deadlineMs
                s = enter(s, s.index + 1, deadline)
                if (t < s.deadlineMs || !s.current.timed) cues += Cue.Enter(s.current)
            }
        }
        if (!s.finished && s.current.timed) {
            val remaining = (s.deadlineMs - t).coerceAtLeast(0)
            val secs = ceil(remaining / 1000.0).toInt()
            if (s.current.seconds > 3 && secs in 1..3 && secs !in s.countdownSpoken) {
                cues += Cue.Count(secs); s = s.copy(countdownSpoken = s.countdownSpoken + secs)
            }
            s = s.copy(remainingMs = remaining)
        }
        state = s
        return cues
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
        val n = enter(s, s.index + 1, now())
        return n to listOf(Cue.Enter(n.current))
    }

    /** Finish the current work step: reps for rep-based work; timed work may end early. */
    fun complete(reps: Int? = null): List<Cue> {
        // Acting while paused resumes first (so paused time isn't counted as active).
        if (state?.paused == true) setPaused(false)
        val s = state ?: return emptyList()
        if (s.finished) return emptyList()
        val step = s.current
        var n = s
        if (step.type == StepType.WORK) {
            val doneSec = if (step.timed) (step.seconds - (s.remainingMs / 1000).toInt()).coerceAtLeast(0) else null
            n = record(s, s.index, reps = if (step.timed) null else (reps ?: step.repsMax), seconds = doneSec, effort = null, skipped = false)
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
        val n = enter(s.copy(finished = false, results = s.results - target), target, now())
        state = n
        return listOf(Cue.Enter(n.current))
    }

    fun addTime(sec: Int) {
        val s = state ?: return
        if (!s.current.timed || s.paused) return
        state = s.copy(deadlineMs = s.deadlineMs + sec * 1000L, remainingMs = s.remainingMs + sec * 1000L)
    }

    fun rateEffort(stepIndex: Int, effort: Effort) {
        val s = state ?: return
        val r = s.results[stepIndex] ?: return
        state = s.copy(results = s.results + (stepIndex to r.copy(effort = effort)))
    }

    fun setPaused(paused: Boolean) {
        val s = state ?: return
        if (s.paused == paused || s.finished) return
        val t = now()
        state = if (paused) s.copy(paused = true, pausedAtMs = t, remainingMs = if (s.current.timed) (s.deadlineMs - t).coerceAtLeast(0) else 0)
        else s.copy(paused = false, deadlineMs = if (s.current.timed) t + s.remainingMs else 0, pausedTotalMs = s.pausedTotalMs + (t - s.pausedAtMs))
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
}

/** Speakable text for cues. */
object WorkoutSpeech {
    fun enter(step: Step): String {
        val ex = Exercises[step.exerciseId]
        return when (step.type) {
            StepType.GET_READY -> "Get ready. ${ex.name}."
            StepType.REST -> "Rest. Next: ${ex.name}${if (step.setIndex < step.setCount && ex.id == step.exerciseId) ", set ${step.setIndex + 1}" else ""}."
            StepType.WORK -> buildString {
                append(ex.name)
                if (step.setCount > 1) append(". Set ${step.setIndex} of ${step.setCount}")
                if (step.side == "Left" || step.side == "Right") append(". ${step.side} side")
                append(". ")
                append(if (step.timed) "${step.seconds} seconds. Go!" else "${if (step.repsMin == step.repsMax) step.repsMax else "${step.repsMin} to ${step.repsMax}"} reps${if (step.side == "each side") " each side" else ""}.")
            }
        }
    }
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
