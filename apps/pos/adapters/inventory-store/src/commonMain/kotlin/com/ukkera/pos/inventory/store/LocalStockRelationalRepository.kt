package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandFrameV2
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RetainedStreamedCommand
import com.ukkera.pos.database.StockIndexRecordV2
import com.ukkera.pos.database.StockPageCursor
import com.ukkera.pos.database.StockQueryV2
import com.ukkera.pos.database.StockRelationalAccess
import com.ukkera.pos.database.StockRelationalKindsV2
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StreamedCommandSink
import com.ukkera.pos.database.StreamedSourceObservationV2
import com.ukkera.pos.database.requireStockRelational
import com.ukkera.pos.inventory.InventoryHoldRecord
import com.ukkera.pos.inventory.InventoryAllocationRecord
import com.ukkera.pos.inventory.InventoryMembershipStream
import com.ukkera.pos.inventory.InventoryPoolProjection
import com.ukkera.pos.inventory.InventoryPositionAvailability
import com.ukkera.pos.inventory.InventoryRelationalPlanner
import com.ukkera.pos.inventory.InventoryReservationHeader
import com.ukkera.pos.inventory.InventoryIssueSlice
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Part of LocalStockRepository's one facade, not a second stock database/service. */
internal class LocalStockRelationalRepository(
    internal val facade: LocalStockRepository,
    verifiers: List<LocalStockRelationalCompositeVerifier>,
) {
    private val producers: Map<CommandKey, LocalStockRelationalCompositeVerifier>
    init {
        val pairs = verifiers.flatMap { verifier -> verifier.commands.toSet().map { it to verifier } }
        localStockCheck(pairs.map { it.first }.distinct().size == pairs.size && pairs.all {
            it.first == LocalStockRelationalProfileV2.FOOD_COMMAND || it.first == LocalStockRelationalProfileV2.COLLECTION_COMMAND ||
                it.first == LocalStockRelationalProfileV2.RETURN_COMMAND
        })
        producers = pairs.toMap()
    }
    fun hasProducer(command: CommandKey): Boolean = command in producers
    suspend fun open(view: CommandReadView, sink: StreamedCommandSink? = null, active: Boolean = true): LocalStockRelationalRead {
        val result = LocalStockRelationalRead(this, view, view.requireStockRelational(), sink)
        if (active) result.requireActivated()
        return result
    }
    suspend fun requireNonstockSource(view: CommandReadView, owner: RetainedStreamedCommand, original: RetainedOriginalV2,
        source: StreamedSourceObservationV2.Aggregate) {
        (producers[owner.identity.producer.command] ?: unavailableProducer()).requireOriginalSource(view, original, source)
    }
    /** Register an actual required original, never a reconstructed historical read facade. */
    suspend fun requireCompleteOriginal(owner: RetainedStreamedCommand,
        actual: RetainedStreamedCommand, traversal: StockOriginalTraversal) {
        val previous = (traversal.original(owner, actual.identity.producer.operationId) as? RetainedOriginalV2.Streamed)?.evidence
            ?: unavailableProducer()
        traversal.requireSame(previous, actual)
        traversal.requireComplete(owner, previous)
    }
    suspend fun requireComplete(original: RetainedStreamedCommand): LocalStockRelationalInspection =
        inspectComplete(original, retainCompleted = false).first

    /** Literal inspections escape only after the normally CLOSED entire graph. The private
     * read scope may retain them alongside their exact immutable originals, never a pass
     * supplied by the caller or data from an incompletely qualified ancestor. */
    suspend fun requireCompleteRetainingInspections(original: RetainedStreamedCommand):
        Pair<LocalStockRelationalInspection, List<LocalStockRelationalInspection>> =
        inspectComplete(original, retainCompleted = true)

    private suspend fun inspectComplete(original: RetainedStreamedCommand, retainCompleted: Boolean):
        Pair<LocalStockRelationalInspection, List<LocalStockRelationalInspection>> {
        val traversal = StockOriginalTraversal(original)
        var result: LocalStockRelationalInspection? = null
        val completed = mutableListOf<LocalStockRelationalInspection>()
        try {
            traversal.begin()
            while (true) {
                val required = traversal.next() ?: break
                val inspection = requireOneComplete(required, traversal)
                if (required === original) result = inspection
                traversal.complete(required)
                if (retainCompleted) completed += inspection
            }
            // No inspection may be reused while any queued ancestor, cycle or resource
            // closure remains unchecked. Closing invalidates the traversal's lookup API.
            traversal.close()
            return (result ?: inconsistentStock()) to completed
        } catch (failure: Throwable) {
            completed.clear()
            traversal.fail()
            throw failure
        }
    }
    private suspend fun requireOneComplete(original: RetainedStreamedCommand,
        traversal: StockOriginalTraversal): LocalStockRelationalInspection {
        val inspection = requireStockRelationalPosting(original, this, traversal)
        if (original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND) {
            when (val request = LocalStockRelationalCommandsV2.decode(original.normalized)) {
                is LocalStockRelationalCommand.InitializeFresh -> requireFreshStockProducer(original, inspection, request)
                is LocalStockRelationalCommand.Receive, is LocalStockRelationalCommand.ReceivePo ->
                    requireReceivingStockProducer(original, inspection, facade.receivingAuthority(), request)
                is LocalStockRelationalCommand.Inspect ->
                    requireInspectionStockProducer(original, inspection, facade.receivingAuthority(), request)
                is LocalStockRelationalCommand.CaptureCount, is LocalStockRelationalCommand.PostCount ->
                    requireCountStockProducer(original, inspection, facade.receivingAuthority(), request)
                is LocalStockRelationalCommand.Transfer ->
                    requireTransferStockProducer(original, inspection, facade.receivingAuthority(), request)
                is LocalStockRelationalCommand.Recall, is LocalStockRelationalCommand.Expire -> {
                    requireStockRelationalTransitions(original, inspection)
                    requireLocalStockRelationalProducer(original, inspection)
                }
            }
        } else {
            requireStockRelationalTransitions(original, inspection)
            (producers[original.identity.producer.command] ?: throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE))
                .requireComplete(original, inspection)
        }
        localStockCheck(inspection.resourceClosureChecked, LocalStockFailure.STORAGE_INCONSISTENT)
        return inspection
    }
}

