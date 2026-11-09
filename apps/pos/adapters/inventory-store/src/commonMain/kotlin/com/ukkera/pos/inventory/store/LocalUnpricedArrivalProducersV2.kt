package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal fun arrivalNormalizeV2(key: CommandKey, input: StoredBytes): StoredBytes = when (key) {
    LocalUnpricedArrivalProfileV2.RECORD -> arrivalEncode(arrivalDecode(input, LocalRecordUnpricedArrivalV2.serializer(), 32_768), LocalRecordUnpricedArrivalV2.serializer(), 32_768)
    LocalUnpricedArrivalProfileV2.QUALIFY -> arrivalEncode(arrivalDecode(input, LocalQualifyUnpricedArrivalV2.serializer(), 32_768), LocalQualifyUnpricedArrivalV2.serializer(), 32_768)
    LocalUnpricedArrivalProfileV2.REJECT -> arrivalEncode(arrivalDecode(input, LocalRejectUnpricedArrivalV2.serializer(), 32_768), LocalRejectUnpricedArrivalV2.serializer(), 32_768)
    LocalUnpricedArrivalProfileV2.DISPATCH -> arrivalEncode(arrivalDecode(input, LocalDispatchUnpricedArrivalV2.serializer(), 32_768), LocalDispatchUnpricedArrivalV2.serializer(), 32_768)
    else -> error("Unsupported v2 unpriced arrival command")
}

internal data class ArrivalTransitionInputV2(
    val action: LocalArrivalActionV1, val eventId: DomainId, val arrivalId: DomainId, val expectedArrivalRevision: Revision,
    val expectedRequesterPolicyRevision: Revision, val expectedSupplierRevision: Revision,
    val item: LocalArrivalItemSelectionV2, val warehouse: LocalArrivalWarehouseSelectionV2,
    val custodyUnitId: DomainId, val quantity: Quantity, val split: LocalArrivalSplitV2?,
    val lotId: DomainId?, val expiresAt: UtcInstant?, val serialId: DomainId?, val serialCode: String?, val serialRegistrationRevision: Revision?,
    val inspectionPassed: Boolean, val recipientSupplierId: DomainId?, val evidence: LocalArrivalEvidenceSelectionV2, val reasonCode: String,
) {
    fun physical(): ArrivalPhysicalTransition = ArrivalPhysicalTransition(action, arrivalId, custodyUnitId,
        quantity, split, lotId, expiresAt, serialId, serialCode, serialRegistrationRevision, inspectionPassed, recipientSupplierId)
}
internal fun arrivalTransitionV2(key: CommandKey, input: StoredBytes): ArrivalTransitionInputV2 = when (key) {
    LocalUnpricedArrivalProfileV2.QUALIFY -> arrivalDecode(input, LocalQualifyUnpricedArrivalV2.serializer(), 32_768).let {
        ArrivalTransitionInputV2(LocalArrivalActionV1.QUALIFY, it.eventId, it.arrivalId, it.expectedArrivalRevision,
            it.expectedRequesterPolicyRevision, it.expectedSupplierRevision, it.item, it.warehouse, it.custodyUnitId,
            it.quantity, it.split, it.lotId, it.expiresAt, it.serialId, it.serialCode, it.serialRegistrationRevision,
            it.inspectionPassed, null, it.evidence, it.reasonCode) }
    LocalUnpricedArrivalProfileV2.REJECT -> arrivalDecode(input, LocalRejectUnpricedArrivalV2.serializer(), 32_768).let {
        ArrivalTransitionInputV2(LocalArrivalActionV1.REJECT, it.eventId, it.arrivalId, it.expectedArrivalRevision,
            it.expectedRequesterPolicyRevision, it.expectedSupplierRevision, it.item, it.warehouse, it.custodyUnitId,
            it.quantity, it.split, null, null, null, null, null, false, null, it.evidence, it.reasonCode) }
    LocalUnpricedArrivalProfileV2.DISPATCH -> arrivalDecode(input, LocalDispatchUnpricedArrivalV2.serializer(), 32_768).let {
        ArrivalTransitionInputV2(LocalArrivalActionV1.DISPATCH, it.eventId, it.arrivalId, it.expectedArrivalRevision,
            it.expectedRequesterPolicyRevision, it.expectedSupplierRevision, it.item, it.warehouse, it.custodyUnitId,
            it.quantity, it.split, null, null, null, null, null, false, it.recipientSupplierId, it.evidence, it.reasonCode) }
    else -> error("Unsupported v2 unpriced arrival transition")
}

