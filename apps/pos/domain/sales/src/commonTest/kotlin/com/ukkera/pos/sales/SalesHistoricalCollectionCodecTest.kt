package com.ukkera.pos.sales

import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.pricing.PricingProfiles
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Historical facts from pure domain fixtures, not durable commits, current custody or payment authority. */
class SalesHistoricalCollectionCodecTest {
    @Test fun awaitingHistoricalIntentSurvivesTheCurrentSessionFinishingThatAttempt() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        val attemptId = source.start(egp(40), Tender.Cash, "original payer")
        val old = source.collection
        val oldOrder = source.order
        val oldSession = source.session
        val encoded = SalesSnapshotCodec.encodeCollection(old)
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(
            SalesSnapshotCodec.decodeCollection(encoded, old.historicalBinding(), oldOrder, oldSession)))

        source.record(attemptId, CollectionOutcome.CashReceived(egp(50), egp(10), f.evidence()))
        assertEquals(old.revision.next(), source.collection.revision)
        assertEquals(oldSession.revision.next(), source.session.revision)
        assertFalse(attemptId in source.session.pendingAttemptIds)
        assertEquals(egp(40), source.collection.collected)
        val currentBytes = historicalCurrentBytes(source)

        val restored = SalesSnapshotCodec.decodeHistoricalCollection(encoded, old.historicalBinding())
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(restored))
        assertEquals(AttemptStatus.AWAITING_ATTESTATION, restored.attempts.single().status)
        assertEquals(old.attempts.single().originalOperationId, restored.attempts.single().originalOperationId)
        assertEquals("original payer", restored.attempts.single().payerReference)
        assertEquals(null, restored.attempts.single().latestObservation)
        assertEquals(null, restored.attempts.single().payment)
        assertEquals(money(0), restored.collected)
        assertEquals(egp(100), restored.remainingPayable)
        assertTrue(restored.unresolved)
        assertHistoricalFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodeCollection(encoded, old.historicalBinding(), oldOrder, source.session)
        }
        assertEquals(currentBytes, historicalCurrentBytes(source))
        assertTrue(attemptId in oldSession.pendingAttemptIds)
    }

    @Test fun partialUnknownHistoricalCollectionKeepsOriginalEvidenceAfterResolution() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        source.cash(egp(40))
        val tender = f.external().copy(policyRevision = Revision(9_007_199_254_740_993L))
        val attemptId = source.start(egp(60), tender, "unknown original payer")
        val unknown = CollectionOutcome.Unknown(f.evidence())
        source.record(attemptId, unknown)
        val old = source.collection
        val oldSession = source.session
        val original = old.attempts.last()
        val encoded = SalesSnapshotCodec.encodeCollection(old)
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(
            SalesSnapshotCodec.decodeCollection(encoded, old.historicalBinding(), source.order, oldSession)))

        source.record(attemptId, CollectionOutcome.ExternalAttested(f.externalEvidence()))
        assertEquals(CollectionStatus.COVERED, source.collection.status)
        assertEquals(egp(100), source.collection.collected)
        assertEquals(oldSession.revision.next(), source.session.revision)
        assertFalse(attemptId in source.session.pendingAttemptIds)
        val currentBytes = historicalCurrentBytes(source)
        val restored = historicalCollectionRoundTrip(old)
        val restoredAttempt = restored.attempts.last()
        assertEquals(CollectionStatus.OUTCOME_UNKNOWN, restored.status)
        assertEquals(egp(40), restored.collected)
        assertEquals(egp(60), restored.remainingPayable)
        assertEquals(unknown, restoredAttempt.latestObservation)
        assertEquals(null, restoredAttempt.payment)
        assertEquals(original.originalOperationId, restoredAttempt.originalOperationId)
        assertEquals(original.originalActorId, restoredAttempt.originalActorId)
        assertEquals(original.originalCashSessionId, restoredAttempt.originalCashSessionId)
        assertEquals(original.payerReference, restoredAttempt.payerReference)
        assertEquals(tender, restoredAttempt.tender)
        assertTrue("\"policyRevision\":\"9007199254740993\"" in encoded)
        assertEquals(StoredPricingSnapshotCodec.encode(old.quote), StoredPricingSnapshotCodec.encode(restored.quote))
        assertEquals(old.stockRequirements.allocations, restored.stockRequirements.allocations)
        assertHistoricalFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodeCollection(encoded, old.historicalBinding(), source.order, source.session)
        }
        assertEquals(currentBytes, historicalCurrentBytes(source))
    }

    @Test fun coveredHistoricalCollectionIsNotReboundToFinalizedOrderOrClosedSession() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        source.cash(egp(100))
        val covered = source.collection
        val oldOrder = source.order
        val openSession = source.session
        val encoded = SalesSnapshotCodec.encodeCollection(covered)
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(
            SalesSnapshotCodec.decodeCollection(encoded, covered.historicalBinding(), oldOrder, openSession)))
        source.finalize()
        assertEquals(CollectionStatus.FINALIZED_PAID, source.collection.status)
        assertEquals(oldOrder.revision.next(), source.order.revision)
        assertHistoricalFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodeCollection(encoded, covered.historicalBinding(), source.order, openSession)
        }

        val work = ClosingWorkReadSet(f.scope, f.owner, Revision(1), emptyList(), emptyList())
        val closing = CashSessions.beginClosing(
            f.command(SecurityAction.CASH_CLOSE, openSession.id, openSession.revision),
            openSession, openSession.revision, work, work.revision).value
        val closed = CashSessions.submitClose(
            f.command(SecurityAction.CASH_CLOSE, closing.id, closing.revision, closing.expectedCash),
            closing, closing.revision, f.assignment(closing), CountedCash(closing.expectedCash, f.evidence()),
            closing.expectedCash, null, null, null).value
        source.session = closed.session // Retain all counted cash; no invented safe delivery.
        assertEquals(null, closed.transfer)
        assertEquals(CashSessionStatus.SUBMITTED_CLOSED, source.session.status)
        assertEquals(openSession.revision.next().next(), source.session.revision)
        val currentBytes = historicalCurrentBytes(source)

        val restored = historicalCollectionRoundTrip(covered)
        assertEquals(CollectionStatus.COVERED, restored.status)
        assertEquals(null, restored.saleId)
        assertEquals(covered.revision, restored.revision)
        assertEquals(egp(100), restored.collected)
        assertHistoricalFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodeCollection(encoded, covered.historicalBinding(), oldOrder, source.session)
        }
        // The stronger decoder legitimately accepts the matching FINALIZED rows with a closed session.
        val finalizedBytes = SalesSnapshotCodec.encodeCollection(source.collection)
        assertEquals(finalizedBytes, SalesSnapshotCodec.encodeCollection(SalesSnapshotCodec.decodeCollection(
            finalizedBytes, source.collection.historicalBinding(), source.order, source.session)))
        assertEquals(currentBytes, historicalCurrentBytes(source))
    }

    @Test fun handoffAndMixedTendersPreserveDifferentOriginalCreatorAndCollector() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("0.33333333", f.quantity(3), taxRate = "14", portions = 2), begin = false)
        val offered = Orders.offerHandoff(f.command(SecurityAction.ORDER_HANDOFF, source.order.id, source.order.revision),
            source.order, source.order.revision, f.nextId(), f.other, f.evidence()).value
        source.order = Orders.acceptHandoff(f.command(SecurityAction.ORDER_HANDOFF, offered.id, offered.revision, actor = f.other),
            offered, offered.revision, checkNotNull(offered.handoff).id, f.evidence(f.other)).value
        source.session = f.openCash(actor = f.other)
        source.begin()
        source.cash(money(80), money(100), money(20))
        source.external(source.collection.remainingPayable)
        val restored = historicalCollectionRoundTrip(source.collection)
        assertEquals(f.owner, restored.originalCreatorId)
        assertEquals(f.other, restored.collectorId)
        assertNotEquals(restored.originalCreatorId, restored.collectorId)
        assertEquals(source.collection.lockedOrderRevision, restored.lockedOrderRevision)
        assertEquals(source.collection.reservationId, restored.reservationId)
        assertEquals(source.collection.cashSessionId, restored.cashSessionId)
        assertEquals(money(228), restored.quote.payable)
        assertEquals(CollectionStatus.COVERED, restored.status)
        assertEquals(source.collection.attempts.map { it.payment }, restored.attempts.map { it.payment })
        assertEquals(listOf(money(80), money(148)), restored.attempts.map { it.amount })
    }

    @Test fun genuineZeroPayableCoveredAndFinalizedRowsNeedNoInventedPayments() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("0"))
        val covered = historicalCollectionRoundTrip(source.collection)
        assertEquals(CollectionStatus.COVERED, covered.status)
        assertEquals(money(0), covered.collected)
        assertTrue(covered.attempts.isEmpty())
        assertTrue(covered.stockRequirements.allocations.isNotEmpty())
        source.finalize()
        val finalized = historicalCollectionRoundTrip(source.collection)
        assertEquals(CollectionStatus.FINALIZED_PAID, finalized.status)
        assertEquals(source.order.paidSaleId, finalized.saleId)
        assertTrue(finalized.attempts.isEmpty())
        assertFalse(finalized.unresolved)
    }

    @Test fun notPaidAttemptSurvivesASubsequentGenuineSuccessfulAttempt() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        val declined = source.start(egp(100), f.external())
        val notPaid = CollectionOutcome.NotPaid(f.evidence(), "cashier.confirmed.not.paid")
        source.record(declined, notPaid)
        val old = source.collection
        source.cash(egp(100))
        val restoredOld = historicalCollectionRoundTrip(old)
        assertEquals(CollectionStatus.LOCKED_UNPAID, restoredOld.status)
        assertEquals(notPaid, restoredOld.attempts.single().latestObservation)
        assertEquals(null, restoredOld.attempts.single().payment)
        val restored = historicalCollectionRoundTrip(source.collection)
        assertEquals(listOf(AttemptStatus.NOT_PAID, AttemptStatus.ATTESTED_PAID), restored.attempts.map { it.status })
        assertEquals(declined, restored.attempts.first().id)
        assertEquals(CollectionStatus.COVERED, restored.status)
        assertEquals(egp(100), restored.collected)
    }

    @Test fun independentHistoricalKindScopeIdAndRevisionMustMatchTheEnvelope() = runTest {
        val f = SalesFixture()
        val collection = f.retail().collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val expected = collection.historicalBinding()
        val wrongBindings = listOf(
            expected.copy(id = f.nextId()),
            expected.copy(revision = expected.revision.next()),
            expected.copy(scope = expected.scope.copy(organizationId = f.nextId())),
            expected.copy(scope = expected.scope.copy(companyId = f.nextId())),
            expected.copy(scope = expected.scope.copy(branchId = f.nextId())),
        ) + SalesSnapshotKind.entries.filter { it != SalesSnapshotKind.COLLECTION }.map { expected.copy(kind = it) }
        wrongBindings.forEach { wrong -> assertHistoricalFailure(SalesSnapshotFailure.BINDING_MISMATCH) {
            SalesSnapshotCodec.decodeHistoricalCollection(encoded, wrong)
        } }
        val changedBodies = listOf(
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "id"),
            mutateHistorical(encoded, JsonPrimitive("1"), "revision"),
            mutateHistorical(encoded, JsonPrimitive("ORDER"), "kind"),
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "scope", "organizationId"),
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "scope", "companyId"),
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "scope", "branchId"),
        )
        changedBodies.forEach { changed -> rejectHistorical(collection, changed, SalesSnapshotFailure.BINDING_MISMATCH) }
    }

    @Test fun unsupportedSchemaAndMalformedContainersDoNotHydrateCollections() = runTest {
        val collection = SalesFixture().retail().collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        rejectHistorical(collection, mutateHistorical(encoded, JsonPrimitive(2), "schemaVersion"), SalesSnapshotFailure.UNSUPPORTED_VERSION)
        for (malformed in listOf("{", "[}", "null", "not-json", encoded.dropLast(1))) {
            rejectHistorical(collection, malformed, SalesSnapshotFailure.MALFORMED)
        }
    }

    @Test fun unknownAndOmittedFieldsRejectAtEveryProtectedLevel() = runTest {
        val source = SalesFixture().retail(); source.cash(egp(40))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val root = historicalElement(encoded).jsonObject
        val state = historicalElement(encoded, "state").jsonObject
        val attempt = historicalElement(encoded, "state", "attempts", "0").jsonObject
        val payment = attempt.getValue("payment").jsonObject
        val invalid = listOf(
            JsonObject(root + ("unexpected" to JsonPrimitive(true))).toString(),
            mutateHistorical(encoded, JsonObject(state + ("unexpected" to JsonPrimitive(true))), "state"),
            mutateHistorical(encoded, JsonObject(attempt + ("unexpected" to JsonPrimitive(true))), "state", "attempts", "0"),
            mutateHistorical(encoded, JsonObject(payment + ("unexpected" to JsonPrimitive(true))), "state", "attempts", "0", "payment"),
            JsonObject(root - "revision").toString(),
            mutateHistorical(encoded, JsonObject(state - "originalCreatorId"), "state"),
            mutateHistorical(encoded, JsonObject(state - "saleId"), "state"),
            mutateHistorical(encoded, JsonObject(attempt - "payerReference"), "state", "attempts", "0"),
            mutateHistorical(encoded, JsonObject(payment - "externalEvidence"), "state", "attempts", "0", "payment"),
        )
        invalid.forEach { rejectHistorical(collection, it, SalesSnapshotFailure.MALFORMED) }
    }

    @Test fun duplicateKeysWhitespaceReorderingAndAlternateEscapesAreNotCanonical() = runTest {
        val source = SalesFixture().retail(); source.cash(egp(40))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val root = historicalElement(encoded).jsonObject
        val state = historicalElement(encoded, "state").jsonObject
        val duplicates = listOf(
            encoded.replaceFirst("{", "{\"schemaVersion\":1,"),
            encoded.replaceFirst("\"state\":{", "\"state\":{\"orderId\":\"${collection.orderId.value}\","),
            encoded.replaceFirst("\"attempts\":[{", "\"attempts\":[{\"id\":\"${collection.attempts.single().id.value}\","),
        )
        duplicates.forEach { rejectHistorical(collection, it, SalesSnapshotFailure.NON_CANONICAL) }
        val alternatives = listOf(" $encoded", "$encoded\n",
            JsonObject(root.entries.reversed().associate { it.toPair() }).toString(),
            mutateHistorical(encoded, JsonObject(state.entries.reversed().associate { it.toPair() }), "state"),
            encoded.replaceFirst("COLLECTION", "COLLECT\\u0049ON"))
        alternatives.forEach { rejectHistorical(collection, it, SalesSnapshotFailure.NON_CANONICAL) }
    }

    @Test fun noncanonicalAndOverflowingScalarFormsRejectRatherThanCoerce() = runTest {
        val source = SalesFixture().retail(); source.cash(egp(40))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val malformed = listOf(
            mutateHistorical(encoded, JsonPrimitive(collection.revision.value), "revision"),
            mutateHistorical(encoded, JsonPrimitive("02"), "revision"),
            mutateHistorical(encoded, JsonPrimitive("-1"), "revision"),
            mutateHistorical(encoded, JsonPrimitive("9223372036854775808"), "revision"),
            mutateHistorical(encoded, JsonPrimitive("ABCDEF00-0000-4000-8000-000000000001"), "id"),
            mutateHistorical(encoded, JsonPrimitive("not-an-operation"), "state", "attempts", "0", "originalOperationId"),
            mutateHistorical(encoded, JsonPrimitive(4000), "state", "attempts", "0", "amount", "minor"),
            mutateHistorical(encoded, JsonPrimitive("04000"), "state", "attempts", "0", "amount", "minor"),
            mutateHistorical(encoded, JsonPrimitive("+4000"), "state", "attempts", "0", "amount", "minor"),
            mutateHistorical(encoded, JsonPrimitive("4e3"), "state", "attempts", "0", "amount", "minor"),
            mutateHistorical(encoded, JsonPrimitive("9223372036854775808"), "state", "attempts", "0", "amount", "minor"),
            mutateHistorical(encoded, JsonPrimitive("USD"), "state", "attempts", "0", "amount", "currency"),
            mutateHistorical(encoded, JsonPrimitive(1), "state", "stockRequirements", "0", "quantity", "scaled"),
            mutateHistorical(encoded, JsonPrimitive("0"), "state", "stockRequirements", "0", "quantity", "scale"),
            mutateHistorical(encoded, JsonPrimitive(7), "state", "stockRequirements", "0", "quantity", "scale"),
            mutateHistorical(encoded, JsonPrimitive("2026-10-23T12:00:00+00:00"), "state", "attempts", "0", "payment", "attestation", "occurredAt"),
        )
        malformed.forEach { rejectHistorical(collection, it, SalesSnapshotFailure.MALFORMED) }
    }

    @Test fun characterUtf8DepthAndOuterArrayBoundsRunBeforeRecursiveParsing() = runTest {
        val collection = SalesFixture().retail().collection
        val expected = collection.historicalBinding()
        assertHistoricalFailure(SalesSnapshotFailure.TOO_LARGE) {
            SalesSnapshotCodec.decodeHistoricalCollection(" ".repeat(SalesSnapshotCodec.MAX_ENCODED_BYTES + 1), expected)
        }
        assertHistoricalFailure(SalesSnapshotFailure.TOO_LARGE) {
            SalesSnapshotCodec.decodeHistoricalCollection("é".repeat(SalesSnapshotCodec.MAX_ENCODED_BYTES / 2 + 1), expected)
        }
        assertHistoricalFailure(SalesSnapshotFailure.TOO_LARGE) {
            SalesSnapshotCodec.decodeHistoricalCollection("[".repeat(49) + "]".repeat(49), expected)
        }
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(SalesSnapshotCodec.decodeHistoricalCollection(encoded, expected)))
        val root = historicalElement(encoded).jsonObject
        // An unknown scalar array cannot hit StoredCollection.restore's smaller allocation cap.
        // At 1,024 entries only strict deserialization rejects; entry 1,025 must hit the scanner first.
        for ((count, failure) in listOf(1_024 to SalesSnapshotFailure.MALFORMED, 1_025 to SalesSnapshotFailure.TOO_LARGE)) {
            val changed = JsonObject(root + ("unexpected" to JsonArray(List(count) { JsonNull }))).toString()
            assertTrue(changed.length < SalesSnapshotCodec.MAX_ENCODED_BYTES)
            assertTrue(changed.encodeToByteArray().size < SalesSnapshotCodec.MAX_ENCODED_BYTES)
            rejectHistorical(collection, changed, failure)
        }
    }

    @Test fun isolatedLoneSurrogatesAreMalformedWhileValidPairsRoundTrip() = runTest {
        val source = SalesFixture().retail()
        val reference = "utf16-control"
        source.start(egp(40), Tender.Cash, reference)
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val validReference = "valid-\uD83D\uDE00"
        val valid = mutateHistorical(encoded, JsonPrimitive(validReference), "state", "attempts", "0", "payerReference")
        val restored = SalesSnapshotCodec.decodeHistoricalCollection(valid, collection.historicalBinding())
        assertEquals(validReference, restored.attempts.single().payerReference)
        assertEquals(valid, SalesSnapshotCodec.encodeCollection(restored))

        assertTrue(reference in encoded)
        for (lone in listOf("\uD800", "\uDC00")) {
            val malformed = encoded.replaceFirst(reference, lone)
            assertTrue(malformed.length < SalesSnapshotCodec.MAX_ENCODED_BYTES)
            rejectHistorical(collection, malformed, SalesSnapshotFailure.MALFORMED)
        }
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(source.collection))
    }

    @Test fun collectionAllocationAndAttemptCountsFailBeforeTheirDuplicateContents() = runTest {
        val source = SalesFixture().retail(); source.cash(egp(40))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val allocation = historicalElement(encoded, "state", "stockRequirements", "0")
        for (count in listOf(0, 257)) {
            rejectHistorical(collection, mutateHistorical(encoded, JsonArray(List(count) { allocation }), "state", "stockRequirements"),
                SalesSnapshotFailure.TOO_LARGE)
        }
        val attempt = historicalElement(encoded, "state", "attempts", "0")
        rejectHistorical(collection, mutateHistorical(encoded, JsonArray(List(65) { attempt }), "state", "attempts"),
            SalesSnapshotFailure.TOO_LARGE)
    }

    @Test fun embeddedPricingStillUsesItsOwnedCanonicalSchemaBoundsAndInternalTotals() = runTest {
        val f = SalesFixture()
        val collection = f.retail().collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val quote = historicalElement(encoded, "state", "quote").jsonPrimitive.content
        val invalidQuotes = listOf(
            " $quote",
            mutateHistorical(quote, JsonPrimitive(2), "schemaVersion"),
            mutateHistorical(quote, JsonPrimitive("9999"), "payable", "minor"),
            "[".repeat(25) + "]".repeat(25),
            "[" + "0,".repeat(PricingProfiles.MAX_COMPONENTS) + "0]",
        )
        // These are quoted strings in the outer JSON; only the pricing decoder sees their nesting/arrays.
        invalidQuotes.forEach { rejectHistorical(collection, mutateHistorical(encoded, JsonPrimitive(it), "state", "quote")) }
        val otherQuote = f.quote("100")
        assertEquals(collection.quote.payable, otherQuote.payable)
        assertNotEquals(collection.quote.lines.first().portionId, otherQuote.lines.first().portionId)
        rejectHistorical(collection, mutateHistorical(encoded, JsonPrimitive(StoredPricingSnapshotCodec.encode(otherQuote)), "state", "quote"))
    }

    @Test fun stockRequirementsMustStillMatchScopePricedPortionsUnitsAndFullQuantity() = runTest {
        val f = SalesFixture()
        val collection = f.retail().collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val allocation = historicalElement(encoded, "state", "stockRequirements", "0")
        val invalid = listOf(
            mutateHistorical(encoded, JsonArray(listOf(allocation, allocation)), "state", "stockRequirements"),
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "state", "stockRequirements", "0", "companyId"),
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "state", "stockRequirements", "0", "branchId"),
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "state", "stockRequirements", "0", "portionId"),
            mutateHistorical(encoded, JsonPrimitive(f.nextId().value), "state", "stockRequirements", "0", "quantity", "unitId"),
            mutateHistorical(encoded, JsonPrimitive(1), "state", "stockRequirements", "0", "quantity", "scale"),
            mutateHistorical(encoded, JsonPrimitive("0"), "state", "stockRequirements", "0", "quantity", "scaled"),
            mutateHistorical(encoded, JsonPrimitive("-1"), "state", "stockRequirements", "0", "quantity", "scaled"),
            mutateHistorical(encoded, JsonPrimitive("2"), "state", "stockRequirements", "0", "quantity", "scaled"),
        )
        invalid.forEach { rejectHistorical(collection, it) }
    }

    @Test fun duplicateAttemptIdsOriginalOperationsAndImpossibleRevisionsReject() = runTest {
        val source = SalesFixture().retail(); source.cash(egp(40)); source.external(egp(60))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val first = collection.attempts.first()
        val duplicateId = mutateHistorical(mutateHistorical(encoded, JsonPrimitive(first.id.value), "state", "attempts", "1", "id"),
            JsonPrimitive(first.id.value), "state", "attempts", "1", "payment", "attemptId")
        val duplicateOperation = mutateHistorical(mutateHistorical(encoded, JsonPrimitive(first.originalOperationId.value),
            "state", "attempts", "1", "originalOperationId"), JsonPrimitive(first.originalOperationId.value),
            "state", "attempts", "1", "payment", "originalOperationId")
        // Each payment still matches its own attempt; the collection-wide uniqueness checks must run.
        rejectHistorical(collection, duplicateId)
        rejectHistorical(collection, duplicateOperation)
        assertHistoricalFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodeHistoricalCollection(mutateHistorical(encoded, JsonPrimitive("1"), "revision"),
                collection.historicalBinding().copy(revision = Revision(1)))
        }
        rejectHistorical(collection, mutateHistorical(encoded, JsonPrimitive("0"), "state", "lockedOrderRevision"))
    }

    @Test fun attemptStatusObservationAndPaymentMustDescribeTheSameHistoricalOutcome() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        val attemptId = source.start(egp(40), Tender.Cash)
        val awaiting = source.collection
        val awaitingBytes = SalesSnapshotCodec.encodeCollection(awaiting)
        for (status in listOf("OUTCOME_UNKNOWN", "NOT_PAID", "ATTESTED_PAID", "FUTURE_STATUS")) {
            rejectHistorical(awaiting, mutateHistorical(awaitingBytes, JsonPrimitive(status), "state", "attempts", "0", "status"))
        }
        source.record(attemptId, CollectionOutcome.Unknown(f.evidence()))
        val unknown = source.collection
        val unknownBytes = SalesSnapshotCodec.encodeCollection(unknown)
        for (status in listOf("AWAITING_ATTESTATION", "NOT_PAID", "ATTESTED_PAID")) {
            rejectHistorical(unknown, mutateHistorical(unknownBytes, JsonPrimitive(status), "state", "attempts", "0", "status"))
        }
        rejectHistorical(unknown, mutateHistorical(unknownBytes, JsonNull, "state", "attempts", "0", "latestObservation"))
        source.record(attemptId, CollectionOutcome.CashReceived(egp(50), egp(10), f.evidence()))
        val paid = source.collection
        val paidBytes = SalesSnapshotCodec.encodeCollection(paid)
        for (status in listOf("AWAITING_ATTESTATION", "OUTCOME_UNKNOWN", "NOT_PAID")) {
            rejectHistorical(paid, mutateHistorical(paidBytes, JsonPrimitive(status), "state", "attempts", "0", "status"))
        }
        rejectHistorical(paid, mutateHistorical(paidBytes, JsonNull, "state", "attempts", "0", "latestObservation"))
        rejectHistorical(paid, mutateHistorical(paidBytes, JsonNull, "state", "attempts", "0", "payment"))
        historicalCollectionRoundTrip(awaiting); historicalCollectionRoundTrip(unknown); historicalCollectionRoundTrip(paid)
    }

    @Test fun attemptAndPaymentActorSessionCollectionTenderAmountAndPayerLinksCannotDrift() = runTest {
        val f = SalesFixture()
        val source = f.retail(); source.cash(egp(40)); source.external(egp(60))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val other = JsonPrimitive(f.nextId().value)
        val idPaths = listOf(
            listOf("state", "collectorId"), listOf("state", "cashSessionId"),
            listOf("state", "attempts", "0", "id"), listOf("state", "attempts", "0", "originalOperationId"),
            listOf("state", "attempts", "0", "originalActorId"), listOf("state", "attempts", "0", "originalCashSessionId"),
            listOf("state", "attempts", "0", "payment", "attemptId"),
            listOf("state", "attempts", "0", "payment", "originalOperationId"),
            listOf("state", "attempts", "0", "payment", "collectionId"),
            listOf("state", "attempts", "0", "payment", "collectorId"),
            listOf("state", "attempts", "0", "payment", "cashSessionId"),
        )
        idPaths.forEach { rejectHistorical(collection, mutateHistorical(encoded, other, *it.toTypedArray())) }
        val externalTender = historicalElement(encoded, "state", "attempts", "1", "tender")
        val otherContradictions = listOf(
            mutateHistorical(encoded, JsonPrimitive("other payer"), "state", "attempts", "0", "payerReference"),
            mutateHistorical(encoded, JsonPrimitive("other payer"), "state", "attempts", "0", "payment", "payerReference"),
            mutateHistorical(encoded, externalTender, "state", "attempts", "0", "tender"),
            mutateHistorical(encoded, externalTender, "state", "attempts", "0", "payment", "tender"),
            mutateHistorical(encoded, JsonPrimitive("4"), "state", "attempts", "1", "payment", "tender", "policyRevision"),
            mutateHistorical(encoded, JsonPrimitive("4001"), "state", "attempts", "0", "amount", "minor"),
            mutateHistorical(encoded, JsonPrimitive("4001"), "state", "attempts", "0", "payment", "amount", "minor"),
        )
        otherContradictions.forEach { rejectHistorical(collection, it) }
    }

    @Test fun observedEvidenceAndPaymentAttestationsMustAgreeExactly() = runTest {
        val f = SalesFixture()
        val source = f.retail(); source.cash(egp(40)); source.external(egp(60))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val other = JsonPrimitive(f.nextId().value)
        val evidencePaths = listOf(
            listOf("state", "attempts", "0", "payment", "attestation", "actorId"),
            listOf("state", "attempts", "0", "payment", "attestation", "evidenceId"),
            listOf("state", "attempts", "0", "latestObservation", "evidence", "actorId"),
            listOf("state", "attempts", "0", "latestObservation", "evidence", "evidenceId"),
            listOf("state", "attempts", "1", "payment", "externalEvidence", "attestation", "evidenceId"),
            listOf("state", "attempts", "1", "latestObservation", "evidence", "attestation", "actorId"),
        )
        evidencePaths.forEach { rejectHistorical(collection, mutateHistorical(encoded, other, *it.toTypedArray())) }
        rejectHistorical(collection, mutateHistorical(encoded, JsonPrimitive("2026-10-23T12:00:01Z"),
            "state", "attempts", "0", "payment", "attestation", "occurredAt"))
    }

    @Test fun cashReceivedMinusChangeAndOriginalObservedGrossAmountsAreBothRequired() = runTest {
        val source = SalesFixture().retail(); source.cash(egp(40), egp(50), egp(10))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val invalid = listOf(
            mutateHistorical(encoded, JsonNull, "state", "attempts", "0", "payment", "cashReceived"),
            mutateHistorical(encoded, JsonNull, "state", "attempts", "0", "payment", "changeGiven"),
            mutateHistorical(encoded, JsonPrimitive("0"), "state", "attempts", "0", "payment", "cashReceived", "minor"),
            mutateHistorical(encoded, JsonPrimitive("-1"), "state", "attempts", "0", "payment", "changeGiven", "minor"),
            mutateHistorical(encoded, JsonPrimitive("5001"), "state", "attempts", "0", "payment", "cashReceived", "minor"),
            mutateHistorical(encoded, JsonPrimitive("999"), "state", "attempts", "0", "payment", "changeGiven", "minor"),
            mutateHistorical(mutateHistorical(encoded, JsonPrimitive("6000"), "state", "attempts", "0", "latestObservation", "received", "minor"),
                JsonPrimitive("2000"), "state", "attempts", "0", "latestObservation", "change", "minor"),
        )
        invalid.forEach { rejectHistorical(collection, it) }
        val payment = historicalCollectionRoundTrip(collection).attempts.single().payment!!
        assertEquals(egp(50), payment.cashReceived)
        assertEquals(egp(10), payment.changeGiven)
        assertEquals(egp(40), payment.amount)
    }

    @Test fun requiredExternalTerminalAndReferenceAreNotRelaxedForHistoricalData() = runTest {
        val source = SalesFixture().retail(); source.external(egp(100))
        val collection = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(collection)
        val tender = assertIs<Tender.External>(collection.attempts.single().tender)
        assertTrue(tender.requiresTerminal && tender.requiresReference)
        for (field in listOf("terminal", "reference")) {
            for (invalid in listOf(JsonNull, JsonPrimitive(" "), JsonPrimitive("x".repeat(129)))) {
                // Keep observation and payment evidence equal so policy/bounds, not only equality, must reject.
                val changed = mutateHistorical(mutateHistorical(encoded, invalid,
                    "state", "attempts", "0", "payment", "externalEvidence", field), invalid,
                    "state", "attempts", "0", "latestObservation", "evidence", field)
                rejectHistorical(collection, changed)
            }
        }
        rejectHistorical(collection, mutateHistorical(encoded, historicalElement(encoded, "state", "attempts", "0", "amount"),
            "state", "attempts", "0", "payment", "cashReceived"))
    }

    @Test fun payerReferencesAndNotPaidReasonsKeepTheirOwnContentBounds() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        val attemptId = source.start(egp(100), f.external(), "original payer")
        val awaiting = source.collection
        val awaitingBytes = SalesSnapshotCodec.encodeCollection(awaiting)
        for (invalid in listOf(" ", "x".repeat(129))) {
            rejectHistorical(awaiting, mutateHistorical(awaitingBytes, JsonPrimitive(invalid), "state", "attempts", "0", "payerReference"))
        }
        source.record(attemptId, CollectionOutcome.NotPaid(f.evidence(), "cashier.confirmed.not.paid"))
        val notPaid = source.collection
        val notPaidBytes = SalesSnapshotCodec.encodeCollection(notPaid)
        for (invalid in listOf(" ", "x".repeat(257))) {
            rejectHistorical(notPaid, mutateHistorical(notPaidBytes, JsonPrimitive(invalid), "state", "attempts", "0", "latestObservation", "reason"))
        }
        source.cash(egp(100))
        val paid = source.collection
        val paidBytes = SalesSnapshotCodec.encodeCollection(paid)
        for (invalid in listOf(" ", "x".repeat(129))) {
            rejectHistorical(paid, mutateHistorical(paidBytes, JsonPrimitive(invalid), "state", "attempts", "1", "payment", "payerReference"))
        }
    }

    @Test fun coverageCannotExceedRemainingPayableEvenWithInternallyConsistentPaymentRows() = runTest {
        val f = SalesFixture()
        val source = f.retail(); source.cash(egp(40))
        val attemptId = source.start(egp(60), f.external())
        val awaiting = source.collection
        val awaitingBytes = SalesSnapshotCodec.encodeCollection(awaiting)
        for (amount in listOf("0", "-1", "6001")) {
            rejectHistorical(awaiting, mutateHistorical(awaitingBytes, JsonPrimitive(amount), "state", "attempts", "1", "amount", "minor"))
        }
        source.record(attemptId, CollectionOutcome.ExternalAttested(f.externalEvidence()))
        val covered = source.collection
        val encoded = SalesSnapshotCodec.encodeCollection(covered)
        for (amount in listOf("6001", Long.MAX_VALUE.toString())) {
            val changed = mutateHistorical(mutateHistorical(encoded, JsonPrimitive(amount), "state", "attempts", "1", "amount", "minor"),
                JsonPrimitive(amount), "state", "attempts", "1", "payment", "amount", "minor")
            rejectHistorical(covered, changed)
        }
        assertEquals(egp(100), historicalCollectionRoundTrip(covered).collected)
    }

    @Test fun anUnresolvedAttemptMustRemainTheLastAttemptNotJustHaveValidEvidence() = runTest {
        val f = SalesFixture()
        val source = f.retail(); source.cash(egp(40))
        val attemptId = source.start(egp(60), f.external())
        val awaiting = source.collection
        val awaitingBytes = SalesSnapshotCodec.encodeCollection(awaiting)
        rejectHistorical(awaiting, mutateHistorical(awaitingBytes,
            JsonArray(historicalElement(awaitingBytes, "state", "attempts").jsonArray.reversed()), "state", "attempts"))
        source.record(attemptId, CollectionOutcome.Unknown(f.evidence()))
        val unknown = source.collection
        val unknownBytes = SalesSnapshotCodec.encodeCollection(unknown)
        rejectHistorical(unknown, mutateHistorical(unknownBytes,
            JsonArray(historicalElement(unknownBytes, "state", "attempts").jsonArray.reversed()), "state", "attempts"))
        historicalCollectionRoundTrip(awaiting); historicalCollectionRoundTrip(unknown)
    }

    @Test fun aSaleIdCannotTurnUnpaidPartialOrUnknownCoverageIntoFinalization() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        val unpaid = source.collection
        source.cash(egp(40))
        val partial = source.collection
        val attemptId = source.start(egp(60), f.external())
        source.record(attemptId, CollectionOutcome.Unknown(f.evidence()))
        val unknown = source.collection
        for (collection in listOf(unpaid, partial, unknown)) {
            rejectHistorical(collection, mutateHistorical(SalesSnapshotCodec.encodeCollection(collection),
                JsonPrimitive(f.nextId().value), "state", "saleId"))
        }
        assertEquals(CollectionStatus.OUTCOME_UNKNOWN, historicalCollectionRoundTrip(unknown).status)
        assertEquals(null, source.collection.saleId)
    }

    @Test fun restoredCollectionsOwnNonmutableListsAndRetainNoExecutableAuthority() = runTest {
        val source = SalesFixture().retail(); source.cash(egp(40))
        val encoded = SalesSnapshotCodec.encodeCollection(source.collection)
        val restored = SalesSnapshotCodec.decodeHistoricalCollection(encoded, source.collection.historicalBinding())
        assertFails { (restored.attempts as MutableList<*>).clear() }
        assertFails { (restored.stockRequirements.allocations as MutableList<*>).clear() }
        assertFails { (restored.quote.lines as MutableList<*>).clear() }
        assertFalse("authorization" in encoded || "payloadDigest" in encoded || "InventoryPlan" in encoded ||
            "ReservationProof" in encoded || "mustCommitBefore" in encoded)
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(restored))
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(source.collection))
    }
}

