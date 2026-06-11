package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionHistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<ConnectionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: ConnectionHistoryEntity)

    @Query("DELETE FROM connection_history WHERE ipAddress = :ipAddress AND port = :port")
    suspend fun deleteHistoryByAddress(ipAddress: String, port: Int)

    @Query("DELETE FROM connection_history")
    suspend fun clearAllHistory()
}
