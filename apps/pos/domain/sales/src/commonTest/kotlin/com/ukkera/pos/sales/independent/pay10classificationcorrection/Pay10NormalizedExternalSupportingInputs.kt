package com.ukkera.pos.sales.independent.pay10classificationcorrection

import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryReservationHeader
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Reducer SUPPORT inputs only. No native identity verifier, actual reservation
 * allocation/stock rows, durable transaction, command journal or RPC is supplied.
 * Domain originals are produced by real normalized Start/outcome functions,
 * never by manufacturing PaymentRecord or accepting native-history fixtures.
 * Auxiliary coordinates follow the commerce-v1 per-family UUIDv5 alias rule.
 */
internal class Pay10NormalizedExternalFixture private constructor(private val ids: Map<String, String>) {
    val collector: DomainId = id("A")
    val otherActor: DomainId = id("B")
    val scope = BranchScope(id("O1"), id("C1"), id("C1-B1"))
    val paymentId: DomainId = id("PAYMENT")
    val secondAttemptId: DomainId = id("SECOND-ATTEMPT")
    val startOperation = operation("NORMALIZED-START-OP")
    val recordingOperation = operation("RECORD-OP")
    val secondStartOperation = operation("SECOND-START-OP")
    val card = Tender.External(id("TENDER"), Revision(1), ExternalTenderKind.CARD,
        requiresTerminal = false, requiresReference = ids["REQUIRES-REFERENCE"] != "false")
    val manager: DomainId get() = id("M")
    val saleId: DomainId get() = id("SALE")
    val closeOperation: OperationId get() = operation("CLOSE-OP")
    val correctionOperation = OperationId("a40f4978-c4fa-5844-a9a6-5b33b006e22b")
    val today: Instant = Instant.parse("2026-11-04T10:00:00Z")
    private val now = Instant.parse("2026-11-03T10:00:00Z")
    private val digest = PayloadDigest("4".repeat(64))
    private val reasonCode = "support.original-payment"

    lateinit var order: OwnedOrder
        private set
    lateinit var session: CashSession
        private set
    lateinit var collection: CollectionLock
        private set
    lateinit var reservation: InventoryReservationHeader
        private set

    fun evidence(): CashierEvidence = CashierEvidence(id("ORIGINAL-EVIDENCE"), collector, now)
    fun externalEvidence(): ExternalTenderEvidence = ExternalTenderEvidence(evidence(), ids["TERMINAL"], ids["REFERENCE"])
    fun correctionEvidence(actor: DomainId = manager): CashierEvidence =
        CashierEvidence(DomainId("ecd09578-5be9-5fb9-8d2b-f676bdefa3c1"), actor, today)
    fun snapshots(): Pair<String, String> =
        SalesSnapshotCodec.encodeCollection(collection) to SalesSnapshotCodec.encodeCashSession(session)

