package com.ukkera.pos.database

import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.store.InventoryRetailObservedFacts
import com.ukkera.pos.inventory.store.InventoryRetailRepository
import com.ukkera.pos.inventory.store.InventoryRetailStoreFailure
import com.ukkera.pos.inventory.store.InventoryRetailStoreRejected
import com.ukkera.pos.sales.Collections
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Genuine committed setup, then PURE controls using public domain planners and synthetic
 * AuthorizationEvaluator decisions. These do not claim live transaction-bound authorization,
 * commit a proposal, construct domain proofs, or register an alternative accepting handler.
 */
class CheckoutTranslationTest {
    @Test fun beginRequiresOriginalCommandAndReservationPlanNotEqualCoordinatesOrDetachedSource() = runTest {
        val j = CheckoutJourney()
        try {
            j.install()
            val deadline = j.nowMillis + 50_000
            val target = j.create(begin = false, lotExpiry = deadline)
            val beforeDatabase = j.fixture.databaseState()
            val beforeChanges = j.fixture.totalChanges()
            j.fixture.withView { view ->
                val order = readCheckoutOrder(view, target.orderId)
                val cash = readCheckoutCash(view, j.sessionId)
                val observed = InventoryRetailRepository.observe(view).also(::requireCheckoutInventoryProducer)
                val body = CheckoutRequest.Begin(CheckoutRef(order.order.id, order.order.revision), cash.reference(),
                    target.collectionId, observed.snapshot.revision, target.reservationId, target.reservationExpiry)
                val request = j.request(body)
                val context = checkoutTranslationContext(request)
                val command = checkoutTranslationCommand(context, SecurityAction.COLLECTION_BEGIN, order.order.id,
                    order.order.revision, order.order.quote.payable, j.fixture.base.clockValue)
                fun reserve(snapshot: InventorySnapshot = observed.snapshot, operation: OperationId = request.operationId,
                    now: Long = j.nowMillis) = InventoryPlanner.reserve(snapshot, snapshot.revision, operation,
                    target.reservationId, CollectionBinding(order.order.id, order.order.quote.revision, target.collectionId,
                        TEST_ACTOR, CollectionExposure.UNKNOWN), order.order.stockRequirements.allocations, now, target.reservationExpiry)
                val original = reserve()
                val transition = Collections.begin(command, order.order, body.order.revision, cash.session,
                    body.cash.session.revision, body.collectionId, original)
                val translated = translateCheckoutBegin(command, body, order, cash, original, transition)
                assertEquals(2, translated.changes.size); assertEquals(1, translated.claims.size)
                assertCheckoutCopiedCommandRejected(command) { copy ->
                    translateCheckoutBegin(copy, body, order, cash, original, transition)
                }
                val twin = reserve()
                assertCheckoutCoordinateTwin(original.plan, twin.plan)
                assertCheckoutTranslationFailure("checkout.transition.inventory-identity") {
                    translateCheckoutBegin(command, body, order, cash, twin, transition)
                }
                val otherOperation = j.operation()
                for (wrong in listOf(reserve(operation = otherOperation), reserve(now = j.nowMillis + 1)))
                    assertCheckoutProofMismatch {
                        Collections.begin(command, order.order, body.order.revision, cash.session,
                            body.cash.session.revision, body.collectionId, wrong)
                    }
                assertCheckoutPureContribution(observed, context, original.plan, reserve(snapshot = detachedCheckoutSnapshot(observed)).plan,
                    otherOperation, j.nowMillis, deadline)
            }
            assertEquals(beforeDatabase, j.fixture.databaseState())
            assertEquals(beforeChanges, j.fixture.totalChanges())
        } finally { j.close() }
    }

