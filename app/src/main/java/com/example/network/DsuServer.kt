package com.example.network

import android.util.Log
import com.example.network.DsuProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

class DsuServer(
    private val port: Int = DsuProtocol.DEFAULT_PORT,
    private val onRumbleReceived: (weak: Int, strong: Int) -> Unit
) {
    private val TAG = "DsuServer"
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null
    private var broadcastJob: Job? = null

    // Client tracking: client ip-port mapped to last request time
    private val connectedClients = ConcurrentHashMap<InetSocketAddress, Long>()

    // Telemetry Events
    private val _clientConnectionEvent = MutableStateFlow<String?>(null)
    val clientConnectionEvent = _clientConnectionEvent.asStateFlow()

    private val _effectiveFps = MutableStateFlow(100)
    val effectiveFps = _effectiveFps.asStateFlow()

    // Configurable parameters
    var isIrModeEnabled = false
    var isNunchuckEnabled = false

    class ControllerSlotState {
        var isConnected = false
        var buttonLeft = false
        var buttonRight = false
        var buttonUp = false
        var buttonDown = false
        var buttonA = false
        var buttonB = false
        var buttonMinus = false
        var buttonPlus = false
        var buttonHome = false
        var button1 = false
        var button2 = false
        var buttonShake = false

        // sticks (-128 to 127)
        var stickX = 0
        var stickY = 0
        var stickRightX = 0
        var stickRightY = 0

        // Raw sensors in SI units (m/s^2 for accel, rad/s for gyro)
        var accelX = 0f
        var accelY = 0f
        var accelZ = 9.8f
        var gyroX = 0f
        var gyroY = 0f
        var gyroZ = 0f

        var packetIndex = 0L
        val mac = ByteArray(6)

        // Previous frames cache for adaptive broadcast rate (last 3 reports)
        var lastReports = ArrayList<ByteArray>()

        fun initMac(slotId: Int) {
            mac[0] = 0x00.toByte()
            mac[1] = 0x1A.toByte()
            mac[2] = 0x2B.toByte()
            mac[3] = 0x3C.toByte()
            mac[4] = 0x4D.toByte()
            mac[5] = (0x5E + slotId).toByte()
        }
    }

    val slots = Array(4) { id ->
        ControllerSlotState().apply {
            initMac(id)
            if (id == 0) isConnected = true // Primary local controller is always online
        }
    }

    // Compatibility getters/setters mapping dynamically to Slot 0
    var buttonLeft: Boolean
        get() = slots[0].buttonLeft
        set(value) { slots[0].buttonLeft = value }

    var buttonRight: Boolean
        get() = slots[0].buttonRight
        set(value) { slots[0].buttonRight = value }

    var buttonUp: Boolean
        get() = slots[0].buttonUp
        set(value) { slots[0].buttonUp = value }

    var buttonDown: Boolean
        get() = slots[0].buttonDown
        set(value) { slots[0].buttonDown = value }

    var buttonA: Boolean
        get() = slots[0].buttonA
        set(value) { slots[0].buttonA = value }

    var buttonB: Boolean
        get() = slots[0].buttonB
        set(value) { slots[0].buttonB = value }

    var buttonMinus: Boolean
        get() = slots[0].buttonMinus
        set(value) { slots[0].buttonMinus = value }

    var buttonPlus: Boolean
        get() = slots[0].buttonPlus
        set(value) { slots[0].buttonPlus = value }

    var buttonHome: Boolean
        get() = slots[0].buttonHome
        set(value) { slots[0].buttonHome = value }

    var button1: Boolean
        get() = slots[0].button1
        set(value) { slots[0].button1 = value }

    var button2: Boolean
        get() = slots[0].button2
        set(value) { slots[0].button2 = value }

    var buttonShake: Boolean
        get() = slots[0].buttonShake
        set(value) { slots[0].buttonShake = value }

    var stickX: Int
        get() = slots[0].stickX
        set(value) { slots[0].stickX = value }

    var stickY: Int
        get() = slots[0].stickY
        set(value) { slots[0].stickY = value }

    var accelX: Float
        get() = slots[0].accelX
        set(value) { slots[0].accelX = value }

    var accelY: Float
        get() = slots[0].accelY
        set(value) { slots[0].accelY = value }

    var accelZ: Float
        get() = slots[0].accelZ
        set(value) { slots[0].accelZ = value }

    var gyroX: Float
        get() = slots[0].gyroX
        set(value) { slots[0].gyroX = value }

    var gyroY: Float
        get() = slots[0].gyroY
        set(value) { slots[0].gyroY = value }

    var gyroZ: Float
        get() = slots[0].gyroZ
        set(value) { slots[0].gyroZ = value }

    fun setSlotState(
        slotId: Int,
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float,
        btnA: Boolean, btnB: Boolean, btnMinus: Boolean, btnPlus: Boolean,
        btnHome: Boolean, btn1: Boolean, btn2: Boolean,
        btnLeft: Boolean, btnRight: Boolean, btnUp: Boolean, btnDown: Boolean,
        btnShake: Boolean, stickX: Int, stickY: Int,
        isConnected: Boolean = true
    ) {
        if (slotId in 0..3) {
            val slot = slots[slotId]
            slot.isConnected = isConnected
            slot.accelX = accelX
            slot.accelY = accelY
            slot.accelZ = accelZ
            slot.gyroX = gyroX
            slot.gyroY = gyroY
            slot.gyroZ = gyroZ
            slot.buttonA = btnA
            slot.buttonB = btnB
            slot.buttonMinus = btnMinus
            slot.buttonPlus = btnPlus
            slot.buttonHome = btnHome
            slot.button1 = btn1
            slot.button2 = btn2
            slot.buttonLeft = btnLeft
            slot.buttonRight = btnRight
            slot.buttonUp = btnUp
            slot.buttonDown = btnDown
            slot.buttonShake = btnShake
            slot.stickX = stickX
            slot.stickY = stickY
        }
    }

    // Running performance statistics
    var totalPacketsReceived = 0
    var totalPacketsSent = 0
    var fps = 0
    private var fpsCounter = 0
    private var fpsTimerJob: Job? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting DSU Server with IPv4 + IPv6 support on port $port...")

        try {
            // Bind using both wildcard IPv4 and IPv6 wildcard (::)
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("::", port))
                soTimeout = 1000
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed binding wildcard :: address, falling back: ${e.message}")
            try {
                socket = DatagramSocket(port).apply {
                    reuseAddress = true
                    soTimeout = 1000
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed standard layout bind: ${ex.message}")
                isRunning = false
                return
            }
        }

        // Listener thread (Receives packets)
        listenerJob = scope.launch {
            val receiveBuffer = ByteArray(1024)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket?.receive(packet)
                    val senderAddress = packet.address
                    val senderPort = packet.port
                    val length = packet.length

                    if (length >= 16) {
                        totalPacketsReceived++
                        val data = packet.data.copyOf(length)
                        handlePacket(data, InetSocketAddress(senderAddress, senderPort))
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Socket timeout
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "SocketException in listener: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in listener: ${e.message}")
                }
            }
        }

        // Broadcast thread supporting Adaptive Rate Control
        broadcastJob = scope.launch {
            var currentInterval = 10L // starts at 10ms (100Hz)
            
            while (isRunning) {
                val now = System.currentTimeMillis()
                
                // 1. Clean stale clients (> 5 seconds old) and auto-emit disconnected signals
                val iterator = connectedClients.entries.iterator()
                while (iterator.hasNext()) {
                    val client = iterator.next()
                    if (now - client.value > 5000) {
                        val clientIp = client.key.address.hostAddress ?: ""
                        iterator.remove()
                        _clientConnectionEvent.value = "DISCONNECTED:$clientIp"
                        Log.i(TAG, "DSU Client $clientIp disconnected.")
                    }
                }

                var inputsChanged = false

                // 2. Broadcast inputs to active subscribers
                if (connectedClients.isNotEmpty()) {
                    for (slotId in 0..3) {
                        val slot = slots[slotId]
                        if (slot.isConnected) {
                            val report = buildInputReport(slotId).copyOfRange(16, 116) // use state bytes
                            
                            // Check if state changed over the last 3 snapshots
                            if (slot.lastReports.size < 3) {
                                slot.lastReports.add(report)
                                inputsChanged = true
                            } else {
                                val isIdentical = slot.lastReports.all { it.contentEquals(report) }
                                if (!isIdentical) {
                                    inputsChanged = true
                                }
                                slot.lastReports.removeAt(0)
                                slot.lastReports.add(report)
                            }

                            val fullPacketBytes = buildInputReport(slotId)
                            for (client in connectedClients.keys) {
                                try {
                                    val datagram = DatagramPacket(
                                        fullPacketBytes,
                                        fullPacketBytes.size,
                                        client.address,
                                        client.port
                                    )
                                    socket?.send(datagram)
                                    totalPacketsSent++
                                    fpsCounter++
                                } catch (e: Exception) {
                                    Log.e(TAG, "Adaptive broadcast send failed to $client: ${e.message}")
                                }
                            }
                        }
                    }
                }

                // 3. Adaptive Rate calculation logic
                if (inputsChanged) {
                    currentInterval = 10L // Immediately return to 100Hz on any input variance
                    _effectiveFps.value = 100
                } else if (currentInterval == 10L) {
                    // No changes on consecutive reports, scale down to 60Hz to conserve radio bandwidth
                    currentInterval = 16L
                    _effectiveFps.value = 60
                }

                delay(currentInterval)
            }
        }

        // Telemetry FPS ticker
        fpsTimerJob = scope.launch {
            while (isRunning) {
                delay(1000)
                fps = fpsCounter
                fpsCounter = 0
            }
        }
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "Stopping DSU Server...")
        socket?.close()
        socket = null
        listenerJob?.cancel()
        broadcastJob?.cancel()
        fpsTimerJob?.cancel()
        connectedClients.clear()
    }

    private fun handlePacket(data: ByteArray, client: InetSocketAddress) {
        // Validate Header (Magic "DSUC")
        if (data[0] != 'D'.toByte() || data[1] != 'S'.toByte() || data[2] != 'U'.toByte() || data[3] != 'C'.toByte()) {
            return
        }

        if (data.size < 20) return
        val messageType = readInt32(data, 16)

        // Check if this was a new connection or re-registration
        val isNew = !connectedClients.containsKey(client)
        connectedClients[client] = System.currentTimeMillis()

        if (isNew) {
            val clientIp = client.address.hostAddress ?: ""
            _clientConnectionEvent.value = "CONNECTED:$clientIp"
            Log.i(TAG, "DSU Client $clientIp subscribed of port ${client.port}")
        }

        when (messageType) {
            DsuProtocol.MSG_TYPE_VERSION -> { // Version Request
                sendVersionResponse(client)
            }
            DsuProtocol.MSG_TYPE_PORTS -> { // Ports Info Request
                if (data.size >= 24) {
                    val count = readInt32(data, 20)
                    for (i in 0 until count) {
                        if (data.size >= 24 + i) {
                            val slotId = data[24 + i].toInt() and 0xFF
                            if (slotId in 0..3) {
                                sendPortsInfoResponse(client, slotId)
                            }
                        }
                    }
                } else {
                    sendPortsInfoResponse(client, 0)
                }
            }
            DsuProtocol.MSG_TYPE_INPUT -> { // Input Data Request / Subscribe
                for (sId in 0..3) {
                    val slot = slots[sId]
                    if (slot.isConnected) {
                        sendResponse(buildInputReport(sId), client)
                    }
                }
            }
            DsuProtocol.MSG_TYPE_OUTPUT -> { // Output Report (Rumble)
                handleOutputReport(data)
            }
        }
    }

    private fun handleOutputReport(data: ByteArray) {
        if (data.size >= 24) {
            val weakMotor = data[22].toInt() and 0xFF
            val strongMotor = data[23].toInt() and 0xFF
            onRumbleReceived(weakMotor, strongMotor)
        }
    }

    private fun sendVersionResponse(client: InetSocketAddress) {
        val payloadLength = 8
        val packet = ByteArray(16 + payloadLength)
        writeHeader(packet, payloadLength)
        writeInt32(packet, 16, DsuProtocol.MSG_TYPE_VERSION)
        packet[20] = 0x00.toByte()
        packet[21] = 0x01.toByte() // Version code 1
        packet[22] = 0x00.toByte()
        packet[23] = 0x00.toByte()
        injectChecksum(packet)
        sendResponse(packet, client)
    }

    private fun sendPortsInfoResponse(client: InetSocketAddress, slotId: Int) {
        val payloadLength = 16
        val packet = ByteArray(16 + payloadLength)
        writeHeader(packet, payloadLength)
        writeInt32(packet, 16, DsuProtocol.MSG_TYPE_PORTS)
        
        val slot = slots[slotId]
        packet[20] = slotId.toByte()
        packet[21] = if (slot.isConnected) 2.toByte() else 0.toByte() // 2 = Connected
        packet[22] = 2.toByte() // Full Gyro / DualShock (2)
        packet[23] = 2.toByte() // Wireless link (2)
        
        System.arraycopy(slot.mac, 0, packet, 24, 6)
        packet[30] = 5.toByte() // Battery Full
        packet[31] = 0.toByte() // Padding
        
        injectChecksum(packet)
        sendResponse(packet, client)
    }

    private fun buildInputReport(slotId: Int): ByteArray {
        val payloadLength = 100 // Typ(4) + Info block(12) + Inputs block(84)
        val packet = ByteArray(16 + payloadLength)

        writeHeader(packet, payloadLength)

        // Message type
        writeInt32(packet, 16, DsuProtocol.MSG_TYPE_INPUT)

        val slot = slots[slotId]

        // Info block
        packet[20] = slotId.toByte()
        packet[21] = if (slot.isConnected) 2.toByte() else 0.toByte()
        packet[22] = 2.toByte()
        packet[23] = 2.toByte()
        System.arraycopy(slot.mac, 0, packet, 24, 6)
        packet[30] = 5.toByte()
        packet[31] = if (slot.isConnected) 1.toByte() else 0.toByte()

        writeInt32(packet, 32, (slot.packetIndex and 0xFFFFFFFFL).toInt())
        slot.packetIndex++

        // Button Digital Block 1
        var btnByte1 = 0
        if (slot.buttonMinus) btnByte1 = btnByte1 or 0x01
        if (slot.buttonPlus) btnByte1 = btnByte1 or 0x08
        if (slot.buttonUp) btnByte1 = btnByte1 or 0x10
        if (slot.buttonRight) btnByte1 = btnByte1 or 0x20
        if (slot.buttonDown) btnByte1 = btnByte1 or 0x40
        if (slot.buttonLeft) btnByte1 = btnByte1 or 0x80
        packet[36] = btnByte1.toByte()

        // Button Digital Block 2
        var btnByte2 = 0
        if (slot.button2) btnByte2 = btnByte2 or 0x10
        if (slot.buttonB) btnByte2 = btnByte2 or 0x20
        if (slot.buttonA) btnByte2 = btnByte2 or 0x40
        if (slot.button1) btnByte2 = btnByte2 or 0x80
        packet[37] = btnByte2.toByte()

        // Home button
        packet[38] = if (slot.buttonHome) 1.toByte() else 0.toByte()
        packet[39] = 0.toByte()

        // Sticks analog
        packet[40] = (slot.stickX + 128).toByte()
        packet[41] = (-slot.stickY + 128).toByte()

        // Nunchuck stick mapping to Right Stick (analog coordinates)
        if (isNunchuckEnabled) {
            packet[42] = (slot.stickRightX + 128).toByte()
            packet[43] = (-slot.stickRightY + 128).toByte()
        } else {
            packet[42] = 128.toByte()
            packet[43] = 128.toByte()
        }

        // Button Analog values
        packet[44] = (if (slot.buttonUp) 255 else 0).toByte()
        packet[45] = (if (slot.buttonRight) 255 else 0).toByte()
        packet[46] = (if (slot.buttonDown) 255 else 0).toByte()
        packet[47] = (if (slot.buttonLeft) 255 else 0).toByte()
        packet[48] = (if (slot.button2) 255 else 0).toByte()
        packet[49] = (if (slot.buttonB) 255 else 0).toByte()
        packet[50] = (if (slot.buttonA) 255 else 0).toByte()
        packet[51] = (if (slot.button1) 255 else 0).toByte()
        packet[52] = 0.toByte()
        packet[53] = 0.toByte()
        packet[54] = 0.toByte()
        packet[55] = 0.toByte()

        // IR Pointer Emulation - Injects simulated cursor coordinates (0..1023) in the report touch blocks (56-61)
        if (isIrModeEnabled) {
            val pitch = atan2(slot.accelY.toDouble(), slot.accelZ.toDouble())
            val roll = atan2(-slot.accelX.toDouble(), sqrt(slot.accelY.toDouble() * slot.accelY.toDouble() + slot.accelZ.toDouble() * slot.accelZ.toDouble()))
            
            val cursorX = (((roll * (180.0 / Math.PI)).coerceIn(-35.0, 35.0) + 35.0) / 70.0 * 1023.0).toInt()
            val cursorY = (((pitch * (180.0 / Math.PI)).coerceIn(-35.0, 35.0) + 35.0) / 70.0 * 1023.0).toInt()

            packet[56] = 1.toByte() // Touchpad Active
            packet[57] = 0.toByte() // Touch ID 0
            packet[58] = (cursorX and 0xFF).toByte()
            packet[59] = ((cursorX shr 8) and 0xFF).toByte()
            packet[60] = (cursorY and 0xFF).toByte()
            packet[61] = ((cursorY shr 8) and 0xFF).toByte()
        } else {
            packet[56] = 0.toByte() // Touch inactive
        }

        // Timestamp
        val timestampUs = System.nanoTime() / 1000
        writeInt64(packet, 68, timestampUs)

        // Accelerometer sensor scaling (SI to G force)
        val scaleG = 9.80665f
        
        var finalAccX = slot.accelX / scaleG
        var finalAccY = slot.accelY / scaleG
        var finalAccZ = slot.accelZ / scaleG

        if (slot.buttonShake) {
            finalAccX += ((Math.sin(System.currentTimeMillis() / 20.0) * 1.5f).toFloat())
        }

        // If Nunchuck mode enabled, split Wiimote / Nunchuck fields:
        // Wiimote: Y axis, Nunchuck: X and Z axes
        if (isNunchuckEnabled) {
            writeFloat(packet, 76, 0.0f) // Wiimote maps local X axis elsewhere
            writeFloat(packet, 80, finalAccY) 
            writeFloat(packet, 84, 0.0f) // Wiimote maps local Z axis elsewhere
        } else {
            writeFloat(packet, 76, finalAccX)
            writeFloat(packet, 80, finalAccY)
            writeFloat(packet, 84, finalAccZ)
        }

        // Gyroscope angular speed degrees mapping
        val radToDeg = (180.0 / Math.PI).toFloat()
        var finalGyrX = slot.gyroX * radToDeg
        var finalGyrY = slot.gyroY * radToDeg
        var finalGyrZ = slot.gyroZ * radToDeg

        if (slot.buttonShake) {
            finalGyrZ += ((Math.cos(System.currentTimeMillis() / 20.0) * 450.0f).toFloat())
        }

        writeFloat(packet, 88, finalGyrX)
        writeFloat(packet, 92, finalGyrY)
        writeFloat(packet, 96, finalGyrZ)

        injectChecksum(packet)
        return packet
    }

    private fun writeHeader(packet: ByteArray, payloadLength: Int) {
        packet[0] = 'D'.toByte()
        packet[1] = 'S'.toByte()
        packet[2] = 'U'.toByte()
        packet[3] = 'S'.toByte()

        packet[4] = (DsuProtocol.PROTOCOL_VERSION and 0xFF).toByte()
        packet[5] = ((DsuProtocol.PROTOCOL_VERSION shr 8) and 0xFF).toByte()

        packet[6] = (payloadLength and 0xFF).toByte()
        packet[7] = ((payloadLength shr 8) and 0xFF).toByte()

        packet[8] = 0
        packet[9] = 0
        packet[10] = 0
        packet[11] = 0

        packet[12] = 0
        packet[13] = 0
        packet[14] = 0
        packet[15] = 0
    }

    private fun injectChecksum(packet: ByteArray) {
        val crc = CRC32()
        val clearPacket = packet.clone()
        clearPacket[8] = 0
        clearPacket[9] = 0
        clearPacket[10] = 0
        clearPacket[11] = 0
        crc.update(clearPacket)

        val crcVal = crc.value
        packet[8] = (crcVal and 0xFF).toByte()
        packet[9] = ((crcVal shr 8) and 0xFF).toByte()
        packet[10] = ((crcVal shr 16) and 0xFF).toByte()
        packet[11] = ((crcVal shr 24) and 0xFF).toByte()
    }

    private fun sendResponse(data: ByteArray, client: InetSocketAddress) {
        try {
            val packet = DatagramPacket(data, data.size, client.address, client.port)
            socket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed sendResponse: ${e.message}")
        }
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24))
    }

    private fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeInt64(data: ByteArray, offset: Int, value: Long) {
        var temp = value
        for (i in 0..7) {
            data[offset + i] = (temp and 0xFF).toByte()
            temp = temp shr 8
        }
    }

    private fun writeFloat(data: ByteArray, offset: Int, value: Float) {
        val intBits = java.lang.Float.floatToIntBits(value)
        writeInt32(data, offset, intBits)
    }

    fun getClientAddresses(): List<String> {
        return connectedClients.keys.map { "${it.address.hostAddress}:${it.port}" }
    }
}
