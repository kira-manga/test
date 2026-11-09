package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.recovery.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Exact core-native BEFORE cut joins; no historical CommandReadView or employee grant is made. */
public class LocalSupplierSettlementHistoryProducer(private val stock: LocalStockRepository) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalSupplierCashProfile.COMMAND, LocalSupplierSettlementProfile.ALLOCATE_ADVANCE)
    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Legacy) return false
        val original = (command.original as RetainedOriginalV2.Legacy).evidence
        requirePurchasingProducer(original)
        if (command.command == LocalSupplierSettlementProfile.ALLOCATE_ADVANCE) return allocate(command)
        return when (val request = LocalSupplierCashCodec.decode(command.normalized)) {
            is LocalSupplierCashRequest.StartCash -> start(command, original, request)
            is LocalSupplierCashRequest.RecordOriginal -> raw(command, original, request.target, LocalSupplierCashPhase.ORIGINAL)
            is LocalSupplierCashRequest.RecordResolution -> raw(command, original, request.target, LocalSupplierCashPhase.RESOLUTION)
            is LocalSupplierCashRequest.ApplyOriginal -> apply(command, request.recordingOperationId, LocalSupplierCashPhase.ORIGINAL)
            is LocalSupplierCashRequest.ApplyResolution -> apply(command, request.resolutionOperationId, LocalSupplierCashPhase.RESOLUTION)
        }
    }

    private suspend fun start(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalSupplierCashRequest.StartCash): Boolean {
        check(command.authority == RuntimeHistoricalAuthority.EMPLOYEE_COMMAND)
        val reader = PurchasingReader(command)
        val supplier = supplierSettlementSupplier(command, request.source.supplierId) ?: error("Original supplier unavailable")
        val cut = (request.source as? LocalSupplierCashSource.Invoice)?.let { reader.invoice(it.invoiceId) }
        check(supplier.value.active && (request.source !is LocalSupplierCashSource.Invoice || cut?.payable != null))
        if (cut != null) {
            check(purchasingInvoiceClaim(cut.invoice).supplierId == request.source.supplierId &&
                purchasingCaptureSettlementEligible(cut))
            requirePurchasingCrossAccounts(reader, cut, stock, command)
            check(request.amount.minor <= checkNotNull(purchasingAvailableToAllocate(cut)).minor)
            requireSupplierInvoiceCapacity(cut, addAttempt = true)
        }
        val basis = purchasingSupplierCashBasis(supplier.row, cut)
        check(purchasingSupplierSelectionMatches(request.source, basis))
        val cash = LocalSupplierCashStorage.historical(command, request.sessionId)
        check(cash.sessionRow.revision == request.expectedSessionRevision && cash.drawerRevision == request.expectedDrawerRevision &&
            cash.custodianRevision == request.expectedCustodianRevision && cash.workRevision == request.expectedWorkRevision &&
            cash.session.availableCash == request.expectedAvailableCash && cash.session.status == CashSessionStatus.OPEN && cash.session.custodianId == command.actorId)
        val commitment = supplierCashCommitment(request.source, request.settlementId, request.amount)
        command.requireRequirement(supplierCashAuthorizationRequirement(command.scope, commitment, command.operationId,
            command.semanticDigest, request.reasonCode, cash.session.custodianId))
        val slot = LocalSupplierCashFacts.requireStart(original)
        val requirement = slot.requirement()
        check(slot.domain.basisDigest == basis.digest() && slot.target == request.target(command.operationId))
        check(supplierNewIdentity(reader, command.operationId, request.settlementId, request.allocationId))
        for (key in LocalSupplierCashJournal.vacantKeys(requirement)) check(command.aggregateSource(key) == null)
        for (claim in LocalSupplierCashJournal.lineageClaims(requirement)) check(command.claim(claim.key) == null)
        // Core already joined both future IDs' authentic native BEFORE absence. A valid
        // later raw/resolution in this complete scan is not a strictly earlier source and
        // cannot be probed through earlierOriginal to establish Start-time vacancy.
        LocalSupplierCashStorage.requireUnusedSource(command, request.attemptId)
        reader.requireSupplierHydrationCapacity(newTarget = slot.target)
        val financial = supplierSettlementResources(supplier, cut, slot.target, cash)
        check(financial == slot.domain.financialResources.toSet())
        val after = SupplierCashSettlements.historicalReserveImage(cash.session, commitment, command.actorId, cash.assignments, request.attemptId)
        val native = original.aggregateWrites.filter { it.key.kind in LocalSupplierCashProfile.gatedKinds || it.key.kind == ReservedOperations.KIND }.map { write ->
            check(write.storageGeneration == Revision(1) && write.payloadDigest == sha256(write.payload.copyBytes()))
            AggregateChange(write.key, null, write.schema, write.payload)
        }
        val expected = supplierStartEffects(SupplierSettlementWrites(command), command.operationId, request, supplier, cut, cash, after, slot,
            native, LocalSupplierCashJournal.lineageClaims(requirement))
        command.requireLegacyEffects(expected, financial + LocalSupplierCashJournal.authorityResources(requirement))
        return true
    }

    private suspend fun raw(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence, target: LocalSupplierCashTarget,
        phase: LocalSupplierCashPhase): Boolean {
        val slot = LocalSupplierCashFacts.slot(command, target.recordingOperationId) ?: error("Original supplier native slot unavailable")
        requirePurchasingProducer(slot.start)
        check(slot.value.target == target && command.actorId == slot.value.originalActorId && command.operationId == target.factOperation(phase))
        val requirement = slot.value.requirement()
        val resolve = phase == LocalSupplierCashPhase.RESOLUTION
        if (resolve) {
            check(command.authority == RuntimeHistoricalAuthority.EMPLOYEE_COMMAND)
            val parent = supplierCashFact(command, target.recordingOperationId, LocalSupplierCashPhase.ORIGINAL) ?: error("Original UNKNOWN unavailable")
            check(parent.application.result?.let { it is LocalSupplierCashResult.Applied && it.action == LocalSupplierCashAction.UNKNOWN } == true)
            LocalSupplierCashResolutionDescriptor.create(requirement, parent.value.body(), command.actorId, command.normalized)
        } else LocalSupplierCashJournalDomain.classifyOriginal(requirement, command.normalized)
        check(command.aggregateSource(if (resolve) LocalSupplierCashJournal.resolutionKey(command.operationId) else LocalSupplierCashJournal.rawKey(command.operationId)) == null &&
            command.aggregateSource(LocalSupplierCashJournal.applicationKey(command.operationId)) == null)
        val raw = LocalSupplierCashFacts.raw(slot.value, phase, command.actorId, command.normalized, slot.row.digest, command.recordedAt)
        check(LocalSupplierCashFacts.requireRaw(original, slot.start, phase) == raw)
        val expected = ProposedOutcome.Apply(LocalSupplierCashCodec.result(LocalSupplierCashResult.RawRetained(target, phase)),
            listOf(LocalSupplierCashFacts.rawWrite(raw), LocalSupplierCashFacts.applicationWrite(LocalSupplierCashFacts.initial(raw), null)))
        val resources = slot.value.domain.financialResources.toSet() + LocalSupplierCashJournal.authorityResources(requirement)
        if (command.authority == RuntimeHistoricalAuthority.NATIVE_ORIGINAL) {
            check(!resolve)
            val native = checkNotNull(command.nativeOriginal)
            command.requireNativeOriginalRequirement(requirement.canonicalBytes)
            LocalSupplierCashFacts.requireStartCertificate(native.certificate, slot.start, requirement)
            LocalSupplierCashFacts.requireNativeOriginalEvidence(original, slot.start, slot.value)
            check(native.provenance == original.nativeOriginalProvenance && native.provenance.nativeOriginal.certificateDigest == sha256(native.certificate.copyBytes()))
            command.requireNativeOriginalEffects(expected, resources)
        } else {
            command.requireRequirement(supplierCashAuthorizationRequirement(command.scope,
                supplierCashCommitment(slot.value.domain.source, target.settlementId, slot.value.domain.amount), command.operationId,
                command.semanticDigest, slot.value.domain.reasonCode, slot.value.originalActorId, resolve))
            command.requireLegacyEffects(expected, resources)
        }
        return true
    }

    private suspend fun apply(command: RuntimeHistoricalCommand, operationId: OperationId, phase: LocalSupplierCashPhase): Boolean {
        check(command.authority == RuntimeHistoricalAuthority.EMPLOYEE_COMMAND)
        val reader = PurchasingReader(command)
        val fact = supplierCashFact(command, operationId, phase, reader) ?: error("Original supplier physical fact unavailable")
        val slot = fact.slot.value; val target = slot.target
        check(command.actorId == slot.originalActorId && fact.value.recordingActorId == command.actorId &&
            command.operationId !in setOf(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId) &&
            fact.application.applicationOperationId == null && fact.applicationRow.revision == Revision(1))
        val resolve = phase == LocalSupplierCashPhase.RESOLUTION
        val parent = if (resolve) supplierCashFact(command, target.recordingOperationId, LocalSupplierCashPhase.ORIGINAL, reader)
            ?: error("Original supplier UNKNOWN application unavailable") else fact
        if (resolve) check(parent.application.result?.let { it is LocalSupplierCashResult.Applied && it.action == LocalSupplierCashAction.UNKNOWN } == true)
        val action = checkNotNull(supplierCashKnownAction(fact.outcome(), slot.domain.amount))
        val supplier = supplierSettlementSupplier(command, target.supplierId) ?: error("Original supplier unavailable")
        val cut = target.invoiceId?.let { reader.invoice(it) }
        check(target.invoiceId == null || cut != null)
        val attempt = reader.supplierAttempt(target.settlementId) ?: error("Original supplier attempt unavailable")
        val before = (attempt.value.body as PurchasingBody.SettlementAttempt).value
        val cash = LocalSupplierCashStorage.historical(command, target.sessionId)
        check(before.target == target && before.slotDigest == fact.value.slotDigest && before.source == slot.domain.source &&
            before.amount == slot.domain.amount && before.basisBefore.digest() == slot.domain.basisDigest &&
            cash.session.status == CashSessionStatus.OPEN && cash.session.custodianId == slot.originalActorId &&
            target.attemptId in cash.session.pendingAttemptIds && cash.session.reservedCashOut.minor >= before.amount.minor &&
            command.claim(LocalSupplierCashStorage.sourceClaim(target.attemptId)) == target.sessionId &&
            command.aggregateSource(LocalSupplierCashStorage.movementKey(target.attemptId)) == null)
        check(if (resolve) before.status == PurchasingSupplierAttemptStatus.OUTCOME_UNKNOWN && before.originalBodyDigest == parent.value.bodyDigest
            else before.status == PurchasingSupplierAttemptStatus.AWAITING_ATTESTATION)
        if (cut != null) {
            check(purchasingInvoiceClaim(cut.invoice).supplierId == target.supplierId && purchasingCaptureSettlementEligible(cut) &&
                cut.settlements.reservedTotal.minor >= before.amount.minor && checkNotNull(purchasingDue(cut)).remainingDue.minor >= before.amount.minor)
            requireSupplierInvoiceCapacity(cut, applying = before, action = action)
        }
        val allocationId = target.allocationId
        check(reader.row(LocalSupplierSettlementProfile.EVENT, DomainId(command.operationId.value)) == null &&
            reader.row(LocalSupplierSettlementProfile.PAYMENT, target.settlementId) == null &&
            (allocationId == null || reader.row(LocalSupplierSettlementProfile.ALLOCATION, allocationId) == null))
        command.requireRequirement(supplierCashAuthorizationRequirement(command.scope, before.commitment(), command.operationId,
            command.semanticDigest, slot.domain.reasonCode, slot.originalActorId, resolve))
        reader.requireSupplierHydrationCapacity(applying = before, action = action)
        val changed = if (action == LocalSupplierCashAction.UNKNOWN) null else SupplierCashSettlements.historicalFinishImage(cash.session,
            before.commitment(), command.actorId, cash.assignments, target.attemptId, command.operationId, action == LocalSupplierCashAction.CASH_PAID,
            supplierEvidence(fact.outcome()).supplierDomain(command.actorId), slot.domain.reasonCode)
        val inputs = SupplierApplicationFact(slot, parent.value, fact.value, PurchasingSource.from(parent.row),
            if (resolve) PurchasingSource.from(fact.row) else null, fact.application, fact.applicationRow.revision)
        val expected = supplierApplyEffects(SupplierSettlementWrites(command), command.operationId, inputs, attempt, supplier, cut, cash, changed,
            changed?.movement?.let { LocalSupplierCashStorage.historicalMovementWrite(command, it) })
        val resources = slot.domain.financialResources.toSet() + LocalSupplierCashJournal.authorityResources(slot.requirement()) +
            supplierSettlementResources(supplier, cut, target, cash)
        command.requireLegacyEffects(expected, resources)
        return true
    }

    private suspend fun allocate(command: RuntimeHistoricalCommand): Boolean {
        check(command.authority == RuntimeHistoricalAuthority.EMPLOYEE_COMMAND)
        val request = LocalSupplierSettlementCodec.allocation(command.normalized)
        val reader = PurchasingReader(command)
        val cut = reader.invoice(request.invoice.invoiceId) ?: error("Original advance invoice unavailable")
        val invoice = purchasingInvoiceClaim(cut.invoice)
        check(purchasingCaptureSettlementEligible(cut))
        val supplier = supplierSettlementSupplier(command, invoice.supplierId) ?: error("Original advance supplier unavailable")
        val advance = reader.advance(request.advanceId) ?: error("Original source advance unavailable")
        reader.observeSupplierAdvanceDependencies(advance)
        requirePurchasingCrossAccounts(reader, cut, stock, command)
        command.requireRequirement(purchasingRequirement(command.scope, invoice.id, request.invoice.expectedProgressRevision,
            command.operationId, command.semanticDigest, request.reasonCode, invoice.total()))
        check(supplier.value.active && request.invoice.supplierId == invoice.supplierId &&
            (advance.advance.value.body as PurchasingBody.SupplierAdvance).value.supplierId == invoice.supplierId &&
            purchasingSupplierSelectionMatches(request.invoice, purchasingSupplierCashBasis(supplier.row, cut)) &&
            advance.advance.row.revision == request.expectedAdvanceRevision && advance.index.row.revision == request.expectedAdvanceAllocationRevision &&
            advance.unapplied == request.expectedUnappliedAdvance && request.amount.minor <= advance.unapplied.minor &&
            request.amount.minor <= checkNotNull(purchasingAvailableToAllocate(cut)).minor)
        check(reader.row(LocalSupplierSettlementProfile.ALLOCATION, request.allocationId) == null &&
            reader.row(LocalSupplierSettlementProfile.EVENT, DomainId(command.operationId.value)) == null && advance.allocations.size < LocalSupplierSettlementProfile.MAX_ENTRIES)
        requireSupplierInvoiceCapacity(cut, addAllocation = true)
        reader.requireSupplierHydrationCapacity()
        val resources = supplierAdvanceResources(cut, supplier, advance, request) + reader.supplierRecoveryResources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId)
        command.requireLegacyEffects(supplierAdvanceAllocationEffects(SupplierSettlementWrites(command), command.operationId, request, supplier, cut, advance), resources)
        return true
    }
}
