package com.vasil.stepcore

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Калибровка уклона: три отрезка подряд на ОДНОМ склоне.
 *
 * Зачем именно так: метки уклона копятся в разных условиях (обувь, карман,
 * поверхность), поэтому класс "ровно" размазан и накрывает "в гору". Если
 * пройти все три подряд в одном месте, условия совпадают, и остаётся чистая
 * разница уклона - личные якоря амплитуды.
 */
class SlopeCalActivity : AppCompatActivity() {

    companion object {
        /** Меньше - медиана шумит; больше - тяжело найти ровный склон. */
        const val MIN_STEPS = 40
        const val KEY_UP = "slope_anchor_up"
        const val KEY_FLAT = "slope_anchor_flat"
        const val KEY_DOWN = "slope_anchor_down"
        const val KEY_TIME = "slope_anchor_time"
    }

    private val labels = listOf("UP", "FLAT", "DOWN")
    private val titles = listOf("В ГОРУ", "РОВНО", "С ГОРЫ")
    private var phase = 0                 // 0..2 - какой отрезок
    private var recording = false
    private var startMs = 0L
    private var startSteps = 0
    private val windows = ArrayList<Triple<String, Long, Long>>()

    private lateinit var root: LinearLayout
    private lateinit var info: TextView
    private lateinit var action: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (18 * resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(Color.parseColor("#0d0d0d"))
        setContentView(root)

        val title = TextView(this)
        title.text = "Калибровка уклона"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        root.addView(title)

        info = TextView(this)
        info.textSize = 16f
        info.setTextColor(Color.parseColor("#dddddd"))
        info.setPadding(0, pad, 0, pad)
        root.addView(info)

        action = Button(this)
        root.addView(action)
        action.setOnClickListener { onAction() }

        val close = Button(this)
        close.text = "Закрыть"
        close.setOnClickListener { finish() }
        root.addView(close)

        render()
    }

    private fun render() {
        if (phase >= labels.size) { showResult(); return }
        val t = titles[phase]
        if (!recording) {
            info.text = "Отрезок ${phase + 1} из 3: «$t».\n\n" +
                "Найди склон и проходи все три отрезка В ОДНОМ МЕСТЕ — тогда " +
                "условия совпадут и останется чистая разница уклона.\n\n" +
                "Телефон в карман: в руке амплитуда сглажена и уклон не читается.\n" +
                "Нужно минимум $MIN_STEPS шагов.\n\n" +
                "Нажми «Начать», убери телефон и иди."
            action.text = "Начать: $t"
        } else {
            val done = StepsState.steps.value - startSteps
            info.text = "Идёт запись: «$t».\n\nШагов пройдено: $done" +
                (if (done < MIN_STEPS) "  (нужно ещё ${MIN_STEPS - done})" else "  — хватит") +
                "\n\nДойди до конца отрезка и нажми «Готово»."
            action.text = "Готово: $t"
        }
    }

    private fun onAction() {
        if (phase >= labels.size) { finish(); return }
        if (!recording) {
            if (!StepsState.serviceRunning.value) {
                info.text = "Сначала запусти счёт шагов на главном экране."
                return
            }
            recording = true
            startMs = System.currentTimeMillis()
            startSteps = StepsState.steps.value
            render()
            tick()
        } else {
            val done = StepsState.steps.value - startSteps
            if (done < MIN_STEPS) {
                info.text = "Пока только $done шагов, нужно $MIN_STEPS.\n" +
                    "Короткий отрезок даст шумную медиану — пройди ещё."
                return
            }
            windows.add(Triple(labels[phase], startMs, System.currentTimeMillis()))
            recording = false
            phase++
            render()
        }
    }

    /** Живой счётчик шагов, пока идёт запись. */
    private fun tick() {
        if (!recording) return
        action.postDelayed({
            if (recording) { render(); tick() }
        }, 1000L)
    }

    private fun showResult() {
        action.text = "Готово"
        info.text = "Считаю…"
        lifecycleScope.launch {
            val dao = AppDb.get(this@SlopeCalActivity).dao()
            val res = LinkedHashMap<String, Float?>()
            val counts = LinkedHashMap<String, Int>()
            for ((lbl, from, to) in windows) {
                // Только чиповые строки: это карман, там уклон и читается.
                val amps = dao.samplesBetween(from, to)
                    .filter { it.sampleSource == 1 }
                    .mapNotNull { it.accP90 ?: it.accRms }
                    .sorted()
                counts[lbl] = amps.size
                res[lbl] = if (amps.isEmpty()) null else amps[amps.size / 2]
            }
            val up = res["UP"]; val flat = res["FLAT"]; val down = res["DOWN"]
            val sb = StringBuilder("Измерено на одном склоне:\n\n")
            for ((k, v) in res) {
                val ru = when (k) { "UP" -> "в гору"; "FLAT" -> "ровно"; else -> "с горы" }
                sb.append("  ").append(ru).append(": ")
                    .append(if (v == null) "нет данных"
                            else String.format(java.util.Locale.US, "%.2f", v))
                    .append("  (строк ").append(counts[k] ?: 0).append(")\n")
            }
            if (up == null || flat == null || down == null) {
                sb.append("\nНе на всех отрезках собрались признаки. Обычно это ")
                sb.append("значит, что телефон был в руке или отрезок вышел коротким. ")
                sb.append("Попробуй ещё раз, положив телефон в карман.")
                info.text = sb.toString()
                return@launch
            }
            sb.append("\nОжидаемый порядок: в гору < ровно < с горы\n")
            val ok = up < flat && flat < down
            if (ok) {
                sb.append("ПОРЯДОК СОШЁЛСЯ ✓\n")
                sb.append("Зазор в гору-ровно: ")
                    .append(String.format(java.util.Locale.US, "%.2f", flat - up)).append("\n")
                sb.append("Зазор ровно-с горы: ")
                    .append(String.format(java.util.Locale.US, "%.2f", down - flat)).append("\n\n")
                val minGap = minOf(flat - up, down - flat)
                sb.append(if (minGap >= 0.5f)
                    "Классы разошлись уверенно — по таким якорям можно будет учить агента различать и «ровно»."
                else
                    "Классы разошлись, но тесно. Стоит повторить на более крутом склоне.")
                getSharedPreferences(StepService.PREFS, MODE_PRIVATE).edit()
                    .putFloat(KEY_UP, up).putFloat(KEY_FLAT, flat).putFloat(KEY_DOWN, down)
                    .putLong(KEY_TIME, System.currentTimeMillis()).apply()
                sb.append("\n\nЯкоря сохранены.")
            } else {
                sb.append("ПОРЯДОК НЕ СОШЁЛСЯ ✗\n")
                sb.append("Так бывает, если отрезки прошли в разных местах, ")
                sb.append("телефон перекладывали, или уклон слишком слабый. ")
                sb.append("Якоря НЕ сохраняю: неверная опора хуже её отсутствия.")
            }
            info.text = sb.toString()
        }
    }
}
