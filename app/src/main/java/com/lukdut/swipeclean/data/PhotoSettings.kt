package com.lukdut.swipeclean.data

enum class Priority(val label: String, val weight: Float) {
    OFF("Выкл.", 0f), NORMAL("Обычный", 1f), HIGH("Высокий", 2f)
}

enum class QualitySignal(val label: String, val description: String, val reason: String) {
    BLUR("Размытие", "Кадры, на которых почти нет чётких границ.", "Возможно размытие"),
    DARK("Тёмные кадры", "Большая часть изображения почти чёрная.", "Очень тёмный кадр"),
    BRIGHT("Пересвет", "Большая часть изображения близка к белому.", "Возможен пересвет"),
    LOW_DETAIL("Мало деталей", "Почти однородные кадры, например случайное фото стены.", "Мало деталей")
}

data class PhotoSettings(
    val sortOrder: SortOrder = SortOrder.default,
    val blur: Priority = Priority.NORMAL,
    val dark: Priority = Priority.NORMAL,
    val bright: Priority = Priority.NORMAL,
    val lowDetail: Priority = Priority.NORMAL,
    val personalization: Priority = Priority.NORMAL
) {
    val hasEnabledSignals: Boolean get() = personalization != Priority.OFF ||
        QualitySignal.entries.any { priority(it) != Priority.OFF }

    fun priority(signal: QualitySignal): Priority = when (signal) {
        QualitySignal.BLUR -> blur
        QualitySignal.DARK -> dark
        QualitySignal.BRIGHT -> bright
        QualitySignal.LOW_DETAIL -> lowDetail
    }

    fun withPriority(signal: QualitySignal, priority: Priority): PhotoSettings = when (signal) {
        QualitySignal.BLUR -> copy(blur = priority)
        QualitySignal.DARK -> copy(dark = priority)
        QualitySignal.BRIGHT -> copy(bright = priority)
        QualitySignal.LOW_DETAIL -> copy(lowDetail = priority)
    }
}
