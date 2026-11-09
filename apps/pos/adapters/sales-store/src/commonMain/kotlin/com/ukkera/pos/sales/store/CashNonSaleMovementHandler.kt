package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterCommandHandler
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessions
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * One typed non-sale cash attestation, not checkout or session provisioning.
 * The kernel alone owns admission, committed retries and the outer durable commit.
 * No request-supplied snapshot, actor, scope, digest or capability is accepted here.
 */
public class CashNonSaleMovementHandler : MasterCommandHandler {
    override public val key: CommandKey get() = CashNonSaleStoreProfile.COMMAND
    override public val writableKinds: Set<String> get() = setOf(
        CashNonSaleStoreProfile.SESSION_KIND, CashNonSaleStoreProfile.MOVEMENT_KIND,
    )
    override public val claimNamespaces: Set<String> get() = setOf(CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)

    override public fun normalize(input: StoredBytes): StoredBytes = CashNonSaleCommandCodec.normalize(input)

    override public suspend fun prepare(
        normalized: StoredBytes,
        view: CommandReadView,
        context: CommandContext,
    ): PreparedMutation {
        val trusted = CashNonSaleContext(context.scope, context.originalActorId, context.operationId,
            context.key, context.semanticDigest)
        val request = validateCashContext(normalized, view.scope, trusted)
        val session = view.aggregate(CashNonSaleStoreProfile.sessionKey(request.sessionId))
        val movement = view.aggregate(CashNonSaleStoreProfile.movementKey(request.sourceId))
        val claimOwner = view.claim(CashNonSaleStoreProfile.cashSourceClaim(request.sourceId))
        return prepareCashNonSale(normalized, trusted, CashNonSaleReadCut(view.scope,
            session?.let(CashNonSaleRow::from), movement?.let(CashNonSaleRow::from), claimOwner))
    }
}

/**
 * Internal copies of a real transaction read cut, also used by pure tests. These are
 * not public kernel factories, storage repositories, credentials or wire schemas.
 */
internal data class CashNonSaleContext(
    val scope: BranchScope,
    val originalActorId: DomainId,
    val operationId: OperationId,
    val key: CommandKey,
    val semanticDigest: PayloadDigest,
)

internal data class CashNonSaleRow(
    val key: AggregateKey,
    val storageRevision: Revision,
    val schema: String,
    val payload: StoredBytes,
) {
    companion object {
        fun from(row: StoredAggregate): CashNonSaleRow = CashNonSaleRow(row.key, row.revision, row.schema, row.payload)
    }
}

internal data class CashNonSaleReadCut(
    val scope: BranchScope,
    val session: CashNonSaleRow?,
    val movement: CashNonSaleRow?,
    val sourceClaimOwner: DomainId?,
)

internal fun prepareCashNonSale(
    normalized: StoredBytes,
    context: CashNonSaleContext,
    cut: CashNonSaleReadCut,
): PreparedMutation {
    val request = validateCashContext(normalized, cut.scope, context)
    val session = cut.session?.let { row ->
        require(row.key == CashNonSaleStoreProfile.sessionKey(request.sessionId) &&
            row.schema == CashNonSaleStoreProfile.SESSION_SCHEMA) { "Cash session storage profile mismatch" }
        SalesSnapshotCodec.decodeCashSession(row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, context.scope, request.sessionId,
                SalesStorageRevision.toDomain(row.storageRevision)))
    }
    cut.movement?.let { row ->
        require(row.key == CashNonSaleStoreProfile.movementKey(request.sourceId)) { "Cash source read binding mismatch" }
    }
    return PreparedCashNonSale(request, context, cut, session)
}

private fun validateCashContext(
    normalized: StoredBytes,
    viewScope: BranchScope,
    context: CashNonSaleContext,
): CashNonSaleRequestV1 {
    require(context.key == CashNonSaleStoreProfile.COMMAND && viewScope == context.scope) {
        "Cash command context mismatch"
    }
    val request = CashNonSaleCommandCodec.decodeRequest(normalized)
    require(semanticDigest(context.scope, context.originalActorId, context.key, normalized) == context.semanticDigest) {
        "Cash command semantic binding mismatch"
    }
    return request
}

