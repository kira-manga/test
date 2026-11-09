package com.ukkera.pos.database

import com.ukkera.pos.inventory.InventoryFailure
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.CollectionLock
import com.ukkera.pos.sales.CollectionOutcome
import com.ukkera.pos.sales.ExternalTenderEvidence
import com.ukkera.pos.sales.ExternalTenderKind
import com.ukkera.pos.sales.OwnedOrder
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.ReceiptEntitlement
import com.ukkera.pos.sales.RetailStockRequirements
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.SalesUniqueKind
import com.ukkera.pos.sales.Tender
import com.ukkera.pos.sales.store.CashNonSaleStoreProfile
import com.ukkera.pos.sales.store.SalesStorageRevision
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlin.text.CharacterCodingException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * TEST ONLY. Closed synthetic checkout data and mechanical row bindings, not an installed
 * gateway, producer approval, custody/authorization source, payment provider or fiscal adapter.
 * Requests never contain caller snapshots, actors, scope, operation IDs, digests or proofs.
 */
internal object CheckoutFixtureProfile {
    val OPEN = CommandKey("fixture.checkout.open", 1)
    val CREATE = CommandKey("fixture.checkout.create", 1)
    val BEGIN = CommandKey("fixture.checkout.begin", 1)
    val ATTEMPT = CommandKey("fixture.checkout.attempt", 1)
    val OUTCOME = CommandKey("fixture.checkout.outcome", 1)
    val FINALIZE = CommandKey("fixture.checkout.finalize", 1)
    val COMMANDS: Set<CommandKey> get() = setOf(OPEN, CREATE, BEGIN, ATTEMPT, OUTCOME, FINALIZE)

    const val SESSION_KIND = CashNonSaleStoreProfile.SESSION_KIND
    const val SESSION_SCHEMA = CashNonSaleStoreProfile.SESSION_SCHEMA
    const val ORDER_KIND = "fixture.checkout.order"
    const val ORDER_SCHEMA = "fixture.checkout.order/v1"
    const val COLLECTION_KIND = "fixture.checkout.collection"
    const val COLLECTION_SCHEMA = "fixture.checkout.collection/v1"
    const val SALE_KIND = "fixture.checkout.sale"
    const val SALE_SCHEMA = "fixture.checkout.sale/v1"
    const val ENTITLEMENT_KIND = "fixture.checkout.entitlement"
    const val ENTITLEMENT_SCHEMA = "fixture.checkout.entitlement/v1"
    const val DRAWER_KIND = "fixture.checkout.drawer"
    const val DRAWER_SCHEMA = "fixture.checkout.drawer/v1"
    const val CUSTODIAN_KIND = "fixture.checkout.custodian"
    const val CUSTODIAN_SCHEMA = "fixture.checkout.custodian/v1"

    const val MAX_REQUEST_BYTES = 32_768
    const val MAX_RESULT_BYTES = 4_096
    const val MAX_ROW_BYTES = 65_536
    // The composer still owes its WHOLE binary-framed proposal bound, not just row sums.
    const val MAX_PROPOSAL_BYTES = 1_048_576
    const val MAX_RESERVATION_EPOCH_MILLIS = 253_402_300_799_999L // End of UTC year 9999.

    val QUOTE_REVISION = Revision(1)
    val PRICE_REVISION = Revision(1)
    val TAX_REVISION = Revision(1)
    val TAX_ID = DomainId("00000000-0000-4000-8000-000000390001")
    const val TAX_CATEGORY = "SYNTHETIC_ZERO"

    fun salesKey(kind: SalesSnapshotKind, id: DomainId): AggregateKey = AggregateKey(when (kind) {
        SalesSnapshotKind.CASH_SESSION -> SESSION_KIND
        SalesSnapshotKind.ORDER -> ORDER_KIND
        SalesSnapshotKind.COLLECTION -> COLLECTION_KIND
        SalesSnapshotKind.PAID_SALE -> SALE_KIND
        SalesSnapshotKind.RECEIPT_ENTITLEMENT -> ENTITLEMENT_KIND
        else -> throw CheckoutContractRejected(CheckoutContractFailure.UNSUPPORTED)
    }, id)

    fun schema(kind: SalesSnapshotKind): String = when (kind) {
        SalesSnapshotKind.CASH_SESSION -> SESSION_SCHEMA
        SalesSnapshotKind.ORDER -> ORDER_SCHEMA
        SalesSnapshotKind.COLLECTION -> COLLECTION_SCHEMA
        SalesSnapshotKind.PAID_SALE -> SALE_SCHEMA
        SalesSnapshotKind.RECEIPT_ENTITLEMENT -> ENTITLEMENT_SCHEMA
        else -> throw CheckoutContractRejected(CheckoutContractFailure.UNSUPPORTED)
    }

