package com.ukkera.pos.android.independent.aosfsetupv2

import android.app.Application
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.NativeHistoryDatabaseIdentityV2
import com.ukkera.pos.security.NativeHistoryPhysicalReadProfileV2
import com.ukkera.pos.security.NativeHistoryReadPassV2
import com.ukkera.pos.security.NativeHistoryRowKindV2
import com.ukkera.pos.security.NativeHistorySqliteTypeV2
import com.ukkera.pos.security.NativeHistoryTableV2
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.sha256
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class AosfAggregateFact(val kind: String, val id: String, val schema: String)
internal data class AosfOperationFact(val operationId: String, val actorId: String, val command: String)
internal data class AosfAuditFact(val kind: String, val operationId: String, val actorId: String)

private val AOSF_CAPTURED_TEXT_TABLES = setOf(NativeHistoryTableV2.BRANCH,
    NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.OPERATION, NativeHistoryTableV2.AUDIT)

/**
 * Independent cut over the actual original current-schema Room store reached only through the
 * production closed-native read custody. It stores table counts, row fingerprints and small
 * branch/aggregate/operation/audit observations, never a live database handle, business payload
 * or second writer.
 */
internal class AosfNativeCut internal constructor(
    val recordDigest: String,
    val definitionDigest: String?,
    val receiptDigest: String?,
    val authorityEpoch: Long,
    val generation: Long,
    val pending: Boolean,
    val database: NativeHistoryDatabaseIdentityV2?,
    private val counts: Map<NativeHistoryTableV2, Long>,
    private val rowHashes: Map<NativeHistoryTableV2, List<String>>,
    val aggregates: List<AosfAggregateFact>,
    val operations: List<AosfOperationFact>,
    val audits: List<AosfAuditFact>,
    val branchScope: String?,
    val branchEpoch: Long?,
    val branchWritable: Long?,
    val branchAssignment: String?,
    val branchDevice: String?,
) {
    fun count(table: NativeHistoryTableV2): Long = counts.getValue(table)

    fun requireInitialSetupOnly() {
        for (table in BUSINESS_TABLES) {
            check(count(table) == 0L) { "Fresh setup created business table rows: $table" }
        }
        check(count(NativeHistoryTableV2.BRANCH) == 1L) {
            "Fresh setup did not retain exactly one setup root/branch authority row"
        }
        check(count(NativeHistoryTableV2.ACCOUNT) == 1L) { "Fresh setup did not retain exactly one account" }
        check(count(NativeHistoryTableV2.POLICY) == 1L) { "Fresh setup did not retain exactly one policy" }
        check(count(NativeHistoryTableV2.ADMISSION) == 1L) { "Fresh setup did not retain exactly one admission" }
        for (table in SESSION_TABLES) {
            check(count(table) == 0L) { "Fresh setup unexpectedly created session state: $table" }
        }
        check(database != null) { "Fresh setup has no current-schema owned Room identity" }
        check(!pending) { "Fresh setup retained an unresolved pending operation" }
    }

    fun requireNoBusinessOrPending() {
        for (table in BUSINESS_TABLES) check(count(table) == 0L) { "Unexpected business rows: $table" }
        check(!pending) { "An unresolved pending operation appeared" }
    }

    fun requireSameReadCut(other: AosfNativeCut) {
        check(recordDigest == other.recordDigest && definitionDigest == other.definitionDigest &&
            receiptDigest == other.receiptDigest && authorityEpoch == other.authorityEpoch &&
            generation == other.generation && pending == other.pending && database == other.database &&
            counts == other.counts && rowHashes == other.rowHashes &&
            aggregates == other.aggregates && operations == other.operations && audits == other.audits &&
            branchScope == other.branchScope &&
            branchEpoch == other.branchEpoch && branchWritable == other.branchWritable &&
            branchAssignment == other.branchAssignment && branchDevice == other.branchDevice) {
            "A read-attributable original setup/business/security/authority/pending delta occurred"
        }
    }

    fun requireSameSetupAuthority(other: AosfNativeCut) {
        check(recordDigest == other.recordDigest && definitionDigest == other.definitionDigest &&
            receiptDigest == other.receiptDigest && authorityEpoch == other.authorityEpoch &&
            branchScope == other.branchScope && branchEpoch == other.branchEpoch &&
            branchWritable == other.branchWritable && branchAssignment == other.branchAssignment &&
            branchDevice == other.branchDevice) {
            "The original setup/authority identity changed"
        }
        for (table in BUSINESS_TABLES) check(count(table) == other.count(table)) {
            "The read changed business rows: $table"
        }
    }

    /** Durable setup identity/root cardinality, not the explicitly authorized policy revision or
     * setup-result command bookkeeping. */
    fun requireSameSetupIdentity(other: AosfNativeCut) {
        check(definitionDigest == other.definitionDigest && receiptDigest == other.receiptDigest &&
            authorityEpoch == other.authorityEpoch && database == other.database &&
            branchScope == other.branchScope && branchEpoch == other.branchEpoch &&
            branchWritable == other.branchWritable && branchAssignment == other.branchAssignment &&
            branchDevice == other.branchDevice) {
            "The original setup/authority identity changed"
        }
        for (table in listOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.ACCOUNT,
            NativeHistoryTableV2.POLICY, NativeHistoryTableV2.ADMISSION)) {
            check(count(table) == other.count(table)) {
                "An unexpected setup root/account/policy/admission appeared: $table"
            }
        }
    }

    /** Immutable setup/authority identity and protected policy/admission rows. An explicit
     * fresh login may change account/throttle/authentication rows, so this is the honest
     * comparison for a cut that spans an explicit session establishment. */
    fun requireSameSetupAndPolicy(other: AosfNativeCut) {
        check(definitionDigest == other.definitionDigest && receiptDigest == other.receiptDigest &&
            authorityEpoch == other.authorityEpoch && database == other.database &&
            branchScope == other.branchScope && branchEpoch == other.branchEpoch &&
            branchWritable == other.branchWritable && branchAssignment == other.branchAssignment &&
            branchDevice == other.branchDevice) {
            "The setup-result action changed the original setup/authority identity"
        }
        for (table in listOf(NativeHistoryTableV2.POLICY, NativeHistoryTableV2.ADMISSION,
            NativeHistoryTableV2.CONFIGURATION)) {
            check(rowHashes.getValue(table) == other.rowHashes.getValue(table)) {
                "The setup-result action changed protected policy/admission rows: $table"
            }
        }
    }

    /** Immutable setup/authority identity and protected current-security rows, ignoring only
     * the explicitly authorized setup-result command bookkeeping. */
    fun requireSameSetupAndSecurity(other: AosfNativeCut) {
        check(definitionDigest == other.definitionDigest && receiptDigest == other.receiptDigest &&
            authorityEpoch == other.authorityEpoch && database == other.database &&
            branchScope == other.branchScope && branchEpoch == other.branchEpoch &&
            branchWritable == other.branchWritable && branchAssignment == other.branchAssignment &&
            branchDevice == other.branchDevice) {
            "The setup-result action changed the original setup/authority identity"
        }
        for (table in listOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.ACCOUNT,
            NativeHistoryTableV2.POLICY, NativeHistoryTableV2.ADMISSION,
            NativeHistoryTableV2.CONFIGURATION)) {
            check(rowHashes.getValue(table) == other.rowHashes.getValue(table)) {
                "The setup-result action changed protected security rows: $table"
            }
        }
    }

    fun requireNoStockOrBusinessMovement(other: AosfNativeCut) {
        for (table in listOf(NativeHistoryTableV2.STOCK_POSITION, NativeHistoryTableV2.STOCK_ALLOCATION,
            NativeHistoryTableV2.STOCK_RESERVATION, NativeHistoryTableV2.STOCK_VALUE)) {
            check(count(table) == other.count(table)) { "A setup-result action moved stock: $table" }
        }
        check(count(NativeHistoryTableV2.CLAIM) == other.count(NativeHistoryTableV2.CLAIM)) {
            "A setup-result action altered a unique claim"
        }
    }

    fun requireDeliveryAckBookkeeping(other: AosfNativeCut): JSONObject = JSONObject()
        .put("operationRows", count(NativeHistoryTableV2.OPERATION) - other.count(NativeHistoryTableV2.OPERATION))
        .put("aggregateRows", count(NativeHistoryTableV2.AGGREGATE) - other.count(NativeHistoryTableV2.AGGREGATE))
        .put("auditRows", count(NativeHistoryTableV2.AUDIT) - other.count(NativeHistoryTableV2.AUDIT))
        .put("outboxRows", count(NativeHistoryTableV2.OUTBOX) - other.count(NativeHistoryTableV2.OUTBOX))
        .put("journalRows", count(NativeHistoryTableV2.JOURNAL) - other.count(NativeHistoryTableV2.JOURNAL))
        .put("fragmentRows", count(NativeHistoryTableV2.JOURNAL_FRAGMENT) -
            other.count(NativeHistoryTableV2.JOURNAL_FRAGMENT))

    /** Immutable setup/authority identity, one writer and the frozen zero business ledger. */
    fun requireSameSetupBase(other: AosfNativeCut) {
        check(definitionDigest == other.definitionDigest && receiptDigest == other.receiptDigest &&
            authorityEpoch == other.authorityEpoch && !pending && !other.pending &&
            branchScope == other.branchScope && branchEpoch == other.branchEpoch &&
            branchWritable == other.branchWritable && branchAssignment == other.branchAssignment &&
            branchDevice == other.branchDevice) {
            "The saved original setup/authority identity or writer state changed"
        }
        for (table in listOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.ACCOUNT,
            NativeHistoryTableV2.POLICY, NativeHistoryTableV2.ADMISSION)) {
            check(count(table) == other.count(table)) {
                "The setup root/account/policy/admission count changed: $table"
            }
        }
        for (table in STOCK_AND_CLAIM_TABLES) {
            check(count(table) == other.count(table)) { "The business ledger moved: $table" }
        }
    }

    fun requireZeroBusinessLedger() {
        for (table in STOCK_TABLES) {
            check(count(table) == 0L) { "The frozen AOSF setup-only business ledger is not zero: $table" }
        }
    }

    fun setupAggregates(kind: String): List<AosfAggregateFact> =
        aggregates.filter { it.kind == kind }

    fun requireNoSetupFacts() {
        check(setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).isEmpty()) {
            "An acknowledgement fact is present where the frozen case requires absence"
        }
        check(setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.GO_LIVE_KIND).isEmpty()) {
            "A go-live fact is present where the frozen case requires absence"
        }
    }

    /** ACK adds exactly its own fact/operation/journal and never a go-live or business movement. */
    fun requireOneAcknowledgement(before: AosfNativeCut, actorId: com.ukkera.pos.types.DomainId,
        operationId: com.ukkera.pos.types.OperationId) {
        requireSameSetupBase(before)
        check(setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size ==
            before.setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size + 1) {
            "Exactly one new acknowledgement aggregate was not committed"
        }
        check(setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.GO_LIVE_KIND).size ==
            before.setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.GO_LIVE_KIND).size) {
            "The acknowledgement added or removed a go-live fact"
        }
        check(operations.count {
            it.operationId == operationId.value && it.actorId == actorId.value &&
                it.command == com.ukkera.pos.database.setup.LocalSetupResultProfileV1.ACKNOWLEDGE.name
        } == 1) { "The retained acknowledgement operation/actor is absent or duplicated" }
        check(count(NativeHistoryTableV2.AGGREGATE) == before.count(NativeHistoryTableV2.AGGREGATE) + 1L &&
            count(NativeHistoryTableV2.OPERATION) == before.count(NativeHistoryTableV2.OPERATION) + 1L &&
            count(NativeHistoryTableV2.JOURNAL) == before.count(NativeHistoryTableV2.JOURNAL) + 1L) {
            "The acknowledgement did not commit exactly one aggregate/operation/journal entry"
        }
    }

    /** Go-live adds exactly its own fact/operation/journal and never an ACK or business movement. */
    fun requireOneGoLive(before: AosfNativeCut, actorId: com.ukkera.pos.types.DomainId,
        operationId: com.ukkera.pos.types.OperationId) {
        requireSameSetupBase(before)
        check(setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.GO_LIVE_KIND).size ==
            before.setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.GO_LIVE_KIND).size + 1) {
            "Exactly one new go-live aggregate was not committed"
        }
        check(setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size ==
            before.setupAggregates(com.ukkera.pos.database.setup.LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size) {
            "The go-live added or removed an acknowledgement fact"
        }
        check(operations.count {
            it.operationId == operationId.value && it.actorId == actorId.value &&
                it.command == com.ukkera.pos.database.setup.LocalSetupResultProfileV1.GO_LIVE.name
        } == 1) { "The retained go-live operation/actor is absent or duplicated" }
        check(count(NativeHistoryTableV2.AGGREGATE) == before.count(NativeHistoryTableV2.AGGREGATE) + 1L &&
            count(NativeHistoryTableV2.OPERATION) == before.count(NativeHistoryTableV2.OPERATION) + 1L &&
            count(NativeHistoryTableV2.JOURNAL) == before.count(NativeHistoryTableV2.JOURNAL) + 1L) {
            "The go-live did not commit exactly one aggregate/operation/journal entry"
        }
    }

    /** A protected read/login episode must not add setup-result or checkpoint bookkeeping. */
    fun requireNoSetupFactMovement(other: AosfNativeCut) {
        requireSameSetupBase(other)
        for (table in listOf(NativeHistoryTableV2.OPERATION, NativeHistoryTableV2.AGGREGATE)) {
            check(count(table) == other.count(table)) { "The read/login episode added $table bookkeeping" }
        }
        check(aggregates == other.aggregates && operations == other.operations) {
            "The read/login episode changed setup-result or checkpoint facts"
        }
    }

    val cutDigest: String by lazy {
        sha256(canonical().encodeToByteArray()).value
    }

    private fun canonical(): String = buildString {
        append("record=").append(recordDigest)
        append("|definition=").append(definitionDigest)
        append("|receipt=").append(receiptDigest)
        append("|epoch=").append(authorityEpoch)
        append("|generation=").append(generation)
        append("|pending=").append(pending)
        append("|database=").append(database)
        append("|branchScope=").append(branchScope)
        append("|branchEpoch=").append(branchEpoch)
        append("|branchWritable=").append(branchWritable)
        append("|branchAssignment=").append(branchAssignment)
        append("|branchDevice=").append(branchDevice)
        counts.toSortedMap(compareBy { it.tag }).forEach { (table, count) ->
            append("|count:").append(table.tag).append('=').append(count)
        }
        rowHashes.toSortedMap(compareBy { it.tag }).forEach { (table, hashes) ->
            hashes.sorted().forEach { append("|row:").append(table.tag).append('=').append(it) }
        }
        aggregates.sortedWith(compareBy({ it.kind }, { it.id })).forEach {
            append("|aggregate:").append(it.kind).append('/').append(it.id).append('/').append(it.schema)
        }
        operations.sortedBy { it.operationId }.forEach {
            append("|operation:").append(it.operationId).append('/').append(it.actorId)
                .append('/').append(it.command)
        }
        audits.sortedWith(compareBy({ it.kind }, { it.operationId }, { it.actorId })).forEach {
            append("|audit:").append(it.kind).append('/').append(it.operationId).append('/').append(it.actorId)
        }
    }

    companion object {
        private val BUSINESS_TABLES = listOf(
            NativeHistoryTableV2.OPERATION, NativeHistoryTableV2.AGGREGATE, NativeHistoryTableV2.CLAIM,
            NativeHistoryTableV2.JOURNAL, NativeHistoryTableV2.AUDIT, NativeHistoryTableV2.OUTBOX,
            NativeHistoryTableV2.JOURNAL_FRAGMENT, NativeHistoryTableV2.STOCK_POSITION,
            NativeHistoryTableV2.STOCK_ALLOCATION, NativeHistoryTableV2.STOCK_RESERVATION,
            NativeHistoryTableV2.STOCK_VALUE,
        )
        private val SESSION_TABLES = listOf(NativeHistoryTableV2.ACCESS, NativeHistoryTableV2.REFRESH,
            NativeHistoryTableV2.ARCHIVE, NativeHistoryTableV2.APPROVAL)
        private val STOCK_TABLES = listOf(NativeHistoryTableV2.STOCK_POSITION,
            NativeHistoryTableV2.STOCK_ALLOCATION, NativeHistoryTableV2.STOCK_RESERVATION,
            NativeHistoryTableV2.STOCK_VALUE)
        private val STOCK_AND_CLAIM_TABLES = STOCK_TABLES + NativeHistoryTableV2.CLAIM
    }
}

