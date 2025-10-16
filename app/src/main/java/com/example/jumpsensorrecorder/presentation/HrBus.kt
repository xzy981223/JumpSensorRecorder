package com.example.jumpsensorrecorder.presentation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object HrBus {
    // 内部可写，外部只读
    private val _hr = MutableSharedFlow<Int>(
        replay = 1,                // 新订阅者立刻拿到最近一次
        extraBufferCapacity = 1    // 临时缓冲，避免偶发丢数据
    )

    // 外部收集这个只读流
    val hr = _hr.asSharedFlow()

    // 对外提供非 suspend 的发射方法（不用协程也能调用）
    fun emit(bpm: Int) {
        _hr.tryEmit(bpm)           // 不会挂起；失败也不会崩
    }
}