    fun drawerKey(id: DomainId): AggregateKey = AggregateKey(DRAWER_KIND, id)
    fun custodianKey(id: DomainId): AggregateKey = AggregateKey(CUSTODIAN_KIND, id)

    /** Keys only. These do not check current ownership or grant permission to acquire a claim. */
    fun claim(kind: SalesUniqueKind, id: DomainId): ClaimKey = ClaimKey(when (kind) {
        SalesUniqueKind.CASH_SESSION -> "fixture.checkout.cash_session"
        SalesUniqueKind.OPENING_FLOAT_SOURCE -> "fixture.checkout.opening_float_source"
        SalesUniqueKind.ACTIVE_DRAWER -> "fixture.checkout.active_drawer"
        SalesUniqueKind.ACTIVE_CUSTODIAN -> "fixture.checkout.active_custodian"
        SalesUniqueKind.CASH_SOURCE -> CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE
        SalesUniqueKind.ORDER -> "fixture.checkout.order"
        SalesUniqueKind.COLLECTION -> "fixture.checkout.collection"
        SalesUniqueKind.PAYMENT_ATTEMPT -> "fixture.checkout.payment_attempt"
        SalesUniqueKind.PAYMENT_RECORD -> "fixture.checkout.payment_record"
        SalesUniqueKind.SALE -> "fixture.checkout.sale"
        SalesUniqueKind.SALE_ORDER -> "fixture.checkout.sale_order"
        SalesUniqueKind.RETURN_ENTITLEMENT -> "fixture.checkout.entitlement"
        else -> throw CheckoutContractRejected(CheckoutContractFailure.UNSUPPORTED)
    }, id.value)
}

/** Domain revision: expected in a request, actual after-state in a result; never storage generation. */
@Serializable
internal data class CheckoutRef(val id: DomainId, val revision: Revision)

@Serializable
internal data class CheckoutCashRef(
    val session: CheckoutRef,
    val drawerAssignmentRevision: Revision,
    val custodianAssignmentRevision: Revision,
)

@Serializable
internal data class CheckoutSalesRef(val order: CheckoutRef, val collection: CheckoutRef, val cash: CheckoutCashRef)

@Serializable
internal data class CheckoutReservationRef(val inventoryRevision: Revision, val reservation: CheckoutRef)

@Serializable
internal data class CheckoutEvidence(val evidenceId: DomainId, val occurredAt: UtcInstant)

@Serializable
internal enum class CheckoutBasket { ZERO, SIMPLE_100, SPLIT_33_33_34 }

/** One concrete priced portion and its one complete owned/released stock allocation. */
@Serializable
internal data class CheckoutLine(
    val portionId: DomainId,
    val allocationId: DomainId,
    val skuId: DomainId,
    val warehouseId: DomainId,
    val lotId: DomainId?,
    val quantity: Quantity,
)

@Serializable
internal enum class CheckoutExternalKind { CARD, EXTERNAL_WALLET, OTHER }

@Serializable
internal sealed interface CheckoutTender {
    @Serializable @SerialName("CASH") data object Cash : CheckoutTender
    /** Synthetic pinned configuration for this fixture, NOT evidence of live provider activation. */
    @Serializable @SerialName("EXTERNAL") data class External(
        val tenderId: DomainId,
        val policyRevision: Revision,
        val kind: CheckoutExternalKind,
        val requiresTerminal: Boolean,
        val requiresReference: Boolean,
    ) : CheckoutTender
}

@Serializable
internal sealed interface CheckoutOutcome {
    @Serializable @SerialName("UNKNOWN") data class Unknown(val evidence: CheckoutEvidence) : CheckoutOutcome
    @Serializable @SerialName("NOT_PAID") data class NotPaid(val evidence: CheckoutEvidence, val reason: String) : CheckoutOutcome
    @Serializable @SerialName("CASH_RECEIVED") data class CashReceived(
        val received: Money, val change: Money, val evidence: CheckoutEvidence,
    ) : CheckoutOutcome
    @Serializable @SerialName("EXTERNAL_ATTESTED") data class ExternalAttested(
        val evidence: CheckoutEvidence, val terminal: String?, val reference: String?,
    ) : CheckoutOutcome
}

