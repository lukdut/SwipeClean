package com.lukdut.swipeclean.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoReviewEntity::class, PhotoAnalysisEntity::class, PhotoFeedbackEntity::class],
    version = 4,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoReviewDao(): PhotoReviewDao
    abstract fun photoAnalysisDao(): PhotoAnalysisDao
    abstract fun photoFeedbackDao(): PhotoFeedbackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo_analysis ADD COLUMN embedding BLOB DEFAULT NULL")
                db.execSQL("ALTER TABLE photo_analysis ADD COLUMN embeddingVersion TEXT DEFAULT NULL")
                db.execSQL("""CREATE TABLE IF NOT EXISTS photo_feedback (
                    photoKey TEXT NOT NULL PRIMARY KEY, mediaStoreId INTEGER NOT NULL,
                    decision TEXT NOT NULL, embedding BLOB, embeddingVersion TEXT,
                    ageDays REAL, decidedAt INTEGER NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photo_feedback_mediaStoreId ON photo_feedback (mediaStoreId)")
                // Existing explicit decisions can be enriched on the next manual analysis.
                // Their age at decision is unknown; do not infer it from today's date.
                db.execSQL("""INSERT INTO photo_feedback
                    SELECT a.mediaStoreId || ':' || a.uri || ':' || a.dateAdded || ':' ||
                        a.dateModified || ':' || a.size, a.mediaStoreId, r.status,
                        NULL, NULL, NULL, 0
                    FROM photo_review r JOIN photo_analysis a ON a.mediaStoreId = r.mediaStoreId""")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo_review ADD COLUMN reviewedAt INTEGER NOT NULL DEFAULT 0")
                // Recover decision times where feedback history is still available.
                db.execSQL("""UPDATE photo_review SET reviewedAt = COALESCE((
                    SELECT MAX(decidedAt) FROM photo_feedback
                    WHERE photo_feedback.mediaStoreId = photo_review.mediaStoreId
                        AND photo_feedback.decision = photo_review.status
                ), 0)""")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "swipeclean.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
