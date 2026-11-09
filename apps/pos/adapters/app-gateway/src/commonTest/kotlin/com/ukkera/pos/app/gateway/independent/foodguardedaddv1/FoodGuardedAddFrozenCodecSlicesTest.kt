package com.ukkera.pos.app.gateway.independent.foodguardedaddv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * L1 bindings of food-guarded-add-v1 GFA-001/002/004/007/008 only.
 * These are public wire/quantity checks. No Master, journal, business dispatcher,
 * authenticated activity source, durable original or physical operation is supplied.
 */
public class FoodGuardedAddFrozenCodecSlicesTest {
    @Test
    public fun gfa001ConcreteGuardedPayloadEqualsLiteralAndRoundTripsExactly(): Unit {
        val input = LocalFoodStockRequestV2.ActivityProtectedAdd(FoodGuardedAddFrozenInputs.guarded())
        assertEquals(FoodGuardedAddFrozenInputs.GUARDED, literal(LocalFoodStockCodecV2.encode(input)))
        val decoded = assertIs<LocalFoodStockRequestV2.ActivityProtectedAdd>(
            LocalFoodStockCodecV2.decode(bytes(FoodGuardedAddFrozenInputs.GUARDED)))
        assertEquals(input, decoded)
        assertEquals(FoodGuardedAddFrozenInputs.GUARDED, literal(LocalFoodStockCodecV2.encode(decoded)))
        assertEquals(listOf(FoodGuardedAddFrozenInputs.portionIds[0], FoodGuardedAddFrozenInputs.portionIds[1]),
            decoded.request.portions.map { it.portionId })
        assertEquals(listOf("No salt", ""), decoded.request.portions.map { it.itemNote })
        assertTrue(decoded.request.portions.all { it.modifierIds.isEmpty() &&
            it.quantity == Quantity(FoodGuardedAddFrozenInputs.pieceUnit, 1, 0) })
    }

