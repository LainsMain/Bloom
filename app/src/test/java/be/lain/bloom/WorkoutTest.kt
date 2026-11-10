package be.lain.bloom

import be.lain.bloom.domain.motion.GuideCue
import be.lain.bloom.domain.motion.RepGuide
import be.lain.bloom.domain.motion.Tempos
import be.lain.bloom.domain.training.BlockKind
import be.lain.bloom.domain.training.Guides
import be.lain.bloom.domain.training.LeadIn
import be.lain.bloom.domain.training.WorkoutSpeech
import be.lain.bloom.domain.training.figure
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
                // Guided tempo up to the top of each rep range (controlled reps) — a bit over 30 min.
                assertTrue("${s.name} is $m min", m in 20..40)
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
                    val range = if (prog.id == "quick-15" || prog.id == "starter-15") 10..20 else 15..42
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

    /** Skips to the first rep exercise (entered by a tap → intro lead-in). */
    private fun toFirstRepWork(e: WorkoutEngine, steps: List<be.lain.bloom.domain.training.Step>): Int {
        val firstRep = steps.indexOfFirst { it.isRepWork }
        while (e.state!!.index < firstRep) e.skip()
        return firstRep
    }

    @Test fun repWorkIsGuidedAtTempoAndStartsTheRestByItself() {
        var t = 0L
        val e = WorkoutEngine({ 2_000L }) { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        val i = toFirstRepWork(e, steps)
        val step = steps[i]
        val guide = Guides.forStep(step)!!
        assertEquals(LeadIn.INTRO, e.state!!.leadIn)
        // Intro (2 s here) then 3-2-1.
        val heard = mutableListOf<Cue>()
        while (t < 5_000) { t += 50; heard += e.tick() }
        assertEquals(listOf(3, 2, 1), heard.filterIsInstance<Cue.Count>().map { it.n })
        t += 10; heard += e.tick()
        assertFalse(e.state!!.inLeadIn)
        // The first call is the first tempo word, right after the countdown.
        assertEquals(Cue.Guide(GuideCue.Word(Tempos[step.exerciseId].beats.first().word!!)), heard.first { it is Cue.Guide })
        // Reps are counted out loud, then the rest starts by itself with the target logged.
        while (t < 5_000 + guide.totalMs + 100) { t += 50; heard += e.tick() }
        val reps = heard.filterIsInstance<Cue.Guide>().map { it.cue }.filterIsInstance<GuideCue.Rep>().map { it.n }
        assertEquals((1..step.repsMax).toList(), reps)
        assertEquals(step.repsMax, e.state!!.results[i]!!.reps)
        assertEquals(StepType.REST, e.state!!.current.type)
        e.rateEffort(i, Effort.EASY)
        e.setReps(i, step.repsMax - 2)
        assertEquals(Effort.EASY, e.state!!.results[i]!!.effort)
        assertEquals(step.repsMax - 2, e.state!!.results[i]!!.reps)
    }

    @Test fun stoppingAGuidedSetLogsTheRepsCountedSoFar() {
        var t = 0L
        val e = WorkoutEngine({ 2_000L }) { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        val i = toFirstRepWork(e, steps)
        val cycle = Tempos[steps[i].exerciseId].cycleMs
        t = e.state!!.guideStartMs + cycle * 2 + cycle / 2
        e.tick()
        e.complete()
        assertEquals(2, e.state!!.results[i]!!.reps)
    }

    @Test fun pausingMidSetCountsBackInAndRedoesThatRep() {
        var t = 0L
        val e = WorkoutEngine({ 2_000L }) { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        val i = toFirstRepWork(e, steps)
        val cycle = Tempos[steps[i].exerciseId].cycleMs
        val start = e.state!!.guideStartMs
        t = start + cycle + cycle / 2; e.tick()
        e.setPaused(true)
        t += 60_000; e.tick()
        e.setPaused(false)
        val s = e.state!!
        assertEquals(LeadIn.RESUME, s.leadIn)
        assertEquals(t + WorkoutEngine.COUNTDOWN_MS, s.leadInEndMs)
        // After the countdown the guide is back at the start of rep 2.
        assertEquals(cycle, s.leadInEndMs - s.guideStartMs)
        val heard = mutableListOf<Cue>()
        repeat(80) { t += 50; heard += e.tick() }
        assertEquals(listOf(3, 2, 1), heard.filterIsInstance<Cue.Count>().map { it.n })
        assertTrue(heard.any { it is Cue.Guide })
    }

    @Test fun naturalStartsHaveNoLeadInAndTimedWorkSaysGo() {
        var t = 0L
        val e = WorkoutEngine { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        t = 10_000
        val cues = e.tick()
        val enter = cues.filterIsInstance<Cue.Enter>().single()
        assertEquals(StepType.WORK, enter.step.type)
        assertEquals(LeadIn.NONE, enter.leadIn)
        assertEquals("Go!", WorkoutSpeech.enter(enter))
    }

    @Test fun timedSidesGetASwitchSidesBreak() {
        var t = 0L
        val e = WorkoutEngine { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        val left = steps.indexOfFirst { it.exerciseId == "leg-swings" && it.side == "Left" }
        while (e.state!!.index < left) e.skip()
        t = e.state!!.deadlineMs
        val cues = e.tick()
        val enter = cues.filterIsInstance<Cue.Enter>().single()
        assertEquals("Right", enter.step.side)
        assertEquals(LeadIn.SWITCH, enter.leadIn)
        assertEquals(t + WorkoutEngine.SWITCH_MS + WorkoutEngine.COUNTDOWN_MS + steps[left + 1].seconds * 1000L, e.state!!.deadlineMs)
    }

    @Test fun longRestsAnnounceTheNextSetBeforeTheCountdown() {
        var t = 0L
        val e = WorkoutEngine { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        val rest = steps.indexOfFirst { it.type == StepType.REST && it.seconds >= 30 }
        while (e.state!!.index < rest) e.skip()
        val heard = mutableListOf<Cue>()
        val end = e.state!!.deadlineMs
        while (t < end - 100) { t += 100; heard += e.tick() }
        val up = heard.indexOfFirst { it is Cue.Upcoming }
        val three = heard.indexOfFirst { it is Cue.Count && it.n == 3 }
        assertTrue("announced", up >= 0)
        assertTrue("before the countdown", up < three)
    }

    @Test fun programNotesChangeTheTempo() {
        val bridge = Tempos["glute-bridge"].withNote("Squeeze 2 s at the top")
        assertEquals(2.0, bridge.beats.first { it.word == "squeeze" }.seconds, 1e-9)
        val split = Tempos["split-squat"].withNote("3 s down, 1 s pause")
        assertEquals(3.0, split.beats.first { it.word == "down" }.seconds, 1e-9)
        assertEquals(1.0, split.beats.first { it.word == "hold" }.seconds, 1e-9)
        val sumo = Tempos["sumo-squat"].withNote("Pause 1 s at the bottom")
        assertEquals(1, sumo.beats.count { it.word == "hold" })
        assertTrue(Tempos["step-up"].withNote("Use a low step") === Tempos["step-up"])
    }

    @Test fun starterProgramIsAboutFifteenMinutes() {
        val starter = be.lain.bloom.domain.training.Programs.byId("starter-15")
        assertEquals("starter-15", starter.id)
        assertEquals(3, starter.sessionsPerWeek)
        starter.phases.flatMap { it.sessions }.forEach { s ->
            val m = s.estimateMinutes()
            assertTrue("${s.name} is $m min", m in 12..17)
        }
    }

    @Test fun warmupLoopsCallTheRhythm() {
        val march = be.lain.bloom.domain.motion.LoopGuide(Tempos["march-in-place"], 45_000)
        val words = march.cues.map { it.cue }.filterIsInstance<GuideCue.Word>().map { it.text }
        // The first "left" falls in the quiet moment after "Go!", so the chant starts on the right knee.
        assertEquals(listOf("right", "left", "right", "left"), words.take(4))
        // Clear of "Go!" at the start and the 3-2-1 at the end.
        assertTrue(march.cues.all { it.atMs >= 700 && it.atMs <= 45_000 - 3_300 })
        val circles = be.lain.bloom.domain.motion.LoopGuide(Tempos["hip-circles"], 30_000)
        assertTrue(circles.cues.any { it.cue == GuideCue.SwitchDirection && it.atMs == 15_000L })
        // The figure turns round at the switch.
        assertTrue(circles.frame(16_000, false).t < circles.frame(15_000, false).t)
    }

    @Test fun timedWarmupSaysTheRhythmInStep() {
        var t = 0L
        val e = WorkoutEngine { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        t = 10_000; e.tick() // march starts after the 10 s get-ready
        assertEquals("march-in-place", e.state!!.current.exerciseId)
        val heard = mutableListOf<Cue>()
        while (t < 30_000) { t += 50; heard += e.tick() }
        val calls = heard.filterIsInstance<Cue.Guide>().map { it.cue }.filterIsInstance<GuideCue.Word>().map { it.text }
        assertTrue(calls.size > 15)
        assertTrue(calls.zipWithNext().all { (a, b) -> a != b }) // left, right, left…
        assertFalse(heard.any { it is Cue.Halfway })
    }

    @Test fun pausedFigureKeepsDemonstrating() {
        var t = 0L
        val e = WorkoutEngine({ 2_000L }) { t }
        val steps = WorkoutPlan.steps(Program.phase(0).sessions[0], 0)
        e.start(steps)
        toFirstRepWork(e, steps)
        t = e.state!!.guideStartMs + 2_500; e.tick()
        e.setPaused(true)
        val a = e.state!!.figure(t + 400).second
        val b = e.state!!.figure(t + 1_400).second
        assertTrue("the demo moves while paused", a.t != b.t)
    }

    @Test fun lateNightSessionsCountForYesterday() {
        val zone = java.time.ZoneId.of("Europe/Brussels")
        val today = java.time.LocalDate.of(2026, 11, 11)
        fun at(h: Int, m: Int) = today.atTime(h, m).atZone(zone).toInstant()
        assertEquals(today.minusDays(1), be.lain.bloom.core.designsystem.defaultLogDate(at(0, 30), zone, today))
        assertEquals(today.minusDays(1), be.lain.bloom.core.designsystem.defaultLogDate(at(3, 59), zone, today))
        assertEquals(today, be.lain.bloom.core.designsystem.defaultLogDate(at(4, 0), zone, today))
        assertEquals(today, be.lain.bloom.core.designsystem.defaultLogDate(at(18, 0), zone, today))
        // Started late yesterday evening and saved after midnight: still yesterday.
        assertEquals(today.minusDays(1), be.lain.bloom.core.designsystem.defaultLogDate(today.minusDays(1).atTime(23, 40).atZone(zone).toInstant(), zone, today))
    }

    @Test fun repGuideTimeline() {
        val g = RepGuide(Tempos["split-squat"], reps = 3, perSide = true)
        assertEquals(2, g.sides)
        val c = Tempos["split-squat"].cycleMs
        assertEquals(c * 6 + RepGuide.SWITCH_MS, g.totalMs)
        val reps = g.cues.map { it.cue }.filterIsInstance<GuideCue.Rep>().map { it.n }
        assertEquals(listOf(1, 2, 3, 1, 2, 3), reps)
        assertTrue(g.cues.any { it.cue == GuideCue.SwitchSides })
        assertEquals(1, g.sideIndex(c * 3 + RepGuide.SWITCH_MS + 10))
        assertEquals(c * 3 + RepGuide.SWITCH_MS, g.repStartMs(c * 3 + 100)) // during the switch → right side
        assertEquals(c, g.repStartMs(c + c / 2))
        // Alternating moves do both sides in one cycle and never switch.
        assertEquals(1, RepGuide(Tempos["dead-bug"], 8, perSide = true).sides)
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
