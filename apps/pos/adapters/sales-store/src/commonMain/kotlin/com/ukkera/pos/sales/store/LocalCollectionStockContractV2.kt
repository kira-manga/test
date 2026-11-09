package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** New explicit NONPHYSICAL identity. Never relabel a pending v1/v2/physical-v3 request. */
@Serializable
public sealed interface LocalCollectionStockRequestV2 {
    @Serializable @SerialName("CREATE_ORDER")
    public data class CreateOrder(public val orderId: DomainId, public val selection: LocalOrderSelectionV1) : LocalCollectionStockRequestV2
    @Serializable @SerialName("REPLACE_UNLOCKED_ORDER")
    public data class ReplaceUnlockedOrder(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val selection: LocalOrderSelectionV1) : LocalCollectionStockRequestV2
    @Serializable @SerialName("OFFER_HANDOFF")
    public data class OfferHandoff(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val handoffId: DomainId, public val recipientId: DomainId,
        public val evidence: LocalCashEvidenceV1) : LocalCollectionStockRequestV2
    @Serializable @SerialName("CANCEL_HANDOFF")
    public data class CancelHandoff(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val handoffId: DomainId) : LocalCollectionStockRequestV2
    @Serializable @SerialName("ACCEPT_HANDOFF")
    public data class AcceptHandoff(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val handoffId: DomainId, public val evidence: LocalCashEvidenceV1) : LocalCollectionStockRequestV2
    @Serializable @SerialName("REASSIGN_ORDER")
    public data class ReassignOrder(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val previousOwnerId: DomainId, public val recipientId: DomainId, public val reasonCode: String,
        public val evidence: LocalCashEvidenceV1) : LocalCollectionStockRequestV2
    @Serializable @SerialName("BEGIN")
    public data class Begin(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val sessionId: DomainId, public val expectedSessionRevision: Revision, public val collectionId: DomainId,
        public val reservationId: DomainId, public val reservationExpiresAt: UtcInstant? = null) : LocalCollectionStockRequestV2
    @Serializable @SerialName("FINALIZE")
    public data class Finalize(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val collectionId: DomainId, public val expectedCollectionRevision: Revision,
        public val reservationRevision: Revision, public val saleId: DomainId, public val issueId: DomainId) : LocalCollectionStockRequestV2
}

public object LocalCollectionStockCodecV2 {
    public val COMMAND: CommandKey = StockStreamingCommandsV2.COLLECTION
    public fun encode(request: LocalCollectionStockRequestV2): StoredBytes {
        when (request) {
            is LocalCollectionStockRequestV2.CreateOrder -> validateNormalizedSelection(request.selection)
            is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> {
                require(request.expectedOrderRevision.value >= 0)
                validateNormalizedSelection(request.selection)
            }
            is LocalCollectionStockRequestV2.OfferHandoff -> require(request.expectedOrderRevision.value >= 0)
            is LocalCollectionStockRequestV2.CancelHandoff -> require(request.expectedOrderRevision.value >= 0)
            is LocalCollectionStockRequestV2.AcceptHandoff -> require(request.expectedOrderRevision.value >= 0)
            is LocalCollectionStockRequestV2.ReassignOrder -> {
                require(request.expectedOrderRevision.value >= 0)
                require(request.reasonCode.length in 1..128 && request.reasonCode.all { it.isLetterOrDigit() || it in "._-" }) {
                    "sales.reassignment.reason.invalid"
                }
            }
            is LocalCollectionStockRequestV2.Begin -> require(request.expectedOrderRevision.value >= 0 && request.expectedSessionRevision.value >= 0)
            is LocalCollectionStockRequestV2.Finalize -> require(request.expectedOrderRevision.value >= 0 &&
                request.expectedCollectionRevision.value >= 0 && request.reservationRevision.value >= 1)
        }
        return localSalesEncode(request, LocalCollectionStockRequestV2.serializer(), 1_048_576)
    }
    public fun decode(body: StoredBytes): LocalCollectionStockRequestV2 =
        localSalesDecode(body, LocalCollectionStockRequestV2.serializer(), 1_048_576)
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun command(operationId: OperationId, request: LocalCollectionStockRequestV2): MutationRequest =
        MutationRequest(operationId, COMMAND, encode(request))
}

/**
 * Actual trusted catalogue port, not a caller snapshot/capability flag. Both methods are
 * mandatory: current reads use the real loan; retained reads consume exact literal sources,
 * never a fabricated CommandReadView or historical AuthorizedAction.
 */
