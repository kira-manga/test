package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Closed retained producer, NOT a native/employee authority factory. Only the actual original
 * history-owner transaction calls encode after matching a SAME-gate lease, whole retained Start,
 * current sources/fence and the concrete domain interpreter's plan. No ordinary command is
 * converted to this tag, and no native tag is accepted as Start/resolution/accounting authority.
 *
 * The distinct tag is intentionally not the stock streamed-command v2 format. Employee v1
 * bytes, historical snapshot bytes and their decoder remain unchanged. The unchanged operation
 * table's authentication/policy columns mirror the HISTORICAL Start only; public employee-only
 * evidence accessors refuse them. The v2 provenance records the real current writer separately.
 */
internal object NativeOriginalRetainedCodecV1 {
    const val JOURNAL_TAG: String = "ukkera.journal/native-original-v2"
    const val MAX_ORIGINAL_BYTES: Int = 8_192
    const val MAX_RAW_BYTES: Int = 24_576
    const val MAX_PENDING_BYTES: Int = 8_192
    const val MAX_RESULT_BYTES: Int = 16_384

    fun requireDomain(command: CommandKey, profile: String): NativeOriginalDomain {
        val domain = NativeOriginalDomain.entries.singleOrNull { it.command == command && it.domainProfile == profile }
        requireNativeOriginalFormatDataV1(domain != null)
        return checkNotNull(domain)
    }

    private fun originalKey(domain: NativeOriginalDomain, operationId: OperationId): AggregateKey =
        AggregateKey(domain.originalKind, DomainId(operationId.value))
    private fun applicationKey(domain: NativeOriginalDomain, operationId: OperationId): AggregateKey =
        AggregateKey(domain.applicationKind, DomainId(operationId.value))

    fun requireChanges(domain: NativeOriginalDomain, operationId: OperationId, changes: List<AggregateChange>) {
        requireNativeOriginalFormatDataV1(changes.size == 2 && changes.all { it.expectedRevision == null })
        val raw = changes.singleOrNull { it.key == originalKey(domain, operationId) }
        val pending = changes.singleOrNull { it.key == applicationKey(domain, operationId) }
        requireNativeOriginalFormatDataV1(raw != null && raw.schema == domain.originalSchema && raw.payload.size in 1..MAX_RAW_BYTES &&
            pending != null && pending.schema == domain.applicationSchema && pending.payload.size in 1..MAX_PENDING_BYTES)
    }

