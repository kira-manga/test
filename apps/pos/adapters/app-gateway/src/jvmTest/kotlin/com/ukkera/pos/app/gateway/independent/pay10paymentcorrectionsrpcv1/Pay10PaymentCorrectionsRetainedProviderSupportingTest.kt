package com.ukkera.pos.app.gateway.independent.pay10paymentcorrectionsrpcv1

import com.ukkera.pos.app.gateway.independent.pay10classificationcorrection.Pay10GatewayInputs
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterRpcRetainedResultAccessProvider
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.StreamedSourceObservationV2
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.HistoricalSalesTenderPolicySourceV1
import com.ukkera.pos.sales.store.LocalPaymentCorrectionCodec
import com.ukkera.pos.sales.store.LocalPaymentCorrectionHandler
import com.ukkera.pos.sales.store.LocalPaymentCorrectionQueryV1
import com.ukkera.pos.sales.store.LocalSalesTenderSnapshot
import com.ukkera.pos.sales.store.TransactionSalesTenderSource
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PC-RPC-R01–R07 / COM-PAY-10-I/E retained-provider SUPPORT only. The actual
 * typed provider is invoked with controlled original-context DATA, not a native
 * grant or authentic retained record. It is not the caller/original-lookup gate.
 * Actor/body/scope mutations keep the original digest; a legitimately recomputed
 * changed-actor digest is not a provider-alone denial oracle. No writer, source
 * view, current policy, transport, durable result or restart is supplied.
 */
class Pay10PaymentCorrectionsRetainedProviderSupportingTest {
    @Test
    fun pcRpcR01SupportOriginalRequestDerivesOneExactPositiveCorrectionRequirementWithoutSources() {
        val fixture = RetainedProviderFixture()
        assertTrue(fixture.handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS,
            fixture.normalized))
        val requirements = fixture.provider.rpcRetainedResultRequirements(
            LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS, fixture.normalized, fixture.originalContext)
        val input = Pay10GatewayInputs
        val expected = AuthorizationRequirement(
            Permission(SecurityAction.PAYMENT_CLASSIFICATION_CORRECT, PermissionVerb.PERFORM), input.scope,
            ActionBinding(input.sale, Revision(4), fixture.originalDigest, input.operation, "payment.classification.correct"),
            LimitUse.Monetary(Money(Currency.EGP, 10000)), OwnerConstraint.Unrestricted)
        assertEquals(listOf(expected), requirements)
        assertEquals("synthetic receipt/count investigation", input.request.reason)
        assertNotEquals(input.request.reason, expected.binding?.reasonCode)
        fixture.assertOriginalUnchangedAndNoSourceAccess()
        // Producing this requirement is not current authorization or authentic
        // original/caller matching. No prepare/evaluate or source read is invoked.
    }

    @Test
    fun pcRpcR02SupportUnrelatedProfilesCannotDeriveCorrectionRequirement() {
        val fixture = RetainedProviderFixture()
        for (profile in LocalMasterRpcWorkflowProfile.entries.filter {
            it != LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS
        }) {
            assertFalse(fixture.handler.supportsRpcWorkflow(profile, fixture.normalized))
            fixture.assertNoEligibleRequirement(profile = profile)
        }
    }

    @Test
    fun pcRpcR03SupportOtherKeysAndCorrectionVersionCannotDeriveRequirement() {
        val fixture = RetainedProviderFixture()
        val keys = listOf(CommandKey("sales.payment-correction.write", 2),
            CommandKey("sales.order.write", 1), CommandKey("sales.cash.control", 1),
            CommandKey("sales.payment-correction.read", 1))
        for (key in keys) fixture.assertNoEligibleRequirement(context = fixture.context(key = key))
    }

    @Test
    fun pcRpcR04SupportMalformedExtraFieldAndNoncanonicalBytesCannotDeriveRequirement() {
        val fixture = RetainedProviderFixture()
        val canonical = fixture.normalized.copyBytes().decodeToString()
        assertTrue(canonical.startsWith("{"))
        val altered = listOf(StoredBytes(ByteArray(0)), StoredBytes("{".encodeToByteArray()),
            StoredBytes(canonical.dropLast(1).encodeToByteArray()),
            StoredBytes(" $canonical".encodeToByteArray()),
            StoredBytes(("{\"originalActorId\":\"${Pay10GatewayInputs.otherActor.value}\"," + canonical.drop(1)).encodeToByteArray()),
            LocalPaymentCorrectionCodec.request(LocalPaymentCorrectionQueryV1.Source(Pay10GatewayInputs.sale)).payload)
        for (body in altered) {
            assertNotEquals(fixture.normalized, body)
            fixture.assertNoEligibleRequirement(body = body)
        }
    }

    @Test
    fun pcRpcR05SupportChangedActorOrScopeWithOriginalDigestCannotDeriveRequirement() {
        val fixture = RetainedProviderFixture()
        val input = Pay10GatewayInputs
        val scopes = listOf(BranchScope(input.auxiliary(21), input.scope.companyId, input.scope.branchId),
            BranchScope(input.scope.organizationId, input.auxiliary(22), input.scope.branchId),
            BranchScope(input.scope.organizationId, input.scope.companyId, input.auxiliary(23)))
        val contexts = listOf(fixture.context(actor = input.otherActor)) + scopes.map { fixture.context(scope = it) }
        for (context in contexts) {
            assertEquals(fixture.originalDigest, context.semanticDigest)
            fixture.assertNoEligibleRequirement(context = context)
        }
        // This supplies a deliberately mismatched original digest. It does not
        // claim genuine stored-actor lookup/current-caller disclosure was tested.
    }

    @Test
    fun pcRpcR06SupportChangedAmount10001AndSemanticFieldsWithOriginalDigestCannotDeriveRequirement() {
        val fixture = RetainedProviderFixture()
        val input = Pay10GatewayInputs
        val changed = listOf(input.request.copy(expectedAmount = Money(Currency.EGP, 10001)),
            input.request.copy(reason = "synthetic receipt/count investigation changed"),
            input.request.copy(saleId = input.auxiliary(24)),
            input.request.copy(paymentAttemptId = input.auxiliary(25)),
            input.request.copy(expectedSessionRevision = Revision(5)))
        for (request in changed) {
            val body = LocalPaymentCorrectionCodec.encode(request)
            assertNotEquals(fixture.normalized, body)
            assertEquals(fixture.originalDigest, fixture.originalContext.semanticDigest)
            fixture.assertNoEligibleRequirement(body = body)
        }
        assertEquals(Money(Currency.EGP, 10000), input.request.expectedAmount)
        assertEquals(Revision(4), input.request.expectedSessionRevision)
    }

    @Test
    fun pcRpcR07SupportMismatchedDigestCannotDeriveRequirementFromTheOriginalBody() {
        val fixture = RetainedProviderFixture()
        val digest = PayloadDigest(if (fixture.originalDigest.value == "0".repeat(64)) "1".repeat(64) else "0".repeat(64))
        assertNotEquals(fixture.originalDigest, digest)
        fixture.assertNoEligibleRequirement(context = fixture.context(digest = digest))
    }
}

