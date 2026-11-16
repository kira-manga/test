package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.CashAssignmentReadSet
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.ClosingWorkReadSet
import com.ukkera.pos.sales.SafeCustodyReadSet
import com.ukkera.pos.sales.SafeTransfer
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.text.CharacterCodingException

internal enum class LocalSalesSourceFailure { MISSING, INVALID }
/** Only missing/invalid decoded sales sources, never an arbitrary transaction or IO exception. */
internal class LocalSalesSourceUnavailable(val failure: LocalSalesSourceFailure) : IllegalStateException("sales.source.${failure.name}")

internal fun requireLocalSalesSource(valid: Boolean) {
    if (!valid) throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
}

@Suppress("UNUSED_PARAMETER")
internal inline fun requireLocalSalesSource(valid: Boolean, message: () -> String) {
    requireLocalSalesSource(valid)
}

internal inline fun <T> validateLocalSalesSource(validation: () -> T): T = try {
    validation()
} catch (invalid: LocalSalesSourceUnavailable) {
    throw invalid
} catch (_: IllegalArgumentException) {
    throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
} catch (_: IllegalStateException) {
    throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
}

internal fun <T> decodeLocalSalesSource(body: StoredBytes, serializer: KSerializer<T>, maximum: Int = LOCAL_SALES_MAX_BYTES): T =
    validateLocalSalesSource { localSalesDecode(body, serializer, maximum) }

@Serializable
internal data class LocalSalesScope(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId) {
    fun value(): BranchScope = BranchScope(organizationId, companyId, branchId)
    companion object {
        fun from(scope: BranchScope): LocalSalesScope = LocalSalesScope(scope.organizationId, scope.companyId, scope.branchId)
    }
}

@Serializable
internal data class LocalDrawerState(
    val scope: LocalSalesScope, val id: DomainId, val revision: Revision, val label: String, val currency: Currency,
    val activeSessionId: DomainId? = null, val closingTransferId: DomainId? = null,
)

@Serializable
internal data class LocalCustodianState(
    val scope: LocalSalesScope, val id: DomainId, val revision: Revision, val activeSessionId: DomainId? = null,
)

/** Complete CURRENT obligations for this owner, not evictable historical order storage. */
@Serializable
internal data class LocalWorkState(
    val scope: LocalSalesScope, val id: DomainId, val revision: Revision,
    val orderIds: List<DomainId> = emptyList(), val returnIds: List<DomainId> = emptyList(),
    /** Pending legal intakes are visible work, not cash/refund liabilities or spendable rights. */
    val pendingClaimIds: List<DomainId> = emptyList(),
) {
    fun readSet(): ClosingWorkReadSet = ClosingWorkReadSet(scope.value(), id, revision, orderIds, returnIds)
}

@Serializable
internal data class LocalSafeState(
    val scope: LocalSalesScope, val id: DomainId, val revision: Revision,
    val custodianId: DomainId, val label: String, val received: Money,
) {
    fun readSet(): SafeCustodyReadSet = SafeCustodyReadSet(scope.value(), id, custodianId, revision)
}

internal data class LocalSalesRow<T>(val stored: StoredAggregate, val value: T)

internal data class LocalCashCut(
    val session: LocalSalesRow<CashSession>, val drawer: LocalSalesRow<LocalDrawerState>,
    val custodian: LocalSalesRow<LocalCustodianState>, val work: LocalSalesRow<LocalWorkState>,
) {
    fun assignments(): CashAssignmentReadSet = CashAssignmentReadSet(session.value.scope,
        drawer.value.id, drawer.value.revision, drawer.value.activeSessionId, drawer.value.closingTransferId,
        custodian.value.id, custodian.value.revision, custodian.value.activeSessionId)
}

