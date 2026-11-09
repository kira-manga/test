package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Complete closed native traversal with bounded supplementary DATA assertions. This is
 * not an accepting history scanner, current custody, a second live reader or a cost API.
 * Production existing-only reopen must independently admit its complete original history. */
internal object NativeCafeCashCensus {
    private const val MAX_ROWS_PER_TABLE = 4_096
    private const val MAX_RETAINED_BYTES = 32L * 1024 * 1024
    private val retainedTables = setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.OPERATION,
        NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM, NativeHistoryTableV2.JOURNAL,
        NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.OUTBOX, NativeHistoryTableV2.JOURNAL_FRAGMENT,
        NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION,
        NativeHistoryTableV2.STOCK_RESERVATION, NativeHistoryTableV2.STOCK_VALUE)

    suspend fun read(custody: SelfOwnedNativeHistoryCustody, coordinates: SelfOwnedAllocationCoordinatesV1,
        plan: NativeCafeCashPlan, originals: List<CafeSavedOriginal>, seed: CafeCashSeed?,
        protected: CafeProtectedCut?, initial: CafeNativeCut?, label: String,
        evidence: NativeCafeCashEvidence): CafeNativeCut = custody.runRoomWork(block = {
        check((seed == null) == (protected == null) && (seed == null) == (initial == null))
        val read = custody.openOriginalReadOnly()
        val rows = retainedTables.associateWith { mutableListOf<CafeCensusRow>() }
        val pending = mutableListOf<ByteArray>()
        var closeStarted = false
        var primary: Throwable? = null
        try {
            requireSameNativeFixtureCoordinates(coordinates, read.original.record.allocation.coordinates)
            check(read.original.record.pending == null) { "An unresolved native original is not an orderly settled cut" }
            val counts = NativeHistoryTableV2.entries.associateWith { 0L }.toMutableMap()
            val complete = mutableSetOf<NativeHistoryTableV2>()
            var retainedBytes = 0L
            while (read.readSchemaPage() != null) { /* Exhaust every actual schema page. */ }
            while (true) {
                val metadata = read.nextRowMetadata() ?: break
                val table = metadata.table
                val census = metadata.pass == NativeHistoryReadPassV2.CENSUS
                if (census && metadata.kind == NativeHistoryRowKindV2.PASS_END)
                    check(complete.add(checkNotNull(table)))
                val keep = census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL && table in retainedTables
                val cells = linkedMapOf<String, CafeCensusCell>()
                var rowBytes = 0L
                for ((index, cell) in metadata.cells.withIndex()) {
                    check(cell.byteCount in 0..NativeHistoryPhysicalReadProfileV2.MAX_CELL_BYTES)
                    rowBytes += cell.byteCount
                    check(rowBytes <= NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES)
                    if (keep) {
                        check(cell.byteCount <= MAX_RETAINED_BYTES - retainedBytes) {
                            "The bounded synthetic census is oversized; no original DATA are truncated"
                        }
                        retainedBytes += cell.byteCount
                    }
                    val kept = if (keep) ByteArray(cell.byteCount.toInt()).also(pending::add) else null
                    var offset = 0L
                    do {
                        val chunk = read.readCellChunk()
                        try {
                            val bytes = chunk.copyBytes()
                            try {
                                check(chunk.column == index && chunk.offset == offset && chunk.totalBytes == cell.byteCount &&
                                    chunk.integerValue == cell.integerValue && bytes.size <= NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES &&
                                    offset + bytes.size <= cell.byteCount)
                                kept?.let { bytes.copyInto(it, offset.toInt()) }
                                offset += bytes.size
                                check(chunk.last == (offset == cell.byteCount))
                                if (chunk.last) break
                                check(bytes.isNotEmpty())
                            } finally { bytes.fill(0) }
                        } finally { chunk.clear() }
                    } while (true)
                    check(offset == cell.byteCount)
                    if (keep) check(cells.put(cell.name, CafeCensusCell(cell.type, cell.integerValue, checkNotNull(kept))) == null)
                }
                if (census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL) {
                    val actual = checkNotNull(table)
                    counts[actual] = counts.getValue(actual) + 1
                    check(counts.getValue(actual) <= MAX_ROWS_PER_TABLE) {
                        "The bounded synthetic table is oversized; no native rows are truncated"
                    }
                    if (keep) {
                        rows.getValue(actual) += CafeCensusRow(cells)
                        cells.values.forEach { pending.remove(it.bytes) }
                    }
                }
            }
            check(complete == NativeHistoryPhysicalReadProfileV2.applicationTables.toSet()) {
                "Every actual application-table CENSUS PASS_END is required"
            }
            val cut = CafeCensusRows(rows.mapValues { it.value.toList() })
            cut.requireBranch(coordinates, plan)
            val bindings = if (seed == null) {
                check(originals.isEmpty() && read.businessPublicationObservations.isEmpty())
                cut.requireInitialNoBusiness(counts)
                JSONObject().put("business", "NONE_BEFORE_EMPLOYEE_SESSION")
            } else {
                cut.requireOriginals(originals, checkNotNull(initial), coordinates, plan, evidence)
                NativeCafeCashOriginalData.require(cut, plan, seed, checkNotNull(protected), originals, evidence)
            }
            val fingerprints = rows.mapValues { (table, values) -> values.associate { it.key(table) to it.fingerprint() }
                .also { check(it.size == values.size) } }
            val audits = rows.getValue(NativeHistoryTableV2.AUDIT).associate { row -> row.key(NativeHistoryTableV2.AUDIT) to
                CafeAuditFingerprint(row.fingerprint(), row.text("kind"), row.text("operationId")) }
            val result = CafeNativeCut(cut.branch.integer("epoch"), cut.branch.integer("revision"),
                cut.branch.integer("sequence"), cut.branch.text("journalHash"), counts.toMap(),
                originals.map { it.request.operationId.value }.toSet(),
                fingerprints.filterKeys { it != NativeHistoryTableV2.AUDIT }, audits)
            closeStarted = true
            val closed = withContext(NonCancellable) { read.closeOriginalRead() }
            val after = custody.reobserveAfterActualClose(closed)
            check(after.lifetimeId == read.original.lifetimeId && after.recordDigest == read.original.recordDigest &&
                after.record.database == read.original.record.database)
            custody.completeOriginalReadDelivery(after)
            evidence.event(label, JSONObject().put("nativeReadActualClose", true).put("completeApplicationTableCensus", true)
                .put("authorityEpoch", result.epoch).put("branchRevision", result.branchRevision)
                .put("commandHeadSequence", result.headSequence).put("retainedBusinessBytes", retainedBytes)
                .put("physicalSourceRowCounts", JSONObject().also { json ->
                    counts.filterKeys { it.application }.forEach { (table, count) -> json.put(table.name, count) } })
                .put("originalOperationIds", JSONArray(originals.map { it.request.operationId.value }))
                .put("boundBusinessData", bindings).put("businessFingerprints", JSONObject().also { json ->
                    fingerprints.forEach { (table, values) -> json.put(table.name, JSONObject(values)) } })
                .put("instructionCount", "UNBOUND_NOT_INFERRED_FROM_SOURCE_OR_SLOT_ROWS")
                .put("requiredPreCloseFoodProducerSourceProof", "UNBOUND")
                .put("fullProductionHistoryAdmission", "NOT_CLAIMED_BY_CENSUS")
                .put("outboxMeaning", "IMMUTABLE_ORIGINAL_OBLIGATIONS_NOT_DELIVERY_ACK_OR_PRINT")
                .put("wholeCase", "NOT_QUALIFIED"))
            result
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            pending.forEach { it.fill(0) }
            rows.values.forEach { values -> values.forEach(CafeCensusRow::clear) }
            if (!closeStarted) try { withContext(NonCancellable) { read.closeOriginalRead() } }
            catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }, discard = { custody.denyDataUse() })
}

