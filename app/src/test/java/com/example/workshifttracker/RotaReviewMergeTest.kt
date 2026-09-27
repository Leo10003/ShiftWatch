package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RotaReviewMergeTest {
    private data class Candidate(val id: String, val day: LocalDate)
    private val mon = LocalDate.of(2026, 9, 21)
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)

    @Test fun lateResultsCannotOverwriteManuallyEditedDays() {
        val user = Candidate("manual-monday-0930", mon)
        val result = RotaReviewMerge.merge(
            listOf(user), listOf(Candidate("wrong-auto-monday", mon), Candidate("tuesday", tue)),
            setOf(mon), Candidate::day
        )
        assertEquals(listOf(user, Candidate("tuesday", tue)), result)
    }

    @Test fun removedDaysRemainTombstonedWhileScanning() {
        val result = RotaReviewMerge.merge(
            emptyList(), listOf(Candidate("deleted-tuesday", tue), Candidate("wednesday", wed)),
            setOf(tue), Candidate::day
        )
        assertEquals(listOf(Candidate("wednesday", wed)), result)
    }

    @Test fun duplicateAnalysisPassesOnlyInsertOneSuggestionPerDay() {
        val incoming = listOf(Candidate("ocr1", tue), Candidate("ocr2", tue))
        val once = RotaReviewMerge.merge(emptyList(), incoming, emptySet(), Candidate::day)
        val twice = RotaReviewMerge.merge(once, incoming, emptySet(), Candidate::day)
        assertEquals(listOf(Candidate("ocr1", tue)), twice)
    }

    @Test fun newImageWithNewStateCanAcceptPreviouslyDeletedDate() {
        val result = RotaReviewMerge.merge(
            emptyList(), listOf(Candidate("new-photo-tuesday", tue)), emptySet(), Candidate::day
        )
        assertEquals(1, result.size)
    }
    @Test fun lateGenerationAfterCancelOrSaveDoesNotApply() {
        assertEquals(false, RotaReviewMerge.isCurrent(3, 4, true))
        assertEquals(false, RotaReviewMerge.isCurrent(3, 3, false))
        assertEquals(true, RotaReviewMerge.isCurrent(3, 3, true))
    }
    @Test fun wrongFallbackWeekCannotDuplicateSameManualWeekday() {
        val badDecember = LocalDate.of(2024, 12, 30) // Monday
        val printedSeptember = LocalDate.of(2026, 9, 21) // Monday
        val manual = Candidate("person-corrected", badDecember)
        val result = RotaReviewMerge.merge(
            listOf(manual), listOf(Candidate("late-wrong-extra", printedSeptember)),
            setOf(badDecember.dayOfWeek), { it.day.dayOfWeek }
        )
        assertEquals(listOf(manual), result)
    }
    @Test fun twoDifferentBlocksOnOneDaySurviveLateScanWithoutDuplicating() {
        data class Slot(val id: String, val day: LocalDate, val block: Int)
        val incoming = listOf(Slot("w09", wed, 1), Slot("w16", wed, 3))
        val once = RotaReviewMerge.mergeBySlot(emptyList(), incoming, emptySet(), Slot::day) { "${it.day.dayOfWeek}-${it.block}" }
        val twice = RotaReviewMerge.mergeBySlot(once, incoming, emptySet(), Slot::day) { "${it.day.dayOfWeek}-${it.block}" }
        assertEquals(incoming, twice)
        val blocked = RotaReviewMerge.mergeBySlot(emptyList(), incoming, setOf(wed), Slot::day) { it.block }
        assertEquals(emptyList<Slot>(), blocked)
    }
}
