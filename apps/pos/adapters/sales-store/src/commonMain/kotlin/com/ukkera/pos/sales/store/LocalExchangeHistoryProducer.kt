package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.ExchangeOffsetSlice
import com.ukkera.pos.sales.Exchanges
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Money

/** Whole positive exchange calculation on the actual original BEFORE. Observed
 * collection bytes, a stored exchange shape or equal totals cannot supply a return
 * obligation. Every earlier writer is already whole-joined by the owning program. */
public class LocalExchangeHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalExchangeCodec.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            !original.committed || original.code != "committed" || original.nativeOriginalProvenance != null) return false
        requireOriginal(NativeBusinessOriginalProgram(command))
        return true
    }

    public suspend fun requireOriginal(command: BusinessOriginalProgram) {
        val original = command.evidence
        check(command.command in commands && original.committed && original.code == "committed" &&
            original.nativeOriginalProvenance == null && original.jobs.isEmpty() &&
            LocalExchangeCodec.normalize(command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val request = LocalExchangeCodec.decode(command.normalized)
        val collection = readOriginalNormalizedCollectionBefore(command, request.collectionId)
        val returned = retailRefundHistorySources(command, request.saleId, request.claimId, request.returnId,
            collection.collection.value.cashSessionId).returned
        val ids = request.slices.map { it.attemptId }
        check(command.claim(LocalExchangeCodec.claim(request.exchangeId)) == null &&
            command.aggregate(LocalExchangeCodec.key(request.exchangeId)) == null)
        for (id in ids) check(!financialSourceAllocated(command, id) &&
            command.claim(LocalSalesStoreProfile.unique("payment_record", id)) == null &&
            command.claim(LocalSalesStoreProfile.unique("refund_record", id)) == null)

        val current = collection.collection.value
        val order = collection.order.order.value
        val session = collection.cash.session.value
        check(session.status == CashSessionStatus.OPEN && session.custodianId == command.actorId &&
            current.collectorId == command.actorId && order.ownerId == current.collectorId &&
            current.saleId == null && order.paidSaleId == null && order.handoff == null &&
            order.collectionId == current.id && order.revision == current.lockedOrderRevision)
        val link = LocalSalesStorage.required(command, commerceLinkKey(collection.collection.stored.key), commerceLinkSchema())
        val observed = checkNotNull(command.requireLegacyReadEvidence()) { "Original exchange collection reads required" }
        NormalizedPaymentSources(observed).also { it.collection(current, order, session); it.finish(stock = false) }
        val readEvidence = StockReadEvidenceV1.proposedChanges(command.scope,
            StreamedProducerIdentityV2(command.operationId, command.command, command.actorId, command.semanticDigest), observed.sources)
        val amount = request.slices.fold(Money(current.quote.payable.currency, 0L)) { sum, slice ->
            check(slice.amount.currency == sum.currency && slice.amount.minor <= Long.MAX_VALUE - sum.minor)
            Money(sum.currency, sum.minor + slice.amount.minor)
        }
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RECORD, command.scope,
            returned.accepted.value.id, request.expectedReturnRevision, command.operationId, command.semanticDigest,
            "exchange.offset.apply", current.collectorId, amount))
        val after = Exchanges.originalOffsetValue(returned.accepted.value, request.expectedReturnRevision, current,
            request.expectedCollectionRevision, request.exchangeId,
            request.slices.map { ExchangeOffsetSlice(it.attemptId, it.originalPaymentId, it.amount) },
            request.evidence.domain(command.actorId), command.operationId, command.actorId)
        check(after.record.amount == amount)
        SalesSnapshotLinks.requireAcceptedReturn(after.acceptedReturn, returned.original.original.entitlement.value,
            returned.original.claim.value)
        SalesSnapshotLinks.requireCollection(after.collection, order, session)
        val resources = setOf(entitlementResource(returned.accepted.value.id), entitlementResource(returned.original.claim.value.id),
            entitlementResource(returned.original.original.entitlement.value.saleId), orderResource(returned.work.value.id),
            entitlementResource(request.exchangeId)) + ids.map(::entitlementResource) + collectionResources(collection) +
            returned.original.caseRecoveryResources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)
        command.requireLegacyEffects(exchangeOutcome(command.scope, command.operationId, command.actorId, command.semanticDigest,
            request, returned, collection, link, after.acceptedReturn, after.collection, after.record, readEvidence, command.recordedAt), resources)
    }
}
