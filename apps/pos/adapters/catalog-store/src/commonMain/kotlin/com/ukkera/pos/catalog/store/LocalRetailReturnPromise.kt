package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Optional original commercial promise, signed INSIDE the existing SKU publication. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalRetailReturnPromiseV1(
    public val policyId: DomainId, public val policyRevision: Revision, public val categoryCode: String,
    public val englishTerms: String, public val arabicTerms: String,
    /** Explicit commercial terms only. Absence is unknown, not the statutory minimum. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val changeOfMindReceiptWindowDays: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val defectiveReceiptWindowDays: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) public val defectiveResolutionDays: Int? = null,
) {
    init {
        require(categoryCode.length in 1..128 && categoryCode.all { it.isLetterOrDigit() || it in "._-" })
        require(englishTerms.isNotBlank() || arabicTerms.isNotBlank())
        require(englishTerms.length <= 4_096 && arabicTerms.length <= 4_096)
        require((englishTerms + arabicTerms).none { (it.code < 0x20 || it.code in 0x7f..0x9f) && it != '\n' && it != '\t' })
        // Validate each literal independently; a surrogate pair cannot bridge languages.
        listOf(englishTerms, arabicTerms).forEach { it.encodeToByteArray(throwOnInvalidSequence = true).fill(0) }
        require(changeOfMindReceiptWindowDays == null || changeOfMindReceiptWindowDays >= 14)
        require((defectiveReceiptWindowDays == null) == (defectiveResolutionDays == null))
        require(defectiveReceiptWindowDays == null || defectiveReceiptWindowDays >= 30 && defectiveResolutionDays in 1..7)
    }
}

/** No period is parsed from literal text; optional structured terms are copied exactly. */
internal fun returnPromiseDescriptor(value: LocalRetailReturnPromiseV1?): LocalSalesReturnPromiseDescriptorV2? = value?.let {
    val bytes = ("ukkera.retail-return-promise/v1\n" +
        localConfigurationJson.encodeToString(LocalRetailReturnPromiseV1.serializer(), it)).encodeToByteArray(throwOnInvalidSequence = true)
    val digest = try { sha256(bytes) } finally { bytes.fill(0) }
    LocalSalesReturnPromiseDescriptorV2(it.policyId, it.policyRevision, it.categoryCode, digest,
        it.changeOfMindReceiptWindowDays, it.defectiveReceiptWindowDays, it.defectiveResolutionDays)
}

internal data class PreparedRetailReturnPublication(
    val publication: LocalSalesReturnPublicationV2, val resources: Set<RecoveryResourceKey>,
)

/** Genuine existing event -> install-operation join. ConfigurationRecord has NO operation ID. */
internal suspend fun prepareRetailReturnPublication(view: CommandReadView, current: ConfigurationRecord,
    signatures: CompanyPublicationSignatureVerifier): PreparedRetailReturnPublication {
    val signed = current.signedSku(); val sku = signed.definition
    val (row, published) = readSignedSku(view, LocalConfigurationProfile.PUBLICATION, sku.publicationId, signatures)
        ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.ORIGINAL_PUBLICATION_UNAVAILABLE)
    check(row.revision == Revision(1) && published.revision == Revision(0) && published.signedSku() == signed)
    val head = readPriceHistoryHead(view, sku.skuId)
        ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.ORIGINAL_PUBLICATION_UNAVAILABLE)
    val resources = mutableSetOf(priceResource(sku.skuId), priceResource(head.second.eventId))
    var id = head.second.eventId; var digest = head.second.eventDigest; var number = head.second.count
    var visited = 0
    while (true) {
        if (visited++ >= MAX_RETURN_PROMISE_HISTORY_STEPS) throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED)
        val event = readPriceHistoryEvent(view, sku.skuId, id) ?: error("Original company publication history missing")
        check(event.first.digest == digest && event.second.number == number)
        resources += priceResource(id)
        if (event.second.kind == PriceHistoryEventKind.COMPANY_PUBLICATION && event.second.companyPublicationId == sku.publicationId) {
            val producer = view.retainedCommand(event.second.operationId)
                ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.ORIGINAL_PUBLICATION_UNAVAILABLE)
            requireRetailPublicationProducer(producer, view.scope, row.key, row.revision, row.schema, row.payload, row.digest,
                event.first.payload, event.first.digest)
            val reference = LocalReturnCaseSourceReferenceV1(row.key.kind, row.key.id, row.schema, row.revision, row.digest,
                producer.operationId, producer.originalActorId, producer.command.name, producer.command.version)
            val descriptor = returnPromiseDescriptor(sku.returnPromise)
            val publication = LocalSalesReturnPublicationV2(sku.skuId, sku.skuRevision, signed.signingKeyId, reference,
                if (descriptor == null) LocalSalesReturnPromiseStateV2.SIGNED_ABSENT else LocalSalesReturnPromiseStateV2.SIGNED_PRESENT, descriptor)
            return PreparedRetailReturnPublication(publication, resources + companySourceResources(published, emptyList()) +
                returnPolicyPublicationResources(publication))
        }
        // INDEXED_COMPANY_PUBLICATION is not the original installer. Never promote it to one.
        id = event.second.priorEventId
            ?: throw LocalSalesQuoteUnavailable(LocalSalesQuoteUnavailableReason.ORIGINAL_PUBLICATION_UNAVAILABLE)
        digest = checkNotNull(event.second.priorEventDigest)
        number = Revision(number.value - 1)
    }
}

