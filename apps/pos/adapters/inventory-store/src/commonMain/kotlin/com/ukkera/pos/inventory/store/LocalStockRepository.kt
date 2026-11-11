package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.JobIntent
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.InventoryEffect
import com.ukkera.pos.inventory.InventoryLocalCut
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.OwnedValuationPool
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockIssue
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.StockReservation
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.KSerializer

/**
 * Normalized stock repository over the real borrowed master transaction. No DAO, process-local
 * stock map, naked write/import method, generic genesis or accepting runtime is exposed.
 * Verifier registrations are copied once at trusted process composition, never request-selected.
 */
public class LocalStockRepository(
    compositeVerifiers: List<LocalStockCompositeVerifier> = emptyList(),
    private val purchaseOrderReader: LocalStockPurchaseOrderReaderV1? = null,
    relationalCompositeVerifiers: List<LocalStockRelationalCompositeVerifier> = emptyList(),
    private val relationalAuthority: LocalStockRelationalAuthorityReader? = null,
    /** Null holds any present v2 custody; it is never an accepting original-evidence default. */
    internal val documentaryCodecV2: LocalUnpricedArrivalDocumentaryCodecV2? = null,
) {
    private val otherProducers: Map<CommandKey, LocalStockCompositeVerifier>
    private val relational = LocalStockRelationalRepository(this, relationalCompositeVerifiers)

    /** Trusted installation discovery only; this does not qualify an original, activate stock,
     * or authorize a request. Actual reads and writes still require their complete producer. */
    public fun hasRelationalProducer(command: CommandKey): Boolean = relational.hasProducer(command)

    internal fun receivingAuthority(actual: LocalStockAuthorityReader? = null): LocalStockRelationalAuthorityReader {
        val installed = relationalAuthority ?: unavailableProducer()
        localStockCheck(actual == null || installed === actual, LocalStockFailure.PRODUCER_UNAVAILABLE)
        return installed
    }

    /** Route by persisted version, then require the full producer on the selected reader. A
     * markerless partial normalized estate is never allowed to fall back to the legacy writer. */
    internal suspend fun isRelationalCurrent(view: CommandReadView): Boolean {
        if (view.aggregate(LocalStockRelationalProfileV2.activationKey(view.scope.branchId)) != null) return true
        requireLegacyStockCurrent(view)
        return false
    }

    /** Explicit current-profile reader; an unavailable/missing activation is NOT empty stock. */
    public suspend fun observeRelational(view: CommandReadView): LocalStockRelationalRead = relational.open(view)

    /** Complete actual producer + one genuine direct-source hop; never a shape-only approval. */
    public suspend fun requireRelationalProducer(view: CommandReadView,
        original: com.ukkera.pos.database.RetainedStreamedCommand): LocalStockRelationalInspection {
        return relational.open(view, active = false).requireOriginalProducer(original)
    }

    /** One original-only read scope on the SAME active core loan. Only normally closed
     * immutable producer proofs may be shared; every requested root and direct source is
     * still reopened and compared. This exposes no current-stock reader, activation bypass,
     * accepting predicate, writer or authority. An escaped scope refuses after return. */
    public suspend fun <T> withRelationalOriginalRead(view: CommandReadView,
        consume: suspend (LocalStockOriginalRead) -> T): T {
        val read = relational.open(view, active = false)
        val originals = LocalStockOriginalRead(this, read)
        try { return read.withCompletedProducerProof { consume(originals) } }
        finally { originals.close() }
    }

    /** Core-native startup capability only, never a fabricated historical current view.
     * Runs the SAME installed full iterative producer/resource traversal as current reads. */
    public suspend fun requireHistoricalRelationalProducer(command: com.ukkera.pos.database.RuntimeHistoricalCommand) {
        command.requireStreamedProducer { original ->
            val inspected = relational.requireComplete(original)
            if (command.command == LocalStockRelationalProfileV2.COMMAND)
                requireStockAdministrationHistoryDeadline(command, inspected, this)
            else if (command.command == LocalStockRelationalProfileV2.RETURN_COMMAND)
                requireHistoricalRetailReturnStockDeadline(command, inspected)
            else requireHistoricalCommerceStockDeadline(command, inspected)
        }
    }

    /** RELEASED restoration must retain the original native final-wall decision for every
     * selected source expiry. The complete installed RETURN producer has already joined the
     * exact movement and destination; only its literal BEFORE issue slice/position can supply
     * this deadline. QUARANTINE-only postings still require the genuine no-deadline decision. */
    private suspend fun requireHistoricalRetailReturnStockDeadline(
        command: com.ukkera.pos.database.RuntimeHistoricalCommand,
        inspected: LocalStockRelationalInspection,
    ) {
        localStockCheck(command.command == LocalStockRelationalProfileV2.RETURN_COMMAND &&
            command.operationId == inspected.operationId && command.normalized == inspected.original.normalized)
        var deadline: UtcInstant? = null
        var intents = 0L
        inspected.retailReturnRestorationIntents { request ->
            intents = stockCheckedAdd(intents, 1)
            val issue = inspected.beforeIssue(request.issueId) ?: inconsistentStock()
            requireStockRetailReturnRequest(request, issue)
            for (selection in request.selections) {
                if (selection.requestedCustody != com.ukkera.pos.inventory.StockCustody.RELEASED) continue
                val original = (inspected.before(LocalStockRelationalProfileV2.issueSliceKey(
                    request.issueId, selection.allocationId))?.body as? StockRelationalBody.IssueSlice)?.value
                    ?: inconsistentStock()
                requireStockRetailReturnSelection(request, selection, original)
                val source = (inspected.before(LocalStockRelationalProfileV2.positionKey(original.allocation.position))?.body
                    as? StockRelationalBody.Position)?.value ?: inconsistentStock()
                localStockCheck(source.key == original.allocation.position)
                source.expiresAtEpochMillis?.let { millis ->
                    val expiry = UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(millis))
                    deadline = deadline?.let { minOf(it, expiry) } ?: expiry
                }
            }
        }
        localStockCheck(intents == 1L)
        command.requireFinalDeadlineDecision(deadline)
    }

    /** After the SAME installed whole commerce producer, compare the original final-wall
     * decision with every expiry actually selected by reserveAll/issueReserved. Retail Begin
     * also has a deadline: checking only Finalize would strand an expiry-bearing reservation
     * on restart. Unused FEFO candidates are not deadlines, and recordedAt is not finalWall.
     * No current view, original, stock cut or source authority is manufactured here. */
    private suspend fun requireHistoricalCommerceStockDeadline(
        command: com.ukkera.pos.database.RuntimeHistoricalCommand,
        inspected: LocalStockRelationalInspection,
    ) {
        val purpose = when (command.command) {
            LocalStockRelationalProfileV2.COLLECTION_COMMAND -> com.ukkera.pos.inventory.ValueIssuePurpose.RETAIL
            LocalStockRelationalProfileV2.FOOD_COMMAND -> com.ukkera.pos.inventory.ValueIssuePurpose.DIRECT_RECIPE
            else -> return
        }
        var deadline: UtcInstant? = null
        var acted = false
        fun note(value: UtcInstant?) { if (value != null) deadline = deadline?.let { minOf(it, value) } ?: value }
        fun noteMillis(value: Long?) { note(value?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) }) }
        inspected.reservationIntents { intent ->
            acted = true
            localStockCheck(inspected.before(LocalStockRelationalProfileV2.reservationKey(intent.reservationId)) == null)
            val after = inspected.reservation(intent.reservationId) ?: inconsistentStock()
            localStockCheck(after.id == intent.reservationId && after.binding == intent.binding && after.holdCount == 0L &&
                after.expiresAtEpochMillis == intent.expiresAt?.value?.toEpochMilliseconds())
            note(intent.expiresAt)
            var allocations = 0L
            inspected.allocations(intent.reservationId) { allocation ->
                val position = (inspected.before(LocalStockRelationalProfileV2.positionKey(allocation.allocation.position))?.body
                    as? StockRelationalBody.Position)?.value ?: inconsistentStock()
                localStockCheck(allocation.reservationId == intent.reservationId && position.key == allocation.allocation.position)
                noteMillis(position.expiresAtEpochMillis)
                allocations = stockCheckedAdd(allocations, 1)
            }
            localStockCheck(allocations == after.allocationCount && allocations > 0L)
        }
        inspected.issueIntents { intent ->
            acted = true
            localStockCheck(intent.purpose == purpose)
            val before = inspected.before(LocalStockRelationalProfileV2.reservationKey(intent.reservationId))?.let {
                it.body as? StockRelationalBody.Reservation ?: inconsistentStock()
            }
            val after = inspected.reservation(intent.reservationId) ?: inconsistentStock()
            if (before == null) {
                // An uncollected food Send reserves and issues the same new reservation.
                // Retail Finalize must instead name its real earlier reservation below.
                localStockCheck(purpose == com.ukkera.pos.inventory.ValueIssuePurpose.DIRECT_RECIPE)
                var reserves = 0
                inspected.reservationIntents { reserve -> if (reserve.reservationId == intent.reservationId) {
                    localStockCheck(intent.expectedRevision == Revision(1) && reserve.binding == after.binding &&
                        after.expiresAtEpochMillis == reserve.expiresAt?.value?.toEpochMilliseconds())
                    note(reserve.expiresAt)
                    reserves++
                } }
                localStockCheck(reserves == 1)
            } else {
                localStockCheck(before.value.id == intent.reservationId && before.value.revision == intent.expectedRevision &&
                    before.value.binding == after.binding && before.value.allocationCount == after.allocationCount)
                noteMillis(before.value.expiresAtEpochMillis)
            }
            val issue = inspected.issue(intent.issueId) ?: inconsistentStock()
            localStockCheck(issue.id == intent.issueId && issue.reservationId == intent.reservationId && issue.purpose == purpose &&
                issue.binding == after.binding && issue.allocationCount == after.allocationCount)
            var slices = 0L
            inspected.issueSlices(intent.issueId) { slice ->
                val position = (inspected.before(LocalStockRelationalProfileV2.positionKey(slice.allocation.position))?.body
                    as? StockRelationalBody.Position)?.value ?: inconsistentStock()
                localStockCheck(slice.issueId == intent.issueId && position.key == slice.allocation.position)
                noteMillis(position.expiresAtEpochMillis)
                slices = stockCheckedAdd(slices, 1)
            }
            localStockCheck(slices == issue.allocationCount && slices > 0L)
        }
        if (acted) command.requireFinalDeadlineDecision(deadline)
    }

    /** Internal fresh-only entry: no public accepting census/import/profile flag. */
    internal suspend fun freshRelationalRead(view: CommandReadView, context: CommandContext,
        sink: com.ukkera.pos.database.StreamedCommandSink): LocalStockRelationalRead {
        localStockCheck(context.key == LocalStockRelationalProfileV2.COMMAND && context.scope == view.scope)
        val read = relational.open(view, sink, active = false)
        sink.requireContext(read.access, context)
        return read
    }

    /** Internal active receiving path; no public profile switch or caller-provided read facade. */
    internal suspend fun receivingRelationalRead(view: CommandReadView, context: CommandContext,
        sink: com.ukkera.pos.database.StreamedCommandSink): LocalStockRelationalRead {
        localStockCheck(context.key == LocalStockRelationalProfileV2.COMMAND && context.scope == view.scope)
        val read = relational.open(view, sink)
        sink.requireContext(read.access, context)
        return read
    }

    /** Called after the installed complete commerce producer's exact authorization, on its SAME sink. */
    public suspend fun streamRelational(view: CommandReadView, context: CommandContext,
        sink: com.ukkera.pos.database.StreamedCommandSink, authority: LocalStockAuthorityReader,
        recordedAt: UtcInstant): LocalStockRelationalTransaction {
        localStockCheck(context.scope == view.scope && context.key in com.ukkera.pos.database.StockStreamingCommandsV2.all)
        val read = relational.open(view, sink)
        sink.requireContext(read.access, context)
        return LocalStockRelationalTransaction(this, authority, read, context, sink, recordedAt)
    }

    init {
        require(compositeVerifiers.size <= 32)
        val copied = compositeVerifiers.flatMap { verifier -> verifier.commands.toSet().map { it to verifier } }
        require(copied.size <= 128 && copied.map { it.first }.distinct().size == copied.size && copied.none { it.first in stockCommands })
        otherProducers = copied.toMap()
    }

    /**
     * Reads complete selected-SKU indexes and the transitive active-reservation dependency closure.
     * Requested original issues load their original reservation and all its current pools too.
     * Exhaustion refuses before planning; it never returns the first N rows as a complete cut.
     * A null item is only an indexed/claim-checked absence for a subsequently authorized first receipt.
     */
    public suspend fun observe(
        view: CommandReadView,
        skuIds: List<DomainId>,
        reservationIds: List<DomainId> = emptyList(),
        issueIds: List<DomainId> = emptyList(),
    ): LocalStockReadCut {
        requireLegacyStockCurrent(view)
        return StockLoader(view, this).load(skuIds, reservationIds, issueIds)
    }

    /** Null means NOT INCLUDED in this cut, never proof of global absence or a release. */
    public fun observedReservationSource(cut: LocalStockReadCut, id: DomainId): LocalStockObservedSource? =
        cut.state.reservations[id]?.let { copiedObservedSource(cut.scope, it) }

    /** Null means NOT INCLUDED in this cut, never permission to manufacture/reissue stock. */
    public fun observedIssueSource(cut: LocalStockReadCut, id: DomainId): LocalStockObservedSource? =
        cut.state.issues[id]?.let { copiedObservedSource(cut.scope, it) }

    /**
     * Current count/stock facts for the configuration owner's separate value-approval command.
     * Null is claim-checked document absence. Posted/recount-needed are explicit facts, so that
     * the caller can reject only after its own exact authorization. Corruption never becomes OPEN.
     */
    public suspend fun countApprovalSnapshot(view: CommandReadView, countId: DomainId): LocalStockCountApprovalSnapshot? {
        if (isRelationalCurrent(view)) return relationalCountApprovalSnapshot(this, view, countId)
        val count = stockDocument(this, view, LocalStockStoreProfile.COUNT_DOCUMENT, countId, StockCountWire.serializer()) ?: return null
        val original = count.value
        val cut = observe(view, listOf(original.position.skuId))
        val item = cut.state.items[original.position.skuId] ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        val position = cut.state.positions[original.position.key(view.scope)] ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(original.capturedQuantity.unitId == position.value.onHand.unitId &&
            original.capturedQuantity.scale == position.value.onHand.scale, LocalStockFailure.STORAGE_INCONSISTENT)
        val sameCut = item.row.revision == original.capturedItemRevision
        if (sameCut) localStockCheck(position.value.onHand == original.capturedQuantity, LocalStockFailure.STORAGE_INCONSISTENT)
        val status = when {
            original.counted != null -> LocalStockCountApprovalStatus.POSTED
            !sameCut -> LocalStockCountApprovalStatus.RECOUNT_REQUIRED
            else -> LocalStockCountApprovalStatus.OPEN
        }
        val sources = listOf(count.row, item.row, position.row).map { LocalStockSourceStamp(it.key, it.revision, it.digest) }
            .sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
        return LocalStockCountApprovalSnapshot(view.scope, countId, count.row.revision, original.position.restore(),
            original.capturedItemRevision, original.capturedQuantity, original.capturedBy, original.capturedAt,
            item.row.revision, position.value.onHand, status, sources, resources(cut) + setOf(
                RecoveryResourceKey(RecoveryResourceKind.STOCK, countId),
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, original.capturedBy)))
    }

    /**
     * Literal consumption of the caller's independently verified immutable approval, not authority.
     * Validates the retained count producer and both document/claim directions without requiring
     * historical item/position generations to remain live after a legitimate stock posting.
     */
    public suspend fun countSurplusConsumed(view: CommandReadView, basis: LocalCountSurplusBasis): Boolean {
        localStockCheck(basis.scope == view.scope, LocalStockFailure.STORAGE_INCONSISTENT)
        val count = stockDocument(this, view, LocalStockStoreProfile.COUNT_DOCUMENT, basis.countId, StockCountWire.serializer())
            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        val value = count.value
        localStockCheck(value.countId == basis.countId && value.position == StockPositionRefWire.from(basis.position) &&
            value.capturedItemRevision == basis.capturedItemRevision && value.capturedQuantity == basis.capturedQuantity,
            LocalStockFailure.STORAGE_INCONSISTENT)
        if (value.counted == null) {
            localStockCheck(count.envelope.producer.commandKey() in setOf(LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_CAPTURE_SERIAL, LocalStockRelationalProfileV2.COMMAND) &&
                count.row.revision == basis.countRevision, LocalStockFailure.STORAGE_INCONSISTENT)
        } else {
            localStockCheck(count.envelope.producer.commandKey() in setOf(LocalStockStoreProfile.COUNT_POST, LocalStockStoreProfile.COUNT_POST_SERIAL, LocalStockRelationalProfileV2.COMMAND) &&
                count.envelope.previous?.revision == basis.countRevision, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        val owner = view.claim(LocalStockStoreProfile.countSurplusClaim(basis.basisId))
        val postedBasis = value.surplusBasis
        if (postedBasis != null) {
            val postedOwner = if (postedBasis.basisId == basis.basisId) owner
                else view.claim(LocalStockStoreProfile.countSurplusClaim(postedBasis.basisId))
            localStockCheck(postedOwner == basis.countId, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        val consumed = postedBasis?.basisId == basis.basisId
        if (consumed) localStockCheck(postedBasis == StockCountSurplusBasisWire.from(basis) && owner == basis.countId,
            LocalStockFailure.STORAGE_INCONSISTENT)
        else localStockCheck(owner == null, LocalStockFailure.STORAGE_INCONSISTENT)
        return consumed
    }

    /** Exact-source live plan projection, not a permission or a complete sales proposal. */
    public fun project(cut: LocalStockReadCut, context: CommandContext, plan: InventoryPlan, now: UtcInstant): LocalStockContribution =
        projectLocalStock(cut.state, context, plan, now)

    /**
     * Composes the inventory contribution, typed caller's other effects and one full posting
     * manifest. The caller still owns its complete producer validation and authorization. This
     * never creates money, a sales proof, an approval or a success reply; only the kernel commits.
     */
    public fun compose(
        context: CommandContext,
        contribution: LocalStockContribution,
        result: StoredBytes,
        additionalChanges: List<AggregateChange> = emptyList(),
        additionalClaims: List<ClaimChange> = emptyList(),
        jobs: List<JobIntent> = emptyList(),
        mustCommitBefore: UtcInstant? = null,
    ): ProposedOutcome.Apply = completeStockProposal(context, contribution.changes + additionalChanges,
        contribution.claims + additionalClaims, result, jobs, contribution.effects,
        listOfNotNull(contribution.mustCommitBefore, mustCommitBefore).minOrNull(), contribution.observations)

    /** Full conservative recovery footprint; a branch-ID sentinel is not a stock wildcard. */
    public fun resources(cut: LocalStockReadCut): Set<RecoveryResourceKey> = buildSet {
        cut.state.items.keys.forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
        cut.snapshot.positions.forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.key.pool.warehouseId))
            it.key.lotId?.let { id -> add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id)) }
            it.key.serialId?.let { id -> add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id)) }
            it.serialRegistrationId?.let { id -> add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)) }
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.positionKey(it.key).id))
        }
        cut.snapshot.pools.forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.poolKey(it.key).id)) }
        cut.snapshot.reservations.forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.reservationId))
            add(RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, it.binding.orderId))
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.binding.originalActorId))
        }
        cut.state.serials.values.forEach { record ->
            val serial = record.value
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, serial.serialId))
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, localStockId("serial-code", serial.serialCode)))
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, serial.receiptId))
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, serial.lineId))
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, serial.sourceId))
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, serial.sourceId))
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, serial.recordedBy))
            serial.registration?.let { addAll(serialRegistrationResources(it.restore())) }
        }
    }

    /**
     * Deterministic FEFO selection from a real cut. Warehouse is explicitly selected; expiry,
     * quarantine, supplier custody, recall and retained paid/unknown holds all remain unavailable.
     * The caller supplies the persisted quote/allocation-set identity AND revision. Repricing
     * must advance that revision; stable order-line IDs deliberately do not advance. Allocation
     * claims from an earlier quote therefore cannot be reused by a later quote.
     */
    public fun allocate(
        cut: LocalStockReadCut,
        allocationSetId: DomainId,
        allocationSetRevision: Revision,
        lineId: DomainId,
        skuId: DomainId,
        warehouseId: DomainId,
        baseQuantity: Quantity,
        now: UtcInstant,
    ): List<StockAllocation> = allocate(cut, allocationSetId, allocationSetRevision,
        listOf(LocalStockAllocationRequest(lineId, skuId, warehouseId, baseQuantity)), now)

    /** Whole-basket selection; preceding lines consume the same transaction-local availability. */
    public fun allocate(
        cut: LocalStockReadCut,
        allocationSetId: DomainId,
        allocationSetRevision: Revision,
        requests: List<LocalStockAllocationRequest>,
        now: UtcInstant,
    ): List<StockAllocation> {
        localStockCheck(requests.size in 1..LocalStockStoreProfile.MAX_LINES_PER_COMMAND &&
            requests.map { it.lineId }.distinct().size == requests.size, LocalStockFailure.BOUND_EXCEEDED)
        val selected = mutableMapOf<StockPositionKey, Quantity>()
        val result = mutableListOf<StockAllocation>()
        for (request in requests) {
            val baseQuantity = request.baseQuantity
            localStockCheck(baseQuantity.scaled > 0 && request.skuId in cut.state.items, LocalStockFailure.INVALID_QUANTITY)
            val item = cut.state.items[request.skuId]
            if (item != null && item.value.positions.any { it.serialId != null }) {
                localStockCheck(baseQuantity.unitId == item.value.unitId && baseQuantity.scale == item.value.quantityScale, LocalStockFailure.INVALID_UNIT)
                localStockCheck(baseQuantity.scaled % stockSerialUnit(baseQuantity).scaled == 0L, LocalStockFailure.INVALID_SERIAL)
            }
            val candidates = cut.snapshot.positions.filter { it.key.pool.skuId == request.skuId && it.key.pool.warehouseId == request.warehouseId &&
                it.isSellableAt(now.value.toEpochMilliseconds()) }.sortedWith(compareBy<StockPosition> { it.expiresAtEpochMillis ?: Long.MAX_VALUE }
                .thenBy { it.key.lotId?.value.orEmpty() }.thenBy { it.key.serialId?.value.orEmpty() })
            var remaining = baseQuantity
            for (position in candidates) {
                localStockCheck(position.onHand.unitId == baseQuantity.unitId && position.onHand.scale == baseQuantity.scale, LocalStockFailure.INVALID_UNIT)
                val used = selected[position.key] ?: Quantity(baseQuantity.unitId, 0, baseQuantity.scale)
                val available = cut.snapshot.available(position.key, now.value.toEpochMilliseconds()) - used
                val quantity = if (available <= remaining) available else remaining
                if (quantity.scaled > 0) {
                    result += StockAllocation(localStockId("allocation", "${allocationSetId.value}/${allocationSetRevision.value}/${request.lineId.value}/${LocalStockStoreProfile.positionKey(position.key).id.value}"),
                        request.lineId, position.key, quantity)
                    selected[position.key] = used + quantity
                    remaining -= quantity
                }
                if (remaining.scaled == 0L) break
            }
            localStockCheck(remaining.scaled == 0L, LocalStockFailure.INSUFFICIENT_STOCK)
        }
        localStockCheck(result.size <= com.ukkera.pos.inventory.InventoryPlanner.MAX_ALLOCATIONS, LocalStockFailure.BOUND_EXCEEDED)
        return localStockFrozen(result)
    }

    /**
     * Literal historical facts plus the exact complete manifest crosswalk. This intentionally
     * does not call composite verifiers (which may call it), issue authority or prove a frontier.
     * The trusted sales verifier must still validate its WHOLE specific producer. Ordered kinds
     * and literal effects include every release/kitchen/waste event, not only retail summaries.
     */
    public fun inspectPosting(evidence: RetainedCommandEvidence): com.ukkera.pos.inventory.InventoryPostedRecords? =
        requireStockInventoryPosting(evidence)

    internal val hasPurchaseOrderReader: Boolean get() = purchaseOrderReader != null

    internal suspend fun approvedPurchaseOrder(view: CommandReadView, id: DomainId): LocalStockApprovedPurchaseOrderV1? {
        val reader = purchaseOrderReader ?: throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)
        return reader.order(view, id)?.also { requireApprovedPoSource(it, view.scope, id) }
    }

    internal suspend fun requireProducer(
        view: CommandReadView, evidence: RetainedCommandEvidence,
        budget: StockLoadBudget = StockLoadBudget(),
        purchaseSources: MutableMap<OperationId, LocalStockApprovedPurchaseOrderV1> = mutableMapOf(),
    ) {
        val records = requireStockInventoryPosting(evidence)
        if (evidence.command in stockCommands) requireLocalStockProducer(evidence, records)
        else (otherProducers[evidence.command] ?: throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)).requireComplete(evidence)
        if (evidence.command == LocalStockStoreProfile.RECEIVE_PO) {
            val request = LocalStockCommandCodec.poReceipt(evidence.normalized)
            val receipt = retainedBody(evidence, AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId), StockReceiptWire.serializer())
            val proof = receipt.poValuation ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            val approvalId = proof.orderSource.approvalOperationId
            val source = purchaseSources[approvalId] ?: (approvedPurchaseOrder(view, request.orderId)
                ?: throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)).also {
                // Charge the actual whole predecessor approval to the SAME bounded stock read session.
                budget.producer(it.approval)
                localStockCheck(it.approval.operationId == approvalId, LocalStockFailure.STORAGE_INCONSISTENT)
                purchaseSources[approvalId] = it
            }
            requirePoReceiptSource(evidence, source)
        }
    }

    internal suspend fun <T> record(view: CommandReadView, key: AggregateKey, serializer: KSerializer<T>): LocalStockRecord<T>? {
        // Only the existing scalar receiving document/allowance/serial families can have the
        // explicit new stock producer. No old item/list-root is reinterpreted as normalized.
        if (key.kind in stockDocumentKinds) {
            val row = view.aggregate(key) ?: return null
            val envelope = stockRecord(row, view.scope)
            if (envelope.producer.commandKey() == LocalStockRelationalProfileV2.COMMAND) {
                val read = relational.open(view, active = false)
                val original = read.original(envelope.producer.operationId)
                localStockCheck(original.identity.producer == com.ukkera.pos.database.StreamedProducerIdentityV2(
                    envelope.producer.operationId, envelope.producer.commandKey(), envelope.producer.actorId, PayloadDigest(envelope.producer.digest)))
                requireOriginalWrite(original, key, row.revision, row.schema, row.payload, row.digest)
                return LocalStockRecord(row, envelope, stockBody(envelope.body, serializer))
            }
        }
        return StockLoader(view, this).record(key, serializer)
    }

    internal suspend fun ownedPool(view: CommandReadView, reference: StockPoolRefWire): LocalStockPool =
        observe(view, listOf(reference.skuId)).state.pools[reference.key(view.scope)]
            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
}

