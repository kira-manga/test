package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.ExternalTenderKind
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.sales.store.LocalSalesTenderSnapshot
import com.ukkera.pos.sales.store.TransactionSalesTenderSource
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface ConfigurationBody {
    @Serializable @SerialName("SKU") data class Sku(val value: LocalSignedSkuV1) : ConfigurationBody
    @Serializable @SerialName("WAREHOUSE") data class Warehouse(val value: LocalWarehouseV1) : ConfigurationBody
    @Serializable @SerialName("BASIS") data class Basis(val value: LocalReceivingApprovalV1) : ConfigurationBody
    @Serializable @SerialName("TENDER") data class ExternalTender(val value: LocalTenderV1) : ConfigurationBody
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("SUPPLIER") data class Supplier(
        val value: LocalSupplierV1,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val reference: Code? = null,
    ) : ConfigurationBody
    @Serializable @SerialName("SALES_ROUTE") data class SalesRoute(val value: LocalSalesRouteV1) : ConfigurationBody
    @Serializable @SerialName("CATALOG_CURSOR") data class CatalogCursor(val latestObservedCompanyRevision: Revision) : ConfigurationBody
}

@Serializable
internal data class ConfigurationRecord(
    val scope: BranchScope, val kind: String, val id: DomainId,
    val revision: Revision, val body: ConfigurationBody,
    val authorizedBy: DomainId, val policyRevision: Revision, val recordedAt: UtcInstant,
)

/** Typed minimal catalog/operating projections, never a raw aggregate or signature/grant. */
@Serializable
public data class LocalConfigurationListItemV1(
    public val id: DomainId, public val revision: Revision, public val storageRevision: Revision,
    public val names: LocalNamesV1?, public val active: Boolean?, public val code: String?,
)
@Serializable
public data class LocalConfigurationPageV1(
    public val kind: String, public val items: List<LocalConfigurationListItemV1>, public val nextAfter: DomainId?,
    /** Local selected-catalog cut, not a promise that an entire company release was installed. */
    public val catalogRevision: Revision? = null,
    public val detail: LocalConfigurationDetailV1? = null,
)

/** Purpose-limited direct-ID/scan details. No signatures, credential rows or reusable grants. */
@Serializable
public sealed interface LocalConfigurationDetailV1 {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("SKU")
    public data class Sku(
        public val skuId: DomainId, public val productId: DomainId, public val skuRevision: Revision,
        public val publicationRevision: Revision, public val names: LocalNamesV1,
        public val code: String, public val barcodes: List<String>, public val baseUnitId: DomainId,
        public val quantityScale: Int, public val tracking: LocalTrackingV1, public val fulfillment: LocalFulfillmentV1,
        public val active: Boolean, public val canPurchase: Boolean, public val canSell: Boolean, public val requiresExpiry: Boolean,
        public val conversions: List<LocalUnitConversionV1>, public val prices: List<LocalPriceV1>,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val unitDefinitions: List<@Serializable(with = LocalCatalogUnitSerializer::class) CatalogUnit>? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val productDefinition: LocalPublishedProductV1? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val variantValues: List<LocalVariantValueV1>? = null,
    ) : LocalConfigurationDetailV1
    @Serializable @SerialName("WAREHOUSE")
    public data class Warehouse(public val value: LocalWarehouseV1) : LocalConfigurationDetailV1
    @Serializable @SerialName("RECEIVING_BASIS")
    public data class ReceivingBasis(public val value: LocalReceivingApprovalV1) : LocalConfigurationDetailV1
    @Serializable @SerialName("TENDER")
    public data class ExternalTender(public val value: LocalTenderV1) : LocalConfigurationDetailV1
    @Serializable @SerialName("SUPPLIER")
    public data class Supplier(public val value: LocalSupplierV1) : LocalConfigurationDetailV1
    @Serializable @SerialName("SALES_ROUTE")
    public data class SalesRoute(public val value: LocalSalesRouteV1) : LocalConfigurationDetailV1
}

/**
 * Real typed setup/activation handler. Company item publication is authenticated separately;
 * local warehouse/supplier/tender configuration and finite purchasing/opening allowances require
 * their own explicit CURRENT delegated permissions. All source rows and approval actor/policy
 * are committed together with the kernel's durable result/audit/journal/outbox.
 */
