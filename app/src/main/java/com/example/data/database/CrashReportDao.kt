package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CrashReportDao {
    @Query("SELECT * FROM crash_reports ORDER BY timestamp DESC LIMIT 20")
    fun getLatestReports(): Flow<List<CrashReportEntity>>

    @Insert
    suspend fun insertReport(report: CrashReportEntity)

    @Query("DELETE FROM crash_reports")
    suspend fun clearReports()
}
