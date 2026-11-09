package com.ukkera.pos.sales.store.independent.immutablesalesdocuments

import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.sales.SalesPriceSourceReference
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotRejectedException
import com.ukkera.pos.sales.store.LocalCollectionQueryV1
import com.ukkera.pos.sales.store.LocalCollectionReadCodec
import com.ukkera.pos.sales.store.LocalCollectionReadHandler
import com.ukkera.pos.sales.store.LocalReturnEntitlementProvenanceProfile
import com.ukkera.pos.sales.store.LocalSalesSourceUnavailable
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.sales.store.readEntitlement
import com.ukkera.pos.sales.store.readReturnSaleSource
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen immutable-sales-documents v1 SUPPORT only. The actual source readers
 * and handler receive inert DATA; no native paid original, current employee
 * authority, protected result release, persistence, admitted RPC or print binds.
 * All IRD whole cases remain NOT_RUN. DATA nonmutation is not a ledger-delta run.
 */
class ImmutableSalesDocumentReadSupportingTest {
    private val f = ImmutableSalesDocumentSourceData
    private val handler = LocalCollectionReadHandler(LocalStockRepository())
    private val request get() = LocalCollectionReadCodec.request(LocalCollectionQueryV1.PaidSale(f.saleId))

    @Test
    fun ird01SupportFrozenMixedOriginalQuoteHasExactInclusiveAndExclusiveAmounts() {
        val quote = f.quote()
        assertEquals(34200L, quote.beforeDiscountPayable.minor)
        assertEquals(0L, quote.advertisedDiscount.minor)
        assertEquals(34200L, quote.payable.minor)
        assertEquals(listOf(14250L, 19950L), quote.lines.map { it.payable.minor })
        assertEquals(30000L, quote.components.filter { it.kind == PriceComponentKind.ITEM_NET }.sumOf { it.posted.minor })
        assertEquals(4200L, quote.components.filter { it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.posted.minor })
        assertEquals(34200L, quote.components.sumOf { it.posted.minor })
        // Exact F-MIXED mathematics only: no original sale, tender/cash, labels,
        // actors, number, fulfillment or protected full document is established.
    }

