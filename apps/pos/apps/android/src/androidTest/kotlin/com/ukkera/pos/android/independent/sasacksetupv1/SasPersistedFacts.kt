package com.ukkera.pos.android.independent.sasacksetupv1

import android.app.Application
import com.ukkera.pos.android.independent.aosfsetupv2.cleanupAosf
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.NativeHistoryPhysicalReadProfileV2
import com.ukkera.pos.security.NativeHistoryReadPassV2
import com.ukkera.pos.security.NativeHistoryRowKindV2
import com.ukkera.pos.security.NativeHistorySqliteTypeV2
import com.ukkera.pos.security.NativeHistoryTableV2
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class SasAggregateFact(val kind: String, val id: String, val schema: String)
internal data class SasOperationFact(val operationId: String, val actorId: String, val command: String)
internal data class SasAuditFact(val kind: String, val operationId: String, val actorId: String)

/**
 * Independent cut over the actual closed original current-schema store. It reads only through
 * the production native history custody and exposes setup aggregate kinds, operation actors/
 * commands, audit identities, table counts and the branch scalar. It is not a master client,
 * session, grant, readiness source or substitute for the protected setup read.
 */
internal class SasPersistedFacts(
    val recordDigest: String,
    val definitionDigest: String?,
    val receiptDigest: String?,
    val authorityEpoch: Long,
    val pending: Boolean,
    private val counts: Map<NativeHistoryTableV2, Long>,
    val aggregates: List<SasAggregateFact>,
    val operations: List<SasOperationFact>,
    val audits: List<SasAuditFact>,
    val branchEpoch: Long?,
    val branchWritable: Long?,
) {
    fun count(table: NativeHistoryTableV2): Long = counts.getValue(table)

    fun setupAggregates(kind: String): List<SasAggregateFact> = aggregates.filter { it.kind == kind }

    fun requireNoSetupFacts() {
        check(setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).isEmpty()) {
            "An acknowledgement fact is present where the frozen case requires absence"
        }
        check(setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).isEmpty()) {
            "A go-live fact is present where the frozen case requires absence"
        }
    }

    fun requireSetupAggregateDelta(before: SasPersistedFacts, kind: String, delta: Int) {
        check(setupAggregates(kind).size == before.setupAggregates(kind).size + delta) {
            "The $kind aggregate count is not the expected +$delta"
        }
    }

    fun requireSameSetupBase(other: SasPersistedFacts) {
        // recordDigest/generation index mutable native history state, not the saved installation
        // identity. Normal rejection-journal semantics may advance them without a setup fact.
        check(definitionDigest == other.definitionDigest && receiptDigest == other.receiptDigest &&
            authorityEpoch == other.authorityEpoch && !pending && !other.pending &&
            branchEpoch == other.branchEpoch && branchWritable == other.branchWritable) {
            "The saved original setup/authority identity changed"
        }
        for (table in SETUP_TABLES) check(count(table) == other.count(table)) {
            "The original setup root/account/policy/admission count changed: $table"
        }
        for (table in STOCK_TABLES) check(count(table) == other.count(table)) {
            "A denied setup action changed the sentinel stock: $table"
        }
    }

    fun requireOneAcknowledgement(before: SasPersistedFacts, actorId: DomainId, operationId: OperationId) {
        requireSameSetupBase(before)
        check(setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size ==
            before.setupAggregates(LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND).size + 1) {
            "Exactly one new acknowledgement aggregate was not committed"
        }
        check(setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).isEmpty()) {
            "Acknowledgement implicitly created a go-live fact"
        }
        check(operations.any {
            it.operationId == operationId.value && it.actorId == actorId.value &&
                it.command == LocalSetupResultProfileV1.ACKNOWLEDGE.name
        }) { "The retained acknowledgement operation/actor is absent" }
        check(count(NativeHistoryTableV2.AGGREGATE) == before.count(NativeHistoryTableV2.AGGREGATE) + 1L) {
            "The acknowledgement did not commit exactly one aggregate"
        }
        check(count(NativeHistoryTableV2.OPERATION) == before.count(NativeHistoryTableV2.OPERATION) + 1L) {
            "The acknowledgement did not commit exactly one retained operation"
        }
        check(count(NativeHistoryTableV2.JOURNAL) == before.count(NativeHistoryTableV2.JOURNAL) + 1L) {
            "The acknowledgement did not commit exactly one journal entry"
        }
    }

    fun requireOneGoLive(before: SasPersistedFacts, actorId: DomainId, operationId: OperationId) {
        requireSameSetupBase(before)
        check(setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size ==
            before.setupAggregates(LocalSetupResultProfileV1.GO_LIVE_KIND).size + 1) {
            "Exactly one new go-live aggregate was not committed"
        }
        check(operations.any {
            it.operationId == operationId.value && it.actorId == actorId.value &&
                it.command == LocalSetupResultProfileV1.GO_LIVE.name
        }) { "The retained go-live operation/actor is absent" }
        check(count(NativeHistoryTableV2.AGGREGATE) == before.count(NativeHistoryTableV2.AGGREGATE) + 1L) {
            "The go-live did not commit exactly one aggregate"
        }
        check(count(NativeHistoryTableV2.OPERATION) == before.count(NativeHistoryTableV2.OPERATION) + 1L) {
            "The go-live did not commit exactly one retained operation"
        }
        check(count(NativeHistoryTableV2.JOURNAL) == before.count(NativeHistoryTableV2.JOURNAL) + 1L) {
            "The go-live did not commit exactly one journal entry"
        }
    }

    private companion object {
        val SETUP_TABLES = listOf(NativeHistoryTableV2.BRANCH, NativeHistoryTableV2.ACCOUNT,
            NativeHistoryTableV2.POLICY, NativeHistoryTableV2.ADMISSION)
        val STOCK_TABLES = listOf(NativeHistoryTableV2.STOCK_POSITION,
            NativeHistoryTableV2.STOCK_ALLOCATION, NativeHistoryTableV2.STOCK_RESERVATION,
            NativeHistoryTableV2.STOCK_VALUE)
    }
}

