package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GameProfileDao {
    @Query("SELECT * FROM game_profiles")
    fun getAllProfiles(): Flow<List<GameProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: GameProfileEntity)

    @Query("DELETE FROM game_profiles WHERE name = :name")
    suspend fun deleteProfile(name: String)

    @Query("SELECT COUNT(*) FROM game_profiles")
    suspend fun getCount(): Int
}
