package com.ukkera.pos.database

import com.ukkera.pos.types.DomainId
import kotlin.test.*

/** Self-authored, NOT independent scheduling DATA controls. No native capability,
 * history proof, operation result or financial authority is constructed or qualified. */
class NativeOriginalCompletedSchedulingSupportingTest {
    private fun locator(n: Int) = NativeOriginalIntakeLocator(DomainId(
        "77000000-0000-4000-8000-" + n.toString().padStart(12, '0')))

    @Test fun heldAndUncertainOriginalsRemainEligibleForRealRecovery() {
        val state = NativeOriginalCompletedScheduling()
        val original = locator(1)
        state.record(original, NativeOriginalWorkerResult.HELD)
        assertFalse(state.contains(original))
        state.record(original, NativeOriginalWorkerResult.STORAGE_UNCERTAIN)
        assertFalse(state.contains(original))
    }

    @Test fun settledSchedulingIsExactAndNeverSurvivesAReplacementEndpoint() {
        val state = NativeOriginalCompletedScheduling()
        state.record(locator(1), NativeOriginalWorkerResult.SETTLED)
        assertTrue(state.contains(locator(1)))
        assertFalse(state.contains(locator(2)))
        assertFalse(NativeOriginalCompletedScheduling().contains(locator(1)))
    }

    @Test fun boundedEvictionPermitsRealRevalidationWithoutGrantingAnOutcome() {
        val state = NativeOriginalCompletedScheduling(2)
        for (id in 1..3) state.record(locator(id), NativeOriginalWorkerResult.SETTLED)
        assertFalse(state.contains(locator(1)))
        assertTrue(state.contains(locator(2)))
        assertTrue(state.contains(locator(3)))
        state.record(locator(1), NativeOriginalWorkerResult.HELD)
        assertFalse(state.contains(locator(1)))
    }
}
