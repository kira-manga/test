package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

internal fun arrivalNormalize(key: CommandKey, input: StoredBytes): StoredBytes = when (key) {
    LocalUnpricedArrivalProfileV1.RECORD -> arrivalEncode(arrivalDecode(input, LocalRecordUnpricedArrivalV1.serializer(), 32_768), LocalRecordUnpricedArrivalV1.serializer(), 32_768)
    LocalUnpricedArrivalProfileV1.QUALIFY -> arrivalEncode(arrivalDecode(input, LocalQualifyUnpricedArrivalV1.serializer(), 32_768), LocalQualifyUnpricedArrivalV1.serializer(), 32_768)
    LocalUnpricedArrivalProfileV1.REJECT -> arrivalEncode(arrivalDecode(input, LocalRejectUnpricedArrivalV1.serializer(), 32_768), LocalRejectUnpricedArrivalV1.serializer(), 32_768)
    LocalUnpricedArrivalProfileV1.DISPATCH -> arrivalEncode(arrivalDecode(input, LocalDispatchUnpricedArrivalV1.serializer(), 32_768), LocalDispatchUnpricedArrivalV1.serializer(), 32_768)
    else -> error("Unsupported unpriced arrival command")
}

internal data class ArrivalTransitionInput(
    val action: LocalArrivalActionV1, val eventId: DomainId, val arrivalId: DomainId, val expectedArrivalRevision: Revision,
    val custodyUnitId: DomainId, val quantity: Quantity, val split: LocalArrivalSplitV1?,
    val lotId: DomainId?, val expiresAt: UtcInstant?, val serialId: DomainId?, val serialCode: String?, val serialRegistrationRevision: Revision?,
    val inspectionPassed: Boolean, val recipientSupplierId: DomainId?, val evidence: LocalArrivalEvidenceSelectionV1, val reasonCode: String,
)
internal fun arrivalTransition(key: CommandKey, input: StoredBytes): ArrivalTransitionInput = when (key) {
    LocalUnpricedArrivalProfileV1.QUALIFY -> arrivalDecode(input, LocalQualifyUnpricedArrivalV1.serializer(), 32_768).let {
        ArrivalTransitionInput(LocalArrivalActionV1.QUALIFY, it.eventId, it.arrivalId, it.expectedArrivalRevision, it.custodyUnitId,
            it.quantity, it.split, it.lotId, it.expiresAt, it.serialId, it.serialCode, it.serialRegistrationRevision,
            it.inspectionPassed, null, it.evidence, it.reasonCode) }
    LocalUnpricedArrivalProfileV1.REJECT -> arrivalDecode(input, LocalRejectUnpricedArrivalV1.serializer(), 32_768).let {
        ArrivalTransitionInput(LocalArrivalActionV1.REJECT, it.eventId, it.arrivalId, it.expectedArrivalRevision, it.custodyUnitId,
            it.quantity, it.split, null, null, null, null, null, false, null, it.evidence, it.reasonCode) }
    LocalUnpricedArrivalProfileV1.DISPATCH -> arrivalDecode(input, LocalDispatchUnpricedArrivalV1.serializer(), 32_768).let {
        ArrivalTransitionInput(LocalArrivalActionV1.DISPATCH, it.eventId, it.arrivalId, it.expectedArrivalRevision, it.custodyUnitId,
            it.quantity, it.split, null, null, null, null, null, false, it.recipientSupplierId, it.evidence, it.reasonCode) }
    else -> error("Unsupported unpriced arrival transition")
}

internal fun arrivalInitialSlices(request: LocalRecordUnpricedArrivalV1): List<ArrivalSliceV1> = arrivalInitialSlicesShared(request.lines)

/** Source-neutral shared planner preserves this v1 API and its exact physical outcomes. */
internal fun arrivalPhysicalSuccessor(original: ArrivalBodyV1.Original, before: ArrivalBodyV1.State,
    input: ArrivalTransitionInput): Pair<List<ArrivalSliceV1>, ArrivalSliceV1> =
    arrivalPhysicalSuccessorShared(original.physical(), before.arrivalId, before.slices, input.physical())

