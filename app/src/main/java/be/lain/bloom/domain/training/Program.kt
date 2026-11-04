package be.lain.bloom.domain.training

enum class BlockKind(val label: String) { WARMUP("Warm-up"), MAIN("Main"), CORE("Core"), COOLDOWN("Cool-down") }

/** One prescribed exercise in a session. */
data class Prescription(
    val exerciseId: String,
    val sets: Int,
    val repsMin: Int = 0,
    val repsMax: Int = 0,
    val seconds: Int = 0,
    val restSec: Int = 60,
    val note: String = "",
) {
    val isTimed: Boolean get() = seconds > 0
    fun target(): String = if (isTimed) "${seconds}s" else if (repsMin == repsMax) "$repsMin reps" else "$repsMin–$repsMax reps"
}

data class Block(val kind: BlockKind, val items: List<Prescription>)

data class Session(val id: String, val name: String, val focus: String, val blocks: List<Block>) {
    /** Rough duration in minutes, including rests and transitions. */
    fun estimateMinutes(): Int {
        var sec = 0
        blocks.forEach { b ->
            b.items.forEach { p ->
                val ex = Exercises[p.exerciseId]
                val sides = if (ex.perSide) 2 else 1
                val work = if (p.isTimed) p.seconds * sides else ((p.repsMin + p.repsMax) / 2) * 3 * sides
                sec += p.sets * (work + 8) + (p.sets - 1).coerceAtLeast(0) * p.restSec
                if (b.kind == BlockKind.MAIN || b.kind == BlockKind.CORE) sec += p.restSec
            }
        }
        return (sec / 60.0).toInt().coerceAtLeast(5)
    }
}

data class Phase(val index: Int, val name: String, val weeks: Int, val summary: String, val sessions: List<Session>)

/** A selectable strength program. Each keeps its own phase, progress and exercise swaps. */
data class TrainingProgram(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val level: String,
    val sessionsPerWeek: Int,
    val focus: List<String>,
    val equipment: String,
    val phases: List<Phase>,
) {
    fun phase(i: Int): Phase = phases[i.coerceIn(0, phases.lastIndex)]

    /** The original program keeps its old keys so existing swaps and history still match. */
    fun slotKey(phase: Int, session: String, blockIdx: Int, itemIdx: Int): String =
        (if (id == Program.ID) "" else "$id:") + "p$phase-$session-b$blockIdx-i$itemIdx"

    /** Rough session length range across phases, e.g. "26–35 min". */
    fun minutes(): String {
        val all = phases.flatMap { ph -> ph.sessions.map { it.estimateMinutes() } }
        val lo = all.min(); val hi = all.max()
        return if (lo == hi) "$lo min" else "$lo–$hi min"
    }

    /** Enough weeks and workouts in this phase to move on. */
    fun readyToAdvance(phase: Phase, week: Int, workouts: Int): Boolean =
        phase.index < phases.lastIndex && week > phase.weeks && workouts >= phase.weeks * maxOf(2, sessionsPerWeek - 1)
}

/**
 * "Feminine Shape – Bodyweight": 3×/week full body at home, glute/hip emphasis, deep core, posture.
 * Sessions alternate A/B. Each phase raises volume and moves up the variation ladders.
 */
object Program {
    const val ID = "feminine-shape"
    const val NAME = "Feminine Shape · Bodyweight"

    internal fun warmup(extra: String? = null) = Block(
        BlockKind.WARMUP,
        listOfNotNull(
            Prescription("march-in-place", 1, seconds = 60, restSec = 0),
            Prescription("hip-circles", 1, seconds = 40, restSec = 0),
            Prescription("cat-cow", 1, seconds = 40, restSec = 0),
            Prescription("leg-swings", 1, seconds = 20, restSec = 0),
            Prescription("arm-circles", 1, seconds = 30, restSec = 0),
            extra?.let { Prescription(it, 1, seconds = 45, restSec = 0) },
        ),
    )

    internal val cooldown = Block(
        BlockKind.COOLDOWN,
        listOf(
            Prescription("hip-flexor-stretch", 1, seconds = 25, restSec = 0),
            Prescription("figure-four-stretch", 1, seconds = 25, restSec = 0),
            Prescription("childs-pose", 1, seconds = 40, restSec = 0),
        ),
    )

    internal fun p(id: String, sets: Int, min: Int, max: Int, rest: Int = 60, note: String = "") = Prescription(id, sets, min, max, restSec = rest, note = note)
    internal fun t(id: String, sets: Int, sec: Int, rest: Int = 45, note: String = "") = Prescription(id, sets, seconds = sec, restSec = rest, note = note)

