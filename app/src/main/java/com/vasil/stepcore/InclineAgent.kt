package com.vasil.stepcore

/**
 * InclineAgent (L3.1) — предполагает уклон сессии по её признакам.
 * Только read-only над уже посчитанными числами: счёт шагов не трогает.
 *
 * Основание — измерено на корпусе пользователя (54 сессии, 20 уклонных):
 * с горы приземление жёстче -> вертикальная амплитуда ВЫШЕ; в гору шаг
 * короче и мягче -> амплитуда НИЖЕ. Разделение чистое, пересечений нет.
 *
 * Абсолютная шкала уплывает от того, как телефон лежит в кармане
 * (у одной прогулки граница ~6.8, у другой ~7.35), поэтому основной режим —
 * ОТНОСИТЕЛЬНЫЙ: сравниваем с медианой той же прогулки. Проверено
 * leave-one-out: относительный 20/20, абсолютный 18/20.
 *
 * Но относительная база врёт на ОДНОРОДНОЙ прогулке (сплошной подъём:
 * 3 ошибки из 6). Защита — ворота по размаху: у смешанных прогулок размах
 * 1.5-3.8, у однородной 0.67. Мало размаха -> откат на абсолютный порог.
 */
object InclineAgent {

    // Телефон в руке даёт совсем другую амплитуду (медиана 1.77 против 6.81
    // в кармане) — там основания нет, молчим.
    const val POCKET_MIN = 0.6f
    // Разрыв больше 2 часов — уже другая прогулка, другая геометрия кармана.
    const val WALK_GAP_MS = 2 * 3600_000L
    // Размах амплитуды внутри прогулки, при котором база осмысленна.
    // Измерено: смешанные прогулки 1.47 и 3.76, однородная 0.67.
    const val SPREAD_MIN = 1.0f
    // Относительный порог: середина между центрами классов (UP -0.309, DOWN +0.720).
    const val REL_THRESHOLD = 0.205f
    // Запасной абсолютный порог (середина между медианами классов).
    const val ABS_THRESHOLD = 7.082f
    // Зона "не уверен": ближе к границе, чем самый тесный проверенный случай
    // (минимальный запас 0.147) — там доказательств нет, честнее спросить.
    const val UNSURE_BAND = 0.15f

    enum class Verdict { UP, DOWN, UNSURE, NO_BASIS }

    data class Input(
        val startMs: Long,
        val chipShare: Float,
        val ampMed: Float
    )

    data class Result(
        val verdict: Verdict,
        val margin: Float,      // запас до границы: чем больше, тем увереннее
        val relative: Boolean   // база от прогулки (true) или абсолютная
    )

    fun predict(target: Input, all: List<Input>): Result {
        if (target.chipShare < POCKET_MIN)
            return Result(Verdict.NO_BASIS, 0f, false)

        val walk = all.filter {
            it.chipShare >= POCKET_MIN &&
                kotlin.math.abs(it.startMs - target.startMs) <= WALK_GAP_MS
        }
        val amps = walk.map { it.ampMed }.sorted()
        val useRel = walk.size >= 3 && (amps.last() - amps.first()) >= SPREAD_MIN

        val value: Float
        val threshold: Float
        if (useRel) {
            val base = amps[amps.size / 2]          // медиана, не среднее
            value = target.ampMed - base
            threshold = REL_THRESHOLD
        } else {
            value = target.ampMed
            threshold = ABS_THRESHOLD
        }
        val margin = kotlin.math.abs(value - threshold)
        val verdict = when {
            margin < UNSURE_BAND -> Verdict.UNSURE
            value > threshold -> Verdict.DOWN
            else -> Verdict.UP
        }
        return Result(verdict, margin, useRel)
    }

    /** Нижняя граница Уилсона (95%): честная оценка доли на малых числах.
     *  5/5 сырых 100% дают всего 57% — доказательств мало. Автометку
     *  разрешаем только при >= 0.90: нужна И точность, И объём. */
    fun wilsonLower(k: Int, n: Int): Float {
        if (n <= 0) return 0f
        val z = 1.96
        val p = k.toDouble() / n
        val d = 1 + z * z / n
        val c = p + z * z / (2 * n)
        val m = z * kotlin.math.sqrt((p * (1 - p) + z * z / (4 * n)) / n)
        return (((c - m) / d).coerceIn(0.0, 1.0)).toFloat()
    }

    /** Рейтинг зрелости — питается той же цифрой, что решает автоматику. */
    fun maturity(k: Int, n: Int): String {
        if (n < 10) return "не знает тебя"
        val lb = wilsonLower(k, n)
        return when {
            lb >= 0.90f -> "доверие"
            lb >= 0.85f -> "отлично"
            lb >= 0.75f -> "хорошо"
            lb >= 0.65f -> "средне"
            else -> "начинает понимать"
        }
    }
}