/** No qualified source/stock original or command read-view is supplied. */
private class RetainedProviderFixture {
    private val input = Pay10GatewayInputs
    val normalized = LocalPaymentCorrectionCodec.normalize(LocalPaymentCorrectionCodec.encode(input.request))
    private val originalBytes = normalized.copyBytes()
    val originalDigest = semanticDigest(input.scope, input.manager, LocalPaymentCorrectionCodec.COMMAND, normalized)
    val originalContext = context()
    private var tenderSourceAccesses = 0
    private var historicalPolicyAccesses = 0
    val handler = LocalPaymentCorrectionHandler(LocalStockRepository(), TransactionSalesTenderSource { _, _, _ ->
        tenderSourceAccesses++
        throw AssertionError("A retained-provider SUPPORT probe must not read current tender sources")
    }, object : HistoricalSalesTenderPolicySourceV1 {
        override fun decode(scope: BranchScope, row: StoredAggregate): LocalSalesTenderSnapshot? {
            historicalPolicyAccesses++
            throw AssertionError("A retained-provider SUPPORT probe must not read historical tender sources")
        }
        override fun decode(scope: BranchScope, row: StreamedSourceObservationV2.Current): LocalSalesTenderSnapshot? {
            historicalPolicyAccesses++
            throw AssertionError("A retained-provider SUPPORT probe must not read streamed tender sources")
        }
    })
    val provider: LocalMasterRpcRetainedResultAccessProvider = handler

    fun context(scope: BranchScope = input.scope, actor: DomainId = input.manager,
        key: CommandKey = LocalPaymentCorrectionCodec.COMMAND, digest: PayloadDigest = originalDigest) =
        CommandContext(scope, actor, input.operation, key, digest)

    fun assertNoEligibleRequirement(profile: LocalMasterRpcWorkflowProfile = LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS,
        body: StoredBytes = normalized, context: CommandContext = originalContext) {
        // The approved boundary may reject by throwing or by producing no
        // requirements. AssertionError from a fail-on-use source is never caught.
        val requirements = try { provider.rpcRetainedResultRequirements(profile, body, context) }
            catch (_: IllegalStateException) { null }
            catch (_: IllegalArgumentException) { null }
        if (requirements != null) assertTrue(requirements.isEmpty(), "Mismatched original must not yield an access requirement")
        assertOriginalUnchangedAndNoSourceAccess()
    }

    fun assertOriginalUnchangedAndNoSourceAccess() {
        assertEquals(0, tenderSourceAccesses)
        assertEquals(0, historicalPolicyAccesses)
        assertTrue(originalBytes.contentEquals(normalized.copyBytes()))
        assertEquals(input.request, LocalPaymentCorrectionCodec.decode(normalized))
        assertEquals(input.scope, originalContext.scope)
        assertEquals(input.manager, originalContext.originalActorId)
        assertEquals(input.operation, originalContext.operationId)
        assertEquals(LocalPaymentCorrectionCodec.COMMAND, originalContext.key)
        assertEquals(originalDigest, originalContext.semanticDigest)
        assertNull(originalContext.physicalRecording)
        assertNull(originalContext.physicalParticipants)
    }
}
