package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Immutable documentary rows. The original payment, sale and closed till are never written. */
@Serializable
internal data class LocalPaymentCorrectionRecordV1(val scope: LocalSalesScope,
    val correction: LocalPaymentCorrectionViewV1)

@Serializable
internal data class LocalPaymentCorrectionHeadV1(val scope: LocalSalesScope, val saleId: DomainId,
    val paymentAttemptId: DomainId, val sessionId: DomainId, val originalCloseOperationId: OperationId,
    val revision: Revision, val latestCorrectionId: DomainId)

internal class LocalPaymentCorrectionHeadCut(val row: LocalSalesRow<LocalPaymentCorrectionHeadV1>,
    corrections: List<LocalPaymentCorrectionViewV1>) {
    val corrections: List<LocalPaymentCorrectionViewV1> = corrections.toList()
    val latest: LocalPaymentCorrectionViewV1 get() = corrections.last()
}

internal object LocalPaymentCorrectionProfileV1 {
    const val RECORD: String = "sales.payment.correction"
    const val HEAD: String = "sales.payment.correction-head"
    const val REASON: String = "payment.classification.correct"
    const val REFUSAL: String = "sales.payment_correction.refused"
    // Work bounds only: an incomplete census is unavailable, never an empty history or storage eviction.
    const val MAX_SCAN_ROWS: Int = 16_384
    const val MAX_SCAN_BYTES: Long = 64L * 1_024 * 1_024
    fun record(id: DomainId): AggregateKey = LocalSalesStoreProfile.key(RECORD, id)
    fun head(paymentId: DomainId): AggregateKey = LocalSalesStoreProfile.key(HEAD, paymentId)
    fun recordClaim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("payment_correction", id)
    fun headClaim(paymentId: DomainId): ClaimKey = LocalSalesStoreProfile.unique("payment_corrections", paymentId)
    fun positionPrefix(paymentId: DomainId): String = "payment_correction_position/${paymentId.value}/"
    fun positionClaim(paymentId: DomainId, position: Revision): ClaimKey = ClaimKey(LocalSalesStoreProfile.UNIQUE_NAMESPACE,
        positionPrefix(paymentId) + position.value)
    fun membershipPrefix(paymentId: DomainId): String = "payment_correction_member/${paymentId.value}/"
    fun membershipClaim(paymentId: DomainId, id: DomainId): ClaimKey = ClaimKey(LocalSalesStoreProfile.UNIQUE_NAMESPACE,
        membershipPrefix(paymentId) + id.value)
}

/** Data access only. The history implementation uses actual core-created native BEFORE
 * sources; it is never adapted into a CommandReadView or an executable authority. */
internal sealed class LocalPaymentCorrectionSources {
    abstract val scope: BranchScope
    abstract suspend fun row(key: AggregateKey): StoredAggregate?
    abstract suspend fun claim(key: ClaimKey): DomainId?
    abstract suspend fun membership(prefix: String): List<ClaimKey>
    abstract suspend fun records(): List<StoredAggregate>
    abstract suspend fun heads(): List<StoredAggregate>
    abstract suspend fun original(operationId: OperationId): RetainedCommandEvidence?
    abstract suspend fun requireOrigin(row: StoredAggregate, operationId: OperationId)

    private var graphRecords: List<LocalPaymentCorrectionViewV1>? = null
    private var graphHeads: List<LocalPaymentCorrectionHeadV1>? = null

