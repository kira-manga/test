package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
internal enum class PriceHistoryIndexState { INDEXING, COMPLETE }

/** COMPLETE is issued only by an empty-baseline proof or a bounded full publication scan. */
@Serializable
internal data class PriceHistoryIndexV1(
    val scope: BranchScope,
    val state: PriceHistoryIndexState,
    val capturedCursorRevision: Revision?,
    val capturedCursorDigest: PayloadDigest?,
    val afterPublicationId: DomainId?,
    val indexedPublications: Revision,
)

@Serializable
internal data class PriceHighWaterV1(
    val scope: BranchScope, val skuId: DomainId, val ruleId: DomainId,
    val price: LocalPriceV1, val publicationId: DomainId,
)

@Serializable
internal data class PriceHistoryHeadV1(
    val scope: BranchScope, val skuId: DomainId, val eventId: DomainId,
    val eventDigest: PayloadDigest, val count: Revision,
)

@Serializable
internal enum class PriceHistoryEventKind { COMPANY_PUBLICATION, INDEXED_COMPANY_PUBLICATION, POLICY_ACTIVATION, BRANCH_CHANGE }

/** Exact independent company/policy/branch domains, including the authoritative limit basis. */
@Serializable
internal data class PriceBranchChangeV1(
    val ruleId: DomainId,
    val overrideRevision: Revision,
    val companyPriceRevision: Revision,
    val companyPriceDigest: PayloadDigest,
    val companyPublicationId: DomainId,
    val policyEntryId: DomainId,
    val policyEntryRevision: Revision,
    val policyPublicationId: DomainId,
    val policyEntryDigest: PayloadDigest,
    val unitPrice: UnitPrice?,
    val authorizationReferenceQuantity: Quantity,
    val authorizationBase: Money,
    val authorizationDeviation: Money,
    val effectiveFrom: UtcInstant,
    val effectiveUntil: UtcInstant?,
    val reasonCode: String,
)

@Serializable
internal data class PriceHistoryEventV1(
    val scope: BranchScope, val skuId: DomainId, val eventId: DomainId,
    val number: Revision, val priorEventId: DomainId?, val priorEventDigest: PayloadDigest?,
    val kind: PriceHistoryEventKind,
    val companyPublicationId: DomainId?, val policyPublicationId: DomainId?,
    val branchChange: PriceBranchChangeV1?,
    val operationId: OperationId,
    val actorId: DomainId, val actorPolicyRevision: Revision, val actionApprovalId: DomainId?,
    val recordedAt: UtcInstant,
)

internal fun priceResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)

internal suspend fun <T> readPricingValue(
    view: CommandReadView, kind: String, id: DomainId, serializer: KSerializer<T>,
): Pair<StoredAggregate, T>? {
    val key = LocalPricingProfile.key(kind, id)
    val row = view.aggregate(key) ?: return null
    check(row.key == key)
    return decodePricingValue(row, serializer)
}

/** The same literal sidecar checks apply to live rows and actual native BEFORE sources. */
internal fun <T> decodePricingValue(row: StoredAggregate, serializer: KSerializer<T>): Pair<StoredAggregate, T> {
    check(row.schema == LocalPricingProfile.schema(row.key.kind) && row.revision.value >= 1)
    val value = decodeValue(row.payload, serializer)
    check(encodeValue(value, serializer) == row.payload) { "Noncanonical pricing sidecar" }
    return row to value
}

internal suspend fun readPriceHistoryIndex(view: CommandReadView): Pair<StoredAggregate, PriceHistoryIndexV1>? =
    readPricingValue(view, LocalPricingProfile.HISTORY_INDEX, view.scope.branchId, PriceHistoryIndexV1.serializer())?.also { (_, value) ->
        requirePriceHistoryIndex(view.scope, value)
    }

internal fun requirePriceHistoryIndex(scope: BranchScope, value: PriceHistoryIndexV1) {
    check(value.scope == scope && (value.capturedCursorRevision == null) == (value.capturedCursorDigest == null))
    check(value.state == PriceHistoryIndexState.COMPLETE || value.afterPublicationId != null || value.indexedPublications == Revision(0))
}

