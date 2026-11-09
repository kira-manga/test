package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.SalesCommand
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal fun cashResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, id)
internal fun orderResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, id)
internal fun entitlementResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT, id)

internal class LocalSalesPrepared(
    private val context: CommandContext,
    action: SecurityAction,
    targetId: DomainId,
    revision: Revision,
    ownerId: DomainId,
    private val reason: String,
    amount: Money? = null,
    resources: Set<RecoveryResourceKey>,
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = emptySet(),
    private val proposal: (SalesCommand, UtcInstant) -> ProposedOutcome,
) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(action, context.scope,
        targetId, revision, context.operationId, context.semanticDigest, reason, ownerId, amount)
    override val affectedResources: Set<RecoveryResourceKey> = resources.toSet() +
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)

    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.isBoundTo(requirement, context.originalActorId)) { "sales.authorization.invalid" }
        val command = SalesCommand(context.operationId, authorization, recordedAt.value, context.semanticDigest, reason)
        return try {
            proposal(command, recordedAt)
        } catch (violation: SalesRuleViolation) {
            salesRejected(violation.rule)
        } catch (unavailable: LocalSalesQuoteUnavailable) {
            salesUnavailable(when (unavailable.reason) {
                LocalSalesQuoteUnavailableReason.NO_EFFECTIVE_PRICE -> LocalSalesUnavailable.PRICE_REQUIRED
                LocalSalesQuoteUnavailableReason.AMBIGUOUS_PRICE -> LocalSalesUnavailable.PRICE_CONFLICT
                LocalSalesQuoteUnavailableReason.INSUFFICIENT_STOCK -> LocalSalesUnavailable.STOCK_REQUIRED
                LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED -> LocalSalesUnavailable.CAPACITY_REQUIRED
                LocalSalesQuoteUnavailableReason.UNSUPPORTED_SELECTOR -> LocalSalesUnavailable.UNSUPPORTED_ACTIVITY
                LocalSalesQuoteUnavailableReason.RETURN_PROMISE_REQUIRES_NORMALIZED_QUOTE -> LocalSalesUnavailable.UNSUPPORTED_ACTIVITY
                LocalSalesQuoteUnavailableReason.ORIGINAL_PUBLICATION_UNAVAILABLE -> LocalSalesUnavailable.SOURCE_REQUIRED
            })
        } catch (_: LocalSalesCapacityExceeded) {
            salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
        } catch (_: LocalSalesWireCapacityExceeded) {
            salesUnavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)
        }
        // Observation preparation requires its immutable kernel future-ID fence.
        // The kernel converts a matching reserved-ID rejection to nonrecorded
        // deferral; ordinary domain/capacity refusal must not retire healthy storage.
        // Storage corruption, arithmetic overflow and programmer failures are not
        // durable "not paid" records. They reach the kernel uncertainty boundary.
    }
}

internal fun cashResources(cut: LocalCashCut): Set<RecoveryResourceKey> = setOf(
    cashResource(cut.session.value.id), cashResource(cut.drawer.value.id), cashResource(cut.custodian.value.id),
    orderResource(cut.work.value.id),
) + cut.work.value.orderIds.map(::orderResource) + cut.work.value.returnIds.map(::entitlementResource)

internal fun cashRevision(cut: LocalCashCut): LocalSalesRevision =
    LocalSalesRevision(CashNonSaleStoreProfile.SESSION_KIND, cut.session.value.id, cut.session.value.revision)
