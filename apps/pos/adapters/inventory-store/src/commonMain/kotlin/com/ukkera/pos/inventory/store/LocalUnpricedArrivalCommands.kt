package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Install in the actual master, alongside ordinary receiving, using its SAME stock repository. */
public class LocalUnpricedArrivalServices(stock: LocalStockRepository, authority: LocalUnpricedArrivalAuthority) {
    public val commands: List<MasterCommandHandler> = localStockFrozen(LocalArrivalActionV1.entries.map { ArrivalCommandHandler(it, stock, authority) })
    public val reads: List<MasterReadHandler> = localStockFrozen(arrivalReadHandlers(stock, authority))
}

internal val arrivalBarriers: Set<RecoveryResourceKind> get() = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.PURCHASE_CUSTODY,
    RecoveryResourceKind.PO_ALLOWANCE, RecoveryResourceKind.SUPPLIER_SETTLEMENT, RecoveryResourceKind.AUTHORIZATION)

private class ArrivalCommandHandler(private val action: LocalArrivalActionV1, private val stock: LocalStockRepository,
    private val authority: LocalUnpricedArrivalAuthority) : MasterCommandHandler {
    override val key: CommandKey get() = action.command()
    override val writableKinds: Set<String> get() = arrivalKinds
    override val claimNamespaces: Set<String> get() = arrivalClaimNamespaces
    override fun normalize(input: StoredBytes): StoredBytes = arrivalNormalize(key, input)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return if (action == LocalArrivalActionV1.RECORD) {
            val request = arrivalDecode(normalized, LocalRecordUnpricedArrivalV1.serializer(), 32_768)
            try { record(request, view, context) }
            catch (_: ArrivalHydrationCapacityUnavailable) { capacity(context, request.arrivalId, null, Revision(0), request.reasonCode, arrivalRequestResources(context.scope, request)) }
            catch (_: LocalStockHydrationCapacityUnavailable) { capacity(context, request.arrivalId, null, Revision(0), request.reasonCode, arrivalRequestResources(context.scope, request)) }
        } else {
            val request = arrivalTransition(key, normalized)
            try { transition(request, normalized, view, context) }
            catch (_: ArrivalHydrationCapacityUnavailable) { capacity(context, request.arrivalId, request.eventId, request.expectedArrivalRevision, request.reasonCode, arrivalTransitionResources(request)) }
            catch (_: LocalStockHydrationCapacityUnavailable) { capacity(context, request.arrivalId, request.eventId, request.expectedArrivalRevision, request.reasonCode, arrivalTransitionResources(request)) }
        }
    }

    private fun capacity(context: CommandContext, id: DomainId, eventId: DomainId?, revision: Revision, reason: String,
        resources: Set<RecoveryResourceKey>): PreparedMutation = preparedArrival(context, action, id, eventId, revision, reason, resources) { _, _ ->
        arrivalReject(action, id, eventId, LocalArrivalFailureV1.HYDRATION_CAPACITY_UNAVAILABLE)
    }

    private suspend fun record(request: LocalRecordUnpricedArrivalV1, view: CommandReadView, context: CommandContext): PreparedMutation {
        val policy = authority.policy(view, request.supplierId)
        // There is currently no qualified local evidence-policy source in catalog configuration.
        // Missing policy does NOT mean a photo is optional; nor does it make every receipt require one.
        if (policy == null) return preparedArrival(context, action, request.arrivalId, null, Revision(0), request.reasonCode,
            arrivalRequestResources(context.scope, request)) { _, _ -> arrivalReject(action, request.arrivalId, null, LocalArrivalFailureV1.UNSUPPORTED_POLICY) }
        val reader = stock.unpricedArrivals(view)
        val existing = reader.arrival(request.arrivalId)
        val duplicateDelivery = reader.deliveryUsed(request)
        var reused = false
        for (line in request.lines) if (reader.sourceUsed(line.sourceId, line.custodyUnitId, line.lineId)) reused = true
        val slices = arrivalInitialSlices(request)
        val stockCut = stock.observe(view, request.items.map { it.skuId })
        val sources = loadArrivalAuthority(authority, view, stockCut, request, slices, emptySet(), action, request.evidence, policy)
        val guards = reader.guards(request, slices)
        val beforeGuards = linkedMapOf<AggregateKey, ArrivalHistorical?>()
        for ((key, row) in guards) beforeGuards[key] = row?.let { reader.historical(it) }
        val serialConflict = normalStockSerialConflict(stock, view, slices)
        val resources = arrivalRequestResources(context.scope, request) + reader.resources() + stock.resources(stockCut) + sources.resources
        return preparedArrival(context, action, request.arrivalId, null, Revision(0), request.reasonCode, resources) { auth, now ->
            arrivalRequire(existing == null && !duplicateDelivery && !reused, LocalArrivalFailureV1.SOURCE_ALREADY_USED)
            arrivalRequire(!serialConflict, LocalArrivalFailureV1.SOURCE_ALREADY_USED)
            val facts = sources.complete(now, record = true, original = null).copy(guards = guardObservations(guards))
            val original = ArrivalBodyV1.Original(request, request.supplierDeliveryReference?.let(::normalizedPurchaseReference), facts)
            requireArrivalPhysicalState(original, slices)
            requireCurrentStockLotFacts(stockCut, request, slices)
            val guardWrites = arrivalGuardSuccessors(original, slices, beforeGuards)
            val originalChange = arrivalChange(context, null, original, now, auth.policyRevision)
            val state = ArrivalBodyV1.State(request.arrivalId, arrivalReference(originalChange, context.operationId), slices, emptyList())
            val bodies = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>>(
                originalChange.key to (null to original), arrivalBodyKey(state) to (null to state))
            slices.forEach { slice -> val identity = arrivalSourceIdentity(request.arrivalId, slice); bodies[arrivalBodyKey(identity)] = null to identity }
            bodies.putAll(guardWrites)
            completeArrival(context, bodies, request, LocalArrivalResultV1(action, request.arrivalId, null, Revision(1), null), now, auth.policyRevision, sources.deadline)
        }
    }

    private suspend fun transition(request: ArrivalTransitionInput, normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val reader = stock.unpricedArrivals(view)
        val cut = reader.arrival(request.arrivalId)
        if (cut == null) return preparedArrival(context, action, request.arrivalId, request.eventId, request.expectedArrivalRevision,
            request.reasonCode, arrivalTransitionResources(request) + reader.resources()) { _, _ ->
            arrivalReject(action, request.arrivalId, request.eventId, LocalArrivalFailureV1.SOURCE_MISSING) }
        if (cut.state.row.revision != request.expectedArrivalRevision) return preparedArrival(context, action, request.arrivalId,
            request.eventId, request.expectedArrivalRevision, request.reasonCode,
            arrivalTransitionResources(request) + arrivalRequestResources(context.scope, cut.request) + reader.resources()) { _, _ ->
            arrivalReject(action, request.arrivalId, request.eventId, LocalArrivalFailureV1.STALE_REVISION) }
        val policy = authority.policy(view, cut.request.supplierId)
        if (policy == null) return preparedArrival(context, action, request.arrivalId, request.eventId, request.expectedArrivalRevision,
            request.reasonCode, arrivalTransitionResources(request) + reader.resources() + arrivalRequestResources(context.scope, cut.request)) { _, _ ->
            arrivalReject(action, request.arrivalId, request.eventId, LocalArrivalFailureV1.UNSUPPORTED_POLICY) }
        val original = cut.original.value.body as ArrivalBodyV1.Original
        val existingEvent = reader.row(arrivalKey(LocalUnpricedArrivalProfileV1.EVENT, request.eventId))
        val selected = cut.current.slices.singleOrNull { it.custodyUnitId == request.custodyUnitId }
        // Selection errors are evaluated after exact authorization, never leaked during prepare.
        val successor = try { arrivalPhysicalSuccessor(original, cut.current, request) }
            catch (failure: ArrivalRejected) { return preparedArrival(context, action, request.arrivalId, request.eventId,
                request.expectedArrivalRevision, request.reasonCode, arrivalTransitionResources(request) + arrivalRequestResources(context.scope, cut.request) + reader.resources()) { _, _ ->
                arrivalReject(action, request.arrivalId, request.eventId, failure.failure) } }
        check(selected != null)
        val changed = successor.second
        val line = cut.request.lines.single { it.lineId == selected.lineId }
        val stockCut = stock.observe(view, listOf(line.skuId))
        val sources = loadArrivalAuthority(authority, view, stockCut, cut.request, listOf(changed), setOfNotNull(selected.serialId), action, request.evidence, policy)
        val guards = reader.guards(cut.request, listOf(changed))
        val beforeGuards = linkedMapOf<AggregateKey, ArrivalHistorical?>()
        for ((key, row) in guards) beforeGuards[key] = row?.let { reader.historical(it) }
        val reusedSplit = request.split?.let { reader.sourceUsed(it.sourceId, it.custodyUnitId, null) } ?: false
        val serialConflict = normalStockSerialConflict(stock, view, listOf(changed))
        val resources = arrivalTransitionResources(request) + arrivalRequestResources(context.scope, cut.request) + reader.resources() + stock.resources(stockCut) + sources.resources
        return preparedArrival(context, action, request.arrivalId, request.eventId, request.expectedArrivalRevision, request.reasonCode, resources) { auth, now ->
            arrivalRequire(cut.state.row.revision == request.expectedArrivalRevision, LocalArrivalFailureV1.STALE_REVISION)
            arrivalRequire(existingEvent == null && !reusedSplit && !serialConflict, LocalArrivalFailureV1.SOURCE_ALREADY_USED)
            arrivalRequire(cut.current.events.size < LocalUnpricedArrivalProfileV1.MAX_EVENTS, LocalArrivalFailureV1.CAPACITY_EXCEEDED)
            check(cut.state.value.recordedAt <= now)
            val facts = sources.complete(now, record = false, original = original).copy(guards = guardObservations(guards))
            requireCurrentStockLotFacts(stockCut, cut.request, listOf(changed))
            val guardWrites = arrivalGuardSuccessors(original, listOf(changed), beforeGuards)
            val event = ArrivalBodyV1.Event(action, normalized.copyBytes().decodeToString(throwOnInvalidSequence = true),
                cut.original.reference(), cut.state.reference(), facts, changed)
            val eventChange = arrivalChange(context, null, event, now, auth.policyRevision)
            val state = cut.current.copy(slices = successor.first, events = cut.current.events + arrivalReference(eventChange, context.operationId))
            val bodies = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>>(
                eventChange.key to (null to event), cut.state.row.key to (cut.state.reference() to state))
            if (request.split != null) { val identity = arrivalSourceIdentity(request.arrivalId, changed); bodies[arrivalBodyKey(identity)] = null to identity }
            bodies.putAll(guardWrites)
            completeArrival(context, bodies, null, LocalArrivalResultV1(action, request.arrivalId, request.eventId,
                request.expectedArrivalRevision.next(), changed.custodyUnitId), now, auth.policyRevision, sources.deadline)
        }
    }
}