internal suspend fun readSignedSku(
    view: CommandReadView, kind: String, id: DomainId, signatures: CompanyPublicationSignatureVerifier,
): Pair<StoredAggregate, ConfigurationRecord>? {
    check(kind == LocalConfigurationProfile.SKU || kind == LocalConfigurationProfile.PUBLICATION)
    val row = view.aggregate(LocalConfigurationProfile.key(kind, id)) ?: return null
    val value = decodeRecord(view.scope, row)
    val signed = (value.body as? ConfigurationBody.Sku)?.value ?: error("Price source is not a company SKU")
    check(signatures.verify(signed.definition.company, signed.signingKeyId,
        LocalConfigurationCodec.canonicalPublication(signed.definition), signed.signatureHex)) { "Company price source signature unavailable" }
    return row to value
}

internal fun ConfigurationRecord.signedSku(): LocalSignedSkuV1 = (body as ConfigurationBody.Sku).value

/** Exact signature/installer dependencies plus only the price contexts actually consumed. */
internal fun companySourceResources(record: ConfigurationRecord, prices: List<LocalPriceV1>): Set<RecoveryResourceKey> {
    val signed = record.signedSku()
    val sku = signed.definition
    return setOf(priceResource(record.id), priceResource(record.authorizedBy), priceResource(signed.signingKeyId),
        priceResource(sku.skuId), priceResource(sku.productId), priceResource(sku.publicationId)) +
        prices.flatMap { listOf(priceResource(it.ruleId), priceResource(it.tax.policyId)) } +
        listOfNotNull(sku.returnPromise?.policyId?.let(::priceResource)) + localCatalogUnitResources(sku)
}

internal class ReadPriceHighWater(val row: StoredAggregate, val value: PriceHighWaterV1, source: ConfigurationRecord) {
    val resources: Set<RecoveryResourceKey> = companySourceResources(source, listOf(value.price)) + priceResource(row.key.id)
}

internal suspend fun readPriceHighWater(
    view: CommandReadView, skuId: DomainId, ruleId: DomainId, signatures: CompanyPublicationSignatureVerifier,
): ReadPriceHighWater? {
    val id = priceContextId(view.scope.company(), skuId, ruleId)
    val source = readPricingValue(view, LocalPricingProfile.COMPANY_HIGH_WATER, id, PriceHighWaterV1.serializer()) ?: return null
    val value = source.second
    check(value.scope == view.scope && value.skuId == skuId && value.ruleId == ruleId && value.price.ruleId == ruleId)
    val publication = readSignedSku(view, LocalConfigurationProfile.PUBLICATION, value.publicationId, signatures)
        ?: error("Company price high-water lost its signed publication")
    return resolvePriceHighWater(view.scope, skuId, ruleId, source, publication.second)
}

internal fun resolvePriceHighWater(scope: BranchScope, skuId: DomainId, ruleId: DomainId,
    source: Pair<StoredAggregate, PriceHighWaterV1>, publication: ConfigurationRecord): ReadPriceHighWater {
    val value = source.second
    check(value.scope == scope && value.skuId == skuId && value.ruleId == ruleId && value.price.ruleId == ruleId)
    val sku = publication.signedSku().definition
    check(sku.skuId == skuId && sku.prices.singleOrNull { it.ruleId == ruleId } == value.price)
    return ReadPriceHighWater(source.first, value, publication)
}

internal suspend fun readPriceHistoryEvent(view: CommandReadView, skuId: DomainId, id: DomainId): Pair<StoredAggregate, PriceHistoryEventV1>? =
    readPricingValue(view, LocalPricingProfile.HISTORY_EVENT, id, PriceHistoryEventV1.serializer())?.also {
        requirePriceHistoryEvent(view.scope, skuId, id, it)
    }

internal fun requirePriceHistoryEvent(scope: BranchScope, skuId: DomainId, id: DomainId,
    event: Pair<StoredAggregate, PriceHistoryEventV1>) {
    val (row, value) = event
    check(row.revision == Revision(1) && value.scope == scope && value.skuId == skuId && value.eventId == id)
    check(value.operationId.value == id.value && value.number.value >= 1)
    check((value.priorEventId == null) == (value.priorEventDigest == null))
    check((value.number == Revision(1)) == (value.priorEventId == null))
    when (value.kind) {
        PriceHistoryEventKind.COMPANY_PUBLICATION, PriceHistoryEventKind.INDEXED_COMPANY_PUBLICATION ->
            check(value.companyPublicationId != null && value.policyPublicationId == null && value.branchChange == null)
        PriceHistoryEventKind.POLICY_ACTIVATION ->
            check(value.companyPublicationId == null && value.policyPublicationId != null && value.branchChange == null)
        PriceHistoryEventKind.BRANCH_CHANGE ->
            check(value.companyPublicationId == null && value.policyPublicationId == null && value.branchChange != null)
    }
}

