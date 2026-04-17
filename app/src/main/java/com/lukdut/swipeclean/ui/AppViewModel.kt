package com.lukdut.swipeclean.ui

import android.app.Application
import android.content.ContentResolver
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.MediaStoreRepository
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoReviewEntity
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PRELOAD_AHEAD = 3

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaStoreRepository(application)
    private val dao = AppDatabase.getInstance(application).photoReviewDao()

    private val _allPhotos = MutableStateFlow<List<MediaPhoto>>(emptyList())
    private val _photos = MutableStateFlow<List<MediaPhoto>>(emptyList())
    private val _currentIndex = MutableStateFlow(0)
    private val _markedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _isLoading = MutableStateFlow(false)
    private val _pendingDeleteSender = MutableStateFlow<IntentSender?>(null)
    private val _sortOrder = MutableStateFlow(SortOrder.default)

    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val pendingDeleteSender: StateFlow<IntentSender?> = _pendingDeleteSender.asStateFlow()

    val markedCount: StateFlow<Int> = _markedIds
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val currentPhoto: StateFlow<MediaPhoto?> = combine(_photos, _currentIndex) { photos, index ->
        photos.getOrNull(index)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val nextPhoto: StateFlow<MediaPhoto?> = combine(_photos, _currentIndex) { photos, index ->
        photos.getOrNull(index + 1)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Next [PRELOAD_AHEAD] photos after the current one, used for background image caching. */
    val photosToPreload: StateFlow<List<MediaPhoto>> = combine(_photos, _currentIndex) { photos, index ->
        (1..PRELOAD_AHEAD).mapNotNull { offset -> photos.getOrNull(index + offset) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val markedPhotos: StateFlow<List<MediaPhoto>> = combine(_allPhotos, _markedIds) { photos, ids ->
        photos.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val swipeProgress: StateFlow<Pair<Int, Int>> = combine(_photos, _currentIndex) { photos, index ->
        index to photos.size
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0 to 0)

    val isDone: StateFlow<Boolean> = combine(_photos, _currentIndex) { photos, index ->
        photos.isNotEmpty() && index >= photos.size
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadPhotos() {
        viewModelScope.launch {
            _isLoading.value = true

            val allPhotos = repository.loadAllPhotos(_sortOrder.value)
            val allIds = allPhotos.map { it.id }
            _allPhotos.value = allPhotos

            // Remove DB entries for photos that no longer exist in MediaStore
            if (allIds.isNotEmpty()) {
                dao.deleteOrphans(allIds)
            }

            val keptIds = dao.getIdsByStatus(PhotoReviewStatus.KEPT).toSet()
            val trashIds = dao.getIdsByStatus(PhotoReviewStatus.TRASH).toSet()

            // Filter out already-reviewed photos; trash photos are shown in basket, not in swipe queue
            _photos.value = allPhotos.filter { it.id !in keptIds && it.id !in trashIds }
            _markedIds.value = trashIds.intersect(allIds.toSet()) // restore only existing photos
            _currentIndex.value = 0

            _isLoading.value = false
        }
    }

    fun setSortOrder(order: SortOrder) {
        if (_sortOrder.value == order) return
        _sortOrder.value = order
        loadPhotos()
    }

    fun markForDeletion() {
        val photo = _photos.value.getOrNull(_currentIndex.value) ?: return
        viewModelScope.launch {
            dao.upsert(PhotoReviewEntity(photo.id, PhotoReviewStatus.TRASH))
        }
        _markedIds.update { it + photo.id }
        _currentIndex.update { it + 1 }
    }

    fun keep() {
        val photo = _photos.value.getOrNull(_currentIndex.value) ?: return
        viewModelScope.launch {
            dao.upsert(PhotoReviewEntity(photo.id, PhotoReviewStatus.KEPT))
        }
        _currentIndex.update { it + 1 }
    }

    fun restorePhoto(photoId: Long) {
        viewModelScope.launch {
            dao.deleteById(photoId)
        }
        _markedIds.update { it - photoId }
    }

    fun restoreAll() {
        viewModelScope.launch {
            dao.deleteAllByStatus(PhotoReviewStatus.TRASH)
        }
        _markedIds.value = emptySet()
    }

    fun requestDelete(contentResolver: ContentResolver) {
        val uris = markedPhotos.value.map { it.uri }
        if (uris.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
            _pendingDeleteSender.value = pendingIntent.intentSender
        } else {
            viewModelScope.launch {
                val deletedIds = mutableSetOf<Long>()
                markedPhotos.value.forEach { photo ->
                    try {
                        val deleted = contentResolver.delete(photo.uri, null, null)
                        if (deleted > 0) deletedIds.add(photo.id)
                    } catch (_: Exception) {
                    }
                }
                dao.deleteByIds(deletedIds)
                _markedIds.update { it - deletedIds }
                _allPhotos.update { photos -> photos.filter { it.id !in deletedIds } }
                _photos.update { photos -> photos.filter { it.id !in deletedIds } }
            }
        }
    }

    fun onDeleteCompleted() {
        val deletedIds = _markedIds.value
        viewModelScope.launch {
            dao.deleteByIds(deletedIds)
        }
        _markedIds.value = emptySet()
        _allPhotos.update { photos -> photos.filter { it.id !in deletedIds } }
        _photos.update { photos -> photos.filter { it.id !in deletedIds } }
        _pendingDeleteSender.value = null
        _currentIndex.update { index ->
            val newSize = _photos.value.size
            if (index > newSize) newSize else index
        }
    }

    fun clearDeleteRequest() {
        _pendingDeleteSender.value = null
    }
}