internal fun requireArrivalSliceTracking(slice: ArrivalSliceV1, item: ArrivalItemAuthorityWireV1) {
    requireArrivalSliceTrackingShared(slice, ArrivalPhysicalItem(item.selection.skuId,
        item.selection.baseUnitId, item.selection.quantityScale, item.tracking))
}

internal fun requireArrivalPhysicalState(original: ArrivalBodyV1.Original, slices: List<ArrivalSliceV1>) {
    requireArrivalPhysicalStateShared(original.physical(), slices)
}

internal fun arrivalSourceIdentity(arrivalId: DomainId, slice: ArrivalSliceV1): ArrivalBodyV1.SourceIdentity =
    ArrivalBodyV1.SourceIdentity(arrivalId, slice.lineId, slice.rootSourceId, slice.originalCustodyUnitId, slice.sourceId, slice.custodyUnitId, slice.parentCustodyUnitId)

internal fun arrivalRequiredGuardKeys(original: LocalRecordUnpricedArrivalV1, slices: List<ArrivalSliceV1>): Set<AggregateKey> = buildSet {
    for (slice in slices) {
        slice.serialCode?.let { add(arrivalSerialCodeKey(it)) }
        slice.serialId?.let { add(arrivalSerialIdKey(it)) }
        slice.lotId?.let { lot -> add(arrivalLotKey(original.lines.single { it.lineId == slice.lineId }.skuId, lot)) }
    }
}

/** Only missing identity facts are appended. Compatible batch reuse does not rewrite its guard. */
internal fun arrivalGuardSuccessors(original: ArrivalBodyV1.Original, slices: List<ArrivalSliceV1>,
    before: Map<AggregateKey, ArrivalHistorical?>): Map<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>> {
    check(before.keys == arrivalRequiredGuardKeys(original.request, slices))
    val result = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>>()
    for (slice in slices) {
        val line = original.request.lines.single { it.lineId == slice.lineId }
        val item = original.authority.items.single { it.selection.skuId == line.skuId }
        val code = slice.serialCode
        if (code != null) for (alias in if (slice.serialId == null) listOf(ArrivalSerialAliasV1.CODE) else ArrivalSerialAliasV1.entries) {
            val next = ArrivalBodyV1.SerialGuard(alias, original.request.arrivalId, line.lineId, slice.sourceId, slice.custodyUnitId,
                line.skuId, code, slice.serialId)
            val key = arrivalSerialKey(next)
            val prior = before.getValue(key)
            val value = prior?.value?.body as? ArrivalBodyV1.SerialGuard
            if (prior != null) {
                check(value != null)
                arrivalRequire(value.copy(serialId = next.serialId) == next && (value.serialId == null || value.serialId == next.serialId), LocalArrivalFailureV1.SOURCE_ALREADY_USED)
            }
            if (value != next) {
                check(key !in result)
                result[key] = prior?.reference() to next
            }
        }
        slice.lotId?.let { lot ->
            val key = arrivalLotKey(line.skuId, lot)
            val prior = before.getValue(key)
            val value = (result[key]?.second ?: prior?.value?.body) as? ArrivalBodyV1.LotGuard
            if (prior != null) check(prior.value.body is ArrivalBodyV1.LotGuard)
            if (value != null) {
                arrivalRequire(value.skuId == line.skuId && value.lotId == lot && value.productId == item.selection.productId &&
                    value.baseUnitId == item.selection.baseUnitId && value.quantityScale == item.selection.quantityScale, LocalArrivalFailureV1.INVALID_LOT)
                arrivalRequire(value.expiresAt == null || slice.expiresAt == null || value.expiresAt == slice.expiresAt, LocalArrivalFailureV1.INVALID_LOT)
            }
            val next = value?.copy(expiresAt = value.expiresAt ?: slice.expiresAt) ?: ArrivalBodyV1.LotGuard(line.skuId, lot,
                item.selection.productId, item.selection.baseUnitId, item.selection.quantityScale, slice.expiresAt, original.request.arrivalId, slice.rootSourceId)
            if (next != prior?.value?.body) result[key] = prior?.reference() to next
        }
    }
    return result
}

