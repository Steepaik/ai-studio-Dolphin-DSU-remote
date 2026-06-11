package com.example.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import kotlinx.coroutines.*

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

    // Real-time server telemetry counters
    var totalBytesReceived = 0L
    var isStreamActive = false
    private var lastPacketTime = 0L
    private var activityMonitorJob: Job? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting Audio Receiver Server on port $port...")

        // Init AudioTrack for low-latency raw PCM mono streaming at 11025Hz
        val sampleRate = 11025 // standard for Wiimote sound
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

        // Listener loop
        listenerJob = scope.launch {
            val receiveBuffer = ByteArray(2048)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket?.receive(packet)
                    val length = packet.length

                    if (length > 0) {
                        totalBytesReceived += length
                        lastPacketTime = System.currentTimeMillis()
                        isStreamActive = true

                        // Pipe the raw PCM buffer bytes directly to speakers
                        audioTrack?.write(packet.data, 0, length)
                    }
                } catch (e: java.io.InterruptedIOException) {
                    // Socket read timeout, normal check
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "SocketException in audio receiver: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio receiver: ${e.message}")
                }
            }
        }

        // Active activity monitor to show "Streaming" indicator in UI
        activityMonitorJob = scope.launch {
            while (isRunning) {
                delay(1000)
                if (System.currentTimeMillis() - lastPacketTime > 1500) {
                    isStreamActive = false
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "Stopping Audio Receiver Server...")
        socket?.close()
        socket = null
        listenerJob?.cancel()
        activityMonitorJob?.cancel()
        isStreamActive = false
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
