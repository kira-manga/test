package com.ukkera.pos.security

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.ukkera.pos.android.files.AndroidNativeFiles
import java.io.File
import java.io.FileDescriptor
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Separate disposable DATA resources, retained by the original native reader before open.
 * Every operation uses that reader's P2 work and original guards. Nothing here issues an
 * authorization, serializes a proof, changes the original database, or exposes a SQL loan.
 */
internal class AndroidSelfOwnedHistoryWorkspace(
    private val source: AndroidSelfOwnedHistoryReadStorage,
    private val work: AndroidOwnedInstallationWork,
    private val environment: AndroidSelfOwnedEnvironment,
    private val original: OriginalNativeHistorySnapshot,
) : SelfOwnedNativeHistoryWorkspaceController , SelfOwnedHistoryWorkspaceStore {
    private enum class State { NEW, OPENING, OPEN, CLOSING, CLOSED, UNKNOWN }
    private var state = State.NEW
    private var directory: File? = null
    private var directoryCreated = false
    private var directoryRemoved = false
    private var parentStamp: Stamp? = null
    private var directoryStamp: Stamp? = null
    private var databaseStamp: Stamp? = null
    private var parentDescriptor: OwnedDescriptor? = null
    private var directoryDescriptor: OwnedDescriptor? = null
    private var databaseDescriptor: OwnedDescriptor? = null
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private val statements = mutableListOf<OwnedStatement>()
    private var connection: SQLiteConnection? = null
    private var connectionCloseStarted = false
    private var encryptionKey: SecretKey? = null
    private var indexKey: SecretKey? = null
    private var workspaceId: ByteArray? = null
    private var noncePrefix: ByteArray? = null
    private var nextNonce = 0L
    private var lastRecordId = 0L
    private val cursors = mutableSetOf<SelfOwnedNativeHistoryDataCursor>()

    override val actuallyClosedNative: Boolean get() = state == State.CLOSED

    override fun openNative() {
        known(state == State.NEW)
        state = State.OPENING
        try {
            source.requireWorkspaceNative(original)
            val random = SecureRandom()
            workspaceId = ByteArray(16).also(random::nextBytes)
            noncePrefix = ByteArray(4).also(random::nextBytes)
            encryptionKey = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
            val indexBytes = ByteArray(32).also(random::nextBytes)
            try { indexKey = SecretKeySpec(indexBytes, "HmacSHA256") } finally { indexBytes.fill(0) }

            val parent = environment.noBackupDirectory
            val observedParent = stamp(Os.lstat(parent.path))
            requireDirectory(observedParent, owned = false)
            known(observedParent.uid == environment.uid)
            parentStamp = observedParent
            parentDescriptor = openDirectory(parent, observedParent)
            // The fixed reserved prefix and native random suffix are never caller-selected.
            // Only a directory CREATED by this exact retained owner can later be removed.
            val name = DIRECTORY_PREFIX + requireNotNull(workspaceId).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
            val scratch = File(parent, name)
            directory = scratch
            known(lstatOrNull(scratch) == null)
            Os.mkdir(scratch.path, DIRECTORY_MODE)
            directoryCreated = true
            val observed = stamp(Os.lstat(scratch.path))
            requireDirectory(observed, owned = true)
            known(observed.device == observedParent.device)
            directoryStamp = observed
            directoryDescriptor = openDirectory(scratch, observed)
            known(androidOwnedDirectoryNames(scratch, 0, work).isEmpty())

            val database = File(scratch, DATABASE_NAME)
            databaseDescriptor = openDescriptor(database,
                OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
                FILE_MODE)
            Os.fchmod(requireNotNull(databaseDescriptor).fd, FILE_MODE)
            val file = stamp(Os.fstat(requireNotNull(databaseDescriptor).fd))
            requireFile(file)
            known(file.sameIdentity(stamp(Os.lstat(database.path))))
            databaseStamp = file
            val driver = BundledSQLiteDriver()
            known(!driver.hasConnectionPool)
            connection = driver.open(database.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_NOFOLLOW)
            checkFootprint(active = true)
            known(scalarText("PRAGMA journal_mode=DELETE") == "delete")
            execute("PRAGMA temp_store=MEMORY")
            known(scalarLong("PRAGMA temp_store") == 2L)
            execute("PRAGMA cache_size=-4096")
            known(scalarLong("PRAGMA cache_size") == -4096L)
            known(scalarLong("PRAGMA mmap_size=0") == 0L)
            known(scalarLong("PRAGMA mmap_size") == 0L)
            execute("PRAGMA foreign_keys=ON")
            known(scalarLong("PRAGMA foreign_keys") == 1L)
            verifyOnlyScratchMain(database.path)
            execute("CREATE TABLE records (record_id INTEGER PRIMARY KEY, partition INTEGER NOT NULL, " +
                "key_token BLOB NOT NULL CHECK(length(key_token)=32), sequence INTEGER NOT NULL CHECK(sequence>=0), " +
                "ordinal INTEGER NOT NULL CHECK(ordinal>=0), payload_size INTEGER NOT NULL CHECK(payload_size BETWEEN 0 AND 5242880), " +
                "key_size INTEGER NOT NULL CHECK(key_size BETWEEN 0 AND 1024), key_envelope BLOB NOT NULL)")
            execute("CREATE UNIQUE INDEX record_key ON records(partition,key_token,sequence,ordinal)")
            execute("CREATE INDEX record_order ON records(partition,sequence,ordinal,key_token)")
            execute("CREATE TABLE chunks (record_id INTEGER NOT NULL REFERENCES records(record_id), " +
                "ordinal INTEGER NOT NULL CHECK(ordinal>=0), envelope BLOB NOT NULL, " +
                "PRIMARY KEY(record_id,ordinal)) WITHOUT ROWID")
            state = State.OPEN
            guard()
        } catch (failure: Throwable) {
            try { closeNative() } catch (_: Throwable) { work.nativeClosureUnknown() }
            throw failure
        }
    }

    override suspend fun append(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int, payload: ByteArray): SelfOwnedNativeHistoryDataRecord = dataWork {
        inputKey(key); input(sequence >= 0 && ordinal >= 0 && payload.size <= MAX_PAYLOAD)
        val ownedKey = key.copyOf()
        val ownedPayload = try { payload.copyOf() } catch (failure: Throwable) { ownedKey.fill(0); throw failure }
        task(clear = { ownedKey.fill(0); ownedPayload.fill(0) }, dispose = { it.clear() }) {
            appendNative(partition, ownedKey, sequence, ordinal, ownedPayload)
        }
    }

    private fun appendNative(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int, payload: ByteArray): SelfOwnedNativeHistoryDataRecord {
        known(lastRecordId < Long.MAX_VALUE)
        val recordId = lastRecordId + 1
        val token = keyTokenFor(partition, key)
        val metadata = Metadata(recordId, partition, token, sequence, ordinal, payload.size, key.size)
        var committed = false
        try {
            // Reject duplicates rather than update/replace an immutable record.
            val duplicate = query(EXACT_SQL, listOf(Number(partition.tag.toLong()), Bytes(token), Number(sequence), Number(ordinal.toLong())))
            try { if (duplicate.value.step()) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
            finally { duplicate.closeActual() }
            execute("BEGIN IMMEDIATE TRANSACTION")
            known(requireNotNull(connection).inTransaction())
            val keyEnvelope = encrypt(metadata, KEY_DOMAIN, 0, key, 0, key.size)
            try {
                val insert = query("INSERT INTO records(record_id,partition,key_token,sequence,ordinal,payload_size,key_size,key_envelope) " +
                    "VALUES(?,?,?,?,?,?,?,?)", listOf(Number(recordId), Number(partition.tag.toLong()), Bytes(token),
                    Number(sequence), Number(ordinal.toLong()), Number(payload.size.toLong()), Number(key.size.toLong()), Bytes(keyEnvelope)))
                try { known(!insert.value.step()) } finally { insert.closeActual() }
            } finally { keyEnvelope.fill(0) }
            var offset = 0
            var chunk = 0
            while (offset < payload.size) {
                work.requireActive()
                val size = minOf(MAX_CHUNK, payload.size - offset)
                val envelope = encrypt(metadata, PAYLOAD_DOMAIN, chunk, payload, offset, size)
                try {
                    val insert = query("INSERT INTO chunks(record_id,ordinal,envelope) VALUES(?,?,?)",
                        listOf(Number(recordId), Number(chunk.toLong()), Bytes(envelope)))
                    try { known(!insert.value.step()) } finally { insert.closeActual() }
                } finally { envelope.fill(0) }
                offset += size; chunk++
            }
            execute("COMMIT")
            known(!requireNotNull(connection).inTransaction())
            committed = true
            lastRecordId = recordId
            return record(metadata, key)
        } finally {
            token.fill(0)
            if (!committed && connection?.inTransaction() == true) {
                try { execute("ROLLBACK"); known(!requireNotNull(connection).inTransaction()) }
                catch (failure: Throwable) { source.denyWorkspaceUseAndRequestClose(); throw failure }
            }
        }
    }

    override suspend fun exact(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        sequence: Long, ordinal: Int): SelfOwnedNativeHistoryDataRecord? = dataWork {
        inputKey(key); input(sequence >= 0 && ordinal >= 0)
        val owned = key.copyOf()
        task(clear = { owned.fill(0) }, dispose = { it?.clear() }) {
            lookup(partition, owned, EXACT_SQL, sequence, ordinal)
        }
    }

    override suspend fun floor(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray,
        beforeSequenceExclusive: Long): SelfOwnedNativeHistoryDataRecord? = dataWork {
        inputKey(key); input(beforeSequenceExclusive >= 0)
        val owned = key.copyOf()
        task(clear = { owned.fill(0) }, dispose = { it?.clear() }) {
            lookup(partition, owned, FLOOR_SQL, beforeSequenceExclusive, null)
        }
    }

    private fun lookup(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray, sql: String,
        sequence: Long, ordinal: Int?): SelfOwnedNativeHistoryDataRecord? {
        val token = keyTokenFor(partition, key)
        try {
            val parameters = listOf(Number(partition.tag.toLong()), Bytes(token), Number(sequence)) +
                if (ordinal == null) emptyList() else listOf(Number(ordinal.toLong()))
            val statement = query(sql, parameters)
            var value: SelfOwnedNativeHistoryDataRecord? = null
            try {
                if (!statement.value.step()) return null
                val found = readRecord(statement.value, partition, key)
                value = found
                try {
                    known(if (ordinal == null) found.sequence < sequence else found.sequence == sequence && found.ordinal == ordinal)
                    known(!statement.value.step()); return found
                }
                catch (failure: Throwable) { found.clear(); throw failure }
            } finally {
                try { statement.closeActual() }
                catch (failure: Throwable) { value?.clear(); throw failure }
            }
        } finally { token.fill(0) }
    }

    override suspend fun discardDerived(key: ByteArray) {
        inputKey(key)
        val owned = key.copyOf()
        try {
            while (true) {
                val complete = dataWork {
                    val batchKey = owned.copyOf()
                    task(clear = { batchKey.fill(0) }) { discardDerivedBatchNative(batchKey) }
                }
                if (complete) return
            }
        } finally { owned.fill(0) }
    }

    private fun discardDerivedBatchNative(key: ByteArray): Boolean {
        val partition = SelfOwnedNativeHistoryDataPartition.DERIVED
        if (cursors.any { !it.closed && it.partition == partition &&
                (it.exactKey == null || it.exactKey.contentEquals(key)) })
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val token = keyTokenFor(partition, key)
        var committed = false
        try {
            execute("BEGIN IMMEDIATE TRANSACTION")
            known(requireNotNull(connection).inTransaction())
            // One authenticated descriptor at a time. No IN-list, temporary row-id estate
            // or unbounded plaintext metadata collection is used for a large sort run.
            // Every batch gets a separately admitted fixed-deadline native operation;
            // progress never refreshes the watchdog inside an unfinished native call.
            var deleted = 0
            var complete = false
            while (deleted < MAX_PAGE) {
                work.requireActive()
                val select = query(SELECT_METADATA + " FROM records INDEXED BY record_key " +
                    "WHERE partition=? AND key_token=? ORDER BY sequence,ordinal LIMIT 1",
                    listOf(Number(partition.tag.toLong()), Bytes(token)))
                val id: Long
                try {
                    if (!select.value.step()) { complete = true; break }
                    val value = readRecord(select.value, partition, key)
                    try { id = value.recordId; known(!select.value.step()) }
                    finally { value.clear() }
                } finally { select.closeActual() }
                val chunks = query("DELETE FROM chunks WHERE record_id=?", listOf(Number(id)))
                try { known(!chunks.value.step()) } finally { chunks.closeActual() }
                val record = query("DELETE FROM records WHERE record_id=? AND partition=? AND key_token=?",
                    listOf(Number(id), Number(partition.tag.toLong()), Bytes(token)))
                try { known(!record.value.step()) } finally { record.closeActual() }
                deleted++
            }
            execute("COMMIT")
            known(!requireNotNull(connection).inTransaction())
            committed = true
            // lastRecordId and nextNonce intentionally survive deletion. Neither a new
            // run nor a failed transaction can reuse a prior authenticated coordinate.
            return complete
        } finally {
            token.fill(0)
            if (!committed && connection?.inTransaction() == true) {
                try { execute("ROLLBACK"); known(!requireNotNull(connection).inTransaction()) }
                catch (failure: Throwable) { source.denyWorkspaceUseAndRequestClose(); throw failure }
            }
        }
    }

    override suspend fun openCursor(partition: SelfOwnedNativeHistoryDataPartition,
        key: ByteArray?): SelfOwnedNativeHistoryDataCursor = dataWork {
        if (key != null) inputKey(key)
        val owned = key?.copyOf()
        task(clear = { owned?.fill(0) }, dispose = { closeCursorNative(it) }) {
            if (cursors.size >= MAX_CURSORS) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            val copied = owned?.copyOf()
            val keyed = if (copied == null) null else try { keyTokenFor(partition, copied) }
                catch (failure: Throwable) { copied.fill(0); throw failure }
            SelfOwnedNativeHistoryDataCursor(this, partition, lastRecordId, keyed, copied).also { cursors += it }
        }
    }

    override suspend fun nextPage(cursor: SelfOwnedNativeHistoryDataCursor): List<SelfOwnedNativeHistoryDataRecord> =
        dataWork { task(dispose = { page -> page.forEach { it.clear() } }) { nextPageNative(cursor) } }

    private fun nextPageNative(cursor: SelfOwnedNativeHistoryDataCursor): List<SelfOwnedNativeHistoryDataRecord> {
        requireCursor(cursor)
        if (cursor.ended) return emptyList()
        val filtered = cursor.keyToken != null
        val started = cursor.lastKeyToken != null
        val sql = SELECT_METADATA + " FROM records INDEXED BY " + (if (filtered) "record_key" else "record_order") +
            " WHERE partition=? AND record_id<=?" + (if (filtered) " AND key_token=?" else "") +
            (if (!started) "" else if (filtered) " AND (sequence,ordinal)>(?,?)" else " AND (sequence,ordinal,key_token)>(?,?,?)") +
            " ORDER BY sequence,ordinal,key_token LIMIT 64"
        val parameters = mutableListOf<Parameter>(Number(cursor.partition.tag.toLong()), Number(cursor.highWater))
        if (filtered) parameters += Bytes(requireNotNull(cursor.keyToken))
        if (started) {
            parameters += Number(cursor.lastSequence); parameters += Number(cursor.lastOrdinal.toLong())
            if (!filtered) parameters += Bytes(requireNotNull(cursor.lastKeyToken))
        }
        val statement = query(sql, parameters)
        val page = mutableListOf<SelfOwnedNativeHistoryDataRecord>()
        try {
            while (statement.value.step()) {
                known(page.size < MAX_PAGE)
                val value = readRecord(statement.value, cursor.partition, cursor.exactKey)
                try {
                    known(value.recordId <= cursor.highWater)
                    val previous = page.lastOrNull()
                    val previousToken = previous?.keyToken ?: cursor.lastKeyToken
                    if (previousToken != null) {
                        val previousSequence = previous?.sequence ?: cursor.lastSequence
                        val previousOrdinal = previous?.ordinal ?: cursor.lastOrdinal
                        known(value.sequence > previousSequence || value.sequence == previousSequence &&
                            (value.ordinal > previousOrdinal || value.ordinal == previousOrdinal &&
                                compareTokens(value.keyToken, previousToken) > 0))
                    }
                    page += value
                } catch (failure: Throwable) { value.clear(); throw failure }
            }
            if (page.isEmpty()) cursor.ended = true
            else {
                val last = page.last()
                cursor.lastSequence = last.sequence; cursor.lastOrdinal = last.ordinal
                cursor.lastKeyToken?.fill(0); cursor.lastKeyToken = last.keyToken.copyOf()
            }
            return page
        } catch (failure: Throwable) { page.forEach { it.clear() }; throw failure }
        finally {
            try { statement.closeActual() }
            catch (failure: Throwable) { page.forEach { it.clear() }; throw failure }
        }
    }

    override suspend fun closeCursor(cursor: SelfOwnedNativeHistoryDataCursor): Unit =
        dataWork { task { closeCursorNative(cursor) } }

    private fun closeCursorNative(cursor: SelfOwnedNativeHistoryDataCursor) {
        known(cursor.controller === this)
        if (cursor.closed) return
        known(cursors.remove(cursor))
        cursor.closed = true; cursor.clear()
    }

    private fun requireCursor(cursor: SelfOwnedNativeHistoryDataCursor) {
        known(cursor.controller === this && !cursor.closed && cursor in cursors && cursor.highWater <= lastRecordId)
    }

    override suspend fun readPayloadChunk(record: SelfOwnedNativeHistoryDataRecord,
        chunkOrdinal: Int): ByteArray = dataWork {
        input(record.controller === this && chunkOrdinal >= 0 && chunkOrdinal < record.chunkCount)
        task(dispose = { it.fill(0) }) { readChunkNative(record, chunkOrdinal) }
    }

    private fun readChunkNative(record: SelfOwnedNativeHistoryDataRecord, chunkOrdinal: Int): ByteArray {
        val descriptor = query(SELECT_METADATA + " FROM records WHERE record_id=?", listOf(Number(record.recordId)))
        val metadata: Metadata
        try {
            known(descriptor.value.step())
            val current = readRecord(descriptor.value, record.partition, null)
            try {
                val key = current.keyBytes()
                try {
                    known(current.recordId == record.recordId && current.sequence == record.sequence && current.ordinal == record.ordinal &&
                        current.payloadBytes == record.payloadBytes && current.keyToken.contentEquals(record.keyToken) && record.matchesKey(key))
                    metadata = Metadata(current.recordId, current.partition, current.keyToken.copyOf(), current.sequence,
                        current.ordinal, current.payloadBytes, key.size)
                } finally { key.fill(0) }
                known(!descriptor.value.step())
            } finally { current.clear() }
        } finally { descriptor.closeActual() }
        try {
            val statement = query("SELECT length(envelope),envelope FROM chunks WHERE record_id=? AND ordinal=?",
                listOf(Number(record.recordId), Number(chunkOrdinal.toLong())))
            var plaintext: ByteArray? = null
            try {
                known(statement.value.step())
                val size = minOf(MAX_CHUNK, record.payloadBytes - chunkOrdinal * MAX_CHUNK)
                val envelope = boundedBlob(statement.value, 0, 1, size + ENVELOPE_OVERHEAD)
                try {
                    val bytes = decrypt(metadata, PAYLOAD_DOMAIN, chunkOrdinal, size, envelope)
                    plaintext = bytes
                    try { known(!statement.value.step()); return bytes }
                    catch (failure: Throwable) { bytes.fill(0); throw failure }
                } finally { envelope.fill(0) }
            } finally {
                try { statement.closeActual() }
                catch (failure: Throwable) { plaintext?.fill(0); throw failure }
            }
        } finally { metadata.token.fill(0) }
    }

    private fun readRecord(statement: SQLiteStatement, expectedPartition: SelfOwnedNativeHistoryDataPartition,
        exactKey: ByteArray?): SelfOwnedNativeHistoryDataRecord {
        known(statement.getColumnCount() == 10)
        val id = integer(statement, 0); val partition = integer(statement, 1)
        known(id in 1L..lastRecordId && partition == expectedPartition.tag.toLong())
        val token = boundedBlob(statement, 2, 3, TOKEN_BYTES)
        try {
            val sequence = integer(statement, 4); val ordinal = integer(statement, 5)
            val size = integer(statement, 6); val keySize = integer(statement, 7)
            known(sequence >= 0 && ordinal in 0..Int.MAX_VALUE.toLong() && size in 0..MAX_PAYLOAD.toLong() && keySize in 0..MAX_KEY.toLong())
            val metadata = Metadata(id, expectedPartition, token, sequence, ordinal.toInt(), size.toInt(), keySize.toInt())
            val envelope = boundedBlob(statement, 8, 9, keySize.toInt() + ENVELOPE_OVERHEAD)
            try {
                val key = decrypt(metadata, KEY_DOMAIN, 0, keySize.toInt(), envelope)
                try {
                    val actualToken = keyTokenFor(expectedPartition, key)
                    try { known(actualToken.contentEquals(token) && (exactKey == null || key.contentEquals(exactKey))) }
                    finally { actualToken.fill(0) }
                    return record(metadata, key)
                } finally { key.fill(0) }
            } finally { envelope.fill(0) }
        } finally { token.fill(0) }
    }

    private fun record(metadata: Metadata, key: ByteArray): SelfOwnedNativeHistoryDataRecord =
        SelfOwnedNativeHistoryDataRecord(this, metadata.id, metadata.partition, metadata.sequence,
            metadata.ordinal, metadata.size, metadata.token.copyOf(), key)

    private data class Metadata(val id: Long, val partition: SelfOwnedNativeHistoryDataPartition,
        val token: ByteArray, val sequence: Long, val ordinal: Int, val size: Int, val keySize: Int)

    private fun keyTokenFor(partition: SelfOwnedNativeHistoryDataPartition, key: ByteArray): ByteArray {
        val input = selfOwnedEncode("history-data-index/v1", 2048) {
            id(original.lifetimeId); string(original.recordDigest.value); field(requireNotNull(workspaceId))
            int(partition.tag); field(key)
        }
        return try { Mac.getInstance("HmacSHA256").apply { init(requireNotNull(indexKey)) }.doFinal(input) }
        finally { input.fill(0) }
    }

    private fun aad(metadata: Metadata, domain: Int, chunk: Int, clearSize: Int): ByteArray =
        selfOwnedEncode("history-data-chunk/v1", 1024) {
            id(original.lifetimeId); string(original.recordDigest.value); field(requireNotNull(workspaceId))
            long(metadata.id); int(metadata.partition.tag); field(metadata.token); long(metadata.sequence)
            int(metadata.ordinal); int(metadata.size); int(metadata.keySize)
            int((metadata.size + MAX_CHUNK - 1) / MAX_CHUNK); int(domain); int(chunk); int(clearSize)
        }

    private fun encrypt(metadata: Metadata, domain: Int, chunk: Int, input: ByteArray,
        offset: Int, size: Int): ByteArray {
        known(size in 0..MAX_CHUNK && nextNonce < Long.MAX_VALUE)
        val nonce = ByteArray(NONCE_BYTES)
        requireNotNull(noncePrefix).copyInto(nonce)
        val counter = nextNonce++ // Consumed even if cipher/provider execution fails.
        for (index in 0..7) nonce[4 + index] = (counter ushr (56 - index * 8)).toByte()
        val associated = aad(metadata, domain, chunk, size)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(encryptionKey), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associated)
            val ciphertext = cipher.doFinal(input, offset, size)
            try {
                known(ciphertext.size == size + TAG_BYTES)
                return ByteArray(size + ENVELOPE_OVERHEAD).also {
                    nonce.copyInto(it); ciphertext.copyInto(it, NONCE_BYTES)
                }
            } finally { ciphertext.fill(0) }
        } finally { nonce.fill(0); associated.fill(0) }
    }

    private fun decrypt(metadata: Metadata, domain: Int, chunk: Int, size: Int, envelope: ByteArray): ByteArray {
        known(envelope.size == size + ENVELOPE_OVERHEAD)
        val prefix = requireNotNull(noncePrefix)
        known((0..3).all { envelope[it] == prefix[it] })
        var counter = 0L
        for (index in 4 until NONCE_BYTES) counter = (counter shl 8) or (envelope[index].toLong() and 255)
        known(counter >= 0 && counter < nextNonce)
        val nonce = envelope.copyOfRange(0, NONCE_BYTES)
        val associated = aad(metadata, domain, chunk, size)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, requireNotNull(encryptionKey), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associated)
            val bytes = cipher.doFinal(envelope, NONCE_BYTES, envelope.size - NONCE_BYTES)
            if (bytes.size != size) { bytes.fill(0); uncertain() }
            return bytes
        } finally { nonce.fill(0); associated.fill(0) }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        try {
            when (val result = work.run {
                object : AndroidOwnedTask<Unit> {
                    override fun execute() { closeNative() }
                    override fun discard(value: Unit) = Unit
                    override fun clearInputs() = Unit
                }
            }) {
                is AndroidOwnedResult.Value -> Unit
                is AndroidOwnedResult.Unavailable -> {
                    source.denyWorkspaceUseAndRequestClose(); throw NativeHistoryUnavailable(result.reason)
                }
            }
        } catch (failure: Throwable) { source.denyWorkspaceUseAndRequestClose(); throw failure }
    }

    /** Cleanup only. The retained reader invokes this before its own actual native close. */
    override fun closeNative() {
        if (state == State.CLOSED) return
        if (state == State.CLOSING || state == State.UNKNOWN) { work.nativeClosureUnknown(); uncertain() }
        state = State.CLOSING
        try {
            cursors.forEach { it.closed = true; it.clear() }; cursors.clear()
            for (statement in statements.toList().asReversed()) statement.closeActual()
            connection?.let {
                if (it.inTransaction()) execute("ROLLBACK")
                known(!it.inTransaction())
                if (connectionCloseStarted) uncertain()
                connectionCloseStarted = true
                it.close() // Forget only after the actual SQLite close returned.
                connection = null
            }
            if (directoryCreated) {
                checkFootprint(active = false)
                val scratch = requireNotNull(directory)
                for (name in names(active = false)) {
                    known(name in SCRATCH_NAMES)
                    val path = File(scratch, name)
                    val before = stamp(Os.lstat(path.path)); requireFile(before)
                    if (name == DATABASE_NAME) known(requireNotNull(databaseStamp).sameIdentity(before))
                    Os.remove(path.path)
                    known(lstatOrNull(path) == null)
                }
                known(names(active = false).isEmpty())
                known(requireNotNull(directoryStamp).sameDirectory(stamp(Os.lstat(scratch.path))))
                Os.remove(scratch.path)
                directoryRemoved = true
                known(lstatOrNull(scratch) == null)
            }
            for (descriptor in descriptors.toList().asReversed()) descriptor.closeActual()
            // Keys are never persisted. Drop provider references; JVM erasure is not claimed.
            encryptionKey = null; indexKey = null
            workspaceId?.fill(0); workspaceId = null
            noncePrefix?.fill(0); noncePrefix = null
            state = State.CLOSED
        } catch (failure: Throwable) { state = State.UNKNOWN; work.nativeClosureUnknown(); throw failure }
    }

    private suspend fun <T> dataWork(prepare: () -> AndroidOwnedTask<T>): T {
        try {
            return when (val result = work.run(prepare)) {
                is AndroidOwnedResult.Value -> result.value
                is AndroidOwnedResult.Unavailable -> {
                    if (result.reason in setOf(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
                            SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN, SelfOwnedInstallationFailureV1.NATIVE_RETIRED)) {
                        source.denyWorkspaceUseAndRequestClose()
                    }
                    throw NativeHistoryUnavailable(result.reason)
                }
            }
        } catch (failure: CancellationException) { source.denyWorkspaceUseAndRequestClose(); throw failure }
    }

    private fun <T> task(clear: () -> Unit = {}, dispose: (T) -> Unit = {}, body: () -> T): AndroidOwnedTask<T> =
        object : AndroidOwnedTask<T> {
            override fun execute(): T {
                guard()
                val value = body()
                try { guard(); return value }
                catch (failure: Throwable) { dispose(value); throw failure }
            }
            override fun discard(value: T) { dispose(value) }
            override fun clearInputs() { clear() }
        }

    private fun guard() {
        known(state == State.OPEN)
        source.requireWorkspaceNative(original)
        checkFootprint(active = true)
        work.requireActive()
    }

    private fun checkFootprint(active: Boolean) {
        val parent = requireNotNull(parentStamp)
        known(parent.sameDirectory(stamp(Os.lstat(environment.noBackupDirectory.path))))
        known(parent.sameDirectory(stamp(Os.fstat(requireNotNull(parentDescriptor).fd))))
        val scratch = requireNotNull(directory)
        known(directoryCreated && !directoryRemoved)
        val expected = requireNotNull(directoryStamp)
        known(expected.sameDirectory(stamp(Os.lstat(scratch.path))))
        known(expected.sameDirectory(stamp(Os.fstat(requireNotNull(directoryDescriptor).fd))))
        val files = names(active)
        known(files.all { it in SCRATCH_NAMES } && files.toSet().size == files.size)
        for (name in files) {
            val observed = stamp(Os.lstat(File(scratch, name).path)); requireFile(observed)
            if (name == DATABASE_NAME) known(requireNotNull(databaseStamp).sameIdentity(observed))
        }
        databaseStamp?.let {
            known(DATABASE_NAME in files)
            known(it.sameIdentity(stamp(Os.fstat(requireNotNull(databaseDescriptor).fd))))
        }
    }

    private fun names(active: Boolean): List<String> {
        val scratch = requireNotNull(directory)
        if (active) return androidOwnedDirectoryNames(scratch, SCRATCH_NAMES.size, work)
        // Terminal owner cleanup is already watched but may be denied/closing. Do not require
        // active data admission or reopen a native source to enumerate its own fixed artifacts.
        val cursor = try { AndroidNativeFiles.openDirectory(scratch.path) }
        catch (failure: AndroidNativeFiles.ClosureUncertainException) { work.nativeClosureUnknown(); throw failure }
        return try {
            val result = mutableListOf<String>()
            while (true) {
                val name = cursor.nextName() ?: break
                known(result.size < SCRATCH_NAMES.size)
                result += name
            }
            result
        } finally {
            try { cursor.close() } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
        }
    }

    private inner class OwnedDescriptor {
        var native: FileDescriptor? = null
        val fd: FileDescriptor get() = requireNotNull(native)
        private var closeStarted = false
        fun closeActual() {
            if (closeStarted) { work.nativeClosureUnknown(); uncertain() }
            closeStarted = true
            native?.let {
                try { AndroidNativeFiles.close(it); native = null }
                catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
            }
            descriptors.remove(this)
        }
    }

    private fun openDescriptor(path: File, flags: Int, mode: Int): OwnedDescriptor {
        val descriptor = OwnedDescriptor().also { descriptors += it }
        descriptor.native = androidOwnedOpenDescriptor(path, flags, mode, work)
        known(AndroidNativeFiles.closeOnExec(descriptor.fd))
        return descriptor
    }

    private fun openDirectory(path: File, expected: Stamp): OwnedDescriptor {
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        known(expected.sameDirectory(stamp(Os.fstat(descriptor.fd))) && expected.sameDirectory(stamp(Os.lstat(path.path))))
        return descriptor
    }

    private inner class OwnedStatement(val value: SQLiteStatement, private val parameters: List<Parameter>) {
        private var closeStarted = false
        fun closeActual() {
            if (closeStarted) { work.nativeClosureUnknown(); uncertain() }
            closeStarted = true
            try { value.close(); statements.remove(this) }
            catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
            finally { parameters.forEach { it.clear() } }
        }
    }

    private sealed interface Parameter {
        fun bind(statement: SQLiteStatement, index: Int)
        fun clear() = Unit
    }
    private class Number(private val value: Long) : Parameter {
        override fun bind(statement: SQLiteStatement, index: Int) { statement.bindLong(index, value) }
    }
    private class Bytes(value: ByteArray) : Parameter {
        private val value = value.copyOf()
        override fun bind(statement: SQLiteStatement, index: Int) { statement.bindBlob(index, value) }
        override fun clear() { value.fill(0) }
        // Only bounded ciphertext/keyed bytes are ever bound; no protected plaintext.
    }

    private fun query(sql: String, parameters: List<Parameter> = emptyList()): OwnedStatement {
        val statement = OwnedStatement(requireNotNull(connection).prepare(sql), parameters).also { statements += it }
        try { parameters.forEachIndexed { index, parameter -> parameter.bind(statement.value, index + 1) }; return statement }
        catch (failure: Throwable) { statement.closeActual(); throw failure }
    }

    private fun execute(sql: String) {
        val statement = query(sql)
        try { known(!statement.value.step()) } finally { statement.closeActual() }
    }

    private fun scalarLong(sql: String): Long {
        val statement = query(sql)
        try {
            known(statement.value.step() && statement.value.getColumnCount() == 1)
            return integer(statement.value, 0).also { known(!statement.value.step()) }
        } finally { statement.closeActual() }
    }

    private fun scalarText(sql: String): String {
        val statement = query(sql)
        try {
            known(statement.value.step() && statement.value.getColumnCount() == 1 && statement.value.getColumnType(0) == 3)
            return statement.value.getText(0).also { known(it.length <= 32 && !statement.value.step()) }
        } finally { statement.closeActual() }
    }

    private fun verifyOnlyScratchMain(path: String) {
        val statement = query("PRAGMA database_list")
        try {
            known(statement.value.step() && integer(statement.value, 0) == 0L && statement.value.getColumnType(1) == 3 &&
                statement.value.getText(1) == "main" && statement.value.getColumnType(2) == 3 &&
                statement.value.getText(2) == path && !statement.value.step())
        } finally { statement.closeActual() }
        val temporary = query("SELECT rowid FROM temp.sqlite_schema LIMIT 1")
        try { known(!temporary.value.step()) } finally { temporary.closeActual() }
    }

    private fun integer(statement: SQLiteStatement, column: Int): Long {
        known(statement.getColumnType(column) == 1)
        return statement.getLong(column)
    }

    private fun boundedBlob(statement: SQLiteStatement, lengthColumn: Int, blobColumn: Int, exactSize: Int): ByteArray {
        known(integer(statement, lengthColumn) == exactSize.toLong() && statement.getColumnType(blobColumn) == 4)
        val bytes = statement.getBlob(blobColumn)
        if (bytes.size != exactSize) { bytes.fill(0); uncertain() }
        return bytes
    }

    private data class Stamp(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int, val links: Long) {
        fun sameIdentity(other: Stamp): Boolean = device == other.device && inode == other.inode && uid == other.uid &&
            gid == other.gid && mode == other.mode && links == other.links
        fun sameDirectory(other: Stamp): Boolean = device == other.device && inode == other.inode && uid == other.uid &&
            gid == other.gid && mode == other.mode && OsConstants.S_ISDIR(other.mode)
    }
    private fun stamp(value: StructStat): Stamp = Stamp(value.st_dev, value.st_ino, value.st_uid, value.st_gid, value.st_mode, value.st_nlink)
    private fun requireDirectory(value: Stamp, owned: Boolean) {
        known(OsConstants.S_ISDIR(value.mode) && value.mode and 2 == 0)
        if (owned) known(value.uid == environment.uid && value.mode and 4095 == DIRECTORY_MODE)
    }
    private fun requireFile(value: Stamp) {
        known(OsConstants.S_ISREG(value.mode) && value.uid == environment.uid && value.mode and 4095 == FILE_MODE &&
            value.links == 1L && value.device == requireNotNull(directoryStamp).device)
    }
    private fun lstatOrNull(path: File): StructStat? = try { Os.lstat(path.path) }
    catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) null else throw failure }
    private fun inputKey(key: ByteArray) { input(key.size <= MAX_KEY) }
    private fun compareTokens(left: ByteArray, right: ByteArray): Int {
        known(left.size == TOKEN_BYTES && right.size == TOKEN_BYTES)
        for (index in 0 until TOKEN_BYTES) {
            val comparison = (left[index].toInt() and 255).compareTo(right[index].toInt() and 255)
            if (comparison != 0) return comparison
        }
        return 0
    }
    private fun input(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
    private fun known(value: Boolean) { if (!value) uncertain() }
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)

    private companion object {
        const val DIRECTORY_PREFIX = "ukkera-native-history-data-v1-"
        const val DATABASE_NAME = "data.db"
        const val DIRECTORY_MODE = 448 // 0700
        const val FILE_MODE = 384 // 0600
        val SCRATCH_NAMES = setOf(DATABASE_NAME, "$DATABASE_NAME-journal")
        const val MAX_KEY = SelfOwnedNativeHistoryWorkspace.MAX_KEY_BYTES
        const val MAX_PAYLOAD = SelfOwnedNativeHistoryWorkspace.MAX_PAYLOAD_BYTES
        const val MAX_CHUNK = SelfOwnedNativeHistoryWorkspace.MAX_CHUNK_BYTES
        const val MAX_PAGE = SelfOwnedNativeHistoryWorkspace.MAX_PAGE_RECORDS
        const val MAX_CURSORS = 64 // Live metadata resources, not a cumulative history limit.
        const val TOKEN_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val ENVELOPE_OVERHEAD = NONCE_BYTES + TAG_BYTES
        const val KEY_DOMAIN = 1
        const val PAYLOAD_DOMAIN = 2
        const val SELECT_METADATA = "SELECT record_id,partition,length(key_token),key_token,sequence,ordinal,payload_size,key_size,length(key_envelope),key_envelope"
        const val EXACT_SQL = "$SELECT_METADATA FROM records INDEXED BY record_key WHERE partition=? AND key_token=? AND sequence=? AND ordinal=?"
        const val FLOOR_SQL = "$SELECT_METADATA FROM records INDEXED BY record_key WHERE partition=? AND key_token=? AND sequence<? ORDER BY sequence DESC,ordinal DESC LIMIT 1"
    }
}
