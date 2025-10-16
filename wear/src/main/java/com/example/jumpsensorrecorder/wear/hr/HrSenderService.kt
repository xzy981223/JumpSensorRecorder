package com.example.jumpsensorrecorder.hr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * HrSenderService (Wear) - 前台服务
 * ✅ 后台持续采集 Pixel Watch 心率并发送到手机
 * ✅ 广播心率给 MainActivity 用于 UI 显示
 */
class HrSenderService : Service() {

    private lateinit var hrSender: HrSender

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("HrSenderService", "onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            stopSending()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()

        // ✅ 启动 HrSender
        hrSender = HrSender(
            context = applicationContext,
            useMock = false
        ) { bpm ->
            // 心率回调：广播给 UI
            broadcastHeartRate(bpm)
        }
        hrSender.start()

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "hr_channel"
        val channelName = "HR Sender"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        channelName,
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Heart Rate Sender")
            .setContentText("Sending heart rate to phone…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(1, notification)
        }
    }

    /** ✅ 广播心率数据给 MainActivity */
    private fun broadcastHeartRate(bpm: Int) {
        val intent = Intent(ACTION_HR_BROADCAST).apply {
            putExtra(EXTRA_HR, bpm)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /** ✅ 停止服务 */
    private fun stopSending() {
        try {
            hrSender.stop()
        } catch (e: Exception) {
            Log.w("HrSenderService", "hrSender.stop() 出错: ${e.message}")
        }
        stopSelf()
        Log.d("HrSenderService", "stopSending → service stopping…")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            hrSender.stop()
        } catch (e: Exception) {
            Log.w("HrSenderService", "onDestroy hrSender.stop 出错: ${e.message}")
        }
        Log.d("HrSenderService", "onDestroy, service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "ACTION_STOP_HR_SERVICE"
        const val ACTION_HR_BROADCAST = "ACTION_HR_BROADCAST"
        const val EXTRA_HR = "EXTRA_HR"
    }
}