    @Test
    public fun gfa002ExplicitNullLineAndEmptySeatNoteKeepTheDefaultLiteral(): Unit {
        val request = FoodGuardedAddFrozenInputs.guarded().let { original -> original.copy(
            portions = original.portions.map { it.copy(lineId = null, seatNote = "") }) }
        val encoded = LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.ActivityProtectedAdd(request))
        assertEquals(FoodGuardedAddFrozenInputs.GUARDED, literal(encoded))
        assertEquals(request, assertIs<LocalFoodStockRequestV2.ActivityProtectedAdd>(LocalFoodStockCodecV2.decode(encoded)).request)
    }

    @Test
    public fun gfa002NondefaultOptionalPublicDtoFieldsRetainTheirLiteralOrderAndModifier(): Unit {
        val input = LocalFoodStockRequestV2.ActivityProtectedAdd(FoodGuardedAddFrozenInputs.optionalGuarded())
        assertEquals(FoodGuardedAddFrozenInputs.OPTIONAL_GUARDED,
            dtoJson.encodeToString(LocalFoodStockRequestV2.serializer(), input))
        val decoded = assertIs<LocalFoodStockRequestV2.ActivityProtectedAdd>(
            dtoJson.decodeFromString(LocalFoodStockRequestV2.serializer(), FoodGuardedAddFrozenInputs.OPTIONAL_GUARDED))
        assertEquals(input, decoded)
        assertEquals(FoodGuardedAddFrozenInputs.OPTIONAL_GUARDED,
            dtoJson.encodeToString(LocalFoodStockRequestV2.serializer(), decoded))
        assertEquals(FoodGuardedAddFrozenInputs.line, decoded.request.portions[0].lineId)
        assertEquals("Seat 2", decoded.request.portions[0].seatNote)
        assertEquals(listOf(FoodGuardedAddFrozenInputs.modifier), decoded.request.portions[0].modifierIds)
        // DTO wire representation only. Normalized-command acceptance and business
        // commercial-line compatibility are UNBOUND for this optional fixture.
    }

    @Test
    public fun gfa004BothExplicitLegacyRepresentationsKeepTheirFrozenBytes(): Unit {
        val request = FoodGuardedAddFrozenInputs.guarded()
        val stock = LocalFoodStockRequestV2.Preparation(request)
        assertEquals(FoodGuardedAddFrozenInputs.LEGACY_STOCK, literal(LocalFoodStockCodecV2.encode(stock)))
        assertEquals(stock, LocalFoodStockCodecV2.decode(bytes(FoodGuardedAddFrozenInputs.LEGACY_STOCK)))
        assertEquals(FoodGuardedAddFrozenInputs.LEGACY_STOCK,
            literal(LocalFoodStockCodecV2.normalize(bytes(FoodGuardedAddFrozenInputs.LEGACY_STOCK))))
        assertEquals(FoodGuardedAddFrozenInputs.LEGACY_FOOD, literal(LocalFoodCodecV1.encode(request)))
        assertEquals(request, LocalFoodCodecV1.decode(bytes(FoodGuardedAddFrozenInputs.LEGACY_FOOD)))
        assertEquals(FoodGuardedAddFrozenInputs.LEGACY_FOOD,
            literal(LocalFoodCodecV1.normalize(bytes(FoodGuardedAddFrozenInputs.LEGACY_FOOD))))
    }

    @Test
    public fun gfa007EveryExactW01ThroughW14EditRefusesExecutableNormalization(): Unit {
        val base = FoodGuardedAddFrozenInputs.GUARDED
        val vectors = listOf(
            Triple("W-01", "/request", Json.parseToJsonElement(base)),
            Triple("W-02", "/request/type", JsonPrimitive("ADD")),
            Triple("W-03", "/request", Json.parseToJsonElement("""{"type":"CREATE_DRAFT"}""")),
            Triple("W-04", "/request", Json.parseToJsonElement("""{"type":"SEND"}""")),
            Triple("W-05", "/request", Json.parseToJsonElement("""{"activity":"RESTAURANT","capability":true}""")),
            Triple("W-06", "/request/orderId", null),
            Triple("W-07", "/request/expectedOrderRevision", JsonPrimitive(7)),
            Triple("W-08", "/request/portions/0/quantity/scaled", JsonPrimitive(1)),
            Triple("W-09", "/request/portions/0/quantity/scaled", JsonPrimitive("1e0")),
            Triple("W-10", "/request/portions/0/quantity/scale", JsonPrimitive(7)),
            Triple("W-11", "/request/orderId", JsonPrimitive("not-a-uuid")),
            Triple("W-12", "/type", JsonPrimitive("GFA_UNKNOWN")),
            Triple("W-13", "/request/expectedOrderRevision", JsonPrimitive("9223372036854775808")),
            Triple("W-14", "/request/portions/0/quantity/unitId", null),
        )
        assertEquals(14, vectors.size)
        vectors.forEach { (id, path, value) -> assertRefused(foodGuardedAddFrozenEdit(base, path, value), id) }
    }

    @Test
    public fun gfa007ListedMissingScalarAndNoncanonicalFamiliesHaveNoAcceptingDefaults(): Unit {
        val base = FoodGuardedAddFrozenInputs.GUARDED
        val missing = listOf("/request/orderId", "/request/expectedOrderRevision", "/request/expectedCatalogRevision",
            "/request/portions", "/request/portions/0/quantity/unitId", "/request/portions/0/quantity/scaled",
            "/request/portions/0/quantity/scale")
        missing.forEach { path -> assertRefused(foodGuardedAddFrozenEdit(base, path, null), "missing $path") }
        val revisionPaths = listOf("/request/expectedOrderRevision", "/request/expectedCatalogRevision",
            "/request/portions/0/expectedRecipeRevision")
        val revisionValues = listOf(JsonPrimitive(7), JsonPrimitive("-1"), JsonPrimitive("07"),
            JsonPrimitive("7e0"), JsonPrimitive("9223372036854775808"))
        revisionPaths.forEach { path -> revisionValues.forEach { value ->
            assertRefused(foodGuardedAddFrozenEdit(base, path, value), "$path = $value")
        } }
        listOf(JsonPrimitive(1), JsonPrimitive("1.0"), JsonPrimitive("1e0"), JsonPrimitive("NaN"),
            JsonPrimitive("Infinity"), JsonPrimitive("01")).forEach { value ->
            assertRefused(foodGuardedAddFrozenEdit(base, "/request/portions/0/quantity/scaled", value), "scaled = $value")
        }
        listOf(JsonPrimitive(-1), JsonPrimitive(7), JsonPrimitive(0.5)).forEach { value ->
            assertRefused(foodGuardedAddFrozenEdit(base, "/request/portions/0/quantity/scale", value), "scale = $value")
        }
        assertRefused(foodGuardedAddFrozenEdit(base, "/request/portions/0/portionId",
            JsonPrimitive("8E000000-0000-4000-8000-000000000001")), "noncanonical UUID")
    }

    @Test
    public fun gfa008OneAndEightDistinctPreparationBodiesRetainExactSelectionOrder(): Unit {
        listOf(FoodGuardedAddFrozenInputs.onePortion(), FoodGuardedAddFrozenInputs.eightPortions()).forEach { request ->
            val encoded = LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.ActivityProtectedAdd(request))
            val decoded = assertIs<LocalFoodStockRequestV2.ActivityProtectedAdd>(LocalFoodStockCodecV2.decode(encoded)).request
            assertEquals(request, decoded)
            assertEquals(FoodGuardedAddFrozenInputs.portionIds.take(request.portions.size), decoded.portions.map { it.portionId })
            assertEquals(encoded, LocalFoodStockCodecV2.normalize(encoded))
        }
    }

    @Test
    public fun gfa008EmptyNineDuplicateZeroNegativeAndScaleSevenSelectionsRefuse(): Unit {
        val original = FoodGuardedAddFrozenInputs.guarded()
        listOf(emptyList(), FoodGuardedAddFrozenInputs.portionIds.map { original.portions[1].copy(portionId = it) },
            listOf(original.portions[0], original.portions[0])).forEach { portions ->
            assertFails { LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.ActivityProtectedAdd(original.copy(portions = portions))) }
        }
        listOf("0", "-1").forEach { scaled -> assertRefused(foodGuardedAddFrozenEdit(
            FoodGuardedAddFrozenInputs.GUARDED, "/request/portions/0/quantity/scaled", JsonPrimitive(scaled)), "quantity $scaled") }
        assertRefused(foodGuardedAddFrozenEdit(FoodGuardedAddFrozenInputs.MEASURED,
            "/request/portions/0/quantity", Json.parseToJsonElement(
                """{"unitId":"8f000000-0000-4000-8000-000000000003","scaled":"12500001","scale":7}""")),
            "frozen unsupported measured precision")
    }

    @Test
    public fun gfa008RevisionsAboveTwoToTheFiftyThirdRemainExactDecimalStrings(): Unit {
        val input = LocalFoodStockRequestV2.ActivityProtectedAdd(FoodGuardedAddFrozenInputs.highRevision())
        assertEquals(FoodGuardedAddFrozenInputs.HIGH_REVISION, literal(LocalFoodStockCodecV2.encode(input)))
        val decoded = assertIs<LocalFoodStockRequestV2.ActivityProtectedAdd>(
            LocalFoodStockCodecV2.decode(bytes(FoodGuardedAddFrozenInputs.HIGH_REVISION)))
        assertEquals(Revision(9007199254740993), decoded.request.expectedOrderRevision)
        assertEquals(Revision(9007199254740994), decoded.request.expectedCatalogRevision)
        assertEquals(Revision(9007199254740995), decoded.request.portions.single().expectedRecipeRevision)
        assertEquals(FoodGuardedAddFrozenInputs.HIGH_REVISION, literal(LocalFoodStockCodecV2.encode(decoded)))
        // These values are wire coordinates only, not observed current Master revisions.
    }

    private fun assertRefused(body: String, vector: String) {
        val retained = bytes(body)
        assertFails(vector) { LocalFoodStockCodecV2.normalize(retained) }
        assertEquals(body, literal(retained), vector)
    }

    private fun bytes(body: String): StoredBytes = StoredBytes(body.encodeToByteArray())
    private fun literal(body: StoredBytes): String = body.copyBytes().decodeToString()
    private val dtoJson: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }
}
