package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Inert observations. Only the two fixed readers below select a current transaction or
 * an active, native-owned original cut; neither supplies an authorization source. */
internal sealed class LocalPricingSourceReader(val scope: BranchScope) {
    protected class Selected(val row: StoredAggregate, val original: RetainedOriginalV2?)

    protected abstract suspend fun select(key: AggregateKey): Selected?
    protected abstract fun requireSkuSource(selected: Selected, record: ConfigurationRecord)
    protected abstract fun requirePolicySource(selected: Selected, record: PricePolicyPublicationV1)
    abstract suspend fun claim(key: ClaimKey): DomainId?
    abstract suspend fun publicationPage(after: DomainId?): LocalPricingPublicationPage
    abstract suspend fun authenticateCandidate(signed: LocalSignedPricePolicyEntryV1): LocalPricingCandidateAuthentication

    suspend fun aggregate(key: AggregateKey): StoredAggregate? = select(key)?.row

    suspend fun <T> value(kind: String, id: DomainId, serializer: KSerializer<T>): Pair<StoredAggregate, T>? {
        val key = LocalPricingProfile.key(kind, id)
        val selected = select(key) ?: return null
        check(selected.row.key == key)
        return decodePricingValue(selected.row, serializer)
    }

    suspend fun sku(kind: String, id: DomainId): Pair<StoredAggregate, ConfigurationRecord>? {
        check(kind == LocalConfigurationProfile.SKU || kind == LocalConfigurationProfile.PUBLICATION)
        val key = LocalConfigurationProfile.key(kind, id)
        val selected = select(key) ?: return null
        check(selected.row.key == key)
        val record = decodeRecord(scope, selected.row)
        check(record.body is ConfigurationBody.Sku)
        requireSkuSource(selected, record)
        return selected.row to record
    }

    suspend fun policyPublication(id: DomainId): Pair<StoredAggregate, PricePolicyPublicationV1>? {
        val key = LocalPricingProfile.key(LocalPricingProfile.POLICY_PUBLICATION, id)
        val selected = select(key) ?: return null
        check(selected.row.key == key)
        val publication = decodePricingValue(selected.row, PricePolicyPublicationV1.serializer())
        requirePricePolicyPublication(scope, id, publication)
        requirePolicySource(selected, publication.second)
        return publication
    }

    suspend fun index(): Pair<StoredAggregate, PriceHistoryIndexV1>? =
        value(LocalPricingProfile.HISTORY_INDEX, scope.branchId, PriceHistoryIndexV1.serializer())?.also {
            requirePriceHistoryIndex(scope, it.second)
        }

