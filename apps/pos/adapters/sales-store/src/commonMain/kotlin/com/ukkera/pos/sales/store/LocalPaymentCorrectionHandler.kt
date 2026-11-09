package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.types.*

internal data class LocalPaymentCorrectionSaleCut(val original: LocalReturnSaleCut, val session: CashSession) {
    val sale: PaidSale get() = original.sale.value
}

internal fun paymentCorrectionClosed(session: CashSession): Boolean =
    session.status in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED)

/** Immutable normalized retail source, independent of an advanced entitlement. Legacy
 * and FOOD sources do not silently become retail correction authority. */
internal suspend fun findPaymentCorrectionSale(view: CommandReadView, saleId: DomainId): LocalPaymentCorrectionSaleCut? {
    if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId),
            listOf(LocalSalesStoreProfile.unique("sale", saleId), LocalSalesStoreProfile.unique("return_entitlement", saleId)),
            listOf(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId),
                commerceLinkKey(LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, saleId))))) return null
    val original = readReturnSaleSource(view, saleId)
    if (original.priceSources?.value?.normalizedStock != true) return null
    // This verifies the actual complete Finalize/financial/stock ancestry, not just a snapshot.
    requireCommerceCurrent(view, original.sale.stored)
    val session = LocalSalesStorage.cash(view, original.sale.value.collectingSessionId).session.value
    requirePaymentCorrectionSaleSession(original.sale.value, session)
    if (paymentCorrectionClosed(session)) {
        val report = checkNotNull(session.closeReport)
        val closed = view.retainedCommand(report.operationId) ?: unavailablePaymentCorrectionSource()
        if (!requirePaymentCorrectionOriginalClose(session, closed)) return null
        val count = LocalCashCountStorage.head(view, session) ?: unavailablePaymentCorrectionSource()
        requireLocalSalesSource(count.count.id == report.count.evidence.evidenceId && count.count.count == report.count &&
            count.count.cut == report.cut)
    }
    return LocalPaymentCorrectionSaleCut(original, session)
}

internal fun requirePaymentCorrectionSaleSession(sale: PaidSale, session: CashSession) {
    requireLocalSalesSource(sale.scope == session.scope && sale.collectingSessionId == session.id &&
        sale.collectorId == session.custodianId && sale.payments.all { payment ->
            payment.collectionId == sale.payments.first().collectionId && payment.collectorId == sale.collectorId &&
                payment.cashSessionId == session.id && payment.amount.minor > 0 && payment.foodResolutionObservation == null &&
                (payment.resolutionObservation?.let { link ->
                    // Intrinsic linkage only; genuine Finalize/financial ancestry was
                    // independently joined above/by the native source owner.
                    setOf(payment.originalOperationId, link.originalUnknownOperationId, link.resolutionOperationId,
                        link.accountingOperationId).size == 4 && payment.recordingOperationId == link.resolutionOperationId &&
                        payment.attestation.actorId == link.resolutionRecorderId
                } ?: (payment.attestation.actorId == sale.collectorId))
        })
}

/** The submitted AFTER is obtained from the actual earlier original. A later Review
 * changes only review/revision/status; every original count/cut/cash fact stays identical. */
