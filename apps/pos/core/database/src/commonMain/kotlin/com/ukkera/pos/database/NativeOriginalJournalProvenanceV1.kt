package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Candidate historical/native/writer DATA, not verified provenance or current authority.
 * Construction, decoding and matching digests prove neither a native owner nor complete history.
 * There is no principal, lease, retained-command/SQL-row factory, original BLOB or v2 registration.
 */
public data class NativeOriginalJournalProvenanceV1(
    public val scope: BranchScope,
    public val historicalStart: HistoricalStart,
    public val nativeOriginal: NativeOriginal,
    public val currentWriter: CurrentWriter,
    public val runtimeParent: RuntimeParent,
) {
    /** Authentication and policy here are historical Start attributes, never current authorization. */
    public data class HistoricalStart(
        public val originalActorId: DomainId,
        public val intentOperationId: OperationId,
        public val command: CommandKey,
        public val intentSemanticDigest: PayloadDigest,
        public val authenticationId: DomainId,
        public val policyRevision: Revision,
        public val assignmentId: DomainId,
        public val masterDeviceId: DomainId,
        public val epoch: Revision,
        public val sequence: JournalSequence,
        public val journalHash: PayloadDigest,
        public val resultDigest: PayloadDigest,
        public val recordedAt: UtcInstant,
    ) {
        init {
            requireNativeOriginalFormatDataV1(sequence.value > 0)
            requireNativeOriginalFormatDataV1(recordedAt.canonicalString().length in 20..30)
        }

        override public fun toString(): String = "NativeOriginalHistoricalStartV1([REDACTED])"
    }

    /** Digests and source attributes only. No certificate, requirement or original body is embedded. */
    public data class NativeOriginal(
        public val domainProfile: String,
        public val recordingOperationId: OperationId,
        public val requirementDigest: PayloadDigest,
        public val certificateDigest: PayloadDigest,
        public val originalBodyDigest: PayloadDigest,
        public val originalSemanticDigest: PayloadDigest,
        public val masterSource: Source,
        public val clientSource: Source,
    ) {
        init {
            requireProtocolName(domainProfile)
            requireNativeOriginalFormatDataV1(masterSource.role == SourceRole.MASTER)
            requireNativeOriginalFormatDataV1(clientSource.role == SourceRole.CLIENT)
            requireNativeOriginalFormatDataV1(masterSource.allocationId != clientSource.allocationId)
        }

        override public fun toString(): String = "NativeOriginalNativeProvenanceV1([REDACTED])"
    }

    public enum class SourceRole { MASTER, CLIENT }

    /**
     * Candidate immutable source attributes, not a source receipt or proof of current custody.
     * The implied key/schema are physical.journal-source/<allocationId>, physical.journal-source/v1,
     * generation 1. Slot 0..127 is this draft format's scalar bound, not geometry qualification.
     */
    public data class Source(
        public val role: SourceRole,
        public val installationId: DomainId,
        public val deviceId: DomainId,
        public val catalogueId: DomainId,
        public val keyId: DomainId,
        public val allocationId: DomainId,
        public val nativeSlotIndex: Int,
        public val sourcePayloadDigest: PayloadDigest,
        public val headerDigest: PayloadDigest,
        public val allocationDigest: PayloadDigest,
        public val nativeIdentityDigest: PayloadDigest,
    ) {
        init { requireNativeOriginalFormatDataV1(nativeSlotIndex in 0..127) }

        override public fun toString(): String = "NativeOriginalSourceProvenanceV1([REDACTED])"
    }

    /** These claimed current-owner attributes cannot attest that a process/owner actually exists. */
    public data class CurrentWriter(
        public val installationId: DomainId,
        public val masterDeviceId: DomainId,
        public val storeIncarnationId: DomainId,
        public val serviceIncarnationId: DomainId,
        public val invocationId: DomainId,
        public val assignmentId: DomainId,
        public val epoch: Revision,
        public val runtimeProfile: String,
        public val runtimeBindingCommitment: PayloadDigest,
    ) {
        init { requireProtocolName(runtimeProfile) }

        override public fun toString(): String = "NativeOriginalWriterProvenanceV1([REDACTED])"
    }

    /**
     * Candidate existing complete-parent attributes; positive sequence is syntax, not completeness.
     * This is not the command-journal head. No current child hash or precommit success flag is stored.
     */
    public data class RuntimeParent(
        public val sequence: JournalSequence,
        public val frameHash: PayloadDigest,
    ) {
        init { requireNativeOriginalFormatDataV1(sequence.value > 0) }

        override public fun toString(): String = "NativeOriginalRuntimeParentV1([REDACTED])"
    }

    override public fun toString(): String = "NativeOriginalJournalProvenanceV1([REDACTED])"
}

internal fun requireNativeOriginalFormatDataV1(condition: Boolean) {
    if (!condition) throw IllegalArgumentException("Invalid native original format data")
}
