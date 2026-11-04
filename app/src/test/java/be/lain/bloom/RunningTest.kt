package be.lain.bloom

import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.running.GeoPoint
import be.lain.bloom.domain.running.IntervalTracker
import be.lain.bloom.domain.running.RunCue
import be.lain.bloom.domain.running.RunProgram
import be.lain.bloom.domain.running.SegKind
import be.lain.bloom.domain.running.Segment
import be.lain.bloom.domain.running.Splits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningTest {
    @Test fun programHas27RunsGrowingToThirtyMinutes() {
        assertEquals(27, RunProgram.runs.size)
        val first = RunProgram.runs.first().segments()
        assertEquals(SegKind.WARMUP, first.first().kind)
        assertEquals(8, first.count { it.kind == SegKind.RUN })
        val last = RunProgram.runs.last().segments().filter { it.kind == SegKind.RUN }
        assertEquals(listOf(1800), last.map { it.seconds })
    }

    @Test fun haversineDistance() {
        // ~111.2 km per degree of latitude
        assertEquals(111_195.0, Geo.distance(50.0, 4.0, 51.0, 4.0), 50.0)
    }

    @Test fun filterRejectsInaccurateAndTeleportingFixes() {
        val a = GeoPoint(0, 50.0, 4.0, null, 5f)
        assertNull(Geo.accept(a, a.copy(t = 1000, accuracy = 40f)))
        // 1 km in 1 second is impossible on foot.
        assertNull(Geo.accept(a, GeoPoint(1000, 50.009, 4.0, null, 5f)))
        // Jitter while standing still adds nothing.
        assertEquals(0.0, Geo.accept(a, GeoPoint(1000, 50.0000101, 4.0, null, 5f))!!, 0.0)
        // A normal 3 m/s step is accepted.
        assertEquals(3.3, Geo.accept(a, GeoPoint(1000, 50.00003, 4.0, null, 5f))!!, 0.2)
    }

    @Test fun elevationGainIgnoresNoise() {
        val alts = listOf(100.0, 101.0, 100.0, 101.5, 104.0, 103.0, 107.0, 106.0, 110.0)
        assertEquals(10.0, Geo.elevationGain(alts), 0.5)
    }

    @Test fun splitsInterpolatePerKilometre() {
        // Constant 6:00/km for 2.5 km.
        val samples = (0..250).map { i -> (i * 3.6) to (i * 10.0) }
        val s = Splits.compute(samples)
        assertEquals(3, s.size)
        assertEquals(360, s[0].seconds)
        assertEquals(360, s[1].seconds)
        assertEquals(0.5, s[2].partial, 0.01)
    }

    @Test fun paceFormatting() {
        assertEquals("6:00", Geo.formatPace(360.0))
        assertEquals("–:––", Geo.formatPace(null))
        assertEquals("1:02:03", Geo.formatClock(3723))
    }

    @Test fun trackerAdvancesTimeAndDistanceSegments() {
        val t = IntervalTracker(listOf(Segment(SegKind.RUN, seconds = 60), Segment(SegKind.WALK, meters = 200), Segment(SegKind.RUN, seconds = 30)))
        assertTrue(t.begin().single() is RunCue.Segment)
        t.update(57_000, 150.0)
        assertTrue(t.update(58_200, 150.0).any { it is RunCue.Count && it.n == 2 })
        val cues = t.update(60_500, 160.0)
        assertEquals(1, t.index)
        assertTrue(cues.any { it is RunCue.Segment })
        t.update(90_000, 350.0)
        assertEquals(1, t.index) // only 190 m into the walk
        t.update(95_000, 361.0)
        assertEquals(2, t.index)
        val end = t.update(126_000, 450.0)
        assertTrue(end.contains(RunCue.PlanDone))
        assertTrue(t.done)
    }

    @Test fun kcalUsesRunningOrWalkingFactor() {
        assertEquals(85, Geo.kcal(85.0, 1.0, 400.0))
        assertEquals(51, Geo.kcal(85.0, 1.0, 700.0))
    }
}
