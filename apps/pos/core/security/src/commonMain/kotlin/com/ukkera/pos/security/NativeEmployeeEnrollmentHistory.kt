package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Read DATA selected by the actual original native employee registry owner before opening
 * its one raw history session. No public constructor, parser, source selector or accepting
 * callback exists. Neither this signed record nor these scalar heads grant client custody.
 * The same native read must observe every original protected seed; the concrete full scanner
 * must additionally join its entire event/lineage/authentication/current-state history, and
 * the actual read close/reobservation/Room admission must consume this exact selection. */
public class NativeEmployeeEnrollmentHistorySource internal constructor(
    internal val selectionIdentity: Any,
    internal val record: NativeEmployeeEnrollmentRecordV1,
) {
    public val originalActionId: DomainId = record.originalActionId
    public val requested: LocalEmployeeEnrollmentDefinition = record.requested()
    public val evidence: LocalEmployeeEnrollmentEffectEvidence = record.effect!!.evidence.copyBytes().let { bytes ->
        try { LocalEmployeeEnrollmentEffectEvidence.decode(bytes) } finally { bytes.fill(0) }
    }
    public val preparationParent: NativeHistoryRecordedHeadV2 = record.target.preparationParent
    public val child: NativeHistoryRecordedHeadV2 = record.effect!!.child
    public val frameId: DomainId = record.effect!!.frameId
    public val originalIntentDigest: PayloadDigest = record.effect!!.originalIntentDigest
    public val authorityDigest: PayloadDigest = LocalEmployeeEnrollmentAuthorityReceipt(record).authorityDigest
    public val finalObservation: LocalAuthenticationObservation = record.terminal!!.finalObservation!!
    public val originalCohostDeviceId: DomainId = record.target.coordinates.deviceId

    // Private copied expectations: the public evidence remains read DATA, not an accepting
    // selector for this native node's genuine physical/key/plaintext observation.
    private val actualImages = evidence.images.toList()
    private data class SeedAfter(val rowId: Long, val digest: PayloadDigest)
    private val after = mutableListOf<SeedAfter>()
    private var eventObserved = false
    private var seedsObserved = 0
    private var selectedEventEnded = false

    init {
        require(record.phase == LocalEmployeeEnrollmentPhase.CONSUMED &&
            record.terminal!!.kind == NativeEmployeeEnrollmentTerminalKindV1.COMMITTED_JOINED_CHILD)
        val effect = record.effect!!
        val dispatch = record.dispatch!!
        val prepared = record.preparedHead
        val approved = record.approvedHead!!
        require(evidence.request == requested.request && evidence.definitionDigest == requested.definitionDigest &&
            evidence.authorityDigest == authorityDigest && effect.dependenciesDigest == digest(localEmployeeEnrollmentDependenciesV1(evidence)) &&
            effect.originalFactsDigest == digest(evidence.copyWireBytes()) &&
            dispatch.originalAnchorDigest == record.target.preparationAnchorDigest &&
            child.historySequence == preparationParent.historySequence + 1L && child.commandEpoch == preparationParent.commandEpoch &&
            child.commandEpoch == record.target.authorityEpoch.value && child.commandSequence == preparationParent.commandSequence &&
            child.commandDigest == preparationParent.commandDigest && child.installationGenesisDigest == preparationParent.installationGenesisDigest &&
            child.roleCommitment == preparationParent.roleCommitment && child.recoveryCommitment == preparationParent.recoveryCommitment &&
            evidence.admissionDigests.map { it.first } == requested.admissions.map { it.deviceId })
        val start = prepared.observation
        require(dispatch.observation.issuerContextId == start.issuerContextId &&
            evidence.protectedPreflight.issuerContextId == start.issuerContextId && finalObservation.issuerContextId == start.issuerContextId &&
            dispatch.observation.moment.bootId == start.moment.bootId && evidence.eventAt.bootId == start.moment.bootId &&
            evidence.protectedPreflight.moment.bootId == start.moment.bootId && finalObservation.moment.bootId == start.moment.bootId &&
            dispatch.observation.moment.elapsedMillis >= start.moment.elapsedMillis &&
            evidence.eventAt.elapsedMillis >= dispatch.observation.moment.elapsedMillis &&
            evidence.protectedPreflight.moment.elapsedMillis >= evidence.eventAt.elapsedMillis &&
            finalObservation.moment.elapsedMillis >= evidence.protectedPreflight.moment.elapsedMillis &&
            finalObservation.moment.elapsedMillis < approved.deadlineElapsedMillis &&
            requested.definition.tokenPolicy.workLifetimeMillis <= Long.MAX_VALUE - finalObservation.moment.elapsedMillis)
    }

    /** Called only by this same native storage's source-fixed original event node. */
    internal fun observeOriginalEvent(sequence: Long, commitment: PayloadDigest) {
        require(!eventObserved && !selectedEventEnded && sequence == child.historySequence && commitment == child.historyDigest)
        eventObserved = true
    }

    /** These are the actual raw native cells, not a supplied absence/fate Boolean. */
    internal fun observeOriginalMutation(row: NativeHistoryPhysicalRowV2) {
        require(eventObserved && !selectedEventEnded && row.table == NativeHistoryTableV2.ROW_VERSION &&
            seedsObserved == after.size && after.size < actualImages.size)
        val cells = namedCells(row)
        val ordinal = cells.number("ordinal")
        val expected = actualImages[after.size]
        val table = NativeHistoryTableV2.entries.single { it.protectedTable == expected.table }
        require(cells.number("sequence") == child.historySequence && ordinal == after.size.toLong() &&
            cells.number("tableTag") == table.tag.toLong() && cells.number("version") == 1L &&
            cells.number("mutationKind") == 1L && cells.number("afterKind") == (if (table.immutable) 2L else 1L) &&
            cells["previousSequence"] == NativeHistoryPhysicalCellV2.Null &&
            cells["previousOrdinal"] == NativeHistoryPhysicalCellV2.Null && cells["previousVersion"] == NativeHistoryPhysicalCellV2.Null &&
            cells["beforeRowId"] == NativeHistoryPhysicalCellV2.Null && cells["beforeDigest"] == NativeHistoryPhysicalCellV2.Null)
        val key = cells.blob("key")
        val expectedKey = expected.nativeHistoryKey(requested.request.scope)
        try { require(key.contentEquals(expectedKey)) } finally { key.fill(0); expectedKey.fill(0) }
        after += SeedAfter(cells.number("afterRowId"), PayloadDigest(cells.text("afterDigest")))
    }

    /** Only the actual native AES-GCM opening of the selected historical IMAGE/immutable
     * row calls this. Every digest/key/revision/storage binding is joined before delivery. */
    internal fun observeOriginalProtectedSeed(row: NativeHistoryPhysicalRowV2, binding: ProtectedLocalBinding, plaintext: ByteArray) {
        require(eventObserved && !selectedEventEnded && seedsObserved < after.size && after.size == seedsObserved + 1)
        val expected = actualImages[seedsObserved]
        val version = after[seedsObserved]
        require(row.table.protectedTable == expected.table && row.rowId == version.rowId &&
            NativeHistoryPhysicalRowWireV2.rowDigest(row) == version.digest && expected.matchesNativeHistoryBinding(binding, requested.request.scope) &&
            sha256(plaintext) == expected.plaintextDigest)
        val key = NativeHistoryPhysicalRowWireV2.key(row)
        val expectedKey = expected.nativeHistoryKey(requested.request.scope)
        try { require(key.contentEquals(expectedKey)) } finally { key.fill(0); expectedKey.fill(0) }
        seedsObserved++
    }

    internal fun finishOriginalSelectedEvent() {
        require(eventObserved && !selectedEventEnded && after.size == actualImages.size && seedsObserved == actualImages.size)
        selectedEventEnded = true
    }
    internal fun requireActualSeedTraversal() { require(selectedEventEnded && seedsObserved == actualImages.size) }
    override fun toString(): String = "NativeEmployeeEnrollmentHistorySource([REDACTED])"

    private companion object {
        fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
        fun namedCells(row: NativeHistoryPhysicalRowV2): Map<String, NativeHistoryPhysicalCellV2> =
            NativeHistoryPhysicalReadProfileV2.columns(row.table).map { it.name }.zip(row.cells).toMap()
        fun Map<String, NativeHistoryPhysicalCellV2>.number(name: String): Long = (get(name) as NativeHistoryPhysicalCellV2.Integer).value
        fun Map<String, NativeHistoryPhysicalCellV2>.blob(name: String): ByteArray = (get(name) as NativeHistoryPhysicalCellV2.Blob).bytes()
        fun Map<String, NativeHistoryPhysicalCellV2>.text(name: String): String {
            val bytes = (get(name) as NativeHistoryPhysicalCellV2.Text).bytes()
            return try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
        }
    }
}

