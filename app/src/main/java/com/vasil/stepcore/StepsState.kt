package com.vasil.stepcore

import kotlinx.coroutines.flow.MutableStateFlow

object StepsState {
    val steps = MutableStateFlow(0)
    val serviceRunning = MutableStateFlow(false)
    val hapticEnabled = MutableStateFlow(false)
    val mode = MutableStateFlow("IDLE")
    val calibrationState = MutableStateFlow("")
    val diag = MutableStateFlow("")
    val detailLog = MutableStateFlow(false)

    /** v391. Журнал решений: события в МОМЕНТ принятия, а не снимок по
     *  таймеру. Работает при погашенном экране — этим и отличается от
     *  detailLog, который при screenOff молчит намеренно. */
    val decisionLog = MutableStateFlow(false)

    /** v391. Признаки походки раз в 10 с: автокорреляция, фаза полёта,
     *  время контакта. Только измерение, на счёт не влияет. */
    val gaitLog = MutableStateFlow(false)

    /** v391. Сырьё вокруг события: окно отсчётов рядом с решением.
     *  Бюджет ограничен (см. RAW_PER_WINDOW), иначе съест журнал. */
    val rawLog = MutableStateFlow(false)
    /** v188: идёт ли замер детектора. Раньше жило в переменной
     *  экрана и врало после сворачивания приложения. */
    val diagRecording = MutableStateFlow(false)

    /** L1.1: собирать ли признаки при выключенном экране (окнами).
     *  По умолчанию выключено: цена по батарее ещё не измерена. */
    val bgAccel = MutableStateFlow(false)

    /** v250. Калибровка уклона живёт в СЛУЖБЕ: в Activity автомат
     *  замерзал, как только телефон уходил в карман и гас экран.
     *  stage: ARM ждём движения, REC пишем, DONE ждём подтверждения. */
    val slopeStage = MutableStateFlow("ARM")
    val slopeSteps = MutableStateFlow(0)
    val slopeResult = MutableStateFlow("")
    /** v262. Какой класс записывается прямо сейчас: UP / FLAT / DOWN.
     *  Пусто - калибровка не идёт. Очередь отменена: рельеф не обязан
     *  давать все три отрезка подряд и в нужном порядке. */
    val slopeTarget = MutableStateFlow("")
    /** v267. Сколько строк признаков собрано в текущем замере: именно
     *  они, а не шаги, решают, хватит ли на медиану. */
    val slopeRows = MutableStateFlow(0)
}