internal class CafeNativeCut(val epoch: Long, val branchRevision: Long, val headSequence: Long, val headHash: String,
    val tableCounts: Map<NativeHistoryTableV2, Long>, val operationIds: Set<String>,
    private val fingerprints: Map<NativeHistoryTableV2, Map<String, String>>,
    private val audits: Map<String, CafeAuditFingerprint>) {
    fun requireSameBusiness(after: CafeNativeCut) {
        check(epoch == after.epoch && branchRevision == after.branchRevision && headSequence == after.headSequence &&
            headHash == after.headHash && operationIds == after.operationIds && fingerprints == after.fingerprints) {
            "Lookup-only cold recovery changed an original business/native source cut"
        }
        check(audits.all { (key, value) -> after.audits[key] == value }) { "An original durable audit was deleted or rewritten" }
        check(after.audits.filterKeys { it !in audits }.values.all {
            it.kind == "result.supervisor-read" && it.operationId in operationIds
        }) { "Recovery added a business audit rather than an authorized historical lookup" }
        // New real employee sessions and their separate protected authentication history are
        // traversed, but never misclassified as a second cash, stock or sale effect.
    }
    override fun toString(): String = "CafeNativeCut([REDACTED])"
}
internal data class CafeAuditFingerprint(val fingerprint: String, val kind: String, val operationId: String)