public class LocalConfigurationHandler(
    private val signatures: CompanyPublicationSignatureVerifier,
    nativePublications: LocalCompanyBusinessPublications? = null,
) : MasterCommandHandler {
    private val unitAuthority = LocalCatalogUnitAuthority(nativePublications)
    private val productAuthority = LocalCatalogProductAuthority(nativePublications)
    override val key: CommandKey = LocalConfigurationProfile.COMMAND
    override val writableKinds: Set<String> = setOf(LocalConfigurationProfile.SKU, LocalConfigurationProfile.PUBLICATION,
        LocalConfigurationProfile.WAREHOUSE, LocalConfigurationProfile.BASIS, LocalConfigurationProfile.TENDER, LocalConfigurationProfile.SUPPLIER,
        LocalConfigurationProfile.SALES_ROUTE, LocalConfigurationProfile.CATALOG_CURSOR) + LocalPricingProfile.historyKinds
    override val claimNamespaces: Set<String> = setOf(LocalConfigurationProfile.SOURCE_CLAIM, LocalPricingProfile.VERSION_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = LocalConfigurationCodec.encode(LocalConfigurationCodec.decode(input))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean = when (profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS ->
            LocalConfigurationCodec.decode(normalized) is LocalConfigurationRequestV1.ConfigureSupplier
        LocalMasterRpcWorkflowProfile.WAREHOUSES ->
            LocalConfigurationCodec.decode(normalized) is LocalConfigurationRequestV1.ConfigureWarehouse
        LocalMasterRpcWorkflowProfile.REPORTS, LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
        LocalMasterRpcWorkflowProfile.KITCHEN, LocalMasterRpcWorkflowProfile.RETAIL_ORDERS,
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS, LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS, LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS,
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, LocalMasterRpcWorkflowProfile.INVOICE_MATCHING,
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> false
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalConfigurationCodec.decode(normalized)
        val sourceResources = mutableSetOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
        fun track(record: ConfigurationRecord): ConfigurationRecord = record.also {
            sourceResources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.id)
            sourceResources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.authorizedBy)
            (it.body as? ConfigurationBody.Sku)?.value?.let { signed ->
                check(signatures.verify(signed.definition.company, signed.signingKeyId,
                    LocalConfigurationCodec.canonicalPublication(signed.definition), signed.signatureHex))
                // Generic SKU consumers (for example receiving eligibility) do not use
                // retail prices. The company install adds its old/new rule+tax set below.
                sourceResources += companySourceResources(it, emptyList())
            }
        }
        suspend fun source(kind: String, sourceId: DomainId): ConfigurationRecord? {
            sourceResources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, sourceId)
            return view.aggregate(LocalConfigurationProfile.key(kind, sourceId))?.let { track(decodeRecord(view.scope, it)) }
        }
        val (kind, id, action) = when (request) {
            is LocalConfigurationRequestV1.InstallCompanySku -> Triple(LocalConfigurationProfile.SKU, request.publication.definition.skuId, SecurityAction.CATALOG_ACTIVATE)
            is LocalConfigurationRequestV1.ConfigureWarehouse -> Triple(LocalConfigurationProfile.WAREHOUSE, request.warehouse.id, SecurityAction.WAREHOUSE_CONFIGURE)
            is LocalConfigurationRequestV1.ApproveReceiving -> Triple(LocalConfigurationProfile.BASIS, request.approval.id,
                if (request.approval.kind == LocalBasisKindV1.OPENING) SecurityAction.OPENING_INVENTORY_APPROVE else SecurityAction.PURCHASING_APPROVE)
            is LocalConfigurationRequestV1.ConfigureTender -> Triple(LocalConfigurationProfile.TENDER, request.tender.id, SecurityAction.TENDER_CONFIGURE)
            is LocalConfigurationRequestV1.ConfigureSupplier -> Triple(LocalConfigurationProfile.SUPPLIER, request.supplier.id, SecurityAction.SUPPLIER_ADMINISTRATION)
            is LocalConfigurationRequestV1.ConfigureSalesRoute -> Triple(LocalConfigurationProfile.SALES_ROUTE, request.route.id, SecurityAction.WAREHOUSE_CONFIGURE)
        }
        val aggregateKey = LocalConfigurationProfile.key(kind, id)
        val old = view.aggregate(aggregateKey)
        val previous = old?.let { track(decodeRecord(view.scope, it)) }
        val revision = previous?.revision ?: Revision(0)
        val amount = if (request is LocalConfigurationRequestV1.ApproveReceiving)
            request.approval.lines.fold(Money(Currency.EGP, 0)) { total, line -> total + line.maximumOwnedValue } else null
        val required = AuthorizationRequirement(Permission(action, PermissionVerb.ADMINISTER), context.scope,
            ActionBinding(id, revision, context.semanticDigest, context.operationId, "local.configuration"),
            if (amount != null) LimitUse.Monetary(amount) else LimitUse.NotApplicable)
        var rejection: String? = null
        var sourceUnavailable = false
        var priceHistory: PreparedCompanyPriceHistory? = null
        val claims = mutableListOf<ClaimChange>()
        var publicationKey: AggregateKey? = null
        val cursorKey = LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, view.scope.branchId)
        val cursorRow = if (request is LocalConfigurationRequestV1.InstallCompanySku) view.aggregate(cursorKey) else null
        val oldCursor = cursorRow?.let { track(decodeRecord(view.scope, it)) }
        check(oldCursor == null || oldCursor.body is ConfigurationBody.CatalogCursor)

        val body: ConfigurationBody = when (request) {
            is LocalConfigurationRequestV1.InstallCompanySku -> {
                // The original signature decision also consumes native completion membership,
                // including legacy unit-less candidates. Unavailable is an admission hold.
                val nativePublications = view.originalNativeBusinessPublications()
                sourceUnavailable = nativePublications == null || nativePublications.company != view.scope.company()
                val value = request.publication.definition
                val bytes = LocalConfigurationCodec.canonicalPublication(value)
                val publicationAuthenticated = try { value.company == view.scope.company() &&
                    signatures.verify(value.company, request.publication.signingKeyId, bytes, request.publication.signatureHex)
                } finally { bytes.fill(0) }
                val oldSku = (previous?.body as? ConfigurationBody.Sku)?.value?.definition
                if (previous != null && oldSku == null) error("Invalid catalog storage")
                val units = unitAuthority.activation(view, request.publication,
                    (previous?.body as? ConfigurationBody.Sku)?.value)
                sourceUnavailable = sourceUnavailable || units.sourceUnavailable
                val product = productAuthority.activation(view, request.publication,
                    (previous?.body as? ConfigurationBody.Sku)?.value)
                sourceUnavailable = sourceUnavailable || product.sourceUnavailable
                sourceResources += product.resources
                priceHistory = prepareCompanyPriceHistory(view, context, request.publication, oldSku, signatures)
                sourceResources += priceHistory!!.resources
                claims += priceHistory!!.claims
                publicationKey = LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, value.publicationId)
                val alreadyInstalled = view.aggregate(publicationKey) != null
                val identities = listOf("sku-code/${value.code}") + value.barcodes.map { "barcode/$it" }
                val identifierOwners = mutableListOf<DomainId?>()
                for (identity in identities) {
                    val claim = ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, identity)
                    val owner = view.claim(claim)
                    identifierOwners += owner
                    if (owner == null) claims += ClaimChange.Acquire(claim, value.skuId)
                }
                rejection = localCompanySkuPreparationRejection(value, oldSku, publicationAuthenticated,
                    units.rejection, priceHistory!!.rejection, alreadyInstalled, identifierOwners, product.rejection)
                ConfigurationBody.Sku(request.publication)
            }
            is LocalConfigurationRequestV1.ConfigureWarehouse -> {
                if (old?.revision != request.expectedStorageRevision) rejection = "configuration.stale_revision"
                // Disabling a warehouse needs a stock/obligation-aware procedure, not a boolean bypass.
                if (!request.warehouse.active) rejection = "warehouse.controlled_retirement_required"
                ConfigurationBody.Warehouse(request.warehouse)
            }
            is LocalConfigurationRequestV1.ConfigureTender -> {
                if (old?.revision != request.expectedStorageRevision) rejection = "configuration.stale_revision"
                ConfigurationBody.ExternalTender(request.tender)
            }
            is LocalConfigurationRequestV1.ConfigureSupplier -> {
                if (old?.revision != request.expectedStorageRevision) rejection = "configuration.stale_revision"
                ConfigurationBody.Supplier(request.supplier, (previous?.body as? ConfigurationBody.Supplier)?.reference)
            }
            is LocalConfigurationRequestV1.ConfigureSalesRoute -> {
                if (old?.revision != request.expectedStorageRevision) rejection = "configuration.stale_revision"
                val warehouseDefinition = (source(LocalConfigurationProfile.WAREHOUSE, request.route.warehouseId)?.body as? ConfigurationBody.Warehouse)?.value
                if (warehouseDefinition?.active != true) rejection = "configuration.warehouse_required"
                val oldRoute = (previous?.body as? ConfigurationBody.SalesRoute)?.value
                if (oldRoute != null && (oldRoute.activity != request.route.activity || oldRoute.channel != request.route.channel)) {
                    rejection = "configuration.route_identity_changed"
                }
                val routeClaim = salesRouteClaim(request.route.activity, request.route.channel)
                val owner = view.claim(routeClaim)
                if (owner != null && owner != request.route.id) rejection = "configuration.route_already_exists"
                if (owner == null) claims += ClaimChange.Acquire(routeClaim, request.route.id)
                ConfigurationBody.SalesRoute(request.route)
            }
            is LocalConfigurationRequestV1.ApproveReceiving -> {
                if (old != null) rejection = "purchasing.approval_already_exists"
                // A branch permission cannot invent another branch's company-shared PO allocation.
                if (request.approval.kind == LocalBasisKindV1.PURCHASE_ORDER_ALLOCATION) rejection = "purchasing.company_allocation_required"
                val supplier = request.approval.supplierId?.let { (source(LocalConfigurationProfile.SUPPLIER, it)?.body as? ConfigurationBody.Supplier)?.value }
                if (request.approval.supplierId != null && supplier?.active != true) rejection = "purchasing.supplier_required"
                for (line in request.approval.lines) {
                    val item = source(LocalConfigurationProfile.SKU, line.skuId)
                    val signed = (item?.body as? ConfigurationBody.Sku)?.value
                    val sku = signed?.definition
                    if (signed != null) check(signatures.verify(signed.definition.company, signed.signingKeyId,
                        LocalConfigurationCodec.canonicalPublication(signed.definition), signed.signatureHex))
                    val warehouse = source(LocalConfigurationProfile.WAREHOUSE, line.warehouseId)
                    if (sku == null || !sku.active || sku.tracking == LocalTrackingV1.NONE ||
                        (request.approval.kind != LocalBasisKindV1.OPENING && !sku.canPurchase) ||
                        line.maximumBaseQuantity.unitId != sku.baseUnitId || line.maximumBaseQuantity.scale != sku.quantityScale ||
                        (warehouse?.body as? ConfigurationBody.Warehouse)?.value?.active != true) rejection = "purchasing.current_source_required"
                    val claim = ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "allocation/${line.allocationId.value}")
                    if (view.claim(claim) != null) rejection = "purchasing.allocation_already_exists"
                    claims += ClaimChange.Acquire(claim, request.approval.id)
                }
                ConfigurationBody.Basis(request.approval)
            }
        }
        val resources = buildSet {
            addAll(sourceResources)
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id))
            if (request is LocalConfigurationRequestV1.InstallCompanySku) {
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.publication.definition.skuId))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.publication.definition.productId))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.publication.definition.publicationId))
                // Exact catalog cursor dependency only; never a wildcard for stock/authorization.
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, cursorKey.id))
                val prior = (previous?.body as? ConfigurationBody.Sku)?.value?.definition
                addAll(localCatalogUnitResources(request.publication.definition))
                prior?.let { addAll(localCatalogUnitResources(it)) }
                addAll(localCatalogProductResources(request.publication.definition))
                prior?.let { addAll(localCatalogProductResources(it)) }
                // Quotes and recovery holds identify the publication that supplied their
                // price/stock authority. Replacing it must not evade a hold on that source.
                prior?.let { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.publicationId)) }
                listOfNotNull(request.publication.definition.returnPromise?.policyId, prior?.returnPromise?.policyId).forEach {
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it))
                }
                // Removal/replacement still depends on the old definitions; a hold on a
                // price/tax source cannot be evaded by omitting it from the new publication.
                (request.publication.definition.prices + prior?.prices.orEmpty()).forEach { price ->
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, price.ruleId))
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, price.tax.policyId))
                }
            }
            if (request is LocalConfigurationRequestV1.ConfigureWarehouse) add(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.warehouse.id))
            if (request is LocalConfigurationRequestV1.ConfigureSalesRoute) add(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.route.warehouseId))
            if (request is LocalConfigurationRequestV1.ApproveReceiving) {
                add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, id))
                request.approval.lines.forEach {
                    add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.skuId))
                    add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.warehouseId))
                    add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it.allocationId))
                }
            }
        }
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = required
            override val affectedResources: Set<RecoveryResourceKey> = resources
            override val admissionRestriction: PreparedMutationAdmissionRestriction? =
                if (sourceUnavailable) PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE else null
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.isBoundTo(required, context.originalActorId))
                if (rejection != null) return reject(rejection!!)
                if (configurationStorageCapacityRequired(old, cursorRow))
                    return reject("configuration.capacity_required")
                if (request is LocalConfigurationRequestV1.ApproveReceiving && request.approval.validUntil?.let { recordedAt >= it } == true) {
                    return reject("purchasing.approval_expired")
                }
                val next = if (previous == null) Revision(0) else previous.revision.next()
                val record = ConfigurationRecord(context.scope, kind, id, next, body, authorization.actorId, authorization.policyRevision, recordedAt)
                val encoded = boundedConfigurationRecord(record) ?: return reject("configuration.capacity_required")
                val changes = mutableListOf(AggregateChange(aggregateKey, old?.revision, configurationV1WriteSchema(kind, old), encoded))
                publicationKey?.let { published ->
                    // Retained publication payload is append-only: every later install expects a NEW key.
                    changes += AggregateChange(published, null, LocalConfigurationProfile.schema(LocalConfigurationProfile.PUBLICATION),
                        boundedConfigurationRecord(record.copy(kind = LocalConfigurationProfile.PUBLICATION, id = published.id, revision = Revision(0)))
                            ?: return reject("configuration.capacity_required"))
                    val companyRevision = (body as ConfigurationBody.Sku).value.definition.catalogRevision
                    val oldCompanyRevision = (oldCursor?.body as? ConfigurationBody.CatalogCursor)?.latestObservedCompanyRevision
                    val cursor = ConfigurationRecord(view.scope, LocalConfigurationProfile.CATALOG_CURSOR, cursorKey.id,
                        oldCursor?.revision?.next() ?: Revision(0), ConfigurationBody.CatalogCursor(
                            if (oldCompanyRevision != null && oldCompanyRevision > companyRevision) oldCompanyRevision else companyRevision),
                        authorization.actorId, authorization.policyRevision, recordedAt)
                    changes += AggregateChange(cursorKey, cursorRow?.revision, LocalConfigurationProfile.schema(LocalConfigurationProfile.CATALOG_CURSOR),
                        encodeValue(cursor, ConfigurationRecord.serializer()))
                }
                priceHistory?.let { changes += it.changes(context, authorization, recordedAt) }
                return ProposedOutcome.Apply(encodeValue(LocalConfigurationResultV1("configuration.applied", id, next), LocalConfigurationResultV1.serializer()), changes, claims)
            }
        }
    }
}

