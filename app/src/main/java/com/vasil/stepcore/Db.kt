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
}

@Database(entities = [DayRecord::class, EventRecord::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StepDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "stepcore.db"
                ).build().also { instance = it }
            }
    }
}
