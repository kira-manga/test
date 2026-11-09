package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Closed native-selected original admission join. Native final DATA is not sufficient:
 * ordinary EVENTS, complete chronological account/admission origins, each actual LINEAGE
 * AFTER and the whole current census must agree before the scanner can remove its hold.
 * This join owns all admission-created rows/counts; existing actor reducers retain their
 * original buckets and receive new device seeds for closure without double-counting them. */
internal class RuntimeDeviceAdmissionHistoryJoin(private val session: SelfOwnedNativeHistoryReadSession) {
    private val coordinates = session.original.record.allocation.coordinates
    private val scope = coordinates.scope
    private class Entry(val source: NativeDeviceAdmissionHistorySource) {
        var header: RuntimeHistoryEventHeaderV2? = null
        val versions = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        var reduced = false
        var tipRevision = Revision(0)
        var tipDigest = source.evidence.images.first().plaintextDigest
        var currentAdmission: Pair<Revision, PayloadDigest>? = null
        var censusAdmission: Pair<Revision, PayloadDigest>? = null
        // Native original INSERT commitments are the only initial bucket tips. Current
        // census/lineage values below are closure targets, never fresh throttle seeds.
        val throttleTips = source.evidence.images.filter { it.table == ProtectedLocalTable.THROTTLE }
            .associate { it.recordId to (Revision(0) to it.plaintextDigest) }.toMutableMap()
        val currentThrottles = linkedMapOf<DomainId, Pair<Revision, PayloadDigest>>()
        val censusThrottles = linkedMapOf<DomainId, Pair<Revision, PayloadDigest>>()
        var withdrawal: Withdrawal? = null
        val lineage = BooleanArray(source.evidence.images.size)
        val plaintext = BooleanArray(source.evidence.images.size)
        val current = BooleanArray(source.evidence.images.size)
        val census = BooleanArray(source.evidence.images.size)
        fun clear() { versions.forEach { it.key.fill(0) }; versions.clear(); header = null
            throttleTips.clear(); currentThrottles.clear(); censusThrottles.clear() }
    }
    private class Withdrawal(val entry: Entry, val header: RuntimeHistoryEventHeaderV2,
        val commitment: PayloadDigest, val evidence: RuntimeDeviceRevocationEvidenceV2,
        mutations: List<RuntimeHistoryRowVersionRowV2>) {
        val versions = mutations.map { it.copy(key = it.key.copyOf()) }
        val lineage = BooleanArray(evidence.images.size)
        val plaintext = BooleanArray(evidence.images.size)
        var reduced = false
        var currentAudit = false
        var censusAudit = false
        fun clear() { versions.forEach { it.key.fill(0) } }
    }
    // CENSUS precedes EVENTS. These bounded audit commitments are closure targets,
    // never evidence of admission origin, current authority or a completed withdrawal.
    private val withdrawalAuditCensus = linkedMapOf<DomainId, Pair<ProtectedLocalBinding, PayloadDigest>>()
    private val withdrawals = linkedMapOf<Long, Withdrawal>()
    private val withdrawalEvents = linkedMapOf<DomainId, Withdrawal>()
    private val entries = session.deviceAdmissions.map(::Entry)
    private val bySequence = entries.associateBy { it.source.child.historySequence }
    private val byDevice = entries.associateBy { it.source.requested.request.targetId }
    private val byEvent = entries.associateBy { it.source.requested.request.requestId }
    private var enabled = true
    var eventCount = 0L
        private set
    var mutationCount = 0L
        private set
    var imageCount = 0L
        private set
    val tipCount: Long get() = entries.filter { it.reduced }.sumOf { it.source.evidence.images.size.toLong() }
    // Withdrawal UPDATEs change an existing admission tip; its audit belongs to the operator replay.
    fun hasSource(sequence: Long): Boolean = sequence in bySequence
    fun count(table: RuntimeHistoryTableV2): Long = when (table) {
        RuntimeHistoryTableV2.ADMISSION, RuntimeHistoryTableV2.AUTH_EVENT -> eventCount
        RuntimeHistoryTableV2.THROTTLE -> entries.filter { it.reduced }.sumOf { it.source.evidence.accountDigests.size.toLong() }
        else -> 0L
    }

