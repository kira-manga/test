package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.CountedCash
import com.ukkera.pos.sales.SafeFloat
import com.ukkera.pos.sales.SafeFloatChanged
import com.ukkera.pos.sales.SafeFloatIssued
import com.ukkera.pos.sales.SafeFloatLedger
import com.ukkera.pos.sales.SafeFloatLedgerChanged
import com.ukkera.pos.sales.SafeFloatStatus
import com.ukkera.pos.sales.SafeFloats
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.sha256
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Safe-to-drawer float (opening top-up / replenishment) as a separate, additive command.
 * The existing `sales.cash.control` v1 command, its bytes and its history stay unchanged.
 * Evidence is attributed to the authenticated actor by the handler, never this DTO.
 */
@Serializable
public sealed interface LocalSafeFloatRequestV1 {
    @Serializable @SerialName("ISSUE")
    public data class Issue(
        public val safeId: DomainId,
        public val expectedSafeRevision: Revision,
        public val expectedLedgerRevision: Revision,
        /** The receiving custodian's session, read current by the master; its revision is not bound. */
        public val sessionId: DomainId,
        public val floatId: DomainId,
        public val amount: Money,
        public val evidence: LocalCashEvidenceV1,
    ) : LocalSafeFloatRequestV1 { init { require(amount.minor > 0L) } }

    /** Counted acknowledgement by the receiving drawer custodian; must equal the issue. */
    @Serializable @SerialName("RECEIVE")
    public data class Receive(
        public val floatId: DomainId,
        public val expectedFloatRevision: Revision,
        public val expectedSessionRevision: Revision,
        public val counted: Money,
        public val evidence: LocalCashEvidenceV1,
    ) : LocalSafeFloatRequestV1 { init { require(counted.minor >= 0L) } }

    /** The safe custodian takes an unacknowledged float back into safe availability. */
    @Serializable @SerialName("RECALL")
    public data class Recall(
        public val floatId: DomainId,
        public val expectedFloatRevision: Revision,
        public val expectedLedgerRevision: Revision,
        public val evidence: LocalCashEvidenceV1,
        public val reason: String,
    ) : LocalSafeFloatRequestV1 { init { localReason(reason) } }
}

@Serializable
public enum class LocalSafeFloatStatusV1 { AWAITING_RECEIPT, RECEIVED, RECALLED }

@Serializable
public data class LocalSafeFloatViewV1(
    public val id: DomainId,
    public val revision: Revision,
    public val safeId: DomainId,
    public val safeCustodianId: DomainId,
    public val sessionId: DomainId,
    public val drawerId: DomainId,
    public val receivingCustodianId: DomainId,
    public val amount: Money,
    public val issue: LocalCashActorEvidenceV1,
    public val issueOperationId: OperationId,
    public val issuedAt: UtcInstant,
    public val status: LocalSafeFloatStatusV1,
    public val receipt: LocalCashActorEvidenceV1? = null,
    public val recall: LocalCashActorEvidenceV1? = null,
    public val recallReason: String? = null,
)

@Serializable
public enum class LocalSafeFloatRefusalV1 {
    NOT_AVAILABLE, STALE, DUPLICATE, NOT_CUSTODIAN, SAME_CUSTODIAN, SESSION_NOT_OPEN,
    INSUFFICIENT_SAFE_CASH, COUNT_MISMATCH, ALREADY_SETTLED, CURRENCY_MISMATCH, CAPACITY_REQUIRED, NOT_AUTHORIZED, INVALID,
}

@Serializable
public sealed interface LocalSafeFloatResultV1 {
    @Serializable @SerialName("RECORDED")
    public data class Recorded(
        public val float: LocalSafeFloatViewV1,
        public val ledgerRevision: Revision?,
        public val sessionRevision: Revision?,
    ) : LocalSafeFloatResultV1
    @Serializable @SerialName("REFUSED")
    public data class Refused(public val reason: LocalSafeFloatRefusalV1) : LocalSafeFloatResultV1
}

