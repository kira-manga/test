package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Genuine synchronous FOOD CASH lane, installed beside the SAME actual native gate. */
public class LocalFoodRefundHandler(private val stock: LocalStockRepository,
    private val participantsSource: TransactionRetailJournalParticipantsSource = UnavailableRetailJournalParticipantsSource) : MasterCommandHandler {
    override val key: CommandKey get() = LocalFoodRefundProfile.COMMAND
    override val writableKinds: Set<String> = LocalFoodRefundProfile.gatedKinds + setOf(LocalFoodRefundProfile.APPLICATION,
        LocalFoodRefundProfile.EVENT, LocalFoodFinancialProfileV1.REFUND, LocalFoodFinancialProfileV1.FRAME,
        CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND, ReservedOperations.KIND, StockReadEvidenceV1.KIND)
    override val claimNamespaces: Set<String> = LocalFoodRefundProfile.gatedClaims + setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE,
        CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    override val physicalRecordingKinds: Set<String> get() = LocalFoodRefundProfile.gatedKinds
    override val physicalRecordingClaimNamespaces: Set<String> get() = LocalFoodRefundProfile.gatedClaims
    override val physicalInteractiveAuthorizer: MasterPhysicalInteractiveAuthorizer = LocalFoodRefundInteractiveAuthorization(stock)
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodRefundCodec.normalize(input)
    override fun physicalRecordingAction(normalized: StoredBytes): PhysicalRecordingAction? = when (LocalFoodRefundCodec.decode(normalized)) {
        is LocalFoodRefundRequest.StartCash -> PhysicalRecordingAction.START_REFUND
        is LocalFoodRefundRequest.RecordOriginal -> PhysicalRecordingAction.RETAIN_ORIGINAL
        is LocalFoodRefundRequest.RecordResolution -> PhysicalRecordingAction.RETAIN_RESOLUTION
        is LocalFoodRefundRequest.ApplyOriginal, is LocalFoodRefundRequest.ApplyResolution -> null
    }
    override fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? = when (val request = LocalFoodRefundCodec.decode(normalized)) {
        is LocalFoodRefundRequest.RecordOriginal -> foodRefundFenceBinding(request.target)
        is LocalFoodRefundRequest.RecordResolution -> foodRefundFenceBinding(request.target, resolution = true)
        is LocalFoodRefundRequest.StartCash, is LocalFoodRefundRequest.ApplyOriginal, is LocalFoodRefundRequest.ApplyResolution -> null
    }
    override fun physicalRecordingOutcome(normalized: StoredBytes, effectiveOutcome: ProposedOutcome,
        context: CommandContext): PhysicalRecordingOutcome? = foodRefundPhysicalOutcome(normalized, effectiveOutcome, context)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = LocalFoodRefundCodec.decode(normalized)) {
            is LocalFoodRefundRequest.StartCash -> prepareStart(request, view, context)
            is LocalFoodRefundRequest.RecordOriginal -> prepareRaw(request, normalized, FoodReadView(commerceLoan(view, stock)), context)
            is LocalFoodRefundRequest.RecordResolution -> prepareFoodRefundResolution(request, normalized,
                FoodReadView(commerceLoan(view, stock)), context, stock)
            is LocalFoodRefundRequest.ApplyOriginal -> prepareFoodRefundApplication(request.recordingOperationId,
                LocalFoodRefundFactPhase.ORIGINAL, view, context, stock)
            is LocalFoodRefundRequest.ApplyResolution -> prepareFoodRefundApplication(request.resolutionOperationId,
                LocalFoodRefundFactPhase.RESOLUTION, view, context, stock)
        }
    }

    private suspend fun prepareStart(request: LocalFoodRefundRequest.StartCash, borrowed: CommandReadView,
        context: CommandContext): PreparedMutation {
        val header = foodFinancialObserve(borrowed, LocalFoodFinancialProfileV1.REFUND, request.refundId)?.second
            ?.let(::foodFinancialRefund) ?: return foodRefundUnavailablePreparation(context, request.refundId)
        if (context.operationId in setOf(request.recordingOperationId, request.resolutionOperationId))
            return foodRefundUnavailablePreparation(context, request.refundId)
        val view = FoodReadView(commerceLoan(borrowed, stock))
        val capture = view.requireStockRelational().beginReadEvidence(context)
        var sealed = false
        suspend fun refuse(code: String): PreparedMutation {
            if (!sealed) capture.discard()
            return FoodRefundPrepared(context, SecurityAction.REFUND_RECORD, header.id, request.expectedRefundRevision,
                context.originalActorId, "food.refund.start", request.amount,
                foodRefundBasisResources(header) + cashResource(request.sessionId), foodRefundApplicationBarriers) { _, _ -> foodRefundReject(code) }
        }
        try {
            val cut = foodRefundCut(view, request.refundId)
            val cash = LocalSalesStorage.cash(view, request.sessionId)
            if (cash.session.value.status != CashSessionStatus.OPEN || cash.session.value.custodianId != context.originalActorId)
                return refuse("food.refund.current_paying_custody_required")
            val obligation = cut.value.obligations.singleOrNull { it.originalPaymentId == request.originalPaymentId }
                ?: return refuse("food.refund.original_payment_required")
            if (obligation.tender != Tender.Cash) return refuse("food.refund.original_cash_tender_required")
            val inputs = cut.inputs; val cashInput = foodFinancialCashInput(cash)
            val preliminary = FoodRefundSources(capture.observations()).refund(inputs, cashInput)
            val financial = preliminary.financialResources + entitlementResource(request.attemptId)
            if (financial.size > LocalFoodRefundProfile.MAX_FINANCIAL_RESOURCES) return refuse("food.refund.capacity_required")
            val requirement = prepareFoodRefundRequirement(view, context, participantsSource, request,
                request.target(cut.value, context.operationId), financial, foodRefundBasisDigest(inputs))
                ?: return refuse("food.refund.journal_required")
            if (!foodRefundVacant(view, requirement)) return refuse("food.refund.identity_used")
            val sources = FoodRefundSources(capture.observations())
            val literal = sources.refund(inputs, cashInput)
            sources.startVacancy(requirement); sources.finish()
            check(literal.financialResources + entitlementResource(request.attemptId) == foodRefundDomain(requirement).financialResources.toSet() &&
                foodRefundBasisDigest(inputs) == foodRefundDomain(requirement).obligationSourcesDigest)
            val resources = financial + foodRefundAuthorityResources(requirement)
            if (resources.size > 512) return refuse("food.refund.capacity_required")
            val evidence = capture.finish(); sealed = true
            fun draft(command: SalesCommand, at: UtcInstant): FoodRefundStartDraft = foodRefundStartDraft {
                val changed = FoodRefunds.start(command, cut.value, request.expectedRefundRevision, cash.session.value,
                    request.expectedSessionRevision, request.attemptId, request.originalPaymentId, request.amount)
                val revisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.REFUND, cut.value.id, cut.refund.row.revision.next()),
                    LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND, cash.session.value.id, cash.session.stored.revision.next()),
                    LocalFoodStorageRevisionV1(LocalFoodRefundProfile.SLOT, DomainId(requirement.recordingOperationId.value), Revision(1)))
                val result = LocalFoodRefundResult.AttemptStarted(foodRefundDomain(requirement).target, request.amount,
                    sha256(requirement.canonicalBytes.copyBytes()), revisions)
                val preview = FoodRefundWrites(context, at, inputs, cashInput)
                preview.refund(changed.refund)
                val finite = preview.finish(result, listOf(foodPaymentCashWrite(changed.session, cashInput.storageRevision)),
                    listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_attempt", request.attemptId), request.refundId)), evidence)
                // Full financial frame/read set plus the MAXIMUM actual native pair/slot/fences
                // is admitted BEFORE asking the genuine gate for physical custody. No fake sources.
                val nativeBytes = 2L * LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES + LocalFoodRefundProfile.MAX_SLOT_BYTES +
                    foodRefundFences(requirement).sumOf { it.payload.size.toLong() } + 4_096L
                foodCapacity(finite.changes.size + 5 <= 128 && finite.claims.size + 5 <= 128 &&
                    finite.changes.sumOf { it.payload.size.toLong() } + finite.result.size + nativeBytes <= 1_048_576L &&
                    finite.changes.sumOf { it.payload.size.toLong() + 512L } + finite.result.size + nativeBytes + 16_384L <= FoodReadLimits.FOOD_PRODUCER_BYTES)
                FoodRefundStartDraft.Ready(changed, result)
            }
            return FoodRefundPrepared(context, SecurityAction.REFUND_RECORD, cut.value.id, request.expectedRefundRevision,
                cash.session.value.custodianId, "food.refund.start", request.amount, resources, foodRefundApplicationBarriers,
                admission = { command, at -> if (draft(command, at) is FoodRefundStartDraft.Ready)
                    foodRefundAdmission(requirement, PhysicalRecordingAction.START_REFUND) else null }) { command, at ->
                when (val value = draft(command, at)) {
                    is FoodRefundStartDraft.Refused -> value.proposal
                    is FoodRefundStartDraft.Ready -> {
                        val native = foodRefundStartSources(context, requirement)
                            ?: return@FoodRefundPrepared foodRefundReject("food.refund.journal_required")
                        val writes = FoodRefundWrites(context, at, inputs, cashInput)
                        writes.refund(value.changed.refund)
                        writes.finish(value.result, listOf(foodPaymentCashWrite(value.changed.session, cashInput.storageRevision)) + native.changes,
                            listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("refund_attempt", request.attemptId), request.refundId)) + native.claims,
                            evidence, slotDigest = sha256(foodRefundSlotWrite(native.slot).payload.copyBytes()))
                    }
                }
            }
        } catch (_: FoodCapacityExceeded) { return refuse("food.refund.capacity_required") }
          catch (_: StockReadEvidenceCapacityExceeded) { return refuse("food.refund.capacity_required") }
          catch (_: LocalSalesWireCapacityExceeded) { return refuse("food.refund.capacity_required") }
          catch (_: LocalSalesCapacityExceeded) { return refuse("food.refund.capacity_required") }
          catch (_: LocalSalesSourceUnavailable) { return refuse("food.refund.source_unavailable") }
          catch (failure: LocalStockRejected) {
            if (failure.failure != LocalStockFailure.BOUND_EXCEEDED) throw failure
            return refuse("food.refund.capacity_required")
          }
    }

    private suspend fun prepareRaw(request: LocalFoodRefundRequest.RecordOriginal, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): PreparedMutation {
        if (context.operationId != request.target.recordingOperationId || view.aggregate(foodRefundSlotKey(context.operationId)) == null)
            return foodRefundUnavailablePreparation(context, DomainId(request.target.recordingOperationId.value))
        val cut = readFoodRefundSlot(view, request.target.recordingOperationId, stock)
        val slot = cut.value
        if (slot.target != request.target || slot.originalActorId != context.originalActorId) return foodRefundUnavailablePreparation(context, cut.stored.key.id)
        val requirement = slot.requirement()
        LocalFoodRefundCashJournalDomain.classifyOriginal(requirement, normalized)
        check(view.aggregate(foodRefundRawKey(context.operationId)) == null && view.aggregate(foodRefundApplicationKey(context.operationId)) == null)
        val admission = foodRefundAdmission(requirement, PhysicalRecordingAction.RETAIN_ORIGINAL)
        return FoodRefundPrepared(context, SecurityAction.REFUND_RECORD, cut.stored.key.id, Revision(0), slot.originalActorId,
            "food.refund.retain", slot.domain.amount, slot.domain.financialResources.toSet() + foodRefundAuthorityResources(requirement),
            admission = { _, _ -> if (context.physicalParticipants?.let { it.authority == slot.domain.authority &&
                it.clientDeviceId == requirement.clientDeviceId && it.originalActorId == requirement.originalActorId } == true) admission else null }) { _, at ->
            val native = context.physicalRecording
            if (native == null || !native.matches(admission) || native.sourceChanges.isNotEmpty()) return@FoodRefundPrepared foodRefundReject("food.refund.journal_required")
            val raw = LocalFoodRefundRaw(1, context.scope, slot.target, slot.originalActorId, context.originalActorId,
                normalized.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(normalized.copyBytes()), context.semanticDigest, cut.stored.digest, at)
            ProposedOutcome.Apply(LocalFoodRefundCodec.result(LocalFoodRefundResult.RawRetained(slot.target)),
                listOf(foodRefundRawWrite(raw), foodRefundApplicationWrite(foodRefundInitialApplication(raw), null)))
        }
    }


}

