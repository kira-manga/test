package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Inert original DATA selected by the genuine native registry before its one raw read.
 * The same exhaustive EVENTS and LINEAGE traversal must observe every original row/image;
 * full semantic replay and actual close/reobservation/admission remain mandatory. */
public class NativeEmployeePolicyActivationHistorySource internal constructor(
    internal val selectionIdentity: Any,
    internal val record: NativeEmployeePolicyActivationRecordV1,
) {
    public val originalActionId: DomainId = record.originalActionId
    public val requested: LocalEmployeePolicyActivationDefinition = record.requested()
    public val evidence: LocalEmployeePolicyActivationEffectEvidence = checkNotNull(record.effect).evidence.copyBytes().let { bytes ->
        try { LocalEmployeePolicyActivationEffectEvidence.decode(bytes) } finally { bytes.fill(0) }
    }
    public val preparationParent: NativeHistoryRecordedHeadV2 = record.target.preparationParent
    public val child: NativeHistoryRecordedHeadV2 = checkNotNull(record.effect).child
    public val frameId: DomainId = checkNotNull(record.effect).frameId
    public val originalIntentDigest: PayloadDigest = checkNotNull(record.effect).originalIntentDigest
    public val authorityDigest: PayloadDigest = LocalEmployeePolicyActivationAuthorityReceipt(record).authorityDigest
    public val finalObservation: LocalAuthenticationObservation = checkNotNull(checkNotNull(record.terminal).finalObservation)

    private data class OriginalAfter(val rowId: Long, val digest: PayloadDigest, val mutationDigest: PayloadDigest)
    private val actualImages = evidence.images.toList()
    private val after = mutableListOf<OriginalAfter>()
    private val lineageObserved = BooleanArray(actualImages.size)
    private val imagesObserved = BooleanArray(actualImages.size)
    private var eventObserved = false

    init {
        require(record.phase == LocalEmployeePolicyActivationPhase.CONSUMED &&
            record.terminal?.kind == NativeEmployeePolicyActivationTerminalKindV1.COMMITTED_JOINED_CHILD)
        val effect = checkNotNull(record.effect); val dispatch = checkNotNull(record.dispatch)
        val prepared = record.preparedHead; val approved = checkNotNull(record.approvedHead)
        require(evidence.request == requested.request && evidence.definitionDigest == requested.definitionDigest &&
            evidence.authorityDigest == authorityDigest && effect.dependenciesDigest == digest(localEmployeePolicyActivationDependenciesV1(evidence)) &&
            effect.originalFactsDigest == digest(evidence.copyWireBytes()) &&
            actualImages[0].revision == requested.nextPolicy.revision &&
            actualImages[0].plaintextDigest == digest(requested.copyCanonicalPolicy()) &&
            dispatch.originalAnchorDigest == record.target.preparationAnchorDigest &&
            child.historySequence == preparationParent.historySequence + 1L && child.commandEpoch == preparationParent.commandEpoch &&
            child.commandEpoch == record.target.authorityEpoch.value && child.commandSequence == preparationParent.commandSequence &&
            child.commandDigest == preparationParent.commandDigest && child.installationGenesisDigest == preparationParent.installationGenesisDigest &&
            child.roleCommitment == preparationParent.roleCommitment && child.recoveryCommitment == preparationParent.recoveryCommitment)
        val start = prepared.observation
        require(dispatch.observation.issuerContextId == start.issuerContextId &&
            evidence.protectedPreflight.issuerContextId == start.issuerContextId && finalObservation.issuerContextId == start.issuerContextId &&
            dispatch.observation.moment.bootId == start.moment.bootId && evidence.eventAt.bootId == start.moment.bootId &&
            evidence.protectedPreflight.moment.bootId == start.moment.bootId && finalObservation.moment.bootId == start.moment.bootId &&
            dispatch.observation.moment.elapsedMillis >= start.moment.elapsedMillis &&
            evidence.eventAt.elapsedMillis >= dispatch.observation.moment.elapsedMillis &&
            evidence.protectedPreflight.moment.elapsedMillis >= evidence.eventAt.elapsedMillis &&
            finalObservation.moment.elapsedMillis >= evidence.protectedPreflight.moment.elapsedMillis &&
            finalObservation.moment.elapsedMillis < approved.deadlineElapsedMillis)
    }

    /** Only the source-fixed ordinary EVENTS node calls these original observations. */
    internal fun observeOriginalEvent(sequence: Long, commitment: PayloadDigest) {
        require(!eventObserved && sequence == child.historySequence && commitment == child.historyDigest)
        eventObserved = true
    }
    internal fun observeOriginalMutation(row: NativeHistoryPhysicalRowV2) {
        require(eventObserved && row.table == NativeHistoryTableV2.ROW_VERSION && after.size < actualImages.size)
        val cells = namedCells(row); val index = after.size; val expected = actualImages[index]
        val table = NativeHistoryTableV2.entries.single { it.protectedTable == expected.table }
        require(cells.number("sequence") == child.historySequence && cells.number("ordinal") == index.toLong() &&
            cells.number("tableTag") == table.tag.toLong() && cells.number("version") == expected.revision.value + 1L &&
            cells.number("mutationKind") == (if (table.immutable) 1L else 2L) &&
            cells.number("afterKind") == (if (table.immutable) 2L else 1L))
        if (table.immutable) require(cells["previousSequence"] == NativeHistoryPhysicalCellV2.Null &&
            cells["previousOrdinal"] == NativeHistoryPhysicalCellV2.Null && cells["previousVersion"] == NativeHistoryPhysicalCellV2.Null &&
            cells["beforeRowId"] == NativeHistoryPhysicalCellV2.Null && cells["beforeDigest"] == NativeHistoryPhysicalCellV2.Null)
        else require(cells.number("previousSequence") < child.historySequence && cells.number("previousOrdinal") >= 0L &&
            cells.number("previousVersion") == expected.revision.value && cells["beforeRowId"] is NativeHistoryPhysicalCellV2.Integer &&
            PayloadDigest(cells.text("beforeDigest")).value == cells.text("beforeDigest"))
        val key = cells.blob("key"); val expectedKey = expected.nativePolicyHistoryKey(requested.request.scope)
        try { require(key.contentEquals(expectedKey)) } finally { key.fill(0); expectedKey.fill(0) }
        after += OriginalAfter(cells.number("afterRowId"), PayloadDigest(cells.text("afterDigest")), NativeHistoryPhysicalRowWireV2.rowDigest(row))
    }

    /** Only the exhaustive LINEAGE node may select an already-observed original mutation. */
    internal fun observeOriginalLineageMutation(row: NativeHistoryPhysicalRowV2) {
        require(eventObserved && after.size == actualImages.size && row.table == NativeHistoryTableV2.ROW_VERSION)
        val cells = namedCells(row); val ordinal = cells.number("ordinal")
        require(cells.number("sequence") == child.historySequence && ordinal >= 0L && ordinal < actualImages.size.toLong())
        val index = ordinal.toInt()
        require(!lineageObserved[index] && !imagesObserved[index] && NativeHistoryPhysicalRowWireV2.rowDigest(row) == after[index].mutationDigest)
        lineageObserved[index] = true
    }

    /** The native AES-GCM opening of this original AFTER, never a current census row. */
    internal fun observeOriginalProtectedImage(row: NativeHistoryPhysicalRowV2, binding: ProtectedLocalBinding, plaintext: ByteArray) {
        val index = actualImages.indexOfFirst { it.table == binding.key.table && it.recordId == binding.key.recordId }
        require(index >= 0 && lineageObserved[index] && !imagesObserved[index])
        val expected = actualImages[index]; val version = after[index]
        require(binding.key.scope == requested.request.scope && binding.revision == expected.revision &&
            row.table.protectedTable == expected.table && row.rowId == version.rowId &&
            NativeHistoryPhysicalRowWireV2.rowDigest(row) == version.digest && sha256(plaintext) == expected.plaintextDigest)
        val key = NativeHistoryPhysicalRowWireV2.key(row); val expectedKey = expected.nativePolicyHistoryKey(requested.request.scope)
        try { require(key.contentEquals(expectedKey)) } finally { key.fill(0); expectedKey.fill(0) }
        imagesObserved[index] = true
    }
    internal fun requireActualTraversal() {
        require(eventObserved && after.size == actualImages.size && lineageObserved.all { it } && imagesObserved.all { it })
    }
    override fun toString(): String = "NativeEmployeePolicyActivationHistorySource([REDACTED])"

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

internal class NativeEmployeePolicyActivationHistorySelection(
    val nativeOwner: Any, val original: OriginalNativeHistorySnapshot, val ledgerDigest: PayloadDigest?,
    records: List<NativeEmployeePolicyActivationRecordV1>, retainedRecords: List<NativeEmployeePolicyActivationRecordV1>,
) {
    val retainedRecords = retainedRecords.toList()
    val identity = Any()
    val sources = records.map { NativeEmployeePolicyActivationHistorySource(identity, it) }
    init {
        require(this.retainedRecords.size <= LocalEmployeePolicyActivationProfileV1.MAXIMUM_ACTIONS &&
            sources.size <= LocalEmployeePolicyActivationProfileV1.MAXIMUM_ACTIONS &&
            sources.map { it.child.historySequence }.distinct().size == sources.size &&
            sources == sources.sortedBy { it.child.historySequence })
    }
    fun requireActualTraversal() { sources.forEach { it.requireActualTraversal() } }
    override fun toString(): String = "NativeEmployeePolicyActivationHistorySelection([REDACTED])"
}

internal fun LocalEmployeePolicyActivationProtectedImage.nativePolicyHistoryKey(scope: BranchScope): ByteArray {
    val table = NativeHistoryTableV2.entries.single { it.protectedTable == this.table }
    val cells = listOf("${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}", recordId.value).map { value ->
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        try { NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) }
    }
    return try { NativeHistoryPhysicalRowWireV2.encodeKey(table, cells) } finally { cells.forEach { it.clear() } }
}
