package com.lukdut.swipeclean.data

enum class DeletionMode(val label: String, val description: String) {
    SYSTEM_TRASH(
        "Системная корзина",
        "Фото можно восстановить через галерею с поддержкой системной корзины. Срок хранения задаёт Android. Место освободится после окончательного удаления."
    ),
    PERMANENT(
        "Удалять навсегда",
        "Фото удаляются сразу, без возможности восстановления через корзину."
    )
}