internal suspend fun readSasPersistedFacts(application: Application): SasPersistedFacts {
    var context: SelfOwnedLocalSecurityContext? = null
    var custody: SelfOwnedNativeHistoryCustody? = null
    try {
        val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val actualContext = (opened as? SelfOwnedNativeOpenResult.Opened)?.context
            ?: error("Closed native setup-fact read is unavailable: $opened")
        context = actualContext
        val claimedCustody = withContext(NonCancellable) {
            context = null
            when (val claimed = AndroidSelfOwnedInstallationOwner.claimOriginalHistoryCustody(actualContext)) {
                is SelfOwnedNativeHistoryClaimResult.Claimed -> claimed.custody.also { custody = it }
                is SelfOwnedNativeHistoryClaimResult.Unresolved -> {
                    if (claimed.reason == SelfOwnedInstallationFailureV1.BUSY) context = actualContext
                    error("Original native history custody is unavailable: $claimed")
                }
            }
        }
        return claimedCustody.runRoomWork(
            block = { readFacts(claimedCustody) },
            discard = { claimedCustody.denyDataUse() },
        )
    } finally {
        cleanupAosf(
            { custody?.let { actual -> custody = null; actual.close() } },
            { context?.let { actual -> context = null; actual.close() } },
        )
    }
}