private fun CollectionLock.historicalBinding(): SalesSnapshotBinding =
    SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, scope, id, revision)

private fun historicalCollectionRoundTrip(collection: CollectionLock): CollectionLock {
    val encoded = SalesSnapshotCodec.encodeCollection(collection)
    val restored = SalesSnapshotCodec.decodeHistoricalCollection(encoded, collection.historicalBinding())
    assertFalse(restored === collection)
    assertEquals(collection.historicalBinding(), restored.historicalBinding())
    assertEquals(encoded, SalesSnapshotCodec.encodeCollection(restored))
    assertEquals(encoded, SalesSnapshotCodec.encodeCollection(collection))
    return restored
}

private fun historicalCurrentBytes(source: RetailFixture): List<String> = listOf(
    SalesSnapshotCodec.encodeCollection(source.collection), SalesSnapshotCodec.encodeOrder(source.order),
    SalesSnapshotCodec.encodeCashSession(source.session), InventorySnapshotCodec.encode(source.inventory),
)

private inline fun assertHistoricalFailure(expected: SalesSnapshotFailure, action: () -> Unit) {
    val failure = assertFailsWith<SalesSnapshotRejectedException>(block = action)
    assertEquals(expected, failure.failure)
    assertEquals(expected.name, failure.message)
    assertEquals(null, failure.cause)
}

