package com.example.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class BluetoothRole {
    IDLE, SENDER, RECEIVER, HID_GAMEPAD
}

enum class BtConnectionState {
    NONE, LISTENING, CONNECTING, CONNECTED, ERROR
}

data class PackedState(
    val buttons: Int,
    val stickX: Byte,
    val stickY: Byte,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float
) {
    fun isBtnPressed(mask: Int): Boolean = (buttons and mask) != 0
}

data class SlottedPackedState(
    val slotId: Int,
    val state: PackedState
)

data class BluetoothClientConn(
    val address: String,
    val name: String,
    val socket: BluetoothSocket,
    val slotId: Int,
    var receiveJob: Job? = null
)

class BluetoothControllerManager(private val context: Context) {
    private val TAG = "BtControllerManager"
    
    private val APP_UUID = UUID.fromString("1f8bd4b2-0382-4aa8-a53b-fde5bc63ee28")
    private val APP_NAME = "WiiControllerBluetooth"

    private var hidDevice: BluetoothProfile? = null
    var activeHidHost: BluetoothDevice? = null
        private set
    var isHidRegistered = false
        private set
    private val hidExecutor = Executors.newSingleThreadExecutor()

    private val HID_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x05.toByte(), // USAGE (Gamepad)
        0xa1.toByte(), 0x01.toByte(), // COLLECTION (Application)
        
