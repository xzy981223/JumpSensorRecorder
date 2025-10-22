package com.example.jumpsensorrecorder.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.example.jumpsensorrecorder.R
import com.example.jumpsensorrecorder.hr.HrSenderService
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvHr: TextView
    private lateinit var tvJpm: TextView
    private lateinit var tvBpm: TextView
    private lateinit var tvState: TextView

    private var isCollecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvHr = findViewById(R.id.tvHr)
        tvJpm = findViewById(R.id.tvJpm)
        tvBpm = findViewById(R.id.tvBpm)
        tvState = findViewById(R.id.tvState)
        tvState.text = getString(R.string.status_ready)

        btnStop.isEnabled = false
        btnStop.alpha = 0.5f

        // 👉 Start 按钮
        btnStart.setOnClickListener {
            if (allPermissionsGranted()) {
                if (!isCollecting) {
                    isCollecting = true
                    btnStop.isEnabled = true
                    btnStop.alpha = 1.0f
                    btnStart.isEnabled = false
                    tvState.text = getString(R.string.status_collecting)

                    // 启动心率服务
                    ContextCompat.startForegroundService(
                        this,
                        android.content.Intent(this, HrSenderService::class.java)
                    )

                    // 通知手机开始采集
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                            for (n in nodes) {
                                Wearable.getMessageClient(this@MainActivity)
                                    .sendMessage(n.id, PATH_START_ALL, ByteArray(0))
                                    .await()
                            }
                            Log.d("WatchMain", "发送 /start_all 给手机 ✅")
                        } catch (e: Exception) {
                            Log.e("WatchMain", "发送 /start_all 失败", e)
                        }
                    }
                }
            } else {
                requestAllPermissions()
                tvState.text = getString(R.string.permission_denied)
            }
        }

        // 👉 Stop 按钮
        btnStop.setOnClickListener {
            if (isCollecting) {
                isCollecting = false
                btnStop.isEnabled = false
                btnStop.alpha = 0.5f
                btnStart.isEnabled = true
                tvState.text = getString(R.string.status_stopped)

                // 停止心率服务
                ContextCompat.startForegroundService(
                    this,
                    android.content.Intent(this, HrSenderService::class.java).apply {
                        action = HrSenderService.ACTION_STOP
                    }
                )

                // 通知手机停止采集
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                        for (n in nodes) {
                            Wearable.getMessageClient(this@MainActivity)
                                .sendMessage(n.id, PATH_STOP_ALL, ByteArray(0))
                                .await()
                        }
                        Log.d("WatchMain", "发送 /stop_all 给手机 ✅")
                    } catch (e: Exception) {
                        Log.e("WatchMain", "发送 /stop_all 失败", e)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(this)
    }

    /** ✅ 接收来自手机的状态更新 */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/update_status") {
            val msg = String(event.data)
            Log.d("WatchMain", "收到状态: $msg")

            // 格式: HR:120,JPM:118,BPM:120,State:Normal
            val parts = msg.split(",")
            val hr = parts.getOrNull(0)?.substringAfter("HR:") ?: "--"
            val jpm = parts.getOrNull(1)?.substringAfter("JPM:") ?: "--"
            val bpm = parts.getOrNull(2)?.substringAfter("BPM:") ?: "--"

            runOnUiThread {
                tvHr.text = "HR: $hr bpm"
                tvJpm.text = "JPM: $jpm"
                tvBpm.text = "BPM: $bpm"
            }
        }
    }

    /** 权限检查 */
    private fun allPermissionsGranted(): Boolean {
        val requiredPermissions = mutableListOf(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        return requiredPermissions.all { perm ->
            checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 请求权限 */
    private fun requestAllPermissions() {
        val permissions = mutableListOf(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 101)
        }
    }

    companion object {
        const val PATH_START_ALL = "/start_all"
        const val PATH_STOP_ALL = "/stop_all"
    }
}
