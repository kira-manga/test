package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Genuine synchronous FOOD CASH lane, installed beside the SAME actual native gate. */
public class LocalFoodPaymentHandler(private val stock: LocalStockRepository,
    private val recipes: TransactionFoodStockSource,
    private val participantsSource: TransactionRetailJournalParticipantsSource = UnavailableRetailJournalParticipantsSource) : MasterCommandHandler,
    LocalMasterRpcRetainedResultSourceAccessProvider {
    override val key: CommandKey get() = LocalFoodPaymentProfile.COMMAND
    override val writableKinds: Set<String> = LocalFoodPaymentProfile.gatedKinds + setOf(LocalFoodPaymentProfile.APPLICATION,
        LocalFoodPaymentProfile.EVENT, LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.FRAME,
        CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND, ReservedOperations.KIND, StockReadEvidenceV1.KIND)
    override val claimNamespaces: Set<String> = LocalFoodPaymentProfile.gatedClaims + setOf(LocalSalesStoreProfile.UNIQUE_NAMESPACE,
        CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    override val physicalRecordingKinds: Set<String> get() = LocalFoodPaymentProfile.gatedKinds
    override val physicalRecordingClaimNamespaces: Set<String> get() = LocalFoodPaymentProfile.gatedClaims
    override val physicalInteractiveAuthorizer: MasterPhysicalInteractiveAuthorizer = LocalFoodPaymentInteractiveAuthorization(stock, recipes)
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodPaymentCodec.normalize(input)
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL && normalize(normalized) == normalized &&
            LocalFoodPaymentCodec.decode(normalized) is LocalFoodPaymentRequest.ApplyOriginal

    override suspend fun rpcRetainedResultRequirements(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): List<AuthorizationRequirement>? {
        if (!supportsRpcWorkflow(profile, normalized) || context.scope != view.scope || context.key != key ||
            semanticDigest(context.scope, context.originalActorId, key, normalized) != context.semanticDigest) return null
        return foodCashFinancialRetainedPaymentRequirements(normalized, FoodReadView(commerceLoan(view, stock)), context, recipes)
    }

    override fun physicalRecordingAction(normalized: StoredBytes): PhysicalRecordingAction? = when (LocalFoodPaymentCodec.decode(normalized)) {
        is LocalFoodPaymentRequest.StartCash -> PhysicalRecordingAction.START_PAYMENT
        is LocalFoodPaymentRequest.RecordOriginal -> PhysicalRecordingAction.RETAIN_ORIGINAL
        is LocalFoodPaymentRequest.RecordResolution -> PhysicalRecordingAction.RETAIN_RESOLUTION
        is LocalFoodPaymentRequest.ApplyOriginal, is LocalFoodPaymentRequest.ApplyResolution -> null
    }
    override fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? = when (val request = LocalFoodPaymentCodec.decode(normalized)) {
        is LocalFoodPaymentRequest.RecordOriginal -> foodPaymentFenceBinding(request.target)
        is LocalFoodPaymentRequest.RecordResolution -> foodPaymentFenceBinding(request.target, resolution = true)
        is LocalFoodPaymentRequest.StartCash, is LocalFoodPaymentRequest.ApplyOriginal, is LocalFoodPaymentRequest.ApplyResolution -> null
    }
    override fun physicalRecordingOutcome(normalized: StoredBytes, effectiveOutcome: ProposedOutcome,
        context: CommandContext): PhysicalRecordingOutcome? = foodPaymentPhysicalOutcome(normalized, effectiveOutcome, context)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = LocalFoodPaymentCodec.decode(normalized)) {
            is LocalFoodPaymentRequest.StartCash -> prepareStart(request, view, context)
            is LocalFoodPaymentRequest.RecordOriginal -> prepareRaw(request, normalized, FoodReadView(commerceLoan(view, stock)), context)
            is LocalFoodPaymentRequest.RecordResolution -> prepareFoodPaymentResolution(request, normalized,
                FoodReadView(commerceLoan(view, stock)), context, stock)
            is LocalFoodPaymentRequest.ApplyOriginal -> prepareFoodPaymentApplication(request.recordingOperationId,
                LocalFoodPaymentFactPhase.ORIGINAL, view, context, stock, recipes)
            is LocalFoodPaymentRequest.ApplyResolution -> prepareFoodPaymentApplication(request.resolutionOperationId,
                LocalFoodPaymentFactPhase.RESOLUTION, view, context, stock, recipes)
        }
    }

    private suspend fun prepareStart(request: LocalFoodPaymentRequest.StartCash, borrowed: CommandReadView,
        context: CommandContext): PreparedMutation {
        // Private header admission precedes rich reads, but uses a separate wrapper from the
        // later real capture: a cache hit must not silently omit the collection source.
        val header = foodFinancialObserve(borrowed, LocalFoodFinancialProfileV1.COLLECTION, request.collectionId)?.second
            ?.let(::foodFinancialCollection) ?: return foodPaymentUnavailablePreparation(context, DomainId(request.recordingOperationId.value))
        if (header.currentOwnerId != context.originalActorId || header.cashSessionId != request.sessionId ||
            context.operationId in setOf(request.recordingOperationId, request.resolutionOperationId))
            return foodPaymentUnavailablePreparation(context, request.collectionId)
        var capture: StockReadCapture? = null
        var sealed = false
        suspend fun refuse(code: String): PreparedMutation {
            if (!sealed) capture?.discard()
            return FoodPaymentPrepared(context, SecurityAction.COLLECTION_ATTEST, header.id, request.expectedCollectionRevision,
                header.currentOwnerId, "food.payment.start", request.amount, setOf(orderResource(header.id), orderResource(header.orderId),
                    cashResource(header.cashSessionId)), setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)) { _, _ -> foodPaymentReject(code) }
        }
        try {
            // Current execution/catalogue validation belongs to the same real transaction,
            // but not the retained STOCK/FOOD/cash census. That compatible census must not
            // silently accept unrelated catalogue observations or lose its exact closure.
            val supportView = FoodReadView(commerceLoan(borrowed, stock))
            val supportCut = foodPaymentCollectionCut(supportView, request.collectionId, recipes)
            val supportCash = LocalSalesStorage.cash(supportView, request.sessionId)
            // Original custody may include already-issued dine-in service. Cancellation,
            // pickup, responsibility and post-transfer funding remain held lanes.
            if (!foodPaymentInitialPhysicalCustody(supportCut.value, supportCut.order.value, supportCut.portions.map { it.envelope }, supportCash.session.value))
                return refuse("food.payment.original_custody_required")
            val kitchenRestriction = foodKitchenExecutionRestriction(supportView, recipes, supportCut.order, supportCut.portions)
            if (kitchenRestriction != null) {
                return FoodPaymentPrepared(context, SecurityAction.COLLECTION_ATTEST, supportCut.value.id, request.expectedCollectionRevision,
                    supportCut.value.currentOwnerId, "food.payment.start", request.amount, foodFinancialResources(supportCut) + cashResources(supportCash),
                    setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION), admissionRestriction = kitchenRestriction) { _, _ ->
                    error("food.payment.kitchen_execution_required")
                }
            }
            // Reobserve the literal payment cut through a fresh wrapper after capture begins;
            // cached precheck rows cannot omit required source observations. No writes occur
            // between these cuts, and both use the same original borrowed writer/owner.
            val view = FoodReadView(commerceLoan(borrowed, stock))
            val activeCapture = view.requireStockRelational().beginReadEvidence(context)
            capture = activeCapture
            val cut = foodPaymentCollectionCut(view, request.collectionId, recipes)
            val cash = LocalSalesStorage.cash(view, request.sessionId)
            val inputs = foodPaymentCutInputs(cut)
            val cashInput = foodFinancialCashInput(cash)
            check(inputs == foodPaymentCutInputs(supportCut) && cashInput == foodFinancialCashInput(supportCash))
            val actualReserved = linkedMapOf<DomainId, LocalStockPaymentCut>()
            val actualIssued = linkedMapOf<DomainId, LocalStockPaymentIssuedCut>()
            for (entry in cut.value.portions) when (entry.stockOrigin) {
                FoodStockOrigin.RESERVED_FOR_COLLECTION ->
                    actualReserved[entry.reservationId] = stock.observePaymentReservation(view, entry.reservationId)
                FoodStockOrigin.ISSUED_BEFORE_COLLECTION -> {
                    val issued = stock.observePaymentIssuedReservation(view, entry.reservationId)
                    actualIssued[entry.reservationId] = issued
                    val portion = cut.portions.single { it.envelope.id == entry.portionId }.envelope.body as FoodBodyV1.Portion
                    requireFoodFirstIssue(portion, LocalStockPaymentObservations.requireIssuedOriginal(view, stock, issued))
                }
            }
            var observed = activeCapture.observations()
            val preliminary = FoodPaymentSources(observed).collection(inputs, cashInput)
            requireFoodPaymentCustody(preliminary, LocalStockPaymentObservations.paymentCustody(observed,
                preliminary.reservedReservationIds, preliminary.issuedReservationIds))
            val deadline = foodPaymentDeadline(actualReserved.values)
            val requirement = prepareFoodPaymentRequirement(view, context, participantsSource, request,
                request.target(cut.order.order.envelope.id, context.operationId), preliminary.financialResources,
                foodPaymentStockDigest(observed), deadline) ?: return refuse("food.payment.journal_required")
            val vacant = foodPaymentVacant(view, requirement)
            if (!vacant) return refuse("food.payment.identity_used")
            observed = activeCapture.observations()
            val sources = FoodPaymentSources(observed)
            val literal = sources.collection(inputs, cashInput)
            sources.startVacancy(requirement); sources.finish(stock = true)
            val selected = LocalStockPaymentObservations.paymentCustody(observed,
                literal.reservedReservationIds, literal.issuedReservationIds)
            requireFoodPaymentCustody(literal, selected)
            check(selected.reserved.keys == actualReserved.keys && selected.issued.keys == actualIssued.keys &&
                selected.reserved.all { (id, value) -> sameFoodPaymentReservedCut(value, actualReserved.getValue(id)) } &&
                selected.issued.all { (id, value) -> sameFoodPaymentIssuedCut(value, actualIssued.getValue(id))
            } && foodPaymentStockDigest(observed) == foodPaymentDomain(requirement).stockSourcesDigest)
            val resources = literal.financialResources + LocalStockPaymentObservations.resources(observed) + foodPaymentAuthorityResources(requirement)
            if (resources.size > 512) return refuse("food.payment.capacity_required")
            val evidence = activeCapture.finish(); sealed = true
            fun draft(command: SalesCommand, at: UtcInstant): FoodPaymentStartDraft = foodPaymentStartDraft {
                foodFinancialOwner(context, cut.order, request.expectedOrderStorageRevision)
                foodFinancialRule(cut.value.currentOwnerId == context.originalActorId && cut.value.responsibility.isEmpty() &&
                    cash.session.value.status == CashSessionStatus.OPEN && cash.session.value.custodianId == context.originalActorId,
                    SalesRule.OWNER_REQUIRED)
                if (!selected.reserved.values.all { it.safeAt(at) }) return@foodPaymentStartDraft FoodPaymentStartDraft.Refused(foodPaymentReject("food.payment.stock_reconciliation_required"))
                val changed = FoodCollections.startAttempt(command, cut.value, request.expectedCollectionRevision, cash.session.value,
                    request.expectedSessionRevision, request.attemptId, Tender.Cash, request.amount, request.payerReference)
                val revisions = listOf(LocalFoodStorageRevisionV1(LocalFoodFinancialProfileV1.COLLECTION, cut.value.id, cut.row.row.revision.next()),
                    LocalFoodStorageRevisionV1(CashNonSaleStoreProfile.SESSION_KIND, cash.session.value.id, cash.session.stored.revision.next()),
                    LocalFoodStorageRevisionV1(LocalFoodPaymentProfile.SLOT, DomainId(requirement.recordingOperationId.value), Revision(1)))
                val result = LocalFoodPaymentResult.AttemptStarted(foodPaymentDomain(requirement).target, request.amount,
                    sha256(requirement.canonicalBytes.copyBytes()), deadline, revisions)
                val preview = FoodPaymentWrites(context, at, inputs, cashInput)
                preview.collection(changed.collection)
                val finite = preview.finish(result, listOf(foodPaymentCashWrite(changed.session, cashInput.storageRevision)),
                    listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_attempt", request.attemptId), request.collectionId)), evidence, deadline)
                // The COMPLETE frame/read package and maximum real native pair/slot/fences are
                // charged BEFORE admission. This is a bound, never fake source/capacity evidence.
                val nativeBytes = 2L * LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES + LocalFoodPaymentProfile.MAX_SLOT_BYTES +
                    foodPaymentFences(requirement).sumOf { it.payload.size.toLong() } + 4_096L
                foodCapacity(finite.changes.size + 5 <= 128 && finite.claims.size + 5 <= 128 &&
                    finite.changes.sumOf { it.payload.size.toLong() } + finite.result.size + nativeBytes <= 1_048_576L &&
                    finite.changes.sumOf { it.payload.size.toLong() + 512L } + finite.result.size + nativeBytes + 16_384L <= FoodReadLimits.FOOD_PRODUCER_BYTES)
                FoodPaymentStartDraft.Ready(changed, result)
            }
            return FoodPaymentPrepared(context, SecurityAction.COLLECTION_ATTEST, cut.value.id, request.expectedCollectionRevision,
                cut.value.currentOwnerId, "food.payment.start", request.amount, resources,
                setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION), admission = { command, at ->
                    if (draft(command, at) is FoodPaymentStartDraft.Ready) foodPaymentAdmission(requirement, PhysicalRecordingAction.START_PAYMENT) else null
                }) { command, at -> when (val value = draft(command, at)) {
                    is FoodPaymentStartDraft.Refused -> value.proposal
                    is FoodPaymentStartDraft.Ready -> {
                        val native = foodPaymentStartSources(context, requirement)
                            ?: return@FoodPaymentPrepared foodPaymentReject("food.payment.journal_required")
                        val writes = FoodPaymentWrites(context, at, inputs, cashInput)
                        writes.collection(value.changed.collection)
                        writes.finish(value.result, listOf(foodPaymentCashWrite(value.changed.session, cashInput.storageRevision)) + native.changes,
                            listOf(ClaimChange.Acquire(LocalSalesStoreProfile.unique("payment_attempt", request.attemptId), request.collectionId)) + native.claims,
                            evidence, deadline, sha256(foodPaymentSlotWrite(native.slot).payload.copyBytes()))
                    }
                } }
        } catch (_: FoodCapacityExceeded) { return refuse("food.payment.capacity_required") }
          catch (_: StockReadEvidenceCapacityExceeded) { return refuse("food.payment.capacity_required") }
          catch (_: LocalSalesWireCapacityExceeded) { return refuse("food.payment.capacity_required") }
          catch (failure: LocalStockRejected) {
            if (failure.failure != LocalStockFailure.BOUND_EXCEEDED) throw failure
            return refuse("food.payment.capacity_required")
          }
    }

    private suspend fun prepareRaw(request: LocalFoodPaymentRequest.RecordOriginal, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): PreparedMutation {
        if (context.operationId != request.target.recordingOperationId || view.aggregate(foodPaymentSlotKey(context.operationId)) == null)
            return foodPaymentUnavailablePreparation(context, DomainId(request.target.recordingOperationId.value))
        val cut = readFoodPaymentSlot(view, request.target.recordingOperationId, stock)
        val slot = cut.value
        if (slot.target != request.target || slot.originalActorId != context.originalActorId) return foodPaymentUnavailablePreparation(context, cut.stored.key.id)
        val requirement = slot.requirement()
        LocalFoodCashJournalDomain.classifyOriginal(requirement, normalized)
        check(view.aggregate(foodPaymentRawKey(context.operationId)) == null && view.aggregate(foodPaymentApplicationKey(context.operationId)) == null)
        val admission = foodPaymentAdmission(requirement, PhysicalRecordingAction.RETAIN_ORIGINAL)
        return FoodPaymentPrepared(context, SecurityAction.COLLECTION_ATTEST, cut.stored.key.id, Revision(0), slot.originalActorId,
            "food.payment.retain", slot.domain.amount, slot.domain.financialResources.toSet() + foodPaymentAuthorityResources(requirement),
            admission = { _, _ -> if (context.physicalParticipants?.let { it.authority.deviceId == requirement.masterDeviceId &&
                it.clientDeviceId == requirement.clientDeviceId && it.originalActorId == requirement.originalActorId } == true) admission else null }) { _, at ->
            val native = context.physicalRecording
            if (native == null || !native.matches(admission) || native.sourceChanges.isNotEmpty()) return@FoodPaymentPrepared foodPaymentReject("food.payment.journal_required")
            val raw = LocalFoodPaymentRaw(1, context.scope, slot.target, slot.originalActorId, context.originalActorId,
                normalized.copyBytes().decodeToString(throwOnInvalidSequence = true), sha256(normalized.copyBytes()), context.semanticDigest, cut.stored.digest, at)
            ProposedOutcome.Apply(LocalFoodPaymentCodec.result(LocalFoodPaymentResult.RawRetained(slot.target)),
                listOf(foodPaymentRawWrite(raw), foodPaymentApplicationWrite(foodPaymentInitialApplication(raw), null)))
        }
    }


}

