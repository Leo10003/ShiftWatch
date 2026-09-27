package com.example.workshifttracker

import org.junit.Assert.assertTrue
import org.junit.Test

class RotaIdentityPolicyTest {
    @Test
    fun knownConfusersDemandMoreSeparation() {
        val clean = RotaIdentityPolicy.boundary(styleCount = 3, confuserCount = 0, candidateNegativeSimilarity = 0.30f)
        val difficult = RotaIdentityPolicy.boundary(styleCount = 3, confuserCount = 12, candidateNegativeSimilarity = 0.78f)
        assertTrue(difficult.requiredSeparation > clean.requiredSeparation)
        assertTrue(difficult.confuserPenalty > clean.confuserPenalty)
        assertTrue(difficult.ambiguous)
    }

    @Test
    fun diverseConfirmedStylesProvideMeasuredSupport() {
        val sparse = RotaIdentityPolicy.boundary(styleCount = 1, confuserCount = 4, candidateNegativeSimilarity = 0.55f)
        val diverse = RotaIdentityPolicy.boundary(styleCount = 8, confuserCount = 4, candidateNegativeSimilarity = 0.55f)
        assertTrue(diverse.styleSupport > sparse.styleSupport)
        assertTrue(diverse.requiredSeparation <= sparse.requiredSeparation)
    }
}
