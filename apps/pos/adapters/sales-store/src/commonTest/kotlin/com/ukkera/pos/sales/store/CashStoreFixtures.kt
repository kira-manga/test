package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.StockReceipt
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.CashAssignmentReadSet
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessions
import com.ukkera.pos.sales.CashWithTransfer
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.ClosingWorkReadSet
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.Collections
import com.ukkera.pos.sales.CountedCash
import com.ukkera.pos.sales.InventoryCommitDependency
import com.ukkera.pos.sales.Orders
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.RefundWithCash
import com.ukkera.pos.sales.RetailStockRequirements
import com.ukkera.pos.sales.ReturnClaimEvidence
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.Returns
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.SalesTransition
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedCentralIdentity
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlinx.datetime.LocalDate
import kotlin.test.assertIs

internal fun cashId(value: Int): DomainId = DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
internal fun cashMoney(minor: Long): Money = Money(Currency.EGP, minor)
internal fun cashBytes(value: String): StoredBytes = StoredBytes(value.encodeToByteArray())
internal fun StoredBytes.cashText(): String = copyBytes().decodeToString(throwOnInvalidSequence = true)

/**
 * SYNTHETIC TEST-ONLY identities and pure domain proposals. Not a database, seed
 * service, protected-login implementation, accepting runtime or durability proof.
 * All ordinary cash/pending fixtures are produced by the actual domain engines.
 */
internal class CashStoreFixture {
    val scope = BranchScope(cashId(1), cashId(2), cashId(3))
    val owner = cashId(4)
    val other = cashId(5)
    val now = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    val occurred = UtcInstant.parseCanonical("2026-10-23T11:59:00Z")
    private var sequence = 100
    fun nextId(): DomainId = cashId(sequence++)
    fun nextOperation(): OperationId = OperationId(nextId().value)

