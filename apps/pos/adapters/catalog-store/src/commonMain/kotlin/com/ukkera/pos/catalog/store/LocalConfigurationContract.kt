package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.CatalogAttribute
import com.ukkera.pos.catalog.CatalogAttributeValue
import com.ukkera.pos.catalog.CatalogProduct
import com.ukkera.pos.catalog.CatalogStatus
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.VariantAxis
import com.ukkera.pos.catalog.VariantValue
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public object LocalConfigurationProfile {
    public val COMMAND: CommandKey = CommandKey("local.configuration.write", 1)
    public val QUERY: ReadKey = ReadKey("local.configuration.read", 1)
    public const val SKU: String = "local.catalog.sku"
    public const val PUBLICATION: String = "local.catalog.publication"
    public const val WAREHOUSE: String = "local.catalog.warehouse"
    public const val BASIS: String = "local.purchasing.basis"
    public const val TENDER: String = "local.payment.tender"
    public const val SUPPLIER: String = "local.purchasing.supplier"
    public const val SALES_ROUTE: String = "local.catalog.sales-route"
    public const val CATALOG_CURSOR: String = "local.catalog.cursor"
    public const val SOURCE_CLAIM: String = "local.configuration.source"
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun schema(kind: String): String = "$kind/v1"
}

@Serializable
public data class LocalNamesV1(public val english: String, public val arabic: String) {
    init {
        require(english.isNotBlank() || arabic.isNotBlank())
        require(english.length <= 160 && arabic.length <= 160)
        require((english + arabic).none { it.code < 0x20 || it.code == 0x7f })
    }
}

@Serializable
public enum class LocalTrackingV1 { NONE, QUANTITY, LOT, SERIAL }
@Serializable
public enum class LocalFulfillmentV1 { RETAIL, MADE_TO_ORDER, PREPARED_BATCH, VIRTUAL_KIT, ASSEMBLED_KIT, SERVICE }

/** Finite wire capacity, not a company permission or an import limit chosen by a request. */
public object LocalCatalogProductProfileV1 {
    public const val MAX_CATEGORIES: Int = 32
    public const val MAX_AXES: Int = 16
    public const val MAX_AXIS_VALUES: Int = 128
    public const val MAX_ALLOWED_VARIANT_COMBINATIONS: Int = 256
    public const val MAX_ATTRIBUTES: Int = 32
    public const val MAX_PRODUCT_BYTES: Int = 32 * 1024
}

@Serializable
public enum class LocalProductStatusV1 { ACTIVE, ARCHIVED }

/** Exact declared labels. The owned list preserves order; no Cartesian generator is implied. */
@Serializable(with = LocalVariantAxisSerializer::class)
public class LocalVariantAxisV1(public val id: DomainId, public val code: Code, values: List<Code>) {
    public val values: List<Code> = localProductFrozen(values, LocalCatalogProductProfileV1.MAX_AXIS_VALUES)
    public fun domain(): VariantAxis = VariantAxis(id, code, values)
    override fun equals(other: Any?): Boolean = other is LocalVariantAxisV1 && id == other.id && code == other.code && values == other.values
    override fun hashCode(): Int = 31 * (31 * id.hashCode() + code.hashCode()) + values.hashCode()
}

@Serializable
public data class LocalVariantValueV1(public val axisId: DomainId, public val value: Code) {
    public fun domain(): VariantValue = VariantValue(axisId, value)
}

/** Typed descriptive values only; none can execute pricing, stock, policy or payment behavior. */
@Serializable
public sealed interface LocalProductAttributeValueV1 {
    @Serializable @SerialName("TEXT")
    public data class Text(public val value: Code) : LocalProductAttributeValueV1
    @Serializable @SerialName("DECIMAL")
    public data class Decimal(public val value: ExactDecimal) : LocalProductAttributeValueV1
    @Serializable @SerialName("FLAG")
    public data class Flag(public val value: Boolean) : LocalProductAttributeValueV1
}

@Serializable
public data class LocalProductAttributeV1(public val key: Code, public val value: LocalProductAttributeValueV1) {
    public fun domain(): CatalogAttribute = CatalogAttribute(key, when (val selected = value) {
        is LocalProductAttributeValueV1.Text -> CatalogAttributeValue.Text(selected.value)
        is LocalProductAttributeValueV1.Decimal -> CatalogAttributeValue.Decimal(selected.value)
        is LocalProductAttributeValueV1.Flag -> CatalogAttributeValue.Flag(selected.value)
    })
}

