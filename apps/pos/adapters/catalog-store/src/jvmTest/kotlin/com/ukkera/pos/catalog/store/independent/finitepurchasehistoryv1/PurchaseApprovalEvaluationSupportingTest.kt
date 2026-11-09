package com.ukkera.pos.catalog.store.independent.finitepurchasehistoryv1

import com.ukkera.pos.catalog.store.isApprovalEvaluationCurrent
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Frozen FPH v1.0.0 supporting evaluation-interval checks only.
 * The unchanged original two-CARTON/twelve-EACH fixture is an inert order carrier,
 * not a successful approval, retained final decision or qualified native history.
 */
class PurchaseApprovalEvaluationSupportingTest {
    @Test
    fun fph001002OriginalEvaluationIncludesTStartAndTMid() {
        assertOriginalEvaluation("T-START", 1894701600000L, expectedCurrent = true)
        assertOriginalEvaluation("T-MID", 1894701610000L, expectedCurrent = true)
    }

    @Test
    fun fph001002OriginalEvaluationIncludesTLast() {
        assertOriginalEvaluation("T-LAST", 1894701659998L, expectedCurrent = true)
    }

    @Test
    fun fph002OriginalEvaluationExcludesTBeforeTEvalEndAndTEvalLate() {
        assertOriginalEvaluation("T-BEFORE", 1894701599999L, expectedCurrent = false)
        assertOriginalEvaluation("T-EVAL-END", 1894701660000L, expectedCurrent = false)
        assertOriginalEvaluation("T-EVAL-LATE", 1894701660001L, expectedCurrent = false)
    }

    private fun assertOriginalEvaluation(variant: String, evaluationMs: Long, expectedCurrent: Boolean) {
        val order = FinitePurchaseHistoryPlannerInputs.originalOrder()
        assertEquals(1894701600000L, order.validFrom.value.toEpochMilliseconds(), "$variant original validFrom")
        assertEquals(1894701660000L, checkNotNull(order.validUntil).value.toEpochMilliseconds(), "$variant original validUntil")
        val evaluatedAt = UtcInstant(Instant.fromEpochMilliseconds(evaluationMs))
        assertEquals(expectedCurrent, order.isApprovalEvaluationCurrent(evaluatedAt), "$variant evaluation interval only")
    }
}
