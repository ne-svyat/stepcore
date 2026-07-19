package com.vasil.stepcore

import kotlin.math.sqrt

/**
 * Guard 1: карантин вместо расстрела.
 *
 * ЗАЧЕМ. До v184 дельты аппаратного чипа, пришедшие во время shake-
 * блокировки, отбрасывались навсегда. Журнал 19.07 (11:16-11:22):
 * телефон лежал в кармане при включённом экране, гироскоп держался
 * 2.1-4.7 при пороге 3.5, и за 6 минут ходьбы было выброшено
 * 728 шагов, посчитанных чипом абсолютно верно.
 *
 * Поднять порог нельзя: карман даёт 2.1-4.7, настоящая тряска 3.6-8.0.
 * Диапазоны перекрываются, разделяющего числа не существует.
 *
 * ЧТО РАЗДЕЛЯЕТ. Не сила вращения, а РОВНОСТЬ ТЕМПА чипа (измерено
 * на журнале 19.07, десятисекундные окна):
 *
 *   карман, ходьба   36 окон   1.80-2.30 ш/с   разброс  6.8%
 *   тряска лёгкая     4 окна   0.10-1.70 ш/с   разброс 59.8%
 *   тряска сильная    3 окна   1.10-2.10 ш/с   разброс 25.2%
 *
 * Между 6.8% и 25.2% нет ни одного измерения - это зазор, а не
 * подобранное число. Пороги ниже выведены из него.
 *
 * ГАРАНТИЯ. Класс не может уменьшить счёт. Всё, что засчитывалось до
 * него, засчитывается и после; меняется только судьба выброшенного:
 * часть возвращается, остальное отбрасывается как и раньше.
 *
 * Чистый Kotlin, ноль android.* - проверяется юнит-тестами до сборки.
 */
class ShakeHold {

    /**
     * release   - засчитать немедленно
     * discarded - отброшено окончательно (лежало в карантине, но не
     *             попало в доказанный отрезок ритма)
     */
    data class Verdict(val release: Int, val discarded: Int, val reason: String?)

    // История дельт под тряской. НЕ чистится при засчитывании: окна
    // правила отсчитываются назад по времени, и очистка рвала бы
    // покрытие, роняя подтверждение на каждом цикле.
    private val timesMs = ArrayList<Long>()
    private val deltas = ArrayList<Int>()
    // Всё до этого индекса уже засчитано и повторно выдано не будет.
    private var settledIdx = 0
    private var confirmed = false

    /** Сколько шагов сейчас лежит в карантине (ещё не засчитано). */
    val heldSteps: Int get() {
        var s = 0
        for (i in settledIdx until deltas.size) s += deltas[i]
        return s
    }

    /** Подтверждена ли локомоция под тряской. */
    val isConfirmed: Boolean get() = confirmed

    /**
     * Пришла дельта чипа во время shake-блокировки.
     * Возвращает, сколько шагов засчитать немедленно.
     */
    fun onShakenDelta(nowMs: Long, delta: Int): Verdict {
        if (delta <= 0) return Verdict(0, 0, null)

        timesMs.add(nowMs)
        deltas.add(delta)
        dropOlderThan(nowMs - MAX_HOLD_MS)

        val ok = locomotionRuleHolds(nowMs, MIN_WINDOWS)

        if (confirmed) {
            // Пока материала меньше MIN_JUDGE_WINDOWS, судить не о чем:
            // это либо начало эпизода после carryOver, либо первые окна
            // обычного подтверждения. Обрывать счёт на нехватке истории
            // нельзя - иначе доверие, выданное детектором, гасло бы на
            // первой же дельте.
            val judged = windowsAvailable(nowMs)
            if (judged < MIN_JUDGE_WINDOWS || locomotionRuleHolds(nowMs, judged)) {
                // Локомоция продолжается: дельта идёт в счёт сразу.
                // Задержка есть только на входе в эпизод.
                val out = heldSteps
                settledIdx = deltas.size
                return Verdict(out, 0, null)
            }
            confirmed = false
            return Verdict(0, 0, "ритм сломался, снова карантин")
        }

        if (ok) {
            confirmed = true
            // Доказательный отрезок НЕ засчитывается задним числом.
            //
            // Почему так. Хвост тряски может случайно совпасть с
            // каденсом ходьбы; если он примыкает к началу ходьбы, отличить
            // его от неё нечем, и ретроактивный зачёт добавил бы ложные
            // шаги. Конституция проекта: лучше недосчитать один, чем
            // добавить десять. Поэтому первые MIN_WINDOWS окон каждого
            // эпизода - плата за доказательство, а дальше счёт идёт
            // без потерь.
            //
            // Цена измерена на журнале 19.07: из 728 шагов кармана
            // теряется 110 (15%) на шестиминутном эпизоде и тем меньше,
            // чем длиннее прогулка. Было потеряно 728 из 728.
            val lost = heldSteps
            settledIdx = deltas.size
            return Verdict(0, lost,
                "ровный темп ${MIN_WINDOWS * WINDOW_MS / 1000} с - человек идёт, счёт открыт")
        }
        return Verdict(0, 0, null)
    }

