package com.lukdut.swipeclean.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashReviewTest {
    @Test fun trashTimeSurvivesForgettingFeedbackAndChangesWhenAddedAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            val photo = MediaPhoto(1, Uri.parse("content://media/external/images/media/1"), "test", 100, 1234, 200)
            repository.review(photo, PhotoReviewStatus.TRASH, 1_000)
            db.photoFeedbackDao().deleteAll()
            assertEquals(1_000L, db.photoReviewDao().getAll().single().reviewedAt)

            repository.restore(listOf(photo.id))
            assertTrue(db.photoReviewDao().getAll().isEmpty())
            repository.review(photo, PhotoReviewStatus.TRASH, 2_000)
            assertEquals(2_000L, db.photoReviewDao().getAll().single().reviewedAt)
        } finally {
            db.close()
        }
    }
}
