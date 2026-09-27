package com.lukdut.swipeclean.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoQueueTest {
    private val photos = (1L..6L).map { MediaPhoto(it, Uri.EMPTY, "$it", 10 - it, it * 100) }
    private val smart = PhotoSettings(sortOrder = SortOrder.ByPotentiallyUnwanted)
    private val quality = mapOf(6L to PhotoQuality(0f, 1f, 0f, 0f), 5L to PhotoQuality(0.7f, 0f, 0f, 0f))

    @Test
    fun personalPreferencesReorderPendingPhotosAndProtectVisibleCards() {
        val reordered = PhotoQueue(photos).reorder(smart, emptyMap(), preserveVisible = true,
            personalScores = mapOf(6L to 0.8f, 3L to -0.5f))
        assertEquals(listOf(1L, 2L, 6L, 4L, 5L, 3L), reordered.photos.map { it.id })
        assertEquals(photos, PhotoQueue(photos).reorder(smart.copy(personalization = Priority.OFF),
            emptyMap(), false, mapOf(6L to 0.8f)).photos)
    }

    @Test
    fun analysisKeepsReviewedAndVisibleCardsInPlace() {
        val reordered = PhotoQueue(photos, index = 1).reorder(smart, quality, preserveVisible = true)
        assertEquals(listOf(1L, 2L, 3L, 6L, 5L, 4L), reordered.photos.map { it.id })
        assertEquals(2L, reordered.current?.id)
        assertEquals(3L, reordered.next?.id)
    }

    @Test
    fun explicitSortChangeKeepsReviewedPhotosOutOfThePendingQueue() {
        val reordered = PhotoQueue(photos, index = 2).reorder(smart, quality, preserveVisible = false)
        assertEquals(listOf(1L, 2L, 6L, 5L, 3L, 4L), reordered.photos.map { it.id })
        assertEquals(2, reordered.index)
    }

    @Test
    fun unknownPhotosAndDisabledSignalsFallBackToDate() {
        val settings = smart.copy(blur = Priority.OFF, dark = Priority.OFF)
        val reordered = PhotoQueue(photos.reversed()).reorder(settings, quality, preserveVisible = false)
        assertEquals(photos, reordered.photos)
    }

    @Test
    fun sizeSortWorksWithoutAnalysis() {
        val reordered = PhotoQueue(photos).reorder(
            PhotoSettings(sortOrder = SortOrder.BySizeDesc), emptyMap(), false
        )
        assertEquals(photos.reversed(), reordered.photos)
    }

    @Test
    fun deletingReviewedPhotosDoesNotSkipTheCurrentPhoto() {
        val queue = PhotoQueue(photos, index = 3).without(setOf(1L, 3L))
        assertEquals(4L, queue.current?.id)
        assertEquals(1, queue.index)
    }

    @Test
    fun restoringReviewedPhotosAddsThemBackOnlyOnce() {
        val queue = PhotoQueue(photos, index = 3).restore(listOf(photos[0], photos[2]))
        assertEquals(4L, queue.current?.id)
        assertEquals(listOf(2L, 4L, 5L, 6L, 1L, 3L), queue.photos.map { it.id })
    }

    @Test
    fun undoReturnsThePhotoBeforeTheCurrentCardAfterSorting() {
        val queue = PhotoQueue(photos, index = 2).reorder(smart, quality, preserveVisible = false)
        val undone = queue.returnTo(photos[1])
        assertEquals(photos[1], undone.current)
        assertEquals(queue.current, undone.next)
        assertEquals(1, undone.index)
        assertEquals(photos.size, undone.photos.size)
        assertEquals(undone.photos.size, undone.photos.map { it.id }.distinct().size)
    }

    @Test
    fun undoWorksAfterTheLastPhotoAndAfterReloadingTheQueue() {
        val completed = PhotoQueue(photos, index = photos.size).returnTo(photos.last())
        assertEquals(photos.last(), completed.current)
        assertEquals(photos.size - 1, completed.index)
        val reloaded = PhotoQueue(photos.drop(2)).returnTo(photos[1])
        assertEquals(photos[1], reloaded.current)
        assertEquals(photos[2], reloaded.next)
        assertEquals(0, reloaded.index)
    }
}
