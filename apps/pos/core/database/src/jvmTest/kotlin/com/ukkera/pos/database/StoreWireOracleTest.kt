package com.ukkera.pos.database

import androidx.room3.PooledConnection
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Independent JVM oracle for the LOCAL binary command/journal/upload framing.
 * This is NOT the backend's signed evidence-v1 wire protocol or signature proof.
 * Fixtures, trust adapters and the claim-only handler are SYNTHETIC; no cash or
 * physical custody event is inferred from these test records.
 */
class StoreWireOracleTest {
    @Test fun explicitCanonicalCounterRequestHasFrozenIndependentCommandDigest() {
        val expected = oracleCommands().first()
        val handler = CounterHandler()
        val normalized = handler.normalize(StoredBytes(oracleUtf8(NOISY_COUNTER_JSON)))
        assertContentEquals(oracleUtf8(APPLY_JSON), normalized.copyBytes())
        assertContentEquals(normalized.copyBytes(), handler.normalize(StoredBytes(oracleUtf8(APPLY_JSON))).copyBytes())
        assertEquals("ae9064d46ce2239c98141af40d8ac628fc1b69fcee2fb09367bba67668df2c16", expected.digest)
        assertEquals(expected.digest, semanticDigest(TEST_SCOPE, TEST_ACTOR, handler.key, normalized).value)
        // Raw whitespace/key order/default spelling is not the semantic preimage.
        assertNotEquals(expected.digest, oracleSha256(oracleFrame(oracleTextFields(
            "ukkera.command/v1", WIRE_SCOPE, WIRE_ACTOR, "qualification.counter", "1",
        ) + oracleUtf8(NOISY_COUNTER_JSON))))
    }

    @Test fun rawCommitRejectAndClaimOnlyRowsMatchIndependentGoldenHashChain() = runTest {
        val f = StoreFixture()
        try {
            f.seed()
            val expected = oracleCommands()
            f.handler.extraJobBytes = expected[0].jobs.single().body.copyOf()
            val claimOnly = syntheticClaimOnlyHandler(f.handler, expected[2])
            val store = AtomicMasterStore(f.database, TEST_SCOPE, TEST_AUTHORITY, listOf(f.handler, claimOnly),
                f.auth, f.runtime, MasterClock { TEST_TIME })
            val principal = testPrincipal()
            val requests = expected.mapIndexed { index, command -> command.request(if (index == 0) NOISY_COUNTER_JSON else command.normalized) }
            var previous = "0".repeat(64)
            for ((index, command) in expected.withIndex()) {
                val sequence = index + 1L
                val envelope = command.envelope(sequence, previous)
                val envelopeHash = oracleSha256(envelope)
                assertEquals(GOLDEN_JOURNAL_HASHES[index], envelopeHash, "Independent oracle must itself match the frozen vector")
                assertEquals(listOf(912, 705, 850)[index], envelope.size)
                val reply = assertIs<MutationReply.Recorded>(store.execute(principal, requests[index]))
                assertEquals(command.operationId, reply.operationId.value)
                assertEquals(command.outcome == "committed", reply.committed)
                assertEquals(command.outcome, reply.code)
                assertEquals(WIRE_ACTOR, reply.originalActorId.value)
                assertEquals(1L, reply.authorityEpoch.value)
                assertEquals(sequence, reply.sequence.value)
                assertEquals(WIRE_TIME, reply.recordedAt.toString())
                assertFalse(reply.replayed)
                assertContentEquals(command.result, reply.result.copyBytes())
                f.assertOracleRows(command, sequence, previous, envelope, envelopeHash)
                // The rejection contributes an empty effect/job list to the chain;
                // claim-only contributes exactly one release, not a fabricated ledger effect.
                val fields = oracleDecode(envelope)
                assertEquals(20, fields.size)
                assertEquals(listOf(2, 0, 1)[index], oracleDecode(fields[18]).size)
                assertEquals(listOf(1, 0, 1)[index], oracleDecode(fields[19]).size)
                previous = envelopeHash
            }
            assertEquals(3, f.handler.prepareCount)
            for ((index, request) in requests.withIndex()) {
                val replay = assertIs<MutationReply.Recorded>(store.execute(principal, request))
                assertTrue(replay.replayed)
                assertEquals(index + 1L, replay.sequence.value)
                assertContentEquals(expected[index].result, replay.result.copyBytes())
            }
            assertEquals(3, f.handler.prepareCount)
            assertEquals(3L, f.count("operation_result"))
            assertEquals(3L, f.count("journal_envelope"))
            assertEquals(3L, f.count("audit_event"))
            assertEquals(5L, f.count("outbox_intent"))
            assertEquals(0L, f.count("unique_claim"))
        } finally { f.close() }
    }

