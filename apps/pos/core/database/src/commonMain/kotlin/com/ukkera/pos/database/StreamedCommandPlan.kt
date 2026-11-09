package com.ukkera.pos.database

import androidx.sqlite.SQLiteStatement
import com.ukkera.pos.recovery.RecoveryAdmissionPolicy
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.recovery.ResourceAdmissionDecision
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Installed closed NONPHYSICAL routes; neither request flags nor inheritance widen v1 Apply. */
public object StockStreamingCommandsV2 {
    public val STOCK: CommandKey = CommandKey("stock.normalized.write", 2)
    public val FOOD: CommandKey = CommandKey("sales.food.stock", 2)
    public val COLLECTION: CommandKey = CommandKey("sales.collection.stock", 2)
    public val RETURN: CommandKey = CommandKey("sales.return.stock", 1)
    public val all: Set<CommandKey> get() = setOf(STOCK, FOOD, COLLECTION, RETURN)
}

public interface MasterStreamingCommandHandler : MasterCommandHandler {
    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedStreamingMutation
}

/** The owning kernel dispatches propose() on its real stream; the synchronous fallback always refuses. */
public abstract class PreparedStreamingMutation : PreparedMutation() {
    final override public fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = stockSqlUnavailableV2()
    final override public fun physicalRecordingAdmission(authorization: AuthorizedAction, recordedAt: UtcInstant): PhysicalRecordingAdmission? = null
    public abstract suspend fun propose(authorization: AuthorizedAction, recordedAt: UtcInstant, sink: StreamedCommandSink): StreamedMutationDecision
    /** Conservative opt-in for a command whose normally deferred refusal has a closed
     * native history profile. This is not historical proof or permission to retain a prefix. */
    public open fun supportsRecordedRejection(rejection: StreamedMutationDecision.Reject): Boolean = false
}

public sealed interface StreamedMutationDecision {
    public class Apply(public val result: StoredBytes, public val mustCommitBefore: UtcInstant? = null) : StreamedMutationDecision
    /** A rejected operation has NO persisted prefix. Owner discards the entire spool first. */
    public class Reject(public val code: String, public val result: StoredBytes) : StreamedMutationDecision {
        init { requireProtocolName(code); stockSqlRequireV2(code != "committed") }
    }
}

/** Non-authoritative, same-frame work areas. They cannot create a durable row or native right. */
public enum class StockWorkingSpace(internal val tag: Int) {
    SELECTION(1), DRAFT_RECORD(2), ALLOCATION(3), COST_SLICE(4), FRONTIER(5), MEMBERSHIP(6), MIGRATION(7), PRODUCER_CHECK(8),
}

public data class StockWorkingRecord(
    public val groupId: DomainId,
    public val recordId: DomainId,
    public val sortValue: Long,
    public val payload: StoredBytes,
)

/** Owner-created sink. Producer EOF is returning propose, NOT an adapter-callable seal. */
public class StreamedCommandSink internal constructor(internal val spool: TransactionPlanSpool) {
    public val scratch: StreamedStockScratch = StreamedStockScratch(spool)
    /** Same actual read loan and immutable command context; not a caller-issued capability. */
    public suspend fun requireContext(access: StockRelationalAccess, context: CommandContext) {
        spool.requireContext(access, context)
    }
    public suspend fun change(value: AggregateChange) { spool.change(value) }
    public suspend fun claim(value: ClaimChange) { spool.claim(value) }
    public suspend fun job(value: JobIntent) { spool.job(value) }
    public suspend fun source(value: StreamedSourceObservationV2) {
        // Only the owner can originate actual complete SQL ranges, never caller-provided coverage.
        stockSqlRequireV2(value !is StreamedSourceObservationV2.StockRange && value !is StreamedSourceObservationV2.StockMember &&
            value !is StreamedSourceObservationV2.EstateMember && value !is StreamedSourceObservationV2.EstateEnd)
        spool.append(RetainedCommandFrameV2.Source(value), allowIdentical = true)
    }
    public suspend fun stockRange(access: StockRelationalAccess, query: StockQueryV2,
        consume: suspend (StockIndexRecordV2) -> Unit) { spool.observeRange(access, query, consume) }
    /** Entire current/claim/original estate on THIS owner loan, never caller-enumerated coverage. */
    public suspend fun stockEstate(access: StockRelationalAccess, consume: suspend (StockEstateRecordV2) -> Unit): StreamedSourceObservationV2.EstateEnd =
        spool.observeEstate(access, consume)
    public suspend fun effect(value: StreamedEffectV2) { spool.append(RetainedCommandFrameV2.Effect(value)) }
    public suspend fun resource(value: RecoveryResourceKey) { spool.append(RetainedCommandFrameV2.Resource(value), allowIdentical = true) }
    /**
     * Irrevocably stops further producer inputs on this loan. NOT the kernel's validation seal,
     * EOF admission, writer phase, transaction commit or a permission to publish a result.
     * The owner still waits for propose() to RETURN and validates its exact returned result.
     */
    public suspend fun finishInputs(result: StoredBytes) { spool.finishInputs(result) }
    public suspend fun sectionSummary(section: StreamedSectionV2): StreamedSectionSummaryV2 {
        stockSqlRequireV2(section == StreamedSectionV2.SOURCES || section == StreamedSectionV2.EFFECTS)
        spool.requireBuilding()
        return spool.summary(section)
    }
    /** Read-only canonical replay of staged data, never publication or authority. */
    public suspend fun frames(section: StreamedSectionV2, consume: suspend (RetainedCommandFrameV2) -> Unit) {
        spool.requireBuilding(); spool.frames(section, consume)
    }
}

