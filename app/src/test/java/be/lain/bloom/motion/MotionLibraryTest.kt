package be.lain.bloom.motion

import be.lain.bloom.domain.motion.GuideFrame
import be.lain.bloom.domain.motion.MotionLibrary
import be.lain.bloom.domain.motion.MotionStyle
import be.lain.bloom.domain.motion.Solver
import be.lain.bloom.domain.motion.Tempos
import be.lain.bloom.domain.training.Exercises
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Every exercise has a demo that stays on the floor, reaches its holds and loops cleanly. */
class MotionLibraryTest {
    companion object {
        private lateinit var asset: FigureAsset
        private lateinit var solver: Solver
        private lateinit var lib: MotionLibrary

        @BeforeClass @JvmStatic fun load() {
            asset = FigureAsset(); solver = Solver(asset.rig); lib = MotionLibrary(solver)
        }
    }

    @Test fun everyExerciseHasAMotionAndATempo() {
        val missing = Exercises.all.map { it.id }.filter { lib[it] == null }
        assertTrue("no motion for $missing", missing.isEmpty())
        val noTempo = Exercises.all.filter { it.mode == be.lain.bloom.domain.training.Mode.REPS && Tempos[it.id].style == MotionStyle.HOLD }
        assertTrue("rep exercises without a tempo: ${noTempo.map { it.id }}", noTempo.isEmpty())
    }

    @Test fun cyclesUseKnownPosesAndEndWhereTheyStart() {
        lib.all.values.forEach { m ->
            m.tempo.beats.forEach { assertTrue("${m.id}: pose ${it.to}", it.to in m.poses) }
            if (m.tempo.beats.isNotEmpty()) assertEquals("${m.id} loops back to start", "start", m.tempo.beats.last().to)
            assertTrue("${m.id} has a counted beat", m.tempo.style != MotionStyle.REPS && m.tempo.style != MotionStyle.ALTERNATING || m.tempo.beats.any { it.count })
        }
    }

    @Test fun figureStaysOnTheFloorAndReachesItsHolds() {
        val problems = mutableListOf<String>()
        val body = asset.meshes.first { it.name == "body" }
        lib.all.values.forEach { m ->
            val n = if (m.tempo.style == MotionStyle.HOLD) 1 else 16
            for (mirror in listOf(false, true)) for (k in 0 until n) {
                val t = k * m.tempo.cycleSeconds / n
                val sp = solver.solve(m.pose(GuideFrame(t, mirror)))
                val ik = sp.ikError.max()
                if (ik > 0.06) problems += "${m.id} t=%.2f: a hand/foot misses its target by %.0f cm".format(t, ik * 100)
                val (pos, _) = asset.skin(sp, body)
                var minY = 9.0
                for (i in 1 until pos.size step 3) minY = minOf(minY, pos[i].toDouble())
                if (minY < -0.06) problems += "${m.id} t=%.2f: sinks %.0f cm into the floor".format(t, -minY * 100)
                val onProp = m.props.any { it.kind == be.lain.bloom.domain.motion.PropKind.BOX }
                if (!onProp && minY > 0.04) problems += "${m.id} t=%.2f: floats %.0f cm above the floor".format(t, minY * 100)
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
