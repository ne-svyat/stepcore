package com.vasil.stepcore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.core.content.ContextCompat
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    /**
     * v289. Один диалог на обе операции. Текст объясняет НЕ механику,
     * а последствие: что человек теряет, если не сделает этого.
     */
    fun backupDialog() {
        val t = TextView(this)
        t.text = "  Данные StepCore"
        t.textSize = 19f
        t.setPadding(48, 36, 48, 12)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(t)
            .setMessage(
                "В файл сохраняется ВСЁ, что нельзя собрать заново:\n\n" +
                "• шаги, калории, дистанция по дням и часам\n" +
                "• журнал событий\n" +
                "• корпус признаков — сырьё обучения\n" +
                "• сессии вместе с твоими ответами про уклон\n" +
                "• калибровки: длина шага, темп, якоря уклона\n\n" +
                "Зачем: при переустановке приложения база стирается " +
                "полностью. Файл — единственный способ вернуть месяцы " +
                "ходьбы. Держи свежий файл всегда.\n\n" +
                "Импорт ничего не перезаписывает: добавляется только то, " +
                "чего сейчас нет. Повторный импорт того же файла безопасен."
            )
            .setPositiveButton("Сохранить") { _, _ ->
                jsonSaver.launch("stepcore_full.json")
            }
            .setNegativeButton("Восстановить") { _, _ ->
                jsonImporter.launch(arrayOf(
                    "application/json", "application/octet-stream", "text/plain"))
            }
            .setNeutralButton("Закрыть", null)
            .show()
    }

    private companion object {
        const val EXTRA_BACKUP = "open_backup"

        /** Ключи калибровки, которые обязаны переживать переустановку.
         *  Всё это измерено ногами и задним числом не восстанавливается. */
        val Q = 34.toChar()

        fun jsonOrNull(v: String?): String =
            if (v == null) "null" else "" + Q + v + Q

        val CALIB_KEYS = listOf(
            "stride_a", "stride_b", "stride_by_gps", "stride_manual",
            "stride_cal_history",
            "walk_min_interval", "walk_max_interval",
            "run_min_interval", "run_max_interval",
            "cal_date_walk", "cal_date_run", "cal_date_stride",
            "slope_anchor_up", "slope_anchor_down", "slope_anchor_flat",
            "slope_anchor_up_ms", "slope_anchor_down_ms", "slope_anchor_flat_ms",
            // v297. ПРОФИЛЬ. В v288 его пропустили, и это стоило дорого:
            // после переустановки вес и рост не вернулись, масса вышла
            // нулевой, а `energyForHour` при нулевой массе возвращает 0
            // и по калориям, И по дистанции - обе цифры считаются в ней.
            // Шаги при этом шли нормально, поэтому поломка выглядела как
            // «приложение считает шаги, но не считает километры».
            "p_weight", "p_height", "p_age", "p_sex", "p_load", "p_goal",
            // v318. Измеренная длина бегового шага.
            "stride_run_m", "stride_run_ms", "stride_run_by_gps"
        )
    }


    private val selectedLines = LinkedHashSet<String>()
    private var copySelBtn: Button? = null
    private var currentFilterDays = 7
    private var visibleDays: List<DayRecord> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val csvSaver =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) lifecycleScope.launch {
                val days = AppDb.get(this@HistoryActivity).dao().allDays()
                val csv = buildString {
                    appendLine("date,walk_steps,run_steps,total")
                    days.forEach { appendLine("${it.date},${it.walkSteps},${it.runSteps},${it.walkSteps + it.runSteps}") }
                }
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                toast("CSV сохранён")
            }
        }

    /**
     * Экранирование строк для JSON (V11.15). Тексты событий содержат что
     * угодно (диаг-строки со скобками, будущие пользовательские заметки) -
     * без экранирования одна кавычка ломает весь бэкап.
     */
    private fun jsonEsc(t: String) = t
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "")

    /**
     * Полный бэкап, schema 2 (V11.15). Прежний формат терял снапшоты дней
     * (kcal, дистанция, активное время) и ВСЮ почасовую таблицу:
     * восстановление из него пересчитало бы прошлое текущим профилем -
     * ровно тот класс багов, что закрыт в V9.9-V11.9, - и оставило бы
     * Timeline внутри дня пустым. Теперь уходит всё, из чего состоит
     * история. Поле schema позволит импорту различать форматы.
     */
    private val jsonSaver =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) lifecycleScope.launch {
                val dao = AppDb.get(this@HistoryActivity).dao()
                val days = dao.allDays()
                val hours = dao.allHours()
                val events = dao.allEvents()
                // Секция экспедиций (schema 3): собирается survival-модулем,
                // ядро о его устройстве не знает. Вызов до buildString - suspend.
                val corpus = dao.allSamplesForBackup()
                val sessions = dao.allSessionsForBackup()
                val calPrefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
                val survivalJson = com.vasil.stepcore.survival.SurvivalBackup.exportFragment(this@HistoryActivity)
                val json = buildString {
                    appendLine("{")
                    appendLine("\"schema\":4,")
                    appendLine("\"days\":[")
                    append(days.joinToString(",\n") {
                        "{\"date\":\"${it.date}\",\"walk\":${it.walkSteps},\"run\":${it.runSteps}," +
                        "\"kcalActive\":${it.kcalActive},\"kcalBasal\":${it.kcalBasal}," +
                        "\"distanceM\":${it.distanceM},\"activeSec\":${it.activeSec}}"
                    })
                    appendLine("],")
                    appendLine("\"hours\":[")
                    append(hours.joinToString(",\n") {
                        "{\"dateHour\":\"${it.dateHour}\",\"walk\":${it.walkSteps},\"run\":${it.runSteps}}"
                    })
                    appendLine("],")
                    appendLine("\"events\":[")
                    append(events.joinToString(",\n") {
                        "{\"timeMs\":${it.timeMs},\"date\":\"${it.date}\",\"text\":\"${jsonEsc(it.text)}\"}"
                    })
                    appendLine("],")
                    // v288. Корпус, сессии и калибровка. Без них бэкап
                    // возвращал историю, но не возвращал ОБУЧЕНИЕ.
                    appendLine("\"corpus\":[")
                    append(corpus.joinToString(",\n") {
                        "{\"t\":${it.timeMs},\"label\":\"${it.label}\",\"mode\":\"${it.mode}\"," +
                        "\"amp\":${it.amp},\"iv\":${it.intervalMs},\"gyro\":${it.gyro}," +
                        "\"fv\":${it.featureVersion},\"pitch\":${it.pitchDeg}," +
                        "\"roll\":${it.rollDeg}}"
                    })
                    appendLine("],")
                    appendLine("\"sessions\":[")
                    append(sessions.joinToString(",\n") {
                        "{\"s\":${it.startMs},\"e\":${it.endMs},\"d\":${it.durationMs}," +
                        "\"label\":\"${it.label}\",\"n\":${it.nSamples}," +
                        "\"rel\":${if (it.reliable) 1 else 0}," +
                        "\"walk\":${it.walkShare},\"run\":${it.runShare},\"chip\":${it.chipShare}," +
                        "\"amp\":${it.ampMed},\"ampIqr\":${it.ampIqr}," +
                        "\"cad\":${it.cadenceMed},\"cadIqr\":${it.cadenceIqr}," +
                        "\"pitch\":${it.pitchMed},\"gyro\":${it.gyroMed}," +
                        "\"fv\":${it.featureVersion},\"ampT\":${it.ampTrend}," +
                        "\"cadT\":${it.cadenceTrend},\"stab\":${it.rhythmStab}," +
                        "\"pr\":${it.pitchRange},\"conf\":${it.confirmState}," +
                        "\"user\":" + jsonOrNull(it.userLabel) + "}"
                    })
                    appendLine("],")
                    appendLine("\"calib\":{")
                    append(CALIB_KEYS.mapNotNull { k ->
                        val v = calPrefs.all[k] ?: return@mapNotNull null
                        val vs = when (v) {
                            is String -> "\"" + jsonEsc(v) + "\""
                            is Boolean -> if (v) "true" else "false"
                            else -> v.toString()
                        }
                        "\"" + k + "\":" + vs
                    }.joinToString(",\n"))
                    appendLine("},")
                    append(survivalJson)
                    appendLine("}")
                }
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                toast("JSON сохранён (полный бэкап)")
            }
        }

    private val jsonImporter =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch { importJson(uri) }
        }

    /**
     * Импорт бэкапа (V11.16). Правила безопасности:
     *   - существующие дни/часы НИКОГДА не перезаписываются - импорт только
     *     добавляет отсутствующее. Локальные данные всегда главнее файла;
     *   - события дедуплицируются по timeMs - повторный импорт того же файла
     *     не плодит копии;
     *   - schema 1 - прежний неполный формат: дни без снапшотов, часов нет
     *     совсем. Принимается, но с честным предупреждением в отчёте;
     *   - битый файл - отказ целиком, база не тронута.
     */
    private suspend fun importJson(uri: android.net.Uri) {
        toast("Импортирую... Большой файл - до минуты")
        val report = try {
            // NonCancellable (V11.18): раньше импорт жил в lifecycleScope
            // экрана - уход на главный посреди большого файла ОТМЕНЯЛ его на
            // середине. Реальный случай: события до 08.07 успели вставиться,
            // 09-10.07 - нет, а отмена маскировалась под "файл повреждён".
            // Начатый импорт теперь доводится до конца независимо от экрана.
            withContext(Dispatchers.IO + NonCancellable) {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext "Не удалось открыть файл"
                val root = org.json.JSONObject(text)
                val schema = root.optInt("schema", 1)
                val db = AppDb.get(this@HistoryActivity)
                val dao = db.dao()
                val haveDates = dao.allDays().map { it.date }.toHashSet()
                val haveHours = dao.allHours().map { it.dateHour }.toHashSet()
                val haveTimes = dao.allEventTimes().toHashSet()
                var dA = 0; var dS = 0; var hA = 0; var hS = 0; var eA = 0; var eS = 0

                // Одна транзакция на весь импорт (V11.18): на порядки быстрее
                // тысяч одиночных вставок и атомарно - при любой ошибке
                // откатывается ВСЁ, частичного импорта не бывает.
                db.withTransaction {

                val days = root.optJSONArray("days") ?: org.json.JSONArray()
                for (i in 0 until days.length()) {
                    val o = days.getJSONObject(i)
                    val date = o.getString("date")
                    if (date in haveDates) { dS++; continue }
                    dao.insertDayIfAbsent(DayRecord(
                        date = date,
                        walkSteps = o.optInt("walk", 0),
                        runSteps = o.optInt("run", 0),
                        kcalActive = o.optInt("kcalActive", -1),
                        kcalBasal = o.optInt("kcalBasal", -1),
                        distanceM = o.optInt("distanceM", -1),
                        activeSec = o.optInt("activeSec", -1),
                    ))
                    dA++
                }
                val hours = root.optJSONArray("hours") ?: org.json.JSONArray()
                for (i in 0 until hours.length()) {
                    val o = hours.getJSONObject(i)
                    val k = o.getString("dateHour")
                    if (k in haveHours) { hS++; continue }
                    dao.insertHourIfAbsent(HourRecord(
                        dateHour = k,
                        walkSteps = o.optInt("walk", 0),
                        runSteps = o.optInt("run", 0),
                    ))
                    hA++
                }
                val events = root.optJSONArray("events") ?: org.json.JSONArray()
                for (i in 0 until events.length()) {
                    val o = events.getJSONObject(i)
                    val t = o.getLong("timeMs")
                    if (t in haveTimes) { eS++; continue }
                    dao.addEvent(EventRecord(
                        timeMs = t,
                        date = o.getString("date"),
                        text = o.getString("text"),
                    ))
                    eA++
                }
                }
                // v288. Корпус, сессии и калибровка. Правила те же, что для
                // дней: существующее НЕ перезаписывается, только добавляется
                // отсутствующее. Дедупликация корпуса по timeMs, сессий по
                // startMs. Калибровка ставится, только если её сейчас нет:
                // свежий замер на этом телефоне всегда главнее файла.
                var addCorpus = 0
                var addSess = 0
                var addCalib = 0
                val corpusArr = root.optJSONArray("corpus")
                if (corpusArr != null && corpusArr.length() > 0) {
                    val haveT = dao.allSampleTimes().toHashSet()
                    for (i in 0 until corpusArr.length()) {
                        val o = corpusArr.getJSONObject(i)
                        val t = o.optLong("t", 0L)
                        if (t <= 0L || haveT.contains(t)) continue
                        dao.insertSample(
                            TerrainSample(
                                timeMs = t,
                                label = o.optString("label", "NONE"),
                                mode = o.optString("mode", "IDLE"),
                                amp = o.optDouble("amp", 0.0).toFloat(),
                                intervalMs = o.optDouble("iv", 0.0).toFloat(),
                                gyro = o.optDouble("gyro", 0.0).toFloat(),
                                featureVersion = o.optInt("fv", 1),
                                pitchDeg = if (o.isNull("pitch")) null
                                    else o.optDouble("pitch").toFloat(),
                                rollDeg = if (o.isNull("roll")) null
                                    else o.optDouble("roll").toFloat()
                            )
                        )
                        haveT.add(t); addCorpus++
                    }
                }
                val sessArr = root.optJSONArray("sessions")
                if (sessArr != null && sessArr.length() > 0) {
                    val haveS = dao.allSessionStarts().toHashSet()
                    for (i in 0 until sessArr.length()) {
                        val o = sessArr.getJSONObject(i)
                        val st = o.optLong("s", 0L)
                        if (st <= 0L || haveS.contains(st)) continue
                        fun fOrNull(k: String): Float? =
                            if (o.isNull(k)) null else o.optDouble(k).toFloat()
                        dao.insertSession(
                            SessionRecord(
                                startMs = st,
                                endMs = o.optLong("e", st),
                                durationMs = o.optLong("d", 0L),
                                label = o.optString("label", "NONE"),
                                nSamples = o.optInt("n", 0),
                                reliable = o.optInt("rel", 0) == 1,
                                walkShare = o.optDouble("walk", 0.0).toFloat(),
                                runShare = o.optDouble("run", 0.0).toFloat(),
                                ampMed = fOrNull("amp"), ampIqr = fOrNull("ampIqr"),
                                cadenceMed = fOrNull("cad"), cadenceIqr = fOrNull("cadIqr"),
                                pitchMed = fOrNull("pitch"), gyroMed = fOrNull("gyro"),
                                chipShare = o.optDouble("chip", 0.0).toFloat(),
                                featureVersion = o.optInt("fv", 5),
                                ampTrend = fOrNull("ampT"), cadenceTrend = fOrNull("cadT"),
                                rhythmStab = fOrNull("stab"), pitchRange = fOrNull("pr"),
                                confirmState = o.optInt("conf", 0),
                                builtFromMaxTimeMs = o.optLong("e", st),
                                userLabel = if (o.isNull("user")) null else o.optString("user")
                            )
                        )
                        haveS.add(st); addSess++
                    }
                }
                val calObj = root.optJSONObject("calib")
                if (calObj != null) {
                    val p = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
                    val ed = p.edit()
                    for (k in CALIB_KEYS) {
                        if (!calObj.has(k) || p.contains(k)) continue
                        val v = calObj.get(k)
                        when (v) {
                            is String -> ed.putString(k, v)
                            is Boolean -> ed.putBoolean(k, v)
                            is Int -> ed.putLong(k, v.toLong())
                            is Long -> ed.putLong(k, v)
                            is Double -> ed.putFloat(k, v.toFloat())
                            else -> {}
                        }
                        addCalib++
                    }
                    ed.apply()
                }

                // Экспедиции (schema 3): отдельная БД - отдельная транзакция.
                // Пустая строка = секции в файле нет, survival не трогается.
                val svReport = com.vasil.stepcore.survival.SurvivalBackup.importFromBackup(this@HistoryActivity, root)
                buildString {
                    append("Дни: +$dA, дубликатов $dS\n")
                    append("Часы: +$hA, дубликатов $hS\n")
                    append("События: +$eA, дубликатов $eS")
                    if (svReport.isNotEmpty()) append("\n" + svReport)
                    if (addCorpus + addSess + addCalib > 0) {
                        append("\n\nОбучение: корпус " + addCorpus +
                            ", сессии " + addSess + ", калибровки " + addCalib)
                    }
                    if (schema < 4) append("\n\nВ этом файле нет корпуса, сессий и " +
                        "калибровок - он снят до версии 1.83. Обучение из него не вернётся.")
                    if (schema < 2) append("\n\nВнимание: это старый неполный бэкап - " +
                        "без калорий, дистанции, активного времени и почасовых данных. " +
                        "Импортированные дни будут считаться по текущему профилю.")
                }
            }
        } catch (e: org.json.JSONException) {
            "Импорт не удался: файл повреждён или это не бэкап StepCore. База не изменена."
        } catch (e: Exception) {
            // Не выдумываем причину: показываем класс ошибки. Транзакция
            // гарантирует, что база откатилась в исходное состояние.
            "Импорт не удался: ${e.javaClass.simpleName}. Транзакция откатила изменения, база в исходном состоянии."
        }
        // V11.17: порядок и защита. Раньше диалог показывался ДО reload и
        // падал с BadTokenException, если окно Activity уже невалидно (MIUI
        // уводит его за системным пикером файлов, а импорт большого файла
        // долгий). Падение убивало процесс ПОСЛЕ успешной записи в базу:
        // данные были на месте, но экран не обновлялся - выглядело как
        // "импорт не сработал, появилось со второго захода".
        // Теперь: сначала обновить экран, потом диалог - и только если окно
        // живо; иначе тост (он не привязан к окну Activity).
        reload()
        if (!isFinishing && !isDestroyed) {
            try {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Импорт завершён")
                    .setMessage(report)
                    .setPositiveButton("Понятно", null)
                    .show()
            } catch (e: Exception) {
                toast("Импорт завершён. " + report.replace("\n", " · "))
            }
        } else {
            toast("Импорт завершён. " + report.replace("\n", " · "))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        UiKit.screenTitle(this, UiKit.ACCENT_DATA)
        // V14.4: своя сцена — архивные тетради, нейтральный серый.
        findViewById<DoodleSceneView>(R.id.doodleHeader).setScene(DoodleSceneView.HISTORY)

        styleHistoryButtons()
        findViewById<Button>(R.id.filter7).setOnClickListener { setFilter(7) }
        findViewById<Button>(R.id.filter30).setOnClickListener { setFilter(30) }
        findViewById<Button>(R.id.filter365).setOnClickListener { setFilter(365) }
        findViewById<Button>(R.id.filterAll).setOnClickListener { setFilter(Int.MAX_VALUE) }

        copySelBtn = findViewById(R.id.copySelectedButton)
        updateSelLabel()
        copySelBtn!!.setOnClickListener {
            copyLine(selectedLines.sorted().joinToString("\n"))
        }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyVisible() }
        findViewById<Button>(R.id.exportCsvButton).setOnClickListener {
            csvSaver.launch("stepcore_days.csv")
        }
        // v291. Тот же диалог, что открывается из SYNX. Один код на оба
        // входа: если объяснение поменяется, оно поменяется в обоих местах,
        // и «а это одно и то же?» не возникнет.
        findViewById<TextView>(R.id.backupPlate).setOnClickListener { backupDialog() }
        DoodleUi.frame(findViewById<TextView>(R.id.backupPlate),
            UiKit.ACCENT_DATA, R.color.surface, 407L, DoodleBorderDrawable.MAT_MECH)

        // v289. Бэкап нужен там, где человек о нём вспоминает: перед
        // калибровкой и в SYNX. Экран Истории открывается с флагом и
        // сразу показывает выбор, не заставляя искать кнопки внизу.
        if (intent?.getBooleanExtra(EXTRA_BACKUP, false) == true) {
            backupDialog()
        }

        findViewById<Button>(R.id.deleteButton).setOnClickListener {
            val input = findViewById<EditText>(R.id.deleteConfirmInput).text.toString().trim()
            if (input != "УДАЛИТЬ ДАННЫЕ") {
                toast("Для удаления введи точно: УДАЛИТЬ ДАННЫЕ")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val dao = AppDb.get(this@HistoryActivity).dao()
                dao.deleteAllDays()
                dao.deleteAllEvents()
                    dao.deleteAllHours()
                findViewById<EditText>(R.id.deleteConfirmInput).setText("")
                toast("Вся история удалена")
                reload()
            }
        }

        setFilter(7)
    }

    private fun setFilter(days: Int) {
        currentFilterDays = days
        styleHistoryButtons()
        reload()
    }

    /**
     * Кнопки экрана в каменном языке приложения с явной иерархией:
     * активный фильтр периода светится, экспорт/импорт - спокойный камень,
     * удаление - единственная красная кнопка на экране.
     */
    private fun styleHistoryButtons() {
        val d = resources.displayMetrics.density
        fun plate(v: Button?, accent: Int, mat: Int, seed: Long, bright: Boolean) {
            if (v == null) return
            v.stateListAnimator = null
            v.elevation = 0f
            v.background = DoodleBorderDrawable(
                ContextCompat.getColor(this, accent),
                ContextCompat.getColor(this, R.color.surface),
                seed, d, mat)
            v.setTextColor(ContextCompat.getColor(this,
                if (bright) accent else R.color.text_dim))
            v.setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
        }
        val filters = listOf(
            Triple(R.id.filter7, 7, 311L),
            Triple(R.id.filter30, 30, 312L),
            Triple(R.id.filter365, 365, 313L),
            Triple(R.id.filterAll, Int.MAX_VALUE, 314L),
        )
        for ((id, days, seed) in filters) {
            val active = currentFilterDays == days
            plate(findViewById(id), if (active) R.color.accent_blue else R.color.axis_dim,
                if (active) DoodleBorderDrawable.MAT_LIGHTNING else DoodleBorderDrawable.MAT_ROCK,
                seed, active)
        }
        plate(findViewById(R.id.copyButton), R.color.axis_dim, DoodleBorderDrawable.MAT_ROCK, 321L, false)
        plate(findViewById(R.id.exportCsvButton), R.color.accent_green, DoodleBorderDrawable.MAT_ROCK, 322L, true)
        plate(findViewById(R.id.copySelectedButton), R.color.accent_blue, DoodleBorderDrawable.MAT_ROCK, 325L, true)
        // Удаление необратимо - единственная красная плита, с огнём.
        plate(findViewById(R.id.deleteButton), R.color.accent_red, DoodleBorderDrawable.MAT_FIRE, 326L, true)
    }

    private fun reload() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@HistoryActivity).dao()
            val container = findViewById<LinearLayout>(R.id.daysContainer)
            container.removeAllViews()
            val summary = findViewById<TextView>(R.id.summaryText)

            // 7д/30д - плоский список дней (частый сценарий: недавнее).
            // Год/Всё - иерархия МЕСЯЦЕВ (V9.6): масштаб на годы вперёд.
            if (currentFilterDays == Int.MAX_VALUE || currentFilterDays >= 365) {
                val months = dao.months()
                visibleDays = dao.allDays()
                val w = visibleDays.sumOf { it.walkSteps }
                val r = visibleDays.sumOf { it.runSteps }
                summary.text = "Месяцев: ${months.size}   " +
                        "Всего: ${w + r}   Ходьба: $w   Бег: $r"
                months.forEach { container.addView(makeMonthRow(it)) }
            } else {
                visibleDays = dao.recentDays(currentFilterDays)
                val w = visibleDays.sumOf { it.walkSteps }
                val r = visibleDays.sumOf { it.runSteps }
                summary.text = "Дней: ${visibleDays.size}   " +
                        "Всего: ${w + r}   Ходьба: $w   Бег: $r"
                visibleDays.forEach { day -> container.addView(makeDayRow(day)) }
            }
        }
    }

    /**
     * Верхний уровень МЕСЯЦА (V9.6): карточка с суммой, тап -> ленивая
     * загрузка дней месяца (переиспользует makeDayRow -> часы -> события).
     * Через год это ~12 карточек вместо 365 строк.
     */
    private fun makeMonthRow(m: MonthAgg): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
        }
        val total = m.walk + m.run
        val title = monthTitle(m.ym)
        val header = TextView(this).apply {
            text = "$title    ${fmtNum(total)} шагов · ${m.days} дн  \u25b8"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@HistoryActivity, R.color.text_main))
            setPadding(20, 22, 16, 22)
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                this@HistoryActivity, R.color.surface))
        }
        val daysBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 0, 0, 8)
        }
        header.setOnClickListener {
            if (daysBox.visibility == View.GONE) {
                daysBox.visibility = View.VISIBLE
                header.text = "$title    ${fmtNum(total)} шагов · ${m.days} дн  \u25be"
                lifecycleScope.launch {
                    daysBox.removeAllViews()
                    val days = AppDb.get(this@HistoryActivity).dao().daysOfMonth(m.ym)
                    days.forEach { daysBox.addView(makeDayRow(it)) }
                }
            } else {
                daysBox.visibility = View.GONE
                header.text = "$title    ${fmtNum(total)} шагов · ${m.days} дн  \u25b8"
            }
        }
        col.addView(header)
        col.addView(daysBox)
        return col
    }

    /** "2026-07" -> "Июль 2026". */
    private fun monthTitle(ym: String): String {
        val names = arrayOf("Январь","Февраль","Март","Апрель","Май","Июнь",
            "Июль","Август","Сентябрь","Октябрь","Ноябрь","Декабрь")
        return try {
            val y = ym.substring(0, 4)
            val mo = ym.substring(5, 7).toInt()
            "${names[mo - 1]} $y"
        } catch (e: Exception) { ym }
    }

    private fun fmtNum(n: Int) = "%,d".format(n).replace(',', ' ')

    /**
     * День -> ленивый список ЧАСОВ (V9.4). Раскрытие дня рендерит только
     * заголовки непустых часов (дёшево), раскрытие часа грузит и рендерит
     * события ЭТОГО часа из БД (eventsInRange). Снимает фриз при 1000+
     * логах: раньше раскрытие дня строило все строки разом.
     */
    private fun makeDayRow(day: DayRecord): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val total = day.walkSteps + day.runSteps
        val headerLine = "${day.date}   $total шагов (ходьба ${day.walkSteps}, бег ${day.runSteps})"
        val header = TextView(this).apply {
            text = "$headerLine  \u25b8"
            textSize = 16f
            setPadding(8, 20, 0, 20)
            setOnLongClickListener { copyLine(headerLine); true }
        }
        // V9.5: чекбокс выделяет ВЕСЬ день (все события), не раскрывая его.
        val dayCheck = CheckBox(this).apply {
            setOnCheckedChangeListener { _, c ->
                lifecycleScope.launch {
                    val evs = AppDb.get(this@HistoryActivity).dao().eventsOfDay(day.date)
                    evs.forEach { e ->
                        val full = "${day.date} ${timeFmt.format(Date(e.timeMs))}  ${e.text}"
                        if (c) selectedLines.add(full) else selectedLines.remove(full)
                    }
                    updateSelLabel()
                }
            }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dayCheck)
            addView(header)
        }
        val hoursBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 0, 0, 12)
        }
        header.setOnClickListener {
            if (hoursBox.visibility == View.GONE) {
                hoursBox.visibility = View.VISIBLE
                header.text = "$headerLine  \u25be"
                lifecycleScope.launch {
                    hoursBox.removeAllViews()
                    val counts = AppDb.get(this@HistoryActivity).dao().eventHourCounts(day.date)
                    if (counts.isEmpty()) {
                        hoursBox.addView(TextView(this@HistoryActivity).apply {
                            text = "Событий нет"; textSize = 14f
                        })
                    } else {
                        hoursBox.addView(Button(this@HistoryActivity).apply {
                            text = "\u29c9 Копировать весь день"
                            setOnClickListener { copyWholeDay(day, headerLine) }
                        })
                        counts.forEach { hc -> hoursBox.addView(makeHourRow(day.date, hc)) }
                    }
                }
            } else {
                hoursBox.visibility = View.GONE
                header.text = "$headerLine  \u25b8"
            }
        }
        col.addView(headerRow)
        col.addView(hoursBox)
        return col
    }

    /** Строка ЧАСА: заголовок "HH:00 (N)" + ленивая загрузка событий часа. */
    private fun makeHourRow(date: String, hc: HourCount): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hourLabel = "%02d:00".format(hc.hour)
        val (hFrom, hTo) = hourRangeMs(date, hc.hour)
        val header = TextView(this).apply {
            text = "  $hourLabel  (${hc.cnt})  \u25b8"
            textSize = 15f
            setPadding(4, 14, 0, 14)
        }
        // V9.5: чекбокс выделяет весь ЧАС (все его события) без раскрытия.
        val hourCheck = CheckBox(this).apply {
            setOnCheckedChangeListener { _, c ->
                lifecycleScope.launch {
                    val evs = AppDb.get(this@HistoryActivity).dao().eventsInRange(hFrom, hTo)
                    evs.forEach { e ->
                        val full = "$date ${timeFmt.format(Date(e.timeMs))}  ${e.text}"
                        if (c) selectedLines.add(full) else selectedLines.remove(full)
                    }
                    updateSelLabel()
                }
            }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hourCheck)
            addView(header)
        }
        val evBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(20, 0, 0, 8)
        }
        header.setOnClickListener {
            if (evBox.visibility == View.GONE) {
                evBox.visibility = View.VISIBLE
                header.text = "  $hourLabel  (${hc.cnt})  \u25be"
                lifecycleScope.launch {
                    evBox.removeAllViews()
                    val (from, to) = hourRangeMs(date, hc.hour)
                    val events = AppDb.get(this@HistoryActivity).dao().eventsInRange(from, to)
                    events.reversed().forEach { e -> evBox.addView(makeEventRow(date, e)) }
                }
            } else {
                evBox.visibility = View.GONE
                header.text = "  $hourLabel  (${hc.cnt})  \u25b8"
            }
        }
        col.addView(headerRow)
        col.addView(evBox)
        return col
    }

    /** Одна строка события: чекбокс выбора + текст (тап = копировать). */
    private fun makeEventRow(date: String, e: EventRecord): View {
        val shown = "${timeFmt.format(Date(e.timeMs))}  ${e.text}"
        val full = "$date $shown"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(CheckBox(this).apply {
            isChecked = selectedLines.contains(full)
            setOnCheckedChangeListener { _, c ->
                if (c) selectedLines.add(full) else selectedLines.remove(full)
                updateSelLabel()
            }
        })
        row.addView(TextView(this).apply {
            text = shown
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(4, 12, 0, 12)
            setOnClickListener { copyLine(full) }
        })
        return row
    }

    /** Границы часа [from, to) в мс локального времени. */
    private fun hourRangeMs(date: String, hour: Int): Pair<Long, Long> {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH", Locale.getDefault())
        val from = sdf.parse("$date %02d".format(hour))?.time ?: 0L
        return from to (from + 3_600_000L)
    }

    private fun copyWholeDay(day: DayRecord, headerLine: String) {
        lifecycleScope.launch {
            val events = AppDb.get(this@HistoryActivity).dao().eventsOfDay(day.date)
            val text = "$headerLine\n" + events.joinToString("\n") { e ->
                "${timeFmt.format(Date(e.timeMs))}  ${e.text}"
            }
            copyLine(text)
        }
    }

    private fun updateSelLabel() {
        val n = selectedLines.size
        copySelBtn?.apply {
            text = "Копировать выбранные ($n)"
            visibility = if (n > 0) View.VISIBLE else View.GONE
        }
    }

    /** Тап по строке журнала кладёт её одну в буфер обмена. */
    private fun copyLine(line: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("StepCore", line))
        toast("Строка скопирована")
    }

    /** Копия видимого периода: дни + журнал событий каждого дня. */
    private fun copyVisible() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@HistoryActivity).dao()
            val text = buildString {
                appendLine("StepCore — история")
                visibleDays.forEach { d ->
                    appendLine()
                    appendLine("${d.date}  ${d.walkSteps + d.runSteps} шагов (ходьба ${d.walkSteps}, бег ${d.runSteps})")
                    dao.eventsOfDay(d.date).forEach { e ->
                        appendLine("  ${timeFmt.format(Date(e.timeMs))}  ${e.text}")
                    }
                }
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("StepCore", text))
            toast("Скопировано с событиями")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // Механизм дудл-анимации крутится, пока виден хоть один экран.
    // onStart нового экрана срабатывает РАНЬШЕ onStop старого, поэтому при
    // переходе между вкладками счётчик не касается нуля и анимация не глохнет.
    override fun onStart() {
        super.onStart()
        BoilClock.screenStarted()
    }

    override fun onStop() {
        BoilClock.screenStopped()
        super.onStop()
    }
}
