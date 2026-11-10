package be.lain.bloom.domain.motion

import be.lain.bloom.domain.motion.MotionStyle.ALTERNATING
import be.lain.bloom.domain.motion.MotionStyle.HOLD
import be.lain.bloom.domain.motion.MotionStyle.LOOP
import be.lain.bloom.domain.motion.MotionStyle.REPS

/**
 * The rhythm of every exercise. Rep cycles start and end at the "start" pose; the beat marked as the
 * count is where the rep number is said, so it is never shorter than ~0.7 s. Tempos follow the coaching
 * cues in the exercise catalogue (slow lowering, pauses and squeezes where the cues ask for them).
 */
object Tempos {
    private fun b(to: String, s: Double, word: String? = null, mirror: Boolean = false, ease: Ease = Ease.IN_OUT) = Beat(to, s, word, mirror = mirror, ease = ease)
    private fun n(to: String, s: Double, mirror: Boolean = false) = Beat(to, s, count = true, mirror = mirror)
    private fun reps(vararg beats: Beat) = Tempo(REPS, beats.toList())
    private fun alt(vararg beats: Beat) = Tempo(ALTERNATING, beats.toList())
    private fun loop(vararg beats: Beat) = Tempo(LOOP, beats.toList())
    private fun circles(vararg beats: Beat) = Tempo(LOOP, beats.toList(), reverseAtHalf = true)
    private fun c(to: String, s: Double) = Beat(to, s, count = true, ease = Ease.LINEAR)
    private val hold = Tempo(HOLD, emptyList())

    /** Lower slowly, come up with intent, short reset at the top. */
    private fun downUp(down: Double = 1.8, up: Double = 1.0, pause: Double = 0.0, word: String = "down", upWord: String = "up") =
        if (pause > 0) reps(b("bottom", down, word), b("bottom", pause, "hold"), b("start", up, upWord), n("start", 0.7))
        else reps(b("bottom", down, word), b("start", up, upWord), n("start", 0.7))

    /** Lift, squeeze at the top, lower with control. */
    private fun liftSqueeze(up: Double = 0.9, squeeze: Double = 1.0, down: Double = 1.5, upWord: String = "up", squeezeWord: String? = "squeeze", downWord: String = "down") =
        reps(b("top", up, upWord), b("top", squeeze, squeezeWord), b("start", down, downWord), n("start", 0.7))

    /** Alternating lunge-type step: out (via a lifted-foot keyframe), down, back up, other side. */
    private fun altStep(down: Double = 1.5, up: Double = 1.0) = alt(
        b("step", 0.5, "down"), b("bottom", down - 0.5), b("step", up - 0.45, "up"), b("start", 0.45),
        b("step", 0.5, "down", mirror = true), b("bottom", down - 0.5, mirror = true), b("step", up - 0.45, "up", mirror = true), b("start", 0.45),
        n("start", 0.7),
    )

