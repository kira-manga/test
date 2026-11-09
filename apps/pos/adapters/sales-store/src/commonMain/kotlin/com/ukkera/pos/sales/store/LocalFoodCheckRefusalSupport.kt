package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockFailure
import com.ukkera.pos.inventory.store.LocalStockHydrationCapacityUnavailable
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Installed typed activity proof over the actual native BEFORE cut. Null is unavailable;
 * no historical CommandReadView, restored employee or accepting activity default exists. */
public interface LocalFoodCheckHistoricalActivitySource {
    public suspend fun before(command: RuntimeHistoricalCommand, activity: LocalSalesActivity): PreparedFoodActivityAdmission?
    public suspend fun before(sources: RuntimeHistoricalFoodCheckRejectedSources,
        activity: LocalSalesActivity): PreparedFoodActivityAdmission?
}

/** Shared closed owner of two source-proven FOOD refusals. The live path still runs under
 * current Master authorization and the genuine sink/writer loan. The historical path derives
 * the same scalar first refusal using native-selected sources, never a reconstructed view.
 * Missing installation disables both recording and joining; recipes, capacity and every
 * other FOOD request/refusal remain outside this component. */
public class LocalFoodCheckRefusalSupport(private val historicalActivity: LocalFoodCheckHistoricalActivitySource) {
    internal suspend fun prepare(request: LocalFoodStockRequestV2, view: CommandReadView, context: CommandContext,
        activity: PreparedFoodActivityAdmission?): LocalFoodCheckPreparedRefusal? {
        if (activity?.sourceEnabled != true) return null
        check(context.scope == view.scope && context.key == StockStreamingCommandsV2.FOOD)
        return when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> {
                val order = foodRead(view, LocalFoodProfileV1.ORDER, request.request.orderId) ?: return null
                if (!supportedLiveSource(order)) return null
                LocalFoodCheckPreparedRefusal(context, request, order.row, null,
                    salesAuthorizationRequirement(SecurityAction.ORDER_CREATE, context.scope, request.request.orderId,
                        Revision(0), context.operationId, context.semanticDigest, "food.operation", context.originalActorId),
                    activity.recoveryResources, DRAFT_DUPLICATE)
            }
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> {
                // Missing/closed targets retain their original authorized proposal ordering.
                // A stale revision does not bypass foodOrder's owner-index/closure check.
                val observed = foodObserveRow(view, LocalFoodProfileV1.ORDER, request.request.orderId) ?: return null
                val body = observed.envelope.body as? FoodBodyV1.Order ?: return null
                if (body.closedByOperationId != null || observed.row.revision == request.request.expectedOrderRevision) return null
                val order = foodOrder(view, request.request.orderId)
                if (!supportedLiveSource(order.order) || !supportedLiveSource(order.ownerIndex)) return null
                LocalFoodCheckPreparedRefusal(context, request, order.order.row, order.ownerIndex.row,
                    salesAuthorizationRequirement(SecurityAction.ORDER_EDIT, context.scope, request.request.orderId,
                        request.request.expectedOrderRevision, context.operationId, context.semanticDigest,
                        "food.operation", order.value.ownerId), activity.recoveryResources, ADD_REVISION_CONFLICT)
            }
            else -> null
        }
    }

    /** Original disclosure requirements only. Neither current ORDER ownership nor the request
     * actor supplies a missing Add BEFORE owner. Literal native retention and the installed
     * whole FOOD/stock/catalog producer checks are independent, mandatory prerequisites. */
    public suspend fun retainedRequirements(normalized: StoredBytes, view: CommandReadView,
        context: CommandContext): List<AuthorizationRequirement>? {
        if (view.scope != context.scope || context.key != StockStreamingCommandsV2.FOOD ||
            LocalFoodStockCodecV2.normalize(normalized) != normalized ||
            semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest)
            return null
        val request = LocalFoodStockCodecV2.decode(normalized)
        if (request !is LocalFoodStockRequestV2.ActivityProtected &&
            request !is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd) return null
        val bounded = foodReadView(view)
        return try {
            val rejected = bounded.foodCheckRejectedOriginalSources(context.operationId)
            val requirement = if (rejected != null) retainedRejectedRequirement(rejected, request, normalized, bounded, context)
                else retainedCommittedRequirement(request, normalized, bounded, context)
            requirement?.let { listOf(it) }
        } catch (_: FoodCapacityExceeded) {
            null
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            null
        } catch (_: LocalSalesWireCapacityExceeded) {
            null
        } catch (failure: LocalStockRejected) {
            if (failure.failure == LocalStockFailure.PRODUCER_UNAVAILABLE ||
                failure.failure == LocalStockFailure.BOUND_EXCEEDED) null else throw failure
        }
    }

    private suspend fun retainedCommittedRequirement(request: LocalFoodStockRequestV2, normalized: StoredBytes,
        view: FoodReadView, context: CommandContext): AuthorizationRequirement? {
        // Older legacy rejections without the closed native marker remain inert. This route
        // accepts only an actual retained successful stream, never a reconstructed proposal.
        val retained = (view.stockRelationalAccess().originals.read(context.operationId) as? RetainedOriginalV2.Streamed)
            ?.evidence ?: return null
        val identity = retained.identity
        if (identity.scope != context.scope || identity.producer.operationId != context.operationId ||
            identity.producer.command != context.key || identity.producer.originalActorId != context.originalActorId ||
            identity.producer.semanticDigest != context.semanticDigest || retained.normalized != normalized) return null
        // This completes the repository's entire mixed predecessor traversal, including the
        // installed FOOD/commerce/catalog verifier, not only stock or literal stream framing.
        val completed = view.foodStockProducer(context.operationId)
        requireSameFoodCheckStream(retained, completed)
        return when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> salesAuthorizationRequirement(SecurityAction.ORDER_CREATE,
                context.scope, request.request.orderId, Revision(0), context.operationId, context.semanticDigest,
                "food.operation", identity.producer.originalActorId)
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> {
                val sources = LocalCommerceOriginalSources(completed)
                val key = LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, request.request.orderId)
                val before = committedBeforeFood(sources, key) ?: return null
                val order = before.second.body as? FoodBodyV1.Order ?: return null
                val index = committedBeforeFood(sources, LocalFoodProfileV1.key(LocalFoodProfileV1.OWNER_INDEX, order.ownerId))
                    ?: return null
                val members = (index.second.body as? FoodBodyV1.OwnerIndex)?.orderIds ?: return null
                check(order.closedByOperationId == null && key.id in members &&
                    before.first.generation == request.request.expectedOrderRevision)
                salesAuthorizationRequirement(SecurityAction.ORDER_EDIT, context.scope, key.id,
                    request.request.expectedOrderRevision, context.operationId, context.semanticDigest,
                    "food.operation", order.ownerId)
            }
            else -> null
        }
    }

    /** Used only AFTER the whole root and all its producer dependencies have completed. The
     * existing literal-source helper requires a genuine Aggregate observation, not Current. */
    private suspend fun committedBeforeFood(sources: LocalCommerceOriginalSources, key: AggregateKey):
        Pair<LocalCommerceOriginalRow, FoodEnvelopeV1>? {
        val row = sources.aggregateOriginal(key)
        val claim = sources.claim(foodSourceClaim(key))
        if (row == null) { check(claim == null); return null }
        check(claim == key.id && (foodStreamedSchema(key.kind, row.schema) || row.schema == LocalFoodProfileV1.schema(key.kind)) &&
            sha256(row.payload.copyBytes()) == row.digest)
        val value = foodStoredEnvelope(row.schema, row.payload)
        check(value.scope == sources.scope && value.kind == key.kind && value.id == key.id && value.revision == row.generation)
        sources.aggregateOriginal(key, value.operationId)
        return row to value
    }

    private suspend fun retainedRejectedRequirement(sources: RuntimeHistoricalFoodCheckRejectedSources,
        request: LocalFoodStockRequestV2, normalized: StoredBytes, view: FoodReadView,
        context: CommandContext): AuthorizationRequirement? {
        val retained = sources.evidence
        if (retained.scope != context.scope || retained.operationId != context.operationId ||
            retained.originalActorId != context.originalActorId || retained.command != context.key ||
            retained.semanticDigest != context.semanticDigest || retained.normalized != normalized ||
            retained.committed || retained.nativeOriginalProvenance != null) return null
        val orderId = when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> request.request.orderId
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> request.request.orderId
            else -> return null
        }
        val code = when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> DRAFT_DUPLICATE
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> ADD_REVISION_CONFLICT
            else -> return null
        }
        if (retained.code != code) return null
        check(retained.result == scalarFoodCheckRefusalResult(code) && sha256(retained.result.copyBytes()) == retained.resultHash &&
            retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty())
        val key = LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, orderId)
        if (sources.orderKey != key || sources.beforeOrderState != RuntimeHistoricalFoodCheckBeforeOrderState.PRESENT ||
            sources.beforeOrderClaimOwnerId != key.id) return null
        val nativeOrder = sources.beforeOrder ?: return null
        check(sources.beforeAggregateSources.singleOrNull { it.row.key == key } === nativeOrder)
        val order = rejectedBeforeFood(sources, key, view) ?: return null
        val body = order.second.body as? FoodBodyV1.Order ?: return null
        val activity: LocalSalesActivity
        val requirement: AuthorizationRequirement
        when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> {
                activity = request.request.activity
                // The duplicate target may belong to somebody else. Creation's private
                // audience remains the original initiating actor, not that target's owner.
                requirement = salesAuthorizationRequirement(SecurityAction.ORDER_CREATE, context.scope, key.id,
                    Revision(0), context.operationId, context.semanticDigest, "food.operation", retained.originalActorId)
            }
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> {
                if (body.closedByOperationId != null) return null
                val index = rejectedBeforeFood(sources,
                    LocalFoodProfileV1.key(LocalFoodProfileV1.OWNER_INDEX, body.ownerId), view) ?: return null
                val members = (index.second.body as? FoodBodyV1.OwnerIndex)?.orderIds ?: return null
                check(key.id in members) { "food.check.retained_owner_index_disagrees" }
                if (order.first.revision == request.request.expectedOrderRevision) return null
                activity = body.activity
                // The native reader selected the latest real BEFORE row independently of
                // this requested revision; conflict precedes actor/handoff checks.
                requirement = salesAuthorizationRequirement(SecurityAction.ORDER_EDIT, context.scope, key.id,
                    request.request.expectedOrderRevision, context.operationId, context.semanticDigest, "food.operation", body.ownerId)
            }
            else -> return null
        }
        // Native source retention charges literal rows/claims. Charge the complete legacy
        // activation originals through the existing request-wide same-loan read boundary too.
        if (!chargeRejectedLegacyOriginals(sources, view)) return null
        val enabled = historicalActivity.before(sources, activity) ?: return null
        if (!enabled.sourceEnabled || sources.requirement != requirement) return null
        return requirement
    }

    /** Native row and claim-acquisition pairings deliberately remain UNQUALIFIED until each
     * installed whole FOOD producer and its exact AFTER/ACQUIRE have completed through EOF. */
    private suspend fun rejectedBeforeFood(sources: RuntimeHistoricalFoodCheckRejectedSources, key: AggregateKey,
        view: FoodReadView): Pair<StoredAggregate, FoodEnvelopeV1>? {
        val literal = sources.beforeAggregateSources.singleOrNull { it.row.key == key } ?: return null
        val claim = sources.beforeClaimSources.singleOrNull { it.key == foodSourceClaim(key) } ?: return null
        if (!foodStreamedSchema(key.kind, literal.row.schema)) return null
        check(claim.ownerId == key.id)
        val original = (literal.original as? RetainedOriginalV2.Streamed)?.evidence ?: return null
        val acquisition = (claim.original as? RetainedOriginalV2.Streamed)?.evidence ?: return null
        if (original.identity.producer.command != StockStreamingCommandsV2.FOOD ||
            acquisition.identity.producer.command != StockStreamingCommandsV2.FOOD) return null
        requireEarlierFoodCheckStream(original, sources.evidence)
        requireEarlierFoodCheckStream(acquisition, sources.evidence)
        val completed = view.foodStockProducer(original.identity.producer.operationId)
        requireSameFoodCheckStream(original, completed)
        requireSameFoodCheckRow(literal.row, completed.writeRow(key) ?: error("food.check.retained_source_write_missing"))
        val completedAcquisition = view.foodStockProducer(acquisition.identity.producer.operationId)
        requireSameFoodCheckStream(acquisition, completedAcquisition)
        var acquisitions = 0
        completedAcquisition.frames { frame ->
            val change = (frame as? RetainedCommandFrameV2.Claim)?.value
            if (change?.key == claim.key) {
                check(change.action == RetainedClaimAction.ACQUIRE && change.ownerId == claim.ownerId)
                acquisitions++
            }
        }
        check(acquisitions == 1) { "food.check.retained_source_claim_missing" }
        val row = literal.row
        check(sha256(row.payload.copyBytes()) == row.digest)
        val value = foodStoredEnvelope(row.schema, row.payload)
        check(value.scope == sources.evidence.scope && value.kind == key.kind && value.id == key.id &&
            value.revision == row.revision && value.operationId == completed.identity.producer.operationId)
        return row to value
    }

    private suspend fun chargeRejectedLegacyOriginals(sources: RuntimeHistoricalFoodCheckRejectedSources,
        view: FoodReadView): Boolean {
        val charged = mutableSetOf<OperationId>()
        suspend fun charge(original: RetainedOriginalV2): Boolean {
            val evidence = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return true
            if (!charged.add(evidence.operationId)) return true
            val actual = view.retainedCommand(evidence.operationId) ?: return false
            requireSameFoodCheckLegacyOriginal(evidence, actual)
            return true
        }
        for (literal in sources.beforeAggregateSources) if (!charge(literal.original)) return false
        for (literal in sources.beforeClaimSources) if (!charge(literal.original)) return false
        return true
    }

    /** Original legacy reject, despite FOOD being a streaming command. This branch closes
     * before the successful-original Streamed producer and never invokes the stock engine. */
    public suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command != StockStreamingCommandsV2.FOOD || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND)
            return false
        val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (retained.committed || retained.nativeOriginalProvenance != null || command.originalApproval != null) return false
        check(LocalFoodStockCodecV2.normalize(command.normalized) == command.normalized)
        val request = LocalFoodStockCodecV2.decode(command.normalized)
        val key: AggregateKey
        val activity: LocalSalesActivity
        val owner: DomainId
        val requirement: AuthorizationRequirement
        val code: String
        when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> {
                key = LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, request.request.orderId)
                if (retained.code != DRAFT_DUPLICATE) return false
                historicalSource(command, key) ?: return false
                owner = command.actorId
                activity = request.request.activity
                requirement = salesAuthorizationRequirement(SecurityAction.ORDER_CREATE, command.scope, key.id,
                    Revision(0), command.operationId, command.semanticDigest, "food.operation", owner)
                code = DRAFT_DUPLICATE
            }
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> {
                key = LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, request.request.orderId)
                if (retained.code != ADD_REVISION_CONFLICT) return false
                val order = historicalSource(command, key) ?: return false
                val body = order.second.body as? FoodBodyV1.Order ?: return false
                if (body.closedByOperationId != null) return false
                val index = historicalSource(command, LocalFoodProfileV1.key(LocalFoodProfileV1.OWNER_INDEX, body.ownerId))
                    ?: return false
                val membership = (index.second.body as? FoodBodyV1.OwnerIndex)?.orderIds ?: return false
                check(key.id in membership) { "food.check.history_owner_index_disagrees" }
                if (order.first.row.revision == request.request.expectedOrderRevision) return false
                owner = body.ownerId
                activity = body.activity
                // Revision conflict precedes owner/handoff checks. No actor-equality
                // prerequisite is invented, and the captured binding uses REQUESTED revision.
                requirement = salesAuthorizationRequirement(SecurityAction.ORDER_EDIT, command.scope, key.id,
                    request.request.expectedOrderRevision, command.operationId, command.semanticDigest, "food.operation", owner)
                code = ADD_REVISION_CONFLICT
            }
            else -> return false
        }
        val enabled = historicalActivity.before(command, activity) ?: return false
        if (!enabled.sourceEnabled) return false
        command.requireRequirement(requirement)
        val resources = foodCheckPreparedResources(key.id, owner, command.actorId, enabled.recoveryResources)
        command.requireFoodCheckRejection(ProposedOutcome.Reject(code, scalarFoodCheckRefusalResult(code)), resources)
        return true
    }

    private suspend fun historicalSource(command: RuntimeHistoricalCommand, key: AggregateKey):
        Pair<RuntimeHistoricalAggregateSource, FoodEnvelopeV1>? {
        val source = command.aggregateSource(key)
        val claim = command.claim(foodSourceClaim(key))
        if (source == null) { check(claim == null); return null }
        check(claim == key.id && source.row.key == key && sha256(source.row.payload.copyBytes()) == source.row.digest)
        if (!foodStreamedSchema(key.kind, source.row.schema)) return null
        val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: return null
        if (original.identity.producer.command != StockStreamingCommandsV2.FOOD) return null
        check(command.earlierOriginal(original.identity.producer.operationId) === source.original &&
            original.identity.scope == command.scope && original.identity.producer.operationId != command.operationId)
        val value = foodStoredEnvelope(source.row.schema, source.row.payload)
        check(value.scope == command.scope && value.kind == key.kind && value.id == key.id &&
            value.revision == source.row.revision && value.operationId == original.identity.producer.operationId)
        if (key.kind == LocalFoodProfileV1.ORDER && value.body !is FoodBodyV1.Order) return null
        return source to value
    }

    private fun supportedLiveSource(row: FoodRow): Boolean =
        foodStreamedSchema(row.row.key.kind, row.row.schema) &&
            row.normalizedProducer?.identity?.producer?.command == StockStreamingCommandsV2.FOOD
}

