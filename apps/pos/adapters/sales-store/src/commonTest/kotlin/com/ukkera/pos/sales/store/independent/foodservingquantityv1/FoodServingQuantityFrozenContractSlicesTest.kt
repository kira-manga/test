package com.ukkera.pos.sales.store.independent.foodservingquantityv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.store.LocalFoodCodecV1
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodProfileV1
import com.ukkera.pos.sales.store.LocalFoodRequestV1
import com.ukkera.pos.sales.store.LocalFoodSelectionGroups
import com.ukkera.pos.sales.store.localSalesDecode
import com.ukkera.pos.sales.store.localSalesEncode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L0 contract/codec slices of frozen FQ-V1-01/02/03/04 and X-BOUNDARY only.
 * Grouping is the chosen contract representation, not a prescribed commercial
 * layout. These checks do not save an order, authorize a session, exercise an
 * installed editor or establish persistence/RPC/retry outcomes. Whole cases
 * and all required integration variants remain NOT_RUN.
 */
class FoodServingQuantityFrozenContractSlicesTest {
    private val f = FrozenFoodServingQuantityInputs

    @Test
    fun FQ_V1_01_TwoConfiguredServingsRetainOneCommercialIdentityAndExactLineNotes() {
        val input = f.twoServings()
        val group = LocalFoodSelectionGroups.group(input).single()
        assertEquals(f.lineId, group.lineId)
        assertEquals(f.two, group.totalQuantity)
        assertEquals(listOf(f.lineId, f.secondPortionId), group.portions.map { it.portionId })
        assertEquals(2, group.portions.size)
        for (portion in group.portions) {
            assertEquals(f.lineId, portion.commercialLineId)
            assertEquals(f.variantId, portion.itemId)
            assertEquals(f.recipeId, portion.recipeId)
            assertEquals(f.originalRecipeRevision, portion.expectedRecipeRevision)
            assertEquals(f.variantId, portion.choiceId)
            assertEquals(listOf(f.cheeseOptionId), portion.modifierIds)
            assertEquals(f.one, portion.quantity)
            assertEquals(f.ITEM_NOTE, portion.itemNote)
            assertEquals(f.SEAT_NOTE, portion.seatNote)
        }
    }

    @Test
    fun FQ_V1_01_02_AddCodecPreservesBothLineNotesAndOriginalSelectionIds() {
        for (recipeRevision in listOf(f.originalRecipeRevision, f.currentRecipeRevision)) {
            val request = f.add(f.twoServings(recipeRevision))
            val bytes = LocalFoodCodecV1.encode(request)
            val decoded = assertIs<LocalFoodRequestV1.Add>(LocalFoodCodecV1.decode(bytes))
            assertEquals(f.orderId, decoded.orderId)
            assertEquals(f.existingOrderRevision, decoded.expectedOrderRevision)
            assertEquals(request.expectedCatalogRevision, decoded.expectedCatalogRevision)
            assertEquals(bytes, LocalFoodCodecV1.normalize(bytes))
            val group = LocalFoodSelectionGroups.group(decoded.portions).single()
            assertEquals(f.lineId, group.lineId)
            assertEquals(f.two, group.totalQuantity)
            assertTrue(group.portions.all {
                it.itemId == f.variantId && it.choiceId == f.variantId &&
                    it.modifierIds == listOf(f.cheeseOptionId) &&
                    it.expectedRecipeRevision == recipeRevision &&
                    it.itemNote == f.ITEM_NOTE && it.seatNote == f.SEAT_NOTE
            })
        }
    }

    @Test
    fun FQ_V1_03_IncompatibleServingIdentityOrNotesCannotCollapseIntoOneLine() {
        val original = f.twoServings()
        val second = original[1]
        val changed = listOf(
            second.copy(itemId = f.lookalikeVariantId),
            second.copy(choiceId = f.lookalikeVariantId),
            second.copy(modifierIds = listOf(f.lookalikeCheeseOptionId)),
            second.copy(expectedRecipeRevision = f.currentRecipeRevision),
            second.copy(quantity = f.two),
            second.copy(itemNote = ""),
            second.copy(seatNote = ""),
        )
        for (incompatible in changed) {
            val input = listOf(original[0], incompatible)
            assertFailsWith<IllegalArgumentException> { LocalFoodSelectionGroups.group(input) }
            assertFailsWith<IllegalArgumentException> { LocalFoodCodecV1.encode(f.add(input)) }
        }
        assertEquals(f.two, LocalFoodSelectionGroups.group(original).single().totalQuantity)
        assertTrue(original.all { it.itemNote == f.ITEM_NOTE && it.seatNote == f.SEAT_NOTE })
    }