    init {
        historyShapeV2(entries.size <= LocalDevicePairingProfileV1.MAXIMUM_ACTIONS &&
            bySequence.size == entries.size && byDevice.size == entries.size && byEvent.size == entries.size &&
            (session.hasDeviceAdmissionHistoryTraversal || entries.isEmpty()))
        for (entry in entries) historyShapeV2(entry.source.requested.request.scope == scope &&
            entry.source.originalCohostDeviceId == coordinates.deviceId && entry.source.child.historySequence > 1L &&
            entry.source.child.commandEpoch == checkNotNull(session.original.record.definition).definition.authorityEpoch.value)
    }

    fun census(binding: ProtectedLocalBinding, bytes: ByteArray, value: Any) = observeCurrent(binding, bytes, value, census = true)
    fun current(binding: ProtectedLocalBinding, bytes: ByteArray, value: Any) = observeCurrent(binding, bytes, value, census = false)

    private fun observeCurrent(binding: ProtectedLocalBinding, bytes: ByteArray, value: Any, census: Boolean) {
        if (census && binding.key is ProtectedLocalRecordKey.AuthEvent && value is LocalAuthEvent &&
            value.kind == LocalAuthEventKind.DEVICE_REVOKED && value.targetId in byDevice) {
            historyShapeV2(withdrawalAuditCensus.size < entries.size && value.eventId !in withdrawalAuditCensus)
            withdrawalAuditCensus[value.eventId] = binding to sha256(bytes)
        }
        (binding.key as? ProtectedLocalRecordKey.AuthEvent)?.let { key ->
            withdrawalEvents[key.recordId]?.let { withdrawal ->
                val image = withdrawal.evidence.images.last()
                historyShapeV2(image.matches(binding) && sha256(bytes) == image.plaintextDigest)
                if (census) { historyShapeV2(!withdrawal.censusAudit); withdrawal.censusAudit = true }
                else { historyShapeV2(!withdrawal.currentAudit); withdrawal.currentAudit = true }
                return
            }
        }
        val entry = when (val key = binding.key) {
            is ProtectedLocalRecordKey.Admission -> byDevice[key.recordId]
            is ProtectedLocalRecordKey.Throttle -> (key.key.dimension as? ThrottleDimension.Device)?.let { byDevice[it.deviceId] }
            is ProtectedLocalRecordKey.AuthEvent -> byEvent[key.recordId]
            else -> null
        } ?: return
        val index = entry.source.evidence.images.indexOfFirst { it.table == binding.key.table && it.recordId == binding.key.recordId &&
            it.dimension == ((binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey ?: "") }
        // A later genuine employee enrollment creates its own bucket for this device.
        // Its source/replay, not this earlier admission, owns that separate key.
        if (index < 0) return
        if (binding.key is ProtectedLocalRecordKey.Admission) {
            // Census runs before EVENTS. Hold only a closure target here, never an origin;
            // compare against the fully reduced chronological tip after the complete scan.
            historyShapeV2(binding.key == ProtectedLocalRecordKey.Admission(scope, entry.source.requested.request.targetId))
            val target = binding.revision to sha256(bytes)
            if (census) { historyShapeV2(entry.censusAdmission == null); entry.censusAdmission = target }
            else { historyShapeV2(entry.currentAdmission == null); entry.currentAdmission = target }
        } else if (binding.key is ProtectedLocalRecordKey.Throttle) {
            val key = binding.key as ProtectedLocalRecordKey.Throttle
            val state = value as? PersistentThrottleState ?: malformed()
            historyShapeV2(state.key == key.key && state.key.scope == scope &&
                state.key.actorId == entry.source.evidence.images[index].recordId &&
                state.key.dimension == ThrottleDimension.Device(entry.source.requested.request.targetId))
            val target = binding.revision to sha256(bytes)
            val throttles = if (census) entry.censusThrottles else entry.currentThrottles
            historyShapeV2(throttles.put(state.key.actorId, target) == null)
        } else historyShapeV2(entry.source.evidence.images[index].matches(binding) &&
            sha256(bytes) == entry.source.evidence.images[index].plaintextDigest)
        val observed = if (census) entry.census else entry.current
        historyShapeV2(!observed[index]); observed[index] = true
    }

    fun event(header: RuntimeHistoryEventHeaderV2, commitment: PayloadDigest, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2, mutations: List<RuntimeHistoryRowVersionRowV2>,
        previous: RuntimeHistoryEventHeaderV2, previousDigest: PayloadDigest): NativeDeviceAdmissionHistorySource? {
        val entry = bySequence[header.sequence] ?: return null
        val source = entry.source
        historyShapeV2(entry.header == null && entry.versions.isEmpty() &&
            header.purpose == RuntimeWritePurposeV2.PROVISIONING && header.transactionId == source.frameId &&
            recordedHead(header, commitment) == source.child && recordedHead(previous, previousDigest) == source.preparationParent &&
            header.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag &&
            header.parentSequence == source.preparationParent.historySequence && header.parentDigest == source.preparationParent.historyDigest &&
            header.changeCount == source.evidence.images.size && mutations.size == header.changeCount)
        requireCapsule(source, capsule, intent)
        requireLiteralEffects(source)
        for ((index, version) in mutations.withIndex()) {
            requireMutation(version, source.evidence.images[index], index)
            entry.versions += version.copy(key = version.key.copyOf())
        }
        entry.header = header
        return source
    }

    fun reduced(source: NativeDeviceAdmissionHistorySource) {
        val entry = bySequence[source.child.historySequence] ?: malformed()
        historyShapeV2(enabled && entry.source === source && entry.header != null && !entry.reduced)
        entry.reduced = true
        eventCount = add(eventCount, 1L); mutationCount = add(mutationCount, source.evidence.images.size.toLong())
    }

    /** Immutable native origin predicate for EVENTS and later LINEAGE selection. It is
     * not current authority: the actor reducer must already have joined this admission
     * in chronological order and the complete scan must still close every original image. */
    fun matchesAuthenticationOrigin(actorId: DomainId, deviceId: DomainId, admissionDigest: PayloadDigest,
        sequence: Long): Boolean {
        val entry = byDevice[deviceId] ?: return false
        return deviceId != coordinates.deviceId && entry.source.child.historySequence < sequence &&
            entry.source.evidence.accountDigests.any { it.first == actorId } &&
            admissionDigest == entry.source.evidence.images.first().plaintextDigest
    }

    /** The account replay has already proved the complete paired transition. Advance only
     * this admission-owned device bucket's closure tip, never an account penalty or count. */
    fun authenticationThrottle(value: RuntimeThrottleEvidenceV1, sequence: Long) {
        value.validate()
        val entry = byDevice[value.deviceId] ?: malformed()
        historyShapeV2(enabled && entry.reduced && entry.header != null && entry.withdrawal == null &&
            value.scope == scope && matchesAuthenticationOrigin(value.actorId, value.deviceId,
                value.dependencies.admission, sequence))
        val before = value.before(value.deviceKey)
        historyShapeV2(entry.throttleTips[value.actorId] ==
            (before.revision to digest(ProtectedLocalSecurityCodec.encodeThrottle(before))))
        val after = value.after(value.deviceKey)
        entry.throttleTips[value.actorId] = after.revision to digest(ProtectedLocalSecurityCodec.encodeThrottle(after))
    }

    /** Select only an earlier, already reduced genuine CONSUMED origin. A live revoked
     * row, a capsule declaration or a native certificate alone can never supply it. */
    fun withdrawalEvent(header: RuntimeHistoryEventHeaderV2, commitment: PayloadDigest,
        capsule: RuntimeHistoryCapsule, intent: NativeHistoryOriginalIntentV2,
        value: RuntimeDeviceRevocationEvidenceV2, mutations: List<RuntimeHistoryRowVersionRowV2>,
        final: NativeHistoryProtectedFinalDecision?, employees: RuntimeEmployeeEnrollmentHistoryJoin,
        founding: RuntimeOriginalAuthenticationReplay?): Boolean {
        value.validate()
        val entry = byDevice[value.command.targetId] ?: return false
        if (!enabled || !entry.reduced || entry.header == null) return false
        historyShapeV2(entry.withdrawal == null && header.sequence !in withdrawals &&
            value.command.requestId !in byEvent && value.command.requestId !in withdrawalEvents &&
            header.sequence > entry.source.child.historySequence && header.purpose == value.purpose &&
            header.commandEpoch == entry.source.child.commandEpoch && header.changeCount == value.images.size &&
            value.authorization.scope == scope && value.authorization.deviceId == coordinates.deviceId &&
            entry.tipRevision == Revision(0) && value.admissionBeforeDigest == entry.tipDigest)
        requireWithdrawalCapsule(value, capsule, intent)
        val original = entry.source.requested.copyCanonicalServiceAdmission()
        val after = try {
            val admission = ProtectedLocalSecurityCodec.decodeAdmission(ProtectedLocalBinding(
                ProtectedLocalRecordKey.Admission(scope, value.command.targetId), Revision(0)), original)
            ProtectedLocalSecurityCodec.encodeAdmission(deviceRevocationAdmissionAfterV1(admission))
        } finally { original.fill(0) }
        try { historyShapeV2(value.images.first() == RuntimeDeviceRevocationImageV1.capture(
            ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(scope, value.command.targetId), Revision(1)), sha256(after))) }
        finally { after.fill(0) }
        historyShapeV2(value.images.size == value.affectedAccounts.size + 2 &&
            value.images.last() == deviceRevocationAuditImageV2(value) &&
            mutations.size == value.images.size)
        val withdrawal = Withdrawal(entry, header, commitment, value, mutations)
        withdrawalAuditCensus[value.command.requestId]?.let { (binding, digest) ->
            historyShapeV2(value.images.last().matches(binding) && value.images.last().plaintextDigest == digest)
            withdrawal.censusAudit = true
        }
        for (index in mutations.indices) requireWithdrawalMutation(withdrawal, mutations[index], index)
        entry.withdrawal = withdrawal
        withdrawals[header.sequence] = withdrawal; withdrawalEvents[value.command.requestId] = withdrawal
        if (final == null) return false
        historyShapeV2(final.child == recordedHead(header, commitment) && final.declaration == value.protectedFinalDeclaration())
        if (!employees.additionalDeviceRevocation(value, final, entry.source, founding)) return false
        // All current authority, whole-census and literal-effect proofs precede this tip.
        entry.tipRevision = value.images.first().revision; entry.tipDigest = value.images.first().plaintextDigest
        withdrawal.reduced = true
        mutationCount = add(mutationCount, 1L) // The single target UPDATE; no new tip.
        return true
    }

