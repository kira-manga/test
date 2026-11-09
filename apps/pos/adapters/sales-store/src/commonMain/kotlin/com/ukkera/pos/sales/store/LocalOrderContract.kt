package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StreamedSourceObservationV2
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.RetailStockRequirements
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class LocalSalesActivity { RESTAURANT, CAFE, CLOTHING, SUPERMARKET }
@Serializable
public enum class LocalSalesChannel { COUNTER, DINE_IN, TAKEAWAY, DELIVERY }

/**
 * Selectors only. The transaction source owns item validity, tax and price. This
 * bounded retail path admits quantities already in the exact stock base unit/scale;
 * it must not silently turn a selected pack quantity into a different sold quantity.
 */
@Serializable
public data class LocalOrderLineV1(
    public val lineId: DomainId, public val portionId: DomainId, public val itemId: DomainId,
    public val quantity: Quantity, public val variantId: DomainId? = null,
    public val modifierIds: List<DomainId> = emptyList(),
)

@Serializable
public data class LocalOrderSelectionV1(
    public val activity: LocalSalesActivity, public val channel: LocalSalesChannel,
    public val currency: Currency, public val expectedCatalogRevision: Revision,
    public val lines: List<LocalOrderLineV1>, public val serviceContextId: DomainId? = null,
)

@Serializable
public sealed interface LocalOrderRequestV1 {
    @Serializable @SerialName("CREATE")
    public data class Create(public val orderId: DomainId, public val selection: LocalOrderSelectionV1) : LocalOrderRequestV1
    @Serializable @SerialName("REPLACE_UNLOCKED")
    public data class ReplaceUnlocked(
        public val orderId: DomainId, public val expectedRevision: Revision, public val selection: LocalOrderSelectionV1,
    ) : LocalOrderRequestV1
    @Serializable @SerialName("OFFER_HANDOFF")
    public data class OfferHandoff(
        public val orderId: DomainId, public val expectedRevision: Revision, public val handoffId: DomainId,
        public val recipientId: DomainId, public val evidence: LocalCashEvidenceV1,
    ) : LocalOrderRequestV1
    @Serializable @SerialName("CANCEL_HANDOFF")
    public data class CancelHandoff(
        public val orderId: DomainId, public val expectedRevision: Revision, public val handoffId: DomainId,
    ) : LocalOrderRequestV1
    @Serializable @SerialName("ACCEPT_HANDOFF")
    public data class AcceptHandoff(
        public val orderId: DomainId, public val expectedRevision: Revision, public val handoffId: DomainId,
        public val evidence: LocalCashEvidenceV1,
    ) : LocalOrderRequestV1
}

public object LocalOrderCommandCodec {
    public fun encode(request: LocalOrderRequestV1): StoredBytes {
        when (request) {
            is LocalOrderRequestV1.Create -> validateSelection(request.selection)
            is LocalOrderRequestV1.ReplaceUnlocked -> validateSelection(request.selection)
            is LocalOrderRequestV1.OfferHandoff, is LocalOrderRequestV1.CancelHandoff, is LocalOrderRequestV1.AcceptHandoff -> Unit
        }
        return localSalesEncode(request, LocalOrderRequestV1.serializer(), 32_768)
    }
    public fun normalize(body: StoredBytes): StoredBytes = encode(decode(body))
    public fun decode(body: StoredBytes): LocalOrderRequestV1 = localSalesDecode(body, LocalOrderRequestV1.serializer(), 32_768)
}

internal fun validateSelection(selection: LocalOrderSelectionV1) {
    require(selection.lines.size in 1..128 && selection.lines.map { it.portionId }.distinct().size == selection.lines.size) { "sales.lines.invalid" }
    for (line in selection.lines) {
        require(line.quantity.scaled > 0 && line.modifierIds.size <= 32 && line.modifierIds.distinct().size == line.modifierIds.size) { "sales.lines.invalid" }
    }
}

/** Explicit new profile only. The v1 selector/normalizer remains capped at128 with identical bytes. */
internal fun validateNormalizedSelection(selection: LocalOrderSelectionV1) {
    require(selection.lines.size in 1..256 && selection.lines.map { it.portionId }.distinct().size == selection.lines.size) { "sales.stock.lines.invalid" }
    for (line in selection.lines) require(line.quantity.scaled > 0 && line.modifierIds.size <= 32 &&
        line.modifierIds.distinct().size == line.modifierIds.size) { "sales.stock.lines.invalid" }
}

/**
 * Installed by the trusted composition root, never supplied by RPC or a caller's
 * cache. current must read the complete activated definitions in this transaction.
 * It may not retain the view, query networks or substitute caller-authored snapshots.
 * Ordinary unavailable configuration/unsupported selectors return null, not a storage
 * exception. Quotes retain sold units; conversion/recipe capability needs its own
 * validated provenance rather than disguising it as this base-unit retail profile.
 */
