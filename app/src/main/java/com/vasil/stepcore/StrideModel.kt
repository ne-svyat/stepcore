package com.vasil.stepcore

import android.content.Context

/**
 * StrideModel (V9.3) - единственная ответственность: длина шага.
 * Чистый Kotlin поверх SharedPreferences, без Android-логики сверх чтения.
 *
 * Три уровня доверия к длине шага (source), каждый честно помечен в UI:
 *   ESTIMATE - от роста (SL = 0.414 * рост), ±15%, без действий;
 *   MANUAL   - измерена калибровкой на известной дистанции, ±5%.
 * (AUTO зарезервирован под V10: подстройка формы кривой из чистых сессий
 *  с привязкой к MANUAL как ground truth. Без метрики доверия не включаем -
 *  тапы/шум отравили бы модель, см. лог 07.07.)
 *
 * Модель длины: SL(cadence) = a * cadenceHz + b [метры].
 * Физиология: ускорение идёт в основном в удлинение шага, в диапазоне
 * 1.4-2.2 Гц зависимость линейна (PDR-литература). Два прохода на разных
 * темпах -> точное решение (a, b); один проход -> сдвиг b при табличном
 * a = 0.37 (наклон популяционный, абсолют персональный).
 */
object StrideModel {

    enum class Source { ESTIMATE, MANUAL, GPS }

    const val A_DEFAULT = 0.37f          // популяционный наклон, м/Гц
    // v229. Пороги двухточечной калибровки (Шаг Б).
    private const val MIN_CADENCE_GAP = 0.15f   // ближе - наклон не определить
    private const val SANE_A_MIN = 0.15f        // разумный наклон человека
    private const val SANE_A_MAX = 0.65f
    // v237. История калибровок: последние N точек. Наклон решается по
    // паре с МАКСИМАЛЬНЫМ разбросом каденса, а не по двум последним -
    // иначе близкие темпы дают вечное "нужен второй проход".
    private const val HIST_MAX = 10
    private const val KEY_HIST = "stride_cal_history"
    // v238. Отсев выбросов: замер, отклонившийся от медианы больше чем
    // на MAD_K медианных отклонений - это GPS-прыжок или пропуск шагов.
    // На данных Markus: 92 см при медиане 73 и MAD 5 -> выброс.
    private const val MAD_K = 3.0f
    private const val MIN_FIT_POINTS = 4   // меньше - регрессии не верим
    // v279. Наклон, когда персональный НЕ доказан. Раньше подставлялся
    // популяционный A_DEFAULT = 0.37, и он применялся к человеку, у
    // которого наклона нет. ИЗМЕРЕНО на 10 калибровках 26.07:
    // корреляция каденса и длины шага r = -0.00, наклон регрессии
    // -0.001 м/Гц, R2 = 0.000. При его разбросе каденса 1.77..2.05 Гц
    // табличный наклон давал размах длины шага 16.7 см (70.8 см на
    // 1.75 Гц против 87.5 на 2.20) там, где измеренный размах нулевой:
    // -360 м на 10 000 шагов на медленном часе, +220 м на быстром.
    // Недоказанный наклон - это выдумка, а не осторожность.
    private const val A_WHEN_UNKNOWN = 0f
    // v279. Ворота значимости: наклон принимается, только если объясняет
    // хотя бы половину разброса точек. На синтетике БЕЗ наклона доля
    // ложных "наклон персональный" падает с 22.6% до 7.2%, при этом
    // настоящий наклон ловится в 47% случаев - его просто померят ещё раз.
    private const val MIN_R2 = 0.5f
    // v279. Возраст замера, после которого он не участвует в модели:
    // обувь, вес и техника меняются. Свежесть в реестре спадает за 30
    // дней; здесь горизонт шире, потому что калибровки редки.
    private const val MAX_AGE_DAYS = 120L
    private const val DAY_MS = 86_400_000L
    // v243. Эталонный замер: длина, на которой ошибка GPS и округление
    // шагов уже размазаны. Измерено у Markus: на ~300 м разброс 2 см,
    // на 105-152 м - 12 см. Опору строим только по длинным, если есть.
    const val REFERENCE_METRES = 200f
    private const val HEIGHT_FACTOR_WALK = 0.414f
    private const val HEIGHT_FACTOR_RUN = 0.65f
    /** Ключи измеренного бегового шага. */
    const val KEY_RUN_SL = "stride_run_m"
    const val KEY_RUN_SL_MS = "stride_run_ms"
    const val KEY_RUN_SL_GPS = "stride_run_by_gps"
    /** Границы правдоподобия отношения бегового шага к ходовому.
     *  Не источник значения - только отсев явно битых замеров. */
    private const val RUN_RATIO_MIN = 1.05f
    private const val RUN_RATIO_MAX = 1.90f
    /** Границы скорости бега, м/с. 2.0 = 7.2 км/ч (медленнее это уже
     *  быстрая ходьба), 7.0 = 25 км/ч (быстрее человек долго не бежит). */
    private const val RUN_SPEED_MIN = 2.0f
    private const val RUN_SPEED_MAX = 7.0f
    /** Насколько каденс замера может расходиться с измеренным темпом бега.
     *  Больше - значит часть шагов потерялась, и длина шага раздута. */
    private const val RUN_CADENCE_TOLERANCE = 0.25f

