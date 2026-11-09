package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** New, future-only linkage. Existing entitlement snapshots and CASE witnesses keep their formats. */
internal object LocalReturnEntitlementProvenanceProfile {
    const val LOCATOR = "sales.return.entitlement-producer"
    const val CONTRIBUTION = "sales.return.entitlement-contribution"
    const val NAMESPACE = "sales.return.entitlement-source"
    const val MAX_BYTES = 16_384
    const val MAX_ADVANCES = 256
    const val MAX_PROOF_BYTES = 16_777_216L
    const val MAX_PRODUCERS = 4_096
    const val MAX_RESOURCES = 512
    fun locator(saleId: DomainId): AggregateKey = AggregateKey(LOCATOR, saleId)
    fun contribution(operationId: OperationId): AggregateKey = AggregateKey(CONTRIBUTION, DomainId(operationId.value))
    fun locatorClaim(saleId: DomainId): ClaimKey = ClaimKey(NAMESPACE, "locator/${saleId.value}")
    fun contributionClaim(operationId: OperationId): ClaimKey = ClaimKey(NAMESPACE, "contribution/${operationId.value}")
}

/** Literal producer coordinates; actual retained originals supply journal/actor/effect provenance. */
@Serializable
internal data class LocalReturnEntitlementProducer(
    val operationId: OperationId, val actorId: DomainId, val command: String, val version: Int,
    val semanticDigest: PayloadDigest, val caseId: DomainId? = null,
) {
    fun identity(): StreamedProducerIdentityV2 = validateLocalSalesSource {
        StreamedProducerIdentityV2(operationId, CommandKey(command, version), actorId, semanticDigest)
    }
}

/** Only the missing linkage is appended. BEFORE remains the actual immutable CASE witness. */
@Serializable
internal data class LocalReturnEntitlementContribution(
    val scope: LocalSalesScope, val saleId: DomainId,
    val producer: LocalReturnEntitlementProducer, val previousProducer: LocalReturnEntitlementProducer,
    val finalizeProducer: LocalReturnEntitlementProducer,
    val beforeGeneration: Revision, val beforeDigest: PayloadDigest,
    val afterGeneration: Revision, val afterDigest: PayloadDigest,
    val previousContributionDigest: PayloadDigest? = null,
)

/** Mutable locator only. Matching this row does not establish remaining rights. */
@Serializable
internal data class LocalReturnEntitlementLocator(
    val scope: LocalSalesScope, val saleId: DomainId, val generation: Revision, val entitlementDigest: PayloadDigest,
    val producer: LocalReturnEntitlementProducer, val finalizeProducer: LocalReturnEntitlementProducer,
    val contributionDigest: PayloadDigest,
)

internal fun returnEntitlementProducer(value: StreamedProducerIdentityV2, caseId: DomainId? = null): LocalReturnEntitlementProducer =
    LocalReturnEntitlementProducer(value.operationId, value.originalActorId, value.command.name, value.command.version, value.semanticDigest, caseId)

internal fun returnEntitlementProducer(value: RetainedCommandEvidence, caseId: DomainId): LocalReturnEntitlementProducer =
    returnEntitlementProducer(StreamedProducerIdentityV2(value.operationId, value.command, value.originalActorId, value.semanticDigest), caseId)

internal fun returnEntitlementLocator(value: LocalReturnEntitlementContribution, digest: PayloadDigest): LocalReturnEntitlementLocator =
    LocalReturnEntitlementLocator(value.scope, value.saleId, value.afterGeneration, value.afterDigest,
        value.producer, value.finalizeProducer, digest)

internal fun returnEntitlementLocatorPayload(value: LocalReturnEntitlementContribution, digest: PayloadDigest): StoredBytes =
    localSalesEncode(returnEntitlementLocator(value, digest), LocalReturnEntitlementLocator.serializer(), LocalReturnEntitlementProvenanceProfile.MAX_BYTES)

internal suspend fun findReturnEntitlementLocator(view: CommandReadView, saleId: DomainId): LocalSalesRow<LocalReturnEntitlementLocator>? {
    val key = LocalReturnEntitlementProvenanceProfile.locator(saleId)
    val row = view.aggregate(key)
    val owner = view.claim(LocalReturnEntitlementProvenanceProfile.locatorClaim(saleId))
    if (row == null) {
        requireLocalSalesSource(owner == null)
        return null
    }
    requireLocalSalesSource(owner == saleId && row.key == key && row.schema == LocalSalesStoreProfile.schema(key.kind) &&
        row.revision.value in 1L..LocalReturnEntitlementProvenanceProfile.MAX_ADVANCES.toLong() && sha256(row.payload.copyBytes()) == row.digest)
    val value = decodeLocalSalesSource(row.payload, LocalReturnEntitlementLocator.serializer(), LocalReturnEntitlementProvenanceProfile.MAX_BYTES)
    requireLocalSalesSource(value.scope.value() == view.scope && value.saleId == saleId && value.generation == row.revision.next())
    return LocalSalesRow(row, value)
}

