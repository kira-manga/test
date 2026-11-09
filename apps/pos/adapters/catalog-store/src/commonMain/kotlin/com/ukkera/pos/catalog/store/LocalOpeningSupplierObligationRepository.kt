package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Only this adapter's finite hydration budget is classified as a capacity result. */
internal class OpeningSupplierObligationHydrationCapacityUnavailable : RuntimeException()

private sealed interface OpeningSupplierObligationObservation {
    val scope: BranchScope
    suspend fun aggregate(key: AggregateKey): StoredAggregate?
    suspend fun claim(key: ClaimKey): DomainId?
    suspend fun original(operationId: OperationId): RetainedCommandEvidence?
}

private class CurrentOpeningSupplierObligationObservation(private val view: CommandReadView) : OpeningSupplierObligationObservation {
    override val scope: BranchScope get() = view.scope
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = view.aggregate(key)
    override suspend fun claim(key: ClaimKey): DomainId? = view.claim(key)
    override suspend fun original(operationId: OperationId): RetainedCommandEvidence? = view.retainedCommand(operationId)
}

private class HistoricalOpeningSupplierObligationObservation(private val command: RuntimeHistoricalCommand) : OpeningSupplierObligationObservation {
    override val scope: BranchScope get() = command.scope
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = command.aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun original(operationId: OperationId): RetainedCommandEvidence? = when (val original = command.earlierOriginal(operationId)) {
        is RetainedOriginalV2.Legacy -> original.evidence
        is RetainedOriginalV2.Streamed -> error("Unsupported opening supplier original")
        null -> null
    }
}

internal data class OpeningSupplierObligationRow(val row: StoredAggregate, val record: OpeningSupplierObligationRecordV1)

internal data class OpeningSupplierObligationCut(
    val previous: OpeningSupplierObligationRow?,
    val sourceOwner: DomainId?,
    val sourceIdentityOwner: DomainId?,
    val referenceOwner: DomainId?,
    val supplier: OpeningSupplierObligationSupplierSourceV1?,
)

/**
 * Current protected loans and genuine native BEFORE cuts share the same strict hydration.
 * A missing producer, mismatched claim or unsupported/corrupt source never becomes absence,
 * zero debt or a business refusal. Historical observations require already joined originals.
 */
internal class OpeningSupplierObligationReader private constructor(
    private val configuration: LocalConfigurationRepository,
    private val source: OpeningSupplierObligationObservation,
) {
    constructor(configuration: LocalConfigurationRepository, view: CommandReadView) : this(configuration, CurrentOpeningSupplierObligationObservation(view))
    constructor(configuration: LocalConfigurationRepository, command: RuntimeHistoricalCommand) : this(configuration, HistoricalOpeningSupplierObligationObservation(command))
    private val rows = mutableMapOf<DomainId, OpeningSupplierObligationRow?>()
    private val originals = mutableMapOf<OperationId, RetainedCommandEvidence>()
    private var loadedBytes = 0L
    private val resources = mutableSetOf<RecoveryResourceKey>()
    val consumedResources: Set<RecoveryResourceKey> get() = resources.toSet()

    suspend fun row(id: DomainId): OpeningSupplierObligationRow? {
        if (id in rows) return rows[id]
        if (rows.size >= MAX_HYDRATED_ROWS) throw OpeningSupplierObligationHydrationCapacityUnavailable()
        val key = LocalOpeningSupplierObligationProfile.key(id)
        val row = source.aggregate(key)
        val owner = source.claim(openingSupplierObligationIdClaim(id))
        check(if (row == null) owner == null else owner == id) { "Opening supplier row/claim mismatch" }
        if (row == null) { rows[id] = null; return null }
        check(row.key == key && row.schema == LocalOpeningSupplierObligationProfile.SCHEMA && row.revision == Revision(1) &&
            row.payload.size <= LocalOpeningSupplierObligationProfile.MAX_RECORD_BYTES && row.digest == sha256(row.payload.copyBytes()))
        accountBytes(row.payload.size.toLong())
        val record = decodeOpeningSupplierObligationRecord(row.payload)
        check(record.scope == source.scope && record.request.obligationId == id && record.revision == row.revision)
        val evidence = originals[record.operationId] ?: source.original(record.operationId) ?: error("Opening supplier producer unavailable")
        check(evidence.scope == source.scope && evidence.operationId == record.operationId)
        val original = requireOpeningSupplierObligationProducer(evidence)
        check(original == record)
        val write = evidence.aggregateWrites.single()
        check(write.key == key && write.schema == row.schema && write.storageGeneration == row.revision &&
            write.payload == row.payload && write.payloadDigest == row.digest)
        if (evidence.operationId !in originals) {
            if (originals.size >= MAX_HYDRATED_ROWS) throw OpeningSupplierObligationHydrationCapacityUnavailable()
            accountBytes(evidence.normalized.size.toLong() + write.payload.size.toLong())
            originals[evidence.operationId] = evidence
        }
        check(source.claim(openingSupplierObligationSourceClaim(record.request.sourceEvidenceId)) == id &&
            source.claim(openingSupplierObligationSourceIdentityClaim(source.scope, record.request.sourceDocument.sourceIdentity)) == id &&
            source.claim(openingSupplierObligationReferenceClaim(source.scope, record.request.supplierId, record.request.sourceReference)) == id)
        resources += openingSupplierObligationRecordResources(record)
        return OpeningSupplierObligationRow(row, record).also { rows[id] = it }
    }

    /** Hydrate every present source first; business rejection order cannot conceal uncertainty. */
    suspend fun creationCut(request: LocalRecordOpeningSupplierObligationV1): OpeningSupplierObligationCut {
        val previous = row(request.obligationId)
        val sourceClaim = openingSupplierObligationSourceClaim(request.sourceEvidenceId)
        val sourceOwner = source.claim(sourceClaim)
        if (sourceOwner != null) {
            val duplicate = row(sourceOwner) ?: error("Opening supplier documentary source lost its owner")
            check(openingSupplierObligationSourceClaim(duplicate.record.request.sourceEvidenceId) == sourceClaim)
        }
        val sourceIdentity = openingSupplierObligationSourceIdentityClaim(source.scope, request.sourceDocument.sourceIdentity)
        val sourceIdentityOwner = source.claim(sourceIdentity)
        if (sourceIdentityOwner != null) {
            val duplicate = row(sourceIdentityOwner) ?: error("Opening supplier documentary identity lost its owner")
            check(duplicate.record.request.sourceDocument.sourceIdentity == request.sourceDocument.sourceIdentity &&
                openingSupplierObligationSourceIdentityClaim(source.scope, duplicate.record.request.sourceDocument.sourceIdentity) == sourceIdentity)
        }
        val reference = openingSupplierObligationReferenceClaim(source.scope, request.supplierId, request.sourceReference)
        val referenceOwner = source.claim(reference)
        if (referenceOwner != null) {
            val duplicate = row(referenceOwner) ?: error("Opening supplier documentary reference lost its owner")
            check(openingSupplierObligationReferenceClaim(source.scope, duplicate.record.request.supplierId,
                duplicate.record.request.sourceReference) == reference)
        }
        val supplierRow = source.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, request.supplierId))
        val supplier = supplierRow?.let { configuration.openingSupplierObligationSource(source.scope, it) }
        supplier?.let { resources += openingSupplierObligationSupplierResources(it) }
        return OpeningSupplierObligationCut(previous, sourceOwner, sourceIdentityOwner, referenceOwner, supplier)
    }

    private fun accountBytes(bytes: Long) {
        check(bytes >= 0)
        if (bytes > MAX_HYDRATED_BYTES - loadedBytes) throw OpeningSupplierObligationHydrationCapacityUnavailable()
        loadedBytes += bytes
    }

    private companion object {
        // Twenty rows in a public page, or four potential duplicate owners in a command.
        const val MAX_HYDRATED_ROWS: Int = LocalOpeningSupplierObligationProfile.MAX_PAGE
        const val MAX_HYDRATED_BYTES: Long = 4_194_304L
    }
}

