package com.example.jumpsensorrecorder.presentation

import android.app.Activity
import android.content.*
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.jumpsensorrecorder.R
import com.example.jumpsensorrecorder.presentation.HrReceiverService.Companion.ACTION_HR_UPDATE
import com.example.jumpsensorrecorder.presentation.HrReceiverService.Companion.ACTION_ACCEL_UPDATE
import com.example.jumpsensorrecorder.presentation.HrReceiverService.Companion.EXTRA_BPM
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Build

import com.example.jumpsensorrecorder.presentation.AccelService


class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {

    private var currentJpm: Double = 0.0
    private var consistencyCounter = 5
    private var isJumping = false
    private var isWarmup = false

    private var lastHr: Int? = null
    private var hrMax: Int = 0
    private var targetLow: Int = 0
    private var targetHigh: Int = 0
    private var currentBpm: Int = 0

    // ---- UI ----
    private lateinit var ageEditText: EditText
    private lateinit var calcHrButton: Button
    private lateinit var hrSummaryTextView: TextView
    private lateinit var tvBpm: TextView
    private lateinit var tvAccel: TextView
    private lateinit var tvJpm: TextView
    private lateinit var metronomeStatusTextView: TextView
    private lateinit var tvWarmupTimer: TextView
    private lateinit var tvMode: TextView
    private lateinit var nameEditText: AutoCompleteTextView
    private var participantName: String = ""
    private val PREFS_NAME = "user_prefs"
    private val KEY_USER_LIST = "user_list"
    private var isUiInitialized = false

    // JPM 检测器
    private val jpmDetector = JpmDetector(
        onJpmCalculated = { jpm ->
            currentJpm = jpm
            runOnUiThread {
                tvJpm.text = if (jpm <= 0.01) {
                    "跳跃频率: 已停止"
                } else {
                    "跳跃频率: ${"%.1f".format(jpm)} JPM"
                }

                if (isJumping) {
                    lastHr?.let { hr ->
                        sendStatusToWatch(hr, jpm, currentBpm)
                    }
                }
            }
        },
        sampleRateHz = 50.0,
        windowSizeSec = 1.8,
        stepSizeMs = 450L,
        threshold = 18.0
    )

    // ---- 文件相关 ----
    private lateinit var logFile: File