public class StreamedStockScratch internal constructor(private val spool: TransactionPlanSpool) {
    public suspend fun get(space: StockWorkingSpace, groupId: DomainId, recordId: DomainId): StockWorkingRecord? =
        spool.workingGet(space, groupId, recordId)
    public suspend fun insert(space: StockWorkingSpace, value: StockWorkingRecord) { spool.workingPut(space, value, replace = false) }
    public suspend fun put(space: StockWorkingSpace, value: StockWorkingRecord) { spool.workingPut(space, value, replace = true) }
    public suspend fun remove(space: StockWorkingSpace, groupId: DomainId, recordId: DomainId) { spool.workingRemove(space, groupId, recordId) }
    /** Group + ID order, or exact signed-Long remainder descending with ID tie-break. No REAL. */
    public suspend fun forEach(space: StockWorkingSpace, groupId: DomainId? = null, byRemainder: Boolean = false,
        consume: suspend (StockWorkingRecord) -> Unit) { spool.workingEach(space, groupId, byRemainder, consume) }
}

/** Internal joining input from the one actual kernel, including ALL installed physical denies. */
internal class StreamedPlanGuard(
    val store: AtomicMasterStore,
    val handler: MasterStreamingCommandHandler,
    val prepared: PreparedStreamingMutation,
    val context: CommandContext,
    val normalized: StoredBytes,
    val authorization: AuthorizedAction,
    val recordedAt: UtcInstant,
    val recovery: RecoveryAdmissionState,
    protectedKinds: Set<String>,
    protectedClaims: Set<String>,
    additionalResources: Set<RecoveryResourceKey>,
) {
    val protectedKinds: Set<String> = protectedKinds.toSet() + PhysicalRecordingProtectedEffects.kinds
    val protectedClaims: Set<String> = protectedClaims.toSet() + PhysicalRecordingProtectedEffects.claims
    val resources: Set<RecoveryResourceKey> = prepared.affectedResources + additionalResources
    fun requireNonphysical() {
        stockSqlRequireV2(context.key in StockStreamingCommandsV2.all && handler.key == context.key && store.scope == context.scope &&
            context.physicalRecording == null && context.physicalParticipants == null && handler.physicalRecordingAction(normalized) == null &&
            handler.reservedOperationBinding(normalized) == null && handler.physicalInteractiveAuthorizer == null &&
            handler.physicalRecordingKinds.isEmpty() && handler.physicalRecordingClaimNamespaces.isEmpty() &&
            handler.normalize(normalized) == normalized && semanticDigest(context.scope, context.originalActorId, context.key, normalized) == context.semanticDigest &&
            authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(prepared.requirement, context.originalActorId) &&
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId) in resources)
    }
}

/**
 * Entire operation TEMP spool, using ONLY StockSqlLoan's exact Transactor. No static active
 * holder/lot/history cap. At most 128 scalar metadata entries and one bounded row are hydrated.
 * The owner still performs operation result/audit/ordinal-zero upload/branch CAS/P4 child and
 * final native settlement. This helper never starts/commits a transaction or publishes success.
 */
