package com.vasil.stepcore

import android.content.Context
import android.graphics.Typeface
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
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textSize = 15f
        letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ); lp.topMargin = dp(c, 24); lp.bottomMargin = dp(c, 8); layoutParams = lp
    }

    fun dimText(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(c, R.color.text_dim))
        textSize = 13f
    }

    /** Карточка-значение: боковая полоса + заголовок + крупное значение + подпись. */
    fun statCard(c: Context, title: String, value: String, sub: String, accentRes: Int): View {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(c, R.drawable.card_asym)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ); lp.bottomMargin = dp(c, 10); layoutParams = lp
        }
        card.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(c, 4), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(ContextCompat.getColor(c, accentRes))
        })
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        }
        col.addView(TextView(c).apply {
            text = title.uppercase()
            setTextColor(ContextCompat.getColor(c, R.color.text_dim))
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            textSize = 12f; letterSpacing = 0.05f
        })
        col.addView(TextView(c).apply {
            text = value
            setTextColor(ContextCompat.getColor(c, R.color.text_main))
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = 24f
        })
        if (sub.isNotEmpty()) col.addView(TextView(c).apply {
            text = sub
            setTextColor(ContextCompat.getColor(c, R.color.text_dim))
            textSize = 12f
        })
        card.addView(col)
        return card
    }
}
