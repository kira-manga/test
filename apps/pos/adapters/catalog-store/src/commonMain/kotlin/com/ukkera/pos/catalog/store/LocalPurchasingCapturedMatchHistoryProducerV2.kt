package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.purchaseReceiptFacts
import com.ukkera.pos.inventory.store.LocalStockHydrationCapacityUnavailable
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.*

/** Exact genuine-native BEFORE reconstruction of the combined financial action.
 * No historical read view, session, current PO substitution or result adoption is used. */
internal class LocalPurchasingCapturedMatchHistoryProducerV2(private val stock: LocalStockRepository) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalPurchasingProfile.MATCH_CAPTURED_INVOICE)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Legacy ||
            command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val original = (command.original as RetainedOriginalV2.Legacy).evidence
        check(purchasingNormalize(command.command, original.normalized) == original.normalized &&
            semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest &&
            original.scope == command.scope && original.operationId == command.operationId && original.originalActorId == command.actorId &&
            original.command == command.command && original.normalized == command.normalized && original.semanticDigest == command.semanticDigest &&
            original.recordedAt == command.recordedAt && original.policyRevision == command.policyRevision)
        if (original.committed) requirePurchasingProducer(original)
        val request = LocalPurchasingCodec.decodeCapturedMatch(command.normalized)
        val reader = PurchasingReader(command)
        val selected = reader.row(LocalPurchasingProfile.INVOICE, request.invoiceId)
        // No actual captured claim means no amount-bound action ever persisted here.
        if (selected?.value?.body !is PurchasingBody.InvoiceCapture) return false
        val claim = purchasingInvoiceClaim(selected)
        command.requireRequirement(purchasingCapturedMatchRequirementV2(command.scope, claim.id, selected.row.revision,
            command.operationId, command.semanticDigest, request.reasonCode, claim.total()))
        val selection = request.sourceQuery()
        val baseResources = purchasingCapturedMatchResourcesV2(selected, selection) + supplierSettlementResource(request.matchId) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)
        try {
            val cut = purchasingCapturedMatchCutV2(reader, selection, request.matchId,
                { receiptId, lineId -> stock.purchaseReceiptFacts(command, receiptId, lineId) },
                { invoice -> requirePurchasingCrossAccounts(reader, invoice, stock, command) })
            val resources = cut.resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)
            val expected = try {
                // Preserve the earlier first refusal instead of reevaluating it into
                // a new match or a later receipt error. Exact native refusal checks
                // below still bind the original result, resources and absence of effects.
                val slice = purchasingCapturedMatchSliceV2(selection, cut, request,
                    literalTermsOnly = !original.committed && original.code == "purchasing.variance_requires_approval")
                reader.requireSupplierHydrationCapacity()
                purchasingCapturedMatchEffectsV2(request, cut, slice) { kind, id, before, body ->
                    AggregateChange(purchasingKey(kind, id), before?.row?.revision, LocalPurchasingProfile.schema(kind),
                        purchasingEncode(PurchasingRecord(command.scope, kind, id, (before?.row?.revision ?: Revision(0)).next(),
                            PurchasingProducer(command.operationId, command.actorId, command.command.name, command.semanticDigest.value),
                            command.recordedAt, command.policyRevision, body), PurchasingRecord.serializer()))
                }
            } catch (refusal: PurchaseRejected) { purchasingCapturedMatchRefusalV2(refusal.code, request, selected) }
            when (expected) {
                is ProposedOutcome.Apply -> { check(original.committed); command.requireLegacyEffects(expected, resources) }
                is ProposedOutcome.Reject -> { check(!original.committed); command.requireCapturedInvoiceMatchRejection(expected, resources) }
            }
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            check(!original.committed && original.code == "purchasing.capacity_unavailable")
            command.requireCapturedInvoiceMatchRejection(purchasingCapturedMatchRefusalV2("purchasing.capacity_unavailable", request, selected), baseResources)
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            check(!original.committed && original.code == "purchasing.capacity_unavailable")
            command.requireCapturedInvoiceMatchRejection(purchasingCapturedMatchRefusalV2("purchasing.capacity_unavailable", request, selected), baseResources)
        }
        return true
    }
}
