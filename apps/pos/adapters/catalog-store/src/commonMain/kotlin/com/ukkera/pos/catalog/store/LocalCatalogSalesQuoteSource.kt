package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.sales.store.TransactionSalesStockQuoteSource
import com.ukkera.pos.sales.store.LocalCommerceOriginalSources
import com.ukkera.pos.sales.store.LocalCommerceOriginalRow
import com.ukkera.pos.sales.store.LocalSalesPriceSourceV2
import com.ukkera.pos.sales.RetailStockDemandV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import kotlinx.serialization.KSerializer
import com.ukkera.pos.inventory.store.LocalStockAllocationRequest
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockReadCut
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingFailure
import com.ukkera.pos.pricing.PricingRejectedException
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.RetailStockRequirements
import com.ukkera.pos.sales.store.LocalOrderSelectionV1
import com.ukkera.pos.sales.store.LocalSalesQuoteUnavailable
import com.ukkera.pos.sales.store.LocalSalesQuoteUnavailableReason
import com.ukkera.pos.sales.store.LocalSalesQuotedOrder
import com.ukkera.pos.sales.store.LocalSalesPriceSourceProfile
import com.ukkera.pos.sales.store.PreparedSalesQuote
import com.ukkera.pos.sales.store.TransactionSalesQuoteSource
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Real selected-catalog/warehouse/stock integration for base-unit retail. Every definition and
 * complete selected stock dependency is read in the command's writer, never from the UI cache.
 * This deliberately does not impersonate recipe, pack-conversion, promotion, service or kit
 * execution. Those selectors remain unavailable until their distinct source proofs are wired.
 * The activity label alone neither enables nor disables ordinary stocked retail merchandise.
 */
public class LocalCatalogSalesQuoteSource(
    private val signatures: CompanyPublicationSignatureVerifier,
    private val stock: LocalStockRepository,
) : TransactionSalesQuoteSource, TransactionSalesStockQuoteSource {
    override suspend fun observeReservationAuthority(view: CommandReadView, lines: List<LocalStockAllocationRequest>): Set<RecoveryResourceKey> =
        observeCommerceReservationAuthority(view, lines, signatures)
    override suspend fun requireOriginalReservationAuthority(sources: LocalCommerceOriginalSources,
        line: com.ukkera.pos.inventory.store.LocalStockDemandLine): Set<RecoveryResourceKey> =
        originalCommerceReservationAuthority(sources, line, signatures)
    override suspend fun current(view: CommandReadView, selection: LocalOrderSelectionV1): PreparedSalesQuote? =
        selectedRetailCurrent(view, selection, signatures, stock, false)
    override suspend fun currentNormalized(view: CommandReadView, selection: LocalOrderSelectionV1): PreparedSalesQuote? =
        selectedRetailCurrent(view, selection, signatures, stock, true)
    override suspend fun requireOriginalNormalized(sources: LocalCommerceOriginalSources,
        selection: LocalOrderSelectionV1, actual: LocalSalesQuotedOrder): Set<RecoveryResourceKey> =
        requireOriginalRetailQuote(sources, selection, actual, signatures)
}

/**
 * New-only catalogue source has no repository construction cycle. It reads the actual signed
 * catalogue/price closure and returns normal domain logical demand; it does not allocate stock
 * or implement a rejecting legacy inventory provider. Construct this before the SAME immutable
 * LocalStockRepository's composite verifiers, then give both to the actual handlers/readers.
 */
public class LocalCatalogNormalizedSalesQuoteSource(private val signatures: CompanyPublicationSignatureVerifier) : TransactionSalesStockQuoteSource {
    override suspend fun currentNormalized(view: CommandReadView, selection: LocalOrderSelectionV1): PreparedSalesQuote? =
        selectedRetailCurrent(view, selection, signatures, null, true)
    override suspend fun requireOriginalNormalized(sources: LocalCommerceOriginalSources, selection: LocalOrderSelectionV1,
        actual: LocalSalesQuotedOrder): Set<RecoveryResourceKey> = requireOriginalRetailQuote(sources, selection, actual, signatures)
    override suspend fun observeReservationAuthority(view: CommandReadView, lines: List<LocalStockAllocationRequest>): Set<RecoveryResourceKey> =
        observeCommerceReservationAuthority(view, lines, signatures)
    override suspend fun requireOriginalReservationAuthority(sources: LocalCommerceOriginalSources,
        line: com.ukkera.pos.inventory.store.LocalStockDemandLine): Set<RecoveryResourceKey> =
        originalCommerceReservationAuthority(sources, line, signatures)
}

private suspend fun selectedRetailCurrent(view: CommandReadView, selection: LocalOrderSelectionV1,
    signatures: CompanyPublicationSignatureVerifier, stock: LocalStockRepository?, normalized: Boolean): PreparedSalesQuote? {
        if (selection.currency != Currency.EGP || selection.serviceContextId != null ||
            selection.lines.isEmpty() || selection.lines.size > (if (normalized) 256 else LocalStockStoreProfile.MAX_LINES_PER_COMMAND) ||
            selection.lines.map { it.portionId }.distinct().size != selection.lines.size) return null
        val cursor = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, view.scope.branchId))
            ?.let { decodeRecord(view.scope, it) } ?: return null
        check(cursor.body is ConfigurationBody.CatalogCursor)
        if (cursor.revision != selection.expectedCatalogRevision) return null
        val routeId = view.claim(salesRouteClaim(selection.activity, selection.channel)) ?: return null
        val routeRecord = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SALES_ROUTE, routeId))
            ?.let { decodeRecord(view.scope, it) } ?: error("Selected warehouse route lost its source")
        val route = (routeRecord.body as? ConfigurationBody.SalesRoute)?.value ?: error("Invalid warehouse route")
        check(route.activity == selection.activity && route.channel == selection.channel)
        if (!route.active) return null
        val warehouseRecord = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, route.warehouseId))
            ?.let { decodeRecord(view.scope, it) } ?: error("Warehouse route lost its warehouse")
        val warehouse = (warehouseRecord.body as? ConfigurationBody.Warehouse)?.value ?: error("Invalid warehouse")
        if (!warehouse.active) return null
        val selectedRecords = linkedMapOf<DomainId, ConfigurationRecord>()
        val definitions = linkedMapOf<DomainId, LocalPublishedSkuV1>()
        // The SKU is the concrete item selector; a variant must identify that same SKU. It is
        // not an ignored modifier or a way to select another product's stock under this price.
        for (line in selection.lines) {
            if (line.quantity.scaled <= 0 || line.modifierIds.isNotEmpty() ||
                (line.variantId != null && line.variantId != line.itemId)) return null
            val sku = definitions[line.itemId] ?: run {
                val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, line.itemId)) ?: return null
                val record = decodeRecord(view.scope, row)
                val signed = (record.body as? ConfigurationBody.Sku)?.value ?: error("Invalid catalog item")
                check(signatures.verify(signed.definition.company, signed.signingKeyId,
                    LocalConfigurationCodec.canonicalPublication(signed.definition), signed.signatureHex))
                selectedRecords[line.itemId] = record
                definitions[line.itemId] = signed.definition
                signed.definition
            }
            if (!sku.active || !sku.canSell || sku.fulfillment != LocalFulfillmentV1.RETAIL ||
                sku.tracking !in setOf(LocalTrackingV1.QUANTITY, LocalTrackingV1.LOT) ||
                line.quantity.unitId != sku.baseUnitId || line.quantity.scale != sku.quantityScale) return null
        }
        if (!normalized && definitions.size > LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND) return null
        val hasPromise = definitions.values.any { it.returnPromise != null }
        if (!normalized && hasPromise) {
            // Preparation precedes authorization on the legacy handler. Retain only the
            // already-verified current sources; the ordinary refusal belongs in evaluation.
            // Do not read publication/history/price/stock closures for a refused legacy quote.
            val resources = buildSet {
                listOf(cursor, routeRecord, warehouseRecord).plus(selectedRecords.values).forEach { record ->
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, record.id))
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, record.authorizedBy))
                    (record.body as? ConfigurationBody.Sku)?.value?.let { signed ->
                        addAll(companySourceResources(record, signed.definition.prices))
                    }
                }
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, route.warehouseId))
                definitions.values.forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.skuId)) }
            }
            return LegacyRetailReturnPromiseRefusal(view.scope, resources)
        }
        // NEW presence-selected capture only. Mixed baskets bind every original signed SKU,
        // including explicit signed absence; the all-no-promise read/effect path is unchanged.
        val captured = if (hasPromise) selectedRecords.values.map { prepareRetailReturnPublication(view, it, signatures) } else emptyList()
        val returnPolicyCapture = if (!hasPromise) null else LocalSalesReturnPolicyCaptureInputV2(
            selection.lines.map { line -> LocalSalesReturnPortionCaptureV2(line.lineId, line.portionId,
                definitions.getValue(line.itemId).publicationId) }.sortedBy { it.portionId.value },
            captured.map { it.publication }.sortedBy { it.publicationId.value })
        // Bound the complete captured price closure, not merely the eventual selected rules.
        // All candidate contexts can affect priority/effective-time selection. Do not partially
        // load them and silently choose a different price when this retail profile is exceeded.
        if (!normalized && definitions.values.sumOf { it.prices.size } > MAX_CAPTURED_PRICE_CONTEXTS) return null
        val pricing = linkedMapOf<DomainId, PreparedLocalSkuPricing>()
        for ((id, sku) in definitions) pricing[id] = prepareLocalSkuPricing(view, sku, signatures)
        val cut = if (normalized) null else try {
            checkNotNull(stock).observe(view, definitions.keys.toList())
        } catch (failure: LocalStockRejected) {
            // A selected closure can exceed an engineering budget without corrupting Room.
            // No partial stock cut, invented zero balance or alternate fixture is returned.
            if (failure.failure == LocalStockFailure.BOUND_EXCEEDED) return null
            throw failure
        }
        if (cut != null && cut.items.any { it.storageRevision == null }) return null
        val resources = cut?.let { checkNotNull(stock).resources(it) }.orEmpty() + buildSet {
            pricing.values.forEach { addAll(it.resources) }
            captured.forEach { addAll(it.resources) }
            listOf(cursor, routeRecord, warehouseRecord).plus(selectedRecords.values).forEach {
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.id))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.authorizedBy))
                (it.body as? ConfigurationBody.Sku)?.value?.let { signed ->
                    addAll(companySourceResources(it, signed.definition.prices))
                }
            }
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, route.warehouseId))
            definitions.values.forEach { sku ->
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, sku.skuId))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, sku.productId))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, sku.publicationId))
                sku.prices.forEach { price ->
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, price.ruleId))
                    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, price.tax.policyId))
                }
            }
        }
        // Canonical handler decoding already owns these immutable scalars. Copy the collection;
        // the returned quote retains no CommandReadView or client collection/backing storage.
        val lines = selection.lines.map { it.copy(modifierIds = it.modifierIds.toList()) }
        return SelectedRetailQuote(view.scope, selection.channel.name, lines.map { it to pricing.getValue(it.itemId) },
            route.warehouseId, cut, stock, resources, normalized, returnPolicyCapture)
}

