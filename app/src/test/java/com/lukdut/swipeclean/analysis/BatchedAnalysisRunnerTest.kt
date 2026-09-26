package com.lukdut.swipeclean.analysis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchedAnalysisRunnerTest {
    @Test
    fun twoPhotosRunConcurrentlyAndEachIsSavedOnceInBatches() = runBlocking {
        withTimeout(5_000) {
            val bothStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var active = 0
            var peak = 0
            var writers = 0
            val batches = mutableListOf<List<Int>>()
            val job = launch {
                BatchedAnalysisRunner(flushIntervalMillis = 60_000).run(
                    items = (0 until 25).toList(), shouldStop = { false },
                    analyze = {
                        active++
                        peak = maxOf(peak, active)
                        if (active == 2) bothStarted.complete(Unit)
                        release.await()
                        delay(1)
                        active--
                        it
                    },
                    save = {
                        assertEquals(0, writers++)
                        delay(1)
                        batches += it
                        writers--
                    },
                    onSkipped = { error("Unexpected skipped photo") }
                )
            }
            bothStarted.await()
            assertEquals(2, active)
            release.complete(Unit)
            job.join()
            assertEquals(2, peak)
            assertEquals(listOf(10, 10, 5), batches.map { it.size })
            assertEquals((0 until 25).toList(), batches.flatten().sorted())
        }
    }

    @Test
    fun partialBatchIsSavedOnDeadlineWhileOtherPhotoIsStillProcessing() = runBlocking {
        withTimeout(5_000) {
            val releaseSecond = CompletableDeferred<Unit>()
            val firstSaved = CompletableDeferred<Unit>()
            val batches = mutableListOf<List<Int>>()
            val job = launch {
                BatchedAnalysisRunner(flushIntervalMillis = 30).run(
                    items = listOf(1, 2), shouldStop = { false },
                    analyze = { if (it == 2) releaseSecond.await(); it },
                    save = { batches += it; firstSaved.complete(Unit) },
                    onSkipped = {}
                )
            }
            firstSaved.await()
            assertTrue(job.isActive)
            assertEquals(listOf(listOf(1)), batches)
            releaseSecond.complete(Unit)
            job.join()
            assertEquals(listOf(listOf(1), listOf(2)), batches)
        }
    }

    @Test
    fun pauseFinishesInFlightPhotosAndFlushesPartialBatch() = runBlocking {
        withTimeout(5_000) {
            var stopped = false
            val started = mutableListOf<Int>()
            val bothStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val batches = mutableListOf<List<Int>>()
            val job = launch {
                BatchedAnalysisRunner().run(
                    items = (0 until 100).toList(), shouldStop = { stopped },
                    analyze = {
                        started += it
                        if (started.size == 2) bothStarted.complete(Unit)
                        release.await()
                        it
                    },
                    save = { batches += it }, onSkipped = {}
                )
            }
            bothStarted.await()
            stopped = true
            release.complete(Unit)
            job.join()
            assertEquals(listOf(0, 1), started)
            assertEquals(listOf(listOf(0, 1)), batches)
        }
    }

    @Test
    fun cancellationFlushesCompletedResults() = runBlocking {
        withTimeout(5_000) {
            val laterPhotoStarted = CompletableDeferred<Unit>()
            val saved = mutableListOf<Int>()
            val job = launch {
                BatchedAnalysisRunner(flushIntervalMillis = 60_000).run(
                    items = (0 until 100).toList(), shouldStop = { false },
                    analyze = {
                        if (it >= 3) {
                            laterPhotoStarted.complete(Unit)
                            awaitCancellation()
                        }
                        it
                    },
                    save = { saved += it }, onSkipped = {}
                )
            }
            laterPhotoStarted.await()
            job.cancelAndJoin()
            assertEquals(listOf(0, 1, 2), saved.sorted())
            assertFalse(job.isActive)
        }
    }

    @Test
    fun unreadablePhotosDoNotBlockOtherResults() = runBlocking {
        val saved = mutableListOf<Int>()
        var skipped = 0
        BatchedAnalysisRunner().run(
            items = (0 until 25).toList(), shouldStop = { false },
            analyze = { it.takeIf { it % 2 == 0 } },
            save = { saved += it }, onSkipped = { skipped++ }
        )
        assertEquals(12, skipped)
        assertEquals((0 until 25 step 2).toList(), saved.sorted())
    }
}
