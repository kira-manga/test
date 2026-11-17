package com.ukkera.pos.sales.store

import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** SELF-AUTHORED, NOT independent. financial-original-source-shared-budget-v1.0.1.
 * Request accounting controls only; these do not establish any original or authority. */
class FinancialOriginalSourceBudgetSupportingTest {
    @Test fun existingFoodEnvelopeIsRetainedUntilAnActuallyStricterSubject() {
        val budget = FinancialOriginalSourceBudget()
        budget.tighten(FoodReadLimits.REQUEST_BYTES, FoodReadLimits.REQUEST_PRODUCERS)
        budget.charge(32L * 1_024 * 1_024)
        repeat(257) { budget.original(OperationId.random(), sha256(byteArrayOf(1))) }
        assertFailsWith<IllegalStateException> { budget.tighten(16L * 1_024 * 1_024, 256) }
    }

    @Test fun siblingSubjectsCannotResetChargesOrLoosenLimits() {
        val budget = FinancialOriginalSourceBudget()
        budget.tighten(10, 2)
        budget.charge(6)
        budget.tighten(100, 20)
        budget.charge(4)
        assertFailsWith<IllegalStateException> { budget.charge(1) }
        assertFailsWith<IllegalStateException> { budget.charge(0) }
    }

    @Test fun stricterNestedFamilyIncludesAlreadyUsedBytes() {
        val budget = FinancialOriginalSourceBudget()
        budget.charge(11)
        assertFailsWith<IllegalStateException> { budget.tighten(10, 2) }
        assertFailsWith<IllegalStateException> { budget.tighten(100, 20) }
    }

    @Test fun exactIdentityReuseDoesNotAdmitAnotherOriginalOrChangedHash() {
        val budget = FinancialOriginalSourceBudget()
        val operation = OperationId.random()
        val hash = sha256(byteArrayOf(1))
        budget.tighten(100, 1)
        budget.original(operation, hash)
        budget.original(operation, hash)
        assertFailsWith<IllegalStateException> { budget.original(operation, sha256(byteArrayOf(2))) }
        assertFailsWith<IllegalStateException> { budget.original(operation, hash) }
        val distinct = FinancialOriginalSourceBudget()
        distinct.tighten(100, 1)
        distinct.original(operation, hash)
        assertFailsWith<IllegalStateException> { distinct.original(OperationId.random(), hash) }
        val tightened = FinancialOriginalSourceBudget()
        tightened.original(operation, hash)
        tightened.original(OperationId.random(), hash)
        assertFailsWith<IllegalStateException> { tightened.tighten(100, 1) }
    }

    @Test fun zeroByteCallsAreBoundedCumulatively() {
        val budget = FinancialOriginalSourceBudget()
        repeat(FoodReadLimits.REQUEST_CALLS) { budget.charge(0) }
        assertFailsWith<IllegalStateException> { budget.charge(0) }
        assertFailsWith<IllegalStateException> { budget.original(OperationId.random(), sha256(byteArrayOf(1))) }
    }
}
