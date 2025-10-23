package com.example.jumpsensorrecorder.presentation

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

class HrReceiverService : WearableListenerService() {

    private var hrRecvCount = 0      // 心率接收计数
    private var accelRecvCount = 0   // 加速度接收计数

    override fun onCreate() {
        super.onCreate()
        Log.d("HrReceiverService", "onCreate - 服务已创建 ✅")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PATH_HR -> {   // ✅ 接收心率
                Log.d("HrReceiverService", "✅ 接收到心率数据")
                try {
                    val buffer = ByteBuffer.wrap(messageEvent.data)
                    hrRecvCount++

                    if (messageEvent.data.size == 8) {
                        // 新格式：sendId + bpm
                        val sendId = buffer.int
                        val bpm = buffer.int
                        Log.d("HrReceiverService", "[HR] Recv #$hrRecvCount ✅ sendId=$sendId, bpm=$bpm")
                        HrBus.emit(bpm)

                        // 广播给 MainActivity
                        val intent = Intent(ACTION_HR_UPDATE).apply {
                            putExtra(EXTRA_BPM, bpm)
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                    } else if (messageEvent.data.size == 4) {
                        // 旧格式：只有 bpm
                        val bpm = buffer.int
                        Log.d("HrReceiverService", "[HR] Recv #$hrRecvCount ⚠️ (no sendId) bpm=$bpm")
                        HrBus.emit(bpm)

                        val intent = Intent(ACTION_HR_UPDATE).apply {
                            putExtra(EXTRA_BPM, bpm)
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                    } else {
                        Log.w("HrReceiverService", "[HR] 收到未知长度数据 size=${messageEvent.data.size}")
                    }

                } catch (e: Exception) {
                    Log.e("HrReceiverService", "[HR] 解析 BPM 出错", e)
                }
            }

            PATH_ACCEL_Y -> {   // ✅ 接收 Y 轴加速度
                Log.d("HrReceiverService", "✅ 接收到加速度数据: ${messageEvent.data.size}")
                try {
                    val buffer = ByteBuffer.wrap(messageEvent.data)
                    accelRecvCount++
                    val sendId = buffer.int
                    val ay = buffer.float

//                    Log.d(TAG, "[DATA] ACCEL#$accelRecvCount sendId=$sendId, ay=$ay m/s² 📉")

                    // 广播给 MainActivity
                    val intent = Intent(ACTION_ACCEL_UPDATE).apply {
                        putExtra(EXTRA_ACCEL_Y, ay)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                } catch (e: Exception) {
                    Log.e("HrReceiverService", "[ACCEL] 解析 AccelY 出错", e)
                }
            }

            PATH_START_ALL -> {
                try {
                    Log.d("HrReceiverService", "收到 StartAll 请求，启动 AccelService ✅")
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, AccelService::class.java)
                    )
                    // 通知 MainActivity 更新 UI（通过广播）
                    val uiIntent = Intent("ACTION_UI_START")
                    LocalBroadcastManager.getInstance(this).sendBroadcast(uiIntent)
                } catch (e: Exception) {
                    Log.e("HrReceiverService", "启动 AccelService 失败", e)
                }
            }

            PATH_STOP_ALL -> {
                try {
                    Log.d("HrReceiverService", "收到 StopAll 请求，准备停止 AccelService ❌")
                    val stopped = stopService(Intent(this, AccelService::class.java))
                    Log.d("HrReceiverService", "stopService(AccelService) 调用结果 = $stopped")
                } catch (e: Exception) {
                    Log.e("HrReceiverService", "停止 AccelService 失败", e)
                }
            }

            else -> {
                Log.w("HrReceiverService", "收到未处理的路径: ${messageEvent.path}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HrReceiverService"
        const val PATH_HR = "/heart_rate"
        const val PATH_ACCEL_Y = "/accel_y"

        // ✅ 心率广播
        const val ACTION_HR_UPDATE = "ACTION_HR_UPDATE"
        const val EXTRA_BPM = "EXTRA_BPM"

        // ✅ 加速度广播
        const val ACTION_ACCEL_UPDATE = "ACTION_ACCEL_UPDATE"
        const val EXTRA_ACCEL_Y = "EXTRA_ACCEL_Y"

        // ✅ 控制路径
        const val PATH_START_ALL = "/start_all"
        const val PATH_STOP_ALL = "/stop_all"
    }
}
