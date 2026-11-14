package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Explicit V2 history protocol. V1 draft domains and N-only P2 records are not reinterpreted.
 * These are canonical DATA; only the original native owner authenticates/retains a record.
 * P3 is the sole encoder of A, complete D7, attempt, approvals, Ginstall7, R7 and ACK.
 */
public object SelfOwnedNativeHistoryWireV2 {
    public const val FORMAT: Int = 2
    public const val PROFILE: String = "ukkera.runtime-history/original-local/v2"
    public const val MAX_ANCHOR_BYTES: Int = 32 * 1024 * 1024
    public const val MAX_CAPSULE_BYTES: Int = 2 * 1024 * 1024
    public const val MAX_RESOURCES: Int = 16_384
    public const val MAX_RETIRED_ATTEMPTS: Int = 4096
    public const val MAX_EVENTS: Long = 10_000_000L
    public const val ENVELOPE_OVERHEAD: Int = 1024
    internal const val MAX_ATTEMPT_FACTS_BYTES: Int = 1024
    internal const val MAX_RETIREMENT_EVIDENCE_BYTES: Int = 4096

    // r003 adds an authenticated, durable pre-attempt reservation. The earlier r002 body
    // has no such promise and is NOT silently adopted or rewritten by an original reader.
    internal const val CAPACITY_RECORD_DOMAIN: String = "anchor-record-capacity/v2"
    internal const val FINAL_DECISION_RECORD_DOMAIN: String = "anchor-record-final-decision/v3"
    public const val FINAL_DECISION_RECORD_FORMAT: Int = 3
    internal const val PROTECTED_FINAL_RECORD_DOMAIN: String = "anchor-record-protected-final/v4"
    public const val PROTECTED_FINAL_RECORD_FORMAT: Int = 4
    internal const val BUSINESS_PUBLICATION_RECORD_DOMAIN: String = "anchor-record-business-publication/v5"
    public const val BUSINESS_PUBLICATION_RECORD_FORMAT: Int = 5
    internal const val MASTER_SDK_ORIGINAL_RECORD_DOMAIN: String = "anchor-record-master-sdk-original/v6"
    public const val MASTER_SDK_ORIGINAL_RECORD_FORMAT: Int = 6
    public const val MAX_FINAL_DEADLINE_DECISIONS: Int = NativeHistoryFinalDecisionCapacity.MAX_DECISIONS

    private fun recordDomain(format: Int): String = when (format) {
        FORMAT -> CAPACITY_RECORD_DOMAIN
        FINAL_DECISION_RECORD_FORMAT -> FINAL_DECISION_RECORD_DOMAIN
        PROTECTED_FINAL_RECORD_FORMAT -> PROTECTED_FINAL_RECORD_DOMAIN
        BUSINESS_PUBLICATION_RECORD_FORMAT -> BUSINESS_PUBLICATION_RECORD_DOMAIN
        MASTER_SDK_ORIGINAL_RECORD_FORMAT -> MASTER_SDK_ORIGINAL_RECORD_DOMAIN
        else -> malformed()
    }

    private fun recordFormat(bytes: ByteArray): Int {
        require(bytes.size <= MAX_ANCHOR_BYTES)
        val reader = NativeHistoryReaderV2(bytes)
        return try {
            require(reader.string(64) == PROFILE)
            val domain = reader.string(64); val format = reader.int()
            require(domain == recordDomain(format)); format
        } finally { reader.clear() }
    }