    fun withdrawalImage(version: RuntimeHistoryRowVersionRowV2): RuntimeDeviceRevocationImageV1? =
        withdrawals[version.sequence]?.let { withdrawal ->
            val expected = withdrawal.versions.getOrNull(version.ordinal) ?: malformed()
            requireSameVersion(version, expected)
            withdrawal.evidence.images[version.ordinal]
        }
    fun employeeWithdrawalAudit(version: RuntimeHistoryRowVersionRowV2): Boolean =
        withdrawals[version.sequence]?.evidence?.authorization?.actorId?.let { it != coordinates.firstActorId } == true

    fun lineageSourceEvent(version: RuntimeHistoryRowVersionRowV2, row: RuntimeHistoryEventRowV2,
        header: RuntimeHistoryEventHeaderV2, previousAdmissionDigest: PayloadDigest? = null,
        previousAccountDigest: PayloadDigest? = null) {
        withdrawals[version.sequence]?.let { withdrawal ->
            requireSameVersion(version, withdrawal.versions.getOrNull(version.ordinal) ?: malformed())
            historyShapeV2(withdrawal.header == header && withdrawal.commitment.value == row.eventDigest &&
                !withdrawal.lineage[version.ordinal] && !withdrawal.plaintext[version.ordinal])
            if (version.tableTag == RuntimeHistoryTableV2.ADMISSION.tag)
                historyShapeV2(previousAdmissionDigest == withdrawal.evidence.admissionBeforeDigest)
            else if (version.tableTag == RuntimeHistoryTableV2.ACCOUNT.tag)
                historyShapeV2(previousAccountDigest == withdrawal.evidence.accounts.single {
                    it.actorId == withdrawal.evidence.images[version.ordinal].recordId
                }.accountBeforeDigest)
            withdrawal.lineage[version.ordinal] = true
            return
        }
        val entry = bySequence[version.sequence] ?: return
        val expected = entry.versions.getOrNull(version.ordinal) ?: malformed()
        requireSameVersion(version, expected)
        historyShapeV2(entry.header == header && row.eventDigest == entry.source.child.historyDigest.value &&
            !entry.lineage[version.ordinal] && !entry.plaintext[version.ordinal])
        entry.lineage[version.ordinal] = true
    }

