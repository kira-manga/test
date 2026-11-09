package com.ukkera.pos.sales.store.independent.immutablesalesdocuments

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.AdjustmentStage
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.DiscountValue
import com.ukkera.pos.pricing.EligibleDiscountPortion
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceAdjustment
import com.ukkera.pos.pricing.PricingProfiles
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.SalesPriceSourceReference
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Frozen v1 F-MIXED arithmetic and F-ZERO monetary basis, over canonical DATA only.
 * The zero-valued legacy envelope is deliberately not an authentic authorized
 * F-ZERO original. It binds source-loader and grant-refusal seams without adding
 * tender policy, paid-source authority, numbering, historical labels or delivery.
 * Generated IDs are controlled DATA coordinates, not original/native evidence.
 */
internal object ImmutableSalesDocumentSourceData {
    val scope = BranchScope(
        DomainId("bc8d81a7-e07c-4a0a-bd66-946c6cb09861"),
        DomainId("254a2acd-c196-4c7a-ae76-0d1d62b5875b"),
        DomainId("8247a02f-c330-4f1e-a2cc-bf1e5a974ef0"),
    )
    val originalOperator = DomainId("975ddf8b-a7d4-4211-9675-e8016c49fa54")
    val collector = DomainId("4a6b5ce7-a660-4a6d-b3a4-351e71d0bfec")
    val reader = DomainId("d7ec6079-6f5f-4d04-842b-54b2d8c8ec60")
    val otherBranch = DomainId("58026fdb-23ee-4b2d-a5f7-6f4a793e18aa")
    val portions = listOf(
        DomainId("60d68626-c2eb-447e-95a6-61215a47ee9e"),
        DomainId("d87a9486-f5ac-4fbb-b8dd-d40545beeb25"),
    )
    val saleId = coordinate(1)
    val orderId = coordinate(2)
    val unitId = coordinate(3)
    val observedAt = UtcInstant(Instant.parse("2026-10-18T10:20:00Z"))
    val saleKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId)
    val entitlementKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId)

    fun coordinate(value: Int): DomainId = DomainId("8a000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
    fun money(minor: Long): Money = Money(Currency.EGP, minor)

    fun quote(zeroPayable: Boolean = false): PricingSnapshot {
        val limits = if (zeroPayable) DiscountLimits(money(34200), PercentageRate.parse("100"))
            else DiscountLimits.none(Currency.EGP)
        val tax = TaxSnapshot(coordinate(4), Revision(0), "SYNTHETIC-NONFISCAL", PercentageRate.parse("14"))
        val lines = listOf(
            ResolvedPriceLine(coordinate(5), portions[0], Revision(0), Quantity(unitId, 1, 0),
                UnitPrice.parse(Currency.EGP, "142.50000000"), TaxInclusion.INCLUSIVE, tax, limits),
            ResolvedPriceLine(coordinate(6), portions[1], Revision(0), Quantity(unitId, 1, 0),
                UnitPrice.parse(Currency.EGP, "175.00000000"), TaxInclusion.EXCLUSIVE, tax, limits),
        )
        val adjustments = if (zeroPayable) listOf(PriceAdjustment(coordinate(7), Revision(0),
            AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT, DiscountValue.FixedPayable(money(34200)),
            portions.map { EligibleDiscountPortion(it) }, limits)) else emptyList()
        return OperationalPricingEngine.calculate(PricingRequest(orderId, Revision(0), Currency.EGP,
            lines, adjustments = adjustments, discountLimits = limits))
    }

    fun legacyZeroSale(
        originalCreator: DomainId = originalOperator,
        priceReference: SalesPriceSourceReference? = null,
    ): PaidSale {
        val quote = quote(zeroPayable = true)
        val state = buildJsonObject {
            put("orderId", orderId.value)
            put("originalCreatorId", originalCreator.value)
            put("collectorId", collector.value)
            put("collectingSessionId", coordinate(8).value)
            put("quote", StoredPricingSnapshotCodec.encode(quote))
            put("stockIssueId", coordinate(9).value)
            put("payments", JsonArray(emptyList()))
            put("originalTenderAllocations", emptyTenderRows(quote))
            if (priceReference != null) put("priceSourceReference",
                Json.encodeToJsonElement(SalesPriceSourceReference.serializer(), priceReference))
        }
        return SalesSnapshotCodec.decodePaidSale(envelope(SalesSnapshotKind.PAID_SALE, state,
            schemaVersion = if (priceReference == null) 1 else 2),
            SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, scope, saleId, Revision(0)))
    }

    /** Consumed cursors are controlled advanced-rights DATA, not a performed return. */
    fun entitlementBody(sale: PaidSale, consumed: Boolean = false): String {
        val saleState = Json.parseToJsonElement(SalesSnapshotCodec.encodePaidSale(sale)).let {
            (it as JsonObject).getValue("state")
        }
        val state = buildJsonObject {
            put("sale", saleState)
            put("returnProfile", PricingProfiles.STORED_RETURN_V1)
            put("cursors", JsonArray(sale.quote.lines.map { line -> buildJsonObject {
                put("portionId", line.portionId.value)
                put("consumedQuantity", Json.encodeToJsonElement(Quantity.serializer(),
                    Quantity(line.quantity.unitId, if (consumed) line.quantity.scaled else 0, line.quantity.scale)))
            } }))
            put("remainingComponentTenders", emptyTenderRows(sale.quote))
        }
        return envelope(SalesSnapshotKind.RECEIPT_ENTITLEMENT, state, Revision(if (consumed) 1 else 0),
            if (sale.priceSourceReference == null) 1 else 2)
    }

    fun legacyData(
        original: PaidSale = legacyZeroSale(),
        entitlementSale: PaidSale = original,
        consumed: Boolean = false,
    ): ImmutableSalesReadData = ImmutableSalesReadData(scope,
        listOf(saleRow(original), stored(entitlementKey, entitlementBody(entitlementSale, consumed),
            Revision(if (consumed) 2 else 1))),
        mapOf(
            LocalSalesStoreProfile.unique("sale", saleId) to orderId,
            LocalSalesStoreProfile.unique("sale_order", orderId) to saleId,
            LocalSalesStoreProfile.unique("return_entitlement", saleId) to saleId,
        ))

    fun saleRow(sale: PaidSale): StoredAggregate = stored(saleKey, SalesSnapshotCodec.encodePaidSale(sale))
    fun stored(key: AggregateKey, body: String, generation: Revision = Revision(1),
        schema: String = LocalSalesStoreProfile.schema(key.kind)): StoredAggregate {
        val payload = StoredBytes(body.encodeToByteArray())
        return StoredAggregate(key, generation, schema, payload, sha256(payload.copyBytes()))
    }

    private fun emptyTenderRows(quote: PricingSnapshot): JsonArray = JsonArray(quote.components.map { component ->
        buildJsonObject { put("componentKey", component.key); put("shares", JsonArray(emptyList())) }
    })

    private fun envelope(kind: SalesSnapshotKind, state: JsonElement, revision: Revision = Revision(0),
        schemaVersion: Int = 1): String = buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("kind", kind.name)
        put("scope", buildJsonObject {
            put("organizationId", scope.organizationId.value)
            put("companyId", scope.companyId.value)
            put("branchId", scope.branchId.value)
        })
        put("id", saleId.value)
        put("revision", Json.encodeToJsonElement(Revision.serializer(), revision))
        put("state", state)
    }.toString()
}

/** No write API, stock/native access, original producer or authorization exists here. */
internal class ImmutableSalesReadData(
    override val scope: BranchScope,
    rows: List<StoredAggregate> = emptyList(),
    claims: Map<ClaimKey, DomainId> = emptyMap(),
) : CommandReadView {
    val rows = rows.toList()
    val claims = claims.toMap()
    val aggregateRequests = mutableListOf<AggregateKey>()
    val claimRequests = mutableListOf<ClaimKey>()
    val originalRequests = mutableListOf<OperationId>()

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        aggregateRequests += key
        return rows.singleOrNull { it.key == key }
    }
    override suspend fun claim(key: ClaimKey): DomainId? {
        claimRequests += key
        return claims[key]
    }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        originalRequests += operationId
        return null
    }

    fun snapshot(): Pair<List<List<Any>>, Map<ClaimKey, DomainId>> = rows.map {
        listOf(it.key, it.revision, it.schema, it.digest, it.payload.copyBytes().toList())
    } to claims.toMap()
}