internal fun arrivalInitialSlicesV2(request: LocalRecordUnpricedArrivalV2): List<ArrivalSliceV1> = arrivalInitialSlicesShared(request.lines)
internal fun ArrivalBodyV2.Original.physical(): ArrivalPhysicalOriginal = ArrivalPhysicalOriginal(request.arrivalId,
    request.supplierId, request.lines, authority.items.map { ArrivalPhysicalItem(it.selection.skuId,
        it.selection.baseUnitId, it.selection.quantityScale, it.tracking) }, ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS)
internal fun arrivalPhysicalSuccessorV2(original: ArrivalBodyV2.Original, before: ArrivalBodyV2.State,
    input: ArrivalTransitionInputV2): Pair<List<ArrivalSliceV1>, ArrivalSliceV1> =
    arrivalPhysicalV2 { arrivalPhysicalSuccessorShared(original.physical(), before.arrivalId, before.slices, input.physical()) }
internal fun requireArrivalPhysicalStateV2(original: ArrivalBodyV2.Original, slices: List<ArrivalSliceV1>) {
    arrivalPhysicalV2 { requireArrivalPhysicalStateShared(original.physical(), slices) }
}
private inline fun <T> arrivalPhysicalV2(block: () -> T): T = try { block() }
    catch (failure: ArrivalRejected) { throw ArrivalRejectedV2(LocalArrivalFailureV2.valueOf(failure.failure.name)) }

/** Keep the existing permanent delivery namespace and exact v1 derivation across both profiles. */
internal fun arrivalDeliveryClaimShared(scope: BranchScope, supplierId: DomainId,
    supplierDeliveryReference: String?, localIntakeTag: DomainId?): ClaimKey {
    val identity = supplierDeliveryReference?.let { "supplier-reference/${normalizedPurchaseReference(it)}" }
        ?: "local-intake-tag/${requireNotNull(localIntakeTag).value}"
    val digest = sha256(("ukkera.arrival-delivery/v1/${scope.companyId.value}/${supplierId.value}/$identity").encodeToByteArray()).value
    return ClaimKey(LocalUnpricedArrivalProfileV1.DELIVERY_CLAIM, "${supplierId.value}/$digest")
}

internal fun arrivalSourceIdentityV2(arrivalId: DomainId, slice: ArrivalSliceV1): ArrivalBodyV2.SourceIdentity =
    ArrivalBodyV2.SourceIdentity(arrivalId, slice.lineId, slice.rootSourceId, slice.originalCustodyUnitId, slice.sourceId, slice.custodyUnitId, slice.parentCustodyUnitId)

internal fun arrivalRequiredGuardKeysV2(original: LocalRecordUnpricedArrivalV2, slices: List<ArrivalSliceV1>): Set<AggregateKey> = buildSet {
    for (slice in slices) {
        slice.serialCode?.let { add(arrivalSerialCodeKeyV2(it)) }
        slice.serialId?.let { add(arrivalSerialIdKeyV2(it)) }
        slice.lotId?.let { lot -> add(arrivalLotKeyV2(original.lines.single { it.lineId == slice.lineId }.skuId, lot)) }
    }
}