/** Current production authority reader over validated persisted source rows; no in-memory catalog. */
public class LocalConfigurationRepository(
    private val signatures: CompanyPublicationSignatureVerifier,
    nativePublications: LocalCompanyBusinessPublications? = null,
) : LocalStockAuthorityReader, TransactionSalesTenderSource {
    private val unitAuthority = LocalCatalogUnitAuthority(nativePublications)
    private val productAuthority = LocalCatalogProductAuthority(nativePublications)
    /** Historical receiving uses actual retained literal inputs, not today's mutable catalog.
     * These internal methods stay inside the real signature-backed repository. */
    internal suspend fun retainedStockItem(original: RetainedStreamedCommand, skuId: DomainId): LocalStockItemAuthority {
        val row = stockReceivingCurrent(original, LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, skuId))
        val record = decodeRecord(original.identity.scope, row)
        val signed = (record.body as? ConfigurationBody.Sku)?.value ?: error("Retained stock SKU unavailable")
        val value = signed.definition
        check(record.recordedAt <= original.identity.recordedAt && value.skuId == skuId && value.company == original.identity.scope.company())
        val sources = mutableListOf(row.stamp())
        if (value.unitDefinitions != null) {
            val published = stockReceivingCurrent(original,
                LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, value.publicationId))
            check(decodeRecord(original.identity.scope, published).signedSku() == signed)
            sources += published.stamp()
        }
        return originalStockItem(original.identity.scope, row, sources)
    }

    internal suspend fun retainedStockWarehouse(original: RetainedStreamedCommand, warehouseId: DomainId): LocalStockWarehouseAuthority {
        val row = stockReceivingCurrent(original, LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, warehouseId))
        val record = decodeRecord(original.identity.scope, row)
        val value = (record.body as? ConfigurationBody.Warehouse)?.value ?: error("Retained warehouse unavailable")
        check(record.recordedAt <= original.identity.recordedAt && value.id == warehouseId)
        return stockWarehouse(original.identity.scope, row)
    }

    internal suspend fun retainedStockBasis(original: RetainedStreamedCommand, id: DomainId): LocalStockReceivingBasis {
        val row = stockReceivingCurrent(original, LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, id))
        val record = decodeRecord(original.identity.scope, row)
        val value = (record.body as? ConfigurationBody.Basis)?.value ?: error("Retained receiving approval unavailable")
        check(record.recordedAt <= original.identity.recordedAt && value.id == id)
        return LocalStockReceivingBasis(original.identity.scope, id, record.revision, LocalStockBasisKind.valueOf(value.kind.name), value.supplierId,
            record.authorizedBy, record.authorizedBy, value.validFrom, value.validUntil,
            value.lines.map { LocalStockApprovedLine(it.allocationId, it.skuId, it.warehouseId, it.maximumBaseQuantity, it.maximumOwnedValue, it.inspectionRequired) }, listOf(row.stamp()))
    }

    override suspend fun item(view: CommandReadView, skuId: DomainId): LocalStockItemAuthority? {
        val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, skuId)) ?: return null
        val signed = verifiedStockSku(view.scope, row)
        val units = unitAuthority.current(view, signed)
        if (units.rejection != null) return null
        val sources = mutableListOf(row.stamp())
        if (units.definitions != null) {
            val published = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION,
                signed.definition.publicationId)) ?: return null
            check(decodeRecord(view.scope, published).signedSku() == signed)
            sources += published.stamp()
        }
        return stockItem(view.scope, signed.definition,
            if (units.definitions == null) emptyList() else signed.definition.conversions.map { it.domain(signed.definition.productId) },
            units.definitions, sources)
    }

    /** Complete native BEFORE source and its fully joined original; no historical current view. */
    internal suspend fun historicalStockItem(command: RuntimeHistoricalCommand, skuId: DomainId): LocalStockItemAuthority? {
        val source = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, skuId)) ?: return null
        val signed = verifiedStockSku(command.scope, source.row)
        val sources = mutableListOf(source.row.stamp())
        if (signed.definition.unitDefinitions != null) {
            val published = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION,
                signed.definition.publicationId)) ?: error("Historical SKU lost its original publication")
            check(decodeRecord(command.scope, published.row).signedSku() == signed)
            sources += published.row.stamp()
        }
        return originalStockItem(command.scope, source.row, sources)
    }

    private fun verifiedStockSku(scope: BranchScope, row: StoredAggregate): LocalSignedSkuV1 {
        val record = decodeRecord(scope, row)
        val signed = (record.body as? ConfigurationBody.Sku)?.value ?: error("Invalid catalog storage")
        val value = signed.definition
        check(row.key.kind == LocalConfigurationProfile.SKU && value.skuId == row.key.id && value.company == scope.company())
        val bytes = LocalConfigurationCodec.canonicalPublication(value)
        try { check(signatures.verify(value.company, signed.signingKeyId, bytes, signed.signatureHex)) }
        finally { bytes.fill(0) }
        return signed
    }

    private fun originalStockItem(scope: BranchScope, row: StoredAggregate,
        sources: List<LocalStockSourceStamp>): LocalStockItemAuthority {
        val value = verifiedStockSku(scope, row).definition
        // Only exact native-retained/BEFORE reads reach this branch. Do not re-admit an old
        // receipt's literal conversion against today's definitions or native signing state.
        return stockItem(scope, value, value.conversions.map { it.domain(value.productId) }, value.unitDefinitions, sources)
    }

    private fun stockItem(scope: BranchScope, value: LocalPublishedSkuV1,
        conversions: List<ProductUnitConversion>, definitions: List<CatalogUnit>?,
        sources: List<LocalStockSourceStamp>): LocalStockItemAuthority {
        return LocalStockItemAuthority(scope, value.skuId, value.productId, value.catalogRevision, value.skuRevision,
            value.baseUnitId, value.quantityScale, StockTracking.valueOf(value.tracking.name), value.requiresExpiry,
            value.canPurchase, value.active, conversions, sources, definitions)
    }

    override suspend fun warehouse(view: CommandReadView, warehouseId: DomainId): LocalStockWarehouseAuthority? {
        val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, warehouseId)) ?: return null
        return stockWarehouse(view.scope, row)
    }

    internal suspend fun historicalStockWarehouse(command: RuntimeHistoricalCommand, warehouseId: DomainId): LocalStockWarehouseAuthority? {
        val source = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, warehouseId)) ?: return null
        return stockWarehouse(command.scope, source.row)
    }

    private fun stockWarehouse(scope: BranchScope, row: StoredAggregate): LocalStockWarehouseAuthority {
        val value = (decodeRecord(scope, row).body as? ConfigurationBody.Warehouse)?.value ?: error("Invalid warehouse storage")
        check(row.key.kind == LocalConfigurationProfile.WAREHOUSE && value.id == row.key.id)
        return LocalStockWarehouseAuthority(scope, value.id, value.active, listOf(row.stamp()))
    }

    override suspend fun receivingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis? =
        basis(view, basisId)?.takeIf { it.kind != LocalStockBasisKind.OPENING }
    override suspend fun openingBasis(view: CommandReadView, basisId: DomainId): LocalStockReceivingBasis? =
        basis(view, basisId)?.takeIf { it.kind == LocalStockBasisKind.OPENING }
    override suspend fun countSurplusBasis(view: CommandReadView, basisId: DomainId): LocalCountSurplusBasis? =
        readLocalCountSurplusBasis(view, basisId)

    private suspend fun basis(view: CommandReadView, id: DomainId): LocalStockReceivingBasis? {
        val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.BASIS, id)) ?: return null
        val record = decodeRecord(view.scope, row)
        val value = (record.body as? ConfigurationBody.Basis)?.value ?: error("Invalid approval storage")
        check(value.id == id)
        return LocalStockReceivingBasis(view.scope, id, record.revision, LocalStockBasisKind.valueOf(value.kind.name), value.supplierId,
            record.authorizedBy, record.authorizedBy, value.validFrom, value.validUntil,
            value.lines.map { LocalStockApprovedLine(it.allocationId, it.skuId, it.warehouseId, it.maximumBaseQuantity, it.maximumOwnedValue, it.inspectionRequired) }, listOf(row.stamp()))
    }

    override suspend fun current(view: CommandReadView, tenderId: DomainId, expectedRevision: Revision): LocalSalesTenderSnapshot? {
        val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.TENDER, tenderId)) ?: return null
        val record = decodeRecord(view.scope, row)
        val value = (record.body as? ConfigurationBody.ExternalTender)?.value ?: error("Invalid tender storage")
        check(value.id == tenderId)
        if (!value.active || record.revision != expectedRevision) return null
        return LocalSalesTenderSnapshot(view.scope, Tender.External(value.id, record.revision,
            ExternalTenderKind.valueOf(value.kind.name), value.requiresTerminal, value.requiresReference),
            setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, tenderId)))
    }

    public fun readHandler(): MasterReadHandler = ConfigurationReadHandler(signatures, unitAuthority, productAuthority)
}