@Serializable
internal sealed interface CheckoutExposureUpdate {
    @Serializable @SerialName("PRESERVE") data object Preserve : CheckoutExposureUpdate
    /** Publishes the actual AFTER collection's conservative exposure; no requested exposure enum. */
    @Serializable @SerialName("DERIVE") data class Derive(val stock: CheckoutReservationRef) : CheckoutExposureUpdate
}

@Serializable
internal sealed interface CheckoutRequest {
    @Serializable @SerialName("OPEN") data class Open(
        val drawerId: DomainId,
        val expectedDrawerRevision: Revision,
        val expectedCustodianRevision: Revision,
        val sessionId: DomainId,
        val businessDate: BusinessDate,
        val opening: Money,
        val openingSourceId: DomainId,
        val evidence: CheckoutEvidence,
    ) : CheckoutRequest

    @Serializable @SerialName("CREATE") data class Create(
        val orderId: DomainId,
        val quoteId: DomainId,
        val basket: CheckoutBasket,
        val lines: List<CheckoutLine>,
    ) : CheckoutRequest

    @Serializable @SerialName("BEGIN") data class Begin(
        val order: CheckoutRef,
        val cash: CheckoutCashRef,
        val collectionId: DomainId,
        val expectedInventoryRevision: Revision,
        val reservationId: DomainId,
        @Serializable(with = MinorUnitsSerializer::class) val reservationExpiresAtEpochMillis: Long?,
    ) : CheckoutRequest

    @Serializable @SerialName("ATTEMPT") data class Attempt(
        val sales: CheckoutSalesRef,
        val stock: CheckoutReservationRef,
        val attemptId: DomainId,
        val tender: CheckoutTender,
        val amount: Money,
        val payerReference: String?,
    ) : CheckoutRequest

    @Serializable @SerialName("OUTCOME") data class Outcome(
        val sales: CheckoutSalesRef,
        val attemptId: DomainId,
        val outcome: CheckoutOutcome,
        val exposure: CheckoutExposureUpdate,
    ) : CheckoutRequest

    @Serializable @SerialName("FINALIZE") data class Finalize(
        val sales: CheckoutSalesRef,
        val stock: CheckoutReservationRef,
        val issueId: DomainId,
        val saleId: DomainId,
    ) : CheckoutRequest
}

/** After-state references are result DATA, not an expected-write port or a reusable approval. */
@Serializable
internal sealed interface CheckoutResult {
    @Serializable @SerialName("OPENED") data class Opened(val cash: CheckoutCashRef) : CheckoutResult
    @Serializable @SerialName("CREATED") data class Created(val order: CheckoutRef) : CheckoutResult
    @Serializable @SerialName("BEGUN") data class Begun(val sales: CheckoutSalesRef, val stock: CheckoutReservationRef) : CheckoutResult
    @Serializable @SerialName("ATTEMPTED") data class Attempted(
        val sales: CheckoutSalesRef, val stock: CheckoutReservationRef, val attemptId: DomainId,
    ) : CheckoutResult
    @Serializable @SerialName("OUTCOME_RECORDED") data class OutcomeRecorded(
        val sales: CheckoutSalesRef, val attemptId: DomainId, val stock: CheckoutReservationRef?,
    ) : CheckoutResult
    @Serializable @SerialName("HELD") data class Held(val sales: CheckoutSalesRef, val stock: CheckoutReservationRef) : CheckoutResult
    @Serializable @SerialName("ISSUED") data class Issued(
        val sales: CheckoutSalesRef, val stock: CheckoutReservationRef, val issueId: DomainId, val saleId: DomainId,
    ) : CheckoutResult
    @Serializable @SerialName("REJECTED") data class Rejected(val code: String) : CheckoutResult
}

/** Stable codes only; never concatenate exceptions, evidence or private request data into a code. */
internal object CheckoutRejectionCodes {
    const val CAPACITY = "fixture.checkout.capacity"
    const val SOURCE_CONFLICT = "fixture.checkout.source-conflict"
    fun sales(rule: SalesRule): String = "fixture.checkout.sales.${rule.name.lowercase().replace('_', '-')}"
    fun inventory(failure: InventoryFailure): String = "fixture.checkout.inventory.${failure.name.lowercase().replace('_', '-')}"
    private val known: Set<String> = SalesRule.entries.map(::sales).toSet() +
        InventoryFailure.entries.map(::inventory).toSet() + setOf(CAPACITY, SOURCE_CONFLICT)
    fun isKnown(code: String): Boolean = code in known
}

