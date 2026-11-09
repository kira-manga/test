package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Complete native-selected employee initial-image/event join, separate from the founding
 * employee reducer. The native prepass supplies the ACTUAL original enrollment images before
 * EVENTS, not a current credential candidate, firstActor fallback or parsed ledger capability.
 * All ordinary exhaustive census/events/lineages/current/relationship passes remain mandatory.
 * No source, count, current row, certificate or callback alone removes a hold or grants custody. */
internal class RuntimeEmployeeEnrollmentHistoryJoin private constructor(
    private val session: SelfOwnedNativeHistoryReadSession,
    private val earlierReplayTarget: RuntimeEarlierOrdinaryCommandReplayTarget?,
) {
    constructor(session: SelfOwnedNativeHistoryReadSession) : this(session, null)

    private val original = session.original
    private val coordinates = original.record.allocation.coordinates
    private val scope = coordinates.scope
    private class Entry(val source: NativeEmployeeEnrollmentHistorySource) {
        var header: RuntimeHistoryEventHeaderV2? = null
        val versions = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        var replay: RuntimeEmployeeAuthenticationReplay? = null
        var activated = false
        var eventJoined = false
        var currentAccount: PayloadDigest? = null
        var currentPolicy: PayloadDigest? = null
        var currentConfiguration: PayloadDigest? = null
        val currentThrottles = linkedMapOf<ThrottleKey, PayloadDigest>()
        fun clear() {
            versions.forEach { it.key.fill(0) }; versions.clear()
            replay?.clear(); replay = null; header = null
            currentAccount = null; currentPolicy = null; currentConfiguration = null; currentThrottles.clear()
        }
    }
    private val entries = session.employeeEnrollments.map(::Entry)
    private val bySequence = entries.associateBy { it.source.child.historySequence }
    private val byActor = entries.associateBy { it.source.requested.request.targetId }
    private val currentAdmissions = linkedMapOf<DomainId, PayloadDigest>()
    // Genuine C1/native original admission origin, NOT a reversal of current revoked rows.
    // Enrollment compares its native historical admission census at its actual event cut;
    // the final full current census is joined only after exact chronological projection.
    private val chronologicalAdmissions = linkedMapOf(coordinates.deviceId to digest(
        ProtectedLocalSecurityCodec.encodeAdmission(checkNotNull(original.record.definition).definition.masterAdmission)))
    // Exact successfully joined original sources survive later negative admission tips.
    // A retained enrollment list or native selection alone cannot register an origin here.
    private val joinedAdditionalAdmissions = linkedMapOf<DomainId, NativeDeviceAdmissionHistorySource>()
    private val activeActors = linkedSetOf(coordinates.firstActorId)
    private val activeLogins = linkedSetOf(localLoginKey(scope, checkNotNull(original.record.definition).definition.firstAccount.login))
    private var seedAt = 0
    private var seed: SeedScan? = null
    private var prepassEnded = !session.hasEmployeeSeedTraversal
    private var enabled = true
    private var earlierFoundingTargetAuthorized = false
    private class NativeOriginalPrefixCutoff(val header: RuntimeHistoryEventHeaderV2,
        private val eventDigest: PayloadDigest, private var evidence: RuntimeNativeOriginalImportEvidenceV1?) {
        private var lastObservedSequence = 0L
        private var lastObservedCommandSequence = 0L
        private var settled = false

        fun requireSame(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
            evidence: RuntimeNativeOriginalImportEvidenceV1) {
            historyShapeV2(!settled && this.evidence === evidence && this.header == header && this.eventDigest == eventDigest)
        }
        fun requirePrior(value: RuntimeHistoryEventHeaderV2) {
            historyShapeV2(value.purpose == RuntimeWritePurposeV2.COMMAND_APPLY)
            requirePriorCoordinates(value)
        }
        fun requirePriorFoodPaymentRejection(value: RuntimeHistoryEventHeaderV2,
            command: RuntimeCommandAuthorizationEvidenceV1) {
            historyShapeV2(RuntimeHistoricalFoodPaymentRejectionProfile.fromCommand(CommandKey(command.commandName, command.commandVersion))
                ?.matches(value, command) == true)
            requirePriorCoordinates(value)
        }
        private fun requirePriorCoordinates(value: RuntimeHistoryEventHeaderV2) {
            historyShapeV2(!settled && evidence != null &&
                value.sequence > lastObservedSequence && value.sequence < header.sequence &&
                value.commandSequence > lastObservedCommandSequence && value.commandSequence < header.commandSequence &&
                value.scope == header.scope && value.storageId == header.storageId && value.bindingDigest == header.bindingDigest &&
                value.originalAllocationDigest == header.originalAllocationDigest && value.definitionDigest == header.definitionDigest &&
                value.installationAttemptId == header.installationAttemptId && value.installationAttemptSequence == header.installationAttemptSequence &&
                value.assignmentId == header.assignmentId && value.installationGenesisDigest == header.installationGenesisDigest &&
                value.commandEpoch == header.commandEpoch && value.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag)
            lastObservedSequence = value.sequence
            lastObservedCommandSequence = value.commandSequence
        }
        fun settle() { historyShapeV2(!settled && evidence != null); settled = true }
        fun clear() { evidence = null }
    }
    // Computation-only cutoff identity; never an authorization, source capture or EOF grant.
    // Keep the disposed marker so clear() cannot turn this prefix into a full replay scope.
    private var nativeOriginalPrefixCutoff: NativeOriginalPrefixCutoff? = null
    val sourceCount: Int get() = entries.size
    var eventCount = 0L
        private set
    var enrollmentMutations = 0L
        private set
    var throttleMutations = 0L
        private set
    var authenticationMutations = 0L
        private set
    var sessionRevocationMutations = 0L
        private set
    var enrollmentImages = 0L
        private set
    var administrationCancellationMutations = 0L
        private set
    var deviceRevocationMutations = 0L
        private set
    var policyActivationMutations = 0L
        private set
    var serialPublicationRequestMutations = 0L
        private set
    val mutationCount: Long get() = enrollmentMutations + throttleMutations + authenticationMutations + sessionRevocationMutations +
        administrationCancellationMutations + deviceRevocationMutations + policyActivationMutations + serialPublicationRequestMutations
    val tipCount: Long get() = entries.sumOf { it.replay?.tipCount ?: 0L }
    val sessionCount: Long get() = entries.sumOf { it.replay?.sessionCount ?: 0L }
    val commandCount: Long get() = entries.sumOf { it.replay?.commandCount ?: 0L }
    val joinedCommandCount: Long get() = entries.sumOf { it.replay?.joinedCommandCount ?: 0L }
    val retainedCommandCount: Int get() = entries.sumOf { it.replay?.retainedCommandCount ?: 0 }
    val commandMutationCount: Long get() = entries.sumOf { it.replay?.commandMutationCount ?: 0L }
    val supervisorReviewCount: Long get() = entries.sumOf { it.replay?.supervisorReviewCount ?: 0L }
    val supervisorReviewMutationCount: Long get() = entries.sumOf { it.replay?.supervisorReviewMutationCount ?: 0L }
    val purchasingPolicyRejectionCount: Long get() = entries.sumOf { it.replay?.purchasingPolicyRejectionCount ?: 0L }
    val purchasingPolicyRejectionMutationCount: Long get() = entries.sumOf { it.replay?.purchasingPolicyRejectionMutationCount ?: 0L }
    val orderReassignmentRejectionCount: Long get() = entries.sumOf { it.replay?.orderReassignmentRejectionCount ?: 0L }
    val orderReassignmentRejectionMutationCount: Long get() = entries.sumOf { it.replay?.orderReassignmentRejectionMutationCount ?: 0L }
    val openingSupplierObligationRejectionCount: Long get() = entries.sumOf { it.replay?.openingSupplierObligationRejectionCount ?: 0L }
    val openingSupplierObligationRejectionMutationCount: Long get() = entries.sumOf { it.replay?.openingSupplierObligationRejectionMutationCount ?: 0L }
    val invoiceCaptureRejectionCount: Long get() = entries.sumOf { it.replay?.invoiceCaptureRejectionCount ?: 0L }
    val invoiceCaptureRejectionMutationCount: Long get() = entries.sumOf { it.replay?.invoiceCaptureRejectionMutationCount ?: 0L }
    val capturedInvoiceMatchRejectionCount: Long get() = entries.sumOf { it.replay?.capturedInvoiceMatchRejectionCount ?: 0L }
    val capturedInvoiceMatchRejectionMutationCount: Long get() = entries.sumOf { it.replay?.capturedInvoiceMatchRejectionMutationCount ?: 0L }
    val branchActivityActivationRejectionCount: Long get() = entries.sumOf { it.replay?.branchActivityActivationRejectionCount ?: 0L }
    val branchActivityActivationRejectionMutationCount: Long get() = entries.sumOf { it.replay?.branchActivityActivationRejectionMutationCount ?: 0L }
    val floorConfigurationRejectionCount: Long get() = entries.sumOf { it.replay?.floorConfigurationRejectionCount ?: 0L }
    val floorConfigurationRejectionMutationCount: Long get() = entries.sumOf { it.replay?.floorConfigurationRejectionMutationCount ?: 0L }
    val configurationRejectionCount: Long get() = entries.sumOf { it.replay?.configurationRejectionCount ?: 0L }
    val configurationRejectionMutationCount: Long get() = entries.sumOf { it.replay?.configurationRejectionMutationCount ?: 0L }
    val returnRightsActivationRejectionCount: Long get() = entries.sumOf { it.replay?.returnRightsActivationRejectionCount ?: 0L }
    val returnRightsActivationRejectionMutationCount: Long get() = entries.sumOf { it.replay?.returnRightsActivationRejectionMutationCount ?: 0L }
    val retailCustomerReceiptRejectionCount: Long get() = entries.sumOf { it.replay?.retailCustomerReceiptRejectionCount ?: 0L }
    val retailCustomerReceiptRejectionMutationCount: Long get() = entries.sumOf { it.replay?.retailCustomerReceiptRejectionMutationCount ?: 0L }
    val foodCheckRejectionCount: Long get() = entries.sumOf { it.replay?.foodCheckRejectionCount ?: 0L }
    val foodCheckRejectionMutationCount: Long get() = entries.sumOf { it.replay?.foodCheckRejectionMutationCount ?: 0L }
    val foodPaymentRejectionCount: Long get() = entries.sumOf { it.replay?.foodPaymentRejectionCount ?: 0L }
    val foodPaymentRejectionMutationCount: Long get() = entries.sumOf { it.replay?.foodPaymentRejectionMutationCount ?: 0L }
    val returnCaseQualificationRejectionCount: Long get() = entries.sumOf { it.replay?.returnCaseQualificationRejectionCount ?: 0L }
    val returnStockDispositionRejectionCount: Long get() = entries.sumOf { it.replay?.returnStockDispositionRejectionCount ?: 0L }
    val returnCaseQualificationRejectionMutationCount: Long get() = entries.sumOf { it.replay?.returnCaseQualificationRejectionMutationCount ?: 0L }
    val returnStockDispositionRejectionMutationCount: Long get() = entries.sumOf { it.replay?.returnStockDispositionRejectionMutationCount ?: 0L }
    fun count(table: RuntimeHistoryTableV2): Long = entries.sumOf { it.replay?.count(table) ?: 0L }

    /** Exact historical employee lifecycle authority join at this event cut. */
    internal fun lifecycle(event: LocalAuthEvent, audit: LocalMasterLifecycleAudit): Boolean {
        val replay = event.operatorId?.let { byActor[it]?.replay } ?: return false
        return replay.lifecycle(event, audit)
    }

    init {
        earlierReplayTarget?.requireSameSession(session)
        historyShapeV2(entries.size <= LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS && bySequence.size == entries.size && byActor.size == entries.size &&
            (session.hasEmployeeSeedTraversal || entries.isEmpty()))
        for (entry in entries) historyShapeV2(entry.source.requested.request.scope == scope &&
            entry.source.requested.request.targetId != coordinates.firstActorId && entry.source.originalCohostDeviceId == coordinates.deviceId)
    }

    /** Hashes of decoded current rows are closure targets ONLY. Credentials/accounts used
     * to seed replay arrive later from the independently selected native initial images. */
    fun census(binding: ProtectedLocalBinding, bytes: ByteArray, value: Any) {
        val commitment = sha256(bytes)
        when (value) {
            is ProtectedLocalAccount -> byActor[value.state.actorId]?.let { entry ->
                historyShapeV2(entry.currentAccount == null); entry.currentAccount = commitment
            }
            is ActivatedLocalPolicy -> byActor[value.actorId]?.let { entry ->
                historyShapeV2(entry.currentPolicy == null); entry.currentPolicy = commitment
            }
            is LocalAuthConfiguration -> byActor[value.actorId]?.let { entry ->
                historyShapeV2(entry.currentConfiguration == null); entry.currentConfiguration = commitment
            }
            is PersistentThrottleState -> byActor[value.key.actorId]?.let { entry ->
                historyShapeV2(binding.key == ProtectedLocalRecordKey.Throttle(value.key) && entry.currentThrottles.put(value.key, commitment) == null)
                historyCapacityV2(entry.currentThrottles.size <= 257)
            }
            is LocalDeviceAdmission -> {
                historyShapeV2(currentAdmissions.put(value.deviceId, commitment) == null)
                historyCapacityV2(currentAdmissions.size <= 256)
            }
        }
    }

    fun startSeed(row: RuntimeHistoryEventRowV2, header: RuntimeHistoryEventHeaderV2) {
        try {
            historyShapeV2(session.hasEmployeeSeedTraversal && !prepassEnded && seed == null && seedAt < entries.size)
            val entry = entries[seedAt]
            requireEnrollmentHeader(entry.source, row, header)
            seed = SeedScan(entry, row, header)
        } catch (failure: Throwable) { row.header.fill(0); throw failure }
    }
    fun seedMutation(row: RuntimeHistoryRowVersionRowV2) = checkNotNull(seed).mutation(row)
    fun seedImage(row: RuntimeHistoryRowViewV2) = checkNotNull(seed).image(row)
    fun seedPlaintext(binding: ProtectedLocalBinding, bytes: ByteArray, value: Any) = checkNotNull(seed).plaintext(binding, bytes, value)
    fun seedChunk(row: RuntimeHistoryChunkRowV2) = checkNotNull(seed).chunk(row)
    fun endSeedMutations() = checkNotNull(seed).endMutations()
    fun endSeedImage(table: RuntimeHistoryTableV2) = checkNotNull(seed).endImage(table)
    fun endSeedChunks() = checkNotNull(seed).endChunks()
    fun endSeed() {
        val active = checkNotNull(seed)
        try { active.finish(); seedAt++ } finally { active.clear(); seed = null }
    }
    fun endSeedPass() {
        historyShapeV2(session.hasEmployeeSeedTraversal && !prepassEnded && seed == null && seedAt == entries.size)
        prepassEnded = true
    }

    private inner class SeedScan(val entry: Entry, val row: RuntimeHistoryEventRowV2, val header: RuntimeHistoryEventHeaderV2) {
        private val source = entry.source
        private val stream = RuntimeHistoryEventStreamV2(header)
        private val capsule = RuntimeHistoryCapsule(header)
        private var mutations = 0
        private var mutationsEnded = false
        private var chunksEnded = false
        private var version: RuntimeHistoryRowVersionRowV2? = null
        private var imageRows = 0
        private var imageOpened = false
        private var imageEnded = false
        private var account: ProtectedLocalAccount? = null
        private var policy: ActivatedLocalPolicy? = null
        private var configuration: LocalAuthConfiguration? = null
        private val throttles = linkedMapOf<ThrottleKey, PersistentThrottleState>()
        private var eventOpened = false

        fun mutation(value: RuntimeHistoryRowVersionRowV2) {
            historyShapeV2(!mutationsEnded && !chunksEnded && mutations < header.changeCount)
            if (version != null) requireImageComplete()
            requireEnrollmentMutation(value, source.evidence.images[mutations], mutations)
            stream.appendMutation(value)
            val retained = value.copy(key = value.key.copyOf())
            entry.versions += retained; version = retained
            imageRows = 0; imageOpened = false; imageEnded = false; mutations++
        }
        fun image(value: RuntimeHistoryRowViewV2) {
            val version = checkNotNull(version)
            historyShapeV2(!mutationsEnded && ++imageRows == 1 && value.table.tag == version.tableTag &&
                value.rowId == version.afterRowId && value.digest().value == version.afterDigest && value.number("revision") == 0L)
            val key = value.key()
            try { historyShapeV2(key.contentEquals(version.key)) } finally { key.fill(0) }
            imageEnded = version.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag
        }
        fun plaintext(binding: ProtectedLocalBinding, bytes: ByteArray, value: Any) {
            val version = checkNotNull(version)
            val expected = source.evidence.images[version.ordinal]
            historyShapeV2(imageRows == 1 && !imageOpened && expected.matchesHistoryBinding(binding, scope) && sha256(bytes) == expected.plaintextDigest)
            val requested = source.requested
            val actor = requested.request.targetId
            when (value) {
                is ProtectedLocalAccount -> {
                    val state = value.state
                    historyShapeV2(account == null && expected.table == ProtectedLocalTable.ACCOUNT && state.scope == scope && state.actorId == actor &&
                        state.enabled && state.revision == Revision(0) && state.generation == SessionGeneration(0) && state.sessions.isEmpty() &&
                        value.credentialRevision == Revision(0) && requested.credentialWasProvided == (value.credential != null))
                    account = value
                }
                is ActivatedLocalPolicy -> {
                    historyShapeV2(policy == null && value.scope == scope && value.actorId == actor && value.revision == Revision(0))
                    val retained = requested.copyCanonicalPolicy(); val semantic = LocalEffectivePolicyCodec.encodePolicy(requested.definition.policy)
                    try { historyShapeV2(bytes.contentEquals(retained) && bytes.contentEquals(semantic)) } finally { retained.fill(0); semantic.fill(0) }
                    policy = value
                }
                is LocalAuthConfiguration -> {
                    historyShapeV2(configuration == null && value.scope == scope && value.actorId == actor && value.revision == Revision(0) &&
                        value.login == requested.definition.login && value.tokens == requested.definition.tokenPolicy && value.tokens.canStartAt(source.evidence.eventAt))
                    val retained = requested.copyCanonicalConfiguration()
                    val semantic = LocalAuthenticationCodec.encodeConfiguration(LocalAuthConfiguration(scope,actor,Revision(0),
                        requested.definition.login,requested.definition.tokenPolicy))
                    try { historyShapeV2(bytes.contentEquals(retained) && bytes.contentEquals(semantic)) } finally { retained.fill(0); semantic.fill(0) }
                    configuration = value
                }
                is PersistentThrottleState -> {
                    val dimension = if (expected.dimension == "account") ThrottleDimension.Account else
                        ThrottleDimension.Device(DomainId(expected.dimension.removePrefix("device:")))
                    val key = ThrottleKey(scope,actor,dimension)
                    historyShapeV2(expected.table == ProtectedLocalTable.THROTTLE &&
                        value == PersistentThrottleState(key,Revision(0),0,source.evidence.eventAt,0,null) && throttles.put(key,value) == null)
                }
                is LocalAuthEvent -> {
                    historyShapeV2(!eventOpened && value.scope == scope && value.eventId == requested.request.requestId &&
                        value.kind == LocalAuthEventKind.ACCOUNT_PROVISIONED && value.targetId == actor && value.operatorId == null && value.sessionId == null &&
                        value.accountRevision == Revision(0) && value.at == source.evidence.eventAt && value.contentDigest == requested.request.contentDigest)
                    eventOpened = true
                }
                else -> malformed()
            }
            imageOpened = true
        }
        fun endImage(table: RuntimeHistoryTableV2) {
            val version = checkNotNull(version)
            historyShapeV2(version.tableTag == table.tag && version.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag &&
                imageRows == 1 && imageOpened && !imageEnded)
            imageEnded = true
        }
        private fun requireImageComplete() { historyShapeV2(imageRows == 1 && imageOpened && imageEnded) }
        fun endMutations() {
            historyShapeV2(!mutationsEnded && mutations == header.changeCount); requireImageComplete(); mutationsEnded = true
        }
        fun chunk(value: RuntimeHistoryChunkRowV2) {
            historyShapeV2(mutationsEnded && !chunksEnded); stream.appendChunk(value); capsule.append(value)
        }
        fun endChunks() {
            historyShapeV2(mutationsEnded && !chunksEnded)
            val commitment = stream.finish()
            historyShapeV2(commitment.digest == source.child.historyDigest && commitment.digest.value == row.eventDigest && commitment.encodedBytes == row.encodedBytes)
            requireEnrollmentCapsule(source, capsule, capsule.finish())
            chunksEnded = true
        }
        fun finish() {
            historyShapeV2(mutationsEnded && chunksEnded && entry.replay == null && entry.header == null &&
                throttles.size == source.requested.admissions.size + 1 && eventOpened)
            requireRetainedAdmissions(source)
            entry.replay = RuntimeEmployeeAuthenticationReplay.fromNativeSeeds(source,checkNotNull(account),checkNotNull(policy),
                checkNotNull(configuration),throttles,earlierReplayTarget)
            entry.header = header
        }
        fun clear() {
            row.header.fill(0); capsule.clear(); stream.clear()
            account = null; policy = null; configuration = null; throttles.clear(); version = null
        }
    }

    /** The complete ordinary EVENTS traversal independently re-joins the selected prepass.
     * The absence census is reconstructed in real event order, not from today's accounts. */
    fun enrollment(header: RuntimeHistoryEventHeaderV2, commitment: PayloadDigest, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2, mutations: List<RuntimeHistoryRowVersionRowV2>,
        previous: RuntimeHistoryEventHeaderV2, previousDigest: PayloadDigest): Boolean {
        if (header.purpose != RuntimeWritePurposeV2.PROVISIONING) return false
        val entry = bySequence[header.sequence] ?: return false
        historyShapeV2(prepassEnded && !entry.eventJoined && entry.header == header && commitment == entry.source.child.historyDigest &&
            recordedHead(previous,previousDigest) == entry.source.preparationParent && mutations.size == entry.versions.size)
        mutations.zip(entry.versions).forEach { (actual, retained) -> requireSameVersion(actual,retained) }
        requireEnrollmentCapsule(entry.source,capsule,intent)
        entry.eventJoined = true
        if (!enabled) return false
        historyShapeV2(!entry.activated && entry.replay != null && activeActors.size < 4096 &&
            entry.source.requested.request.targetId !in activeActors)
        requireRetainedAdmissions(entry.source)
        historyShapeV2(chronologicalAdmissions == entry.source.evidence.admissionDigests.toMap())
        val request = entry.source.requested.request
        val login = entry.source.requested.definition.login
        historyShapeV2(localLoginKey(scope,login) !in activeLogins && entry.source.evidence.absenceDependenciesDigest ==
            localEmployeeEnrollmentAbsenceDigestV1(request,login,activeActors,chronologicalAdmissions.keys))
        for (admission in entry.source.requested.admissions) {
            // Retained withdrawn/nonzero devices keep their genuine enrollment buckets
            // for closure, but cannot acquire a new positive authentication origin.
            if (admission.deviceId == coordinates.deviceId || !admission.admitted || admission.revision != Revision(0)) continue
            val origin = joinedAdditionalAdmissions[admission.deviceId] ?: malformed()
            checkNotNull(entry.replay).enrollmentDeviceAdmission(session, origin,
                checkNotNull(chronologicalAdmissions[admission.deviceId]))
        }
        activeActors += request.targetId; activeLogins += localLoginKey(scope,login); entry.activated = true
        eventCount = add(eventCount,1L); enrollmentMutations = add(enrollmentMutations,entry.source.evidence.images.size.toLong())
        return true
    }

    fun throttle(value: RuntimeThrottleEvidenceV1): Boolean {
        val entry = active(value.actorId) ?: return false
        if (!matchesThrottle(value)) return false
        checkNotNull(entry.replay).throttle(value)
        eventCount = add(eventCount,1L); throttleMutations = add(throttleMutations,value.changed().size.toLong())
        return true
    }

    /** Separate admission-origin lane; ordinary cohost/approval predicates stay unchanged.
     * The native enrollment and admission must BOTH already have reduced here. */
    fun additionalDeviceThrottle(value: RuntimeThrottleEvidenceV1, sequence: Long): Boolean {
        val entry = active(value.actorId) ?: return false
        if (!matchesAdditionalDeviceThrottle(value, sequence)) return false
        historyShapeV2(prepassEnded && entry.eventJoined &&
            chronologicalAdmissions[value.deviceId] == value.dependencies.admission)
        checkNotNull(entry.replay).throttle(value)
        eventCount = add(eventCount, 1L); throttleMutations = add(throttleMutations, value.changed().size.toLong())
        return true
    }

    /** Complete chronological census before a native-selected admission. Prove every
     * existing actor's actual origin and BEFORE before projecting any added device bucket.
     * This coordinator never derives a missing actor/device origin from current rows. */
    fun deviceAdmission(source: NativeDeviceAdmissionHistorySource,
        founding: RuntimeOriginalAuthenticationReplay?): Boolean {
        if (!enabled || founding == null || !session.hasChronologicalAccountImageTraversal ||
            !session.hasDeviceAdmissionHistoryTraversal) return false
        historyShapeV2(prepassEnded && session.deviceAdmissions.any { it === source } &&
            source.requested.request.scope == scope && source.originalCohostDeviceId == coordinates.deviceId &&
            source.requested.request.targetId !in chronologicalAdmissions && chronologicalAdmissions.size < 256 &&
            source.requested.request.targetId !in joinedAdditionalAdmissions &&
            source.evidence.admissionDigests.toMap() == chronologicalAdmissions &&
            source.evidence.accountDigests.map { it.first }.toSet() == activeActors)
        val actualAccounts = activeActors.sortedBy { it.value }.map { actor ->
            val digest = if (actor == coordinates.firstActorId) founding.deviceAdmissionAccountDigest(session) ?: return false
            else {
                val entry = active(actor) ?: return false
                historyShapeV2(entry.eventJoined && entry.source.child.historySequence < source.child.historySequence &&
                    entry.source.child.commandEpoch == source.child.commandEpoch)
                checkNotNull(entry.replay).deviceAdmissionAccountDigest(session)
            }
            actor to digest
        }
        historyShapeV2(actualAccounts == source.evidence.accountDigests)
        founding.deviceAdmissionThrottle(source)
        for (entry in entries.filter { it.activated }) checkNotNull(entry.replay).deviceAdmissionThrottle(source)
        chronologicalAdmissions[source.requested.request.targetId] = digest(source.requested.copyCanonicalServiceAdmission())
        joinedAdditionalAdmissions[source.requested.request.targetId] = source
        return true
    }

    /** Positive ownership proof only for a fully activated later enrollment's own native
     * bucket. False leaves admission-owned routing unchanged; this never grants permission. */
    fun ownsEnrollmentDeviceThrottle(actorId: DomainId, deviceId: DomainId,
        admissionDigest: PayloadDigest, sequence: Long): Boolean {
        val entry = active(actorId) ?: return false
        val origin = joinedAdditionalAdmissions[deviceId] ?: return false
        if (!prepassEnded || !entry.eventJoined || entry.header == null ||
            chronologicalAdmissions[deviceId] != admissionDigest ||
            origin.child.historySequence >= entry.source.child.historySequence || entry.source.child.historySequence >= sequence) return false
        return checkNotNull(entry.replay).ownsEnrollmentDeviceThrottle(session, origin, admissionDigest, sequence)
    }
    /** The enclosing issuance owns the history event. Its actual MATCH completion advances
     * these same paired buckets and mutation accounting WITHOUT a second employee event. */
    internal fun approvalThrottle(value: RuntimeThrottleEvidenceV1, header: RuntimeHistoryEventHeaderV2): Boolean {
        val entry = active(value.actorId) ?: return false
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence &&
            header.purpose == RuntimeWritePurposeV2.APPROVAL_ISSUE && value.outcome == CredentialAttemptOutcome.MATCH && value.denial == null)
        if (!matchesThrottle(value)) return false
        checkNotNull(entry.replay).throttle(value)
        throttleMutations = add(throttleMutations, value.changed().size.toLong())
        return true
    }
    internal fun approvalActor(actor: DomainId, header: RuntimeHistoryEventHeaderV2): RuntimeActionApprovalHistoryActorFacts? {
        val entry = active(actor) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).approvalActor(session, header)
    }
    internal fun serialPublicationRequest(proof: RuntimeSerialPublicationHistoryRequest): Boolean {
        val entry = active(proof.actorId) ?: return false
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < proof.header.sequence &&
            proof.header.purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST && proof.evidence.authorization.scope == scope)
        checkNotNull(entry.replay).serialPublicationRequest(proof)
        eventCount = add(eventCount, 1L); serialPublicationRequestMutations = add(serialPublicationRequestMutations, 1L)
        return true
    }
    fun authentication(value: RuntimeAuthenticationEvidenceV1): Boolean {
        val entry = active(value.actorId) ?: return false
        if (!matchesAuthentication(value)) return false
        checkNotNull(entry.replay).authentication(value)
        eventCount = add(eventCount,1L); authenticationMutations = add(authenticationMutations,value.images.size.toLong())
        return true
    }

    fun additionalDeviceAuthentication(value: RuntimeAuthenticationEvidenceV1, sequence: Long): Boolean {
        val entry = active(value.actorId) ?: return false
        if (!matchesAdditionalDeviceAuthentication(value, sequence)) return false
        historyShapeV2(prepassEnded && entry.eventJoined &&
            chronologicalAdmissions[value.deviceId] == value.dependencies.admission)
        checkNotNull(entry.replay).authentication(value)
        eventCount = add(eventCount, 1L); authenticationMutations = add(authenticationMutations, value.images.size.toLong())
        return true
    }
    /** Native selection is inert until the scanner has bound the complete actual policy
     * event/capsule/mutation cut. Only an already-activated target's own reducer may advance. */
    fun policyActivation(source: NativeEmployeePolicyActivationHistorySource,
        founding: RuntimeOriginalAuthenticationReplay?): RuntimeEmployeePolicyActivationTargetOwner? {
        if (!enabled || !session.hasChronologicalAccountImageTraversal || !session.hasEmployeePolicyHistoryTraversal) return null
        historyShapeV2(prepassEnded && session.employeePolicyActivations.any { it === source } &&
            source.requested.request.scope == scope)
        if (source.requested.request.targetId == coordinates.firstActorId) {
            if (founding?.policyActivation(source) != true) return null
            return RuntimeEmployeePolicyActivationTargetOwner.ORIGINAL
        }
        val entry = active(source.requested.request.targetId) ?: return null
        historyShapeV2(entry.eventJoined && entry.source.child.historySequence < source.child.historySequence)
        checkNotNull(entry.replay).policyActivation(source)
        eventCount = add(eventCount,1L)
        policyActivationMutations = add(policyActivationMutations,source.evidence.images.size.toLong())
        return RuntimeEmployeePolicyActivationTargetOwner.ENROLLED_EMPLOYEE
    }
    fun selfSessionRevocation(value: RuntimeSessionRevocationEvidenceV1, final: NativeHistoryProtectedFinalDecision?): Boolean {
        val entry = active(value.authorization.actorId) ?: return false
        if (!matchesSessionRevocation(value) || !checkNotNull(entry.replay).selfSessionRevocation(value,final)) return false
        eventCount = add(eventCount,1L); sessionRevocationMutations = add(sessionRevocationMutations,value.images.size.toLong())
        return true
    }

    /** Closed cross-account coordinator, not a granting callback. BOTH operator and target
     * must already exist at this real event cut in their respective native-seeded reducers.
     * A distinct native final witness is required before either target reducer advances. */
    fun retainedSessionRevocation(value: RuntimeRetainedSessionRevocationEvidenceV1,
        final: NativeHistoryProtectedFinalDecision,
        founding: RuntimeOriginalAuthenticationReplay?): RuntimeRetainedSessionRevocationTargetOwner? {
        if (!enabled) return null
        value.validate()
        val operatorPermitted = if (value.authorization.actorId == coordinates.firstActorId)
            founding?.retainedSessionRevocationAuthority(value, final) == true
        else {
            val operator = active(value.authorization.actorId) ?: return null
            historyShapeV2(prepassEnded && operator.eventJoined &&
                operator.source.child.historySequence < final.child.historySequence &&
                operator.source.child.commandEpoch == final.child.commandEpoch)
            checkNotNull(operator.replay).retainedSessionRevocationAuthority(value, final)
        }
        if (!operatorPermitted) return null
        if (value.command.targetId == coordinates.firstActorId) {
            if (founding?.retainedSessionRevocationTarget(value) != true) return null
            return RuntimeRetainedSessionRevocationTargetOwner.ORIGINAL
        }
        val target = active(value.command.targetId) ?: return null
        historyShapeV2(prepassEnded && target.eventJoined && target.source.child.historySequence < final.child.historySequence &&
            target.source.child.commandEpoch == final.child.commandEpoch)
        if (!checkNotNull(target.replay).retainedSessionRevocationTarget(value)) return null
        eventCount = add(eventCount,1L); sessionRevocationMutations = add(sessionRevocationMutations,value.images.size.toLong())
        return RuntimeRetainedSessionRevocationTargetOwner.ENROLLED_EMPLOYEE
    }

    /** One-audit cancellation joins ONLY the operator's own genuine native prefix and
     * actual final witness. Target coordinates are inert intent, never target existence/state. */
    fun administrationCancellation(value: RuntimeAdministrationCancellationEvidenceV1,
        final: NativeHistoryProtectedFinalDecision, founding: RuntimeOriginalAuthenticationReplay?): Boolean {
        if (!enabled) return false
        value.validate()
        if (value.authorization.actorId == coordinates.firstActorId)
            return founding?.administrationCancellation(value, final) == true
        val operator = active(value.authorization.actorId) ?: return false
        historyShapeV2(prepassEnded && operator.eventJoined && operator.source.child.historySequence < final.child.historySequence &&
            operator.source.child.commandEpoch == final.child.commandEpoch)
        if (!checkNotNull(operator.replay).administrationCancellation(value, final)) return false
        eventCount = add(eventCount,1L)
        administrationCancellationMutations = add(administrationCancellationMutations,value.images.size.toLong())
        return true
    }

    /** Closed whole-census cohost coordinator. Only founding + already ACTIVATED native
     * enrollments are account origins. Prove the original operator/final authority FIRST;
     * check every account's genuine BEFORE before any admission/account prediction advances.
     * One shared admission and one operator audit are counted once, never per affected actor. */
    fun deviceRevocation(value: RuntimeDeviceRevocationEvidenceV1, final: NativeHistoryProtectedFinalDecision,
        founding: RuntimeOriginalAuthenticationReplay?): Boolean {
        if (!enabled || !session.hasChronologicalAccountImageTraversal || founding == null) return false
        value.validate()
        historyShapeV2(prepassEnded && value.authorization.scope == scope && value.command.targetId == coordinates.deviceId &&
            chronologicalAdmissions[coordinates.deviceId] == value.admissionBeforeDigest &&
            value.accounts.map { it.actorId }.toSet() == activeActors)
        val operatorPermitted = if (value.authorization.actorId == coordinates.firstActorId)
            founding.deviceRevocationAuthority(value, final)
        else {
            val operator = active(value.authorization.actorId) ?: return false
            historyShapeV2(operator.eventJoined && operator.source.child.historySequence < final.child.historySequence &&
                operator.source.child.commandEpoch == final.child.commandEpoch)
            checkNotNull(operator.replay).deviceRevocationAuthority(value, final)
        }
        if (!operatorPermitted) return false
        for (record in value.accounts) {
            val actual = if (record.actorId == coordinates.firstActorId) founding.deviceRevocationCensus(coordinates.deviceId)
            else {
                val entry = active(record.actorId) ?: return false
                historyShapeV2(entry.eventJoined && entry.source.child.historySequence < final.child.historySequence &&
                    entry.source.child.commandEpoch == final.child.commandEpoch)
                checkNotNull(entry.replay).deviceRevocationCensus(coordinates.deviceId)
            }
            historyShapeV2(record == actual)
        }
        // All proofs above use PRE-effect facts. Preserve the original actual writer order:
        // admission first, then every affected account in actor order, original audit last.
        founding.deviceRevocationAdmission(value)
        for (entry in entries.filter { it.activated }) checkNotNull(entry.replay).deviceRevocationAdmission(value)
        chronologicalAdmissions[coordinates.deviceId] = value.images.first().plaintextDigest
        for (record in value.accounts) {
            if (record.actorId == coordinates.firstActorId) founding.deviceRevocationAccount(value, record)
            else checkNotNull(active(record.actorId)?.replay).deviceRevocationAccount(value, record)
        }
        if (value.authorization.actorId == coordinates.firstActorId) founding.deviceRevocationAudit(value)
        else checkNotNull(active(value.authorization.actorId)?.replay).deviceRevocationAudit(value)
        val mutations = value.affectedAccounts.count { it.actorId != coordinates.firstActorId }.toLong() +
            if (value.authorization.actorId == coordinates.firstActorId) 0L else 1L
        deviceRevocationMutations = add(deviceRevocationMutations, mutations)
        // Scanner owns this single event count; employee counters own only their mutations.
        return true
    }

    /** Bounded first withdrawal of an additional genuinely admitted device. Every retained
     * issuance has its own chronological origin; the original cohost operator and WHOLE
     * account BEFORE census are proved before any account or admission projection advances. */
    fun additionalDeviceRevocation(value: RuntimeDeviceRevocationEvidenceV2,
        final: NativeHistoryProtectedFinalDecision, source: NativeDeviceAdmissionHistorySource,
        founding: RuntimeOriginalAuthenticationReplay?): Boolean {
        if (!enabled || !session.hasChronologicalAccountImageTraversal || founding == null ||
            !session.hasDeviceAdmissionHistoryTraversal) return false
        value.validate()
        historyShapeV2(prepassEnded && session.deviceAdmissions.any { it === source } &&
            source.originalCohostDeviceId == coordinates.deviceId &&
            source.child.historySequence < final.child.historySequence && source.child.commandEpoch == final.child.commandEpoch &&
            value.authorization.scope == scope && value.command.targetId == source.requested.request.targetId &&
            value.command.targetId != coordinates.deviceId &&
            chronologicalAdmissions[value.command.targetId] == value.admissionBeforeDigest &&
            value.accounts.map { it.actorId }.toSet() == activeActors)
        val operatorPermitted = if (value.authorization.actorId == coordinates.firstActorId)
            founding.additionalDeviceRevocationAuthority(value, final)
        else {
            val operator = active(value.authorization.actorId) ?: return false
            historyShapeV2(operator.eventJoined && operator.source.child.historySequence < final.child.historySequence &&
                operator.source.child.commandEpoch == final.child.commandEpoch)
            checkNotNull(operator.replay).additionalDeviceRevocationAuthority(value, final)
        }
        if (!operatorPermitted) return false
        for (record in value.accounts) {
            val actual = if (record.actorId == coordinates.firstActorId)
                founding.additionalDeviceRevocationCensus(value.command.targetId)
            else {
                val entry = active(record.actorId) ?: return false
                historyShapeV2(entry.eventJoined && entry.source.child.historySequence < final.child.historySequence &&
                    entry.source.child.commandEpoch == final.child.commandEpoch)
                checkNotNull(entry.replay).additionalDeviceRevocationCensus(value.command.targetId)
            }
            historyShapeV2(record == actual)
        }
        // Every proof above is PRE-effect, including authority if the operator's account
        // also retains target sessions. Preserve each actor's exact whole-account reducer;
        // only then may negative admission projections require no remaining ACTIVE target.
        // Actual native image order remains admission, affected accounts, original audit.
        for (record in value.accounts) {
            if (record.actorId == coordinates.firstActorId) founding.additionalDeviceRevocationAccount(value, record)
            else checkNotNull(active(record.actorId)?.replay).additionalDeviceRevocationAccount(value, record)
        }
        founding.additionalDeviceRevocationAdmission(value)
        for (entry in entries.filter { it.activated })
            checkNotNull(entry.replay).additionalDeviceRevocationAdmission(value)
        chronologicalAdmissions[value.command.targetId] = value.images.first().plaintextDigest
        if (value.authorization.actorId == coordinates.firstActorId) founding.additionalDeviceRevocationAudit(value)
        else checkNotNull(active(value.authorization.actorId)?.replay).additionalDeviceRevocationAudit(value)
        val mutations = value.affectedAccounts.count { it.actorId != coordinates.firstActorId }.toLong() +
            if (value.authorization.actorId == coordinates.firstActorId) 0L else 1L
        deviceRevocationMutations = add(deviceRevocationMutations, mutations)
        // Scanner owns one event; the admission join owns one target UPDATE. Each actor
        // owns only its affected ACCOUNT UPDATE and the original operator's one audit.
        return true
    }

    /** Closed reset coordinator. Both identities already have their OWN native-founded or
     * enrolled prefix at this event cut. The opened AFTER is only a credential candidate,
     * never authority/current state; prove the operator/final first, then reduce the target. */
    fun credentialReset(value: RuntimeCredentialResetEvidenceV1,
        final: NativeHistoryProtectedFinalDecision,
        founding: RuntimeOriginalAuthenticationReplay?,
        actualAfter: ProtectedLocalAccount): RuntimeRetainedSessionRevocationTargetOwner? {
        if (!enabled || !session.hasChronologicalAccountImageTraversal) return null
        value.validate()
        val operatorPermitted = if (value.authorization.actorId == coordinates.firstActorId)
            founding?.credentialResetAuthority(value, final) == true
        else {
            val operator = active(value.authorization.actorId) ?: return null
            historyShapeV2(prepassEnded && operator.eventJoined &&
                operator.source.child.historySequence < final.child.historySequence &&
                operator.source.child.commandEpoch == final.child.commandEpoch)
            checkNotNull(operator.replay).credentialResetAuthority(value, final)
        }
        if (!operatorPermitted) return null
        if (value.command.targetId == coordinates.firstActorId) {
            if (founding?.credentialResetTarget(value, actualAfter) != true) return null
            return RuntimeRetainedSessionRevocationTargetOwner.ORIGINAL
        }
        val target = active(value.command.targetId) ?: return null
        historyShapeV2(prepassEnded && target.eventJoined && target.source.child.historySequence < final.child.historySequence &&
            target.source.child.commandEpoch == final.child.commandEpoch)
        if (!checkNotNull(target.replay).credentialResetTarget(value, actualAfter)) return null
        eventCount = add(eventCount,1L); sessionRevocationMutations = add(sessionRevocationMutations,value.images.size.toLong())
        return RuntimeRetainedSessionRevocationTargetOwner.ENROLLED_EMPLOYEE
    }

    /** Distinct disable coordinator. Original or genuinely native-enrolled operator AND
     * target must have their own complete prefix before this actual native final child.
     * Enrollment prepass/current census alone cannot create either identity or authority. */
    fun accountDisable(value: RuntimeAccountDisableEvidenceV1,
        final: NativeHistoryProtectedFinalDecision,
        founding: RuntimeOriginalAuthenticationReplay?): RuntimeRetainedSessionRevocationTargetOwner? {
        if (!enabled) return null
        value.validate()
        val operatorPermitted = if (value.authorization.actorId == coordinates.firstActorId)
            founding?.accountDisableAuthority(value, final) == true
        else {
            val operator = active(value.authorization.actorId) ?: return null
            historyShapeV2(prepassEnded && operator.eventJoined &&
                operator.source.child.historySequence < final.child.historySequence &&
                operator.source.child.commandEpoch == final.child.commandEpoch)
            checkNotNull(operator.replay).accountDisableAuthority(value, final)
        }
        if (!operatorPermitted) return null
        if (value.command.targetId == coordinates.firstActorId) {
            if (founding?.accountDisableTarget(value) != true) return null
            return RuntimeRetainedSessionRevocationTargetOwner.ORIGINAL
        }
        val target = active(value.command.targetId) ?: return null
        historyShapeV2(prepassEnded && target.eventJoined && target.source.child.historySequence < final.child.historySequence &&
            target.source.child.commandEpoch == final.child.commandEpoch)
        if (!checkNotNull(target.replay).accountDisableTarget(value)) return null
        eventCount = add(eventCount,1L); sessionRevocationMutations = add(sessionRevocationMutations,value.images.size.toLong())
        return RuntimeRetainedSessionRevocationTargetOwner.ENROLLED_EMPLOYEE
    }
    /** Select only an employee whose actual native enrollment has already been joined in
     * ordinary EVENTS. The source prepass alone cannot authorize an earlier command. */
    fun command(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeCommandHistoryAuthorization? {
        historyShapeV2(nativeOriginalPrefixCutoff == null)
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).command(value, header, eventDigest)
    }

    /** Authenticate a strictly prior ordinary command in this separate replay scope.
     * The target and all state-changing or nonordinary events use their own actual paths. */
    fun observeEarlierOrdinaryCommand(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2): Boolean {
        val target = earlierReplayTarget ?: malformed()
        target.requireSameSession(session)
        val entry = active(value.authorization.actorId) ?: return false
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).observeEarlierOrdinaryCommand(value, header)
    }

    /** Authenticate only a strictly earlier direct ordinary command for one exact native
     * target cut. Its genuinely joined enrollment and historical interactive facts supply
     * no command proof, business/source join, mutation accounting or full replay closure. */
    fun observeEarlierNativeOriginalPrefixCommand(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2, cutoffHeader: RuntimeHistoryEventHeaderV2,
        cutoffDigest: PayloadDigest, cutoffEvidence: RuntimeNativeOriginalImportEvidenceV1): Boolean {
        val cutoff = requireNativeOriginalPrefixCutoff(cutoffHeader, cutoffDigest, cutoffEvidence)
        cutoff.requirePrior(header)
        val entry = active(value.authorization.actorId) ?: return false
        historyShapeV2(entry.eventJoined && entry.source.child.historySequence < header.sequence &&
            entry.source.child.commandSequence < header.commandSequence && entry.source.child.commandEpoch == header.commandEpoch &&
            entry.source.child.installationGenesisDigest == header.installationGenesisDigest)
        return checkNotNull(entry.replay).observeEarlierNativeOriginalPrefixCommand(value, header, session, cutoff.header)
    }

    /** The exact founding target uses the genuine C1-seeded original reducer, never an
     * employee proof. Its one attempt is consumed even when original authority fails. */
    fun commandEarlierOrdinaryFoundingTarget(founding: RuntimeOriginalAuthenticationReplay,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1): Boolean {
        val target = earlierReplayTarget ?: malformed()
        historyShapeV2(enabled && prepassEnded && seed == null)
        target.requireSameSession(session)
        target.requireFoundingTarget(header, eventDigest, evidence)
        if (founding.deviceAdmissionAccountDigest(session) == null || !founding.command(evidence)) return false
        earlierFoundingTargetAuthorized = true
        return true
    }

    /** Ordinary retirement belongs to the actor's same native-seeded owner and actual
     * read session. Capture alone is insufficient; every proof consumer must have joined. */
    fun retireJoinedCommand(proof: RuntimeEmployeeCommandHistoryAuthorization,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1) {
        val entry = active(evidence.authorization.actorId) ?: malformed()
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        checkNotNull(entry.replay).retireJoinedCommand(proof, session, header, eventDigest, evidence)
    }

    /** Same-read retirement for the separately registered no-effect payment refusal. */
    fun retireJoinedFoodPaymentRejection(proof: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1) {
        val entry = active(evidence.authorization.actorId) ?: malformed()
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        checkNotNull(entry.replay).retireJoinedFoodPaymentRejection(proof, session, header, eventDigest, evidence)
    }

    /** The enrolled target has genuinely joined and retired, or the founding target has
     * actual original authority without any employee proof. This supplies no EOF/admission. */
    fun requireEarlierOrdinaryCommandSettled() {
        val target = earlierReplayTarget ?: malformed()
        target.requireSameSession(session)
        historyShapeV2(enabled && prepassEnded && seed == null)
        if (target.isFoundingTarget) {
            historyShapeV2(earlierFoundingTargetAuthorized && commandCount == 0L && joinedCommandCount == 0L && retainedCommandCount == 0)
        } else {
            val entry = active(target.actorId) ?: malformed()
            historyShapeV2(entry.eventJoined && target.owns(entry.source) &&
                commandCount == 1L && joinedCommandCount == 1L && retainedCommandCount == 0)
        }
        for (retained in entries) {
            if (retained.source.child.historySequence < target.historySequence) historyShapeV2(retained.activated && retained.eventJoined)
            else historyShapeV2(!retained.activated && !retained.eventJoined)
            checkNotNull(retained.replay).requireEarlierOrdinaryCommandSettled()
        }
    }

    /** Cutoff-only settlement after the native target and every genuine earlier Start loan
     * have closed. Their original/certificate/source joins remain separate; this supplies
     * no direct target authorization, full EOF/current closure or native admission. */
    fun requireEarlierNativeOriginalPrefixSettled(header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeNativeOriginalImportEvidenceV1) {
        val cutoff = requireNativeOriginalPrefixCutoff(header, eventDigest, evidence)
        for (retained in entries) {
            val source = retained.source
            if (source.child.historySequence < header.sequence) historyShapeV2(retained.activated && retained.eventJoined &&
                source.child.commandSequence < header.commandSequence && source.child.commandEpoch == header.commandEpoch &&
                source.child.installationGenesisDigest == header.installationGenesisDigest)
            else historyShapeV2(!retained.activated && !retained.eventJoined)
            checkNotNull(retained.replay).requireEarlierNativeOriginalPrefixSettled(session, header.sequence)
        }
        cutoff.settle()
    }

    /** Bind only the actual original native coordinates and one exact inert cutoff tuple
     * from the closed supported retail/supplier-cash ORIGINAL families. Selecting a DATA profile does
     * not join chronological enrollment, authorize its Start or settle either source. */
    private fun requireNativeOriginalPrefixCutoff(header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeNativeOriginalImportEvidenceV1): NativeOriginalPrefixCutoff {
        historyShapeV2(enabled && earlierReplayTarget == null && !earlierFoundingTargetAuthorized &&
            prepassEnded && seed == null && original === session.original &&
            (session.hasEmployeeSeedTraversal || entries.isEmpty()) && session.hasChronologicalAccountImageTraversal)
        evidence.validate()
        val native = original.record
        val definition = checkNotNull(native.definition)
        val attempt = checkNotNull(native.attempt)
        val firstChild = checkNotNull(native.firstChild)
        val receipt = checkNotNull(native.receipt)
        val cut = checkNotNull(native.parent)
        val writer = evidence.provenance.currentWriter
        val domain = RuntimeBusinessHistoryJoin.supportedColdNativeOriginalDomain(evidence) ?: malformed()
        val binding = original.nativeOriginalBinding.copyWireBytes()
        try {
            historyShapeV2(evidence.scope == scope && evidence.provenance.scope == scope &&
                evidence.producer.command == domain.command && evidence.provenance.nativeOriginal.domainProfile == domain.domainProfile &&
                header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && header.changeCount == evidence.capturedChanges &&
                header.scope == scope.storageKey() && header.storageId == coordinates.storageInstanceId &&
                header.bindingDigest == native.allocation.originalNativeBindingDigest && header.originalAllocationDigest == native.allocation.digest &&
                header.definitionDigest == definition.definitionDigest && header.installationAttemptId == attempt.facts.issuedAttemptId &&
                header.installationAttemptSequence == attempt.facts.attemptSequence && header.assignmentId == coordinates.assignmentId &&
                header.installationGenesisDigest == receipt.genesis && header.installationGenesisDigest == firstChild.installationGenesisDigest &&
                header.commandEpoch == definition.definition.authorityEpoch.value && header.commandEpoch == firstChild.commandEpoch.value &&
                writer.installationId == coordinates.installationId && writer.masterDeviceId == coordinates.deviceId &&
                writer.assignmentId == coordinates.assignmentId && writer.epoch.value == header.commandEpoch &&
                writer.runtimeProfile == SelfOwnedNativeHistoryWireV2.PROFILE && writer.runtimeBindingCommitment == sha256(binding) &&
                header.sequence > 1L && header.sequence <= cut.historySequence &&
                header.commandSequence > 0L && header.commandSequence <= cut.commandSequence &&
                cut.commandEpoch == header.commandEpoch && cut.installationGenesisDigest == header.installationGenesisDigest &&
                header.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag &&
                evidence.provenance.runtimeParent.sequence.value == header.parentSequence &&
                evidence.provenance.runtimeParent.frameHash == header.parentDigest)
        } finally { binding.fill(0) }
        if (header.sequence == cut.historySequence) historyShapeV2(recordedHead(header, eventDigest) == cut)
        nativeOriginalPrefixCutoff?.let { retained ->
            retained.requireSame(header, eventDigest, evidence)
            return retained
        }
        return NativeOriginalPrefixCutoff(header, eventDigest, evidence).also { nativeOriginalPrefixCutoff = it }
    }

    /** Separate exact historical Review path; active selection never borrows current rows. */
    fun supervisorReview(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeSupervisorReviewHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).supervisorReview(value, header, eventDigest)
    }
    /** Original native enrollment and event-order authorization for the closed policy lane. */
    fun purchasingPolicyRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).purchasingPolicyRejection(value, header, eventDigest)
    }

    /** Separate original operator proof for this closed candidate reassignment rejection. */
    fun orderReassignmentRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).orderReassignmentRejection(value, header, eventDigest)
    }

    /** The same original enrollment/event cut, with a distinct opening-obligation proof. */
    fun openingSupplierObligationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).openingSupplierObligationRejection(value, header, eventDigest)
    }

    /** Distinct V2 capture-refusal proof from this actor's genuinely earlier, fully joined
     * native enrollment/event cut. No current or prepass-only grant supplies it. */
    fun invoiceCaptureRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).invoiceCaptureRejection(value, header, eventDigest)
    }

    /** Separate combined match/recognition refusal from this actor's genuine earlier,
     * fully joined native enrollment/event cut; current or prepass-only grants cannot enter. */
    fun capturedInvoiceMatchRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).capturedInvoiceMatchRejection(value, header, eventDigest)
    }

    /** Separate initial-activity proof from this actor's genuinely earlier, fully joined
     * native enrollment/event cut. Prepass-only or current employee facts grant nothing. */
    fun branchActivityActivationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).branchActivityActivationRejection(value, header, eventDigest)
    }

    /** Separate directory refusal proof from this actor's genuinely earlier, fully joined
     * native enrollment/event cut. Current or prepass-only employee facts grant nothing. */
    fun floorConfigurationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).floorConfigurationRejection(value, header, eventDigest)
    }

    /** Separate closed configuration/pricing proof from this actor's genuinely earlier, fully joined
     * native enrollment/event cut. Prepass-only and current employee facts grant nothing. */
    fun configurationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeConfigurationRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).configurationRejection(value, header, eventDigest)
    }

    /** Separate documentary return-rights refusal proof from this actor's genuinely earlier,
     * fully joined native enrollment/event cut. Prepass or current facts grant nothing. */
    fun returnRightsActivationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).returnRightsActivationRejection(value, header, eventDigest)
    }

    /** Separate receipt-refusal proof from this actor's genuinely earlier, fully joined
     * native enrollment/event cut. Neither prepass-only nor current facts grant authority. */
    fun retailCustomerReceiptRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).retailCustomerReceiptRejection(value, header, eventDigest)
    }

    /** Dedicated FOOD refusal proof from this actor's genuinely earlier fully joined
     * native enrollment. A prepass selection or a current employee cannot supply it. */
    fun foodCheckRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeFoodCheckRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).foodCheckRejection(value, header, eventDigest)
    }

    /** Distinct ordinary AlreadyApplied proof from this genuinely earlier joined enrollment. */
    fun foodPaymentRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization? {
        historyShapeV2(nativeOriginalPrefixCutoff == null)
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).foodPaymentRejection(value, header, eventDigest)
    }

    /** A strictly earlier founding refusal uses only its genuine C1-seeded event-cut
     * authority. It remains pending the complete native/source/typed refusal join. */
    fun foodPaymentRejectionEarlierOrdinaryFoundingPrefix(founding: RuntimeOriginalAuthenticationReplay,
        value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2): Boolean {
        val target = earlierReplayTarget ?: malformed()
        historyShapeV2(enabled && prepassEnded && seed == null && nativeOriginalPrefixCutoff == null)
        target.requireSameSession(session)
        value.validate()
        target.requirePriorFoodPaymentRejection(null, header, value)
        return founding.deviceAdmissionAccountDigest(session) != null && founding.command(value)
    }

    /** Native-target prefix qualification is distinct from authentication-only ordinary
     * observations. The exact cutoff and joined enrollment precede this proof's issuance. */
    fun foodPaymentRejectionEarlierNativeOriginalPrefix(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        cutoffHeader: RuntimeHistoryEventHeaderV2, cutoffDigest: PayloadDigest,
        cutoffEvidence: RuntimeNativeOriginalImportEvidenceV1): RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization? {
        val cutoff = requireNativeOriginalPrefixCutoff(cutoffHeader, cutoffDigest, cutoffEvidence)
        cutoff.requirePriorFoodPaymentRejection(header, value)
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(entry.eventJoined && entry.source.child.historySequence < header.sequence &&
            entry.source.child.commandSequence < header.commandSequence && entry.source.child.commandEpoch == header.commandEpoch &&
            entry.source.child.installationGenesisDigest == header.installationGenesisDigest)
        return checkNotNull(entry.replay).foodPaymentRejectionEarlierNativeOriginalPrefix(value, header, eventDigest, session, cutoff.header)
    }

    /** The native cutoff lends no founding authority. Require the actual C1 source and
     * historical interactive reducer before capturing this closed prior refusal. */
    fun foodPaymentRejectionEarlierNativeOriginalFoundingPrefix(founding: RuntimeOriginalAuthenticationReplay,
        value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        cutoffHeader: RuntimeHistoryEventHeaderV2, cutoffDigest: PayloadDigest,
        cutoffEvidence: RuntimeNativeOriginalImportEvidenceV1): Boolean {
        val cutoff = requireNativeOriginalPrefixCutoff(cutoffHeader, cutoffDigest, cutoffEvidence)
        value.validate()
        historyShapeV2(value.authorization.actorId == coordinates.firstActorId)
        cutoff.requirePriorFoodPaymentRejection(header, value)
        return founding.deviceAdmissionAccountDigest(session) != null && founding.command(value)
    }

    /** Separate initial-entitlement revision-guard QUALIFY refusal proof from this actor's genuinely earlier,
     * fully joined native enrollment/event cut. No current or prepass-only grant is used. */
    fun returnCaseQualificationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).returnCaseQualificationRejection(value, header, eventDigest)
    }

    /** Original stale-disposition RETURN refusal proof from this employee's genuinely
     * earlier fully joined enrollment/event cut, never a current or prepass-only grant. */
    fun returnStockDispositionRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization? {
        val entry = active(value.authorization.actorId) ?: return null
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        return checkNotNull(entry.replay).returnStockDispositionRejection(value, header, eventDigest)
    }
    private fun active(actor: DomainId): Entry? = if (!enabled) null else byActor[actor]?.takeIf { it.activated && it.replay != null }

    /** Publish only an enrollment independently re-joined and activated by ordinary EVENTS.
     * The native source prepass cannot make the recipient exist before this actual cut. */
    fun captureRecipientEnrollment(join: RuntimeBusinessHistoryJoin, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest) {
        if (!enabled || !join.enabled) return
        val entry = bySequence[header.sequence] ?: malformed()
        historyShapeV2(prepassEnded && entry.eventJoined && entry.activated && entry.header == header &&
            entry.source.child.historyDigest == eventDigest)
        join.captureEmployeeRecipientOrigin(header, eventDigest, checkNotNull(entry.replay))
    }

    /** Only the changed actor's own already-active native reducer is projected. */
    fun captureRecipientAccountAfter(join: RuntimeBusinessHistoryJoin, header: RuntimeHistoryEventHeaderV2,
        actorId: DomainId) {
        if (!enabled || !join.enabled) return
        val entry = active(actorId) ?: malformed()
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence)
        join.captureRecipientAccountAfter(header, checkNotNull(entry.replay))
    }
    fun captureRecipientPolicyAfter(join: RuntimeBusinessHistoryJoin, header: RuntimeHistoryEventHeaderV2, actorId: DomainId) {
        if (!enabled || !join.enabled) return
        val entry = active(actorId) ?: malformed()
        historyShapeV2(prepassEnded && entry.eventJoined && entry.source.child.historySequence < header.sequence &&
            session.employeePolicyActivations.any { it.child.historySequence == header.sequence && it.requested.request.targetId == actorId })
        join.captureRecipientPolicyAfter(header,checkNotNull(entry.replay))
    }

    /** Native historical identities only; chronological policy/account dependencies require the
     * separate event-order reducer above. These predicates alone grant no authorization. */
    fun matchesThrottle(value: RuntimeThrottleEvidenceV1): Boolean {
        val source = byActor[value.actorId]?.source ?: return false
        return value.scope == scope && value.deviceId == source.originalCohostDeviceId &&
            originDependencies(source,value.dependencies.admission,value.dependencies.configuration)
    }
    fun matchesAuthentication(value: RuntimeAuthenticationEvidenceV1): Boolean {
        val source = byActor[value.actorId]?.source ?: return false
        return value.scope == scope && value.deviceId == source.originalCohostDeviceId &&
            originDependencies(source,value.dependencies.admission,value.dependencies.configuration)
    }

    fun matchesAdditionalDeviceThrottle(value: RuntimeThrottleEvidenceV1, sequence: Long): Boolean =
        value.scope == scope && additionalAuthenticationOrigin(value.actorId, value.deviceId,
            value.dependencies.admission, value.dependencies.configuration, sequence)

    fun matchesAdditionalDeviceAuthentication(value: RuntimeAuthenticationEvidenceV1, sequence: Long): Boolean =
        value.scope == scope && additionalAuthenticationOrigin(value.actorId, value.deviceId,
            value.dependencies.admission, value.dependencies.configuration, sequence)

    /** Immutable source selection for original EVENTS and each later LINEAGE image.
     * Enrollment before admission retains its original census lane. Admission before
     * enrollment instead needs that enrollment's exact retained original and own seed;
     * neither branch depends on final mutable admission/account state after withdrawal. */
    private fun additionalAuthenticationOrigin(actor: DomainId, device: DomainId, admission: PayloadDigest,
        configuration: PayloadDigest?, sequence: Long): Boolean {
        val enrollment = byActor[actor]?.source ?: return false
        val origin = session.deviceAdmissions.singleOrNull { it.requested.request.targetId == device } ?: return false
        if (device == coordinates.deviceId || configuration != enrollment.evidence.images[2].plaintextDigest) return false
        if (enrollment.child.historySequence < origin.child.historySequence && origin.child.historySequence < sequence &&
            origin.originalCohostDeviceId == coordinates.deviceId && origin.requested.request.scope == scope &&
            origin.child.commandEpoch == enrollment.child.commandEpoch &&
            origin.child.installationGenesisDigest == enrollment.child.installationGenesisDigest &&
            origin.evidence.accountDigests.any { it.first == actor } && origin.evidence.images.first().plaintextDigest == admission) return true
        return enrollment.child.historySequence < sequence &&
            RuntimeEmployeeAuthenticationReplay.matchesEnrollmentDeviceAdmissionOrigin(session, enrollment, origin, admission)
    }
    fun matchesSessionRevocation(value: RuntimeSessionRevocationEvidenceV1): Boolean {
        val facts = value.authorization
        val source = byActor[facts.actorId]?.source ?: return false
        return facts.scope == scope && facts.deviceId == source.originalCohostDeviceId &&
            facts.dependencies.admission == source.evidence.admissionDigests.single { it.first == source.originalCohostDeviceId }.second
    }
    /** Lineage/image ownership follows the target. This identity predicate grants nothing;
     * operator authority and mutable BEFORE/issuance facts were proved in actual event order. */
    fun matchesRetainedSessionRevocationTarget(value: RuntimeRetainedSessionRevocationEvidenceV1): Boolean {
        val source = byActor[value.command.targetId]?.source ?: return false
        return value.authorization.scope == scope && value.target.session.scope == scope &&
            value.target.session.actorId == source.requested.request.targetId &&
            value.target.session.deviceId == source.originalCohostDeviceId
    }
    /** Target lineage ownership only, never an operator grant or a current target seed. */
    fun matchesAccountDisableTarget(value: RuntimeAccountDisableEvidenceV1): Boolean {
        val source = byActor[value.command.targetId]?.source ?: return false
        return value.authorization.scope == scope && value.command.targetId == source.requested.request.targetId &&
            value.authorization.deviceId == source.originalCohostDeviceId
    }
    /** Reset target lineage ownership only. Mutable BEFORE/operator/final authority and
     * genuine chronological AFTER remain separate event-order joins, never this predicate. */
    fun matchesCredentialResetTarget(value: RuntimeCredentialResetEvidenceV1): Boolean {
        val source = byActor[value.command.targetId]?.source ?: return false
        return value.authorization.scope == scope && value.command.targetId == source.requested.request.targetId &&
            value.authorization.deviceId == source.originalCohostDeviceId
    }
    /** Lineage audit ownership is the original OPERATOR, not the inert cancellation target.
     * This predicate is only a counter join; event-cut/operator/native-final proof is separate. */
    fun matchesAdministrationCancellation(value: RuntimeAdministrationCancellationEvidenceV1): Boolean {
        val source = byActor[value.authorization.actorId]?.source ?: return false
        return value.authorization.scope == scope && value.authorization.actorId == source.requested.request.targetId &&
            value.authorization.deviceId == source.originalCohostDeviceId
    }
    /** Lineage image ownership only, never an operator grant/current account seed.
     * Admission belongs to the founding replay; account images to their own actor; audit
     * to the actual original operator. Complete authority/BEFORE/event joins are separate. */
    fun matchesDeviceRevocationImage(value: RuntimeDeviceRevocationEvidenceV1, image: RuntimeDeviceRevocationImageV1): Boolean {
        val actor = when (image.table) {
            RuntimeHistoryTableV2.ACCOUNT -> image.recordId
            RuntimeHistoryTableV2.AUTH_EVENT -> value.authorization.actorId
            else -> return false
        }
        val source = byActor[actor]?.source ?: return false
        return value.authorization.scope == scope && value.command.targetId == source.originalCohostDeviceId &&
            value.authorization.deviceId == source.originalCohostDeviceId
    }
    private fun originDependencies(source: NativeEmployeeEnrollmentHistorySource,
        admission: PayloadDigest, configuration: PayloadDigest?): Boolean = configuration == source.evidence.images[2].plaintextDigest &&
        admission == source.evidence.admissionDigests.single { it.first == source.originalCohostDeviceId }.second

    /** Every original enrollment mutation reappears in the exhaustive per-key LINEAGE.
     * This is not a mini receipt query or a count substituted for a real historical image. */
    fun lineageSourceImage(version: RuntimeHistoryRowVersionRowV2): LocalEmployeeEnrollmentProtectedImage? {
        val entry = bySequence[version.sequence] ?: return null
        val retained = entry.versions.getOrNull(version.ordinal) ?: malformed()
        requireSameVersion(version,retained)
        return entry.source.evidence.images[version.ordinal]
    }
    fun lineageSourceEvent(version: RuntimeHistoryRowVersionRowV2, row: RuntimeHistoryEventRowV2, header: RuntimeHistoryEventHeaderV2) {
        val entry = bySequence[version.sequence] ?: return
        historyShapeV2(entry.eventJoined && row.eventDigest == entry.source.child.historyDigest.value && entry.header == header)
    }
    fun lineageSourcePlaintext(version: RuntimeHistoryRowVersionRowV2, binding: ProtectedLocalBinding, bytes: ByteArray) {
        val expected = lineageSourceImage(version) ?: return
        historyShapeV2(expected.matchesHistoryBinding(binding,scope) && sha256(bytes) == expected.plaintextDigest)
        enrollmentImages = add(enrollmentImages,1L)
    }
    fun finish() {
        historyShapeV2(enabled && earlierReplayTarget == null && nativeOriginalPrefixCutoff == null &&
            prepassEnded && seed == null && entries.all { it.activated && it.eventJoined && it.replay != null } &&
            enrollmentImages == enrollmentMutations && mutationCount == entries.sumOf { checkNotNull(it.replay).mutationCount } &&
            currentAdmissions == chronologicalAdmissions)
        for (entry in entries) {
            checkNotNull(entry.replay).finish(entry.currentAccount,entry.currentPolicy,entry.currentConfiguration,entry.currentThrottles)
        }
    }
    fun abandon() { enabled = false; entries.forEach { it.replay?.clear(); it.replay = null }; joinedAdditionalAdmissions.clear()
        nativeOriginalPrefixCutoff?.clear() }
    fun clear() {
        seed?.clear(); seed = null; entries.forEach { it.clear() }
        currentAdmissions.clear(); chronologicalAdmissions.clear(); joinedAdditionalAdmissions.clear(); activeActors.clear(); activeLogins.clear()
        earlierFoundingTargetAuthorized = false
        nativeOriginalPrefixCutoff?.clear()
        earlierReplayTarget?.clear()
    }

    companion object {
        /** Fresh computation scope on the actual original session/source objects. The
         * caller reruns the real seed prepass, C1 and chronological native transcript;
         * no retained joined marker or current census is an authorization seed. */
        fun forEarlierOrdinaryReplay(session: SelfOwnedNativeHistoryReadSession,
            targetHeader: RuntimeHistoryEventHeaderV2, targetDigest: PayloadDigest,
            targetEvidence: RuntimeCommandAuthorizationEvidenceV1): RuntimeEmployeeEnrollmentHistoryJoin =
            RuntimeEmployeeEnrollmentHistoryJoin(session,
                RuntimeEarlierOrdinaryCommandReplayTarget.create(session, targetHeader, targetDigest, targetEvidence))
    }

    private fun requireEnrollmentHeader(source: NativeEmployeeEnrollmentHistorySource, row: RuntimeHistoryEventRowV2, header: RuntimeHistoryEventHeaderV2) {
        historyShapeV2(row.sequence == source.child.historySequence && row.eventDigest == source.child.historyDigest.value &&
            header.sequence == row.sequence && header.transactionId == source.frameId && header.purpose == RuntimeWritePurposeV2.PROVISIONING &&
            header.changeCount == source.evidence.images.size && header.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag &&
            header.parentSequence == source.preparationParent.historySequence && header.parentDigest == source.preparationParent.historyDigest &&
            recordedHead(header,PayloadDigest(row.eventDigest)) == source.child)
    }
    private fun requireEnrollmentCapsule(source: NativeEmployeeEnrollmentHistorySource, capsule: RuntimeHistoryCapsule, intent: NativeHistoryOriginalIntentV2) {
        val request = source.requested.request
        historyShapeV2(intent.purposeTag == RuntimeWritePurposeV2.PROVISIONING.tag && intent.originalRequestId == request.requestId &&
            intent.originalRequestDigest == request.contentDigest && intent.originalActorId == null && intent.originalOperationId == null &&
            intent.resources.isEmpty() && sha256(capsule.intent) == source.originalIntentDigest)
        val dependencies = localEmployeeEnrollmentDependenciesV1(source.evidence); val facts = source.evidence.copyWireBytes()
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies) && facts.contentEquals(capsule.facts)) }
        finally { dependencies.fill(0); facts.fill(0) }
    }
    private fun requireRetainedAdmissions(source: NativeEmployeeEnrollmentHistorySource) {
        val actual = source.requested.admissions.map { it.deviceId to digest(ProtectedLocalSecurityCodec.encodeAdmission(it)) }
        historyShapeV2(actual == source.evidence.admissionDigests &&
            source.requested.admissions.filter { it.admitted }.map { it.deviceId }.toSet() == source.requested.definition.admittedDeviceIds &&
            source.requested.admissions.single { it.deviceId == source.originalCohostDeviceId }.admitted)
    }
    private fun requireEnrollmentMutation(row: RuntimeHistoryRowVersionRowV2, expected: LocalEmployeeEnrollmentProtectedImage, ordinal: Int) {
        val key = expected.runtimeKey(scope)
        val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == expected.table }
        try { historyShapeV2(row.ordinal == ordinal && row.tableTag == table.tag && row.key.contentEquals(key) && row.version == 1L &&
            row.previousSequence == null && row.previousOrdinal == null && row.previousVersion == null && row.beforeRowId == null && row.beforeDigest == null &&
            row.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && row.afterRowId != null && row.afterDigest != null &&
            row.afterKind == (if (table.immutable) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag else RuntimeHistoryAfterKindV2.IMAGE.tag)) }
        finally { key.fill(0) }
    }
    private fun requireSameVersion(left: RuntimeHistoryRowVersionRowV2, right: RuntimeHistoryRowVersionRowV2) {
        val a = RuntimeHistoryCodecV2.encodeMutation(left); val b = RuntimeHistoryCodecV2.encodeMutation(right)
        try { historyShapeV2(a.contentEquals(b)) } finally { a.fill(0); b.fill(0) }
    }
    private fun recordedHead(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest): NativeHistoryRecordedHeadV2 =
        NativeHistoryRecordedHeadV2(header.sequence,digest,header.installationGenesisDigest,header.commandEpoch,header.commandSequence,
            header.commandDigest,header.roleCommitment,header.recoveryCommitment)
    private fun LocalEmployeeEnrollmentProtectedImage.matchesHistoryBinding(binding: ProtectedLocalBinding, scope: BranchScope): Boolean =
        binding.key.scope == scope && binding.key.table == table && binding.key.recordId == recordId && binding.revision == Revision(0) &&
            ((binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey ?: "") == dimension
    private fun add(left: Long, right: Long): Long = historyAddV2(left,right,RuntimeHistoryProfileV2.MAX_MUTATIONS)
    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}