private fun copiedObservedSource(scope: BranchScope, record: LocalStockRecord<*>): LocalStockObservedSource {
    val producer = record.envelope.producer
    // StockLoader already authenticated the envelope and its actual complete producer.
    // Keep only copied immutable scalar coordinates; never expose its internal state/wire.
    return LocalStockObservedSource(scope, record.row.key, record.row.revision, record.row.digest,
        producer.operationId, producer.commandKey(), producer.actorId, PayloadDigest(producer.digest))
}

internal class LocalStockRecord<T>(val row: StoredAggregate, val envelope: StockRecordWire, val value: T)
internal class LocalStockPool(val record: LocalStockRecord<StockPoolWire>, val value: OwnedValuationPool, val pages: Map<DomainId, StoredAggregate>)
internal class LocalStockLoaded(
    val scope: BranchScope,
    val items: Map<DomainId, LocalStockRecord<StockItemWire>?>,
    val positions: Map<StockPositionKey, LocalStockRecord<StockPosition>>,
    val pools: Map<StockPoolKey, LocalStockPool>,
    val reservations: Map<DomainId, LocalStockRecord<StockReservation>>,
    val issues: Map<DomainId, LocalStockRecord<StockIssue>>,
    val serials: Map<DomainId, LocalStockRecord<StockSerialWire>>,
    val snapshot: InventorySnapshot,
)

