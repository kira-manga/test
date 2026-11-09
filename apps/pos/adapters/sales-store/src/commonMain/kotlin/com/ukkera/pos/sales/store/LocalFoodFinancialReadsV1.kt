package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockHydrationCapacityUnavailable
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable public sealed interface LocalFoodFinancialQueryV1 {
    @Serializable @SerialName("COLLECTION")
    public data class Collection(public val collectionId: DomainId) : LocalFoodFinancialQueryV1
    @Serializable @SerialName("PAID_RECEIPT")
    public data class Receipt(public val saleId: DomainId) : LocalFoodFinancialQueryV1
    @Serializable @SerialName("REFUND_OBLIGATION")
    public data class Refund(public val refundId: DomainId) : LocalFoodFinancialQueryV1
    @Serializable @SerialName("QUALITY_CLAIM")
    public data class Claim(public val claimId: DomainId) : LocalFoodFinancialQueryV1
    @Serializable @SerialName("OWN_QUALITY_CLAIMS")
    public data class OwnClaims(public val after: DomainId? = null, public val limit: Int = 8) : LocalFoodFinancialQueryV1
}

/** Thin whole-check responsibility projection: no recipe, stock cost or station-delivery claim. */
@Serializable public data class LocalFoodFinancialPortionWorkV1(public val portionId: DomainId,
    public val originalQuantity: Quantity, public val preparationDomainRevision: Revision,
    public val preparationStatus: FoodPreparationStatus, public val fulfillment: FoodFulfillmentStatus,
    public val stockOrigin: FoodStockOrigin, public val reservationStatus: String,
    public val chargeDecisionRequired: Boolean)

/** These identities support reconciliation; neither an intent nor a view permits physical money. */
@Serializable public data class LocalFoodPaymentAttemptViewV1(public val attemptId: DomainId, public val status: String,
    public val tender: LocalTenderViewV1, public val amount: Money, public val intentOperationId: OperationId,
    public val appliedPaymentObservationId: OperationId?)

@Serializable public data class LocalFoodCollectionViewV1(public val collectionId: DomainId, public val orderId: DomainId,
    public val collectionStorageRevision: Revision, public val collectionDomainRevision: Revision,
    public val orderStorageRevision: Revision, public val lockedCommercialStorageRevision: Revision,
    public val status: String, public val originalPayable: Money, public val collected: Money, public val unpaidRemaining: Money,
    public val saleId: DomainId?, public val cancellationRefundId: DomainId?, public val workClosed: Boolean,
    public val portions: List<LocalFoodFinancialPortionWorkV1>, public val attempts: List<LocalFoodPaymentAttemptViewV1>,
    public val refundLiabilityDue: Money, public val unresolvedClaimIds: List<DomainId>,
    public val executionProfile: String = "NONPHYSICAL_ONLY")

@Serializable public data class LocalFoodReceiptLineV1(public val portionId: DomainId, public val batchId: DomainId,
    public val originalQuantity: Quantity, public val remainingOriginalQuantity: Quantity,
    public val originalValue: Money, public val remainingOriginalValue: Money)
@Serializable public data class LocalFoodReceiptComponentV1(public val batchId: DomainId, public val originalKey: String,
    public val portionId: DomainId, public val kind: String, public val originalPosted: Money)
@Serializable public data class LocalFoodTenderShareV1(public val originalPaymentId: DomainId, public val amount: Money)
@Serializable public data class LocalFoodComponentTendersV1(public val componentKey: String,
    public val shares: List<LocalFoodTenderShareV1>)

/**
 * Same-branch original FOOD document and unconsumed original allocations only. No old owner,
 * cashier/custody/payer identities, current stock, receipt delivery, legal deadline, policy
 * eligibility or fiscal acceptance is inferred. Remaining value is NOT an approved payout.
 */
@Serializable public data class LocalFoodPaidReceiptViewV1(public val saleId: DomainId, public val saleStorageRevision: Revision,
    public val entitlementStorageRevision: Revision, public val entitlementDomainRevision: Revision,
    public val finalizedAt: UtcInstant, public val originalPayable: Money, public val lines: List<LocalFoodReceiptLineV1>,
    public val components: List<LocalFoodReceiptComponentV1>, public val originalTenders: List<LocalRefundObligationViewV1>,
    public val remainingComponentTenders: List<LocalFoodComponentTendersV1>,
    public val remedyQualification: String = "SEPARATE_SIGNED_POLICY_AND_RECEIPT_QUALIFICATION_REQUIRED")

