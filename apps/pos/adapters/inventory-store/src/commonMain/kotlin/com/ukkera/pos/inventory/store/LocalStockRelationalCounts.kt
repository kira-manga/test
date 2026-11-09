package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable internal data class StockCountRequestV2Wire(val action: Int, val operationId: OperationId,
    val capture: StockCountCaptureWire?, val post: StockCountPostWire?) {
    fun restore(): LocalStockRelationalCommand = when (action) {
        7 -> { localStockCheck(capture != null && post == null); LocalStockRelationalCommand.CaptureCount(operationId, checkNotNull(capture).restore()) }
        8 -> { localStockCheck(capture == null && post != null); LocalStockRelationalCommand.PostCount(operationId, checkNotNull(post).restore()) }
        else -> inconsistentStock()
    }
}
internal const val STOCK_COUNT_EFFECT_V2: String = "local.stock.v2.count"

/** Module-local planning output. Production owns its writer and exact retained checker;
 * a recording output can exercise the same plan without granting storage authority. */
internal abstract class CountOutput {
    abstract suspend fun record(value: StockRelationalBody)
    abstract suspend fun claim(key: ClaimKey, owner: DomainId)
    abstract suspend fun document(key: AggregateKey, before: StoredAggregate?, value: StockCountWire)
    class Current(private val context: CommandContext, private val work: StockPostingBuilder,
        private val sink: StreamedCommandSink) : CountOutput() {
        override suspend fun record(value: StockRelationalBody) { work.put(value) }
        override suspend fun claim(key: ClaimKey, owner: DomainId) { work.acquire(key, owner) }
        override suspend fun document(key: AggregateKey, before: StoredAggregate?, value: StockCountWire) {
            val body = stockBody(value, StockCountWire.serializer())
            sink.change(AggregateChange(key, before?.revision, stockSchema(key.kind, body),
                stockRecord(context.scope, key, before?.revision, StockProducerWire.from(context), body, before)))
        }
    }
    class Retained(private val stock: LocalStockRelationalInspection, private val target: AggregateKey? = null) : CountOutput() {
        var recognized: Boolean = false
        val claims = linkedMapOf<ClaimKey, DomainId>() // At most document/source/surplus-basis, never holder-sized.
        override suspend fun record(value: StockRelationalBody) {
            val key = stockKey(value)
            val row = stock.write(key) ?: inconsistentStock()
            val before = stock.before(key)
            localStockCheck(row.body == value && row.producer == stock.original.identity.producer &&
                row.generation == (before?.generation?.next() ?: Revision(1)) && row.previous == before?.let {
                    StockPreviousWire(it.producer.operationId, it.generation, it.digest.value) })
            if (target == key) recognized = true
        }
        override suspend fun claim(key: ClaimKey, owner: DomainId) {
            localStockCheck(stock.claim(key)?.let { it.action == RetainedClaimAction.ACQUIRE && it.ownerId == owner } == true)
            localStockCheck(claims.put(key, owner) == null)
        }
        override suspend fun document(key: AggregateKey, before: StoredAggregate?, value: StockCountWire) {
            val row = stock.original.writeRow(key) ?: inconsistentStock()
            val actual = stockRecord(row, stock.scope)
            val producer = stock.original.identity.producer
            localStockCheck(actual.body == stockBody(value, StockCountWire.serializer()) && row.revision == (before?.revision?.next() ?: Revision(1)) &&
                actual.previous == before?.let { StockPreviousWire(stockRecord(it, stock.scope).producer.operationId, it.revision, it.digest.value) } &&
                actual.producer == StockProducerWire(producer.operationId, producer.command.name, producer.command.version,
                    producer.originalActorId, producer.semanticDigest.value))
            if (target == key) recognized = true
        }
    }
}

internal class CountPlan(val id: DomainId, val result: StoredBytes, val deadline: UtcInstant?,
    val resources: Set<RecoveryResourceKey>)

