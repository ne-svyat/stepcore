package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.SecureRandom

/**
 * Клавиатура тайника: своя View, а не системный метод ввода.
 *
 * ПОЧЕМУ НЕ IME
 * -------------
 * Свой метод ввода - отдельное приложение в системе, видное в настройках
 * и доступное всем полям на телефоне. Тайник, который заявляет о себе
 * строкой в системных настройках, перестаёт быть тайником. Здесь же это
 * обычная вьюха внутри экрана: снаружи её не существует.
 *
 * ПОРЯДОК ПЕРЕСОБИРАЕТСЯ КАЖДЫЙ РАЗ
 * ---------------------------------
 * Запомненный порядок - это просто вторая раскладка, к которой палец
 * привыкает так же, как к первой. Зерно берётся из SecureRandom при
 * каждом показе.
 *
 * ВОЗВРАТ К СИСТЕМНОЙ - ВСЕГДА
 * ----------------------------
 * Отдельная клавиша, которую нельзя отключить настройкой. Если в наборе
 * символов однажды не окажется чьего-то знака, человек должен иметь
 * выход: ключа нет нигде, и запертый тайник не открыть ничем.
 */
class VaultKeyboard(
    context: Context,
    private val target: EditText,
    private val onSystem: () -> Unit,
    private val onDone: () -> Unit,
) : LinearLayout(context) {

    private var layoutMode = VaultKeys.LAYOUT_NORMAL
    private var page = VaultKeys.PAGE_LAT
    private var upper = false
    private var seed = 0L

    init {
        orientation = VERTICAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0xFF121218.toInt())
            setStroke(dp(1), 0xFF2E2A3A.toInt())
        }
    }

    fun show(mode: Int) {
        layoutMode = mode
        page = VaultKeys.PAGE_LAT
        upper = false
        newSeed()
        build()
    }

    /** Новый порядок. Зерно системное: предсказуемый порядок бесполезен. */
    private fun newSeed() {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        seed = v
    }

    private fun build() {
        removeAllViews()
        for (row in VaultKeys.rows(page, layoutMode, upper, seed)) {
            val w = VaultKeys.widths(row, layoutMode, seed)
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((i, ch) in row.withIndex()) {
                line.addView(key(ch.toString(), w[i], KEY_CHAR) { type(ch) })
            }
            addView(line, LayoutParams(-1, -2).also { it.bottomMargin = dp(4) })
        }

        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (VaultKeys.hasCase(page)) {
            bottom.addView(key(if (upper) "▲" else "△", 1.3f, KEY_MOD) {
                upper = !upper; build()
            })
        }
        bottom.addView(key(nextPageLabel(), 1.5f, KEY_MOD) {
            page = (page + 1) % VaultKeys.PAGE_COUNT
            upper = false
            build()
        })
        // Пробел без надписи: подпись на нём ничего не добавляет, а ширина
        // и так делает клавишу единственной узнаваемой на ощупь.
        bottom.addView(key(" ", 3f, KEY_CHAR) { type(' ') })
        bottom.addView(key("⌫", 1.3f, KEY_MOD) { backspace() })
        addView(bottom, LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })

        val tail = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        tail.addView(key("Системная", 2.6f, KEY_SYSTEM) { onSystem() })
        tail.addView(key("Готово", 1.6f, KEY_DONE) { onDone() })
        addView(tail, LayoutParams(-1, -2))
    }

    private fun nextPageLabel(): String =
        VaultKeys.pageLabel((page + 1) % VaultKeys.PAGE_COUNT)

    private fun type(c: Char) {
        target.text.insert(target.selectionEnd.coerceAtLeast(0), c.toString())
    }

    private fun backspace() {
        val e = target.text
        val at = target.selectionEnd.coerceAtLeast(0)
        if (at > 0) e.delete(at - 1, at)
    }

    private fun key(label: String, weight: Float, kind: Int, action: () -> Unit): View {
        val tint = when (kind) {
            KEY_MOD -> 0xFF9A94A8.toInt()
            KEY_SYSTEM -> 0xFFE0C08A.toInt()
            KEY_DONE -> 0xFFB9A6E8.toInt()
            else -> 0xFFEEEEEE.toInt()
        }
        val bg = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(if (kind == KEY_CHAR) 0xFF1E1E26.toInt() else 0xFF17171C.toInt())
            setStroke(dp(1), if (kind == KEY_CHAR) 0xFF2E2A3A.toInt()
                else (tint and 0xFFFFFF) or 0x66000000.toInt())
        }
        val t = TextView(context).apply {
            text = label
            textSize = if (label.length > 1) 12f else 17f
            gravity = Gravity.CENTER
            isSingleLine = true
            setTextColor(tint)
            // Палец, а не курсор: клавиша ниже 44 точек промахивается.
            minHeight = dp(44)
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), bg, null
            )
            isClickable = true
            setOnClickListener { action() }
        }
        t.layoutParams = LayoutParams(0, dp(44), weight).also {
            it.marginStart = dp(2); it.marginEnd = dp(2)
        }
        return t
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_CHAR = 0
        private const val KEY_MOD = 1
        private const val KEY_SYSTEM = 2
        private const val KEY_DONE = 3
    }
}