@Serializable public data class LocalFoodRefundAttemptViewV1(public val attemptId: DomainId, public val originalPaymentId: DomainId,
    public val status: String, public val tender: LocalTenderViewV1, public val amount: Money,
    public val ownAttempt: Boolean, public val intentOperationId: OperationId?, public val appliedRefundObservationId: OperationId?)
@Serializable public data class LocalFoodRefundViewV1(public val refundId: DomainId, public val collectionId: DomainId,
    public val saleId: DomainId?, public val refundStorageRevision: Revision, public val refundDomainRevision: Revision,
    public val originalQuotedTotal: Money, public val actualTenderLiability: Money, public val refunded: Money, public val due: Money,
    public val status: String, public val obligations: List<LocalRefundObligationViewV1>,
    public val attempts: List<LocalFoodRefundAttemptViewV1>)
@Serializable public data class LocalFoodQualityClaimViewV1(public val claimId: DomainId, public val storageRevision: Revision,
    public val recordedAt: UtcInstant, public val facts: LocalFoodClaimFactsV1,
    public val disposition: String = "ASSERTED_UNRESOLVED_NOT_RECEIPT_OR_REMEDY_EVIDENCE")

@Serializable public enum class LocalFoodFinancialUnavailableV1 { TARGET_UNAVAILABLE, CAPACITY_REQUIRED }
@Serializable public sealed interface LocalFoodFinancialReadResultV1 {
    /** A failed complete observation is never returned as an empty index or successful partial page. */
    @Serializable @SerialName("UNAVAILABLE") public data class Unavailable(public val reason: LocalFoodFinancialUnavailableV1) : LocalFoodFinancialReadResultV1
    @Serializable @SerialName("COLLECTION") public data class Collection(public val value: LocalFoodCollectionViewV1) : LocalFoodFinancialReadResultV1
    @Serializable @SerialName("PAID_RECEIPT") public data class Receipt(public val value: LocalFoodPaidReceiptViewV1) : LocalFoodFinancialReadResultV1
    @Serializable @SerialName("REFUND_OBLIGATION") public data class Refund(public val value: LocalFoodRefundViewV1) : LocalFoodFinancialReadResultV1
    @Serializable @SerialName("QUALITY_CLAIMS") public data class Claims(public val claims: List<LocalFoodQualityClaimViewV1>,
        public val nextAfter: DomainId?) : LocalFoodFinancialReadResultV1
}

public object LocalFoodFinancialReadCodecV1 {
    public fun encode(query: LocalFoodFinancialQueryV1): StoredBytes {
        if (query is LocalFoodFinancialQueryV1.OwnClaims) require(query.limit in 1..8)
        return localSalesEncode(query, LocalFoodFinancialQueryV1.serializer(), 2_048)
    }
    public fun read(query: LocalFoodFinancialQueryV1): ReadRequest = ReadRequest(LocalFoodFinancialProfileV1.QUERY, encode(query))
    public fun result(bytes: StoredBytes): LocalFoodFinancialReadResultV1 =
        localSalesDecode(bytes, LocalFoodFinancialReadResultV1.serializer(), 65_536)
}