private fun rejectHistorical(
    collection: CollectionLock,
    encoded: String,
    failure: SalesSnapshotFailure = SalesSnapshotFailure.INCONSISTENT_STATE,
) {
    assertNotEquals(SalesSnapshotCodec.encodeCollection(collection), encoded)
    assertHistoricalFailure(failure) { SalesSnapshotCodec.decodeHistoricalCollection(encoded, collection.historicalBinding()) }
}

private fun historicalElement(encoded: String, vararg path: String): JsonElement =
    path.fold(Json.parseToJsonElement(encoded)) { node, key ->
        when (node) {
            is JsonObject -> node.getValue(key)
            is JsonArray -> node[key.toInt()]
            else -> error("Test path is not present")
        }
    }

/** Keep field order and unrelated scalar forms; all paths must already exist in the genuine fixture. */
private fun mutateHistorical(encoded: String, replacement: JsonElement, vararg path: String): String {
    fun replace(node: JsonElement, depth: Int): JsonElement {
        if (depth == path.size) return replacement
        return when (node) {
            is JsonObject -> {
                val key = path[depth]
                check(key in node)
                JsonObject(node.mapValues { (name, value) -> if (name == key) replace(value, depth + 1) else value })
            }
            is JsonArray -> {
                val index = path[depth].toInt()
                check(index in node.indices)
                JsonArray(node.mapIndexed { at, value -> if (at == index) replace(value, depth + 1) else value })
            }
            else -> error("Test mutation path is not present")
        }
    }
    return replace(Json.parseToJsonElement(encoded), 0).toString()
}
