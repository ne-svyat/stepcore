package com.vasil.stepcore

/**
 * L2 — агрегатор сессий (чистый Kotlin, без Android).
 *
 * Берёт поток образцов корпуса (по возрастанию времени) и режет его на
 * СЕССИИ — непрерывные однородные куски движения. По каждой считает
 * агрегаты трёх слоёв. Детерминизм: те же образцы -> те же сессии, что
 * даёт тестируемость в песочнице и прослеживаемость (timeMs краёв).
 *
 * Логика вынесена сюда, чтобы Room/Android её не касались: движок
 * гоняется юнит-тестами тысячами прогонов до установки на телефон.
 */

/** Вход агрегатора — ровно те поля корпуса, что нужны сессии. */
data class SampleIn(
    val timeMs: Long,
    val label: String,        // UP / FLAT / DOWN
    val mode: String,         // WALK / RUN / IDLE / TRANSPORT
    val featureVersion: Int,
    val sampleSource: Int,    // 0 детектор, 1 чип
    // амплитуда/каденс: берём из независимого канала, если это строка чипа
    val amp: Float?,          // amp (детектор) или accRms (чип)
    val cadence: Float?,      // 1000/intervalMs или zcrCadence
    val pitchDeg: Float?,
    val gyro: Float?
)

/** Строка сессии — три слоя (см. концепт-документ). */
data class SessionOut(
    // --- слой 1: что это было ---
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val label: String,
    val nSamples: Int,
    val reliable: Boolean,
    val modeShare: Map<String, Float>,   // доли WALK/RUN/...
    // --- слой 2: как выглядело движение ---
    val ampMed: Float?, val ampIqr: Float?,
    val cadenceMed: Float?, val cadenceIqr: Float?,
    val pitchMed: Float?,
    val gyroMed: Float?,
    val chipShare: Float,                // доля строк от чипа (карман)
    val featureVersion: Int,
    // --- слой 3: неочевидный задел ---
    val ampTrend: Float?,                // наклон амплитуды по сессии (устал?)
    val cadenceTrend: Float?,
    val rhythmStab: Float?,              // IQR интервала / медиана (ровность)
    val pitchRange: Float?,              // размах наклона (менял хват?)
    // --- задел под L3 ---
    val confirmState: Int                // 0 не спрошено (наполнится в L3)
)

object SessionEngine {

    // Границы сессии.
    // v197: паузы (светофор, отдышаться на склоне) больше НЕ рвут сессию -
    // прогулка целиком одна сессия. Разрыв рвёт только когда прогулка реально
    // окончена (ушёл домой, ночь). Урок трекеров бега: резать по паузе -
    // ошибка, бесившая пользователей; конец ставится по устойчивой смене
    // состояния, а не по тишине.
    // SESSION_END_GAP_MS - провизорно 5 мин (долина между короткой паузой и
    // отдельным выходом). Уточняется по гистограмме разрывов из кнопки
    // "Пересобрать сессии" на реальном корпусе.
    const val SESSION_END_GAP_MS = 300_000L
    // Смена метки уклона рвёт сессию, только если новая метка держится
    // >= LABEL_CONFIRM образцов подряд. Одиночный чужой образец (мис-тап, шум)
    // поглощается. Конституция: не решать по одному измерению.
    const val LABEL_CONFIRM = 2
    const val MIN_SAMPLES = 10           // короче -> reliable=false
    const val MIN_DURATION_MS = 30_000L
    // короткое мелькание транспорта НЕ рвёт; долгое рвёт
    const val TRANSPORT_BREAK_MS = 15_000L