    private fun p(c: Context) =
        c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    // ===== ЧИСТЫЕ ФОРМУЛЫ (V11.1) =====
    // Без Context: считают по ПЕРЕДАННЫМ параметрам, а не по живым prefs.
    // Нужны Stats.energyForHour, который берёт профиль ИЗ ИСТОРИИ. Обёртки
    // ниже делегируют сюда: одна формула, два способа достать параметры
    // (ARCHITECTURE_RULES - источник истины один).

    fun walkCadenceHzOf(minIntervalMs: Long, maxIntervalMs: Long): Float {
        val medMs = (minIntervalMs + maxIntervalMs) / 2f
        return if (medMs > 0) 1000f / medMs else 1.8f
    }

    /** v220. Каденс из ЖИВОГО среднего интервала часа (мс -> Гц).
     *  Диапазон зажат: вне 1.3..2.4 Гц линейная модель длины шага не
     *  валидна (PDR-литература), а мусорный интервал не должен раздуть
     *  дистанцию. count == 0 -> нет измерений -> вернём 0, вызывающий
     *  откатится на константу профиля. */
    fun cadenceHzFromHour(intervalSum: Long, stepCount: Int): Float {
        if (stepCount <= 0 || intervalSum <= 0L) return 0f
        val medMs = intervalSum.toFloat() / stepCount
        if (medMs <= 0f) return 0f
        return (1000f / medMs).coerceIn(1.3f, 2.4f)
    }

    fun walkStrideMOf(
        cadenceHz: Float, strideManual: Boolean,
        strideA: Float, strideB: Float, heightCm: Int
    ): Float = if (strideManual) (strideA * cadenceHz + strideB).coerceIn(0.3f, 1.2f)
        else if (heightCm > 0) heightCm * HEIGHT_FACTOR_WALK / 100f else 0.7f

    fun runStrideMOf(heightCm: Int): Float =
        if (heightCm > 0) heightCm * HEIGHT_FACTOR_RUN / 100f else 1.0f

    fun source(c: Context): Source = when {
        !p(c).getBoolean("stride_manual", false) -> Source.ESTIMATE
        p(c).getBoolean("stride_by_gps", false) -> Source.GPS
        else -> Source.MANUAL
    }

    /** Длина шага ходьбы для заданного каденса (Гц), по ТЕКУЩЕМУ профилю. */
    fun walkStrideM(c: Context, cadenceHz: Float): Float {
        val pr = p(c)
        return walkStrideMOf(
            cadenceHz,
            pr.getBoolean("stride_manual", false),
            pr.getFloat("stride_a", A_DEFAULT),
            pr.getFloat("stride_b", 0f),
            pr.getInt("p_height", 0)
        )
    }

    /** Средняя длина шага ходьбы по калиброванному каденсу (для сумм за день). */
    fun walkStrideAvgM(c: Context): Float = walkStrideM(c, avgWalkCadenceHz(c))

    /**
     * v318. Длина шага БЕГА.
     *
     * Приоритет: ИЗМЕРЕНО > оценка. Если беговой отрезок пройден - берём
     * его; если нет, остаётся оценка по росту (рост x 0.65), и она честно
     * помечается как оценка в отчёте.
     *
     * Модель НЕ подмешивается к измерению. Усреднять свой замер с
     * популяционным коэффициентом значит разбавлять данные чужой
     * константой - ровно то, за что в v279 выкинули табличный наклон
     * длины шага. Формула здесь работает только как ПРОВЕРКА (см.
     * applyRunCalibration): она не задаёт число, а отсекает бессмыслицу.
     */
    fun runStrideM(c: Context): Float {
        val measured = p(c).getFloat(KEY_RUN_SL, 0f)
        if (measured > 0f) return measured
        return runStrideMOf(p(c).getInt("p_height", 0))
    }

    /** Измеренный темп бега в герцах, 0 - если калибровки бега нет. */
    fun calibratedRunCadenceHz(c: Context): Float {
        val lo = p(c).getLong("run_min_interval", 0L)
        val hi = p(c).getLong("run_max_interval", 0L)
        if (lo <= 0L || hi <= 0L) return 0f
        val mid = (lo + hi) / 2
        return if (mid > 0L) 1000f / mid else 0f
    }

    /** Измерена ли длина бегового шага, или это оценка по росту. */
    fun runStrideMeasured(c: Context): Boolean = p(c).getFloat(KEY_RUN_SL, 0f) > 0f

    /** Когда измеряли (0 - никогда). */
    fun runStrideMs(c: Context): Long = p(c).getLong(KEY_RUN_SL_MS, 0L)

