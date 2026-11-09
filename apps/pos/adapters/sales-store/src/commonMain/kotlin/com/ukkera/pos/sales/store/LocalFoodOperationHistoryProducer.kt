package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalCodecV2
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/**
 * The activated-normalized restaurant/cafe operation slice, on the genuine native before-cut.
 * No handler is executed and no current view, employee grant, stock cut, recipe or original is
 * constructed from history. The SAME installed full stock + FoodStockOriginalProof traversals
 * authenticate every signed recipe/pricing/configuration dependency and compare the complete
 * result/effects/resources. Native source pairings below additionally require each present
 * BEFORE row's strictly earlier original to have already completed its whole business join.
 * The explicit quotation Add uses that same owner and exact Add requirement; literal original
 * quotation sources and complete nonphysical effects remain the installed producer's duty.
 *
 * Only the existing NONPHYSICAL financial requests are added to this SAME FOOD owner.
 * Physical funding/refund REQUESTS, legacy operations, handoff and unstarted confirmation/
 * restoration remain unsupported. Genuine earlier FOOD CASH collection roots may be sources
 * only after their separately installed complete payment/native/raw/Apply business joins. Kitchen delivery/START evidence is not inferred from money
 * or outbox insertion. Core retains the original employee/firstActor and UNKNOWN restrictions.
 */
