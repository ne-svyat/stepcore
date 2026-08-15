package com.vasil.stepcore

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Приложение целиком. Единственная забота - ЗАВОДИТЬ ОБЩИЙ ТАКТ АНИМАЦИИ.
 *
 * Почему это здесь, а не в экранах. Такт (BoilClock) считает живые экраны:
 * пока счётчик больше нуля - линия дышит, стало ноль - таймер честно
 * замирает и не будит процессор в фоне. Счёт вёлся вручную, в onStart и
 * onStop КАЖДОГО экрана, и пять экранов этот вызов не получили вовсе:
 * Профиль дня, Разрешения, Калибровка уклона, Отрезки и SYNX. На них
 * счётчик падал в ноль, и вся дудл-анимация замирала - контур, пульсация,
 * рамки, сцены. Симптом «где-то анимация не работает» имел ровно эту
 * причину.
 *
 * УРОК проекта в чистом виде: гарантия ставится в воронку, а не в
 * обработчики. Здесь воронка - жизненный цикл приложения: новый экран
 * физически не может её обойти, потому что для этого ему пришлось бы не
 * быть Activity.
 *
 * Прежние ручные вызовы в экранах оставлены намеренно: они парны
 * (onStart/onStop), поэтому дают ровно удвоение счётчика на своих
 * экранах и никогда не мешают ему дойти до нуля.
 */
class StepCoreApp : Application() {

    private val clockCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = BoilClock.screenStarted()
        override fun onActivityStopped(activity: Activity) = BoilClock.screenStopped()
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(clockCallbacks)
    }
}
