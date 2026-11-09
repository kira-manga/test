package com.ukkera.pos.security

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Actual native V2 companion on the original P2 worker, keys, namespace and process lease.
 * N-only P2 state remains unchanged. Ordinary read/verification never initializes, repairs, signs a
 * missing receipt, settles a pending child, renews a head grant or creates a SQL permission.
 * The separate startup coordinator may classify only a fenced, completely scanned original
 * pending C1. Its actual close/custody checks and STOP-only result do not grant business use.
 */
internal class AndroidSelfOwnedHistoryAnchor(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val environment: AndroidSelfOwnedEnvironment,
    private val work: AndroidOwnedInstallationWork,
    private val namespace: AndroidSelfOwnedDatabaseNamespace,
    private val signing: AndroidSelfOwnedInstallationSigning,
    private val material: AndroidSelfOwnedDataKeyMaterial,
    private val ownerIdentity: Any,
    private val lifetimeId: com.ukkera.pos.types.DomainId,
) {
    private var originalProposal: OriginalInstallationProposal? = null
    private var originalAttempt: OriginalInstallationAttempt? = null
    // Actual SQL/cleanup/native settlement creates the first completion. Closed-scan startup
    // can rebind an already native-settled C1 or positively classify its fenced original
    // pending child after complete original history/seed validation and actual native close.
    // No D2-R reader, deserializer, supplied facts, close callback or Boolean can do so.
    private var originalCompletion: OriginalInstallationCompletion? = null
    private var originalRetirement: OriginalAttemptRetirement? = null

    /** First companion is possible only in the namespace actually created by this P2 run.
     * An immutable intention is forced first; restart never synthesizes a missing anchor. */
    fun initializeOriginalCompanion(initialization: AndroidOriginalCompanionInitialization) {
        initialization.requireAnchor(this)
        known(namespace.createdThisLifetime)
        guard()
        known(namespace.readHistoryAnchorBytes() == null)
        val binding = owner.readOriginalStateForHistoryNative().originalBinding
        val allocation = signing.originalHistoryAllocation(binding,initialization)
        namespace.retainHistoryOriginIntent(allocation.digest)
        val masterOriginal = owner.captureInitialMasterSdkOriginalNative(initialization, allocation)
        val initial = NativeHistoryAnchorRecordV2(allocation, 1, 0, null, null, null, null,
            null, null, emptyList(), null, null, null,
            recordFormat = SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT,
            masterSdkOriginal = masterOriginal)
        writeRecord(initial,initialization)
        val reread = readOriginal(initialization)
        known(reread.record.generation == 1L && reread.record.allocation.digest == allocation.digest &&
            reread.record.masterSdkOriginal?.descriptorDigest == masterOriginal.descriptorDigest)
    }

    fun signAndRetainOriginalTarget(attempt: OriginalInstallationAttempt) {
        requireOriginalAttemptForIssuance(attempt)
        val cut = snapshotOriginal(); val original = requireNotNull(cut.record.attempt)
        if (original.targetApproval != null) return
        val target = signing.signOriginalTargetApproval(this, attempt, requireNotNull(original.humanApproval))
        retain(cut, next(cut.record, attempt = original.copy(targetApproval = target)))
    }

    fun armOriginalSql(attempt: OriginalInstallationAttempt) {
        requireOriginalAttemptForIssuance(attempt)
        val cut = snapshotOriginal(); val a = requireNotNull(cut.record.attempt)
        val approvals = requireNotNull(a.approvals)
        val submission = LocalBranchInstallationWireV2.targetApprovalFrame(attempt.snapshot, attempt.facts, approvals.human).digest
        // This force/readback MUST precede the first native file or SQLite open.
        retain(cut, next(cut.record, attempt = a.copy(phase = NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED,
            submissionDigest = submission)))
    }

    fun bindOriginalDatabase(identity: NativeHistoryDatabaseIdentityV2) {
        val cut = snapshotOriginal(); val old = cut.record
        old.database?.let { known(it == identity); return }
        val a = requireNotNull(old.attempt)
        known(a.phase == NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED && old.parent == null && old.pending == null)
        retain(cut, next(old, database = identity, attempt = a.copy(phase = NativeHistoryAttemptPhaseV2.DATABASE_BOUND)))
    }

    fun retainRoomChild(frame: SelfOwnedNativeRoomFrame, child: NativeHistoryRecordedHeadV2,
        intent: NativeHistoryOriginalIntentV2, installation: SelfOwnedInstallationChild?,
        finalDeadline: NativeHistoryFinalDeadlineDeclaration?, protectedFinal: NativeHistoryProtectedFinalDeclaration?,
        businessPublications: NativeCompanyBusinessPublicationObservation?, businessPublicationDependencies: ByteArray?) {
        known(frame.ownerIdentity === ownerIdentity && !frame.consumed && frame.nativeBegan &&
            frame.nativeDisposition == SelfOwnedNativeSqlDisposition.UNKNOWN && frame.preparedChild == null)
        val cut = snapshotOriginal(); val old = cut.record
        known(old.pending == null && old.parent == frame.original.record.parent && old.database == frame.original.record.database)
        known(child.historySequence == (old.parent?.historySequence ?: 0L) + 1L)
        val a = requireNotNull(old.attempt)
        if (old.parent == null) {
            known(installation != null && a.phase == NativeHistoryAttemptPhaseV2.DATABASE_BOUND && intent.purposeTag == 1)
            val facts = requireNotNull(installation).facts
            known(facts.historyDigest == child.historyDigest && facts.installationGenesisDigest == child.installationGenesisDigest &&
                facts.receiptDigest == installation.receipt.digest && facts.issuedAttemptId == a.facts.issuedAttemptId &&
                facts.definitionDigest == requireNotNull(old.definition).definitionDigest)
        } else known(a.phase == NativeHistoryAttemptPhaseV2.COMPLETED && old.issuerAcknowledgement != null && installation == null)
        finalDeadline?.let { known(old.parent != null); it.requireIntent(intent) }
        protectedFinal?.let {
            known(old.parent != null); it.requireIntent(intent)
            if (intent.purposeTag in setOf(2, 3)) known(frame.approvedOperationId == intent.originalOperationId)
        }
        requireNativeApprovalFinalPair(intent, finalDeadline, protectedFinal)
        if (finalDeadline != null && protectedFinal != null) known(frame.approvedOperationId == intent.originalOperationId)
        val intentBytes = SelfOwnedNativeHistoryWireV2.encodeIntent(intent)
        val intentDigest = try { sha256(intentBytes) } finally { intentBytes.fill(0) }
        val publicationProof = if (businessPublications == null) {
            known(businessPublicationDependencies == null)
            null
        } else {
            known(frame.registeredBusinessPublicationObservation === businessPublications &&
                businessPublications.nativeFrame === frame && cut.recordDigest == frame.original.recordDigest &&
                businessPublications.originalAnchorDigest == frame.original.recordDigest &&
                businessPublications.originalAllocationDigest == old.allocation.digest &&
                old.parent != null && installation == null && intent.purposeTag in 2..3 &&
                intent.originalOperationId != null && intent.originalActorId != null &&
                intent.originalRequestId == intent.originalOperationId &&
                old.businessPublicationObservations.size < NativeBusinessPublicationObservationWire.MAX_OBSERVATIONS)
            val dependencies = businessPublicationDependencies ?: uncertain()
            try {
                known(sha256(dependencies) == intent.dependenciesDigest)
                NativeBusinessPublicationObservationWire.requireDependency(dependencies, businessPublications.dependencyFields())
            } catch (_: IllegalArgumentException) { uncertain() }
            val manifest = NativeBusinessPublicationObservationWire.encodeManifest(businessPublications.manifest)
            try { NativeHistoryBusinessPublicationObservationProof(old.allocation.coordinates.scope, old.allocation.digest,
                frame.original.recordDigest, requireNotNull(old.parent), frame.frameId, businessPublications.observationDigest,
                child, intentDigest, intent.purposeTag, intent.originalRequestId, requireNotNull(intent.originalOperationId),
                requireNotNull(intent.originalActorId), intent.originalRequestDigest, intent.dependenciesDigest,
                intent.originalFactsDigest, SelfOwnedOctetsV1(manifest)) }
            finally { manifest.fill(0) }
        }
        val pending = NativeHistoryPendingV2(child, intent, finalDeadline, protectedFinal, publicationProof)
        val prepared = next(old, pending = pending,
            recordFormat = when {
                publicationProof != null -> maxOf(old.recordFormat, SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_FORMAT)
                protectedFinal != null -> maxOf(old.recordFormat, SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_FORMAT)
                old.parent != null -> maxOf(old.recordFormat, SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_FORMAT)
                else -> old.recordFormat
            },
            attempt = if (old.parent == null) a.copy(phase = NativeHistoryAttemptPhaseV2.NATIVE_PENDING) else a)
        // Encode the ACTUAL pending child before its force. The codec reserves the entire
        // possible final decision, so a captured successful sample cannot outgrow settlement.
        val capacity = SelfOwnedNativeHistoryWireV2.encodeRecord(prepared)
        capacity.fill(0)
        retain(cut, prepared)
        frame.preparedChild = child; frame.installationChild = installation
        frame.preparedIntentDigest = intentDigest; frame.finalDeadline = finalDeadline
        frame.protectedFinal = protectedFinal
        frame.preparedBusinessPublicationProof = publicationProof
    }

    fun settleRoomAfterActualOuter(frame: SelfOwnedNativeRoomFrame): SelfOwnedNativeRoomSettlement {
        known(frame.ownerIdentity === ownerIdentity && !frame.consumed)
        val cut = snapshotOriginal(); val old = cut.record
        val child = frame.preparedChild
        val disposition = frame.nativeDisposition
        known(disposition != SelfOwnedNativeSqlDisposition.UNKNOWN)
        if (child == null) {
            known(old.pending == null && old.parent == frame.original.record.parent)
            frame.consumed = true
            return SelfOwnedNativeRoomSettlement(disposition, cut)
        }
        known(old.pending?.child == child && old.parent == frame.original.record.parent)
        if (disposition == SelfOwnedNativeSqlDisposition.COMMITTED) {
            known(frame.finalized)
            val deadline = old.pending?.finalDeadline
            val decision = frame.finalDeadlineDecision
            known(deadline == frame.finalDeadline && (deadline == null) == (decision == null))
            decision?.let {
                known(it.child == child && it.declaration == deadline && it.originalIntentDigest == frame.preparedIntentDigest &&
                    it.scope == old.allocation.coordinates.scope && it.originalAllocationDigest == old.allocation.digest)
            }
            val protectedDeclaration = old.pending?.protectedFinal
            val protectedDecision = frame.protectedFinalDecision
            known(protectedDeclaration == frame.protectedFinal && (protectedDeclaration == null) == (protectedDecision == null))
            protectedDecision?.let {
                known(it.child == child && it.declaration == protectedDeclaration &&
                    it.originalIntentDigest == frame.preparedIntentDigest && it.scope == old.allocation.coordinates.scope &&
                    it.originalAllocationDigest == old.allocation.digest)
            }
            if (decision != null && protectedDecision != null) {
                val pending = requireNotNull(old.pending)
                requireNativeApprovalFinalPair(pending.originalIntent, deadline, protectedDeclaration)
                known(frame.approvedOperationId == pending.originalIntent.originalOperationId &&
                    decision.child == protectedDecision.child && decision.originalIntentDigest == protectedDecision.originalIntentDigest)
            }
            val publicationProof = old.pending?.businessPublications
            known(publicationProof == frame.preparedBusinessPublicationProof)
            publicationProof?.let {
                known(it.child == child && it.originalIntentDigest == frame.preparedIntentDigest &&
                    it.scope == old.allocation.coordinates.scope && it.originalAllocationDigest == old.allocation.digest &&
                    it.preparationParent == frame.original.record.parent && it.frameId == frame.frameId &&
                    it.originalAnchorDigest == frame.original.recordDigest)
            }
            val first = frame.installationChild
            val a = requireNotNull(old.attempt)
            val role = old.roleIntent ?: NativeHistoryRoleIntentV2(NativeHistoryDesiredRoleV2.RUN, old.generation + 1,
                requireNotNull(old.definition).definition.originalRequestId, old.definition.definitionDigest, requireNotNull(a.targetApproval).digest)
            val settled = retain(cut, next(old, parent = child, pending = null, role = role,
                attempt = if (first != null) a.copy(phase = NativeHistoryAttemptPhaseV2.COMPLETED) else a,
                firstChild = first?.facts ?: old.firstChild, receipt = first?.receipt ?: old.receipt,
                finalDecisions = if (decision == null) old.finalDeadlineDecisions else old.finalDeadlineDecisions + decision,
                protectedDecisions = if (protectedDecision == null) old.protectedFinalDecisions else old.protectedFinalDecisions + protectedDecision,
                publicationObservations = if (publicationProof == null) old.businessPublicationObservations
                    else old.businessPublicationObservations + publicationProof))
            if (first != null) originalCompletion = OriginalInstallationCompletion(ownerIdentity, Any(), requireNotNull(old.definition),
                a.facts, requireNotNull(a.approvals), first.receipt, first.facts, settled.record.generation, settled.recordDigest)
            frame.consumed = true
            return SelfOwnedNativeRoomSettlement(disposition, settled)
        }
        known(disposition == SelfOwnedNativeSqlDisposition.ROLLED_BACK)
        // A passed final sample is not COMMIT. A real rollback publishes no witness.
        frame.finalDeadlineDecision = null
        frame.protectedFinalDecision = null
        frame.preparedBusinessPublicationProof = null
        if (old.parent == null) {
            // Never erase a first-install pending capsule merely because the body threw.
            // Real rollback is known, but the issuer retirement join must remain explicit.
            val a = requireNotNull(old.attempt)
            val retiring = retain(cut, next(old, denial = old.denialGeneration + 1,
                attempt = a.copy(phase = NativeHistoryAttemptPhaseV2.RETIRING)))
            frame.consumed = true
            return SelfOwnedNativeRoomSettlement(disposition, retiring)
        }
        val settled = retain(cut, next(old, pending = null))
        frame.consumed = true
        return SelfOwnedNativeRoomSettlement(disposition, settled)
    }

    fun completedOriginalForIssuer(): OriginalInstallationCompletion {
        // An actual writer settlement or the narrowly checked closed-scan startup below
        // owns this capability. Parsed receipt bytes and pending SQL never supply it.
        return checkNotNull(originalCompletion).also(::requireOriginalCompletion)
    }

    /** Called only by the original native owner after consuming its exact fully traversed,
     * actually closed/reobserved read. All COMMIT/first-child facts must ALREADY be retained
     * in authenticated native state. This performs no write or repair of that state. */
    fun recoverSettledOriginalForIssuer(afterClosedRead: OriginalNativeHistorySnapshot) {
        known(originalCompletion == null && originalAttempt == null && originalRetirement == null)
        val cut = requireSameOriginalCut(afterClosedRead)
        val record = cut.record
        val original = requireNotNull(record.attempt)
        val definition = requireNotNull(record.definition)
        val parent = requireNotNull(record.parent)
        val first = requireNotNull(record.firstChild)
        val receipt = requireNotNull(record.receipt)
        known(record.database != null && record.pending == null && record.issuerAcknowledgement == null &&
            original.phase == NativeHistoryAttemptPhaseV2.COMPLETED && original.approvals != null &&
            record.retiredAttempts.all { it.issuerRetirementDigest != null })
        // Never turn a later suffix, rollback or an unsettled SQL result into the original C1.
        known(parent.historySequence == 1L && parent.commandSequence == 0L &&
            parent.commandEpoch == definition.definition.authorityEpoch.value &&
            parent.historyDigest == first.historyDigest &&
            parent.installationGenesisDigest == first.installationGenesisDigest &&
            parent.commandDigest == first.installationGenesisDigest &&
            first.originalAllocationDigest == record.allocation.digest &&
            first.definitionDigest == definition.definitionDigest &&
            first.issuedAttemptId == original.facts.issuedAttemptId &&
            first.receiptDigest == receipt.digest && receipt.genesis == first.installationGenesisDigest)
        originalCompletion = OriginalInstallationCompletion(ownerIdentity, Any(), definition,
            original.facts, requireNotNull(original.approvals), receipt, first, record.generation, cut.recordDigest)
    }

    /** New original-custody startup only, never a live writer's cancellation/finally. */
    fun fencePendingOriginalInstallation(): OriginalNativeHistorySnapshot {
        known(originalAttempt == null && originalCompletion == null && originalRetirement == null)
        val cut = snapshotOriginal()
        val record = cut.record
        val attempt = requireNotNull(record.attempt)
        requirePendingOriginal(record)
        if (attempt.phase == NativeHistoryAttemptPhaseV2.RETIRING) return cut // Already durably fenced.
        known(attempt.phase == NativeHistoryAttemptPhaseV2.NATIVE_PENDING && record.denialGeneration < Long.MAX_VALUE)
        return retain(cut, next(record, denial = record.denialGeneration + 1,
            attempt = attempt.copy(phase = NativeHistoryAttemptPhaseV2.RETIRING)))
    }

    /** Actual fully closed scan proved the ORIGINAL retained pending child is present, with
     * its full canonical C1/seed history. No schema/SQL write, absent-child inference, fresh
     * identity or replacement approval occurs here. The original native pending/intent remain
     * until this exact positive classification has been forced/read back under the old keys. */
    fun settlePendingOriginalInstallation(afterClosedRead: OriginalNativeHistorySnapshot,
        child: SelfOwnedInstallationChild): OriginalNativeHistorySnapshot {
        known(originalAttempt == null && originalCompletion == null && originalRetirement == null)
        val cut = requireSameOriginalCut(afterClosedRead)
        val record = cut.record
        requirePendingOriginal(record)
        val attempt = requireNotNull(record.attempt)
        known(attempt.phase == NativeHistoryAttemptPhaseV2.RETIRING && record.denialGeneration > attempt.denialGeneration)
        val definition = requireNotNull(record.definition)
        val pending = requireNotNull(record.pending)
        val facts = child.facts
        val receipt = child.receipt
        val approvals = requireNotNull(attempt.approvals)
        // Pure original receipt/approval/coordinate validation, NOT signing or human consent.
        LocalBranchInstallationWireV2.acknowledgement(definition, attempt.facts, approvals, receipt, facts)
        known(pending.child.historySequence == 1L && pending.child.commandSequence == 0L &&
            pending.child.commandEpoch == facts.commandEpoch.value &&
            pending.child.historyDigest == facts.historyDigest &&
            pending.child.installationGenesisDigest == receipt.genesis && pending.child.commandDigest == receipt.genesis)
        val intent = pending.originalIntent
        known(intent.purposeTag == 1 && intent.originalRequestId == definition.definition.originalRequestId &&
            intent.originalRequestDigest == definition.definitionDigest && intent.originalOperationId == null &&
            intent.originalActorId == null && intent.resources.isEmpty())
        val dependencies = definition.copyWireBytes()
        try {
            val encodedReceipt = LocalBranchInstallationWireV2.encodeReceipt(receipt)
            try { known(sha256(dependencies) == intent.dependenciesDigest && sha256(encodedReceipt) == intent.originalFactsDigest) }
            finally { encodedReceipt.fill(0) }
        } finally { dependencies.fill(0) }
        // Classification cannot undo the fence or promote a recovered original into RUN.
        val stopped = NativeHistoryRoleIntentV2(NativeHistoryDesiredRoleV2.STOP, record.generation + 1,
            definition.definition.originalRequestId, definition.definitionDigest, approvals.target.digest)
        val settled = retain(cut, next(record, parent = pending.child, pending = null, role = stopped,
            attempt = attempt.copy(phase = NativeHistoryAttemptPhaseV2.COMPLETED), firstChild = facts, receipt = receipt))
        originalCompletion = OriginalInstallationCompletion(ownerIdentity, Any(), definition,
            attempt.facts, approvals, receipt, facts, settled.record.generation, settled.recordDigest)
        return settled
    }

    private fun requirePendingOriginal(record: NativeHistoryAnchorRecordV2) {
        val attempt = requireNotNull(record.attempt)
        known(record.database != null && record.parent == null && record.firstChild == null && record.receipt == null &&
            record.issuerAcknowledgement == null && record.pending?.child?.historySequence == 1L &&
            attempt.approvals != null && attempt.submissionDigest != null &&
            attempt.phase in setOf(NativeHistoryAttemptPhaseV2.NATIVE_PENDING, NativeHistoryAttemptPhaseV2.RETIRING) &&
            record.retiredAttempts.all { it.issuerRetirementDigest != null } &&
            record.roleIntent?.role != NativeHistoryDesiredRoleV2.RUN)
    }

    fun requireIssuerParticipant(actualWork: AndroidOwnedInstallationWork, actualEnvironment: AndroidSelfOwnedEnvironment) {
        known(actualWork === work && actualEnvironment === environment)
        guard()
    }

    fun snapshotOriginal(): OriginalNativeHistorySnapshot {
        environment.requireExistingOriginalObservation()
        return readOriginal(null)
    }
    private fun readOriginal(initialization: AndroidOriginalCompanionInitialization?): OriginalNativeHistorySnapshot {
        initialization?.requireAnchor(this)
        guard()
        val binding = owner.readOriginalStateForHistoryNative().originalBinding
        val allocation = signing.originalHistoryAllocation(binding,initialization)
        val encoded = namespace.readHistoryAnchorBytes() ?: uncertain()
        val envelope = try { SelfOwnedNativeHistoryWireV2.decodeEnvelope(encoded) } finally { encoded.fill(0) }
        var aad: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            val actualAad = SelfOwnedNativeHistoryWireV2.envelopeAad(allocation); aad = actualAad
            signing.verifyHistoryEnvelope(allocation,actualAad,envelope,initialization)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,material.storageKey(),GCMParameterSpec(128,envelope.nonce))
            cipher.updateAAD(actualAad)
            val bytes = cipher.doFinal(envelope.ciphertext); plaintext = bytes
            known(bytes.size in 1..SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES)
            val record = SelfOwnedNativeHistoryWireV2.decodeRecord(bytes)
            if(initialization!=null) requireInitialRecord(record)
            known(record.allocation.digest == allocation.digest &&
                record.allocation.originalNativeBindingDigest == allocation.originalNativeBindingDigest)
            requireOriginalMasterSdkAssociation(record, allocation, initialization)
            record.database?.let { known(it.namespaceDigest == namespace.historyNamespaceDigest()) }
            verifyOriginalProofs(record)
            val result = OriginalNativeHistorySnapshot(ownerIdentity,Any(),lifetimeId,binding,record,sha256(bytes))
            material.assertOriginalDataKeys(); guard()
            return result
        } finally { plaintext?.fill(0); aad?.fill(0); envelope.clear() }
    }

    fun requireSameOriginalCut(expected: OriginalNativeHistorySnapshot): OriginalNativeHistorySnapshot {
        known(expected.ownerIdentity === ownerIdentity && expected.lifetimeId == lifetimeId)
        val actual = snapshotOriginal()
        known(actual.record.generation == expected.record.generation && actual.recordDigest == expected.recordDigest &&
            actual.record.denialGeneration == expected.record.denialGeneration)
        return actual
    }

    private fun verifyOriginalProofs(record: NativeHistoryAnchorRecordV2) {
        val attempt = record.attempt
        if (attempt != null) {
            val definition = requireNotNull(record.definition)
            attempt.humanApproval?.let { signing.verifyOriginalHumanApproval(definition,attempt.facts,it) }
            attempt.targetApproval?.let { signing.verifyOriginalTargetApproval(definition,attempt.facts,requireNotNull(attempt.humanApproval),it) }
            record.receipt?.let { receipt ->
                val first = requireNotNull(record.firstChild)
                val expected = LocalBranchInstallationWireV2.acknowledgement(definition,attempt.facts,requireNotNull(attempt.approvals),receipt,first)
                record.issuerAcknowledgement?.let { retained ->
                    val expectedBytes = expected.copyWireBytes()
                    try {
                        val actualBytes = retained.frame.copyWireBytes()
                        try { known(expectedBytes.contentEquals(actualBytes)) } finally { actualBytes.fill(0) }
                    } finally { expectedBytes.fill(0) }
                }
            }
        }
        record.issuerAcknowledgement?.let { signing.verifyOriginalAcknowledgement(it) }
        guard()
    }

    /** Authenticating the companion also joins its SDK original to these CURRENT exact
     * native allocation/application participants. C1 preparation, completion and recovered
     * closure all read through this path; legacy absence never authorizes SDK acquisition. */
    private fun requireOriginalMasterSdkAssociation(record: NativeHistoryAnchorRecordV2,
        actualAllocation: OriginalAllocationCommitmentV2, initialization: AndroidOriginalCompanionInitialization?) {
        val original = record.masterSdkOriginal
        if (original == null) {
            known(record.recordFormat < SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT)
            return
        }
        known(record.recordFormat == SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT &&
            original.allocation.originalNativeBindingDigest == actualAllocation.originalNativeBindingDigest &&
            original.nativeApplication.same(environment.target) && original.nativeApplication.same(owner.materialAtOpen.target))
        val actualBytes = LocalBranchInstallationWireV2.encodeAllocation(actualAllocation)
        try {
            val originalBytes = LocalBranchInstallationWireV2.encodeAllocation(original.allocation)
            try { known(actualBytes.contentEquals(originalBytes)) } finally { originalBytes.fill(0) }
        } finally { actualBytes.fill(0) }
        // Only the first producer still holds a Kit. Existing readers never create one
        // just to turn persisted public DATA into evidence of current SDK custody.
        if (initialization != null) owner.requireInitialMasterSdkOriginalNative(initialization, original)
        guard()
    }

    /**
     * Native producer for a proposal ONLY. Not exposed by the D2-R facade; the separately
     * approved D2-W owner must perform complete canonical/protected-seed capacity preflight
     * before calling. Existing original record is mandatory: missing companion is not fresh.
     */
    fun retainOriginalProposal(snapshot: LocalBranchInstallationSnapshotV2): OriginalInstallationProposal {
        val cut = snapshotOriginal(); val record = cut.record
        known(record.parent == null && record.pending == null && record.database == null && record.attempt == null)
        known(record.retiredAttempts.lastOrNull()?.issuerRetirementDigest != null || record.retiredAttempts.isEmpty())
        known(snapshot.allocationDigest == record.allocation.digest)
        val canonical = LocalBranchInstallationWireV2.snapshotDefinition(snapshot.definition)
        known(canonical.definitionDigest == snapshot.definitionDigest)
        val previous = record.definition
        known(previous == null || previous.definitionDigest == canonical.definitionDigest)
        // This native promise must exist BEFORE A0b accepts its durable P1 admission.
        // Definition alone is insufficient: after a terminal issuer join it is historical,
        // and must not silently reserve a fresh attempt or spend the last terminal force.
        val reservation = NativeHistoryInstallationReservationV2(record.retiredAttempts.size.toLong()+1,canonical.definitionDigest)
        known(record.installationReservation == null || record.installationReservation == reservation)
        val retained = if (previous == null || record.installationReservation == null)
            retain(cut, next(record,definition=canonical,reservation=reservation)) else cut
        requireInstallationReservation(retained.record,canonical)
        return OriginalInstallationProposal(ownerIdentity,Any(),canonical,retained.record.generation,retained.recordDigest)
            .also { originalProposal = it }
    }

    fun requireOriginalProposal(proposal: OriginalInstallationProposal): LocalBranchInstallationSnapshotV2 {
        known(proposal.ownerIdentity === ownerIdentity && proposal === originalProposal)
        val actual = snapshotOriginal().record
        known(actual.parent == null && actual.pending == null && actual.database == null && actual.attempt == null &&
            actual.definition?.definitionDigest == proposal.snapshot.definitionDigest && actual.allocation.digest == proposal.snapshot.allocationDigest)
        known(actual.retiredAttempts.lastOrNull()?.issuerRetirementDigest != null || actual.retiredAttempts.isEmpty())
        requireInstallationReservation(actual,proposal.snapshot)
        return requireNotNull(actual.definition)
    }

    /**
     * Latent D2-W join, not exposed by P3. Only the SAME-work actual P1 helper can supply its
     * currently installed one-shot admission. Its durable claim/output/terminal capacity and
     * full reserved-ID census MUST precede this first attempt force. Raw head scalars, expired
     * snapshot facts, cancellation or a parsed reservation cannot replace that capability.
     */
    fun reserveOriginalInstallationAttempt(
        proposal: OriginalInstallationProposal,
        admission: AndroidLocalCompanyInstallationAdmission,
    ): OriginalInstallationAttempt {
        val snapshot = requireOriginalProposal(proposal)
        val cut = snapshotOriginal(); val previous = cut.record
        known(previous.attempt == null && previous.retiredAttempts.size < SelfOwnedNativeHistoryWireV2.MAX_RETIRED_ATTEMPTS)
        requireInstallationReservation(previous,snapshot)
        val sequence = previous.retiredAttempts.size.toLong()+1
        val candidateId = AndroidP256Primitives.newId()
        val c = previous.allocation.coordinates; val h = snapshot.definition.human
        val reserved = setOf(c.allocationId,c.scope.organizationId,c.scope.companyId,c.scope.branchId,c.installationId,
            c.assignmentId,c.deviceId,c.storageInstanceId,c.storageKeyId,c.pepperKeyId,c.targetSigningKeyId,c.firstActorId,
            h.rootId,h.rootKeyId,h.foundingActionId,h.headAuthorityId,h.headEnrollmentId,h.headInstallationKeyId,
            snapshot.definition.originalRequestId,lifetimeId)
        known(candidateId !in reserved && previous.retiredAttempts.none { it.facts.issuedAttemptId == candidateId })
        // Consumed exactly once before any attempt write; refusal leaves P1's durable admission
        // reserved. The genuine P1 helper invalidates the token on this call's return OR throw.
        admission.requireBeforeAttemptRetention(this,work,environment,proposal,snapshot,sequence,candidateId)
        val clock = owner.historyClockSampleNative()
        known(candidateId != clock.issuerContextId && candidateId != clock.moment.bootId)
        val now = clock.moment.elapsedMillis
        val deadline = minOf(admission.deadlineElapsedMillis,work.currentDeadlineMillis())
        known(now >= 0 && deadline > now)
        // OS wall time is original audit information only. Cross-attempt UTC ordering is not
        // a purchasing schedule, cloud clock grant, startup condition or ceremony authority.
        val wall = System.currentTimeMillis(); known(wall >= 0)
        val facts = LocalInstallationAttemptFactsV2(candidateId,sequence,
            clock.issuerContextId,lifetimeId,previous.generation,cut.recordDigest,admission.headAccountRevision,admission.headCredentialRevision,
            admission.installationPurposeRevision,admission.installationKeyRevision,admission.protectedHeadStateDigest,
            cut.recordDigest,environment.bootCount,clock.moment.bootId,now,deadline,wall,null)
        val attempt = NativeHistoryAttemptRecordV2(facts,NativeHistoryAttemptPhaseV2.PRE_SQL,previous.denialGeneration,null,null,null,null)
        // Atomic native conversion of the retained capacity reservation into this exact
        // attempt. A failed/unknown force cannot cancel either side or refund its budget.
        retain(cut,next(previous,attempt=attempt,reservation=null))
        val value = OriginalInstallationAttempt(ownerIdentity,Any(),snapshot,facts,previous.denialGeneration)
        originalAttempt = value; originalProposal = null
        requireOriginalAttemptForIssuance(value)
        return value
    }

    /** Historical original output may be reread after expiry/withdrawal. This does not sign. */
    fun readOriginalInstallationAttempt(): OriginalInstallationAttempt {
        val record = snapshotOriginal().record
        val stored = record.attempt ?: uncertain()
        val definition = record.definition ?: uncertain()
        val existing = originalAttempt
        if (existing != null && existing.facts == stored.facts && existing.denialGeneration == stored.denialGeneration &&
            existing.snapshot.definitionDigest == definition.definitionDigest) return existing
        return OriginalInstallationAttempt(ownerIdentity,Any(),definition,stored.facts,stored.denialGeneration).also { originalAttempt = it }
    }
    fun readOriginalInstallationAttempt(attempt: OriginalInstallationAttempt): OriginalInstallationAttempt {
        requireHistoricalAttempt(attempt)
        return attempt
    }

    fun requireOriginalAttemptForIssuance(attempt: OriginalInstallationAttempt) {
        val actual = requireHistoricalAttempt(attempt)
        known(actual.parent == null && actual.pending == null && actual.database == null &&
            actual.attempt?.phase == NativeHistoryAttemptPhaseV2.PRE_SQL && actual.denialGeneration == attempt.denialGeneration)
        val clock = owner.historyClockSampleNative(); val facts = attempt.facts
        known(facts.nativeCustodyLifetimeId == lifetimeId && facts.issuerContextId == clock.issuerContextId &&
            facts.osBootCount == environment.bootCount && facts.monotonicBootId == clock.moment.bootId &&
            clock.moment.elapsedMillis >= facts.observedElapsedMillis && clock.moment.elapsedMillis < facts.deadlineElapsedMillis)
        known(owner.newCeremoniesAllowedNoIo())
    }

    /**
     * P1 has already forced/reread its exact original issued proof. Rejoining those exact bytes
     * is bookkeeping, not another signature or fresh head authentication. A conflicting proof
     * cannot replace a prior output, including after withdrawal/expiry/caller cancellation.
     */
    fun retainOriginalIssuerApproval(attempt: OriginalInstallationAttempt, proof: LocalInstallationSignedApprovalV2) {
        val previous = requireHistoricalAttempt(attempt)
        val stored = requireNotNull(previous.attempt)
        signing.verifyOriginalHumanApproval(attempt.snapshot,attempt.facts,proof)
        stored.humanApproval?.let { known(it.digest == proof.digest); return }
        known(stored.phase == NativeHistoryAttemptPhaseV2.PRE_SQL && previous.denialGeneration == attempt.denialGeneration)
        val cut = snapshotOriginal(); known(cut.record.generation == previous.generation)
        // A0b independently checks its retained claim against this exact original snapshot,
        // facts and signed HUMAN envelope. This field references that output, not claim absence.
        val updated = stored.copy(issuerClaimDigest=proof.digest,humanApproval=proof)
        retain(cut,next(previous,attempt=updated))
    }

    fun requireOriginalCompletion(completion: OriginalInstallationCompletion) {
        known(completion.ownerIdentity === ownerIdentity && completion === originalCompletion)
        val record = snapshotOriginal().record
        val attempt = record.attempt ?: uncertain()
        known(record.pending == null && record.parent != null && attempt.phase == NativeHistoryAttemptPhaseV2.COMPLETED &&
            record.firstChild == completion.childFacts && record.receipt?.digest == completion.receipt.digest &&
            record.definition?.definitionDigest == completion.snapshot.definitionDigest && attempt.facts == completion.facts)
    }
    fun retainOriginalIssuerAcknowledgement(completion: OriginalInstallationCompletion, acknowledgement: LocalInstallationSignedAcknowledgementV2) {
        requireOriginalCompletion(completion)
        val expected = LocalBranchInstallationWireV2.acknowledgement(completion.snapshot,completion.facts,
            completion.approvals,completion.receipt,completion.childFacts)
        known(expected.digest == acknowledgement.frame.digest)
        signing.verifyOriginalAcknowledgement(acknowledgement)
        val cut = snapshotOriginal(); val previous = cut.record
        previous.issuerAcknowledgement?.let { known(it.digest == acknowledgement.digest); return }
        retain(cut,next(previous,acknowledgement=acknowledgement))
    }

    /**
     * No D2-R producer exists for this capability. D2-W must irreversibly fence the exact old
     * attempt under its real admission gate, drain actual key/SQL jobs and outer loans, and
     * complete both actual raw closes before a RECONCILED_UNINSTALLED token can be registered.
     * ARMED is NOT DB_BOUND, and a missing file/signature never proves NEVER_SUBMITTED.
     */
    fun requireOriginalRetirement(retirement: OriginalAttemptRetirement) {
        known(retirement.ownerIdentity === ownerIdentity && retirement === originalRetirement)
        val record = snapshotOriginal().record
        val retained = record.retiredAttempts.singleOrNull { it.facts.issuedAttemptId == retirement.facts.issuedAttemptId } ?: uncertain()
        known(retained.facts == retirement.facts && retained.digest == retirement.retirementDigest &&
            retained.denialGeneration == retirement.denialGeneration && record.denialGeneration >= retirement.denialGeneration)
        known(record.pending == null && record.parent == null)
        val actual = retained.copyEvidenceBytes()
        try {
            val supplied = retirement.copyEvidenceBytes()
            try { known(actual.contentEquals(supplied)) } finally { supplied.fill(0) }
        } finally { actual.fill(0) }
    }
    fun retainOriginalIssuerRetirement(retirement: OriginalAttemptRetirement, issuerClaimDigest: PayloadDigest) {
        requireOriginalRetirement(retirement)
        val cut = snapshotOriginal(); val previous = cut.record
        val entry = previous.retiredAttempts.single { it.facts.issuedAttemptId == retirement.facts.issuedAttemptId }
        entry.issuerRetirementDigest?.let { known(it == issuerClaimDigest); return }
        val evidence = entry.copyEvidenceBytes()
        try {
            val updated = NativeHistoryRetiredAttemptV2(entry.facts,entry.fate,entry.denialGeneration,evidence,issuerClaimDigest)
            retain(cut,next(previous,retired=previous.retiredAttempts.map { if (it === entry) updated else it }))
        } finally { evidence.fill(0) }
    }

    private fun requireHistoricalAttempt(value: OriginalInstallationAttempt): NativeHistoryAnchorRecordV2 {
        known(value.ownerIdentity === ownerIdentity && value === originalAttempt)
        val record = snapshotOriginal().record; val original = record.attempt ?: uncertain()
        known(original.facts == value.facts && original.denialGeneration == value.denialGeneration &&
            record.definition?.definitionDigest == value.snapshot.definitionDigest && record.allocation.digest == value.snapshot.allocationDigest)
        return record
    }

    /** Original-key force/replace/reread. Never an arbitrary generation/head reset. */
    private fun retain(previous: OriginalNativeHistorySnapshot, next: NativeHistoryAnchorRecordV2): OriginalNativeHistorySnapshot {
        val current = requireSameOriginalCut(previous).record
        // No writer exemption, including role/denial bookkeeping, repeated unknown cuts
        // or issuer joins. Only closed lifecycle progress can consume the reserved tail.
        NativeHistoryLifecycleCapacityV2.requireTransition(current,next)
        writeRecord(next)
        val actual = snapshotOriginal()
        val bytes = SelfOwnedNativeHistoryWireV2.encodeRecord(next)
        try { known(actual.record.generation == next.generation && actual.recordDigest == sha256(bytes)) }
        finally { bytes.fill(0) }
        return actual
    }

    private fun requireInitialRecord(record: NativeHistoryAnchorRecordV2) {
        val original = record.masterSdkOriginal ?: uncertain()
        known(record.recordFormat==SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT &&
            original.compositionProfile == environment.profile.sdkProfile &&
            original.exactDeviceName == original.compositionProfile.deviceName(record.allocation.coordinates.deviceId) &&
            record.generation==1L && record.denialGeneration==0L && record.definition==null && record.database==null &&
            record.parent==null && record.pending==null && record.roleIntent==null && record.attempt==null &&
            record.retiredAttempts.isEmpty() && record.firstChild==null && record.receipt==null &&
            record.issuerAcknowledgement==null && record.installationReservation==null && record.finalDeadlineDecisions.isEmpty() &&
            record.protectedFinalDecisions.isEmpty() && record.businessPublicationObservations.isEmpty())
    }
    private fun writeRecord(next: NativeHistoryAnchorRecordV2,initialization: AndroidOriginalCompanionInitialization? = null) {
        if(initialization==null) environment.requireExistingOriginalObservation()
        else {
            initialization.requireAnchor(this); requireInitialRecord(next)
            requireOriginalMasterSdkAssociation(next, next.allocation, initialization)
        }
        val plaintext = SelfOwnedNativeHistoryWireV2.encodeRecord(next)
        val aad = SelfOwnedNativeHistoryWireV2.envelopeAad(next.allocation)
        val nonce = AndroidP256Primitives.randomBytes(12)
        var ciphertext: ByteArray? = null; var signature: ByteArray? = null; var envelope: ByteArray? = null
        try {
            guard()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, material.storageKey(), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext); ciphertext = encrypted
            val signed = signing.signHistoryEnvelope(next.allocation, aad, nonce, encrypted,initialization); signature = signed
            val encoded = SelfOwnedNativeHistoryWireV2.encodeEnvelope(nonce, encrypted, signed); envelope = encoded
            namespace.retainHistoryAnchorBytes(encoded)
            if (initialization != null) requireOriginalMasterSdkAssociation(next, next.allocation, initialization)
        } finally { plaintext.fill(0); aad.fill(0); nonce.fill(0); ciphertext?.fill(0); signature?.fill(0); envelope?.fill(0) }
    }

    private fun next(old: NativeHistoryAnchorRecordV2, definition: LocalBranchInstallationSnapshotV2? = old.definition,
        attempt: NativeHistoryAttemptRecordV2? = old.attempt, retired: List<NativeHistoryRetiredAttemptV2> = old.retiredAttempts,
        acknowledgement: LocalInstallationSignedAcknowledgementV2? = old.issuerAcknowledgement,
        reservation: NativeHistoryInstallationReservationV2? = old.installationReservation,
        database: NativeHistoryDatabaseIdentityV2? = old.database,
        parent: NativeHistoryRecordedHeadV2? = old.parent, pending: NativeHistoryPendingV2? = old.pending,
        role: NativeHistoryRoleIntentV2? = old.roleIntent, denial: Long = old.denialGeneration,
        firstChild: LocalInstallationCompletedChildFactsV2? = old.firstChild,
        receipt: LocalBranchInstallationReceiptV2? = old.receipt,
        recordFormat: Int = old.recordFormat,
        finalDecisions: List<NativeHistoryFinalDeadlineDecision> = old.finalDeadlineDecisions,
        protectedDecisions: List<NativeHistoryProtectedFinalDecision> = old.protectedFinalDecisions,
        publicationObservations: List<NativeHistoryBusinessPublicationObservationProof> = old.businessPublicationObservations): NativeHistoryAnchorRecordV2 {
        known(old.generation < Long.MAX_VALUE)
        return NativeHistoryAnchorRecordV2(old.allocation,old.generation+1,denial,definition,database,
            parent,pending,role,attempt,retired,firstChild,receipt,acknowledgement,reservation,recordFormat,finalDecisions,protectedDecisions,
            publicationObservations, masterSdkOriginal = old.masterSdkOriginal)
    }
    private fun requireInstallationReservation(record: NativeHistoryAnchorRecordV2, snapshot: LocalBranchInstallationSnapshotV2) {
        known(record.attempt == null && record.definition?.definitionDigest == snapshot.definitionDigest &&
            record.installationReservation == NativeHistoryInstallationReservationV2(record.retiredAttempts.size.toLong()+1,snapshot.definitionDigest))
        // Canonical encoding checks the derived byte AND monotone-generation/denial
        // obligations. The authenticated reservation survives unrelated writes/restart.
        val bytes = SelfOwnedNativeHistoryWireV2.encodeRecord(record)
        bytes.fill(0)
    }
    private fun guard() { environment.requireCurrent(); owner.requireDataNative(); work.requireActive() }
    private fun known(value: Boolean) { if (!value) uncertain() }
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}