/**
 * Independently versioned product content inside an exact signed SKU original. This is not a
 * catalog-release identity, a category-membership authority or a branch activation grant.
 * All fields are explicit and lists are owned immutable snapshots; equality includes BOTH names
 * and every ordered field. Product revision is never derived from SKU, cursor or storage counters.
 */
@Serializable(with = LocalPublishedProductSerializer::class)
public class LocalPublishedProductV1(
    public val id: DomainId, public val revision: Revision, public val names: LocalNamesV1,
    categoryIds: List<DomainId>, variantAxes: List<LocalVariantAxisV1>, attributes: List<LocalProductAttributeV1>,
    public val status: LocalProductStatusV1,
    allowedVariantCombinations: List<List<LocalVariantValueV1>>? = null,
) {
    public val categoryIds: List<DomainId> = localProductFrozen(categoryIds, LocalCatalogProductProfileV1.MAX_CATEGORIES)
    public val variantAxes: List<LocalVariantAxisV1> = localProductFrozen(variantAxes, LocalCatalogProductProfileV1.MAX_AXES)
    public val attributes: List<LocalProductAttributeV1> = localProductFrozen(attributes, LocalCatalogProductProfileV1.MAX_ATTRIBUTES)
    /** Omitted on prior signed bodies; a present matrix declares every allowed complete selection. */
    public val allowedVariantCombinations: List<List<LocalVariantValueV1>>? = allowedVariantCombinations?.let { rows ->
        require(rows.size <= LocalCatalogProductProfileV1.MAX_ALLOWED_VARIANT_COMBINATIONS)
        localProductFrozen(rows.map { localProductFrozen(it, LocalCatalogProductProfileV1.MAX_AXES) },
            LocalCatalogProductProfileV1.MAX_ALLOWED_VARIANT_COMBINATIONS)
    }

    init {
        listOf(names.english, names.arabic).forEach { it.encodeToByteArray(throwOnInvalidSequence = true).fill(0) }
        val bytes = LocalConfigurationCodec.canonicalProductDefinition(this)
        try { require(bytes.size <= LocalCatalogProductProfileV1.MAX_PRODUCT_BYTES) { "Product definition exceeds wire capacity" } }
        finally { bytes.fill(0) }
    }

    /** Explicit display-label projection; full wire equality never discards the other translation. */
    public fun domain(displayName: Code): CatalogProduct {
        require(displayName.original == names.english || displayName.original == names.arabic)
        return CatalogProduct(id, revision, displayName, categoryIds, variantAxes.map { it.domain() },
            attributes.map { it.domain() }, CatalogStatus.valueOf(status.name),
            allowedVariantCombinations = allowedVariantCombinations?.map { row -> row.map { it.domain() } })
    }

    override fun equals(other: Any?): Boolean = other is LocalPublishedProductV1 && id == other.id && revision == other.revision &&
        names == other.names && categoryIds == other.categoryIds && variantAxes == other.variantAxes && attributes == other.attributes &&
        status == other.status && allowedVariantCombinations == other.allowedVariantCombinations
    override fun hashCode(): Int {
        val previousHash = listOf(id, revision, names, categoryIds, variantAxes, attributes, status).hashCode()
        return allowedVariantCombinations?.let { 31 * previousHash + it.hashCode() } ?: previousHash
    }
}

private fun <T> localProductFrozen(values: List<T>, maximum: Int): List<T> {
    require(values.size <= maximum)
    val owned = values.toList()
    require(owned.size <= maximum)
    return LocalProductFrozenList(owned)
}

private class LocalProductFrozenList<T>(private val values: List<T>) : AbstractList<T>() {
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}

@Serializable
public data class LocalUnitConversionV1(
    public val id: DomainId, public val revision: Revision,
    public val sourceUnitId: DomainId, public val targetUnitId: DomainId,
    public val rate: UnitConversionRate, public val dimensionalEvidenceId: DomainId? = null,
) {
    public fun domain(productId: DomainId): ProductUnitConversion = ProductUnitConversion(
        id, revision, productId, sourceUnitId, targetUnitId, rate, dimensionalEvidenceId)
}

