package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryPosting
import com.ukkera.pos.inventory.InventoryPostingBinding
import com.ukkera.pos.inventory.InventoryPostingCodec
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.ReservationHold
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InventoryRetailCodecTest {
    private val f = RetailFixtures

    @Test fun literalGenesisAndOrderedBatchRoundTripCanonicalExactScalars() {
        assertEquals("""{"profile":"fixture-inventory-retail-genesis-v1"}""",
            InventoryRetailCommandCodec.genesisRequest().copyBytes().decodeToString())
        assertEquals(InventoryRetailCommandCodec.genesisRequest(), InventoryRetailCommandCodec.normalizeGenesis(InventoryRetailCommandCodec.genesisRequest()))
        val first = f.request().copy(quantity = Quantity(f.unit, Long.MAX_VALUE, 6), originalValue = Money(Currency.EGP, Long.MAX_VALUE),
            expiresAtEpochMillis = Long.MAX_VALUE)
        val second = f.request(2).copy(lotId = null, expiresAtEpochMillis = null)
        val request = InventoryRetailReceiveBatch(listOf(first, second))
        val bytes = InventoryRetailCommandCodec.encodeReceive(request)
        val decoded = InventoryRetailCommandCodec.decodeReceive(bytes)
        assertEquals(request.receipts, decoded.receipts)
        assertEquals(Revision(0), decoded.expectedRevision)
        assertEquals(bytes, InventoryRetailCommandCodec.normalizeReceive(bytes))
        val text = bytes.copyBytes().decodeToString()
        assertTrue(text.startsWith("""{"schemaVersion":1,"profile":"fixture-inventory-retail-receive-v1","receipts":["""))
        assertTrue(text.contains("\"scaled\":\"9223372036854775807\""))
        assertTrue(text.contains("\"minor\":\"9223372036854775807\""))
        assertTrue(!text.contains("actor") && !text.contains("scope") && !text.contains("operationId"))
    }

    @Test fun batchRejectsEmptyMixedRevisionsDuplicateIdentitiesOriginsAndInvalidScalars() {
        val first = f.request()
        for (bad in listOf(listOf(first, first), listOf(first, f.request(2).copy(origin = first.origin)))) {
            retailFailure(InventoryRetailStoreFailure.SOURCE_CONFLICT) { InventoryRetailReceiveBatch(bad) }
        }
        for (bad in listOf(emptyList(), listOf(first, f.request(2, Revision(1))), listOf(first.copy(quantity = Quantity(f.unit, 0, 0))),
            listOf(first.copy(originalValue = Money(Currency.EGP, -1))), listOf(first.copy(expiresAtEpochMillis = -1)),
            listOf(first.copy(lotId = null)))) {
            retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { InventoryRetailReceiveBatch(bad) }
        }
        val full = InventoryRetailReceiveBatch((1..32).map { f.request(it) })
        assertEquals(32, InventoryRetailCommandCodec.decodeReceive(InventoryRetailCommandCodec.encodeReceive(full)).receipts.size)
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { InventoryRetailReceiveBatch((1..33).map { f.request(it) }) }
    }

    @Test fun canonicalFieldsVersionEnumsAndEpochsAreNotCoerced() {
        retailFailure(InventoryRetailStoreFailure.NON_CANONICAL) { InventoryRetailCommandCodec.normalizeGenesis(bytes("{}")) }
        retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_VERSION) {
            InventoryRetailCommandCodec.normalizeGenesis(bytes("""{"profile":"fixture-inventory-retail-genesis-v2"}"""))
        }
        val text = InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(listOf(f.request()))).copyBytes().decodeToString()
        for ((invalid, expected) in listOf(" $text" to InventoryRetailStoreFailure.NON_CANONICAL,
            (text.dropLast(1) + ",\"unknown\":true}") to InventoryRetailStoreFailure.MALFORMED,
            text.replaceFirst("{", "{\"schemaVersion\":1,") to InventoryRetailStoreFailure.NON_CANONICAL,
            text.replace("\"schemaVersion\":1", "\"schemaVersion\":2") to InventoryRetailStoreFailure.UNSUPPORTED_VERSION,
            jsonEdit(text, listOf("receipts", "0", "expiresAtEpochMillis"), JsonPrimitive("01")) to InventoryRetailStoreFailure.INCONSISTENT_STATE,
            jsonEdit(text, listOf("receipts", "0", "originalValue", "minor"), JsonPrimitive("01")) to InventoryRetailStoreFailure.MALFORMED,
            jsonEdit(text, listOf("receipts", "0", "origin", "kind"), JsonPrimitive("PURCHASE_APPROVED")) to InventoryRetailStoreFailure.UNSUPPORTED_VERSION)) {
            retailFailure(expected) { InventoryRetailCommandCodec.decodeReceive(bytes(invalid)) }
        }
        for (invalid in listOf(JsonPrimitive("-0"), JsonPrimitive("+1"), JsonPrimitive("1e3"), JsonPrimitive("9223372036854775808"))) {
            retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) {
                InventoryRetailCommandCodec.decodeReceive(bytes(jsonEdit(text, listOf("receipts", "0", "expiresAtEpochMillis"), invalid)))
            }
        }
        // StoredRequest uses String?: strict JSON string decoding rejects an unquoted number
        // before receiptEpoch or the outer canonical re-encoding check can run.
        retailFailure(InventoryRetailStoreFailure.MALFORMED) {
            InventoryRetailCommandCodec.decodeReceive(bytes(jsonEdit(text, listOf("receipts", "0", "expiresAtEpochMillis"), JsonPrimitive(1000))))
        }
    }

    @Test fun companionStoresCanonicalA1AsAStringAndBindsEveryIndependentCoordinate() {
        val current = f.one()
        val companion = current.companion
        val encoded = InventoryRetailCompanionCodec.encode(companion)
        val text = encoded.copyBytes().decodeToString()
        val batchText = jsonAt(text, "latestBatch").jsonPrimitive
        assertTrue(batchText.isString)
        assertEquals(InventoryPostingCodec.encode(companion.latestBatch!!), batchText.content)
        assertTrue(text.contains("\\\"calculationVersion\\\""))
        val decoded = InventoryRetailCompanionCodec.decode(encoded, retailBinding(companion))
        assertEquals(encoded, InventoryRetailCompanionCodec.encode(decoded))
        assertEquals(companion.receipts, decoded.receipts)
        assertEquals(companion.issueSources, decoded.issueSources)
        assertEquals(companion.previous, decoded.previous)
        val binding = retailBinding(companion)
        // Only the INNER string has leading whitespace; the complete OUTER body is canonical.
        val noncanonicalBatch = " ${batchText.content}"
        val wrapped = bytes(jsonEdit(text, listOf("latestBatch"), JsonPrimitive(noncanonicalBatch)))
        val outerOnly = decodeReceiptBody(wrapped, JsonElement.serializer(), InventoryRetailStoreProfile.MAX_ROW_BYTES)
        assertEquals(wrapped, encodeReceiptBody(outerOnly, JsonElement.serializer(), InventoryRetailStoreProfile.MAX_ROW_BYTES))
        assertEquals(noncanonicalBatch, (outerOnly as JsonObject).getValue("latestBatch").jsonPrimitive.content)
        retailFailure(InventoryRetailStoreFailure.NON_CANONICAL) { InventoryRetailCompanionCodec.decode(wrapped, binding) }
        for (bad in listOf(binding.copy(scope = BranchScope(f.id(81), f.scope.companyId, f.scope.branchId)),
            binding.copy(scope = BranchScope(f.scope.organizationId, f.id(82), f.scope.branchId)),
            binding.copy(scope = BranchScope(f.scope.organizationId, f.scope.companyId, f.id(83))),
            binding.copy(id = f.id(84)), binding.copy(inventoryRevision = Revision(0)))) {
            retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) { InventoryRetailCompanionCodec.decode(encoded, bad) }
        }
        val changedOperation = jsonEdit(batchText.content, listOf("operationId"), JsonPrimitive(f.op(88).value))
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) {
            InventoryRetailCompanionCodec.decode(bytes(jsonEdit(text, listOf("latestBatch"), JsonPrimitive(changedOperation))), binding)
        }
        retailFailure(InventoryRetailStoreFailure.MALFORMED) {
            InventoryRetailCompanionCodec.decode(bytes(jsonEdit(text, listOf("latestBatch"), jsonAt(batchText.content))), binding)
        }
    }

    @Test fun nullLatestBatchIsGenesisOnlyAndPriorPairCannotBeInventedOrSelfReferential() {
        val genesis = f.genesis().contribution.companion
        val received = f.one().companion
        assertNull(InventoryRetailCompanionCodec.decode(InventoryRetailCompanionCodec.encode(genesis), retailBinding(genesis)).latestBatch)
        for (bad in listOf(copyRetailCompanion(received, latestBatch = null), copyRetailCompanion(received, previous = null),
            copyRetailCompanion(received, previous = received.previous!!.copy(producerOperationId = received.producer.operationId)),
            copyRetailCompanion(received, previous = received.previous!!.copy(cutDigest = received.cutDigest)))) {
            retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { InventoryRetailCompanionCodec.encode(bad) }
        }
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { InventoryRetailCompanionCodec.encode(copyRetailCompanion(genesis, latestBatch = received.latestBatch)) }
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { InventoryRetailCompanionCodec.encode(copyRetailCompanion(genesis, receipts = received.receipts)) }
    }

    @Test fun unsupportedOuterOwnershipCustodyAndUnknownA1TagsAreRejectedWithStableCodes() {
        val companion = f.one().companion
        val binding = retailBinding(companion)
        val text = InventoryRetailCompanionCodec.encode(companion).copyBytes().decodeToString()
        for (path in listOf(listOf("receipts", "0", "position", "pool", "ownership"), listOf("receipts", "0", "position", "custody"))) {
            retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_STATE) {
                InventoryRetailCompanionCodec.decode(bytes(jsonEdit(text, path, JsonPrimitive("UNKNOWN"))), binding)
            }
        }
        val batch = InventoryPostingCodec.encode(companion.latestBatch!!)
        val changedBatch = jsonEdit(batch, listOf("effects", "0", "type"), JsonPrimitive("cost-adjustment"))
        retailFailure(InventoryRetailStoreFailure.MALFORMED) {
            InventoryRetailCompanionCodec.decode(bytes(jsonEdit(text, listOf("latestBatch"), JsonPrimitive(changedBatch))), binding)
        }
        retailFailure(InventoryRetailStoreFailure.UNSUPPORTED_VERSION) {
            InventoryRetailCompanionCodec.decode(bytes(jsonEdit(text, listOf("profile"), JsonPrimitive("receipt-v1"))), binding)
        }
    }

    @Test fun utf8IsStrictForAllPublicCodecsAndBothSidesOfTheCurrentPair() {
        val current = f.one()
        for (raw in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))) {
            val value = StoredBytes(raw)
            retailFailure(InventoryRetailStoreFailure.MALFORMED) { InventoryRetailCommandCodec.normalizeGenesis(value) }
            retailFailure(InventoryRetailStoreFailure.MALFORMED) { InventoryRetailCommandCodec.decodeReceive(value) }
            retailFailure(InventoryRetailStoreFailure.MALFORMED) { InventoryRetailCompanionCodec.decode(value, retailBinding(current.companion)) }
            retailFailure(InventoryRetailStoreFailure.MALFORMED) { InventoryRetailFixtureResultCodec.decode(value) }
            retailFailure(InventoryRetailStoreFailure.MALFORMED) {
                decodeRetailPair(current.scope, current.cutRow.copy(payload = value, digest = com.ukkera.pos.security.sha256(raw)), current.companionRow)
            }
        }
    }

    @Test fun exactByteCapsRespectBothAdapterAndKernelBoundariesBeforeParsing() {
        val binding = retailBinding(f.one().companion)
        val decoders: List<Pair<Int, (StoredBytes) -> Unit>> = listOf(
            InventoryRetailStoreProfile.MAX_REQUEST_BYTES to { input: StoredBytes -> InventoryRetailCommandCodec.decodeReceive(input); Unit },
            InventoryRetailStoreProfile.MAX_RESULT_BYTES to { input: StoredBytes -> InventoryRetailFixtureResultCodec.decode(input); Unit },
            InventoryRetailStoreProfile.MAX_ROW_BYTES to { input: StoredBytes -> InventoryRetailCompanionCodec.decode(input, binding); Unit })
        for ((maximum, decode) in decoders) {
            retailFailure(InventoryRetailStoreFailure.MALFORMED) { decode(bytes(" ".repeat(maximum))) }
            if (maximum < InventoryRetailStoreProfile.MAX_ROW_BYTES) {
                retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { decode(bytes(" ".repeat(maximum + 1))) }
            } else {
                // Kernel StoredBytes itself owns this boundary; an oversized value cannot reach
                // an adapter decoder. The separate encoder-overflow test reaches the retail cap.
                val rejected = assertFailsWith<IllegalArgumentException> { bytes(" ".repeat(maximum + 1)) }
                assertTrue(rejected !is InventoryRetailStoreRejected)
            }
        }
        // UTF-8 bytes, not character count: these strings remain below the request character cap.
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) {
            InventoryRetailCommandCodec.decodeReceive(bytes("\"" + "\u0800".repeat(InventoryRetailStoreProfile.MAX_REQUEST_BYTES / 3 + 1) + "\""))
        }
    }

    @Test fun outerAndEmbeddedScannersBoundUnknownContainersBeforeRestoration() {
        val current = f.one()
        val text = current.companionRow.payload.copyBytes().decodeToString()
        val binding = retailBinding(current.companion)
        val unknownChildren = "{" + List(1_024) { "\"x$it\":{}" }.joinToString(",") + "}"
        for (raw in listOf("[".repeat(17) + "]".repeat(17), "[" + List(33) { "null" }.joinToString(",") + "]", unknownChildren)) {
            retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { InventoryRetailCompanionCodec.decode(bytes(raw), binding) }
        }
        // The outer scanner treats the A1 payload as a string. A1's OWN scanner still rejects it.
        val nested = "[".repeat(25) + "0" + "]".repeat(25)
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) {
            InventoryRetailCompanionCodec.decode(bytes(jsonEdit(text, listOf("latestBatch"), JsonPrimitive(nested))), binding)
        }
        val body = InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(listOf(f.request()))).copyBytes().decodeToString()
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) {
            InventoryRetailCommandCodec.decodeReceive(bytes(jsonEdit(body, listOf("receipts"), JsonArray(List(33) { JsonNull }))))
        }
    }

    @Test fun embeddedA1ConservativeBudgetRejectsBeforeRestoringAnInvalidFortySecondAllocation() {
        val before = f.one(f.request().copy(quantity = Quantity(f.unit, 100, 0)))
        val plan = f.reserve(before, allocations = (1..41).map { f.allocation(it, before.snapshot.positions.single().key, 1) })
        val companion = f.project(before, plan).companion
        val positive = InventoryRetailCompanionCodec.encode(companion)
        val decoded = InventoryRetailCompanionCodec.decode(positive, retailBinding(companion))
        assertEquals(positive, InventoryRetailCompanionCodec.encode(decoded))
        assertEquals(41, assertIs<InventoryPostingEffect.ReservationRecorded>(decoded.latestBatch!!.effects.single()).reservation.allocations.size)
        val whole = positive.copyBytes().decodeToString()
        val batch = InventoryPostingCodec.encode(companion.latestBatch!!)
        val path = listOf("effects", "0", "reservation", "allocations")
        val allocations = jsonAt(batch, *path.toTypedArray()) as JsonArray
        assertEquals(41, allocations.size)
        val extra = JsonObject((allocations.first() as JsonObject).toMutableMap().also {
            it["allocationId"] = JsonPrimitive(f.id(90_001).value)
            it["lineId"] = JsonPrimitive(f.id(90_002).value)
        })
        val enlarged = jsonEdit(batch, path, JsonArray(allocations + extra))
        val invalid = jsonEdit(enlarged, path + listOf("41", "position", "custody"), JsonPrimitive("INVALID_CUSTODY"))
        val encoded = bytes(jsonEdit(whole, listOf("latestBatch"), JsonPrimitive(invalid)))
        assertTrue(encoded.size < InventoryRetailStoreProfile.MAX_ROW_BYTES)
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { InventoryRetailCompanionCodec.decode(encoded, retailBinding(companion)) }
    }

    @Test fun completeEscapedWrapperCanExceedTheRowCapEvenWhenItsGenuineA1BatchFits() {
        // Genuine A1 expiry batch: 31 full before/after positions fit A1's conservative budget.
        // This is a CODEC boundary fixture, not a purportedly accepted whole inventory cut: its
        // separate maximum cumulative indexes intentionally need a later cross-row validator.
        var snapshot = InventorySnapshot.empty(InventoryScope(f.scope.companyId, f.scope.branchId))
        val requests = (1..32).map { n -> f.request(n).copy(skuId = f.id(91_000 + n), lotId = f.id(92_000 + n),
            quantity = Quantity(f.unit, Long.MAX_VALUE, 6), originalValue = Money(Currency.EGP, Long.MAX_VALUE), expiresAtEpochMillis = Long.MAX_VALUE) }
        for ((index, request) in requests.take(31).withIndex()) snapshot = InventoryPlanner.receive(snapshot, snapshot.revision,
            f.op(93_000 + index), listOf(request.toReceipt(f.scope))).nextSnapshot
        val plan = InventoryPlanner.expireReservations(snapshot, snapshot.revision, f.op(94_000), Long.MAX_VALUE)
        val batch = InventoryPosting.fromPlan(snapshot, plan, InventoryPostingBinding(snapshot.scope, plan.operationId, snapshot.revision, plan.nextSnapshot.revision),
            Long.MAX_VALUE)
        val batchText = InventoryPostingCodec.encode(batch)
        assertEquals(31, batch.effects.size)
        assertTrue(batchText.encodeToByteArray().size < InventoryPostingCodec.MAX_ENCODED_BYTES)
        assertTrue(batch.effects.all { it is InventoryPostingEffect.SafetyChanged })
        val base = f.one().companion
        val producer = f.planContext(plan).producer()
        val sources = requests.map { request -> InventoryReceiptSourceBinding(request.receiptId, f.op(93_000), request.origin,
            request.toReceipt(f.scope).position, request.quantity, request.originalValue, request.expiresAtEpochMillis) }
        val companion = copyRetailCompanion(base, revision = plan.nextSnapshot.revision, producer = producer,
            previous = InventoryReceiptPreviousPair(snapshot.revision, f.op(93_030), PayloadDigest("a".repeat(64)), PayloadDigest("b".repeat(64))),
            receipts = sources, issueSources = (1..32).map { InventoryRetailIssueSourceBinding(f.id(95_000 + it), f.op(96_000 + it)) }, latestBatch = batch)
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { InventoryRetailCompanionCodec.encode(companion) }
        // Reducing only wrapper indexes does fit; A1 itself is not the rejecting boundary.
        val smallWrapper = copyRetailCompanion(companion, receipts = sources.take(1), issueSources = emptyList())
        assertTrue(InventoryRetailCompanionCodec.encode(smallWrapper).size <= InventoryRetailStoreProfile.MAX_ROW_BYTES)
    }

    @Test fun batchCompanionResultsContributionsAndNestedA1ListsAreDefensive() {
        val inputs = mutableListOf(f.request())
        val request = InventoryRetailReceiveBatch(inputs)
        inputs.clear()
        assertEquals(1, request.receipts.size)
        assertEquals(Revision(0), request.expectedRevision)
        val first = f.one()
        val plan = f.reserve(first)
        val contribution = f.project(first, plan)
        val original = contribution.companion
        val receipts = original.receipts.toMutableList()
        val issues = mutableListOf(InventoryRetailIssueSourceBinding(f.id(97_001), f.op(97_002)))
        val companion = copyRetailCompanion(original, receipts = receipts, issueSources = issues)
        receipts.clear(); issues.clear()
        assertEquals(original.receipts, companion.receipts)
        assertEquals(1, companion.issueSources.size)
        assertFails { (companion.receipts as MutableList<InventoryReceiptSourceBinding>).clear() }
        assertFails { (companion.issueSources as MutableList<InventoryRetailIssueSourceBinding>).clear() }
        assertFails { (contribution.changes as MutableList<AggregateChange>).clear() }
        assertFails { (contribution.claims as MutableList<ClaimChange>).clear() }
        assertFails { (contribution.companion.latestBatch!!.effects as MutableList<InventoryPostingEffect>).clear() }
        val reservation = assertIs<InventoryPostingEffect.ReservationRecorded>(contribution.companion.latestBatch!!.effects.single()).reservation
        assertFails { (reservation.allocations as MutableList<StockAllocation>).clear() }
        assertFails { (reservation.holds as MutableList<ReservationHold>).clear() }
        val ids = mutableListOf(f.id(101))
        val result = InventoryRetailFixtureResult(InventoryRetailFixtureResultKind.RECEIVED, f.scope, f.op(1_001), Revision(1), ids,
            first.cutRow.digest, first.companionRow.digest)
        ids.clear()
        assertEquals(listOf(f.id(101)), result.receiptIds)
        assertFails { (result.receiptIds as MutableList<DomainId>).clear() }
        val bytes = InventoryRetailCompanionCodec.encode(original)
        val before = bytes.copyBytes()
        bytes.copyBytes().fill(0)
        assertTrue(before.contentEquals(bytes.copyBytes()))
    }

    @Test fun decodedResultsAreCanonicalFullOrderedHistoricalDataNotProposals() {
        val genesis = f.genesis()
        val first = f.current(genesis)
        val proposal = f.receive(first, listOf(f.request(1), f.request(2)))
        for (value in listOf(genesis, proposal)) {
            val result = InventoryRetailFixtureResultCodec.decode(value.result)
            assertEquals(value.result, InventoryRetailFixtureResultCodec.encode(result))
            assertEquals(value.contribution.companion.scope, result.scope)
            assertEquals(value.contribution.companion.producer.operationId, result.operationId)
        }
        val result = InventoryRetailFixtureResultCodec.decode(proposal.result)
        assertEquals(listOf(f.id(101), f.id(102)), result.receiptIds)
        val text = proposal.result.copyBytes().decodeToString()
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) {
            InventoryRetailFixtureResultCodec.decode(bytes(jsonEdit(text, listOf("receiptIds"), JsonArray(listOf(JsonPrimitive(f.id(101).value), JsonPrimitive(f.id(101).value))))))
        }
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { InventoryRetailFixtureResultCodec.decode(bytes(jsonEdit(text, listOf("kind"), JsonPrimitive("GENESIS")))) }
    }

    @Test fun retailGuardPreservesCoroutineCancellationAndSanitizesOtherFailures() {
        val cancellation = CancellationException("private cancellation context")
        assertSame(cancellation, assertFailsWith<CancellationException> { retailGuard { throw cancellation } })
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { retailGuard { throw IllegalStateException("private inventory transcript") } }
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { retailGuard { throw ArithmeticException("private amount") } }
        assertNotNull(f.one().companion.latestBatch)
    }

    private fun bytes(text: String): StoredBytes = StoredBytes(text.encodeToByteArray())

}