internal suspend fun readAosfNativeCut(application: Application): AosfNativeCut {
    var context: com.ukkera.pos.security.SelfOwnedLocalSecurityContext? = null
    var custody: SelfOwnedNativeHistoryCustody? = null
    try {
        val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val actualContext = (opened as? SelfOwnedNativeOpenResult.Opened)?.context
            ?: error("Closed native read reopen is unavailable: $opened")
        context = actualContext
        val claimedCustody = withContext(NonCancellable) {
            context = null // The claim producer owns failed/undelivered non-BUSY cleanup.
            when (val claimed = AndroidSelfOwnedInstallationOwner.claimOriginalHistoryCustody(actualContext)) {
                is SelfOwnedNativeHistoryClaimResult.Claimed -> claimed.custody.also { custody = it }
                is SelfOwnedNativeHistoryClaimResult.Unresolved -> {
                    if (claimed.reason == com.ukkera.pos.security.SelfOwnedInstallationFailureV1.BUSY)
                        context = actualContext // A rejected BUSY claim transfers nothing.
                    error("Original native history custody is unavailable: $claimed")
                }
            }
        }
        return claimedCustody.runRoomWork(
            block = { readCut(claimedCustody) },
            discard = { claimedCustody.denyDataUse() },
        )
    } finally {
        cleanupAosf(
            { custody?.let { actual -> custody = null; actual.close() } },
            { context?.let { actual -> context = null; actual.close() } },
        )
    }
}

