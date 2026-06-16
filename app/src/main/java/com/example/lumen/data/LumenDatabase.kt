package com.example.lumen.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.lumen.data.dao.ArticleDao
import com.example.lumen.data.dao.StoryDao
import com.example.lumen.data.dao.UserPrefsDao
import com.example.lumen.data.model.Article
import com.example.lumen.data.model.UserPrefs
import com.example.lumen.data.model.Story

@Database(
    entities = [Article::class, UserPrefs::class, Story::class],
    version = 1,
    exportSchema = false
)
abstract class LumenDatabase : RoomDatabase() {

    abstract fun articleDao(): ArticleDao
    abstract fun userPrefsDao(): UserPrefsDao
    abstract fun storyDao(): StoryDao

    companion object {
        @Volatile
        private var INSTANCE: LumenDatabase? = null

        fun getInstance(context: Context): LumenDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    LumenDatabase::class.java,
                    "lumen_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}