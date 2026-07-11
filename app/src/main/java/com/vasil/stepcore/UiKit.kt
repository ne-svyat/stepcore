package com.vasil.stepcore

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Общие элементы дизайна для экранов (карточки, заголовки). */
object UiKit {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun sectionTitle(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(c, R.color.accent_red))
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
