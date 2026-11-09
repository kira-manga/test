package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Supplementary DATA observations through genuine closed native custody. This is not
 * an accepting history scanner, a command decoder, a live second reader or a cost API.
 * Normal production reopen independently owns complete source/history admission. */
internal object NativeFoodColdCensus {
    private const val MAX_ROWS_PER_TABLE = 4_096
    private const val MAX_RETAINED_BYTES = 32L * 1024 * 1024
    private val retainedTables = setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.OPERATION,
        NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM, NativeHistoryTableV2.JOURNAL,
        NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.OUTBOX, NativeHistoryTableV2.JOURNAL_FRAGMENT,
        NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION,
        NativeHistoryTableV2.STOCK_RESERVATION, NativeHistoryTableV2.STOCK_VALUE)

    suspend fun read(custody: SelfOwnedNativeHistoryCustody, coordinates: SelfOwnedAllocationCoordinatesV1,
        plan: NativeFoodColdInputPlan, originals: List<FoodColdSavedOriginal>, seed: FoodColdSeedBindings?,
        protected: FoodColdProtectedSeedCut?, initial: FoodColdNativeCut?, label: String,
        evidence: NativeFoodColdEvidence): FoodColdNativeCut = custody.runRoomWork(block = {
        check((seed == null) == (protected == null) && (seed == null) == (initial == null))
        val read = custody.openOriginalReadOnly()
        val rows = retainedTables.associateWith { mutableListOf<FoodColdCensusRow>() }
        val pending = mutableListOf<ByteArray>()
        var closeStarted = false
        var primary: Throwable? = null
        try {
            requireSameNativeFixtureCoordinates(coordinates, read.original.record.allocation.coordinates)
            check(read.original.record.pending == null) { "An unresolved native original is not an orderly settled seed" }
            val counts = NativeHistoryTableV2.entries.associateWith { 0L }.toMutableMap()
            val complete = mutableSetOf<NativeHistoryTableV2>()
            var retainedBytes = 0L
            while (read.readSchemaPage() != null) { /* Consume every actual schema page to EOF. */ }
            while (true) {
                val metadata = read.nextRowMetadata() ?: break
                val table = metadata.table
                val census = metadata.pass == NativeHistoryReadPassV2.CENSUS
                if (census && metadata.kind == NativeHistoryRowKindV2.PASS_END)
                    check(complete.add(checkNotNull(table)))
                val keep = census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL && table in retainedTables
                val cells = linkedMapOf<String, FoodColdCensusCell>()
                var rowBytes = 0L
                for ((index, cell) in metadata.cells.withIndex()) {
                    check(cell.byteCount in 0..NativeHistoryPhysicalReadProfileV2.MAX_CELL_BYTES)
                    rowBytes += cell.byteCount
                    check(rowBytes <= NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES)
                    if (keep) {
                        check(cell.byteCount <= MAX_RETAINED_BYTES - retainedBytes) {
                            "The bounded synthetic census is oversized; no native DATA are truncated"
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
                    if (keep) check(cells.put(cell.name,
                        FoodColdCensusCell(cell.type, cell.integerValue, checkNotNull(kept))) == null)
                }
                if (census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL) {
                    val actual = checkNotNull(table)
                    counts[actual] = counts.getValue(actual) + 1
                    check(counts.getValue(actual) <= MAX_ROWS_PER_TABLE) {
                        "The bounded synthetic table is oversized; no native rows are truncated"
                    }
                    if (keep) {
                        rows.getValue(actual) += FoodColdCensusRow(cells)
                        cells.values.forEach { pending.remove(it.bytes) }
                    }
                }
            }
            check(complete == NativeHistoryPhysicalReadProfileV2.applicationTables.toSet()) {
                "Every actual application-table CENSUS PASS_END is required"
            }
            val cut = FoodColdCensusRows(rows.mapValues { it.value.toList() })
            cut.requireBranch(coordinates, plan)
            val proofData = if (seed == null) {
                check(originals.isEmpty() && read.businessPublicationObservations.isEmpty())
                cut.requireInitialNoBusiness(counts)
                emptyMap()
            } else {
                check(read.hasBusinessPublicationObservationTraversal) {
                    "The genuine original publication observation traversal is unavailable"
                }
                cut.requireOriginals(originals, checkNotNull(initial), coordinates)
                NativeFoodColdOriginalData.require(cut, plan, seed, checkNotNull(protected), originals)
                cut.requirePublicationProofs(read.businessPublicationObservations, seed, originals)
            }
            // Detach only hash/scalar observations while the source owner still owns its
            // genuine selection. No raw row, native source, reader or capability escapes.
            val fingerprints = rows.mapValues { (table, values) -> values.associate { it.key(table) to it.fingerprint() }
                .also { check(it.size == values.size) } }
            val audits = rows.getValue(NativeHistoryTableV2.AUDIT).associate { row -> row.key(NativeHistoryTableV2.AUDIT) to
                FoodColdAuditFingerprint(row.fingerprint(), row.text("kind"), row.text("operationId")) }
            val result = FoodColdNativeCut(cut.branch.integer("epoch"), cut.branch.integer("revision"),
                cut.branch.integer("sequence"), cut.branch.text("journalHash"), counts.toMap(),
                originals.map { it.request.operationId.value }.toSet(),
                fingerprints.filterKeys { it != NativeHistoryTableV2.AUDIT }, audits, proofData.mapValues { foodColdTextHash(it.value.toString()) })
            closeStarted = true
            val closed = withContext(NonCancellable) { read.closeOriginalRead() }
            val after = custody.reobserveAfterActualClose(closed)
            check(after.lifetimeId == read.original.lifetimeId && after.recordDigest == read.original.recordDigest &&
                after.record.database == read.original.record.database)
            custody.completeOriginalReadDelivery(after) // Actual read delivery only, not Room/writer admission.
            evidence.event(label, JSONObject().put("nativeReadActualClose", true).put("completeApplicationTableCensus", true)
                .put("authorityEpoch", result.epoch).put("branchRevision", result.branchRevision).put("commandHeadSequence", result.headSequence)
                .put("retainedBytes", retainedBytes).put("physicalSourceRowCounts", JSONObject().also { json ->
                    counts.filterKeys { it.application }.forEach { (table, count) -> json.put(table.name, count) } })
                .put("originalOperationIds", JSONArray(originals.map { it.request.operationId.value }))
                .put("originalPublicationProofs", JSONArray(proofData.values.toList()))
                .put("publicationObservationProofTiming", if (seed == null) "NONE_AT_INITIAL_NO_BUSINESS_CUT"
                    else "FIRST_CLOSED_SEED_BOUNDARY_OR_LATER_NOT_PRE_CLOSE")
                .put("closedFoodNativeOriginalEventSourceProof", "UNBOUND")
                .put("requiredPreCloseFoodProducerSourceProof", "UNBOUND")
                .put("foodNativeProofLimit", "PUBLICATION_OBSERVATIONS_AND_CENSUS_DATA_DO_NOT_BIND_FOOD_NATIVE_ORIGINAL_EVENT_SOURCE_JOINS")
                .put("businessFingerprints", JSONObject().also { json -> fingerprints.forEach { (table, values) ->
                    json.put(table.name, JSONObject(values)) } })
                .put("fullProductionHistoryAdmission", "NOT_CLAIMED_BY_CENSUS")
                .put("outboxMeaning", "ORIGINAL_IMMUTABLE_OBLIGATIONS_NOT_DELIVERY_ACK_OR_PHYSICAL_PRINT"))
            result
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            pending.forEach { it.fill(0) }
            rows.values.forEach { values -> values.forEach(FoodColdCensusRow::clear) }
            if (!closeStarted) try { withContext(NonCancellable) { read.closeOriginalRead() } }
            catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }, discard = { custody.denyDataUse() })
}

internal class FoodColdNativeCut(val epoch: Long, val branchRevision: Long, val headSequence: Long, val headHash: String,
    val tableCounts: Map<NativeHistoryTableV2, Long>, val operationIds: Set<String>,
    private val fingerprints: Map<NativeHistoryTableV2, Map<String, String>>,
    private val audits: Map<String, FoodColdAuditFingerprint>, private val publicationProofs: Map<String, String>) {
    fun requireSameBusiness(after: FoodColdNativeCut) {
        check(epoch == after.epoch && branchRevision == after.branchRevision && headSequence == after.headSequence &&
            headHash == after.headHash && operationIds == after.operationIds && fingerprints == after.fingerprints &&
            publicationProofs == after.publicationProofs) { "Cold read-only recovery changed an original business/native source cut" }
        check(audits.all { (key, value) -> after.audits[key] == value }) { "An original audit was deleted or rewritten" }
        check(after.audits.filterKeys { it !in audits }.values.all {
            it.kind == "result.supervisor-read" && it.operationId in operationIds
        }) { "Recovery added an unexpected business audit" }
        // Real authentication history is in separately traversed protected tables. New
        // sessions/archives/authentication events are never compared as business effects.
    }
    override fun toString(): String = "FoodColdNativeCut([REDACTED])"
}
internal data class FoodColdAuditFingerprint(val fingerprint: String, val kind: String, val operationId: String)

internal class FoodColdCensusRows(private val rows: Map<NativeHistoryTableV2, List<FoodColdCensusRow>>) {
    val branch: FoodColdCensusRow get() = table(NativeHistoryTableV2.BRANCH).single()
    fun table(table: NativeHistoryTableV2): List<FoodColdCensusRow> = rows.getValue(table)
    fun aggregates(kind: String): List<FoodColdCensusRow> = table(NativeHistoryTableV2.AGGREGATE).filter { it.text("kind") == kind }

    fun requireBranch(c: SelfOwnedAllocationCoordinatesV1, plan: NativeFoodColdInputPlan) {
        check(branch.text("scope") == foodColdScope(c.scope) && branch.text("assignmentId") == c.assignmentId.value &&
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
                "Fresh setup unexpectedly created business, stock, approval or employee-session state"
            }
        check(counts.getValue(NativeHistoryTableV2.ACCOUNT) == 1L && counts.getValue(NativeHistoryTableV2.POLICY) == 1L)
    }

    fun requireOriginals(originals: List<FoodColdSavedOriginal>, initial: FoodColdNativeCut, coordinates: SelfOwnedAllocationCoordinatesV1) {
        check(originals.isNotEmpty() && originals.map { it.request.operationId }.distinct().size == originals.size &&
            originals.map { it.result.sequence.value } == originals.map { it.result.sequence.value }.sorted())
        val ids = originals.map { it.request.operationId.value }.toSet()
        check(table(NativeHistoryTableV2.OPERATION).map { it.text("operationId") }.toSet() == ids &&
            table(NativeHistoryTableV2.OPERATION).size == originals.size && table(NativeHistoryTableV2.JOURNAL).size == originals.size)
        check(table(NativeHistoryTableV2.OUTBOX).all { it.text("operationId") in ids } &&
            table(NativeHistoryTableV2.JOURNAL_FRAGMENT).all { it.text("operationId") in ids })
        check(table(NativeHistoryTableV2.AUDIT).all {
            it.text("operationId") in ids && (it.text("kind").startsWith("command.") || it.text("kind") == "result.supervisor-read")
        })
        originals.forEach { saved ->
            check(saved.scope == coordinates.scope && saved.deviceId == coordinates.deviceId && saved.installationId == coordinates.installationId &&
                saved.assignmentId == coordinates.assignmentId && saved.storageInstanceId == coordinates.storageInstanceId)
            requireOriginal(saved)
        }
        val journals = table(NativeHistoryTableV2.JOURNAL).sortedBy { it.integer("sequence") }
        check(initial.operationIds.isEmpty() && initial.epoch == branch.integer("epoch") &&
            journals.map { it.text("operationId") } == originals.map { it.request.operationId.value } &&
            journals.first().text("previousHash") == initial.headHash && journals.first().integer("sequence") > initial.headSequence &&
            journals.zipWithNext().all { (before, after) ->
                after.integer("sequence") > before.integer("sequence") && after.text("previousHash") == before.text("hash") } &&
            branch.integer("sequence") == journals.last().integer("sequence") && branch.text("journalHash") == journals.last().text("hash"))
    }

    private fun requireOriginal(saved: FoodColdSavedOriginal) {
        val request = saved.request
        val result = saved.result
        check(result.operationId == request.operationId && result.actorId == saved.identity.originalActorId &&
            saved.identity.semanticDigestVersion == 1 && saved.identity.semanticSha256 ==
            semanticDigest(saved.scope, result.actorId, request.command, request.payload).value)
        val operation = table(NativeHistoryTableV2.OPERATION).single { it.text("operationId") == request.operationId.value }
        check(operation.text("scope") == foodColdScope(saved.scope) && operation.text("actorId") == result.actorId.value &&
            operation.text("command") == request.command.name && operation.integer("commandVersion") == request.command.version.toLong() &&
            operation.text("digest") == saved.identity.semanticSha256 && operation.text("outcome") == result.code &&
            operation.integer("epoch") == result.epoch.value && operation.integer("sequence") == result.sequence.value &&
            operation.text("committedAt") == result.recordedAt.canonicalString() && operation.equalsStored("result", result.body) &&
            operation.text("resultHash") == sha256(operation.bytes("result")).value && operation.text("authenticationId") == saved.sessionId.value &&
            operation.integer("policyRevision") == saved.policyRevision.value)
        val journal = table(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == request.operationId.value }
        check(journal.integer("epoch") == result.epoch.value && journal.integer("sequence") == result.sequence.value &&
            journal.text("assignmentId") == saved.assignmentId.value && journal.text("hash") == sha256(journal.bytes("envelope")).value)
        val outbox = table(NativeHistoryTableV2.OUTBOX).filter { it.text("operationId") == request.operationId.value }.sortedBy { it.integer("ordinal") }
        check(outbox.isNotEmpty() && outbox.map { it.integer("ordinal") } == outbox.indices.map(Int::toLong))
        outbox.forEach { check(it.text("bodyHash") == sha256(it.bytes("body")).value) }
        check(outbox.first().text("kind") == "journal.upload" && FoodColdSlice(outbox.first().bytes("body")).frames().map { it.text() } ==
            listOf("ukkera.journal-upload/v1", saved.scope.branchId.value, result.epoch.value.toString(), result.sequence.value.toString(), journal.text("hash")))
        when (FoodColdBinaryReader(journal.bytes("envelope")).text(48)) {
            "ukkera.journal/v1" -> {
                val fields = FoodColdSlice(journal.bytes("envelope")).frames()
                check(fields.size == 20 && fields.take(16).map { it.text() } == listOf("ukkera.journal/v1", operation.text("scope"),
                    saved.assignmentId.value, result.epoch.value.toString(), result.sequence.value.toString(), request.operationId.value,
                    result.actorId.value, request.command.name, request.command.version.toString(), saved.sessionId.value,
                    saved.policyRevision.value.toString(), saved.identity.semanticSha256, result.code, result.recordedAt.canonicalString(),
                    operation.text("resultHash"), journal.text("previousHash")) && fields[16].equalsStored(request.payload) && fields[17].equalsStored(result.body))
                check(table(NativeHistoryTableV2.JOURNAL_FRAGMENT).none { it.text("operationId") == request.operationId.value })
                val jobs = fields[19].frames().map { it.frames() }
                check(jobs.size == outbox.size - 1)
                jobs.forEachIndexed { index, job -> check(job.size == 2 && job[0].text() == outbox[index + 1].text("kind") &&
                    job[1].equalsBytes(outbox[index + 1].bytes("body"))) }
            }
            "ukkera.journal/root/v2" -> requireStreamedOriginal(journal, operation, saved, outbox)
            else -> error("Unsupported native journal format; an original is never fabricated or guessed")
        }
        val audits = table(NativeHistoryTableV2.AUDIT).filter { it.text("operationId") == request.operationId.value && it.text("kind") == "command.${result.code}" }
        check(audits.isNotEmpty() && audits.all { it.text("actorId") == result.actorId.value && it.text("originalActorId") == result.actorId.value &&
            it.text("digest") == saved.identity.semanticSha256 && it.integer("policyRevision") == saved.policyRevision.value &&
            it.text("recordedAt") == result.recordedAt.canonicalString() })
    }

    /** Raw root/fragment/request/result/job linkage only. No accepting normalized command,
     * typed-source history decoder or RetainedStreamedCommand is constructed here. */
    private fun requireStreamedOriginal(journal: FoodColdCensusRow, operation: FoodColdCensusRow,
        saved: FoodColdSavedOriginal, outbox: List<FoodColdCensusRow>) {
        val request = saved.request
        val reader = FoodColdBinaryReader(journal.bytes("envelope"))
        check(reader.text(48) == "ukkera.journal/root/v2" && reader.text(36) == saved.scope.organizationId.value &&
            reader.text(36) == saved.scope.companyId.value && reader.text(36) == saved.scope.branchId.value &&
            reader.text(36) == request.operationId.value && reader.text(96) == request.command.name && reader.int() == request.command.version &&
            reader.text(36) == saved.identity.originalActorId.value && reader.text(64) == saved.identity.semanticSha256 &&
            reader.text(36) == saved.assignmentId.value && reader.long() == saved.result.epoch.value && reader.long() == saved.result.sequence.value &&
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
            var chain = foodColdTextHash("ukkera.journal/fragment-chain/v2")
            fragments.forEachIndexed { ordinal, fragment ->
                val bytes = fragment.bytes("payload")
                check(fragment.integer("ordinal") == ordinal.toLong() && bytes.size.toLong() == fragment.integer("byteCount") &&
                    bytes.size in 1..49_152 && (ordinal == fragments.lastIndex || bytes.size == 49_152) &&
                    fragment.text("payloadHash") == sha256(bytes).value && bytes.size <= stream.size - at)
                bytes.copyInto(stream, at); at += bytes.size
                chain = foodColdBinaryHash { data -> data.foodColdText("ukkera.journal/fragment-link/v2"); data.foodColdText(chain)
                    data.writeLong(ordinal.toLong()); data.writeInt(bytes.size); data.foodColdText(fragment.text("payloadHash")) }
            }
            check(at == stream.size && foodColdBinaryHash { data -> data.foodColdText("ukkera.journal/fragment-finish/v2"); data.foodColdText(chain)
                data.writeLong(fragmentCount); data.writeLong(streamBytes) } == commitment)
            val observedCounts = LongArray(8)
            val observedBytes = LongArray(8)
            var prior = 1
            val jobOrdinals = mutableSetOf<Long>()
            for (frame in FoodColdSlice(stream).frames()) {
                val fields = FoodColdBinaryReader(frame)
                check(fields.text(48) == "ukkera.journal/frame/v2")
                val section = fields.int()
                check(section in prior..8); prior = section
                observedCounts[section - 1]++; observedBytes[section - 1] += frame.size + 4L
                when (section) {
                    1 -> { check(fields.blob(65_536).equalsStored(request.payload)); fields.end() }
                    2 -> { check(fields.blob(65_536).equalsStored(saved.result.body)); fields.end() }
                    5 -> {
                        val ordinal = fields.long()
                        check(ordinal in 1L until outbox.size.toLong() && jobOrdinals.add(ordinal) &&
                            fields.text(96) == outbox[ordinal.toInt()].text("kind") && fields.blob(65_536).equalsBytes(outbox[ordinal.toInt()].bytes("body")))
                        fields.end()
                    }
                    else -> Unit // Full frame bytes traversed. Typed source/effect admission remains production-owned.
                }
            }
            check(observedCounts.contentEquals(sectionCounts) && observedBytes.contentEquals(sectionBytes) &&
                sectionCounts[4] == (outbox.size - 1).toLong() && jobOrdinals == (1L until outbox.size.toLong()).toSet())
        } finally { stream.fill(0) }
    }

    fun requirePublicationProofs(sources: List<NativeCompanyBusinessPublicationHistorySource>, seed: FoodColdSeedBindings,
        originals: List<FoodColdSavedOriginal>): Map<String, JSONObject> {
        check(sources.map { it.originalOperationId }.distinct().size == sources.size)
        val proofData = linkedMapOf<String, JSONObject>()
        for (source in sources) {
            val original = originals.single { it.request.operationId.value == source.originalOperationId.value }
            val journal = table(NativeHistoryTableV2.JOURNAL).single { it.text("operationId") == source.originalOperationId.value }
            check(source.hasActualOriginalEvent && source.scope == original.scope && source.originalRequestId == source.originalOperationId &&
                source.originalActorId == original.identity.originalActorId && source.semanticDigest.value == original.identity.semanticSha256 &&
                source.purposeTag in 2..3 && source.child.commandEpoch == original.result.epoch.value &&
                source.child.commandSequence == original.result.sequence.value && source.child.commandDigest.value == journal.text("hash") &&
                source.child.historySequence == source.preparationParent.historySequence + 1L)
            seed.published.requireSnapshot(source.snapshot) // Exact original native-selected cut, not today's refresh.
            check(source.dependencyFields() == listOf("native-business-publications/v1", source.frameId.value, source.observationDigest.value))
            proofData[original.symbol] = JSONObject().put("symbol", original.symbol).put("operationId", source.originalOperationId.value)
                .put("originalActorId", source.originalActorId.value).put("semanticDigest", source.semanticDigest.value)
                .put("originalRecordedAt", original.result.recordedAt.canonicalString()).put("frameId", source.frameId.value)
                .put("observationDigest", source.observationDigest.value).put("originalIntentDigest", source.originalIntentDigest.value)
                .put("dependenciesDigest", source.dependenciesDigest.value).put("originalAllocationDigest", source.originalAllocationDigest.value)
                .put("originalAnchorDigest", source.originalAnchorDigest.value).put("purposeTag", source.purposeTag)
                .put("preparationHistorySequence", source.preparationParent.historySequence).put("preparationHistoryDigest", source.preparationParent.historyDigest.value)
                .put("actualEventHistorySequence", source.child.historySequence).put("actualEventHistoryDigest", source.child.historyDigest.value)
                .put("originalCommandSequence", source.child.commandSequence).put("originalJournalHash", source.child.commandDigest.value)
                .put("actualOriginalEventObserved", true).put("originalCompletedCut", seed.published.cutData())
        }
        // Quoted Add does not consume a publication observation. The release proof
        // cannot bind its independent FOOD native original-event/source join.
        check("ACTIVATE_RELEASE5" in proofData) {
            "The exact original release activation publication-observation proof is required at the closed boundary"
        }
        return proofData.toMap()
    }
}

internal class FoodColdCensusCell(val type: NativeHistorySqliteTypeV2, val integer: Long?, val bytes: ByteArray) {
    fun fingerprint(): String = "${type.name}:${integer ?: "-"}:${sha256(bytes).value}"
}
internal class FoodColdCensusRow(private val cells: Map<String, FoodColdCensusCell>) {
    fun text(name: String): String = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.TEXT); it.bytes.decodeToString(throwOnInvalidSequence = true) }
    fun integer(name: String): Long = cells.getValue(name).let { check(it.type == NativeHistorySqliteTypeV2.INTEGER); checkNotNull(it.integer) }
    fun bytes(name: String): ByteArray = cells.getValue(name).bytes
    fun textBytes(name: String): String = bytes(name).decodeToString(throwOnInvalidSequence = true)
    fun key(table: NativeHistoryTableV2): String = foodColdTextHash(table.keyColumns.joinToString("|") { cells.getValue(it).fingerprint() })
    fun fingerprint(): String = foodColdTextHash(cells.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value.fingerprint()}" })
    fun equalsStored(name: String, value: StoredBytes): Boolean = FoodColdSlice(bytes(name)).equalsStored(value)
    fun clear() { cells.values.forEach { it.bytes.fill(0) } }
}
private class FoodColdSlice(val bytes: ByteArray, val start: Int = 0, val end: Int = bytes.size) {
    val size: Int get() = end - start
    fun text(): String = bytes.decodeToString(start, end, throwOnInvalidSequence = true)
    fun equalsBytes(other: ByteArray): Boolean = size == other.size && other.indices.all { bytes[start + it] == other[it] }
    fun equalsStored(other: StoredBytes): Boolean { val copy = other.copyBytes(); return try { equalsBytes(copy) } finally { copy.fill(0) } }
    fun frames(): List<FoodColdSlice> {
        check(size <= 32 * 1024 * 1024)
        val result = mutableListOf<FoodColdSlice>()
        var at = start
        while (at < end) {
            check(result.size < 16_384 && end - at >= 4)
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[at++].toLong() and 255L) }
            check(length <= end - at)
            result += FoodColdSlice(bytes, at, at + length.toInt()); at += length.toInt()
        }
        check(at == end)
        return result
    }
}
private class FoodColdBinaryReader(private val source: FoodColdSlice) {
    constructor(bytes: ByteArray) : this(FoodColdSlice(bytes))
    private var at = source.start
    fun int(): Int { check(source.end - at >= 4); var value = 0; repeat(4) { value = (value shl 8) or (source.bytes[at++].toInt() and 255) }; return value }
    fun long(): Long { check(source.end - at >= 8); var value = 0L; repeat(8) { value = (value shl 8) or (source.bytes[at++].toLong() and 255L) }; return value }
    fun blob(maximum: Int): FoodColdSlice { val size = int(); check(size in 0..maximum && size <= source.end - at); return FoodColdSlice(source.bytes, at, at + size).also { at += size } }
    fun text(maximum: Int): String = blob(maximum).text()
    fun end() { check(at == source.end) }
}
internal fun foodColdScope(scope: BranchScope): String = listOf(scope.organizationId.value, scope.companyId.value, scope.branchId.value).joinToString("/")
internal fun foodColdTextHash(value: String): String {
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}
private fun foodColdBinaryHash(write: (DataOutputStream) -> Unit): String {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use(write)
    val bytes = output.toByteArray()
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}
private fun DataOutputStream.foodColdText(value: String) {
    val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
    try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
}
