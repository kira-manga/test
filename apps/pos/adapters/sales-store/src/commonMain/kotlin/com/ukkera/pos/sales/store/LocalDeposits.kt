package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashSessionChanged
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.DepositChanged
import com.ukkera.pos.sales.DepositEntitlement
import com.ukkera.pos.sales.DepositSaleSource
import com.ukkera.pos.sales.DepositTerms
import com.ukkera.pos.sales.Deposits
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.SalesRuleViolation
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Receipt-linked refundable container deposits (LF-CAT-11, CAT-10) as one additive Master command.
 * Terms come only from the exact signed SKU publication that priced the paid sale portion
 * ([TransactionDepositTermsSource]); collected cash is a drawer liability movement, and each refund
 * is an actual cash payout of returned containers. No balance, credit or stored value exists.
 */
@Serializable
public sealed interface LocalDepositRequestV1 {
    /** The OPEN drawer custodian receives the deposit for containers leaving with the customer. */
    @Serializable @SerialName("COLLECT")
    public data class Collect(
        public val depositId: DomainId,
        public val saleId: DomainId,
        public val portionId: DomainId,
        public val sessionId: DomainId,
        public val expectedSessionRevision: Revision,
        public val containers: Long,
        public val evidence: LocalCashEvidenceV1,
    ) : LocalDepositRequestV1 { init { require(containers in 1..DepositEntitlement.MAX_CONTAINERS) } }

    /** Cash refund for physically returned containers, from the refunder's OPEN drawer. */
    @Serializable @SerialName("REFUND")
    public data class Refund(
        public val depositId: DomainId,
        public val expectedDepositRevision: Revision,
        public val sessionId: DomainId,
        public val expectedSessionRevision: Revision,
        public val refundId: DomainId,
        public val containers: Long,
        public val evidence: LocalCashEvidenceV1,
    ) : LocalDepositRequestV1 { init { require(containers in 1..DepositEntitlement.MAX_CONTAINERS) } }
}

@Serializable
public data class LocalDepositRefundV1(
    public val refundId: DomainId,
    public val operationId: OperationId,
    public val sessionId: DomainId,
    public val containers: Long,
    public val amount: Money,
    public val evidence: LocalCashActorEvidenceV1,
    public val recordedAt: UtcInstant,
)

@Serializable
public data class LocalDepositViewV1(
    public val id: DomainId,
    public val revision: Revision,
    public val saleId: DomainId,
    public val portionId: DomainId,
    public val skuId: DomainId,
    public val publicationId: DomainId,
    public val amountPerContainer: Money,
    public val liabilityMappingId: DomainId,
    public val taxCategoryId: DomainId,
    public val containers: Long,
    public val returned: Long,
    public val collectorId: DomainId,
    public val sessionId: DomainId,
    public val collect: LocalCashActorEvidenceV1,
    public val collectOperationId: OperationId,
    public val collectedAt: UtcInstant,
    public val refunds: List<LocalDepositRefundV1> = emptyList(),
)

@Serializable
public enum class LocalDepositRefusalV1 {
    NOT_AVAILABLE, NO_DEPOSIT_TERMS, STALE, DUPLICATE, NOT_CUSTODIAN, SESSION_NOT_OPEN, CONTAINERS_EXCEEDED,
    INSUFFICIENT_DRAWER_CASH, CURRENCY_MISMATCH, CAPACITY_REQUIRED, NOT_AUTHORIZED, INVALID,
}

@Serializable
public sealed interface LocalDepositResultV1 {
    @Serializable @SerialName("RECORDED")
    public data class Recorded(public val deposit: LocalDepositViewV1, public val sessionRevision: Revision) : LocalDepositResultV1
    @Serializable @SerialName("REFUSED")
    public data class Refused(public val reason: LocalDepositRefusalV1) : LocalDepositResultV1
}

