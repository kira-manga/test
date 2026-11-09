package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedStreamedCommand
import com.ukkera.pos.database.StockRelationalKindsV2
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.CollectionBinding
import com.ukkera.pos.inventory.InventoryAllocationRecord
import com.ukkera.pos.inventory.InventoryHoldRecord
import com.ukkera.pos.inventory.InventoryIssueSlice
import com.ukkera.pos.inventory.InventoryPoolProjection
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryReservationHeader
import com.ukkera.pos.inventory.KitchenDisposition
import com.ukkera.pos.inventory.RecipeIssueRequest
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Explicit new storage/producer version. Historical command/body identities are never rewritten. */
public object LocalStockRelationalProfileV2 {
    public val COMMAND: CommandKey = StockStreamingCommandsV2.STOCK
    public val FOOD_COMMAND: CommandKey = StockStreamingCommandsV2.FOOD
    public val COLLECTION_COMMAND: CommandKey = StockStreamingCommandsV2.COLLECTION
    public val RETURN_COMMAND: CommandKey = StockStreamingCommandsV2.RETURN
    public const val MAX_FOOD_PORTIONS: Int = 32
    public const val MAX_INGREDIENTS_PER_PORTION: Int = 16
    public const val MAX_RETAIL_LINES: Int = 256
    public const val MAX_RETAIL_RETURN_SELECTIONS: Int = 32
    public const val EFFECT_KIND: String = "local.stock.v2.effect"
    public fun positionKey(position: StockPositionKey): AggregateKey = AggregateKey(StockRelationalKindsV2.POSITION,
        LocalStockStoreProfile.positionKey(position).id)
    public fun poolKey(pool: StockPoolKey): AggregateKey = AggregateKey(StockRelationalKindsV2.POOL,
        LocalStockStoreProfile.poolKey(pool).id)
    public fun itemKey(skuId: DomainId): AggregateKey = AggregateKey(StockRelationalKindsV2.ITEM, skuId)
    public fun reservationKey(id: DomainId): AggregateKey = AggregateKey(StockRelationalKindsV2.RESERVATION, id)
    public fun allocationKey(id: DomainId): AggregateKey = AggregateKey(StockRelationalKindsV2.ALLOCATION, id)
    public fun issueKey(id: DomainId): AggregateKey = AggregateKey(StockRelationalKindsV2.ISSUE, id)
    public fun issueSliceKey(issueId: DomainId, allocationId: DomainId): AggregateKey = AggregateKey(StockRelationalKindsV2.ISSUE_SLICE,
        localStockId("v2.issue-slice", "${issueId.value}/${allocationId.value}"))
    public fun activationKey(branchId: DomainId): AggregateKey = AggregateKey(StockRelationalKindsV2.ACTIVATION, branchId)
    public fun allocationId(setId: DomainId, revision: Revision, lineId: DomainId, position: StockPositionKey): DomainId =
        localStockId("allocation", "${setId.value}/${revision.value}/${lineId.value}/${LocalStockStoreProfile.positionKey(position).id.value}")
}

/** One distinct cancellable owner. A line list is the existing quote/recipe domain bound, not lot capacity. */
public class LocalStockReservationDemand(
    public val reservationId: DomainId,
    public val binding: CollectionBinding,
    public val allocationSetId: DomainId,
    public val allocationSetRevision: Revision,
    lines: List<LocalStockAllocationRequest>,
    public val expiresAt: UtcInstant? = null,
) {
    init {
        localStockCheck(allocationSetRevision.value >= 1 && lines.size in 1..LocalStockRelationalProfileV2.MAX_RETAIL_LINES &&
            lines.map { it.lineId }.distinct().size == lines.size && lines.all { it.baseQuantity.scaled > 0 })
    }
    public val lines: List<LocalStockAllocationRequest> = localStockFrozen(lines)
}

public data class LocalStockReservedIssue(
    public val reservationId: DomainId,
    public val expectedRevision: Revision,
    public val issueId: DomainId,
    public val purpose: ValueIssuePurpose,
    public val recipe: RecipeIssueRequest? = null,
)

