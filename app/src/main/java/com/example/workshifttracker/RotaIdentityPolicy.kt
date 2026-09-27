package com.example.workshifttracker

/**
 * v13 adaptive identity policy.
 *
 * Keeps identity decision math separate from bitmap/OCR code so it can be regression-tested on the
 * JVM. The policy becomes more conservative when the local model has known confusers and relaxes
 * slightly when several independent handwriting styles have been confirmed.
 */
object RotaIdentityPolicy {
    data class Boundary(
        val requiredSeparation: Float,
        val confuserPenalty: Float,
        val styleSupport: Float,
        val ambiguous: Boolean
    )

    fun boundary(styleCount: Int, confuserCount: Int, candidateNegativeSimilarity: Float): Boundary {
        val styles = styleCount.coerceAtLeast(0)
        val confusers = confuserCount.coerceAtLeast(0)
        val negative = candidateNegativeSimilarity.coerceIn(0f, 1f)
        val styleSupport = ((styles - 1).coerceAtLeast(0) * 0.008f).coerceAtMost(0.045f)
        val confuserLoad = (confusers / 10f).coerceIn(0f, 1f)
        val similarityPressure = ((negative - 0.48f) / 0.38f).coerceIn(0f, 1f)
        val required = (0.035f + confuserLoad * 0.022f + similarityPressure * 0.075f - styleSupport)
            .coerceIn(0.025f, 0.13f)
        val penalty = ((negative - 0.60f).coerceAtLeast(0f) * (0.38f + confuserLoad * 0.30f))
            .coerceAtMost(0.24f)
        return Boundary(
            requiredSeparation = required,
            confuserPenalty = penalty,
            styleSupport = styleSupport,
            ambiguous = negative >= 0.64f && confusers > 0
        )
    }
}