internal suspend fun readReturnEntitlementContribution(view: CommandReadView, operationId: OperationId): LocalSalesRow<LocalReturnEntitlementContribution> {
    val key = LocalReturnEntitlementProvenanceProfile.contribution(operationId)
    val row = LocalSalesStorage.required(view, key)
    requireLocalSalesSource(row.revision == Revision(1) && sha256(row.payload.copyBytes()) == row.digest)
    val value = decodeLocalSalesSource(row.payload, LocalReturnEntitlementContribution.serializer(), LocalReturnEntitlementProvenanceProfile.MAX_BYTES)
    requireLocalSalesSource(value.scope.value() == view.scope && value.producer.operationId == operationId &&
        value.producer.identity().command == LocalReturnCaseProfileV1.COMMAND && value.producer.caseId != null &&
        value.finalizeProducer.identity().command == StockStreamingCommandsV2.COLLECTION && value.finalizeProducer.caseId == null &&
        value.beforeGeneration.value in 1L..LocalReturnEntitlementProvenanceProfile.MAX_ADVANCES.toLong() &&
        value.beforeGeneration.next() == value.afterGeneration &&
        (value.beforeGeneration == Revision(1)) == (value.previousContributionDigest == null) &&
        (if (value.beforeGeneration == Revision(1)) value.previousProducer == value.finalizeProducer
            else value.previousProducer.identity().command == LocalReturnCaseProfileV1.COMMAND && value.previousProducer.caseId != null) &&
        view.claim(LocalReturnEntitlementProvenanceProfile.contributionClaim(operationId)) == value.saleId)
    return LocalSalesRow(row, value)
}

internal fun returnEntitlementWriteMatches(original: RetainedCommandEvidence, key: AggregateKey, generation: Revision,
    schema: String, payload: StoredBytes, digest: PayloadDigest): Boolean =
    original.committed && original.nativeOriginalProvenance == null && sha256(payload.copyBytes()) == digest &&
        original.aggregateWrites.singleOrNull { it.key == key }?.let {
            it.storageGeneration == generation && it.schema == schema && it.payload == payload && it.payloadDigest == digest
        } == true

/** Immediate literal contribution shape only. The source reader closes all predecessors. */
internal suspend fun requireReturnCaseEntitlementContribution(view: CommandReadView, original: RetainedCommandEvidence,
    request: LocalReturnCaseRequestV1.Accept, saleId: DomainId, before: RetainedAggregateWrite, after: RetainedAggregateWrite,
    keys: MutableSet<AggregateKey>, claims: MutableList<ClaimChange>) {
    val profile = LocalReturnEntitlementProvenanceProfile
    val key = profile.contribution(original.operationId)
    val write = original.aggregateWrites.singleOrNull { it.key == key }
    if (write == null) {
        // Preserve the exact pre-linkage producer profile. Partial new contributions cannot
        // shed their required locator/linkage and fall back to the old selected-CASE path.
        requireLocalSalesSource(original.aggregateWrites.none { it.key.kind in setOf(profile.LOCATOR, profile.CONTRIBUTION) } &&
            original.claimChanges.none { it.key.namespace == profile.NAMESPACE } && view.aggregate(key) == null &&
            view.claim(profile.contributionClaim(original.operationId)) == null)
        return
    }
    val row = readReturnEntitlementContribution(view, original.operationId)
    val value = row.value
    val locator = original.aggregateWrites.singleOrNull { it.key == profile.locator(saleId) }
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(original.scope == view.scope && original.nativeOriginalProvenance == null &&
        value.saleId == saleId && value.producer == returnEntitlementProducer(original, request.caseId) &&
        value.beforeGeneration == request.expectedEntitlementRevision.next() && value.beforeDigest == before.payloadDigest &&
        value.afterGeneration == after.storageGeneration && value.afterDigest == after.payloadDigest &&
        before.storageGeneration == Revision(1) && before.key == LocalReturnCaseProfileV1.key(
            LocalReturnCaseProfileV1.ENTITLEMENT_BEFORE, DomainId(original.operationId.value)) &&
        after.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId) &&
        returnEntitlementWriteMatches(original, row.stored.key, row.stored.revision, row.stored.schema, row.stored.payload, row.stored.digest) &&
        locator.schema == LocalSalesStoreProfile.schema(profile.LOCATOR) &&
        locator.storageGeneration == SalesStorageRevision.toDomain(value.afterGeneration) &&
        locator.payload == returnEntitlementLocatorPayload(value, row.stored.digest) && sha256(locator.payload.copyBytes()) == locator.payloadDigest)
    keys += listOf(key, locator.key)
    claims += ClaimChange.Acquire(profile.contributionClaim(original.operationId), saleId)
    if (value.beforeGeneration == Revision(1)) claims += ClaimChange.Acquire(profile.locatorClaim(saleId), saleId)
}

