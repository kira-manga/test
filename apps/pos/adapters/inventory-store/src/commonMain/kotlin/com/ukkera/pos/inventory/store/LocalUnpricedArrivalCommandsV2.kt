package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Add beside v1/ordinary receiving on their SAME stock facade and borrowed writer. */
public class LocalUnpricedArrivalServicesV2(stock: LocalStockRepository, authority: LocalUnpricedArrivalAuthorityV2) {
    init {
        require(stock.documentaryCodecV2 === authority)
        require(authority.documentaryKinds.isNotEmpty() && authority.documentaryClaimNamespaces.isNotEmpty() &&
            authority.documentaryKinds.none { it in arrivalKindsV2 || it in StockRelationalKindsV2.all || it == StockReadEvidenceV1.KIND } &&
            authority.documentaryClaimNamespaces.none { it in arrivalClaimNamespacesV2 })
    }
    public val commands: List<MasterCommandHandler> = localStockFrozen(LocalArrivalActionV1.entries.map {
        ArrivalCommandHandlerV2(it, stock, authority)
    })
    public val reads: List<MasterReadHandler> = localStockFrozen(arrivalReadHandlersV2(stock, authority))
}

private class ArrivalCommandHandlerV2(private val action: LocalArrivalActionV1, private val stock: LocalStockRepository,
    private val authority: LocalUnpricedArrivalAuthorityV2) : MasterCommandHandler {
    override val key: CommandKey get() = action.commandV2()
    override val writableKinds: Set<String> get() = arrivalKindsV2 + authority.documentaryKinds + StockReadEvidenceV1.KIND
    override val claimNamespaces: Set<String> get() = arrivalClaimNamespacesV2 + authority.documentaryClaimNamespaces
    override fun normalize(input: StoredBytes): StoredBytes = arrivalNormalizeV2(key, input)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return if (action == LocalArrivalActionV1.RECORD) record(arrivalDecode(normalized,
            LocalRecordUnpricedArrivalV2.serializer(), 32_768), view, context)
        else transition(arrivalTransitionV2(key, normalized), normalized, view, context)
    }

    private suspend fun record(request: LocalRecordUnpricedArrivalV2, view: CommandReadView, context: CommandContext): PreparedMutation {
        val reader = stock.unpricedArrivalsV2(view)
        val base = arrivalRequestResourcesV2(context.scope, request)
        fun refusal(failure: LocalArrivalFailureV2, extra: Set<RecoveryResourceKey> = emptySet()): PreparedMutation =
            preparedArrivalRefusalV2(context, action, request.arrivalId, null, Revision(0), request.reasonCode,
                request.expectedRequesterPolicyRevision, base + reader.resources() + extra, failure)
        // Closed first-refusal ordering: custody identity, delivery, original source/unit/line,
        // ordinary/normal source, source selection/current sources, physical tracking,
        // guards/normal serial, stock,
        // purchasing preparation; time/direct proof, guard successors and whole capacity follow.
        if (reader.arrival(request.arrivalId) != null || reader.foreignGuardConflict())
            return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        if (reader.deliveryUsed(request)) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        for (line in request.lines) if (reader.sourceUsed(line.sourceId, line.custodyUnitId, line.lineId))
            return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        val ordinary = observeArrivalOrdinaryReceiptsV2(stock, view, request.lines.map {
            ArrivalOrdinaryReceiptIdentityV2(it.sourceId, it.lineId)
        })
        if (ordinary.used) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, ordinary.resources)
        val normalSources = observeArrivalNormalSourcesV2(stock, view, request.lines.map { it.sourceId })
        val interlocks = ordinary.resources + normalSources.resources
        if (normalSources.used) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        val slices = arrivalInitialSlicesV2(request)
        try { requireArrivalSourceSelectionV2(request, slices, request.items, request.warehouses) }
        catch (failure: ArrivalRejectedV2) { return refusal(failure.failure, interlocks) }
        val sources = loadArrivalCurrentSourcesV2(authority, view, request, slices, emptySet(), request.items,
            request.warehouses, request.expectedSupplierRevision)
        val sourceResources = sources.resources(request.evidence)
        if (!sources.available) return unavailablePreparedArrivalV2(context, action, request.arrivalId, null, Revision(0),
            request.reasonCode, request.expectedRequesterPolicyRevision, base + reader.resources() + interlocks + sourceResources)
        sources.primaryFailure?.let { return refusal(it, interlocks + sourceResources) }
        try { requireArrivalInitialPhysicalV2(request, sources.actualItems) }
        catch (failure: ArrivalRejectedV2) { return refusal(failure.failure, interlocks + sourceResources) }
        val guards = reader.guards(request, slices)
        if (reader.foreignGuardConflict()) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks + sourceResources)
        val normalSerials = observeArrivalNormalSerialsV2(stock, view, slices)
        val preStock = interlocks + sourceResources + normalSerials.resources
        if (normalSerials.used) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, preStock)
        val beforeGuards = linkedMapOf<AggregateKey, ArrivalHistoricalV2?>()
        for ((key, row) in guards) beforeGuards[key] = row?.let { reader.historical(it) }
        val stockCut = captureArrivalStockV2(stock, view, context, request.lines, slices)
        val stockResources = arrivalStockResourcesV2(stockCut)
        val facts = try { sources.wire(stockCut, request, slices, null) }
        catch (failure: ArrivalRejectedV2) { return refusal(failure.failure, preStock + stockResources) }
        val requirement = arrivalRequirementV2(context.scope, request.arrivalId, Revision(0), context.semanticDigest,
            context.operationId, request.reasonCode)
        val evidence = try { authority.prepareEvidence(view, context, action, request.evidence,
            request.expectedRequesterPolicyRevision, sources.actualSupplier, sources.actualItems, requirement) }
        catch (failure: LocalArrivalEvidenceRejectedV2) { return refusal(failure.failure, preStock + stockResources) }
        val resources = arrivalMutationResourcesV2(context.originalActorId, context.scope, request, null, null,
            reader.resources(), stockResources + interlocks + normalSerials.resources,
            sourceResources + evidence?.sources.orEmpty().let { if (it.isEmpty()) emptySet() else
                arrivalAuthoritySourceResourcesV2(it, request.evidence, sources.actualRegistrations) },
            evidence?.affectedResources.orEmpty(), context.operationId)
        if (evidence == null) return unavailablePreparedArrivalV2(context, action, request.arrivalId, null, Revision(0),
            request.reasonCode, request.expectedRequesterPolicyRevision, resources)
        return preparedArrivalV2(context, action, request.arrivalId, null, Revision(0), request.reasonCode,
            request.expectedRequesterPolicyRevision, resources) { authorization, now ->
            sources.requireRegistrationTime(now)
            val documentary = evidence.complete(authorization, now)
            val original = ArrivalBodyV2.Original(request, request.supplierDeliveryReference?.let(::normalizedPurchaseReference),
                facts.withDocumentary(documentary.evidence, guards))
            requireArrivalPhysicalStateV2(original, slices)
            val bodies = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>()
            val originalChange = arrivalChangeV2(context, null, original, now, authorization.policyRevision)
            bodies[originalChange.key] = null to original
            val state = ArrivalBodyV2.State(request.arrivalId, arrivalReferenceV2(originalChange, context.operationId), slices, emptyList())
            bodies[arrivalBodyKeyV2(state)] = null to state
            slices.forEach { slice -> val identity = arrivalSourceIdentityV2(request.arrivalId, slice); bodies[arrivalBodyKeyV2(identity)] = null to identity }
            bodies.putAll(arrivalGuardSuccessorsV2(original, slices, beforeGuards))
            completeArrivalV2(context, bodies, request, LocalArrivalResultV2(action, request.arrivalId, null, Revision(1), null),
                documentary, stockCut, authority, now, authorization.policyRevision,
                arrivalDeadlineV2(evidence.mustCommitBefore, sources.actualRegistrations))
        }
    }

    private suspend fun transition(request: ArrivalTransitionInputV2, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): PreparedMutation {
        val reader = stock.unpricedArrivalsV2(view)
        val cut = reader.arrival(request.arrivalId)
        val base = arrivalTransitionResourcesV2(request) + cut?.request?.let { arrivalRequestResourcesV2(context.scope, it) }.orEmpty()
        fun refusal(failure: LocalArrivalFailureV2, extra: Set<RecoveryResourceKey> = emptySet()): PreparedMutation =
            preparedArrivalRefusalV2(context, action, request.arrivalId, request.eventId, request.expectedArrivalRevision,
                request.reasonCode, request.expectedRequesterPolicyRevision, base + reader.resources() + extra, failure)
        // Missing/version-conflicting arrival, stale state, event identity, physical eligibility,
        // split interlocks, event capacity, guard/normal serial, fresh sources, stock, purchasing.
        if (cut == null) return refusal(if (reader.foreignGuardConflict()) LocalArrivalFailureV2.SOURCE_ALREADY_USED else LocalArrivalFailureV2.SOURCE_MISSING)
        if (cut.state.row.revision != request.expectedArrivalRevision) return refusal(LocalArrivalFailureV2.STALE_REVISION)
        if (reader.row(arrivalKeyV2(LocalUnpricedArrivalProfileV2.EVENT, request.eventId)) != null || reader.foreignGuardConflict())
            return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
        val original = cut.original.value.body as ArrivalBodyV2.Original
        val successor = try { arrivalPhysicalSuccessorV2(original, cut.current, request) }
        catch (failure: ArrivalRejectedV2) { return refusal(failure.failure) }
        val selected = cut.current.slices.single { it.custodyUnitId == request.custodyUnitId }
        val changed = successor.second
        val interlocks = linkedSetOf<RecoveryResourceKey>()
        request.split?.let { split ->
            if (reader.sourceUsed(split.sourceId, split.custodyUnitId, null)) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED)
            val ordinary = observeArrivalOrdinaryReceiptsV2(stock, view, listOf(ArrivalOrdinaryReceiptIdentityV2(split.sourceId, null)))
            interlocks += ordinary.resources
            if (ordinary.used) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
            val normal = observeArrivalNormalSourcesV2(stock, view, listOf(split.sourceId))
            interlocks += normal.resources
            if (normal.used) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        }
        if (cut.current.events.size >= LocalUnpricedArrivalProfileV2.MAX_EVENTS) return refusal(LocalArrivalFailureV2.CAPACITY_EXCEEDED, interlocks)
        val guards = reader.guards(cut.request, listOf(changed))
        if (reader.foreignGuardConflict()) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        val normalSerials = observeArrivalNormalSerialsV2(stock, view, listOf(changed))
        interlocks += normalSerials.resources
        if (normalSerials.used) return refusal(LocalArrivalFailureV2.SOURCE_ALREADY_USED, interlocks)
        val beforeGuards = linkedMapOf<AggregateKey, ArrivalHistoricalV2?>()
        for ((key, row) in guards) beforeGuards[key] = row?.let { reader.historical(it) }
        try { requireArrivalSourceSelectionV2(cut.request, listOf(changed), listOf(request.item), listOf(request.warehouse)) }
        catch (failure: ArrivalRejectedV2) { return refusal(failure.failure, interlocks) }
        val sources = loadArrivalCurrentSourcesV2(authority, view, cut.request, listOf(changed), setOfNotNull(selected.serialId),
            listOf(request.item), listOf(request.warehouse), request.expectedSupplierRevision)
        val sourceResources = sources.resources(request.evidence)
        if (!sources.available) return unavailablePreparedArrivalV2(context, action, request.arrivalId, request.eventId,
            request.expectedArrivalRevision, request.reasonCode, request.expectedRequesterPolicyRevision,
            base + reader.resources() + interlocks + sourceResources)
        sources.primaryFailure?.let { return refusal(it, interlocks + sourceResources) }
        val stockCut = captureArrivalStockV2(stock, view, context, cut.request.lines, listOf(changed))
        val stockResources = arrivalStockResourcesV2(stockCut)
        val facts = try { sources.wire(stockCut, cut.request, listOf(changed), original) }
        catch (failure: ArrivalRejectedV2) { return refusal(failure.failure, interlocks + sourceResources + stockResources) }
        val requirement = arrivalRequirementV2(context.scope, request.arrivalId, request.expectedArrivalRevision,
            context.semanticDigest, context.operationId, request.reasonCode)
        val evidence = try { authority.prepareEvidence(view, context, action, request.evidence,
            request.expectedRequesterPolicyRevision, sources.actualSupplier, sources.actualItems, requirement) }
        catch (failure: LocalArrivalEvidenceRejectedV2) { return refusal(failure.failure, interlocks + sourceResources + stockResources) }
        val resources = arrivalMutationResourcesV2(context.originalActorId, context.scope, null, request, cut.request,
            reader.resources(), stockResources + interlocks, sourceResources + evidence?.sources.orEmpty().let {
                if (it.isEmpty()) emptySet() else arrivalAuthoritySourceResourcesV2(it, request.evidence, sources.actualRegistrations)
            }, evidence?.affectedResources.orEmpty(), context.operationId)
        if (evidence == null) return unavailablePreparedArrivalV2(context, action, request.arrivalId, request.eventId,
            request.expectedArrivalRevision, request.reasonCode, request.expectedRequesterPolicyRevision, resources)
        return preparedArrivalV2(context, action, request.arrivalId, request.eventId, request.expectedArrivalRevision,
            request.reasonCode, request.expectedRequesterPolicyRevision, resources) { authorization, now ->
            check(cut.state.value.recordedAt <= now)
            sources.requireRegistrationTime(now)
            val documentary = evidence.complete(authorization, now)
            val event = ArrivalBodyV2.Event(action, normalized.copyBytes().decodeToString(throwOnInvalidSequence = true),
                cut.original.reference(), cut.state.reference(), facts.withDocumentary(documentary.evidence, guards), changed)
            val eventChange = arrivalChangeV2(context, null, event, now, authorization.policyRevision)
            val state = cut.current.copy(slices = successor.first, events = cut.current.events + arrivalReferenceV2(eventChange, context.operationId))
            val bodies = linkedMapOf<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>(
                eventChange.key to (null to event), cut.state.row.key to (cut.state.reference() to state))
            if (request.split != null) { val identity = arrivalSourceIdentityV2(request.arrivalId, changed); bodies[arrivalBodyKeyV2(identity)] = null to identity }
            bodies.putAll(arrivalGuardSuccessorsV2(original, listOf(changed), beforeGuards))
            completeArrivalV2(context, bodies, null, LocalArrivalResultV2(action, request.arrivalId, request.eventId,
                request.expectedArrivalRevision.next(), changed.custodyUnitId), documentary, stockCut, authority, now,
                authorization.policyRevision, arrivalDeadlineV2(evidence.mustCommitBefore, sources.actualRegistrations))
        }
    }
}