@Serializable
public sealed interface LocalSafeFloatQueryV1 {
    /** Safe custodian's view: availability and every unacknowledged float. */
    @Serializable @SerialName("SAFE")
    public data class Safe(public val safeId: DomainId) : LocalSafeFloatQueryV1
    /** Drawer custodian's view of floats addressed to their own session. */
    @Serializable @SerialName("SESSION")
    public data class Session(public val sessionId: DomainId) : LocalSafeFloatQueryV1
}

@Serializable
public sealed interface LocalSafeFloatPageV1 {
    @Serializable @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalSafeFloatPageV1
    @Serializable @SerialName("SAFE")
    public data class Safe(
        public val safeId: DomainId,
        public val safeRevision: Revision,
        public val ledgerRevision: Revision,
        public val received: Money,
        public val issued: Money,
        public val recalled: Money,
        public val available: Money,
        public val awaiting: List<LocalSafeFloatViewV1>,
    ) : LocalSafeFloatPageV1
    @Serializable @SerialName("SESSION")
    public data class Session(
        public val sessionId: DomainId,
        public val sessionRevision: Revision,
        public val floats: List<LocalSafeFloatViewV1>,
    ) : LocalSafeFloatPageV1
}

public object LocalSafeFloatCodec {
    public val COMMAND: CommandKey = CommandKey("sales.cash.safe-float.write", 1)
    public val QUERY: ReadKey = ReadKey("sales.cash.safe-float.read", 1)
    public const val FLOAT_KIND: String = "sales.cash.safe-float"
    public const val LEDGER_KIND: String = "sales.cash.safe-float-ledger"
    public const val SESSION_INDEX_KIND: String = "sales.cash.safe-float-session"
    public const val MAX_OPEN: Int = 64
    public fun encode(value: LocalSafeFloatRequestV1): StoredBytes = localSalesEncode(value, LocalSafeFloatRequestV1.serializer(), 8_192)
    public fun decode(value: StoredBytes): LocalSafeFloatRequestV1 = localSalesDecode(value, LocalSafeFloatRequestV1.serializer(), 8_192)
    public fun normalize(value: StoredBytes): StoredBytes = encode(decode(value))
    public fun request(value: LocalSafeFloatQueryV1): ReadRequest = ReadRequest(QUERY, localSalesEncode(value, LocalSafeFloatQueryV1.serializer(), 2_048))
    public fun result(value: StoredBytes): LocalSafeFloatResultV1 = localSalesDecode(value, LocalSafeFloatResultV1.serializer(), 16_384)
    public fun page(value: StoredBytes): LocalSafeFloatPageV1 = localSalesDecode(value, LocalSafeFloatPageV1.serializer())
    internal fun result(value: LocalSafeFloatResultV1): StoredBytes = localSalesEncode(value, LocalSafeFloatResultV1.serializer(), 16_384)
}

@Serializable
private data class SafeFloatRecordV1(val scope: LocalSalesScope, val view: LocalSafeFloatViewV1)

/** Per-safe ledger; the safe's own receipt row is never rewritten by a float. */
@Serializable
private data class SafeFloatLedgerRecordV1(
    val scope: LocalSalesScope, val safeId: DomainId, val revision: Revision,
    val issued: Money, val recalled: Money, val awaiting: List<DomainId>,
)

@Serializable
private data class SafeFloatSessionIndexV1(val scope: LocalSalesScope, val sessionId: DomainId, val floatIds: List<DomainId>)

private fun floatClaim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("safe_float", id)
private fun floatKey(id: DomainId): AggregateKey = LocalSalesStoreProfile.key(LocalSafeFloatCodec.FLOAT_KIND, id)
private fun ledgerKey(id: DomainId): AggregateKey = LocalSalesStoreProfile.key(LocalSafeFloatCodec.LEDGER_KIND, id)
private fun indexKey(id: DomainId): AggregateKey = LocalSalesStoreProfile.key(LocalSafeFloatCodec.SESSION_INDEX_KIND, id)

private fun LocalCashActorEvidenceV1.domain(): CashierEvidence = CashierEvidence(evidenceId, actorId, occurredAt.value)
private fun CashierEvidence.local(): LocalCashActorEvidenceV1 = LocalCashActorEvidenceV1(evidenceId, actorId, UtcInstant(occurredAt))

