package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** A separate count-value authority, never a purchase, stock receipt or supplier payable. */
public object LocalCountSurplusApprovalProfile {
    public val COMMAND: CommandKey = CommandKey("local.stock.count.approve-surplus", 1)
    public val QUERY: ReadKey = ReadKey("local.stock.count.surplus-approval", 1)
    public const val KIND: String = "local.stock.count-surplus-basis"
    public const val SCHEMA: String = "local.stock.count-surplus-basis/v1"
    public const val CLAIM: String = "local.stock.count-surplus-source"
    public fun key(id: DomainId): AggregateKey = AggregateKey(KIND, id)
    public fun claim(id: DomainId): ClaimKey = ClaimKey(CLAIM, id.value)
}

@Serializable
public data class LocalCountSurplusApprovalRequestV1(
    public val approvalId: DomainId,
    public val countId: DomainId,
    public val expectedCountRevision: Revision,
    public val countedQuantity: Quantity,
    /** Explicit approved value of the found units only, not the value of the entire count. */
    public val surplusValue: Money,
    public val validFrom: UtcInstant,
    public val validUntil: UtcInstant?,
    public val reasonCode: String,
) {
    init {
        require(expectedCountRevision.value >= 1 && countedQuantity.scaled > 0)
        require(surplusValue.currency == Currency.EGP && surplusValue.minor >= 0)
        require(validUntil == null || validUntil > validFrom)
        // Enforce the downstream ActionBinding grammar before entering the writer. A
        // printable but invalid reason must be a malformed request, not a storage fault.
        require(reasonCode.length in 1..96 && reasonCode.all {
            it.code in 0x21..0x7e && (it.isLetterOrDigit() || it in "._-")
        })
    }
}

@Serializable
public data class LocalCountSurplusApprovalResultV1(
    public val code: String, public val approvalId: DomainId?, public val authorityRevision: Revision?,
)

/** Purpose-limited cost projection. Reading this does not supply reusable approval authority. */
@Serializable
public data class LocalCountSurplusApprovalViewV1(
    public val approvalId: DomainId,
    public val authorityRevision: Revision,
    public val countId: DomainId,
    public val countRevision: Revision,
    public val capturedQuantity: Quantity,
    public val countedQuantity: Quantity,
    public val surplusValue: Money,
    public val approvedBy: DomainId,
    public val recordedAt: UtcInstant,
    public val validFrom: UtcInstant,
    public val validUntil: UtcInstant?,
    public val consumed: Boolean,
)

@Serializable
public data class LocalCountSurplusApprovalPageV1(public val approval: LocalCountSurplusApprovalViewV1?)

public object LocalCountSurplusApprovalCodec {
    public fun encode(request: LocalCountSurplusApprovalRequestV1): StoredBytes =
        encodeValue(request, LocalCountSurplusApprovalRequestV1.serializer())
    public fun decode(request: StoredBytes): LocalCountSurplusApprovalRequestV1 =
        decodeValue(request, LocalCountSurplusApprovalRequestV1.serializer())
    public fun query(approvalId: DomainId): StoredBytes = encodeValue(ApprovalLookupV1(approvalId), ApprovalLookupV1.serializer())
    public fun decodeResult(result: StoredBytes): LocalCountSurplusApprovalResultV1 =
        decodeValue(result, LocalCountSurplusApprovalResultV1.serializer())
    public fun decodePage(result: StoredBytes): LocalCountSurplusApprovalPageV1 =
        decodeValue(result, LocalCountSurplusApprovalPageV1.serializer())
}

@Serializable private data class ApprovalLookupV1(val approvalId: DomainId)
@Serializable private data class ApprovalSourceV1(val kind: String, val id: DomainId, val revision: Revision, val digest: PayloadDigest) {
    fun key(): AggregateKey = AggregateKey(kind, id)
}
@Serializable private data class ApprovalPositionV1(
    val skuId: DomainId, val warehouseId: DomainId, val lotId: DomainId?, val custody: String,
) {
    fun restore(): LocalStockPositionRef = LocalStockPositionRef(skuId, warehouseId, lotId, StockCustody.valueOf(custody))
}
@Serializable private data class CountSurplusRecordV1(
    val scope: BranchScope,
    val request: LocalCountSurplusApprovalRequestV1,
    val position: ApprovalPositionV1,
    val capturedItemRevision: Revision,
    val capturedQuantity: Quantity,
    val capturedBy: DomainId,
    val capturedAt: UtcInstant,
    /** Original observed input witnesses, NOT live authority stamps after a later count posts. */
    val originalSources: List<ApprovalSourceV1>,
    val operationId: OperationId,
    val approvedBy: DomainId,
    val policyRevision: Revision,
    val recordedAt: UtcInstant,
)