    /**
     * Тряска кончилась. Всё, что не подтвердилось, отбрасывается -
     * ровно так же, как это делалось до v184.
     * Возвращает число отброшенных шагов (для журнала).
     */
    fun onShakeEnded(): Int {
        val lost = heldSteps
        clear()
        confirmed = false
        return lost
    }

    /**
     * v187: локомоция УЖЕ доказана детектором.
     *
     * Перед каждым из семи карантинов 19.07 детектор подтверждал ходьбу
     * за считанные секунды до начала тряски - и карантин заставлял
     * доказывать её заново по сорок секунд. Суммарно 474 шага из 594.
     *
     * Вызывается только на входе в эпизод, когда в карантине ещё пусто.
     * Правило ровности после этого продолжает работать и оборвёт счёт,
     * как только появится чем судить (см. MIN_JUDGE_WINDOWS).
     *
     * Возвращает true, если счёт действительно открыт этим вызовом.
     */
    fun carryOver(): Boolean {
        if (confirmed) return false
        if (deltas.size > settledIdx) return false
        confirmed = true
        return true
    }

    fun reset() { clear(); confirmed = false }

    private fun clear() { timesMs.clear(); deltas.clear(); settledIdx = 0 }

    private fun dropOlderThan(cutMs: Long) {
        while (timesMs.isNotEmpty() && timesMs[0] < cutMs) {
            timesMs.removeAt(0); deltas.removeAt(0)
            if (settledIdx > 0) settledIdx--
        }
    }

    /**
     * Правило локомоции: MIN_WINDOWS подряд окон по WINDOW_MS, в каждом
     * темп в диапазоне ходьбы-бега, и разброс темпа между окнами мал.
     *
     * Окна отсчитываются назад от последней дельты. Требуется полное
     * покрытие: буфер обязан начинаться не позже начала самого раннего
     * окна, иначе неполное окно дало бы заниженный темп.
     */
    /** Сколько полных окон покрыто буфером, не больше MIN_WINDOWS. */
    private fun windowsAvailable(nowMs: Long): Int {
        if (timesMs.isEmpty()) return 0
        val spanMs = nowMs - timesMs[0]
        if (spanMs < WINDOW_MS) return 0
        val n = (spanMs / WINDOW_MS).toInt()
        return if (n > MIN_WINDOWS) MIN_WINDOWS else n
    }

    private fun locomotionRuleHolds(nowMs: Long, windows: Int): Boolean {
        if (timesMs.isEmpty() || windows <= 0) return false
        val spanStart = nowMs - windows * WINDOW_MS
        if (timesMs[0] > spanStart) return false

        val counts = IntArray(windows)
        for (i in timesMs.indices) {
            val age = nowMs - timesMs[i]
            if (age < 0 || age >= windows * WINDOW_MS) continue
            val w = (age / WINDOW_MS).toInt()
            counts[w] += deltas[i]
        }

        val secPerWindow = WINDOW_MS / 1000f
        for (c in counts) {
            val rate = c / secPerWindow
            if (rate < RATE_MIN || rate > RATE_MAX) return false
        }

        var s = 0.0
        for (c in counts) s += c
        val mean = s / windows
        if (mean <= 0.0) return false
        var v = 0.0
        for (c in counts) { val d = c - mean; v += d * d }
        val cv = sqrt(v / windows) / mean
        return cv < CV_MAX
    }

    companion object {
        /** Окно измерения. Равно наблюдаемому периоду выдачи дельт чипа
         *  на MIUI: в журнале 19.07 дельты приходили каждые ~10 с. */
        const val WINDOW_MS = 10_000L

        /** Сколько окон подряд должны выполнить правило. Самый длинный
         *  измеренный эпизод тряски занял 2 окна; 4 - двойной запас. */
        const val MIN_WINDOWS = 4

        /**
         * Минимум окон, по которым уже можно судить (v187). Три, а не два:
         * измеренная сильная тряска дала 11/17/21 шагов за окно, и первые
         * два (1.7 и 2.1 ш/с) попадают в диапазон ходьбы. Третье окно с
         * 1.1 ш/с ломает и диапазон, и разброс. На двух окнах тряска
         * прошла бы.
         */
        const val MIN_JUDGE_WINDOWS = 3

        /** Границы темпа локомоции, шагов в секунду.
         *  Измерено: ходьба в кармане 1.80-2.30; бег при интервале
         *  322-383 мс даёт 2.6-3.1. Границы взяты с запасом. */
        const val RATE_MIN = 1.5f
        const val RATE_MAX = 3.5f

        /** Порог разброса темпа. Измеренный зазор: ходьба 6.8%,
         *  ближайшая тряска 25.2%. 15% - середина зазора. */
        const val CV_MAX = 0.15f

        /** Предел карантина. Дальше держать бессмысленно: эпизод такой
         *  длины уже не отличим от осмысленного движения. */
        const val MAX_HOLD_MS = 600_000L
    }
}