    public fun encodeRecord(value: NativeHistoryAnchorRecordV2): ByteArray = write(recordDomain(value.recordFormat), MAX_ANCHOR_BYTES, value.recordFormat) {
        ownedField(LocalBranchInstallationWireV2.encodeAllocation(value.allocation))
        long(value.generation); long(value.denialGeneration)
        bool(value.definition != null); value.definition?.let { ownedField(it.copyWireBytes()) }
        bool(value.nativeDatabase != null); value.nativeDatabase?.let { databaseIdentity(it) }
        bool(value.parent != null); value.parent?.let { head(it) }
        bool(value.pending != null); value.pending?.let {
            head(it.child); ownedField(encodeIntent(it.originalIntent))
            if (value.recordFormat >= FINAL_DECISION_RECORD_FORMAT) {
                bool(it.finalDeadline != null); it.finalDeadline?.let { deadline -> finalDeclaration(deadline) }
            }
            if (value.recordFormat >= PROTECTED_FINAL_RECORD_FORMAT) {
                bool(it.protectedFinal != null); it.protectedFinal?.let { declaration -> protectedDeclaration(declaration) }
            }
            if (value.recordFormat >= BUSINESS_PUBLICATION_RECORD_FORMAT) {
                bool(it.businessPublications != null)
                it.businessPublications?.let { proof -> ownedField(NativeBusinessPublicationObservationWire.encodeProof(proof)) }
            }
        }
        bool(value.roleIntent != null); value.roleIntent?.let {
            int(it.role.tag); long(it.generation); id(it.requestId); digest(it.requestDigest); digest(it.authorizationDigest)
        }
        bool(value.attempt != null); value.attempt?.let { attempt(it) }
        int(value.retiredAttempts.size)
        value.retiredAttempts.forEach {
            ownedField(LocalBranchInstallationWireV2.encodeAttemptFacts(it.facts)); int(it.fate.tag)
            long(it.denialGeneration); ownedField(it.copyEvidenceBytes()); nullableDigest(it.issuerRetirementDigest)
        }
        bool(value.firstChild != null); value.firstChild?.let { child(it) }
        bool(value.receipt != null); value.receipt?.let { ownedField(it.copyWireBytes()) }
        bool(value.issuerAcknowledgement != null); value.issuerAcknowledgement?.let { ownedField(it.copyWireBytes()) }
        bool(value.installationReservation != null); value.installationReservation?.let {
            long(it.nextAttemptSequence); digest(it.definitionDigest)
        }
        if (value.recordFormat >= FINAL_DECISION_RECORD_FORMAT) {
            int(value.finalDeadlineDecisions.size)
            value.finalDeadlineDecisions.forEach { finalDecision(it) }
        }
        if (value.recordFormat >= PROTECTED_FINAL_RECORD_FORMAT) {
            int(value.protectedFinalDecisions.size)
            value.protectedFinalDecisions.forEach { protectedDecision(it) }
        }
        if (value.recordFormat >= BUSINESS_PUBLICATION_RECORD_FORMAT) {
            int(value.businessPublicationObservations.size)
            value.businessPublicationObservations.forEach { ownedField(NativeBusinessPublicationObservationWire.encodeProof(it)) }
        }
        if (value.recordFormat >= MASTER_SDK_ORIGINAL_RECORD_FORMAT) {
            ownedField(requireNotNull(value.masterSdkOriginal).copyWireBytes())
        }
    }.also { bytes ->
        try { NativeHistoryLifecycleCapacityV2.requireEncodedCapacity(value, bytes.size) }
        catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    public fun decodeRecord(bytes: ByteArray): NativeHistoryAnchorRecordV2 {
        val format = recordFormat(bytes)
        return decodeRecord(bytes, format)
    }

    private fun decodeRecord(bytes: ByteArray, format: Int): NativeHistoryAnchorRecordV2 =
        read(bytes, recordDomain(format), MAX_ANCHOR_BYTES, format = format) {
        val allocation = decodedField(LocalBranchInstallationWireV2.MAXIMUM_ALLOCATION_BYTES) {
            LocalBranchInstallationWireV2.decodeAllocation(it) ?: malformed()
        }
        val generation = long(); val denialGeneration = long()
        val definition = if (bool()) decodedField(LocalBranchInstallationWireV2.MAXIMUM_DEFINITION_BYTES) {
            LocalBranchInstallationWireV2.decodeDefinition(it) ?: malformed()
        } else null
        val database = if (bool()) databaseIdentity() else null
        val parent = if (bool()) head() else null
        val pending = if (bool()) {
            val pendingChild = head(); val intent = decodedField(MAX_CAPSULE_BYTES, ::decodeIntent)
            val deadline = if (format >= FINAL_DECISION_RECORD_FORMAT && bool()) finalDeclaration() else null
            val protectedFinal = if (format >= PROTECTED_FINAL_RECORD_FORMAT && bool()) protectedDeclaration() else null
            val publications = if (format >= BUSINESS_PUBLICATION_RECORD_FORMAT && bool())
                decodedField(NativeBusinessPublicationObservationWire.MAX_PROOF_BYTES, NativeBusinessPublicationObservationWire::decodeProof)
                else null
            NativeHistoryPendingV2(pendingChild, intent, deadline, protectedFinal, publications)
        } else null
        val role = if (bool()) NativeHistoryRoleIntentV2(NativeHistoryDesiredRoleV2.fromTag(int()), long(), id(), digest(), digest()) else null
        val attempt = if (bool()) attempt(definition ?: malformed()) else null
        val count = int(); require(count in 0..MAX_RETIRED_ATTEMPTS)
        val retired = List(count) {
            val facts = decodedField(MAX_ATTEMPT_FACTS_BYTES) { LocalBranchInstallationWireV2.decodeAttemptFacts(it) ?: malformed() }
            val fate = NativeHistoryRetirementFateV2.fromTag(int()); val denial = long()
            decodedField(MAX_RETIREMENT_EVIDENCE_BYTES) { NativeHistoryRetiredAttemptV2(facts, fate, denial, it, nullableDigest()) }
        }
        val child = if (bool()) child() else null
        val receipt = if (bool()) decodedField(LocalBranchInstallationWireV2.MAXIMUM_RECEIPT_BYTES) {
            LocalBranchInstallationWireV2.decodeReceipt(it) ?: malformed()
        } else null
        val ack = if (bool()) {
            val a = attempt ?: malformed(); val d = definition ?: malformed()
            decodedField(LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES) {
                LocalBranchInstallationWireV2.decodeSignedAcknowledgement(it,
                    d, a.facts, a.approvals ?: malformed(), receipt ?: malformed(), child ?: malformed()) ?: malformed()
            }
        } else null
        val reservation = if (bool()) NativeHistoryInstallationReservationV2(long(), digest()) else null
        val decisions = if (format >= FINAL_DECISION_RECORD_FORMAT) {
            val decisionCount = int(); require(decisionCount in 0..MAX_FINAL_DEADLINE_DECISIONS)
            List(decisionCount) { finalDecision() }
        } else emptyList()
        val protectedDecisions = if (format >= PROTECTED_FINAL_RECORD_FORMAT) {
            val decisionCount = int(); require(decisionCount in 0..MAX_FINAL_DEADLINE_DECISIONS - decisions.size)
            List(decisionCount) { protectedDecision() }
        } else emptyList()
        val publicationObservations = if (format >= BUSINESS_PUBLICATION_RECORD_FORMAT) {
            val observationCount = int(); require(observationCount in 0..NativeBusinessPublicationObservationWire.MAX_OBSERVATIONS)
            List(observationCount) {
                decodedField(NativeBusinessPublicationObservationWire.MAX_PROOF_BYTES, NativeBusinessPublicationObservationWire::decodeProof)
            }
        } else emptyList()
        val masterSdkOriginal = if (format >= MASTER_SDK_ORIGINAL_RECORD_FORMAT)
            decodedField(LocalMasterSdkOriginal.MAXIMUM_ORIGINAL_BYTES, LocalMasterSdkOriginal::decode) else null
        NativeHistoryAnchorRecordV2(allocation, generation, denialGeneration, definition, database, parent, pending,
            role, attempt, retired, child, receipt, ack, reservation, format, decisions, protectedDecisions, publicationObservations,
            masterSdkOriginal)
    }.also { value ->
        val canonical = encodeRecord(value)
        try { require(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
    }

    /** Separate actual original-key signature purpose; never the P2 N-only state signature. */
    public fun envelopeAad(allocation: OriginalAllocationCommitmentV2): ByteArray = write("anchor-aad", 2048) {
        string(PROFILE); digest(allocation.digest); digest(allocation.originalNativeBindingDigest)
        digest(allocation.originalTargetKeyDigest); id(allocation.coordinates.storageInstanceId)
    }
    public fun envelopeSignatureMessage(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        write("original-target-history-envelope", MAX_ANCHOR_BYTES + ENVELOPE_OVERHEAD) {
            require(nonce.size == 12 && ciphertext.size in 17..MAX_ANCHOR_BYTES + 16)
            field(aad); field(nonce); field(ciphertext)
        }
    public fun encodeEnvelope(nonce: ByteArray, ciphertext: ByteArray, signature: ByteArray): ByteArray =
        write("encrypted-anchor", MAX_ANCHOR_BYTES + ENVELOPE_OVERHEAD) {
            require(nonce.size == 12 && ciphertext.size in 17..MAX_ANCHOR_BYTES + 16 && signature.size == 64)
            field(nonce); field(ciphertext); field(signature)
        }
    internal fun decodeEnvelope(bytes: ByteArray): NativeHistoryEnvelopeV2 =
        read(bytes, "encrypted-anchor", MAX_ANCHOR_BYTES + ENVELOPE_OVERHEAD, discard = { it.clear() }) {
            val fields = mutableListOf<ByteArray>()
            var transferred = false
            try {
                val nonce = field(12).also { fields += it }
                val ciphertext = field(MAX_ANCHOR_BYTES + 16).also { fields += it }
                val signature = field(64).also { fields += it }
                require(nonce.size == 12 && signature.size == 64 && ciphertext.size >= 17)
                NativeHistoryEnvelopeV2(nonce, ciphertext, signature).also { transferred = true }
            } finally { if (!transferred) fields.forEach { it.fill(0) } }
        }

    public fun encodeIntent(value: NativeHistoryOriginalIntentV2): ByteArray = write("original-intent", MAX_CAPSULE_BYTES) {
        int(value.purposeTag); id(value.originalRequestId); digest(value.originalRequestDigest)
        nullableId(value.originalOperationId); nullableId(value.originalActorId)
        digest(value.dependenciesDigest); digest(value.originalFactsDigest); int(value.resources.size)
        value.resources.forEach {
            int(it.selectorTag); int(it.kind?.tag ?: 0); nullableId(it.resourceId)
            bool(it.preserveHold); digest(it.originalEvidenceDigest)
        }
    }
    public fun decodeIntent(bytes: ByteArray): NativeHistoryOriginalIntentV2 = read(bytes, "original-intent", MAX_CAPSULE_BYTES) {
        val purpose = int(); val request = id(); val requestDigest = digest(); val operation = nullableId(); val actor = nullableId()
        val dependencies = digest(); val facts = digest(); val count = int(); require(count in 0..MAX_RESOURCES)
        NativeHistoryOriginalIntentV2(purpose, request, requestDigest, operation, actor, dependencies, facts, List(count) {
            val selector = int(); val kind = int()
            NativeHistoryOriginalResourceV2(selector, if (kind == 0) null else NativeHistoryResourceKindV2.fromTag(kind),
                nullableId(), bool(), digest())
        })
    }

    internal fun retirementEvidence(record: NativeHistoryAnchorRecordV2, facts: LocalInstallationAttemptFactsV2,
        fate: NativeHistoryRetirementFateV2, denialGeneration: Long, finalCut: PayloadDigest): ByteArray =
        retirementEvidence(record.allocation,requireNotNull(record.definition),facts,fate,denialGeneration,finalCut)

    private fun retirementEvidence(allocation: OriginalAllocationCommitmentV2, definition: LocalBranchInstallationSnapshotV2,
        facts: LocalInstallationAttemptFactsV2, fate: NativeHistoryRetirementFateV2, denialGeneration: Long,
        finalCut: PayloadDigest): ByteArray = write("original-attempt-retirement", MAX_RETIREMENT_EVIDENCE_BYTES) {
        digest(allocation.digest); digest(definition.definitionDigest)
        id(definition.definition.originalRequestId); ownedField(LocalBranchInstallationWireV2.encodeAttemptFacts(facts))
        int(fate.tag); long(denialGeneration); digest(finalCut)
    }

    /** Full retained evidence DATA crosswalk, not an admission/absence/cleanup certificate. */
    internal fun requireOriginalRetirementEvidence(allocation: OriginalAllocationCommitmentV2,
        definition: LocalBranchInstallationSnapshotV2, retired: NativeHistoryRetiredAttemptV2) {
        val bytes = retired.copyEvidenceBytes()
        try {
            val finalCut = read(bytes,"original-attempt-retirement",MAX_RETIREMENT_EVIDENCE_BYTES) {
                require(digest() == allocation.digest && digest() == definition.definitionDigest &&
                    id() == definition.definition.originalRequestId)
                val facts = decodedField(MAX_ATTEMPT_FACTS_BYTES) { LocalBranchInstallationWireV2.decodeAttemptFacts(it) ?: malformed() }
                require(facts == retired.facts && int() == retired.fate.tag && long() == retired.denialGeneration)
                digest()
            }
            val canonical = retirementEvidence(allocation,definition,retired.facts,retired.fate,retired.denialGeneration,finalCut)
            try { require(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
        } finally { bytes.fill(0) }
    }

    private fun NativeHistoryWriterV2.attempt(value: NativeHistoryAttemptRecordV2) {
        ownedField(LocalBranchInstallationWireV2.encodeAttemptFacts(value.facts)); int(value.phase.tag)
        long(value.denialGeneration); nullableDigest(value.issuerClaimDigest)
        bool(value.humanApproval != null); value.humanApproval?.let { ownedField(it.copyWireBytes()) }
        bool(value.targetApproval != null); value.targetApproval?.let { ownedField(it.copyWireBytes()) }
        nullableDigest(value.submissionDigest)
    }
    private fun NativeHistoryReaderV2.attempt(snapshot: LocalBranchInstallationSnapshotV2): NativeHistoryAttemptRecordV2 {
        val facts = decodedField(MAX_ATTEMPT_FACTS_BYTES) { LocalBranchInstallationWireV2.decodeAttemptFacts(it) ?: malformed() }
        val phase = NativeHistoryAttemptPhaseV2.fromTag(int()); val denial = long(); val claim = nullableDigest()
        val human = if (bool()) decodedField(LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES) {
            LocalBranchInstallationWireV2.decodeSignedHumanApproval(it, snapshot, facts) ?: malformed()
        } else null
        val target = if (bool()) decodedField(LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES) {
            LocalBranchInstallationWireV2.decodeSignedTargetApproval(it, snapshot, facts, human ?: malformed()) ?: malformed()
        } else null
        return NativeHistoryAttemptRecordV2(facts, phase, denial, claim, human, target, nullableDigest())
    }
    private fun NativeHistoryWriterV2.child(value: LocalInstallationCompletedChildFactsV2) {
        digest(value.originalAllocationDigest); digest(value.definitionDigest); id(value.originalRequestId); id(value.issuedAttemptId)
        long(value.attemptSequence); digest(value.installationGenesisDigest); digest(value.receiptDigest); digest(value.historyDigest)
        long(value.commandEpoch.value)
    }
    private fun NativeHistoryReaderV2.child(): LocalInstallationCompletedChildFactsV2 = LocalInstallationCompletedChildFactsV2(
        digest(), digest(), id(), id(), long(), digest(), digest(), digest(), Revision(long()))
    private fun NativeHistoryWriterV2.head(value: NativeHistoryRecordedHeadV2) {
        long(value.historySequence); digest(value.historyDigest); digest(value.installationGenesisDigest)
        long(value.commandEpoch); long(value.commandSequence); digest(value.commandDigest); digest(value.roleCommitment); digest(value.recoveryCommitment)
    }
    private fun NativeHistoryReaderV2.head(): NativeHistoryRecordedHeadV2 =
        NativeHistoryRecordedHeadV2(long(), digest(), digest(), long(), long(), digest(), digest(), digest())
    private fun NativeHistoryWriterV2.finalDeclaration(value: NativeHistoryFinalDeadlineDeclaration) {
        id(value.originalRequestId); id(value.originalOperationId); digest(value.originalRequestDigest)
        string(value.mustCommitBefore.canonicalString())
    }
    private fun NativeHistoryReaderV2.finalDeclaration(): NativeHistoryFinalDeadlineDeclaration =
        NativeHistoryFinalDeadlineDeclaration(id(), id(), digest(), UtcInstant.parseCanonical(string(30)))
    private fun NativeHistoryWriterV2.finalDecision(value: NativeHistoryFinalDeadlineDecision) {
        id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
        digest(value.originalAllocationDigest); head(value.child); digest(value.originalIntentDigest)
        finalDeclaration(value.declaration); string(value.finalWall.canonicalString())
    }
    private fun NativeHistoryReaderV2.finalDecision(): NativeHistoryFinalDeadlineDecision =
        NativeHistoryFinalDeadlineDecision(BranchScope(id(), id(), id()), digest(), head(), digest(),
            finalDeclaration(), UtcInstant.parseCanonical(string(30)))

    private fun NativeHistoryWriterV2.protectedDeclaration(value: NativeHistoryProtectedFinalDeclaration) {
        id(value.originalRequestId); digest(value.originalRequestDigest); id(value.actorId); id(value.sessionId)
        id(value.issuerContextId); id(value.issuedAt.bootId); long(value.issuedAt.elapsedMillis)
        id(value.lastObservedAt.bootId); long(value.lastObservedAt.elapsedMillis); long(value.expiresAtElapsedMillis)
    }
    private fun NativeHistoryReaderV2.protectedDeclaration(): NativeHistoryProtectedFinalDeclaration =
        NativeHistoryProtectedFinalDeclaration(id(), digest(), id(), id(), id(), MonotonicMoment(id(), long()),
            MonotonicMoment(id(), long()), long())
    private fun NativeHistoryWriterV2.protectedDecision(value: NativeHistoryProtectedFinalDecision) {
        id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
        digest(value.originalAllocationDigest); head(value.child); digest(value.originalIntentDigest)
        protectedDeclaration(value.declaration)
        id(value.observed.issuerContextId); id(value.observed.moment.bootId); long(value.observed.moment.elapsedMillis)
    }
    private fun NativeHistoryReaderV2.protectedDecision(): NativeHistoryProtectedFinalDecision =
        NativeHistoryProtectedFinalDecision(BranchScope(id(), id(), id()), digest(), head(), digest(),
            protectedDeclaration(), LocalAuthenticationObservation(id(), MonotonicMoment(id(), long())))

    /**
     * The POSIX V2 identity keeps its exact untagged bytes (device is always >= 0). Only a new
     * variant is tag-dispatched behind the otherwise impossible device sentinel -1, so no existing
     * V2 record byte changes and no V2 decode can ever be reinterpreted as another variant.
     */
    private fun NativeHistoryWriterV2.databaseIdentity(value: NativeHistoryDatabaseIdentity) {
        when (value) {
            is NativeHistoryDatabaseIdentityV2 -> {
                long(value.device); long(value.inode); int(value.uid); digest(value.namespaceDigest); string(value.roomIdentityHash)
            }
            is NativeHistoryDatabaseBindingWindowsV1 -> {
                long(TAGGED_DATABASE_SENTINEL); int(WINDOWS_DATABASE_TAG)
                ownedField(NativeHistoryDatabaseIdentityWireWindowsV1.encode(value.file))
                digest(value.namespaceDigest); string(value.roomIdentityHash)
            }
            is NativeHistoryDatabaseIdentityWindowsV1 -> malformed()
        }
    }
    private fun NativeHistoryReaderV2.databaseIdentity(): NativeHistoryDatabaseIdentity {
        val first = long()
        if (first != TAGGED_DATABASE_SENTINEL) return NativeHistoryDatabaseIdentityV2(first, long(), int(), digest(), string(32))
        if (int() != WINDOWS_DATABASE_TAG) malformed()
        val file = decodedField(NativeHistoryDatabaseIdentityWireWindowsV1.MAXIMUM_BYTES) {
            when (val result = NativeHistoryDatabaseIdentityWireWindowsV1.decode(it)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> result.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> malformed()
            }
        }
        return NativeHistoryDatabaseBindingWindowsV1(file, digest(), string(32))
    }
    internal const val TAGGED_DATABASE_SENTINEL: Long = -1L
    internal const val WINDOWS_DATABASE_TAG: Int = 1

    private fun NativeHistoryWriterV2.ownedField(value: ByteArray) {
        try { field(value) } finally { value.fill(0) }
    }
    private inline fun <T> NativeHistoryReaderV2.decodedField(maximum: Int, decode: (ByteArray) -> T): T {
        val value = field(maximum)
        return try { decode(value) } finally { value.fill(0) }
    }
    private fun <T> read(bytes: ByteArray, domain: String, maximum: Int, discard: (T) -> Unit = {},
        format: Int = FORMAT, body: NativeHistoryReaderV2.() -> T): T {
        require(bytes.size <= maximum)
        val reader = NativeHistoryReaderV2(bytes)
        try {
            require(reader.string(64) == PROFILE && reader.string(64) == domain && reader.int() == format)
            val value = reader.body()
            return try { reader.end(); value } catch (failure: Throwable) { discard(value); throw failure }
        } finally { reader.clear() }
    }
    private fun write(domain: String, maximum: Int, format: Int = FORMAT, body: NativeHistoryWriterV2.() -> Unit): ByteArray {
        val writer = NativeHistoryWriterV2(maximum)
        try { writer.string(PROFILE); writer.string(domain); writer.int(format); writer.body(); return writer.finish() }
        finally { writer.clear() }
    }
    private fun malformed(): Nothing = throw IllegalArgumentException("native.history.v2.malformed")
}

public enum class NativeHistoryDesiredRoleV2(public val tag: Int) {
    RUN(1), STOP(2), RETIRE(3), RECOVERY_HOLD(4);
    public companion object { public fun fromTag(tag: Int): NativeHistoryDesiredRoleV2 = entries.single { it.tag == tag } }
}
public enum class NativeHistoryAttemptPhaseV2(public val tag: Int) {
    PRE_SQL(1), SQL_SUBMISSION_ARMED(2), DATABASE_BOUND(3), NATIVE_PENDING(4), COMPLETED(5), RETIRING(6);
    public companion object { public fun fromTag(tag: Int): NativeHistoryAttemptPhaseV2 = entries.single { it.tag == tag } }
}
public enum class NativeHistoryRetirementFateV2(public val tag: Int) {
    NEVER_SUBMITTED(1), NORMAL_ROLLBACK(2), RECONCILED_UNINSTALLED(3);
    public companion object { public fun fromTag(tag: Int): NativeHistoryRetirementFateV2 = entries.single { it.tag == tag } }
}
public data class NativeHistoryDatabaseIdentityV2(
    public val device: Long, public val inode: Long, public val uid: Int,
    public val namespaceDigest: PayloadDigest, public val roomIdentityHash: String,
) : NativeHistoryDatabaseIdentity {
    init { require(device >= 0 && inode > 0 && uid >= 0 && roomIdentityHash.length == 32 && roomIdentityHash.all { it in "0123456789abcdef" }) }
}
public data class NativeHistoryRecordedHeadV2(
    public val historySequence: Long, public val historyDigest: PayloadDigest,
    public val installationGenesisDigest: PayloadDigest, public val commandEpoch: Long,
    public val commandSequence: Long, public val commandDigest: PayloadDigest,
    public val roleCommitment: PayloadDigest, public val recoveryCommitment: PayloadDigest,
) { init { require(historySequence in 1..SelfOwnedNativeHistoryWireV2.MAX_EVENTS && commandEpoch >= 0 && commandSequence >= 0) } }
public data class NativeHistoryRoleIntentV2(public val role: NativeHistoryDesiredRoleV2, public val generation: Long,
    public val requestId: DomainId, public val requestDigest: PayloadDigest, public val authorizationDigest: PayloadDigest) {
    init { require(generation > 0) }
}
public data class NativeHistoryPendingV2(public val child: NativeHistoryRecordedHeadV2,
    public val originalIntent: NativeHistoryOriginalIntentV2,
    public val finalDeadline: NativeHistoryFinalDeadlineDeclaration? = null,
    public val protectedFinal: NativeHistoryProtectedFinalDeclaration? = null,
    public val businessPublications: NativeHistoryBusinessPublicationObservationProof? = null,
) { init {
    requireNativeApprovalFinalPair(originalIntent, finalDeadline, protectedFinal)
    businessPublications?.let { require(it.child == child); it.requireIntent(originalIntent) }
} }
public data class NativeHistoryAttemptRecordV2(
    public val facts: LocalInstallationAttemptFactsV2, public val phase: NativeHistoryAttemptPhaseV2,
    public val denialGeneration: Long,
    /** Exact P3 signed HUMAN envelope digest, not the P1 reservation/claim-record digest.
     * Null means no joined human OUTPUT; it proves no absence of a claim, signer or SQL work. */
    public val issuerClaimDigest: PayloadDigest?,
    public val humanApproval: LocalInstallationSignedApprovalV2?, public val targetApproval: LocalInstallationSignedApprovalV2?,
    public val submissionDigest: PayloadDigest?,
) {
    init { require(denialGeneration >= 0); require(targetApproval == null || humanApproval != null)
        require((humanApproval == null) == (issuerClaimDigest == null))
        humanApproval?.let { require(issuerClaimDigest == it.digest) }
        if (phase in setOf(NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED, NativeHistoryAttemptPhaseV2.DATABASE_BOUND,
                NativeHistoryAttemptPhaseV2.NATIVE_PENDING, NativeHistoryAttemptPhaseV2.COMPLETED))
            require(humanApproval != null && targetApproval != null && submissionDigest != null)
        if (phase == NativeHistoryAttemptPhaseV2.PRE_SQL) require(submissionDigest == null)
    }
    public val approvals: LocalInstallationOriginalApprovalsV2?
        get() = if (humanApproval != null && targetApproval != null) LocalInstallationOriginalApprovalsV2(humanApproval, targetApproval) else null
}
public class NativeHistoryRetiredAttemptV2(public val facts: LocalInstallationAttemptFactsV2,
    public val fate: NativeHistoryRetirementFateV2, public val denialGeneration: Long,
    evidenceBytes: ByteArray, public val issuerRetirementDigest: PayloadDigest?) {
    init { require(denialGeneration > 0 && evidenceBytes.size in 1..SelfOwnedNativeHistoryWireV2.MAX_RETIREMENT_EVIDENCE_BYTES) }
    private val evidence = evidenceBytes.copyOf()
    public fun copyEvidenceBytes(): ByteArray = evidence.copyOf()
    public val digest: PayloadDigest get() = sha256(evidence)
    override fun toString(): String = "NativeHistoryRetiredAttemptV2([REDACTED])"
}

/**
 * Capacity DATA, not an issuer admission or an issued ID. It survives native restart and
 * unrelated writes before the actual P1 helper forces its own pending admission. Only an
 * actual PRE_SQL attempt with this next sequence consumes it; abandonment does not.
 */
public data class NativeHistoryInstallationReservationV2(
    public val nextAttemptSequence: Long, public val definitionDigest: PayloadDigest,
) { init { require(nextAttemptSequence in 1..SelfOwnedNativeHistoryWireV2.MAX_RETIRED_ATTEMPTS.toLong()) } }

public class NativeHistoryAnchorRecordV2(
    public val allocation: OriginalAllocationCommitmentV2, public val generation: Long, public val denialGeneration: Long,
    public val definition: LocalBranchInstallationSnapshotV2?, database: NativeHistoryDatabaseIdentity?,
    public val parent: NativeHistoryRecordedHeadV2?, public val pending: NativeHistoryPendingV2?,
    public val roleIntent: NativeHistoryRoleIntentV2?, public val attempt: NativeHistoryAttemptRecordV2?,
    retiredAttempts: List<NativeHistoryRetiredAttemptV2>, public val firstChild: LocalInstallationCompletedChildFactsV2?,
    public val receipt: LocalBranchInstallationReceiptV2?, public val issuerAcknowledgement: LocalInstallationSignedAcknowledgementV2?,
    public val installationReservation: NativeHistoryInstallationReservationV2? = null,
    public val recordFormat: Int = SelfOwnedNativeHistoryWireV2.FORMAT,
    finalDeadlineDecisions: List<NativeHistoryFinalDeadlineDecision> = emptyList(),
    protectedFinalDecisions: List<NativeHistoryProtectedFinalDecision> = emptyList(),
    businessPublicationObservations: List<NativeHistoryBusinessPublicationObservationProof> = emptyList(),
    public val masterSdkOriginal: LocalMasterSdkOriginal? = null,
) {
    private val decisions = finalDeadlineDecisions.toList()
    public val finalDeadlineDecisions: List<NativeHistoryFinalDeadlineDecision> get() = decisions.toList()
    private val protectedDecisions = protectedFinalDecisions.toList()
    public val protectedFinalDecisions: List<NativeHistoryProtectedFinalDecision> get() = protectedDecisions.toList()
    private val publicationObservations = businessPublicationObservations.toList()
    public val businessPublicationObservations: List<NativeHistoryBusinessPublicationObservationProof> get() = publicationObservations.toList()
    private val retired = retiredAttempts.toList()
    public val retiredAttempts: List<NativeHistoryRetiredAttemptV2> get() = retired.toList()
    /** The exact retained original database identity (POSIX V2 or Windows binding), or null if unbound. */
    public val nativeDatabase: NativeHistoryDatabaseIdentity? = database
    /**
     * POSIX V2 view of [nativeDatabase] (Android/macOS). It is null for a Windows binding, so binding
     * presence and phase decisions must use [nativeDatabase]; this view never stands in for absence.
     */
    public val database: NativeHistoryDatabaseIdentityV2? get() = nativeDatabase as? NativeHistoryDatabaseIdentityV2
    init {
        // Only a Windows V2 Master original may hold the Windows binding, and it may hold nothing else.
        val windowsMaster = (masterSdkOriginal as? LocalMasterSdkOriginalV2)?.installedTarget?.platform ==
            SelfOwnedInstalledTargetPlatformV1.WINDOWS
        when (nativeDatabase) {
            null -> Unit
            is NativeHistoryDatabaseIdentityV2 -> require(!windowsMaster)
            is NativeHistoryDatabaseBindingWindowsV1 -> require(windowsMaster)
            is NativeHistoryDatabaseIdentityWindowsV1 -> throw IllegalArgumentException("native.history.database.unbound")
        }
        require(generation > 0 && denialGeneration >= 0 && retired.size <= SelfOwnedNativeHistoryWireV2.MAX_RETIRED_ATTEMPTS)
        require(recordFormat in setOf(SelfOwnedNativeHistoryWireV2.FORMAT, SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_FORMAT,
            SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_FORMAT, SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_FORMAT,
            SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT) &&
            decisions.size + protectedDecisions.size <= SelfOwnedNativeHistoryWireV2.MAX_FINAL_DEADLINE_DECISIONS)
        // Absence in a legacy record is unsupported, never evidence permitting first capture.
        if (recordFormat >= SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT)
            require(requireNotNull(masterSdkOriginal).allocation.digest == allocation.digest)
        else require(masterSdkOriginal == null)
        require(publicationObservations.size <= NativeBusinessPublicationObservationWire.MAX_OBSERVATIONS)
        if (recordFormat == SelfOwnedNativeHistoryWireV2.FORMAT) require(decisions.isEmpty() && pending?.finalDeadline == null)
        if (recordFormat < SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_FORMAT)
            require(protectedDecisions.isEmpty() && pending?.protectedFinal == null)
        if (recordFormat < SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_FORMAT)
            require(publicationObservations.isEmpty() && pending?.businessPublications == null)
        require(publicationObservations.zipWithNext().all { (a, b) -> a.child.historySequence < b.child.historySequence } &&
            publicationObservations.map { it.originalOperationId }.distinct().size == publicationObservations.size &&
            publicationObservations.map { it.frameId }.distinct().size == publicationObservations.size)
        for (observation in publicationObservations) {
            val tip = requireNotNull(parent)
            require(observation.scope == allocation.coordinates.scope && observation.originalAllocationDigest == allocation.digest &&
                observation.child.installationGenesisDigest == tip.installationGenesisDigest &&
                observation.child.historySequence <= tip.historySequence && observation.child.commandEpoch <= tip.commandEpoch)
            if (observation.child.historySequence == tip.historySequence) require(observation.child == tip)
        }
        pending?.businessPublications?.let {
            require(it.scope == allocation.coordinates.scope && it.originalAllocationDigest == allocation.digest &&
                publicationObservations.size < NativeBusinessPublicationObservationWire.MAX_OBSERVATIONS &&
                it.preparationParent == parent && publicationObservations.none { old ->
                    old.originalOperationId == it.originalOperationId || old.frameId == it.frameId })
        }
        require(decisions.zipWithNext().all { (a, b) -> a.child.historySequence < b.child.historySequence } &&
            decisions.map { it.declaration.originalOperationId }.distinct().size == decisions.size)
        for (decision in decisions) {
            val tip = requireNotNull(parent)
            require(decision.scope == allocation.coordinates.scope && decision.originalAllocationDigest == allocation.digest &&
                decision.child.installationGenesisDigest == tip.installationGenesisDigest &&
                decision.child.historySequence <= tip.historySequence && decision.child.commandEpoch <= tip.commandEpoch)
            if (decision.child.historySequence == tip.historySequence) require(decision.child == tip)
        }
        // Approval IDs and command operation IDs are independent namespaces. The exact
        // authenticated original intents assign those lanes during the mandatory full
        // chronological scan; a raw UUID cannot classify a parsed protected decision.
        // Retain structural uniqueness here, without granting authority to parsed DATA.
        require(protectedDecisions.zipWithNext().all { (a, b) -> a.child.historySequence < b.child.historySequence })
        for (decision in protectedDecisions) {
            val tip = requireNotNull(parent)
            require(decision.scope == allocation.coordinates.scope && decision.originalAllocationDigest == allocation.digest &&
                decision.child.installationGenesisDigest == tip.installationGenesisDigest &&
                decision.child.historySequence <= tip.historySequence && decision.child.commandEpoch <= tip.commandEpoch)
            if (decision.child.historySequence == tip.historySequence) require(decision.child == tip)
            decisions.singleOrNull { it.child.historySequence == decision.child.historySequence }?.let { wall ->
                require(wall.child == decision.child && wall.originalIntentDigest == decision.originalIntentDigest &&
                    wall.scope == decision.scope && wall.originalAllocationDigest == decision.originalAllocationDigest &&
                    wall.declaration.originalRequestId == decision.declaration.originalRequestId &&
                    wall.declaration.originalOperationId == decision.declaration.originalRequestId &&
                    wall.declaration.originalRequestDigest == decision.declaration.originalRequestDigest)
            }
        }
        require(definition == null || definition.allocationDigest == allocation.digest)
        require((attempt == null && retired.isEmpty()) || definition != null)
        require(roleIntent == null || roleIntent.generation <= generation)
        require(parent == null || nativeDatabase != null)
        require(attempt != null || (parent == null && pending == null && firstChild == null && receipt == null && issuerAcknowledgement == null))
        val c = allocation.coordinates
        val reservedIds = mutableSetOf(c.allocationId,c.scope.organizationId,c.scope.companyId,c.scope.branchId,c.installationId,
            c.assignmentId,c.deviceId,c.storageInstanceId,c.storageKeyId,c.pepperKeyId,c.targetSigningKeyId,c.firstActorId)
        definition?.let {
            val h = it.definition.human
            reservedIds += listOf(it.definition.originalRequestId,h.rootId,h.rootKeyId,h.foundingActionId,
                h.headAuthorityId,h.headEnrollmentId,h.headInstallationKeyId)
        }
        var previous = 0L
        var previousDenial = 0L
        retired.forEachIndexed { index, value ->
            require(value.facts.attemptSequence == previous + 1 && value.facts.originalAnchorGeneration < generation &&
                value.denialGeneration > previousDenial && value.denialGeneration <= denialGeneration && reservedIds.add(value.facts.issuedAttemptId))
            // Only the newest terminal join may still be incomplete. No next attempt can
            // cross that unresolved native/P1 boundary, including after an interrupted ACK.
            require(value.issuerRetirementDigest != null || (index == retired.lastIndex && attempt == null))
            SelfOwnedNativeHistoryWireV2.requireOriginalRetirementEvidence(allocation,requireNotNull(definition),value)
            previous++; previousDenial = value.denialGeneration
        }
        attempt?.let {
            require(it.facts.attemptSequence == previous + 1 && it.facts.originalAnchorGeneration < generation &&
                it.denialGeneration in previousDenial..denialGeneration && reservedIds.add(it.facts.issuedAttemptId))
            when (it.phase) {
                NativeHistoryAttemptPhaseV2.COMPLETED -> require(nativeDatabase != null && parent != null && firstChild != null && receipt != null)
                NativeHistoryAttemptPhaseV2.RETIRING -> require(denialGeneration > it.denialGeneration)
                else -> require(denialGeneration == it.denialGeneration)
            }
            if (it.phase != NativeHistoryAttemptPhaseV2.COMPLETED) {
                require(retired.size < SelfOwnedNativeHistoryWireV2.MAX_RETIRED_ATTEMPTS)
                require(parent == null && firstChild == null && receipt == null && issuerAcknowledgement == null)
            }
            when (it.phase) {
                NativeHistoryAttemptPhaseV2.PRE_SQL, NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED -> require(pending == null)
                NativeHistoryAttemptPhaseV2.DATABASE_BOUND -> require(nativeDatabase != null && pending == null)
                NativeHistoryAttemptPhaseV2.NATIVE_PENDING -> require(nativeDatabase != null && pending != null)
                NativeHistoryAttemptPhaseV2.RETIRING -> if (pending != null) require(it.approvals != null && it.submissionDigest != null)
                NativeHistoryAttemptPhaseV2.COMPLETED -> if (pending != null) {
                    // No ordinary writer can occupy pending or spend the original ACK tail.
                    require(issuerAcknowledgement != null && roleIntent != null)
                }
            }
        }
        installationReservation?.let {
            require(attempt == null && parent == null && pending == null && firstChild == null && receipt == null && issuerAcknowledgement == null)
            require(it.definitionDigest == requireNotNull(definition).definitionDigest && it.nextAttemptSequence == previous + 1)
            require(retired.size < SelfOwnedNativeHistoryWireV2.MAX_RETIRED_ATTEMPTS && retired.all { entry -> entry.issuerRetirementDigest != null })
        }
        pending?.let { require(nativeDatabase != null && it.child.historySequence == (parent?.historySequence ?: 0L) + 1)
            if (parent != null) require(it.child.installationGenesisDigest == parent.installationGenesisDigest)
            else require(it.originalIntent.purposeTag == 1) }
        if (firstChild != null) {
            val d = requireNotNull(definition); val a = requireNotNull(attempt)
            require(firstChild.originalAllocationDigest == allocation.digest && firstChild.definitionDigest == d.definitionDigest &&
                firstChild.originalRequestId == d.definition.originalRequestId && firstChild.issuedAttemptId == a.facts.issuedAttemptId &&
                firstChild.attemptSequence == a.facts.attemptSequence && firstChild.commandEpoch == d.definition.authorityEpoch)
            require(nativeDatabase != null && a.approvals != null)
            parent?.let { head ->
                require(head.installationGenesisDigest == firstChild.installationGenesisDigest &&
                    head.commandEpoch >= d.definition.authorityEpoch.value)
                if (head.historySequence == 1L) require(head.historyDigest == firstChild.historyDigest &&
                    head.commandEpoch == firstChild.commandEpoch.value && head.commandSequence == 0L &&
                    head.commandDigest == firstChild.installationGenesisDigest)
            }
            pending?.let { require(it.child.installationGenesisDigest == firstChild.installationGenesisDigest) }
        }
        receipt?.let { require(firstChild != null && it.genesis == firstChild.installationGenesisDigest && it.digest == firstChild.receiptDigest) }
        require(issuerAcknowledgement == null || receipt != null)
        NativeHistoryLifecycleCapacityV2.requireMonotoneCapacity(this)
    }
    override fun toString(): String = "NativeHistoryAnchorRecordV2([REDACTED])"
}

/**
 * A retained promise, never a producer of authority, SQL fate or drain evidence.
 *
 * Count whole-record native forces, not calls, SQL statements or failed replies. From
 * NATIVE_PENDING, success needs native completion + exact issuer ACK (two). The longer
 * alternative is one irreversible admission-fence/denial/RETIRING force, one classified
 * terminal force, then the exact issuer join (three). The classified terminal force either
 * settles rollback AND appends the retirement, or retains DB/C1/R7/parent as COMPLETED.
 * In particular, an ARMED attempt that committed after withdrawal takes the latter path.
 * RETIRING does not mean uninstalled; unknown work keeps both remaining forces reserved.
 *
 * Working backward adds the pending, DATABASE_BOUND and ARMED forces and the still-
 * missing human/target joins. The durable proposal reserves PRE_SQL too, before P1 can
 * accept its own pending admission. A read, timeout, cancellation, role update, denial
 * bookkeeping or repeated unknown observation does not discharge any of these steps.
 * Classified rollback settlement and retirement occupy the SAME terminal force. A future
 * D2-W writer must use these actual closed joins; their absent production implementation
 * is not evidence of runtime qualification.
 */
internal object NativeHistoryLifecycleCapacityV2 {
    private const val ISSUER_TERMINAL_JOIN = 1L
    private const val CLASSIFIED_NATIVE_TERMINAL = 1L
    private const val ADMISSION_FENCE_AND_DENIAL = 1L
    private const val RETIRING_REMAINDER = CLASSIFIED_NATIVE_TERMINAL + ISSUER_TERMINAL_JOIN
    private const val PENDING_REMAINDER = ADMISSION_FENCE_AND_DENIAL + RETIRING_REMAINDER
    private const val RETAIN_PENDING = 1L
    private const val RETAIN_DATABASE_BINDING = 1L
    private const val RETAIN_SQL_ARMED = 1L
    private const val RETAIN_PRE_SQL = 1L
    private const val JOIN_HUMAN = 1L
    private const val JOIN_TARGET = 1L

    private fun remainingGenerations(value: NativeHistoryAnchorRecordV2): Long {
        val attempt = value.attempt
        if (attempt == null) {
            if (value.installationReservation != null) return RETAIN_PRE_SQL + JOIN_HUMAN + JOIN_TARGET +
                RETAIN_SQL_ARMED + RETAIN_DATABASE_BINDING + RETAIN_PENDING + PENDING_REMAINDER
            return if (value.retiredAttempts.lastOrNull()?.issuerRetirementDigest == null && value.retiredAttempts.isNotEmpty())
                ISSUER_TERMINAL_JOIN else 0L
        }
        return when (attempt.phase) {
            NativeHistoryAttemptPhaseV2.PRE_SQL ->
                (if (attempt.humanApproval == null) JOIN_HUMAN else 0L) +
                (if (attempt.targetApproval == null) JOIN_TARGET else 0L) +
                RETAIN_SQL_ARMED + RETAIN_DATABASE_BINDING + RETAIN_PENDING + PENDING_REMAINDER
            NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED -> RETAIN_DATABASE_BINDING + RETAIN_PENDING + PENDING_REMAINDER
            NativeHistoryAttemptPhaseV2.DATABASE_BOUND -> RETAIN_PENDING + PENDING_REMAINDER
            NativeHistoryAttemptPhaseV2.NATIVE_PENDING -> PENDING_REMAINDER
            NativeHistoryAttemptPhaseV2.RETIRING -> RETIRING_REMAINDER
            NativeHistoryAttemptPhaseV2.COMPLETED -> when {
                value.issuerAcknowledgement == null -> ISSUER_TERMINAL_JOIN
                value.pending == null -> 0L
                // An ordinary accepted child can still require a deny/role force and then
                // exact native settlement. Non-RUN already records that denial, not fate.
                value.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN -> ADMISSION_FENCE_AND_DENIAL + CLASSIFIED_NATIVE_TERMINAL
                else -> CLASSIFIED_NATIVE_TERMINAL
            }
        }
    }

    private fun remainingDenials(value: NativeHistoryAnchorRecordV2): Long = when {
        value.installationReservation != null -> 1L
        value.attempt == null -> 0L
        value.attempt.phase == NativeHistoryAttemptPhaseV2.RETIRING -> 0L
        value.attempt.phase != NativeHistoryAttemptPhaseV2.COMPLETED -> 1L
        value.pending != null && value.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN -> 1L
        else -> 0L
    }

    fun requireMonotoneCapacity(value: NativeHistoryAnchorRecordV2) {
        // Subtraction before comparison avoids overflow, including at authenticated MAX.
        require(value.generation <= Long.MAX_VALUE - remainingGenerations(value))
        require(value.denialGeneration <= Long.MAX_VALUE - remainingDenials(value))
    }

    // Exact wire framing bounds: bool=1, int/field length=4, long=8, UUID=4+36,
    // digest=4+64. Nullable flags and list counts already occur in encodeRecord's size.
    // Only an absent attempt introduces its four INNER nullable flags. No guessed tail.
    private const val BOOL = 1L
    private const val INT = 4L
    private const val LONG = 8L
    private const val ID = INT + 36L
    private const val DIGEST = INT + 64L
    private const val FACTS_FIELD = INT + SelfOwnedNativeHistoryWireV2.MAX_ATTEMPT_FACTS_BYTES
    private const val ATTEMPT_BASE = FACTS_FIELD + INT + LONG + 4L * BOOL
    private const val APPROVAL_FIELD = INT + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES
    private const val DATABASE_BODY = 2L * LONG + INT + DIGEST + INT + 32L
    // Sentinel + tag + bounded Windows file-identity field + the same digest/hash tail.
    private const val WINDOWS_DATABASE_BODY = LONG + INT + INT + NativeHistoryDatabaseIdentityWireWindowsV1.MAXIMUM_BYTES +
        DIGEST + INT + 32L
    /** Android/macOS keep the unchanged V2 reserve; only a Windows V2 Master original reserves the Windows body. */
    private fun databaseReserve(value: NativeHistoryAnchorRecordV2): Long =
        if ((value.masterSdkOriginal as? LocalMasterSdkOriginalV2)?.installedTarget?.platform ==
            SelfOwnedInstalledTargetPlatformV1.WINDOWS) WINDOWS_DATABASE_BODY else DATABASE_BODY
    private const val HEAD_BODY = 3L * LONG + 5L * DIGEST
    private const val PENDING_BODY = HEAD_BODY + INT + SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES
    private const val ROLE_BODY = INT + LONG + ID + 2L * DIGEST
    private const val FIRST_CHILD_BODY = 5L * DIGEST + 2L * ID + 2L * LONG
    private const val RECEIPT_FIELD = INT + LocalBranchInstallationWireV2.MAXIMUM_RECEIPT_BYTES
    private const val ACK_FIELD = INT + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES
    private const val RETIRED_ENTRY = FACTS_FIELD + INT + LONG + INT +
        SelfOwnedNativeHistoryWireV2.MAX_RETIREMENT_EVIDENCE_BYTES + BOOL + DIGEST

    private fun remainingGrowth(value: NativeHistoryAnchorRecordV2): Long {
        val attempt = value.attempt
        if (attempt?.phase == NativeHistoryAttemptPhaseV2.COMPLETED)
            return (if (value.issuerAcknowledgement == null) ACK_FIELD else 0L) +
                (if (value.pending?.finalDeadline != null) NativeHistoryFinalDecisionCapacity.MAX_DECISION_BYTES else 0L) +
                (if (value.pending?.protectedFinal != null) NativeHistoryFinalDecisionCapacity.MAX_PROTECTED_DECISION_BYTES else 0L) +
                (if (value.pending?.businessPublications != null) INT + NativeBusinessPublicationObservationWire.MAX_PROOF_BYTES else 0L)
        if (attempt == null && value.installationReservation == null)
            return if (value.retiredAttempts.isNotEmpty() && value.retiredAttempts.last().issuerRetirementDigest == null) DIGEST else 0L

        // Reserve the maximum still possible native footprint, including an entire
        // bounded pending capsule, not just its digest. Filled immutable fields replace
        // their bounds with their actual encoded sizes. Unknown retains both fate paths.
        val proofs = (if (attempt == null) ATTEMPT_BASE else 0L) +
            (if (attempt?.humanApproval == null) APPROVAL_FIELD + DIGEST else 0L) +
            (if (attempt?.targetApproval == null) APPROVAL_FIELD else 0L) +
            (if (attempt?.submissionDigest == null) DIGEST else 0L)
        val role = if (value.roleIntent == null) ROLE_BODY else 0L
        val completion = proofs + role +
            (if (value.nativeDatabase == null) databaseReserve(value) else 0L) +
            (if (value.parent == null) HEAD_BODY else 0L) +
            (if (value.pending == null) PENDING_BODY +
                (if (value.recordFormat >= SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_FORMAT) BOOL else 0L) +
                (if (value.recordFormat >= SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_FORMAT) BOOL else 0L) +
                (if (value.recordFormat >= SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_FORMAT) BOOL else 0L) else 0L) +
            (if (value.firstChild == null) FIRST_CHILD_BODY else 0L) +
            (if (value.receipt == null) RECEIPT_FIELD else 0L) +
            (if (value.issuerAcknowledgement == null) ACK_FIELD else 0L)
        // Deliberately do not credit removal of the old attempt/pending while reserving
        // this alternative. The new entry includes its later issuer-join digest.
        val retirement = role + RETIRED_ENTRY
        return maxOf(completion, retirement)
    }

    fun requireEncodedCapacity(value: NativeHistoryAnchorRecordV2, encodedBytes: Int) {
        requireMonotoneCapacity(value)
        require(encodedBytes >= 0 && encodedBytes.toLong() <= SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES.toLong() - remainingGrowth(value))
    }

    /**
     * Shared mandatory guard for EVERY original-key native retention. A terminal phase
     * cannot be used as a capacity refund while dropping the original facts, pending
     * work or issuer evidence. These structural checks do not replace the actual private
     * D2-W producer's fence/drain/full-census/outer-cleanup/native/P1 proof requirements.
     */
    fun requireTransition(old: NativeHistoryAnchorRecordV2, next: NativeHistoryAnchorRecordV2) {
        requireMonotoneCapacity(old); requireMonotoneCapacity(next)
        require(old.generation < Long.MAX_VALUE && next.generation == old.generation + 1L)
        require(next.denialGeneration == old.denialGeneration ||
            (old.denialGeneration < Long.MAX_VALUE && next.denialGeneration == old.denialGeneration + 1L))
        require(next.allocation.digest == old.allocation.digest)
        // Only the genuine first-companion producer can introduce a Master original. Later
        // writes preserve it exactly; this guard does not authorize legacy adoption or repair.
        require(next.masterSdkOriginal?.descriptorDigest == old.masterSdkOriginal?.descriptorDigest)
        require(next.recordFormat == old.recordFormat ||
            old.recordFormat < next.recordFormat && next.recordFormat in setOf(
                SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_FORMAT, SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_FORMAT,
                SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_FORMAT) &&
                old.parent != null && old.pending == null && next.pending != null &&
                old.attempt?.phase == NativeHistoryAttemptPhaseV2.COMPLETED && old.issuerAcknowledgement != null)
        val oldDecisions = old.finalDeadlineDecisions; val nextDecisions = next.finalDeadlineDecisions
        require(nextDecisions.size in oldDecisions.size..oldDecisions.size + 1 &&
            nextDecisions.take(oldDecisions.size) == oldDecisions)
        if (nextDecisions.size > oldDecisions.size) {
            val pendingDecision = requireNotNull(old.pending)
            val decision = nextDecisions.last()
            require(next.pending == null && next.parent == pendingDecision.child && decision.child == pendingDecision.child &&
                decision.declaration == pendingDecision.finalDeadline && decision.originalIntentDigest == intentDigest(pendingDecision.originalIntent) &&
                decision.scope == old.allocation.coordinates.scope && decision.originalAllocationDigest == old.allocation.digest)
        }
        if (old.pending?.finalDeadline != null && next.parent == old.pending.child && next.pending == null)
            require(nextDecisions.size == oldDecisions.size + 1) // Restart cannot synthesize the lost original sample.
        val oldProtected = old.protectedFinalDecisions; val nextProtected = next.protectedFinalDecisions
        require(nextProtected.size in oldProtected.size..oldProtected.size + 1 &&
            nextProtected.take(oldProtected.size) == oldProtected)
        if (nextProtected.size > oldProtected.size) {
            val pendingDecision = requireNotNull(old.pending)
            val decision = nextProtected.last()
            require(next.pending == null && next.parent == pendingDecision.child && decision.child == pendingDecision.child &&
                decision.declaration == pendingDecision.protectedFinal && decision.originalIntentDigest == intentDigest(pendingDecision.originalIntent) &&
                decision.scope == old.allocation.coordinates.scope && decision.originalAllocationDigest == old.allocation.digest)
        }
        if (old.pending?.protectedFinal != null && next.parent == old.pending.child && next.pending == null)
            require(nextProtected.size == oldProtected.size + 1)
        val oldPublications = old.businessPublicationObservations; val nextPublications = next.businessPublicationObservations
        require(nextPublications.size in oldPublications.size..oldPublications.size + 1 &&
            nextPublications.take(oldPublications.size) == oldPublications)
        if (nextPublications.size > oldPublications.size) {
            val pendingPublication = requireNotNull(old.pending)
            val observation = nextPublications.last()
            require(next.pending == null && next.parent == pendingPublication.child &&
                observation == pendingPublication.businessPublications && observation.child == pendingPublication.child &&
                observation.preparationParent == old.parent &&
                observation.originalIntentDigest == intentDigest(pendingPublication.originalIntent) &&
                observation.scope == old.allocation.coordinates.scope && observation.originalAllocationDigest == old.allocation.digest)
        }
        if (old.pending?.businessPublications != null && next.parent == old.pending.child && next.pending == null)
            require(nextPublications.size == oldPublications.size + 1)
        old.definition?.let { require(next.definition?.definitionDigest == it.definitionDigest) }
        old.nativeDatabase?.let { require(next.nativeDatabase == it) }
        old.roleIntent?.let {
            val role = requireNotNull(next.roleIntent)
            require(role.generation >= it.generation)
            if (role.generation == it.generation) require(role == it)
        }
        old.firstChild?.let { require(next.firstChild == it) }
        old.receipt?.let { require(next.receipt?.digest == it.digest) }
        old.issuerAcknowledgement?.let { require(next.issuerAcknowledgement?.digest == it.digest) }

        val before = old.retiredAttempts; val after = next.retiredAttempts
        require(after.size in before.size..before.size + 1)
        before.forEachIndexed { index, entry ->
            val retained = after[index]
            require(retained.facts == entry.facts && retained.fate == entry.fate && retained.denialGeneration == entry.denialGeneration && retained.digest == entry.digest)
            entry.issuerRetirementDigest?.let { require(retained.issuerRetirementDigest == it) }
        }
        val a = old.attempt; val b = next.attempt
        when {
            a == null && b == null -> {
                require(after.size == before.size)
                old.installationReservation?.let { require(next.installationReservation == it) }
                if (old.installationReservation == null && next.installationReservation != null)
                    require(before.all { it.issuerRetirementDigest != null })
            }
            a == null && b != null -> {
                val reservation = requireNotNull(old.installationReservation)
                require(next.installationReservation == null && after.size == before.size)
                require(b.phase == NativeHistoryAttemptPhaseV2.PRE_SQL && b.facts.attemptSequence == reservation.nextAttemptSequence &&
                    b.facts.originalAnchorGeneration == old.generation && next.definition?.definitionDigest == reservation.definitionDigest)
                require(b.denialGeneration == old.denialGeneration && next.denialGeneration == old.denialGeneration &&
                    b.humanApproval == null && b.targetApproval == null && b.submissionDigest == null)
            }
            a != null && b == null -> {
                require(a.phase == NativeHistoryAttemptPhaseV2.RETIRING && after.size == before.size + 1 && next.installationReservation == null)
                val terminal = after.last()
                require(terminal.facts == a.facts && terminal.denialGeneration == next.denialGeneration && terminal.issuerRetirementDigest == null)
                // The same native force settles pending AND appends the classified result.
                // Merely clearing pending or losing its handle never creates this entry.
                require(next.parent == null && next.pending == null)
            }
            else -> {
                requireNotNull(a); requireNotNull(b)
                require(next.installationReservation == null && after.size == before.size && b.facts == a.facts && b.denialGeneration == a.denialGeneration)
                a.issuerClaimDigest?.let { require(b.issuerClaimDigest == it) }
                a.humanApproval?.let { require(b.humanApproval?.digest == it.digest) }
                a.targetApproval?.let { require(b.targetApproval?.digest == it.digest) }
                a.submissionDigest?.let { require(b.submissionDigest == it) }
                require(when (a.phase) {
                    NativeHistoryAttemptPhaseV2.PRE_SQL -> b.phase in setOf(a.phase, NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED, NativeHistoryAttemptPhaseV2.RETIRING)
                    NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED -> b.phase in setOf(a.phase, NativeHistoryAttemptPhaseV2.DATABASE_BOUND, NativeHistoryAttemptPhaseV2.RETIRING)
                    NativeHistoryAttemptPhaseV2.DATABASE_BOUND -> b.phase in setOf(a.phase, NativeHistoryAttemptPhaseV2.NATIVE_PENDING, NativeHistoryAttemptPhaseV2.RETIRING)
                    NativeHistoryAttemptPhaseV2.NATIVE_PENDING -> b.phase in setOf(a.phase, NativeHistoryAttemptPhaseV2.COMPLETED, NativeHistoryAttemptPhaseV2.RETIRING)
                    NativeHistoryAttemptPhaseV2.RETIRING -> b.phase in setOf(a.phase, NativeHistoryAttemptPhaseV2.COMPLETED)
                    NativeHistoryAttemptPhaseV2.COMPLETED -> b.phase == a.phase
                })
                if (a.phase != NativeHistoryAttemptPhaseV2.RETIRING && b.phase == NativeHistoryAttemptPhaseV2.RETIRING)
                    require(next.denialGeneration == old.denialGeneration + 1L)
                if (a.phase != NativeHistoryAttemptPhaseV2.COMPLETED && b.phase == NativeHistoryAttemptPhaseV2.COMPLETED)
                    require(next.parent?.historySequence == 1L && next.pending == null && next.issuerAcknowledgement == null)
                if (a.phase == NativeHistoryAttemptPhaseV2.RETIRING) {
                    // Fencing cannot manufacture a later submission or admit a new pending
                    // child. It can still classify the original, already ARMED commit.
                    require(b.submissionDigest == a.submissionDigest)
                    if (b.phase == NativeHistoryAttemptPhaseV2.RETIRING && old.pending == null) require(next.pending == null)
                }
                if (a.phase == NativeHistoryAttemptPhaseV2.RETIRING && b.phase == NativeHistoryAttemptPhaseV2.COMPLETED)
                    require(a.submissionDigest != null && a.approvals != null)
            }
        }

        val pending = old.pending
        if (pending != null) {
            next.pending?.let {
                require(it.child == pending.child && intentDigest(it.originalIntent) == intentDigest(pending.originalIntent) &&
                    it.finalDeadline == pending.finalDeadline && it.protectedFinal == pending.protectedFinal &&
                    it.businessPublications == pending.businessPublications && next.parent == old.parent)
            } ?: run {
                require(next.parent == old.parent || next.parent == pending.child)
                // Original pending cannot disappear into a still-unknown attempt state.
                require(b == null || b.phase == NativeHistoryAttemptPhaseV2.COMPLETED)
            }
        } else if (next.pending != null && a?.phase == NativeHistoryAttemptPhaseV2.COMPLETED) {
            require(old.issuerAcknowledgement != null && old.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN &&
                next.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN && next.denialGeneration == old.denialGeneration)
        }
        if (old.parent != null && next.parent != old.parent) require(pending != null && next.parent == pending.child && next.pending == null)
        if (pending != null && a?.phase == NativeHistoryAttemptPhaseV2.COMPLETED &&
            old.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN && next.roleIntent?.role != NativeHistoryDesiredRoleV2.RUN)
            require(next.denialGeneration == old.denialGeneration + 1L)
    }

    private fun intentDigest(value: NativeHistoryOriginalIntentV2): PayloadDigest {
        val bytes = SelfOwnedNativeHistoryWireV2.encodeIntent(value)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
}

public enum class NativeHistoryResourceKindV2(public val tag: Int) {
    STOCK(1), REFUND_ENTITLEMENT(2), DEPOSIT_ENTITLEMENT(3), EXCHANGE_OFFSET(4), CASH_CUSTODY(5), PO_ALLOWANCE(6),
    SUPPLIER_SETTLEMENT(7), KITCHEN_CUSTODY(8), ORDER_CUSTODY(9), PURCHASE_CUSTODY(10), AUTHORIZATION(11);
    public companion object { public fun fromTag(tag: Int): NativeHistoryResourceKindV2 = entries.single { it.tag == tag } }
}
public data class NativeHistoryOriginalResourceV2(public val selectorTag: Int, public val kind: NativeHistoryResourceKindV2?,
    public val resourceId: DomainId?, public val preserveHold: Boolean, public val originalEvidenceDigest: PayloadDigest) {
    init { require(when (selectorTag) { 1 -> kind == null && resourceId == null; 2 -> kind != null && resourceId == null
        3 -> kind != null && resourceId != null; else -> false }) }
}
public class NativeHistoryOriginalIntentV2(public val purposeTag: Int, public val originalRequestId: DomainId,
    public val originalRequestDigest: PayloadDigest, public val originalOperationId: DomainId?, public val originalActorId: DomainId?,
    public val dependenciesDigest: PayloadDigest, public val originalFactsDigest: PayloadDigest,
    resources: List<NativeHistoryOriginalResourceV2>) {
    init { require(purposeTag in 1..19 && resources.size <= SelfOwnedNativeHistoryWireV2.MAX_RESOURCES) }
    private val values = resources.toList()
    public val resources: List<NativeHistoryOriginalResourceV2> get() = values.toList()
    override fun toString(): String = "NativeHistoryOriginalIntentV2([REDACTED])"
}
internal class NativeHistoryEnvelopeV2(val nonce: ByteArray, val ciphertext: ByteArray, val signature: ByteArray) {
    fun clear() { nonce.fill(0); ciphertext.fill(0); signature.fill(0) }
}

internal class NativeHistoryWriterV2(private val maximum: Int) {
    private var bytes = ByteArray(minOf(256, maximum)); private var at = 0
    private fun need(count: Int) { require(count >= 0 && count <= maximum - at)
        val next = at + count
        if (next > bytes.size) {
            val old = bytes
            bytes = bytes.copyOf(maxOf(next, minOf(maximum.toLong(), bytes.size.toLong() * 2).toInt()))
            old.fill(0)
        } }
    fun byte(value: Int) { require(value in 0..255); need(1); bytes[at++] = value.toByte() }
    fun int(value: Int) { need(4); for (shift in 24 downTo 0 step 8) bytes[at++] = (value ushr shift).toByte() }
    fun long(value: Long) { need(8); for (shift in 56 downTo 0 step 8) bytes[at++] = (value ushr shift).toByte() }
    fun bool(value: Boolean) { need(1); bytes[at++] = if (value) 1 else 0 }
    fun field(value: ByteArray) { need(4); require(value.size <= maximum - at - 4); int(value.size)
        need(value.size); value.copyInto(bytes, at); at += value.size }
    fun string(value: String) { require('\u0000' !in value); val raw = value.encodeToByteArray(throwOnInvalidSequence = true)
        try { field(raw) } finally { raw.fill(0) } }
    fun id(value: DomainId) { string(value.value) }
    fun digest(value: PayloadDigest) { string(value.value) }
    fun nullableId(value: DomainId?) { bool(value != null); value?.let { id(it) } }
    fun nullableDigest(value: PayloadDigest?) { bool(value != null); value?.let { digest(it) } }
    fun finish(): ByteArray = bytes.copyOf(at)
    fun clear() { bytes.fill(0) }
}
internal class NativeHistoryReaderV2(input: ByteArray) {
    private val bytes = input.copyOf(); private var at = 0
    private fun need(count: Int) { require(count >= 0 && count <= bytes.size - at) }
    fun byte(): Int { need(1); return bytes[at++].toInt() and 255 }
    fun int(): Int { need(4); var value = 0; repeat(4) { value = (value shl 8) or (bytes[at++].toInt() and 255) }; return value }
    fun long(): Long { need(8); var value = 0L; repeat(8) { value = (value shl 8) or (bytes[at++].toLong() and 255) }; return value }
    fun bool(): Boolean { need(1); return when (bytes[at++].toInt()) { 0 -> false; 1 -> true; else -> error("native.history.v2.boolean") } }
    fun field(maximum: Int): ByteArray { val count = int(); require(count in 0..maximum); need(count)
        return bytes.copyOfRange(at, at + count).also { at += count } }
    fun string(maximum: Int): String { val raw = field(maximum)
        return try { raw.decodeToString(throwOnInvalidSequence = true).also { require('\u0000' !in it) } } finally { raw.fill(0) } }
    fun id(): DomainId = DomainId(string(36))
    fun digest(): PayloadDigest = PayloadDigest(string(64))
    fun nullableId(): DomainId? = if (bool()) id() else null
    fun nullableDigest(): PayloadDigest? = if (bool()) digest() else null
    fun end() { require(at == bytes.size) }
    fun clear() { bytes.fill(0) }
}


/** Fixed storage classes, not SQLite affinity/coercion. Null is permitted only when declared. */
public enum class NativeHistorySqliteTypeV2(public val tag: Int) { NULL(0), INTEGER(1), REAL(2), TEXT(3), BLOB(4) }
public data class NativeHistoryColumnV2(public val name: String, public val type: NativeHistorySqliteTypeV2,
    public val minimum: Long, public val maximum: Long, public val exactText: String? = null, public val nullable: Boolean = false)
public data class NativeHistoryIndexV2(public val name: String, public val columns: List<String>, public val unique: Boolean = false)

/** One tag/table/key/column registry for both native statements and downstream schema/scanner. */
public enum class NativeHistoryTableV2(public val tag: Int, public val sqlName: String, public val immutable: Boolean,
    public val application: Boolean = true) {
    BRANCH(1,"branch_authority",false), OPERATION(2,"operation_result",true), AGGREGATE(3,"aggregate_state",false),
    CLAIM(4,"unique_claim",false), JOURNAL(5,"journal_envelope",true), AUDIT(6,"audit_event",true), OUTBOX(7,"outbox_intent",true),
    ACCOUNT(8,"protected_local_account",false), POLICY(9,"protected_effective_policy",false), ADMISSION(10,"protected_device_admission",false),
    THROTTLE(11,"protected_login_throttle",false), ACCESS(12,"protected_local_access_issuance",true),
    CONFIGURATION(13,"protected_local_auth_configuration",false), REFRESH(14,"protected_local_refresh_issuance",true),
    AUTH_EVENT(15,"protected_local_auth_event",true), ARCHIVE(16,"protected_local_session_archive",true), APPROVAL(17,"protected_local_action_approval",false),
    STOCK_POSITION(18,"stock_position_index_v2",false), STOCK_ALLOCATION(19,"stock_allocation_index_v2",false),
    STOCK_RESERVATION(20,"stock_reservation_index_v2",false), STOCK_VALUE(21,"stock_value_index_v2",false),
    JOURNAL_FRAGMENT(22,"journal_fragment_v2",true),
    HEAD(101,"runtime_history_head_v2",false,false), EVENT(102,"runtime_history_event_v2",true,false),
    ROW_VERSION(103,"runtime_history_row_version_v2",true,false), CHUNK(104,"runtime_history_chunk_v2",true,false),
    ROW_TIP(105,"runtime_history_row_tip_v2",false,false), WRITE_FRAME(106,"runtime_history_write_frame_v2",false,false),
    CAPTURE(107,"runtime_history_capture_v2",false,false);

    /** Fixed native selection, not a caller-supplied relationship/table allowlist. */
    public val hasAuthenticationImages: Boolean get() = when (this) {
        ACCOUNT, THROTTLE, ACCESS, REFRESH, AUTH_EVENT, ARCHIVE -> true
        else -> false
    }

    public val keyColumns: List<String> get() = when (this) {
        BRANCH -> listOf("scope")
        OPERATION -> listOf("scope","operationId")
        AGGREGATE -> listOf("scope","kind","id")
        CLAIM -> listOf("scope","namespace","key")
        JOURNAL -> listOf("scope","epoch","sequence")
        AUDIT -> listOf("id")
        OUTBOX, JOURNAL_FRAGMENT -> listOf("scope","operationId","ordinal")
        ACCOUNT,POLICY,CONFIGURATION -> listOf("scope","actorId")
        ADMISSION -> listOf("scope","deviceId")
        THROTTLE -> listOf("scope","actorId","dimensionKey")
        ACCESS,REFRESH,ARCHIVE -> listOf("scope","sessionId")
        AUTH_EVENT -> listOf("scope","eventId")
        APPROVAL -> listOf("scope","approvalId")
        STOCK_POSITION,STOCK_ALLOCATION,STOCK_RESERVATION,STOCK_VALUE -> listOf("scope","recordKind","recordId")
        HEAD,WRITE_FRAME -> listOf("singleton")
        EVENT -> listOf("sequence")
        ROW_VERSION,CHUNK -> listOf("sequence","ordinal")
        ROW_TIP -> listOf("tableTag","key")
        CAPTURE -> listOf("captureOrdinal")
    }
    public val protectedTable: ProtectedLocalTable? get() = when (this) {
        ACCOUNT -> ProtectedLocalTable.ACCOUNT; POLICY -> ProtectedLocalTable.EFFECTIVE_POLICY
        ADMISSION -> ProtectedLocalTable.ADMISSION; THROTTLE -> ProtectedLocalTable.THROTTLE
        ACCESS -> ProtectedLocalTable.SESSION_ISSUANCE; CONFIGURATION -> ProtectedLocalTable.AUTH_CONFIGURATION
        REFRESH -> ProtectedLocalTable.REFRESH_ISSUANCE; AUTH_EVENT -> ProtectedLocalTable.AUTH_EVENT
        ARCHIVE -> ProtectedLocalTable.SESSION_ARCHIVE; APPROVAL -> ProtectedLocalTable.ACTION_APPROVAL
        else -> null
    }
    public companion object { public fun fromTag(tag: Int): NativeHistoryTableV2 = entries.single { it.tag == tag } }
}

/**
 * Conservative inherited read ceilings, NOT a qualification of stock/recovery/backup capacity.
 * No isolated V2 limit increase substitutes for F05. Oversize is unresolved, never truncation. Cell length
 * is Long and each actual chunk is <=65536. No row/event is truncated to a chunk. The complete
 * nested recovery and all-writer/backup bound derivation remains an activation requirement.
 */
public object NativeHistoryPhysicalReadProfileV2 {
    public const val METADATA_PAGE: Int = 64
    public const val MAX_SCHEMA_OBJECTS: Int = 1024
    public const val MAX_SQL_TEXT_BYTES: Int = 65_536
    public const val CHUNK_BYTES: Int = 65_536
    /** In-memory read grouping only; existing cell bytes and all wire formats stay fixed. */
    public const val CELL_BATCH_CHUNKS: Int = 16
    /** One fixed native DATA task; at most 1 MiB of chunks plus bounded metadata. */
    public const val ROW_BATCH_ROWS: Int = 16
    public const val ROW_BATCH_CHUNKS: Int = 128
    public const val ROW_BATCH_BYTES: Int = 1024 * 1024
    public const val MAX_KEY_BYTES: Int = 1024
    public const val MAX_ROW_ARRAY_BYTES: Int = 5 * 1024 * 1024
    public const val MAX_CELL_BYTES: Long = 1L * MAX_ROW_ARRAY_BYTES
    public const val MAX_ROW_BYTES: Long = MAX_CELL_BYTES
    public const val MAX_EVENT_BYTES: Long = 2L * 1024 * 1024 * 1024
    public const val MAX_EVENT_CHUNKS: Int = 32_768
    public const val MAX_CHANGES: Int = 16_384
    public const val MAX_DATASET_BYTES: Long = 64L * 1024 * 1024 * 1024
    public val applicationTables: List<NativeHistoryTableV2> get() = NativeHistoryTableV2.entries.filter { it.application }
    public val historyTables: List<NativeHistoryTableV2> get() = NativeHistoryTableV2.entries.filter { !it.application }

    public fun columns(table: NativeHistoryTableV2): List<NativeHistoryColumnV2> {
        fun text(name: String, min: Long = 1, max: Long = 96, exact: String? = null, nullable: Boolean = false) =
            NativeHistoryColumnV2(name, NativeHistorySqliteTypeV2.TEXT, min, max, exact, nullable)
        fun integer(name: String, min: Long = 0, max: Long = Long.MAX_VALUE, nullable: Boolean = false) =
            NativeHistoryColumnV2(name, NativeHistorySqliteTypeV2.INTEGER, min, max, nullable = nullable)
        fun blob(name: String, min: Long = 1, max: Long = MAX_CELL_BYTES, nullable: Boolean = false) =
            NativeHistoryColumnV2(name, NativeHistorySqliteTypeV2.BLOB, min, max, nullable = nullable)
        fun id(name: String, optional: Boolean = false) = text(name, if (optional) 0 else 36, 36)
        fun hash(name: String, nullable: Boolean = false) = text(name, 64, 64, nullable = nullable)
        val scope = text("scope",110,110)
        val protected = table.protectedTable
        if (protected != null) {
            val keys = table.keyColumns.map { if (it == "scope") scope else if (it == "dimensionKey") text(it,7,43) else id(it) }
            val login = if (table == NativeHistoryTableV2.CONFIGURATION) listOf(hash("loginKey")) else emptyList()
            val maxRevision = when (table) {
                NativeHistoryTableV2.ACCESS,NativeHistoryTableV2.REFRESH,NativeHistoryTableV2.AUTH_EVENT,NativeHistoryTableV2.ARCHIVE -> 0L
                NativeHistoryTableV2.APPROVAL -> 1L; else -> Long.MAX_VALUE
            }
            return keys + login + listOf(integer("schemaVersion",1,1),integer("formatVersion",1,1),
                text("algorithm",11,11,ProtectedLocalStorageFormat.ALGORITHM),integer("revision",0,maxRevision),id("keyId"),
                blob("nonce",ProtectedLocalStorageFormat.NONCE_BYTES.toLong(),ProtectedLocalStorageFormat.NONCE_BYTES.toLong()),
                blob("ciphertextAndTag",ProtectedLocalStorageFormat.TAG_BYTES.toLong()+1,protected.maximumPlaintextBytes.toLong()+ProtectedLocalStorageFormat.TAG_BYTES))
        }
        val stock = listOf(scope,text("recordKind"),id("recordId"),integer("aggregateGeneration",1),id("producerOperationId"),hash("payloadHash"))
        return when (table) {
            NativeHistoryTableV2.BRANCH -> listOf(scope,id("assignmentId"),id("deviceId"),integer("epoch"),integer("revision"),integer("sequence"),
                hash("journalHash"),integer("writable",0,1),text("recoveryJson",1,MAX_CELL_BYTES))
            NativeHistoryTableV2.OPERATION -> listOf(scope,id("operationId"),id("actorId"),text("command"),integer("commandVersion",1,1000),
                hash("digest"),text("outcome"),blob("result",0,65_536),hash("resultHash"),integer("sequence",1),integer("epoch"),
                text("committedAt",20,30),id("authenticationId"),integer("policyRevision"))
            NativeHistoryTableV2.AGGREGATE -> listOf(scope,text("kind"),id("id"),integer("revision",1),text("schema"),blob("payload",0,65_536),hash("payloadHash"))
            NativeHistoryTableV2.CLAIM -> listOf(scope,text("namespace"),text("key",1,256),id("ownerId"))
            NativeHistoryTableV2.JOURNAL -> listOf(scope,integer("sequence",1),id("operationId"),id("assignmentId"),integer("epoch"),
                hash("previousHash"),hash("hash"),blob("envelope",1,4*1024*1024))
            NativeHistoryTableV2.AUDIT -> listOf(id("id"),scope,id("operationId"),text("kind",1,104),id("actorId"),id("originalActorId"),
                integer("policyRevision"),text("recordedAt",20,30),hash("digest"))
            NativeHistoryTableV2.OUTBOX -> listOf(scope,id("operationId"),integer("ordinal",0,32),text("kind"),blob("body",0,65_536),hash("bodyHash"))
            NativeHistoryTableV2.STOCK_POSITION -> stock + listOf(id("skuId"),id("warehouseId"),integer("ownership",1,2),id("ownerId",true),
                integer("custody",1,3),id("lotId",true),id("serialId",true),integer("expiryMillis",nullable=true),integer("expiryOrder"))
            NativeHistoryTableV2.STOCK_ALLOCATION -> stock + listOf(id("reservationId"),id("allocationId",true),id("lineId",true),
                id("positionId",true),integer("holdReason",0,5))
            NativeHistoryTableV2.STOCK_RESERVATION -> stock + listOf(id("orderId"),id("collectionId"),id("originalActorId"),
                integer("status",1,4),integer("exposure",1,4),integer("expiresAtMillis",nullable=true))
            NativeHistoryTableV2.STOCK_VALUE -> stock + listOf(id("poolId"),id("sourceId"),id("parentEventId",true),id("issueId",true),
                id("allocationId",true),id("linkedPoolId",true),integer("eventOrdinal"))
            NativeHistoryTableV2.JOURNAL_FRAGMENT -> listOf(scope,id("operationId"),integer("ordinal"),integer("byteCount",1,49_152),
                blob("payload",1,49_152),hash("payloadHash"))
            NativeHistoryTableV2.HEAD -> listOf(integer("singleton",1,1),scope,text("profile",40,40,SelfOwnedNativeHistoryWireV2.PROFILE),id("storageId"),
                hash("bindingDigest"),hash("installationGenesisDigest"),integer("sequence",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),hash("eventDigest"),
                integer("commandEpoch"),integer("commandSequence"),hash("commandDigest"),hash("roleCommitment"),hash("recoveryCommitment"))
            NativeHistoryTableV2.EVENT -> listOf(integer("sequence",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),id("transactionId"),integer("parentKind",1,2),
                integer("parentSequence",0,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),hash("parentDigest"),hash("eventDigest"),integer("changeCount",0,MAX_CHANGES.toLong()),
                integer("chunkCount",1,MAX_EVENT_CHUNKS.toLong()),integer("payloadBytes",1,MAX_EVENT_BYTES),integer("encodedBytes",1,MAX_EVENT_BYTES),blob("header",1,65_536))
            NativeHistoryTableV2.ROW_VERSION -> listOf(integer("sequence",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),integer("ordinal",0,MAX_CHANGES.toLong()-1),
                integer("tableTag",1,22),blob("key",1,MAX_KEY_BYTES.toLong()),integer("version",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS*MAX_CHANGES),
                integer("previousSequence",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS,true),integer("previousOrdinal",0,MAX_CHANGES.toLong()-1,true),
                integer("previousVersion",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS*MAX_CHANGES,true),integer("mutationKind",1,3),
                integer("beforeRowId",Long.MIN_VALUE,Long.MAX_VALUE,true),hash("beforeDigest",true),integer("afterRowId",Long.MIN_VALUE,Long.MAX_VALUE,true),
                hash("afterDigest",true),integer("afterKind",1,3),integer("imageOffset",0,MAX_EVENT_BYTES,true),integer("imageBytes",1,MAX_ROW_BYTES,true))
            NativeHistoryTableV2.CHUNK -> listOf(integer("sequence",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),integer("ordinal",0,MAX_EVENT_CHUNKS.toLong()-1),
                hash("digest"),blob("bytes",1,CHUNK_BYTES.toLong()))
            NativeHistoryTableV2.ROW_TIP -> listOf(integer("tableTag",1,22),blob("key",1,MAX_KEY_BYTES.toLong()),integer("version",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS*MAX_CHANGES),
                integer("sequence",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),integer("ordinal",0,MAX_CHANGES.toLong()-1),integer("tombstone",0,1),
                integer("targetRowId",Long.MIN_VALUE,Long.MAX_VALUE,true),hash("rowDigest",true))
            NativeHistoryTableV2.WRITE_FRAME -> listOf(integer("singleton",1,1),id("frameId"),id("transactionId"),integer("purposeTag",1,19),integer("parentKind",1,2),
                integer("parentSequence",0,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),hash("parentDigest"),integer("nextSequence",1,SelfOwnedNativeHistoryWireV2.MAX_EVENTS),hash("originalIntentDigest"))
            NativeHistoryTableV2.CAPTURE -> listOf(integer("captureOrdinal",1,MAX_CHANGES.toLong()),id("frameId"),integer("tableTag",1,22),integer("mutationKind",1,3),
                integer("beforeRowId",Long.MIN_VALUE,Long.MAX_VALUE,true),integer("afterRowId",Long.MIN_VALUE,Long.MAX_VALUE,true)) +
                listOf("before","after").flatMap { prefix -> listOf(blob(prefix+"Key1",1,MAX_KEY_BYTES.toLong(),true),
                    blob(prefix+"Key2Text",1,MAX_KEY_BYTES.toLong(),true),integer(prefix+"Key2Integer",0,Long.MAX_VALUE,true),
                    blob(prefix+"Key3Text",1,MAX_KEY_BYTES.toLong(),true),integer(prefix+"Key3Integer",0,Long.MAX_VALUE,true)) }
            else -> error("native.history.fixed.protected.table")
        }
    }

    public fun indices(table: NativeHistoryTableV2): List<NativeHistoryIndexV2> = when (table) {
        NativeHistoryTableV2.OPERATION -> listOf(NativeHistoryIndexV2("index_operation_result_scope_epoch_sequence",listOf("scope","epoch","sequence"),true))
        NativeHistoryTableV2.JOURNAL -> listOf(NativeHistoryIndexV2("index_journal_envelope_scope_operationId",listOf("scope","operationId"),true))
        NativeHistoryTableV2.AUDIT -> listOf(NativeHistoryIndexV2("index_audit_event_scope_operationId",listOf("scope","operationId")))
        NativeHistoryTableV2.OUTBOX -> listOf(NativeHistoryIndexV2("index_outbox_intent_scope_operationId",listOf("scope","operationId")))
        NativeHistoryTableV2.CONFIGURATION -> listOf(NativeHistoryIndexV2("index_protected_local_auth_configuration_scope_loginKey",listOf("scope","loginKey"),true))
        NativeHistoryTableV2.EVENT -> listOf(NativeHistoryIndexV2("index_runtime_history_event_v2_transactionId",listOf("transactionId"),true))
        NativeHistoryTableV2.ROW_VERSION -> listOf(NativeHistoryIndexV2("index_runtime_history_row_version_v2_tableTag_key_version",listOf("tableTag","key","version"),true))
        NativeHistoryTableV2.STOCK_POSITION -> listOf(
            NativeHistoryIndexV2("stock_position_coordinate_v2",listOf("scope","skuId","warehouseId","ownership","ownerId","custody","lotId","serialId"),true),
            NativeHistoryIndexV2("stock_position_fefo_v2",listOf("scope","skuId","warehouseId","expiryOrder","lotId","serialId","recordId")),
            NativeHistoryIndexV2("stock_position_lot_v2",listOf("scope","skuId","lotId","warehouseId","recordId")))
        NativeHistoryTableV2.STOCK_ALLOCATION -> listOf(NativeHistoryIndexV2("stock_allocation_position_v2",listOf("scope","positionId","recordKind","recordId")),
            NativeHistoryIndexV2("stock_allocation_reservation_v2",listOf("scope","reservationId","recordKind","lineId","recordId")))
        NativeHistoryTableV2.STOCK_RESERVATION -> listOf(NativeHistoryIndexV2("stock_reservation_order_v2",listOf("scope","orderId","recordId")),
            NativeHistoryIndexV2("stock_reservation_collection_v2",listOf("scope","collectionId","recordId")),
            NativeHistoryIndexV2("stock_reservation_owner_v2",listOf("scope","originalActorId","status","recordId")))
        NativeHistoryTableV2.STOCK_VALUE -> listOf(NativeHistoryIndexV2("stock_value_pool_v2",listOf("scope","poolId","eventOrdinal","recordKind","recordId")),
            NativeHistoryIndexV2("stock_value_source_v2",listOf("scope","sourceId","recordKind","recordId")),
            NativeHistoryIndexV2("stock_value_issue_v2",listOf("scope","issueId","allocationId","recordKind","recordId")),
            NativeHistoryIndexV2("stock_value_link_v2",listOf("scope","linkedPoolId","poolId","recordId")))
        else -> emptyList() // Legacy17 exact indices/guards are additionally verified by the DB schema owner.
    }
}

public enum class NativeHistoryReadPassV2 {
    CENSUS, EVENTS, EVENT_MUTATIONS, EVENT_CHUNKS, LINEAGE, LINEAGE_EVENT, LINEAGE_IMAGE, LINEAGE_TIP, LINEAGE_CURRENT,
    COMMANDS, COMMAND_OPERATION, COMMAND_AUDIT, COMMAND_OUTBOX, COMMAND_FRAGMENT,
    STOCK_AGGREGATES, STOCK_PROJECTION, STOCK_REVERSE, STOCK_REVERSE_AGGREGATE,
    ACCOUNT_RELATIONS, ACCOUNT_POLICY, ACCOUNT_CONFIGURATION, ACCOUNT_THROTTLES,
    ACCESS_RELATIONS, ACCESS_REFRESH, ACCESS_ARCHIVE, ACCESS_ACCOUNTS, ACCESS_ADMISSIONS, ACCESS_CONFIGURATIONS,
    ARCHIVE_RELATIONS, ARCHIVE_ACCESS, APPROVAL_RELATIONS, APPROVAL_OPERATIONS, END,
    // Appended to preserve existing ordinal identities. This is a source-fixed native read
    // pass, not a persisted schema/command change or permission to select arbitrary SQL.
    LINEAGE_EVENT_CHUNKS,
    // Actual native-owned employee initial-image prepass. Appended, never reorders an
    // existing source/wire ordinal and changes no persisted schema/header/intent format.
    EMPLOYEE_ENROLLMENTS, EMPLOYEE_ENROLLMENT_MUTATIONS, EMPLOYEE_ENROLLMENT_IMAGE, EMPLOYEE_ENROLLMENT_CHUNKS,
}
public enum class NativeHistoryRowKindV2 { PHYSICAL, IMAGE, PLAINTEXT, GROUP_END, PASS_END }
public data class NativeHistoryCellMetadataV2(public val name: String, public val type: NativeHistorySqliteTypeV2,
    public val byteCount: Long, public val integerValue: Long? = null)
public class NativeHistoryRowMetadataV2 internal constructor(public val pass: NativeHistoryReadPassV2,
    public val kind: NativeHistoryRowKindV2, public val table: NativeHistoryTableV2?, public val rowId: Long?,
    cells: List<NativeHistoryCellMetadataV2>, public val protectedBinding: ProtectedLocalBinding? = null) {
    private val columns = cells.toList()
    public val cells: List<NativeHistoryCellMetadataV2> get() = columns.toList()
    override fun toString(): String = "NativeHistoryRowMetadataV2(pass=$pass, [REDACTED])"
}
/** Finite ordered DATA from one native snapshot. Only the last row may have remaining
 * chunks in the source. This object proves neither custody nor consumer validation. */
public class NativeHistoryRowBatchItemV2 internal constructor(
    public val metadata: NativeHistoryRowMetadataV2,
    chunks: List<NativeHistoryCellChunkV2>,
) {
    public val chunks: List<NativeHistoryCellChunkV2> = chunks.toList()
    public fun clear(): Unit = chunks.forEach { it.clear() }
    override fun toString(): String = "NativeHistoryRowBatchItemV2([REDACTED])"
}
public class NativeHistoryCellChunkV2 internal constructor(public val column: Int, public val offset: Long,
    public val totalBytes: Long, public val last: Boolean, public val integerValue: Long?, bytes: ByteArray) {
    private val value = bytes.copyOf()
    public val byteCount: Int get() = value.size
    public fun copyBytes(): ByteArray = value.copyOf()
    public fun clear(): Unit = value.fill(0)
    override fun toString(): String = "NativeHistoryCellChunkV2([REDACTED])"
}
public sealed interface NativeHistorySchemaItemV2 {
    public data class Object(public val rowId: Long, public val type: String, public val name: String,
        public val table: String, public val rootPage: Long, public val sql: String?) : NativeHistorySchemaItemV2
    public data class Column(public val table: String, public val ordinal: Int, public val name: String,
        public val declaredType: String, public val notNull: Boolean, public val defaultSql: String?,
        public val primaryKeyOrdinal: Int, public val hidden: Int) : NativeHistorySchemaItemV2
    public data class Index(public val table: String, public val ordinal: Int, public val name: String,
        public val unique: Boolean, public val origin: String, public val partial: Boolean) : NativeHistorySchemaItemV2
    public data class IndexColumn(public val index: String, public val ordinal: Int, public val columnOrdinal: Int,
        public val name: String?, public val descending: Boolean, public val collation: String?, public val key: Boolean) : NativeHistorySchemaItemV2
    public data class ForeignKey(public val table: String, public val id: Int, public val ordinal: Int,
        public val targetTable: String, public val from: String, public val to: String,
        public val onUpdate: String, public val onDelete: String, public val match: String) : NativeHistorySchemaItemV2
    public data class Setting(public val name: String, public val value: String) : NativeHistorySchemaItemV2
}
public class NativeHistorySchemaPageV2 internal constructor(items: List<NativeHistorySchemaItemV2>) {
    private val values = items.toList()
    public val items: List<NativeHistorySchemaItemV2> get() = values.toList()
    override fun toString(): String = "NativeHistorySchemaPageV2([REDACTED])"
}


public sealed interface NativeHistoryPhysicalCellV2 {
    public data object Null : NativeHistoryPhysicalCellV2
    public data class Integer(public val value: Long) : NativeHistoryPhysicalCellV2
    public class Text(bytes: ByteArray) : NativeHistoryPhysicalCellV2 {
        private val value = bytes.copyOf()
        public val byteCount: Int get() = value.size
        public fun bytes(): ByteArray = value.copyOf()
        public fun clear(): Unit = value.fill(0)
        override fun toString(): String = "NativeHistoryTextV2([REDACTED])"
    }
    public class Blob(bytes: ByteArray) : NativeHistoryPhysicalCellV2 {
        private val value = bytes.copyOf()
        public val byteCount: Int get() = value.size
        public fun bytes(): ByteArray = value.copyOf()
        public fun clear(): Unit = value.fill(0)
        override fun toString(): String = "NativeHistoryBlobV2([REDACTED])"
    }
}
public class NativeHistoryPhysicalRowV2(public val table: NativeHistoryTableV2, public val rowId: Long,
    cells: List<NativeHistoryPhysicalCellV2>) {
    private val values = cells.toList()
    public val cells: List<NativeHistoryPhysicalCellV2> get() = values.toList()
    init { require(values.size == NativeHistoryPhysicalReadProfileV2.columns(table).size) }
    public fun clear(): Unit = values.forEach { cell -> when (cell) {
        is NativeHistoryPhysicalCellV2.Text -> cell.clear()
        is NativeHistoryPhysicalCellV2.Blob -> cell.clear()
        else -> Unit
    } }
    override fun toString(): String = "NativeHistoryPhysicalRowV2(table=${table.tag}, [REDACTED])"
}

/** One physical frame, also used by native opening of original protected historical images. */
public object NativeHistoryPhysicalRowWireV2 {
    private const val ROW = "ukkera.runtime-history/physical-row/v2"
    private const val KEY = "ukkera.runtime-history/physical-key/v2"
    public fun encode(row: NativeHistoryPhysicalRowV2): ByteArray {
        val writer = NativeHistoryWriterV2(NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES.toInt())
        return try {
            writer.string(ROW); writer.int(row.table.tag); writer.long(row.rowId); writer.int(row.cells.size)
            NativeHistoryPhysicalReadProfileV2.columns(row.table).zip(row.cells).forEach { (column,cell) -> put(writer,column,cell) }
            writer.finish()
        } finally { writer.clear() }
    }
    public fun decode(bytes: ByteArray): NativeHistoryPhysicalRowV2 {
        require(bytes.size.toLong() <= NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES)
        val reader = NativeHistoryReaderV2(bytes)
        val cells = mutableListOf<NativeHistoryPhysicalCellV2>()
        return try {
            require(reader.string(64) == ROW)
            val table = NativeHistoryTableV2.fromTag(reader.int()); val rowId = reader.long()
            val columns = NativeHistoryPhysicalReadProfileV2.columns(table); require(reader.int() == columns.size)
            columns.forEach { cells += get(reader,it) }
            reader.end()
            NativeHistoryPhysicalRowV2(table,rowId,cells).also { cells.clear() } // Transfer only after exact EOF.
        } finally { cells.forEach(::clearCell); reader.clear() }
    }
    public fun key(row: NativeHistoryPhysicalRowV2): ByteArray {
        val columns = NativeHistoryPhysicalReadProfileV2.columns(row.table)
        return encodeKey(row.table,row.table.keyColumns.map { name -> row.cells[columns.indexOfFirst { it.name == name }] })
    }
    public fun encodeKey(table: NativeHistoryTableV2, cells: List<NativeHistoryPhysicalCellV2>): ByteArray {
        val columns = table.keyColumns.map { key -> NativeHistoryPhysicalReadProfileV2.columns(table).single { it.name == key } }
        require(columns.size == cells.size)
        val writer = NativeHistoryWriterV2(NativeHistoryPhysicalReadProfileV2.MAX_KEY_BYTES)
        return try {
            writer.string(KEY); writer.int(table.tag); writer.int(columns.size)
            columns.zip(cells).forEach { (column,cell) -> put(writer,column,cell) }; writer.finish()
        } finally { writer.clear() }
    }
    public fun requireKey(table: NativeHistoryTableV2, bytes: ByteArray) {
        require(bytes.size <= NativeHistoryPhysicalReadProfileV2.MAX_KEY_BYTES)
        val reader = NativeHistoryReaderV2(bytes)
        try {
            require(reader.string(64) == KEY && reader.int() == table.tag)
            val columns = table.keyColumns.map { key -> NativeHistoryPhysicalReadProfileV2.columns(table).single { it.name == key } }
            require(reader.int() == columns.size)
            columns.forEach { clearCell(get(reader,it)) }; reader.end()
        } finally { reader.clear() }
    }
    private fun clearCell(cell: NativeHistoryPhysicalCellV2) {
        when (cell) {
            is NativeHistoryPhysicalCellV2.Text -> cell.clear()
            is NativeHistoryPhysicalCellV2.Blob -> cell.clear()
            else -> Unit
        }
    }
    public fun rowDigest(row: NativeHistoryPhysicalRowV2): PayloadDigest {
        val encoded = encode(row); return try { sha256(encoded) } finally { encoded.fill(0) }
    }
    private fun put(writer: NativeHistoryWriterV2, column: NativeHistoryColumnV2, cell: NativeHistoryPhysicalCellV2) {
        if (cell == NativeHistoryPhysicalCellV2.Null) { require(column.nullable); writer.byte(0); return }
        writer.byte(column.type.tag)
        when (cell) {
            is NativeHistoryPhysicalCellV2.Integer -> { require(column.type == NativeHistorySqliteTypeV2.INTEGER && cell.value in column.minimum..column.maximum); writer.long(cell.value) }
            is NativeHistoryPhysicalCellV2.Text -> { require(column.type == NativeHistorySqliteTypeV2.TEXT); val bytes = cell.bytes()
                try { requireBytes(column,bytes); writer.field(bytes) } finally { bytes.fill(0) } }
            is NativeHistoryPhysicalCellV2.Blob -> { require(column.type == NativeHistorySqliteTypeV2.BLOB); val bytes = cell.bytes()
                try { requireBytes(column,bytes); writer.field(bytes) } finally { bytes.fill(0) } }
            NativeHistoryPhysicalCellV2.Null -> error("unreachable")
        }
    }
    private fun get(reader: NativeHistoryReaderV2, column: NativeHistoryColumnV2): NativeHistoryPhysicalCellV2 {
        val tag = reader.byte()
        if (tag == 0) { require(column.nullable); return NativeHistoryPhysicalCellV2.Null }
        require(tag == column.type.tag)
        return when (column.type) {
            NativeHistorySqliteTypeV2.INTEGER -> NativeHistoryPhysicalCellV2.Integer(reader.long().also { require(it in column.minimum..column.maximum) })
            NativeHistorySqliteTypeV2.TEXT -> { val bytes = reader.field(column.maximum.toInt()); try { requireBytes(column,bytes); NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) } }
            NativeHistorySqliteTypeV2.BLOB -> { val bytes = reader.field(column.maximum.toInt()); try { requireBytes(column,bytes); NativeHistoryPhysicalCellV2.Blob(bytes) } finally { bytes.fill(0) } }
            else -> error("native.history.v2.storage.class")
        }
    }
    public fun requireBytes(column: NativeHistoryColumnV2, bytes: ByteArray) {
        require(bytes.size.toLong() in column.minimum..column.maximum)
        if (column.type == NativeHistorySqliteTypeV2.TEXT) {
            val value = bytes.decodeToString(throwOnInvalidSequence=true); require('\u0000' !in value)
            column.exactText?.let { require(value == it) }
            when {
                column.name == "scope" -> { val ids = value.split('/'); require(ids.size == 3 && value.length == 110); ids.forEach { DomainId(it) } }
                column.maximum == 36L -> { if (value.isNotEmpty() || column.minimum != 0L) DomainId(value) }
                column.minimum == 64L && column.maximum == 64L -> PayloadDigest(value)
                column.name == "dimensionKey" -> { if (value != "account") { require(value.startsWith("device:") && value.length == 43); DomainId(value.substring(7)) } }
                column.name == "key" -> require(value.all { it.code in 0x21..0x7e })
            }
        }
    }
}

/** Source-fixed executable/observed DDL shared by native capture and the Room schema census.
 * These descriptions are not native custody. Only the original driver's bootstrap installs
 * TEMP capture; only the original fresh Room callback installs persistent writer guards. */
public object NativeHistoryOwnedSqlV2 {
    public data class Object(public val type: String, public val table: String, public val sql: String?)
    private fun quoted(value: String): String = "`$value`"
    private const val FRAME_REQUIRED = "SELECT CASE WHEN (SELECT count(*) FROM runtime_history_write_frame_v2 WHERE singleton=1) != 1 THEN RAISE(ABORT, 'original writer frame required') END; "
    public fun normalized(sql: String?): String? = sql?.replaceFirst(
        Regex("^CREATE (?:TEMP | )?(TABLE|TRIGGER|(?:UNIQUE )?INDEX) (?:IF NOT EXISTS )?"), "CREATE $1 ")

    public val persistentGuards: Map<String,Object> = buildMap {
        fun trigger(table: NativeHistoryTableV2, name: String, operation: String, body: String) {
            put(name,Object("trigger",table.sqlName,"CREATE TRIGGER `$name` BEFORE $operation ON `${table.sqlName}` BEGIN $body"+"END"))
        }
        for(table in NativeHistoryTableV2.entries) {
            val columns=NativeHistoryPhysicalReadProfileV2.columns(table)
            for(operation in listOf("INSERT","UPDATE","DELETE")) {
                val name="__rh_guard_${table.tag}_${operation.lowercase()}"
                val body=when {
                    table.immutable && operation!="INSERT" -> "SELECT RAISE(ABORT, 'immutable original history'); "
                    table==NativeHistoryTableV2.WRITE_FRAME && operation=="UPDATE" -> "SELECT RAISE(ABORT, 'original frame immutable'); "
                    table==NativeHistoryTableV2.WRITE_FRAME && operation=="INSERT" ->
                        "SELECT CASE WHEN NEW.singleton!=1 OR EXISTS(SELECT 1 FROM runtime_history_write_frame_v2) THEN RAISE(ABORT, 'original frame already exists') END; "
                    else -> FRAME_REQUIRED
                }
                val aliases=when(operation) { "INSERT" -> listOf("NEW"); "DELETE" -> listOf("OLD"); else -> listOf("OLD","NEW") }
                val types=if(table.application) aliases.joinToString("") { alias -> columns.joinToString("") { column ->
                    val value="$alias.${quoted(column.name)}"
                    val correct=if(column.type==NativeHistorySqliteTypeV2.INTEGER)
                        "typeof($value)='integer' AND $value BETWEEN ${column.minimum} AND ${column.maximum}"
                    else "typeof($value)='${column.type.name.lowercase()}' AND length(CAST($value AS BLOB)) BETWEEN ${column.minimum} AND ${column.maximum}"
                    val condition=if(column.nullable) "($value IS NULL OR ($correct))" else "($correct)"
                    "SELECT CASE WHEN NOT $condition THEN RAISE(ABORT, 'original physical column shape') END; "
                } } else ""
                val capacity=if(table.application) "SELECT CASE WHEN (SELECT count(*) FROM runtime_history_capture_v2)>=${NativeHistoryPhysicalReadProfileV2.MAX_CHANGES} THEN RAISE(ABORT, 'original atomic history capacity') END; " else ""
                trigger(table,name,operation,body+types+capacity)
            }
            // Primary/alternate uniqueness never permits OR REPLACE to erase original rows.
            // Ordinary row-tip/head/index replacement uses an explicit captured delete/insert.
            if(table.tag>=18 && table!=NativeHistoryTableV2.WRITE_FRAME) {
                val identity=table.keyColumns.joinToString(" AND ") { "${quoted(it)} IS NEW.${quoted(it)}" }
                trigger(table,"__rh_no_replace_${table.tag}","INSERT",
                    "SELECT CASE WHEN EXISTS(SELECT 1 FROM `${table.sqlName}` WHERE ($identity) OR rowid=NEW.rowid) THEN RAISE(ABORT, 'original identity already exists') END; ")
            }
            if(table.application && !table.immutable && table.tag>=18) {
                val changed=(listOf("NEW.rowid IS NOT OLD.rowid")+table.keyColumns.map { "NEW.${quoted(it)} IS NOT OLD.${quoted(it)}" }).joinToString(" OR ")
                trigger(table,"__rh_identity_${table.tag}","UPDATE","SELECT CASE WHEN $changed THEN RAISE(ABORT, 'original identity relocation') END; ")
            }
        }
    }

    // Private TEMP-spool metadata is distinct from every captured application column.
    // OUTBOX/JOURNAL_FRAGMENT already contain an application column named ordinal.
    public const val IMAGE_ORDINAL_COLUMN: String = "__rh_ordinal"
    public const val IMAGE_SIDE_COLUMN: String = "__rh_side"
    public const val IMAGE_ROW_ID_COLUMN: String = "__rh_rowid"
    public val captureObjects: Map<String,Object> = buildMap {
        for(table in NativeHistoryTableV2.entries.filter { it.application }) {
            val columns=NativeHistoryPhysicalReadProfileV2.columns(table)
            val spool="__rh_image_${table.tag}"
            check(columns.none { it.name in setOf(IMAGE_ORDINAL_COLUMN, IMAGE_SIDE_COLUMN, IMAGE_ROW_ID_COLUMN) })
            put(spool,Object("table",spool,"CREATE TEMP TABLE `$spool` (`$IMAGE_ORDINAL_COLUMN` INTEGER NOT NULL, `$IMAGE_SIDE_COLUMN` INTEGER NOT NULL, `$IMAGE_ROW_ID_COLUMN` INTEGER NOT NULL, "+
                columns.joinToString(", ") { "`${it.name}` ${it.type.name}" }+", PRIMARY KEY(`$IMAGE_ORDINAL_COLUMN`, `$IMAGE_SIDE_COLUMN`))"))
            put("sqlite_autoindex_${spool}_1",Object("index",spool,null))
            for((operation,tag) in listOf("INSERT" to 1,"UPDATE" to 2,"DELETE" to 3)) {
                if(table.immutable && operation!="INSERT") continue
                val old=if(operation!="INSERT") "OLD" else null
                val new=if(operation!="DELETE") "NEW" else null
                fun key(alias: String?,index: Int,integer: Boolean=false): String {
                    if(alias==null || index>=table.keyColumns.size) return "NULL"
                    val name=table.keyColumns[index]; val type=columns.single { it.name==name }.type
                    return if(integer) { if(type==NativeHistorySqliteTypeV2.INTEGER) "$alias.`$name`" else "NULL" }
                        else if(type==NativeHistorySqliteTypeV2.TEXT) "CAST($alias.`$name` AS BLOB)" else "NULL"
                }
                val values=listOf("(SELECT COALESCE(MAX(captureOrdinal),0)+1 FROM runtime_history_capture_v2)",
                    "(SELECT frameId FROM runtime_history_write_frame_v2 WHERE singleton=1)",table.tag.toString(),tag.toString(),
                    old?.let { "$it.rowid" } ?: "NULL",new?.let { "$it.rowid" } ?: "NULL",key(old,0),key(old,1),key(old,1,true),key(old,2),key(old,2,true),
                    key(new,0),key(new,1),key(new,1,true),key(new,2),key(new,2,true))
                val images=listOfNotNull(old?.let { it to 0 },new?.let { it to 1 }).joinToString("") { (alias,side) ->
                    "INSERT INTO `$spool` VALUES ((SELECT MAX(captureOrdinal) FROM runtime_history_capture_v2), $side, $alias.rowid, "+
                        columns.joinToString(", ") { "$alias.`${it.name}`" }+"); "
                }
                val name="__rh_${table.tag}_${operation.lowercase()}"
                put(name,Object("trigger",table.sqlName,"CREATE TEMP TRIGGER `$name` AFTER $operation ON main.`${table.sqlName}` BEGIN "+
                    FRAME_REQUIRED+"INSERT INTO runtime_history_capture_v2 VALUES (${values.joinToString(", ")}); "+images+"END"))
            }
        }
    }
    public const val TRACKING: String = "room_table_modification_log"
    public const val TRACKING_TABLE_SQL: String = "CREATE TEMP TABLE IF NOT EXISTS room_table_modification_log (table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)"
    /** In this service no DAO flow/observer is registered. An unknown or look-alike trigger
     * never passes by name prefix. A future observer registry must bind generated table IDs. */
    public fun isTrackingTable(type: String,name: String,table: String,sql: String?): Boolean =
        type=="table" && name==TRACKING && table==TRACKING && normalized(sql)==normalized(TRACKING_TABLE_SQL)
    public val housekeepingStatements: Set<String> = setOf(
        "SELECT * FROM room_table_modification_log WHERE invalidated = 1",
        "UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1",
    )
}