internal val stockCommands: Set<CommandKey> = setOf(LocalStockStoreProfile.OPENING, LocalStockStoreProfile.RECEIVE, LocalStockStoreProfile.RECEIVE_PO,
    LocalStockStoreProfile.INSPECT, LocalStockStoreProfile.COUNT_CAPTURE, LocalStockStoreProfile.COUNT_POST,
    LocalStockStoreProfile.RECALL, LocalStockStoreProfile.TRANSFER,
    LocalStockStoreProfile.OPENING_SERIAL, LocalStockStoreProfile.RECEIVE_SERIAL, LocalStockStoreProfile.INSPECT_SERIAL,
    LocalStockStoreProfile.COUNT_CAPTURE_SERIAL, LocalStockStoreProfile.COUNT_POST_SERIAL,
    LocalStockStoreProfile.RECALL_SERIAL, LocalStockStoreProfile.TRANSFER_SERIAL, LocalStockStoreProfile.TRANSFER_OWNED)

private class StockLoader(private val view: CommandReadView, private val repository: LocalStockRepository) {
    private val budget = StockLoadBudget()
    private val producers = mutableMapOf<OperationId, RetainedCommandEvidence>()
    private val purchaseSources = mutableMapOf<OperationId, LocalStockApprovedPurchaseOrderV1>()
    private val crosswalked = mutableSetOf<OperationId>()
    private val items = linkedMapOf<DomainId, LocalStockRecord<StockItemWire>?>()
    private val positions = linkedMapOf<StockPositionKey, LocalStockRecord<StockPosition>>()
    private val pools = linkedMapOf<StockPoolKey, LocalStockPool>()
    private val reservations = linkedMapOf<DomainId, LocalStockRecord<StockReservation>>()
    private val issues = linkedMapOf<DomainId, LocalStockRecord<StockIssue>>()
    private val serials = linkedMapOf<DomainId, LocalStockRecord<StockSerialWire>>()
    private var valueBytes = 0L
    private val inventoryScope get() = InventoryScope(view.scope.companyId, view.scope.branchId)