internal fun preparedArrivalV2(context: CommandContext, action: LocalArrivalActionV1, arrivalId: DomainId, eventId: DomainId?,
    revision: Revision, reason: String, expectedRequesterPolicyRevision: Revision, resources: Set<RecoveryResourceKey>,
    restriction: PreparedMutationAdmissionRestriction? = null,
    block: (AuthorizedAction, UtcInstant) -> ProposedOutcome): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = arrivalRequirementV2(context.scope, arrivalId, revision,
        context.semanticDigest, context.operationId, reason)
    private val resources = resources.toSet() + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId) +
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, DomainId(context.operationId.value))
    override val affectedResources: Set<RecoveryResourceKey> get() = resources.toSet()
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() = arrivalBarriers
    // Preparation mode is supplied by the trusted kernel, not a request-body grant.
    // Actual issuance/current checks and consumption remain the kernel's responsibility.
    override val allowsActionApproval: Boolean get() = context.actionApprovalRequested
    override val actionApprovalBusinessAttribution: ActionApprovalBusinessAttributionSupport
        get() = ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER
    override val admissionRestriction: PreparedMutationAdmissionRestriction? get() = restriction
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
        return try {
            arrivalRequireV2(authorization.policyRevision == expectedRequesterPolicyRevision, LocalArrivalFailureV2.STALE_REQUESTER_POLICY)
            if (!context.actionApprovalRequested)
                arrivalRequireV2(authorization.approvalId == null && authorization.approverId == null && authorization.approverPolicyRevision == null,
                    LocalArrivalFailureV2.APPROVAL_HISTORY_UNAVAILABLE)
            block(authorization, recordedAt)
        } catch (failure: ArrivalRejectedV2) { arrivalRejectV2(action, arrivalId, eventId, failure.failure) }
        catch (failure: LocalArrivalEvidenceRejectedV2) { arrivalRejectV2(action, arrivalId, eventId, failure.failure) }
    }
}

