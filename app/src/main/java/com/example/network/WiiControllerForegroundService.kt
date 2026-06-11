package com.example.network

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.network.DsuProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramSocket
import java.net.InetSocketAddress

class WiiControllerForegroundService : Service() {
    private val TAG = "WiiControllerService"
    private val CHANNEL_ID = "WiiControllerServiceChannel"
    private val NOTIFICATION_ID = 40402

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Server references
    var dsuServer: DsuServer? = null
        private set
    var btManager: BluetoothControllerManager? = null
        private set
    var audioServer: AudioReceiverServer? = null
        private set
    private var nsdHelper: DsuNsdHelper? = null

    // Service Locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Mutable state flows to expose from Service
    private val _isDsuRunning = MutableStateFlow(false)
    val isDsuRunning = _isDsuRunning.asStateFlow()

    private val _isAudioRunning = MutableStateFlow(false)
    val isAudioRunning = _isAudioRunning.asStateFlow()

    private val _nsdDiscoverable = MutableStateFlow(false)
    val nsdDiscoverable = _nsdDiscoverable.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): WiiControllerForegroundService = this@WiiControllerForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Foreground Service onCreate")
        createNotificationChannel()
        btManager = BluetoothControllerManager(applicationContext)
        nsdHelper = DsuNsdHelper(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Service Bound")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            Log.i(TAG, "Request to stop service received via action")
            stopDsu()
            stopAudio()
            btManager?.stopAll()
            stopServiceAndLocks()
            return START_NOT_STICKY
        }
        
        // Ensure starting foreground notification
        startForeground(NOTIFICATION_ID, buildNotification("Ready", "Tap to configure bindings."))
        return START_STICKY
    }

    fun startDsu(port: Int = DsuProtocol.DEFAULT_PORT, onRumble: (weak: Int, strong: Int) -> Unit) {
        if (dsuServer != null) return
        
        acquireSessionLocks()
        dsuServer = DsuServer(port) { weak, strong ->
            onRumble(weak, strong)
        }.apply {
            start()
        }
        _isDsuRunning.value = true
        
        if (_nsdDiscoverable.value) {
            nsdHelper?.registerService(port)
        }
        
        updateNotification()
    }

    fun stopDsu() {
        nsdHelper?.unregisterService()
        dsuServer?.stop()
        dsuServer = null
        _isDsuRunning.value = false
        checkLocksRelease()
        updateNotification()
    }

    fun setNsdEnabled(enabled: Boolean, port: Int = DsuProtocol.DEFAULT_PORT) {
        _nsdDiscoverable.value = enabled
        if (enabled && _isDsuRunning.value) {
            nsdHelper?.registerService(port)
        } else {
            nsdHelper?.unregisterService()
        }
    }

    fun startAudio(port: Int = 26761) {
        if (audioServer != null) return
        acquireSessionLocks()
        audioServer = AudioReceiverServer(port).apply {
            start()
        }
        _isAudioRunning.value = true
        updateNotification()
    }

    fun stopAudio() {
        audioServer?.stop()
        audioServer = null
        _isAudioRunning.value = false
        checkLocksRelease()
        updateNotification()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireSessionLocks() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiiController::WakeLock")
            wakeLock?.acquire()
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WiiController::WifiLock")
            wifiLock?.acquire()
        }
    }

    private fun checkLocksRelease() {
        if (!_isDsuRunning.value && !_isAudioRunning.value) {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        }
    }

    private fun stopServiceAndLocks() {
        stopDsu()
        stopAudio()
        btManager?.stopAll()
        checkLocksRelease()
        stopSelf()
    }

    private fun updateNotification() {
        val activeRole = when (btManager?.role?.value) {
            BluetoothRole.SENDER -> "SENDER (Client)"
            BluetoothRole.RECEIVER -> "RECEIVER (Hub)"
            else -> "HOST (Dsu Server)"
        }
        val status = if (_isDsuRunning.value) "DSU Active" else "Ready"
        val desc = "Role: $activeRole | Audio: ${if (_isAudioRunning.value) "ON" else "OFF"}"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status, desc))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = Intent(this, WiiControllerForegroundService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 1, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wii Controller: $title")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wii Controller Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps active controller and sound links alive in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopServiceAndLocks()
        Log.i(TAG, "Service Destroyed")
    }
}

class DsuNsdHelper(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun registerService(port: Int) {
        unregisterService()
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = "_cemuhook._udp.local."
            serviceName = "WiiController-${Build.MODEL}"
            setPort(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("port", port.toString())
                setAttribute("slots", "4")
            }
        }
        
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) {
                Log.i("DsuNsdHelper", "mDNS Service registered: ${info?.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.e("DsuNsdHelper", "mDNS Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo?) {
                Log.i("DsuNsdHelper", "mDNS Service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.e("DsuNsdHelper", "mDNS Unregistration failed: $errorCode")
            }
        }
        
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("DsuNsdHelper", "Error registering NSD: ${e.message}")
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e("DsuNsdHelper", "Error unregistering NSD: ${e.message}")
            }
            registrationListener = null
        }
    }
}
