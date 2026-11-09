package com.ukkera.pos.android.independent.nativesupplierreferencev2

import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Supplementary closed-native DATA, not an accepting history verifier or a writer/read SPI.
 * Every finite native pass and cell chunk is consumed. Protected cell contents are wiped and
 * never retained. Normal production Master reopening must independently admit the history. */
internal class SupplierReferenceV2Census private constructor(
    private val rows: Map<NativeHistoryTableV2, List<SupplierReferenceV2Row>>,
    private val counts: Map<NativeHistoryTableV2, Int>,
    private val coordinates: SelfOwnedAllocationCoordinatesV1,
) {
    private val branch: SupplierReferenceV2Row get() = rows.getValue(NativeHistoryTableV2.BRANCH).single()
    val epoch: Long get() = branch.integer("epoch")

    fun requireInitialNoBusiness() {
        check(counts.getValue(NativeHistoryTableV2.ACCESS) == 0) { "Fresh native setup created an employee session" }
        for (table in listOf(NativeHistoryTableV2.OPERATION, NativeHistoryTableV2.AGGREGATE,
            NativeHistoryTableV2.CLAIM, NativeHistoryTableV2.JOURNAL, NativeHistoryTableV2.OUTBOX))
            check(rows.getValue(table).isEmpty()) { "An existing business history is not an independent fresh leg" }
        check(rows.getValue(NativeHistoryTableV2.AUDIT).none { it.text("kind").startsWith("command.") })
        requireNoUnrelatedIndexes()
    }

    private fun requireNoUnrelatedIndexes() {
        for (table in listOf(NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION,
            NativeHistoryTableV2.STOCK_RESERVATION, NativeHistoryTableV2.STOCK_VALUE, NativeHistoryTableV2.JOURNAL_FRAGMENT))
            check(counts.getValue(table) == 0) { "Supplier-only edits emitted unrelated stock/value/fragment state" }
    }

    /** Exact own-cut requests/results/supplier records and normal documentary identities.
     * Storage CAS and domain revision are observed separately; no counter is manufactured. */
    fun requireSequence(originals: List<SupplierReferenceV2Original>, supplierId: DomainId,
        initial: SupplierReferenceV2Census, evidence: SupplierReferenceV2Evidence) {
        check(originals.isNotEmpty())
        initial.requireInitialNoBusiness()
        requireSameNativeFixtureCoordinates(coordinates, initial.coordinates)
        check(epoch == initial.epoch)
        requireNoUnrelatedIndexes()
        check(rows.getValue(NativeHistoryTableV2.CLAIM).isEmpty())
        val expectedIds = originals.map { it.request.operationId.value }
        check(expectedIds.distinct().size == expectedIds.size)
        val operations = rows.getValue(NativeHistoryTableV2.OPERATION)
        check(operations.size == expectedIds.size && operations.map { it.text("operationId") }.toSet() == expectedIds.toSet())
        val journals = rows.getValue(NativeHistoryTableV2.JOURNAL).sortedBy { it.integer("sequence") }
        check(journals.map { it.text("operationId") } == expectedIds)
        check(journals.first().text("previousHash") == initial.branch.text("journalHash") &&
            journals.first().integer("sequence") > initial.branch.integer("sequence"))
        check(journals.zipWithNext().all { (before, after) ->
            after.text("previousHash") == before.text("hash") && after.integer("sequence") > before.integer("sequence")
        })
        check(branch.integer("sequence") == journals.last().integer("sequence") &&
            branch.text("journalHash") == journals.last().text("hash"))
        check(listOf("scope", "assignmentId", "deviceId").all { branch.text(it) == initial.branch.text(it) })
        check(rows.getValue(NativeHistoryTableV2.OUTBOX).size == originals.size)
        val commandAudits = rows.getValue(NativeHistoryTableV2.AUDIT).filter { it.text("kind").startsWith("command.") }
        check(commandAudits.size == originals.size && commandAudits.all {
            it.text("operationId") in expectedIds && it.text("kind") == "command.committed"
        })

        var previousStorageRevision: Revision? = null
        for (original in originals) {
            val request = original.request
            val operationId = request.operationId.value
            val owner = original.owner
            check(owner.scope == coordinates.scope && owner.actorId == coordinates.firstActorId && owner.deviceId == coordinates.deviceId)
            val operation = operations.single { it.text("operationId") == operationId }
            val journal = journals.single { it.text("operationId") == operationId }
            val digest = semanticDigest(owner.scope, owner.actorId, request.command, request.payload).value
            val rawResult = StoredBytes(operation.bytes("result"))
            val recordedAt = UtcInstant.parseCanonical(operation.text("committedAt"))
            val policyRevision = Revision(operation.integer("policyRevision"))
            val authenticationId = DomainId(operation.text("authenticationId"))
            check(policyRevision == SupplierReferenceV2NativeSetup.account(coordinates).policy.revision)
            check(operation.text("scope") == branch.text("scope") && operation.text("actorId") == owner.actorId.value &&
                operation.text("command") == request.command.name && operation.integer("commandVersion") == request.command.version.toLong() &&
                operation.text("digest") == digest && operation.text("outcome") == "committed" &&
                operation.text("resultHash") == sha256(operation.bytes("result")).value && operation.integer("epoch") == epoch)
            check(journal.text("scope") == operation.text("scope") && journal.text("assignmentId") == branch.text("assignmentId") &&
                journal.integer("epoch") == epoch && journal.integer("sequence") == operation.integer("sequence"))

            val expectedCas: Revision?
            val resultRevision: Revision
            if (original.step.version == 2) {
                check(request.command == LocalSupplierProfileV2.COMMAND)
                val typedRequest = LocalSupplierCodecV2.decode(request.payload)
                check(LocalSupplierCodecV2.encode(typedRequest) == request.payload && typedRequest.supplier ==
                    LocalSupplierV2(supplierId, original.step.after.names, original.step.after.active, original.step.after.reference?.code))
                expectedCas = typedRequest.expectedStorageRevision
                val typedResult = LocalSupplierCodecV2.decodeResult(rawResult)
                check(typedResult.code == "configuration.applied" && typedResult.id == supplierId &&
                    typedResult.reference == original.step.after.reference?.code)
                check(StoredBytes(canonicalJson.encodeToString(LocalSupplierResultV2.serializer(), typedResult).encodeToByteArray()) == rawResult)
                resultRevision = checkNotNull(typedResult.revision)
            } else {
                check(request.command == LocalConfigurationProfile.COMMAND)
                val typedRequest = LocalConfigurationCodec.decode(request.payload) as LocalConfigurationRequestV1.ConfigureSupplier
                check(LocalConfigurationCodec.encode(typedRequest) == request.payload && typedRequest.supplier ==
                    LocalSupplierV1(supplierId, original.step.after.names, original.step.after.active))
                expectedCas = typedRequest.expectedStorageRevision
                val typedResult = LocalConfigurationCodec.decodeResult(rawResult)
                check(typedResult.code == "configuration.applied" && typedResult.id == supplierId)
                // Existing strict V1 serializer/decoder: no reference/names/active result members are added.
                check(StoredBytes(canonicalJson.encodeToString(LocalConfigurationResultV1.serializer(), typedResult).encodeToByteArray()) == rawResult)
                resultRevision = checkNotNull(typedResult.revision)
            }
            check(expectedCas == previousStorageRevision)

            val envelope = journal.bytes("envelope")
            check(journal.text("hash") == sha256(envelope).value)
            val fields = supplierReferenceV2Frames(envelope)
            check(fields.size == 20 && fields[0].supplierReferenceV2Text() == "ukkera.journal/v1")
            check(fields.take(16).map { it.supplierReferenceV2Text() } == listOf(
                "ukkera.journal/v1", operation.text("scope"), journal.text("assignmentId"), epoch.toString(),
                operation.integer("sequence").toString(), operationId, owner.actorId.value, request.command.name,
                request.command.version.toString(), authenticationId.value, policyRevision.value.toString(), digest,
                "committed", recordedAt.canonicalString(), operation.text("resultHash"), journal.text("previousHash")))
            check(fields[16].contentEquals(request.payload.copyBytes()) && fields[17].contentEquals(rawResult.copyBytes()))
            val effects = supplierReferenceV2Frames(fields[18]).map(::supplierReferenceV2Frames)
            val effect = effects.single()
            check(effect.size == 5 && effect[0].supplierReferenceV2Text() == LocalConfigurationProfile.SUPPLIER &&
                effect[1].supplierReferenceV2Text() == supplierId.value && effect[3].supplierReferenceV2Text() == original.step.sourceSchema)
            val storageRevision = Revision(effect[2].supplierReferenceV2Text().toLong())
            check(storageRevision != previousStorageRevision && supplierReferenceV2Frames(fields[19]).isEmpty())
            requireSupplierRecord(effect[4], supplierId, original, resultRevision, policyRevision, recordedAt)
            previousStorageRevision = storageRevision

            val audit = commandAudits.single { it.text("operationId") == operationId }
            check(audit.text("scope") == operation.text("scope") && audit.text("actorId") == owner.actorId.value &&
                audit.text("originalActorId") == owner.actorId.value && audit.integer("policyRevision") == policyRevision.value &&
                audit.text("recordedAt") == recordedAt.canonicalString() && audit.text("digest") == digest)
            val obligation = rows.getValue(NativeHistoryTableV2.OUTBOX).single { it.text("operationId") == operationId }
            check(obligation.text("scope") == operation.text("scope") && obligation.integer("ordinal") == 0L &&
                obligation.text("kind") == "journal.upload" && obligation.text("bodyHash") == sha256(obligation.bytes("body")).value)
            check(supplierReferenceV2Frames(obligation.bytes("body")).map { it.supplierReferenceV2Text() } == listOf(
                "ukkera.journal-upload/v1", coordinates.scope.branchId.value, epoch.toString(),
                operation.integer("sequence").toString(), journal.text("hash")))
            original.observeSource(SupplierReferenceV2Source(rawResult, StoredBytes(effect[4]), StoredBytes(envelope),
                effect[3].supplierReferenceV2Text(), storageRevision, resultRevision, authenticationId, policyRevision,
                epoch, operation.integer("sequence"), recordedAt,
                listOf(operation, journal, audit, obligation).joinToString(";") { it.fingerprint() }), evidence)
        }

        val current = rows.getValue(NativeHistoryTableV2.AGGREGATE).single()
        val last = checkNotNull(originals.last().source)
        check(current.text("scope") == branch.text("scope") && current.text("kind") == LocalConfigurationProfile.SUPPLIER &&
            current.text("id") == supplierId.value && current.integer("revision") == last.storageRevision.value &&
            current.text("schema") == last.sourceSchema && current.bytes("payload").contentEquals(last.originalSupplierRecord.copyBytes()) &&
            current.text("payloadHash") == sha256(current.bytes("payload")).value)
        // A complete single supplier aggregate and empty claims/indexes plus exact supplier-only
        // originals/effects/jobs exclude other durable invoice/payable/payment/cash business state.
    }

    private fun requireSupplierRecord(bytes: ByteArray, supplierId: DomainId, original: SupplierReferenceV2Original,
        resultRevision: Revision, policyRevision: Revision, recordedAt: UtcInstant) {
        val record = JSONObject(bytes.supplierReferenceV2Text())
        val scope = record.getJSONObject("scope")
        val body = record.getJSONObject("body")
        val stored = canonicalJson.decodeFromString(LocalSupplierV1.serializer(), body.getJSONObject("value").toString())
        val reference = if (!body.has("reference") || body.isNull("reference")) null
            else canonicalJson.decodeFromString(Code.serializer(), body.getJSONObject("reference").toString())
        check(record.getString("kind") == LocalConfigurationProfile.SUPPLIER && record.getString("id") == supplierId.value &&
            scope.getString("organizationId") == coordinates.scope.organizationId.value &&
            scope.getString("companyId") == coordinates.scope.companyId.value && scope.getString("branchId") == coordinates.scope.branchId.value &&
            body.getString("type") == "SUPPLIER" && stored == LocalSupplierV1(supplierId, original.step.after.names, original.step.after.active) &&
            reference == original.step.after.reference?.code && record.getString("authorizedBy") == original.owner.actorId.value &&
            record.getString("policyRevision") == policyRevision.value.toString() && record.getString("revision") == resultRevision.value.toString() &&
            record.getString("recordedAt") == recordedAt.canonicalString())
        if (original.step.sourceSchema == LocalConfigurationProfile.schema(LocalConfigurationProfile.SUPPLIER))
            check(!body.has("reference")) { "A current V1-only supplier source was decorated with a reference" }
    }

    /** No supplier work occurred during this interval; only security bookkeeping may append. */
    fun requireSameOriginalBusiness(after: SupplierReferenceV2Census) {
        requireSameNativeFixtureCoordinates(coordinates, after.coordinates)
        check(epoch == after.epoch && branch.integer("sequence") == after.branch.integer("sequence") &&
            branch.text("journalHash") == after.branch.text("journalHash"))
        for (table in retainedTables - NativeHistoryTableV2.BRANCH) {
            fun selected(source: SupplierReferenceV2Census): List<String> = source.rows.getValue(table)
                .filter { table != NativeHistoryTableV2.AUDIT || it.text("kind").startsWith("command.") }
                .map { it.fingerprint() }.sorted()
            check(selected(this) == selected(after)) { "Protected lookup/ACK/reopen changed durable supplier originals/effects" }
        }
        requireNoUnrelatedIndexes()
        after.requireNoUnrelatedIndexes()
    }

    companion object {
        private val canonicalJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
        private val retainedTables = setOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.OPERATION,
            NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM, NativeHistoryTableV2.JOURNAL,
            NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.OUTBOX)

        suspend fun read(custody: SelfOwnedNativeHistoryCustody, coordinates: SelfOwnedAllocationCoordinatesV1,
            label: String, evidence: SupplierReferenceV2Evidence): SupplierReferenceV2Census = custody.runRoomWork(block = {
            val read = custody.openOriginalReadOnly()
            var closeStarted = false
            var primaryFailure: Throwable? = null
            try {
                requireSameNativeFixtureCoordinates(coordinates, read.original.record.allocation.coordinates)
                val rows = retainedTables.associateWith { mutableListOf<SupplierReferenceV2Row>() }
                val counts = NativeHistoryTableV2.entries.associateWith { 0 }.toMutableMap()
                val complete = mutableSetOf<NativeHistoryTableV2>()
                var schemaPages = 0
                while (read.readSchemaPage() != null) schemaPages += 1
                check(schemaPages > 0) { "Original native schema traversal produced no schema" }
                while (true) {
                    val metadata = read.nextRowMetadata() ?: break
                    val table = metadata.table
                    val census = metadata.pass == NativeHistoryReadPassV2.CENSUS
                    if (census && metadata.kind == NativeHistoryRowKindV2.PASS_END)
                        check(complete.add(checkNotNull(table))) { "Duplicate native census completion" }
                    val keep = census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL && table in retainedTables
                    val cells = linkedMapOf<String, SupplierReferenceV2Cell>()
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
                        if (keep) {
                            check(!cells.containsKey(cell.name))
                            cells[cell.name] = SupplierReferenceV2Cell(cell.integerValue, checkNotNull(kept))
                        }
                    }
                    if (census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL) {
                        val actualTable = checkNotNull(table)
                        counts[actualTable] = counts.getValue(actualTable) + 1
                        if (keep) {
                            check(rows.getValue(actualTable).size < 1_024) { "Bounded supplier census exceeds its observation scope" }
                            rows.getValue(actualTable) += SupplierReferenceV2Row(cells)
                        }
                    }
                }
                check(complete.containsAll(NativeHistoryPhysicalReadProfileV2.applicationTables))
                closeStarted = true
                val closed = withContext(NonCancellable) { read.closeOriginalRead() }
                val after = custody.reobserveAfterActualClose(closed)
                check(after.lifetimeId == read.original.lifetimeId && after.recordDigest == read.original.recordDigest &&
                    after.record.database == read.original.record.database)
                custody.completeOriginalReadDelivery(after) // DATA delivery only, never admission or a writer proof.
                val branch = rows.getValue(NativeHistoryTableV2.BRANCH).single()
                check(branch.text("scope") == listOf(coordinates.scope.organizationId.value, coordinates.scope.companyId.value,
                    coordinates.scope.branchId.value).joinToString("/") && branch.text("assignmentId") == coordinates.assignmentId.value &&
                    branch.text("deviceId") == coordinates.deviceId.value)
                evidence.event(label, JSONObject().put("actualNativeReadClose", true).put("authorityEpoch", branch.integer("epoch"))
                    .put("observedBranchRevision", branch.integer("revision"))
                    .put("operationIds", JSONArray(rows.getValue(NativeHistoryTableV2.OPERATION).map { it.text("operationId") }))
                    .put("censusTablesComplete", complete.size).put("schemaPagesConsumed", schemaPages)
                    .put("fullProductionHistoryAdmission", "NOT_CLAIMED_BY_CENSUS"))
                SupplierReferenceV2Census(rows.mapValues { it.value.toList() }, counts.toMap(), coordinates)
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

private class SupplierReferenceV2Cell(val integer: Long?, val bytes: ByteArray)
private class SupplierReferenceV2Row(private val cells: Map<String, SupplierReferenceV2Cell>) {
    fun text(name: String): String = cells.getValue(name).bytes.supplierReferenceV2Text()
    fun integer(name: String): Long = checkNotNull(cells.getValue(name).integer)
    fun bytes(name: String): ByteArray = cells.getValue(name).bytes
    fun fingerprint(): String = cells.toSortedMap().entries.joinToString("|") { (name, value) ->
        "$name:${value.integer ?: "-"}:${sha256(value.bytes).value}"
    }
}

private fun ByteArray.supplierReferenceV2Text(): String = decodeToString(throwOnInvalidSequence = true)

/** Decoder of the existing persisted framing only; never emits a command/history envelope. */
private fun supplierReferenceV2Frames(bytes: ByteArray): List<ByteArray> {
    check(bytes.size <= 4 * 1024 * 1024)
    val fields = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < bytes.size) {
        check(fields.size < 1024 && bytes.size - offset >= 4)
        var length = 0L
        repeat(4) { length = (length shl 8) or (bytes[offset++].toLong() and 255L) }
        check(length <= bytes.size - offset)
        fields += bytes.copyOfRange(offset, offset + length.toInt())
        offset += length.toInt()
    }
    check(offset == bytes.size)
    return fields
}