private const val MAX_CAPTURED_PRICE_CONTEXTS: Int = 128

/** No view, mutable selection, catalogue row or stock cut survives this prepared refusal. */
private class LegacyRetailReturnPromiseRefusal(
    override val scope: BranchScope,
    resources: Set<RecoveryResourceKey>,
) : PreparedSalesQuote {
    override val recoveryResources: Set<RecoveryResourceKey> = resources.toSet()
    override fun quote(quoteId: DomainId, quoteRevision: Revision, at: UtcInstant): LocalSalesQuotedOrder =
        throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.RETURN_PROMISE_REQUIRES_NORMALIZED_QUOTE)
}

private class SelectedRetailQuote(
    override val scope: BranchScope,
    private val channel: String,
    private val lines: List<Pair<com.ukkera.pos.sales.store.LocalOrderLineV1, PreparedLocalSkuPricing>>,
    private val warehouseId: DomainId,
    private val cut: LocalStockReadCut?,
    private val stock: LocalStockRepository?,
    override val recoveryResources: Set<RecoveryResourceKey>,
    private val normalized: Boolean,
    private val returnPolicyCapture: LocalSalesReturnPolicyCaptureInputV2?,
) : PreparedSalesQuote {
    override fun quote(quoteId: DomainId, quoteRevision: Revision, at: UtcInstant): LocalSalesQuotedOrder {
        val selected = lines.map { (line, pricing) -> line to pricing.resolve(channel, line.quantity, at) }
        val resolved = selected.map { (line, price) ->
            // priceRevision remains the company domain. The separately retained companion
            // identifies the exact policy/branch source without rewriting legacy snapshots.
            ResolvedPriceLine(line.lineId, line.portionId, price.company.revision, line.quantity,
                price.effectiveUnitPrice, price.company.inclusion, price.company.tax)
        }
        val priced = try {
            OperationalPricingEngine.calculate(PricingRequest(quoteId, quoteRevision, Currency.EGP, resolved))
        } catch (failure: PricingRejectedException) {
            // Pure pre-collection arithmetic/configuration refusal, not a storage exception or
            // evidence that any physical payment failed. Never blanket-catch financial errors.
            throw LocalSalesQuoteUnavailable(if (failure.failure == PricingFailure.ARITHMETIC_OR_POSTING_LIMIT)
                LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED else LocalSalesQuoteUnavailableReason.UNSUPPORTED_SELECTOR)
        }
        if (normalized) return LocalSalesQuotedOrder(priced, RetailStockRequirements.normalized(lines.map { (line, _) ->
            RetailStockDemandV2(line.portionId, line.itemId, warehouseId, line.quantity)
        }), LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2,
            selected.map { (line, price) -> price.provenance(line.lineId, line.portionId) }, returnPolicyCapture)
        val allocations = try {
            checkNotNull(stock).allocate(checkNotNull(cut), quoteId, quoteRevision, lines.map { (line, _) ->
                LocalStockAllocationRequest(line.portionId, line.itemId, warehouseId, line.quantity)
            }, at)
        } catch (failure: LocalStockRejected) {
            when (failure.failure) {
                LocalStockFailure.INSUFFICIENT_STOCK -> throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.INSUFFICIENT_STOCK)
                LocalStockFailure.BOUND_EXCEEDED -> throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED)
                else -> throw failure
            }
        }
        return LocalSalesQuotedOrder(priced, RetailStockRequirements(allocations),
            LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2,
            selected.map { (line, price) -> price.provenance(line.lineId, line.portionId) })
    }
}

/** Same priority-first pre-ID ranking as catalog's SKU-specific subset; ties never hide conflict. */
internal fun selectedRetailPrice(prices: List<LocalPriceV1>, channel: String, quantity: Quantity, at: UtcInstant): LocalPriceV1 {
    val eligible = prices.filter { price ->
        (price.channel == null || price.channel == channel) && price.minimumQuantity.unitId == quantity.unitId &&
            price.minimumQuantity.scale == quantity.scale && quantity >= price.minimumQuantity &&
            (price.maximumQuantityExclusive == null || quantity < price.maximumQuantityExclusive) &&
            at >= price.effectiveFrom && (price.effectiveUntil == null || at < price.effectiveUntil)
    }.sortedWith(compareByDescending<LocalPriceV1> { it.priority }
        .thenByDescending { it.channel != null }.thenByDescending { it.minimumQuantity }.thenBy { it.ruleId.value })
    val selected = eligible.firstOrNull() ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.NO_EFFECTIVE_PRICE)
    if (eligible.any { it.priority == selected.priority && (it.channel != null) == (selected.channel != null) &&
            it.minimumQuantity == selected.minimumQuantity &&
            (it.unitPrice != selected.unitPrice || it.inclusion != selected.inclusion || it.tax != selected.tax) }) {
        throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.AMBIGUOUS_PRICE)
    }
    return selected
}

