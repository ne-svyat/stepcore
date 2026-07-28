package com.vasil.stepcore

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** SYNX — экран модуля обучения.
 *  v198: стихия читает состояние (огонь/электричество/вода).
 *  L3.0: тумблер обучения + лесенка вопросов при открытии
 *  (ворота -> режим -> подтверждение метки уклона) -> три архива. */
class SynxActivity : AppCompatActivity() {

    // Порог "достаточно" для уклона. Выведен, не угадан: провизорно ~10
    // надёжных сессий на каждое направление (в гору/с горы), чтобы оценить
    // личную медиану и разброс признаков уклона без диктата одного выброса.
    // Уточнится, когда L3 определит реальное "достаточно".
    private val inclineTarget = 20

    // Сколько вопросов подряд за один заход. Потолок, а не цель.
    private var askedInVisit = 0
    private val MAX_ASK_PER_VISIT = 10

    /** Состав корпуса: сколько образцов и по каким меткам. Ноль по метке -
     *  это ноль ДАННЫХ, а не отсутствие уклона: показываем честно. */
    private suspend fun refreshCorpusSynx(view: TextView) {
        val dao = AppDb.get(this).dao()
        val total = dao.countSamplesV3()
        if (total == 0) { view.text = "Корпус: пока пусто"; return }
        val flat = dao.countSamplesLabel("FLAT")
        val up = dao.countSamplesLabel("UP")
        val down = dao.countSamplesLabel("DOWN")
        val chip = dao.countSamplesChip()
        // v248. Состояние автометки: человек должен видеть, включилась ли
        // она и по каким направлениям, иначе непонятно, работает ли L3.2.
        val pr = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val upOk = pr.getInt("ia_up_ok", 0); val upN = pr.getInt("ia_up_n", 0)
        val dnOk = pr.getInt("ia_down_ok", 0); val dnN = pr.getInt("ia_down_n", 0)
        val autoCnt = dao.countAutoLabeled()
        fun st(ok: Int, n: Int): String {
            if (n == 0) return "нет данных"
            val lb = InclineAgent.wilsonLower(ok, n)
            return ok.toString() + "/" + n + " = " +
                String.format(java.util.Locale.US, "%.0f", lb * 100) + "%" +
                ""
        }
        val bUpOk = pr.getInt("ia_bup_ok", 0); val bUpN = pr.getInt("ia_bup_n", 0)
        val bDnOk = pr.getInt("ia_bdown_ok", 0); val bDnN = pr.getInt("ia_bdown_n", 0)
        fun stBlind(ok: Int, n: Int): String {
            if (n == 0) return "проверок ещё не было"
            val lb = InclineAgent.wilsonLower(ok, n)
            return ok.toString() + "/" + n + " = " +
                String.format(java.util.Locale.US, "%.0f", lb * 100) + "%" +
                (if (n < InclineAgent.BLIND_MIN)
                    "  (нужно " + InclineAgent.BLIND_MIN + " проверок)"
                 else if (lb >= InclineAgent.AUTO_TRUST) "  ✓ авто" else "")
        }
        view.text = "Корпус: " + total + " образцов" +
            "\n  вслепую: в гору " + stBlind(bUpOk, bUpN) +
            "\n           с горы " + stBlind(bDnOk, bDnN) +
            "\n  с подсказкой: в гору " + st(upOk, upN) +
            "\n                с горы " + st(dnOk, dnN) +
            (if (autoCnt > 0) "\n  агент разметил сам: " + autoCnt else "") +
            "\n  ровно " + flat + " · в гору " + up + " · с горы " + down +
            "\n  от детектора " + (total - chip) + " · от чипа " + chip
    }

