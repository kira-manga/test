package com.ukkera.pos.catalog.store.independent.arrivalapproval

import com.ukkera.pos.catalog.store.LocalPurchasingArrivalDocumentUseProfileV2
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.inventory.store.LocalArrivalRetentionSelectionV2
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen QAM-02–05 / APV-01–03 / UAD2 preservation slices of prospective bytes only.
 * No AuthorizedAction, actual approval, native original, consuming transaction or retained
 * history is fabricated. Refusal here is a content inconsistency, not a current-authority
 * verdict. Complete cases and all native/atomic/history/RPC/restart lanes remain NOT_RUN.
 */
class ArrivalApprovalFrozenDocumentarySlicesTest {
    @Test
    fun qam_02_direct_projected_producer_keeps_null_approval_and_original_requester() {
        val effects = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.DIRECT)
        val record = ArrivalApprovalFrozenInputs.record(effects)
        assertEquals(ArrivalApprovalFrozenInputs.requester, record.producer.actorId)
        assertEquals(ArrivalApprovalFrozenInputs.requesterPolicyRevision, record.requesterPolicyRevision)
        assertNull(record.producer.approvalId)
        assertNull(record.producer.approverId)
        assertNull(record.producer.approverPolicyRevision)
        assertEquals(ArrivalApprovalFrozenInputs.operationId, record.producer.operationId)
        assertEquals(ArrivalApprovalFrozenInputs.authenticationIdCarrier, record.producer.authenticationId)
        assertEquals(ArrivalApprovalFrozenInputs.generationCarrier, record.producer.generation)
    }

    @Test
    fun qam_02_direct_default_and_explicit_null_projection_arguments_have_equivalent_bytes() {
        val defaults = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.DIRECT, omitDirectTupleArguments = true)
        val explicitNulls = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.DIRECT, ArrivalApprovalFrozenInputs.direct)
        val defaultBytes = defaults.changes.single().payload.copyBytes()
        val explicitBytes = explicitNulls.changes.single().payload.copyBytes()
        try { assertContentEquals(defaultBytes, explicitBytes) }
        finally { defaultBytes.fill(0); explicitBytes.fill(0) }
        assertEquals(defaults.evidence, explicitNulls.evidence)
        assertEquals(defaults.claims, explicitNulls.claims)
        assertEquals(ArrivalApprovalFrozenInputs.record(defaults), ArrivalApprovalFrozenInputs.record(explicitNulls))
        // Equivalence only: neither projection is a historical frozen golden byte oracle.
    }

    @Test
    fun qam_02_separate_projected_producer_preserves_every_original_approval_coordinate() {
        val effects = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate)
        val record = ArrivalApprovalFrozenInputs.record(effects)
        assertEquals(ArrivalApprovalFrozenInputs.requester, record.producer.actorId)
        assertEquals(ArrivalApprovalFrozenInputs.separateApprovalId, record.producer.approvalId)
        assertEquals(ArrivalApprovalFrozenInputs.approver, record.producer.approverId)
        assertEquals(Revision(12), record.producer.approverPolicyRevision)
        assertEquals(Revision(11), record.requesterPolicyRevision)
        assertNotEquals(record.producer.actorId, record.producer.approverId)
        assertNotEquals(record.requesterPolicyRevision, record.producer.approverPolicyRevision)
        assertEquals(ArrivalApprovalFrozenInputs.operationId, record.producer.operationId)
    }

    @Test
    fun qam_02_named_self_projected_producer_preserves_nonnull_exact_self_approval() {
        val effects = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.NAMED_SELF, ArrivalApprovalFrozenInputs.namedSelf)
        val record = ArrivalApprovalFrozenInputs.record(effects)
        assertEquals(ArrivalApprovalFrozenInputs.requester, record.producer.actorId)
        assertEquals(ArrivalApprovalFrozenInputs.selfApprovalId, record.producer.approvalId)
        assertEquals(ArrivalApprovalFrozenInputs.requester, record.producer.approverId)
        assertEquals(Revision(11), record.producer.approverPolicyRevision)
        assertEquals(Revision(11), record.requesterPolicyRevision)
    }

    @Test
    fun qam_02_named_self_projected_producer_also_preserves_distinct_original_actors() {
        val effects = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.NAMED_SELF, ArrivalApprovalFrozenInputs.separate)
        val record = ArrivalApprovalFrozenInputs.record(effects)
        assertEquals(ArrivalApprovalFrozenInputs.requester, record.producer.actorId)
        assertEquals(ArrivalApprovalFrozenInputs.separateApprovalId, record.producer.approvalId)
        assertEquals(ArrivalApprovalFrozenInputs.approver, record.producer.approverId)
        assertEquals(Revision(12), record.producer.approverPolicyRevision)
        assertNotEquals(record.producer.actorId, record.producer.approverId)
    }

    @Test
    fun qam_02_a_later_self_projection_does_not_rewrite_the_first_separate_producer() {
        val first = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate)
        val firstBytes = first.changes.single().payload.copyBytes()
        try {
            val later = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.NAMED_SELF, ArrivalApprovalFrozenInputs.namedSelf,
                operation = ArrivalApprovalFrozenInputs.otherOperationId)
            assertEquals(ArrivalApprovalFrozenInputs.otherOperationId, ArrivalApprovalFrozenInputs.record(later).producer.operationId)
            val original = ArrivalApprovalFrozenInputs.record(first)
            assertEquals(ArrivalApprovalFrozenInputs.operationId, original.producer.operationId)
            assertEquals(ArrivalApprovalFrozenInputs.requester, original.producer.actorId)
            assertEquals(ArrivalApprovalFrozenInputs.separateApprovalId, original.producer.approvalId)
            assertEquals(ArrivalApprovalFrozenInputs.approver, original.producer.approverId)
            assertEquals(Revision(12), original.producer.approverPolicyRevision)
            val retainedBytes = first.changes.single().payload.copyBytes()
            try { assertContentEquals(firstBytes, retainedBytes) }
            finally { retainedBytes.fill(0) }
        } finally { firstBytes.fill(0) }
        // Two DATA projections do not execute APV-02's issuance/consumption transactions.
    }

    @Test
    fun qam_02_approval_backed_projection_refuses_the_null_direct_tuple() {
        for (mode in listOf(ArrivalApprovalMode.SEPARATE, ArrivalApprovalMode.NAMED_SELF)) {
            assertFails(mode.name) { ArrivalApprovalFrozenInputs.project(mode, ArrivalApprovalFrozenInputs.direct) }
        }
    }

    @Test
    fun qam_02_direct_projection_refuses_any_present_approval_coordinate() {
        for (tuple in ArrivalApprovalFrozenInputs.nonemptyTuples()) {
            assertFails(tuple.toString()) { ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.DIRECT, tuple) }
        }
    }

    @Test
    fun qam_02_separate_projection_refuses_incomplete_tuple_and_same_actor() {
        for (tuple in ArrivalApprovalFrozenInputs.partialTuples() + ArrivalApprovalFrozenInputs.namedSelf) {
            assertFails(tuple.toString()) { ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, tuple) }
        }
    }

    @Test
    fun qam_02_named_self_projection_refuses_an_equality_exception_for_another_account() {
        assertFails {
            ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.NAMED_SELF, ArrivalApprovalFrozenInputs.namedSelf,
                namedAccount = ArrivalApprovalFrozenInputs.approver)
        }
    }

    @Test
    fun qam_03_ordered_originals_and_sources_remain_owned_after_caller_collection_changes() {
        val selectedDocuments = ArrivalApprovalFrozenInputs.documents().toMutableList()
        val originals = ArrivalApprovalFrozenInputs.documents().toMutableList()
        val products = ArrivalApprovalFrozenInputs.products().toMutableList()
        val selection = ArrivalApprovalFrozenInputs.selection(ArrivalApprovalMode.SEPARATE, selectedDocuments)
        val sources = ArrivalApprovalFrozenInputs.sources(selection).toMutableList()
        val expectedSources = sources.map { it.key to (it.storageRevision to it.digest) }
        val effects = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate,
            selected = selection, selectedProducts = products, originals = originals, originalSources = sources)
        selectedDocuments.reverse(); originals.clear(); products.clear(); sources.clear()
        val record = ArrivalApprovalFrozenInputs.record(effects)
        assertEquals(ArrivalApprovalFrozenInputs.documents(), record.originalDocuments)
        assertEquals(listOf(ArrivalApprovalFrozenInputs.jpegId, ArrivalApprovalFrozenInputs.pdfId),
            record.originalDocuments.map { it.documentId })
        assertEquals(ArrivalApprovalFrozenInputs.documents(), record.selection.documents)
        assertEquals(ArrivalApprovalFrozenInputs.products(), record.products)
        assertEquals(expectedSources, record.sources.map { it.stamp().key to (it.storageRevision to it.digest) })
        assertEquals(ArrivalApprovalFrozenInputs.documents(), effects.evidence.originalDocuments)
        assertEquals(ArrivalApprovalFrozenInputs.documents(), effects.evidence.selection.documents)
        assertEquals(ArrivalApprovalFrozenInputs.products(), effects.evidence.products)
        assertEquals(expectedSources, effects.evidence.sources.map { it.stamp().key to (it.storageRevision to it.digest) })
    }

    @Test
    fun qam_03_wrong_resolved_original_order_is_refused_and_preserves_the_earlier_projection() {
        val first = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate)
        val originalBytes = first.changes.single().payload.copyBytes()
        try {
            assertFails {
                ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate,
                    originals = ArrivalApprovalFrozenInputs.documents().reversed())
            }
            assertEquals(ArrivalApprovalFrozenInputs.documents(), ArrivalApprovalFrozenInputs.record(first).originalDocuments)
            val unchanged = first.changes.single().payload.copyBytes()
            try { assertContentEquals(originalBytes, unchanged) }
            finally { unchanged.fill(0) }
        } finally { originalBytes.fill(0) }
        // No captured native original, command, business effect or consumption is involved.
    }

    @Test
    fun qam_03_optional_empty_originals_keep_explicit_retention_and_null_direct_producer() {
        val selection = ArrivalApprovalFrozenInputs.selection(ArrivalApprovalMode.DIRECT, documents = emptyList())
        val effects = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.DIRECT, selected = selection, originals = emptyList())
        val record = ArrivalApprovalFrozenInputs.record(effects)
        assertTrue(record.originalDocuments.isEmpty())
        assertTrue(record.selection.documents.isEmpty())
        assertTrue(effects.evidence.originalDocuments.isEmpty())
        assertTrue(effects.evidence.selection.documents.isEmpty())
        assertEquals(ArrivalApprovalFrozenInputs.retentionClassId, record.retention.classId)
        assertEquals(Revision(2), record.retention.revision)
        assertEquals(selection.retention.classId, record.retention.classId)
        assertEquals(selection.retention.revision, record.retention.revision)
        assertNull(record.producer.approvalId)
        assertNull(record.producer.approverId)
        assertNull(record.producer.approverPolicyRevision)
        assertEquals(1, effects.changes.size)
        assertEquals(LocalPurchasingArrivalDocumentUseProfileV2.KIND, effects.changes.single().key.kind)
        val claim = assertIs<ClaimChange.Acquire>(effects.claims.single())
        assertEquals(record.id, claim.ownerId)
        assertEquals(ArrivalApprovalFrozenInputs.operationId.value, claim.key.key)
        // One prospective use/claim is not an atomic committed zero-file action outcome.
    }

    @Test
    fun qam_04_wrong_selected_retention_coordinates_refuse_without_rewriting_an_earlier_tuple() {
        val first = ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate)
        val originalBytes = first.changes.single().payload.copyBytes()
        try {
            val variations = listOf(
                LocalArrivalRetentionSelectionV2(ArrivalApprovalFrozenInputs.differentRetentionClassId, Revision(2)),
                LocalArrivalRetentionSelectionV2(ArrivalApprovalFrozenInputs.retentionClassId, Revision(1)),
            )
            for (retention in variations) {
                val selection = ArrivalApprovalFrozenInputs.selection(ArrivalApprovalMode.SEPARATE, retention = retention)
                assertFails {
                    ArrivalApprovalFrozenInputs.project(ArrivalApprovalMode.SEPARATE, ArrivalApprovalFrozenInputs.separate, selected = selection)
                }
            }
            val original = ArrivalApprovalFrozenInputs.record(first)
            assertEquals(ArrivalApprovalFrozenInputs.separateApprovalId, original.producer.approvalId)
            assertEquals(ArrivalApprovalFrozenInputs.approver, original.producer.approverId)
            assertEquals(Revision(12), original.producer.approverPolicyRevision)
            assertEquals(ArrivalApprovalFrozenInputs.retentionClassId, original.retention.classId)
            assertEquals(Revision(2), original.retention.revision)
            val unchanged = first.changes.single().payload.copyBytes()
            try { assertContentEquals(originalBytes, unchanged) }
            finally { unchanged.fill(0) }
        } finally { originalBytes.fill(0) }
    }
}
