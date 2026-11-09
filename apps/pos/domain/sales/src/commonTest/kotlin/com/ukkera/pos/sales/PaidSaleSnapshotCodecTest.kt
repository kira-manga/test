package com.ukkera.pos.sales

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

/** Pure protected-fact codec tests, not persisted payment, stock, provenance or crash-recovery evidence. */
class PaidSaleSnapshotCodecTest {
    @Test fun standaloneRoundTripPreservesOriginalQuoteSplitTenderStockIssueAndDifferentCreatorCollector() = runTest {
        val fixture = paidSaleFixture()
        val sale = fixture.finalized.sale
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val restored = SalesSnapshotCodec.decodePaidSale(encoded, sale.paidBinding())
        assertEquals(Revision(0), SalesSnapshotCodec.PAID_SALE_REVISION)
        assertEquals(sale.id, restored.id)
        assertEquals(sale.scope, restored.scope)
        assertEquals(sale.orderId, restored.orderId)
        assertEquals(fixture.data.owner, restored.originalCreatorId)
        assertEquals(fixture.data.other, restored.collectorId)
        assertNotEquals(restored.originalCreatorId, restored.collectorId)
        assertEquals(fixture.source.session.id, restored.collectingSessionId)
        val issue = checkNotNull(fixture.source.inventory.issue(sale.stockIssueId))
        assertEquals(issue.issueId, restored.stockIssueId)
        assertEquals(issue.binding.orderId, restored.orderId)
        assertEquals(issue.binding.originalActorId, restored.collectorId)
        assertEquals(fixture.finalized.collection.id, issue.binding.collectionId)
        val originalPricing = StoredPricingSnapshotCodec.encode(sale.quote)
        assertEquals(originalPricing, StoredPricingSnapshotCodec.encode(restored.quote))
        assertEquals(money(228), restored.quote.payable)
        assertEquals(sale.payments, restored.payments)
        assertEquals(2, restored.payments.size)
        assertTrue(restored.payments.all { it.collectionId == fixture.finalized.collection.id &&
            it.collectorId == restored.collectorId && it.cashSessionId == restored.collectingSessionId &&
            it.originalOperationId != it.recordingOperationId })
        val cash = restored.payments.first()
        assertEquals(Tender.Cash, cash.tender)
        assertEquals(money(80), cash.amount)
        assertEquals(money(100), cash.cashReceived)
        assertEquals(money(20), cash.changeGiven)
        val external = restored.payments.last()
        assertEquals("original payer evidence", external.payerReference)
        assertEquals(Revision(9_007_199_254_740_993L), assertIs<Tender.External>(external.tender).policyRevision)
        assertEquals(sale.originalTenderAllocations.map { it.componentKey to it.shares },
            restored.originalTenderAllocations.map { it.componentKey to it.shares })
        val envelope = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("PAID_SALE", envelope.getValue("kind").jsonPrimitive.content)
        assertEquals("0", envelope.getValue("revision").jsonPrimitive.content)
        val state = envelope.getValue("state").jsonObject
        assertEquals(originalPricing, state.getValue("quote").jsonPrimitive.content)
        assertFalse("sale" in state || "cursors" in state || "remainingComponentTenders" in state)
        assertTrue("\"policyRevision\":\"9007199254740993\"" in encoded)
        assertFalse("authorization" in encoded || "payloadDigest" in encoded || "InventoryPlan" in encoded || "mustCommitBefore" in encoded)
        assertEquals(encoded, SalesSnapshotCodec.encodePaidSale(restored))
    }

    @Test fun genuineZeroPayableSaleRemainsAStandaloneFactWithoutInventedPaymentRecords() = runTest {
        val f = SalesFixture()
        val sale = f.retail(f.quote("0")).finalize().value.sale
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val restored = SalesSnapshotCodec.decodePaidSale(encoded, sale.paidBinding())
        assertEquals(money(0), restored.quote.payable)
        assertTrue(restored.payments.isEmpty())
        assertTrue(restored.originalTenderAllocations.isNotEmpty())
        assertTrue(restored.originalTenderAllocations.all { it.shares.isEmpty() })
        assertEquals(sale.stockIssueId, restored.stockIssueId)
        assertEquals(encoded, SalesSnapshotCodec.encodePaidSale(restored))
    }

