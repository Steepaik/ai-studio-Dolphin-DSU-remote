package com.example.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlinx.coroutines.*

class DsuServer(
    private val port: Int = 26760,
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

        // Raw sensors in SI units (m/s^2 for accel, rad/s for gyro)
        var accelX = 0f
        var accelY = 0f
        var accelZ = 9.8f
        var gyroX = 0f
        var gyroY = 0f
        var gyroZ = 0f

        var packetIndex = 0L
        val mac = ByteArray(6)

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

    // Helpers to write inputs on any slot remotely
    fun setSlotButton(slotId: Int, buttonName: String, isPressed: Boolean) {
        if (slotId in 0..3) {
            val slot = slots[slotId]
            slot.isConnected = true
            when (buttonName.uppercase()) {
                "A" -> slot.buttonA = isPressed
                "B" -> slot.buttonB = isPressed
                "MINUS" -> slot.buttonMinus = isPressed
                "PLUS" -> slot.buttonPlus = isPressed
                "HOME" -> slot.buttonHome = isPressed
                "ONE" -> slot.button1 = isPressed
                "TWO" -> slot.button2 = isPressed
                "UP" -> slot.buttonUp = isPressed
                "RIGHT" -> slot.buttonRight = isPressed
                "DOWN" -> slot.buttonDown = isPressed
                "LEFT" -> slot.buttonLeft = isPressed
                "SHAKE" -> slot.buttonShake = isPressed
            }
        }
    }

    fun setSlotStick(slotId: Int, sx: Int, sy: Int) {
        if (slotId in 0..3) {
            val slot = slots[slotId]
            slot.isConnected = true
            slot.stickX = sx
            slot.stickY = sy
        }
    }

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

    private val controllerMac = byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E)
    private var packetIndex = 0L

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting DSU Server on port $port...")

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                soTimeout = 1000 // periodic check
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind DSU port $port: ${e.message}")
            isRunning = false
            return
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
                    // socket timeout, just loop back
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "SocketException in listener: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in listener: ${e.message}")
                }
            }
        }

        // Broadcast thread (Pushes controller reports at 100Hz / 10ms for low latency)
        broadcastJob = scope.launch {
            while (isRunning) {
                val now = System.currentTimeMillis()
                // Clean stale clients (no request for > 5 seconds)
                val iterator = connectedClients.entries.iterator()
                while (iterator.hasNext()) {
                    val client = iterator.next()
                    if (now - client.value > 5000) {
                        iterator.remove()
                        Log.i(TAG, "Client ${client.key} timed out.")
                    }
                }

                // Send input reports for each active/connected slot to active clients
                if (connectedClients.isNotEmpty()) {
                    for (slotId in 0..3) {
                        val slot = slots[slotId]
                        if (slot.isConnected) {
                            val reportPacketBytes = buildInputReport(slotId)
                            for (client in connectedClients.keys) {
                                try {
                                    val datagram = DatagramPacket(
                                        reportPacketBytes,
                                        reportPacketBytes.size,
                                        client.address,
                                        client.port
                                    )
                                    socket?.send(datagram)
                                    totalPacketsSent++
                                    fpsCounter++
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error sending report to $client: ${e.message}")
                                }
                            }
                        }
                    }
                }

                delay(10) // 100 Hz update loop
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

        // In DSU structure, message type is 4 bytes at offset 16
        if (data.size < 20) return
        val messageType = readInt32(data, 16)

        // Track or refresh client
        connectedClients[client] = System.currentTimeMillis()

        when (messageType) {
            0x100000 -> { // Version Request
                sendVersionResponse(client)
            }
            0x100001 -> { // Ports Info Request
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
            0x100002 -> { // Input Data Request / Subscribe
                val regType = if (data.size >= 21) data[20].toInt() and 0xFF else 0
                val slotRequested = if (data.size >= 22) data[21].toInt() and 0xFF else 0
                
                for (sId in 0..3) {
                    val slot = slots[sId]
                    if (slot.isConnected) {
                        sendResponse(buildInputReport(sId), client)
                    }
                }
            }
            0x100003 -> { // Output Report (Rumble, LED, etc.)
                handleOutputReport(data)
            }
        }
    }

    private fun handleOutputReport(data: ByteArray) {
        // Output report contains rumble data
        // Format layout check: inside payload (offset 20 onwards):
        // 20: Slot index
        // 21: Command code
        // 22: Small motor intensity (0..255)
        // 23: Large motor intensity (0..255)
        if (data.size >= 24) {
            val weakMotor = data[22].toInt() and 0xFF
            val strongMotor = data[23].toInt() and 0xFF
            onRumbleReceived(weakMotor, strongMotor)
        }
    }

    private fun sendVersionResponse(client: InetSocketAddress) {
        val payloadLength = 6 // Message type (4) + Version (2)
        val packet = ByteArray(16 + payloadLength)

        // Header: DSUS, version 1001 (0xE9 0x03), length
        writeHeader(packet, payloadLength)

        // Payload Type: 0x100000
        writeInt32(packet, 16, 0x100000)

        // Protocol Version: 1001 (0x03E9 in Big, 0xE9 0x03 in Little)
        packet[20] = 0xE9.toByte()
        packet[21] = 0x03.toByte()

        // Write Checksum
        injectChecksum(packet)
        sendResponse(packet, client)
    }

    fun sendPortsInfoResponse(client: InetSocketAddress) {
        sendPortsInfoResponse(client, 0)
    }

    private fun sendPortsInfoResponse(client: InetSocketAddress, slotId: Int) {
        val payloadLength = 16 // Type (4) + Slot (1) + State (1) + Model (1) + Conn (1) + MAC (6) + Battery (1) + Padding (1)
        val packet = ByteArray(16 + payloadLength)

        writeHeader(packet, payloadLength)

        // Payload Type: 0x100001
        writeInt32(packet, 16, 0x100001)

        val slot = slots[slotId]

        packet[20] = slotId.toByte() // Slot
        packet[21] = if (slot.isConnected) 2.toByte() else 0.toByte() // Connected state (2)
        packet[22] = 2.toByte() // Full Giro device (2)
        packet[23] = 2.toByte() // Connection: wireless (2)

        // MAC Address (6 bytes)
        System.arraycopy(slot.mac, 0, packet, 24, 6)

        packet[30] = 5.toByte() // Battery (Full)
        packet[31] = 0.toByte() // Padding

        injectChecksum(packet)
        sendResponse(packet, client)
    }

    fun buildInputReport(): ByteArray {
        return buildInputReport(0)
    }

    private fun buildInputReport(slotId: Int): ByteArray {
        val payloadLength = 100 // Typ(4) + Info block(12) + Inputs block(84)
        val packet = ByteArray(16 + payloadLength)

        writeHeader(packet, payloadLength)

        // 16-19: Message type (0x100002)
        writeInt32(packet, 16, 0x100002)

        val slot = slots[slotId]

        // Info block
        packet[20] = slotId.toByte() // Slot Index
        packet[21] = if (slot.isConnected) 2.toByte() else 0.toByte() // State: connected (2)
        packet[22] = 2.toByte() // Model: Full Gyro (2)
        packet[23] = 2.toByte() // Conn: wireless (2)
        System.arraycopy(slot.mac, 0, packet, 24, 6)
        packet[30] = 5.toByte() // Battery (Full / Charging)
        packet[31] = if (slot.isConnected) 1.toByte() else 0.toByte() // Is Active (1)

        // Incremental Packet Counter (4 bytes)
        writeInt32(packet, 32, (slot.packetIndex and 0xFFFFFFFFL).toInt())
        slot.packetIndex++

        // Button Digital Block 1
        var btnByte1 = 0
        if (slot.buttonMinus) btnByte1 = btnByte1 or 0x01 // Mapped to Share / Options Left
        // L3 stick click (0x02), R3 stick click (0x04)
        if (slot.buttonPlus) btnByte1 = btnByte1 or 0x08  // Mapped to Options Right / Start
        if (slot.buttonUp) btnByte1 = btnByte1 or 0x10
        if (slot.buttonRight) btnByte1 = btnByte1 or 0x20
        if (slot.buttonDown) btnByte1 = btnByte1 or 0x40
        if (slot.buttonLeft) btnByte1 = btnByte1 or 0x80
        packet[36] = btnByte1.toByte()

        // Button Digital Block 2
        var btnByte2 = 0
        // L2 (0x01), R2 (0x02)
        // L1 bumper (0x04), R1 bumper (0x08)
        if (slot.button2) btnByte2 = btnByte2 or 0x10     // Mapped to Triangle / Y
        if (slot.buttonB) btnByte2 = btnByte2 or 0x20     // Mapped to Circle / B
        if (slot.buttonA) btnByte2 = btnByte2 or 0x40     // Mapped to Cross / A
        if (slot.button1) btnByte2 = btnByte2 or 0x80     // Mapped to Square / X
        packet[37] = btnByte2.toByte()

        // Home button
        if (slot.buttonHome) {
            packet[38] = 1.toByte() // Home button mapped directly
        } else {
            packet[38] = 0.toByte()
        }

        packet[39] = 0.toByte() // Touch pad button (0)

        // Sticks analog (-128 to 127 mapped to 0..255, offset 128)
        packet[40] = (slot.stickX + 128).toByte() // Left stick X
        packet[41] = (-slot.stickY + 128).toByte() // Left stick Y (inverted standard axis)
        packet[42] = 128.toByte()            // Right stick X (default centered)
        packet[43] = 128.toByte()            // Right stick Y (default centered)

        // Button Analog block values (0 or 255 depending on digital press)
        packet[44] = (if (slot.buttonUp) 255 else 0).toByte()
        packet[45] = (if (slot.buttonRight) 255 else 0).toByte()
        packet[46] = (if (slot.buttonDown) 255 else 0).toByte()
        packet[47] = (if (slot.buttonLeft) 255 else 0).toByte()
        packet[48] = (if (slot.button2) 255 else 0).toByte() // Triangle / Y
        packet[49] = (if (slot.buttonB) 255 else 0).toByte() // Circle / B
        packet[50] = (if (slot.buttonA) 255 else 0).toByte() // Cross / A
        packet[51] = (if (slot.button1) 255 else 0).toByte() // Square / X
        packet[52] = 0.toByte() // L1
        packet[53] = 0.toByte() // R1
        packet[54] = 0.toByte() // L2
        packet[55] = 0.toByte() // R2

        // Touchpad points (disabled for standard Wiimote inputs)
        packet[56] = 0.toByte() // touch is inactive (0)

        // Microsecond timestamp (8 bytes)
        val timestampUs = System.nanoTime() / 1000
        writeInt64(packet, 68, timestampUs)

        // Accelerometer sensor values (3 floats, in g's). Standard value sitting flat: X=0, Y=0, Z=-1 or +1
        // Android returns in m/s². Scale by 1/9.80665
        val scaleG = 9.80665f
        
        // Let's add simulated shaking if shake flag is active
        var finalAccX = slot.accelX / scaleG
        var finalAccY = slot.accelY / scaleG
        var finalAccZ = slot.accelZ / scaleG

        if (slot.buttonShake) {
            // Shake triggers high frequencies of visual movement
            finalAccX += ((Math.sin(System.currentTimeMillis() / 20.0) * 1.5f).toFloat())
        }

        writeFloat(packet, 76, finalAccX)
        writeFloat(packet, 80, finalAccY)
        writeFloat(packet, 84, finalAccZ)

        // Gyroscope sensor values (3 floats, in deg/s). Rad/s to deg/s.
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
        // Magic "DSUS" (server replies with DSUS)
        packet[0] = 'D'.toByte()
        packet[1] = 'S'.toByte()
        packet[2] = 'U'.toByte()
        packet[3] = 'S'.toByte()

        // Protocol Version 1001 (E9 03 in little end)
        packet[4] = 0xE9.toByte()
        packet[5] = 0x03.toByte()

        // Payload Length (2 bytes)
        packet[6] = (payloadLength and 0xFF).toByte()
        packet[7] = ((payloadLength shr 8) and 0xFF).toByte()

        // Bytes 8-11: Checksum (Calculated later, inited to 0)
        packet[8] = 0
        packet[9] = 0
        packet[10] = 0
        packet[11] = 0

        // Bytes 12-15: Server/Sender ID (0)
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

    // Helper functions for reading/writing in little endian format
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
