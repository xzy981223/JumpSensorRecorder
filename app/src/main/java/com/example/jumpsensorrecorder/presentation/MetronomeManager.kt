package com.example.jumpsensorrecorder.presentation

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.example.jumpsensorrecorder.R
import kotlinx.coroutines.*

/**
 * 🎵 MetronomeManager（改进版）
 * - 使用 SoundPool 播放节拍 click 声
 * - 保证加载完成后才能播放
 * - 支持任意 BPM（如 100 / 120 / 135）
 * - 使用主线程节奏调度，确保播放稳定
 */
object MetronomeManager {

    private const val TAG = "MetronomeManager"

    private lateinit var soundPool: SoundPool
    private var soundId: Int = 0
    private var metronomeJob: Job? = null
    private var isInitialized = false
    private var isSoundLoaded = false

    // 使用一个 CoroutineScope 管理节拍协程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 初始化 SoundPool */
    fun init(context: Context) {
        if (isInitialized) return

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(audioAttributes)
            .setMaxStreams(1)
            .build()

        soundId = soundPool.load(context, R.raw.click, 1)
        Log.d(TAG, "🎧 Loading click.wav ...")

        // 监听加载完成事件
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                isSoundLoaded = true
                Log.d(TAG, "✅ click.wav 加载完成 (soundId=$sampleId)")
            } else {
                Log.e(TAG, "❌ click.wav 加载失败 (status=$status)")
            }
        }

        isInitialized = true
    }

    /** 启动节拍器 */
    fun start(bpm: Int) {
        if (!isInitialized) {
            Log.e(TAG, "⚠️ 请先调用 init(context)")
            return
        }
        if (!isSoundLoaded) {
            Log.w(TAG, "⏳ click.wav 尚未加载完成，等待后重试")
            return
        }

        stop() // 停止上一次节拍

        val intervalMs = (60000.0 / bpm).toLong()
        Log.d(TAG, "▶️ Metronome started at $bpm BPM (interval=${intervalMs}ms)")

        metronomeJob = scope.launch(Dispatchers.Main) {
            var nextBeat = System.currentTimeMillis()
            var beatCount = 0

            while (isActive) {
                val now = System.currentTimeMillis()
                if (now >= nextBeat) {
                    soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
                    beatCount++
                    Log.d(TAG, "🎵 第 $beatCount 拍 (time=$now)")
                    nextBeat += intervalMs
                }
                delay(2)  // 精准节奏控制
            }
        }
    }

    /** 停止节拍器 */
    fun stop() {
        if (metronomeJob?.isActive == true) {
            metronomeJob?.cancel()
            Log.d(TAG, "⏹ Metronome stopped")
        }
        metronomeJob = null
    }

    /** 释放资源 */
    fun release() {
        stop()
        if (isInitialized) {
            soundPool.release()
            isInitialized = false
            isSoundLoaded = false
            Log.d(TAG, "🧹 SoundPool released")
        }
    }
}