internal object CheckoutFixtureCodec {
    fun keyOf(request: CheckoutRequest): CommandKey = when (request) {
        is CheckoutRequest.Open -> CheckoutFixtureProfile.OPEN
        is CheckoutRequest.Create -> CheckoutFixtureProfile.CREATE
        is CheckoutRequest.Begin -> CheckoutFixtureProfile.BEGIN
        is CheckoutRequest.Attempt -> CheckoutFixtureProfile.ATTEMPT
        is CheckoutRequest.Outcome -> CheckoutFixtureProfile.OUTCOME
        is CheckoutRequest.Finalize -> CheckoutFixtureProfile.FINALIZE
    }

    fun encode(request: CheckoutRequest): StoredBytes = checkoutGuard {
        validateCheckoutRequest(request)
        encodeCheckoutBody(request, CheckoutRequest.serializer(), CheckoutFixtureProfile.MAX_REQUEST_BYTES)
    }

    fun decode(key: CommandKey, bytes: StoredBytes): CheckoutRequest = checkoutGuard {
        checkoutCheck(key in CheckoutFixtureProfile.COMMANDS, CheckoutContractFailure.UNSUPPORTED)
        val request = decodeCheckoutBody(bytes, CheckoutRequest.serializer(), CheckoutFixtureProfile.MAX_REQUEST_BYTES)
        checkoutCheck(keyOf(request) == key, CheckoutContractFailure.BINDING_MISMATCH)
        validateCheckoutRequest(request)
        request
    }

    fun normalize(key: CommandKey, bytes: StoredBytes): StoredBytes = encode(decode(key, bytes))

    fun encodeResult(result: CheckoutResult): StoredBytes = checkoutGuard {
        validateCheckoutResult(result)
        encodeCheckoutBody(result, CheckoutResult.serializer(), CheckoutFixtureProfile.MAX_RESULT_BYTES)
    }

    fun decodeResult(key: CommandKey, bytes: StoredBytes): CheckoutResult = checkoutGuard {
        checkoutCheck(key in CheckoutFixtureProfile.COMMANDS, CheckoutContractFailure.UNSUPPORTED)
        val result = decodeCheckoutBody(bytes, CheckoutResult.serializer(), CheckoutFixtureProfile.MAX_RESULT_BYTES)
        val matches = when (result) {
            is CheckoutResult.Opened -> key == CheckoutFixtureProfile.OPEN
            is CheckoutResult.Created -> key == CheckoutFixtureProfile.CREATE
            is CheckoutResult.Begun -> key == CheckoutFixtureProfile.BEGIN
            is CheckoutResult.Attempted -> key == CheckoutFixtureProfile.ATTEMPT
            is CheckoutResult.OutcomeRecorded -> key == CheckoutFixtureProfile.OUTCOME
            is CheckoutResult.Held, is CheckoutResult.Issued -> key == CheckoutFixtureProfile.FINALIZE
            is CheckoutResult.Rejected -> true
        }
        checkoutCheck(matches, CheckoutContractFailure.BINDING_MISMATCH)
        validateCheckoutResult(result)
        result
    }
}

internal fun CheckoutEvidence.toDomain(actorId: DomainId): CashierEvidence = checkoutGuard {
    validateCheckoutEvidence(this)
    CashierEvidence(evidenceId, actorId, occurredAt.value)
}

internal fun CheckoutTender.toDomain(): Tender = when (this) {
    CheckoutTender.Cash -> Tender.Cash
    is CheckoutTender.External -> Tender.External(tenderId, policyRevision, when (kind) {
        CheckoutExternalKind.CARD -> ExternalTenderKind.CARD
        CheckoutExternalKind.EXTERNAL_WALLET -> ExternalTenderKind.EXTERNAL_WALLET
        CheckoutExternalKind.OTHER -> ExternalTenderKind.OTHER
    }, requiresTerminal, requiresReference)
}

internal fun CheckoutOutcome.toDomain(actorId: DomainId): CollectionOutcome = checkoutGuard {
    validateCheckoutOutcome(this)
    when (this) {
        is CheckoutOutcome.Unknown -> CollectionOutcome.Unknown(evidence.toDomain(actorId))
        is CheckoutOutcome.NotPaid -> CollectionOutcome.NotPaid(evidence.toDomain(actorId), reason)
        is CheckoutOutcome.CashReceived -> CollectionOutcome.CashReceived(received, change, evidence.toDomain(actorId))
        is CheckoutOutcome.ExternalAttested -> CollectionOutcome.ExternalAttested(
            ExternalTenderEvidence(evidence.toDomain(actorId), terminal, reference))
    }
}

