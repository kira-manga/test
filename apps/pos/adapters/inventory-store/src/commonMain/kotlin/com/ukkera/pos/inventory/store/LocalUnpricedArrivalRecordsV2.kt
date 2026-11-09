package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class ArrivalProducerV2(val operationId: OperationId, val actorId: DomainId,
    val command: String, val version: Int, val digest: String) {
    companion object {
        fun from(context: CommandContext): ArrivalProducerV2 = ArrivalProducerV2(context.operationId, context.originalActorId,
            context.key.name, context.key.version, context.semanticDigest.value)
        fun from(evidence: RetainedCommandEvidence): ArrivalProducerV2 = ArrivalProducerV2(evidence.operationId, evidence.originalActorId,
            evidence.command.name, evidence.command.version, evidence.semanticDigest.value)
    }
}

/** A precise immutable historical AFTER, not today's row or permission to replay its command. */
@Serializable internal data class ArrivalRowRefV2(val kind: String, val id: DomainId, val revision: Revision,
    val operationId: OperationId, val digest: String) {
    fun key(): AggregateKey = AggregateKey(kind, id)
}

@Serializable internal data class ArrivalRecordV2(
    val scope: BranchScope, val kind: String, val id: DomainId, val revision: Revision,
    val producer: ArrivalProducerV2, val recordedAt: UtcInstant, val policyRevision: Revision,
    val previous: ArrivalRowRefV2?, val body: ArrivalBodyV2,
)

@Serializable internal data class ArrivalItemAuthorityWireV2(val selection: LocalArrivalItemSelectionV2,
    val tracking: LocalArrivalTrackingV1, val requiresExpiry: Boolean, val active: Boolean, val canPurchase: Boolean,
    val source: StockSourceStampWire, val sources: List<StockSourceStampWire>)
@Serializable internal data class ArrivalWarehouseAuthorityWireV2(val selection: LocalArrivalWarehouseSelectionV2,
    val active: Boolean, val source: StockSourceStampWire, val sources: List<StockSourceStampWire>)
@Serializable internal data class ArrivalGuardObservationV2(val kind: String, val id: DomainId, val before: ArrivalRowRefV2?) {
    fun key(): AggregateKey = AggregateKey(kind, id)
}
@Serializable internal enum class ArrivalStockProfileV2 { LEGACY, RELATIONAL_V2 }

/** Cost-free original authority and physical stock observations; never exposed as a read proof. */
@Serializable internal data class ArrivalAuthorityWireV2(
    val supplier: LocalArrivalSupplierViewV2, val supplierSource: StockSourceStampWire, val supplierSources: List<StockSourceStampWire>,
    val items: List<ArrivalItemAuthorityWireV2>, val warehouses: List<ArrivalWarehouseAuthorityWireV2>,
    val serialRegistrations: List<StockSerialRegistrationWire>, val purchasing: LocalArrivalPurchasingEvidenceV2,
    val stockProfile: ArrivalStockProfileV2, val stockActivation: StockObservedWire?,
    val stock: List<StockObservedWire>, val absentStockItems: List<DomainId>,
    val guards: List<ArrivalGuardObservationV2>,
)

@Serializable internal sealed interface ArrivalBodyV2 {
    @Serializable @SerialName("ORIGINAL") data class Original(val request: LocalRecordUnpricedArrivalV2,
        val normalizedDeliveryReference: String?, val authority: ArrivalAuthorityWireV2) : ArrivalBodyV2
    @Serializable @SerialName("STATE") data class State(val arrivalId: DomainId, val original: ArrivalRowRefV2,
        val slices: List<ArrivalSliceV1>, val events: List<ArrivalRowRefV2>) : ArrivalBodyV2
    @Serializable @SerialName("EVENT") data class Event(val action: LocalArrivalActionV1, val request: String,
        val original: ArrivalRowRefV2, val stateBefore: ArrivalRowRefV2, val authority: ArrivalAuthorityWireV2,
        val changedSlice: ArrivalSliceV1) : ArrivalBodyV2
    @Serializable @SerialName("SOURCE") data class SourceIdentity(val arrivalId: DomainId, val lineId: DomainId,
        val rootSourceId: DomainId, val originalCustodyUnitId: DomainId, val sourceId: DomainId,
        val custodyUnitId: DomainId, val parentCustodyUnitId: DomainId?) : ArrivalBodyV2
    @Serializable @SerialName("SERIAL") data class SerialGuard(val alias: ArrivalSerialAliasV2,
        val arrivalId: DomainId, val lineId: DomainId, val sourceId: DomainId, val custodyUnitId: DomainId,
        val skuId: DomainId, val serialCode: String, val serialId: DomainId?) : ArrivalBodyV2
    /** Same qualified (SKU, lot) coordinate as normalized stock, NOT a global lot-ID registry. */
    @Serializable @SerialName("LOT") data class LotGuard(val skuId: DomainId, val lotId: DomainId,
        val productId: DomainId, val baseUnitId: DomainId, val quantityScale: Int, val expiresAt: UtcInstant?,
        val originArrivalId: DomainId, val originSourceId: DomainId) : ArrivalBodyV2
}