internal object LocalSalesStorage {
    suspend fun drawer(view: CommandReadView, id: DomainId): LocalSalesRow<LocalDrawerState> =
        root(view, LocalSalesStoreProfile.DRAWER_KIND, id, LocalDrawerState.serializer()) { value, revision ->
            requireLocalSalesSource(value.scope.value() == view.scope && value.id == id && value.revision == revision)
            localLabel(value.label)
            requireLocalSalesSource(value.activeSessionId == null || value.closingTransferId == null)
        }.also { row ->
            requireLocalSalesSource(view.claim(LocalSalesStoreProfile.activeDrawer(id)) == row.value.activeSessionId) { "sales.custody.unavailable" }
        }

    suspend fun custodian(view: CommandReadView, id: DomainId): LocalSalesRow<LocalCustodianState> =
        root(view, LocalSalesStoreProfile.CUSTODIAN_KIND, id, LocalCustodianState.serializer()) { value, revision ->
            requireLocalSalesSource(value.scope.value() == view.scope && value.id == id && value.revision == revision)
        }.also { row ->
            requireLocalSalesSource(view.claim(LocalSalesStoreProfile.activeCustodian(id)) == row.value.activeSessionId) { "sales.custody.unavailable" }
        }

    suspend fun work(view: CommandReadView, id: DomainId): LocalSalesRow<LocalWorkState> =
        root(view, LocalSalesStoreProfile.WORK_KIND, id, LocalWorkState.serializer()) { value, revision ->
            requireLocalSalesSource(value.scope.value() == view.scope && value.id == id && value.revision == revision)
            requireLocalSalesSource(listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all {
                it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size
            })
        }

    suspend fun safe(view: CommandReadView, id: DomainId): LocalSalesRow<LocalSafeState> =
        root(view, LocalSalesStoreProfile.SAFE_KIND, id, LocalSafeState.serializer()) { value, revision ->
            requireLocalSalesSource(value.scope.value() == view.scope && value.id == id && value.revision == revision && value.received.minor >= 0)
            localLabel(value.label)
        }

    suspend fun session(view: CommandReadView, id: DomainId): LocalSalesRow<CashSession> {
        val row = required(view, CashNonSaleStoreProfile.sessionKey(id), CashNonSaleStoreProfile.SESSION_SCHEMA)
        val value = SalesSnapshotCodec.decodeCashSession(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, view.scope, id, row.domainRevision()))
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("cash_session", id)) == id) { "sales.source.unavailable" }
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("opening_source", value.openingSourceId)) == id) { "sales.source.unavailable" }
        return LocalSalesRow(row, value)
    }

    suspend fun cash(view: CommandReadView, id: DomainId): LocalCashCut {
        val session = session(view, id)
        val drawer = drawer(view, session.value.drawerId)
        val custodian = custodian(view, session.value.custodianId)
        val work = work(view, session.value.custodianId)
        requireLocalSalesSource(drawer.value.currency == session.value.expectedCash.currency) { "sales.custody.unavailable" }
        if (session.value.status == CashSessionStatus.OPEN || session.value.status == CashSessionStatus.CLOSING) {
            requireLocalSalesSource(drawer.value.activeSessionId == id && custodian.value.activeSessionId == id && drawer.value.closingTransferId == null) {
                "sales.custody.unavailable"
            }
        } else {
            // An older submitted report stays readable after a later session opens.
            requireLocalSalesSource(drawer.value.activeSessionId != id && custodian.value.activeSessionId != id) { "sales.custody.unavailable" }
        }
        return LocalCashCut(session, drawer, custodian, work)
    }

    suspend fun transfer(view: CommandReadView, id: DomainId): LocalSalesRow<SafeTransfer> {
        val row = required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, id))
        val value = SalesSnapshotCodec.decodeSafeTransfer(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.SAFE_TRANSFER, view.scope, id, row.domainRevision()))
        requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique("safe_transfer", id)) == value.senderSessionId) { "sales.source.unavailable" }
        return LocalSalesRow(row, value)
    }

    suspend fun required(view: CommandReadView, key: AggregateKey, schema: String = LocalSalesStoreProfile.schema(key.kind)): StoredAggregate {
        val row = view.aggregate(key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(row.key == key && row.schema == schema && row.revision.value > 0)
        return row
    }

    private suspend fun <T> root(
        view: CommandReadView, kind: String, id: DomainId, serializer: KSerializer<T>, validate: (T, Revision) -> Unit,
    ): LocalSalesRow<T> {
        val row = required(view, LocalSalesStoreProfile.key(kind, id))
        val value = decodeLocalSalesSource(row.payload, serializer)
        validateLocalSalesSource { validate(value, row.domainRevision()) }
        val configuredKind = if (kind == LocalSalesStoreProfile.WORK_KIND) LocalSalesStoreProfile.CUSTODIAN_KIND else kind
        requireLocalSalesSource(view.claim(configuredClaim(configuredKind, id)) == id) { "sales.source.unavailable" }
        return LocalSalesRow(row, value)
    }
}

