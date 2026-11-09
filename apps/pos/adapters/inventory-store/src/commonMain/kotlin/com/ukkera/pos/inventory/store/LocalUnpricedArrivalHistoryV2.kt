package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Actual core-selected BEFORE projections, not current sessions or restored permissions. */
public class LocalArrivalHistoricalSourcesV2(
    public val supplier: LocalArrivalSupplierAuthorityV2,
    public val items: List<LocalArrivalItemAuthorityV2>,
    public val warehouses: List<LocalArrivalWarehouseAuthority>,
    public val serialRegistrations: List<LocalStockSerialRegistration>,
    public val resources: Set<RecoveryResourceKey>,
)

/** Native original-source admission belongs to the installed catalog integration, not the
 * inert retained-effect codec. This interface cannot fabricate an AuthorizedAction. */
public interface LocalUnpricedArrivalOriginalSourcesV2 {
    /** Exact physical source facts selected by the original command, before documentary
     * qualification. Null is unavailable provenance, not a durable source-missing refusal. */
    public suspend fun physicalBefore(command: RuntimeHistoricalCommand, supplierId: DomainId,
        skuIds: List<DomainId>, warehouseIds: List<DomainId>, serialIds: List<DomainId>): LocalArrivalHistoricalSourcesV2?
    /** Data-only original rule/approval-coordinate comparison. No new employee grant, current session,
     * approval, custody row or documentary-use effect is issued during recovery. */
    public suspend fun evidenceBefore(command: RuntimeHistoricalCommand, action: LocalArrivalActionV1,
        selection: LocalArrivalEvidenceSelectionV2, expectedRequesterPolicyRevision: Revision,
        supplier: LocalArrivalSupplierAuthorityV2, items: List<LocalArrivalItemAuthorityV2>): LocalArrivalHistoricalEvidenceV2?
    public suspend fun before(command: RuntimeHistoricalCommand, supplierId: DomainId,
        skuIds: List<DomainId>, warehouseIds: List<DomainId>, serialIds: List<DomainId>,
        purchasing: LocalArrivalPurchasingEvidenceV2): LocalArrivalHistoricalSourcesV2?
}

public class LocalArrivalHistoricalEvidenceV2(public val failure: LocalArrivalFailureV2?,
    public val sources: List<LocalStockSourceStamp>, public val resources: Set<RecoveryResourceKey>,
    public val mustCommitBefore: UtcInstant?, public val effects: LocalArrivalDocumentaryEffectsV2? = null)

/** Exact source closure for an already reconstructed custody original. These facts
 * come from the active native BEFORE cut and earlier fully joined originals, not
 * from the retained authority DTO, current authentication or a recreated read view. */
