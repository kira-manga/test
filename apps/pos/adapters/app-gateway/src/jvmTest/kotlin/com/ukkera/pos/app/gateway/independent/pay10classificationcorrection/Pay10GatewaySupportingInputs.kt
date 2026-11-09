package com.ukkera.pos.app.gateway.independent.pay10classificationcorrection

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/** Frozen PAY-10 values with auxiliary carrier coordinates. No native source is supplied. */
internal object Pay10GatewayInputs {
    val sale = DomainId("8f6d317d-2e03-5c79-91d9-8c8dd7e5a308")
    val payment = DomainId("442df5db-d4e2-57ca-89ea-c864263f3440")
    val order = DomainId("347f9aa6-dcdc-59cd-bd68-35aa9ddc5708")
    val cashSession = DomainId("b2be07ab-e9b9-5898-bb88-6442ab466aff")
    val collector = DomainId("24a96c96-d193-5950-98ed-5e90d0a5a59e")
    val manager = DomainId("d98be03d-544f-5317-9838-e4a170d7aca2")
    fun auxiliary(slot: Int) = DomainId("f1000000-0000-4000-8000-${slot.toString().padStart(12, '0')}")
    val otherActor = DomainId("4183672f-b34c-57e5-afe1-a97428460189")
    val scope = BranchScope(auxiliary(2), auxiliary(3), auxiliary(4))
    val device = auxiliary(5)
    val owner = LocalClientIdentity(scope, manager, device)
    val operation = OperationId("a40f4978-c4fa-5844-a9a6-5b33b006e22b")
    val closeOperation = OperationId("d493f524-100d-57b5-b120-9128099762dd")
    val intentOperation = OperationId("1c018b5d-ed2c-5ee2-a532-1fe5c93a6399")
    val recordingOperation = OperationId("c7ca4bc0-6d94-54d9-8745-7797b6619b4d")
    val sessionRevision = Revision(4)
    val yesterday = UtcInstant.parseCanonical("2026-11-03T10:00:00Z")
    val today = UtcInstant.parseCanonical("2026-11-04T10:00:00Z")
    val card = LocalTenderViewV1("CARD", DomainId("e0e13c6a-63d7-5d10-9c31-7fd89d6c6f27"), Revision(1))
    val evidence = LocalCashEvidenceV1(DomainId("ecd09578-5be9-5fb9-8d2b-f676bdefa3c1"), today)
    val request = LocalPaymentCorrectionRequestV1(sale, payment, sessionRevision, null,
        Money(Currency.EGP, 10000), LocalTenderSelectionV1.Cash, evidence, "synthetic receipt/count investigation")
    val close = LocalPaymentCorrectionCloseViewV1(cashSession, closeOperation, Revision(2),
        Money(Currency.EGP, 100000), Money(Currency.EGP, 10000), Money(Currency.EGP, 0),
        Money(Currency.EGP, 110000), Money(Currency.EGP, 10000), Money(Currency.EGP, 0), Money(Currency.EGP, 110000),
        DomainId("38e7e881-577c-5423-af05-e31afdd8382e"), collector,
        LocalCashActorEvidenceV1(DomainId("3e743c06-01f1-5d2b-9e2c-bcbc50ae5d7a"), collector, yesterday),
        "synthetic original overage")
    val originalPayment = LocalPaymentCorrectionPaymentViewV1(payment, Money(Currency.EGP, 10000), card,
        intentOperation, recordingOperation,
        LocalCashActorEvidenceV1(DomainId("0b12b510-703c-55b6-a49b-23008e691563"), collector, yesterday),
        null, null, null, null, card, 0)
    val source = LocalPaymentCorrectionSourceViewV1(sale, order, collector, cashSession,
        sessionRevision, close, listOf(originalPayment))
    val correction = LocalPaymentCorrectionViewV1(sale, payment, cashSession, collector, intentOperation,
        recordingOperation, closeOperation, Money(Currency.EGP, 10000), card, card, LocalTenderViewV1("CASH"),
        Money(Currency.EGP, -10000), Money(Currency.EGP, 10000), Money(Currency.EGP, 0), Revision(0), null,
        LocalCashActorEvidenceV1(evidence.evidenceId, manager, today), "synthetic receipt/count investigation",
        null, null, operation, today)
    val page = LocalPaymentCorrectionsPageV1(sale, cashSession, sessionRevision, close,
        originalPayment.copy(latestCorrectionId = correction.id, latestClassification = LocalTenderViewV1("CASH"), totalCorrections = 1),
        null, listOf(correction), null)
    val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    fun intent(amount: String = "100.00", extra: List<LocalScalarInput> = emptyList(),
        version: Int = 1, action: LocalAction = LocalAction.PAYMENT_CLASSIFICATION_CORRECT,
        replace: Map<LocalField, LocalScalarValue> = emptyMap(), omit: Set<LocalField> = emptySet(),
        lineField: LocalField? = null, lines: List<LocalInputLine> = emptyList()): LocalCommandIntent {
        val values = listOf(
            LocalScalarInput(LocalField.SALE_ID, LocalScalarValue.Identifier(sale)),
            LocalScalarInput(LocalField.PAYMENT_ID, LocalScalarValue.Identifier(payment)),
            LocalScalarInput(LocalField.EXPECTED_SESSION_REVISION, LocalScalarValue.Integer(sessionRevision.value)),
            LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(amount)),
            LocalScalarInput(LocalField.PAYMENT_METHOD, LocalScalarValue.Choice("CASH")),
            LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(evidence.evidenceId)),
            LocalScalarInput(LocalField.OCCURRED_AT, LocalScalarValue.Text(today.canonicalString())),
            LocalScalarInput(LocalField.REASON, LocalScalarValue.Text("synthetic receipt/count investigation")),
        ).filterNot { it.field in omit }.map { value -> replace[value.field]?.let { value.copy(value = it) } ?: value } + extra
        return LocalCommandIntent(operation, action, version, null, values, lineField, lines)
    }

    fun mutation(value: LocalPaymentCorrectionRequestV1 = request) =
        MutationRequest(operation, LocalPaymentCorrectionCodec.COMMAND, LocalPaymentCorrectionCodec.encode(value))
    fun staged(value: LocalPaymentCorrectionRequestV1 = request, identity: LocalClientIdentity = owner,
        original: LocalCommandIntent = intent()) =
        StoredClientIntentV1.staged(identity, original, mutation(value), LocalText("Tender correction", "تصحيح وسيلة الدفع"),
            paymentCorrectionSummary(mutation(value)))
    fun correctionResult(value: LocalPaymentCorrectionResultV1): StoredBytes =
        StoredBytes(wire.encodeToString(LocalPaymentCorrectionResultV1.serializer(), value).encodeToByteArray())
    fun readReply(value: LocalPaymentCorrectionReadResultV1) = ReadReply.Data(
        StoredBytes(wire.encodeToString(LocalPaymentCorrectionReadResultV1.serializer(), value).encodeToByteArray()),
        Revision(8), today)
    fun recorded(value: LocalPaymentCorrectionViewV1 = correction, committed: Boolean = true,
        code: String = "committed", actor: DomainId = manager, operationId: OperationId = operation,
        revision: Revision = sessionRevision, replayed: Boolean = false) = MutationReply.Recorded(operationId,
        actor, committed, code, correctionResult(LocalPaymentCorrectionResultV1.Recorded(value, revision)),
        Revision(1), JournalSequence(1), today, replayed)
    fun refused(committed: Boolean = false, code: String = "sales.payment_correction.refused") = MutationReply.Recorded(
        operation, manager, committed, code,
        correctionResult(LocalPaymentCorrectionResultV1.Refused(LocalPaymentCorrectionRefusalV1.STALE_SOURCE)),
        Revision(1), JournalSequence(1), today, false)
    fun grant(effect: GrantEffect = GrantEffect.ALLOW, target: DomainId = sale, grantScope: BranchScope = scope) = PermissionGrant(
        Permission(SecurityAction.PAYMENT_CLASSIFICATION_CORRECT, PermissionVerb.PERFORM), grantScope, effect,
        targetIds = setOf(target))
    fun sessionView(grants: List<PermissionGrant> = listOf(grant()), actor: DomainId = manager): LocalSessionView {
        val policy = ActivatedLocalPolicy(scope, actor, Revision(1), grants,
            LocalSessionPolicy(1), ThrottlePolicy(0, 1000, 1000, 1000))
        val moment = MonotonicMoment(auxiliary(6), 100000)
        return LocalSessionView(actor, scope, device, auxiliary(7), SessionGeneration(1), LocalLoginIdentifier("pay10-support"),
            policy, moment, moment)
    }
    fun historyQuery(cursor: String? = null, saleId: DomainId = sale) = LocalWorkflowQuery(LocalWorkflow.ORDERS,
        reference = LocalRecordReference(LocalRecordKind.PAYMENT_CORRECTION_HISTORY, payment, parentId = saleId), cursor = cursor)
}

