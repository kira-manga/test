package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** One actual master transaction joins supplier obligation, real drawer custody and native originals. */
public class LocalSupplierCashHandler(private val stock: LocalStockRepository,
    private val participants: TransactionRetailJournalParticipantsSource = UnavailableRetailJournalParticipantsSource) : MasterCommandHandler {
    override val key: CommandKey = LocalSupplierCashProfile.COMMAND
    override val writableKinds: Set<String> = LocalSupplierSettlementProfile.kinds + LocalSupplierCashProfile.gatedKinds +
        LocalSupplierCashStorage.writableKinds + setOf(LocalSupplierCashProfile.APPLICATION, ReservedOperations.KIND)
    override val claimNamespaces: Set<String> = LocalSupplierCashProfile.gatedClaims + LocalSupplierCashStorage.claimNamespaces + LocalPurchasingProfile.DOCUMENT_CLAIM
    override val physicalRecordingKinds: Set<String> = LocalSupplierCashProfile.gatedKinds
    override val physicalRecordingClaimNamespaces: Set<String> = LocalSupplierCashProfile.gatedClaims
    override val physicalInteractiveAuthorizer: MasterPhysicalInteractiveAuthorizer = LocalSupplierCashInteractiveAuthorization(stock)
    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierCashCodec.normalize(input)
    override fun physicalRecordingAction(normalized: StoredBytes): PhysicalRecordingAction? = when (LocalSupplierCashCodec.decode(normalized)) {
        is LocalSupplierCashRequest.StartCash -> PhysicalRecordingAction.START_PAYMENT
        is LocalSupplierCashRequest.RecordOriginal -> PhysicalRecordingAction.RETAIN_ORIGINAL
        is LocalSupplierCashRequest.RecordResolution -> PhysicalRecordingAction.RETAIN_RESOLUTION
        is LocalSupplierCashRequest.ApplyOriginal, is LocalSupplierCashRequest.ApplyResolution -> null
    }
    override fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? = when (val request = LocalSupplierCashCodec.decode(normalized)) {
        is LocalSupplierCashRequest.RecordOriginal -> LocalSupplierCashJournal.fenceBinding(request.target)
        is LocalSupplierCashRequest.RecordResolution -> LocalSupplierCashJournal.fenceBinding(request.target, resolution = true)
        else -> null
    }
    override fun physicalRecordingOutcome(normalized: StoredBytes, effectiveOutcome: ProposedOutcome, context: CommandContext): PhysicalRecordingOutcome? =
        supplierPhysicalOutcome(normalized, effectiveOutcome, context)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        return when (val request = LocalSupplierCashCodec.decode(normalized)) {
            is LocalSupplierCashRequest.StartCash -> start(request, view, context)
            is LocalSupplierCashRequest.RecordOriginal -> raw(request.target, LocalSupplierCashPhase.ORIGINAL, normalized, view, context)
            is LocalSupplierCashRequest.RecordResolution -> raw(request.target, LocalSupplierCashPhase.RESOLUTION, normalized, view, context)
            is LocalSupplierCashRequest.ApplyOriginal -> apply(request.recordingOperationId, LocalSupplierCashPhase.ORIGINAL, view, context)
            is LocalSupplierCashRequest.ApplyResolution -> apply(request.resolutionOperationId, LocalSupplierCashPhase.RESOLUTION, view, context)
        }
    }

    private suspend fun start(request: LocalSupplierCashRequest.StartCash, view: CommandReadView, context: CommandContext): PreparedMutation {
        val commitment = supplierCashCommitment(request.source, request.settlementId, request.amount)
        if (context.operationId in setOf(request.recordingOperationId, request.resolutionOperationId)) return supplierUnavailable(context, request.source.supplierId)
        val target = request.target(context.operationId)
        val supplier = supplierSettlementSupplier(view, request.source.supplierId) ?: return supplierUnavailable(context, request.source.supplierId)
        val reader = PurchasingReader(view)
        val selected = (request.source as? LocalSupplierCashSource.Invoice)?.let { reader.row(LocalPurchasingProfile.INVOICE, it.invoiceId) }
        if (selected?.value?.body is PurchasingBody.InvoiceCapture) {
            val captureCut = try { reader.invoice(selected.row.key.id) }
                catch (_: PurchasingInvoiceCaptureUnqualified) { null }
                catch (_: PurchasingHydrationCapacityUnavailable) { null }
            if (captureCut == null || !purchasingCaptureSettlementEligible(captureCut))
                return supplierCaptureFinancialDeferral(context, commitment, reader, selected, context.originalActorId,
                    request.reasonCode, supplierSettlementResources(supplier, null, target, null))
        }
        try {
            val source = request.source
            val cut = (source as? LocalSupplierCashSource.Invoice)?.let { reader.invoice(it.invoiceId) }
            if (source is LocalSupplierCashSource.Invoice && cut == null) return supplierUnavailable(context, source.invoiceId)
            if (cut != null) requirePurchasingCrossAccounts(reader, cut, stock, view)
            val cash = LocalSupplierCashStorage.current(view, request.sessionId)
            val resources = supplierSettlementResources(supplier, cut, target, cash)
            fun refusal(code: String): PreparedMutation = SupplierCashPrepared(context, commitment, cash.session.custodianId,
                request.reasonCode, resources) { _, _ -> supplierReject(code) }
            if (cash.session.custodianId != context.originalActorId || cash.session.status != CashSessionStatus.OPEN)
                return refusal("purchasing.supplier_cash_current_custody_required")
            if (cut != null && cut.payable == null) return refusal("purchasing.recognized_payable_required")
            val basis = purchasingSupplierCashBasis(supplier.row, cut)
            val sourceMatches = purchasingSupplierSelectionMatches(request.source, basis) &&
                cut?.let { purchasingInvoiceClaim(it.invoice).supplierId == request.source.supplierId } != false
            val freeIdentity = supplierNewIdentity(reader, context.operationId, request.settlementId, request.allocationId) &&
                LocalSupplierCashStorage.requireUnusedSource(view, request.attemptId)
            val requirement = LocalSupplierCashJournal.requirement(view, context, participants, request, basis.digest(), resources)
                ?: return refusal("purchasing.supplier_cash_journal_required")
            val vacant = LocalSupplierCashJournal.vacant(view, requirement)
            val allResources = resources + LocalSupplierCashJournal.authorityResources(requirement)
            fun changed(command: SalesCommand): CashSession {
                purchaseRequire(supplier.value.active, "purchasing.supplier_required")
                purchaseRequire(sourceMatches && cash.sessionRow.revision == request.expectedSessionRevision &&
                    cash.drawerRevision == request.expectedDrawerRevision && cash.custodianRevision == request.expectedCustodianRevision &&
                    cash.workRevision == request.expectedWorkRevision && cash.session.availableCash == request.expectedAvailableCash,
                    "purchasing.stale_revision")
                purchaseRequire(freeIdentity && vacant, "purchasing.identity_already_used")
                if (cut != null) {
                    purchaseRequire(request.amount.minor <= checkNotNull(purchasingAvailableToAllocate(cut)).minor,
                        "purchasing.supplier_cash_amount_exceeds_available_due")
                    requireSupplierInvoiceCapacity(cut, addAttempt = true)
                }
                reader.requireSupplierHydrationCapacity(newTarget = target)
                // The request pins STORAGE generations; the domain receives the observed DOMAIN revision.
                return SupplierCashSettlements.reserve(command, commitment, cash.session, cash.session.revision, cash.assignments, request.attemptId)
            }
            return SupplierCashPrepared(context, commitment, cash.session.custodianId, request.reasonCode, allResources,
                admission = { command, _ -> changed(command); LocalSupplierCashJournal.admission(requirement, PhysicalRecordingAction.START_PAYMENT) }) { command, at ->
                val after = changed(command)
                val native = LocalSupplierCashJournal.startSources(context, requirement) ?: return@SupplierCashPrepared supplierReject("purchasing.supplier_cash_journal_required")
                supplierStartEffects(SupplierSettlementWrites(context, at, command.authorization), context.operationId, request,
                    supplier, cut, cash, after, native.slot, native.changes, native.claims)
            }
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            return SupplierCashPrepared(context, commitment, context.originalActorId, request.reasonCode,
                supplierSettlementResources(supplier, null, target, null)) { _, _ -> supplierReject("purchasing.capacity_unavailable") }
        } catch (unsupported: PurchasingInvoiceCaptureUnqualified) {
            return supplierCaptureFinancialDeferral(context, commitment, reader, unsupported.capture.invoice,
                context.originalActorId, request.reasonCode, supplierSettlementResources(supplier, null, target, null))
        } catch (_: LocalSupplierCashSourceUnavailable) { return supplierUnavailable(context, request.source.supplierId) }
    }

    private suspend fun raw(target: LocalSupplierCashTarget, phase: LocalSupplierCashPhase, normalized: StoredBytes,
        view: CommandReadView, context: CommandContext): PreparedMutation {
        if (context.operationId != target.factOperation(phase)) return supplierUnavailable(context, target.supplierId)
        val slot = LocalSupplierCashFacts.slot(view, target.recordingOperationId) ?: return supplierUnavailable(context, target.supplierId)
        requirePurchasingProducer(slot.start)
        if (slot.value.target != target || slot.value.originalActorId != context.originalActorId) return supplierUnavailable(context, target.supplierId)
        val requirement = slot.value.requirement()
        val resolve = phase == LocalSupplierCashPhase.RESOLUTION
        if (resolve) {
            val original = supplierCashFact(view, target.recordingOperationId, LocalSupplierCashPhase.ORIGINAL)
                ?: return supplierUnavailable(context, target.supplierId)
            val request = LocalSupplierCashCodec.decode(normalized) as LocalSupplierCashRequest.RecordResolution
            if (request.resolverActorId != context.originalActorId || request.originalBodyDigest != original.value.bodyDigest ||
                original.application.result?.let { it is LocalSupplierCashResult.Applied && it.action == LocalSupplierCashAction.UNKNOWN } != true)
                return supplierUnavailable(context, target.supplierId)
            LocalSupplierCashResolutionDescriptor.create(requirement, original.value.body(), context.originalActorId, normalized)
        } else LocalSupplierCashJournalDomain.classifyOriginal(requirement, normalized)
        val rawKey = if (resolve) LocalSupplierCashJournal.resolutionKey(context.operationId) else LocalSupplierCashJournal.rawKey(context.operationId)
        check(view.aggregate(rawKey) == null && view.aggregate(LocalSupplierCashJournal.applicationKey(context.operationId)) == null)
        val admission = LocalSupplierCashJournal.admission(requirement, if (resolve) PhysicalRecordingAction.RETAIN_RESOLUTION else PhysicalRecordingAction.RETAIN_ORIGINAL)
        val commitment = supplierCashCommitment(slot.value.domain.source, target.settlementId, slot.value.domain.amount)
        return SupplierCashPrepared(context, commitment, slot.value.originalActorId, slot.value.domain.reasonCode,
            slot.value.domain.financialResources.toSet() + LocalSupplierCashJournal.authorityResources(requirement), resolve,
            admission = { _, _ -> if (context.physicalParticipants?.let { it.authority == slot.value.domain.authority &&
                it.clientDeviceId == requirement.clientDeviceId && it.originalActorId == slot.value.originalActorId } == true) admission else null }) { _, at ->
            val native = context.physicalRecording
            if (native == null || !native.matches(admission) || native.sourceChanges.isNotEmpty()) return@SupplierCashPrepared supplierReject("purchasing.supplier_cash_journal_required")
            val raw = LocalSupplierCashFacts.raw(slot.value, phase, context.originalActorId, normalized, slot.row.digest, at)
            ProposedOutcome.Apply(LocalSupplierCashCodec.result(LocalSupplierCashResult.RawRetained(target, phase)),
                listOf(LocalSupplierCashFacts.rawWrite(raw), LocalSupplierCashFacts.applicationWrite(LocalSupplierCashFacts.initial(raw), null)))
        }
    }

    private suspend fun apply(operationId: OperationId, phase: LocalSupplierCashPhase, view: CommandReadView, context: CommandContext): PreparedMutation {
        val rawKey = if (phase == LocalSupplierCashPhase.ORIGINAL) LocalSupplierCashJournal.rawKey(operationId) else LocalSupplierCashJournal.resolutionKey(operationId)
        val header = view.aggregate(rawKey) ?: return supplierUnavailable(context, DomainId(operationId.value))
        val raw = LocalSupplierCashFacts.decodeRaw(header.payload)
        if (raw.originalActorId != context.originalActorId || raw.recordingActorId != context.originalActorId)
            return supplierUnavailable(context, DomainId(operationId.value))
        // The exact native-backed physical fact is retained even if financial qualification
        // is unavailable. Only the rich invoice/ledger join may authorize application.
        val physicalFact = LocalSupplierCashFacts.fact(view, operationId, phase) ?: error("Purchasing retained supplier fact unavailable")
        val slot = physicalFact.slot.value; val target = slot.target
        check(context.operationId !in setOf(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId))
        val resolve = phase == LocalSupplierCashPhase.RESOLUTION
        val commitment = supplierCashCommitment(slot.domain.source, target.settlementId, slot.domain.amount)
        val resources = slot.domain.financialResources.toSet() + LocalSupplierCashJournal.authorityResources(slot.requirement())
        val captureReader = PurchasingReader(view)
        val selected = target.invoiceId?.let { captureReader.row(LocalPurchasingProfile.INVOICE, it) }
        if (selected?.value?.body is PurchasingBody.InvoiceCapture) {
            val captureCut = try { captureReader.invoice(selected.row.key.id) }
                catch (_: PurchasingInvoiceCaptureUnqualified) { null }
                catch (_: PurchasingHydrationCapacityUnavailable) { null }
            if (captureCut == null || !purchasingCaptureSettlementEligible(captureCut))
                return supplierCaptureFinancialDeferral(context, commitment, captureReader, selected, slot.originalActorId,
                    slot.domain.reasonCode, resources, resolve)
        }
        fun pending(reason: LocalSupplierCashPendingReason, extra: Set<RecoveryResourceKey> = emptySet()): PreparedMutation =
            SupplierCashPrepared(context, commitment, slot.originalActorId, slot.domain.reasonCode, resources + extra, resolve) { _, _ ->
                ProposedOutcome.Reject("purchasing.supplier_cash_pending", LocalSupplierCashCodec.result(LocalSupplierCashResult.Pending(target, reason, phase))) }
        val fact = try { supplierCashFact(view, operationId, phase) ?: error("Purchasing retained supplier fact unavailable") }
            catch (unsupported: PurchasingInvoiceCaptureUnqualified) {
                return supplierCaptureFinancialDeferral(context, commitment, captureReader, unsupported.capture.invoice,
                    slot.originalActorId, slot.domain.reasonCode, resources, resolve)
            }
        fact.application.applicationOperationId?.let { applied -> return SupplierCashPrepared(context, commitment, slot.originalActorId,
            slot.domain.reasonCode, resources, resolve) { _, _ -> ProposedOutcome.Reject("purchasing.supplier_cash_already_applied",
                LocalSupplierCashCodec.result(LocalSupplierCashResult.AlreadyApplied(target, applied, phase))) } }
        val original = if (resolve) {
            try { supplierCashFact(view, target.recordingOperationId, LocalSupplierCashPhase.ORIGINAL)
                ?: return pending(LocalSupplierCashPendingReason.ORIGINAL_APPLICATION_REQUIRED) }
            catch (unsupported: PurchasingInvoiceCaptureUnqualified) {
                return supplierCaptureFinancialDeferral(context, commitment, captureReader, unsupported.capture.invoice,
                    slot.originalActorId, slot.domain.reasonCode, resources, resolve)
            }
        } else fact
        if (resolve && original.application.result?.let { it is LocalSupplierCashResult.Applied && it.action == LocalSupplierCashAction.UNKNOWN } != true)
            return pending(LocalSupplierCashPendingReason.ORIGINAL_APPLICATION_REQUIRED)
        val outcome = fact.outcome()
        val action = supplierCashKnownAction(outcome, slot.domain.amount) ?: return pending(LocalSupplierCashPendingReason.VALUE_OR_TENDER_REQUIRES_RECONCILIATION)
        try {
            val reader = PurchasingReader(view)
            val supplier = supplierSettlementSupplier(view, target.supplierId) ?: return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE)
            val cut = target.invoiceId?.let { reader.invoice(it) }
            if (target.invoiceId != null && cut == null) return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE)
            val attempt = reader.supplierAttempt(target.settlementId) ?: return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE)
            val before = (attempt.value.body as PurchasingBody.SettlementAttempt).value
            val cash = LocalSupplierCashStorage.current(view, target.sessionId)
            val extra = supplierSettlementResources(supplier, cut, target, cash)
            if (before.target != target || before.slotDigest != fact.value.slotDigest || before.amount != slot.domain.amount ||
                before.source != slot.domain.source || before.basisBefore.digest() != slot.domain.basisDigest ||
                cash.session.status != CashSessionStatus.OPEN || cash.session.custodianId != slot.originalActorId ||
                target.attemptId !in cash.session.pendingAttemptIds || cash.session.reservedCashOut.minor < before.amount.minor ||
                view.claim(LocalSupplierCashStorage.sourceClaim(target.attemptId)) != target.sessionId ||
                view.aggregate(LocalSupplierCashStorage.movementKey(target.attemptId)) != null ||
                (if (resolve) before.status != PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN || before.originalBodyDigest != original.value.bodyDigest
                else before.status != PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION))
                return pending(LocalSupplierCashPendingReason.SOURCE_OR_CUSTODY_CHANGED, extra)
            if (cut != null && (cut.settlements.reservedTotal.minor < before.amount.minor || checkNotNull(purchasingDue(cut)).remainingDue.minor < before.amount.minor))
                return pending(LocalSupplierCashPendingReason.SOURCE_OR_CUSTODY_CHANGED, extra)
            if (cut != null) requireSupplierInvoiceCapacity(cut, applying = before, action = action)
            if (reader.row(LocalSupplierSettlementProfile.EVENT, DomainId(context.operationId.value)) != null ||
                reader.row(LocalSupplierSettlementProfile.PAYMENT, target.settlementId) != null ||
                target.allocationId?.let { reader.row(LocalSupplierSettlementProfile.ALLOCATION, it) != null } == true)
                return pending(LocalSupplierCashPendingReason.SOURCE_OR_CUSTODY_CHANGED, extra)
            reader.requireSupplierHydrationCapacity(applying = before, action = action)
            val inputs = SupplierApplicationFact(slot, original.value, fact.value, PurchasingSource.from(original.row),
                if (resolve) PurchasingSource.from(fact.row) else null, fact.application, fact.applicationRow.revision)
            return SupplierCashPrepared(context, commitment, slot.originalActorId, slot.domain.reasonCode, resources + extra, resolve) { command, at ->
                val changed = if (action == LocalSupplierCashAction.UNKNOWN) null else SupplierCashSettlements.finish(command, commitment,
                    cash.session, cash.session.revision, cash.assignments, target.attemptId, action == LocalSupplierCashAction.CASH_PAID,
                    supplierEvidence(outcome).supplierDomain(slot.originalActorId), resolve)
                supplierApplyEffects(SupplierSettlementWrites(context, at, command.authorization), context.operationId, inputs, attempt,
                    supplier, cut, cash, changed, changed?.movement?.let { LocalSupplierCashStorage.movementWrite(context, it, at) })
            }
        } catch (_: PurchasingHydrationCapacityUnavailable) { return pending(LocalSupplierCashPendingReason.STORAGE_CAPACITY_REQUIRED) }
          catch (unsupported: PurchasingInvoiceCaptureUnqualified) {
              return supplierCaptureFinancialDeferral(context, commitment, captureReader, unsupported.capture.invoice,
                  slot.originalActorId, slot.domain.reasonCode, resources, resolve)
          }
          catch (_: LocalSupplierCashSourceUnavailable) { return pending(LocalSupplierCashPendingReason.SOURCE_UNAVAILABLE) }
          catch (rejected: PurchaseRejected) { return pending(LocalSupplierCashPendingReason.STORAGE_CAPACITY_REQUIRED) }
    }
}