private class ConfigurationReadHandler(private val signatures: CompanyPublicationSignatureVerifier,
    private val unitAuthority: LocalCatalogUnitAuthority,
    private val productAuthority: LocalCatalogProductAuthority) : MasterReadHandler {
    override val key: ReadKey = LocalConfigurationProfile.QUERY
    override fun normalize(input: StoredBytes): StoredBytes =
        encodeValue(decodeValue(input, LocalConfigurationQueryV1.serializer()), LocalConfigurationQueryV1.serializer())
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        val kind = when (profile) {
            LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> {
                val query = decodeValue(normalized, LocalConfigurationQueryV1.serializer())
                return query.kind in setOf(LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.SKU) &&
                    query.barcode == null && normalize(normalized) == normalized
            }
            LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalConfigurationProfile.SUPPLIER
            LocalMasterRpcWorkflowProfile.WAREHOUSES, LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> LocalConfigurationProfile.WAREHOUSE
            LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> LocalConfigurationProfile.SKU
            LocalMasterRpcWorkflowProfile.REPORTS, LocalMasterRpcWorkflowProfile.KITCHEN,
            LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS, LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
            LocalMasterRpcWorkflowProfile.FOOD_CHECKS, LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS,
            LocalMasterRpcWorkflowProfile.INVOICE_MATCHING, LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> return false
        }
        val query = decodeValue(normalized, LocalConfigurationQueryV1.serializer())
        return query.kind == kind && (profile != LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS || query.barcode == null) &&
            (profile != LocalMasterRpcWorkflowProfile.RETAIL_ORDERS || normalize(normalized) == normalized)
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        val query = decodeValue(normalized, LocalConfigurationQueryV1.serializer())
        val scanned = query.barcode?.let { view.claim(ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "barcode/$it")) }
        val page = if (query.id == null && query.barcode == null) view.aggregateKeys(query.kind, query.after, query.limit) else null
        val selectedId = query.id ?: scanned
        val keys = page?.keys ?: selectedId?.let { listOf(LocalConfigurationProfile.key(query.kind, it)) }.orEmpty()
        val records = keys.mapNotNull { key ->
            val row = view.aggregate(key)
            // A key selected in this SAME writer transaction cannot disappear without an error.
            // A genuinely absent direct-ID/scan remains a bounded empty result, not a fake page.
            check(row != null || (page == null && scanned == null)) { "Catalog page/identity lost its source" }
            row?.let {
                val record = decodeRecord(view.scope, it)
                (record.body as? ConfigurationBody.Sku)?.value?.let { signed ->
                    check(signatures.verify(signed.definition.company, signed.signingKeyId,
                        LocalConfigurationCodec.canonicalPublication(signed.definition), signed.signatureHex))
                }
                it to record
            }
        }
        val values = records.map { (row, record) ->
            val names = when (val body = record.body) {
                is ConfigurationBody.Sku -> body.value.definition.names
                is ConfigurationBody.Warehouse -> body.value.names
                is ConfigurationBody.ExternalTender -> body.value.names
                is ConfigurationBody.Supplier -> body.value.names
                is ConfigurationBody.Basis, is ConfigurationBody.SalesRoute, is ConfigurationBody.CatalogCursor -> null
            }
            val active = when (val body = record.body) {
                is ConfigurationBody.Sku -> body.value.definition.active
                is ConfigurationBody.Warehouse -> body.value.active
                is ConfigurationBody.ExternalTender -> body.value.active
                is ConfigurationBody.Supplier -> body.value.active
                is ConfigurationBody.SalesRoute -> body.value.active
                is ConfigurationBody.Basis, is ConfigurationBody.CatalogCursor -> null
            }
            LocalConfigurationListItemV1(record.id, record.revision, row.revision, names, active,
                (record.body as? ConfigurationBody.Sku)?.value?.definition?.code)
        }
        val detail = if (page == null) records.singleOrNull()?.second?.let { record ->
            when (val body = record.body) {
                is ConfigurationBody.Sku -> {
                    val value = body.value.definition
                    check(signatures.verify(value.company, body.value.signingKeyId, LocalConfigurationCodec.canonicalPublication(value), body.value.signatureHex))
                    val units = unitAuthority.current(view, body.value)
                    val product = productAuthority.current(view, body.value)
                    // A retained historical barcode claim is not current scan eligibility.
                    if (query.barcode != null && query.barcode !in value.barcodes) null else
                        LocalConfigurationDetailV1.Sku(value.skuId, value.productId, value.skuRevision, value.catalogRevision,
                            value.names, value.code, value.barcodes, value.baseUnitId, value.quantityScale, value.tracking, value.fulfillment,
                            value.active, value.canPurchase, value.canSell, value.requiresExpiry,
                            if (units.definitions == null) emptyList() else value.conversions, value.prices, units.definitions,
                            product.definition, product.variantValues)
                }
                is ConfigurationBody.Warehouse -> LocalConfigurationDetailV1.Warehouse(body.value)
                is ConfigurationBody.Basis -> LocalConfigurationDetailV1.ReceivingBasis(body.value)
                is ConfigurationBody.ExternalTender -> LocalConfigurationDetailV1.ExternalTender(body.value)
                is ConfigurationBody.Supplier -> LocalConfigurationDetailV1.Supplier(body.value)
                is ConfigurationBody.SalesRoute -> LocalConfigurationDetailV1.SalesRoute(body.value)
                is ConfigurationBody.CatalogCursor -> error("Catalog cursor is not a public detail")
            }
        } else null
        // Warehouse directory revisions come from their own rows, not company catalog state.
        val cursor = if (query.kind == LocalConfigurationProfile.WAREHOUSE) null else
            view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, view.scope.branchId))
                ?.let { decodeRecord(view.scope, it) }
        check(cursor == null || cursor.body is ConfigurationBody.CatalogCursor)
        val action = if (query.kind in setOf(LocalConfigurationProfile.BASIS, LocalConfigurationProfile.SUPPLIER))
            SecurityAction.PURCHASING_READ else SecurityAction.CATALOG_READ
        val required = AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope)
        // Direct basis detail includes maximumOwnedValue. Require cost authority by request shape,
        // including an absent ID; a quantity-only basis list still has its existing requirement.
        val costRequired = if (query.kind == LocalConfigurationProfile.BASIS && query.id != null)
            AuthorizationRequirement(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM), context.scope,
                ActionBinding(requireNotNull(query.id), records.singleOrNull()?.first?.revision ?: Revision(0),
                    context.semanticDigest, reasonCode = "configuration.cost_read"))
        else null
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOfNotNull(required, costRequired)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (authorization, requirement) ->
                    authorization.isBoundTo(requirement, context.actorId)
                })
                return encodeValue(LocalConfigurationPageV1(query.kind,
                    if (query.barcode != null && detail == null) emptyList() else values, page?.nextAfter,
                    cursor?.revision, detail), LocalConfigurationPageV1.serializer())
            }
        }
    }
}

