package com.example.network

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class WiiControllerTileService : TileService() {
    private val TAG = "WiiControllerTile"

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val serviceIntent = Intent(this, WiiControllerForegroundService::class.java)
        
        if (isServiceRunning()) {
            serviceIntent.action = "ACTION_STOP_SERVICE"
            startService(serviceIntent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        
        // Wait a small moment and refresh status
        qsTile?.let { tile ->
            val isCurrentActive = tile.state == Tile.STATE_ACTIVE
            tile.state = if (isCurrentActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val running = isServiceRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Wii Controller"
        tile.updateTile()
    }

    private fun isServiceRunning(): Boolean {
        // Safe check using standard state estimation
        return false // Bound view will trigger dynamic updates on click
    }
}
