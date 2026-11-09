package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.RoomOpenDelegate
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import java.io.ByteArrayOutputStream
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Redacted observation plumbing; no JUnit and no assertion/case PASS aggregation. */
internal class AuthV1Journal(
    private val run: Path,
    private val invocation: String,
    wiring: JsonObject,
) : AutoCloseable {
    private val channel = FileChannel.open(run.resolve("observations.jsonl"), CREATE_NEW, WRITE)
    private val slices = wiring.getValue("slice_registry").jsonArray.associate { item ->
        val value = item.jsonObject
        value.getValue("slice_id").jsonPrimitive.content to value.getValue("assertion_ids")
    }
    private val completed = mutableSetOf<String>()
    private var sequence = 0

    fun event(kind: String, id: String, outcome: String, facts: JsonObject = buildJsonObject {}) {
        val value = buildJsonObject {
            put("schema", "independent-auth-observation/v1")
            put("invocation", invocation)
            put("sequence", ++sequence)
            put("kind", kind)
            put("id", id)
            put("outcome", outcome)
            put("facts", facts)
            if (kind == "slice") {
                put("coverage", "PARTIAL")
                put("assertion_ids", checkNotNull(slices[id]))
            }
        }
        val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }

    suspend fun slice(id: String, body: suspend AuthV1Slice.() -> Unit) {
        check(id in slices && completed.add(id)) { "Unexpected or duplicate executor slice" }
        val observation = AuthV1Slice()
        try {
            observation.body()
            event("slice", id, "PASS_SLICE", JsonObject(observation.facts))
        } catch (failure: AuthV1Mismatch) {
            event("slice", id, "FAIL", JsonObject(observation.facts))
            throw failure
        } catch (failure: Throwable) {
            observation.facts["sanitized_error_type"] = JsonPrimitive(failure.javaClass.simpleName)
            event("slice", id, "ERROR", JsonObject(observation.facts))
            throw failure
        }
    }

    fun control(id: String, valid: Boolean, facts: JsonObject = buildJsonObject {}) {
        event("control", id, if (valid) "READY" else "INVALID_CONTROL", facts)
        if (!valid) throw AuthV1ControlFailure()
    }

    fun requireAllSlices() { check(completed == slices.keys) { "Executor did not visit the declared slices" } }

    fun prepareRetirementDiagnostic(fixture: String): AuthV1RetirementDiagnostic =
        AuthV1RetirementDiagnostic.prepare(run, invocation, fixture)

    override fun close() { channel.close() }
}

/** Last executor boundary only, never proof of request dispatch, COMMIT or active kernel work. */
internal enum class AuthV1RetirementCut {
    BEFORE_SERVICE_OPEN, AFTER_SERVICE_OPEN_RETURN,
    BEFORE_REVOKE_DEVICE_CALL, AFTER_REVOKE_DEVICE_RETURN,
    BEFORE_QUIESCE_AND_CLOSE, AFTER_QUIESCE_AND_CLOSE_RETURN,
}

/**
 * Fixed per-fixture process diagnostic. Initialization is forced BEFORE service startup.
 * The retirement callback must never write/force/lock the sequential observation journal.
 * Mapped stores below have no explicit blocking I/O; OS page residency/latency is not qualified.
 * The callback update is NOT forced: only a complete, validated post-reap slot is diagnostic,
 * never durable power-loss/business evidence, an inferred cause from silence, or a pass oracle.
 */
internal class AuthV1RetirementDiagnostic private constructor(private val slot: MappedByteBuffer) {
    private val claimed = AtomicBoolean(false)
    @Volatile private var currentCut = AuthV1RetirementCut.BEFORE_SERVICE_OPEN.ordinal + 1

    fun markCut(cut: AuthV1RetirementCut) { currentCut = cut.ordinal + 1 }

    fun record(reason: LocalMasterRetirementReason) {
        if (!claimed.compareAndSet(false, true)) return
        val cut = currentCut
        slot.putInt(16, 1) // Callback entered; 0 is the pre-start unobserved sentinel.
        slot.putInt(20, reason.ordinal + 1) // Version 1 binds the archived production enum order.
        slot.putInt(24, cut)
        VarHandle.releaseFence()
        slot.putInt(28, 1) // Publish last; partial/invalid records remain UNKNOWN.
    }

