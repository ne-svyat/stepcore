package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Картинка на весь экран: щипок, двойной тап, перетаскивание.
 *
 * ПОЧЕМУ СВОЯ ВЬЮХА, А НЕ БИБЛИОТЕКА
 * ----------------------------------
 * Готовые PhotoView тянут зависимость ради двух жестов. Здесь всё
 * укладывается в одну матрицу и два детектора из системы: ноль
 * зависимостей, ноль лишнего кода в APK.
 *
 * ГРАНИЦЫ ДЕРЖАТСЯ ЖЁСТКО
 * -----------------------
 * После любого жеста картинка возвращается в разрешённые пределы: не
 * мельче вписанного размера и не уезжает за край. Без этого палец
 * однажды уводит изображение в пустоту, и человек думает, что оно
 * пропало.
 */
class VaultZoomView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val values = FloatArray(9)

    /** Масштаб «вписано в экран». Считается при первой отрисовке. */
    private var fitScale = 1f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent,
                                  dx: Float, dy: Float): Boolean {
                matrix.postTranslate(-dx, -dy)
                clamp()
                invalidate()
                return true
            }

            /** Двойной тап: приблизить к точке, а из приближённого — назад. */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val cur = currentScale()
                if (cur > fitScale * 1.3f) reset()
                else zoomBy(fitScale * 3f / cur, e.x, e.y)
                invalidate()
                return true
            }

            override fun onDown(e: MotionEvent) = true
        })

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        reset()
    }

    /** Вписать целиком и поставить по центру. */
    fun reset() {
        val b = bitmap ?: return
        if (width == 0 || height == 0 || b.width == 0 || b.height == 0) return
        fitScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        matrix.reset()
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate(
            (width - b.width * fitScale) / 2f,
            (height - b.height * fitScale) / 2f
        )
        invalidate()
    }

    private fun currentScale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val cur = currentScale()
        // Пределы: мельче вписанного не даём совсем, крупнее восьмикратного
        // смысла нет — дальше видны только квадраты сжатия.
        val target = (cur * factor).coerceIn(fitScale, fitScale * 8f)
        val real = if (cur == 0f) 1f else target / cur
        matrix.postScale(real, real, fx, fy)
        clamp()
        invalidate()
    }

    /** Вернуть картинку в разрешённые пределы после жеста. */
    private fun clamp() {
        val b = bitmap ?: return
        matrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val w = b.width * scale
        val h = b.height * scale
        var dx = 0f
        var dy = 0f
        val tx = values[Matrix.MTRANS_X]
        val ty = values[Matrix.MTRANS_Y]
        // Уже экрана — держим по центру. Шире — не пускаем края внутрь.
        dx = if (w <= width) (width - w) / 2f - tx
             else tx.coerceIn(width - w, 0f) - tx
        dy = if (h <= height) (height - h) / 2f - ty
             else ty.coerceIn(height - h, 0f) - ty
        matrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // Пока картинка увеличена, прокрутка экрана не должна перехватывать
        // палец: иначе двигать изображение невозможно.
        parent?.requestDisallowInterceptTouchEvent(currentScale() > fitScale * 1.02f)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        canvas.drawBitmap(b, matrix, paint)
    }
}