    @Test fun framingGoldenUsesBigEndianByteLengthsAndDistinguishesNulAndEmptyFields() {
        val fields = listOf(oracleUtf8("A\u0000B"), byteArrayOf(), oracleUtf8("ع"), byteArrayOf(-1, 0, 10))
        val golden = HexFormat.of().parseHex("000000034100420000000000000002d8b900000003ff000a")
        assertContentEquals(golden, oracleFrame(fields))
        val actual = frame(fields) // Production encoder is the subject, never the oracle.
        assertContentEquals(golden, actual)
        assertEquals(fields.map(::oracleHex), oracleDecode(actual).map(::oracleHex))
        assertContentEquals(byteArrayOf(), frame(emptyList()))
        assertContentEquals(byteArrayOf(0, 0, 0, 0), frame(listOf(byteArrayOf())))

        val left = listOf(oracleUtf8("a\u0000"), oracleUtf8("b"))
        val right = listOf(oracleUtf8("a"), oracleUtf8("\u0000b"))
        assertContentEquals(left[0] + left[1], right[0] + right[1])
        assertFalse(frame(left).contentEquals(frame(right)), "NUL cannot act as an ambiguous delimiter")
        assertContentEquals(oracleFrame(left), frame(left))
        assertContentEquals(oracleFrame(right), frame(right))
    }

    @Test fun byteAndFieldLimitsIncludeLengthPrefixesAndRejectTheFirstExcessByte() {
        val maximumFields = List(1_024) { byteArrayOf() }
        assertContentEquals(oracleFrame(maximumFields), frame(maximumFields))
        assertFailsWith<IllegalArgumentException> { frame(List(1_025) { byteArrayOf() }) }
        val maximumPayload = ByteArray(4_194_300) { (it % 251).toByte() }
        val maximumFrame = frame(listOf(maximumPayload))
        assertEquals(4_194_304, maximumFrame.size)
        assertContentEquals(oracleFrame(listOf(maximumPayload)), maximumFrame)
        assertEquals(4_194_300, ByteBuffer.wrap(maximumFrame).order(ByteOrder.BIG_ENDIAN).int)
        assertFailsWith<IllegalArgumentException> { frame(listOf(ByteArray(4_194_301))) }
        // A second empty field still costs its four-byte prefix.
        assertFailsWith<IllegalArgumentException> { frame(listOf(maximumPayload, byteArrayOf())) }
        val maximumStored = ByteArray(65_536) { (it % 256).toByte() }
        assertContentEquals(maximumStored, StoredBytes(maximumStored).copyBytes())
        assertFailsWith<IllegalArgumentException> { StoredBytes(ByteArray(65_537)) }
    }
}

private const val WIRE_SCOPE = "00000000-0000-4000-8000-000000000001/00000000-0000-4000-8000-000000000002/00000000-0000-4000-8000-000000000003"
private const val WIRE_BRANCH = "00000000-0000-4000-8000-000000000003"
private const val WIRE_ASSIGNMENT = "00000000-0000-4000-8000-000000000004"
private const val WIRE_DEVICE = "00000000-0000-4000-8000-000000000005"
private const val WIRE_ACTOR = "00000000-0000-4000-8000-000000000006"
private const val WIRE_AUTHENTICATION = "00000000-0000-4000-8000-000000000007"
private const val WIRE_RESOURCE = "00000000-0000-4000-8000-000000000701"
private const val WIRE_TIME = "2026-10-23T12:00:00Z"
private const val APPLY_JSON = """{"id":"00000000-0000-4000-8000-000000000701","delta":"7","claim":"wire-custody"}"""
private const val REJECT_JSON = """{"id":"00000000-0000-4000-8000-000000000701","expectedRevision":"1","delta":"-8"}"""
private const val CLAIM_JSON = """{"id":"00000000-0000-4000-8000-000000000701","expectedRevision":"1","claim":"wire-custody"}"""
private const val NOISY_COUNTER_JSON = """ { "claim": "wire-custody", "reject": false, "delta": "7", "deadline": null, "id": "00000000-0000-4000-8000-000000000701", "expectedRevision": null } """
private val GOLDEN_JOURNAL_HASHES = listOf(
    "97641ecc47966742fe17681d0e21e86caa6cbbe7fa0620cce21cd4712148ad2c",
    "ea381fd9ed4b77269e6935caa8d688153165755c12c871b3ba3274c395977c30",
    "b660cb1ce1d139b3a399e198d3d71ea6813fddc750c42a4778fd5c9ff3e127d5",
)