    suspend fun decision(
        requirement: AuthorizationRequirement,
        actor: DomainId = owner,
        maximum: Money = cashMoney(Long.MAX_VALUE),
        ownerAccess: OwnerAccess = OwnerAccess.OWN_ONLY,
    ): AuthorizationDecision {
        val branch = requirement.scope as BranchScope
        val identity = VerifiedLocalIdentity(actor, branch, nextId(), nextId(), SessionGeneration(1),
            Revision(2), AuthenticationPurpose.INTERACTIVE)
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity = identity
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val principal = checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(
            LocalGatewayCredentials(nextId(), OpaqueCredential(ByteArray(32) { 7 }))))
        val snapshot = AuthorizationSnapshot(actor, principal.kind, branch, identity.sessionId, identity.sessionGeneration,
            identity.deviceId, identity.admissionRevision, Revision(3), true, true, true, identity.purpose,
            listOf(PermissionGrant(requirement.permission, branch, GrantEffect.ALLOW, PermissionLimits(maximum), ownerAccess)))
        return AuthorizationEvaluator.evaluate(principal, snapshot, requirement)
    }

    suspend fun authorization(requirement: AuthorizationRequirement, actor: DomainId = owner): AuthorizedAction =
        assertIs<AuthorizationDecision.Allowed>(decision(requirement, actor)).authorization

    suspend fun command(
        action: SecurityAction,
        targetId: DomainId,
        revision: Revision,
        amount: Money? = null,
        actor: DomainId = owner,
    ): SalesCommand {
        val operation = nextOperation()
        val digest = PayloadDigest("1".repeat(64))
        val reason = "synthetic.fixture"
        val requirement = salesAuthorizationRequirement(action, scope, targetId, revision, operation, digest, reason, actor, amount)
        return SalesCommand(operation, authorization(requirement, actor), now.value, digest, reason)
    }

    suspend fun open(amount: Money = cashMoney(1_000), actor: DomainId = owner): CashSession {
        val assignments = CashAssignmentReadSet(scope, nextId(), Revision(2), null, null, actor, Revision(4), null)
        val command = command(SecurityAction.CASH_OPEN, assignments.drawerId, assignments.drawerRevision, amount, actor)
        return CashSessions.open(command, assignments, assignments.drawerRevision, assignments.custodianRevision,
            nextId(), LocalDate(2026, 10, 23), CountedCash(amount, CashierEvidence(nextId(), actor, occurred.value)), nextId()).value
    }

    fun request(session: CashSession, kind: NonSaleCashKind = NonSaleCashKind.NON_SALE_PAY_IN, amount: Money = cashMoney(100)): CashNonSaleRequestV1 =
        CashNonSaleRequestV1(session.id, session.revision, nextId(), kind, amount, nextId(), occurred,
            "Synthetic actual cash: drawer \"A\" [counted].\nNo bank assertion.")

    fun context(request: CashNonSaleRequestV1, actor: DomainId = owner, operation: OperationId = nextOperation()): CashNonSaleContext =
        CashNonSaleContext(scope, actor, operation, CashNonSaleStoreProfile.COMMAND,
            semanticDigest(scope, actor, CashNonSaleStoreProfile.COMMAND, CashNonSaleCommandCodec.encode(request)))

    fun row(session: CashSession): CashNonSaleRow = CashNonSaleRow(CashNonSaleStoreProfile.sessionKey(session.id),
        SalesStorageRevision.toStorage(session.revision), CashNonSaleStoreProfile.SESSION_SCHEMA,
        cashBytes(SalesSnapshotCodec.encodeCashSession(session)))

    fun cut(session: CashSession?, movement: CashNonSaleRow? = null, sourceOwner: DomainId? = null): CashNonSaleReadCut =
        CashNonSaleReadCut(scope, session?.let(::row), movement, sourceOwner)

    suspend fun evaluate(
        request: CashNonSaleRequestV1,
        cut: CashNonSaleReadCut,
        context: CashNonSaleContext = context(request),
    ): ProposedOutcome {
        val prepared = prepareCashNonSale(CashNonSaleCommandCodec.encode(request), context, cut)
        return prepared.evaluate(authorization(prepared.requirement, context.originalActorId), now)
    }

    fun sessionAfter(change: AggregateChange): CashSession = SalesSnapshotCodec.decodeCashSession(change.payload.cashText(),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, scope, change.key.id,
            SalesStorageRevision.toDomain(checkNotNull(change.expectedRevision).next())))

    suspend fun transition(session: CashSession, request: CashNonSaleRequestV1): SalesTransition<CashSession> {
        val context = context(request, session.custodianId)
        val requirement = salesAuthorizationRequirement(SecurityAction.CASH_MOVE, scope, session.id,
            request.expectedDomainRevision, context.operationId, context.semanticDigest, request.kind.reasonCode,
            session.custodianId, request.amount)
        val command = SalesCommand(context.operationId, authorization(requirement, session.custodianId), now.value,
            context.semanticDigest, request.kind.reasonCode)
        return CashSessions.recordNonSale(command, session, request.expectedDomainRevision, request.sourceId,
            request.kind.domain, request.amount, CashierEvidence(request.evidenceId, session.custodianId, request.occurredAt.value), request.reason)
    }

    suspend fun dropped(session: CashSession): CashWithTransfer = CashSessions.dropToSafe(
        command(SecurityAction.CASH_TRANSFER, session.id, session.revision, cashMoney(100), session.custodianId),
        session, session.revision, nextId(), nextId(), cashMoney(100), CashierEvidence(nextId(), session.custodianId, occurred.value)).value

    suspend fun closing(session: CashSession): CashSession {
        val work = ClosingWorkReadSet(scope, session.custodianId, Revision(1), emptyList(), emptyList())
        return CashSessions.beginClosing(command(SecurityAction.CASH_CLOSE, session.id, session.revision, actor = session.custodianId),
            session, session.revision, work, work.revision).value
    }

    /** Actual stock reservation + locked retail quote + admitted cash attempt, not fabricated coverage. */
    suspend fun pending(session: CashSession): PendingCashFixture {
        val quantity = Quantity(nextId(), 1, 0)
        val tax = TaxSnapshot(nextId(), Revision(1), "SYNTHETIC_ONLY", PercentageRate.parse("0"))
        val line = ResolvedPriceLine(nextId(), nextId(), Revision(1), quantity, UnitPrice.parse(Currency.EGP, "1"),
            TaxInclusion.EXCLUSIVE, tax, DiscountLimits.none(Currency.EGP))
        val quote = OperationalPricingEngine.calculate(PricingRequest(nextId(), Revision(1), Currency.EGP, listOf(line)))
        val empty = InventorySnapshot.empty(InventoryScope(scope.companyId, scope.branchId))
        val position = StockPositionKey(StockPoolKey(empty.scope, nextId(), nextId()), nextId())
        val received = InventoryPlanner.receive(empty, empty.revision, nextOperation(),
            listOf(StockReceipt(nextId(), position, quantity, cashMoney(10)))).nextSnapshot
        val requirements = RetailStockRequirements(listOf(StockAllocation(nextId(), line.portionId, position, quantity)))
        val orderId = nextId()
        val order = Orders.create(command(SecurityAction.ORDER_CREATE, orderId, Revision(0), actor = session.custodianId),
            scope, orderId, quote, requirements).value
        val collectionId = nextId()
        val beginCommand = command(SecurityAction.COLLECTION_BEGIN, order.id, order.revision, quote.payable, session.custodianId)
        val reserved = InventoryPlanner.reserve(received, received.revision, beginCommand.operationId, nextId(),
            CollectionBinding(order.id, quote.revision, collectionId, session.custodianId, CollectionExposure.UNKNOWN),
            requirements.allocations, now.value.toEpochMilliseconds())
        val begun = Collections.begin(beginCommand, order, order.revision, session, session.revision, collectionId, reserved)
        val collection = begun.value.collection
        val attemptCommand = command(SecurityAction.COLLECTION_ATTEST, collection.id, collection.revision, quote.payable, session.custodianId)
        val inventory = reserved.plan.nextSnapshot
        val reservation = checkNotNull(inventory.reservation(collection.reservationId))
        val safe = assertIs<ReservationSafetyResult.Safe>(InventoryPlanner.revalidateReservation(inventory, inventory.revision,
            attemptCommand.operationId, reservation.reservationId, reservation.revision, now.value.toEpochMilliseconds()))
        val pending = Collections.startAttempt(attemptCommand, collection, collection.revision, session, session.revision,
            nextId(), Tender.Cash, quote.payable, safe).value
        return PendingCashFixture(pending.session, begun.inventoryDependencies.single(), begun.value.order,
            pending.collection, safe.plan.nextSnapshot)
    }

    /** Actual original cash sale/entitlement and accepted return; stops BEFORE any refund outcome. */
    suspend fun pendingRefund(session: CashSession): RefundWithCash {
        val pending = pending(session)
        val collection = pending.collection
        val paymentAttempt = collection.attempts.single()
        val paid = Collections.recordOutcome(
            command(SecurityAction.COLLECTION_ATTEST, collection.id, collection.revision, paymentAttempt.amount, session.custodianId),
            collection, collection.revision, pending.session, pending.session.revision, paymentAttempt.id,
            CollectionOutcome.CashReceived(paymentAttempt.amount, cashMoney(0),
                CashierEvidence(nextId(), session.custodianId, now.value))).value
        val order = pending.order
        val finalizeCommand = command(SecurityAction.COLLECTION_FINALIZE, order.id, order.revision,
            collection.quote.payable, session.custodianId)
        val inventory = pending.inventory
        val reservation = checkNotNull(inventory.reservation(collection.reservationId))
        val issued = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(inventory, inventory.revision,
            finalizeCommand.operationId, reservation.reservationId, reservation.revision, nextId(), now.value.toEpochMilliseconds()))
        val finalized = Collections.finalizePaid(finalizeCommand, order, order.revision, paid.collection,
            paid.collection.revision, nextId(), issued).value
        val entitlement = finalized.entitlement
        val line = entitlement.sale.quote.lines.single()
        val claimId = nextId()
        val claim = Returns.recordClaim(command(SecurityAction.RETURN_CLAIM, claimId, Revision(0), actor = session.custodianId),
            entitlement, entitlement.revision, claimId, line.portionId, line.quantity,
            ReturnClaimEvidence(nextId(), Revision(1), now.value, now.value, null, "synthetic.inspected",
                CashierEvidence(nextId(), session.custodianId, now.value))).value
        val accepted = Returns.accept(command(SecurityAction.RETURN_ACCEPT, entitlement.saleId, entitlement.revision,
            entitlement.sale.quote.payable, session.custodianId), entitlement, entitlement.revision, claim, claim.revision,
            nextId(), ReturnDisposition.QUARANTINE, CashierEvidence(nextId(), session.custodianId, now.value)).value
        val originalPayment = entitlement.sale.payments.single()
        val returned = accepted.acceptedReturn
        val refund = Returns.startRefund(command(SecurityAction.REFUND_RECORD, returned.id, returned.revision,
            originalPayment.amount, session.custodianId), returned, returned.revision, paid.session, paid.session.revision,
            nextId(), originalPayment.attemptId, originalPayment.amount).value
        SalesSnapshotLinks.requireAcceptedReturn(refund.acceptedReturn, accepted.entitlement, accepted.claim)
        SalesSnapshotLinks.requireCashSessionPending(refund.session, listOf(finalized.collection),
            listOf(refund.acceptedReturn), emptyList())
        return refund
    }
}

internal data class PendingCashFixture(
    val session: CashSession,
    val inventoryDependency: InventoryCommitDependency,
    val order: OwnedOrder,
    val collection: CollectionLock,
    val inventory: InventorySnapshot,
)