internal fun requirePaymentCorrectionOriginalClose(session: CashSession, original: RetainedCommandEvidence): Boolean {
    val report = checkNotNull(session.closeReport)
    requireLocalSalesSource(original.scope == session.scope && original.operationId == report.operationId && original.committed &&
        original.command == LocalSalesStoreProfile.CASH_COMMAND && original.nativeOriginalProvenance == null &&
        original.originalActorId == session.custodianId && LocalCashCommandCodec.normalize(original.normalized) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest)
    // Counted closing is the source-qualified native profile. The retained combined-close
    // compatibility source needs its own successful native join; it is not inferred here.
    val request = LocalCashCommandCodec.decode(original.normalized) as? LocalCashRequestV1.SubmitCountedClose ?: return false
    val write = original.aggregateWrites.singleOrNull { it.key == CashNonSaleStoreProfile.sessionKey(session.id) }
        ?: unavailablePaymentCorrectionSource()
    requireLocalSalesSource(write.schema == CashNonSaleStoreProfile.SESSION_SCHEMA && write.storageGeneration.value > 0)
    val bytes = write.payload.copyBytes()
    val submitted = try {
        SalesSnapshotCodec.decodeCashSession(bytes.decodeToString(throwOnInvalidSequence = true),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, session.scope, session.id,
                Revision(write.storageGeneration.value - 1)))
    } finally { bytes.fill(0) }
    requireLocalSalesSource(request.sessionId == session.id && request.expectedRevision.next() == submitted.revision &&
        request.countId == report.count.evidence.evidenceId && request.counted == report.count.amount &&
        request.retainedFloat == report.retainedFloat && request.varianceReason == report.varianceReason &&
        submitted.status == CashSessionStatus.SUBMITTED_CLOSED && submitted.review == null && submitted.closeReport == report &&
        submitted.id == session.id && submitted.scope == session.scope && submitted.drawerId == session.drawerId &&
        submitted.custodianId == session.custodianId && submitted.businessDate == session.businessDate &&
        submitted.opening == session.opening && submitted.openingSourceId == session.openingSourceId &&
        submitted.totals == session.totals && submitted.expectedCash == session.expectedCash &&
        submitted.reservedCashOut == session.reservedCashOut && submitted.cashLedgerRevision == session.cashLedgerRevision &&
        submitted.pendingAttemptIds == session.pendingAttemptIds && submitted.pendingTransferIds == session.pendingTransferIds &&
        submitted.closingCut == session.closingCut && report.sessionId == session.id && report.submittedBy == session.custodianId &&
        report.count.evidence.actorId == session.custodianId && report.variance == report.count.amount - report.cut.expected &&
        if (session.status == CashSessionStatus.REVIEWED) session.revision == submitted.revision.next() &&
            session.review?.originalReport == report else session.revision == submitted.revision && session.review == null)
    return true
}

internal class LocalPaymentCorrectionTenderCut(val tender: Tender?, resources: Set<RecoveryResourceKey> = emptySet()) {
    val resources: Set<RecoveryResourceKey> = resources.toSet()
}

/** The configured source may read exactly one current tender row on this real loan. */
private class PaymentCorrectionTenderRead(private val view: CommandReadView, private val key: AggregateKey) : CommandReadView by view {
    var observed: StoredAggregate? = null
        private set
    private var read = false
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        check(!read && key == this.key) { "sales.payment_correction.tender_source_invalid" }
        read = true
        return view.aggregate(key).also { observed = it }
    }
    override suspend fun claim(key: ClaimKey): DomainId? = error("sales.payment_correction.tender_source_invalid")
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage =
        error("sales.payment_correction.tender_source_invalid")
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage =
        error("sales.payment_correction.tender_source_invalid")
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
        error("sales.payment_correction.tender_source_invalid")
}

internal suspend fun currentPaymentCorrectionTender(view: CommandReadView, selection: LocalTenderSelectionV1,
    tenders: TransactionSalesTenderSource, policies: HistoricalSalesTenderPolicySourceV1): LocalPaymentCorrectionTenderCut {
    if (selection == LocalTenderSelectionV1.Cash) return LocalPaymentCorrectionTenderCut(Tender.Cash)
    selection as LocalTenderSelectionV1.External
    val loan = PaymentCorrectionTenderRead(view, LocalSalesTenderPolicyProfileV1.key(selection.tenderId))
    val current = tenders.current(loan, selection.tenderId, selection.expectedPolicyRevision)
    val row = loan.observed ?: return LocalPaymentCorrectionTenderCut(null)
    val decoded = paymentCorrectionTenderPolicy(view.scope, row, selection, policies)
    if (current == null || decoded == null) return LocalPaymentCorrectionTenderCut(null)
    requireLocalSalesSource(current.scope == view.scope && current.tender == decoded.tender &&
        current.recoveryResources == decoded.resources)
    return decoded
}

internal fun paymentCorrectionTenderPolicy(scope: com.ukkera.pos.security.BranchScope, row: StoredAggregate,
    selection: LocalTenderSelectionV1.External, policies: HistoricalSalesTenderPolicySourceV1): LocalPaymentCorrectionTenderCut? {
    requireLocalSalesSource(row.key == LocalSalesTenderPolicyProfileV1.key(selection.tenderId) &&
        row.schema == LocalSalesTenderPolicyProfileV1.SCHEMA && row.revision.value > 0 &&
        com.ukkera.pos.security.sha256(row.payload.copyBytes()) == row.digest)
    val decoded = policies.decode(scope, row) ?: return null
    val resources = setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, selection.tenderId))
    requireLocalSalesSource(decoded.scope == scope && decoded.tender.tenderId == selection.tenderId && decoded.recoveryResources == resources)
    if (decoded.tender.policyRevision != selection.expectedPolicyRevision) return null
    return LocalPaymentCorrectionTenderCut(decoded.tender, resources)
}