private data class OracleJob(val kind: String, val body: ByteArray)

private data class OracleCommand(
    val operationId: String,
    val command: String,
    val version: Int,
    val normalized: String,
    val outcome: String,
    val result: ByteArray,
    val effects: List<ByteArray>,
    val jobs: List<OracleJob>,
) {
    val digest: String get() = oracleSha256(oracleFrame(oracleTextFields(
        "ukkera.command/v1", WIRE_SCOPE, WIRE_ACTOR, command, version.toString(),
    ) + oracleUtf8(normalized)))

    fun request(payload: String): MutationRequest = MutationRequest(OperationId(operationId), CommandKey(command, version), StoredBytes(oracleUtf8(payload)))

    fun envelope(sequence: Long, previousHash: String): ByteArray = oracleFrame(oracleTextFields(
        "ukkera.journal/v1", WIRE_SCOPE, WIRE_ASSIGNMENT, "1", sequence.toString(), operationId, WIRE_ACTOR,
        command, version.toString(), WIRE_AUTHENTICATION, "1", digest, outcome, WIRE_TIME, oracleSha256(result), previousHash,
    ) + listOf(oracleUtf8(normalized), result, oracleFrame(effects), oracleFrame(jobs.map {
        oracleFrame(listOf(oracleUtf8(it.kind), it.body))
    })))
}

private fun oracleCommands(): List<OracleCommand> = listOf(
    OracleCommand("00000000-0000-4000-8000-000000000711", "qualification.counter", 1, APPLY_JSON, "committed", oracleUtf8("7"),
        listOf(
            oracleFrame(oracleTextFields("qualification.counter", WIRE_RESOURCE, "1", "counter/v1") + oracleUtf8("7")),
            oracleFrame(oracleTextFields("claim.acquire", "qualification.custody", "wire-custody", WIRE_RESOURCE)),
        ), listOf(OracleJob("qualification.receipt", byteArrayOf(0x52, 0, 0xc3.toByte(), 0xa9.toByte(), 10, -1)))),
    OracleCommand("00000000-0000-4000-8000-000000000712", "qualification.counter", 1, REJECT_JSON, "stock.insufficient",
        oracleUtf8("stock.insufficient"), emptyList(), emptyList()),
    OracleCommand("00000000-0000-4000-8000-000000000713", "qualification.claim-only", 3, CLAIM_JSON, "committed", oracleUtf8("released\u0000wire\n"),
        listOf(oracleFrame(oracleTextFields("claim.release", "qualification.custody", "wire-custody", WIRE_RESOURCE))),
        listOf(OracleJob("qualification.custody-notice", byteArrayOf(0, -1, 10)))),
)

/** Synthetic local claim release only: no aggregate change and no cash movement. */
private fun syntheticClaimOnlyHandler(counter: CounterHandler, expected: OracleCommand): MasterCommandHandler = object : MasterCommandHandler by counter {
    override val key = CommandKey("qualification.claim-only", 3)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val prepared = counter.prepare(normalized, view, context)
        return object : PreparedMutation() {
            override val requirement = prepared.requirement
            override val affectedResources = prepared.affectedResources
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome = ProposedOutcome.Apply(
                StoredBytes(expected.result), emptyList(),
                listOf(ClaimChange.Release(ClaimKey("qualification.custody", "wire-custody"), DomainId(WIRE_RESOURCE))),
                expected.jobs.map { JobIntent(it.kind, StoredBytes(it.body)) },
            )
        }
    }
}