    /**
     * Сохранить замер длины бегового шага. Возвращает текст результата -
     * его же произносит голос и показывает экран.
     *
     * Ворота вменяемости: беговой шаг не может быть короче ходового и не
     * может превышать его почти вдвое. Диапазон 1.05..1.90 взят из
     * литературы по биомеханике как ГРАНИЦЫ ПРАВДОПОДОБИЯ, а не как
     * источник значения: всё, что внутри, сохраняется как измерено, всё,
     * что снаружи - почти наверняка срезанный угол или сбой GPS.
     */
    fun applyRunCalibration(
        c: Context, metres: Float, steps: Int, byGps: Boolean, durationSec: Float = 0f
    ): String {
        if (steps <= 0 || metres <= 0f) return "Замер пустой - ничего не сохранено."
        val sl = metres / steps
        val walk = walkStrideAvgM(c)
        if (walk <= 0f) return "Сначала измерь длину шага ходьбы."
        val ratio = sl / walk
        if (ratio < RUN_RATIO_MIN || ratio > RUN_RATIO_MAX) {
            return "Беговой шаг вышел " + (sl * 100).toInt() + " см при ходьбе " +
                (walk * 100).toInt() + " см - это в " +
                String.format(java.util.Locale.US, "%.1f", ratio) +
                " раза. Похоже на срезанный угол или сбой GPS. НЕ сохранено."
        }
        // v321. ВОРОТА ПО СКОРОСТИ. Отношение к ходовому шагу пропустило
        // замер 136 см (в 1.81 раза), а он означал бы 18 км/ч на пятнадцать
        // минут бега - столько человек так долго не бежит.
        if (durationSec > 0f) {
            val speed = metres / durationSec
            if (speed < RUN_SPEED_MIN || speed > RUN_SPEED_MAX) {
                return "Скорость замера " +
                    String.format(java.util.Locale.US, "%.1f", speed * 3.6f) +
                    " км/ч - это не похоже на бег. НЕ сохранено."
            }
            // v321. ВОРОТА СОГЛАСОВАННОСТИ С ТЕМПОМ.
            // Главная причина завышенной длины шага - ПОТЕРЯННЫЕ шаги:
            // гвардия тряски выбрасывает их пачками (в журнале 05.08 за
            // пробежку отброшено около 290). Метры делятся на заниженное
            // число шагов, и длина раздувается. Скорость при этом остаётся
            // правильной, поэтому предыдущие ворота такое пропускают.
            // Ловится это иначе: каденс самого замера должен сходиться с
            // измеренным беговым темпом. На замере 136 см расхождение
            // вышло бы 30%, на честных 95 см - меньше одного процента.
            val calCad = calibratedRunCadenceHz(c)
            if (calCad > 0f) {
                val measCad = steps / durationSec
                val diff = kotlin.math.abs(measCad - calCad) / calCad
                if (diff > RUN_CADENCE_TOLERANCE) {
                    return "Темп замера " +
                        String.format(java.util.Locale.US, "%.2f", measCad) +
                        " Гц против измеренного бега " +
                        String.format(java.util.Locale.US, "%.2f", calCad) +
                        " Гц - расхождение " + (diff * 100).toInt() +
                        "%. Похоже, часть шагов не досчиталась. НЕ сохранено."
                }
            }
        }
        p(c).edit()
            .putFloat(KEY_RUN_SL, sl)
            .putLong(KEY_RUN_SL_MS, System.currentTimeMillis())
            .putBoolean(KEY_RUN_SL_GPS, byGps)
            .apply()
        return "Готово: беговой шаг " + (sl * 100).toInt() + " см (" +
            metres.toInt() + " м / " + steps + " шаг.). Это в " +
            String.format(java.util.Locale.US, "%.2f", ratio) + " раза длиннее шага ходьбы."
    }

    /** Каденс ходьбы из калибровки интервалов: med = (lo+hi)/2 -> Гц. */
    fun avgWalkCadenceHz(c: Context): Float {
        val pr = p(c)
        return walkCadenceHzOf(
            pr.getLong("walk_min_interval", 400L),
            pr.getLong("walk_max_interval", 1200L)
        )
    }

