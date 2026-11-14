package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.util.concurrent.atomic.AtomicReference

/** Consumption participant of the same original native owner, registry, root and worker.
 * An approval is DATA: only a forced original dispatch can bind the actual native frame.
 * Neither SQL existence nor a supplied outcome/cleanup callback terminalizes an original. */
internal class AndroidLocalCompanyDeviceAdmissions(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val environment: SelfOwnedOriginalEnvironment,
    private val custody: SelfOwnedCompanyCustody,
    private val work: AndroidOwnedInstallationWork,
    private val anchor: AndroidSelfOwnedHistoryAnchor,
    private val issuance: AndroidLocalCompanyIssuanceGate,
    private val pairings: AndroidLocalCompanyDevicePairings,
) {
    private val actionOwner = Any()
    private class DispatchEntry(val dispatch: NativeLocalDeviceAdmissionDispatch) {
        val permit = AtomicReference<NativeLocalDeviceAdmissionPermit?>(null)
        val frame = AtomicReference<NativeDeviceAdmissionFrame?>(null)
        val record = AtomicReference(dispatch.record)
        val preflight = AtomicReference<LocalAuthenticationObservation?>(null)
        // Only the actual final synchronous native sample may fill this slot.
        val finalObservation = AtomicReference<LocalAuthenticationObservation?>(null)
    }
    private class ReceiptEntry(val permit: LocalDeviceAdmissionReceiptPermit, val record: NativeDeviceAdmissionRecordV1)
    private val dispatches = AtomicReference<List<DispatchEntry>>(emptyList())
    private val receipts = AtomicReference<List<ReceiptEntry>>(emptyList())
    private val admittedHistory = AtomicReference<NativeDeviceAdmissionHistorySelection?>(null)

    fun selectOriginalHistory(original: OriginalNativeHistorySnapshot): NativeDeviceAdmissionHistorySelection {
        requireOriginal(); anchor.requireSameOriginalCut(original)
        val state = ledger(); val retained = state?.records.orEmpty()
        if (retained.any { !pairings.matchesOriginalAdmissionOrigin(it.pairing, original) })
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val consumed = retained.filter { it.phase == NativeDeviceAdmissionPhaseV1.CONSUMED }
            .sortedBy { checkNotNull(it.effect).child.historySequence }
        val parent = original.record.parent
        if (consumed.any { parent == null || checkNotNull(it.effect).child.historySequence > parent.historySequence })
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return NativeDeviceAdmissionHistorySelection(actionOwner, original, ledgerDigest(state), consumed, retained)
    }

    fun reobserveOriginalHistory(selection: NativeDeviceAdmissionHistorySelection, original: OriginalNativeHistorySnapshot) {
        requireOriginal()
        if (selection.nativeOwner !== actionOwner || selection.original !== original || ledgerDigest(ledger()) != selection.ledgerDigest)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        selection.requireActualTraversal(); anchor.requireSameOriginalCut(original)
    }

    /** Only the existing actual complete-read/close/reobservation/Room-admission cut calls this. */
    fun admitOriginalHistory(selection: NativeDeviceAdmissionHistorySelection, afterClose: OriginalNativeHistorySnapshot) {
        work.requireActive()
        check(selection.nativeOwner === actionOwner && selection.original.ownerIdentity === afterClose.ownerIdentity &&
            selection.original.recordDigest == afterClose.recordDigest && selection.original.lifetimeId == afterClose.lifetimeId &&
            admittedHistory.get() == null && !owner.dataUseDeniedNoIo())
        selection.requireActualTraversal()
        check(admittedHistory.compareAndSet(null, selection))
    }

    /** Fresh census provenance is exactly C1 plus every observed consumed original.
     * Revised/revoked other-device rows require a separate supported history producer and
     * cannot be adopted here. Room independently recaptures the complete current census. */
    fun requireGenuineCensus(requested: LocalDevicePairingDefinition, original: OriginalNativeHistorySnapshot,
        dispatchAction: DomainId? = null) {
        requireOriginal()
        val native = original.record; val initial = checkNotNull(native.definition).definition.masterAdmission
        if (!initial.admitted || initial.revision != Revision(0) || initial.deviceId != native.allocation.coordinates.deviceId)
            refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val records = ledger()?.records.orEmpty()
        if (records.any { !pairings.matchesOriginalAdmissionOrigin(it.pairing, original) ||
                it.phase in setOf(NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT, NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT) &&
                    it.pairing.body.originalActionId != dispatchAction })
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val consumed = records.filter { it.phase == NativeDeviceAdmissionPhaseV1.CONSUMED }
        if (consumed.any { !terminalObserved(it) || native.parent == null || checkNotNull(it.effect).child.historySequence > native.parent.historySequence })
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val expected = (listOf(initial) + consumed.map { it.pairing.body.requested().admission }).sortedBy { it.deviceId.value }
        val supplied = requested.retainedAdmissions
        if (expected.size != supplied.size || expected.map { it.deviceId }.distinct().size != expected.size ||
            expected.any { it.deviceId == requested.admission.deviceId }) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        expected.zip(supplied).forEach { (before, after) ->
            val a = LocalBranchInstallationWireV2.encodeAdmission(before); val b = LocalBranchInstallationWireV2.encodeAdmission(after)
            try { if (!a.contentEquals(b)) refuse(SelfOwnedInstallationFailureV1.CONFLICT) }
            finally { a.fill(0); b.fill(0) }
        }
    }

    fun beginDispatch(id: DomainId): NativeLocalDeviceAdmissionDispatch {
        requireOriginal()
        val state = ledger(); requireNoUncertainOriginal(state)
        if (state?.records?.any { it.pairing.body.originalActionId == id } == true)
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val pairing = pairings.originalForAdmission(id)
        if (state != null && (state.records.size >= LocalDevicePairingProfileV1.MAXIMUM_ACTIONS ||
                state.generation >= Long.MAX_VALUE - 3L) || pairing.body.requested().retainedAdmissions.size >= MAX_ADMISSIONS)
            refuse(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val original = anchor.snapshotOriginal(); pairings.requireAdmissionCurrent(pairing, original)
        val observation = observe(); requireObservation(pairing, observation)
        val dispatchId = AndroidP256Primitives.newId()
        val reserved = checkNotNull(custody.companyOriginal()).allIds().toSet() + pairing.body.origin.reservedOriginalIds() +
            custody.readDevicePairings()?.records.orEmpty().flatMap { it.nativeIds() } +
            state?.records.orEmpty().map { it.dispatchId } + custody.readEmployeeEnrollments()?.records.orEmpty().flatMap { it.nativeIds() } +
            custody.readEmployeePolicyActivations()?.records.orEmpty().flatMap { it.nativeIds() } +
            custody.readBusinessPublications()?.records.orEmpty().flatMap { it.reservedIdentityIdsV1() }
        if (dispatchId in reserved) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val record = NativeDeviceAdmissionRecordV1(pairing, dispatchId, observation, NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT)
        val next = state?.next(record) ?: NativeDeviceAdmissionStateV1(0L, listOf(record))
        // The approval census does not freeze account creation. Reserve the supported full
        // account bound before dispatch; the actual Room preflight later supplies exact N.
        NativeDeviceAdmissionWireV1.requireCompletionCapacity(next, MAX_ACCOUNTS, pairing.body.requested().retainedAdmissions.size)
        val retained = retain(next, id)
        pairings.requireAdmissionCurrent(pairing, anchor.requireSameOriginalCut(original))
        val dispatch = NativeLocalDeviceAdmissionDispatch(actionOwner, Any(), owner, retained)
        dispatches.set(dispatches.get() + DispatchEntry(dispatch))
        return dispatch
    }

    fun authorizeEffect(dispatch: NativeLocalDeviceAdmissionDispatch, request: LocalProvisioningRequest): NativeLocalDeviceAdmissionPermit? {
        val entry = dispatches.get().singleOrNull { it.dispatch === dispatch } ?: return null
        if (dispatch.owner !== actionOwner || dispatch.controller !== owner || dispatch.requested.request != request) return null
        val record = currentOriginalRecord(dispatch.originalActionId)
        if (record != entry.record.get() || record.phase != NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT || entry.frame.get() != null) return null
        pairings.requireAdmissionCurrent(record.pairing, anchor.snapshotOriginal())
        return entry.permit.get() ?: NativeLocalDeviceAdmissionPermit(actionOwner, owner, dispatch).also {
            check(entry.permit.compareAndSet(null, it))
        }
    }

    fun bindFrame(native: SelfOwnedNativeRoomFrame, permit: NativeLocalDeviceAdmissionPermit): NativeDeviceAdmissionFrame {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit }
            ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val record = currentOriginalRecord(entry.dispatch.originalActionId)
        if (permit.owner !== actionOwner || permit.controller !== owner || permit.dispatch !== entry.dispatch ||
            record != entry.record.get() || record.phase != NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT || entry.frame.get() != null ||
            native.preparedChild != null || !native.nativeBegan || native.finalized || native.consumed ||
            native.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        pairings.requireAdmissionCurrent(record.pairing, native.original); anchor.requireSameOriginalCut(native.original)
        val observation = observe(); requireObservation(record.pairing, observation)
        if (observation.moment.elapsedMillis < record.dispatchObservation.moment.elapsedMillis)
            refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val frame = NativeDeviceAdmissionFrame(actionOwner, owner, native, permit, observation)
        check(entry.frame.compareAndSet(null, frame)); return frame
    }

    fun retainPreparedEffect(frame: NativeDeviceAdmissionFrame, intent: NativeHistoryOriginalIntentV2,
        evidence: LocalDeviceAdmissionEffectEvidence) {
        val entry = requireEntry(frame); val before = currentOriginalRecord(entry.dispatch.originalActionId)
        if (before != entry.record.get() || before.phase != NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        pairings.requireAdmissionAuthorityCurrent(before.pairing)
        val native = frame.native; val cut = anchor.snapshotOriginal(); val pending = checkNotNull(cut.record.pending)
        val child = checkNotNull(native.preparedChild); val origin = before.pairing.body.origin; val parent = origin.preparationParent
        if (native.consumed || native.finalized || child != pending.child || native.preparedIntentDigest == null ||
            !sameIntent(pending.originalIntent, intent) || native.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN ||
            native.original.recordDigest != origin.preparationAnchorDigest || cut.record.parent != parent ||
            child.historySequence != parent.historySequence + 1L || child.commandEpoch != parent.commandEpoch ||
            child.commandSequence != parent.commandSequence || child.commandDigest != parent.commandDigest ||
            child.installationGenesisDigest != parent.installationGenesisDigest || child.roleCommitment != parent.roleCommitment ||
            child.recoveryCommitment != parent.recoveryCommitment || intent.purposeTag != PROVISIONING_PURPOSE_TAG ||
            intent.originalRequestId != frame.requested.request.requestId || intent.originalRequestDigest != frame.requested.request.contentDigest ||
            intent.originalActorId != null || intent.originalOperationId != null || intent.resources.isNotEmpty() ||
            evidence.request != frame.requested.request || evidence.definitionDigest != frame.requested.definitionDigest ||
            evidence.authorityDigest != frame.authorityDigest || evidence.nativeBindingDigest != frame.nativeBindingDigest ||
            evidence.protectedPreflight.issuerContextId != frame.bindingObservation.issuerContextId ||
            evidence.protectedPreflight.moment.bootId != frame.bindingObservation.moment.bootId ||
            evidence.eventAt.bootId != frame.bindingObservation.moment.bootId ||
            evidence.eventAt.elapsedMillis < frame.bindingObservation.moment.elapsedMillis ||
            evidence.protectedPreflight.moment.elapsedMillis < frame.bindingObservation.moment.elapsedMillis ||
            evidence.admissionDigests.map { it.first } != frame.requested.retainedAdmissions.map { it.deviceId } ||
            origin.coordinates.firstActorId !in evidence.accountDigests.map { it.first }) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        requireObservation(before.pairing, evidence.protectedPreflight)
        // Later admission hashes also join their exact original protected INSERT witness.
        // C1's complete initial protected image is joined by the chronological scanner.
        ledger()?.records.orEmpty().filter { it.phase == NativeDeviceAdmissionPhaseV1.CONSUMED }.forEach { retained ->
            if (!terminalObserved(retained) || evidence.admissionDigests.singleOrNull {
                    it.first == retained.pairing.body.requested().admission.deviceId
                }?.second != checkNotNull(retained.evidence()).images.first().plaintextDigest)
                refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        val dependencies = localDeviceAdmissionDependenciesV1(evidence); val facts = evidence.copyWireBytes()
        try {
            if (sha256(dependencies) != intent.dependenciesDigest || sha256(facts) != intent.originalFactsDigest)
                refuse(SelfOwnedInstallationFailureV1.CONFLICT)
            val record = before.copy(phase = NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT,
                effect = NativeDeviceAdmissionEffectV1(native.frameId, child, checkNotNull(native.preparedIntentDigest),
                    SelfOwnedOctetsV1(facts, NativeDeviceAdmissionWireV1.MAX_EVIDENCE_BYTES)))
            val next = checkNotNull(ledger()).next(record)
            NativeDeviceAdmissionWireV1.requireCompletionCapacity(next, evidence.accountDigests.size, evidence.admissionDigests.size)
            entry.record.set(retain(next, entry.dispatch.originalActionId))
            entry.preflight.set(evidence.protectedPreflight)
            pairings.requireAdmissionAuthorityCurrent(before.pairing)
        } finally { dependencies.fill(0); facts.fill(0) }
    }

    /** Directly called after native's LAST real sample. No IO, suspension or clock follows. */
    fun finishNativeNow(native: SelfOwnedNativeRoomFrame, sample: AndroidSelfOwnedNativeClockSample) {
        val entry = dispatches.get().singleOrNull { it.frame.get()?.native === native } ?: return
        val frame = checkNotNull(entry.frame.get()); val record = entry.record.get()
        if (native.preparedChild == null) {
            check(record.phase == NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT && record.effect == null &&
                native.preparedIntentDigest == null && entry.finalObservation.get() == null)
            return // Only actual no-child outer settlement may prove no effect.
        }
        if (record.phase != NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT || record.effect?.child != native.preparedChild ||
            record.effect?.intentDigest != native.preparedIntentDigest || entry.finalObservation.get() != null ||
            !owner.newCeremoniesAllowedNoIo() || issuance.denied()) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val observation = LocalAuthenticationObservation(sample.issuerContextId, sample.moment)
        requireObservation(record.pairing, observation)
        val preflight = checkNotNull(entry.preflight.get())
        if (observation.issuerContextId != frame.bindingObservation.issuerContextId ||
            observation.moment.bootId != frame.bindingObservation.moment.bootId ||
            observation.issuerContextId != preflight.issuerContextId || observation.moment.bootId != preflight.moment.bootId ||
            observation.moment.elapsedMillis < preflight.moment.elapsedMillis) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        check(entry.finalObservation.compareAndSet(null, observation))
    }

    /** Actual driver release, drained outer children and exact anchor settlement precede this. */
    fun settleAfterActualOuter(native: SelfOwnedNativeRoomFrame, settlement: SelfOwnedNativeRoomSettlement) {
        val entry = dispatches.get().singleOrNull { it.frame.get()?.native === native } ?: return
        check(native.consumed && settlement.disposition == native.nativeDisposition && settlement.disposition != SelfOwnedNativeSqlDisposition.UNKNOWN)
        val before = currentOriginalRecord(entry.dispatch.originalActionId); check(before == entry.record.get())
        val committed = settlement.disposition == SelfOwnedNativeSqlDisposition.COMMITTED && native.preparedChild != null
        val record = if (committed) {
            check(before.phase == NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT && native.finalized &&
                before.effect?.child == native.preparedChild && before.effect?.intentDigest == native.preparedIntentDigest &&
                settlement.original.record.parent == native.preparedChild && settlement.original.record.pending == null)
            before.copy(phase = NativeDeviceAdmissionPhaseV1.CONSUMED, settledAnchorDigest = settlement.original.recordDigest,
                finalObservation = checkNotNull(entry.finalObservation.get()))
        } else {
            check((settlement.disposition in setOf(SelfOwnedNativeSqlDisposition.NOT_SUBMITTED, SelfOwnedNativeSqlDisposition.ROLLED_BACK) ||
                settlement.disposition == SelfOwnedNativeSqlDisposition.COMMITTED && native.preparedChild == null &&
                    native.preparedIntentDigest == null && before.phase == NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT && before.effect == null && native.finalized) &&
                settlement.original.record.pending == null && settlement.original.record.parent == before.pairing.body.origin.preparationParent &&
                (native.preparedChild != null || settlement.original.recordDigest == before.pairing.body.origin.preparationAnchorDigest))
            entry.finalObservation.set(null)
            before.copy(phase = NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT, settledAnchorDigest = settlement.original.recordDigest)
        }
        entry.record.set(retain(checkNotNull(ledger()).next(record), entry.dispatch.originalActionId))
    }

    /** Negative-only unused dispatch terminalization, never proof of endpoint cleanup. */
    fun finishKnownNoEffect(dispatch: NativeLocalDeviceAdmissionDispatch): Boolean {
        val entry = dispatches.get().singleOrNull { it.dispatch === dispatch } ?: return false
        if (dispatch.owner !== actionOwner || dispatch.controller !== owner) return false
        val before = currentOriginalRecord(dispatch.originalActionId)
        if (entry.frame.get() != null || before != entry.record.get() || before.phase != NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT) return false
        val actual = anchor.snapshotOriginal()
        if (actual.record.pending != null || actual.recordDigest != before.pairing.body.origin.preparationAnchorDigest ||
            actual.record.parent != before.pairing.body.origin.preparationParent) return false
        val record = before.copy(phase = NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT, settledAnchorDigest = actual.recordDigest)
        entry.record.set(retain(checkNotNull(ledger()).next(record), dispatch.originalActionId)); return true
    }

    fun lookupReceipt(id: DomainId): LocalDeviceAdmissionReceiptPermit? {
        requireOriginal()
        val record = ledger()?.records?.singleOrNull { it.pairing.body.originalActionId == id } ?: return null
        if (record.phase !in setOf(NativeDeviceAdmissionPhaseV1.CONSUMED, NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT)) return null
        if (!terminalObserved(record)) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!pairings.matchesOriginalAdmissionOrigin(record.pairing, anchor.snapshotOriginal())) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        receipts.get().singleOrNull { it.record.pairing.body.originalActionId == id }?.let {
            if (it.record != record) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return it.permit
        }
        return LocalDeviceAdmissionReceiptPermit(actionOwner, owner, record).also {
            receipts.set(receipts.get() + ReceiptEntry(it, record))
        }
    }

    fun effectPermitCurrent(permit: NativeLocalDeviceAdmissionPermit): Boolean = noThrowCurrent {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: return@noThrowCurrent false
        val record = entry.record.get(); val approved = checkNotNull(record.pairing.approvedHead)
        permit.owner === actionOwner && permit.controller === owner && entry.dispatch === permit.dispatch &&
            record.phase in setOf(NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT, NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT) &&
            entry.frame.get()?.native?.consumed != true && owner.newCeremoniesAllowedNoIo() && !issuance.denied() &&
            work.clock() < approved.deadlineElapsedMillis && environment.bootCount == approved.osBootCount
    }
    fun ownCommitCurrent(permit: NativeLocalDeviceAdmissionPermit): Boolean = noThrowCurrent {
        val entry = dispatches.get().singleOrNull { it.permit.get() === permit } ?: return@noThrowCurrent false
        val record = entry.record.get()
        permit.owner === actionOwner && permit.controller === owner && permit.dispatch === entry.dispatch &&
            record.phase == NativeDeviceAdmissionPhaseV1.CONSUMED && entry.frame.get()?.native?.consumed == true &&
            entry.finalObservation.get() == record.finalObservation
    }
    fun receiptCurrent(permit: LocalDeviceAdmissionReceiptPermit): Boolean = noThrowCurrent {
        permit.owner === actionOwner && permit.controller === owner && receipts.get().any { it.permit === permit && it.record == permit.record }
    }

    private fun terminalObserved(record: NativeDeviceAdmissionRecordV1): Boolean {
        val warm = dispatches.get().singleOrNull { it.record.get() == record }
        if (warm != null) {
            if (record.phase == NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT &&
                (warm.frame.get() == null || warm.frame.get()?.native?.consumed == true)) return true
            if (record.phase == NativeDeviceAdmissionPhaseV1.CONSUMED && warm.frame.get()?.native?.consumed == true &&
                warm.finalObservation.get() == record.finalObservation) return true
        }
        val history = admittedHistory.get() ?: return false
        return history.nativeOwner === actionOwner && history.retainedRecords.any { it == record } &&
            (record.phase == NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT ||
                record.phase == NativeDeviceAdmissionPhaseV1.CONSUMED && history.sources.any { it.record == record })
    }
    private fun requireEntry(frame: NativeDeviceAdmissionFrame): DispatchEntry = dispatches.get().singleOrNull {
        it.frame.get() === frame && frame.owner === actionOwner && frame.controller === owner && it.permit.get() === frame.permit
    } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun requireObservation(pairing: NativeDevicePairingRecordV1, observed: LocalAuthenticationObservation) {
        val prepared = pairing.body.preparedHead; val approved = checkNotNull(pairing.approvedHead)
        if (observed.issuerContextId != prepared.observation.issuerContextId || observed.moment.bootId != prepared.observation.moment.bootId ||
            observed.moment.elapsedMillis < prepared.observation.moment.elapsedMillis ||
            observed.moment.elapsedMillis >= approved.deadlineElapsedMillis || environment.bootCount != approved.osBootCount)
            refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireNoUncertainOriginal(state: NativeDeviceAdmissionStateV1?) {
        if (state?.records?.any { it.phase in setOf(NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT, NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT) } == true)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    private fun sameIntent(left: NativeHistoryOriginalIntentV2, right: NativeHistoryOriginalIntentV2): Boolean {
        val a = SelfOwnedNativeHistoryWireV2.encodeIntent(left); val b = SelfOwnedNativeHistoryWireV2.encodeIntent(right)
        return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
    }
    private fun ledgerDigest(state: NativeDeviceAdmissionStateV1?): PayloadDigest? {
        if (state == null) return null
        val bytes = NativeDeviceAdmissionWireV1.encode(state)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    private fun requireOriginal() {
        work.requireActive(); environment.requireExistingOriginalObservation(); custody.assertContinuity()
        if (owner.dataUseDeniedNoIo()) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        custody.companyOriginal() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    }
    private fun ledger(): NativeDeviceAdmissionStateV1? = custody.readDeviceAdmissions()
    private fun currentOriginalRecord(id: DomainId): NativeDeviceAdmissionRecordV1 = ledger()?.records?.singleOrNull {
        it.pairing.body.originalActionId == id
    } ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun retain(state: NativeDeviceAdmissionStateV1, id: DomainId): NativeDeviceAdmissionRecordV1 =
        custody.retainDeviceAdmissions(state).records.single { it.pairing.body.originalActionId == id }
    private fun observe(): LocalAuthenticationObservation = owner.historyClockSampleNative().let { LocalAuthenticationObservation(it.issuerContextId, it.moment) }
    private inline fun noThrowCurrent(block: () -> Boolean): Boolean = try {
        work.requireActive(); !owner.dataUseDeniedNoIo() && block()
    } catch (_: Exception) { false }
    private fun refuse(reason: SelfOwnedInstallationFailureV1): Nothing = selfOwnedFail(reason)
    private companion object {
        const val PROVISIONING_PURPOSE_TAG = 13
        const val MAX_ACCOUNTS = 4096
        const val MAX_ADMISSIONS = 256
    }
}
