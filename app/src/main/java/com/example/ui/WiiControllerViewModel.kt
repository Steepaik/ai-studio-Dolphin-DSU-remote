package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.*
import com.example.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.sqrt

class WiiControllerViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val TAG = "WiiControllerVM"

    // Target Database & Repositories
    private val database = WiiControllerDatabase.getDatabase(application)
    private val historyRepository = ConnectionHistoryRepository(database.connectionHistoryDao())
    private val profileRepository = GameProfileRepository(database.gameProfileDao())
    private val crashRepository = CrashReportRepository(database.crashReportDao())

    // Flow lists
    val connectionHistory: StateFlow<List<ConnectionHistoryEntity>> = historyRepository.allHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val gameProfiles: StateFlow<List<GameProfileEntity>> = profileRepository.allProfiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val crashReports: StateFlow<List<CrashReportEntity>> = crashRepository.latestReports.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Hardware Sensors
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Vibrator
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Dynamic Sensor Settings (Persisted or applied in-memory)
    val perAxisMode = MutableStateFlow(false)
    val sensX = MutableStateFlow(1.0f)
    val sensY = MutableStateFlow(1.0f)
    val sensZ = MutableStateFlow(1.0f)
    val motionSmoothing = MutableStateFlow(0.5f) // Low-Pass Filter alpha
    val analogDeadzone = MutableStateFlow(0.05f) // Analog Deadzone 0% to 20%
    val shakeThreshold = MutableStateFlow(1.5f)  // Shake trigger threshold
    val currentVolume = MutableStateFlow(100f)   // Volume scale 0% to 150%

    // Mode Flags
    val isIrModeEnabled = MutableStateFlow(false)
    val isNunchuckEnabled = MutableStateFlow(false)

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

    // Bound Foreground Service Connection
    private var boundService: WiiControllerForegroundService? = null
    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound = _isServiceBound.asStateFlow()

    private val fallbackBtManager by lazy { BluetoothControllerManager(getApplication()) }
    val btManager: BluetoothControllerManager
        get() = boundService?.btManager ?: fallbackBtManager

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

    // Redirected States
    val ipAddress = MutableStateFlow("127.0.0.1")
    val isDsuRunning = MutableStateFlow(false)
    val isAudioRunning = MutableStateFlow(false)
    val isNsdDiscoverable = MutableStateFlow(false)

    // Live Telemetry states
    val registeredClients = MutableStateFlow<List<String>>(emptyList())
    val totalPacketsSent = MutableStateFlow(0)
    val totalPacketsReceived = MutableStateFlow(0)
    val dsuFps = MutableStateFlow(0)
    val audioBytesReceived = MutableStateFlow(0L)
    val audioStatusString = MutableStateFlow("Disconnected")
    val audioWaveform = MutableStateFlow(FloatArray(128))

    // Bluetooth client connection status streams
    val btConnectionState = MutableStateFlow(BtConnectionState.NONE)
    val btRole = MutableStateFlow(BluetoothRole.IDLE)
    val btDeviceName = MutableStateFlow<String?>(null)
    val btClientsList = MutableStateFlow<List<String>>(emptyList())
    val btSignalStrength = MutableStateFlow(3)
    val isReconnecting = MutableStateFlow(false)
    val reconnectAttempt = MutableStateFlow(0)

    // Test Shake indicator
    val isShakeTestDetected = MutableStateFlow(false)

    // UI custom layout customizer
    val layoutPreset = MutableStateFlow("Classic Wii")
    val buttonScale = MutableStateFlow(1.0f)
    val themeColor = MutableStateFlow("Wii Blue")

    // Active Sensor States (for UI preview displays)
    private val _accelState = MutableStateFlow(Triple(0f, 0f, 9.8f))
    val accelState = _accelState.asStateFlow()

    private val _gyroState = MutableStateFlow(Triple(0f, 0f, 0f))
    val gyroState = _gyroState.asStateFlow()

    private var telemetryJob: Job? = null
    private var shakeResetTimerJob: Job? = null
    private var testShakeFlashJob: Job? = null
    private var lastRumbleTime = 0L

    // Gyro Calibration properties
    val motionPlusCalibrating = MutableStateFlow(false)
    val motionPlusCalibrated = MutableStateFlow(false)
    private var gyroBiasX = 0f
    private var gyroBiasY = 0f
    private var gyroBiasZ = 0f
    private var lastRawGyro = Triple(0f, 0f, 0f)

    // Low pass filter history
    private var prevAccX = 0f
    private var prevAccY = 0f
    private var prevAccZ = 9.8f

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WiiControllerForegroundService.LocalBinder
            val s = binder.getService()
            boundService = s
            _isServiceBound.value = true

            // Sync States from Service automatically
            viewModelScope.launch {
                s.isDsuRunning.collect { isDsuRunning.value = it }
            }
            viewModelScope.launch {
                s.isAudioRunning.collect { isAudioRunning.value = it }
            }
            viewModelScope.launch {
                s.nsdDiscoverable.collect { isNsdDiscoverable.value = it }
            }

            s.btManager?.let { btm ->
                viewModelScope.launch { btm.connectionState.collect { btConnectionState.value = it } }
                viewModelScope.launch { btm.role.collect { btRole.value = it } }
                viewModelScope.launch { btm.connectedDeviceName.collect { btDeviceName.value = it } }
                viewModelScope.launch { btm.connectedClientsList.collect { btClientsList.value = it } }
                viewModelScope.launch { btm.signalStrength.collect { btSignalStrength.value = it } }
                viewModelScope.launch { btm.isReconnecting.collect { isReconnecting.value = it } }
                viewModelScope.launch { btm.reconnectAttempt.collect { reconnectAttempt.value = it } }

                // Collect slotted inputs
                viewModelScope.launch {
                    btm.slottedReceivedState.collect { slotted ->
                        if (slotted != null && btm.role.value == BluetoothRole.RECEIVER) {
                            val p = slotted.state
                            val sId = slotted.slotId
                            
                            if (sId == 0) {
                                _accelState.value = Triple(p.accelX, p.accelY, p.accelZ)
                                _gyroState.value = Triple(p.gyroX, p.gyroY, p.gyroZ)
                            }

                            s.dsuServer?.setSlotState(
                                sId,
                                accelX = p.accelX,
                                accelY = p.accelY,
                                accelZ = p.accelZ,
                                gyroX = p.gyroX,
                                gyroY = p.gyroY,
                                gyroZ = p.gyroZ,
                                btnA = p.isBtnPressed(BluetoothControllerManager.BTN_A),
                                btnB = p.isBtnPressed(BluetoothControllerManager.BTN_B),
                                btnMinus = p.isBtnPressed(BluetoothControllerManager.BTN_MINUS),
                                btnPlus = p.isBtnPressed(BluetoothControllerManager.BTN_PLUS),
                                btnHome = p.isBtnPressed(BluetoothControllerManager.BTN_HOME),
                                btn1 = p.isBtnPressed(BluetoothControllerManager.BTN_1),
                                btn2 = p.isBtnPressed(BluetoothControllerManager.BTN_2),
                                btnLeft = p.isBtnPressed(BluetoothControllerManager.BTN_LEFT),
                                btnRight = p.isBtnPressed(BluetoothControllerManager.BTN_RIGHT),
                                btnUp = p.isBtnPressed(BluetoothControllerManager.BTN_UP),
                                btnDown = p.isBtnPressed(BluetoothControllerManager.BTN_DOWN),
                                btnShake = p.isBtnPressed(BluetoothControllerManager.BTN_SHAKE),
                                stickX = p.stickX.toInt(),
                                stickY = p.stickY.toInt(),
                                isConnected = true
                            )
                        }
                    }
                }
            }

            // Sync audio volume setting on change
            viewModelScope.launch {
                currentVolume.collect { vol ->
                    s.audioServer?.volumeScale = vol / 100f
                }
            }

            // Synchronize DSU feature flags
            viewModelScope.launch {
                isIrModeEnabled.collect { ir ->
                    s.dsuServer?.isIrModeEnabled = ir
                }
            }
            viewModelScope.launch {
                isNunchuckEnabled.collect { nun ->
                    s.dsuServer?.isNunchuckEnabled = nun
                }
            }

            // Monitor telemetry
            startTelemetryMonitoring()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            _isServiceBound.value = false
            stopTelemetryMonitoring()
        }
    }

    init {
        // Prepare built-in profiles in Room Database on launch if empty
        viewModelScope.launch {
            if (profileRepository.getCount() == 0) {
                profileRepository.insert(GameProfileEntity("Default", 1.0f, 1.0f, 1.0f, 0.05f, 1.5f, false, 100f, true))
                profileRepository.insert(GameProfileEntity("Precision (Shooters)", 0.6f, 0.6f, 0.6f, 0.10f, 2.5f, true, 80f, true))
                profileRepository.insert(GameProfileEntity("Arcade (Racing/Sports)", 1.8f, 1.8f, 1.8f, 0.02f, 1.0f, false, 120f, true))
            }
        }

        refreshLocalIp()

        // Sync sensor registration transition
        viewModelScope.launch {
            btConnectionState.collect { state ->
                val currentRole = btRole.value
                if (state == BtConnectionState.CONNECTED && currentRole == BluetoothRole.SENDER) {
                    startLocalSensors()
                    triggerVibrationNotification()
                } else if (state == BtConnectionState.NONE && !isDsuRunning.value) {
                    stopLocalSensors()
                }
            }
        }

        // Auto-refresh lists on bind
        val bindIntent = Intent(application, WiiControllerForegroundService::class.java).apply {
            action = "ACTION_BIND_SERVICE"
        }
        application.startService(bindIntent)
        application.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun triggerVibration(durationMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        vibrator?.let { v ->
            if (v.hasVibrator()) {
                try {
                    val targetAmp = amplitude.coerceIn(1, 255)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(durationMs, targetAmp))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(durationMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Vibration exception: ${e.message}")
                }
            }
        }
    }

    fun triggerVibrationNotification() {
        viewModelScope.launch {
            triggerVibration(60, 255)
            delay(100)
            triggerVibration(60, 255)
        }
    }

    fun startDsuServer(port: Int = 26760) {
        boundService?.startDsu(port) { weak, strong ->
            handleIncomingRumble(weak, strong)
        }
        if (btRole.value != BluetoothRole.RECEIVER) {
            startLocalSensors()
        }
        triggerVibrationNotification()
    }

    fun stopDsuServer() {
        boundService?.stopDsu()
        if (btRole.value != BluetoothRole.SENDER) {
            stopLocalSensors()
        }
    }

    fun setNsdEnabled(enabled: Boolean) {
        boundService?.setNsdEnabled(enabled)
    }

    fun toggleAudioServer(port: Int = 26761) {
        val s = boundService ?: return
        if (s.isAudioRunning.value) {
            s.stopAudio()
        } else {
            s.startAudio(port)
            s.audioServer?.volumeScale = currentVolume.value / 100f
            triggerVibration(60, 200)
        }
    }

    fun startLocalSensors() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stopLocalSensors() {
        sensorManager.unregisterListener(this)
        _accelState.value = Triple(0f, 0f, 9.8f)
        _gyroState.value = Triple(0f, 0f, 0f)
    }

    private fun startTelemetryMonitoring() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                boundService?.let { service ->
                    service.dsuServer?.let { dsu ->
                        registeredClients.value = dsu.getClientAddresses()
                        totalPacketsSent.value = dsu.totalPacketsSent
                        totalPacketsReceived.value = dsu.totalPacketsReceived
                        
                        // Collect effective FPS
                        dsuFps.value = dsu.effectiveFps.value
                    }
                    service.audioServer?.let { audio ->
                        audioBytesReceived.value = audio.totalBytesReceived
                        audioStatusString.value = audio.streamStatus.value
                        
                        // Collect copy of waveform float array
                        audioWaveform.value = audio.waveformFlow.value.clone()
                    }
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

                // Apply dynamic Motion Smoothing low-pass filter
                val alpha = motionSmoothing.value
                val filteredX = alpha * x + (1f - alpha) * prevAccX
                val filteredY = alpha * y + (1f - alpha) * prevAccY
                val filteredZ = alpha * z + (1f - alpha) * prevAccZ

                prevAccX = filteredX
                prevAccY = filteredY
                prevAccZ = filteredZ

                _accelState.value = Triple(filteredX, filteredY, filteredZ)

                // Shake detection logic with custom threshold
                val rootAcc = sqrt((filteredX * filteredX + filteredY * filteredY + filteredZ * filteredZ).toDouble()) - 9.80665
                val isShake = rootAcc > (shakeThreshold.value * 5.0f)

                if (isShake) {
                    isShakeTestDetected.value = true
                    triggerTestShakeFlash()
                }

                val bSync = boundService?.btManager
                if (bSync?.role?.value == BluetoothRole.SENDER && bSync.connectionState.value == BtConnectionState.CONNECTED) {
                    bSync.senderAccelX = filteredX
                    bSync.senderAccelY = filteredY
                    bSync.senderAccelZ = filteredZ
                    if (isShake) {
                        bSync.updateSenderButton(BluetoothControllerManager.BTN_SHAKE, true)
                        triggerSenderShakeReset()
                    }
                } else {
                    boundService?.dsuServer?.let { server ->
                        server.accelX = filteredX
                        server.accelY = filteredY
                        server.accelZ = filteredZ
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

                lastRawGyro = Triple(x, y, z)

                // Apply asymmetric or simple multipliers
                val mulX = sensX.value
                val mulY = sensY.value
                val mulZ = sensZ.value

                val adjX = (x - gyroBiasX) * mulX
                val adjY = (y - gyroBiasY) * mulY
                val adjZ = (z - gyroBiasZ) * mulZ

                _gyroState.value = Triple(adjX, adjY, adjZ)

                val bSync = boundService?.btManager
                if (bSync?.role?.value == BluetoothRole.SENDER && bSync.connectionState.value == BtConnectionState.CONNECTED) {
                    bSync.senderGyroX = adjX
                    bSync.senderGyroY = adjY
                    bSync.senderGyroZ = adjZ
                } else {
                    boundService?.dsuServer?.let { server ->
                        server.gyroX = adjX
                        server.gyroY = adjY
                        server.gyroZ = adjZ
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun calibrateMotionPlus() {
        if (motionPlusCalibrating.value) return
        viewModelScope.launch {
            motionPlusCalibrating.value = true
            motionPlusCalibrated.value = false
            triggerVibration(100, 150)

            val sX = mutableListOf<Float>()
            val sY = mutableListOf<Float>()
            val sZ = mutableListOf<Float>()

            for (i in 0 until 15) {
                sX.add(lastRawGyro.first)
                sY.add(lastRawGyro.second)
                sZ.add(lastRawGyro.third)
                delay(100)
            }

            gyroBiasX = sX.average().toFloat()
            gyroBiasY = sY.average().toFloat()
            gyroBiasZ = sZ.average().toFloat()

            motionPlusCalibrating.value = false
            motionPlusCalibrated.value = true
            triggerVibrationNotification()
        }
    }

    private fun triggerTestShakeFlash() {
        testShakeFlashJob?.cancel()
        testShakeFlashJob = viewModelScope.launch {
            delay(300)
            isShakeTestDetected.value = false
        }
    }

    private fun triggerShakeResetTimer() {
        shakeResetTimerJob?.cancel()
        shakeResetTimerJob = viewModelScope.launch {
            delay(150)
            boundService?.dsuServer?.buttonShake = false
        }
    }

    private fun triggerSenderShakeReset() {
        viewModelScope.launch {
            delay(150)
            boundService?.btManager?.updateSenderButton(BluetoothControllerManager.BTN_SHAKE, false)
        }
    }

    private fun handleIncomingRumble(weak: Int, strong: Int) {
        val now = System.currentTimeMillis()
        if (weak > 0 || strong > 0) {
            val maxBound = weak.coerceAtLeast(strong)
            val amplitude = maxBound.coerceIn(0, 255)
            if (now - lastRumbleTime > 25) {
                triggerVibration(35, amplitude)
                lastRumbleTime = now
            }
        }
    }

    fun onButtonPressed(button: String, isPressed: Boolean) {
        val destination = buttonMappings.value[button.uppercase()] ?: button.uppercase()

        if (isPressed) {
            triggerVibration(25, 120)
        }

        val bSync = boundService?.btManager
        if (bSync?.role?.value == BluetoothRole.SENDER && bSync.connectionState.value == BtConnectionState.CONNECTED) {
            val mask = when (destination) {
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
                bSync.updateSenderButton(mask, isPressed)
            }
            return
        }

        boundService?.dsuServer?.let { dsu ->
            when (destination) {
                "A" -> dsu.buttonA = isPressed
                "B" -> dsu.buttonB = isPressed
                "MINUS" -> dsu.buttonMinus = isPressed
                "PLUS" -> dsu.buttonPlus = isPressed
                "HOME" -> dsu.buttonHome = isPressed
                "ONE" -> dsu.button1 = isPressed
                "TWO" -> dsu.button2 = isPressed
                "UP" -> dsu.buttonUp = isPressed
                "RIGHT" -> dsu.buttonRight = isPressed
                "DOWN" -> dsu.buttonDown = isPressed
                "LEFT" -> dsu.buttonLeft = isPressed
                "SHAKE" -> dsu.buttonShake = isPressed
            }
        }
    }

    fun onStickMoved(x: Float, y: Float) {
        val dz = analogDeadzone.value
        val originMagnitude = sqrt(x * x + y * y)

        val processedX = if (originMagnitude < dz) 0f else x
        val processedY = if (originMagnitude < dz) 0f else y

        val finalByteX = (processedX * 127f).toInt().coerceIn(-128, 127).toByte()
        val finalByteY = (processedY * 127f).toInt().coerceIn(-128, 127).toByte()

        val bSync = boundService?.btManager
        if (bSync?.role?.value == BluetoothRole.SENDER && bSync.connectionState.value == BtConnectionState.CONNECTED) {
            bSync.senderStickX = finalByteX
            bSync.senderStickY = finalByteY
            return
        }

        boundService?.dsuServer?.let { dsu ->
            dsu.stickX = finalByteX.toInt()
            dsu.stickY = finalByteY.toInt()
        }
    }

    // Profiles persistence management
    fun saveProfile(name: String, sx: Float, sy: Float, sz: Float, dz: Float, st: Float, ir: Boolean, vol: Float) {
        viewModelScope.launch {
            if (name.isNotBlank()) {
                val profile = GameProfileEntity(
                    name = name.trim(),
                    sensX = sx,
                    sensY = sy,
                    sensZ = sz,
                    deadzone = dz,
                    shakeThreshold = st,
                    irModeEnabled = ir,
                    audioVolume = vol,
                    isBuiltIn = false
                )
                profileRepository.insert(profile)
            }
        }
    }

    fun applyProfile(profile: GameProfileEntity) {
        sensX.value = profile.sensX
        sensY.value = profile.sensY
        sensZ.value = profile.sensZ
        analogDeadzone.value = profile.deadzone
        shakeThreshold.value = profile.shakeThreshold
        isIrModeEnabled.value = profile.irModeEnabled
        currentVolume.value = profile.audioVolume
        triggerVibration(100, 200)
    }

    fun deleteProfile(name: String) {
        viewModelScope.launch {
            profileRepository.delete(name)
        }
    }

    // Session History
    fun saveConnectionHistory(ip: String, port: Int, description: String) {
        viewModelScope.launch {
            if (ip.isNotBlank()) {
                historyRepository.insert(
                    ConnectionHistoryEntity(
                        ipAddress = ip.trim(),
                        port = port,
                        description = description.trim()
                    )
                )
            }
        }
    }

    fun deleteHistory(ip: String, port: Int) {
        viewModelScope.launch {
            historyRepository.deleteByAddress(ip, port)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
        }
    }

    // Crash Log clear
    fun clearCrashReports() {
        viewModelScope.launch {
            crashRepository.clear()
        }
    }

    fun refreshLocalIp() {
        viewModelScope.launch {
            ipAddress.value = getLocalIpAddress()
        }
    }

    fun getLocalIpAddress(): String {
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
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {}
        telemetryJob?.cancel()
    }
}
