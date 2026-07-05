package com.vasil.stepcore

import kotlinx.coroutines.flow.MutableStateFlow

object StepsState {
    val steps = MutableStateFlow(0)
    val serviceRunning = MutableStateFlow(false)
    val hapticEnabled = MutableStateFlow(false)
}
