package com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1

import com.ukkera.pos.catalog.store.LocalCatalogReleaseMemberV1
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalProductAttributeV1
import com.ukkera.pos.catalog.store.LocalProductAttributeValueV1
import com.ukkera.pos.catalog.store.LocalProductStatusV1
import com.ukkera.pos.catalog.store.LocalPublishedCatalogReleaseV1
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalVariantAxisV1
import com.ukkera.pos.catalog.store.LocalVariantValueV1
import com.ukkera.pos.catalog.store.localCatalogReleaseReference
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant

/**
 * Supported content-only projection of unchanged CSR-FROZEN-1 semantic fixtures.
 * Publication aliases become disposable supplied-content IDs, NOT native originals.
 * Digests hash the exact instantiated canonical SKU bytes, never a claimed native cut.
 * Required wire-only IDs/status/tax/effectivity below are explicit inert carriers.
 * No unit, category, tax, grant, native custody or completed-history qualification follows.
 * The frozen allowed-combination matrix has no DTO field and remains UNBOUND.
 * BULK-P0 is deliberately not translated: its 65-value axis exceeds the DTO schema.
 */
internal object CatalogReleaseSourceFrozenFixture {
    internal val company: CompanyScope = CompanyScope(metadataId(1),
        DomainId("10000000-0000-4000-8000-000000000001"))
    internal val otherCompany: CompanyScope = CompanyScope(company.organizationId,
        DomainId("20000000-0000-4000-8000-000000000001"))
    internal val branchId: DomainId = DomainId("11000000-0000-4000-8000-000000000001")
    internal val catalogId: DomainId = DomainId("30000000-0000-4000-8000-000000000001")
    internal val otherCatalogId: DomainId = DomainId("40000000-0000-4000-8000-000000000001")

    private data class AxisInput(val label: String, val values: List<String>)
    private data class ProductInput(val alias: String, val id: DomainId, val revision: Revision,
        val names: LocalNamesV1, val category: String, val material: String, val axes: List<AxisInput>,
        val allowedCombinations: List<Map<String, String>>)
    private data class SkuInput(val alias: String, val id: DomainId, val revision: Revision,
        val productAlias: String, val names: LocalNamesV1, val selected: Map<String, String>,
        val barcode: String, val priceMinor: Long)
    private data class ReleaseInput(val alias: String, val revision: Revision,
        val previousAlias: String?, val memberAliases: List<String>)

