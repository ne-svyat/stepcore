package com.vasil.stepcore

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Калибровка уклона: три САМОСТОЯТЕЛЬНЫХ замера.
 *
 * Раньше требовалась очередь в гору -> ровно -> с горы за один заход, и это
 * не сходилось с рельефом: ровного участка на склоне может не быть, спуск
 * бывает в другом месте, а прерваться нельзя. Теперь каждый класс пишется
 * отдельно, в любом порядке и в любой день; пара в гору + с горы уже даёт
 * опору, "ровно" - по возможности.
 *
 * Логика в службе: телефон уходит в карман, экран гаснет, а активность
 * HyperOS замораживает.
 */
class SlopeCalActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var dens = 1f
    private val order = listOf("UP", "FLAT", "DOWN")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dens = resources.displayMetrics.density
        val scroll = android.widget.ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (18 * dens).toInt()
        root.setPadding(pad, pad, pad, pad)
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        scroll.addView(root)
        setContentView(scroll)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { StepsState.slopeTarget.collect { render() } }
                launch { StepsState.slopeStage.collect { render() } }
                launch { StepsState.slopeSteps.collect { render() } }
                launch { StepsState.slopeResult.collect { render() } }
            }
        }
    }

    private fun ru(t: String) = when (t) {
        "UP" -> "в гору"; "DOWN" -> "с горы"; else -> "ровно"
    }

    private fun colorOf(t: String) = when (t) {
        "UP" -> R.color.accent_amber
        "DOWN" -> R.color.accent_blue
        else -> R.color.accent_green
    }

    private fun riftOf(t: String) = when (t) {
        "UP" -> DoodleBorderDrawable.RIFT_UP
        "DOWN" -> DoodleBorderDrawable.RIFT_DOWN
        else -> DoodleBorderDrawable.RIFT_FLAT
    }

    /** Чтение якоря защищено: если ключ когда-то сохранили другим типом,
     *  getFloat кидает ClassCastException и уносит весь экран. */
    private fun anchorOf(t: String): Pair<Float, Long>? = runCatching {
        val p = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val v = p.getFloat("slope_anchor_" + t.lowercase(), 0f)
        if (v <= 0f) return@runCatching null
        v to runCatching {
            p.getLong("slope_anchor_" + t.lowercase() + "_ms", 0L)
        }.getOrDefault(0L)
    }.getOrNull()

    private fun send(action: String, target: String? = null) {
        val i = Intent(this, StepService::class.java).setAction(action)
        if (target != null) i.putExtra(StepService.EXTRA_SLOPE_TARGET, target)
        startForegroundService(i)
    }

    /** Экран не имеет права падать: показать ошибку честнее, чем
     *  вылететь. Текст пригодится, чтобы понять причину. */
    private fun render() {
        try { renderInner() } catch (t: Throwable) {
            root.removeAllViews()
            val at = t.stackTrace.firstOrNull { it.className.contains("stepcore") }
            note("Сбой экрана: " + t.javaClass.simpleName + " " +
                (t.message ?: "") +
                (if (at == null) "" else "\n" + at.fileName + ":" + at.lineNumber))
            close()
        }
    }

    private fun renderInner() {
        root.removeAllViews()
        val title = TextView(this)
        title.text = "Калибровка уклона"
        title.textSize = 22f
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        root.addView(title)

        if (!StepsState.serviceRunning.value) {
            note("Сначала запусти счёт шагов на главном экране — калибровка " +
                "считает шаги чипом.")
            close()
            return
        }

        val target = StepsState.slopeTarget.value
        if (target != "") { renderRecording(target); return }

        note("Каждый отрезок записывается отдельно. Порядок любой, между " +
            "замерами хоть неделя. Нужны хотя бы «в гору» и «с горы» — " +
            "ровный участок на склоне бывает не всегда.\n\n" +
            "Телефон в карман: в руке амплитуда сглажена и уклон не читается. " +
            "На ходу всё слышно: один сигнал — пошёл, два — хватит, подтверди.")

        for (t in order) card(t)
        verdict()

        val res = StepsState.slopeResult.value
        if (res != "") note(res)
        close()
    }

    private fun renderRecording(t: String) {
        val stage = StepsState.slopeStage.value
        val steps = StepsState.slopeSteps.value
        val card = TextView(this)
        card.gravity = Gravity.CENTER
        card.textSize = 17f
        card.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        val cp = (20 * dens).toInt()
        card.setPadding(cp, cp, cp, cp)
        card.background = DoodleBorderDrawable(
            ContextCompat.getColor(this, colorOf(t)),
            ContextCompat.getColor(this, R.color.surface),
            811L, dens, DoodleBorderDrawable.MAT_ROCK, riftOf(t))
        card.text = when (stage) {
            "ARM" -> "Записываю «" + ru(t) + "»\n\nПоложи в карман и иди"
            "REC" -> "Записываю «" + ru(t) + "»\n\n" + steps + " шагов" +
                (if (steps < StepService.SLOPE_MIN_STEPS)
                    "\nнужно ещё " + (StepService.SLOPE_MIN_STEPS - steps) else "\nхватит")
            "DONE" -> "Отрезок «" + ru(t) + "» записан\n\n" + steps + " шагов"
            else -> "Считаю…"
        }
        add(card)

        note(when (stage) {
            "ARM" -> "Запись начнётся сама — услышишь один сигнал."
            "REC" -> "Два сигнала прозвучат сами, когда наберётся " +
                StepService.SLOPE_MIN_STEPS + " шагов. Телефон доставать не нужно."
            "DONE" -> "Замер закрыт в момент сигнала — обратная дорога в него " +
                "не попадёт."
            else -> ""
        })

        if (stage == "DONE") {
            button("✓ Подтвердить «" + ru(t) + "»", colorOf(t)) {
                send(StepService.ACTION_SLOPE_CONFIRM)
            }
        }
        button("Отменить замер", R.color.text_dim) {
            send(StepService.ACTION_SLOPE_CANCEL)
        }
        close()
    }

    private fun card(t: String) {
        val a = anchorOf(t)
        val v = TextView(this)
        v.textSize = 16f
        v.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        val cp = (16 * dens).toInt()
        v.setPadding(cp, cp, cp, cp)
        v.background = DoodleBorderDrawable(
            ContextCompat.getColor(this, colorOf(t)),
            ContextCompat.getColor(this, R.color.surface),
            700L + t.length, dens, DoodleBorderDrawable.MAT_ROCK, riftOf(t))
        v.text = if (a == null)
            ru(t).replaceFirstChar { it.uppercase() } + "\nне записано — нажми, чтобы записать"
        else {
            val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru"))
            ru(t).replaceFirstChar { it.uppercase() } + "\nамплитуда " +
                String.format(java.util.Locale.US, "%.2f", a.first) +
                "   ·   " + fmt.format(java.util.Date(a.second)) +
                "\nнажми, чтобы перезаписать"
        }
        v.setOnClickListener { send(StepService.ACTION_SLOPE_PICK, t) }
        add(v)
    }

    /** Вердикт по тому, что уже собрано. Пара в гору + с горы - минимум. */
    private fun verdict() {
        val up = anchorOf("UP")?.first
        val down = anchorOf("DOWN")?.first
        val flat = anchorOf("FLAT")?.first
        if (up == null || down == null) {
            note("Пока нет пары «в гору» + «с горы» — по ней и строится опора. " +
                "Запиши оба, можно в разные дни.")
            return
        }
        if (up >= down) {
            note("Порядок не сошёлся: «в гору» должно быть МЯГЧЕ, чем «с горы», " +
                "а вышло наоборот. Скорее всего отрезки прошли в разных местах " +
                "или телефон лежал по-разному. Перезапиши один из них.")
            return
        }
        val sb = StringBuilder("Порядок сошёлся ✓  зазор " +
            String.format(java.util.Locale.US, "%.2f", down - up))
        if (flat != null) {
            sb.append(if (flat > up && flat < down)
                "\n«Ровно» встало между ними — три класса разделены."
            else
                "\n«Ровно» выпало из порядка: в разборе оно учитываться не будет.")
        } else {
            sb.append("\n«Ровно» не записано — разбор будет считать его серединой.")
        }
        sb.append("\n\nЭти числа уже работают в разборе отрезков.")
        note(sb.toString())
    }

    private fun add(v: View) {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (14 * dens).toInt()
        root.addView(v, lp)
    }

    private fun note(t: String) {
        if (t == "") return
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        v.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        v.setLineSpacing(3f * dens, 1f)
        v.setPadding(0, (14 * dens).toInt(), 0, 0)
        root.addView(v)
    }

    private fun button(label: String, colorRes: Int, onClick: () -> Unit) {
        val v = TextView(this)
        v.text = label
        v.gravity = Gravity.CENTER
        v.textSize = 17f
        v.setTextColor(ContextCompat.getColor(this, colorRes))
        v.setPadding(0, (16 * dens).toInt(), 0, (14 * dens).toInt())
        v.setOnClickListener { onClick() }
        root.addView(v)
    }

    private fun close() {
        val v = TextView(this)
        v.text = "Закрыть"
        v.gravity = Gravity.CENTER
        v.textSize = 16f
        v.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        v.setPadding(0, (20 * dens).toInt(), 0, (12 * dens).toInt())
        v.setOnClickListener { finish() }
        root.addView(v)
    }
}
