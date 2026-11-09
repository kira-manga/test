package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Codec/model tests only; no protected stock origin, runtime or real Room qualification. */
class InventoryReceiptCompanionCodecTest {
    private val f = ReceiptFixtures

    @Test fun fixtureCommandsRoundTripCanonicalExactScalarsWithoutBodyScopeOrActor() {
        val request = f.request().copy(quantity = Quantity(f.unit, Long.MAX_VALUE, 6),
            originalValue = Money(Currency.EGP, Long.MAX_VALUE), expiresAtEpochMillis = Long.MAX_VALUE)
        val bytes = InventoryReceiptCommandCodec.encode(request)
        assertEquals(request, InventoryReceiptCommandCodec.decode(bytes))
        assertEquals(bytes, InventoryReceiptCommandCodec.normalizeReceive(bytes))
        val text = bytes.copyBytes().decodeToString()
        assertTrue(text.contains("\"scaled\":\"9223372036854775807\""))
        assertTrue(text.contains("\"minor\":\"9223372036854775807\""))
        assertTrue(text.contains("\"expiresAtEpochMillis\":\"9223372036854775807\""))
        assertTrue(!text.contains("actor") && !text.contains("scope") && !text.contains("operationId"))
        assertEquals(InventoryReceiptCommandCodec.genesisRequest(),
            InventoryReceiptCommandCodec.normalizeGenesis(InventoryReceiptCommandCodec.genesisRequest()))
    }

    @Test fun commandRejectsInvalidQuantitiesValuesAndExpiryRatherThanCoercing() {
        val request = f.request()
        for (invalid in listOf(request.copy(quantity = request.quantity.copy(scaled = 0)),
            request.copy(originalValue = request.originalValue.copy(minor = -1)),
            request.copy(expiresAtEpochMillis = -1), request.copy(lotId = null))) {
            receiptFailure { InventoryReceiptCommandCodec.encode(invalid) }
        }
        val untracked = request.copy(lotId = null, expiresAtEpochMillis = null)
        assertEquals(untracked, InventoryReceiptCommandCodec.decode(InventoryReceiptCommandCodec.encode(untracked)))
    }

    @Test fun commandsRejectUnknownDuplicateAndNoncanonicalFields() {
        val text = InventoryReceiptCommandCodec.encode(f.request()).copyBytes().decodeToString()
        val cases = listOf(" $text", text.dropLast(1) + ",\"unexpected\":true}",
            text.replaceFirst("{", "{\"expectedRevision\":\"0\","),
            text.replace("\"expectedRevision\":\"0\"", "\"expectedRevision\":0"),
            text.replace("\"SYNTHETIC_FIXTURE\"", "\"PURCHASE_APPROVED\""))
        for (invalid in cases) receiptFailure { InventoryReceiptCommandCodec.decode(StoredBytes(invalid.encodeToByteArray())) }
        receiptFailure { InventoryReceiptCommandCodec.normalizeGenesis(StoredBytes("{}".encodeToByteArray())) }
        receiptFailure { InventoryReceiptCommandCodec.normalizeGenesis(StoredBytes("{\"profile\":\"production\"}".encodeToByteArray())) }
    }

    @Test fun canonicalEpochAndMoneyStringsCannotBeNumbersExponentsOrOverflow() {
        val text = InventoryReceiptCommandCodec.encode(f.request()).copyBytes().decodeToString()
        for (replacement in listOf(JsonPrimitive("02000000000000"), JsonPrimitive("-0"), JsonPrimitive("+2"),
            JsonPrimitive("1e3"), JsonPrimitive("9223372036854775808"), JsonPrimitive(1000))) {
            val changed = jsonEdit(text, listOf("expiresAtEpochMillis"), replacement)
            receiptFailure { InventoryReceiptCommandCodec.decode(StoredBytes(changed.encodeToByteArray())) }
        }
        for (replacement in listOf(JsonPrimitive("0101"), JsonPrimitive("1.0"), JsonPrimitive(101))) {
            val changed = jsonEdit(text, listOf("originalValue", "minor"), replacement)
            receiptFailure { InventoryReceiptCommandCodec.decode(StoredBytes(changed.encodeToByteArray())) }
        }
    }

