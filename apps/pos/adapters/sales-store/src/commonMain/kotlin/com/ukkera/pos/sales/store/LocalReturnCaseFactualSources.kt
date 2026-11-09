package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandIdentityV2
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.requireStockRelational
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Literal paid Finalize coordinates from its validated original, never delivery or a grant. */
public class LocalReturnCasePaidFinalizeFactsV1 internal constructor(
    public val saleSource: LocalReturnCaseSourceReferenceV1,
    public val semanticDigest: PayloadDigest,
    public val assignmentId: DomainId,
    public val authorityEpoch: Revision,
    public val sequence: JournalSequence,
    public val recordedAt: UtcInstant,
)

/** Exact immutable quote companion and its actual authenticated quote producer. */
public class LocalReturnCaseFactualPolicyCaptureV1 internal constructor(
    value: LocalSalesReturnPolicyCaptureV2,
    public val source: LocalReturnCaseSourceReferenceV1,
    public val payload: StoredBytes,
    public val producer: RetainedCommandIdentityV2,
) {
    public val value: LocalSalesReturnPolicyCaptureV2 = value.copy(input = value.input.copy(
        portions = value.input.portions.toList(), publications = value.input.publications.toList()))
}

/** Every immutable event keeps its exact bytes, source and complete dependency set. */
public class LocalReturnCaseFactualReceiptEventV1 internal constructor(
    value: LocalRetailCustomerReceiptEventViewV1,
    public val source: LocalReturnCaseSourceReferenceV1,
    public val payload: StoredBytes,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    public val value: LocalRetailCustomerReceiptEventViewV1 = value.copy(portions = value.portions.toList())
    private val resources: Set<RecoveryResourceKey> = recoveryResources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()
}

/**
 * Detached factual data for transaction preparation and native-history comparison. No loan,
 * original reader or current authority escapes. The event list is the COMPLETE ordered
 * receipt census (up to 64), not the assessment's at-most-eight receipt references.
 * A missing head describes absence of this observation producer, not physical non-delivery.
 * These facts select no delivery allocation, starting instant, applicable rights or remedy.
 */
public class LocalReturnCaseFactualCutV1 internal constructor(
    public val originalSale: PaidSale,
    public val originalSalePayload: StoredBytes,
    public val paidFinalize: LocalReturnCasePaidFinalizeFactsV1,
    public val returnPolicyCapture: LocalReturnCaseFactualPolicyCaptureV1?,
    originalCustomerReceipt: LocalRetailCustomerReceiptOriginalV1,
    customerReceiptHead: LocalRetailCustomerReceiptHeadViewV1?,
    customerReceiptEvents: List<LocalReturnCaseFactualReceiptEventV1>,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    public val scope: BranchScope get() = originalSale.scope
    public val originalCustomerReceipt: LocalRetailCustomerReceiptOriginalV1 =
        originalCustomerReceipt.copy(portions = originalCustomerReceipt.portions.toList())
    public val customerReceiptHead: LocalRetailCustomerReceiptHeadViewV1? = customerReceiptHead?.copy(
        original = this.originalCustomerReceipt, recordedPortions = customerReceiptHead.recordedPortions.toList())
    private val events: List<LocalReturnCaseFactualReceiptEventV1> = customerReceiptEvents.toList()
    public val customerReceiptEvents: List<LocalReturnCaseFactualReceiptEventV1> get() = events.toList()
    private val resources: Set<RecoveryResourceKey> = recoveryResources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()
}

/**
 * Reuses the actual immutable-sale and COMPLETE receipt validators. Neither route evaluates
 * return eligibility, substitutes today's remaining entitlement or accepts customer dates
 * as submission/delivery authority. Callers must retain current Master authorization and
 * independently prepare applicable rights, remaining entitlement and any legal assessment.
 */
public object LocalReturnCaseFactualSourcesV1 {
    /** Borrowed genuine writer view only. Missing, damaged or unsupported sources throw. */
    public suspend fun read(view: CommandReadView, saleId: DomainId): LocalReturnCaseFactualCutV1 {
        val sale = readReturnSaleSource(view, saleId)
        val original = readRetailCustomerReceiptOriginalSource(view, saleId)
        val head = findRetailCustomerReceiptHead(view, original)
        val capture = sale.returnPolicyCapture?.let { row ->
            val quoted = (view.requireStockRelational().originals.read(row.value.originalQuoteOperationId)
                as? RetainedOriginalV2.Streamed)?.evidence
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireLocalSalesSource(quoted.identity.scope == view.scope &&
                quoted.identity.producer.operationId == row.value.originalQuoteOperationId)
            requireNormalizedPaymentQuoteRequest(quoted, row.value.orderId, row.value.quoteRevision)
            commerceRepository(view).requireRelationalProducer(view, quoted)
            requireCommerceWrite(quoted, row.stored)
            returnCaseFactualCapture(row, quoted.identity)
        }
        return returnCaseFactualCut(sale, original, capture, head?.head?.value,
            head?.events.orEmpty(), head?.recoveryResources.orEmpty())
    }

