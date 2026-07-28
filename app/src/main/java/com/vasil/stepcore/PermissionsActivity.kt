package com.vasil.stepcore

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Что мешает StepCore работать. Каждый пункт ведёт на СВОЮ страницу
 *  системных настроек, а не в общий список - иначе искать вручную. */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var dens = 1f

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
    }

    override fun onResume() {
        super.onResume()
        render()   // вернулся из настроек - статусы пересчитываются
    }

    private fun render() {
        root.removeAllViews()
        val title = TextView(this)
        title.text = "Что мешает работать"
        title.textSize = 22f
        title.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        root.addView(title)

        val items = SystemHealth.items(this)
        val bad = items.count { !it.ok }
        val sub = TextView(this)
        sub.text = if (bad == 0) "Всё в порядке — система ничего не блокирует."
            else "Не в порядке: " + bad
        sub.textSize = 15f
        sub.setTextColor(ContextCompat.getColor(this,
            if (bad == 0) R.color.accent_teal else R.color.accent_amber))
        sub.setPadding(0, (8 * dens).toInt(), 0, 0)
        root.addView(sub)

        // Имя переменной НЕ "it": внутри setOnClickListener неявное it -
        // это View, и обращение к полям пункта не скомпилировалось бы.
        for (item in items) {
            val v = TextView(this)
            v.text = (if (item.ok) "✓  " else "!  ") + item.title + "\n" + item.why +
                (if (item.ok) "" else "\n\nНажми — откроется нужная страница настроек")
            v.textSize = 16f
            v.setTextColor(ContextCompat.getColor(this,
                if (item.ok) R.color.text_dim else R.color.text_main))
            val cp = (16 * dens).toInt()
            v.setPadding(cp, cp, cp, cp)
            v.background = DoodleBorderDrawable(
                ContextCompat.getColor(this,
                    if (item.ok) R.color.accent_teal else R.color.accent_amber),
                ContextCompat.getColor(this, R.color.surface),
                919L + item.title.length, dens,
                DoodleBorderDrawable.MAT_ROCK, DoodleBorderDrawable.RIFT_NONE)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (14 * dens).toInt()
            val fix = item.fix
            if (fix != null) {
                v.setOnClickListener {
                    runCatching { startActivity(fix) }
                        .onFailure {
                            android.widget.Toast.makeText(this,
                                "Эта страница недоступна на твоей прошивке",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                }
            }
            root.addView(v, lp)
        }

        val note = TextView(this)
        note.text = "Отдельно: в HyperOS есть свои ограничения — автозапуск и " +
            "поведение в фоне. Через программу их прочитать нельзя, поэтому " +
            "сюда они не попали. Если счёт всё равно прерывается, проверь их " +
            "вручную в настройках приложения."
        note.textSize = 14f
        note.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        note.setLineSpacing(3f * dens, 1f)
        note.setPadding(0, (20 * dens).toInt(), 0, 0)
        root.addView(note)

        val close = TextView(this)
        close.text = "Закрыть"
        close.gravity = Gravity.CENTER
        close.textSize = 16f
        close.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        close.setPadding(0, (18 * dens).toInt(), 0, (12 * dens).toInt())
        close.setOnClickListener { finish() }
        root.addView(close)
    }

}