/** Binds a computation-only prefix to one exact original ordinary target. This object
 * neither stores nor registers an authorization proof and cannot admit a native read. */
internal class RuntimeEarlierOrdinaryCommandReplayTarget private constructor(
    private var session: SelfOwnedNativeHistoryReadSession?,
    private val source: NativeEmployeeEnrollmentHistorySource?,
    private val header: RuntimeHistoryEventHeaderV2,
    private val eventDigest: PayloadDigest,
    private val evidence: RuntimeCommandAuthorizationEvidenceV1,
) {
    val actorId: DomainId get() = evidence.authorization.actorId
    val historySequence: Long get() = header.sequence
    val isFoundingTarget: Boolean get() = source == null
    private var lastOrdinarySequence = 0L
    private var lastOrdinaryCommandSequence = 0L
    private var foundingTargetAttempted = false

    fun requireSameSession(session: SelfOwnedNativeHistoryReadSession) {
        historyShapeV2(this.session === session)
    }

    fun owns(source: NativeEmployeeEnrollmentHistorySource): Boolean {
        historyShapeV2(session != null)
        return this.source === source
    }

    fun requireTarget(source: NativeEmployeeEnrollmentHistorySource, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        historyShapeV2(owns(source) && this.header == header && this.eventDigest == eventDigest && this.evidence === evidence &&
            header.sequence > lastOrdinarySequence && header.commandSequence > lastOrdinaryCommandSequence)
        lastOrdinarySequence = header.sequence
        lastOrdinaryCommandSequence = header.commandSequence
    }

    fun requireFoundingTarget(header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        evidence: RuntimeCommandAuthorizationEvidenceV1) {
        historyShapeV2(session != null && isFoundingTarget && !foundingTargetAttempted)
        foundingTargetAttempted = true
        historyShapeV2(this.header == header && this.eventDigest == eventDigest && this.evidence === evidence &&
            header.sequence > lastOrdinarySequence && header.commandSequence > lastOrdinaryCommandSequence)
        lastOrdinarySequence = header.sequence
        lastOrdinaryCommandSequence = header.commandSequence
    }

    fun requireIssuableTarget(source: NativeEmployeeEnrollmentHistorySource, sequence: Long) {
        historyShapeV2(owns(source) && sequence == header.sequence)
    }

    fun requirePrior(source: NativeEmployeeEnrollmentHistorySource, header: RuntimeHistoryEventHeaderV2,
        evidence: RuntimeCommandAuthorizationEvidenceV1) {
        val session = checkNotNull(session)
        historyShapeV2(session.employeeEnrollments.any { it === source })
        requirePriorCoordinates(header, evidence)
    }

    /** Only ordinary AlreadyApplied refusals may additionally register prefix proofs.
     * Founding provenance uses C1; enrolled provenance must own this actual earlier source. */
    fun requirePriorFoodPaymentRejection(source: NativeEmployeeEnrollmentHistorySource?,
        header: RuntimeHistoryEventHeaderV2, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        val session = checkNotNull(session)
        val coordinates = session.original.record.allocation.coordinates
        historyShapeV2(RuntimeHistoricalFoodPaymentRejectionProfile.fromCommand(CommandKey(evidence.commandName, evidence.commandVersion))
            ?.matches(header, evidence) == true)
        if (source == null) historyShapeV2(evidence.authorization.actorId == coordinates.firstActorId)
        else historyShapeV2(session.employeeEnrollments.any { it === source } &&
            source.requested.request.targetId == evidence.authorization.actorId &&
            source.child.historySequence < header.sequence && source.child.commandSequence < header.commandSequence &&
            source.child.commandEpoch == header.commandEpoch && source.child.installationGenesisDigest == header.installationGenesisDigest)
        requirePriorCoordinates(header, evidence)
    }

    private fun requirePriorCoordinates(header: RuntimeHistoryEventHeaderV2,
        evidence: RuntimeCommandAuthorizationEvidenceV1) {
        historyShapeV2(session != null && this.evidence !== evidence &&
            header.sequence > lastOrdinarySequence && header.sequence < this.header.sequence &&
            header.commandSequence > lastOrdinaryCommandSequence && header.commandSequence < this.header.commandSequence &&
            header.scope == this.header.scope && header.storageId == this.header.storageId && header.bindingDigest == this.header.bindingDigest &&
            header.originalAllocationDigest == this.header.originalAllocationDigest && header.definitionDigest == this.header.definitionDigest &&
            header.installationAttemptId == this.header.installationAttemptId && header.installationAttemptSequence == this.header.installationAttemptSequence &&
            header.assignmentId == this.header.assignmentId && header.installationGenesisDigest == this.header.installationGenesisDigest &&
            header.commandEpoch == this.header.commandEpoch && header.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag)
        lastOrdinarySequence = header.sequence
        lastOrdinaryCommandSequence = header.commandSequence
    }

    fun clear() { session = null }

    companion object {
        fun create(session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
            eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1): RuntimeEarlierOrdinaryCommandReplayTarget {
            evidence.validate()
            val native = session.original.record
            val coordinates = native.allocation.coordinates
            val authorization = evidence.authorization
            val source = if (authorization.actorId == coordinates.firstActorId) null else
                session.employeeEnrollments.singleOrNull { it.requested.request.targetId == authorization.actorId }
                    ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
            historyShapeV2((session.hasEmployeeSeedTraversal || session.employeeEnrollments.isEmpty()) && session.hasChronologicalAccountImageTraversal &&
                header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && evidence.purposeTag == header.purpose.tag &&
                evidence.actionApprovalId == null && evidence.capturedChanges == header.changeCount &&
                header.scope == coordinates.scope.storageKey() && header.assignmentId == coordinates.assignmentId &&
                authorization.scope == coordinates.scope && authorization.deviceId == coordinates.deviceId)
            if (source == null) historyShapeV2(authorization.actorId == coordinates.firstActorId &&
                header.sequence > 1L && header.commandSequence > 0L &&
                header.installationGenesisDigest == native.receipt?.genesis &&
                header.installationGenesisDigest == native.firstChild?.installationGenesisDigest &&
                header.commandEpoch == native.definition?.definition?.authorityEpoch?.value &&
                header.commandEpoch == native.firstChild?.commandEpoch?.value)
            else historyShapeV2(authorization.actorId != coordinates.firstActorId && source.originalCohostDeviceId == coordinates.deviceId &&
                source.requested.request.scope == coordinates.scope && source.child.historySequence < header.sequence &&
                source.child.commandSequence < header.commandSequence && source.child.commandEpoch == header.commandEpoch &&
                source.child.installationGenesisDigest == header.installationGenesisDigest)
            return RuntimeEarlierOrdinaryCommandReplayTarget(session, source, header, eventDigest, evidence)
        }
    }
}