public class LocalFoodFinancialReadHandler(private val recipes: TransactionFoodRecipeSourceV1,
    private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalFoodFinancialProfileV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodFinancialReadCodecV1.encode(
        localSalesDecode(input, LocalFoodFinancialQueryV1.serializer(), 2_048))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL || normalize(normalized) != normalized) return false
        return when (localSalesDecode(normalized, LocalFoodFinancialQueryV1.serializer(), 2_048)) {
            is LocalFoodFinancialQueryV1.Collection, is LocalFoodFinancialQueryV1.Receipt -> true
            else -> false
        }
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(view.scope == context.scope && context.key == key)
        val bounded = FoodReadView(commerceLoan(view, stock))
        return when (val query = localSalesDecode(normalized, LocalFoodFinancialQueryV1.serializer(), 2_048)) {
            is LocalFoodFinancialQueryV1.Collection -> collection(bounded, context, query.collectionId)
            is LocalFoodFinancialQueryV1.Receipt -> receipt(bounded, context, query.saleId)
            is LocalFoodFinancialQueryV1.Refund -> refund(bounded, context, query.refundId)
            is LocalFoodFinancialQueryV1.Claim -> claim(bounded, context, query.claimId)
            is LocalFoodFinancialQueryV1.OwnClaims -> claims(bounded, context, query)
        }
    }

    private suspend fun collection(view: FoodReadView, context: ReadContext, id: DomainId): PreparedRead {
        val header = foodFinancialObserve(view, LocalFoodFinancialProfileV1.COLLECTION, id)?.second
        val owner = header?.let(::foodFinancialCollection)?.currentOwnerId ?: context.actorId
        val requirement = foodFinancialReadRequirement(context, SecurityAction.ORDER_READ, id, header?.revision ?: Revision(0), owner)
        if (header == null || owner != context.actorId) return foodFinancialPreparedRead(context, listOf(requirement), foodFinancialMissing())
        return foodFinancialBoundedRead(context, listOf(requirement)) {
            val cut = foodFinancialCollectionCut(view, id, recipes, includeClosed = true)
            val genuineCashProfile = cut.row.normalizedProducer != null || isFoodPaymentFinancialProducer(cut.row.producer)
            val reservationStatuses = if (genuineCashProfile) {
                foodFinancialRelationalStock(view, stock, cut.portions, cut.value).reservations.mapValues { it.value.status.name }
            } else foodFinancialLegacyStock(view, stock, cut.portions, cut.value).reservations.mapValues { it.value.status.name }
            cut.value.saleId?.let { saleId ->
                val paid = foodFinancialRequired(view, LocalFoodFinancialProfileV1.SALE, saleId)
                val rights = foodFinancialRequired(view, LocalFoodFinancialProfileV1.ENTITLEMENT, saleId)
                requireFoodPaidSource(cut.value, foodFinancialSale(paid.envelope), foodFinancialEntitlement(rights.envelope))
            }
            val index = foodFinancialRead(view, LocalFoodFinancialProfileV1.REFUND_INDEX, cut.value.orderId)
            val refunds = (index?.envelope?.body as? FoodFinancialBodyV1.RefundIndex)?.refundIds.orEmpty().map {
                foodFinancialRefund(foodFinancialRequired(view, LocalFoodFinancialProfileV1.REFUND, it).envelope).also { r ->
                    requireFoodRefundSource(cut.value, r)
                }
            }
            check(refunds.map { it.id } == listOfNotNull(cut.value.cancellationRefundId))
            val claims = foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX,
                foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, cut.value.orderId))
            LocalFoodFinancialReadResultV1.Collection(LocalFoodCollectionViewV1(id, cut.value.orderId, cut.row.row.revision,
                cut.value.revision, cut.order.order.row.revision, cut.value.lockedCommercialRevision, cut.value.status.name,
                cut.value.quote.payable, cut.value.collected, cut.value.remaining, cut.value.saleId, cut.value.cancellationRefundId,
                cut.order.value.closedByOperationId != null, cut.portions.map { row ->
                    val p = row.envelope.body as FoodBodyV1.Portion
                    val link = cut.value.portions.single { it.portionId == row.envelope.id }
                    LocalFoodFinancialPortionWorkV1(row.envelope.id, p.selection.quantity, p.preparation.revision,
                        p.preparation.status, p.preparation.fulfillment, link.stockOrigin,
                        reservationStatuses.getValue(link.reservationId),
                        p.preparation.cancellationReasonId != null && cut.value.cancellationRefundId == null)
                }, cut.value.attempts.map { LocalFoodPaymentAttemptViewV1(it.id, it.status.name, tenderView(it.tender), it.amount,
                    it.originalOperationId, it.payment?.recordingOperationId) },
                refunds.fold(Money(Currency.EGP, 0)) { sum, refund -> sum + refund.due },
                (claims?.envelope?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty(),
                executionProfile = if (genuineCashProfile) "GENUINE_FOOD_CASH_V1" else "NONPHYSICAL_ONLY"))
        }
    }

    private suspend fun receipt(view: FoodReadView, context: ReadContext, id: DomainId): PreparedRead {
        val header = foodFinancialObserve(view, LocalFoodFinancialProfileV1.ENTITLEMENT, id)?.second
        val requirement = foodFinancialReadRequirement(context, SecurityAction.RETURN_RECEIPT_LOOKUP, id, header?.revision ?: Revision(0))
        if (header == null) return foodFinancialPreparedRead(context, listOf(requirement), foodFinancialMissing())
        return foodFinancialBoundedRead(context, listOf(requirement)) {
            val paid = foodFinancialRequired(view, LocalFoodFinancialProfileV1.SALE, id)
            val rights = foodFinancialRequired(view, LocalFoodFinancialProfileV1.ENTITLEMENT, id)
            val sale = foodFinancialSale(paid.envelope); val entitlement = foodFinancialEntitlement(rights.envelope)
            // Historic quote/recipe provenance, NOT current price, current stock or an eligibility policy.
            val original = foodFinancialCollectionCut(view, sale.collection.id, recipes, includeClosed = true)
            requireFoodPaidSource(original.value, sale, entitlement)
            LocalFoodFinancialReadResultV1.Receipt(LocalFoodPaidReceiptViewV1(id, paid.row.revision, rights.row.revision,
                entitlement.revision, sale.finalizedAt, sale.quote.payable, entitlement.cursors.map { cursor ->
                    LocalFoodReceiptLineV1(cursor.portionId, sale.quote.batchFor(cursor.portionId).id, cursor.originalQuantity,
                        cursor.remainingQuantity, cursor.originalComponents.fold(Money(Currency.EGP, 0)) { sum, c -> sum + c.amount },
                        cursor.remainingPayable)
                }, sale.quote.batches.flatMap { batch -> batch.quote.components.map { c ->
                    LocalFoodReceiptComponentV1(batch.id, c.key, checkNotNull(c.portionId), c.kind.name, c.posted)
                } }, sale.payments.map { LocalRefundObligationViewV1(it.attemptId, tenderView(it.tender), it.amount) },
                entitlement.remainingTenders.map { LocalFoodComponentTendersV1(it.componentKey,
                    it.shares.map { share -> LocalFoodTenderShareV1(share.paymentId, share.amount) }) }))
        }
    }

    private suspend fun refund(view: FoodReadView, context: ReadContext, id: DomainId): PreparedRead {
        val header = foodFinancialObserve(view, LocalFoodFinancialProfileV1.REFUND, id)?.second
        val owner = header?.let(::foodFinancialRefund)?.acceptingActorId ?: context.actorId
        val requirement = foodFinancialReadRequirement(context, SecurityAction.RETURN_RECEIPT_LOOKUP, id, header?.revision ?: Revision(0), owner)
        if (header == null || owner != context.actorId) return foodFinancialPreparedRead(context, listOf(requirement), foodFinancialMissing())
        return foodFinancialBoundedRead(context, listOf(requirement)) {
            val row = foodFinancialRequired(view, LocalFoodFinancialProfileV1.REFUND, id)
            val value = foodFinancialRefund(row.envelope)
            val collection = foodFinancialRequired(view, LocalFoodFinancialProfileV1.COLLECTION, value.originalCollection.id)
            requireFoodRefundSource(foodFinancialCollection(collection.envelope), value)
            val index = foodFinancialRequired(view, LocalFoodFinancialProfileV1.REFUND_INDEX, value.orderId)
            check((index.envelope.body as FoodFinancialBodyV1.RefundIndex).refundIds == listOf(id))
            LocalFoodFinancialReadResultV1.Refund(LocalFoodRefundViewV1(id, value.originalCollection.id, value.saleId,
                row.row.revision, value.revision, value.originalQuotedTotal, value.amount, value.refunded, value.due, value.status.name,
                value.obligations.map { LocalRefundObligationViewV1(it.originalPaymentId, tenderView(it.tender), it.amount) },
                value.attempts.map { attempt ->
                    val own = attempt.originalActorId == context.actorId
                    LocalFoodRefundAttemptViewV1(attempt.id, attempt.originalPaymentId, attempt.status.name, tenderView(attempt.tender),
                        attempt.amount, own, attempt.originalOperationId.takeIf { own }, attempt.refund?.recordingOperationId?.takeIf { own })
                }))
        }
    }

    private suspend fun claim(view: FoodReadView, context: ReadContext, id: DomainId): PreparedRead {
        val header = foodFinancialObserve(view, LocalFoodFinancialProfileV1.CLAIM, id)?.second
        val owner = (header?.body as? FoodFinancialBodyV1.Claim)?.responsibleOwnerId ?: context.actorId
        val requirement = foodFinancialReadRequirement(context, SecurityAction.RETURN_CLAIM, id, header?.revision ?: Revision(0), owner)
        if (header == null || owner != context.actorId) return foodFinancialPreparedRead(context, listOf(requirement), foodFinancialMissing())
        return foodFinancialBoundedRead(context, listOf(requirement)) {
            LocalFoodFinancialReadResultV1.Claims(listOf(foodClaimView(view, id, context.actorId)), null)
        }
    }

    private suspend fun claims(view: FoodReadView, context: ReadContext, query: LocalFoodFinancialQueryV1.OwnClaims): PreparedRead {
        val id = foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, context.actorId)
        val header = foodFinancialObserve(view, LocalFoodFinancialProfileV1.CLAIM_INDEX, id)?.second
        val ids = (header?.body as? FoodFinancialBodyV1.ClaimIndex)?.claimIds.orEmpty().sortedBy { it.value }
            .filter { query.after == null || it.value > query.after.value }
        val selected = ids.take(query.limit)
        // At most eight bounded claim headers derive EVERY exact action before full provenance work.
        val headers = selected.map { foodFinancialObserve(view, LocalFoodFinancialProfileV1.CLAIM, it)?.second ?: error("food.claim_missing") }
        check(headers.all { (it.body as FoodFinancialBodyV1.Claim).responsibleOwnerId == context.actorId })
        val requirements = listOf(foodFinancialReadRequirement(context, SecurityAction.RETURN_CLAIM, id,
            header?.revision ?: Revision(0), context.actorId)) + headers.map {
            foodFinancialReadRequirement(context, SecurityAction.RETURN_CLAIM, it.id, it.revision, context.actorId)
        }
        return foodFinancialBoundedRead(context, requirements) {
            foodFinancialRead(view, LocalFoodFinancialProfileV1.CLAIM_INDEX, id)
            LocalFoodFinancialReadResultV1.Claims(selected.map { foodClaimView(view, it, context.actorId) },
                if (selected.size < ids.size) selected.last() else null)
        }
    }
}

