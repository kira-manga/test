package com.ukkera.pos.sales

import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure protected-snapshot tests; not a production store, migration or crash/restart proof. */
class SalesSnapshotCodecTest {
    @Test fun cashRoundTripKeepsValuesBeyondBinary64AndCanonicalTimeScalars() = runTest {
        val f = SalesFixture()
        val assigned = f.assignment()
        val opening = money(9_007_199_254_740_993L)
        val session = CashSessions.open(f.command(SecurityAction.CASH_OPEN, assigned.drawerId, assigned.drawerRevision,
            opening, maximum = opening), assigned, assigned.drawerRevision, assigned.custodianRevision, f.nextId(),
            LocalDate(2026, 10, 23), CountedCash(opening, f.evidence()), f.nextId()).value
        val encoded = SalesSnapshotCodec.encodeCashSession(session)
        val restored = SalesSnapshotCodec.decodeCashSession(encoded, session.binding())
        assertEquals(opening, restored.expectedCash)
        assertEquals(session.opening, restored.opening)
        assertEquals(encoded, SalesSnapshotCodec.encodeCashSession(restored))
        assertTrue("\"minor\":\"9007199254740993\"" in encoded)
        assertTrue("\"revision\":\"0\"" in encoded)
        assertTrue("\"businessDate\":\"2026-10-23\"" in encoded)
        assertTrue("2026-10-23T12:00:00Z" in encoded)
        assertFalse("authorization" in encoded || "payloadDigest" in encoded)
    }

