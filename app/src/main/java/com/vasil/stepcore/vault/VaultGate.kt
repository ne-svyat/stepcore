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

/**
 * Формат файла ключей. Вынесен из файловых операций, чтобы разбор байтов
 * проверялся тестами отдельно от Android.
 *
 * Файл содержит ДВЕ обёртки одного ключа данных — паролем и фразой
 * восстановления. Самого ключа в открытом виде нет нигде.
 *
 * Раскладка: "SCV1" | u16 длина обёртки пароля | байты | u16 длина обёртки
 * фразы | байты. Порядок байтов big-endian, как везде в проекте.
 */
object VaultKeyFile {

    private const val M0 = 'S'.code.toByte()
    private const val M1 = 'C'.code.toByte()
    private const val M2 = 'V'.code.toByte()
    private const val M3 = '1'.code.toByte()

    data class Keys(val byPassword: VaultCrypto.Wrap, val byPhrase: VaultCrypto.Wrap)

    fun encode(k: Keys): ByteArray {
        val a = k.byPassword.toBytes()
        val b = k.byPhrase.toBytes()
        require(a.size <= 0xFFFF && b.size <= 0xFFFF) { "wrap too large" }
        val out = ByteArray(4 + 2 + a.size + 2 + b.size)
        out[0] = M0; out[1] = M1; out[2] = M2; out[3] = M3
        var o = 4
        out[o++] = (a.size ushr 8).toByte(); out[o++] = a.size.toByte()
        a.copyInto(out, o); o += a.size
        out[o++] = (b.size ushr 8).toByte(); out[o++] = b.size.toByte()
        b.copyInto(out, o)
        return out
    }

    /** @return null при любой порче: обрезке, чужом файле, битой длине. */
    fun decode(raw: ByteArray): Keys? {
        if (raw.size < 8) return null
        if (raw[0] != M0 || raw[1] != M1 || raw[2] != M2 || raw[3] != M3) return null
        var o = 4
        val la = u16(raw, o) ?: return null; o += 2
        if (o + la > raw.size) return null
        val a = VaultCrypto.Wrap.fromBytes(raw.copyOfRange(o, o + la)) ?: return null
        o += la
        val lb = u16(raw, o) ?: return null; o += 2
        if (o + lb != raw.size) return null
        val b = VaultCrypto.Wrap.fromBytes(raw.copyOfRange(o, o + lb)) ?: return null
        return Keys(a, b)
    }

    private fun u16(b: ByteArray, o: Int): Int? {
        if (o + 2 > b.size) return null
        return ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    }
}
