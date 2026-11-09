package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class ArrivalProducerV1(val operationId: OperationId, val actorId: DomainId,
    val command: String, val version: Int, val digest: String) {
    companion object {
        fun from(context: CommandContext): ArrivalProducerV1 = ArrivalProducerV1(context.operationId, context.originalActorId,
            context.key.name, context.key.version, context.semanticDigest.value)
        fun from(evidence: RetainedCommandEvidence): ArrivalProducerV1 = ArrivalProducerV1(evidence.operationId, evidence.originalActorId,
            evidence.command.name, evidence.command.version, evidence.semanticDigest.value)
    }
}

/** A precise immutable historical AFTER, not today's row or permission to replay its command. */
@Serializable internal data class ArrivalRowRefV1(val kind: String, val id: DomainId, val revision: Revision,
    val operationId: OperationId, val digest: String) {
    fun key(): AggregateKey = AggregateKey(kind, id)
}

@Serializable internal data class ArrivalRecordV1(
    val scope: BranchScope, val kind: String, val id: DomainId, val revision: Revision,
    val producer: ArrivalProducerV1, val recordedAt: UtcInstant, val policyRevision: Revision,
    val previous: ArrivalRowRefV1?, val body: ArrivalBodyV1,
)

@Serializable internal data class ArrivalItemAuthorityWireV1(val selection: LocalArrivalItemSelectionV1,
    val tracking: LocalArrivalTrackingV1, val requiresExpiry: Boolean, val active: Boolean, val canPurchase: Boolean,
    val source: StockSourceStampWire, val sources: List<StockSourceStampWire>)
@Serializable internal data class ArrivalWarehouseAuthorityWireV1(val selection: LocalArrivalWarehouseSelectionV1,
    val active: Boolean, val source: StockSourceStampWire, val sources: List<StockSourceStampWire>)
@Serializable internal data class ArrivalPolicyWireV1(val supplierId: DomainId, val value: LocalArrivalPolicyViewV1,
    val source: StockSourceStampWire, val sources: List<StockSourceStampWire>)
@Serializable internal data class ArrivalAttachmentWireV1(val supplierId: DomainId, val action: LocalArrivalActionV1,
    val attachmentId: DomainId, val source: StockSourceStampWire, val sources: List<StockSourceStampWire>)
@Serializable internal data class ArrivalGuardObservationV1(val kind: String, val id: DomainId, val before: ArrivalRowRefV1?) {
    fun key(): AggregateKey = AggregateKey(kind, id)
}

/** Cost-free original authority and physical stock observations; never exposed as a read proof. */
@Serializable internal data class ArrivalAuthorityWireV1(
    val supplier: LocalArrivalSupplierViewV1, val supplierSource: StockSourceStampWire, val supplierSources: List<StockSourceStampWire>,
    val items: List<ArrivalItemAuthorityWireV1>, val warehouses: List<ArrivalWarehouseAuthorityWireV1>,
    val serialRegistrations: List<StockSerialRegistrationWire>, val policy: ArrivalPolicyWireV1,
    val attachment: ArrivalAttachmentWireV1?, val stock: List<StockObservedWire>, val absentStockItems: List<DomainId>,
    val guards: List<ArrivalGuardObservationV1>,
)