internal class FoodPaymentPrepared(private val context: CommandContext, action: SecurityAction, targetId: DomainId,
    revision: Revision, ownerId: DomainId, private val reason: String, amount: Money? = null,
    resources: Set<RecoveryResourceKey>, override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = emptySet(),
    override val allowsActionApproval: Boolean = false,
    private val admission: ((SalesCommand, UtcInstant) -> PhysicalRecordingAdmission?)? = null,
    override val admissionRestriction: PreparedMutationAdmissionRestriction? = null,
    private val proposal: (SalesCommand, UtcInstant) -> ProposedOutcome) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = salesAuthorizationRequirement(action, context.scope, targetId, revision,
        context.operationId, context.semanticDigest, reason, ownerId, amount)
    override val affectedResources: Set<RecoveryResourceKey> = resources + recordingAuthority(context.originalActorId)
    private fun command(authorization: AuthorizedAction, at: UtcInstant): SalesCommand {
        check(admissionRestriction == null && authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            authorization.isBoundTo(requirement, context.originalActorId))
        return SalesCommand(context.operationId, authorization, at.value, context.semanticDigest, reason)
    }
    override fun physicalRecordingAdmission(authorization: AuthorizedAction, recordedAt: UtcInstant): PhysicalRecordingAdmission? =
        admission?.invoke(command(authorization, recordedAt), recordedAt)
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = try {
        proposal(command(authorization, recordedAt), recordedAt)
    } catch (_: FoodCapacityExceeded) { foodPaymentReject("food.payment.capacity_required") }
      catch (_: LocalSalesWireCapacityExceeded) { foodPaymentReject("food.payment.capacity_required") }
      catch (_: LocalSalesCapacityExceeded) { foodPaymentReject("food.payment.capacity_required") }
      catch (failure: SalesRuleViolation) { foodPaymentReject("food.payment.rule." + failure.rule.name.lowercase()) }
}

