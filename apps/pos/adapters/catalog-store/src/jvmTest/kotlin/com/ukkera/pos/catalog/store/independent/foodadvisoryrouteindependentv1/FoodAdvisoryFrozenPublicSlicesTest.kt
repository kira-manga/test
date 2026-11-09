package com.ukkera.pos.catalog.store.independent.foodadvisoryrouteindependentv1

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalCatalogFoodSource
import com.ukkera.pos.catalog.store.LocalCatalogFoodStockSource
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.LocalFoodCodecV1
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodRequestV1
import com.ukkera.pos.sales.store.LocalFoodSelectionPreviewCodecV1
import com.ukkera.pos.sales.store.LocalFoodSelectionPreviewReadHandler
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.TransactionFoodRecipeSourceV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Public slices of food-advisory-route-independent-v1: FAR-V1-01, the constructor
 * part of FAR-V1-03 INVALID-QUANTITY and FAR-V1-04 CREATE-DELIVERY. Inputs and
 * assertions bind the frozen cases/fixtures without submitting Create or Add.
 *
 * Both production catalog adapter compositions exercise only the real preview
 * key/normalizer. No source preparation, catalog rows, ReadContext, authorization,
 * Available result, durable zero-effect readback, native storage or RPC is supplied.
 * In particular the JDK reference hashes are not genuine Available.requestDigest
 * observations. Every source/Master/native/RPC and whole-case assertion is UNBOUND.
 */
class FoodAdvisoryFrozenPublicSlicesTest {
    private val f = FrozenFoodAdvisoryPublicInputs

    @Test
    fun far01CreateBytesRetainExistingTypedSelectionAndReadKey() {
        assertExistingBytesAndReadKey(f.create())
    }

    @Test
    fun far01AddBytesRetainExistingTypedSelectionAndReadKey() {
        assertExistingBytesAndReadKey(f.add())
    }

    @Test
    fun far01WhitespaceNormalizesToCanonicalBytesWithIndependentSha256() {
        val canonical = LocalFoodCodecV1.encode(f.add())
        // Leading/trailing JSON whitespace is outside every existing string.
        val expanded = bytes(" \n\t" + canonical.copyBytes().decodeToString() + "\r\n ")
        val canonicalDigest = referenceSha256(canonical)
        assertNotEquals(canonical, expanded)
        assertNotEquals(canonicalDigest, referenceSha256(expanded))
        for (handler in previewNormalizers()) {
            val normalized = handler.normalize(expanded)
            assertEquals(canonical, normalized)
            assertEquals(canonicalDigest, referenceSha256(normalized))
        }
        // A genuine Available.requestDigest observation remains UNBOUND.
    }

    @Test
    fun far01TypedPortionListOrderIsPreservedAndChangesIndependentDigest() {
        val original = f.add()
        val reversed = original.copy(portions = original.portions.reversed())
        val originalBytes = LocalFoodSelectionPreviewCodecV1.encode(original)
        val reversedBytes = LocalFoodSelectionPreviewCodecV1.encode(reversed)
        assertEquals(LocalFoodCodecV1.encode(original), originalBytes)
        assertEquals(LocalFoodCodecV1.encode(reversed), reversedBytes)
        assertEquals(listOf(f.firstPortionId, f.secondPortionId),
            assertIs<LocalFoodRequestV1.Add>(LocalFoodCodecV1.decode(originalBytes)).portions.map { it.portionId })
        assertEquals(listOf(f.secondPortionId, f.firstPortionId),
            assertIs<LocalFoodRequestV1.Add>(LocalFoodCodecV1.decode(reversedBytes)).portions.map { it.portionId })
        assertNotEquals(originalBytes, reversedBytes)
        assertNotEquals(referenceSha256(originalBytes), referenceSha256(reversedBytes))
        for (handler in previewNormalizers()) {
            assertEquals(originalBytes, handler.normalize(originalBytes))
            assertEquals(reversedBytes, handler.normalize(reversedBytes))
        }
    }

    @Test
    fun far01UnknownActorKeyIsRejectedAtCodecAndRealReadNormalizer() {
        val canonical = LocalFoodCodecV1.encode(f.add()).copyBytes().decodeToString()
        val forged = bytes("{\"forgedActorId\":\"${f.otherOwnerId.value}\"," + canonical.removePrefix("{"))
        assertFailsWith<IllegalArgumentException> { LocalFoodCodecV1.decode(forged) }
        assertFailsWith<IllegalArgumentException> { LocalFoodCodecV1.normalize(forged) }
        for (handler in previewNormalizers()) {
            assertFailsWith<IllegalArgumentException> { handler.normalize(forged) }
        }
        // This ingress assertion does not establish protected denial/disclosure.
    }

