package com.ukkera.pos.app.gateway.independent.localpricingoriginalv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Frozen LP unit slices only. No native signature, admitted master or successful custody write is fabricated. */
class LocalPricingOriginalFrozenGatewaySlicesTest {
    private val inputs = LocalPricingOriginalFrozenBindings

    @Test
    fun lp01And02UnsignedNativeProposalCannotBypassTheSeparateReceiptGateway() = runTest {
        val definition = inputs.unsignedPolicy()
        val canonical = LocalPricingCodec.canonicalPolicy(definition)
        val ports = LocalPricingOriginalUnitPorts()
        try {
            val proposal = definition.toNativeCompanyPricePolicyProposal()
            assertEquals(CompanyBusinessPublicationKind.PRICE_POLICY_V1, proposal.kind)
            assertEquals(inputs.scope.company(), proposal.company)
            assertEquals(definition.publicationId, proposal.publicationId)
            assertEquals(definition.entryId, proposal.entityId)
            val exported = proposal.copyCanonicalContent()
            try { assertContentEquals(canonical, exported) } finally { exported.fill(0) }
            val fresh = proposal.copyCanonicalContent()
            try { assertContentEquals(canonical, fresh) } finally { fresh.fill(0) }

            // Unsigned exact content is not a completed native action, receipt or activation.
            for (values in listOf(emptyList(), listOf(LocalScalarInput(LocalField.NOTES,
                LocalScalarValue.Text(canonical.decodeToString()))))) {
                val intent = LocalCommandIntent(inputs.operation("OP-RECEIVE-R6"), LocalAction.PRICE_POLICY_RECEIVE,
                    1, LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, inputs.publicationId), values)
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                    assertIs<LocalStageResult.NotStaged>(ports.gateway.stage(intent)).problem)
            }
            assertFalse(UnavailableLocalCompanyCatalog.pricePolicies.configured)
            assertIs<LocalStageResult.NotStaged>(UnavailableLocalCompanyCatalog.pricePolicies.stageReceipt(inputs.id("unbound-native-original")))
            assertEquals(0, ports.journal.writes)
            assertTrue(ports.master.reads.isEmpty())
            assertTrue(ports.master.lookups.isEmpty())
            assertTrue(ports.master.mutations.isEmpty())
        } finally { canonical.fill(0); ports.close() }
    }

    @Test
    fun lp02And13PricingActionProfileAndNullApprovalPairingsAreExact() {
        val requests = listOf(
            LocalAction.PRICE_POLICY_ACTIVATE to LocalPricingRequestV1.ActivatePolicy(inputs.publicationId, inputs.capturedStorageCas),
            LocalAction.PRICE_HISTORY_INDEX to LocalPricingRequestV1.IndexRetainedCompanyHistory(inputs.capturedStorageCas),
            LocalAction.PRICE_SET to inputs.branchRequest(reset = false),
            LocalAction.PRICE_RESET to inputs.branchRequest(reset = true))
        val actions = requests.map { it.first } + LocalAction.PRICE_POLICY_RECEIVE + LocalAction.CATALOG_ACTIVATE
        for ((action, body) in requests) {
            val request = MutationRequest(inputs.operation("pairing-${action.name}"), LocalPricingProfile.COMMAND, LocalPricingCodec.encode(body))
            assertEquals(action, pricingRequestAction(body))
            assertNull(request.actionApprovalId)
            for (candidate in actions) assertEquals(candidate == action, actionMatches(candidate, request), "$candidate / $action")
            assertFalse(actionMatches(action, MutationRequest(request.operationId, request.command, request.payload, inputs.id("unrelated-approval"))))
            assertFalse(actionMatches(action, MutationRequest(request.operationId,
                CommandKey(request.command.name, request.command.version + 1), request.payload)))
            assertFalse(actionMatches(action, MutationRequest(request.operationId,
                CommandKey("local.catalog.unsupported", request.command.version), request.payload)))
        }
        // No syntactic signature or positive receipt pairing substitutes for B-RELEASE.
    }

    @Test
    fun lp01And09And13ActivationAndIndexKeepTheCapturedCasAfterCurrentStateChanges() = runTest {
        for (cas in listOf(null, inputs.capturedStorageCas)) {
            for (action in listOf(LocalAction.PRICE_POLICY_ACTIVATE, LocalAction.PRICE_HISTORY_INDEX)) {
                val intent = if (action == LocalAction.PRICE_POLICY_ACTIVATE) inputs.activationIntent(cas) else inputs.indexIntent(cas)
                val expected = if (action == LocalAction.PRICE_POLICY_ACTIVATE)
                    LocalPricingRequestV1.ActivatePolicy(inputs.publicationId, cas)
                else LocalPricingRequestV1.IndexRetainedCompanyHistory(cas)
                val mapping = LocalPricingOriginalUnitPorts(initialCas = cas)
                val original = try {
                    val request = preparePricing(mapping.access(), intent)
                    assertEquals(expected, LocalPricingCodec.decode(request.payload))
                    assertEquals(intent.operationId, request.operationId)
                    assertEquals(LocalPricingProfile.COMMAND, request.command)
                    assertNull(request.actionApprovalId)
                    assertEquals(0, mapping.journal.writes)
                    assertTrue(mapping.master.mutations.isEmpty())
                    inputs.entry(intent, request)
                } finally { mapping.close() }

                val recovery = LocalPricingOriginalUnitPorts(original, inputs.laterStorageCas)
                recovery.master.companyRevision = inputs.laterCompanyRevision
                try {
                    val staged = assertIs<LocalStageResult.Staged>(recovery.gateway.stage(intent))
                    assertEquals(intent.operationId, staged.operation.operationId)
                    assertEquals(action, staged.operation.action)
                    assertEquals(original.identity(), recovery.master.lookups.single())
                    assertIs<LocalOperationResult.Pending>(recovery.gateway.lookup(intent.operationId))
                    val retained = assertNotNull(recovery.journal.entry)
                    assertTrue(original.sameIntent(retained))
                    assertEquals(original.payloadHex, retained.payloadHex)
                    assertEquals(original.semanticSha256, retained.semanticSha256)
                    assertEquals(expected, LocalPricingCodec.decode(retained.request().payload))
                    assertEquals(inputs.laterStorageCas, recovery.master.storageCas)
                    assertEquals(inputs.laterCompanyRevision, recovery.master.companyRevision)
                    assertEquals(0, recovery.journal.writes)
                    assertTrue(recovery.master.mutations.isEmpty())
                } finally { recovery.close() }
            }
        }
    }

    @Test
    fun lp13ChangedCasTargetActionOrOriginatorCannotReplaceTheSavedOriginal() = runTest {
        val intent = inputs.activationIntent(inputs.capturedStorageCas)
        val original = inputs.entry(intent, activationRequest(intent, inputs.capturedStorageCas))
        val ports = LocalPricingOriginalUnitPorts(original, inputs.laterStorageCas)
        try {
            val changes = listOf(inputs.activationIntent(inputs.laterStorageCas),
                inputs.activationIntent(inputs.capturedStorageCas, inputs.id("different-publication")),
                LocalCommandIntent(intent.operationId, LocalAction.PRICE_POLICY_RECEIVE, intent.formVersion, intent.reference, intent.values))
            for (changed in changes) assertEquals(intent.operationId,
                assertIs<LocalStageResult.Uncertain>(ports.gateway.stage(changed)).operationId)
            assertTrue(ports.master.lookups.isEmpty())
            assertTrue(ports.master.reads.isEmpty())
            ports.replaceActor(inputs.id("different-originating-actor"))
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalOperationResult.Unresolved>(ports.gateway.lookup(intent.operationId)).problem)
            assertEquals(original, ports.journal.entry)
            assertEquals(inputs.owner.actorId, assertNotNull(ports.journal.entry).owner.actorId)
            assertEquals(0, ports.journal.writes)
            assertTrue(ports.master.mutations.isEmpty())
        } finally { ports.close() }
    }

    @Test
    fun lp02And13MismatchedSavedActionProfileOrApprovalCannotValidateOrContinue() = runTest {
        val intent = inputs.activationIntent(inputs.capturedStorageCas)
        val original = inputs.entry(intent, activationRequest(intent, inputs.capturedStorageCas))
        val ports = LocalPricingOriginalUnitPorts(original)
        try {
            val invalid = listOf(original.copy(action = LocalAction.PRICE_POLICY_RECEIVE.name),
                original.copy(action = LocalAction.PRICE_HISTORY_INDEX.name),
                original.copy(commandVersion = original.commandVersion + 1),
                original.copy(actionApprovalId = inputs.id("unrelated-approval")))
            val access = ports.access()
            for (entry in invalid) {
                assertFailsWith<IllegalArgumentException> { entry.validate(inputs.owner) }
                assertFalse(pricingContinuationHint(access, entry))
                assertFalse(access.canContinue(entry))
            }
            assertEquals(original, ports.journal.entry)
            assertEquals(0, ports.journal.writes)
            assertTrue(ports.master.reads.isEmpty())
            assertTrue(ports.master.mutations.isEmpty())
        } finally { ports.close() }
    }

    @Test
    fun lp13And14RecordedRefusalRepresentationSurvivesAndMissingMasterProofNeverBecomesSuccess() = runTest {
        val intent = inputs.activationIntent(inputs.capturedStorageCas)
        val original = inputs.entry(intent, activationRequest(intent, inputs.capturedStorageCas))
        // Public stale-policy code binding only; this does not manufacture an authoritative refusal.
        val code = "catalog.stale_price_policy"
        val payload = inputs.resultBytes(LocalPricingResultV1(code)).copyBytes()
        val refusal = try { StoredKernelResultV1(intent.operationId, inputs.owner.actorId, false, code,
            encodeHex(payload), Revision(1), JournalSequence(1), inputs.observedAt) } finally { payload.fill(0) }
        val recorded = original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = refusal)
        for (state in listOf(recorded, recorded.copy(acknowledged = true))) {
            val restored = inputs.roundTrip(state)
            assertTrue(original.sameIntent(restored))
            assertEquals(original.payloadHex, restored.payloadHex)
            assertEquals(original.semanticSha256, restored.semanticSha256)
            assertEquals(refusal, restored.recorded)
            assertEquals(state.acknowledged, restored.acknowledged)
        }
        val ports = LocalPricingOriginalUnitPorts(recorded, inputs.laterStorageCas)
        ports.master.companyRevision = inputs.laterCompanyRevision
        try {
            assertIs<LocalOperationResult.Unresolved>(ports.gateway.submit(intent.operationId))
            assertEquals(recorded, ports.journal.entry)
            assertEquals(0, ports.journal.writes)
            assertTrue(ports.master.mutations.isEmpty())
        } finally { ports.close() }
        // Actual LP-14 limit refusal, durable replay and a fresh successful request remain UNBOUND.
    }

    private fun activationRequest(intent: LocalCommandIntent, cas: Revision?): MutationRequest =
        MutationRequest(intent.operationId, LocalPricingProfile.COMMAND,
            LocalPricingCodec.encode(LocalPricingRequestV1.ActivatePolicy(inputs.publicationId, cas)))
}