public interface TransactionCommerceStockAuthority {
    /** Same actual loan; resource aliases and unit/active checks come from the real catalogue. */
    public suspend fun observeReservationAuthority(view: CommandReadView,
        lines: List<com.ukkera.pos.inventory.store.LocalStockAllocationRequest>): Set<RecoveryResourceKey>
    /** Revalidates the exact Line authority against literal signed SKU/current warehouse sources. */
    public suspend fun requireOriginalReservationAuthority(sources: LocalCommerceOriginalSources,
        line: com.ukkera.pos.inventory.store.LocalStockDemandLine): Set<RecoveryResourceKey>
}
public interface TransactionSalesStockQuoteSource : TransactionCommerceStockAuthority {
    public suspend fun currentNormalized(view: CommandReadView, selection: LocalOrderSelectionV1): PreparedSalesQuote?
    public suspend fun requireOriginalNormalized(sources: LocalCommerceOriginalSources,
        selection: LocalOrderSelectionV1, actual: LocalSalesQuotedOrder): Set<RecoveryResourceKey>
}

/** A literal retained row, deliberately not a current StoredAggregate or an authorization. */
public data class LocalCommerceOriginalRow(public val key: AggregateKey, public val generation: Revision,
    public val schema: String, public val payload: StoredBytes, public val digest: com.ukkera.pos.security.PayloadDigest)

/**
 * Per-ID access to complete original nonstock observations. Payloads are not all hydrated;
 * every query traverses the owner's retained stream to EOF and requires explicit presence or
 * absence. This does not reread today's catalogue or mint a substitute original transaction.
 */
public class LocalCommerceOriginalSources internal constructor(public val original: RetainedStreamedCommand) {
    public val scope: com.ukkera.pos.security.BranchScope get() = original.identity.scope
    public val recordedAt: UtcInstant get() = original.identity.recordedAt
    internal val consumed: MutableSet<AggregateKey> = mutableSetOf()
    internal val consumedClaims: MutableSet<ClaimKey> = mutableSetOf()
    public suspend fun aggregate(key: AggregateKey): LocalCommerceOriginalRow? {
        check(key.kind !in StockRelationalKindsV2.all)
        var found = false; var row: LocalCommerceOriginalRow? = null
        original.frames { frame ->
            when (val source = (frame as? RetainedCommandFrameV2.Source)?.value) {
                is StreamedSourceObservationV2.Current -> if (source.key == key) {
                    check(!found); found = true
                    row = LocalCommerceOriginalRow(key, source.generation, source.schema, source.payload, source.payloadHash)
                }
                is StreamedSourceObservationV2.Aggregate -> if (source.key == key) {
                    check(!found); found = true
                    row = LocalCommerceOriginalRow(key, source.generation, source.schema, source.payload, source.payloadHash)
                }
                is StreamedSourceObservationV2.CurrentAbsent -> if (source.key == key) { check(!found); found = true }
                else -> Unit
            }
        }
        check(found) { "sales.stock.original_observation_missing" }
        consumed += key
        row?.let { check(it.generation.value >= 1 && com.ukkera.pos.security.sha256(it.payload.copyBytes()) == it.digest) }
        return row
    }
    /** Commerce-owned current rows require actual producer provenance, never a Current shortcut. */
    internal suspend fun aggregateOriginal(key: AggregateKey, operationId: OperationId? = null): LocalCommerceOriginalRow? {
        val row = aggregate(key) ?: return null
        var found = false
        original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate
            if (source?.key == key) {
                check(!found && (operationId == null || source.producer.operationId == operationId)); found = true
            }
        }
        check(found) { "sales.stock.commerce_original_source_required" }
        return row
    }
    public suspend fun claim(key: ClaimKey): DomainId? {
        var found = false; var owner: DomainId? = null
        original.frames { frame ->
            val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Claim
            if (source?.key == key) { check(!found); found = true; owner = source.ownerId }
        }
        check(found) { "sales.stock.original_claim_missing" }; consumedClaims += key
        return owner
    }
    public suspend fun original(operationId: OperationId): RetainedOriginalV2? = original.original(operationId)
}

/** Trusted current source adapters may contribute aliases; the whole producer verifies exact union. */
public interface LocalCommerceResourceCollector {
    public suspend fun observeRecoveryResources(resources: Set<RecoveryResourceKey>)
}
