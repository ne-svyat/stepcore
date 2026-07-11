package com.vasil.stepcore.survival.engine

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * SplitMix64 — собственный детерминированный генератор случайности.
 *
 * Почему не kotlin.random.Random: его алгоритм не зафиксирован контрактом
 * и может измениться между версиями Kotlin/платформы. Архив экспедиции
 * обязан воспроизводиться из seed через годы, поэтому генератор реализован
 * в проекте явно. Алгоритм: SplitMix64 (Steele, Lea, Flood — 2014,
 * public domain), константы — канонические из оригинальной публикации.
 */
class SplitMix64(seed: Long) {

    private var x = seed

    fun nextLong(): Long {
        x += GOLDEN
        var z = x
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Равномерно в диапазоне 0 включительно .. 1 не включительно. */
    fun nextDouble(): Double = (nextLong() ushr 11) * INV_2_53

    /** Равномерно в диапазоне 0 .. bound-1. */
    fun nextInt(bound: Int): Int {
        val r = (nextDouble() * bound).toInt()
        return if (r >= bound) bound - 1 else r
    }

    /** Гауссов шум по Боксу-Мюллеру: среднее 0, сигма 1. Тратит два nextLong. */
    fun nextGaussian(): Double {
        val u1 = 1.0 - nextDouble() // сдвиг в (0,1] — защита от ln(0)
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * kotlin.math.PI * u2)
    }

    companion object {
        private const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        private const val MIX1 = -0x40a7b892e31b1a47L   // 0xBF58476D1CE4E5B9
        private const val MIX2 = -0x6b2fb644ecceee15L   // 0x94D049BB133111EB
        private const val INV_2_53 = 1.0 / (1L shl 53)

        /**
         * Независимый генератор для конкретного тика (дня мира).
         *
         * Ключевое свойство детерминизма: поток случайности тика N зависит
         * только от (seed, N) и не зависит от того, какими партиями
         * досчитывались тики. Catch-up одним куском в 100 дней и десятью
         * кусками по 10 дней дают побайтово одинаковый мир — целый класс
         * багов рассинхронизации потока исключён конструкцией.
         */
        fun forTick(expeditionSeed: Long, tick: Int): SplitMix64 {
            // прогрев одним раундом перемешивания: соседние тики не должны
            // стартовать с коррелированных внутренних состояний
            val warm = SplitMix64(expeditionSeed xor (tick.toLong() * GOLDEN))
            return SplitMix64(warm.nextLong())
        }
    }
}