    suspend fun load(skuIds: List<DomainId>, reservationIds: List<DomainId>, issueIds: List<DomainId>): LocalStockReadCut {
        localStockCheck(skuIds.size <= LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND && skuIds.distinct().size == skuIds.size &&
            reservationIds.size <= LocalStockStoreProfile.MAX_RESERVATIONS_PER_COMMAND && issueIds.size <= 16,
            LocalStockFailure.BOUND_EXCEEDED)
        val pending = skuIds.toMutableSet()
        for (id in issueIds.distinct()) {
            val raw = record(LocalStockStoreProfile.issueKey(id), StockIssueWire.serializer())
                ?: throw LocalStockRejected(LocalStockFailure.STOCK_MISSING)
            val issue = InventoryRecordCodec.decodeIssue(raw.value.encoded, inventoryScope, id, raw.value.revision)
            issues[id] = LocalStockRecord(raw.row, raw.envelope, issue)
            pending += issue.allocations.map { it.allocation.position.pool.skuId }
            loadReservation(issue.reservationId)
        }
        for (id in reservationIds.distinct()) loadReservation(id)
        pending += reservations.values.flatMap { it.value.allocations }.map { it.position.pool.skuId }
        while (pending.any { it !in items }) {
            if (pending.size > LocalStockStoreProfile.MAX_ITEMS_PER_COMMAND) throw LocalStockHydrationCapacityUnavailable()
            val next = pending.filter { it !in items }.minBy { it.value }
            loadItem(next)
            pending += reservations.values.flatMap { it.value.allocations }.map { it.position.pool.skuId }
        }
        for ((skuId, item) in items) {
            val expected = reservations.values.filter { it.value.retainsStockClaim && it.value.allocations.any { allocation -> allocation.position.pool.skuId == skuId } }
                .map { it.value.reservationId }.toSet()
            localStockCheck(item != null || expected.isEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
            if (item != null) localStockCheck(item.value.activeReservationIds.toSet() == expected, LocalStockFailure.STORAGE_INCONSISTENT)
        }
        val snapshot = InventoryLocalCut.assemble(inventoryScope, positions.values.map { it.value }, pools.values.map { it.value },
            reservations.values.map { it.value }, issues.values.map { it.value })
        return LocalStockReadCut(LocalStockLoaded(view.scope, items.toMap(), positions.toMap(), pools.toMap(),
            reservations.toMap(), issues.toMap(), serials.toMap(), snapshot))
    }

    private suspend fun loadItem(skuId: DomainId) {
        val itemRecord = record(LocalStockStoreProfile.itemKey(skuId), StockItemWire.serializer())
        val claim = view.claim(LocalStockStoreProfile.itemClaim(skuId))
        if (itemRecord == null) {
            localStockCheck(claim == null, LocalStockFailure.STORAGE_INCONSISTENT)
            items[skuId] = null
            return
        }
        localStockCheck(claim == skuId, LocalStockFailure.STORAGE_INCONSISTENT)
        val item = itemRecord.value
        requireStockItemIndex(item, view.scope, skuId)
        if (positions.size + item.positions.size > LocalStockStoreProfile.MAX_POSITIONS_PER_COMMAND ||
            pools.size + item.pools.size > LocalStockStoreProfile.MAX_POOLS_PER_COMMAND ||
            item.activeReservationIds.size > LocalStockStoreProfile.MAX_RESERVATIONS_PER_COMMAND)
            throw LocalStockHydrationCapacityUnavailable()
        items[skuId] = itemRecord
        for (reference in item.positions) {
            val key = reference.key(view.scope)
            val raw = record(LocalStockStoreProfile.positionKey(key), StockPositionWire.serializer())
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(raw.value.position == reference, LocalStockFailure.STORAGE_INCONSISTENT)
            val position = InventoryRecordCodec.decodePosition(raw.value.encoded, key)
            key.serialId?.let { id ->
                val serial = serials[id] ?: (record(LocalStockStoreProfile.serialKey(id), StockSerialWire.serializer())
                    ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)).also {
                    requireStockSerialClaims(view, id, it); serials[id] = it
                }
                requireStockSerialPosition(serial.value, position)
            }
            localStockCheck(position.onHand.unitId == item.unitId && position.onHand.scale == item.quantityScale,
                LocalStockFailure.STORAGE_INCONSISTENT)
            positions[key] = LocalStockRecord(raw.row, raw.envelope, position)
        }
        val expectedPools = item.positions.map { it.key(view.scope).pool }.filter { it.ownership == StockOwnership.CompanyOwned }.toSet()
        localStockCheck(item.pools.map { it.key(view.scope) }.toSet() == expectedPools, LocalStockFailure.STORAGE_INCONSISTENT)
        for (reference in item.pools) loadPool(reference)
        for (id in item.activeReservationIds) {
            loadReservation(id)
            val reservation = reservations.getValue(id).value
            localStockCheck(reservation.retainsStockClaim && reservation.allocations.any { it.position.pool.skuId == skuId },
                LocalStockFailure.STORAGE_INCONSISTENT)
        }
    }