internal fun CheckoutLine.toAllocation(scope: BranchScope): StockAllocation = checkoutGuard {
    validateCheckoutLine(this)
    StockAllocation(allocationId, portionId,
        StockPositionKey(StockPoolKey(InventoryScope(scope.companyId, scope.branchId), skuId, warehouseId), lotId), quantity)
}

/** Literal synthetic prices and zero tax; no catalog/policy reads, caller quote, discounts or charges. */
internal fun checkoutBasket(scope: BranchScope, request: CheckoutRequest.Create): Pair<PricingSnapshot, RetailStockRequirements> = checkoutGuard {
    validateCheckoutCreate(request)
    val lines = request.lines.toList()
    val prices = when (request.basket) {
        CheckoutBasket.ZERO -> listOf("0")
        CheckoutBasket.SIMPLE_100 -> listOf("1.00")
        CheckoutBasket.SPLIT_33_33_34 -> listOf("0.33", "0.33", "0.34")
    }
    val tax = TaxSnapshot(CheckoutFixtureProfile.TAX_ID, CheckoutFixtureProfile.TAX_REVISION,
        CheckoutFixtureProfile.TAX_CATEGORY, PercentageRate.parse("0"))
    val quote = OperationalPricingEngine.calculate(PricingRequest(request.quoteId, CheckoutFixtureProfile.QUOTE_REVISION,
        Currency.EGP, lines.mapIndexed { index, line ->
            ResolvedPriceLine(line.portionId, line.portionId, CheckoutFixtureProfile.PRICE_REVISION, line.quantity,
                UnitPrice.parse(Currency.EGP, prices[index]), TaxInclusion.EXCLUSIVE, tax, DiscountLimits.none(Currency.EGP))
        }))
    checkoutCheck(quote.payable == Money(Currency.EGP, if (request.basket == CheckoutBasket.ZERO) 0L else 100L))
    quote to RetailStockRequirements(lines.map { it.toAllocation(scope) })
}

/** Only these primitive empty assignments may be seeded; open/create still use real typed commits. */
@Serializable
internal data class CheckoutDrawerAssignment(
    val scope: BranchScope,
    val drawerId: DomainId,
    val revision: Revision,
    val activeSessionId: DomainId?,
    val unresolvedClosingTransferId: DomainId?,
)

@Serializable
internal data class CheckoutCustodianAssignment(
    val scope: BranchScope,
    val custodianId: DomainId,
    val revision: Revision,
    val activeSessionId: DomainId?,
)

/** Copied row scalars only. Neither a live read capability nor historical complete-producer approval. */
internal data class CheckoutRow(
    val key: AggregateKey,
    val storageGeneration: Revision,
    val schema: String,
    val payload: StoredBytes,
    val payloadDigest: PayloadDigest,
) {
    companion object {
        fun from(row: StoredAggregate): CheckoutRow = CheckoutRow(row.key, row.revision, row.schema, row.payload, row.digest)
        fun from(row: RetainedAggregateWrite): CheckoutRow = CheckoutRow(row.key, row.storageGeneration, row.schema, row.payload, row.payloadDigest)
    }
}

/** Mechanical profile/codec checks. Current cross-links, custody, claims and admission remain external. */
internal object CheckoutRows {
    fun text(row: CheckoutRow): String = checkoutGuard {
        checkoutCheck(row.payload.size <= CheckoutFixtureProfile.MAX_ROW_BYTES, CheckoutContractFailure.TOO_LARGE)
        val bytes = row.payload.copyBytes()
        checkoutCheck(sha256(bytes) == row.payloadDigest, CheckoutContractFailure.BINDING_MISMATCH)
        bytes.decodeToString(throwOnInvalidSequence = true)
    }

    fun binding(row: CheckoutRow, scope: BranchScope, kind: SalesSnapshotKind, id: DomainId): SalesSnapshotBinding = checkoutGuard {
        requireRowProfile(row, CheckoutFixtureProfile.salesKey(kind, id), CheckoutFixtureProfile.schema(kind))
        if (kind == SalesSnapshotKind.PAID_SALE) checkoutCheck(row.storageGeneration == Revision(1), CheckoutContractFailure.BINDING_MISMATCH)
        SalesSnapshotBinding(kind, scope, id, SalesStorageRevision.toDomain(row.storageGeneration))
    }

    fun cash(row: CheckoutRow, scope: BranchScope, id: DomainId): CashSession = checkoutGuard {
        SalesSnapshotCodec.decodeCashSession(text(row), binding(row, scope, SalesSnapshotKind.CASH_SESSION, id))
    }