    /** Complete envelope encoding; only the two immutable generation-one contributions exist. */
    fun encode(
        row: OperationRow,
        authority: AuthorityIdentity,
        previousHash: PayloadDigest,
        normalized: StoredBytes,
        changes: List<AggregateChange>,
        provenance: NativeOriginalJournalProvenanceV1,
    ): ByteArray {
        val operationId = OperationId(row.operationId)
        val domain = requireDomain(CommandKey(row.command, row.commandVersion), provenance.nativeOriginal.domainProfile)
        requireChanges(domain, operationId, changes)
        val writes = changes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value })).map {
            RetainedAggregateWrite(it.key, Revision(1), it.schema, it.payload, sha256(it.payload.bytes()))
        }
        val encodedProvenance = NativeOriginalJournalProvenanceCodecV1.encode(provenance)
        requireNativeOriginalFormatDataV1(row.scope == provenance.scope.storageKey() &&
            row.epoch == authority.epoch.value && sha256(row.result).value == row.resultHash)
        validate(encodedProvenance, provenance.scope, operationId, DomainId(row.actorId),
            CommandKey(row.command, row.commandVersion), authority.assignmentId, authority.epoch,
            JournalSequence(row.sequence), UtcInstant.parseCanonical(row.committedAt), DomainId(row.authenticationId),
            Revision(row.policyRevision), PayloadDigest(row.digest), row.outcome, normalized,
            StoredBytes(row.result), writes, emptyList(), emptyList())
        val effects = writes.map { write -> frame(listOf(write.key.kind, write.key.id.value,
            write.storageGeneration.value.toString(), write.schema).map(String::encodeToByteArray) + write.payload.copyBytes()) }
        return frame(listOf(JOURNAL_TAG, row.scope, authority.assignmentId.value, authority.epoch.value.toString(),
            row.sequence.toString(), row.operationId, row.actorId, row.command, row.commandVersion.toString(),
            row.authenticationId, row.policyRevision.toString(), row.digest, row.outcome, row.committedAt,
            row.resultHash, previousHash.value).map(String::encodeToByteArray) +
            listOf(normalized.copyBytes(), row.result.copyOf(), frame(effects), frame(emptyList()), encodedProvenance.copyBytes()))
    }

    /**
     * Called ONLY after generic metadata-first whole operation/journal validation: pair fields,
     * bounded canonical framing, result/semantic/envelope hashes, complete sorted effects/jobs.
     * This validates the additional CLOSED producer tag, not current native/history custody.
     * Full domain body/Start/current-application semantics remain the actual domain verifier's
     * responsibility; no duplicated retail/FOOD JSON parser or financial reducer is introduced here.
     */
    fun validate(
        encoded: StoredBytes,
        scope: BranchScope,
        operationId: OperationId,
        actor: DomainId,
        command: CommandKey,
        assignment: DomainId,
        epoch: Revision,
        sequence: JournalSequence,
        recordedAt: UtcInstant,
        authentication: DomainId,
        policyRevision: Revision,
        digest: PayloadDigest,
        code: String,
        normalized: StoredBytes,
        result: StoredBytes,
        writes: List<RetainedAggregateWrite>,
        claims: List<RetainedClaimChange>,
        jobs: List<RetainedJobIntent>,
    ): NativeOriginalJournalProvenanceV1 {
        val provenance = NativeOriginalJournalProvenanceCodecV1.decode(encoded)
        requireNativeOriginalFormatDataV1(NativeOriginalJournalProvenanceCodecV1.encode(provenance) == encoded)
        val historical = provenance.historicalStart
        val native = provenance.nativeOriginal
        val writer = provenance.currentWriter
        val domain = requireDomain(command, native.domainProfile)
        requireNativeOriginalFormatDataV1(code == "committed" &&
            normalized.size in 1..MAX_ORIGINAL_BYTES && result.size in 1..MAX_RESULT_BYTES &&
            claims.isEmpty() && jobs.isEmpty() && writes.size == 2 &&
            writes.all { it.storageGeneration == Revision(1) && sha256(it.payload.bytes()) == it.payloadDigest })
        requireChanges(domain, operationId, writes.map { AggregateChange(it.key, null, it.schema, it.payload) })
        requireNativeOriginalFormatDataV1(provenance.scope == scope && historical.originalActorId == actor &&
            historical.intentOperationId != operationId && historical.command == command &&
            historical.authenticationId == authentication && historical.policyRevision == policyRevision &&
            native.recordingOperationId == operationId && native.originalBodyDigest == sha256(normalized.bytes()) &&
            native.originalSemanticDigest == digest && semanticDigest(scope, actor, command, normalized) == digest &&
            writer.assignmentId == assignment && writer.epoch == epoch &&
            writer.runtimeProfile == SelfOwnedNativeHistoryWireV2.PROFILE &&
            writer.masterDeviceId == native.masterSource.deviceId &&
            writer.installationId == native.masterSource.installationId &&
            historical.masterDeviceId == native.masterSource.deviceId &&
            native.masterSource.role == NativeOriginalJournalProvenanceV1.SourceRole.MASTER &&
            native.clientSource.role == NativeOriginalJournalProvenanceV1.SourceRole.CLIENT &&
            native.masterSource.allocationId != native.clientSource.allocationId)
        // Cross-authority wall clocks are not causality. Within the SAME authority journal,
        // however, an original cannot precede or equal the Start that reserved its operation.
        if (historical.assignmentId == assignment && historical.epoch == epoch) {
            requireNativeOriginalFormatDataV1(historical.sequence.value < sequence.value)
        }
        requireEvidenceV1Time(recordedAt)
        return provenance
    }
}