internal class FoodRefundPrepared(private val context: CommandContext, action: SecurityAction, targetId: DomainId,
    revision: Revision, ownerId: DomainId, private val reason: String, amount: Money? = null,
    resources: Set<RecoveryResourceKey>, override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = emptySet(),
    override val allowsActionApproval: Boolean = false,
    private val admission: ((SalesCommand, UtcInstant) -> PhysicalRecordingAdmission?)? = null,
    private val proposal: (SalesCommand, UtcInstant) -> ProposedOutcome) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(action, context.scope, targetId, revision,
        context.operationId, context.semanticDigest, reason, ownerId, amount)
    override val affectedResources: Set<RecoveryResourceKey> = resources + recordingAuthority(context.originalActorId)
    private fun command(authorization: AuthorizedAction, at: UtcInstant): SalesCommand {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
        return SalesCommand(context.operationId, authorization, at.value, context.semanticDigest, reason)
    }
    override fun physicalRecordingAdmission(authorization: AuthorizedAction, recordedAt: UtcInstant): PhysicalRecordingAdmission? =
        admission?.invoke(command(authorization, recordedAt), recordedAt)
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = try {
        proposal(command(authorization, recordedAt), recordedAt)
    } catch (_: FoodCapacityExceeded) { foodRefundReject("food.refund.capacity_required") }
      catch (_: LocalSalesWireCapacityExceeded) { foodRefundReject("food.refund.capacity_required") }
      catch (_: LocalSalesCapacityExceeded) { foodRefundReject("food.refund.capacity_required") }
      catch (failure: SalesRuleViolation) { foodRefundReject("food.refund.rule." + failure.rule.name.lowercase()) }
}