    @Test fun independentIdScopeKindAndFixedZeroRevisionCannotDriftEvenTogetherWithTheBody() = runTest {
        val fixture = paidSaleFixture()
        val sale = fixture.finalized.sale
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val expected = sale.paidBinding()
        val f = fixture.data
        val wrongBindings = listOf(
            expected.copy(id = f.nextId()),
            expected.copy(scope = expected.scope.copy(organizationId = f.nextId())),
            expected.copy(scope = expected.scope.copy(companyId = f.nextId())),
            expected.copy(scope = expected.scope.copy(branchId = f.nextId())),
            expected.copy(revision = Revision(1)),
            expected.copy(revision = Revision(Long.MAX_VALUE)),
        ) + SalesSnapshotKind.entries.filter { it != SalesSnapshotKind.PAID_SALE }.map { expected.copy(kind = it) }
        for (wrong in wrongBindings) assertPaidFailure(SalesSnapshotFailure.BINDING_MISMATCH) {
            SalesSnapshotCodec.decodePaidSale(encoded, wrong)
        }
        for (revision in listOf(Revision(1), Revision(Long.MAX_VALUE))) {
            val drifted = mutatePaid(encoded, JsonPrimitive(revision.value.toString()), "revision")
            assertPaidFailure(SalesSnapshotFailure.BINDING_MISMATCH) { SalesSnapshotCodec.decodePaidSale(drifted, expected) }
            // Matching mutable-looking metadata must not turn an immutable fact into a revisioned aggregate.
            assertPaidFailure(SalesSnapshotFailure.BINDING_MISMATCH) {
                SalesSnapshotCodec.decodePaidSale(drifted, expected.copy(revision = revision))
            }
        }
        assertPaidFailure(SalesSnapshotFailure.BINDING_MISMATCH) {
            SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, JsonPrimitive(f.nextId().value), "id"), expected)
        }
    }

    @Test fun changingReturnEntitlementNeverChangesOrSubstitutesTheOriginalStandaloneSale() = runTest {
        val fixture = paidSaleFixture()
        val sale = fixture.finalized.sale
        val original = SalesSnapshotCodec.encodePaidSale(sale)
        val accepted = fixture.data.accept(fixture.finalized.entitlement, fixture.data.quantity(1))
        assertEquals(Revision(1), accepted.entitlement.revision)
        assertTrue(accepted.entitlement.cursors.any { it.consumedQuantity.scaled > 0 })
        assertEquals(original, SalesSnapshotCodec.encodePaidSale(accepted.entitlement.sale))
        val restored = SalesSnapshotCodec.decodePaidSale(original, sale.paidBinding())
        assertEquals(sale.originalTenderAllocations.map { it.componentKey to it.shares },
            restored.originalTenderAllocations.map { it.componentKey to it.shares })
        val entitlementBytes = SalesSnapshotCodec.encodeReceiptEntitlement(accepted.entitlement)
        assertPaidRejected {
            SalesSnapshotCodec.decodePaidSale(entitlementBytes, sale.paidBinding())
        }
        assertPaidRejected {
            SalesSnapshotCodec.decodeReceiptEntitlement(original,
                SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, sale.scope, sale.id, Revision(0)))
        }
        assertEquals(original, SalesSnapshotCodec.encodePaidSale(restored))
    }

    @Test fun malformedUnknownDuplicateAlternateAndNoncanonicalFormatsAreNotHydrated() = runTest {
        val sale = paidSaleFixture().finalized.sale
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val expected = sale.paidBinding()
        assertPaidFailure(SalesSnapshotFailure.UNSUPPORTED_VERSION) {
            SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, JsonPrimitive(2), "schemaVersion"), expected)
        }
        for (malformed in listOf("{", "null", "not-json", encoded.dropLast(1))) {
            assertPaidRejected { SalesSnapshotCodec.decodePaidSale(malformed, expected) }
        }
        val root = Json.parseToJsonElement(encoded).jsonObject
        val state = root.getValue("state").jsonObject
        val payment = state.getValue("payments").jsonArray.first().jsonObject
        val unknownOrMissing = listOf(
            JsonObject(root + ("unexpected" to JsonPrimitive(true))).toString(),
            mutatePaid(encoded, JsonObject(state + ("unexpected" to JsonPrimitive(true))), "state"),
            mutatePaid(encoded, JsonObject(payment + ("unexpected" to JsonPrimitive(true))), "state", "payments", "0"),
            mutatePaid(encoded, JsonObject(state - "originalCreatorId"), "state"),
            mutatePaid(encoded, JsonObject(payment - "payerReference"), "state", "payments", "0"),
        )
        for (changed in unknownOrMissing) assertPaidRejected { SalesSnapshotCodec.decodePaidSale(changed, expected) }
        val duplicates = listOf(
            encoded.replaceFirst("{", "{\"schemaVersion\":1,"),
            encoded.replaceFirst("\"state\":{", "\"state\":{\"orderId\":\"${sale.orderId.value}\","),
        )
        for (duplicate in duplicates) assertPaidRejected { SalesSnapshotCodec.decodePaidSale(duplicate, expected) }
        for (noncanonical in listOf(" $encoded", "$encoded\n",
            JsonObject(root.entries.reversed().associate { it.toPair() }).toString(),
            encoded.replaceFirst("PAID_SALE", "PAID\\u005fSALE"))) {
            assertPaidFailure(SalesSnapshotFailure.NON_CANONICAL) { SalesSnapshotCodec.decodePaidSale(noncanonical, expected) }
        }
        assertPaidRejected { SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, JsonPrimitive(80),
            "state", "payments", "0", "amount", "minor"), expected) }
        assertPaidRejected { SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, JsonPrimitive("2026-10-23T12:00:00+00:00"),
            "state", "payments", "0", "attestation", "occurredAt"), expected) }
        assertPaidRejected { SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded,
            JsonPrimitive(" " + StoredPricingSnapshotCodec.encode(sale.quote)), "state", "quote"), expected) }
    }

    @Test fun standaloneEntryPointRetainsByteDepthOuterArrayAndPaymentCountBounds() = runTest {
        val sale = paidSaleFixture().finalized.sale
        val expected = sale.paidBinding()
        assertPaidFailure(SalesSnapshotFailure.TOO_LARGE) {
            SalesSnapshotCodec.decodePaidSale(" ".repeat(SalesSnapshotCodec.MAX_ENCODED_BYTES + 1), expected)
        }
        assertPaidFailure(SalesSnapshotFailure.TOO_LARGE) {
            // Below the character limit, above the UTF-8 byte limit.
            SalesSnapshotCodec.decodePaidSale("é".repeat(SalesSnapshotCodec.MAX_ENCODED_BYTES / 2 + 1), expected)
        }
        assertPaidFailure(SalesSnapshotFailure.TOO_LARGE) {
            SalesSnapshotCodec.decodePaidSale("[".repeat(49) + "]".repeat(49), expected)
        }
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val state = Json.parseToJsonElement(encoded).jsonObject.getValue("state").jsonObject
        assertPaidFailure(SalesSnapshotFailure.TOO_LARGE) {
            val tooManyRows = JsonArray(List(1_025) { state.getValue("originalTenderAllocations").jsonArray.first() })
            SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, tooManyRows, "state", "originalTenderAllocations"), expected)
        }
        assertPaidFailure(SalesSnapshotFailure.TOO_LARGE) {
            val tooManyPayments = JsonArray(List(65) { state.getValue("payments").jsonArray.first() })
            SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, tooManyPayments, "state", "payments"), expected)
        }
    }

    @Test fun paymentActorSessionCollectionTenderEvidenceAndCoverageContradictionsReject() = runTest {
        val fixture = paidSaleFixture()
        val sale = fixture.finalized.sale
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val otherId = fixture.data.nextId().value
        val contradictions = listOf(
            mutatePaid(encoded, JsonPrimitive(otherId), "state", "collectorId"),
            mutatePaid(encoded, JsonPrimitive(otherId), "state", "collectingSessionId"),
            mutatePaid(encoded, JsonPrimitive(otherId), "state", "payments", "1", "collectionId"),
            mutatePaid(encoded, JsonPrimitive(sale.payments.first().attemptId.value), "state", "payments", "1", "attemptId"),
            mutatePaid(encoded, JsonPrimitive(otherId), "state", "payments", "0", "attestation", "actorId"),
            mutatePaid(encoded, JsonPrimitive("101"), "state", "payments", "0", "cashReceived", "minor"),
            mutatePaid(encoded, JsonNull, "state", "payments", "1", "externalEvidence", "terminal"),
            mutatePaid(encoded, JsonNull, "state", "payments", "1", "externalEvidence", "reference"),
            mutatePaid(encoded, JsonArray(emptyList()), "state", "payments"),
        )
        for (changed in contradictions) assertPaidFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodePaidSale(changed, sale.paidBinding())
        }
    }

    @Test fun pricingComponentRowsAndOriginalTenderColumnsMustBothConserveAndMatch() = runTest {
        val fixture = paidSaleFixture()
        val sale = fixture.finalized.sale
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val rows = sale.originalTenderAllocations
        val changedRows = listOf(
            mutatePaid(encoded, JsonArray(emptyList()), "state", "originalTenderAllocations"),
            mutatePaid(encoded, JsonPrimitive(rows.first().componentKey), "state", "originalTenderAllocations", "1", "componentKey"),
            mutatePaid(encoded, JsonArray(emptyList()), "state", "originalTenderAllocations", "0", "shares"),
            mutatePaid(encoded, JsonPrimitive(rows.first().shares.first().paymentId.value),
                "state", "originalTenderAllocations", "0", "shares", "1", "paymentId"),
            mutatePaid(encoded, JsonPrimitive("-1"), "state", "originalTenderAllocations", "0", "shares", "0", "amount", "minor"),
        )
        for (changed in changedRows) assertPaidFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodePaidSale(changed, sale.paidBinding())
        }
        val firstShares = rows.first().shares
        assertTrue(firstShares.all { it.amount.minor > 0 })
        val shiftedColumn = mutatePaid(mutatePaid(encoded, JsonPrimitive((firstShares[0].amount.minor + 1).toString()),
            "state", "originalTenderAllocations", "0", "shares", "0", "amount", "minor"),
            JsonPrimitive((firstShares[1].amount.minor - 1).toString()),
            "state", "originalTenderAllocations", "0", "shares", "1", "amount", "minor")
        // Row total still agrees with pricing, but the original payment columns no longer do.
        assertPaidFailure(SalesSnapshotFailure.INCONSISTENT_STATE) { SalesSnapshotCodec.decodePaidSale(shiftedColumn, sale.paidBinding()) }
        val otherQuote = fixture.data.quote("0.33333333", fixture.data.quantity(3), taxRate = "14", portions = 2)
        assertEquals(sale.quote.payable, otherQuote.payable)
        // Same payable is insufficient: different original portion/component keys cannot back these rows.
        assertPaidFailure(SalesSnapshotFailure.INCONSISTENT_STATE) {
            SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, JsonPrimitive(StoredPricingSnapshotCodec.encode(otherQuote)),
                "state", "quote"), sale.paidBinding())
        }
        val futurePricing = StoredPricingSnapshotCodec.encode(sale.quote).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertPaidRejected { SalesSnapshotCodec.decodePaidSale(mutatePaid(encoded, JsonPrimitive(futurePricing), "state", "quote"), sale.paidBinding()) }
    }

    @Test fun restoredFactOwnsNonmutableListsAndEncodingReusesTheExistingSaleValidator() = runTest {
        val sale = paidSaleFixture().finalized.sale
        val encoded = SalesSnapshotCodec.encodePaidSale(sale)
        val restored = SalesSnapshotCodec.decodePaidSale(encoded, sale.paidBinding())
        assertFails { (restored.payments as MutableList<*>).clear() }
        assertFails { (restored.originalTenderAllocations as MutableList<*>).clear() }
        assertFails { (restored.originalTenderAllocations.first().shares as MutableList<*>).clear() }
        assertFails { (restored.quote.lines as MutableList<*>).clear() }
        assertEquals(encoded, SalesSnapshotCodec.encodePaidSale(restored))
        // Deliberately invalid internal copy for the encoder guard; not a payment action or positive fixture.
        val incomplete = PaidSale(sale.id, sale.scope, sale.orderId, sale.originalCreatorId, sale.collectorId,
            sale.collectingSessionId, sale.quote, sale.stockIssueId, sale.payments.dropLast(1), sale.originalTenderAllocations)
        assertPaidFailure(SalesSnapshotFailure.INCONSISTENT_STATE) { SalesSnapshotCodec.encodePaidSale(incomplete) }
        assertEquals(encoded, SalesSnapshotCodec.encodePaidSale(sale))
    }
}