/** Scalar current header; original allocation/valuation slices are separate per-ID records. */
public class LocalStockIssueHeader(
    public val id: DomainId,
    public val reservationId: DomainId,
    public val binding: CollectionBinding,
    public val purpose: ValueIssuePurpose,
    public val revision: Revision,
    public val allocationCount: Long,
    public val originalValue: Money,
    public val recipeSnapshotId: DomainId?,
    requiredStationIds: List<DomainId>,
    startedStationIds: List<DomainId>,
    public val disposition: KitchenDisposition?,
) {
    public val requiredStationIds: List<DomainId> = localStockFrozen(requiredStationIds)
    public val startedStationIds: List<DomainId> = localStockFrozen(startedStationIds)
    init {
        localStockCheck(revision.value >= 1 && allocationCount > 0 && originalValue.minor >= 0 &&
            requiredStationIds.size <= 32 && requiredStationIds.distinct().size == requiredStationIds.size &&
            startedStationIds.distinct().size == startedStationIds.size && startedStationIds.all { it in requiredStationIds })
        localStockCheck(when (purpose) {
            ValueIssuePurpose.RETAIL -> recipeSnapshotId == null && disposition == null && requiredStationIds.isEmpty() && startedStationIds.isEmpty()
            ValueIssuePurpose.DIRECT_RECIPE -> recipeSnapshotId != null && disposition != null && requiredStationIds.isNotEmpty()
            else -> false
        }, LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    }
}

/** No cap on allocation/holder count. These are copied summary scalars, never stock authority. */
public data class LocalStockStreamResult(
    public val reservationCount: Long,
    public val allocationCount: Long,
    public val mustCommitBefore: UtcInstant?,
)

/** Installed complete commerce verifier. It must account for ALL nonstock effects and originals. */
public interface LocalStockRelationalCompositeVerifier {
    public val commands: Set<CommandKey>
    public suspend fun requireComplete(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection)
    /**
     * Exact nonstock direct producer, after the repository proves the actual literal write and
     * prior same-lineage sequence. For Streamed inputs, full stock+installed commerce verification
     * has ALSO run. For Legacy, validate the ENTIRE matching original commerce producer here.
     * Do not recurse through unbounded ancestors or accept a current-row/hash-only certificate.
     */
    public suspend fun requireOriginalSource(original: com.ukkera.pos.database.RetainedOriginalV2,
        observation: com.ukkera.pos.database.StreamedSourceObservationV2.Aggregate)
    /** Actual same-loan owner for financial predecessors; old food profiles keep their exact validator. */
    public suspend fun requireOriginalSource(view: com.ukkera.pos.database.CommandReadView,
        original: com.ukkera.pos.database.RetainedOriginalV2,
        observation: com.ukkera.pos.database.StreamedSourceObservationV2.Aggregate): Unit = requireOriginalSource(original, observation)
}

/** Source-linked frontier input, not a whole pool history or a digest-only correction authority. */
internal enum class StockValueEventKind(val tag: Int) {
    RECEIVE(1), ISSUE(2), RESTORE(3), TRANSFER_OUT(4), TRANSFER_IN(5), CORRECTION(6), OPENING(7), COUNT_SHORTAGE(8), COUNT_SURPLUS(9);
    companion object { fun fromTag(tag: Int): StockValueEventKind = entries.singleOrNull { it.tag == tag }
        ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT) }
}