    /** Current correction graph only, not recovery-tail qualification. Exact graph censuses
     * reject orphan claims/rows even for a sale whose immutable payment list is empty. */
    suspend fun requireSaleCoverage(sale: PaidSale) {
        if (graphRecords == null) {
            val profile = LocalPaymentCorrectionProfileV1
            val rows = records()
            requireLocalSalesSource(rows.size <= profile.MAX_SCAN_ROWS &&
                rows.sumOf { it.payload.size.toLong() + 512L } <= profile.MAX_SCAN_BYTES)
            val values = rows.map { paymentCorrectionRecord(it, scope).correction }
            requireLocalSalesSource(values.map { it.id }.distinct().size == values.size)
            suspend fun exactClaims(prefix: String, expected: Map<ClaimKey, DomainId>) {
                val keys = membership(prefix)
                requireLocalSalesSource(expected.size == values.size && keys.size == expected.size &&
                    keys.toSet() == expected.keys && keys.zipWithNext().all { (a, b) -> a.key < b.key })
                for (key in keys) requireLocalSalesSource(claim(key) == expected.getValue(key))
            }
            exactClaims("payment_correction/", values.associate { profile.recordClaim(it.id) to it.saleId })
            exactClaims("payment_correction_member/", values.associate {
                profile.membershipClaim(it.paymentAttemptId, it.id) to it.saleId })
            exactClaims("payment_correction_position/", values.associate {
                profile.positionClaim(it.paymentAttemptId, it.position) to it.id })
            val headRows = heads()
            requireLocalSalesSource(headRows.size <= profile.MAX_SCAN_ROWS &&
                headRows.sumOf { it.payload.size.toLong() + 512L } <= profile.MAX_SCAN_BYTES)
            val heads = headRows.map { row ->
                val head = decodeLocalSalesSource(row.payload, LocalPaymentCorrectionHeadV1.serializer())
                requireLocalSalesSource(row.key == profile.head(head.paymentAttemptId) &&
                    row.schema == LocalSalesStoreProfile.schema(profile.HEAD) && sha256(row.payload.copyBytes()) == row.digest &&
                    localSalesEncode(head, LocalPaymentCorrectionHeadV1.serializer()) == row.payload &&
                    head.scope.value() == scope && head.revision == row.domainRevision() && head.revision.value < Long.MAX_VALUE)
                head
            }
            val groups = values.groupBy { it.paymentAttemptId }
            requireLocalSalesSource(heads.map { it.paymentAttemptId }.distinct().size == heads.size &&
                heads.map { it.paymentAttemptId }.toSet() == groups.keys)
            val headClaims = membership("payment_corrections/")
            val expectedHeads = heads.associate { profile.headClaim(it.paymentAttemptId) to it.saleId }
            requireLocalSalesSource(headClaims.size == expectedHeads.size && headClaims.toSet() == expectedHeads.keys &&
                headClaims.zipWithNext().all { (a, b) -> a.key < b.key })
            for (head in heads) {
                requireLocalSalesSource(claim(profile.headClaim(head.paymentAttemptId)) == head.saleId)
                val corrections = groups.getValue(head.paymentAttemptId)
                val latest = corrections.singleOrNull { it.id == head.latestCorrectionId } ?: unavailablePaymentCorrectionSource()
                requireLocalSalesSource(corrections.all { it.saleId == head.saleId && it.sessionId == head.sessionId &&
                    it.originalCloseOperationId == head.originalCloseOperationId } &&
                    head.revision == latest.position && corrections.size.toLong() == head.revision.value + 1)
            }
            graphRecords = values
            graphHeads = heads
        }
        val paymentIds = sale.payments.map { it.attemptId }.toSet()
        requireLocalSalesSource(checkNotNull(graphRecords).filter { it.saleId == sale.id }.all {
            it.paymentAttemptId in paymentIds && it.sessionId == sale.collectingSessionId } &&
            checkNotNull(graphHeads).filter { it.saleId == sale.id }.all {
                it.paymentAttemptId in paymentIds && it.sessionId == sale.collectingSessionId })
    }

    suspend fun record(id: DomainId): LocalPaymentCorrectionViewV1 {
        val row = row(LocalPaymentCorrectionProfileV1.record(id)) ?: unavailablePaymentCorrectionSource()
        val value = paymentCorrectionRecord(row, scope)
        val record = value.correction
        requireLocalSalesSource(record.id == id &&
            claim(LocalPaymentCorrectionProfileV1.recordClaim(id)) == record.saleId &&
            claim(LocalPaymentCorrectionProfileV1.positionClaim(record.paymentAttemptId, record.position)) == id &&
            claim(LocalPaymentCorrectionProfileV1.membershipClaim(record.paymentAttemptId, id)) == record.saleId)
        val producer = original(record.operationId) ?: unavailablePaymentCorrectionSource()
        requireOrigin(row, record.operationId)
        requirePaymentCorrectionRecordProducer(producer, row, value)
        return record
    }

