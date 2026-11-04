package be.lain.bloom

import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.running.GeoPoint
import be.lain.bloom.domain.running.IntervalTracker
import be.lain.bloom.domain.running.RunCue
import be.lain.bloom.domain.running.RunProgram
import be.lain.bloom.domain.running.SegKind
import be.lain.bloom.domain.running.Segment
import be.lain.bloom.domain.running.Splits
import be.lain.bloom.domain.running.Track
import be.lain.bloom.domain.running.TrackPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
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
        // Jitter while standing still is skipped (and so never moves the anchor).
        assertNull(Geo.accept(a, GeoPoint(1000, 50.0000101, 4.0, null, 5f)))
        // A normal 3 m/s step is accepted.
        assertEquals(3.3, Geo.accept(a, GeoPoint(1000, 50.00003, 4.0, null, 5f))!!, 0.2)
    }

    @Test fun elevationGainCountsARealClimb() {
        // A steady 10 m rise over 2 minutes with ±1.5 m jitter on top.
        val alts = (0..120).map { i -> i.toDouble() to 100.0 + i / 12.0 + if (i % 2 == 0) 1.5 else -1.5 }
        assertEquals(10.0, Geo.elevationGain(alts, Geo.BARO_ALT_WINDOW, Geo.BARO_ALT_THRESHOLD), 2.5)
    }

    @Test fun elevationGainIgnoresGpsWanderOnFlatGround() {
        // 40 minutes on flat ground: GPS altitude jumps ±4 m a second and wanders ±4 m over minutes.
        val rnd = Random(7)
        val alts = (0..2400).map { i -> i.toDouble() to 20.0 + 4 * sin(i / 90.0) + rnd.nextDouble(-4.0, 4.0) }
        assertTrue(Geo.elevationGain(alts) < 12.0)
    }

    /** Points every second, [speed] m/s due north, with GPS noise that drifts slowly like a fused fix does. */
    private fun walkNorth(seconds: Int, speed: (Int) -> Double, accuracy: Float, seed: Int = 1, pauseAt: Int = -1, pauseSec: Int = 0): Pair<List<TrackPoint>, Double> {
        val rnd = Random(seed)
        var north = 0.0; var nx = 0.0; var ny = 0.0; var truth = 0.0
        val mPerDegLat = 111_195.0
        val pts = (0 until seconds).map { i ->
            if (i > 0) { val v = speed(i); north += v; truth += v }
            nx = 0.8 * nx + rnd.nextDouble(-0.6, 0.6); ny = 0.8 * ny + rnd.nextDouble(-0.6, 0.6)
            val wall = i * 1000L + if (pauseAt in 0..i) pauseSec * 1000L else 0
            TrackPoint(wall, 50.8 + (north + ny) / mPerDegLat, 3.26 + nx / (mPerDegLat * cos(Math.toRadians(50.8))), 20.0, null, accuracy, i.toDouble(), 0)
        }
        return pts to truth
    }

    @Test fun steadyRunWithOneFixASecondAddsUp() {
        // The 1.2.1 bug: 2.6 m steps under a 6 m noise radius were all dropped, giving ~0 km.
        val (pts, truth) = walkNorth(600, { 2.6 }, accuracy = 12f)
        val d = Track.compute(pts, 80.0).distanceM
        assertEquals(truth, d, truth * 0.05)
    }

    @Test fun intervalRunWithPauseMatchesTruthAndCountsWalkingCheaper() {
        // 10 × (90 s run at 2.7 m/s + 120 s walk at 1.5 m/s), 11 m accuracy, a 2-minute pause half-way.
        val speed = { i: Int -> if (i % 210 < 90) 2.7 else 1.5 }
        val (pts, truth) = walkNorth(2100, speed, accuracy = 11f, seed = 3, pauseAt = 1000, pauseSec = 120)
        val s = Track.compute(pts.map { it.copy(seg = (it.activeSec.toInt() / 210) * 2 + if (it.activeSec.toInt() % 210 < 90) 0 else 1) }, 80.0)
        assertEquals(truth, s.distanceM, truth * 0.05)
        // Runs ≈ 2.43 km at 1.0 kcal/kg/km, walks ≈ 1.8 km at 0.6.
        assertEquals(80 * (2.43 + 1.8 * 0.6), s.kcal.toDouble(), 25.0)
        assertEquals(pts.size, s.cumulative.size)
        assertTrue(s.cumulative.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun standingStillAddsAlmostNothing() {
        val (pts, _) = walkNorth(300, { 0.0 }, accuracy = 10f)
        assertTrue(Track.compute(pts, 80.0).distanceM < 15.0)
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