internal fun paymentCorrectionResources(sale: LocalPaymentCorrectionSaleCut?, saleId: DomainId,
    tender: LocalPaymentCorrectionTenderCut = LocalPaymentCorrectionTenderCut(null)): Set<RecoveryResourceKey> =
    setOf(orderResource(saleId)) + (sale?.let {
        setOf(orderResource(it.sale.orderId), cashResource(it.session.id), cashResource(it.sale.collectorId),
            entitlementResource(it.sale.id)) + priceSourcesResources(it.sale.priceSourceReference)
    } ?: emptySet()) + tender.resources

internal fun paymentCorrectionFirstRefusal(request: LocalPaymentCorrectionRequestV1, source: LocalPaymentCorrectionSaleCut?,
    payment: PaymentRecord?, head: LocalPaymentCorrectionHeadCut?, duplicate: Boolean,
    tender: LocalPaymentCorrectionTenderCut): LocalPaymentCorrectionRefusalV1? = when {
    source == null -> LocalPaymentCorrectionRefusalV1.SOURCE_NOT_AVAILABLE
    payment == null -> LocalPaymentCorrectionRefusalV1.PAYMENT_NOT_AVAILABLE
    !paymentCorrectionClosed(source.session) -> LocalPaymentCorrectionRefusalV1.SESSION_NOT_CLOSED
    source.session.revision != request.expectedSessionRevision || payment.amount != request.expectedAmount ||
        head?.latest?.id != request.expectedLatestCorrectionId -> LocalPaymentCorrectionRefusalV1.STALE_SOURCE
    duplicate || request.evidence.evidenceId == head?.latest?.id -> LocalPaymentCorrectionRefusalV1.DUPLICATE_CORRECTION
    tender.tender == null -> LocalPaymentCorrectionRefusalV1.TENDER_NOT_AVAILABLE
    tender.tender is Tender.External &&
        (tender.tender.requiresTerminal && request.terminal == null || tender.tender.requiresReference && request.reference == null) ->
        LocalPaymentCorrectionRefusalV1.INVALID_TENDER_EVIDENCE
    PaymentClassificationCorrections.sameClassification(head?.latest?.correctedTender?.let(::paymentCorrectionTender) ?: payment.tender,
        tender.tender) -> LocalPaymentCorrectionRefusalV1.NO_CLASSIFICATION_CHANGE
    head?.row?.value?.revision?.value == Long.MAX_VALUE - 1 -> LocalPaymentCorrectionRefusalV1.CAPACITY_REQUIRED
    else -> null
}

internal fun paymentCorrectionRefused(reason: LocalPaymentCorrectionRefusalV1): ProposedOutcome.Reject =
    ProposedOutcome.Reject(LocalPaymentCorrectionProfileV1.REFUSAL,
        LocalPaymentCorrectionCodec.result(LocalPaymentCorrectionResultV1.Refused(reason)))

/** Current explicit permission is enforced by the kernel on the same serialized writer.
 * No cash/session, sale, receipt, stock, refund or entitlement row is writable here. */
