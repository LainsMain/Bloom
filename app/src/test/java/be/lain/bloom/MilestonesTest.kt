package be.lain.bloom

import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.Milestones
import be.lain.bloom.domain.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MilestonesTest {
    private val p = Profile("T", Goal.LOSE, EnergyFormula.FEMALE, 2000, 176.0, 85.0, 59.0, Lifestyle.DESK, 0.5, emptySet(), emptySet(), LocalDate.of(2026, 1, 1))

    @Test fun nothingBeforeTheFirstKilo() {
        assertTrue(Milestones.reached(p, 84.2).isEmpty())
        assertTrue(Milestones.reached(p, 86.0).isEmpty())
    }

    @Test fun kilosPercentHalfwayBmiAndGoal() {
        val ids = Milestones.reached(p, 80.6).map { it.id }
        assertEquals(listOf("kg-1", "kg-2", "kg-3", "kg-4"), ids.filter { it.startsWith("kg") })
        assertTrue("5% of 85 is 4.25 kg", "pct-5" in ids)
        val ids2 = Milestones.reached(p, 71.9).map { it.id }
        assertTrue("halfway" in ids2) // halfway is 72.0
        assertTrue("BMI 23.2 is under 25", "bmi-25" in ids2)
        assertTrue("bmi-25" !in Milestones.reached(p, 78.0).map { it.id })
        // 176 cm: BMI 25 ≈ 77.4 kg, 30 ≈ 92.9 (started under 30, so no bmi-30)
        assertTrue("bmi-30" !in Milestones.reached(p, 70.0).map { it.id })
        assertTrue("bmi-25" in Milestones.reached(p, 77.0).map { it.id })
        assertTrue("goal" in Milestones.reached(p, 58.9).map { it.id })
    }

    @Test fun picksTheBiggestNewOne() {
        val all = Milestones.reached(p, 79.9)
        assertEquals("pct-5", Milestones.pick(all.filter { it.id in setOf("kg-4", "kg-5", "pct-5") })!!.id)
        assertEquals("kg-5", Milestones.pick(all.filter { it.id in setOf("kg-4", "kg-5") })!!.id)
        assertTrue(all.first { it.id == "kg-5" }.big)
        assertTrue(!all.first { it.id == "kg-4" }.big)
    }

    @Test fun nextMilestone() {
        val (label, left) = Milestones.next(p, 83.7)!!
        assertEquals("−2 kg", label)
        assertEquals(0.7, left, 1e-9)
        assertNull(Milestones.next(p.copy(goal = Goal.MAINTAIN), 80.0))
    }
}
