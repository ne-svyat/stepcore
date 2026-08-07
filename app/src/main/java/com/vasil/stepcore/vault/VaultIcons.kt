package com.vasil.stepcore.vault

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Иконки Vault. Рисуются кодом, не лежат картинками.
 *
 * ПОЧЕМУ НЕ ЭМОДЗИ
 * ----------------
 * Юникодные значки рисует шрифт системы. На разных прошивках они разного
 * веса, разного размера и разного настроения: где-то тонкая линия, где-то
 * цветная картинка. Тайник должен выглядеть одинаково у всех, и его тон -
 * приглушённый, а эмодзи почти всегда яркие.
 *
 * ПОЧЕМУ НЕ ФАЙЛЫ РЕСУРСОВ
 * ------------------------
 * Векторный ресурс - это XML на каждую иконку плюс имя, которое надо не
 * перепутать. Здесь всё в одном месте, цвет и толщина линии приходят
 * параметром, а размер задаётся в точке использования.
 *
 * СТИЛЬ ОДИН НА ВСЕ
 * -----------------
 * Тонкая линия, скруглённые концы, поле 24 единицы, отступ 4 по краям.
 * Ни одной заливки: заливка спорила бы с приглушённым тоном модуля.
 */
class VaultIcon(
    private val kind: Kind,
    private val color: Int,
    private val sizePx: Int,
    private val strokeRatio: Float = 0.085f,
) : Drawable() {

    enum class Kind {
        SEARCH, PLUS, ROOTS, CLOSE, PENCIL, EYE, HEADING,
        PREV, NEXT, PAGE_PLUS, IMAGE, TRAIL, LIST, HISTORY, TAG, JUMP
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        paint.color = color
        paint.strokeWidth = s * strokeRatio

        // Все фигуры описаны в поле 24x24 и масштабируются целиком:
        // тогда толщина линии и пропорции совпадают у всех иконок.
        val k = s / 24f
        val ox = b.left + (b.width() - s) / 2f
        val oy = b.top + (b.height() - s) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(k, k)
        path.reset()

        when (kind) {
            Kind.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint)
                path.moveTo(14.6f, 14.6f); path.lineTo(19.5f, 19.5f)
            }
            Kind.PLUS -> {
                path.moveTo(12f, 5f); path.lineTo(12f, 19f)
                path.moveTo(5f, 12f); path.lineTo(19f, 12f)
            }
            // Корни: три жилы разной длины и перемычка между двумя.
            Kind.ROOTS -> {
                path.moveTo(6f, 4f); path.lineTo(6f, 20f)
                path.moveTo(12f, 4f); path.lineTo(12f, 17f)
                path.moveTo(18f, 4f); path.lineTo(18f, 20f)
                path.moveTo(6f, 14f); path.quadTo(9f, 18f, 12f, 14f)
            }
            Kind.CLOSE -> {
                // Замок: дужка и тело. Закрытие тайника - не крестик.
                path.moveTo(8f, 10f); path.lineTo(8f, 7.5f)
                path.quadTo(8f, 4.5f, 12f, 4.5f)
                path.quadTo(16f, 4.5f, 16f, 7.5f)
                path.lineTo(16f, 10f)
                path.moveTo(5.5f, 10.5f); path.lineTo(18.5f, 10.5f)
                path.lineTo(18.5f, 19.5f); path.lineTo(5.5f, 19.5f)
                path.close()
            }
            Kind.PENCIL -> {
                path.moveTo(4.5f, 19.5f); path.lineTo(5.5f, 15.5f)
                path.lineTo(16f, 5f); path.lineTo(19f, 8f)
                path.lineTo(8.5f, 18.5f); path.close()
                path.moveTo(14f, 7f); path.lineTo(17f, 10f)
            }
            Kind.EYE -> {
                path.moveTo(3f, 12f)
                path.quadTo(12f, 4f, 21f, 12f)
                path.quadTo(12f, 20f, 3f, 12f)
                path.close()
                canvas.drawCircle(12f, 12f, 2.6f, paint)
            }
            Kind.PREV -> { path.moveTo(14.5f, 5f); path.lineTo(8f, 12f); path.lineTo(14.5f, 19f) }
            Kind.NEXT -> { path.moveTo(9.5f, 5f); path.lineTo(16f, 12f); path.lineTo(9.5f, 19f) }
            // Новая страница: лист с загнутым углом и плюсом.
            Kind.PAGE_PLUS -> {
                path.moveTo(5.5f, 3.5f); path.lineTo(13f, 3.5f); path.lineTo(18.5f, 9f)
                path.lineTo(18.5f, 20.5f); path.lineTo(5.5f, 20.5f); path.close()
                path.moveTo(13f, 3.5f); path.lineTo(13f, 9f); path.lineTo(18.5f, 9f)
                path.moveTo(12f, 12f); path.lineTo(12f, 17.5f)
                path.moveTo(9.2f, 14.7f); path.lineTo(14.8f, 14.7f)
            }
            Kind.IMAGE -> {
                path.moveTo(3.5f, 5f); path.lineTo(20.5f, 5f)
                path.lineTo(20.5f, 19f); path.lineTo(3.5f, 19f); path.close()
                path.moveTo(3.5f, 16f); path.lineTo(9f, 10.5f); path.lineTo(14f, 16f)
                path.moveTo(13f, 15f); path.lineTo(16f, 12f); path.lineTo(20.5f, 16.5f)
                canvas.drawCircle(15.5f, 9f, 1.6f, paint)
            }
            // Тропа: пунктирная дорожка между двумя точками.
            Kind.TRAIL -> {
                canvas.drawCircle(5.5f, 18.5f, 1.8f, paint)
                canvas.drawCircle(18.5f, 5.5f, 1.8f, paint)
                path.moveTo(7.5f, 16.5f); path.lineTo(9.5f, 14.5f)
                path.moveTo(11f, 13f); path.lineTo(13f, 11f)
                path.moveTo(14.5f, 9.5f); path.lineTo(16.5f, 7.5f)
            }
            Kind.LIST -> {
                path.moveTo(4f, 7f); path.lineTo(20f, 7f)
                path.moveTo(4f, 12f); path.lineTo(20f, 12f)
                path.moveTo(4f, 17f); path.lineTo(14f, 17f)
            }
            // История: часы со стрелкой назад.
            Kind.HISTORY -> {
                canvas.drawCircle(12f, 12.5f, 7.5f, paint)
                path.moveTo(12f, 8f); path.lineTo(12f, 12.5f); path.lineTo(15.5f, 14.5f)
                path.moveTo(4.5f, 8.5f); path.lineTo(4.5f, 4.5f)
                path.moveTo(4.5f, 8.5f); path.lineTo(8.5f, 8.5f)
            }
            // Заголовок: буква H со ступенькой сверху - уровень.
            Kind.HEADING -> {
                path.moveTo(5f, 6f); path.lineTo(5f, 19f)
                path.moveTo(13f, 6f); path.lineTo(13f, 19f)
                path.moveTo(5f, 12.5f); path.lineTo(13f, 12.5f)
                path.moveTo(16.5f, 9f); path.lineTo(19.5f, 6.5f)
                path.lineTo(19.5f, 13f)
                path.moveTo(17.5f, 13f); path.lineTo(21.5f, 13f)
            }
            Kind.TAG -> {
                path.moveTo(3.5f, 11f); path.lineTo(11f, 3.5f); path.lineTo(20.5f, 3.5f)
                path.lineTo(20.5f, 13f); path.lineTo(13f, 20.5f); path.close()
                canvas.drawCircle(16.5f, 7.5f, 1.5f, paint)
            }
            // Переход на страницу: стрелка в поле.
            Kind.JUMP -> {
                path.moveTo(4.5f, 4.5f); path.lineTo(4.5f, 19.5f); path.lineTo(19.5f, 19.5f)
                path.moveTo(9f, 15f); path.lineTo(19f, 5f)
                path.moveTo(13.5f, 5f); path.lineTo(19f, 5f); path.lineTo(19f, 10.5f)
            }
        }

        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
