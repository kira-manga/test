@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnsourceomittedexpectation

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Separate initial-revision-zero DATA scaffold for the bounded shared producer and
 * history selector. It is NOT F-SOURCE's current-revision-seven native fixture.
 * Visibility suppressions construct inert receipt/row/retained DATA only, never
 * an authorized SalesCommand, native original, historical view or runtime command.
 * Record/append proposals reach CASE 11 in memory; no store is opened or written.
 * Quantity 2 and payable 10000 project the frozen scalar inputs. Other source,
 * quote, publication, proof and bookkeeping slots are opaque synthetic fillers.
 */
internal class ReturnSourceOmittedExpectationInitialData {
    private val frozen = ReturnSourceOmittedExpectationFrozenInputs
    private val rows = linkedMapOf<AggregateKey, StoredAggregate>()
    private val commands = mutableListOf<StoredBytes>()
    val scope: BranchScope = frozen.scope
    val ownerId = frozen.ownerId
    val caseId = frozen.caseId
    val now = UtcInstant.parseCanonical("2026-11-03T10:00:00Z")
    val request = frozen.request
    val identity = identity(frozen.operationId, request)
    private val saleId = id(20)
    private val portionId = id(21)
    private val lineId = id(22)
    private val quantity = Quantity.parse(id(23), "2", 0)
    private val evidence = LocalCashEvidenceV1(id(24), now)
    private val binding = LocalReturnCaseReceiptBindingV1(saleId, portionId, quantity, evidence)
    private val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(saleId, portionId, quantity, null,
        now, "CLAIM_UNBOUND", evidence), "ASSERTED_DEFECT", LocalReturnRequestedRemedyV1.REFUND, "Africa/Cairo")
    val dependency = RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT, id(70))
    val initialEntitlementRevision = Revision(0)

    init {
        val record = LocalReturnCaseRequestV1.Record(caseId, facts)
        materialize(assertIs<ProposedOutcome.Apply>(returnCaseRecordProduction(identity(op(100), record), record,
            work = null, dependencies = emptySet(), duplicate = false).propose(now)))
        commands += LocalReturnCaseCommandCodecV1.encode(record)
        repeat(11) { index -> append(op(101 + index)) }
        assertEquals(frozen.caseRevision, before.row.value.revision)
    }

    val before: LocalReturnCaseCut get() = caseCut()
    private val quoteId = id(30)
    private val quoteOperation = OperationId(quoteId.value)
    private val publicationId = id(31)
    private val skuId = id(32)
    private val signingKeyId = id(33)
    private val publicationOperation = op(34)
    private val publicationActor = id(35)
    private val policyId = id(36)
    private val finalizeOperation = op(40)
    private val finalizeActor = id(41)
    private val sourceProofResource = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id(42))
    private val unitPrice = UnitPrice.parse(Currency.EGP, "50")
    private val quote = OperationalPricingEngine.calculate(PricingRequest(quoteId, Revision(0), Currency.EGP,
        listOf(ResolvedPriceLine(lineId, portionId, Revision(0), quantity, unitPrice, TaxInclusion.INCLUSIVE,
            TaxSnapshot(id(43), Revision(0), "DATA", PercentageRate.parse("0"))))))
    private val quoteDigest = sha256(StoredPricingSnapshotCodec.encode(quote).encodeToByteArray())
    private val price = LocalSalesPriceSourceV2(scope, lineId, portionId, skuId, publicationId, id(44), Revision(0),
        digest("inert original price"), unitPrice, unitPrice, null, null, null, null, null, null)
    private val pricesValue = LocalSalesPriceSourcesV2(LocalSalesScope.from(scope), quoteId, quoteId, quote.revision,
        quoteDigest, quoteOperation, LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2, listOf(price), normalizedStock = true)
    private val prices = row(LocalSalesStoreProfile.PRICE_SOURCES_KIND, quoteId, Revision(1),
        LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA, localSalesEncode(pricesValue, LocalSalesPriceSourcesV2.serializer()), pricesValue)
    private val publication = LocalSalesReturnPublicationV2(skuId, Revision(0), signingKeyId,
        LocalReturnCaseSourceReferenceV1("local.catalog.publication", publicationId, "local.catalog.publication/v1",
            Revision(1), digest("inert publication"), publicationOperation, publicationActor, "local.configuration.write", 1),
        LocalSalesReturnPromiseStateV2.SIGNED_PRESENT,
        LocalSalesReturnPromiseDescriptorV2(policyId, Revision(0), "DATA", digest("inert promise")))
    private val captureValue = LocalSalesReturnPolicyCaptureV2(scope, quoteId, quoteId, quote.revision, quoteDigest,
        quoteOperation, LocalSalesReturnPolicyCaptureInputV2(listOf(LocalSalesReturnPortionCaptureV2(lineId, portionId,
            publicationId)), listOf(publication)))
    private val capture = row(LocalSalesReturnPolicyCaptureCodecV2.KIND, quoteId, Revision(1),
        LocalSalesReturnPolicyCaptureCodecV2.SCHEMA, LocalSalesReturnPolicyCaptureCodecV2.encode(captureValue), captureValue)
    private val payment = PaymentRecord(id(45), op(46), op(46), id(47), ownerId, id(48), null,
        Tender.Cash, quote.payable, quote.payable, Money(Currency.EGP, 0), CashierEvidence(id(49), ownerId, now.value), null)
    private val allocations = quote.components.map {
        OriginalComponentTenderAllocation(it.key, listOf(OriginalTenderShare(payment.attemptId, it.posted)))
    }
    private val saleValue = PaidSale(saleId, scope, quoteId, ownerId, ownerId, payment.cashSessionId, quote, id(50),
        listOf(payment), allocations, SalesPriceSourceReference(prices.stored.key.id, prices.stored.digest),
        SalesReturnPolicySourceReference(capture.stored.key.id, capture.stored.digest))
    private val sale = row(LocalSalesStoreProfile.SALE_KIND, saleId, Revision(1),
        LocalSalesStoreProfile.schema(LocalSalesStoreProfile.SALE_KIND),
        StoredBytes(SalesSnapshotCodec.encodePaidSale(saleValue).encodeToByteArray()), saleValue)
    private val entitlementValue = ReceiptEntitlement(saleValue, initialEntitlementRevision,
        listOf(quote.openReturnCursor(portionId)), allocations)
    private val entitlement = row(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId, Revision(1),
        LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ENTITLEMENT_KIND),
        StoredBytes(SalesSnapshotCodec.encodeReceiptEntitlement(entitlementValue).encodeToByteArray()), entitlementValue)
    private val proofResources = setOf(entitlementResource(DomainId(finalizeOperation.value)),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, finalizeActor), sourceProofResource)
    private val proof = LocalReturnEntitlementProofBudget().also { it.resource(proofResources) }
    private val producer = LocalReturnEntitlementProducer(finalizeOperation, finalizeActor,
        StockStreamingCommandsV2.COLLECTION.name, StockStreamingCommandsV2.COLLECTION.version, digest("inert finalize coordinates"))
    val original = LocalEntitlementCut(sale, entitlement, capture, prices,
        LocalReturnEntitlementSourceCut(producer, producer, null, null, 0, proof))

    /** Expected set derives from named DATA coordinates, not the production resource helper. */
    fun expectedResources(cut: LocalReturnCaseCut = before): Set<RecoveryResourceKey> = buildSet {
        add(resource(RecoveryResourceKind.REFUND_ENTITLEMENT, caseId))
        add(resource(RecoveryResourceKind.ORDER_CUSTODY, ownerId))
        add(resource(RecoveryResourceKind.REFUND_ENTITLEMENT, ownerId))
        add(resource(RecoveryResourceKind.AUTHORIZATION, ownerId))
        cut.row.value.events.forEach { add(resource(RecoveryResourceKind.REFUND_ENTITLEMENT, DomainId(it.value))) }
        add(dependency)
        add(resource(RecoveryResourceKind.REFUND_ENTITLEMENT, saleId))
        add(resource(RecoveryResourceKind.ORDER_CUSTODY, quoteId))
        listOf(publicationId, skuId, signingKeyId, DomainId(publicationOperation.value), publicationActor, policyId)
            .forEach { add(resource(RecoveryResourceKind.AUTHORIZATION, it)) }
        addAll(proofResources)
        add(resource(RecoveryResourceKind.REFUND_ENTITLEMENT, DomainId(identity.operationId.value)))
    }

    fun matches(retained: RetainedCommandEvidence, request: LocalReturnCaseRequestV1.Qualify = this.request,
        state: LocalReturnCaseStateV1 = before.row.value, entitlementRevision: Revision = initialEntitlementRevision): Boolean =
        returnCaseHistorySourceOmittedEntitlementQualification(retained, request, state, entitlementRevision)

    fun retained(normalized: StoredBytes = frozen.bytes, command: CommandKey = LocalReturnCaseProfileV1.COMMAND,
        actorId: DomainId = ownerId, scope: BranchScope = this.scope, code: String = "sales.return_case.refused",
        result: StoredBytes = conflictBytes(), writes: List<RetainedAggregateWrite> = emptyList(),
        claims: List<RetainedClaimChange> = emptyList(), jobs: List<RetainedJobIntent> = emptyList()): RetainedCommandEvidence =
        RetainedCommandEvidence(scope, identity.operationId, actorId, command, id(60), Revision(0), JournalSequence(0),
            now, id(61), Revision(0), semanticDigest(scope, actorId, command, normalized), code, normalized, result,
            sha256(result.copyBytes()), digest("inert previous journal bookkeeping"), digest("inert journal bookkeeping"),
            writes, claims, jobs)

    fun retainedWrite(stored: StoredAggregate): RetainedAggregateWrite =
        RetainedAggregateWrite(stored.key, stored.revision, stored.schema, stored.payload, stored.digest)

    fun append(operation: OperationId = op(112)) {
        val request = LocalReturnCaseRequestV1.AppendReceiptEvidence(caseId, before.row.value.revision, binding)
        materialize(assertIs<ProposedOutcome.Apply>(returnCaseAppendProduction(identity(operation, request), request, before).propose(now)))
        commands += LocalReturnCaseCommandCodecV1.encode(request)
    }

    fun snapshot(): OmittedExpectationDataSnapshot = OmittedExpectationDataSnapshot(
        rows.values.sortedWith(compareBy({ it.key.kind }, { it.key.id.value })).map(::image),
        localSalesEncode(before.row.value, LocalReturnCaseStateV1.serializer()),
        localSalesEncode(before.event.value, LocalReturnCaseEventV1.serializer()),
        localSalesEncode(before.work.value, LocalReturnCaseWorkV1.serializer()),
        before.binding?.let { localSalesEncode(it.value, LocalReturnCaseEventV1.serializer()) },
        commands.toList(), LocalReturnCaseCommandCodecV1.encode(request), identity,
        listOf(sale.stored, entitlement.stored, prices.stored, capture.stored).map(::image),
        StoredBytes(SalesSnapshotCodec.encodePaidSale(original.sale.value).encodeToByteArray()),
        StoredBytes(SalesSnapshotCodec.encodeReceiptEntitlement(original.entitlement.value).encodeToByteArray()),
        LocalSalesReturnPolicyCaptureCodecV2.encode(checkNotNull(original.returnPolicyCapture).value),
        checkNotNull(original.normalizedSource).recoveryResources)

    private fun identity(operation: OperationId, request: LocalReturnCaseRequestV1): LocalReturnCaseProductionIdentity =
        LocalReturnCaseProductionIdentity(scope, ownerId, operation,
            semanticDigest(scope, ownerId, LocalReturnCaseProfileV1.COMMAND, LocalReturnCaseCommandCodecV1.encode(request)))

    private fun materialize(proposal: ProposedOutcome.Apply) {
        for (change in proposal.changes) {
            assertEquals(rows[change.key]?.revision, change.expectedRevision)
            rows[change.key] = StoredAggregate(change.key, (change.expectedRevision ?: Revision(0)).next(),
                change.schema, change.payload, sha256(change.payload.copyBytes()))
        }
    }

    private fun caseCut(): LocalReturnCaseCut {
        val row = caseRow(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, caseId), LocalReturnCaseStateV1.serializer())
        val currentEvent = event(row.value.lastOperationId)
        val work = caseRow(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.WORK, ownerId), LocalReturnCaseWorkV1.serializer())
        return LocalReturnCaseCut(row, currentEvent, work, row.value.bindingOperationId?.let(::event),
            row.value.decisionOperationId?.let(::event), setOf(dependency))
    }

    private fun event(operation: OperationId): LocalSalesRow<LocalReturnCaseEventV1> =
        caseRow(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(operation.value)), LocalReturnCaseEventV1.serializer())

    private fun <T> caseRow(key: AggregateKey, serializer: KSerializer<T>): LocalSalesRow<T> =
        rows.getValue(key).let { LocalSalesRow(it, localSalesDecode(it.payload, serializer)) }

    private fun <T> row(kind: String, id: DomainId, generation: Revision, schema: String, payload: StoredBytes, value: T): LocalSalesRow<T> =
        LocalSalesRow(StoredAggregate(AggregateKey(kind, id), generation, schema, payload, sha256(payload.copyBytes())), value)

    private fun image(row: StoredAggregate): OmittedExpectationRowImage =
        OmittedExpectationRowImage(row.key, row.revision, row.schema, StoredBytes(row.payload.copyBytes()), row.digest)

    private fun id(serial: Int): DomainId = frozen.dataId(serial)
    private fun op(serial: Int): OperationId = OperationId(id(serial).value)
    private fun digest(value: String): PayloadDigest = sha256(value.encodeToByteArray())
    private fun resource(kind: RecoveryResourceKind, id: DomainId): RecoveryResourceKey = RecoveryResourceKey(kind, id)
}

internal fun conflictBytes(): StoredBytes = StoredBytes("{\"type\":\"REFUSED\",\"reason\":\"REVISION_CONFLICT\"}".encodeToByteArray())

internal data class OmittedExpectationRowImage(val key: AggregateKey, val generation: Revision, val schema: String,
    val payload: StoredBytes, val digest: PayloadDigest)

internal data class OmittedExpectationDataSnapshot(val caseRows: List<OmittedExpectationRowImage>, val case: StoredBytes,
    val event: StoredBytes, val work: StoredBytes, val binding: StoredBytes?, val setupCommands: List<StoredBytes>,
    val qualify: StoredBytes, val identity: LocalReturnCaseProductionIdentity, val sourceRows: List<OmittedExpectationRowImage>,
    val sale: StoredBytes, val entitlement: StoredBytes, val capture: StoredBytes, val sourceResources: Set<RecoveryResourceKey>)