private sealed interface FoodRefundStartDraft {
    class Ready(val changed: FoodRefundCashChange, val result: LocalFoodRefundResult.AttemptStarted) : FoodRefundStartDraft
    class Refused(val proposal: ProposedOutcome.Reject) : FoodRefundStartDraft
}
private inline fun foodRefundStartDraft(block: () -> FoodRefundStartDraft): FoodRefundStartDraft = try { block() }
catch (_: FoodCapacityExceeded) { FoodRefundStartDraft.Refused(foodRefundReject("food.refund.capacity_required")) }
catch (_: LocalSalesWireCapacityExceeded) { FoodRefundStartDraft.Refused(foodRefundReject("food.refund.capacity_required")) }
catch (_: LocalSalesCapacityExceeded) { FoodRefundStartDraft.Refused(foodRefundReject("food.refund.capacity_required")) }
catch (failure: SalesRuleViolation) { FoodRefundStartDraft.Refused(foodRefundReject("food.refund.rule." + failure.rule.name.lowercase())) }

internal fun foodRefundUnavailablePreparation(context: CommandContext, target: DomainId): PreparedMutation =
    FoodRefundPrepared(context, SecurityAction.RETURN_RECEIPT_LOOKUP, target, Revision(0), context.originalActorId,
        "food.refund.unavailable", resources = setOf(entitlementResource(target), recordingAuthority(context.originalActorId))) { _, _ -> foodRefundReject("food.refund.not_available") }

