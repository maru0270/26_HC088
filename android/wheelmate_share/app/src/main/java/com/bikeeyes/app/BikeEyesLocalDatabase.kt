package com.bikeeyes.app

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocalSyncLogEntity::class,
        RideResultEntity::class,
        AccidentEventEntity::class,
        RiskZoneEntity::class,
        RideDeletionEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class BikeEyesLocalDatabase : RoomDatabase() {
    abstract fun localSyncLogDao(): LocalSyncLogDao
    abstract fun rideResultDao(): RideResultDao
    abstract fun accidentEventDao(): AccidentEventDao
    abstract fun riskZoneDao(): RiskZoneDao
    abstract fun rideDeletionDao(): RideDeletionDao

    companion object {
        @Volatile
        private var INSTANCE: BikeEyesLocalDatabase? = null

        fun getInstance(context: Context): BikeEyesLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BikeEyesLocalDatabase::class.java,
                    "bikeeyes_local.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ride_results (
                        ride_result_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER NOT NULL,
                        elapsed_seconds INTEGER NOT NULL,
                        total_distance_meters REAL NOT NULL,
                        average_speed_kmh REAL NOT NULL,
                        max_speed_kmh REAL NOT NULL,
                        collected_frame_count INTEGER NOT NULL,
                        accident_count INTEGER NOT NULL,
                        sync_status TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ride_results ADD COLUMN ride_session_id TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ride_results_ride_session_id " +
                            "ON ride_results(ride_session_id)"
                )
                db.execSQL(
                    "ALTER TABLE local_sync_logs ADD COLUMN ride_session_id TEXT"
                )
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS accident_events (accident_event_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event_uuid TEXT NOT NULL, ride_session_id TEXT, detected_at INTEGER NOT NULL, latitude REAL, longitude REAL, accuracy REAL, speed REAL, accel_x REAL NOT NULL, accel_y REAL NOT NULL, accel_z REAL NOT NULL, gyro_x REAL NOT NULL, gyro_y REAL NOT NULL, gyro_z REAL NOT NULL, acceleration_magnitude REAL NOT NULL, gyro_magnitude REAL NOT NULL, confirmation_status TEXT NOT NULL, is_false_positive INTEGER NOT NULL, created_at INTEGER NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_accident_events_event_uuid ON accident_events(event_uuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_accident_events_ride_session_id ON accident_events(ride_session_id)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE accident_events ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'PENDING'"
                )
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS risk_zones (
                        zone_id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        road_damage_type TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        radius_meters REAL NOT NULL,
                        detection_count INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        last_detected_at INTEGER NOT NULL,
                        is_mock INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM ride_results
                    WHERE ride_result_id NOT IN (
                        SELECT MAX(ride_result_id) FROM ride_results GROUP BY ride_session_id
                    )
                    """.trimIndent()
                )
                db.execSQL("DROP INDEX IF EXISTS index_ride_results_ride_session_id")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_ride_results_ride_session_id " +
                        "ON ride_results(ride_session_id)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ride_deletions (
                        ride_session_id TEXT NOT NULL PRIMARY KEY,
                        deleted_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_sync_logs_ride_session_id " +
                        "ON local_sync_logs(ride_session_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_sync_logs_sync_status_created_at " +
                        "ON local_sync_logs(sync_status, created_at)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ride_results_ended_at " +
                        "ON ride_results(ended_at)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ride_results_sync_status_ended_at " +
                        "ON ride_results(sync_status, ended_at)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_accident_events_detected_at " +
                        "ON accident_events(detected_at)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_accident_events_sync_status_detected_at " +
                        "ON accident_events(sync_status, detected_at)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sync_logs ADD COLUMN next_retry_at INTEGER")
                db.execSQL("ALTER TABLE local_sync_logs ADD COLUMN error_category TEXT")
                db.execSQL("ALTER TABLE local_sync_logs ADD COLUMN http_status INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_sync_logs_sync_status_next_retry_at_created_at " +
                        "ON local_sync_logs(sync_status, next_retry_at, created_at)"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_sync_logs_event_uuid_data_type " +
                        "ON local_sync_logs(event_uuid, data_type)"
                )
            }
        }
    }
}
