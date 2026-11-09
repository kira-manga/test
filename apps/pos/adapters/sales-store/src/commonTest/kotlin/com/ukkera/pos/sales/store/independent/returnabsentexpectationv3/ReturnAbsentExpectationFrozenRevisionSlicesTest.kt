package com.ukkera.pos.sales.store.independent.returnabsentexpectationv3

import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseResultV1
import com.ukkera.pos.sales.store.returnCaseEntitlementRevisionGuard
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RAEX3-006 fixed scalar slices of the actual production guard. The pure observed 7
 * is not a genuine receipt/entitlement source. Passing the guard does not bind PS/Z,
 * authority, source admission, action/stage, original amounts, history or persistence.
 */
class ReturnAbsentExpectationFrozenRevisionSlicesTest {
    @Test fun raex3006_omittedAgainstPure7_revisionConflict() = assertConflict(null)
    @Test fun raex3006_stalePure6AgainstPure7_revisionConflict() = assertConflict(Revision(6))
    @Test fun raex3006_suppliedZeroAgainstPure7_revisionConflict() = assertConflict(Revision(0))

    @Test
    fun raex3006_correctPure7_scalarGuardOnlyDoesNotRefuse() {
        assertNull(returnCaseEntitlementRevisionGuard(expected = Revision(7), observed = Revision(7)))
    }

    private fun assertConflict(expected: Revision?) {
        val refusal = assertNotNull(returnCaseEntitlementRevisionGuard(expected, observed = Revision(7)))
        assertEquals(LocalReturnCaseResultV1.Refused("REVISION_CONFLICT"),
            LocalReturnCaseCommandCodecV1.decodeResult(refusal.result))
    }
}