    val phases: List<Phase> = listOf(
        Phase(
            0, "Foundation", 4,
            "Learn the moves, wake up your glutes and core, build the habit. Stop each set with 2–3 reps left in the tank.",
            listOf(
                Session("A", "Foundation A", "Squat · bridge · push · core", listOf(
                    warmup("step-jacks"),
                    Block(BlockKind.MAIN, listOf(
                        p("box-squat", 2, 10, 12),
                        p("glute-bridge", 2, 12, 15, note = "Squeeze 2 s at the top"),
                        p("wall-pushup", 2, 8, 12),
                        p("side-lying-leg-raise", 2, 12, 15, rest = 30),
                    )),
                    Block(BlockKind.CORE, listOf(p("dead-bug", 2, 6, 8, rest = 45), t("stomach-vacuum", 2, 15, rest = 30))),
                    cooldown,
                )),
                Session("B", "Foundation B", "Step · hinge · posture · core", listOf(
                    warmup("step-jacks"),
                    Block(BlockKind.MAIN, listOf(
                        p("step-up", 2, 8, 10, note = "Use a low step"),
                        p("good-morning", 2, 10, 12),
                        p("prone-ytw", 2, 6, 8, rest = 45),
                        p("clamshell", 2, 12, 15, rest = 30),
                    )),
                    Block(BlockKind.CORE, listOf(p("bird-dog", 2, 6, 8, rest = 45), t("knee-plank", 2, 20, rest = 45))),
                    cooldown,
                )),
            ),
        ),
        Phase(
            1, "Build", 6,
            "Three sets now, and single-leg work for shape and balance. Add reps before moving to a harder version.",
            listOf(
                Session("A", "Build A", "Squat · single-leg bridge · push · core", listOf(
                    warmup("step-jacks"),
                    Block(BlockKind.MAIN, listOf(
                        p("bodyweight-squat", 3, 10, 15),
                        p("single-leg-glute-bridge", 3, 8, 12),
                        p("incline-pushup", 3, 8, 12),
                        p("fire-hydrant", 3, 10, 12, rest = 30),
                    )),
                    Block(BlockKind.CORE, listOf(t("plank", 3, 20, rest = 30), t("stomach-vacuum", 2, 15, rest = 20))),
                    cooldown,
                )),
                Session("B", "Build B", "Split squat · hinge · row · core", listOf(
                    warmup("step-jacks"),
                    Block(BlockKind.MAIN, listOf(
                        p("split-squat", 3, 8, 10),
                        p("good-morning", 3, 12, 15, rest = 45),
                        p("doorway-row", 3, 10, 12, rest = 45),
                        p("frog-pump", 3, 15, 20, rest = 30),
                    )),
                    Block(BlockKind.CORE, listOf(t("side-plank-knees", 3, 20, rest = 30), p("dead-bug", 3, 6, 8, rest = 30))),
                    cooldown,
                )),
            ),
        ),
        Phase(
            2, "Shape", 8,
            "More glute volume with slow lowering and pauses. This is where the shape really starts to show.",
            listOf(
                Session("A", "Shape A", "Bulgarian · hip thrust · push · core", listOf(
                    warmup("glute-bridge"),
                    Block(BlockKind.MAIN, listOf(
                        p("bulgarian-split-squat", 3, 8, 10, note = "3 seconds down"),
                        p("hip-thrust", 3, 12, 15, note = "2 s squeeze at the top"),
                        p("knee-pushup", 3, 8, 12, rest = 45),
                        p("donkey-kick", 2, 12, 15, rest = 30),
                    )),
                    Block(BlockKind.CORE, listOf(p("plank-shoulder-tap", 2, 8, 10, rest = 30), t("hollow-hold", 2, 20, rest = 30))),
                    cooldown,
                )),
                Session("B", "Shape B", "Sumo · single-leg RDL · row · core", listOf(
                    warmup("step-jacks"),
                    Block(BlockKind.MAIN, listOf(
                        p("sumo-squat", 3, 12, 15, rest = 45, note = "Pause 1 s at the bottom"),
                        p("single-leg-rdl", 3, 8, 10, rest = 45),
                        p("table-row", 3, 8, 12),
                        p("curtsy-lunge", 2, 8, 10, rest = 45),
                    )),
                    Block(BlockKind.CORE, listOf(t("side-plank", 2, 25, rest = 30), p("reverse-crunch", 2, 10, 12, rest = 30))),
                    cooldown,
                )),
            ),
        ),
        Phase(
            3, "Sculpt", 8,
            "Advanced variations and a bit more intensity. Repeat this phase as long as you keep progressing.",
            listOf(
                Session("A", "Sculpt A", "Bulgarian · single-leg thrust · push · core", listOf(
                    warmup("glute-bridge"),
                    Block(BlockKind.MAIN, listOf(
                        p("bulgarian-split-squat", 3, 10, 12, note = "3 s down, 1 s pause"),
                        p("single-leg-hip-thrust", 3, 10, 12, rest = 45),
                        p("pushup", 3, 6, 10),
                        p("fire-hydrant", 2, 12, 15, rest = 30),
                    )),
                    Block(BlockKind.CORE, listOf(p("plank-shoulder-tap", 2, 12, 14, rest = 30), t("stomach-vacuum", 2, 20, rest = 20))),
                    cooldown,
                )),
                Session("B", "Sculpt B", "Side lunge · hamstrings · row · arms · core", listOf(
                    warmup("step-jacks"),
                    Block(BlockKind.MAIN, listOf(
                        p("lateral-lunge", 3, 8, 10, rest = 45),
                        p("towel-hamstring-curl", 3, 8, 12, rest = 45),
                        p("table-row", 3, 8, 12),
                        p("frog-pump", 2, 20, 25, rest = 30),
                        p("bench-dip", 2, 8, 12, rest = 45),
                    )),
                    Block(BlockKind.CORE, listOf(t("side-plank", 2, 30, rest = 30), t("hollow-hold", 2, 25, rest = 30))),
                    cooldown,
                )),
            ),
        ),
    )

    fun phase(i: Int): Phase = phases[i.coerceIn(0, phases.lastIndex)]

    val program = TrainingProgram(
        id = ID,
        name = NAME,
        tagline = "Glutes, hips, deep core and posture",
        description = "The all-round shape program: full body three times a week with extra glute and core work, from your very first session to advanced variations.",
        level = "Beginner → advanced",
        sessionsPerWeek = 3,
        focus = listOf("Glutes & hips", "Core", "Posture"),
        equipment = "A chair, a low step and a sturdy table",
        phases = phases,
    )
}
