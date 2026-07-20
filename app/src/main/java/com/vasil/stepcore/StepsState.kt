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
    /** v188: идёт ли замер детектора. Раньше жило в переменной
     *  экрана и врало после сворачивания приложения. */
    val diagRecording = MutableStateFlow(false)
}