/**
 * Current value-limited INVENTORY_ADJUST authorization and the actual captured stock count
 * are required in the same writer transaction. No moving-average guess, receipt allowance,
 * supplied actor, supplied stock snapshot or implicit company-wide approval is accepted.
 */
public class LocalCountSurplusApprovalHandler(private val stock: LocalStockRepository) : MasterCommandHandler {
    override val key: CommandKey = LocalCountSurplusApprovalProfile.COMMAND
    override val writableKinds: Set<String> = setOf(LocalCountSurplusApprovalProfile.KIND)
    override val claimNamespaces: Set<String> = setOf(LocalCountSurplusApprovalProfile.CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = LocalCountSurplusApprovalCodec.encode(LocalCountSurplusApprovalCodec.decode(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalCountSurplusApprovalCodec.decode(normalized)
        val previous = readCountSurplusRecord(view, request.approvalId)
        var capacityUnavailable = false
        val count = try { stock.countApprovalSnapshot(view, request.countId) }
        catch (failure: LocalStockRejected) {
            if (failure.failure != LocalStockFailure.BOUND_EXCEEDED) throw failure
            capacityUnavailable = true
            null
        }
        val required = AuthorizationRequirement(
            Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), context.scope,
            ActionBinding(request.countId, request.expectedCountRevision, context.semanticDigest, context.operationId, request.reasonCode),
            LimitUse.Monetary(request.surplusValue),
        )
        val resources = count?.recoveryResources.orEmpty() + setOf(
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.approvalId),
            RecoveryResourceKey(RecoveryResourceKind.STOCK, request.countId),
        ) + listOfNotNull(previous?.second?.approvedBy?.let { RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) })
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = required
            override val affectedResources: Set<RecoveryResourceKey> = resources.toSet()
            // This v1 authority records the direct actor. A separate action approval cannot
            // silently turn the requester's identity into the approving stock-value authority.
            override val allowsActionApproval: Boolean = false
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() = setOf(RecoveryResourceKind.STOCK)
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.isBoundTo(required, context.originalActorId))
                fun reject(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
                    encodeValue(LocalCountSurplusApprovalResultV1(code, null, null), LocalCountSurplusApprovalResultV1.serializer()))
                if (authorization.approvalId != null) return reject("stock.direct_value_authority_required")
                if (previous != null) return reject("stock.count_approval_exists")
                if (capacityUnavailable) return reject("stock.capacity_required")
                val actual = count ?: return reject("stock.count_source_required")
                if (actual.status != LocalStockCountApprovalStatus.OPEN) return reject("stock.count_requires_recount_or_closed")
                if (actual.countRevision != request.expectedCountRevision) return reject("stock.stale_count")
                // An existing serial cannot be resurrected by a quantity gain. Preserve the
                // no-serial v1 authority wire; found serialized units need receipt provenance.
                if (actual.position.serialId != null) return reject("stock.serial_surplus_requires_receipt")
                if (actual.position.supplierId != null || actual.position.custody == StockCustody.REJECTED_HELD)
                    return reject("stock.owned_count_required")
                if (request.countedQuantity.unitId != actual.capturedQuantity.unitId ||
                    request.countedQuantity.scale != actual.capturedQuantity.scale || request.countedQuantity <= actual.capturedQuantity)
                    return reject("stock.exact_surplus_required")
                if (request.validUntil?.let { recordedAt >= it } == true) return reject("stock.count_approval_expired")
                val record = CountSurplusRecordV1(context.scope, request,
                    ApprovalPositionV1(actual.position.skuId, actual.position.warehouseId, actual.position.lotId, actual.position.custody.name),
                    actual.capturedItemRevision, actual.capturedQuantity, actual.capturedBy, actual.capturedAt,
                    actual.sources.sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value })
                        .map { ApprovalSourceV1(it.key.kind, it.key.id, it.storageRevision, it.digest) },
                    context.operationId, authorization.actorId, authorization.policyRevision, recordedAt)
                val payload = encodeValue(record, CountSurplusRecordV1.serializer())
                return ProposedOutcome.Apply(approvedCountSurplusResult(request.approvalId),
                    listOf(AggregateChange(LocalCountSurplusApprovalProfile.key(request.approvalId), null,
                        LocalCountSurplusApprovalProfile.SCHEMA, payload)),
                    listOf(ClaimChange.Acquire(LocalCountSurplusApprovalProfile.claim(request.approvalId), request.approvalId)),
                    mustCommitBefore = request.validUntil)
            }
        }
    }
}