    // ✅ 广播接收心率 & 加速度
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val timestamp = System.currentTimeMillis()
            when (intent?.action) {
                ACTION_HR_UPDATE -> {
                    val bpm = intent.getIntExtra(EXTRA_BPM, -1)
                    if (bpm != -1) {
                        lastHr = bpm
                        runOnUiThread { tvBpm.text = "Heart rate: $bpm bpm" }
                        updateMetronome(bpm)
                        appendLog(timestamp, bpm, null, null, null, null, currentJpm, currentBpm, "HR")
                        if (isJumping && isWarmup) {
                            sendStatusToWatch(bpm, currentJpm, currentBpm)
                        }
                    }
                }
                ACTION_ACCEL_UPDATE -> {
                    val ax = intent.getFloatExtra(AccelService.EXTRA_ACCEL_X, 0f).toDouble()
                    val ay = intent.getFloatExtra(AccelService.EXTRA_ACCEL_Y, 0f).toDouble()
                    val az = intent.getFloatExtra(AccelService.EXTRA_ACCEL_Z, 0f).toDouble()
                    val aAbs = kotlin.math.sqrt(ax * ax + ay * ay + az * az)

                    Log.d("Experiment", "收到加速度: X=$ax, Y=$ay, Z=$az, Abs=$aAbs")

                    runOnUiThread {
                        tvAccel.text = "Accel: X=$ax, Y=$ay, Z=$az, |A|=${"%.2f".format(aAbs)}"
                    }

                    // ✅ JPM 用绝对加速度
                    jpmDetector.addSample(aAbs.toDouble(), System.currentTimeMillis())

                    appendLog(timestamp, null, ax, ay, az, aAbs, currentJpm, currentBpm, "ACCEL")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContentView(R.layout.activity_main)
        checkPermissionsAndMaybeInit()

        // ✅ 注册来自 HrReceiverService 的广播监听
        LocalBroadcastManager.getInstance(this).registerReceiver(object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "ACTION_UI_START") {
                    Log.d("PhoneMain", "📩 收到 HrReceiverService 发来的 ACTION_UI_START")
                    startWarmup() // 🔥 直接启动实验逻辑
                }
            }
        }, IntentFilter("ACTION_UI_START"))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ACTION_HR_UPDATE)
            addAction(ACTION_ACCEL_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(dataReceiver, filter)
        Wearable.getMessageClient(this).addListener(this) // 🔹 监听手表
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver)
        Wearable.getMessageClient(this).removeListener(this)
    }

    // 🔹 接收手表指令
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/start_all" -> {
                Log.d("PhoneMain", "手表按了 Start ✅")
                startWarmup()
            }
            "/stop_all" -> {
                Log.d("PhoneMain", "手表按了 Stop ❌")
                stopJumping()
            }
        }
    }

    private fun startWarmup() {
        // ✅ 新增：在热身开始前获取姓名 & 创建日志文件
        participantName = nameEditText.text.toString().trim()

        // ✅ 启动前台加速度采集服务，保证手机端实时收到加速度
        kotlin.runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AccelService::class.java)
            )
        }.onFailure { e ->
            Log.e("PhoneMain", "启动 AccelService 失败", e)
        }

        // ---- 保存用户名到 SharedPreferences ----
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userSet = prefs.getStringSet(KEY_USER_LIST, mutableSetOf())!!.toMutableSet()
        if (!userSet.contains(participantName)) {
            userSet.add(participantName)
            prefs.edit().putStringSet(KEY_USER_LIST, userSet).apply()
        }

        // ---- 生成文件名与路径 ----
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val filename = "experiment_log_${participantName}_${sdf.format(Date())}.csv"
        val dir = File(filesDir, "logs/$participantName")   // ✅ 每个用户独立目录
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, filename)
        Log.d("Experiment", "日志文件初始化: ${logFile.absolutePath}")

        // ---- 写入表头 ----
        if (!logFile.exists() || logFile.length() == 0L) {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                writer.appendLine("Name: $participantName")
                writer.appendLine("timestamp,hr,ax,ay,az,jpm,bpm")
            }
        }

        if (isJumping || hrMax == 0) {
            runOnUiThread { hrSummaryTextView.text = "⚠️ 请先计算 HRmax!" }
            return
        }


        isJumping = true
        isWarmup = true
        consistencyCounter = 5
        currentBpm = 100

        // 固定 100 BPM 热身 30 秒
        MetronomeManager.start(100)
        runOnUiThread {
            metronomeStatusTextView.text = "Metronome: 100 BPM (warming up)"
            tvMode.text = "mode: warming up (30s, 100 BPM)"
        }

        object : CountDownTimer(30_000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt()
                runOnUiThread {
                    tvWarmupTimer.text = "Warm-up ends in: ${sec}s"
                }
            }

            override fun onFinish() {
                runOnUiThread { tvWarmupTimer.text = "Warm-up finished" }
                isWarmup = false
                if (isJumping) {
                    runOnUiThread {
                        tvMode.text = "mode: automatic"
                        metronomeStatusTextView.text = "Metronome: automatic"
                    }
                    lastHr?.let { updateMetronome(it) }
                }
            }
        }.start()
    }

    private fun stopJumping() {
        if (!isJumping) return
        isJumping = false
        MetronomeManager.stop()
        currentBpm = 0
        runOnUiThread {
            tvMode.text = "mode: stop"
            metronomeStatusTextView.text = "Metronome: STOP"
        }
        // 🔹 停止前台加速度服务，避免继续采集
        kotlin.runCatching {
            val stopped = stopService(Intent(this, AccelService::class.java))
            Log.d("Experiment", "停止 AccelService: $stopped")
        }.onFailure { e ->
            Log.e("Experiment", "停止 AccelService 失败", e)
        }
        Log.d("Experiment", "跳绳已停止")
    }

    private fun calcHrMax(age: Int): Int = (220 - age).coerceIn(80, 220)

    private fun updateMetronome(hr: Int) {
        if (!isJumping || hrMax == 0 || isWarmup) return

        val targetBpm = when {
            hr < targetLow -> 135
            hr <= targetHigh -> 120
            else -> 100
        }

        if (targetBpm != currentBpm) {
            currentBpm = targetBpm
            MetronomeManager.start(currentBpm)
            runOnUiThread {
                metronomeStatusTextView.text = "Metronome: $currentBpm BPM"
                tvMode.text = "mode: auto ($currentBpm BPM)"
            }
        }
        sendStatusToWatch(hr, currentJpm, currentBpm)
    }

    private fun sendStatusToWatch(hr: Int, jpm: Double, bpm: Int) {
        val msg = "HR:$hr,JPM:${"%.0f".format(jpm)},BPM:$bpm"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (n in nodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(n.id, "/update_status", msg.toByteArray())
                        .await()
                }
                Log.d("PhoneMain", "发送状态到手表: $msg ✅")
            } catch (e: Exception) {
                Log.e("PhoneMain", "发送状态失败", e)
            }
        }
    }

    private fun appendLog(
        timestamp: Long,
        hr: Int?,
        ax: Double?,
        ay: Double?,
        az: Double?,
        aAbs: Double?,   // ✅ 保留参数以兼容旧调用，但不再使用
        jpm: Double,
        bpm: Int,
        type: String     // ✅ 仍保留参数以兼容旧结构
    ) {
        try {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                // ✅ 去掉 aAbs 与 type，只写基础字段
                writer.appendLine(
                    "$timestamp," +
                            "${hr ?: ""}," +
                            "${ax ?: ""}," +
                            "${ay ?: ""}," +
                            "${az ?: ""}," +
                            "${"%.2f".format(jpm)}," +
                            "$bpm"
                )
            }
        } catch (e: Exception) {
            Log.e("Experiment", "写入CSV失败: ${e.message}", e)
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        MetronomeManager.stop()
        stopService(Intent(this, AccelService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_BT || requestCode == REQUEST_CODE_LOCATION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("MainActivity", "权限已授予，继续初始化 UI")
                checkPermissionsAndMaybeInit()
            } else {
                Log.w("MainActivity", "必要权限被拒绝，部分功能不可用")
            }
        }
    }

    private fun checkPermissionsAndMaybeInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_BT)
                return
            }
        } else {
            val locPerm = Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, locPerm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(locPerm), REQUEST_CODE_LOCATION)
                return
            }
        }

        if (isUiInitialized) {
            return
        }
        isUiInitialized = true

        // 🔹 让屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ✅ 初始化节拍器
        MetronomeManager.init(this)

        ageEditText = findViewById(R.id.ageEditText)
        calcHrButton = findViewById(R.id.calcHrButton)
        hrSummaryTextView = findViewById(R.id.hrSummaryTextView)
        tvBpm = findViewById(R.id.tvBpm)
        tvAccel = findViewById(R.id.tvAccel)
        tvJpm = findViewById(R.id.tvJpm)
        metronomeStatusTextView = findViewById(R.id.metronomeStatusTextView)
        tvWarmupTimer = findViewById(R.id.tvWarmupTimer)
        tvMode = findViewById(R.id.tvMode)
        nameEditText = findViewById(R.id.nameEditText)

        // 加载保存过的用户名列表
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userList = prefs.getStringSet(KEY_USER_LIST, mutableSetOf())!!.toMutableList()

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, userList)
        nameEditText.setAdapter(adapter)


        // ✅ 日志文件初始化
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val filename = "experiment_log_${sdf.format(Date())}.csv"
        val dir = File(filesDir, "logs")   // ✅ 改成内部目录
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, filename)
        Log.d("Experiment", "日志文件初始化: ${logFile.absolutePath}")

        // ✅ 写入列名和姓名（只在第一次创建时）
        if (!logFile.exists() || logFile.length() == 0L) {
            BufferedWriter(FileWriter(logFile, true)).use { writer ->
                if (participantName.isNotEmpty()) {
                    writer.appendLine("Name: $participantName")
                }
                writer.appendLine("timestamp,hr,ax,ay,az,jpm,bpm")
            }
        }

        setupUiListeners()
    }

    private fun setupUiListeners() {
        // 计算 HRmax 和 target zone
        calcHrButton.setOnClickListener {
            val age = ageEditText.text.toString().trim().toIntOrNull()
            if (age == null || age !in 10..90) {
                hrSummaryTextView.text = "请输入有效年龄 (10–90)"
                return@setOnClickListener
            }
            hrMax = calcHrMax(age)
            targetLow = (hrMax * 0.55).toInt()
            targetHigh = (hrMax * 0.65).toInt()

            hrSummaryTextView.text = """
                HRmax: $hrMax bpm
                Target zone: $targetLow–$targetHigh bpm (55–65%)
            """.trimIndent()
        }
    }

    companion object {
        private const val REQUEST_CODE_BT = 100
        private const val REQUEST_CODE_LOCATION = 101
    }
}