private fun preparedArrival(context: CommandContext, action: LocalArrivalActionV1, arrivalId: DomainId, eventId: DomainId?, revision: Revision,
    reason: String, resources: Set<RecoveryResourceKey>, block: (AuthorizedAction, UtcInstant) -> ProposedOutcome): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
        context.scope, ActionBinding(arrivalId, revision, context.semanticDigest, context.operationId, reason))
    private val resources = (resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)).toSet()
    override val affectedResources: Set<RecoveryResourceKey> get() = resources.toSet()
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() = arrivalBarriers
    // Retained physical producer records identify the actual direct employee. No inferred approver provenance.
    override val allowsActionApproval: Boolean get() = false
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
        return try { block(authorization, recordedAt) }
        catch (failure: ArrivalRejected) { arrivalReject(action, arrivalId, eventId, failure.failure) }
    }
}

private fun arrivalReject(action: LocalArrivalActionV1, id: DomainId, eventId: DomainId?, failure: LocalArrivalFailureV1): ProposedOutcome.Reject =
    ProposedOutcome.Reject("arrival.${failure.name.lowercase()}", arrivalEncode(LocalArrivalResultV1(action, id, eventId, null, null, failure), LocalArrivalResultV1.serializer()))

private fun completeArrival(context: CommandContext, bodies: Map<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>>,
    original: LocalRecordUnpricedArrivalV1?, result: LocalArrivalResultV1, now: UtcInstant, policyRevision: Revision, deadline: UtcInstant?): ProposedOutcome.Apply {
    val changes = bodies.entries.sortedWith(compareBy<Map.Entry<AggregateKey, Pair<ArrivalRowRefV1?, ArrivalBodyV1>>> { it.key.kind }.thenBy { it.key.id.value })
        .map { (_, entry) -> arrivalChange(context, entry.first, entry.second, now, policyRevision) }
    val claims = arrivalClaims(context.scope, bodies, original).sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })
    val encoded = arrivalEncode(result, LocalArrivalResultV1.serializer())
    val framed = 32_768L + encoded.size + changes.sumOf { it.payload.size.toLong() + 512L } + claims.sumOf { 128L + it.key.namespace.length + it.key.key.length }
    arrivalRequire(changes.size <= 128 && claims.size <= 128 && framed <= 1_048_576L, LocalArrivalFailureV1.CAPACITY_EXCEEDED)
    return ProposedOutcome.Apply(encoded, changes, claims, mustCommitBefore = deadline)
}