internal fun preparedArrivalRefusalV2(context: CommandContext, action: LocalArrivalActionV1, arrivalId: DomainId, eventId: DomainId?,
    revision: Revision, reason: String, expectedRequesterPolicyRevision: Revision, resources: Set<RecoveryResourceKey>,
    failure: LocalArrivalFailureV2): PreparedMutation = preparedArrivalV2(context, action, arrivalId, eventId, revision, reason,
    expectedRequesterPolicyRevision, resources) { _, _ -> arrivalRejectV2(action, arrivalId, eventId, failure) }

private fun unavailablePreparedArrivalV2(context: CommandContext, action: LocalArrivalActionV1, arrivalId: DomainId, eventId: DomainId?,
    revision: Revision, reason: String, expectedRequesterPolicyRevision: Revision, resources: Set<RecoveryResourceKey>): PreparedMutation =
    preparedArrivalV2(context, action, arrivalId, eventId, revision, reason, expectedRequesterPolicyRevision, resources,
        PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE) { _, _ -> error("Unavailable arrival sources cannot be evaluated") }

private suspend fun captureArrivalStockV2(stock: LocalStockRepository, view: CommandReadView, context: CommandContext,
    lines: List<LocalArrivalLineV2>, slices: List<ArrivalSliceV1>): ArrivalStockCutV2 {
    if (!stock.isRelationalCurrent(view)) return observeArrivalStockV2(stock, view, lines, slices)
    val capture = view.requireStockRelational().beginReadEvidence(context)
    var sealed = false
    try {
        val cut = observeArrivalStockV2(stock, view, lines, slices)
        check(cut.profile == ArrivalStockProfileV2.RELATIONAL_V2)
        val changes = capture.finish()
        sealed = true
        return cut.withReadEvidence(changes)
    } catch (failure: Throwable) {
        if (!sealed) capture.discard()
        throw failure
    }
}

