package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Internal copied-fact seam. Lead-owned Room tests exercise genuine retained commits/public views. */
class InventoryRetailProducerValidationTest {
    private val f = RetailFixtures

    @Test fun genuineGenesisAndMultiReceiptFixtureProducersPassTheConcreteCompleteDispatcher() {
        val genesis = f.genesis()
        validateRetailFixtureProducer(f.current(genesis), f.retained(genesis, InventoryRetailCommandCodec.genesisRequest()))
        val requests = (1..3).map { f.request(it) }
        val proposal = f.receive(f.current(genesis), requests)
        val normalized = InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(requests))
        val retained = f.retained(proposal, normalized)
        validateRetailRetainedInventory(f.current(proposal), retained)
        validateRetailFixtureProducer(f.current(proposal), retained)
        assertEquals(6, retained.claims.size)
        assertEquals(2, retained.writes.size)
        assertTrue(retained.jobs.isEmpty())
    }

    @Test fun correctlyBoundUnknownAndWrongVersionProducersRemainObservationsNotAcceptedFixtures() {
        val before = f.current(f.genesis())
        val input = f.request()
        val plan = InventoryPlanner.receive(before.snapshot, before.snapshot.revision, f.op(1_001), listOf(input.toReceipt(f.scope)))
        for (key in listOf(f.compositeCommand, CommandKey(InventoryRetailStoreProfile.RECEIVE_COMMAND.name, 2),
            InventoryReceiptStoreProfile.RECEIVE_COMMAND, CommandKey(InventoryRetailStoreProfile.GENESIS_COMMAND.name, 2))) {
            val normalized = InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(listOf(input)))
            val context = f.context(key, normalized, plan.operationId)
            val contribution = projectRetailContribution(before, context, plan, f.now, listOf(input))
            val pair = f.current(contribution)
            val retained = RetailRetainedFacts(true, f.scope, pair.companion.producer, normalized, StoredBytes("unqualified-result".encodeToByteArray()),
                f.rows(contribution), emptyList(), emptyList())
            validateRetailRetainedInventory(pair, retained)
            retailFailure(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED) { validateRetailFixtureProducer(pair, retained) }
        }
    }

    @Test fun observationsDoNotApproveWrongResultsExtraNonInventoryWritesClaimsOrRequestedJobs() {
        val request = InventoryRetailReceiveBatch(listOf(f.request()))
        val proposal = f.receive(f.current(f.genesis()), request.receipts)
        val pair = f.current(proposal)
        val retained = f.retained(proposal, InventoryRetailCommandCodec.encodeReceive(request))
        val extraWrite = pair.cutRow.copy(key = AggregateKey("sales.order", f.id(30_001)), schema = "qualification.sales/v1")
        val extraClaim = RetailRetainedClaim(RetainedClaimAction.ACQUIRE, ClaimKey("qualification.extra", "one"), f.id(30_002))
        val changes = listOf(retained.copy(result = StoredBytes("wrong-full-result".encodeToByteArray())),
            retained.copy(writes = retained.writes + extraWrite), retained.copy(claims = retained.claims + extraClaim),
            retained.copy(claims = retained.claims.dropLast(1)), retained.copy(claims = retained.claims + retained.claims.last()),
            retained.copy(claims = retained.claims.map { it.copy(action = RetainedClaimAction.RELEASE) }),
            retained.copy(jobs = listOf("qualification.job" to StoredBytes(byteArrayOf(1)))))
        for (changed in changes) {
            validateRetailRetainedInventory(pair, changed)
            retailFailure(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED) { validateRetailFixtureProducer(pair, changed) }
        }
    }

    @Test fun evenAnObservationRejectsExtraInventoryRowsOrAnyDifferentFullInventoryAfterWrite() {
        val proposal = f.receive(f.current(f.genesis()))
        val pair = f.current(proposal)
        val retained = f.retained(proposal, InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(listOf(f.request()))))
        val cut = pair.cutRow
        for (writes in listOf(retained.writes + cut.copy(key = InventoryRetailStoreProfile.cutKey(f.id(31_000))),
            retained.writes + cut, retained.writes.drop(1),
            listOf(cut.copy(generation = Revision(3)), pair.companionRow),
            listOf(cut.copy(schema = "inventory.cut/v2"), pair.companionRow),
            listOf(cut.copy(payload = StoredBytes("different".encodeToByteArray())), pair.companionRow),
            listOf(cut.copy(digest = PayloadDigest("f".repeat(64))), pair.companionRow))) {
            retailFailure(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED) { validateRetailRetainedInventory(pair, retained.copy(writes = writes)) }
        }
    }

    @Test fun everyProducerIdentityDimensionAndOriginalNormalizedDigestAreIndependentlyRequired() {
        val proposal = f.genesis()
        val pair = f.current(proposal)
        val retained = f.retained(proposal, InventoryRetailCommandCodec.genesisRequest())
        for (changed in listOf(retained.copy(committed = false),
            retained.copy(scope = BranchScope(f.id(32_001), f.scope.companyId, f.scope.branchId)),
            retained.copy(producer = retained.producer.copy(operationId = f.op(32_002))),
            retained.copy(producer = retained.producer.copy(command = InventoryRetailStoreProfile.RECEIVE_COMMAND)),
            retained.copy(producer = retained.producer.copy(originalActorId = f.id(32_003))),
            retained.copy(producer = retained.producer.copy(semanticDigest = PayloadDigest("f".repeat(64)))),
            retained.copy(normalized = StoredBytes("{}".encodeToByteArray())))) {
            retailFailure(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED) { validateRetailRetainedInventory(pair, changed) }
        }
    }

    @Test fun coherentDigestRebindingCannotReplaceCompleteOrderedReceiveInputs() {
        val requests = listOf(f.request(1), f.request(2).copy(lotId = f.id(23)))
        val proposal = f.receive(f.current(f.genesis()), requests)
        val pair = f.current(proposal)
        val retained = f.retained(proposal, InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(requests)))
        for (changed in listOf(requests.reversed(), requests.take(1),
            listOf(requests[0].copy(expiresAtEpochMillis = requests[0].expiresAtEpochMillis!! + 1), requests[1]),
            listOf(requests[0].copy(origin = requests[0].origin.copy(documentId = f.id(33_001))), requests[1]))) {
            val normalized = InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(changed))
            val rebound = reboundProducer(pair, retained, normalized)
            validateRetailRetainedInventory(rebound.first, rebound.second)
            retailFailure(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED) { validateRetailFixtureProducer(rebound.first, rebound.second) }
        }
    }

    @Test fun exactResultIncludesEveryReceiptIdOrderScopeOperationRevisionAndBothDigests() {
        val requests = listOf(f.request(1), f.request(2))
        val proposal = f.receive(f.current(f.genesis()), requests)
        val pair = f.current(proposal)
        val retained = f.retained(proposal, InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(requests)))
        val text = proposal.result.copyBytes().decodeToString()
        val wrongResults = listOf(jsonEdit(text, listOf("receiptIds"), JsonArray(requests.reversed().map { JsonPrimitive(it.receiptId.value) })),
            jsonEdit(text, listOf("receiptIds"), JsonArray(listOf(JsonPrimitive(requests.first().receiptId.value)))),
            jsonEdit(text, listOf("scope", "organizationId"), JsonPrimitive(f.id(34_001).value)),
            jsonEdit(text, listOf("operationId"), JsonPrimitive(f.op(34_002).value)),
            jsonEdit(text, listOf("inventoryRevision"), JsonPrimitive("2")),
            jsonEdit(text, listOf("cutDigest"), JsonPrimitive("f".repeat(64))),
            jsonEdit(text, listOf("companionDigest"), JsonPrimitive("f".repeat(64))))
        for (wrong in wrongResults) {
            val result = StoredBytes(wrong.encodeToByteArray())
            InventoryRetailFixtureResultCodec.decode(result) // Still valid bounded canonical historical result data.
            validateRetailRetainedInventory(pair, retained.copy(result = result))
            retailFailure(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED) { validateRetailFixtureProducer(pair, retained.copy(result = result)) }
        }
    }

    private fun reboundProducer(pair: RetailObservedPair, retained: RetailRetainedFacts, normalized: StoredBytes): Pair<RetailObservedPair, RetailRetainedFacts> {
        val prior = pair.companion.producer
        val producer = prior.copy(semanticDigest = semanticDigest(pair.scope, prior.originalActorId, prior.command, normalized))
        val companion = copyRetailCompanion(pair.companion, producer = producer)
        val bytes = InventoryRetailCompanionCodec.encode(companion)
        val row = pair.companionRow.copy(payload = bytes, digest = sha256(bytes.copyBytes()))
        val observation = decodeRetailPair(pair.scope, pair.cutRow, row)
        // Rebind the full result too, so an unchanged result digest cannot mask the request check.
        val result = InventoryRetailFixtureResultCodec.encode(InventoryRetailFixtureResult(InventoryRetailFixtureResultKind.RECEIVED,
            pair.scope, producer.operationId, pair.snapshot.revision,
            companion.receipts.filter { it.producingOperationId == producer.operationId }.map { it.receiptId }, pair.cutRow.digest, row.digest))
        return observation to retained.copy(producer = producer, normalized = normalized, result = result, writes = listOf(pair.cutRow, row))
    }
}
