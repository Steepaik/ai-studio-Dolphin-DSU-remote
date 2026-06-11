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
import com.example.network.BluetoothControllerManager
import com.example.network.BluetoothRole
import com.example.network.BtConnectionState
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

    // Bluetooth manager instances
    val btManager = BluetoothControllerManager(application)

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

    // Layout configuration variables (Customizable section)
    val layoutPreset = MutableStateFlow("Classic Wii") // Options: "Classic Wii", "Horizontal Gamepad", "Big Buttons"
    val buttonScale = MutableStateFlow(1.0f) // Scale multiplier: 0.8f, 1.0f, 1.2f, 1.5f
    val themeColor = MutableStateFlow("Wii Blue") // Options: "Wii Blue", "Carbon Grey", "Nintendo Red", "Teal Fusion"

    // Dynamic configurable Button Mapping Map
    val buttonMappings = MutableStateFlow<Map<String, String>>(
        mapOf(
            "A" to "A",
            "B" to "B",
            "MINUS" to "MINUS",
            "PLUS" to "PLUS",
            "HOME" to "HOME",
            "ONE" to "ONE",
            "TWO" to "TWO",
            "UP" to "UP",
            "DOWN" to "DOWN",
            "LEFT" to "LEFT",
            "RIGHT" to "RIGHT"
        )
    )

    // Sensor state flows for visual overlay feedback
    private val _accelState = MutableStateFlow(Triple(0f, 0f, 10f))
    val accelState: StateFlow<Triple<Float, Float, Float>> = _accelState

    private val _gyroState = MutableStateFlow(Triple(0f, 0f, 0f))
    val gyroState: StateFlow<Triple<Float, Float, Float>> = _gyroState

    private var telemetryJob: Job? = null
    private var shakeResetJob: Job? = null
    private var senderShakeResetJob: Job? = null
    private var lastRumbleTime = 0L

    // Wii MotionPlus variables
    val motionPlusCalibrated = MutableStateFlow(false)
    val motionPlusCalibrating = MutableStateFlow(false)
    val motionPlusSensitivity = MutableStateFlow(1.0f) // Sensitivity multiplier: 0.5f (Slow), 1.0f (Standard), 2.0f (Fast)
    
    private var gyroBiasX = 0f
    private var gyroBiasY = 0f
    private var gyroBiasZ = 0f
    private var lastRawGyro = Triple(0f, 0f, 0f)

    fun calibrateMotionPlus() {
        if (motionPlusCalibrating.value) return
        viewModelScope.launch {
            motionPlusCalibrating.value = true
            motionPlusCalibrated.value = false
            triggerVibration(100, 150)
            
            val samplesX = mutableListOf<Float>()
            val samplesY = mutableListOf<Float>()
            val samplesZ = mutableListOf<Float>()
            
            for (i in 0 until 15) {
                samplesX.add(lastRawGyro.first)
                samplesY.add(lastRawGyro.second)
                samplesZ.add(lastRawGyro.third)
                delay(100) // 1.5 seconds total calibration window
            }
            
            if (samplesX.isNotEmpty()) {
                gyroBiasX = samplesX.average().toFloat()
                gyroBiasY = samplesY.average().toFloat()
                gyroBiasZ = samplesZ.average().toFloat()
            }
            
            motionPlusCalibrating.value = false
            motionPlusCalibrated.value = true
            triggerVibrationNotification()
        }
    }

    fun launchDolphinApp(context: Context): Boolean {
        listOf(
            "org.dolphinemu.dolphinemu",
            "org.dolphinemu.dolphinemu.debug",
            "org.dolphinemu.dolphinemu.canary"
        ).forEach { packageName ->
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                // Keep looking
            }
        }
        return false
    }

    init {
        refreshLocalIp()

        // Sync incoming slotted bluetooth packets on receiver to DSU server
        viewModelScope.launch {
            btManager.slottedReceivedState.collect { slotted ->
                if (slotted != null && btManager.role.value == BluetoothRole.RECEIVER) {
                    val packed = slotted.state
                    val slotId = slotted.slotId
                    
                    if (slotId == 0) {
                        _accelState.value = Triple(packed.accelX, packed.accelY, packed.accelZ)
                        _gyroState.value = Triple(packed.gyroX, packed.gyroY, packed.gyroZ)
                    }

                    // Inject values to local DSU server on the slotted index
                    dsuServer?.let { server ->
                        server.setSlotState(
                            slotId,
                            accelX = packed.accelX,
                            accelY = packed.accelY,
                            accelZ = packed.accelZ,
                            gyroX = packed.gyroX,
                            gyroY = packed.gyroY,
                            gyroZ = packed.gyroZ,
                            btnA = packed.isBtnPressed(BluetoothControllerManager.BTN_A),
                            btnB = packed.isBtnPressed(BluetoothControllerManager.BTN_B),
                            btnMinus = packed.isBtnPressed(BluetoothControllerManager.BTN_MINUS),
                            btnPlus = packed.isBtnPressed(BluetoothControllerManager.BTN_PLUS),
                            btnHome = packed.isBtnPressed(BluetoothControllerManager.BTN_HOME),
                            btn1 = packed.isBtnPressed(BluetoothControllerManager.BTN_1),
                            btn2 = packed.isBtnPressed(BluetoothControllerManager.BTN_2),
                            btnLeft = packed.isBtnPressed(BluetoothControllerManager.BTN_LEFT),
                            btnRight = packed.isBtnPressed(BluetoothControllerManager.BTN_RIGHT),
                            btnUp = packed.isBtnPressed(BluetoothControllerManager.BTN_UP),
                            btnDown = packed.isBtnPressed(BluetoothControllerManager.BTN_DOWN),
                            btnShake = packed.isBtnPressed(BluetoothControllerManager.BTN_SHAKE),
                            stickX = packed.stickX.toInt(),
                            stickY = packed.stickY.toInt(),
                            isConnected = true
                        )
                    }
                }
            }
        }

        // Trigger sensor activation/deactivation during connection transitions
        viewModelScope.launch {
            btManager.connectionState.collect { state ->
                if (state == BtConnectionState.CONNECTED && btManager.role.value == BluetoothRole.SENDER) {
                    startSensors()
                    triggerVibrationNotification()
                } else if (state == BtConnectionState.NONE) {
                    // Reset sensors if DSU is not running
                    if (!isDsuRunning.value) {
                        stopSensors()
                    }
                }
            }
        }
    }

    private fun ConnectionHistoryRepository.allItemsFlow(): StateFlow<List<ConnectionHistoryEntity>> {
        return allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun updateButtonMapping(source: String, destination: String) {
        val current = buttonMappings.value.toMutableMap()
        current[source.uppercase()] = destination.uppercase()
        buttonMappings.value = current
    }

    fun resetButtonMappings() {
        buttonMappings.value = mapOf(
            "A" to "A",
            "B" to "B",
            "MINUS" to "MINUS",
            "PLUS" to "PLUS",
            "HOME" to "HOME",
            "ONE" to "ONE",
            "TWO" to "TWO",
            "UP" to "UP",
            "DOWN" to "DOWN",
            "LEFT" to "LEFT",
            "RIGHT" to "RIGHT"
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
        
        // Start physical sensors only if Receiver mode is not using peer data
        if (btManager.role.value != BluetoothRole.RECEIVER) {
            startSensors()
        }
        startTelemetryMonitoring()
        triggerVibrationNotification()
    }

    fun stopDsuServer() {
        dsuServer?.stop()
        dsuServer = null
        isDsuRunning.value = false
        if (btManager.role.value != BluetoothRole.SENDER) {
            stopSensors()
        }
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

                _accelState.value = Triple(x, y, z)

                // Shake detection logic (Wii Remote style shaking)
                val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()) - 9.80665
                val isShake = magnitude > 6.0

                if (btManager.role.value == BluetoothRole.SENDER && btManager.connectionState.value == BtConnectionState.CONNECTED) {
                    btManager.senderAccelX = x
                    btManager.senderAccelY = y
                    btManager.senderAccelZ = z
                    if (isShake) {
                        btManager.updateSenderButton(BluetoothControllerManager.BTN_SHAKE, true)
                        triggerSenderShakeReset()
                    }
                } else {
                    dsuServer?.let { server ->
                        server.accelX = x
                        server.accelY = y
                        server.accelZ = z
                        if (isShake) {
                            server.buttonShake = true
                            triggerShakeResetTimer()
                        }
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Save raw value for calibration purposes
                lastRawGyro = Triple(x, y, z)

                // Apply MotionPlus calibration bias and multiplier sensitivity
                val adjX = (x - gyroBiasX) * motionPlusSensitivity.value
                val adjY = (y - gyroBiasY) * motionPlusSensitivity.value
                val adjZ = (z - gyroBiasZ) * motionPlusSensitivity.value

                _gyroState.value = Triple(adjX, adjY, adjZ)

                if (btManager.role.value == BluetoothRole.SENDER && btManager.connectionState.value == BtConnectionState.CONNECTED) {
                    btManager.senderGyroX = adjX
                    btManager.senderGyroY = adjY
                    btManager.senderGyroZ = adjZ
                } else {
                    dsuServer?.let { server ->
                        server.gyroX = adjX
                        server.gyroY = adjY
                        server.gyroZ = adjZ
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerShakeResetTimer() {
        shakeResetJob?.cancel()
        shakeResetJob = viewModelScope.launch {
            try {
                delay(150)
                dsuServer?.buttonShake = false
            } catch (e: Exception) {}
        }
    }

    private fun triggerSenderShakeReset() {
        senderShakeResetJob?.cancel()
        senderShakeResetJob = viewModelScope.launch {
            try {
                delay(150)
                btManager.updateSenderButton(BluetoothControllerManager.BTN_SHAKE, false)
            } catch (e: Exception) {}
        }
    }

    private fun handleIncomingRumble(weak: Int, strong: Int) {
        val now = System.currentTimeMillis()
        if (weak > 0 || strong > 0) {
            val maxStrength = Math.max(weak, strong)
            val amplitude = maxStrength.coerceIn(0, 255)
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
        viewModelScope.launch {
            triggerVibration(60, 255)
            delay(100)
            triggerVibration(60, 255)
        }
    }

    // Input actions mapped dynamically from physical screen taps
    fun onButtonPressed(button: String, isPressed: Boolean) {
        val mappedButton = buttonMappings.value[button.uppercase()] ?: button.uppercase()

        if (isPressed) {
            triggerVibration(25, 120) // crisp touch feedback
        }

        // If we connected Bluetooth as SENDER, update the outgoing state
        if (btManager.role.value == BluetoothRole.SENDER && btManager.connectionState.value == BtConnectionState.CONNECTED) {
            val mask = when (mappedButton) {
                "A" -> BluetoothControllerManager.BTN_A
                "B" -> BluetoothControllerManager.BTN_B
                "MINUS" -> BluetoothControllerManager.BTN_MINUS
                "PLUS" -> BluetoothControllerManager.BTN_PLUS
                "HOME" -> BluetoothControllerManager.BTN_HOME
                "ONE" -> BluetoothControllerManager.BTN_1
                "TWO" -> BluetoothControllerManager.BTN_2
                "UP" -> BluetoothControllerManager.BTN_UP
                "DOWN" -> BluetoothControllerManager.BTN_DOWN
                "LEFT" -> BluetoothControllerManager.BTN_LEFT
                "RIGHT" -> BluetoothControllerManager.BTN_RIGHT
                "SHAKE" -> BluetoothControllerManager.BTN_SHAKE
                else -> 0
            }
            if (mask != 0) {
                btManager.updateSenderButton(mask, isPressed)
            }
            return
        }

        // Otherwise write onto the local active DSU instance
        dsuServer?.let { server ->
            when (mappedButton) {
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
        val sX = (x * 127f).toInt().coerceIn(-128, 127).toByte()
        val sY = (y * 127f).toInt().coerceIn(-128, 127).toByte()

        if (btManager.role.value == BluetoothRole.SENDER && btManager.connectionState.value == BtConnectionState.CONNECTED) {
            btManager.senderStickX = sX
            btManager.senderStickY = sY
            return
        }

        dsuServer?.let { server ->
            server.stickX = sX.toInt()
            server.stickY = sY.toInt()
        }
    }

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
                1 -> 0.08
                2 -> 0.4
                else -> 0.35
            }
            val numSamples = (duration * sampleRate).toInt()
            val samples = ShortArray(numSamples)
            
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                when(soundId) {
                    1 -> {
                        val envelope = Math.max(0.0, 1.0 - t / duration)
                        samples[i] = (envelope * 24000 * Math.sin(2.0 * Math.PI * 880.0 * t)).toInt().toShort()
                    }
                    2 -> {
                        val envelope = Math.max(0.0, 1.0 - t / duration)
                        val value = 0.5 * Math.sin(2.0 * Math.PI * 880.0 * t) + 0.5 * Math.sin(2.0 * Math.PI * 1100.0 * t)
                        samples[i] = (envelope * 22000 * value).toInt().toShort()
                    }
                    else -> {
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
        btManager.stopAll()
        audioServer?.stop()
        audioServer = null
    }
}
