package com.example.workshifttracker

/** Independent evidence gates: a good time does not prove a date or employee identity. */
internal object RotaReviewPolicy {
    enum class Status { CHECK_NAME, CHECK_DATE, CHECK_TIME, CONFLICT, REVIEW, READY }
    data class Evidence(
        val identityConfirmed: Boolean,
        val dateConfirmed: Boolean,
        val timeConfirmed: Boolean,
        val conflicting: Boolean = false,
        val selected: Boolean = false
    )
    fun status(e: Evidence): Status = when {
        e.conflicting -> Status.CONFLICT
        !e.identityConfirmed -> Status.CHECK_NAME
        !e.dateConfirmed -> Status.CHECK_DATE
        !e.timeConfirmed -> Status.CHECK_TIME
        e.selected -> Status.READY
        else -> Status.REVIEW
    }
    fun canImport(e: Evidence): Boolean = status(e) == Status.READY
}
