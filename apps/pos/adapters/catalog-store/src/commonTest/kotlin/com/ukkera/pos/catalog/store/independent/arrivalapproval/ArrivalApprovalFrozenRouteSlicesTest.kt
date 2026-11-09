package com.ukkera.pos.catalog.store.independent.arrivalapproval

import com.ukkera.pos.catalog.store.purchasingArrivalUseRouteMatchesV2
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Frozen QAM-02 literal consistency only. Inert tuples do not provision public proofs.
 * NO_MISMATCH at this seam does not prove current grants, issuance, consumption or history.
 */
class ArrivalApprovalFrozenRouteSlicesTest {
    private fun agrees(mode: ArrivalApprovalMode, tuple: ArrivalApprovalTuple,
        requester: DomainId = ArrivalApprovalFrozenInputs.requester,
        namedAccount: DomainId = ArrivalApprovalFrozenInputs.requester): Boolean =
        purchasingArrivalUseRouteMatchesV2(ArrivalApprovalFrozenInputs.route(mode, namedAccount), requester,
            tuple.approvalId, tuple.approverId, tuple.approverPolicyRevision)

    @Test
    fun qam_02_direct_agrees_with_exact_null_provenance() {
        assertTrue(agrees(ArrivalApprovalMode.DIRECT, ArrivalApprovalFrozenInputs.direct))
    }

    @Test
    fun qam_02_direct_disagrees_with_any_present_approval_coordinate() {
        for (tuple in ArrivalApprovalFrozenInputs.nonemptyTuples()) {
            assertFalse(agrees(ArrivalApprovalMode.DIRECT, tuple), tuple.toString())
        }
    }

    @Test
    fun qam_02_separate_agrees_with_distinct_original_requester_actual_approver_and_complete_tuple() {
        assertTrue(agrees(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate))
    }

    @Test
    fun qam_02_separate_disagrees_when_any_original_approval_coordinate_is_missing() {
        for (tuple in ArrivalApprovalFrozenInputs.partialTuples()) {
            assertFalse(agrees(ArrivalApprovalMode.SEPARATE, tuple), tuple.toString())
        }
    }

    @Test
    fun qam_02_separate_disagrees_when_original_requester_equals_actual_approver() {
        assertFalse(agrees(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.namedSelf))
    }

    @Test
    fun qam_02_named_self_agrees_with_explicitly_named_requester_and_nonnull_self_approver() {
        assertTrue(agrees(ArrivalApprovalMode.NAMED_SELF, ArrivalApprovalFrozenInputs.namedSelf))
    }

    @Test
    fun qam_02_named_self_still_agrees_with_distinct_original_requester_and_actual_approver() {
        assertTrue(agrees(ArrivalApprovalMode.NAMED_SELF, ArrivalApprovalFrozenInputs.separate))
    }

    @Test
    fun qam_02_named_self_disagrees_when_the_equality_exception_names_another_account() {
        assertFalse(agrees(ArrivalApprovalMode.NAMED_SELF, ArrivalApprovalFrozenInputs.namedSelf,
            namedAccount = ArrivalApprovalFrozenInputs.approver))
    }

    @Test
    fun qam_02_named_self_disagrees_with_every_incomplete_original_approval_tuple() {
        for (tuple in ArrivalApprovalFrozenInputs.partialTuples(ArrivalApprovalFrozenInputs.namedSelf)) {
            assertFalse(agrees(ArrivalApprovalMode.NAMED_SELF, tuple), tuple.toString())
        }
    }

    @Test
    fun qam_05_identical_literal_route_calls_repeat_without_changing_the_supplied_tuple() {
        val tuple = ArrivalApprovalFrozenInputs.separate
        val expected = ArrivalApprovalTuple(ArrivalApprovalFrozenInputs.separateApprovalId,
            ArrivalApprovalFrozenInputs.approver, ArrivalApprovalFrozenInputs.approverPolicyRevision)
        val first = agrees(ArrivalApprovalMode.SEPARATE, tuple)
        repeat(4) {
            assertEquals(first, agrees(ArrivalApprovalMode.SEPARATE, tuple))
            assertEquals(expected, tuple)
        }
        assertTrue(first)
        // No effectful adapter is installed; repeatability is not a transaction/absence proof.
    }
}