private fun completeArrivalV2(context: CommandContext, bodies: Map<AggregateKey, Pair<ArrivalRowRefV2?, ArrivalBodyV2>>,
    original: LocalRecordUnpricedArrivalV2?, result: LocalArrivalResultV2, documentary: LocalArrivalDocumentaryEffectsV2,
    stockCut: ArrivalStockCutV2, codec: LocalUnpricedArrivalDocumentaryCodecV2, now: UtcInstant,
    policyRevision: Revision, deadline: UtcInstant?): ProposedOutcome.Apply {
    check(documentary.changes.all { it.key.kind in codec.documentaryKinds } &&
        documentary.claims.all { it.key.namespace in codec.documentaryClaimNamespaces })
    val custody = bodies.values.map { entry -> arrivalChangeV2(context, entry.first, entry.second, now, policyRevision) }
    val changes = (custody + documentary.changes + stockCut.readEvidenceChanges)
        .sortedWith(compareBy<AggregateChange> { it.key.kind }.thenBy { it.key.id.value })
    val claims = (arrivalClaimsV2(context.scope, bodies, original) + documentary.claims)
        .sortedWith(compareBy<ClaimChange> { it.key.namespace }.thenBy { it.key.key })
    check(changes.map { it.key }.distinct().size == changes.size && claims.map { it.key }.distinct().size == claims.size)
    val encoded = arrivalEncode(result, LocalArrivalResultV2.serializer())
    requireArrivalEffectsCapacityV2(encoded, changes, claims)
    return ProposedOutcome.Apply(encoded, changes, claims, mustCommitBefore = deadline)
}

