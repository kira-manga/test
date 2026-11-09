package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Inert typed body selection; the kernel still authenticates the complete retained original. */
internal data class LocalRetailOrderRpcSelection(val orderId: DomainId, val revision: Revision)

internal fun LocalOrderRequestV1.retailOrderRpcSelection(): LocalRetailOrderRpcSelection? = when (this) {
    is LocalOrderRequestV1.Create -> LocalRetailOrderRpcSelection(orderId, Revision(0))
    is LocalOrderRequestV1.ReplaceUnlocked -> LocalRetailOrderRpcSelection(orderId, expectedRevision)
    is LocalOrderRequestV1.OfferHandoff, is LocalOrderRequestV1.CancelHandoff,
    is LocalOrderRequestV1.AcceptHandoff -> null
}

internal fun LocalCollectionStockRequestV2.retailOrderRpcSelection(): LocalRetailOrderRpcSelection? = when (this) {
    is LocalCollectionStockRequestV2.CreateOrder -> LocalRetailOrderRpcSelection(orderId, Revision(0))
    is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> LocalRetailOrderRpcSelection(orderId, expectedOrderRevision)
    is LocalCollectionStockRequestV2.OfferHandoff, is LocalCollectionStockRequestV2.CancelHandoff,
    is LocalCollectionStockRequestV2.AcceptHandoff, is LocalCollectionStockRequestV2.ReassignOrder,
    is LocalCollectionStockRequestV2.Begin, is LocalCollectionStockRequestV2.Finalize -> null
}

/** Pure original-resource disclosure, without repricing or reading progressed order state. */
internal fun retailOrderRpcResultRequirement(selection: LocalRetailOrderRpcSelection,
    context: CommandContext): AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), context.scope,
        ActionBinding(selection.orderId, selection.revision, context.semanticDigest, context.operationId, "order.result"),
        owner = OwnerConstraint.OwnerPrivate(context.originalActorId))