    /** Экспорт ВСЕХ сессий в CSV (в буфер). Все, не только надёжные: для
     *  разбора нужна полная картина, включая короткие уклонные. */
    /** Диагностика курса: есть ли на чём строить понимание "вернулся тем
     *  же путём". Только читает. Вывод делает человек. */
    private suspend fun headingReport() {
        val dao = AppDb.get(this).dao()
        val all = dao.countSamples()
        val hs = dao.samplesWithHeading()
        val sb = StringBuilder("StepCore — диагностика курса\n\n")
        sb.append("Образцов всего: ").append(all).append("\n")
        sb.append("С курсом: ").append(hs.size)
        if (all > 0) sb.append("  (").append(hs.size * 100 / all).append("%)")
        sb.append("\n")
        if (hs.isEmpty()) {
            sb.append("\nКурса нет. Он пишется с v213 — нужно походить, ")
            sb.append("чтобы накопился.")
            deliver(sb.toString()); return
        }
        // Точность компаса: 3 высокая, 0 ненадёжная
        val acc = IntArray(4)
        for (x in hs) { val a = x.headingAcc ?: 0; if (a in 0..3) acc[a]++ }
        sb.append("Точность компаса: высокая ").append(acc[3])
          .append(", средняя ").append(acc[2])
          .append(", низкая ").append(acc[1])
          .append(", ненадёжная ").append(acc[0]).append("\n")
        val good = acc[3] + acc[2]
        sb.append("Годных (средняя и выше): ").append(good)
          .append("  (").append(if (hs.isEmpty()) 0 else good * 100 / hs.size)
          .append("%)\n")

        // Группируем по отрезкам между большими паузами
        val segs = ArrayList<HeadingDiag.Seg>()
        var cur = ArrayList<TerrainSample>()
        fun flush() {
            if (cur.size < 3) { cur = ArrayList(); return }
            val vals = cur.mapNotNull { it.headingDeg }
            if (vals.size < 3) { cur = ArrayList(); return }
            val accs = cur.mapNotNull { it.headingAcc }.sorted()
            segs.add(HeadingDiag.Seg(
                cur.first().label, cur.first().timeMs, vals.size,
                HeadingDiag.circularMedian(vals),
                HeadingDiag.circularSpread(vals),
                if (accs.isEmpty()) 0 else accs[accs.size / 2]))
            cur = ArrayList()
        }
        for (x in hs) {
            if (cur.isNotEmpty() &&
                x.timeMs - cur.last().timeMs > SEG_GAP_MS) flush()
            cur.add(x)
        }
        flush()
        sb.append("Отрезков с курсом: ").append(segs.size).append("\n\n")

        if (segs.isEmpty()) {
            sb.append("Отрезков не набралось — курса пока мало.")
            deliver(sb.toString()); return
        }
        // Устойчивость: если разброс внутри отрезка велик, поворот тела
        // утонет в шуме и строить на курсе нечего.
        val spreads = segs.map { it.spread }.sorted()
        val medSpread = spreads[spreads.size / 2]
        sb.append("Разброс курса внутри отрезка: медиана ")
          .append(medSpread.toInt()).append("°")
          .append(if (medSpread <= 30f) "  — курс держится"
                  else "  — курс гуляет, поворот утонет в шуме").append("\n")

        // Развороты между соседними отрезками
        var rev = 0; var pairs = 0
        val lines = StringBuilder()
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("ru"))
        for (i in 1 until segs.size) {
            val a = segs[i - 1]; val b = segs[i]
            if (b.startMs - a.startMs > PAIR_GAP_MS) continue
            pairs++
            val d = HeadingDiag.angleDiff(a.headMed, b.headMed)
            val isRev = HeadingDiag.isReversal(a.headMed, b.headMed)
            if (isRev) rev++
            if (lines.length < 1200) {
                lines.append("  ").append(fmt.format(java.util.Date(a.startMs)))
                  .append("  ").append(labelRu(a.label)).append(" ")
                  .append(a.headMed.toInt()).append("° → ")
                  .append(labelRu(b.label)).append(" ")
                  .append(b.headMed.toInt()).append("°   смена ")
                  .append(d.toInt()).append("°")
                  .append(if (isRev) "  ↩ разворот" else "").append("\n")
            }
        }
        sb.append("Соседних пар: ").append(pairs)
          .append(", похоже на разворот: ").append(rev)
        if (pairs > 0) sb.append("  (").append(rev * 100 / pairs).append("%)")
        sb.append("\n\n").append(lines)
        sb.append("\nЧто это значит:\n")
        sb.append("Разворот на ~180° — прямая улика «иду обратно». Если таких\n")
        sb.append("пар много и разброс курса мал, на курсе можно строить\n")
        sb.append("понимание маршрута. Если мало — компас в кармане не тянет,\n")
        sb.append("и честнее это признать, чем городить догадки.\n")
        deliver(sb.toString())
    }

    /** Отчёт в буфер и файлом: буфер обрезает длинное. */
    private fun deliver(text: String) {
        var saved: String? = null
        try {
            val fname = "stepcore_heading_" + System.currentTimeMillis() + ".txt"
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray()); it.flush()
                }
                saved = fname
            }
        } catch (e: Exception) { saved = null }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("StepCore heading", text))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (saved != null) "Курс — отчёт (файл + буфер)"
                      else "Курс — отчёт (буфер)")
            .setMessage(text)
            .setPositiveButton("Ок", null)
            .show()
    }

    private suspend fun exportCorpusSynx() {
        val dao = AppDb.get(this).dao()
        val list = dao.allSessionsForMap()
        fun f(x: Float?): String =
            if (x == null) "" else String.format(java.util.Locale.US, "%.3f", x)
        val sb = StringBuilder()
        sb.append("startMs,label,userLabel,confirm,reliable,nSamples,durMs,")
        sb.append("walkShare,runShare,chipShare,ampMed,ampIqr,cadMed,cadIqr,")
        sb.append("pitchMed,gyroMed,ampTrend,cadTrend,rhythmStab,pitchRange\n")
        for (x in list) {
            sb.append(x.startMs).append(",").append(x.label).append(",")
                .append(x.userLabel ?: "").append(",").append(x.confirmState).append(",")
                .append(if (x.reliable) 1 else 0).append(",")
                .append(x.nSamples).append(",").append(x.durationMs).append(",")
                .append(f(x.walkShare)).append(",").append(f(x.runShare)).append(",")
                .append(f(x.chipShare)).append(",").append(f(x.ampMed)).append(",")
                .append(f(x.ampIqr)).append(",").append(f(x.cadenceMed)).append(",")
                .append(f(x.cadenceIqr)).append(",").append(f(x.pitchMed)).append(",")
                .append(f(x.gyroMed)).append(",").append(f(x.ampTrend)).append(",")
                .append(f(x.cadenceTrend)).append(",").append(f(x.rhythmStab)).append(",")
                .append(f(x.pitchRange)).append("\n")
        }
        // Буфер обмена обрезает большой корпус - пишем ФАЙЛ в Downloads,
        // его можно скинуть целиком. Буфер оставляем как запасной для мелочи.
        val text = sb.toString()
        var savedTo: String? = null
        try {
            val fname = "stepcore_corpus_" + System.currentTimeMillis() + ".csv"
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray()); it.flush()
                }
                savedTo = fname
            }
        } catch (e: Exception) { savedTo = null }

        val cm = getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("StepCore corpus", text))

        val msg = if (savedTo != null)
            "Корпус (" + list.size + " сессий) -> Downloads/" + savedTo + " и в буфере"
        else
            "Корпус (" + list.size + " сессий) в буфере (файл не удалось записать)"
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Инкрементальный догон сессий: строит только хвост корпуса после
     *  lastBuiltTimeMs, старые сессии с их ответами не трогает. Дёшево,
     *  без потери подтверждений - тот же движок SessionEngine, что и на главном
     *  экране, только без диагностики. */
    /** Разовая полная пересборка новым порогом. Сохраняет ответы человека
     *  (confirmState, userLabel) по совпадению времени и метки - прошлое
     *  неизменно. Тот же выверенный путь свёртки корпуса в сессии. */
    private suspend fun rebuildAllWithAnswers(dao: StepDao) {
        val saved = dao.answeredSessions().map {
            Triple(it.startMs + it.durationMs / 2, it.label, it.confirmState)
        }
        dao.deleteAllSessions()
        val raw = dao.samplesAfter(0L)
        if (raw.isNotEmpty()) {
            val maxT = raw.maxOf { it.timeMs }
            val input = raw.map { t ->
                val amp = if (t.sampleSource == 1) t.accRms else t.amp
                val cad = if (t.sampleSource == 1) t.zcrCadence
                          else if (t.intervalMs > 0f) 1000f / t.intervalMs else null
                SampleIn(
                    timeMs = t.timeMs, label = t.label, mode = t.mode,
                    featureVersion = t.featureVersion, sampleSource = t.sampleSource,
                    amp = amp, cadence = cad, pitchDeg = t.pitchDeg, gyro = t.gyro
                )
            }
            for (o in SessionEngine.build(input)) {
                dao.insertSession(SessionRecord(
                    startMs = o.startMs, endMs = o.endMs, durationMs = o.durationMs,
                    label = o.label, nSamples = o.nSamples, reliable = o.reliable,
                    walkShare = o.modeShare["WALK"] ?: 0f, runShare = o.modeShare["RUN"] ?: 0f,
                    ampMed = o.ampMed, ampIqr = o.ampIqr,
                    cadenceMed = o.cadenceMed, cadenceIqr = o.cadenceIqr,
                    pitchMed = o.pitchMed, gyroMed = o.gyroMed, chipShare = o.chipShare,
                    featureVersion = o.featureVersion, ampTrend = o.ampTrend,
                    cadenceTrend = o.cadenceTrend, rhythmStab = o.rhythmStab,
                    pitchRange = o.pitchRange, confirmState = o.confirmState,
                    builtFromMaxTimeMs = maxT
                ))
            }
        }
        for ((mid, label, state) in saved) dao.restoreConfirmAt(mid, label, state)
    }

    /** L3.2 автометка. Ставит подтверждение сам, но только там, где
     *  доказал право: направление с Уилсоном >= 90%, уверенный вердикт,
     *  запас до границы, и совпадение с меткой человека.
     *  Расхождение НЕ съедаем - оно уходит в вопросы, это самый ценный
     *  материал. Каждая пятая всё равно спрашивается: без контроля
     *  система перестанет замечать дрейф походки.
     *  Авто-метки помечаются состоянием 4 и НЕ идут в базу агента -
     *  иначе он начнёт учиться на своих же догадках. */
    private suspend fun autoLabelSessions(dao: StepDao) {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val upOk = prefs.getInt("ia_up_ok", 0); val upN = prefs.getInt("ia_up_n", 0)
        val dnOk = prefs.getInt("ia_down_ok", 0); val dnN = prefs.getInt("ia_down_n", 0)
        // v249. Право даёт только СЛЕПОЙ счёт: подсказанный меряет
        // согласие человека с собственной меткой, а не правоту агента.
        val bUpOk = prefs.getInt("ia_bup_ok", 0); val bUpN = prefs.getInt("ia_bup_n", 0)
        val bDnOk = prefs.getInt("ia_bdown_ok", 0); val bDnN = prefs.getInt("ia_bdown_n", 0)
        val upTrusted = InclineAgent.trustedBlind(bUpOk, bUpN)
        val dnTrusted = InclineAgent.trustedBlind(bDnOk, bDnN)
        if (!upTrusted && !dnTrusted) return

        var seen = prefs.getInt(KEY_AUTO_SEEN, 0)
        var marked = 0
        for (s in dao.autoLabelCandidates(AUTO_BATCH)) {
            val near = dao.sessionsAround(
                s.startMs - InclineAgent.WALK_GAP_MS,
                s.startMs + InclineAgent.WALK_GAP_MS)
            val r = InclineAgent.predict(
                InclineAgent.Input(s.startMs, s.chipShare, s.ampMed ?: 0f),
                near.map { InclineAgent.Input(it.startMs, it.chipShare, it.ampMed ?: 0f) })
            val v = when (r.verdict) {
                InclineAgent.Verdict.UP -> "UP"
                InclineAgent.Verdict.DOWN -> "DOWN"
                else -> ""
            }
            if (v == "") continue                     // не уверен - человеку
            if (v != s.label) continue                // расхождение - человеку
            if (r.margin < InclineAgent.AUTO_MARGIN) continue
            val ok = if (v == "UP") upTrusted else dnTrusted
            if (!ok) continue                         // направление не в доверии
            seen++
            if (seen % InclineAgent.AUTO_CONTROL_EVERY == 0) continue  // контроль
            dao.setSessionConfirm(s.id, 4)
            marked++
        }
        prefs.edit().putInt(KEY_AUTO_SEEN, seen).apply()
        if (marked > 0) {
            android.widget.Toast.makeText(this,
                "Агент сам разметил " + marked + " прогулок",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun catchUpSessions(dao: StepDao) {
        val after = dao.lastBuiltTimeMs()
        val raw = dao.samplesAfter(after)
        if (raw.isEmpty()) return
        val maxT = raw.maxOf { it.timeMs }
        val input = raw.map { t ->
            val amp = if (t.sampleSource == 1) t.accRms else t.amp
            val cad = if (t.sampleSource == 1) t.zcrCadence
                      else if (t.intervalMs > 0f) 1000f / t.intervalMs else null
            SampleIn(
                timeMs = t.timeMs, label = t.label, mode = t.mode,
                featureVersion = t.featureVersion, sampleSource = t.sampleSource,
                amp = amp, cadence = cad, pitchDeg = t.pitchDeg, gyro = t.gyro
            )
        }
        for (o in SessionEngine.build(input)) {
            dao.insertSession(SessionRecord(
                startMs = o.startMs, endMs = o.endMs, durationMs = o.durationMs,
                label = o.label, nSamples = o.nSamples, reliable = o.reliable,
                walkShare = o.modeShare["WALK"] ?: 0f, runShare = o.modeShare["RUN"] ?: 0f,
                ampMed = o.ampMed, ampIqr = o.ampIqr,
                cadenceMed = o.cadenceMed, cadenceIqr = o.cadenceIqr,
                pitchMed = o.pitchMed, gyroMed = o.gyroMed, chipShare = o.chipShare,
                featureVersion = o.featureVersion, ampTrend = o.ampTrend,
                cadenceTrend = o.cadenceTrend, rhythmStab = o.rhythmStab,
                pitchRange = o.pitchRange, confirmState = o.confirmState,
                builtFromMaxTimeMs = maxT
            ))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_synx)
        val orb = findViewById<SynxOrbView>(R.id.synxHeroOrb)
        val status = findViewById<TextView>(R.id.synxStatus)
        val learnSwitch = findViewById<SwitchCompat>(R.id.learnSwitch)
        // Кнопка должна выглядеть кнопкой: среди такого же зелёного текста
        // ссылка неотличима от описания. Плита - язык кнопок этого проекта.
        val profileButtonPlate = findViewById<TextView>(R.id.dayProfileButton)
        profileButtonPlate.background = DoodleBorderDrawable(
            androidx.core.content.ContextCompat.getColor(this, R.color.accent_amber),
            androidx.core.content.ContextCompat.getColor(this, R.color.surface),
            521L, resources.displayMetrics.density,
            DoodleBorderDrawable.MAT_ROCK, DoodleBorderDrawable.RIFT_NONE)
        profileButtonPlate.setOnClickListener {
            startActivity(android.content.Intent(this, DayProfileActivity::class.java))
        }

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        // Тумблер обучения. Выкл по умолчанию: пока не разрешил - вопросов нет.
        // v227. "Сбор при выключенном экране" переехал сюда из Инструментов.
        // Пишет тот же ключ bg_accel: сервис читает его из prefs напрямую,
        // и StepsState держит его для живого решения об окне сбора.
        val bgSwitch = findViewById<SwitchCompat>(R.id.synxBgAccelSwitch)
        bgSwitch.isChecked = prefs.getBoolean("bg_accel", false)
        StepsState.bgAccel.value = bgSwitch.isChecked
        bgSwitch.setOnCheckedChangeListener { _, checked ->
            StepsState.bgAccel.value = checked
            prefs.edit().putBoolean("bg_accel", checked).apply()
        }

        // v228. Счётчик корпуса и экспорт переехали сюда из Инструментов.
        val corpusText = findViewById<TextView>(R.id.synxCorpusText)
        lifecycleScope.launch { refreshCorpusSynx(corpusText) }
        findViewById<TextView>(R.id.synxExportButton).setOnClickListener {
            lifecycleScope.launch { exportCorpusSynx() }
        }
        findViewById<TextView>(R.id.synxHeadingButton).setOnClickListener {
            lifecycleScope.launch { headingReport() }
        }
                learnSwitch.isChecked = prefs.getBoolean(KEY_LEARN, false)
        learnSwitch.setOnCheckedChangeListener { _, checked ->
            // Включение - явное согласие: снимает и паузу "не беспокоить".
            val e = prefs.edit().putBoolean(KEY_LEARN, checked)
            if (checked) e.putLong(KEY_SNOOZE, 0L)
            e.apply()
        }

        // Фундамент: профиль заполнен, длина шага и темп ходьбы калиброваны.
        val profileDone = prefs.contains("p_weight")
        val strideDone = CalibrationRegistry.isDone(this, CalibrationRegistry.Kind.STRIDE)
        val walkDone = CalibrationRegistry.isDone(this, CalibrationRegistry.Kind.WALK_TEMPO)

        lifecycleScope.launch {
            val dao = AppDb.get(this@SynxActivity).dao()
            // v224. Догоняем сессии до входа в состояние: иначе свежие
            // прогулки не попадут ни в стихию, ни в вопросы, и счётчик
            // будет стоять, хотя человек весь день ходил. Догон
            // инкрементальный - только новый хвост, ответы не трогаются.
            // v226. Порог надёжности уклонных смягчён - но старые сессии в
            // базе построены строгим. Один раз пересобираем весь корпус
            // новым порогом (с сохранением ответов), потом снова инкремент.
            val prefsMig = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
            if (!prefsMig.getBoolean("reliable_v226_done", false)) {
                rebuildAllWithAnswers(dao)
                prefsMig.edit().putBoolean("reliable_v226_done", true).apply()
            }
            catchUpSessions(dao)
            // v244. L3.2: агент сам подтверждает направления, в которых
            // заслужил доверие. Строго после догона - иначе свежие
            // сессии не попадут под разбор.
            autoLabelSessions(dao)
            val reliableIncline = dao.countSessionsInclineReliable()
            when {
                !profileDone || !strideDone || !walkDone -> {
                    orb.setElement(SynxOrbView.Element.FIRE, 0.7f)
                    val miss = ArrayList<String>()
                    if (!profileDone) miss.add("профиль")
                    if (!strideDone) miss.add("длину шага")
                    if (!walkDone) miss.add("темп ходьбы")
                    status.text = "Огонь: сначала заложи фундамент — заполни " +
                        miss.joinToString(", ") + ". Без этого цифры — только оценка."
                }
                reliableIncline < inclineTarget -> {
                    val deficit = 1f - reliableIncline.toFloat() / inclineTarget
                    orb.setElement(SynxOrbView.Element.ELECTRIC, deficit.coerceIn(0.35f, 1f))
                    status.text = "Электричество: мало надёжных сессий уклона (" +
                        reliableIncline + " из ~" + inclineTarget +
                        "). Отмечай «в гору» и «с горы» на прогулке — каждая метка на счету."
                }
                else -> {
                    orb.setElement(SynxOrbView.Element.WATER, 0.5f)
                    status.text = "Вода: данных для уклона достаточно. Можно двигаться дальше."
                }
            }
            // L3.1: зрелость агента - по нижней границе Уилсона, не по сырому проценту.
            val upN = prefs.getInt("ia_up_n", 0)
            val downN = prefs.getInt("ia_down_n", 0)
            if (upN + downN > 0) {
                status.text = status.text.toString() + "\n\nАгент уклона: в гору — " +
                    InclineAgent.maturity(prefs.getInt("ia_up_ok", 0), upN) +
                    " (" + prefs.getInt("ia_up_ok", 0) + "/" + upN + "), с горы — " +
                    InclineAgent.maturity(prefs.getInt("ia_down_ok", 0), downN) +
                    " (" + prefs.getInt("ia_down_ok", 0) + "/" + downN + ")"
            }
            // L3.0: если обучение включено и есть свежая надёжная неспрошенная
            // уклонная сессия - запускаем лесенку. Одна сессия за раз.
            // Пауза видна человеку: иначе молчание выглядит как поломка.
            val snoozeUntil = prefs.getLong(KEY_SNOOZE, 0L)
            if (snoozeUntil > System.currentTimeMillis()) {
                val f = java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale("ru"))
                status.text = status.text.toString() + "\n\nОпрос на паузе до " +
                    f.format(java.util.Date(snoozeUntil)) +
                    ". Вернуть раньше — выключи и включи тумблер."
            }
            // Пауза "не беспокоить" уважается наравне с тумблером.
            val snoozed = prefs.getLong(KEY_SNOOZE, 0L) > System.currentTimeMillis()
            if (prefs.getBoolean(KEY_LEARN, false) && !snoozed) {
                // Уклон в дефиците -> приоритет ему. Но каждый 3-й вопрос про
                // ПЛОСКУЮ сессию: иначе "ровно" навсегда останется меткой по
                // умолчанию ("не нажимал"), а не подтверждённым классом.
                val s = nextCandidate(dao)
                if (s != null) askGate(s)
            }
        }
    }

    // --- L3.0: лесенка ворота -> режим -> подтверждение метки уклона ---

    private fun askGate(s: SessionRecord) {
        val longWalk = s.durationMs >= 20 * 60_000L
        val head = "Твоя прогулка:\n" + anchor(s) + "\n\n"
        val q = if (longWalk) head + "Долгая вышла — устал? Уделишь пару вопросов про неё?"
                else head + "Уделишь пару коротких вопросов про неё?"
        AlertDialog.Builder(this)
            .setTitle("SYNX учится")
            .setMessage(q)
            .setPositiveButton("Да") { _, _ ->
                if (longWalk) journal("SYNX ворота: усталость отмечена")
                askMode(s)
            }
            .setNegativeButton("Не сейчас", null)  // сессию не трогаем, спросим позже
            .show()
    }

    private fun askMode(s: SessionRecord) {
        val modes = arrayOf("Ходьба", "Бег", "Машина", "Покой")
        // Полная дата обязательна: по одному времени невозможно понять, какой
        // это был день, и легко ответить не про ту прогулку.
        lifecycleScope.launch {
            val head = dayHeader(s, "Что это было?\n\n" + anchor(s))
            AlertDialog.Builder(this@SynxActivity)
                .setCustomTitle(head)
                .setItems(modes) { _, which ->
                    journal("SYNX режим: " + modes[which] + " (" + anchor(s) + ")")
                    askIncline(s)
                }
                .show()
        }
    }

    /** Фактический состав меток внутри сессии. Метка сессии берётся с первого
     *  образца, а одиночные чужие образцы поглощаются (защита от мис-тапа).
     *  Поэтому показываем состав: проверяемость вместо доверия на слово. */
    private suspend fun labelBreakdown(s: SessionRecord): String {
        val dao = AppDb.get(this).dao()
        val list = dao.samplesBetween(s.startMs, s.endMs)
        if (list.isEmpty()) return ""
        val counts = LinkedHashMap<String, Int>()
        for (x in list) counts[x.label] = (counts[x.label] ?: 0) + 1
        val parts = ArrayList<String>()
        for ((k, v) in counts) parts.add(labelRu(k) + " " + v)
        return "Образцов " + list.size + ": " + parts.joinToString(", ")
    }

    private fun askIncline(s: SessionRecord) {
        lifecycleScope.launch {
            // v249. Агент говорит и про НЕотмеченные сессии: именно там он
            // приносит новое знание ("ты забыл отметить, тут был подъём"),
            // а не штампует уже проставленное. На FLAT молчит по-прежнему:
            // "ровно" от "в гору" по амплитуде не отделяется.
            var verdict = InclineAgent.Verdict.NO_BASIS
            var margin = 0f
            if (s.label != "FLAT") {
                val dao = AppDb.get(this@SynxActivity).dao()
                val near = dao.sessionsAround(
                    s.startMs - InclineAgent.WALK_GAP_MS,
                    s.startMs + InclineAgent.WALK_GAP_MS
                )
                val r = InclineAgent.predict(
                    InclineAgent.Input(s.startMs, s.chipShare, s.ampMed ?: 0f),
                    near.map { InclineAgent.Input(it.startMs, it.chipShare, it.ampMed ?: 0f) }
                )
                verdict = r.verdict; margin = r.margin
            }
            val guess = when (verdict) {
                InclineAgent.Verdict.UP -> "UP"
                InclineAgent.Verdict.DOWN -> "DOWN"
                else -> ""
            }
            // v249. Каждый N-й вопрос - слепой: без подсказки и без
            // объяснений агента. Только так измеряется правота, а не
            // согласие под наводящим вопросом.
            val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
            val asked = prefs.getInt(KEY_ASK_COUNT, 0) + 1
            prefs.edit().putInt(KEY_ASK_COUNT, asked).apply()
            val blind = guess != "" && asked % InclineAgent.BLIND_EVERY == 0
            if (blind) {
                val head = dayHeader(s, headText(s, "", labelBreakdown(s), ""))
                blindAsk(s, guess, head)
                return@launch
            }
            val note = confidenceNote(verdict, margin)
            val brk = labelBreakdown(s)
            val head = dayHeader(s, headText(s, guess, brk, note))
            showInclineDialog(s, guess, head)
        }
    }

    /** Текст вопроса об уклоне - отдельно, чтобы вложить его в шапку с картой. */
    private fun headText(
        s: SessionRecord, guess: String, breakdown: String, note: String = ""
    ): String {
        val head = anchor(s) + (if (breakdown == "") "" else "\n" + breakdown) + "\n\n"
        val tail = if (note == "") "" else "\n\n" + note
        return head + (if (guess != "" && guess != s.label)
            "Помечена «" + labelRu(s.label) + "», но по признакам похоже на «" +
                labelRu(guess) + "». Что было на самом деле?" + tail
        else if (s.label == "NONE")
            "Уклон не отмечен. Она была ровной?"
        else if (s.label == "FLAT")
            "Помечена «ровно» — верно?"
        else if (guess != "")
            "Помечена «" + labelRu(s.label) + "», и признаки согласны. Верно?" + tail
        else
            "Помечена «" + labelRu(s.label) + "» — верно?")
    }

    /** Заметка об уверенности агента - честно показывает, почему спрашиваем.
     *  На корпусе доказано: уверенные ответы 100%, спорные агент не гадает.
     *  Порог margin взят из UNSURE_BAND агента (0.15) с запасом. */
    private fun confidenceNote(v: InclineAgent.Verdict, margin: Float): String = when (v) {
        InclineAgent.Verdict.UNSURE ->
            "🤔 Я на грани — по признакам почти поровну. Твой ответ тут ценнее всего."
        InclineAgent.Verdict.NO_BASIS ->
            "🌱 Признаков рядом пока мало — подскажи, буду точнее."
        InclineAgent.Verdict.UP, InclineAgent.Verdict.DOWN ->
            if (margin < 0.35f)
                "🤔 Похоже, но уверенность средняя — потому и спрашиваю."
            else
                "✓ По признакам уверенно. Проверь, не ошибся ли я."
    }

    private fun showInclineDialog(s: SessionRecord, guess: String, headView: View) {
        // Агент не согласен с меткой -> спрашиваем, что было на самом деле.
        if (guess != "" && guess != s.label) {
            // Агент не согласен с меткой. Спрашиваем правду и СОХРАНЯЕМ её:
            // раньше правильная метка терялась, оставался только "дефект".
            askTruth(s, guess, headView)
            return
        }
        AlertDialog.Builder(this)
            .setCustomTitle(headView)
            .setPositiveButton("Да") { _, _ ->
                if (guess != "") scoreAgent(guess, s.label)
                recordAnswer(s, 1, "подтверждено")
            }
            .setNegativeButton("Нет") { _, _ ->
                // v248: "нет" без продолжения терял правду. Спрашиваем,
                // что было на самом деле, и записываем это.
                askTruth(s, guess, null)
            }
            .setNeutralButton("🔍 Разобрать") { _, _ -> openSplit(s) }
            .show()
    }

    /** Счёт точности агента - отдельно по направлениям (общий процент прячет
     *  "в гору отлично, с горы мимо"). Считаем только когда известна правда. */
    private fun scoreAgent(guess: String, truth: String) {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val kk = "ia_" + truth.lowercase() + "_ok"
        val nk = "ia_" + truth.lowercase() + "_n"
        val ok = prefs.getInt(kk, 0) + (if (guess == truth) 1 else 0)
        val n = prefs.getInt(nk, 0) + 1
        prefs.edit().putInt(kk, ok).putInt(nk, n).apply()
    }

    /** Кандидат на вопрос. Уклон в приоритете (он в дефиците), но каждый
     *  третий вопрос - про неуклонную сессию, иначе "ровно" никогда не станет
     *  подтверждённым классом. */
    /** Активное обучение: среди свежих неспрошенных уклонных выбираем ту, где
     *  агент СОМНЕВАЕТСЯ (наименьший margin) - такой вопрос учит быстрее. Пока
     *  у агента нет базы (<AGENT_BASE_MIN подтверждённых), margin - шум, и мы
     *  берём просто самую свежую. */
    private suspend fun mostUncertainIncline(dao: StepDao): SessionRecord? {
        val base = dao.countInclineConfirmed()
        if (base < AGENT_BASE_MIN) return dao.latestUnaskedIncline()
        val window = dao.unaskedInclineWindow(UNCERTAIN_WINDOW)
        if (window.isEmpty()) return null
        if (window.size == 1) return window[0]
        var best: SessionRecord? = null
        var bestMargin = Float.MAX_VALUE
        for (cand in window) {
            val near = dao.sessionsAround(
                cand.startMs - InclineAgent.WALK_GAP_MS,
                cand.startMs + InclineAgent.WALK_GAP_MS)
            val r = InclineAgent.predict(
                InclineAgent.Input(cand.startMs, cand.chipShare, cand.ampMed ?: 0f),
                near.map { InclineAgent.Input(it.startMs, it.chipShare, it.ampMed ?: 0f) })
            // UNSURE/NO_BASIS - самые ценные: margin 0. Иначе меньший margin
            // = ближе к границе = спорнее.
            val m = if (r.verdict == InclineAgent.Verdict.UNSURE ||
                        r.verdict == InclineAgent.Verdict.NO_BASIS) 0f else r.margin
            if (m < bestMargin) { bestMargin = m; best = cand }
        }
        return best ?: window[0]
    }

    private suspend fun nextCandidate(dao: StepDao): SessionRecord? {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val n = prefs.getInt(KEY_ASK_N, 0)
        val flatTurn = (n % 3) == 2
        val s = if (flatTurn) dao.latestUnaskedFlat() ?: mostUncertainIncline(dao)
                else mostUncertainIncline(dao) ?: dao.latestUnaskedFlat()
        if (s != null) prefs.edit().putInt(KEY_ASK_N, n + 1).apply()
        return s
    }

    /** Открыть экран разбора по шагам: там можно увидеть точку разлома,
     *  объяснение словами и разрезать сессию на две с разными метками. */
    private fun openSplit(s: SessionRecord) {
        val i = android.content.Intent(this, SplitActivity::class.java)
        i.putExtra("startMs", s.startMs)
        i.putExtra("endMs", s.endMs)
        startActivity(i)
    }

    /** СЛЕПОЙ вопрос: человек не видит вердикта агента. Предсказание
     *  уже сделано и спрятано; после ответа сверяем. Это единственная
     *  честная мера правоты - подсказанный вопрос меряет согласие. */
    private fun blindAsk(s: SessionRecord, hidden: String, headView: View) {
        val opts = arrayOf("В гору", "Ровно", "С горы", "Не помню")
        val codes = arrayOf("UP", "FLAT", "DOWN", "")
        AlertDialog.Builder(this)
            .setCustomTitle(headView)
            .setItems(opts) { _, which ->
                val truth = codes[which]
                if (truth == "") { recordAnswer(s, 3, "не подтверждено (слепой)"); return@setItems }
                scoreBlind(hidden, truth)
                scoreAgent(hidden, truth)
                lifecycleScope.launch {
                    val dao = AppDb.get(this@SynxActivity).dao()
                    if (truth == s.label) dao.setSessionConfirm(s.id, 1)
                    else dao.setUserLabel(s.id, truth)
                    val hit = if (hidden == truth) "угадал" else "промах"
                    journal("SYNX слепая проверка: человек «" + labelRu(truth) +
                        "», агент «" + labelRu(hidden) + "» — " + hit +
                        " (" + anchor(s) + ")")
                    askedInVisit++
                    if (askedInVisit < MAX_ASK_PER_VISIT) {
                        val next = nextCandidate(dao)
                        if (next != null) { offerNext(next); return@launch }
                    }
                    Toast.makeText(this@SynxActivity,
                        if (hidden == truth) "Совпало — агент был прав"
                        else "Не совпало — это ценнее всего",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Слепой счёт ведётся отдельно: только он даёт право на автометку. */
    private fun scoreBlind(guess: String, truth: String) {
        if (truth != "UP" && truth != "DOWN") return
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val kk = "ia_b" + truth.lowercase() + "_ok"
        val nk = "ia_b" + truth.lowercase() + "_n"
        prefs.edit()
            .putInt(kk, prefs.getInt(kk, 0) + (if (guess == truth) 1 else 0))
            .putInt(nk, prefs.getInt(nk, 0) + 1)
            .apply()
    }

    /** Спрашивает, каким был уклон на самом деле, и СОХРАНЯЕТ ответ.
     *  Исходная метка не переписывается (прошлое неизменно) - правда
     *  ложится рядом в userLabel и становится истиной для показа и
     *  обучения. Это то же правило, что у ручной правки (v218). */
    private fun askTruth(s: SessionRecord, guess: String, headView: View?) {
        val opts = arrayOf("В гору", "Ровно", "С горы", "Не помню")
        val codes = arrayOf("UP", "FLAT", "DOWN", "")
        val b = AlertDialog.Builder(this)
        if (headView != null) b.setCustomTitle(headView)
        else b.setTitle("А что было на самом деле?")
        b.setItems(opts) { _, which ->
            val truth = codes[which]
            if (truth == "") { recordAnswer(s, 3, "не подтверждено"); return@setItems }
            if (guess != "") scoreAgent(guess, truth)
            lifecycleScope.launch {
                val dao = AppDb.get(this@SynxActivity).dao()
                if (truth == s.label) {
                    dao.setSessionConfirm(s.id, 1)
                    journal("SYNX уклон «" + labelRu(s.label) + "»: подтверждено (" +
                        anchor(s) + ")")
                } else {
                    // Правка: исходная метка остаётся фактом нажатия,
                    // правда живёт рядом.
                    dao.setUserLabel(s.id, truth)
                    journal("SYNX уклон: было «" + labelRu(s.label) + "», человек " +
                        "поправил на «" + labelRu(truth) + "» (" + anchor(s) + ")")
                }
                askedInVisit++
                if (askedInVisit < MAX_ASK_PER_VISIT) {
                    val next = nextCandidate(dao)
                    if (next != null) { offerNext(next); return@launch }
                }
                Toast.makeText(this@SynxActivity,
                    if (truth == s.label) "Записал, спасибо"
                    else "Поправил на «" + labelRu(truth) + "», спасибо",
                    Toast.LENGTH_SHORT).show()
            }
        }
        b.show()
    }

    private fun recordAnswer(s: SessionRecord, state: Int, word: String) {
        lifecycleScope.launch {
            val dao = AppDb.get(this@SynxActivity).dao()
            dao.setSessionConfirm(s.id, state)
            journal("SYNX уклон «" + labelRu(s.label) + "»: " + word + " (" + anchor(s) + ")")
            // Человек уже в потоке - предлагаем следующую сразу, не заставляя
            // выходить и заходить. Потолок бережёт внимание: устал - закрыл.
            askedInVisit++
            if (askedInVisit < MAX_ASK_PER_VISIT) {
                val next = nextCandidate(dao)
                if (next != null) { offerNext(next); return@launch }
            }
            Toast.makeText(
                this@SynxActivity, "Записал, спасибо", Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Человек решает сам, продолжать ли. Молчаливое авто-продолжение
     *  превращает помощь в назойливость. */
    private fun offerNext(next: SessionRecord) {
        AlertDialog.Builder(this)
            .setTitle("Записал, спасибо")
            .setMessage("Есть ещё одна прогулка:\n" + anchor(next))
            .setPositiveButton("Ещё вопрос") { _, _ -> askMode(next) }
            .setNegativeButton("Хватит", null)
            .setNeutralButton("Не беспокоить") { _, _ -> askSnooze() }
            .show()
    }

    private fun askSnooze() {
        val groups = arrayOf("Минуты", "Часы", "Дни", "Выключить обучение")
        AlertDialog.Builder(this)
            .setCustomTitle(dialogTitle("Не беспокоить\n\nВопросы вернутся сами. " +
                "Раньше срока — переключи тумблер обучения выкл/вкл."))
            .setItems(groups) { _, which ->
                when (which) {
                    0 -> snoozePick("Минуты", intArrayOf(5, 10, 15, 30, 60), 60_000L, "мин")
                    1 -> snoozePick("Часы", intArrayOf(1, 2, 4, 8, 16, 24), 3_600_000L, "ч")
                    2 -> snoozePick("Дни", intArrayOf(2, 4, 6, 8, 10), 86_400_000L, "дн")
                    else -> {
                        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
                        prefs.edit().putBoolean(KEY_LEARN, false).apply()
                        findViewById<SwitchCompat>(R.id.learnSwitch).isChecked = false
                        journal("SYNX: обучение выключено")
                        Toast.makeText(this, "Обучение выключено", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** Второй уровень: конкретный срок внутри выбранной единицы. */
    private fun snoozePick(unit: String, values: IntArray, mult: Long, suffix: String) {
        val names = Array(values.size) { values[it].toString() + " " + suffix }
        AlertDialog.Builder(this)
            .setCustomTitle(dialogTitle("Не беспокоить: " + unit.lowercase()))
            .setItems(names) { _, which ->
                val until = System.currentTimeMillis() + values[which] * mult
                getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
                    .edit().putLong(KEY_SNOOZE, until).apply()
                journal("SYNX: пауза опроса на " + names[which])
                Toast.makeText(this, "Пауза: " + names[which], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Заголовок-текст для диалогов СО СПИСКОМ.
     *  setMessage() и setItems() делят одну область: если задать оба, список
     *  не отрисуется и нажать будет нечего (баг v205). setCustomTitle живёт в
     *  своей области и со списком не конфликтует. */
    private fun dialogTitle(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(getColor(R.color.text_main))
        val pad = (16 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad + pad / 2, pad, pad + pad / 2, pad / 2)
        return tv
    }

    /** Шапка вопроса: профиль ТОГО ЖЕ дня с подсвеченным спрашиваемым
     *  отрезком плюс текст вопроса. Человек видит, где отрезок в дне и что
     *  было до и после - вспомнить проще, чем по одному времени.
     *  Живёт в setCustomTitle: своя область, со списком и кнопками не спорит. */
    private suspend fun dayHeader(s: SessionRecord, text: String): View {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (16 * d).toInt()
        box.setPadding(pad, pad, pad, (8 * d).toInt())

        // Сессии того же календарного дня, по порядку.
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val key = dayFmt.format(java.util.Date(s.startMs))
        val dao = AppDb.get(this).dao()
        val around = dao.sessionsAround(s.startMs - 86_400_000L, s.startMs + 86_400_000L)
            .filter { dayFmt.format(java.util.Date(it.startMs)) == key }
            .sortedBy { it.startMs }

        if (around.size >= 2) {
            val chart = DayProfileView(this)
            chart.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (120 * d).toInt())
            chart.setData(
                around.map {
                    DayProfileView.Seg(it.label, it.nSamples * 20, it.durationMs, it.startMs)
                },
                false
            )
            val idx = around.indexOfFirst { it.id == s.id }
            if (idx >= 0) chart.select(idx)
            box.addView(chart)
        }

        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(getColor(R.color.text_main))
        tv.setPadding(0, (10 * d).toInt(), 0, 0)
        box.addView(tv)
        return box
    }

    private fun journal(text: String) {
        lifecycleScope.launch {
            AppDb.get(this@SynxActivity).dao().addEvent(
                EventRecord(
                    timeMs = System.currentTimeMillis(),
                    date = java.time.LocalDate.now().toString(),
                    text = text
                )
            )
        }
    }

    /** Полный якорь для памяти: дата (день недели, число, месяц, год),
     *  интервал начало-конец, минуты, ~шаги и как нёс телефон (из доли чипа).
     *  Дистанции/маршрута в строке сессии нет - не выдумываем. */
    private fun anchor(s: SessionRecord): String {
        val ru = java.util.Locale("ru")
        val dfDate = java.text.SimpleDateFormat("EEE, d MMMM yyyy", ru)
        val dfTime = java.text.SimpleDateFormat("HH:mm", ru)
        val date = dfDate.format(java.util.Date(s.startMs))
        val from = dfTime.format(java.util.Date(s.startMs))
        val to = dfTime.format(java.util.Date(s.endMs))
        val mins = (s.durationMs / 60_000L).toInt()
        val steps = s.nSamples * 20
        // chipShare - доля образцов от чипа (карман). Пороги описательные,
        // не решают счёт: это зацепка памяти, а не измерение.
        val carry = when {
            s.chipShare >= 0.6f -> "телефон в основном в кармане"
            s.chipShare <= 0.4f -> "телефон в основном в руке"
            else -> "телефон то в руке, то в кармане"
        }
        return date + ", " + from + "–" + to +
            " (~" + mins + " мин, ~" + steps + " шаг., " + carry + ")"
    }

    private fun shortAnchor(s: SessionRecord): String {
        val dfTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
        return dfTime.format(java.util.Date(s.startMs)) + "–" +
            dfTime.format(java.util.Date(s.endMs))
    }

    private fun labelRu(l: String) = when (l) {
        "UP" -> "в гору"; "DOWN" -> "с горы"
        "NONE" -> "не отмечено"; else -> "ровно"
    }

    companion object {
        private const val KEY_LEARN = "learn_enabled"
        private const val KEY_ASK_N = "learn_ask_n"
        // v231. Активное обучение: окно свежих неспрошенных уклонных и
        // минимальная база подтверждённых, при которой margin осмыслен.
        private const val UNCERTAIN_WINDOW = 8
        private const val AGENT_BASE_MIN = 15
        // v244. Сколько сессий разбираем за один заход и счётчик для
        // контрольной доли (каждая пятая уходит человеку).
        private const val AUTO_BATCH = 40
        private const val KEY_ASK_COUNT = "ia_ask_count"
        /** Разрыв, после которого образцы считаются другим отрезком. */
        private const val SEG_GAP_MS = 3 * 60 * 1000L
        /** Пары дальше этого не сравниваем: другая прогулка. */
        private const val PAIR_GAP_MS = 30 * 60 * 1000L
        private const val KEY_AUTO_SEEN = "auto_label_seen"
        private const val KEY_SNOOZE = "learn_snooze_until"
    }
}