internal class SupplierCashPrepared(private val context: CommandContext, private val commitment: SupplierCashCommitment,
    ownerId: DomainId, private val reason: String, resources: Set<RecoveryResourceKey>, resolution: Boolean = false,
    override val admissionRestriction: PreparedMutationAdmissionRestriction? = null,
    private val admission: ((SalesCommand, UtcInstant) -> PhysicalRecordingAdmission?)? = null,
    private val proposal: (SalesCommand, UtcInstant) -> ProposedOutcome) : PreparedMutation() {
    override val requirement: AuthorizationRequirement = supplierCashAuthorizationRequirement(context.scope, commitment,
        context.operationId, context.semanticDigest, reason, ownerId, resolution)
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId)
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = supplierCashBarriers
    private fun command(auth: AuthorizedAction, at: UtcInstant): SalesCommand {
        check(auth.principalKind == PrincipalKind.LOCAL_EMPLOYEE && auth.approvalId == null && auth.isBoundTo(requirement, context.originalActorId))
        return SalesCommand(context.operationId, auth, at.value, context.semanticDigest, reason)
    }
    override fun physicalRecordingAdmission(authorization: AuthorizedAction, recordedAt: UtcInstant): PhysicalRecordingAdmission? = try {
        check(admissionRestriction == null)
        admission?.invoke(command(authorization, recordedAt), recordedAt)
    } catch (_: PurchaseRejected) { null } catch (_: SalesRuleViolation) { null }
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = try {
        check(admissionRestriction == null)
        proposal(command(authorization, recordedAt), recordedAt)
    } catch (rejected: PurchaseRejected) { supplierReject(rejected.code) }
      catch (failure: SalesRuleViolation) { supplierReject("purchasing.supplier_cash_rule." + failure.rule.name.lowercase()) }
}