@Serializable
public sealed interface LocalDepositQueryV1 {
    /** One deposit per page: each entitlement can include its complete bounded refund history. */
    @Serializable @SerialName("SALE")
    public data class Sale(public val saleId: DomainId, public val afterDepositId: DomainId? = null) : LocalDepositQueryV1
    @Serializable @SerialName("DEPOSIT")
    public data class Deposit(public val depositId: DomainId) : LocalDepositQueryV1
}

@Serializable
public sealed interface LocalDepositPageV1 {
    @Serializable @SerialName("NOT_AVAILABLE")
    public data object NotAvailable : LocalDepositPageV1
    @Serializable @SerialName("SALE")
    public data class Sale(public val saleId: DomainId, public val deposits: List<LocalDepositViewV1>,
        public val nextAfter: DomainId? = null) : LocalDepositPageV1
    @Serializable @SerialName("DEPOSIT")
    public data class Deposit(public val deposit: LocalDepositViewV1) : LocalDepositPageV1
}

public object LocalDepositCodec {
    public val COMMAND: CommandKey = CommandKey("sales.deposit.write", 1)
    public val QUERY: ReadKey = ReadKey("sales.deposit.read", 1)
    public const val KIND: String = "sales.deposit"
    public const val SALE_INDEX_KIND: String = "sales.deposit-sale"
    public const val MAX_REFUNDS: Int = 64
    public const val MAX_PER_SALE: Int = 256
    public fun encode(value: LocalDepositRequestV1): StoredBytes = localSalesEncode(value, LocalDepositRequestV1.serializer(), 4_096)
    public fun decode(value: StoredBytes): LocalDepositRequestV1 = localSalesDecode(value, LocalDepositRequestV1.serializer(), 4_096)
    public fun normalize(value: StoredBytes): StoredBytes = encode(decode(value))
    public fun request(value: LocalDepositQueryV1): ReadRequest = ReadRequest(QUERY, localSalesEncode(value, LocalDepositQueryV1.serializer(), 1_024))
    public fun result(value: StoredBytes): LocalDepositResultV1 = localSalesDecode(value, LocalDepositResultV1.serializer(), 65_536)
    public fun page(value: StoredBytes): LocalDepositPageV1 = localSalesDecode(value, LocalDepositPageV1.serializer())
    internal fun result(value: LocalDepositResultV1): StoredBytes = localSalesEncode(value, LocalDepositResultV1.serializer(), 65_536)
    internal fun key(id: DomainId): AggregateKey = LocalSalesStoreProfile.key(KIND, id)
    internal fun saleKey(id: DomainId): AggregateKey = LocalSalesStoreProfile.key(SALE_INDEX_KIND, id)
    internal fun claim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("deposit", id)
    internal fun portionClaim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("deposit_portion", id)
    internal fun refundClaim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("deposit_refund", id)
}

/** Detached, already-verified original terms; no transaction or catalog object survives preparation. */
public class PreparedDepositTerms(public val terms: DepositTerms, recoveryResources: Set<RecoveryResourceKey>) {
    private val resources = recoveryResources.toSet()
    public val recoveryResources: Set<RecoveryResourceKey> get() = resources.toSet()
}

/** A completed source read found missing or untrusted original terms; never a storage/IO failure. */
public class OriginalDepositTermsUnavailable : IllegalStateException("sales.deposit.original_terms_unavailable")

public fun interface TransactionDepositTermsSource {
    /**
     * The deposit terms signed into the exact SKU publication that priced [source]'s portion of the
     * paid [sale]; null when that publication carries no deposit. Damaged or unauthenticated
     * sources throw [OriginalDepositTermsUnavailable]; today's catalog never substitutes for
     * the original publication. Storage, cancellation and custody failures must not be wrapped.
     */
    public suspend fun terms(view: CommandReadView, sale: PaidSale, source: LocalSalesPriceSourceV2): PreparedDepositTerms?
}