private fun arrivalDeadlineV2(policyDeadline: UtcInstant?, registrations: List<LocalStockSerialRegistration>): UtcInstant? =
    (listOfNotNull(policyDeadline) + registrations.mapNotNull { it.validUntil }).minOrNull()

/** Source-only physical validation never fabricates a custody authority/evidence envelope. */
internal fun requireArrivalInitialPhysicalV2(request: LocalRecordUnpricedArrivalV2, items: List<LocalArrivalItemAuthorityV2>) {
    val physical = ArrivalPhysicalOriginal(request.arrivalId, request.supplierId, request.lines, items.map {
        ArrivalPhysicalItem(it.value.skuId, it.value.baseUnitId, it.value.quantityScale, arrivalTrackingV2(it.value.tracking.name))
    }, ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS)
    try { requireArrivalPhysicalStateShared(physical, arrivalInitialSlicesV2(request)) }
    catch (failure: ArrivalRejected) { throw ArrivalRejectedV2(LocalArrivalFailureV2.valueOf(failure.failure.name)) }
}

private fun arrivalTrackingV2(name: String): LocalArrivalTrackingV1 = LocalArrivalTrackingV1.entries.singleOrNull { it.name == name }
    ?: throw ArrivalRejectedV2(LocalArrivalFailureV2.INVALID_TRACKING)

