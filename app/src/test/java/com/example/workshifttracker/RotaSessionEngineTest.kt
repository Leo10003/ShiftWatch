package com.example.workshifttracker

import org.junit.Assert.*
import org.junit.Test

class RotaSessionEngineTest {
    private fun auto(id: String, day: Int, slot: String = "$day-$id") =
        RotaSessionEngine.Candidate(id, day, slot, RotaSessionEngine.Provenance.ANALYZER)
    @Test fun multipleShiftsSameDayWithDifferentPhysicalBlocksSurvive() {
        val start = RotaSessionEngine.begin("imageA", 1)
        val after = RotaSessionEngine.merge(start, "imageA", 1,
            listOf(auto("a", 2, "wed-block1"), auto("b", 2, "wed-block3")))
        assertEquals(2, after.candidates.size)
        assertTrue(after.candidates.none { it.selected })
    }
    @Test fun userDecisionsOutrankLateAndRepeatedAnalysis() {
        val start = RotaSessionEngine.merge(RotaSessionEngine.begin("a", 1), "a", 1,
            listOf(auto("m", 0, "mon-block1")))
        val confirmed = RotaSessionEngine.decide(start, start.candidates.single().copy(selected = true))
        val result = RotaSessionEngine.merge(confirmed, "a", 1,
            listOf(auto("wrong", 0, "mon-block1"), auto("also-wrong", 0, "mon-block2"), auto("t", 1, "tue-block1")))
        assertEquals(listOf("m", "t"), result.candidates.map { it.id })
        assertTrue(result.candidates.first().selected)
    }
    @Test fun removeAndRemoveAllPreventLateResurrection() {
        val s = RotaSessionEngine.merge(RotaSessionEngine.begin("a", 1), "a", 1,
            listOf(auto("t", 1, "tue-block1"), auto("w", 2, "wed-block1")))
        val removed = RotaSessionEngine.remove(s, "t")
        assertEquals(listOf("w"), RotaSessionEngine.merge(removed, "a", 1, listOf(auto("t2", 1, "tue-block1"))).candidates.map { it.id })
        assertTrue(RotaSessionEngine.merge(RotaSessionEngine.removeAll(s), "a", 1, listOf(auto("z", 5))).candidates.isEmpty())
    }
    @Test fun cancelledAndOldGenerationsCannotMutateNextPhoto() {
        val next = RotaSessionEngine.begin("new", 3)
        assertEquals(next, RotaSessionEngine.merge(next, "old", 2, listOf(auto("stale", 0))))
        val stopped = RotaSessionEngine.stop(next)
        assertEquals(stopped, RotaSessionEngine.merge(stopped, "new", 3, listOf(auto("late", 0))))
    }
    @Test fun confirmingWeekDoesNotConfirmNamesOrTimes() {
        val state = RotaSessionEngine.confirmWeek(RotaSessionEngine.begin("a", 1))
        assertTrue(state.weekConfirmed)
        assertTrue(state.candidates.isEmpty())
    }
}
