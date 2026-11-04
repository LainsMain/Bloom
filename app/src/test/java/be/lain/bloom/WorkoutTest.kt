package be.lain.bloom

import be.lain.bloom.domain.training.BlockKind
import be.lain.bloom.domain.training.Cue
import be.lain.bloom.domain.training.Effort
import be.lain.bloom.domain.training.Exercises
import be.lain.bloom.domain.training.Prescription
import be.lain.bloom.domain.training.Program
import be.lain.bloom.domain.training.Progression
import be.lain.bloom.domain.training.SetResult
import be.lain.bloom.domain.training.StepType
import be.lain.bloom.domain.training.SuggestionKind
import be.lain.bloom.domain.training.WorkoutEngine
import be.lain.bloom.domain.training.WorkoutPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutTest {
    @Test fun everyProgramExerciseExistsAndLaddersAreValid() {
        Program.phases.forEach { ph -> ph.sessions.forEach { s -> s.blocks.forEach { b -> b.items.forEach { Exercises[it.exerciseId] } } } }
        Exercises.ladders.values.flatten().forEach { Exercises[it] }
        assertEquals("bodyweight-squat", Exercises.harder("box-squat"))
        assertEquals("knee-pushup", Exercises.easier("pushup"))
        assertEquals(null, Exercises.harder("single-leg-hip-thrust"))
    }

    @Test fun sessionsFitAboutThirtyMinutes() {
        Program.phases.forEach { ph ->
            ph.sessions.forEach { s ->
                val m = s.estimateMinutes()
                assertTrue("${s.name} is $m min", m in 20..36)
            }
        }
    }

    @Test fun everyCatalogueProgramIsValid() {
        val ids = be.lain.bloom.domain.training.Programs.all.map { it.id }
        assertEquals("program ids are unique", ids.size, ids.toSet().size)
        be.lain.bloom.domain.training.Programs.all.forEach { prog ->
            assertTrue(prog.phases.isNotEmpty())
            prog.phases.forEachIndexed { i, ph ->
                assertEquals("${prog.id} phase index", i, ph.index)
                assertTrue("${prog.id} ${ph.name} has sessions", ph.sessions.isNotEmpty())
                assertEquals("${prog.id} session ids unique", ph.sessions.size, ph.sessions.map { it.id }.toSet().size)
                ph.sessions.forEach { s ->
                    s.blocks.forEach { b -> b.items.forEach { Exercises[it.exerciseId] } }
                    // Every session also produces a playable plan.
                    assertTrue(WorkoutPlan.steps(s, ph.index, program = prog).isNotEmpty())
                    val m = s.estimateMinutes()
                    val range = if (prog.id == "quick-15") 10..20 else 15..40
                    assertTrue("${prog.id} ${s.name} is $m min", m in range)
                }
            }
        }
    }

    @Test fun slotKeysStayCompatibleAndSeparate() {
        // The original program keeps its old keys (existing swaps and history), others are namespaced.
        assertEquals("p0-A-b1-i0", Program.program.slotKey(0, "A", 1, 0))
        assertEquals("glute-builder:p0-A-b1-i0", be.lain.bloom.domain.training.Programs.gluteBuilder.slotKey(0, "A", 1, 0))
        assertEquals(Program.program, be.lain.bloom.domain.training.Programs.byId("nope"))
    }

    @Test fun stepsIncludeSidesRestsAndGetReady() {
        val s = Program.phase(0).sessions[0]
        val steps = WorkoutPlan.steps(s, 0)
        assertEquals(StepType.GET_READY, steps.first().type)
        // Leg swings are timed and per side -> Left and Right steps.
        val swings = steps.filter { it.exerciseId == "leg-swings" && it.type == StepType.WORK }
        assertEquals(listOf("Left", "Right"), swings.map { it.side })
        // No rest inside the warm-up circuit.
        assertTrue(steps.none { it.type == StepType.REST && it.block == BlockKind.WARMUP })
        // Main block: 2 sets of chair squats with a rest between.
        val squat = steps.filter { it.exerciseId == "box-squat" && it.type == StepType.WORK }
        assertEquals(2, squat.size)
        assertTrue(squat.all { it.isRepWork })
    }

    @Test fun swappedVariationIsUsed() {
        val s = Program.phase(0).sessions[0]
        val steps = WorkoutPlan.steps(s, 0) { slot, d -> if (d == "wall-pushup") "incline-pushup" else d }
        assertTrue(steps.any { it.exerciseId == "incline-pushup" })
        assertFalse(steps.any { it.exerciseId == "wall-pushup" })
    }

    @Test fun timedStepsAdvanceWithoutDrift() {
        var t = 0L
        val e = WorkoutEngine { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        // First get-ready is 10 s, then march 60 s.
        t = 10_000; e.tick()
        assertEquals("march-in-place", e.state!!.current.exerciseId)
        t = 69_000; val cues = e.tick()
        assertTrue(cues.any { it is Cue.Count && it.n == 1 })
        t = 70_200; e.tick()
        // Late tick: deadline of the next step is measured from 70 000, not 70 200.
        assertEquals(70_000L + steps[e.state!!.index].seconds * 1000L, e.state!!.deadlineMs)
    }

    @Test fun repWorkWaitsForDoneAndRecordsReps() {
        var t = 0L
        val e = WorkoutEngine { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        val firstRep = steps.indexOfFirst { it.isRepWork }
        // Skip ahead through the warm-up quickly.
        while (e.state!!.index < firstRep) e.skip()
        t += 100_000; e.tick()
        assertEquals(firstRep, e.state!!.index) // still waiting
        e.complete(11)
        assertEquals(11, e.state!!.results[firstRep]!!.reps)
        assertEquals(StepType.REST, e.state!!.current.type)
        e.rateEffort(firstRep, Effort.EASY)
        assertEquals(Effort.EASY, e.state!!.results[firstRep]!!.effort)
    }

    @Test fun pauseFreezesTheClock() {
        var t = 0L
        val e = WorkoutEngine { t }
        e.start(WorkoutPlan.steps(Program.phase(0).sessions[0], 0))
        t = 4_000; e.tick(); e.setPaused(true)
        t = 60_000; e.tick()
        assertEquals(0, e.state!!.index)
        e.setPaused(false)
        t = 65_000; e.tick()
        assertEquals(0, e.state!!.index) // 6 s remained when paused → ends at 66 s
        t = 66_000; e.tick()
        assertEquals(1, e.state!!.index)
        // 66 s elapsed, 56 s of it paused.
        assertEquals(10_000L, e.activeMs())
    }

    @Test fun actingWhilePausedResumesAndExcludesPausedTime() {
        var t = 0L
        val e = WorkoutEngine { t }
        e.start(WorkoutPlan.steps(Program.phase(0).sessions[0], 0))
        t = 2_000; e.setPaused(true)
        t = 30_000; e.complete()
        assertFalse(e.state!!.paused)
        assertEquals(1, e.state!!.index)
        assertEquals(2_000L, e.activeMs())
    }

    private fun r(reps: Int?, sec: Int? = null, effort: Effort? = Effort.GOOD) =
        SetResult(0, "x", "s", 1, null, reps, sec, effort, false)

    @Test fun progressionSuggestsHarderWhenTopOfRangeIsEasy() {
        val p = Prescription("box-squat", 2, 10, 12)
        val s = Progression.suggest(p, "box-squat", listOf(r(12), r(12)))
        assertEquals(SuggestionKind.HARDER, s!!.kind)
        assertEquals("bodyweight-squat", s.exerciseId)
        assertEquals(SuggestionKind.HOLD, Progression.suggest(p, "box-squat", listOf(r(12), r(12, effort = Effort.HARD)))!!.kind)
        assertEquals(SuggestionKind.EASIER, Progression.suggest(Prescription("pushup", 3, 6, 10), "pushup", listOf(r(2), r(2), r(1)))!!.kind)
    }

    @Test fun timedProgressionAddsTime() {
        val p = Prescription("knee-plank", 2, seconds = 20)
        assertEquals(SuggestionKind.MORE, Progression.suggest(p, "knee-plank", listOf(r(null, 20), r(null, 20)))!!.kind)
        assertNotNull(Progression.suggest(Prescription("knee-plank", 2, seconds = 30), "knee-plank", listOf(r(null, 30), r(null, 30)))!!.exerciseId)
    }
}