    /**
     * Результат калибровки дистанции: metres пройдено за steps шагов.
     * Один вызов -> сдвиг b (наклон табличный). Модель помечается MANUAL.
     * Второй вызов с ДРУГИМ каденсом мог бы решить (a,b) точно - задел,
     * пока сохраняем последнюю точку и сдвиг.
     */
    fun applyCalibration(
        c: Context, metres: Float, steps: Int, byGps: Boolean = false,
        // v230. Фактический каденс ЭТОГО замера (Гц). <=0 -> откат на профиль.
        // Именно он делает две точки разными: медленный проход низкий, быстрый
        // высокий. Без него c1≈c2 и Шаг Б не сработал бы.
        measuredCadence: Float = 0f
    ) {
        if (steps <= 0 || metres <= 0f) return
        val measuredSL = metres / steps
        val cadence = if (measuredCadence > 0f) measuredCadence else avgWalkCadenceHz(c)
        val pr = p(c)

        // v237. Кладём точку в историю и решаем наклон по паре с самым
        // большим разбросом каденса среди всех сохранённых калибровок.
        addToHistory(c, CalPoint(System.currentTimeMillis(), cadence,
            measuredSL, metres, steps))
        val hist = calHistory(c)
        // v238. Сначала регрессия по всем чистым точкам (шум усредняется).
        val solved = fitSlope(hist)

        val a: Float
        val b: Float
        if (solved != null) {
            a = solved.first; b = solved.second
        } else {
            // Наклон честно не выводится: шум замера больше эффекта.
            // Тогда табличный наклон, но якорь по МЕДИАНЕ истории, а не
            // по последнему замеру - иначе длина шага скачет от выброса
            // к выбросу (у Markus прыгала 68..92 см).
            // v279. Наклона нет - значит нет, а не "возьмём популяционный".
            a = A_WHEN_UNKNOWN
            // v243. Опора - эталонные (длинные) замеры, если они есть.
            // v279. И только свежие.
            val clean = anchorPoints(freshPoints(hist))
            val medSL = medianStride(clean) ?: measuredSL
            val medCad = if (clean.isEmpty()) cadence else medianOf(clean.map { it.cadence })
            b = medSL - a * medCad
        }

        pr.edit()
            .putFloat("stride_a", a)
            .putFloat("stride_b", b)
            .putBoolean("stride_manual", true)
            .putBoolean("stride_by_gps", byGps)
            .putFloat("stride_measured_sl", measuredSL)
            .putFloat("stride_cal_cadence", cadence)
            .putBoolean("stride_personal_slope", solved != null)
            .apply()
    }

    /** Точка калибровки: когда, на каком каденсе, какая вышла длина шага. */
    data class CalPoint(
        val timeMs: Long, val cadence: Float, val strideM: Float,
        val metres: Float, val steps: Int
    )

