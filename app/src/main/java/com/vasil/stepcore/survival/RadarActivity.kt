package com.vasil.stepcore.survival

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vasil.stepcore.DoodleUi
import com.vasil.stepcore.R
import com.vasil.stepcore.survival.engine.Compass
import com.vasil.stepcore.survival.engine.RadarModel
import com.vasil.stepcore.survival.engine.SurvivalEngine
import kotlinx.coroutines.launch

/**
 * Экран «Окрестности».
 *
 * Ничего не считает сам: берёт снимок знания у репозитория и показывает.
 * Работает и для архивной экспедиции — там это знание на её последний день,
 * то есть буквально «что человек знал, когда всё закончилось».
 */
class RadarActivity : AppCompatActivity() {

    private lateinit var repo: SurvivalRepo
    private lateinit var radar: RadarView
    private lateinit var titleText: TextView
    private lateinit var factsText: TextView
    private lateinit var legendBox: LinearLayout

    /** Готовый отчёт текущего экрана. Считается один раз при отрисовке. */
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radar)
        repo = SurvivalRepo(this)

        radar = findViewById(R.id.radarView)
        titleText = findViewById(R.id.radarTitle)
        factsText = findViewById(R.id.radarFacts)
        legendBox = findViewById(R.id.radarLegend)
        findViewById<Button>(R.id.radarBackBtn).setOnClickListener { finish() }

        // Те же три носителя смысла, что и на экране экспедиции: отчёт —
        // фиолетовый (вынести наружу), возврат — тусклый.
        DoodleUi.chip(findViewById(R.id.radarCopyBtn),
            com.vasil.stepcore.DoodleIconDrawable.COPY,
            R.color.accent_violet, R.color.surface_violet,
            R.color.accent_violet_bright, 601L)
        DoodleUi.chip(findViewById(R.id.radarShareBtn),
            com.vasil.stepcore.DoodleIconDrawable.SHARE,
            R.color.accent_violet, R.color.surface_violet,
            R.color.accent_violet_bright, 602L)
        DoodleUi.chip(findViewById(R.id.radarBackBtn),
            com.vasil.stepcore.DoodleIconDrawable.BACK,
            R.color.axis_dim, R.color.surface, R.color.text_dim, 603L)

        findViewById<Button>(R.id.radarCopyBtn).setOnClickListener {
            if (report.isEmpty()) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Радар", report))
            android.widget.Toast.makeText(this, "Отчёт скопирован",
                android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.radarShareBtn).setOnClickListener {
            if (report.isEmpty()) return@setOnClickListener
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Радар окрестностей")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(send, "Поделиться отчётом"))
        }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        lifecycleScope.launch {
            val e = repo.byId(id)
            if (e == null) { finish(); return@launch }
            val day = repo.days(e).lastOrNull()
            val header = if (day == null) "" else SurvivalEngine.headerOf(day)
            render(e, repo.recon(e), header)

            // v122. По активной экспедиции радар — руль: тап задаёт курс. По
            // архивной руля нет — это уже история, её не переиграть.
            if (e.status == "active" && e.engineVersion >= 6) {
                radar.currentCourse = e.courseHeading
                radar.onCourse = { sector, mark ->
                    lifecycleScope.launch {
                        repo.setCourse(e.id, sector)
                        radar.currentCourse = sector
                        val msg = when {
                            sector < 0 -> "Курс: сам выбираю (автопилот)"
                            mark != null -> "Иду к: " + RadarModel.kindRu(mark.kind) +
                                " (" + Compass.RU[sector] + ")"
                            else -> "Курс: " + Compass.RU[sector]
                        }
                        android.widget.Toast.makeText(
                            this@RadarActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                legendBox.addView(card(
                    "Ткни в сторону — пойдёшь туда. Ткни в зверя — пойдёшь к нему. " +
                    "Ткни в лагерь — вернёшь автопилот. Новый курс — со следующего дня.",
                    R.color.text_main, R.color.accent_violet), 0)
            }
        }
    }

    private fun render(e: Expedition, r: RadarModel.Recon, header: String) {
        titleText.text = "Окрестности · экспедиция №" + e.id
        radar.setRecon(r)
        // Отчёт строится из того же снимка знания, что и картинка: текст и
        // экран не могут разойтись даже теоретически.
        report = RadarModel.report(e.id, header, r)

        val sb = StringBuilder()
        sb.append("День ").append(r.day)
        if (r.hasWorld) {
            if (r.calm) {
                sb.append(" · штиль: запах стоит вокруг лагеря, ")
                    .append(RadarModel.kmRu(r.scentKm))
            } else {
                sb.append(" · ветер ").append(Compass.RU[r.windDir])
                    .append(", запах несёт на ").append(Compass.RU[r.downwind])
                    .append(" — ").append(RadarModel.kmRu(r.scentKm))
            }
        }
        if (r.hasWorld) {
            sb.append("\nВидно ").append(RadarModel.kmRu(r.sightKm))
                .append(" · слышно ").append(RadarModel.kmRu(r.hearKm))
            if (!r.calm) sb.append(", против ветра дальше")
        }
        factsText.text = sb.toString()

        legendBox.removeAllViews()

        if (e.engineVersion < 2) {
            legendBox.addView(card(
                "Эта экспедиция живёт по правилам первой версии мира — зверей в нём ещё не было. Радар пуст не потому, что тайга пуста.",
                R.color.text_dim, R.color.accent_violet))
            return
        }
        if (!r.hasWorld) {
            legendBox.addView(card("Мир ещё не начался: пройди первую норму шагов.",
                R.color.text_dim, R.color.accent_violet))
            return
        }

        for (m in r.marks) {
            val head = StringBuilder()
            head.append(RadarModel.kindRu(m.kind))
            if (m.kind == com.vasil.stepcore.survival.engine.FaunaModel.WOLF && m.packSize > 1) {
                head.append(", ").append(m.packSize).append(" — ")
            } else {
                head.append(" — ")
            }
            head.append(Compass.RU[m.sector]).append(", ")
                .append(RadarModel.kmRu(m.distKm))
            head.append("\n").append(RadarModel.ageRu(m.ageDays))
                .append(", ").append(RadarModel.sourceRu(m.source))
            when {
                m.attention -> head.append(" · ЧУЕТ ЛАГЕРЬ")
                m.stale -> head.append(" · сведения устарели")
            }
            val col = when {
                m.attention -> R.color.accent_red_bright
                m.source == com.vasil.stepcore.survival.engine.Obs.TRACK ->
                    R.color.accent_teal_bright
                else -> R.color.accent_violet_bright
            }
            val frame = if (m.attention) R.color.accent_red else R.color.accent_violet
            legendBox.addView(card(head.toString(), col, frame, m.freshness.toFloat()))
        }

        if (r.marks.isEmpty()) {
            legendBox.addView(card(
                "Пока ничего. Следов нет, голосов не слышно. Это не значит, что рядом никого нет.",
                R.color.text_dim, R.color.accent_violet))
        }

        // Забыть — не то же самое, что не знать. Тусклая строка вместо метки:
        // рисовать метку было бы враньём, молчать — тоже.
        for (f in r.faded) {
            legendBox.addView(dim(
                RadarModel.kindRu(f.kind) + " — сведения устарели: " +
                RadarModel.ageRu(f.ageDays) + ", " + Compass.RU[f.sector] +
                ", " + RadarModel.sourceRu(f.source)))
        }

        val unknown = r.unknown.joinToString(" · ") { RadarModel.kindRu(it) }
        if (unknown.isNotEmpty()) {
            legendBox.addView(dim("Ни разу не встречены: " + unknown))
        }
        legendBox.addView(dim(
            "Ярче — свежее. Шире ореол — меньше уверенности: зверь ушёл, а сведения остались. " +
            "Красным — то, что чует лагерь сегодня.\n\n" +
            "Белое кольцо — докуда сегодня видно. Синий лепесток — докуда слышно: " +
            "против ветра дальше, по ветру почти никак. За этими границами зверь есть — " +
            "просто ты о нём не узнаешь."))
    }

    private fun card(
        text: String, textColor: Int, frameColor: Int, fade: Float = 1f,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@RadarActivity, textColor))
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        gravity = Gravity.START
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        layoutParams = lp
        DoodleUi.frame(this, frameColor, R.color.surface, 1200L + text.length * 7L)
        // Карточка тускнеет вместе с меткой: одно и то же знание — одна яркость.
        this.alpha = 0.45f + 0.55f * fade
    }

    private fun dim(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@RadarActivity, R.color.text_dim))
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(4), dp(8), dp(4), 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        com.vasil.stepcore.BoilClock.screenStarted()
    }

    override fun onStop() {
        com.vasil.stepcore.BoilClock.screenStopped()
        super.onStop()
    }

    companion object {
        const val EXTRA_ID = "expedition_id"
    }
}