/** A published effective price row; percentages/rates are exact operational snapshots, not fiscal certification. */
@Serializable
public data class LocalPriceV1(
    public val ruleId: DomainId, public val revision: Revision,
    public val channel: String?, public val priority: Int,
    public val minimumQuantity: Quantity, public val maximumQuantityExclusive: Quantity? = null,
    public val effectiveFrom: UtcInstant, public val effectiveUntil: UtcInstant? = null,
    public val unitPrice: UnitPrice, public val inclusion: TaxInclusion, public val tax: TaxSnapshot,
) {
    init {
        require(channel == null || channel in setOf("COUNTER", "DINE_IN", "TAKEAWAY", "DELIVERY"))
        require(minimumQuantity.scaled >= 0)
        require(maximumQuantityExclusive == null || (maximumQuantityExclusive.unitId == minimumQuantity.unitId &&
            maximumQuantityExclusive.scale == minimumQuantity.scale && maximumQuantityExclusive > minimumQuantity))
        require(effectiveUntil == null || effectiveUntil > effectiveFrom)
        require(unitPrice.currency == Currency.EGP)
        require(unitPrice.amount >= ExactDecimal.parse("0"))
        require(tax.categoryCode.isNotBlank() && tax.categoryCode.length <= 128 &&
            tax.categoryCode.none { it.code < 0x20 || it.code == 0x7f })
        require(tax.rate.toFraction() >= ExactDecimal.parse("0").toFraction())
    }
}

/**
 * One signed company item publication, independently versioned so a supermarket is not one
 * 64KiB aggregate. It is NOT an unrestricted branch product-create request. A company signature
 * over the canonical definition is mandatory. Current implementation's retail price rows are
 * SKU-specific; wider category/promotion/recipe activation remains separately required.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalPublishedSkuV1(
    public val company: CompanyScope, public val publicationId: DomainId,
    public val skuId: DomainId, public val productId: DomainId,
    public val catalogRevision: Revision, public val skuRevision: Revision,
    public val names: LocalNamesV1, public val code: String, public val barcodes: List<String>,
    public val baseUnitId: DomainId, public val quantityScale: Int,
    public val tracking: LocalTrackingV1, public val fulfillment: LocalFulfillmentV1,
    public val requiresExpiry: Boolean, public val canPurchase: Boolean, public val canSell: Boolean,
    public val active: Boolean, public val conversions: List<LocalUnitConversionV1>,
    public val prices: List<LocalPriceV1>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val returnPromise: LocalRetailReturnPromiseV1? = null,
    /** Absent on legacy publications: raw unit IDs do not establish dimensional authority. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val unitDefinitions: List<@Serializable(with = LocalCatalogUnitSerializer::class) CatalogUnit>? = null,
    /** Omitted on legacy originals: neither a raw product ID nor another revision supplies this body. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val productDefinition: LocalPublishedProductV1? = null,
    /** Explicit selected combination, including an empty list for a new simple product. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val variantValues: List<LocalVariantValueV1>? = null,
) {
    init {
        // This identifier enters an ASCII protocol claim, unlike translated display names.
        // Enforce that grammar during request decoding, before preparation/authorization.
        require(code.length in 1..96 && code.all { it.code in 0x21..0x7e })
        require(quantityScale in 0..6 && barcodes.size <= 32 && barcodes.distinct().size == barcodes.size)
        require(barcodes.all { it.isNotEmpty() && it.length <= 128 && it.all { c -> c.code in 0x21..0x7e } })
        require(conversions.size <= 32 && conversions.map { it.id }.distinct().size == conversions.size)
        require(conversions.all { it.targetUnitId == baseUnitId && it.sourceUnitId != baseUnitId })
        require(prices.size <= 64 && prices.map { it.ruleId }.distinct().size == prices.size)
        require(!canSell || prices.isNotEmpty())
        require(returnPromise == null || canSell && fulfillment == LocalFulfillmentV1.RETAIL &&
            tracking in setOf(LocalTrackingV1.QUANTITY, LocalTrackingV1.LOT, LocalTrackingV1.SERIAL))
        require(prices.all { it.minimumQuantity.unitId == baseUnitId && it.minimumQuantity.scale == quantityScale })
        require(!requiresExpiry || tracking == LocalTrackingV1.LOT)
        require(tracking != LocalTrackingV1.SERIAL || quantityScale == 0)
        require(tracking != LocalTrackingV1.NONE || fulfillment in setOf(LocalFulfillmentV1.SERVICE, LocalFulfillmentV1.VIRTUAL_KIT))
        // Wire presence is distinct from absence. An empty signed extension is
        // representable, but activation still rejects its unresolved base unit.
        require(unitDefinitions == null || (unitDefinitions.size in 0..33 &&
            unitDefinitions.map { it.id }.distinct().size == unitDefinitions.size &&
            unitDefinitions.map { it.code.normalizedKey }.distinct().size == unitDefinitions.size))
        require(variantValues == null || variantValues.size <= LocalCatalogProductProfileV1.MAX_AXES)
    }
}

@Serializable
public data class LocalSignedSkuV1(
    public val definition: LocalPublishedSkuV1,
    public val signingKeyId: DomainId,
    /** Canonical lowercase hexadecimal, 64-byte Ed25519 signature. Never a private key. */
    public val signatureHex: String,
) { init { require(signatureHex.length == 128 && signatureHex.all { it in '0'..'9' || it in 'a'..'f' }) } }