internal class TransactionPlanSpool private constructor(
    internal val loan: StockSqlLoan,
    private val access: StockRelationalAccess,
    private val context: CommandContext,
) {
    private enum class Phase { BUILDING, INPUTS_CLOSED, SEALING, SEALED, INVALIDATED, WRITING, WRITTEN, CLEANED }
    private var phase: Phase = Phase.BUILDING
    private var claimedNames: Boolean = false
    private var jobCount = 0L
    private var guard: StreamedPlanGuard? = null
    private var decision: StreamedMutationDecision.Apply? = null
    private var closedResultHash: PayloadDigest? = null
    private val dao = StockRelationalDao(loan)
    internal val isSealed: Boolean get() = phase == Phase.INVALIDATED
    internal val sink: StreamedCommandSink = StreamedCommandSink(this)

    internal companion object {
        suspend fun create(access: StockRelationalAccess, context: CommandContext): TransactionPlanSpool {
            access.loan.requireBody()
            stockSqlRequireV2(context.scope == access.scope && context.key in StockStreamingCommandsV2.all)
            access.requireCensus()
            val spool = TransactionPlanSpool(access.loan, access, context)
            access.loan.attach(spool)
            spool.createScratch()
            return spool
        }
    }

    internal suspend fun discardByOwner() { loan.discardProposal(this) }

    internal suspend fun requireBuilding() { loan.requireBody(); stockSqlRequireV2(phase == Phase.BUILDING) }

    internal suspend fun requireContext(actualAccess: StockRelationalAccess, actualContext: CommandContext) {
        requireBuilding()
        stockSqlRequireV2(actualAccess === access && actualAccess.loan === loan && actualContext === context)
    }

    internal suspend fun change(change: AggregateChange) {
        requireBuilding()
        val generation = change.expectedRevision?.next() ?: Revision(1)
        append(RetainedCommandFrameV2.Write(RetainedAggregateWrite(change.key, generation, change.schema, change.payload,
            sha256(change.payload.copyBytes()))), expected = change.expectedRevision)
    }

    internal suspend fun claim(change: ClaimChange) {
        requireBuilding()
        append(RetainedCommandFrameV2.Claim(RetainedClaimChange(if (change is ClaimChange.Acquire) RetainedClaimAction.ACQUIRE else RetainedClaimAction.RELEASE,
            change.key, change.ownerId)))
    }

    internal suspend fun job(job: JobIntent) {
        requireBuilding()
        // Existing outbox domain-intent profile stays 32. Stock rows/claims are NOT charged here.
        stockSqlRequireV2(jobCount < MAX_JOBS)
        jobCount = stockCheckedAddV2(jobCount, 1)
        append(RetainedCommandFrameV2.Job(jobCount, RetainedJobIntent(job.kind, job.body)))
    }

    internal suspend fun append(frame: RetainedCommandFrameV2, expected: Revision? = null, allowIdentical: Boolean = false) {
        requireBuilding()
        appendRow(frame, expected, allowIdentical)
    }

    internal suspend fun finishInputs(result: StoredBytes) {
        requireBuilding()
        closedResultHash = sha256(result.copyBytes())
        phase = Phase.INPUTS_CLOSED
    }

    private suspend fun appendOwned(frame: RetainedCommandFrameV2) {
        loan.requireBody()
        stockSqlRequireV2(phase == Phase.SEALING && (frame is RetainedCommandFrameV2.Normalized ||
            frame is RetainedCommandFrameV2.Result || frame is RetainedCommandFrameV2.Resource))
        appendRow(frame, expected = null, allowIdentical = frame is RetainedCommandFrameV2.Resource)
    }

    private suspend fun appendRow(frame: RetainedCommandFrameV2, expected: Revision?, allowIdentical: Boolean) {
        val key = RetainedCommandFramesV2.key(frame)
        val bytes = RetainedCommandFramesV2.encode(frame)
        if (allowIdentical) {
            val old = planRow(frame.section, key)
            if (old != null) { stockSqlRequireV2(old.bytes.contentEquals(bytes) && old.expected == expected); return }
        }
        loan.sql("INSERT INTO temp.stock_plan_rows_v2(frameId,section,keyA,keyB,expectedRevision,byteCount,payload,payloadHash) VALUES(?,?,?,?,?,?,?,?)") { statement ->
            statement.bindText(1, loan.frameId.value); statement.bindLong(2, frame.section.tag.toLong()); statement.bindText(3, key.first); statement.bindText(4, key.second)
            if (expected == null) statement.bindNull(5) else statement.bindLong(5, expected.value)
            statement.bindLong(6, bytes.size.toLong()); statement.bindBlob(7, bytes); statement.bindText(8, sha256(bytes).value)
            stockSqlRequireV2(!statement.step())
        }
    }

    internal suspend fun observeRange(actual: StockRelationalAccess, query: StockQueryV2,
        consume: suspend (StockIndexRecordV2) -> Unit) {
        requireBuilding(); stockSqlRequireV2(actual === access && actual.loan === loan)
        val chain = StockRangeChainV2(query)
        var cursor: StockPageCursor? = null
        do {
            val page = access.page(query, cursor)
            for (row in page.records) {
                append(RetainedCommandFrameV2.Source(StreamedSourceObservationV2.StockMember(query, chain.count, row)), allowIdentical = true)
                chain.append(row); consume(row)
            }
            cursor = page.next
        } while (cursor != null)
        append(RetainedCommandFrameV2.Source(chain.finish()), allowIdentical = true)
    }

    internal suspend fun observeEstate(actual: StockRelationalAccess,
        consume: suspend (StockEstateRecordV2) -> Unit): StreamedSourceObservationV2.EstateEnd {
        requireBuilding(); stockSqlRequireV2(actual === access && actual.loan === loan)
        val chain = StockEstateChainV2()
        access.estate { row ->
            append(RetainedCommandFrameV2.Source(StreamedSourceObservationV2.EstateMember(chain.count, row)), allowIdentical = true)
            chain.append(row); consume(row)
        }
        val end = chain.finish()
        append(RetainedCommandFrameV2.Source(end), allowIdentical = true)
        return end
    }

    private suspend fun requireEstate(expected: StreamedSourceObservationV2.EstateEnd) {
        val chain = StockEstateChainV2()
        access.estate { row ->
            val member = RetainedCommandFrameV2.Source(StreamedSourceObservationV2.EstateMember(chain.count, row))
            val actual = planRow(StreamedSectionV2.SOURCES, RetainedCommandFramesV2.key(member)) ?: stockSqlUnavailableV2()
            stockSqlRequireV2(actual.bytes.contentEquals(RetainedCommandFramesV2.encode(member)))
            chain.append(row)
        }
        stockSqlRequireV2(chain.finish() == expected)
    }

    private suspend fun requireRange(expected: StreamedSourceObservationV2.StockRange) {
        val chain = StockRangeChainV2(expected.query)
        var cursor: StockPageCursor? = null
        do {
            val page = access.page(expected.query, cursor)
            for (row in page.records) {
                val member = RetainedCommandFrameV2.Source(StreamedSourceObservationV2.StockMember(expected.query, chain.count, row))
                val stored = planRow(StreamedSectionV2.SOURCES, RetainedCommandFramesV2.key(member)) ?: stockSqlUnavailableV2()
                stockSqlRequireV2(stored.bytes.contentEquals(RetainedCommandFramesV2.encode(member)))
                chain.append(row)
            }
            cursor = page.next
        } while (cursor != null)
        stockSqlRequireV2(chain.finish() == expected)
    }

    /** Called only AFTER the complete propose invocation returned; no public caller can seal. */
    internal suspend fun seal(value: StreamedMutationDecision.Apply, owner: StreamedPlanGuard) {
        loan.requireBody(); owner.requireNonphysical()
        stockSqlRequireV2(phase == Phase.INPUTS_CLOSED && closedResultHash == sha256(value.result.copyBytes()))
        stockSqlRequireV2(owner.store === loan.owner && owner.context === context && owner.normalized.size <= MAX_STORED_BYTES &&
            (value.mustCommitBefore == null || owner.recordedAt < value.mustCommitBefore))
        phase = Phase.SEALING
        for (resource in owner.resources) appendOwned(RetainedCommandFrameV2.Resource(resource))
        appendOwned(RetainedCommandFrameV2.Normalized(owner.normalized)); appendOwned(RetainedCommandFrameV2.Result(value.result))
        requireScratchShape()
        var effects = 0L
        frames(null) { frame -> when (frame) {
            is RetainedCommandFrameV2.Write -> {
                val write = frame.value
                stockSqlRequireV2(write.key.kind in owner.handler.writableKinds && write.key.kind !in owner.protectedKinds && write.key.kind != ReservedOperations.KIND)
                val expected = planRow(StreamedSectionV2.WRITES, RetainedCommandFramesV2.key(frame))?.expected
                stockSqlRequireV2(write.storageGeneration == (expected?.next() ?: Revision(1)))
                val current = dao.aggregate(write.key)
                stockSqlRequireV2(current?.revision == expected && (current == null) == (expected == null))
                if (write.key.kind in StockRelationalKindsV2.all) access.projection.project(context.scope,
                    StoredAggregate(write.key, write.storageGeneration, write.schema, write.payload, write.payloadDigest))
                effects = stockCheckedAddV2(effects, 1)
            }
            is RetainedCommandFrameV2.Claim -> {
                val claim = frame.value
                stockSqlRequireV2(claim.key.namespace in owner.handler.claimNamespaces && claim.key.namespace !in owner.protectedClaims)
                val current = dao.claim(claim.key)
                stockSqlRequireV2(if (claim.action == RetainedClaimAction.ACQUIRE) current == null else current == claim.ownerId)
                effects = stockCheckedAddV2(effects, 1)
            }
            is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
                is StreamedSourceObservationV2.Aggregate -> {
                    val actual = dao.aggregate(source.key) ?: stockSqlUnavailableV2()
                    stockSqlRequireV2(actual.revision == source.generation && actual.schema == source.schema && actual.digest == source.payloadHash && actual.payload == source.payload)
                }
                is StreamedSourceObservationV2.Claim -> stockSqlRequireV2(dao.claim(source.key) == source.ownerId)
                is StreamedSourceObservationV2.StockRange -> requireRange(source)
                is StreamedSourceObservationV2.StockMember -> {
                    val terminal = planRow(StreamedSectionV2.SOURCES, "stock.range" to stockQueryIdV2(source.query).value)
                        ?: stockSqlUnavailableV2()
                    val range = ((RetainedCommandFramesV2.decode(terminal.bytes) as? RetainedCommandFrameV2.Source)?.value
                        as? StreamedSourceObservationV2.StockRange) ?: stockSqlUnavailableV2()
                    stockSqlRequireV2(range.query == source.query && source.ordinal < range.count)
                }
                is StreamedSourceObservationV2.EstateEnd -> requireEstate(source)
                is StreamedSourceObservationV2.EstateMember -> {
                    val terminal = planRow(StreamedSectionV2.SOURCES, "estate.end" to "") ?: stockSqlUnavailableV2()
                    val end = ((RetainedCommandFramesV2.decode(terminal.bytes) as? RetainedCommandFrameV2.Source)?.value
                        as? StreamedSourceObservationV2.EstateEnd) ?: stockSqlUnavailableV2()
                    stockSqlRequireV2(source.ordinal < end.count)
                }
                // No streamed producer currently declares typed item-absence semantics.
                is StreamedSourceObservationV2.StockItemAbsent -> stockSqlUnavailableV2()
                is StreamedSourceObservationV2.CurrentAbsent -> {
                    stockSqlRequireV2(source.key.kind !in StockRelationalKindsV2.all && dao.aggregate(source.key) == null)
                }
                is StreamedSourceObservationV2.Current -> {
                    stockSqlRequireV2(source.key.kind !in StockRelationalKindsV2.all)
                    val actual = dao.aggregate(source.key) ?: stockSqlUnavailableV2()
                    stockSqlRequireV2(actual.revision == source.generation && actual.schema == source.schema && actual.digest == source.payloadHash && actual.payload == source.payload)
                }
            }
            // Admission requires the actual operation's authorization footprint as well
            // as this retained resource. The owner already required that actor resource;
            // every remaining resource is still checked, without hydrating the full spool.
            is RetainedCommandFrameV2.Resource -> stockSqlRequireV2(RecoveryAdmissionPolicy.evaluate(owner.recovery,
                context.scope.branchId, setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
                    frame.value), owner.prepared.recoveryHoldBarrierKinds) == ResourceAdmissionDecision.ADMITTED)
            else -> Unit
        } }
        stockSqlRequireV2(effects > 0)
        decision = value; guard = owner; phase = Phase.SEALED
    }

    /**
     * Existing real B4 end-only hook, streamed one bounded footprint at a time. Every key/range,
     * claim, resource and retained-operation presence is invalidated before the FIRST write.
     * No large Set or branch-wide cap-only invalidation substitutes for actual complete keys.
     */
    internal suspend fun invalidateBeforeWrite() {
        loan.requireBody(); stockSqlRequireV2(phase == Phase.SEALED)
        val owner = checkNotNull(guard); owner.requireNonphysical()
        owner.store.invalidatePhysicalBeforeWrite(context.operationId, emptySet(), emptySet(), emptySet())
        frames(null) { when (it) {
            is RetainedCommandFrameV2.Write -> owner.store.invalidatePhysicalBeforeWrite(context.operationId, setOf(it.value.key), emptySet(), emptySet())
            is RetainedCommandFrameV2.Claim -> owner.store.invalidatePhysicalBeforeWrite(context.operationId, emptySet(), setOf(it.value.key), emptySet())
            is RetainedCommandFrameV2.Resource -> owner.store.invalidatePhysicalBeforeWrite(context.operationId, emptySet(), emptySet(), setOf(it.value))
            else -> Unit
        } }
        phase = Phase.INVALIDATED
    }

    /** Actual aggregate/index/claim SQL. Owner must surround this with its SAME P4 child. */
    internal suspend fun writeBusinessRows() {
        stockSqlRequireV2(phase == Phase.INVALIDATED)
        loan.beginOwnerWrite(this); phase = Phase.WRITING
        frames(StreamedSectionV2.WRITES) { frame ->
            val write = (frame as RetainedCommandFrameV2.Write).value
            val expected = planRow(StreamedSectionV2.WRITES, RetainedCommandFramesV2.key(frame))?.expected
            if (expected == null) {
                loan.sql("INSERT INTO main.aggregate_state(scope,kind,id,revision,schema,payload,payloadHash) VALUES(?,?,?,?,?,?,?)") { statement ->
                    bindWrite(statement, write); stockSqlRequireV2(!statement.step())
                }
            } else {
                loan.sql("UPDATE main.aggregate_state SET revision=?,schema=?,payload=?,payloadHash=? WHERE scope=? AND kind=? AND id=? AND revision=?") { statement ->
                    statement.bindLong(1, write.storageGeneration.value); statement.bindText(2, write.schema); statement.bindBlob(3, write.payload.copyBytes())
                    statement.bindText(4, write.payloadDigest.value); statement.bindText(5, context.scope.storageKey()); statement.bindText(6, write.key.kind)
                    statement.bindText(7, write.key.id.value); statement.bindLong(8, expected.value); stockSqlRequireV2(!statement.step())
                }
                requireOneChange()
            }
            if (write.key.kind in StockRelationalKindsV2.all) access.projection.project(context.scope,
                StoredAggregate(write.key, write.storageGeneration, write.schema, write.payload, write.payloadDigest))?.let { dao.replaceIndex(it) }
        }
        frames(StreamedSectionV2.CLAIMS) { frame ->
            val claim = (frame as RetainedCommandFrameV2.Claim).value
            val sql = if (claim.action == RetainedClaimAction.ACQUIRE) "INSERT INTO main.unique_claim(scope,namespace,`key`,ownerId) VALUES(?,?,?,?)"
                else "DELETE FROM main.unique_claim WHERE scope=? AND namespace=? AND `key`=? AND ownerId=?"
            loan.sql(sql) { statement ->
                statement.bindText(1, context.scope.storageKey()); statement.bindText(2, claim.key.namespace); statement.bindText(3, claim.key.key)
                statement.bindText(4, claim.ownerId.value); stockSqlRequireV2(!statement.step())
            }
            requireOneChange()
        }
        access.projection.census(dao)
        phase = Phase.WRITTEN
    }

    /** Operation row must already be inserted in this SAME transaction (fragment FK). */
    internal suspend fun writeJournalFragments(identity: RetainedCommandIdentityV2): StoredBytes {
        stockSqlRequireV2(phase == Phase.WRITTEN && identity.scope == context.scope && identity.producer ==
            StreamedProducerIdentityV2(context.operationId, context.key, context.originalActorId, context.semanticDigest) &&
            identity.resultHash == sha256(checkNotNull(decision).result.copyBytes()))
        val chain = StockFragmentChainV2()
        val order = StockFrameOrderV2()
        var buffer = ByteArray(STOCK_JOURNAL_FRAGMENT_BYTES_V2)
        var used = 0
        var ordinal = 0L
        suspend fun flush() {
            if (used == 0) return
            val bytes = buffer.copyOf(used)
            loan.sql("INSERT INTO main.journal_fragment_v2(scope,operationId,ordinal,byteCount,payload,payloadHash) VALUES(?,?,?,?,?,?)") { statement ->
                statement.bindText(1, context.scope.storageKey()); statement.bindText(2, context.operationId.value); statement.bindLong(3, ordinal)
                statement.bindLong(4, bytes.size.toLong()); statement.bindBlob(5, bytes); statement.bindText(6, sha256(bytes).value)
                stockSqlRequireV2(!statement.step())
            }
            chain.append(ordinal, bytes); ordinal = stockCheckedAddV2(ordinal, 1); used = 0
        }
        suspend fun pack(bytes: ByteArray) {
            var at = 0
            while (at < bytes.size) {
                val count = minOf(buffer.size - used, bytes.size - at)
                bytes.copyInto(buffer, used, at, at + count); used += count; at += count
                if (used == buffer.size) flush()
            }
        }
        frames(null) { frame ->
            val bytes = RetainedCommandFramesV2.encode(frame); order.append(frame, bytes.size)
            val length = ByteArray(4); for (index in 0..3) length[index] = (bytes.size ushr (24 - index * 8)).toByte()
            pack(length); pack(bytes)
        }
        flush(); buffer.fill(0)
        val commitment = chain.finish()
        return StoredBytes(RetainedCommandFramesV2.encodeRoot(JournalRootV2(identity, ordinal, commitment.bytes,
            commitment.commitment, order.counts, order.bytes)))
    }

    internal suspend fun summary(section: StreamedSectionV2): StreamedSectionSummaryV2 {
        val chain = StockSectionChainV2(section)
        frames(section) { chain.append(RetainedCommandFramesV2.encode(it)) }
        return chain.finish()
    }

    internal suspend fun frames(section: StreamedSectionV2?, consume: suspend (RetainedCommandFrameV2) -> Unit) {
        stockSqlRequireV2(phase != Phase.CLEANED)
        var previous: Triple<Long, String, String>? = null
        while (true) {
            val rows = loan.sql("SELECT section,keyA,keyB,CASE WHEN ${planStorage()} THEN 1 ELSE 0 END " +
                "FROM temp.stock_plan_rows_v2 WHERE frameId=?" + (if (section != null) " AND section=?" else "") +
                (if (previous != null) " AND (section,keyA,keyB)>(?,?,?)" else "") + " ORDER BY section,keyA,keyB LIMIT $STOCK_SQL_PAGE_V2") { statement ->
                statement.bindText(1, loan.frameId.value); var at = 2
                section?.let { statement.bindLong(at++, it.tag.toLong()) }
                previous?.let { statement.bindLong(at++, it.first); statement.bindText(at++, it.second); statement.bindText(at, it.third) }
                val page = mutableListOf<Triple<Long, String, String>>()
                while (statement.step()) {
                    stockSqlRequireV2(page.size < STOCK_SQL_PAGE_V2 && statement.getLong(3) == 1L)
                    page += Triple(statement.getLong(0), statement.getText(1), statement.getText(2))
                }
                page
            }
            for (key in rows) {
                val selected = StreamedSectionV2.fromTag(key.first.toInt())
                val row = planRow(selected, key.second to key.third) ?: stockSqlUnavailableV2()
                val frame = RetainedCommandFramesV2.decode(row.bytes)
                stockSqlRequireV2(frame.section == selected && RetainedCommandFramesV2.key(frame) == (key.second to key.third) &&
                    (row.expected == null || selected == StreamedSectionV2.WRITES))
                consume(frame); previous = key
            }
            if (rows.size < STOCK_SQL_PAGE_V2) break
        }
    }

    private data class PlanRow(val bytes: ByteArray, val expected: Revision?)
    private suspend fun planRow(section: StreamedSectionV2, key: Pair<String, String>): PlanRow? {
        val size = loan.sql("SELECT CASE WHEN ${planStorage()} THEN byteCount ELSE -1 END FROM temp.stock_plan_rows_v2 WHERE frameId=? AND section=? AND keyA=? AND keyB=?") { statement ->
            bindPlanKey(statement, section, key)
            if (!statement.step()) null else { val value = statement.getLong(0); stockSqlRequireV2(value in 1L..STOCK_FRAME_BYTES_V2.toLong() && !statement.step()); value }
        } ?: return null
        return loan.sql("SELECT expectedRevision,payload,payloadHash FROM temp.stock_plan_rows_v2 WHERE frameId=? AND section=? AND keyA=? AND keyB=?") { statement ->
            bindPlanKey(statement, section, key); stockSqlRequireV2(statement.step())
            val expected = if (statement.isNull(0)) null else Revision(statement.getLong(0))
            val bytes = statement.getBlob(1)
            stockSqlRequireV2(bytes.size.toLong() == size && sha256(bytes) == PayloadDigest(statement.getText(2)) && !statement.step())
            PlanRow(bytes, expected)
        }
    }

    internal suspend fun workingGet(space: StockWorkingSpace, groupId: DomainId, recordId: DomainId): StockWorkingRecord? {
        requireBuilding()
        val size = loan.sql("SELECT CASE WHEN ${workStorage()} THEN byteCount ELSE -1 END FROM temp.stock_work_rows_v2 WHERE frameId=? AND space=? AND groupId=? AND recordId=?") { statement ->
            bindWorkKey(statement, space, groupId, recordId)
            if (!statement.step()) null else { val count = statement.getLong(0); stockSqlRequireV2(count in 0L..MAX_STORED_BYTES.toLong() && !statement.step()); count }
        } ?: return null
        return loan.sql("SELECT sortValue,payload,payloadHash FROM temp.stock_work_rows_v2 WHERE frameId=? AND space=? AND groupId=? AND recordId=?") { statement ->
            bindWorkKey(statement, space, groupId, recordId); stockSqlRequireV2(statement.step())
            val sort = statement.getLong(0); val bytes = statement.getBlob(1)
            stockSqlRequireV2(bytes.size.toLong() == size && sha256(bytes) == PayloadDigest(statement.getText(2)) && !statement.step())
            StockWorkingRecord(groupId, recordId, sort, StoredBytes(bytes))
        }
    }

    internal suspend fun workingPut(space: StockWorkingSpace, value: StockWorkingRecord, replace: Boolean) {
        requireBuilding()
        val bytes = value.payload.copyBytes()
        loan.sql("INSERT INTO temp.stock_work_rows_v2(frameId,space,groupId,recordId,sortValue,byteCount,payload,payloadHash) VALUES(?,?,?,?,?,?,?,?)" +
            if (replace) " ON CONFLICT(frameId,space,groupId,recordId) DO UPDATE SET sortValue=excluded.sortValue,byteCount=excluded.byteCount,payload=excluded.payload,payloadHash=excluded.payloadHash" else "") { statement ->
            bindWorkKey(statement, space, value.groupId, value.recordId); statement.bindLong(5, value.sortValue); statement.bindLong(6, bytes.size.toLong())
            statement.bindBlob(7, bytes); statement.bindText(8, sha256(bytes).value); stockSqlRequireV2(!statement.step())
        }
    }

    internal suspend fun workingRemove(space: StockWorkingSpace, groupId: DomainId, recordId: DomainId) {
        requireBuilding()
        loan.sql("DELETE FROM temp.stock_work_rows_v2 WHERE frameId=? AND space=? AND groupId=? AND recordId=?") { statement ->
            bindWorkKey(statement, space, groupId, recordId); stockSqlRequireV2(!statement.step())
        }
    }

    internal suspend fun workingEach(space: StockWorkingSpace, groupId: DomainId?, byRemainder: Boolean, consume: suspend (StockWorkingRecord) -> Unit) {
        requireBuilding(); stockSqlRequireV2(!byRemainder || groupId != null)
        var previous: StockWorkingRecord? = null
        while (true) {
            val keys = loan.sql("SELECT groupId,recordId,sortValue,CASE WHEN ${workStorage()} THEN 1 ELSE 0 END FROM temp.stock_work_rows_v2 WHERE frameId=? AND space=?" +
                (if (groupId != null) " AND groupId=?" else "") + (if (previous == null) "" else if (byRemainder)
                    " AND (sortValue<? OR (sortValue=? AND recordId>?))" else " AND (groupId,recordId)>(?,?)") +
                (if (byRemainder) " ORDER BY sortValue DESC,recordId" else " ORDER BY groupId,recordId") + " LIMIT $STOCK_SQL_PAGE_V2") { statement ->
                statement.bindText(1, loan.frameId.value); statement.bindLong(2, space.tag.toLong()); var at = 3
                groupId?.let { statement.bindText(at++, it.value) }
                previous?.let { if (byRemainder) { statement.bindLong(at++, it.sortValue); statement.bindLong(at++, it.sortValue); statement.bindText(at, it.recordId.value) }
                    else { statement.bindText(at++, it.groupId.value); statement.bindText(at, it.recordId.value) } }
                val result = mutableListOf<Triple<DomainId, DomainId, Long>>()
                while (statement.step()) {
                    stockSqlRequireV2(result.size < STOCK_SQL_PAGE_V2 && statement.getLong(3) == 1L)
                    result += Triple(DomainId(statement.getText(0)), DomainId(statement.getText(1)), statement.getLong(2))
                }
                result
            }
            for (key in keys) {
                val row = workingGet(space, key.first, key.second) ?: stockSqlUnavailableV2()
                stockSqlRequireV2(row.sortValue == key.third)
                // Callback may write OTHER groups/work areas; it cannot keep a live SQLiteStatement.
                consume(row); previous = row
            }
            if (keys.size < STOCK_SQL_PAGE_V2) break
        }
    }

    private suspend fun createScratch() {
        requireBuilding()
        val absent = loan.sql("SELECT 1 FROM temp.sqlite_master WHERE name IN ('stock_plan_rows_v2','stock_work_rows_v2','stock_work_order_v2') " +
            "OR tbl_name IN ('stock_plan_rows_v2','stock_work_rows_v2') LIMIT 1") { !it.step() }
        stockSqlRequireV2(absent)
        claimedNames = true
        for (sql in SCRATCH_DDL) loan.sql(sql) { stockSqlRequireV2(!it.step()) }
        requireScratchShape()
    }

    private suspend fun requireScratchShape() {
        val observed = loan.sql("SELECT type,name,tbl_name,sql,CASE WHEN ${stockTextStorageV2("type", 1, 16)} AND ${stockTextStorageV2("name", 1, 96)} " +
            "AND ${stockTextStorageV2("tbl_name", 1, 96)} AND ${stockTextStorageV2("sql", 1, 8192)} THEN 1 ELSE 0 END " +
            "FROM temp.sqlite_master WHERE name IN ('stock_plan_rows_v2','stock_work_rows_v2','stock_work_order_v2') ORDER BY name") { statement ->
            val rows = mutableListOf<Pair<String, String>>()
            while (statement.step()) { stockSqlRequireV2(rows.size < 3 && statement.getLong(4) == 1L); rows += statement.getText(1) to statement.getText(3) }
            rows
        }
        stockSqlRequireV2(observed.size == 3 && observed.all { (name, sql) -> scratchSql(sql) == scratchSql(checkNotNull(SCRATCH_SQL[name])) })
        // Only the two exact automatic PK indexes and our exact ordering index may accompany
        // these tables. A hidden trigger/index is not covered by checking three favorable names.
        stockSqlRequireV2(loan.sql("SELECT 1 FROM temp.sqlite_master WHERE tbl_name IN ('stock_plan_rows_v2','stock_work_rows_v2') AND NOT (" +
            "(type='table' AND name=tbl_name) OR (type='index' AND name='stock_work_order_v2' AND tbl_name='stock_work_rows_v2') OR " +
            "(type='index' AND sql IS NULL AND ((name='sqlite_autoindex_stock_plan_rows_v2_1' AND tbl_name='stock_plan_rows_v2') OR " +
            "(name='sqlite_autoindex_stock_work_rows_v2_1' AND tbl_name='stock_work_rows_v2')))) LIMIT 1") { !it.step() })
        for (table in listOf("stock_plan_rows_v2", "stock_work_rows_v2")) {
            stockSqlRequireV2(loan.sql("SELECT 1 FROM temp.$table WHERE typeof(frameId)<>'text' OR frameId<>? LIMIT 1") { statement ->
                statement.bindText(1, loan.frameId.value); !statement.step()
            })
        }
    }

    /** Exactly the owner connection; caller uses NonCancellable BEFORE releasing that loan. */
    internal suspend fun cleanup(sql: suspend (String, suspend (SQLiteStatement) -> Unit) -> Unit) {
        if (phase == Phase.CLEANED) return
        if (claimedNames) {
            sql("DROP INDEX IF EXISTS temp.stock_work_order_v2") { stockSqlRequireV2(!it.step()) }
            sql("DROP TABLE IF EXISTS temp.stock_work_rows_v2") { stockSqlRequireV2(!it.step()) }
            sql("DROP TABLE IF EXISTS temp.stock_plan_rows_v2") { stockSqlRequireV2(!it.step()) }
        }
        sql("SELECT 1 FROM temp.sqlite_master WHERE name IN ('stock_plan_rows_v2','stock_work_rows_v2','stock_work_order_v2') " +
            "OR tbl_name IN ('stock_plan_rows_v2','stock_work_rows_v2') LIMIT 1") { stockSqlRequireV2(!it.step()) }
        phase = Phase.CLEANED; claimedNames = false
    }

    private suspend fun requireOneChange() { loan.sql("SELECT changes()") { stockSqlRequireV2(it.step() && it.getLong(0) == 1L && !it.step()) } }
    private fun bindPlanKey(s: SQLiteStatement, section: StreamedSectionV2, key: Pair<String, String>) {
        s.bindText(1, loan.frameId.value); s.bindLong(2, section.tag.toLong()); s.bindText(3, key.first); s.bindText(4, key.second)
    }
    private fun bindWorkKey(s: SQLiteStatement, space: StockWorkingSpace, group: DomainId, id: DomainId) {
        s.bindText(1, loan.frameId.value); s.bindLong(2, space.tag.toLong()); s.bindText(3, group.value); s.bindText(4, id.value)
    }
    private fun bindWrite(s: SQLiteStatement, write: RetainedAggregateWrite) {
        s.bindText(1, context.scope.storageKey()); s.bindText(2, write.key.kind); s.bindText(3, write.key.id.value); s.bindLong(4, write.storageGeneration.value)
        s.bindText(5, write.schema); s.bindBlob(6, write.payload.copyBytes()); s.bindText(7, write.payloadDigest.value)
    }
    private fun planStorage(): String = listOf(stockTextStorageV2("frameId", 36, 36), "typeof(section)='integer' AND section BETWEEN 1 AND 8",
        stockTextStorageV2("keyA", 0, 112), stockTextStorageV2("keyB", 0, 256),
        "(typeof(expectedRevision)='null' OR (typeof(expectedRevision)='integer' AND expectedRevision>=1))",
        "typeof(byteCount)='integer' AND byteCount BETWEEN 1 AND $STOCK_FRAME_BYTES_V2", "typeof(payload)='blob' AND length(payload)=byteCount",
        stockTextStorageV2("payloadHash", 64, 64)).joinToString(" AND ") { "($it)" }
    private fun workStorage(): String = listOf(stockTextStorageV2("frameId", 36, 36), "typeof(space)='integer' AND space BETWEEN 1 AND 8",
        stockTextStorageV2("groupId", 36, 36), stockTextStorageV2("recordId", 36, 36), "typeof(sortValue)='integer'",
        "typeof(byteCount)='integer' AND byteCount BETWEEN 0 AND $MAX_STORED_BYTES", "typeof(payload)='blob' AND length(payload)=byteCount",
        stockTextStorageV2("payloadHash", 64, 64)).joinToString(" AND ") { "($it)" }
}