private class PreparedCashNonSale(
    private val request: CashNonSaleRequestV1,
    private val context: CashNonSaleContext,
    private val cut: CashNonSaleReadCut,
    private val session: CashSession?,
) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(
        SecurityAction.CASH_MOVE, context.scope, request.sessionId, request.expectedDomainRevision,
        context.operationId, context.semanticDigest, request.kind.reasonCode,
        session?.custodianId ?: context.originalActorId, request.amount,
    )

    override val affectedResources: Set<RecoveryResourceKey> get() = buildSet {
        add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
        add(RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, request.sessionId))
        session?.let { add(RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, it.drawerId)) }
    }

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        // Even missing/unsupported/rejected proposals require the exact live actor
        // capability. This cannot be substituted by a grant supplied in the body.
        require(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.isBoundTo(requirement, context.originalActorId) &&
            (session == null || authorization.actorId == session.custodianId)) { "Cash authorization binding mismatch" }
        val before = session ?: return rejectCash(CashNonSaleResultV1.SessionMissing)
        // Classification alone cannot settle an invoice/payable or consume its
        // allowance. Keep historical decoding, but never post this new cash-only
        // success without a separately typed, authoritative source allocation.
        if (request.kind == NonSaleCashKind.INVENTORY_SETTLEMENT) {
            return unsupportedCash(CashNonSaleUnsupported.INVENTORY_SETTLEMENT_SOURCE_REQUIRED)
        }
        if (before.revision != request.expectedDomainRevision) return rejectCash(
            CashNonSaleResultV1.DomainRejected(SalesRule.REVISION_CONFLICT))
        if (before.pendingAttemptIds.isNotEmpty() || before.pendingTransferIds.isNotEmpty() || before.reservedCashOut.minor != 0L) {
            return unsupportedCash(CashNonSaleUnsupported.PENDING_FINANCIAL_OR_CUSTODY_WORK)
        }
        val row = checkNotNull(cut.session)
        if (row.storageRevision.value == Long.MAX_VALUE) return unsupportedCash(CashNonSaleUnsupported.REVISION_EXHAUSTED)
        // The kernel still enforces both constraints transactionally. A fresh
        // operation cannot reinterpret a used source as a committed-result retry.
        if (cut.sourceClaimOwner != null || cut.movement != null) return rejectCash(
            CashNonSaleResultV1.DomainRejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION))
        val command = SalesCommand(context.operationId, authorization, recordedAt.value,
            context.semanticDigest, request.kind.reasonCode)
        val transition = try {
            CashSessions.recordNonSale(command, before, request.expectedDomainRevision, request.sourceId,
                request.kind.domain, request.amount,
                CashierEvidence(request.evidenceId, context.originalActorId, request.occurredAt.value), request.reason)
        } catch (violation: SalesRuleViolation) {
            return rejectCash(CashNonSaleResultV1.DomainRejected(violation.rule))
        }
        // No catch-all converts corruption, arithmetic overflow or programming
        // failure into a recorded money success or an invented "not paid" result.
        val movement = validateNonSaleTransition(command, before, request, transition.command,
            transition.value, transition.versions, transition.unique, transition.effects, transition.inventoryDependencies)
            ?: return unsupportedCash(CashNonSaleUnsupported.UNEXPECTED_DOMAIN_TRANSITION)
        val after = transition.value
        check(SalesStorageRevision.toStorage(before.revision) == row.storageRevision &&
            SalesStorageRevision.toStorage(after.revision) == row.storageRevision.next()) {
            "Cash storage revision mapping mismatch"
        }
        val cashBytes = SalesSnapshotCodec.encodeCashSession(after).encodeToByteArray(throwOnInvalidSequence = true)
        if (cashBytes.size > CashNonSaleStoreProfile.MAX_ROW_BYTES) return unsupportedCash(CashNonSaleUnsupported.STORAGE_PROFILE_LIMIT)
        val movementBytes = CashNonSaleMovementCodec.encode(context, before, after, request, movement, recordedAt)
        return ProposedOutcome.Apply(
            CashNonSaleResultCodec.encode(CashNonSaleResultV1.Applied(after.id, after.revision,
                movement.sourceId, after.cashLedgerRevision, after.expectedCash)),
            listOf(
                AggregateChange(row.key, row.storageRevision, CashNonSaleStoreProfile.SESSION_SCHEMA, StoredBytes(cashBytes)),
                // Insert-only projection of the immutable journal fact. The generic
                // aggregate table itself is NOT a DB-enforced append-only table.
                AggregateChange(CashNonSaleStoreProfile.movementKey(movement.sourceId), null,
                    CashNonSaleStoreProfile.MOVEMENT_SCHEMA, movementBytes),
            ),
            listOf(ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(movement.sourceId), before.id)),
        )
    }
}

internal fun unsupportedCash(reason: CashNonSaleUnsupported): ProposedOutcome.Reject =
    rejectCash(CashNonSaleResultV1.Unsupported(reason))

internal fun rejectCash(result: CashNonSaleResultV1): ProposedOutcome.Reject {
    val code = when (result) {
        is CashNonSaleResultV1.Applied -> error("Applied cash result cannot be rejected")
        is CashNonSaleResultV1.DomainRejected -> "sales.cash.domain_rejected"
        CashNonSaleResultV1.SessionMissing -> "sales.cash.session_missing"
        is CashNonSaleResultV1.Unsupported -> "sales.cash.unsupported"
    }
    return ProposedOutcome.Reject(code, CashNonSaleResultCodec.encode(result))
}
