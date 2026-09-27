package com.lukdut.swipeclean.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lukdut.swipeclean.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun upgradePreservesQualityAndCreatesPendingFeedbackForExistingDecisions() {
        helper.createDatabase("feedback-migration-test", 2).apply {
            execSQL("INSERT INTO photo_review VALUES (1, 'KEPT')")
            execSQL("INSERT INTO photo_analysis VALUES (1, 'content://media/1', 100, 200, 1234, 1, 0.2, 0.0, 0.0, 0.0)")
            close()
        }
        helper.runMigrationsAndValidate("feedback-migration-test", 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT blur, embedding, embeddingVersion FROM photo_analysis").use {
                assertTrue(it.moveToFirst())
                assertEquals(0.2f, it.getFloat(0), 0.001f)
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }
            db.query("SELECT photoKey, decision, ageDays FROM photo_feedback").use {
                assertTrue(it.moveToFirst())
                assertEquals("1:content://media/1:100:200:1234", it.getString(0))
                assertEquals("KEPT", it.getString(1))
                assertTrue(it.isNull(2))
            }
        }
    }
}