    @Test
    fun far01ExactNoteBoundsAndControlCharactersAreRejected() {
        val selected = f.portions().first()
        assertFailsWith<IllegalArgumentException> { selected.copy(itemNote = "x".repeat(241)) }
        assertFailsWith<IllegalArgumentException> { selected.copy(seatNote = "x".repeat(161)) }
        assertFailsWith<IllegalArgumentException> { selected.copy(itemNote = "\u0001") }
        assertFailsWith<IllegalArgumentException> { selected.copy(seatNote = "\u0001") }
    }

    @Test
    fun far03LiteralZeroAndNegativeQuantitiesAreRejected() {
        val selected = f.portions().first()
        for (literal in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> {
                selected.copy(quantity = Quantity(f.unitId, literal, 0))
            }
        }
    }

    @Test
    fun far04ExistingCreateDeliveryIsRejected() {
        val delivery = f.service.copy(channel = LocalSalesChannel.DELIVERY)
        assertFailsWith<IllegalArgumentException> { f.create().copy(service = delivery) }
    }

    private fun assertExistingBytesAndReadKey(request: LocalFoodRequestV1) {
        val canonical = LocalFoodCodecV1.encode(request)
        val preview = LocalFoodSelectionPreviewCodecV1.encode(request)
        val read = LocalFoodSelectionPreviewCodecV1.read(request)
        assertEquals(canonical, preview)
        assertEquals(ReadKey("sales.food.selection-preview", 1), LocalFoodSelectionPreviewCodecV1.QUERY)
        assertEquals(ReadKey("sales.food.selection-preview", 1), read.query)
        assertEquals(canonical, read.payload)
        assertEquals(request, LocalFoodCodecV1.decode(preview))
        for (handler in previewNormalizers()) {
            assertEquals(read.query, handler.key)
            assertEquals(canonical, handler.normalize(read.payload))
        }
    }

    private fun previewNormalizers(): List<MasterReadHandler> {
        val unreadSignatures = CompanyPublicationSignatureVerifier { _, _, _, _ ->
            error("Public normalization must not reach source signature verification")
        }
        return listOf<TransactionFoodRecipeSourceV1>(
            LocalCatalogFoodSource(unreadSignatures),
            LocalCatalogFoodStockSource(unreadSignatures),
        ).map { source -> LocalFoodSelectionPreviewReadHandler.withStock(LocalStockRepository(), source) }
    }

    private fun referenceSha256(bytes: StoredBytes): List<Byte> =
        MessageDigest.getInstance("SHA-256").digest(bytes.copyBytes()).toList()

    private fun bytes(text: String): StoredBytes = StoredBytes(text.encodeToByteArray())
}

/** Exact frozen typed request facts; no catalog installation or genuine source evidence. */
private object FrozenFoodAdvisoryPublicInputs {
    val orderId = DomainId("f0000000-0000-4000-8000-000000000001")
    val firstPortionId = DomainId("f3000000-0000-4000-8000-000000000001")
    val secondPortionId = DomainId("f3000000-0000-4000-8000-000000000002")
    val itemId = DomainId("f1000000-0000-4000-8000-000000000001")
    val recipeId = DomainId("f2000000-0000-4000-8000-000000000001")
    val choiceId = DomainId("f1000000-0000-4000-8000-000000000002")
    val cheeseId = DomainId("f1000000-0000-4000-8000-000000000003")
    val otherOwnerId = DomainId("b0000000-0000-4000-8000-000000000002")

    // The freeze declares literal quantities, not a UOM identity. This scale-0
    // constructor-only synthetic binding establishes no signed SKU/UOM compatibility.
    val unitId = DomainId("f6000000-0000-4000-8000-000000000001")
    val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "T-02", "")

    fun portions(): List<LocalFoodPortionSelectionV1> = listOf(
        LocalFoodPortionSelectionV1(firstPortionId, itemId, recipeId, Revision(1), choiceId,
            listOf(cheeseId), Quantity(unitId, 1, 0), "no salt / بدون ملح", null, "Seat 02 / المقعد 02"),
        LocalFoodPortionSelectionV1(secondPortionId, itemId, recipeId, Revision(1), choiceId,
            listOf(cheeseId), Quantity(unitId, 1, 0), "no salt / بدون ملح", firstPortionId, "Seat 02 / المقعد 02"),
    )

    fun create(): LocalFoodRequestV1.Create = LocalFoodRequestV1.Create(orderId,
        LocalSalesActivity.RESTAURANT, service, Revision(5), portions())

    fun add(): LocalFoodRequestV1.Add = LocalFoodRequestV1.Add(orderId, Revision(7), Revision(5), portions())
}