internal class ArrivalCurrentSourcesV2(val scope: BranchScope, val supplier: LocalArrivalSupplierAuthorityV2?,
    val items: List<Pair<LocalArrivalItemSelectionV2, LocalArrivalItemAuthorityV2?>>,
    val warehouses: List<Pair<LocalArrivalWarehouseSelectionV2, LocalArrivalWarehouseAuthority?>>,
    val registrations: List<Pair<ArrivalSliceV1, LocalStockSerialRegistration?>>, val expectedSupplierRevision: Revision,
    val stamps: List<LocalStockSourceStamp>) {
    val available: Boolean get() = supplier != null && items.all { it.second != null } && warehouses.all { it.second != null } && registrations.all { it.second != null }
    val actualSupplier: LocalArrivalSupplierAuthorityV2 get() = checkNotNull(supplier)
    val actualItems: List<LocalArrivalItemAuthorityV2> get() = items.map { checkNotNull(it.second) }
    val actualRegistrations: List<LocalStockSerialRegistration> get() = registrations.map { checkNotNull(it.second) }
    val primaryFailure: LocalArrivalFailureV2? get() {
        check(available)
        if (!actualSupplier.value.active) return LocalArrivalFailureV2.SUPPLIER_UNAVAILABLE
        if (actualSupplier.source.storageRevision != expectedSupplierRevision) return LocalArrivalFailureV2.STALE_REVISION
        for ((expected, source) in items) {
            val actual = checkNotNull(source)
            val item = actual.value
            if (!item.active) return LocalArrivalFailureV2.ITEM_UNAVAILABLE
            if (item.productId != expected.productId || item.baseUnitId != expected.baseUnitId || item.quantityScale != expected.quantityScale)
                return LocalArrivalFailureV2.INVALID_UNIT
            if (item.catalogRevision != expected.catalogRevision || item.skuRevision != expected.skuRevision ||
                actual.productRevision != expected.productRevision || actual.source.storageRevision != expected.storageRevision)
                return LocalArrivalFailureV2.STALE_REVISION
            if (LocalArrivalTrackingV1.entries.none { it.name == item.tracking.name } || item.requiresExpiry && item.tracking.name != LocalArrivalTrackingV1.LOT.name)
                return LocalArrivalFailureV2.INVALID_TRACKING
        }
        for ((expected, source) in warehouses) {
            val actual = checkNotNull(source)
            if (!actual.value.active) return LocalArrivalFailureV2.WAREHOUSE_UNAVAILABLE
            if (actual.source.storageRevision != expected.storageRevision) return LocalArrivalFailureV2.STALE_REVISION
        }
        for ((slice, source) in registrations) {
            val actual = checkNotNull(source)
            if (actual.serialCode != slice.serialCode) return LocalArrivalFailureV2.INVALID_SERIAL
            if (actual.revision != slice.serialRegistrationRevision) return LocalArrivalFailureV2.STALE_REVISION
        }
        return null
    }

    fun resources(selection: LocalArrivalEvidenceSelectionV2): Set<RecoveryResourceKey> =
        arrivalAuthoritySourceResourcesV2(stamps, selection, registrations.mapNotNull { it.second })

    fun requireRegistrationTime(now: UtcInstant) {
        for (registration in actualRegistrations) arrivalRequireV2(now >= registration.validFrom &&
            (registration.validUntil?.let { now < it } ?: true), LocalArrivalFailureV2.SERIAL_REGISTRATION_REQUIRED)
    }

    fun wire(stock: ArrivalStockCutV2, request: LocalRecordUnpricedArrivalV2, selected: List<ArrivalSliceV1>,
        original: ArrivalBodyV2.Original?): ArrivalCurrentAuthorityFactsV2 {
        check(available && primaryFailure == null && stock.scope == scope)
        for ((slice, source) in registrations) arrivalRequireV2(checkNotNull(source).skuId ==
            request.lines.single { it.lineId == slice.lineId }.skuId, LocalArrivalFailureV2.INVALID_SERIAL)
        val itemFacts = items.map { (expected, source) ->
            val actual = checkNotNull(source); val item = actual.value
            val selection = LocalArrivalItemSelectionV2(item.skuId, item.productId, actual.productRevision, item.baseUnitId,
                item.quantityScale, item.catalogRevision, item.skuRevision, actual.source.storageRevision, stock.items.getValue(item.skuId)?.source?.revision)
            arrivalRequireV2(selection == expected, LocalArrivalFailureV2.STALE_REVISION)
            val frozen = request.items.single { it.skuId == item.skuId }
            arrivalRequireV2(selection.productId == frozen.productId && selection.baseUnitId == frozen.baseUnitId &&
                selection.quantityScale == frozen.quantityScale, LocalArrivalFailureV2.INVALID_UNIT)
            val tracking = arrivalTrackingV2(item.tracking.name)
            if (original != null) arrivalRequireV2(original.authority.items.single { it.selection.skuId == item.skuId }.tracking == tracking,
                LocalArrivalFailureV2.INVALID_TRACKING)
            ArrivalItemAuthorityWireV2(selection, tracking, item.requiresExpiry, item.active, item.canPurchase,
                StockSourceStampWire.from(actual.source), item.sources.map(StockSourceStampWire::from))
        }
        val warehouseFacts = warehouses.map { (expected, source) ->
            val actual = checkNotNull(source)
            val selection = LocalArrivalWarehouseSelectionV2(actual.value.warehouseId, actual.source.storageRevision)
            arrivalRequireV2(selection == expected, LocalArrivalFailureV2.STALE_REVISION)
            ArrivalWarehouseAuthorityWireV2(selection, actual.value.active, StockSourceStampWire.from(actual.source), actual.value.sources.map(StockSourceStampWire::from))
        }
        stock.requireCompatible(itemFacts, request.lines, selected)
        return ArrivalCurrentAuthorityFactsV2(actualSupplier, itemFacts, warehouseFacts, actualRegistrations.map(StockSerialRegistrationWire::from), stock)
    }
}