public object UnavailableDepositTermsSource : TransactionDepositTermsSource {
    override public suspend fun terms(view: CommandReadView, sale: PaidSale, source: LocalSalesPriceSourceV2): PreparedDepositTerms? = null
}

@Serializable
private data class DepositRecordV1(val scope: LocalSalesScope, val view: LocalDepositViewV1)

@Serializable
private data class DepositSaleIndexV1(val scope: LocalSalesScope, val saleId: DomainId, val depositIds: List<DomainId>)

private fun LocalCashActorEvidenceV1.domain(): CashierEvidence = CashierEvidence(evidenceId, actorId, occurredAt.value)
private fun CashierEvidence.local(): LocalCashActorEvidenceV1 = LocalCashActorEvidenceV1(evidenceId, actorId, UtcInstant(occurredAt))

private fun LocalDepositViewV1.terms(): DepositTerms =
    DepositTerms(skuId, publicationId, amountPerContainer, liabilityMappingId, taxCategoryId)

private fun LocalDepositViewV1.domain(scope: LocalSalesScope): DepositEntitlement = DepositEntitlement.restore(id, scope.value(),
    revision, saleId, portionId, terms(), containers, returned, collectorId, sessionId, collect.domain())

private suspend fun storedDeposit(view: CommandReadView, id: DomainId): LocalSalesRow<LocalDepositViewV1> {
    val row = LocalSalesStorage.required(view, LocalDepositCodec.key(id))
    requireLocalSalesSource(row.schema == LocalSalesStoreProfile.schema(LocalDepositCodec.KIND))
    val value = decodeLocalSalesSource(row.payload, DepositRecordV1.serializer())
    val deposit = value.view
    requireLocalSalesSource(value.scope.value() == view.scope && deposit.id == id && deposit.revision == row.domainRevision() &&
        view.claim(LocalDepositCodec.claim(id)) == deposit.saleId && view.claim(LocalDepositCodec.portionClaim(deposit.portionId)) == id &&
        deposit.refunds.size <= LocalDepositCodec.MAX_REFUNDS &&
        deposit.refunds.map { it.refundId }.distinct().size == deposit.refunds.size &&
        deposit.refunds.map { it.operationId }.distinct().size == deposit.refunds.size &&
        deposit.refunds.all { it.containers in 1..DepositEntitlement.MAX_CONTAINERS } &&
        deposit.refunds.sumOf { it.containers } == deposit.returned &&
        deposit.revision.value == deposit.refunds.size.toLong() &&
        deposit.refunds.all { it.amount.minor == deposit.amountPerContainer.minor * it.containers &&
            it.amount.currency == deposit.amountPerContainer.currency && view.claim(LocalDepositCodec.refundClaim(it.refundId)) == id })
    validateLocalSalesSource { deposit.domain(value.scope) }
    requireDepositOriginals(view, deposit)
    requireLocalSalesSource(id in (saleIndex(view, deposit.saleId)?.value?.depositIds ?: emptyList()))
    return LocalSalesRow(row, deposit)
}

private suspend fun saleIndex(view: CommandReadView, saleId: DomainId): LocalSalesRow<DepositSaleIndexV1>? {
    val row = view.aggregate(LocalDepositCodec.saleKey(saleId)) ?: return null
    requireLocalSalesSource(row.schema == LocalSalesStoreProfile.schema(LocalDepositCodec.SALE_INDEX_KIND))
    val value = decodeLocalSalesSource(row.payload, DepositSaleIndexV1.serializer())
    requireLocalSalesSource(value.scope.value() == view.scope && value.saleId == saleId &&
        value.depositIds.size in 1..LocalDepositCodec.MAX_PER_SALE && value.depositIds.distinct().size == value.depositIds.size)
    return LocalSalesRow(row, value)
}

