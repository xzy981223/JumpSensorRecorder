package com.example.jumpsensorrecorder.presentation

import android.util.Log
import kotlin.math.sqrt

/**
 * 💡 Realtime JPM Detector with RMS smoothing (Python-aligned)
 */
class JpmDetector(
    private val onJpmCalculated: (Double) -> Unit,
    private val sampleRateHz: Double = 100.0,
    private val windowSizeSec: Double = 1.8,
    private val stepSizeMs: Long = 450L,
    private val threshold: Double = 18.0
) {
    private val buffer = mutableListOf<Pair<Long, Double>>() // (timestamp, accel)
    private var lastUpdateTime = 0L

    // 🔹 RMS smoothing params
    private val rmsWindow = 10        // n=10 samples (same as Python)
    private val minPeakDistance = (0.35 * sampleRateHz).toInt()  // min 0.35s between peaks

    fun addSample(accelAbs: Double, timestamp: Long) {
        buffer.add(timestamp to accelAbs)
        if (buffer.size > (windowSizeSec * sampleRateHz).toInt())
            buffer.removeFirst()

        if (timestamp - lastUpdateTime >= stepSizeMs) {
            computeAvgJpm()
            lastUpdateTime = timestamp
        }
    }

    private fun computeAvgJpm() {
        if (buffer.size < rmsWindow) return

        val accel = buffer.map { it.second }

        // ✅ RMS smoothing
        val rms = MutableList(accel.size) { 0.0 }
        for (i in accel.indices) {
            val start = maxOf(0, i - rmsWindow / 2)
            val end = minOf(accel.size, i + rmsWindow / 2)
            val segment = accel.subList(start, end)
            rms[i] = sqrt(segment.map { it * it }.average())
        }

        // ✅ Peak detection
        val peaks = mutableListOf<Int>()
        var lastPeak = -minPeakDistance
        for (i in 1 until rms.size - 1) {
            if (rms[i] > threshold &&
                rms[i] > rms[i - 1] &&
                rms[i] > rms[i + 1] &&
                i - lastPeak >= minPeakDistance
            ) {
                peaks.add(i)
                lastPeak = i
            }
        }

        if (peaks.size > 1) {
            val times = buffer.map { it.first.toDouble() / 1000.0 }
            val intervals = peaks.zipWithNext { a, b -> times[b] - times[a] }
            val valid = intervals.filter { it <= 2.0 }
            if (valid.isNotEmpty()) {
                val jpm = 60.0 / valid.average()
                onJpmCalculated(jpm)
                Log.d("JpmDetector", "✅ Peaks=${peaks.size}, JPM=%.1f".format(jpm))
            } else {
                onJpmCalculated(0.0)
            }
        } else {
            onJpmCalculated(0.0)
        }
    }
}
