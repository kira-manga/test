package com.ukkera.pos.security

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock


/** Actual native driver, never created from a supplied path/driver or verifier. Room receives
 * this exact instance from P2. The native statement RETURN, not an exception class, records
 * COMMIT/ROLLBACK. Unknown close/fate retains P2 process exclusion. */
public class AndroidSelfOwnedRoomDriver internal constructor(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val work: AndroidOwnedInstallationWork,
    private val namespace: SelfOwnedOriginalDatabaseNamespace,
    private val anchor: AndroidSelfOwnedHistoryAnchor,
    public val databasePath: String,
) : SQLiteDriver, SelfOwnedNativeRoomCoroutineDriver {
    private val coroutines = work.roomCoroutines
    override public val ownedRoomCoroutines: SelfOwnedNativeRoomCoroutineBinding =
        SelfOwnedNativeRoomCoroutineBinding(this, object : SelfOwnedNativeRoomCoroutineController {
            override fun queryContext(): kotlin.coroutines.CoroutineContext = coroutines.queryContext()
            override fun afterActualBuilder() = coroutines.afterActualBuilder()
        })
    override public val hasConnectionPool: Boolean get() = false
    private var connection: OwnedConnection? = null
    private var ready = false
    private var closed = false
    private var frame: SelfOwnedNativeRoomFrame? = null
    private var frameInvocation: Any? = null

    override public fun open(fileName: String): SQLiteConnection {
        coroutines.traceStage("driver-open-before-actor")
        val actor = coroutines.sqlActor()
        check(actor.kind == AndroidOwnedRoomCoroutines.Kind.FOREGROUND)
        work.requireActive()
        check(fileName == databasePath && connection == null && !closed)
        val value = OwnedConnection(); connection = value // Retain before the real driver call.
        value.openNative()
        return value
    }

    internal fun finishOriginalRoomOpen(): Unit = coroutines.nativeSql {
        work.requireActive(); check(!ready && frame == null)
        val actual = checkNotNull(connection)
        actual.requireNoLiveSteppedStatements()
        val hash = actual.scalarText("SELECT identity_hash FROM room_master_table WHERE id=42")
        check(actual.scalarLong("PRAGMA main.user_version") == 7L)
        check(actual.scalarText("PRAGMA main.journal_mode").lowercase() == "truncate" &&
            actual.scalarLong("PRAGMA main.synchronous") == 2L && actual.scalarLong("PRAGMA foreign_keys") == 1L)
        val identity = namespace.observeRoomDatabaseIdentity(databasePath, hash)
        anchor.bindOriginalDatabase(identity)
        actual.installCaptureSpool()
        ready = true
    }
    internal fun attach(value: SelfOwnedNativeRoomFrame) {
        check(ready && !closed && frame == null && !value.consumed)
        work.requireActive()
        frameInvocation = coroutines.currentNativeInvocation()
        frame = value
    }
    internal fun releaseAfterOuter(value: SelfOwnedNativeRoomFrame): Unit = coroutines.nativeSql {
        check(frame === value && value.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN)
        checkNotNull(connection).requireNoLiveSteppedStatements()
        checkNotNull(connection).requireCaptureSpool()
        frame = null; frameInvocation = null
    }
    internal fun requireAttached(value: SelfOwnedNativeRoomFrame) { check(frame === value && ready && !closed) }
    internal fun closeNative() {
        if (closed) return
        coroutines.requireActualClose()
        try { connection?.close(); closed = true }
        catch (failure: Throwable) { coroutines.fault(failure); throw failure }
    }

    private class Transaction(val actor: AndroidOwnedRoomCoroutines.Actor,
        val financial: SelfOwnedNativeRoomFrame?, val housekeeping: Boolean) {
        // DATA subordinate to this actual outer transaction; never a native child/receipt.
        val daoScope = NativeHistoryRoomDaoScopeV1()
    }

    private inner class OwnedConnection : SQLiteConnection {
        private var raw: SQLiteConnection? = null
        private val statements = linkedSetOf<OwnedStatement>()
        private var closeStarted = false
        private var actuallyClosed = false
        private var transaction: Transaction? = null

        fun openNative() {
            check(raw == null)
            val driver = BundledSQLiteDriver(); check(!driver.hasConnectionPool)
            coroutines.traceStage("bundled-driver-open-before")
            work.requireActive()
            raw = driver.open(databasePath, SQLITE_OPEN_READWRITE or SQLITE_OPEN_NOFOLLOW)
            coroutines.traceStage("bundled-driver-open-returned")
            val current = anchor.snapshotOriginal().record.nativeDatabase
            current?.let { check(namespace.observeRoomDatabaseIdentity(databasePath, it.boundRoomIdentityHash) == it) }
        }
        override fun inTransaction(): Boolean {
            coroutines.sqlActor(); check(!closeStarted && !actuallyClosed)
            return checkNotNull(raw).inTransaction()
        }
        override fun prepare(sql: String): SQLiteStatement {
            if (!ready) coroutines.traceStage("initial-sql-prepare-before-actor")
            val actor = coroutines.sqlActor()
            check(!closeStarted && !actuallyClosed)
            val actualSql = if (!ready && sql == "PRAGMA temp_store = MEMORY") "PRAGMA temp_store = FILE" else sql
            val kind = SqlKind(actualSql)
            authorize(actor, kind, submitting = false)
            return try {
                OwnedStatement(checkNotNull(raw).prepare(actualSql), kind).also { statements += it }
            } catch (failure: Throwable) { coroutines.fault(failure); throw failure }
        }
        override fun close() {
            if (actuallyClosed) return
            coroutines.requireActualClose()
            if (closeStarted) { coroutines.fault(); error("Original Room close uncertain") }
            closeStarted = true
            try {
                statements.toList().forEach { it.closeFromConnection() }
                checkNotNull(raw).close(); raw = null; actuallyClosed = true
            } catch (failure: Throwable) { coroutines.fault(failure); throw failure }
        }
        fun requireNoLiveSteppedStatements() {
            check(transaction == null && statements.none { it.stepped && !it.resetAfterStep })
            check(!inTransaction())
        }
        // Native direct schema/capture work uses the same retained statement inventory, AFTER
        // real query/background draining. There is no untracked raw prepare/use cleanup edge.
        fun scalarText(sql: String): String = prepare(sql).use { check(it.step()); it.getText(0) }
        fun scalarLong(sql: String): Long = prepare(sql).use { check(it.step()); it.getLong(0) }
        private fun execute(sql: String) { prepare(sql).use { check(!it.step()) } }
        fun installCaptureSpool() {
            for (objectSql in NativeHistoryOwnedSqlV2.captureObjects.values) objectSql.sql?.let(::execute)
            requireCaptureSpool()
        }
        fun requireCaptureSpool() {
            val remaining = NativeHistoryOwnedSqlV2.captureObjects.keys.toMutableSet()
            prepare("SELECT type,name,tbl_name,sql FROM temp.sqlite_schema ORDER BY rowid").use { statement ->
                var count = 0
                while (statement.step()) {
                    check(++count <= NativeHistoryPhysicalReadProfileV2.MAX_SCHEMA_OBJECTS)
                    val type = statement.getText(0); val name = statement.getText(1); val table = statement.getText(2)
                    val sql = if (statement.isNull(3)) null else statement.getText(3)
                    val expected = NativeHistoryOwnedSqlV2.captureObjects[name]
                    if (expected == null) check(NativeHistoryOwnedSqlV2.isTrackingTable(type, name, table, sql))
                    else check(remaining.remove(name) && type == expected.type && table == expected.table &&
                        NativeHistoryOwnedSqlV2.normalized(sql) == NativeHistoryOwnedSqlV2.normalized(expected.sql))
                }
            }
            check(remaining.isEmpty())
        }

        private fun authorize(actor: AndroidOwnedRoomCoroutines.Actor, sql: SqlKind, submitting: Boolean) {
            if (actor.kind == AndroidOwnedRoomCoroutines.Kind.NATIVE) {
                check(transaction == null); work.requireActive(); return
            }
            val active = transaction
            if (active != null) coroutines.requireSameActor(actor, active.actor)
            if (actor.kind == AndroidOwnedRoomCoroutines.Kind.BACKGROUND) {
                check(sql.begin || sql.commit || sql.rollback || sql.housekeeping)
                check(active == null || active.housekeeping)
                // TEMP cleanup belongs to the already-admitted actor, even after data denial.
                // It cannot finalize or overwrite any financial frame attached to the driver.
                return
            }
            if (ready) {
                val selected = checkNotNull(frame)
                check(frameInvocation === actor.invocation)
                if (selected.finalized && selected.nativeDisposition == SelfOwnedNativeSqlDisposition.UNKNOWN)
                    check(sql.commit || sql.rollback)
                if (selected.nativeBegan && selected.nativeDisposition != SelfOwnedNativeSqlDisposition.UNKNOWN)
                    error("SQL after financial disposition")
                if (sql.daoScopeControl) {
                    // Room3 wraps a generated DAO write in its one fixed savepoint. Only the
                    // SAME already-begun financial writer may use it; no nested public entry,
                    // background actor, new invocation/deadline or finalized frame is admitted.
                    check(active != null && active.financial === selected && !active.housekeeping &&
                        selected.nativeBegan && selected.nativeDisposition == SelfOwnedNativeSqlDisposition.UNKNOWN &&
                        !selected.finalized)
                }
                active?.takeIf { it.financial === selected }?.daoScope?.before(sql.sql)
            } else {
                // Schema bootstrap cannot write application rows before DATABASE_BOUND.
                val target = APPLICATION_WRITE.find(sql.sql.trim())?.groupValues?.get(1)
                check(target == null || NativeHistoryTableV2.entries.none { it.application && it.sqlName.equals(target, true) })
            }
            if (submitting && !sql.rollback) work.requireActive()
        }

        private inner class OwnedStatement(private val delegate: SQLiteStatement, private val sql: SqlKind) : SQLiteStatement {
            var stepped = false; private set
            var resetAfterStep = false; private set
            private var closing = false
            private var closedStatement = false
            private var useActor: AndroidOwnedRoomCoroutines.Actor? = null

            private fun actor(claim: Boolean = true): AndroidOwnedRoomCoroutines.Actor {
                check(!closedStatement && !closing && !actuallyClosed)
                val current = coroutines.sqlActor()
                useActor?.let { coroutines.requireSameActor(current, it) }
                if (claim && useActor == null) useActor = current
                return current
            }
            override fun step(): Boolean {
                if (!ready) coroutines.traceStage("initial-sql-step-before-actor")
                val current = actor()
                authorize(current, sql, submitting = true)
                if (current.kind == AndroidOwnedRoomCoroutines.Kind.NATIVE)
                    return stepDelegate() // Only the closed-purpose, quiescent native cut.
                val daoAction = if (ready && sql.daoScopeControl &&
                    current.kind == AndroidOwnedRoomCoroutines.Kind.FOREGROUND)
                    checkNotNull(transaction).daoScope.before(sql.sql) else null
                var ending: Transaction? = null
                if (sql.begin) {
                    check(transaction == null)
                    val financial = if (ready && current.kind == AndroidOwnedRoomCoroutines.Kind.FOREGROUND) checkNotNull(frame) else null
                    if (financial != null) {
                        check(!financial.nativeBegan && financial.nativeDisposition == SelfOwnedNativeSqlDisposition.NOT_SUBMITTED)
                        financial.nativeBegan = true; financial.nativeDisposition = SelfOwnedNativeSqlDisposition.UNKNOWN
                    }
                    transaction = Transaction(current, financial, current.kind == AndroidOwnedRoomCoroutines.Kind.BACKGROUND)
                } else if (sql.commit || sql.rollback) {
                    ending = checkNotNull(transaction)
                    coroutines.requireSameActor(current, ending.actor)
                    if (sql.commit && ending.financial != null) check(ending.financial.finalized)
                }
                return try {
                    val result = stepDelegate()
                    if (daoAction != null) {
                        check(!result)
                        checkNotNull(transaction).daoScope.completed(daoAction)
                    }
                    if (ending != null && !result) {
                        ending.financial?.let {
                            check(it.nativeBegan && it.nativeDisposition == SelfOwnedNativeSqlDisposition.UNKNOWN)
                            it.nativeDisposition = if (sql.commit) SelfOwnedNativeSqlDisposition.COMMITTED else SelfOwnedNativeSqlDisposition.ROLLED_BACK
                        }
                        transaction = null
                    }
                    result
                } catch (failure: Throwable) {
                    // Only the ACTUAL financial transaction can change financial disposition.
                    // A later TEMP failure cannot turn its successful COMMIT into ROLLED_BACK.
                    val actual = transaction
                    if ((sql.begin || sql.commit || sql.rollback) && actual?.financial != null)
                        actual.financial.nativeDisposition = SelfOwnedNativeSqlDisposition.UNKNOWN
                    if (current.kind == AndroidOwnedRoomCoroutines.Kind.BACKGROUND) coroutines.fault()
                    throw failure
                }
            }
            private fun stepDelegate(): Boolean { stepped = true; resetAfterStep = false; return delegate.step() }
            override fun bindBlob(index: Int, value: ByteArray) { actor(); delegate.bindBlob(index, value) }
            override fun bindDouble(index: Int, value: Double) { actor(); delegate.bindDouble(index, value) }
            override fun bindLong(index: Int, value: Long) { actor(); delegate.bindLong(index, value) }
            override fun bindText(index: Int, value: String) { actor(); delegate.bindText(index, value) }
            override fun bindNull(index: Int) { actor(); delegate.bindNull(index) }
            override fun getBlob(index: Int): ByteArray { actor(); return delegate.getBlob(index) }
            override fun getDouble(index: Int): Double { actor(); return delegate.getDouble(index) }
            override fun getLong(index: Int): Long { actor(); return delegate.getLong(index) }
            override fun getText(index: Int): String { actor(); return delegate.getText(index) }
            override fun getColumnCount(): Int { actor(); return delegate.getColumnCount() }
            override fun getColumnName(index: Int): String { actor(); return delegate.getColumnName(index) }
            override fun getColumnType(index: Int): Int { actor(); return delegate.getColumnType(index) }
            override fun isNull(index: Int): Boolean { actor(); return delegate.isNull(index) }
            override fun clearBindings() { actor(claim = false); delegate.clearBindings() }
            override fun reset() {
                actor(claim = false)
                try { delegate.reset(); resetAfterStep = true; useActor = null }
                catch (failure: Throwable) { coroutines.fault(failure); throw failure }
            }
            override fun close() {
                if (closedStatement) return
                coroutines.requireStatementClose(useActor); closeDelegate()
            }
            fun closeFromConnection() {
                if (closedStatement) return
                coroutines.requireActualClose(); closeDelegate()
            }
            private fun closeDelegate() {
                if (closing) { coroutines.fault(); error("Original statement close uncertain") }
                closing = true
                try { delegate.close(); closedStatement = true; statements.remove(this); useActor = null }
                catch (failure: Throwable) { coroutines.fault(failure); throw failure }
            }
        }
    }

    private class SqlKind(val sql: String) {
        val normalized = sql.trim().uppercase()
        val commit = normalized in setOf("COMMIT", "COMMIT TRANSACTION", "END TRANSACTION")
        val rollback = normalized in setOf("ROLLBACK", "ROLLBACK TRANSACTION")
        val begin = normalized in setOf("BEGIN", "BEGIN DEFERRED TRANSACTION", "BEGIN IMMEDIATE TRANSACTION", "BEGIN EXCLUSIVE TRANSACTION")
        val housekeeping = sql in NativeHistoryOwnedSqlV2.housekeepingStatements
        val daoScopeControl = normalized.startsWith("SAVEPOINT") || normalized.startsWith("RELEASE") ||
            (!rollback && normalized.startsWith("ROLLBACK"))
    }
    private companion object {
        val APPLICATION_WRITE = Regex("^(?:INSERT(?: OR [A-Z]+)? INTO|UPDATE(?: OR [A-Z]+)?|DELETE FROM)\\s+[`\"]?([a-zA-Z0-9_]+)", RegexOption.IGNORE_CASE)
    }
}
