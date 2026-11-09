package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Genuine package-owned implementation after financial framing integration. foodOrder loads
 * the actual full current producer plus its direct originals and current owner index. Both
 * installed preparation and financial complete verifiers require a collection binding to be
 * retained in every later order version. Bound orders use the separate settled-responsibility
 * handoff proof, never this precollection predicate. This is
 * not legacy JSON-field absence, a header-only read or a query of an arbitrary collection ID.
 * No current stock/recipe reconstruction can turn a bound/paid obligation into precollection.
 */
public object LocalFoodHandoffPrecollection : LocalFoodHandoffPrecollectionProof {
    override suspend fun verify(view: CommandReadView, orderId: DomainId, orderStorageRevision: Revision): Boolean {
        val current = foodOrder(foodReadView(view), orderId, includeClosed = true)
        check(current.order.envelope.revision == orderStorageRevision)
        return current.value.financialCollectionId == null && current.value.closedByOperationId == null
    }
}
