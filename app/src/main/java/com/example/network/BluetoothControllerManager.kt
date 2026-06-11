package com.example.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

enum class BluetoothRole {
    IDLE, SENDER, RECEIVER
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

class BluetoothControllerManager(private val context: Context) {
    private val TAG = "BtControllerManager"
    
    // Custom UUID for pairing
    private val APP_UUID = UUID.fromString("1f8bd4b2-0382-4aa8-a53b-fde5bc63ee28")
    private val APP_NAME = "WiiControllerBluetooth"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(BtConnectionState.NONE)
    val connectionState = _connectionState.asStateFlow()

    private val _role = MutableStateFlow(BluetoothRole.IDLE)
    val role = _role.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices = _pairedDevices.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    // Flow representing incoming packed state updates (for receiver mode)
    private val _receivedState = MutableStateFlow<PackedState?>(null)
    val receivedState = _receivedState.asStateFlow()

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
                
                var socket: BluetoothSocket? = null
                while (activeRole() == BluetoothRole.RECEIVER) {
                    try {
                        socket = serverSocket?.accept(10000) // 10 second timeout check loop
                        if (socket != null) {
                            break
                        }
                    } catch (e: IOException) {
                        // Accept timeout, check if still running
                        continue
                    }
                }

                if (socket != null) {
                    clientSocket = socket
                    _connectedDeviceName.value = socket.remoteDevice.name ?: "Unknown Peer"
                    _connectionState.value = BtConnectionState.CONNECTED
                    startReceiveLoop(socket.inputStream)
                    serverSocket?.close() // close server as we have a client
                } else {
                    if (_connectionState.value == BtConnectionState.LISTENING) {
                        _connectionState.value = BtConnectionState.NONE
                        _role.value = BluetoothRole.IDLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket failed: ${e.message}")
                _connectionState.value = BtConnectionState.ERROR
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
    fun connectAsSender(device: BluetoothDevice) {
        stopAll()
        _role.value = BluetoothRole.SENDER
        _connectionState.value = BtConnectionState.CONNECTING
        _connectedDeviceName.value = device.name ?: "Remote Receiver"

        workerScope.launch(Dispatchers.IO) {
            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                bluetoothAdapter?.cancelDiscovery() // stop scanning to free up bandwidth
                socket.connect()
                clientSocket = socket
                activeOutStream = socket.outputStream
                _connectionState.value = BtConnectionState.CONNECTED
                startSendLoop()
                Log.i(TAG, "Connected to receiver device over Bluetooth RFCOMM")
            } catch (e: Exception) {
                Log.e(TAG, "Connection as sender failed: ${e.message}")
                _connectionState.value = BtConnectionState.ERROR
                _role.value = BluetoothRole.IDLE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startReceiveLoop(inputStream: InputStream) {
        receiveJob = workerScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(128)
            var bytesReadTotal = 0L
            var fpsCounter = 0
            var lastTimer = System.currentTimeMillis()

            while (isActive) {
                try {
                    // Packet is strictly 32 bytes
                    var pointer = 0
                    while (pointer < 32 && isActive) {
                        val byteVal = inputStream.read()
                        if (byteVal == -1) {
                            throw IOException("Bluetooth connection lost")
                        }
                        buffer[pointer] = byteVal.toByte()
                        pointer++
                    }

                    // Parse PackedState
                    val state = unpackState(buffer)
                    if (state != null) {
                        _receivedState.value = state
                        bytesReadTotal += 32
                        _bytesTransmitted.value = bytesReadTotal
                        fpsCounter++

                        val now = System.currentTimeMillis()
                        if (now - lastTimer >= 1000) {
                            _btFps.value = fpsCounter
                            fpsCounter = 0
                            lastTimer = now
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in receive loop: ${e.message}")
                    _connectionState.value = BtConnectionState.NONE
                    _role.value = BluetoothRole.IDLE
                    _connectedDeviceName.value = null
                    break
                }
            }
        }
    }

    private fun startSendLoop() {
        // Send loop keeps connection alive and transmits inputs at 100Hz (every 10ms for ultra-responsiveness)
        // Values are updated externally in the manager and we transmit the snapshot
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

                    sentBytesTotal += 32
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

    // Thread-safe inputs to send when we are a Sender
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
        val buffer = ByteBuffer.allocate(32)
        buffer.put(0xAA.toByte())
        buffer.put(0x55.toByte())
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
        if (data.size < 32) return null
        val buffer = ByteBuffer.wrap(data)
        val h1 = buffer.get()
        val h2 = buffer.get()
        if (h1 != 0xAA.toByte() || h2 != 0x55.toByte()) return null
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
    }

    private fun activeRole(): BluetoothRole = _role.value

    fun stopAll() {
        Log.i(TAG, "Cleaning up all Bluetooth connections and servers...")
        sendJob?.cancel()
        sendJob = null
        receiveJob?.cancel()
        receiveJob = null
        
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
    }
}