    suspend fun head(sale: PaidSale, payment: PaymentRecord, session: CashSession): LocalPaymentCorrectionHeadCut? {
        requireSaleCoverage(sale)
        val profile = LocalPaymentCorrectionProfileV1
        val key = profile.head(payment.attemptId)
        val row = row(key)
        val owner = claim(profile.headClaim(payment.attemptId))
        val prefix = profile.membershipPrefix(payment.attemptId)
        val keys = membership(prefix)
        requireLocalSalesSource(keys.size <= profile.MAX_SCAN_ROWS && keys.zipWithNext().all { (a, b) -> a.key < b.key })
        val ids = keys.map { member ->
            requireLocalSalesSource(member.namespace == LocalSalesStoreProfile.UNIQUE_NAMESPACE &&
                member.key.startsWith(prefix) && claim(member) == sale.id)
            val id = validateLocalSalesSource { DomainId(member.key.removePrefix(prefix)) }
            requireLocalSalesSource(member == profile.membershipClaim(payment.attemptId, id))
            id
        }.toSet()
        requireLocalSalesSource(ids.size == keys.size)
        val actual = checkNotNull(graphRecords).filter { it.paymentAttemptId == payment.attemptId }
        requireLocalSalesSource(actual.map { it.id }.toSet() == ids && actual.size == ids.size)
        if (row == null) {
            requireLocalSalesSource(owner == null && ids.isEmpty())
            return null
        }
        val head = decodeLocalSalesSource(row.payload, LocalPaymentCorrectionHeadV1.serializer())
        requireLocalSalesSource(row.key == key && row.schema == LocalSalesStoreProfile.schema(profile.HEAD) &&
            sha256(row.payload.copyBytes()) == row.digest &&
            localSalesEncode(head, LocalPaymentCorrectionHeadV1.serializer()) == row.payload &&
            head.scope.value() == scope && head.saleId == sale.id && head.paymentAttemptId == payment.attemptId &&
            head.sessionId == session.id && head.originalCloseOperationId == checkNotNull(session.closeReport).operationId &&
            head.revision == row.domainRevision() && head.revision.value < Long.MAX_VALUE && owner == sale.id &&
            ids.size.toLong() == head.revision.value + 1)
        val history = ids.map { record(it) }.sortedBy { it.position.value }
        var prior: LocalPaymentCorrectionViewV1? = null
        for ((position, correction) in history.withIndex()) {
            requirePaymentCorrectionSource(correction, sale, payment, session)
            requireLocalSalesSource(correction.position.value == position.toLong() &&
                correction.previousCorrectionId == prior?.id &&
                correction.previousClassification == (prior?.correctedTender ?: tenderView(payment.tender)))
            prior = correction
        }
        val latest = prior ?: unavailablePaymentCorrectionSource()
        requireLocalSalesSource(latest.id == head.latestCorrectionId && latest.position == head.revision)
        val producer = original(latest.operationId) ?: unavailablePaymentCorrectionSource()
        requireOrigin(row, latest.operationId)
        requirePaymentCorrectionWrite(producer, row)
        return LocalPaymentCorrectionHeadCut(LocalSalesRow(row, head), history)
    }
}

internal class LocalCurrentPaymentCorrectionSources(private val view: CommandReadView) : LocalPaymentCorrectionSources() {
    // One borrowed immutable read/command cut only; never reused across authority/checkpoints.
    private var retainedRecords: List<StoredAggregate>? = null
    override val scope: BranchScope get() = view.scope
    override suspend fun row(key: AggregateKey): StoredAggregate? = view.aggregate(key)
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
    override suspend fun original(operationId: OperationId): RetainedCommandEvidence? = view.retainedCommand(operationId)
    override suspend fun requireOrigin(row: StoredAggregate, operationId: OperationId): Unit = Unit
    override suspend fun membership(prefix: String): List<ClaimKey> {
        val rows = mutableListOf<ClaimKey>()
        var after: String? = null
        do {
            val page = view.claimKeys(LocalSalesStoreProfile.UNIQUE_NAMESPACE, prefix, after, 100)
            requireLocalSalesSource(rows.size <= LocalPaymentCorrectionProfileV1.MAX_SCAN_ROWS - page.entries.size &&
                page.entries.all { claim(it.key) == it.ownerId })
            rows += page.entries.map { it.key }
            requireLocalSalesSource(page.nextAfter == null || page.nextAfter != after && page.entries.isNotEmpty())
            after = page.nextAfter
        } while (after != null)
        return rows
    }
    override suspend fun records(): List<StoredAggregate> {
        retainedRecords?.let { return it }
        return aggregates(LocalPaymentCorrectionProfileV1.RECORD).also { retainedRecords = it }
    }
    override suspend fun heads(): List<StoredAggregate> = aggregates(LocalPaymentCorrectionProfileV1.HEAD)
    private suspend fun aggregates(kind: String): List<StoredAggregate> {
        val rows = mutableListOf<StoredAggregate>()
        var after: DomainId? = null
        var bytes = 0L
        do {
            val page = view.aggregateKeys(kind, after, 100)
            requireLocalSalesSource(rows.size <= LocalPaymentCorrectionProfileV1.MAX_SCAN_ROWS - page.keys.size &&
                page.keys.size <= 100 && page.keys.all { it.kind == kind } &&
                page.keys.map { it.id.value } == page.keys.map { it.id.value }.distinct().sorted() &&
                page.keys.all { after == null || it.id.value > checkNotNull(after).value } &&
                (page.nextAfter == null || page.nextAfter == page.keys.lastOrNull()?.id))
            for (key in page.keys) {
                val row = view.aggregate(key) ?: unavailablePaymentCorrectionSource()
                requireLocalSalesSource(row.key == key)
                bytes += row.payload.size.toLong() + 512L
                requireLocalSalesSource(bytes <= LocalPaymentCorrectionProfileV1.MAX_SCAN_BYTES)
                rows += row
            }
            after = page.nextAfter
        } while (after != null)
        return rows.toList()
    }
}

