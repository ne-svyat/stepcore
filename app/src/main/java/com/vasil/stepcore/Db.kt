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
 * Сегмент 3: помеченный образец походки для будущего обучения уклону.
 * Признаки сглажены детектором; label - метка уклона, действовавшая в
 * момент шага. Прореженная выборка (см. StepService.terrainSampleEvery).
 */
@Entity(tableName = "terrain_samples")
data class TerrainSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val label: String,      // "UP" / "FLAT" / "DOWN"
    val mode: String,       // "WALK" / "RUN"
    val amp: Float,         // сглаженная вертикальная амплитуда
    val intervalMs: Float,  // сглаженный интервал шага, мс
    val gyro: Float,        // RMS гироскопа

    // --- L1 (featureVersion = 2). Все поля nullable: null означает
    // "не измеряли", а не "измерили ноль". Старые строки остаются с
    // featureVersion = 1 и null во всех новых колонках.
    // Правило: пишем только то, что НЕЛЬЗЯ восстановить из уже
    // записанного. Профиль момента берётся по timeMs через
    // profileAt(); час и день недели - через strftime.
    val featureVersion: Int = 1,
    // Ориентация телефона из сглаженного вектора гравитации
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    // Гироскоп по осям (RMS). Ось наибольшей вариации - производная,
    // считается при обучении, здесь не хранится.
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    // Асимметрия чётных/нечётных шагов серии, окно 8
    val ampEvenMed: Float? = null,
    val ampOddMed: Float? = null,
    val intervalEvenMed: Float? = null,
    val intervalOddMed: Float? = null,
    // Регулярность ритма, окно 32
    val ampMed: Float? = null,
    val ampIqr: Float? = null,
    val intervalMed: Float? = null,
    val intervalIqr: Float? = null,
    val windowN: Int? = null,   // сколько шагов реально было в окне
    // Непрерывная серия движения
    val seriesSteps: Int? = null,
    val seriesMs: Long? = null,
    // Контекст. screenOn до релиза L1.1 всегда true: обработчик
    // акселерометра при выключенном экране выходит сразу.
    val screenOn: Boolean? = null,
    // Сколько шагов насчитал чип с прошлого образца - честность даром
    val chipDelta: Int? = null,

    // --- v185: независимый канал акселерометра ---
    // Считается из сырого сигнала, БЕЗ вето детектора по гироскопу.
    // В кармане детектор молчит, и это единственный источник амплитуды
    // и каденса - то есть главных признаков уклона.
    val accRms: Float? = null,      // средняя энергия шага
    val accP90: Float? = null,      // типичный пик без выбросов
    val accMax: Float? = null,      // самый сильный удар (у спуска выше)
    val zcrCadence: Float? = null,  // каденс по пересечениям нуля, шаг/с
    val sampleHz: Float? = null,    // фактическая частота сенсора
    // 0 = строка от детектора (amp/intervalMs измерены),
    // 1 = строка от чипа: детектор молчал, amp/intervalMs НЕ измерялись
    //     и записаны нулями. Амплитуду и каденс для таких строк брать
    //     из accRms/accP90/zcrCadence.
    val sampleSource: Int = 0,
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

    // --- корпус уклона (Сегмент 3) ---
    @Insert
    suspend fun insertSample(s: TerrainSample)

    @Query("SELECT COUNT(*) FROM terrain_samples")
    suspend fun countSamples(): Int

    /** L1: сколько образцов уже собрано в расширенной схеме. */
    /** v188: срез корпуса для экрана. Схема не меняется - только чтение. */
    @Query("SELECT COUNT(*) FROM terrain_samples WHERE featureVersion >= 3")
    suspend fun countSamplesV3(): Int

    @Query("SELECT COUNT(*) FROM terrain_samples WHERE featureVersion >= 3 AND sampleSource = 1")
    suspend fun countSamplesChip(): Int

    @Query("SELECT COUNT(*) FROM terrain_samples WHERE featureVersion >= 3 AND label = :label")
    suspend fun countSamplesLabel(label: String): Int

    // --- L2: сессии (v196) ---
    @Insert
    suspend fun insertSession(s: SessionRecord)

    @Query("SELECT COALESCE(MAX(builtFromMaxTimeMs), 0) FROM sessions")
    suspend fun lastBuiltTimeMs(): Long

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM terrain_samples WHERE featureVersion >= 3 AND timeMs > :afterMs ORDER BY timeMs ASC")
    suspend fun samplesAfter(afterMs: Long): List<TerrainSample>

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun countSessions(): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE reliable = 1")
    suspend fun countSessionsReliable(): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE label != 'FLAT'")
    suspend fun countSessionsIncline(): Int

    @Query("SELECT COUNT(*) FROM sessions WHERE label != 'FLAT' AND reliable = 1")
    suspend fun countSessionsInclineReliable(): Int

    // L3.0: самая свежая надёжная уклонная сессия, про которую ещё не спрашивали.
    @Query("SELECT * FROM sessions WHERE reliable = 1 AND confirmState = 0 " +
        "AND label != 'FLAT' ORDER BY endMs DESC LIMIT 1")
    suspend fun latestUnaskedIncline(): SessionRecord?

    // L3.0: записать исход вопроса в три архива (1=подтв, 2=дефект, 3=серая зона).
    @Query("UPDATE sessions SET confirmState = :state WHERE id = :id")
    suspend fun setSessionConfirm(id: Long, state: Int)

    @Query("SELECT COUNT(*) FROM terrain_samples WHERE featureVersion >= 2")
    suspend fun countSamplesV2(): Int
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

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS terrain_samples (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "timeMs INTEGER NOT NULL, " +
                "label TEXT NOT NULL, " +
                "mode TEXT NOT NULL, " +
                "amp REAL NOT NULL, " +
                "intervalMs REAL NOT NULL, " +
                "gyro REAL NOT NULL)"
        )
    }
}