    fun build(
        samples: List<SampleIn>,
        sessionEndGapMs: Long = SESSION_END_GAP_MS
    ): List<SessionOut> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.timeMs }
        val out = ArrayList<SessionOut>()
        var cur = ArrayList<SampleIn>()
        var curLabel = ""            // установленная метка текущей сессии
        var labelCand = ""           // кандидат на новую метку
        var labelCandRun = 0         // сколько образцов подряд держится кандидат
        var transportSince = -1L

        fun flush() {
            if (cur.isNotEmpty()) { out.add(aggregate(cur)); cur = ArrayList() }
        }

        for (s in sorted) {
            if (cur.isEmpty()) {
                cur.add(s); curLabel = s.label
                labelCand = ""; labelCandRun = 0; transportSince = -1L
                continue
            }
            val gap = s.timeMs - cur.last().timeMs

            // 1. прогулка окончена: длинный разрыв
            if (gap > sessionEndGapMs) {
                flush()
                cur.add(s); curLabel = s.label
                labelCand = ""; labelCandRun = 0; transportSince = -1L
                continue
            }

            // 2. устойчивая смена метки уклона (с подтверждением)
            if (s.label != curLabel) {
                if (s.label == labelCand) labelCandRun++
                else { labelCand = s.label; labelCandRun = 1 }
                if (labelCandRun >= LABEL_CONFIRM) {
                    // ретро-разрез: последние (LABEL_CONFIRM-1) образцов уже
                    // относятся к новой метке -> переносим их в новую сессию.
                    val tail = ArrayList<SampleIn>()
                    repeat(LABEL_CONFIRM - 1) {
                        if (cur.isNotEmpty()) tail.add(0, cur.removeAt(cur.size - 1))
                    }
                    flush()                       // старая сессия без хвоста
                    cur.addAll(tail)
                    cur.add(s); curLabel = s.label
                    labelCand = ""; labelCandRun = 0; transportSince = -1L
                } else {
                    cur.add(s)                    // одиночный чужой образец поглощён
                }
                continue
            } else {
                labelCand = ""; labelCandRun = 0
            }

            // 3. долгий транспорт рвёт (короткое мелькание - нет)
            if (s.mode == "TRANSPORT") {
                if (transportSince < 0) transportSince = s.timeMs
                if (s.timeMs - transportSince > TRANSPORT_BREAK_MS) {
                    // выкидываем накопленный транспортный хвост из сессии
                    while (cur.isNotEmpty() && cur.last().mode == "TRANSPORT") cur.removeAt(cur.size - 1)
                    flush()
                    cur.add(s); curLabel = s.label
                    labelCand = ""; labelCandRun = 0; transportSince = s.timeMs
                    continue
                }
            } else transportSince = -1L

            cur.add(s)
        }
        flush()
        return out
    }

    private fun aggregate(g: List<SampleIn>): SessionOut {
        val start = g.first().timeMs; val end = g.last().timeMs
        val dur = end - start
        val n = g.size
        val reliable = n >= MIN_SAMPLES && dur >= MIN_DURATION_MS

        val modeShare = g.groupingBy { it.mode }.eachCount()
            .mapValues { it.value.toFloat() / n }

        val amps = g.mapNotNull { it.amp }
        val cads = g.mapNotNull { it.cadence }
        val pitches = g.mapNotNull { it.pitchDeg }
        val gyros = g.mapNotNull { it.gyro }
        val chipShare = g.count { it.sampleSource == 1 }.toFloat() / n

        return SessionOut(
            startMs = start, endMs = end, durationMs = dur,
            label = g.first().label, nSamples = n, reliable = reliable,
            modeShare = modeShare,
            ampMed = median(amps), ampIqr = iqr(amps),
            cadenceMed = median(cads), cadenceIqr = iqr(cads),
            pitchMed = median(pitches),
            gyroMed = median(gyros),
            chipShare = chipShare,
            featureVersion = g.minOf { it.featureVersion },
            ampTrend = trend(g.mapNotNull { p -> p.amp?.let { p.timeMs to it } }),
            cadenceTrend = trend(g.mapNotNull { p -> p.cadence?.let { p.timeMs to it } }),
            rhythmStab = median(cads)?.let { m -> if (m > 0) iqr(cads)?.div(m) else null },
            pitchRange = if (pitches.size >= 2) pitches.max() - pitches.min() else null,
            confirmState = 0
        )
    }

    // --- статистика: медиана и IQR (устойчивы к выбросам) ---
    fun median(xs: List<Float>): Float? {
        if (xs.isEmpty()) return null
        val s = xs.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }
    fun iqr(xs: List<Float>): Float? {
        if (xs.size < 4) return null
        val s = xs.sorted()
        return quantile(s, 0.75f) - quantile(s, 0.25f)
    }
    private fun quantile(sorted: List<Float>, q: Float): Float {
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt(); val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }
    /** Наклон линии тренда (least squares) по (t, value); нормируем t в секунды. */
    private fun trend(pts: List<Pair<Long, Float>>): Float? {
        if (pts.size < 3) return null
        val t0 = pts.first().first
        val xs = pts.map { (it.first - t0) / 1000f }
        val ys = pts.map { it.second }
        val n = xs.size
        val mx = xs.average().toFloat(); val my = ys.average().toFloat()
        var num = 0f; var den = 0f
        for (i in 0 until n) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        return if (den == 0f) null else num / den
    }
}