@Serializable internal enum class ArrivalSerialAliasV2 { CODE, ID }

internal class ArrivalRowV2(val row: StoredAggregate, val value: ArrivalRecordV2) {
    fun reference(): ArrivalRowRefV2 = ArrivalRowRefV2(row.key.kind, row.key.id, row.revision, value.producer.operationId, row.digest.value)
}
internal class ArrivalHistoricalV2(val row: RetainedAggregateWrite, val value: ArrivalRecordV2) {
    fun reference(): ArrivalRowRefV2 = ArrivalRowRefV2(row.key.kind, row.key.id, row.storageGeneration, value.producer.operationId, row.payloadDigest.value)
}
internal class ArrivalCutV2(val original: ArrivalRowV2, val state: ArrivalRowV2, val events: List<ArrivalRowV2>,
    val sources: List<ArrivalRowV2>, val guards: List<ArrivalRowV2>) {
    val request: LocalRecordUnpricedArrivalV2 get() = (original.value.body as ArrivalBodyV2.Original).request
    val current: ArrivalBodyV2.State get() = state.value.body as ArrivalBodyV2.State
}

internal val arrivalCommandsV2: Set<CommandKey> get() = setOf(LocalUnpricedArrivalProfileV2.RECORD, LocalUnpricedArrivalProfileV2.QUALIFY,
    LocalUnpricedArrivalProfileV2.REJECT, LocalUnpricedArrivalProfileV2.DISPATCH)
internal val arrivalKindsV2: Set<String> get() = setOf(LocalUnpricedArrivalProfileV2.ARRIVAL, LocalUnpricedArrivalProfileV2.STATE,
    LocalUnpricedArrivalProfileV2.EVENT, LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY,
    LocalUnpricedArrivalProfileV2.SERIAL_GUARD, LocalUnpricedArrivalProfileV2.LOT_GUARD)
internal val arrivalClaimNamespacesV2: Set<String> get() = arrivalClaimNamespaces

internal fun arrivalKeyV2(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun arrivalDocumentClaimV2(kind: String, id: DomainId): ClaimKey = arrivalDocumentClaim(kind, id)
internal fun arrivalSourceClaimV2(id: DomainId): ClaimKey = arrivalSourceClaim(id)
internal fun arrivalUnitClaimV2(id: DomainId): ClaimKey = arrivalUnitClaim(id)
internal fun arrivalLineClaimV2(id: DomainId): ClaimKey = arrivalLineClaim(id)
internal fun arrivalSerialCodeKeyV2(code: String): AggregateKey = arrivalKeyV2(LocalUnpricedArrivalProfileV2.SERIAL_GUARD, arrivalSerialCodeKey(code).id)
internal fun arrivalSerialIdKeyV2(id: DomainId): AggregateKey = arrivalKeyV2(LocalUnpricedArrivalProfileV2.SERIAL_GUARD, arrivalSerialIdKey(id).id)
internal fun arrivalLotKeyV2(skuId: DomainId, lotId: DomainId): AggregateKey = arrivalKeyV2(LocalUnpricedArrivalProfileV2.LOT_GUARD, arrivalLotKey(skuId, lotId).id)
internal fun arrivalSerialCodeClaimV2(code: String): ClaimKey = arrivalSerialCodeClaim(code)
internal fun arrivalSerialIdClaimV2(id: DomainId): ClaimKey = arrivalSerialIdClaim(id)
internal fun arrivalLotClaimV2(key: AggregateKey): ClaimKey = arrivalLotClaim(key)
internal fun arrivalDeliveryClaimV2(scope: BranchScope, request: LocalRecordUnpricedArrivalV2): ClaimKey =
    arrivalDeliveryClaimShared(scope, request.supplierId, request.supplierDeliveryReference, request.localIntakeTag)

internal fun arrivalSerialKeyV2(value: ArrivalBodyV2.SerialGuard): AggregateKey = when (value.alias) {
    ArrivalSerialAliasV2.CODE -> arrivalSerialCodeKeyV2(value.serialCode)
    ArrivalSerialAliasV2.ID -> arrivalSerialIdKeyV2(requireNotNull(value.serialId))
}
internal fun arrivalSerialClaimV2(value: ArrivalBodyV2.SerialGuard): ClaimKey = when (value.alias) {
    ArrivalSerialAliasV2.CODE -> arrivalSerialCodeClaimV2(value.serialCode)
    ArrivalSerialAliasV2.ID -> arrivalSerialIdClaimV2(requireNotNull(value.serialId))
}
internal fun arrivalBodyKeyV2(body: ArrivalBodyV2): AggregateKey = when (body) {
    is ArrivalBodyV2.Original -> arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, body.request.arrivalId)
    is ArrivalBodyV2.State -> arrivalKeyV2(LocalUnpricedArrivalProfileV2.STATE, body.arrivalId)
    is ArrivalBodyV2.Event -> arrivalKeyV2(LocalUnpricedArrivalProfileV2.EVENT,
        arrivalTransitionV2(body.action.commandV2(), StoredBytes(body.request.encodeToByteArray(throwOnInvalidSequence = true))).eventId)
    is ArrivalBodyV2.SourceIdentity -> arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, body.sourceId)
    is ArrivalBodyV2.SerialGuard -> arrivalSerialKeyV2(body)
    is ArrivalBodyV2.LotGuard -> arrivalLotKeyV2(body.skuId, body.lotId)
}