/**
 * One closed, loan-bound complete-producer traversal. Required originals are processed by the
 * repository's iterative queue, never by recursive commerce callbacks. Only exact genuine
 * identities may share one completion inside this proof. A private protected read scope may
 * retain exact immutable identities only after the WHOLE graph closes normally; no current
 * rows or grants are retained. The commerce request envelope (4096 originals / 64 MiB) is cumulative
 * over the whole mixed legacy/normalized subtree, not freshly available at each handoff.
 * Physical stock frames remain streamed and are not converted into a lot/holder/row cap.
 */
internal class StockOriginalTraversal(root: RetainedStreamedCommand) {
    private val scope = root.identity.scope
    // Only begin needs the root handle. Closed ancestor inspections must not keep
    // a different, potentially unretained root alive outside the read-scope budget.
    private var initialRoot: RetainedStreamedCommand? = root
    private class Required(val original: RetainedStreamedCommand) {
        val dependencies = mutableSetOf<OperationId>()
        var complete = false
    }
    private val required = linkedMapOf<OperationId, Required>()
    private val pending = ArrayDeque<Required>()
    private var current: Required? = null
    private var open = false
    private var closed = false
    private var calls = 0
    private var bytes = 0L

    suspend fun begin() {
        localStockCheck(!open && !closed && required.isEmpty())
        open = true
        val root = initialRoot ?: inconsistentStock()
        call()
        charge(root)
        val first = Required(root)
        required[root.identity.producer.operationId] = first
        pending.addLast(first)
        initialRoot = null
    }
    fun next(): RetainedStreamedCommand? {
        localStockCheck(open && !closed && current == null)
        val next = pending.removeFirstOrNull() ?: return null
        localStockCheck(!next.complete)
        current = next
        return next.original
    }
    fun complete(original: RetainedStreamedCommand) {
        val node = current ?: inconsistentStock()
        localStockCheck(open && !closed && node.original === original && !node.complete)
        node.complete = true
        current = null
    }
    fun close() {
        localStockCheck(open && !closed && current == null && pending.isEmpty() && required.values.all { it.complete })
        clear()
    }
    fun fail() { clear() }
    private fun clear() {
        open = false; closed = true; initialRoot = null; current = null; required.clear(); pending.clear()
    }
    private fun requireOwner(value: RetainedStreamedCommand): Required {
        val node = current ?: inconsistentStock()
        localStockCheck(open && !closed && node.original === value)
        return node
    }
    /** Every lookup is a real owner.original traversal on the same active core loan. */
    suspend fun original(owner: RetainedStreamedCommand, id: OperationId): RetainedOriginalV2? {
        requireOwner(owner)
        call()
        localStockCheck(id != owner.identity.producer.operationId)
        val actual = owner.original(id)
        when (actual) {
            is RetainedOriginalV2.Legacy -> {
                val value = actual.evidence
                localStockCheck(value.scope == scope && value.operationId == id &&
                    (value.assignmentId != owner.identity.assignmentId || value.authorityEpoch != owner.identity.authorityEpoch ||
                        value.sequence < owner.identity.sequence))
                data(8_192L + value.normalized.size + value.result.size)
                for (write in value.aggregateWrites) data(write.payload.size.toLong() + 512)
                for (claim in value.claimChanges) data(128L + claim.key.namespace.length + claim.key.key.length)
                for (job in value.jobs) data(job.body.size.toLong() + 128)
            }
            is RetainedOriginalV2.Streamed -> {
                val value = actual.evidence
                localStockCheck(value.identity.scope == scope && value.identity.producer.operationId == id &&
                    (value.identity.assignmentId != owner.identity.assignmentId || value.identity.authorityEpoch != owner.identity.authorityEpoch ||
                        value.identity.sequence < owner.identity.sequence))
                charge(value)
            }
            null -> data(512)
        }
        return actual
    }
    fun requireSame(left: RetainedStreamedCommand, right: RetainedStreamedCommand) {
        localStockCheck(left.identity == right.identity && left.journalHash == right.journalHash &&
            left.normalized == right.normalized && left.result == right.result)
    }
    fun requireComplete(owner: RetainedStreamedCommand, original: RetainedStreamedCommand) {
        val from = requireOwner(owner)
        val id = original.identity.producer.operationId
        val ownerId = owner.identity.producer.operationId
        localStockCheck(id != ownerId && original.identity.scope == scope)
        val existing = required[id]
        if (existing != null) requireSame(existing.original, original)
        // A complete node may later acquire an incoming edge; detect cycles across the WHOLE
        // required graph, not only the current queue item or a favorable already-seen prefix.
        localStockCheck(!reaches(id, ownerId))
        from.dependencies += id
        if (existing == null) {
            val next = Required(original)
            required[id] = next
            pending.addLast(next)
        }
    }
    private fun reaches(start: OperationId, target: OperationId): Boolean {
        val visited = mutableSetOf<OperationId>()
        val queue = ArrayDeque<OperationId>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == target) return true
            if (visited.add(id)) required[id]?.dependencies?.forEach { queue.addLast(it) }
        }
        return false
    }
    private fun call() {
        if (calls >= 4_096) throw LocalStockHydrationCapacityUnavailable()
        calls++
    }
    private fun data(amount: Long) {
        localStockCheck(amount >= 0)
        if (amount > 64L * 1_024 * 1_024 - bytes) throw LocalStockHydrationCapacityUnavailable()
        bytes += amount
    }
    private suspend fun charge(original: RetainedStreamedCommand) {
        data(512L + original.normalized.size + original.result.size)
        original.frames { frame -> when (frame) {
            is RetainedCommandFrameV2.Write -> if (frame.value.key.kind !in StockRelationalKindsV2.all)
                data(frame.value.payload.size.toLong() + 512)
            is RetainedCommandFrameV2.Claim -> if (frame.value.key.namespace !in stockOwnedClaimNamespaces)
                data(128L + frame.value.key.namespace.length + frame.value.key.key.length)
            is RetainedCommandFrameV2.Job -> data(frame.value.body.size.toLong() + 128)
            is RetainedCommandFrameV2.Effect -> if (frame.value.kind != LocalStockRelationalProfileV2.EFFECT_KIND &&
                frame.value.kind != STOCK_ACTION_EFFECT_V2) data(frame.value.payload.size.toLong() + 128)
            is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
                is StreamedSourceObservationV2.Aggregate -> if (source.key.kind !in StockRelationalKindsV2.all)
                    data(source.payload.size.toLong() + 512)
                is StreamedSourceObservationV2.Current -> data(source.payload.size.toLong() + 512)
                is StreamedSourceObservationV2.CurrentAbsent -> data(256)
                is StreamedSourceObservationV2.StockItemAbsent -> inconsistentStock()
                is StreamedSourceObservationV2.Claim -> if (source.key.namespace !in stockOwnedClaimNamespaces)
                    data(128L + source.key.namespace.length + source.key.key.length)
                else -> Unit
            }
            else -> Unit
        } }
    }
}