    suspend fun cursor(): Pair<StoredAggregate, ConfigurationRecord>? {
        val key = LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, scope.branchId)
        val row = aggregate(key) ?: return null
        val record = decodeRecord(scope, row)
        check(record.body is ConfigurationBody.CatalogCursor)
        return row to record
    }

    suspend fun event(skuId: DomainId, id: DomainId): Pair<StoredAggregate, PriceHistoryEventV1>? =
        value(LocalPricingProfile.HISTORY_EVENT, id, PriceHistoryEventV1.serializer())?.also {
            requirePriceHistoryEvent(scope, skuId, id, it)
        }

    suspend fun head(skuId: DomainId): Pair<StoredAggregate, PriceHistoryHeadV1>? {
        val result = value(LocalPricingProfile.HISTORY_HEAD, skuId, PriceHistoryHeadV1.serializer()) ?: return null
        val (row, head) = result
        check(head.scope == scope && head.skuId == skuId && head.count == row.revision)
        val event = event(skuId, head.eventId) ?: error("Pricing history head lost its original event")
        check(event.first.digest == head.eventDigest && event.second.number == head.count)
        return result
    }

    suspend fun append(skuId: DomainId, identity: LocalPricingIdentity): PreparedPriceHistoryAppend {
        check(identity.scope == scope)
        val eventId = DomainId(identity.operationId.value)
        check(aggregate(LocalPricingProfile.key(LocalPricingProfile.HISTORY_EVENT, eventId)) == null) {
            "Unrecorded operation already owns pricing history"
        }
        return PreparedPriceHistoryAppend(scope, skuId, eventId, head(skuId))
    }

    suspend fun policy(skuId: DomainId, ruleId: DomainId): ReadPricePolicy? {
        val id = priceContextId(scope.company(), skuId, ruleId)
        val current = value(LocalPricingProfile.POLICY_CURRENT, id, PricePolicyActivationV1.serializer()) ?: return null
        val activation = current.second
        check(activation.scope == scope && activation.skuId == skuId && activation.ruleId == ruleId)
        val publication = policyPublication(activation.publicationId) ?: error("Active pricing policy lost its original publication")
        val event = event(skuId, activation.eventId) ?: error("Active pricing policy lost its original history")
        return resolvePricePolicy(scope, skuId, ruleId, current, publication, event)
    }

    suspend fun branch(skuId: DomainId, ruleId: DomainId): ReadBranchPrice? {
        val id = priceContextId(scope.company(), skuId, ruleId)
        val current = value(LocalPricingProfile.OVERRIDE_CURRENT, id, BranchPriceHeadV1.serializer()) ?: return null
        val event = event(skuId, current.second.eventId) ?: error("Branch pricing head lost its original history")
        return resolveBranchPrice(scope, skuId, ruleId, current, event)
    }

    suspend fun high(skuId: DomainId, ruleId: DomainId): ReadPriceHighWater? {
        val id = priceContextId(scope.company(), skuId, ruleId)
        val current = value(LocalPricingProfile.COMPANY_HIGH_WATER, id, PriceHighWaterV1.serializer()) ?: return null
        val value = current.second
        check(value.scope == scope && value.skuId == skuId && value.ruleId == ruleId && value.price.ruleId == ruleId)
        val publication = sku(LocalConfigurationProfile.PUBLICATION, value.publicationId)
            ?: error("Pricing high-water lost its original signed publication")
        return resolvePriceHighWater(scope, skuId, ruleId, current, publication.second)
    }

    suspend fun version(skuId: DomainId, publicationId: DomainId, price: LocalPriceV1): PreparedPriceVersion {
        val contextId = priceContextId(scope.company(), skuId, price.ruleId)
        val owner = claim(ClaimKey(LocalPricingProfile.VERSION_CLAIM, "${contextId.value}/${price.revision.value}"))
        val published = owner?.let { sku(LocalConfigurationProfile.PUBLICATION, it)
            ?: error("Pricing version claim lost its original publication") }
        return preparePriceVersion(scope, skuId, publicationId, price, owner, published?.second)
    }

    companion object {
        fun current(view: CommandReadView, signatures: CompanyPublicationSignatureVerifier): LocalPricingSourceReader =
            CurrentPricingSources(view, signatures)
        fun original(command: RuntimeHistoricalCommand): LocalPricingSourceReader = OriginalPricingSources(command)
    }
}

internal class LocalPricingPublicationPage(val key: AggregateKey?, val hasMore: Boolean)
internal class LocalPricingCandidateAuthentication(val authenticated: Boolean, val sourceUnavailable: Boolean)

private class CurrentPricingSources(
    private val view: CommandReadView,
    private val signatures: CompanyPublicationSignatureVerifier,
) : LocalPricingSourceReader(view.scope) {
    override suspend fun select(key: AggregateKey): Selected? = view.aggregate(key)?.let { Selected(it, null) }
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)

    override fun requireSkuSource(selected: Selected, record: ConfigurationRecord) {
        val signed = record.signedSku()
        val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
        try { check(signatures.verify(signed.definition.company, signed.signingKeyId, bytes, signed.signatureHex)) {
            "Company price source signature unavailable"
        } } finally { bytes.fill(0) }
    }

    override fun requirePolicySource(selected: Selected, record: PricePolicyPublicationV1) {
        val signed = record.signed
        val bytes = LocalPricingCodec.canonicalPolicy(signed.definition)
        try { check(signatures.verify(signed.definition.company, signed.signingKeyId, bytes, signed.signatureHex)) {
            "Company pricing policy signature unavailable"
        } } finally { bytes.fill(0) }
    }

    override suspend fun publicationPage(after: DomainId?): LocalPricingPublicationPage {
        val page = view.aggregateKeys(LocalConfigurationProfile.PUBLICATION, after, 1)
        check(page.keys.size <= 1 && page.keys.all { it.kind == LocalConfigurationProfile.PUBLICATION &&
            (after == null || it.id.value > after.value) })
        check(page.nextAfter == null || page.keys.singleOrNull()?.id == page.nextAfter)
        return LocalPricingPublicationPage(page.keys.singleOrNull(), page.nextAfter != null)
    }

    override suspend fun authenticateCandidate(signed: LocalSignedPricePolicyEntryV1): LocalPricingCandidateAuthentication {
        val snapshot = view.originalNativeBusinessPublications()
            ?: return LocalPricingCandidateAuthentication(false, sourceUnavailable = true)
        if (snapshot.company != scope.company()) return LocalPricingCandidateAuthentication(false, sourceUnavailable = true)
        return LocalPricingCandidateAuthentication(originalPricePolicyAuthenticated(scope, signed, snapshot.completedPublications), false)
    }
}

/** Each positive signed source is closed by its exact, already fully joined earlier
 * producer and literal AFTER row. Today's trust/ledger is never used as a substitute. */
