package com.bikeeyes.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingStabilizerTest {
    @Test
    fun circularMeanHandlesNorthBoundary() {
        val mean = HeadingStabilizer.circularMean(listOf(358f, 359f, 1f, 2f))
        assertEquals(0f, if (mean > 180f) mean - 360f else mean, 0.1f)
    }

    @Test
    fun gpsDeadbandSuppressesSmallChangeAfterResponsiveUpdate() {
        val stabilizer = HeadingStabilizer()
        assertEquals(10f, gps(stabilizer, 0L, 10f, 1L) ?: error("initial heading"), 0.01f)
        assertEquals(17.5f, gps(stabilizer, 300L, 25f, 2L) ?: error("responsive update"), 0.01f)
        assertNull(gps(stabilizer, 600L, 14f, 3L))
    }

    @Test
    fun stationaryStateFreezesHeadingDespiteSensorMovement() {
        val stabilizer = HeadingStabilizer()
        assertNull(
            stabilizer.update(0L, 0.1f, 5f, null, 1L, 180f)
        )
    }

    @Test
    fun abruptChangeMustPersistForOneSecond() {
        val stabilizer = HeadingStabilizer()
        assertEquals(0f, gps(stabilizer, 0L, 0f, 1L) ?: error("initial heading"), 0.01f)
        repeat(2) { index ->
            assertNull(gps(stabilizer, 300L + index * 200L, 100f, index + 2L))
        }
        val accepted = gps(stabilizer, 700L, 100f, 4L)
        assertEquals(100f, accepted ?: error("persistent direction should be accepted"), 0.01f)
    }

    private fun gps(
        stabilizer: HeadingStabilizer,
        nowMs: Long,
        bearing: Float,
        sampleId: Long
    ): Float? = stabilizer.update(
        nowMs = nowMs,
        speedMetersPerSecond = 4f,
        accuracyMeters = 5f,
        gpsBearingDegrees = bearing,
        gpsSampleId = sampleId,
        sensorBearingDegrees = null
    )
}