private fun refusal(rule: SalesRule): LocalDepositRefusalV1 = when (rule) {
    SalesRule.REVISION_CONFLICT -> LocalDepositRefusalV1.STALE
    SalesRule.OWNER_REQUIRED -> LocalDepositRefusalV1.NOT_CUSTODIAN
    SalesRule.PERMISSION_DENIED -> LocalDepositRefusalV1.NOT_AUTHORIZED
    SalesRule.INSUFFICIENT_DRAWER_CASH -> LocalDepositRefusalV1.INSUFFICIENT_DRAWER_CASH
    SalesRule.RETURN_ENTITLEMENT_EXHAUSTED -> LocalDepositRefusalV1.CONTAINERS_EXCEEDED
    SalesRule.INVALID_STATE -> LocalDepositRefusalV1.SESSION_NOT_OPEN
    else -> LocalDepositRefusalV1.INVALID
}

private fun refuse(reason: LocalDepositRefusalV1): ProposedOutcome =
    ProposedOutcome.Reject("sales.deposit.refused", LocalDepositCodec.result(LocalDepositResultV1.Refused(reason)))

private inline fun domainOutcome(block: () -> ProposedOutcome): ProposedOutcome = try {
    block()
} catch (violation: SalesRuleViolation) {
    refuse(refusal(violation.rule))
}

/**
 * Master-enforced: the kernel checks the CURRENT owner-private grant (CASH_MOVE for collection on
 * the collector's own OPEN drawer, REFUND_RECORD for a container refund bound to the deposit's
 * revision); the domain rechecks custody, remaining containers and drawer cash. Client state never
 * grants authority.
 */
