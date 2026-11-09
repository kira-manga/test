package com.ukkera.pos.pricing

import com.ukkera.pos.types.Quantity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoredPricingSnapshotCodecTest {
    @Test fun fullMixedTaxChargeDiscountSnapshotRoundTripsWithoutRepricing() {
        val lines = listOf(line(1, "114", percent = "14", inclusion = TaxInclusion.INCLUSIVE), line(2, "100", percent = "14"))
        val original = OperationalPricingEngine.calculate(request(lines, listOf(service(setOf(id(1), id(2)))),
            listOf(fixed(2_508, listOf(id(1), id(2))))))
        val encoded = StoredPricingSnapshotCodec.encode(original)
        val restored = StoredPricingSnapshotCodec.decode(encoded)
        assertEquals(encoded, StoredPricingSnapshotCodec.encode(restored))
        assertEquals(original.snapshotId, restored.snapshotId)
        assertEquals(original.revision, restored.revision)
        assertEquals(original.profile, restored.profile)
        assertEquals(original.components, restored.components)
        assertEquals(original.lines, restored.lines)
        assertEquals(money(22_572), restored.payable)
        assertEquals(original.discounts.single().allocations, restored.discounts.single().allocations)
        assertFails { (restored.components as MutableList<*>).clear() }
    }

    @Test fun persistedQuotePlusConsumedQuantityRestartsOriginalReturnRemainder() {
        val original = OperationalPricingEngine.calculate(request(listOf(line(1, "0.03333333", quantity = "3"))))
        val first = original.openReturnCursor(id(1)).consume(Quantity.parse(unit, "1", 6))
        val quoteData = StoredPricingSnapshotCodec.encode(original)
        val consumedQuantity = first.remaining.consumedQuantity
        // Another live quote cannot alter the serialized receipt's unit price/tax.
        OperationalPricingEngine.calculate(request(listOf(line(1, "999", quantity = "3", percent = "90"))))
        val restored = StoredPricingSnapshotCodec.decode(quoteData)
        val cursor = restored.openReturnCursor(id(1)).consume(consumedQuantity).remaining
        val second = cursor.consume(Quantity.parse(unit, "1", 6))
        val last = second.remaining.consume(Quantity.parse(unit, "1", 6))
        assertEquals(listOf(money(3), money(3), money(4)), listOf(first.payable, second.payable, last.payable))
        assertEquals(money(0), last.remaining.remainingPayable)
        assertEquals(restored.snapshotId, last.remaining.originalSnapshotId)
    }

    @Test fun sequentialExactAndPostedCapsSurviveStorageValidation() {
        val lines = listOf(line(1, "0.018", cap = limits(1)), line(2, "0.017", cap = limits(1)), line(3, "0.016"))
        val original = OperationalPricingEngine.calculate(request(lines, adjustments = listOf(
            fixed(1, listOf(id(1), id(2))),
            fixed(1, listOf(id(1), id(2)), index = 6_001, stacking = StackingPermission.EXPLICITLY_ALLOWED))))
        val restored = StoredPricingSnapshotCodec.decode(StoredPricingSnapshotCodec.encode(original))
        assertEquals(listOf(money(1), money(1), money(1)), restored.lines.map { it.payable })
        assertEquals(original.discounts.map { it.allocations }, restored.discounts.map { it.allocations })
        assertConserves(restored)
    }

    @Test fun zeroAndHighestMoneyAndExactNoPostedReductionRoundTrip() {
        val inputs = listOf(
            request(emptyList()),
            request(listOf(line(1, "0.01", scale = 0).copy(quantity = Quantity(unit, Long.MAX_VALUE, 0)))),
            request(listOf(line(1, "10", cap = limits(0))), adjustments = listOf(fixed(0, listOf(id(1))))),
            request(listOf(line(1, "0.006"), line(2, "0.004")), adjustments = listOf(fixed(0, listOf(id(1))).copy(
                value = DiscountValue.Percentage(com.ukkera.pos.types.PercentageRate.parse("50"))))),
        )
        for (input in inputs) {
            val original = OperationalPricingEngine.calculate(input)
            val encoded = StoredPricingSnapshotCodec.encode(original)
            assertEquals(encoded, StoredPricingSnapshotCodec.encode(StoredPricingSnapshotCodec.decode(encoded)))
        }
    }

    @Test fun alteredTotalsTaxInputsCapsAndFinalComponentsAreRejected() {
        val original = OperationalPricingEngine.calculate(request(listOf(line(1, "100", percent = "14")),
            adjustments = listOf(fixed(1_140, listOf(id(1))))))
        val encoded = StoredPricingSnapshotCodec.encode(original)
        val variants = listOf(
            edit(encoded, listOf("payable", "minor"), JsonPrimitive("999")),
            edit(encoded, listOf("lines", "0", "unitPrice", "amount"), JsonPrimitive("101")),
            edit(encoded, listOf("lines", "0", "tax", "rate", "percent"), JsonPrimitive("15")),
            edit(encoded, listOf("lines", "0", "discountLimits", "maximumPayableReduction", "minor"), JsonPrimitive("0")),
            edit(encoded, listOf("components", "0", "posted", "minor"), JsonPrimitive("1")),
            edit(encoded, listOf("discounts", "0", "exactPayableReduction"), JsonPrimitive("1/1")),
            edit(encoded, listOf("discounts", "0", "allocations", "0", "roundingDifference"), JsonPrimitive("1/1")),
        )
        variants.forEach { assertFails { StoredPricingSnapshotCodec.decode(it) } }
    }

    @Test fun balancedButWrongBaselineRemainderRecipientIsRejected() {
        val original = OperationalPricingEngine.calculate(request((1..10).map { line(it, "0.005") }))
        var encoded = StoredPricingSnapshotCodec.encode(original)
        val firstIndex = original.components.indexOfFirst { it.portionId == id(1) && it.kind == PriceComponentKind.ITEM_NET }
        val sixthIndex = original.components.indexOfFirst { it.portionId == id(6) && it.kind == PriceComponentKind.ITEM_NET }
        for ((index, minor) in listOf(firstIndex to "0", sixthIndex to "1")) {
            encoded = edit(encoded, listOf("components", index.toString(), "postedBeforeDiscounts", "minor"), JsonPrimitive(minor))
            encoded = edit(encoded, listOf("components", index.toString(), "posted", "minor"), JsonPrimitive(minor))
        }
        encoded = edit(encoded, listOf("lines", "0", "payable", "minor"), JsonPrimitive("0"))
        encoded = edit(encoded, listOf("lines", "5", "payable", "minor"), JsonPrimitive("1"))
        assertFails { StoredPricingSnapshotCodec.decode(encoded) }
    }

    @Test fun balancedButWrongProtectedRemainderRecipientIsRejected() {
        val original = OperationalPricingEngine.calculate(request(listOf(line(1, "0.018"), line(2, "0.017"), line(3, "0.016")),
            adjustments = listOf(fixed(1, listOf(id(1), id(2))))))
        var encoded = StoredPricingSnapshotCodec.encode(original)
        // Both alternatives satisfy floors/ceilings, frozen C and the document sum,
        // but (1,2,1) violates v2's greater eligible remainder winner (2,1,1).
        for ((lineIndex, amount) in listOf(0 to 1, 1 to 2)) {
            val componentIndex = original.components.indexOfFirst { it.portionId == id(lineIndex + 1) && it.kind == PriceComponentKind.ITEM_NET }
            val index = componentIndex.toString()
            encoded = edit(encoded, listOf("lines", lineIndex.toString(), "payable", "minor"), JsonPrimitive(amount.toString()))
            encoded = edit(encoded, listOf("components", index, "posted", "minor"), JsonPrimitive(amount.toString()))
            for (field in listOf("postingDelta", "advertisedReduction")) {
                encoded = edit(encoded, listOf("discounts", "0", "allocations", index, field, "minor"), JsonPrimitive((2 - amount).toString()))
            }
            encoded = edit(encoded, listOf("discounts", "0", "allocations", index, "roundingDifference"),
                JsonPrimitive(if (lineIndex == 0) "17/3500" else "-17/3500"))
        }
        assertEquals("Stored protected allocation does not satisfy its versioned rule",
            assertFailsWith<IllegalArgumentException> { StoredPricingSnapshotCodec.decode(encoded) }.message)
    }

    @Test fun balancedButNonProportionalHistoricalDiscountCannotBeForged() {
        val original = OperationalPricingEngine.calculate(request(listOf(line(1, "100"), line(2, "100")),
            adjustments = listOf(fixed(1_000, listOf(id(1), id(2))))))
        var encoded = StoredPricingSnapshotCodec.encode(original)
        for ((lineIndex, net) in listOf(0 to 94, 1 to 96)) {
            val componentIndex = original.components.indexOfFirst { it.portionId == id(lineIndex + 1) && it.kind == PriceComponentKind.ITEM_NET }
            val ci = componentIndex.toString()
            encoded = edit(encoded, listOf("lines", lineIndex.toString(), "exactItemNet"), JsonPrimitive("$net/1"))
            encoded = edit(encoded, listOf("lines", lineIndex.toString(), "payable", "minor"), JsonPrimitive((net * 100).toString()))
            encoded = edit(encoded, listOf("components", ci, "exact"), JsonPrimitive("$net/1"))
            encoded = edit(encoded, listOf("components", ci, "posted", "minor"), JsonPrimitive((net * 100).toString()))
            encoded = edit(encoded, listOf("discounts", "0", "allocations", ci, "exactAfter"), JsonPrimitive("$net/1"))
            encoded = edit(encoded, listOf("discounts", "0", "allocations", ci, "exactReduction"), JsonPrimitive("${100 - net}/1"))
            for (field in listOf("advertisedReduction", "postingDelta")) {
                encoded = edit(encoded, listOf("discounts", "0", "allocations", ci, field, "minor"), JsonPrimitive(((100 - net) * 100).toString()))
            }
        }
        assertFails { StoredPricingSnapshotCodec.decode(encoded) } // All document totals still reconcile, but 6/4 violates 5/5.
    }

    @Test fun unknownVersionFiscalModeDuplicateFieldsAndNoncanonicalNumbersReject() {
        val encoded = StoredPricingSnapshotCodec.encode(OperationalPricingEngine.calculate(request(listOf(line(1, "100")))))
        val invalid = listOf(
            edit(encoded, listOf("schemaVersion"), JsonPrimitive(99)),
            edit(encoded, listOf("profile"), JsonPrimitive("linear-document-half-up-lr/v1")),
            edit(encoded, listOf("taxPostingPolicy"), JsonPrimitive("INDEPENDENT_LINE_HALF_UP")),
            edit(encoded, listOf("payable", "minor"), JsonPrimitive(10_000)),
            edit(encoded, listOf("exactPayable"), JsonPrimitive("1000/10")),
            edit(encoded, listOf("lines", "0", "unitPrice", "amount"), JsonPrimitive("100.0")),
            "{\"schemaVersion\":1," + encoded.drop(1),
            encoded + " ",
        )
        invalid.forEach { assertFails { StoredPricingSnapshotCodec.decode(it) } }
    }

    @Test fun deeplyNestedOrOversizedStoredInputRejectsBeforeUnboundedParsing() {
        assertEquals("Stored pricing JSON nesting exceeds bound",
            assertFailsWith<IllegalArgumentException> { StoredPricingSnapshotCodec.decode("[".repeat(25) + "0" + "]".repeat(25)) }.message)
        assertEquals("Stored pricing snapshot exceeds envelope bound",
            assertFailsWith<IllegalArgumentException> {
                StoredPricingSnapshotCodec.decode(" ".repeat(StoredPricingSnapshotCodec.MAX_JSON_CHARACTERS + 1))
            }.message)
        assertEquals("Stored pricing array exceeds bound",
            assertFailsWith<IllegalArgumentException> {
                StoredPricingSnapshotCodec.decode("[" + "0,".repeat(PricingProfiles.MAX_COMPONENTS) + "0]")
            }.message)
    }

    @Test fun utf8AndPreEncodeResourceLimitsAreSeparateFromJsonParsingAndRecordValidation() {
        val multibyte = "\"" + "\u0800".repeat(StoredPricingSnapshotCodec.MAX_ENCODED_BYTES / 3 + 1) + "\""
        assertTrue(multibyte.length <= StoredPricingSnapshotCodec.MAX_JSON_CHARACTERS)
        assertEquals("Stored pricing UTF-8 envelope exceeds bound",
            assertFailsWith<IllegalArgumentException> { StoredPricingSnapshotCodec.decode(multibyte) }.message)
        val original = OperationalPricingEngine.calculate(request(listOf(line(1, "10")), adjustments = listOf(fixed(0, listOf(id(1))))))
        // Deliberately invalid internal test records must hit resource guards before
        // duplicate-record validation or a potentially huge JSON allocation.
        val tooManyRecords = original.unsafeStoredListsForBudgetTest(discounts = List(4_096) { original.discounts.single() })
        assertEquals("Stored pricing record count exceeds bound",
            assertFailsWith<IllegalArgumentException> { StoredPricingSnapshotCodec.encode(tooManyRecords) }.message)
        val tooManyCharacters = original.unsafeStoredListsForBudgetTest(components = List(4_100) { original.components.first() })
        assertEquals("Stored pricing conservative character budget exceeded",
            assertFailsWith<IllegalArgumentException> { StoredPricingSnapshotCodec.encode(tooManyCharacters) }.message)
    }

    @Test fun persistedSnapshotsAreNotClaimedCryptographicApprovalEvidence() {
        val encoded = StoredPricingSnapshotCodec.encode(OperationalPricingEngine.calculate(request(listOf(line(1, "10")))))
        val schema = Json.parseToJsonElement(encoded) as JsonObject
        assertTrue("schemaVersion" in schema && "components" in schema && "discounts" in schema)
        assertTrue("authorizationToken" !in schema && "paymentConfirmed" !in schema)
    }
}

private fun PricingSnapshot.unsafeStoredListsForBudgetTest(
    components: List<PostedPriceComponent> = this.components,
    discounts: List<AppliedDiscountSnapshot> = this.discounts,
): PricingSnapshot = PricingSnapshot(snapshotId, revision, currency, profile, taxPostingPolicy, promotionResolution,
    discountLimits, beforeDiscountPayable, payable, exactBeforeDiscountPayable, exactPayable, lines, charges, components, discounts)

private fun edit(encoded: String, path: List<String>, replacement: JsonElement): String {
    fun change(element: JsonElement, index: Int): JsonElement {
        if (index == path.size) return replacement
        return when (element) {
            is JsonObject -> JsonObject(element.toMutableMap().also { it[path[index]] = change(it.getValue(path[index]), index + 1) })
            is JsonArray -> JsonArray(element.toMutableList().also { it[path[index].toInt()] = change(it[path[index].toInt()], index + 1) })
            else -> error("Test path does not address a container")
        }
    }
    return change(Json.parseToJsonElement(encoded), 0).toString()
}