/** An invocation-local complete source cut; no authority or producer DTO is persisted as a grant. */
internal class LocalReturnEntitlementSourceCut(
    val latestProducer: LocalReturnEntitlementProducer, val finalizeProducer: LocalReturnEntitlementProducer,
    val locator: LocalSalesRow<LocalReturnEntitlementLocator>?,
    val contribution: LocalSalesRow<LocalReturnEntitlementContribution>?,
    val advances: Int, private val proof: LocalReturnEntitlementProofBudget,
) {
    val recoveryResources: Set<RecoveryResourceKey> get() = proof.resources()
    internal fun appendProof(): LocalReturnEntitlementProofBudget = proof.copy()
}

internal fun entitlementSourceResources(value: LocalEntitlementCut?): Set<RecoveryResourceKey> =
    value?.normalizedSource?.recoveryResources.orEmpty()

/** New records are produced only after reading a genuine initial/fully linked current source. */
internal class LocalReturnEntitlementAppend(private val source: LocalReturnEntitlementSourceCut,
    private val original: LocalEntitlementCut, private val identity: LocalReturnCaseProductionIdentity,
    private val caseId: DomainId, private val normalized: StoredBytes, private val proof: LocalReturnEntitlementProofBudget) {
    val recoveryResources: Set<RecoveryResourceKey> get() = proof.resources()
    fun changes(after: AggregateChange): List<AggregateChange> {
        check(after.key == original.entitlement.stored.key && after.expectedRevision == original.entitlement.stored.revision)
        val before = original.entitlement.stored
        val contribution = LocalReturnEntitlementContribution(LocalSalesScope.from(identity.scope), original.sale.value.id,
            LocalReturnEntitlementProducer(identity.operationId, identity.actorId, LocalReturnCaseProfileV1.COMMAND.name,
                LocalReturnCaseProfileV1.COMMAND.version, identity.semanticDigest, caseId),
            source.latestProducer, source.finalizeProducer, before.revision, before.digest, before.revision.next(),
            sha256(after.payload.copyBytes()), source.contribution?.stored?.digest)
        val payload = localSalesEncode(contribution, LocalReturnEntitlementContribution.serializer(), LocalReturnEntitlementProvenanceProfile.MAX_BYTES)
        return listOf(AggregateChange(LocalReturnEntitlementProvenanceProfile.contribution(identity.operationId), null,
            LocalSalesStoreProfile.schema(LocalReturnEntitlementProvenanceProfile.CONTRIBUTION), payload),
            AggregateChange(LocalReturnEntitlementProvenanceProfile.locator(contribution.saleId), source.locator?.stored?.revision,
                LocalSalesStoreProfile.schema(LocalReturnEntitlementProvenanceProfile.LOCATOR),
                returnEntitlementLocatorPayload(contribution, sha256(payload.copyBytes()))))
    }
    fun claims(): List<ClaimChange> = listOf(ClaimChange.Acquire(
        LocalReturnEntitlementProvenanceProfile.contributionClaim(identity.operationId), original.sale.value.id)) +
        if (source.locator == null) listOf(ClaimChange.Acquire(LocalReturnEntitlementProvenanceProfile.locatorClaim(original.sale.value.id), original.sale.value.id))
        else emptyList()

    /** Refuse before committing a contribution that its subsequent complete reader cannot retain. */
    fun bounded(proposal: ProposedOutcome): ProposedOutcome {
        if (proposal !is ProposedOutcome.Apply) return proposal
        val added = normalized.size.toLong() + proposal.result.size +
            proposal.changes.sumOf { it.payload.size.toLong() + it.key.kind.length + it.key.id.value.length + it.schema.length + 80L } +
            proposal.claims.sumOf { it.key.namespace.length.toLong() + it.key.key.length + it.ownerId.value.length + 64L } +
            proposal.jobs.sumOf { it.body.size.toLong() + it.kind.length + 16L } + 2_048L
        return if (source.advances >= LocalReturnEntitlementProvenanceProfile.MAX_ADVANCES ||
            !proof.canAppendOriginal(added)) caseRefused("CAPACITY_REQUIRED") else proposal
    }
}

internal suspend fun prepareReturnEntitlementAppend(view: CommandReadView, original: LocalEntitlementCut,
    identity: LocalReturnCaseProductionIdentity, request: LocalReturnCaseRequestV1.Accept): LocalReturnEntitlementAppend? {
    if (original.priceSources?.value?.normalizedStock != true) return null // Explicit legacy producer stays unchanged.
    val source = original.normalizedSource ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val key = LocalReturnEntitlementProvenanceProfile.contribution(identity.operationId)
    requireLocalSalesSource(identity.scope == view.scope && view.aggregate(key) == null &&
        view.claim(LocalReturnEntitlementProvenanceProfile.contributionClaim(identity.operationId)) == null)
    val proof = source.appendProof()
    // Account for the selected new CASE's actual dependencies before its Accept is appended.
    val cut = readReturnCase(view, request.caseId, LocalReturnCaseDependencies(proof::original))
    proof.resource(returnCaseResources(cut) + entitlementResource(request.returnId) +
        entitlementResource(DomainId(identity.operationId.value)))
    return LocalReturnEntitlementAppend(source, original, identity, request.caseId, LocalReturnCaseCommandCodecV1.encode(request), proof)
}