/** CLOSED typed effective outcome, never an Apply result or request flag masquerading as Start. */
internal fun foodRefundPhysicalOutcome(normalized: StoredBytes, outcome: ProposedOutcome,
    context: CommandContext): PhysicalRecordingOutcome? {
    val request = LocalFoodRefundCodec.decode(normalized)
    if (request is LocalFoodRefundRequest.ApplyOriginal || request is LocalFoodRefundRequest.ApplyResolution) return null
    if (outcome is ProposedOutcome.Reject) {
        return PhysicalRecordingOutcome.NonArming
    }
    val apply = outcome as ProposedOutcome.Apply
    return when (request) {
        is LocalFoodRefundRequest.StartCash -> {
            val result = LocalFoodRefundCodec.result(apply.result) as? LocalFoodRefundResult.AttemptStarted ?: error("food.refund.typed_start_required")
            val change = apply.changes.single { it.key == foodRefundSlotKey(request.recordingOperationId) }
            val slot = localSalesDecode(change.payload, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES)
            val requirement = slot.requirement()
            check(slot.target.refundId == request.refundId && slot.target.sessionId == request.sessionId &&
                slot.target.originalPaymentId == request.originalPaymentId && slot.target.attemptId == request.attemptId &&
                slot.target.intentOperationId == context.operationId && slot.target.recordingOperationId == request.recordingOperationId &&
                slot.target.resolutionOperationId == request.resolutionOperationId && slot.domain.amount == request.amount &&
                slot.domain.refundRevision == request.expectedRefundRevision && slot.domain.sessionRevision == request.expectedSessionRevision &&
                result.target == slot.target && result.amount == request.amount &&
                result.requirementDigest == sha256(requirement.canonicalBytes.copyBytes()) && apply.mustCommitBefore == null)
            val actual = foodRefundStartSources(context, requirement) ?: error("food.refund.actual_sources_required")
            val gated = apply.changes.filter { it.key.kind in LocalFoodRefundProfile.gatedKinds || it.key.kind == ReservedOperations.KIND }
            requireFoodRefundExactChanges(gated, actual.changes)
            val claims = apply.claims.filter { it.key.namespace in LocalFoodRefundProfile.gatedClaims }
            check(claims == actual.claims && apply.changes.none { it.key.kind in StockRelationalKindsV2.all || it.key.kind in LocalStockStoreProfile.inventoryKinds })
            PhysicalRecordingOutcome.Start(PhysicalRecordingStartKind.REFUND, actual.changes, actual.claims)
        }
        is LocalFoodRefundRequest.RecordOriginal -> {
            check(context.operationId == request.target.recordingOperationId)
            val raw = localSalesDecode(apply.changes.single { it.key == foodRefundRawKey(context.operationId) }.payload,
                LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
            check(raw.target == request.target && raw.body() == normalized && raw.originalActorId == context.originalActorId &&
                raw.recordingActorId == context.originalActorId && raw.bodyDigest == sha256(normalized.copyBytes()) && raw.semanticDigest == context.semanticDigest &&
                LocalFoodRefundCodec.result(apply.result) == LocalFoodRefundResult.RawRetained(request.target) && apply.claims.isEmpty() &&
                apply.jobs.isEmpty() && apply.mustCommitBefore == null)
            requireFoodRefundExactChanges(apply.changes, listOf(foodRefundRawWrite(raw), foodRefundApplicationWrite(foodRefundInitialApplication(raw), null)))
            PhysicalRecordingOutcome.FactImported(raw.bodyDigest, listOf(foodRefundRawWrite(raw)))
        }
        is LocalFoodRefundRequest.RecordResolution -> {
            check(context.operationId == request.target.resolutionOperationId && request.resolverActorId == context.originalActorId)
            val raw = localSalesDecode(apply.changes.single { it.key == foodRefundResolutionKey(context.operationId) }.payload,
                LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
            check(raw.target == request.target && raw.body() == normalized && raw.recordingActorId == context.originalActorId &&
                raw.originalBodyDigest == request.originalBodyDigest && raw.bodyDigest == sha256(normalized.copyBytes()) &&
                raw.semanticDigest == context.semanticDigest && request.outcome !is LocalFoodRefundOutcome.Unknown &&
                LocalFoodRefundCodec.result(apply.result) == LocalFoodRefundResult.RawRetained(request.target,
                    phase = LocalFoodRefundFactPhase.RESOLUTION) && apply.claims.isEmpty() && apply.jobs.isEmpty() && apply.mustCommitBefore == null)
            requireFoodRefundExactChanges(apply.changes, listOf(foodRefundResolutionWrite(raw),
                foodRefundApplicationWrite(foodRefundInitialApplication(raw), null)))
            PhysicalRecordingOutcome.FactImported(raw.bodyDigest, listOf(foodRefundResolutionWrite(raw)))
        }
        is LocalFoodRefundRequest.ApplyOriginal, is LocalFoodRefundRequest.ApplyResolution -> error("food.refund.nonphysical_action")
    }
}

/** Exact FOOD native tuple comparison, independent of any retail monetary profile. */
internal fun requireFoodRefundExactChanges(actual: List<AggregateChange>, expected: List<AggregateChange>) {
    check(actual.size == expected.size)
    for ((value, wanted) in actual.zip(expected)) check(value.key == wanted.key && value.expectedRevision == wanted.expectedRevision &&
        value.schema == wanted.schema && value.payload == wanted.payload)
}
