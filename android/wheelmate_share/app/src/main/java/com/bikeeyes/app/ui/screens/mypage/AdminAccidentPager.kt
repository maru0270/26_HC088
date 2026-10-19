package com.bikeeyes.app.ui.screens.mypage

import com.bikeeyes.app.AdminAccidentRecord

internal enum class AdminAccidentFilter(val label: String) {
    ALL("전체"), ACCIDENT("사고"), SAFE("괜찮아요"), NO_RESPONSE("무응답");

    fun matches(event: AdminAccidentRecord): Boolean = when (this) {
        ALL -> true
        SAFE -> event.isSafeResponse()
        ACCIDENT -> !event.isSafeResponse() &&
            event.confirmation_status.uppercase() in setOf("ACCIDENT", "CONFIRMED", "EMERGENCY")
        NO_RESPONSE -> !event.isSafeResponse() &&
            event.confirmation_status.uppercase() in setOf("NO_RESPONSE", "TIMEOUT", "UNRESPONDED")
    }

    companion object {
        fun fromRoute(value: String): AdminAccidentFilter =
            entries.firstOrNull { it.name == value.uppercase() } ?: ALL
    }
}

internal fun AdminAccidentRecord.isSafeResponse(): Boolean =
    is_false_positive || confirmation_status.uppercase() in
        setOf("SAFE", "OK", "CANCELLED", "FALSE_POSITIVE")

internal fun AdminAccidentRecord.responseLabel(): String = when {
    isSafeResponse() -> "괜찮아요"
    AdminAccidentFilter.ACCIDENT.matches(this) -> "사고입니다"
    AdminAccidentFilter.NO_RESPONSE.matches(this) -> "30초 무응답"
    else -> confirmation_status
}

/** 서버에서 받은 해당 주행의 사고만 사용. 주행/필터 변경 시 새 상태로 초기화. */
internal data class AdminAccidentPager private constructor(
    val events: List<AdminAccidentRecord>,
    val index: Int = 0
) {
    val current: AdminAccidentRecord? get() = events.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < events.lastIndex

    fun previous(): AdminAccidentPager = if (hasPrevious) copy(index = index - 1) else this
    fun next(): AdminAccidentPager = if (hasNext) copy(index = index + 1) else this

    companion object {
        fun create(
            events: List<AdminAccidentRecord>,
            filter: AdminAccidentFilter,
            initialEventUuid: String? = null
        ): AdminAccidentPager {
            val filteredEvents = events.filter(filter::matches)
                .sortedWith(compareByDescending<AdminAccidentRecord> { it.detected_at }
                    .thenBy { it.event_uuid })
            val initialIndex = filteredEvents.indexOfFirst {
                it.event_uuid == initialEventUuid
            }.takeIf { it >= 0 } ?: 0
            return AdminAccidentPager(filteredEvents, initialIndex)
        }
    }
}
