package com.example.jumpsensorwatch

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var messageClient: MessageClient
    private var lastSentTime = 0L
    private var nodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        messageClient = Wearable.getMessageClient(this)

        val nodeClient: NodeClient = Wearable.getNodeClient(this)
        Thread {
            try {
                val nodes = Tasks.await(nodeClient.connectedNodes)
                nodeId = nodes.firstOrNull()?.id
                Log.d("Watch", "Connected to phone node: $nodeId")
            } catch (e: Exception) {
                Log.e("Watch", "Node error: ${e.message}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val accY = event.values[1]
        val timestamp = System.currentTimeMillis()

        if (timestamp - lastSentTime >= 100) {
            lastSentTime = timestamp

            val json = JSONObject().apply {
                put("timestamp", timestamp)
                put("acc_y", accY)
            }

            nodeId?.let { id ->
                messageClient.sendMessage(id, "/jump_data", json.toString().toByteArray())
                    .addOnSuccessListener {
                        Log.d("Watch", "Data sent: $json")
                    }
                    .addOnFailureListener {
                        Log.e("Watch", "Send failed: ${it.message}")
                    }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