    companion object {
        private const val SLOT_BYTES = 256

        fun prepare(run: Path, invocation: String, fixture: String): AuthV1RetirementDiagnostic {
            require(invocation.matches(Regex("[0-9a-f]{32}")))
            require(fixture.matches(Regex("[a-z0-9-]{1,128}")))
            val fixtureBytes = fixture.toByteArray(Charsets.US_ASCII)
            // Big-endian fixed layout: magic/version/size; four mutable ints at 16..31;
            // invocation at 32..63; fixture byte length at 64..67, then ASCII and zero padding.
            val initial = ByteBuffer.allocate(SLOT_BYTES)
            initial.put("AUTH75V1".toByteArray(Charsets.US_ASCII))
            initial.putInt(1)
            initial.putInt(SLOT_BYTES)
            repeat(4) { initial.putInt(0) }
            initial.put(invocation.toByteArray(Charsets.US_ASCII))
            initial.putInt(fixtureBytes.size)
            initial.put(fixtureBytes)
            initial.position(0)
            return FileChannel.open(run.resolve("auth-retirement-$fixture.bin"), CREATE_NEW, READ, WRITE).use { channel ->
                while (initial.hasRemaining()) channel.write(initial)
                channel.force(true)
                val mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, SLOT_BYTES.toLong())
                // Touch the full small mapping and initialize the fence path before any callback.
                for (offset in 0 until SLOT_BYTES step 4) mapped.putInt(offset, mapped.getInt(offset))
                VarHandle.releaseFence()
                mapped.force()
                AuthV1RetirementDiagnostic(mapped)
            }
        }
    }
}

internal class AuthV1Slice {
    val facts: MutableMap<String, JsonElement> = linkedMapOf()
    fun observe(name: String, value: String) { facts[name] = JsonPrimitive(value) }
    fun observe(name: String, value: JsonElement) { facts[name] = value }
    fun expect(name: String, value: Boolean) {
        facts[name] = JsonPrimitive(value)
        if (!value) throw AuthV1Mismatch()
    }
}

internal class AuthV1Mismatch : RuntimeException("Frozen assertion slice did not hold; see redacted observations")
internal class AuthV1ControlFailure : RuntimeException("Positive control or fixture unavailable; no acceptance result")

internal fun authV1Sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun authV1WriteNew(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
}

/**
 * Genuine new KSP/Room database, not a schema JSON reconstruction or restored archive.
 * Calls the actual generated Room table/index/FK validator and actual production
 * trigger validators. These are observed production checks, NOT an independent
 * compatibility approval. The hash must never populate an accepted schema profile.
 */