internal suspend fun countPlan(request: LocalStockRelationalCommand, read: StockReceiptAccess,
    basis: LocalCountSurplusBasis?, output: CountOutput): CountPlan {
    val activation = read.normal(LocalStockRelationalProfileV2.activationKey(read.scope.branchId)) ?: unavailableProducer()
    localStockCheck(activation.body is StockRelationalBody.Activation && stockActivationShape(activation.body))
    val id = when (request) {
        is LocalStockRelationalCommand.CaptureCount -> { localStockCheck(request.operationId == read.operationId); request.request.countId }
        is LocalStockRelationalCommand.PostCount -> { localStockCheck(request.operationId == read.operationId); request.request.countId }
        else -> inconsistentStock()
    }
    val resources = linkedSetOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, id))
    val key = AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, id)
    val current = read.document(key)
    localStockCheck(read.claim(LocalStockStoreProfile.documentClaim(id)) == current?.key?.id)
    if (request is LocalStockRelationalCommand.CaptureCount) {
        val input = request.request
        localStockCheck(current == null && basis == null, LocalStockFailure.SOURCE_ALREADY_USED)
        val item = read.normal(LocalStockRelationalProfileV2.itemKey(input.position.skuId)) ?: missingStock()
        localStockCheck(item.body is StockRelationalBody.Item && item.generation == input.expectedItemRevision &&
            read.claim(LocalStockStoreProfile.itemClaim(input.position.skuId)) == input.position.skuId, LocalStockFailure.STALE_REVISION)
        val position = (read.normal(LocalStockRelationalProfileV2.positionKey(input.position.inScope(read.scope)))?.body
            as? StockRelationalBody.Position)?.value ?: missingStock()
        output.claim(LocalStockStoreProfile.documentClaim(id), id)
        output.document(key, null, StockCountWire(id, StockPositionRefWire.from(position.key), item.generation, position.onHand,
            read.actorId, read.now, null, null, null, input.reasonCode, null))
        return CountPlan(id, stockResult(LocalStockResultKind.COUNT_CAPTURED, id), null, resources)
    }
    val input = (request as LocalStockRelationalCommand.PostCount).request
    val row = current ?: missingStock()
    val before = receiptDocument(row, read.scope, StockCountWire.serializer()).value
    resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, before.capturedBy)
    localStockCheck(row.revision == input.expectedCountRevision, LocalStockFailure.STALE_REVISION)
    localStockCheck(before.counted == null && read.claim(LocalStockStoreProfile.sourceClaim(id)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
    val positionKey = before.position.key(read.scope)
    val item = read.normal(LocalStockRelationalProfileV2.itemKey(positionKey.pool.skuId)) ?: inconsistentStock()
    localStockCheck(item.generation == before.capturedItemRevision, LocalStockFailure.COUNT_REQUIRES_RECOUNT)
    localStockCheck(read.claim(LocalStockStoreProfile.itemClaim(positionKey.pool.skuId)) == positionKey.pool.skuId)
    val position = (read.normal(LocalStockRelationalProfileV2.positionKey(positionKey))?.body as? StockRelationalBody.Position)?.value
        ?: inconsistentStock()
    localStockCheck(position.onHand == before.capturedQuantity && input.serialId == positionKey.serialId)
    localStockCheck(input.counted.unitId == position.onHand.unitId && input.counted.scale == position.onHand.scale && input.counted.scaled >= 0,
        LocalStockFailure.INVALID_UNIT)
    if (input.serialId != null) localStockCheck((input.counted.scaled == 0L || input.counted == stockSerialUnit(input.counted)) &&
        input.counted <= position.onHand, LocalStockFailure.INVALID_SERIAL)
    val difference = input.counted - position.onHand
    val surplus = difference.scaled > 0
    if (surplus) {
        val approved = basis ?: throw LocalStockRejected(LocalStockFailure.COUNT_SURPLUS_REQUIRES_APPROVAL)
        localStockCheck(approved.basisId == input.surplusBasisId && approved.revision == input.expectedSurplusBasisRevision &&
            positionKey.pool.ownership == StockOwnership.CompanyOwned && positionKey.serialId == null, LocalStockFailure.BASIS_UNAVAILABLE)
        requireCountSurplusBasis(approved, read.scope, row.revision, before, input.counted, read.now)
        for (source in approved.sources) read.current(source)
        localStockCheck(read.claim(LocalStockStoreProfile.countSurplusClaim(approved.basisId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
        output.claim(LocalStockStoreProfile.countSurplusClaim(approved.basisId), id)
        resources += sourceResources(approved.sources)
        resources += listOf(approved.basisId, approved.approvedBy, approved.policyId).map { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) }
    } else localStockCheck(basis == null && input.surplusBasisId == null, LocalStockFailure.BASIS_UNAVAILABLE)
    output.claim(LocalStockStoreProfile.sourceClaim(id), id)
    if (difference.scaled != 0L) {
        // Counting is not quality release. Preserve the original restriction in
        // both current planning and exact retained validation; never legitimize
        // an old count that erased recall/expiry protection.
        output.record(StockRelationalBody.Position(position.copy(onHand = input.counted,
            safety = if (difference.scaled < 0L && position.safety == StockSafety.CLEAR)
                StockSafety.COUNT_REVIEW else position.safety)))
        if (positionKey.pool.ownership == StockOwnership.CompanyOwned) {
            val pool = (read.normal(LocalStockRelationalProfileV2.poolKey(positionKey.pool))?.body as? StockRelationalBody.Pool)?.value
                ?: inconsistentStock()
            requireReceiptPoolBefore(read, positionKey.pool, pool, pool.onHand)
            val delta = if (surplus) InventoryValuationProjection.receive(pool, difference, checkNotNull(basis).surplusValue)
                else InventoryValuationProjection.issue(pool, -difference)
            val event = StockRelationalBody.ValueEvent(positionKey.pool, id, delta.after.lastEventOrdinal,
                if (surplus) StockValueEventKind.COUNT_SURPLUS else StockValueEventKind.COUNT_SHORTAGE,
                pool, delta.after, difference, delta.value, delta.value, 1, null)
            output.record(StockRelationalBody.Pool(delta.after)); output.record(event)
            output.record(StockRelationalBody.ValueSlice(positionKey.pool, id, stockKey(event).id, event.ordinal, null, id,
                if (surplus) difference else -difference, if (surplus) delta.value else -delta.value))
        }
        suspend fun touch(skuId: DomainId) {
            val metadata = read.normal(LocalStockRelationalProfileV2.itemKey(skuId)) ?: inconsistentStock()
            localStockCheck(metadata.body is StockRelationalBody.Item && read.claim(LocalStockStoreProfile.itemClaim(skuId)) == skuId)
            output.record(metadata.body)
        }
        touch(positionKey.pool.skuId)
        if (difference.scaled < 0) {
            // Every holder is retained, including paid/unknown claims in excess of counted stock.
            // Never truncate, expire money, or resell the rights merely to make a count balance.
            read.range(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(positionKey).id)) { member ->
                val allocation = member.body as? StockRelationalBody.Allocation
                if (allocation != null) {
                    localStockCheck(allocation.value.allocation.position == positionKey)
                    val reservation = read.normal(LocalStockRelationalProfileV2.reservationKey(allocation.value.reservationId))?.body
                        as? StockRelationalBody.Reservation ?: inconsistentStock()
                    localStockCheck(read.claim(LocalStockStoreProfile.reservationClaim(reservation.value.id)) == reservation.value.id)
                    var allocations = 0L; var holds = 0L; var existing = false
                    val hold = StockRelationalBody.Hold(InventoryHoldRecord(reservation.value.id, ReservationHold(ReservationHoldReason.COUNT_SHORTAGE, positionKey)))
                    read.range(StockQueryV2.ReservationMembers(reservation.value.id)) { source -> when (val body = source.body) {
                        is StockRelationalBody.Allocation -> {
                            val a = body.value.allocation
                            localStockCheck(body.value.reservationId == reservation.value.id && a.allocationId == LocalStockRelationalProfileV2.allocationId(
                                reservation.allocationSetId, reservation.allocationSetRevision, a.lineId, a.position) &&
                                read.claim(LocalStockStoreProfile.allocationClaim(a.allocationId)) == reservation.value.id)
                            allocations = stockCheckedAdd(allocations, 1)
                            if (reservation.value.retainsStockClaim) touch(a.position.pool.skuId)
                        }
                        is StockRelationalBody.Hold -> {
                            localStockCheck(body.value.reservationId == reservation.value.id)
                            holds = stockCheckedAdd(holds, 1); if (body == hold) existing = true
                        }
                        else -> inconsistentStock()
                    } }
                    localStockCheck(allocations == reservation.value.allocationCount && holds == reservation.value.holdCount)
                    if (reservation.value.retainsStockClaim && !existing) {
                        output.record(hold)
                        output.record(reservation.copy(value = InventoryRelationalPlanner.hold(reservation.value, 1)))
                    }
                } else localStockCheck(member.body is StockRelationalBody.Hold && member.body.value.hold.position == positionKey)
            }
        }
    }
    output.document(key, row, before.copy(counted = input.counted, postedBy = read.actorId, postedAt = read.now,
        postingReasonCode = input.reasonCode, surplusBasis = basis?.let(StockCountSurplusBasisWire::from)))
    return CountPlan(id, stockResult(LocalStockResultKind.COUNT_POSTED, id, listOf(LocalStockItemVersion(positionKey.pool.skuId,
        if (difference.scaled == 0L) item.generation else item.generation.next()))), basis?.validUntil, resources)
}

internal suspend fun countStock(repository: LocalStockRepository, authority: LocalStockAuthorityReader,
    view: CommandReadView, context: CommandContext, sink: StreamedCommandSink, now: UtcInstant,
    request: LocalStockRelationalCommand): StreamedMutationDecision.Apply {
    val read = repository.receivingRelationalRead(view, context, sink)
    val basis = (request as? LocalStockRelationalCommand.PostCount)?.request?.surplusBasisId?.let {
        repository.receivingAuthority(authority).countSurplusBasis(view, it) ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
    }
    val work = StockPostingBuilder(read, context, sink)
    val plan = countPlan(request, CurrentReceiptAccess(repository, read, context, now), basis, CountOutput.Current(context, work, sink))
    sink.effect(StreamedEffectV2(STOCK_COUNT_EFFECT_V2, plan.id, LocalStockRelationalCommandsV2.encode(request)))
    for (resource in plan.resources) sink.resource(resource)
    return work.finish(plan.result, plan.deadline)
}

internal suspend fun requireCountStockProducer(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand) {
    localStockCheck(original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND && LocalStockRelationalCommandsV2.encode(request) == original.normalized)
    val basis = (request as? LocalStockRelationalCommand.PostCount)?.request?.surplusBasisId?.let { authority.retainedCountSurplus(original, it) }
    val output = CountOutput.Retained(stock)
    val plan = countPlan(request, RetainedReceiptAccess(stock), basis, output)
    localStockCheck(plan.result == original.result && (plan.deadline == null || stock.recordedAt < plan.deadline))
    var records = 0L; var rowEffects = 0L; var commandEffects = 0L; var claims = 0L
    original.frames { frame -> when (frame) {
        is RetainedCommandFrameV2.Write -> {
            if (frame.value.key.kind == StockRelationalKindsV2.POSTING) localStockCheck(frame.value.key.id.value == stock.operationId.value && frame.value.storageGeneration == Revision(1))
            else {
                val member = CountOutput.Retained(stock, frame.value.key)
                countPlan(request, RetainedReceiptAccess(stock), basis, member)
                localStockCheck(member.recognized)
                if (frame.value.key.kind in StockRelationalKindsV2.all) records = stockCheckedAdd(records, 1)
            }
        }
        is RetainedCommandFrameV2.Claim -> {
            localStockCheck(frame.value.action == RetainedClaimAction.ACQUIRE && output.claims[frame.value.key] == frame.value.ownerId &&
                stock.observedClaim(frame.value.key) == null)
            claims = stockCheckedAdd(claims, 1)
        }
        is RetainedCommandFrameV2.Effect -> when (frame.value.kind) {
            LocalStockRelationalProfileV2.EFFECT_KIND -> {
                val effect = stockDecode(frame.value.payload, StockRowEffectV2Wire.serializer())
                val key = AggregateKey(effect.kind, effect.id); val row = stock.write(key) ?: inconsistentStock(); val before = stock.before(key)
                localStockCheck(key.kind != StockRelationalKindsV2.POSTING && frame.value.id == rowEffectId(key) &&
                    effect == StockRowEffectV2Wire(key.kind, key.id, before?.generation, before?.digest?.value, row.digest.value))
                rowEffects = stockCheckedAdd(rowEffects, 1)
            }
            STOCK_COUNT_EFFECT_V2 -> { localStockCheck(frame.value.id == plan.id && frame.value.payload == original.normalized); commandEffects++ }
            else -> inconsistentStock()
        }
        is RetainedCommandFrameV2.Job -> inconsistentStock()
        is RetainedCommandFrameV2.Source -> {
            var used = false
            countPlan(request, RetainedReceiptAccess(stock) { if (it == frame.value) used = true }, basis, CountOutput.Retained(stock))
            localStockCheck(used)
        }
        else -> Unit
    } }
    localStockCheck(records == rowEffects && commandEffects == 1L && claims == output.claims.size.toLong())
    stock.requireResources(plan.resources)
}

/** Re-evaluate the admitted count plan with the genuine retained output checker.
 * Capture, equal counts and shortages explicitly have no deadline; surplus uses its exact basis. */
internal suspend fun retainedCountStockDeadline(stock: LocalStockRelationalInspection,
    authority: LocalStockRelationalAuthorityReader, request: LocalStockRelationalCommand): UtcInstant? {
    val basis = (request as? LocalStockRelationalCommand.PostCount)?.request?.surplusBasisId?.let {
        authority.retainedCountSurplus(stock.original, it)
    }
    return countPlan(request, RetainedReceiptAccess(stock), basis, CountOutput.Retained(stock)).deadline
}

/** Real approval view used by the existing catalog count-value authority after migration. */
internal suspend fun relationalCountApprovalSnapshot(repository: LocalStockRepository, view: CommandReadView,
    countId: DomainId): LocalStockCountApprovalSnapshot? {
    val read = repository.observeRelational(view)
    val count = stockDocument(repository, view, LocalStockStoreProfile.COUNT_DOCUMENT, countId, StockCountWire.serializer()) ?: return null
    val value = count.value
    val item = read.record(LocalStockRelationalProfileV2.itemKey(value.position.skuId)) ?: inconsistentStock()
    localStockCheck(read.itemVersion(value.position.skuId).storageRevision == item.generation)
    val position = read.record(LocalStockRelationalProfileV2.positionKey(value.position.key(view.scope))) ?: inconsistentStock()
    val current = (position.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
    localStockCheck(value.capturedQuantity.unitId == current.onHand.unitId && value.capturedQuantity.scale == current.onHand.scale)
    val same = item.generation == value.capturedItemRevision
    if (same) localStockCheck(current.onHand == value.capturedQuantity)
    val status = when { value.counted != null -> LocalStockCountApprovalStatus.POSTED
        !same -> LocalStockCountApprovalStatus.RECOUNT_REQUIRED
        else -> LocalStockCountApprovalStatus.OPEN }
    val sources = listOf(LocalStockSourceStamp(count.row.key, count.row.revision, count.row.digest),
        LocalStockSourceStamp(item.key, item.generation, item.digest), LocalStockSourceStamp(position.key, position.generation, position.digest))
        .sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
    return LocalStockCountApprovalSnapshot(view.scope, countId, count.row.revision, value.position.restore(), value.capturedItemRevision,
        value.capturedQuantity, value.capturedBy, value.capturedAt, item.generation, current.onHand, status, sources,
        setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, countId), RecoveryResourceKey(RecoveryResourceKind.STOCK, value.position.skuId),
            RecoveryResourceKey(RecoveryResourceKind.STOCK, value.position.warehouseId), RecoveryResourceKey(RecoveryResourceKind.STOCK, position.key.id),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, value.capturedBy)))
}