internal suspend fun requireArrivalOriginalSourcesV2(
    command: RuntimeHistoricalCommand,
    sources: LocalUnpricedArrivalOriginalSourcesV2,
    plan: ArrivalRetainedPlanV2,
): Set<RecoveryResourceKey> {
    val original = plan.original
    val retained = plan.authority
    val actual = checkNotNull(sources.before(command, original.request.supplierId,
        retained.items.map { it.selection.skuId }, retained.warehouses.map { it.selection.warehouseId },
        retained.serialRegistrations.map { it.serialId }, retained.purchasing)) {
        "Original unpriced arrival sources unavailable"
    }
    check(actual.supplier.scope == command.scope && actual.supplier.value == retained.supplier &&
        StockSourceStampWire.from(actual.supplier.source) == retained.supplierSource &&
        actual.supplier.sources.map(StockSourceStampWire::from) == retained.supplierSources)
    check(actual.items.size == retained.items.size && actual.warehouses.size == retained.warehouses.size &&
        actual.serialRegistrations.size == retained.serialRegistrations.size)
    for ((item, expected) in actual.items.zip(retained.items)) {
        val value = item.value
        val selection = expected.selection
        check(value.scope == command.scope && value.skuId == selection.skuId &&
            value.productId == selection.productId && item.productRevision == selection.productRevision &&
            value.baseUnitId == selection.baseUnitId && value.quantityScale == selection.quantityScale &&
            value.catalogRevision == selection.catalogRevision && value.skuRevision == selection.skuRevision &&
            item.source.storageRevision == selection.storageRevision &&
            value.tracking.name == expected.tracking.name && value.requiresExpiry == expected.requiresExpiry &&
            value.active == expected.active && value.canPurchase == expected.canPurchase &&
            StockSourceStampWire.from(item.source) == expected.source &&
            value.sources.map(StockSourceStampWire::from) == expected.sources)
    }
    for ((warehouse, expected) in actual.warehouses.zip(retained.warehouses)) {
        check(warehouse.value.scope == command.scope && warehouse.value.warehouseId == expected.selection.warehouseId &&
            warehouse.source.storageRevision == expected.selection.storageRevision &&
            warehouse.value.active == expected.active && StockSourceStampWire.from(warehouse.source) == expected.source &&
            warehouse.value.sources.map(StockSourceStampWire::from) == expected.sources)
    }
    check(actual.serialRegistrations.map(StockSerialRegistrationWire::from) == retained.serialRegistrations)
    val stamps = actual.supplier.sources + actual.items.flatMap { it.value.sources } +
        actual.warehouses.flatMap { it.value.sources } + actual.serialRegistrations.flatMap { it.sources } +
        retained.purchasing.sources.map { it.stamp() }
    check(stamps.groupBy { it.key }.values.all { it.distinct().size == 1 })
    for (stamp in stamps.distinct()) {
        val source = checkNotNull(command.aggregateSource(stamp.key)) { "Original arrival source missing" }
        check(source.row.key == stamp.key && source.row.revision == stamp.storageRevision && source.row.digest == stamp.digest)
    }
    return actual.resources
}

/** Producer access confined to the one active native historical command. Earlier
 * sources have already completed the installed join. No live repository reader or
 * current grant is introduced while reconstructing immutable custody effects. */
