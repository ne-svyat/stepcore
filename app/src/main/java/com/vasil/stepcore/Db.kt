package com.vasil.stepcore

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "days")
data class DayRecord(
    @PrimaryKey val date: String,
    val walkSteps: Int = 0,
    val runSteps: Int = 0,
)

@Entity(tableName = "events")
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val date: String,
    val text: String,
    val photoUri: String? = null,
)

/** Почасовая агрегация для внутридневного timeline. Ключ: "2026-07-06 14". */
@Entity(tableName = "hours")
data class HourRecord(
    @PrimaryKey val dateHour: String,
    val walkSteps: Int = 0,
    val runSteps: Int = 0,
)

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: DayRecord)

    @Query("SELECT * FROM days WHERE date = :date")
    suspend fun day(date: String): DayRecord?

    @Query("SELECT * FROM days ORDER BY date DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<DayRecord>

    @Query("SELECT * FROM days ORDER BY date DESC")
    suspend fun allDays(): List<DayRecord>

    @Insert
    suspend fun addEvent(e: EventRecord)

    @Query("SELECT * FROM events WHERE date = :date ORDER BY timeMs ASC")
    suspend fun eventsOfDay(date: String): List<EventRecord>

    @Query("SELECT * FROM events ORDER BY timeMs ASC")
    suspend fun allEvents(): List<EventRecord>

    @Query("DELETE FROM days")
    suspend fun deleteAllDays()

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()

    // --- почасовые ---
    @Query("INSERT OR IGNORE INTO hours(dateHour, walkSteps, runSteps) VALUES(:k, 0, 0)")
    suspend fun ensureHour(k: String)

    @Query("UPDATE hours SET walkSteps = walkSteps + :w, runSteps = runSteps + :r WHERE dateHour = :k")
    suspend fun addHour(k: String, w: Int, r: Int)

    @Query("SELECT * FROM hours WHERE dateHour LIKE :dayPrefix || '%' ORDER BY dateHour ASC")
    suspend fun hoursOfDay(dayPrefix: String): List<HourRecord>

    @Query("DELETE FROM hours")
    suspend fun deleteAllHours()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS hours (" +
                "dateHour TEXT NOT NULL PRIMARY KEY, " +
                "walkSteps INTEGER NOT NULL DEFAULT 0, " +
                "runSteps INTEGER NOT NULL DEFAULT 0)"
        )
    }
}

@Database(entities = [DayRecord::class, EventRecord::class, HourRecord::class],
    version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StepDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "stepcore.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
