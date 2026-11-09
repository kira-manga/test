package com.ukkera.pos.android.independent.coldoriginalstartv1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Supplementary observations, never a full history verifier or a public cost read.
 * No raw native buffers, protected images, source DTOs or native owner escape the read.
 * Normal cold Master opens must still perform genuine complete production admission. */
internal object NativeColdOriginalCensus {
    private const val MAX_ROWS_PER_TABLE = 4_096
    private const val MAX_RETAINED_BYTES = 32L * 1024 * 1024
    private val retainedTables = setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.OPERATION,
        NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM, NativeHistoryTableV2.JOURNAL,
        NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.OUTBOX, NativeHistoryTableV2.JOURNAL_FRAGMENT,
        NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION,
        NativeHistoryTableV2.STOCK_RESERVATION, NativeHistoryTableV2.STOCK_VALUE)

    suspend fun read(custody: SelfOwnedNativeHistoryCustody, coordinates: SelfOwnedAllocationCoordinatesV1,
        plan: NativeColdOriginalFixturePlan, originals: List<ColdSavedOriginal>, checkpoint: String,
        label: String, evidence: NativeLocalFixtureEvidence): ColdNativeBusinessCensus = custody.runRoomWork(block = {
        check(checkpoint == "C3" && originals.map { it.symbol }.toSet() ==
            setOf("ORIGINAL_START", "ORIGINAL_OUTCOME", "ORIGINAL_APPLICATION", "ORIGINAL_FINALIZATION") && originals.size == 4)
        check(originals.all { it.scope == coordinates.scope && it.identity.originalActorId == coordinates.firstActorId &&
            it.deviceId == coordinates.deviceId && it.installationId == coordinates.installationId })
        val read = custody.openOriginalReadOnly()
        val rows = retainedTables.associateWith { mutableListOf<ColdCensusRow>() }
        val pending = mutableListOf<ByteArray>()
        var closeStarted = false
        var primary: Throwable? = null
        try {
            requireSameNativeFixtureCoordinates(coordinates, read.original.record.allocation.coordinates)
            val counts = NativeHistoryTableV2.entries.associateWith { 0L }.toMutableMap()
            val complete = mutableSetOf<NativeHistoryTableV2>()
            var retainedBytes = 0L
            while (read.readSchemaPage() != null) { /* Consume every native schema page. */ }
            while (true) {
                val metadata = read.nextRowMetadata() ?: break
                val census = metadata.pass == NativeHistoryReadPassV2.CENSUS
                val table = metadata.table
                if (census && metadata.kind == NativeHistoryRowKindV2.PASS_END)
                    check(complete.add(checkNotNull(table)))
                val keep = census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL && table in retainedTables
                val cells = linkedMapOf<String, ColdCensusCell>()
                for ((index, cell) in metadata.cells.withIndex()) {
                    check(cell.byteCount in 0..NativeHistoryPhysicalReadProfileV2.MAX_CELL_BYTES)
                    if (keep) {
                        check(cell.byteCount <= MAX_RETAINED_BYTES - retainedBytes) {
                            "The bounded synthetic native census is oversized; no data are truncated"
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
                    if (keep) check(cells.put(cell.name, ColdCensusCell(cell.type, cell.integerValue, checkNotNull(kept))) == null)
                }
                if (census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL) {
                    val actual = checkNotNull(table)
                    counts[actual] = counts.getValue(actual) + 1
                    check(counts.getValue(actual) <= MAX_ROWS_PER_TABLE) {
                        "The bounded synthetic native table is oversized; no rows are truncated"
                    }
                    if (keep) {
                        rows.getValue(actual) += ColdCensusRow(cells)
                        cells.values.forEach { pending.remove(it.bytes) }
                    }
                }
            }
            check(complete.containsAll(NativeHistoryPhysicalReadProfileV2.applicationTables)) {
                "The complete application-table census did not reach every actual PASS_END"
            }
            closeStarted = true
            val closed = withContext(NonCancellable) { read.closeOriginalRead() }
            val after = custody.reobserveAfterActualClose(closed)
            check(after.lifetimeId == read.original.lifetimeId && after.recordDigest == read.original.recordDigest &&
                after.record.database == read.original.record.database)
            custody.completeOriginalReadDelivery(after)

            val cut = ColdCensusCut(rows.mapValues { it.value.toList() })
            cut.requireBranch(coordinates)
            cut.requireJournalHead()
            originals.forEach { cut.requireOriginal(it, originals, plan) }
            val bindings = cut.requireCounts(checkpoint, plan)
            val fingerprints = rows.mapValues { (table, values) -> values.associate { it.key(table) to it.fingerprint() }
                .also { check(it.size == values.size) } }
            val audits = rows.getValue(NativeHistoryTableV2.AUDIT).associate { row -> row.key(NativeHistoryTableV2.AUDIT) to
                ColdNativeAuditFingerprint(row.fingerprint(), row.text("kind"), row.text("operationId")) }
            val result = ColdNativeBusinessCensus(cut.branch.integer("epoch"), cut.branch.integer("revision"),
                counts.toMap(), cut.table(NativeHistoryTableV2.OPERATION).map { it.text("operationId") }.toSet(),
                bindings, fingerprints.filterKeys { it != NativeHistoryTableV2.AUDIT }, audits)
            evidence.event(label, JSONObject().put("checkpoint", checkpoint).put("nativeReadActualClose", true)
                .put("completeApplicationTableCensus", true).put("retainedBusinessBytes", retainedBytes)
                .put("authorityEpoch", result.epoch).put("branchRevision", result.branchRevision)
                .put("physicalSourceRowCounts", JSONObject().also { json -> counts.filterKeys { it.application }
                    .forEach { (table, count) -> json.put(table.name, count) } })
                .put("boundNativeCounts", bindings.json())
                .put("businessFingerprints", JSONObject().also { json -> fingerprints.forEach { (table, values) ->
                    json.put(table.name, JSONObject(values))
                } })
                .put("retainedOriginalCanonicalRequestsChecked", originals.map { it.symbol }.joinToString(","))
                .put("physicalInstructionCount", "UNBOUND_NOT_INFERRED_FROM_SLOT_OR_SOURCE_ROWS")
                .put("paymentReceiptCount", "UNBOUND_NOT_INFERRED_FROM_PAID_SALE_OR_ENTITLEMENT")
                .put("originalSaleCost", "NOT_INFERRED_FROM_CENSUS_SEE_PROTECTED_HISTORY_CHECKPOINT")
                .put("fullProductionHistoryAdmission", "NOT_CLAIMED_BY_CENSUS"))
            result
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            pending.forEach { it.fill(0) }
            rows.values.forEach { values -> values.forEach(ColdCensusRow::clear) }
            if (!closeStarted) try { withContext(NonCancellable) { read.closeOriginalRead() } }
            catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }, discard = { custody.denyDataUse() })
}

/** Actual retained-source counts. Slot/source rows are NOT physical instruction counts. */
internal data class ColdNativeCountBindings(val slotCount: Int, val durableOriginalFactCount: Int,
    val appliedCollectionApplicationCount: Int, val paidSaleCount: Int) {
    fun json(): JSONObject = JSONObject().put("slotCount", slotCount).put("durableOriginalFactCount", durableOriginalFactCount)
        .put("appliedCollectionApplicationCount", appliedCollectionApplicationCount).put("paidSaleCount", paidSaleCount)
}

internal class ColdNativeBusinessCensus internal constructor(val epoch: Long, val branchRevision: Long,
    val tableCounts: Map<NativeHistoryTableV2, Long>, val operationIds: Set<String>, val counts: ColdNativeCountBindings,
    private val fingerprints: Map<NativeHistoryTableV2, Map<String, String>>,
    private val audits: Map<String, ColdNativeAuditFingerprint>) {
    fun requireSameBusiness(after: ColdNativeBusinessCensus) {
        check(epoch == after.epoch && branchRevision == after.branchRevision && counts == after.counts &&
            operationIds == after.operationIds && fingerprints == after.fingerprints) {
            "Same-storage recovery changed actual native business evidence"
        }
        requireAuditPreservation(after)
        val added = after.audits.filterKeys { it !in audits }.values
        check(added.all { it.kind == "result.supervisor-read" && it.operationId in operationIds }) {
            "Cold recovery introduced a durable business audit rather than a protected historical read"
        }
    }

    private fun requireAuditPreservation(after: ColdNativeBusinessCensus) {
        check(audits.all { (key, value) -> after.audits[key] == value }) { "An original durable audit was deleted or rewritten" }
    }
    override fun toString(): String = "ColdNativeBusinessCensus([REDACTED])"
}

internal data class ColdNativeAuditFingerprint(val fingerprint: String, val kind: String, val operationId: String)

private class ColdCensusCut(private val rows: Map<NativeHistoryTableV2, List<ColdCensusRow>>) {
    val branch: ColdCensusRow get() = table(NativeHistoryTableV2.BRANCH).single()
    fun table(table: NativeHistoryTableV2): List<ColdCensusRow> = rows.getValue(table)
    private fun aggregates(kind: String): List<ColdCensusRow> = table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") == kind }

    fun requireBranch(coordinates: SelfOwnedAllocationCoordinatesV1) {
        check(branch.text("scope") == coldCensusScope(coordinates.scope) && branch.text("assignmentId") == coordinates.assignmentId.value &&
            branch.text("deviceId") == coordinates.deviceId.value)
        rows.values.forEach { values -> values.forEach { check(it.text("scope") == branch.text("scope")) } }
        rows.forEach { (table, values) -> check(values.map { it.key(table) }.distinct().size == values.size) }
    }

    fun requireCounts(checkpoint: String, plan: NativeColdOriginalFixturePlan): ColdNativeCountBindings {
        val slots = aggregates(LocalFinancialJournalProfileV3.SLOT_KIND)
        val originals = aggregates(LocalFinancialJournalProfileV3.ORIGINAL_KIND)
        val applications = aggregates(LocalFinancialJournalProfileV3.APPLICATION_KIND)
        val sales = aggregates(LocalSalesStoreProfile.SALE_KIND)
        val expected = plan.checkpoint(checkpoint)
        val applied = applications.filter { JSONObject(it.textBytes("payload")).getString("state") == "APPLIED" }
        check(originals.size == expected.getInt("captured_outcome_count") &&
            applied.size == expected.getInt("collection_application_count") && sales.size == expected.getInt("sale_count"))
        check(aggregates(LocalFinancialJournalProfileV3.RESOLUTION_KIND).isEmpty())
        check(checkpoint == "C3")
        run {
            check(slots.size == 1 && originals.size == 1 && applications.size == 1 && sales.size == 1)
            for ((values, schema) in listOf(slots to LocalFinancialJournalProfileV3.SLOT_SCHEMA,
                originals to LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA, applications to LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)) {
                val row = values.single()
                check(row.text("id") == plan.recordingOperationId.value && row.text("schema") == schema &&
                    row.text("payloadHash") == sha256(row.bytes("payload")).value)
            }
            val application = JSONObject(applications.single().textBytes("payload"))
            check(application.getString("phase") == "ORIGINAL" && application.getString("state") == "APPLIED" &&
                application.isNull("reason") && application.getString("lastApplicationOperationId") == plan.applicationOperationId.value &&
                application.getString("appliedAction") == "PAYMENT_RECORDED")
            val target = application.getJSONObject("target")
            check(target.getString("recordingOperationId") == plan.recordingOperationId.value &&
                target.getString("intentOperationId") == plan.startOperationId.value && target.getString("documentId") == plan.collectionId.value &&
                target.getString("sessionId") == plan.cashSessionId.value && target.getString("attemptId") == plan.attemptId.value)
            val sale = sales.single()
            check(sale.text("id") == plan.saleId.value && sale.text("schema") == LocalSalesStoreProfile.schema(LocalSalesStoreProfile.SALE_KIND) &&
                sale.text("payloadHash") == sha256(sale.bytes("payload")).value)
        }
        return ColdNativeCountBindings(slots.size, originals.size, applied.size, sales.size)
    }

    fun requireOriginal(saved: ColdSavedOriginal, all: List<ColdSavedOriginal>, plan: NativeColdOriginalFixturePlan) {
        val request = saved.request
        val result = saved.result
        check(result.committed && result.operationId == request.operationId && result.actorId == saved.identity.originalActorId &&
            saved.identity.semanticDigestVersion == 1 && saved.identity.semanticSha256 ==
            semanticDigest(saved.scope, result.actorId, request.command, request.payload).value)
        val operation = table(NativeHistoryTableV2.OPERATION).single { it.text("operationId") == request.operationId.value }
        check(operation.text("scope") == coldCensusScope(saved.scope) && operation.text("actorId") == result.actorId.value &&
            operation.text("command") == request.command.name && operation.integer("commandVersion") == request.command.version.toLong() &&
            operation.text("digest") == saved.identity.semanticSha256 && operation.text("outcome") == result.code &&
            operation.integer("epoch") == result.authorityEpoch.value && operation.integer("sequence") == result.sequence.value &&
            operation.text("committedAt") == result.recordedAt.canonicalString() && operation.equalsStored("result", result.result) &&
            operation.text("resultHash") == sha256(operation.bytes("result")).value &&
            operation.text("authenticationId") == saved.sessionId.value && operation.integer("policyRevision") == saved.policyRevision.value)
        val journal = table(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == request.operationId.value }
        check(journal.text("scope") == operation.text("scope") && journal.integer("epoch") == result.authorityEpoch.value &&
            journal.integer("sequence") == result.sequence.value && journal.text("assignmentId") == saved.assignmentId.value &&
            journal.text("hash") == sha256(journal.bytes("envelope")).value)
        val obligations = table(NativeHistoryTableV2.OUTBOX).filter { it.text("operationId") == request.operationId.value }.sortedBy { it.integer("ordinal") }
        check(obligations.isNotEmpty() && obligations.map { it.integer("ordinal") } == obligations.indices.map(Int::toLong))
        obligations.forEach { check(it.text("bodyHash") == sha256(it.bytes("body")).value) }
        check(obligations.first().text("kind") == "journal.upload")
        check(ColdCensusSlice(obligations.first().bytes("body")).frames().map { it.text() } == listOf("ukkera.journal-upload/v1",
            saved.scope.branchId.value, result.authorityEpoch.value.toString(), result.sequence.value.toString(), journal.text("hash")))
        val tag = ColdCensusBinaryReader(journal.bytes("envelope")).text(48)
        when (tag) {
            "ukkera.journal/v1", "ukkera.journal/native-original-v2" -> {
                val fields = ColdCensusSlice(journal.bytes("envelope")).frames()
                val native = tag == "ukkera.journal/native-original-v2"
                check(fields.size == if (native) 21 else 20)
                val metadata = listOf(tag, operation.text("scope"), saved.assignmentId.value, result.authorityEpoch.value.toString(),
                    result.sequence.value.toString(), request.operationId.value, result.actorId.value, request.command.name,
                    request.command.version.toString(), saved.sessionId.value, saved.policyRevision.value.toString(),
                    saved.identity.semanticSha256, result.code, result.recordedAt.canonicalString(), operation.text("resultHash"), journal.text("previousHash"))
                check(fields.take(16).map { it.text() } == metadata && fields[16].equalsStored(request.payload) && fields[17].equalsStored(result.result))
                check(table(NativeHistoryTableV2.JOURNAL_FRAGMENT).none { it.text("operationId") == request.operationId.value })
                val jobs = fields[19].frames().map { it.frames() }
                check(jobs.size == obligations.size - 1)
                jobs.forEachIndexed { i, values -> check(values.size == 2 && values[0].text() == obligations[i + 1].text("kind") &&
                    values[1].equalsBytes(obligations[i + 1].bytes("body"))) }
                if (native) requireNativeOriginal(fields, saved, all.single { it.symbol == "ORIGINAL_START" }, plan)
                else check(saved.symbol != "ORIGINAL_OUTCOME") { "The captured native original was replaced with employee execute evidence" }
            }
            "ukkera.journal/root/v2" -> {
                check(saved.symbol != "ORIGINAL_OUTCOME")
                requireStreamedOriginal(journal, operation, saved, obligations)
            }
            else -> error("Unsupported original native journal format; no history or canonical request is guessed")
        }
        val auditKind = if (saved.symbol == "ORIGINAL_OUTCOME") "native-original.retained" else "command.${result.code}"
        check(table(NativeHistoryTableV2.AUDIT).count { it.text("operationId") == request.operationId.value && it.text("kind") == auditKind } == 1)
    }

    /** Literal retained provenance checks only. The header authentication columns are the
     * HISTORICAL Start's attributes; they are not a current employee grant for native import. */
    private fun requireNativeOriginal(fields: List<ColdCensusSlice>, saved: ColdSavedOriginal,
        start: ColdSavedOriginal, plan: NativeColdOriginalFixturePlan) {
        check(saved.symbol == "ORIGINAL_OUTCOME" && saved.request.operationId == plan.recordingOperationId &&
            start.request.operationId == plan.startOperationId && saved.sessionId == start.sessionId && saved.policyRevision == start.policyRevision)
        val provenance = fields[20].frames()
        check(provenance.size == 6 && provenance[0].text() == NativeOriginalJournalProvenanceCodecV1.TAG &&
            provenance[1].text() == coldCensusScope(saved.scope))
        val historical = provenance[2].frames()
        val startJournal = table(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == start.request.operationId.value }
        check(historical.map { it.text() } == listOf(start.identity.originalActorId.value, start.request.operationId.value,
            start.request.command.name, start.request.command.version.toString(), start.identity.semanticSha256,
            start.sessionId.value, start.policyRevision.value.toString(), start.assignmentId.value, start.deviceId.value,
            start.result.authorityEpoch.value.toString(), start.result.sequence.value.toString(), startJournal.text("hash"),
            coldDigest(start.result.result).value, start.result.recordedAt.canonicalString()))
        val original = provenance[3].frames()
        val started = LocalFinancialResultCodecV3.decode(start.result.result) as? LocalFinancialResultV3.AttemptStarted
            ?: error("Retained original Start is not an actual V3 AttemptStarted result")
        check(original.size == 8 && original[0].text() == LocalFinancialJournalProfileV3.PAYMENT_PROFILE &&
            original[1].text() == saved.request.operationId.value && original[2].text() == started.requirementDigest &&
            original[4].text() == coldDigest(saved.request.payload).value &&
            original[5].text() == saved.identity.semanticSha256)
        val writer = provenance[4].frames()
        check(writer.size == 9 && writer[0].text() == saved.installationId.value && writer[1].text() == saved.deviceId.value &&
            writer[5].text() == saved.assignmentId.value &&
            writer[6].text() == saved.result.authorityEpoch.value.toString() && writer[7].text() == SelfOwnedNativeHistoryWireV2.PROFILE)
        // The core physical writer incarnation is distinct from the Android client lifetime.
        // Its literal bytes are fingerprinted, never substituted with the wrapper's ID.
        val parent = provenance[5].frames()
        check(parent.size == 2 && parent[0].text().toLong() > 0 && parent[1].text().length == 64)
        for ((source, role, allocation) in listOf(Triple(original[6], "MASTER", plan.masterAllocationId),
            Triple(original[7], "CLIENT", plan.clientAllocationId))) {
            val values = source.frames()
            check(values.size == 11 && values[0].text() == role && values[1].text() == saved.installationId.value &&
                values[2].text() == saved.deviceId.value && values[5].text() == allocation.value)
            val row = aggregates(LocalPhysicalJournalFormatV1.SOURCE_KIND).single { it.text("id") == allocation.value }
            check(row.integer("revision") == 1L && row.text("payloadHash") == values[7].text() &&
                row.text("payloadHash") == sha256(row.bytes("payload")).value)
        }
        val writes = fields[18].frames().map { it.frames() }
        check(writes.size == 2 && writes.all { it.size == 5 && it[1].text() == saved.request.operationId.value && it[2].text() == "1" } &&
            writes.map { it[0].text() }.toSet() == setOf(LocalFinancialJournalProfileV3.ORIGINAL_KIND, LocalFinancialJournalProfileV3.APPLICATION_KIND) &&
            fields[19].frames().isEmpty())
        val raw = writes.single { it[0].text() == LocalFinancialJournalProfileV3.ORIGINAL_KIND }
        val retainedRaw = aggregates(LocalFinancialJournalProfileV3.ORIGINAL_KIND).single()
        check(raw[3].text() == LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA && retainedRaw.integer("revision") == 1L &&
            raw[4].equalsBytes(retainedRaw.bytes("payload")))
    }

    /** Exact root/fragment/request/result linkage, not typed effects/source admission. */
    private fun requireStreamedOriginal(journal: ColdCensusRow, operation: ColdCensusRow,
        saved: ColdSavedOriginal, outbox: List<ColdCensusRow>) {
        val request = saved.request
        val reader = ColdCensusBinaryReader(journal.bytes("envelope"))
        check(reader.text(48) == "ukkera.journal/root/v2" && reader.text(36) == saved.scope.organizationId.value &&
            reader.text(36) == saved.scope.companyId.value && reader.text(36) == saved.scope.branchId.value &&
            reader.text(36) == request.operationId.value && reader.text(96) == request.command.name && reader.int() == request.command.version &&
            reader.text(36) == saved.identity.originalActorId.value && reader.text(64) == saved.identity.semanticSha256 &&
            reader.text(36) == saved.assignmentId.value && reader.long() == saved.result.authorityEpoch.value &&
            reader.long() == saved.result.sequence.value && reader.text(30) == saved.result.recordedAt.canonicalString() &&
            reader.text(36) == saved.sessionId.value && reader.long() == saved.policyRevision.value &&
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
            var chain = coldCensusTextHash("ukkera.journal/fragment-chain/v2")
            fragments.forEachIndexed { ordinal, fragment ->
                val bytes = fragment.bytes("payload")
                check(fragment.integer("ordinal") == ordinal.toLong() && bytes.size.toLong() == fragment.integer("byteCount") &&
                    bytes.size in 1..49_152 && (ordinal == fragments.lastIndex || bytes.size == 49_152) &&
                    fragment.text("payloadHash") == sha256(bytes).value)
                bytes.copyInto(stream, at); at += bytes.size
                chain = coldCensusBinaryHash { data ->
                    data.coldCensusText("ukkera.journal/fragment-link/v2"); data.coldCensusText(chain)
                    data.writeLong(ordinal.toLong()); data.writeInt(bytes.size); data.coldCensusText(fragment.text("payloadHash"))
                }
            }
            check(at == stream.size && coldCensusBinaryHash { data ->
                data.coldCensusText("ukkera.journal/fragment-finish/v2"); data.coldCensusText(chain)
                data.writeLong(fragmentCount); data.writeLong(streamBytes)
            } == commitment)
            val observedCounts = LongArray(8)
            val observedBytes = LongArray(8)
            var prior = 1
            for (frame in ColdCensusSlice(stream).frames()) {
                val fields = ColdCensusBinaryReader(frame)
                check(fields.text(48) == "ukkera.journal/frame/v2")
                val section = fields.int()
                check(section in prior..8); prior = section
                observedCounts[section - 1]++
                observedBytes[section - 1] += frame.size + 4L
                when (section) {
                    1 -> { check(fields.blob(65_536).equalsStored(request.payload)); fields.end() }
                    2 -> { check(fields.blob(65_536).equalsStored(saved.result.result)); fields.end() }
                    5 -> {
                        val ordinal = fields.long()
                        check(ordinal in 1L until outbox.size.toLong() && fields.text(96) == outbox[ordinal.toInt()].text("kind") &&
                            fields.blob(65_536).equalsBytes(outbox[ordinal.toInt()].bytes("body")))
                        fields.end()
                    }
                    else -> Unit // Entire bytes consumed; no fabricated source/effect DTOs.
                }
            }
            check(observedCounts.contentEquals(sectionCounts) && observedBytes.contentEquals(sectionBytes) && sectionCounts[4] == (outbox.size - 1).toLong())
        } finally { stream.fill(0) }
    }

    fun requireJournalHead() {
        val journals = table(NativeHistoryTableV2.JOURNAL).sortedBy { it.integer("sequence") }
        check(journals.isNotEmpty() && journals.all { it.integer("epoch") == branch.integer("epoch") &&
            it.text("hash") == sha256(it.bytes("envelope")).value })
        check(journals.zipWithNext().all { (a, b) -> b.integer("sequence") > a.integer("sequence") && b.text("previousHash") == a.text("hash") })
        check(branch.integer("sequence") == journals.last().integer("sequence") && branch.text("journalHash") == journals.last().text("hash"))
    }
}

private class ColdCensusCell(val type: NativeHistorySqliteTypeV2, val integer: Long?, val bytes: ByteArray) {
    fun fingerprint(): String = "${type.name}:${integer ?: "-"}:${sha256(bytes).value}"
}
private class ColdCensusRow(private val cells: Map<String, ColdCensusCell>) {
    fun text(name: String): String = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.TEXT); it.bytes.decodeToString(throwOnInvalidSequence = true) }
    fun integer(name: String): Long = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.INTEGER); checkNotNull(it.integer) }
    fun bytes(name: String): ByteArray = cells.getValue(name).bytes
    fun textBytes(name: String): String = bytes(name).decodeToString(throwOnInvalidSequence = true)
    fun key(table: NativeHistoryTableV2): String = coldCensusTextHash(table.keyColumns.joinToString("|") { cells.getValue(it).fingerprint() })
    fun fingerprint(): String = coldCensusTextHash(cells.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value.fingerprint()}" })
    fun equalsStored(name: String, value: StoredBytes): Boolean = ColdCensusSlice(bytes(name)).equalsStored(value)
    fun clear() { cells.values.forEach { it.bytes.fill(0) } }
}
private class ColdCensusSlice(val bytes: ByteArray, val start: Int = 0, val end: Int = bytes.size) {
    val size: Int get() = end - start
    fun text(): String = bytes.decodeToString(start, end, throwOnInvalidSequence = true)
    fun equalsBytes(other: ByteArray): Boolean = size == other.size && other.indices.all { bytes[start + it] == other[it] }
    fun equalsStored(other: StoredBytes): Boolean { val copy = other.copyBytes(); return try { equalsBytes(copy) } finally { copy.fill(0) } }
    fun frames(): List<ColdCensusSlice> {
        check(size <= 32 * 1024 * 1024)
        val result = mutableListOf<ColdCensusSlice>()
        var at = start
        while (at < end) {
            check(result.size < 16_384 && end - at >= 4)
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[at++].toLong() and 255L) }
            check(length <= end - at)
            result += ColdCensusSlice(bytes, at, at + length.toInt()); at += length.toInt()
        }
        check(at == end)
        return result
    }
}
private class ColdCensusBinaryReader(private val source: ColdCensusSlice) {
    constructor(bytes: ByteArray) : this(ColdCensusSlice(bytes))
    private var at = source.start
    fun int(): Int { check(source.end - at >= 4); var value = 0; repeat(4) { value = (value shl 8) or (source.bytes[at++].toInt() and 255) }; return value }
    fun long(): Long { check(source.end - at >= 8); var value = 0L; repeat(8) { value = (value shl 8) or (source.bytes[at++].toLong() and 255L) }; return value }
    fun blob(maximum: Int): ColdCensusSlice { val size = int(); check(size in 0..maximum && size <= source.end - at); return ColdCensusSlice(source.bytes, at, at + size).also { at += size } }
    fun text(maximum: Int): String = blob(maximum).text()
    fun end() { check(at == source.end) }
}
private fun coldCensusScope(scope: BranchScope): String = listOf(scope.organizationId.value, scope.companyId.value, scope.branchId.value).joinToString("/")
private fun coldCensusTextHash(value: String): String { val bytes = value.encodeToByteArray(throwOnInvalidSequence = true); return try { sha256(bytes).value } finally { bytes.fill(0) } }
private fun coldCensusBinaryHash(write: (DataOutputStream) -> Unit): String {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use(write)
    val bytes = output.toByteArray()
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}
private fun DataOutputStream.coldCensusText(value: String) {
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
}
