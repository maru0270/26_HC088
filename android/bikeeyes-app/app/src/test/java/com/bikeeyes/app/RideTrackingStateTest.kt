package com.bikeeyes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackingStateTest {
    @Test
    fun finishRideUsesElapsedTimeAndFiltersUnrealisticMaxSpeed() {
        RideTrackingState.startRide(1_000L)
        RideTrackingState.updateElapsedSeconds(60L)
        RideTrackingState.updateTotalDistanceMeters(1_000.0)
        RideTrackingState.updateCurrentSpeedKmh(100.0, 5f)
        RideTrackingState.updateCurrentSpeedKmh(20.0, 5f)

        val result = RideTrackingState.finishRide(RideSyncStatus.PENDING)

        assertEquals(60.0, result.averageSpeedKmh, 0.001)
        assertEquals(20.0, result.maxSpeedKmh, 0.001)
        assertTrue(result.rideSessionId.isNotBlank())
    }
}