internal fun arrivalDecodeRecordV2(payload: StoredBytes): ArrivalRecordV2 {
    val value = arrivalDecode(payload, ArrivalRecordV2.serializer())
    check(arrivalEncode(value, ArrivalRecordV2.serializer()) == payload)
    check(value.kind in arrivalKindsV2 && arrivalBodyKeyV2(value.body) == arrivalKeyV2(value.kind, value.id) && value.revision.value >= 1)
    check(value.producer.version == 2 && CommandKey(value.producer.command, value.producer.version) in arrivalCommandsV2)
    PayloadDigest(value.producer.digest)
    value.previous?.let { previous ->
        check(previous.key() == arrivalKeyV2(value.kind, value.id) && previous.revision.next() == value.revision && previous.operationId != value.producer.operationId)
        PayloadDigest(previous.digest)
    } ?: check(value.revision == Revision(1))
    if (value.kind in setOf(LocalUnpricedArrivalProfileV2.ARRIVAL, LocalUnpricedArrivalProfileV2.EVENT, LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY))
        check(value.previous == null)
    return value
}

internal fun arrivalChangeV2(context: CommandContext, before: ArrivalRowRefV2?, body: ArrivalBodyV2,
    at: UtcInstant, policyRevision: Revision): AggregateChange =
    arrivalChangeV2(context.scope, ArrivalProducerV2.from(context), before, body, at, policyRevision)

/** Pure literal AFTER builder; historical callers supply inert original coordinates, not a proof. */
internal fun arrivalChangeV2(scope: BranchScope, producer: ArrivalProducerV2, before: ArrivalRowRefV2?, body: ArrivalBodyV2,
    at: UtcInstant, policyRevision: Revision): AggregateChange {
    val key = arrivalBodyKeyV2(body)
    check(before == null || before.key() == key)
    val record = ArrivalRecordV2(scope, key.kind, key.id, (before?.revision ?: Revision(0)).next(),
        producer, at, policyRevision, before, body)
    val bytes = arrivalJson.encodeToString(ArrivalRecordV2.serializer(), record).encodeToByteArray(throwOnInvalidSequence = true)
    arrivalRequireV2(bytes.size <= LocalUnpricedArrivalProfileV2.MAX_ROW_BYTES, LocalArrivalFailureV2.CAPACITY_EXCEEDED)
    return AggregateChange(key, before?.revision, LocalUnpricedArrivalProfileV2.schema(key.kind), StoredBytes(bytes))
}
internal fun arrivalReferenceV2(change: AggregateChange, operationId: OperationId): ArrivalRowRefV2 = ArrivalRowRefV2(change.key.kind,
    change.key.id, (change.expectedRevision ?: Revision(0)).next(), operationId, sha256(change.payload.copyBytes()).value)

internal fun LocalArrivalActionV1.commandV2(): CommandKey = when (this) {
    LocalArrivalActionV1.RECORD -> LocalUnpricedArrivalProfileV2.RECORD
    LocalArrivalActionV1.QUALIFY -> LocalUnpricedArrivalProfileV2.QUALIFY
    LocalArrivalActionV1.REJECT -> LocalUnpricedArrivalProfileV2.REJECT
    LocalArrivalActionV1.DISPATCH -> LocalUnpricedArrivalProfileV2.DISPATCH
}
internal fun arrivalActionV2(key: CommandKey): LocalArrivalActionV1 = LocalArrivalActionV1.entries.singleOrNull { it.commandV2() == key }
    ?: error("Unsupported unpriced arrival command")
internal fun arrivalHeldV2(disposition: LocalArrivalDispositionV1): Boolean = arrivalHeld(disposition)
internal class ArrivalRejectedV2(val failure: LocalArrivalFailureV2) : IllegalArgumentException(failure.name)
internal fun arrivalRequireV2(condition: Boolean, failure: LocalArrivalFailureV2) { if (!condition) throw ArrivalRejectedV2(failure) }

/** Only explicit loan-local admission throws this type. Malformed rows/producers never do. */
internal class ArrivalHydrationCapacityUnavailableV2 : RuntimeException()
