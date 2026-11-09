package com.ukkera.pos.inventory

import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InventoryPostingCodecTest {
    @Test fun independentEmptyBatchGoldenRetainsDomainCoordinatesWithoutInventingGenesisOrCommit() {
        val plan = InventoryPlanner.expireReservations(InventorySnapshot.empty(scope), Revision(0), OperationId(id(700).value), 1)
        val golden = """{"schemaVersion":1,"profile":"purchase-retail-expiry-v1","calculationVersion":"single-pool-reference-half-up-v1","scope":{"companyId":"00000000-0000-0000-0000-000000000002","branchId":"00000000-0000-0000-0000-000000000003"},"operationId":"00000000-0000-0000-0000-0000000002bc","requiredInventoryRevision":"0","resultingInventoryRevision":"1","effects":[]}"""
        assertEquals(golden, InventoryPostingCodec.encode(postingCapture(plan)))
        assertEquals(golden, InventoryPostingCodec.encode(InventoryPostingCodec.decode(golden, postingBinding(plan))))
    }

    @Test fun independentlyWrittenReceiptGoldenPinsEveryFieldAndCanonicalScalar() {
        // Handwritten wire fragments, not values produced by any codec or serializer under test.
        val pool = """{"scope":{"companyId":"00000000-0000-0000-0000-000000000002","branchId":"00000000-0000-0000-0000-000000000003"},"skuId":"00000000-0000-0000-0000-000000000004","warehouseId":"00000000-0000-0000-0000-000000000005","ownership":"COMPANY_OWNED","supplierId":null}"""
        val q = """{"unitId":"00000000-0000-0000-0000-000000000001","scaled":"3","scale":0}"""
        val v = """{"currency":"EGP","minor":"100"}"""
        val golden = """{"schemaVersion":1,"profile":"purchase-retail-expiry-v1","calculationVersion":"single-pool-reference-half-up-v1","scope":{"companyId":"00000000-0000-0000-0000-000000000002","branchId":"00000000-0000-0000-0000-000000000003"},"operationId":"00000000-0000-0000-0000-0000000002bd","requiredInventoryRevision":"0","resultingInventoryRevision":"1","effects":[{"type":"valuation","pool":$pool,"requiredPoolRevision":"0","resultingPoolRevision":"1","fact":{"type":"receipt","factId":"00000000-0000-0000-0000-0000000002be","quantity":$q,"originalValue":$v,"origin":"PURCHASE"},"quantityDelta":$q,"originalValueDelta":$v,"carryingValueDelta":$v},{"type":"movement","sourceId":"00000000-0000-0000-0000-0000000002be","position":{"pool":$pool,"lotId":"00000000-0000-0000-0000-000000000014","custody":"RELEASED"},"quantityDelta":$q,"reason":"RECEIPT","sourceAllocationId":null}]}"""
        val plan = postingSimpleReceipt()
        assertEquals(golden, InventoryPostingCodec.encode(postingCapture(plan)))
        assertEquals(golden, InventoryPostingCodec.encode(InventoryPostingCodec.decode(golden, postingBinding(plan))))
    }

    @Test fun allFiveTagsRoundTripWithFullStateAndSafetyBeforeAfter() {
        val issued = postingIssueWithExpiry()
        val batch = postingRoundTrip(issued.plan, 10)
        assertEquals(setOf("safety", "reservation", "valuation", "movement", "issue"),
            (inventoryJsonAt(InventoryPostingCodec.encode(batch), "effects") as JsonArray).map {
                (it as JsonObject).getValue("type").toString().trim('"')
            }.toSet())
        val safety = assertIs<InventoryPostingEffect.SafetyChanged>(batch.effects.first())
        assertEquals(StockSafety.CLEAR, safety.before.safety)
        assertEquals(StockSafety.EXPIRED, safety.after.safety)
        assertEquals(safety.before.copy(safety = StockSafety.EXPIRED), safety.after)
        assertEquals(issued.proof.issueId, assertIs<InventoryPostingEffect.IssueRecorded>(batch.effects.last()).issue.issueId)
    }

    @Test fun decodedDataContainsNoExecutablePlanProofOrDeadlineCapability() {
        val issued = postingIssueWithExpiry()
        val batch: Any = postingRoundTrip(issued.plan, 10)
        assertFalse(batch is InventoryPlan)
        assertFalse(batch is ValuationPlan)
        assertFalse(batch is ReservationProof)
        assertFalse(batch is IssueProof)
        val text = InventoryPostingCodec.encode(batch as InventoryPostingBatch)
        assertFalse(text.contains("mustCommitBefore"))
        assertFalse(text.contains("sourceBasis"))
        assertFalse(text.contains("sourceSnapshot"))
    }

    @Test fun batchAndNestedPlannerListsAreImmutableDefensiveCopies() {
        val original = postingCapture(postingSimpleReceipt())
        val mutable = original.effects.toMutableList()
        val copy = InventoryPostingBatch(original.binding, mutable)
        mutable.clear()
        assertEquals(2, copy.effects.size)
        assertFails { (copy.effects as MutableList<InventoryPostingEffect>).clear() }
        val issue = assertIs<InventoryPostingEffect.IssueRecorded>(postingRoundTrip(postingIssueWithExpiry().plan, 10).effects.last()).issue
        assertFails { (issue.allocations as MutableList<IssuedStockAllocation>).clear() }
    }

    @Test fun independentScopeOperationAndBothRevisionsAreRequired() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        val binding = postingBinding(plan)
        val alternatives = listOf(binding.copy(scope = scope.copy(companyId = id(710))),
            binding.copy(scope = scope.copy(branchId = id(711))), binding.copy(operationId = OperationId(id(712).value)),
            binding.copy(requiredInventoryRevision = Revision(1), resultingInventoryRevision = Revision(2)),
            binding.copy(resultingInventoryRevision = Revision(2)))
        alternatives.forEach { expected -> postingFailure(InventoryPostingFailure.BINDING_MISMATCH) {
            InventoryPostingCodec.decode(text, expected)
        } }
    }

    @Test fun versionProfileAndCalculationVersionCannotFallBack() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        listOf("schemaVersion" to JsonPrimitive(2), "profile" to JsonPrimitive("generic-v2"),
            "calculationVersion" to JsonPrimitive("future-rounding")).forEach { (field, value) ->
            postingDecodeFailure(plan, inventoryJsonEdit(text, listOf(field), value), InventoryPostingFailure.UNSUPPORTED_VERSION)
        }
    }

    @Test fun unknownMissingDuplicateAndNoncanonicalFieldsFailClosed() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        val root = inventoryJsonAt(text) as JsonObject
        listOf(JsonObject(root + ("authorization" to JsonPrimitive("not-authority"))).toString(),
            JsonObject(root.filterKeys { it != "effects" }).toString(), "{\"schemaVersion\":1," + text.drop(1),
            text + " ", JsonObject(root.toList().reversed().toMap()).toString(),
            text.replace("\"PURCHASE\"", "\"\\u0050URCHASE\""),
            inventoryJsonEdit(text, listOf("schemaVersion"), JsonPrimitive("1"))).forEach { postingDecodeFailure(plan, it) }
    }

    @Test fun unknownEffectFactAndEnumTagsCannotBeIgnored() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        listOf(listOf("effects", "0", "type") to "future",
            listOf("effects", "0", "fact", "type") to "transfer",
            listOf("effects", "1", "reason") to "SALE",
            listOf("effects", "1", "position", "custody") to "TRUSTED").forEach { (path, tag) ->
            postingDecodeFailure(plan, inventoryJsonEdit(text, path, JsonPrimitive(tag)))
        }
    }

    @Test fun unsupportedReceiptOriginsRejectRatherThanChangingMeaning() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        for (origin in ReceiptValueOrigin.entries.filter { it != ReceiptValueOrigin.PURCHASE }) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "0", "fact", "origin"), JsonPrimitive(origin.name)),
                InventoryPostingFailure.UNSUPPORTED_STATE)
        }
    }

    @Test fun costPlanFieldsAreNotARecognizedEmptyExtension() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        val value = inventoryJsonAt(text, "effects", "0") as JsonObject
        for (field in listOf("linkedAdjustment", "sourceBasis", "next")) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "0"), JsonObject(value + (field to JsonNull))))
        }
    }

    @Test fun invalidUuidAndNoncanonicalLongScalarsRejectWithoutEchoingInput() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        for (bad in listOf("01", "-0", "1e2", "+3", "9223372036854775808", "-9223372036854775809", "secret-value")) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "0", "quantityDelta", "scaled"), JsonPrimitive(bad)))
        }
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("operationId"), JsonPrimitive("00000000-0000-0000-0000-000000000ABC")))
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("requiredInventoryRevision"), JsonPrimitive(0)))
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "0", "fact", "originalValue", "minor"), JsonPrimitive(100)))
    }

    @Test fun numericScaleMustBeExactAndSupported() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        for (bad in listOf(JsonPrimitive("0"), JsonPrimitive(7), JsonPrimitive(-1), JsonPrimitive(0.0))) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "0", "quantityDelta", "scale"), bad))
        }
    }

    @Test fun highestMoneyQuantityAndFractionalScalesRemainExact() {
        val f = PostingFixture()
        val high = f.receive(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(quantity(Long.MAX_VALUE), assertIs<InventoryPostingEffect.Valuation>(postingRoundTrip(high).effects[0]).quantityDelta)
        val fractional = PostingFixture()
        val plan = fractional.receipts(listOf(StockReceipt(fractional.ids.next(), fractional.key, quantity(1_234_567, 6), money(987))))
        assertEquals(quantity(1_234_567, 6), assertIs<InventoryPostingEffect.Valuation>(postingRoundTrip(plan).effects[0]).quantityDelta)
    }

    @Test fun revisionOverflowAndImpossibleIntrinsicClockFailSanitized() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        postingFailure { InventoryPostingCodec.decode(text, postingBinding(plan).copy(requiredInventoryRevision = Revision(Long.MAX_VALUE))) }
        postingFailure(InventoryPostingFailure.TOO_LARGE) {
            InventoryPostingCodec.decode(text, postingBinding(plan).copy(requiredInventoryRevision = Revision(4_096), resultingInventoryRevision = Revision(4_097)))
        }
    }

    @Test fun allPostedDeltasAndMovementFlagsMustAgreeWithTheWholeFact() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        for (path in listOf(listOf("effects", "0", "quantityDelta", "scaled"),
            listOf("effects", "0", "originalValueDelta", "minor"), listOf("effects", "0", "carryingValueDelta", "minor"),
            listOf("effects", "1", "quantityDelta", "scaled"))) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, path, JsonPrimitive("0")))
        }
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "1", "sourceAllocationId"), JsonPrimitive(id(715).value)))
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "1", "sourceId"), JsonPrimitive(id(716).value)))
    }

    @Test fun incompleteReceiptPairDuplicatePairAndReorderedMembersReject() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        val effects = inventoryJsonAt(text, "effects") as JsonArray
        for (changed in listOf(JsonArray(effects.take(1)), JsonArray(effects + effects), JsonArray(effects.reversed()))) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects"), changed))
        }
    }

    @Test fun sourceFreeDecodeAllowsWholeReceiptPairOmissionButLiveCrosswalkRejectsIt() {
        val plan = postingSimpleReceipt()
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        val emptyHistorical = inventoryJsonEdit(text, listOf("effects"), JsonArray(emptyList()))
        // No source cut is supplied to decoding: an empty historical batch cannot prove completeness.
        assertEquals(emptyHistorical,
            InventoryPostingCodec.encode(InventoryPostingCodec.decode(emptyHistorical, postingBinding(plan))))
        postingFailure(InventoryPostingFailure.INCONSISTENT_STATE) {
            postingCapture(postingForge(plan, effects = emptyList()))
        }
    }

    @Test fun safetyCannotRewriteQuantityExpiryPositionOrOldFlag() {
        val plan = postingIssueWithExpiry().plan
        val text = InventoryPostingCodec.encode(postingCapture(plan, 10))
        for ((path, value) in listOf(listOf("effects", "0", "after", "onHand", "scaled") to JsonPrimitive("9"),
            listOf("effects", "0", "after", "expiresAtEpochMillis") to JsonPrimitive("11"),
            listOf("effects", "0", "after", "key", "lotId") to JsonPrimitive(id(717).value),
            listOf("effects", "0", "before", "safety") to JsonPrimitive("EXPIRED"))) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, path, value))
        }
    }

    @Test fun epochScalarAndReservationStatusHoldReasonAreStrict() {
        val f = PostingFixture(); f.receive(expires = 100)
        val plan = f.reserve(expires = 50).plan
        val text = InventoryPostingCodec.encode(postingCapture(plan))
        for (bad in listOf(JsonPrimitive("050"), JsonPrimitive("5e1"), JsonPrimitive("9223372036854775808"), JsonPrimitive(50))) {
            postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "0", "reservation", "expiresAtEpochMillis"), bad))
        }
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects", "0", "reservation", "status"), JsonPrimitive("RELEASED")),
            InventoryPostingFailure.UNSUPPORTED_STATE)
        val held = postingHeldFixture().result.plan
        val heldText = InventoryPostingCodec.encode(postingCapture(held, 10))
        postingDecodeFailure(held, inventoryJsonEdit(heldText, listOf("effects", "1", "reservation", "holds", "0", "reason"),
            JsonPrimitive("RECALL")), InventoryPostingFailure.UNSUPPORTED_STATE)
    }

    @Test fun heldIntermediateRevisionOrderCannotBeFlattenedOrReversed() {
        val plan = postingHeldFixture().result.plan
        val text = InventoryPostingCodec.encode(postingCapture(plan, 10))
        val effects = inventoryJsonAt(text, "effects") as JsonArray
        // Only an INTERIOR gap is intrinsically provable without the source reservation revision.
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects"), JsonArray(effects.filterIndexed { index, _ -> index != 4 })))
        postingDecodeFailure(plan, inventoryJsonEdit(text, listOf("effects"), JsonArray(effects.reversed())))
    }

    @Test fun sourceFreeDecodeDoesNotInventTheFirstReservationRevisionOrProveCompleteness() {
        val plan = postingHeldFixture().result.plan
        val text = InventoryPostingCodec.encode(postingCapture(plan, 10))
        val effects = inventoryJsonAt(text, "effects") as JsonArray
        val withoutFirst = inventoryJsonEdit(text, listOf("effects"), JsonArray(effects.filterIndexed { index, _ -> index != 1 }))
        // Remaining target R3 -> R4 is intrinsically consecutive, but its first revision is unanchored.
        // The live source-object crosswalk separately rejects this exact omission in CrosswalkTest.
        assertEquals(withoutFirst, InventoryPostingCodec.encode(InventoryPostingCodec.decode(withoutFirst, postingBinding(plan))))
    }

    @Test fun individuallyValidLongAmountsCannotOverflowTheCrossPoolIssueAggregate() {
        val f = PostingFixture(); f.receive(1, 1)
        val other = f.key.copy(pool = pool.copy(warehouseId = id(718)))
        f.receive(1, 1, other)
        val reserved = f.reserve(listOf(f.allocation(), f.allocation(at = other)))
        val plan = assertIs<ReservationIssueResult.Issued>(f.issue(reserved.proof.reservationId)).plan
        var text = InventoryPostingCodec.encode(postingRoundTrip(plan))
        for (index in listOf("0", "2")) {
            text = inventoryJsonEdit(text, listOf("effects", index, "fact", "slices", "0", "originalValue", "minor"),
                JsonPrimitive(Long.MAX_VALUE.toString()))
            for (field in listOf("originalValueDelta", "carryingValueDelta")) {
                text = inventoryJsonEdit(text, listOf("effects", index, field, "minor"), JsonPrimitive((-Long.MAX_VALUE).toString()))
            }
        }
        for (index in listOf("0", "1")) {
            text = inventoryJsonEdit(text, listOf("effects", "5", "issue", "allocations", index, "originalValue", "minor"),
                JsonPrimitive(Long.MAX_VALUE.toString()))
        }
        postingDecodeFailure(plan, text, InventoryPostingFailure.INCONSISTENT_STATE)
    }

    @Test fun byteArrayContainerAndDepthLimitsPrecedeRecursiveParsing() {
        val plan = postingSimpleReceipt()
        val containers = "[" + List(3) { "[" + "{},".repeat(2_731) + "{}]" }.joinToString(",") + "]"
        for (text in listOf(" ".repeat(InventoryPostingCodec.MAX_ENCODED_BYTES + 1),
            "\"" + "\u0800".repeat(InventoryPostingCodec.MAX_ENCODED_BYTES / 3 + 1) + "\"",
            "[" + "0,".repeat(InventoryPostingCodec.MAX_ARRAY_ENTRIES) + "0]",
            "[".repeat(InventoryPostingCodec.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(InventoryPostingCodec.MAX_JSON_DEPTH + 1), containers)) {
            postingDecodeFailure(plan, text, InventoryPostingFailure.TOO_LARGE)
        }
    }

    @Test fun malformedSurrogatesAndStructuralTruncationNeverReachDomainConstruction() {
        val plan = postingSimpleReceipt()
        for (text in listOf("\"\uD800\"", "\"\uDC00\"", "[}", "{", "\"unterminated")) {
            postingDecodeFailure(plan, text, InventoryPostingFailure.MALFORMED)
        }
    }

    @Test fun preEncodeEffectAndRecordCeilingsRejectBeforeMappingInvalidPayloads() {
        val plan = postingSimpleReceipt(); val healthy = postingCapture(plan)
        postingFailure(InventoryPostingFailure.TOO_LARGE) {
            InventoryPostingCodec.encode(InventoryPostingBatch(healthy.binding, List(InventoryPostingCodec.MAX_EFFECTS + 1) { healthy.effects[0] }))
        }
        val f = PostingFixture(); f.receive()
        val reserved = f.reserve().plan
        val reservation = (reserved.effects.single() as InventoryEffect.ReservationRecorded).reservation
        val oversized = StockReservation(reservation.reservationId, reservation.binding,
            List(InventoryPlanner.MAX_ALLOCATIONS + 1) { reservation.allocations.single() }, reservation.revision,
            reservation.status, reservation.holds, reservation.expiresAtEpochMillis)
        postingFailure(InventoryPostingFailure.TOO_LARGE) {
            InventoryPostingCodec.encode(InventoryPostingBatch(postingBinding(reserved), listOf(InventoryPostingEffect.ReservationRecorded(oversized))))
        }
    }

    @Test fun conservativeRestoreBudgetPrecedesInvalidChildRestorationBelowRawByteLimit() {
        val f = PostingFixture(); f.receive(100, 100)
        val plan = f.reserve(List(41) { f.allocation() }).plan
        val healthy = InventoryPostingCodec.encode(postingRoundTrip(plan))
        val path = listOf("effects", "0", "reservation", "allocations")
        val allocations = inventoryJsonAt(healthy, *path.toTypedArray()) as JsonArray
        assertEquals(41, allocations.size)
        val extra = JsonObject((allocations.first() as JsonObject).toMutableMap().also {
            it["allocationId"] = JsonPrimitive(id(9_200).value)
            it["lineId"] = JsonPrimitive(id(9_201).value)
        })
        val enlarged = JsonArray(allocations + extra)
        assertEquals(42, enlarged.map { (it as JsonObject).getValue("allocationId") }.distinct().size)
        val oversized = inventoryJsonEdit(healthy, path, enlarged)
        val invalid42 = inventoryJsonEdit(oversized, path + listOf("41", "position", "custody"), JsonPrimitive("INVALID_CUSTODY"))
        for (encoded in listOf(oversized, invalid42)) {
            assertTrue(encoded.encodeToByteArray().size < InventoryPostingCodec.MAX_ENCODED_BYTES)
            // Conservative restore units: 1,024 + 1,024 + 42 * 1,536 = 66,560, not raw JSON bytes.
            postingDecodeFailure(plan, encoded, InventoryPostingFailure.TOO_LARGE)
        }
        val invalid41 = inventoryJsonEdit(healthy, path + listOf("0", "position", "custody"), JsonPrimitive("INVALID_CUSTODY"))
        assertTrue(invalid41.encodeToByteArray().size < InventoryPostingCodec.MAX_ENCODED_BYTES)
        // 41 allocations fit the conservative budget; only then is the bad custody restored/rejected.
        postingDecodeFailure(plan, invalid41, InventoryPostingFailure.INCONSISTENT_STATE)
    }

    @Test fun conservativeByteBudgetRejectsWholeBatchRatherThanTruncatingToFit() {
        val plan = postingSimpleReceipt(); val healthy = postingCapture(plan)
        assertTrue(InventoryPostingCodec.encode(healthy).encodeToByteArray().size < InventoryPostingCodec.MAX_ENCODED_BYTES)
        postingFailure(InventoryPostingFailure.TOO_LARGE) {
            InventoryPostingCodec.encode(InventoryPostingBatch(healthy.binding, List(40) { healthy.effects[0] }))
        }
        assertEquals(2, healthy.effects.size)
    }
}