/** Controlled read projection only: no writer, native credentials or RPC transport. */
internal class Pay10ProjectionClient : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    val reads = mutableListOf<ReadRequest>()
    var executeCalls = 0
    var lookupCalls = 0
    var projection: (ReadRequest) -> ReadReply = { error("Unassigned controlled projection") }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean) = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials) = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials) = LocalSignOutResult.Unavailable
    override suspend fun current(credentials: LocalGatewayCredentials) = LocalSessionViewResult.Current(Pay10GatewayInputs.sessionView())
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        return projection(request)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++
        error("A documentary mapping support test must not execute business commands")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookupCalls++
        error("A documentary mapping support test must not claim native result recovery")
    }
}

internal class Pay10GatewayFixture(grants: List<PermissionGrant> = listOf(Pay10GatewayInputs.grant()),
    actor: DomainId = Pay10GatewayInputs.manager, readInstalled: Boolean = true, writeInstalled: Boolean = true) {
    val endpoint = Pay10ProjectionClient()
    var current = true
    private val credential = LocalGatewayCredentials(Pay10GatewayInputs.auxiliary(8), OpaqueCredential(ByteArray(32) { 0x5a }))
    private val lease = LocalWorkflowCredentialLease(credential) { current }
    val access = GatewayAccess(endpoint, lease, Pay10GatewayInputs.sessionView(grants, actor),
        LocalWorkflowInstallation(if (writeInstalled) setOf(LocalPaymentCorrectionCodec.COMMAND) else emptySet(),
            if (readInstalled) setOf(LocalPaymentCorrectionCodec.QUERY) else emptySet()))
    fun close() = lease.destroy()
}

internal suspend fun <T> withPay10Gateway(grants: List<PermissionGrant> = listOf(Pay10GatewayInputs.grant()),
    actor: DomainId = Pay10GatewayInputs.manager, readInstalled: Boolean = true, writeInstalled: Boolean = true,
    block: suspend (Pay10GatewayFixture) -> T): T {
    val fixture = Pay10GatewayFixture(grants, actor, readInstalled, writeInstalled)
    return try { block(fixture) } finally { fixture.close() }
}