public class LocalFoodOperationHistoryProducer(private val stock: LocalStockRepository,
    private val foodCheckRefusals: LocalFoodCheckRefusalSupport? = null) : RuntimeHistoryBusinessProducer {
    override public val commands: Set<CommandKey> = setOf(StockStreamingCommandsV2.FOOD)

    /** Same installed owner; native-current kitchen and actual CASH financial documents close
     * their complete quote/order/reservation/issue/station/obligation relationships. Handoff,
     * quality claims, legacy and unstarted confirmation/restoration remain held. */
    override public suspend fun joinCurrentFood(state: RuntimeHistoricalFoodCurrentState): Boolean =
        requireFoodCurrentHistory(state)

    override public suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        if (command.original is RetainedOriginalV2.Legacy) return foodCheckRefusals?.join(command) == true
        val original = (command.original as? RetainedOriginalV2.Streamed)?.evidence ?: return false
        check(LocalFoodStockCodecV2.normalize(command.normalized) == command.normalized)
        val supported = when (val request = LocalFoodStockCodecV2.decode(command.normalized)) {
            is LocalFoodStockRequestV2.ActivityProtected -> requireDraftRequirement(command, request.request)
            is LocalFoodStockRequestV2.ActivityProtectedAdd -> requirePreparationRequirement(command, request.request)
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> requirePreparationRequirement(command, request.request)
            is LocalFoodStockRequestV2.CreateDraft -> requireDraftRequirement(command, request.request)
            is LocalFoodStockRequestV2.Preparation -> requirePreparationRequirement(command, request.request)
            is LocalFoodStockRequestV2.Financial -> requireNonphysicalFoodFinancialHistoryRequirement(command, request.request)
        }
        if (!supported) return false

        requireOriginalSources(command, original)
        // The repository's installed full producer includes LocalCatalogFoodStockSource's
        // original complete signed quote/current-for-send logic and the shared pricing engine.
        // Its native deadline hook joins the exact selected stock/reservation deadline for
        // Begin/Send; core requires an absent decision when this command produced no deadline.
        stock.requireHistoricalRelationalProducer(command)
        return true
    }

    private suspend fun requireDraftRequirement(command: RuntimeHistoricalCommand, request: LocalFoodDraftCreateV1): Boolean {
        val key = AggregateKey(LocalFoodProfileV1.ORDER, request.orderId)
        check(command.aggregateSource(key) == null && command.claim(foodSourceClaim(key)) == null)
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.ORDER_CREATE, command.scope, request.orderId,
            Revision(0), command.operationId, command.semanticDigest, "food.operation", command.actorId))
        return true
    }

    private suspend fun requirePreparationRequirement(command: RuntimeHistoricalCommand, request: LocalFoodRequestV1): Boolean {
        when (request) {
            is LocalFoodRequestV1.Create, is LocalFoodRequestV1.Add, is LocalFoodRequestV1.EditServiceDetails, is LocalFoodRequestV1.Send,
            is LocalFoodRequestV1.Serve, is LocalFoodRequestV1.Cancel, is LocalFoodRequestV1.DiscardStarted -> Unit
            is LocalFoodRequestV1.Station -> when (request.action) {
                LocalFoodStationActionV1.START, LocalFoodStationActionV1.READY -> Unit
                LocalFoodStationActionV1.CONFIRM_UNSTARTED -> return false
            }
            else -> return false
        }

        val orderId = request.foodOrderId()
        val key = AggregateKey(LocalFoodProfileV1.ORDER, orderId)
        val before = command.aggregateSource(key)
        val owner = if (request is LocalFoodRequestV1.Create) {
            check(before == null && command.claim(foodSourceClaim(key)) == null)
            command.actorId
        } else {
            val source = before ?: unavailable()
            val row = source.row
            check(foodStreamedSchema(key.kind, row.schema) && command.claim(foodSourceClaim(key)) == orderId)
            if (row.schema == LocalFoodDraftProfileV1.ORDER_SCHEMA &&
                request !is LocalFoodRequestV1.Add && request !is LocalFoodRequestV1.EditServiceDetails) return false
            val value = foodStoredEnvelope(row.schema, row.payload)
            check(value.scope == command.scope && value.kind == key.kind && value.id == orderId && value.revision == row.revision)
            check(producer(source.original).operationId == value.operationId &&
                producer(source.original).command == StockStreamingCommandsV2.FOOD)
            val order = value.body as? FoodBodyV1.Order ?: unavailable()
            check(order.closedByOperationId == null)
            order.ownerId
        }
        // Exact production foodStockAdmission/FoodStockPrepared requirement, not a restored
        // session or AuthorizedAction. Station actions keep their original permission semantics.
        command.requireRequirement(salesAuthorizationRequirement(foodAction(request), command.scope, foodTarget(request),
            request.foodOrderRevision(), command.operationId, command.semanticDigest, "food.operation", owner))

        return true
    }

    private suspend fun requireOriginalSources(command: RuntimeHistoricalCommand, original: RetainedStreamedCommand) {
        original.frames { frame ->
            when (val source = (frame as? RetainedCommandFrameV2.Source)?.value) {
                is StreamedSourceObservationV2.Aggregate -> {
                    val paired = requireSource(command, source.key, source.generation, source.schema, source.payload, source.payloadHash)
                    check(producer(paired.original) == source.producer &&
                        command.earlierOriginal(source.producer.operationId) === paired.original)
                }
                is StreamedSourceObservationV2.Current -> {
                    requireSource(command, source.key, source.generation, source.schema, source.payload, source.payloadHash)
                }
                is StreamedSourceObservationV2.CurrentAbsent -> check(command.aggregateSource(source.key) == null)
                is StreamedSourceObservationV2.Claim -> check(command.claim(source.key) == source.ownerId)
                is StreamedSourceObservationV2.StockMember -> {
                    val identity = source.record.identity
                    val paired = command.aggregateSource(identity.key) ?: unavailable()
                    requireEarlier(command, paired)
                    check(paired.row.revision == identity.generation && paired.row.digest == identity.payloadHash &&
                        producer(paired.original).operationId == identity.producerOperationId &&
                        LocalStockRelationalCodecV2.project(command.scope, paired.row) == source.record)
                }
                // Core closes the complete unfiltered range against this original native cut;
                // every literal member above is also paired, never a digest-only stock proof.
                is StreamedSourceObservationV2.StockRange -> Unit
                is StreamedSourceObservationV2.StockItemAbsent,
                is StreamedSourceObservationV2.EstateMember,
                is StreamedSourceObservationV2.EstateEnd -> unavailable()
                null -> Unit
            }
        }
    }

    private suspend fun requireSource(command: RuntimeHistoricalCommand, key: AggregateKey, generation: Revision,
        schema: String, payload: StoredBytes, digest: PayloadDigest): RuntimeHistoricalAggregateSource {
        val paired = command.aggregateSource(key) ?: unavailable()
        check(paired.row.key == key && paired.row.revision == generation && paired.row.schema == schema &&
            paired.row.payload == payload && paired.row.digest == digest)
        requireEarlier(command, paired)
        // A financial root/index is authenticated as its actual typed document, not merely
        // paired by digest. Framed leaves come from this SAME genuine earlier original and
        // are individually compared with the complete native BEFORE cut.
        if (key.kind in foodFinancialDocumentKinds) requireFoodFinancialHistorySource(command, paired)
        return paired
    }

    private suspend fun requireEarlier(command: RuntimeHistoricalCommand, source: RuntimeHistoricalAggregateSource) {
        val actual = producer(source.original)
        check(command.earlierOriginal(actual.operationId) === source.original && actual.operationId != command.operationId)
        val current = (command.original as? RetainedOriginalV2.Streamed)?.evidence ?: unavailable()
        val identity = current.identity
        when (val earlier = source.original) {
            is RetainedOriginalV2.Legacy -> check(earlier.evidence.committed && earlier.evidence.scope == command.scope &&
                earlier.evidence.assignmentId == identity.assignmentId && earlier.evidence.authorityEpoch == identity.authorityEpoch &&
                earlier.evidence.sequence < identity.sequence)
            is RetainedOriginalV2.Streamed -> check(earlier.evidence.identity.scope == command.scope &&
                earlier.evidence.identity.assignmentId == identity.assignmentId &&
                earlier.evidence.identity.authorityEpoch == identity.authorityEpoch && earlier.evidence.identity.sequence < identity.sequence)
        }
    }

    private fun producer(original: RetainedOriginalV2): StreamedProducerIdentityV2 = when (original) {
        is RetainedOriginalV2.Legacy -> with(original.evidence) { StreamedProducerIdentityV2(operationId, command, originalActorId, semanticDigest) }
        is RetainedOriginalV2.Streamed -> original.evidence.identity.producer
    }

    private fun unavailable(): Nothing = error("food.history.original_source_unavailable")
}
