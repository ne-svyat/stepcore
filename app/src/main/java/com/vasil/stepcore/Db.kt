package com.vasil.stepcore

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
    // Снапшот энергии/дистанции (V9.9): замораживается при ЗАКРЫТИИ дня
    // с параметрами того дня, чтобы смена веса не пересчитывала прошлое.
    // -1 = снапшота нет (день ещё открыт или создан до V9.9).
    val kcalActive: Int = -1,
    val kcalBasal: Int = -1,
    val distanceM: Int = -1,
    // Активное время дня, сек (V11.9). Замораживается вместе с калориями:
    // раньше считалось на лету из ТЕКУЩЕЙ калибровки темпа, и новая
    // калибровка переписывала время всех прошлых дней (наблюдалось в
    // реальности: 6ч05м -> 9ч43м после мусорной калибровки 774 мс).
    val activeSec: Int = -1,
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
    // Сегмент 2: сколько шагов часа помечено уклоном (flat = total-up-down).
    val upSteps: Int = 0,
    val downSteps: Int = 0,
)

/**
 * Точка истории профиля (V11): какие параметры действовали НАЧИНАЯ с
 * timestampMs. Пишется при сохранении Профиля и при каждой калибровке.
 */
@Entity(tableName = "profile_history", indices = [Index(value = ["timestampMs"])])
data class ProfileSnapshotRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val weightKg: Float,
    val loadKg: Float,
    val heightCm: Int,
    val age: Int,
    val male: Boolean,
    val walkMinIntervalMs: Long,
    val walkMaxIntervalMs: Long,
    val runMinIntervalMs: Long,
    val runMaxIntervalMs: Long,
    val strideA: Float,
    val strideB: Float,
    val strideManual: Boolean,
    val strideByGps: Boolean,
)

/** Проекция: номер часа (0-23) и число событий в нём (V9.4). */
data class HourCount(val hour: Int, val cnt: Int)