    fun order(row: CheckoutRow, scope: BranchScope, id: DomainId): OwnedOrder = checkoutGuard {
        SalesSnapshotCodec.decodeOrder(text(row), binding(row, scope, SalesSnapshotKind.ORDER, id))
    }

    fun historicalCollection(row: CheckoutRow, scope: BranchScope, id: DomainId): CollectionLock = checkoutGuard {
        SalesSnapshotCodec.decodeHistoricalCollection(text(row), binding(row, scope, SalesSnapshotKind.COLLECTION, id))
    }

    fun collection(row: CheckoutRow, scope: BranchScope, id: DomainId, order: OwnedOrder, cash: CashSession): CollectionLock = checkoutGuard {
        SalesSnapshotCodec.decodeCollection(text(row), binding(row, scope, SalesSnapshotKind.COLLECTION, id), order, cash)
    }

    fun sale(row: CheckoutRow, scope: BranchScope, id: DomainId): PaidSale = checkoutGuard {
        SalesSnapshotCodec.decodePaidSale(text(row), binding(row, scope, SalesSnapshotKind.PAID_SALE, id))
    }

    fun entitlement(row: CheckoutRow, scope: BranchScope, id: DomainId): ReceiptEntitlement = checkoutGuard {
        SalesSnapshotCodec.decodeReceiptEntitlement(text(row), binding(row, scope, SalesSnapshotKind.RECEIPT_ENTITLEMENT, id))
    }

    fun drawer(row: CheckoutRow, scope: BranchScope, id: DomainId): CheckoutDrawerAssignment = checkoutGuard {
        requireRowProfile(row, CheckoutFixtureProfile.drawerKey(id), CheckoutFixtureProfile.DRAWER_SCHEMA)
        val value = decodeCheckoutBody(row.payload, CheckoutDrawerAssignment.serializer(), CheckoutFixtureProfile.MAX_ROW_BYTES)
        checkoutCheck(value.scope == scope && value.drawerId == id &&
            value.revision == SalesStorageRevision.toDomain(row.storageGeneration), CheckoutContractFailure.BINDING_MISMATCH)
        value
    }

    fun custodian(row: CheckoutRow, scope: BranchScope, id: DomainId): CheckoutCustodianAssignment = checkoutGuard {
        requireRowProfile(row, CheckoutFixtureProfile.custodianKey(id), CheckoutFixtureProfile.CUSTODIAN_SCHEMA)
        val value = decodeCheckoutBody(row.payload, CheckoutCustodianAssignment.serializer(), CheckoutFixtureProfile.MAX_ROW_BYTES)
        checkoutCheck(value.scope == scope && value.custodianId == id &&
            value.revision == SalesStorageRevision.toDomain(row.storageGeneration), CheckoutContractFailure.BINDING_MISMATCH)
        value
    }

    fun encodeCash(value: CashSession): StoredBytes = checkoutGuard { encodedRow(SalesSnapshotCodec.encodeCashSession(value)) }
    fun encodeOrder(value: OwnedOrder): StoredBytes = checkoutGuard { encodedRow(SalesSnapshotCodec.encodeOrder(value)) }
    fun encodeCollection(value: CollectionLock): StoredBytes = checkoutGuard { encodedRow(SalesSnapshotCodec.encodeCollection(value)) }
    fun encodeSale(value: PaidSale): StoredBytes = checkoutGuard { encodedRow(SalesSnapshotCodec.encodePaidSale(value)) }
    fun encodeEntitlement(value: ReceiptEntitlement): StoredBytes = checkoutGuard { encodedRow(SalesSnapshotCodec.encodeReceiptEntitlement(value)) }

    fun encodeDrawer(value: CheckoutDrawerAssignment): StoredBytes = checkoutGuard {
        SalesStorageRevision.toStorage(value.revision)
        encodeCheckoutBody(value, CheckoutDrawerAssignment.serializer(), CheckoutFixtureProfile.MAX_ROW_BYTES)
    }

    fun encodeCustodian(value: CheckoutCustodianAssignment): StoredBytes = checkoutGuard {
        SalesStorageRevision.toStorage(value.revision)
        encodeCheckoutBody(value, CheckoutCustodianAssignment.serializer(), CheckoutFixtureProfile.MAX_ROW_BYTES)
    }

    private fun requireRowProfile(row: CheckoutRow, key: AggregateKey, schema: String) {
        checkoutCheck(row.key == key && row.schema == schema && row.storageGeneration.value > 0L,
            CheckoutContractFailure.BINDING_MISMATCH)
        text(row) // Checks bounded payload, hash and strict UTF-8 before any domain decode.
    }

