package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable
internal data class PricePolicyPublicationV1(
    val scope: BranchScope, val signed: LocalSignedPricePolicyEntryV1,
    val operationId: OperationId, val receivedBy: DomainId, val actorPolicyRevision: Revision, val receivedAt: UtcInstant,
)

@Serializable
internal data class PricePolicyActivationV1(
    val scope: BranchScope, val skuId: DomainId, val ruleId: DomainId,
    val publicationId: DomainId, val definitionDigest: PayloadDigest,
    val eventId: DomainId, val activatedBy: DomainId, val activatedAt: UtcInstant,
)

@Serializable
internal data class BranchPriceHeadV1(
    val scope: BranchScope, val skuId: DomainId, val ruleId: DomainId,
    val eventId: DomainId, val eventDigest: PayloadDigest, val revision: Revision,
)

internal class ReadPricePolicy(
    val row: StoredAggregate, val value: PricePolicyActivationV1,
    val publication: Pair<StoredAggregate, PricePolicyPublicationV1>,
) {
    val definition: LocalPricePolicyEntryV1 get() = publication.second.signed.definition
    val resources: Set<RecoveryResourceKey> get() = setOf(priceResource(row.key.id), priceResource(value.eventId),
        priceResource(value.activatedBy), priceResource(definition.entryId), priceResource(definition.publicationId),
        priceResource(publication.second.receivedBy), priceResource(publication.second.signed.signingKeyId))
}

internal class ReadBranchPrice(val row: StoredAggregate, val head: BranchPriceHeadV1, val event: PriceHistoryEventV1) {
    val change: PriceBranchChangeV1 get() = checkNotNull(event.branchChange)
    val resources: Set<RecoveryResourceKey> get() = setOf(priceResource(row.key.id), priceResource(head.eventId),
        priceResource(event.actorId), priceResource(change.companyPublicationId), priceResource(change.policyEntryId),
        priceResource(change.policyPublicationId)) + event.actionApprovalId?.let { setOf(priceResource(it)) }.orEmpty()
}

internal suspend fun readPricePolicyPublication(
    view: CommandReadView, id: DomainId, signatures: CompanyPublicationSignatureVerifier,
): Pair<StoredAggregate, PricePolicyPublicationV1>? =
    readPricingValue(view, LocalPricingProfile.POLICY_PUBLICATION, id, PricePolicyPublicationV1.serializer())?.also { publication ->
        requirePricePolicyPublication(view.scope, id, publication)
        val value = publication.second
        val signed = value.signed
        val bytes = LocalPricingCodec.canonicalPolicy(signed.definition)
        try { check(signatures.verify(signed.definition.company, signed.signingKeyId, bytes, signed.signatureHex)) {
            "Company pricing policy signature unavailable"
        } } finally { bytes.fill(0) }
    }

internal fun requirePricePolicyPublication(scope: BranchScope, id: DomainId,
    publication: Pair<StoredAggregate, PricePolicyPublicationV1>) {
    val (row, value) = publication
    check(row.key == LocalPricingProfile.key(LocalPricingProfile.POLICY_PUBLICATION, id) && row.revision == Revision(1) &&
        value.scope == scope && value.signed.definition.company == scope.company() && value.signed.definition.publicationId == id)
}

internal suspend fun readPricePolicy(
    view: CommandReadView, skuId: DomainId, ruleId: DomainId, signatures: CompanyPublicationSignatureVerifier,
): ReadPricePolicy? {
    val id = priceContextId(view.scope.company(), skuId, ruleId)
    val (row, value) = readPricingValue(view, LocalPricingProfile.POLICY_CURRENT, id, PricePolicyActivationV1.serializer()) ?: return null
    check(value.scope == view.scope && value.skuId == skuId && value.ruleId == ruleId)
    val publication = readPricePolicyPublication(view, value.publicationId, signatures) ?: error("Active price policy lost its publication")
    val event = readPriceHistoryEvent(view, skuId, value.eventId) ?: error("Active price policy lost its history")
    return resolvePricePolicy(view.scope, skuId, ruleId, row to value, publication, event)
}