private fun guardObservations(values: Map<AggregateKey, ArrivalRow?>): List<ArrivalGuardObservationV1> =
    values.map { (key, row) -> ArrivalGuardObservationV1(key.kind, key.id, row?.reference()) }.sortedWith(compareBy<ArrivalGuardObservationV1> { it.kind }.thenBy { it.id.value })

private suspend fun normalStockSerialConflict(stock: LocalStockRepository, view: CommandReadView, slices: List<ArrivalSliceV1>): Boolean {
    var conflict = false
    for (slice in slices) {
        slice.serialId?.let { if (stockSerial(stock, view, it) != null) conflict = true }
        slice.serialCode?.let { code -> view.claim(LocalStockStoreProfile.serialCodeClaim(code))?.let { id ->
            val origin = stockSerial(stock, view, id) ?: error("Owned serial claim lost its origin")
            check(origin.value.serialCode == code)
            conflict = true
        } }
    }
    return conflict
}

private fun requireCurrentStockLotFacts(cut: LocalStockReadCut, request: LocalRecordUnpricedArrivalV1, slices: List<ArrivalSliceV1>) {
    for (slice in slices) {
        val sku = request.lines.single { it.lineId == slice.lineId }.skuId
        val item = cut.state.items[sku]
        arrivalRequire(item == null || item.value.unitId == slice.quantity.unitId && item.value.quantityScale == slice.quantity.scale, LocalArrivalFailureV1.INVALID_UNIT)
        if (slice.lotId != null && slice.expiresAt != null) arrivalRequire(cut.snapshot.positions.filter {
            it.key.pool.skuId == sku && it.key.lotId == slice.lotId }.all { it.expiresAtEpochMillis == slice.expiresAt.value.toEpochMilliseconds() }, LocalArrivalFailureV1.INVALID_LOT)
    }
}