    @Test
    fun ird08And13SupportZeroDataPreservesExplicitAmountsButCannotGrantItsOwnRead() = runTest {
        val data = f.legacyData()
        val before = data.snapshot()
        val original = readReturnSaleSource(data, f.saleId).sale.value
        assertEquals(34200L, original.quote.beforeDiscountPayable.minor)
        assertEquals(34200L, original.quote.advertisedDiscount.minor)
        assertEquals(0L, original.quote.payable.minor)
        assertEquals(0L, original.quote.components.sumOf { it.posted.minor })
        assertTrue(original.payments.isEmpty())
        assertEquals(f.originalOperator, original.originalCreatorId)
        assertEquals(f.collector, original.collectorId)
        val normalized = handler.normalize(request.payload)
        val context = context(normalized)
        val prepared = handler.prepare(normalized, data, context)
        val required = prepared.requirements.single()
        assertEquals(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM), required.permission)
        assertEquals(f.scope, required.scope)
        assertEquals(f.saleId, required.binding?.documentId)
        assertEquals(Revision(0), required.binding?.revision)
        assertEquals(context.semanticDigest, required.binding?.payloadDigest)
        assertNull(required.binding?.operationId)
        assertFailsWith<IllegalStateException> { prepared.project(emptyList(), f.observedAt) }
        assertEquals(before, data.snapshot())
        assertTrue(data.originalRequests.isEmpty())
        // This does not authorize a zero sale or release even a limited preview.
    }

    @Test
    fun ird22SupportCompleteSourceAbsenceStillRequiresProtectedReadAuthority() = runTest {
        val data = ImmutableSalesReadData(f.scope)
        val before = data.snapshot()
        val normalized = handler.normalize(request.payload)
        val prepared = handler.prepare(normalized, data, context(normalized))
        assertEquals(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM),
            prepared.requirements.single().permission)
        assertEquals(f.saleId, prepared.requirements.single().binding?.documentId)
        assertFailsWith<IllegalStateException> { prepared.project(emptyList(), f.observedAt) }
        assertTrue(data.aggregateRequests.contains(f.entitlementKey))
        assertTrue(data.aggregateRequests.contains(LocalReturnEntitlementProvenanceProfile.locator(f.saleId)))
        assertEquals(before, data.snapshot())
        assertTrue(data.originalRequests.isEmpty())
    }

    @Test
    fun ird22SupportSurvivingRightsClaimsOrLocatorCannotBecomeCleanSourceAbsence() = runTest {
        val baseline = f.legacyData()
        val orphanRows = listOf(
            baseline.rows.single { it.key == f.entitlementKey },
            f.stored(LocalReturnEntitlementProvenanceProfile.locator(f.saleId), "{}"),
        )
        val orphanClaims = listOf(
            LocalSalesStoreProfile.unique("sale", f.saleId) to f.orderId,
            LocalSalesStoreProfile.unique("return_entitlement", f.saleId) to f.saleId,
            LocalReturnEntitlementProvenanceProfile.locatorClaim(f.saleId) to f.saleId,
        )
        val candidates = orphanRows.map { ImmutableSalesReadData(f.scope, rows = listOf(it)) } +
            orphanClaims.map { ImmutableSalesReadData(f.scope, claims = mapOf(it)) }
        for (data in candidates) {
            val before = data.snapshot()
            val normalized = handler.normalize(request.payload)
            assertFailsWith<LocalSalesSourceUnavailable> { handler.prepare(normalized, data, context(normalized)) }
            assertEquals(before, data.snapshot())
            assertTrue(data.originalRequests.isEmpty())
        }
    }

    @Test
    fun ird22And25SupportCorruptUnsupportedOrForeignOriginalCannotProducePreparedContent() = runTest {
        val baseline = f.legacyData()
        val encoded = SalesSnapshotCodec.encodePaidSale(f.legacyZeroSale())
        val objectData = Json.parseToJsonElement(encoded).jsonObject
        val wrongScope = JsonObject(objectData.getValue("scope").jsonObject +
            ("branchId" to JsonPrimitive(f.otherBranch.value)))
        val bodies = listOf(
            "not-json",
            JsonObject(objectData - "state").toString(),
            JsonObject(objectData + ("schemaVersion" to JsonPrimitive(999))).toString(),
            JsonObject(objectData + ("scope" to wrongScope)).toString(),
        )
        for (body in bodies) {
            val data = ImmutableSalesReadData(f.scope,
                baseline.rows.filterNot { it.key == f.saleKey } + f.stored(f.saleKey, body), baseline.claims)
            val before = data.snapshot()
            val normalized = handler.normalize(request.payload)
            val failure = assertFailsWith<SalesSnapshotRejectedException> {
                handler.prepare(normalized, data, context(normalized))
            }
            assertFalse(encoded in failure.message.orEmpty())
            assertEquals(before, data.snapshot())
            assertTrue(data.originalRequests.isEmpty())
        }
        val laterRow = ImmutableSalesReadData(f.scope,
            baseline.rows.filterNot { it.key == f.saleKey } + f.stored(f.saleKey, encoded, Revision(2)), baseline.claims)
        val before = laterRow.snapshot()
        val normalized = handler.normalize(request.payload)
        assertFailsWith<SalesSnapshotRejectedException> { handler.prepare(normalized, laterRow, context(normalized)) }
        assertEquals(before, laterRow.snapshot())
    }

    @Test
    fun ird07And22SupportExplicitLegacyStillRequiresItsCurrentIdenticalSaleEntitlement() = runTest {
        val baseline = f.legacyData()
        val missing = ImmutableSalesReadData(f.scope, baseline.rows.filterNot { it.key == f.entitlementKey }, baseline.claims)
        val replaced = f.legacyData(entitlementSale = f.legacyZeroSale(originalCreator = f.reader))
        val normalized = handler.normalize(request.payload)
        for (data in listOf(missing, replaced)) {
            val before = data.snapshot()
            assertFailsWith<LocalSalesSourceUnavailable> { handler.prepare(normalized, data, context(normalized)) }
            assertTrue(f.entitlementKey in data.aggregateRequests)
            assertEquals(before, data.snapshot())
            assertTrue(data.originalRequests.isEmpty())
        }
    }

    @Test
    fun ird07And09SupportAdvancedLegacyRightsKeepCurrentBindingAndUnchangedOriginalBasis() = runTest {
        val data = f.legacyData(consumed = true)
        val before = data.snapshot()
        val original = readReturnSaleSource(data, f.saleId).sale.value
        val entitlement = readEntitlement(data, f.saleId).entitlement.value
        assertEquals(Revision(1), entitlement.revision)
        assertTrue(entitlement.cursors.all { it.consumedQuantity == it.originalQuantity })
        assertEquals(SalesSnapshotCodec.encodePaidSale(original), SalesSnapshotCodec.encodePaidSale(entitlement.sale))
        assertEquals(34200L, original.quote.beforeDiscountPayable.minor)
        assertEquals(34200L, original.quote.advertisedDiscount.minor)
        assertEquals(0L, original.quote.payable.minor)
        val normalized = handler.normalize(request.payload)
        val prepared = handler.prepare(normalized, data, context(normalized))
        assertEquals(entitlement.revision, prepared.requirements.single().binding?.revision)
        assertFailsWith<IllegalStateException> { prepared.project(emptyList(), f.observedAt) }
        assertEquals(before, data.snapshot())
        assertTrue(data.originalRequests.isEmpty())
        // The advanced DATA did not execute a return or qualify normalized ancestry.
    }

    @Test
    fun ird22SupportDeclaredPriceSourceCannotFallBackToAbsentLegacyCompanion() = runTest {
        val reference = SalesPriceSourceReference(f.coordinate(10), sha256("absent synthetic price source".encodeToByteArray()))
        val data = f.legacyData(original = f.legacyZeroSale(priceReference = reference))
        val before = data.snapshot()
        val normalized = handler.normalize(request.payload)
        assertFailsWith<LocalSalesSourceUnavailable> { handler.prepare(normalized, data, context(normalized)) }
        assertTrue(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id) in data.aggregateRequests)
        assertFalse(f.entitlementKey in data.aggregateRequests)
        assertEquals(before, data.snapshot())
        assertTrue(data.originalRequests.isEmpty())
    }

    @Test
    fun ird13And25SupportCallerGrantsAndOriginalReplacementsAreNotPaidSaleQueryFields() {
        val base = Json.parseToJsonElement(request.payload.copyBytes().decodeToString()).jsonObject
        val deniedFields = listOf("callerGrant", "replacementPayable", "replacementCollector", "employeeCredentialMaterial")
        for (field in deniedFields) {
            val canary = "SYNTHETIC-DENIED-QUERY-FIELD-$field"
            val body = StoredBytes(JsonObject(base + (field to JsonPrimitive(canary))).toString().encodeToByteArray())
            val failure = assertFailsWith<IllegalArgumentException> { handler.normalize(body) }
            assertFalse(canary in failure.message.orEmpty())
        }
    }

    private fun context(normalized: StoredBytes) = ReadContext(f.scope, f.reader, handler.key,
        queryDigest(f.scope, f.reader, handler.key, normalized))
}