    private fun encodedRow(text: String): StoredBytes {
        checkoutCheck(text.length <= CheckoutFixtureProfile.MAX_ROW_BYTES, CheckoutContractFailure.TOO_LARGE)
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        checkoutCheck(bytes.size <= CheckoutFixtureProfile.MAX_ROW_BYTES, CheckoutContractFailure.TOO_LARGE)
        return StoredBytes(bytes)
    }
}

internal enum class CheckoutContractFailure { MALFORMED, TOO_LARGE, NON_CANONICAL, BINDING_MISMATCH, UNSUPPORTED, INCONSISTENT }

/** No parser transcript or protected values escape through contract errors. */
internal class CheckoutContractRejected(val failure: CheckoutContractFailure) : IllegalArgumentException(failure.name)

private val checkoutJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}

private fun validateCheckoutRequest(request: CheckoutRequest) {
    when (request) {
        is CheckoutRequest.Open -> {
            validateCheckoutMoney(request.opening, positive = false)
            validateCheckoutEvidence(request.evidence)
            val date = request.businessDate.canonicalString()
            checkoutCheck(date.length == 10 && date.take(4).all { it in '0'..'9' })
        }
        is CheckoutRequest.Create -> validateCheckoutCreate(request)
        is CheckoutRequest.Begin -> checkoutCheck(request.reservationExpiresAtEpochMillis == null ||
            request.reservationExpiresAtEpochMillis in 0L..CheckoutFixtureProfile.MAX_RESERVATION_EPOCH_MILLIS)
        is CheckoutRequest.Attempt -> {
            validateCheckoutMoney(request.amount, positive = true)
            validateCheckoutOptionalText(request.payerReference, 128)
        }
        is CheckoutRequest.Outcome -> validateCheckoutOutcome(request.outcome)
        is CheckoutRequest.Finalize -> Unit
    }
}

private fun validateCheckoutCreate(request: CheckoutRequest.Create) {
    val count = if (request.basket == CheckoutBasket.SPLIT_33_33_34) 3 else 1
    checkoutCheck(request.lines.size == count, CheckoutContractFailure.TOO_LARGE)
    checkoutCheck(request.lines.map { it.portionId }.distinct().size == count &&
        request.lines.map { it.allocationId }.distinct().size == count)
    request.lines.forEach(::validateCheckoutLine)
}

private fun validateCheckoutLine(line: CheckoutLine) {
    checkoutCheck(line.quantity.scaled == 1L && line.quantity.scale == 0)
}

private fun validateCheckoutEvidence(evidence: CheckoutEvidence) {
    requireEvidenceV1Time(evidence.occurredAt)
}

private fun validateCheckoutOutcome(outcome: CheckoutOutcome) {
    when (outcome) {
        is CheckoutOutcome.Unknown -> validateCheckoutEvidence(outcome.evidence)
        is CheckoutOutcome.NotPaid -> {
            validateCheckoutEvidence(outcome.evidence)
            validateCheckoutText(outcome.reason, 256)
        }
        is CheckoutOutcome.CashReceived -> {
            validateCheckoutEvidence(outcome.evidence)
            validateCheckoutMoney(outcome.received, positive = true)
            validateCheckoutMoney(outcome.change, positive = false)
        }
        is CheckoutOutcome.ExternalAttested -> {
            validateCheckoutEvidence(outcome.evidence)
            validateCheckoutOptionalText(outcome.terminal, 128)
            validateCheckoutOptionalText(outcome.reference, 128)
        }
    }
}

private fun validateCheckoutMoney(value: Money, positive: Boolean) {
    checkoutCheck(value.currency == Currency.EGP && if (positive) value.minor > 0L else value.minor >= 0L)
}

private fun validateCheckoutOptionalText(value: String?, maximum: Int) {
    if (value != null) validateCheckoutText(value, maximum)
}

private fun validateCheckoutText(value: String, maximum: Int) {
    checkoutCheck(value.length <= maximum, CheckoutContractFailure.TOO_LARGE)
    checkoutCheck(value.isNotBlank())
    value.encodeToByteArray(throwOnInvalidSequence = true) // Also reject escaped lone surrogate values.
}