/** Unsupported claim finance defers before physical acquisition or a retained application result. */
private suspend fun supplierCaptureFinancialDeferral(context: CommandContext, commitment: SupplierCashCommitment,
    reader: PurchasingReader, row: PurchasingRow, ownerId: DomainId, reason: String,
    resources: Set<RecoveryResourceKey>, resolution: Boolean = false): PreparedMutation {
    val invoice = (row.value.body as PurchasingBody.InvoiceCapture).value
    requireCaptureFinancialDeferralSource(reader, row)
    return SupplierCashPrepared(context, commitment, ownerId, reason,
        resources + purchasingCaptureResources(context.scope, invoice) + priceResource(row.value.producer.actorId), resolution,
        admissionRestriction = PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE) { _, _ ->
        error("Unresolved capture cash action must be deferred before evaluation")
    }
}

internal fun supplierReject(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
    LocalSupplierCashCodec.result(LocalSupplierCashResult.Unavailable(code)))
internal fun supplierUnavailable(context: CommandContext, target: DomainId): PreparedMutation = object : PreparedMutation() {
    override val requirement: AuthorizationRequirement = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
        context.scope, ActionBinding(target, Revision(0), context.semanticDigest, context.operationId, "purchasing.supplier_cash_unavailable"))
    override val allowsActionApproval: Boolean = false
    override val affectedResources: Set<RecoveryResourceKey> = setOf(supplierSettlementResource(target), RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
    override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = supplierCashBarriers
    override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
        check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null && authorization.isBoundTo(requirement, context.originalActorId))
        return supplierReject("purchasing.supplier_cash_unavailable")
    }
}