internal class LocalNativePaymentCorrectionSources(private val command: RuntimeHistoricalCommand) : LocalPaymentCorrectionSources() {
    override val scope: BranchScope get() = command.scope
    override suspend fun row(key: AggregateKey): StoredAggregate? = command.aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun membership(prefix: String): List<ClaimKey> = command.claimKeys(LocalSalesStoreProfile.UNIQUE_NAMESPACE, prefix)
    override suspend fun records(): List<StoredAggregate> = command.aggregates(LocalPaymentCorrectionProfileV1.RECORD)
    override suspend fun heads(): List<StoredAggregate> = command.aggregates(LocalPaymentCorrectionProfileV1.HEAD)
    override suspend fun original(operationId: OperationId): RetainedCommandEvidence? =
        (command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy)?.evidence
    override suspend fun requireOrigin(row: StoredAggregate, operationId: OperationId) {
        val source = command.aggregateSource(row.key) ?: unavailablePaymentCorrectionSource()
        requireLocalSalesSource(source.original === command.earlierOriginal(operationId) && source.row.payload == row.payload &&
            source.row.revision == row.revision && source.row.schema == row.schema && source.row.digest == row.digest)
    }
}

private fun paymentCorrectionRecord(row: StoredAggregate, scope: BranchScope): LocalPaymentCorrectionRecordV1 {
    val value = decodeLocalSalesSource(row.payload, LocalPaymentCorrectionRecordV1.serializer(), 8_192)
    val correction = value.correction
    requireLocalSalesSource(row.key == LocalPaymentCorrectionProfileV1.record(correction.id) && row.revision == Revision(1) &&
        row.schema == LocalSalesStoreProfile.schema(LocalPaymentCorrectionProfileV1.RECORD) && value.scope.value() == scope &&
        sha256(row.payload.copyBytes()) == row.digest &&
        localSalesEncode(value, LocalPaymentCorrectionRecordV1.serializer(), 8_192) == row.payload &&
        correction.position.value < Long.MAX_VALUE &&
        !PaymentClassificationCorrections.sameClassification(paymentCorrectionTender(correction.previousClassification),
            paymentCorrectionTender(correction.correctedTender)))
    paymentCorrectionTender(correction.originalTender)
    requireLocalSalesSource(correction.correctedTender.kind != "CASH" || correction.terminal == null && correction.reference == null)
    requireLocalSalesSource(!correction.correctedTender.requiresTerminal || correction.terminal != null)
    requireLocalSalesSource(!correction.correctedTender.requiresReference || correction.reference != null)
    return value
}

internal fun paymentCorrectionTender(value: LocalTenderViewV1): Tender = validateLocalSalesSource {
    if (value.kind == "CASH") {
        requireLocalSalesSource(value == LocalTenderViewV1("CASH"))
        Tender.Cash
    } else Tender.External(checkNotNull(value.tenderId), checkNotNull(value.policyRevision),
        ExternalTenderKind.valueOf(value.kind), value.requiresTerminal, value.requiresReference)
}

internal fun requirePaymentCorrectionSource(correction: LocalPaymentCorrectionViewV1,
    sale: PaidSale, payment: PaymentRecord, session: CashSession) {
    requireLocalSalesSource(correction.saleId == sale.id && correction.paymentAttemptId == payment.attemptId &&
        correction.sessionId == session.id && correction.collectorId == sale.collectorId &&
        correction.originalIntentOperationId == payment.originalOperationId &&
        correction.originalRecordingOperationId == payment.recordingOperationId &&
        correction.originalCloseOperationId == checkNotNull(session.closeReport).operationId &&
        correction.amount == payment.amount && correction.originalTender == tenderView(payment.tender))
}