private fun reject(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
    encodeValue(LocalConfigurationResultV1(code, null, null), LocalConfigurationResultV1.serializer()))
internal fun boundedConfigurationRecord(value: ConfigurationRecord): StoredBytes? {
    val bytes = localConfigurationJson.encodeToString(ConfigurationRecord.serializer(), value).encodeToByteArray()
    return if (bytes.size <= 65_536) StoredBytes(bytes) else null
}

internal fun configurationStorageCapacityRequired(before: StoredAggregate?, cursor: StoredAggregate?): Boolean =
    before?.revision?.value == Long.MAX_VALUE || cursor?.revision?.value == Long.MAX_VALUE

/** A legacy edit cannot downgrade a validated supplier row or erase its directory reference. */
internal fun configurationV1WriteSchema(kind: String, before: StoredAggregate?): String =
    if (kind == LocalConfigurationProfile.SUPPLIER && before?.schema == LocalSupplierProfileV2.SCHEMA)
        LocalSupplierProfileV2.SCHEMA else LocalConfigurationProfile.schema(kind)

/** Preserve the handler's full overwrite order, including every later source decision. */
internal fun localCompanySkuPreparationRejection(sku: LocalPublishedSkuV1, previous: LocalPublishedSkuV1?,
    publicationAuthenticated: Boolean, unitRejection: String?, priceHistoryRejection: String?,
    alreadyInstalled: Boolean, identifierOwners: List<DomainId?>, productRejection: String? = null): String? {
    check(identifierOwners.size == 1 + sku.barcodes.size)
    var rejection: String? = if (publicationAuthenticated) null else "catalog.publication_required"
    if (incompatibleLocalPriceRules(sku.prices)) rejection = "catalog.price_conflict"
    if (previous != null) {
        val oldPrices = previous.prices.associateBy { it.ruleId }
        for (price in sku.prices) {
            val prior = oldPrices[price.ruleId] ?: continue
            if (price.revision < prior.revision || price.revision == prior.revision && price != prior)
                rejection = "catalog.price_version_changed"
        }
        if (sku.skuRevision <= previous.skuRevision || sku.catalogRevision < previous.catalogRevision)
            rejection = "catalog.stale_publication"
        if (previous.productId != sku.productId || previous.baseUnitId != sku.baseUnitId ||
            previous.quantityScale != sku.quantityScale || previous.tracking != sku.tracking || previous.fulfillment != sku.fulfillment)
            rejection = "catalog.controlled_migration_required"
    }
    unitRejection?.let { rejection = it }
    productRejection?.let { rejection = it }
    priceHistoryRejection?.let { rejection = it }
    if (alreadyInstalled) rejection = "catalog.publication_already_installed"
    for (owner in identifierOwners) if (owner != null && owner != sku.skuId) rejection = "catalog.duplicate_identifier"
    return rejection
}
private fun StoredAggregate.stamp(): LocalStockSourceStamp = LocalStockSourceStamp(key, revision, digest)
internal fun decodeRecord(scope: BranchScope, row: StoredAggregate): ConfigurationRecord =
    decodeRecord(scope, row.key, row.revision, row.schema, row.payload)

