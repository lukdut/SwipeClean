package com.lukdut.swipeclean.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lukdut.swipeclean.analysis.AnalysisCoordinator
import com.lukdut.swipeclean.analysis.AnalysisProgress
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.MediaStoreRepository
import com.lukdut.swipeclean.data.PhotoQuality
import com.lukdut.swipeclean.data.PhotoQueue
import com.lukdut.swipeclean.data.PhotoSettings
import com.lukdut.swipeclean.data.Priority
import com.lukdut.swipeclean.data.QualitySignal
import com.lukdut.swipeclean.data.SettingsRepository
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.data.model.EmbeddingModelRepository
import com.lukdut.swipeclean.data.model.ModelSpec
import com.lukdut.swipeclean.data.FeedbackExample
import com.lukdut.swipeclean.data.PersonalPhotoRanker
import com.lukdut.swipeclean.data.PhotoAnalyzer
import com.lukdut.swipeclean.data.PhotoEmbedding
import com.lukdut.swipeclean.data.PhotoFeedbackRepository
import com.lukdut.swipeclean.data.UnwantedPhotoScorer
import com.lukdut.swipeclean.data.ageDays
import com.lukdut.swipeclean.data.feedbackKey
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PRELOAD_AHEAD = 10

data class ProgressResetState(
    val running: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null
)

data class DeletePreparation(val running: Boolean = false, val completed: Int = 0, val total: Int = 0,
    val error: String? = null)