internal class ArrivalNativeHistoryAccessV2(
    private val stock: LocalStockRepository,
    private val command: RuntimeHistoricalCommand,
    override val documentaryCodec: LocalUnpricedArrivalDocumentaryCodecV2,
) : ArrivalProducerAccessV2 {
    override val scope: BranchScope get() = command.scope
    private var stockCut: ArrivalStockCutV2? = null

    override suspend fun historical(reference: ArrivalRowRefV2, child: RetainedCommandEvidence?): ArrivalHistoricalV2 {
        check(reference.kind in arrivalKindsV2 && reference.revision.value >= 1)
        check(child == null || child.operationId == command.operationId)
        val original = (command.earlierOriginal(reference.operationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("Original arrival predecessor unavailable")
        check(original.committed && original.scope == scope && original.command in arrivalCommandsV2 &&
            original.operationId == reference.operationId && original.operationId != command.operationId &&
            original.recordedAt <= command.recordedAt)
        val row = original.aggregateWrites.singleOrNull { it.key == reference.key() }
            ?: error("Original arrival predecessor row unavailable")
        check(row.storageGeneration == reference.revision && row.payloadDigest.value == reference.digest &&
            row.schema == LocalUnpricedArrivalProfileV2.schema(reference.kind) && sha256(row.payload.copyBytes()) == row.payloadDigest)
        val value = arrivalDecodeRecordV2(row.payload)
        check(value.scope == scope && value.kind == reference.kind && value.id == reference.id &&
            value.revision == reference.revision && value.producer == ArrivalProducerV2.from(original))
        // References consumed by this command are its actual BEFORE original/state/guard,
        // not a favorable older generation of a now-changed row.
        val current = checkNotNull(command.aggregateSource(reference.key())) { "Original arrival BEFORE row unavailable" }
        check(current.row.key == row.key && current.row.revision == row.storageGeneration &&
            current.row.schema == row.schema && current.row.payload == row.payload && current.row.digest == row.payloadDigest &&
            (current.original as? RetainedOriginalV2.Legacy)?.evidence?.operationId == original.operationId)
        return ArrivalHistoricalV2(row, value)
    }

    override suspend fun requireStockObservations(child: RetainedCommandEvidence, original: ArrivalBodyV2.Original,
        selected: List<ArrivalSliceV1>, authority: ArrivalAuthorityWireV2) {
        check(child.operationId == command.operationId && stockCut == null)
        command.requireLegacyReadEvidence()
        stockCut = requireArrivalHistoricalStockV2(stock, command, original, selected, authority)
    }

    fun stockResources(): Set<RecoveryResourceKey> = arrivalStockResourcesV2(checkNotNull(stockCut))
}

/** Complete command decision from the original native BEFORE cut. In particular a
 * refusal has no favorable AFTER authority to read: identity, physical interlocks, source
 * revisions, policy, original files and prospective capacity are independently compared. */
public class LocalUnpricedArrivalHistoryProducerV2(
    private val stock: LocalStockRepository,
    private val sources: LocalUnpricedArrivalOriginalSourcesV2,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> get() = arrivalCommandsV2.toSet()
    override fun actionApprovalBusinessAttribution(command: CommandKey): ActionApprovalBusinessAttributionSupport =
        if (command in commands) ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER
        else ActionApprovalBusinessAttributionSupport.UNSUPPORTED

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        check(retained.command == command.command && retained.scope == command.scope &&
            retained.operationId == command.operationId && retained.nativeOriginalProvenance == null && retained.jobs.isEmpty())
        check(arrivalNormalizeV2(command.command, command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val action = arrivalActionV2(command.command)
        val request = if (action == LocalArrivalActionV1.RECORD)
            arrivalDecode(command.normalized, LocalRecordUnpricedArrivalV2.serializer(), 32_768) else null
        val transition = if (request == null) arrivalTransitionV2(command.command, command.normalized) else null
        val id = request?.arrivalId ?: checkNotNull(transition).arrivalId
        val eventId = transition?.eventId
        val revision = transition?.expectedArrivalRevision ?: Revision(0)
        val reason = request?.reasonCode ?: checkNotNull(transition).reasonCode
        val expectedPolicy = request?.expectedRequesterPolicyRevision ?: checkNotNull(transition).expectedRequesterPolicyRevision
        val requirement = arrivalRequirementV2(command.scope, id, revision, command.semanticDigest, command.operationId, reason)
        command.requireRequirement(requirement)
        val decision = (if (request != null) recordBefore(command, request) else transitionBefore(command, checkNotNull(transition)))
            ?: return false
        val resources = decision.resources + command.originalApproval?.let { approval ->
            setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, approval.approverId),
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, approval.approvalId))
        }.orEmpty()
        // The production wrapper compares the current original requester revision even
        // when preparation has already selected a different deterministic refusal.
        val failure = if (command.policyRevision != expectedPolicy) LocalArrivalFailureV2.STALE_REQUESTER_POLICY else decision.failure
        if (failure != null) {
            if (retained.committed) return false
            val rejected = arrivalRejectV2(action, id, eventId, failure)
            if (command.originalApproval != null) command.requireApprovedLegacyRejection(rejected, resources)
            else command.requireUnpricedArrivalRejection(rejected, resources)
            return true
        }
        if (!retained.committed) return false
        val expected = checkNotNull(decision.outcome)
        val codec = checkNotNull(stock.documentaryCodecV2)
        val access = ArrivalNativeHistoryAccessV2(stock, command, codec)
        val plan = arrivalRetainedPlanV2(access, retained)
        check(requireArrivalOriginalSourcesV2(command, sources, plan).all { it in resources })
        check(plan.outcome.result == expected.result && plan.outcome.changes.size == expected.changes.size && plan.outcome.changes.zip(expected.changes).all { (a, b) ->
                a.key == b.key && a.expectedRevision == b.expectedRevision && a.schema == b.schema && a.payload == b.payload } &&
            plan.outcome.claims == expected.claims && plan.outcome.mustCommitBefore == expected.mustCommitBefore)
        command.requireLegacyEffects(expected, resources)
        return true
    }

    private fun decision(command: RuntimeHistoricalCommand, base: Set<RecoveryResourceKey>, reader: ArrivalReaderV2,
        extras: Set<RecoveryResourceKey>, failure: LocalArrivalFailureV2): ArrivalOriginalDecisionV2 =
        ArrivalOriginalDecisionV2(failure, base + reader.resources() + extras + wrapperResources(command), null)

    private suspend fun recordBefore(command: RuntimeHistoricalCommand,
        request: LocalRecordUnpricedArrivalV2): ArrivalOriginalDecisionV2? {
        val reader = ArrivalReaderV2(stock, command)
        val base = arrivalRequestResourcesV2(command.scope, request)
        fun refuse(failure: LocalArrivalFailureV2, extras: Set<RecoveryResourceKey> = emptySet()) =
            decision(command, base, reader, extras, failure)
        if (reader.arrival(request.arrivalId) != null || reader.foreignGuardConflict()) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        if (reader.deliveryUsed(request)) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        for (line in request.lines) if (reader.sourceUsed(line.sourceId, line.custodyUnitId, line.lineId))
            return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        val ordinary = observeArrivalOrdinaryReceiptsBeforeV2(command, request.lines.map { ArrivalOrdinaryReceiptIdentityV2(it.sourceId, it.lineId) })
        if (ordinary.used) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, ordinary.resources)
        val normal = observeArrivalNormalSourcesBeforeV2(command, request.lines.map { it.sourceId })
        val interlocks = ordinary.resources + normal.resources
        if (normal.used) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        val selected = arrivalInitialSlicesV2(request)
        val physical = try { physicalSourcesBefore(command, request, selected, emptySet(), request.items,
            request.warehouses, request.expectedSupplierRevision) }
        catch (failure: ArrivalRejectedV2) { return refuse(failure.failure, interlocks) }
            ?: return null
        val sourceResources = physical.resources(request.evidence)
        physical.primaryFailure?.let { return refuse(it, interlocks + sourceResources) }
        try { requireArrivalInitialPhysicalV2(request, physical.actualItems) }
        catch (failure: ArrivalRejectedV2) { return refuse(failure.failure, interlocks + sourceResources) }
        val guards = reader.guards(request, selected)
        if (reader.foreignGuardConflict()) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks + sourceResources)
        val serials = observeArrivalNormalSerialsBeforeV2(command, selected)
        val preStock = interlocks + sourceResources + serials.resources
        if (serials.used) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, preStock)
        val beforeGuards = linkedMapOf<AggregateKey, ArrivalHistoricalV2?>()
        for ((key, row) in guards) beforeGuards[key] = row?.let { reader.historical(it) }
        val stockCut = observeArrivalStockBeforeV2(command, request.lines, selected)
        val stockResources = arrivalStockResourcesV2(stockCut)
        val facts = try { physical.wire(stockCut, request, selected, null) }
        catch (failure: ArrivalRejectedV2) { return refuse(failure.failure, preStock + stockResources) }
        val evidence = try { sources.evidenceBefore(command, LocalArrivalActionV1.RECORD, request.evidence,
            request.expectedRequesterPolicyRevision, physical.actualSupplier, physical.actualItems) }
        catch (failure: LocalArrivalEvidenceRejectedV2) { return refuse(failure.failure, preStock + stockResources) }
            ?: return null
        if (evidence.sources.isEmpty()) return refuse(checkNotNull(evidence.failure), preStock + stockResources)
        val resources = arrivalMutationResourcesV2(command.actorId, command.scope, request, null, null, reader.resources(),
            stockResources + interlocks + serials.resources, sourceResources +
                arrivalAuthoritySourceResourcesV2(evidence.sources, request.evidence, physical.actualRegistrations), evidence.resources, command.operationId)
        return finishDecision(resources) {
            physical.requireRegistrationTime(command.recordedAt)
            evidence.failure?.let { throw ArrivalRejectedV2(it) }
            val documentary = checkNotNull(evidence.effects)
            val original = ArrivalBodyV2.Original(request, request.supplierDeliveryReference?.let(::normalizedPurchaseReference),
                facts.withDocumentary(documentary.evidence, guards))
            requireArrivalPhysicalStateV2(original, selected)
            val bodies = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>()
            val originalChange = arrivalChangeV2(command.scope, ArrivalProducerV2(command.operationId, command.actorId, command.command.name, command.command.version, command.semanticDigest.value), null, original, command.recordedAt, command.policyRevision)
            bodies[originalChange.key] = null to original
            val state = ArrivalBodyV2.State(request.arrivalId, arrivalReferenceV2(originalChange, command.operationId), selected, emptyList())
            bodies[arrivalBodyKeyV2(state)] = null to state
            selected.forEach { val identity = arrivalSourceIdentityV2(request.arrivalId, it); bodies[arrivalBodyKeyV2(identity)] = null to identity }
            bodies.putAll(arrivalGuardSuccessorsV2(original, selected, beforeGuards))
            outcome(command, bodies, request, LocalArrivalResultV2(LocalArrivalActionV1.RECORD, request.arrivalId, null, Revision(1), null),
                documentary, stockCut, evidence.mustCommitBefore, physical.actualRegistrations)
        }
    }

    private suspend fun transitionBefore(command: RuntimeHistoricalCommand,
        request: ArrivalTransitionInputV2): ArrivalOriginalDecisionV2? {
        val reader = ArrivalReaderV2(stock, command)
        val cut = reader.arrival(request.arrivalId)
        val base = arrivalTransitionResourcesV2(request) + cut?.request?.let { arrivalRequestResourcesV2(command.scope, it) }.orEmpty()
        fun refuse(failure: LocalArrivalFailureV2, extras: Set<RecoveryResourceKey> = emptySet()) =
            decision(command, base, reader, extras, failure)
        if (cut == null) return refuse(if (reader.foreignGuardConflict()) LocalArrivalFailureV2.SOURCE_ALREADY_USED else LocalArrivalFailureV2.SOURCE_MISSING)
        if (cut.state.row.revision != request.expectedArrivalRevision) return refuse(LocalArrivalFailureV2.STALE_REVISION)
        if (reader.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.EVENT, request.eventId)) != null || reader.foreignGuardConflict())
            return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        val original = cut.original.value.body as ArrivalBodyV2.Original
        val successor = try { arrivalPhysicalSuccessorV2(original, cut.current, request) }
        catch (failure: ArrivalRejectedV2) { return refuse(failure.failure) }
        val selected = cut.current.slices.single { it.custodyUnitId == request.custodyUnitId }
        val changed = successor.second
        val interlocks = linkedSetOf<RecoveryResourceKey>()
        request.split?.let { split ->
            if (reader.sourceUsed(split.sourceId, split.custodyUnitId, null)) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
            val ordinary = observeArrivalOrdinaryReceiptsBeforeV2(command, listOf(ArrivalOrdinaryReceiptIdentityV2(split.sourceId, null)))
            interlocks += ordinary.resources
            if (ordinary.used) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
            val normal = observeArrivalNormalSourcesBeforeV2(command, listOf(split.sourceId))
            interlocks += normal.resources
            if (normal.used) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        }
        if (cut.current.events.size >= LocalUnpricedArrivalProfileV2.MAX_EVENTS) return refuse(LocalArrivalFailureV2.CAPACITY_EXCEEDED, interlocks)
        val guards = reader.guards(cut.request, listOf(changed))
        if (reader.foreignGuardConflict()) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        val serials = observeArrivalNormalSerialsBeforeV2(command, listOf(changed))
        interlocks += serials.resources
        if (serials.used) return refuse(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        val beforeGuards = linkedMapOf<AggregateKey, ArrivalHistoricalV2?>()
        for ((key, row) in guards) beforeGuards[key] = row?.let { reader.historical(it) }
        val physical = try { physicalSourcesBefore(command, cut.request, listOf(changed), setOfNotNull(selected.serialId),
            listOf(request.item), listOf(request.warehouse), request.expectedSupplierRevision) }
        catch (failure: ArrivalRejectedV2) { return refuse(failure.failure, interlocks) }
            ?: return null
        val sourceResources = physical.resources(request.evidence)
        physical.primaryFailure?.let { return refuse(it, interlocks + sourceResources) }
        val stockCut = observeArrivalStockBeforeV2(command, cut.request.lines, listOf(changed))
        val stockResources = arrivalStockResourcesV2(stockCut)
        val facts = try { physical.wire(stockCut, cut.request, listOf(changed), original) }
        catch (failure: ArrivalRejectedV2) { return refuse(failure.failure, interlocks + sourceResources + stockResources) }
        val evidence = try { sources.evidenceBefore(command, request.action, request.evidence, request.expectedRequesterPolicyRevision,
            physical.actualSupplier, physical.actualItems) }
        catch (failure: LocalArrivalEvidenceRejectedV2) { return refuse(failure.failure, interlocks + sourceResources + stockResources) }
            ?: return null
        if (evidence.sources.isEmpty()) return refuse(checkNotNull(evidence.failure), interlocks + sourceResources + stockResources)
        val resources = arrivalMutationResourcesV2(command.actorId, command.scope, null, request, cut.request, reader.resources(),
            stockResources + interlocks, sourceResources + arrivalAuthoritySourceResourcesV2(evidence.sources, request.evidence,
                physical.actualRegistrations), evidence.resources, command.operationId)
        return finishDecision(resources) {
            check(cut.state.value.recordedAt <= command.recordedAt)
            physical.requireRegistrationTime(command.recordedAt)
            evidence.failure?.let { throw ArrivalRejectedV2(it) }
            val documentary = checkNotNull(evidence.effects)
            val event = ArrivalBodyV2.Event(request.action, command.normalized.copyBytes().decodeToString(throwOnInvalidSequence = true),
                cut.original.reference(), cut.state.reference(), facts.withDocumentary(documentary.evidence, guards), changed)
            val eventChange = arrivalChangeV2(command.scope, ArrivalProducerV2(command.operationId, command.actorId, command.command.name, command.command.version, command.semanticDigest.value), null, event, command.recordedAt, command.policyRevision)
            val state = cut.current.copy(slices = successor.first, events = cut.current.events + arrivalReferenceV2(eventChange, command.operationId))
            val bodies = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>(
                eventChange.key to (null to event), cut.state.row.key to (cut.state.reference() to state))
            if (request.split != null) { val identity = arrivalSourceIdentityV2(request.arrivalId, changed); bodies[arrivalBodyKeyV2(identity)] = null to identity }
            bodies.putAll(arrivalGuardSuccessorsV2(original, listOf(changed), beforeGuards))
            outcome(command, bodies, null, LocalArrivalResultV2(request.action, request.arrivalId, request.eventId,
                request.expectedArrivalRevision.next(), changed.custodyUnitId), documentary, stockCut, evidence.mustCommitBefore, physical.actualRegistrations)
        }
    }

    private suspend fun physicalSourcesBefore(command: RuntimeHistoricalCommand, request: LocalRecordUnpricedArrivalV2,
        selected: List<ArrivalSliceV1>, previouslyQualified: Set<DomainId>, expectedItems: List<LocalArrivalItemSelectionV2>,
        expectedWarehouses: List<LocalArrivalWarehouseSelectionV2>, expectedSupplierRevision: Revision): ArrivalCurrentSourcesV2? {
        requireArrivalSourceSelectionV2(request, selected, expectedItems, expectedWarehouses)
        val registrations = selected.filter { it.serialId != null && it.serialId !in previouslyQualified }.sortedBy { requireNotNull(it.serialId).value }
        val actual = sources.physicalBefore(command, request.supplierId, expectedItems.map { it.skuId }.sortedBy { it.value },
            expectedWarehouses.map { it.warehouseId }.sortedBy { it.value }, registrations.map { requireNotNull(it.serialId) }) ?: return null
        check(actual.supplier.scope == command.scope && actual.supplier.value.id == request.supplierId)
        val items = expectedItems.sortedBy { it.skuId.value }.map { expected -> expected to actual.items.single { it.value.skuId == expected.skuId } }
        val warehouses = expectedWarehouses.sortedBy { it.warehouseId.value }.map { expected -> expected to actual.warehouses.single { it.value.warehouseId == expected.warehouseId } }
        val serials = registrations.map { slice -> slice to actual.serialRegistrations.single { it.serialId == slice.serialId } }
        val stamps = (actual.supplier.sources + actual.items.flatMap { it.value.sources } + actual.warehouses.flatMap { it.value.sources } +
            actual.serialRegistrations.flatMap { it.sources }).groupBy { it.key }.values.map { values -> check(values.distinct().size == 1); values.first() }
            .sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
        if (stamps.size > LocalUnpricedArrivalProfileV2.MAX_PURCHASING_SOURCES) throw ArrivalHydrationCapacityUnavailableV2()
        for (stamp in stamps) {
            val source = checkNotNull(command.aggregateSource(stamp.key))
            check(source.row.revision == stamp.storageRevision && source.row.digest == stamp.digest)
        }
        return ArrivalCurrentSourcesV2(command.scope, actual.supplier, items, warehouses, serials, expectedSupplierRevision, stamps)
    }

    private fun outcome(command: RuntimeHistoricalCommand, bodies: Map<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>,
        originalRequest: LocalRecordUnpricedArrivalV2?, result: LocalArrivalResultV2, documentary: LocalArrivalDocumentaryEffectsV2,
        stockCut: ArrivalStockCutV2, deadline: UtcInstant?, registrations: List<LocalStockSerialRegistration>): ProposedOutcome.Apply {
        val codec = checkNotNull(stock.documentaryCodecV2)
        check(documentary.changes.all { it.key.kind in codec.documentaryKinds } && documentary.claims.all { it.key.namespace in codec.documentaryClaimNamespaces })
        val changes = (bodies.values.map { arrivalChangeV2(command.scope, ArrivalProducerV2(command.operationId, command.actorId, command.command.name, command.command.version, command.semanticDigest.value), it.first, it.second,
            command.recordedAt, command.policyRevision) } + documentary.changes + stockCut.readEvidenceChanges)
            .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
        val claims = (arrivalClaimsV2(command.scope, bodies, originalRequest) + documentary.claims)
            .sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })
        check(changes.map { it.key }.distinct().size == changes.size && claims.map { it.key }.distinct().size == claims.size)
        val encoded = arrivalEncode(result, LocalArrivalResultV2.serializer())
        requireArrivalEffectsCapacityV2(encoded, changes, claims)
        return ProposedOutcome.Apply(encoded, changes, claims, mustCommitBefore =
            (listOfNotNull(deadline) + registrations.mapNotNull { it.validUntil }).minOrNull())
    }

    private fun finishDecision(resources: Set<RecoveryResourceKey>, block: () -> ProposedOutcome.Apply): ArrivalOriginalDecisionV2 =
        try { ArrivalOriginalDecisionV2(null, resources, block()) }
        catch (failure: ArrivalRejectedV2) { ArrivalOriginalDecisionV2(failure.failure, resources, null) }

    private fun wrapperResources(command: RuntimeHistoricalCommand): Set<RecoveryResourceKey> = setOf(
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, DomainId(command.operationId.value)))
}

private class ArrivalOriginalDecisionV2(val failure: LocalArrivalFailureV2?, val resources: Set<RecoveryResourceKey>, val outcome: ProposedOutcome.Apply?)
