package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicReference

/** Execute-only participant of the existing ORIGINAL native owner, registry, root and worker.
 * No new key/root, caller-selected path/verifier, second executor, restored effect permit or head session. */
internal class AndroidLocalCompanyEmployeePolicyActivations(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: LocalProvisionerRegistryStore,
    private val keys: LocalProvisionerNativeKeys,
    private val work: AndroidOwnedInstallationWork,
    private val anchor: AndroidSelfOwnedHistoryAnchor,
    private val issuance: AndroidLocalCompanyIssuanceGate,
) {
    private val actionOwner = Any()
    private val authentication = AndroidLocalInstallerAuthentication(environment, registry, keys, work, issuance)
    private class DispatchEntry(val dispatch: NativeLocalEmployeePolicyActivationDispatch) {
        val permit = AtomicReference<NativeLocalEmployeePolicyActivationPermit?>(null)
        val frame = AtomicReference<NativeEmployeePolicyActivationFrame?>(null)
        val record = AtomicReference(dispatch.record)
        // Captured only by actual finishRoomFrameNow, after preparation and its native sample.
        val finalObservation = AtomicReference<LocalAuthenticationObservation?>(null)
    }
    private class ReceiptEntry(val permit: LocalEmployeePolicyActivationReceiptPermit, val record: NativeEmployeePolicyActivationRecordV1)
    private val dispatches = AtomicReference<List<DispatchEntry>>(emptyList())
    private val receipts = AtomicReference<List<ReceiptEntry>>(emptyList())
    // Published only by the actual complete native read's close/reobserve/Room admission.
    // A parsed CONSUMED ledger, signed certificate, current account or scalar cut grants none.
    private val admittedHistory = AtomicReference<NativeEmployeePolicyActivationHistorySelection?>(null)

    fun selectOriginalHistory(original: OriginalNativeHistorySnapshot): NativeEmployeePolicyActivationHistorySelection {
        requireOriginal()
        anchor.requireSameOriginalCut(original)
        val state = ledger()
        val records = state?.records.orEmpty().filter { matchesOriginalHistoryTarget(it.target, original) }
        val consumed = records.filter { it.phase == LocalEmployeePolicyActivationPhase.CONSUMED }.sortedBy { checkNotNull(it.effect).child.historySequence }
        val parent = original.record.parent
        if (consumed.any { parent == null || checkNotNull(it.effect).child.historySequence > parent.historySequence })
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return NativeEmployeePolicyActivationHistorySelection(actionOwner, original, ledgerDigest(state), consumed, records)
    }

    fun reobserveOriginalHistory(selection: NativeEmployeePolicyActivationHistorySelection, original: OriginalNativeHistorySnapshot) {
        requireOriginal()
        if (selection.nativeOwner !== actionOwner || selection.original !== original || ledgerDigest(ledger()) != selection.ledgerDigest)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        selection.requireActualTraversal()
        anchor.requireSameOriginalCut(original)
    }

    /** Exact actual read delivery cut only. Called by native's existing Room-admission method,
     * never by a callback, imported receipt, detached source, Boolean or parsed snapshot. */
    fun admitOriginalHistory(selection: NativeEmployeePolicyActivationHistorySelection, afterClose: OriginalNativeHistorySnapshot) {
        work.requireActive()
        check(selection.nativeOwner === actionOwner && selection.original.ownerIdentity === afterClose.ownerIdentity &&
            selection.original.recordDigest == afterClose.recordDigest && selection.original.lifetimeId == afterClose.lifetimeId &&
            admittedHistory.get() == null && !owner.dataUseDeniedNoIo())
        selection.requireActualTraversal()
        check(admittedHistory.compareAndSet(null, selection))
    }

    /** Original signed late-phase connection DATA only. No fresh-head check, signing,
     * deadline extension, dispatch, effect permit, absence or custody is produced here. */
    fun recoverConnection(id: DomainId): LocalEmployeePolicyActivationAuthorityReceipt {
        requireOriginal()
        val actual = anchor.snapshotOriginal(); requireOperationalOriginal(actual)
        val record = currentOriginalRecord(id)
        if (record.phase !in setOf(LocalEmployeePolicyActivationPhase.DISPATCH_INTENT, LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT,
                LocalEmployeePolicyActivationPhase.CONSUMED, LocalEmployeePolicyActivationPhase.REJECTED) || record.rootEndorsement.size != 64)
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val warm = dispatches.get().any { sameOriginalConnectionRecord(it.record.get(), record) }
        val cold = admittedHistory.get()?.retainedRecords?.any { sameOriginalConnectionRecord(it, record) } == true
        if ((!warm && !cold) || record.phase == LocalEmployeePolicyActivationPhase.CONSUMED && !consumedCompletionObserved(record))
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireTarget(record.target, actual, requirePreparationParent = false)
        return LocalEmployeePolicyActivationAuthorityReceipt(record)
    }

    fun retained(): List<LocalEmployeePolicyActivationReference> {
        requireOriginal()
        return ledger()?.records.orEmpty().map {
            val request = it.requested().request
            LocalEmployeePolicyActivationReference(it.originalActionId, request.requestId, request.targetId, it.phase)
        }
    }

    fun prepare(requested: LocalEmployeePolicyActivationDefinition, suppliedOriginal: OriginalNativeHistorySnapshot): LocalEmployeePolicyActivationAction {
        requireNewIssuance()
        val actual = anchor.requireSameOriginalCut(suppliedOriginal)
        requireOperationalOriginal(actual)
        val native = actual.record; val coordinates = native.allocation.coordinates; val request = requested.request
        if (request.scope != coordinates.scope || request.action != LocalProvisioningAction.ACTIVATE_POLICY) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val state = ledger()
        requireNoUncertainOriginal(state)
        val previous = state?.records?.singleOrNull { it.requested().request.requestId == request.requestId }
        if (previous != null) {
            requireSameDefinition(previous, requested)
            if (previous.phase != LocalEmployeePolicyActivationPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            requireTarget(previous.target, actual, requirePreparationParent = true)
            return reviewRetained(previous)
        }
        if (state?.records?.size == LocalEmployeePolicyActivationProfileV1.MAXIMUM_ACTIONS) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val original = requireOriginal(); val company = companyState(); val head = company.head
        if (!head.enabled) refuse(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val observation = owner.historyClockSampleNative().publicObservation()
        val elapsed = observation.moment.elapsedMillis
        if (elapsed > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val definition = checkNotNull(native.definition); val receipt = checkNotNull(native.receipt)
        val rootBytes = company.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val target = NativeEmployeePolicyActivationTargetV1(coordinates, native.allocation.digest, native.allocation.originalNativeBindingDigest,
            definition.definition.originalRequestId, definition.definitionDigest, receipt.digest, definition.definition.authorityEpoch,
            checkNotNull(native.parent), actual.recordDigest, original.original.rootId, original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId,
            rootDigest, original.original.actionId, original.headAuthorityId, original.headEnrollmentId,
            AndroidP256Primitives.rootFingerprint(keys.rootBody()))
        val reserved = original.allIds().toSet() + target.reservedOriginalIds() + state?.records.orEmpty().flatMap { it.nativeIds() } +
            registry.readBusinessPublications(keys)?.records.orEmpty().flatMap { listOf(it.originalActionId, it.keyId, it.publicationId, it.entityId) } +
            registry.devicePairingReservedIdentityIds(keys) +
            registry.readEmployeeEnrollments(keys)?.records.orEmpty().flatMap { it.nativeIds() }
        if (request.requestId in reserved || request.requestId == request.targetId) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        // The ORIGINAL action ID and complete next policy are retained BEFORE head verification.
        val actionId = AndroidP256Primitives.newId()
        if (actionId in reserved || actionId == request.requestId || actionId == request.targetId) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = requested.copyWireBytes()
        val record = try { NativeEmployeePolicyActivationRecordV1(actionId, target,
            SelfOwnedOctetsV1(bytes, LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES),
            NativeEmployeePolicyActivationHeadV1(original.original.facts.companyName, head.name, head.login, head.accountRevision,
                head.credentialRevision, company.original.revision, environment.bootCount, observation,
                elapsed + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS), LocalEmployeePolicyActivationPhase.PREPARED) }
        finally { bytes.fill(0) }
        requireNewIssuance(); anchor.requireSameOriginalCut(actual)
        val retained = retain(state?.next(record) ?: NativeEmployeePolicyActivationStateV1(0L, listOf(record)), record.originalActionId)
        return reviewRetained(retained)
    }

    fun review(id: DomainId): LocalEmployeePolicyActivationAction {
        val state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUncertainOriginal(state)
        val record = state.records.singleOrNull { it.originalActionId == id } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != LocalEmployeePolicyActivationPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return reviewRetained(record)
    }

    private fun reviewRetained(record: NativeEmployeePolicyActivationRecordV1): LocalEmployeePolicyActivationAction {
        requireNewIssuance(); requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        requirePreparedHeadCurrent(record)
        return LocalEmployeePolicyActivationAction(actionOwner, record.originalActionId, record.requested(), record.preparedHead.companyName,
            record.preparedHead.headName, record.preparedHead.headLogin, record.target.rootFingerprint)
    }

    fun approve(action: LocalEmployeePolicyActivationAction, login: String, secret: CharArray): LocalEmployeePolicyActivationAuthorityReceipt {
        var state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        var record = state.records.singleOrNull { it.originalActionId == action.originalActionId }
            ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (action.owner !== actionOwner || action.rootFingerprint != record.target.rootFingerprint) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireSameDefinition(record, action.requested)
        // SIGNING_INTENT and every later phase are original-ID review/lookup ONLY. Never re-sign.
        requireNoUncertainOriginal(state)
        if (record.phase != LocalEmployeePolicyActivationPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNewIssuance(); requirePreparedHeadCurrent(record)
        requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        val actionBytes = NativeEmployeePolicyActivationWireV1.actionMessage(record)
        val actionDigest = try { sha256(actionBytes) } finally { actionBytes.fill(0) }
        val approval = authentication.authenticateEmployeePolicyActivationAction(record.originalActionId, actionDigest, login, secret,
            record.preparedHead.deadlineElapsedMillis)
        authentication.requireEmployeePolicyActivationStillCurrent(approval, companyState(), record.originalActionId, actionDigest)
        requirePreparedHeadCurrent(record); requireNewIssuance()
        record = record.copy(phase = LocalEmployeePolicyActivationPhase.SIGNING_INTENT,
            approvedHead = NativeEmployeePolicyActivationApprovalV1(approval.accountRevision, approval.credentialRevision,
                approval.retainedRevision, approval.boot, approval.expires))
        record = retain(state.next(record), record.originalActionId)
        state = checkNotNull(ledger())
        requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        authentication.requireEmployeePolicyActivationStillCurrent(approval, companyState(), record.originalActionId, actionDigest)
        requireNewIssuance()
        val signature = keys.signEmployeePolicyActivationEndorsement(record, approval)
        try {
            authentication.requireEmployeePolicyActivationStillCurrent(approval, companyState(), record.originalActionId, actionDigest)
            requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true); requireNewIssuance()
            record = retain(state.next(record.copy(phase = LocalEmployeePolicyActivationPhase.APPROVED,
                rootEndorsement = SelfOwnedOctetsV1(signature))), record.originalActionId)
            requireApprovedHeadCurrent(record); requireNewIssuance()
            return LocalEmployeePolicyActivationAuthorityReceipt(record)
        } finally { signature.fill(0) }
    }

    fun recoverApproval(id: DomainId): LocalEmployeePolicyActivationAuthorityReceipt {
        requireNewIssuance()
        val state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUncertainOriginal(state)
        val record = state.records.singleOrNull { it.originalActionId == id } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != LocalEmployeePolicyActivationPhase.APPROVED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        requireApprovedHeadCurrent(record)
        // Original-root signature/definition continuity was verified by the genuine registry
        // read. No new signature, IDs, approval purpose, password verification or SQL effects.
        return LocalEmployeePolicyActivationAuthorityReceipt(record)
    }

    fun beginDispatch(request: LocalProvisioningRequest): NativeLocalEmployeePolicyActivationDispatch {
        requireNewIssuance()
        val state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUncertainOriginal(state)
        val record = state.records.singleOrNull { it.requested().request == request } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase != LocalEmployeePolicyActivationPhase.APPROVED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val actual = anchor.snapshotOriginal(); requireTarget(record.target, actual, requirePreparationParent = true)
        requireApprovedHeadCurrent(record)
        val observation = owner.historyClockSampleNative().publicObservation()
        requireObservation(record, observation)
        val dispatchId = AndroidP256Primitives.newId()
        val reserved = requireOriginal().allIds().toSet() + record.target.reservedOriginalIds() + state.records.flatMap { it.nativeIds() } +
            registry.readEmployeeEnrollments(keys)?.records.orEmpty().flatMap { it.nativeIds() } +
            registry.readBusinessPublications(keys)?.records.orEmpty().flatMap { listOf(it.originalActionId, it.keyId, it.publicationId, it.entityId) } +
            registry.devicePairingReservedIdentityIds(keys)
        if (dispatchId in reserved) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val retained = retain(state.next(record.copy(phase = LocalEmployeePolicyActivationPhase.DISPATCH_INTENT,
            dispatch = NativeEmployeePolicyActivationDispatchV1(dispatchId, actual.recordDigest, observation))), record.originalActionId)
        requireNewIssuance(); requireApprovedHeadCurrent(retained)
        val dispatch = NativeLocalEmployeePolicyActivationDispatch(actionOwner, Any(), owner, retained.originalActionId, request, retained)
        dispatches.set(dispatches.get() + DispatchEntry(dispatch))
        return dispatch
    }

    /** Read-only lookup. No forced dispatch, allocation, signature or credential work here. */
    fun authorizeEffect(dispatch: NativeLocalEmployeePolicyActivationDispatch, request: LocalProvisioningRequest): NativeLocalEmployeePolicyActivationPermit? {
        val entry = dispatches.get().singleOrNull { it.dispatch === dispatch } ?: return null
        if (dispatch.owner !== actionOwner || dispatch.controller !== owner || dispatch.request != request) return null
        val record = currentOriginalRecord(dispatch.originalActionId)
        if (record != entry.record.get() || record.phase != LocalEmployeePolicyActivationPhase.DISPATCH_INTENT || entry.frame.get() != null) return null
        requireNewIssuance(); requireApprovedHeadCurrent(record); requireTarget(record.target, anchor.snapshotOriginal(), requirePreparationParent = true)
        return entry.permit.get() ?: NativeLocalEmployeePolicyActivationPermit(actionOwner, dispatch, owner,
            LocalEmployeePolicyActivationAuthorityReceipt(record)).also { check(entry.permit.compareAndSet(null, it)) }
    }

    fun bindFrame(native: SelfOwnedNativeRoomFrame, permit: NativeLocalEmployeePolicyActivationPermit): NativeEmployeePolicyActivationFrame {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val record = currentOriginalRecord(permit.approval.originalActionId)
        if (permit.owner !== actionOwner || record != entry.record.get() || record.phase != LocalEmployeePolicyActivationPhase.DISPATCH_INTENT ||
            entry.frame.get() != null || native.preparedChild != null || !native.nativeBegan || native.finalized || native.consumed ||
            native.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNewIssuance(); requireTarget(record.target, native.original, requirePreparationParent = true)
        anchor.requireSameOriginalCut(native.original); requireApprovedHeadCurrent(record)
        if (native.original.recordDigest != checkNotNull(record.dispatch).originalAnchorDigest) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val observation = owner.historyClockSampleNative().publicObservation(); requireObservation(record, observation)
        val frame = NativeEmployeePolicyActivationFrame(actionOwner, owner, native, permit, observation)
        check(entry.frame.compareAndSet(null, frame)); return frame
    }

    fun retainPreparedEffect(frame: NativeEmployeePolicyActivationFrame, intent: NativeHistoryOriginalIntentV2,
        evidence: LocalEmployeePolicyActivationEffectEvidence) {
        val entry = requireEntry(frame); val before = currentOriginalRecord(entry.dispatch.originalActionId)
        if (before != entry.record.get() || before.phase != LocalEmployeePolicyActivationPhase.DISPATCH_INTENT) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireNewIssuance(); requireApprovedHeadCurrent(before)
        val native = frame.native; val cut = anchor.snapshotOriginal(); val pending = checkNotNull(cut.record.pending)
        val child = checkNotNull(native.preparedChild)
        if (native.consumed || native.finalized || child != pending.child || native.preparedIntentDigest == null || !sameIntent(pending.originalIntent, intent) ||
            native.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN || child.historySequence != before.target.preparationParent.historySequence + 1L ||
            cut.record.parent != before.target.preparationParent || child.commandEpoch != before.target.authorityEpoch.value ||
            child.commandSequence != before.target.preparationParent.commandSequence || child.commandDigest != before.target.preparationParent.commandDigest ||
            child.installationGenesisDigest != before.target.preparationParent.installationGenesisDigest ||
            child.roleCommitment != before.target.preparationParent.roleCommitment || child.recoveryCommitment != before.target.preparationParent.recoveryCommitment ||
            intent.purposeTag != EMPLOYEE_POLICY_ACTIVATION_PURPOSE_TAG || intent.originalRequestId != entry.dispatch.request.requestId ||
            intent.originalRequestDigest != entry.dispatch.request.contentDigest || intent.originalActorId != null || intent.originalOperationId != null || intent.resources.isNotEmpty() ||
            evidence.request != entry.dispatch.request || !evidence.matchesRequestedPolicy(frame.requested) ||
            evidence.authorityDigest != frame.authorityDigest || evidence.nativeBindingDigest != frame.nativeBindingDigest ||
            evidence.protectedPreflight.issuerContextId != frame.bindingObservation.issuerContextId ||
            evidence.protectedPreflight.moment.bootId != frame.bindingObservation.moment.bootId ||
            evidence.eventAt.elapsedMillis < frame.bindingObservation.moment.elapsedMillis ||
            evidence.protectedPreflight.moment.elapsedMillis < frame.bindingObservation.moment.elapsedMillis) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        requireObservation(before, evidence.protectedPreflight)
        val dependencies = localEmployeePolicyActivationDependenciesV1(evidence); val facts = evidence.copyWireBytes()
        try {
            if (sha256(dependencies) != intent.dependenciesDigest || sha256(facts) != intent.originalFactsDigest) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
            val record = before.copy(phase = LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT,
                effect = NativeEmployeePolicyActivationEffectV1(native.frameId, child, checkNotNull(native.preparedIntentDigest),
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
            check(record.phase == LocalEmployeePolicyActivationPhase.DISPATCH_INTENT && record.effect == null &&
                native.preparedIntentDigest == null && entry.finalObservation.get() == null)
            return
        }
        if (record.phase != LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT || record.effect?.child != native.preparedChild ||
            record.effect?.originalIntentDigest != native.preparedIntentDigest || entry.finalObservation.get() != null ||
            !owner.newCeremoniesAllowedNoIo() || issuance.denied()) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val observation = sample.publicObservation(); requireObservation(record, observation)
        if (observation.issuerContextId != frame.bindingObservation.issuerContextId ||
            observation.moment.bootId != frame.bindingObservation.moment.bootId ||
            observation.moment.elapsedMillis < frame.bindingObservation.moment.elapsedMillis ||
            observation.moment.elapsedMillis < effectEvidence(record).protectedPreflight.moment.elapsedMillis) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
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
            check(before.phase == LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT && native.finalized &&
                before.effect?.child == native.preparedChild && settlement.original.record.parent == native.preparedChild &&
                settlement.original.record.pending == null)
            NativeEmployeePolicyActivationTerminalV1(NativeEmployeePolicyActivationTerminalKindV1.COMMITTED_JOINED_CHILD,
                settlement.original.recordDigest, checkNotNull(entry.finalObservation.get()))
        } else {
            check((settlement.disposition in setOf(SelfOwnedNativeSqlDisposition.NOT_SUBMITTED, SelfOwnedNativeSqlDisposition.ROLLED_BACK) ||
                settlement.disposition == SelfOwnedNativeSqlDisposition.COMMITTED && native.preparedChild == null && native.preparedIntentDigest == null &&
                    before.phase == LocalEmployeePolicyActivationPhase.DISPATCH_INTENT && before.effect == null && native.finalized) &&
                settlement.original.record.pending == null && settlement.original.record.parent == before.target.preparationParent &&
                (native.preparedChild != null || settlement.original.recordDigest == checkNotNull(before.dispatch).originalAnchorDigest))
            entry.finalObservation.set(null)
            NativeEmployeePolicyActivationTerminalV1(NativeEmployeePolicyActivationTerminalKindV1.KNOWN_NO_POLICY_EFFECT,
                settlement.original.recordDigest, null)
        }
        val record = before.copy(phase = if (committed) LocalEmployeePolicyActivationPhase.CONSUMED else LocalEmployeePolicyActivationPhase.REJECTED, terminal = terminal)
        entry.record.set(retain(checkNotNull(ledger()).next(record), record.originalActionId))
    }

    /** Native terminalizes only an exact unused dispatch at its unchanged original cut,
     * with no actual/bound frame or pending child. This withdraws later effect eligibility;
     * it never proves endpoint/OS cleanup. The real service waits its exact cleanup first. */
    fun finishKnownNoEffect(dispatch: NativeLocalEmployeePolicyActivationDispatch): Boolean {
        val entry = dispatches.get().singleOrNull { it.dispatch === dispatch } ?: return false
        val before = currentOriginalRecord(dispatch.originalActionId)
        if (entry.frame.get() != null || before != entry.record.get() || before.phase != LocalEmployeePolicyActivationPhase.DISPATCH_INTENT) return false
        val actual = anchor.snapshotOriginal()
        if (actual.record.pending != null || actual.recordDigest != checkNotNull(before.dispatch).originalAnchorDigest ||
            actual.record.parent != before.target.preparationParent) return false
        val record = before.copy(phase = LocalEmployeePolicyActivationPhase.REJECTED, terminal = NativeEmployeePolicyActivationTerminalV1(
            NativeEmployeePolicyActivationTerminalKindV1.KNOWN_NO_POLICY_EFFECT, actual.recordDigest, null))
        entry.record.set(retain(checkNotNull(ledger()).next(record), record.originalActionId)); return true
    }

    fun lookupReceipt(request: LocalProvisioningRequest): LocalEmployeePolicyActivationReceiptPermit? {
        requireOriginal(); requireOperationalOriginal(anchor.snapshotOriginal())
        val record = ledger()?.records?.singleOrNull { it.requested().request == request } ?: return null
        if (record.phase !in setOf(LocalEmployeePolicyActivationPhase.CONSUMED, LocalEmployeePolicyActivationPhase.REJECTED)) return null
        if (record.phase == LocalEmployeePolicyActivationPhase.CONSUMED && !consumedCompletionObserved(record))
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val retained = receipts.get()
        retained.singleOrNull { it.record.originalActionId == record.originalActionId }?.let { existing ->
            // One immutable original terminal receipt slot, never an effect permit.
            val same = if (record.phase == LocalEmployeePolicyActivationPhase.CONSUMED)
                sameConsumedCompletion(existing.record, record) else existing.record == record
            if (!same || existing.permit.request != request) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return existing.permit
        }
        if (retained.size >= LocalEmployeePolicyActivationProfileV1.MAXIMUM_ACTIONS) refuse(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val eventDigest = if (record.phase == LocalEmployeePolicyActivationPhase.CONSUMED) effectEvidence(record).images.last().plaintextDigest else null
        return LocalEmployeePolicyActivationReceiptPermit(actionOwner, owner, record.originalActionId, request, eventDigest,
            record.phase == LocalEmployeePolicyActivationPhase.REJECTED).also { receipts.set(retained + ReceiptEntry(it, record)) }
    }

    fun effectPermitCurrent(permit: NativeLocalEmployeePolicyActivationPermit): Boolean = noThrowCurrent {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: return@noThrowCurrent false
        val record = entry.record.get()
        permit.owner === actionOwner && permit.controller === owner && entry.dispatch === permit.dispatch &&
            record.phase in setOf(LocalEmployeePolicyActivationPhase.DISPATCH_INTENT, LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT) &&
            entry.frame.get()?.native?.consumed != true && owner.newCeremoniesAllowedNoIo() && !issuance.denied() &&
            work.clock() < checkNotNull(record.approvedHead).deadlineElapsedMillis && environment.bootCount == record.approvedHead.osBootCount
    }
    fun ownCommitCurrent(permit: NativeLocalEmployeePolicyActivationPermit): Boolean = noThrowCurrent {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: return@noThrowCurrent false
        val record = entry.record.get()
        permit.owner === actionOwner && record.phase == LocalEmployeePolicyActivationPhase.CONSUMED &&
            entry.frame.get()?.native?.consumed == true && entry.finalObservation.get() == record.terminal?.finalObservation
    }
    fun receiptCurrent(permit: LocalEmployeePolicyActivationReceiptPermit): Boolean = noThrowCurrent {
        permit.owner === actionOwner && receipts.get().any { it.permit === permit && it.record.requested().request == permit.request &&
            it.record.phase in setOf(LocalEmployeePolicyActivationPhase.CONSUMED, LocalEmployeePolicyActivationPhase.REJECTED) }
    }
    private fun consumedCompletionObserved(record: NativeEmployeePolicyActivationRecordV1): Boolean {
        if (record.phase != LocalEmployeePolicyActivationPhase.CONSUMED) return false
        val warm = dispatches.get().singleOrNull { sameConsumedCompletion(it.record.get(), record) && it.frame.get()?.native?.consumed == true }
        if (warm != null && warm.finalObservation.get() == record.terminal?.finalObservation) return true
        val history = admittedHistory.get() ?: return false
        return history.nativeOwner === actionOwner && history.sources.any { sameConsumedCompletion(it.record, record) }
    }
    private fun sameOriginalConnectionRecord(left: NativeEmployeePolicyActivationRecordV1, right: NativeEmployeePolicyActivationRecordV1): Boolean =
        if (right.phase == LocalEmployeePolicyActivationPhase.CONSUMED) sameConsumedCompletion(left, right) else left == right
    private fun ledgerDigest(state: NativeEmployeePolicyActivationStateV1?): PayloadDigest? {
        if (state == null) return null
        val bytes = NativeEmployeePolicyActivationWireV1.encode(state)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    /** Pure immutable original-target comparison for read DATA, without RUN/head/session
     * freshness. The actual source registry read already verified the unchanged original root. */
    private fun matchesOriginalHistoryTarget(target: NativeEmployeePolicyActivationTargetV1, original: OriginalNativeHistorySnapshot): Boolean {
        val native = original.record
        val definition = native.definition ?: return false
        val expected = NativeEmployeePolicyActivationWireV1.targetBytes(target)
        val joined = NativeEmployeePolicyActivationWireV1.targetBytes(target.copy(coordinates = native.allocation.coordinates))
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
    private fun sameConsumedCompletion(left: NativeEmployeePolicyActivationRecordV1, right: NativeEmployeePolicyActivationRecordV1): Boolean =
        left.phase == LocalEmployeePolicyActivationPhase.CONSUMED && right.phase == LocalEmployeePolicyActivationPhase.CONSUMED && left == right
    private fun requireEntry(frame: NativeEmployeePolicyActivationFrame): DispatchEntry = dispatches.get().singleOrNull {
        it.frame.get() === frame && frame.owner === actionOwner && frame.controller === owner && it.permit.get() === frame.permit
    } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun requirePreparedHeadCurrent(record: NativeEmployeePolicyActivationRecordV1) {
        val current = companyState(); val head = current.head; val retained = record.preparedHead
        if (!head.enabled || head.accountRevision != retained.accountRevision || head.credentialRevision != retained.credentialRevision ||
            head.name != retained.headName || head.login != retained.headLogin || current.original.revision < retained.retainedRevision ||
            environment.bootCount != retained.osBootCount || work.clock() >= retained.deadlineElapsedMillis) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireApprovedHeadCurrent(record: NativeEmployeePolicyActivationRecordV1) {
        requirePreparedHeadCurrent(record)
        val current = companyState(); val head = current.head; val approved = checkNotNull(record.approvedHead)
        if (head.accountRevision != approved.accountRevision || head.credentialRevision != approved.credentialRevision ||
            current.original.revision < approved.retainedRevision || environment.bootCount != approved.osBootCount ||
            work.clock() >= approved.deadlineElapsedMillis || head.reservationId != null || head.failedAttempts != 0) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireObservation(record: NativeEmployeePolicyActivationRecordV1, observed: LocalAuthenticationObservation) {
        val prepared = record.preparedHead; val approved = record.approvedHead
        if (observed.issuerContextId != prepared.observation.issuerContextId || observed.moment.bootId != prepared.observation.moment.bootId ||
            observed.moment.elapsedMillis < prepared.observation.moment.elapsedMillis ||
            observed.moment.elapsedMillis >= (approved?.deadlineElapsedMillis ?: prepared.deadlineElapsedMillis) ||
            environment.bootCount != prepared.osBootCount) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireTarget(target: NativeEmployeePolicyActivationTargetV1, actual: OriginalNativeHistorySnapshot, requirePreparationParent: Boolean) {
        requireOperationalOriginal(actual)
        val native = actual.record; val definition = checkNotNull(native.definition); val c = native.allocation.coordinates
        val expectedCoordinates = NativeEmployeePolicyActivationWireV1.targetBytes(target)
        val joinedCoordinates = NativeEmployeePolicyActivationWireV1.targetBytes(target.copy(coordinates = c))
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
    private fun requireSameDefinition(record: NativeEmployeePolicyActivationRecordV1, requested: LocalEmployeePolicyActivationDefinition) {
        val bytes = requested.copyWireBytes()
        try { if (!record.definition.same(bytes)) refuse(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { bytes.fill(0) }
    }
    private fun requireNoUncertainOriginal(state: NativeEmployeePolicyActivationStateV1?) {
        if (state?.records?.any { it.phase in setOf(LocalEmployeePolicyActivationPhase.SIGNING_INTENT,
            LocalEmployeePolicyActivationPhase.DISPATCH_INTENT, LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT) } == true)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    private fun effectEvidence(record: NativeEmployeePolicyActivationRecordV1): LocalEmployeePolicyActivationEffectEvidence {
        val bytes = checkNotNull(record.effect).evidence.copyBytes()
        return try { LocalEmployeePolicyActivationEffectEvidence.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun companyState(): LocalCompanyAuthorityStateV1 = registry.readCompanyState(keys)
        ?: refuse(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    private fun requireOriginal(): LocalCompanyAuthorityBootstrapV1 {
        work.requireActive(); environment.requireExistingOriginalObservation(); registry.assertContinuity()
        return registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    }
    private fun requireNewIssuance() { requireOriginal(); issuance.requireIssuance(null); if (!owner.newCeremoniesAllowedNoIo()) refuse(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN) }
    private fun ledger(): NativeEmployeePolicyActivationStateV1? = registry.readEmployeePolicyActivations(keys)
    private fun currentOriginalRecord(id: DomainId): NativeEmployeePolicyActivationRecordV1 = ledger()?.records?.singleOrNull { it.originalActionId == id }
        ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun retain(state: NativeEmployeePolicyActivationStateV1, id: DomainId): NativeEmployeePolicyActivationRecordV1 =
        registry.retainEmployeePolicyActivations(state, keys).records.single { it.originalActionId == id }
    private inline fun noThrowCurrent(block: () -> Boolean): Boolean = try {
        work.requireActive(); !owner.dataUseDeniedNoIo() && block()
    } catch (_: Exception) { false }
    private fun AndroidSelfOwnedNativeClockSample.publicObservation(): LocalAuthenticationObservation = LocalAuthenticationObservation(issuerContextId, moment)
    private fun refuse(reason: SelfOwnedInstallationFailureV1): Nothing = selfOwnedFail(reason)
    private companion object { const val EMPLOYEE_POLICY_ACTIVATION_PURPOSE_TAG = 13 }
}
