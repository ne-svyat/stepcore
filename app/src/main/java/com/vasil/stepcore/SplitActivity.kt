package com.vasil.stepcore

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Разбор сессии по шагам: показывает амплитуду каждого образца, найденную
 * точку разлома и объяснение словами; даёт разрезать сессию на две с разными
 * метками. Высота дня пересчитается сама (карта берёт метки сессий).
 */
class SplitActivity : AppCompatActivity() {

    private var sessionStart = 0L
    private var sessionEnd = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sessionStart = intent.getLongExtra("startMs", 0L)
        sessionEnd = intent.getLongExtra("endMs", 0L)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(Color.parseColor("#0d0d0d"))
        setContentView(root)

        val title = TextView(this)
        title.text = "Разбор по шагам"
        title.textSize = 20f
        title.setTextColor(Color.WHITE)
        root.addView(title)

        lifecycleScope.launch { build(root) }
    }

    private suspend fun build(root: LinearLayout) {
        val dao = AppDb.get(this).dao()
        val samples = dao.samplesBetween(sessionStart, sessionEnd)
            .sortedBy { it.timeMs }
        // амплитуда: у чиповых строк из accP90, у детекторных из amp
        val amps = samples.map {
            if (it.sampleSource == 1) (it.accP90 ?: it.accRms ?: 0f) else it.amp
        }
        if (amps.size < 6) {
            addText(root, "Мало образцов для разбора (нужно ≥6, есть ${amps.size}).")
            return
        }
        val split = SplitFinder.find(amps)
        addView(root, ChartView(this, amps, split?.index))

        if (split == null) {
            addText(root, "Сессия выглядит однородной — амплитуда не прыгает. " +
                "Резать не нужно: это один участок.")
            return
        }

        val (lLabel, lPhrase) = SplitFinder.explain(
            split.leftMean, split.leftMean - split.rightMean)
        val (rLabel, rPhrase) = SplitFinder.explain(
            split.rightMean, split.rightMean - split.leftMean)
        addText(root, "Первые ${split.index} шагов — $lPhrase.")
        addText(root, "Дальше ${amps.size - split.index} шагов — $rPhrase.")
        addText(root, "\nЕсли похоже на правду — можно разрезать здесь. Первой " +
            "части дам метку «${labelRu(lLabel)}», второй «${labelRu(rLabel)}».")

        val cut = Button(this)
        cut.text = "✂ Разрезать здесь"
        cut.setOnClickListener {
            lifecycleScope.launch {
                doSplit(samples, split.index, lLabel, rLabel)
                Toast.makeText(this@SplitActivity,
                    "Разрезано: ${labelRu(lLabel)} + ${labelRu(rLabel)}",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(cut)
    }

    /** Заменяет одну сессию на две по точке разлома. Каждая половина - своя
     *  сессия со своей меткой (в userLabel, исходные метки образцов не трогаем
     *  - прошлое неизменно). */
    private suspend fun doSplit(
        samples: List<TerrainSample>, at: Int, lLabel: String, rLabel: String
    ) {
        val dao = AppDb.get(this).dao()
        val left = samples.subList(0, at)
        val right = samples.subList(at, samples.size)
        // найти исходную сессию, чтобы удалить и не задвоить
        val existing = dao.sessionsAround(sessionStart - 1000, sessionStart + 1000)
            .firstOrNull { it.startMs == sessionStart }
        if (existing != null) dao.deleteSessionById(existing.id)
        insertHalf(dao, left, lLabel)
        insertHalf(dao, right, rLabel)
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
            confirmState = 1,          // разрез = осознанный ответ человека
            userLabel = label,
            builtFromMaxTimeMs = part.last().timeMs
        ))
    }

    private fun labelRu(l: String) = when (l) {
        "UP" -> "в гору"; "DOWN" -> "с горы"; "FLAT" -> "ровно"; else -> l
    }

    private fun addText(root: LinearLayout, t: String) {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 15f
        tv.setTextColor(Color.parseColor("#dddddd"))
        val m = (8 * resources.displayMetrics.density).toInt()
        (tv.layoutParams ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)).also {
            tv.layoutParams = it
        }
        tv.setPadding(0, m, 0, 0)
        root.addView(tv)
    }

    private fun addView(root: LinearLayout, v: View) {
        val h = (160 * resources.displayMetrics.density).toInt()
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h)
        val m = (12 * resources.displayMetrics.density).toInt()
        lp.topMargin = m
        root.addView(v, lp)
    }

    /** Мини-график амплитуды по шагам с вертикалью на точке разлома. */
    private class ChartView(
        ctx: android.content.Context,
        val amps: List<Float>,
        val splitAt: Int?
    ) : View(ctx) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4a86e8"); strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4a86e8"); style = Paint.Style.FILL
        }
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#ffad47"); strokeWidth = 3f
        }
        override fun onDraw(c: Canvas) {
            if (amps.isEmpty()) return
            val w = width.toFloat(); val h = height.toFloat()
            val pad = 20f
            val lo = amps.min(); val hi = amps.max()
            val span = (hi - lo).coerceAtLeast(0.1f)
            fun px(i: Int) = pad + (w - 2 * pad) * i / (amps.size - 1).coerceAtLeast(1)
            fun py(v: Float) = h - pad - (h - 2 * pad) * (v - lo) / span
            for (i in 0 until amps.size - 1) {
                c.drawLine(px(i), py(amps[i]), px(i + 1), py(amps[i + 1]), line)
            }
            for (i in amps.indices) c.drawCircle(px(i), py(amps[i]), 5f, dot)
            if (splitAt != null && splitAt in 1 until amps.size) {
                val x = (px(splitAt - 1) + px(splitAt)) / 2f
                c.drawLine(x, pad, x, h - pad, cut)
            }
        }
    }
}
