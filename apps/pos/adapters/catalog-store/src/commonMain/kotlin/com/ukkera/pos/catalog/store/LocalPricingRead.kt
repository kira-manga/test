package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Captured immutable sources only; evaluation retains no live writer or client collections. */
internal class PreparedLocalSkuPricing(
    val scope: BranchScope,
    val sku: LocalPublishedSkuV1,
    private val highWater: Map<DomainId, PriceHighWaterV1?>,
    private val policies: Map<DomainId, ReadPricePolicy?>,
    private val overrides: Map<DomainId, ReadBranchPrice?>,
    val resources: Set<RecoveryResourceKey>,
) {
    fun resolve(channel: String, quantity: Quantity, at: UtcInstant): SelectedLocalPrice {
        val company = selectedRetailPrice(sku.prices, channel, quantity, at)
        val policy = policies[company.ruleId]
        val override = overrides[company.ruleId]
        val disposition = branchPriceDisposition(company, policy, override, at)
        val effective = if (disposition == "APPLIED") checkNotNull(override?.change?.unitPrice) else company.unitPrice
        return SelectedLocalPrice(scope, sku, company, policy, if (disposition == "APPLIED") override else null, effective,
            context(company.ruleId, at))
    }

    fun context(ruleId: DomainId, at: UtcInstant): LocalPriceContextViewV1 {
        val company = sku.prices.singleOrNull { it.ruleId == ruleId }
        val policy = policies[ruleId]
        val branch = overrides[ruleId]
        return LocalPriceContextViewV1(sku.skuId, ruleId, priceContextId(scope.company(), sku.skuId, ruleId), company,
            highWater[ruleId]?.price?.revision, policy?.let { policyView(it.definition, it, at) }, branch?.let {
                LocalBranchPriceViewV1(it.head.eventId, it.head.revision, it.row.revision, it.change.companyPriceRevision,
                    it.change.policyEntryRevision, it.change.unitPrice, it.change.effectiveFrom, it.change.effectiveUntil,
                    branchPriceDisposition(company, policy, it, at))
            })
    }
}

internal class SelectedLocalPrice(
    private val scope: BranchScope,
    private val sku: LocalPublishedSkuV1,
    val company: LocalPriceV1,
    private val policy: ReadPricePolicy?,
    private val override: ReadBranchPrice?,
    val effectiveUnitPrice: UnitPrice,
    val context: LocalPriceContextViewV1,
) {
    fun provenance(lineId: DomainId, portionId: DomainId): LocalSalesPriceSourceV2 = LocalSalesPriceSourceV2(
        scope, lineId, portionId, sku.skuId, sku.publicationId, company.ruleId, company.revision, localPriceDigest(company),
        company.unitPrice, effectiveUnitPrice, policy?.definition?.entryId, policy?.definition?.revision,
        policy?.definition?.publicationId, policy?.value?.definitionDigest, override?.head?.eventId, override?.head?.revision)
}

internal suspend fun prepareLocalSkuPricing(
    view: CommandReadView, sku: LocalPublishedSkuV1, signatures: CompanyPublicationSignatureVerifier,
    requestedRuleIds: List<DomainId> = sku.prices.map { it.ruleId },
): PreparedLocalSkuPricing {
    check(sku.company == view.scope.company() && requestedRuleIds.size <= 64 && requestedRuleIds.distinct().size == requestedRuleIds.size)
    val index = readPriceHistoryIndex(view)
    val waters = linkedMapOf<DomainId, PriceHighWaterV1?>()
    val policies = linkedMapOf<DomainId, ReadPricePolicy?>()
    val branches = linkedMapOf<DomainId, ReadBranchPrice?>()
    val resources = mutableSetOf(priceResource(view.scope.branchId), priceResource(sku.skuId), priceResource(sku.publicationId))
    for (id in requestedRuleIds) {
        val current = sku.prices.singleOrNull { it.ruleId == id }
        val water = readPriceHighWater(view, sku.skuId, id, signatures)
        val policy = readPricePolicy(view, sku.skuId, id, signatures)
        val branch = readBranchPrice(view, sku.skuId, id)
        if (index?.second?.state == PriceHistoryIndexState.COMPLETE && current != null)
            check(water?.value?.price == current) { "Current company price is not its indexed high-water" }
        if (branch != null) check(index?.second?.state == PriceHistoryIndexState.COMPLETE) { "Branch price exists without complete company history" }
        if (policy != null) check(policy.definition.authorizationReferenceQuantity.unitId == sku.baseUnitId &&
            policy.definition.authorizationReferenceQuantity.scale == sku.quantityScale)
        waters[id] = water?.value
        policies[id] = policy
        branches[id] = branch
        resources += priceResource(priceContextId(view.scope.company(), sku.skuId, id))
        resources += priceResource(id)
        resources += water?.resources.orEmpty()
        resources += policy?.resources.orEmpty()
        resources += branch?.resources.orEmpty()
    }
    return PreparedLocalSkuPricing(view.scope, sku, waters.toMap(), policies.toMap(), branches.toMap(), resources.toSet())
}

