package com.vasil.stepcore

import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Калибровка уклона: три отрезка подряд на ОДНОМ склоне.
 *
 * Уклон читается только когда телефон в кармане, поэтому экран рассчитан на
 * работу вслепую: отрезок начинается сам, когда человек пошёл, и закрывается
 * сам, когда он остановился. Обратная связь - вибрацией.
 *
 * Зачем эти якоря: метки уклона копятся в разных условиях (обувь, карман,
 * поверхность), из-за чего класс "ровно" размазан и накрывает "в гору". Три
 * отрезка подряд в одном месте убирают разницу условий - остаётся чистая
 * разница уклона.
 */
class SlopeCalActivity : AppCompatActivity() {

    companion object {
        /** Меньше - медиана шумит. Проверено: на 40 шагах медиана устойчива. */
        const val MIN_STEPS = 40
        /** Остановка дольше этого закрывает отрезок. Светофор обычно короче,
         *  а конец склона - это настоящая остановка. */
        const val PAUSE_MS = 7000L
        /** Столько шагов подряд считаем началом движения. */
        const val START_STEPS = 4
        const val KEY_UP = "slope_anchor_up"
        const val KEY_FLAT = "slope_anchor_flat"
        const val KEY_DOWN = "slope_anchor_down"
        const val KEY_TIME = "slope_anchor_time"
    }

    private val labels = listOf("UP", "FLAT", "DOWN")
    private val titles = listOf("В ГОРУ", "РОВНО", "С ГОРЫ")
    private val rifts = listOf(
        DoodleBorderDrawable.RIFT_UP,
        DoodleBorderDrawable.RIFT_FLAT,
        DoodleBorderDrawable.RIFT_DOWN)

    private var phase = 0
    private var recording = false
    private var startMs = 0L
    private var startSteps = 0
    private var lastStepMs = 0L
    private var lastSeenSteps = 0
    private var finished = false
    private val windows = ArrayList<Triple<String, Long, Long>>()

    private lateinit var root: LinearLayout
    private lateinit var card: TextView
    private lateinit var hint: TextView
    private var dens = 1f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dens = resources.displayMetrics.density
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (18 * dens).toInt()
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        setContentView(root)

        val title = TextView(this)
        title.text = "Калибровка уклона"
        title.textSize = 22f
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        root.addView(title)

        card = TextView(this)
        card.textSize = 17f
        card.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        card.gravity = Gravity.CENTER
        val cp = (20 * dens).toInt()
        card.setPadding(cp, cp, cp, cp)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (16 * dens).toInt()
        root.addView(card, lp)

        hint = TextView(this)
        hint.textSize = 14f
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        hint.setPadding(0, (16 * dens).toInt(), 0, 0)
        hint.setLineSpacing(3f * dens, 1f)
        root.addView(hint)

        val close = TextView(this)
        close.text = "Закрыть"
        close.gravity = Gravity.CENTER
        close.textSize = 16f
        close.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clp.topMargin = (24 * dens).toInt()
        close.setPadding(0, (12 * dens).toInt(), 0, (12 * dens).toInt())
        close.setOnClickListener { finish() }
        root.addView(close, clp)

