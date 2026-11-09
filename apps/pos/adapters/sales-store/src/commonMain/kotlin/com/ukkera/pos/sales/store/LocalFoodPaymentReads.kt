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
public sealed interface LocalFoodPaymentQuery {
    @Serializable @SerialName("ATTEMPT_RECORDING")
    public data class AttemptRecording(public val orderId: DomainId, public val collectionId: DomainId,
        public val sessionId: DomainId, public val attemptId: DomainId,
        public val intentOperationId: OperationId) : LocalFoodPaymentQuery
    @Serializable @SerialName("ORIGINAL")
    public data class Original(public val recordingOperationId: OperationId) : LocalFoodPaymentQuery
    @Serializable @SerialName("RESOLUTION")
    public data class Resolution(public val resolutionOperationId: OperationId) : LocalFoodPaymentQuery
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val after: DomainId? = null, public val limit: Int = 25) : LocalFoodPaymentQuery
    @Serializable @SerialName("OWN_RESOLUTION_PENDING")
    public data class OwnResolutionPending(public val after: DomainId? = null, public val limit: Int = 25) : LocalFoodPaymentQuery
}

@Serializable
public data class LocalFoodPaymentOriginalSummary(public val target: LocalFoodPaymentTarget,
    public val originalActorId: DomainId, public val recordingActorId: DomainId, public val bodyDigest: PayloadDigest,
    public val recordedAt: UtcInstant, public val amount: Money,
    public val applicationState: LocalFoodPaymentApplicationState,
    public val pendingReason: LocalFoodPaymentPendingReason?, public val lastApplicationOperationId: OperationId?,
    public val appliedAction: LocalFoodPaymentAction?)

/** Immutable FOOD resolution linkage/accounting DATA; neither supervisor permission nor native capacity. */
@Serializable
public data class LocalFoodPaymentResolutionSummary(public val target: LocalFoodPaymentTarget,
    public val originalActorId: DomainId, public val recordingActorId: DomainId, public val bodyDigest: PayloadDigest,
    public val originalBodyDigest: PayloadDigest, public val recordedAt: UtcInstant, public val amount: Money,
    public val applicationState: LocalFoodPaymentApplicationState,
    public val pendingReason: LocalFoodPaymentPendingReason?, public val lastApplicationOperationId: OperationId?,
    public val appliedAction: LocalFoodPaymentAction?)

@Serializable
public sealed interface LocalFoodPaymentReadResult {
    @Serializable @SerialName("NOT_AVAILABLE") public data object NotAvailable : LocalFoodPaymentReadResult
    /** Display/linkage DATA only. SQL absence is not proof of no native/human action or capacity. */
    @Serializable @SerialName("ATTEMPT_RECORDING")
    public data class AttemptRecording(public val target: LocalFoodPaymentTarget, public val requirementDigest: PayloadDigest,
        public val masterAllocationId: DomainId, public val clientAllocationId: DomainId, public val clientDeviceId: DomainId,
        public val amount: Money, public val canonicalRequirementHex: String,
        public val original: LocalFoodPaymentOriginalSummary?) : LocalFoodPaymentReadResult {
        public fun requirementBytes(): StoredBytes = foodPaymentRequirementFromHex(canonicalRequirementHex).also {
            check(sha256(it.copyBytes()) == requirementDigest)
            LocalFoodPaymentPhysicalProjection.parse(it).also { projection -> check(projection.target == target && projection.amount == amount) }
        }
    }
    @Serializable @SerialName("ORIGINAL")
    public data class Original(public val summary: LocalFoodPaymentOriginalSummary,
        public val outcome: LocalFoodCashOutcome) : LocalFoodPaymentReadResult
    @Serializable @SerialName("OWN_PENDING")
    public data class OwnPending(public val originals: List<LocalFoodPaymentOriginalSummary>, public val nextAfter: DomainId?) : LocalFoodPaymentReadResult
    @Serializable @SerialName("RESOLUTION")
    public data class Resolution(public val summary: LocalFoodPaymentResolutionSummary,
        public val outcome: LocalFoodCashOutcome) : LocalFoodPaymentReadResult
    @Serializable @SerialName("OWN_RESOLUTION_PENDING")
    public data class OwnResolutionPending(public val resolutions: List<LocalFoodPaymentResolutionSummary>,
        public val nextAfter: DomainId?) : LocalFoodPaymentReadResult
    @Serializable @SerialName("CAPACITY_REQUIRED") public data object CapacityRequired : LocalFoodPaymentReadResult
}