private fun LocalSafeFloatViewV1.domain(scope: LocalSalesScope): SafeFloat = SafeFloat.restore(id, scope.value(), revision, safeId,
    safeCustodianId, sessionId, drawerId, receivingCustodianId, amount, issue.domain(), SafeFloatStatus.valueOf(status.name),
    receipt?.domain(), recall?.domain(), recallReason)

private fun SafeFloat.view(issueOperationId: OperationId, issuedAt: UtcInstant): LocalSafeFloatViewV1 = LocalSafeFloatViewV1(
    id, revision, safeId, safeCustodianId, sessionId, drawerId, receivingCustodianId, amount, issueEvidence.local(),
    issueOperationId, issuedAt, LocalSafeFloatStatusV1.valueOf(status.name), receipt?.local(), recall?.local(), recallReason)

private suspend fun storedFloat(view: CommandReadView, id: DomainId): LocalSalesRow<LocalSafeFloatViewV1> {
    val row = LocalSalesStorage.required(view, floatKey(id))
    val value = decodeLocalSalesSource(row.payload, SafeFloatRecordV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.view.id == id &&
        value.view.revision == row.domainRevision() && view.claim(floatClaim(id)) == value.view.safeId)
    validateLocalSalesSource { value.view.domain(value.scope) }
    return LocalSalesRow(row, value.view)
}

private suspend fun ledger(view: CommandReadView, safe: LocalSafeState): LocalSalesRow<SafeFloatLedgerRecordV1>? {
    val row = view.aggregate(ledgerKey(safe.id)) ?: return null
    requireLocalSalesSource(row.schema == LocalSalesStoreProfile.schema(LocalSafeFloatCodec.LEDGER_KIND))
    val value = decodeLocalSalesSource(row.payload, SafeFloatLedgerRecordV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.safeId == safe.id && value.revision.value > 0L &&
        value.issued.currency == safe.received.currency && value.awaiting.size <= LocalSafeFloatCodec.MAX_OPEN &&
        value.awaiting.distinct().size == value.awaiting.size)
    validateLocalSalesSource { value.domain() }
    return LocalSalesRow(row, value)
}

private fun SafeFloatLedgerRecordV1.domain(): SafeFloatLedger = SafeFloatLedger(scope.value(), safeId, revision, issued, recalled)

private suspend fun sessionIndex(view: CommandReadView, sessionId: DomainId): LocalSalesRow<SafeFloatSessionIndexV1>? {
    val row = view.aggregate(indexKey(sessionId)) ?: return null
    requireLocalSalesSource(row.schema == LocalSalesStoreProfile.schema(LocalSafeFloatCodec.SESSION_INDEX_KIND))
    val value = decodeLocalSalesSource(row.payload, SafeFloatSessionIndexV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.sessionId == sessionId &&
        value.floatIds.size <= LocalSafeFloatCodec.MAX_OPEN && value.floatIds.distinct().size == value.floatIds.size)
    return LocalSalesRow(row, value)
}

private fun refusal(rule: SalesRule, receiving: Boolean): LocalSafeFloatRefusalV1 = when (rule) {
    SalesRule.REVISION_CONFLICT -> LocalSafeFloatRefusalV1.STALE
    SalesRule.OWNER_REQUIRED -> LocalSafeFloatRefusalV1.NOT_CUSTODIAN
    SalesRule.PERMISSION_DENIED -> LocalSafeFloatRefusalV1.NOT_AUTHORIZED
    SalesRule.INSUFFICIENT_DRAWER_CASH -> LocalSafeFloatRefusalV1.INSUFFICIENT_SAFE_CASH
    SalesRule.IMMUTABLE_RECORD -> LocalSafeFloatRefusalV1.ALREADY_SETTLED
    SalesRule.INVALID_AMOUNT -> if (receiving) LocalSafeFloatRefusalV1.COUNT_MISMATCH else LocalSafeFloatRefusalV1.INVALID
    SalesRule.INVALID_STATE -> LocalSafeFloatRefusalV1.SESSION_NOT_OPEN
    SalesRule.DUPLICATE_SOURCE_ALLOCATION -> LocalSafeFloatRefusalV1.DUPLICATE
    else -> LocalSafeFloatRefusalV1.INVALID
}