/** The branch's real supplier configuration, read only from this protected transaction. */
private fun LocalConfigurationRepository.openingSupplierObligationSource(scope: BranchScope, row: StoredAggregate): OpeningSupplierObligationSupplierSourceV1 {
    check(row.key.kind == LocalConfigurationProfile.SUPPLIER && row.payload.size <= LocalOpeningSupplierObligationProfile.MAX_RECORD_BYTES &&
        row.digest == sha256(row.payload.copyBytes()))
    val record = decodeRecord(scope, row)
    val supplier = (record.body as? ConfigurationBody.Supplier)?.value ?: error("Opening supplier configuration unavailable")
    check(row.key.id == supplier.id)
    return OpeningSupplierObligationSupplierSourceV1(row.revision, row.digest, record)
}

internal fun openingSupplierObligationRefusal(request: LocalRecordOpeningSupplierObligationV1, cut: OpeningSupplierObligationCut): String? = when {
    cut.previous != null -> "purchasing.opening_obligation_identity_used"
    cut.sourceOwner != null || cut.sourceIdentityOwner != null -> "purchasing.opening_obligation_duplicate_source"
    cut.referenceOwner != null -> "purchasing.opening_obligation_duplicate_reference"
    cut.supplier == null || !(cut.supplier.configuration.body as ConfigurationBody.Supplier).value.active -> "purchasing.opening_obligation_supplier_required"
    cut.supplier.storageRevision != request.expectedSupplierRevision -> "purchasing.opening_obligation_stale_supplier"
    else -> null
}

internal fun openingSupplierObligationResources(scope: BranchScope, actorId: DomainId, request: LocalRecordOpeningSupplierObligationV1): Set<RecoveryResourceKey> =
    setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actorId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.supplierId),
        RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.obligationId),
        RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.supplierId),
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.sourceEvidenceId)) +
        openingSupplierObligationClaims(scope, request).map { RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, purchasingReferenceResource(it.key)) }

private fun openingSupplierObligationSupplierResources(supplier: OpeningSupplierObligationSupplierSourceV1): Set<RecoveryResourceKey> = setOf(
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, supplier.configuration.id),
    RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, supplier.configuration.authorizedBy),
)

private fun openingSupplierObligationRecordResources(record: OpeningSupplierObligationRecordV1): Set<RecoveryResourceKey> =
    openingSupplierObligationResources(record.scope, record.actorId, record.request) + openingSupplierObligationSupplierResources(record.supplierSource)
