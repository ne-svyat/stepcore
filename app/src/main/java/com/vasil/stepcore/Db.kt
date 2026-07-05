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

/**
 * Схема рассчитана на десятилетия:
 * - DayRecord: одна строка на день (~30 байт). 100 лет = 36500 строк.
 * - EventRecord: журнал смен режимов. photoUri заложен на будущее
 *   (фото-воспоминания): храним ссылку, не сам файл.
 * SQLite-формат стабилен с 2000 года; изменения схемы - через миграции Room.
 */
@Entity(tableName = "days")
data class DayRecord(
    @PrimaryKey val date: String,   // "2026-07-05"
    val walkSteps: Int = 0,
    val runSteps: Int = 0,
)

@Entity(tableName = "events")
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,               // System.currentTimeMillis()
    val date: String,               // для выборки по дню
    val text: String,               // "Ходьба", "Бег", "Покой"
    val photoUri: String? = null,   // задел на будущее
)

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: DayRecord)

    @Query("SELECT * FROM days WHERE date = :date")
    suspend fun day(date: String): DayRecord?

    @Query("SELECT * FROM days ORDER BY date DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<DayRecord>

    @Insert
    suspend fun addEvent(e: EventRecord)

    @Query("SELECT * FROM events WHERE date = :date ORDER BY timeMs DESC LIMIT 200")
    suspend fun eventsOfDay(date: String): List<EventRecord>
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