internal fun configuredClaim(kind: String, id: DomainId): ClaimKey =
    ClaimKey(LocalSalesStoreProfile.CONFIGURED_NAMESPACE, "$kind/${id.value}")

internal fun StoredAggregate.domainRevision(): Revision = SalesStorageRevision.toDomain(revision)
internal fun StoredAggregate.text(): String = try {
    payload.copyBytes().decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
}

/**
 * Narrow public lookup admission only. A genuinely missing requested document may
 * be presented as unavailable; a surviving source claim/companion is a broken cut,
 * not a clean miss. This never proves historical absence or creates a default row.
 */
internal suspend fun localSalesSourcePresent(view: CommandReadView, key: AggregateKey,
    sourceClaims: List<ClaimKey>, companionKeys: List<AggregateKey> = emptyList()): Boolean {
    if (view.aggregate(key) != null) return true
    check(sourceClaims.all { view.claim(it) == null } && companionKeys.all { view.aggregate(it) == null }) { "sales.source.incomplete" }
    return false
}

internal suspend fun localCustodianSourcePresent(view: CommandReadView, id: DomainId): Boolean =
    localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.CUSTODIAN_KIND, id),
        listOf(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, id), LocalSalesStoreProfile.activeCustodian(id)),
        listOf(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, id)))

internal fun LocalCashEvidenceV1.domain(actorId: DomainId): CashierEvidence = CashierEvidence(evidenceId, actorId, occurredAt.value)

/** Reserve a physical money source identity at intent time, even before its cash fact exists. */
internal suspend fun financialSourceAllocated(view: CommandReadView, id: DomainId): Boolean =
    view.claim(LocalSalesStoreProfile.unique("payment_attempt", id)) != null ||
        view.claim(LocalSalesStoreProfile.unique("refund_attempt", id)) != null ||
        view.claim(CashNonSaleStoreProfile.cashSourceClaim(id)) != null ||
        view.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, id)) != null

internal fun domainWrite(
    kind: String, id: DomainId, revision: Revision, body: String, previous: StoredAggregate?,
    schema: String = LocalSalesStoreProfile.schema(kind),
): AggregateChange {
    check(if (previous == null) revision == Revision(0) else previous.key == AggregateKey(kind, id) &&
        revision == previous.domainRevision().next()) { "sales.transition.invalid" }
    val payload = body.encodeToByteArray(throwOnInvalidSequence = true)
    if (payload.size > LOCAL_SALES_MAX_BYTES) throw LocalSalesCapacityExceeded()
    return AggregateChange(AggregateKey(kind, id), previous?.revision, schema, StoredBytes(payload))
}

internal fun <T> rootWrite(kind: String, id: DomainId, value: T, serializer: KSerializer<T>, previous: StoredAggregate?): AggregateChange =
    AggregateChange(LocalSalesStoreProfile.key(kind, id), previous?.revision, LocalSalesStoreProfile.schema(kind), localSalesEncode(value, serializer))

internal fun cashWrite(after: CashSession, previous: StoredAggregate?): AggregateChange = domainWrite(
    CashNonSaleStoreProfile.SESSION_KIND, after.id, after.revision, SalesSnapshotCodec.encodeCashSession(after), previous,
    CashNonSaleStoreProfile.SESSION_SCHEMA,
)

internal fun transferWrite(after: SafeTransfer, previous: StoredAggregate?): AggregateChange = domainWrite(
    LocalSalesStoreProfile.TRANSFER_KIND, after.id, after.revision, SalesSnapshotCodec.encodeSafeTransfer(after), previous,
)