    private suspend fun loadPool(reference: StockPoolRefWire) {
        val key = reference.key(view.scope)
        if (key in pools) return
        val record = record(LocalStockStoreProfile.poolKey(key), StockPoolWire.serializer())
            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        val header = record.value
        localStockCheck(header.pool == reference, LocalStockFailure.STORAGE_INCONSISTENT)
        requireStockPoolPages(header, record.row.key.id, record.row.revision)
        valueBytes += header.encodedBytes
        if (valueBytes > 8L * 1_024 * 1_024) throw LocalStockHydrationCapacityUnavailable()
        val bytes = ByteArray(header.encodedBytes)
        var offset = 0
        val pages = linkedMapOf<DomainId, StoredAggregate>()
        for (page in header.pages) {
            val digest = com.ukkera.pos.security.PayloadDigest(page.digest)
            val row = view.aggregate(AggregateKey(LocalStockStoreProfile.VALUE_PAGE, page.id))
                ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            budget.row(row.payload.size)
            localStockCheck(row.schema == stockPoolPageSchema(header) && row.revision == Revision(1) &&
                row.payload.size == page.bytes && row.digest == digest && sha256(row.payload.copyBytes()) == digest,
                LocalStockFailure.STORAGE_INCONSISTENT)
            row.payload.copyBytes().copyInto(bytes, offset)
            offset += page.bytes
            pages[page.id] = row
        }
        localStockCheck(offset == bytes.size && sha256(bytes).value == header.encodedDigest, LocalStockFailure.STORAGE_INCONSISTENT)
        val pool = InventoryRecordCodec.decodePool(bytes.decodeToString(throwOnInvalidSequence = true), key, header.revision)
        requireStockPoolProfile(header, pool)
        localStockCheck(pool.value.onHand == header.quantity && pool.value.carryingValue == header.carryingValue, LocalStockFailure.STORAGE_INCONSISTENT)
        for (sourceId in pool.value.eventIds) requireSource(sourceId, record.row.key)
        pools[key] = LocalStockPool(record, pool, pages)
    }