private val SCRATCH_SQL: Map<String, String> = linkedMapOf(
    "stock_plan_rows_v2" to "CREATE TEMP TABLE stock_plan_rows_v2(frameId TEXT NOT NULL,section INTEGER NOT NULL,keyA TEXT NOT NULL,keyB TEXT NOT NULL,expectedRevision INTEGER,byteCount INTEGER NOT NULL,payload BLOB NOT NULL,payloadHash TEXT NOT NULL,PRIMARY KEY(frameId,section,keyA,keyB))",
    "stock_work_rows_v2" to "CREATE TEMP TABLE stock_work_rows_v2(frameId TEXT NOT NULL,space INTEGER NOT NULL,groupId TEXT NOT NULL,recordId TEXT NOT NULL,sortValue INTEGER NOT NULL,byteCount INTEGER NOT NULL,payload BLOB NOT NULL,payloadHash TEXT NOT NULL,PRIMARY KEY(frameId,space,groupId,recordId))",
    "stock_work_order_v2" to "CREATE INDEX stock_work_order_v2 ON stock_work_rows_v2(frameId,space,groupId,sortValue DESC,recordId)",
)
private val SCRATCH_DDL: List<String> = SCRATCH_SQL.values.toList()
private fun scratchSql(sql: String): String = sql.replace("CREATE TEMP TABLE", "CREATE TABLE").filterNot { it.isWhitespace() }