    fun lineageSourceCapsule(version: RuntimeHistoryRowVersionRowV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2) {
        withdrawals[version.sequence]?.let { withdrawal ->
            historyShapeV2(withdrawal.lineage[version.ordinal])
            requireWithdrawalCapsule(withdrawal.evidence, capsule, intent)
            return
        }
        val entry = bySequence[version.sequence] ?: return
        historyShapeV2(entry.lineage[version.ordinal])
        requireCapsule(entry.source, capsule, intent)
    }

    fun lineageSourcePlaintext(version: RuntimeHistoryRowVersionRowV2, binding: ProtectedLocalBinding,
        bytes: ByteArray, value: Any) {
        withdrawals[version.sequence]?.let { withdrawal ->
            val expected = withdrawal.evidence.images.getOrNull(version.ordinal) ?: malformed()
            historyShapeV2(withdrawal.lineage[version.ordinal] && !withdrawal.plaintext[version.ordinal] &&
                expected.matches(binding) && sha256(bytes) == expected.plaintextDigest)
            val semantic = when (expected.table) {
                RuntimeHistoryTableV2.ADMISSION -> {
                    historyShapeV2(value is LocalDeviceAdmission)
                    val original = withdrawal.entry.source.requested.copyCanonicalServiceAdmission()
                    try {
                        val admission = ProtectedLocalSecurityCodec.decodeAdmission(ProtectedLocalBinding(
                            ProtectedLocalRecordKey.Admission(scope, withdrawal.evidence.command.targetId), Revision(0)), original)
                        ProtectedLocalSecurityCodec.encodeAdmission(deviceRevocationAdmissionAfterV1(admission))
                    } finally { original.fill(0) }
                }
                RuntimeHistoryTableV2.ACCOUNT -> {
                    val account = value as? ProtectedLocalAccount ?: malformed()
                    historyShapeV2(account.state.scope == scope && account.state.actorId == expected.recordId &&
                        account.state.revision == expected.revision)
                    // EVENTS already compared this image to the actor's genuine BEFORE
                    // reduction. This opened native AFTER closes it; it never seeds replay.
                    ProtectedLocalSecurityCodec.encodeAccount(account)
                }
                RuntimeHistoryTableV2.AUTH_EVENT -> {
                    historyShapeV2(value is LocalAuthEvent)
                    val evidence = withdrawal.evidence
                    LocalAuthenticationCodec.encodeEvent(LocalAuthEvent(scope, evidence.command.requestId, LocalAuthEventKind.DEVICE_REVOKED,
                        evidence.command.targetId, evidence.authorization.actorId, evidence.authorization.sessionId,
                        null, evidence.at, evidence.semanticDigest))
                }
                else -> malformed()
            }
            try { historyShapeV2(bytes.contentEquals(semantic)) } finally { semantic.fill(0) }
            if (expected.table == RuntimeHistoryTableV2.ADMISSION) imageCount = add(imageCount, 1L)
            withdrawal.plaintext[version.ordinal] = true
            return
        }
        val entry = bySequence[version.sequence] ?: return
        val expected = entry.source.evidence.images.getOrNull(version.ordinal) ?: malformed()
        historyShapeV2(entry.lineage[version.ordinal] && !entry.plaintext[version.ordinal] &&
            expected.matches(binding) && sha256(bytes) == expected.plaintextDigest)
        val source = entry.source
        val semantic = when (expected.table) {
            ProtectedLocalTable.ADMISSION -> {
                historyShapeV2(value is LocalDeviceAdmission)
                source.requested.copyCanonicalServiceAdmission()
            }
            ProtectedLocalTable.THROTTLE -> {
                val state = freshThrottle(source, expected.recordId)
                historyShapeV2(value == state); ProtectedLocalSecurityCodec.encodeThrottle(state)
            }
            ProtectedLocalTable.AUTH_EVENT -> {
                val event = admissionEvent(source)
                historyShapeV2(value is LocalAuthEvent); LocalAuthenticationCodec.encodeEvent(event)
            }
            else -> malformed()
        }
        try { historyShapeV2(bytes.contentEquals(semantic)) } finally { semantic.fill(0) }
        entry.plaintext[version.ordinal] = true; imageCount = add(imageCount, 1L)
    }