private fun validateCheckoutResult(result: CheckoutResult) {
    fun ref(value: CheckoutRef) { SalesStorageRevision.toStorage(value.revision) }
    fun cash(value: CheckoutCashRef) {
        ref(value.session)
        SalesStorageRevision.toStorage(value.drawerAssignmentRevision)
        SalesStorageRevision.toStorage(value.custodianAssignmentRevision)
    }
    fun sales(value: CheckoutSalesRef) { ref(value.order); ref(value.collection); cash(value.cash) }
    fun stock(value: CheckoutReservationRef) {
        SalesStorageRevision.toStorage(value.inventoryRevision)
        checkoutCheck(value.reservation.revision.value > 0L)
    }
    when (result) {
        is CheckoutResult.Opened -> {
            cash(result.cash)
            checkoutCheck(result.cash.session.revision == Revision(0) && result.cash.drawerAssignmentRevision.value > 0L &&
                result.cash.custodianAssignmentRevision.value > 0L)
        }
        is CheckoutResult.Created -> { ref(result.order); checkoutCheck(result.order.revision == Revision(0)) }
        is CheckoutResult.Begun -> {
            sales(result.sales); stock(result.stock)
            checkoutCheck(result.sales.order.revision.value > 0L && result.sales.collection.revision == Revision(0) &&
                result.stock.reservation.revision == Revision(1))
        }
        is CheckoutResult.Attempted -> { sales(result.sales); stock(result.stock) }
        is CheckoutResult.OutcomeRecorded -> { sales(result.sales); result.stock?.let(::stock) }
        is CheckoutResult.Held -> { sales(result.sales); stock(result.stock) }
        is CheckoutResult.Issued -> { sales(result.sales); stock(result.stock) }
        is CheckoutResult.Rejected -> checkoutCheck(CheckoutRejectionCodes.isKnown(result.code), CheckoutContractFailure.UNSUPPORTED)
    }
}

private fun <T> encodeCheckoutBody(value: T, serializer: KSerializer<T>, maximum: Int): StoredBytes {
    val text = checkoutJson.encodeToString(serializer, value)
    checkoutCheck(text.length <= maximum, CheckoutContractFailure.TOO_LARGE)
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    checkoutCheck(bytes.size <= maximum, CheckoutContractFailure.TOO_LARGE)
    boundCheckoutJson(text)
    return StoredBytes(bytes)
}

private fun <T> decodeCheckoutBody(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int): T {
    checkoutCheck(bytes.size <= maximum, CheckoutContractFailure.TOO_LARGE)
    val text = bytes.copyBytes().decodeToString(throwOnInvalidSequence = true)
    boundCheckoutJson(text) // Before recursive DTO allocation, including unsupported/unknown members.
    val value = checkoutJson.decodeFromString(serializer, text)
    checkoutCheck(checkoutJson.encodeToString(serializer, value) == text, CheckoutContractFailure.NON_CANONICAL)
    return value
}

/** The only request array is the basket's <=3 lines; results and primitive assignments have none. */
private fun boundCheckoutJson(text: String) {
    val frames = mutableListOf<Pair<Char, Int>>()
    var quoted = false
    var escaped = false
    var containers = 0
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                containers++
                checkoutCheck(frames.size < 12 && containers <= 128, CheckoutContractFailure.TOO_LARGE)
                frames += character to 0
            }
            '}', ']' -> {
                checkoutCheck(frames.isNotEmpty() && frames.last().first == if (character == ']') '[' else '{', CheckoutContractFailure.MALFORMED)
                frames.removeAt(frames.lastIndex)
            }
            ',' -> if (frames.isNotEmpty() && frames.last().first == '[') {
                val count = frames.last().second + 1
                checkoutCheck(count < 3, CheckoutContractFailure.TOO_LARGE)
                frames[frames.lastIndex] = '[' to count
            }
        }
    }
    checkoutCheck(!quoted && frames.isEmpty(), CheckoutContractFailure.MALFORMED)
}

private fun checkoutCheck(condition: Boolean, failure: CheckoutContractFailure = CheckoutContractFailure.INCONSISTENT) {
    if (!condition) throw CheckoutContractRejected(failure)
}

private inline fun <T> checkoutGuard(block: () -> T): T = try {
    block()
} catch (failure: CheckoutContractRejected) {
    throw failure
} catch (_: CharacterCodingException) {
    throw CheckoutContractRejected(CheckoutContractFailure.MALFORMED)
} catch (_: SerializationException) {
    throw CheckoutContractRejected(CheckoutContractFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    throw CheckoutContractRejected(CheckoutContractFailure.INCONSISTENT)
} catch (_: ArithmeticException) {
    throw CheckoutContractRejected(CheckoutContractFailure.INCONSISTENT)
}