/** Completed immutable producer proofs within ONE explicit protected stock read. Actual
 * originals, current rows/claims/ranges and selected writes are still read on the
 * same active loan. This is private bookkeeping, never a supplied producer pass.
 * The bounded optimization falls back to full proof rather than adding a row cap. */
private class StockReadProducerProof(private val repository: LocalStockRelationalRepository) {
    private val completed = linkedMapOf<OperationId, RetainedStreamedCommand>()
    private val inspections = linkedMapOf<OperationId, LocalStockRelationalInspection>()
    private var bytes = 0L
    private var open = true

    suspend fun requireComplete(original: RetainedStreamedCommand) {
        localStockCheck(open)
        val previous = completed[original.identity.producer.operationId]
        if (previous != null) {
            localStockCheck(previous.identity == original.identity && previous.journalHash == original.journalHash &&
                previous.normalized == original.normalized && previous.result == original.result,
                LocalStockFailure.STORAGE_INCONSISTENT)
            return
        }
        inspectComplete(original)
    }

    suspend fun inspectComplete(original: RetainedStreamedCommand): LocalStockRelationalInspection {
        localStockCheck(open)
        val id = original.identity.producer.operationId
        val previous = completed[id]
        if (previous != null) {
            localStockCheck(previous.identity == original.identity && previous.journalHash == original.journalHash &&
                previous.normalized == original.normalized && previous.result == original.result,
                LocalStockFailure.STORAGE_INCONSISTENT)
            inspections[id]?.let { return it }
        }
        // Keep every eligible literal inspection from this normally CLOSED graph. A
        // later ancestor request otherwise has a completed identity but repeats the full
        // graph just to obtain the inspection. Actual lookups/direct sources stay below
        // in the read facade, regardless of which closed inspection is retained here.
        val (inspection, closed) = repository.requireCompleteRetainingInspections(original)
        localStockCheck(open)
        for (value in closed) {
            val evidence = value.original
            val operationId = evidence.identity.producer.operationId
            val previous = completed[operationId]
            if (previous != null) {
                localStockCheck(previous.identity == evidence.identity && previous.journalHash == evidence.journalHash &&
                    previous.normalized == evidence.normalized && previous.result == evidence.result,
                    LocalStockFailure.STORAGE_INCONSISTENT)
                inspections[operationId] = value
            } else {
                val size = 512L + evidence.normalized.size + evidence.result.size
                if (completed.size < 128 && size <= 8L * 1_024 * 1_024 - bytes) {
                    completed[operationId] = evidence
                    inspections[operationId] = value
                    bytes += size
                }
            }
        }
        return inspection
    }