    @Test fun reviewedClosingAndActualSafeDispositionHaveOneOriginalReportAfterRoundTrip() = runTest {
        val f = SalesFixture()
        var cash = f.openCash(egp(100))
        val work = ClosingWorkReadSet(f.scope, f.owner, Revision(1), emptyList(), emptyList())
        cash = CashSessions.beginClosing(f.command(SecurityAction.CASH_CLOSE, cash.id, cash.revision), cash,
            cash.revision, work, work.revision).value
        val closed = CashSessions.submitClose(f.command(SecurityAction.CASH_CLOSE, cash.id, cash.revision, egp(110)),
            cash, cash.revision, f.assignment(cash), CountedCash(egp(110), f.evidence()), egp(10), f.nextId(), f.safe,
            "actual counted overage", f.evidence()).value
        cash = CashSessions.review(f.command(SecurityAction.CASH_REVIEW, closed.session.id, closed.session.revision, actor = f.other),
            closed.session, closed.session.revision, f.evidence(f.other), "review preserves submitted count").value
        val encoded = SalesSnapshotCodec.encodeCashSession(cash)
        val restored = SalesSnapshotCodec.decodeCashSession(encoded, cash.binding())
        assertEquals(cash.closeReport, restored.closeReport)
        assertSame(restored.closeReport, restored.review?.originalReport)
        assertEquals(egp(10), restored.closeReport?.variance)
        val transfer = assertNotNull(closed.transfer)
        val storedTransfer = SalesSnapshotCodec.encodeSafeTransfer(transfer)
        val restoredTransfer = SalesSnapshotCodec.decodeSafeTransfer(storedTransfer, transfer.binding())
        SalesSnapshotLinks.requireSafeTransfer(restoredTransfer, restored)
        assertEquals(egp(100), restoredTransfer.amount)
        assertEquals(storedTransfer, SalesSnapshotCodec.encodeSafeTransfer(restoredTransfer))
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive("999"),
            "state", "closeReport", "variance", "minor"), cash.binding()) }
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive("OPEN"), "state", "status"), cash.binding()) }
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive("99"),
            "state", "closingCut", "cashLedgerRevision"), cash.binding()) }
    }

    @Test fun unknownSchemaFieldsDuplicateKeysAlternateEncodingAndForeignBindingsFailClosed() = runTest {
        val f = SalesFixture()
        val session = f.openCash()
        val encoded = SalesSnapshotCodec.encodeCashSession(session)
        val expected = session.binding()
        assertFailure(SalesSnapshotFailure.UNSUPPORTED_VERSION) {
            SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive(2), "schemaVersion"), expected)
        }
        val unknown = JsonObject(Json.parseToJsonElement(encoded).jsonObject + ("unexpected" to JsonPrimitive(true))).toString()
        assertRejected { SalesSnapshotCodec.decodeCashSession(unknown, expected) }
        assertRejected { SalesSnapshotCodec.decodeCashSession(encoded.replaceFirst("{", "{\"schemaVersion\":1,"), expected) }
        assertFailure(SalesSnapshotFailure.NON_CANONICAL) { SalesSnapshotCodec.decodeCashSession(" $encoded", expected) }
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive(100000), "state", "opening", "amount", "minor"), expected) }
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive("2026-10-23T12:00:00+00:00"),
            "state", "opening", "evidence", "occurredAt"), expected) }
        for (wrong in listOf(expected.copy(id = f.nextId()), expected.copy(revision = Revision(1)),
            expected.copy(scope = f.scope.copy(branchId = f.nextId())), expected.copy(kind = SalesSnapshotKind.ORDER))) {
            assertFailure(SalesSnapshotFailure.BINDING_MISMATCH) { SalesSnapshotCodec.decodeCashSession(encoded, wrong) }
        }
    }

    @Test fun cashArithmeticAndBoundsAreCheckedBeforeUse() = runTest {
        val session = SalesFixture().openCash()
        val encoded = SalesSnapshotCodec.encodeCashSession(session)
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive("1"), "state", "expectedCash", "minor"), session.binding()) }
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive("1"), "state", "reservedCashOut", "minor"), session.binding()) }
        assertRejected { SalesSnapshotCodec.decodeCashSession(mutate(encoded, JsonPrimitive("FUTURE_STATUS"), "state", "status"), session.binding()) }
        assertFailure(SalesSnapshotFailure.TOO_LARGE) {
            SalesSnapshotCodec.decodeCashSession(" ".repeat(SalesSnapshotCodec.MAX_ENCODED_BYTES + 1), session.binding())
        }
        assertFailure(SalesSnapshotFailure.TOO_LARGE) {
            SalesSnapshotCodec.decodeCashSession("[".repeat(49) + "]".repeat(49), session.binding())
        }
        assertFailure(SalesSnapshotFailure.TOO_LARGE) {
            val tooManyIds = JsonArray(List(1_025) { JsonPrimitive(id(it).value) })
            SalesSnapshotCodec.decodeCashSession(mutate(encoded, tooManyIds, "state", "pendingAttemptIds"), session.binding())
        }
    }

    @Test fun safeReceiptPreservesDifferentActualCustodianAndRejectsForgedStatus() = runTest {
        val f = SalesFixture()
        val cash = f.openCash(egp(100))
        val sent = CashSessions.dropToSafe(f.command(SecurityAction.CASH_TRANSFER, cash.id, cash.revision, egp(20)), cash,
            cash.revision, f.nextId(), f.safe, egp(20), f.evidence()).value
        val received = CashSessions.receiveSafeTransfer(f.command(SecurityAction.CASH_RECEIVE, sent.transfer.id,
            sent.transfer.revision, egp(20), f.safeOwner), sent.transfer, sent.transfer.revision, sent.session, sent.session.revision,
            SafeCustodyReadSet(f.scope, f.safe, f.safeOwner, Revision(1)), f.evidence(f.safeOwner)).value
        val encoded = SalesSnapshotCodec.encodeSafeTransfer(received.transfer)
        val restored = SalesSnapshotCodec.decodeSafeTransfer(encoded, received.transfer.binding())
        assertEquals(received.transfer.receipt, restored.receipt)
        SalesSnapshotLinks.requireSafeTransfer(restored, received.session)
        assertRejected { SalesSnapshotCodec.decodeSafeTransfer(mutate(encoded, JsonPrimitive(f.owner.value), "state", "receipt", "actorId"), restored.binding()) }
        assertRejected { SalesSnapshotCodec.decodeSafeTransfer(mutate(encoded, JsonPrimitive("AWAITING_RECEIPT"), "state", "status"), restored.binding()) }
        assertRejected { SalesSnapshotLinks.requireSafeTransfer(restored, sent.session) }
    }

    @Test fun completePendingSafeTransfersCannotTogetherExceedCashActuallyDebited() = runTest {
        val f = SalesFixture()
        val cash = f.openCash(egp(500))
        val first = CashSessions.dropToSafe(f.command(SecurityAction.CASH_TRANSFER, cash.id, cash.revision, egp(100)),
            cash, cash.revision, f.nextId(), f.safe, egp(100), f.evidence()).value
        val second = CashSessions.dropToSafe(f.command(SecurityAction.CASH_TRANSFER, first.session.id, first.session.revision, egp(100)),
            first.session, first.session.revision, f.nextId(), f.safe, egp(100), f.evidence()).value
        val transfers = listOf(first.transfer, second.transfer)
        SalesSnapshotLinks.requireCashSessionPending(second.session, emptyList(), emptyList(), transfers)
        // Keep isolated cash arithmetic coherent, but erase half of the transfer debit.
        val altered = mutate(mutate(SalesSnapshotCodec.encodeCashSession(second.session), JsonPrimitive("10000"),
            "state", "totals", "safeDrops", "minor"), JsonPrimitive("40000"), "state", "expectedCash", "minor")
        val restored = SalesSnapshotCodec.decodeCashSession(altered, second.session.binding())
        transfers.forEach { SalesSnapshotLinks.requireSafeTransfer(it, restored) } // Each100 individually fits100.
        assertRejected { SalesSnapshotLinks.requireCashSessionPending(restored, emptyList(), emptyList(), transfers) } // Together200 does not.
    }

    @Test fun handoffOrderKeepsProtectedQuoteAndOriginalCreatorAndRejectsBrokenLinks() = runTest {
        val f = SalesFixture()
        val retail = f.retail(begin = false)
        val order = retail.order
        val offered = Orders.offerHandoff(f.command(SecurityAction.ORDER_HANDOFF, order.id, order.revision), order, order.revision,
            f.nextId(), f.other, f.evidence()).value
        val encoded = SalesSnapshotCodec.encodeOrder(offered)
        val restored = SalesSnapshotCodec.decodeOrder(encoded, offered.binding())
        assertEquals(offered.handoff, restored.handoff)
        assertEquals(f.owner, restored.originalCreatorId)
        assertEquals(StoredPricingSnapshotCodec.encode(order.quote), StoredPricingSnapshotCodec.encode(restored.quote))
        assertEquals(encoded, SalesSnapshotCodec.encodeOrder(restored))
        assertRejected { SalesSnapshotCodec.decodeOrder(mutate(encoded, JsonPrimitive(f.other.value), "state", "ownerId"), offered.binding()) }
        assertRejected { SalesSnapshotCodec.decodeOrder(mutate(encoded, JsonPrimitive("2"),
            "state", "stockRequirements", "0", "quantity", "scaled"), offered.binding()) }
        val accepted = Orders.acceptHandoff(f.command(SecurityAction.ORDER_HANDOFF, restored.id, restored.revision, actor = f.other),
            restored, restored.revision, assertNotNull(restored.handoff).id, f.evidence(f.other)).value
        assertEquals(f.owner, accepted.originalCreatorId)
        assertEquals(f.other, accepted.ownerId)
    }

    @Test fun partialAndUnknownCollectionCanResumeOriginalIntentAfterHydration() = runTest {
        val f = SalesFixture()
        val retail = f.retail()
        retail.cash(egp(40))
        val attemptId = retail.start(egp(60), f.external(), "original payer evidence")
        retail.record(attemptId, CollectionOutcome.Unknown(f.evidence()))
        val original = retail.collection.attempts.last()
        val encoded = SalesSnapshotCodec.encodeCollection(retail.collection)
        retail.order = SalesSnapshotCodec.decodeOrder(SalesSnapshotCodec.encodeOrder(retail.order), retail.order.binding())
        retail.session = SalesSnapshotCodec.decodeCashSession(SalesSnapshotCodec.encodeCashSession(retail.session), retail.session.binding())
        retail.collection = SalesSnapshotCodec.decodeCollection(encoded, retail.collection.binding(), retail.order, retail.session)
        val restored = retail.collection.attempts.last()
        assertEquals(original.originalOperationId, restored.originalOperationId)
        assertEquals(original.originalActorId, restored.originalActorId)
        assertEquals(original.originalCashSessionId, restored.originalCashSessionId)
        assertEquals("original payer evidence", restored.payerReference)
        assertEquals(egp(40), retail.collection.collected)
        assertEquals(egp(60), retail.collection.remainingPayable)
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(retail.collection))
        assertFalse("InventoryPlan" in encoded || "AuthorizedAction" in encoded || "mustCommitBefore" in encoded)
        SalesSnapshotLinks.requireCashSessionPending(retail.session, listOf(retail.collection), emptyList(), emptyList())
        retail.record(attemptId, CollectionOutcome.ExternalAttested(f.externalEvidence()))
        val finalized = retail.finalize().value
        val finalEncoded = SalesSnapshotCodec.encodeCollection(finalized.collection)
        val decoded = SalesSnapshotCodec.decodeCollection(finalEncoded, finalized.collection.binding(), finalized.order, retail.session)
        assertEquals(CollectionStatus.FINALIZED_PAID, decoded.status)
        assertEquals(egp(100), decoded.collected)
    }

    @Test fun collectionPaymentTamperingAndIncompleteCashIndexCannotCreateCoverage() = runTest {
        val f = SalesFixture()
        val retail = f.retail()
        retail.cash(egp(40))
        val encoded = SalesSnapshotCodec.encodeCollection(retail.collection)
        assertRejected { SalesSnapshotCodec.decodeCollection(mutate(encoded, JsonPrimitive("4001"),
            "state", "attempts", "0", "payment", "amount", "minor"), retail.collection.binding(), retail.order, retail.session) }
        assertRejected { SalesSnapshotCodec.decodeCollection(mutate(encoded, JsonPrimitive("NOT_PAID"),
            "state", "attempts", "0", "status"), retail.collection.binding(), retail.order, retail.session) }
        assertRejected { SalesSnapshotCodec.decodeCollection(mutate(encoded, JsonPrimitive(f.other.value),
            "state", "collectorId"), retail.collection.binding(), retail.order, retail.session) }
        retail.start(egp(60), f.external())
        assertRejected { SalesSnapshotLinks.requireCashSessionPending(retail.session, emptyList(), emptyList(), emptyList()) }
        val cashBytes = mutate(SalesSnapshotCodec.encodeCashSession(retail.session), JsonPrimitive("1"), "state", "reservedCashOut", "minor")
        val incorrectlyReserved = SalesSnapshotCodec.decodeCashSession(cashBytes, retail.session.binding())
        assertRejected { SalesSnapshotLinks.requireCashSessionPending(incorrectlyReserved, listOf(retail.collection), emptyList(), emptyList()) }
    }

    @Test fun zeroPayableReceiptAndZeroValuedReturnRemainValidWithoutInventedPayments() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("0"))
        val sale = source.finalize().value
        val encoded = SalesSnapshotCodec.encodeReceiptEntitlement(sale.entitlement)
        val entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(encoded, sale.entitlement.binding())
        assertTrue(entitlement.sale.payments.isEmpty())
        val accepted = f.accept(entitlement, f.quantity(1))
        val returned = SalesSnapshotCodec.decodeAcceptedReturn(
            SalesSnapshotCodec.encodeAcceptedReturn(accepted.acceptedReturn, accepted.entitlement, accepted.claim),
            accepted.acceptedReturn.binding(), accepted.entitlement, accepted.claim)
        assertEquals(RefundStatus.SETTLED, returned.refundStatus)
        assertTrue(returned.tenderObligations.isEmpty() && returned.attempts.isEmpty())
        SalesSnapshotLinks.requireReceiptHistory(accepted.entitlement, listOf(returned), listOf(accepted.claim))
    }

    @Test fun repeatedPartialReturnSnapshotsResumeOriginalRoundingAndTenderRemainders() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("0.33333333", f.quantity(3)))
        val cash = source.cash(money(40))
        val external = source.external(money(60))
        var entitlement = source.finalize().value.entitlement
        val originalPricing = StoredPricingSnapshotCodec.encode(entitlement.sale.quote)
        val accepted = mutableListOf<AcceptedReturn>()
        val claims = mutableListOf<ReturnClaim>()
        repeat(3) { index ->
            val result = f.accept(entitlement, f.quantity(1))
            val bytes = SalesSnapshotCodec.encodeReceiptEntitlement(result.entitlement)
            entitlement = SalesSnapshotCodec.decodeReceiptEntitlement(bytes, result.entitlement.binding())
            assertEquals(bytes, SalesSnapshotCodec.encodeReceiptEntitlement(entitlement))
            val claimBytes = SalesSnapshotCodec.encodeReturnClaim(result.claim)
            val claim = SalesSnapshotCodec.decodeReturnClaim(claimBytes, result.claim.binding(), entitlement)
            val returnBytes = SalesSnapshotCodec.encodeAcceptedReturn(result.acceptedReturn, entitlement, claim)
            val returned = SalesSnapshotCodec.decodeAcceptedReturn(returnBytes, result.acceptedReturn.binding(), entitlement, claim)
            assertEquals(returnBytes, SalesSnapshotCodec.encodeAcceptedReturn(returned, entitlement, claim))
            assertEquals(index.toLong(), returned.consumedQuantityBefore.scaled)
            accepted += returned; claims += claim
        }
        assertEquals(listOf(33L, 33L, 34L), accepted.map { it.originalPayable.minor })
        assertEquals(originalPricing, StoredPricingSnapshotCodec.encode(entitlement.sale.quote))
        assertEquals(money(40), accepted.flatMap { it.tenderObligations }.filter { it.originalPaymentId == cash }.fold(money(0)) { sum, it -> sum + it.amount })
        assertEquals(money(60), accepted.flatMap { it.tenderObligations }.filter { it.originalPaymentId == external }.fold(money(0)) { sum, it -> sum + it.amount })
        SalesSnapshotLinks.requireReceiptHistory(entitlement, accepted, claims)
        val finalBytes = SalesSnapshotCodec.encodeAcceptedReturn(accepted.last(), entitlement, claims.last())
        assertRejected { SalesSnapshotCodec.decodeAcceptedReturn(mutate(finalBytes, JsonPrimitive("0"),
            "state", "consumedQuantityBefore", "scaled"), accepted.last().binding(), entitlement, claims.last()) }
        assertRejected { SalesSnapshotCodec.decodeAcceptedReturn(mutate(finalBytes, JsonPrimitive(f.nextId().value),
            "state", "tenderObligations", "0", "originalPaymentId"), accepted.last().binding(), entitlement, claims.last()) }
    }

    @Test fun receiptRejectsRegeneratedRightsDroppedRowsAndChangedProtectedPricing() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("0.33333333", f.quantity(3)))
        source.cash(money(100))
        val accepted = f.accept(source.finalize().value.entitlement, f.quantity(1))
        val entitlement = accepted.entitlement
        val encoded = SalesSnapshotCodec.encodeReceiptEntitlement(entitlement)
        assertRejected { SalesSnapshotCodec.decodeReceiptEntitlement(mutate(encoded, JsonPrimitive("0"),
            "state", "cursors", "0", "consumedQuantity", "scaled"), entitlement.binding()) }
        assertRejected { SalesSnapshotCodec.decodeReceiptEntitlement(mutate(encoded, JsonPrimitive("100"),
            "state", "remainingComponentTenders", "0", "shares", "0", "amount", "minor"), entitlement.binding()) }
        assertRejected { SalesSnapshotCodec.decodeReceiptEntitlement(mutate(encoded, JsonArray(emptyList()),
            "state", "sale", "originalTenderAllocations"), entitlement.binding()) }
        val unsupportedPrice = StoredPricingSnapshotCodec.encode(entitlement.sale.quote).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertRejected { SalesSnapshotCodec.decodeReceiptEntitlement(mutate(encoded, JsonPrimitive(unsupportedPrice),
            "state", "sale", "quote"), entitlement.binding()) }
    }

    @Test fun unknownRefundRoundTripKeepsOriginalPayerSessionAndReservedCashUntilActualPayout() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("10"))
        val originalPayment = source.cash(money(1_000))
        val accepted = f.accept(source.finalize().value.entitlement, f.quantity(1))
        val today = f.openCash(money(1_500), f.other)
        val attemptId = f.nextId()
        val started = Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.acceptedReturn.id,
            accepted.acceptedReturn.revision, money(600), f.other), accepted.acceptedReturn, accepted.acceptedReturn.revision,
            today, today.revision, attemptId, originalPayment, money(600)).value
        val unknown = Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RECORD, started.acceptedReturn.id,
            started.acceptedReturn.revision, money(600), f.other), started.acceptedReturn, started.acceptedReturn.revision,
            started.session, started.session.revision, attemptId, RefundOutcome.Unknown(f.evidence(f.other))).value
        val bytes = SalesSnapshotCodec.encodeAcceptedReturn(unknown.acceptedReturn, accepted.entitlement, accepted.claim)
        val returned = SalesSnapshotCodec.decodeAcceptedReturn(bytes, unknown.acceptedReturn.binding(), accepted.entitlement, accepted.claim)
        val cash = SalesSnapshotCodec.decodeCashSession(SalesSnapshotCodec.encodeCashSession(unknown.session), unknown.session.binding())
        assertEquals(RefundStatus.OUTCOME_UNKNOWN, returned.refundStatus)
        assertEquals(f.other, returned.attempts.single().originalActorId)
        assertEquals(today.id, returned.attempts.single().originalCashSessionId)
        assertEquals(money(600), cash.reservedCashOut)
        assertEquals(money(1_500), cash.expectedCash)
        SalesSnapshotLinks.requireCashSessionPending(cash, emptyList(), listOf(returned), emptyList())
        assertRule(SalesRule.INSUFFICIENT_DRAWER_CASH) {
            CashSessions.recordNonSale(f.command(SecurityAction.CASH_MOVE, cash.id, cash.revision, money(901), f.other),
                cash, cash.revision, f.nextId(), CashMovementKind.OPERATING_EXPENSE, money(901), f.evidence(f.other), "cannot spend reserved payout")
        }
        val paid = Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RESOLVE, returned.id, returned.revision, money(600), f.other),
            returned, returned.revision, cash, cash.revision, attemptId, RefundOutcome.CashPaid(f.evidence(f.other))).value
        assertEquals(money(900), paid.session.expectedCash)
        assertEquals(money(0), paid.session.reservedCashOut)
        assertEquals(money(400), paid.acceptedReturn.due)
        val paidBytes = SalesSnapshotCodec.encodeAcceptedReturn(paid.acceptedReturn, accepted.entitlement, accepted.claim)
        val hydratedPaid = SalesSnapshotCodec.decodeAcceptedReturn(paidBytes, paid.acceptedReturn.binding(), accepted.entitlement, accepted.claim)
        assertEquals(paid.acceptedReturn.attempts.single().refund, hydratedPaid.attempts.single().refund)
        assertRule(SalesRule.IMMUTABLE_RECORD) {
            Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RECORD, hydratedPaid.id, hydratedPaid.revision, money(600), f.other),
                hydratedPaid, hydratedPaid.revision, paid.session, paid.session.revision, attemptId, RefundOutcome.CashPaid(f.evidence(f.other)))
        }
    }

    @Test fun completeHistoryDetectsOmittedDuplicatedAndCoherentlyOverlappingClaims() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("0.33333333", f.quantity(3)))
        source.cash(money(100))
        val first = f.accept(source.finalize().value.entitlement, f.quantity(1))
        val second = f.accept(first.entitlement, f.quantity(1))
        SalesSnapshotLinks.requireReceiptHistory(second.entitlement, listOf(first.acceptedReturn, second.acceptedReturn), listOf(first.claim, second.claim))
        assertRejected { SalesSnapshotLinks.requireReceiptHistory(second.entitlement, listOf(first.acceptedReturn), listOf(first.claim)) }
        assertRejected { SalesSnapshotLinks.requireReceiptHistory(second.entitlement,
            listOf(first.acceptedReturn, first.acceptedReturn), listOf(first.claim, first.claim)) }
        val encoded = SalesSnapshotCodec.encodeAcceptedReturn(second.acceptedReturn, second.entitlement, second.claim)
        // Both first/second slices are 33. Isolated bytes cannot prove which historical
        // slot was used; the complete protected history must reject the overlap.
        val overlapping = SalesSnapshotCodec.decodeAcceptedReturn(mutate(encoded, JsonPrimitive("0"),
            "state", "consumedQuantityBefore", "scaled"), second.acceptedReturn.binding(), second.entitlement, second.claim)
        assertRejected { SalesSnapshotLinks.requireReceiptHistory(second.entitlement,
            listOf(first.acceptedReturn, overlapping), listOf(first.claim, second.claim)) }
    }
}

