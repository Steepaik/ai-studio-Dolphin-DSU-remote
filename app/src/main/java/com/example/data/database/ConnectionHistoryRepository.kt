package com.example.data.database

import kotlinx.coroutines.flow.Flow

class ConnectionHistoryRepository(private val dao: ConnectionHistoryDao) {
    val allHistory: Flow<List<ConnectionHistoryEntity>> = dao.getAllHistory()

    suspend fun insert(history: ConnectionHistoryEntity) {
        dao.insertHistory(history)
    }

    suspend fun deleteByAddress(ip: String, port: Int) {
        dao.deleteHistoryByAddress(ip, port)
    }

    suspend fun clearHistory() {
        dao.clearAllHistory()
    }
}
