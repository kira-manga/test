package com.ukkera.pos.inventory.store.independent.unpricedarrivaldirectv2

import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen UAD2 transport/operation-proof slices, not protected command execution.
 * Current native sources/retention/ordered original history and Master commit checks
 * remain UNBOUND. Public-evaluator proofs use supplied synthetic status/grant facts;
 * neither those facts nor a normalized digest establishes a durable/current permission.
 * All whole cases/variants remain NOT_RUN, including UAD2-04 revocation/commit schedules.
 */
class UnpricedArrivalDirectV2ContractSlicesTest {
    private fun normalized(mutation: MutationRequest) = LocalUnpricedArrivalCommandsV2.normalize(mutation.command, mutation.payload)

    /** Independently materialized from the public action and actual typed normalized body,
     * never read back from the proof being checked. The target/operation/content stay exact. */
    private fun requirement(mutation: MutationRequest): AuthorizationRequirement {
        val body = normalized(mutation)
        val target: com.ukkera.pos.types.DomainId
        val revision: Revision
        val reason: String
        when (mutation.command) {
            LocalUnpricedArrivalProfileV2.RECORD -> {
                val request = LocalUnpricedArrivalCommandsV2.decodeRecordRequest(body)
                target = request.arrivalId; revision = Revision(0); reason = request.reasonCode
            }
            LocalUnpricedArrivalProfileV2.QUALIFY -> {
                val request = LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(body)
                target = request.arrivalId; revision = request.expectedArrivalRevision; reason = request.reasonCode
            }
            LocalUnpricedArrivalProfileV2.REJECT -> {
                val request = LocalUnpricedArrivalCommandsV2.decodeRejectRequest(body)
                target = request.arrivalId; revision = request.expectedArrivalRevision; reason = request.reasonCode
            }
            LocalUnpricedArrivalProfileV2.DISPATCH -> {
                val request = LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(body)
                target = request.arrivalId; revision = request.expectedArrivalRevision; reason = request.reasonCode
            }
            else -> error("No frozen UAD2 action binding")
        }
        return AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), Uad2Inputs.scope,
            ActionBinding(target, revision, semanticDigest(Uad2Inputs.scope, Uad2Inputs.id("REQUESTER-R"), mutation.command, body),
                mutation.operationId, reason), LimitUse.NotApplicable, OwnerConstraint.Unrestricted)
    }

    private fun recordMutation(request: LocalRecordUnpricedArrivalV2): MutationRequest =
        LocalUnpricedArrivalCommandsV2.record(Uad2Inputs.operation("OP-RECORD"), request)

    private fun contentVariations(): List<LocalRecordUnpricedArrivalV2> {
        val base = Uad2Inputs.record()
        val a = base.lines.single { it.skuId == Uad2Inputs.id("SKU-A") }
        fun withA(line: LocalArrivalLineV2): LocalRecordUnpricedArrivalV2 = base.copy(lines = base.lines.map { if (it.lineId == a.lineId) line else it })
        return listOf(
            withA(a.copy(baseQuantity = Uad2Inputs.quantity("A", "25"))),
            withA(a.copy(lotId = Uad2Inputs.id("LOT-L"))),
            base.copy(supplierId = Uad2Inputs.id("SUPPLIER-OTHER"), evidence = base.evidence.copy(documents =
                base.evidence.documents.map { it.copy(supplierId = Uad2Inputs.id("SUPPLIER-OTHER")) })),
            base.copy(evidence = base.evidence.copy(documents = base.evidence.documents.reversed())),
            base.copy(evidence = base.evidence.copy(documents = base.evidence.documents.map {
                if (it.documentId == Uad2Inputs.id("DOC-A")) it.copy(exactBytesDigest = PayloadDigest("0".repeat(64))) else it })),
            base.copy(evidence = base.evidence.copy(retention = LocalArrivalRetentionSelectionV2(Uad2Inputs.otherRetentionCarrier, Revision(3)))),
        )
    }

    @Test
    fun uad2_01_all_four_published_mutations_keep_exact_quantities_rules_manifests_and_prior_employee_revision() {
        val mutations = Uad2Inputs.mainMutations()
        val record = LocalUnpricedArrivalCommandsV2.decodeRecordRequest(normalized(mutations[0]))
        assertEquals(Uad2Inputs.quantity("A", "24"), record.lines.single { it.skuId == Uad2Inputs.id("SKU-A") }.baseQuantity)
        assertEquals(Uad2Inputs.quantity("B", "6"), record.lines.single { it.skuId == Uad2Inputs.id("SKU-B") }.baseQuantity)
        assertEquals(Uad2Inputs.id("RULE-RECORD"), record.evidence.ruleId)
        assertEquals(Uad2Inputs.requesterPolicyRevision, record.expectedRequesterPolicyRevision)
        assertEquals(Uad2Inputs.documents(), record.evidence.documents)
        val qualify = LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(normalized(mutations[1]))
        assertEquals(Uad2Inputs.quantity("A", "10"), qualify.quantity); assertTrue(qualify.inspectionPassed)
        assertEquals(Uad2Inputs.qualifySplit, qualify.split); assertEquals(Uad2Inputs.custodyA, qualify.custodyUnitId)
        assertEquals(Uad2Inputs.id("RULE-QUALIFY"), qualify.evidence.ruleId)
        assertEquals(Uad2Inputs.requesterPolicyRevision, qualify.expectedRequesterPolicyRevision)
        assertEquals(Uad2Inputs.documents(), qualify.evidence.documents)
        val reject = LocalUnpricedArrivalCommandsV2.decodeRejectRequest(normalized(mutations[2]))
        assertEquals(Uad2Inputs.quantity("A", "4"), reject.quantity); assertEquals(Uad2Inputs.rejectSplit, reject.split)
        assertEquals(Uad2Inputs.qualifySplit.custodyUnitId, reject.custodyUnitId)
        assertEquals(Uad2Inputs.id("RULE-REJECT"), reject.evidence.ruleId)
        assertEquals(Uad2Inputs.requesterPolicyRevision, reject.expectedRequesterPolicyRevision)
        assertEquals(Uad2Inputs.documents(), reject.evidence.documents)
        val dispatch = LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(normalized(mutations[3]))
        assertEquals(Uad2Inputs.quantity("A", "3"), dispatch.quantity); assertEquals(Uad2Inputs.dispatchSplit, dispatch.split)
        assertEquals(Uad2Inputs.rejectSplit.custodyUnitId, dispatch.custodyUnitId)
        assertEquals(Uad2Inputs.id("SUPPLIER-S"), dispatch.recipientSupplierId)
        assertEquals(Uad2Inputs.id("RULE-DISPATCH"), dispatch.evidence.ruleId)
        assertEquals(Uad2Inputs.requesterPolicyRevision, dispatch.expectedRequesterPolicyRevision)
        assertEquals(Uad2Inputs.documents(), dispatch.evidence.documents)
    }

    @Test
    fun uad2_01_05_normalization_is_stable_for_the_exact_original_input_of_each_action() {
        for (mutation in Uad2Inputs.mainMutations()) {
            val first = normalized(mutation)
            assertEquals(first, LocalUnpricedArrivalCommandsV2.normalize(mutation.command, first))
            assertEquals(first, normalized(mutation))
            assertNull(mutation.actionApprovalId)
        }
        // This does not execute an operation replay or compare a persisted original result.
    }

    @Test
    fun uad2_03_zero_source_coordinate_is_representable_without_substituting_generation_one() {
        val base = Uad2Inputs.record()
        val policy = base.evidence.policy.copy(activePointer = base.evidence.policy.activePointer.copy(storageRevision = Revision(0)),
            publication = base.evidence.policy.publication.copy(storageRevision = Revision(0)))
        val catalog = base.evidence.catalog.copy(activePointer = base.evidence.catalog.activePointer.copy(storageRevision = Revision(0)),
            publication = base.evidence.catalog.publication.copy(storageRevision = Revision(0)))
        val request = base.copy(expectedSupplierRevision = Revision(0), items = base.items.map { it.copy(storageRevision = Revision(0)) },
            warehouses = base.warehouses.map { it.copy(storageRevision = Revision(0)) }, evidence = base.evidence.copy(policy = policy, catalog = catalog))
        val decoded = LocalUnpricedArrivalCommandsV2.decodeRecordRequest(normalized(recordMutation(request)))
        assertEquals(Revision(0), decoded.expectedSupplierRevision)
        assertTrue(decoded.items.all { it.storageRevision == Revision(0) })
        assertTrue(decoded.warehouses.all { it.storageRevision == Revision(0) })
        for (source in listOf(decoded.evidence.policy.activePointer, decoded.evidence.policy.publication,
            decoded.evidence.catalog.activePointer, decoded.evidence.catalog.publication)) assertEquals(Revision(0), source.stamp().storageRevision)
        assertTrue(decoded.evidence.documents.all { it.storageRevision == Revision(1) })
        // Actual present zero-generation sources and all four protected accepted cuts are UNBOUND.
    }

    @Test
    fun uad2_03_ordered_originals_are_not_sorted_or_deduplicated_by_the_request_normalizer() {
        val base = Uad2Inputs.record()
        val reversed = base.copy(evidence = base.evidence.copy(documents = base.evidence.documents.reversed()))
        assertEquals(base.evidence.documents.reversed(), LocalUnpricedArrivalCommandsV2.decodeRecordRequest(normalized(recordMutation(reversed))).evidence.documents)
        assertNotEquals(normalized(recordMutation(base)), normalized(recordMutation(reversed)))
        assertFails { base.evidence.copy(documents = listOf(base.evidence.documents.first(), base.evidence.documents.first())) }
        // The actual rule/original-loader refusal of reversed or excessive selections is UNBOUND.
    }

    @Test
    fun uad2_03_public_encoding_snapshots_original_order_before_caller_lists_change() {
        val documents = Uad2Inputs.documents().toMutableList()
        val request = Uad2Inputs.record().copy(evidence = Uad2Inputs.evidence(documents = documents))
        val mutation = recordMutation(request)
        documents.reverse(); documents.clear()
        assertEquals(Uad2Inputs.documents(), LocalUnpricedArrivalCommandsV2.decodeRecordRequest(normalized(mutation)).evidence.documents)
        // Encoded transport ownership is not immutable action-use/retention storage.
    }

    @Test
    fun uad2_06_zero_negative_and_seven_decimal_record_quantities_are_not_constructed_by_rounding() {
        val line = Uad2Inputs.record().lines.first()
        for (scaled in listOf(0L, -1L)) assertFails { line.copy(baseQuantity = Quantity(line.baseQuantity.unitId, scaled, line.baseQuantity.scale)) }
        assertFails { Quantity.parse(Uad2Inputs.kilogram, "2.5000001", 3) }
    }

    @Test
    fun uad2_05_changed_quantity_supplier_tracking_original_order_manifest_or_retention_changes_content_identity() {
        val base = recordMutation(Uad2Inputs.record())
        val expected = requirement(base)
        for (request in contentVariations()) {
            val changed = recordMutation(request)
            assertEquals(base.operationId, changed.operationId)
            assertNotEquals(normalized(base), normalized(changed))
            assertNotEquals(expected, requirement(changed))
        }
        // Actual conflict/denial, conserved stored result and every nonphysical ledger are UNBOUND.
    }

    @Test
    fun uad2_05_original_requester_is_part_of_content_identity_not_a_body_claim_or_transport_alias() {
        val mutation = recordMutation(Uad2Inputs.record())
        assertNotEquals(semanticDigest(Uad2Inputs.scope, Uad2Inputs.id("REQUESTER-R"), mutation.command, normalized(mutation)),
            semanticDigest(Uad2Inputs.scope, Uad2Inputs.id("REQUESTER-X"), mutation.command, normalized(mutation)))
    }

    @Test
    fun uad2_01_03_each_direct_public_evaluator_proof_binds_its_independently_normalized_requirement() = runTest {
        val principal = Uad2PublicAuthorization.principal()
        for (mutation in Uad2Inputs.mainMutations()) {
            val expected = requirement(mutation)
            val proof = Uad2PublicAuthorization.proof(principal, expected)
            assertTrue(proof.isBoundTo(requirement(mutation), Uad2Inputs.id("REQUESTER-R")))
            assertEquals(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), expected.permission)
            assertEquals(Uad2Inputs.requesterPolicyRevision, proof.policyRevision)
            assertEquals(Uad2Inputs.id("SESSION-R"), proof.authenticationId)
            assertNull(proof.approvalId); assertNull(proof.approverId); assertNull(proof.approverPolicyRevision)
        }
        // Facts were supplied to the evaluator, not loaded from genuine current Master storage.
    }

    @Test
    fun uad2_03_successful_record_proof_does_not_bind_any_later_action() = runTest {
        val mutations = Uad2Inputs.mainMutations()
        val principal = Uad2PublicAuthorization.principal()
        val recordProof = Uad2PublicAuthorization.proof(principal, requirement(mutations.first()))
        for (later in mutations.drop(1)) assertFalse(recordProof.isBoundTo(requirement(later), Uad2Inputs.id("REQUESTER-R")))
    }

    @Test
    fun uad2_03_05_original_proof_does_not_bind_changed_content_or_another_requester() = runTest {
        val principal = Uad2PublicAuthorization.principal()
        val base = recordMutation(Uad2Inputs.record())
        val proof = Uad2PublicAuthorization.proof(principal, requirement(base))
        for (variation in contentVariations()) assertFalse(proof.isBoundTo(requirement(recordMutation(variation)), Uad2Inputs.id("REQUESTER-R")))
        assertFalse(proof.isBoundTo(requirement(base), Uad2Inputs.id("REQUESTER-X")))
    }

    @Test
    fun uad2_03_a_request_verb_public_proof_is_not_the_required_perform_proof() = runTest {
        val principal = Uad2PublicAuthorization.principal()
        val expected = requirement(Uad2Inputs.mainMutations().first())
        val requestOnly = expected.copy(permission = expected.permission.copy(verb = PermissionVerb.REQUEST))
        val proof = Uad2PublicAuthorization.proof(principal, requestOnly)
        assertFalse(proof.isBoundTo(expected, Uad2Inputs.id("REQUESTER-R")))
    }

    @Test
    fun uad2_04_each_action_public_evaluator_refuses_disabled_requester_facts() = runTest {
        val principal = Uad2PublicAuthorization.principal()
        for (mutation in Uad2Inputs.mainMutations()) assertIs<AuthorizationDecision.Denied>(
            Uad2PublicAuthorization.evaluate(principal, requirement(mutation), enabled = false))
    }

    @Test
    fun uad2_04_each_action_public_evaluator_refuses_missing_or_explicitly_denied_grant_facts() = runTest {
        val principal = Uad2PublicAuthorization.principal()
        for (mutation in Uad2Inputs.mainMutations()) {
            assertIs<AuthorizationDecision.Denied>(Uad2PublicAuthorization.evaluate(principal, requirement(mutation), permissionPresent = false))
            assertIs<AuthorizationDecision.Denied>(Uad2PublicAuthorization.evaluate(principal, requirement(mutation), explicitlyDenied = true))
        }
    }

    @Test
    fun uad2_04_each_action_public_evaluator_refuses_inactive_session_or_unadmitted_device_facts() = runTest {
        val principal = Uad2PublicAuthorization.principal()
        for (mutation in Uad2Inputs.mainMutations()) {
            assertIs<AuthorizationDecision.Denied>(Uad2PublicAuthorization.evaluate(principal, requirement(mutation), sessionActive = false))
            assertIs<AuthorizationDecision.Denied>(Uad2PublicAuthorization.evaluate(principal, requirement(mutation), deviceAdmitted = false))
        }
    }

    @Test
    fun uad2_06_additive_normalizer_cannot_reinterpret_a_v1_command_identity_as_v2() {
        val record = recordMutation(Uad2Inputs.record())
        assertEquals(2, record.command.version)
        assertNotEquals(LocalUnpricedArrivalProfileV1.RECORD, record.command)
        assertFails { LocalUnpricedArrivalCommandsV2.normalize(LocalUnpricedArrivalProfileV1.RECORD, record.payload) }
    }
}