internal sealed interface StockRelationalBody {
    data class Item(val skuId: DomainId, val unitId: DomainId, val scale: Int, val serialized: Boolean) : StockRelationalBody
    data class Position(val value: StockPosition) : StockRelationalBody
    data class Pool(val value: InventoryPoolProjection) : StockRelationalBody
    data class Reservation(val value: InventoryReservationHeader, val allocationSetId: DomainId, val allocationSetRevision: Revision) : StockRelationalBody
    data class Allocation(val value: InventoryAllocationRecord) : StockRelationalBody
    data class Hold(val value: InventoryHoldRecord) : StockRelationalBody
    data class Issue(val value: LocalStockIssueHeader) : StockRelationalBody
    data class IssueSlice(val value: InventoryIssueSlice) : StockRelationalBody
    data class ValueEvent(val pool: StockPoolKey, val sourceId: DomainId, val ordinal: Long, val event: StockValueEventKind,
        val before: InventoryPoolProjection, val after: InventoryPoolProjection, val quantityDelta: Quantity,
        val originalValueDelta: Money, val carryingValueDelta: Money, val sliceCount: Long, val issueId: DomainId?) : StockRelationalBody
    data class ValueSlice(val pool: StockPoolKey, val sourceId: DomainId, val eventId: DomainId, val eventOrdinal: Long,
        val issueId: DomainId?, val allocationId: DomainId, val quantity: Quantity, val originalValue: Money) : StockRelationalBody
    data class ValueTarget(val pool: StockPoolKey, val sourceId: DomainId, val originalEventId: DomainId, val eventOrdinal: Long,
        val originalValue: Money, val targetValue: Money) : StockRelationalBody
    data class TransferEdge(val pool: StockPoolKey, val sourceId: DomainId, val eventId: DomainId, val eventOrdinal: Long,
        val linkedPool: StockPoolKey, val linkedEventId: DomainId, val quantity: Quantity, val originalValue: Money) : StockRelationalBody
    data class Posting(val sourceCount: Long, val sourceBytes: Long, val sourceCommitment: PayloadDigest,
        val effectCount: Long, val effectBytes: Long, val effectCommitment: PayloadDigest, val resultHash: PayloadDigest) : StockRelationalBody
    data class Activation(val migrationId: DomainId, val legacyAggregateCount: Long, val normalizedAggregateCount: Long,
        val legacyCommitment: PayloadDigest, val sourceProfile: String, val targetProfile: String) : StockRelationalBody
}

internal fun stockKey(body: StockRelationalBody): AggregateKey = when (body) {
    is StockRelationalBody.Item -> LocalStockRelationalProfileV2.itemKey(body.skuId)
    is StockRelationalBody.Position -> LocalStockRelationalProfileV2.positionKey(body.value.key)
    is StockRelationalBody.Pool -> LocalStockRelationalProfileV2.poolKey(body.value.key)
    is StockRelationalBody.Reservation -> LocalStockRelationalProfileV2.reservationKey(body.value.id)
    is StockRelationalBody.Allocation -> LocalStockRelationalProfileV2.allocationKey(body.value.allocation.allocationId)
    is StockRelationalBody.Hold -> AggregateKey(StockRelationalKindsV2.HOLD, localStockId("v2.hold",
        "${body.value.reservationId.value}/${stockV2HoldTag(body.value.hold.reason)}/${body.value.hold.position?.let { LocalStockRelationalProfileV2.positionKey(it).id.value }.orEmpty()}"))
    is StockRelationalBody.Issue -> LocalStockRelationalProfileV2.issueKey(body.value.id)
    is StockRelationalBody.IssueSlice -> LocalStockRelationalProfileV2.issueSliceKey(body.value.issueId, body.value.allocation.allocationId)
    is StockRelationalBody.ValueEvent -> AggregateKey(StockRelationalKindsV2.VALUE_EVENT, localStockId("v2.value-event", "${body.sourceId.value}/${LocalStockRelationalProfileV2.poolKey(body.pool).id.value}"))
    is StockRelationalBody.ValueSlice -> AggregateKey(StockRelationalKindsV2.VALUE_SLICE, localStockId("v2.value-slice", "${body.eventId.value}/${body.allocationId.value}"))
    is StockRelationalBody.ValueTarget -> AggregateKey(StockRelationalKindsV2.VALUE_TARGET, localStockId("v2.value-target", "${body.sourceId.value}/${LocalStockRelationalProfileV2.poolKey(body.pool).id.value}"))
    is StockRelationalBody.TransferEdge -> AggregateKey(StockRelationalKindsV2.TRANSFER_EDGE, localStockId("v2.transfer-edge", "${body.eventId.value}/${LocalStockRelationalProfileV2.poolKey(body.pool).id.value}"))
    is StockRelationalBody.Posting, is StockRelationalBody.Activation -> throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
}