public class LocalPaymentCorrectionHandler(private val stock: LocalStockRepository,
    private val tenders: TransactionSalesTenderSource = UnavailableSalesTenderSource,
    private val policies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : MasterCommandHandler, LocalMasterRpcRetainedResultAccessProvider {
    override val key: CommandKey get() = LocalPaymentCorrectionCodec.COMMAND
    override val writableKinds: Set<String> = setOf(LocalPaymentCorrectionProfileV1.RECORD, LocalPaymentCorrectionProfileV1.HEAD)
    override val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE)
    override fun normalize(input: StoredBytes): StoredBytes = LocalPaymentCorrectionCodec.normalize(input)

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS && normalize(normalized) == normalized

    /** Current disclosure of this exact retained original never re-prepares a correction. */
    override fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        context: CommandContext): List<AuthorizationRequirement> {
        check(supportsRpcWorkflow(profile, normalized) && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalPaymentCorrectionCodec.decode(normalized)
        return listOf(AuthorizationRequirement(Permission(SecurityAction.PAYMENT_CLASSIFICATION_CORRECT, PermissionVerb.PERFORM),
            context.scope, ActionBinding(request.saleId, request.expectedSessionRevision, context.semanticDigest,
                context.operationId, LocalPaymentCorrectionProfileV1.REASON), LimitUse.Monetary(request.expectedAmount)))
    }


    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(view.scope == context.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalPaymentCorrectionCodec.decode(normalized)
        val loan = commerceLoan(view, stock, tenderPolicies = policies)
        val source = findPaymentCorrectionSale(loan, request.saleId)
        val payment = source?.sale?.payments?.singleOrNull { it.attemptId == request.paymentAttemptId }
        val records = LocalCurrentPaymentCorrectionSources(loan)
        val head = if (source != null && payment != null && paymentCorrectionClosed(source.session))
            records.head(source.sale, payment, source.session) else null
        val id = request.evidence.evidenceId
        val duplicate = localSalesSourcePresent(loan, LocalPaymentCorrectionProfileV1.record(id),
            listOf(LocalPaymentCorrectionProfileV1.recordClaim(id)))
        if (duplicate) records.record(id)
        val tender = currentPaymentCorrectionTender(loan, request.correctedTender, tenders, policies)
        val refusal = paymentCorrectionFirstRefusal(request, source, payment, head, duplicate, tender)
        if (refusal == null) {
            val position = head?.row?.value?.revision?.next() ?: Revision(0)
            requireLocalSalesSource(records.claim(LocalPaymentCorrectionProfileV1.positionClaim(request.paymentAttemptId, position)) == null &&
                records.claim(LocalPaymentCorrectionProfileV1.membershipClaim(request.paymentAttemptId, id)) == null)
        }
        // Only absence/refusal lacks an original payment amount. It cannot produce a
        // correction; every possible Apply authorizes the actual immutable amount.
        return LocalSalesPrepared(context, SecurityAction.PAYMENT_CLASSIFICATION_CORRECT, request.saleId,
            request.expectedSessionRevision, context.originalActorId, LocalPaymentCorrectionProfileV1.REASON,
            payment?.amount ?: request.expectedAmount, paymentCorrectionResources(source, request.saleId, tender)) { command, now ->
            if (refusal != null) paymentCorrectionRefused(refusal) else {
                val actual = checkNotNull(source)
                val previous = head?.latest?.correctedTender?.let(::paymentCorrectionTender) ?: checkNotNull(payment).tender
                val value = PaymentClassificationCorrections.record(command, actual.sale, request.paymentAttemptId, actual.session,
                    request.expectedSessionRevision, request.expectedAmount, checkNotNull(tender.tender),
                    head?.row?.value?.revision?.next() ?: Revision(0), head?.latest?.id, previous,
                    request.evidence.domain(context.originalActorId), request.reason, request.terminal, request.reference)
                paymentCorrectionOutcome(LocalSalesScope.from(context.scope), paymentCorrectionView(value, now),
                    actual.session.revision, head?.row?.stored?.revision)
            }
        }
    }
}

private fun paymentCorrectionView(value: PaymentClassificationCorrection, at: UtcInstant): LocalPaymentCorrectionViewV1 =
    LocalPaymentCorrectionViewV1(value.saleId, value.paymentAttemptId, value.sessionId, value.collectorId,
        value.originalIntentOperationId, value.originalRecordingOperationId, value.originalCloseOperationId, value.amount,
        tenderView(value.originalTender), tenderView(value.previousClassification), tenderView(value.correctedTender),
        value.previousClassificationDelta, value.correctedClassificationDelta, value.physicalCashDelta, value.position,
        value.previousCorrectionId, LocalCashActorEvidenceV1(value.id, value.evidence.actorId, UtcInstant(value.evidence.occurredAt)),
        value.reason, value.terminal, value.reference, value.operationId, at)

/** Inert historical producer: no employee authorization is reconstructed. The caller
 * separately joins original authority and proves every exact BEFORE source. */
