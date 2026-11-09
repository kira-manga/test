package com.ukkera.pos.sales.store.independent.historicaltenderreportv2

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.sales.store.independent.paymentcorrectionreport.PaymentCorrectionReportSupportingInputs
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*

/** Inert canonical domain DATA and read-only source ranges. No native seed, principal, grant,
 * writer, retained producer or admitted RPC is provided; a nonempty head cannot qualify here. */
internal object HistoricalTenderSourceData {
    private val f = HistoricalTenderReportSupportingInputs
    val scope: BranchScope = BranchScope(f.id("79000000", 1), f.id("79000000", 2), f.id("79000000", 3))
    private val storedScope = LocalSalesScope(scope.organizationId, scope.companyId, scope.branchId)

    fun sale(fixture: HistoricalTenderDocument): PaidSale {
        val portion = f.id("79000000", 11)
        val amountBefore = if (fixture.paidMinor == 0L) 1000L else fixture.paidMinor
        val cap = DiscountLimits(f.money(if (fixture.paidMinor == 0L) 1000 else 0), PercentageRate.parse("100"))
        val line = ResolvedPriceLine(f.id("79000000", 12), portion, Revision(1),
            Quantity(f.id("79000000", 13), 1, 0), UnitPrice.parse(Currency.EGP,
                "${amountBefore / 100}.${(amountBefore % 100).toString().padStart(2, '0')}"), TaxInclusion.EXCLUSIVE,
            TaxSnapshot(f.id("79000000", 14), Revision(1), "SYNTHETIC-NONFISCAL", PercentageRate.parse("0")), cap)
        val adjustments = if (fixture.paidMinor == 0L) listOf(PriceAdjustment(f.id("79000000", 15), Revision(1),
            AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT, DiscountValue.FixedPayable(f.money(1000)),
            listOf(EligibleDiscountPortion(portion)), cap)) else emptyList()
        val quote = OperationalPricingEngine.calculate(PricingRequest(fixture.orderId, Revision(0), Currency.EGP,
            listOf(line), adjustments = adjustments, discountLimits = cap))
        val state = buildJsonObject {
            put("orderId", fixture.orderId.value); put("originalCreatorId", f.collector.value)
            put("collectorId", f.collector.value); put("collectingSessionId", fixture.close.sessionId.value)
            put("quote", StoredPricingSnapshotCodec.encode(quote)); put("stockIssueId", f.id("79000000", 16).value)
            put("payments", JsonArray(fixture.payments.mapIndexed { index, part -> paymentData(fixture, part, index) }))
            put("originalTenderAllocations", JsonArray(quote.components.map { component -> buildJsonObject {
                put("componentKey", component.key)
                put("shares", JsonArray(fixture.payments.map { payment -> buildJsonObject {
                    put("paymentId", payment.paymentAttemptId.value)
                    put("amount", money(if (component.posted.minor == fixture.paidMinor) payment.amount.minor else 0))
                } }))
            } }))
        }
        return SalesSnapshotCodec.decodePaidSale(envelope(SalesSnapshotKind.PAID_SALE, fixture.saleId, Revision(0), state),
            SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, scope, fixture.saleId, Revision(0)))
    }

    fun session(fixture: HistoricalTenderDocument): CashSession {
        val close = fixture.close
        val inherited = when (close.expectedCash.minor) {
            235000L -> PaymentCorrectionReportSupportingInputs.cash10.originalCloseMetrics.toMap()
            62550L -> PaymentCorrectionReportSupportingInputs.ext2735.originalCloseMetrics.toMap()
            else -> emptyMap()
        }
        fun minor(metric: LocalBranchReportMetricV1): Long = inherited[metric] ?: 0L
        val evidence = evidence(f.id("79000000", 17), close.countEvidence.occurredAt)
        val cut = buildJsonObject {
            put("cashLedgerRevision", revision(close.ledgerRevision)); put("expected", money(close.expectedCash.minor))
            put("externalCollections", money(close.externalCollections.minor)); put("externalRefunds", money(close.externalRefunds.minor))
        }
        val counted = buildJsonObject { put("amount", money(close.counted.minor)); put("evidence", evidence) }
        val state = buildJsonObject {
            put("drawerId", f.id("79000000", 18).value); put("custodianId", f.collector.value)
            put("businessDate", "2026-11-02")
            put("opening", buildJsonObject {
                put("amount", money(if (inherited.isEmpty()) 10000 else minor(LocalBranchReportMetricV1.OPENING_FLOAT)))
                put("evidence", evidence)
            })
            put("openingSourceId", f.id("79000000", 19).value)
            put("totals", buildJsonObject {
                put("received", money(minor(LocalBranchReportMetricV1.CASH_RECEIVED)))
                put("change", money(minor(LocalBranchReportMetricV1.CHANGE_GIVEN)))
                put("refunds", money(minor(LocalBranchReportMetricV1.CASH_REFUNDS)))
                put("payIns", money(minor(LocalBranchReportMetricV1.PAY_INS)))
                put("payOuts", money(minor(LocalBranchReportMetricV1.PAY_OUTS)))
                put("safeDrops", money(minor(LocalBranchReportMetricV1.SAFE_DROPS)))
                put("externalCollections", money(close.externalCollections.minor)); put("externalRefunds", money(close.externalRefunds.minor))
            })
            put("expectedCash", money(close.expectedCash.minor)); put("reservedCashOut", money(0))
            put("cashLedgerRevision", revision(close.ledgerRevision)); put("status", "SUBMITTED_CLOSED")
            put("pendingAttemptIds", JsonArray(emptyList())); put("pendingTransferIds", JsonArray(emptyList()))
            put("closingCut", cut)
            put("closeReport", buildJsonObject {
                put("sessionId", close.sessionId.value); put("cut", cut); put("count", counted)
                put("variance", money(close.variance.minor)); put("retainedFloat", money(close.retainedFloat.minor))
                put("actualSafeDelivery", money(close.actualSafeDelivery.minor))
                put("safeTransferId", close.safeTransferId?.let { JsonPrimitive(it.value) } ?: JsonNull)
                put("varianceReason", close.varianceReason?.let(::JsonPrimitive) ?: JsonNull)
                put("submittedBy", f.collector.value); put("operationId", close.originalCloseOperationId.value)
            })
            put("review", JsonNull)
        }
        val domainRevision = close.ledgerRevision.next()
        return SalesSnapshotCodec.decodeCashSession(envelope(SalesSnapshotKind.CASH_SESSION, close.sessionId, domainRevision, state),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, scope, close.sessionId, domainRevision))
    }

    fun graph(corrections: List<LocalPaymentCorrectionViewV1>,
        heads: List<LocalPaymentCorrectionHeadV1> = corrections.groupBy { it.paymentAttemptId }.values.map { records ->
            val last = records.maxBy { it.position.value }
            LocalPaymentCorrectionHeadV1(storedScope, last.saleId, last.paymentAttemptId, last.sessionId,
                last.originalCloseOperationId, last.position, last.id)
        }): HistoricalTenderReadData {
        val profile = LocalPaymentCorrectionProfileV1
        val rows = corrections.map(::recordRow) + heads.map(::headRow)
        val claims = buildMap {
            for (record in corrections) {
                put(profile.recordClaim(record.id), record.saleId)
                put(profile.membershipClaim(record.paymentAttemptId, record.id), record.saleId)
                put(profile.positionClaim(record.paymentAttemptId, record.position), record.id)
            }
            for (head in heads) put(profile.headClaim(head.paymentAttemptId), head.saleId)
        }
        return HistoricalTenderReadData(scope, rows, claims)
    }

    fun recordRow(correction: LocalPaymentCorrectionViewV1): StoredAggregate = stored(
        LocalPaymentCorrectionProfileV1.record(correction.id), Revision(1), LocalPaymentCorrectionProfileV1.RECORD,
        localSalesEncode(LocalPaymentCorrectionRecordV1(storedScope, correction), LocalPaymentCorrectionRecordV1.serializer()))
    fun headRow(head: LocalPaymentCorrectionHeadV1): StoredAggregate = stored(
        LocalPaymentCorrectionProfileV1.head(head.paymentAttemptId), head.revision.next(), LocalPaymentCorrectionProfileV1.HEAD,
        localSalesEncode(head, LocalPaymentCorrectionHeadV1.serializer()))
    fun head(correction: LocalPaymentCorrectionViewV1): LocalPaymentCorrectionHeadV1 = LocalPaymentCorrectionHeadV1(
        storedScope, correction.saleId, correction.paymentAttemptId, correction.sessionId, correction.originalCloseOperationId,
        correction.position, correction.id)

    fun chainRecords(): List<LocalPaymentCorrectionViewV1> = (0..3).map { index ->
        val before = f.chain(index).payments.last()
        val after = f.chain(index + 1).payments.last()
        val recordedAt = checkNotNull(after.latestCorrectionRecordedAt)
        PaymentCorrectionReportSupportingInputs.ext2735.correction.copy(saleId = f.chainSaleId,
            paymentAttemptId = f.chainPaymentId, sessionId = f.chain(4).close.sessionId,
            originalCloseOperationId = f.chain(4).close.originalCloseOperationId,
            previousClassification = before.reconciledClassification, correctedTender = after.reconciledClassification,
            position = Revision(index.toLong()), previousCorrectionId = if (index == 0) null else f.chainIds[index - 1],
            evidence = LocalCashActorEvidenceV1(f.chainIds[index], f.id("73000000", 40 + index), recordedAt),
            reason = "synthetic linked classification finding", terminal = null, reference = null,
            operationId = f.operation("77000000", 100 + index), recordedAt = recordedAt)
    }

    private fun paymentData(fixture: HistoricalTenderDocument, part: LocalBranchReportRetailTenderPaymentV3, index: Int): JsonObject {
        val original = part.originalClassification
        val inherited = PaymentCorrectionReportSupportingInputs.fixtures.map { it.correction }
            .singleOrNull { it.paymentAttemptId == part.paymentAttemptId }
        val attestation = evidence(f.id("79000000", 30 + index), fixture.close.countEvidence.occurredAt)
        return buildJsonObject {
            put("attemptId", part.paymentAttemptId.value)
            put("originalOperationId", (inherited?.originalIntentOperationId ?: f.operation("79000000", 40 + index)).value)
            put("recordingOperationId", (inherited?.originalRecordingOperationId ?: f.operation("79000000", 50 + index)).value)
            put("collectionId", f.id("79000000", 60).value); put("collectorId", f.collector.value)
            put("cashSessionId", fixture.close.sessionId.value); put("payerReference", JsonNull)
            put("tender", if (original.kind == "CASH") buildJsonObject { put("type", "CASH") } else buildJsonObject {
                put("type", "EXTERNAL"); put("tenderId", original.tenderId!!.value)
                put("policyRevision", revision(original.policyRevision!!)); put("kind", original.kind)
                put("requiresTerminal", original.requiresTerminal); put("requiresReference", original.requiresReference)
            })
            put("amount", money(part.amount.minor))
            put("cashReceived", if (original.kind == "CASH") money(500000) else JsonNull)
            put("changeGiven", if (original.kind == "CASH") money(40000) else JsonNull)
            put("attestation", attestation)
            put("externalEvidence", if (original.kind == "CASH") JsonNull else buildJsonObject {
                put("attestation", attestation); put("terminal", JsonNull); put("reference", JsonNull)
            })
        }
    }

    private fun evidence(id: DomainId, at: UtcInstant): JsonObject = buildJsonObject {
        put("evidenceId", id.value); put("actorId", f.collector.value); put("occurredAt", at.canonicalString())
    }
    private fun money(minor: Long): JsonElement = Json.encodeToJsonElement(Money.serializer(), f.money(minor))
    private fun revision(value: Revision): JsonElement = Json.encodeToJsonElement(Revision.serializer(), value)
    private fun envelope(kind: SalesSnapshotKind, id: DomainId, domainRevision: Revision, state: JsonObject): String = buildJsonObject {
        put("schemaVersion", 1); put("kind", kind.name)
        put("scope", buildJsonObject {
            put("organizationId", scope.organizationId.value); put("companyId", scope.companyId.value); put("branchId", scope.branchId.value)
        })
        put("id", id.value); put("revision", revision(domainRevision)); put("state", state)
    }.toString()
    private fun stored(key: AggregateKey, revision: Revision, kind: String, bytes: StoredBytes): StoredAggregate =
        StoredAggregate(key, revision, LocalSalesStoreProfile.schema(kind), bytes, sha256(bytes.copyBytes()))
}

