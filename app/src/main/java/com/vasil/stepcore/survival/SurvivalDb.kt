package com.vasil.stepcore.survival

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

/**
 * Survival Mode живёт в ОТДЕЛЬНОМ файле БД (survival.db), не в AppDb.
 *
 * Почему не миграция stepcore.db 5 -> 6:
 * - сломанная миграция режима не может помешать открыться базе шагомера
 *   (правило изоляции: падение симуляции не задевает счёт шагов);
 * - ядро остаётся на своей версии схемы, релиз режима не требует
 *   обязательного экспорта перед установкой;
 * - транзакции режима не конкурируют за writer-блокировку с записью шагов.
 */
@Entity(tableName = "expeditions")
data class Expedition(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seed: Long,             // корень детерминизма: мир = f(seed, тик)
    val engineVersion: Int,     // версия правил мира на момент старта
    val region: String,         // "taiga" (задел на будущие регионы)
    val startSeason: Int,       // 0 зима - 1 весна - 2 лето - 3 осень
    val startOffset: Int,       // день внутри стартового сезона (10..50, из seed)
    val plannedDays: Int,       // план экспедиции в днях мира
    val stepsPerTick: Int,      // темп: реальных шагов на один день мира
    val status: String,         // active / done_success / done_voluntary
    val createdMs: Long,
    val finishedMs: Long = 0L,
    val ticksDone: Int = 0,     // прожито дней мира
    val syncDate: String,       // до какой даты шаги съедены
    val syncDaySteps: Int = 0,  // съедено шагов этой даты
    val stepRemainder: Int = 0, // несожжённый остаток (< stepsPerTick)
)

@Entity(tableName = "expedition_events", indices = [Index("expeditionId")])
data class ExpeditionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expeditionId: Long,
    val tick: Int,              // день мира (0 = прибытие в лагерь)
    val realTimeMs: Long,       // когда строка попала в журнал (реальное время)
    val category: String,       // milestone / weather / ambient / system
    val text: String,           // уже отрендеренный текст: прошлое неизменно
)

@Dao
interface SurvivalDao {
    @Insert
    suspend fun insertExpedition(e: Expedition): Long

    @Update
    suspend fun updateExpedition(e: Expedition)

    @Insert
    suspend fun insertEvents(events: List<ExpeditionEvent>)

    @Query("SELECT * FROM expeditions WHERE status = 'active' ORDER BY id DESC LIMIT 1")
    suspend fun active(): Expedition?

    @Query("SELECT * FROM expeditions WHERE id = :id")
    suspend fun byId(id: Long): Expedition?

    @Query("SELECT * FROM expeditions WHERE status != 'active' ORDER BY id DESC")
    suspend fun archive(): List<Expedition>

    @Query("SELECT * FROM expedition_events WHERE expeditionId = :id ORDER BY tick DESC, id DESC")
    suspend fun eventsOf(id: Long): List<ExpeditionEvent>
}

@Database(entities = [Expedition::class, ExpeditionEvent::class],
    version = 1, exportSchema = false)
abstract class SurvivalDb : RoomDatabase() {
    abstract fun dao(): SurvivalDao

    companion object {
        @Volatile private var instance: SurvivalDb? = null
        fun get(context: Context): SurvivalDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, SurvivalDb::class.java, "survival.db"
                ).build().also { instance = it }
            }
    }
}
