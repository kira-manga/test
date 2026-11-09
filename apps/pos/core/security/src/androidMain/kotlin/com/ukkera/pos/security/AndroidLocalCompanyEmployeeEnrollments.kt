package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicReference

/** Execute-only participant of the existing ORIGINAL native owner, registry, root and worker.
 * No caller-selected path/key/verifier, second executor, restored effect permit or head session. */
internal class AndroidLocalCompanyEmployeeEnrollments(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val environment: AndroidSelfOwnedEnvironment,
    private val registry: AndroidLocalProvisionerRegistry,
    private val keys: AndroidLocalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
    private val anchor: AndroidSelfOwnedHistoryAnchor,
    private val issuance: AndroidLocalCompanyIssuanceGate,
) {
    private val actionOwner = Any()
    private val authentication = AndroidLocalInstallerAuthentication(environment, registry, keys, work, issuance)
    private class DispatchEntry(val dispatch: NativeLocalEmployeeEnrollmentDispatch) {
        val permit = AtomicReference<NativeLocalEmployeeProvisioningPermit?>(null)
        val frame = AtomicReference<NativeEmployeeProvisioningFrame?>(null)
        val record = AtomicReference(dispatch.record)
        // Captured only by actual finishRoomFrameNow, after preparation and its native sample.
        val finalObservation = AtomicReference<LocalAuthenticationObservation?>(null)
    }
    private class ReceiptEntry(val permit: LocalProvisioningReceiptPermit, val record: NativeEmployeeEnrollmentRecordV1)
    private val dispatches = AtomicReference<List<DispatchEntry>>(emptyList())
    private val receipts = AtomicReference<List<ReceiptEntry>>(emptyList())
    private val custodies = AtomicReference<List<NativeEmployeeClientCustody>>(emptyList())
    private val allocations = AtomicReference<List<NativeEmployeeJournalAllocation>>(emptyList())
    private val openedAllocations = AtomicReference<List<NativeEmployeeJournalAllocation>>(emptyList())
    // Published only by the actual complete native read's close/reobserve/Room admission.
    // A parsed CONSUMED ledger, signed certificate, current account or scalar cut grants none.
    private val admittedHistory = AtomicReference<NativeEmployeeEnrollmentHistorySelection?>(null)

    fun selectOriginalHistory(original: OriginalNativeHistorySnapshot): NativeEmployeeEnrollmentHistorySelection {
        requireOriginal()
        anchor.requireSameOriginalCut(original)
        val state = ledger()
        val records = state?.records.orEmpty().filter { matchesOriginalHistoryTarget(it.target, original) }
        val consumed = records.filter { it.phase == LocalEmployeeEnrollmentPhase.CONSUMED }.sortedBy { checkNotNull(it.effect).child.historySequence }
        val parent = original.record.parent
        if (consumed.any { parent == null || checkNotNull(it.effect).child.historySequence > parent.historySequence })
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return NativeEmployeeEnrollmentHistorySelection(actionOwner, original, ledgerDigest(state), consumed, records)
    }

    fun reobserveOriginalHistory(selection: NativeEmployeeEnrollmentHistorySelection, original: OriginalNativeHistorySnapshot) {
        requireOriginal()
        if (selection.nativeOwner !== actionOwner || selection.original !== original || ledgerDigest(ledger()) != selection.ledgerDigest)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        selection.requireActualPrepass()
        anchor.requireSameOriginalCut(original)
    }

    /** Exact actual read delivery cut only. Called by native's existing Room-admission method,
     * never by a callback, imported receipt, detached source, Boolean or parsed snapshot. */
    fun admitOriginalHistory(selection: NativeEmployeeEnrollmentHistorySelection, afterClose: OriginalNativeHistorySnapshot) {
        work.requireActive()
        check(selection.nativeOwner === actionOwner && selection.original.ownerIdentity === afterClose.ownerIdentity &&
            selection.original.recordDigest == afterClose.recordDigest && selection.original.lifetimeId == afterClose.lifetimeId &&
            admittedHistory.get() == null && !owner.dataUseDeniedNoIo())
        selection.requireActualPrepass()
        check(admittedHistory.compareAndSet(null, selection))
    }

    /** Original signed late-phase connection DATA only. No fresh-head check, signing,
     * deadline extension, dispatch, effect permit, absence or custody is produced here. */
    fun recoverConnection(id: DomainId): LocalEmployeeEnrollmentAuthorityReceipt {
        requireOriginal()
        val actual = anchor.snapshotOriginal(); requireOperationalOriginal(actual)
        val record = currentOriginalRecord(id)
        if (record.phase !in setOf(LocalEmployeeEnrollmentPhase.DISPATCH_INTENT, LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT,
                LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED) || record.rootEndorsement.size != 64)
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val warm = dispatches.get().any { sameOriginalConnectionRecord(it.record.get(), record) }
        val cold = admittedHistory.get()?.retainedRecords?.any { sameOriginalConnectionRecord(it, record) } == true
        if ((!warm && !cold) || record.phase == LocalEmployeeEnrollmentPhase.CONSUMED && !consumedCompletionObserved(record))
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireTarget(record.target, actual, requirePreparationParent = false)
        return LocalEmployeeEnrollmentAuthorityReceipt(record)
    }

    fun retained(): List<LocalEmployeeEnrollmentReference> {
        requireOriginal()
        return ledger()?.records.orEmpty().map {
            val request = it.requested().request
            LocalEmployeeEnrollmentReference(it.originalActionId, request.requestId, request.targetId, it.phase)
        }
    }

    fun prepare(requested: LocalEmployeeEnrollmentDefinition, suppliedOriginal: OriginalNativeHistorySnapshot): LocalEmployeeEnrollmentAction {
        requireNewIssuance()
        val actual = anchor.requireSameOriginalCut(suppliedOriginal)
        requireOperationalOriginal(actual)
        val native = actual.record; val coordinates = native.allocation.coordinates; val request = requested.request
        if (request.scope != coordinates.scope || request.targetId == coordinates.firstActorId ||
            coordinates.deviceId !in requested.definition.admittedDeviceIds) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val state = ledger()
        requireNoUncertainOriginal(state)
        val previous = state?.records?.singleOrNull { it.requested().request.requestId == request.requestId }
        if (previous != null) {
            requireSameDefinition(previous, requested)
            if (previous.phase != LocalEmployeeEnrollmentPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            requireTarget(previous.target, actual, requirePreparationParent = true)
            return reviewRetained(previous)
        }
        if (state?.records?.any { it.requested().request.targetId == request.targetId } == true ||
            state?.records?.size == LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val original = requireOriginal(); val company = companyState(); val head = company.head
        if (!head.enabled) refuse(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val observation = owner.historyClockSampleNative().publicObservation()
        val elapsed = observation.moment.elapsedMillis
        if (elapsed > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val definition = checkNotNull(native.definition); val receipt = checkNotNull(native.receipt)
        val rootBytes = company.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val target = NativeEmployeeEnrollmentTargetV1(coordinates, native.allocation.digest, native.allocation.originalNativeBindingDigest,
            definition.definition.originalRequestId, definition.definitionDigest, receipt.digest, definition.definition.authorityEpoch,
            checkNotNull(native.parent), actual.recordDigest, original.original.rootId, original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId,
            rootDigest, original.original.actionId, original.headAuthorityId, original.headEnrollmentId,
            AndroidP256Primitives.rootFingerprint(keys.rootBody()))
        val reserved = original.allIds().toSet() + target.reservedOriginalIds() + state?.records.orEmpty().flatMap { it.nativeIds() } +
            registry.readBusinessPublications(keys)?.records.orEmpty().flatMap { listOf(it.originalActionId, it.keyId, it.publicationId, it.entityId) } +
            registry.devicePairingReservedIdentityIds(keys) // pairing-created identities only; not legacy original references
        if (request.requestId in reserved || request.targetId in reserved || request.requestId == request.targetId) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        // These ORIGINAL IDs are retained with the complete definition BEFORE head verification.
        val ids = List(4) { AndroidP256Primitives.newId() }
        if (ids.distinct().size != 4 || ids.any { it in reserved || it == request.requestId || it == request.targetId }) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = requested.copyWireBytes()
        val record = try { NativeEmployeeEnrollmentRecordV1(ids[0], ids[1], ids[2], ids[3], target, SelfOwnedOctetsV1(bytes),
            NativeEmployeeEnrollmentHeadV1(original.original.facts.companyName, head.name, head.login, head.accountRevision,
                head.credentialRevision, company.original.revision, environment.bootCount, observation,
                elapsed + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS), LocalEmployeeEnrollmentPhase.PREPARED) }
        finally { bytes.fill(0) }
        requireNewIssuance(); anchor.requireSameOriginalCut(actual)
        val retained = retain(state?.next(record) ?: NativeEmployeeEnrollmentStateV1(0L, listOf(record)), record.originalActionId)
        return reviewRetained(retained)
    }

    fun review(id: DomainId): LocalEmployeeEnrollmentAction {
        val state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUncertainOriginal(state)
        val record = state.records.singleOrNull { it.originalActionId == id } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != LocalEmployeeEnrollmentPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return reviewRetained(record)
    }

    private fun reviewRetained(record: NativeEmployeeEnrollmentRecordV1): LocalEmployeeEnrollmentAction {
        requireNewIssuance(); requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        requirePreparedHeadCurrent(record)
        return LocalEmployeeEnrollmentAction(actionOwner, record.originalActionId, record.requested(), record.preparedHead.companyName,
            record.preparedHead.headName, record.preparedHead.headLogin, record.target.rootFingerprint)
    }

    fun approve(action: LocalEmployeeEnrollmentAction, login: String, secret: CharArray): LocalEmployeeEnrollmentAuthorityReceipt {
        var state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        var record = state.records.singleOrNull { it.originalActionId == action.originalActionId }
            ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (action.owner !== actionOwner || action.rootFingerprint != record.target.rootFingerprint) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireSameDefinition(record, action.requested)
        // SIGNING_INTENT and every later phase are original-ID review/lookup ONLY. Never re-sign.
        requireNoUncertainOriginal(state)
        if (record.phase != LocalEmployeeEnrollmentPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNewIssuance(); requirePreparedHeadCurrent(record)
        requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        val actionBytes = NativeEmployeeEnrollmentWireV1.actionMessage(record)
        val actionDigest = try { sha256(actionBytes) } finally { actionBytes.fill(0) }
        val approval = authentication.authenticateEmployeeEnrollmentAction(record.originalActionId, actionDigest, login, secret,
            record.preparedHead.deadlineElapsedMillis)
        authentication.requireEmployeeEnrollmentStillCurrent(approval, companyState(), record.originalActionId, actionDigest)
        requirePreparedHeadCurrent(record); requireNewIssuance()
        record = record.copy(phase = LocalEmployeeEnrollmentPhase.SIGNING_INTENT,
            approvedHead = NativeEmployeeEnrollmentApprovalV1(approval.accountRevision, approval.credentialRevision,
                approval.retainedRevision, approval.boot, approval.expires))
        record = retain(state.next(record), record.originalActionId)
        state = checkNotNull(ledger())
        requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        authentication.requireEmployeeEnrollmentStillCurrent(approval, companyState(), record.originalActionId, actionDigest)
        requireNewIssuance()
        val signature = keys.signEmployeeEnrollmentEndorsement(record, approval)
        try {
            authentication.requireEmployeeEnrollmentStillCurrent(approval, companyState(), record.originalActionId, actionDigest)
            requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true); requireNewIssuance()
            record = retain(state.next(record.copy(phase = LocalEmployeeEnrollmentPhase.APPROVED,
                rootEndorsement = SelfOwnedOctetsV1(signature))), record.originalActionId)
            requireApprovedHeadCurrent(record); requireNewIssuance()
            return LocalEmployeeEnrollmentAuthorityReceipt(record)
        } finally { signature.fill(0) }
    }

    fun originalDefinition(requestId: DomainId, login: LocalLoginIdentifier): LocalEmployeeEnrollmentDefinition? {
        requireOriginal()
        val record = ledger()?.records?.singleOrNull { it.requested().request.requestId == requestId } ?: return null
        val requested = record.requested()
        return requested.takeIf { it.definition.login == login }
    }

    fun recoverApproval(id: DomainId): LocalEmployeeEnrollmentAuthorityReceipt {
        requireNewIssuance()
        val state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUncertainOriginal(state)
        val record = state.records.singleOrNull { it.originalActionId == id } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != LocalEmployeeEnrollmentPhase.APPROVED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        requireApprovedHeadCurrent(record)
        // Original-root signature/definition continuity was verified by the genuine registry
        // read. No new signature, IDs, approval purpose, password verification or SQL effects.
        return LocalEmployeeEnrollmentAuthorityReceipt(record)
    }

    fun beginDispatch(request: LocalProvisioningRequest): NativeLocalEmployeeEnrollmentDispatch {
        requireNewIssuance()
        val state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUncertainOriginal(state)
        val record = state.records.singleOrNull { it.requested().request == request } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != LocalEmployeeEnrollmentPhase.APPROVED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val actual = anchor.snapshotOriginal(); requireTarget(record.target, actual, requirePreparationParent = true)
        requireApprovedHeadCurrent(record)
        val observation = owner.historyClockSampleNative().publicObservation()
        requireObservation(record, observation)
        val dispatchId = AndroidP256Primitives.newId()
        val reserved = requireOriginal().allIds().toSet() + record.target.reservedOriginalIds() + state.records.flatMap { it.nativeIds() }
        if (dispatchId in reserved) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val retained = retain(state.next(record.copy(phase = LocalEmployeeEnrollmentPhase.DISPATCH_INTENT,
            dispatch = NativeEmployeeEnrollmentDispatchV1(dispatchId, actual.recordDigest, observation))), record.originalActionId)
        requireNewIssuance(); requireApprovedHeadCurrent(retained)
        val dispatch = NativeLocalEmployeeEnrollmentDispatch(actionOwner, Any(), owner, retained.originalActionId, request, retained)
        dispatches.set(dispatches.get() + DispatchEntry(dispatch))
        return dispatch
    }

    /** Read-only lookup. No forced dispatch, allocation, signature or credential work here. */
    fun authorizeEffect(dispatch: NativeLocalEmployeeEnrollmentDispatch, request: LocalProvisioningRequest): NativeLocalEmployeeProvisioningPermit? {
        val entry = dispatches.get().singleOrNull { it.dispatch === dispatch } ?: return null
        if (dispatch.owner !== actionOwner || dispatch.controller !== owner || dispatch.request != request) return null
        val record = currentOriginalRecord(dispatch.originalActionId)
        if (record != entry.record.get() || record.phase != LocalEmployeeEnrollmentPhase.DISPATCH_INTENT || entry.frame.get() != null) return null
        requireNewIssuance(); requireApprovedHeadCurrent(record); requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        return entry.permit.get() ?: NativeLocalEmployeeProvisioningPermit(actionOwner, dispatch, owner,
            LocalEmployeeEnrollmentAuthorityReceipt(record)).also { check(entry.permit.compareAndSet(null, it)) }
    }

    fun bindFrame(native: SelfOwnedNativeRoomFrame, permit: NativeLocalEmployeeProvisioningPermit): NativeEmployeeProvisioningFrame {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val record = currentOriginalRecord(permit.approval.originalActionId)
        if (permit.owner !== actionOwner || record != entry.record.get() || record.phase != LocalEmployeeEnrollmentPhase.DISPATCH_INTENT ||
            entry.frame.get() != null || native.preparedChild != null || !native.nativeBegan || native.finalized || native.consumed ||
            native.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNewIssuance(); requireTarget(record.target, native.original, requirePreparationParent = true)
        anchor.requireSameOriginalCut(native.original); requireApprovedHeadCurrent(record)
        if (native.original.recordDigest != checkNotNull(record.dispatch).originalAnchorDigest) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val observation = owner.historyClockSampleNative().publicObservation(); requireObservation(record, observation)
        val frame = NativeEmployeeProvisioningFrame(actionOwner, owner, native, permit, observation)
        check(entry.frame.compareAndSet(null, frame)); return frame
    }

    fun retainPreparedEffect(frame: NativeEmployeeProvisioningFrame, intent: NativeHistoryOriginalIntentV2,
        evidence: LocalEmployeeEnrollmentEffectEvidence) {
        val entry = requireEntry(frame); val before = currentOriginalRecord(entry.dispatch.originalActionId)
        if (before != entry.record.get() || before.phase != LocalEmployeeEnrollmentPhase.DISPATCH_INTENT) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireNewIssuance(); requireApprovedHeadCurrent(before)
        val native = frame.native; val cut = anchor.snapshotOriginal(); val pending = checkNotNull(cut.record.pending)
        val child = checkNotNull(native.preparedChild)
        if (native.consumed || native.finalized || child != pending.child || native.preparedIntentDigest == null || !sameIntent(pending.originalIntent, intent) ||
            native.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN || child.historySequence != before.target.preparationParent.historySequence + 1L ||
            cut.record.parent != before.target.preparationParent || child.commandEpoch != before.target.authorityEpoch.value ||
            child.commandSequence != before.target.preparationParent.commandSequence || child.commandDigest != before.target.preparationParent.commandDigest ||
            child.installationGenesisDigest != before.target.preparationParent.installationGenesisDigest ||
            child.roleCommitment != before.target.preparationParent.roleCommitment || child.recoveryCommitment != before.target.preparationParent.recoveryCommitment ||
            intent.purposeTag != EMPLOYEE_PROVISIONING_PURPOSE_TAG || intent.originalRequestId != entry.dispatch.request.requestId ||
            intent.originalRequestDigest != entry.dispatch.request.contentDigest || intent.originalActorId != null || intent.originalOperationId != null || intent.resources.isNotEmpty() ||
            evidence.request != entry.dispatch.request || evidence.definitionDigest != frame.requested.definitionDigest ||
            evidence.authorityDigest != frame.authorityDigest || evidence.nativeBindingDigest != frame.nativeBindingDigest ||
            evidence.protectedPreflight.issuerContextId != frame.bindingObservation.issuerContextId ||
            evidence.protectedPreflight.moment.bootId != frame.bindingObservation.moment.bootId ||
            evidence.eventAt.elapsedMillis < frame.bindingObservation.moment.elapsedMillis ||
            evidence.protectedPreflight.moment.elapsedMillis < frame.bindingObservation.moment.elapsedMillis ||
            evidence.admissionDigests.map { it.first } != frame.requested.admissions.map { it.deviceId }) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        requireObservation(before, evidence.protectedPreflight)
        val dependencies = localEmployeeEnrollmentDependenciesV1(evidence); val facts = evidence.copyWireBytes()
        try {
            if (sha256(dependencies) != intent.dependenciesDigest || sha256(facts) != intent.originalFactsDigest) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
            val record = before.copy(phase = LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT,
                effect = NativeEmployeeEnrollmentEffectV1(native.frameId, child, checkNotNull(native.preparedIntentDigest),
                    intent.dependenciesDigest, intent.originalFactsDigest, SelfOwnedOctetsV1(facts)))
            entry.record.set(retain(checkNotNull(ledger()).next(record), record.originalActionId))
            requireApprovedHeadCurrent(record); requireNewIssuance()
        } finally { dependencies.fill(0); facts.fill(0) }
    }

    /** Called directly by ACTUAL native finishNow after its last genuine native observation.
     * All registry/key/head IO has already finished; only bounded pure checks and capture follow. */
    fun finishNativeNow(native: SelfOwnedNativeRoomFrame, sample: AndroidSelfOwnedNativeClockSample) {
        val entry = dispatches.get().singleOrNull { it.frame.get()?.native === native } ?: return
        val frame = checkNotNull(entry.frame.get()); val record = entry.record.get()
        if (native.preparedChild == null) {
            // A real no-write Room transaction can COMMIT. Its actual no-child/unchanged
            // outer settlement below, not this final sample or a supplied result, proves absence.
            check(record.phase == LocalEmployeeEnrollmentPhase.DISPATCH_INTENT && record.effect == null &&
                native.preparedIntentDigest == null && entry.finalObservation.get() == null)
            return
        }
        if (record.phase != LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT || record.effect?.child != native.preparedChild ||
            record.effect?.originalIntentDigest != native.preparedIntentDigest || entry.finalObservation.get() != null ||
            !owner.newCeremoniesAllowedNoIo() || issuance.denied()) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val observation = sample.publicObservation(); requireObservation(record, observation)
        if (observation.issuerContextId != frame.bindingObservation.issuerContextId ||
            observation.moment.bootId != frame.bindingObservation.moment.bootId ||
            observation.moment.elapsedMillis < frame.bindingObservation.moment.elapsedMillis ||
            frame.tokenWorkLifetimeMillis > Long.MAX_VALUE - observation.moment.elapsedMillis) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        // No renewal, provider callback, wall API or caller supplied success/fate Boolean.
        check(entry.finalObservation.compareAndSet(null, observation))
    }

    /** Native calls this ONLY after actual driver release, outer cleanup and anchor settlement. */
    fun settleAfterActualOuter(native: SelfOwnedNativeRoomFrame, settlement: SelfOwnedNativeRoomSettlement) {
        val entry = dispatches.get().singleOrNull { it.frame.get()?.native === native } ?: return
        check(native.consumed && settlement.disposition == native.nativeDisposition && settlement.disposition != SelfOwnedNativeSqlDisposition.UNKNOWN)
        val before = currentOriginalRecord(entry.dispatch.originalActionId)
        check(before == entry.record.get())
        val committed = settlement.disposition == SelfOwnedNativeSqlDisposition.COMMITTED && native.preparedChild != null
        val terminal = if (committed) {
            check(before.phase == LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT && native.finalized &&
                before.effect?.child == native.preparedChild && settlement.original.record.parent == native.preparedChild &&
                settlement.original.record.pending == null)
            NativeEmployeeEnrollmentTerminalV1(NativeEmployeeEnrollmentTerminalKindV1.COMMITTED_JOINED_CHILD,
                settlement.original.recordDigest, checkNotNull(entry.finalObservation.get()))
        } else {
            check((settlement.disposition in setOf(SelfOwnedNativeSqlDisposition.NOT_SUBMITTED, SelfOwnedNativeSqlDisposition.ROLLED_BACK) ||
                settlement.disposition == SelfOwnedNativeSqlDisposition.COMMITTED && native.preparedChild == null && native.preparedIntentDigest == null &&
                    before.phase == LocalEmployeeEnrollmentPhase.DISPATCH_INTENT && before.effect == null && native.finalized) &&
                settlement.original.record.pending == null && settlement.original.record.parent == before.target.preparationParent &&
                (native.preparedChild != null || settlement.original.recordDigest == checkNotNull(before.dispatch).originalAnchorDigest))
            entry.finalObservation.set(null)
            NativeEmployeeEnrollmentTerminalV1(NativeEmployeeEnrollmentTerminalKindV1.KNOWN_NO_EMPLOYEE_EFFECT,
                settlement.original.recordDigest, null)
        }
        val record = before.copy(phase = if (committed) LocalEmployeeEnrollmentPhase.CONSUMED else LocalEmployeeEnrollmentPhase.REJECTED, terminal = terminal)
        entry.record.set(retain(checkNotNull(ledger()).next(record), record.originalActionId))
    }

    /** Native terminalizes only an exact unused dispatch at its unchanged original cut,
     * with no actual/bound frame or pending child. This withdraws later effect eligibility;
     * it never proves endpoint/OS cleanup. The real service waits its exact cleanup first. */
    fun finishKnownNoEffect(dispatch: NativeLocalEmployeeEnrollmentDispatch): Boolean {
        val entry = dispatches.get().singleOrNull { it.dispatch === dispatch } ?: return false
        val before = currentOriginalRecord(dispatch.originalActionId)
        if (entry.frame.get() != null || before != entry.record.get() || before.phase != LocalEmployeeEnrollmentPhase.DISPATCH_INTENT) return false
        val actual = anchor.snapshotOriginal()
        if (actual.record.pending != null || actual.recordDigest != checkNotNull(before.dispatch).originalAnchorDigest ||
            actual.record.parent != before.target.preparationParent) return false
        val record = before.copy(phase = LocalEmployeeEnrollmentPhase.REJECTED, terminal = NativeEmployeeEnrollmentTerminalV1(
            NativeEmployeeEnrollmentTerminalKindV1.KNOWN_NO_EMPLOYEE_EFFECT, actual.recordDigest, null))
        entry.record.set(retain(checkNotNull(ledger()).next(record), record.originalActionId)); return true
    }

    fun lookupReceipt(request: LocalProvisioningRequest): LocalProvisioningReceiptPermit? {
        requireOriginal(); requireOperationalOriginal(anchor.snapshotOriginal())
        val record = ledger()?.records?.singleOrNull { it.requested().request == request } ?: return null
        if (record.phase !in setOf(LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED)) return null
        if (record.phase == LocalEmployeeEnrollmentPhase.CONSUMED && !consumedCompletionObserved(record))
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val retained = receipts.get()
        retained.singleOrNull { it.record.originalActionId == record.originalActionId }?.let { existing ->
            // One original terminal receipt slot, never an effect permit. STARTED is ignored
            // only after every immutable consumed completion field has matched exactly.
            val same = if (record.phase == LocalEmployeeEnrollmentPhase.CONSUMED)
                sameConsumedCompletion(existing.record, record) else existing.record == record
            if (!same || existing.permit.request != request) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return existing.permit
        }
        if (retained.size >= LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS) refuse(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val eventDigest = if (record.phase == LocalEmployeeEnrollmentPhase.CONSUMED) effectEvidence(record).images.last().plaintextDigest else null
        return LocalProvisioningReceiptPermit(actionOwner, owner, record.originalActionId, request, eventDigest,
            record.phase == LocalEmployeeEnrollmentPhase.REJECTED).also { receipts.set(retained + ReceiptEntry(it, record)) }
    }

    fun acquireConsumedEmployee(requestId: DomainId): NativeEmployeeClientCustody {
        val actual = anchor.snapshotOriginal(); requireOperationalOriginal(actual)
        val record = ledger()?.records?.singleOrNull { it.requested().request.requestId == requestId }
            ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        // Warm custody still requires the actual dispatch/frame/final COMMITTED completion.
        // Cold custody additionally requires this owner's exact native seed traversal, complete
        // semantic scan and actual close/reobserve/Room admission. Parsed ledger DATA grants none.
        if (record.phase != LocalEmployeeEnrollmentPhase.CONSUMED || !consumedCompletionObserved(record))
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireTarget(record.target, actual, requirePreparationParent = false)
        custodies.get().singleOrNull { sameConsumedCompletion(it.record, record) }?.let { return it }
        return NativeEmployeeClientCustody(actionOwner, owner, record).also { custodies.set(custodies.get() + it) }
    }

    fun acquireJournalAction(custody: NativeEmployeeClientCustody, action: NativeEmployeeClientJournalAction): NativeEmployeeJournalAllocation {
        if (!custodyCurrent(custody)) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        var record = currentOriginalRecord(custody.originalActionId)
        if (record.phase != LocalEmployeeEnrollmentPhase.CONSUMED) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (action == NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE) {
            if (record.journalEnrollmentStarted) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            // Irreversible STARTED reservation precedes any client native-key/file creation.
            record = retain(checkNotNull(ledger()).next(record.copy(journalEnrollmentStarted = true)), record.originalActionId)
        } else if (!record.journalEnrollmentStarted) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return NativeEmployeeJournalAllocation(actionOwner, custody, action, owner).also { value ->
            // Prior allocation currentness is withdrawn before a second existing-only owner
            // attempt. The concrete client owner still retains its OS lease until actual close.
            allocations.set(allocations.get().filterNot { it.custody === custody } + value)
            openedAllocations.set(openedAllocations.get().filterNot { it.custody === custody })
        }
    }

    fun claimJournalOpen(allocation: NativeEmployeeJournalAllocation) {
        if (!allocationCurrent(allocation) || openedAllocations.get().any { it === allocation }) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        // Native allocation/STARTED was already forced. This exact-object one-shot is claimed
        // before ANY client-native open; absence/partial state can never reuse ENROLL.
        openedAllocations.set(openedAllocations.get() + allocation)
    }
    fun journalOpenClaimed(allocation: NativeEmployeeJournalAllocation): Boolean = noThrowCurrent {
        allocationCurrent(allocation) && openedAllocations.get().any { it === allocation }
    }

    fun effectPermitCurrent(permit: NativeLocalEmployeeProvisioningPermit): Boolean = noThrowCurrent {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: return@noThrowCurrent false
        val record = entry.record.get()
        permit.owner === actionOwner && permit.controller === owner && entry.dispatch === permit.dispatch &&
            record.phase in setOf(LocalEmployeeEnrollmentPhase.DISPATCH_INTENT, LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT) &&
            entry.frame.get()?.native?.consumed != true && owner.newCeremoniesAllowedNoIo() && !issuance.denied() &&
            work.clock() < checkNotNull(record.approvedHead).deadlineElapsedMillis && environment.bootCount == record.approvedHead.osBootCount
    }
    fun ownCommitCurrent(permit: NativeLocalEmployeeProvisioningPermit): Boolean = noThrowCurrent {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: return@noThrowCurrent false
        val record = entry.record.get()
        permit.owner === actionOwner && record.phase == LocalEmployeeEnrollmentPhase.CONSUMED &&
            entry.frame.get()?.native?.consumed == true && entry.finalObservation.get() == record.terminal?.finalObservation
    }
    fun receiptCurrent(permit: LocalProvisioningReceiptPermit): Boolean = noThrowCurrent {
        permit.owner === actionOwner && receipts.get().any { it.permit === permit && it.record.requested().request == permit.request &&
            it.record.phase in setOf(LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED) }
    }
    fun custodyCurrent(custody: NativeEmployeeClientCustody): Boolean = noThrowCurrent {
        custody.owner === actionOwner && custody.controller === owner && custodies.get().any { it === custody } &&
            custody.record.phase == LocalEmployeeEnrollmentPhase.CONSUMED
    }
    fun allocationCurrent(allocation: NativeEmployeeJournalAllocation): Boolean = noThrowCurrent {
        allocation.owner === actionOwner && allocation.controller === owner && custodyCurrent(allocation.custody) && allocations.get().any { it === allocation }
    }

    private fun consumedCompletionObserved(record: NativeEmployeeEnrollmentRecordV1): Boolean {
        if (record.phase != LocalEmployeeEnrollmentPhase.CONSUMED) return false
        val warm = dispatches.get().singleOrNull { sameConsumedCompletion(it.record.get(), record) && it.frame.get()?.native?.consumed == true }
        if (warm != null && warm.finalObservation.get() == record.terminal?.finalObservation) return true
        val history = admittedHistory.get() ?: return false
        return history.nativeOwner === actionOwner && history.sources.any { sameConsumedCompletion(it.record, record) }
    }
    private fun sameOriginalConnectionRecord(left: NativeEmployeeEnrollmentRecordV1, right: NativeEmployeeEnrollmentRecordV1): Boolean =
        if (right.phase == LocalEmployeeEnrollmentPhase.CONSUMED) sameConsumedCompletion(left, right) else left == right
    private fun ledgerDigest(state: NativeEmployeeEnrollmentStateV1?): PayloadDigest? {
        if (state == null) return null
        val bytes = NativeEmployeeEnrollmentWireV1.encode(state)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    /** Pure immutable original-target comparison for read DATA, without RUN/head/session
     * freshness. The actual source registry read already verified the unchanged original root. */
    private fun matchesOriginalHistoryTarget(target: NativeEmployeeEnrollmentTargetV1, original: OriginalNativeHistorySnapshot): Boolean {
        val native = original.record
        val definition = native.definition ?: return false
        val expected = NativeEmployeeEnrollmentWireV1.targetBytes(target)
        val joined = NativeEmployeeEnrollmentWireV1.targetBytes(target.copy(coordinates = native.allocation.coordinates))
        val sameCoordinates = try { expected.contentEquals(joined) } finally { expected.fill(0); joined.fill(0) }
        return sameCoordinates && native.allocation.digest == target.allocationDigest &&
            native.allocation.originalNativeBindingDigest == target.bindingDigest &&
            definition.definition.originalRequestId == target.installationRequestId && definition.definitionDigest == target.installationDefinitionDigest &&
            native.receipt?.digest == target.installationReceiptDigest && definition.definition.authorityEpoch == target.authorityEpoch
    }

    private fun sameIntent(left: NativeHistoryOriginalIntentV2, right: NativeHistoryOriginalIntentV2): Boolean {
        val a = SelfOwnedNativeHistoryWireV2.encodeIntent(left); val b = SelfOwnedNativeHistoryWireV2.encodeIntent(right)
        return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
    }
    /** STARTED is later append-only journal administration; it cannot erase or replace the
     * genuinely observed enrollment completion/custody. All other retained fields are exact. */
    private fun sameConsumedCompletion(left: NativeEmployeeEnrollmentRecordV1, right: NativeEmployeeEnrollmentRecordV1): Boolean =
        left.phase == LocalEmployeeEnrollmentPhase.CONSUMED && right.phase == LocalEmployeeEnrollmentPhase.CONSUMED &&
            left.copy(journalEnrollmentStarted = false) == right.copy(journalEnrollmentStarted = false)
    private fun requireEntry(frame: NativeEmployeeProvisioningFrame): DispatchEntry = dispatches.get().singleOrNull {
        it.frame.get() === frame && frame.owner === actionOwner && frame.controller === owner && it.permit.get() === frame.permit
    } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun requirePreparedHeadCurrent(record: NativeEmployeeEnrollmentRecordV1) {
        val current = companyState(); val head = current.head; val retained = record.preparedHead
        if (!head.enabled || head.accountRevision != retained.accountRevision || head.credentialRevision != retained.credentialRevision ||
            head.name != retained.headName || head.login != retained.headLogin || current.original.revision < retained.retainedRevision ||
            environment.bootCount != retained.osBootCount || work.clock() >= retained.deadlineElapsedMillis) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireApprovedHeadCurrent(record: NativeEmployeeEnrollmentRecordV1) {
        requirePreparedHeadCurrent(record)
        val current = companyState(); val head = current.head; val approved = checkNotNull(record.approvedHead)
        if (head.accountRevision != approved.accountRevision || head.credentialRevision != approved.credentialRevision ||
            current.original.revision < approved.retainedRevision || environment.bootCount != approved.osBootCount ||
            work.clock() >= approved.deadlineElapsedMillis || head.reservationId != null || head.failedAttempts != 0) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireObservation(record: NativeEmployeeEnrollmentRecordV1, observed: LocalAuthenticationObservation) {
        val prepared = record.preparedHead; val approved = record.approvedHead
        if (observed.issuerContextId != prepared.observation.issuerContextId || observed.moment.bootId != prepared.observation.moment.bootId ||
            observed.moment.elapsedMillis < prepared.observation.moment.elapsedMillis ||
            observed.moment.elapsedMillis >= (approved?.deadlineElapsedMillis ?: prepared.deadlineElapsedMillis) ||
            environment.bootCount != prepared.osBootCount) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireTarget(target: NativeEmployeeEnrollmentTargetV1, actual: OriginalNativeHistorySnapshot, requirePreparationParent: Boolean) {
        requireOperationalOriginal(actual)
        val native = actual.record; val definition = checkNotNull(native.definition); val c = native.allocation.coordinates
        val expectedCoordinates = NativeEmployeeEnrollmentWireV1.targetBytes(target)
        val joinedCoordinates = NativeEmployeeEnrollmentWireV1.targetBytes(target.copy(coordinates = c))
        try { if (!expectedCoordinates.contentEquals(joinedCoordinates)) refuse(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { expectedCoordinates.fill(0); joinedCoordinates.fill(0) }
        if (native.allocation.digest != target.allocationDigest || native.allocation.originalNativeBindingDigest != target.bindingDigest ||
            definition.definition.originalRequestId != target.installationRequestId || definition.definitionDigest != target.installationDefinitionDigest ||
            native.receipt?.digest != target.installationReceiptDigest || definition.definition.authorityEpoch != target.authorityEpoch ||
            (requirePreparationParent && (native.parent != target.preparationParent || actual.recordDigest != target.preparationAnchorDigest))) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
    }
    private fun requireOperationalOriginal(actual: OriginalNativeHistorySnapshot) {
        val native = actual.record
        if (native.database == null || native.parent == null || native.pending != null || native.issuerAcknowledgement == null ||
            native.attempt?.phase != NativeHistoryAttemptPhaseV2.COMPLETED || native.roleIntent?.role != NativeHistoryDesiredRoleV2.RUN)
            refuse(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    }
    private fun requireSameDefinition(record: NativeEmployeeEnrollmentRecordV1, requested: LocalEmployeeEnrollmentDefinition) {
        val bytes = requested.copyWireBytes()
        try { if (!record.definition.same(bytes)) refuse(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { bytes.fill(0) }
    }
    private fun requireNoUncertainOriginal(state: NativeEmployeeEnrollmentStateV1?) {
        if (state?.records?.any { it.phase in setOf(LocalEmployeeEnrollmentPhase.SIGNING_INTENT,
            LocalEmployeeEnrollmentPhase.DISPATCH_INTENT, LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT) } == true)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    private fun effectEvidence(record: NativeEmployeeEnrollmentRecordV1): LocalEmployeeEnrollmentEffectEvidence {
        val bytes = checkNotNull(record.effect).evidence.copyBytes()
        return try { LocalEmployeeEnrollmentEffectEvidence.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun companyState(): LocalCompanyAuthorityStateV1 = registry.readCompanyState(keys)
        ?: refuse(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    private fun requireOriginal(): LocalCompanyAuthorityBootstrapV1 {
        work.requireActive(); environment.requireExistingOriginalObservation(); registry.assertContinuity()
        return registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    }
    private fun requireNewIssuance() { requireOriginal(); issuance.requireIssuance(null); if (!owner.newCeremoniesAllowedNoIo()) refuse(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN) }
    private fun ledger(): NativeEmployeeEnrollmentStateV1? = registry.readEmployeeEnrollments(keys)
    private fun currentOriginalRecord(id: DomainId): NativeEmployeeEnrollmentRecordV1 = ledger()?.records?.singleOrNull { it.originalActionId == id }
        ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun retain(state: NativeEmployeeEnrollmentStateV1, id: DomainId): NativeEmployeeEnrollmentRecordV1 =
        registry.retainEmployeeEnrollments(state, keys).records.single { it.originalActionId == id }
    private inline fun noThrowCurrent(block: () -> Boolean): Boolean = try {
        work.requireActive(); !owner.dataUseDeniedNoIo() && block()
    } catch (_: Exception) { false }
    private fun AndroidSelfOwnedNativeClockSample.publicObservation(): LocalAuthenticationObservation = LocalAuthenticationObservation(issuerContextId, moment)
    private fun refuse(reason: SelfOwnedInstallationFailureV1): Nothing = selfOwnedFail(reason)
    private companion object { const val EMPLOYEE_PROVISIONING_PURPOSE_TAG = 13 }
}
