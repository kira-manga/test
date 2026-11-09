package com.ukkera.pos.database

import androidx.room3.Transactor
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlin.coroutines.CoroutineContext

/**
 * Trusted kernel port. Preparation reauthorizes current REQUEST and returns only the exact
 * intended PERFORM proof. Consumption belongs to the SAME actual effects/result transaction,
 * after successful business/recovery validation; never consume on reject, defer or replay.
 */
public interface TransactionApprovalSource {
    public suspend fun prepare(principal: LocalEmployeePrincipal, approvalId: DomainId,
        requirement: AuthorizationRequirement, view: CommandReadView): TransactionApprovalDecision
    public suspend fun consume(permit: TransactionApprovalPermit, view: CommandReadView): Boolean
}

public object UnavailableTransactionApprovalSource : TransactionApprovalSource {
    override suspend fun prepare(principal: LocalEmployeePrincipal, approvalId: DomainId,
        requirement: AuthorizationRequirement, view: CommandReadView): TransactionApprovalDecision = TransactionApprovalDecision.Denied
    override suspend fun consume(permit: TransactionApprovalPermit, view: CommandReadView): Boolean = false
}

public sealed interface TransactionApprovalDecision {
    public class Ready(public val permit: TransactionApprovalPermit) : TransactionApprovalDecision
    public data object Denied : TransactionApprovalDecision
}

/** Non-serializable, once-only capability bound to one exact live view/source. */
public class TransactionApprovalPermit internal constructor(
    internal val owner: RoomActionApprovalBinding,
    public val authorization: AuthorizedAction,
) {
    private var attributionContext: CommandContext? = null
    public val approvalId: DomainId = checkNotNull(authorization.approvalId)
    private val approverId: DomainId = checkNotNull(authorization.approverId)
    // Derive both identities from the same final proof. A separate supplied approver must
    // never choose a different recovery dependency than the consumed authorization.
    // The exact retained approval is an authority dependency in its own right. Revalidating
    // its approver/account does not readmit a separate unresolved hold on the approval record.
    public val additionalResources: Set<RecoveryResourceKey> =
        setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, approverId),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, approvalId))

    /** Release the actual native-qualified business DATA into one kernel context only. */
    internal fun bindBusinessAttribution(context: CommandContext, requirement: AuthorizationRequirement,
        support: ActionApprovalBusinessAttributionSupport): Boolean {
        check(attributionContext == null)
        val value = owner.businessAttributionFor(this, requirement, support) ?: return false
        context.bindBusinessAttribution(authorization, requirement, value)
        attributionContext = context
        return true
    }
    internal val businessAttributionReleased: Boolean get() = attributionContext != null
    override fun toString(): String = "TransactionApprovalPermit([REDACTED])"
}

internal class RoomTransactionApprovalSource(
    private val store: RoomLocalSecurityStore,
    private val authorization: RoomCurrentLocalAuthorizationSource,
    private val entropy: LocalAuthenticationEntropy,
) : TransactionApprovalSource {
    init { check(authorization.belongsTo(store)) }

    override suspend fun prepare(principal: LocalEmployeePrincipal, approvalId: DomainId,
        requirement: AuthorizationRequirement, view: CommandReadView): TransactionApprovalDecision =
        (view as? TransactionView ?: protectedLocalUnavailable()).prepareActionApproval(this, principal, approvalId, requirement)

    override suspend fun consume(permit: TransactionApprovalPermit, view: CommandReadView): Boolean =
        (view as? TransactionView ?: protectedLocalUnavailable()).consumeActionApproval(this, permit)

    internal fun bind(database: MasterRoomDatabase, connection: Transactor, principal: LocalEmployeePrincipal,
        context: CoroutineContext, view: CommandReadView, approvalId: DomainId,
        requirement: AuthorizationRequirement): RoomActionApprovalBinding = RoomActionApprovalBinding(
            this, store.borrowApprovalTransaction(database, connection), authorization, entropy,
            principal, context, view, approvalId, requirement)
}