        // Buttons (16 buttons)
        0x85.toByte(), 0x01.toByte(), //   REPORT_ID (1)
        0x05.toByte(), 0x09.toByte(), //   USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(), //   USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x10.toByte(), //   USAGE_MAXIMUM (Button 16)
        0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(), //   LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
        0x95.toByte(), 0x10.toByte(), //   REPORT_COUNT (16)
        0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs)
        
        // Joysticks / Axial controls (X, Y, Z, Rz)
        0x05.toByte(), 0x01.toByte(), //   USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //   USAGE (X)
        0x09.toByte(), 0x31.toByte(), //   USAGE (Y)
        0x09.toByte(), 0x32.toByte(), //   USAGE (Z)
        0x09.toByte(), 0x35.toByte(), //   USAGE (Rz)
        0x15.toByte(), 0x81.toByte(), //   LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7f.toByte(), //   LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
        0x95.toByte(), 0x04.toByte(), //   REPORT_COUNT (4)
        0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs)
        
        0xc0.toByte()                 // END_COLLECTION
    )

    private val hidCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                Log.d("BtControllerManager", "onAppStatusChanged: registered=$registered, device=${pluggedDevice?.name}")
                isHidRegistered = registered
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                Log.d("BtControllerManager", "onConnectionStateChanged: device=${device?.name}, state=$state")
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    activeHidHost = device
                    _connectionState.value = BtConnectionState.CONNECTED
                    _connectedDeviceName.value = device?.name ?: "HID Host"
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (activeHidHost?.address == device?.address) {
                        activeHidHost = null
                        if (_role.value == BluetoothRole.HID_GAMEPAD) {
                            _connectionState.value = BtConnectionState.LISTENING
                        } else {
                            _connectionState.value = BtConnectionState.NONE
                        }
                        _connectedDeviceName.value = null
                    }
                }
            }

            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {}
            override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {}
            override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {}
            override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {}
        }
    } else {
        null
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy
                Log.d("BtControllerManager", "Bluetooth HID Device Proxy connected successfully")
                if (_role.value == BluetoothRole.HID_GAMEPAD) {
                    registerHidApp()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
            }
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
            }
        } catch (e: Exception) {
            Log.e("BtControllerManager", "Failed to get Bluetooth HID_DEVICE profile proxy: ${e.message}")
        }
    }

    private val _connectionState = MutableStateFlow(BtConnectionState.NONE)
    val connectionState = _connectionState.asStateFlow()

    private val _role = MutableStateFlow(BluetoothRole.IDLE)
    val role = _role.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices = _pairedDevices.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    // Resilient Connection States
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt = _reconnectAttempt.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting = _isReconnecting.asStateFlow()

    // Estimated connection quality signal strength bars (1 to 3)
    private val _signalStrength = MutableStateFlow(3)
    val signalStrength = _signalStrength.asStateFlow()

    // Legacy support for single-device charts
    private val _receivedState = MutableStateFlow<PackedState?>(null)
    val receivedState = _receivedState.asStateFlow()

    // Multi-device active connected clients list
    val activeClients = ConcurrentHashMap<String, BluetoothClientConn>()

    private val _connectedClientsList = MutableStateFlow<List<String>>(emptyList())
    val connectedClientsList = _connectedClientsList.asStateFlow()

    private val _slottedReceivedState = MutableStateFlow<SlottedPackedState?>(null)
    val slottedReceivedState = _slottedReceivedState.asStateFlow()

    // Diagnostics telemetry
    private val _bytesTransmitted = MutableStateFlow(0L)
    val bytesTransmitted = _bytesTransmitted.asStateFlow()

    private val _btFps = MutableStateFlow(0)
    val btFps = _btFps.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    
    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var workerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var activeOutStream: OutputStream? = null

    // Controller input bitmasks
    companion object {
        const val BTN_LEFT = 1 shl 0
        const val BTN_RIGHT = 1 shl 1
        const val BTN_UP = 1 shl 2
        const val BTN_DOWN = 1 shl 3
        const val BTN_A = 1 shl 4
        const val BTN_B = 1 shl 5
        const val BTN_MINUS = 1 shl 6
        const val BTN_PLUS = 1 shl 7
        const val BTN_HOME = 1 shl 8
        const val BTN_1 = 1 shl 9
        const val BTN_2 = 1 shl 10
        const val BTN_SHAKE = 1 shl 11
    }

    private fun updateConnectedClientsList() {
        _connectedClientsList.value = activeClients.values.map { "Slot ${it.slotId + 1}: ${it.name}" }
        _connectedDeviceName.value = activeClients.values.joinToString(", ") { "${it.name} (P${it.slotId + 1})" }.ifEmpty { null }
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        if (adapter.isEnabled) {
            _pairedDevices.value = adapter.bondedDevices.toList()
        } else {
            _pairedDevices.value = emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startReceiverServer() {
        stopAll()
        _role.value = BluetoothRole.RECEIVER
        _connectionState.value = BtConnectionState.LISTENING

        workerScope.launch(Dispatchers.IO) {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                _connectionState.value = BtConnectionState.ERROR
                return@launch
            }

            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                Log.i(TAG, "Bluetooth Server listening on UUID: $APP_UUID")
                
                while (activeRole() == BluetoothRole.RECEIVER) {
                    try {
                        val socket = serverSocket?.accept(10000) 
                        if (socket != null) {
                            val address = socket.remoteDevice.address
                            val name = socket.remoteDevice.name ?: "Remote Controller"
                            
                            val occupiedSlots = activeClients.values.map { it.slotId }
                            var assignedSlot = -1
                            
                            // Check slots 1 to 3 first (P2, P3, P4)
                            for (sId in 1..3) {
                                if (sId !in occupiedSlots) {
                                    assignedSlot = sId
                                    break
                                }
                            }
                            
                            // Fallback to slot 0 if P2 to P4 are full
                            if (assignedSlot == -1 && 0 !in occupiedSlots) {
                                assignedSlot = 0
                            }

                            if (assignedSlot != -1) {
                                activeClients[address]?.let { old ->
                                    try { old.socket.close() } catch (e: Exception) {}
                                    old.receiveJob?.cancel()
                                }

                                val conn = BluetoothClientConn(address, name, socket, assignedSlot)
                                activeClients[address] = conn
                                _connectionState.value = BtConnectionState.CONNECTED
                                
                                startClientReceiveLoop(conn)
                                updateConnectedClientsList()
                                Log.i(TAG, "Successfully paired multi-player: $name on Slot P${assignedSlot + 1}")
                            } else {
                                Log.w(TAG, "Rejecting connection from $name - all slots are currently filled.")
                                try { socket.close() } catch (e: Exception) {}
                            }
                        }
                    } catch (e: IOException) {
                        // Accept timeout check
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket failed: ${e.message}")
                if (activeRole() == BluetoothRole.RECEIVER) {
                    _connectionState.value = BtConnectionState.ERROR
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startSenderMode() {
        stopAll()
        _role.value = BluetoothRole.SENDER
        _connectionState.value = BtConnectionState.NONE
        refreshPairedDevices()
    }

    @SuppressLint("MissingPermission")
    fun startHidGamepadMode() {
        stopAll()
        _role.value = BluetoothRole.HID_GAMEPAD
        _connectionState.value = BtConnectionState.LISTENING
        refreshPairedDevices()
        registerHidApp()
    }

    @SuppressLint("MissingPermission")
    fun registerHidApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = hidDevice as? BluetoothHidDevice ?: return
        if (isHidRegistered) return

        try {
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                "WiiMote Emulated Gamepad",
                "Emulating standard game controller input with motion tilt",
                "Nintendo Emulators Inc",
                0x24.toByte(), // Subclass: Gamepad Profile Device subclass identifier
                HID_DESCRIPTOR
            )

            val registered = device.registerApp(
                sdpSettings,
                null,
                null,
                hidExecutor,
                hidCallback as? BluetoothHidDevice.Callback
            )
            Log.d("BtControllerManager", "registerApp called, success status = $registered")
            isHidRegistered = registered
        } catch (e: Exception) {
            Log.e("BtControllerManager", "Failed to register HID application: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun unregisterHidApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = hidDevice as? BluetoothHidDevice ?: return
        if (!isHidRegistered) return

        try {
            device.unregisterApp()
            isHidRegistered = false
            Log.d("BtControllerManager", "unregisterApp called")
        } catch (e: Exception) {
            Log.e("BtControllerManager", "Failed to unregister HID application: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendHidReport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = hidDevice as? BluetoothHidDevice ?: return
        val host = activeHidHost ?: return

        try {
            val reportData = ByteArray(6) // 16 buttons (2 bytes) + 4 axes (4 bytes)
            
            // Buttons Byte 0-1
            var btns = 0
            if ((senderButtons and BTN_A) != 0) btns = btns or (1 shl 0)
            if ((senderButtons and BTN_B) != 0) btns = btns or (1 shl 1)
            if ((senderButtons and BTN_1) != 0) btns = btns or (1 shl 2)
            if ((senderButtons and BTN_2) != 0) btns = btns or (1 shl 3)
            if ((senderButtons and BTN_PLUS) != 0) btns = btns or (1 shl 4)
            if ((senderButtons and BTN_MINUS) != 0) btns = btns or (1 shl 5)
            if ((senderButtons and BTN_HOME) != 0) btns = btns or (1 shl 6)
            if ((senderButtons and BTN_SHAKE) != 0) btns = btns or (1 shl 7)
            
            if ((senderButtons and BTN_LEFT) != 0) btns = btns or (1 shl 8)
            if ((senderButtons and BTN_RIGHT) != 0) btns = btns or (1 shl 9)
            if ((senderButtons and BTN_UP) != 0) btns = btns or (1 shl 10)
            if ((senderButtons and BTN_DOWN) != 0) btns = btns or (1 shl 11)
            
            reportData[0] = (btns and 0xFF).toByte()
            reportData[1] = ((btns shr 8) and 0xFF).toByte()
            
            // Axes: X, Y (Nunchuk analog)
            reportData[2] = senderStickX
            reportData[3] = senderStickY
            
            // Axes: Z, Rz (Tilt equivalent mapped dynamically to physical tilt)
            val mappedZ = (senderAccelX * 12.0f).toInt().coerceIn(-127, 127).toByte()
            val mappedRz = (senderAccelY * 12.0f).toInt().coerceIn(-127, 127).toByte()
            
            reportData[4] = mappedZ
            reportData[5] = mappedRz
            
            device.sendReport(host, 1, reportData)
            
            _bytesTransmitted.value = _bytesTransmitted.value + reportData.size
        } catch (e: Exception) {
            Log.e("BtControllerManager", "Error transmitting HID report packet: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectAsSender(device: BluetoothDevice) {
        stopAll()
        _role.value = BluetoothRole.SENDER
        _connectionState.value = BtConnectionState.CONNECTING
        _connectedDeviceName.value = device.name ?: "Remote Receiver"

        workerScope.launch(Dispatchers.IO) {
            var attempt = 0
            var connected = false
            var delayMs = 1000L

            while (attempt < 10 && activeRole() == BluetoothRole.SENDER && !connected) {
                try {
                    _reconnectAttempt.value = attempt + 1
                    if (attempt > 0) {
                        _isReconnecting.value = true
                    }
                    
                    val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                    bluetoothAdapter?.cancelDiscovery() 
                    socket.connect()
                    
                    clientSocket = socket
                    activeOutStream = socket.outputStream
                    _connectionState.value = BtConnectionState.CONNECTED
                    _isReconnecting.value = false
                    _reconnectAttempt.value = 0
                    startSendLoop()
                    connected = true
                    Log.i(TAG, "Connected to receiver device over Bluetooth RFCOMM")
                } catch (e: Exception) {
                    attempt++
                    Log.e(TAG, "Sender connect attempt $attempt failed: ${e.message}")
                    if (attempt >= 10) {
                        _connectionState.value = BtConnectionState.ERROR
                        _role.value = BluetoothRole.IDLE
                        _isReconnecting.value = false
                    } else {
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(60000L) // Exponential step
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClientReceiveLoop(conn: BluetoothClientConn) {
        val job = workerScope.launch(Dispatchers.IO) {
            val inputStream = conn.socket.inputStream
            var bytesReadTotal = 0L
            var fpsCounter = 0
            var lastTimer = System.currentTimeMillis()

            while (isActive && activeRole() == BluetoothRole.RECEIVER) {
                try {
                    val b1 = inputStream.read()
                    if (b1 == -1) throw IOException("Remote socket closed")
                    if (b1.toByte() != BluetoothProtocol.SYNC_HEADER_1) {
                        continue
                    }
                    val b2 = inputStream.read()
                    if (b2 == -1) throw IOException("Remote socket closed")
                    if (b2.toByte() != BluetoothProtocol.SYNC_HEADER_2) {
                        continue
                    }

                    val body = ByteArray(BluetoothProtocol.BODY_SIZE)
                    var bodyPointer = 0
                    while (bodyPointer < BluetoothProtocol.BODY_SIZE && isActive) {
                        val bVal = inputStream.read()
                        if (bVal == -1) throw IOException("Remote socket closed")
                        body[bodyPointer] = bVal.toByte()
                        bodyPointer++
                    }

                    val packet = ByteArray(BluetoothProtocol.PACKET_SIZE)
                    packet[0] = BluetoothProtocol.SYNC_HEADER_1
                    packet[1] = BluetoothProtocol.SYNC_HEADER_2
                    System.arraycopy(body, 0, packet, 2, BluetoothProtocol.BODY_SIZE)

                    val state = unpackState(packet)
                    if (state != null) {
                        _slottedReceivedState.value = SlottedPackedState(conn.slotId, state)
                        _receivedState.value = state
                        
                        bytesReadTotal += BluetoothProtocol.PACKET_SIZE
                        _bytesTransmitted.value = bytesReadTotal
                        fpsCounter++

                        val now = System.currentTimeMillis()
                        if (now - lastTimer >= 1000) {
                            _btFps.value = fpsCounter
                            
                            // Dynamically set RSSI signal quality bars based on packet latency consistency
                            _signalStrength.value = if (fpsCounter > 80) 3 else if (fpsCounter > 40) 2 else 1
                            fpsCounter = 0
                            lastTimer = now
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Disconnect/Error in receiver client ${conn.name} on Slot P${conn.slotId + 1}: ${e.message}")
                    activeClients.remove(conn.address)
                    try { conn.socket.close() } catch (ex: Exception) {}
                    updateConnectedClientsList()
                    if (activeClients.isEmpty()) {
                        _connectionState.value = BtConnectionState.NONE
                    }
                    break
                }
            }
        }
        conn.receiveJob = job
    }

    private fun startSendLoop() {
        sendJob = workerScope.launch(Dispatchers.IO) {
            var sentBytesTotal = 0L
            var fpsCounter = 0
            var lastTimer = System.currentTimeMillis()

            while (isActive) {
                try {
                    val out = activeOutStream ?: throw IOException("Output stream is closed")
                    val frame = currentSenderFrame()
                    out.write(frame)
                    out.flush()

                    sentBytesTotal += BluetoothProtocol.PACKET_SIZE
                    _bytesTransmitted.value = sentBytesTotal
                    fpsCounter++

                    val now = System.currentTimeMillis()
                    if (now - lastTimer >= 1000) {
                        _btFps.value = fpsCounter
                        fpsCounter = 0
                        lastTimer = now
                    }

                    delay(10) // 100Hz
                } catch (e: Exception) {
                    Log.e(TAG, "Error in bluetooth send loop: ${e.message}")
                    _connectionState.value = BtConnectionState.NONE
                    _role.value = BluetoothRole.IDLE
                    _connectedDeviceName.value = null
                    break
                }
            }
        }
    }

    var senderButtons = 0
    var senderStickX: Byte = 0
    var senderStickY: Byte = 0
    var senderAccelX = 0f
    var senderAccelY = 0f
    var senderAccelZ = 9.8f
    var senderGyroX = 0f
    var senderGyroY = 0f
    var senderGyroZ = 0f

    private fun currentSenderFrame(): ByteArray {
        val buffer = ByteBuffer.allocate(BluetoothProtocol.PACKET_SIZE)
        buffer.put(BluetoothProtocol.SYNC_HEADER_1)
        buffer.put(BluetoothProtocol.SYNC_HEADER_2)
        buffer.putShort(senderButtons.toShort())
        buffer.put(senderStickX)
        buffer.put(senderStickY)
        buffer.putFloat(senderAccelX)
        buffer.putFloat(senderAccelY)
        buffer.putFloat(senderAccelZ)
        buffer.putFloat(senderGyroX)
        buffer.putFloat(senderGyroY)
        buffer.putFloat(senderGyroZ)
        return buffer.array()
    }

    private fun unpackState(data: ByteArray): PackedState? {
        if (data.size < BluetoothProtocol.PACKET_SIZE) return null
        val buffer = ByteBuffer.wrap(data)
        val h1 = buffer.get()
        val h2 = buffer.get()
        if (h1 != BluetoothProtocol.SYNC_HEADER_1 || h2 != BluetoothProtocol.SYNC_HEADER_2) return null
        val buttons = buffer.short.toInt() and 0xFFFF
        val stickX = buffer.get()
        val stickY = buffer.get()
        val accelX = buffer.getFloat()
        val accelY = buffer.getFloat()
        val accelZ = buffer.getFloat()
        val gyroX = buffer.getFloat()
        val gyroY = buffer.getFloat()
        val gyroZ = buffer.getFloat()
        return PackedState(buttons, stickX, stickY, accelX, accelY, accelZ, gyroX, gyroY, gyroZ)
    }

    @Synchronized
    fun updateSenderButton(mask: Int, isPressed: Boolean) {
        senderButtons = if (isPressed) {
            senderButtons or mask
        } else {
            senderButtons and mask.inv()
        }
        if (_role.value == BluetoothRole.HID_GAMEPAD) {
            sendHidReport()
        }
    }

    private fun activeRole(): BluetoothRole = _role.value

    fun stopAll() {
        Log.i(TAG, "Cleaning up all Bluetooth connections and servers...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            unregisterHidApp()
        }
        activeHidHost = null

        sendJob?.cancel()
        sendJob = null
        receiveJob?.cancel()
        receiveJob = null
        
        activeClients.values.forEach { conn ->
            conn.receiveJob?.cancel()
            try {
                conn.socket.close()
            } catch (e: Exception) {}
        }
        activeClients.clear()
        _connectedClientsList.value = emptyList()
        _slottedReceivedState.value = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null

        try {
            clientSocket?.close()
        } catch (e: Exception) {}
        clientSocket = null

        activeOutStream = null
        _connectionState.value = BtConnectionState.NONE
        _role.value = BluetoothRole.IDLE
        _connectedDeviceName.value = null
        _receivedState.value = null
        _isReconnecting.value = false
        _reconnectAttempt.value = 0
    }
}