private class ArrivalPreparedAuthority(
    val scope: BranchScope, val request: LocalRecordUnpricedArrivalV1, val selected: List<ArrivalSliceV1>, val action: LocalArrivalActionV1,
    val evidence: LocalArrivalEvidenceSelectionV1, val supplier: LocalArrivalSupplierAuthority?,
    val items: Map<DomainId, LocalArrivalItemAuthority?>, val warehouses: Map<DomainId, LocalArrivalWarehouseAuthority?>,
    val registrations: Map<DomainId, LocalStockSerialRegistration?>, val policy: LocalArrivalPolicyAuthority,
    val attachment: LocalArrivalAttachmentAuthority?, val stockCut: LocalStockReadCut, val sources: List<LocalStockSourceStamp>,
) {
    val deadline: UtcInstant? get() = (listOfNotNull(policy.value.validUntil) + registrations.values.mapNotNull { it?.validUntil }).minOrNull()
    val resources: Set<RecoveryResourceKey> get() = sourceResources(sources) + buildSet {
        add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, policy.value.policyId))
        registrations.values.filterNotNull().forEach { addAll(serialRegistrationResources(it)) }
        attachment?.let { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.attachmentId)) }
    }

    fun complete(now: UtcInstant, record: Boolean, original: ArrivalBodyV1.Original?): ArrivalAuthorityWireV1 {
        val supplier = supplier ?: throw ArrivalRejected(LocalArrivalFailureV1.SUPPLIER_UNAVAILABLE)
        arrivalRequire(!record || supplier.value.active, LocalArrivalFailureV1.SUPPLIER_UNAVAILABLE)
        arrivalRequire(!record || supplier.value.storageRevision == request.expectedSupplierRevision, LocalArrivalFailureV1.STALE_REVISION)
        arrivalRequire(evidence.policyId == policy.value.policyId && evidence.policyRevision == policy.value.revision, LocalArrivalFailureV1.STALE_REVISION)
        arrivalRequire(now >= policy.value.validFrom && (policy.value.validUntil?.let { now < it } ?: true), LocalArrivalFailureV1.UNSUPPORTED_POLICY)
        arrivalRequire(action !in policy.value.attachmentRequiredFor || attachment != null, LocalArrivalFailureV1.EVIDENCE_REQUIRED)
        arrivalRequire(evidence.attachmentId == null || attachment != null, LocalArrivalFailureV1.EVIDENCE_UNAVAILABLE)
        attachment?.let { arrivalRequire(it.source.storageRevision == evidence.attachmentStorageRevision, LocalArrivalFailureV1.STALE_REVISION) }
        val itemFacts = items.entries.sortedBy { it.key.value }.map { (id, source) ->
            val actual = source ?: throw ArrivalRejected(LocalArrivalFailureV1.ITEM_UNAVAILABLE)
            val item = actual.value
            val expected = request.items.single { it.skuId == id }
            val tracking = LocalArrivalTrackingV1.entries.singleOrNull { it.name == item.tracking.name }
                ?: throw ArrivalRejected(LocalArrivalFailureV1.INVALID_TRACKING)
            check(!item.requiresExpiry || tracking == LocalArrivalTrackingV1.LOT)
            arrivalRequire(!record || item.active, LocalArrivalFailureV1.ITEM_UNAVAILABLE)
            arrivalRequire(item.productId == expected.productId && item.baseUnitId == expected.baseUnitId && item.quantityScale == expected.quantityScale, LocalArrivalFailureV1.INVALID_UNIT)
            val priorStock = stockCut.state.items.getValue(id)
            arrivalRequire(priorStock == null || priorStock.value.positions.all { (it.serialId != null) == (tracking == LocalArrivalTrackingV1.SERIAL) },
                LocalArrivalFailureV1.INVALID_TRACKING)
            val selection = LocalArrivalItemSelectionV1(id, item.productId, item.baseUnitId, item.quantityScale, item.catalogRevision, item.skuRevision,
                actual.source.storageRevision, stockCut.state.items.getValue(id)?.row?.revision)
            arrivalRequire(!record || selection == expected, LocalArrivalFailureV1.STALE_REVISION)
            if (original != null) arrivalRequire(original.authority.items.single { it.selection.skuId == id }.tracking == tracking, LocalArrivalFailureV1.INVALID_TRACKING)
            ArrivalItemAuthorityWireV1(selection, tracking, item.requiresExpiry, item.active, item.canPurchase,
                StockSourceStampWire.from(actual.source), item.sources.map(StockSourceStampWire::from))
        }
        val warehouseFacts = warehouses.entries.sortedBy { it.key.value }.map { (id, source) ->
            val actual = source ?: throw ArrivalRejected(LocalArrivalFailureV1.WAREHOUSE_UNAVAILABLE)
            arrivalRequire(!record || actual.value.active, LocalArrivalFailureV1.WAREHOUSE_UNAVAILABLE)
            val selection = LocalArrivalWarehouseSelectionV1(id, actual.source.storageRevision)
            arrivalRequire(!record || selection == request.warehouses.single { it.warehouseId == id }, LocalArrivalFailureV1.STALE_REVISION)
            ArrivalWarehouseAuthorityWireV1(selection, actual.value.active, StockSourceStampWire.from(actual.source), actual.value.sources.map(StockSourceStampWire::from))
        }
        val serialFacts = registrations.entries.sortedBy { it.key.value }.map { (id, registration) ->
            val actual = registration ?: throw ArrivalRejected(LocalArrivalFailureV1.SERIAL_REGISTRATION_REQUIRED)
            val slice = selected.single { it.serialId == id }
            val line = request.lines.single { it.lineId == slice.lineId }
            arrivalRequire(actual.skuId == line.skuId && actual.serialCode == slice.serialCode, LocalArrivalFailureV1.INVALID_SERIAL)
            arrivalRequire(actual.revision == slice.serialRegistrationRevision, LocalArrivalFailureV1.STALE_REVISION)
            arrivalRequire(now >= actual.validFrom && (actual.validUntil?.let { now < it } ?: true), LocalArrivalFailureV1.SERIAL_REGISTRATION_REQUIRED)
            StockSerialRegistrationWire.from(actual)
        }
        return ArrivalAuthorityWireV1(supplier.value, StockSourceStampWire.from(supplier.source), supplier.sources.map(StockSourceStampWire::from), itemFacts, warehouseFacts, serialFacts,
            ArrivalPolicyWireV1(request.supplierId, policy.value, StockSourceStampWire.from(policy.source), policy.sources.map(StockSourceStampWire::from)), attachment?.let {
                ArrivalAttachmentWireV1(it.supplierId, it.action, it.attachmentId, StockSourceStampWire.from(it.source), it.sources.map(StockSourceStampWire::from)) },
            arrivalStockObservations(stockCut, request, selected), items.keys.filter { stockCut.state.items.getValue(it) == null }.sortedBy { it.value }, emptyList())
    }
}

