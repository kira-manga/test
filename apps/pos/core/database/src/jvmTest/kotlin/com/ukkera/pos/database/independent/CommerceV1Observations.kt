package com.ukkera.pos.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.CashNonSaleStoreProfile
import com.ukkera.pos.sales.store.SalesStorageRevision
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

/**
 * Post-freeze executor instrumentation, not a business service or acceptance oracle.
 * Every observed business row comes from a NEW production-driver connection to the
 * real file. No security tables, passwords, tokens or key material are exported.
 * Nothing here makes a whole assertion, case, platform or scenario PASS.
 */
internal class CommerceV1Observations(private val directory: Path) {
    private val events: Path = directory.resolve("events.jsonl")
    private var ordinal = 0

    init {
        Files.createDirectories(directory)
        Files.createFile(events)
    }

    @Synchronized
    fun event(kind: String, body: JsonObject = buildJsonObject {}): Unit {
        val line = buildJsonObject {
            put("ordinal", ++ordinal)
            put("kind", kind)
            put("body", body)
        }.toString().plus("\n").toByteArray(Charsets.UTF_8)
        FileChannel.open(events, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line)
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    fun save(name: String, value: JsonObject): String {
        require(name.matches(Regex("[a-zA-Z0-9._-]+\\.json")))
        val file = directory.resolve(name)
        val bytes = value.toString().plus("\n").toByteArray(Charsets.UTF_8)
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        // The runner profile is Linux/JVM; this does not qualify other platforms.
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        return file.fileName.toString()
    }

    fun stage(label: String, actorAlias: String, request: MutationRequest): String = save(
        "$label.intent.json",
        buildJsonObject {
            put("purpose", "Original operation identity/body staged before dispatch; synthetic business data only")
            put("actor_alias", actorAlias)
            put("operation_id", request.operationId.value)
            put("command", request.command.name)
            put("command_version", request.command.version)
            put("payload_sha256", commerceV1Hash(request.payload.copyBytes()))
            put("payload_base64", Base64.getEncoder().encodeToString(request.payload.copyBytes()))
            put("payload_utf8", request.payload.copyBytes().decodeToString(throwOnInvalidSequence = true))
        },
    )

    fun snapshot(name: String, database: Path, scope: BranchScope): String = save(name, commerceV1Snapshot(database, scope))
}

internal fun commerceV1Hash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun commerceV1JsonBytes(bytes: ByteArray): JsonElement = try {
    Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
} catch (_: Exception) {
    buildJsonObject {
        put("encoding", "base64")
        put("value", Base64.getEncoder().encodeToString(bytes))
    }
}

/** Raw production DTO observations; no success is inferred from these projections. */
internal fun commerceV2Reply(reply: MutationReply): JsonObject = when (reply) {
    is MutationReply.Recorded -> buildJsonObject {
        put("type", "Recorded"); put("committed", reply.committed); put("code", reply.code)
        put("original_actor_id", reply.originalActorId.value); put("operation_id", reply.operationId.value)
        put("authority_epoch", reply.authorityEpoch.value.toString()); put("sequence", reply.sequence.value.toString())
        put("recorded_at", reply.recordedAt.canonicalString()); put("replayed", reply.replayed)
        put("result", commerceV1JsonBytes(reply.result.copyBytes())); put("result_sha256", commerceV1Hash(reply.result.copyBytes()))
    }
    is MutationReply.Deferred -> buildJsonObject { put("type", "Deferred"); put("reason", reply.reason.name) }
    is MutationReply.IdentityConflict -> buildJsonObject { put("type", "IdentityConflict") }
}

internal fun commerceV2Lookup(reply: LookupReply): JsonObject = when (reply) {
    is LookupReply.Found -> buildJsonObject { put("type", "Found"); put("result", commerceV2Reply(reply.result)) }
    is LookupReply.AbsentAtCurrentWriter -> buildJsonObject { put("type", "AbsentAtCurrentWriter"); put("operation_id", reply.operationId.value) }
    is LookupReply.Deferred -> buildJsonObject { put("type", "Deferred"); put("reason", reply.reason.name) }
    is LookupReply.IdentityConflict -> buildJsonObject { put("type", "IdentityConflict") }
}

internal fun commerceV2Read(reply: ReadReply): JsonObject = when (reply) {
    is ReadReply.Data -> buildJsonObject {
        put("type", "Data"); put("branch_revision", reply.branchRevision.value.toString())
        put("payload", commerceV1JsonBytes(reply.payload.copyBytes()))
    }
    is ReadReply.Unavailable -> buildJsonObject { put("type", "Unavailable"); put("reason", reply.reason.name) }
}

private fun SQLiteConnection.rows(
    sql: String,
    scope: String,
    observe: (SQLiteStatement) -> JsonObject,
): JsonArray = prepare(sql).use { statement ->
    statement.bindText(1, scope)
    buildJsonArray {
        var count = 0
        while (statement.step()) {
            check(++count <= 4096) { "commerce.snapshot.capacity" }
            add(observe(statement))
        }
    }
}

/** SQL observation only. The serialized production payload is retained, not replaced by a model. */
internal fun commerceV1Snapshot(database: Path, scope: BranchScope): JsonObject {
    check(Files.isRegularFile(database) && !Files.isSymbolicLink(database)) { "commerce.database.missing" }
    val key = scope.storageKey()
    return BundledSQLiteDriver().open(database.toString()).use { connection ->
        connection.prepare("PRAGMA query_only=ON").use { it.step() }
        connection.prepare("BEGIN DEFERRED TRANSACTION").use { it.step() }
        try {
            val aggregates = connection.rows(
                "SELECT kind,id,revision,schema,payload,payloadHash FROM aggregate_state WHERE scope=? ORDER BY kind,id", key,
            ) { row ->
                val bytes = row.getBlob(4)
                buildJsonObject {
                    put("kind", row.getText(0)); put("id", row.getText(1)); put("storage_revision", row.getLong(2).toString())
                    put("schema", row.getText(3)); put("payload_hash", row.getText(5))
                    put("observed_payload_hash", commerceV1Hash(bytes)); put("payload", commerceV1JsonBytes(bytes))
                }
            }
            val cash = connection.rows(
                "SELECT id,revision,payload FROM aggregate_state WHERE scope=? AND kind='${CashNonSaleStoreProfile.SESSION_KIND}' ORDER BY id", key,
            ) { row ->
                val session = SalesSnapshotCodec.decodeCashSession(
                    row.getBlob(2).decodeToString(throwOnInvalidSequence = true),
                    SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, scope, DomainId(row.getText(0)),
                        SalesStorageRevision.toDomain(Revision(row.getLong(1)))),
                )
                buildJsonObject {
                    put("id", session.id.value); put("drawer_id", session.drawerId.value)
                    put("custodian_id", session.custodianId.value); put("status", session.status.name)
                    put("opening_source_id", session.openingSourceId.value)
                    put("currency", session.opening.amount.currency.name)
                    put("opening_minor", session.opening.amount.minor.toString())
                    put("expected_cash_minor", session.expectedCash.minor.toString())
                    put("received_minor", session.totals.received.minor.toString())
                    put("change_minor", session.totals.change.minor.toString())
                    put("refunds_minor", session.totals.refunds.minor.toString())
                    put("pay_ins_minor", session.totals.payIns.minor.toString())
                    put("pay_outs_minor", session.totals.payOuts.minor.toString())
                    put("safe_drops_minor", session.totals.safeDrops.minor.toString())
                    put("external_collections_minor", session.totals.externalCollections.minor.toString())
                    put("external_refunds_minor", session.totals.externalRefunds.minor.toString())
                    put("raw_payload_sha256", commerceV1Hash(row.getBlob(2)))
                }
            }
            val claims = connection.rows(
                "SELECT namespace,`key`,ownerId FROM unique_claim WHERE scope=? ORDER BY namespace,`key`", key,
            ) { row -> buildJsonObject {
                put("namespace", row.getText(0)); put("key", row.getText(1)); put("owner_id", row.getText(2))
            } }
            val operations = connection.rows(
                "SELECT operationId,actorId,command,commandVersion,digest,outcome,result,resultHash,epoch,sequence,committedAt FROM operation_result WHERE scope=? ORDER BY epoch,sequence", key,
            ) { row -> buildJsonObject {
                put("operation_id", row.getText(0)); put("actor_id", row.getText(1)); put("command", row.getText(2))
                put("command_version", row.getLong(3)); put("digest", row.getText(4)); put("outcome", row.getText(5))
                put("result", commerceV1JsonBytes(row.getBlob(6))); put("result_hash", row.getText(7))
                put("epoch", row.getLong(8).toString()); put("sequence", row.getLong(9).toString()); put("committed_at", row.getText(10))
            } }
            val journals = connection.rows(
                "SELECT operationId,epoch,sequence,assignmentId,previousHash,hash,envelope FROM journal_envelope WHERE scope=? ORDER BY epoch,sequence", key,
            ) { row -> buildJsonObject {
                put("operation_id", row.getText(0)); put("epoch", row.getLong(1).toString()); put("sequence", row.getLong(2).toString())
                put("assignment_id", row.getText(3)); put("previous_hash", row.getText(4)); put("hash", row.getText(5))
                put("envelope", commerceV1JsonBytes(row.getBlob(6)))
            } }
            val audits = connection.rows(
                "SELECT id,operationId,kind,actorId,originalActorId,policyRevision,recordedAt,digest FROM audit_event WHERE scope=? ORDER BY id", key,
            ) { row -> buildJsonObject {
                put("id", row.getText(0)); put("operation_id", row.getText(1)); put("kind", row.getText(2))
                put("actor_id", row.getText(3)); put("original_actor_id", row.getText(4))
                put("policy_revision", row.getLong(5).toString()); put("recorded_at", row.getText(6)); put("digest", row.getText(7))
            } }
            val outbox = connection.rows(
                "SELECT operationId,ordinal,kind,body,bodyHash FROM outbox_intent WHERE scope=? ORDER BY operationId,ordinal", key,
            ) { row -> buildJsonObject {
                put("operation_id", row.getText(0)); put("ordinal", row.getLong(1)); put("kind", row.getText(2))
                put("body", commerceV1JsonBytes(row.getBlob(3))); put("body_hash", row.getText(4))
            } }
            buildJsonObject {
                put("schema", "commerce-v1-fresh-driver-observation/1")
                put("connection", "NEW BundledSQLiteDriver connection; query_only; one explicit read transaction")
                put("database_file", database.fileName.toString()); put("scope", key)
                put("aggregates", aggregates); put("cash_sessions", cash); put("claims", claims)
                put("operations", operations); put("journals", journals); put("audits", audits); put("outbox", outbox)
                put("security_rows_exported", false)
            }
        } finally {
            connection.prepare("ROLLBACK").use { it.step() }
        }
    }
}