public class LocalDepositHandler(
    private val terms: TransactionDepositTermsSource = UnavailableDepositTermsSource,
) : MasterCommandHandler {
    override public val key: CommandKey get() = LocalDepositCodec.COMMAND
    override public val writableKinds: Set<String> = setOf(LocalDepositCodec.KIND, LocalDepositCodec.SALE_INDEX_KIND,
        CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND)
    override public val claimNamespaces: Set<String> = setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE, CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    override public fun normalize(input: StoredBytes): StoredBytes = LocalDepositCodec.normalize(input)

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(view.scope == context.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest) { "sales.context.invalid" }
        return when (val request = LocalDepositCodec.decode(normalized)) {
            is LocalDepositRequestV1.Collect -> collect(request, view, context)
            is LocalDepositRequestV1.Refund -> refund(request, view, context)
        }
    }

    private fun unavailable(context: CommandContext, action: SecurityAction, target: DomainId, revision: Revision,
        reason: LocalDepositRefusalV1 = LocalDepositRefusalV1.NOT_AVAILABLE): PreparedMutation =
        LocalSalesPrepared(context, action, target, revision, context.originalActorId, "sales.deposit.unavailable",
            resources = setOf(cashResource(target))) { _, _ -> refuse(reason) }

    private fun sourceUnavailable(context: CommandContext, request: LocalDepositRequestV1.Collect,
        cut: LocalCashCut): PreparedMutation =
        // A zero-liability requirement only admits the unavailable response. The restriction
        // prevents evaluation, persistence and every financial transition.
        LocalSalesPrepared(context, SecurityAction.CASH_MOVE, request.sessionId, request.expectedSessionRevision,
            cut.session.value.custodianId, "sales.deposit.source_unavailable", Money(cut.session.value.expectedCash.currency, 0L),
            cashResources(cut) + entitlementResource(request.saleId), setOf(RecoveryResourceKind.CASH_CUSTODY),
            admissionRestriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE) { _, _ ->
            error("sales.deposit.source_unavailable")
        }

    private suspend fun collect(request: LocalDepositRequestV1.Collect, view: CommandReadView, context: CommandContext): PreparedMutation {
        val saleKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, request.saleId)
        if (view.aggregate(saleKey) == null || !localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(request.sessionId),
                listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId))))
            return unavailable(context, SecurityAction.CASH_MOVE, request.sessionId, request.expectedSessionRevision)
        val cut = LocalSalesStorage.cash(view, request.sessionId)
        val sale = try { readReturnSaleSource(view, request.saleId) }
            catch (_: LocalSalesSourceUnavailable) { return unavailable(context, SecurityAction.CASH_MOVE, request.sessionId, request.expectedSessionRevision) }
            catch (unavailable: LocalSalesQuoteUnavailable) {
                if (unavailable.reason != LocalSalesQuoteUnavailableReason.ORIGINAL_PUBLICATION_UNAVAILABLE) throw unavailable
                return sourceUnavailable(context, request, cut)
            }
        if (sale.priceSources?.value?.normalizedStock != true)
            return unavailable(context, SecurityAction.CASH_MOVE, request.sessionId, request.expectedSessionRevision)
        val paid = sale.sale.value
        val line = paid.quote.lines.singleOrNull { it.portionId == request.portionId }
        val price = sale.priceSources?.value?.sources?.singleOrNull { it.portionId == request.portionId }
        if (line == null || price == null || line.quantity.scale != 0 || line.quantity.scaled <= 0L)
            return unavailable(context, SecurityAction.CASH_MOVE, request.sessionId, request.expectedSessionRevision)
        val prepared = try { terms.terms(view, paid, price) }
            catch (_: OriginalDepositTermsUnavailable) { return sourceUnavailable(context, request, cut) }
        if (prepared == null) {
            // Signed absence has no monetary liability. This bound proposal can only reject;
            // it cannot enter the collection path with a fabricated zero deposit amount.
            return LocalSalesPrepared(context, SecurityAction.CASH_MOVE, request.sessionId, request.expectedSessionRevision,
                cut.session.value.custodianId, "sales.deposit.no_terms", Money(cut.session.value.expectedCash.currency, 0L),
                cashResources(cut), setOf(RecoveryResourceKind.CASH_CUSTODY)) { _, _ ->
                refuse(LocalDepositRefusalV1.NO_DEPOSIT_TERMS)
            }
        }
        check(prepared.terms.skuId == price.skuId && prepared.terms.publicationId == price.companyPublicationId) { "sales.deposit.terms_source" }
        val index = saleIndex(view, request.saleId)
        val duplicate = claimed(view, listOf(LocalDepositCodec.claim(request.depositId), LocalDepositCodec.portionClaim(request.portionId))) ||
            view.aggregate(LocalDepositCodec.key(request.depositId)) != null || financialSourceAllocated(view, request.depositId)
        val source = DepositSaleSource(paid.scope, paid.id, request.portionId, line.quantity.scaled, prepared.terms)
        val amount = prepared.terms.amountPerContainer.let { per ->
            if (per.minor > Long.MAX_VALUE / request.containers) null
            else Money(per.currency, per.minor * request.containers)
        }
        val session = cut.session.value
        return LocalSalesPrepared(context, SecurityAction.CASH_MOVE, session.id, request.expectedSessionRevision,
            session.custodianId, "sales.deposit.collect", amount,
            cashResources(cut) + setOf(entitlementResource(paid.id), entitlementResource(request.depositId)) + prepared.recoveryResources,
            setOf(RecoveryResourceKind.CASH_CUSTODY)) { command, now ->
            when {
                duplicate -> refuse(LocalDepositRefusalV1.DUPLICATE)
                amount == null || request.containers > line.quantity.scaled -> refuse(LocalDepositRefusalV1.CONTAINERS_EXCEEDED)
                session.revision != request.expectedSessionRevision -> refuse(LocalDepositRefusalV1.STALE)
                session.custodianId != context.originalActorId -> refuse(LocalDepositRefusalV1.NOT_CUSTODIAN)
                session.status != CashSessionStatus.OPEN -> refuse(LocalDepositRefusalV1.SESSION_NOT_OPEN)
                session.expectedCash.currency != amount.currency -> refuse(LocalDepositRefusalV1.CURRENCY_MISMATCH)
                (index?.value?.depositIds?.size ?: 0) >= LocalDepositCodec.MAX_PER_SALE -> refuse(LocalDepositRefusalV1.CAPACITY_REQUIRED)
                else -> domainOutcome {
                    val transition = Deposits.collect(command, source, session, request.expectedSessionRevision, request.depositId,
                        request.containers, request.evidence.domain(context.originalActorId))
                    val after = transition.value
                    val movement = transition.effects.first() as? CashMovement ?: error("sales.transition.invalid")
                    check(transition.command == command && transition.effects ==
                        listOf(movement, CashSessionChanged(after.session), DepositChanged(after.deposit))) { "sales.deposit.transition" }
                    val deposit = LocalDepositViewV1(after.deposit.id, after.deposit.revision, paid.id, request.portionId,
                        prepared.terms.skuId, prepared.terms.publicationId, prepared.terms.amountPerContainer,
                        prepared.terms.liabilityMappingId, prepared.terms.taxCategoryId, after.deposit.containers, 0L,
                        after.deposit.collectorId, session.id, after.deposit.collectEvidence.local(), context.operationId, now)
                    val scope = LocalSalesScope.from(context.scope)
                    val nextIndex = DepositSaleIndexV1(scope, paid.id, index?.value?.depositIds.orEmpty() + deposit.id)
                    ProposedOutcome.Apply(LocalDepositCodec.result(LocalDepositResultV1.Recorded(deposit, after.session.revision)),
                        listOf(rootWrite(LocalDepositCodec.KIND, deposit.id, DepositRecordV1(scope, deposit), DepositRecordV1.serializer(), null),
                            rootWrite(LocalDepositCodec.SALE_INDEX_KIND, paid.id, nextIndex, DepositSaleIndexV1.serializer(), index?.stored),
                            cashWrite(after.session, cut.session.stored), movementWrite(context, movement, now)),
                        listOf(ClaimChange.Acquire(LocalDepositCodec.claim(deposit.id), paid.id),
                            ClaimChange.Acquire(LocalDepositCodec.portionClaim(request.portionId), deposit.id),
                            ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(deposit.id), session.id)))
                }
            }
        }
    }

    private suspend fun refund(request: LocalDepositRequestV1.Refund, view: CommandReadView, context: CommandContext): PreparedMutation {
        if (!localSalesSourcePresent(view, LocalDepositCodec.key(request.depositId), listOf(LocalDepositCodec.claim(request.depositId))) ||
            !localSalesSourcePresent(view, CashNonSaleStoreProfile.sessionKey(request.sessionId),
                listOf(LocalSalesStoreProfile.unique("cash_session", request.sessionId))))
            return unavailable(context, SecurityAction.REFUND_RECORD, request.depositId, request.expectedDepositRevision)
        val stored = storedDeposit(view, request.depositId)
        val deposit = stored.value
        val cut = LocalSalesStorage.cash(view, request.sessionId)
        val session = cut.session.value
        val duplicate = claimed(view, listOf(LocalDepositCodec.refundClaim(request.refundId))) || financialSourceAllocated(view, request.refundId)
        // Bind the requested cash amount even for an exhausted entitlement, then reject the
        // excess quantity below. An absent limit use would prevent that durable refusal.
        val amount = if (deposit.amountPerContainer.minor > Long.MAX_VALUE / request.containers) null
            else Money(deposit.amountPerContainer.currency, deposit.amountPerContainer.minor * request.containers)
        val scope = LocalSalesScope.from(context.scope)
        return LocalSalesPrepared(context, SecurityAction.REFUND_RECORD, deposit.id, request.expectedDepositRevision,
            context.originalActorId, "sales.deposit.refund", amount,
            cashResources(cut) + setOf(entitlementResource(deposit.saleId), entitlementResource(deposit.id), entitlementResource(request.refundId)),
            setOf(RecoveryResourceKind.CASH_CUSTODY, RecoveryResourceKind.REFUND_ENTITLEMENT)) { command, now ->
            when {
                duplicate -> refuse(LocalDepositRefusalV1.DUPLICATE)
                amount == null || request.containers > deposit.containers - deposit.returned ->
                    refuse(LocalDepositRefusalV1.CONTAINERS_EXCEEDED)
                deposit.revision != request.expectedDepositRevision || session.revision != request.expectedSessionRevision ->
                    refuse(LocalDepositRefusalV1.STALE)
                session.custodianId != context.originalActorId -> refuse(LocalDepositRefusalV1.NOT_CUSTODIAN)
                session.status != CashSessionStatus.OPEN -> refuse(LocalDepositRefusalV1.SESSION_NOT_OPEN)
                session.expectedCash.currency != amount.currency -> refuse(LocalDepositRefusalV1.CURRENCY_MISMATCH)
                deposit.refunds.size >= LocalDepositCodec.MAX_REFUNDS -> refuse(LocalDepositRefusalV1.CAPACITY_REQUIRED)
                else -> domainOutcome {
                    val evidence = request.evidence.domain(context.originalActorId)
                    val transition = Deposits.refund(command, deposit.domain(scope), request.expectedDepositRevision, session,
                        request.expectedSessionRevision, request.refundId, request.containers, evidence)
                    val after = transition.value
                    val movement = transition.effects.first() as? CashMovement ?: error("sales.transition.invalid")
                    check(transition.command == command && transition.effects ==
                        listOf(movement, CashSessionChanged(after.session), DepositChanged(after.deposit))) { "sales.deposit.transition" }
                    val next = deposit.copy(revision = after.deposit.revision, returned = after.deposit.returned,
                        refunds = deposit.refunds + LocalDepositRefundV1(request.refundId, context.operationId, session.id,
                            request.containers, amount, evidence.local(), now))
                    ProposedOutcome.Apply(LocalDepositCodec.result(LocalDepositResultV1.Recorded(next, after.session.revision)),
                        listOf(rootWrite(LocalDepositCodec.KIND, next.id, DepositRecordV1(scope, next), DepositRecordV1.serializer(), stored.stored),
                            cashWrite(after.session, cut.session.stored), movementWrite(context, movement, now)),
                        listOf(ClaimChange.Acquire(LocalDepositCodec.refundClaim(request.refundId), deposit.id),
                            ClaimChange.Acquire(CashNonSaleStoreProfile.cashSourceClaim(request.refundId), session.id)))
                }
            }
        }
    }
}

