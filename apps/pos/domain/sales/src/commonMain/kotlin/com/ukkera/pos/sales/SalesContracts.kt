package com.ukkera.pos.sales

import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.time.Instant

/** Stable, nonlocalized rule codes. A rejection is not an operation-result lookup. */
public enum class SalesRule {
    WRONG_BRANCH, OWNER_REQUIRED, REVISION_CONFLICT, PERMISSION_DENIED,
    INVALID_STATE, INVALID_AMOUNT, INVALID_EVIDENCE, DUPLICATE_SOURCE_ALLOCATION,
    DRAWER_ASSIGNED, CUSTODIAN_ASSIGNED, CUSTODY_UNRESOLVED, INSUFFICIENT_DRAWER_CASH,
    CLOSING_BLOCKED, IMMUTABLE_RECORD, PAYABLE_LOCKED, HANDOFF_CONFLICT,
    PAYMENT_UNRESOLVED, INSUFFICIENT_COVERAGE, TENDER_MISMATCH, REFERENCE_REQUIRED,
    STOCK_PROOF_MISMATCH, UNSUPPORTED_STOCK_TIMING, RETURN_ENTITLEMENT_EXHAUSTED,
    APPROVAL_REQUIRED, PAYLOAD_TOO_LARGE,
}

public class SalesRuleViolation(public val rule: SalesRule) : IllegalArgumentException(rule.name)

internal fun requireSales(condition: Boolean, rule: SalesRule) {
    if (!condition) throw SalesRuleViolation(rule)
}

/**
 * A trusted, transaction-bound authorization, never a client-supplied boolean.
 * The command runtime owns canonical payload binding, stable operation identity,
 * authenticated admission and current policy/session/resource-recovery checks.
 */
public data class SalesCommand(
    public val operationId: OperationId,
    public val authorization: AuthorizedAction,
    public val recordedAt: Instant,
    /** Computed by the master from the complete typed command, not from a body digest. */
    public val payloadDigest: PayloadDigest,
    public val reasonCode: String,
)

public enum class SalesAggregate {
    DRAWER_ASSIGNMENT, CUSTODIAN_ASSIGNMENT, CASH_SESSION, CLOSING_WORK_INDEX,
    SAFE_TRANSFER, SAFE_CUSTODY, ORDER, COLLECTION, RETURN_ENTITLEMENT, RETURN_CLAIM, ACCEPTED_RETURN,
}

public data class SalesVersionCheck(
    public val aggregate: SalesAggregate,
    public val id: DomainId,
    public val expected: Revision,
)

public enum class SalesUniqueKind {
    CASH_SESSION, OPENING_FLOAT_SOURCE, ACTIVE_DRAWER, ACTIVE_CUSTODIAN, CASH_SOURCE, SAFE_TRANSFER,
    ORDER, HANDOFF_OFFER, HANDOFF_ACCEPTANCE, COLLECTION, PAYMENT_ATTEMPT, PAYMENT_RECORD,
    SALE, SALE_ORDER, RETURN_ENTITLEMENT, RETURN_CLAIM, CLAIM_ACCEPTANCE, ACCEPTED_RETURN, REFUND_ATTEMPT, REFUND_RECORD,
}

/** Each key is scoped by the command's authenticated branch, not globally. */
public data class SalesUniqueCheck(public val kind: SalesUniqueKind, public val sourceId: DomainId)

/**
 * This is a mandatory dependency on the inventory plan, NOT a recorded stock effect.
 * The outer store must validate its CAS cut and commit that exact plan atomically
 * with this transition. An in-memory proof by itself is not durable reservation.
 */
public enum class InventoryCommitKind { RESERVE, REVALIDATE, RETAIL_ISSUE }

public class InventoryCommitDependency internal constructor(
    public val kind: InventoryCommitKind,
    /** Apply this EXACT immutable plan; a matching revision/operation tuple is insufficient. */
    public val plan: InventoryPlan,
    public val reservationId: DomainId,
    public val issueId: DomainId? = null,
) {
    public val operationId: OperationId get() = plan.operationId
    public val expectedInventoryRevision: Revision get() = plan.requiredInventoryRevision
}

public sealed interface SalesEffect

/**
 * Pure proposal, not a successful sale, durable result, or repository.
 *
 * REQUIRED STORE CONTRACT: in ONE authoritative transaction, re-read/check these
 * revisions, live authorization and resource recovery admission; enforce actual
 * branch-scoped unique constraints; apply all value/effects and inventory plans;
 * then persist operation result, original actors, audit and outboxes. Return success
 * only after commit. Stable (branch, operationId, canonical payload) deduplication
 * precedes evaluation; a retry must not evaluate a new money movement.
 *
 * Constructors for states/proofs are not a wire security boundary. Never deserialize
 * caller snapshots or forward these effects from a cashier cache as authority.
 */
public class SalesTransition<out T> internal constructor(
    public val command: SalesCommand,
    public val value: T,
    versions: List<SalesVersionCheck>,
    unique: List<SalesUniqueCheck>,
    effects: List<SalesEffect>,
    inventoryDependencies: List<InventoryCommitDependency> = emptyList(),
) {
    public val versions: List<SalesVersionCheck> = frozenList(versions)
    public val unique: List<SalesUniqueCheck> = frozenList(unique)
    public val effects: List<SalesEffect> = frozenList(effects)
    public val inventoryDependencies: List<InventoryCommitDependency> = frozenList(inventoryDependencies)
}