internal fun branchPriceDisposition(company: LocalPriceV1?, policy: ReadPricePolicy?, branch: ReadBranchPrice?, at: UtcInstant): String {
    if (branch == null) return "NONE"
    if (branch.change.unitPrice == null) return "INHERITED"
    if (company == null) return "COMPANY_CONTEXT_REMOVED"
    if (branch.change.companyPriceRevision != company.revision || branch.change.companyPriceDigest != localPriceDigest(company))
        return "SUPERSEDED_COMPANY_REVISION"
    if (at < company.effectiveFrom || company.effectiveUntil?.let { at >= it } == true) return "COMPANY_CONTEXT_NOT_EFFECTIVE"
    if (policy == null || !policy.definition.branchOverridesEnabled) return "COMPANY_OVERRIDES_DISABLED"
    if (branch.change.policyEntryId != policy.definition.entryId || branch.change.policyEntryRevision != policy.definition.revision ||
        branch.change.policyEntryDigest != policy.value.definitionDigest) return "SUPERSEDED_POLICY_ENTRY"
    if (at < policy.definition.effectiveFrom || policy.definition.effectiveUntil?.let { at >= it } == true) return "POLICY_NOT_EFFECTIVE"
    if (at < branch.change.effectiveFrom || branch.change.effectiveUntil?.let { at >= it } == true) return "OVERRIDE_NOT_EFFECTIVE"
    return "APPLIED"
}

internal fun policyView(definition: LocalPricePolicyEntryV1, current: ReadPricePolicy?, at: UtcInstant): LocalPricePolicyViewV1 {
    val selected = current?.definition?.publicationId == definition.publicationId
    val status = when {
        definition.effectiveUntil?.let { at >= it } == true -> if (selected) "EXPIRED" else "RECEIVED_EXPIRED"
        selected && at >= definition.effectiveFrom -> "ACTIVE"
        !selected && current != null && current.definition.revision >= definition.revision -> "RECEIVED_SUPERSEDED"
        at < definition.effectiveFrom -> "SCHEDULED_RECEIVED"
        else -> "RECEIVED"
    }
    return LocalPricePolicyViewV1(definition.entryId, definition.revision, definition.publicationId, definition.skuId, definition.ruleId,
        definition.branchOverridesEnabled, definition.authorizationReferenceQuantity, definition.effectiveFrom, definition.effectiveUntil,
        status, current?.row?.revision)
}