@Serializable internal sealed interface ArrivalBodyV1 {
    @Serializable @SerialName("ORIGINAL") data class Original(val request: LocalRecordUnpricedArrivalV1,
        val normalizedDeliveryReference: String?, val authority: ArrivalAuthorityWireV1) : ArrivalBodyV1
    @Serializable @SerialName("STATE") data class State(val arrivalId: DomainId, val original: ArrivalRowRefV1,
        val slices: List<ArrivalSliceV1>, val events: List<ArrivalRowRefV1>) : ArrivalBodyV1
    @Serializable @SerialName("EVENT") data class Event(val action: LocalArrivalActionV1, val request: String,
        val original: ArrivalRowRefV1, val stateBefore: ArrivalRowRefV1, val authority: ArrivalAuthorityWireV1,
        val changedSlice: ArrivalSliceV1) : ArrivalBodyV1
    @Serializable @SerialName("SOURCE") data class SourceIdentity(val arrivalId: DomainId, val lineId: DomainId,
        val rootSourceId: DomainId, val originalCustodyUnitId: DomainId, val sourceId: DomainId,
        val custodyUnitId: DomainId, val parentCustodyUnitId: DomainId?) : ArrivalBodyV1
    @Serializable @SerialName("SERIAL") data class SerialGuard(val alias: ArrivalSerialAliasV1,
        val arrivalId: DomainId, val lineId: DomainId, val sourceId: DomainId, val custodyUnitId: DomainId,
        val skuId: DomainId, val serialCode: String, val serialId: DomainId?) : ArrivalBodyV1
    /** Same qualified (SKU, lot) coordinate as normalized stock, NOT a global lot-ID registry. */
    @Serializable @SerialName("LOT") data class LotGuard(val skuId: DomainId, val lotId: DomainId,
        val productId: DomainId, val baseUnitId: DomainId, val quantityScale: Int, val expiresAt: UtcInstant?,
        val originArrivalId: DomainId, val originSourceId: DomainId) : ArrivalBodyV1
}

@Serializable internal enum class ArrivalSerialAliasV1 { CODE, ID }
@Serializable internal data class ArrivalSliceV1(
    val lineId: DomainId, val rootSourceId: DomainId, val originalCustodyUnitId: DomainId,
    val sourceId: DomainId, val custodyUnitId: DomainId, val parentCustodyUnitId: DomainId?,
    val quantity: Quantity, val lotId: DomainId?, val expiresAt: UtcInstant?, val serialId: DomainId?,
    val serialCode: String?, val serialRegistrationRevision: Revision?, val disposition: LocalArrivalDispositionV1,
)

internal class ArrivalRow(val row: StoredAggregate, val value: ArrivalRecordV1) {
    fun reference(): ArrivalRowRefV1 = ArrivalRowRefV1(row.key.kind, row.key.id, row.revision, value.producer.operationId, row.digest.value)
}
internal class ArrivalHistorical(val row: RetainedAggregateWrite, val value: ArrivalRecordV1) {
    fun reference(): ArrivalRowRefV1 = ArrivalRowRefV1(row.key.kind, row.key.id, row.storageGeneration, value.producer.operationId, row.payloadDigest.value)
}
internal class ArrivalCut(val original: ArrivalRow, val state: ArrivalRow, val events: List<ArrivalRow>,
    val sources: List<ArrivalRow>, val guards: List<ArrivalRow>) {
    val request: LocalRecordUnpricedArrivalV1 get() = (original.value.body as ArrivalBodyV1.Original).request
    val current: ArrivalBodyV1.State get() = state.value.body as ArrivalBodyV1.State
}

internal val arrivalCommands: Set<CommandKey> get() = setOf(LocalUnpricedArrivalProfileV1.RECORD, LocalUnpricedArrivalProfileV1.QUALIFY,
    LocalUnpricedArrivalProfileV1.REJECT, LocalUnpricedArrivalProfileV1.DISPATCH)
internal val arrivalKinds: Set<String> get() = setOf(LocalUnpricedArrivalProfileV1.ARRIVAL, LocalUnpricedArrivalProfileV1.STATE,
    LocalUnpricedArrivalProfileV1.EVENT, LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY,
    LocalUnpricedArrivalProfileV1.SERIAL_GUARD, LocalUnpricedArrivalProfileV1.LOT_GUARD)
internal val arrivalClaimNamespaces: Set<String> get() = setOf(LocalUnpricedArrivalProfileV1.DOCUMENT_CLAIM,
    LocalUnpricedArrivalProfileV1.SOURCE_CLAIM, LocalUnpricedArrivalProfileV1.UNIT_CLAIM, LocalUnpricedArrivalProfileV1.LINE_CLAIM,
    LocalUnpricedArrivalProfileV1.DELIVERY_CLAIM, LocalUnpricedArrivalProfileV1.SERIAL_ID_CLAIM,
    LocalUnpricedArrivalProfileV1.SERIAL_CODE_CLAIM, LocalUnpricedArrivalProfileV1.LOT_CLAIM)
