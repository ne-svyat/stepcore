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
 * Калибровка уклона - ЭКРАН. Вся логика в StepService: телефон уходит в
 * карман, экран гаснет, и HyperOS морозит активность. Служба не мёрзнет.
 *
 * Экран только показывает состояние и шлёт команды. Обратная связь на ходу -
 * ЗВУКОМ из службы: вибрацию в кармане не слышно.
 */
class SlopeCalActivity : AppCompatActivity() {

    private lateinit var card: TextView
    private lateinit var hint: TextView
    private lateinit var btnMain: TextView
    private lateinit var btnSkip: TextView
    private var dens = 1f

    private val titles = listOf("В ГОРУ", "РОВНО", "С ГОРЫ")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dens = resources.displayMetrics.density
        val root = LinearLayout(this)
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
        card.gravity = Gravity.CENTER
        card.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        val cp = (20 * dens).toInt()
        card.setPadding(cp, cp, cp, cp)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (16 * dens).toInt()
        root.addView(card, lp)

        hint = TextView(this)
        hint.textSize = 14f
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        hint.setLineSpacing(3f * dens, 1f)
        hint.setPadding(0, (14 * dens).toInt(), 0, 0)
        root.addView(hint)

        btnMain = mkButton(root, R.color.accent_teal)
        btnSkip = mkButton(root, R.color.accent_amber)

        val cancel = TextView(this)
        cancel.text = "Отменить и закрыть"
        cancel.gravity = Gravity.CENTER
        cancel.textSize = 15f
        cancel.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        cancel.setPadding(0, (16 * dens).toInt(), 0, (12 * dens).toInt())
        cancel.setOnClickListener { send(StepService.ACTION_SLOPE_CANCEL); finish() }
        root.addView(cancel)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { StepsState.slopePhase.collect { render() } }
                launch { StepsState.slopeStage.collect { render() } }
                launch { StepsState.slopeSteps.collect { render() } }
                launch { StepsState.slopeResult.collect { render() } }
            }
        }
    }

    private fun mkButton(root: LinearLayout, colorRes: Int): TextView {
        val b = TextView(this)
        b.gravity = Gravity.CENTER
        b.textSize = 17f
        b.setTextColor(ContextCompat.getColor(this, colorRes))
        b.setPadding(0, (14 * dens).toInt(), 0, (14 * dens).toInt())
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (14 * dens).toInt()
        root.addView(b, lp)
        return b
    }

    private fun send(action: String) {
        startForegroundService(Intent(this, StepService::class.java).setAction(action))
    }

    private fun frame(v: TextView, colorRes: Int, rift: Int) {
        v.background = DoodleBorderDrawable(
            ContextCompat.getColor(this, colorRes),
            ContextCompat.getColor(this, R.color.surface),
            811L, dens, DoodleBorderDrawable.MAT_ROCK, rift)
    }

    private fun render() {
        val phase = StepsState.slopePhase.value
        val stage = StepsState.slopeStage.value
        val steps = StepsState.slopeSteps.value
        btnSkip.visibility = View.GONE

        if (!StepsState.serviceRunning.value) {
            card.text = "Сначала запусти счёт шагов\nна главном экране"
            frame(card, R.color.accent_amber, DoodleBorderDrawable.RIFT_NONE)
            hint.text = "Калибровка считает шаги чипом — без работающего счёта измерять нечего."
            btnMain.text = "Закрыть"
            btnMain.setOnClickListener { finish() }
            return
        }

        if (phase < 0) {
            card.text = "Три отрезка на одном склоне"
            frame(card, R.color.accent_teal, DoodleBorderDrawable.RIFT_NONE)
            hint.text = "Метки уклона копятся в разных условиях, поэтому «ровно» " +
                "размазано и накрывает «в гору». Три отрезка подряд в одном месте " +
                "убирают разницу условий — остаётся чистая разница уклона.\n\n" +
                "Телефон в карман: в руке амплитуда сглажена, уклон не читается.\n" +
                "Сигналы — ЗВУКОМ: один сигнал «пошёл», два «хватит, подтверди».\n" +
                "Ровный участок можно пропустить, если на склоне его нет."
            btnMain.text = "Начать"
            btnMain.setOnClickListener { send(StepService.ACTION_SLOPE_START) }
            return
        }

        if (phase >= 3 || stage == "RESULT" || stage == "CALC") {
            card.text = if (stage == "CALC") "Считаю…"
                else StepsState.slopeResult.value.ifEmpty { "Готово" }
            frame(card, R.color.accent_violet, DoodleBorderDrawable.RIFT_NONE)
            hint.text = if (stage == "CALC") "" else
                "Якоря измерены на одном склоне, поэтому условия совпадают. " +
                "Повтори в другой день — если зазор устойчив, научим агента " +
                "различать и «ровно»."
            btnMain.text = "Закрыть"
            btnMain.setOnClickListener { finish() }
            return
        }

        val t = titles[phase]
        val rift = when (phase) {
            0 -> DoodleBorderDrawable.RIFT_UP
            1 -> DoodleBorderDrawable.RIFT_FLAT
            else -> DoodleBorderDrawable.RIFT_DOWN
        }
        val color = when (phase) {
            0 -> R.color.accent_amber
            1 -> R.color.accent_teal
            else -> R.color.accent_blue
        }
        frame(card, color, rift)

        when (stage) {
            "ARM" -> {
                card.text = "Отрезок ${phase + 1} из 3\n\n$t\n\nПоложи в карман и иди"
                hint.text = "Запись начнётся сама — услышишь один сигнал. " +
                    "Когда наберётся ${StepService.SLOPE_MIN_STEPS} шагов, " +
                    "прозвучат два: доставай телефон и подтверждай.\n\n" +
                    "Экран может гаснуть — счёт идёт в службе."
                btnMain.text = "Ждём начала движения…"
                btnMain.setOnClickListener { }
                if (phase == 1) {
                    btnSkip.visibility = View.VISIBLE
                    btnSkip.text = "Пропустить «ровно» (нет ровного участка)"
                    btnSkip.setOnClickListener { send(StepService.ACTION_SLOPE_SKIP) }
                }
            }
            "REC" -> {
                val need = StepService.SLOPE_MIN_STEPS - steps
                card.text = "Идёт запись\n\n$t\n\n$steps шагов" +
                    (if (need > 0) "\nнужно ещё $need" else "\nхватит")
                hint.text = "Иди спокойно. Два сигнала прозвучат сами, когда наберётся " +
                    "нужное число шагов. Телефон доставать не нужно."
                btnMain.text = "Идёт запись…"
                btnMain.setOnClickListener { }
            }
            "DONE" -> {
                card.text = "Отрезок записан\n\n$t\n\n$steps шагов"
                hint.text = "Замер закрыт в момент сигнала — обратная дорога в него " +
                    "не попадёт. Подтверди, и перейдём к следующему отрезку."
                btnMain.text = "Подтвердить и дальше"
                btnMain.setOnClickListener { send(StepService.ACTION_SLOPE_CONFIRM) }
            }
        }
    }
}