internal class ArrivalCurrentAuthorityFactsV2(val supplier: LocalArrivalSupplierAuthorityV2,
    val items: List<ArrivalItemAuthorityWireV2>, val warehouses: List<ArrivalWarehouseAuthorityWireV2>,
    val registrations: List<StockSerialRegistrationWire>, val stock: ArrivalStockCutV2) {
    fun withDocumentary(purchasing: LocalArrivalPurchasingEvidenceV2, guards: Map<AggregateKey, ArrivalRowV2?>): ArrivalAuthorityWireV2 =
        ArrivalAuthorityWireV2(supplier.value, StockSourceStampWire.from(supplier.source), supplier.sources.map(StockSourceStampWire::from),
            items, warehouses, registrations, purchasing, stock.profile, stock.activation, stock.observations, stock.absentItems,
            guards.map { (key, row) -> ArrivalGuardObservationV2(key.kind, key.id, row?.reference()) }
                .sortedWith(compareBy<ArrivalGuardObservationV2> { it.kind }.thenBy { it.id.value }))
}

/** Before any authority reads, current and native preparation classify the same selection. */
internal fun requireArrivalSourceSelectionV2(request: LocalRecordUnpricedArrivalV2, selected: List<ArrivalSliceV1>,
    expectedItems: List<LocalArrivalItemSelectionV2>, expectedWarehouses: List<LocalArrivalWarehouseSelectionV2>) {
    val lines = selected.map { slice -> request.lines.single { it.lineId == slice.lineId } }
    arrivalRequireV2(expectedItems.map { it.skuId }.toSet() == lines.map { it.skuId }.toSet() &&
        expectedWarehouses.map { it.warehouseId }.toSet() == lines.map { it.warehouseId }.toSet(), LocalArrivalFailureV2.STALE_REVISION)
}

