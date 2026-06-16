package com.example.lumen.data.dao

import androidx.room.*
import com.example.lumen.data.model.UserPrefs

@Dao
interface UserPrefsDao {

    @Query("SELECT * FROM user_prefs WHERE id = 1")
    suspend fun getPrefs(): UserPrefs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePrefs(prefs: UserPrefs)
}