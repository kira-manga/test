package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One complete source-owned DATA transcript and its index. Earlier originals are
 * rejoined by a separate chronological scanner, not reconstructed from a joined flag.
 * Every loan retains its genuine business/authorization owner until the caller closes it. */
internal class RuntimeHistoryColdReplay(
    val transcript: RuntimeHistoryColdTranscript,
    val index: RuntimeHistoryColdIndex,
    private val producer: RuntimeHistoryBusinessProducer,
    private val stockCodec: StockCanonicalProjectionV2,
) : RuntimeHistoryColdOriginalRejoin {
    val session: SelfOwnedNativeHistoryReadSession get() = transcript.session

    init { historyShapeV2(index.session === session) }

    override suspend fun byOperation(requestingJoin: RuntimeBusinessHistoryJoin,
        before: RuntimeBusinessHistoryJoin.CommandCapture, operationId: OperationId): RuntimeHistoryColdOriginalLoan? {
        requestingJoin.requireColdRejoinRequest(before)
        val sequence = index.sequenceForOperation(operationId) ?: return null
        if (sequence >= before.header.sequence) return null
        val loan = bySequence(requestingJoin, before, sequence) ?: malformed()
        return try {
            historyShapeV2(loan.capture.producer.operationId == operationId)
            loan
        } catch (failure: Throwable) { closeAfter(failure) { loan.close() }; throw failure }
    }

    override suspend fun bySequence(requestingJoin: RuntimeBusinessHistoryJoin,
        before: RuntimeBusinessHistoryJoin.CommandCapture, sequence: Long): RuntimeHistoryColdOriginalLoan? {
        requestingJoin.requireColdRejoinRequest(before)
        historyShapeV2(sequence > 1L && sequence < before.header.sequence && before.scope ==
            session.original.record.allocation.coordinates.scope)
        val range = index.eventRange(sequence) ?: return null
        val target = readTarget(range)
        var transferred = false
        var failure: Throwable? = null
        try {
            historyShapeV2(target.header.sequence == sequence && target.header.commandEpoch == before.header.commandEpoch &&
                target.header.assignmentId == before.header.assignmentId && target.header.scope == before.header.scope)
            val loan = RuntimeHistoryScanner.replayEarlierOrdinary(this, producer, stockCodec, target)
            transferred = true
            return loan
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { if (!transferred) closeAfter(failure) { target.close() } }
    }

    override suspend fun byCurrentFoodSequence(requestingJoin: RuntimeBusinessHistoryJoin,
        sequence: Long): RuntimeHistoryColdOriginalLoan? {
        val head = requestingJoin.requireColdCurrentFoodRejoin(this, session)
        historyShapeV2(sequence > 1L && sequence <= head.historySequence)
        val range = index.eventRange(sequence) ?: return null
        val target = readTarget(range)
        var transferred = false
        var failure: Throwable? = null
        try {
            val header = target.header
            val coordinates = session.original.record.allocation.coordinates
            historyShapeV2(header.sequence == sequence && header.scope == coordinates.scope.storageKey() &&
                header.assignmentId == coordinates.assignmentId && header.commandEpoch == head.commandEpoch &&
                header.installationGenesisDigest == head.installationGenesisDigest &&
                header.commandSequence <= head.commandSequence)
            if (sequence == head.historySequence) historyShapeV2(
                NativeHistoryRecordedHeadV2(header.sequence, target.eventDigest, header.installationGenesisDigest,
                    header.commandEpoch, header.commandSequence, header.commandDigest,
                    header.roleCommitment, header.recoveryCommitment) == head)
            // Replay retains the genuine authorization/source owner for this loan.
            // Its earlier-original path cannot publish main coverage or admission.
            val loan = RuntimeHistoryScanner.replayEarlierOrdinary(this, producer, stockCodec, target)
            transferred = true
            return loan
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { if (!transferred) closeAfter(failure) { target.close() } }
    }

    /** Decoded target coordinates only. The later scanner consumes the actual C1,
     * native enrollment seeds and chronological authorization events before using it. */
    private suspend fun readTarget(range: RuntimeHistoryColdIndex.EventRange): RuntimeHistoryColdReplayTarget {
        val event = transcript.withItem(range.first) { item ->
            historyShapeV2(item.metadata.pass == NativeHistoryReadPassV2.EVENTS)
            ((item as? RuntimeHistoryReadItem.Row)?.row ?: malformed()).event()
        }
        val header = try { RuntimeHistoryCodecV2.decodeHeader(event.header) }
        finally { event.header.fill(0) }
        if (header.purpose != RuntimeWritePurposeV2.COMMAND_APPLY) unavailable()
        val bytes = RuntimeHistoryColdScanMemory.eventBytes(header)
        transcript.records.memory.reserve(bytes)
        var retained = false
        var capsule: RuntimeHistoryCapsule? = null
        var nativeOriginal: RuntimeNativeOriginalImportEvidenceV1? = null
        var cursor: RuntimeHistoryColdTranscript.Cursor? = null
        var failure: Throwable? = null
        try {
            val actual = RuntimeHistoryCapsule(header).also { capsule = it }
            val rows = transcript.openCursor(range.first + 1, range.afterLast).also { cursor = it }
            while (true) {
                val item = rows.next() ?: break
                if (item is RuntimeHistoryReadItem.Row && item.metadata.pass == NativeHistoryReadPassV2.EVENT_CHUNKS) {
                    val chunk = item.row.chunk()
                    try { actual.append(chunk) } finally { chunk.bytes.fill(0) }
                }
            }
            actual.finish()
            val evidence = when {
                RuntimeCommandAuthorizationEvidenceCodec.hasProfile(actual.facts) ->
                    RuntimeCommandAuthorizationEvidenceCodec.decode(actual.facts).also {
                        if (it.actionApprovalId != null) unavailable()
                    }
                RuntimeNativeOriginalImportEvidenceCodec.hasProfile(actual.facts) -> {
                    val decoded = RuntimeNativeOriginalImportEvidenceCodec.decode(actual.facts)
                    nativeOriginal = decoded
                    if (!RuntimeBusinessHistoryJoin.supportsColdNativeOriginal(decoded)) unavailable()
                    null
                }
                else -> unavailable()
            }
            // Dispose input loans before transferring the target reservation. If
            // cursor cleanup fails, no unreachable target may retain that budget.
            rows.close(); cursor = null
            actual.clear(); capsule = null
            return RuntimeHistoryColdReplayTarget(header, PayloadDigest(event.eventDigest), evidence, range.afterLast,
                transcript.records.memory, bytes, nativeOriginal).also { retained = true }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            try { closeAfter(failure) { cursor?.close() } }
            finally {
                capsule?.clear()
                if (!retained) {
                    nativeOriginal?.canonicalRequirement?.bytes()?.fill(0)
                    nativeOriginal?.certificate?.bytes()?.fill(0)
                    transcript.records.memory.release(bytes)
                }
            }
        }
    }

    private suspend fun closeAfter(primary: Throwable?, close: suspend () -> Unit) = withContext(NonCancellable) {
        try { close() } catch (failure: Throwable) {
            if (primary == null) throw failure
            if (primary !== failure) primary.addSuppressed(failure)
        }
    }

    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    private fun unavailable(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.UNRESOLVED_PROTOCOL)
}

/** A bounded owned DATA target, not a historical authorization or native read proof.
 * Ordinary and native-original facts remain distinct. The latter can be authorized
 * only by the scanner's genuinely rejoined earlier Start and original native sources. */
internal class RuntimeHistoryColdReplayTarget(
    val header: RuntimeHistoryEventHeaderV2,
    val eventDigest: PayloadDigest,
    val evidence: RuntimeCommandAuthorizationEvidenceV1?,
    val afterLast: Long,
    private val memory: RuntimeHistoryColdMemory,
    private val bytes: Long,
    val nativeOriginal: RuntimeNativeOriginalImportEvidenceV1? = null,
) {
    private var closed = false

    init { historyShapeV2((evidence == null) != (nativeOriginal == null)) }

    fun select(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        decoded: RuntimeCommandAuthorizationEvidenceV1): RuntimeCommandAuthorizationEvidenceV1 {
        historyShapeV2(!closed && nativeOriginal == null && this.header == header && eventDigest == digest && evidence == decoded)
        return checkNotNull(evidence)
    }

    /** Compare exact inert facts without fabricating an employee authorization object.
     * The caller retains its own decoded bytes; closing this target cannot clear a
     * scanner event or its independently copied command capture. */
    fun selectNative(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        decoded: RuntimeNativeOriginalImportEvidenceV1): RuntimeNativeOriginalImportEvidenceV1 {
        val selected = nativeOriginal
        historyShapeV2(!closed && evidence == null && selected != null && this.header == header && eventDigest == digest)
        checkNotNull(selected)
        historyShapeV2(selected.scope == decoded.scope && selected.producer == decoded.producer &&
            selected.canonicalRequirement == decoded.canonicalRequirement && selected.certificate == decoded.certificate &&
            selected.provenance == decoded.provenance && selected.nativeRecordDigest == decoded.nativeRecordDigest &&
            selected.capturedChanges == decoded.capturedChanges)
        return decoded
    }

    fun close() {
        if (closed) return
        closed = true
        nativeOriginal?.canonicalRequirement?.bytes()?.fill(0)
        nativeOriginal?.certificate?.bytes()?.fill(0)
        memory.release(bytes)
    }
}