/** Constructors below create DATA only; the public source implementation still validates every census. */
internal class HistoricalTenderReadData(override val scope: BranchScope,
    val rows: List<StoredAggregate> = emptyList(), val claims: Map<ClaimKey, DomainId> = emptyMap()) : CommandReadView {
    val rangePrefixes = mutableListOf<String>()
    val originalRequests = mutableListOf<OperationId>()
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = rows.singleOrNull { it.key == key }
    override suspend fun claim(key: ClaimKey): DomainId? = claims[key]
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        val selected = rows.filter { it.key.kind == kind && (after == null || it.key.id.value > after.value) }
            .map { it.key }.sortedBy { it.id.value }.take(limit + 1)
        return AggregateKeyPage(selected.take(limit), if (selected.size > limit) selected[limit - 1].id else null)
    }
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage {
        rangePrefixes += prefix
        val selected = claims.filter { it.key.namespace == namespace && it.key.key.startsWith(prefix) &&
            (after == null || it.key.key > after) }.entries.sortedBy { it.key.key }.take(limit + 1)
        return ClaimKeyPage(selected.take(limit).map { ClaimKeyEntry(it.key, it.value) },
            if (selected.size > limit) selected[limit - 1].key.key else null)
    }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        originalRequests += operationId
        return null
    }
}
