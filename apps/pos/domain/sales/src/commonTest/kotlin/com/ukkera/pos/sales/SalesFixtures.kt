package com.ukkera.pos.sales

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
import com.ukkera.pos.pricing.PricingCharge
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
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
import kotlinx.datetime.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

internal fun id(value: Int): DomainId = DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
internal fun money(minor: Long): Money = Money(Currency.EGP, minor)
internal fun egp(whole: Long): Money = money(whole * 100)

/** TEST-ONLY pure snapshots. This deliberately is not called an atomic/durable repository. */
internal class SalesFixture {
    val owner = id(1)
    val other = id(2)
    val safeOwner = id(3)
    val scope = BranchScope(id(10), id(11), id(12))
    val unit = id(20)
    val safe = id(21)
    val now: Instant = Instant.parse("2026-10-23T12:00:00Z")
    private var sequence = 100
    fun nextId(): DomainId = id(sequence++)
    fun nextOperation(): OperationId = OperationId(nextId().value)
    fun quantity(value: Long, scale: Int = 0): Quantity = Quantity(unit, value, scale)
    fun evidence(actor: DomainId = owner): CashierEvidence = CashierEvidence(nextId(), actor, now)
    fun external(): Tender.External = Tender.External(nextId(), Revision(3), ExternalTenderKind.CARD, true, true)
    fun externalEvidence(actor: DomainId = owner): ExternalTenderEvidence = ExternalTenderEvidence(evidence(actor), "terminal-1", "cashier-reference")