/**
 * L1: расширение корпуса походки. Только ADD COLUMN - ни одна
 * существующая строка не переписывается. Старые образцы остаются
 * с featureVersion = 1 и NULL в новых полях.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN featureVersion INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN pitchDeg REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN rollDeg REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN gyroX REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN gyroY REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN gyroZ REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN ampEvenMed REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN ampOddMed REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN intervalEvenMed REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN intervalOddMed REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN ampMed REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN ampIqr REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN intervalMed REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN intervalIqr REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN windowN INTEGER")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN seriesSteps INTEGER")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN seriesMs INTEGER")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN screenOn INTEGER")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN chipDelta INTEGER")
    }
}

/**
 * v185: независимый канал акселерометра в корпусе. Только ADD COLUMN.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN accRms REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN accP90 REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN accMax REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN zcrCadence REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN sampleHz REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN sampleSource INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v186: удаление строк корпуса, записанных при выключенном экране по
 * каналу чипа. У них признаки взяты из момента ДО блокировки экрана,
 * а метка уклона - текущая. Схема не меняется, только чистка.
 *
 * Условие узкое намеренно: sampleSource = 1 И screenOn = 0. Строки
 * детектора и строки чипа при включённом экране честны и остаются.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sessions (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, " +
            "durationMs INTEGER NOT NULL, label TEXT NOT NULL, " +
            "nSamples INTEGER NOT NULL, reliable INTEGER NOT NULL, " +
            "walkShare REAL NOT NULL, runShare REAL NOT NULL, " +
            "ampMed REAL, ampIqr REAL, cadenceMed REAL, cadenceIqr REAL, " +
            "pitchMed REAL, gyroMed REAL, chipShare REAL NOT NULL, " +
            "featureVersion INTEGER NOT NULL, ampTrend REAL, cadenceTrend REAL, " +
            "rhythmStab REAL, pitchRange REAL, confirmState INTEGER NOT NULL, " +
            "builtFromMaxTimeMs INTEGER NOT NULL)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM terrain_samples WHERE sampleSource = 1 AND screenOn = 0")
    }
}

// ==================== L2: сессии (v196) ====================
// Витрина сессий: образцы корпуса сворачиваются в непрерывные куски
// движения. Обучение работает по сессиям, а не по одиночным образцам.
// Три слоя: что было / как выглядело / неочевидный задел. Плюс
// confirmState - пустой задел под три архива L3 (подтверждено/дефект/
// не подтверждено). Медиана и IQR, не среднее: выброс не искажает.
@Entity(tableName = "sessions")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // --- слой 1: что это было ---
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val label: String,            // UP / FLAT / DOWN
    val nSamples: Int,
    val reliable: Boolean,        // false для коротких - не выбрасываем
    val walkShare: Float,         // доля WALK
    val runShare: Float,          // доля RUN
    // --- слой 2: как выглядело движение ---
    val ampMed: Float? = null,
    val ampIqr: Float? = null,
    val cadenceMed: Float? = null,
    val cadenceIqr: Float? = null,
    val pitchMed: Float? = null,
    val gyroMed: Float? = null,
    val chipShare: Float = 0f,    // доля строк от чипа (карман)
    val featureVersion: Int = 3,
    // --- слой 3: неочевидный задел на будущее ---
    val ampTrend: Float? = null,      // наклон амплитуды (устал в гору?)
    val cadenceTrend: Float? = null,
    val rhythmStab: Float? = null,    // IQR каденса / медиана (ровность)
    val pitchRange: Float? = null,    // размах наклона (менял хват?)
    // --- задел под L3 ---
    // 0 = не спрошено, 1 = подтверждено, 2 = дефект, 3 = не подтверждено.
    // Наполнится активным обучением. Сейчас всегда 0.
    val confirmState: Int = 0,
    // прослеживаемость: по краям можно поднять исходные образцы
    val builtFromMaxTimeMs: Long = 0  // до какого образца корпус уже свёрнут
)

@Database(entities = [DayRecord::class, EventRecord::class, HourRecord::class, ProfileSnapshotRecord::class, TerrainSample::class, SessionRecord::class],
    version = 11, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StepDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "stepcore.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build().also { instance = it }
            }
    }
}