private suspend fun readCut(custody: SelfOwnedNativeHistoryCustody): AosfNativeCut {
    val read = custody.openOriginalReadOnly()
    var closeStarted = false
    try {
        while (read.readSchemaPage() != null) {
            // Consume every actual schema page; no caller-selected table/column query.
        }
        val applicationTables = NativeHistoryPhysicalReadProfileV2.applicationTables
        val counts = applicationTables.associateWith { 0L }.toMutableMap()
        val hashes = applicationTables.associateWith { mutableListOf<String>() }.toMutableMap()
        val aggregates = mutableListOf<AosfAggregateFact>()
        val operations = mutableListOf<AosfOperationFact>()
        val audits = mutableListOf<AosfAuditFact>()
        val complete = mutableSetOf<NativeHistoryTableV2>()
        var branchTexts: Map<String, String>? = null
        var branchIntegers: Map<String, Long>? = null
        while (true) {
            val metadata = read.nextRowMetadata() ?: break
            val census = metadata.pass == NativeHistoryReadPassV2.CENSUS
            if (census && metadata.kind == NativeHistoryRowKindV2.PASS_END) {
                complete.add(checkNotNull(metadata.table))
            }
            val physical = census && metadata.kind == NativeHistoryRowKindV2.PHYSICAL
            val table = if (physical) checkNotNull(metadata.table) else null
            val columns = table?.let { NativeHistoryPhysicalReadProfileV2.columns(it) }
            val captured = table != null && table in AOSF_CAPTURED_TEXT_TABLES
            val rowTexts = if (captured) linkedMapOf<String, String>() else null
            val rowIntegers = if (captured) linkedMapOf<String, Long>() else null
            val rowDigest = MessageDigest.getInstance("SHA-256")
            for ((index, cell) in metadata.cells.withIndex()) {
                rowDigest.update("$index:${cell.name}:${cell.type.tag}:${cell.byteCount}:${cell.integerValue}"
                    .encodeToByteArray())
                val column = columns?.get(index)
                var text: ByteArrayOutputStream? =
                    if (column?.type == NativeHistorySqliteTypeV2.TEXT && rowTexts != null) ByteArrayOutputStream() else null
                var offset = 0L
                do {
                    val chunk = read.readCellChunk()
                    try {
                        val bytes = chunk.copyBytes()
                        try {
                            check(chunk.column == index && chunk.offset == offset &&
                                chunk.totalBytes == cell.byteCount && chunk.integerValue == cell.integerValue &&
                                offset + bytes.size <= cell.byteCount) {
                                "Closed native cell metadata changed"
                            }
                            rowDigest.update("chunk:$offset:${chunk.integerValue}:${bytes.size}".encodeToByteArray())
                            rowDigest.update(bytes)
                            text?.write(bytes)
                            offset += bytes.size
                            check(chunk.last == (offset == cell.byteCount)) { "Closed native cell chunk is incomplete" }
                            if (chunk.last) break
                            check(bytes.isNotEmpty()) { "Closed native cell made no progress" }
                        } finally {
                            bytes.fill(0)
                        }
                    } finally {
                        chunk.clear()
                    }
                } while (true)
                check(offset == cell.byteCount)
                if (column != null && rowTexts != null && rowIntegers != null) {
                    if (column.type == NativeHistorySqliteTypeV2.TEXT) {
                        val textValue = text?.let {
                            val bytes = it.toByteArray()
                            try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
                        }
                        if (textValue != null) rowTexts[column.name] = textValue
                    } else if (column.type == NativeHistorySqliteTypeV2.INTEGER) {
                        rowIntegers[column.name] = checkNotNull(cell.integerValue)
                    }
                }
                text = null
            }
            if (table != null && table.application) {
                counts[table] = counts.getValue(table) + 1L
                hashes.getValue(table).add(rowDigest.digest().toHex())
                when (table) {
                    NativeHistoryTableV2.AGGREGATE -> checkNotNull(rowTexts).let {
                        aggregates += AosfAggregateFact(
                            it.getValue("kind"), it.getValue("id"), it.getValue("schema"))
                    }
                    NativeHistoryTableV2.OPERATION -> checkNotNull(rowTexts).let {
                        operations += AosfOperationFact(
                            it.getValue("operationId"), it.getValue("actorId"),
                            it.getValue("command"))
                    }
                    NativeHistoryTableV2.AUDIT -> checkNotNull(rowTexts).let {
                        audits += AosfAuditFact(
                            it.getValue("kind"), it.getValue("operationId"),
                            it.getValue("actorId"))
                    }
                    NativeHistoryTableV2.BRANCH -> {
                        branchTexts = rowTexts
                        branchIntegers = rowIntegers
                    }
                    else -> Unit
                }
            }
        }
        check(complete == applicationTables.toSet()) {
            "Closed native cut did not complete every current-schema application table"
        }
        val original = read.original
        val record = original.record
        val branch = branchTexts ?: error("The actual original branch row is unavailable")
        val cut = AosfNativeCut(
            recordDigest = original.recordDigest.value,
            definitionDigest = record.definition?.definitionDigest?.value,
            receiptDigest = record.receipt?.digest?.value,
            authorityEpoch = record.receipt?.core?.authorityEpoch?.value ?: -1L,
            generation = record.generation,
            pending = record.pending != null,
            database = record.database,
            counts = counts,
            rowHashes = hashes.mapValues { it.value.toList() },
            aggregates = aggregates.toList(),
            operations = operations.toList(),
            audits = audits.toList(),
            branchScope = branch["scope"],
            branchEpoch = branchIntegers?.get("epoch"),
            branchWritable = branchIntegers?.get("writable"),
            branchAssignment = branch["assignmentId"],
            branchDevice = branch["deviceId"],
        )
        check(cut.branchScope != null && cut.branchEpoch != null && cut.branchWritable != null &&
            cut.branchAssignment != null && cut.branchDevice != null) {
            "The current-schema branch authority row is incomplete"
        }
        closeStarted = true
        val closed = withContext(NonCancellable) { read.closeOriginalRead() }
        val after = custody.reobserveAfterActualClose(closed)
        check(after.lifetimeId == original.lifetimeId && after.recordDigest == original.recordDigest &&
            after.record.database == original.record.database) {
            "Closed native read did not reobserve the same original"
        }
        custody.completeOriginalReadDelivery(after)
        return cut
    } finally {
        if (!closeStarted) {
            withContext(NonCancellable) { read.closeOriginalRead() }
        }
    }
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 15])
        }
    }
}
