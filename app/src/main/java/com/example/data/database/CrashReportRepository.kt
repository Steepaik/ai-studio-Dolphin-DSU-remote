package com.example.data.database

import kotlinx.coroutines.flow.Flow

class CrashReportRepository(private val dao: CrashReportDao) {
    val latestReports: Flow<List<CrashReportEntity>> = dao.getLatestReports()

    suspend fun insert(report: CrashReportEntity) {
        dao.insertReport(report)
    }

    suspend fun clear() {
        dao.clearReports()
    }
}