private suspend fun loadArrivalCurrentSourcesV2(authority: LocalUnpricedArrivalAuthorityV2, view: CommandReadView,
    originalRequest: LocalRecordUnpricedArrivalV2, selected: List<ArrivalSliceV1>, previouslyQualified: Set<DomainId>,
    expectedItems: List<LocalArrivalItemSelectionV2>, expectedWarehouses: List<LocalArrivalWarehouseSelectionV2>,
    expectedSupplierRevision: Revision): ArrivalCurrentSourcesV2 {
    val supplier = authority.supplier(view, originalRequest.supplierId)
    check(supplier == null || supplier.scope == view.scope && supplier.value.id == originalRequest.supplierId)
    val items = expectedItems.sortedBy { it.skuId.value }.map { expected ->
        val source = authority.item(view, expected.skuId)
        check(source == null || source.value.scope == view.scope && source.value.skuId == expected.skuId &&
            source.source.key.id == expected.skuId && source.source in source.value.sources)
        expected to source
    }
    val warehouses = expectedWarehouses.sortedBy { it.warehouseId.value }.map { expected ->
        val source = authority.warehouse(view, expected.warehouseId)
        check(source == null || source.value.scope == view.scope && source.value.warehouseId == expected.warehouseId &&
            source.source.key.id == expected.warehouseId && source.source in source.value.sources)
        expected to source
    }
    val registrations = selected.filter { it.serialId != null && it.serialId !in previouslyQualified }.sortedBy { requireNotNull(it.serialId).value }.map { slice ->
        val source = authority.serialRegistration(view, requireNotNull(slice.serialId))
        check(source == null || source.scope == view.scope && source.serialId == slice.serialId)
        slice to source
    }
    val raw = supplier?.sources.orEmpty() + items.mapNotNull { it.second }.flatMap { it.value.sources } +
        warehouses.mapNotNull { it.second }.flatMap { it.value.sources } + registrations.mapNotNull { it.second }.flatMap { it.sources }
    val stamps = if (raw.isEmpty()) emptyList() else requireArrivalAuthorityStampsV2(view, raw)
    return ArrivalCurrentSourcesV2(view.scope, supplier, items, warehouses, registrations, expectedSupplierRevision, stamps)
}

/** Fresh exact stamps; separate from v1's smaller source bound and positive-generation API. */
internal suspend fun requireArrivalAuthorityStampsV2(view: CommandReadView, sources: List<LocalStockSourceStamp>): List<LocalStockSourceStamp> {
    val grouped = sources.groupBy { it.key }
    check(grouped.isNotEmpty() && grouped.values.all { it.distinct().size == 1 })
    if (grouped.size > LocalUnpricedArrivalProfileV2.MAX_PURCHASING_SOURCES) throw ArrivalHydrationCapacityUnavailableV2()
    val exact = grouped.values.map { it.first() }.sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
    for (stamp in exact) {
        val row = view.aggregate(stamp.key) ?: error("Arrival authority source disappeared")
        check(row.key == stamp.key && row.revision == stamp.storageRevision && row.digest == stamp.digest && sha256(row.payload.copyBytes()) == stamp.digest)
    }
    return localStockFrozen(exact)
}
