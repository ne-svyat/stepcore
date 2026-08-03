package com.vasil.stepcore

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * v312. Голосовые реплики калибровки.
 *
 * Зачем: вибрацию в кармане не слышно, а доставать телефон во время замера
 * значит его испортить. Голос - единственный способ понять, что калибровка
 * началась и закончилась, не трогая телефон.
 *
 * Как устроено:
 *  - файлы лежат в res/raw и ищутся ПО ИМЕНИ во время работы. Поэтому
 *    добавление новых вариантов не требует правки кода: положил файл -
 *    он попал в ротацию;
 *  - вариант выбирается мешком (VoiceBag): без повторов внутри круга и
 *    без повтора на стыке кругов;
 *  - есть мужской и женский вариант реплики (суффиксы _m и _w) - берётся
 *    по полу из профиля, а если такого нет, обычный без суффикса;
 *  - ОТСУТСТВИЕ ФАЙЛА НЕ ЛОМАЕТ НИЧЕГО: реплики просто нет, остаётся
 *    вибрация. Это позволяет добавлять озвучку по частям.
 */
object Voice {

    private const val PREFS_STATE = "voice_bag_"
    private const val KEY_ENABLED = "voice_enabled"
    private const val MAX_VARIANTS = 20

    private val bag = VoiceBag { n -> (Math.random() * n).toInt().coerceIn(0, n - 1) }
    private val cache = HashMap<String, List<Int>>()
    private var player: MediaPlayer? = null

    fun enabled(c: Context): Boolean =
        c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * Сыграть реплику по ключу, например "cal_walk_start".
     * Возвращает false, если файлов нет - вызывающий может дать вибрацию.
     */
    fun say(c: Context, key: String): Boolean {
        if (!enabled(c)) return false
        val ids = variants(c, key)
        if (ids.isEmpty()) return false
        val prefs = c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)
        val stateKey = PREFS_STATE + key
        val (idx, newState) = bag.next(ids.size, prefs.getString(stateKey, "") ?: "")
        if (idx < 0 || idx >= ids.size) return false
        prefs.edit().putString(stateKey, newState).apply()
        return play(c, ids[idx])
    }

    /** Список существующих файлов для ключа, с учётом пола. */
    private fun variants(c: Context, key: String): List<Int> {
        val male = c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)
            .getString("p_sex", "m") != "f"
        val cacheKey = key + if (male) "_m" else "_w"
        cache[cacheKey]?.let { return it }
        val out = ArrayList<Int>()
        val pkg = c.packageName
        for (i in 1..MAX_VARIANTS) {
            val base = "v_" + key + "_" + String.format("%02d", i)
            // Сначала вариант своего пола, потом общий.
            val gendered = base + if (male) "_m" else "_w"
            var id = c.resources.getIdentifier(gendered, "raw", pkg)
            if (id == 0) id = c.resources.getIdentifier(base, "raw", pkg)
            if (id != 0) out.add(id)
        }
        cache[cacheKey] = out
        return out
    }

    private fun play(c: Context, resId: Int): Boolean = try {
        player?.runCatching { release() }
        val mp = MediaPlayer.create(c.applicationContext, resId)
        if (mp == null) false
        else {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    // Речь помощника: звучит поверх музыки, а не глушит её
                    // насовсем, и идёт в наушники вместе с треком.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnCompletionListener { it.runCatching { release() }; player = null }
            player = mp
            mp.start()
            true
        }
    } catch (e: Exception) {
        false
    }

    /** Сбросить кэш - после добавления файлов в новой сборке. */
    fun invalidate() = cache.clear()
}