internal fun resolvePricePolicy(scope: BranchScope, skuId: DomainId, ruleId: DomainId,
    current: Pair<StoredAggregate, PricePolicyActivationV1>, publication: Pair<StoredAggregate, PricePolicyPublicationV1>,
    event: Pair<StoredAggregate, PriceHistoryEventV1>): ReadPricePolicy {
    val (row, value) = current
    check(row.key == LocalPricingProfile.key(LocalPricingProfile.POLICY_CURRENT, priceContextId(scope.company(), skuId, ruleId)) &&
        value.scope == scope && value.skuId == skuId && value.ruleId == ruleId)
    requirePricePolicyPublication(scope, value.publicationId, publication)
    val definition = publication.second.signed.definition
    check(definition.skuId == skuId && definition.ruleId == ruleId &&
        value.definitionDigest == sha256(LocalPricingCodec.canonicalPolicy(definition)))
    requirePriceHistoryEvent(scope, skuId, value.eventId, event)
    check(event.second.kind == PriceHistoryEventKind.POLICY_ACTIVATION && event.second.policyPublicationId == value.publicationId &&
        event.second.actorId == value.activatedBy && event.second.recordedAt == value.activatedAt)
    return ReadPricePolicy(row, value, publication)
}

internal suspend fun readBranchPrice(view: CommandReadView, skuId: DomainId, ruleId: DomainId): ReadBranchPrice? {
    val id = priceContextId(view.scope.company(), skuId, ruleId)
    val (row, value) = readPricingValue(view, LocalPricingProfile.OVERRIDE_CURRENT, id, BranchPriceHeadV1.serializer()) ?: return null
    val event = readPriceHistoryEvent(view, skuId, value.eventId) ?: error("Branch price head lost its history")
    return resolveBranchPrice(view.scope, skuId, ruleId, row to value, event)
}

internal fun resolveBranchPrice(scope: BranchScope, skuId: DomainId, ruleId: DomainId,
    current: Pair<StoredAggregate, BranchPriceHeadV1>, event: Pair<StoredAggregate, PriceHistoryEventV1>): ReadBranchPrice {
    val (row, value) = current
    check(row.key == LocalPricingProfile.key(LocalPricingProfile.OVERRIDE_CURRENT, priceContextId(scope.company(), skuId, ruleId)) &&
        value.scope == scope && value.skuId == skuId && value.ruleId == ruleId && value.revision == row.revision)
    requirePriceHistoryEvent(scope, skuId, value.eventId, event)
    check(event.first.digest == value.eventDigest && event.second.kind == PriceHistoryEventKind.BRANCH_CHANGE)
    val branch = checkNotNull(event.second.branchChange)
    check(branch.ruleId == ruleId && branch.overrideRevision == row.revision)
    check(branch.authorizationReferenceQuantity.scaled > 0 && branch.authorizationBase.currency == Currency.EGP &&
        branch.authorizationBase.minor > 0 && branch.authorizationDeviation.currency == Currency.EGP && branch.authorizationDeviation.minor >= 0)
    check(branch.unitPrice == null || branch.unitPrice.currency == Currency.EGP && branch.unitPrice.amount >= ExactDecimal.parse("0"))
    check(branch.effectiveUntil == null || branch.effectiveUntil > branch.effectiveFrom)
    // This first branch-authority producer has a direct-actor proof, not delegated approver metadata.
    check(event.second.actionApprovalId == null)
    return ReadBranchPrice(row, value, event.second)
}

/** Actual signed-policy staging/activation, branch change/reset and bounded retained-history work. */
public class LocalPricingHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterCommandHandler {
    override val key: CommandKey = LocalPricingProfile.COMMAND
    override val writableKinds: Set<String> = LocalPricingProfile.allKinds + LocalConfigurationProfile.CATALOG_CURSOR
    override val claimNamespaces: Set<String> = setOf(LocalPricingProfile.VERSION_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = LocalPricingCodec.encode(LocalPricingCodec.decode(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return prepareLocalPricing(LocalPricingCodec.decode(normalized), LocalPricingSourceReader.current(view, signatures),
            LocalPricingIdentity.current(context)).currentMutation()
    }
}

internal fun incrementPriceCursor(cursor: Pair<StoredAggregate, ConfigurationRecord>, actor: LocalPricingActorAudit, at: UtcInstant): AggregateChange =
    AggregateChange(cursor.first.key, cursor.first.revision, cursor.first.schema, encodeValue(cursor.second.copy(
        revision = cursor.second.revision.next(), authorizedBy = actor.actorId,
        policyRevision = actor.policyRevision, recordedAt = at), ConfigurationRecord.serializer()))

internal fun localPriceDigest(price: LocalPriceV1): PayloadDigest = sha256(encodeValue(price, LocalPriceV1.serializer()).copyBytes())
