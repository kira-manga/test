package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import kotlin.coroutines.cancellation.CancellationException

/** Literal original native-import facts, not an employee authorization snapshot. The original
 * requirement and whole committed-Start certificate remain bounded bytes; source/raw payloads
 * are read from their genuine native BEFORE/retained originals rather than duplicated leases. */
internal class RuntimeNativeOriginalImportEvidenceV1(
    val scope: BranchScope,
    val producer: StreamedProducerIdentityV2,
    val canonicalRequirement: StoredBytes,
    val certificate: StoredBytes,
    val provenance: NativeOriginalJournalProvenanceV1,
    val nativeRecordDigest: PayloadDigest,
    val capturedChanges: Int,
) {
    fun validate() {
        historyShapeV2(capturedChanges == CAPTURED_CHANGES && canonicalRequirement.size in 1..8192 && certificate.size in 1..16384 &&
            provenance.scope == scope && producer.operationId == provenance.nativeOriginal.recordingOperationId &&
            producer.originalActorId == provenance.historicalStart.originalActorId && producer.command == provenance.historicalStart.command &&
            producer.semanticDigest == provenance.nativeOriginal.originalSemanticDigest &&
            sha256(canonicalRequirement.bytes()) == provenance.nativeOriginal.requirementDigest &&
            sha256(certificate.bytes()) == provenance.nativeOriginal.certificateDigest)
        val domain = NativeOriginalDomain.entries.singleOrNull { it.command == producer.command &&
            it.domainProfile == provenance.nativeOriginal.domainProfile } ?: malformed()
        val c = PhysicalCommittedStartProjectionV1.parse(certificate)
        val h = provenance.historicalStart
        historyShapeV2(c.scope == scope && c.originalActorId == h.originalActorId && c.intentOperationId == h.intentOperationId &&
            c.command == h.command && c.intentSemanticDigest == h.intentSemanticDigest && c.assignmentId == h.assignmentId &&
            c.masterDeviceId == h.masterDeviceId && c.epoch == h.epoch && c.sequence == h.sequence && c.journalHash == h.journalHash &&
            c.resultDigest == h.resultDigest && c.recordedAt == h.recordedAt && c.requirementBytes == canonicalRequirement &&
            c.domainProfile == domain.domainProfile && c.startKind == domain.startKind)
    }
    override fun toString(): String = "RuntimeNativeOriginalImportEvidenceV1([REDACTED])"
    companion object {
        const val PROFILE = "ukkera.runtime-native-original-import-evidence/v1"
        const val CAPTURED_CHANGES = 7
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}

/** Kernel/frame-only immutable capture, after the actual validated plan, lease and invocation.
 * It retains no lease, source owner, employee principal or success/settlement Boolean. */
internal class RuntimeNativeOriginalImportCapture private constructor(
    private val scope: BranchScope,
    private val producer: StreamedProducerIdentityV2,
    private val requirement: StoredBytes,
    private val certificate: StoredBytes,
    private val provenance: NativeOriginalJournalProvenanceV1,
) {
    fun evidence(nativeRecordDigest: PayloadDigest, changes: Int): RuntimeNativeOriginalImportEvidenceV1 =
        RuntimeNativeOriginalImportEvidenceV1(scope, producer, requirement, certificate, provenance, nativeRecordDigest, changes).also { it.validate() }

    companion object {
        fun capture(plan: NativeOriginalImportPlan, projection: NativeOriginalProjection,
            invocation: NativeOriginalIntakeInvocation, lease: NativeOriginalRetainedLease,
            provenance: NativeOriginalJournalProvenanceV1): RuntimeNativeOriginalImportCapture {
            val retained = lease.retained
            historyShapeV2(lease.invocation === invocation && !invocation.denied && plan.domain == projection.domain &&
                plan.operationId == projection.recordingOperationId && plan.originalActorId == projection.originalActorId &&
                plan.command == retained.command && plan.canonicalBody == retained.originalBody && plan.semanticDigest ==
                    semanticDigest(invocation.scope, plan.originalActorId, plan.command, plan.canonicalBody) &&
                projection.scope == invocation.scope && projection.clientSource.allocationId == invocation.locator.clientAllocationId &&
                provenance.currentWriter.invocationId == invocation.invocationId && provenance.scope == invocation.scope &&
                provenance.nativeOriginal.originalBodyDigest == sha256(retained.originalBody.bytes()) &&
                provenance.nativeOriginal.masterSource == projection.masterSource && provenance.nativeOriginal.clientSource == projection.clientSource)
            return RuntimeNativeOriginalImportCapture(invocation.scope,
                StreamedProducerIdentityV2(plan.operationId, plan.command, plan.originalActorId, plan.semanticDigest),
                StoredBytes(retained.canonicalRequirement.copyBytes()), StoredBytes(retained.certificate.copyBytes()), provenance)
        }
    }
    override fun toString(): String = "RuntimeNativeOriginalImportCapture([REDACTED])"
}

/** Distinct strict canonical binary facts profile. V1 employee/owned-decision capsules are
 * never upgraded or interpreted as native-original authority by this reader. */
internal object RuntimeNativeOriginalImportEvidenceCodec {
    private const val MAX_BYTES = 32 * 1024
    private val prefix = StockFrameWriterV2(128).run {
        text(RuntimeNativeOriginalImportEvidenceV1.PROFILE, 96); int(1); finish()
    }
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeNativeOriginalImportEvidenceV1): ByteArray = guarded {
        value.validate()
        val requirement = value.canonicalRequirement.copyBytes()
        val certificate = value.certificate.copyBytes()
        val provenance = NativeOriginalJournalProvenanceCodecV1.encode(value.provenance).copyBytes()
        try { StockFrameWriterV2(MAX_BYTES).run {
            text(RuntimeNativeOriginalImportEvidenceV1.PROFILE, 96); int(1); scope(value.scope); producer(value.producer)
            bytes(requirement, 8192); bytes(certificate, 16384); bytes(provenance, 4096)
            digest(value.nativeRecordDigest); int(value.capturedChanges); finish()
        } } finally { requirement.fill(0); certificate.fill(0); provenance.fill(0) }
    }
    fun decode(bytes: ByteArray): RuntimeNativeOriginalImportEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = StockFrameReaderV2(bytes, MAX_BYTES).run {
            historyShapeV2(text(96) == RuntimeNativeOriginalImportEvidenceV1.PROFILE && int() == 1)
            val scope = scope(); val producer = producer()
            val requirement = bytes(8192); val certificate = bytes(16384); val provenance = bytes(4096)
            try { RuntimeNativeOriginalImportEvidenceV1(scope, producer, StoredBytes(requirement), StoredBytes(certificate),
                NativeOriginalJournalProvenanceCodecV1.decode(StoredBytes(provenance)), digest(), int()).also { end(); it.validate() }
            } finally { requirement.fill(0); certificate.fill(0); provenance.fill(0) }
        }
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}