/** One inert prepared source comparison. Recording support becomes true only after these
 * same rows/claims and enabled activity were revalidated on the actual authorized sink loan. */
internal class LocalFoodCheckPreparedRefusal(
    private val context: CommandContext,
    private val request: LocalFoodStockRequestV2,
    private val order: StoredAggregate,
    private val ownerIndex: StoredAggregate?,
    private val requirement: AuthorizationRequirement,
    private val activityResources: Set<RecoveryResourceKey>,
    private val code: String,
) {
    private val result = scalarFoodCheckRefusalResult(code)
    private val resources = foodCheckPreparedResources(order.key.id,
        checkNotNull((requirement.owner as? com.ukkera.pos.security.OwnerConstraint.OwnerPrivate)?.ownerId),
        context.originalActorId, activityResources)
    private var emitted = false

    suspend fun decide(view: CommandReadView, selectedContext: CommandContext,
        activity: PreparedFoodActivityAdmission): StreamedMutationDecision.Reject {
        check(!emitted && selectedContext == context && view.scope == context.scope &&
            activity.sourceEnabled && activity.recoveryResources == activityResources)
        when (request) {
            is LocalFoodStockRequestV2.ActivityProtected -> {
                val actual = checkNotNull(foodRead(view, LocalFoodProfileV1.ORDER, order.key.id))
                requireSameFoodCheckRow(order, actual.row)
                check(actual.normalizedProducer?.identity?.producer?.command == StockStreamingCommandsV2.FOOD &&
                    ownerIndex == null && code == DRAFT_DUPLICATE)
            }
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> {
                val actual = foodOrder(view, order.key.id)
                requireSameFoodCheckRow(order, actual.order.row)
                requireSameFoodCheckRow(checkNotNull(ownerIndex), actual.ownerIndex.row)
                check(actual.order.normalizedProducer?.identity?.producer?.command == StockStreamingCommandsV2.FOOD &&
                    actual.ownerIndex.normalizedProducer?.identity?.producer?.command == StockStreamingCommandsV2.FOOD &&
                    actual.order.row.revision != request.request.expectedOrderRevision && code == ADD_REVISION_CONFLICT)
            }
            else -> error("food.check.refusal_request_unavailable")
        }
        emitted = true
        return StreamedMutationDecision.Reject(code, result)
    }

    fun supports(decision: StreamedMutationDecision.Reject, actualRequirement: AuthorizationRequirement,
        actualResources: Set<RecoveryResourceKey>): Boolean = emitted && decision.code == code &&
        decision.result == result && actualRequirement == requirement && actualResources == resources
}

