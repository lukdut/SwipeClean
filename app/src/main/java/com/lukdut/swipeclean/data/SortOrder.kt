package com.lukdut.swipeclean.data

import android.provider.MediaStore

sealed class SortOrder(
    val label: String,
    private val column: String,
    private val ascending: Boolean = false
) {
    val sqlOrder: String get() = "$column ${if (ascending) "ASC" else "DESC"}"

    data object ByDateDesc : SortOrder(
        label = "По дате (новые)",
        column = MediaStore.Images.Media.DATE_ADDED,
        ascending = false
    )

    data object BySizeDesc : SortOrder(
        label = "По размеру (большие)",
        column = MediaStore.Images.Media.SIZE,
        ascending = false
    )

    companion object {
        val all: List<SortOrder> = listOf(ByDateDesc, BySizeDesc)
        val default: SortOrder = ByDateDesc
    }
}
