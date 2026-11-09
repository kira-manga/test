package com.ukkera.pos.database

import com.ukkera.pos.inventory.CollectionExposure
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.inventory.store.InventoryRetailCompanionBinding
import com.ukkera.pos.inventory.store.InventoryRetailCompanionCodec
import com.ukkera.pos.inventory.store.InventoryRetailFixtureResultCodec
import com.ukkera.pos.inventory.store.InventoryRetailStoreProfile
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rebuilds a hypothetical pair with independent TEST framing, then makes the REAL kernel
 * decoder validate it. Never writes a database or claims a genuine command produced the mutant.
 */
internal fun checkoutEvidenceMutant(
    original: RetainedCommandEvidence,
    command: CommandKey = original.command,
    actor: DomainId = original.originalActorId,
    operation: OperationId = original.operationId,
    normalized: StoredBytes = original.normalized,
    result: StoredBytes = original.result,
    writes: List<RetainedAggregateWrite> = original.aggregateWrites,
    claims: List<RetainedClaimChange> = original.claimChanges,
    jobs: List<RetainedJobIntent> = original.jobs,
): RetainedCommandEvidence {
    val digest = semanticDigest(original.scope, actor, command, normalized)
    val resultHash = sha256(result.copyBytes())
    val row = OperationRow(original.scope.storageKey(), operation.value, actor.value, command.name, command.version,
        digest.value, original.code, result.copyBytes(), resultHash.value, original.sequence.value, original.authorityEpoch.value,
        original.recordedAt.toString(), original.authenticationId.value, original.policyRevision.value)
    val effects = writes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value })).map {
        retainedTestFrame(retainedTestTexts(it.key.kind, it.key.id.value, it.storageGeneration.value.toString(), it.schema) + it.payload.copyBytes())
    } + claims.sortedWith(compareBy({ it.key.namespace }, { it.key.key })).map {
        retainedTestFrame(retainedTestTexts(if (it.action == RetainedClaimAction.ACQUIRE) "claim.acquire" else "claim.release",
            it.key.namespace, it.key.key, it.ownerId.value))
    }
    val envelope = retainedTestFrame(retainedTestTexts("ukkera.journal/v1", row.scope, original.assignmentId.value,
        row.epoch.toString(), row.sequence.toString(), operation.value, actor.value, command.name, command.version.toString(),
        row.authenticationId, row.policyRevision.toString(), digest.value, row.outcome, row.committedAt, resultHash.value,
        original.previousJournalHash.value) + listOf(normalized.copyBytes(), result.copyBytes(), retainedTestFrame(effects),
        retainedTestFrame(jobs.map { retainedTestFrame(listOf(it.kind.encodeToByteArray(), it.body.copyBytes())) })))
    val journal = JournalRow(row.scope, row.sequence, operation.value, original.assignmentId.value, row.epoch,
        original.previousJournalHash.value, sha256(envelope).value, envelope)
    return assertNotNull(decodeRetainedCommand(original.scope, operation, row, journal))
}

internal fun RetainedAggregateWrite.checkoutMutant(
    key: AggregateKey = this.key,
    generation: Revision = storageGeneration,
    schema: String = this.schema,
    payload: StoredBytes = this.payload,
): RetainedAggregateWrite = RetainedAggregateWrite(key, generation, schema, payload, sha256(payload.copyBytes()))

internal fun checkoutJsonChange(bytes: StoredBytes, transform: (JsonObject) -> JsonObject): StoredBytes =
    StoredBytes(transform(Json.parseToJsonElement(bytes.copyBytes().decodeToString()).jsonObject).toString().encodeToByteArray())

internal fun JsonObject.checkoutField(key: String, value: JsonElement): JsonObject {
    require(key in this) { "Negative control must edit an existing field" }
    return JsonObject(toMutableMap().apply { this[key] = value })
}

/** Only this finite set of invalid CURRENT reservation links is forged in the disposable fixture. */
internal enum class CheckoutReservationCorruption { UNCOLLECTED, ALLOCATION_ID, ALLOCATION_QUANTITY, QUOTE_REVISION, ACTOR, ORDER, COLLECTION }

