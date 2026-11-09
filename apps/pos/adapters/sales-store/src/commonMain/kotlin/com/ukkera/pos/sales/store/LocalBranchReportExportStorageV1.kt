package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

internal object LocalBranchReportExportProfileV1 {
    const val KIND = "local.report-export.audit"
    const val SCHEMA = "local.report-export.audit/v1"
    const val OWNER_NAMESPACE = "local.report-export.owner"
    fun key(id: DomainId): AggregateKey = AggregateKey(KIND, id)
    fun owner(id: DomainId): ClaimKey = ClaimKey(OWNER_NAMESPACE, id.value)
}

/** At most Begin, first observation, and an exact UNKNOWN-to-PUBLISHED observation. */
@Serializable
internal data class LocalBranchReportExportStateV1(
    val scope: BranchScope,
    val view: LocalBranchReportExportViewV1,
    val operations: List<OperationId>,
)

internal data class LocalBranchReportExportCutV1(
    val stored: StoredAggregate,
    val value: LocalBranchReportExportStateV1,
)

internal fun reportExportStateBytes(value: LocalBranchReportExportStateV1): StoredBytes =
    localSalesEncode(value, LocalBranchReportExportStateV1.serializer(), 16_384)

internal fun beginReportExport(
    scope: BranchScope, actor: DomainId, operation: OperationId, at: UtcInstant,
    request: LocalBranchReportExportRequestV1.Begin,
): LocalBranchReportExportStateV1 = LocalBranchReportExportStateV1(scope,
    LocalBranchReportExportViewV1(request.exportId, actor, request.query, request.nativeReceiptId,
        request.destination, Revision(0), LocalBranchReportExportStageV1.REQUESTED, operation, operation,
        at, null, null, request.coverage), listOf(operation))

internal fun reportExportFinishRejection(
    state: LocalBranchReportExportStateV1,
    actor: DomainId,
    request: LocalBranchReportExportRequestV1.Finish,
): LocalBranchReportExportRejectionV1? {
    val value = state.view
    if (actor != value.ownerId) return LocalBranchReportExportRejectionV1.OWNER_REQUIRED
    if (request.exportId != value.exportId) return LocalBranchReportExportRejectionV1.NOT_FOUND
    if (request.expectedRevision != value.revision) return LocalBranchReportExportRejectionV1.REVISION_CONFLICT
    if (request.nativeReceiptId != value.nativeReceiptId) return LocalBranchReportExportRejectionV1.NATIVE_RECEIPT_MISMATCH
    when (value.stage) {
        LocalBranchReportExportStageV1.REQUESTED -> Unit
        LocalBranchReportExportStageV1.PUBLICATION_OUTCOME_UNKNOWN ->
            if (request.outcome != LocalBranchReportExportOutcomeV1.CLIENT_ATTESTED_PUBLISHED)
                return LocalBranchReportExportRejectionV1.TERMINAL_OR_UNSUPPORTED_TRANSITION
        else -> return LocalBranchReportExportRejectionV1.TERMINAL_OR_UNSUPPORTED_TRANSITION
    }
    if (value.file != null && request.file != value.file) return LocalBranchReportExportRejectionV1.FILE_IDENTITY_CONFLICT
    return null
}

/** Records only what the authenticated client reported; never performs or certifies file IO. */
internal fun finishReportExport(
    state: LocalBranchReportExportStateV1, actor: DomainId, operation: OperationId, at: UtcInstant,
    request: LocalBranchReportExportRequestV1.Finish,
): LocalBranchReportExportStateV1 {
    check(reportExportFinishRejection(state, actor, request) == null)
    check(state.operations.size < 3 && operation !in state.operations)
    val stage = when (request.outcome) {
        LocalBranchReportExportOutcomeV1.CLIENT_ATTESTED_PUBLISHED -> LocalBranchReportExportStageV1.CLIENT_ATTESTED_PUBLISHED
        LocalBranchReportExportOutcomeV1.CANCELLED_BEFORE_PUBLICATION -> LocalBranchReportExportStageV1.CANCELLED_BEFORE_PUBLICATION
        LocalBranchReportExportOutcomeV1.PUBLICATION_OUTCOME_UNKNOWN -> LocalBranchReportExportStageV1.PUBLICATION_OUTCOME_UNKNOWN
    }
    return state.copy(view = state.view.copy(revision = state.view.revision.next(), stage = stage,
        lastOperationId = operation, lastReportedAt = at, file = request.file), operations = state.operations + operation)
}