/** Protected, bounded, purpose-specific projections; no signed envelopes, keys or reusable grants. */
public class LocalPricingReadHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterReadHandler {
    override val key: ReadKey = LocalPricingProfile.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalPricingCodec.query(decodeValue(input, LocalPricingQueryV1.serializer()))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = decodeValue(normalized, LocalPricingQueryV1.serializer())
        val required = listOf(AuthorizationRequirement(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), view.scope)) +
            if (query is LocalPricingQueryV1.History || query is LocalPricingQueryV1.CompanyPublication)
                listOf(AuthorizationRequirement(Permission(SecurityAction.CATALOG_AUDIT_READ, PermissionVerb.PERFORM), view.scope)) else emptyList()
        val project: (UtcInstant) -> LocalPricingPageV1 = when (query) {
            is LocalPricingQueryV1.IndexStatus -> {
                val index = readPriceHistoryIndex(view)
                val projection: (UtcInstant) -> LocalPricingPageV1 = { _ ->
                    LocalPricingPageV1(indexStatus = index?.second?.state?.name ?: "UNINDEXED", indexStorageRevision = index?.first?.revision)
                }
                projection
            }
            is LocalPricingQueryV1.PolicyPublication -> {
                val published = readPricePolicyPublication(view, query.publicationId, signatures)
                val definition = published?.second?.signed?.definition
                val current = definition?.let { readPricePolicy(view, it.skuId, it.ruleId, signatures) }
                val projection: (UtcInstant) -> LocalPricingPageV1 = { at -> LocalPricingPageV1(policy = definition?.let { policyView(it, current, at) }) }
                projection
            }
            is LocalPricingQueryV1.CompanyPublication -> {
                val source = readSignedSku(view, LocalConfigurationProfile.PUBLICATION, query.publicationId, signatures)?.second
                val definition = source?.signedSku()?.definition
                val history = if (source == null || definition == null) null else LocalCompanyPriceHistoryViewV1(
                    definition.skuId, definition.publicationId, definition.skuRevision, definition.catalogRevision,
                    definition.prices.toList(), source.authorizedBy, source.recordedAt)
                val projection: (UtcInstant) -> LocalPricingPageV1 = { _ -> LocalPricingPageV1(companyPublication = history) }
                projection
            }
            is LocalPricingQueryV1.Context -> {
                val sku = readSignedSku(view, LocalConfigurationProfile.SKU, query.skuId, signatures)?.second?.signedSku()?.definition
                val prepared = sku?.let { prepareLocalSkuPricing(view, it, signatures, listOf(query.ruleId)) }
                val projection: (UtcInstant) -> LocalPricingPageV1 = { at -> LocalPricingPageV1(context = prepared?.context(query.ruleId, at)) }
                projection
            }
            is LocalPricingQueryV1.Preview -> {
                val sku = readSignedSku(view, LocalConfigurationProfile.SKU, query.skuId, signatures)?.second?.signedSku()?.definition
                val prepared = sku?.let { prepareLocalSkuPricing(view, it, signatures) }
                val projection: (UtcInstant) -> LocalPricingPageV1 = { at ->
                    val result = if (prepared == null || sku == null || !sku.active || !sku.canSell || query.quantity.unitId != sku.baseUnitId ||
                        query.quantity.scale != sku.quantityScale) LocalPricePreviewV1(query.skuId, query.channel, query.quantity, null, null, "catalog.sku_or_quantity_unavailable")
                    else try {
                        val selected = prepared.resolve(query.channel, query.quantity, at)
                        LocalPricePreviewV1(query.skuId, query.channel, query.quantity, selected.context, selected.effectiveUnitPrice, "catalog.price_selected")
                    } catch (failure: LocalSalesQuoteUnavailable) {
                        LocalPricePreviewV1(query.skuId, query.channel, query.quantity, null, null, "catalog.${failure.reason.name.lowercase()}")
                    }
                    LocalPricingPageV1(preview = result)
                }
                projection
            }
            is LocalPricingQueryV1.History -> {
                val head = readPriceHistoryHead(view, query.skuId)
                var next = if (query.afterEventId == null) head?.second?.eventId else {
                    val cursor = readPriceHistoryEvent(view, query.skuId, query.afterEventId)
                    cursor?.second?.priorEventId
                }
                val items = mutableListOf<LocalPriceHistoryItemV1>()
                val seen = mutableSetOf<DomainId>()
                var expectedDigest: PayloadDigest? = if (query.afterEventId == null) head?.second?.eventDigest else
                    readPriceHistoryEvent(view, query.skuId, query.afterEventId)?.second?.priorEventDigest
                var expectedNumber: Revision? = if (query.afterEventId == null) head?.second?.count else
                    readPriceHistoryEvent(view, query.skuId, query.afterEventId)?.second?.number?.let { if (it.value > 1) Revision(it.value - 1) else null }
                while (next != null && items.size < query.limit) {
                    val id = next
                    check(seen.add(id)) { "Pricing history cycle" }
                    val (row, event) = readPriceHistoryEvent(view, query.skuId, id) ?: error("Pricing history link lost its event")
                    check(row.digest == expectedDigest && event.number == expectedNumber)
                    val source = event.companyPublicationId?.let { readSignedSku(view, LocalConfigurationProfile.PUBLICATION, it, signatures) }
                    check(event.companyPublicationId == null || source?.second?.signedSku()?.definition?.skuId == query.skuId)
                    val policy = event.policyPublicationId?.let { readPricePolicyPublication(view, it, signatures) }
                    check(event.policyPublicationId == null || policy?.second?.signed?.definition?.skuId == query.skuId)
                    items += LocalPriceHistoryItemV1(event.eventId, event.kind.name, event.skuId,
                        event.branchChange?.ruleId ?: policy?.second?.signed?.definition?.ruleId,
                        event.companyPublicationId, event.policyPublicationId, event.branchChange?.unitPrice,
                        source?.second?.authorizedBy ?: event.actorId, source?.second?.recordedAt ?: event.recordedAt, event.branchChange?.reasonCode)
                    next = event.priorEventId
                    expectedDigest = event.priorEventDigest
                    expectedNumber = if (event.number.value > 1) Revision(event.number.value - 1) else null
                }
                val page = LocalPricingPageV1(history = items.toList(), nextEventId = if (next != null) items.lastOrNull()?.eventId else null)
                val projection: (UtcInstant) -> LocalPricingPageV1 = { _ -> page }
                projection
            }
        }
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = required
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == required.size && authorizations.zip(required).all { (authorization, requirement) ->
                    authorization.isBoundTo(requirement, context.actorId)
                })
                return encodeValue(project(observedAt), LocalPricingPageV1.serializer())
            }
        }
    }
}