/** No backing MutableList escapes even if the caller retains its input collection. */
internal fun <T> frozenList(items: Collection<T>, maximum: Int = 1_024): List<T> {
    requireSales(items.size <= maximum, SalesRule.PAYLOAD_TOO_LARGE)
    return object : AbstractList<T>() {
        private val values: List<T> = items.toList()
        override val size: Int get() = values.size
        override fun get(index: Int): T = values[index]
    }
}

internal fun requireRevision(actual: Revision, expected: Revision) {
    requireSales(actual == expected, SalesRule.REVISION_CONFLICT)
}

internal fun Money.requireNonnegative(): Money {
    requireSales(minor >= 0, SalesRule.INVALID_AMOUNT)
    return this
}

internal fun Money.requirePositive(): Money {
    requireSales(minor > 0, SalesRule.INVALID_AMOUNT)
    return this
}

internal fun Money.zero(): Money = Money(currency, 0)

internal fun sameCurrency(first: Money, vararg rest: Money) {
    requireSales(rest.all { it.currency == first.currency }, SalesRule.INVALID_AMOUNT)
}

/** Evidence reference, not uploaded attachments or independent bank verification. */
public data class CashierEvidence(
    public val evidenceId: DomainId,
    public val actorId: DomainId,
    public val occurredAt: Instant,
)

internal fun CashierEvidence.requireActor(actor: DomainId) {
    requireSales(actorId == actor, SalesRule.INVALID_EVIDENCE)
}

internal fun requireReason(reason: String) {
    requireSales(reason.isNotBlank() && reason.length <= 256, SalesRule.INVALID_EVIDENCE)
}

/** Same bounded contract as ActionBinding; never substitute free-form evidence text. */
internal fun requireReasonCode(reasonCode: String) {
    requireSales(reasonCode.length in 1..128 && reasonCode.all { it.isLetterOrDigit() || it in "._-" }, SalesRule.INVALID_EVIDENCE)
}

/** Public so a trusted gateway can build the identical current authorization requirement. */
public fun salesAuthorizationRequirement(
    action: SecurityAction,
    scope: BranchScope,
    targetId: DomainId,
    revision: Revision,
    operationId: OperationId,
    payloadDigest: PayloadDigest,
    reasonCode: String,
    actorId: DomainId,
    amount: Money? = null,
): AuthorizationRequirement = AuthorizationRequirement(
    permission = Permission(action, PermissionVerb.PERFORM),
    scope = scope,
    binding = ActionBinding(targetId, revision, payloadDigest, operationId, reasonCode),
    limitUse = if (amount == null) LimitUse.NotApplicable else LimitUse.Monetary(amount),
    owner = if (action.ownerPrivate) OwnerConstraint.OwnerPrivate(actorId) else OwnerConstraint.Unrestricted,
)

internal fun authorize(
    command: SalesCommand,
    action: String,
    scope: BranchScope,
    targetId: DomainId,
    revision: Revision,
    amount: Money? = null,
): DomainId {
    val securityAction = when (action) {
        "cash.open" -> SecurityAction.CASH_OPEN
        "cash.move" -> SecurityAction.CASH_MOVE
        "cash.transfer" -> SecurityAction.CASH_TRANSFER
        "cash.receive" -> SecurityAction.CASH_RECEIVE
        "cash.close" -> SecurityAction.CASH_CLOSE
        "cash.review" -> SecurityAction.CASH_REVIEW
        "order.create" -> SecurityAction.ORDER_CREATE
        "order.read" -> SecurityAction.ORDER_READ
        "order.edit" -> SecurityAction.ORDER_EDIT
        "order.handoff" -> SecurityAction.ORDER_HANDOFF
        "order.reassign" -> SecurityAction.ORDER_REASSIGN
        "payment.begin" -> SecurityAction.COLLECTION_BEGIN
        "payment.record" -> SecurityAction.COLLECTION_ATTEST
        "payment.resolve" -> SecurityAction.COLLECTION_RESOLVE
        "payment.classification.correct" -> SecurityAction.PAYMENT_CLASSIFICATION_CORRECT
        "sale.finalize" -> SecurityAction.COLLECTION_FINALIZE
        "sale.finalizeZero" -> SecurityAction.COLLECTION_FINALIZE_ZERO
        "return.claim" -> SecurityAction.RETURN_CLAIM
        "return.accept" -> SecurityAction.RETURN_ACCEPT
        "return.receipt" -> SecurityAction.RETURN_RECEIPT_LOOKUP
        "refund.record" -> SecurityAction.REFUND_RECORD
        "refund.resolve" -> SecurityAction.REFUND_RESOLVE
        else -> throw SalesRuleViolation(SalesRule.PERMISSION_DENIED)
    }
    val proof = command.authorization
    requireSales(proof.principalKind == PrincipalKind.LOCAL_EMPLOYEE, SalesRule.PERMISSION_DENIED)
    val expected = salesAuthorizationRequirement(securityAction, scope, targetId, revision, command.operationId,
        command.payloadDigest, command.reasonCode, proof.actorId, amount)
    requireSales(proof.isBoundTo(expected), SalesRule.PERMISSION_DENIED)
    return proof.actorId
}
