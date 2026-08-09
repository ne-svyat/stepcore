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
    private val strokeRatio: Float = 0.065f,
) : Drawable() {

    companion object {
        /**
         * Свой цвет у каждой иконки.
         *
         * Одинаково серый набор читается медленнее: глаз ищет форму, а
         * форма на двадцати точках почти неразличима. Цвет опознаётся
         * боковым зрением раньше, чем очертание.
         *
         * Оттенки приглушённые и из одного семейства - тайник не должен
         * стать пёстрым. Родственные действия делят тон: обе стрелки
         * серые, все действия над текстом фиолетовые, опасное красное.
         */
        fun tintFor(kind: Kind): Int = when (kind) {
            Kind.SEARCH -> 0xFFB9A6E8.toInt()
            Kind.PLUS -> 0xFF9FD9A8.toInt()
            Kind.ROOTS -> 0xFF8FC4D8.toInt()
            Kind.CLOSE -> 0xFF9A94A8.toInt()
            Kind.PENCIL -> 0xFFB9A6E8.toInt()
            Kind.EYE -> 0xFF8FC4D8.toInt()
            Kind.HEADING -> 0xFFB9A6E8.toInt()
            Kind.TRASH -> 0xFFE08A94.toInt()
            Kind.PREV -> 0xFF9A94A8.toInt()
            Kind.NEXT -> 0xFF9A94A8.toInt()
            Kind.PAGE_PLUS -> 0xFF9FD9A8.toInt()
            Kind.IMAGE -> 0xFFE0C08A.toInt()
            Kind.TRAIL -> 0xFFC8A6D8.toInt()
            Kind.LIST -> 0xFF8FC4D8.toInt()
            Kind.HISTORY -> 0xFFE0C08A.toInt()
            Kind.TAG -> 0xFF9FD9A8.toInt()
            Kind.JUMP -> 0xFF8FC4D8.toInt()
            // Янтарный - тон предупреждения в модуле. Не красный: защита
            // это не опасное действие. Не синий: в нижнем ряду уже два
            // синих значка подряд, третий перестал бы опознаваться
            // боковым зрением.
            Kind.SHIELD -> 0xFFE0C08A.toInt()
        }
    }

    enum class Kind {
        SEARCH, PLUS, ROOTS, CLOSE, PENCIL, EYE, HEADING, TRASH,
        PREV, NEXT, PAGE_PLUS, IMAGE, TRAIL, LIST, HISTORY, TAG, JUMP,
        SHIELD
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
                // Дужка НАД телом, без касания: касание линий на 20dp
                // сливалось в пятно.
                path.moveTo(8f, 9.5f); path.lineTo(8f, 7.5f)
                path.quadTo(8f, 4.5f, 12f, 4.5f)
                path.quadTo(16f, 4.5f, 16f, 7.5f)
                path.lineTo(16f, 9.5f)
                path.moveTo(6f, 11f); path.lineTo(18f, 11f)
                path.lineTo(18f, 19.5f); path.lineTo(6f, 19.5f)
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
                // Лист без загнутого угла: угол на мелком размере
                // превращался в кляксу поверх плюса.
                path.moveTo(6f, 4f); path.lineTo(18f, 4f)
                path.lineTo(18f, 20f); path.lineTo(6f, 20f); path.close()
                path.moveTo(12f, 9f); path.lineTo(12f, 16f)
                path.moveTo(8.5f, 12.5f); path.lineTo(15.5f, 12.5f)
            }
            Kind.IMAGE -> {
                // Один силуэт горы вместо двух пересекающихся.
                path.moveTo(4f, 5f); path.lineTo(20f, 5f)
                path.lineTo(20f, 19f); path.lineTo(4f, 19f); path.close()
                path.moveTo(4f, 16.5f); path.lineTo(9.5f, 10.5f)
                path.lineTo(14f, 15f); path.lineTo(16.5f, 12.5f); path.lineTo(20f, 16.5f)
                canvas.drawCircle(15.5f, 8.5f, 1.4f, paint)
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
                // Стрелка возврата вынесена за круг, а не поверх него.
                canvas.drawCircle(13f, 13f, 6.5f, paint)
                path.moveTo(13f, 9.5f); path.lineTo(13f, 13f); path.lineTo(16f, 14.5f)
                path.moveTo(4f, 5f); path.lineTo(4f, 9f); path.lineTo(8f, 9f)
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
            // Удаление: корзина с крышкой. Единственная иконка, которую
            // человек обязан узнать НЕ читая подписи.
            Kind.TRASH -> {
                path.moveTo(4.5f, 6.5f); path.lineTo(19.5f, 6.5f)
                path.moveTo(9.5f, 6.5f); path.lineTo(9.5f, 4.5f)
                path.lineTo(14.5f, 4.5f); path.lineTo(14.5f, 6.5f)
                path.moveTo(7f, 9f); path.lineTo(8f, 20f)
                path.lineTo(16f, 20f); path.lineTo(17f, 9f)
            }
            Kind.TAG -> {
                path.moveTo(3.5f, 11f); path.lineTo(11f, 3.5f); path.lineTo(20.5f, 3.5f)
                path.lineTo(20.5f, 13f); path.lineTo(13f, 20.5f); path.close()
                canvas.drawCircle(16.5f, 7.5f, 1.5f, paint)
            }
            /**
             * Щит: плоский верх, прямые грани, острый низ.
             *
             * Округлый низ на двадцати точках превращал фигуру в
             * шестиугольник, а острый ВЕРХ - в каплю. Узнаваемость держится
             * ровно на двух чертах: горизонтальная кромка сверху и сход в
             * точку снизу. Кривых нет вовсе - на этом размере они дают
             * ступеньки, а не скругление.
             *
             * Внутреннего знака (замочной скважины, галочки) нет намеренно:
             * мелкая деталь внутри контура сливается в пятно, и это уже
             * дважды проверено на загнутом углу листа и на круге в горе.
             */
            Kind.SHIELD -> {
                path.moveTo(5.5f, 4.5f); path.lineTo(18.5f, 4.5f)
                path.lineTo(18.5f, 11.5f); path.lineTo(12f, 20.5f)
                path.lineTo(5.5f, 11.5f); path.close()
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