    private suspend fun loadReservation(id: DomainId) {
        if (id in reservations) return
        if (reservations.size >= LocalStockStoreProfile.MAX_RESERVATIONS_PER_COMMAND) throw LocalStockHydrationCapacityUnavailable()
        val raw = record(LocalStockStoreProfile.reservationKey(id), StockReservationWire.serializer())
            ?: throw LocalStockRejected(LocalStockFailure.STOCK_MISSING)
        localStockCheck(view.claim(LocalStockStoreProfile.reservationClaim(id)) == id, LocalStockFailure.STORAGE_INCONSISTENT)
        val reservation = InventoryRecordCodec.decodeReservation(raw.value.encoded, inventoryScope, id, raw.value.revision)
        for (allocation in reservation.allocations) localStockCheck(view.claim(LocalStockStoreProfile.allocationClaim(allocation.allocationId)) == id,
            LocalStockFailure.STORAGE_INCONSISTENT)
        reservations[id] = LocalStockRecord(raw.row, raw.envelope, reservation)
    }

    private suspend fun requireSource(id: DomainId, pool: AggregateKey) {
        val owner = view.claim(LocalStockStoreProfile.sourceClaim(id))
            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        val evidence = producer(OperationId(owner.value))
        localStockCheck(evidence.claimChanges.any { it.action == RetainedClaimAction.ACQUIRE &&
            it.key == LocalStockStoreProfile.sourceClaim(id) && it.ownerId == owner } && evidence.aggregateWrites.any { it.key == pool },
            LocalStockFailure.STORAGE_INCONSISTENT)
    }

