package com.bikeeyes.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BikeEyesRoomTest {
    private lateinit var database: BikeEyesLocalDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BikeEyesLocalDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun rideCanBeStoredObservedAndDeleted() = runBlocking {
        val entity = RideResultEntity(
            ride_session_id = "test-session",
            started_at = 1L,
            ended_at = 2L,
            elapsed_seconds = 1L,
            total_distance_meters = 3.0,
            average_speed_kmh = 4.0,
            max_speed_kmh = 5.0,
            collected_frame_count = 1,
            accident_count = 0,
            sync_status = "PENDING"
        )
        database.rideResultDao().insert(entity)
        assertEquals(1, database.rideResultDao().observeAll().first().size)
        database.rideResultDao().deleteBySessionId("test-session")
        assertEquals(0, database.rideResultDao().observeAll().first().size)
    }
}