internal class LocalSalesCapacityExceeded : IllegalStateException("sales.capacity.required")

internal fun salesRejected(rule: SalesRule): ProposedOutcome.Reject =
    ProposedOutcome.Reject("sales.domain_rejected", LocalSalesResultCodec.encode(LocalSalesResultV1.Rejected(rule)))

internal fun salesUnavailable(reason: LocalSalesUnavailable): ProposedOutcome.Reject =
    ProposedOutcome.Reject("sales.profile_unavailable", LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(reason)))

internal fun salesApplied(
    action: LocalSalesAction, id: DomainId, revisions: List<LocalSalesRevision>,
    changes: List<AggregateChange>, claims: List<ClaimChange> = emptyList(), amount: Money? = null,
    deadline: UtcInstant? = null,
): ProposedOutcome {
    val result = LocalSalesResultCodec.encode(LocalSalesResultV1.Applied(action, id, revisions, amount))
    if (changes.size > 128 || claims.size > 128 || changes.sumOf { it.payload.size.toLong() } + result.size > 1_048_576L) {
        return salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
    }
    return ProposedOutcome.Apply(result, changes, claims, mustCommitBefore = deadline)
}

@Serializable
internal data class LocalCashMovementFact(
    val scope: LocalSalesScope, val sourceId: DomainId, val operationId: OperationId, val originalActorId: DomainId,
    val sessionId: DomainId, val kind: String, val delta: Money, val cashReceived: Money, val changeGiven: Money,
    val evidence: LocalCashEvidenceV1, val reason: String?, val recordedAt: UtcInstant,
)

/** A source-bound insert projection; immutable history is the existing kernel journal. */
internal fun movementWrite(context: CommandContext, movement: CashMovement, recordedAt: UtcInstant): AggregateChange {
    return cashMovementLiteralWrite(context.scope, context.operationId, context.originalActorId, movement, recordedAt)
}

/** Exact inert movement projection for actual proposals and retained comparison; no fake context. */
internal fun cashMovementLiteralWrite(scope: BranchScope, operationId: OperationId, actorId: DomainId,
    movement: CashMovement, recordedAt: UtcInstant): AggregateChange {
    check(movement.operationId == operationId && movement.custodianId == actorId &&
        movement.evidence.actorId == actorId) { "sales.movement.invalid" }
    val fact = LocalCashMovementFact(LocalSalesScope.from(scope), movement.sourceId, movement.operationId,
        movement.custodianId, movement.sessionId, movement.kind.name, movement.delta, movement.cashReceived,
        movement.changeGiven, LocalCashEvidenceV1(movement.evidence.evidenceId, UtcInstant(movement.evidence.occurredAt)),
        movement.reason, recordedAt)
    return rootWrite(LocalSalesStoreProfile.MOVEMENT_KIND, movement.sourceId, fact, LocalCashMovementFact.serializer(), null)
}

/** Distinct source-bound supplier projection. Original cash custody and actual fact recorder
 * remain separate from the current accounting actor. Pure serialization grants no authority. */