internal suspend fun supplierNewIdentity(reader: PurchasingReader, operation: OperationId, settlementId: DomainId, allocationId: DomainId?): Boolean {
    for ((kind, id) in listOf(LocalSupplierSettlementProfile.ATTEMPT to settlementId, LocalSupplierSettlementProfile.PAYMENT to settlementId,
        LocalSupplierSettlementProfile.ADVANCE to settlementId, LocalSupplierSettlementProfile.ADVANCE_ALLOCATIONS to settlementId,
        LocalSupplierSettlementProfile.EVENT to DomainId(operation.value)) + listOfNotNull(allocationId?.let { LocalSupplierSettlementProfile.ALLOCATION to it }))
        if (reader.row(kind, id) != null) return false
    return true
}
/**
 * An invoice Start reserves one possible payment allocation and BOTH possible application
 * events (UNKNOWN original followed by a known fixed-ID resolution). This headroom is
 * derived from the genuine current attempts in the same atomic loan; later Starts and
 * nonphysical allocations cannot spend it. Apply consumes its own reservation only.
 */
internal fun requireSupplierInvoiceCapacity(cut: PurchasingInvoiceCut, addAttempt: Boolean = false,
    addAllocation: Boolean = false, applying: PurchasingSupplierAttempt? = null, action: LocalSupplierCashAction? = null) {
    check((applying == null) == (action == null) && (!addAttempt || !addAllocation && applying == null) &&
        (applying == null || !addAllocation && !addAttempt))
    val attempts = cut.settlements.attempts.map { (it.value.body as PurchasingBody.SettlementAttempt).value }
    if (applying != null) check(attempts.singleOrNull { it.target.settlementId == applying.target.settlementId } == applying)
    val remaining = attempts.map { attempt ->
        if (applying != null && attempt.target.settlementId == applying.target.settlementId) attempt.copy(status = when (checkNotNull(action)) {
            LocalSupplierCashAction.UNKNOWN -> PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN
            LocalSupplierCashAction.CASH_PAID -> PurchasingSupplierAttemptStatus.CASH_PAID
            LocalSupplierCashAction.NOT_PAID -> PurchasingSupplierAttemptStatus.NOT_PAID
        }) else attempt
    }
    val allocationsAfter = cut.settlements.allocations.size + if (addAllocation || action == LocalSupplierCashAction.CASH_PAID) 1 else 0
    purchaseRequire(supplierInvoiceHeadroomFits(cut.settlements.indexHistory.size + 1, allocationsAfter, remaining,
        newAttempt = addAttempt), "purchasing.capacity_required")
}

