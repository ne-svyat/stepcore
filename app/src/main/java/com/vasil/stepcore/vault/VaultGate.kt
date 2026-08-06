package com.vasil.stepcore.vault

/**
 * Автомат секретного жеста. Чистый Kotlin: ни View, ни Context, ни времени
 * системы — время приходит параметром. Поэтому проверяется тестами, а не
 * пальцем по экрану.
 *
 * ЗАМЫСЕЛ
 * -------
 * Двойной тап по луне -> в течение 5 секунд двойной тап по свитку -> вход.
 * Никаких подсказок и никакого "секрет найден" при промахе: элементы должны
 * оставаться декоративными для того, кто не знает.
 *
 * ПОЧЕМУ АВТОМАТ ОТДЕЛЬНО ОТ ВЬЮХ
 * -------------------------------
 * CrystalRingView рисует гору, MotiveScrollView рисует свиток. Знание о
 * существовании Vault внутри них — смешение ответственностей и, что важнее,
 * невозможность проверить последовательность касаний без устройства.
 * Вьюхи сообщают факт "по мне тапнули", решение принимает этот класс.
 *
 * ПОЧЕМУ ЛЮБОЕ ЧУЖОЕ КАСАНИЕ СБРАСЫВАЕТ
 * -------------------------------------
 * Без этого случайная комбинация из обычного пользования однажды сложилась
 * бы сама. Требование "два двойных тапа подряд, между ними ничего" делает
 * случайное срабатывание практически невозможным.
 */
class VaultGate(
    /** Порог двойного тапа. 400 мс — системное значение Android. */
    private val doubleTapMs: Long = 400,
    /** Окно между луной и свитком. */
    private val armWindowMs: Long = 5000,
) {

    /** Куда пришло касание. */
    enum class Spot { MOON, SCROLL, ELSEWHERE }

    /**
     * NONE  — ничего не произошло, виду молчать.
     * ARMED — луна принята, пошло окно 5 секунд (короткая вибрация).
     * OPEN  — жест собран целиком.
     */
    enum class Signal { NONE, ARMED, OPEN }

    private var lastSpot: Spot? = null
    private var lastTapMs = 0L
    private var armedUntil = 0L

    fun onTap(spot: Spot, now: Long): Signal {
        if (armedUntil != 0L && now > armedUntil) armedUntil = 0L

        if (spot == Spot.ELSEWHERE) {
            reset()
            return Signal.NONE
        }

        val isDouble = lastSpot == spot && now - lastTapMs <= doubleTapMs
        if (isDouble) {
            // Двойной тап израсходован: третий тап подряд не считается вторым
            // двойным, иначе быстрое постукивание собирало бы жест само.
            lastSpot = null
            lastTapMs = 0L
        } else {
            lastSpot = spot
            lastTapMs = now
            return Signal.NONE
        }

        return when (spot) {
            Spot.MOON -> {
                armedUntil = now + armWindowMs
                Signal.ARMED
            }
            Spot.SCROLL -> {
                if (armedUntil != 0L) {
                    reset()
                    Signal.OPEN
                } else {
                    Signal.NONE
                }
            }
            Spot.ELSEWHERE -> Signal.NONE
        }
    }

    fun isArmed(now: Long): Boolean = armedUntil != 0L && now <= armedUntil

    fun reset() {
        lastSpot = null
        lastTapMs = 0L
        armedUntil = 0L
    }
}
