package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Uncommitted draft records share the owner's TEMP connection; never a second stock store. */
internal class StockPostingBuilder(
    val read: LocalStockRelationalRead,
    val context: CommandContext,
    val sink: StreamedCommandSink,
) {
    private var finished = false
    private var deadline: UtcInstant? = null
    suspend fun requireOpen() { localStockCheck(!finished); sink.requireContext(read.access, context) }

    suspend fun body(key: AggregateKey): StockRelationalBody? {
        requireOpen()
        val scratch = sink.scratch.get(StockWorkingSpace.DRAFT_RECORD, group(key.kind), key.id)
        if (scratch != null) {
            val wire = stockDecode(scratch.payload, StockDraftV2Wire.serializer())
            localStockCheck(wire.kind == key.kind && wire.id == key.id)
            return LocalStockRelationalCodecV2.body(context.scope, wire.body)
        }
        return read.record(key)?.body
    }

    suspend fun put(body: StockRelationalBody) { put(stockKey(body), body) }
    private suspend fun put(key: AggregateKey, body: StockRelationalBody) {
        requireOpen()
        localStockCheck(key.kind != StockRelationalKindsV2.ACTIVATION && key.kind != StockRelationalKindsV2.POSTING)
        sink.scratch.put(StockWorkingSpace.DRAFT_RECORD, StockWorkingRecord(group(key.kind), key.id, 0,
            stockEncode(StockDraftV2Wire(key.kind, key.id, LocalStockRelationalCodecV2.body(body)), StockDraftV2Wire.serializer())))
        read.resources(key, body)
    }

    suspend fun insert(body: StockRelationalBody) {
        localStockCheck(body(stockKey(body)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
        put(body)
    }

    suspend fun acquire(key: ClaimKey, owner: DomainId) {
        requireOpen()
        localStockCheck(read.claim(key) == null, LocalStockFailure.SOURCE_ALREADY_USED)
        // A second acquire in the same operation is a duplicate spool key, not an idempotent prefix.
        sink.claim(ClaimChange.Acquire(key, owner))
    }

    suspend fun intent(value: StockIntentV2Wire) {
        requireOpen()
        sink.effect(StreamedEffectV2(STOCK_ACTION_EFFECT_V2, stockIntentId(value), stockEncode(value, StockIntentV2Wire.serializer())))
        if (value is StockIntentV2Wire.Restore) stockRestorationResources(value.request()) { sink.resource(it) }
        if (value is StockIntentV2Wire.RetailReturn) stockRetailReturnResources(value.request()) { sink.resource(it) }
    }

    fun before(instant: UtcInstant?) { if (instant != null) deadline = deadline?.let { minOf(it, instant) } ?: instant }
    fun beforeMillis(millis: Long?) { before(millis?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) }) }

    /** Advance one scalar SKU storage revision for every actual reservation/hold/physical/value
     * change. Membership is a same-transaction TEMP set, not an ever-growing serialized root.
     * These are REAL extra aggregate writes (therefore real B4/physical footprint entries). */
    private suspend fun prepareItemRevisions() {
        val selected = group("v2.touched-items")
        suspend fun touch(id: DomainId) {
            sink.scratch.put(StockWorkingSpace.MEMBERSHIP, StockWorkingRecord(selected, id, 0, StoredBytes(byteArrayOf())))
        }
        sink.scratch.forEach(StockWorkingSpace.DRAFT_RECORD) { working ->
            val draft = stockDecode(working.payload, StockDraftV2Wire.serializer())
            when (val value = LocalStockRelationalCodecV2.body(context.scope, draft.body)) {
                is StockRelationalBody.Position -> touch(value.value.key.pool.skuId)
                is StockRelationalBody.Pool -> touch(value.value.key.skuId)
                is StockRelationalBody.Allocation -> touch(value.value.allocation.position.pool.skuId)
                is StockRelationalBody.IssueSlice -> touch(value.value.allocation.position.pool.skuId)
                is StockRelationalBody.ValueEvent -> touch(value.pool.skuId)
                is StockRelationalBody.ValueSlice -> touch(value.pool.skuId)
                is StockRelationalBody.ValueTarget -> touch(value.pool.skuId)
                is StockRelationalBody.TransferEdge -> touch(value.pool.skuId)
                is StockRelationalBody.Reservation -> if (read.reservationRecord(value.value.id) != null)
                    read.allocations(value.value.id) { touch(it.allocation.position.pool.skuId) }
                is StockRelationalBody.Hold -> read.allocations(value.value.reservationId) { touch(it.allocation.position.pool.skuId) }
                is StockRelationalBody.Issue -> if (read.issue(value.value.id) != null)
                    read.issueAllocations(value.value.id) { touch(it.allocation.position.pool.skuId) }
                is StockRelationalBody.Item, is StockRelationalBody.Posting, is StockRelationalBody.Activation -> Unit
            }
        }
        sink.scratch.forEach(StockWorkingSpace.MEMBERSHIP, selected) { working ->
            localStockCheck(working.payload.size == 0 && working.sortValue == 0L)
            val key = LocalStockRelationalProfileV2.itemKey(working.recordId)
            val metadata = body(key) as? StockRelationalBody.Item ?: missingStock()
            val old = read.record(key)
            localStockCheck(old == null || old.body == metadata, LocalStockFailure.INVALID_UNIT)
            localStockCheck(read.claim(LocalStockStoreProfile.itemClaim(working.recordId)) == old?.key?.id)
            put(metadata)
        }
    }

    /** Must be the last stock call AFTER the complete caller producer supplied all companion effects. */
    suspend fun finish(result: StoredBytes, extraDeadline: UtcInstant?): StreamedMutationDecision.Apply {
        requireOpen(); before(extraDeadline)
        prepareItemRevisions()
        var count = 0L
        sink.scratch.forEach(StockWorkingSpace.DRAFT_RECORD) { working ->
            val draft = stockDecode(working.payload, StockDraftV2Wire.serializer())
            val key = AggregateKey(draft.kind, draft.id)
            localStockCheck(working.groupId == group(key.kind) && working.recordId == key.id)
            val old = read.record(key)
            val next = LocalStockRelationalCodecV2.body(context.scope, draft.body)
            val change = LocalStockRelationalCodecV2.change(context, key, old, next)
            sink.change(change)
            sink.effect(StreamedEffectV2(LocalStockRelationalProfileV2.EFFECT_KIND, rowEffectId(key),
                stockEncode(StockRowEffectV2Wire(key.kind, key.id, old?.generation, old?.digest?.value,
                    sha256(change.payload.copyBytes()).value), StockRowEffectV2Wire.serializer())))
            count = stockCheckedAdd(count, 1)
        }
        if (count == 0L) {
            // A commerce-only transition still needs its real companion effects and complete
            // installed producer; a finished expiry sweep needs both actual complete ranges.
            if (context.key == LocalStockRelationalProfileV2.FOOD_COMMAND || context.key == LocalStockRelationalProfileV2.COLLECTION_COMMAND) {
                var companion = false
                sink.frames(StreamedSectionV2.WRITES) { frame ->
                    val key = (frame as? RetainedCommandFrameV2.Write)?.value?.key ?: inconsistentStock()
                    localStockCheck(key.kind !in StockRelationalKindsV2.all); companion = true
                }
                sink.frames(StreamedSectionV2.CLAIMS) { frame ->
                    val key = (frame as? RetainedCommandFrameV2.Claim)?.value?.key ?: inconsistentStock()
                    localStockCheck(key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces); companion = true
                }
                localStockCheck(companion)
            } else {
                localStockCheck(context.key == LocalStockRelationalProfileV2.COMMAND)
                var countDocument = false
                sink.frames(StreamedSectionV2.EFFECTS) { frame ->
                    val effect = (frame as? RetainedCommandFrameV2.Effect)?.value ?: inconsistentStock()
                    if (effect.kind == STOCK_COUNT_EFFECT_V2) {
                        val request = LocalStockRelationalCommandsV2.decode(effect.payload)
                        localStockCheck(!countDocument && (request is LocalStockRelationalCommand.CaptureCount || request is LocalStockRelationalCommand.PostCount))
                        countDocument = true
                    }
                }
                val receiving = sinkReceivingCommand(context, sink)
                if (countDocument) {
                    var document = false
                    sink.frames(StreamedSectionV2.WRITES) { frame ->
                        localStockCheck(!document && (frame as? RetainedCommandFrameV2.Write)?.value?.key?.kind == LocalStockStoreProfile.COUNT_DOCUMENT)
                        document = true
                    }
                    localStockCheck(document)
                } else if (receiving) {
                    // Only the real receiving route emits this effect; complete retained validation
                    // compares its exact original receipt and ALL rows/claims/resources below.
                    var receipt = false
                    sink.frames(StreamedSectionV2.WRITES) { frame ->
                        val key = (frame as? RetainedCommandFrameV2.Write)?.value?.key ?: inconsistentStock()
                        localStockCheck(key.kind == LocalStockStoreProfile.RECEIPT_DOCUMENT && !receipt); receipt = true
                    }
                    localStockCheck(receipt)
                } else {
                var sweep = false
                sink.frames(StreamedSectionV2.EFFECTS) { frame ->
                    val effect = (frame as? RetainedCommandFrameV2.Effect)?.value ?: inconsistentStock()
                    localStockCheck(effect.kind == STOCK_ACTION_EFFECT_V2 && !sweep &&
                        stockDecode(effect.payload, StockIntentV2Wire.serializer()) is StockIntentV2Wire.ExpireSweep)
                    sweep = true
                }
                localStockCheck(sweep)
                }
            }
        }
        val sources = sink.sectionSummary(StreamedSectionV2.SOURCES)
        val effects = sink.sectionSummary(StreamedSectionV2.EFFECTS)
        val posting = StockRelationalBody.Posting(sources.count, sources.bytes, sources.commitment,
            effects.count, effects.bytes, effects.commitment, sha256(result.copyBytes()))
        val postingKey = AggregateKey(StockRelationalKindsV2.POSTING, DomainId(context.operationId.value))
        // Full current canonical/index census has already run. This CAS is not missing=>empty stock.
        localStockCheck(read.access.aggregate(postingKey) == null, LocalStockFailure.STORAGE_INCONSISTENT)
        sink.change(LocalStockRelationalCodecV2.change(context, postingKey, null, posting))
        read.resources(postingKey, posting)
        sink.finishInputs(result)
        finished = true
        return StreamedMutationDecision.Apply(result, deadline)
    }

    companion object { fun group(kind: String): DomainId = localStockId("v2.draft-kind", kind) }
}