    suspend fun <T> record(key: AggregateKey, serializer: KSerializer<T>): LocalStockRecord<T>? {
        val row = view.aggregate(key) ?: return null
        budget.row(row.payload.size)
        val envelope = stockRecord(row, view.scope)
        val evidence = producer(envelope.producer.operationId)
        val expected = envelope.producer
        localStockCheck(evidence.scope == view.scope && evidence.command == expected.commandKey() && evidence.originalActorId == expected.actorId &&
            evidence.semanticDigest.value == expected.digest && evidence.committed &&
            semanticDigest(view.scope, expected.actorId, expected.commandKey(), evidence.normalized).value == expected.digest,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val after = evidence.aggregateWrites.singleOrNull { it.key == key }
            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
        localStockCheck(after.storageGeneration == row.revision && after.schema == row.schema && after.payload == row.payload &&
            after.payloadDigest == row.digest, LocalStockFailure.STORAGE_INCONSISTENT)
        if (evidence.operationId !in crosswalked) {
            requireStockSourceCrosswalk(view, evidence, budget) { id -> producer(id) }
            crosswalked += evidence.operationId
        }
        return LocalStockRecord(row, envelope, stockBody(envelope.body, serializer))
    }

    private suspend fun producer(id: OperationId): RetainedCommandEvidence = producers[id] ?: run {
        val evidence = view.retainedCommand(id) ?: throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)
        localStockCheck(evidence.operationId == id && evidence.scope == view.scope, LocalStockFailure.STORAGE_INCONSISTENT)
        budget.producer(evidence)
        repository.requireProducer(view, evidence, budget, purchaseSources)
        producers[id] = evidence
        evidence
    }
}