    /** История калибровок, свежие первыми. Хранится строкой в prefs:
     *  time,cadence,stride,metres,steps; ... - без зависимостей на JSON. */
    fun calHistory(c: Context): List<CalPoint> {
        val raw = p(c).getString(KEY_HIST, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<CalPoint>()
        for (rec in raw.split(";")) {
            if (rec.isBlank()) continue
            val f = rec.split(",")
            if (f.size < 5) continue
            val t = f[0].toLongOrNull() ?: continue
            val cad = f[1].toFloatOrNull() ?: continue
            val sl = f[2].toFloatOrNull() ?: continue
            val m = f[3].toFloatOrNull() ?: 0f
            val st = f[4].toIntOrNull() ?: 0
            out.add(CalPoint(t, cad, sl, m, st))
        }
        return out
    }

    private fun addToHistory(c: Context, pt: CalPoint) {
        val list = ArrayList(calHistory(c))
        list.add(0, pt)
        while (list.size > HIST_MAX) list.removeAt(list.size - 1)
        val sb = StringBuilder()
        for (x in list) {
            sb.append(x.timeMs).append(",").append(x.cadence).append(",")
              .append(x.strideM).append(",").append(x.metres).append(",")
              .append(x.steps).append(";")
        }
        p(c).edit().putString(KEY_HIST, sb.toString()).apply()
    }

    /** Скорость прохода, м/с. Время замера = шаги / каденс.
     *  Именно скорость должна различаться между калибровками: модель
     *  SL = a*cadence + b описывает, как удлиняется шаг при РАЗГОНЕ. */
    fun speedOf(pt: CalPoint): Float {
        if (pt.cadence <= 0f || pt.steps <= 0) return 0f
        val durSec = pt.steps / pt.cadence
        return if (durSec > 0f) pt.metres / durSec else 0f
    }

    fun durationSecOf(pt: CalPoint): Float =
        if (pt.cadence > 0f) pt.steps / pt.cadence else 0f

    /** Эталонные точки: замеры длиннее REFERENCE_METRES. На них опираемся,
     *  если их хотя бы две - короткие проходы шумят втрое сильнее. */
    fun referencePoints(h: List<CalPoint>): List<CalPoint> =
        h.filter { it.metres >= REFERENCE_METRES }

    /** Точки, на которых строится опора: эталонные, если их >= 2,
     *  иначе все чистые. */
    fun anchorPoints(h: List<CalPoint>): List<CalPoint> {
        val ref = referencePoints(cleanPoints(h))
        return if (ref.size >= 2) ref else cleanPoints(h)
    }

    /**
     * Честная медиана. v279: при ЧЁТНОМ числе точек берётся среднее двух
     * средних, а не верхний из них. Прежний v[size/2] систематически
     * завышал: +1.09 см/шаг на случайных наборах, то есть +109 м на
     * каждые 10 000 шагов. История хранит до 10 точек, чётные размеры
     * обычны, поэтому перекос работал почти всегда.
     */
    fun medianOf(v: List<Float>): Float {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    /** Точки не старше MAX_AGE_DAYS. Полугодовалый замер - другая обувь и
     *  другой вес, в модель он идти не должен. Если свежих нет вовсе,
     *  возвращаем всё: считать по старому лучше, чем не считать. */
    fun freshPoints(h: List<CalPoint>): List<CalPoint> {
        val now = System.currentTimeMillis()
        val fresh = h.filter { it.timeMs > 0L && now - it.timeMs <= MAX_AGE_DAYS * DAY_MS }
        return if (fresh.isEmpty()) h else fresh
    }

    /** Медиана длины шага по истории - устойчивый якорь. В отличие от
     *  последнего замера не скачет от выброса к выбросу. */
    fun medianStride(h: List<CalPoint>): Float? {
        if (h.isEmpty()) return null
        return medianOf(h.map { it.strideM })
    }

    /** Медианное абсолютное отклонение - устойчивая мера разброса. */
    fun madStride(h: List<CalPoint>): Float {
        val med = medianStride(h) ?: return 0f
        if (h.isEmpty()) return 0f
        return medianOf(h.map { kotlin.math.abs(it.strideM - med) })
    }

    /** Точки без выбросов: GPS-прыжки и пропуски шагов не должны
     *  тянуть модель. Если разброс нулевой - берём всё. */
    fun cleanPoints(h: List<CalPoint>): List<CalPoint> {
        val med = medianStride(h) ?: return h
        val mad = madStride(h)
        if (mad <= 0f) return h
        return h.filter { kotlin.math.abs(it.strideM - med) <= MAD_K * mad }
    }

    /** Наклон методом наименьших квадратов по всем чистым точкам.
     *  Двухточечный метод делит разницу длин на разницу темпов и
     *  умножает шум; регрессия его усредняет. Возвращает null, если
     *  точек мало, темп однороден или наклон неправдоподобен. */
    fun fitSlope(h: List<CalPoint>): Pair<Float, Float>? {
        // v279. Регрессия строится только на ОПОРНЫХ и СВЕЖИХ точках.
        // Раньше 110-метровый проход весил столько же, сколько 300-метровый,
        // хотя разброс на коротких 12 см против 2 см на длинных - шум
        // коротких проходов прямо тянул наклон.
        val pts = anchorPoints(freshPoints(h))
        if (pts.size < MIN_FIT_POINTS) return null
        val cads = pts.map { it.cadence }
        if ((cads.max() - cads.min()) < MIN_CADENCE_GAP) return null
        var mx = 0f; var my = 0f
        for (x in pts) { mx += x.cadence; my += x.strideM }
        mx /= pts.size; my /= pts.size
        var num = 0f; var den = 0f
        for (x in pts) {
            val dx = x.cadence - mx
            num += dx * (x.strideM - my); den += dx * dx
        }
        if (den <= 0f) return null
        val a = num / den
        if (a < SANE_A_MIN || a > SANE_A_MAX) return null
        val b = my - a * mx
        // v279. Ворота значимости. Наклон внутри разумного диапазона ещё
        // не значит, что он существует: на чистом шуме такой находился в
        // каждом пятом случае и объявлялся "персональным". Принимаем,
        // только если он объясняет разброс точек.
        var ssTot = 0f
        var ssRes = 0f
        for (x in pts) {
            val dy = x.strideM - my
            val e = x.strideM - (a * x.cadence + b)
            ssTot += dy * dy
            ssRes += e * e
        }
        if (ssTot <= 0f) return null
        if (1f - ssRes / ssTot < MIN_R2) return null
        return a to b
    }

    /** Пара с максимальным разбросом каденса - на ней наклон точнее всего.
     *  Две близкие точки наклон не задают (делим на почти ноль). */
    fun bestPair(h: List<CalPoint>): Pair<CalPoint, CalPoint>? {
        if (h.size < 2) return null
        var lo = h[0]; var hi = h[0]
        for (x in h) {
            if (x.cadence < lo.cadence) lo = x
            if (x.cadence > hi.cadence) hi = x
        }
        return if (hi.cadence - lo.cadence >= MIN_CADENCE_GAP) lo to hi else null
    }

    fun solveFromHistory(h: List<CalPoint>): Pair<Float, Float>? {
        val pair = bestPair(h) ?: return null
        return solveTwoPoint(pair.first.cadence, pair.first.strideM,
            pair.second.cadence, pair.second.strideM)
    }

    /** Отчёт для человека: все калибровки и честный диагноз. */
    /**
     * v306. Блок бега и уклона в отчёте. До этого отчёт знал только про
     * длину шага, а бег и уклон приходилось выяснять по журналу вручную.
     * Отчёт целиком копируется в буфер одной кнопкой - это отладочный
     * инструмент на время настройки, потом его можно свернуть.
     */
    private fun tempoAndSlopeBlock(c: Context): String {
        val p = p(c)
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru"))
        val sb = StringBuilder()

        sb.append("\n--- ТЕМП ---\n")
        val wLo = p.getLong("walk_min_interval", 0L)
        val wHi = p.getLong("walk_max_interval", 0L)
        if (wLo > 0L && wHi > 0L) {
            val mid = (wLo + wHi) / 2
            sb.append("Ходьба: ").append(wLo).append("-").append(wHi)
              .append(" мс (середина ").append(mid).append(" мс = ")
              .append(String.format(java.util.Locale.US, "%.2f", 1000f / mid))
              .append(" Гц)\n")
        } else sb.append("Ходьба: не калибровалась\n")

        val rLo = p.getLong("run_min_interval", 0L)
        val rHi = p.getLong("run_max_interval", 0L)
        if (rLo > 0L && rHi > 0L) {
            val mid = (rLo + rHi) / 2
            sb.append("Бег: ").append(rLo).append("-").append(rHi)
              .append(" мс (середина ").append(mid).append(" мс = ")
              .append(String.format(java.util.Locale.US, "%.2f", 1000f / mid))
              .append(" Гц)\n")
        } else sb.append("Бег: НЕ калиброван\n")
        val runDate = p.getLong("cal_date_run", 0L)
        if (runDate > 0L) sb.append("Бег измерен: ")
            .append(fmt.format(java.util.Date(runDate))).append("\n")

        sb.append("Беговой шаг: ").append((runStrideM(c) * 100).toInt()).append(" см")
        if (runStrideMeasured(c)) {
            val ms = runStrideMs(c)
            sb.append("  измерено")
            if (ms > 0L) sb.append(" ").append(fmt.format(java.util.Date(ms)))
        } else {
            sb.append("  ОЦЕНКА по росту (не измерено)")
        }
        sb.append("\n")

        sb.append("\n--- УКЛОН ---\n")
        val up = p.getFloat("slope_anchor_up", 0f)
        val down = p.getFloat("slope_anchor_down", 0f)
        val flat = p.getFloat("slope_anchor_flat", 0f)
        fun line(name: String, v: Float, key: String) {
            sb.append(name).append(": ")
            if (v <= 0f) { sb.append("нет\n"); return }
            sb.append(String.format(java.util.Locale.US, "%.2f", v))
            val ms = p.getLong(key, 0L)
            if (ms > 0L) sb.append("  (").append(fmt.format(java.util.Date(ms))).append(")")
            sb.append("\n")
        }
        line("В гору", up, "slope_anchor_up_ms")
        line("Ровно", flat, "slope_anchor_flat_ms")
        line("С горы", down, "slope_anchor_down_ms")
        if (up > 0f && down > 0f) {
            val gap = down - up
            sb.append("Зазор с горы минус в гору: ")
              .append(String.format(java.util.Locale.US, "%+.2f", gap))
            sb.append(if (gap >= 0.30f) "  — годится\n"
                      else if (gap > 0f) "  — МАЛО, нужно от 0.30\n"
                      else "  — ПОРЯДОК ПЕРЕВЁРНУТ\n")
            if (flat > 0f) {
                sb.append("Проверка: ровно должно лежать между ними — ")
                sb.append(if (flat in up..down) "да\n" else "НЕТ, значение выпадает\n")
            }
        }
        return sb.toString()
    }

    fun calibrationReport(c: Context): String {
        val h = calHistory(c)
        val sb = StringBuilder("StepCore — отчёт калибровки\n\n")
        if (h.isEmpty()) return sb.append("Калибровок длины шага пока нет.")
            .append("\n").append(tempoAndSlopeBlock(c)).toString()
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru"))
        sb.append("Калибровок длины шага: ").append(h.size).append("\n")
        sb.append(tempoAndSlopeBlock(c)).append("\n--- ДЛИНА ШАГА ---\n")
        for ((i, x) in h.withIndex()) {
            sb.append(i + 1).append(") ").append(fmt.format(java.util.Date(x.timeMs)))
              .append("  темп ").append(String.format(java.util.Locale.US, "%.2f", x.cadence))
              .append(" Гц  шаг ").append((x.strideM * 100).toInt()).append(" см")
              .append("  (").append(x.metres.toInt()).append(" м / ")
              .append(x.steps).append(" шаг.)")
              .append("\n     ").append(durationSecOf(x).toInt()).append(" с, ")
              .append(String.format(java.util.Locale.US, "%.2f", speedOf(x)))
              .append(" м/с")
              .append(if (x.metres >= REFERENCE_METRES) "  ★ эталон" else "")
              .append("\n")
        }
        val cads = h.map { it.cadence }
        val spread = (cads.max() - cads.min())
        sb.append("\nРазброс темпа: ")
          .append(String.format(java.util.Locale.US, "%.2f", spread)).append(" Гц")
        sb.append(" (нужно ≥ ").append(MIN_CADENCE_GAP).append(")\n")
        val clean = cleanPoints(h)
        val mad = madStride(h)
        val anchor = anchorPoints(h)
        val refCount = referencePoints(cleanPoints(h)).size
        val med = medianStride(anchor)
        if (med != null) {
            sb.append("ОПОРА: длина шага ").append((med * 100).toInt()).append(" см")
            if (refCount >= 2)
                sb.append("  (по ").append(refCount).append(" эталонным замерам ≥")
                  .append(REFERENCE_METRES.toInt()).append(" м)\n")
            else
                sb.append("  (эталонных замеров ≥").append(REFERENCE_METRES.toInt())
                  .append(" м пока мало — короткие шумят сильнее)\n")
        }
        sb.append("Шум замера (MAD): ").append((mad * 100).toInt()).append(" см")
        if (mad > 0.06f) sb.append("  ← БОЛЬШОЙ")
        sb.append("\n")
        if (clean.size < h.size) {
            sb.append("Отброшено выбросов: ").append(h.size - clean.size)
              .append(" (GPS-прыжок или пропуск шагов)\n")
        }
        val fit = fitSlope(h)
        if (fit != null) {
            sb.append("\nНАКЛОН ПЕРСОНАЛЬНЫЙ ✓ (регрессия по ")
              .append(clean.size).append(" точкам)\n")
            sb.append("a=").append(String.format(java.util.Locale.US, "%.3f", fit.first))
              .append("  b=").append(String.format(java.util.Locale.US, "%.3f", fit.second))
              .append("\n")
            return sb.toString()
        }
        // v240. ГЛАВНАЯ проверка: менялась ли СКОРОСТЬ. Модель описывает
        // удлинение шага при разгоне. Если скорость одна, то
        // длина = скорость / каденс - связь обратная, наклон выйдет
        // отрицательным по арифметике, а не из-за шума.
        val speeds = clean.map { speedOf(it) }.filter { it > 0f }
        if (speeds.size >= 2) {
            val vmin = speeds.min(); val vmax = speeds.max()
            sb.append("Скорость: от ")
              .append(String.format(java.util.Locale.US, "%.2f", vmin))
              .append(" до ")
              .append(String.format(java.util.Locale.US, "%.2f", vmax))
              .append(" м/с\n")
            val ratio = if (vmin > 0f) vmax / vmin else 1f
            if (ratio < 1.35f) {
                sb.append("\nВОТ ПРИЧИНА: скорость почти не менялась.\n")
                sb.append("Длина шага x каденс = скорость. Если скорость одна,\n")
                sb.append("то чаще шаги = короче шаг — связь ОБРАТНАЯ, и наклон\n")
                sb.append("выходит отрицательным по арифметике. Модель описывает\n")
                sb.append("удлинение шага при РАЗГОНЕ, а не смену манеры шага.\n\n")
                val med = medianOf(clean.map { it.metres })
                val slow = (med / 1.15f).toInt()
                val fast = (med / 2.05f).toInt()
                sb.append("ЗАДАНИЕ (если захочешь наклон):\n")
                sb.append("  1) тот же отрезок ~").append(med.toInt())
                  .append(" м НЕ СПЕША, примерно за ").append(slow).append(" с\n")
                sb.append("  2) он же БЫСТРЫМ шагом, примерно за ").append(fast)
                  .append(" с\n")
                sb.append("Разница во ВРЕМЕНИ — вот что нужно, не в частоте шагов.\n\n")
                val m2 = medianStride(clean)
                sb.append("НО ЭТО НЕОБЯЗАТЕЛЬНО. Длина шага уже посчитана")
                if (m2 != null) sb.append(" = ").append((m2 * 100).toInt()).append(" см")
                sb.append(",\nона устойчива и работает. Наклон дал бы небольшую\n")
                sb.append("поправку на очень быстрой ходьбе — и только.\n")
                return sb.toString()
            }
        }
        // v239. Главная проверка: а меняется ли у человека каденс вообще?
        // У этого пользователя измерено: каденс бега и ходьбы совпадает
        // (523 vs 543 мс). Если весь разброс мал - модель "длина от
        // каденса" к нему не применима, и это ответ, а не отговорка.
        val cadSpread = if (clean.isEmpty()) 0f
            else (clean.maxOf { it.cadence } - clean.minOf { it.cadence })
        if (cadSpread < 0.35f) {
            sb.append("\nВЕРДИКТ: у тебя ПОСТОЯННЫЙ КАДЕНС.\n")
            sb.append("Все проходы уложились в ")
              .append(String.format(java.util.Locale.US, "%.2f", cadSpread))
              .append(" Гц — ты меняешь скорость ДЛИНОЙ ШАГА, а не частотой.\n")
            sb.append("Это давно измерено и в ядре: каденс бега и ходьбы у тебя\n")
            sb.append("почти одинаков. Значит модель «длина шага от каденса»\n")
            sb.append("к твоей походке НЕ ПРИМЕНИМА — выводить наклон не из чего.\n\n")
            sb.append("ЧТО ИСПОЛЬЗУЕТСЯ: медиана измеренной длины шага")
            val m = medianStride(clean)
            if (m != null) sb.append(" = ").append((m * 100).toInt()).append(" см")
            sb.append(".\nЭто правильный ответ для тебя, а не запасной вариант.\n")
            sb.append("Больше калибровок «на другом темпе» не нужно.\n")
            return sb.toString()
        }
        if (mad > 0.06f) {
            sb.append("\nПОЧЕМУ НАКЛОН НЕ ВЫВЕДЕН: шум замера больше эффекта.\n")
            sb.append("Разница длины шага между медленной и быстрой ходьбой\n")
            sb.append("всего 10-13 см, а твои замеры пляшут на ")
              .append((mad * 200).toInt()).append(" см.\n")
            sb.append("ЧТО ДЕЛАТЬ: отрезок ДЛИННЕЕ (300 м лучше 100 м) -\n")
            sb.append("ошибка GPS размазывается по большему пути.\n")
            sb.append("Длина шага при этом уже устойчива: берётся медиана.\n")
            return sb.toString()
        }
        val pair = bestPair(h)
        if (pair == null) {
            sb.append("\nПОЧЕМУ НАКЛОН НЕ ВЫВЕДЕН: все проходы на близком темпе.\n")
            sb.append("НУЖНО: один проход ЯВНО медленно (прогулочный шаг),\n")
            sb.append("второй ЯВНО быстро (энергичный шаг). Разница темпа важнее длины.\n")
        } else {
            val sol = solveFromHistory(h)
            if (sol == null) {
                sb.append("\nПОЧЕМУ НАКЛОН НЕ ВЫВЕДЕН: наклон вышел неправдоподобным\n")
                sb.append("(вероятно разные маршруты или GPS-дрейф). Пройди оба замера\n")
                sb.append("на ОДНОМ прямом отрезке, ≥150 м, открытое небо.\n")
            } else {
                sb.append("\nНАКЛОН ПЕРСОНАЛЬНЫЙ ✓\n")
                sb.append("a=").append(String.format(java.util.Locale.US, "%.3f", sol.first))
                  .append("  b=").append(String.format(java.util.Locale.US, "%.3f", sol.second))
                  .append("\nПостроен по проходам на ")
                  .append(String.format(java.util.Locale.US, "%.2f", pair.first.cadence))
                  .append(" и ")
                  .append(String.format(java.util.Locale.US, "%.2f", pair.second.cadence))
                  .append(" Гц.\n")
            }
        }
        sb.append("\nРазброс длины шага между проходами - это нормально:\n")
        sb.append("GPS на коротком отрезке врёт на несколько процентов.\n")
        sb.append("Чем длиннее замер, тем точнее (≥150 м лучше 100 м).\n")
        return sb.toString()
    }

    /** Решает прямую SL = a*cadence + b по двум точкам. Возвращает (a,b) или
     *  null, если точки не годятся: близкие каденсы или неправдоподобный
     *  наклон (GPS-дрейф, разный маршрут). null -> откат на табличный наклон. */
    fun solveTwoPoint(c1: Float, sl1: Float, c2: Float, sl2: Float): Pair<Float, Float>? {
        if (kotlin.math.abs(c2 - c1) < MIN_CADENCE_GAP) return null
        val a = (sl2 - sl1) / (c2 - c1)
        if (a < SANE_A_MIN || a > SANE_A_MAX) return null
        val b = sl1 - a * c1
        return a to b
    }

    /** Персональный ли наклон (обе калибровки сошлись) или ещё табличный. */
    fun hasPersonalSlope(c: Context): Boolean =
        p(c).getBoolean("stride_personal_slope", false)

    /**
     * v320. Вердикт о наклоне: три РАЗНЫХ состояния вместо двух.
     *
     * Раньше их было два - «наклон персональный» и «пройди ещё раз на
     * другом темпе». Второе показывалось всегда, когда наклон не выведен,
     * и у этого человека стало невыполнимой просьбой: разброс темпа уже
     * 0.28 Гц при нужных 0.15, а связь темпа с длиной шага отсутствует
     * (r = -0.00, R2 = 0.000, измерено в v279). Сколько ни ходи, наклон
     * не появится, потому что его нет.
     *
     * Теперь различаются:
     *  - наклон выведен;
     *  - данных ещё мало ИЛИ темп не разошёлся - тогда просьба уместна;
     *  - темп разошёлся достаточно, а связи нет - это ОТВЕТ, а не
     *    незаконченная работа: длина шага у человека постоянна.
     */
    fun slopeVerdict(c: Context): String {
        if (hasPersonalSlope(c)) return "наклон персональный ✓"
        val h = anchorPoints(freshPoints(calHistory(c)))
        if (h.size < MIN_FIT_POINTS)
            return "для наклона нужно ещё замеров: " + h.size + " из " + MIN_FIT_POINTS
        val cads = h.map { it.cadence }
        val gap = (cads.max() - cads.min())
        if (gap < MIN_CADENCE_GAP)
            return "для наклона пройди ещё раз в ЗАМЕТНО другом темпе " +
                "(сейчас разброс " + String.format(java.util.Locale.US, "%.2f", gap) +
                " Гц, нужно " + MIN_CADENCE_GAP + ")"
        return "наклона у тебя НЕТ - и это измерено: темп менялся на " +
            String.format(java.util.Locale.US, "%.2f", gap) +
            " Гц, а длина шага осталась прежней. Значит она постоянная, " +
            "и это правильный ответ, а не незаконченная калибровка"
    }

    fun reset(c: Context) {
        p(c).edit()
            .remove("stride_a").remove("stride_b")
            .remove("stride_manual").remove("stride_by_gps").remove("stride_measured_sl")
            .remove("stride_cal_cadence").remove("stride_personal_slope")
            .remove(KEY_HIST)
            .apply()
    }

    /** Для UI: измеренная длина шага в см или null, если не калибровано. */
    fun measuredStrideCm(c: Context): Int? {
        val pr = p(c)
        if (!pr.getBoolean("stride_manual", false)) return null
        return (pr.getFloat("stride_measured_sl", 0f) * 100).toInt()
    }
}