private fun refuse(reason: LocalSafeFloatRefusalV1): ProposedOutcome =
    ProposedOutcome.Reject("cash.safe.float.refused", LocalSafeFloatCodec.result(LocalSafeFloatResultV1.Refused(reason)))

private inline fun domainOutcome(receiving: Boolean, block: () -> ProposedOutcome): ProposedOutcome = try {
    block()
} catch (violation: SalesRuleViolation) {
    refuse(refusal(violation.rule, receiving))
}

/**
 * Master-enforced: the kernel checks the CURRENT grant (CASH_TRANSFER for the safe custodian,
 * CASH_RECEIVE for the drawer custodian) and the domain rechecks the bound proof. Client state
 * never grants authority.
 */
public class LocalSafeFloatHandler : MasterCommandHandler, LocalMasterRpcRetainedResultSourceAccessProvider {
    override public val key: CommandKey get() = LocalSafeFloatCodec.COMMAND
    override public val writableKinds: Set<String> = setOf(LocalSafeFloatCodec.FLOAT_KIND, LocalSafeFloatCodec.LEDGER_KIND,
        LocalSafeFloatCodec.SESSION_INDEX_KIND, CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND)
    override public val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalSafeFloatCodec.normalize(input)

    override public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS && normalize(normalized) == normalized

    override public suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        if (!supportsRpcWorkflow(profile, normalized) || context.scope != view.scope || context.key != key ||
            semanticDigest(context.scope, context.originalActorId, key, normalized) != context.semanticDigest) return null
        val original = view.retainedCommand(context.operationId) ?: return null
        if (original.nativeOriginalProvenance != null || original.scope != context.scope || original.command != key ||
            original.operationId != context.operationId || original.originalActorId != context.originalActorId ||
            original.semanticDigest != context.semanticDigest || original.normalized != normalized) return null
        val result = LocalSafeFloatCodec.result(original.result)
        if (LocalSafeFloatCodec.result(result) != original.result || original.jobs.isNotEmpty()) return null
        fun required(target: DomainId, revision: Revision, owner: DomainId): List<AuthorizationRequirement> =
            listOf(salesAuthorizationRequirement(SecurityAction.CASH_READ, context.scope, target, revision,
                context.operationId, context.semanticDigest, "cash.safe.float.original", owner))
        if (!original.committed) {
            if (original.code != "cash.safe.float.refused" || result !is LocalSafeFloatResultV1.Refused ||
                original.aggregateWrites.isNotEmpty() || original.claimChanges.isNotEmpty()) return null
            return required(context.originalActorId, Revision(0), context.originalActorId)
        }
        val recorded = result as? LocalSafeFloatResultV1.Recorded ?: return null
        val request = LocalSafeFloatCodec.decode(normalized)
        val id = when (request) {
            is LocalSafeFloatRequestV1.Issue -> request.floatId
            is LocalSafeFloatRequestV1.Receive -> request.floatId
            is LocalSafeFloatRequestV1.Recall -> request.floatId
        }
        val write = original.aggregateWrites.singleOrNull { it.key == floatKey(id) } ?: return null
        if (write.schema != LocalSalesStoreProfile.schema(LocalSafeFloatCodec.FLOAT_KIND) || write.storageGeneration.value < 1 ||
            sha256(write.payload.copyBytes()) != write.payloadDigest) return null
        val record = localSalesDecode(write.payload, SafeFloatRecordV1.serializer())
        val actual = record.view
        if (record.scope.value() != context.scope || actual != recorded.float || actual.id != id ||
            actual.revision != Revision(write.storageGeneration.value - 1)) return null
        actual.domain(record.scope) // Validate the complete immutable float, including receipt/recall evidence.
        val owner: DomainId
        when (request) {
            is LocalSafeFloatRequestV1.Issue -> {
                if (actual.revision != Revision(0) || actual.safeId != request.safeId || actual.sessionId != request.sessionId ||
                    actual.amount != request.amount || actual.issue.actorId != context.originalActorId ||
                    actual.issueOperationId != context.operationId || actual.status != LocalSafeFloatStatusV1.AWAITING_RECEIPT) return null
                owner = actual.safeCustodianId
            }
            is LocalSafeFloatRequestV1.Receive -> {
                if (actual.revision != request.expectedFloatRevision.next() || actual.amount != request.counted ||
                    actual.receipt?.actorId != context.originalActorId || actual.status != LocalSafeFloatStatusV1.RECEIVED) return null
                owner = actual.receivingCustodianId
            }
            is LocalSafeFloatRequestV1.Recall -> {
                if (actual.revision != request.expectedFloatRevision.next() || actual.recall?.actorId != context.originalActorId ||
                    actual.status != LocalSafeFloatStatusV1.RECALLED || actual.recallReason != request.reason) return null
                owner = actual.safeCustodianId
            }
        }
        if (owner != context.originalActorId) return null
        return required(actual.id, actual.revision, owner)
    }

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(view.scope == context.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalSafeFloatCodec.decode(normalized)) {
            is LocalSafeFloatRequestV1.Issue -> issue(request, view, context)
            is LocalSafeFloatRequestV1.Receive -> receive(request, view, context)
            is LocalSafeFloatRequestV1.Recall -> recall(request, view, context)
        }
    }
}