/** Read for any actor holding the current RETURN_RECEIPT_LOOKUP grant; totals only, no customer data. */
public class LocalDepositReadHandler : MasterReadHandler {
    override public val key: ReadKey get() = LocalDepositCodec.QUERY
    override public fun normalize(input: StoredBytes): StoredBytes = LocalDepositCodec.request(
        localSalesDecode(input, LocalDepositQueryV1.serializer(), 1_024)).payload

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(view.scope == context.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalDepositQueryV1.serializer(), 1_024)
        val target: DomainId
        val revision: Revision
        val page: LocalDepositPageV1
        when (query) {
            is LocalDepositQueryV1.Sale -> {
                target = query.saleId
                val index = saleIndex(view, target)
                revision = index?.stored?.revision ?: Revision(0)
                val ids = index?.value?.depositIds.orEmpty().sortedBy { it.value }
                    .filter { query.afterDepositId == null || it.value > query.afterDepositId.value }
                val deposit = ids.firstOrNull()?.let { id ->
                    storedDeposit(view, id).value.also { requireLocalSalesSource(it.saleId == target) }
                }
                page = LocalDepositPageV1.Sale(target, listOfNotNull(deposit),
                    if (ids.size > 1) deposit?.id else null)
            }
            is LocalDepositQueryV1.Deposit -> {
                target = query.depositId
                if (!localSalesSourcePresent(view, LocalDepositCodec.key(target), listOf(LocalDepositCodec.claim(target)))) {
                    revision = Revision(0)
                    page = LocalDepositPageV1.NotAvailable
                } else {
                    val deposit = storedDeposit(view, target)
                    revision = deposit.stored.revision
                    page = LocalDepositPageV1.Deposit(deposit.value)
                }
            }
        }
        return localSalesRead(context, SecurityAction.RETURN_RECEIPT_LOOKUP, target, revision,
            context.actorId, "sales.deposit.read", page, LocalDepositPageV1.serializer())
    }
}