/** Only missing identity facts are appended. Compatible batch reuse does not rewrite its guard. */
internal fun arrivalGuardSuccessorsV2(original: ArrivalBodyV2.Original, slices: List<ArrivalSliceV1>,
    before: Map<AggregateKey, ArrivalHistoricalV2?>): Map<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>> {
    check(before.keys == arrivalRequiredGuardKeysV2(original.request, slices))
    val result = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>()
    for (slice in slices) {
        val line = original.request.lines.single { it.lineId == slice.lineId }
        val item = original.authority.items.single { it.selection.skuId == line.skuId }
        val code = slice.serialCode
        if (code != null) for (alias in if (slice.serialId == null) listOf(ArrivalSerialAliasV2.CODE) else ArrivalSerialAliasV2.entries) {
            val next = ArrivalBodyV2.SerialGuard(alias, original.request.arrivalId, line.lineId, slice.sourceId, slice.custodyUnitId,
                line.skuId, code, slice.serialId)
            val key = arrivalSerialKeyV2(next)
            val prior = before.getValue(key)
            val value = prior?.value?.body as? ArrivalBodyV2.SerialGuard
            if (prior != null) {
                check(value != null)
                arrivalRequireV2(value.copy(serialId = next.serialId) == next && (value.serialId == null || value.serialId == next.serialId), LocalArrivalFailureV2.SOURCE_ALREADY_USED)
            }
            if (value != next) {
                check(key !in result)
                result[key] = prior?.reference() to next
            }
        }
        slice.lotId?.let { lot ->
            val key = arrivalLotKeyV2(line.skuId, lot)
            val prior = before.getValue(key)
            val value = (result[key]?.second ?: prior?.value?.body) as? ArrivalBodyV2.LotGuard
            if (prior != null) check(prior.value.body is ArrivalBodyV2.LotGuard)
            if (value != null) {
                arrivalRequireV2(value.skuId == line.skuId && value.lotId == lot && value.productId == item.selection.productId &&
                    value.baseUnitId == item.selection.baseUnitId && value.quantityScale == item.selection.quantityScale, LocalArrivalFailureV2.INVALID_LOT)
                arrivalRequireV2(value.expiresAt == null || slice.expiresAt == null || value.expiresAt == slice.expiresAt, LocalArrivalFailureV2.INVALID_LOT)
            }
            val next = value?.copy(expiresAt = value.expiresAt ?: slice.expiresAt) ?: ArrivalBodyV2.LotGuard(line.skuId, lot,
                item.selection.productId, item.selection.baseUnitId, item.selection.quantityScale, slice.expiresAt, original.request.arrivalId, slice.rootSourceId)
            if (next != prior?.value?.body) result[key] = prior?.reference() to next
        }
    }
    return result
}

internal fun arrivalClaimsV2(scope: com.ukkera.pos.security.BranchScope, writes: Map<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>,
    originalRequest: LocalRecordUnpricedArrivalV2?): List<ClaimChange> = buildList {
    for ((key, entry) in writes) if (entry.first == null) when (val body = entry.second) {
        is ArrivalBodyV2.Original, is ArrivalBodyV2.State, is ArrivalBodyV2.Event -> add(ClaimChange.Acquire(arrivalDocumentClaimV2(key.kind, key.id), key.id))
        is ArrivalBodyV2.SourceIdentity -> {
            add(ClaimChange.Acquire(arrivalSourceClaimV2(body.sourceId), body.sourceId))
            add(ClaimChange.Acquire(arrivalUnitClaimV2(body.custodyUnitId), body.sourceId))
            if (body.parentCustodyUnitId == null) add(ClaimChange.Acquire(arrivalLineClaimV2(body.lineId), body.arrivalId))
        }
        is ArrivalBodyV2.SerialGuard -> add(ClaimChange.Acquire(arrivalSerialClaimV2(body), body.sourceId))
        is ArrivalBodyV2.LotGuard -> add(ClaimChange.Acquire(arrivalLotClaimV2(key), key.id))
    }
    originalRequest?.let { add(ClaimChange.Acquire(arrivalDeliveryClaimV2(scope, it), it.arrivalId)) }
}.also { check(it.map { change -> change.key }.distinct().size == it.size) }

