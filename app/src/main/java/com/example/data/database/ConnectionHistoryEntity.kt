package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_history")
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ipAddress: String,
    val port: Int,
    val description: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