    fun finish() {
        historyShapeV2(enabled && entries.all { it.header != null && it.reduced &&
            it.versions.size == it.source.evidence.images.size && it.lineage.all { it } && it.plaintext.all { it } &&
            it.census.all { it } && it.current.all { it } &&
            it.censusThrottles == it.throttleTips && it.currentThrottles == it.throttleTips &&
            it.censusAdmission == (it.tipRevision to it.tipDigest) && it.currentAdmission == (it.tipRevision to it.tipDigest) } &&
            withdrawals.values.all { it.reduced && it.lineage.all { it } && it.plaintext.all { it } &&
                it.censusAudit && it.currentAudit } && eventCount == entries.size.toLong() && mutationCount == imageCount)
    }
    fun abandon() { enabled = false }
    fun clear() { entries.forEach { it.clear() }; withdrawals.values.forEach { it.clear() };
        withdrawals.clear(); withdrawalEvents.clear(); withdrawalAuditCensus.clear(); enabled = false }

    private fun requireWithdrawalCapsule(value: RuntimeDeviceRevocationEvidenceV2,
        capsule: RuntimeHistoryCapsule, intent: NativeHistoryOriginalIntentV2) {
        historyShapeV2(intent.purposeTag == value.purpose.tag && intent.originalRequestId == value.command.requestId &&
            intent.originalRequestDigest == value.semanticDigest && intent.originalActorId == value.authorization.actorId &&
            intent.originalOperationId == null && intent.resources.isEmpty())
        val facts = RuntimeDeviceRevocationEvidenceCodecV2.encode(value)
        val dependencies = RuntimeDeviceRevocationEvidenceCodecV2.dependencies(value)
        try { historyShapeV2(facts.contentEquals(capsule.facts) && dependencies.contentEquals(capsule.dependencies)) }
        finally { facts.fill(0); dependencies.fill(0) }
    }
    private fun requireWithdrawalMutation(withdrawal: Withdrawal, row: RuntimeHistoryRowVersionRowV2, ordinal: Int) {
        val image = withdrawal.evidence.images[ordinal]; val key = image.key(scope)
        try {
            historyShapeV2(row.ordinal == ordinal && row.sequence == withdrawal.header.sequence && row.tableTag == image.tableTag &&
                row.key.contentEquals(key) && row.mutationKind == image.mutation.tag && row.afterRowId != null && row.afterDigest != null)
            if (image.table == RuntimeHistoryTableV2.ADMISSION) {
                val origin = withdrawal.entry.versions.first()
                historyShapeV2(ordinal == 0 && row.version == 2L && row.previousVersion == 1L &&
                    row.previousSequence == withdrawal.entry.source.child.historySequence && row.previousOrdinal == 0 &&
                    row.beforeRowId == origin.afterRowId && row.beforeDigest == origin.afterDigest && row.afterRowId == row.beforeRowId &&
                    row.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag && row.imageOffset != null && row.imageBytes != null)
            } else if (image.table == RuntimeHistoryTableV2.ACCOUNT) {
                val account = withdrawal.evidence.affectedAccounts.getOrNull(ordinal - 1) ?: malformed()
                val previousSequence = row.previousSequence
                val previousVersion = row.previousVersion
                historyShapeV2(ordinal in 1 until withdrawal.evidence.images.lastIndex &&
                    account.actorId == image.recordId && account.afterRevision == image.revision &&
                    previousSequence != null && previousSequence < row.sequence && row.previousOrdinal != null &&
                    previousVersion != null && previousVersion >= 1L && previousVersion < Long.MAX_VALUE &&
                    row.version == previousVersion + 1L && row.beforeRowId != null && row.beforeDigest != null &&
                    row.afterRowId == row.beforeRowId && row.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag &&
                    row.imageOffset != null && row.imageBytes != null)
                // Logical lineage advances by one actual write even when the whole-account
                // reducer jumps revision/generation to permanent MAX_VALUE lockdown.
            } else historyShapeV2(ordinal == withdrawal.evidence.images.lastIndex &&
                image.table == RuntimeHistoryTableV2.AUTH_EVENT && row.version == 1L &&
                row.previousSequence == null && row.previousOrdinal == null && row.previousVersion == null &&
                row.beforeRowId == null && row.beforeDigest == null && row.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag &&
                row.imageOffset == null && row.imageBytes == null)
        } finally { key.fill(0) }
    }

