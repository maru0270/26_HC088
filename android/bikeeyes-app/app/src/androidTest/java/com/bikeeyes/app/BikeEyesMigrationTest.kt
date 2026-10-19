package com.bikeeyes.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BikeEyesMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BikeEyesLocalDatabase::class.java,
        emptyList()
    )

    @Test
    fun migrate9To10_preservesRowsAndAddsCompositeIndex() {
        helper.createDatabase(TEST_DATABASE, 9).apply {
            execSQL(
                """
                INSERT INTO local_sync_logs (
                    event_uuid, ride_session_id, data_type, payload, sync_status,
                    retry_count, last_error_message, is_crash_related, created_at,
                    last_retry_at, synced_at, is_recovered, recovered_at,
                    next_retry_at, error_category, http_status
                ) VALUES (
                    '11111111-1111-1111-1111-111111111111',
                    '22222222-2222-2222-2222-222222222222',
                    'SENSOR_LOG', '{}', 'PENDING', 0, NULL, 0, 1000,
                    NULL, NULL, 0, NULL, NULL, NULL, NULL
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO ride_results (
                    ride_session_id, started_at, ended_at, elapsed_seconds,
                    total_distance_meters, average_speed_kmh, max_speed_kmh,
                    collected_frame_count, accident_count, sync_status
                ) VALUES (
                    '22222222-2222-2222-2222-222222222222',
                    1000, 2000, 1, 3.0, 10.8, 12.0, 1, 0, 'PENDING'
                )
                """.trimIndent()
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            10,
            true,
            BikeEyesLocalDatabase.MIGRATION_9_10
        )

        database.query(
            "SELECT event_uuid, data_type, payload FROM local_sync_logs"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "11111111-1111-1111-1111-111111111111",
                cursor.getString(0)
            )
            assertEquals("SENSOR_LOG", cursor.getString(1))
            assertEquals("{}", cursor.getString(2))
        }
        database.query(
            "SELECT ride_session_id, total_distance_meters FROM ride_results"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "22222222-2222-2222-2222-222222222222",
                cursor.getString(0)
            )
            assertEquals(3.0, cursor.getDouble(1), 0.0)
        }
        database.query(
            """
            SELECT COUNT(*) FROM sqlite_master
            WHERE type='index'
              AND name='index_local_sync_logs_event_uuid_data_type'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "bikeeyes-migration-test"
    }
}