private sealed interface FoodPaymentStartDraft {
    class Ready(val changed: FoodCollectionCashChange, val result: LocalFoodPaymentResult.AttemptStarted) : FoodPaymentStartDraft
    class Refused(val proposal: ProposedOutcome.Reject) : FoodPaymentStartDraft
}
private inline fun foodPaymentStartDraft(block: () -> FoodPaymentStartDraft): FoodPaymentStartDraft = try { block() }
catch (_: FoodCapacityExceeded) { FoodPaymentStartDraft.Refused(foodPaymentReject("food.payment.capacity_required")) }
catch (_: LocalSalesWireCapacityExceeded) { FoodPaymentStartDraft.Refused(foodPaymentReject("food.payment.capacity_required")) }
catch (_: LocalSalesCapacityExceeded) { FoodPaymentStartDraft.Refused(foodPaymentReject("food.payment.capacity_required")) }
catch (failure: SalesRuleViolation) { FoodPaymentStartDraft.Refused(foodPaymentReject("food.payment.rule." + failure.rule.name.lowercase())) }

internal fun foodPaymentUnavailablePreparation(context: CommandContext, target: DomainId): PreparedMutation =
    FoodPaymentPrepared(context, SecurityAction.CASH_READ, target, Revision(0), context.originalActorId,
        "food.payment.unavailable", resources = setOf(orderResource(target), recordingAuthority(context.originalActorId))) { _, _ -> foodPaymentReject("food.payment.not_available") }