/**
 * ADVERSARIAL AFTER-FACT CONTROL, NOT valid staging or a supported import/command.
 * Change an old reservation under the latest genuine RECEIVE's coherent current/original AFTER
 * pair. A2's after-only receipt verifier cannot prove an absent reservation preimage. Rehash only
 * the latest selected pair and its head/upload; restore every dropped trigger before returning.
 * The test must prove genuine observe + closed producer validation succeed BEFORE checking the
 * live target gate, so an earlier provenance refusal cannot mask the guard under test.
 */
internal suspend fun CheckoutJourney.forgeLatestReceiptReservationForNegativeControl(control: CheckoutReservationCorruption) {
    val original = inventory()
    assertEquals(InventoryRetailStoreProfile.RECEIVE_COMMAND, original.retained.command)
    assertEquals(1, original.snapshot.reservations.size)
    val beforeReservation = original.snapshot.reservations.single()
    assertEquals(CollectionExposure.UNKNOWN, beforeReservation.binding.exposure)
    val cut = checkoutJsonChange(original.cutBytes) { root ->
        val state = root.getValue("state").jsonObject
        val reservations = state.getValue("reservations").jsonArray
        assertEquals(1, reservations.size)
        val reservation = reservations.single().jsonObject
        val changed = if (control == CheckoutReservationCorruption.ALLOCATION_ID || control == CheckoutReservationCorruption.ALLOCATION_QUANTITY) {
            val allocation = reservation.getValue("allocations").jsonArray.single().jsonObject
            val next = if (control == CheckoutReservationCorruption.ALLOCATION_ID) allocation.checkoutField("allocationId", JsonPrimitive(id().value))
            else allocation.checkoutField("quantity", allocation.getValue("quantity").jsonObject.checkoutField("scaled", JsonPrimitive("2")))
            reservation.checkoutField("allocations", JsonArray(listOf(next)))
        } else {
            val binding = reservation.getValue("binding").jsonObject
            val (key, value) = when (control) {
                CheckoutReservationCorruption.UNCOLLECTED -> "exposure" to "UNCOLLECTED"
                CheckoutReservationCorruption.QUOTE_REVISION -> "quoteRevision" to beforeReservation.binding.quoteRevision.next().value.toString()
                CheckoutReservationCorruption.ACTOR -> "originalActorId" to id().value
                CheckoutReservationCorruption.ORDER -> "orderId" to id().value
                CheckoutReservationCorruption.COLLECTION -> "collectionId" to id().value
                else -> error("Not a binding control")
            }
            reservation.checkoutField("binding", binding.checkoutField(key, JsonPrimitive(value)))
        }
        root.checkoutField("state", state.checkoutField("reservations", JsonArray(listOf(changed))))
    }
    val decoded = InventorySnapshotCodec.decode(cut.copyBytes().decodeToString(), InventorySnapshotBinding(
        InventorySnapshotKind.BRANCH_INVENTORY, original.snapshot.scope, TEST_SCOPE.branchId, original.snapshot.revision))
    assertEquals(cut.copyBytes().decodeToString(), InventorySnapshotCodec.encode(decoded))
    val cutDigest = sha256(cut.copyBytes())
    val companion = checkoutJsonChange(original.companionBytes) { it.checkoutField("cutDigest", JsonPrimitive(cutDigest.value)) }
    InventoryRetailCompanionCodec.decode(companion, InventoryRetailCompanionBinding(TEST_SCOPE, TEST_SCOPE.branchId, original.snapshot.revision))
    val companionDigest = sha256(companion.copyBytes())
    val result = checkoutJsonChange(original.retained.result) {
        it.checkoutField("cutDigest", JsonPrimitive(cutDigest.value)).checkoutField("companionDigest", JsonPrimitive(companionDigest.value))
    }
    InventoryRetailFixtureResultCodec.decode(result)
    val pair = checkoutEvidenceMutant(original.retained, result = result, writes = original.retained.aggregateWrites.map { row ->
        row.checkoutMutant(payload = if (row.key.kind == InventoryRetailStoreProfile.CUT_KIND) cut else companion)
    })
    val resultHash = sha256(result.copyBytes()).value
    fixture.corruptProjection { connection ->
        // The helper already temporarily removed only aggregate_revision_update.
        val names = listOf("operation_result_immutable_update", "journal_envelope_immutable_update", "outbox_intent_immutable_update")
        val guards = names.associateWith { name -> connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?") {
            it.bindText(1, name); assertTrue(it.step()); it.getText(0)
        } }
        guards.keys.forEach { name -> connection.usePrepared("DROP TRIGGER $name") { it.step() } }
        try {
            for ((key, bytes) in listOf(InventoryRetailStoreProfile.cutKey(TEST_SCOPE.branchId) to cut,
                InventoryRetailStoreProfile.companionKey(TEST_SCOPE.branchId) to companion)) {
                connection.usePrepared("UPDATE aggregate_state SET payload=?,payloadHash=? WHERE scope=? AND kind=? AND id=?") {
                    it.bindBlob(1, bytes.copyBytes()); it.bindText(2, sha256(bytes.copyBytes()).value)
                    it.bindText(3, TEST_SCOPE.storageKey()); it.bindText(4, key.kind); it.bindText(5, key.id.value); it.step()
                }
            }
            val envelope = connection.usePrepared("SELECT envelope FROM journal_envelope WHERE scope=? AND operationId=?") {
                it.bindText(1, TEST_SCOPE.storageKey()); it.bindText(2, pair.operationId.value); assertTrue(it.step()); it.getBlob(0)
            }
            val fields = retainedTestFields(envelope).toMutableList()
            fields[14] = resultHash.encodeToByteArray()
            fields[17] = result.copyBytes()
            fields[18] = retainedTestFrame(retainedTestFields(fields[18]).map { encoded ->
                val effect = retainedTestFields(encoded).toMutableList()
                if (effect.size == 5) {
                    val kind = effect[0].decodeToString()
                    if (kind == InventoryRetailStoreProfile.CUT_KIND) effect[4] = cut.copyBytes()
                    else if (kind == InventoryRetailStoreProfile.COMPANION_KIND) effect[4] = companion.copyBytes()
                }
                retainedTestFrame(effect)
            })
            val rewritten = retainedTestFrame(fields)
            assertEquals(pair.journalHash, sha256(rewritten))
            connection.usePrepared("UPDATE operation_result SET result=?,resultHash=? WHERE scope=? AND operationId=?") {
                it.bindBlob(1, result.copyBytes()); it.bindText(2, resultHash); it.bindText(3, TEST_SCOPE.storageKey())
                it.bindText(4, pair.operationId.value); it.step()
            }
            connection.usePrepared("UPDATE journal_envelope SET envelope=?,hash=? WHERE scope=? AND operationId=?") {
                it.bindBlob(1, rewritten); it.bindText(2, pair.journalHash.value); it.bindText(3, TEST_SCOPE.storageKey())
                it.bindText(4, pair.operationId.value); it.step()
            }
            val head = connection.usePrepared("SELECT sequence,journalHash FROM branch_authority WHERE scope=?") {
                it.bindText(1, TEST_SCOPE.storageKey()); assertTrue(it.step()); it.getLong(0) to it.getText(1)
            }
            assertEquals(original.retained.sequence.value, head.first)
            assertEquals(original.retained.journalHash.value, head.second)
            connection.usePrepared("UPDATE branch_authority SET journalHash=? WHERE scope=?") {
                it.bindText(1, pair.journalHash.value); it.bindText(2, TEST_SCOPE.storageKey()); it.step()
            }
            val upload = retainedTestFrame(retainedTestTexts("ukkera.journal-upload/v1", TEST_SCOPE.branchId.value,
                pair.authorityEpoch.value.toString(), pair.sequence.value.toString(), pair.journalHash.value))
            connection.usePrepared("UPDATE outbox_intent SET body=?,bodyHash=? WHERE scope=? AND operationId=? AND ordinal=0") {
                it.bindBlob(1, upload); it.bindText(2, sha256(upload).value); it.bindText(3, TEST_SCOPE.storageKey())
                it.bindText(4, pair.operationId.value); it.step()
            }
        } finally {
            for ((_, ddl) in guards) connection.usePrepared(ddl) { it.step() }
        }
        for ((name, ddl) in guards) connection.usePrepared("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?") {
            it.bindText(1, name); assertTrue(it.step()); assertEquals(ddl, it.getText(0))
        }
    }
    // Actual kernel borrowed view -> actual A2 observe -> actual closed dispatcher. No fake view.
    assertEquals(pair.journalHash, inventory().retained.journalHash)
}