    private val productInputs = listOf(
        ProductInput("SHIRT-P0", DomainId("50000000-0000-4000-8000-000000000001"), Revision(0),
            LocalNamesV1("Linen shirt", "قميص كتان"), "clothing", "linen",
            listOf(AxisInput("size", listOf("M", "L")), AxisInput("color", listOf("BLUE", "BLACK"))),
            listOf(mapOf("size" to "M", "color" to "BLUE"), mapOf("size" to "L", "color" to "BLACK"))),
        ProductInput("SHIRT-P1", DomainId("50000000-0000-4000-8000-000000000001"), Revision(1),
            LocalNamesV1("Linen shirt, relaxed", "قميص كتان واسع"), "clothing", "linen",
            listOf(AxisInput("size", listOf("M", "L")), AxisInput("color", listOf("BLUE", "BLACK"))),
            listOf(mapOf("size" to "M", "color" to "BLUE"), mapOf("size" to "L", "color" to "BLACK"))),
        ProductInput("SHIRT-P2", DomainId("50000000-0000-4000-8000-000000000001"), Revision(2),
            LocalNamesV1("Linen shirt, relaxed fit", "قميص كتان بقصة واسعة"), "clothing", "linen",
            listOf(AxisInput("size", listOf("M", "L")), AxisInput("color", listOf("BLUE", "BLACK"))),
            listOf(mapOf("size" to "M", "color" to "BLUE"), mapOf("size" to "L", "color" to "BLACK"))),
        ProductInput("BAG-P0", DomainId("60000000-0000-4000-8000-000000000001"), Revision(0),
            LocalNamesV1("Cotton bag", "حقيبة قطنية"), "accessories", "cotton",
            listOf(AxisInput("style", listOf("STANDARD"))),
            listOf(mapOf("style" to "STANDARD"))),
    )
    private val skuInputs = listOf(
        SkuInput("SKU-BLUE-0", DomainId("70000000-0000-4000-8000-000000000001"), Revision(0), "SHIRT-P0",
            LocalNamesV1("Blue shirt M", "قميص أزرق M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 10000L),
        SkuInput("SKU-BLUE-1", DomainId("70000000-0000-4000-8000-000000000001"), Revision(1), "SHIRT-P1",
            LocalNamesV1("Blue shirt M", "قميص أزرق M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 10000L),
        SkuInput("SKU-BLUE-2", DomainId("70000000-0000-4000-8000-000000000001"), Revision(2), "SHIRT-P2",
            LocalNamesV1("Blue shirt M", "قميص أزرق M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 10000L),
        SkuInput("SKU-BLUE-3", DomainId("70000000-0000-4000-8000-000000000001"), Revision(3), "SHIRT-P2",
            LocalNamesV1("Blue shirt / M", "قميص أزرق / M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 12000L),
        SkuInput("SKU-BLUE-4", DomainId("70000000-0000-4000-8000-000000000001"), Revision(4), "SHIRT-P2",
            LocalNamesV1("Blue shirt / M", "قميص أزرق / M"), mapOf("size" to "M", "color" to "BLUE"), "00011001", 12500L),
        SkuInput("SKU-BLACK-0", DomainId("80000000-0000-4000-8000-000000000001"), Revision(0), "SHIRT-P0",
            LocalNamesV1("Black shirt L", "قميص أسود L"), mapOf("size" to "L", "color" to "BLACK"), "00011002", 10000L),
        SkuInput("SKU-BLACK-1", DomainId("80000000-0000-4000-8000-000000000001"), Revision(1), "SHIRT-P1",
            LocalNamesV1("Black shirt L", "قميص أسود L"), mapOf("size" to "L", "color" to "BLACK"), "00011002", 10000L),
        SkuInput("SKU-BLACK-2", DomainId("80000000-0000-4000-8000-000000000001"), Revision(2), "SHIRT-P2",
            LocalNamesV1("Black shirt / L", "قميص أسود / L"), mapOf("size" to "L", "color" to "BLACK"), "00011002", 11000L),
        SkuInput("SKU-BAG-0", DomainId("90000000-0000-4000-8000-000000000001"), Revision(0), "BAG-P0",
            LocalNamesV1("Cotton bag", "حقيبة قطنية"), mapOf("style" to "STANDARD"), "00021001", 3000L),
        SkuInput("SKU-BAG-1", DomainId("90000000-0000-4000-8000-000000000001"), Revision(1), "BAG-P0",
            LocalNamesV1("Cotton bag, natural", "حقيبة قطنية طبيعية"), mapOf("style" to "STANDARD"), "00021001", 3500L),
    )
    private val releaseInputs = listOf(
        ReleaseInput("REL-0", Revision(0), null, listOf("SKU-BLUE-3", "SKU-BLACK-2", "SKU-BAG-0")),
        ReleaseInput("REL-1", Revision(1), "REL-0", listOf("SKU-BAG-1", "SKU-BLACK-2", "SKU-BLUE-3")),
        ReleaseInput("REL-2", Revision(2), "REL-1", listOf("SKU-BLUE-3", "SKU-BAG-1", "SKU-BLACK-2")),
        ReleaseInput("REL-3", Revision(3), "REL-2", listOf("SKU-BAG-1", "SKU-BLUE-4")),
        ReleaseInput("REL-4", Revision(4), "REL-3", listOf("SKU-BLUE-4", "SKU-BAG-1")),
    )

    private val axisIds = mapOf("size" to metadataId(201), "color" to metadataId(202), "style" to metadataId(203))
    private val categoryIds = mapOf("clothing" to metadataId(301), "accessories" to metadataId(302))
    private val baseUnitId = metadataId(401)

    /** Retained as semantic evidence only; never used as an accepting precheck. */
    internal val declaredAllowedCombinations: Map<String, List<Map<String, String>>> =
        productInputs.associate { it.alias to it.allowedCombinations }

    private val productDefinitions: Map<String, LocalPublishedProductV1> = productInputs.associate { input ->
        input.alias to LocalPublishedProductV1(input.id, input.revision, input.names,
            listOf(categoryIds.getValue(input.category)), input.axes.map { axis ->
                LocalVariantAxisV1(axisIds.getValue(axis.label), Code.exact(axis.label), axis.values.map { Code.exact(it) })
            }, listOf(LocalProductAttributeV1(Code.exact("material"),
                LocalProductAttributeValueV1.Text(Code.exact(input.material)))), LocalProductStatusV1.ACTIVE)
    }

    internal val definitions: Map<String, LocalPublishedSkuV1> = skuInputs.withIndex().associate { (index, input) ->
        val product = productDefinitions.getValue(input.productAlias)
        // Regular frozen products explicitly agree on all these SKU-owned traits:
        // STOCKED/UNIT/precision0, nonserial/nonlot/noexpiry, RETAIL, purchasable/sellable,
        // no production/recipe/kit/deposit, tax included and category STANDARD.
        // Product status and the old SKU catalogRevision are wire carriers, not inferred counters.
        input.alias to LocalPublishedSkuV1(company, metadataId(101 + index), input.id, product.id,
            Revision(0), input.revision, input.names, "CSR-${input.id.value}", listOf(input.barcode),
            baseUnitId, 0, LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, true, true,
            emptyList(), listOf(LocalPriceV1(metadataId(501 + index), Revision(0), null, 0,
                Quantity(baseUnitId, 0L, 0), effectiveFrom = UtcInstant.parseCanonical("1970-01-01T00:00:00Z"),
                unitPrice = UnitPrice(Currency.EGP, exactEgp(input.priceMinor)), inclusion = TaxInclusion.INCLUSIVE,
                tax = TaxSnapshot(metadataId(601), Revision(0), "STANDARD", PercentageRate.parse("0")))),
            productDefinition = product,
            variantValues = input.selected.map { (axis, value) -> LocalVariantValueV1(axisIds.getValue(axis), Code.exact(value)) })
    }

    internal val releases: List<LocalPublishedCatalogReleaseV1> = run {
        val byAlias = linkedMapOf<String, LocalPublishedCatalogReleaseV1>()
        for ((index, input) in releaseInputs.withIndex()) {
            val previous = input.previousAlias?.let { localCatalogReleaseReference(byAlias.getValue(it)) }
            byAlias[input.alias] = LocalPublishedCatalogReleaseV1(company, catalogId, metadataId(701 + index),
                input.revision, previous, input.memberAliases.map { member(sku(it)) })
        }
        byAlias.values.toList()
    }

    internal fun sku(alias: String): LocalPublishedSkuV1 = definitions.getValue(alias)
    internal fun product(alias: String): LocalPublishedProductV1 = productDefinitions.getValue(alias)
    internal fun axisId(label: String): DomainId = axisIds.getValue(label)

    internal fun member(sku: LocalPublishedSkuV1): LocalCatalogReleaseMemberV1 {
        val product = checkNotNull(sku.productDefinition)
        return LocalCatalogReleaseMemberV1(sku.publicationId, digest(sku), sku.skuId, sku.skuRevision, product.id, product.revision)
    }

    internal fun digest(sku: LocalPublishedSkuV1): PayloadDigest {
        val canonical = LocalConfigurationCodec.canonicalPublication(sku)
        return try { sha256(canonical) } finally { canonical.fill(0) }
    }

    internal fun metadataId(index: Int): DomainId =
        DomainId("d0000000-0000-4000-8000-${index.toString().padStart(12, '0')}")

    private fun exactEgp(minor: Long): ExactDecimal =
        ExactDecimal.parse("${minor / 100}.${(minor % 100).toString().padStart(2, '0')}")
}
