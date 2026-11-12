package com.ukkera.pos.security

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Concrete no-CREATE raw resources, retained by the SAME P2 lifetime before open. There is
 * no external SQL/path/table/offset/driver/provider/borrower/close callback. All native calls,
 * statement destruction, undelivered plaintext/chunks and actual connection close use P2's
 * independently watched admitted work. A Kotlin finally/isOpen flag never releases its lease.
 */
internal class AndroidSelfOwnedHistoryReadStorage(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val work: AndroidOwnedInstallationWork,
    private val environment: AndroidSelfOwnedEnvironment,
    private val namespace: AndroidSelfOwnedDatabaseNamespace,
    private val anchor: AndroidSelfOwnedHistoryAnchor,
    private val material: AndroidSelfOwnedDataKeyMaterial,
    private val ownerIdentity: Any,
    val original: OriginalNativeHistorySnapshot,
    val employeeSelection: NativeEmployeeEnrollmentHistorySelection? = null,
    val policySelection: NativeEmployeePolicyActivationHistorySelection? = null,
    val businessPublicationSelection: NativeBusinessPublicationHistorySelection? = null,
    val deviceAdmissionSelection: NativeDeviceAdmissionHistorySelection? = null,
) : SelfOwnedNativeHistoryReadController {
    // This SAME retained native controller owns the additional finite event-image grammar.
    // Presence is not a close/custody proof and exposes no caller-selected read operation.
    override val hasChronologicalAccountImageTraversal: Boolean get() = true
    override val hasChronologicalProtectedSeedTraversal: Boolean get() = true
    override val hasDataWorkspace: Boolean get() = true
    private enum class State { NEW, OPENING, READING, CLOSING, CLOSED, UNKNOWN }
    private var state = State.NEW
    private val sessionIdentity = Any()
    private var databasePin: AndroidSelfOwnedDatabaseNamespace.OriginalDatabasePin? = null
    private var actuallyClosedPin: AndroidSelfOwnedDatabaseNamespace.OriginalDatabasePin? = null
    private var connection: SQLiteConnection? = null
    private var connectionCloseStarted = false
    private val statements = mutableListOf<OwnedStatement>()
    private val schemaQueries = ArrayDeque<SchemaQuery>()
    private var schemaCursor: Pair<SchemaQuery,OwnedStatement>? = null
    private var schemaRows = 0
    private var schemaEnded = false
    private var traversalEnded = false
    private val roots = ArrayDeque<Node>()
    private val stack = ArrayDeque<Node>()
    private var active: CellSource? = null
    // Same native owner retains undelivered plaintext until the next batch or actual
    // close, including cancellation/abandonment after a delivered wrapper is lost.
    private var retainedRowBatch: List<NativeHistoryRowBatchItemV2> = emptyList()
    private var lastClose: SelfOwnedNativeHistoryReadClosed? = null
    private var dataWorkspace: AndroidSelfOwnedHistoryWorkspace? = null

    fun openNative() {
        check(state == State.NEW)
        state = State.OPENING
        work.roomCoroutines.traceStage("history-read-open-before-custody")
        environment.requireExistingOriginalObservation() // Before any potentially creating reobserve.
        owner.requireDataNative(); anchor.requireSameOriginalCut(original)
        val binding = original.record.database ?: uncertain()
        work.roomCoroutines.traceStage("history-read-open-after-custody")
        try {
            val pin = namespace.pinOriginalDatabase(binding,AndroidSelfOwnedDatabaseNamespace.OriginalDatabaseUse.STABLE_READ)
            databasePin = pin
            work.roomCoroutines.traceStage("history-read-open-after-pin")
            val driver = BundledSQLiteDriver()
            known(!driver.hasConnectionPool)
            // The one-argument open is deliberately forbidden: it includes CREATE.
            connection = driver.open(pin.path,SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW)
            work.roomCoroutines.traceStage("history-read-open-after-sqlite")
            namespace.assertOriginalDatabasePin(pin)
            executeFixed("BEGIN DEFERRED TRANSACTION")
            known(requireNotNull(connection).inTransaction())
            verifyOnlyOriginalMain()
            known(scalarText("PRAGMA main.journal_mode") in setOf("delete","truncate"))
            known(scalarText("PRAGMA main.encoding") == "UTF-8")
            val pageSize = scalarLong("PRAGMA main.page_size")
            known(pageSize in 512..65536 && pageSize and (pageSize-1) == 0L)
            enqueueSchema(); enqueueTraversal()
            state = State.READING
            guard()
            work.roomCoroutines.traceStage("history-read-open-returned")
        } catch (failure: Throwable) {
            // Partially opened resources were already retained. Actual disposal is watched.
            try { closeNative() } catch (_: Throwable) { work.nativeClosureUnknown() }
            throw failure
        }
    }

    override suspend fun readSchemaPage(): NativeHistorySchemaPageV2? = perform(phase = "history-schema-page") { readSchemaPageNative() }
    override suspend fun nextRowMetadata(): NativeHistoryRowMetadataV2? = perform(phase = "history-row-metadata") { nextRowNative() }
    override suspend fun readCellChunk(): NativeHistoryCellChunkV2 = perform(phase = "history-cell-chunk", dispose={ it.clear() }) { readCellNative() }

    override suspend fun readCellBatch(): List<NativeHistoryCellChunkV2> =
        perform(phase = "history-cell-chunk-batch", dispose = { chunks -> chunks.forEach { it.clear() } }) {
            // One fixed, non-suspending native task over this row's pinned snapshot.
            // perform() re-proves app/namespace/journal/all native keys and pin before
            // AND after the whole task; no proof or handle escapes between chunk reads.
            // At most 16 unchanged 64-KiB chunks (1 MiB) are retained for delivery.
            // This never advances nextRowNative(), so it cannot bypass row validation.
            val chunks = ArrayList<NativeHistoryCellChunkV2>(NativeHistoryPhysicalReadProfileV2.CELL_BATCH_CHUNKS)
            try {
                do {
                    work.requireActive() // Same original watchdog, including between chunks.
                    chunks += readCellNative()
                } while (active != null && chunks.size < NativeHistoryPhysicalReadProfileV2.CELL_BATCH_CHUNKS)
                chunks
            } catch (failure: Throwable) {
                chunks.forEach { it.clear() }
                throw failure
            }
        }

    override suspend fun readRowBatch(): List<NativeHistoryRowBatchItemV2> =
        perform(phase = "history-row-metadata-and-data-batch", dispose = { rows -> rows.forEach { it.clear() } }) {
            // Only fully materialized DATA is grouped. The same guarded task checks
            // custody/pin before and after; every row keeps its original metadata,
            // chunk order and scanner validation. No proof is cached between tasks.
            retainedRowBatch.forEach { it.clear() }
            retainedRowBatch = emptyList()
            val rows = ArrayList<NativeHistoryRowBatchItemV2>(NativeHistoryPhysicalReadProfileV2.ROW_BATCH_ROWS)
            var chunksRead = 0
            var bytesRead = 0
            try {
                do {
                    work.requireActive() // Original watchdog, never a new allowance.
                    val metadata = nextRowNative() ?: break
                    val chunks = ArrayList<NativeHistoryCellChunkV2>()
                    try {
                        while (active != null && chunksRead < NativeHistoryPhysicalReadProfileV2.ROW_BATCH_CHUNKS &&
                            bytesRead <= NativeHistoryPhysicalReadProfileV2.ROW_BATCH_BYTES - NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES) {
                            work.requireActive()
                            val chunk = readCellNative()
                            chunks += chunk
                            chunksRead++
                            bytesRead += chunk.byteCount
                        }
                        rows += NativeHistoryRowBatchItemV2(metadata, chunks)
                    } catch (failure: Throwable) { chunks.forEach { it.clear() }; throw failure }
                    // A partial row is always last. Later cell reads finish it before
                    // another metadata operation can advance the source.
                } while (active == null && rows.size < NativeHistoryPhysicalReadProfileV2.ROW_BATCH_ROWS &&
                    chunksRead < NativeHistoryPhysicalReadProfileV2.ROW_BATCH_CHUNKS &&
                    bytesRead <= NativeHistoryPhysicalReadProfileV2.ROW_BATCH_BYTES - NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES)
                rows.also { retainedRowBatch = it }
            } catch (failure: Throwable) { rows.forEach { it.clear() }; throw failure }
        }

    override suspend fun openDataWorkspace(session: SelfOwnedNativeHistoryReadSession): SelfOwnedNativeHistoryWorkspace =
        perform(dispose = { dataWorkspace?.closeNative() }) {
            known(session.controller === this && session.original === original)
            if (dataWorkspace != null) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
            val retained = AndroidSelfOwnedHistoryWorkspace(this, work, environment, original)
            dataWorkspace = retained // Retain before any file/key/SQLite acquisition.
            retained.openNative()
            SelfOwnedNativeHistoryWorkspace(retained, original)
        }

    /** Only this retained source can admit DATA work; equality/copies cannot select a source. */
    internal fun requireWorkspaceNative(expected: OriginalNativeHistorySnapshot) {
        known(expected === original)
        guard()
    }

    /** Private correlation only; the independent DATA owner retains no reader/session object. */
    internal fun receiptMembershipReadIdentityNative(session: SelfOwnedNativeHistoryReadSession): Any {
        known(session.controller === this && session.original === original)
        guard()
        return sessionIdentity
    }

    internal fun requireReceiptMembershipReadNative(identity: Any) {
        known(identity === sessionIdentity)
        guard()
    }

    internal fun denyWorkspaceUseAndRequestClose() {
        owner.denyDataUse()
        owner.requestHistoryLifetimeClose()
    }

    override suspend fun closeOriginalRead(): SelfOwnedNativeHistoryReadClosed = withContext(NonCancellable) {
        when (val result = work.run {
            object : AndroidOwnedTask<SelfOwnedNativeHistoryReadClosed> {
                override fun execute(): SelfOwnedNativeHistoryReadClosed {
                    val complete = traversalEnded && active == null
                    if (complete) {
                        employeeSelection?.requireActualPrepass()
                        policySelection?.requireActualTraversal()
                        businessPublicationSelection?.requireActualTraversal()
                        deviceAdmissionSelection?.requireActualTraversal()
                    }
                    work.roomCoroutines.traceStage("history-read-before-actual-close")
                    closeNative() // May block; the same P2 watchdog still watches it.
                    work.roomCoroutines.traceStage("history-read-after-actual-close")
                    val value = SelfOwnedNativeHistoryReadClosed(ownerIdentity,sessionIdentity,original,complete,
                        employeeSelection,policySelection,businessPublicationSelection,deviceAdmissionSelection)
                    lastClose = value
                    owner.historyReadActuallyClosedNative(this@AndroidSelfOwnedHistoryReadStorage,value)
                    return value
                }
                override fun discard(value: SelfOwnedNativeHistoryReadClosed) = Unit
                override fun clearInputs() = Unit
            }
        }) {
            is AndroidOwnedResult.Value -> result.value
            is AndroidOwnedResult.Unavailable -> {
                // A cancelled in-flight job may still hold the work slot. Do NOT infer close
                // or retry a descriptor; actual lifetime close waits for its native disposal.
                owner.denyDataUse()
                owner.requestHistoryLifetimeClose()
                throw NativeHistoryUnavailable(result.reason)
            }
        }
    }

    fun ownsClose(value: SelfOwnedNativeHistoryReadClosed): Boolean =
        state == State.CLOSED && dataWorkspace?.actuallyClosedNative != false && value === lastClose && value.sessionIdentity === sessionIdentity && value.ownerIdentity === ownerIdentity &&
            value.employeeSelection === employeeSelection && value.policySelection === policySelection &&
            value.businessPublicationSelection === businessPublicationSelection && value.deviceAdmissionSelection === deviceAdmissionSelection

    fun reobserveOriginalFileAfterActualClose(value: SelfOwnedNativeHistoryReadClosed) {
        known(ownsClose(value) && value.completeTraversal && !value.consumed)
        namespace.reobserveOriginalDatabaseAfterActualClose(requireNotNull(actuallyClosedPin))
        actuallyClosedPin = null
    }

    /** Called by the lifetime's actual close path, including abandoned opens/reads. */
    fun closeNative() {
        if (state == State.CLOSED) return
        if (state == State.CLOSING || state == State.UNKNOWN) { work.nativeClosureUnknown(); uncertain() }
        // Refuse an unparked DATA candidate before starting any original close. A refused
        // precondition has not consumed a statement/connection/pin close and can still be
        // disposed by the terminal owner after its independent DATA cleanup.
        owner.requireReceiptMembershipParkedBeforeOriginalClose(this, sessionIdentity)
        state = State.CLOSING
        try {
            // Scratch actual close/removal must return before original close evidence exists.
            dataWorkspace?.closeNative()
            retainedRowBatch.forEach { it.clear() }; retainedRowBatch = emptyList()
            active?.clear(); active = null
            roots.forEach { it.clear() }; roots.clear()
            stack.forEach { it.clear() }; stack.clear()
            for (statement in statements.toList().asReversed()) statement.closeActual()
            schemaCursor = null; schemaQueries.clear()
            val handle = connection
            if (handle != null) {
                if (handle.inTransaction()) executeFixed("ROLLBACK")
                known(!handle.inTransaction())
                if (connectionCloseStarted) uncertain()
                connectionCloseStarted = true
                handle.close() // Only its actual return permits forgetting the handle.
                connection = null
            }
            databasePin?.let {
                namespace.closeOriginalDatabasePin(it)
                actuallyClosedPin = it; databasePin = null
            }
            state = State.CLOSED
        } catch (failure: Throwable) { state = State.UNKNOWN; work.nativeClosureUnknown(); throw failure }
    }

    /**
     * Distinct internal startup primitive. No P3 method calls it. The future F03 owner must
     * fence/drain original submission and retain this SAME lifetime before invoking. It does
     * no business SQL, CREATE, migration, native settlement or absence classification.
     */
    internal class RollbackPager private constructor(
        private val owner: AndroidSelfOwnedInstallationLifetime,
        private val work: AndroidOwnedInstallationWork,
        private val environment: AndroidSelfOwnedEnvironment,
        private val namespace: AndroidSelfOwnedDatabaseNamespace,
        private val binding: NativeHistoryDatabaseIdentityV2,
    ) {
        private var pin: AndroidSelfOwnedDatabaseNamespace.OriginalDatabasePin? = null
        private var handle: SQLiteConnection? = null
        private var statement: SQLiteStatement? = null
        private var statementCloseStarted = false
        private var handleCloseStarted = false
        private var closed = false
        private var recoveryReadAndCloseCompleted = false
        fun recoverAndCloseNative(): ActualPagerClose {
            environment.requireExistingOriginalObservation(); owner.requireDataNative()
            check(pin == null && handle == null && !closed)
            try {
                // No size/header/page validity check before this actual RW/noCREATE pager.
                pin = namespace.pinOriginalDatabase(binding,AndroidSelfOwnedDatabaseNamespace.OriginalDatabaseUse.ROLLBACK_PAGER)
                val driver = BundledSQLiteDriver(); knownPager(!driver.hasConnectionPool)
                handle = driver.open(requireNotNull(pin).path,SQLITE_OPEN_READWRITE or SQLITE_OPEN_NOFOLLOW)
                namespace.assertOriginalDatabasePin(requireNotNull(pin))
                statement = requireNotNull(handle).prepare("PRAGMA main.user_version")
                // SQLite's first real read takes the locks and performs genuine hot rollback.
                knownPager(requireNotNull(statement).step() && requireNotNull(statement).getColumnType(0) == 1)
                requireNotNull(statement).getLong(0); knownPager(!requireNotNull(statement).step())
                closeNative()
                recoveryReadAndCloseCompleted = true // Set only after the real pager read AND close both returned.
                val completion = ActualPagerClose.fromActuallyClosed(this)
                owner.historyRollbackPagerActuallyClosedNative(this,completion)
                return completion
            } catch (failure: Throwable) {
                try { closeNative() } catch (_: Throwable) { work.nativeClosureUnknown() }
                throw failure
            }
        }
        fun closeNative() {
            if (closed) return
            try {
                statement?.let {
                    if (statementCloseStarted) { work.nativeClosureUnknown(); uncertainPager() }
                    statementCloseStarted = true; it.close(); statement = null
                }
                handle?.let {
                    if (handleCloseStarted) { work.nativeClosureUnknown(); uncertainPager() }
                    handleCloseStarted = true; it.close(); handle = null
                }
                pin?.let { namespace.closeOriginalDatabasePin(it); pin = null }
                closed = true
            } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
        internal fun requireOriginalCustody(actualOwner: AndroidSelfOwnedInstallationLifetime,
            actualWork: AndroidOwnedInstallationWork, actualEnvironment: AndroidSelfOwnedEnvironment,
            actualNamespace: AndroidSelfOwnedDatabaseNamespace, originalBinding: NativeHistoryDatabaseIdentityV2) {
            knownPager(owner === actualOwner && work === actualWork && environment === actualEnvironment &&
                namespace === actualNamespace && binding == originalBinding && pin == null && handle == null && !closed)
        }
        internal fun requireActualRecoveryClose() {
            knownPager(recoveryReadAndCloseCompleted && closed && pin == null && handle == null && statement == null)
        }
        private fun knownPager(value: Boolean) { if (!value) uncertainPager() }
        private fun uncertainPager(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        companion object {
            fun retainedCandidate(owner: AndroidSelfOwnedInstallationLifetime,work: AndroidOwnedInstallationWork,
                environment: AndroidSelfOwnedEnvironment,namespace: AndroidSelfOwnedDatabaseNamespace,
                binding: NativeHistoryDatabaseIdentityV2): RollbackPager = RollbackPager(owner,work,environment,namespace,binding).also {
                    owner.retainHistoryRollbackPagerNative(it)
                }
        }
    }
    internal class ActualPagerClose private constructor(private val actualPager: RollbackPager) {
        internal fun requireOriginalPager(expected: RollbackPager) {
            check(actualPager === expected)
            actualPager.requireActualRecoveryClose()
        }
        companion object {
            internal fun fromActuallyClosed(actual: RollbackPager): ActualPagerClose {
                actual.requireActualRecoveryClose()
                return ActualPagerClose(actual)
            }
        }
    }

    private suspend fun <T> perform(phase: String = "history-owned-action", dispose: (T)->Unit = {}, body: ()->T): T {
        // Fixed call-site labels and bounded static stacks only; no row, key or path data.
        work.roomCoroutines.traceStage("$phase-before-custody")
        return when (val result = work.run {
            object : AndroidOwnedTask<T> {
                override fun execute(): T {
                    guard()
                    work.roomCoroutines.traceStage("$phase-after-custody")
                    val value = body()
                    try {
                        guard()
                        work.roomCoroutines.traceStage("$phase-returned")
                        return value
                    }
                    catch (failure: Throwable) { dispose(value); throw failure }
                }
                override fun discard(value: T) { dispose(value) }
                override fun clearInputs() = Unit
            }
        }) {
            is AndroidOwnedResult.Value -> result.value
            is AndroidOwnedResult.Unavailable -> throw NativeHistoryUnavailable(result.reason)
        }
    }
    // Fixed, bounded observations of this reader only. The counter never admits work,
    // caches custody or creates another deadline for the shared original operation.
    private val nativeGuardObservations = java.util.concurrent.atomic.AtomicInteger()
    private fun guard() {
        val index = nativeGuardObservations.getAndUpdate { if (it < 4097) it + 1 else it }
        val trace = index < 4 || index == 16 || index == 64 || index == 128 || index == 256 ||
            index == 512 || index == 1024 || index == 2048 || index == 4096
        var stage = "existing-context"
        var started = if (trace) android.os.SystemClock.elapsedRealtime() else 0L
        fun completed(next: String) {
            if (trace) {
                val finished = android.os.SystemClock.elapsedRealtime()
                try { android.util.Log.i("UkkeraNativeOwner", "Native history-read guard: observation=${index + 1}; stage=$stage; durationMillis=${finished - started}") }
                catch (_: Throwable) { /* Diagnostic only; preserve the actual outcome. */ }
                started = finished
            }
            stage = next
        }
        environment.requireExistingOriginalObservation()
        completed("data-custody")
        owner.requireDataNative()
        completed("database-pin")
        known(state == State.READING); namespace.assertOriginalDatabasePin(requireNotNull(databasePin))
        completed("active-work")
        work.requireActive()
        completed("returned")
    }
    private inner class OwnedStatement(val value: SQLiteStatement) {
        private var closeStarted = false; private var closed = false
        fun closeActual() {
            if (closed) return
            if (closeStarted) { work.nativeClosureUnknown(); uncertain() }
            closeStarted = true
            try { value.close(); closed = true; statements.remove(this) }
            catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
    }
    private fun prepare(sql: String, parameters: List<Parameter> = emptyList()): OwnedStatement {
        val statement = OwnedStatement(requireNotNull(connection).prepare(sql)).also { statements += it }
        parameters.forEachIndexed { index,value -> value.bind(statement.value,index+1) }
        return statement
    }
    private fun executeFixed(sql: String) { val statement = prepare(sql); try { known(!statement.value.step()) } finally { statement.closeActual() } }
    private fun scalarLong(sql: String): Long {
        val statement = prepare(sql)
        try { known(statement.value.step() && statement.value.getColumnCount() == 1 && statement.value.getColumnType(0) == 1)
            return statement.value.getLong(0).also { known(!statement.value.step()) } } finally { statement.closeActual() }
    }
    private fun scalarText(sql: String): String {
        val statement = prepare(sql)
        try { known(statement.value.step() && statement.value.getColumnCount() == 1 && statement.value.getColumnType(0) == 3)
            return statement.value.getText(0).also { known(it.length <= 32 && !statement.value.step()) } } finally { statement.closeActual() }
    }
    private fun verifyOnlyOriginalMain() {
        val statement = prepare("PRAGMA database_list")
        try {
            known(statement.value.step() && statement.value.getColumnType(0)==1 && statement.value.getLong(0)==0L &&
                statement.value.getColumnType(1)==3 && statement.value.getText(1)=="main" && statement.value.getColumnType(2)==3 &&
                statement.value.getText(2)==requireNotNull(databasePin).path && !statement.value.step())
        } finally { statement.closeActual() }
        val temporary = prepare("SELECT rowid FROM temp.sqlite_schema LIMIT 1")
        try { known(!temporary.value.step()) } finally { temporary.closeActual() }
    }

    private sealed interface Parameter {
        fun bind(statement: SQLiteStatement,index: Int)
        fun clear() = Unit
        data class Number(val value: Long): Parameter { override fun bind(statement: SQLiteStatement,index: Int) { statement.bindLong(index,value) } }
        data class Text(val value: String): Parameter { override fun bind(statement: SQLiteStatement,index: Int) { statement.bindText(index,value) } }
        class Bytes(value: ByteArray): Parameter {
            private val bytes = value.copyOf()
            override fun bind(statement: SQLiteStatement,index: Int) { statement.bindBlob(index,bytes) }
            override fun clear() { bytes.fill(0) }
        }
    }

    private inner class SchemaQuery(val source: String,val names: List<String>,val limits: List<Int>,
        val parameters: List<Parameter> = emptyList(),val map: (List<SchemaValue>)->NativeHistorySchemaItemV2) {
        fun sql(): String = "SELECT " + names.joinToString(",") { "typeof(${quoted(it)}),length(CAST(${quoted(it)} AS BLOB)),${quoted(it)}" } + " FROM " + source
    }
    private sealed interface SchemaValue {
        data object Null: SchemaValue
        data class Number(val value: Long): SchemaValue
        data class Text(val value: String): SchemaValue
    }
    private fun List<SchemaValue>.number(at: Int): Long = (get(at) as? SchemaValue.Number)?.value ?: uncertain()
    private fun List<SchemaValue>.int(at: Int): Int = number(at).also {
        known(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    }.toInt()
    private fun List<SchemaValue>.text(at: Int): String = (get(at) as? SchemaValue.Text)?.value ?: uncertain()
    private fun List<SchemaValue>.optionalText(at: Int): String? = if (get(at) == SchemaValue.Null) null else text(at)
    private fun enqueueSchema() {
        schemaQueries += SchemaQuery("main.sqlite_schema ORDER BY rowid",listOf("rowid","type","name","tbl_name","rootpage","sql"),
            listOf(0,16,256,256,0,NativeHistoryPhysicalReadProfileV2.MAX_SQL_TEXT_BYTES)) {
            NativeHistorySchemaItemV2.Object(it.number(0),it.text(1),it.text(2),it.text(3),it.number(4),it.optionalText(5)) }
        val tables = NativeHistoryTableV2.entries.map { it.sqlName } + "room_master_table"
        for (table in tables) {
            schemaQueries += SchemaQuery("pragma_table_xinfo(?) ORDER BY cid",listOf("cid","name","type","notnull","dflt_value","pk","hidden"),
                listOf(0,256,64,0,NativeHistoryPhysicalReadProfileV2.MAX_SQL_TEXT_BYTES,0,0),listOf(Parameter.Text(table))) {
                NativeHistorySchemaItemV2.Column(table,it.int(0),it.text(1),it.text(2),boolean(it.number(3)),it.optionalText(4),it.int(5),it.int(6)) }
            schemaQueries += SchemaQuery("pragma_index_list(?) ORDER BY seq",listOf("seq","name","unique","origin","partial"),
                listOf(0,256,0,4,0),listOf(Parameter.Text(table))) { row ->
                val name = row.text(1)
                schemaQueries += SchemaQuery("pragma_index_xinfo(?) ORDER BY seqno",listOf("seqno","cid","name","desc","coll","key"),
                    listOf(0,0,256,0,32,0),listOf(Parameter.Text(name))) {
                    NativeHistorySchemaItemV2.IndexColumn(name,it.int(0),it.int(1),it.optionalText(2),boolean(it.number(3)),it.optionalText(4),boolean(it.number(5))) }
                NativeHistorySchemaItemV2.Index(table,row.int(0),name,boolean(row.number(2)),row.text(3),boolean(row.number(4))) }
            schemaQueries += SchemaQuery("pragma_foreign_key_list(?) ORDER BY id,seq",listOf("id","seq","table","from","to","on_update","on_delete","match"),
                listOf(0,0,256,256,256,32,32,32),listOf(Parameter.Text(table))) {
                NativeHistorySchemaItemV2.ForeignKey(table,it.int(0),it.int(1),it.text(2),it.text(3),it.text(4),it.text(5),it.text(6),it.text(7)) }
        }
        schemaQueries += SchemaQuery("main.room_master_table ORDER BY rowid",listOf("id","identity_hash"),listOf(0,32)) {
            known(it.number(0)==42L)
            NativeHistorySchemaItemV2.Setting("room.identity_hash",it.text(1)) }
        schemaQueries += SchemaQuery("pragma_user_version",listOf("user_version"),listOf(0)) { NativeHistorySchemaItemV2.Setting("user_version",it.number(0).toString()) }
        schemaQueries += SchemaQuery("pragma_application_id",listOf("application_id"),listOf(0)) { NativeHistorySchemaItemV2.Setting("application_id",it.number(0).toString()) }
    }
    private fun readSchemaPageNative(): NativeHistorySchemaPageV2? {
        known(active == null && stack.isEmpty() && !traversalEnded)
        if (schemaEnded) return null
        val items = mutableListOf<NativeHistorySchemaItemV2>()
        while (items.size < NativeHistoryPhysicalReadProfileV2.METADATA_PAGE) {
            var cursor = schemaCursor
            if (cursor == null) {
                if (schemaQueries.isEmpty()) break
                val query = schemaQueries.removeFirst(); cursor = query to prepare(query.sql(),query.parameters); schemaCursor = cursor
            }
            val query = cursor.first; val statement = cursor.second.value
            if (!statement.step()) { cursor.second.closeActual(); query.parameters.forEach { it.clear() }; schemaCursor=null; continue }
            known(++schemaRows <= 16_384 && statement.getColumnCount()==query.names.size*3)
            val values = query.names.indices.map { index ->
                val base = index*3; val type=statement.getText(base)
                when(type) {
                    "null" -> { known(statement.isNull(base+1) && statement.isNull(base+2)); SchemaValue.Null }
                    "integer" -> { known(statement.getColumnType(base+2)==1); SchemaValue.Number(statement.getLong(base+2)) }
                    "text" -> { known(statement.getColumnType(base+1)==1); val size=statement.getLong(base+1)
                        known(size>=0 && size<=query.limits[index] && statement.getColumnType(base+2)==3)
                        val text=statement.getText(base+2); val encoded=text.encodeToByteArray(throwOnInvalidSequence=true)
                        try { known(encoded.size.toLong()==size && '\u0000' !in text) } finally { encoded.fill(0) }; SchemaValue.Text(text) }
                    else -> uncertain()
                }
            }
            items += query.map(values)
        }
        if (items.isEmpty()) { known(schemaCursor==null && schemaQueries.isEmpty()); schemaEnded=true; return null }
        return NativeHistorySchemaPageV2(items)
    }


    private abstract inner class Node {
        abstract fun next(): NativeHistoryRowMetadataV2?
        open fun clear() = Unit
        var ended = false
    }
    private inner class Marker(private val pass: NativeHistoryReadPassV2,private val table: NativeHistoryTableV2?,
        private val kind: NativeHistoryRowKindV2 = NativeHistoryRowKindV2.GROUP_END): Node() {
        override fun next(): NativeHistoryRowMetadataV2? {
            if (ended) return null
            ended=true; return NativeHistoryRowMetadataV2(pass,kind,table,null,emptyList())
        }
    }
    private inner class Stream(
        private val table: NativeHistoryTableV2, private val pass: NativeHistoryReadPassV2,
        private val suffix: String, private val parameters: List<Parameter> = emptyList(),
        private val root: Boolean = false, private val employeeSource: NativeEmployeeEnrollmentHistorySource? = null,
        private val policySource: NativeEmployeePolicyActivationHistorySource? = null,
        private val deviceAdmissionSource: NativeDeviceAdmissionHistorySource? = null,
        private val children: (RowCache)->List<Node> = { emptyList() },
    ): Node() {
        private var statement: OwnedStatement? = null
        private var completedRow: RowCache? = null
        private var count = 0L
        override fun next(): NativeHistoryRowMetadataV2? {
            if (ended) return null
            completedRow?.let { row ->
                completedRow=null
                try {
                    val descendants=mutableListOf<Node>()
                    try {
                        if (table.protectedTable != null) descendants += openedProtectedNode(row,pass,employeeSource,policySource,deviceAdmissionSource)
                        descendants += children(row)
                        for (child in descendants.asReversed()) stack.addLast(child)
                    } catch (failure: Throwable) { descendants.forEach { it.clear() }; throw failure }
                } finally { row.clear() }
                if (stack.lastOrNull() !== this) return null
            }
            val cursor=statement ?: prepare(metadataSql(table,suffix),parameters).also { statement=it }
            if (!cursor.value.step()) {
                cursor.closeActual(); statement=null; parameters.forEach { it.clear() }; ended=true
                if (pass == NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS) checkNotNull(employeeSource).finishOriginalSelectedEvent()
                return NativeHistoryRowMetadataV2(pass,if(root) NativeHistoryRowKindV2.PASS_END else NativeHistoryRowKindV2.GROUP_END,table,null,emptyList())
            }
            known(count < Long.MAX_VALUE); count++
            val metadata=physicalMetadata(cursor.value,table,pass)
            val row=RowCache(table,requireNotNull(metadata.rowId))
            active=CellSource(metadata, row=row) { completedRow=it ?: uncertain() }
            return metadata
        }
        override fun clear() { completedRow?.clear(); completedRow=null; parameters.forEach { it.clear() } }
    }
    private inner class ImageNode(private val row: RowCache,
        private val pass: NativeHistoryReadPassV2 = NativeHistoryReadPassV2.LINEAGE_IMAGE,
        private val employeeSource: NativeEmployeeEnrollmentHistorySource? = null,
        private val policySource: NativeEmployeePolicyActivationHistorySource? = null,
        private val deviceAdmissionSource: NativeDeviceAdmissionHistorySource? = null): Node() {
        private var announced=false
        override fun next(): NativeHistoryRowMetadataV2? {
            if (ended) return null
            if (announced) { ended=true; row.clear(); return null }
            announced=true
            val table=NativeHistoryTableV2.fromTag(row.number("tableTag").toInt())
            val rowId=row.number("afterRowId"); val length=row.number("imageBytes")
            known(length>0 && length<=NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES)
            val metadata=NativeHistoryRowMetadataV2(pass,NativeHistoryRowKindV2.IMAGE,table,rowId,
                listOf(NativeHistoryCellMetadataV2("image",NativeHistorySqliteTypeV2.BLOB,length)))
            val collected=if(table.protectedTable!=null) {
                known(length<=requireNotNull(table.protectedTable).maximumPlaintextBytes.toLong()+8192)
                ByteArray(length.toInt())
            } else null
            active=CellSource(metadata,image=ImageSlice(row.number("sequence"),row.number("imageOffset"),length),
                collectedImage=collected) {
                if (collected!=null) {
                    try {
                        val physical=NativeHistoryPhysicalRowWireV2.decode(collected)
                        try {
                            known(physical.table==table && physical.rowId==rowId)
                            val cache=RowCache.fromPhysical(physical)
                            try { stack.addLast(openedProtectedNode(cache,pass,employeeSource,policySource,deviceAdmissionSource)) }
                            finally { cache.clear() }
                        } finally { physical.clear() }
                    } finally { collected.fill(0) }
                }
            }
            return metadata
        }
        override fun clear() { row.clear() }
    }
    /** Enumerates only original-root/target/terminal sources privately selected by the same
     * native registry before open. It is not a caller WHERE/key/pass or current-account seed. */
    private inner class EmployeeSeedRoot(private val selection: NativeEmployeeEnrollmentHistorySelection): Node() {
        private var at = 0
        override fun next(): NativeHistoryRowMetadataV2? {
            if (ended) return null
            if (at == selection.sources.size) {
                selection.finishActualPrepass(); ended = true
                return NativeHistoryRowMetadataV2(NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS,
                    NativeHistoryRowKindV2.PASS_END,NativeHistoryTableV2.EVENT,null,emptyList())
            }
            val source = selection.sources[at++]
            stack.addLast(Stream(NativeHistoryTableV2.EVENT,NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENTS,
                "WHERE sequence=?",listOf(Parameter.Number(source.child.historySequence)),employeeSource=source) { event ->
                source.observeOriginalEvent(event.number("sequence"),PayloadDigest(event.text("eventDigest")))
                listOf(Stream(NativeHistoryTableV2.ROW_VERSION,NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_MUTATIONS,
                    "WHERE sequence=? ORDER BY ordinal",listOf(Parameter.Number(source.child.historySequence)),employeeSource=source) { version ->
                    val actual = version.physical()
                    try { source.observeOriginalMutation(actual) } finally { actual.clear() }
                    val table = NativeHistoryTableV2.fromTag(version.number("tableTag").toInt())
                    when (version.number("afterKind")) {
                        1L -> listOf(ImageNode(version.copy(),NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE,source))
                        2L -> listOf(Stream(table,NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE,
                            "WHERE rowid=?",listOf(Parameter.Number(version.number("afterRowId"))),employeeSource=source))
                        else -> uncertain()
                    }
                }, Stream(NativeHistoryTableV2.CHUNK,NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_CHUNKS,
                    "WHERE sequence=? ORDER BY ordinal",listOf(Parameter.Number(source.child.historySequence)),employeeSource=source))
            })
            return null
        }
    }

    private inner class PlaintextNode(private val pass: NativeHistoryReadPassV2,private val table: NativeHistoryTableV2,
        private val rowId: Long,private val binding: ProtectedLocalBinding,private val plaintext: ByteArray): Node() {
        private var announced=false
        override fun next(): NativeHistoryRowMetadataV2? {
            if(ended) return null
            if(announced) { ended=true; plaintext.fill(0); return null }
            announced=true
            val metadata=NativeHistoryRowMetadataV2(pass,NativeHistoryRowKindV2.PLAINTEXT,table,rowId,
                listOf(NativeHistoryCellMetadataV2("plaintext",NativeHistorySqliteTypeV2.BLOB,plaintext.size.toLong())),binding)
            active=CellSource(metadata,literal=plaintext) { }
            return metadata
        }
        override fun clear() { plaintext.fill(0) }
    }

    private fun enqueueTraversal() {
        // First: independent exhaustive physical census. Later WHERE/join lookups cannot hide
        // malformed/orphan rows: all original rows were already observed without validity filters.
        for(table in NativeHistoryTableV2.entries) roots += Stream(table,NativeHistoryReadPassV2.CENSUS,"ORDER BY rowid",root=true)
        // Seed the separate employee reducer from the actual ORIGINAL enrollment images
        // before EVENTS. The exhaustive census/events/lineages/relations remain unchanged.
        employeeSelection?.let { roots += EmployeeSeedRoot(it) }
        roots += Stream(NativeHistoryTableV2.EVENT,NativeHistoryReadPassV2.EVENTS,"ORDER BY sequence",root=true) { event ->
            val sequence=event.number("sequence")
            val policySource=policySelection?.sources?.singleOrNull { it.child.historySequence == sequence }
            policySource?.observeOriginalEvent(sequence,PayloadDigest(event.text("eventDigest")))
            val admissionSource=deviceAdmissionSelection?.sources?.singleOrNull { it.child.historySequence == sequence }
            admissionSource?.observeOriginalEvent(sequence,PayloadDigest(event.text("eventDigest")))
            businessPublicationSelection?.sources?.singleOrNull { it.child.historySequence == sequence }?.observeOriginalEvent(
                sequence,PayloadDigest(event.text("eventDigest")),DomainId(event.text("transactionId")),
                event.number("parentSequence"),PayloadDigest(event.text("parentDigest")))
            listOf(Stream(NativeHistoryTableV2.ROW_VERSION,NativeHistoryReadPassV2.EVENT_MUTATIONS,
                "WHERE sequence=? ORDER BY ordinal",listOf(Parameter.Number(sequence))) { version ->
                if (policySource != null) {
                    val actual=version.physical()
                    try { policySource.observeOriginalMutation(actual) } finally { actual.clear() }
                }
                if (admissionSource != null) {
                    val actual=version.physical()
                    try { admissionSource.observeOriginalMutation(actual) } finally { actual.clear() }
                }
                // Every mutable ACCOUNT AFTER and every actual initial protected INSERT
                // is opened at its chronological cut. The native version selects the exact
                // signed image slice or retained immutable row; no caller/current selector.
                val table=NativeHistoryTableV2.fromTag(version.number("tableTag").toInt())
                val protectedSeed=table.protectedTable!=null && version.number("version")==1L &&
                    version.number("mutationKind")==1L
                if ((table==NativeHistoryTableV2.ACCOUNT && version.number("afterKind")==1L) || protectedSeed) {
                    when (version.number("afterKind")) {
                        1L -> listOf(ImageNode(version.copy(),NativeHistoryReadPassV2.EVENT_MUTATIONS))
                        2L -> {
                            known(protectedSeed && table.immutable)
                            listOf(Stream(table,NativeHistoryReadPassV2.EVENT_MUTATIONS,
                                "WHERE rowid=?",listOf(Parameter.Number(version.number("afterRowId")))))
                        }
                        else -> uncertain()
                    }
                } else emptyList()
            },
                Stream(NativeHistoryTableV2.CHUNK,NativeHistoryReadPassV2.EVENT_CHUNKS,"WHERE sequence=? ORDER BY ordinal",listOf(Parameter.Number(sequence))))
        }
        roots += Stream(NativeHistoryTableV2.ROW_VERSION,NativeHistoryReadPassV2.LINEAGE,"ORDER BY tableTag,`key`,version",root=true) { version ->
            val table=NativeHistoryTableV2.fromTag(version.number("tableTag").toInt())
            known(table.application)
            val policySource=policySelection?.sources?.singleOrNull { it.child.historySequence == version.number("sequence") }
            if (policySource != null) {
                val actual=version.physical()
                try { policySource.observeOriginalLineageMutation(actual) } finally { actual.clear() }
            }
            val admissionSource=deviceAdmissionSelection?.sources?.singleOrNull { it.child.historySequence == version.number("sequence") }
            if (admissionSource != null) {
                val actual=version.physical()
                try { admissionSource.observeOriginalLineageMutation(actual) } finally { actual.clear() }
            }
            val nodes=mutableListOf<Node>(Stream(NativeHistoryTableV2.EVENT,NativeHistoryReadPassV2.LINEAGE_EVENT,
                "WHERE sequence=?",listOf(Parameter.Number(version.number("sequence")))))
            try {
                // Native-selected authentication lineages receive their own exact event read.
                // The consumer joins every plaintext image to the signed semantic decision,
                // original seed/event-order replay, mutation key and current tip. No SQL,
                // caller-selected dependency/key or short installation prefix is accepted.
                if (table.hasAuthenticationImages) nodes += Stream(NativeHistoryTableV2.CHUNK,
                    NativeHistoryReadPassV2.LINEAGE_EVENT_CHUNKS, "WHERE sequence=? AND ordinal>=? ORDER BY ordinal",
                    listOf(Parameter.Number(version.number("sequence")), Parameter.Number(firstCapsuleChunk(version.number("sequence")))))
                when(version.number("afterKind")) {
                    1L -> nodes += ImageNode(version.copy(),policySource=policySource,deviceAdmissionSource=admissionSource)
                    2L -> nodes += Stream(table,NativeHistoryReadPassV2.LINEAGE_IMAGE,"WHERE rowid=?",listOf(Parameter.Number(version.number("afterRowId"))),policySource=policySource,deviceAdmissionSource=admissionSource)
                    3L -> Unit
                    else -> uncertain()
                }
                if(!hasLaterVersion(version)) {
                    val key=version.bytes("key")
                    try {
                        nodes += Stream(NativeHistoryTableV2.ROW_TIP,NativeHistoryReadPassV2.LINEAGE_TIP,"WHERE tableTag=? AND `key`=?",
                            listOf(Parameter.Number(table.tag.toLong()),Parameter.Bytes(key))) { tip ->
                            if(tip.number("tombstone")==1L) emptyList() else
                                listOf(Stream(table,NativeHistoryReadPassV2.LINEAGE_CURRENT,"WHERE rowid=?",listOf(Parameter.Number(tip.number("targetRowId")))))
                        }
                    } finally { key.fill(0) }
                    nodes += Marker(NativeHistoryReadPassV2.LINEAGE,table)
                }
                nodes
            } catch (failure: Throwable) { nodes.forEach { it.clear() }; throw failure }
        }
        roots += Stream(NativeHistoryTableV2.JOURNAL,NativeHistoryReadPassV2.COMMANDS,"ORDER BY scope,epoch,sequence",root=true) { journal ->
            val scope=journal.text("scope"); val operation=journal.text("operationId")
            fun parameters()=listOf(Parameter.Text(scope),Parameter.Text(operation))
            listOf(Stream(NativeHistoryTableV2.OPERATION,NativeHistoryReadPassV2.COMMAND_OPERATION,"WHERE scope=? AND operationId=?",parameters()),
                Stream(NativeHistoryTableV2.AUDIT,NativeHistoryReadPassV2.COMMAND_AUDIT,"WHERE scope=? AND operationId=? ORDER BY rowid",parameters()),
                Stream(NativeHistoryTableV2.OUTBOX,NativeHistoryReadPassV2.COMMAND_OUTBOX,"WHERE scope=? AND operationId=? ORDER BY ordinal",parameters()),
                Stream(NativeHistoryTableV2.JOURNAL_FRAGMENT,NativeHistoryReadPassV2.COMMAND_FRAGMENT,"WHERE scope=? AND operationId=? ORDER BY ordinal",parameters()))
        }
        roots += Stream(NativeHistoryTableV2.AGGREGATE,NativeHistoryReadPassV2.STOCK_AGGREGATES,"ORDER BY scope,kind,id",root=true) { aggregate ->
            val table=stockProjectionTable(aggregate.text("kind"))
            if(table==null) emptyList() else listOf(Stream(table,NativeHistoryReadPassV2.STOCK_PROJECTION,
                "WHERE scope=? AND recordKind=? AND recordId=?",listOf(Parameter.Text(aggregate.text("scope")),Parameter.Text(aggregate.text("kind")),Parameter.Text(aggregate.text("id")))))
        }
        for(table in STOCK_TABLES) roots += Stream(table,NativeHistoryReadPassV2.STOCK_REVERSE,"ORDER BY scope,recordKind,recordId",root=true) { index ->
            listOf(Stream(NativeHistoryTableV2.AGGREGATE,NativeHistoryReadPassV2.STOCK_REVERSE_AGGREGATE,"WHERE scope=? AND kind=? AND id=?",
                listOf(Parameter.Text(index.text("scope")),Parameter.Text(index.text("recordKind")),Parameter.Text(index.text("recordId")))))
        }
        // These relation passes never accept caller-selected keys or decryptor callbacks.
        // Actor/device references reside INSIDE protected ciphertext. Fixed exhaustive scoped
        // candidates are therefore emitted and decoded by DB, not filtered by an untrusted
        // supplied actor. This costs reads, not unbounded maps; scanner charges all traversal.
        roots += Stream(NativeHistoryTableV2.ACCOUNT,NativeHistoryReadPassV2.ACCOUNT_RELATIONS,"ORDER BY scope,actorId",root=true) { account ->
            fun actor()=listOf(Parameter.Text(account.text("scope")),Parameter.Text(account.text("actorId")))
            listOf(Stream(NativeHistoryTableV2.POLICY,NativeHistoryReadPassV2.ACCOUNT_POLICY,"WHERE scope=? AND actorId=?",actor()),
                Stream(NativeHistoryTableV2.CONFIGURATION,NativeHistoryReadPassV2.ACCOUNT_CONFIGURATION,"WHERE scope=? AND actorId=?",actor()),
                Stream(NativeHistoryTableV2.THROTTLE,NativeHistoryReadPassV2.ACCOUNT_THROTTLES,"WHERE scope=? AND actorId=? ORDER BY dimensionKey",actor()),
                Marker(NativeHistoryReadPassV2.ACCOUNT_RELATIONS,NativeHistoryTableV2.ACCOUNT))
        }
        roots += Stream(NativeHistoryTableV2.ACCESS,NativeHistoryReadPassV2.ACCESS_RELATIONS,"ORDER BY scope,sessionId",root=true) { access ->
            fun session()=listOf(Parameter.Text(access.text("scope")),Parameter.Text(access.text("sessionId")))
            fun scope()=listOf(Parameter.Text(access.text("scope")))
            listOf(Stream(NativeHistoryTableV2.REFRESH,NativeHistoryReadPassV2.ACCESS_REFRESH,"WHERE scope=? AND sessionId=?",session()),
                Stream(NativeHistoryTableV2.ARCHIVE,NativeHistoryReadPassV2.ACCESS_ARCHIVE,"WHERE scope=? AND sessionId=?",session()),
                Stream(NativeHistoryTableV2.ACCOUNT,NativeHistoryReadPassV2.ACCESS_ACCOUNTS,"WHERE scope=? ORDER BY actorId",scope()),
                Stream(NativeHistoryTableV2.ADMISSION,NativeHistoryReadPassV2.ACCESS_ADMISSIONS,"WHERE scope=? ORDER BY deviceId",scope()),
                Stream(NativeHistoryTableV2.CONFIGURATION,NativeHistoryReadPassV2.ACCESS_CONFIGURATIONS,"WHERE scope=? ORDER BY actorId",scope()),
                Marker(NativeHistoryReadPassV2.ACCESS_RELATIONS,NativeHistoryTableV2.ACCESS))
        }
        roots += Stream(NativeHistoryTableV2.ARCHIVE,NativeHistoryReadPassV2.ARCHIVE_RELATIONS,"ORDER BY scope,sessionId",root=true) { archive ->
            listOf(Stream(NativeHistoryTableV2.ACCESS,NativeHistoryReadPassV2.ARCHIVE_ACCESS,"WHERE scope=? AND sessionId=?",
                listOf(Parameter.Text(archive.text("scope")),Parameter.Text(archive.text("sessionId")))),
                Marker(NativeHistoryReadPassV2.ARCHIVE_RELATIONS,NativeHistoryTableV2.ARCHIVE))
        }
        roots += Stream(NativeHistoryTableV2.APPROVAL,NativeHistoryReadPassV2.APPROVAL_RELATIONS,"ORDER BY scope,approvalId",root=true) { approval ->
            listOf(Stream(NativeHistoryTableV2.OPERATION,NativeHistoryReadPassV2.APPROVAL_OPERATIONS,"WHERE scope=? ORDER BY operationId",
                listOf(Parameter.Text(approval.text("scope")))),
                Marker(NativeHistoryReadPassV2.APPROVAL_RELATIONS,NativeHistoryTableV2.APPROVAL))
        }
    }
    private fun hasLaterVersion(row: RowCache): Boolean {
        val key=row.bytes("key")
        val parameters=try { listOf(Parameter.Number(row.number("tableTag")),Parameter.Bytes(key),Parameter.Number(row.number("version"))) }
            finally { key.fill(0) }
        try {
            val statement=prepare("SELECT 1 FROM main.runtime_history_row_version_v2 WHERE tableTag=? AND `key`=? AND version>? ORDER BY version LIMIT 1",parameters)
            return try { statement.value.step() } finally { statement.closeActual() }
        } finally { parameters.forEach { it.clear() } }
    }
    private fun stockProjectionTable(kind: String): NativeHistoryTableV2? = when(kind) {
        "local.stock.v2.position" -> NativeHistoryTableV2.STOCK_POSITION
        "local.stock.v2.allocation","local.stock.v2.hold" -> NativeHistoryTableV2.STOCK_ALLOCATION
        "local.stock.v2.reservation" -> NativeHistoryTableV2.STOCK_RESERVATION
        "local.stock.v2.value-event","local.stock.v2.value-slice","local.stock.v2.value-target","local.stock.v2.transfer-edge","local.stock.v2.issue-slice" -> NativeHistoryTableV2.STOCK_VALUE
        else -> null
    }
    /**
     * Original event traversal has already emitted ALL mutation rows and payload chunks.
     * Their contiguous image offsets are independently checked by the event commitment.
     * Derive this subsequent relation's boundary from those same native rows, never a caller
     * offset. The consumer independently derives it from the signed header and verifies every
     * capsule digest/ordinal. Avoid rereading all 256-session account images for EACH archive.
     */
    private fun firstCapsuleChunk(sequence: Long): Long {
        val cursor = prepare("SELECT MAX(imageOffset+imageBytes) FROM main.runtime_history_row_version_v2 WHERE sequence=? AND afterKind=1",
            listOf(Parameter.Number(sequence)))
        try {
            val statement = cursor.value
            known(statement.step() && statement.getColumnCount() == 1)
            val imageEnd = if (statement.isNull(0)) 0L else {
                known(statement.getColumnType(0) == 1)
                statement.getLong(0)
            }
            known(imageEnd in 0L..NativeHistoryPhysicalReadProfileV2.MAX_EVENT_BYTES && !statement.step())
            return imageEnd / NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES
        } finally { cursor.closeActual() }
    }

    private fun nextRowNative(): NativeHistoryRowMetadataV2? {
        known(schemaEnded && active==null)
        if(traversalEnded) return null
        while(true) {
            if(stack.isEmpty()) {
                if(roots.isEmpty()) {
                    traversalEnded=true
                    work.roomCoroutines.traceStage("history-read-traversal-ended")
                    return null
                }
                stack.addLast(roots.removeFirst())
            }
            val node=stack.last()
            val row=node.next()
            if(row!=null) return row
            if(stack.lastOrNull()===node && node.ended) { stack.removeLast(); node.clear() }
        }
    }
    private fun metadataSql(table: NativeHistoryTableV2,suffix: String): String =
        "SELECT rowid," + NativeHistoryPhysicalReadProfileV2.columns(table).joinToString(",") {
            val name=quoted(it.name)
            "typeof($name),length(CAST($name AS BLOB)),CASE WHEN typeof($name)='integer' THEN $name ELSE NULL END"
        } + " FROM main.${quoted(table.sqlName)} $suffix"
    private fun physicalMetadata(statement: SQLiteStatement,table: NativeHistoryTableV2,pass: NativeHistoryReadPassV2): NativeHistoryRowMetadataV2 {
        val columns=NativeHistoryPhysicalReadProfileV2.columns(table)
        known(statement.getColumnCount()==1+columns.size*3 && statement.getColumnType(0)==1)
        val rowId=statement.getLong(0) // Signed64, including zero and negative: no initial sentinel.
        var total=0L
        val cells=columns.mapIndexed { index,column ->
            val at=1+index*3
            known(statement.getColumnType(at)==3)
            val type=when(statement.getText(at)) {
                "null" -> NativeHistorySqliteTypeV2.NULL; "integer" -> NativeHistorySqliteTypeV2.INTEGER
                "real" -> NativeHistorySqliteTypeV2.REAL; "text" -> NativeHistorySqliteTypeV2.TEXT
                "blob" -> NativeHistorySqliteTypeV2.BLOB; else -> uncertain()
            }
            if(type==NativeHistorySqliteTypeV2.NULL) { known(column.nullable && statement.isNull(at+1)); NativeHistoryCellMetadataV2(column.name,type,0) }
            else {
                known(type==column.type)
                if(type==NativeHistorySqliteTypeV2.INTEGER) {
                    known(statement.getColumnType(at+2)==1)
                    val value=statement.getLong(at+2); known(value in column.minimum..column.maximum)
                    NativeHistoryCellMetadataV2(column.name,type,0,value)
                } else {
                    known(statement.getColumnType(at+1)==1); val size=statement.getLong(at+1)
                    known(size in column.minimum..column.maximum && size<=NativeHistoryPhysicalReadProfileV2.MAX_CELL_BYTES)
                    known(size<=NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES-total); total+=size
                    NativeHistoryCellMetadataV2(column.name,type,size)
                }
            }
        }
        return NativeHistoryRowMetadataV2(pass,NativeHistoryRowKindV2.PHYSICAL,table,rowId,cells)
    }

    private data class ImageSlice(val sequence: Long,val offset: Long,val length: Long)
    private inner class CellSource(val metadata: NativeHistoryRowMetadataV2,private val row: RowCache? = null,
        private val image: ImageSlice? = null,private val literal: ByteArray? = null,private val collectedImage: ByteArray? = null,
        private val completed: (RowCache?)->Unit) {
        var column=0; var offset=0L
        private var collected: ByteArray? = null
        private var complete=false
        fun nextChunk(): NativeHistoryCellChunkV2 {
            known(!complete && column in metadata.cells.indices)
            val cell=metadata.cells[column]; val at=offset
            val remaining=cell.byteCount-at; known(remaining>=0)
            val amount=minOf(remaining,NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES.toLong()).toInt()
            val bytes=when {
                cell.type==NativeHistorySqliteTypeV2.INTEGER || cell.type==NativeHistorySqliteTypeV2.NULL -> ByteArray(0)
                literal!=null -> literal.copyOfRange(at.toInt(),at.toInt()+amount)
                image!=null -> readImageSlice(image,at,amount)
                else -> readPhysicalSlice(metadata,cell,at,amount)
            }
            try {
                known(bytes.size==amount)
                val last=at+amount==cell.byteCount
                if(row!=null) {
                    if(cell.type==NativeHistorySqliteTypeV2.INTEGER) row.values[cell.name]=StoredValue.Number(requireNotNull(cell.integerValue))
                    else if(cell.type==NativeHistorySqliteTypeV2.NULL) row.values[cell.name]=StoredValue.Null
                    else if(metadata.table?.protectedTable!=null || cell.byteCount<=65_536) {
                        if(at==0L) collected=ByteArray(cell.byteCount.toInt())
                        bytes.copyInto(requireNotNull(collected),at.toInt())
                        if(last) { row.values[cell.name]=StoredValue.Bytes(requireNotNull(collected),cell.type); collected=null }
                    }
                }
                if(collectedImage!=null) bytes.copyInto(collectedImage,at.toInt())
                val output=NativeHistoryCellChunkV2(column,at,cell.byteCount,last,cell.integerValue,bytes)
                try {
                    offset+=amount
                    if(last) {
                        column++; offset=0
                        if(column==metadata.cells.size) { complete=true; active=null; completed(row) }
                    }
                    return output
                } catch (failure: Throwable) { output.clear(); throw failure }
            } finally { bytes.fill(0) }
        }
        fun clear() { collected?.fill(0); collected=null; collectedImage?.fill(0); row?.clear() }
    }
    private fun readCellNative(): NativeHistoryCellChunkV2 = (active ?: uncertain()).nextChunk()
    private fun readPhysicalSlice(metadata: NativeHistoryRowMetadataV2,cell: NativeHistoryCellMetadataV2,offset: Long,amount: Int): ByteArray {
        val table=requireNotNull(metadata.table); val name=quoted(cell.name)
        val statement=prepare("SELECT typeof($name),length(CAST($name AS BLOB)),substr(CAST($name AS BLOB),?,?) FROM main.${quoted(table.sqlName)} WHERE rowid=?",
            listOf(Parameter.Number(offset+1),Parameter.Number(amount.toLong()),Parameter.Number(requireNotNull(metadata.rowId))))
        var produced: ByteArray? = null
        var closeAttempted = false
        try {
            known(statement.value.step() && statement.value.getColumnType(0)==3 && statement.value.getColumnType(1)==1)
            val expected=if(cell.type==NativeHistorySqliteTypeV2.TEXT) "text" else "blob"
            known(statement.value.getText(0)==expected && statement.value.getLong(1)==cell.byteCount && statement.value.getColumnType(2)==4)
            val bytes=statement.value.getBlob(2); produced=bytes
            known(bytes.size==amount && !statement.value.step())
            closeAttempted=true; statement.closeActual()
            produced=null; return bytes
        } finally {
            produced?.fill(0)
            if(!closeAttempted) statement.closeActual() // Never retry an uncertain close.
        }
    }
    private fun readImageSlice(image: ImageSlice,offset: Long,amount: Int): ByteArray {
        known(offset>=0 && offset<=image.length && amount.toLong()<=image.length-offset && image.offset>=0 &&
            offset<=NativeHistoryPhysicalReadProfileV2.MAX_EVENT_BYTES-image.offset)
        val result=ByteArray(amount); var written=0
        try {
            while(written<amount) {
                val absolute=image.offset+offset+written
                val ordinal=absolute/NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES
                val inside=(absolute%NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES).toInt()
                val count=minOf(amount-written,NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES-inside)
                val statement=prepare("SELECT typeof(bytes),length(bytes),substr(bytes,?,?) FROM main.runtime_history_chunk_v2 WHERE sequence=? AND ordinal=?",
                    listOf(Parameter.Number(inside.toLong()+1),Parameter.Number(count.toLong()),Parameter.Number(image.sequence),Parameter.Number(ordinal)))
                try {
                    known(statement.value.step() && statement.value.getText(0)=="blob" && statement.value.getColumnType(1)==1)
                    val length=statement.value.getLong(1)
                    known(length in 1..NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES.toLong() && inside+count<=length && statement.value.getColumnType(2)==4)
                    val bytes=statement.value.getBlob(2)
                    try { known(bytes.size==count && !statement.value.step()); bytes.copyInto(result,written) } finally { bytes.fill(0) }
                } finally { statement.closeActual() }
                written+=count
            }
            return result
        } catch(failure: Throwable) { result.fill(0); throw failure }
    }

    private sealed interface StoredValue {
        data object Null: StoredValue
        data class Number(val value: Long): StoredValue
        class Bytes(val value: ByteArray,val type: NativeHistorySqliteTypeV2): StoredValue
    }
    private class RowCache(val table: NativeHistoryTableV2,val rowId: Long) {
        val values=linkedMapOf<String,StoredValue>()
        fun number(name: String): Long = (values[name] as? StoredValue.Number)?.value ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        fun bytes(name: String): ByteArray = (values[name] as? StoredValue.Bytes)?.value?.copyOf() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        fun text(name: String): String {
            val bytes=values[name] as? StoredValue.Bytes ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            check(bytes.type==NativeHistorySqliteTypeV2.TEXT)
            return bytes.value.decodeToString(throwOnInvalidSequence=true).also { check('\u0000' !in it) }
        }
        fun physical(): NativeHistoryPhysicalRowV2 {
            val cells = mutableListOf<NativeHistoryPhysicalCellV2>()
            return try {
                for (column in NativeHistoryPhysicalReadProfileV2.columns(table)) {
                    cells += when (val value = values[column.name] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)) {
                        StoredValue.Null -> NativeHistoryPhysicalCellV2.Null
                        is StoredValue.Number -> NativeHistoryPhysicalCellV2.Integer(value.value)
                        is StoredValue.Bytes -> when (value.type) {
                            NativeHistorySqliteTypeV2.TEXT -> NativeHistoryPhysicalCellV2.Text(value.value)
                            NativeHistorySqliteTypeV2.BLOB -> NativeHistoryPhysicalCellV2.Blob(value.value)
                            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                        }
                    }
                }
                NativeHistoryPhysicalRowV2(table,rowId,cells).also { cells.clear() }
            } finally { cells.forEach { cell -> when (cell) {
                is NativeHistoryPhysicalCellV2.Text -> cell.clear()
                is NativeHistoryPhysicalCellV2.Blob -> cell.clear()
                else -> Unit
            } } }
        }
        fun copy(): RowCache {
            val next=RowCache(table,rowId)
            return try {
                values.forEach { (name,value) ->
                    next.values[name]=if(value is StoredValue.Bytes) StoredValue.Bytes(value.value.copyOf(),value.type) else value }
                next
            } catch (failure: Throwable) { next.clear(); throw failure }
        }
        fun clear() { values.values.forEach { if(it is StoredValue.Bytes) it.value.fill(0) }; values.clear() }
        companion object {
            fun fromPhysical(row: NativeHistoryPhysicalRowV2): RowCache {
                val cache=RowCache(row.table,row.rowId)
                return try {
                    NativeHistoryPhysicalReadProfileV2.columns(row.table).zip(row.cells).forEach { (column,cell) ->
                        cache.values[column.name]=when(cell) {
                            NativeHistoryPhysicalCellV2.Null -> StoredValue.Null
                            is NativeHistoryPhysicalCellV2.Integer -> StoredValue.Number(cell.value)
                            is NativeHistoryPhysicalCellV2.Text -> StoredValue.Bytes(cell.bytes(),NativeHistorySqliteTypeV2.TEXT)
                            is NativeHistoryPhysicalCellV2.Blob -> StoredValue.Bytes(cell.bytes(),NativeHistorySqliteTypeV2.BLOB)
                        }
                    }
                    cache
                } catch (failure: Throwable) { cache.clear(); throw failure }
            }
        }
    }
    private fun openedProtectedNode(row: RowCache,pass: NativeHistoryReadPassV2,
        employeeSource: NativeEmployeeEnrollmentHistorySource? = null,
        policySource: NativeEmployeePolicyActivationHistorySource? = null,
        deviceAdmissionSource: NativeDeviceAdmissionHistorySource? = null): Node {
        val configuration=material.configuration()
        val scopeParts=row.text("scope").split('/'); known(scopeParts.size==3)
        val scope=BranchScope(DomainId(scopeParts[0]),DomainId(scopeParts[1]),DomainId(scopeParts[2]))
        known(scope==configuration.scope && row.number("schemaVersion")==1L && row.number("formatVersion")==1L &&
            row.text("algorithm")==ProtectedLocalStorageFormat.ALGORITHM && DomainId(row.text("keyId"))==configuration.storageReference.keyId)
        fun id(name: String)=DomainId(row.text(name))
        val key=when(row.table) {
            NativeHistoryTableV2.ACCOUNT -> ProtectedLocalRecordKey.Account(scope,id("actorId"))
            NativeHistoryTableV2.POLICY -> ProtectedLocalRecordKey.EffectivePolicy(scope,id("actorId"))
            NativeHistoryTableV2.ADMISSION -> ProtectedLocalRecordKey.Admission(scope,id("deviceId"))
            NativeHistoryTableV2.THROTTLE -> {
                val dimension=row.text("dimensionKey")
                val selector=if(dimension=="account") ThrottleDimension.Account else {
                    known(dimension.startsWith("device:") && dimension.length==43); ThrottleDimension.Device(DomainId(dimension.substring(7))) }
                ProtectedLocalRecordKey.Throttle(ThrottleKey(scope,id("actorId"),selector))
            }
            NativeHistoryTableV2.ACCESS -> ProtectedLocalRecordKey.SessionIssuance(scope,id("sessionId"))
            NativeHistoryTableV2.CONFIGURATION -> ProtectedLocalRecordKey.AuthConfiguration(scope,id("actorId"))
            NativeHistoryTableV2.REFRESH -> ProtectedLocalRecordKey.RefreshIssuance(scope,id("sessionId"))
            NativeHistoryTableV2.AUTH_EVENT -> ProtectedLocalRecordKey.AuthEvent(scope,id("eventId"))
            NativeHistoryTableV2.ARCHIVE -> ProtectedLocalRecordKey.SessionArchive(scope,id("sessionId"))
            NativeHistoryTableV2.APPROVAL -> ProtectedLocalRecordKey.ActionApproval(scope,id("approvalId"))
            else -> uncertain()
        }
        val binding=ProtectedLocalBinding(key,Revision(row.number("revision")))
        val aad=binding.aad(configuration.storageReference)
        var nonce: ByteArray?=null; var encrypted: ByteArray?=null; var plaintext: ByteArray?=null
        try {
            val actualNonce=row.bytes("nonce"); nonce=actualNonce
            val actualEncrypted=row.bytes("ciphertextAndTag"); encrypted=actualEncrypted
            known(actualNonce.size==ProtectedLocalStorageFormat.NONCE_BYTES && actualEncrypted.size in 17..binding.table.maximumPlaintextBytes+16)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,material.storageKey(),GCMParameterSpec(128,actualNonce)); cipher.updateAAD(aad)
            val opened=cipher.doFinal(actualEncrypted); plaintext=opened; binding.requirePlaintextSize(opened.size)
            material.assertOriginalDataKeys(); guard()
            if (employeeSource != null) {
                known(pass == NativeHistoryReadPassV2.EMPLOYEE_ENROLLMENT_IMAGE)
                val actual = row.physical()
                try { employeeSource.observeOriginalProtectedSeed(actual,binding,opened) } finally { actual.clear() }
            }
            if (policySource != null) {
                known(pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && employeeSource == null)
                val actual=row.physical()
                try { policySource.observeOriginalProtectedImage(actual,binding,opened) } finally { actual.clear() }
            }
            if (deviceAdmissionSource != null) {
                known(pass == NativeHistoryReadPassV2.LINEAGE_IMAGE && employeeSource == null && policySource == null)
                val actual=row.physical()
                try { deviceAdmissionSource.observeOriginalProtectedImage(actual,binding,opened) } finally { actual.clear() }
            }
            return PlaintextNode(pass,row.table,row.rowId,binding,opened).also { plaintext=null }
        } finally { aad.fill(0); nonce?.fill(0); encrypted?.fill(0); plaintext?.fill(0) }
    }
    private fun boolean(value: Long): Boolean { known(value in 0..1); return value==1L }
    private fun quoted(name: String): String = "`" + name.replace("`","``") + "`"
    private fun known(value: Boolean) { if(!value) uncertain() }
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    companion object {
        private val STOCK_TABLES=listOf(NativeHistoryTableV2.STOCK_POSITION,NativeHistoryTableV2.STOCK_ALLOCATION,
            NativeHistoryTableV2.STOCK_RESERVATION,NativeHistoryTableV2.STOCK_VALUE)
    }
}