private data class PaidSaleFixture(val data: SalesFixture, val source: RetailFixture, val finalized: FinalizedSale)

/** All positive facts come from real public domain transitions; identities/admission are synthetic test fixtures. */
private suspend fun paidSaleFixture(): PaidSaleFixture {
    val f = SalesFixture()
    val source = f.retail(f.quote("0.33333333", f.quantity(3), taxRate = "14", portions = 2), begin = false)
    val offered = Orders.offerHandoff(f.command(SecurityAction.ORDER_HANDOFF, source.order.id, source.order.revision),
        source.order, source.order.revision, f.nextId(), f.other, f.evidence()).value
    source.order = Orders.acceptHandoff(f.command(SecurityAction.ORDER_HANDOFF, offered.id, offered.revision, actor = f.other),
        offered, offered.revision, checkNotNull(offered.handoff).id, f.evidence(f.other)).value
    source.session = f.openCash(actor = f.other)
    source.begin()
    source.cash(money(80), money(100), money(20))
    val tender = f.external().copy(policyRevision = Revision(9_007_199_254_740_993L))
    val external = source.start(source.collection.remainingPayable, tender, "original payer evidence")
    source.record(external, CollectionOutcome.ExternalAttested(f.externalEvidence(f.other)))
    return PaidSaleFixture(f, source, source.finalize().value)
}

private fun PaidSale.paidBinding(): SalesSnapshotBinding =
    SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, scope, id, SalesSnapshotCodec.PAID_SALE_REVISION)

private inline fun assertPaidRejected(block: () -> Unit): SalesSnapshotRejectedException =
    assertFailsWith<SalesSnapshotRejectedException>(block = block).also { assertEquals(it.failure.name, it.message) }

private inline fun assertPaidFailure(expected: SalesSnapshotFailure, block: () -> Unit) {
    assertEquals(expected, assertPaidRejected(block).failure)
}

/** Preserve property order and exact scalar forms while corrupting one explicitly selected path. */
private fun mutatePaid(encoded: String, replacement: JsonElement, vararg path: String): String {
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