@Serializable internal data class StockDraftV2Wire(val kind: String, val id: DomainId, val body: String)
internal fun rowEffectId(key: AggregateKey): DomainId = localStockId("v2.row-effect", "${key.kind}/${key.id.value}")

/**
 * Fully traversed literal stock projection, not an authority token or a proof that a sale paid.
 * Whole-commerce verifiers compare these exact actions to their OWN normalized request and
 * ALL of their financial/kitchen/custody effects. No 'verified=true' callback is supplied here.
 */
public class LocalStockRelationalInspection internal constructor(
    internal val original: RetainedStreamedCommand,
    private val repository: LocalStockRelationalRepository,
    private val traversal: StockOriginalTraversal,
) {
    internal var resourceClosureChecked: Boolean = false
        private set
    public val scope: com.ukkera.pos.security.BranchScope get() = original.identity.scope
    public val operationId: OperationId get() = original.identity.producer.operationId
    public val recordedAt: UtcInstant get() = original.identity.recordedAt

    /**
     * Require a genuine earlier normalized original using this repository's installed complete
     * stock/commerce producer. The original is reopened through THIS owner's active core loan,
     * matched exactly and queued in the SAME closed proof. The outer real requireComplete drains
     * every full typed producer before returning. No partially validated Inspection or proof is
     * returned, no caller verifier is accepted, and the caller still joins its exact source write.
     */
    public suspend fun requireCompleteOriginal(actual: RetainedStreamedCommand): Unit =
        repository.requireCompleteOriginal(original, actual, traversal)

    /** Actual mixed original lookup under this same proof's cumulative work envelope. It is a
     * structural literal, not typed producer approval, and is unavailable after proof closure. */
    public suspend fun original(operationId: OperationId): RetainedOriginalV2? = traversal.original(original, operationId)

    /**
     * ALL retained resources must equal stock's complete literal row/source/action alias union
     * plus the commerce verifier's OWN exactly derived finite resource set. No per-lot/holder
     * Set grows in memory; bidirectional streaming visits every alias and every retained member.
     * The repository requires this check before accepting any installed complete producer.
     */
    public suspend fun requireResources(companion: Set<com.ukkera.pos.recovery.RecoveryResourceKey>) {
        val supplied = companion.toSet()
        for (resource in supplied) requireResource(this, resource)
        requireStockResourcesPresent(this)
        original.frames { frame ->
            val resource = (frame as? RetainedCommandFrameV2.Resource)?.value ?: return@frames
            if (resource !in supplied) {
                var expected = false
                stockInspectionResourceAliases(this) { if (it == resource) expected = true }
                localStockCheck(expected, LocalStockFailure.STORAGE_INCONSISTENT)
            }
        }
        resourceClosureChecked = true
    }

    /**
     * Unique literal Current observations required by the validated Line item/warehouse stamps.
     * Every stamp is checked forward first; replay then emits each actual original key once.
     * No CurrentAbsent, loose namespace/prefix allowance, after-state substitution or authority
     * grant is produced. Commerce must account for its own complementary sources and reject extras.
     */
    public suspend fun stockAuthoritySources(consume: suspend (StreamedSourceObservationV2.Current) -> Unit) {
        stockRequiredAuthoritySources(this) { /* Complete required-stamp-to-literal crosswalk. */ }
        original.frames { frame ->
            val actual = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Current ?: return@frames
            var required = false
            intents { intent -> if (intent is StockIntentV2Wire.Line) {
                for (stamps in listOf(intent.authority.itemSources, intent.authority.warehouseSources)) for (stamp in stamps) {
                    if (AggregateKey(stamp.kind, stamp.id) == actual.key) {
                        localStockCheck(actual.generation == stamp.revision && actual.payloadHash == PayloadDigest(stamp.digest))
                        required = true
                    }
                }
            } }
            // The retained structural reader enforces strictly unique section/key order.
            if (required) consume(actual)
        }
    }

    /**
     * Unique ACTUAL before-state Claim observations required by typed stock transitions,
     * complete current reservation/issue memberships and Line/Issue serial source validation.
     * An acquire requires observed absence even though its after-state has an owner. Unknown
     * stock-owned claim observations fail; nonstock observations remain the consumer's obligation.
     */
    public suspend fun stockClaimSources(consume: suspend (StreamedSourceObservationV2.Claim) -> Unit) {
        stockRequiredClaimSources(this) { /* Every required before-state observation must exist. */ }
        original.frames { frame ->
            val actual = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Claim ?: return@frames
            val required = stockClaimSourceRequired(this, actual)
            localStockCheck(required || actual.key.namespace !in stockOwnedClaimNamespaces, LocalStockFailure.STORAGE_INCONSISTENT)
            if (required) consume(actual)
        }
    }

    /**
     * Literal BEFORE header, never an after-state reconstruction. Null means ONLY this original's
     * validated new target (gen1 write + actual absent permanent claim/acquire). An unobserved or
     * missing source otherwise fails, rather than turning historical stock into empty state.
     */
    public suspend fun beforeReservation(id: DomainId): InventoryReservationHeader? =
        reservationBefore(id, { }, { })

    public suspend fun beforeAllocations(reservationId: DomainId, consume: suspend (InventoryAllocationRecord) -> Unit) {
        reservationBefore(reservationId, consume, { }) ?: inconsistentStock()
    }

    public suspend fun beforeHolds(reservationId: DomainId, consume: suspend (InventoryHoldRecord) -> Unit) {
        reservationBefore(reservationId, { }, consume) ?: inconsistentStock()
    }

    public suspend fun beforeIssue(id: DomainId): LocalStockIssueHeader? = issueBefore(id, { })

    public suspend fun beforeIssueSlices(issueId: DomainId, consume: suspend (InventoryIssueSlice) -> Unit) {
        issueBefore(issueId, consume) ?: inconsistentStock()
    }

    private suspend fun requireNewTargetAbsence(key: AggregateKey, claimKey: ClaimKey) {
        val created = write(key) ?: inconsistentStock()
        val acquire = claim(claimKey) ?: inconsistentStock()
        localStockCheck(created.generation == Revision(1) && created.previous == null && acquire.key == claimKey &&
            acquire.action == RetainedClaimAction.ACQUIRE && acquire.ownerId == key.id && observedClaim(claimKey) == null)
    }

    private suspend fun reservationBefore(id: DomainId, allocation: suspend (InventoryAllocationRecord) -> Unit,
        hold: suspend (InventoryHoldRecord) -> Unit): InventoryReservationHeader? {
        val key = LocalStockRelationalProfileV2.reservationKey(id)
        val row = before(key)
        if (row == null) { requireNewTargetAbsence(key, LocalStockStoreProfile.reservationClaim(id)); return null }
        val body = row.body as? StockRelationalBody.Reservation ?: inconsistentStock()
        val header = body.value
        localStockCheck(header.id == id && observedClaim(LocalStockStoreProfile.reservationClaim(id)) == id)
        var allocations = 0L; var holds = 0L
        range(StockQueryV2.ReservationMembers(id)) { member -> when (val value = member.body) {
            is StockRelationalBody.Allocation -> {
                val item = value.value.allocation
                localStockCheck(value.value.reservationId == id && item.allocationId == LocalStockRelationalProfileV2.allocationId(
                    body.allocationSetId, body.allocationSetRevision, item.lineId, item.position) &&
                    observedClaim(LocalStockStoreProfile.allocationClaim(item.allocationId)) == id)
                allocations = stockCheckedAdd(allocations, 1); allocation(value.value)
            }
            is StockRelationalBody.Hold -> {
                localStockCheck(value.value.reservationId == id); holds = stockCheckedAdd(holds, 1); hold(value.value)
            }
            else -> inconsistentStock()
        } }
        localStockCheck(allocations == header.allocationCount && holds == header.holdCount)
        return header
    }

    private suspend fun issueBefore(id: DomainId, consume: suspend (InventoryIssueSlice) -> Unit): LocalStockIssueHeader? {
        val key = LocalStockRelationalProfileV2.issueKey(id)
        val row = before(key)
        if (row == null) { requireNewTargetAbsence(key, LocalStockStoreProfile.sourceClaim(id)); return null }
        val header = (row.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
        localStockCheck(header.id == id && observedClaim(LocalStockStoreProfile.sourceClaim(id)) == id)
        var count = 0L; var value = Money(header.originalValue.currency, 0)
        range(StockQueryV2.IssueValues(id)) { member -> when (val body = member.body) {
            is StockRelationalBody.IssueSlice -> {
                localStockCheck(body.value.issueId == id); count = stockCheckedAdd(count, 1); value += body.value.originalValue
                consume(body.value)
            }
            is StockRelationalBody.ValueEvent -> {
                localStockCheck(body.issueId == id)
                if (body.event == StockValueEventKind.RESTORE)
                    localStockCheck(observedClaim(LocalStockStoreProfile.sourceClaim(body.sourceId)) == body.sourceId)
            }
            is StockRelationalBody.ValueSlice -> localStockCheck(body.issueId == id)
            else -> inconsistentStock()
        } }
        localStockCheck(count == header.allocationCount && value == header.originalValue)
        return header
    }

    public suspend fun reservationIntents(consume: suspend (LocalStockReservationIntent) -> Unit) {
        intents { intent -> if (intent is StockIntentV2Wire.Reserve) consume(LocalStockReservationIntent(intent.id,
            intent.binding.restore(), intent.setId, intent.setRevision, intent.expiresAt, intent.lineCount, intent.ordinal)) }
    }
    public suspend fun demandLines(reservationId: DomainId, consume: suspend (LocalStockDemandLine) -> Unit) {
        intents { intent -> if (intent is StockIntentV2Wire.Line && intent.reservationId == reservationId)
            consume(LocalStockDemandLine(reservationId, intent.ordinal, LocalStockAllocationRequest(intent.lineId,
                intent.skuId, intent.warehouseId, intent.quantity), intent.authority.catalogRevision, intent.authority.skuRevision)) }
    }
    public suspend fun issueIntents(consume: suspend (LocalStockReservedIssue) -> Unit) {
        intents { intent -> if (intent is StockIntentV2Wire.Issue) consume(LocalStockReservedIssue(intent.reservationId,
            intent.revision, intent.issueId, stockV2Purpose(intent.purpose), intent.recipeId?.let { RecipeIssueRequest(it, intent.stationIds) })) }
    }
    public suspend fun releaseIntents(consume: suspend (LocalStockRelease) -> Unit) {
        intents { intent -> if (intent is StockIntentV2Wire.Release) consume(LocalStockRelease(intent.reservationId,
            intent.revision, releaseResolution(intent))) }
    }
    public suspend fun kitchenStartIntents(consume: suspend (LocalStockKitchenStart) -> Unit) {
        intents { if (it is StockIntentV2Wire.KitchenStart) consume(LocalStockKitchenStart(it.issueId, it.revision, it.stationId)) }
    }
    public suspend fun wasteIntents(consume: suspend (LocalStockKitchenWaste) -> Unit) {
        intents { if (it is StockIntentV2Wire.Waste) consume(LocalStockKitchenWaste(it.issueId, it.revision, it.reasonId)) }
    }
    public suspend fun restorationIntents(consume: suspend (LocalStockKitchenRestoration) -> Unit) {
        intents { if (it is StockIntentV2Wire.Restore) consume(it.request()) }
    }
    public suspend fun retailReturnRestorationIntents(consume: suspend (LocalStockRetailReturnRestoration) -> Unit) {
        intents { if (it is StockIntentV2Wire.RetailReturn) consume(it.request()) }
    }
    /**
     * Exact first-issued scalar/source. Inspection alone never approves the complete older sale:
     * the owning repository's genuine direct-source crosswalk performs that producer validation.
     */
    public suspend fun issueOriginal(id: DomainId): LocalStockIssueOriginal? {
        val current = issue(id) ?: (before(LocalStockRelationalProfileV2.issueKey(id))?.body as? StockRelationalBody.Issue)?.value ?: return null
        val created = write(LocalStockRelationalProfileV2.issueKey(id))
        if (created?.generation == Revision(1)) return stockIssueOriginal(original, id, current)
        var producer: StreamedProducerIdentityV2? = null
        range(StockQueryV2.IssueValues(id)) { row ->
            val event = row.body as? StockRelationalBody.ValueEvent
            if (event?.event == StockValueEventKind.ISSUE) {
                localStockCheck(event.issueId == id && event.sourceId == id && row.generation == Revision(1) && row.previous == null &&
                    (producer == null || producer == row.producer))
                producer = row.producer
            }
        }
        val source = producer ?: inconsistentStock()
        val first = (original(source.operationId) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
        localStockCheck(first.identity.producer == source)
        requireCompleteOriginal(first)
        return stockIssueOriginal(first, id, current)
    }
    public suspend fun reservation(id: DomainId): InventoryReservationHeader? =
        (write(LocalStockRelationalProfileV2.reservationKey(id))?.body as? StockRelationalBody.Reservation)?.value
    public suspend fun allocations(reservationId: DomainId, consume: suspend (InventoryAllocationRecord) -> Unit) {
        records { record -> (record.body as? StockRelationalBody.Allocation)?.value?.let { if (it.reservationId == reservationId) consume(it) } }
    }
    public suspend fun issue(id: DomainId): LocalStockIssueHeader? =
        (write(LocalStockRelationalProfileV2.issueKey(id))?.body as? StockRelationalBody.Issue)?.value
    public suspend fun issueSlices(issueId: DomainId, consume: suspend (InventoryIssueSlice) -> Unit) {
        records { record -> (record.body as? StockRelationalBody.IssueSlice)?.value?.let { if (it.issueId == issueId) consume(it) } }
    }

    internal suspend fun records(consume: suspend (StockRelationalRecordV2) -> Unit) {
        original.frames { frame ->
            val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
            if (write.key.kind in StockRelationalKindsV2.all) consume(LocalStockRelationalCodecV2.decode(scope, write.key,
                write.storageGeneration, write.schema, write.payload, write.payloadDigest))
        }
    }
    internal suspend fun write(key: AggregateKey): StockRelationalRecordV2? {
        var found: StockRelationalRecordV2? = null
        records { if (it.key == key) { localStockCheck(found == null); found = it } }
        return found
    }
    internal suspend fun before(key: AggregateKey): StockRelationalRecordV2? {
        val source = source(key) ?: return null
        return LocalStockRelationalCodecV2.decode(scope, source.key, source.generation, source.schema, source.payload, source.payloadHash)
    }
    internal suspend fun source(key: AggregateKey): StreamedSourceObservationV2.Aggregate? {
        var found: StreamedSourceObservationV2.Aggregate? = null
        original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate
            if (source?.key == key) { localStockCheck(found == null); found = source }
        }
        return found
    }
    internal suspend fun claim(key: ClaimKey): RetainedClaimChange? {
        var found: RetainedClaimChange? = null
        original.frames { frame ->
            val value = (frame as? RetainedCommandFrameV2.Claim)?.value
            if (value?.key == key) { localStockCheck(found == null); found = value }
        }
        return found
    }
    internal suspend fun observedClaim(key: ClaimKey): DomainId? {
        var found = false; var owner: DomainId? = null
        original.frames { frame ->
            val value = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Claim
            if (value?.key == key) { localStockCheck(!found); found = true; owner = value.ownerId }
        }
        localStockCheck(found, LocalStockFailure.STORAGE_INCONSISTENT)
        return owner
    }
    internal suspend fun intents(consume: suspend (StockIntentV2Wire) -> Unit) {
        original.frames { frame ->
            val value = (frame as? RetainedCommandFrameV2.Effect)?.value ?: return@frames
            if (value.kind == STOCK_ACTION_EFFECT_V2) {
                val intent = stockDecode(value.payload, StockIntentV2Wire.serializer())
                localStockCheck(value.id == stockIntentId(intent)); consume(intent)
            }
        }
    }
    internal suspend fun intent(id: DomainId): StockIntentV2Wire? {
        var found: StockIntentV2Wire? = null
        intents { if (stockIntentId(it) == id) { localStockCheck(found == null); found = it } }
        return found
    }
    internal suspend fun range(query: StockQueryV2, consume: suspend (StockRelationalRecordV2) -> Unit) {
        original.stockRange(query) { index ->
            val record = before(index.identity.key) ?: inconsistentStock()
            val projected = stockIndexOf(scope, record)
            localStockCheck(projected == index, LocalStockFailure.STORAGE_INCONSISTENT)
            consume(record)
        }
    }
}

internal fun stockIndexOf(scope: com.ukkera.pos.security.BranchScope, record: StockRelationalRecordV2): StockIndexRecordV2? =
    LocalStockRelationalCodecV2.projectRecord(scope, record)

internal suspend fun requireStockRelationalPosting(original: RetainedStreamedCommand,
    repository: LocalStockRelationalRepository, traversal: StockOriginalTraversal): LocalStockRelationalInspection {
    val inspection = LocalStockRelationalInspection(original, repository, traversal)
    val key = AggregateKey(StockRelationalKindsV2.POSTING, DomainId(original.identity.producer.operationId.value))
    val record = inspection.write(key) ?: inconsistentStock()
    val posting = record.body as? StockRelationalBody.Posting ?: inconsistentStock()
    localStockCheck(record.generation == Revision(1) && record.previous == null && record.producer == original.identity.producer)
    val sources = original.sectionSummary(StreamedSectionV2.SOURCES)
    val effects = original.sectionSummary(StreamedSectionV2.EFFECTS)
    localStockCheck(posting.sourceCount == sources.count && posting.sourceBytes == sources.bytes && posting.sourceCommitment == sources.commitment &&
        posting.effectCount == effects.count && posting.effectBytes == effects.bytes && posting.effectCommitment == effects.commitment &&
        posting.resultHash == original.identity.resultHash, LocalStockFailure.STORAGE_INCONSISTENT)
    // The descriptor hashes named sections, not the envelope/write which contains itself.
    original.frames { frame ->
        val range = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.StockRange
        if (range != null)
            inspection.range(range.query) { /* Complete literal crosswalk, including unused unfavorable candidates. */ }
    }
    return inspection
}

internal fun releaseResolution(value: StockIntentV2Wire.Release): CollectionResolutionReference? {
    if (value.resolutionId == null) {
        localStockCheck(value.orderId == null && value.collectionId == null && value.obligationId == null)
        return null
    }
    return CollectionResolutionReference(value.resolutionId, value.orderId ?: inconsistentStock(),
        value.collectionId ?: inconsistentStock(), value.obligationId ?: inconsistentStock())
}

/** Same immutable scalar identity across kitchen revisions, with the exact literal generation1 write. */
internal suspend fun stockIssueOriginal(first: RetainedStreamedCommand, id: DomainId,
    current: LocalStockIssueHeader): LocalStockIssueOriginal {
    var result: LocalStockIssueOriginal? = null
    first.frames { frame ->
        val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
        if (write.key == LocalStockRelationalProfileV2.issueKey(id)) {
            localStockCheck(result == null && write.storageGeneration == Revision(1))
            val record = LocalStockRelationalCodecV2.decode(first.identity.scope, write.key, write.storageGeneration,
                write.schema, write.payload, write.payloadDigest)
            val header = (record.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
            localStockCheck(record.producer == first.identity.producer && record.previous == null && header.revision == Revision(1) &&
                header.startedStationIds.isEmpty() && (header.purpose != ValueIssuePurpose.DIRECT_RECIPE || header.disposition == KitchenDisposition.ACTIVE) &&
                IssueV2Wire.from(copyIssueHeader(header, current.revision, current.startedStationIds, current.disposition)) == IssueV2Wire.from(current))
            result = LocalStockIssueOriginal(header, record.generation, record.digest, first)
        }
    }
    return result ?: inconsistentStock()
}

internal suspend fun requireResource(stock: LocalStockRelationalInspection,
    resource: com.ukkera.pos.recovery.RecoveryResourceKey) {
    var found = false
    stock.original.frames { frame -> if (frame is RetainedCommandFrameV2.Resource && frame.value == resource) {
        localStockCheck(!found); found = true
    } }
    localStockCheck(found, LocalStockFailure.STORAGE_INCONSISTENT)
}

internal suspend fun requireStockResourcesPresent(stock: LocalStockRelationalInspection) {
    stockInspectionResourceAliases(stock) { requireResource(stock, it) }
}

/** Shared literal alias projection used in both directions; no opaque summary supplies membership. */
internal suspend fun stockInspectionResourceAliases(stock: LocalStockRelationalInspection,
    consume: suspend (com.ukkera.pos.recovery.RecoveryResourceKey) -> Unit) {
    suspend fun authorization(id: DomainId) { consume(com.ukkera.pos.recovery.RecoveryResourceKey(
        com.ukkera.pos.recovery.RecoveryResourceKind.AUTHORIZATION, id)) }
    authorization(stock.original.identity.producer.originalActorId)
    stock.records { stockResourceAliases(it.key, it.body, consume) }
    stock.original.frames { frame ->
        val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate ?: return@frames
        if (source.key.kind in StockRelationalKindsV2.all) {
            val row = LocalStockRelationalCodecV2.decode(stock.scope, source.key, source.generation, source.schema, source.payload, source.payloadHash)
            stockResourceAliases(row.key, row.body, consume)
        } else if (source.key.kind == LocalStockStoreProfile.SERIAL_ORIGIN) {
            val envelope = stockDecode(source.payload, StockRecordWire.serializer())
            val serial = stockBody(envelope.body, StockSerialWire.serializer())
            serial.registration?.restore()?.let { for (resource in serialRegistrationResources(it)) consume(resource) }
        }
    }
    stock.intents { intent -> when (intent) {
        is StockIntentV2Wire.Line -> {
            for (stamp in intent.authority.itemSources) authorization(stamp.id)
            for (stamp in intent.authority.warehouseSources) authorization(stamp.id)
        }
        is StockIntentV2Wire.ExpireSweep -> consume(com.ukkera.pos.recovery.RecoveryResourceKey(
            com.ukkera.pos.recovery.RecoveryResourceKind.STOCK, intent.sweepId))
        is StockIntentV2Wire.Restore -> stockRestorationResources(intent.request(), consume)
        is StockIntentV2Wire.RetailReturn -> stockRetailReturnResources(intent.request(), consume)
        else -> Unit
    } }
}

/** Exact finite ownership set, NOT acceptance by prefix or by membership in this set. */
internal val stockOwnedClaimNamespaces: Set<String> get() = LocalStockStoreProfile.inventoryClaimNamespaces + setOf(
    LocalStockStoreProfile.DOCUMENT_CLAIM, LocalStockStoreProfile.ALLOWANCE_CLAIM,
    LocalStockStoreProfile.RECEIPT_LINE_CLAIM, LocalStockStoreProfile.COUNT_SURPLUS_CLAIM,
    LocalStockStoreProfile.SERIAL_ID_CLAIM, LocalStockStoreProfile.SERIAL_CODE_CLAIM)

/** Bounded repeated scans avoid a stock-size Set; returned values are the actual original rows. */
internal suspend fun stockRequiredAuthoritySources(stock: LocalStockRelationalInspection,
    consume: suspend (StreamedSourceObservationV2.Current) -> Unit) {
    stock.intents { intent ->
        if (intent is StockIntentV2Wire.Line) {
            for (stamps in listOf(intent.authority.itemSources, intent.authority.warehouseSources)) {
                localStockCheck(stamps.size in 1..16 && stamps.map { it.kind to it.id }.distinct().size == stamps.size)
                for (stamp in stamps) {
                    val key = AggregateKey(stamp.kind, stamp.id)
                    localStockCheck(key.kind !in StockRelationalKindsV2.all)
                    var source: StreamedSourceObservationV2.Current? = null
                    stock.original.frames { frame ->
                        val actual = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Current
                        if (actual?.key == key) {
                            localStockCheck(source == null && actual.generation == stamp.revision && actual.payloadHash == PayloadDigest(stamp.digest))
                            source = actual
                        }
                    }
                    consume(source ?: inconsistentStock())
                }
            }
        }
    }
}