internal suspend fun authV1ObserveFreshSchema(path: Path, run: Path, journal: AuthV1Journal) {
    check(!Files.exists(path)) { "Schema probe requires a never-created disposable database" }
    lateinit var database: MasterRoomDatabase
    var captured = false
    val observer = object : RoomDatabase.Callback() {
        override suspend fun onOpen(connection: SQLiteConnection) {
            check(!captured) { "Unexpected second connection in fresh schema probe" }
            // Test-only reflection exposes the protected generated delegate, not a copied
            // hand-maintained schema oracle. No source or stored identity is modified.
            val factory = database.javaClass.getDeclaredMethod("createOpenDelegate")
            check(factory.trySetAccessible()) { "Generated Room validator is unavailable" }
            val delegate = factory.invoke(database) as RoomOpenDelegate
            val validation = delegate.onValidateSchema(connection)
            journal.control("fresh-schema-generated-room-validator", validation.isValid, buildJsonObject {
                put("validator", "actual generated MasterRoomDatabase createOpenDelegate/onValidateSchema")
                put("access", "TEST_ONLY_REFLECTION_NO_SCHEMA_ORACLE_COPY")
                put("compatibility_approval", false)
            })
            val callbacks = listOf(
                "ImmutableFactsCallback" to ImmutableFactsCallback,
                "ProtectedLocalSchemaCallback" to ProtectedLocalSchemaCallback,
                "LocalAccessIssuanceSchemaCallback" to LocalAccessIssuanceSchemaCallback,
                "MasterReplacementGuardsCallback" to MasterReplacementGuardsCallback,
                "LocalAuthenticationSchemaCallback" to LocalAuthenticationSchemaCallback,
            )
            for ((name, callback) in callbacks) {
                callback.onOpen(connection)
                journal.event("schema_validator", name, "RETURNED_NORMALLY", buildJsonObject {
                    put("actual_production_callback", true)
                    put("compatibility_approval", false)
                })
            }
            val canonical = ByteArrayOutputStream()
            canonical.write("ukkera.sqlite-main-schema/v1\u0000".toByteArray(Charsets.US_ASCII))
            val objects = mutableListOf<JsonElement>()
            connection.prepare("SELECT type,name,tbl_name,sql FROM main.sqlite_master " +
                "ORDER BY type COLLATE BINARY,name COLLATE BINARY").use { statement ->
                while (statement.step()) {
                    check(objects.size < 1024) { "Schema observation row budget exceeded" }
                    val storageTypes = mutableListOf<JsonElement>()
                    for (column in 0..3) {
                        val type = statement.getColumnType(column)
                        val bytes = when (type) {
                            1 -> ByteBuffer.allocate(8).putLong(statement.getLong(column)).array()
                            2 -> ByteBuffer.allocate(8).putLong(statement.getDouble(column).toRawBits()).array()
                            // Raw blob projection: TEXT is never lossy decoded/re-encoded in the hash.
                            3, 4 -> statement.getBlob(column)
                            5 -> ByteArray(0)
                            else -> error("Unsupported SQLite storage type")
                        }
                        check(bytes.size <= 4 * 1024 * 1024 && canonical.size() + bytes.size + 5 <= 16 * 1024 * 1024)
                        canonical.write(type)
                        canonical.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                        canonical.write(bytes)
                        storageTypes.add(JsonPrimitive(type))
                    }
                    objects.add(buildJsonObject {
                        put("type", statement.getText(0))
                        put("name", statement.getText(1))
                        put("table", statement.getText(2))
                        put("sql", if (statement.getColumnType(3) == 5) JsonNull else JsonPrimitive(statement.getText(3)))
                        put("storage_types", JsonArray(storageTypes))
                    })
                }
            }
            val bytes = canonical.toByteArray()
            val schemaVersion = connection.prepare("PRAGMA main.user_version").use {
                check(it.step()); it.getLong(0)
            }
            val sqliteVersion = connection.prepare("SELECT sqlite_version()").use {
                check(it.step()); it.getText(0)
            }
            val identity = connection.prepare("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                check(it.step()); it.getText(0)
            }
            val profile = buildJsonObject {
                put("journal_mode", connection.prepare("PRAGMA main.journal_mode").use { check(it.step()); it.getText(0) })
                put("synchronous", connection.prepare("PRAGMA main.synchronous").use { check(it.step()); it.getLong(0) })
                put("foreign_keys", connection.prepare("PRAGMA foreign_keys").use { check(it.step()); it.getLong(0) })
            }
            authV1WriteNew(run.resolve("fresh-main-schema.canonical.bin"), bytes)
            authV1WriteNew(run.resolve("fresh-main-schema.json"), buildJsonObject {
                put("schema", "independent-fresh-sqlite-schema-observation/v1")
                put("provenance", "NEW_EMPTY_REAL_ROOM_DATABASE_NOT_ARCHIVE_NOT_RESTORE")
                put("schema_version", schemaVersion)
                put("sqlite_version", sqliteVersion)
                put("room_identity_hash", identity)
                put("algorithm", "ukkera.sqlite-main-schema/v1")
                put("sha256", authV1Sha256(bytes))
                put("canonical_bytes", bytes.size)
                put("objects", JsonArray(objects))
                put("writer_profile", profile)
                put("compatibility_approval", false)
                put("observed_hash_is_accepted_backup_pin", false)
            }.toString().toByteArray(Charsets.UTF_8))
            captured = true
        }
    }
    database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(),
        MasterRoomConstructor::initialize).addCallback(observer))
    // Lazy Room open runs the full installed callback chain before this loan is returned.
    // Any newer production callback added to buildMasterRoom runs here as well; the
    // explicit list above claims only its named observations, not a copied total.
    try {
        database.useWriterConnection { requireDurableWriter(it) }
        check(captured)
        journal.event("schema_probe", "fresh-schema-full-installed-open", "RETURNED_NORMALLY")
    } finally {
        // Never open a replacement pool after an uncertain close. The outer process
        // deadline/reaper handles a blocked close; an exception aborts this executor.
        database.close()
    }
    journal.event("schema_probe", "fresh-schema-close", "RETURNED_NORMALLY")
}
