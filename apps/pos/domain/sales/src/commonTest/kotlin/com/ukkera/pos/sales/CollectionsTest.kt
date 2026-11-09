package com.ukkera.pos.sales

import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryEffect
import com.ukkera.pos.inventory.InventoryRejection
import com.ukkera.pos.inventory.RecipeIssueRequest
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationSafetyResult
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CollectionsTest {
    @Test fun ownerStaysUntilNamedRecipientAcceptsAndOriginalCreatorIsNotRelabelled() = runTest {
        val f = SalesFixture()
        val sale = f.retail(begin = false)
        val originalSession = sale.session
        val handoffId = f.nextId()
        val offered = Orders.offerHandoff(f.command(SecurityAction.ORDER_HANDOFF, sale.order.id, sale.order.revision), sale.order,
            sale.order.revision, handoffId, f.other, f.evidence()).value
        assertEquals(f.owner, offered.ownerId)
        assertRule(SalesRule.OWNER_REQUIRED) {
            Orders.readOwn(f.command(SecurityAction.ORDER_READ, offered.id, offered.revision, actor = f.other), offered, offered.revision)
        }
        assertRule(SalesRule.HANDOFF_CONFLICT) {
            Orders.acceptHandoff(f.command(SecurityAction.ORDER_HANDOFF, offered.id, offered.revision), offered, offered.revision, handoffId, f.evidence())
        }
        val accepted = Orders.acceptHandoff(f.command(SecurityAction.ORDER_HANDOFF, offered.id, offered.revision, actor = f.other), offered,
            offered.revision, handoffId, f.evidence(f.other))
        assertEquals(f.other, accepted.value.ownerId)
        assertEquals(f.owner, accepted.value.originalCreatorId)
        assertEquals(SalesUniqueKind.HANDOFF_ACCEPTANCE, accepted.unique.single().kind)
        assertRule(SalesRule.REVISION_CONFLICT) {
            Orders.acceptHandoff(f.command(SecurityAction.ORDER_HANDOFF, accepted.value.id, accepted.value.revision, actor = f.other),
                accepted.value, offered.revision, handoffId, f.evidence(f.other))
        }
        assertRule(SalesRule.OWNER_REQUIRED) {
            Orders.readOwn(f.command(SecurityAction.ORDER_READ, accepted.value.id, accepted.value.revision), accepted.value, accepted.value.revision)
        }
        sale.order = accepted.value
        sale.session = f.openCash(actor = f.other)
        sale.begin(); sale.cash(egp(100))
        val paid = sale.finalize().value.sale
        assertEquals(f.owner, paid.originalCreatorId)
        assertEquals(f.other, paid.collectorId)
        assertEquals(f.other, paid.payments.single().collectorId)
        assertEquals(originalSession.opening.amount, originalSession.expectedCash)
        assertTrue(originalSession.pendingAttemptIds.isEmpty())
    }

    @Test fun everyQuotedPortionAndExactQuantityMustHaveReservedStock() = runTest {
        val f = SalesFixture()
        val sale = f.retail(f.quote(portions = 2), begin = false)
        val missing = RetailStockRequirements(sale.order.stockRequirements.allocations.take(1))
        assertRule(SalesRule.STOCK_PROOF_MISMATCH) {
            Orders.replaceUnlockedQuote(f.command(SecurityAction.ORDER_EDIT, sale.order.id, sale.order.revision), sale.order,
                sale.order.revision, sale.order.quote, missing)
        }
        assertRule(SalesRule.STOCK_PROOF_MISMATCH) { RetailStockRequirements(emptyList()) }
        val original = sale.order.stockRequirements.allocations
        val wrongUnit = original.first().copy(quantity = original.first().quantity.copy(unitId = f.nextId()))
        assertRule(SalesRule.UNSUPPORTED_STOCK_TIMING) {
            Orders.replaceUnlockedQuote(f.command(SecurityAction.ORDER_EDIT, sale.order.id, sale.order.revision), sale.order,
                sale.order.revision, sale.order.quote, RetailStockRequirements(listOf(wrongUnit) + original.drop(1)))
        }
        assertEquals(2, sale.order.stockRequirements.allocations.size)
        assertTrue(sale.inventory.reservations.isEmpty())
    }

    @Test fun unknownCollectionRetainsQuoteActorPayerSessionAndBlocksRetryEditHandoffClose() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        val quote = sale.collection.quote
        val attemptId = sale.start(egp(100), f.external(), "original-payer-reference")
        val started = sale.collection.attempts.single()
        sale.record(attemptId, CollectionOutcome.Unknown(f.evidence()))
        assertEquals(CollectionStatus.OUTCOME_UNKNOWN, sale.collection.status)
        assertSame(quote, sale.collection.quote)
        assertEquals(egp(1_000), sale.session.expectedCash)
        assertEquals(listOf(attemptId), sale.session.pendingAttemptIds)
        assertRule(SalesRule.PAYMENT_UNRESOLVED) { sale.start(egp(100), f.external()) }
        assertRule(SalesRule.PAYABLE_LOCKED) {
            Orders.replaceUnlockedQuote(f.command(SecurityAction.ORDER_EDIT, sale.order.id, sale.order.revision), sale.order,
                sale.order.revision, f.quote("50"), sale.order.stockRequirements)
        }
        assertRule(SalesRule.PAYABLE_LOCKED) {
            Orders.offerHandoff(f.command(SecurityAction.ORDER_HANDOFF, sale.order.id, sale.order.revision), sale.order,
                sale.order.revision, f.nextId(), f.other, f.evidence())
        }
        val work = ClosingWorkReadSet(f.scope, f.owner, Revision(1), emptyList(), emptyList())
        assertRule(SalesRule.PAYMENT_UNRESOLVED) {
            CashSessions.beginClosing(f.command(SecurityAction.CASH_CLOSE, sale.session.id, sale.session.revision),
                sale.session, sale.session.revision, work, work.revision)
        }
        assertRule(SalesRule.OWNER_REQUIRED) {
            Collections.recordOutcome(f.command(SecurityAction.COLLECTION_RESOLVE, sale.collection.id, sale.collection.revision, egp(100), f.other),
                sale.collection, sale.collection.revision, sale.session, sale.session.revision, attemptId,
                CollectionOutcome.ExternalAttested(f.externalEvidence(f.other)))
        }
        // A new authenticated login of the SAME actor does not create another charge or custody.
        val resolution = Collections.recordOutcome(f.command(SecurityAction.COLLECTION_RESOLVE, sale.collection.id, sale.collection.revision,
            egp(100), generation = SessionGeneration(2)), sale.collection, sale.collection.revision, sale.session,
            sale.session.revision, attemptId, CollectionOutcome.ExternalAttested(f.externalEvidence()))
        val payment = assertNotNull(resolution.value.collection.attempts.single().payment)
        assertEquals(started.originalOperationId, payment.originalOperationId)
        assertEquals(started.originalActorId, payment.collectorId)
        assertEquals(started.originalCashSessionId, payment.cashSessionId)
        assertEquals("original-payer-reference", payment.payerReference)
        assertEquals(egp(100), resolution.value.collection.collected)
        assertEquals(egp(1_000), resolution.value.session.expectedCash)
        assertEquals(1, resolution.effects.filterIsInstance<PaymentRecord>().size)
        assertTrue(resolution.effects.none { it is CashMovement })
    }

    @Test fun splitAndPartialTenderCoverageIsExactAndFinalizationNeverCreatesPayment() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        sale.cash(egp(40), egp(50), egp(10))
        assertEquals(CollectionStatus.PART_PAID, sale.collection.status)
        assertEquals(egp(60), sale.collection.remainingPayable)
        val inventoryBefore = sale.inventory
        val (command, plannedIssue) = sale.prepareIssue()
        assertRule(SalesRule.INSUFFICIENT_COVERAGE) {
            Collections.finalizePaid(command, sale.order, sale.order.revision, sale.collection, sale.collection.revision, f.nextId(), plannedIssue)
        }
        assertSame(inventoryBefore, sale.inventory) // Plans do not mutate the source cut.
        sale.external(egp(60))
        assertEquals(egp(1_040), sale.session.expectedCash)
        assertEquals(CollectionStatus.COVERED, sale.collection.status)
        val finalized = sale.finalize()
        assertEquals(egp(100), finalized.value.sale.payments.fold(money(0)) { sum, payment -> sum + payment.amount })
        assertEquals(2, finalized.value.sale.payments.size)
        assertTrue(finalized.effects.none { it is CashMovement || it is PaymentRecord })
        assertEquals(1, finalized.inventoryDependencies.size)
        assertEquals(SalesUniqueKind.RETURN_ENTITLEMENT, finalized.unique.last().kind)
        assertEquals(0L, sale.inventory.positions.single().onHand.scaled)
        val shares = finalized.value.sale.originalTenderAllocations.flatMap { it.shares }
        for (payment in finalized.value.sale.payments) {
            assertEquals(payment.amount, shares.filter { it.paymentId == payment.attemptId }.fold(money(0)) { sum, share -> sum + share.amount })
        }
        assertFailsWith<InventoryRejection> {
            InventoryPlanner.issueReservation(sale.inventory, sale.inventory.revision, f.nextOperation(), sale.collection.reservationId,
                checkNotNull(sale.inventory.reservation(sale.collection.reservationId)).revision, f.nextId(), f.now.toEpochMilliseconds())
        }
    }

    @Test fun externalTenderCannotReceiveCashOrGiveCardChangeAndRequiresConfiguredEvidence() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        assertRule(SalesRule.INVALID_AMOUNT) { sale.start(egp(101), f.external()) }
        val attempt = sale.start(egp(100), f.external())
        assertRule(SalesRule.TENDER_MISMATCH) { sale.record(attempt, CollectionOutcome.CashReceived(egp(110), egp(10), f.evidence())) }
        assertRule(SalesRule.REFERENCE_REQUIRED) {
            sale.record(attempt, CollectionOutcome.ExternalAttested(ExternalTenderEvidence(f.evidence(), "terminal", null)))
        }
        assertEquals(money(0), sale.collection.collected)
        assertEquals(AttemptStatus.AWAITING_ATTESTATION, sale.collection.attempts.single().status)
        sale.record(attempt, CollectionOutcome.ExternalAttested(f.externalEvidence()))
        assertEquals(egp(1_000), sale.session.expectedCash)
        assertEquals(egp(100), sale.session.totals.externalCollections)
        assertRule(SalesRule.IMMUTABLE_RECORD) { sale.record(attempt, CollectionOutcome.ExternalAttested(f.externalEvidence())) }
    }

    @Test fun cashMustReconcileReceivedAndActualChangeToLockedAttemptAmount() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        val attempt = sale.start(egp(100), Tender.Cash)
        assertRule(SalesRule.INVALID_AMOUNT) { sale.record(attempt, CollectionOutcome.CashReceived(egp(110), money(0), f.evidence())) }
        assertRule(SalesRule.INVALID_AMOUNT) { sale.record(attempt, CollectionOutcome.CashReceived(egp(90), money(0), f.evidence())) }
        assertRule(SalesRule.INVALID_AMOUNT) { sale.record(attempt, CollectionOutcome.CashReceived(egp(100), egp(-1), f.evidence())) }
        assertEquals(egp(1_000), sale.session.expectedCash)
        val result = sale.record(attempt, CollectionOutcome.CashReceived(egp(120), egp(20), f.evidence()))
        assertEquals(egp(1_100), result.value.session.expectedCash)
        assertEquals(egp(100), result.effects.filterIsInstance<CashMovement>().single().delta)
        assertEquals(egp(20), result.effects.filterIsInstance<CashMovement>().single().changeGiven)
    }

    @Test fun stockProofMustBindExactOperationTimeQuoteAndRemainARequiredAtomicDependency() = runTest {
        val f = SalesFixture()
        val sale = f.retail(begin = false)
        val collectionId = f.nextId()
        val first = f.command(SecurityAction.COLLECTION_BEGIN, sale.order.id, sale.order.revision, egp(100))
        val reservation = InventoryPlanner.reserve(sale.inventory, sale.inventory.revision, first.operationId, f.nextId(),
            CollectionBinding(sale.order.id, sale.order.quote.revision, collectionId, f.owner, CollectionExposure.UNKNOWN),
            sale.order.stockRequirements.allocations, f.now.toEpochMilliseconds())
        assertRule(SalesRule.STOCK_PROOF_MISMATCH) {
            Collections.begin(f.command(SecurityAction.COLLECTION_BEGIN, sale.order.id, sale.order.revision, egp(100)), sale.order,
                sale.order.revision, sale.session, sale.session.revision, collectionId, reservation)
        }
        val proposal = Collections.begin(first, sale.order, sale.order.revision, sale.session, sale.session.revision, collectionId, reservation)
        assertEquals(sale.inventory.revision, proposal.inventoryDependencies.single().expectedInventoryRevision)
        assertEquals(first.operationId, proposal.inventoryDependencies.single().operationId)
        assertTrue(sale.inventory.reservations.isEmpty())
        assertTrue(proposal.effects.none { it is PaymentRecord || it is CashMovement })
        assertFailsWith<InventoryRejection> { InventoryPlanner.requireCurrent(reservation.plan, reservation.plan.nextSnapshot) }
    }

    @Test fun zeroPayableRequiresDistinctExplicitAuthorityAndStillConsumesRealStock() = runTest {
        val f = SalesFixture()
        val sale = f.retail(f.quote("0"))
        val (authorized, issue) = sale.prepareIssue()
        val ordinary = f.command(SecurityAction.COLLECTION_FINALIZE, sale.order.id, sale.order.revision, money(0), operation = authorized.operationId)
        assertRule(SalesRule.PERMISSION_DENIED) {
            Collections.finalizePaid(ordinary, sale.order, sale.order.revision, sale.collection, sale.collection.revision, f.nextId(), issue)
        }
        val result = Collections.finalizePaid(authorized, sale.order, sale.order.revision, sale.collection, sale.collection.revision, f.nextId(), issue)
        assertEquals(money(0), result.value.sale.quote.payable)
        assertTrue(result.value.sale.payments.isEmpty())
        assertTrue(result.effects.none { it is PaymentRecord || it is CashMovement })
        assertEquals(0L, issue.plan.nextSnapshot.positions.single().onHand.scaled)
    }

    @Test fun retailFinalizationRejectsKitchenIssueProofEvenForMatchingQuantities() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        sale.cash(egp(100))
        val command = f.command(SecurityAction.COLLECTION_FINALIZE, sale.order.id, sale.order.revision, egp(100))
        val reservation = checkNotNull(sale.inventory.reservation(sale.collection.reservationId))
        val issue = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(sale.inventory, sale.inventory.revision,
            command.operationId, reservation.reservationId, reservation.revision, f.nextId(), f.now.toEpochMilliseconds(),
            ValueIssuePurpose.DIRECT_RECIPE, RecipeIssueRequest(f.nextId(), listOf(f.nextId()))))
        assertRule(SalesRule.UNSUPPORTED_STOCK_TIMING) {
            Collections.finalizePaid(command, sale.order, sale.order.revision, sale.collection, sale.collection.revision, f.nextId(), issue)
        }
        assertEquals(egp(100), sale.collection.collected)
        assertEquals(1L, sale.inventory.positions.single().onHand.scaled)
    }

    @Test fun staleOrWrongDigestAuthorityCannotBeReusedAndNotPaidAttemptsAreBounded() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        val command = f.command(SecurityAction.COLLECTION_ATTEST, sale.collection.id, sale.collection.revision, egp(100))
        val safety = assertIs<ReservationSafetyResult.Safe>(sale.safety(command))
        assertRule(SalesRule.PERMISSION_DENIED) {
            Collections.startAttempt(command.copy(payloadDigest = PayloadDigest("2".repeat(64))), sale.collection, sale.collection.revision,
                sale.session, sale.session.revision, f.nextId(), Tender.Cash, egp(100), safety)
        }
        repeat(64) {
            val attempt = sale.start(egp(100), Tender.Cash)
            sale.record(attempt, CollectionOutcome.NotPaid(f.evidence(), "customer did not hand over cash"))
        }
        assertRule(SalesRule.PAYLOAD_TOO_LARGE) { sale.start(egp(100), Tender.Cash) }
        assertEquals(64, sale.collection.attempts.size)
        assertEquals(money(0), sale.collection.collected)
        assertEquals(egp(1_000), sale.session.expectedCash)
    }

    @Test fun recallRejectsFreshCollectionAdmissionButNeverErasesAlreadyReceivedMoney() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        val pending = sale.start(egp(40), Tender.Cash)
        val key = sale.inventory.positions.single().key
        sale.inventory = InventoryPlanner.recallLot(sale.inventory, sale.inventory.revision, f.nextOperation(), key.pool.skuId,
            checkNotNull(key.lotId)).nextSnapshot
        val actual = sale.record(pending, CollectionOutcome.CashReceived(egp(40), money(0), f.evidence()))
        assertEquals(egp(40), actual.value.collection.collected)
        assertEquals(egp(1_040), actual.value.session.expectedCash)
        assertEquals(CollectionStatus.PART_PAID, actual.value.collection.status)
        val next = f.command(SecurityAction.COLLECTION_ATTEST, sale.collection.id, sale.collection.revision, egp(60))
        assertIs<ReservationSafetyResult.Held>(sale.safety(next))
        assertEquals(1, sale.collection.attempts.size)
        assertEquals(egp(60), sale.collection.remainingPayable)
        assertEquals(1L, sale.inventory.positions.single().onHand.scaled)
    }

    @Test fun staleAttemptSafetyAndSameTupleNonIssuePlanCannotSatisfyCompanionDependency() = runTest {
        val f = SalesFixture()
        val sale = f.retail()
        val start = f.command(SecurityAction.COLLECTION_ATTEST, sale.collection.id, sale.collection.revision, egp(100))
        val safe = assertIs<ReservationSafetyResult.Safe>(sale.safety(start))
        val attempt = Collections.startAttempt(start, sale.collection, sale.collection.revision, sale.session,
            sale.session.revision, f.nextId(), Tender.Cash, egp(100), safe)
        assertSame(safe.plan, attempt.inventoryDependencies.single().plan)
        assertEquals(InventoryCommitKind.REVALIDATE, attempt.inventoryDependencies.single().kind)
        val key = sale.inventory.positions.single().key
        val recalled = InventoryPlanner.recallLot(sale.inventory, sale.inventory.revision, f.nextOperation(), key.pool.skuId,
            checkNotNull(key.lotId)).nextSnapshot
        assertFailsWith<InventoryRejection> { InventoryPlanner.requireCurrent(attempt.inventoryDependencies.single().plan, recalled) }
        assertTrue(sale.collection.attempts.isEmpty()) // Neither proposal nor failed CAS committed.

        // A separate clean fixture: identical tuple is insufficient; the exact issue plan is retained.
        val clean = f.retail()
        clean.cash(egp(100))
        val (finalizeCommand, issued) = clean.prepareIssue()
        val merelySafe = assertIs<ReservationSafetyResult.Safe>(clean.safety(finalizeCommand))
        assertEquals(merelySafe.plan.operationId, issued.plan.operationId)
        assertEquals(merelySafe.plan.requiredInventoryRevision, issued.plan.requiredInventoryRevision)
        val final = Collections.finalizePaid(finalizeCommand, clean.order, clean.order.revision, clean.collection,
            clean.collection.revision, f.nextId(), issued)
        val dependency = final.inventoryDependencies.single()
        assertSame(issued.plan, dependency.plan)
        assertEquals(InventoryCommitKind.RETAIL_ISSUE, dependency.kind)
        assertEquals(issued.proof.issueId, dependency.issueId)
        assertTrue(dependency.plan.effects.any { it is InventoryEffect.IssueRecorded })
        assertTrue(merelySafe.plan.effects.none { it is InventoryEffect.IssueRecorded })
    }
}
