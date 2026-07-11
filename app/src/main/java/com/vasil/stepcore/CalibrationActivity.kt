package com.vasil.stepcore

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Экран Калибровки (V10). Собирает ВСЕ калибровки в одном месте с явным
 * статусом, свежестью и итоговой точностью - вместо разрозненных кнопок
 * в "Инструментах" (там осталась только диагностика).
 *
 * Длина шага измеряется одним из двух способов (GPS или метраж) - это
 * одна калибровка, а не две: обе дают одно и то же число.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var accuracyValue: TextView
    private lateinit var accuracyHint: TextView
    private lateinit var calStatus: TextView
    private lateinit var finishBtn: Button
    private lateinit var container: LinearLayout

    private var activeKind: CalibrationRegistry.Kind? = null
    private var gpsCal: LocationCalibrator? = null
    private var gpsStepsAtStart = 0

    private val gpsPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startGps()
            else toastState("Без доступа к GPS калибровка невозможна")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        // V14.3: дудл-стиль.
        findViewById<DoodleSceneView>(R.id.doodleHeader).setScene(DoodleSceneView.CALIBRATION)
        DoodleUi.frame(findViewById(R.id.calCard), R.color.accent_violet, R.color.surface, 401L)
        accuracyValue = findViewById(R.id.accuracyValue)
        accuracyHint = findViewById(R.id.accuracyHint)
        calStatus = findViewById(R.id.calStatus)
        finishBtn = findViewById(R.id.finishButton)
        container = findViewById(R.id.calContainer)

        finishBtn.setOnClickListener { finishActive() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StepsState.calibrationState.collect { s ->
                    if (s.isNotEmpty()) {
                        calStatus.text = s
                        calStatus.visibility = View.VISIBLE
                    }
                }
            }
        }
        render()
    }

    override fun onResume() { super.onResume(); render() }

    override fun onPause() {
        super.onPause()
        // GPS не должен остаться включённым при уходе с экрана.
        if (activeKind == CalibrationRegistry.Kind.STRIDE && gpsCal != null) {
            gpsCal?.stop(); gpsCal = null; activeKind = null
        }
    }

    private fun render() {
        val pct = CalibrationRegistry.overallPercent(this)
        accuracyValue.text = "$pct%"
        accuracyHint.text = when {
            pct >= 90 -> "Отлично. Дистанция и калории считаются по твоим измерениям."
            pct >= 70 -> "Хорошо. Часть параметров ещё оценочные — пройди недостающие калибровки."
            else -> "Пока используются табличные оценки по росту. Калибровка заметно повысит точность."
        }
        container.removeAllViews()
        CalibrationRegistry.Kind.values().forEach { container.addView(card(it)) }
    }

    /** Карточка одной калибровки: значение, свежесть, на что влияет. */
    private fun card(k: CalibrationRegistry.Kind): View {
        val fresh = (CalibrationRegistry.freshness(this, k) * 100).toInt()
        val done = CalibrationRegistry.isDone(this, k)
        val age = CalibrationRegistry.ageText(this, k)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@CalibrationActivity,
                if (done) R.drawable.card_period else R.drawable.card_column)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10); layoutParams = lp
            isClickable = true
            setOnClickListener { onCardTap(k) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = k.title.uppercase()
            setTextColor(ContextCompat.getColor(this@CalibrationActivity, R.color.text_main))
            textSize = 15f
            letterSpacing = 0.04f
        })
        col.addView(TextView(this).apply {
            text = CalibrationRegistry.valueText(this@CalibrationActivity, k)
            setTextColor(ContextCompat.getColor(this@CalibrationActivity,
                if (done) R.color.text_main else R.color.text_dim))
            textSize = 14f
            setPadding(0, dp(3), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "Влияет на: ${k.affects}"
            setTextColor(ContextCompat.getColor(this@CalibrationActivity, R.color.text_dim))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), dp(16), dp(14))
        }
        right.addView(TextView(this).apply {
            text = "$fresh%"
            setTextColor(ContextCompat.getColor(this@CalibrationActivity,
                if (done) R.color.accent_blue else R.color.text_dim))
            textSize = 20f
        })
        right.addView(TextView(this).apply {
            text = if (done) age else "не пройдена"
            setTextColor(ContextCompat.getColor(this@CalibrationActivity, R.color.text_dim))
            textSize = 11f
        })
        root.addView(col); root.addView(right)
        return root
    }

    private fun onCardTap(k: CalibrationRegistry.Kind) {
        if (activeKind != null) { toastState("Сначала заверши текущую калибровку"); return }
        if (!StepsState.serviceRunning.value) {
            AlertDialog.Builder(this)
                .setTitle("Нужен запущенный счётчик")
                .setMessage("Вернись на главный экран и нажми Старт — калибровке нужны шаги.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        when (k) {
            CalibrationRegistry.Kind.STRIDE -> chooseStrideMethod()
            CalibrationRegistry.Kind.WALK_TEMPO -> confirmTempo(k,
                "Пройди 30–50 шагов своим обычным шагом, потом нажми «Готово».\n\n" +
                "Система измерит твой темп: сколько миллисекунд занимает один шаг. " +
                "Это задаёт скорость, а от неё зависят калории.")
            CalibrationRegistry.Kind.RUN_TEMPO -> confirmTempo(k,
                "Пробеги 30–50 шагов в обычном темпе, потом нажми «Готово».\n\n" +
                "Система измерит темп бега. Это уточняет метку «бег» и время бега.")
        }
    }

    private fun confirmTempo(k: CalibrationRegistry.Kind, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(k.title)
            .setMessage(msg)
            .setPositiveButton("Начать") { _, _ ->
                activeKind = k
                val action = if (k == CalibrationRegistry.Kind.WALK_TEMPO)
                    StepService.ACTION_CAL_WALK else StepService.ACTION_CAL_RUN
                startForegroundService(Intent(this, StepService::class.java).setAction(action))
                showFinish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseStrideMethod() {
        AlertDialog.Builder(this)
            .setTitle("Длина шага")
            .setMessage("Два способа измерить одно и то же. GPS точнее и удобнее, " +
                    "но нужен открытый участок неба. Метраж работает где угодно, " +
                    "если знаешь длину отрезка.")
            .setPositiveButton("По GPS") { _, _ -> confirmGps() }
            .setNeutralButton("По метражу") { _, _ -> askMetres() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmGps() {
        AlertDialog.Builder(this)
            .setTitle("Калибровка по GPS")
            .setMessage(
                "Включит GPS-приёмник на время замера. Координаты нужны только для " +
                "подсчёта метров — никуда не отправляются и не сохраняются, StepCore " +
                "остаётся офлайн.\n\n" +
                "Где мерить для точности:\n" +
                "• открытое небо (не между домами, не в лесу)\n" +
                "• прямой участок, обычный шаг\n" +
                "• идеально 300–500 м\n\n" +
                "GPS в помещении и у высоких зданий врёт."
            )
            .setPositiveButton("Начать") { _, _ ->
                gpsPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startGps() {
        val cal = LocationCalibrator(this)
        if (!cal.isGpsEnabled()) { toastState("Включи GPS в настройках телефона"); return }
        gpsCal = cal
        activeKind = CalibrationRegistry.Kind.STRIDE
        gpsStepsAtStart = StepsState.steps.value
        cal.onUpdate = { metres, fixes, acc ->
            runOnUiThread {
                val a = if (acc >= 0) "±${acc.toInt()}м" else "—"
                toastState("GPS: %.0f м, точек %d, сигнал %s".format(metres, fixes, a))
            }
        }
        cal.start()
        toastState("GPS: иди по прямой… (ждём сигнал)")
        showFinish()
    }

    private fun askMetres() {
        val input = EditText(this).apply {
            hint = "Длина отрезка, м (например 500)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("По метражу")
            .setMessage("Введи длину известного отрезка. Пройди его обычным непрерывным " +
                    "шагом (без разворотов!) и нажми «Готово». Шаги посчитает приложение.")
            .setView(input)
            .setPositiveButton("Начать") { _, _ ->
                val m = input.text.toString().replace(',', '.').toFloatOrNull()
                if (m == null || m < 50f) { toastState("Нужен отрезок минимум 50 м"); return@setPositiveButton }
                activeKind = CalibrationRegistry.Kind.STRIDE
                startForegroundService(Intent(this, StepService::class.java)
                    .setAction(StepService.ACTION_CAL_DIST_START)
                    .putExtra(StepService.EXTRA_METRES, m))
                showFinish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun finishActive() {
        val k = activeKind ?: return
        when {
            k == CalibrationRegistry.Kind.STRIDE && gpsCal != null -> finishGps()
            k == CalibrationRegistry.Kind.STRIDE -> {
                startForegroundService(Intent(this, StepService::class.java)
                    .setAction(StepService.ACTION_CAL_DIST_STOP))
            }
            else -> startForegroundService(Intent(this, StepService::class.java)
                .setAction(StepService.ACTION_CAL_STOP))
        }
        activeKind = null
        finishBtn.visibility = View.GONE
        // Сервис пишет результат асинхронно - обновим карточки чуть позже.
        container.postDelayed({ render() }, 400)
    }

    private fun finishGps() {
        val cal = gpsCal ?: return
        val metres = cal.stop()
        gpsCal = null
        val steps = StepsState.steps.value - gpsStepsAtStart
        if (metres < 100f || steps < 30) {
            toastState("Мало данных (%.0f м, %d шагов). Нужно ≥100 м на открытом небе.".format(metres, steps))
            return
        }
        StrideModel.applyCalibration(this, metres, steps, byGps = true)
        lifecycleScope.launch { ProfileHistory.record(this@CalibrationActivity) }   // V11
        CalibrationRegistry.markDone(this, CalibrationRegistry.Kind.STRIDE)
        val cm = StrideModel.measuredStrideCm(this) ?: 0
        toastState("Готово (GPS): %.0f м за %d шагов = длина шага %d см".format(metres, steps, cm))
    }

    private fun showFinish() {
        finishBtn.visibility = View.VISIBLE
        calStatus.visibility = View.VISIBLE
    }

    private fun toastState(s: String) {
        StepsState.calibrationState.value = s
        calStatus.text = s
        calStatus.visibility = View.VISIBLE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
