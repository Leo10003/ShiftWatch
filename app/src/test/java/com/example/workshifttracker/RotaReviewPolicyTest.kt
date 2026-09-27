package com.example.workshifttracker

import org.junit.Assert.*
import org.junit.Test

class RotaReviewPolicyTest {
    @Test fun weekConfirmationNeverApprovesAnUnknownNameOrTime() {
        val p = RotaReviewPolicy
        assertEquals(RotaReviewPolicy.Status.CHECK_NAME, p.status(RotaReviewPolicy.Evidence(false, true, true, selected = true)))
        assertEquals(RotaReviewPolicy.Status.CHECK_TIME, p.status(RotaReviewPolicy.Evidence(true, true, false, selected = true)))
        assertEquals(RotaReviewPolicy.Status.CHECK_DATE, p.status(RotaReviewPolicy.Evidence(true, false, true, selected = true)))
    }
    @Test fun explicitSelectionAndIndependentEvidenceRequiredForReady() {
        val p = RotaReviewPolicy
        assertEquals(RotaReviewPolicy.Status.REVIEW, p.status(RotaReviewPolicy.Evidence(true, true, true)))
        assertTrue(p.canImport(RotaReviewPolicy.Evidence(true, true, true, selected = true)))
        assertEquals(RotaReviewPolicy.Status.CONFLICT, p.status(RotaReviewPolicy.Evidence(true, true, true, true, true)))
    }
}
