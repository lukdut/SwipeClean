package com.lukdut.swipeclean.data

enum class SortOrder(val label: String) {
    ByDateDesc("По дате (новые)"),
    BySizeDesc("По размеру (большие)"),
    ByPotentiallyUnwanted("Неудачные");

    companion object {
        val all: List<SortOrder> = entries
        val default: SortOrder = ByDateDesc
    }
}