    private fun requireLiteralEffects(source: NativeDeviceAdmissionHistorySource) {
        val requested = source.requested
        val admissions = requested.retainedAdmissions.map { it.deviceId to digest(ProtectedLocalSecurityCodec.encodeAdmission(it)) }
        historyShapeV2(admissions == source.evidence.admissionDigests &&
            requested.retainedAdmissions.single { it.deviceId == coordinates.deviceId }.admitted)
        for (image in source.evidence.images) {
            val bytes = when (image.table) {
                ProtectedLocalTable.ADMISSION -> requested.copyCanonicalServiceAdmission()
                ProtectedLocalTable.THROTTLE -> ProtectedLocalSecurityCodec.encodeThrottle(freshThrottle(source, image.recordId))
                ProtectedLocalTable.AUTH_EVENT -> LocalAuthenticationCodec.encodeEvent(admissionEvent(source))
                else -> malformed()
            }
            historyShapeV2(digest(bytes) == image.plaintextDigest)
        }
    }
    private fun requireCapsule(source: NativeDeviceAdmissionHistorySource, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2) {
        val request = source.requested.request
        historyShapeV2(intent.purposeTag == RuntimeWritePurposeV2.PROVISIONING.tag && intent.originalRequestId == request.requestId &&
            intent.originalRequestDigest == request.contentDigest && intent.originalOperationId == null && intent.originalActorId == null &&
            intent.resources.isEmpty() && sha256(capsule.intent) == source.originalIntentDigest)
        val dependencies = localDeviceAdmissionDependenciesV1(source.evidence); val facts = source.evidence.copyWireBytes()
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies) && facts.contentEquals(capsule.facts)) }
        finally { dependencies.fill(0); facts.fill(0) }
    }
    private fun requireMutation(row: RuntimeHistoryRowVersionRowV2, image: LocalDeviceAdmissionProtectedImage, ordinal: Int) {
        val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == image.table }
        val key = image.runtimeDeviceAdmissionKey(scope)
        try { historyShapeV2(row.ordinal == ordinal && row.tableTag == table.tag && row.key.contentEquals(key) && row.version == 1L &&
            row.previousSequence == null && row.previousOrdinal == null && row.previousVersion == null &&
            row.beforeRowId == null && row.beforeDigest == null && row.afterRowId != null && row.afterDigest != null &&
            row.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
            row.afterKind == (if (table.immutable) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag else RuntimeHistoryAfterKindV2.IMAGE.tag) &&
            (if (table.immutable) row.imageOffset == null && row.imageBytes == null else row.imageOffset != null && row.imageBytes != null)) }
        finally { key.fill(0) }
    }
    private fun LocalDeviceAdmissionProtectedImage.matches(binding: ProtectedLocalBinding): Boolean =
        binding.key.scope == scope && binding.key.table == table && binding.key.recordId == recordId && binding.revision == Revision(0) &&
            ((binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey ?: "") == dimension
    private fun requireSameVersion(left: RuntimeHistoryRowVersionRowV2, right: RuntimeHistoryRowVersionRowV2) {
        val a = RuntimeHistoryCodecV2.encodeMutation(left); val b = RuntimeHistoryCodecV2.encodeMutation(right)
        try { historyShapeV2(a.contentEquals(b)) } finally { a.fill(0); b.fill(0) }
    }
    private fun recordedHead(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest): NativeHistoryRecordedHeadV2 =
        NativeHistoryRecordedHeadV2(header.sequence, digest, header.installationGenesisDigest, header.commandEpoch, header.commandSequence,
            header.commandDigest, header.roleCommitment, header.recoveryCommitment)
    private fun freshThrottle(source: NativeDeviceAdmissionHistorySource, actor: DomainId): PersistentThrottleState =
        PersistentThrottleState(ThrottleKey(scope, actor, ThrottleDimension.Device(source.requested.request.targetId)),
            Revision(0), 0, source.evidence.eventAt, 0, null)
    private fun admissionEvent(source: NativeDeviceAdmissionHistorySource): LocalAuthEvent = LocalAuthEvent(scope,
        source.requested.request.requestId, LocalAuthEventKind.DEVICE_ADMITTED, source.requested.request.targetId,
        null, null, null, source.evidence.eventAt, source.requested.request.contentDigest)
    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    private fun add(left: Long, right: Long): Long = historyAddV2(left, right, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}