internal class CafeCensusRows(private val rows: Map<NativeHistoryTableV2, List<CafeCensusRow>>) {
    val branch: CafeCensusRow get() = table(NativeHistoryTableV2.BRANCH).single()
    fun table(table: NativeHistoryTableV2): List<CafeCensusRow> = rows.getValue(table)
    fun aggregates(kind: String): List<CafeCensusRow> = table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") == kind }

    fun requireBranch(c: SelfOwnedAllocationCoordinatesV1, plan: NativeCafeCashPlan) {
        check(branch.text("scope") == cafeScope(c.scope) && branch.text("assignmentId") == c.assignmentId.value &&
            branch.text("deviceId") == c.deviceId.value && branch.integer("epoch") == plan.authorityEpoch.value && branch.integer("writable") == 1L)
        rows.forEach { (table, values) ->
            check(values.map { it.key(table) }.distinct().size == values.size)
            values.forEach { check(it.text("scope") == branch.text("scope")) }
        }
        table(NativeHistoryTableV2.AGGREGATE).forEach { check(it.text("payloadHash") == sha256(it.bytes("payload")).value) }
    }
    fun requireInitialNoBusiness(counts: Map<NativeHistoryTableV2, Long>) {
        for (table in listOf(NativeHistoryTableV2.OPERATION, NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM,
            NativeHistoryTableV2.JOURNAL, NativeHistoryTableV2.OUTBOX, NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.JOURNAL_FRAGMENT,
            NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION, NativeHistoryTableV2.STOCK_RESERVATION,
            NativeHistoryTableV2.STOCK_VALUE, NativeHistoryTableV2.ACCESS, NativeHistoryTableV2.REFRESH,
            NativeHistoryTableV2.ARCHIVE, NativeHistoryTableV2.APPROVAL)) check(counts.getValue(table) == 0L) {
            "Fresh setup unexpectedly retained business, stock, approval or employee-session work"
        }
        check(counts.getValue(NativeHistoryTableV2.ACCOUNT) == 1L && counts.getValue(NativeHistoryTableV2.POLICY) == 1L)
    }
    fun requireOriginals(originals: List<CafeSavedOriginal>, initial: CafeNativeCut,
        c: SelfOwnedAllocationCoordinatesV1, plan: NativeCafeCashPlan, evidence: NativeCafeCashEvidence) {
        check(originals.isNotEmpty() && originals.map { it.request.operationId }.distinct().size == originals.size &&
            originals.map { it.result.sequence.value } == originals.map { it.result.sequence.value }.sorted())
        val ids = originals.map { it.request.operationId.value }.toSet()
        check(table(NativeHistoryTableV2.OPERATION).map { it.text("operationId") }.toSet() == ids &&
            table(NativeHistoryTableV2.OPERATION).size == originals.size && table(NativeHistoryTableV2.JOURNAL).size == originals.size)
        check(table(NativeHistoryTableV2.OUTBOX).all { it.text("operationId") in ids } &&
            table(NativeHistoryTableV2.JOURNAL_FRAGMENT).all { it.text("operationId") in ids })
        check(table(NativeHistoryTableV2.AUDIT).all { it.text("operationId") in ids &&
            (it.text("kind").startsWith("command.") || it.text("kind") in setOf("native-original.retained", "result.supervisor-read")) })
        originals.forEach { saved ->
            check(saved.scope == c.scope && saved.identity.originalActorId == c.firstActorId && saved.deviceId == c.deviceId &&
                saved.installationId == c.installationId && saved.assignmentId == c.assignmentId && saved.storageInstanceId == c.storageInstanceId)
            requireOriginal(saved, originals, plan, evidence)
        }
        val journals = table(NativeHistoryTableV2.JOURNAL).sortedBy { it.integer("sequence") }
        check(initial.operationIds.isEmpty() && initial.epoch == branch.integer("epoch") &&
            journals.map { it.text("operationId") } == originals.map { it.request.operationId.value } &&
            journals.first().text("previousHash") == initial.headHash && journals.first().integer("sequence") > initial.headSequence &&
            journals.zipWithNext().all { (before, after) -> after.integer("sequence") > before.integer("sequence") &&
                after.text("previousHash") == before.text("hash") } &&
            branch.integer("sequence") == journals.last().integer("sequence") && branch.text("journalHash") == journals.last().text("hash"))
    }
    private fun requireOriginal(saved: CafeSavedOriginal, all: List<CafeSavedOriginal>, plan: NativeCafeCashPlan,
        evidence: NativeCafeCashEvidence) {
        val request = saved.request
        val result = saved.result
        check(result.committed && result.operationId == request.operationId && result.actorId == saved.identity.originalActorId &&
            saved.identity.semanticDigestVersion == 1 && saved.identity.semanticSha256 ==
            semanticDigest(saved.scope, result.actorId, request.command, request.payload).value)
        val historical = if (saved.symbol == "O_NATIVE_IMPORT") all.single { it.symbol == "I_START" } else saved
        val operation = table(NativeHistoryTableV2.OPERATION).single { it.text("operationId") == request.operationId.value }
        check(operation.text("scope") == cafeScope(saved.scope) && operation.text("actorId") == result.actorId.value &&
            operation.text("command") == request.command.name && operation.integer("commandVersion") == request.command.version.toLong() &&
            operation.text("digest") == saved.identity.semanticSha256 && operation.text("outcome") == result.code &&
            operation.integer("epoch") == result.authorityEpoch.value && operation.integer("sequence") == result.sequence.value &&
            operation.text("committedAt") == result.recordedAt.canonicalString() && operation.equalsStored("result", result.result) &&
            operation.text("resultHash") == sha256(operation.bytes("result")).value &&
            operation.text("authenticationId") == historical.sessionId.value && operation.integer("policyRevision") == historical.policyRevision.value)
        val journal = table(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == request.operationId.value }
        check(journal.integer("epoch") == result.authorityEpoch.value && journal.integer("sequence") == result.sequence.value &&
            journal.text("assignmentId") == saved.assignmentId.value && journal.text("hash") == sha256(journal.bytes("envelope")).value)
        val outbox = table(NativeHistoryTableV2.OUTBOX).filter { it.text("operationId") == request.operationId.value }.sortedBy { it.integer("ordinal") }
        check(outbox.isNotEmpty() && outbox.map { it.integer("ordinal") } == outbox.indices.map(Int::toLong))
        outbox.forEach { check(it.text("bodyHash") == sha256(it.bytes("body")).value) }
        check(outbox.first().text("kind") == "journal.upload" && CafeCensusSlice(outbox.first().bytes("body")).frames().map { it.text() } ==
            listOf("ukkera.journal-upload/v1", saved.scope.branchId.value, result.authorityEpoch.value.toString(),
                result.sequence.value.toString(), journal.text("hash")))
        val tag = CafeCensusBinaryReader(journal.bytes("envelope")).text(48)
        when (tag) {
            "ukkera.journal/v1", "ukkera.journal/native-original-v2" -> {
                val fields = CafeCensusSlice(journal.bytes("envelope")).frames()
                val native = tag == "ukkera.journal/native-original-v2"
                check(fields.size == (if (native) 21 else 20))
                check(fields.take(16).map { it.text() } == listOf(tag, operation.text("scope"), saved.assignmentId.value,
                    result.authorityEpoch.value.toString(), result.sequence.value.toString(), request.operationId.value,
                    result.actorId.value, request.command.name, request.command.version.toString(), historical.sessionId.value,
                    historical.policyRevision.value.toString(), saved.identity.semanticSha256, result.code,
                    result.recordedAt.canonicalString(), operation.text("resultHash"), journal.text("previousHash")) &&
                    fields[16].equalsStored(request.payload) && fields[17].equalsStored(result.result))
                check(table(NativeHistoryTableV2.JOURNAL_FRAGMENT).none { it.text("operationId") == request.operationId.value })
                val jobs = fields[19].frames().map { it.frames() }
                check(jobs.size == outbox.size - 1)
                jobs.forEachIndexed { index, job -> check(job.size == 2 && job[0].text() == outbox[index + 1].text("kind") &&
                    job[1].equalsBytes(outbox[index + 1].bytes("body"))) }
                if (native) requireNativeOriginal(fields, saved, historical, plan, evidence)
                else check(saved.symbol != "O_NATIVE_IMPORT") { "Native O was replaced with employee execute evidence" }
            }
            "ukkera.journal/root/v2" -> {
                check(saved.symbol != "O_NATIVE_IMPORT")
                requireStreamedOriginal(journal, operation, saved, outbox)
            }
            else -> error("Unsupported actual native journal format; no original or history is guessed")
        }
        val kind = if (saved.symbol == "O_NATIVE_IMPORT") "native-original.retained" else "command.${result.code}"
        val audits = table(NativeHistoryTableV2.AUDIT).filter { it.text("operationId") == request.operationId.value && it.text("kind") == kind }
        check(audits.size == 1 && audits.all { it.text("actorId") == result.actorId.value && it.text("originalActorId") == result.actorId.value &&
            it.text("digest") == saved.identity.semanticSha256 && it.integer("policyRevision") == historical.policyRevision.value &&
            it.text("recordedAt") == result.recordedAt.canonicalString() })
    }
    /** Literal historical/native provenance DATA, never a current credential or lease. */
    private fun requireNativeOriginal(fields: List<CafeCensusSlice>, saved: CafeSavedOriginal, start: CafeSavedOriginal,
        plan: NativeCafeCashPlan, evidence: NativeCafeCashEvidence) {
        check(saved.symbol == "O_NATIVE_IMPORT" && saved.request.operationId == plan.recordingOperationId &&
            start.symbol == "I_START" && start.request.operationId == plan.startOperationId)
        val provenance = fields[20].frames()
        check(provenance.size == 6 && provenance[0].text() == NativeOriginalJournalProvenanceCodecV1.TAG && provenance[1].text() == cafeScope(saved.scope))
        val startJournal = table(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == start.request.operationId.value }
        check(provenance[2].frames().map { it.text() } == listOf(start.identity.originalActorId.value, start.request.operationId.value,
            start.request.command.name, start.request.command.version.toString(), start.identity.semanticSha256, start.sessionId.value,
            start.policyRevision.value.toString(), start.assignmentId.value, start.deviceId.value, start.result.authorityEpoch.value.toString(),
            start.result.sequence.value.toString(), startJournal.text("hash"), cafeDigest(start.result.result).value,
            start.result.recordedAt.canonicalString()))
        val original = provenance[3].frames()
        val started = LocalFoodPaymentCodec.result(start.result.result) as? LocalFoodPaymentResult.AttemptStarted
            ?: error("Retained I is not the original genuine FOOD AttemptStarted result")
        check(original.size == 8 && original.take(6).map { it.text() } == listOf(LocalFoodPaymentProfile.DOMAIN,
            saved.request.operationId.value, started.requirementDigest.value,
            cafeDigest(cafeStored(evidence.binding("ORIGINAL_NATIVE_START_CERTIFICATE_DATA") as String)).value,
            cafeDigest(saved.request.payload).value, saved.identity.semanticSha256))
        val writer = provenance[4].frames()
        check(writer.size == 9 && writer[0].text() == saved.installationId.value && writer[1].text() == saved.deviceId.value &&
            writer[5].text() == saved.assignmentId.value && writer[6].text() == saved.result.authorityEpoch.value.toString() &&
            writer[7].text() == SelfOwnedNativeHistoryWireV2.PROFILE)
        val parent = provenance[5].frames()
        check(parent.size == 2 && parent[0].text().toLong() > 0 && parent[1].text().length == 64)
        for ((source, role, allocation) in listOf(Triple(original[6], "MASTER", plan.masterAllocationId),
            Triple(original[7], "CLIENT", plan.clientAllocationId))) {
            val values = source.frames()
            val row = aggregates(LocalPhysicalJournalFormatV1.SOURCE_KIND).single { it.text("id") == allocation.value }
            val projection = LocalPhysicalJournalSourceProjection.decode(AggregateChange(AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND,
                allocation), null, LocalPhysicalJournalFormatV1.SOURCE_SCHEMA, StoredBytes(row.bytes("payload"))))
            check(values.size == 11 && values.take(10).map { it.text() } == listOf(role, projection.owner.installationId.value,
                projection.owner.deviceId.value, projection.owner.catalogueId.value, projection.owner.keyId.value, allocation.value,
                projection.slotIndex.toString(), row.text("payloadHash"), projection.headerDigest.value, projection.allocationDigest.value) &&
                values[10].text() == CafeCensusSlice(row.bytes("payload")).countedFrames(4)[2].digest() && row.integer("revision") == 1L)
        }
        val writes = fields[18].frames().map { it.frames() }
        check(writes.size == 2 && writes.all { it.size == 5 && it[1].text() == saved.request.operationId.value && it[2].text() == "1" } &&
            writes.map { it[0].text() }.toSet() == setOf(LocalFoodPaymentProfile.ORIGINAL, LocalFoodPaymentProfile.APPLICATION) && fields[19].frames().isEmpty())
        val raw = writes.single { it[0].text() == LocalFoodPaymentProfile.ORIGINAL }
        val retained = aggregates(LocalFoodPaymentProfile.ORIGINAL).single()
        check(raw[3].text() == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL) && retained.integer("revision") == 1L &&
            raw[4].equalsBytes(retained.bytes("payload")))
    }

    /** Root/fragment/request/result/job DATA linkage. No retained-command authority or
     * accepting normalized producer/typed-source decoder is constructed. */
    private fun requireStreamedOriginal(journal: CafeCensusRow, operation: CafeCensusRow,
        saved: CafeSavedOriginal, outbox: List<CafeCensusRow>) {
        val request = saved.request
        val reader = CafeCensusBinaryReader(journal.bytes("envelope"))
        check(reader.text(48) == "ukkera.journal/root/v2" && reader.text(36) == saved.scope.organizationId.value &&
            reader.text(36) == saved.scope.companyId.value && reader.text(36) == saved.scope.branchId.value &&
            reader.text(36) == request.operationId.value && reader.text(96) == request.command.name && reader.int() == request.command.version &&
            reader.text(36) == saved.identity.originalActorId.value && reader.text(64) == saved.identity.semanticSha256 &&
            reader.text(36) == saved.assignmentId.value && reader.long() == saved.result.authorityEpoch.value && reader.long() == saved.result.sequence.value &&
            reader.text(30) == saved.result.recordedAt.canonicalString() && reader.text(36) == saved.sessionId.value && reader.long() == saved.policyRevision.value &&
            reader.text(64) == operation.text("resultHash") && reader.text(64) == journal.text("previousHash"))
        val fragmentCount = reader.long()
        val streamBytes = reader.long()
        val commitment = reader.text(64)
        val sectionCounts = LongArray(8)
        val sectionBytes = LongArray(8)
        repeat(8) { sectionCounts[it] = reader.long(); sectionBytes[it] = reader.long() }
        reader.end()
        check(fragmentCount in 1L..4_096L && streamBytes in 1L..(32L * 1024 * 1024) && sectionCounts[0] == 1L && sectionCounts[1] == 1L &&
            sectionCounts.all { it >= 0 } && sectionBytes.all { it >= 0 })
        val fragments = table(NativeHistoryTableV2.JOURNAL_FRAGMENT).filter { it.text("operationId") == request.operationId.value }.sortedBy { it.integer("ordinal") }
        check(fragments.size.toLong() == fragmentCount && fragments.sumOf { it.integer("byteCount") } == streamBytes)
        val stream = ByteArray(streamBytes.toInt())
        try {
            var at = 0
            var chain = cafeCensusTextHash("ukkera.journal/fragment-chain/v2")
            fragments.forEachIndexed { ordinal, fragment ->
                val bytes = fragment.bytes("payload")
                check(fragment.integer("ordinal") == ordinal.toLong() && bytes.size.toLong() == fragment.integer("byteCount") &&
                    bytes.size in 1..49_152 && (ordinal == fragments.lastIndex || bytes.size == 49_152) &&
                    fragment.text("payloadHash") == sha256(bytes).value && bytes.size <= stream.size - at)
                bytes.copyInto(stream, at); at += bytes.size
                chain = cafeCensusBinaryHash { data -> data.cafeCensusText("ukkera.journal/fragment-link/v2"); data.cafeCensusText(chain)
                    data.writeLong(ordinal.toLong()); data.writeInt(bytes.size); data.cafeCensusText(fragment.text("payloadHash")) }
            }
            check(at == stream.size && cafeCensusBinaryHash { data -> data.cafeCensusText("ukkera.journal/fragment-finish/v2"); data.cafeCensusText(chain)
                data.writeLong(fragmentCount); data.writeLong(streamBytes) } == commitment)
            val observedCounts = LongArray(8)
            val observedBytes = LongArray(8)
            var prior = 1
            val jobOrdinals = mutableSetOf<Long>()
            for (frame in CafeCensusSlice(stream).frames()) {
                val fields = CafeCensusBinaryReader(frame)
                check(fields.text(48) == "ukkera.journal/frame/v2")
                val section = fields.int()
                check(section in prior..8); prior = section
                observedCounts[section - 1]++; observedBytes[section - 1] += frame.size + 4L
                when (section) {
                    1 -> { check(fields.blob(65_536).equalsStored(request.payload)); fields.end() }
                    2 -> { check(fields.blob(65_536).equalsStored(saved.result.result)); fields.end() }
                    5 -> {
                        val ordinal = fields.long()
                        check(ordinal in 1L until outbox.size.toLong() && jobOrdinals.add(ordinal) &&
                            fields.text(96) == outbox[ordinal.toInt()].text("kind") && fields.blob(65_536).equalsBytes(outbox[ordinal.toInt()].bytes("body")))
                        fields.end()
                    }
                    else -> Unit // All frame bytes traversed; typed effect/source admission is production-owned.
                }
            }
            check(observedCounts.contentEquals(sectionCounts) && observedBytes.contentEquals(sectionBytes) &&
                sectionCounts[4] == (outbox.size - 1).toLong() && jobOrdinals == (1L until outbox.size.toLong()).toSet())
        } finally { stream.fill(0) }
    }
}