/** Narrow inert predecessor traversal, not a historical CommandReadView or an authorization. */
internal interface ArrivalProducerAccessV2 {
    val scope: BranchScope
    val documentaryCodec: LocalUnpricedArrivalDocumentaryCodecV2
    suspend fun historical(reference: ArrivalRowRefV2, child: RetainedCommandEvidence? = null): ArrivalHistoricalV2
    suspend fun requireStockObservations(child: RetainedCommandEvidence, original: ArrivalBodyV2.Original,
        selected: List<ArrivalSliceV1>, authority: ArrivalAuthorityWireV2)
}

internal data class ArrivalRetainedPlanV2(
    val original: ArrivalBodyV2.Original, val selected: List<ArrivalSliceV1>, val authority: ArrivalAuthorityWireV2,
    val outcome: ProposedOutcome.Apply,
)

internal suspend fun requireArrivalProducerV2(reader: ArrivalProducerAccessV2, evidence: RetainedCommandEvidence) {
    arrivalRetainedPlanV2(reader, evidence)
}

/** Reconstructs every literal effect from the whole original; it never authenticates a source DTO. */
internal suspend fun arrivalRetainedPlanV2(reader: ArrivalProducerAccessV2, evidence: RetainedCommandEvidence): ArrivalRetainedPlanV2 {
    check(evidence.committed && evidence.scope == reader.scope && evidence.command in arrivalCommandsV2 && evidence.jobs.isEmpty())
    check(arrivalNormalizeV2(evidence.command, evidence.normalized) == evidence.normalized &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest &&
        sha256(evidence.result.copyBytes()) == evidence.resultHash)
    check(evidence.aggregateWrites.size in 1..128 && evidence.claimChanges.size <= 128 &&
        evidence.aggregateWrites.map { it.key }.distinct().size == evidence.aggregateWrites.size &&
        evidence.claimChanges.map { it.key }.distinct().size == evidence.claimChanges.size)
    val custody = evidence.aggregateWrites.filter { it.key.kind in arrivalKindsV2 }
    val after = custody.associate { row ->
        check(row.schema == LocalUnpricedArrivalProfileV2.schema(row.key.kind) && sha256(row.payload.copyBytes()) == row.payloadDigest)
        val value = arrivalDecodeRecordV2(row.payload)
        check(arrivalKeyV2(value.kind, value.id) == row.key && value.revision == row.storageGeneration && value.scope == evidence.scope &&
            value.producer == ArrivalProducerV2.from(evidence) && value.recordedAt == evidence.recordedAt && value.policyRevision == evidence.policyRevision)
        row.key to ArrivalHistoricalV2(row, value)
    }
    val action = arrivalActionV2(evidence.command)
    val expected = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>()
    val original: ArrivalBodyV2.Original
    val selected: List<ArrivalSliceV1>
    val authority: ArrivalAuthorityWireV2
    val newlyQualified: List<ArrivalSliceV1>
    val selection: LocalArrivalEvidenceSelectionV2
    val expectedResult: LocalArrivalResultV2
    val originRequest: LocalRecordUnpricedArrivalV2?
    val expectedPolicyRevision: Revision
    val expectedSupplierRevision: Revision
    val expectedItems: List<LocalArrivalItemSelectionV2>
    val expectedWarehouses: List<LocalArrivalWarehouseSelectionV2>
    val revision: Revision
    val reason: String
    if (action == LocalArrivalActionV1.RECORD) {
        val request = arrivalDecode(evidence.normalized, LocalRecordUnpricedArrivalV2.serializer(), 32_768)
        val originalRow = after.getValue(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, request.arrivalId))
        original = originalRow.value.body as ArrivalBodyV2.Original
        check(original.request == request && original.normalizedDeliveryReference == request.supplierDeliveryReference?.let(::normalizedPurchaseReference))
        selected = arrivalInitialSlicesV2(request)
        newlyQualified = selected.filter { it.serialId != null }
        val state = ArrivalBodyV2.State(request.arrivalId, originalRow.reference(), selected, emptyList())
        expected[originalRow.row.key] = null to original
        expected[arrivalKeyV2(LocalUnpricedArrivalProfileV2.STATE, request.arrivalId)] = null to state
        selected.forEach { slice -> val source = arrivalSourceIdentityV2(request.arrivalId, slice); expected[arrivalBodyKeyV2(source)] = null to source }
        authority = original.authority; selection = request.evidence; originRequest = request
        expectedPolicyRevision = request.expectedRequesterPolicyRevision; expectedSupplierRevision = request.expectedSupplierRevision
        expectedItems = request.items; expectedWarehouses = request.warehouses; revision = Revision(0); reason = request.reasonCode
        expectedResult = LocalArrivalResultV2(action, request.arrivalId, null, Revision(1), null)
    } else {
        val request = arrivalTransitionV2(evidence.command, evidence.normalized)
        val eventRow = after.getValue(arrivalKeyV2(LocalUnpricedArrivalProfileV2.EVENT, request.eventId))
        val event = eventRow.value.body as ArrivalBodyV2.Event
        check(event.action == action && event.request == evidence.normalized.copyBytes().decodeToString(throwOnInvalidSequence = true))
        val originalRow = reader.historical(event.original, evidence)
        val previous = reader.historical(event.stateBefore, evidence)
        original = originalRow.value.body as ArrivalBodyV2.Original
        val before = previous.value.body as ArrivalBodyV2.State
        check(originalRow.row.key == arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, request.arrivalId) &&
            previous.row.key == arrivalKeyV2(LocalUnpricedArrivalProfileV2.STATE, request.arrivalId) &&
            previous.row.storageGeneration == request.expectedArrivalRevision && before.original == event.original &&
            before.events.size < LocalUnpricedArrivalProfileV2.MAX_EVENTS && previous.value.recordedAt <= evidence.recordedAt)
        val (slices, changed) = arrivalPhysicalSuccessorV2(original, before, request)
        check(event.changedSlice == changed)
        val selectedBefore = before.slices.single { it.custodyUnitId == request.custodyUnitId }
        val line = original.request.lines.single { it.lineId == selectedBefore.lineId }
        check(request.item.skuId == line.skuId && request.warehouse.warehouseId == line.warehouseId)
        newlyQualified = if (selectedBefore.serialId == null && changed.serialId != null) listOf(changed) else emptyList()
        val state = before.copy(slices = slices, events = before.events + eventRow.reference())
        expected[eventRow.row.key] = null to event
        expected[previous.row.key] = previous.reference() to state
        if (request.split != null) { val source = arrivalSourceIdentityV2(request.arrivalId, changed); expected[arrivalBodyKeyV2(source)] = null to source }
        authority = event.authority; selection = request.evidence; selected = listOf(changed); originRequest = null
        expectedPolicyRevision = request.expectedRequesterPolicyRevision; expectedSupplierRevision = request.expectedSupplierRevision
        expectedItems = listOf(request.item); expectedWarehouses = listOf(request.warehouse)
        revision = request.expectedArrivalRevision; reason = request.reasonCode
        expectedResult = LocalArrivalResultV2(action, request.arrivalId, request.eventId, request.expectedArrivalRevision.next(), changed.custodyUnitId)
    }
    requireArrivalPhysicalStateV2(original, (expected.getValue(arrivalKeyV2(LocalUnpricedArrivalProfileV2.STATE, original.request.arrivalId)).second as ArrivalBodyV2.State).slices)
    requireArrivalAuthorityShapeV2(evidence, original, selected, newlyQualified, authority, selection,
        expectedSupplierRevision, expectedItems, expectedWarehouses, expectedPolicyRevision)
    reader.requireStockObservations(evidence, original, selected, authority)
    check(authority.guards.map { it.key() }.distinct().size == authority.guards.size &&
        authority.guards.map { it.key() }.toSet() == arrivalRequiredGuardKeysV2(original.request, selected))
    val guards = linkedMapOf<AggregateKey, ArrivalHistoricalV2?>()
    for (observation in authority.guards) {
        check(observation.before == null || observation.before.key() == observation.key())
        guards[observation.key()] = observation.before?.let { reader.historical(it, evidence) }
    }
    for ((key, entry) in arrivalGuardSuccessorsV2(original, selected, guards)) { check(key !in expected); expected[key] = entry }
    check(after.keys == expected.keys)
    for ((key, entry) in expected) {
        val row = after.getValue(key)
        check(row.value.body == entry.second && row.value.previous == entry.first && row.value.revision == (entry.first?.revision ?: Revision(0)).next())
    }
    val requirement = arrivalRequirementV2(evidence.scope, original.request.arrivalId, revision, evidence.semanticDigest,
        evidence.operationId, reason)
    val documentary = reader.documentaryCodec.retainedEffects(evidence, authority.purchasing, requirement, expectedPolicyRevision)
    check(documentary.evidence == authority.purchasing && documentary.changes.all { it.key.kind in reader.documentaryCodec.documentaryKinds } &&
        documentary.claims.all { it.key.namespace in reader.documentaryCodec.documentaryClaimNamespaces })
    val custodyChanges = expected.values.map { entry -> arrivalChangeV2(evidence.scope, ArrivalProducerV2.from(evidence),
        entry.first, entry.second, evidence.recordedAt, evidence.policyRevision) }
    val readEvidence = arrivalStockReadEvidenceChangesV2(evidence, authority.stockProfile)
    val changes = (custodyChanges + documentary.changes + readEvidence).sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
    val claims = (arrivalClaimsV2(evidence.scope, expected, originRequest) + documentary.claims)
        .sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })
    check(changes.map { it.key }.distinct().size == changes.size && claims.map { it.key }.distinct().size == claims.size)
    check(evidence.aggregateWrites.size == changes.size && evidence.aggregateWrites.zip(changes).all { (actual, change) ->
        actual.key == change.key && actual.storageGeneration == (change.expectedRevision ?: Revision(0)).next() &&
            actual.schema == change.schema && actual.payload == change.payload && actual.payloadDigest == sha256(change.payload.copyBytes())
    })
    check(evidence.claimChanges.size == claims.size && evidence.claimChanges.zip(claims).all { (actual, change) ->
        actual.action == RetainedClaimAction.ACQUIRE && actual.key == change.key && actual.ownerId == change.ownerId
    })
    val deadline = (listOfNotNull(selection.policy.validUntil) + authority.serialRegistrations.mapNotNull { it.validUntil }).minOrNull()
    val encodedResult = arrivalEncode(expectedResult, LocalArrivalResultV2.serializer())
    requireArrivalEffectsCapacityV2(encodedResult, changes, claims)
    val outcome = ProposedOutcome.Apply(encodedResult, changes, claims, mustCommitBefore = deadline)
    check(evidence.result == outcome.result)
    return ArrivalRetainedPlanV2(original, selected, authority, outcome)
}

