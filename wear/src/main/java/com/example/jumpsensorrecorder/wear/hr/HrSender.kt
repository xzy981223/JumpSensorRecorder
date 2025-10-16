package com.example.jumpsensorrecorder.hr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

/**
 * HrSender - 负责采集心率并发送到手机，同时支持 UI 回调。
 */

const val PATH_HR = "/heart_rate"

class HrSender(
    private val context: Context,
    private val useMock: Boolean = false,
    private val onHeartRateUpdate: ((Int) -> Unit)? = null   // 回调函数
) : SensorEventListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    private var isStarted = false

    private var sendId = 0  // 🔹 全局发送ID（每次发送递增）

    fun start() {
        if (isStarted) return
        isStarted = true

        if (useMock) {
            // ----------- 模拟模式 -----------
            scope.launch {
                var bpm = 100
                while (isActive) {
                    sendId++
                    sendBpm(sendId, bpm)
                    onHeartRateUpdate?.invoke(bpm) // 更新 UI
                    bpm += (-2..2).random()
                    delay(1000)
                }
            }
        } else {
            // ----------- 真实心率模式 -----------
            heartRateSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            } ?: run {
                onHeartRateUpdate?.invoke(-1) // -1 表示没有传感器
            }
        }
    }

    fun stop() {
        if (!useMock) {
            sensorManager.unregisterListener(this)
        }
        scope.cancel()
        isStarted = false
    }

    // ========== SensorEventListener ==========
    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            Log.d("HrSender", "Got heart rate = $bpm")
            sendBpm(sendId, bpm)
            onHeartRateUpdate?.invoke(bpm) // 回调 UI
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ========== 发消息给手机 ==========
    private fun sendBpm(id: Int, bpm: Int) {
        scope.launch {
            try {
                // 🔹 打包 sendId + bpm (8字节)
                val buffer = ByteBuffer.allocate(8)
                    .putInt(id)
                    .putInt(bpm)
                    .array()

                val nodes = Wearable.getNodeClient(context).connectedNodes.await()

                // ✅ 在发送之前打印
                Log.d("HrSender", "➡️ preparing to send bpm=$bpm to ${nodes.size} nodes, path=$PATH_HR")

                for (n in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(n.id, "/heart_rate", buffer)
                        .addOnSuccessListener {
                            Log.d("HrSender", "✅ sending bpm=$bpm to node=${n.displayName} (id=${n.id}) success")
                        }
                        .addOnFailureListener { e ->
                            Log.e("HrSender", "❌ failed to send bpm=$bpm to node=${n.displayName} (id=${n.id})", e)
                        }
                        .await()
                }
            } catch (e: Exception) {
                Log.e("HrSender", "Exception while sending bpm=$bpm", e)
            }
        }
    }
}
