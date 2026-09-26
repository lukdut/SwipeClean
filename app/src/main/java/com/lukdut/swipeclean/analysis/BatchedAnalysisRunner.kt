package com.lukdut.swipeclean.analysis

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** Bounded queues: only [parallelism] decoders and one database writer are active. */
internal class BatchedAnalysisRunner(
    private val parallelism: Int = 2,
    private val batchSize: Int = 10,
    private val flushIntervalMillis: Long = 1_000
) {
    init {
        require(parallelism > 0 && batchSize > 0 && flushIntervalMillis > 0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T, R : Any> run(
        items: List<T>,
        shouldStop: () -> Boolean,
        analyze: suspend (T) -> R?,
        save: suspend (List<R>) -> Unit,
        onSkipped: () -> Unit
    ) = coroutineScope {
        val photos = Channel<T>(parallelism)
        val results = Channel<R?>(batchSize)
        val producers = launch {
            try {
                coroutineScope {
                    launch {
                        try {
                            for (photo in items) {
                                if (shouldStop()) break
                                photos.send(photo)
                            }
                        } finally {
                            photos.close()
                        }
                    }
                    repeat(parallelism) {
                        launch {
                            for (photo in photos) {
                                // Drain queued items on a normal pause, finishing only in-flight photos.
                                if (!shouldStop()) results.send(analyze(photo))
                            }
                        }
                    }
                }
            } finally {
                results.close()
            }
        }

        val batch = ArrayList<R>(batchSize)
        var firstResultAt = 0L
        var saveFailed = false

        suspend fun flush() {
            if (batch.isEmpty() || saveFailed) return
            // A committed transaction and its progress update must stay together during cancellation.
            withContext(NonCancellable) {
                try {
                    save(batch.toList())
                    batch.clear()
                } catch (e: Exception) {
                    saveFailed = true
                    throw e
                }
            }
        }

        suspend fun accept(result: R?) {
            if (result == null) {
                onSkipped()
            } else {
                if (batch.isEmpty()) firstResultAt = System.nanoTime()
                batch.add(result)
                if (batch.size >= batchSize) flush()
            }
        }

        try {
            while (true) {
                val received = if (batch.isEmpty()) {
                    results.receiveCatching()
                } else {
                    val remaining = flushIntervalMillis - (System.nanoTime() - firstResultAt) / 1_000_000
                    if (remaining <= 0) {
                        flush()
                        continue
                    }
                    // select avoids losing a received result in a timeout/receive race.
                    select<ChannelResult<R?>?> {
                        results.onReceiveCatching { it }
                        onTimeout(remaining) { null }
                    }
                }
                if (received == null) flush()
                else if (received.isClosed) break
                else accept(received.getOrThrow())
            }
        } finally {
            producers.cancel()
            photos.cancel()
            withContext(NonCancellable) {
                producers.join()
                if (!saveFailed) {
                    while (true) {
                        val queued = results.tryReceive()
                        if (queued.isFailure) break
                        accept(queued.getOrThrow())
                    }
                    flush()
                }
            }
            results.cancel()
        }
    }
}