    @Test fun genesisCompanionAndResultRoundTripOnlyTheirExplicitEmptyShape() {
        val writes = f.genesis()
        val companion = writes.companion
        val bytes = InventoryReceiptCompanionCodec.encode(companion)
        val restored = InventoryReceiptCompanionCodec.decode(bytes, companionBinding(companion))
        assertEquals(bytes, InventoryReceiptCompanionCodec.encode(restored))
        assertEquals(emptyList(), restored.sources)
        assertEquals(emptyList(), restored.effects)
        assertNull(restored.previous)
        val result = InventoryReceiptResultCodec.decode(writes.result)
        assertEquals(InventoryReceiptResultKind.GENESIS, result.kind)
        assertEquals(Revision(0), result.inventoryRevision)
        assertNull(result.receiptId)
        assertEquals(restored.producer.operationId, result.operationId)
    }

    @Test fun receivedCompanionRetainsExactlyTwoOrderedFactsAndPreviousPairReferences() {
        val prior = f.one()
        val writes = f.receive(prior, 2)
        val restored = InventoryReceiptCompanionCodec.decode(InventoryReceiptCompanionCodec.encode(writes.companion), companionBinding(writes.companion))
        assertEquals(writes.companion.sources, restored.sources)
        assertEquals(writes.companion.effects, restored.effects)
        assertIs<InventoryReceiptPostedEffect.Valuation>(restored.effects[0])
        assertIs<InventoryReceiptPostedEffect.Movement>(restored.effects[1])
        val previous = assertNotNull(restored.previous)
        assertEquals(prior.cutRow.digest, previous.cutDigest)
        assertEquals(prior.companionRow.digest, previous.companionDigest)
        assertEquals(prior.companion.producer.operationId, previous.producerOperationId)
        assertEquals(Revision(1), previous.inventoryRevision)
    }

    @Test fun everyIndependentCompanionBindingDimensionMustMatch() {
        val companion = f.one().companion
        val bytes = InventoryReceiptCompanionCodec.encode(companion)
        val binding = companionBinding(companion)
        for (wrong in listOf(binding.copy(scope = BranchScope(f.id(80), f.scope.companyId, f.scope.branchId)),
            binding.copy(scope = BranchScope(f.scope.organizationId, f.id(81), f.scope.branchId)),
            binding.copy(scope = BranchScope(f.scope.organizationId, f.scope.companyId, f.id(82))),
            binding.copy(id = f.id(83)), binding.copy(inventoryRevision = Revision(0)))) {
            receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { InventoryReceiptCompanionCodec.decode(bytes, wrong) }
        }
    }

    @Test fun missingReorderedOrSubstitutedReceiptEffectsReject() {
        val companion = f.one().companion
        val value = companion.effects[0] as InventoryReceiptPostedEffect.Valuation
        val movement = companion.effects[1] as InventoryReceiptPostedEffect.Movement
        val cases: List<List<InventoryReceiptPostedEffect>> = listOf(emptyList(), listOf(value), listOf(movement, value), listOf(value, value),
            listOf(value.copy(origin = ReceiptValueOrigin.OPENING), movement),
            listOf(value.copy(carryingValueDelta = Money(Currency.EGP, 100)), movement),
            listOf(value.copy(requiredPoolRevision = Revision(1)), movement),
            listOf(value, movement.copy(reason = StockMovementReason.ISSUE)),
            listOf(value, movement.copy(sourceAllocationId = f.id(88))))
        for (effects in cases) receiptFailure { InventoryReceiptCompanionCodec.encode(copyCompanion(companion, effects = effects)) }
    }

    @Test fun unknownSchemaEffectsOwnershipAndCostStateCannotBeIgnored() {
        val companion = f.one().companion
        val text = InventoryReceiptCompanionCodec.encode(companion).copyBytes().decodeToString()
        val cases = listOf(text.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            text.replace("fixture-receipts-v1", "production-receipts-v1"),
            text.replace("RECEIPT_VALUATION", "COST_CORRECTION"),
            text.replace("COMPANY_OWNED", "SUPPLIER_OWNED"),
            text.replace("\"custody\":\"RELEASED\"", "\"custody\":\"QUARANTINE\""),
            text.dropLast(1) + ",\"costSources\":[]}")
        for (invalid in cases) receiptFailure {
            InventoryReceiptCompanionCodec.decode(StoredBytes(invalid.encodeToByteArray()), companionBinding(companion))
        }
    }