internal fun stockCheckedAdd(left: Long, right: Long): Long {
    localStockCheck(left >= 0 && right >= 0 && right <= Long.MAX_VALUE - left, LocalStockFailure.BOUND_EXCEEDED)
    return left + right
}

/** Exact typed public observation for the separately installed whole-commerce producer. */
public data class LocalStockReservationIntent(
    public val reservationId: DomainId, public val binding: CollectionBinding,
    public val allocationSetId: DomainId, public val allocationSetRevision: Revision,
    public val expiresAt: UtcInstant?, public val lineCount: Int, public val ordinal: Int,
)
public data class LocalStockDemandLine(
    public val reservationId: DomainId, public val ordinal: Int, public val request: LocalStockAllocationRequest,
    public val catalogRevision: Revision, public val skuRevision: Revision,
)
public data class LocalStockRelease(
    public val reservationId: DomainId, public val expectedRevision: Revision,
    public val resolution: com.ukkera.pos.inventory.CollectionResolutionReference?,
)
public data class LocalStockIssueResult(public val issueId: DomainId, public val allocationCount: Long,
    public val originalValue: Money)

public data class LocalStockKitchenStart(public val issueId: DomainId, public val expectedRevision: Revision, public val stationId: DomainId)
public data class LocalStockKitchenWaste(public val issueId: DomainId, public val expectedRevision: Revision, public val reasonId: DomainId)

/** Literal station evidence, not a permission or a declaration that printing implies no start.
 * The installed complete food producer crosswalks every field to its actual retained task. */
public data class LocalStockKitchenConfirmation(
    public val stationId: DomainId,
    public val confirmationId: DomainId,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val requestedCustody: StockCustody,
) {
    init { localStockCheck(requestedCustody == StockCustody.RELEASED || requestedCustody == StockCustody.QUARANTINE) }
}

/** One whole independently cancellable original recipe issue. Physical lot slices are streamed;
 * only the already bounded required-station evidence is held here. No caller-selected quantity,
 * carrying value, replacement allocation, current recipe or invented original is accepted. */
public class LocalStockKitchenRestoration(
    public val restorationId: DomainId,
    public val issueId: DomainId,
    public val expectedRevision: Revision,
    confirmations: List<LocalStockKitchenConfirmation>,
) {
    public val confirmations: List<LocalStockKitchenConfirmation> = localStockFrozen(confirmations)
    init {
        localStockCheck(restorationId != issueId && expectedRevision.value >= 1 && confirmations.size in 1..32 &&
            confirmations.map { it.stationId }.distinct().size == confirmations.size &&
            confirmations.map { it.confirmationId }.distinct().size == confirmations.size)
    }
}

/** An explicitly inspected original allocation. No replacement lot or carrying cost is supplied. */
public data class LocalStockRetailReturnSelection(
    public val allocationId: DomainId,
    public val quantity: Quantity,
    public val requestedCustody: StockCustody,
) {
    init {
        localStockCheck(quantity.scaled > 0 &&
            requestedCustody in setOf(StockCustody.RELEASED, StockCustody.QUARANTINE))
    }
}

/** A positive subset of one accepted RETAIL portion. The installed sales verifier separately
 * proves the genuine acceptance, current inspection/authority and remaining accepted quantity.
 * The per-operation selection bound does not cap the issue or all postings for an acceptance. */
public class LocalStockRetailReturnRestoration(
    public val restorationId: DomainId,
    public val acceptedReturnId: DomainId,
    public val saleId: DomainId,
    public val portionId: DomainId,
    public val issueId: DomainId,
    public val expectedIssueRevision: Revision,
    public val quantityToRestore: Quantity,
    selections: List<LocalStockRetailReturnSelection>,
) {
    public val selections: List<LocalStockRetailReturnSelection> = localStockFrozen(selections)
    init {
        localStockCheck(restorationId != acceptedReturnId && restorationId != issueId &&
            expectedIssueRevision.value >= 1 && quantityToRestore.scaled > 0 &&
            selections.size in 1..LocalStockRelationalProfileV2.MAX_RETAIL_RETURN_SELECTIONS &&
            selections.map { it.allocationId }.distinct().size == selections.size && selections.all {
                it.quantity.unitId == quantityToRestore.unitId && it.quantity.scale == quantityToRestore.scale
            })
        localStockCheck(selections.fold(0L) { total, selection -> stockCheckedAdd(total, selection.quantity.scaled) } ==
            quantityToRestore.scaled)
    }
}