private fun approvedCountSurplusResult(id: DomainId): StoredBytes = encodeValue(
    LocalCountSurplusApprovalResultV1("stock.count_surplus_approved", id, Revision(0)), LocalCountSurplusApprovalResultV1.serializer())

/** Validate this exact append-only producer; an overlapping namespace alone is not provenance. */
private suspend fun readCountSurplusRecord(view: CommandReadView, id: DomainId): Pair<StoredAggregate, CountSurplusRecordV1>? {
    val row = view.aggregate(LocalCountSurplusApprovalProfile.key(id))
    val claim = view.claim(LocalCountSurplusApprovalProfile.claim(id))
    check(if (row == null) claim == null else claim == id) { "Count approval source inconsistent" }
    if (row == null) return null
    check(row.schema == LocalCountSurplusApprovalProfile.SCHEMA && row.revision == Revision(1))
    val value = decodeValue(row.payload, CountSurplusRecordV1.serializer())
    check(value.scope == view.scope && value.request.approvalId == id &&
        encodeValue(value, CountSurplusRecordV1.serializer()) == row.payload)
    check(value.position.restore().custody != StockCustody.REJECTED_HELD && value.capturedItemRevision.value >= 1)
    check(value.capturedQuantity.unitId == value.request.countedQuantity.unitId &&
        value.capturedQuantity.scale == value.request.countedQuantity.scale &&
        value.capturedQuantity.scaled >= 0 && value.request.countedQuantity > value.capturedQuantity)
    check(value.originalSources.size in 1..16 && value.originalSources.map { it.key() }.distinct().size == value.originalSources.size)
    check(value.originalSources == value.originalSources.sortedWith(compareBy<ApprovalSourceV1> { it.kind }.thenBy { it.id.value }))
    check(value.originalSources.any { it.key() == AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, value.request.countId) &&
        it.revision == value.request.expectedCountRevision })
    val original = view.retainedCommand(value.operationId) ?: error("Count approval producer unavailable")
    check(original.committed && original.command == LocalCountSurplusApprovalProfile.COMMAND && original.scope == view.scope &&
        original.originalActorId == value.approvedBy && original.policyRevision == value.policyRevision && original.recordedAt == value.recordedAt)
    check(original.normalized == LocalCountSurplusApprovalCodec.encode(value.request) && original.result == approvedCountSurplusResult(id))
    check(original.aggregateWrites.size == 1 && original.claimChanges.size == 1 && original.jobs.isEmpty())
    val write = original.aggregateWrites.single()
    check(write.key == row.key && write.storageGeneration == row.revision && write.schema == row.schema &&
        write.payload == row.payload && write.payloadDigest == row.digest)
    val originalClaim = original.claimChanges.single()
    check(originalClaim.action == RetainedClaimAction.ACQUIRE && originalClaim.key == LocalCountSurplusApprovalProfile.claim(id) && originalClaim.ownerId == id)
    return row to value
}

/** Literal retained approval on the same owner loan. Historical policy/result/one write/one
 * claim are checked exactly; no fabricated CommandReadView or current re-read supplies approval. */