/** Canonical interpretation only; literal historical bytes do not mint a stored row or authority. */
internal fun decodeRecord(scope: BranchScope, key: AggregateKey, generation: Revision,
    schema: String, payload: StoredBytes): ConfigurationRecord {
    check(schema == LocalConfigurationProfile.schema(key.kind) ||
        key.kind == LocalConfigurationProfile.SUPPLIER && schema == LocalSupplierProfileV2.SCHEMA)
    val record = decodeValue(payload, ConfigurationRecord.serializer())
    check(record.scope == scope && record.kind == key.kind && record.id == key.id && record.revision.value < Long.MAX_VALUE)
    check(generation == record.revision.next()) { "Configuration domain/storage revisions diverged" }
    val bodyId = when (val body = record.body) {
        is ConfigurationBody.Sku -> {
            check(body.value.definition.company == scope.company())
            when (record.kind) {
                LocalConfigurationProfile.SKU -> body.value.definition.skuId
                LocalConfigurationProfile.PUBLICATION -> body.value.definition.publicationId
                else -> error("Configuration body kind mismatch")
            }
        }
        is ConfigurationBody.Warehouse -> { check(record.kind == LocalConfigurationProfile.WAREHOUSE); body.value.id }
        is ConfigurationBody.ExternalTender -> { check(record.kind == LocalConfigurationProfile.TENDER); body.value.id }
        is ConfigurationBody.Supplier -> {
            check(record.kind == LocalConfigurationProfile.SUPPLIER &&
                (schema == LocalSupplierProfileV2.SCHEMA || body.reference == null))
            body.value.id
        }
        is ConfigurationBody.Basis -> { check(record.kind == LocalConfigurationProfile.BASIS); body.value.id }
        is ConfigurationBody.SalesRoute -> { check(record.kind == LocalConfigurationProfile.SALES_ROUTE); body.value.id }
        is ConfigurationBody.CatalogCursor -> { check(record.kind == LocalConfigurationProfile.CATALOG_CURSOR); scope.branchId }
    }
    check(bodyId == record.id) { "Configuration body identity mismatch" }
    check(encodeValue(record, ConfigurationRecord.serializer()) == payload)
    return record
}