    suspend fun command(
        action: SecurityAction,
        target: DomainId,
        revision: Revision,
        amount: Money? = null,
        actor: DomainId = owner,
        branch: BranchScope = scope,
        operation: OperationId = nextOperation(),
        digest: PayloadDigest = PayloadDigest("1".repeat(64)),
        maximum: Money? = egp(100_000_000),
        generation: SessionGeneration = SessionGeneration(1),
    ): SalesCommand {
        val identity = VerifiedLocalIdentity(actor, branch, id(31), id(32), generation, Revision(2), AuthenticationPurpose.INTERACTIVE)
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity = identity
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val principal = checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(LocalGatewayCredentials(id(33), OpaqueCredential(ByteArray(32) { 7 }))))
        val requirement = salesAuthorizationRequirement(action, branch, target, revision, operation, digest, "test.actual-evidence", actor, amount)
        val snapshot = AuthorizationSnapshot(actor, principal.kind, branch, identity.sessionId, generation,
            identity.deviceId, identity.admissionRevision, Revision(4), true, true, true, identity.purpose,
            listOf(PermissionGrant(requirement.permission, branch, GrantEffect.ALLOW, PermissionLimits(maximum))))
        val authorized = assertIs<AuthorizationDecision.Allowed>(AuthorizationEvaluator.evaluate(principal, snapshot, requirement)).authorization
        return SalesCommand(operation, authorized, now, digest, "test.actual-evidence")
    }

    fun assignment(session: CashSession? = null, actor: DomainId = session?.custodianId ?: owner,
        drawer: DomainId = session?.drawerId ?: nextId(), revision: Revision = Revision(1), unresolved: DomainId? = null): CashAssignmentReadSet =
        CashAssignmentReadSet(scope, drawer, revision, session?.id, unresolved, actor, revision, session?.id)

    suspend fun openCash(opening: Money = egp(1_000), actor: DomainId = owner): CashSession {
        val assigned = assignment(actor = actor)
        val command = command(SecurityAction.CASH_OPEN, assigned.drawerId, assigned.drawerRevision, opening, actor)
        return CashSessions.open(command, assigned, assigned.drawerRevision, assigned.custodianRevision, nextId(),
            LocalDate(2026, 10, 23), CountedCash(opening, evidence(actor)), nextId()).value
    }

    fun quote(price: String = "100", quantity: Quantity = quantity(1), taxRate: String = "0",
        charges: List<PricingCharge> = emptyList(), portions: Int = 1): PricingSnapshot {
        val tax = TaxSnapshot(nextId(), Revision(1), "TEST_ONLY", PercentageRate.parse(taxRate))
        val lines = (0 until portions).map {
            ResolvedPriceLine(nextId(), nextId(), Revision(2), quantity, UnitPrice.parse(Currency.EGP, price),
                TaxInclusion.EXCLUSIVE, tax, DiscountLimits.none(Currency.EGP))
        }
        return OperationalPricingEngine.calculate(PricingRequest(nextId(), Revision(5), Currency.EGP, lines, charges))
    }

    suspend fun retail(quote: PricingSnapshot = quote(), opening: Money = egp(1_000), begin: Boolean = true): RetailFixture {
        var inventory = InventorySnapshot.empty(InventoryScope(scope.companyId, scope.branchId))
        val stock = quote.lines.map { line ->
            val position = StockPositionKey(StockPoolKey(inventory.scope, nextId(), nextId()), nextId())
            val receipt = InventoryPlanner.receive(inventory, inventory.revision, nextOperation(),
                listOf(StockReceipt(nextId(), position, line.quantity, egp(10))))
            inventory = receipt.nextSnapshot
            StockAllocation(nextId(), line.portionId, position, line.quantity)
        }
        val requirements = RetailStockRequirements(stock)
        val orderId = nextId()
        val order = Orders.create(command(SecurityAction.ORDER_CREATE, orderId, Revision(0)), scope, orderId, quote, requirements).value
        val fixture = RetailFixture(this, order, openCash(opening), inventory)
        if (begin) fixture.begin()
        return fixture
    }

    suspend fun claim(entitlement: ReceiptEntitlement, quantity: Quantity, portionId: DomainId = entitlement.cursors.first().portionId,
        actor: DomainId = other): ReturnClaim {
        val claimId = nextId()
        val data = ReturnClaimEvidence(nextId(), Revision(1), Instant.parse("2026-10-22T12:00:00Z"), now,
            Instant.parse("2026-10-30T12:00:00Z"), "defect.inspected", evidence(actor))
        return Returns.recordClaim(command(SecurityAction.RETURN_CLAIM, claimId, Revision(0), actor = actor),
            entitlement, entitlement.revision, claimId, portionId, quantity, data).value
    }

    suspend fun accept(entitlement: ReceiptEntitlement, quantity: Quantity,
        portionId: DomainId = entitlement.cursors.first().portionId, actor: DomainId = other): ReturnAccepted {
        val claim = claim(entitlement, quantity, portionId, actor)
        val amount = entitlement.cursors.single { it.portionId == portionId }.consume(quantity).payable
        return Returns.accept(command(SecurityAction.RETURN_ACCEPT, entitlement.saleId, entitlement.revision, amount, actor),
            entitlement, entitlement.revision, claim, claim.revision, nextId(), ReturnDisposition.QUARANTINE, evidence(actor)).value
    }

    suspend fun payRefund(accepted: AcceptedReturn, session: CashSession, originalPaymentId: DomainId,
        amount: Money, external: Boolean = false): RefundWithCash {
        val attemptId = nextId()
        val start = Returns.startRefund(command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, amount, session.custodianId),
            accepted, accepted.revision, session, session.revision, attemptId, originalPaymentId, amount).value
        val outcome = if (external) RefundOutcome.ExternalAttested(externalEvidence(session.custodianId)) else RefundOutcome.CashPaid(evidence(session.custodianId))
        return Returns.recordRefundOutcome(command(SecurityAction.REFUND_RECORD, accepted.id, start.acceptedReturn.revision, amount, session.custodianId),
            start.acceptedReturn, start.acceptedReturn.revision, start.session, start.session.revision, attemptId, outcome).value
    }
}

