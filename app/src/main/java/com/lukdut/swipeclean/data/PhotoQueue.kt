package com.lukdut.swipeclean.data

data class PhotoQueue(val photos: List<MediaPhoto> = emptyList(), val index: Int = 0) {
    val current: MediaPhoto? get() = photos.getOrNull(index)
    val next: MediaPhoto? get() = photos.getOrNull(index + 1)

    fun reorder(
        settings: PhotoSettings,
        quality: Map<Long, PhotoQuality>,
        preserveVisible: Boolean,
        personalScores: Map<Long, Float> = emptyMap()
    ): PhotoQueue {
        val fixedCount = (index + if (preserveVisible) 2 else 0).coerceAtMost(photos.size)
        val scores = if (settings.sortOrder == SortOrder.ByPotentiallyUnwanted) {
            photos.associate { it.id to UnwantedPhotoScorer.score(quality[it.id], personalScores[it.id] ?: 0f, settings).value }
        } else emptyMap()
        val comparator = when (settings.sortOrder) {
            SortOrder.ByDateDesc -> compareByDescending<MediaPhoto> { it.dateAdded }
            SortOrder.BySizeDesc -> compareByDescending<MediaPhoto> { it.size }
            SortOrder.ByPotentiallyUnwanted -> compareByDescending<MediaPhoto> { scores[it.id] ?: 0f }
        }.thenByDescending { it.dateAdded }.thenByDescending { it.id }
        return copy(photos = photos.take(fixedCount) + photos.drop(fixedCount).sortedWith(comparator))
    }

    fun advance(): PhotoQueue = copy(index = (index + 1).coerceAtMost(photos.size))

    fun without(ids: Set<Long>): PhotoQueue = PhotoQueue(
        photos = photos.filter { it.id !in ids },
        index = photos.take(index).count { it.id !in ids }
    )

    fun restore(restored: List<MediaPhoto>): PhotoQueue {
        val remaining = without(restored.map { it.id }.toSet())
        return remaining.copy(photos = remaining.photos + restored)
    }
}