/** Shape/equality constraints only. Genuine original native BEFORE sources are joined separately. */
internal fun requireArrivalAuthorityShapeV2(evidence: RetainedCommandEvidence, original: ArrivalBodyV2.Original,
    selected: List<ArrivalSliceV1>, newlyQualified: List<ArrivalSliceV1>, value: ArrivalAuthorityWireV2,
    input: LocalArrivalEvidenceSelectionV2, expectedSupplierRevision: Revision,
    expectedItems: List<LocalArrivalItemSelectionV2>, expectedWarehouses: List<LocalArrivalWarehouseSelectionV2>, expectedPolicyRevision: Revision) {
    val action = arrivalActionV2(evidence.command)
    val lines = selected.map { slice -> original.request.lines.single { it.lineId == slice.lineId } }
    val itemIds = lines.map { it.skuId }.toSet(); val warehouseIds = lines.map { it.warehouseId }.toSet()
    check(evidence.policyRevision == expectedPolicyRevision && value.supplier.id == original.request.supplierId &&
        value.supplier.storageRevision == expectedSupplierRevision && value.supplierSource.id == value.supplier.id &&
        value.supplierSource.revision == value.supplier.storageRevision && value.supplierSource in value.supplierSources)
    check(value.items.map { it.selection.skuId }.toSet() == itemIds && value.items.size == itemIds.size &&
        value.warehouses.map { it.selection.warehouseId }.toSet() == warehouseIds && value.warehouses.size == warehouseIds.size)
    for (item in value.items) {
        val originalItem = original.request.items.single { it.skuId == item.selection.skuId }
        check(item.selection == expectedItems.single { it.skuId == item.selection.skuId } &&
            item.selection.productId == originalItem.productId && item.selection.baseUnitId == originalItem.baseUnitId &&
            item.selection.quantityScale == originalItem.quantityScale && item.source.id == item.selection.skuId &&
            item.source.revision == item.selection.storageRevision && item.source in item.sources)
        check(item.tracking == original.authority.items.single { it.selection.skuId == item.selection.skuId }.tracking &&
            (!item.requiresExpiry || item.tracking == LocalArrivalTrackingV1.LOT))
        check(item.active)
    }
    for (warehouse in value.warehouses) {
        check(warehouse.selection == expectedWarehouses.single { it.warehouseId == warehouse.selection.warehouseId } &&
            warehouse.source.id == warehouse.selection.warehouseId && warehouse.source.revision == warehouse.selection.storageRevision && warehouse.source in warehouse.sources)
        check(warehouse.active)
    }
    check(value.supplier.active)
    val purchasing = value.purchasing
    check(purchasing.selection == input && input.policy.scope == evidence.scope && input.catalog.scope == evidence.scope &&
        evidence.recordedAt >= input.policy.validFrom && (input.policy.validUntil?.let { evidence.recordedAt < it } ?: true))
    check(purchasing.products.toSet() == value.items.map { LocalArrivalProductReferenceV2(it.selection.skuId, it.selection.skuRevision,
        it.selection.productId, it.selection.productRevision) }.toSet() && purchasing.products.size == value.items.size)
    check(purchasing.originalDocuments == input.documents && purchasing.originalDocuments.all { it.supplierId == original.request.supplierId })
    for (source in listOf(input.policy.activePointer, input.policy.publication, input.catalog.activePointer, input.catalog.publication))
        check(source in purchasing.sources)
    check(value.serialRegistrations.map { it.serialId }.toSet() == newlyQualified.map { it.serialId }.toSet() && value.serialRegistrations.size == newlyQualified.size)
    for (registration in value.serialRegistrations) {
        val slice = newlyQualified.single { it.serialId == registration.serialId }
        val line = original.request.lines.single { it.lineId == slice.lineId }
        check(registration.scope.restore() == evidence.scope && registration.skuId == line.skuId && registration.serialCode == slice.serialCode &&
            registration.revision == slice.serialRegistrationRevision && registration.sources.isNotEmpty())
        check(registration == StockSerialRegistrationWire.from(registration.restore()))
        requireSerialRegistration(registration.restore(), evidence.scope, registration.serialId, line.skuId, requireNotNull(slice.serialCode), evidence.recordedAt)
    }
    val groups = listOf(value.supplierSources) + value.items.map { it.sources } + value.warehouses.map { it.sources } + value.serialRegistrations.map { it.sources }
    for (sources in groups) {
        check(sources.size in 1..16 && sources.map { it.kind to it.id }.distinct().size == sources.size)
        sources.forEach { it.restore() }
    }
    val all = (groups.flatten().map { LocalArrivalSourceSelectionV2.from(it.restore()) } + purchasing.sources).groupBy { it.kind to it.id }
    check(all.size <= LocalUnpricedArrivalProfileV2.MAX_PURCHASING_SOURCES && all.values.all { it.distinct().size == 1 })
}

/** The whole atomic proposal includes documentary companions and native-owned read chunks. */
internal fun requireArrivalEffectsCapacityV2(result: StoredBytes, changes: List<AggregateChange>, claims: List<ClaimChange>) {
    val framed = 32_768L + result.size + changes.sumOf { it.payload.size.toLong() + 512L } +
        claims.sumOf { 128L + it.key.namespace.length + it.key.key.length }
    arrivalRequireV2(changes.size <= 128 && claims.size <= 128 && framed <= 1_048_576L,
        LocalArrivalFailureV2.CAPACITY_EXCEEDED)
}