/** Проекция месяца для верхнего уровня Истории (V9.6). */
data class MonthAgg(val ym: String, val walk: Int, val run: Int, val days: Int)

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: DayRecord)

    /**
     * Вставка дня ТОЛЬКО если его нет (V11.16, импорт). Импорт никогда не
     * перезаписывает существующие данные - REPLACE тут был бы потерей.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDayIfAbsent(day: DayRecord)

    @Query("SELECT * FROM days WHERE date = :date")
    suspend fun day(date: String): DayRecord?

    @Query("SELECT * FROM days ORDER BY date DESC LIMIT :limit")
    suspend fun recentDays(limit: Int): List<DayRecord>

    @Query("SELECT * FROM days ORDER BY date DESC")
    suspend fun allDays(): List<DayRecord>

    /** Месяцы с суммами - верхний уровень Истории (V9.6). "yyyy-MM". */
    @Query("SELECT substr(date,1,7) AS ym, SUM(walkSteps) AS walk, " +
           "SUM(runSteps) AS run, COUNT(*) AS days FROM days " +
           "GROUP BY ym ORDER BY ym DESC")
    suspend fun months(): List<MonthAgg>

    /** Дни одного месяца (префикс "yyyy-MM"). */
    @Query("SELECT * FROM days WHERE date LIKE :ym || '%' ORDER BY date DESC")
    suspend fun daysOfMonth(ym: String): List<DayRecord>

    /** Закрытые дни без снапшота энергии - для одноразового бэкфилла (V9.19). */
    @Query("SELECT * FROM days WHERE kcalActive < 0 AND date < :today")
    suspend fun daysWithoutSnapshot(today: String): List<DayRecord>

    /** Автоочистка диаг-логов старше cutoffMs (V9.6). Суммы не трогаются. */
    @Query("DELETE FROM events WHERE text LIKE '[диаг]%' AND timeMs < :cutoffMs")
    suspend fun purgeOldDiagLogs(cutoffMs: Long): Int

    @Insert
    suspend fun addEvent(e: EventRecord)

    @Query("SELECT * FROM events WHERE date = :date ORDER BY timeMs ASC")
    suspend fun eventsOfDay(date: String): List<EventRecord>

    /** События за диапазон времени - для ленивой загрузки по часу (V9.4). */
    @Query("SELECT * FROM events WHERE timeMs >= :fromMs AND timeMs < :toMs ORDER BY timeMs ASC")
    suspend fun eventsInRange(fromMs: Long, toMs: Long): List<EventRecord>

    /** Счётчики событий по часу дня - чтобы показать только непустые часы (V9.4). */
    @Query("SELECT CAST(strftime('%H', timeMs/1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, " +
           "COUNT(*) AS cnt FROM events WHERE date = :date GROUP BY hour ORDER BY hour ASC")
    suspend fun eventHourCounts(date: String): List<HourCount>

    @Query("SELECT * FROM events ORDER BY timeMs ASC")
    suspend fun allEvents(): List<EventRecord>

    @Query("DELETE FROM days")
    suspend fun deleteAllDays()

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()

    // --- почасовые ---
    @Query("INSERT OR IGNORE INTO hours(dateHour, walkSteps, runSteps) VALUES(:k, 0, 0)")
    suspend fun ensureHour(k: String)

    @Query("UPDATE hours SET walkSteps = walkSteps + :w, runSteps = runSteps + :r, upSteps = upSteps + :up, downSteps = downSteps + :down WHERE dateHour = :k")
    suspend fun addHour(k: String, w: Int, r: Int, up: Int, down: Int)

    @Query("SELECT * FROM hours WHERE dateHour LIKE :dayPrefix || '%' ORDER BY dateHour ASC")
    suspend fun hoursOfDay(dayPrefix: String): List<HourRecord>

    /** Вся почасовая таблица - для полного бэкапа (V11.15). */
    @Query("SELECT * FROM hours ORDER BY dateHour ASC")
    suspend fun allHours(): List<HourRecord>

    /** Вставка часа только если его нет (V11.16, импорт). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHourIfAbsent(h: HourRecord)

    /** Времена всех событий - дедупликация при импорте (V11.16). */
    @Query("SELECT timeMs FROM events")
    suspend fun allEventTimes(): List<Long>

    @Query("DELETE FROM hours")
    suspend fun deleteAllHours()

    // --- история профиля (V11) ---
    @Insert
    suspend fun insertProfileSnapshot(p: ProfileSnapshotRecord)

    @Query("SELECT * FROM profile_history WHERE timestampMs <= :atMs ORDER BY timestampMs DESC LIMIT 1")
    suspend fun profileAt(atMs: Long): ProfileSnapshotRecord?

    /**
     * Самая ранняя точка истории. Нужна как якорь для часов, прожитых ДО
     * первой записи (переход на V11): они замораживаются на первом известном
     * профиле, а не плывут вслед за текущим.
     */
    @Query("SELECT * FROM profile_history ORDER BY timestampMs ASC LIMIT 1")
    suspend fun earliestProfile(): ProfileSnapshotRecord?
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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE days ADD COLUMN kcalActive INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE days ADD COLUMN kcalBasal INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE days ADD COLUMN distanceM INTEGER NOT NULL DEFAULT -1")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS profile_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "timestampMs INTEGER NOT NULL, " +
                "weightKg REAL NOT NULL, " +
                "loadKg REAL NOT NULL, " +
                "heightCm INTEGER NOT NULL, " +
                "age INTEGER NOT NULL, " +
                "male INTEGER NOT NULL, " +
                "walkMinIntervalMs INTEGER NOT NULL, " +
                "walkMaxIntervalMs INTEGER NOT NULL, " +
                "runMinIntervalMs INTEGER NOT NULL, " +
                "runMaxIntervalMs INTEGER NOT NULL, " +
                "strideA REAL NOT NULL, " +
                "strideB REAL NOT NULL, " +
                "strideManual INTEGER NOT NULL, " +
                "strideByGps INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_profile_history_timestampMs ON profile_history(timestampMs)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE days ADD COLUMN activeSec INTEGER NOT NULL DEFAULT -1")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hours ADD COLUMN upSteps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE hours ADD COLUMN downSteps INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [DayRecord::class, EventRecord::class, HourRecord::class, ProfileSnapshotRecord::class],
    version = 6, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StepDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "stepcore.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
            }
    }
}