private class OriginalPricingSources(private val command: RuntimeHistoricalCommand) : LocalPricingSourceReader(command.scope) {
    override suspend fun select(key: AggregateKey): Selected? = command.aggregateSource(key)?.let { Selected(it.row, it.original) }
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)

    private fun original(selected: Selected): RetainedCommandEvidence {
        val retained = (selected.original as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("Unsupported original pricing source")
        check(retained.committed && retained.nativeOriginalProvenance == null && retained.scope == scope &&
            retained.operationId != command.operationId)
        val write = retained.aggregateWrites.single { it.key == selected.row.key }
        check(write.storageGeneration == selected.row.revision && write.schema == selected.row.schema &&
            write.payload == selected.row.payload && write.payloadDigest == selected.row.digest)
        check(semanticDigest(scope, retained.originalActorId, retained.command, retained.normalized) == retained.semanticDigest)
        return retained
    }

    override fun requireSkuSource(selected: Selected, record: ConfigurationRecord) {
        val retained = original(selected)
        check(retained.command == LocalConfigurationProfile.COMMAND)
        val request = LocalConfigurationCodec.decode(retained.normalized)
        check(LocalConfigurationCodec.encode(request) == retained.normalized &&
            request is LocalConfigurationRequestV1.InstallCompanySku && request.publication == record.signedSku())
        check(record.authorizedBy == retained.originalActorId && record.policyRevision == retained.policyRevision &&
            record.recordedAt == retained.recordedAt && record.signedSku().definition.company == scope.company())
    }

    override fun requirePolicySource(selected: Selected, record: PricePolicyPublicationV1) {
        val retained = original(selected)
        check(retained.command == LocalPricingProfile.COMMAND)
        val request = LocalPricingCodec.decode(retained.normalized)
        check(LocalPricingCodec.encode(request) == retained.normalized &&
            request is LocalPricingRequestV1.ReceivePolicy && request.publication == record.signed)
        check(record.operationId == retained.operationId && record.receivedBy == retained.originalActorId &&
            record.actorPolicyRevision == retained.policyRevision && record.receivedAt == retained.recordedAt)
    }

    override suspend fun publicationPage(after: DomainId?): LocalPricingPublicationPage {
        var first: AggregateKey? = null
        var second: AggregateKey? = null
        // The complete native cut selects one latest row per canonical key. Its
        // traversal order need not be ID order; retain only the two smallest
        // eligible keys and consume the entire cut before certifying the page.
        command.forEachAggregate(LocalConfigurationProfile.PUBLICATION) { row ->
            val key = row.key
            check(key.kind == LocalConfigurationProfile.PUBLICATION)
            if (after != null && key.id.value <= after.value) return@forEachAggregate
            check(key != first && key != second)
            val lowest = first
            if (lowest == null || key.id.value < lowest.id.value) {
                second = lowest
                first = key
            } else {
                val next = second
                if (next == null || key.id.value < next.id.value) second = key
            }
        }
        return LocalPricingPublicationPage(first, second != null)
    }

    override suspend fun authenticateCandidate(signed: LocalSignedPricePolicyEntryV1): LocalPricingCandidateAuthentication =
        LocalPricingCandidateAuthentication(originalPricePolicyAuthenticated(scope, signed,
            command.completedNativeBusinessPublicationsBefore()), false)
}

/** Complete native membership proves only this original local signing fact, not an
 * employee grant or permission to activate the candidate. Known absence is distinct
 * from missing native evidence, which the readers never turn into an empty list. */
private fun originalPricePolicyAuthenticated(scope: BranchScope, signed: LocalSignedPricePolicyEntryV1,
    completed: List<CompanyBusinessPublicationOutput>): Boolean {
    check(completed.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS &&
        completed.all { it.proposal.company == scope.company() } &&
        completed.map { it.originalActionId }.distinct().size == completed.size &&
        completed.map { it.proposal.publicationId }.distinct().size == completed.size &&
        completed.map { it.signingKeyId }.distinct().size == completed.size)
    val definition = signed.definition
    if (definition.company != scope.company()) return false
    val output = completed.singleOrNull { it.signingKeyId == signed.signingKeyId } ?: return false
    if (output.proposal.kind != CompanyBusinessPublicationKind.PRICE_POLICY_V1 ||
        output.proposal.publicationId != definition.publicationId || output.proposal.entityId != definition.entryId ||
        output.signatureHex != signed.signatureHex) return false
    val bytes = LocalPricingCodec.canonicalPolicy(definition)
    val native = output.proposal.copyCanonicalContent()
    return try { bytes.contentEquals(native) } finally { bytes.fill(0); native.fill(0) }
}