internal fun arrivalClaims(scope: com.ukkera.pos.security.BranchScope, writes: Map<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>>,
    originalRequest: LocalRecordUnpricedArrivalV1?): List<ClaimChange> = buildList {
    for ((key, entry) in writes) if (entry.first == null) when (val body = entry.second) {
        is ArrivalBodyV1.Original, is ArrivalBodyV1.State, is ArrivalBodyV1.Event -> add(ClaimChange.Acquire(arrivalDocumentClaim(key.kind, key.id), key.id))
        is ArrivalBodyV1.SourceIdentity -> {
            add(ClaimChange.Acquire(arrivalSourceClaim(body.sourceId), body.sourceId))
            add(ClaimChange.Acquire(arrivalUnitClaim(body.custodyUnitId), body.sourceId))
            if (body.parentCustodyUnitId == null) add(ClaimChange.Acquire(arrivalLineClaim(body.lineId), body.arrivalId))
        }
        is ArrivalBodyV1.SerialGuard -> add(ClaimChange.Acquire(arrivalSerialClaim(body), body.sourceId))
        is ArrivalBodyV1.LotGuard -> add(ClaimChange.Acquire(arrivalLotClaim(key), key.id))
    }
    originalRequest?.let { add(ClaimChange.Acquire(arrivalDeliveryClaim(scope, it), it.arrivalId)) }
}.also { check(it.map { change -> change.key }.distinct().size == it.size) }