internal fun supplierSupervisedMovementLiteralWrite(scope: BranchScope, operationId: com.ukkera.pos.types.OperationId,
    accountingActorId: DomainId, digest: com.ukkera.pos.security.PayloadDigest, normalized: StoredBytes,
    slot: LocalSupplierCashSlot, raw: LocalSupplierCashRaw, originalUnknown: LocalSupplierCashRaw?,
    movement: CashMovement, recordedAt: UtcInstant): AggregateChange {
    check(LocalSupplierCashSupervisorCodec.normalize(normalized) == normalized &&
        com.ukkera.pos.database.semanticDigest(scope, accountingActorId, LocalSupplierCashSupervisorCodec.COMMAND, normalized) == digest)
    val request = LocalSupplierCashSupervisorCodec.decode(normalized) as? LocalSupplierCashSupervisorRequest.Apply
        ?: error("supplier.cash.supervised_apply_required")
    check(slot.scope == scope && raw.scope == scope && slot.target == raw.target && raw.originalActorId == slot.originalActorId &&
        request.phase == raw.phase && request.factOperationId == raw.target.factOperation(raw.phase) &&
        operationId !in setOf(raw.target.intentOperationId, raw.target.recordingOperationId, raw.target.resolutionOperationId) &&
        raw.slotDigest == com.ukkera.pos.security.sha256(LocalSupplierCashJournal.slotWrite(slot).payload.copyBytes()) &&
        raw.bodyDigest == com.ukkera.pos.security.sha256(raw.body().copyBytes()) &&
        raw.semanticDigest == com.ukkera.pos.database.semanticDigest(scope, raw.recordingActorId, LocalSupplierCashProfile.COMMAND, raw.body()))
    val outcome = if (raw.phase == LocalSupplierCashPhase.ORIGINAL) {
        check(originalUnknown == null && raw.recordingActorId == slot.originalActorId)
        LocalSupplierCashJournalDomain.classifyOriginal(slot.requirement(), raw.body())
        (LocalSupplierCashCodec.decode(raw.body()) as LocalSupplierCashRequest.RecordOriginal).outcome
    } else {
        val parent = checkNotNull(originalUnknown)
        check(parent.phase == LocalSupplierCashPhase.ORIGINAL && parent.target == raw.target && parent.scope == scope &&
            parent.originalActorId == slot.originalActorId && parent.recordingActorId == slot.originalActorId &&
            parent.bodyDigest == com.ukkera.pos.security.sha256(parent.body().copyBytes()))
        LocalSupplierCashResolutionDescriptor.create(slot.requirement(), parent.body(), raw.recordingActorId, raw.body())
        (LocalSupplierCashCodec.decode(raw.body()) as LocalSupplierCashRequest.RecordResolution).outcome
    }
    val paid = outcome as? LocalSupplierCashOutcome.CashPaid ?: error("supplier.cash.paid_fact_required")
    check(paid.supportedAmountOrNull() == slot.domain.amount && movement.operationId == operationId &&
        movement.sourceId == slot.target.attemptId && movement.sessionId == slot.target.sessionId &&
        movement.custodianId == slot.originalActorId && movement.evidence.actorId == raw.recordingActorId &&
        movement.evidence.evidenceId == paid.evidence.evidenceId && movement.evidence.occurredAt == paid.evidence.occurredAt.value &&
        movement.kind == com.ukkera.pos.sales.CashMovementKind.INVENTORY_SETTLEMENT && movement.delta == -slot.domain.amount &&
        movement.cashReceived == Money(slot.domain.amount.currency, 0L) && movement.changeGiven == Money(slot.domain.amount.currency, 0L) && movement.reason == slot.domain.reasonCode)
    val fact = LocalCashMovementFact(LocalSalesScope.from(scope), movement.sourceId, movement.operationId,
        movement.custodianId, movement.sessionId, movement.kind.name, movement.delta, movement.cashReceived,
        movement.changeGiven, LocalCashEvidenceV1(movement.evidence.evidenceId, UtcInstant(movement.evidence.occurredAt)), movement.reason, recordedAt)
    return rootWrite(LocalSalesStoreProfile.MOVEMENT_KIND, movement.sourceId, fact, LocalCashMovementFact.serializer(), null)
}

/** Exact same-format FOOD supervisor projection. The complete native raw/slot/UNKNOWN
 * lineage is proved by callers; this inert serializer preserves original cash custody,
 * actual fact recorder and current accounting attribution without granting any action. */
