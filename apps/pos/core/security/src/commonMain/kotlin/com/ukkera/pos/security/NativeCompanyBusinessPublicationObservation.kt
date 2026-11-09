package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * One complete native ledger observation registered before the actual Room BEGIN. The
 * opaque frame identity cannot be supplied by a caller, transferred to another command,
 * or reconstructed from a manifest. Empty membership is positive observed DATA; missing
 * support or a signing intent supplies no observation.
 */
public class NativeCompanyBusinessPublicationObservation internal constructor(
    internal val nativeFrame: SelfOwnedNativeRoomFrame,
    public val snapshot: NativeCompanyBusinessPublicationSnapshot,
) {
    public val frameId: DomainId = nativeFrame.frameId
    public val originalAnchorDigest: PayloadDigest = nativeFrame.original.recordDigest
    public val originalAllocationDigest: PayloadDigest = nativeFrame.original.record.allocation.digest
    internal val manifest: NativeBusinessPublicationManifest = NativeBusinessPublicationManifest(snapshot)
    public val observationDigest: PayloadDigest = NativeBusinessPublicationObservationWire.observationDigest(
        nativeFrame.original.record.allocation.coordinates.scope, originalAllocationDigest, originalAnchorDigest,
        nativeFrame.original.record.parent, frameId, manifest)

    init { require(snapshot.company == nativeFrame.original.record.allocation.coordinates.scope.company()) }

    /** The exact three UTF-8 fields in the existing length-framed dependency grammar. */
    public fun dependencyFields(): List<String> = listOf(
        NativeBusinessPublicationObservationWire.DEPENDENCY_DOMAIN, frameId.value, observationDigest.value)
    override fun toString(): String = "NativeCompanyBusinessPublicationObservation([REDACTED])"
}

/** Inert manifest DATA. Only a native-selected, reauthenticated source can expose contents. */
internal data class NativeBusinessPublicationMember(
    val kind: CompanyBusinessPublicationKind, val originalActionId: DomainId, val publicationId: DomainId,
    val entityId: DomainId, val signingKeyId: DomainId, val contentDigest: PayloadDigest,
) {
    constructor(output: CompanyBusinessPublicationOutput) : this(output.proposal.kind, output.originalActionId,
        output.proposal.publicationId, output.proposal.entityId, output.signingKeyId, output.proposal.contentDigest)
}