internal fun salesRouteClaim(activity: com.ukkera.pos.sales.store.LocalSalesActivity, channel: com.ukkera.pos.sales.store.LocalSalesChannel): ClaimKey =
    ClaimKey(LocalConfigurationProfile.SOURCE_CLAIM, "sales-route/${activity.name}/${channel.name}")

/** Bounded SKU-price subset validation at activation, never discover ambiguity after taking money. */
internal fun incompatibleLocalPriceRules(prices: List<LocalPriceV1>): Boolean {
    for (leftIndex in prices.indices) for (rightIndex in leftIndex + 1 until prices.size) {
        val left = prices[leftIndex]
        val right = prices[rightIndex]
        if (left.priority != right.priority || left.channel != right.channel || left.minimumQuantity != right.minimumQuantity) continue
        val from = maxOf(left.effectiveFrom, right.effectiveFrom)
        val until = listOfNotNull(left.effectiveUntil, right.effectiveUntil).minOrNull()
        if (until != null && from >= until) continue
        val minimum = left.minimumQuantity
        val witness = if (minimum.scaled > 0) minimum else Quantity(minimum.unitId, 1, minimum.scale)
        if (left.maximumQuantityExclusive?.let { witness >= it } == true || right.maximumQuantityExclusive?.let { witness >= it } == true) continue
        if (left.unitPrice != right.unitPrice || left.inclusion != right.inclusion || left.tax != right.tax) return true
    }
    return false
}