private fun CashSession.binding(): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, scope, id, revision)
private fun SafeTransfer.binding(): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.SAFE_TRANSFER, scope, id, revision)
private fun OwnedOrder.binding(): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.ORDER, scope, id, revision)
private fun CollectionLock.binding(): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, scope, id, revision)
private fun ReceiptEntitlement.binding(): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, scope, saleId, revision)
private fun ReturnClaim.binding(): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, scope, id, revision)
private fun AcceptedReturn.binding(): SalesSnapshotBinding = SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, scope, id, revision)

private inline fun assertRejected(block: () -> Unit) {
    assertFailsWith<SalesSnapshotRejectedException>(block = block)
}

private inline fun assertFailure(expected: SalesSnapshotFailure, block: () -> Unit) {
    assertEquals(expected, assertFailsWith<SalesSnapshotRejectedException>(block = block).failure)
}

/** Keep field order and canonical scalar representation while changing one real path. */
private fun mutate(encoded: String, replacement: JsonElement, vararg path: String): String {
    fun replace(node: JsonElement, depth: Int): JsonElement {
        if (depth == path.size) return replacement
        val segment = path[depth]
        return when (node) {
            is JsonObject -> {
                check(segment in node)
                JsonObject(node.mapValues { (key, value) -> if (key == segment) replace(value, depth + 1) else value })
            }
            is JsonArray -> {
                val index = segment.toInt()
                check(index in node.indices)
                JsonArray(node.mapIndexed { at, value -> if (at == index) replace(value, depth + 1) else value })
            }
            else -> error("Test mutation path is not present")
        }
    }
    return replace(Json.parseToJsonElement(encoded), 0).toString()
}
