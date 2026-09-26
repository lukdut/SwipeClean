package com.lukdut.swipeclean.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PhotoReviewEntity::class, PhotoAnalysisEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoReviewDao(): PhotoReviewDao
    abstract fun photoAnalysisDao(): PhotoAnalysisDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "swipeclean.db"
                ).build().also { INSTANCE = it }
            }
    }
}