        if (!StepsState.serviceRunning.value) {
            card.text = "Сначала запусти счёт шагов\nна главном экране"
            paint(R.color.accent_amber, DoodleBorderDrawable.RIFT_NONE)
            hint.text = "Калибровка считает шаги чипом — без работающего счёта измерять нечего."
            return
        }
        armPhase()
        loop()
    }

    /** Рамка в цвет текущего отрезка, трещина показывает направление. */
    private fun paint(colorRes: Int, rift: Int) {
        card.background = DoodleBorderDrawable(
            ContextCompat.getColor(this, colorRes),
            ContextCompat.getColor(this, R.color.surface),
            707L + phase, dens, DoodleBorderDrawable.MAT_ROCK, rift)
    }

    private fun colorOf(i: Int) = when (i) {
        0 -> R.color.accent_amber
        1 -> R.color.accent_teal
        else -> R.color.accent_blue
    }

    private fun vib(pattern: LongArray) {
        try {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) { /* без вибро тоже работает */ }
    }

    private fun vibPulses(n: Int) {
        val p = ArrayList<Long>()
        p.add(0L)
        for (i in 0 until n) { p.add(90L); p.add(160L) }
        vib(p.toLongArray())
    }

    /** Готовность к отрезку: ждём, пока человек пойдёт. */
    private fun armPhase() {
        recording = false
        lastSeenSteps = StepsState.steps.value
        val t = titles[phase]
        paint(colorOf(phase), rifts[phase])
        card.text = "Отрезок ${phase + 1} из 3\n\n$t\n\nПоложи телефон в карман\nи начинай идти"
        hint.text = "Запись начнётся сама, когда пойдёшь (короткая вибрация). " +
            "Дойди до конца отрезка и просто остановись на несколько секунд — " +
            "отрезок закроется сам, две вибрации подтвердят.\n\n" +
            "Все три отрезка проходи в одном месте: тогда условия совпадут " +
            "и останется чистая разница уклона. Нужно минимум $MIN_STEPS шагов."
        vibPulses(phase + 1)
    }

    /** Единственный цикл: следит за шагами, сам начинает и сам заканчивает. */
    private fun loop() {
        lifecycleScope.launch {
            while (!finished) {
                delay(500L)
                if (phase >= labels.size) break
                val now = System.currentTimeMillis()
                val steps = StepsState.steps.value
                if (!recording) {
                    if (steps - lastSeenSteps >= START_STEPS) {
                        recording = true
                        startMs = now
                        startSteps = lastSeenSteps
                        lastStepMs = now
                        vibPulses(1)
                        renderRecording(steps)
                    }
                } else {
                    if (steps > lastSeenSteps) lastStepMs = now
                    lastSeenSteps = steps
                    val done = steps - startSteps
                    renderRecording(steps)
                    if (now - lastStepMs >= PAUSE_MS) {
                        if (done >= MIN_STEPS) {
                            windows.add(Triple(labels[phase], startMs, lastStepMs))
                            vibPulses(2)
                            phase++
                            if (phase >= labels.size) { finished = true; showResult() }
                            else armPhase()
                        } else {
                            vib(longArrayOf(0L, 450L))
                            card.text = "Отрезок ${phase + 1} из 3\n\n" +
                                "${titles[phase]}\n\nМало шагов ($done из $MIN_STEPS)\n" +
                                "Проходим этот отрезок заново"
                            hint.text = "Короткий отрезок даёт шумную медиану, поэтому не " +
                                "засчитываю. Начни идти снова — запись включится сама."
                            recording = false
                            lastSeenSteps = steps
                        }
                    }
                }
                if (!recording && phase < labels.size) lastSeenSteps = maxOf(lastSeenSteps, 0)
            }
        }
    }

    private fun renderRecording(steps: Int) {
        val done = steps - startSteps
        val t = titles[phase]
        card.text = "Идёт запись\n\n$t\n\n$done шагов" +
            (if (done < MIN_STEPS) "\nнужно ещё ${MIN_STEPS - done}" else "\nхватит — можно останавливаться")
        hint.text = "Остановись на конце отрезка — через ${PAUSE_MS / 1000} секунд " +
            "покоя отрезок закроется сам, две вибрации подтвердят. " +
            "Телефон доставать не нужно."
    }

    private fun showResult() {
        vib(longArrayOf(0L, 300L, 200L, 300L))
        paint(R.color.accent_violet, DoodleBorderDrawable.RIFT_NONE)
        card.text = "Считаю…"
        hint.text = ""
        lifecycleScope.launch {
            val dao = AppDb.get(this@SlopeCalActivity).dao()
            val res = LinkedHashMap<String, Float?>()
            val counts = LinkedHashMap<String, Int>()
            for ((lbl, from, to) in windows) {
                val amps = dao.samplesBetween(from, to)
                    .filter { it.sampleSource == 1 }
                    .mapNotNull { it.accP90 ?: it.accRms }
                    .sorted()
                counts[lbl] = amps.size
                res[lbl] = if (amps.isEmpty()) null else amps[amps.size / 2]
            }
            val up = res["UP"]; val flat = res["FLAT"]; val down = res["DOWN"]
            val sb = StringBuilder()
            for ((k, v) in res) {
                val ru = when (k) { "UP" -> "в гору"; "FLAT" -> "ровно"; else -> "с горы" }
                sb.append(ru).append(": ")
                    .append(if (v == null) "нет данных"
                            else String.format(java.util.Locale.US, "%.2f", v))
                    .append("   (строк ").append(counts[k] ?: 0).append(")\n")
            }
            if (up == null || flat == null || down == null) {
                card.text = "Признаков не хватило"
                hint.text = sb.toString() +
                    "\nОбычно это значит, что телефон был в руке или сбор при " +
                    "выключенном экране отключён. Проверь тумблер в SYNX и повтори."
                return@launch
            }
            val ok = up < flat && flat < down
            val g1 = flat - up; val g2 = down - flat
            if (ok) {
                val minGap = minOf(g1, g2)
                card.text = "Порядок сошёлся ✓\n\n" + sb.toString().trim()
                paint(R.color.accent_teal, DoodleBorderDrawable.RIFT_NONE)
                getSharedPreferences(StepService.PREFS, MODE_PRIVATE).edit()
                    .putFloat(KEY_UP, up).putFloat(KEY_FLAT, flat).putFloat(KEY_DOWN, down)
                    .putLong(KEY_TIME, System.currentTimeMillis()).apply()
                hint.text = "В гору мягче, с горы жёстче — как и должно быть.\n" +
                    "Зазор в гору↔ровно: " + String.format(java.util.Locale.US, "%.2f", g1) +
                    "\nЗазор ровно↔с горы: " + String.format(java.util.Locale.US, "%.2f", g2) +
                    "\n\n" + (if (minGap >= 0.5f)
                        "Классы разошлись уверенно. По таким якорям агента можно будет научить различать и «ровно»."
                    else
                        "Классы разошлись, но тесно. Повтори на более крутом склоне — тогда опора будет надёжнее.") +
                    "\n\nЯкоря сохранены."
            } else {
                card.text = "Порядок не сошёлся ✗\n\n" + sb.toString().trim()
                paint(R.color.accent_amber, DoodleBorderDrawable.RIFT_NONE)
                hint.text = "Ожидалось: в гору < ровно < с горы.\n\n" +
                    "Так бывает, если отрезки прошли в разных местах, телефон " +
                    "перекладывали, или уклон слишком слабый.\n\n" +
                    "Якоря НЕ сохранены: неверная опора хуже её отсутствия."
            }
        }
    }
}