/** Closed WHOLE retained producer validation. Never reruns a handler or today's business authority. */
internal suspend fun requireArrivalProducer(reader: ArrivalReader, evidence: RetainedCommandEvidence) {
    check(evidence.committed && evidence.scope == reader.scope && evidence.command in arrivalCommands && evidence.jobs.isEmpty())
    check(arrivalNormalize(evidence.command, evidence.normalized) == evidence.normalized &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest &&
        sha256(evidence.result.copyBytes()) == evidence.resultHash)
    check(evidence.aggregateWrites.size in 1..128 && evidence.claimChanges.size <= 128 &&
        evidence.aggregateWrites.map { it.key }.distinct().size == evidence.aggregateWrites.size &&
        evidence.claimChanges.map { it.key }.distinct().size == evidence.claimChanges.size)
    val after = evidence.aggregateWrites.associate { row ->
        check(row.key.kind in arrivalKinds && row.schema == LocalUnpricedArrivalProfileV1.schema(row.key.kind) && sha256(row.payload.copyBytes()) == row.payloadDigest)
        val value = arrivalDecodeRecord(row.payload)
        check(arrivalKey(value.kind, value.id) == row.key && value.revision == row.storageGeneration && value.scope == evidence.scope &&
            value.producer == ArrivalProducerV1.from(evidence) && value.recordedAt == evidence.recordedAt && value.policyRevision == evidence.policyRevision)
        row.key to ArrivalHistorical(row, value)
    }
    val action = arrivalAction(evidence.command)
    val expected = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>>()
    val original: ArrivalBodyV1.Original
    val selected: List<ArrivalSliceV1>
    val authority: ArrivalAuthorityWireV1
    val newlyQualified: List<ArrivalSliceV1>
    val selection: LocalArrivalEvidenceSelectionV1
    val expectedResult: LocalArrivalResultV1
    val originRequest: LocalRecordUnpricedArrivalV1?
    if (action == LocalArrivalActionV1.RECORD) {
        val request = arrivalDecode(evidence.normalized, LocalRecordUnpricedArrivalV1.serializer(), 32_768)
        val originalRow = after.getValue(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, request.arrivalId))
        original = originalRow.value.body as ArrivalBodyV1.Original
        check(original.request == request && original.normalizedDeliveryReference == request.supplierDeliveryReference?.let(::normalizedPurchaseReference))
        selected = arrivalInitialSlices(request)
        newlyQualified = selected.filter { it.serialId != null }
        val state = ArrivalBodyV1.State(request.arrivalId, originalRow.reference(), selected, emptyList())
        expected[originalRow.row.key] = null to original
        expected[arrivalKey(LocalUnpricedArrivalProfileV1.STATE, request.arrivalId)] = null to state
        selected.forEach { slice -> val source = arrivalSourceIdentity(request.arrivalId, slice); expected[arrivalBodyKey(source)] = null to source }
        authority = original.authority; selection = request.evidence; originRequest = request
        expectedResult = LocalArrivalResultV1(action, request.arrivalId, null, Revision(1), null)
    } else {
        val request = arrivalTransition(evidence.command, evidence.normalized)
        val eventRow = after.getValue(arrivalKey(LocalUnpricedArrivalProfileV1.EVENT, request.eventId))
        val event = eventRow.value.body as ArrivalBodyV1.Event
        check(event.action == action && event.request == evidence.normalized.copyBytes().decodeToString(throwOnInvalidSequence = true))
        val originalRow = reader.historical(event.original, evidence)
        val previous = reader.historical(event.stateBefore, evidence)
        original = originalRow.value.body as ArrivalBodyV1.Original
        val before = previous.value.body as ArrivalBodyV1.State
        check(originalRow.row.key == arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, request.arrivalId) &&
            previous.row.key == arrivalKey(LocalUnpricedArrivalProfileV1.STATE, request.arrivalId) &&
            previous.row.storageGeneration == request.expectedArrivalRevision && before.original == event.original &&
            before.events.size < LocalUnpricedArrivalProfileV1.MAX_EVENTS && previous.value.recordedAt <= evidence.recordedAt)
        val (slices, changed) = arrivalPhysicalSuccessor(original, before, request)
        check(event.changedSlice == changed)
        val selectedBefore = before.slices.single { it.custodyUnitId == request.custodyUnitId }
        newlyQualified = if (selectedBefore.serialId == null && changed.serialId != null) listOf(changed) else emptyList()
        val state = before.copy(slices = slices, events = before.events + eventRow.reference())
        expected[eventRow.row.key] = null to event
        expected[previous.row.key] = previous.reference() to state
        if (request.split != null) {
            val source = arrivalSourceIdentity(request.arrivalId, changed)
            expected[arrivalBodyKey(source)] = null to source
        }
        authority = event.authority; selection = request.evidence; selected = listOf(changed); originRequest = null
        expectedResult = LocalArrivalResultV1(action, request.arrivalId, request.eventId, request.expectedArrivalRevision.next(), changed.custodyUnitId)
    }
    requireArrivalPhysicalState(original, (expected.getValue(arrivalKey(LocalUnpricedArrivalProfileV1.STATE, original.request.arrivalId)).second as ArrivalBodyV1.State).slices)
    requireArrivalAuthorityShape(evidence, original, selected, newlyQualified, authority, selection)
    reader.requireStockObservations(evidence, original, selected, authority)
    check(authority.guards.map { it.key() }.distinct().size == authority.guards.size &&
        authority.guards.map { it.key() }.toSet() == arrivalRequiredGuardKeys(original.request, selected))
    val guards = linkedMapOf<AggregateKey, ArrivalHistorical?>()
    for (observation in authority.guards) {
        check(observation.before == null || observation.before.key() == observation.key())
        guards[observation.key()] = observation.before?.let { reader.historical(it, evidence) }
    }
    for ((key, entry) in arrivalGuardSuccessors(original, selected, guards)) {
        check(key !in expected); expected[key] = entry
    }
    check(after.keys == expected.keys)
    for ((key, entry) in expected) {
        val row = after.getValue(key)
        check(row.value.body == entry.second && row.value.previous == entry.first && row.value.revision == (entry.first?.revision ?: Revision(0)).next())
    }
    val claims = arrivalClaims(evidence.scope, expected, originRequest)
    check(evidence.claimChanges.size == claims.size && evidence.claimChanges.all { retained ->
        retained.action == RetainedClaimAction.ACQUIRE && claims.singleOrNull { it.key == retained.key }?.ownerId == retained.ownerId
    })
    check(evidence.result == arrivalEncode(expectedResult, LocalArrivalResultV1.serializer()))
}