private suspend fun foodFinancialRequired(view: CommandReadView, kind: String, id: DomainId): FoodFinancialRow =
    foodFinancialRead(view, kind, id) ?: error("food.financial.source_missing")
private suspend fun foodClaimView(view: CommandReadView, id: DomainId, ownerId: DomainId): LocalFoodQualityClaimViewV1 {
    val row = foodFinancialRequired(view, LocalFoodFinancialProfileV1.CLAIM, id)
    val value = row.envelope.body as FoodFinancialBodyV1.Claim
    check(value.responsibleOwnerId == ownerId && value.linkedOrderId == null)
    val owner = foodFinancialRequired(view, LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, ownerId))
    check(id in (owner.envelope.body as FoodFinancialBodyV1.ClaimIndex).claimIds)
    value.facts.assertedOrderId?.let {
        val order = foodFinancialRequired(view, LocalFoodFinancialProfileV1.CLAIM_INDEX, foodClaimIndexId(FoodClaimIndexTargetV1.ORDER, it))
        check(id in (order.envelope.body as FoodFinancialBodyV1.ClaimIndex).claimIds)
    }
    return LocalFoodQualityClaimViewV1(id, row.row.revision, value.recordedAt, value.facts)
}
private fun foodFinancialMissing(): LocalFoodFinancialReadResultV1 =
    LocalFoodFinancialReadResultV1.Unavailable(LocalFoodFinancialUnavailableV1.TARGET_UNAVAILABLE)