/** Installed trust anchors belong to the trusted provisioning context, never an employee request. */
public fun interface CompanyPublicationSignatureVerifier {
    public fun verify(company: CompanyScope, keyId: DomainId, canonicalDefinition: ByteArray, signatureHex: String): Boolean
}

public object UnavailableCompanyPublicationSignatureVerifier : CompanyPublicationSignatureVerifier {
    override fun verify(company: CompanyScope, keyId: DomainId, canonicalDefinition: ByteArray, signatureHex: String): Boolean = false
}

@Serializable
public data class LocalWarehouseV1(
    public val id: DomainId, public val names: LocalNamesV1, public val active: Boolean,
)

@Serializable
public enum class LocalBasisKindV1 { OPENING, LOCAL_PURCHASE, PURCHASE_ORDER_ALLOCATION }
@Serializable
public data class LocalApprovedStockLineV1(
    public val allocationId: DomainId, public val skuId: DomainId, public val warehouseId: DomainId,
    public val maximumBaseQuantity: Quantity, public val maximumOwnedValue: Money,
    public val inspectionRequired: Boolean,
) {
    init { require(maximumBaseQuantity.scaled > 0 && maximumOwnedValue.currency == Currency.EGP && maximumOwnedValue.minor >= 0) }
}

/** Draft allowance. Actor/policy are supplied only by the current, limited authorization proof. */
@Serializable
public data class LocalReceivingApprovalV1(
    public val id: DomainId, public val kind: LocalBasisKindV1, public val supplierId: DomainId?,
    public val validFrom: UtcInstant, public val validUntil: UtcInstant?,
    public val lines: List<LocalApprovedStockLineV1>,
) {
    init {
        require((kind == LocalBasisKindV1.OPENING) == (supplierId == null))
        require(validUntil == null || validUntil > validFrom)
        require(lines.size in 1..128 && lines.map { it.allocationId }.distinct().size == lines.size)
        // An oversized sum is malformed INPUT, not a poisoned storage event after prepare.
        // No individual line or signed Long overflow may silently erase the approval ceiling.
        var total = 0L
        for (line in lines) {
            require(line.maximumOwnedValue.minor <= Long.MAX_VALUE - total) { "Receiving allowance exceeds monetary capacity" }
            total += line.maximumOwnedValue.minor
        }
    }
}

@Serializable
public enum class LocalExternalTenderKindV1 { CARD, EXTERNAL_WALLET, OTHER }
@Serializable
public data class LocalTenderV1(
    public val id: DomainId, public val names: LocalNamesV1, public val kind: LocalExternalTenderKindV1,
    public val requiresTerminal: Boolean, public val requiresReference: Boolean, public val active: Boolean,
)

@Serializable
public data class LocalSupplierV1(public val id: DomainId, public val names: LocalNamesV1, public val active: Boolean)

/** An explicit activated warehouse route, not the first warehouse returned by a list. */
@Serializable
public data class LocalSalesRouteV1(
    public val id: DomainId, public val activity: LocalSalesActivity, public val channel: LocalSalesChannel,
    public val warehouseId: DomainId, public val active: Boolean,
)