    fun close() { open = false; inspections.clear(); completed.clear(); bytes = 0L }
}

/** Original-only facade for one protected scope, never a current-stock reader or grant. */
public class LocalStockOriginalRead internal constructor(
    private val repository: LocalStockRepository,
    private val read: LocalStockRelationalRead,
) {
    private var open = true
    private var reading = false

    /** Reopen the real original, match all identity/body fields and recheck its direct
     * sources. Only completed immutable proof may be shared on this exact core loan. */
    public suspend fun requireProducer(view: CommandReadView, stock: LocalStockRepository,
        original: RetainedStreamedCommand): LocalStockRelationalInspection {
        localStockCheck(open && !reading && stock === repository && view.requireStockRelational() === read.access)
        reading = true
        try { return read.requireOriginalProducer(original).also { localStockCheck(open) } }
        finally { reading = false }
    }

    internal fun close() { open = false }
}

/**
 * Read facade confined by the actual core loan on EVERY access. No adapter-owned validation
 * cache survives invalidation/exit: each original is traversed on the active loan. One explicit
 * protected stock read may share already-complete immutable producer proof; it retains no
 * current-row, claim, range or selected-write result and clears every entry before returning.
 */
public class LocalStockRelationalRead internal constructor(
    private val repository: LocalStockRelationalRepository,
    internal val view: CommandReadView,
    internal val access: StockRelationalAccess,
    internal val sink: StreamedCommandSink?,
) {
    public val scope: BranchScope get() = view.scope
    private var stockReadProducerProof: StockReadProducerProof? = null

    internal suspend fun <T> withCompletedProducerProof(block: suspend () -> T): T {
        localStockCheck(stockReadProducerProof == null)
        val proof = StockReadProducerProof(repository)
        stockReadProducerProof = proof
        try { return block() }
        finally { stockReadProducerProof = null; proof.close() }
    }

    private suspend fun requireCompleteProducer(original: RetainedStreamedCommand) {
        val proof = stockReadProducerProof
        if (proof == null) repository.requireComplete(original) else proof.requireComplete(original)
    }

    /** Read-only alias sink for the bounded payment reader; never an acceptance predicate. */
    internal var paymentResourceObserver: (suspend (RecoveryResourceKey) -> Unit)? = null

    internal suspend fun requireActivated() { requireNormalizedStockCurrent(this) }

    /** Scalar current stock generation, not catalog revision. Null is only an actual new SKU
     * identity after permanent-claim agreement, never a fabricated zero stocked quantity. */
    public suspend fun itemVersion(skuId: DomainId): LocalStockItemVersion {
        val row = record(LocalStockRelationalProfileV2.itemKey(skuId))
        localStockCheck(claim(LocalStockStoreProfile.itemClaim(skuId)) == row?.key?.id)
        localStockCheck(row == null || row.body is StockRelationalBody.Item)
        return LocalStockItemVersion(skuId, row?.generation)
    }

    public suspend fun position(key: StockPositionKey): StockPosition? {
        val row = record(LocalStockRelationalProfileV2.positionKey(key)) ?: return null
        val position = (row.body as? StockRelationalBody.Position)?.value ?: inconsistentStock()
        requireRelationalStockSerialPosition(this, repository.facade, position)
        return position
    }

    public suspend fun pool(key: StockPoolKey): InventoryPoolProjection? =
        record(LocalStockRelationalProfileV2.poolKey(key))?.let { (it.body as? StockRelationalBody.Pool)?.value ?: inconsistentStock() }

    public suspend fun reservation(id: DomainId): InventoryReservationHeader? =
        reservationWithMembers(id, { _, _ -> }, { })

    /**
     * One actual header and complete member range on this loan. Consumers see checked members,
     * but only normal return proves both terminal counts. No proof or row cache is retained.
     */
    public suspend fun reservationWithMembers(id: DomainId,
        allocation: suspend (InventoryReservationHeader, InventoryAllocationRecord) -> Unit,
        hold: suspend (InventoryHoldRecord) -> Unit): InventoryReservationHeader? {
        val source = reservationRecord(id) ?: return null
        val header = (source.body as StockRelationalBody.Reservation).value
        reservationMembers(source, { allocation(header, it) }, hold)
        return header
    }

    public suspend fun issue(id: DomainId): LocalStockIssueHeader? {
        val record = record(LocalStockRelationalProfileV2.issueKey(id)) ?: return null
        val issue = (record.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
        localStockCheck(claim(LocalStockStoreProfile.sourceClaim(id)) == id, LocalStockFailure.STORAGE_INCONSISTENT)
        var count = 0L
        var total = com.ukkera.pos.types.Money(issue.originalValue.currency, 0)
        issueAllocations(id) { slice -> count = stockCheckedAdd(count, 1); total += slice.originalValue }
        localStockCheck(count == issue.allocationCount && total == issue.originalValue, LocalStockFailure.STORAGE_INCONSISTENT)
        if (issue.purpose == com.ukkera.pos.inventory.ValueIssuePurpose.RETAIL)
            requireStockRetailIssueHistory(stockHistoryAccess(this), id)
        return issue
    }

    /** Immutable first issue producer, not the later kitchen acknowledgement row producer. */
    public suspend fun issueOriginal(id: DomainId): LocalStockIssueOriginal? {
        val current = issue(id) ?: return null
        var producer: com.ukkera.pos.database.StreamedProducerIdentityV2? = null
        query(StockQueryV2.IssueValues(id)) { index ->
            val row = record(index.identity.key) ?: inconsistentStock()
            val event = row.body as? StockRelationalBody.ValueEvent
            if (event?.event == StockValueEventKind.ISSUE) {
                localStockCheck(event.issueId == id && event.sourceId == id && row.generation == com.ukkera.pos.types.Revision(1) && row.previous == null &&
                    (producer == null || producer == row.producer), LocalStockFailure.STORAGE_INCONSISTENT)
                producer = row.producer
            }
        }
        val first = original((producer ?: inconsistentStock()).operationId)
        localStockCheck(first.identity.producer == producer)
        return stockIssueOriginal(first, id, current)
    }

    public suspend fun allocations(reservationId: DomainId, consume: suspend (InventoryAllocationRecord) -> Unit) {
        reservationMembers(reservationId, consume, { })
    }

    /** Complete actual per-reservation reasons, with both member counts and permanent claims checked. */
    public suspend fun holds(reservationId: DomainId, consume: suspend (InventoryHoldRecord) -> Unit) {
        reservationMembers(reservationId, { }, consume)
    }

    private suspend fun reservationMembers(reservationId: DomainId,
        allocation: suspend (InventoryAllocationRecord) -> Unit, hold: suspend (InventoryHoldRecord) -> Unit) {
        val source = reservationRecord(reservationId) ?: missingStock()
        reservationMembers(source, allocation, hold)
    }

    private suspend fun reservationMembers(source: StockRelationalRecordV2,
        allocation: suspend (InventoryAllocationRecord) -> Unit, hold: suspend (InventoryHoldRecord) -> Unit) {
        val header = (source.body as StockRelationalBody.Reservation).value
        val reservationId = header.id
        var allocations = 0L; var holds = 0L
        query(StockQueryV2.ReservationMembers(reservationId)) { index ->
            val record = record(index.identity.key) ?: inconsistentStock()
            when (val body = record.body) {
                is StockRelationalBody.Allocation -> {
                    requireAllocation(source.body, body.value)
                    allocations = stockCheckedAdd(allocations, 1); allocation(body.value)
                }
                is StockRelationalBody.Hold -> {
                    localStockCheck(body.value.reservationId == reservationId, LocalStockFailure.STORAGE_INCONSISTENT)
                    holds = stockCheckedAdd(holds, 1); hold(body.value)
                }
                else -> inconsistentStock()
            }
        }
        localStockCheck(allocations == header.allocationCount && holds == header.holdCount, LocalStockFailure.STORAGE_INCONSISTENT)
    }

    public suspend fun issueAllocations(issueId: DomainId, consume: suspend (InventoryIssueSlice) -> Unit) {
        query(StockQueryV2.IssueValues(issueId)) { index ->
            val record = record(index.identity.key) ?: inconsistentStock()
            when (val body = record.body) {
                is StockRelationalBody.IssueSlice -> {
                    localStockCheck(body.value.issueId == issueId, LocalStockFailure.STORAGE_INCONSISTENT)
                    consume(body.value)
                }
                is StockRelationalBody.ValueEvent -> {
                    localStockCheck(body.issueId == issueId)
                    if (body.event == StockValueEventKind.RESTORE)
                        localStockCheck(claim(LocalStockStoreProfile.sourceClaim(body.sourceId)) == body.sourceId)
                }
                is StockRelationalBody.ValueSlice -> localStockCheck(body.issueId == issueId)
                else -> inconsistentStock()
            }
        }
    }

    public suspend fun availability(position: StockPositionKey): InventoryPositionAvailability {
        val current = position(position) ?: missingStock()
        return InventoryRelationalPlanner.availability(current, InventoryMembershipStream { consume ->
            positionMembers(position) { header, allocation -> consume(header, allocation) }
        })
    }

    /** Complete selected-position memberships; unrelated allocation positions are not hydrated. */
    internal suspend fun positionMembers(position: StockPositionKey,
        consume: suspend (InventoryReservationHeader, InventoryAllocationRecord) -> Unit) {
        query(StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(position).id)) { index ->
            val row = record(index.identity.key) ?: inconsistentStock()
            when (val body = row.body) {
                is StockRelationalBody.Allocation -> {
                    localStockCheck(body.value.allocation.position == position, LocalStockFailure.STORAGE_INCONSISTENT)
                    val reservation = reservationRecord(body.value.reservationId) ?: inconsistentStock()
                    val header = (reservation.body as StockRelationalBody.Reservation).value
                    requireAllocation(reservation.body, body.value)
                    // This checks counts and literal records only. It never recursively loads
                    // other SKU positions/pools just because this owner also allocated there.
                    requireMembership(header)
                    consume(header, body.value)
                }
                is StockRelationalBody.Hold -> localStockCheck(body.value.hold.position == position)
                else -> inconsistentStock()
            }
        }
    }

    internal suspend fun reservationRecord(id: DomainId): StockRelationalRecordV2? {
        val record = record(LocalStockRelationalProfileV2.reservationKey(id))
        val owner = claim(LocalStockStoreProfile.reservationClaim(id))
        localStockCheck(if (record == null) owner == null else owner == id && record.body is StockRelationalBody.Reservation,
            LocalStockFailure.STORAGE_INCONSISTENT)
        return record
    }

    private suspend fun requireMembership(header: InventoryReservationHeader) { allocations(header.id) { /* Complete iteration, no favorable subset. */ } }

    private suspend fun requireAllocation(reservation: StockRelationalBody, value: InventoryAllocationRecord) {
        val header = reservation as? StockRelationalBody.Reservation ?: inconsistentStock()
        localStockCheck(value.reservationId == header.value.id && value.allocation.allocationId == LocalStockRelationalProfileV2.allocationId(
            header.allocationSetId, header.allocationSetRevision, value.allocation.lineId, value.allocation.position) &&
            claim(LocalStockStoreProfile.allocationClaim(value.allocation.allocationId)) == value.reservationId, LocalStockFailure.STORAGE_INCONSISTENT)
    }

    internal suspend fun query(query: StockQueryV2, consume: suspend (StockIndexRecordV2) -> Unit) {
        val output = sink
        if (output != null) { output.stockRange(access, query, consume); return }
        access.completeRange(query, consume)
    }

    internal suspend fun all(kind: String, consume: suspend (StockRelationalRecordV2) -> Unit) {
        var after: DomainId? = null
        while (true) {
            val page = access.keys(kind, after)
            for (key in page) { consume(record(key) ?: inconsistentStock()); after = key.id }
            // A full page requires the next request, including an explicit empty terminal page.
            if (page.size < 128) return
        }
    }

    internal suspend fun claim(key: ClaimKey): DomainId? {
        val owner = access.claim(key)
        sink?.source(StreamedSourceObservationV2.Claim(key, owner))
        return owner
    }

    internal suspend fun record(key: AggregateKey): StockRelationalRecordV2? {
        val row = access.aggregate(key) ?: return null
        val value = LocalStockRelationalCodecV2.decode(scope, row)
        val original = original(value.producer.operationId)
        localStockCheck(original.identity.producer == value.producer, LocalStockFailure.STORAGE_INCONSISTENT)
        requireOriginalWrite(original, value.key, value.generation, value.schema, value.payload, value.digest)
        sink?.source(StreamedSourceObservationV2.Aggregate(value.key, value.generation, value.schema, value.payload, value.digest, value.producer))
        resources(value)
        return value
    }

    /** Ordinary row readers also retain the CLOSED graph only through their own direct-source
     * pass. An enclosing projection keeps its existing scope; otherwise this call owns/clears
     * one in finally. Actual original lookups, selected writes and current reads are unchanged. */
    internal suspend fun original(operationId: OperationId): RetainedStreamedCommand =
        if (stockReadProducerProof == null) withCompletedProducerProof { qualifiedOriginal(operationId) }
        else qualifiedOriginal(operationId)

    private suspend fun qualifiedOriginal(operationId: OperationId): RetainedStreamedCommand {
        val value = (access.originals.read(operationId) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
        requireCompleteProducer(value)
        requireDirectSources(value)
        return value
    }

    /** Same original and direct-source qualification as original(), retaining the exact root
     * inspection. Its one-call scope shares only normally completed immutable ancestors;
     * actual direct sources and selected writes are still read/compared before scope exit.
     * It is literal projection DATA, never current authority or a supplied producer pass. */
    internal suspend fun originalWithInspection(operationId: OperationId): Pair<RetainedStreamedCommand, LocalStockRelationalInspection> =
        if (stockReadProducerProof == null) withCompletedProducerProof { inspectedOriginal(operationId) }
        else inspectedOriginal(operationId)

    private suspend fun inspectedOriginal(operationId: OperationId): Pair<RetainedStreamedCommand, LocalStockRelationalInspection> {
        val value = (access.originals.read(operationId) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
        val inspection = checkNotNull(stockReadProducerProof).inspectComplete(value)
        requireDirectSources(value)
        return value to inspection
    }

    internal suspend fun requireOriginalProducer(original: RetainedStreamedCommand): LocalStockRelationalInspection {
        val (actual, inspection) = originalWithInspection(original.identity.producer.operationId)
        localStockCheck(actual.identity == original.identity && actual.journalHash == original.journalHash &&
            actual.normalized == original.normalized && actual.result == original.result, LocalStockFailure.STORAGE_INCONSISTENT)
        return inspection
    }

    /** One genuine predecessor hop, processed in bounded batches of actual source
     * frames. Equal complete producer identities share one read/qualification inside
     * each batch; every selected literal write is still matched exactly once. No
     * source or proof survives this call, and no command-wide row cap is imposed. */
    private suspend fun requireDirectSources(original: RetainedStreamedCommand) {
        val sources = mutableListOf<StreamedSourceObservationV2.Aggregate>()
        var bytes = 0L
        suspend fun flush() {
            for ((identity, selected) in sources.groupBy { it.producer }) {
                localStockCheck(identity.operationId != original.identity.producer.operationId,
                    LocalStockFailure.STORAGE_INCONSISTENT)
                val producer = access.originals.read(identity.operationId) ?: unavailableProducer()
                fun legacyStockKind(source: StreamedSourceObservationV2.Aggregate): Boolean =
                    source.key.kind in LocalStockStoreProfile.inventoryKinds || source.key.kind in setOf(
                        LocalStockStoreProfile.RECEIPT_DOCUMENT, LocalStockStoreProfile.ALLOWANCE, LocalStockStoreProfile.INSPECTION,
                        LocalStockStoreProfile.COUNT_DOCUMENT, LocalStockStoreProfile.TRANSFER_DOCUMENT)
                when (producer) {
                    is RetainedOriginalV2.Streamed -> {
                        val old = producer.evidence
                        localStockCheck(old.identity.scope == scope && old.identity.producer == identity &&
                            (old.identity.assignmentId != original.identity.assignmentId || old.identity.authorityEpoch != original.identity.authorityEpoch ||
                                old.identity.sequence < original.identity.sequence), LocalStockFailure.STORAGE_INCONSISTENT)
                        requireDirectOriginalWrites(old, selected)
                        // Complete normalized stock + commerce qualification stays mandatory.
                        requireCompleteProducer(old)
                    }
                    is RetainedOriginalV2.Legacy -> {
                        val old = producer.evidence
                        localStockCheck(old.scope == scope && old.committed && old.operationId == identity.operationId && old.command == identity.command &&
                            old.originalActorId == identity.originalActorId && old.semanticDigest == identity.semanticDigest &&
                            (old.assignmentId != original.identity.assignmentId || old.authorityEpoch != original.identity.authorityEpoch ||
                                old.sequence < original.identity.sequence), LocalStockFailure.STORAGE_INCONSISTENT)
                        for (source in selected) {
                            val write = old.aggregateWrites.singleOrNull { it.key == source.key } ?: inconsistentStock()
                            requireSameWrite(write, source.key, source.generation, source.schema, source.payload, source.payloadHash)
                            localStockCheck(source.key.kind !in StockRelationalKindsV2.all, LocalStockFailure.STORAGE_INCONSISTENT)
                        }
                        if (selected.any(::legacyStockKind)) repository.facade.requireProducer(view, old)
                    }
                }
                for (source in selected) if (source.key.kind !in StockRelationalKindsV2.all && !legacyStockKind(source))
                    repository.requireNonstockSource(view, original, producer, source)
            }
            sources.clear(); bytes = 0L
        }
        original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate ?: return@frames
            // Oversized single frames keep their existing envelope rather than becoming
            // an artificial row refusal; the following frame first drains that singleton.
            val size = source.payload.size.toLong()
            if (sources.isNotEmpty() && (sources.size == 16 || bytes + size > 1_048_576L)) flush()
            sources += source; bytes += size
        }
        flush()
    }

    internal suspend fun resources(record: StockRelationalRecordV2) = resources(record.key, record.body)

    internal suspend fun resources(key: AggregateKey, body: StockRelationalBody) {
        val output = sink
        val observer = paymentResourceObserver
        if (output == null && observer == null) return
        stockResourceAliases(key, body) { output?.resource(it); observer?.invoke(it) }
    }
}

/** A finite batch may repeat an identical source observation. Its producer still
 * has exactly one matching write per selected key; contradictory observations,
 * missing writes and duplicate producer writes all refuse. */
private suspend fun requireDirectOriginalWrites(original: RetainedStreamedCommand,
    sources: List<StreamedSourceObservationV2.Aggregate>) {
    localStockCheck(sources.isNotEmpty() && sources.size <= 16)
    val selected = linkedMapOf<AggregateKey, StreamedSourceObservationV2.Aggregate>()
    for (source in sources) {
        val prior = selected.put(source.key, source)
        localStockCheck(prior == null || prior == source, LocalStockFailure.STORAGE_INCONSISTENT)
    }
    val found = mutableSetOf<AggregateKey>()
    original.frames { frame ->
        val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
        val source = selected[write.key] ?: return@frames
        localStockCheck(found.add(write.key), LocalStockFailure.STORAGE_INCONSISTENT)
        requireSameWrite(write, source.key, source.generation, source.schema, source.payload, source.payloadHash)
    }
    localStockCheck(found == selected.keys, LocalStockFailure.STORAGE_INCONSISTENT)
}

internal suspend fun requireOriginalWrite(original: RetainedStreamedCommand, key: AggregateKey, generation: com.ukkera.pos.types.Revision,
    schema: String, payload: com.ukkera.pos.database.StoredBytes, digest: com.ukkera.pos.security.PayloadDigest) {
    var found = false
    original.frames { frame ->
        val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
        if (write.key == key) { localStockCheck(!found); requireSameWrite(write, key, generation, schema, payload, digest); found = true }
    }
    localStockCheck(found, LocalStockFailure.STORAGE_INCONSISTENT)
}

internal fun requireSameWrite(write: RetainedAggregateWrite, key: AggregateKey, generation: com.ukkera.pos.types.Revision, schema: String,
    payload: com.ukkera.pos.database.StoredBytes, digest: com.ukkera.pos.security.PayloadDigest) {
    localStockCheck(write.key == key && write.storageGeneration == generation && write.schema == schema && write.payload == payload && write.payloadDigest == digest,
        LocalStockFailure.STORAGE_INCONSISTENT)
}
internal fun missingStock(): Nothing = throw LocalStockRejected(LocalStockFailure.STOCK_MISSING)
internal fun inconsistentStock(): Nothing = throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
internal fun unavailableProducer(): Nothing = throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)

