package be.lain.bloom

import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TrendTest {
    private val d0 = LocalDate.of(2026, 1, 1)

    @Test fun constantWeightGivesFlatTrend() {
        val w = (0..20).map { WeightPoint(d0.plusDays(it.toLong()), 80.0) }
        assertEquals(80.0, Trend.at(w, d0.plusDays(20))!!, 1e-9)
        assertEquals(0.0, Trend.weeklyRate(w, d0.plusDays(20))!!, 1e-9)
    }

    @Test fun missingDaysCarryTrendForward() {
        val w = listOf(WeightPoint(d0, 80.0), WeightPoint(d0.plusDays(10), 79.0))
        val pts = Trend.daily(w)
        assertEquals(11, pts.size)
        assertEquals(80.0, pts[5].trend, 1e-9)
        assertEquals(79.9, pts.last().trend, 1e-9)
    }

    @Test fun sameDayWeighInsAreAveraged() {
        val w = listOf(WeightPoint(d0, 80.0), WeightPoint(d0, 81.0))
        assertEquals(80.5, Trend.daily(w).single().trend, 1e-9)
    }

    @Test fun steadyLossRateIsDetected() {
        // 0.5 kg/week loss with daily noise of ±0.4 kg.
        val noise = listOf(0.4, -0.3, 0.1, -0.4, 0.2, 0.0, -0.2)
        val w = (0..60).map { WeightPoint(d0.plusDays(it.toLong()), 85.0 - it * 0.5 / 7.0 + noise[it % 7]) }
        val rate = Trend.weeklyRate(w, d0.plusDays(60), days = 21)!!
        assertEquals(-0.5, rate, 0.08)
    }

    @Test fun rateNeedsTwoWeighIns() {
        assertNull(Trend.weeklyRate(listOf(WeightPoint(d0, 80.0)), d0))
    }
}
