package com.example.ui

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.ConnectionHistoryEntity
import com.example.data.database.ConnectionHistoryRepository
import com.example.data.database.WiiControllerDatabase
import com.example.network.AudioReceiverServer
import com.example.network.DsuServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.net.Inet4Address
import java.net.NetworkInterface

class WiiControllerViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val TAG = "WiiControllerVM"

    // Database access
    private val database = WiiControllerDatabase.getDatabase(application)
    private val repository = ConnectionHistoryRepository(database.connectionHistoryDao())
    val connectionHistory: StateFlow<List<ConnectionHistoryEntity>> = repository.allItemsFlow()

    // Hardware sensors
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Hardware vibrator
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Network instances
    private var dsuServer: DsuServer? = null
    private var audioServer: AudioReceiverServer? = null

    // UI States
    val ipAddress = MutableStateFlow("127.0.0.1")
    val isDsuRunning = MutableStateFlow(false)
    val isAudioRunning = MutableStateFlow(false)
    val registeredClients = MutableStateFlow<List<String>>(emptyList())
    val totalPacketsSent = MutableStateFlow(0)
    val totalPacketsReceived = MutableStateFlow(0)
    val dsuFps = MutableStateFlow(0)
    val audioBytesReceived = MutableStateFlow(0L)
    val isAudioStreaming = MutableStateFlow(false)

    // Sensor state flows for visual overlay feedback
    private val _accelState = MutableStateFlow(Triple(0f, 0f, 10f))
    val accelState: StateFlow<Triple<Float, Float, Float>> = _accelState

    private val _gyroState = MutableStateFlow(Triple(0f, 0f, 0f))
    val gyroState: StateFlow<Triple<Float, Float, Float>> = _gyroState

    private var telemetryJob: Job? = null
    private var shakeResetJob: Job? = null
    private var lastRumbleTime = 0L

    init {
        refreshLocalIp()
    }

    private fun ConnectionHistoryRepository.allItemsFlow(): StateFlow<List<ConnectionHistoryEntity>> {
        return allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun refreshLocalIp() {
        viewModelScope.launch {
            ipAddress.value = getLocalIpAddress()
        }
    }

    fun startDsuServer(port: Int = 26760) {
        if (dsuServer != null && isDsuRunning.value) return

        dsuServer = DsuServer(port) { weak, strong ->
            handleIncomingRumble(weak, strong)
        }.apply {
            start()
        }
        isDsuRunning.value = true
        startSensors()
        startTelemetryMonitoring()
        triggerVibrationNotification()
    }

    fun stopDsuServer() {
        dsuServer?.stop()
        dsuServer = null
        isDsuRunning.value = false
        stopSensors()
        stopTelemetryMonitoring()
        registeredClients.value = emptyList()
    }

    fun toggleAudioServer(port: Int = 26761) {
        if (isAudioRunning.value) {
            audioServer?.stop()
            audioServer = null
            isAudioRunning.value = false
            isAudioStreaming.value = false
        } else {
            audioServer = AudioReceiverServer(port).apply {
                start()
            }
            isAudioRunning.value = true
            triggerVibration(60, 200)
        }
    }

    private fun startSensors() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        _accelState.value = Triple(0f, 0f, 9.8f)
        _gyroState.value = Triple(0f, 0f, 0f)
    }

    private fun startTelemetryMonitoring() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                dsuServer?.let { server ->
                    registeredClients.value = server.getClientAddresses()
                    totalPacketsSent.value = server.totalPacketsSent
                    totalPacketsReceived.value = server.totalPacketsReceived
                    dsuFps.value = server.fps
                }
                audioServer?.let { server ->
                    audioBytesReceived.value = server.totalBytesReceived
                    isAudioStreaming.value = server.isStreamActive
                }
            }
        }
    }

    private fun stopTelemetryMonitoring() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                dsuServer?.accelX = x
                dsuServer?.accelY = y
                dsuServer?.accelZ = z

                _accelState.value = Triple(x, y, z)

                // Shake detection logic
                val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()) - 9.80665
                if (magnitude > 6.0) { // Shake gesture
                    dsuServer?.buttonShake = true
                    triggerShakeResetTimer()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                dsuServer?.gyroX = x
                dsuServer?.gyroY = y
                dsuServer?.gyroZ = z

                _gyroState.value = Triple(x, y, z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerShakeResetTimer() {
        shakeResetJob?.cancel()
        shakeResetJob = viewModelScope.launch {
            try {
                // Keep shake active for 150ms to guarantee Dolphin samples it
                delay(150)
                dsuServer?.buttonShake = false
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun handleIncomingRumble(weak: Int, strong: Int) {
        val now = System.currentTimeMillis()
        if (weak > 0 || strong > 0) {
            val maxStrength = Math.max(weak, strong)
            val amplitude = maxStrength.coerceIn(0, 255)
            // Throttle to 25ms interval to match hardware limitations
            if (now - lastRumbleTime > 25) {
                triggerVibration(35, amplitude)
                lastRumbleTime = now
            }
        }
    }

    fun triggerVibration(durationMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        vibrator?.let {
            if (it.hasVibrator()) {
                try {
                    val targetAmp = if (amplitude <= 0) 1 else amplitude.coerceIn(1, 255)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(VibrationEffect.createOneShot(durationMs, targetAmp))
                    } else {
                        @Suppress("DEPRECATION")
                        it.vibrate(durationMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Vibrate exception: ${e.message}")
                }
            }
        }
    }

    private fun triggerVibrationNotification() {
        // Wii controller double pulse sound/vibe on connected
        viewModelScope.launch {
            triggerVibration(60, 255)
            delay(100)
            triggerVibration(60, 255)
        }
    }

    // Input actions from UI Buttons overlay
    fun onButtonPressed(button: String, isPressed: Boolean) {
        dsuServer?.let { server ->
            // Trigger feedback vibration on press down
            if (isPressed) {
                triggerVibration(25, 120) // crisp touch feedback
            }

            when (button.uppercase()) {
                "A" -> server.buttonA = isPressed
                "B" -> server.buttonB = isPressed
                "MINUS" -> server.buttonMinus = isPressed
                "PLUS" -> server.buttonPlus = isPressed
                "HOME" -> server.buttonHome = isPressed
                "ONE" -> server.button1 = isPressed
                "TWO" -> server.button2 = isPressed
                "UP" -> server.buttonUp = isPressed
                "RIGHT" -> server.buttonRight = isPressed
                "DOWN" -> server.buttonDown = isPressed
                "LEFT" -> server.buttonLeft = isPressed
                "SHAKE" -> server.buttonShake = isPressed
            }
        }
    }

    fun onStickMoved(x: Float, y: Float) {
        dsuServer?.let { server ->
            // Map floating coordinates -1.0..1.0 to digital stick byte boundary -128..127
            server.stickX = (x * 127f).toInt().coerceIn(-128, 127)
            server.stickY = (y * 127f).toInt().coerceIn(-128, 127)
        }
    }

    // Profile History database operations
    fun saveConnectionToHistory(ip: String, port: Int, desc: String) {
        viewModelScope.launch {
            if (ip.isNotBlank()) {
                repository.insert(ConnectionHistoryEntity(ipAddress = ip.trim(), port = port, description = desc.trim()))
            }
        }
    }

    fun deleteProfile(ip: String, port: Int) {
        viewModelScope.launch {
            repository.deleteByAddress(ip, port)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress ?: ""
                        // Prioritize local subnets
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving IP addresses: ${e.message}")
        }
        return "Not Connected (Check Wi-Fi)"
    }

    fun playSyntheticSound(soundId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val sampleRate = 11025
            val duration = when(soundId) {
                1 -> 0.08 // click
                2 -> 0.4  // wii chime pulse
                else -> 0.35 // laser
            }
            val numSamples = (duration * sampleRate).toInt()
            val samples = ShortArray(numSamples)
            
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                when(soundId) {
                    1 -> { // Click: fading high pitch
                        val envelope = Math.max(0.0, 1.0 - t / duration)
                        samples[i] = (envelope * 24000 * Math.sin(2.0 * Math.PI * 880.0 * t)).toInt().toShort()
                    }
                    2 -> { // Dual tone beep
                        val envelope = Math.max(0.0, 1.0 - t / duration)
                        val value = 0.5 * Math.sin(2.0 * Math.PI * 880.0 * t) + 0.5 * Math.sin(2.0 * Math.PI * 1100.0 * t)
                        samples[i] = (envelope * 22000 * value).toInt().toShort()
                    }
                    else -> { // Retro slide
                        val freq = 1200.0 - (t / duration) * 800.0
                        val envelope = Math.max(0.0, 1.0 - t / duration)
                        samples[i] = (envelope * 20000 * Math.sin(2.0 * Math.PI * freq * t)).toInt().toShort()
                    }
                }
            }
            
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                
                track.write(samples, 0, samples.size)
                track.play()
                delay((duration * 1000 + 100).toLong())
                track.release()
            } catch(e: Exception) {
                Log.e(TAG, "Failed to play synth sound: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDsuServer()
        audioServer?.stop()
        audioServer = null
    }
}
