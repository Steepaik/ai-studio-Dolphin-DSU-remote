package com.example.data.database

import kotlinx.coroutines.flow.Flow

class GameProfileRepository(private val dao: GameProfileDao) {
    val allProfiles: Flow<List<GameProfileEntity>> = dao.getAllProfiles()

    suspend fun insert(profile: GameProfileEntity) {
        dao.insertProfile(profile)
    }

    suspend fun delete(name: String) {
        dao.deleteProfile(name)
    }

    suspend fun getCount(): Int {
        return dao.getCount()
    }
}
