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
    private const val HEIGHT_FACTOR_WALK = 0.414f
    private const val HEIGHT_FACTOR_RUN = 0.65f

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

    fun runStrideM(c: Context): Float = runStrideMOf(p(c).getInt("p_height", 0))

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
            a = A_DEFAULT
            val clean = cleanPoints(hist)
            val medSL = medianStride(clean) ?: measuredSL
            val medCad = if (clean.isEmpty()) cadence
                else clean.map { it.cadence }.sorted()[clean.size / 2]
            b = medSL - A_DEFAULT * medCad
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

    /** Медиана длины шага по истории - устойчивый якорь. В отличие от
     *  последнего замера не скачет от выброса к выбросу. */
    fun medianStride(h: List<CalPoint>): Float? {
        if (h.isEmpty()) return null
        val v = h.map { it.strideM }.sorted()
        return v[v.size / 2]
    }

    /** Медианное абсолютное отклонение - устойчивая мера разброса. */
    fun madStride(h: List<CalPoint>): Float {
        val med = medianStride(h) ?: return 0f
        val dev = h.map { kotlin.math.abs(it.strideM - med) }.sorted()
        if (dev.isEmpty()) return 0f
        return dev[dev.size / 2]
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
        val pts = cleanPoints(h)
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
        return a to (my - a * mx)
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
    fun calibrationReport(c: Context): String {
        val h = calHistory(c)
        val sb = StringBuilder("StepCore — отчёт калибровки\n\n")
        if (h.isEmpty()) return sb.append("Калибровок пока нет.").toString()
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru"))
        sb.append("Калибровок сохранено: ").append(h.size).append("\n\n")
        for ((i, x) in h.withIndex()) {
            sb.append(i + 1).append(") ").append(fmt.format(java.util.Date(x.timeMs)))
              .append("  темп ").append(String.format(java.util.Locale.US, "%.2f", x.cadence))
              .append(" Гц  шаг ").append((x.strideM * 100).toInt()).append(" см")
              .append("  (").append(x.metres.toInt()).append(" м / ")
              .append(x.steps).append(" шаг.)\n")
        }
        val cads = h.map { it.cadence }
        val spread = (cads.max() - cads.min())
        sb.append("\nРазброс темпа: ")
          .append(String.format(java.util.Locale.US, "%.2f", spread)).append(" Гц")
        sb.append(" (нужно ≥ ").append(MIN_CADENCE_GAP).append(")\n")
        val clean = cleanPoints(h)
        val mad = madStride(h)
        val med = medianStride(clean)
        if (med != null) {
            sb.append("Медиана длины шага: ").append((med * 100).toInt())
              .append(" см  (её и берём за опору)\n")
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
