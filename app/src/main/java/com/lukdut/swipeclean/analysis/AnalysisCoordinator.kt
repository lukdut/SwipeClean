package com.lukdut.swipeclean.analysis

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.lukdut.swipeclean.data.model.ModelDownloadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-local control only. PhotoAnalysisService owns the work; Room owns saved results. */
@MainThread
object AnalysisCoordinator {
    private val _progress = MutableStateFlow<AnalysisProgress?>(null)
    val progress = _progress.asStateFlow()
    private var nextRequest = 0L
    private var request: Long? = null
    private var job: Job? = null

    fun start(context: Context, total: Int, cached: Int, updateModel: Boolean = false, modelVersion: String? = null) {
        if (request != null) return
        val id = ++nextRequest
        request = id
        _progress.value = AnalysisProgress(total = total, analyzed = cached, running = true, preparing = true,
            modelVersion = modelVersion)
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, PhotoAnalysisService::class.java)
                    .setAction(PhotoAnalysisService.ACTION_START)
                    .putExtra(PhotoAnalysisService.EXTRA_UPDATE_MODEL, updateModel)
                    .putExtra(PhotoAnalysisService.EXTRA_REQUEST, id))
        } catch (e: RuntimeException) {
            Log.e("AnalysisCoordinator", "Cannot start photo analysis service", e)
            finish(id, "Не удалось запустить анализ. Откройте приложение и попробуйте ещё раз.")
        }
    }

    fun pause() {
        val id = request ?: return
        _progress.update { it?.copy(stopping = true) }
        // There are no photo results to flush during preparation. Cancel download/validation too.
        if (_progress.value?.preparing == true) job?.cancel()
        // A request can be paused before Android has delivered it to the service.
        if (job == null) finish(id)
    }

    suspend fun pauseAndJoin() {
        val activeJob = job
        pause()
        activeJob?.join()
    }

    fun clearFinishedProgress() {
        if (request == null) _progress.value = null
    }

    internal fun isRequested(id: Long) = request == id

    internal fun attach(id: Long, activeJob: Job) {
        check(request == id && job == null)
        job = activeJob
    }

    internal fun shouldStop(id: Long) = request != id || _progress.value?.stopping == true

    internal fun prepared(id: Long, total: Int, cached: Int, modelVersion: String) {
        if (request == id) _progress.update {
            it?.copy(total = total, analyzed = cached, preparing = false, modelDownload = null,
                modelVersion = modelVersion)
        }
    }

    internal fun downloading(id: Long, progress: ModelDownloadProgress) {
        if (request == id) _progress.update { it?.copy(modelDownload = progress) }
    }

    internal fun saved(id: Long, count: Int) {
        if (request == id) _progress.update { it?.copy(analyzed = it.analyzed + count) }
    }

    internal fun skipped(id: Long) {
        if (request == id) _progress.update { it?.copy(skipped = it.skipped + 1) }
    }

    internal fun finish(id: Long, error: String? = null) {
        if (request != id) return
        job = null
        request = null
        _progress.update { it?.copy(running = false, stopping = false, preparing = false, modelDownload = null,
            error = error ?: it.error) }
    }
}
