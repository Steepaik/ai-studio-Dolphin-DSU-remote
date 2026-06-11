package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ConnectionHistoryEntity::class], version = 1, exportSchema = false)
abstract class WiiControllerDatabase : RoomDatabase() {
    abstract fun connectionHistoryDao(): ConnectionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: WiiControllerDatabase? = null

        fun getDatabase(context: Context): WiiControllerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WiiControllerDatabase::class.java,
                    "wii_controller_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