private fun foodFinancialReadRequirement(context: ReadContext, action: SecurityAction, id: DomainId,
    revision: Revision, ownerId: DomainId? = null): AuthorizationRequirement = AuthorizationRequirement(
    Permission(action, PermissionVerb.PERFORM), context.scope, ActionBinding(id, revision, context.semanticDigest, null, "food.financial.read"),
    owner = ownerId?.let { OwnerConstraint.OwnerPrivate(it) } ?: OwnerConstraint.Unrestricted)

private suspend fun foodFinancialBoundedRead(context: ReadContext, requirements: List<AuthorizationRequirement>,
    read: suspend () -> LocalFoodFinancialReadResultV1): PreparedRead {
    val result = try { read() }
    catch (_: FoodCapacityExceeded) { LocalFoodFinancialReadResultV1.Unavailable(LocalFoodFinancialUnavailableV1.CAPACITY_REQUIRED) }
    catch (_: LocalStockHydrationCapacityUnavailable) { LocalFoodFinancialReadResultV1.Unavailable(LocalFoodFinancialUnavailableV1.CAPACITY_REQUIRED) }
    // Stored-row/codec BOUND failures, arithmetic and IO are not benign capacity or absence.
    return foodFinancialPreparedRead(context, requirements, result)
}
private fun foodFinancialPreparedRead(context: ReadContext, exactRequirements: List<AuthorizationRequirement>,
    result: LocalFoodFinancialReadResultV1): PreparedRead = object : PreparedRead() {
    override val requirements: List<AuthorizationRequirement> = exactRequirements.toList()
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == requirements.size && authorizations.indices.all {
            authorizations[it].principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorizations[it].isBoundTo(requirements[it], context.actorId)
        })
        return try { localSalesEncode(result, LocalFoodFinancialReadResultV1.serializer(), 65_536) }
        catch (_: LocalSalesWireCapacityExceeded) { localSalesEncode(LocalFoodFinancialReadResultV1.Unavailable(
            LocalFoodFinancialUnavailableV1.CAPACITY_REQUIRED), LocalFoodFinancialReadResultV1.serializer(), 65_536) }
    }
}