    @Test fun attemptRequiresOriginalCommandAndSafetyPlanNotEqualCoordinatesOrDetachedSource() = runTest {
        val j = CheckoutJourney()
        try {
            j.install()
            val deadline = j.nowMillis + 50_000
            val target = j.create(lotExpiry = deadline)
            val sales = j.salesRef(target)
            val beforeDatabase = j.fixture.databaseState()
            val beforeChanges = j.fixture.totalChanges()
            j.fixture.withView { view ->
                val current = readCheckoutSales(view, sales)
                val observed = InventoryRetailRepository.observe(view).also(::requireCheckoutInventoryProducer)
                val reservation = observed.snapshot.reservationFor(target)
                val body = CheckoutRequest.Attempt(sales, CheckoutReservationRef(observed.snapshot.revision,
                    CheckoutRef(reservation.reservationId, reservation.revision)), j.id(), CheckoutTender.Cash,
                    checkoutMoney(100), "synthetic pure translation")
                val request = j.request(body)
                val context = checkoutTranslationContext(request)
                val command = checkoutTranslationCommand(context, SecurityAction.COLLECTION_ATTEST, current.collection.id,
                    current.collection.revision, body.amount, j.fixture.base.clockValue)
                fun revalidate(snapshot: InventorySnapshot = observed.snapshot, operation: OperationId = request.operationId,
                    now: Long = j.nowMillis) = assertIs<ReservationSafetyResult.Safe>(InventoryPlanner.revalidateReservation(
                    snapshot, snapshot.revision, operation, reservation.reservationId, reservation.revision, now))
                val original = revalidate()
                val transition = Collections.startAttempt(command, current.collection, sales.collection.revision, current.cash.session,
                    sales.cash.session.revision, body.attemptId, body.tender.toDomain(), body.amount, original, body.payerReference)
                val translated = translateCheckoutAttempt(command, body, current, original, transition)
                assertEquals(2, translated.changes.size); assertEquals(1, translated.claims.size)
                assertCheckoutCopiedCommandRejected(command) { copy -> translateCheckoutAttempt(copy, body, current, original, transition) }
                val twin = revalidate()
                assertCheckoutCoordinateTwin(original.plan, twin.plan)
                assertCheckoutTranslationFailure("checkout.transition.inventory-identity") {
                    translateCheckoutAttempt(command, body, current, twin, transition)
                }
                val otherOperation = j.operation()
                for (wrong in listOf(revalidate(operation = otherOperation), revalidate(now = j.nowMillis + 1)))
                    assertCheckoutProofMismatch {
                        Collections.startAttempt(command, current.collection, sales.collection.revision, current.cash.session,
                            sales.cash.session.revision, body.attemptId, body.tender.toDomain(), body.amount, wrong, body.payerReference)
                    }
                assertCheckoutPureContribution(observed, context, original.plan, revalidate(snapshot = detachedCheckoutSnapshot(observed)).plan,
                    otherOperation, j.nowMillis, deadline)
            }
            assertEquals(beforeDatabase, j.fixture.databaseState())
            assertEquals(beforeChanges, j.fixture.totalChanges())
        } finally { j.close() }
    }

    @Test fun finalizedSaleRequiresOriginalCommandAndIssuePlanNotEqualCoordinatesOrDetachedSource() = runTest {
        val j = CheckoutJourney()
        try {
            j.install()
            val deadline = j.nowMillis + 50_000
            val target = j.create(lotExpiry = deadline)
            j.payCash(target)
            val sales = j.salesRef(target)
            val beforeDatabase = j.fixture.databaseState()
            val beforeChanges = j.fixture.totalChanges()
            j.fixture.withView { view ->
                val current = readCheckoutSales(view, sales)
                val observed = InventoryRetailRepository.observe(view).also(::requireCheckoutInventoryProducer)
                val reservation = observed.snapshot.reservationFor(target)
                val body = CheckoutRequest.Finalize(sales, CheckoutReservationRef(observed.snapshot.revision,
                    CheckoutRef(reservation.reservationId, reservation.revision)), j.id(), j.id())
                val request = j.request(body)
                val context = checkoutTranslationContext(request)
                val command = checkoutTranslationCommand(context, SecurityAction.COLLECTION_FINALIZE, current.order.order.id,
                    current.order.order.revision, current.collection.quote.payable, j.fixture.base.clockValue)
                fun issue(snapshot: InventorySnapshot = observed.snapshot, operation: OperationId = request.operationId,
                    now: Long = j.nowMillis) = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(snapshot,
                    snapshot.revision, operation, reservation.reservationId, reservation.revision, body.issueId, now))
                val original = issue()
                val transition = Collections.finalizePaid(command, current.order.order, sales.order.revision,
                    current.collection, sales.collection.revision, body.saleId, original)
                val translated = translateCheckoutFinalized(command, body, current, original, transition)
                assertEquals(4, translated.changes.size); assertEquals(3, translated.claims.size)
                assertCheckoutCopiedCommandRejected(command) { copy -> translateCheckoutFinalized(copy, body, current, original, transition) }
                val twin = issue()
                assertCheckoutCoordinateTwin(original.plan, twin.plan)
                assertCheckoutTranslationFailure("checkout.transition.inventory-identity") {
                    translateCheckoutFinalized(command, body, current, twin, transition)
                }
                val otherOperation = j.operation()
                for (wrong in listOf(issue(operation = otherOperation), issue(now = j.nowMillis + 1)))
                    assertCheckoutProofMismatch {
                        Collections.finalizePaid(command, current.order.order, sales.order.revision,
                            current.collection, sales.collection.revision, body.saleId, wrong)
                    }
                assertCheckoutPureContribution(observed, context, original.plan, issue(snapshot = detachedCheckoutSnapshot(observed)).plan,
                    otherOperation, j.nowMillis, deadline)
            }
            assertEquals(beforeDatabase, j.fixture.databaseState())
            assertEquals(beforeChanges, j.fixture.totalChanges())
        } finally { j.close() }
    }
}

private fun checkoutTranslationContext(request: MutationRequest) = CommandContext(TEST_SCOPE, TEST_ACTOR, request.operationId,
    request.command, semanticDigest(TEST_SCOPE, TEST_ACTOR, request.command, request.payload))

