package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalFoodRefundQuery {
    @Serializable @SerialName("OBLIGATION")
    public data class Obligation(public val refundId: DomainId) : LocalFoodRefundQuery
    @Serializable @SerialName("ATTEMPT_RECORDING")
    public data class AttemptRecording(public val orderId: DomainId, public val collectionId: DomainId, public val refundId: DomainId,
        public val sessionId: DomainId, public val attemptId: DomainId,
        public val intentOperationId: OperationId) : LocalFoodRefundQuery
    @Serializable @SerialName("ORIGINAL")
    public data class Original(public val recordingOperationId: OperationId) : LocalFoodRefundQuery
    @Serializable @SerialName("RESOLUTION")
    public data class Resolution(public val resolutionOperationId: OperationId) : LocalFoodRefundQuery
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val after: DomainId? = null, public val limit: Int = 25) : LocalFoodRefundQuery
    @Serializable @SerialName("OWN_RESOLUTION_PENDING")
    public data class OwnResolutionPending(public val after: DomainId? = null, public val limit: Int = 25) : LocalFoodRefundQuery
}

@Serializable
public data class LocalFoodRefundOriginalSummary(public val target: LocalFoodRefundTarget,
    public val originalActorId: DomainId, public val recordingActorId: DomainId, public val bodyDigest: PayloadDigest,
    public val recordedAt: UtcInstant, public val amount: Money,
    public val applicationState: LocalFoodRefundApplicationState,
    public val pendingReason: LocalFoodRefundPendingReason?, public val lastApplicationOperationId: OperationId?,
    public val appliedAction: LocalFoodRefundAction?)

/** Immutable FOOD resolution linkage/accounting DATA; neither supervisor permission nor native capacity. */
@Serializable
public data class LocalFoodRefundResolutionSummary(public val target: LocalFoodRefundTarget,
    public val originalActorId: DomainId, public val recordingActorId: DomainId, public val bodyDigest: PayloadDigest,
    public val originalBodyDigest: PayloadDigest, public val recordedAt: UtcInstant, public val amount: Money,
    public val applicationState: LocalFoodRefundApplicationState,
    public val pendingReason: LocalFoodRefundPendingReason?, public val lastApplicationOperationId: OperationId?,
    public val appliedAction: LocalFoodRefundAction?)

/** Own immutable slot selectors, read from the actual attempt claim; never reconstructed IDs. */
@Serializable
public data class LocalFoodRefundRecoverySummary(public val target: LocalFoodRefundTarget,
    public val amount: Money, public val status: String)

@Serializable
public sealed interface LocalFoodRefundReadResult {
    @Serializable @SerialName("OBLIGATION")
    public data class Obligation(public val value: LocalFoodRefundViewV1, public val orderId: DomainId,
        public val ownRecoveries: List<LocalFoodRefundRecoverySummary>) : LocalFoodRefundReadResult
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalFoodRefundReadResult
    /** Display/linkage DATA only. SQL absence is not proof of no native/human action or capacity. */
    @Serializable @SerialName("ATTEMPT_RECORDING")
    public data class AttemptRecording(public val target: LocalFoodRefundTarget, public val requirementDigest: PayloadDigest,
        public val masterAllocationId: DomainId, public val clientAllocationId: DomainId, public val clientDeviceId: DomainId,
        public val amount: Money, public val canonicalRequirementHex: String,
        public val original: LocalFoodRefundOriginalSummary?) : LocalFoodRefundReadResult {
        public fun requirementBytes(): StoredBytes = foodRefundRequirementFromHex(canonicalRequirementHex).also {
            check(sha256(it.copyBytes()) == requirementDigest)
            LocalFoodRefundPhysicalProjection.parse(it).also { projection -> check(projection.target == target && projection.amount == amount) }
        }
    }
    @Serializable @SerialName("ORIGINAL")
    public data class Original(public val summary: LocalFoodRefundOriginalSummary,
        public val outcome: LocalFoodRefundOutcome) : LocalFoodRefundReadResult
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val originals: List<LocalFoodRefundOriginalSummary>, public val nextAfter: DomainId?) : LocalFoodRefundReadResult
    @Serializable @SerialName("RESOLUTION")
    public data class Resolution(public val summary: LocalFoodRefundResolutionSummary,
        public val outcome: LocalFoodRefundOutcome) : LocalFoodRefundReadResult
    @Serializable @SerialName("OWN_RESOLUTION_PENDING")
    public data class OwnResolutionPending(public val resolutions: List<LocalFoodRefundResolutionSummary>,
        public val nextAfter: DomainId?) : LocalFoodRefundReadResult
    @Serializable @SerialName("CAPACITY_REQUIRED") public data object CapacityRequired : LocalFoodRefundReadResult
}