    suspend fun command(action: SecurityAction, target: DomainId, revision: Revision,
        amount: Money? = null, actor: DomainId = collector, operation: OperationId,
        recordedAt: Instant = now): SalesCommand {
        val generation = SessionGeneration(1)
        val identity = VerifiedLocalIdentity(actor, scope, id("LOGIN-SESSION"), id("DEVICE"), generation,
            Revision(1), AuthenticationPurpose.INTERACTIVE)
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity = identity
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val principal = checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(
            LocalGatewayCredentials(id("CONNECTION"), OpaqueCredential(ByteArray(32) { 0x41 }))))
        val required = salesAuthorizationRequirement(action, scope, target, revision, operation,
            digest, reasonCode, actor, amount)
        val snapshot = AuthorizationSnapshot(actor, principal.kind, scope, identity.sessionId, generation,
            identity.deviceId, identity.admissionRevision, Revision(1), true, true, true, identity.purpose,
            listOf(PermissionGrant(required.permission, scope, GrantEffect.ALLOW, PermissionLimits(amount))))
        val decision = AuthorizationEvaluator.evaluate(principal, snapshot, required)
        check(decision is AuthorizationDecision.Allowed) { "Explicit controlled SUPPORT grant did not bind" }
        return SalesCommand(operation, decision.authorization, recordedAt, digest, reasonCode)
        // The placeholder digest and controlled principal cannot qualify kernel
        // semantic idempotency, native current authority or authenticated history.
    }

    suspend fun start(amount: Money = Money(Currency.EGP, 10000),
        attemptId: DomainId = paymentId, operation: OperationId = startOperation): SalesTransition<CollectionWithCash> {
        val command = command(SecurityAction.COLLECTION_ATTEST, collection.id, collection.revision, amount,
            operation = operation)
        val result = Collections.startNormalizedAttempt(command, collection, collection.revision,
            session, session.revision, attemptId, amount, reservation, tender = card)
        collection = result.value.collection
        session = result.value.session
        return result
    }

    suspend fun record(outcome: CollectionOutcome, actor: DomainId = collector): SalesTransition<CollectionWithCash> {
        val attempt = collection.attempts.single { it.id == paymentId }
        val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.COLLECTION_RESOLVE
            else SecurityAction.COLLECTION_ATTEST
        val command = command(action, collection.id, collection.revision, Money(Currency.EGP, 10000), actor,
            recordingOperation)
        val result = Collections.recordOutcome(command, collection, collection.revision, session,
            session.revision, paymentId, outcome)
        collection = result.value.collection
        session = result.value.session
        return result
    }

    /** Actual reducers, but issued header/closing-work inputs remain inert SUPPORT data. */
    suspend fun closedPay10Original(): Pay10ClosedSupportingOriginal {
        start()
        record(CollectionOutcome.ExternalAttested(externalEvidence()))
        val issued = reservation.copy(revision = Revision(2), status = ReservationStatus.ISSUED, issueId = id("ISSUE"))
        val finalized = Collections.finalizeNormalized(command(SecurityAction.COLLECTION_FINALIZE, order.id,
            order.revision, Money(Currency.EGP, 10000), operation = operation("FINALIZE-OP")), order,
            order.revision, collection, collection.revision, saleId, issued, id("ISSUE")).value
        order = finalized.order
        collection = finalized.collection
        val beforeClosing = session
        val work = ClosingWorkReadSet(scope, collector, Revision(0), emptyList(), emptyList())
        session = CashSessions.beginClosing(command(SecurityAction.CASH_CLOSE, session.id, session.revision,
            operation = operation("BEGIN-CLOSE-OP")), session, session.revision, work, work.revision).value
        val assignment = CashAssignmentReadSet(scope, session.drawerId, Revision(1), session.id, null,
            collector, Revision(1), session.id)
        session = CashSessions.submitClose(command(SecurityAction.CASH_CLOSE, session.id, session.revision,
            Money(Currency.EGP, 110000), operation = closeOperation), session, session.revision, assignment,
            CountedCash(Money(Currency.EGP, 110000), CashierEvidence(id("COUNT-EVIDENCE"), collector, now)),
            Money(Currency.EGP, 0), id("SAFE-TRANSFER"), id("SAFE"), "synthetic original overage",
            CashierEvidence(id("DELIVERY-EVIDENCE"), collector, now)).value.session
        return Pay10ClosedSupportingOriginal(this, finalized.sale, finalized.entitlement, beforeClosing)
    }

    private suspend fun initialize(): Pay10NormalizedExternalFixture {
        val quantity = Quantity(id("UNIT"), 1, 0)
        val tax = TaxSnapshot(id("TAX"), Revision(1), "SYNTHETIC-NONFISCAL", PercentageRate.parse("0"))
        val quote = OperationalPricingEngine.calculate(PricingRequest(id("ORDER"), Revision(0), Currency.EGP,
            listOf(ResolvedPriceLine(id("LINE"), id("PORTION"), Revision(1), quantity,
                UnitPrice.parse(Currency.EGP, "100.00"), TaxInclusion.EXCLUSIVE, tax))))
        val stock = RetailStockRequirements.normalized(listOf(RetailStockDemandV2(id("PORTION"),
            id("SKU"), id("WAREHOUSE"), quantity)))
        order = Orders.create(command(SecurityAction.ORDER_CREATE, id("ORDER"), Revision(0),
            operation = operation("CREATE-OP")), scope, id("ORDER"), quote, stock).value
        val assignment = CashAssignmentReadSet(scope, id("DA"), Revision(1), null, null,
            collector, Revision(1), null)
        session = CashSessions.open(command(SecurityAction.CASH_OPEN, id("DA"), Revision(1),
            Money(Currency.EGP, 100000), operation = operation("OPEN-OP")), assignment,
            Revision(1), Revision(1), id("SA"), LocalDate(2026, 11, 3),
            CountedCash(Money(Currency.EGP, 100000), CashierEvidence(id("OPENING-EVIDENCE"), collector, now)),
            id("OPENING-SOURCE")).value
        reservation = InventoryReservationHeader(id("RESERVATION"), CollectionBinding(order.id, quote.revision,
            id("COLLECTION"), collector, CollectionExposure.UNKNOWN), Revision(1), ReservationStatus.RESERVED,
            expiresAtEpochMillis = null, allocationCount = 1, holdCount = 0)
        val begun = Collections.beginNormalized(command(SecurityAction.COLLECTION_BEGIN, order.id,
            order.revision, Money(Currency.EGP, 10000), operation = operation("BEGIN-OP")), order,
            order.revision, session, session.revision, id("COLLECTION"), reservation).value
        order = begun.order
        collection = begun.collection
        return this
    }

    private fun id(alias: String): DomainId = DomainId(ids.getValue(alias))
    private fun operation(alias: String): OperationId = OperationId(ids.getValue(alias))

    companion object {
        suspend fun pay01(): Pay10NormalizedExternalFixture = Pay10NormalizedExternalFixture(PAY01).initialize()
        suspend fun pay04(): Pay10NormalizedExternalFixture = Pay10NormalizedExternalFixture(PAY04).initialize()
        suspend fun pay10(): Pay10NormalizedExternalFixture = Pay10NormalizedExternalFixture(PAY10).initialize()

        private val PAY01 = mapOf(
            "O1" to "f9934f0b-5e7e-5eaa-9e64-a64c9fb85329",
            "C1" to "b6fdf0e4-14b0-52dc-ab17-c94a2731f9a7",
            "C1-B1" to "36325752-f78b-5ff4-be35-69add5041f40",
            "A" to "a8d9bd87-76d7-5c69-a5ec-78ebae075b81",
            "B" to "a4c49eef-4bdd-512b-b123-8e439550d0e5",
            "SA" to "8785af53-d7b8-5723-9ab6-69d4e33cce82",
            "DA" to "0fd4dec8-2442-5825-bd3f-e44a023581fa",
            "ORDER" to "0a7afe68-3d9a-5e7d-b7c1-ff0c913ff6d3",
            "COLLECTION" to "e8fed1a1-26ba-598f-8265-32b6feccb226",
            "PAYMENT" to "21cc321b-7442-595c-893d-f56bde854a07",
            "RESERVATION" to "8b8195c7-35fa-58e7-87af-d123183af035",
            "SKU" to "ffd7e63c-e760-5763-8e82-e4310563eed2",
            "WAREHOUSE" to "49699002-9224-593e-9665-8a63591e84ec",
            "UNIT" to "80a25684-976b-5f5f-9cab-3adbd4fea1d5",
            "LINE" to "33fba387-f063-5450-9f91-ab2d97cc8e5a",
            "PORTION" to "4a7e6f8c-41fb-5b8e-9bcd-442453d7c2c5",
            "TAX" to "8ac3ce17-3991-57a9-b286-cd39872f2a87",
            "TENDER" to "7d721822-9cfa-57ff-9d2b-dcd7498f5f8b",
            "DEVICE" to "3c2151e2-814f-517e-8f5b-134c02adf346",
            "LOGIN-SESSION" to "18585558-2d78-5784-9d67-b5a1c7000f7d",
            "CONNECTION" to "aa17b2a0-ab0d-5b74-9485-182da8c03cd3",
            "ORIGINAL-EVIDENCE" to "954540bf-a537-5577-8d17-cbd4187f6bab",
            "OPENING-EVIDENCE" to "4909d463-d287-5f79-860d-db20e3d0407a",
            "OPENING-SOURCE" to "f911e25c-730f-5f8e-b2e3-b5d098387ecf",
            "NORMALIZED-START-OP" to "0d6aea33-b142-5791-8eea-91e5d2064f58",
            "OPEN-OP" to "b0cc1560-b56a-536d-83ac-b78d35a8ba0a",
            "CREATE-OP" to "1f206dea-9b75-55de-b85c-717145175e3c",
            "BEGIN-OP" to "adb3dce6-f305-5b24-98b4-c842f28cd12f",
            "SECOND-START-OP" to "463c0acd-5adc-5d34-88dd-2f9389a6ae5c",
            "SECOND-ATTEMPT" to "175dd72e-860f-538d-8623-3f691bba4643",
            "RECORD-OP" to "a3f72031-c221-5f7e-aa95-9c88c6a31959",
            "TERMINAL" to "SYN-T1",
            "REFERENCE" to "SYN-OK-001",
        )
        private val PAY04 = mapOf(
            "O1" to "65163ce4-455b-5cb1-a390-69130c824f2a",
            "C1" to "845b54c3-9b12-5121-b6ca-4a61967b38a9",
            "C1-B1" to "bda83143-f7a7-5102-8be5-c5e2c3505cd2",
            "A" to "ca5f39cf-8ed3-5ee3-802a-617319e0d953",
            "B" to "c1ee513d-f4ec-5bb4-8029-67ec0a670bae",
            "SA" to "f1a29ef7-8cac-55b3-96cf-bf2a436d6545",
            "DA" to "d6aa7c2e-03e5-5ba7-ae1c-ee7832c3e5a6",
            "ORDER" to "f382e7c8-b053-509f-93ab-62d636c38d95",
            "COLLECTION" to "19a86905-c168-534b-a73b-84eaaef09ab3",
            "PAYMENT" to "a88d4243-9065-5019-8c6b-47d39da9b721",
            "RESERVATION" to "85b73308-a33f-57b5-af51-4f70a1ad53a5",
            "SKU" to "6a27cdaa-9e12-5bac-81eb-675a93fd84f5",
            "WAREHOUSE" to "47c86b5f-cc0e-5ab8-977b-db144a8ee2d6",
            "UNIT" to "f486ee6a-d966-5c83-b24b-ae873221b5e2",
            "LINE" to "ebece873-16e2-5821-bacc-d079fe8ef8d1",
            "PORTION" to "85b95906-45c6-50ec-aa77-dfb3e15c1776",
            "TAX" to "b165e8eb-b8c9-5a69-87fb-19972e0c6bd9",
            "TENDER" to "e681879e-6060-5a3c-b0dd-dd25c0ab77f1",
            "DEVICE" to "c2967f9b-5c81-5b8f-999f-f9aa57aee7d4",
            "LOGIN-SESSION" to "6a36c5c6-027a-5b74-9ee5-cb8788e34c0e",
            "CONNECTION" to "580964b6-ef0e-5e50-a9b9-4c5d8b1b7adc",
            "ORIGINAL-EVIDENCE" to "f744144c-53af-5c4e-b55b-8ece71568f6d",
            "OPENING-EVIDENCE" to "1566c262-740c-5240-9770-de1570147b46",
            "OPENING-SOURCE" to "85d87f73-6184-5a2d-9542-5f7ac567adee",
            "NORMALIZED-START-OP" to "4434d6ad-2d0e-5d6d-96e9-f9128995639d",
            "OPEN-OP" to "d00bbec3-72f4-595c-9c5b-5b3c95e51200",
            "CREATE-OP" to "fa485206-16f4-5058-92aa-7e4c7dde1fda",
            "BEGIN-OP" to "440609bd-61bb-52ca-ae13-5eaf7938b311",
            "SECOND-START-OP" to "c3abc4a9-ca77-54f3-a6ee-771d76682004",
            "SECOND-ATTEMPT" to "57624ca5-8b79-5495-9cd5-301a84a81bd4",
            "RECORD-OP" to "c406e17f-d121-51ed-8eef-428760ab47ed",
            "REFERENCE" to "SYN-UNKNOWN",
        )
        private val PAY10 = mapOf(
            "O1" to "288ff26c-1235-539d-9db9-9f19b95a6d4f",
            "C1" to "86fce531-4d27-574e-a42a-e10d8d3394b6",
            "C1-B1" to "67479b28-7d5f-5497-8ab3-727a1485a25c",
            "A" to "24a96c96-d193-5950-98ed-5e90d0a5a59e",
            "B" to "4183672f-b34c-57e5-afe1-a97428460189",
            "M" to "d98be03d-544f-5317-9838-e4a170d7aca2",
            "SA" to "b2be07ab-e9b9-5898-bb88-6442ab466aff",
            "DA" to "34d295b5-8964-5ab5-adc8-fb2723c294fb",
            "ORDER" to "347f9aa6-dcdc-59cd-bd68-35aa9ddc5708",
            "SALE" to "8f6d317d-2e03-5c79-91d9-8c8dd7e5a308",
            "COLLECTION" to "14c76b07-b3e1-5082-b7a5-762ba29f13eb",
            "PAYMENT" to "442df5db-d4e2-57ca-89ea-c864263f3440",
            "RESERVATION" to "98400781-f08e-5172-8b6f-d08d63754c2b",
            "SKU" to "2d43457e-c2ed-5296-b854-5889c0b02134",
            "WAREHOUSE" to "be7e98c0-33be-5dc4-bf8c-c5cf85e46cca",
            "UNIT" to "2f73b4dc-8cbc-536a-ba81-7587c0834064",
            "LINE" to "2e7ae631-d7d5-53af-a7f4-ba045f55be1c",
            "PORTION" to "0e21f9de-9d02-585e-a10a-f266c70d67e4",
            "TAX" to "0601a527-6f8f-5ace-a143-cefe84808fd8",
            "TENDER" to "e0e13c6a-63d7-5d10-9c31-7fd89d6c6f27",
            "DEVICE" to "62a29e2c-c25b-5635-b18b-f1ca62c016de",
            "LOGIN-SESSION" to "958e1a78-d1bc-5a72-8e9f-2ddfb95884e1",
            "CONNECTION" to "da5a8182-5cae-56e6-a8c8-0d800c3c4b05",
            "ORIGINAL-EVIDENCE" to "0b12b510-703c-55b6-a49b-23008e691563",
            "OPENING-EVIDENCE" to "97a521f9-02ba-5570-8160-34f50e0fc4c6",
            "OPENING-SOURCE" to "fc089f2b-edf2-568d-9eea-4113d8b307f2",
            "NORMALIZED-START-OP" to "1c018b5d-ed2c-5ee2-a532-1fe5c93a6399",
            "OPEN-OP" to "51b96acd-4573-5d4b-8d45-9fccc86ce632",
            "CREATE-OP" to "cd78e2d8-bdba-573c-98b8-acadc73e5b0d",
            "BEGIN-OP" to "71466c1d-9e87-5ee3-941d-eed396e8fdf6",
            "SECOND-START-OP" to "164e5962-579a-5008-b70c-e07d556cc4e7",
            "SECOND-ATTEMPT" to "8ea27cf9-49aa-5f5b-a7e6-ae4ef2e0a9ec",
            "RECORD-OP" to "c7ca4bc0-6d94-54d9-8745-7797b6619b4d",
            "FINALIZE-OP" to "a5ab7db2-5f1c-5aa2-b6ff-4f6923c20a61",
            "ISSUE" to "73945f8e-1ceb-57d1-a0a6-3b9b0a11ecdd",
            "BEGIN-CLOSE-OP" to "82636931-6d36-5ce6-be2e-5657c45663f3",
            "CLOSE-OP" to "d493f524-100d-57b5-b120-9128099762dd",
            "COUNT-EVIDENCE" to "3e743c06-01f1-5d2b-9e2c-bcbc50ae5d7a",
            "SAFE-TRANSFER" to "38e7e881-577c-5423-af05-e31afdd8382e",
            "SAFE" to "dc8dd679-608b-5c2a-838e-b91fee350d41",
            "DELIVERY-EVIDENCE" to "e6c28bb0-78d4-57cd-b206-3206f2cde78d",
            "REQUIRES-REFERENCE" to "false",
        )
    }
}

