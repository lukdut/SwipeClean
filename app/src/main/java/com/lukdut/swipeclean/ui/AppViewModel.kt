package com.lukdut.swipeclean.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.MediaStoreRepository
import com.lukdut.swipeclean.data.PhotoAnalyzer
import com.lukdut.swipeclean.data.PhotoQuality
import com.lukdut.swipeclean.data.PhotoQueue
import com.lukdut.swipeclean.data.PhotoSettings
import com.lukdut.swipeclean.data.Priority
import com.lukdut.swipeclean.data.QualitySignal
import com.lukdut.swipeclean.data.SettingsRepository
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import com.lukdut.swipeclean.data.db.PhotoReviewEntity
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PRELOAD_AHEAD = 10

/** Counts refer to the photos in the queue when the latest analysis pass started. */
data class AnalysisProgress(
    val total: Int = 0,
    val analyzed: Int = 0,
    val skipped: Int = 0,
    val running: Boolean = false,
    val error: String? = null
) {
    val remaining: Int get() = (total - analyzed - skipped).coerceAtLeast(0)
}

data class ProgressResetState(
    val running: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaStoreRepository(application)
    private val database = AppDatabase.getInstance(application)
    private val dao = database.photoReviewDao()
    private val analysisDao = database.photoAnalysisDao()
    private val analyzer = PhotoAnalyzer(application.contentResolver)
    private val settingsRepository = SettingsRepository(
        application.getSharedPreferences("photo_settings", Context.MODE_PRIVATE)
    )
    private val reviewMutex = Mutex()
    // Apply in-session decisions even if a reload overlaps a pending database write.
    private val reviewOverrides = mutableMapOf<Long, PhotoReviewStatus?>()
    private val analysisResults = mutableMapOf<Long, PhotoQuality>()
    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisGeneration = 0
    private var loadGeneration = 0
    private var reviewVisible = false
    private var deleteRequestedIds: Set<Long> = emptySet()

    private val _settings = MutableStateFlow(settingsRepository.load())
    private val _allPhotos = MutableStateFlow<List<MediaPhoto>>(emptyList())
    private val _queue = MutableStateFlow(PhotoQueue())
    private val _markedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _isLoading = MutableStateFlow(false)
    private val _loadError = MutableStateFlow<String?>(null)
    private val _pendingDeleteSender = MutableStateFlow<IntentSender?>(null)
    private val _analysisProgress = MutableStateFlow(AnalysisProgress())
    private val _hasAnalysisResults = MutableStateFlow(false)
    private val _priorityReason = MutableStateFlow<String?>(null)
    private val _progressReset = MutableStateFlow(ProgressResetState())

    val settings: StateFlow<PhotoSettings> = _settings.asStateFlow()
    val analysisProgress: StateFlow<AnalysisProgress> = _analysisProgress.asStateFlow()
    val hasAnalysisResults: StateFlow<Boolean> = _hasAnalysisResults.asStateFlow()
    val priorityReason: StateFlow<String?> = _priorityReason.asStateFlow()
    val progressReset: StateFlow<ProgressResetState> = _progressReset.asStateFlow()
    val sortOrder = _settings.map { it.sortOrder }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _settings.value.sortOrder)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    val pendingDeleteSender: StateFlow<IntentSender?> = _pendingDeleteSender.asStateFlow()
    val markedCount = _markedIds.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val currentPhoto = _queue.map { it.current }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val nextPhoto = _queue.map { it.next }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val photosToPreload = _queue.map { queue ->
        (1..PRELOAD_AHEAD).mapNotNull { queue.photos.getOrNull(queue.index + it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val markedPhotos = combine(_allPhotos, _markedIds) { photos, ids ->
        photos.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val swipeProgress = _queue.map { it.index to it.photos.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0 to 0)
    val isDone = _queue.map { it.photos.isNotEmpty() && it.index >= it.photos.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadPhotos() {
        if (_progressReset.value.running) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        pauseAnalysis()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            try {
                val photos = repository.loadAllPhotos()
                val reviews = reviewMutex.withLock {
                    val kept = dao.getIdsByStatus(PhotoReviewStatus.KEPT)
                    val trash = dao.getIdsByStatus(PhotoReviewStatus.TRASH)
                    (kept.associateWith { PhotoReviewStatus.KEPT } +
                        trash.associateWith { PhotoReviewStatus.TRASH }).toMutableMap()
                }
                val cached = analysisDao.getAll().associateBy { it.mediaStoreId }
                reviewOverrides.forEach { (id, status) ->
                    if (status == null) reviews.remove(id) else reviews[id] = status
                }
                // Missing media may simply be outside the user's current permission scope.
                _allPhotos.value = photos
                _markedIds.value = photos.mapNotNull { photo ->
                    photo.id.takeIf { reviews[it] == PhotoReviewStatus.TRASH }
                }.toSet()
                analysisResults.clear()
                photos.forEach { photo ->
                    cached[photo.id]?.takeIf { it.matches(photo) }?.let {
                        analysisResults[photo.id] = it.quality
                    }
                }
                _queue.value = PhotoQueue(photos.filter { it.id !in reviews })
                reorderQueue(preserveVisible = false)
                refreshAnalysisProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _loadError.value = "Не удалось загрузить фотографии. Проверьте доступ к галерее и попробуйте ещё раз."
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
            }
        }
    }

    fun setSortOrder(order: SortOrder) {
        if (order == SortOrder.ByPotentiallyUnwanted && !_hasAnalysisResults.value) return
        updateSettings(_settings.value.copy(sortOrder = order))
    }

    fun setPriority(signal: QualitySignal, priority: Priority) {
        updateSettings(_settings.value.withPriority(signal, priority))
    }

    fun resetProgress() {
        if (_isLoading.value || _progressReset.value.running || _pendingDeleteSender.value != null) return
        pauseAnalysis()
        _progressReset.value = ProgressResetState(running = true)
        _isLoading.value = true
        viewModelScope.launch {
            try {
                // Wait for pending swipe writes before clearing the saved decisions.
                reviewMutex.withLock { dao.deleteAll() }
                reviewOverrides.clear()
                _markedIds.value = emptySet()
                _queue.value = PhotoQueue(_allPhotos.value)
                reorderQueue(preserveVisible = false)
                refreshAnalysisProgress()
                _progressReset.value = ProgressResetState(completed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _progressReset.value = ProgressResetState(
                    error = "Не удалось сбросить прогресс. Попробуйте ещё раз."
                )
            } finally {
                _progressReset.update { it.copy(running = false) }
                _isLoading.value = false
            }
        }
    }

    private fun updateSettings(settings: PhotoSettings) {
        if (_settings.value == settings) return
        _settings.value = settings
        settingsRepository.save(settings)
        reorderQueue(preserveVisible = false)
    }

    /** Only a user action starts analysis. Changing sort order, loading or resuming never does. */
    fun startAnalysis() {
        if (_isLoading.value || analysisJob?.isActive == true) return
        val candidates = _queue.value.let { it.photos.drop(it.index) }
        if (candidates.isEmpty()) return
        val missing = candidates.filter { it.id !in analysisResults }
        _analysisProgress.value = AnalysisProgress(
            total = candidates.size,
            analyzed = candidates.size - missing.size,
            running = missing.isNotEmpty()
        )
        if (missing.isEmpty()) return
        val generation = ++analysisGeneration
        analysisJob = viewModelScope.launch {
            try {
                missing.forEachIndexed { index, photo ->
                    val quality = analyzer.analyze(photo)
                    if (quality != null) {
                        analysisDao.upsert(PhotoAnalysisEntity.from(photo, quality))
                        analysisResults[photo.id] = quality
                        _hasAnalysisResults.value = true
                        _analysisProgress.update { it.copy(analyzed = it.analyzed + 1) }
                    } else {
                        _analysisProgress.update { it.copy(skipped = it.skipped + 1) }
                    }
                    // Batch sorting; the front and back cards stay fixed while the user swipes.
                    if ((index + 1) % 16 == 0 || index == missing.lastIndex) {
                        reorderQueue(preserveVisible = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _analysisProgress.update { it.copy(error = "Не удалось завершить анализ. Можно попробовать снова.") }
            } finally {
                if (generation == analysisGeneration) {
                    _analysisProgress.update { it.copy(running = false) }
                    reorderQueue(preserveVisible = true)
                }
            }
        }
    }

    fun pauseAnalysis() {
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        _analysisProgress.update { it.copy(running = false) }
        reorderQueue(preserveVisible = true)
    }

    fun setReviewVisible(visible: Boolean) {
        reviewVisible = visible
    }

    private fun refreshAnalysisProgress() {
        _hasAnalysisResults.value = analysisResults.isNotEmpty()
        if (!_hasAnalysisResults.value && _settings.value.sortOrder == SortOrder.ByPotentiallyUnwanted) {
            setSortOrder(SortOrder.default)
        }
        val pending = _queue.value.let { it.photos.drop(it.index) }
        _analysisProgress.value = AnalysisProgress(
            total = pending.size,
            analyzed = pending.count { it.id in analysisResults }
        )
    }

    private fun reorderQueue(preserveVisible: Boolean) {
        _queue.value = _queue.value.reorder(_settings.value, analysisResults, preserveVisible && reviewVisible)
        updatePriorityReason()
    }

    private fun updatePriorityReason() {
        _priorityReason.value = if (_settings.value.sortOrder == SortOrder.ByPotentiallyUnwanted) {
            _queue.value.current?.let { analysisResults[it.id]?.reason(_settings.value) }
        } else null
    }

    fun markForDeletion() = reviewCurrent(PhotoReviewStatus.TRASH)
    fun keep() = reviewCurrent(PhotoReviewStatus.KEPT)

    private fun reviewCurrent(status: PhotoReviewStatus) {
        if (_isLoading.value) return
        val photo = _queue.value.current ?: return
        _progressReset.value = ProgressResetState()
        reviewOverrides[photo.id] = status
        viewModelScope.launch {
            reviewMutex.withLock { dao.upsert(PhotoReviewEntity(photo.id, status)) }
        }
        if (status == PhotoReviewStatus.TRASH) _markedIds.update { it + photo.id }
        _queue.update { it.advance() }
        updatePriorityReason()
    }

    fun restorePhoto(photoId: Long) = restore(setOf(photoId))
    fun restoreAll() = restore(_markedIds.value)

    private fun restore(ids: Set<Long>) {
        if (ids.isEmpty() || _isLoading.value) return
        ids.forEach { reviewOverrides[it] = null }
        viewModelScope.launch { reviewMutex.withLock { dao.deleteByIds(ids) } }
        _markedIds.update { it - ids }
        _queue.update { queue -> queue.restore(_allPhotos.value.filter { it.id in ids }) }
        reorderQueue(preserveVisible = true)
        if (!_analysisProgress.value.running) refreshAnalysisProgress()
    }

    fun requestDelete(contentResolver: ContentResolver) {
        if (_isLoading.value) return
        val photos = _allPhotos.value.filter { it.id in _markedIds.value }
        if (photos.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = android.provider.MediaStore.createDeleteRequest(contentResolver, photos.map { it.uri })
            deleteRequestedIds = photos.map { it.id }.toSet()
            _pendingDeleteSender.value = pendingIntent.intentSender
        } else {
            viewModelScope.launch {
                val deletedIds = mutableSetOf<Long>()
                photos.forEach { photo ->
                    try {
                        if (contentResolver.delete(photo.uri, null, null) > 0) deletedIds.add(photo.id)
                    } catch (_: Exception) {
                        // Keep unsuccessful deletions in the basket.
                    }
                }
                removeDeleted(deletedIds)
            }
        }
    }

    fun onDeleteCompleted() {
        removeDeleted(deleteRequestedIds)
        clearDeleteRequest()
    }

    private fun removeDeleted(ids: Set<Long>) {
        if (ids.isEmpty()) return
        pauseAnalysis()
        ids.forEach {
            reviewOverrides[it] = null
            analysisResults.remove(it)
        }
        viewModelScope.launch {
            reviewMutex.withLock { dao.deleteByIds(ids) }
            analysisDao.deleteByIds(ids)
        }
        _markedIds.update { it - ids }
        _allPhotos.update { photos -> photos.filter { it.id !in ids } }
        _queue.update { it.without(ids) }
        updatePriorityReason()
        refreshAnalysisProgress()
    }

    fun clearDeleteRequest() {
        deleteRequestedIds = emptySet()
        _pendingDeleteSender.value = null
    }
}
