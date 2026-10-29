package com.lukdut.swipeclean.ui

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lukdut.swipeclean.analysis.AnalysisCoordinator
import com.lukdut.swipeclean.data.DeletionMode
import com.lukdut.swipeclean.data.FeedbackDecision
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.PhotoSettings
import com.lukdut.swipeclean.data.SettingsRepository
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import com.lukdut.swipeclean.data.feedbackKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class DeletionFlowTest {
    @Test
    fun firstUseAndCancellationPreserveThePhotoAndPendingReview() = Fixture().use { fixture ->
        val viewModel = fixture.viewModel
        assertEquals(DeletionMode.SYSTEM_TRASH, viewModel.settings.value.deletionMode)
        assertFalse(viewModel.settings.value.deletionModeConfirmed)

        fixture.onMain { viewModel.requestDelete(fixture.resolver) }
        assertTrue(viewModel.deletionModePrompt.value)
        assertFalse(viewModel.deletePreparation.value.running)
        assertNull(viewModel.pendingDeleteSender.value)
        fixture.assertStillPending()

        fixture.onMain { viewModel.dismissDeletionModePrompt() }
        assertFalse(viewModel.deletionModePrompt.value)
        assertFalse(SettingsRepository(fixture.preferences).load().deletionModeConfirmed)
        fixture.assertStillPending()

        fixture.onMain {
            viewModel.requestDelete(fixture.resolver)
            viewModel.confirmDeletionMode(DeletionMode.SYSTEM_TRASH, fixture.resolver)
        }
        fixture.awaitRequest()
        assertFalse(viewModel.deletionModePrompt.value)
        assertTrue(SettingsRepository(fixture.preferences).load().deletionModeConfirmed)
        // This is the same callback used when Android's confirmation is cancelled.
        fixture.onMain { viewModel.clearDeleteRequest() }
        fixture.assertStillPending()

        fixture.reopen()
        assertTrue(fixture.viewModel.settings.value.deletionModeConfirmed)
        assertEquals(DeletionMode.SYSTEM_TRASH, fixture.viewModel.settings.value.deletionMode)
        fixture.onMain { fixture.viewModel.requestDelete(fixture.resolver) }
        fixture.awaitRequest()
        assertFalse(fixture.viewModel.deletionModePrompt.value)
        fixture.onMain { fixture.viewModel.clearDeleteRequest() }
        fixture.assertStillPending()
    }

    @Test
    fun systemTrashRetainsTheFileAndExternalRestoreReturnsItToReview() = Fixture().use { fixture ->
        val viewModel = fixture.viewModel
        fixture.onMain {
            viewModel.requestDelete(fixture.resolver)
            viewModel.confirmDeletionMode(DeletionMode.SYSTEM_TRASH, fixture.resolver)
        }
        fixture.acceptSystemRequest { fixture.trashedState() == 1 }
        fixture.onMain { viewModel.onDeleteCompleted() }
        await {
            fixture.feedbackDecision() == FeedbackDecision.SYSTEM_TRASH &&
                viewModel.markedPhotos.value.none { it.id == fixture.photo.id }
        }
        assertEquals(1, fixture.trashedState())
        assertTrue(runBlocking {
            fixture.db.photoReviewDao().getAll().none { it.mediaStoreId == fixture.photo.id }
        })
        assertNull(runBlocking { fixture.db.photoAnalysisDao().getById(fixture.photo.id) })

        // The fixture belongs to this app, so it can emulate a restore performed by a gallery.
        assertEquals(1, fixture.resolver.update(fixture.uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) },
            Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }))
        fixture.onMain { viewModel.loadPhotos() }
        await {
            !viewModel.isLoading.value && fixture.feedbackDecision() == null &&
                viewModel.currentPhoto.value?.id == fixture.photo.id
        }
        assertEquals(0, fixture.trashedState())
    }

    @Test
    fun lostSystemCallbackCanBeRecovered() = Fixture().use { fixture ->
        fixture.onMain {
            fixture.viewModel.requestDelete(fixture.resolver)
            fixture.viewModel.confirmDeletionMode(DeletionMode.SYSTEM_TRASH, fixture.resolver)
        }
        fixture.acceptSystemRequest { fixture.trashedState() == 1 }
        // Simulate losing the process after Android moved the photo but before its result was
        // handled. The database still has the provisional review, and the old callback never runs.
        assertEquals(FeedbackDecision.TRASH, fixture.feedbackDecision())
        fixture.reopen(expectMarked = false)
        await { fixture.feedbackDecision() == FeedbackDecision.SYSTEM_TRASH }
        assertTrue(fixture.viewModel.markedPhotos.value.none { it.id == fixture.photo.id })
        assertTrue(runBlocking {
            fixture.db.photoReviewDao().getAll().none { it.mediaStoreId == fixture.photo.id }
        })
        assertEquals(1, fixture.trashedState())

        assertEquals(1, fixture.resolver.update(fixture.uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) },
            Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }))
        fixture.onMain { fixture.viewModel.loadPhotos() }
        await {
            !fixture.viewModel.isLoading.value && fixture.feedbackDecision() == null &&
                fixture.viewModel.currentPhoto.value?.id == fixture.photo.id
        }
        assertEquals(0, fixture.trashedState())
    }

    @Test
    fun explicitPermanentModeDeletesOnlyTheGeneratedFixture() = Fixture().use { fixture ->
        fixture.onMain {
            fixture.viewModel.setDeletionMode(DeletionMode.PERMANENT)
            fixture.viewModel.requestDelete(fixture.resolver)
        }
        assertFalse(fixture.viewModel.deletionModePrompt.value)
        fixture.acceptSystemRequest { fixture.trashedState() == null }
        fixture.onMain { fixture.viewModel.onDeleteCompleted() }
        await { fixture.feedbackDecision() == FeedbackDecision.DELETED }
        assertNull(fixture.trashedState())
        assertEquals(DeletionMode.PERMANENT, SettingsRepository(fixture.preferences).load().deletionMode)
    }

    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val application = instrumentation.targetContext.applicationContext as Application
        val resolver = application.contentResolver
        val preferences = application.getSharedPreferences("photo_settings", Context.MODE_PRIVATE)
        val db = AppDatabase.getInstance(application)
        private val originalPreferences = preferences.all.toMap()
        private val originalTrashReviews = runBlocking {
            db.photoReviewDao().getAll().filter { it.status == PhotoReviewStatus.TRASH }
        }
        private var store: ViewModelStore? = null
        val uri: Uri
        val photo: MediaPhoto
        lateinit var viewModel: AppViewModel

        init {
            instrumentation.uiAutomation.grantRuntimePermission(application.packageName,
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE)
            runBlocking {
                withContext(Dispatchers.Main) {
                    AnalysisCoordinator.pauseAndJoin()
                    AnalysisCoordinator.clearFinishedProgress()
                }
                // Preserve preexisting marks, and ensure no system request can include them.
                db.photoReviewDao().deleteByIds(originalTrashReviews.map { it.mediaStoreId })
            }
            SettingsRepository(preferences).save(PhotoSettings())
            uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "deletion-flow-${System.nanoTime()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwipeCleanDeletionFlowTest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                checkNotNull(resolver.openOutputStream(uri)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            open()
            await { viewModel.currentPhoto.value != null }
            photo = checkNotNull(viewModel.currentPhoto.value)
            check(photo.id == ContentUris.parseId(uri)) { "Expected only the newly generated fixture" }
            onMain { viewModel.markForDeletion() }
            await { viewModel.markedPhotos.value.map { it.id } == listOf(photo.id) }
            assertStillPending()
        }

        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }

        private fun open() {
            onMain {
                val newStore = ViewModelStore()
                store = newStore
                viewModel = ViewModelProvider(newStore,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(application))[AppViewModel::class.java]
                // No app Activity is visible, so automatic gallery analysis cannot start.
                viewModel.loadPhotos()
            }
            await { !viewModel.isLoading.value && !viewModel.modelState.value.initializing }
            assertNull(viewModel.loadError.value)
        }

        fun reopen(expectMarked: Boolean = true) {
            onMain { store?.clear(); store = null }
            open()
            await { viewModel.markedPhotos.value.any { it.id == photo.id } == expectMarked }
        }

        fun awaitRequest() = await {
            assertNull(viewModel.deletePreparation.value.error)
            viewModel.pendingDeleteSender.value != null && !viewModel.deletePreparation.value.running
        }

        fun acceptSystemRequest(completed: () -> Boolean) {
            awaitRequest()
            check(viewModel.markedPhotos.value.map { it.id } == listOf(photo.id)) {
                "Refusing to launch a system request for anything except the generated fixture"
            }
            // ui-test-manifest already supplies this empty Activity in the debug APK. Android's
            // media confirmation requires a result caller, but no application screen is needed.
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.startIntentSenderForResult(checkNotNull(viewModel.pendingDeleteSender.value),
                        100, null, 0, 0, 0)
                }
                var accepted = false
                await {
                    if (completed()) true
                    else {
                        if (!accepted) {
                            val root = instrumentation.uiAutomation.rootInActiveWindow?.takeIf {
                                it.packageName?.toString()?.contains("providers.media") == true
                            }
                            val button = root?.let(::findAllowButton)
                            if (button != null) accepted = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        false
                    }
                }
            }
        }

        private fun findAllowButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val id = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty()
            if (node.isClickable && node.isEnabled &&
                (id.endsWith(":id/button1") || id.endsWith(":id/allow_button") ||
                    text.lowercase() in setOf("allow", "move to trash", "move to bin", "delete", "разрешить",
                        "переместить в корзину", "удалить"))) return node
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child -> findAllowButton(child)?.let { return it } }
            }
            return null
        }

        fun trashedState(): Int? = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED),
            Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else null }

        fun feedbackDecision(): FeedbackDecision? = runBlocking {
            db.photoFeedbackDao().getByKey(photo.feedbackKey())?.decision
        }

        fun assertStillPending() {
            assertEquals(0, trashedState())
            await { feedbackDecision() == FeedbackDecision.TRASH }
            assertTrue(runBlocking {
                db.photoReviewDao().getAll().any {
                    it.mediaStoreId == photo.id && it.status == PhotoReviewStatus.TRASH
                }
            })
        }

        override fun close() {
            onMain { store?.clear(); store = null }
            runBlocking {
                db.photoReviewDao().deleteById(photo.id)
                db.photoAnalysisDao().deleteByIds(listOf(photo.id))
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM photo_feedback WHERE mediaStoreId = ?", arrayOf(photo.id))
                originalTrashReviews.forEach { db.photoReviewDao().upsert(it) }
            }
            // Only our synthetic file is ever permanently removed during cleanup.
            try {
                if (trashedState() != null) {
                    resolver.delete(uri, Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    })
                }
            } finally {
                preferences.edit().apply {
                    clear()
                    originalPreferences.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }.commit()
            }
        }
    }

    companion object {
        private fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for deletion flow" }
                SystemClock.sleep(25)
            }
        }
    }
}