/** First-sent scalar cost/recipe identity, not a materialized list of all lot allocations. */
public class LocalStockIssueOriginal internal constructor(
    public val header: LocalStockIssueHeader,
    public val storageGeneration: Revision,
    public val payloadHash: PayloadDigest,
    public val original: RetainedStreamedCommand,
)

/** No new physical-financial command identity, pending-operation rewrite, or kernel cap bump. */
public sealed interface LocalStockRelationalCommand {
    public data class InitializeFresh(public val operationId: com.ukkera.pos.types.OperationId,
        public val initializationId: DomainId, public val reasonCode: String) : LocalStockRelationalCommand
    public data class Receive(public val operationId: com.ukkera.pos.types.OperationId,
        public val request: LocalGoodsReceiptRequest, public val opening: Boolean) : LocalStockRelationalCommand
    public data class ReceivePo(public val operationId: com.ukkera.pos.types.OperationId,
        public val request: LocalPoReceiptRequestV1) : LocalStockRelationalCommand
    public data class Inspect(public val operationId: com.ukkera.pos.types.OperationId,
        public val request: LocalInspectionReleaseRequest) : LocalStockRelationalCommand
    public data class CaptureCount(public val operationId: com.ukkera.pos.types.OperationId,
        public val request: LocalCountCaptureRequest) : LocalStockRelationalCommand
    public data class PostCount(public val operationId: com.ukkera.pos.types.OperationId,
        public val request: LocalCountPostRequest) : LocalStockRelationalCommand
    public data class Transfer(public val operationId: com.ukkera.pos.types.OperationId,
        public val request: LocalWarehouseTransferRequest) : LocalStockRelationalCommand
    public data class Recall(public val skuId: DomainId, public val lotId: DomainId,
        public val expectedItemRevision: Revision, public val reasonCode: String) : LocalStockRelationalCommand
    public data class Expire(public val sweepId: DomainId, public val reasonCode: String) : LocalStockRelationalCommand
}

/** Explicit fresh/physical receiving/safety profile; authority is still current kernel authorization. */
public object LocalStockRelationalCommandsV2 {
    /** Explicit fresh-intent routing only. Never call on a persisted pending command/retry;
     * historical command names/bytes remain the original identity for recovery. */
    public fun routeFreshIntent(request: com.ukkera.pos.database.MutationRequest): com.ukkera.pos.database.MutationRequest {
        localStockCheck(request.actionApprovalId == null)
        val id = request.operationId
        return when (request.command) {
            LocalStockStoreProfile.OPENING, LocalStockStoreProfile.OPENING_SERIAL -> opening(id, LocalStockCommandCodec.receipt(request.payload))
            LocalStockStoreProfile.RECEIVE, LocalStockStoreProfile.RECEIVE_SERIAL -> receive(id, LocalStockCommandCodec.receipt(request.payload))
            LocalStockStoreProfile.RECEIVE_PO -> receivePo(id, LocalStockCommandCodec.poReceipt(request.payload))
            LocalStockStoreProfile.INSPECT, LocalStockStoreProfile.INSPECT_SERIAL -> inspect(id, LocalStockCommandCodec.inspection(request.payload))
            LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_CAPTURE_SERIAL -> captureCount(id, LocalStockCommandCodec.countCapture(request.payload))
            LocalStockStoreProfile.COUNT_POST, LocalStockStoreProfile.COUNT_POST_SERIAL -> postCount(id, LocalStockCommandCodec.countPost(request.payload))
            LocalStockStoreProfile.RECALL, LocalStockStoreProfile.RECALL_SERIAL -> recall(id, LocalStockCommandCodec.recall(request.payload))
            LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.TRANSFER_SERIAL, LocalStockStoreProfile.TRANSFER_OWNED ->
                transfer(id, LocalStockCommandCodec.transfer(request.payload))
            else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
        }
    }