/** Explicit synthetic authorization for a pure test, not the fixture's live admission callback. */
private suspend fun checkoutTranslationCommand(context: CommandContext, action: SecurityAction, aggregate: DomainId,
    revision: Revision, amount: Money, at: UtcInstant): SalesCommand {
    val principal = testPrincipal()
    val requirement = salesAuthorizationRequirement(action, TEST_SCOPE, aggregate, revision, context.operationId,
        context.semanticDigest, context.key.name, TEST_ACTOR, amount)
    val snapshot = AuthorizationSnapshot(TEST_ACTOR, PrincipalKind.LOCAL_EMPLOYEE, TEST_SCOPE, principal.authenticationId,
        principal.generation, principal.deviceId, principal.admissionRevision, Revision(1), true, true, true,
        AuthenticationPurpose.INTERACTIVE, listOf(PermissionGrant(requirement.permission, TEST_SCOPE, GrantEffect.ALLOW,
            PermissionLimits(checkoutMoney(Long.MAX_VALUE)))))
    val allowed = assertIs<AuthorizationDecision.Allowed>(AuthorizationEvaluator.evaluate(principal, snapshot, requirement))
    return SalesCommand(context.operationId, allowed.authorization, at.value, context.semanticDigest, context.key.name)
}

private fun assertCheckoutCopiedCommandRejected(command: SalesCommand, translate: (SalesCommand) -> CheckoutSalesChanges) {
    val copy = command.copy()
    assertEquals(command, copy)
    assertNotSame(command, copy)
    assertCheckoutTranslationFailure("checkout.transition.complete-effects") { translate(copy) }
}

private fun assertCheckoutTranslationFailure(message: String, action: () -> Unit) {
    assertEquals(message, assertFailsWith<IllegalStateException>(block = action).message)
}

private fun assertCheckoutProofMismatch(action: () -> Unit) {
    assertEquals(SalesRule.STOCK_PROOF_MISMATCH, assertFailsWith<SalesRuleViolation>(block = action).rule)
}

private fun assertCheckoutCoordinateTwin(original: InventoryPlan, twin: InventoryPlan) {
    assertNotSame(original, twin)
    assertSame(original.sourceSnapshot, twin.sourceSnapshot)
    assertEquals(original.operationId, twin.operationId)
    assertEquals(original.requiredInventoryRevision, twin.requiredInventoryRevision)
    assertEquals(original.mustCommitBeforeEpochMillis, twin.mustCommitBeforeEpochMillis)
    assertEquals(InventorySnapshotCodec.encode(original.nextSnapshot), InventorySnapshotCodec.encode(twin.nextSnapshot))
}

private fun detachedCheckoutSnapshot(observed: InventoryRetailObservedFacts): InventorySnapshot {
    val original = observed.snapshot
    val detached = InventorySnapshotCodec.decode(InventorySnapshotCodec.encode(original), InventorySnapshotBinding(
        InventorySnapshotKind.BRANCH_INVENTORY, original.scope, original.scope.branchId, original.revision))
    assertNotSame(original, detached)
    assertEquals(InventorySnapshotCodec.encode(original), InventorySnapshotCodec.encode(detached))
    return detached
}

private fun assertCheckoutPureContribution(observed: InventoryRetailObservedFacts, context: CommandContext, original: InventoryPlan,
    detached: InventoryPlan, otherOperation: OperationId, now: Long, deadline: Long) {
    val contribution = InventoryRetailRepository.projectContribution(observed, context, original, now)
    assertSame(observed.snapshot, original.sourceSnapshot)
    assertSame(original.nextSnapshot, contribution.snapshot)
    assertEquals(2, contribution.changes.size)
    assertEquals(deadline, original.mustCommitBeforeEpochMillis)
    assertEquals(deadline, contribution.mustCommitBeforeEpochMillis)
    assertEquals(deadline, InventoryRetailRepository.projectContribution(observed, context, original, now + 1).mustCommitBeforeEpochMillis)
    assertNotSame(observed.snapshot, detached.sourceSnapshot)
    assertEquals(original.operationId, detached.operationId)
    assertEquals(original.requiredInventoryRevision, detached.requiredInventoryRevision)
    assertEquals(InventorySnapshotCodec.encode(original.nextSnapshot), InventorySnapshotCodec.encode(detached.nextSnapshot))
    assertEquals(InventoryRetailStoreFailure.BINDING_MISMATCH, assertFailsWith<InventoryRetailStoreRejected> {
        InventoryRetailRepository.projectContribution(observed, context, detached, now)
    }.failure)
    val wrongContext = CommandContext(context.scope, context.originalActorId, otherOperation, context.key, context.semanticDigest)
    assertEquals(InventoryRetailStoreFailure.BINDING_MISMATCH, assertFailsWith<InventoryRetailStoreRejected> {
        InventoryRetailRepository.projectContribution(observed, wrongContext, original, now)
    }.failure)
}