internal fun foodRefundSupervisedMovementLiteralWrite(scope: BranchScope, operationId: OperationId,
    accountingActorId: DomainId, digest: com.ukkera.pos.security.PayloadDigest, normalized: StoredBytes,
    slot: LocalFoodRefundSlot, originalRaw: LocalFoodRefundRaw, resolutionRaw: LocalFoodRefundResolutionRaw?,
    movement: CashMovement, recordedAt: UtcInstant): AggregateChange {
    check(LocalFoodRefundSupervisorCodec.normalize(normalized) == normalized &&
        com.ukkera.pos.database.semanticDigest(scope, accountingActorId, LocalFoodRefundSupervisorCodec.COMMAND, normalized) == digest)
    val request = LocalFoodRefundSupervisorCodec.decode(normalized) as? LocalFoodRefundSupervisorRequest.Apply
        ?: error("food.refund.supervised_apply_required")
    check(slot.scope == scope && originalRaw.scope == scope && originalRaw.target == slot.target &&
        originalRaw.originalActorId == slot.originalActorId && originalRaw.recordingActorId == slot.originalActorId &&
        originalRaw.slotDigest == com.ukkera.pos.security.sha256(foodRefundSlotWrite(slot).payload.copyBytes()) &&
        originalRaw.bodyDigest == com.ukkera.pos.security.sha256(originalRaw.body().copyBytes()) &&
        originalRaw.semanticDigest == com.ukkera.pos.database.semanticDigest(scope, originalRaw.recordingActorId,
            LocalFoodRefundProfile.COMMAND, originalRaw.body()) &&
        request.factOperationId == slot.target.factOperation(request.phase) &&
        operationId !in setOf(slot.target.intentOperationId, slot.target.recordingOperationId, slot.target.resolutionOperationId))
    LocalFoodRefundCashJournalDomain.classifyOriginal(slot.requirement(), originalRaw.body())
    val outcome: LocalFoodRefundOutcome
    val recordingActor: DomainId
    if (request.phase == LocalFoodRefundFactPhase.ORIGINAL) {
        check(resolutionRaw == null)
        outcome = (LocalFoodRefundCodec.decode(originalRaw.body()) as LocalFoodRefundRequest.RecordOriginal).outcome
        recordingActor = originalRaw.recordingActorId
    } else {
        val resolution = checkNotNull(resolutionRaw)
        check(resolution.scope == scope && resolution.target == slot.target && resolution.originalActorId == slot.originalActorId &&
            resolution.slotDigest == originalRaw.slotDigest && resolution.originalBodyDigest == originalRaw.bodyDigest &&
            resolution.bodyDigest == com.ukkera.pos.security.sha256(resolution.body().copyBytes()) &&
            resolution.semanticDigest == com.ukkera.pos.database.semanticDigest(scope, resolution.recordingActorId,
                LocalFoodRefundProfile.COMMAND, resolution.body()))
        LocalFoodRefundResolutionDescriptor.create(slot.requirement(), originalRaw.body(), resolution.recordingActorId, resolution.body())
        outcome = (LocalFoodRefundCodec.decode(resolution.body()) as LocalFoodRefundRequest.RecordResolution).outcome
        recordingActor = resolution.recordingActorId
    }
    val paid = outcome as? LocalFoodRefundOutcome.CashPaid ?: error("food.refund.paid_fact_required")
    check(paid.amount == slot.domain.amount && movement.operationId == operationId && movement.sourceId == slot.target.attemptId &&
        movement.sessionId == slot.target.sessionId && movement.custodianId == slot.originalActorId &&
        movement.evidence.actorId == recordingActor && movement.evidence.evidenceId == paid.evidence.evidenceId &&
        movement.evidence.occurredAt == paid.evidence.occurredAt.value &&
        movement.kind == com.ukkera.pos.sales.CashMovementKind.CUSTOMER_REFUND && movement.delta == -slot.domain.amount &&
        movement.cashReceived == Money(slot.domain.amount.currency, 0L) && movement.changeGiven == Money(slot.domain.amount.currency, 0L) &&
        movement.reason == null)
    val fact = LocalCashMovementFact(LocalSalesScope.from(scope), movement.sourceId, movement.operationId,
        movement.custodianId, movement.sessionId, movement.kind.name, movement.delta, movement.cashReceived,
        movement.changeGiven, LocalCashEvidenceV1(movement.evidence.evidenceId, UtcInstant(movement.evidence.occurredAt)), movement.reason, recordedAt)
    return rootWrite(LocalSalesStoreProfile.MOVEMENT_KIND, movement.sourceId, fact, LocalCashMovementFact.serializer(), null)
}