internal fun paymentCorrectionHistoricalView(request: LocalPaymentCorrectionRequestV1, source: LocalPaymentCorrectionSaleCut,
    payment: PaymentRecord, head: LocalPaymentCorrectionHeadCut?, tender: Tender, actorId: DomainId,
    operationId: OperationId, at: UtcInstant): LocalPaymentCorrectionViewV1 {
    requirePaymentCorrectionSaleSession(source.sale, source.session)
    val previous = head?.latest?.correctedTender?.let(::paymentCorrectionTender) ?: payment.tender
    val position = head?.row?.value?.revision?.next() ?: Revision(0)
    requireLocalSalesSource(request.saleId == source.sale.id && request.paymentAttemptId == payment.attemptId &&
        payment in source.sale.payments && paymentCorrectionClosed(source.session) &&
        source.session.revision == request.expectedSessionRevision && payment.amount == request.expectedAmount &&
        request.expectedLatestCorrectionId == head?.latest?.id && request.evidence.evidenceId != head?.latest?.id &&
        position.value < Long.MAX_VALUE && tenderSelectionMatches(request.correctedTender, tender) &&
        !PaymentClassificationCorrections.sameClassification(previous, tender))
    when (tender) {
        Tender.Cash -> requireLocalSalesSource(request.terminal == null && request.reference == null)
        is Tender.External -> requireLocalSalesSource((!tender.requiresTerminal || request.terminal != null) &&
            (!tender.requiresReference || request.reference != null))
    }
    return LocalPaymentCorrectionViewV1(source.sale.id, payment.attemptId, source.session.id, source.sale.collectorId,
        payment.originalOperationId, payment.recordingOperationId, checkNotNull(source.session.closeReport).operationId,
        payment.amount, tenderView(payment.tender), tenderView(previous), tenderView(tender),
        Money(payment.amount.currency, -payment.amount.minor), payment.amount, Money(payment.amount.currency, 0), position,
        head?.latest?.id, LocalCashActorEvidenceV1(request.evidence.evidenceId, actorId, request.evidence.occurredAt),
        request.reason, request.terminal, request.reference, operationId, at)
}

internal fun paymentCorrectionOutcome(scope: LocalSalesScope, value: LocalPaymentCorrectionViewV1,
    sessionRevision: Revision, previousHeadGeneration: Revision?): ProposedOutcome.Apply {
    val profile = LocalPaymentCorrectionProfileV1
    val head = LocalPaymentCorrectionHeadV1(scope, value.saleId, value.paymentAttemptId, value.sessionId,
        value.originalCloseOperationId, value.position, value.id)
    requireLocalSalesSource(previousHeadGeneration == if (value.position == Revision(0)) null else value.position)
    return ProposedOutcome.Apply(LocalPaymentCorrectionCodec.result(LocalPaymentCorrectionResultV1.Recorded(value, sessionRevision)),
        listOf(rootWrite(profile.RECORD, value.id, LocalPaymentCorrectionRecordV1(scope, value),
            LocalPaymentCorrectionRecordV1.serializer(), null),
            AggregateChange(profile.head(value.paymentAttemptId), previousHeadGeneration,
                LocalSalesStoreProfile.schema(profile.HEAD), localSalesEncode(head, LocalPaymentCorrectionHeadV1.serializer()))),
        listOf(ClaimChange.Acquire(profile.recordClaim(value.id), value.saleId),
            ClaimChange.Acquire(profile.positionClaim(value.paymentAttemptId, value.position), value.id),
            ClaimChange.Acquire(profile.membershipClaim(value.paymentAttemptId, value.id), value.saleId)) +
            if (previousHeadGeneration == null) listOf(ClaimChange.Acquire(profile.headClaim(value.paymentAttemptId), value.saleId)) else emptyList())
}

internal fun paymentCorrectionCloseView(session: CashSession): LocalPaymentCorrectionCloseViewV1 {
    val report = checkNotNull(session.closeReport)
    return LocalPaymentCorrectionCloseViewV1(session.id, report.operationId, report.cut.cashLedgerRevision, report.cut.expected,
        report.cut.externalCollections, report.cut.externalRefunds, report.count.amount, report.variance, report.retainedFloat,
        report.actualSafeDelivery, report.safeTransferId, report.submittedBy,
        LocalCashActorEvidenceV1(report.count.evidence.evidenceId, report.count.evidence.actorId, UtcInstant(report.count.evidence.occurredAt)),
        report.varianceReason)
}

