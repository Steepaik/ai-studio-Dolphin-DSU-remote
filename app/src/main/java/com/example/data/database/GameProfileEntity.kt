package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_profiles")
data class GameProfileEntity(
    @PrimaryKey val name: String,
    val sensX: Float,
    val sensY: Float,
    val sensZ: Float,
    val deadzone: Float,
    val shakeThreshold: Float,
    val irModeEnabled: Boolean,
    val audioVolume: Float,
    val isBuiltIn: Boolean = false
)
