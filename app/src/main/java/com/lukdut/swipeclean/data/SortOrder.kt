package com.lukdut.swipeclean.data

enum class SortOrder(val label: String, val groupLabel: String? = null) {
    ByDateDesc("Сначала новые", "По дате"),
    ByDateAsc("Сначала старые", "По дате"),
    BySizeDesc("Сначала большие", "По размеру"),
    BySizeAsc("Сначала маленькие", "По размеру"),
    ByPotentiallyUnwanted("Неудачные");

    companion object {
        val all: List<SortOrder> = entries
        val groups: Map<String?, List<SortOrder>> = entries.groupBy { it.groupLabel }
        val default: SortOrder = ByDateDesc
    }
}