private fun unavailable(context: CommandContext, action: SecurityAction, target: DomainId, revision: Revision): PreparedMutation =
    LocalSalesPrepared(context, action, target, revision, context.originalActorId, "cash.safe.float.unavailable",
        resources = setOf(cashResource(target))) { _, _ -> refuse(LocalSafeFloatRefusalV1.NOT_AVAILABLE) }

private suspend fun issue(request: LocalSafeFloatRequestV1.Issue, view: CommandReadView, context: CommandContext): PreparedMutation {
    if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SAFE_KIND, request.safeId),
            listOf(configuredClaim(LocalSalesStoreProfile.SAFE_KIND, request.safeId)), listOf(ledgerKey(request.safeId))) ||
        !localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(request.sessionId),
            listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId)), listOf(indexKey(request.sessionId))))
        return unavailable(context, SecurityAction.CASH_TRANSFER, request.safeId, request.expectedLedgerRevision)
    val safe = LocalSalesStorage.safe(view, request.safeId)
    val cut = LocalSalesStorage.cash(view, request.sessionId)
    val stored = ledger(view, safe.value)
    val index = sessionIndex(view, request.sessionId)
    val duplicate = claimed(view, listOf(floatClaim(request.floatId))) || view.aggregate(floatKey(request.floatId)) != null ||
        financialSourceAllocated(view, request.floatId)
    val currentLedger = stored?.value?.domain() ?: SafeFloatLedger(safe.value.scope.value(), safe.value.id, Revision(0),
        Money(safe.value.received.currency, 0L), Money(safe.value.received.currency, 0L))
    return LocalSalesPrepared(context, SecurityAction.CASH_TRANSFER, safe.value.id, request.expectedLedgerRevision,
        safe.value.custodianId, "cash.safe.float.issue", request.amount,
        cashResources(cut) + setOf(cashResource(safe.value.id), cashResource(safe.value.custodianId), cashResource(request.floatId))) { command, now ->
        when {
            duplicate -> refuse(LocalSafeFloatRefusalV1.DUPLICATE)
            safe.value.custodianId != context.originalActorId -> refuse(LocalSafeFloatRefusalV1.NOT_CUSTODIAN)
            cut.session.value.custodianId == context.originalActorId -> refuse(LocalSafeFloatRefusalV1.SAME_CUSTODIAN)
            safe.value.revision != request.expectedSafeRevision || currentLedger.revision != request.expectedLedgerRevision -> refuse(LocalSafeFloatRefusalV1.STALE)
            safe.value.received.currency != request.amount.currency ||
                cut.session.value.expectedCash.currency != request.amount.currency -> refuse(LocalSafeFloatRefusalV1.CURRENCY_MISMATCH)
            cut.session.value.status != CashSessionStatus.OPEN -> refuse(LocalSafeFloatRefusalV1.SESSION_NOT_OPEN)
            (stored?.value?.awaiting?.size ?: 0) >= LocalSafeFloatCodec.MAX_OPEN ||
                (index?.value?.floatIds?.size ?: 0) >= LocalSafeFloatCodec.MAX_OPEN -> refuse(LocalSafeFloatRefusalV1.CAPACITY_REQUIRED)
            else -> domainOutcome(false) {
                val transition = SafeFloats.issue(command, safe.value.readSet(), safe.value.received, currentLedger,
                    request.expectedLedgerRevision, cut.session.value, request.floatId,
                    request.amount, request.evidence.domain(context.originalActorId))
                val issued = transition.value
                check(transition.command == command && transition.effects == listOf(SafeFloatIssued(issued.float, issued.ledger)))
                issueOutcome(issued.float, issued.ledger, stored, index, cut.session.value.scope, context.operationId, now)
            }
        }
    }
}

