package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Inert originals selected by the genuine owner before its one exhaustive native read.
 * Exact EVENTS/LINEAGE images, semantic replay and actual close/reobservation/Room
 * admission are all required; a retained certificate or this DATA grants no authority. */
public class NativeDeviceAdmissionHistorySource internal constructor(
    internal val selectionIdentity: Any,
    internal val record: NativeDeviceAdmissionRecordV1,
) {
    public val originalActionId: DomainId = record.pairing.body.originalActionId
    public val requested: LocalDevicePairingDefinition = record.pairing.body.requested()
    public val evidence: LocalDeviceAdmissionEffectEvidence = checkNotNull(record.evidence())
    public val preparationParent: NativeHistoryRecordedHeadV2 = record.pairing.body.origin.preparationParent
    public val preparationAnchorDigest: PayloadDigest = record.pairing.body.origin.preparationAnchorDigest
    public val child: NativeHistoryRecordedHeadV2 = checkNotNull(record.effect).child
    public val frameId: DomainId = checkNotNull(record.effect).frameId
    public val originalIntentDigest: PayloadDigest = checkNotNull(record.effect).intentDigest
    public val authorityDigest: PayloadDigest = LocalDevicePairingApproval(record.pairing).authorityDigest
    public val dispatchObservation: LocalAuthenticationObservation = record.dispatchObservation
    public val finalObservation: LocalAuthenticationObservation = checkNotNull(record.finalObservation)
    public val originalCohostDeviceId: DomainId = record.pairing.body.origin.coordinates.deviceId
    public val settledAnchorDigest: PayloadDigest = checkNotNull(record.settledAnchorDigest)

    private data class OriginalAfter(val rowId: Long, val digest: PayloadDigest, val mutationDigest: PayloadDigest)
    private val actualImages = evidence.images.toList()
    private val after = mutableListOf<OriginalAfter>()
    private val lineageObserved = BooleanArray(actualImages.size)
    private val imagesObserved = BooleanArray(actualImages.size)
    private var eventObserved = false

    init {
        require(record.phase == NativeDeviceAdmissionPhaseV1.CONSUMED && requested.sdkIdentity != null &&
            requested.admission.revision == Revision(0) && evidence.request == requested.request &&
            evidence.definitionDigest == requested.definitionDigest && evidence.authorityDigest == authorityDigest &&
            evidence.admissionDigests.map { it.first } == requested.retainedAdmissions.map { it.deviceId } &&
            originalCohostDeviceId in evidence.admissionDigests.map { it.first } &&
            actualImages.first().plaintextDigest == digest(requested.copyCanonicalServiceAdmission()))
    }

    /** Only this source's ordinary native EVENTS node observes the original event. */
    internal fun observeOriginalEvent(sequence: Long, commitment: PayloadDigest) {
        require(!eventObserved && sequence == child.historySequence && commitment == child.historyDigest)
        eventObserved = true
    }

    internal fun observeOriginalMutation(row: NativeHistoryPhysicalRowV2) {
        require(eventObserved && row.table == NativeHistoryTableV2.ROW_VERSION && after.size < actualImages.size)
        val cells = namedCells(row); val index = after.size; val expected = actualImages[index]
        val table = NativeHistoryTableV2.entries.single { it.protectedTable == expected.table }
        require(cells.number("sequence") == child.historySequence && cells.number("ordinal") == index.toLong() &&
            cells.number("tableTag") == table.tag.toLong() && cells.number("version") == 1L &&
            cells.number("mutationKind") == 1L && cells.number("afterKind") == (if (table.immutable) 2L else 1L) &&
            cells["previousSequence"] == NativeHistoryPhysicalCellV2.Null &&
            cells["previousOrdinal"] == NativeHistoryPhysicalCellV2.Null &&
            cells["previousVersion"] == NativeHistoryPhysicalCellV2.Null &&
            cells["beforeRowId"] == NativeHistoryPhysicalCellV2.Null && cells["beforeDigest"] == NativeHistoryPhysicalCellV2.Null)
        val key = cells.blob("key"); val expectedKey = expected.nativeDeviceAdmissionHistoryKey(requested.request.scope)
        try { require(key.contentEquals(expectedKey)) } finally { key.fill(0); expectedKey.fill(0) }
        after += OriginalAfter(cells.number("afterRowId"), PayloadDigest(cells.text("afterDigest")), NativeHistoryPhysicalRowWireV2.rowDigest(row))
    }

    /** The same complete LINEAGE traversal must select the exact already-observed INSERT. */
    internal fun observeOriginalLineageMutation(row: NativeHistoryPhysicalRowV2) {
        require(eventObserved && after.size == actualImages.size && row.table == NativeHistoryTableV2.ROW_VERSION)
        val cells = namedCells(row); val ordinal = cells.number("ordinal")
        require(cells.number("sequence") == child.historySequence && ordinal >= 0L && ordinal < actualImages.size.toLong())
        val index = ordinal.toInt()
        require(!lineageObserved[index] && !imagesObserved[index] && NativeHistoryPhysicalRowWireV2.rowDigest(row) == after[index].mutationDigest)
        lineageObserved[index] = true
    }

    /** Actual native AES-GCM opening of a historical AFTER, never a current-row substitute. */
    internal fun observeOriginalProtectedImage(row: NativeHistoryPhysicalRowV2, binding: ProtectedLocalBinding, plaintext: ByteArray) {
        val index = actualImages.indexOfFirst { it.matchesDeviceAdmissionHistoryBinding(binding, requested.request.scope) }
        require(index >= 0 && lineageObserved[index] && !imagesObserved[index])
        val expected = actualImages[index]; val version = after[index]
        require(row.table.protectedTable == expected.table && row.rowId == version.rowId &&
            NativeHistoryPhysicalRowWireV2.rowDigest(row) == version.digest && sha256(plaintext) == expected.plaintextDigest)
        val key = NativeHistoryPhysicalRowWireV2.key(row); val expectedKey = expected.nativeDeviceAdmissionHistoryKey(requested.request.scope)
        try { require(key.contentEquals(expectedKey)) } finally { key.fill(0); expectedKey.fill(0) }
        imagesObserved[index] = true
    }

    internal fun requireActualTraversal() {
        require(eventObserved && after.size == actualImages.size && lineageObserved.all { it } && imagesObserved.all { it })
    }
    override fun toString(): String = "NativeDeviceAdmissionHistorySource([REDACTED])"

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

/** Exact owner/session/ledger selection; incomplete or uncertain originals stay retained. */
internal class NativeDeviceAdmissionHistorySelection(
    val nativeOwner: Any, val original: OriginalNativeHistorySnapshot, val ledgerDigest: PayloadDigest?,
    records: List<NativeDeviceAdmissionRecordV1>, retainedRecords: List<NativeDeviceAdmissionRecordV1>,
) {
    val retainedRecords = retainedRecords.toList()
    val identity = Any()
    val sources = records.map { NativeDeviceAdmissionHistorySource(identity, it) }
    init {
        require(this.retainedRecords.size <= LocalDevicePairingProfileV1.MAXIMUM_ACTIONS &&
            sources.size <= LocalDevicePairingProfileV1.MAXIMUM_ACTIONS &&
            sources.map { it.child.historySequence }.distinct().size == sources.size &&
            sources.map { it.requested.admission.deviceId }.distinct().size == sources.size &&
            sources == sources.sortedBy { it.child.historySequence } &&
            records == this.retainedRecords.filter { it.phase == NativeDeviceAdmissionPhaseV1.CONSUMED }.sortedBy { checkNotNull(it.effect).child.historySequence })
    }
    fun requireActualTraversal() { sources.forEach { it.requireActualTraversal() } }
    override fun toString(): String = "NativeDeviceAdmissionHistorySelection([REDACTED])"
}

internal fun LocalDeviceAdmissionProtectedImage.matchesDeviceAdmissionHistoryBinding(binding: ProtectedLocalBinding,
    scope: BranchScope): Boolean = binding.key.scope == scope && binding.key.table == table && binding.key.recordId == recordId &&
    binding.revision == Revision(0) && ((binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey ?: "") == dimension

internal fun LocalDeviceAdmissionProtectedImage.nativeDeviceAdmissionHistoryKey(scope: BranchScope): ByteArray {
    val table = NativeHistoryTableV2.entries.single { it.protectedTable == this.table }
    val parts = listOf("${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}", recordId.value) +
        if (dimension.isEmpty()) emptyList() else listOf(dimension)
    val cells = parts.map { value ->
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        try { NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) }
    }
    return try { NativeHistoryPhysicalRowWireV2.encodeKey(table, cells) } finally { cells.forEach { it.clear() } }
}