private suspend fun StoreFixture.assertOracleRows(command: OracleCommand, sequence: Long, previous: String, envelope: ByteArray, envelopeHash: String) {
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        val identity = listOf(WIRE_SCOPE, command.operationId)
        oracleAssertRows(connection,
            "SELECT scope,operationId,actorId,command,commandVersion,digest,outcome,result,resultHash,sequence,epoch,committedAt,authenticationId,policyRevision FROM operation_result WHERE scope=? AND operationId=?",
            identity, listOf(listOf(WIRE_SCOPE, command.operationId, WIRE_ACTOR, command.command, command.version.toLong(), command.digest,
                command.outcome, command.result, oracleSha256(command.result), sequence, 1L, WIRE_TIME, WIRE_AUTHENTICATION, 1L)))
        oracleAssertRows(connection,
            "SELECT scope,sequence,operationId,assignmentId,epoch,previousHash,hash,envelope FROM journal_envelope WHERE scope=? AND operationId=?",
            identity, listOf(listOf(WIRE_SCOPE, sequence, command.operationId, WIRE_ASSIGNMENT, 1L, previous, envelopeHash, envelope)))
        oracleAssertRows(connection,
            "SELECT scope,operationId,kind,actorId,originalActorId,policyRevision,recordedAt,digest FROM audit_event WHERE scope=? AND operationId=?",
            identity, listOf(listOf(WIRE_SCOPE, command.operationId, "command.${command.outcome}", WIRE_ACTOR, WIRE_ACTOR, 1L, WIRE_TIME, command.digest)))
        val upload = oracleFrame(oracleTextFields("ukkera.journal-upload/v1", WIRE_BRANCH, "1", sequence.toString(), envelopeHash))
        oracleAssertRows(connection,
            "SELECT scope,operationId,ordinal,kind,body,bodyHash FROM outbox_intent WHERE scope=? AND operationId=? ORDER BY ordinal",
            identity, listOf(listOf(WIRE_SCOPE, command.operationId, 0L, "journal.upload", upload, oracleSha256(upload))) + command.jobs.mapIndexed { index, job ->
                listOf(WIRE_SCOPE, command.operationId, index + 1L, job.kind, job.body, oracleSha256(job.body))
            })
        oracleAssertRows(connection,
            "SELECT scope,assignmentId,deviceId,epoch,revision,sequence,journalHash FROM branch_authority WHERE scope=?",
            listOf(WIRE_SCOPE), listOf(listOf(WIRE_SCOPE, WIRE_ASSIGNMENT, WIRE_DEVICE, 1L, sequence, sequence, envelopeHash)))
        // Neither the rejection nor the claim-only release may fabricate/change this aggregate.
        oracleAssertRows(connection,
            "SELECT scope,kind,id,revision,schema,payload,payloadHash FROM aggregate_state WHERE scope=? AND kind=? AND id=?",
            listOf(WIRE_SCOPE, "qualification.counter", WIRE_RESOURCE),
            listOf(listOf(WIRE_SCOPE, "qualification.counter", WIRE_RESOURCE, 1L, "counter/v1", oracleUtf8("7"), oracleSha256(oracleUtf8("7")))))
        oracleAssertRows(connection,
            "SELECT scope,namespace,`key`,ownerId FROM unique_claim WHERE scope=? AND namespace=? AND `key`=?",
            listOf(WIRE_SCOPE, "qualification.custody", "wire-custody"),
            if (sequence == 3L) emptyList() else listOf(listOf(WIRE_SCOPE, "qualification.custody", "wire-custody", WIRE_RESOURCE)))
        // Additive decoder oracle: the unchanged production encoder and all three
        // independently frozen commit/reject/claim-only vectors remain subjects.
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val view = TransactionView(TEST_SCOPE, database.facts())
            try {
                val retained = assertNotNull(view.retainedCommand(OperationId(command.operationId)))
                assertEquals(TEST_SCOPE, retained.scope)
                assertEquals(command.operationId, retained.operationId.value)
                assertEquals(WIRE_ACTOR, retained.originalActorId.value)
                assertEquals(command.command, retained.command.name)
                assertEquals(command.version, retained.command.version)
                assertEquals(WIRE_ASSIGNMENT, retained.assignmentId.value)
                assertEquals(1L, retained.authorityEpoch.value)
                assertEquals(sequence, retained.sequence.value)
                assertEquals(WIRE_AUTHENTICATION, retained.authenticationId.value)
                assertEquals(1L, retained.policyRevision.value)
                assertEquals(WIRE_TIME, retained.recordedAt.toString())
                assertEquals(command.digest, retained.semanticDigest.value)
                assertEquals(command.outcome, retained.code)
                assertEquals(command.outcome == "committed", retained.committed)
                assertContentEquals(oracleUtf8(command.normalized), retained.normalized.copyBytes())
                assertContentEquals(command.result, retained.result.copyBytes())
                assertEquals(oracleSha256(command.result), retained.resultHash.value)
                assertEquals(previous, retained.previousJournalHash.value)
                assertEquals(envelopeHash, retained.journalHash.value)
                val effects = command.effects.map(::oracleDecode)
                val writes = effects.filter { it.size == 5 }
                val claims = effects.filter { it.size == 4 }
                assertEquals(writes.size, retained.aggregateWrites.size)
                writes.zip(retained.aggregateWrites).forEach { (fields, write) ->
                    assertContentEquals(fields[0], oracleUtf8(write.key.kind))
                    assertContentEquals(fields[1], oracleUtf8(write.key.id.value))
                    assertContentEquals(fields[2], oracleUtf8(write.storageGeneration.value.toString()))
                    assertContentEquals(fields[3], oracleUtf8(write.schema))
                    assertContentEquals(fields[4], write.payload.copyBytes())
                    assertEquals(oracleSha256(fields[4]), write.payloadDigest.value)
                }
                assertEquals(claims.size, retained.claimChanges.size)
                claims.zip(retained.claimChanges).forEach { (fields, claim) ->
                    assertContentEquals(fields[0], oracleUtf8(if (claim.action == RetainedClaimAction.ACQUIRE) "claim.acquire" else "claim.release"))
                    assertContentEquals(fields[1], oracleUtf8(claim.key.namespace))
                    assertContentEquals(fields[2], oracleUtf8(claim.key.key))
                    assertContentEquals(fields[3], oracleUtf8(claim.ownerId.value))
                }
                assertEquals(command.jobs.size, retained.jobs.size)
                command.jobs.zip(retained.jobs).forEach { (expected, actual) ->
                    assertEquals(expected.kind, actual.kind)
                    assertContentEquals(expected.body, actual.body.copyBytes())
                }
            } finally { view.close() }
        }
    }
}

