package com.vasil.stepcore

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Окно-объяснение в языке приложения.
 *
 * Зачем. Системный AlertDialog - серый прямоугольник со скруглением: он
 * принадлежит Android, а не StepCore. Экран вокруг живёт (плиты дышат,
 * кант горит, сцена движется), а объяснение калорий выглядело как
 * системное сообщение об ошибке. Разрыв языка читается как дешевизна,
 * даже если текст хороший.
 *
 * Как устроено. Никакой новой отрисовки не изобретено: окно - это та же
 * плита DoodleBorderDrawable, что и все карточки. Значит оно получает
 * ровно те же свойства: резной кант, фактуру материала, трещину, пыль,
 * наливание снизу вверх при открытии и бегущий по кромке огонёк.
 * Одна вещь - один механизм.
 *
 * Содержимое прокручивается, поэтому длинный текст (калории) не
 * обрезается на маленьком экране.
 */
object DoodleDialog {

    /** Потолок высоты прокрутки, пиксели. Ставится перед сборкой окна. */
    private var scrollMax = 0

    fun info(
        ctx: Context,
        title: String,
        body: String,
        okText: String = "Понятно",
        strokeRes: Int = R.color.accent_amber,
        fillRes: Int = R.color.surface_amber,
        material: Int = DoodleBorderDrawable.MAT_LIGHTNING,
    ): Dialog {
        val d = ctx.resources.displayMetrics.density
        val stroke = ContextCompat.getColor(ctx, strokeRes)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (13 * d).toInt())
        DoodleUi.frame(root, strokeRes, fillRes, title.hashCode().toLong(), material)

        val head = TextView(ctx)
        head.text = title
        head.textSize = 19f
        head.setTextColor(stroke)
        head.typeface = Typeface.DEFAULT_BOLD
        root.addView(head)

        // Черта под заголовком в тоне плиты: заголовок и текст - разные
        // вещи, и глаз не должен их склеивать.
        val rule = View(ctx)
        val ruleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (1.5f * d).toInt())
        ruleLp.topMargin = (7 * d).toInt()
        ruleLp.bottomMargin = (9 * d).toInt()
        rule.layoutParams = ruleLp
        rule.setBackgroundColor(stroke)
        rule.alpha = 0.55f
        root.addView(rule)

        val text = TextView(ctx)
        text.text = body
        text.textSize = 16f
        text.setTextColor(ContextCompat.getColor(ctx, R.color.text_main))
        text.setLineSpacing(3f * d, 1.03f)
        // Высота окна ограничена: длинный текст (калории) прокручивается,
        // а не выталкивает кнопку за край экрана.
        scrollMax = (ctx.resources.displayMetrics.heightPixels * 0.62f).toInt()
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(
                    scrollMax, MeasureSpec.AT_MOST))
            }
        }
        scroll.isVerticalScrollBarEnabled = true
        scroll.addView(text)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val btn = Button(ctx)
        btn.text = okText
        btn.setAllCaps(false)
        btn.textSize = 15f
        btn.setTextColor(ContextCompat.getColor(ctx, R.color.text_main))
        btn.minWidth = 0; btn.minimumWidth = 0
        btn.minHeight = 0; btn.minimumHeight = 0
        btn.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        DoodleUi.frame(btn, strokeRes, fillRes, title.hashCode() * 31L + 7L,
            DoodleBorderDrawable.MAT_ROCK)
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        btnLp.topMargin = (13 * d).toInt()
        btnLp.gravity = Gravity.END
        btn.layoutParams = btnLp
        root.addView(btn)

        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Экран под окном ГАСИТСЯ. Без этого пёстрый главный экран
        // продолжал спорить с текстом, и окно читалось как прозрачное,
        // даже когда плита под ним была плотной.
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dlg.window?.attributes = dlg.window?.attributes?.apply { dimAmount = 0.78f }
        dlg.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.93f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        btn.setOnClickListener { dlg.dismiss() }
        dlg.show()

        // Плита наливается сама (её механизм), окно поднимается ей
        // навстречу: движение одно, а не два разных.
        // Прозрачность больше НЕ гасится в ноль. Прежде окно всплывало
        // из невидимости 300 мс, а плита внутри зажигалась своим ходом -
        // два движения подряд, и текст появлялся последним. Теперь окно
        // видно с первого кадра, а движение остаётся только как короткий
        // подъём: одно движение вместо двух, и ничего не ждёшь.
        root.alpha = 0.55f
        root.translationY = 10 * d
        root.animate().alpha(1f).translationY(0f).setDuration(170).start()
        return dlg
    }
}