public fun interface TransactionSalesQuoteSource {
    public suspend fun current(view: CommandReadView, selection: LocalOrderSelectionV1): PreparedSalesQuote?
}

/** Effective-time calculation uses the kernel's actual evaluation time, not the UI's clock. */
public interface PreparedSalesQuote {
    public val scope: BranchScope
    public val recoveryResources: Set<RecoveryResourceKey>
    public fun quote(quoteId: DomainId, quoteRevision: Revision, at: UtcInstant): LocalSalesQuotedOrder
}

/** Ordinary effective-time quote refusal, never a substitute for corrupt-source detection. */
public enum class LocalSalesQuoteUnavailableReason {
    NO_EFFECTIVE_PRICE, AMBIGUOUS_PRICE, INSUFFICIENT_STOCK, CAPACITY_REQUIRED, UNSUPPORTED_SELECTOR,
    RETURN_PROMISE_REQUIRES_NORMALIZED_QUOTE, ORIGINAL_PUBLICATION_UNAVAILABLE,
}

public class LocalSalesQuoteUnavailable(public val reason: LocalSalesQuoteUnavailableReason) :
    IllegalStateException("sales.quote.unavailable.${reason.name}")

/** Only stock-tracked RETAIL timing is currently admitted by this domain transition. */
public class LocalSalesQuotedOrder(
    public val quote: PricingSnapshot,
    public val stockRequirements: RetailStockRequirements,
    public val priceSourceProfile: LocalSalesPriceSourceProfile = LocalSalesPriceSourceProfile.HISTORICAL_COMPANY_ONLY_V1,
    priceSources: List<LocalSalesPriceSourceV2> = emptyList(),
    returnPolicyCapture: LocalSalesReturnPolicyCaptureInputV2? = null,
) {
    public val priceSources: List<LocalSalesPriceSourceV2> = priceSources.toList()
    public val returnPolicyCapture: LocalSalesReturnPolicyCaptureInputV2? = returnPolicyCapture?.let {
        it.copy(portions = it.portions.toList(), publications = it.publications.toList())
    }
    init {
        require(this.returnPolicyCapture == null || stockRequirements.normalized &&
            priceSourceProfile == LocalSalesPriceSourceProfile.LOCAL_RETAIL_COMPANY_BRANCH_V2)
        this.returnPolicyCapture?.let(::requireReturnPolicyInput)
        require(if (priceSourceProfile == LocalSalesPriceSourceProfile.HISTORICAL_COMPANY_ONLY_V1) priceSources.isEmpty()
            else priceSources.isNotEmpty() && priceSources.size <= 256)
    }
}

public fun interface TransactionSalesTenderSource {
    public suspend fun current(view: CommandReadView, tenderId: DomainId, expectedRevision: Revision): LocalSalesTenderSnapshot?
}

public class LocalSalesTenderSnapshot(
    public val scope: BranchScope,
    public val tender: Tender.External,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    public val recoveryResources: Set<RecoveryResourceKey> = recoveryResources.toSet()
}

/** Inert canonical catalog-row decoder, not a current grant or a historical view.
 * Consumers must obtain this exact row from their real same-loan/native BEFORE source.
 * Missing/unsupported configuration never supplies an accepting tender snapshot. */
public fun interface HistoricalSalesTenderPolicySourceV1 {
    public fun decode(scope: BranchScope, row: StoredAggregate): LocalSalesTenderSnapshot?
    /** Original literal DATA only. No StoredAggregate or historical view is minted. */
    public fun decode(scope: BranchScope, row: StreamedSourceObservationV2.Current): LocalSalesTenderSnapshot? = null
}

public object LocalSalesTenderPolicyProfileV1 {
    public const val KIND: String = "local.payment.tender"
    public const val SCHEMA: String = "local.payment.tender/v1"
    public fun key(tenderId: DomainId): AggregateKey = AggregateKey(KIND, tenderId)
}

public object UnavailableHistoricalSalesTenderPolicySourceV1 : HistoricalSalesTenderPolicySourceV1 {
    override public fun decode(scope: BranchScope, row: StoredAggregate): LocalSalesTenderSnapshot? = null
}

/** Safe defaults do not convert unavailable catalog/tender configuration to free or cash sales. */
public object UnavailableSalesQuoteSource : TransactionSalesQuoteSource {
    override public suspend fun current(view: CommandReadView, selection: LocalOrderSelectionV1): PreparedSalesQuote? = null
}
public object UnavailableSalesTenderSource : TransactionSalesTenderSource {
    override public suspend fun current(view: CommandReadView, tenderId: DomainId, expectedRevision: Revision): LocalSalesTenderSnapshot? = null
}