private suspend fun requireDepositOriginals(view: CommandReadView, deposit: LocalDepositViewV1) {
    suspend fun original(operation: OperationId, expected: LocalDepositViewV1, actor: DomainId,
        matches: (LocalDepositRequestV1) -> Boolean, claims: Set<Pair<ClaimKey, DomainId>>) {
        val retained = view.retainedCommand(operation) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(retained.committed && retained.scope == view.scope && retained.command == LocalDepositCodec.COMMAND &&
            retained.operationId == operation && retained.originalActorId == actor && retained.jobs.isEmpty() &&
            semanticDigest(view.scope, actor, retained.command, retained.normalized) == retained.semanticDigest &&
            LocalDepositCodec.normalize(retained.normalized) == retained.normalized && matches(LocalDepositCodec.decode(retained.normalized)) &&
            com.ukkera.pos.security.sha256(retained.result.copyBytes()) == retained.resultHash)
        val result = LocalDepositCodec.result(retained.result) as? LocalDepositResultV1.Recorded
        requireLocalSalesSource(result?.deposit == expected)
        val write = retained.aggregateWrites.singleOrNull { it.key == LocalDepositCodec.key(deposit.id) }
        val payload = localSalesEncode(DepositRecordV1(LocalSalesScope.from(view.scope), expected), DepositRecordV1.serializer())
        requireLocalSalesSource(write != null && write.storageGeneration == expected.revision.next() &&
            write.schema == LocalSalesStoreProfile.schema(LocalDepositCodec.KIND) && write.payload == payload &&
            write.payloadDigest == com.ukkera.pos.security.sha256(payload.copyBytes()) &&
            retained.claimChanges.size == claims.size && retained.claimChanges.all {
                it.action == RetainedClaimAction.ACQUIRE && (it.key to it.ownerId) in claims
            })
    }
    val collected = deposit.copy(revision = Revision(0), returned = 0, refunds = emptyList())
    original(deposit.collectOperationId, collected, deposit.collectorId, { request ->
        request is LocalDepositRequestV1.Collect && request.depositId == deposit.id && request.saleId == deposit.saleId &&
            request.portionId == deposit.portionId && request.sessionId == deposit.sessionId && request.containers == deposit.containers &&
            request.evidence.domain(deposit.collectorId) == deposit.collect.domain()
    }, setOf(LocalDepositCodec.claim(deposit.id) to deposit.saleId,
        LocalDepositCodec.portionClaim(deposit.portionId) to deposit.id,
        CashNonSaleStoreProfile.cashSourceClaim(deposit.id) to deposit.sessionId))
    var returned = 0L
    for ((index, refund) in deposit.refunds.withIndex()) {
        returned += refund.containers
        val expected = deposit.copy(revision = Revision(index + 1L), returned = returned, refunds = deposit.refunds.take(index + 1))
        original(refund.operationId, expected, refund.evidence.actorId, { request ->
            request is LocalDepositRequestV1.Refund && request.depositId == deposit.id && request.refundId == refund.refundId &&
                request.expectedDepositRevision == Revision(index.toLong()) && request.sessionId == refund.sessionId &&
                request.containers == refund.containers && request.evidence.domain(refund.evidence.actorId) == refund.evidence.domain()
        }, setOf(LocalDepositCodec.refundClaim(refund.refundId) to deposit.id,
            CashNonSaleStoreProfile.cashSourceClaim(refund.refundId) to refund.sessionId))
    }
}