    public fun decodeResult(request: StoredBytes, result: StoredBytes): LocalStockResult = when (val input = decode(request)) {
        is LocalStockRelationalCommand.Recall -> {
            val value = stockDecode(result, StockAdminResultV2Wire.serializer())
            localStockCheck(value.format == 2 && value.action == 1 && value.id == input.lotId)
            LocalStockResult(LocalStockResultKind.RECALLED, value.id, emptyList())
        }
        is LocalStockRelationalCommand.InitializeFresh, is LocalStockRelationalCommand.Expire ->
            throw LocalStockRejected(LocalStockFailure.MALFORMED)
        else -> LocalStockCommandCodec.decodeResult(result)
    }

    /** Decode only the exact existing action3/result pair; profile reads cannot supply this. */
    public fun decodeInitializationResult(request: StoredBytes, result: StoredBytes): LocalStockInitializationResult {
        val input = decode(request) as? LocalStockRelationalCommand.InitializeFresh ?: throw LocalStockRejected(LocalStockFailure.MALFORMED)
        val value = stockDecode(result, StockFreshResultV2Wire.serializer())
        localStockCheck(value.format == 2 && value.initializationId == input.initializationId &&
            value.mode in setOf("FRESH_EMPTY", "MIGRATION_REQUIRED"))
        return LocalStockInitializationResult(value.initializationId, value.mode == "FRESH_EMPTY")
    }