internal suspend fun readPriceHistoryHead(view: CommandReadView, skuId: DomainId): Pair<StoredAggregate, PriceHistoryHeadV1>? {
    val result = readPricingValue(view, LocalPricingProfile.HISTORY_HEAD, skuId, PriceHistoryHeadV1.serializer()) ?: return null
    val (row, value) = result
    check(value.scope == view.scope && value.skuId == skuId && value.count == row.revision)
    val event = readPriceHistoryEvent(view, skuId, value.eventId) ?: error("Pricing history head lost its event")
    check(event.first.digest == value.eventDigest && event.second.number == value.count)
    return result
}

internal class PreparedPriceHistoryAppend(
    private val scope: BranchScope,
    private val skuId: DomainId,
    private val eventId: DomainId,
    private val previous: Pair<StoredAggregate, PriceHistoryHeadV1>?,
) {
    val hasHistory: Boolean get() = previous != null
    val hasCapacity: Boolean get() = previous?.first?.revision?.value != Long.MAX_VALUE
    val resources: Set<RecoveryResourceKey> = setOf(priceResource(skuId), priceResource(eventId)) +
        previous?.second?.let { setOf(priceResource(it.eventId)) }.orEmpty()

    fun changes(kind: PriceHistoryEventKind, companyPublicationId: DomainId?, policyPublicationId: DomainId?,
        branch: PriceBranchChangeV1?, context: CommandContext, authorization: AuthorizedAction, at: UtcInstant): List<AggregateChange> {
        return changes(kind, companyPublicationId, policyPublicationId, branch,
            LocalPricingIdentity(context.scope, context.originalActorId, context.operationId, context.semanticDigest),
            LocalPricingActorAudit(authorization.actorId, authorization.policyRevision, authorization.approvalId), at)
    }

    fun changes(kind: PriceHistoryEventKind, companyPublicationId: DomainId?, policyPublicationId: DomainId?,
        branch: PriceBranchChangeV1?, identity: LocalPricingIdentity, actor: LocalPricingActorAudit, at: UtcInstant): List<AggregateChange> {
        check(hasCapacity && identity.scope == scope && identity.operationId.value == eventId.value && actor.actorId == identity.actorId)
        val number = previous?.second?.count?.next() ?: Revision(1)
        val event = PriceHistoryEventV1(scope, skuId, eventId, number, previous?.second?.eventId, previous?.second?.eventDigest,
            kind, companyPublicationId, policyPublicationId, branch, identity.operationId,
            actor.actorId, actor.policyRevision, actor.actionApprovalId, at)
        val bytes = encodeValue(event, PriceHistoryEventV1.serializer())
        val head = PriceHistoryHeadV1(scope, skuId, eventId, sha256(bytes.copyBytes()), number)
        return listOf(
            AggregateChange(LocalPricingProfile.key(LocalPricingProfile.HISTORY_EVENT, eventId), null,
                LocalPricingProfile.schema(LocalPricingProfile.HISTORY_EVENT), bytes),
            AggregateChange(LocalPricingProfile.key(LocalPricingProfile.HISTORY_HEAD, skuId), previous?.first?.revision,
                LocalPricingProfile.schema(LocalPricingProfile.HISTORY_HEAD), encodeValue(head, PriceHistoryHeadV1.serializer())),
        )
    }
}

internal suspend fun preparePriceHistoryAppend(view: CommandReadView, skuId: DomainId, context: CommandContext): PreparedPriceHistoryAppend {
    val eventId = DomainId(context.operationId.value)
    check(view.aggregate(LocalPricingProfile.key(LocalPricingProfile.HISTORY_EVENT, eventId)) == null) { "Unrecorded operation already owns pricing history" }
    return PreparedPriceHistoryAppend(view.scope, skuId, eventId, readPriceHistoryHead(view, skuId))
}

internal class PreparedCompanyPriceHistory(
    val rejection: String?,
    val resources: Set<RecoveryResourceKey>,
    val claims: List<ClaimChange>,
    private val sidecars: List<AggregateChange>,
    private val append: PreparedPriceHistoryAppend,
    private val publicationId: DomainId,
) {
    fun changes(context: CommandContext, authorization: AuthorizedAction, at: UtcInstant): List<AggregateChange> =
        sidecars + append.changes(PriceHistoryEventKind.COMPANY_PUBLICATION, publicationId, null, null, context, authorization, at)

    fun changes(identity: LocalPricingIdentity, actor: LocalPricingActorAudit, at: UtcInstant): List<AggregateChange> =
        sidecars + append.changes(PriceHistoryEventKind.COMPANY_PUBLICATION, publicationId, null, null, identity, actor, at)
}

