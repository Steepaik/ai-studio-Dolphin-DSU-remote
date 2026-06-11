package com.example

import android.app.Application
import android.util.Log
import com.example.data.database.CrashReportEntity
import com.example.data.database.WiiControllerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.PrintWriter
import java.io.StringWriter

class WiiControllerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            val msg = throwable.message ?: "Unhandled Exception"

            Log.e("WiiApplication", "Global Uncaught Exception Handled: $msg")
            
            val db = WiiControllerDatabase.getDatabase(this)
            val job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    db.crashReportDao().insertReport(
                        CrashReportEntity(
                            exceptionMessage = msg,
                            stackTrace = stackTrace
                        )
                    )
                } catch (e: Exception) {
                    Log.e("WiiApplication", "Failed logging to Room: ${e.message}")
                }
            }
            
            runBlocking {
                try {
                    withTimeout(2000) {
                        job.join()
                    }
                } catch (timeout: Exception) {
                    Log.e("WiiApplication", "Database crash logging request timed out")
                }
            }
            
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