/** Snapshot immutability SUPPORT, not evidence the input originals were committed. */
internal class Pay10ClosedSupportingOriginal(val input: Pay10NormalizedExternalFixture,
    val sale: PaidSale, val entitlement: ReceiptEntitlement, val beforeClosing: CashSession) {
    val session: CashSession get() = input.session
    fun snapshots(): List<String> = listOf(SalesSnapshotCodec.encodePaidSale(sale),
        SalesSnapshotCodec.encodeCashSession(session), SalesSnapshotCodec.encodeReceiptEntitlement(entitlement))

    suspend fun correction(expectedAmount: Money = Money(Currency.EGP, 10000),
        paymentAttemptId: DomainId = input.paymentId, sourceSession: CashSession = session,
        expectedSessionRevision: Revision = sourceSession.revision,
        action: SecurityAction = SecurityAction.PAYMENT_CLASSIFICATION_CORRECT,
        proofAmount: Money = Money(Currency.EGP, 10000), proofTarget: DomainId = sale.id,
        evidence: CashierEvidence = input.correctionEvidence(), previousClassification: Tender = input.card): PaymentClassificationCorrection {
        val command = input.command(action, proofTarget, expectedSessionRevision, proofAmount,
            input.manager, input.correctionOperation, input.today)
        return PaymentClassificationCorrections.record(command, sale, paymentAttemptId, sourceSession,
            expectedSessionRevision, expectedAmount, Tender.Cash, Revision(0), null, previousClassification,
            evidence, "synthetic receipt/count investigation")
    }
}