    @Test
    fun FQ_V1_X_BOUNDARY_DuplicateServingIdsAndNonpositiveSelectionQuantityReject() {
        val original = f.twoServings()
        val duplicate = listOf(original[0], original[1].copy(portionId = original[0].portionId))
        assertFailsWith<IllegalArgumentException> { LocalFoodSelectionGroups.group(duplicate) }
        assertFailsWith<IllegalArgumentException> { LocalFoodCodecV1.encode(f.add(duplicate)) }
        for (quantity in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> {
                original[0].copy(quantity = f.one.copy(scaled = quantity))
            }
        }
        assertEquals(f.two, LocalFoodSelectionGroups.group(original).single().totalQuantity)
    }

    @Test
    fun FQ_V1_04_ChangedQuantityNotesOrSourceRetainDistinctOriginalCommandSemantics() {
        val original = f.add()
        val originalCommand = LocalFoodCodecV1.command(f.saveOperationId, original)
        val originalDigest = semanticDigest(f.scope, f.ownerA, originalCommand.command, originalCommand.payload)
        val changed = listOf(
            original.copy(portions = original.portions.take(1)),
            original.copy(portions = original.portions.map { it.copy(itemNote = "salt") }),
            original.copy(portions = original.portions.map { it.copy(seatNote = "Seat 02") }),
            original.copy(portions = original.portions.map {
                it.copy(itemId = f.lookalikeVariantId, choiceId = f.lookalikeVariantId,
                    modifierIds = listOf(f.lookalikeCheeseOptionId))
            }),
        )
        for (request in changed) {
            val command = LocalFoodCodecV1.command(f.saveOperationId, request)
            assertEquals(f.saveOperationId, command.operationId)
            assertEquals(LocalFoodProfileV1.COMMAND, command.command)
            assertNotEquals(originalCommand.payload, command.payload)
            assertNotEquals(originalDigest, semanticDigest(f.scope, f.ownerA, command.command, command.payload))
        }
        assertNotEquals(originalDigest,
            semanticDigest(f.scope, f.otherEmployee, originalCommand.command, originalCommand.payload))
        assertEquals(original, LocalFoodCodecV1.decode(originalCommand.payload))
        // Distinct semantics do not prove the kernel's conflict, recovery or exactly-once result.
    }

    @Test
    fun FQ_V1_04_LegacySelectionAndAddBytesNormalizeWithoutNewDefaultFields() {
        val selectionBytes = StoredBytes(f.legacySelectionJson.encodeToByteArray())
        val selection = localSalesDecode(selectionBytes, LocalFoodPortionSelectionV1.serializer())
        assertEquals(f.lineId, selection.portionId)
        assertEquals(f.lineId, selection.commercialLineId)
        assertEquals(f.one, selection.quantity)
        assertEquals(f.ITEM_NOTE, selection.itemNote)
        assertNull(selection.lineId)
        assertEquals("", selection.seatNote)
        assertEquals(selectionBytes, localSalesEncode(selection, LocalFoodPortionSelectionV1.serializer()))

        val originalBytes = StoredBytes(f.legacyAddJson.encodeToByteArray())
        val request = assertIs<LocalFoodRequestV1.Add>(LocalFoodCodecV1.decode(originalBytes))
        assertEquals(selection, request.portions.single())
        assertEquals(f.orderId, request.orderId)
        assertEquals(originalBytes, LocalFoodCodecV1.encode(request))
        assertEquals(originalBytes, LocalFoodCodecV1.normalize(originalBytes))
        assertEquals(f.one, LocalFoodSelectionGroups.group(request.portions).single().totalQuantity)
    }
}