private fun projectLocalStock(current: LocalStockLoaded, context: CommandContext, plan: InventoryPlan, now: UtcInstant): LocalStockContribution {
    localStockCheck(current.scope == context.scope && plan.sourceSnapshot === current.snapshot && plan.operationId == context.operationId,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val encodedEffects = InventoryRecordCodec.encodeEffects(current.snapshot, plan, now.value.toEpochMilliseconds())
        .encodeToByteArray(throwOnInvalidSequence = true)
    localStockCheck(encodedEffects.size <= LocalStockStoreProfile.MAX_ROW_BYTES, LocalStockFailure.BOUND_EXCEEDED)
    val effects = StoredBytes(encodedEffects)
    val after = plan.nextSnapshot
    localStockCheck(after.positions.size <= LocalStockStoreProfile.MAX_POSITIONS_PER_COMMAND && after.pools.size <= LocalStockStoreProfile.MAX_POOLS_PER_COMMAND &&
        after.reservations.size <= LocalStockStoreProfile.MAX_RESERVATIONS_PER_COMMAND && after.positions.all { it.key.pool.skuId in current.items },
        LocalStockFailure.BOUND_EXCEEDED)
    val changes = linkedMapOf<AggregateKey, AggregateChange>()
    val claims = mutableListOf<ClaimChange>()
    val touched = mutableSetOf<DomainId>()
    val producer = StockProducerWire.from(context)
    fun add(key: AggregateKey, previous: StoredAggregate?, body: String) {
        localStockCheck(key !in changes, LocalStockFailure.STORAGE_INCONSISTENT)
        changes[key] = AggregateChange(key, previous?.revision, stockSchema(key.kind, body),
            stockRecord(context.scope, key, previous?.revision, producer, body, previous))
    }
    val safetyPositions = plan.effects.filterIsInstance<InventoryEffect.SafetyChanged>().map { it.after.key }.toSet()
    for (position in after.positions) {
        val previous = current.positions[position.key]
        if (previous?.value == position && position.key !in safetyPositions) continue
        touched += position.key.pool.skuId
        add(LocalStockStoreProfile.positionKey(position.key), previous?.row,
            stockBody(StockPositionWire(StockPositionRefWire.from(position.key), InventoryRecordCodec.encodePosition(position)), StockPositionWire.serializer()))
    }
    for (pool in after.pools) {
        val previous = current.pools[pool.key]
        if (previous?.value?.value === pool.value) continue
        touched += pool.key.skuId
        val key = LocalStockStoreProfile.poolKey(pool.key)
        val generation = (previous?.record?.row?.revision ?: Revision(0)).next()
        val ownedTransferVersion = if (pool.value.hasTransferLineage) InventoryRecordCodec.OWNED_TRANSFER_VERSION else null
        val encoded = InventoryRecordCodec.encodePool(pool).encodeToByteArray(throwOnInvalidSequence = true)
        val pageCount = (encoded.size + LocalStockStoreProfile.VALUE_PAGE_BYTES - 1) / LocalStockStoreProfile.VALUE_PAGE_BYTES
        localStockCheck(pageCount in 1..LocalStockStoreProfile.MAX_POOL_PAGES, LocalStockFailure.BOUND_EXCEEDED)
        val pages = (0 until pageCount).map { ordinal ->
            val from = ordinal * LocalStockStoreProfile.VALUE_PAGE_BYTES
            val bytes = StoredBytes(encoded.copyOfRange(from, minOf(encoded.size, from + LocalStockStoreProfile.VALUE_PAGE_BYTES)))
            val digest = sha256(bytes.copyBytes())
            val retained = previous?.record?.value?.takeIf { it.ownedTransferVersion == ownedTransferVersion }?.pages?.getOrNull(ordinal)
                ?.takeIf { it.digest == digest.value && it.bytes == bytes.size }
            if (retained != null) {
                localStockCheck(previous?.pages?.get(retained.id)?.payload == bytes, LocalStockFailure.STORAGE_INCONSISTENT)
                retained
            } else {
                // An identical page may exist in older, noncurrent history. A new creator
                // generation makes this insert distinct without overwriting immutable history.
                val id = stockValuePageId(key.id, generation, ordinal, digest.value)
                val pageKey = AggregateKey(LocalStockStoreProfile.VALUE_PAGE, id)
                localStockCheck(pageKey !in changes, LocalStockFailure.STORAGE_INCONSISTENT)
                changes[pageKey] = AggregateChange(pageKey, null, LocalStockStoreProfile.schema(pageKey.kind, ownedTransferVersion ?: 1), bytes)
                StockPageRefWire(id, generation, bytes.size, digest.value)
            }
        }
        add(key, previous?.record?.row, stockBody(StockPoolWire(StockPoolRefWire.from(pool.key), pool.value.revision,
            pool.value.onHand, pool.value.carryingValue, encoded.size, sha256(encoded).value, pages, ownedTransferVersion), StockPoolWire.serializer()))
    }
    for (reservation in after.reservations) {
        val previous = current.reservations[reservation.reservationId]
        val encoded = InventoryRecordCodec.encodeReservation(reservation, after.scope)
        if (previous != null && InventoryRecordCodec.encodeReservation(previous.value, after.scope) == encoded) continue
        touched += reservation.allocations.map { it.position.pool.skuId }
        add(LocalStockStoreProfile.reservationKey(reservation.reservationId), previous?.row,
            stockBody(StockReservationWire(reservation.revision, encoded), StockReservationWire.serializer()))
        if (previous == null) {
            claims += ClaimChange.Acquire(LocalStockStoreProfile.reservationClaim(reservation.reservationId), reservation.reservationId)
            reservation.allocations.forEach { claims += ClaimChange.Acquire(LocalStockStoreProfile.allocationClaim(it.allocationId), reservation.reservationId) }
        }
    }
    for (issue in after.issues) {
        val previous = current.issues[issue.issueId]
        val encoded = InventoryRecordCodec.encodeIssue(issue, after.scope)
        if (previous != null && InventoryRecordCodec.encodeIssue(previous.value, after.scope) == encoded) continue
        touched += issue.allocations.map { it.allocation.position.pool.skuId }
        add(LocalStockStoreProfile.issueKey(issue.issueId), previous?.row,
            stockBody(StockIssueWire(issue.revision, encoded), StockIssueWire.serializer()))
    }
    // A zero-shortage count and repeated recall still have durable captured-cut semantics.
    for (effect in plan.effects) when (effect) {
        is InventoryEffect.CountReconciled -> touched += effect.position.pool.skuId
        is InventoryEffect.SafetyChanged -> touched += effect.after.key.pool.skuId
        else -> Unit
    }
    val itemVersions = mutableListOf<LocalStockItemVersion>()
    for (skuId in touched.sortedBy { it.value }) {
        localStockCheck(skuId in current.items, LocalStockFailure.STORAGE_INCONSISTENT)
        val previous = current.items[skuId]
        val positions = after.positions.filter { it.key.pool.skuId == skuId }
        localStockCheck(positions.isNotEmpty(), LocalStockFailure.STORAGE_INCONSISTENT)
        val unit = positions.first().onHand
        localStockCheck(positions.all { it.onHand.unitId == unit.unitId && it.onHand.scale == unit.scale } &&
            (previous == null || (previous.value.unitId == unit.unitId && previous.value.quantityScale == unit.scale)), LocalStockFailure.INVALID_UNIT)
        val item = StockItemWire(skuId, unit.unitId, unit.scale,
            positions.sortedBy { LocalStockStoreProfile.positionKey(it.key).id.value }.map { StockPositionRefWire.from(it.key) },
            after.pools.filter { it.key.skuId == skuId }.sortedBy { LocalStockStoreProfile.poolKey(it.key).id.value }.map { StockPoolRefWire.from(it.key) },
            after.reservations.filter { it.retainsStockClaim && it.allocations.any { allocation -> allocation.position.pool.skuId == skuId } }
                .map { it.reservationId }.sortedBy { it.value })
        add(LocalStockStoreProfile.itemKey(skuId), previous?.row, stockBody(item, StockItemWire.serializer()))
        itemVersions += LocalStockItemVersion(skuId, (previous?.row?.revision ?: Revision(0)).next())
        if (previous == null) claims += ClaimChange.Acquire(LocalStockStoreProfile.itemClaim(skuId), skuId)
    }
    val priorSources = current.snapshot.sourceIds.toSet()
    after.sourceIds.filter { it !in priorSources }.forEach { claims += ClaimChange.Acquire(LocalStockStoreProfile.sourceClaim(it), DomainId(context.operationId.value)) }
    localStockCheck(current.positions.keys.all { after.position(it) != null } && current.pools.keys.all { after.pool(it) != null } &&
        current.reservations.keys.all { after.reservation(it) != null } && current.issues.keys.all { after.issue(it) != null },
        LocalStockFailure.STORAGE_INCONSISTENT)
    return LocalStockContribution(plan, changes.values.toList(), claims, effects,
        plan.mustCommitBeforeEpochMillis?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) }, itemVersions, stockReadObservations(current))
}

internal fun stockValuePageId(poolId: DomainId, creationGeneration: Revision, ordinal: Int, digest: String): DomainId =
    localStockId("value-page", "${poolId.value}/${creationGeneration.value}/$ordinal/$digest")

/** Complete normalized read dependencies; no branch-global serialized stock snapshot. */
internal fun stockReadObservations(current: LocalStockLoaded): List<StockObservedWire> {
    val rows = current.items.values.filterNotNull().map { it.row to it.envelope } +
        current.positions.values.map { it.row to it.envelope } + current.pools.values.map { it.record.row to it.record.envelope } +
        current.reservations.values.map { it.row to it.envelope } + current.issues.values.map { it.row to it.envelope } +
        current.serials.values.map { it.row to it.envelope }
    return localStockFrozen(rows.map { (row, envelope) -> StockObservedWire(row.key.kind, row.key.id, envelope.producer.operationId, row.revision, row.digest.value) }
        .sortedWith(compareBy<StockObservedWire> { it.kind }.thenBy { it.id.value }))
}