internal fun arrivalKey(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
internal fun arrivalDocumentClaim(kind: String, id: DomainId): ClaimKey = ClaimKey(LocalUnpricedArrivalProfileV1.DOCUMENT_CLAIM, "$kind/${id.value}")
internal fun arrivalSourceClaim(id: DomainId): ClaimKey = ClaimKey(LocalUnpricedArrivalProfileV1.SOURCE_CLAIM, id.value)
internal fun arrivalUnitClaim(id: DomainId): ClaimKey = ClaimKey(LocalUnpricedArrivalProfileV1.UNIT_CLAIM, id.value)
internal fun arrivalLineClaim(id: DomainId): ClaimKey = ClaimKey(LocalUnpricedArrivalProfileV1.LINE_CLAIM, id.value)
internal fun arrivalSerialCodeKey(code: String): AggregateKey = arrivalKey(LocalUnpricedArrivalProfileV1.SERIAL_GUARD, localStockId("arrival-code-guard", code))
internal fun arrivalSerialIdKey(id: DomainId): AggregateKey = arrivalKey(LocalUnpricedArrivalProfileV1.SERIAL_GUARD, localStockId("arrival-id-guard", id.value))
internal fun arrivalLotKey(skuId: DomainId, lotId: DomainId): AggregateKey = arrivalKey(LocalUnpricedArrivalProfileV1.LOT_GUARD, localStockId("arrival-lot-guard", "${skuId.value}/${lotId.value}"))
internal fun arrivalSerialCodeClaim(code: String): ClaimKey = ClaimKey(LocalUnpricedArrivalProfileV1.SERIAL_CODE_CLAIM, localStockId("serial-code", code).value)
internal fun arrivalSerialIdClaim(id: DomainId): ClaimKey = ClaimKey(LocalUnpricedArrivalProfileV1.SERIAL_ID_CLAIM, id.value)
internal fun arrivalLotClaim(key: AggregateKey): ClaimKey = ClaimKey(LocalUnpricedArrivalProfileV1.LOT_CLAIM, key.id.value)
internal fun arrivalDeliveryClaim(scope: BranchScope, request: LocalRecordUnpricedArrivalV1): ClaimKey {
    val identity = request.supplierDeliveryReference?.let { "supplier-reference/${normalizedPurchaseReference(it)}" }
        ?: "local-intake-tag/${requireNotNull(request.localIntakeTag).value}"
    val digest = sha256(("ukkera.arrival-delivery/v1/${scope.companyId.value}/${request.supplierId.value}/$identity").encodeToByteArray()).value
    return ClaimKey(LocalUnpricedArrivalProfileV1.DELIVERY_CLAIM, "${request.supplierId.value}/$digest")
}

internal fun arrivalSerialKey(value: ArrivalBodyV1.SerialGuard): AggregateKey = when (value.alias) {
    ArrivalSerialAliasV1.CODE -> arrivalSerialCodeKey(value.serialCode)
    ArrivalSerialAliasV1.ID -> arrivalSerialIdKey(requireNotNull(value.serialId))
}
internal fun arrivalSerialClaim(value: ArrivalBodyV1.SerialGuard): ClaimKey = when (value.alias) {
    ArrivalSerialAliasV1.CODE -> arrivalSerialCodeClaim(value.serialCode)
    ArrivalSerialAliasV1.ID -> arrivalSerialIdClaim(requireNotNull(value.serialId))
}
internal fun arrivalBodyKey(body: ArrivalBodyV1): AggregateKey = when (body) {
    is ArrivalBodyV1.Original -> arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, body.request.arrivalId)
    is ArrivalBodyV1.State -> arrivalKey(LocalUnpricedArrivalProfileV1.STATE, body.arrivalId)
    is ArrivalBodyV1.Event -> arrivalKey(LocalUnpricedArrivalProfileV1.EVENT,
        arrivalTransition(body.action.command(), StoredBytes(body.request.encodeToByteArray(throwOnInvalidSequence = true))).eventId)
    is ArrivalBodyV1.SourceIdentity -> arrivalKey(LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY, body.sourceId)
    is ArrivalBodyV1.SerialGuard -> arrivalSerialKey(body)
    is ArrivalBodyV1.LotGuard -> arrivalLotKey(body.skuId, body.lotId)
}