    @Test fun utf8NestingArrayContainerAndRequestByteBoundsRunBeforeRecursiveDecoding() {
        val binding = companionBinding(f.one().companion)
        for (raw in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            val invalid = StoredBytes(raw)
            receiptFailure(InventoryReceiptStoreFailure.MALFORMED) { InventoryReceiptCompanionCodec.decode(invalid, binding) }
            receiptFailure(InventoryReceiptStoreFailure.MALFORMED) { InventoryReceiptCommandCodec.decode(invalid) }
            receiptFailure(InventoryReceiptStoreFailure.MALFORMED) { InventoryReceiptCommandCodec.normalizeGenesis(invalid) }
            receiptFailure(InventoryReceiptStoreFailure.MALFORMED) { InventoryReceiptResultCodec.decode(invalid) }
        }
        for (text in listOf("[".repeat(17) + "]".repeat(17),
            "[" + List(33) { "0" }.joinToString(",") + "]",
            "{" + List(1_025) { "\"x$it\":{}" }.joinToString(",") + "}")) {
            receiptFailure(InventoryReceiptStoreFailure.TOO_LARGE) {
                InventoryReceiptCompanionCodec.decode(StoredBytes(text.encodeToByteArray()), binding)
            }
        }
        receiptFailure(InventoryReceiptStoreFailure.TOO_LARGE) {
            InventoryReceiptCommandCodec.decode(StoredBytes(ByteArray(InventoryReceiptStoreProfile.MAX_REQUEST_BYTES + 1) { 32 }))
        }
    }

    @Test fun duplicateSourceReceiptOriginOrProducingOperationIsNotACompleteIndex() {
        val companion = f.receive(f.one(), 2).companion
        val first = companion.sources[0]
        val second = companion.sources[1]
        for (changed in listOf(second.copy(receiptId = first.receiptId), second.copy(origin = first.origin),
            second.copy(producingOperationId = first.producingOperationId))) {
            receiptFailure { InventoryReceiptCompanionCodec.encode(copyCompanion(companion, sources = listOf(first, changed))) }
        }
    }

    @Test fun olderMemberIsNotLatestProducerAndPreviousPairCannotPointToSelf() {
        val companion = f.receive(f.one(), 2).companion
        receiptFailure { InventoryReceiptCompanionCodec.encode(copyCompanion(companion,
            producer = companion.producer.copy(operationId = companion.sources.first().producingOperationId))) }
        receiptFailure { InventoryReceiptCompanionCodec.encode(copyCompanion(companion,
            previous = companion.previous!!.copy(producerOperationId = companion.producer.operationId))) }
        receiptFailure { InventoryReceiptCompanionCodec.encode(copyCompanion(companion,
            previous = companion.previous!!.copy(cutDigest = companion.cutDigest))) }
    }

    @Test fun listsAndBytesAreDefensiveAndCannotBeUsedToRewritePostedFacts() {
        val companion = f.one().companion
        val sources = companion.sources.toMutableList()
        val effects = companion.effects.toMutableList()
        val copied = copyCompanion(companion, sources = sources, effects = effects)
        val bytes = InventoryReceiptCompanionCodec.encode(copied)
        sources.clear()
        effects.reverse()
        assertEquals(bytes, InventoryReceiptCompanionCodec.encode(copied))
        val restored = InventoryReceiptCompanionCodec.decode(bytes, companionBinding(copied))
        assertFails { (restored.sources as MutableList<InventoryReceiptSourceBinding>).clear() }
        assertFails { (restored.effects as MutableList<InventoryReceiptPostedEffect>).clear() }
        val detached = bytes.copyBytes()
        detached.fill(0)
        assertEquals(bytes, InventoryReceiptCompanionCodec.encode(restored))
    }

    @Test fun resultRejectsUnsupportedOrInconsistentShapeAndPreservesExactDigests() {
        val writes = f.receive(f.current(f.genesis()))
        val result = InventoryReceiptResultCodec.decode(writes.result)
        assertEquals(InventoryReceiptResultKind.RECEIVED, result.kind)
        assertEquals(writes.companion.cutDigest, result.cutDigest)
        assertEquals(writes.result, InventoryReceiptResultCodec.encode(result))
        for (invalid in listOf(result.copy(kind = InventoryReceiptResultKind.GENESIS), result.copy(receiptId = null),
            result.copy(inventoryRevision = Revision(0)))) receiptFailure { InventoryReceiptResultCodec.encode(invalid) }
        val text = writes.result.copyBytes().decodeToString()
        for (invalid in listOf(text.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            text.replace(result.cutDigest.value, "xyz"), text.dropLast(1) + ",\"paid\":true}")) {
            receiptFailure { InventoryReceiptResultCodec.decode(StoredBytes(invalid.encodeToByteArray())) }
        }
        assertEquals(PayloadDigest(result.cutDigest.value), result.cutDigest)
    }
}
