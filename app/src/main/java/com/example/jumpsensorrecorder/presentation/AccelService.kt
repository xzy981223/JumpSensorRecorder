package com.example.jumpsensorrecorder.presentation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.MetaWearBoard.DeviceDisconnectedHandler
import com.mbientlab.metawear.Route
import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.data.Acceleration
import com.mbientlab.metawear.module.Accelerometer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.launch

/**
 * AccelService - 连接 MetaMotionS，采集加速度 (X/Y/Z 三轴 + 绝对值)
 */
class AccelService : Service() {

    private var serviceBinder: BtleService.LocalBinder? = null
    private var board: MetaWearBoard? = null
    private var accelModule: Accelerometer? = null
    private var accelRoute: Route? = null
    private var isConnected = false

    private val mainHandler by lazy { Handler(mainLooper) }
    private var remainingConnectRetries = MAX_CONNECT_RETRIES

    private val csvBuffer = StringBuilder()
    private val bufferLock = Any()

    // -------- ServiceConnection ----------
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "✅ BtleService 已连接")
            serviceBinder = binder as BtleService.LocalBinder

            try {
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = btManager.adapter.getRemoteDevice(META_MOTION_MAC)
                Log.d(TAG, "🔍 尝试连接设备 MAC=$META_MOTION_MAC")

                board = serviceBinder?.getMetaWearBoard(device)
                remainingConnectRetries = MAX_CONNECT_RETRIES
                connectBoardWithRetry()
            } catch (e: Exception) {
                Log.e(TAG, "❌ 获取 BluetoothDevice 出错", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "⚠️ BtleService 已断开")
            serviceBinder = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 onStartCommand - 启动加速度采集服务")

        // ⭐ 启动前台通知，防止被系统杀死
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "accel_channel"
            val channel =
                NotificationChannel(channelId, "Accel Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("JumpSensor Recorder")
                .setContentText("加速度采集中…")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()

            startForeground(1, notification)
        }

        val serviceIntent = Intent(this, BtleService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        return START_STICKY
    }

    /** 配置加速度采集 (X/Y/Z 三轴 + 绝对值) */
    private fun setupAccel() {
        try {
            accelModule = board?.getModule(Accelerometer::class.java)
            if (accelModule == null) {
                Log.e(TAG, "❌ 获取 Accelerometer 模块失败")
                return
            }
            Log.d(TAG, "✅ Accelerometer 模块获取成功")

            // ⭐修改点①：在重新配置前务必先 stop，否则 ODR 可能不会刷新
            accelModule?.stop()
            accelModule?.acceleration()?.stop()

            // ⭐修改点②：完整配置 + 禁用低功耗
            accelModule?.configure()
                ?.odr(50f)
                ?.range(16f)
                ?.commit()

            Log.d(TAG, "⚙️ 已设置采样率 100Hz (range=16g, lowPower=false)")

            // ⭐修改点③：等待设备应用配置（很重要）
            mainHandler.postDelayed({
                startAccelStream()
            }, 300) // 延迟300ms后启动
        } catch (e: Exception) {
            Log.e(TAG, "❌ setupAccel 出错", e)
        }
    }

    private fun connectBoardWithRetry() {
        val currentBoard = board
        if (currentBoard == null) {
            Log.e(TAG, "❌ MetaMotionS board 未初始化，无法连接")
            return
        }

        val attempt = MAX_CONNECT_RETRIES - remainingConnectRetries + 1
        Log.d(
            TAG,
            "🔄 正在尝试连接 MetaMotionS (第 ${attempt} 次，共 ${MAX_CONNECT_RETRIES} 次)"
        )

        currentBoard.connectAsync().continueWith { task ->
            if (task.isFaulted) {
                Log.e(TAG, "❌ MetaMotionS 连接失败 (第 ${attempt} 次)", task.error)

                if (remainingConnectRetries > 1) {
                    remainingConnectRetries--
                    val delay = CONNECT_RETRY_DELAY_MS * attempt
                    Log.w(TAG, "⏳ ${delay}ms 后重试连接 (剩余 ${remainingConnectRetries} 次)")
                    mainHandler.postDelayed({ connectBoardWithRetry() }, delay)
                } else {
                    Log.e(TAG, "🚫 MetaMotionS 连接失败，已达到最大重试次数")
                    stopSelf()
                }
            } else {
                Log.d(TAG, "✅ MetaMotionS 连接成功 (第 ${attempt} 次)")
                isConnected = true

                currentBoard.onUnexpectedDisconnect = DeviceDisconnectedHandler { status ->
                    Log.w(TAG, "⚠️ MetaMotionS 意外断开: $status")
                    isConnected = false
                    remainingConnectRetries = MAX_CONNECT_RETRIES
                    mainHandler.post { connectBoardWithRetry() }
                }

                // ⭐ 在此处打印设备型号
                Log.d(TAG, "✅ Connected board: ${currentBoard.model}")
                setupAccel()
            }
        }
    }

    /** 启动数据流（拆分出来便于调试） */
    private fun startAccelStream() {
        try {
            val writeIntervalMs = 3000L  // 每3秒写入一次
            var lastFlush = System.currentTimeMillis()
            var sampleCount = 0
            val startTime = System.currentTimeMillis()

            synchronized(bufferLock) {
                csvBuffer.clear()
            }

            accelModule?.acceleration()?.addRouteAsync { source ->
                source.stream { data, _ ->
                    try {
                        val acc = data.value(Acceleration::class.java)
                        val ax = acc.x() * 9.81f
                        val ay = acc.y() * 9.81f
                        val az = acc.z() * 9.81f
                        val timestamp = System.currentTimeMillis()

                        // ✅ 每条样本都放进缓存（不漏）
                        synchronized(bufferLock) {
                            csvBuffer.append("$timestamp,$ax,$ay,$az\n")
                        }

                        // 用于监控实际采样率
                        sampleCount++
                        if (sampleCount % 100 == 0) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                            val rate = sampleCount / elapsed
                            Log.d(TAG, "📈 实际采样率 = %.1f Hz".format(rate))
                        }

                        // ✅ 每3秒写入一次文件
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= writeIntervalMs) {
                            flushBuffer()
                            lastFlush = now
                        }

                        // ✅ 同时广播给 MainActivity（用于UI显示）
                        val intent = Intent(ACTION_ACCEL_UPDATE).apply {
                            putExtra(EXTRA_ACCEL_X, ax)
                            putExtra(EXTRA_ACCEL_Y, ay)
                            putExtra(EXTRA_ACCEL_Z, az)
                        }
                        LocalBroadcastManager.getInstance(this@AccelService).sendBroadcast(intent)

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 解析加速度出错", e)
                    }
                }
            }?.continueWith { task ->
                if (!task.isFaulted) {
                    accelRoute = task.result
                    accelModule?.acceleration()?.start()
                    accelModule?.start()
                    Log.d(TAG, "✅ 加速度采集开始 (每3秒批量写入)")
                } else {
                    Log.e(TAG, "❌ 加速度路由创建失败", task.error)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ startAccelStream 出错", e)
        }
    }


    /** 停止并释放资源 */
    private fun releaseBoard() {
        try {
            Log.d(TAG, "🛑 准备停止加速度采集...")
            mainHandler.removeCallbacksAndMessages(null)
            remainingConnectRetries = MAX_CONNECT_RETRIES

            accelRoute?.remove()
            accelRoute = null

            accelModule?.acceleration()?.stop()
            accelModule?.stop()
            Log.d(TAG, "✅ 加速度采集已停止")

            // 🔹 写入最后的未满3秒数据
            flushBuffer()

            if (isConnected) {
                board?.disconnectAsync()?.continueWith {
                    Log.d(TAG, "🔌 MetaMotionS 已断开连接")
                }
            }

            unbindService(serviceConnection)
            isConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ releaseBoard 出错", e)
        }
    }

    private fun flushBuffer() {
        val chunk: String
        synchronized(bufferLock) {
            if (csvBuffer.isEmpty()) return
            chunk = csvBuffer.toString()
            csvBuffer.clear()
        }

        // ✅ 用后台协程执行写入
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(getExternalFilesDir(null), "accel_log.csv")
                FileWriter(file, true).use { it.write(chunk) }
                val linesWritten = chunk.count { it == '\n' } +
                    if (chunk.isNotEmpty() && chunk.last() != '\n') 1 else 0
                Log.d(TAG, "💾 已写入 $linesWritten 行数据")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 写入文件失败: ${e.message}")
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 onDestroy - 停止加速度采集")
        releaseBoard()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AccelService"
        // ⚠️ 改成你自己的 MetaMotionS MAC 地址
        private const val META_MOTION_MAC = "D3:19:E3:DE:E6:9B"

        private const val MAX_CONNECT_RETRIES = 3
        private const val CONNECT_RETRY_DELAY_MS = 2000L

        // ✅ 广播 Action & Extra Key
        const val ACTION_ACCEL_UPDATE = "ACTION_ACCEL_UPDATE"
        const val EXTRA_ACCEL_X = "EXTRA_ACCEL_X"
        const val EXTRA_ACCEL_Y = "EXTRA_ACCEL_Y"
        const val EXTRA_ACCEL_Z = "EXTRA_ACCEL_Z"
        const val EXTRA_ACCEL_ABS = "EXTRA_ACCEL_ABS"
    }
}