    val all: Map<String, Tempo> = mapOf(
        // Warm-up (timed loops)
        "march-in-place" to loop(b("knee", 0.45, "left"), b("start", 0.45), b("knee", 0.45, "right", mirror = true), b("start", 0.45)),
        "step-jacks" to loop(b("lift", 0.3, "out"), b("out", 0.4), b("lift", 0.3, "in"), b("start", 0.4), b("lift", 0.3, "out", mirror = true), b("out", 0.4, mirror = true), b("lift", 0.3, "in", mirror = true), b("start", 0.4)),
        "hip-circles" to circles(c("left", 0.75), b("back", 0.75, ease = Ease.LINEAR), b("right", 0.75, ease = Ease.LINEAR), b("start", 0.75, ease = Ease.LINEAR)),
        "cat-cow" to loop(b("cat", 1.8, "cat"), b("cat", 0.4), b("cow", 2.2, "cow"), b("cow", 0.4), b("start", 0.8)),
        "arm-circles" to circles(c("front", 0.4), b("up", 0.4, ease = Ease.LINEAR), b("back", 0.4, ease = Ease.LINEAR), b("start", 0.4, ease = Ease.LINEAR)),
        "leg-swings" to loop(b("front", 0.65, "forward"), b("back", 0.75, "back"), b("start", 0.4)),

        // Lower body
        "box-squat" to reps(b("bottom", 1.8, "down"), b("bottom", 0.3), b("start", 1.0, "up"), n("start", 0.7)),
        "bodyweight-squat" to downUp(),
        "sumo-squat" to downUp(pause = 1.0),
        "split-squat" to downUp(),
        "bulgarian-split-squat" to downUp(down = 3.0),
        "step-up" to reps(b("top", 1.2, "up"), b("top", 0.3), b("start", 1.8, "down"), n("start", 0.7)),
        "reverse-lunge" to altStep(),
        "curtsy-lunge" to altStep(),
        "lateral-lunge" to altStep(down = 1.6, up = 1.1),

        // Glutes
        "glute-bridge" to liftSqueeze(squeeze = 1.2, down = 1.6),
        "glute-bridge-march" to alt(b("knee", 0.75, "left"), b("start", 0.75), b("knee", 0.75, "right", mirror = true), n("start", 0.75)),
        "single-leg-glute-bridge" to liftSqueeze(squeeze = 1.0, down = 1.6),
        "hip-thrust" to liftSqueeze(squeeze = 2.0, down = 1.6),
        "single-leg-hip-thrust" to liftSqueeze(squeeze = 1.0, down = 1.6),
        "frog-pump" to liftSqueeze(up = 0.6, squeeze = 0.5, down = 1.2),
        "donkey-kick" to liftSqueeze(up = 0.9, squeeze = 0.5, down = 1.2),
        "fire-hydrant" to liftSqueeze(up = 0.9, squeeze = 0.5, down = 1.2, squeezeWord = "hold"),
        "clamshell" to liftSqueeze(up = 0.9, squeeze = 0.5, down = 1.2, upWord = "open", squeezeWord = null, downWord = "close"),
        "side-lying-leg-raise" to liftSqueeze(up = 0.9, squeeze = 0.4, down = 1.3, squeezeWord = null),

        // Hinge
        "good-morning" to downUp(down = 1.8, up = 1.2),
        "single-leg-rdl" to downUp(down = 2.0, up = 1.3),
        "towel-hamstring-curl" to reps(b("bottom", 1.8, "out"), b("start", 1.2, "in"), n("start", 0.7)),

        // Upper body
        "wall-pushup" to downUp(up = 0.9),
        "incline-pushup" to downUp(up = 0.9),
        "knee-pushup" to downUp(up = 0.9),
        "pushup" to downUp(up = 0.9),
        "bench-dip" to downUp(),
        "prone-ytw" to reps(b("y", 0.9, "Y"), b("y", 0.4), b("start", 0.5), b("t", 0.8, "T"), b("t", 0.4), b("start", 0.5), b("w", 0.8, "W"), b("w", 0.4), n("start", 0.7)),
        "reverse-snow-angel" to reps(b("top", 1.6, "up"), b("start", 1.6, "down"), n("start", 0.7)),
        "doorway-row" to liftSqueeze(up = 0.9, squeeze = 0.5, down = 1.4, upWord = "pull", downWord = "lower"),
        "table-row" to liftSqueeze(up = 0.9, squeeze = 0.5, down = 1.6, upWord = "pull", downWord = "lower"),

        // Core
        "dead-bug" to alt(b("reach", 1.4, "reach"), b("start", 1.1), b("reach", 1.4, "reach", mirror = true), n("start", 1.1)),
        "bird-dog" to alt(b("reach", 1.1, "reach"), b("reach", 2.0, "hold"), b("start", 0.9), b("reach", 1.1, "reach", mirror = true), b("reach", 2.0, "hold", mirror = true), n("start", 0.9)),
        "knee-plank" to hold,
        "plank" to hold,
        "plank-shoulder-tap" to alt(b("tap", 0.7, "tap"), b("start", 0.6), b("tap", 0.7, "tap", mirror = true), n("start", 0.7)),
        "side-plank-knees" to hold,
        "side-plank" to hold,
        "hollow-hold" to hold,
        "reverse-crunch" to reps(b("top", 1.1, "up"), b("start", 1.8, "down"), n("start", 0.7)),
        "stomach-vacuum" to hold,

        // Cool-down
        "hip-flexor-stretch" to hold,
        "figure-four-stretch" to hold,
        "hamstring-stretch" to hold,
        "childs-pose" to hold,
    )

    operator fun get(id: String): Tempo = all[id] ?: hold
}
