package be.lain.bloom

import be.lain.bloom.data.health.overlapsMostly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthTest {
    private fun t(min: Long) = Instant.ofEpochSecond(min * 60)

    @Test fun watchRecordingOfTheSameRunIsADuplicate() {
        // Bloom run 10:00–10:30, watch auto-detected 10:02–10:29
        assertTrue(overlapsMostly(t(2), t(29), t(0), t(30)))
    }

    @Test fun separateWalkLaterIsNotADuplicate() {
        assertFalse(overlapsMostly(t(40), t(70), t(0), t(30)))
    }

    @Test fun smallOverlapIsNotADuplicate() {
        // 30-min walk that only touches the last 5 minutes of a run
        assertFalse(overlapsMostly(t(25), t(55), t(0), t(30)))
    }
}