@Serializable
public sealed interface LocalConfigurationRequestV1 {
    @Serializable @SerialName("INSTALL_COMPANY_SKU")
    public data class InstallCompanySku(public val publication: LocalSignedSkuV1) : LocalConfigurationRequestV1
    @Serializable @SerialName("CONFIGURE_WAREHOUSE")
    public data class ConfigureWarehouse(public val expectedStorageRevision: Revision?, public val warehouse: LocalWarehouseV1) : LocalConfigurationRequestV1
    @Serializable @SerialName("APPROVE_RECEIVING")
    public data class ApproveReceiving(public val approval: LocalReceivingApprovalV1) : LocalConfigurationRequestV1
    @Serializable @SerialName("CONFIGURE_TENDER")
    public data class ConfigureTender(public val expectedStorageRevision: Revision?, public val tender: LocalTenderV1) : LocalConfigurationRequestV1
    @Serializable @SerialName("CONFIGURE_SUPPLIER")
    public data class ConfigureSupplier(public val expectedStorageRevision: Revision?, public val supplier: LocalSupplierV1) : LocalConfigurationRequestV1
    @Serializable @SerialName("CONFIGURE_SALES_ROUTE")
    public data class ConfigureSalesRoute(public val expectedStorageRevision: Revision?, public val route: LocalSalesRouteV1) : LocalConfigurationRequestV1
}

@Serializable
public data class LocalConfigurationQueryV1(
    public val kind: String, public val id: DomainId? = null, public val after: DomainId? = null,
    public val limit: Int = 50, public val barcode: String? = null,
) {
    init {
        require(kind in setOf(LocalConfigurationProfile.SKU, LocalConfigurationProfile.WAREHOUSE, LocalConfigurationProfile.BASIS,
            LocalConfigurationProfile.TENDER, LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.SALES_ROUTE))
        require(limit in 1..50 && (id == null || after == null))
        require(barcode == null || (kind == LocalConfigurationProfile.SKU && id == null && after == null &&
            barcode.length in 1..128 && barcode.all { it.code in 0x21..0x7e }))
    }
}

@Serializable
public data class LocalConfigurationResultV1(public val code: String, public val id: DomainId?, public val revision: Revision?)

public object LocalConfigurationCodec {
    public fun encode(request: LocalConfigurationRequestV1): StoredBytes = encodeValue(request, LocalConfigurationRequestV1.serializer())
    public fun decode(request: StoredBytes): LocalConfigurationRequestV1 = decodeValue(request, LocalConfigurationRequestV1.serializer())
    public fun query(request: LocalConfigurationQueryV1): StoredBytes = encodeValue(request, LocalConfigurationQueryV1.serializer())
    public fun decodeQuery(request: StoredBytes): LocalConfigurationQueryV1 = decodeValue(request, LocalConfigurationQueryV1.serializer())
    public fun decodeResult(result: StoredBytes): LocalConfigurationResultV1 = decodeValue(result, LocalConfigurationResultV1.serializer())
    public fun decodePage(result: StoredBytes): LocalConfigurationPageV1 = decodeValue(result, LocalConfigurationPageV1.serializer())
    public fun canonicalPublication(definition: LocalPublishedSkuV1): ByteArray =
        ("ukkera.company-sku-publication/v1\n" + localConfigurationJson.encodeToString(LocalPublishedSkuV1.serializer(), definition)).encodeToByteArray()
    /** Exact embedded body bytes for equality/capacity; no separate signing domain is introduced. */
    public fun canonicalProductDefinition(definition: LocalPublishedProductV1): ByteArray =
        localConfigurationJson.encodeToString(LocalPublishedProductV1.serializer(), definition).encodeToByteArray()
}

internal val localConfigurationJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
internal fun <T> encodeValue(value: T, serializer: KSerializer<T>): StoredBytes =
    StoredBytes(localConfigurationJson.encodeToString(serializer, value).encodeToByteArray())
internal fun <T> decodeValue(bytes: StoredBytes, serializer: KSerializer<T>): T =
    localConfigurationJson.decodeFromString(serializer, bytes.copyBytes().decodeToString(throwOnInvalidSequence = true))
