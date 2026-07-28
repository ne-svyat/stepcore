package com.vasil.stepcore

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Проверка того, что система не перекрыла приложению кислород.
 *
 * Читаем только штатным API: догадок не строим. То, что через API не
 * читается (автозапуск и фирменные ограничения HyperOS), в проверки не
 * попадает - о нём говорим текстом, а не выдаём предположение за факт.
 */
object SystemHealth {

    data class Item(
        val title: String,
        val ok: Boolean,
        val why: String,
        val fix: Intent?
    )

    fun items(c: Context): List<Item> {
        val out = ArrayList<Item>()
        val nm = c.getSystemService(NotificationManager::class.java)

        val notifOn = nm.areNotificationsEnabled()
        out.add(Item(
            "Уведомления приложения",
            notifOn,
            if (notifOn) "включены"
            else "выключены — не будет ни счётчика, ни кнопок уклона",
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
        ))

        val ch = nm.getNotificationChannel(StepService.CHANNEL_MARKS)
        val chOk = ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
        out.add(Item(
            "Канал «Метки уклона»",
            chOk,
            if (ch == null) "появится после первого запуска счёта"
            else if (chOk) "разрешён"
            else "заблокирован — кнопки уклона не придут",
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, StepService.CHANNEL_MARKS)
        ))

        if (ch != null) {
            val lockOk = ch.lockscreenVisibility != android.app.Notification.VISIBILITY_SECRET
            out.add(Item(
                "Метки на экране блокировки",
                lockOk,
                if (lockOk) "показываются"
                else "скрыты — метку на ходу не поставить",
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, StepService.CHANNEL_MARKS)
            ))
        }

        val actOk = c.checkSelfPermission(
            android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        out.add(Item(
            "Распознавание активности",
            actOk,
            if (actOk) "разрешено" else "без него чип шагов недоступен",
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + c.packageName))
        ))

        val pm = c.getSystemService(PowerManager::class.java)
        val battOk = pm.isIgnoringBatteryOptimizations(c.packageName)
        out.add(Item(
            "Экономия батареи",
            battOk,
            if (battOk) "приложение не ограничено"
            else "система может усыплять счёт — шаги теряются",
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ))

        return out
    }

    /** Сколько пунктов не в порядке. Ноль - плиту не показываем. */
    fun problems(c: Context): Int = items(c).count { !it.ok }
}