    public fun initializeFresh(operationId: com.ukkera.pos.types.OperationId, initializationId: DomainId,
        reasonCode: String): com.ukkera.pos.database.MutationRequest = com.ukkera.pos.database.MutationRequest(operationId,
        LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.InitializeFresh(operationId, initializationId, reasonCode)))
    public fun opening(operationId: com.ukkera.pos.types.OperationId, request: LocalGoodsReceiptRequest): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.Receive(operationId, request, true)))
    public fun receive(operationId: com.ukkera.pos.types.OperationId, request: LocalGoodsReceiptRequest): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.Receive(operationId, request, false)))
    public fun receivePo(operationId: com.ukkera.pos.types.OperationId, request: LocalPoReceiptRequestV1): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.ReceivePo(operationId, request)))
    public fun inspect(operationId: com.ukkera.pos.types.OperationId, request: LocalInspectionReleaseRequest): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.Inspect(operationId, request)))
    public fun recall(operationId: com.ukkera.pos.types.OperationId, request: LocalLotRecallRequest): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND,
            encode(LocalStockRelationalCommand.Recall(request.skuId, request.lotId, request.expectedItemRevision, request.reasonCode)))
    public fun captureCount(operationId: com.ukkera.pos.types.OperationId, request: LocalCountCaptureRequest): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.CaptureCount(operationId, request)))
    public fun postCount(operationId: com.ukkera.pos.types.OperationId, request: LocalCountPostRequest): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.PostCount(operationId, request)))
    public fun transfer(operationId: com.ukkera.pos.types.OperationId, request: LocalWarehouseTransferRequest): com.ukkera.pos.database.MutationRequest =
        com.ukkera.pos.database.MutationRequest(operationId, LocalStockRelationalProfileV2.COMMAND, encode(LocalStockRelationalCommand.Transfer(operationId, request)))
    public fun encode(value: LocalStockRelationalCommand): StoredBytes = when (value) {
        is LocalStockRelationalCommand.Transfer -> stockEncode(StockTransferRequestV2Wire(10, value.operationId,
            StockTransferRequestWire.from(value.request)).also { it.restore() }, StockTransferRequestV2Wire.serializer())
        is LocalStockRelationalCommand.Inspect -> stockEncode(StockInspectionRequestV2Wire(9, value.operationId,
            StockInspectionRequestWire.from(value.request)).also { it.restore() }, StockInspectionRequestV2Wire.serializer())
        is LocalStockRelationalCommand.CaptureCount -> stockEncode(StockCountRequestV2Wire(7, value.operationId,
            StockCountCaptureWire.from(value.request), null).also { it.restore() }, StockCountRequestV2Wire.serializer())
        is LocalStockRelationalCommand.PostCount -> stockEncode(StockCountRequestV2Wire(8, value.operationId,
            null, StockCountPostWire.from(value.request)).also { it.restore() }, StockCountRequestV2Wire.serializer())
        is LocalStockRelationalCommand.Recall -> stockEncode(StockAdminV2Wire(1, value.skuId, value.lotId, value.expectedItemRevision, value.reasonCode).also { it.restore() }, StockAdminV2Wire.serializer())
        is LocalStockRelationalCommand.Expire -> stockEncode(StockAdminV2Wire(2, value.sweepId, null, Revision(0), value.reasonCode).also { it.restore() }, StockAdminV2Wire.serializer())
        is LocalStockRelationalCommand.InitializeFresh -> stockEncode(StockFreshRequestV2Wire(3, value.operationId, value.initializationId, value.reasonCode).also { it.restore() }, StockFreshRequestV2Wire.serializer())
        is LocalStockRelationalCommand.Receive -> stockEncode(StockReceivingRequestV2Wire(if (value.opening) 4 else 5, value.operationId,
            StockReceiptRequestWire.from(value.request), null).also { it.restore() }, StockReceivingRequestV2Wire.serializer())
        is LocalStockRelationalCommand.ReceivePo -> stockEncode(StockReceivingRequestV2Wire(6, value.operationId, null,
            StockPoReceiptRequestWire.from(value.request)).also { it.restore() }, StockReceivingRequestV2Wire.serializer())
    }
    public fun decode(bytes: StoredBytes): LocalStockRelationalCommand = localStockWire {
        localStockCheck(bytes.size <= 65_536, LocalStockFailure.BOUND_EXCEEDED)
        val text = bytes.copyBytes().decodeToString(throwOnInvalidSequence = true)
        // Canonical first scalar selects the bounded FULL decoder below. No permissive JSON
        // parse before its depth/size guard; old action1/2 literal encodings remain unchanged.
        localStockCheck(text.startsWith("{\"action\":") && text.length > 12)
        // Action10 is an additive wire case, not a reinterpretation of saved action1.
        // Only the two explicitly supported discriminator widths are admitted.
        val action = if (text.startsWith("{\"action\":10,")) 10 else {
            localStockCheck(text[11] == ','); text[10].digitToIntOrNull() ?: inconsistentStock()
        }
        when (action) {
            1, 2 -> stockDecode(bytes, StockAdminV2Wire.serializer()).restore()
            3 -> stockDecode(bytes, StockFreshRequestV2Wire.serializer()).restore()
            4, 5, 6 -> stockDecode(bytes, StockReceivingRequestV2Wire.serializer()).restore()
            7, 8 -> stockDecode(bytes, StockCountRequestV2Wire.serializer()).restore()
            9 -> stockDecode(bytes, StockInspectionRequestV2Wire.serializer()).restore()
            10 -> stockDecode(bytes, StockTransferRequestV2Wire.serializer()).restore()
            else -> inconsistentStock()
        }
    }
}

@kotlinx.serialization.Serializable internal data class StockAdminV2Wire(val action: Int, val id: DomainId,
    val lotId: DomainId?, val revision: Revision, val reason: String) {
    fun restore(): LocalStockRelationalCommand {
        localStockReason(reason)
        return when (action) {
            1 -> { localStockCheck(lotId != null && revision.value >= 1); LocalStockRelationalCommand.Recall(id, checkNotNull(lotId), revision, reason) }
            2 -> { localStockCheck(lotId == null && revision == Revision(0)); LocalStockRelationalCommand.Expire(id, reason) }
            else -> inconsistentStock()
        }
    }
}

