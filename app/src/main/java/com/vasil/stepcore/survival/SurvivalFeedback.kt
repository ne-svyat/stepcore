package com.vasil.stepcore.survival

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.vasil.stepcore.R

/**
 * Что принёс догон мира. Считается на радаре ДО и ПОСЛЕ догона — озвучивается
 * только НОВОЕ знание, поэтому сигнал не повторяется на каждом обновлении.
 */
enum class Signal { NONE, EVENT, CHOICE, DANGER }

/**
 * Голос тайги: звук и вибро на то, что случилось, пока ты шёл.
 *
 * Границы намеренно узкие: класс ничего не решает и не видит мир напрямую —
 * ему передают уже готовый Signal, посчитанный из того же радара, что видит
 * игрок. Звучит только когда экран открыт: фоновой симуляции нет, значит и
 * голосу неоткуда взяться в кармане. Это честно и будет расширено отдельно.
 */
class SurvivalFeedback(context: Context) {

    private val app = context.applicationContext
    private val pool: SoundPool
    private val idChoice: Int
    private val idDanger: Int
    private val idEvent: Int

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
        idChoice = pool.load(app, R.raw.alert_choice, 1)
        idDanger = pool.load(app, R.raw.alert_danger, 1)
        idEvent = pool.load(app, R.raw.alert_event, 1)
    }

    /**
     * Каждый сигнал — свой звук и свой почерк вибро:
     *  - опасность: длинная настойчивая (хищник чует лагерь);
     *  - выбор: короткая двойная (есть куда повернуть, а ты на автопилоте);
     *  - событие: одна средняя (в чувства вошло новое).
     */
    fun play(signal: Signal, soundOn: Boolean, vibeOn: Boolean) {
        when (signal) {
            Signal.NONE -> return
            Signal.DANGER -> { if (soundOn) shot(idDanger); if (vibeOn) buzz(longArrayOf(0, 300, 120, 300)) }
            Signal.CHOICE -> { if (soundOn) shot(idChoice); if (vibeOn) buzz(longArrayOf(0, 40, 60, 40)) }
            Signal.EVENT -> { if (soundOn) shot(idEvent); if (vibeOn) buzz(longArrayOf(0, 120)) }
        }
    }

    private fun shot(id: Int) { if (id != 0) pool.play(id, 1f, 1f, 1, 0, 1f) }

    private fun buzz(pattern: LongArray) {
        val vib = vibrator() ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun release() { pool.release() }
}