internal class CafeCensusCell(val type: NativeHistorySqliteTypeV2, val integer: Long?, val bytes: ByteArray) {
    fun fingerprint(): String = "${type.name}:${integer ?: "-"}:${sha256(bytes).value}"
}
internal class CafeCensusRow(private val cells: Map<String, CafeCensusCell>) {
    fun text(name: String): String = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.TEXT); it.bytes.decodeToString(throwOnInvalidSequence = true) }
    fun integer(name: String): Long = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.INTEGER); checkNotNull(it.integer) }
    fun bytes(name: String): ByteArray = cells.getValue(name).bytes
    fun textBytes(name: String): String = bytes(name).decodeToString(throwOnInvalidSequence = true)
    fun key(table: NativeHistoryTableV2): String = cafeCensusTextHash(table.keyColumns.joinToString("|") { cells.getValue(it).fingerprint() })
    fun fingerprint(): String = cafeCensusTextHash(cells.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value.fingerprint()}" })
    fun equalsStored(name: String, value: StoredBytes): Boolean = CafeCensusSlice(bytes(name)).equalsStored(value)
    fun clear() { cells.values.forEach { it.bytes.fill(0) } }
}
private class CafeCensusSlice(val bytes: ByteArray, val start: Int = 0, val end: Int = bytes.size) {
    val size: Int get() = end - start
    fun text(): String = bytes.decodeToString(start, end, throwOnInvalidSequence = true)
    fun equalsBytes(other: ByteArray): Boolean = size == other.size && other.indices.all { bytes[start + it] == other[it] }
    fun equalsStored(other: StoredBytes): Boolean { val copy = other.copyBytes(); return try { equalsBytes(copy) } finally { copy.fill(0) } }
    fun digest(): String { val copy = bytes.copyOfRange(start, end); return try { sha256(copy).value } finally { copy.fill(0) } }
    fun countedFrames(count: Int): List<CafeCensusSlice> {
        val reader = CafeCensusBinaryReader(this)
        check(reader.int() == count)
        val result = List(count) { reader.blob(LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES) }
        reader.end()
        return result
    }
    fun frames(): List<CafeCensusSlice> {
        check(size <= 32 * 1024 * 1024)
        val result = mutableListOf<CafeCensusSlice>()
        var at = start
        while (at < end) {
            check(result.size < 16_384 && end - at >= 4)
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[at++].toLong() and 255L) }
            check(length <= end - at)
            result += CafeCensusSlice(bytes, at, at + length.toInt()); at += length.toInt()
        }
        check(at == end)
        return result
    }
}
private class CafeCensusBinaryReader(private val source: CafeCensusSlice) {
    constructor(bytes: ByteArray) : this(CafeCensusSlice(bytes))
    private var at = source.start
    fun int(): Int { check(source.end - at >= 4); var value = 0; repeat(4) { value = (value shl 8) or (source.bytes[at++].toInt() and 255) }; return value }
    fun long(): Long { check(source.end - at >= 8); var value = 0L; repeat(8) { value = (value shl 8) or (source.bytes[at++].toLong() and 255L) }; return value }
    fun blob(maximum: Int): CafeCensusSlice { val size = int(); check(size in 0..maximum && size <= source.end - at); return CafeCensusSlice(source.bytes, at, at + size).also { at += size } }
    fun text(maximum: Int): String = blob(maximum).text()
    fun end() { check(at == source.end) }
}
private fun cafeCensusTextHash(value: String): String {
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}
private fun cafeCensusBinaryHash(write: (DataOutputStream) -> Unit): String {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use(write)
    val bytes = output.toByteArray()
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}
private fun DataOutputStream.cafeCensusText(value: String) {
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
}
