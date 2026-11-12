package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId

/** One creation's native-final/creator/initial-image comparison. This is a building block
 * used by the chronological scanner, not a registered accepting history family.
 * Complete native event, ciphertext lineage, current-row/tip, census and close passes remain
 * mandatory; neither this object nor its returned seed can admit a Room/native owner.
 *
 * The caller must supply the actual preceding event, chronological census/admissions and
 * event-cut creator from the SAME native traversal. No current-row or decoded snapshot
 * factory exists here. Plaintext buffers remain borrowed and are never retained. */
internal class RuntimeDelegatedEmployeeCreationSeedJoin private constructor(
    private val observedSession: SelfOwnedNativeHistoryReadSession,
    private val observedHeader: RuntimeHistoryEventHeaderV2,
    private val observedEventDigest: PayloadDigest,
    private val observedFinal: NativeHistoryProtectedFinalDecision,
    private val evidence: RuntimeDelegatedEmployeeCreationEvidenceV1,
    versions: List<RuntimeHistoryRowVersionRowV2>,
) {
    private val versions = versions.map { it.copy(key = it.key.copyOf()) }
    private var imageAt = 0
    private var account: ProtectedLocalAccount? = null
    private var terminal = false

    private fun observedImage(version: RuntimeHistoryRowVersionRowV2, binding: ProtectedLocalBinding,
        plaintextDigest: PayloadDigest, actualAccount: ProtectedLocalAccount?) {
        try {
            check(!terminal && imageAt < versions.size)
            val expected = evidence.images[imageAt]
            val retainedVersion = RuntimeHistoryCodecV2.encodeMutation(versions[imageAt])
            val suppliedVersion = RuntimeHistoryCodecV2.encodeMutation(version)
            try { historyShapeV2(retainedVersion.contentEquals(suppliedVersion)) }
            finally { retainedVersion.fill(0); suppliedVersion.fill(0) }
            historyShapeV2(binding.key.scope == evidence.authorization.scope && expected.matches(binding) &&
                plaintextDigest == expected.plaintextDigest)
            if (imageAt == 0) {
                val actual = checkNotNull(actualAccount)
                evidence.requireActualSeed(actual)
                account = actual
            }
            imageAt++
        } catch (failure: Exception) { clear(); throw failure }
    }

    /** Returns initial DATA only after every exact historical INSERT image. No session,
     * employee authority, origin registration, child preparation or native acceptance occurs. */
    private fun finish(): RuntimeEmployeeCreationSeed {
        check(!terminal)
        try {
            historyShapeV2(imageAt == versions.size)
            val actual = checkNotNull(account)
            return RuntimeEmployeeCreationSeed.delegated(evidence.original(), evidence.authorization,
                actual.credential, evidence.at)
        } finally { clear() }
    }

    fun clear() {
        terminal = true; account = null
        versions.forEach { it.key.fill(0) }
    }

    override fun toString(): String = "RuntimeDelegatedEmployeeCreationSeedJoin([REDACTED])"

    /** Unforgeable completed comparison, tied to the actual read session, header/digest,
     * native protected-final object and every initial version/image. Construction requires
     * a private join instance which never escapes InitialImages. It is historical DATA,
     * not a registered actor, native history admission or current employee authority. */
    internal class Completed private constructor(
        private val observedSession: SelfOwnedNativeHistoryReadSession,
        val header: RuntimeHistoryEventHeaderV2,
        private val eventDigest: PayloadDigest,
        val final: NativeHistoryProtectedFinalDecision,
        private val evidence: RuntimeDelegatedEmployeeCreationEvidenceV1,
        private var seed: RuntimeEmployeeCreationSeed?,
        private var versions: List<RuntimeHistoryRowVersionRowV2>?,
    ) {
        val original = evidence.original()
        val requested = original.copyRequestedDefinition()
        val originalCohostDeviceId = observedSession.original.record.allocation.coordinates.deviceId
        val eventAt get() = evidence.at
        val seedImages = evidence.images.map { image -> LocalEmployeeEnrollmentProtectedImage(
            checkNotNull(image.table.protectedTable), image.recordId, image.dimension.orEmpty(), image.plaintextDigest) }
        val admissionDigests = requested.admissions.map { admission ->
            val bytes = ProtectedLocalSecurityCodec.encodeAdmission(admission)
            try { admission.deviceId to sha256(bytes) } finally { bytes.fill(0) }
        }.sortedBy { it.first.value }

        fun matchesNativeSelection(session: SelfOwnedNativeHistoryReadSession): Boolean =
            observedSession === session && session.hasChronologicalProtectedSeedTraversal &&
                session.hasChronologicalAccountImageTraversal &&
                session.original.record.protectedFinalDecisions.any { it === final } &&
                final.child.historySequence == header.sequence && final.child.historyDigest == eventDigest

        fun sameNativeSelection(other: Completed): Boolean = observedSession === other.observedSession &&
            final === other.final && header == other.header && eventDigest == other.eventDigest

        fun requireCapsule(session: SelfOwnedNativeHistoryReadSession,
            header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
            capsule: RuntimeHistoryCapsule, intent: NativeHistoryOriginalIntentV2) {
            historyShapeV2(matchesNativeSelection(session) && this.header == header && this.eventDigest == eventDigest)
            val facts = RuntimeDelegatedEmployeeCreationWireV1.encode(evidence)
            val dependencies = RuntimeDelegatedEmployeeCreationWireV1.dependencies(evidence)
            val expectedIntent = SelfOwnedNativeHistoryWireV2.encodeIntent(NativeHistoryOriginalIntentV2(
                RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION.tag, original.requestId, original.contentDigest,
                null, original.operatorId, sha256(dependencies), sha256(facts), emptyList()))
            val actualIntent = SelfOwnedNativeHistoryWireV2.encodeIntent(intent)
            try { historyShapeV2(facts.contentEquals(capsule.facts) && dependencies.contentEquals(capsule.dependencies) &&
                expectedIntent.contentEquals(capsule.intent) && actualIntent.contentEquals(capsule.intent)) }
            finally { facts.fill(0); dependencies.fill(0); expectedIntent.fill(0); actualIntent.fill(0) }
        }

        fun takeSeed(): RuntimeEmployeeCreationSeed = checkNotNull(seed).also { seed = null }
        fun takeVersions(): List<RuntimeHistoryRowVersionRowV2> = checkNotNull(versions).also { versions = null }
        fun clear() { seed = null; versions?.forEach { it.key.fill(0) }; versions = null }
        override fun toString(): String = "RuntimeDelegatedEmployeeCreationCompleted([REDACTED])"

        companion object {
            // No caller can obtain a join instance: its constructor/begin/finish are private
            // and the actual image collector returns only the completed result.
            fun from(join: RuntimeDelegatedEmployeeCreationSeedJoin): Completed {
                val retained = join.versions.map { it.copy(key = it.key.copyOf()) }
                return try { Completed(join.observedSession, join.observedHeader, join.observedEventDigest,
                    join.observedFinal, join.evidence, join.finish(), retained) }
                catch (failure: Throwable) { retained.forEach { it.key.fill(0) }; throw failure }
            }
        }
    }

    /** Event-local bridge across the native grammar: images precede capsule facts. Only
     * exact versions, bindings, plaintext digests and the first historical ACCOUNT survive
     * the input loan. No plaintext byte buffer or non-ACCOUNT decoded value is retained.
     * EventScan reserves its bounded mutation workspace before constructing this collector.
     * Only the complete creator/final/census join can return Completed; full closure remains mandatory. */
    internal class InitialImages private constructor(private val observedSession: SelfOwnedNativeHistoryReadSession,
        private val header: RuntimeHistoryEventHeaderV2) {
        private class Observation(val binding: ProtectedLocalBinding, val digest: PayloadDigest,
            val account: ProtectedLocalAccount?)
        private val versions = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private val images = linkedMapOf<Int, Observation>()
        private var terminal = false

        fun mutation(version: RuntimeHistoryRowVersionRowV2) {
            historyShapeV2(!terminal && versions.size < header.changeCount &&
                version.sequence == header.sequence && version.ordinal == versions.size)
            versions += version.copy(key = version.key.copyOf())
        }

        fun plaintext(version: RuntimeHistoryRowVersionRowV2, binding: ProtectedLocalBinding, bytes: ByteArray) {
            historyShapeV2(!terminal && version.ordinal == versions.lastIndex && version.ordinal !in images)
            val retained = RuntimeHistoryCodecV2.encodeMutation(versions.last())
            val supplied = RuntimeHistoryCodecV2.encodeMutation(version)
            try { historyShapeV2(retained.contentEquals(supplied)) }
            finally { retained.fill(0); supplied.fill(0) }
            val account = if (version.ordinal == 0 && version.tableTag == RuntimeHistoryTableV2.ACCOUNT.tag)
                ProtectedLocalSecurityCodec.decodeAccount(binding, bytes) else null
            images[version.ordinal] = Observation(binding, sha256(bytes), account)
        }

        fun join(session: SelfOwnedNativeHistoryReadSession, previous: RuntimeHistoryEventHeaderV2,
            previousDigest: PayloadDigest, eventDigest: PayloadDigest,
            evidence: RuntimeDelegatedEmployeeCreationEvidenceV1, creator: RuntimeActionApprovalHistoryActorFacts,
            beforeAccountIds: Set<DomainId>, beforeLogins: Set<LocalLoginIdentifier>,
            admissions: Map<DomainId, PayloadDigest>, final: NativeHistoryProtectedFinalDecision): Completed {
            historyShapeV2(!terminal && session === observedSession && session.hasChronologicalProtectedSeedTraversal &&
                session.hasChronologicalAccountImageTraversal && versions.size == header.changeCount &&
                images.size == header.changeCount)
            var join: RuntimeDelegatedEmployeeCreationSeedJoin? = null
            return try {
                val active = begin(session, previous, previousDigest, header, eventDigest, evidence,
                    versions, creator, beforeAccountIds, beforeLogins, admissions, final)
                join = active
                for (version in versions) {
                    val observed = checkNotNull(images[version.ordinal])
                    active.observedImage(version, observed.binding, observed.digest, observed.account)
                }
                Completed.from(active)
            } finally { join?.clear(); clear() }
        }

        fun clear() {
            terminal = true
            versions.forEach { it.key.fill(0) }; versions.clear(); images.clear()
        }

        override fun toString(): String = "RuntimeDelegatedEmployeeCreationInitialImages([REDACTED])"

        companion object {
            fun observe(session: SelfOwnedNativeHistoryReadSession,
                header: RuntimeHistoryEventHeaderV2): InitialImages? =
                if (session.hasChronologicalProtectedSeedTraversal && session.hasChronologicalAccountImageTraversal &&
                    header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION &&
                    header.changeCount in 6..261) InitialImages(session, header) else null
        }
    }

    companion object {
        private fun begin(session: SelfOwnedNativeHistoryReadSession, previous: RuntimeHistoryEventHeaderV2,
            previousDigest: PayloadDigest, header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
            evidence: RuntimeDelegatedEmployeeCreationEvidenceV1,
            versions: List<RuntimeHistoryRowVersionRowV2>, creator: RuntimeActionApprovalHistoryActorFacts,
            beforeAccountIds: Set<DomainId>, beforeLogins: Set<LocalLoginIdentifier>, admissions: Map<DomainId, PayloadDigest>,
            final: NativeHistoryProtectedFinalDecision): RuntimeDelegatedEmployeeCreationSeedJoin {
            // Detach all decoded lists from the caller before retaining this event's DATA.
            val canonical = RuntimeDelegatedEmployeeCreationWireV1.encode(evidence)
            val frozen = try { RuntimeDelegatedEmployeeCreationWireV1.decode(canonical) } finally { canonical.fill(0) }
            val native = session.original.record
            val coordinates = native.allocation.coordinates
            val scope = coordinates.scope
            val original = frozen.original()
            val definition = checkNotNull(native.definition)
            val attempt = checkNotNull(native.attempt)
            val child = checkNotNull(native.firstChild)
            val receipt = checkNotNull(native.receipt)
            historyShapeV2(header.purpose == RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION &&
                header.scope == scope.storageKey() && original.scope == scope &&
                header.storageId == coordinates.storageInstanceId && header.assignmentId == coordinates.assignmentId &&
                header.bindingDigest == native.allocation.originalNativeBindingDigest &&
                header.originalAllocationDigest == native.allocation.digest && header.definitionDigest == definition.definitionDigest &&
                header.installationAttemptId == attempt.facts.issuedAttemptId &&
                header.installationAttemptSequence == attempt.facts.attemptSequence &&
                header.installationGenesisDigest == child.installationGenesisDigest && header.installationGenesisDigest == receipt.genesis &&
                header.commandEpoch == definition.definition.authorityEpoch.value &&
                header.sequence > 1 && original.actorId != coordinates.firstActorId &&
                coordinates.firstActorId in beforeAccountIds && original.operatorId in beforeAccountIds &&
                original.actorId !in beforeAccountIds && frozen.beforeAccountIds == beforeAccountIds.sortedBy { it.value } &&
                beforeLogins.size == beforeAccountIds.size && original.copyRequestedDefinition().definition.login !in beforeLogins)
            // Creation is a protected insert, never a business command, role or recovery transition.
            historyShapeV2(header.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag &&
                header.parentSequence == previous.sequence && header.sequence == previous.sequence + 1 &&
                header.parentDigest == previousDigest && header.scope == previous.scope &&
                header.storageId == previous.storageId && header.bindingDigest == previous.bindingDigest &&
                header.originalAllocationDigest == previous.originalAllocationDigest &&
                header.definitionDigest == previous.definitionDigest && header.installationAttemptId == previous.installationAttemptId &&
                header.installationAttemptSequence == previous.installationAttemptSequence && header.assignmentId == previous.assignmentId &&
                header.installationGenesisDigest == previous.installationGenesisDigest && header.commandEpoch == previous.commandEpoch &&
                header.commandSequence == previous.commandSequence && header.commandDigest == previous.commandDigest &&
                header.roleCommitment == previous.roleCommitment && header.recoveryCommitment == previous.recoveryCommitment)
            requireFinal(session, header, eventDigest, frozen, final)
            val access = frozen.authorization.access
            val observations = listOf(LocalAuthenticationObservation(access.issuerContextId, access.observedAt),
                LocalAuthenticationObservation(access.issuerContextId, frozen.at),
                LocalAuthenticationObservation(frozen.preflightContextId, frozen.preflightAt), final.observed)
            historyShapeV2(observations.all { creator.employeeCreation(session, header, frozen, it) })
            requireAdmissions(scope, original, admissions)
            historyShapeV2(header.changeCount == frozen.images.size && versions.size == frozen.images.size)
            versions.forEachIndexed { ordinal, version -> requireVersion(scope, header, version, frozen.images[ordinal], ordinal) }
            return RuntimeDelegatedEmployeeCreationSeedJoin(session, header, eventDigest, final, frozen, versions)
        }

        private fun requireAdmissions(scope: BranchScope, original: LocalDelegatedEmployeeCreationV1,
            actual: Map<DomainId, PayloadDigest>) {
            val requested = original.copyRequestedDefinition()
            historyShapeV2(actual.size in 1..256 && requested.admissions.size == actual.size &&
                requested.admissions.map { it.deviceId }.distinct().size == actual.size &&
                requested.admissions.all { it.scope == scope } &&
                requested.admissions.filter { it.admitted }.map { it.deviceId }.toSet() == requested.definition.admittedDeviceIds)
            val commitments = requested.admissions.associate { admission ->
                val bytes = ProtectedLocalSecurityCodec.encodeAdmission(admission)
                try { admission.deviceId to sha256(bytes) } finally { bytes.fill(0) }
            }
            // These are the complete chronological commitments from actual admission
            // origin/revocation replay. The requested list supplies no census authority.
            historyShapeV2(commitments == actual)
        }

        private fun requireVersion(scope: BranchScope, header: RuntimeHistoryEventHeaderV2,
            version: RuntimeHistoryRowVersionRowV2, image: RuntimeEmployeeCreationImageV1, ordinal: Int) {
            version.requireShape()
            val key = image.key(scope)
            try { historyShapeV2(version.sequence == header.sequence && version.ordinal == ordinal &&
                version.tableTag == image.tableTag && version.key.contentEquals(key) && version.version == 1L &&
                version.previousSequence == null && version.previousOrdinal == null && version.previousVersion == null &&
                version.beforeRowId == null && version.beforeDigest == null &&
                version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                version.afterRowId != null && version.afterDigest != null &&
                version.afterKind == (if (image.table.immutable) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag
                    else RuntimeHistoryAfterKindV2.IMAGE.tag)) }
            finally { key.fill(0) }
        }

        private fun requireFinal(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
            eventDigest: PayloadDigest, evidence: RuntimeDelegatedEmployeeCreationEvidenceV1,
            final: NativeHistoryProtectedFinalDecision) {
            val original = evidence.original()
            val facts = RuntimeDelegatedEmployeeCreationWireV1.encode(evidence)
            try {
                val dependencies = RuntimeDelegatedEmployeeCreationWireV1.dependencies(evidence)
                try {
                    val intent = SelfOwnedNativeHistoryWireV2.encodeIntent(NativeHistoryOriginalIntentV2(
                        RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION.tag, original.requestId, original.contentDigest,
                        null, original.operatorId, sha256(dependencies), sha256(facts), emptyList()))
                    try { historyShapeV2(header.factBytes == facts.size && header.factDigest == sha256(facts) &&
                        header.dependencyBytes == dependencies.size && header.dependencyDigest == sha256(dependencies) &&
                        header.intentBytes == intent.size && header.intentDigest == sha256(intent)) }
                    finally { intent.fill(0) }
                } finally { dependencies.fill(0) }
            } finally { facts.fill(0) }
            historyShapeV2(session.original.record.protectedFinalDecisions.any { it === final } &&
                final.scope == original.scope && final.originalAllocationDigest == session.original.record.allocation.digest &&
                final.originalIntentDigest == header.intentDigest && final.declaration == evidence.protectedFinalDeclaration() &&
                final.child == NativeHistoryRecordedHeadV2(header.sequence, eventDigest, header.installationGenesisDigest,
                    header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment))
        }
    }
}