private fun issueOutcome(float: SafeFloat, after: SafeFloatLedger, stored: LocalSalesRow<SafeFloatLedgerRecordV1>?,
    index: LocalSalesRow<SafeFloatSessionIndexV1>?, scope: com.ukkera.pos.security.BranchScope, operationId: OperationId,
    recordedAt: UtcInstant): ProposedOutcome.Apply {
    val local = LocalSalesScope.from(scope)
    val view = float.view(operationId, recordedAt)
    val ledgerRecord = SafeFloatLedgerRecordV1(local, after.safeId, after.revision, after.issued, after.recalled,
        stored?.value?.awaiting.orEmpty() + float.id)
    val nextIndex = SafeFloatSessionIndexV1(local, float.sessionId, index?.value?.floatIds.orEmpty() + float.id)
    return ProposedOutcome.Apply(LocalSafeFloatCodec.result(LocalSafeFloatResultV1.Recorded(view, after.revision, null)),
        listOf(rootWrite(LocalSafeFloatCodec.FLOAT_KIND, float.id, SafeFloatRecordV1(local, view), SafeFloatRecordV1.serializer(), null),
            rootWrite(LocalSafeFloatCodec.LEDGER_KIND, after.safeId, ledgerRecord, SafeFloatLedgerRecordV1.serializer(), stored?.stored),
            rootWrite(LocalSafeFloatCodec.SESSION_INDEX_KIND, float.sessionId, nextIndex, SafeFloatSessionIndexV1.serializer(), index?.stored)),
        listOf(ClaimChange.Acquire(floatClaim(float.id), float.safeId),
            ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(float.id), float.safeId)))
}