/** CLOSED typed effective outcome, never an Apply result or request flag masquerading as Start. */
internal fun foodPaymentPhysicalOutcome(normalized: StoredBytes, outcome: ProposedOutcome,
    context: CommandContext): PhysicalRecordingOutcome? {
    val request = LocalFoodPaymentCodec.decode(normalized)
    if (request is LocalFoodPaymentRequest.ApplyOriginal || request is LocalFoodPaymentRequest.ApplyResolution) return null
    if (outcome is ProposedOutcome.Reject) {
        check(LocalFoodPaymentCodec.result(outcome.result) is LocalFoodPaymentResult.Unavailable)
        return PhysicalRecordingOutcome.NonArming
    }
    val apply = outcome as ProposedOutcome.Apply
    return when (request) {
        is LocalFoodPaymentRequest.StartCash -> {
            val result = LocalFoodPaymentCodec.result(apply.result) as? LocalFoodPaymentResult.AttemptStarted ?: error("food.payment.typed_start_required")
            val change = apply.changes.single { it.key == foodPaymentSlotKey(request.recordingOperationId) }
            val slot = localSalesDecode(change.payload, LocalFoodPaymentSlot.serializer(), LocalFoodPaymentProfile.MAX_SLOT_BYTES)
            val requirement = slot.requirement()
            check(slot.target == request.target(slot.target.orderId, context.operationId) && slot.domain.amount == request.amount &&
                slot.domain.orderStorageRevision == request.expectedOrderStorageRevision && slot.domain.collectionRevision == request.expectedCollectionRevision &&
                slot.domain.sessionRevision == request.expectedSessionRevision && result.target == slot.target && result.amount == request.amount &&
                result.requirementDigest == sha256(requirement.canonicalBytes.copyBytes()) && apply.mustCommitBefore == slot.domain.stockDeadlineExclusive &&
                result.mustCommitBefore == apply.mustCommitBefore)
            val actual = foodPaymentStartSources(context, requirement) ?: error("food.payment.actual_sources_required")
            val gated = apply.changes.filter { it.key.kind in LocalFoodPaymentProfile.gatedKinds || it.key.kind == ReservedOperations.KIND }
            requireFoodPaymentExactChanges(gated, actual.changes)
            val claims = apply.claims.filter { it.key.namespace in LocalFoodPaymentProfile.gatedClaims }
            check(claims == actual.claims && apply.changes.none { it.key.kind in StockRelationalKindsV2.all || it.key.kind in LocalStockStoreProfile.inventoryKinds })
            PhysicalRecordingOutcome.Start(PhysicalRecordingStartKind.PAYMENT, actual.changes, actual.claims)
        }
        is LocalFoodPaymentRequest.RecordOriginal -> {
            check(context.operationId == request.target.recordingOperationId)
            val raw = localSalesDecode(apply.changes.single { it.key == foodPaymentRawKey(context.operationId) }.payload,
                LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
            check(raw.target == request.target && raw.body() == normalized && raw.originalActorId == context.originalActorId &&
                raw.recordingActorId == context.originalActorId && raw.bodyDigest == sha256(normalized.copyBytes()) && raw.semanticDigest == context.semanticDigest &&
                LocalFoodPaymentCodec.result(apply.result) == LocalFoodPaymentResult.RawRetained(request.target) && apply.claims.isEmpty() &&
                apply.jobs.isEmpty() && apply.mustCommitBefore == null)
            requireFoodPaymentExactChanges(apply.changes, listOf(foodPaymentRawWrite(raw), foodPaymentApplicationWrite(foodPaymentInitialApplication(raw), null)))
            PhysicalRecordingOutcome.FactImported(raw.bodyDigest, listOf(foodPaymentRawWrite(raw)))
        }
        is LocalFoodPaymentRequest.RecordResolution -> {
            check(context.operationId == request.target.resolutionOperationId && request.resolverActorId == context.originalActorId)
            val raw = localSalesDecode(apply.changes.single { it.key == foodPaymentResolutionKey(context.operationId) }.payload,
                LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
            check(raw.target == request.target && raw.body() == normalized && raw.recordingActorId == context.originalActorId &&
                raw.originalBodyDigest == request.originalBodyDigest && raw.bodyDigest == sha256(normalized.copyBytes()) &&
                raw.semanticDigest == context.semanticDigest && request.outcome !is LocalFoodCashOutcome.Unknown &&
                LocalFoodPaymentCodec.result(apply.result) == LocalFoodPaymentResult.RawRetained(request.target,
                    phase = LocalFoodPaymentFactPhase.RESOLUTION) && apply.claims.isEmpty() && apply.jobs.isEmpty() && apply.mustCommitBefore == null)
            requireFoodPaymentExactChanges(apply.changes, listOf(foodPaymentResolutionWrite(raw),
                foodPaymentApplicationWrite(foodPaymentInitialApplication(raw), null)))
            PhysicalRecordingOutcome.FactImported(raw.bodyDigest, listOf(foodPaymentResolutionWrite(raw)))
        }
        is LocalFoodPaymentRequest.ApplyOriginal, is LocalFoodPaymentRequest.ApplyResolution -> error("food.payment.nonphysical_action")
    }
}

/** Exact FOOD native tuple comparison, independent of any retail monetary profile. */
internal fun requireFoodPaymentExactChanges(actual: List<AggregateChange>, expected: List<AggregateChange>) {
    check(actual.size == expected.size)
    for ((value, wanted) in actual.zip(expected)) check(value.key == wanted.key && value.expectedRevision == wanted.expectedRevision &&
        value.schema == wanted.schema && value.payload == wanted.payload)
}