/** Literal catalogue decoding, never a fabricated current view or a grant reconstructed from history. */
private class OriginalRetailCatalogue(private val source: LocalCommerceOriginalSources,
    private val signatures: CompanyPublicationSignatureVerifier) {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf()
    suspend fun configuration(kind: String, id: DomainId): ConfigurationRecord? {
        val row = source.aggregate(LocalConfigurationProfile.key(kind, id)) ?: return null
        check(row.schema == LocalConfigurationProfile.schema(kind))
        val value = decodeValue(row.payload, ConfigurationRecord.serializer())
        check(value.scope == source.scope && value.id == id && value.kind == kind && value.revision.next() == row.generation &&
            encodeValue(value, ConfigurationRecord.serializer()) == row.payload)
        val bodyId = when (val body = value.body) {
            is ConfigurationBody.Sku -> {
                check(body.value.definition.company == source.scope.company() && signatures.verify(body.value.definition.company,
                    body.value.signingKeyId, LocalConfigurationCodec.canonicalPublication(body.value.definition), body.value.signatureHex))
                check(kind == LocalConfigurationProfile.SKU || kind == LocalConfigurationProfile.PUBLICATION)
                if (kind == LocalConfigurationProfile.SKU) body.value.definition.skuId else body.value.definition.publicationId
            }
            is ConfigurationBody.Warehouse -> { check(kind == LocalConfigurationProfile.WAREHOUSE); body.value.id }
            is ConfigurationBody.SalesRoute -> { check(kind == LocalConfigurationProfile.SALES_ROUTE); body.value.id }
            is ConfigurationBody.CatalogCursor -> { check(kind == LocalConfigurationProfile.CATALOG_CURSOR); source.scope.branchId }
            else -> error("sales.stock.unrequested_catalogue_source")
        }
        check(bodyId == id)
        return value
    }
    suspend fun <T> pricing(kind: String, id: DomainId, serializer: KSerializer<T>): Pair<LocalCommerceOriginalRow, T>? {
        val row = source.aggregate(LocalPricingProfile.key(kind, id)) ?: return null
        check(row.schema == LocalPricingProfile.schema(kind))
        val value = decodeValue(row.payload, serializer)
        check(encodeValue(value, serializer) == row.payload)
        return row to value
    }
    suspend fun event(skuId: DomainId, id: DomainId): Pair<LocalCommerceOriginalRow, PriceHistoryEventV1> {
        val row = checkNotNull(pricing(LocalPricingProfile.HISTORY_EVENT, id, PriceHistoryEventV1.serializer()))
        val value = row.second
        check(row.first.generation == Revision(1) && value.scope == source.scope && value.skuId == skuId && value.eventId == id &&
            value.operationId.value == id.value && value.number.value >= 1 && (value.priorEventId == null) == (value.priorEventDigest == null) &&
            (value.number == Revision(1)) == (value.priorEventId == null))
        when (value.kind) {
            PriceHistoryEventKind.COMPANY_PUBLICATION, PriceHistoryEventKind.INDEXED_COMPANY_PUBLICATION ->
                check(value.companyPublicationId != null && value.policyPublicationId == null && value.branchChange == null)
            PriceHistoryEventKind.POLICY_ACTIVATION -> check(value.companyPublicationId == null && value.policyPublicationId != null && value.branchChange == null)
            PriceHistoryEventKind.BRANCH_CHANGE -> check(value.companyPublicationId == null && value.policyPublicationId == null && value.branchChange != null)
        }
        return row
    }
    suspend fun returnPublication(sku: LocalPublishedSkuV1): PreparedRetailReturnPublication {
        val published = checkNotNull(configuration(LocalConfigurationProfile.PUBLICATION, sku.publicationId))
        val row = checkNotNull(source.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, sku.publicationId)))
        check(row.generation == Revision(1) && published.revision == Revision(0) && published.signedSku().definition == sku)
        val head = checkNotNull(pricing(LocalPricingProfile.HISTORY_HEAD, sku.skuId, PriceHistoryHeadV1.serializer()))
        check(head.second.scope == source.scope && head.second.skuId == sku.skuId && head.second.count == head.first.generation)
        val dependencies = mutableSetOf(priceResource(sku.skuId), priceResource(head.second.eventId))
        var id = head.second.eventId; var digest = head.second.eventDigest; var number = head.second.count
        var visited = 0
        while (true) {
            check(visited++ < MAX_RETURN_PROMISE_HISTORY_STEPS) { "sales.return.original_publication_history_capacity" }
            val event = event(sku.skuId, id)
            check(event.first.digest == digest && event.second.number == number)
            dependencies += priceResource(id)
            if (event.second.kind == PriceHistoryEventKind.COMPANY_PUBLICATION && event.second.companyPublicationId == sku.publicationId) {
                val producer = (source.original(event.second.operationId) as? RetainedOriginalV2.Legacy)?.evidence
                    ?: error("sales.return.original_publication_producer_missing")
                requireRetailPublicationProducer(producer, source.scope, row.key, row.generation, row.schema, row.payload, row.digest,
                    event.first.payload, event.first.digest)
                val reference = LocalReturnCaseSourceReferenceV1(row.key.kind, row.key.id, row.schema, row.generation, row.digest,
                    producer.operationId, producer.originalActorId, producer.command.name, producer.command.version)
                val descriptor = returnPromiseDescriptor(sku.returnPromise)
                val publication = LocalSalesReturnPublicationV2(sku.skuId, sku.skuRevision, published.signedSku().signingKeyId, reference,
                    if (descriptor == null) LocalSalesReturnPromiseStateV2.SIGNED_ABSENT else LocalSalesReturnPromiseStateV2.SIGNED_PRESENT, descriptor)
                return PreparedRetailReturnPublication(publication, dependencies + companySourceResources(published, emptyList()) +
                    returnPolicyPublicationResources(publication))
            }
            id = checkNotNull(event.second.priorEventId) { "sales.return.original_installation_unavailable" }
            digest = checkNotNull(event.second.priorEventDigest)
            number = Revision(number.value - 1)
        }
    }
    suspend fun skuPrices(sku: LocalPublishedSkuV1): Map<DomainId, OriginalPriceContext> {
        val index = pricing(LocalPricingProfile.HISTORY_INDEX, source.scope.branchId, PriceHistoryIndexV1.serializer())?.second
        if (index != null) check(index.scope == source.scope && (index.capturedCursorRevision == null) == (index.capturedCursorDigest == null) &&
            (index.state == PriceHistoryIndexState.COMPLETE || index.afterPublicationId != null || index.indexedPublications == Revision(0)))
        resources += setOf(priceResource(source.scope.branchId), priceResource(sku.skuId), priceResource(sku.publicationId))
        val result = linkedMapOf<DomainId, OriginalPriceContext>()
        for (company in sku.prices) {
            val rule = company.ruleId
            val contextId = priceContextId(source.scope.company(), sku.skuId, rule)
            resources += setOf(priceResource(contextId), priceResource(rule))
            val waterRow = pricing(LocalPricingProfile.COMPANY_HIGH_WATER, contextId, PriceHighWaterV1.serializer())
            val water = waterRow?.second
            if (water != null) {
                check(water.scope == source.scope && water.skuId == sku.skuId && water.ruleId == rule && water.price.ruleId == rule)
                val published = checkNotNull(configuration(LocalConfigurationProfile.PUBLICATION, water.publicationId))
                val definition = published.signedSku().definition
                check(definition.skuId == sku.skuId && definition.prices.single { it.ruleId == rule } == water.price)
                resources += companySourceResources(published, listOf(water.price)) + priceResource(contextId)
            }
            if (index?.state == PriceHistoryIndexState.COMPLETE) check(water?.price == company)
            val policyRow = pricing(LocalPricingProfile.POLICY_CURRENT, contextId, PricePolicyActivationV1.serializer())
            val activation = policyRow?.second
            var policy: PricePolicyPublicationV1? = null
            if (activation != null) {
                check(activation.scope == source.scope && activation.skuId == sku.skuId && activation.ruleId == rule)
                val publication = checkNotNull(pricing(LocalPricingProfile.POLICY_PUBLICATION, activation.publicationId, PricePolicyPublicationV1.serializer()))
                policy = publication.second
                val signed = policy.signed; val definition = signed.definition
                check(publication.first.generation == Revision(1) && policy.scope == source.scope && definition.company == source.scope.company() &&
                    definition.publicationId == activation.publicationId && definition.skuId == sku.skuId && definition.ruleId == rule &&
                    signatures.verify(definition.company, signed.signingKeyId, LocalPricingCodec.canonicalPolicy(definition), signed.signatureHex) &&
                    activation.definitionDigest == sha256(LocalPricingCodec.canonicalPolicy(definition)) &&
                    definition.authorizationReferenceQuantity.unitId == sku.baseUnitId && definition.authorizationReferenceQuantity.scale == sku.quantityScale)
                val history = event(sku.skuId, activation.eventId).second
                check(history.kind == PriceHistoryEventKind.POLICY_ACTIVATION && history.policyPublicationId == activation.publicationId &&
                    history.actorId == activation.activatedBy && history.recordedAt == activation.activatedAt)
                resources += setOf(priceResource(contextId), priceResource(activation.eventId), priceResource(activation.activatedBy),
                    priceResource(definition.entryId), priceResource(definition.publicationId), priceResource(policy.receivedBy), priceResource(signed.signingKeyId))
            }
            val branchRow = pricing(LocalPricingProfile.OVERRIDE_CURRENT, contextId, BranchPriceHeadV1.serializer())
            val head = branchRow?.second
            var change: PriceBranchChangeV1? = null
            if (head != null) {
                check(index?.state == PriceHistoryIndexState.COMPLETE && head.scope == source.scope && head.skuId == sku.skuId &&
                    head.ruleId == rule && head.revision == checkNotNull(branchRow).first.generation)
                val history = event(sku.skuId, head.eventId)
                check(history.first.digest == head.eventDigest && history.second.kind == PriceHistoryEventKind.BRANCH_CHANGE)
                change = checkNotNull(history.second.branchChange)
                check(change.ruleId == rule && change.overrideRevision == head.revision && change.authorizationReferenceQuantity.scaled > 0 &&
                    change.authorizationBase.currency == Currency.EGP && change.authorizationBase.minor > 0 &&
                    change.authorizationDeviation.currency == Currency.EGP && change.authorizationDeviation.minor >= 0 &&
                    (change.unitPrice == null || change.unitPrice.currency == Currency.EGP && change.unitPrice.amount >= com.ukkera.pos.types.ExactDecimal.parse("0")) &&
                    (change.effectiveUntil == null || change.effectiveUntil > change.effectiveFrom) && history.second.actionApprovalId == null)
                resources += setOf(priceResource(contextId), priceResource(head.eventId), priceResource(history.second.actorId),
                    priceResource(change.companyPublicationId), priceResource(change.policyEntryId), priceResource(change.policyPublicationId))
            }
            result[rule] = OriginalPriceContext(activation, policy, head, change)
        }
        return result
    }
}
private data class OriginalPriceContext(val activation: PricePolicyActivationV1?, val policy: PricePolicyPublicationV1?,
    val head: BranchPriceHeadV1?, val branch: PriceBranchChangeV1?) {
    fun applied(company: LocalPriceV1, at: UtcInstant): Boolean {
        val definition = policy?.signed?.definition
        return branch?.unitPrice != null && activation != null && definition != null && definition.branchOverridesEnabled &&
            branch.companyPriceRevision == company.revision && branch.companyPriceDigest == localPriceDigest(company) &&
            at >= company.effectiveFrom && (company.effectiveUntil == null || at < company.effectiveUntil) &&
            branch.policyEntryId == definition.entryId && branch.policyEntryRevision == definition.revision &&
            branch.policyEntryDigest == activation.definitionDigest && at >= definition.effectiveFrom &&
            (definition.effectiveUntil == null || at < definition.effectiveUntil) && at >= branch.effectiveFrom &&
            (branch.effectiveUntil == null || at < branch.effectiveUntil)
    }
}

