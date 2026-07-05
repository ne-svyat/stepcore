package com.vasil.stepcore

import kotlinx.coroutines.flow.MutableStateFlow

/** Простейший общий стейт для V1. В V2 заменим на Room + Repository. */
object StepsState {
    val steps = MutableStateFlow(0)
    val serviceRunning = MutableStateFlow(false)
}
