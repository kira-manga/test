package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RoomFoodCheckNativeCensus(val heads: Long, val events: Long, val versions: Long,
    val chunks: Long, val tips: Long, val applicationRows: Map<RuntimeHistoryTableV2, Long>)

/** One bounded, source-only traversal on the actual unmutated writer. Existing native
 * event/capsule/physical codecs authenticate the ENTIRE immutable tail to its real parent.
 * No native read session, security replay, historical view or business qualification is
 * constructed. Literal sources remain explicitly unqualified until the installed producer
 * independently verifies their whole originals on this same loan. */
internal class RoomClosedRejectedOriginalSources(
    private val frame: RoomRuntimeHistoryFrame,
    private val caller: TransactionView,
    private val operationId: OperationId,
) {
    private val native = frame.native.original.record
    private val coordinates = native.allocation.coordinates
    private val scope = coordinates.scope
    private val scopeKey = scope.storageKey()
    private val parent = frame.originalParent
    private val events = linkedMapOf<Long, Event>()
    private val byOperation = linkedMapOf<OperationId, Event>()
    private val histories = linkedMapOf<String, MutableList<RuntimeHistoryRowVersionRowV2>>()
    private val originals = linkedMapOf<OperationId, RetainedOriginalV2>()
    private var retainedBytes = 0L
    private var traversedBytes = 0L
    private var versions = 0L
    private var chunks = 0L
    private var branch: BranchRow? = null

    private class Event(val header: RuntimeHistoryEventHeaderV2, val digest: PayloadDigest,
        val intent: NativeHistoryOriginalIntentV2, val command: RuntimeCommandAuthorizationEvidenceV1?,
        val approved: Boolean, val imported: RuntimeNativeOriginalImportEvidenceV1?,
        val mutations: List<RuntimeHistoryRowVersionRowV2>, val dependencies: List<List<String>>?) {
        val operationId: OperationId? = intent.originalOperationId?.let { OperationId(it.value) }
    }

    /** Internal fixed selection only; neither requests nor captured DATA choose a profile. */
    private enum class Profile {
        FOOD_CHECK, FOOD_TABLE_ASSIGNMENT, FOOD_PAYMENT_ALREADY_APPLIED, FOOD_PAYMENT_VALUE_MISMATCH;

        val command: CommandKey get() = when (this) {
            FOOD_CHECK -> RuntimeHistoricalFoodCheckRejectionProfile.FOOD.command
            FOOD_TABLE_ASSIGNMENT -> RuntimeHistoricalConfigurationRejectionProfile.FOOD_TABLE_ASSIGNMENT.command
            FOOD_PAYMENT_ALREADY_APPLIED -> RuntimeHistoricalFoodPaymentRejectionProfile.ALREADY_APPLIED.command
            FOOD_PAYMENT_VALUE_MISMATCH -> RuntimeHistoricalFoodPaymentRejectionProfile.VALUE_OR_TENDER_MISMATCH.command
        }
        val rejectionCodes: Set<String> get() = when (this) {
            FOOD_CHECK -> RuntimeHistoricalFoodCheckRejectionProfile.FOOD.rejectionCodes
            FOOD_TABLE_ASSIGNMENT -> RuntimeHistoricalConfigurationRejectionProfile.FOOD_TABLE_ASSIGNMENT.rejectionCodes
            FOOD_PAYMENT_ALREADY_APPLIED -> setOf(RuntimeHistoricalFoodPaymentRejectionProfile.ALREADY_APPLIED.rejectionCode)
            FOOD_PAYMENT_VALUE_MISMATCH -> setOf(RuntimeHistoricalFoodPaymentRejectionProfile.VALUE_OR_TENDER_MISMATCH.rejectionCode)
        }
        fun matches(header: RuntimeHistoryEventHeaderV2, capture: RuntimeCommandAuthorizationEvidenceV1): Boolean = when (this) {
            FOOD_CHECK -> RuntimeHistoricalFoodCheckRejectionProfile.FOOD.matches(header, capture)
            FOOD_TABLE_ASSIGNMENT -> RuntimeHistoricalConfigurationRejectionProfile.FOOD_TABLE_ASSIGNMENT.matches(header, capture)
            FOOD_PAYMENT_ALREADY_APPLIED -> RuntimeHistoricalFoodPaymentRejectionProfile.ALREADY_APPLIED.matches(header, capture)
            FOOD_PAYMENT_VALUE_MISMATCH -> RuntimeHistoricalFoodPaymentRejectionProfile.VALUE_OR_TENDER_MISMATCH.matches(header, capture)
        }
        fun acceptsRejection(capture: RuntimeCommandAuthorizationEvidenceV1, code: String): Boolean = when (this) {
            FOOD_CHECK -> code in RuntimeHistoricalFoodCheckRejectionProfile.FOOD.rejectionCodes
            FOOD_TABLE_ASSIGNMENT -> RuntimeHistoricalConfigurationRejectionProfile.FOOD_TABLE_ASSIGNMENT.acceptsRejection(capture, code)
            FOOD_PAYMENT_ALREADY_APPLIED -> RuntimeHistoricalFoodPaymentRejectionProfile.ALREADY_APPLIED.acceptsRejection(code)
            FOOD_PAYMENT_VALUE_MISMATCH -> RuntimeHistoricalFoodPaymentRejectionProfile.VALUE_OR_TENDER_MISMATCH.acceptsRejection(code)
        }
        fun markerFields(header: RuntimeHistoryEventHeaderV2, capture: RuntimeCommandAuthorizationEvidenceV1): List<String>? = when (this) {
            FOOD_CHECK -> RuntimeHistoricalFoodCheckRejectionProfile.FOOD.markerFields(header, capture)
            FOOD_TABLE_ASSIGNMENT -> null // Table originals have no FOOD marker or new persisted marker.
            FOOD_PAYMENT_ALREADY_APPLIED, FOOD_PAYMENT_VALUE_MISMATCH -> null // No FOOD-check marker.
        }
    }

    private class Cut(val evidence: RetainedCommandEvidence, val requirement: AuthorizationRequirement,
        val orderKey: AggregateKey, val beforeOrder: RuntimeNativeLiteralAggregateSource?,
        val beforeOrderClaimOwnerId: DomainId?, val orderPresent: Boolean,
        val aggregateSources: List<RuntimeNativeLiteralAggregateSource>, val claimSources: List<RuntimeNativeLiteralClaimSource>,
        val absentAggregates: Set<AggregateKey>, val absentClaims: Set<ClaimKey>)

    private class ObservationCut(val evidence: RetainedCommandEvidence, val requirement: AuthorizationRequirement,
        val resources: List<NativeHistoryOriginalResourceV2>,
        val aggregateSources: List<RuntimeNativeLiteralAggregateSource>, val claimSources: List<RuntimeNativeLiteralClaimSource>,
        val absentAggregates: Set<AggregateKey>, val absentClaims: Set<ClaimKey>)

    suspend fun readFoodCheck(): RuntimeHistoricalFoodCheckRejectedSources? = readSelected(Profile.FOOD_CHECK)?.let { cut ->
        RuntimeHistoricalFoodCheckRejectedSources(cut.evidence, cut.requirement, cut.orderKey, cut.beforeOrder,
            cut.beforeOrderClaimOwnerId, if (cut.orderPresent) RuntimeHistoricalFoodCheckBeforeOrderState.PRESENT
                else RuntimeHistoricalFoodCheckBeforeOrderState.ABSENT,
            cut.aggregateSources, cut.claimSources, cut.absentAggregates, cut.absentClaims)
    }

    suspend fun readFoodTableAssignment(): RuntimeHistoricalFoodTableAssignmentRejectedSources? = readSelected(Profile.FOOD_TABLE_ASSIGNMENT)?.let { cut ->
        RuntimeHistoricalFoodTableAssignmentRejectedSources(cut.evidence, cut.requirement, cut.orderKey, cut.beforeOrder,
            cut.beforeOrderClaimOwnerId, if (cut.orderPresent) RuntimeHistoricalFoodTableAssignmentBeforeOrderState.PRESENT
                else RuntimeHistoricalFoodTableAssignmentBeforeOrderState.ABSENT,
            cut.aggregateSources, cut.claimSources, cut.absentAggregates, cut.absentClaims)
    }

    suspend fun readFoodPaymentAlreadyApplied(): RuntimeHistoricalFoodPaymentAlreadyAppliedSources? {
        val cut = readObserved(Profile.FOOD_PAYMENT_ALREADY_APPLIED) ?: return null
        val target = checkNotNull(cut.requirement.binding).documentId
        val key = AggregateKey(RuntimeHistoricalFoodPaymentAlreadyAppliedSources.APPLICATION_KIND, target)
        // The recording target selects only its observed application, never an ORDER.
        // Missing or contradictory observations do not manufacture an application cut.
        val before = cut.aggregateSources.singleOrNull { it.row.key == key } ?: return null
        historyShapeV2(before.row.schema == RuntimeHistoricalFoodPaymentAlreadyAppliedSources.APPLICATION_SCHEMA &&
            before.row.revision == Revision(2))
        val applied = (before.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        historyShapeV2(applied.committed && applied.nativeOriginalProvenance == null &&
            applied.command in RuntimeHistoricalFoodPaymentAlreadyAppliedSources.APPLICATION_WRITER_COMMANDS &&
            applied.sequence.value < cut.evidence.sequence.value)
        return RuntimeHistoricalFoodPaymentAlreadyAppliedSources(cut.evidence, cut.requirement, cut.resources,
            before, cut.aggregateSources, cut.claimSources, cut.absentAggregates, cut.absentClaims)
    }

    suspend fun readFoodPaymentValueMismatch(): RuntimeHistoricalFoodPaymentValueMismatchSources? {
        val cut = readObserved(Profile.FOOD_PAYMENT_VALUE_MISMATCH) ?: return null
        val key = AggregateKey(RuntimeHistoricalFoodPaymentValueMismatchSources.APPLICATION_KIND,
            checkNotNull(cut.requirement.binding).documentId)
        val before = cut.aggregateSources.singleOrNull { it.row.key == key } ?: return null
        historyShapeV2(before.row.schema == RuntimeHistoricalFoodPaymentValueMismatchSources.APPLICATION_SCHEMA &&
            before.row.revision == Revision(1))
        val raw = (before.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        historyShapeV2(raw.committed && raw.nativeOriginalProvenance != null &&
            raw.command == RuntimeHistoricalFoodPaymentRejectionProfile.VALUE_OR_TENDER_MISMATCH.command &&
            raw.sequence.value < cut.evidence.sequence.value)
        return RuntimeHistoricalFoodPaymentValueMismatchSources(cut.evidence, cut.requirement, cut.resources,
            before, cut.aggregateSources, cut.claimSources, cut.absentAggregates, cut.absentClaims)
    }

    private suspend fun readSelected(profile: Profile): Cut? {
        val cut = readObserved(profile) ?: return null
        val orderKey = AggregateKey(ORDER_KIND, checkNotNull(cut.requirement.binding).documentId)
        val orderClaim = ClaimKey(FOOD_IDENTITY, "$ORDER_KIND/${orderKey.id.value}")
        // These two fixed ORDER profiles require genuine actual observations. Requested
        // revision never selects their native cut; payment uses its separate application cut.
        val beforeOrder = cut.aggregateSources.singleOrNull { it.row.key == orderKey }
        val beforeClaim = cut.claimSources.singleOrNull { it.key == orderClaim }
        val orderPresent = if (beforeOrder == null) {
            historyShapeV2(orderKey in cut.absentAggregates && orderClaim in cut.absentClaims && beforeClaim == null)
            false
        } else {
            historyShapeV2(beforeClaim?.ownerId == orderKey.id)
            true
        }
        return Cut(cut.evidence, cut.requirement, orderKey, beforeOrder, beforeClaim?.ownerId, orderPresent,
            cut.aggregateSources, cut.claimSources, cut.absentAggregates, cut.absentClaims)
    }

    private suspend fun readObserved(profile: Profile): ObservationCut? {
        frame.requireClosedRejectedSourceRead(caller)
        // Fixed metadata/chunk reads and their defensive framing copies are bounded
        // independently of the retained literal sources. Charge before any such read.
        reserve(4L * RuntimeHistoryProfileV2.MAX_HEADER_BYTES +
            4L * RuntimeHistoryProfileV2.MAX_CHUNK_BYTES + 8_192L)
        val requested = (caller.originalForKernel(operationId) as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        if (requested.command != profile.command || requested.committed ||
            requested.nativeOriginalProvenance != null || requested.scope != scope ||
            requested.assignmentId != coordinates.assignmentId || requested.authorityEpoch.value != parent.commandEpoch ||
            requested.code !in profile.rejectionCodes) return null
        reserve(requested.normalized.size.toLong() + requested.result.size)
        authenticateTail()
        val selected = byOperation[operationId] ?: return null
        val capture = selected.command ?: return null
        if (selected.approved || selected.imported != null || !profile.matches(selected.header, capture) ||
            !profile.acceptsRejection(capture, requested.code) || selected.dependencies == null) return null
        val marker = profile.markerFields(selected.header, capture)
        if (marker != null) {
            if (selected.dependencies.none { it.firstOrNull() == RuntimeHistoricalFoodCheckRejectionProfile.MARKER }) return null
            historyShapeV2(selected.dependencies.count { it.firstOrNull() == RuntimeHistoricalFoodCheckRejectionProfile.MARKER } == 1 &&
                selected.dependencies.single { it.firstOrNull() == RuntimeHistoricalFoodCheckRejectionProfile.MARKER } == marker)
        }
        val retained = (readOriginal(selected) as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        historyShapeV2(retained.operationId == requested.operationId && retained.journalHash == requested.journalHash &&
            retained.result == requested.result && retained.normalized == requested.normalized &&
            retained.resultHash == requested.resultHash && retained.sequence == requested.sequence &&
            retained.semanticDigest == requested.semanticDigest && retained.originalActorId == requested.originalActorId &&
            retained.authenticationId == capture.authorization.sessionId && retained.policyRevision == capture.authorization.policyRevision &&
            capture.authorization.deviceId == coordinates.deviceId && !retained.committed &&
            retained.command == profile.command && retained.code in profile.rejectionCodes && profile.acceptsRejection(capture, retained.code) &&
            retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
            retained.nativeOriginalProvenance == null && native.finalDeadlineDecisions.none { it.child.historySequence == selected.header.sequence } &&
            native.protectedFinalDecisions.none { it.child.historySequence == selected.header.sequence } &&
            native.businessPublicationObservations.none { it.child.historySequence == selected.header.sequence })
        requireRefusalMutations(selected, retained)

        val aggregateSources = linkedMapOf<AggregateKey, RuntimeNativeLiteralAggregateSource>()
        val claimSources = linkedMapOf<ClaimKey, RuntimeNativeLiteralClaimSource>()
        val absentAggregates = linkedSetOf<AggregateKey>()
        val absentClaims = linkedSetOf<ClaimKey>()
        var commandSeen = false; var markerSeen = false
        for (observation in selected.dependencies) {
            when (observation.firstOrNull()) {
                "aggregate" -> {
                    historyShapeV2(observation.size == 6)
                    val key = AggregateKey(observation[1], DomainId(observation[2]))
                    val source = if (key in absentAggregates) null else aggregateSources[key] ?: aggregateBefore(selected, key)
                    historyShapeV2(observation[3] == (source?.row?.revision?.value?.toString() ?: "absent") &&
                        observation[4] == (source?.row?.schema ?: "") && observation[5] == (source?.row?.digest?.value ?: ""))
                    if (source == null) absentAggregates += key else aggregateSources[key] = source
                }
                "claim" -> {
                    historyShapeV2(observation.size == 4)
                    val key = ClaimKey(observation[1], observation[2])
                    val source = if (key in absentClaims) null else claimSources[key] ?: claimBefore(selected, key)
                    historyShapeV2(observation[3] == (source?.ownerId?.value ?: "absent"))
                    if (source == null) absentClaims += key else claimSources[key] = source
                }
                "command" -> {
                    val auth = capture.authorization
                    historyShapeV2(!commandSeen && observation == listOf("command", capture.commandName,
                        capture.commandVersion.toString(), auth.actorId.value, auth.sessionId.value, auth.deviceId.value,
                        auth.sessionId.value, auth.generation.value.toString(), capture.semanticDigest.value))
                    commandSeen = true
                }
                RuntimeHistoricalFoodCheckRejectionProfile.MARKER -> {
                    historyShapeV2(marker != null && !markerSeen && observation == marker); markerSeen = true
                }
                else -> unavailable()
            }
        }
        historyShapeV2(commandSeen && markerSeen == (marker != null))
        val resources = selected.intent.resources
        // Escaping source DATA copies list/set metadata and the captured resource values.
        // The rows and complete originals themselves were charged before hydration.
        reserve(2_048L + resources.size * 256L +
            (aggregateSources.size.toLong() + claimSources.size + absentAggregates.size + absentClaims.size) * 64L)
        frame.requireClosedRejectedSourceRead(caller)
        return ObservationCut(retained, capture.requirement.value(), resources,
            aggregateSources.values.toList(), claimSources.values.toList(), absentAggregates, absentClaims)
    }

    private suspend fun authenticateTail() {
        val census = frame.foodCheckHistoryCensus()
        historyShapeV2(census.heads == 1L && census.events == parent.historySequence)
        historyCapacityV2(census.versions <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            census.applicationRows.getValue(RuntimeHistoryTableV2.OPERATION) <= RuntimeBusinessHistoryJoin.MAX_COMMANDS)
        val head = frame.foodCheckHistoryHead()
        historyShapeV2(head.scope == scopeKey && head.storageId == coordinates.storageInstanceId.value &&
            head.bindingDigest == native.allocation.originalNativeBindingDigest.value &&
            head.installationGenesisDigest == parent.installationGenesisDigest.value && head.sequence == parent.historySequence &&
            head.eventDigest == parent.historyDigest.value && head.commandEpoch == parent.commandEpoch &&
            head.commandSequence == parent.commandSequence && head.commandDigest == parent.commandDigest.value &&
            head.roleCommitment == parent.roleCommitment.value && head.recoveryCommitment == parent.recoveryCommitment.value)
        var previous: Event? = null
        val transactionIds = mutableSetOf<DomainId>()
        for (sequence in 1L..parent.historySequence) {
            currentCoroutineContext().ensureActive()
            historyCapacityV2(events.size < RuntimeBusinessHistoryJoin.MAX_VERSIONS)
            val row = frame.foodCheckHistoryEvent(sequence)
            val header = try { RuntimeHistoryCodecV2.decodeHeader(row.header) } finally { row.header.fill(0) }
            val definition = checkNotNull(native.definition); val attempt = checkNotNull(native.attempt)
            historyShapeV2(header.sequence == sequence && header.scope == scopeKey &&
                header.storageId == coordinates.storageInstanceId && header.bindingDigest == native.allocation.originalNativeBindingDigest &&
                header.originalAllocationDigest == native.allocation.digest && header.definitionDigest == definition.definitionDigest &&
                header.installationAttemptId == attempt.facts.issuedAttemptId && header.installationAttemptSequence == attempt.facts.attemptSequence &&
                header.assignmentId == coordinates.assignmentId && header.installationGenesisDigest == parent.installationGenesisDigest &&
                header.changeCount > 0 && transactionIds.add(header.transactionId))
            if (previous == null) historyShapeV2(sequence == 1L && header.parentKind == 1 && header.parentSequence == 0L &&
                header.parentDigest == native.allocation.digest && header.purpose == RuntimeWritePurposeV2.ORIGINAL_INSTALLATION)
            else historyShapeV2(header.parentKind == 2 && header.parentSequence == previous.header.sequence &&
                header.parentDigest == previous.digest && header.purpose != RuntimeWritePurposeV2.ORIGINAL_INSTALLATION &&
                header.commandEpoch >= previous.header.commandEpoch &&
                (header.commandEpoch != previous.header.commandEpoch || header.commandSequence >= previous.header.commandSequence))
            reserve(header.intentBytes.toLong() * 2L + header.factBytes.toLong() * 2L + 2048L)
            // Capsule arrays, strict decoder/canonical copies and dependency framing are
            // transient but not free. These stay charged until the whole event finishes.
            val capsuleBudget = 4L * (header.intentBytes.toLong() + header.dependencyBytes + header.factBytes)
            reserve(capsuleBudget)
            val stream = RuntimeHistoryEventStreamV2(header)
            val capsule = RuntimeHistoryCapsule(header)
            val mutations = mutableListOf<RuntimeHistoryRowVersionRowV2>()
            val imageCursor = ImageCursor(header)
            try {
                for (ordinal in 0 until header.changeCount) {
                    historyCapacityV2(versions < RuntimeBusinessHistoryJoin.MAX_VERSIONS)
                    val version = frame.foodCheckHistoryVersion(sequence, ordinal)
                    val encoded = RuntimeHistoryCodecV2.encodeMutation(version)
                    try { reserve(encoded.size.toLong() + version.key.size * 5L + 256L) } finally { encoded.fill(0) }
                    requirePredecessor(version)
                    mutations += version; stream.appendMutation(version); versions++
                    if (version.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag) imageCursor.add(version)
                    else if (version.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag)
                        withAfter(version) { requireRow(version, it) }
                }
                for (ordinal in 0 until header.chunkCount) {
                    val chunk = frame.foodCheckHistoryChunk(sequence, ordinal)
                    try {
                        stream.appendChunk(chunk); capsule.append(chunk); imageCursor.append(chunk)
                        traversedBytes = historyAddV2(traversedBytes, chunk.bytes.size.toLong(), RuntimeHistoryProfileV2.MAX_DATASET_BYTES)
                        chunks++
                    } finally { chunk.bytes.fill(0) }
                }
                imageCursor.finish()
                val commitment = stream.finish(); val intent = capsule.finish()
                historyShapeV2(commitment.digest.value == row.eventDigest && commitment.encodedBytes == row.encodedBytes)
                val approved = RuntimeActionApprovalEvidenceCodec.hasCommandProfile(capsule.facts)
                val imported = if (RuntimeNativeOriginalImportEvidenceCodec.hasProfile(capsule.facts))
                    RuntimeNativeOriginalImportEvidenceCodec.decode(capsule.facts) else null
                val command = when {
                    RuntimeCommandAuthorizationEvidenceCodec.hasProfile(capsule.facts) -> RuntimeCommandAuthorizationEvidenceCodec.decode(capsule.facts)
                    approved -> RuntimeActionApprovalEvidenceCodec.decodeCommand(capsule.facts).command()
                    else -> null
                }
                if (header.purpose in setOf(RuntimeWritePurposeV2.COMMAND_APPLY, RuntimeWritePurposeV2.COMMAND_REJECT)) {
                    historyShapeV2((command == null) != (imported == null) && intent.originalOperationId != null &&
                        intent.originalRequestId == intent.originalOperationId)
                    command?.let { historyShapeV2(it.authorization.scope == scope && it.purposeTag == header.purpose.tag &&
                        it.capturedChanges == header.changeCount && it.operationId.value == intent.originalOperationId?.value &&
                        it.authorization.actorId == intent.originalActorId && it.semanticDigest == intent.originalRequestDigest) }
                    imported?.let { historyShapeV2(header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && it.scope == scope &&
                        it.capturedChanges == header.changeCount && it.producer.operationId.value == intent.originalOperationId?.value &&
                        it.producer.originalActorId == intent.originalActorId && it.producer.semanticDigest == intent.originalRequestDigest) }
                } else historyShapeV2(command == null && imported == null && intent.originalOperationId == null)
                val dependencies = if (intent.originalOperationId?.value == operationId.value) {
                    // Includes bounded slice/UTF-8/list metadata, even many short fields.
                    // Reserve before the unchanged strict dependency decoder allocates.
                    reserve(header.dependencyBytes.toLong() * 16L + 8_192L)
                    runtimeFoodCheckDependencyFields(capsule.dependencies)
                } else null
                val event = Event(header, commitment.digest, intent, command, approved, imported, mutations.toList(), dependencies)
                events[sequence] = event
                event.operationId?.let {
                    historyCapacityV2(byOperation.size < RuntimeBusinessHistoryJoin.MAX_COMMANDS)
                    historyShapeV2(byOperation.put(it, event) == null && mutations.count { v -> v.tableTag == RuntimeHistoryTableV2.OPERATION.tag } == 1 &&
                        mutations.count { v -> v.tableTag == RuntimeHistoryTableV2.JOURNAL.tag } == 1)
                }
                val afterBranch = branch ?: unavailable()
                historyShapeV2(afterBranch.scope == scopeKey && afterBranch.assignmentId == coordinates.assignmentId.value &&
                    afterBranch.deviceId == coordinates.deviceId.value && afterBranch.epoch == header.commandEpoch &&
                    afterBranch.sequence == header.commandSequence && afterBranch.journalHash == header.commandDigest.value &&
                    RuntimeHistoryCodecV2.roleCommitment(afterBranch) == header.roleCommitment &&
                    RuntimeHistoryCodecV2.recoveryCommitment(afterBranch) == header.recoveryCommitment)
                if (sequence == 1L) historyShapeV2(checkNotNull(native.firstChild) ==
                    NativeHistoryRecordedHeadV2(header.sequence, event.digest, header.installationGenesisDigest,
                        header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment) &&
                    header.commandSequence == 0L && intent.originalActorId == null && intent.originalOperationId == null &&
                    intent.originalRequestId == definition.definition.originalRequestId && intent.originalRequestDigest == definition.definitionDigest)
                previous = event
            } finally { imageCursor.clear(); stream.clear(); capsule.clear(); release(capsuleBudget) }
        }
        val last = previous ?: unavailable()
        historyShapeV2(NativeHistoryRecordedHeadV2(last.header.sequence, last.digest, last.header.installationGenesisDigest,
            last.header.commandEpoch, last.header.commandSequence, last.header.commandDigest,
            last.header.roleCommitment, last.header.recoveryCommitment) == parent &&
            versions == census.versions && chunks == census.chunks &&
            byOperation.size.toLong() == census.applicationRows.getValue(RuntimeHistoryTableV2.OPERATION))
        requireTipCensus(census)
        val after = frame.foodCheckHistoryCensus()
        historyShapeV2(after.heads == census.heads && after.events == census.events && after.versions == census.versions &&
            after.chunks == census.chunks && after.tips == census.tips && after.applicationRows == census.applicationRows)
    }

    private fun requirePredecessor(version: RuntimeHistoryRowVersionRowV2) {
        val token = token(RuntimeHistoryTableV2.fromTag(version.tableTag), version.key)
        val history = histories.getOrPut(token) { mutableListOf() }
        val prior = history.lastOrNull()
        if (prior == null) historyShapeV2(version.version == 1L && version.previousSequence == null)
        else {
            historyShapeV2(version.version == prior.version + 1 && version.previousSequence == prior.sequence &&
                version.previousOrdinal == prior.ordinal && version.previousVersion == prior.version)
            if (version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag)
                historyShapeV2(prior.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag)
            else historyShapeV2(prior.afterKind != RuntimeHistoryAfterKindV2.TOMBSTONE.tag &&
                version.beforeRowId == prior.afterRowId && version.beforeDigest == prior.afterDigest)
        }
        history += version
    }

    private suspend fun requireTipCensus(census: RoomFoodCheckNativeCensus) {
        historyShapeV2(histories.size.toLong() == census.tips)
        val liveCounts = mutableMapOf<RuntimeHistoryTableV2, Long>()
        val rowIds = mutableSetOf<Pair<RuntimeHistoryTableV2, Long>>()
        for (history in histories.values) {
            val version = history.last(); val table = RuntimeHistoryTableV2.fromTag(version.tableTag)
            val tip = frame.foodCheckHistoryTip(table, version.key)
            try {
                historyShapeV2(tip.tableTag == version.tableTag && tip.key.contentEquals(version.key) && tip.version == version.version &&
                    tip.sequence == version.sequence && tip.ordinal == version.ordinal &&
                    tip.tombstone == (if (version.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag) 1 else 0))
                historyShapeV2(tip.targetRowId == version.afterRowId && tip.rowDigest == version.afterDigest)
                if (tip.tombstone == 0) {
                    val rowId = checkNotNull(tip.targetRowId)
                    historyShapeV2(rowIds.add(table to rowId))
                    val budget = 4L * RuntimeHistoryProfileV2.MAX_ROW_BYTES
                    reserve(budget)
                    try {
                        val current = RuntimeHistoryRowViewV2(frame.foodCheckHistoryCurrentRow(table, rowId))
                        try { requireRow(version, current) } finally { current.clear() }
                    } finally { release(budget) }
                    liveCounts[table] = (liveCounts[table] ?: 0L) + 1L
                }
            } finally { tip.key.fill(0) }
        }
        historyShapeV2(census.applicationRows.all { (table, count) -> count == (liveCounts[table] ?: 0L) })
    }

    private suspend fun requireRefusalMutations(event: Event, retained: RetainedCommandEvidence) {
        val tables = setOf(RuntimeHistoryTableV2.OPERATION, RuntimeHistoryTableV2.JOURNAL,
            RuntimeHistoryTableV2.AUDIT, RuntimeHistoryTableV2.OUTBOX, RuntimeHistoryTableV2.BRANCH)
        historyShapeV2(event.mutations.size == 5 && event.mutations.map { RuntimeHistoryTableV2.fromTag(it.tableTag) }.toSet() == tables &&
            event.header.sequence > 1L && event.header.commandSequence == retained.sequence.value &&
            event.header.commandDigest == retained.journalHash && event.header.commandEpoch == retained.authorityEpoch.value &&
            event.intent.resources.all { it.selectorTag == 3 && it.preserveHold && it.originalEvidenceDigest == retained.semanticDigest })
        val previous = events[event.header.parentSequence] ?: unavailable()
        historyShapeV2(event.header.commandSequence == previous.header.commandSequence + 1 &&
            event.header.commandEpoch == previous.header.commandEpoch && event.header.roleCommitment == previous.header.roleCommitment &&
            event.header.recoveryCommitment == previous.header.recoveryCommitment)
        val branchVersion = event.one(RuntimeHistoryTableV2.BRANCH)
        val before = beforeVersion(RuntimeHistoryTableV2.BRANCH, branchVersion.key, event.header.sequence) ?: unavailable()
        val beforeBranch = withAfter(before) { it.branch() }
        withAfter(branchVersion) { row -> historyShapeV2(branchVersion.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag &&
            row.branch() == beforeBranch.copy(revision = Revision(beforeBranch.revision).next().value,
                sequence = retained.sequence.value, journalHash = retained.journalHash.value)) }
        for (table in tables - RuntimeHistoryTableV2.BRANCH) {
            val version = event.one(table)
            historyShapeV2(version.version == 1L && version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                version.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag && version.beforeRowId == null && version.beforeDigest == null)
        }
        withAfter(event.one(RuntimeHistoryTableV2.AUDIT)) { row ->
            val value = row.audit()
            historyShapeV2(value.scope == scopeKey && value.operationId == retained.operationId.value &&
                value.kind == "command.${retained.code}" && value.actorId == retained.originalActorId.value &&
                value.originalActorId == retained.originalActorId.value && value.policyRevision == retained.policyRevision.value &&
                value.recordedAt == retained.recordedAt.toString() && value.digest == retained.semanticDigest.value)
        }
        val upload = framedText(listOf("ukkera.journal-upload/v1", scope.branchId.value, retained.authorityEpoch.value.toString(),
            retained.sequence.value.toString(), retained.journalHash.value))
        try { withAfter(event.one(RuntimeHistoryTableV2.OUTBOX)) { row ->
            val value = row.outbox()
            try { historyShapeV2(value.scope == scopeKey && value.operationId == retained.operationId.value && value.ordinal == 0 &&
                value.kind == SYNC_JOB && value.body.contentEquals(upload) && value.bodyHash == sha256(upload).value) }
            finally { value.body.fill(0) }
        } } finally { upload.fill(0) }
    }

    private suspend fun readOriginal(event: Event): RetainedOriginalV2 {
        val id = event.operationId ?: unavailable()
        originals[id]?.let { return it }
        val operation = withAfter(event.one(RuntimeHistoryTableV2.OPERATION)) {
            it.operation().also { row -> reserve(row.result.size.toLong() * 4L + 1_024L) }
        }
        val journal = try { withAfter(event.one(RuntimeHistoryTableV2.JOURNAL)) {
            // Complete original bytes, scalar/effect metadata and defensive decoder copies
            // stay charged while escaped literal DATA may retain this original.
            it.journal().also { row -> reserve(row.envelope.size.toLong() * 4L + 8_192L) }
        } }
        catch (failure: Throwable) { operation.result.fill(0); throw failure }
        try {
            historyShapeV2(operation.scope == scopeKey && journal.scope == scopeKey && operation.operationId == id.value &&
                journal.operationId == id.value && operation.actorId == event.intent.originalActorId?.value &&
                operation.digest == event.intent.originalRequestDigest.value && operation.epoch == event.header.commandEpoch &&
                journal.epoch == operation.epoch && operation.sequence == event.header.commandSequence && journal.sequence == operation.sequence &&
                journal.assignmentId == coordinates.assignmentId.value && journal.hash == event.header.commandDigest.value &&
                sha256(journal.envelope).value == journal.hash && sha256(operation.result).value == operation.resultHash)
            event.command?.let { capture -> historyShapeV2(operation.command == capture.commandName &&
                operation.commandVersion == capture.commandVersion && operation.actorId == capture.authorization.actorId.value &&
                capture.authorization.scope == scope && (event.approved || operation.authenticationId == capture.authorization.sessionId.value &&
                    operation.policyRevision == capture.authorization.policyRevision.value)) }
            val original = if (RetainedCommandFramesV2.isRoot(journal.envelope)) {
                historyShapeV2(event.imported == null && operation.outcome == "committed")
                val current = (caller.originalForKernel(id) as? RetainedOriginalV2.Streamed) ?: unavailable()
                val bytes = RetainedCommandFramesV2.encodeRoot(current.evidence.root)
                try { historyShapeV2(bytes.contentEquals(journal.envelope)) } finally { bytes.fill(0) }
                val identity = current.evidence.identity
                val result = current.evidence.result.copyBytes()
                try { historyShapeV2(identity.scope == scope && identity.assignmentId == coordinates.assignmentId &&
                    identity.authorityEpoch.value == operation.epoch && identity.sequence.value == operation.sequence &&
                    identity.producer.operationId == id && identity.producer.command == CommandKey(operation.command, operation.commandVersion) &&
                    identity.producer.originalActorId.value == operation.actorId && identity.producer.semanticDigest.value == operation.digest &&
                    identity.authenticationId.value == operation.authenticationId && identity.policyRevision.value == operation.policyRevision &&
                    identity.recordedAt.toString() == operation.committedAt && identity.previousJournalHash.value == journal.previousHash &&
                    identity.resultHash.value == operation.resultHash && current.evidence.journalHash.value == journal.hash &&
                    result.contentEquals(operation.result)) } finally { result.fill(0) }
                current
            } else RetainedOriginalV2.Legacy(decodeRetainedCommand(scope, id, operation, journal) ?: unavailable())
            if (original is RetainedOriginalV2.Legacy) {
                val imported = event.imported
                if (imported == null) historyShapeV2(original.evidence.nativeOriginalProvenance == null)
                else {
                    // Native raw sources retain their distinct literal import provenance.
                    // Pairing its authentic import capsule never recasts Start mirrors as
                    // employee-command authentication or a completed business proof.
                    val evidence = original.evidence
                    historyShapeV2(evidence.committed && evidence.nativeOriginalProvenance == imported.provenance &&
                        imported.producer == StreamedProducerIdentityV2(evidence.operationId, evidence.command,
                            evidence.originalActorId, evidence.semanticDigest) && imported.scope == evidence.scope &&
                        imported.provenance.runtimeParent.sequence.value == event.header.parentSequence &&
                        imported.provenance.runtimeParent.frameHash == event.header.parentDigest)
                }
            }
            historyShapeV2(originals.put(id, original) == null)
            return original
        } finally { operation.result.fill(0); journal.envelope.fill(0) }
    }

    private suspend fun aggregateBefore(event: Event, key: AggregateKey): RuntimeNativeLiteralAggregateSource? {
        val packedKey = physicalKey(RuntimeHistoryTableV2.AGGREGATE, scopeKey, key.kind, key.id.value)
        val version = try { beforeVersion(RuntimeHistoryTableV2.AGGREGATE, packedKey, event.header.sequence) } finally { packedKey.fill(0) }
            ?: return null
        val writer = earlierWriter(event, version)
        val original = readOriginal(writer)
        val row = withAfter(version) { value ->
            val aggregate = value.aggregate()
            try { historyShapeV2(aggregate.scope == scopeKey && aggregate.kind == key.kind && aggregate.id == key.id.value &&
                aggregate.payloadHash == sha256(aggregate.payload).value)
                reserve(aggregate.payload.size.toLong() * 2L + 512L)
                StoredAggregate(key, Revision(aggregate.revision), aggregate.schema, StoredBytes(aggregate.payload), PayloadDigest(aggregate.payloadHash))
            } finally { aggregate.payload.fill(0) }
        }
        var matched = 0
        fun compare(write: RetainedAggregateWrite) {
            if (write.key != key) return
            historyShapeV2(write.storageGeneration == row.revision && write.schema == row.schema &&
                write.payload == row.payload && write.payloadDigest == row.digest); matched++
        }
        when (original) {
            is RetainedOriginalV2.Legacy -> { historyShapeV2(original.evidence.committed); original.evidence.aggregateWrites.forEach(::compare) }
            is RetainedOriginalV2.Streamed -> original.evidence.frames { if (it is RetainedCommandFrameV2.Write) compare(it.value) }
        }
        historyShapeV2(matched == 1)
        return RuntimeNativeLiteralAggregateSource(row, original, writer.header.sequence)
    }

    private suspend fun claimBefore(event: Event, key: ClaimKey): RuntimeNativeLiteralClaimSource? {
        val packedKey = physicalKey(RuntimeHistoryTableV2.CLAIM, scopeKey, key.namespace, key.key)
        val version = try { beforeVersion(RuntimeHistoryTableV2.CLAIM, packedKey, event.header.sequence) } finally { packedKey.fill(0) }
            ?: return null
        val writer = earlierWriter(event, version)
        val original = readOriginal(writer)
        val owner = withAfter(version) { value -> val claim = value.claim()
            historyShapeV2(claim.scope == scopeKey && claim.namespace == key.namespace && claim.key == key.key)
            DomainId(claim.ownerId)
        }
        var matched = 0
        fun compare(change: RetainedClaimChange) {
            if (change.key != key) return
            historyShapeV2(change.action == RetainedClaimAction.ACQUIRE && change.ownerId == owner); matched++
        }
        when (original) {
            is RetainedOriginalV2.Legacy -> { historyShapeV2(original.evidence.committed); original.evidence.claimChanges.forEach(::compare) }
            is RetainedOriginalV2.Streamed -> original.evidence.frames { if (it is RetainedCommandFrameV2.Claim) compare(it.value) }
        }
        historyShapeV2(matched == 1)
        return RuntimeNativeLiteralClaimSource(key, owner, original, writer.header.sequence)
    }

    private fun earlierWriter(event: Event, version: RuntimeHistoryRowVersionRowV2): Event {
        val writer = events[version.sequence] ?: unavailable()
        historyShapeV2(writer.header.sequence < event.header.sequence && writer.header.commandEpoch == event.header.commandEpoch &&
            writer.header.assignmentId == event.header.assignmentId && writer.header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
            writer.operationId != event.operationId)
        return writer
    }

    private fun beforeVersion(table: RuntimeHistoryTableV2, key: ByteArray, sequence: Long): RuntimeHistoryRowVersionRowV2? {
        val last = histories[token(table, key)]?.lastOrNull { it.sequence < sequence } ?: return null
        return last.takeUnless { it.afterKind == RuntimeHistoryAfterKindV2.TOMBSTONE.tag }
    }

    private suspend fun <T> withAfter(version: RuntimeHistoryRowVersionRowV2, consume: (RuntimeHistoryRowViewV2) -> T): T {
        val table = RuntimeHistoryTableV2.fromTag(version.tableTag)
        val immutable = version.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag
        historyShapeV2(immutable || version.afterKind == RuntimeHistoryAfterKindV2.IMAGE.tag)
        val budget = if (immutable) 4L * RuntimeHistoryProfileV2.MAX_ROW_BYTES
            else checkNotNull(version.imageBytes).toLong() * 4L + 4L * RuntimeHistoryProfileV2.MAX_CHUNK_BYTES
        reserve(budget)
        try {
            val row = if (immutable) frame.foodCheckHistoryCurrentRow(table, checkNotNull(version.afterRowId)) else {
                val size = checkNotNull(version.imageBytes); val offset = checkNotNull(version.imageOffset)
                val bytes = ByteArray(size)
                try {
                    var copied = 0
                    val first = (offset / RuntimeHistoryProfileV2.MAX_CHUNK_BYTES).toInt()
                    val last = ((offset + size - 1L) / RuntimeHistoryProfileV2.MAX_CHUNK_BYTES).toInt()
                    for (ordinal in first..last) {
                        val chunk = frame.foodCheckHistoryChunk(version.sequence, ordinal)
                        try {
                            historyShapeV2(RuntimeHistoryCodecV2.chunkDigest(version.sequence, ordinal, chunk.bytes).value == chunk.digest)
                            val start = ordinal.toLong() * RuntimeHistoryProfileV2.MAX_CHUNK_BYTES
                            val from = maxOf(start, offset); val to = minOf(start + chunk.bytes.size, offset + size)
                            historyShapeV2(to > from && from - offset == copied.toLong())
                            chunk.bytes.copyInto(bytes, copied, (from - start).toInt(), (to - start).toInt()); copied += (to - from).toInt()
                        } finally { chunk.bytes.fill(0) }
                    }
                    historyShapeV2(copied == size && sha256(bytes).value == version.afterDigest)
                    RuntimeHistoryRowCodecV2.decode(bytes)
                } finally { bytes.fill(0) }
            }
            val view = RuntimeHistoryRowViewV2(row)
            return try { requireRow(version, view); consume(view) } finally { view.clear() }
        } finally { release(budget) }
    }

    private fun requireRow(version: RuntimeHistoryRowVersionRowV2, row: RuntimeHistoryRowViewV2) {
        val key = row.key()
        try { historyShapeV2(row.table.tag == version.tableTag && row.rowId == version.afterRowId &&
            key.contentEquals(version.key) && row.digest().value == version.afterDigest && row.text("scope") == scopeKey) }
        finally { key.fill(0) }
    }

    /** At most one bounded original row is assembled; the event itself always streams. */
    private inner class ImageCursor(private val header: RuntimeHistoryEventHeaderV2) {
        private val images = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        private var at = 0; private var copied = 0; private var bytes: ByteArray? = null; private var budget = 0L
        fun add(version: RuntimeHistoryRowVersionRowV2) { images += version }
        fun append(chunk: RuntimeHistoryChunkRowV2) {
            val start = chunk.ordinal.toLong() * RuntimeHistoryProfileV2.MAX_CHUNK_BYTES
            while (at < images.size) {
                val version = images[at]; val offset = checkNotNull(version.imageOffset); val size = checkNotNull(version.imageBytes)
                val from = maxOf(start, offset); val to = minOf(start + chunk.bytes.size, offset + size)
                if (from >= to) break
                if (bytes == null) { budget = size.toLong() * 4L; reserve(budget); bytes = ByteArray(size) }
                historyShapeV2(from - offset == copied.toLong())
                chunk.bytes.copyInto(checkNotNull(bytes), copied, (from - start).toInt(), (to - start).toInt()); copied += (to - from).toInt()
                if (copied != size) break
                val value = checkNotNull(bytes)
                var row: RuntimeHistoryRowViewV2? = null
                try {
                    val decoded = RuntimeHistoryRowViewV2(RuntimeHistoryRowCodecV2.decode(value)); row = decoded
                    historyShapeV2(sha256(value).value == version.afterDigest); requireRow(version, decoded)
                    if (decoded.table == RuntimeHistoryTableV2.BRANCH) branch = decoded.branch()
                } finally { row?.clear(); value.fill(0); bytes = null; copied = 0; release(budget); budget = 0L }
                at++
            }
        }
        fun finish() { historyShapeV2(at == images.size && bytes == null && header.imageBytes == images.sumOf { checkNotNull(it.imageBytes).toLong() }) }
        fun clear() { bytes?.fill(0); bytes = null; if (budget != 0L) release(budget); budget = 0L; images.clear() }
    }

    fun clear() {
        histories.values.forEach { history -> history.forEach { it.key.fill(0) } }; histories.clear()
        events.clear(); byOperation.clear(); originals.clear(); branch = null
    }
    private fun reserve(bytes: Long) { retainedBytes = historyAddV2(retainedBytes, bytes, RuntimeBusinessHistoryJoin.MAX_RETAINED_BYTES) }
    private fun release(bytes: Long) { historyShapeV2(bytes >= 0L && bytes <= retainedBytes); retainedBytes -= bytes }
    private fun Event.one(table: RuntimeHistoryTableV2): RuntimeHistoryRowVersionRowV2 =
        mutations.singleOrNull { it.tableTag == table.tag } ?: unavailable()
    private fun token(table: RuntimeHistoryTableV2, key: ByteArray): String = table.tag.toString() + ":" +
        key.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun physicalKey(table: RuntimeHistoryTableV2, vararg values: String): ByteArray {
        val cells = values.map { value -> val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
            try { NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) } }
        return try { RuntimeHistoryRowCodecV2.encodeKey(table, cells) } finally { cells.forEach { it.clear() } }
    }
    private fun framedText(values: List<String>): ByteArray {
        val fields = values.map { it.encodeToByteArray(throwOnInvalidSequence = true) }
        return try { com.ukkera.pos.database.frame(fields) } finally { fields.forEach { it.fill(0) } }
    }
    private fun unavailable(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.UNRESOLVED_PROTOCOL)
    private companion object {
        const val ORDER_KIND = "sales.food.order"
        const val FOOD_IDENTITY = "sales.food.identity"
    }
}
