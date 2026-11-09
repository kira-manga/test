package com.ukkera.pos.android.independent.nativesupplierworkflowv1

import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Supplementary physical observations, NOT production full-history admission. Every cell
 * comes from the original native finite read and every close is the actual watched close.
 * A later normal Android production reopen must still qualify the complete history. No SQL,
 * injected native proof, current-row shortcut to admission, or protected plaintext is retained. */
internal class NativeSupplierCensus private constructor(
    private val rows: Map<NativeHistoryTableV2, List<SupplierCensusRow>>,
    private val counts: Map<NativeHistoryTableV2, Int>,
    val epoch: Long,
    val branchRevision: Long,
) {
    fun requireInitialNoBusiness() {
        check(counts.getValue(NativeHistoryTableV2.ACCESS) == 0) { "Setup created an employee session" }
        requireBusiness(emptyList(), null)
    }

    /** The fixed supplier fixture emits only supplier business originals. Other aggregate,
     * claim, stock-index or operation kinds are not silently classified as zero business. */
    fun requireBusiness(originals: List<NativeSupplierOriginal>, supplierId: DomainId?,
        initial: NativeSupplierCensus? = null) {
        val branch = rows.getValue(NativeHistoryTableV2.BRANCH).single()
        val operationRows = rows.getValue(NativeHistoryTableV2.OPERATION)
        check(operationRows.map { it.text("operationId") }.toSet() == originals.map { it.operationId.value }.toSet())
        check(operationRows.size == originals.size)
        val aggregates = rows.getValue(NativeHistoryTableV2.AGGREGATE)
        check(aggregates.size == if (supplierId == null) 0 else 1)
        if (supplierId != null) check(aggregates.single().text("scope") == branch.text("scope") &&
            aggregates.single().text("kind") == LocalConfigurationProfile.SUPPLIER &&
            aggregates.single().text("id") == supplierId.value)
        check(rows.getValue(NativeHistoryTableV2.CLAIM).isEmpty())
        check(counts.getValue(NativeHistoryTableV2.JOURNAL_FRAGMENT) == 0) {
            "The supplier fixture unexpectedly emitted fragmented business history"
        }
        for (table in listOf(NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION,
                NativeHistoryTableV2.STOCK_RESERVATION, NativeHistoryTableV2.STOCK_VALUE))
            check(counts.getValue(table) == 0) { "Supplier administration produced unrelated stock/value state" }

        val writes = mutableListOf<List<ByteArray>>()
        for (original in originals) {
            val request = checkNotNull(original.request)
            val result = checkNotNull(original.result)
            val identity = checkNotNull(original.identity)
            val session = checkNotNull(original.session)
            val typedResult = LocalConfigurationCodec.decodeResult(result.result)
            val operation = operationRows.single { it.text("operationId") == request.operationId.value }
            check(operation.text("scope") == listOf(original.owner.scope.organizationId.value,
                original.owner.scope.companyId.value, original.owner.scope.branchId.value).joinToString("/") &&
                operation.text("actorId") == original.owner.actorId.value && operation.text("command") == request.command.name &&
                operation.integer("commandVersion") == request.command.version.toLong() &&
                operation.text("digest") == identity.semanticSha256 && operation.text("outcome") == result.code &&
                operation.integer("sequence") == result.sequence.value && operation.integer("epoch") == result.authorityEpoch.value &&
                operation.text("authenticationId") == session.sessionId.value &&
                operation.integer("policyRevision") == session.policy.revision.value &&
                operation.text("committedAt") == result.recordedAt.canonicalString() &&
                operation.bytes("result").contentEquals(result.result.copyBytes()))
            check(operation.text("resultHash") == sha256(operation.bytes("result")).value)
            val journal = rows.getValue(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == request.operationId.value }
            check(journal.text("scope") == operation.text("scope") && journal.integer("sequence") == result.sequence.value &&
                journal.integer("epoch") == result.authorityEpoch.value && journal.integer("epoch") == branch.integer("epoch") &&
                journal.text("assignmentId") == branch.text("assignmentId"))
            val envelope = journal.bytes("envelope")
            check(journal.text("hash") == sha256(envelope).value)
            val fields = supplierFrames(envelope)
            // Literal persisted v1 mapping. Unsupported framing is a wiring failure, never a
            // fabricated empty effect set. No storage/result revision increment is assumed.
            check(fields.size == 20 && fields[0].supplierText() == "ukkera.journal/v1")
            check(fields[1].supplierText() == operation.text("scope") &&
                fields[2].supplierText() == journal.text("assignmentId") &&
                fields[3].supplierText() == result.authorityEpoch.value.toString() &&
                fields[4].supplierText() == result.sequence.value.toString() &&
                fields[5].supplierText() == request.operationId.value && fields[6].supplierText() == original.owner.actorId.value &&
                fields[7].supplierText() == request.command.name && fields[8].supplierText() == request.command.version.toString() &&
                fields[9].supplierText() == session.sessionId.value && fields[10].supplierText() == session.policy.revision.value.toString() &&
                fields[11].supplierText() == identity.semanticSha256 && fields[12].supplierText() == result.code &&
                fields[13].supplierText() == result.recordedAt.canonicalString() &&
                fields[14].supplierText() == operation.text("resultHash") && fields[15].supplierText() == journal.text("previousHash") &&
                fields[16].contentEquals(request.payload.copyBytes()) && fields[17].contentEquals(result.result.copyBytes()))
            val effects = supplierFrames(fields[18]).map(::supplierFrames)
            check(effects.size == if (result.committed) 1 else 0)
            for (effect in effects) {
                check(effect.size == 5 && effect[0].supplierText() == LocalConfigurationProfile.SUPPLIER &&
                    effect[1].supplierText() == checkNotNull(supplierId).value)
                val supplier = (LocalConfigurationCodec.decode(request.payload) as LocalConfigurationRequestV1.ConfigureSupplier).supplier
                val record = JSONObject(effect[4].supplierText())
                val scope = record.getJSONObject("scope")
                val stored = record.getJSONObject("body").getJSONObject("value")
                check(record.getString("kind") == LocalConfigurationProfile.SUPPLIER && record.getString("id") == supplier.id.value &&
                    scope.getString("organizationId") == original.owner.scope.organizationId.value &&
                    scope.getString("companyId") == original.owner.scope.companyId.value &&
                    scope.getString("branchId") == original.owner.scope.branchId.value &&
                    stored.getString("id") == supplier.id.value && stored.getBoolean("active") == supplier.active &&
                    stored.getJSONObject("names").getString("english") == supplier.names.english &&
                    stored.getJSONObject("names").getString("arabic") == supplier.names.arabic &&
                    record.getString("authorizedBy") == original.owner.actorId.value &&
                    record.getString("policyRevision") == session.policy.revision.value.toString() &&
                    record.getString("revision") == checkNotNull(typedResult.revision).value.toString() &&
                    record.getString("recordedAt") == result.recordedAt.canonicalString())
                // Bind the next accepted input to the actual prior write, without deriving
                // any storage revision from a domain revision or an assumed increment.
                check(original.input.expectedStorageRevision?.value?.toString() == writes.lastOrNull()?.get(2)?.supplierText())
                writes.add(effect)
            }
            val jobs = supplierFrames(fields[19])
            check(jobs.isEmpty()) { "Supplier original produced an unrelated business job" }
            val audits = rows.getValue(NativeHistoryTableV2.AUDIT).filter {
                it.text("operationId") == request.operationId.value && it.text("kind") == "command.${result.code}"
            }
            check(audits.isNotEmpty() && audits.all {
                it.text("scope") == operation.text("scope") && it.text("actorId") == original.owner.actorId.value &&
                    it.text("originalActorId") == original.owner.actorId.value && it.text("digest") == identity.semanticSha256 &&
                    it.integer("policyRevision") == session.policy.revision.value
            }) { "Original business audit is missing or changed" }
            val obligations = rows.getValue(NativeHistoryTableV2.OUTBOX).filter { it.text("operationId") == request.operationId.value }
            check(obligations.size == 1 && obligations.single().text("kind") == "journal.upload")
            val obligation = obligations.single()
            check(obligation.integer("ordinal") == 0L && obligation.text("scope") == operation.text("scope") &&
                obligation.text("bodyHash") == sha256(obligation.bytes("body")).value)
            val upload = supplierFrames(obligation.bytes("body")).map { it.supplierText() }
            check(upload == listOf("ukkera.journal-upload/v1", original.owner.scope.branchId.value,
                result.authorityEpoch.value.toString(), result.sequence.value.toString(), journal.text("hash")))
        }
        check(rows.getValue(NativeHistoryTableV2.JOURNAL).size == originals.size &&
            rows.getValue(NativeHistoryTableV2.OUTBOX).size == originals.size)
        check(rows.getValue(NativeHistoryTableV2.AUDIT).filter { it.text("kind").startsWith("command.") }
            .all { audit -> originals.any { it.operationId.value == audit.text("operationId") &&
                "command.${checkNotNull(it.result).code}" == audit.text("kind") } })
        if (originals.isNotEmpty()) {
            val baseline = checkNotNull(initial) { "Business history requires its actual initial native cut" }
            baseline.requireInitialNoBusiness()
            val firstBranch = baseline.rows.getValue(NativeHistoryTableV2.BRANCH).single()
            check(listOf("scope", "assignmentId", "deviceId").all { branch.text(it) == firstBranch.text(it) } &&
                epoch == baseline.epoch)
            val ordered = rows.getValue(NativeHistoryTableV2.JOURNAL).sortedBy { it.integer("sequence") }
            check(ordered.map { it.text("operationId") } == originals.map { it.operationId.value })
            check(ordered.first().text("previousHash") == firstBranch.text("journalHash") &&
                ordered.first().integer("sequence") > firstBranch.integer("sequence"))
            check(ordered.zipWithNext().all { (before, after) ->
                after.text("previousHash") == before.text("hash") && after.integer("sequence") > before.integer("sequence")
            })
            check(branch.integer("sequence") == ordered.last().integer("sequence") &&
                branch.text("journalHash") == ordered.last().text("hash"))
        }
        if (supplierId != null) {
            val last = writes.last()
            val current = aggregates.single()
            check(current.integer("revision").toString() == last[2].supplierText() && current.text("schema") == last[3].supplierText() &&
                current.bytes("payload").contentEquals(last[4]) && current.text("payloadHash") == sha256(last[4]).value)
        }
    }

    /** Preserves literal durable originals across lookups/ACK/reopen, not callback totals. */
    fun requireSameOriginalBusiness(after: NativeSupplierCensus) {
        check(epoch == after.epoch) { "Ordinary reopen changed the authority epoch" }
        val branch = rows.getValue(NativeHistoryTableV2.BRANCH).single()
        val reopenedBranch = after.rows.getValue(NativeHistoryTableV2.BRANCH).single()
        check(listOf("scope", "assignmentId", "deviceId", "journalHash").all { branch.text(it) == reopenedBranch.text(it) } &&
            branch.integer("sequence") == reopenedBranch.integer("sequence")) {
            "Ordinary lookup/ACK/reopen changed the original business history head"
        }
        val operationIds = rows.getValue(NativeHistoryTableV2.OPERATION).map { it.text("operationId") }.toSet()
        fun selected(table: NativeHistoryTableV2, source: NativeSupplierCensus): List<String> =
            source.rows.getValue(table).filter {
                table != NativeHistoryTableV2.AUDIT || it.text("operationId") in operationIds && it.text("kind").startsWith("command.")
            }.map { it.fingerprint() }.sorted()
        for (table in retainedTables - NativeHistoryTableV2.BRANCH) {
            check(selected(table, this) == selected(table, after)) { "Original durable supplier evidence changed after recovery" }
        }
    }

    companion object {
        private val retainedTables = setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.OPERATION,
            NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM, NativeHistoryTableV2.JOURNAL,
            NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.OUTBOX)

        suspend fun read(custody: SelfOwnedNativeHistoryCustody, coordinates: SelfOwnedAllocationCoordinatesV1,
            label: String, evidence: NativeSupplierEvidence): NativeSupplierCensus = custody.runRoomWork(block = {
            val read = custody.openOriginalReadOnly()
            var closeStarted = false
            var primaryFailure: Throwable? = null
            try {
                requireSameSupplierCoordinates(coordinates, read.original.record.allocation.coordinates)
                val rows = retainedTables.associateWith { mutableListOf<SupplierCensusRow>() }
                val counts = NativeHistoryTableV2.entries.associateWith { 0 }.toMutableMap()
                val complete = mutableSetOf<NativeHistoryTableV2>()
                while (read.readSchemaPage() != null) { /* Entire native schema traversal, not an accepting schema verifier. */ }
                while (true) {
                    val metadata = read.nextRowMetadata() ?: break
                    val table = metadata.table
                    val census = metadata.pass == NativeHistoryReadPassV2.CENSUS
                    if (census && metadata.kind == NativeHistoryRowKindV2.PASS_END) complete += checkNotNull(table)
                    val keep = census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL && table in retainedTables
                    val cells = linkedMapOf<String, SupplierCensusCell>()
                    for ((index, cell) in metadata.cells.withIndex()) {
                        check(cell.byteCount in 0..NativeHistoryPhysicalReadProfileV2.MAX_CELL_BYTES)
                        val kept = if (keep) ByteArray(cell.byteCount.toInt()) else null
                        var offset = 0L
                        do {
                            val chunk = read.readCellChunk()
                            try {
                                val bytes = chunk.copyBytes()
                                try {
                                    check(chunk.column == index && chunk.offset == offset && chunk.totalBytes == cell.byteCount &&
                                        chunk.integerValue == cell.integerValue && offset + bytes.size <= cell.byteCount &&
                                        bytes.size <= NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES)
                                    kept?.let { bytes.copyInto(it, offset.toInt()) }
                                    offset += bytes.size
                                    check(chunk.last == (offset == cell.byteCount))
                                    if (chunk.last) break
                                    check(bytes.isNotEmpty())
                                } finally { bytes.fill(0) }
                            } finally { chunk.clear() }
                        } while (true)
                        check(offset == cell.byteCount)
                        if (keep) cells[cell.name] = SupplierCensusCell(cell.integerValue, checkNotNull(kept))
                    }
                    if (census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL) {
                        val actualTable = checkNotNull(table)
                        counts[actualTable] = counts.getValue(actualTable) + 1
                        if (keep) {
                            check(rows.getValue(actualTable).size < 1_024) { "The bounded supplier observation is oversized" }
                            rows.getValue(actualTable) += SupplierCensusRow(cells)
                        }
                    }
                }
                check(complete.containsAll(NativeHistoryPhysicalReadProfileV2.applicationTables))
                closeStarted = true
                val closed = withContext(NonCancellable) { read.closeOriginalRead() }
                val after = custody.reobserveAfterActualClose(closed)
                check(after.lifetimeId == read.original.lifetimeId && after.recordDigest == read.original.recordDigest &&
                    after.record.database == read.original.record.database)
                custody.completeOriginalReadDelivery(after) // Read delivery only; never Room/writer admission.
                val branch = rows.getValue(NativeHistoryTableV2.BRANCH).single()
                check(branch.text("scope") == listOf(coordinates.scope.organizationId.value, coordinates.scope.companyId.value,
                    coordinates.scope.branchId.value).joinToString("/") && branch.text("assignmentId") == coordinates.assignmentId.value &&
                    branch.text("deviceId") == coordinates.deviceId.value)
                evidence.event(label, JSONObject().put("nativeReadActualClose", true)
                    .put("authorityEpoch", branch.integer("epoch")).put("branchRevision", branch.integer("revision"))
                    .put("operationIds", JSONArray(rows.getValue(NativeHistoryTableV2.OPERATION).map { it.text("operationId") }))
                    .put("fullProductionHistoryAdmission", "NOT_CLAIMED_BY_CENSUS"))
                NativeSupplierCensus(rows.mapValues { it.value.toList() }, counts.toMap(), branch.integer("epoch"), branch.integer("revision"))
            } catch (failure: Throwable) { primaryFailure = failure; throw failure }
            finally {
                if (!closeStarted) try { withContext(NonCancellable) { read.closeOriginalRead() } }
                catch (cleanup: Throwable) {
                    val primary = primaryFailure
                    if (primary == null) throw cleanup
                    if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
        }, discard = { custody.denyDataUse() })
    }
}

private class SupplierCensusCell(val integer: Long?, val bytes: ByteArray)
private class SupplierCensusRow(private val cells: Map<String, SupplierCensusCell>) {
    fun text(name: String): String = cells.getValue(name).bytes.supplierText()
    fun integer(name: String): Long = checkNotNull(cells.getValue(name).integer)
    fun bytes(name: String): ByteArray = cells.getValue(name).bytes
    fun fingerprint(): String = cells.entries.joinToString("|") { (name, value) ->
        "$name:${value.integer ?: "-"}:${sha256(value.bytes).value}"
    }
}

private fun ByteArray.supplierText(): String = decodeToString(throwOnInvalidSequence = true)

/** Decoder of the existing bounded length-framed durable journal, not a command producer. */
private fun supplierFrames(bytes: ByteArray): List<ByteArray> {
    check(bytes.size <= 4 * 1024 * 1024)
    val result = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < bytes.size) {
        check(result.size < 1024 && bytes.size - offset >= 4)
        var size = 0L
        repeat(4) { size = (size shl 8) or (bytes[offset++].toLong() and 255L) }
        check(size <= bytes.size - offset)
        result += bytes.copyOfRange(offset, offset + size.toInt())
        offset += size.toInt()
    }
    check(offset == bytes.size)
    return result
}