private suspend fun loadArrivalAuthority(authority: LocalUnpricedArrivalAuthority, view: CommandReadView, stockCut: LocalStockReadCut,
    request: LocalRecordUnpricedArrivalV1, slices: List<ArrivalSliceV1>, previouslyQualifiedSerialIds: Set<DomainId>,
    action: LocalArrivalActionV1, evidence: LocalArrivalEvidenceSelectionV1,
    policy: LocalArrivalPolicyAuthority): ArrivalPreparedAuthority {
    check(policy.scope == view.scope && policy.supplierId == request.supplierId && policy.sources.isNotEmpty())
    val supplier = authority.supplier(view, request.supplierId)
    check(supplier == null || supplier.scope == view.scope && supplier.value.id == request.supplierId && supplier.sources.isNotEmpty())
    val lines = slices.map { request.lines.single { line -> line.lineId == it.lineId } }
    val items = lines.map { it.skuId }.distinct().associateWith { id -> authority.item(view, id).also { source ->
        check(source == null || source.value.scope == view.scope && source.value.skuId == id && source.source.key.id == id && source.source in source.value.sources) } }
    val warehouses = lines.map { it.warehouseId }.distinct().associateWith { id -> authority.warehouse(view, id).also { source ->
        check(source == null || source.value.scope == view.scope && source.value.warehouseId == id && source.source.key.id == id && source.source in source.value.sources) } }
    // A retained exact qualification does not expire the physical object's identity. Only a
    // newly supplied qualified ID needs current authority; later rejection/departure preserves it.
    val registrations = slices.mapNotNull { it.serialId }.filter { it !in previouslyQualifiedSerialIds }.distinct().associateWith { id -> authority.serialRegistration(view, id).also { source ->
        check(source == null || source.scope == view.scope && source.serialId == id) } }
    val attachment = evidence.attachmentId?.let { id -> authority.attachment(view, id, request.supplierId, action).also { source ->
        check(source == null || source.scope == view.scope && source.supplierId == request.supplierId && source.action == action &&
            source.attachmentId == id && source.source in source.sources) } }
    val rawStamps = supplier?.sources.orEmpty() + policy.sources + items.values.filterNotNull().flatMap { it.value.sources } +
        warehouses.values.filterNotNull().flatMap { it.value.sources } + registrations.values.filterNotNull().flatMap { it.sources } + attachment?.sources.orEmpty()
    val groups = rawStamps.groupBy { it.key }
    check(groups.values.all { it.distinct().size == 1 } && rawStamps.all { it.storageRevision.value >= 1 })
    if (groups.size > 128) throw ArrivalHydrationCapacityUnavailable()
    val stamps = requireStockAuthorityStamps(view, rawStamps)
    return ArrivalPreparedAuthority(view.scope, request, slices, action, evidence, supplier, items, warehouses, registrations, policy, attachment, stockCut, stamps)
}

