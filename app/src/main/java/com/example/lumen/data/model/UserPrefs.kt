package com.example.lumen.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_prefs")
data class UserPrefs(
    @PrimaryKey val id: Int = 1,
    val topics: String = "",     // comma-separated: "Technology,World,Science"
    val sources: String = "",
    val showBiasMeter: Boolean = true,
    val notificationsEnabled: Boolean = false
)