internal class PreparedPriceVersion(
    val conflict: Boolean,
    val acquisition: ClaimChange.Acquire?,
    val resources: Set<RecoveryResourceKey>,
)

/** A high-water alone cannot detect two contradictory OLDER revisions in an unordered backfill. */
internal suspend fun preparePriceVersion(
    view: CommandReadView, skuId: DomainId, publicationId: DomainId, price: LocalPriceV1,
    signatures: CompanyPublicationSignatureVerifier,
): PreparedPriceVersion {
    val contextId = priceContextId(view.scope.company(), skuId, price.ruleId)
    val claim = ClaimKey(LocalPricingProfile.VERSION_CLAIM, "${contextId.value}/${price.revision.value}")
    val retainedId = view.claim(claim)
    val retained = retainedId?.let {
        readSignedSku(view, LocalConfigurationProfile.PUBLICATION, it, signatures)
            ?: error("Price version claim lost its signed publication")
    }
    return preparePriceVersion(view.scope, skuId, publicationId, price, retainedId, retained?.second)
}

internal fun preparePriceVersion(scope: BranchScope, skuId: DomainId, publicationId: DomainId,
    price: LocalPriceV1, retainedId: DomainId?, retained: ConfigurationRecord?): PreparedPriceVersion {
    check((retainedId == null) == (retained == null))
    val contextId = priceContextId(scope.company(), skuId, price.ruleId)
    val claim = ClaimKey(LocalPricingProfile.VERSION_CLAIM, "${contextId.value}/${price.revision.value}")
    val retainedDefinition = retained?.signedSku()?.definition
    // Verify complete identity in addition to its derived key. Never accept a hash collision
    // or a dangling claim as evidence for terms supplied by the new request.
    val retainedPrice = retainedDefinition?.prices?.singleOrNull { it.ruleId == price.ruleId && it.revision == price.revision }
    check(retainedDefinition == null || retainedDefinition.company == scope.company() &&
        retainedDefinition.skuId == skuId && retainedPrice != null) { "Price version claim identity mismatch" }
    return PreparedPriceVersion(retainedPrice != null && retainedPrice != price,
        if (retainedId == null) ClaimChange.Acquire(claim, publicationId) else null,
        setOf(priceResource(contextId), priceResource(price.ruleId)) + retained?.let {
            companySourceResources(it, listOf(checkNotNull(retainedPrice)))
        }.orEmpty())
}

internal class CompanyPriceHistoryRuleSources(
    val ruleId: DomainId, val policy: ReadPricePolicy?, val branch: ReadBranchPrice?,
)

internal class CompanyPriceHistoryPriceSources(
    val price: LocalPriceV1, val version: PreparedPriceVersion, val high: ReadPriceHighWater?,
)

/** Typed observations only. Both readers select their own genuine transaction/cut. */
internal class CompanyPriceHistoryInputs(
    val index: Pair<StoredAggregate, PriceHistoryIndexV1>?,
    val append: PreparedPriceHistoryAppend,
    val hasRetainedPublications: Boolean,
    val rules: List<CompanyPriceHistoryRuleSources>,
    val prices: List<CompanyPriceHistoryPriceSources>,
)

/** High-water survives omission; relevant reintroduction must use a strictly higher company revision. */
internal suspend fun prepareCompanyPriceHistory(
    view: CommandReadView, context: CommandContext, signed: LocalSignedSkuV1,
    previous: LocalPublishedSkuV1?, signatures: CompanyPublicationSignatureVerifier,
): PreparedCompanyPriceHistory {
    val sku = signed.definition
    val index = readPriceHistoryIndex(view)
    val append = preparePriceHistoryAppend(view, sku.skuId, context)
    val existing = if (index == null) view.aggregateKeys(LocalConfigurationProfile.PUBLICATION, limit = 1) else null
    if (index?.second?.state == PriceHistoryIndexState.COMPLETE && previous != null &&
        view.aggregate(LocalPricingProfile.key(LocalPricingProfile.HISTORY_HEAD, sku.skuId)) == null) {
        error("Completed company-price index lost SKU history")
    }
    val rules = (sku.prices + previous?.prices.orEmpty()).distinctBy { it.ruleId }.map { rule ->
        CompanyPriceHistoryRuleSources(rule.ruleId, readPricePolicy(view, sku.skuId, rule.ruleId, signatures),
            readBranchPrice(view, sku.skuId, rule.ruleId))
    }
    val prices = sku.prices.map { price ->
        CompanyPriceHistoryPriceSources(price, preparePriceVersion(view, sku.skuId, sku.publicationId, price, signatures),
            readPriceHighWater(view, sku.skuId, price.ruleId, signatures))
    }
    return prepareCompanyPriceHistory(view.scope, signed, previous, CompanyPriceHistoryInputs(index, append,
        existing?.let { it.keys.isNotEmpty() || it.nextAfter != null } == true, rules, prices))
}

