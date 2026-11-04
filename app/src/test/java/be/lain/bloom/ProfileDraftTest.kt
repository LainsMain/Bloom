package be.lain.bloom

import be.lain.bloom.domain.Goal
import be.lain.bloom.feature.onboarding.ProfileDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileDraftTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val valid = ProfileDraft(name = "Lain", heightCm = 176.0, weightKg = 85.0, goalWeightKg = 59.0)

    @Test fun validDraftHasNoErrors() {
        assertTrue(valid.errors(today).isEmpty())
    }

    @Test fun loseGoalMustBeBelowCurrent() {
        assertNotNull(valid.copy(goalWeightKg = 90.0).weightError())
    }

    @Test fun goalBelowHealthyRangeIsBlocked() {
        // BMI 16.5 at 176 cm
        assertNotNull(valid.copy(goalWeightKg = 51.0).weightError())
    }

    @Test fun maintainIgnoresGoalWeightAndPace() {
        val p = valid.copy(goal = Goal.MAINTAIN, goalWeightKg = 40.0).toProfile(today)
        assertEquals(85.0, p.goalWeightKg, 0.0)
        assertEquals(0.0, p.paceKgPerWeek, 0.0)
        assertNull(valid.copy(goal = Goal.MAINTAIN, goalWeightKg = 40.0).weightError())
    }

    @Test fun blankNameIsAnError() {
        assertNotNull(valid.copy(name = "  ").nameError())
    }

    @Test fun goalNoteMentionsHealthyRange() {
        assertTrue(valid.goalNote()!!.contains("healthy"))
    }
}