data class SwipeUiState(
    val currentPhoto: MediaPhoto? = null,
    val nextPhoto: MediaPhoto? = null,
    val progress: Pair<Int, Int> = 0 to 0,
    val isDone: Boolean = false,
    val sortOrder: SortOrder = SortOrder.default,
    val priorityReason: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val modelRepository = EmbeddingModelRepository.getInstance(application)
    val modelState = modelRepository.state
    private val activeModel = modelState.map { it.installed?.spec }.distinctUntilChanged()
    private val repository = MediaStoreRepository(application)
    private val database = AppDatabase.getInstance(application)
    private val dao = database.photoReviewDao()
    private val analysisDao = database.photoAnalysisDao()
    private val feedbackDao = database.photoFeedbackDao()
    private val feedbackRepository = PhotoFeedbackRepository(database)
    private val settingsRepository = SettingsRepository(
        application.getSharedPreferences("photo_settings", Context.MODE_PRIVATE)
    )
    private val reviewMutex = Mutex()
    // Apply in-session decisions even if a reload overlaps a pending database write.
    private val reviewOverrides = mutableMapOf<Long, PhotoReviewStatus?>()
    private val analysisResults = mutableMapOf<Long, PhotoQuality>()
    private var embeddings = emptyMap<Long, Pair<String, PhotoEmbedding>>()
    private var embeddingsVersion: String? = null
    private var feedbackVersion: String? = null
    private var feedbackExamples = emptyList<FeedbackExample>()
    private var personalScores = emptyMap<Long, Float>()
    private var personalScoresVersion: String? = null
    private var rankingJob: Job? = null
    private var deletePreparationJob: Job? = null
    private var loadJob: Job? = null
    private var photosLoaded = false
    private var loadGeneration = 0
    private var reviewVisible = false
    private var deleteRequestedPhotos: List<MediaPhoto> = emptyList()

    private val _settings = MutableStateFlow(settingsRepository.load())
    private val _allPhotos = MutableStateFlow<List<MediaPhoto>>(emptyList())
    private val _queue = MutableStateFlow(PhotoQueue())
    private val _markedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _isLoading = MutableStateFlow(false)
    private val _loadError = MutableStateFlow<String?>(null)
    private val _pendingDeleteSender = MutableStateFlow<IntentSender?>(null)
    private val _analysisProgress = MutableStateFlow(AnalysisProgress())
    private val _hasAnalysisResults = MutableStateFlow(false)
    private val _swipeUiState = MutableStateFlow(SwipeUiState(sortOrder = _settings.value.sortOrder))
    private val _progressReset = MutableStateFlow(ProgressResetState())
    private val _feedbackReset = MutableStateFlow(ProgressResetState())
    private val _feedbackCount = MutableStateFlow(0)
    private val _deletePreparation = MutableStateFlow(DeletePreparation())

    val settings: StateFlow<PhotoSettings> = _settings.asStateFlow()
    val analysisProgress: StateFlow<AnalysisProgress> = _analysisProgress.asStateFlow()
    val hasAnalysisResults: StateFlow<Boolean> = _hasAnalysisResults.asStateFlow()
    val swipeUiState: StateFlow<SwipeUiState> = _swipeUiState.asStateFlow()
    val progressReset: StateFlow<ProgressResetState> = _progressReset.asStateFlow()
    val feedbackReset: StateFlow<ProgressResetState> = _feedbackReset.asStateFlow()
    val feedbackCount: StateFlow<Int> = _feedbackCount.asStateFlow()
    val deletePreparation: StateFlow<DeletePreparation> = _deletePreparation.asStateFlow()
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

    init {
        viewModelScope.launch { modelRepository.initialize() }
        viewModelScope.launch {
            // Reconnect to saved results when a screen is recreated during background analysis.
            combine(analysisDao.observeAll(), _allPhotos, _isLoading, activeModel) { cached, photos, loading, model ->
                if (photosLoaded && !loading) {
                    applyCachedAnalysis(photos, cached, model)
                    refreshAnalysisProgress()
                    reorderQueue(preserveVisible = true)
                    schedulePersonalRanking()
                }
            }.collect { }
        }
        viewModelScope.launch {
            AnalysisCoordinator.progress.collect { refreshAnalysisProgress() }
        }
        viewModelScope.launch {
            combine(feedbackDao.observeAll(), activeModel) { rows, model -> rows to model }.collect { (rows, model) ->
                feedbackExamples = withContext(Dispatchers.Default) {
                    rows.mapNotNull { row ->
                        if (model == null || row.embeddingVersion != model.embeddingVersion) null
                        else PhotoEmbedding.decode(row.embedding, model.dimensions)?.let {
                            FeedbackExample(row.photoKey, it, row.decision, row.ageDays, row.decidedAt)
                        }
                    }
                }
                feedbackVersion = model?.embeddingVersion
                _feedbackCount.value = feedbackExamples.size
                // Keep the last completed ranking until its replacement is ready. Each swipe
                // writes feedback; clearing here would blink the caption during the debounce.
                schedulePersonalRanking()
            }
        }
    }

    fun loadPhotos() {
        if (_progressReset.value.running) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        AnalysisCoordinator.clearFinishedProgress()
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
                val cached = analysisDao.getAll()
                reviewOverrides.forEach { (id, status) ->
                    if (status == null) reviews.remove(id) else reviews[id] = status
                }
                // Missing media may simply be outside the user's current permission scope.
                _allPhotos.value = photos
                _markedIds.value = photos.mapNotNull { photo ->
                    photo.id.takeIf { reviews[it] == PhotoReviewStatus.TRASH }
                }.toSet()
                applyCachedAnalysis(photos, cached)
                _queue.value = PhotoQueue(photos.filter { it.id !in reviews })
                photosLoaded = true
                reorderQueue(preserveVisible = false)
                schedulePersonalRanking()
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

    fun setPersonalization(priority: Priority) {
        updateSettings(_settings.value.copy(personalization = priority))
        schedulePersonalRanking()
    }

    fun forgetFeedback() {
        if (_feedbackReset.value.running || _deletePreparation.value.running) return
        _feedbackReset.value = ProgressResetState(running = true)
        viewModelScope.launch {
            try {
                reviewMutex.withLock { feedbackDao.deleteAll() }
                rankingJob?.cancel()
                feedbackExamples = emptyList()
                personalScores = emptyMap()
                _feedbackCount.value = 0
                reorderQueue(preserveVisible = true)
                _feedbackReset.value = ProgressResetState(completed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _feedbackReset.value = ProgressResetState(error = "Не удалось очистить историю рекомендаций.")
            }
        }
    }

    fun resetProgress() {
        if (_isLoading.value || _progressReset.value.running || _pendingDeleteSender.value != null ||
            _deletePreparation.value.running) return
        _progressReset.value = ProgressResetState(running = true)
        _isLoading.value = true
        viewModelScope.launch {
            try {
                AnalysisCoordinator.pauseAndJoin()
                AnalysisCoordinator.clearFinishedProgress()
                applyCachedAnalysis(_allPhotos.value, analysisDao.getAll())
                // Wait for pending swipe writes before clearing the saved decisions.
                reviewMutex.withLock { feedbackRepository.resetProgress() }
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
    fun startAnalysis(updateModel: Boolean = false) {
        if (_isLoading.value || AnalysisCoordinator.progress.value?.running == true) return
        if (_deletePreparation.value.running || modelState.value.initializing || modelState.value.checking) return
        if (updateModel && modelState.value.available == null) return
        val candidates = _allPhotos.value
        val cached = if (embeddingsVersion == modelState.value.installed?.spec?.embeddingVersion)
            candidates.count { it.id in embeddings } else 0
        if (!updateModel && cached == candidates.size) return
        viewModelScope.launch {
            // Commit pending decisions before analysis starts enriching them.
            reviewMutex.withLock {
                if (!_isLoading.value) AnalysisCoordinator.start(getApplication(), candidates.size,
                    cached, updateModel, modelState.value.installed?.spec?.embeddingVersion)
            }
        }
    }

    fun checkModelUpdate() {
        if (modelState.value.checking || _analysisProgress.value.running || _deletePreparation.value.running) return
        viewModelScope.launch { modelRepository.checkForUpdate() }
    }

    fun pauseAnalysis() {
        AnalysisCoordinator.pause()
    }

    fun setReviewVisible(visible: Boolean) {
        reviewVisible = visible
    }

    private fun refreshAnalysisProgress() {
        val session = AnalysisCoordinator.progress.value
        if (!photosLoaded) {
            _analysisProgress.value = session ?: AnalysisProgress()
            return
        }
        _hasAnalysisResults.value = analysisResults.isNotEmpty()
        if (!_hasAnalysisResults.value && _settings.value.sortOrder == SortOrder.ByPotentiallyUnwanted) {
            setSortOrder(SortOrder.default)
        }
        // The service publishes committed counts before Room's observer catches up. Preserve those
        // counts for its model; an update cancelled after activation must use the new model's cache.
        if (session != null && (session.running ||
                session.modelVersion == modelState.value.installed?.spec?.embeddingVersion)) {
            _analysisProgress.value = session
            return
        }
        val pending = _allPhotos.value
        val cached = if (embeddingsVersion == modelState.value.installed?.spec?.embeddingVersion)
            pending.count { it.id in embeddings } else 0
        _analysisProgress.value = (session ?: AnalysisProgress()).copy(
            total = pending.size, analyzed = cached,
            skipped = (session?.skipped ?: 0).coerceAtMost(pending.size - cached))
    }

    private suspend fun applyCachedAnalysis(photos: List<MediaPhoto>, cached: List<PhotoAnalysisEntity>,
        model: ModelSpec? = modelState.value.installed?.spec) {
        val previous = embeddings.takeIf { embeddingsVersion == model?.embeddingVersion }.orEmpty()
        val (qualities, vectors) = withContext(Dispatchers.Default) {
            val byId = cached.associateBy { it.mediaStoreId }
            val quality = mutableMapOf<Long, PhotoQuality>()
            val vectors = mutableMapOf<Long, Pair<String, PhotoEmbedding>>()
            for (photo in photos) {
                ensureActive()
                val entry = byId[photo.id]?.takeIf { it.matches(photo) } ?: continue
                quality[photo.id] = entry.quality
                val key = photo.feedbackKey()
                val vector = if (model != null && entry.embeddingVersion == model.embeddingVersion)
                    previous[photo.id]?.takeIf { it.first == key }?.second ?: entry.currentEmbedding(model)
                else null
                if (vector != null) vectors[photo.id] = key to vector
            }
            quality to vectors
        }
        if (photos != _allPhotos.value || model != modelState.value.installed?.spec) return
        analysisResults.clear()
        analysisResults.putAll(qualities)
        embeddings = vectors
        embeddingsVersion = model?.embeddingVersion
    }

    private fun schedulePersonalRanking() {
        rankingJob?.cancel()
        val modelVersion = modelState.value.installed?.spec?.embeddingVersion
        if (_settings.value.personalization == Priority.OFF || feedbackExamples.isEmpty() || modelVersion == null ||
            embeddingsVersion != modelVersion || feedbackVersion != modelVersion) {
            personalScores = emptyMap()
            reorderQueue(preserveVisible = true)
            return
        }
        val examples = feedbackExamples
        val vectors = embeddings
        val photos = _allPhotos.value
        rankingJob = viewModelScope.launch {
            delay(150) // Merge fast swipes and database batches without doing work on the UI thread.
            val scores = withContext(Dispatchers.Default) {
                val ranker = PersonalPhotoRanker(examples)
                val now = System.currentTimeMillis()
                buildMap {
                    for (photo in photos) {
                        ensureActive()
                        val (key, embedding) = vectors[photo.id] ?: continue
                        put(photo.id, ranker.score(key, embedding, photo.ageDays(now)))
                    }
                }
            }
            if (modelState.value.installed?.spec?.embeddingVersion != modelVersion) return@launch
            personalScores = scores
            personalScoresVersion = modelVersion
            reorderQueue(preserveVisible = true)
        }
    }

    private fun currentPersonalScores(): Map<Long, Float> =
        if (personalScoresVersion == modelState.value.installed?.spec?.embeddingVersion) personalScores
        else emptyMap()

    private fun reorderQueue(preserveVisible: Boolean) {
        _queue.value = _queue.value.reorder(_settings.value, analysisResults, preserveVisible && reviewVisible,
            currentPersonalScores())
        publishSwipeState()
    }

    private fun publishSwipeState() {
        val queue = _queue.value
        val settings = _settings.value
        val reason = if (settings.sortOrder == SortOrder.ByPotentiallyUnwanted) {
            queue.current?.let {
                UnwantedPhotoScorer.score(analysisResults[it.id], currentPersonalScores()[it.id] ?: 0f, settings).reason
            }
        } else null
        // A single emission switches the photo and its caption together, including an absent caption.
        _swipeUiState.value = SwipeUiState(queue.current, queue.next, queue.index to queue.photos.size,
            queue.photos.isNotEmpty() && queue.index >= queue.photos.size, settings.sortOrder, reason)
    }

    fun markForDeletion() = reviewCurrent(PhotoReviewStatus.TRASH)
    fun keep() = reviewCurrent(PhotoReviewStatus.KEPT)

    private fun reviewCurrent(status: PhotoReviewStatus) {
        if (_isLoading.value || _deletePreparation.value.running) return
        val photo = _queue.value.current ?: return
        _progressReset.value = ProgressResetState()
        _feedbackReset.value = ProgressResetState()
        reviewOverrides[photo.id] = status
        viewModelScope.launch {
            reviewMutex.withLock { feedbackRepository.review(photo, status) }
        }
        if (status == PhotoReviewStatus.TRASH) _markedIds.update { it + photo.id }
        _queue.update { it.advance() }
        publishSwipeState()
    }

    fun restorePhoto(photoId: Long) = restore(setOf(photoId))
    fun restoreAll() = restore(_markedIds.value)

    private fun restore(ids: Set<Long>) {
        if (ids.isEmpty() || _isLoading.value || _deletePreparation.value.running || _pendingDeleteSender.value != null) return
        ids.forEach { reviewOverrides[it] = null }
        viewModelScope.launch { reviewMutex.withLock { feedbackRepository.restore(ids) } }
        _markedIds.update { it - ids }
        _queue.update { queue -> queue.restore(_allPhotos.value.filter { it.id in ids }) }
        reorderQueue(preserveVisible = true)
        if (!_analysisProgress.value.running) {
            AnalysisCoordinator.clearFinishedProgress()
            refreshAnalysisProgress()
        }
    }

    fun requestDelete(contentResolver: ContentResolver) {
        if (_isLoading.value || _deletePreparation.value.running || _pendingDeleteSender.value != null) return
        val photos = _allPhotos.value.filter { it.id in _markedIds.value }
        if (photos.isEmpty()) return
        _deletePreparation.value = DeletePreparation(running = true, total = photos.size)
        deletePreparationJob = viewModelScope.launch {
            try {
                AnalysisCoordinator.pauseAndJoin()
                // Finish pending review writes and capture features while the files still exist.
                reviewMutex.withLock { }
                try {
                    // Deletion must never trigger a model download or wait for the internet.
                    modelRepository.initialize()
                    modelState.value.installed?.let { model -> PhotoAnalyzer(getApplication(), model).use { analyzer ->
                        photos.forEachIndexed { index, photo ->
                            ensureActive()
                            val sample = feedbackDao.getByKey(photo.feedbackKey())
                            if (sample != null && (sample.embeddingVersion != model.spec.embeddingVersion ||
                                    PhotoEmbedding.decode(sample.embedding, model.spec.dimensions) == null)) {
                                analyzer.analyze(photo)?.let {
                                    feedbackRepository.saveAnalysis(listOf(PhotoAnalysisEntity.from(photo, it)))
                                }
                            }
                            _deletePreparation.update { it.copy(completed = index + 1) }
                        }
                    } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Recommendations must never prevent the user's explicit deletion.
                    Log.w("SwipeClean", "Could not capture deletion feedback", e)
                }
                ensureActive()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pendingIntent = android.provider.MediaStore.createDeleteRequest(contentResolver, photos.map { it.uri })
                    deleteRequestedPhotos = photos
                    _pendingDeleteSender.value = pendingIntent.intentSender
                } else {
                    val deletedIds = mutableSetOf<Long>()
                    try {
                        photos.forEach { photo ->
                            try {
                                if (withContext(Dispatchers.IO) { contentResolver.delete(photo.uri, null, null) } > 0)
                                    deletedIds.add(photo.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Keep unsuccessful deletions in the basket.
                            }
                        }
                    } finally {
                        removeDeleted(photos.filter { it.id in deletedIds })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _deletePreparation.update { it.copy(error = "Не удалось запросить удаление. Попробуйте ещё раз.") }
            } finally {
                _deletePreparation.update { it.copy(running = false) }
                AnalysisCoordinator.clearFinishedProgress()
                refreshAnalysisProgress()
            }
        }
    }

    fun cancelDeletePreparation() {
        deletePreparationJob?.cancel()
    }

    fun onDeleteCompleted() {
        removeDeleted(deleteRequestedPhotos)
        clearDeleteRequest()
    }

    private fun removeDeleted(deletedPhotos: List<MediaPhoto>) {
        if (deletedPhotos.isEmpty()) return
        val ids = deletedPhotos.map { it.id }.toSet()
        pauseAnalysis()
        ids.forEach {
            reviewOverrides[it] = null
            analysisResults.remove(it)
        }
        viewModelScope.launch {
            AnalysisCoordinator.pauseAndJoin()
            reviewMutex.withLock { feedbackRepository.deleted(deletedPhotos) }
            AnalysisCoordinator.clearFinishedProgress()
            refreshAnalysisProgress()
        }
        _markedIds.update { it - ids }
        _allPhotos.update { photos -> photos.filter { it.id !in ids } }
        _queue.update { it.without(ids) }
        publishSwipeState()
        refreshAnalysisProgress()
    }

    fun clearDeleteRequest() {
        deleteRequestedPhotos = emptyList()
        _pendingDeleteSender.value = null
    }
}