private fun requireArrivalAuthorityShape(evidence: RetainedCommandEvidence, original: ArrivalBodyV1.Original, selected: List<ArrivalSliceV1>,
    newlyQualified: List<ArrivalSliceV1>, value: ArrivalAuthorityWireV1, input: LocalArrivalEvidenceSelectionV1) {
    val action = arrivalAction(evidence.command)
    val lines = selected.map { slice -> original.request.lines.single { it.lineId == slice.lineId } }
    val itemIds = lines.map { it.skuId }.toSet(); val warehouseIds = lines.map { it.warehouseId }.toSet()
    check(value.supplier.id == original.request.supplierId && value.supplier.storageRevision.value >= 1 &&
        value.supplierSource.id == value.supplier.id && value.supplierSource.revision == value.supplier.storageRevision && value.supplierSource in value.supplierSources)
    check(value.items.map { it.selection.skuId }.toSet() == itemIds && value.items.size == itemIds.size &&
        value.warehouses.map { it.selection.warehouseId }.toSet() == warehouseIds && value.warehouses.size == warehouseIds.size)
    for (item in value.items) {
        val originalItem = original.request.items.single { it.skuId == item.selection.skuId }
        check(item.selection.productId == originalItem.productId && item.selection.baseUnitId == originalItem.baseUnitId &&
            item.selection.quantityScale == originalItem.quantityScale && item.source.id == item.selection.skuId &&
            item.source.revision == item.selection.storageRevision && item.source in item.sources)
        val originalFacts = original.authority.items.single { it.selection.skuId == item.selection.skuId }
        check(item.tracking == originalFacts.tracking && (!item.requiresExpiry || item.tracking == LocalArrivalTrackingV1.LOT))
        if (action == LocalArrivalActionV1.RECORD) check(item.selection == originalItem && item.active)
    }
    for (warehouse in value.warehouses) {
        check(warehouse.source.id == warehouse.selection.warehouseId && warehouse.source.revision == warehouse.selection.storageRevision && warehouse.source in warehouse.sources)
        if (action == LocalArrivalActionV1.RECORD) check(warehouse.active && warehouse.selection == original.request.warehouses.single { it.warehouseId == warehouse.selection.warehouseId })
    }
    if (action == LocalArrivalActionV1.RECORD) check(value.supplier.active && value.supplier.storageRevision == original.request.expectedSupplierRevision)
    val policy = value.policy
    check(policy.supplierId == original.request.supplierId && policy.value.policyId == input.policyId && policy.value.revision == input.policyRevision &&
        policy.source.id == policy.value.policyId && policy.source in policy.sources && evidence.recordedAt >= policy.value.validFrom && (policy.value.validUntil?.let { evidence.recordedAt < it } ?: true))
    val attachment = value.attachment
    check((attachment == null) == (input.attachmentId == null))
    if (action in policy.value.attachmentRequiredFor) check(attachment != null)
    attachment?.let {
        check(it.attachmentId == input.attachmentId && it.source.id == it.attachmentId && it.source.revision == input.attachmentStorageRevision &&
            it.supplierId == original.request.supplierId && it.action == action && it.source in it.sources)
    }
    val selectedSerials = newlyQualified
    check(value.serialRegistrations.map { it.serialId }.toSet() == selectedSerials.map { it.serialId }.toSet() &&
        value.serialRegistrations.size == selectedSerials.size)
    value.serialRegistrations.forEach { registration ->
        val slice = selectedSerials.single { it.serialId == registration.serialId }
        val line = original.request.lines.single { it.lineId == slice.lineId }
        check(registration.scope.restore() == evidence.scope && registration.skuId == line.skuId && registration.serialCode == slice.serialCode &&
            registration.revision == slice.serialRegistrationRevision && registration.sources.isNotEmpty())
        check(registration == StockSerialRegistrationWire.from(registration.restore()))
        requireSerialRegistration(registration.restore(), evidence.scope, registration.serialId, line.skuId, requireNotNull(slice.serialCode), evidence.recordedAt)
    }
    val groups = listOf(value.supplierSources, policy.sources) + value.items.map { it.sources } + value.warehouses.map { it.sources } +
        value.serialRegistrations.map { it.sources } + listOfNotNull(attachment?.sources)
    for (sources in groups) {
        check(sources.size in 1..16 && sources.map { it.kind to it.id }.distinct().size == sources.size)
        sources.forEach { check(it.revision.value >= 1); it.restore() }
    }
    val all = groups.flatten().groupBy { it.kind to it.id }
    check(all.size <= 128 && all.values.all { it.distinct().size == 1 })
}
