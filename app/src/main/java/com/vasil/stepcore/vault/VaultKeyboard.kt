package com.vasil.stepcore.vault

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
 * строкой в системных настройках, перестаёт быть тайником. Здесь это
 * обычная вьюха внутри экрана: снаружи её не существует.
 *
 * ПОРЯДОК ПЕРЕСОБИРАЕТСЯ
 * ----------------------
 * При каждом показе и после каждого неверного пароля. Второе не мелочь:
 * если за неудачной попыткой подсмотрели, повтор по той же раскладке
 * выдал бы пароль целиком. Зерно берётся из SecureRandom.
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
    private var revealed = false

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
        revealed = false
        applyReveal()
        newSeed()
        build()
    }

    /**
     * Новый порядок после неудачной попытки.
     *
     * У раскладок без перемешивания порядок и так постоянный - трогать
     * нечего, а лишняя перерисовка всегда чувствуется.
     */
    fun reshuffle() {
        if (!VaultKeys.isShuffling(layoutMode)) return
        newSeed()
        build()
    }

    private fun newSeed() {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        seed = v
    }

    private fun build() {
        removeAllViews()
        addView(pageTabs(), LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })

        for (row in VaultKeys.rows(page, layoutMode, upper, seed)) {
            val w = VaultKeys.widths(row, layoutMode, seed)
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((i, ch) in row.withIndex()) {
                line.addView(key(ch.toString(), w[i], tintOf(ch), KEY_CHAR) { type(ch) })
            }
            addView(line, LayoutParams(-1, -2).also { it.bottomMargin = dp(4) })
        }

        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (VaultKeys.hasCase(page)) {
            bottom.addView(key(if (upper) "▲" else "△", 1.3f, MOD_TINT, KEY_MOD) {
                upper = !upper
                build()
            })
        }
        // Пробел без надписи: подпись ничего не добавляет, а ширина и так
        // делает клавишу единственной узнаваемой на ощупь.
        bottom.addView(key(" ", 3.4f, CHAR_TINT, KEY_CHAR) { type(' ') })
        val back = key("⌫", 1.3f, MOD_TINT, KEY_MOD) { backspace() }
        // Долгое нажатие стирает всё: набрал половину не той раскладкой -
        // не надо жать двенадцать раз.
        back.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            target.text.clear()
            true
        }
        bottom.addView(back)
        addView(bottom, LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })

        val tail = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        // Показать пароль - решение человека, а не наше. На перемешанной
        // раскладке промахи часты, и проверить набранное важнее, чем
        // спрятать его от себя самого.
        tail.addView(key(if (revealed) "Скрыть" else "Показать", 1.7f, REVEAL_TINT, KEY_MOD) {
            revealed = !revealed
            applyReveal()
            build()
        })
        tail.addView(key("Системная", 2.0f, SYSTEM_TINT, KEY_MOD) { onSystem() })
        tail.addView(key("Готово", 1.7f, DONE_TINT, KEY_MOD) { onDone() })
        addView(tail, LayoutParams(-1, -2))
    }

    /**
     * Вкладки страниц вместо клавиши-карусели.
     *
     * Каруселью до знаков надо было жать три раза, и человек не видел,
     * что вообще есть. Вкладки: одно нажатие до любой страницы и видно,
     * где находишься.
     */
    private fun pageTabs(): View {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (p in 0 until VaultKeys.PAGE_COUNT) {
            val active = p == page
            val tint = if (active) 0xFFB9A6E8.toInt() else 0xFF7A7488.toInt()
            line.addView(key(VaultKeys.pageLabel(p), 1f, tint,
                if (active) KEY_TAB_ON else KEY_MOD) {
                if (p != page) {
                    page = p
                    upper = false
                    build()
                }
            })
        }
        return line
    }

    private fun tintOf(c: Char): Int = when (VaultKeys.classOf(c)) {
        VaultKeys.CLASS_VOWEL -> VOWEL_TINT
        VaultKeys.CLASS_SIGN -> SIGN_TINT
        VaultKeys.CLASS_DIGIT -> DIGIT_TINT
        VaultKeys.CLASS_SYMBOL -> SYMBOL_TINT
        else -> CHAR_TINT
    }

    private fun applyReveal() {
        val at = target.selectionEnd.coerceAtLeast(0)
        target.transformationMethod =
            if (revealed) null else PasswordTransformationMethod.getInstance()
        // Подмена способа показа сбрасывает курсор в начало, и следующая
        // буква уехала бы в начало пароля.
        target.setSelection(at.coerceAtMost(target.text.length))
    }

    private fun type(c: Char) {
        target.text.insert(target.selectionEnd.coerceAtLeast(0), c.toString())
    }

    private fun backspace() {
        val e = target.text
        val at = target.selectionEnd.coerceAtLeast(0)
        if (at > 0) e.delete(at - 1, at)
    }

    private fun key(label: String, weight: Float, tint: Int, kind: Int,
                    action: () -> Unit): View {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(when (kind) {
                KEY_CHAR -> 0xFF1E1E26.toInt()
                KEY_TAB_ON -> 0xFF241E33.toInt()
                else -> 0xFF17171C.toInt()
            })
            // Рамка тем же тоном, что и буква, но приглушённая: группа
            // читается и по надписи, и по очертанию клавиши.
            setStroke(dp(1), (tint and 0xFFFFFF) or 0x55000000.toInt())
        }
        val t = TextView(context).apply {
            text = label
            textSize = if (label.length > 1) 12f else 17f
            gravity = Gravity.CENTER
            isSingleLine = true
            setTextColor(tint)
            minHeight = dp(44)   // палец, а не курсор
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
            isClickable = true
            setOnClickListener {
                // Отдача на касание: когда клавиши переставлены, без неё
                // непонятно, попал ли палец вообще.
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
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
        private const val KEY_TAB_ON = 2

        // Тона из семейства модуля: приглушённые, ни одного яркого.
        private const val CHAR_TINT = 0xFFCFCFDA.toInt()      // согласные
        private const val VOWEL_TINT = 0xFFE0C08A.toInt()     // гласные
        private const val SIGN_TINT = 0xFF8FC4D8.toInt()      // ъ и ь
        private const val DIGIT_TINT = 0xFF9FD9A8.toInt()     // цифры
        private const val SYMBOL_TINT = 0xFFC8A6D8.toInt()    // знаки
        private const val MOD_TINT = 0xFF9A94A8.toInt()
        private const val SYSTEM_TINT = 0xFFE0C08A.toInt()
        private const val DONE_TINT = 0xFFB9A6E8.toInt()
        private const val REVEAL_TINT = 0xFF8FC4D8.toInt()
    }
}