internal fun arrivalDecodeRecord(payload: StoredBytes): ArrivalRecordV1 {
    val value = arrivalDecode(payload, ArrivalRecordV1.serializer())
    check(arrivalEncode(value, ArrivalRecordV1.serializer()) == payload)
    check(value.kind in arrivalKinds && arrivalBodyKey(value.body) == arrivalKey(value.kind, value.id) && value.revision.value >= 1)
    check(value.producer.version == 1 && CommandKey(value.producer.command, value.producer.version) in arrivalCommands)
    PayloadDigest(value.producer.digest)
    value.previous?.let { previous ->
        check(previous.key() == arrivalKey(value.kind, value.id) && previous.revision.next() == value.revision && previous.operationId != value.producer.operationId)
        PayloadDigest(previous.digest)
    } ?: check(value.revision == Revision(1))
    if (value.kind in setOf(LocalUnpricedArrivalProfileV1.ARRIVAL, LocalUnpricedArrivalProfileV1.EVENT, LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY))
        check(value.previous == null)
    return value
}

internal fun arrivalChange(context: CommandContext, before: ArrivalRowRefV1?, body: ArrivalBodyV1,
    at: UtcInstant, policyRevision: Revision): AggregateChange {
    val key = arrivalBodyKey(body)
    check(before == null || before.key() == key)
    val record = ArrivalRecordV1(context.scope, key.kind, key.id, (before?.revision ?: Revision(0)).next(),
        ArrivalProducerV1.from(context), at, policyRevision, before, body)
    val bytes = arrivalJson.encodeToString(ArrivalRecordV1.serializer(), record).encodeToByteArray(throwOnInvalidSequence = true)
    arrivalRequire(bytes.size <= LocalUnpricedArrivalProfileV1.MAX_ROW_BYTES, LocalArrivalFailureV1.CAPACITY_EXCEEDED)
    return AggregateChange(key, before?.revision, LocalUnpricedArrivalProfileV1.schema(key.kind), StoredBytes(bytes))
}
internal fun arrivalReference(change: AggregateChange, operationId: OperationId): ArrivalRowRefV1 = ArrivalRowRefV1(change.key.kind,
    change.key.id, (change.expectedRevision ?: Revision(0)).next(), operationId, sha256(change.payload.copyBytes()).value)

internal fun LocalArrivalActionV1.command(): CommandKey = when (this) {
    LocalArrivalActionV1.RECORD -> LocalUnpricedArrivalProfileV1.RECORD
    LocalArrivalActionV1.QUALIFY -> LocalUnpricedArrivalProfileV1.QUALIFY
    LocalArrivalActionV1.REJECT -> LocalUnpricedArrivalProfileV1.REJECT
    LocalArrivalActionV1.DISPATCH -> LocalUnpricedArrivalProfileV1.DISPATCH
}
internal fun arrivalAction(key: CommandKey): LocalArrivalActionV1 = LocalArrivalActionV1.entries.singleOrNull { it.command() == key }
    ?: error("Unsupported unpriced arrival command")
internal fun arrivalHeld(disposition: LocalArrivalDispositionV1): Boolean = disposition in setOf(LocalArrivalDispositionV1.UNINSPECTED_HELD,
    LocalArrivalDispositionV1.PASSED_HELD, LocalArrivalDispositionV1.REJECTED_HELD)
internal class ArrivalRejected(val failure: LocalArrivalFailureV1) : IllegalArgumentException(failure.name)
internal fun arrivalRequire(condition: Boolean, failure: LocalArrivalFailureV1) { if (!condition) throw ArrivalRejected(failure) }

/** Only explicit loan-local admission throws this type. Malformed rows/producers never do. */
internal class ArrivalHydrationCapacityUnavailable : RuntimeException()