private fun requirePaymentCorrectionRecordProducer(producer: RetainedCommandEvidence, row: StoredAggregate,
    value: LocalPaymentCorrectionRecordV1) {
    val correction = value.correction
    val profile = LocalPaymentCorrectionProfileV1
    requireLocalSalesSource(producer.committed && producer.nativeOriginalProvenance == null &&
        producer.scope == value.scope.value() && producer.command == LocalPaymentCorrectionCodec.COMMAND &&
        producer.operationId == correction.operationId && producer.originalActorId == correction.evidence.actorId &&
        producer.recordedAt == correction.recordedAt && producer.aggregateWrites.size == 2 && producer.jobs.isEmpty() &&
        LocalPaymentCorrectionCodec.normalize(producer.normalized) == producer.normalized &&
        semanticDigest(producer.scope, producer.originalActorId, producer.command, producer.normalized) == producer.semanticDigest)
    val request = LocalPaymentCorrectionCodec.decode(producer.normalized)
    requireLocalSalesSource(request.saleId == correction.saleId && request.paymentAttemptId == correction.paymentAttemptId &&
        request.expectedAmount == correction.amount && request.expectedLatestCorrectionId == correction.previousCorrectionId &&
        request.evidence.evidenceId == correction.id && request.evidence.occurredAt == correction.evidence.occurredAt &&
        request.reason == correction.reason && request.terminal == correction.terminal && request.reference == correction.reference &&
        tenderSelectionMatches(request.correctedTender, paymentCorrectionTender(correction.correctedTender)) &&
        producer.result == LocalPaymentCorrectionCodec.result(LocalPaymentCorrectionResultV1.Recorded(correction,
            request.expectedSessionRevision)))
    requirePaymentCorrectionWrite(producer, row)
    val head = LocalPaymentCorrectionHeadV1(value.scope, correction.saleId, correction.paymentAttemptId, correction.sessionId,
        correction.originalCloseOperationId, correction.position, correction.id)
    val headWrite = producer.aggregateWrites.singleOrNull { it.key == profile.head(correction.paymentAttemptId) }
        ?: unavailablePaymentCorrectionSource()
    requireLocalSalesSource(headWrite.storageGeneration == correction.position.next() &&
        headWrite.schema == LocalSalesStoreProfile.schema(profile.HEAD) &&
        headWrite.payload == localSalesEncode(head, LocalPaymentCorrectionHeadV1.serializer()))
    val expectedClaims = listOf(ClaimChange.Acquire(profile.recordClaim(correction.id), correction.saleId),
        ClaimChange.Acquire(profile.positionClaim(correction.paymentAttemptId, correction.position), correction.id),
        ClaimChange.Acquire(profile.membershipClaim(correction.paymentAttemptId, correction.id), correction.saleId)) +
        if (correction.position == Revision(0)) listOf(ClaimChange.Acquire(profile.headClaim(correction.paymentAttemptId), correction.saleId)) else emptyList()
    requireLocalSalesSource(producer.claimChanges.size == expectedClaims.size)
    for ((actual, expected) in producer.claimChanges.zip(expectedClaims)) {
        requireLocalSalesSource(actual.action == RetainedClaimAction.ACQUIRE && actual.key == expected.key &&
            actual.ownerId == (expected as ClaimChange.Acquire).ownerId)
    }
}

internal fun tenderSelectionMatches(selection: LocalTenderSelectionV1, tender: Tender): Boolean = when (selection) {
    LocalTenderSelectionV1.Cash -> tender == Tender.Cash
    is LocalTenderSelectionV1.External -> tender is Tender.External && selection.tenderId == tender.tenderId &&
        selection.expectedPolicyRevision == tender.policyRevision
}

private fun requirePaymentCorrectionWrite(producer: RetainedCommandEvidence, row: StoredAggregate) {
    val write = producer.aggregateWrites.singleOrNull { it.key == row.key } ?: unavailablePaymentCorrectionSource()
    requireLocalSalesSource(write.storageGeneration == row.revision && write.schema == row.schema &&
        write.payload == row.payload && write.payloadDigest == row.digest)
}

internal fun unavailablePaymentCorrectionSource(): Nothing = throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