private suspend fun requireOriginalRetailQuote(source: LocalCommerceOriginalSources, selection: LocalOrderSelectionV1,
    actual: LocalSalesQuotedOrder, signatures: CompanyPublicationSignatureVerifier): Set<RecoveryResourceKey> {
    check(selection.lines.size in 1..256 && selection.lines.map { it.portionId }.distinct().size == selection.lines.size &&
        selection.currency == Currency.EGP && selection.serviceContextId == null && actual.stockRequirements.normalized)
    val catalogue = OriginalRetailCatalogue(source, signatures)
    val cursor = checkNotNull(catalogue.configuration(LocalConfigurationProfile.CATALOG_CURSOR, source.scope.branchId))
    check(cursor.body is ConfigurationBody.CatalogCursor && cursor.revision == selection.expectedCatalogRevision)
    val routeId = checkNotNull(source.claim(salesRouteClaim(selection.activity, selection.channel)))
    val routeRecord = checkNotNull(catalogue.configuration(LocalConfigurationProfile.SALES_ROUTE, routeId))
    val route = (routeRecord.body as ConfigurationBody.SalesRoute).value
    check(route.active && route.activity == selection.activity && route.channel == selection.channel)
    val warehouseRecord = checkNotNull(catalogue.configuration(LocalConfigurationProfile.WAREHOUSE, route.warehouseId))
    check((warehouseRecord.body as ConfigurationBody.Warehouse).value.active)
    val definitions = linkedMapOf<DomainId, LocalPublishedSkuV1>()
    val contexts = linkedMapOf<DomainId, Map<DomainId, OriginalPriceContext>>()
    val resources = catalogue.resources
    for (record in listOf(cursor, routeRecord, warehouseRecord)) resources += setOf(priceResource(record.id), priceResource(record.authorizedBy))
    resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, route.warehouseId)
    val resolved = mutableListOf<ResolvedPriceLine>()
    val provenance = mutableListOf<LocalSalesPriceSourceV2>()
    for (line in selection.lines) {
        check(line.quantity.scaled > 0 && line.modifierIds.isEmpty() && (line.variantId == null || line.variantId == line.itemId))
        val sku = definitions[line.itemId] ?: run {
            val record = checkNotNull(catalogue.configuration(LocalConfigurationProfile.SKU, line.itemId))
            val value = record.signedSku().definition
            resources += companySourceResources(record, value.prices)
            resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, value.skuId)
            definitions[line.itemId] = value
            contexts[line.itemId] = catalogue.skuPrices(value)
            value
        }
        check(sku.active && sku.canSell && sku.fulfillment == LocalFulfillmentV1.RETAIL &&
            sku.tracking in setOf(LocalTrackingV1.QUANTITY, LocalTrackingV1.LOT) &&
            line.quantity.unitId == sku.baseUnitId && line.quantity.scale == sku.quantityScale)
        val company = selectedRetailPrice(sku.prices, selection.channel.name, line.quantity, source.recordedAt)
        val context = contexts.getValue(sku.skuId).getValue(company.ruleId)
        val applied = context.applied(company, source.recordedAt)
        val effective = if (applied) checkNotNull(context.branch?.unitPrice) else company.unitPrice
        val definition = context.policy?.signed?.definition
        resolved += ResolvedPriceLine(line.lineId, line.portionId, company.revision, line.quantity, effective, company.inclusion, company.tax)
        provenance += LocalSalesPriceSourceV2(source.scope, line.lineId, line.portionId, sku.skuId, sku.publicationId, company.ruleId,
            company.revision, localPriceDigest(company), company.unitPrice, effective, definition?.entryId, definition?.revision,
            definition?.publicationId, context.activation?.definitionDigest, if (applied) context.head?.eventId else null,
            if (applied) context.head?.revision else null)
    }
    val expectedCapture = if (definitions.values.none { it.returnPromise != null }) null else {
        val captured = definitions.values.map { catalogue.returnPublication(it) }
        captured.forEach { resources += it.resources }
        LocalSalesReturnPolicyCaptureInputV2(selection.lines.map { line ->
            LocalSalesReturnPortionCaptureV2(line.lineId, line.portionId, definitions.getValue(line.itemId).publicationId)
        }.sortedBy { it.portionId.value }, captured.map { it.publication }.sortedBy { it.publicationId.value })
    }
    check(actual.returnPolicyCapture == expectedCapture) { "sales.return.original_quote_capture_mismatch" }
    val quote = OperationalPricingEngine.calculate(PricingRequest(actual.quote.snapshotId, actual.quote.revision, Currency.EGP, resolved))
    check(StoredPricingSnapshotCodec.encode(quote) == StoredPricingSnapshotCodec.encode(actual.quote) &&
        actual.priceSourceProfile == LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2 &&
        actual.priceSources.sortedBy { it.portionId.value } == provenance.sortedBy { it.portionId.value } &&
        actual.stockRequirements.normalizedLines == selection.lines.map { RetailStockDemandV2(it.portionId, it.itemId, route.warehouseId, it.quantity) })
    return resources.toSet()
}

/**
 * Actual food side of the same normalized-commerce profile. Existing recipe/pricing current
 * readers remain the implementation; original verification consumes literal rows/real original
 * handles rather than pretending a historical body is a current CommandReadView or grant.
 */