internal fun paymentCorrectionPaymentView(payment: PaymentRecord, head: LocalPaymentCorrectionHeadCut?): LocalPaymentCorrectionPaymentViewV1 =
    LocalPaymentCorrectionPaymentViewV1(payment.attemptId, payment.amount, tenderView(payment.tender), payment.originalOperationId,
        payment.recordingOperationId, LocalCashActorEvidenceV1(payment.attestation.evidenceId, payment.attestation.actorId,
            UtcInstant(payment.attestation.occurredAt)), payment.payerReference, payment.externalEvidence?.terminal,
        payment.externalEvidence?.reference, head?.latest?.id, head?.latest?.correctedTender ?: tenderView(payment.tender),
        head?.row?.value?.revision?.value?.plus(1) ?: 0L)

/** Both source and backward history are protected by current review authority on the
 * original collecting session. A later classification never counts as current cash. */
public class LocalPaymentCorrectionReadHandler(private val stock: LocalStockRepository,
    private val policies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1) : MasterReadHandler {
    override val key: ReadKey get() = LocalPaymentCorrectionCodec.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalPaymentCorrectionCodec.request(
        localSalesDecode(input, LocalPaymentCorrectionQueryV1.serializer(), 2_048)).payload
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS && normalize(normalized) == normalized

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(view.scope == context.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalPaymentCorrectionQueryV1.serializer(), 2_048)
        val saleId = when (query) {
            is LocalPaymentCorrectionQueryV1.Source -> query.saleId
            is LocalPaymentCorrectionQueryV1.History -> query.saleId
        }
        val loan = commerceLoan(view, stock, tenderPolicies = policies)
        val source = findPaymentCorrectionSale(loan, saleId)
        fun result(value: LocalPaymentCorrectionReadResultV1): PreparedRead = localSalesRead(context, SecurityAction.CASH_REVIEW,
            source?.session?.id ?: saleId, source?.session?.revision ?: Revision(0), source?.sale?.collectorId ?: context.actorId,
            "payment.classification.history", value, LocalPaymentCorrectionReadResultV1.serializer())
        if (source == null || !paymentCorrectionClosed(source.session)) return result(LocalPaymentCorrectionReadResultV1.NotAvailable)
        val records = LocalCurrentPaymentCorrectionSources(loan)
        when (query) {
            is LocalPaymentCorrectionQueryV1.Source -> {
                val payments = source.sale.payments.map { payment ->
                    paymentCorrectionPaymentView(payment, records.head(source.sale, payment, source.session))
                }
                return result(LocalPaymentCorrectionReadResultV1.Source(LocalPaymentCorrectionSourceViewV1(source.sale.id,
                    source.sale.orderId, source.sale.collectorId, source.session.id, source.session.revision,
                    paymentCorrectionCloseView(source.session), payments)))
            }
            is LocalPaymentCorrectionQueryV1.History -> {
                val payment = source.sale.payments.singleOrNull { it.attemptId == query.paymentAttemptId }
                    ?: return result(LocalPaymentCorrectionReadResultV1.NotAvailable)
                val head = records.head(source.sale, payment, source.session)
                val position = query.before?.let { cursor ->
                    if (!localSalesSourcePresent(loan, LocalPaymentCorrectionProfileV1.record(cursor),
                            listOf(LocalPaymentCorrectionProfileV1.recordClaim(cursor)))) return result(LocalPaymentCorrectionReadResultV1.NotAvailable)
                    val previous = records.record(cursor)
                    if (previous.saleId != saleId || previous.paymentAttemptId != payment.attemptId ||
                        head == null || previous !in head.corrections) return result(LocalPaymentCorrectionReadResultV1.NotAvailable)
                    previous.position.value
                }
                val eligible = head?.corrections.orEmpty().asReversed().filter { position == null || it.position.value < position }
                val page = eligible.take(query.limit)
                return result(LocalPaymentCorrectionReadResultV1.History(LocalPaymentCorrectionsPageV1(source.sale.id,
                    source.session.id, source.session.revision, paymentCorrectionCloseView(source.session),
                    paymentCorrectionPaymentView(payment, head), query.before, page, if (eligible.size > page.size) page.last().id else null)))
            }
        }
    }
}