/** One complete decision for live preparation and the exact original BEFORE comparison. */
internal fun prepareCompanyPriceHistory(scope: BranchScope, signed: LocalSignedSkuV1,
    previous: LocalPublishedSkuV1?, inputs: CompanyPriceHistoryInputs): PreparedCompanyPriceHistory {
    val sku = signed.definition
    val index = inputs.index
    index?.let { requirePriceHistoryIndex(scope, it.second) }
    val append = inputs.append
    val changes = mutableListOf<AggregateChange>()
    val claims = mutableListOf<ClaimChange>()
    val resources = mutableSetOf(priceResource(scope.branchId), priceResource(sku.publicationId), priceResource(signed.signingKeyId))
    resources += append.resources
    var rejection: String? = null
    if (index == null) {
        if (previous != null || inputs.hasRetainedPublications) rejection = "catalog.price_history_index_required"
        else changes += AggregateChange(LocalPricingProfile.key(LocalPricingProfile.HISTORY_INDEX, scope.branchId), null,
            LocalPricingProfile.schema(LocalPricingProfile.HISTORY_INDEX), encodeValue(
                PriceHistoryIndexV1(scope, PriceHistoryIndexState.COMPLETE, null, null, null, Revision(0)), PriceHistoryIndexV1.serializer()))
    } else if (index.second.state != PriceHistoryIndexState.COMPLETE) rejection = "catalog.price_history_index_in_progress"
    if (!append.hasCapacity) rejection = "catalog.price_history_capacity_required"
    if (index?.second?.state == PriceHistoryIndexState.COMPLETE && previous != null &&
        !append.hasHistory) {
        error("Completed company-price index lost SKU history")
    }
    val priorRules = previous?.prices.orEmpty().associateBy { it.ruleId }
    // A company change/removal can supersede an older still-current override even when the
    // SKU history tail belongs to another context. Capture those exact retained authorities.
    check(inputs.rules.map { it.ruleId } == (sku.prices + previous?.prices.orEmpty()).distinctBy { it.ruleId }.map { it.ruleId })
    for (rule in inputs.rules) {
        resources += rule.policy?.resources.orEmpty()
        resources += rule.branch?.resources.orEmpty()
    }
    check(inputs.prices.map { it.price } == sku.prices)
    for (source in inputs.prices) {
        val price = source.price
        val version = source.version
        resources += version.resources
        version.acquisition?.let { claims += it }
        if (version.conflict) rejection = "catalog.price_history_conflict"
        val high = source.high
        val id = priceContextId(scope.company(), sku.skuId, price.ruleId)
        resources += priceResource(id)
        resources += priceResource(price.ruleId)
        resources += high?.resources.orEmpty()
        if (index?.second?.state == PriceHistoryIndexState.COMPLETE && priorRules[price.ruleId] != null && high == null)
            error("Completed company-price index lost active price")
        if (high != null) {
            val retained = high.value.price
            if (price.revision < retained.revision || price.revision == retained.revision && price != retained ||
                price.ruleId !in priorRules && price.revision <= retained.revision) rejection = "catalog.price_history_conflict"
        }
        if (high == null || price.revision > high.value.price.revision) {
            if (high?.row?.revision?.value == Long.MAX_VALUE) rejection = "catalog.price_history_capacity_required"
            changes += AggregateChange(LocalPricingProfile.key(LocalPricingProfile.COMPANY_HIGH_WATER, id), high?.row?.revision,
                LocalPricingProfile.schema(LocalPricingProfile.COMPANY_HIGH_WATER), encodeValue(
                    PriceHighWaterV1(scope, sku.skuId, price.ruleId, price, sku.publicationId), PriceHighWaterV1.serializer()))
        }
    }
    return PreparedCompanyPriceHistory(rejection, resources.toSet(), claims.toList(), changes.toList(), append, sku.publicationId)
}
