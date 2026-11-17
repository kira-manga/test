package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Whole positive legacy reservation/finalization program. It uses genuine qualified
 * BEFORE facts, the same inventory planner, fact reducers and literal effects as live
 * collection. Historical tender starts/observations need their separate actual native
 * journal proof; this program never fabricates a recording capability or payment. */
public class LocalLegacyCollectionHistoryProducer(private val stock: LocalStockRepository) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalSalesStoreProfile.COLLECTION_COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            !original.committed || original.code != "committed" || original.nativeOriginalProvenance != null) return false
        val request = LocalCollectionCommandCodec.decode(command.normalized)
        if (request !is LocalCollectionRequestV1.Begin && request !is LocalCollectionRequestV1.Finalize) return false
        requireOriginal(NativeBusinessOriginalProgram(command))
        return true
    }

    public suspend fun requireOriginal(command: BusinessOriginalProgram) {
        val original = command.evidence
        check(command.command in commands && original.committed && original.code == "committed" &&
            original.nativeOriginalProvenance == null && LocalCollectionCommandCodec.normalize(command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val outcome: ProposedOutcome.Apply
        val resources: Set<RecoveryResourceKey>
        when (val request = LocalCollectionCommandCodec.decode(command.normalized)) {
            is LocalCollectionRequestV1.Begin -> {
                val order = readLegacyOrderBefore(command, request.orderId)
                val cash = LocalSalesStorage.cash(command, request.sessionId)
                val skuIds = order.order.value.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
                val inventory = stock.observe(command, skuIds.toList())
                val unique = LocalSalesStoreProfile.unique("collection", request.collectionId)
                val duplicate = command.claim(unique) != null ||
                    command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.COLLECTION_KIND, request.collectionId)) != null ||
                    command.claim(LocalStockStoreProfile.reservationClaim(request.reservationId)) != null ||
                    command.aggregate(LocalStockStoreProfile.reservationKey(request.reservationId)) != null
                val allocated = order.order.value.stockRequirements.allocations.any {
                    command.claim(LocalStockStoreProfile.allocationClaim(it.allocationId)) != null
                }
                resources = cashResources(cash) + stock.resources(inventory) + orderResources(order) + orderResource(request.collectionId)
                command.requireRequirement(salesAuthorizationRequirement(SecurityAction.COLLECTION_BEGIN, command.scope,
                    request.orderId, request.expectedOrderRevision, command.operationId, command.semanticDigest,
                    "collection.begin", order.order.value.ownerId, order.order.value.quote.payable))
                check(!duplicate && !allocated && requiredStockVersions(inventory, request.items, skuIds) &&
                    order.order.value.ownerId == cash.session.value.custodianId)
                val reserved = InventoryPlanner.reserve(inventory.snapshot, inventory.snapshot.revision, command.operationId,
                    request.reservationId, CollectionBinding(request.orderId, order.order.value.quote.revision, request.collectionId,
                        command.actorId, CollectionExposure.UNKNOWN), order.order.value.stockRequirements.allocations,
                    command.recordedAt.value.toEpochMilliseconds(), request.reservationExpiresAt?.value?.toEpochMilliseconds())
                val after = Collections.begunCollection(command.actorId, command.operationId, command.recordedAt.value,
                    order.order.value, request.expectedOrderRevision, cash.session.value, request.expectedSessionRevision,
                    request.collectionId, reserved)
                SalesSnapshotLinks.requireCollection(after.collection, after.order, cash.session.value)
                val contribution = stock.projectOriginal(inventory, original, reserved.plan, command.recordedAt)
                val effects = legacyCollectionBeginEffects(command.operationId, order, cash, after, legacyCollectionStockVersions(contribution))
                outcome = stock.composeOriginal(original, contribution, LocalSalesResultCodec.encode(effects.result), effects.changes, effects.claims)
            }
            is LocalCollectionRequestV1.Finalize -> {
                val cut = readLegacyCollectionBefore(command, request.collectionId)
                val collection = cut.collection.value
                val order = cut.order.order.value
                val skuIds = collection.stockRequirements.allocations.map { it.position.pool.skuId }.toSet()
                val action = if (collection.quote.payable.minor == 0L) SecurityAction.COLLECTION_FINALIZE_ZERO else SecurityAction.COLLECTION_FINALIZE
                val inventory = stock.observe(command, skuIds.toList(), listOf(collection.reservationId))
                val reservation = requireCollectionReservation(cut, inventory)
                val unique = listOf(LocalSalesStoreProfile.unique("sale", request.saleId), LocalSalesStoreProfile.unique("sale_order", order.id),
                    LocalSalesStoreProfile.unique("return_entitlement", request.saleId))
                val duplicate = unique.any { command.claim(it) != null } ||
                    command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, request.saleId)) != null ||
                    command.aggregate(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, request.saleId)) != null ||
                    command.claim(LocalStockStoreProfile.sourceClaim(request.stockIssueId)) != null ||
                    command.aggregate(LocalStockStoreProfile.issueKey(request.stockIssueId)) != null
                resources = collectionResources(cut) + stock.resources(inventory) + entitlementResource(request.saleId)
                command.requireRequirement(salesAuthorizationRequirement(action, command.scope, order.id, request.expectedOrderRevision,
                    command.operationId, command.semanticDigest, "collection.finalize", order.ownerId, collection.quote.payable))
                check(collectionAttemptAdmission(cut, command.actorId, request.expectedCollectionRevision, request.expectedSessionRevision) == null &&
                    order.revision == request.expectedOrderRevision && !collection.unresolved && collection.collected == collection.quote.payable &&
                    !duplicate && requiredStockVersions(inventory, request.items, skuIds) && reservation.revision == request.expectedReservationRevision)
                val issued = InventoryPlanner.issueReservation(inventory.snapshot, inventory.snapshot.revision, command.operationId,
                    collection.reservationId, request.expectedReservationRevision, request.stockIssueId, command.recordedAt.value.toEpochMilliseconds())
                val before = collectionBefore(command.operationId, listOf("order" to cut.order.order.stored, "collection" to cut.collection.stored,
                    "cash" to cut.cash.session.stored, "work" to cut.order.work.stored) + priceSourcesBefore(cut.order))
                when (issued) {
                    is ReservationIssueResult.Held -> {
                        val contribution = stock.projectOriginal(inventory, original, issued.plan, command.recordedAt)
                        val held = contribution.plan.nextSnapshot.reservation(collection.reservationId) ?: error("Original held reservation required")
                        check(held.status == ReservationStatus.HELD && held.binding.exposure != CollectionExposure.UNCOLLECTED)
                        val result = LocalSalesResultV1.StockResolutionRequired(collection.id, held.reservationId, held.revision,
                            legacyCollectionStockVersions(contribution))
                        outcome = stock.composeOriginal(original, contribution, LocalSalesResultCodec.encode(result), before)
                    }
                    is ReservationIssueResult.Issued -> {
                        val after = Collections.finalizedPaidSale(command.actorId, command.operationId, command.recordedAt.value,
                            order, request.expectedOrderRevision, collection, request.expectedCollectionRevision, request.saleId, issued)
                        SalesSnapshotLinks.requireCollection(after.collection, after.order, cut.cash.session.value)
                        val contribution = stock.projectOriginal(inventory, original, issued.plan, command.recordedAt)
                        val effects = legacyCollectionFinalizeEffects(cut, after, before, legacyCollectionStockVersions(contribution))
                        outcome = stock.composeOriginal(original, contribution, LocalSalesResultCodec.encode(effects.result), effects.changes, effects.claims)
                    }
                }
            }
            else -> error("Original legacy physical recording proof required")
        }
        command.requireLegacyEffects(outcome, resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
    }
}
