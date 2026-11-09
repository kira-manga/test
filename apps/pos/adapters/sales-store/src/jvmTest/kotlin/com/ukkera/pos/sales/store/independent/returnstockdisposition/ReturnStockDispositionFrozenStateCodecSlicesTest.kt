@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnstockdisposition

import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Supporting pure-state/codec slices of frozen RSD v1, never whole-case passes.
 * No actual allocation/cost, inspection, acceptance/history, authority, persistence,
 * protected read, stock posting, payout, tracking or communication is bound here. */
class ReturnStockDispositionFrozenStateCodecSlicesTest {
    private val inputs get() = FrozenReturnStockDispositionInputs

    @Test
    fun rsd001a_zeroPostedScalarStatusKeepsFrozenAcceptancePending() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val zero = data.quantity("0")
        val remaining = data.quantity(data.fixture.objectAt("before_stock_post")
            .literal("current_remaining_disposition_quantity"))
        assertEquals(LocalReturnStockStatusV1.PENDING, returnStockStatus(zero, data.acceptedQuantity))
        assertEquals(data.acceptedQuantity, zero + remaining)
        assertRefundCarrierUnchanged(data)
    }

    @Test
    fun rsd001c_stockRequestCodecRetainsOnlyInertEvidenceCoordinates() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val request = data.request()
        val encoded = LocalReturnStockCodecV1.encode(request)
        val decoded = LocalReturnStockCodecV1.decode(encoded)
        assertEquals(request, decoded)
        assertEquals(data.inspection, decoded.inspectionEvidence)
        assertEquals(data.acceptedQuantity, decoded.acceptedQuantity)
        assertEquals(data.quantity("0"), decoded.expectedPostedQuantity)
        assertEquals(encoded, LocalReturnStockCodecV1.normalize(encoded))
        val mutation = LocalReturnStockCodecV1.request(inputs.operation(900), request)
        assertEquals(StockStreamingCommandsV2.RETURN, mutation.command)
        assertEquals(inputs.operation(900), mutation.operationId)
        assertEquals(encoded, mutation.payload)
        assertNull(mutation.actionApprovalId)
        // This DTO has no literal reported-time or authoritative actor field.
        // Its roundtrip does not prove actual inspection, attribution or permission.
    }

    @Test
    fun rsd002_fullCoreQuantityConsumesOnlyFrozenCurrentAcceptance() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val request = data.request()
        val posting = data.post(request)
        val expected = data.fixture.objectAt("after_all_two_units_sellable_post")
        assertEquals(data.quantity(expected.literal("quantity_delta")), posting.quantity)
        assertFrontier(data, posting, data.acceptedLiteral, expected.literal("remaining_disposition_quantity"),
            LocalReturnStockStatusV1.POSTED)
        assertEquals(data.acceptanceOperationId, posting.after.acceptanceOperationId)
        assertEquals(data.acceptanceDigest, posting.after.acceptanceDigest)
        assertRefundCarrierUnchanged(data)
        // Inventory +40.00, pool 4/120.00, cash, entitlement and historical cost
        // require genuine persisted source/effect readers, which are not supplied.
    }

    @Test
    fun rsd003a_twoOneUnitDataPostsConserveAcceptedDispositionNotEntitlement() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val firstRequest = data.request(data.quantity("1")) // Exact frozen action RSD-003(a).
        val first = data.post(firstRequest)
        val partial = data.fixture.objectAt("after_one_unit_sellable_post")
        assertFrontier(data, first, "1", partial.literal("remaining_disposition_quantity"),
            LocalReturnStockStatusV1.PARTIALLY_POSTED)
        assertEquals(data.quantity("0"), firstRequest.expectedPostedQuantity)
        val firstStateBeforeContinuation = first.after.copy()
        val secondRequest = data.request(first.result.remainingQuantity, first.after, postingNumber = 2)
        assertEquals(data.quantity("1"), secondRequest.expectedPostedQuantity)
        val second = data.post(secondRequest, first.after, operationNumber = 2)
        val complete = data.fixture.objectAt("after_all_two_units_sellable_post")
        assertEquals(data.quantity("1"), first.quantity)
        assertEquals(data.quantity("1"), second.quantity)
        assertFrontier(data, second, data.acceptedLiteral, complete.literal("remaining_disposition_quantity"),
            LocalReturnStockStatusV1.POSTED)
        assertNotEquals(first.operationId, second.operationId)
        assertNotEquals(first.request.restorationId, second.request.restorationId)
        assertEquals(firstStateBeforeContinuation, first.after)
        assertEquals(data.quantity(partial.literal("remaining_disposition_quantity")), first.result.remainingQuantity)
        assertEquals(data.quantity("1"), data.consumedBefore)
        assertRefundCarrierUnchanged(data)
    }

    @Test
    fun rsd003b_rsd010_newDataIdentityCannotExceedOrRepostAcceptedQuantity() {
        val data = FrozenReturnStockDataCarrier("CORE")
        assertFailsWith<LocalReturnStockRefused> { data.post(data.request(data.quantity("3"))) }
        val complete = data.post(data.request())
        val before = complete.after.copy()
        val repost = data.request(data.quantity("1"), before, postingNumber = 2)
        assertFailsWith<LocalReturnStockRefused> { data.post(repost, before, operationNumber = 2) }
        assertEquals(complete.after, before)
        assertEquals(data.acceptedQuantity, before.postedQuantity)
        assertRefundCarrierUnchanged(data)
        // Same-operation durable replay, business ledgers and source uniqueness are UNBOUND.
    }

    @Test
    fun rsd003c_foreignScalarSaleCaseReturnAndPortionBindingsRefuse() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val request = data.request()
        val foreign = inputs.id(900)
        for (wrong in listOf(request.copy(saleId = foreign), request.copy(caseId = foreign), request.copy(returnId = foreign))) {
            assertFailsWith<IllegalArgumentException> { data.post(wrong) }
        }
        val first = data.post(data.request(data.quantity("1")))
        val next = data.request(first.result.remainingQuantity, first.after, postingNumber = 2)
        assertFailsWith<IllegalArgumentException> {
            data.post(next, first.after.copy(portionId = foreign), operationNumber = 2)
        }
        assertEquals(data.portionId, first.after.portionId)
        assertRefundCarrierUnchanged(data)
        // No receipt, issued-cost or earlier-return history is loaded by this scalar guard.
    }

    @Test
    fun rsd003c_guardedSourceQuantitiesCannotReplaceFrozenCoreFrontier() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val originalRequest = data.request(data.quantity("1"))
        val wrongAccepted = data.quantity(data.fixture.objectAt("before_stock_post")
            .literal("remaining_original_return_entitlement_quantity"))
        assertFailsWith<LocalReturnStockRefused> { data.post(originalRequest.copy(acceptedQuantity = wrongAccepted)) }
        assertFailsWith<LocalReturnStockRefused> {
            data.post(originalRequest.copy(expectedPostedQuantity = data.quantity("1")))
        }
        val first = data.post(originalRequest)
        val next = data.request(first.result.remainingQuantity, first.after, postingNumber = 2)
            .copy(expectedPostedQuantity = originalRequest.expectedPostedQuantity)
        assertFailsWith<LocalReturnStockRefused> { data.post(next, first.after, operationNumber = 2) }
        assertEquals(data.quantity("1"), first.after.postedQuantity)
        assertEquals(data.quantity("1"), first.result.remainingQuantity)
        assertRefundCarrierUnchanged(data)
        // Caller assertions cannot replace the helper's accepted/prior DATA.
        // Their actual native acceptance/source qualification remains UNBOUND.
    }

    @Test
    fun rsd003d_missingDataPredecessorDigestCannotContinueScalarFrontier() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val first = data.post(data.request(data.quantity("1")))
        val next = data.request(first.result.remainingQuantity, first.after, postingNumber = 2)
        assertFailsWith<IllegalArgumentException> {
            data.post(next, first.after, operationNumber = 2, beforeDigest = null)
        }
        assertEquals(data.quantity("1"), first.after.postedQuantity)
        assertEquals(data.quantity("1"), first.result.remainingQuantity)
        assertRefundCarrierUnchanged(data)
        // A data fingerprint is not actual immutable original/history evidence.
    }

    @Test
    fun rsd004a_quarantineScalarCustodyCannotBeRelabeledReleased() {
        val data = FrozenReturnStockDataCarrier("CORE", ReturnDisposition.QUARANTINE)
        assertFailsWith<LocalReturnStockRefused> { data.post(data.request(custody = LocalReturnStockCustodyV1.RELEASED)) }
        val posting = data.post(data.request(custody = LocalReturnStockCustodyV1.QUARANTINE))
        val remaining = inputs.fixture("ROUTES").objectAt("quarantine_branch").literal("remaining_disposition_quantity")
        assertFrontier(data, posting, data.acceptedLiteral, remaining, LocalReturnStockStatusV1.POSTED)
        assertTrue(posting.request.selections.all { it.custody == LocalReturnStockCustodyV1.QUARANTINE })
        assertRefundCarrierUnchanged(data)
        // No configured destination, availability or non-sellable valuation policy is invented.
        // U-DISPOSITION and the actual quarantine/waste/supplier cases remain UNBOUND.
    }

    @Test
    fun rsd006a_taxAcceptedQuantityCodecRetainsOriginalRefundCarrierWithoutPosting() {
        val data = FrozenReturnStockDataCarrier("TAX")
        assertEquals(data.acceptedQuantity, quantityCodecRoundtrip(data.acceptedQuantity))
        assertRefundCarrierUnchanged(data)
        // Exact guarded prior-posted quantity is absent from this frozen fixture.
        // Stock request/frontier, price/tax/cost and pool 5/108.00 remain UNBOUND.
    }

    @Test
    fun rsd006b_packQuantityCodecKeepsFrozenThreeEachNotLaterFive() {
        val data = FrozenReturnStockDataCarrier("PACK")
        val decoded = quantityCodecRoundtrip(data.acceptedQuantity)
        assertEquals(data.quantity(data.fixture.objectAt("current_accepted_return").literal("base_quantity")), decoded)
        assertNotEquals(data.quantity(data.fixture.objectAt("later_controlled_pack_version").literal("each_per_pack")), decoded)
        assertRefundCarrierUnchanged(data)
        // No guarded prior-posted quantity is supplied, so no stock request/post is
        // constructed. Actual pack conversion, lot and resulting stock stay UNBOUND.
    }

    @Test
    fun rsd006c_quarterKgQuantityCodecRetainsExactAcceptedPrecisionWithoutPosting() {
        val data = FrozenReturnStockDataCarrier("MEASURE")
        assertEquals(data.acceptedQuantity, quantityCodecRoundtrip(data.acceptedQuantity))
        assertRefundCarrierUnchanged(data)
        // The exact guarded prior-posted frontier is not frozen here. No stock
        // action, 0.00 disposition remainder or pool/average 1.00/55.00 is claimed.
    }

    @Test
    fun rsd006d_coreZeroNegativeAndExcessRefuseWithoutDataAllocation() {
        val data = FrozenReturnStockDataCarrier("CORE")
        for (literal in listOf("0", "-1", "3")) { // Exact frozen CORE invalid actions.
            val request = data.request(data.quantity(literal))
            if (literal != "3") assertFailsWith<IllegalArgumentException> { LocalReturnStockCodecV1.encode(request) }
            assertFailsWith<LocalReturnStockRefused> { data.post(request) }
        }
        assertRefundCarrierUnchanged(data)
    }

    @Test
    fun rsd006d_measuredInvalidQuantityTypeOrStatusBoundsRefuseWithoutRounding() {
        val data = FrozenReturnStockDataCarrier("MEASURE")
        for (element in data.fixture.getValue("invalid_quantities").jsonArray) {
            val literal = element.jsonPrimitive.content
            assertFails {
                val quantity = data.quantity(literal).requirePositive()
                returnStockStatus(quantity, data.acceptedQuantity)
            }
        }
        val unsupported = data.fixture.getValue("invalid_quantities").jsonArray.last().jsonPrimitive.content
        val alternatePrecision = Quantity.parse(inputs.kgUnit, unsupported, 3)
        assertFailsWith<IllegalArgumentException> { returnStockStatus(alternatePrecision, data.acceptedQuantity) }
        assertRefundCarrierUnchanged(data)
        // Numeric/type/status guards only; no guessed current-disposition frontier
        // is supplied to the stock request or posting boundary for MEASURE.
    }

    @Test
    fun rsd006d_originalScalarUnitAndPrecisionSnapshotsCannotBeChanged() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val wrongUnit = data.acceptedQuantity.copy(unitId = inputs.kgUnit)
        val wrongPrecision = Quantity.parse(inputs.eachUnit, data.acceptedLiteral, 1)
        for (quantity in listOf(wrongUnit, wrongPrecision)) {
            assertFailsWith<LocalReturnStockRefused> { data.post(data.request(quantity)) }
        }
        assertRefundCarrierUnchanged(data)
    }

    @Test
    fun rsd006d_exactFrozenOversizedLiteralFailsParserAndActualRequestCodec() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val oversized = inputs.frozen.literal("invalid_oversized_each_quantity")
        assertFails { data.quantity(oversized) }
        val encoded = LocalReturnStockCodecV1.encode(data.request())
        val root = inputs.json.parseToJsonElement(encoded.copyBytes().decodeToString()).jsonObject
        val selections = root.getValue("selections").jsonArray
        val selection = selections.single().jsonObject
        val quantity = selection.getValue("quantity").jsonObject
        val oversizedQuantity = JsonObject(quantity.toMutableMap().apply { put("scaled", JsonPrimitive(oversized)) })
        val oversizedSelection = JsonObject(selection.toMutableMap().apply { put("quantity", oversizedQuantity) })
        val malformed = JsonObject(root.toMutableMap().apply { put("selections", JsonArray(listOf(oversizedSelection))) })
        assertFails { LocalReturnStockCodecV1.normalize(StoredBytes(malformed.toString().encodeToByteArray())) }
        assertRefundCarrierUnchanged(data)
    }

    @Test
    fun rsd011_staleDataDispositionRevisionCannotContinuePartialScalarFrontier() {
        val data = FrozenReturnStockDataCarrier("CORE")
        val stale = data.request(data.quantity("1"))
        val first = data.post(stale)
        val next = data.request(first.result.remainingQuantity, first.after, postingNumber = 2)
            .copy(expectedDispositionRevision = stale.expectedDispositionRevision)
        assertFailsWith<LocalReturnStockRefused> { data.post(next, first.after, operationNumber = 2) }
        assertEquals(data.quantity("1"), first.after.postedQuantity)
        assertEquals(data.quantity("1"), first.result.remainingQuantity)
        assertRefundCarrierUnchanged(data)
        // No actual competing writers, commit serialization or revocation is exercised.
    }

    private fun assertFrontier(data: FrozenReturnStockDataCarrier, posting: LocalReturnStockPostingV1,
        posted: String, remaining: String, status: LocalReturnStockStatusV1) {
        assertEquals(data.quantity(posted), posting.after.postedQuantity)
        assertEquals(data.quantity(posted), posting.result.postedQuantity)
        assertEquals(data.quantity(remaining), posting.result.remainingQuantity)
        assertEquals(data.acceptedQuantity, posting.after.acceptedQuantity)
        assertEquals(data.acceptedQuantity, posting.result.postedQuantity + posting.result.remainingQuantity)
        assertEquals(status, posting.result.status)
        assertEquals(posting.after.revision, posting.result.revision)
        assertEquals(data.returnId, posting.result.returnId)
        assertEquals(posting.request.restorationId, posting.result.restorationId)
        assertEquals(posting.result, LocalReturnStockResultCodecV1.decode(LocalReturnStockResultCodecV1.encode(posting.result)))
    }

    private fun quantityCodecRoundtrip(value: Quantity): Quantity = inputs.json.decodeFromJsonElement(Quantity.serializer(),
        inputs.json.encodeToJsonElement(Quantity.serializer(), value))

    private fun assertRefundCarrierUnchanged(data: FrozenReturnStockDataCarrier) {
        assertEquals(data.acceptedQuantity, data.accepted.quantity)
        assertEquals(data.consumedBefore, data.accepted.consumedQuantityBefore)
        assertEquals(data.originalPayable, data.accepted.originalPayable)
        assertEquals(data.originalPayable, data.accepted.due)
        assertEquals(Money(Currency.EGP, 0), data.accepted.refunded)
        assertEquals(data.claimEvidence, data.accepted.evidence)
        assertTrue(data.accepted.attempts.isEmpty())
        // Immutable input DATA only; no financial ledger, cash drawer or entitlement
        // is connected, so these checks cannot establish native no-effect assertions.
    }
}
