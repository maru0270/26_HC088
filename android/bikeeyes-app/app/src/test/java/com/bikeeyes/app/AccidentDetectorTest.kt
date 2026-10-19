package com.bikeeyes.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AccidentDetectorTest {
    private fun createFallCandidate(detector: AccidentDetector, advance: () -> Unit) {
        detector.update(0.0, 9.80665, 0.0, 0.0, 0.0, 0.0)
        advance()
        detector.update(36.0, 0.0, 0.0, 4.0, 0.0, 0.0)
        advance()
        detector.update(9.80665, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    @Test
    fun impactWhileContinuingToMoveDoesNotAlert() {
        var now = 60_001L
        var detections = 0
        val detector = AccidentDetector({ detections += 1 }, { now })

        createFallCandidate(detector) { now += 100L }
        repeat(7) {
            now += 1_000L
            detector.updateLocation(35.1800 + it * 0.0001, 128.1000, 5.0, 4.0)
        }

        assertEquals(0, detections)
    }

    @Test
    fun impactFollowedByThirtySecondsStationaryAlertsOnce() {
        var now = 60_001L
        var detections = 0
        val detector = AccidentDetector({ detections += 1 }, { now })

        createFallCandidate(detector) { now += 100L }
        repeat(31) {
            detector.updateLocation(35.1800, 128.1000, 5.0, 0.1)
            now += 1_000L
        }

        assertEquals(1, detections)
    }

    @Test
    fun measuredWeakFallThresholdStillAlerts() {
        var now = 60_001L
        var detections = 0
        val detector = AccidentDetector({ detections += 1 }, { now })

        detector.update(0.0, 9.80665, 0.0, 0.0, 0.0, 0.0)
        now += 100L
        detector.update(15.3, 0.0, 0.0, 4.0, 0.0, 0.0)
        now += 100L
        detector.update(9.80665, 0.0, 0.0, 0.0, 0.0, 0.0)
        repeat(31) {
            detector.updateLocation(35.1800, 128.1000, 5.0, 0.1)
            now += 1_000L
        }

        assertEquals(1, detections)
    }

    @Test
    fun impactWithoutSixtyDegreeOrientationChangeDoesNotAlert() {
        var now = 60_001L
        var detections = 0
        val detector = AccidentDetector({ detections += 1 }, { now })

        detector.update(0.0, 9.80665, 0.0, 0.0, 0.0, 0.0)
        now += 100L
        detector.update(36.0, 0.0, 0.0, 4.0, 0.0, 0.0)
        now += 100L
        detector.update(0.0, 9.80665, 0.0, 0.0, 0.0, 0.0)
        repeat(31) {
            detector.updateLocation(35.1800, 128.1000, 5.0, 0.1)
            now += 1_000L
        }

        assertEquals(0, detections)
    }

    @Test
    fun impactFollowedByOnlyTwentyNineSecondsStationaryDoesNotAlert() {
        var now = 60_001L
        var detections = 0
        val detector = AccidentDetector({ detections += 1 }, { now })

        createFallCandidate(detector) { now += 100L }
        repeat(30) {
            detector.updateLocation(35.1800, 128.1000, 5.0, 0.1)
            now += 1_000L
        }

        assertEquals(0, detections)
    }

    @Test
    fun impactWithPoorGpsDoesNotAlert() {
        var now = 60_001L
        var detections = 0
        val detector = AccidentDetector({ detections += 1 }, { now })

        createFallCandidate(detector) { now += 100L }
        repeat(10) {
            detector.updateLocation(35.1800, 128.1000, 80.0, 0.0)
            now += 1_000L
        }

        assertEquals(0, detections)
    }

    @Test
    fun ordinaryImpactFollowedByLaterStopDoesNotAlert() {
        var now = 60_001L
        var detections = 0
        val detector = AccidentDetector({ detections += 1 }, { now })

        createFallCandidate(detector) { now += 100L }
        repeat(5) {
            detector.updateLocation(35.1800 + it * 0.00001, 128.1000, 5.0, 1.0)
            now += 1_000L
        }
        repeat(10) {
            detector.updateLocation(35.1801, 128.1000, 5.0, 0.0)
            now += 1_000L
        }

        assertEquals(0, detections)
    }
}