internal const val MAX_RETURN_PROMISE_HISTORY_STEPS: Int = 256

/**
 * Exact retained installer relation, not an issuer or a reconstructed authorization. The real
 * signature verifier has already checked the immutable definition. This joins the canonical
 * committed branch command, its PUBLICATION/SKU/history writes and original installer metadata.
 * It does not broaden LocalConfigurationHistoryProducer's separately held migration subset.
 */
internal fun requireRetailPublicationProducer(producer: RetainedCommandEvidence, scope: BranchScope,
    key: AggregateKey, generation: Revision, schema: String, payload: StoredBytes, digest: PayloadDigest,
    eventPayload: StoredBytes, eventDigest: PayloadDigest) {
    check(producer.committed && producer.scope == scope && producer.command == LocalConfigurationProfile.COMMAND && producer.jobs.isEmpty() &&
        producer.nativeOriginalProvenance == null && semanticDigest(scope, producer.originalActorId, producer.command, producer.normalized) == producer.semanticDigest &&
        LocalConfigurationCodec.encode(LocalConfigurationCodec.decode(producer.normalized)) == producer.normalized)
    val request = LocalConfigurationCodec.decode(producer.normalized) as? LocalConfigurationRequestV1.InstallCompanySku
        ?: error("Return promise publication producer is not an installation")
    val signed = request.publication; val sku = signed.definition
    check(sku.company == scope.company() && key == LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, sku.publicationId) &&
        generation == Revision(1) && schema == LocalConfigurationProfile.schema(key.kind) && sha256(payload.copyBytes()) == digest)
    val record = decodeValue(payload, ConfigurationRecord.serializer())
    check(record == ConfigurationRecord(scope, key.kind, key.id, Revision(0), ConfigurationBody.Sku(signed),
        producer.originalActorId, producer.policyRevision, producer.recordedAt) && encodeValue(record, ConfigurationRecord.serializer()) == payload)
    val event = decodeValue(eventPayload, PriceHistoryEventV1.serializer())
    val eventKey = LocalPricingProfile.key(LocalPricingProfile.HISTORY_EVENT, DomainId(producer.operationId.value))
    check(encodeValue(event, PriceHistoryEventV1.serializer()) == eventPayload && sha256(eventPayload.copyBytes()) == eventDigest &&
        event.scope == scope && event.skuId == sku.skuId && event.eventId == eventKey.id && event.operationId == producer.operationId &&
        event.number.value >= 1 && (event.priorEventId == null) == (event.priorEventDigest == null) &&
        (event.number == Revision(1)) == (event.priorEventId == null) && event.kind == PriceHistoryEventKind.COMPANY_PUBLICATION &&
        event.companyPublicationId == sku.publicationId && event.policyPublicationId == null && event.branchChange == null &&
        event.actorId == producer.originalActorId && event.actorPolicyRevision == producer.policyRevision && event.recordedAt == producer.recordedAt)
    fun write(wanted: AggregateKey): RetainedAggregateWrite = producer.aggregateWrites.single { it.key == wanted }
    val immutable = write(key)
    check(immutable.storageGeneration == generation && immutable.schema == schema && immutable.payload == payload && immutable.payloadDigest == digest)
    val eventWrite = write(eventKey)
    check(eventWrite.storageGeneration == Revision(1) && eventWrite.schema == LocalPricingProfile.schema(eventKey.kind) &&
        eventWrite.payload == eventPayload && eventWrite.payloadDigest == eventDigest)
    val headKey = LocalPricingProfile.key(LocalPricingProfile.HISTORY_HEAD, sku.skuId)
    val headWrite = write(headKey)
    check(headWrite.storageGeneration == event.number && headWrite.schema == LocalPricingProfile.schema(headKey.kind) &&
        headWrite.payload == encodeValue(PriceHistoryHeadV1(scope, sku.skuId, event.eventId, eventDigest, event.number), PriceHistoryHeadV1.serializer()))
    val skuKey = LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, sku.skuId)
    val skuWrite = write(skuKey)
    check(skuWrite.storageGeneration.value >= 1 && skuWrite.schema == LocalConfigurationProfile.schema(skuKey.kind) &&
        skuWrite.payload == encodeValue(record.copy(kind = skuKey.kind, id = skuKey.id,
            revision = Revision(skuWrite.storageGeneration.value - 1)), ConfigurationRecord.serializer()))
    check(producer.result == encodeValue(LocalConfigurationResultV1("configuration.applied", sku.skuId,
        Revision(skuWrite.storageGeneration.value - 1)), LocalConfigurationResultV1.serializer()) &&
        producer.resultHash == sha256(producer.result.copyBytes()))
}