internal fun arrivalRequestResources(scope: BranchScope, request: LocalRecordUnpricedArrivalV1): Set<RecoveryResourceKey> = buildSet {
    val delivery = arrivalDeliveryClaim(scope, request)
    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, localStockId("arrival-delivery-resource", "${delivery.namespace}/${delivery.key}")))
    listOfNotNull(request.arrivalId, request.supplierId, request.localIntakeTag, request.evidence.attachmentId).forEach {
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
    add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.supplierId))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.supplierId))
    request.evidence.policyId?.let { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)) }
    request.documentaryPoId?.let { add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it)) }
    for (line in request.lines) {
        listOf(line.lineId, line.sourceId, line.custodyUnitId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
        listOfNotNull(line.skuId, line.warehouseId, line.lotId, line.serialId, line.serialCode?.let { localStockId("serial-code", it) }).forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
        listOf(line.skuId, line.warehouseId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)) }
    }
}
private fun arrivalTransitionResources(request: ArrivalTransitionInput): Set<RecoveryResourceKey> = buildSet {
    listOfNotNull(request.arrivalId, request.eventId, request.custodyUnitId, request.split?.sourceId, request.split?.custodyUnitId,
        request.evidence.attachmentId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
    listOfNotNull(request.lotId, request.serialId, request.serialCode?.let { localStockId("serial-code", it) }).forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
    request.evidence.policyId?.let { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)) }
    request.recipientSupplierId?.let { add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, it)) }
}
internal fun arrivalAuthorityResources(authority: ArrivalAuthorityWireV1): Set<RecoveryResourceKey> = buildSet {
    val sources = authority.supplierSources + authority.items.flatMap { it.sources } + authority.warehouses.flatMap { it.sources } +
        authority.serialRegistrations.flatMap { it.sources } + authority.policy.sources + authority.attachment?.sources.orEmpty()
    addAll(sourceResources(sources.map { it.restore() }))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, authority.policy.value.policyId))
    authority.attachment?.let { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.attachmentId)) }
    authority.serialRegistrations.forEach { addAll(serialRegistrationResources(it.restore())) }
}
