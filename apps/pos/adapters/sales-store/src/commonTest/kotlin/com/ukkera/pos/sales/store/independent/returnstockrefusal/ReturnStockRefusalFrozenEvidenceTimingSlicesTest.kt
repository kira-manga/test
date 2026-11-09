package com.ukkera.pos.sales.store.independent.returnstockrefusal

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.sales.ElapsedReturnWindow
import com.ukkera.pos.sales.store.LocalReturnStockProfileV1
import com.ukkera.pos.sales.store.LocalSalesSourceUnavailable
import com.ukkera.pos.sales.store.readReturnStockAcceptance
import com.ukkera.pos.sales.store.readReturnStockState
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.ukkera.pos.sales.store.independent.returnstockrefusal.FrozenReturnStockRefusalInputs as F

/**
 * Bounded failure/numeric slices of RSDF-006(a) and RSDF-007, not native evidence.
 * Missing rows and the dangling locator are controlled failure premises, never
 * genuine current absence or source reconstruction. These real production readers
 * cannot turn that incomplete pair into a qualified original or known zero frontier.
 * No accepted source, positive port, fabricated StoredAggregate, grant or history
 * evidence is constructed for either read.
 *
 * Timing uses only the freeze's exact original receipt/submission/period tuple.
 * Applicability, Master capture, whole receipt history, genuine acceptance after
 * expiry, later stock processing and accepted-obligation persistence stay UNBOUND.
 * All common inventory/money/history/no-business-effect assertions need fresh native
 * authorized readers; this harness supplies no known C1/C2 ledger projection.
 */
class ReturnStockRefusalFrozenEvidenceTimingSlicesTest {
    @Test
    fun rsdf006a_missingAcceptedOriginalRemainsUnavailableInProductionReader() = runTest {
        val view = MissingSourceView()
        assertFailsWith<LocalSalesSourceUnavailable> {
            readReturnStockAcceptance(view, F.id(20), F.id(21), F.id(22))
        }
    }

    @Test
    fun rsdf006a_missingDispositionRowWithDanglingLocatorCannotBecomeZeroFrontier() = runTest {
        val returnId = F.id(22)
        val view = MissingSourceView(danglingStateClaim = returnId)
        assertFailsWith<LocalSalesSourceUnavailable> {
            readReturnStockState(view, returnId)
        }
    }

    @Test
    fun rsdf007_exact336ElapsedHoursSubmissionAtInclusiveExpiry_numericOnly() {
        val timing = F.timing
        val receivedAt = F.instant(timing.literal("authoritative_customer_receipt_utc"))
        val submittedAt = F.instant(timing.literal("immutable_original_master_submission_utc"))
        val expectedExpiry = F.instant(timing.literal("inclusive_expiry_utc"))
        val capturedDays = timing.literal("captured_applicable_period_days").toInt()
        val currentDays = timing.literal("current_matching_applicable_ordinary_period_days").toInt()
        val capturedWindow = ElapsedReturnWindow(capturedDays)
        val currentWindow = ElapsedReturnWindow(currentDays)
        val actualExpiry = assertNotNull(capturedWindow.expiresAt(receivedAt))
        assertEquals(expectedExpiry, actualExpiry)
        assertEquals(expectedExpiry, currentWindow.expiresAt(receivedAt))
        assertEquals(expectedExpiry, submittedAt)
        assertEquals(true, capturedWindow.containsSubmission(receivedAt, submittedAt))
        assertEquals(true, currentWindow.containsSubmission(receivedAt, submittedAt))

        val elapsedSeconds = actualExpiry.value.epochSeconds - receivedAt.value.epochSeconds
        assertEquals(timing.literal("elapsed_hours").toLong(), elapsedSeconds / 3_600L)
        assertEquals(0L, elapsedSeconds % 3_600L)
        assertEquals(receivedAt.value.nanosecondsOfSecond, actualExpiry.value.nanosecondsOfSecond)
        assertTrue(F.instant(timing.literal("later_processing_example_utc")) > expectedExpiry)
        // The later clock is not substituted for the immutable submittedAt input.
        // This pure observation grants no eligibility or authentic accepted origin.
    }

    private class MissingSourceView(private val danglingStateClaim: DomainId? = null) : CommandReadView {
        override val scope: BranchScope = F.scope
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? = null
        override suspend fun claim(key: ClaimKey): DomainId? = danglingStateClaim?.takeIf {
            key == LocalReturnStockProfileV1.stateClaim(it)
        }
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
            error("No retained original is provided by this controlled missing-source premise")
    }
}