private suspend fun receive(request: LocalSafeFloatRequestV1.Receive, view: CommandReadView, context: CommandContext): PreparedMutation {
    if (!localSalesSourcePresent(view, floatKey(request.floatId), listOf(floatClaim(request.floatId))))
        return unavailable(context, SecurityAction.CASH_RECEIVE, request.floatId, request.expectedFloatRevision)
    val float = storedFloat(view, request.floatId)
    val cut = LocalSalesStorage.cash(view, float.value.sessionId)
    val safe = LocalSalesStorage.safe(view, float.value.safeId)
    val stored = checkNotNull(ledger(view, safe.value)) { "sales.source.incomplete" }
    requireLocalSalesSource(float.value.status != LocalSafeFloatStatusV1.AWAITING_RECEIPT || float.value.id in stored.value.awaiting)
    val scope = cut.session.value.scope
    return LocalSalesPrepared(context, SecurityAction.CASH_RECEIVE, float.value.id, request.expectedFloatRevision,
        float.value.receivingCustodianId, "cash.safe.float.receive", float.value.amount,
        cashResources(cut) + setOf(cashResource(safe.value.id), cashResource(float.value.id))) { command, now ->
        when {
            float.value.revision != request.expectedFloatRevision ||
                cut.session.value.revision != request.expectedSessionRevision -> refuse(LocalSafeFloatRefusalV1.STALE)
            float.value.status != LocalSafeFloatStatusV1.AWAITING_RECEIPT -> refuse(LocalSafeFloatRefusalV1.ALREADY_SETTLED)
            request.counted.currency != float.value.amount.currency -> refuse(LocalSafeFloatRefusalV1.CURRENCY_MISMATCH)
            else -> domainOutcome(true) {
                val evidence = request.evidence.domain(context.originalActorId)
                val transition = SafeFloats.receive(command, float.value.domain(LocalSalesScope.from(scope)), request.expectedFloatRevision,
                    cut.session.value, request.expectedSessionRevision, CountedCash(request.counted, evidence))
                val after = transition.value
                val movement = transition.effects.first() as? CashMovement ?: error("sales.transition.invalid")
                check(transition.command == command && transition.effects == listOf(movement, CashSessionChanged(after.session), SafeFloatChanged(after.float)))
                val afterView = after.float.view(float.value.issueOperationId, float.value.issuedAt)
                val ledgerRecord = stored.value.copy(awaiting = stored.value.awaiting - float.value.id)
                ProposedOutcome.Apply(LocalSafeFloatCodec.result(LocalSafeFloatResultV1.Recorded(afterView, null, after.session.revision)),
                    listOf(rootWrite(LocalSafeFloatCodec.FLOAT_KIND, afterView.id, SafeFloatRecordV1(LocalSalesScope.from(scope), afterView),
                        SafeFloatRecordV1.serializer(), float.stored),
                        rootWrite(LocalSafeFloatCodec.LEDGER_KIND, ledgerRecord.safeId, ledgerRecord, SafeFloatLedgerRecordV1.serializer(), stored.stored),
                        cashWrite(after.session, cut.session.stored), movementWrite(context, movement, now)),
                    emptyList())
            }
        }
    }
}

private suspend fun recall(request: LocalSafeFloatRequestV1.Recall, view: CommandReadView, context: CommandContext): PreparedMutation {
    if (!localSalesSourcePresent(view, floatKey(request.floatId), listOf(floatClaim(request.floatId))))
        return unavailable(context, SecurityAction.CASH_TRANSFER, request.floatId, request.expectedFloatRevision)
    val float = storedFloat(view, request.floatId)
    val safe = LocalSalesStorage.safe(view, float.value.safeId)
    val stored = checkNotNull(ledger(view, safe.value)) { "sales.source.incomplete" }
    val scope = LocalSalesScope(context.scope.organizationId, context.scope.companyId, context.scope.branchId)
    return LocalSalesPrepared(context, SecurityAction.CASH_TRANSFER, float.value.id, request.expectedFloatRevision,
        float.value.safeCustodianId, "cash.safe.float.recall", float.value.amount,
        setOf(cashResource(safe.value.id), cashResource(float.value.sessionId), cashResource(float.value.id))) { command, _ ->
        when {
            float.value.revision != request.expectedFloatRevision || stored.value.revision != request.expectedLedgerRevision ->
                refuse(LocalSafeFloatRefusalV1.STALE)
            float.value.status != LocalSafeFloatStatusV1.AWAITING_RECEIPT -> refuse(LocalSafeFloatRefusalV1.ALREADY_SETTLED)
            else -> domainOutcome(false) {
                val transition = SafeFloats.recall(command, float.value.domain(scope), request.expectedFloatRevision,
                    stored.value.domain(), request.expectedLedgerRevision, request.evidence.domain(context.originalActorId), request.reason)
                val after = transition.value
                check(transition.command == command && transition.effects == listOf(SafeFloatChanged(after.float), SafeFloatLedgerChanged(after.ledger)))
                val afterView = after.float.view(float.value.issueOperationId, float.value.issuedAt)
                val ledgerRecord = stored.value.copy(revision = after.ledger.revision, recalled = after.ledger.recalled,
                    awaiting = stored.value.awaiting - float.value.id)
                ProposedOutcome.Apply(LocalSafeFloatCodec.result(LocalSafeFloatResultV1.Recorded(afterView, after.ledger.revision, null)),
                    listOf(rootWrite(LocalSafeFloatCodec.FLOAT_KIND, afterView.id, SafeFloatRecordV1(scope, afterView),
                        SafeFloatRecordV1.serializer(), float.stored),
                        rootWrite(LocalSafeFloatCodec.LEDGER_KIND, ledgerRecord.safeId, ledgerRecord, SafeFloatLedgerRecordV1.serializer(), stored.stored)),
                    emptyList())
            }
        }
    }
}