/** Exact source/session identity retained by native, not exposed as a transferable proof. */
internal class NativeEmployeeEnrollmentHistorySelection(
    val nativeOwner: Any,
    val original: OriginalNativeHistorySnapshot,
    val ledgerDigest: PayloadDigest?,
    records: List<NativeEmployeeEnrollmentRecordV1>,
    retainedRecords: List<NativeEmployeeEnrollmentRecordV1>,
) {
    val retainedRecords = retainedRecords.toList()
    val identity = Any()
    val sources = records.map { NativeEmployeeEnrollmentHistorySource(identity, it) }
    private var prepassEnded = false
    init {
        require(this.retainedRecords.size <= LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS &&
            sources.size <= LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS &&
            sources.map { it.child.historySequence }.distinct().size == sources.size &&
            sources.map { it.requested.request.targetId }.distinct().size == sources.size &&
            sources == sources.sortedBy { it.child.historySequence })
    }
    fun finishActualPrepass() {
        require(!prepassEnded)
        sources.forEach { it.requireActualSeedTraversal() }
        prepassEnded = true
    }
    fun requireActualPrepass() { require(prepassEnded); sources.forEach { it.requireActualSeedTraversal() } }
    override fun toString(): String = "NativeEmployeeEnrollmentHistorySelection([REDACTED])"
}

internal fun LocalEmployeeEnrollmentProtectedImage.matchesNativeHistoryBinding(binding: ProtectedLocalBinding, scope: BranchScope): Boolean =
    binding.key.scope == scope && binding.key.table == table && binding.key.recordId == recordId && binding.revision == Revision(0) &&
        ((binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey ?: "") == dimension

internal fun LocalEmployeeEnrollmentProtectedImage.nativeHistoryKey(scope: BranchScope): ByteArray {
    val table = NativeHistoryTableV2.entries.single { it.protectedTable == this.table }
    val parts = listOf("${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}", recordId.value) +
        if (dimension.isEmpty()) emptyList() else listOf(dimension)
    val cells = parts.map { value ->
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        try { NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) }
    }
    return try { NativeHistoryPhysicalRowWireV2.encodeKey(table, cells) } finally { cells.forEach { it.clear() } }
}