    /**
     * Core-created active native BEFORE only, with actual earlier fully joined originals.
     * Null means the original profile cannot be joined; it proves neither source absence
     * nor non-delivery. Incomplete event membership/chronology still throws and remains held.
     */
    public suspend fun readHistory(command: RuntimeHistoricalCommand,
        saleId: DomainId): LocalReturnCaseFactualCutV1? {
        val original = readRetailCustomerReceiptHistoryOriginal(command, saleId) ?: return null
        val sale = readReturnCaseHistorySaleSource(command, saleId)
        val head = LocalRetailCustomerReceiptHistorySources(command).findHead(original)
        val capture = sale.returnPolicyCapture?.let { row ->
            val source = command.aggregateSource(row.stored.key)
                ?: error("sales.return.factual_history.capture_missing")
            val quoted = source.original as? RetainedOriginalV2.Streamed
                ?: error("sales.return.factual_history.capture_profile_unsupported")
            check(command.earlierOriginal(row.value.originalQuoteOperationId) === quoted &&
                quoted.evidence.identity.scope == command.scope && source.row.key == row.stored.key &&
                source.row.revision == row.stored.revision && source.row.schema == row.stored.schema &&
                source.row.payload == row.stored.payload && source.row.digest == row.stored.digest)
            requireNormalizedPaymentQuoteRequest(quoted.evidence, row.value.orderId, row.value.quoteRevision)
            requireCommerceWrite(quoted.evidence, row.stored)
            returnCaseFactualCapture(row, quoted.evidence.identity)
        }
        return returnCaseFactualCut(sale, original, capture, head?.head?.value,
            head?.events.orEmpty(), head?.recoveryResources.orEmpty())
    }
}

private fun returnCaseFactualCapture(row: LocalSalesRow<LocalSalesReturnPolicyCaptureV2>,
    original: RetainedCommandIdentityV2): LocalReturnCaseFactualPolicyCaptureV1 {
    val producer = original.producer
    return LocalReturnCaseFactualPolicyCaptureV1(row.value,
        returnCaseFactualReference(row.stored, producer.operationId, producer.originalActorId, producer.command),
        row.stored.payload, original)
}

/** Projection only: each source/producer coordinate was compared by the existing validators. */
private fun returnCaseFactualCut(sale: LocalReturnSaleCut,
    original: LocalRetailCustomerReceiptOriginalSource, capture: LocalReturnCaseFactualPolicyCaptureV1?,
    head: LocalRetailCustomerReceiptHeadV1?, receiptEvents: List<LocalSalesRow<LocalRetailCustomerReceiptEventV1>>,
    receiptResources: Set<RecoveryResourceKey>): LocalReturnCaseFactualCutV1 {
    val coordinates = original.coordinates
    requireLocalSalesSource(sale.sale.value.id == coordinates.saleId && sale.sale.value.scope == coordinates.scope.value() &&
        sale.sale.stored.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, coordinates.saleId) &&
        sale.sale.stored.revision == coordinates.saleGeneration && sale.sale.stored.schema == coordinates.saleSchema &&
        sale.sale.stored.digest == coordinates.saleDigest &&
        (capture == null) == (sale.returnPolicyCapture == null))
    val saleSource = returnCaseFactualReference(sale.sale.stored, coordinates.finalizeOperationId,
        coordinates.finalizeActorId, CommandKey(coordinates.finalizeCommand, coordinates.finalizeVersion))
    val finalize = LocalReturnCasePaidFinalizeFactsV1(saleSource, coordinates.finalizeSemanticDigest,
        coordinates.finalizeAssignmentId, coordinates.finalizeAuthorityEpoch, coordinates.finalizeSequence,
        coordinates.paidFinalizeAt)
    val events = receiptEvents.map { row ->
        val value = row.value
        val source = returnCaseFactualReference(row.stored, value.operationId, value.actorId,
            LocalRetailCustomerReceiptCodec.COMMAND)
        LocalReturnCaseFactualReceiptEventV1(retailCustomerReceiptEventView(value), source, row.stored.payload,
            value.sourceRecoveryResources.toSet() + retailCustomerReceiptResources(coordinates.saleId, value.actorId,
                value.operationId) + returnCaseSourceResources(listOf(source)))
    }
    val sources = listOf(saleSource) + listOfNotNull(capture?.source) + events.map { it.source }
    val resources = original.recoveryResources + receiptResources + entitlementReturnPolicyResources(sale) +
        returnCaseSourceResources(sources) + events.flatMap { it.recoveryResources }
    return LocalReturnCaseFactualCutV1(sale.sale.value, sale.sale.stored.payload, finalize, capture,
        original.publicOriginal(), head?.let { retailCustomerReceiptHeadView(original, it) }, events, resources)
}

private fun returnCaseFactualReference(row: StoredAggregate, operationId: OperationId,
    actorId: DomainId, command: CommandKey): LocalReturnCaseSourceReferenceV1 =
    LocalReturnCaseSourceReferenceV1(row.key.kind, row.key.id, row.schema, row.revision, row.digest,
        operationId, actorId, command.name, command.version)