/**
 * Validate the complete bounded local producer sequence, result, exact aggregate effects and
 * current immutable owner claim. A generic aggregate kind is not exclusive producer authority.
 * Retained hashes establish consistency, not complete-history/rollback/native-file proof.
 */
internal suspend fun readReportExport(view: CommandReadView, id: DomainId): LocalBranchReportExportCutV1? {
    val row = view.aggregate(LocalBranchReportExportProfileV1.key(id))
    val owner = view.claim(LocalBranchReportExportProfileV1.owner(id))
    return requireReportExportSource(view.scope, id, row, owner, view::retainedCommand)
}

/** Bounded consistency comparison only. The caller supplies real transaction observations
 * or native-selected BEFORE sources; this function creates neither kind of authority. */
internal suspend fun requireReportExportSource(scope: BranchScope, id: DomainId, row: StoredAggregate?,
    owner: DomainId?, original: suspend (OperationId) -> RetainedCommandEvidence?): LocalBranchReportExportCutV1? {
    val key = LocalBranchReportExportProfileV1.key(id)
    if (row == null) { requireLocalSalesSource(owner == null); return null }
    requireLocalSalesSource(row.key == key && row.schema == LocalBranchReportExportProfileV1.SCHEMA &&
        row.digest == sha256(row.payload.copyBytes()))
    val value = validateLocalSalesSource {
        localSalesDecode(row.payload, LocalBranchReportExportStateV1.serializer(), 16_384)
    }
    requireLocalSalesSource(value.scope == scope && value.view.exportId == id && owner == value.view.ownerId &&
        value.view.query.delivery == LocalBranchReportDeliveryV1.EXPORT && value.view.revision.value in 0L..2L &&
        value.operations.size in 1..3 && value.operations.size.toLong() == value.view.revision.value + 1 &&
        value.operations.distinct().size == value.operations.size &&
        value.operations.first() == value.view.originalOperationId && value.operations.last() == value.view.lastOperationId &&
        row.revision == value.view.revision.next())

    var reconstructed: LocalBranchReportExportStateV1? = null
    for ((index, operation) in value.operations.withIndex()) {
        val evidence = original(operation)
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(evidence.scope == scope && evidence.originalActorId == value.view.ownerId &&
            evidence.operationId == operation && evidence.command == LocalBranchReportExportCodecV1.COMMAND &&
            evidence.committed && evidence.nativeOriginalProvenance == null && evidence.jobs.isEmpty() &&
            semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
        val request = validateLocalSalesSource { LocalBranchReportExportCodecV1.decodeRequest(evidence.normalized) }
        requireLocalSalesSource(LocalBranchReportExportCodecV1.encode(request) == evidence.normalized && request.exportId == id)
        val previous = reconstructed
        val next = when (request) {
            is LocalBranchReportExportRequestV1.Begin -> {
                requireLocalSalesSource(index == 0 && previous == null)
                beginReportExport(scope, evidence.originalActorId, operation, evidence.recordedAt, request)
            }
            is LocalBranchReportExportRequestV1.Finish -> {
                requireLocalSalesSource(index > 0 && previous != null)
                val before = checkNotNull(previous)
                requireLocalSalesSource(reportExportFinishRejection(before, evidence.originalActorId, request) == null)
                finishReportExport(before, evidence.originalActorId, operation, evidence.recordedAt, request)
            }
        }
        requireLocalSalesSource(evidence.result == LocalBranchReportExportCodecV1.result(
            LocalBranchReportExportResultV1.Recorded(next.view)))
        val write = evidence.aggregateWrites.singleOrNull()
        requireLocalSalesSource(write != null && write.key == key &&
            write.storageGeneration == next.view.revision.next() && write.schema == LocalBranchReportExportProfileV1.SCHEMA &&
            write.payload == reportExportStateBytes(next) && write.payloadDigest == sha256(write.payload.copyBytes()))
        if (index == 0) {
            val claim = evidence.claimChanges.singleOrNull()
            requireLocalSalesSource(claim != null && claim.action == RetainedClaimAction.ACQUIRE &&
                claim.key == LocalBranchReportExportProfileV1.owner(id) && claim.ownerId == next.view.ownerId)
        } else requireLocalSalesSource(evidence.claimChanges.isEmpty())
        reconstructed = next
    }
    requireLocalSalesSource(reconstructed == value && reportExportStateBytes(value) == row.payload)
    return LocalBranchReportExportCutV1(row, value)
}
