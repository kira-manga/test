package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

public enum class ProjectionChannel {
    QUERY,
    DIRECT_ID,
    SUBSCRIPTION,
    EXPORT,
    CACHED_VIEW,
}

public enum class ProjectionField {
    ID,
    DESCRIPTION,
    QUANTITY,
    STATUS,
    OWNER,
    SALE_TOTAL,
    TENDER_SUMMARY,
    UNIT_COST,
    MARGIN,
    DELIVERY_CONTACT,
    ASSIGNMENT,
}

/** Closed shapes, not "serialize the entire entity and hide a few fields in the UI". */
public enum class ProjectionKind {
    OWN_ACTIVE_ORDER,
    SAME_BRANCH_RETURN_RECEIPT,
    OWN_CASH_SESSION,
    INVENTORY_QUANTITY,
    INVENTORY_COST,
    KITCHEN_STATION_TASK,
    ASSIGNED_DELIVERY,
    BRANCH_SUMMARY,
    COMPANY_SUMMARY,
}

public data class PrivateProjectionCacheKey(
    public val scope: TenantScope,
    public val actorId: DomainId,
    public val authenticationId: DomainId,
    public val generation: SessionGeneration,
    public val policyRevision: Revision,
    public val kind: ProjectionKind,
    public val resourceId: DomainId,
    public val resourceRevision: Revision,
)

/**
 * Valid only for the authorized delivery. Reauthorize every query/direct ID/export/subscription
 * emission/cached render; the cache key partitions data but does not grant access or defer local
 * revocation. Related records still need their own authorization.
 */
public class AuthorizedProjection private constructor(
    public val cacheKey: PrivateProjectionCacheKey,
    public val channel: ProjectionChannel,
    fields: Set<ProjectionField>,
) {
    public val fields: Set<ProjectionField> = fields.toSet()

    /** Apply before serialization. Adapters map scalar fields, not hidden nested raw entities. */
    public fun <T> select(values: Map<ProjectionField, T>): Map<ProjectionField, T> =
        values.filterKeys { it in fields }

    internal companion object {
        internal fun issue(
            authorization: AuthorizedAction,
            kind: ProjectionKind,
            binding: ActionBinding,
            channel: ProjectionChannel,
            fields: Set<ProjectionField>,
        ): AuthorizedProjection = AuthorizedProjection(
            PrivateProjectionCacheKey(
                authorization.requirement.scope, authorization.actorId, authorization.authenticationId,
                authorization.generation, authorization.policyRevision, kind, binding.documentId, binding.revision,
            ),
            channel,
            fields,
        )
    }
}

public sealed interface ProjectionDecision {
    public data class Allowed(public val projection: AuthorizedProjection) : ProjectionDecision
    public data class Denied(public val reason: AuthorizationDenial) : ProjectionDecision
}

public class ProjectionPolicy(private val authorization: AuthorizationPolicy) {
    /**
     * binding/owner/assignment are authoritative resource metadata, not request-supplied ownership.
     * Station/task grants require target IDs. Delivery ownerId must be the stored assigned courier;
     * another courier is denied unless a separately activated ANY_OWNER supervisory grant exists.
     */
    public suspend fun authorize(
        principal: AuthenticatedPrincipal?,
        scope: TenantScope,
        kind: ProjectionKind,
        binding: ActionBinding,
        ownerId: DomainId?,
        channel: ProjectionChannel,
    ): ProjectionDecision {
        val action = when (kind) {
            ProjectionKind.OWN_ACTIVE_ORDER -> SecurityAction.ORDER_READ
            ProjectionKind.SAME_BRANCH_RETURN_RECEIPT -> SecurityAction.RETURN_RECEIPT_LOOKUP
            ProjectionKind.OWN_CASH_SESSION -> SecurityAction.CASH_READ
            ProjectionKind.INVENTORY_QUANTITY -> SecurityAction.INVENTORY_QUANTITY_READ
            ProjectionKind.INVENTORY_COST -> SecurityAction.INVENTORY_COST_READ
            ProjectionKind.KITCHEN_STATION_TASK -> SecurityAction.KITCHEN_TASK
            ProjectionKind.ASSIGNED_DELIVERY -> SecurityAction.DELIVERY_ASSIGNMENT
            ProjectionKind.BRANCH_SUMMARY -> SecurityAction.BRANCH_REPORT
            ProjectionKind.COMPANY_SUMMARY -> SecurityAction.COMPANY_REPORT
        }
        val owner = if (action.ownerPrivate) {
            if (ownerId == null) return ProjectionDecision.Denied(AuthorizationDenial.INVALID_REQUIREMENT)
            OwnerConstraint.OwnerPrivate(ownerId)
        } else {
            OwnerConstraint.Unrestricted
        }
        val decision = authorization.authorize(
            principal,
            AuthorizationRequirement(
                Permission(action, if (channel == ProjectionChannel.EXPORT) PermissionVerb.EXPORT else PermissionVerb.PERFORM),
                scope, binding, owner = owner,
            ),
        )
        return when (decision) {
            is AuthorizationDecision.Denied -> ProjectionDecision.Denied(decision.reason)
            is AuthorizationDecision.Allowed -> ProjectionDecision.Allowed(
                AuthorizedProjection.issue(decision.authorization, kind, binding, channel, fieldsFor(kind)),
            )
        }
    }

    private fun fieldsFor(kind: ProjectionKind): Set<ProjectionField> = when (kind) {
        ProjectionKind.OWN_ACTIVE_ORDER -> setOf(
            ProjectionField.ID, ProjectionField.DESCRIPTION, ProjectionField.QUANTITY, ProjectionField.STATUS,
            ProjectionField.OWNER, ProjectionField.SALE_TOTAL,
        )
        ProjectionKind.SAME_BRANCH_RETURN_RECEIPT -> setOf(
            ProjectionField.ID, ProjectionField.DESCRIPTION, ProjectionField.QUANTITY,
            ProjectionField.SALE_TOTAL, ProjectionField.TENDER_SUMMARY,
        )
        ProjectionKind.OWN_CASH_SESSION -> setOf(
            ProjectionField.ID, ProjectionField.STATUS, ProjectionField.OWNER,
            ProjectionField.SALE_TOTAL, ProjectionField.TENDER_SUMMARY,
        )
        ProjectionKind.INVENTORY_QUANTITY -> setOf(
            ProjectionField.ID, ProjectionField.DESCRIPTION, ProjectionField.QUANTITY, ProjectionField.STATUS,
        )
        ProjectionKind.INVENTORY_COST -> setOf(
            ProjectionField.ID, ProjectionField.DESCRIPTION, ProjectionField.QUANTITY,
            ProjectionField.UNIT_COST, ProjectionField.MARGIN,
        )
        ProjectionKind.KITCHEN_STATION_TASK -> setOf(
            ProjectionField.ID, ProjectionField.DESCRIPTION, ProjectionField.QUANTITY,
            ProjectionField.STATUS, ProjectionField.ASSIGNMENT,
        )
        ProjectionKind.ASSIGNED_DELIVERY -> setOf(
            ProjectionField.ID, ProjectionField.DESCRIPTION, ProjectionField.STATUS,
            ProjectionField.ASSIGNMENT, ProjectionField.DELIVERY_CONTACT, ProjectionField.SALE_TOTAL,
        )
        ProjectionKind.BRANCH_SUMMARY, ProjectionKind.COMPANY_SUMMARY -> setOf(
            ProjectionField.ID, ProjectionField.STATUS, ProjectionField.QUANTITY, ProjectionField.SALE_TOTAL,
        )
    }
}