/** Owner-private reads: the safe custodian sees its safe; the drawer custodian sees its session. */
public class LocalSafeFloatReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalSafeFloatCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes = LocalSafeFloatCodec.request(
        localSalesDecode(input, LocalSafeFloatQueryV1.serializer(), 2_048)).payload

    override public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.LAN_BUSINESS && normalize(normalized) == normalized

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(view.scope == context.scope && context.key == key)
        return when (val query = localSalesDecode(normalized, LocalSafeFloatQueryV1.serializer(), 2_048)) {
            is LocalSafeFloatQueryV1.Safe -> safePage(query, view, context)
            is LocalSafeFloatQueryV1.Session -> sessionPage(query, view, context)
        }
    }

    private fun notAvailable(context: ReadContext, target: DomainId): PreparedRead = localSalesRead(context, SecurityAction.CASH_READ,
        target, Revision(0), context.actorId, "cash.safe.float.unavailable", LocalSafeFloatPageV1.NotAvailable, LocalSafeFloatPageV1.serializer())

    private suspend fun safePage(query: LocalSafeFloatQueryV1.Safe, view: CommandReadView, context: ReadContext): PreparedRead {
        if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SAFE_KIND, query.safeId),
                listOf(configuredClaim(LocalSalesStoreProfile.SAFE_KIND, query.safeId)), listOf(ledgerKey(query.safeId))))
            return notAvailable(context, query.safeId)
        val safe = LocalSalesStorage.safe(view, query.safeId).value
        if (safe.custodianId != context.actorId) return notAvailable(context, query.safeId)
        val stored = ledger(view, safe)
        val ledger = stored?.value?.domain() ?: SafeFloatLedger(safe.scope.value(), safe.id, Revision(0), Money(safe.received.currency, 0L), Money(safe.received.currency, 0L))
        val awaiting = stored?.value?.awaiting.orEmpty().map { id ->
            storedFloat(view, id).value.also { requireLocalSalesSource(it.safeId == safe.id && it.status == LocalSafeFloatStatusV1.AWAITING_RECEIPT) }
        }
        val page = LocalSafeFloatPageV1.Safe(safe.id, safe.revision, ledger.revision, safe.received, ledger.issued, ledger.recalled,
            ledger.available(safe.received), awaiting)
        return localSalesRead(context, SecurityAction.CASH_READ, safe.id, safe.revision, safe.custodianId,
            "cash.safe.float.read", page, LocalSafeFloatPageV1.serializer())
    }

    private suspend fun sessionPage(query: LocalSafeFloatQueryV1.Session, view: CommandReadView, context: ReadContext): PreparedRead {
        if (!localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(query.sessionId),
                listOf(LocalSalesStoreProfile.unique("cash_session", query.sessionId)), listOf(indexKey(query.sessionId))))
            return notAvailable(context, query.sessionId)
        val session = LocalSalesStorage.session(view, query.sessionId).value
        if (session.custodianId != context.actorId) return notAvailable(context, query.sessionId)
        val floats = sessionIndex(view, session.id)?.value?.floatIds.orEmpty().map { id ->
            storedFloat(view, id).value.also { requireLocalSalesSource(it.sessionId == session.id) }
        }
        return localSalesRead(context, SecurityAction.CASH_READ, session.id, session.revision, session.custodianId,
            "cash.safe.float.read", LocalSafeFloatPageV1.Session(session.id, session.revision, floats), LocalSafeFloatPageV1.serializer())
    }
}