public object LocalFoodRefundReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.food.refund.read", 1)
    public fun request(query: LocalFoodRefundQuery): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalFoodRefundQuery): StoredBytes {
        require(query !is LocalFoodRefundQuery.OwnPending || query.limit in 1..25)
        require(query !is LocalFoodRefundQuery.OwnResolutionPending || query.limit in 1..25)
        return localSalesEncode(query, LocalFoodRefundQuery.serializer(), 2_048)
    }
    public fun decode(bytes: StoredBytes): LocalFoodRefundReadResult = localSalesDecode(bytes, LocalFoodRefundReadResult.serializer(), 65_536)
}

internal fun foodRefundRequirementHex(bytes: StoredBytes): String = bytes.copyBytes().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun foodRefundRequirementFromHex(value: String): StoredBytes {
    check(value.length in 2..16_384 && value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
    return StoredBytes(ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
}

/**
 * Own immutable FOOD originals/current accounting only. Public projections are emitted
 * exclusively after actual current scoped employee CASH_READ. Native capacity, a fresh
 * instruction, retry permission and no-human-action are never inferred from these rows.
 */
public class LocalFoodRefundReadHandler(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey get() = LocalFoodRefundReadCodec.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodRefundReadCodec.encode(
        localSalesDecode(input, LocalFoodRefundQuery.serializer(), 2_048))

    override suspend fun prepare(normalized: StoredBytes, borrowed: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == borrowed.scope && context.key == key)
        val view = FoodReadView(commerceLoan(borrowed, stock))
        val query = localSalesDecode(normalized, LocalFoodRefundQuery.serializer(), 2_048)
        if (query is LocalFoodRefundQuery.Obligation) return obligation(view, context, query.refundId)
        val result = try {
            when (query) {
                is LocalFoodRefundQuery.Obligation -> error("food.refund.obligation_selected_separately")
                is LocalFoodRefundQuery.AttemptRecording -> attempt(view, context, query)
                is LocalFoodRefundQuery.Original -> {
                    val cut = ownOriginal(view, context, query.recordingOperationId)
                    if (cut == null) LocalFoodRefundReadResult.NotAvailable
                    else LocalFoodRefundReadResult.Original(foodRefundOriginalSummary(cut),
                        (LocalFoodRefundCodec.decode(cut.raw.value.body()) as LocalFoodRefundRequest.RecordOriginal).outcome)
                }
                is LocalFoodRefundQuery.Resolution -> {
                    val cut = ownResolution(view, context, query.resolutionOperationId)
                    if (cut == null) LocalFoodRefundReadResult.NotAvailable
                    else LocalFoodRefundReadResult.Resolution(foodRefundResolutionSummary(cut),
                        (LocalFoodRefundCodec.decode(cut.raw.value.body()) as LocalFoodRefundRequest.RecordResolution).outcome)
                }
                is LocalFoodRefundQuery.OwnPending -> pending(view, context, query)
                is LocalFoodRefundQuery.OwnResolutionPending -> resolutionPending(view, context, query)
            }
        } catch (_: FoodCapacityExceeded) { LocalFoodRefundReadResult.CapacityRequired }
          catch (failure: LocalStockRejected) {
            if (failure.failure != LocalStockFailure.BOUND_EXCEEDED) throw failure
            LocalFoodRefundReadResult.CapacityRequired
          }
        // Stored-row corruption/codec bounds and IO remain uncertainty, not an empty page.
        return foodRefundPreparedRead(context, result)
    }

    /** Genuine accepted liability, not limited to the former collection/accepting actor. */
    private suspend fun obligation(view: FoodReadView, context: ReadContext, id: DomainId): PreparedRead {
        val header = foodFinancialObserve(view, LocalFoodFinancialProfileV1.REFUND, id)?.second?.let(::foodFinancialRefund)
        val exact = AuthorizationRequirement(Permission(if (header == null) SecurityAction.RETURN_RECEIPT_LOOKUP else SecurityAction.REFUND_RECORD,
            PermissionVerb.PERFORM), context.scope, ActionBinding(id, header?.revision ?: Revision(0), context.semanticDigest,
                null, "food.refund.obligation.read"),
            limitUse = header?.due?.let { LimitUse.Monetary(it) } ?: LimitUse.NotApplicable,
            owner = if (header == null) OwnerConstraint.Unrestricted else OwnerConstraint.OwnerPrivate(context.actorId))
        val result = if (header == null) LocalFoodRefundReadResult.NotAvailable else try {
            val cut = foodRefundCut(view, id)
            val value = cut.value
            check(value.revision == header.revision && value.amount == header.amount && value.due == header.due)
            val recoveries = mutableListOf<LocalFoodRefundRecoverySummary>()
            for (attempt in value.attempts.filter { it.originalActorId == context.actorId }) {
                // Old scalar attempts without this lane's immutable claim are not guessed into
                // native slots. They remain visible, but do not offer a replacement Start.
                val claimed = view.claim(foodRefundAttemptClaim(attempt.id)) ?: continue
                val slot = readFoodRefundSlot(view, OperationId(claimed.value), stock).value
                val target = slot.target
                check(slot.originalActorId == context.actorId && target.orderId == value.orderId &&
                    target.collectionId == value.originalCollection.id && target.refundId == value.id &&
                    target.saleId == value.saleId && target.originalPaymentId == attempt.originalPaymentId &&
                    target.sessionId == attempt.originalCashSessionId && target.attemptId == attempt.id &&
                    target.intentOperationId == attempt.originalOperationId && slot.domain.amount == attempt.amount)
                recoveries += LocalFoodRefundRecoverySummary(target, attempt.amount, attempt.status.name)
            }
            LocalFoodRefundReadResult.Obligation(LocalFoodRefundViewV1(id, value.originalCollection.id, value.saleId,
                cut.refund.row.revision, value.revision, value.originalQuotedTotal, value.amount, value.refunded, value.due, value.status.name,
                value.obligations.map { LocalRefundObligationViewV1(it.originalPaymentId, tenderView(it.tender), it.amount) },
                value.attempts.map { attempt ->
                    val own = attempt.originalActorId == context.actorId
                    LocalFoodRefundAttemptViewV1(attempt.id, attempt.originalPaymentId, attempt.status.name, tenderView(attempt.tender),
                        attempt.amount, own, attempt.originalOperationId.takeIf { own }, attempt.refund?.recordingOperationId?.takeIf { own })
                }), value.orderId, recoveries)
        } catch (_: FoodCapacityExceeded) { LocalFoodRefundReadResult.CapacityRequired }
          catch (failure: LocalStockRejected) {
            if (failure.failure != LocalStockFailure.BOUND_EXCEEDED) throw failure
            LocalFoodRefundReadResult.CapacityRequired
          }
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(exact)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(exact, context.actorId))
                return try { localSalesEncode(result, LocalFoodRefundReadResult.serializer(), 65_536) }
                catch (_: LocalSalesWireCapacityExceeded) { localSalesEncode(LocalFoodRefundReadResult.CapacityRequired,
                    LocalFoodRefundReadResult.serializer(), 65_536) }
            }
        }
    }

    private suspend fun attempt(view: FoodReadView, context: ReadContext,
        query: LocalFoodRefundQuery.AttemptRecording): LocalFoodRefundReadResult {
        val id = view.claim(foodRefundAttemptClaim(query.attemptId)) ?: return LocalFoodRefundReadResult.NotAvailable
        val operation = OperationId(id.value)
        val row = LocalSalesStorage.required(view, foodRefundSlotKey(operation), LocalFoodRefundProfile.schema(LocalFoodRefundProfile.SLOT))
        val header = localSalesDecode(row.payload, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES)
        // Header/selector hints are DENIAL only. Do not interpret another employee's rich
        // stock/recipe/original lineage before private ownership has matched this request.
        if (header.originalActorId != context.actorId || header.target.orderId != query.orderId ||
            header.target.collectionId != query.collectionId || header.target.refundId != query.refundId ||
            header.target.sessionId != query.sessionId ||
            header.target.attemptId != query.attemptId || header.target.intentOperationId != query.intentOperationId)
            return LocalFoodRefundReadResult.NotAvailable
        val slot = readFoodRefundSlot(view, operation, stock)
        check(slot.value == header && slot.stored.revision == row.revision && slot.stored.digest == row.digest && slot.stored.payload == row.payload)
        val start = view.retainedCommand(slot.value.target.intentOperationId) ?: error("food.refund.read_start_missing")
        val raw = ownOriginal(view, context, operation)
        val requirement = slot.value.requirement()
        // Raw/application absence may precede import while genuine native evidence exists.
        // These are bounded display/linkage bytes only; no source owner/lease is returned.
        return LocalFoodRefundReadResult.AttemptRecording(slot.value.target, sha256(requirement.canonicalBytes.copyBytes()),
            slot.value.masterAllocationId, slot.value.clientAllocationId, slot.value.domain.clientOwner.deviceId,
            slot.value.domain.amount, foodRefundRequirementHex(requirement.canonicalBytes), raw?.let(::foodRefundOriginalSummary))
    }

    private suspend fun ownOriginal(view: FoodReadView, context: ReadContext, operation: OperationId): FoodRefundRawCut? {
        val row = view.aggregate(foodRefundRawKey(operation))
        if (row == null) {
            requireFoodRefundAbsentFactApplication(view, operation, LocalFoodRefundFactPhase.ORIGINAL)
            return null
        }
        val header = localSalesDecode(row.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        if (header.originalActorId != context.actorId || header.recordingActorId != context.actorId) return null
        val cut = readFoodRefundRaw(view, operation, stock)
        check(cut.raw.value == header && cut.raw.stored.revision == row.revision && cut.raw.stored.schema == row.schema &&
            cut.raw.stored.digest == row.digest && cut.raw.stored.payload == row.payload)
        return cut
    }

    private suspend fun pending(view: FoodReadView, context: ReadContext,
        query: LocalFoodRefundQuery.OwnPending): LocalFoodRefundReadResult.OwnPending {
        val page = view.aggregateKeys(LocalFoodRefundProfile.APPLICATION, query.after, query.limit)
        check(page.keys.size <= query.limit && page.keys.map { it.id }.distinct().size == page.keys.size &&
            page.keys.all { it.kind == LocalFoodRefundProfile.APPLICATION && (query.after == null || it.id.value > query.after.value) } &&
            page.keys == page.keys.sortedBy { it.id.value } && (page.nextAfter == null || page.keys.lastOrNull()?.id == page.nextAfter))
        val summaries = mutableListOf<LocalFoodRefundOriginalSummary>()
        for (key in page.keys) {
            val row = LocalSalesStorage.required(view, key, LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
            val header = localSalesDecode(row.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
            check(header.version == 1 && header.scope == context.scope && header.target.factOperation(header.phase).value == key.id.value &&
                header.revision.next() == row.revision)
            // A foreign header can only deny disclosure; its ownership hint never grants it.
            if (header.originalActorId != context.actorId || header.phase != LocalFoodRefundFactPhase.ORIGINAL ||
                header.state != LocalFoodRefundApplicationState.PENDING_RECONCILIATION) continue
            val cut = ownOriginal(view, context, header.target.recordingOperationId) ?: error("food.refund.pending_original_missing")
            check(cut.application.value == header && cut.application.stored.key == row.key && cut.application.stored.schema == row.schema &&
                cut.application.stored.revision == row.revision && cut.application.stored.digest == row.digest && cut.application.stored.payload == row.payload)
            summaries += foodRefundOriginalSummary(cut)
        }
        return LocalFoodRefundReadResult.OwnPending(summaries, page.nextAfter)
    }

    private suspend fun ownResolution(view: FoodReadView, context: ReadContext,
        operation: OperationId): FoodRefundResolutionCut? {
        val row = view.aggregate(foodRefundResolutionKey(operation))
        if (row == null) {
            requireFoodRefundAbsentFactApplication(view, operation, LocalFoodRefundFactPhase.RESOLUTION)
            return null
        }
        val header = localSalesDecode(row.payload, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        if (header.originalActorId != context.actorId || header.recordingActorId != context.actorId) return null
        val cut = readFoodRefundResolution(view, operation, stock)
        check(cut.raw.value == header && cut.raw.stored.revision == row.revision && cut.raw.stored.schema == row.schema &&
            cut.raw.stored.digest == row.digest && cut.raw.stored.payload == row.payload)
        return cut
    }

    private suspend fun resolutionPending(view: FoodReadView, context: ReadContext,
        query: LocalFoodRefundQuery.OwnResolutionPending): LocalFoodRefundReadResult.OwnResolutionPending {
        val page = view.aggregateKeys(LocalFoodRefundProfile.APPLICATION, query.after, query.limit)
        check(page.keys.size <= query.limit && page.keys.map { it.id }.distinct().size == page.keys.size &&
            page.keys.all { it.kind == LocalFoodRefundProfile.APPLICATION && (query.after == null || it.id.value > query.after.value) } &&
            page.keys == page.keys.sortedBy { it.id.value } && (page.nextAfter == null || page.keys.lastOrNull()?.id == page.nextAfter))
        val summaries = mutableListOf<LocalFoodRefundResolutionSummary>()
        for (key in page.keys) {
            val row = LocalSalesStorage.required(view, key, LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
            val header = localSalesDecode(row.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
            check(header.version == 1 && header.scope == context.scope &&
                header.target.factOperation(header.phase).value == key.id.value && header.revision.next() == row.revision)
            if (header.originalActorId != context.actorId || header.phase != LocalFoodRefundFactPhase.RESOLUTION ||
                header.state != LocalFoodRefundApplicationState.PENDING_RECONCILIATION) continue
            val cut = ownResolution(view, context, header.target.resolutionOperationId) ?: continue
            check(cut.application.value == header && cut.application.stored.key == row.key && cut.application.stored.schema == row.schema &&
                cut.application.stored.revision == row.revision && cut.application.stored.digest == row.digest && cut.application.stored.payload == row.payload)
            summaries += foodRefundResolutionSummary(cut)
        }
        return LocalFoodRefundReadResult.OwnResolutionPending(summaries, page.nextAfter)
    }


}

internal fun foodRefundOriginalSummary(cut: FoodRefundRawCut): LocalFoodRefundOriginalSummary = with(cut.raw.value) {
    LocalFoodRefundOriginalSummary(target, originalActorId, recordingActorId, bodyDigest, recordedAt, cut.slot.value.domain.amount,
        cut.application.value.state, cut.application.value.reason, cut.application.value.lastApplicationOperationId, cut.application.value.action)
}

internal fun foodRefundResolutionSummary(cut: FoodRefundResolutionCut): LocalFoodRefundResolutionSummary = with(cut.raw.value) {
    LocalFoodRefundResolutionSummary(target, originalActorId, recordingActorId, bodyDigest, originalBodyDigest, recordedAt,
        cut.original.slot.value.domain.amount, cut.application.value.state, cut.application.value.reason,
        cut.application.value.lastApplicationOperationId, cut.application.value.action)
}

private fun foodRefundPreparedRead(context: ReadContext, result: LocalFoodRefundReadResult): PreparedRead = object : PreparedRead() {
    private val exact = AuthorizationRequirement(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), context.scope,
        ActionBinding(context.actorId, Revision(0), context.semanticDigest, null, "food.refund.read"), owner = OwnerConstraint.OwnerPrivate(context.actorId))
    override val requirements: List<AuthorizationRequirement> = listOf(exact)
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorizations.single().isBoundTo(exact, context.actorId))
        return try { localSalesEncode(result, LocalFoodRefundReadResult.serializer(), 65_536) }
        catch (_: LocalSalesWireCapacityExceeded) { localSalesEncode(LocalFoodRefundReadResult.CapacityRequired,
            LocalFoodRefundReadResult.serializer(), 65_536) }
    }
}

/** Wrong-phase selectors can deny; a matching-phase app without its immutable raw is corrupt. */
private suspend fun requireFoodRefundAbsentFactApplication(view: CommandReadView, operationId: OperationId,
    phase: LocalFoodRefundFactPhase) {
    val row = view.aggregate(foodRefundApplicationKey(operationId)) ?: return
    val header = localSalesDecode(row.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
    check(row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION) && header.scope == view.scope &&
        header.target.factOperation(header.phase) == operationId && header.phase != phase)
}