@kotlinx.serialization.Serializable internal data class StockAllocationAuthorityV2Wire(
    val skuId: DomainId, val productId: DomainId, val catalogRevision: Revision, val skuRevision: Revision,
    val unitId: DomainId, val scale: Int, val tracking: Int, val requiresExpiry: Boolean, val warehouseId: DomainId,
    val itemSources: List<StockSourceStampWire>, val warehouseSources: List<StockSourceStampWire>,
)

@kotlinx.serialization.Serializable internal data class StockRowEffectV2Wire(val kind: String, val id: DomainId,
    val beforeGeneration: Revision?, val beforeDigest: String?, val afterDigest: String)

@kotlinx.serialization.Serializable internal sealed interface StockIntentV2Wire {
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("reserve") data class Reserve(
        val id: DomainId, val binding: BindingV2Wire, val setId: DomainId, val setRevision: Revision,
        val expiresAt: UtcInstant?, val lineCount: Int, val ordinal: Int) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("line") data class Line(
        val reservationId: DomainId, val ordinal: Int, val lineId: DomainId, val skuId: DomainId,
        val warehouseId: DomainId, val quantity: Quantity, val authority: StockAllocationAuthorityV2Wire) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("issue") data class Issue(
        val reservationId: DomainId, val revision: Revision, val issueId: DomainId, val purpose: Int,
        val recipeId: DomainId?, val stationIds: List<DomainId>) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("release") data class Release(
        val reservationId: DomainId, val revision: Revision, val resolutionId: DomainId?, val orderId: DomainId?,
        val collectionId: DomainId?, val obligationId: DomainId?) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("expire-sweep") data class ExpireSweep(val sweepId: DomainId) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("safety") data class Safety(
        val position: PositionKeyV2Wire, val reason: Int) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("movement") data class Movement(
        val sourceId: DomainId, val allocationId: DomainId?, val position: PositionKeyV2Wire,
        val quantity: Quantity, val reason: Int) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("kitchen-start") data class KitchenStart(
        val issueId: DomainId, val revision: Revision, val stationId: DomainId) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("waste") data class Waste(
        val issueId: DomainId, val revision: Revision, val reasonId: DomainId) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("restore-recipe") data class Restore(
        val restorationId: DomainId, val issueId: DomainId, val revision: Revision,
        val confirmations: List<StockKitchenConfirmationV2Wire>) : StockIntentV2Wire
    @kotlinx.serialization.Serializable @kotlinx.serialization.SerialName("restore-retail-return") data class RetailReturn(
        val restorationId: DomainId, val acceptedReturnId: DomainId, val saleId: DomainId,
        val portionId: DomainId, val issueId: DomainId, val revision: Revision,
        val quantityToRestore: Quantity, val selections: List<StockRetailReturnSelectionV2Wire>) : StockIntentV2Wire
}

internal const val STOCK_ACTION_EFFECT_V2: String = "local.stock.v2.action"
internal fun stockIntentId(value: StockIntentV2Wire): DomainId = localStockId("v2.intent", when (value) {
    is StockIntentV2Wire.Reserve -> "reserve/${value.id.value}"
    is StockIntentV2Wire.Line -> "line/${value.reservationId.value}/${value.ordinal}"
    is StockIntentV2Wire.Issue -> "issue/${value.issueId.value}"
    is StockIntentV2Wire.Release -> "release/${value.reservationId.value}"
    is StockIntentV2Wire.ExpireSweep -> "expire-sweep/${value.sweepId.value}"
    is StockIntentV2Wire.Safety -> "safety/${stockBody(value.position, PositionKeyV2Wire.serializer())}/${value.reason}"
    is StockIntentV2Wire.Movement -> "movement/${value.sourceId.value}/${value.allocationId?.value.orEmpty()}/${stockBody(value.position, PositionKeyV2Wire.serializer())}/${value.reason}"
    is StockIntentV2Wire.KitchenStart -> "start/${value.issueId.value}/${value.stationId.value}"
    is StockIntentV2Wire.Waste -> "waste/${value.issueId.value}"
    is StockIntentV2Wire.Restore -> "restore-recipe/${value.issueId.value}"
    is StockIntentV2Wire.RetailReturn -> "restore-retail-return/${value.restorationId.value}"
})