public object LocalFoodPaymentReadCodec {
    public val QUERY: ReadKey = ReadKey("sales.food.payment.read", 1)
    public fun request(query: LocalFoodPaymentQuery): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalFoodPaymentQuery): StoredBytes {
        require(query !is LocalFoodPaymentQuery.OwnPending || query.limit in 1..25)
        require(query !is LocalFoodPaymentQuery.OwnResolutionPending || query.limit in 1..25)
        return localSalesEncode(query, LocalFoodPaymentQuery.serializer(), 2_048)
    }
    public fun decode(bytes: StoredBytes): LocalFoodPaymentReadResult = localSalesDecode(bytes, LocalFoodPaymentReadResult.serializer(), 65_536)
}

internal fun foodPaymentRequirementHex(bytes: StoredBytes): String = bytes.copyBytes().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun foodPaymentRequirementFromHex(value: String): StoredBytes {
    check(value.length in 2..16_384 && value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
    return StoredBytes(ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
}

/**
 * Own immutable FOOD originals/current accounting only. Public projections are emitted
 * exclusively after actual current scoped employee CASH_READ. Native capacity, a fresh
 * instruction, retry permission and no-human-action are never inferred from these rows.
 */
public class LocalFoodPaymentReadHandler(private val stock: LocalStockRepository,
    private val recipes: TransactionFoodStockSource) : MasterReadHandler {
    override val key: ReadKey get() = LocalFoodPaymentReadCodec.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodPaymentReadCodec.encode(
        localSalesDecode(input, LocalFoodPaymentQuery.serializer(), 2_048))
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL || normalize(normalized) != normalized) return false
        return when (localSalesDecode(normalized, LocalFoodPaymentQuery.serializer(), 2_048)) {
            is LocalFoodPaymentQuery.AttemptRecording, is LocalFoodPaymentQuery.Original, is LocalFoodPaymentQuery.OwnPending -> true
            else -> false
        }
    }

    override suspend fun prepare(normalized: StoredBytes, borrowed: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == borrowed.scope && context.key == key)
        val view = FoodReadView(commerceLoan(borrowed, stock))
        val result = try {
            when (val query = localSalesDecode(normalized, LocalFoodPaymentQuery.serializer(), 2_048)) {
                is LocalFoodPaymentQuery.AttemptRecording -> attempt(view, context, query)
                is LocalFoodPaymentQuery.Original -> {
                    val cut = ownOriginal(view, context, query.recordingOperationId)
                    if (cut == null) LocalFoodPaymentReadResult.NotAvailable
                    else LocalFoodPaymentReadResult.Original(foodPaymentOriginalSummary(cut),
                        (LocalFoodPaymentCodec.decode(cut.raw.value.body()) as LocalFoodPaymentRequest.RecordOriginal).outcome)
                }
                is LocalFoodPaymentQuery.Resolution -> {
                    val cut = ownResolution(view, context, query.resolutionOperationId)
                    if (cut == null) LocalFoodPaymentReadResult.NotAvailable
                    else LocalFoodPaymentReadResult.Resolution(foodPaymentResolutionSummary(cut),
                        (LocalFoodPaymentCodec.decode(cut.raw.value.body()) as LocalFoodPaymentRequest.RecordResolution).outcome)
                }
                is LocalFoodPaymentQuery.OwnPending -> pending(view, context, query)
                is LocalFoodPaymentQuery.OwnResolutionPending -> resolutionPending(view, context, query)
            }
        } catch (_: FoodCapacityExceeded) { LocalFoodPaymentReadResult.CapacityRequired }
          catch (failure: LocalStockRejected) {
            if (failure.failure != LocalStockFailure.BOUND_EXCEEDED) throw failure
            LocalFoodPaymentReadResult.CapacityRequired
          }
        // Stored-row corruption/codec bounds and IO remain uncertainty, not an empty page.
        return foodPaymentPreparedRead(context, result)
    }

    private suspend fun attempt(view: FoodReadView, context: ReadContext,
        query: LocalFoodPaymentQuery.AttemptRecording): LocalFoodPaymentReadResult {
        val id = view.claim(foodPaymentAttemptClaim(query.attemptId)) ?: return LocalFoodPaymentReadResult.NotAvailable
        val operation = OperationId(id.value)
        val row = LocalSalesStorage.required(view, foodPaymentSlotKey(operation), LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.SLOT))
        val header = localSalesDecode(row.payload, LocalFoodPaymentSlot.serializer(), LocalFoodPaymentProfile.MAX_SLOT_BYTES)
        // Header/selector hints are DENIAL only. Do not interpret another employee's rich
        // stock/recipe/original lineage before private ownership has matched this request.
        if (header.originalActorId != context.actorId || header.target.orderId != query.orderId ||
            header.target.collectionId != query.collectionId || header.target.sessionId != query.sessionId ||
            header.target.attemptId != query.attemptId || header.target.intentOperationId != query.intentOperationId)
            return LocalFoodPaymentReadResult.NotAvailable
        val slot = readFoodPaymentSlot(view, operation, stock)
        check(slot.value == header && slot.stored.revision == row.revision && slot.stored.digest == row.digest && slot.stored.payload == row.payload)
        val start = view.retainedCommand(slot.value.target.intentOperationId) ?: error("food.payment.read_start_missing")
        requireRecipes(start)
        val raw = ownOriginal(view, context, operation)
        val requirement = slot.value.requirement()
        // Raw/application absence may precede import while genuine native evidence exists.
        // These are bounded display/linkage bytes only; no source owner/lease is returned.
        return LocalFoodPaymentReadResult.AttemptRecording(slot.value.target, sha256(requirement.canonicalBytes.copyBytes()),
            slot.value.masterAllocationId, slot.value.clientAllocationId, slot.value.domain.clientOwner.deviceId,
            slot.value.domain.amount, foodPaymentRequirementHex(requirement.canonicalBytes), raw?.let(::foodPaymentOriginalSummary))
    }

    private suspend fun ownOriginal(view: FoodReadView, context: ReadContext, operation: OperationId): FoodPaymentRawCut? {
        val row = view.aggregate(foodPaymentRawKey(operation))
        if (row == null) {
            requireFoodPaymentAbsentFactApplication(view, operation, LocalFoodPaymentFactPhase.ORIGINAL)
            return null
        }
        val header = localSalesDecode(row.payload, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        if (header.originalActorId != context.actorId || header.recordingActorId != context.actorId) return null
        val cut = readFoodPaymentRaw(view, operation, stock)
        check(cut.raw.value == header && cut.raw.stored.revision == row.revision && cut.raw.stored.schema == row.schema &&
            cut.raw.stored.digest == row.digest && cut.raw.stored.payload == row.payload)
        requireRecipes(cut.startOriginal)
        return cut
    }

    private suspend fun pending(view: FoodReadView, context: ReadContext,
        query: LocalFoodPaymentQuery.OwnPending): LocalFoodPaymentReadResult.OwnPending {
        val page = view.aggregateKeys(LocalFoodPaymentProfile.APPLICATION, query.after, query.limit)
        check(page.keys.size <= query.limit && page.keys.map { it.id }.distinct().size == page.keys.size &&
            page.keys.all { it.kind == LocalFoodPaymentProfile.APPLICATION && (query.after == null || it.id.value > query.after.value) } &&
            page.keys == page.keys.sortedBy { it.id.value } && (page.nextAfter == null || page.keys.lastOrNull()?.id == page.nextAfter))
        val summaries = mutableListOf<LocalFoodPaymentOriginalSummary>()
        for (key in page.keys) {
            val row = LocalSalesStorage.required(view, key, LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION))
            val header = localSalesDecode(row.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
            check(header.version == 1 && header.scope == context.scope && header.target.factOperation(header.phase).value == key.id.value &&
                header.revision.next() == row.revision)
            // A foreign header can only deny disclosure; its ownership hint never grants it.
            if (header.originalActorId != context.actorId || header.phase != LocalFoodPaymentFactPhase.ORIGINAL ||
                header.state != LocalFoodPaymentApplicationState.PENDING_RECONCILIATION) continue
            val cut = ownOriginal(view, context, header.target.recordingOperationId) ?: error("food.payment.pending_original_missing")
            check(cut.application.value == header && cut.application.stored.key == row.key && cut.application.stored.schema == row.schema &&
                cut.application.stored.revision == row.revision && cut.application.stored.digest == row.digest && cut.application.stored.payload == row.payload)
            summaries += foodPaymentOriginalSummary(cut)
        }
        return LocalFoodPaymentReadResult.OwnPending(summaries, page.nextAfter)
    }

    private suspend fun ownResolution(view: FoodReadView, context: ReadContext,
        operation: OperationId): FoodPaymentResolutionCut? {
        val row = view.aggregate(foodPaymentResolutionKey(operation))
        if (row == null) {
            requireFoodPaymentAbsentFactApplication(view, operation, LocalFoodPaymentFactPhase.RESOLUTION)
            return null
        }
        val header = localSalesDecode(row.payload, LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        if (header.originalActorId != context.actorId || header.recordingActorId != context.actorId) return null
        val cut = readFoodPaymentResolution(view, operation, stock)
        check(cut.raw.value == header && cut.raw.stored.revision == row.revision && cut.raw.stored.schema == row.schema &&
            cut.raw.stored.digest == row.digest && cut.raw.stored.payload == row.payload)
        requireRecipes(cut.original.startOriginal)
        return cut
    }

    private suspend fun resolutionPending(view: FoodReadView, context: ReadContext,
        query: LocalFoodPaymentQuery.OwnResolutionPending): LocalFoodPaymentReadResult.OwnResolutionPending {
        val page = view.aggregateKeys(LocalFoodPaymentProfile.APPLICATION, query.after, query.limit)
        check(page.keys.size <= query.limit && page.keys.map { it.id }.distinct().size == page.keys.size &&
            page.keys.all { it.kind == LocalFoodPaymentProfile.APPLICATION && (query.after == null || it.id.value > query.after.value) } &&
            page.keys == page.keys.sortedBy { it.id.value } && (page.nextAfter == null || page.keys.lastOrNull()?.id == page.nextAfter))
        val summaries = mutableListOf<LocalFoodPaymentResolutionSummary>()
        for (key in page.keys) {
            val row = LocalSalesStorage.required(view, key, LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION))
            val header = localSalesDecode(row.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
            check(header.version == 1 && header.scope == context.scope &&
                header.target.factOperation(header.phase).value == key.id.value && header.revision.next() == row.revision)
            if (header.originalActorId != context.actorId || header.phase != LocalFoodPaymentFactPhase.RESOLUTION ||
                header.state != LocalFoodPaymentApplicationState.PENDING_RECONCILIATION) continue
            val cut = ownResolution(view, context, header.target.resolutionOperationId) ?: continue
            check(cut.application.value == header && cut.application.stored.key == row.key && cut.application.stored.schema == row.schema &&
                cut.application.stored.revision == row.revision && cut.application.stored.digest == row.digest && cut.application.stored.payload == row.payload)
            summaries += foodPaymentResolutionSummary(cut)
        }
        return LocalFoodPaymentReadResult.OwnResolutionPending(summaries, page.nextAfter)
    }

    private suspend fun requireRecipes(start: RetainedCommandEvidence) {
        LocalFoodPaymentProducer.requireStart(start).cut.portions.forEach {
            recipes.requireLegacyRecipe((it.body as FoodBodyV1.Portion).recipe, start.scope)
        }
    }
}

internal fun foodPaymentOriginalSummary(cut: FoodPaymentRawCut): LocalFoodPaymentOriginalSummary = with(cut.raw.value) {
    LocalFoodPaymentOriginalSummary(target, originalActorId, recordingActorId, bodyDigest, recordedAt, cut.slot.value.domain.amount,
        cut.application.value.state, cut.application.value.reason, cut.application.value.lastApplicationOperationId, cut.application.value.action)
}

internal fun foodPaymentResolutionSummary(cut: FoodPaymentResolutionCut): LocalFoodPaymentResolutionSummary = with(cut.raw.value) {
    LocalFoodPaymentResolutionSummary(target, originalActorId, recordingActorId, bodyDigest, originalBodyDigest, recordedAt,
        cut.original.slot.value.domain.amount, cut.application.value.state, cut.application.value.reason,
        cut.application.value.lastApplicationOperationId, cut.application.value.action)
}

private fun foodPaymentPreparedRead(context: ReadContext, result: LocalFoodPaymentReadResult): PreparedRead = object : PreparedRead() {
    private val exact = AuthorizationRequirement(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), context.scope,
        ActionBinding(context.actorId, Revision(0), context.semanticDigest, null, "food.payment.read"), owner = OwnerConstraint.OwnerPrivate(context.actorId))
    override val requirements: List<AuthorizationRequirement> = listOf(exact)
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorizations.single().isBoundTo(exact, context.actorId))
        return try { localSalesEncode(result, LocalFoodPaymentReadResult.serializer(), 65_536) }
        catch (_: LocalSalesWireCapacityExceeded) { localSalesEncode(LocalFoodPaymentReadResult.CapacityRequired,
            LocalFoodPaymentReadResult.serializer(), 65_536) }
    }
}

/** Wrong-phase selectors can deny; a matching-phase app without its immutable raw is corrupt. */
private suspend fun requireFoodPaymentAbsentFactApplication(view: CommandReadView, operationId: OperationId,
    phase: LocalFoodPaymentFactPhase) {
    val row = view.aggregate(foodPaymentApplicationKey(operationId)) ?: return
    val header = localSalesDecode(row.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
    check(row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION) && header.scope == view.scope &&
        header.target.factOperation(header.phase) == operationId && header.phase != phase)
}