internal suspend fun retainedLocalCountSurplusBasis(original: RetainedStreamedCommand, id: DomainId): LocalCountSurplusBasis {
    val row = stockReceivingCurrent(original, LocalCountSurplusApprovalProfile.key(id))
    check(row.schema == LocalCountSurplusApprovalProfile.SCHEMA && row.revision == Revision(1))
    val value = decodeValue(row.payload, CountSurplusRecordV1.serializer())
    check(value.scope == original.identity.scope && value.request.approvalId == id &&
        encodeValue(value, CountSurplusRecordV1.serializer()) == row.payload && value.recordedAt <= original.identity.recordedAt)
    check(value.position.restore().custody != StockCustody.REJECTED_HELD && value.capturedItemRevision.value >= 1 &&
        value.capturedQuantity.unitId == value.request.countedQuantity.unitId && value.capturedQuantity.scale == value.request.countedQuantity.scale &&
        value.capturedQuantity.scaled >= 0 && value.request.countedQuantity > value.capturedQuantity)
    check(value.originalSources.size in 1..16 && value.originalSources.map { it.key() }.distinct().size == value.originalSources.size &&
        value.originalSources == value.originalSources.sortedWith(compareBy<ApprovalSourceV1> { it.kind }.thenBy { it.id.value }) &&
        value.originalSources.any { it.key() == AggregateKey(LocalStockStoreProfile.COUNT_DOCUMENT, value.request.countId) &&
            it.revision == value.request.expectedCountRevision })
    val actual = (original.original(value.operationId) as? RetainedOriginalV2.Legacy)?.evidence ?: error("Count approval producer unavailable")
    check(actual.committed && actual.operationId == value.operationId && actual.command == LocalCountSurplusApprovalProfile.COMMAND && actual.scope == value.scope &&
        (actual.assignmentId != original.identity.assignmentId || actual.authorityEpoch != original.identity.authorityEpoch ||
            actual.sequence < original.identity.sequence) &&
        actual.originalActorId == value.approvedBy && actual.policyRevision == value.policyRevision && actual.recordedAt == value.recordedAt &&
        actual.normalized == LocalCountSurplusApprovalCodec.encode(value.request) && actual.result == approvedCountSurplusResult(id) &&
        actual.aggregateWrites.size == 1 && actual.claimChanges.size == 1 && actual.jobs.isEmpty())
    val write = actual.aggregateWrites.single()
    check(write.key == row.key && write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
    check(actual.claimChanges.single().let { it.action == RetainedClaimAction.ACQUIRE && it.key == LocalCountSurplusApprovalProfile.claim(id) && it.ownerId == id })
    return localCountSurplusBasis(row, value)
}

internal suspend fun readLocalCountSurplusBasis(view: CommandReadView, id: DomainId): LocalCountSurplusBasis? {
    val (row, record) = readCountSurplusRecord(view, id) ?: return null
    return localCountSurplusBasis(row, record)
}

private fun localCountSurplusBasis(row: StoredAggregate, record: CountSurplusRecordV1): LocalCountSurplusBasis {
    val request = record.request
    return LocalCountSurplusBasis(record.scope, request.approvalId, Revision(0), request.countId, request.expectedCountRevision,
        record.capturedItemRevision, record.position.restore(), record.capturedQuantity, request.countedQuantity,
        request.surplusValue, record.approvedBy, record.approvedBy, request.validFrom, request.validUntil,
        // This current immutable approval row is the authority source. Original stock witnesses
        // are historical inputs; replay/posting never requires that history to be current again.
        listOf(LocalStockSourceStamp(row.key, row.revision, row.digest)))
}

public class LocalCountSurplusApprovalReadHandler(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalCountSurplusApprovalProfile.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalCountSurplusApprovalCodec.query(decodeValue(input, ApprovalLookupV1.serializer()).approvalId)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val id = decodeValue(normalized, ApprovalLookupV1.serializer()).approvalId
        val source = readCountSurplusRecord(view, id)
        val record = source?.second
        val consumed = if (source == null) {
            check(view.claim(LocalStockStoreProfile.countSurplusClaim(id)) == null) { "Count approval consumption is orphaned" }
            false
        } else {
            // Validate the actual count-post producer and its claim in both directions. The
            // original stock observations need not still be current after a legitimate post.
            stock.countSurplusConsumed(view, localCountSurplusBasis(source.first, source.second))
        }
        val requirements = listOf(SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.INVENTORY_COST_READ).map {
            AuthorizationRequirement(Permission(it, PermissionVerb.PERFORM), context.scope)
        }
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = requirements
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (auth, required) -> auth.isBoundTo(required, context.actorId) })
                return encodeValue(LocalCountSurplusApprovalPageV1(record?.let {
                    LocalCountSurplusApprovalViewV1(id, Revision(0), it.request.countId, it.request.expectedCountRevision,
                        it.capturedQuantity, it.request.countedQuantity, it.request.surplusValue, it.approvedBy,
                        it.recordedAt, it.request.validFrom, it.request.validUntil, consumed)
                }), LocalCountSurplusApprovalPageV1.serializer())
            }
        }
    }
}
