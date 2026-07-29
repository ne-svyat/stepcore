package com.vasil.stepcore

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Общие элементы дизайна для экранов (карточки, заголовки). */
object UiKit {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    // --- V272 «Каркас»: один акцент на экран ---
    // Смысл, а не вкус. До v272 каждый экран красился как придётся
    // (Калибровка синий+фиолетовый, Уклон янтарный+синий+зелёный,
    // заголовки секций красные везде) - отсюда ощущение разнобоя.
    // Правило: экран выбирает акцент ОДИН раз, и его берут шапка,
    // заголовки секций и карточки этого экрана.
    val ACCENT_HERO    = R.color.accent_red     // главный экран, герой
    val ACCENT_MEASURE = R.color.accent_blue    // измерение (калибровки)
    val ACCENT_DATA    = R.color.accent_teal    // данные (аналитика, история)
    val ACCENT_TIME    = R.color.accent_violet  // время (timeline, карта дня)
    val ACCENT_LEARN   = R.color.accent_amber   // обучение (SYNX, разрезка)
    val ACCENT_INPUT   = R.color.accent_green   // ввод/действие (профиль)

    /**
     * Единая шапка экрана. Размер и трекинг заданы здесь, а не в разметке,
     * чтобы новый экран не мог завести свой 31sp или 42sp.
     */
    fun screenTitle(a: android.app.Activity, accentRes: Int) {
        val t = a.findViewById<TextView>(R.id.screenTitle) ?: return
        t.setTextColor(ContextCompat.getColor(a, accentRes))
        t.textSize = TITLE_SP
        t.letterSpacing = 0.05f
    }

    private const val TITLE_SP = 33f

    fun sectionTitle(c: Context, text: String, accentRes: Int = R.color.accent_red): TextView = TextView(c).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(c, accentRes))
        textSize = 19f
        letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ); lp.topMargin = dp(c, 24); lp.bottomMargin = dp(c, 8); layoutParams = lp
    }

    fun dimText(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(c, R.color.text_dim))
        textSize = 16f
    }

    /** Карточка-значение: боковая полоса + заголовок + крупное значение + подпись. */
    fun statCard(c: Context, title: String, value: String, sub: String, accentRes: Int): View {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            // V14.3: рамка "от руки" вместо ровного shape. Сид от заголовка
            // -> у каждой карточки своя кривизна, но стабильная между
            // перерисовками (одинаковая кривизна выдала бы машину).
            background = DoodleBorderDrawable(
                ContextCompat.getColor(c, accentRes),
                ContextCompat.getColor(c, R.color.surface),
                title.hashCode().toLong(),
                c.resources.displayMetrics.density,
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ); lp.bottomMargin = dp(c, 10); layoutParams = lp
        }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        }
        col.addView(TextView(c).apply {
            text = title.uppercase()
            setTextColor(ContextCompat.getColor(c, R.color.text_dim))
            textSize = 15f; letterSpacing = 0.05f
        })
        col.addView(TextView(c).apply {
            text = value
            setTextColor(ContextCompat.getColor(c, R.color.text_main))
            textSize = 30f
        })
        if (sub.isNotEmpty()) col.addView(TextView(c).apply {
            text = sub
            setTextColor(ContextCompat.getColor(c, R.color.text_dim))
            textSize = 15f
        })
        card.addView(col)
        return card
    }
}