public class LocalCatalogFoodStockSource(private val signatures: CompanyPublicationSignatureVerifier) :
    TransactionFoodStockSource, TransactionFoodRecipeQuotationVerificationSourceV1, TransactionFoodRecipeRoutingVerificationSource {
    private val activityAuthority = LocalCatalogFoodActivityAuthority(signatures)
    override suspend fun prepareActivity(view: CommandReadView, activity: LocalSalesActivity): PreparedFoodActivityAdmission =
        activityAuthority.prepare(view, activity)
    override suspend fun requireOriginalActivity(sources: LocalCommerceOriginalSources,
        activity: LocalSalesActivity): Set<RecoveryResourceKey> = activityAuthority.requireOriginal(sources, activity)
    override suspend fun observeReservationAuthority(view: CommandReadView, lines: List<LocalStockAllocationRequest>): Set<RecoveryResourceKey> =
        observeCommerceReservationAuthority(view, lines, signatures)
    override suspend fun requireOriginalReservationAuthority(sources: LocalCommerceOriginalSources,
        line: com.ukkera.pos.inventory.store.LocalStockDemandLine): Set<RecoveryResourceKey> =
        originalCommerceReservationAuthority(sources, line, signatures)
    private val current = LocalCatalogFoodSource(signatures)
    override suspend fun prepareSelectionPreview(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodSelectionPreviewV1? =
        current.prepareSelectionPreview(view, activity, service, catalogRevision, portions)
    override suspend fun prepareRouting(view: CommandReadView,
        quotation: LocalFoodRecipeQuotationV1): PreparedFoodRecipeRouting? =
        current.prepareRouting(view, quotation)
    override suspend fun requireOriginalRouting(sources: LocalCommerceOriginalSources,
        quotation: LocalFoodRecipeQuotationV1, snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> =
        originalFoodRouting(sources, signatures, quotation, snapshot)
    override suspend fun prepareQuotation(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeQuotationBatchV1? {
        val observed = FoodCatalogueResourceRead(view)
        val prepared = current.prepareQuotation(observed, activity, service, catalogRevision, portions) ?: return null
        return object : PreparedFoodRecipeQuotationBatchV1 {
            override val scope: BranchScope = prepared.scope
            override val recoveryResources: Set<RecoveryResourceKey> = prepared.recoveryResources + observed.resources
            override fun quote(batchId: DomainId, observedAt: UtcInstant): LocalFoodRecipeQuotationBatchV1 =
                prepared.quote(batchId, observedAt)
        }
    }
    override suspend fun requireHistoricalQuotation(view: CommandReadView, quotation: LocalFoodRecipeQuotationV1) {
        val observed = FoodCatalogueResourceRead(view)
        current.requireHistoricalQuotation(observed, quotation)
        (view as? LocalCommerceResourceCollector)?.observeRecoveryResources(observed.resources)
    }
    override suspend fun requireOriginalHistoricalQuotation(sources: LocalCommerceOriginalSources,
        quotation: LocalFoodRecipeQuotationV1): Set<RecoveryResourceKey> =
        originalFoodQuotationHistorical(sources, signatures, quotation)
    override suspend fun requireOriginalQuotation(sources: LocalCommerceOriginalSources, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision, selections: List<LocalFoodPortionSelectionV1>,
        quoted: LocalFoodRecipeQuotationBatchV1): Set<RecoveryResourceKey> =
        originalFoodQuotation(sources, signatures, activity, service, catalogRevision, selections, quoted)
    override suspend fun prepareKitchenExecution(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, snapshots: List<LocalFoodRecipeSnapshotV1>): PreparedMutationAdmissionRestriction? =
        current.prepareKitchenExecution(view, activity, service, snapshots)
    override suspend fun prepare(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeBatchV1? {
        val observed = FoodCatalogueResourceRead(view)
        val prepared = current.prepare(observed, activity, service, catalogRevision, portions) ?: return null
        return object : PreparedFoodRecipeBatchV1 {
            override val scope: BranchScope = prepared.scope
            override val recoveryResources: Set<RecoveryResourceKey> = prepared.recoveryResources + observed.resources
            override fun quote(batchId: DomainId, at: UtcInstant): LocalFoodQuotedBatchV1 = prepared.quote(batchId, at)
        }
    }
    override suspend fun currentForSend(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Boolean {
        val observed = FoodCatalogueResourceRead(view)
        val current = current.currentForSend(observed, snapshot)
        if (current) (view as? LocalCommerceResourceCollector)?.observeRecoveryResources(observed.resources)
        return current
    }
    override suspend fun requireHistorical(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1) {
        val observed = FoodCatalogueResourceRead(view)
        current.requireHistorical(observed, snapshot)
        (view as? LocalCommerceResourceCollector)?.observeRecoveryResources(observed.resources)
    }
    override suspend fun requireOriginalHistorical(sources: LocalCommerceOriginalSources,
        snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> = OriginalFoodCatalogue(sources, signatures).let {
        it.historical(snapshot); it.resources.toSet()
    }
    override suspend fun requireOriginalCurrentForSend(sources: LocalCommerceOriginalSources,
        snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> = OriginalFoodCatalogue(sources, signatures).let {
        it.current(snapshot); it.resources.toSet()
    }
    override suspend fun requireOriginalQuote(sources: LocalCommerceOriginalSources, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision, selections: List<LocalFoodPortionSelectionV1>,
        quoted: LocalFoodQuotedBatchV1): Set<RecoveryResourceKey> = originalFoodQuote(sources, signatures, activity, service, catalogRevision, selections, quoted)
    override fun requireLegacyRecipe(snapshot: LocalFoodRecipeSnapshotV1, scope: BranchScope): Unit = requireSignedFoodSnapshot(snapshot, scope, signatures)
}

private val foodConfigurationKinds: Set<String> = setOf(LocalFoodConfigurationProfileV1.RECIPE,
    LocalFoodConfigurationProfileV1.PUBLICATION, LocalFoodConfigurationProfileV1.ROUTE, LocalFoodConfigurationProfileV1.STATION,
    LocalFoodConfigurationProfileV1.VERSION)
private fun foodCatalogueLiteralResources(key: AggregateKey, payload: StoredBytes): Set<RecoveryResourceKey> = when {
    key.kind in foodConfigurationKinds -> {
        val value = decodeValue(payload, FoodConfigurationRecordV1.serializer())
        foodConfigurationResources(value) + foodConfigResource(key.id) +
            listOfNotNull((value.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.signingKeyId?.let(::foodConfigResource))
    }
    key.kind in setOf(LocalConfigurationProfile.SKU, LocalConfigurationProfile.PUBLICATION, LocalConfigurationProfile.WAREHOUSE,
        LocalConfigurationProfile.CATALOG_CURSOR) -> {
        val value = decodeValue(payload, ConfigurationRecord.serializer())
        if (value.body is ConfigurationBody.Sku) companySourceResources(value, emptyList())
        else setOf(foodConfigResource(key.id), foodConfigResource(value.authorizedBy))
    }
    else -> emptySet() // Actual pricing helper supplies its separately typed full dependencies.
}
/** This is a forwarding decorator of the ACTUAL loan, not an original-view adapter. */
internal class FoodCatalogueResourceRead(private val borrowed: CommandReadView) : CommandReadView {
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf()
    override val scope: BranchScope get() = borrowed.scope
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = borrowed.aggregate(key).also { row ->
        if (row != null) resources += foodCatalogueLiteralResources(key, row.payload)
    }
    override suspend fun claim(key: ClaimKey): DomainId? = borrowed.claim(key)
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = borrowed.retainedCommand(operationId)
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage = borrowed.aggregateKeys(kind, after, limit)
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage = borrowed.claimKeys(namespace, prefix, after, limit)
}
private class OriginalFoodCatalogue(val source: LocalCommerceOriginalSources,
    private val signatures: CompanyPublicationSignatureVerifier) {
    val common = OriginalRetailCatalogue(source, signatures)
    val resources: MutableSet<RecoveryResourceKey> = mutableSetOf()
    suspend fun configuration(kind: String, id: DomainId): ConfigurationRecord {
        val value = checkNotNull(common.configuration(kind, id))
        val row = checkNotNull(source.aggregate(AggregateKey(kind, id)))
        resources += foodCatalogueLiteralResources(row.key, row.payload)
        return value
    }
    suspend fun food(kind: String, id: DomainId): Pair<LocalCommerceOriginalRow, FoodConfigurationRecordV1> {
        check(kind in foodConfigurationKinds)
        val row = checkNotNull(source.aggregate(LocalFoodConfigurationProfileV1.key(kind, id)))
        check(row.schema == LocalFoodConfigurationProfileV1.schema(kind))
        val record = decodeValue(row.payload, FoodConfigurationRecordV1.serializer())
        check(record.scope == source.scope && encodeValue(record, FoodConfigurationRecordV1.serializer()) == row.payload)
        if (kind == LocalFoodConfigurationProfileV1.VERSION) check(row.generation == Revision(1) && id.value == record.operationId.value)
        else if (kind == LocalFoodConfigurationProfileV1.PUBLICATION) check(row.generation == Revision(1) &&
            (record.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.definition?.publicationId == id)
        else check(record.id == id && record.kind == kind && record.revision == row.generation)
        val original = (source.original(record.operationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("food.stock.catalog_original_missing")
        requireFoodConfigurationProducer(original, signatures)
        check(original.scope == source.scope && original.aggregateWrites.single { it.key == row.key }.let {
            it.storageGeneration == row.generation && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
        })
        when (val request = record.request) {
            is LocalFoodConfigurationRequestV1.InstallRecipe -> check(source.claim(ClaimKey(LocalFoodConfigurationProfileV1.CLAIM,
                "publication/${request.recipe.definition.publicationId.value}")) == request.recipe.definition.recipeId)
            is LocalFoodConfigurationRequestV1.Route -> check(source.claim(foodRouteClaim(request.route.recipeId, request.route.activity, request.route.channel)) == request.route.id)
            is LocalFoodConfigurationRequestV1.Station -> Unit
        }
        resources += foodCatalogueLiteralResources(row.key, row.payload)
        return row to record
    }
    suspend fun historicalQuotation(quotation: LocalFoodRecipeQuotationV1) {
        val signed = requireSignedFoodQuotation(quotation, source.scope, signatures)
        val skus = linkedMapOf<DomainId, LocalPublishedSkuV1>()
        val skuReferences = linkedMapOf<DomainId, LocalFoodSourceReferenceV1>()
        var recipeReference: LocalFoodSourceReferenceV1? = null
        for (reference in quotation.sources) {
            when (reference.kind) {
                LocalFoodConfigurationProfileV1.PUBLICATION -> {
                    val (row, record) = food(reference.kind, reference.id)
                    val publication = (record.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe
                        ?: error("food.quotation.original_recipe_history_kind")
                    val actual = LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.generation, row.digest,
                        record.actorId, publication.signingKeyId, record.operationId)
                    check(recipeReference == null && reference == actual && publication == signed)
                    recipeReference = actual
                }
                LocalConfigurationProfile.PUBLICATION -> {
                    val record = configuration(reference.kind, reference.id)
                    val row = checkNotNull(source.aggregate(AggregateKey(reference.kind, reference.id)))
                    val publication = record.signedSku()
                    val actual = LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.generation, row.digest,
                        record.authorizedBy, publication.signingKeyId)
                    check(row.generation == Revision(1) && reference == actual)
                    check(skus.put(publication.definition.skuId, publication.definition) == null)
                    skuReferences[publication.definition.skuId] = actual
                }
                else -> error("food.quotation.original_history_kind")
            }
        }
        val recipe = signed.definition
        val choice = recipe.choices.single { it.id == quotation.choiceId }
        val ids = (listOf(recipe.outputSkuId, choice.pricingSkuId) + choice.ingredients.map { it.skuId }).distinct()
        check(skus.keys == ids.toSet() && validFoodDefinition(recipe.copy(choices = listOf(choice)), skus))
        requireFoodQuotationProjection(quotation, foodRecipeQuotation(quotation.portionId, quotation.activity,
            quotation.channel, signed, choice, listOf(checkNotNull(recipeReference)) + ids.map { skuReferences.getValue(it) }))
    }
    suspend fun historical(snapshot: LocalFoodRecipeSnapshotV1) {
        requireSignedFoodSnapshot(snapshot, source.scope, signatures)
        val configurations = mutableListOf<FoodConfigurationRecordV1>()
        val skus = linkedMapOf<DomainId, LocalPublishedSkuV1>()
        for (reference in snapshot.sources) {
            val key = AggregateKey(reference.kind, reference.id)
            when (reference.kind) {
                LocalFoodConfigurationProfileV1.PUBLICATION, LocalFoodConfigurationProfileV1.VERSION -> {
                    val (row, record) = food(reference.kind, reference.id)
                    check(row.generation == reference.revision && row.digest == reference.digest && record.operationId == reference.operationId &&
                        record.actorId == reference.authorizedBy &&
                        (record.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.signingKeyId == reference.signingKeyId)
                    check(if (reference.kind == LocalFoodConfigurationProfileV1.PUBLICATION)
                        reference.id == snapshot.publicationId && record.request is LocalFoodConfigurationRequestV1.InstallRecipe
                    else record.request is LocalFoodConfigurationRequestV1.Route || record.request is LocalFoodConfigurationRequestV1.Station)
                    configurations += record
                }
                LocalConfigurationProfile.PUBLICATION -> {
                    val record = configuration(reference.kind, reference.id)
                    val row = checkNotNull(source.aggregate(key))
                    check(row.generation == reference.revision && row.digest == reference.digest && reference.operationId == null &&
                        record.authorizedBy == reference.authorizedBy && record.signedSku().signingKeyId == reference.signingKeyId)
                    val sku = record.signedSku().definition
                    check(skus.put(sku.skuId, sku) == null)
                }
                else -> error("food.stock.original_catalog_kind")
            }
        }
        val recipe = (configurations.single { it.request is LocalFoodConfigurationRequestV1.InstallRecipe }.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe
        check(LocalFoodConfigurationCodecV1.signedPublication(recipe) == snapshot.signedPublication)
        val choice = recipe.definition.choices.single { it.id == snapshot.choiceId }
        check(skus.keys == (listOf(snapshot.outputSkuId, snapshot.pricingSkuId) + snapshot.ingredients.map { it.skuId }).toSet() &&
            validFoodDefinition(recipe.definition.copy(choices = listOf(choice)), skus))
        val routeRecord = configurations.single { it.request is LocalFoodConfigurationRequestV1.Route }
        val route = (routeRecord.request as LocalFoodConfigurationRequestV1.Route).route
        check(routeRecord.operationId.value == snapshot.routeVersionId.value && route.active && route.recipeId == snapshot.recipeId &&
            route.recipeRevision == snapshot.recipeRevision && route.warehouseId == snapshot.warehouseId &&
            route.activity == snapshot.activity && route.channel == snapshot.channel)
        val stations = configurations.filter { it.request is LocalFoodConfigurationRequestV1.Station }
        check(stations.size == snapshot.routes.size)
        for (station in snapshot.routes) {
            check(route.targets.single { it.routeKey == station.routeKey }.stationId == station.stationId)
            val record = stations.single { it.id == station.stationId }
            val definition = (record.request as LocalFoodConfigurationRequestV1.Station).station
            check(record.revision == station.stationRevision && definition.mode == station.mode && definition.active)
        }
    }
    suspend fun current(snapshot: LocalFoodRecipeSnapshotV1) {
        historical(snapshot)
        val recipe = (food(LocalFoodConfigurationProfileV1.RECIPE, snapshot.recipeId).second.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe.definition
        check(recipe.active && recipe.revision == snapshot.recipeRevision && recipe.publicationId == snapshot.publicationId)
        for (reference in snapshot.sources.filter { it.kind == LocalFoodConfigurationProfileV1.VERSION }) {
            val version = food(reference.kind, reference.id).second
            val latest = food(version.kind, version.id).second
            check(latest.operationId == version.operationId)
            when (val request = latest.request) {
                is LocalFoodConfigurationRequestV1.Route -> check(request.route.active)
                is LocalFoodConfigurationRequestV1.Station -> check(request.station.active)
                else -> error("food.stock.current_version_kind")
            }
        }
        for ((skuId, revision) in listOf(snapshot.outputSkuId to snapshot.outputSkuRevision) + snapshot.ingredients.map { it.skuId to it.skuRevision }) {
            val sku = configuration(LocalConfigurationProfile.SKU, skuId).signedSku().definition
            check(sku.active && sku.skuRevision == revision)
        }
        check((configuration(LocalConfigurationProfile.WAREHOUSE, snapshot.warehouseId).body as ConfigurationBody.Warehouse).value.active)
    }
}

/** Exact correspondence and applicability on one literal original cut, without execution authority. */
internal suspend fun originalFoodRouting(source: LocalCommerceOriginalSources,
    signatures: CompanyPublicationSignatureVerifier, quotation: LocalFoodRecipeQuotationV1,
    snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> {
    val checked = FoodRecipeRouting.requireOriginalFacts(quotation, snapshot)
    val catalogue = OriginalFoodCatalogue(source, signatures)
    catalogue.historicalQuotation(quotation)
    catalogue.current(checked)
    return foodRoutingResourceSet(catalogue.resources + foodRoutingSnapshotResources(checked))
}

/** Retain the complete alias union without exposing a mutable set or its iterator. */
private fun foodRoutingResourceSet(resources: Set<RecoveryResourceKey>): Set<RecoveryResourceKey> =
    object : AbstractSet<RecoveryResourceKey>() {
        private val entries = resources.toList()
        override val size: Int get() = entries.size
        override fun iterator(): Iterator<RecoveryResourceKey> = object : Iterator<RecoveryResourceKey> {
            private var index = 0
            override fun hasNext(): Boolean = index < entries.size
            override fun next(): RecoveryResourceKey {
                if (!hasNext()) throw NoSuchElementException()
                return entries[index++]
            }
        }
    }

internal suspend fun originalFoodQuotationHistorical(source: LocalCommerceOriginalSources,
    signatures: CompanyPublicationSignatureVerifier, quotation: LocalFoodRecipeQuotationV1): Set<RecoveryResourceKey> =
    OriginalFoodCatalogue(source, signatures).let { catalogue ->
        catalogue.historicalQuotation(quotation)
        catalogue.resources.toSet()
    }

/** Literal original capture only: no routed snapshot/current-view adapter or saved-operation authority. */
internal suspend fun originalFoodQuotation(source: LocalCommerceOriginalSources, signatures: CompanyPublicationSignatureVerifier,
    activity: LocalSalesActivity, service: LocalFoodServiceV1, catalogRevision: Revision,
    selections: List<LocalFoodPortionSelectionV1>, quoted: LocalFoodRecipeQuotationBatchV1): Set<RecoveryResourceKey> {
    check(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) &&
        selections.size in 1..LocalFoodProfileV1.MAX_PORTIONS_PER_COMMAND)
    // Keep the literal selection order/notes/line identity, without retaining caller-owned lists
    // across any suspended original read. Invalid original selections remain failures.
    val selected = selections.map { it.copy(modifierIds = it.modifierIds.toList()) }
    check(selected.map { it.portionId }.distinct().size == selected.size &&
        quoted.quotations.map { it.portionId } == selected.map { it.portionId })
    val groupQuantities = LocalFoodSelectionGroups.group(selected).associate { it.lineId to it.totalQuantity }
    val catalogue = OriginalFoodCatalogue(source, signatures)
    val cursor = catalogue.configuration(LocalConfigurationProfile.CATALOG_CURSOR, source.scope.branchId)
    check(cursor.revision == catalogRevision && cursor.body is ConfigurationBody.CatalogCursor)
    val resources = catalogue.resources
    val resolved = mutableListOf<ResolvedPriceLine>()
    val provenance = mutableListOf<LocalSalesPriceSourceV2>()
    var contexts = 0
    for ((selection, quotation) in selected.zip(quoted.quotations)) {
        catalogue.historicalQuotation(quotation)
        check(quotation.activity == activity && quotation.channel == service.channel && selection.itemId == quotation.outputSkuId &&
            selection.recipeId == quotation.recipeId && selection.expectedRecipeRevision == quotation.recipeRevision &&
            selection.choiceId == quotation.choiceId && selection.modifierIds.toSet() == quotation.modifierIds.toSet() &&
            selection.quantity == quotation.portionQuantity)
        val currentRecipe = catalogue.food(LocalFoodConfigurationProfileV1.RECIPE, selection.recipeId).second
        val (publicationRow, publication) = catalogue.food(LocalFoodConfigurationProfileV1.PUBLICATION, quotation.publicationId)
        check(currentRecipe == publication)
        val signed = (publication.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe
        val recipe = signed.definition
        val choice = recipe.choices.single { it.id == selection.choiceId }
        val references = mutableListOf(LocalFoodSourceReferenceV1(publicationRow.key.kind, publicationRow.key.id,
            publicationRow.generation, publicationRow.digest, publication.actorId, signed.signingKeyId, publication.operationId))
        val definitions = linkedMapOf<DomainId, LocalPublishedSkuV1>()
        val ids = (listOf(recipe.outputSkuId, choice.pricingSkuId) + choice.ingredients.map { it.skuId }).distinct()
        for (id in ids) {
            val current = catalogue.configuration(LocalConfigurationProfile.SKU, id)
            val definition = current.signedSku().definition
            val published = catalogue.configuration(LocalConfigurationProfile.PUBLICATION, definition.publicationId)
            check(published.signedSku() == current.signedSku())
            val row = checkNotNull(source.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, definition.publicationId)))
            check(row.generation == Revision(1))
            references += LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.generation, row.digest,
                published.authorizedBy, published.signedSku().signingKeyId)
            definitions[id] = definition
            resources += companySourceResources(current, if (id == choice.pricingSkuId) definition.prices else emptyList()) +
                RecoveryResourceKey(RecoveryResourceKind.STOCK, id)
        }
        check(validFoodDefinition(recipe.copy(choices = listOf(choice)), definitions))
        requireFoodQuotationProjection(quotation, foodRecipeQuotation(selection.portionId, activity, service.channel, signed, choice, references))
        val sku = definitions.getValue(choice.pricingSkuId)
        contexts += sku.prices.size
        check(contexts <= 128) // The unchanged capture price scope, not ingredient allocation count.
        val prices = catalogue.common.skuPrices(sku)
        resources += catalogue.common.resources
        val company = selectedRetailPrice(sku.prices, service.channel.name,
            groupQuantities.getValue(selection.commercialLineId), source.recordedAt)
        val price = prices.getValue(company.ruleId)
        val applied = price.applied(company, source.recordedAt)
        val effective = if (applied) checkNotNull(price.branch?.unitPrice) else company.unitPrice
        val policy = price.policy?.signed?.definition
        resolved += ResolvedPriceLine(selection.commercialLineId, selection.portionId, company.revision,
            selection.quantity, effective, company.inclusion, company.tax)
        provenance += LocalSalesPriceSourceV2(source.scope, selection.commercialLineId, selection.portionId, sku.skuId, sku.publicationId,
            company.ruleId, company.revision, localPriceDigest(company), company.unitPrice, effective, policy?.entryId, policy?.revision,
            policy?.publicationId, price.activation?.definitionDigest, if (applied) price.head?.eventId else null,
            if (applied) price.head?.revision else null)
        resources += foodConfigResource(signed.signingKeyId)
    }
    val expected = OperationalPricingEngine.calculate(PricingRequest(quoted.quote.snapshotId, Revision(0), Currency.EGP, resolved))
    check(StoredPricingSnapshotCodec.encode(expected) == StoredPricingSnapshotCodec.encode(quoted.quote) && quoted.priceSources == provenance)
    return resources.toSet()
}

private suspend fun originalFoodQuote(source: LocalCommerceOriginalSources, signatures: CompanyPublicationSignatureVerifier,
    activity: LocalSalesActivity, service: LocalFoodServiceV1, catalogRevision: Revision,
    selections: List<LocalFoodPortionSelectionV1>, quoted: LocalFoodQuotedBatchV1): Set<RecoveryResourceKey> {
    check(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) && selections.size in 1..LocalFoodProfileV1.MAX_PORTIONS_PER_COMMAND &&
        selections.map { it.portionId }.distinct().size == selections.size && quoted.snapshots.map { it.portionId } == selections.map { it.portionId })
    val groupQuantities = LocalFoodSelectionGroups.group(selections).associate { it.lineId to it.totalQuantity }
    val catalogue = OriginalFoodCatalogue(source, signatures)
    val cursor = catalogue.configuration(LocalConfigurationProfile.CATALOG_CURSOR, source.scope.branchId)
    check(cursor.revision == catalogRevision && cursor.body is ConfigurationBody.CatalogCursor)
    val resources = catalogue.resources
    val resolved = mutableListOf<ResolvedPriceLine>(); val provenance = mutableListOf<LocalSalesPriceSourceV2>()
    var contexts = 0
    for ((selection, snapshot) in selections.zip(quoted.snapshots)) {
        catalogue.current(snapshot)
        check(snapshot.activity == activity && snapshot.channel == service.channel && selection.itemId == snapshot.outputSkuId &&
            selection.recipeId == snapshot.recipeId && selection.expectedRecipeRevision == snapshot.recipeRevision && selection.choiceId == snapshot.choiceId &&
            selection.modifierIds.toSet() == snapshot.modifierIds.toSet() && selection.quantity == snapshot.portionQuantity)
        val currentRecipe = catalogue.food(LocalFoodConfigurationProfileV1.RECIPE, snapshot.recipeId).second
        val publication = catalogue.food(LocalFoodConfigurationProfileV1.PUBLICATION, snapshot.publicationId).second
        check(currentRecipe == publication)
        val routeId = checkNotNull(source.claim(foodRouteClaim(snapshot.recipeId, activity, service.channel)))
        val route = catalogue.food(LocalFoodConfigurationProfileV1.ROUTE, routeId).second
        check(route.operationId.value == snapshot.routeVersionId.value)
        resources += foodConfigurationResources(currentRecipe) + foodConfigurationResources(route)
        resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, snapshot.warehouseId)
        val choice = (publication.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe.definition.choices.single { it.id == snapshot.choiceId }
        val definitions = linkedMapOf<DomainId, LocalPublishedSkuV1>()
        val references = linkedMapOf<AggregateKey, LocalFoodSourceReferenceV1>()
        fun reference(row: LocalCommerceOriginalRow, by: DomainId, signingKey: DomainId? = null, operation: OperationId? = null) {
            references[row.key] = LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.generation, row.digest, by, signingKey, operation)
        }
        val signedRecipe = (publication.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe
        reference(checkNotNull(source.aggregate(LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.PUBLICATION, snapshot.publicationId))),
            publication.actorId, signedRecipe.signingKeyId, publication.operationId)
        val (routeVersionRow, routeVersion) = catalogue.food(LocalFoodConfigurationProfileV1.VERSION, DomainId(route.operationId.value))
        reference(routeVersionRow, routeVersion.actorId, operation = routeVersion.operationId)
        val selectedRoute = (route.request as LocalFoodConfigurationRequestV1.Route).route
        val stations = choice.routeKeys.map { routeKey ->
            val target = selectedRoute.targets.single { it.routeKey == routeKey }
            val (stationRow, stationRecord) = catalogue.food(LocalFoodConfigurationProfileV1.STATION, target.stationId)
            val station = (stationRecord.request as LocalFoodConfigurationRequestV1.Station).station
            check(station.active)
            val (versionRow, versionRecord) = catalogue.food(LocalFoodConfigurationProfileV1.VERSION, DomainId(stationRecord.operationId.value))
            reference(versionRow, versionRecord.actorId, operation = versionRecord.operationId)
            LocalFoodStationRouteV1(routeKey, station.id, stationRow.generation, station.mode)
        }
        check(stations.map { it.stationId }.distinct().size == stations.size)
        for (id in (listOf(snapshot.outputSkuId, snapshot.pricingSkuId) + snapshot.ingredients.map { it.skuId }).distinct()) {
            val current = catalogue.configuration(LocalConfigurationProfile.SKU, id)
            val definition = current.signedSku().definition
            val published = catalogue.configuration(LocalConfigurationProfile.PUBLICATION, definition.publicationId)
            check(published.signedSku() == current.signedSku())
            reference(checkNotNull(source.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, definition.publicationId))),
                published.authorizedBy, published.signedSku().signingKeyId)
            definitions[id] = definition
            resources += companySourceResources(current, if (id == snapshot.pricingSkuId) definition.prices else emptyList()) +
                RecoveryResourceKey(RecoveryResourceKind.STOCK, id)
        }
        val recipe = (publication.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe.definition
        check(validFoodDefinition(recipe.copy(choices = listOf(choice)), definitions))
        val expectedSnapshot = LocalFoodRecipeSnapshotV1(
            LocalFoodProfileV1.recipeSnapshotId(selection.portionId, recipe.publicationId, routeVersionRow.key.id),
            selection.portionId, routeVersionRow.key.id, activity, service.channel,
            recipe.recipeId, recipe.revision, recipe.publicationId, recipe.outputSkuId, recipe.outputSkuRevision,
            choice.id, choice.modifierIds.toList(), listOf(choice.names.english, choice.names.arabic).filter { it.isNotBlank() }.joinToString(" / "),
            recipe.yieldQuantity, recipe.portionQuantity, choice.pricingSkuId,
            choice.ingredients.map { component -> LocalFoodIngredientV1(component.componentId, component.skuId, component.skuRevision,
                component.quantityPerYield, Quantity(component.quantityPerYield.unitId,
                    (component.quantityPerYield.toFraction() * recipe.portionQuantity.toFraction() / recipe.yieldQuantity.toFraction())
                        .toScaledLongExact(component.quantityPerYield.scale), component.quantityPerYield.scale)) },
            selectedRoute.warehouseId, stations, LocalFoodConfigurationCodecV1.signedPublication(signedRecipe), references.values.toList())
        check(snapshot == expectedSnapshot) { "food.stock.original_recipe_projection_mismatch" }
        val sku = definitions.getValue(snapshot.pricingSkuId)
        contexts += sku.prices.size; check(contexts <= 128) // Existing per-command food price scope, not physical allocation count.
        val prices = catalogue.common.skuPrices(sku)
        resources += catalogue.common.resources
        val company = selectedRetailPrice(sku.prices, service.channel.name,
            groupQuantities.getValue(selection.commercialLineId), source.recordedAt)
        val price = prices.getValue(company.ruleId)
        val applied = price.applied(company, source.recordedAt)
        val effective = if (applied) checkNotNull(price.branch?.unitPrice) else company.unitPrice
        val policy = price.policy?.signed?.definition
        resolved += ResolvedPriceLine(selection.commercialLineId, selection.portionId, company.revision, selection.quantity, effective, company.inclusion, company.tax)
        provenance += LocalSalesPriceSourceV2(source.scope, selection.commercialLineId, selection.portionId, sku.skuId, sku.publicationId, company.ruleId,
            company.revision, localPriceDigest(company), company.unitPrice, effective, policy?.entryId, policy?.revision, policy?.publicationId,
            price.activation?.definitionDigest, if (applied) price.head?.eventId else null, if (applied) price.head?.revision else null)
        resources += foodConfigResource((publication.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe.signingKeyId)
        resources += RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, snapshot.snapshotId)
    }
    val expected = OperationalPricingEngine.calculate(PricingRequest(quoted.quote.snapshotId, Revision(0), Currency.EGP, resolved))
    check(StoredPricingSnapshotCodec.encode(expected) == StoredPricingSnapshotCodec.encode(quoted.quote) && quoted.priceSources == provenance)
    return resources.toSet()
}

/** Present-only stock authority, independently decoded on current and original sides. */
private suspend fun observeCommerceReservationAuthority(view: CommandReadView,
    lines: List<LocalStockAllocationRequest>, signatures: CompanyPublicationSignatureVerifier): Set<RecoveryResourceKey> {
    val resources = mutableSetOf<RecoveryResourceKey>()
    for (line in lines) {
        val itemRow = checkNotNull(view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, line.skuId)))
        val warehouseRow = checkNotNull(view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, line.warehouseId)))
        val item = decodeRecord(view.scope, itemRow); val warehouse = decodeRecord(view.scope, warehouseRow)
        val signed = item.signedSku(); val sku = signed.definition
        check(signatures.verify(sku.company, signed.signingKeyId, LocalConfigurationCodec.canonicalPublication(sku), signed.signatureHex))
        requireCommerceReservationDefinition(sku, warehouse, line)
        resources += commerceReservationAuthorityResources(item, warehouse)
    }
    return resources
}
private suspend fun originalCommerceReservationAuthority(source: LocalCommerceOriginalSources,
    line: com.ukkera.pos.inventory.store.LocalStockDemandLine,
    signatures: CompanyPublicationSignatureVerifier): Set<RecoveryResourceKey> {
    val catalogue = OriginalRetailCatalogue(source, signatures)
    val item = checkNotNull(catalogue.configuration(LocalConfigurationProfile.SKU, line.request.skuId))
    val warehouse = checkNotNull(catalogue.configuration(LocalConfigurationProfile.WAREHOUSE, line.request.warehouseId))
    val sku = item.signedSku().definition
    requireCommerceReservationDefinition(sku, warehouse, line.request)
    check(line.catalogRevision == sku.catalogRevision && line.skuRevision == sku.skuRevision)
    return commerceReservationAuthorityResources(item, warehouse)
}
private fun requireCommerceReservationDefinition(sku: LocalPublishedSkuV1, warehouse: ConfigurationRecord,
    line: LocalStockAllocationRequest) {
    check(sku.skuId == line.skuId && sku.active && sku.tracking in setOf(LocalTrackingV1.QUANTITY, LocalTrackingV1.LOT) &&
        sku.baseUnitId == line.baseQuantity.unitId && sku.quantityScale == line.baseQuantity.scale && line.baseQuantity.scaled > 0 &&
        (warehouse.body as ConfigurationBody.Warehouse).value.let { it.id == line.warehouseId && it.active })
}
private fun commerceReservationAuthorityResources(item: ConfigurationRecord, warehouse: ConfigurationRecord): Set<RecoveryResourceKey> =
    companySourceResources(item, emptyList()) + setOf(foodConfigResource(warehouse.id), foodConfigResource(warehouse.authorizedBy),
        RecoveryResourceKey(RecoveryResourceKind.STOCK, item.id), RecoveryResourceKey(RecoveryResourceKind.STOCK, warehouse.id))
