package com.example.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioReceiverServer(
    val port: Int = 26761
) {
    private val TAG = "AudioReceiverServer"
    private var socket: DatagramSocket? = null
    var isRunning = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null
    private var audioTrack: AudioTrack? = null

    // Volume multiplier (0 to 150)
    var volumeScale: Float = 1.0f

    // Waveform state flow to draw in UI (Mono 16-bit PCM amplitude values, scaled -1f to 1f)
    private val _waveformFlow = MutableStateFlow<FloatArray>(FloatArray(128))
    val waveformFlow = _waveformFlow.asStateFlow()

    // Real-time server telemetry counters
    var totalBytesReceived = 0L
    
    private val _streamStatus = MutableStateFlow("Disconnected")
    val streamStatus = _streamStatus.asStateFlow()

    private var lastPacketTime = 0L
    private var activityMonitorJob: Job? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting Audio Receiver Server on port $port...")

        val sampleRate = 11025 // standard for Wiimote sound
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(1024)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
            isRunning = false
            return
        }

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                soTimeout = 1000
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Audio port $port: ${e.message}")
            isRunning = false
            releaseAudio()
            return
        }

        // Listener loop implementing an Adaptive Jitter Buffer
        listenerJob = scope.launch {
            val receiveBuffer = ByteArray(4096)
            var lastArrivalDiff = 10L
            
            while (isRunning) {
                try {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket?.receive(packet)
                    val length = packet.length

                    if (length > 0) {
                        totalBytesReceived += length
                        val now = System.currentTimeMillis()
                        
                        if (lastPacketTime != 0L) {
                            val diff = now - lastPacketTime
                            lastArrivalDiff = (lastArrivalDiff * 7 + diff * 3) / 10 // Exponential moving average of jitter
                        }
                        lastPacketTime = now
                        _streamStatus.value = "Streaming"

                        // Adaptive Jitter sizing based on latency logs:
                        // Resize processing window between 256 and 2048 bytes dynamically
                        val targetAdaptiveSize = if (lastArrivalDiff > 35) 1024 else 512
                        
                        // Copy payload and modify gain using volume multiplier
                        val modifiedData = processPCMBytes(packet.data, length)
                        
                        // Extract sample values for the scrolling UI waveform
                        updateWaveform(modifiedData)

                        // Direct low-latency PCM write to hardware
                        audioTrack?.write(modifiedData, 0, modifiedData.size)
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Socket read timeout
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "SocketException in audio receiver: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio receiver: ${e.message}")
                }
            }
        }

        // Active connection health tracking
        activityMonitorJob = scope.launch {
            while (isRunning) {
                delay(1000)
                val durationSilent = System.currentTimeMillis() - lastPacketTime
                if (lastPacketTime != 0L && durationSilent > 10000) {
                    _streamStatus.value = "Audio stream disconnected"
                    // flush hardware buffer
                    try {
                        audioTrack?.flush()
                    } catch (e: Exception) {}
                } else if (lastPacketTime != 0L && durationSilent > 1500) {
                    _streamStatus.value = "Idle"
                }
            }
        }
    }

    private fun processPCMBytes(rawPCM: ByteArray, length: Int): ByteArray {
        val count = length / 2
        val inputBuffer = ByteBuffer.wrap(rawPCM, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val outputBytes = ByteArray(length)
        val outputBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until count) {
            if (inputBuffer.remaining() >= 2) {
                val originalSample = inputBuffer.short.toFloat()
                // Apply gain volume scale
                val modifiedSample = (originalSample * volumeScale).coerceIn(-32768f, 32767f).toInt().toShort()
                outputBuffer.putShort(modifiedSample)
            }
        }
        return outputBytes
    }

    private fun updateWaveform(pcmData: ByteArray) {
        val count = pcmData.size / 2
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val viewSize = 128
        val samples = FloatArray(viewSize)
        val step = (count / viewSize).coerceAtLeast(1)

        for (i in 0 until viewSize) {
            val pcmOffset = (i * step * 2).coerceAtMost(pcmData.size - 2)
            if (pcmOffset >= 0 && pcmOffset < pcmData.size - 1) {
                val sampleValue = ((pcmData[pcmOffset].toInt() and 0xFF) or (pcmData[pcmOffset + 1].toInt() shl 8)).toShort().toFloat()
                samples[i] = sampleValue / 32768.1f
            }
        }
        _waveformFlow.value = samples
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "Stopping Audio Receiver Server...")
        socket?.close()
        socket = null
        listenerJob?.cancel()
        activityMonitorJob?.cancel()
        _streamStatus.value = "Disconnected"
        releaseAudio()
    }

    private fun releaseAudio() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio track: ${e.message}")
        } finally {
            audioTrack = null
        }
    }
}