internal class RetailFixture(val data: SalesFixture, var order: OwnedOrder, var session: CashSession, var inventory: InventorySnapshot) {
    lateinit var collection: CollectionLock

    suspend fun begin() {
        val collectionId = data.nextId()
        val command = data.command(SecurityAction.COLLECTION_BEGIN, order.id, order.revision, order.quote.payable, session.custodianId)
        val reserved = InventoryPlanner.reserve(inventory, inventory.revision, command.operationId, data.nextId(),
            CollectionBinding(order.id, order.quote.revision, collectionId, order.ownerId, CollectionExposure.UNKNOWN),
            order.stockRequirements.allocations, data.now.toEpochMilliseconds())
        val begun = Collections.begin(command, order, order.revision, session, session.revision, collectionId, reserved)
        assertEquals(reserved.plan.requiredInventoryRevision, begun.inventoryDependencies.single().expectedInventoryRevision)
        order = begun.value.order; collection = begun.value.collection; inventory = reserved.plan.nextSnapshot
    }

    suspend fun start(amount: Money, tender: Tender, payer: String? = null): DomainId {
        val attemptId = data.nextId()
        val command = data.command(SecurityAction.COLLECTION_ATTEST, collection.id, collection.revision, amount, session.custodianId)
        val checked = assertIs<ReservationSafetyResult.Safe>(safety(command))
        val result = Collections.startAttempt(command, collection, collection.revision, session, session.revision, attemptId, tender, amount, checked, payer).value
        collection = result.collection; session = result.session; inventory = checked.plan.nextSnapshot
        return attemptId
    }

    fun safety(command: SalesCommand): ReservationSafetyResult {
        val reservation = checkNotNull(inventory.reservation(collection.reservationId))
        return InventoryPlanner.revalidateReservation(inventory, inventory.revision, command.operationId,
            reservation.reservationId, reservation.revision, data.now.toEpochMilliseconds())
    }

    suspend fun record(attemptId: DomainId, outcome: CollectionOutcome): SalesTransition<CollectionWithCash> {
        val attempt = collection.attempts.single { it.id == attemptId }
        val action = if (attempt.status == AttemptStatus.OUTCOME_UNKNOWN) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST
        val result = Collections.recordOutcome(data.command(action, collection.id, collection.revision, attempt.amount, session.custodianId),
            collection, collection.revision, session, session.revision, attemptId, outcome)
        collection = result.value.collection; session = result.value.session
        return result
    }

    suspend fun cash(amount: Money, received: Money = amount, change: Money = money(0)): DomainId {
        val attempt = start(amount, Tender.Cash)
        record(attempt, CollectionOutcome.CashReceived(received, change, data.evidence(session.custodianId)))
        return attempt
    }

    suspend fun external(amount: Money): DomainId {
        val attempt = start(amount, data.external())
        record(attempt, CollectionOutcome.ExternalAttested(data.externalEvidence(session.custodianId)))
        return attempt
    }

    suspend fun prepareIssue(): Pair<SalesCommand, ReservationIssueResult.Issued> {
        val action = if (order.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
        val command = data.command(action, order.id, order.revision, order.quote.payable, session.custodianId)
        val reservation = checkNotNull(inventory.reservation(collection.reservationId))
        val issue = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(inventory, inventory.revision,
            command.operationId, reservation.reservationId, reservation.revision, data.nextId(), data.now.toEpochMilliseconds()))
        return command to issue
    }

    suspend fun finalize(): SalesTransition<FinalizedSale> {
        val (command, issue) = prepareIssue()
        val result = Collections.finalizePaid(command, order, order.revision, collection, collection.revision, data.nextId(), issue)
        order = result.value.order; collection = result.value.collection; inventory = issue.plan.nextSnapshot
        return result
    }
}

internal inline fun assertRule(expected: SalesRule, block: () -> Unit) {
    assertEquals(expected, assertFailsWith<SalesRuleViolation>(block = block).rule)
}