private suspend fun readFacts(custody: SelfOwnedNativeHistoryCustody): SasPersistedFacts {
    val read = custody.openOriginalReadOnly()
    var closeStarted = false
    try {
        while (read.readSchemaPage() != null) {
            // Consume every actual schema page; no caller-selected table/column query.
        }
        val applicationTables = NativeHistoryPhysicalReadProfileV2.applicationTables
        val counts = applicationTables.associateWith { 0L }.toMutableMap()
        val aggregates = mutableListOf<SasAggregateFact>()
        val operations = mutableListOf<SasOperationFact>()
        val audits = mutableListOf<SasAuditFact>()
        var branchEpoch: Long? = null
        var branchWritable: Long? = null
        while (true) {
            val metadata = read.nextRowMetadata() ?: break
            val physical = metadata.pass == NativeHistoryReadPassV2.CENSUS &&
                metadata.kind == NativeHistoryRowKindV2.PHYSICAL
            val table = if (physical) checkNotNull(metadata.table) else null
            val columns = table?.let { NativeHistoryPhysicalReadProfileV2.columns(it) }
            val texts = linkedMapOf<String, String>()
            val integers = linkedMapOf<String, Long>()
            for ((index, cell) in metadata.cells.withIndex()) {
                val column = columns?.get(index)
                var text: ByteArrayOutputStream? =
                    if (column?.type == NativeHistorySqliteTypeV2.TEXT) ByteArrayOutputStream() else null
                var offset = 0L
                do {
                    val chunk = read.readCellChunk()
                    try {
                        val bytes = chunk.copyBytes()
                        try {
                            check(chunk.column == index && chunk.offset == offset &&
                                chunk.totalBytes == cell.byteCount && chunk.integerValue == cell.integerValue &&
                                offset + bytes.size <= cell.byteCount) { "Closed native cell metadata changed" }
                            text?.write(bytes)
                            offset += bytes.size
                            check(chunk.last == (offset == cell.byteCount)) { "Closed native cell chunk is incomplete" }
                            if (chunk.last) break
                            check(bytes.isNotEmpty()) { "Closed native cell made no progress" }
                        } finally { bytes.fill(0) }
                    } finally { chunk.clear() }
                } while (true)
                check(offset == cell.byteCount)
                if (column != null) {
                    if (column.type == NativeHistorySqliteTypeV2.TEXT && text != null) {
                        texts[column.name] = text.toByteArray().decodeToString(throwOnInvalidSequence = true)
                    } else if (column.type == NativeHistorySqliteTypeV2.INTEGER) {
                        integers[column.name] = checkNotNull(cell.integerValue)
                    }
                }
                text = null
            }
            if (table != null && table.application) {
                counts[table] = counts.getValue(table) + 1L
                when (table) {
                    NativeHistoryTableV2.AGGREGATE -> aggregates += SasAggregateFact(
                        texts.getValue("kind"), texts.getValue("id"), texts.getValue("schema"))
                    NativeHistoryTableV2.OPERATION -> operations += SasOperationFact(
                        texts.getValue("operationId"), texts.getValue("actorId"), texts.getValue("command"))
                    NativeHistoryTableV2.AUDIT -> audits += SasAuditFact(
                        texts.getValue("kind"), texts.getValue("operationId"), texts.getValue("actorId"))
                    NativeHistoryTableV2.BRANCH -> {
                        branchEpoch = integers["epoch"]
                        branchWritable = integers["writable"]
                    }
                    else -> Unit
                }
            }
        }
        val original = read.original
        val facts = SasPersistedFacts(
            recordDigest = original.recordDigest.value,
            definitionDigest = original.record.definition?.definitionDigest?.value,
            receiptDigest = original.record.receipt?.digest?.value,
            authorityEpoch = original.record.receipt?.core?.authorityEpoch?.value ?: -1L,
            pending = original.record.pending != null,
            counts = counts,
            aggregates = aggregates.toList(),
            operations = operations.toList(),
            audits = audits.toList(),
            branchEpoch = branchEpoch,
            branchWritable = branchWritable,
        )
        check(facts.branchEpoch != null && facts.branchWritable != null) {
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
        return facts
    } finally {
        if (!closeStarted) {
            withContext(NonCancellable) { read.closeOriginalRead() }
        }
    }
}