/** Canonical row-derived alias union, emitted as a stream rather than a whole-stock Set. */
internal suspend fun stockResourceAliases(key: AggregateKey, body: StockRelationalBody,
    consume: suspend (RecoveryResourceKey) -> Unit) {
    suspend fun add(kind: RecoveryResourceKind, id: DomainId) { consume(RecoveryResourceKey(kind, id)) }
    suspend fun pool(key: StockPoolKey) {
        add(RecoveryResourceKind.STOCK, key.skuId); add(RecoveryResourceKind.STOCK, key.warehouseId)
        add(RecoveryResourceKind.STOCK, LocalStockRelationalProfileV2.poolKey(key).id)
    }
    suspend fun position(value: StockPositionKey) {
        pool(value.pool); add(RecoveryResourceKind.STOCK, LocalStockRelationalProfileV2.positionKey(value).id)
        value.lotId?.let { add(RecoveryResourceKind.STOCK, it) }; value.serialId?.let { add(RecoveryResourceKind.STOCK, it) }
    }
    suspend fun binding(value: com.ukkera.pos.inventory.CollectionBinding) {
        add(RecoveryResourceKind.ORDER_CUSTODY, value.orderId); add(RecoveryResourceKind.ORDER_CUSTODY, value.collectionId)
        add(RecoveryResourceKind.AUTHORIZATION, value.originalActorId)
    }
    add(RecoveryResourceKind.STOCK, key.id)
    when (body) {
        is StockRelationalBody.Item -> Unit
        is StockRelationalBody.Position -> { position(body.value.key); body.value.serialRegistrationId?.let { add(RecoveryResourceKind.AUTHORIZATION, it) } }
        is StockRelationalBody.Pool -> pool(body.value.key)
        is StockRelationalBody.Reservation -> binding(body.value.binding)
        is StockRelationalBody.Allocation -> { position(body.value.allocation.position); add(RecoveryResourceKind.STOCK, body.value.reservationId) }
        is StockRelationalBody.Hold -> { body.value.hold.position?.let { position(it) }; add(RecoveryResourceKind.STOCK, body.value.reservationId) }
        is StockRelationalBody.Issue -> {
            binding(body.value.binding); add(RecoveryResourceKind.STOCK, body.value.reservationId)
            body.value.recipeSnapshotId?.let { add(RecoveryResourceKind.KITCHEN_CUSTODY, it) }
            body.value.requiredStationIds.forEach { add(RecoveryResourceKind.KITCHEN_CUSTODY, it) }
        }
        is StockRelationalBody.IssueSlice -> { position(body.value.allocation.position); add(RecoveryResourceKind.STOCK, body.value.issueId) }
        is StockRelationalBody.ValueEvent -> {
            pool(body.pool); add(RecoveryResourceKind.STOCK, body.sourceId); body.issueId?.let { add(RecoveryResourceKind.STOCK, it) }
        }
        is StockRelationalBody.ValueSlice -> {
            pool(body.pool); add(RecoveryResourceKind.STOCK, body.sourceId); add(RecoveryResourceKind.STOCK, body.eventId)
            body.issueId?.let { add(RecoveryResourceKind.STOCK, it) }
        }
        is StockRelationalBody.ValueTarget -> { pool(body.pool); add(RecoveryResourceKind.STOCK, body.sourceId); add(RecoveryResourceKind.STOCK, body.originalEventId) }
        is StockRelationalBody.TransferEdge -> { pool(body.pool); pool(body.linkedPool); add(RecoveryResourceKind.STOCK, body.sourceId); add(RecoveryResourceKind.STOCK, body.linkedEventId) }
        is StockRelationalBody.Posting, is StockRelationalBody.Activation -> Unit
    }
}
