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
    // v220. Живой каденс часа как ВЗВЕШЕННАЯ сумма интервалов шага:
    // cadenceIntervalSum = Σ(intervalMs), cadenceStepSum = число этих шагов.
    // Медианный интервал = sum/count -> каденс = 1000/интервал. Взвешивание
    // по шагам, а не среднее средних: медленная минута не перевесит быструю.
    // 0 в обоих значит "детектор не мерил" (карман) -> откат на константу.
    val cadenceIntervalSum: Long = 0,
    val cadenceStepSum: Int = 0,
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
    // v213. Азимут УСТРОЙСТВА (0..360, магнитный север), не направление
    // ходьбы: в кармане телефон лежит произвольно. Ценность - в изменении
    // азимута между образцами: поворот тела виден в дельте.
    val headingDeg: Float? = null,
    // Точность магнитометра, как её сообщает система (0 ненадёжно .. 3 высокая).
    // Без неё нельзя отличить честный курс от вранья рядом с металлом.
    val headingAcc: Int? = null,
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

    @Query("UPDATE hours SET walkSteps = walkSteps + :w, runSteps = runSteps + :r, upSteps = upSteps + :up, downSteps = downSteps + :down, cadenceIntervalSum = cadenceIntervalSum + :cadSum, cadenceStepSum = cadenceStepSum + :cadN WHERE dateHour = :k")
    suspend fun addHour(k: String, w: Int, r: Int, up: Int, down: Int, cadSum: Long, cadN: Int)

    @Query("SELECT * FROM hours WHERE dateHour LIKE :dayPrefix || '%' ORDER BY dateHour ASC")
    suspend fun hoursOfDay(dayPrefix: String): List<HourRecord>

    // v301. Диагностика пустого Timeline. Экран, который показывает нули,
    // обязан уметь объяснить, ПОЧЕМУ они нули: сам факт «пусто» ничего не
    // говорит - данных нет, или они есть, но не читаются.
    @Query("SELECT COUNT(*) FROM hours")
    suspend fun countHoursAll(): Int

    @Query("SELECT COUNT(*) FROM hours WHERE dateHour LIKE :dayPrefix || '%'")
    suspend fun countHoursOfDay(dayPrefix: String): Int

    @Query("SELECT dateHour FROM hours ORDER BY dateHour DESC LIMIT 3")
    suspend fun lastHourKeys(): List<String>

    // v304. Дамп таблиц как есть. Косвенные признаки отвечали на вопрос
    // «сколько строк», но не на вопрос «что в них лежит»: ключ, нули или
    // значения, тот ли день. Источник правды - сама база, поэтому
    // показываем строки целиком, а не выводы о них.
    @Query("SELECT * FROM hours ORDER BY dateHour DESC LIMIT 20")
    suspend fun dumpHours(): List<HourRecord>

    @Query("SELECT * FROM days ORDER BY date DESC LIMIT 7")
    suspend fun dumpDays(): List<DayRecord>

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

    // v298. То же, но только среди годных точек.
    @Query("SELECT * FROM profile_history WHERE timestampMs <= :atMs " +
        "AND weightKg > 0 ORDER BY timestampMs DESC LIMIT 1")
    suspend fun profileAtUsable(atMs: Long): ProfileSnapshotRecord?

    /**
     * Самая ранняя точка истории. Нужна как якорь для часов, прожитых ДО
     * первой записи (переход на V11): они замораживаются на первом известном
     * профиле, а не плывут вслед за текущим.
     */
    @Query("SELECT * FROM profile_history ORDER BY timestampMs ASC LIMIT 1")
    suspend fun earliestProfile(): ProfileSnapshotRecord?

    // v298. Самая ранняя ГОДНАЯ точка. Точка с нулевым весом не является
    // профилем - по ней нельзя посчитать ни калории, ни дистанцию.
    @Query("SELECT * FROM profile_history WHERE weightKg > 0 " +
        "ORDER BY timestampMs ASC LIMIT 1")
    suspend fun earliestUsableProfile(): ProfileSnapshotRecord?

    // v298. Разовая чистка: негодные точки, попавшие в историю до этой
    // версии. Не удаляем чужое молча - удаляем ровно то, что нельзя
    // использовать и что мешает считать день.
    @Query("DELETE FROM profile_history WHERE weightKg <= 0")
    suspend fun purgeUnusableProfiles(): Int

    // --- корпус уклона (Сегмент 3) ---
    @Insert
    suspend fun insertSample(s: TerrainSample)

    @Query("SELECT COUNT(*) FROM terrain_samples")
    suspend fun countSamples(): Int

    // v269: ровные карманные строки - из них берётся якорь "ровно".
    // Отдельно ходить по ровному незачем: этих данных в корпусе сотни.
    @Query("SELECT * FROM terrain_samples WHERE label = 'FLAT' " +
        "AND sampleSource = 1 ORDER BY timeMs DESC LIMIT 400")
    suspend fun flatPocketSamples(): List<TerrainSample>

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

    // v287. Импорт витрины сессий из файла экспорта. Нужен, чтобы вернуть
    // ответы человека (confirmState, userLabel) после переустановки:
    // бэкап их не содержит, а собраны они месяцами ходьбы.
    // Дедупликация по startMs - повторный импорт того же файла не плодит
    // копий, а существующие строки НИКОГДА не перезаписываются: локальное
    // всегда главнее файла, как и в импорте бэкапа.
    @Query("SELECT startMs FROM sessions")
    suspend fun allSessionStarts(): List<Long>

    // v288. Полный бэкап. Раньше в файл уходили только дни, часы и события,
    // а корпус и сессии считались "данными устройства" и НЕ сохранялись.
    // Цена этого решения выяснилась на переустановке: месяцы разметки
    // исчезли, хотя весь смысл проекта - в накопленных данных.
    // Правило теперь простое: раз всё лежит офлайн и никуда не уходит,
    // в бэкап идёт ВСЁ, что нельзя собрать задним числом.
    @Query("SELECT * FROM sessions ORDER BY startMs")
    suspend fun allSessionsForBackup(): List<SessionRecord>

    @Query("SELECT * FROM terrain_samples ORDER BY timeMs")
    suspend fun allSamplesForBackup(): List<TerrainSample>

    @Query("SELECT timeMs FROM terrain_samples")
    suspend fun allSampleTimes(): List<Long>

    @Query("SELECT COALESCE(MAX(builtFromMaxTimeMs), 0) FROM sessions")
    suspend fun lastBuiltTimeMs(): Long

    // v223: докуда реально дошёл корпус. Больше lastBuiltTimeMs - сессии
    // устарели, на карте по-сессиям не хватает свежих прогулок.
    @Query("SELECT COALESCE(MAX(timeMs), 0) FROM terrain_samples")
    suspend fun maxSampleTimeMs(): Long

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

    // v283. ВОРОТА ОДНОРОДНОСТИ.
    // Сессия - это ОДИН кусок движения. Если внутри неё и равнина, и склон,
    // она не пример ни одного класса: подтвердить её целиком значит соврать.
    // Порог не выбран на вкус - в данных есть пустой промежуток. Отношение
    // разброса амплитуды к её медиане у 55 подтверждённых сессий идёт так:
    // 0.02 0.03 ... 0.08 0.09, затем ПУСТО, затем 0.21 0.22 0.26 ...
    // Ниже 0.09 лежат все чистые уклонные, выше 0.21 - только смеси.
    // 0.15 стоит посреди этого промежутка.
    // Измерено: ворота убирают одну смешанную «в гору» (разброс 47% от
    // медианы) и одну «с горы» (55%), и зазор между классами растёт
    // с +0.25 до +0.38. Сессии с неизвестным разбросом проходят: их
    // не в чем упрекнуть.

    // v282. ВОРОТА ВМЕНЯЕМОСТИ КОРПУСА.
    // Найдено на выгрузке 201 сессии: две подтверждённые уклонные строки
    // содержали ТОЛЬКО амплитуду - ни каденса, ни наклона телефона, ни
    // гироскопа, ни IQR. Их амплитуды 10.95 и 11.83 лежат выше всего
    // измеренного диапазона ходьбы (0.8-7.8) и выше обоих классов уклона
    // (в гору 6.43-7.48, с горы 7.73-8.83).
    // Цена: с ними зазор между максимумом «в гору» и минимумом «с горы»
    // равен МИНУС 3.22 - классы перекрываются. Без них зазор ПЛЮС 0.25 -
    // классы разделены. Две строки из тридцати четырёх переворачивали
    // разделимость всей задачи.
    // Правило не выдумано, а следует из данных: строка, у которой нет
    // признаков второго слоя, не является наблюдением - её нечем
    // проверить. Такие строки не удаляются (прошлое неизменно), они
    // просто не участвуют ни в обучении, ни в вопросах, ни в базе
    // относительного порога.

    // L3.0: самая свежая надёжная уклонная сессия, про которую ещё не спрашивали.
    @Query("SELECT * FROM sessions WHERE reliable = 1 AND confirmState = 0 " +
        "AND cadenceMed IS NOT NULL AND pitchMed IS NOT NULL " +
        "AND (ampIqr IS NULL OR ampMed IS NULL OR ampIqr <= 0.15 * ampMed) " +
        "AND label != 'FLAT' ORDER BY endMs DESC LIMIT 1")
    suspend fun latestUnaskedIncline(): SessionRecord?

    // v231: ОКНО свежих неспрошенных уклонных (для активного обучения -
    // среди них выбираем самую спорную по margin агента).
    @Query("SELECT * FROM sessions WHERE reliable = 1 AND confirmState = 0 " +
        "AND cadenceMed IS NOT NULL AND pitchMed IS NOT NULL " +
        "AND (ampIqr IS NULL OR ampMed IS NULL OR ampIqr <= 0.15 * ampMed) " +
        "AND label != 'FLAT' ORDER BY endMs DESC LIMIT :limit")
    suspend fun unaskedInclineWindow(limit: Int): List<SessionRecord>

    // v231: сколько уклонных ПОДТВЕРЖДЕНО - база агента. Пока мала, margin
    // ещё шум, приоритет спорным не включаем.
    @Query("SELECT COUNT(*) FROM sessions WHERE label != 'FLAT' " +
        "AND cadenceMed IS NOT NULL AND pitchMed IS NOT NULL " +
        "AND (ampIqr IS NULL OR ampMed IS NULL OR ampIqr <= 0.15 * ampMed) " +
        "AND reliable = 1 AND confirmState = 1")
    suspend fun countInclineConfirmed(): Int

    // L3.0: записать исход вопроса в три архива (1=подтв, 2=дефект, 3=серая зона).
    @Query("UPDATE sessions SET confirmState = :state WHERE id = :id")
    suspend fun setSessionConfirm(id: Long, state: Int)

    // v244: кандидаты на автометку - надёжные уклонные, ещё не тронутые.
    // Порядок от старых к свежим: сначала разбираем накопившееся.
    @Query("SELECT * FROM sessions WHERE reliable = 1 AND confirmState = 0 " +
        "AND cadenceMed IS NOT NULL AND pitchMed IS NOT NULL " +
        "AND (ampIqr IS NULL OR ampMed IS NULL OR ampIqr <= 0.15 * ampMed) " +
        "AND label != 'FLAT' ORDER BY endMs ASC LIMIT :limit")
    suspend fun autoLabelCandidates(limit: Int): List<SessionRecord>

    // v244: сколько сессий проставлено автоматически (для показа).
    @Query("SELECT COUNT(*) FROM sessions WHERE confirmState = 4")
    suspend fun countAutoLabeled(): Int

    // v254: всё, где есть курс - для диагностики магнитометра.
    @Query("SELECT * FROM terrain_samples WHERE headingDeg IS NOT NULL " +
        "ORDER BY timeMs ASC")
    suspend fun samplesWithHeading(): List<TerrainSample>

    // v218: правка метки. Заодно подтверждение - человек ответил осознанно.
    @Query("UPDATE sessions SET userLabel = :label, confirmState = 1 WHERE id = :id")
    suspend fun setUserLabel(id: Long, label: String)

    @Query("UPDATE sessions SET userLabel = NULL WHERE id = :id")
    suspend fun clearUserLabel(id: Long)

    // v235: удалить сессию по id - при разрезке заменяем на две.
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionById(id: Long)

    // L3.0: свежая надёжная ПЛОСКАЯ сессия, про которую ещё не спрашивали.
    // Нужна, чтобы "ровно" стало подтверждённым классом, а не меткой по умолчанию.
    @Query("SELECT * FROM sessions WHERE reliable = 1 AND confirmState = 0 " +
        "AND cadenceMed IS NOT NULL AND pitchMed IS NOT NULL " +
        "AND (ampIqr IS NULL OR ampMed IS NULL OR ampIqr <= 0.15 * ampMed) " +
        "AND label NOT IN ('UP','DOWN') ORDER BY endMs DESC LIMIT 1")
    suspend fun latestUnaskedFlat(): SessionRecord?

    // v214: транспортные образцы дня - поездки для карты. Сессии их вырезают
    // (и правильно: агент уклона не должен на них учиться), но человеку нужно
    // видеть, чем заполнен день.
    @Query("SELECT * FROM terrain_samples WHERE timeMs BETWEEN :from AND :to " +
        "AND mode = 'TRANSPORT' ORDER BY timeMs ASC")
    suspend fun transportSamples(from: Long, to: Long): List<TerrainSample>

    // v205: образцы внутри сессии - чтобы показать человеку фактический
    // состав меток и не требовать веры на слово.
    @Query("SELECT * FROM terrain_samples WHERE timeMs BETWEEN :from AND :to " +
        "ORDER BY timeMs ASC")
    suspend fun samplesBetween(from: Long, to: Long): List<TerrainSample>

    // L3.1: соседние сессии - база прогулки для относительного порога агента.
    // v282. Ворота стоят и здесь - это САМОЕ важное место: относительный
    // порог агента строится как медиана соседей по прогулке, и одна строка
    // с амплитудой 11.8 сдвигает базу для всех соседних сессий сразу.
    @Query("SELECT * FROM sessions WHERE startMs BETWEEN :from AND :to " +
        "AND cadenceMed IS NOT NULL AND pitchMed IS NOT NULL " +
        "AND (ampIqr IS NULL OR ampMed IS NULL OR ampIqr <= 0.15 * ampMed) ")
    suspend fun sessionsAround(from: Long, to: Long): List<SessionRecord>

    // v202: ответы человека - исходные данные, их нельзя терять при пересборке.
    @Query("SELECT * FROM sessions WHERE confirmState != 0")
    suspend fun answeredSessions(): List<SessionRecord>

    // Вернуть ответ новой сессии, накрывающей то же время (после пересборки).
    @Query("UPDATE sessions SET confirmState = :state WHERE " +
        ":mid BETWEEN startMs AND endMs AND label = :label AND confirmState = 0")
    suspend fun restoreConfirmAt(mid: Long, label: String, state: Int): Int

    // Экспорт для разбора: все надёжные сессии с признаками.
    @Query("SELECT * FROM sessions WHERE reliable = 1 ORDER BY endMs DESC")
    suspend fun reliableSessions(): List<SessionRecord>

    // v225: ВСЕ сессии для карты дня. Надёжность - критерий обучения, а не
    // существования: короткий подъём был, и на карте он должен быть виден.
    // Обучение по-прежнему берёт только reliableSessions().
    @Query("SELECT * FROM sessions ORDER BY endMs DESC")
    suspend fun allSessionsForMap(): List<SessionRecord>

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
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Только добавление колонок: старые часы получают 0/0 = "каденс не
        // мерили", и расчёт для них честно откатывается на константу профиля.
        db.execSQL("ALTER TABLE hours ADD COLUMN cadenceIntervalSum INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE hours ADD COLUMN cadenceStepSum INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Только добавление колонки: старые строки получают NULL, что честно
        // означает "правки не было". Ничего не переписываем.
        db.execSQL("ALTER TABLE sessions ADD COLUMN userLabel TEXT")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Только добавление колонок: старые строки получают NULL, что честно
        // означает "курс тогда не писали". Прошлое не переписываем.
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN headingDeg REAL")
        db.execSQL("ALTER TABLE terrain_samples ADD COLUMN headingAcc INTEGER")
    }
}

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
    val builtFromMaxTimeMs: Long = 0,  // до какого образца корпус уже свёрнут
    // v218. Правка метки человеком. Исходный label НЕ переписывается: он
    // факт того, что было нажато тогда. null = правки не было.
    val userLabel: String? = null
)

@Database(entities = [DayRecord::class, EventRecord::class, HourRecord::class, ProfileSnapshotRecord::class, TerrainSample::class, SessionRecord::class],
    version = 14, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StepDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "stepcore.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14).build().also { instance = it }
            }
    }
}