private suspend fun oracleAssertRows(connection: PooledConnection, sql: String, bindings: List<String>, rows: List<List<Any>>) {
    connection.usePrepared(sql) { statement ->
        bindings.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
        for (row in rows) {
            assertTrue(statement.step(), "Missing expected raw row")
            assertEquals(row.size, statement.getColumnCount())
            row.forEachIndexed { column, expected -> oracleAssertCell(statement, column, expected) }
        }
        assertFalse(statement.step(), "Unexpected extra raw row")
    }
}

private fun oracleAssertCell(statement: SQLiteStatement, column: Int, expected: Any) {
    when (expected) {
        is ByteArray -> assertContentEquals(expected, statement.getBlob(column), "column $column")
        is Long -> assertEquals(expected, statement.getLong(column), "column $column")
        is String -> assertEquals(expected, statement.getText(column), "column $column")
        else -> error("Unsupported oracle cell")
    }
}

/** Deliberately not database.frame, core.security.sha256, or a copied bit-shift loop. */
private fun oracleFrame(fields: List<ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        for (field in fields) {
            output.writeInt(field.size) // JDK specifies four-byte big-endian lengths.
            output.write(field)
        }
    }
    return bytes.toByteArray()
}

private fun oracleDecode(bytes: ByteArray): List<ByteArray> {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return buildList {
        while (input.hasRemaining()) {
            require(input.remaining() >= 4)
            val length = input.int
            require(length >= 0 && length <= input.remaining())
            add(ByteArray(length).also { input.get(it) })
        }
    }
}

private fun oracleUtf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
private fun oracleTextFields(vararg values: String): List<ByteArray> = values.map(::oracleUtf8)
private fun oracleHex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
private fun oracleSha256(bytes: ByteArray): String = oracleHex(MessageDigest.getInstance("SHA-256").digest(bytes))
