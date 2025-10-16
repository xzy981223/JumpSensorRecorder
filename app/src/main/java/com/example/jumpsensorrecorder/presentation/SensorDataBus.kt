package com.example.jumpsensorrecorder.presentation

import kotlinx.coroutines.flow.MutableSharedFlow

object SensorDataBus {
    val hrFlow = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val accelFlow = MutableSharedFlow<Float>(extraBufferCapacity = 1024)
}