private const val DRAFT_DUPLICATE: String = "food.stock.rule.duplicate_source_allocation"
private const val ADD_REVISION_CONFLICT: String = "food.stock.rule.revision_conflict"
private fun scalarFoodCheckRefusalResult(code: String): StoredBytes =
    localSalesEncode(LocalFoodResultV1(code, null, null), LocalFoodResultV1.serializer())
private fun foodCheckPreparedResources(target: DomainId, owner: DomainId, actor: DomainId,
    activity: Set<RecoveryResourceKey>): Set<RecoveryResourceKey> = setOf(orderResource(target), orderResource(owner),
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actor)) + activity
private fun requireSameFoodCheckRow(expected: StoredAggregate, actual: StoredAggregate) {
    check(expected.key == actual.key && expected.revision == actual.revision && expected.schema == actual.schema &&
        expected.payload == actual.payload && expected.digest == actual.digest)
}
private fun requireSameFoodCheckStream(expected: RetainedStreamedCommand, actual: RetainedStreamedCommand) {
    check(expected.identity == actual.identity && expected.journalHash == actual.journalHash &&
        expected.normalized == actual.normalized && expected.result == actual.result)
}
private fun requireEarlierFoodCheckStream(original: RetainedStreamedCommand, rejected: RetainedCommandEvidence) {
    val identity = original.identity
    check(identity.scope == rejected.scope && identity.assignmentId == rejected.assignmentId &&
        identity.authorityEpoch == rejected.authorityEpoch && identity.sequence < rejected.sequence &&
        identity.producer.operationId != rejected.operationId)
}
private fun requireSameFoodCheckLegacyOriginal(expected: RetainedCommandEvidence, actual: RetainedCommandEvidence) {
    check(expected.scope == actual.scope && expected.operationId == actual.operationId &&
        expected.originalActorId == actual.originalActorId && expected.command == actual.command &&
        expected.assignmentId == actual.assignmentId && expected.authorityEpoch == actual.authorityEpoch &&
        expected.sequence == actual.sequence && expected.recordedAt == actual.recordedAt &&
        expected.semanticDigest == actual.semanticDigest && expected.code == actual.code &&
        expected.normalized == actual.normalized && expected.result == actual.result && expected.resultHash == actual.resultHash &&
        expected.previousJournalHash == actual.previousJournalHash && expected.journalHash == actual.journalHash &&
        expected.nativeOriginalProvenance == actual.nativeOriginalProvenance &&
        expected.aggregateWrites.size == actual.aggregateWrites.size &&
        expected.aggregateWrites.zip(actual.aggregateWrites).all { (a, b) -> a.key == b.key &&
            a.storageGeneration == b.storageGeneration && a.schema == b.schema && a.payload == b.payload &&
            a.payloadDigest == b.payloadDigest } && expected.claimChanges.size == actual.claimChanges.size &&
        expected.claimChanges.zip(actual.claimChanges).all { (a, b) ->
            a.key == b.key && a.ownerId == b.ownerId && a.action == b.action } && expected.jobs.size == actual.jobs.size &&
        expected.jobs.zip(actual.jobs).all { (a, b) -> a.kind == b.kind && a.body == b.body })
    if (expected.nativeOriginalProvenance == null)
        check(expected.authenticationId == actual.authenticationId && expected.policyRevision == actual.policyRevision)
}