/** The retained postimage must still have capacity for every unresolved physical payout. */
internal fun supplierInvoiceHeadroomFits(historySize: Int, allocationSize: Int,
    attempts: Collection<PurchasingSupplierAttempt>, newAttempt: Boolean = false): Boolean {
    val futureAllocations = attempts.count { it.status == PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION ||
        it.status == PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN } + if (newAttempt) 1 else 0
    val futureEvents = attempts.sumOf { when (it.status) {
        PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION -> 2
        PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN -> 1
        PurchasingSupplierAttemptStatus.CASH_PAID, PurchasingSupplierAttemptStatus.NOT_PAID -> 0
    } } + if (newAttempt) 2 else 0
    return historySize >= 0 && allocationSize >= 0 &&
        historySize + futureEvents <= LocalSupplierSettlementProfile.MAX_INDEX_HISTORY &&
        allocationSize + futureAllocations <= LocalSupplierSettlementProfile.MAX_ENTRIES &&
        attempts.size + (if (newAttempt) 1 else 0) <= LocalSupplierSettlementProfile.MAX_ENTRIES
}

private fun supplierPhysicalOutcome(normalized: StoredBytes, outcome: ProposedOutcome, context: CommandContext): PhysicalRecordingOutcome? {
    val request = LocalSupplierCashCodec.decode(normalized)
    if (request is LocalSupplierCashRequest.ApplyOriginal || request is LocalSupplierCashRequest.ApplyResolution) return null
    if (outcome is ProposedOutcome.Reject) return PhysicalRecordingOutcome.NonArming
    val apply = outcome as ProposedOutcome.Apply
    check(apply.jobs.isEmpty() && apply.mustCommitBefore == null)
    return if (request is LocalSupplierCashRequest.StartCash) {
        val result = LocalSupplierCashCodec.result(apply.result) as LocalSupplierCashResult.AttemptStarted
        val slot = LocalSupplierCashJournal.decodeSlot(apply.changes.single { it.key == LocalSupplierCashJournal.slotKey(request.recordingOperationId) }.payload)
        check(slot.target == request.target(context.operationId) && result.target == slot.target && result.amount == request.amount &&
            result.requirementDigest == sha256(slot.requirement().canonicalBytes.copyBytes()) && result.revisions == supplierCashRevisions(apply.changes))
        val native = LocalSupplierCashJournal.startSources(context, slot.requirement()) ?: error("Purchasing actual native sources required")
        requireSupplierChanges(apply.changes.filter { it.key.kind in LocalSupplierCashProfile.gatedKinds || it.key.kind == ReservedOperations.KIND }, native.changes)
        check(apply.claims.filter { it.key.namespace in LocalSupplierCashProfile.gatedClaims } == native.claims)
        PhysicalRecordingOutcome.Start(PhysicalRecordingStartKind.PAYMENT, native.changes, native.claims)
    } else {
        val resolve = request is LocalSupplierCashRequest.RecordResolution
        val phase = if (resolve) LocalSupplierCashPhase.RESOLUTION else LocalSupplierCashPhase.ORIGINAL
        val raw = LocalSupplierCashFacts.decodeRaw(apply.changes.single {
            it.key == if (resolve) LocalSupplierCashJournal.resolutionKey(context.operationId) else LocalSupplierCashJournal.rawKey(context.operationId)
        }.payload)
        check(raw.body() == normalized && raw.phase == phase && raw.originalActorId == context.originalActorId && raw.recordingActorId == context.originalActorId &&
            raw.semanticDigest == context.semanticDigest && context.operationId == raw.target.factOperation(phase) &&
            LocalSupplierCashCodec.result(apply.result) == LocalSupplierCashResult.RawRetained(raw.target, phase) && apply.claims.isEmpty())
        requireSupplierChanges(apply.changes, listOf(LocalSupplierCashFacts.rawWrite(raw), LocalSupplierCashFacts.applicationWrite(LocalSupplierCashFacts.initial(raw), null)))
        PhysicalRecordingOutcome.FactImported(raw.bodyDigest, listOf(LocalSupplierCashFacts.rawWrite(raw)))
    }
}
internal fun requireSupplierChanges(actual: List<AggregateChange>, expected: List<AggregateChange>) {
    check(actual.size == expected.size)
    for ((a, b) in actual.zip(expected)) check(a.key == b.key && a.expectedRevision == b.expectedRevision && a.schema == b.schema && a.payload == b.payload)
}
