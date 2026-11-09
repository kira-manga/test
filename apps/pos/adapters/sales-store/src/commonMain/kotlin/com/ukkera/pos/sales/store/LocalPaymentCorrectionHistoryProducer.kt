package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Exact append-only classification success/refusal joins. Every present BEFORE source
 * has its earlier whole business/native join in the same active scan. This never calls a
 * handler, manufactures a historical view/grant or changes original money/stock facts. */
public class LocalPaymentCorrectionHistoryProducer(
    private val policies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalPaymentCorrectionCodec.COMMAND)
    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            command.supervisorReview != null) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (original.nativeOriginalProvenance != null || original.jobs.isNotEmpty()) return false
        val result = LocalPaymentCorrectionCodec.result(original.result)
        if (result is LocalPaymentCorrectionResultV1.Recorded && !original.committed ||
            result is LocalPaymentCorrectionResultV1.Refused && (original.committed ||
                original.code != LocalPaymentCorrectionProfileV1.REFUSAL ||
                original.aggregateWrites.isNotEmpty() || original.claimChanges.isNotEmpty())) return false
        check(original.scope == command.scope && original.command == command.command && original.operationId == command.operationId &&
            original.originalActorId == command.actorId && original.authenticationId == command.authenticationId &&
            original.policyRevision == command.policyRevision && original.semanticDigest == command.semanticDigest &&
            original.recordedAt == command.recordedAt && original.normalized == command.normalized &&
            LocalPaymentCorrectionCodec.normalize(command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val request = LocalPaymentCorrectionCodec.decode(command.normalized)
        val source = findPaymentCorrectionHistorySale(command, request.saleId)
        val payment = source?.sale?.payments?.singleOrNull { it.attemptId == request.paymentAttemptId }
        val records = LocalNativePaymentCorrectionSources(command)
        val head = if (source != null && payment != null && paymentCorrectionClosed(source.session))
            records.head(source.sale, payment, source.session) else null
        val id = request.evidence.evidenceId
        val record = records.row(LocalPaymentCorrectionProfileV1.record(id))
        val owner = records.claim(LocalPaymentCorrectionProfileV1.recordClaim(id))
        check((record == null) == (owner == null))
        val duplicate = record != null
        if (duplicate) records.record(id)
        val tender = when (val selection = request.correctedTender) {
            LocalTenderSelectionV1.Cash -> LocalPaymentCorrectionTenderCut(com.ukkera.pos.sales.Tender.Cash)
            is LocalTenderSelectionV1.External -> {
                // The policy is this correction's actual native BEFORE row. The catalog
                // writer has already joined it; a saved tender marker cannot supply it.
                val row = command.aggregateSource(LocalSalesTenderPolicyProfileV1.key(selection.tenderId))?.row
                row?.let { paymentCorrectionTenderPolicy(command.scope, it, selection, policies) }
                    ?: LocalPaymentCorrectionTenderCut(null)
            }
        }
        val refusal = paymentCorrectionFirstRefusal(request, source, payment, head, duplicate, tender)
        val resources = paymentCorrectionResources(source, request.saleId, tender) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.PAYMENT_CLASSIFICATION_CORRECT, command.scope,
            request.saleId, request.expectedSessionRevision, command.operationId, command.semanticDigest,
            LocalPaymentCorrectionProfileV1.REASON, command.actorId, payment?.amount ?: request.expectedAmount))
        if (refusal != null) {
            if (result !is LocalPaymentCorrectionResultV1.Refused) return false
            val expected = paymentCorrectionRefused(refusal)
            if (command.originalApproval != null) command.requireApprovedLegacyRejection(expected, resources)
            else command.requirePaymentCorrectionRejection(expected, resources)
        } else {
            if (result !is LocalPaymentCorrectionResultV1.Recorded) return false
            val actual = checkNotNull(source)
            val value = paymentCorrectionHistoricalView(request, actual, checkNotNull(payment), head, checkNotNull(tender.tender),
                command.actorId, command.operationId, command.recordedAt)
            check(records.claim(LocalPaymentCorrectionProfileV1.positionClaim(request.paymentAttemptId, value.position)) == null &&
                records.claim(LocalPaymentCorrectionProfileV1.membershipClaim(request.paymentAttemptId, id)) == null)
            command.requireLegacyEffects(paymentCorrectionOutcome(LocalSalesScope.from(command.scope), value,
                actual.session.revision, head?.row?.stored?.revision), resources)
        }
        return true
    }
}

/** Immutable normalized original SALE/Finalize/quote/capture and native closed till.
 * Unsupported originals stay unavailable; no legacy/FOOD or today's entitlement fallback. */
private suspend fun findPaymentCorrectionHistorySale(command: RuntimeHistoricalCommand,
    saleId: DomainId): LocalPaymentCorrectionSaleCut? {
    val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId)
    val source = command.aggregateSource(key)
    if (source == null) {
        check(command.claim(LocalSalesStoreProfile.unique("sale", saleId)) == null &&
            command.claim(LocalSalesStoreProfile.unique("return_entitlement", saleId)) == null &&
            command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId)) == null &&
            command.aggregateSource(commerceLinkKey(key)) == null)
        return null
    }
    if (source.original !is RetainedOriginalV2.Streamed) return null
    val original = readReturnCaseHistorySaleSource(command, saleId)
    if (original.priceSources?.value?.normalizedStock != true) return null
    check(original.sale.stored.key == source.row.key && original.sale.stored.revision == source.row.revision &&
        original.sale.stored.schema == source.row.schema && original.sale.stored.payload == source.row.payload &&
        original.sale.stored.digest == source.row.digest)
    val read = LocalCashHistoryRead(command)
    val session = read.cash(original.sale.value.collectingSessionId).session.value
    requirePaymentCorrectionSaleSession(original.sale.value, session)
    if (paymentCorrectionClosed(session)) {
        val report = checkNotNull(session.closeReport)
        val closed = (command.earlierOriginal(report.operationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: unavailablePaymentCorrectionSource()
        if (!requirePaymentCorrectionOriginalClose(session, closed)) return null
        val count = read.countHead(session) ?: unavailablePaymentCorrectionSource()
        check(count.count.id == report.count.evidence.evidenceId && count.count.count == report.count && count.count.cut == report.cut)
    }
    return LocalPaymentCorrectionSaleCut(original, session)
}
