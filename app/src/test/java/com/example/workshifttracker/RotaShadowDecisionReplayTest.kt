package com.example.workshifttracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaShadowDecisionReplayTest {
    private fun c(score: Float, negative: Float = .5f, separation: Float = score-negative,
                  trimmed: Boolean = false) = RotaShadowDecisionReplay.Candidate(score, negative, separation, 2, trimmed)
    private fun week(saturday: RotaShadowDecisionReplay.Candidate) = listOf(
        RotaShadowDecisionReplay.Column(0,.55f,listOf(c(.692f),c(.441f))),
        RotaShadowDecisionReplay.Column(1,.55f,listOf(c(.716f),c(.404f))),
        RotaShadowDecisionReplay.Column(2,.55f,listOf(c(.747f),c(.436f))),
        RotaShadowDecisionReplay.Column(3,.55f,listOf(c(.543f,.732f,.543f-.732f),c(.525f))),
        RotaShadowDecisionReplay.Column(4,.55f,listOf(c(.438f,.60f,-.162f),c(.426f))),
        RotaShadowDecisionReplay.Column(5,.55f,listOf(saturday,c(.423f))),
        RotaShadowDecisionReplay.Column(6,.55f,listOf(c(.709f),c(.42f))))

    @Test fun referenceSimulationPreservesFridayAndRaisesSaturdayOnlyWhenQualified() {
        val original = RotaShadowDecisionReplay.replay(week(c(.496f,.604f,-.108f)),5,3)
        val refined = RotaShadowDecisionReplay.replay(week(c(.668f,.612f,.056f,true)),5,3)
        assertFalse(original[5].accepted)
        assertTrue(refined[5].accepted)
        assertTrue(refined[5].selectedTrim)
        assertEquals(.668f,refined[5].best ?: 0f,.00001f)
        assertEquals(.245f,refined[5].margin ?: 0f,.00001f)
        assertEquals("accepted_normal",refined[5].status)
        assertEquals("rejected_below_rescue_floor",refined[4].status)
        assertFalse(refined[4].accepted)
        for (day in listOf(0,1,2,3,4,6)) assertEquals(original[day].status,refined[day].status)
    }

    @Test fun highScoreWithoutAdequateRunnerMarginCanStillBeRejected() {
        val changed = week(c(.668f,.612f,.056f,true)).toMutableList()
        changed[5] = changed[5].copy(floor=.60f, candidates=listOf(c(.668f,.612f,.056f,true),c(.661f)))
        val results = RotaShadowDecisionReplay.replay(changed,5,3)
        assertEquals("rejected_insufficient_runner_margin",results[5].status)
    }

    @Test fun weeklyRescueIsLimitedAndRequiresConfuserSeparation() {
        val changed=week(c(.496f,.40f,.096f)).toMutableList()
        changed[5] = changed[5].copy(floor=.60f, candidates=listOf(c(.525f,.40f,.125f),c(.42f)))
        assertEquals("accepted_weekly_rescue",RotaShadowDecisionReplay.replay(changed,5,3)[5].status)
        changed[5] = changed[5].copy(candidates=listOf(c(.525f,.50f,.025f),c(.42f)))
        assertFalse(RotaShadowDecisionReplay.replay(changed,5,3)[5].accepted)
    }
}