internal class NativeBusinessPublicationManifest(
    val company: CompanyScope, val rootFingerprint: String, val nativeLedgerPresent: Boolean,
    val generation: Long, members: List<NativeBusinessPublicationMember>,
) {
    // Preserve the captured native sequence: first-conflict decisions depend on its order.
    private val retained = members.toList()
    val members: List<NativeBusinessPublicationMember> get() = retained.toList()
    constructor(snapshot: NativeCompanyBusinessPublicationSnapshot) : this(snapshot.company, snapshot.rootFingerprint,
        snapshot.nativeLedgerPresent, snapshot.generation,
        snapshot.completedPublications.map(::NativeBusinessPublicationMember))
    init {
        require(rootFingerprint.length == 64 && rootFingerprint.all { it in "0123456789abcdef" } &&
            generation >= 0 && retained.size <= CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        require(nativeLedgerPresent || (generation == 0L && retained.isEmpty()))
        require(retained.map { it.originalActionId }.distinct().size == retained.size &&
            retained.map { it.publicationId }.distinct().size == retained.size &&
            retained.map { it.signingKeyId }.distinct().size == retained.size)
    }
    override fun toString(): String = "NativeBusinessPublicationManifest([REDACTED])"
}

/**
 * Authenticated pending/settled original DATA, never a standalone history or authority
 * certificate. Native retains this only after exact-object and full-dependency validation.
 * The signed original anchor determines which proof exists; parsed copies cannot mint one.
 */
public data class NativeHistoryBusinessPublicationObservationProof internal constructor(
    public val scope: BranchScope,
    public val originalAllocationDigest: PayloadDigest,
    public val originalAnchorDigest: PayloadDigest,
    public val preparationParent: NativeHistoryRecordedHeadV2,
    public val frameId: DomainId,
    public val observationDigest: PayloadDigest,
    public val child: NativeHistoryRecordedHeadV2,
    public val originalIntentDigest: PayloadDigest,
    public val purposeTag: Int,
    public val originalRequestId: DomainId,
    public val originalOperationId: DomainId,
    public val originalActorId: DomainId,
    public val semanticDigest: PayloadDigest,
    public val dependenciesDigest: PayloadDigest,
    public val originalFactsDigest: PayloadDigest,
    private val capturedManifest: SelfOwnedOctetsV1,
) {
    internal val manifest: NativeBusinessPublicationManifest = capturedManifest.copyBytes().let { bytes ->
        try { NativeBusinessPublicationObservationWire.decodeManifest(bytes) } finally { bytes.fill(0) }
    }
    init {
        require(purposeTag in 2..3 && originalRequestId == originalOperationId &&
            child.historySequence == preparationParent.historySequence + 1L &&
            child.installationGenesisDigest == preparationParent.installationGenesisDigest &&
            child.commandSequence > 0L && manifest.company == scope.company() &&
            observationDigest == NativeBusinessPublicationObservationWire.observationDigest(
                scope, originalAllocationDigest, originalAnchorDigest, preparationParent, frameId, manifest))
    }
    internal fun copyManifestBytes(): ByteArray = capturedManifest.copyBytes()
    internal fun requireIntent(intent: NativeHistoryOriginalIntentV2) {
        require(intent.purposeTag == purposeTag && intent.originalRequestId == originalRequestId &&
            intent.originalOperationId == originalOperationId && intent.originalActorId == originalActorId &&
            intent.originalRequestDigest == semanticDigest && intent.dependenciesDigest == dependenciesDigest &&
            intent.originalFactsDigest == originalFactsDigest &&
            NativeBusinessPublicationObservationWire.intentDigest(intent) == originalIntentDigest)
    }
    override fun toString(): String = "NativeHistoryBusinessPublicationObservationProof([REDACTED])"
}

/**
 * Original membership selected solely from the real native reader's signed anchor and
 * reauthenticated immutable completed outputs. Later approvals are excluded by the
 * captured manifest, regardless of today's ledger generation or ordering.
 */
public class NativeCompanyBusinessPublicationHistorySource internal constructor(
    internal val selectionIdentity: Any,
    internal val proof: NativeHistoryBusinessPublicationObservationProof,
    private val retainedSnapshot: NativeCompanyBusinessPublicationSnapshot,
) {
    public val scope: BranchScope get() = proof.scope
    public val originalAllocationDigest: PayloadDigest get() = proof.originalAllocationDigest
    public val originalAnchorDigest: PayloadDigest get() = proof.originalAnchorDigest
    public val preparationParent: NativeHistoryRecordedHeadV2 get() = proof.preparationParent
    public val frameId: DomainId get() = proof.frameId
    public val observationDigest: PayloadDigest get() = proof.observationDigest
    public val child: NativeHistoryRecordedHeadV2 get() = proof.child
    public val originalIntentDigest: PayloadDigest get() = proof.originalIntentDigest
    public val purposeTag: Int get() = proof.purposeTag
    public val originalRequestId: DomainId get() = proof.originalRequestId
    public val originalOperationId: DomainId get() = proof.originalOperationId
    public val originalActorId: DomainId get() = proof.originalActorId
    public val semanticDigest: PayloadDigest get() = proof.semanticDigest
    public val dependenciesDigest: PayloadDigest get() = proof.dependenciesDigest
    private var eventObserved = false
    /** Set only by the actual source-fixed EVENTS traversal. A pending proof alone is
     * not an observed event, COMMIT, absent original, or native settlement. */
    public val hasActualOriginalEvent: Boolean get() = eventObserved
    public val snapshot: NativeCompanyBusinessPublicationSnapshot
        get() { require(eventObserved); return retainedSnapshot }
    init {
        val expected = NativeBusinessPublicationObservationWire.encodeManifest(proof.manifest)
        val actual = NativeBusinessPublicationObservationWire.encodeManifest(NativeBusinessPublicationManifest(retainedSnapshot))
        try { require(expected.contentEquals(actual)) } finally { expected.fill(0); actual.fill(0) }
    }
    public fun dependencyFields(): List<String> = listOf(
        NativeBusinessPublicationObservationWire.DEPENDENCY_DOMAIN, frameId.value, observationDigest.value)
    internal fun observeOriginalEvent(sequence: Long, digest: PayloadDigest, transactionId: DomainId,
        parentSequence: Long, parentDigest: PayloadDigest) {
        require(!eventObserved && sequence == child.historySequence && digest == child.historyDigest &&
            transactionId == frameId && parentSequence == preparationParent.historySequence &&
            parentDigest == preparationParent.historyDigest)
        eventObserved = true
    }
    internal fun requireActualTraversal() { require(eventObserved) }
    override fun toString(): String = "NativeCompanyBusinessPublicationHistorySource([REDACTED])"
}

internal class NativeBusinessPublicationHistorySelection(
    val nativeOwner: Any, val original: OriginalNativeHistorySnapshot, val ledgerIdentity: Any?,
    proofs: List<NativeHistoryBusinessPublicationObservationProof>, snapshots: List<NativeCompanyBusinessPublicationSnapshot>,
) {
    val identity = Any()
    val sources = proofs.zip(snapshots).map { (proof, snapshot) ->
        NativeCompanyBusinessPublicationHistorySource(identity, proof, snapshot)
    }
    init {
        require(proofs.size == snapshots.size && this.sources.size <= NativeBusinessPublicationObservationWire.MAX_OBSERVATIONS &&
            this.sources.all { it.selectionIdentity === identity } &&
            this.sources.map { it.child.historySequence }.distinct().size == this.sources.size &&
            this.sources == this.sources.sortedBy { it.child.historySequence })
        require(proofs == original.record.businessPublicationObservations +
            listOfNotNull(original.record.pending?.businessPublications))
    }
    fun requireActualTraversal() {
        val pending = original.record.pending?.businessPublications
        sources.forEach {
            // A pending capsule is optional DATA reconciliation. Missing its actual event
            // cannot become a source or remove the existing unknown-native-outcome hold.
            if (it.proof != pending || it.hasActualOriginalEvent) it.requireActualTraversal()
        }
    }
    override fun toString(): String = "NativeBusinessPublicationHistorySelection([REDACTED])"
}

/** Closed native observation grammar. It adds no business-ledger or original-intent fields. */
internal object NativeBusinessPublicationObservationWire {
    const val DEPENDENCY_DOMAIN = "native-business-publications/v1"
    const val MAX_OBSERVATIONS = 4096
    const val MAX_MANIFEST_BYTES = 96 * 1024
    const val MAX_PROOF_BYTES = 100 * 1024
    private const val PROFILE = "ukkera.native-business-publication-observation/v1"

    fun encodeManifest(value: NativeBusinessPublicationManifest): ByteArray = write("membership") {
        id(value.company.organizationId); id(value.company.companyId); string(value.rootFingerprint)
        bool(value.nativeLedgerPresent); long(value.generation); int(value.members.size)
        value.members.forEach {
            string(it.kind.name); id(it.originalActionId); id(it.publicationId); id(it.entityId)
            id(it.signingKeyId); digest(it.contentDigest)
        }
    }
    fun decodeManifest(bytes: ByteArray): NativeBusinessPublicationManifest = read(bytes, "membership") {
        val company = CompanyScope(id(), id()); val root = string(64); val present = bool(); val generation = long()
        val count = int(); require(count in 0..CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
        NativeBusinessPublicationManifest(company, root, present, generation, List(count) {
            val kindName = string(64)
            NativeBusinessPublicationMember(CompanyBusinessPublicationKind.entries.single { it.name == kindName },
                id(), id(), id(), id(), digest())
        })
    }.also { value ->
        val canonical = encodeManifest(value)
        try { require(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
    }
    fun observationDigest(branchScope: BranchScope, allocation: PayloadDigest, anchor: PayloadDigest,
        parent: NativeHistoryRecordedHeadV2?, frameId: DomainId, manifest: NativeBusinessPublicationManifest): PayloadDigest {
        val bytes = write("original-frame-membership") {
            scope(branchScope); digest(allocation); digest(anchor); bool(parent != null); parent?.let { head(it) }
            id(frameId); owned(encodeManifest(manifest))
        }
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    fun encodeProof(value: NativeHistoryBusinessPublicationObservationProof): ByteArray = write("original-child") {
        scope(value.scope); digest(value.originalAllocationDigest); digest(value.originalAnchorDigest)
        head(value.preparationParent); id(value.frameId); digest(value.observationDigest); head(value.child)
        digest(value.originalIntentDigest); int(value.purposeTag); id(value.originalRequestId)
        id(value.originalOperationId); id(value.originalActorId); digest(value.semanticDigest)
        digest(value.dependenciesDigest); digest(value.originalFactsDigest); owned(value.copyManifestBytes())
    }
    fun decodeProof(bytes: ByteArray): NativeHistoryBusinessPublicationObservationProof = read(bytes, "original-child") {
        val scope = scope(); val allocation = digest(); val anchor = digest(); val parent = head()
        val frameId = id(); val observation = digest(); val child = head(); val intent = digest(); val purpose = int()
        val request = id(); val operation = id(); val actor = id(); val semantic = digest(); val dependencies = digest(); val facts = digest()
        val manifest = field(MAX_MANIFEST_BYTES)
        try { NativeHistoryBusinessPublicationObservationProof(scope, allocation, anchor, parent, frameId,
            observation, child, intent, purpose, request, operation, actor, semantic, dependencies, facts, SelfOwnedOctetsV1(manifest)) }
        finally { manifest.fill(0) }
    }.also { value ->
        val canonical = encodeProof(value)
        try { require(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
    }
    fun intentDigest(value: NativeHistoryOriginalIntentV2): PayloadDigest {
        val bytes = SelfOwnedNativeHistoryWireV2.encodeIntent(value)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }

    /** Inspect full real dependencies without interpreting the unrelated SQL observation
     * domains. Exactly one complete canonical marker must match this registered frame. */
    fun requireDependency(bytes: ByteArray, fields: List<String>) {
        require(bytes.size in 1..SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES)
        val expected = framed(fields)
        val domain = DEPENDENCY_DOMAIN.encodeToByteArray()
        var at = 0; var matches = 0
        try {
            fun length(offset: Int): Int {
                require(offset >= 0 && bytes.size - offset >= 4)
                var size = 0L
                repeat(4) { size = (size shl 8) or (bytes[offset + it].toLong() and 255) }
                require(size <= bytes.size - offset - 4)
                return size.toInt()
            }
            while (at < bytes.size) {
                val size = length(at); at += 4; val end = at + size
                if (size >= 4) {
                    val first = length(at)
                    require(first <= size - 4)
                    if (first == domain.size && domain.indices.all { bytes[at + 4 + it] == domain[it] }) {
                        require(size == expected.size && expected.indices.all { bytes[at + it] == expected[it] })
                        matches++
                    }
                }
                at = end
            }
            require(at == bytes.size && matches == 1)
        } finally { expected.fill(0); domain.fill(0) }
    }

    /** Reserve the full possible proof before attach/BEGIN. An exhausted optional source
     * stays unavailable; it never prunes observations, blocks unrelated commands, or
     * supplies an empty history. Existing lifecycle/final reservations still apply. */
    fun requireBeforeRoomSubmission(record: NativeHistoryAnchorRecordV2) {
        if (record.parent == null) return
        require(record.pending == null && record.businessPublicationObservations.size < MAX_OBSERVATIONS)
        val encoded = SelfOwnedNativeHistoryWireV2.encodeRecord(record)
        try {
            val oldDomain = when (record.recordFormat) {
                SelfOwnedNativeHistoryWireV2.FORMAT -> SelfOwnedNativeHistoryWireV2.CAPACITY_RECORD_DOMAIN
                SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_FORMAT -> SelfOwnedNativeHistoryWireV2.FINAL_DECISION_RECORD_DOMAIN
                SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_FORMAT -> SelfOwnedNativeHistoryWireV2.PROTECTED_FINAL_RECORD_DOMAIN
                else -> SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_DOMAIN
            }
            val formatGrowth = SelfOwnedNativeHistoryWireV2.BUSINESS_PUBLICATION_RECORD_DOMAIN.length.toLong() -
                oldDomain.length + (if (record.recordFormat < 3) 4L else 0L) +
                (if (record.recordFormat < 4) 4L else 0L) + (if (record.recordFormat < 5) 4L else 0L)
            val pendingGrowth = 3L * 8 + 5L * (4 + 64) + 4L +
                SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES + 3L +
                maxOf(NativeHistoryFinalDecisionCapacity.MAX_DECLARATION_BYTES,
                    NativeHistoryFinalDecisionCapacity.MAX_PROTECTED_DECLARATION_BYTES) + 4L + MAX_PROOF_BYTES
            val settlementGrowth = maxOf(NativeHistoryFinalDecisionCapacity.MAX_DECISION_BYTES,
                NativeHistoryFinalDecisionCapacity.MAX_PROTECTED_DECISION_BYTES) + 4L + MAX_PROOF_BYTES
            require(encoded.size.toLong() <= SelfOwnedNativeHistoryWireV2.MAX_ANCHOR_BYTES.toLong() -
                formatGrowth - pendingGrowth - settlementGrowth)
        } finally { encoded.fill(0) }
    }

    private fun framed(fields: List<String>): ByteArray {
        val bytes = fields.map { it.encodeToByteArray(throwOnInvalidSequence = true) }
        return try {
            val writer = NativeHistoryWriterV2(1024)
            try { bytes.forEach { writer.field(it) }; writer.finish() } finally { writer.clear() }
        } finally { bytes.forEach { it.fill(0) } }
    }
    private fun write(domain: String, block: NativeHistoryWriterV2.() -> Unit): ByteArray {
        val writer = NativeHistoryWriterV2(MAX_PROOF_BYTES)
        return try { writer.string(PROFILE); writer.string(domain); writer.int(1); writer.block(); writer.finish() }
        finally { writer.clear() }
    }
    private fun <T> read(bytes: ByteArray, domain: String, block: NativeHistoryReaderV2.() -> T): T {
        require(bytes.size in 1..MAX_PROOF_BYTES)
        val reader = NativeHistoryReaderV2(bytes)
        return try {
            require(reader.string(64) == PROFILE && reader.string(64) == domain && reader.int() == 1)
            reader.block().also { reader.end() }
        } finally { reader.clear() }
    }
    private fun NativeHistoryWriterV2.owned(bytes: ByteArray) { try { field(bytes) } finally { bytes.fill(0) } }
    private fun NativeHistoryWriterV2.scope(value: BranchScope) { id(value.organizationId); id(value.companyId); id(value.branchId) }
    private fun NativeHistoryReaderV2.scope(): BranchScope = BranchScope(id(), id(), id())
    private fun NativeHistoryWriterV2.head(value: NativeHistoryRecordedHeadV2) {
        long(value.historySequence); digest(value.historyDigest); digest(value.installationGenesisDigest)
        long(value.commandEpoch); long(value.commandSequence); digest(value.commandDigest)
        digest(value.roleCommitment); digest(value.recoveryCommitment)
    }
    private fun NativeHistoryReaderV2.head(): NativeHistoryRecordedHeadV2 =
        NativeHistoryRecordedHeadV2(long(), digest(), digest(), long(), long(), digest(), digest(), digest())
}
