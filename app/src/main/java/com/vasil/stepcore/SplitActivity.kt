package com.vasil.stepcore

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Разбор сессии по шагам.
 *
 * Задача экрана - не предложить разрез, а дать ПОНЯТЬ. Поэтому здесь:
 * лента шагов в цветах классов, личные пороги словами и цифрами, и несколько
 * вариантов разлома на выбор. Решение принимает человек, зная основания.
 */
class SplitActivity : AppCompatActivity() {

    private var sessionStart = 0L
    private var sessionEnd = 0L
    private var dens = 1f
    private lateinit var root: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dens = resources.displayMetrics.density
        sessionStart = intent.getLongExtra("startMs", 0L)
        sessionEnd = intent.getLongExtra("endMs", 0L)

        val scroll = android.widget.ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (16 * dens).toInt()
        root.setPadding(pad, pad, pad, pad)
        scroll.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "Разбор по шагам"
        title.textSize = 22f
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        root.addView(title)

        lifecycleScope.launch { build() }
    }

    private fun colorOf(cls: String) = when (cls) {
        "UP" -> ContextCompat.getColor(this, R.color.accent_amber)
        "DOWN" -> ContextCompat.getColor(this, R.color.accent_blue)
        else -> ContextCompat.getColor(this, R.color.accent_teal)
    }

    private fun ru(cls: String) = when (cls) {
        "UP" -> "в гору"; "DOWN" -> "с горы"; else -> "ровно"
    }

    private suspend fun build() {
        val dao = AppDb.get(this).dao()
        val samples = dao.samplesBetween(sessionStart, sessionEnd).sortedBy { it.timeMs }
        val amps = samples.map {
            if (it.sampleSource == 1) (it.accP90 ?: it.accRms ?: 0f) else it.amp
        }
        if (amps.size < 6) {
            text("Мало образцов для разбора: " + amps.size + " (нужно от 6).")
            closeButton(); return
        }
        val chipShare = samples.count { it.sampleSource == 1 }.toFloat() / samples.size
        if (chipShare < InclineAgent.POCKET_MIN) {
            text("Телефон был в основном в руке. Амплитуда там сглажена, уклон " +
                "по ней не читается — разрезать не берусь, это была бы догадка.")
            closeButton(); return
        }

        // Личные пороги: измеренные калибровкой уклона - лучше выведенных.
        val pr = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val aUp = pr.getFloat("slope_anchor_up", 0f)
        val aDown = pr.getFloat("slope_anchor_down", 0f)
        val aFlat = pr.getFloat("slope_anchor_flat", 0f)
        val anchors = if (aUp > 0f && aDown > aUp) {
            val flat = if (aFlat > aUp && aFlat < aDown) aFlat else (aUp + aDown) / 2f
            SplitFinder.Anchors(aUp, flat, aDown, true)
        } else SplitFinder.fallbackAnchors(amps)

        val classes = amps.map { SplitFinder.classify(it, anchors) }
        addBand(amps, classes, null)

        // Легенда: ответ на "как отличить шаги"
        legend(anchors)

        // Состав отрезка в шагах
        val cnt = classes.groupingBy { it }.eachCount()
        val parts = listOf("UP", "FLAT", "DOWN").filter { (cnt[it] ?: 0) > 0 }
            .joinToString(" · ") { ru(it) + " " + (cnt[it] ?: 0) }
        text("Всего образцов: " + amps.size + "   (" + parts + ")\n" +
            "Один образец пишется примерно раз в 20 шагов.")

        val cands = SplitFinder.candidates(amps)
        if (cands.isEmpty()) {
            text("Резких границ внутри нет — отрезок однородный. Резать нечего: " +
                "это один участок.")
            closeButton(); return
        }

        text(if (cands.size == 1) "Нашёл одну границу:" else
            "Нашёл " + cands.size + " возможные границы — выбери подходящую:")

        for ((i, sp) in cands.withIndex()) {
            addVariant(i + 1, sp, amps, classes, anchors, samples)
        }
        closeButton("Оставить как есть")
    }

    /** Лента шагов: каждый образец - блок в цвете своего класса. */
    private fun addBand(amps: List<Float>, classes: List<String>, cutAt: Int?) {
        val v = BandView(this, classes.map { colorOf(it) }, cutAt,
            ContextCompat.getColor(this, R.color.text_main))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (44 * dens).toInt())
        lp.topMargin = (14 * dens).toInt()
        root.addView(v, lp)
    }

    private fun legend(a: SplitFinder.Anchors) {
        val t = TextView(this)
        val src = if (a.measured) "измерено твоей калибровкой уклона"
            else "выведено из этой прогулки (калибровки уклона ещё не было)"
        t.text = "Как отличаются шаги — " + src + ":\n" +
            "  ▲ в гору — шаг мягче, амплитуда до " +
            String.format(java.util.Locale.US, "%.1f", a.upFlat) + "\n" +
            "  ━ ровно — между " +
            String.format(java.util.Locale.US, "%.1f", a.upFlat) + " и " +
            String.format(java.util.Locale.US, "%.1f", a.flatDown) + "\n" +
            "  ▼ с горы — приземление жёстче, от " +
            String.format(java.util.Locale.US, "%.1f", a.flatDown) +
            (if (a.measured) "" else
                "\n\nЭто оценка. Пройди калибровку уклона — пороги станут измеренными.")
        t.textSize = 14f
        t.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        t.setLineSpacing(3f * dens, 1f)
        t.setPadding(0, (12 * dens).toInt(), 0, 0)
        root.addView(t)
    }

    /** Карточка одного варианта разреза: что слева, что справа, чем режем. */
    private fun addVariant(
        num: Int, sp: SplitFinder.Split, amps: List<Float>,
        classes: List<String>, a: SplitFinder.Anchors, samples: List<TerrainSample>
    ) {
        val lCls = SplitFinder.classify(sp.leftMean, a)
        val rCls = SplitFinder.classify(sp.rightMean, a)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cp = (14 * dens).toInt()
        card.setPadding(cp, cp, cp, cp)
        card.background = DoodleBorderDrawable(
            ContextCompat.getColor(this, R.color.accent_violet),
            ContextCompat.getColor(this, R.color.surface),
            640L + num, dens, DoodleBorderDrawable.MAT_ROCK,
            DoodleBorderDrawable.RIFT_NONE)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (16 * dens).toInt()

        val head = TextView(this)
        head.text = "Вариант " + num
        head.textSize = 16f
        head.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        card.addView(head)

        // мини-лента с отметкой этого разреза
        val band = BandView(this, classes.map { colorOf(it) }, sp.index,
            ContextCompat.getColor(this, R.color.text_main))
        val blp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (28 * dens).toInt())
        blp.topMargin = (10 * dens).toInt()
        card.addView(band, blp)

        val body = TextView(this)
        body.text =
            "Слева " + sp.index + " образцов — «" + ru(lCls) + "», амплитуда " +
                String.format(java.util.Locale.US, "%.1f", sp.leftMean) + "\n" +
            "Справа " + (amps.size - sp.index) + " образцов — «" + ru(rCls) +
                "», амплитуда " +
                String.format(java.util.Locale.US, "%.1f", sp.rightMean) + "\n" +
            "Перепад на границе: " +
                String.format(java.util.Locale.US, "%.1f", sp.gap)
        body.textSize = 15f
        body.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        body.setLineSpacing(2f * dens, 1f)
        body.setPadding(0, (10 * dens).toInt(), 0, 0)
        card.addView(body)

        if (lCls == rCls) {
            val warn = TextView(this)
            warn.text = "Обе части попадают в один класс — резать смысла нет."
            warn.textSize = 14f
            warn.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            warn.setPadding(0, (8 * dens).toInt(), 0, 0)
            card.addView(warn)
        } else {
            val btn = TextView(this)
            btn.text = "✂ Разрезать: «" + ru(lCls) + "» + «" + ru(rCls) + "»"
            btn.gravity = Gravity.CENTER
            btn.textSize = 16f
            btn.setTextColor(ContextCompat.getColor(this, R.color.accent_teal))
            btn.setPadding(0, (14 * dens).toInt(), 0, (10 * dens).toInt())
            btn.setOnClickListener {
                lifecycleScope.launch {
                    doSplit(samples, sp.index, lCls, rCls)
                    Toast.makeText(this@SplitActivity,
                        "Разрезано: " + ru(lCls) + " + " + ru(rCls),
                        Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            card.addView(btn)
        }
        root.addView(card, lp)
    }

    private fun text(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 15f
        v.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        v.setLineSpacing(3f * dens, 1f)
        v.setPadding(0, (14 * dens).toInt(), 0, 0)
        root.addView(v)
    }

    private fun closeButton(label: String = "Понятно, закрыть") {
        val v = TextView(this)
        v.text = label
        v.gravity = Gravity.CENTER
        v.textSize = 16f
        v.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        v.setPadding(0, (22 * dens).toInt(), 0, (14 * dens).toInt())
        v.setOnClickListener { finish() }
        root.addView(v)
    }

    /** Заменяет сессию на две половины. Корпус не трогаем: прошлое неизменно,
     *  метка живёт на сессии. */
    private suspend fun doSplit(
        samples: List<TerrainSample>, at: Int, lLabel: String, rLabel: String
    ) {
        val dao = AppDb.get(this).dao()
        val existing = dao.sessionsAround(sessionStart - 1000, sessionStart + 1000)
            .firstOrNull { it.startMs == sessionStart }
        if (existing != null) dao.deleteSessionById(existing.id)
        insertHalf(dao, samples.subList(0, at), lLabel)
        insertHalf(dao, samples.subList(at, samples.size), rLabel)
    }

    private suspend fun insertHalf(
        dao: StepDao, part: List<TerrainSample>, label: String
    ) {
        if (part.isEmpty()) return
        val amps = part.map {
            if (it.sampleSource == 1) (it.accP90 ?: it.accRms ?: 0f) else it.amp
        }.sorted()
        val chip = part.count { it.sampleSource == 1 }.toFloat() / part.size
        dao.insertSession(SessionRecord(
            startMs = part.first().timeMs,
            endMs = part.last().timeMs,
            durationMs = part.last().timeMs - part.first().timeMs,
            label = label,
            nSamples = part.size,
            reliable = part.size >= SessionEngine.INCLINE_MIN_SAMPLES,
            walkShare = 1f, runShare = 0f,
            ampMed = amps[amps.size / 2],
            chipShare = chip,
            featureVersion = part.first().featureVersion,
            confirmState = 1,
            userLabel = label,
            builtFromMaxTimeMs = part.last().timeMs
        ))
    }

    /** Лента: по блоку на образец, цвет = класс шага. Структура отрезка
     *  видна сразу, без чтения цифр. */
    private class BandView(
        ctx: android.content.Context,
        val colors: List<Int>,
        val cutAt: Int?,
        val cutColor: Int
    ) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            if (colors.isEmpty()) return
            val w = width.toFloat(); val h = height.toFloat()
            val bw = w / colors.size
            for (i in colors.indices) {
                p.color = colors[i]
                c.drawRect(i * bw, 0f, (i + 1) * bw - 1f, h, p)
            }
            val cut = cutAt
            if (cut != null && cut in 1 until colors.size) {
                p.color = cutColor
                val x = cut * bw
                c.drawRect(x - 2f, 0f, x + 2f, h, p)
            }
        }
    }
}
