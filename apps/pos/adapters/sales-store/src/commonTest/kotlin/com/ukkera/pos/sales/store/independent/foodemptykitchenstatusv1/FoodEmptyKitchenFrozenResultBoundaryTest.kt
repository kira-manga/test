package com.ukkera.pos.sales.store.independent.foodemptykitchenstatusv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenObservationV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenStatusV1
import com.ukkera.pos.sales.store.LocalFoodOrderDetailV1
import com.ukkera.pos.sales.store.LocalFoodOrderSummaryV1
import com.ukkera.pos.sales.store.LocalFoodReadResultV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.sales.store.localSalesJson
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unchanged food-empty-kitchen-status/v1, L1_DIAGNOSTIC_RESULT_SCALAR_V1.
 * Only the actual public result constructors and V1/V2 strict result decoders are exercised.
 * The lawful public model values below are synthetic scalar premises, not a fake Master reply,
 * authentic stored order, paid history or endpoint/business fixture. V2 payable is left unknown;
 * this freeze supplies no current-payable oracle. The visible-NOT_SENT consumer check is not
 * bound here: it lives outside this module, and must not be replaced with a weaker assertion.
 */
class FoodEmptyKitchenFrozenResultBoundaryTest {
    @Test
    fun feksL1012ValidEmptyPublicResultRetainsExactFrozenScalarsInBothVersions() {
        val food = emptyFood()
        val kitchen = validEmptyObservation()
        for (version in ResultVersion.entries) {
            val bytes = version.encode(food, kitchen)
            val actual = version.decode(bytes)
            assertEmptyIdentity(actual.first, "FEKS-L1-012/VALID_EMPTY/${version.name}")
            assertEmptyObservation(assertNotNull(actual.second), "FEKS-L1-012/VALID_EMPTY/${version.name}")
            assertEquals(bytes, version.encode(actual.first, actual.second), "${version.name}: canonical bytes retained")
        }
    }

    @Test
    fun feksL1012WrongKitchenOrderIdCannotBindOrDecodeAsTheSameOrder() {
        val food = emptyFood()
        val invalid = validEmptyObservation().copy(orderId = frozenId(1002))
        assertInvalidBindingAndDecode("WRONG_ORDER_ID", food, invalid,
            mapOf("orderId" to localSalesJson.encodeToJsonElement(com.ukkera.pos.types.DomainId.serializer(), frozenId(1002))))
    }

    @Test
    fun feksL1012WrongKitchenRevisionCannotBindOrDecodeAsTheSameRevision() {
        val food = emptyFood()
        val invalid = validEmptyObservation().copy(orderStorageRevision = Revision(8))
        assertInvalidBindingAndDecode("WRONG_REVISION", food, invalid,
            mapOf("orderStorageRevision" to localSalesJson.encodeToJsonElement(Revision.serializer(), Revision(8))))
    }

    @Test
    fun feksL1012CountMinusOneCannotConstructOrDecode() {
        assertInvalidCount("COUNT_MINUS_ONE", -1)
    }

    @Test
    fun feksL1012CountThirtyThreeCannotConstructOrDecode() {
        assertInvalidCount("COUNT_THIRTY_THREE", 33)
    }

    @Test
    fun feksL1012NoUnsentWorkWithPositiveCountCannotConstructOrDecode() {
        assertInvalidCount("NO_UNSENT_WITH_POSITIVE_COUNT", 1)
    }

    @Test
    fun feksL1012UnsupportedAvailableStatusCannotDecodeInEitherVersion() {
        val food = emptyFood()
        for (version in ResultVersion.entries) {
            val baseline = version.encode(food, validEmptyObservation())
            val changed = mutateKitchen(baseline, mapOf("status" to JsonPrimitive("AVAILABLE")))
            assertFails("FEKS-L1-012/UNSUPPORTED_POSITIVE_STATUS/${version.name}: actual result decoder") {
                version.decode(changed)
            }
        }
    }

    @Test
    fun feksL1012PositiveCountContradictsSuppliedCompleteEmptyPublicSummary() {
        val invalid = LocalFoodCheckKitchenObservationV1(frozenId(1001), Revision(7), 1,
            LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
        assertInvalidBindingAndDecode("NONZERO_FOR_SUPPLIED_COMPLETE_EMPTY_FACT", emptyFood(), invalid,
            mapOf("unsentPortionCount" to JsonPrimitive(1), "status" to JsonPrimitive("UNAVAILABLE")))
    }

    @Test
    fun feksL1012EmptyVisiblePageDoesNotRejectSuppliedTwoUnsentWholeOrderControl() {
        val food = hiddenWorkFood()
        val kitchen = LocalFoodCheckKitchenObservationV1(frozenId(2001), Revision(13), 2,
            LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
        for (version in ResultVersion.entries) {
            val bytes = version.encode(food, kitchen)
            val actual = version.decode(bytes)
            val detail = assertNotNull(actual.first.detail)
            val observation = assertNotNull(actual.second)
            val tag = "FEKS-L1-012/VALID_HIDDEN_WORK_CONTROL/${version.name}"
            assertEquals(frozenId(2001), detail.summary.orderId, "$tag/food.orderId")
            assertEquals(Revision(13), detail.summary.revision, "$tag/food.revision")
            assertEquals(2, detail.summary.portionCount, "$tag/supplied complete member count")
            assertTrue(detail.portions.isEmpty(), "$tag/supplied empty visible page")
            assertEquals(frozenId(2001), observation.orderId, "$tag/kitchen.orderId")
            assertEquals(Revision(13), observation.orderStorageRevision, "$tag/kitchen.orderStorageRevision")
            assertEquals(2, observation.unsentPortionCount, "$tag/kitchen.unsentPortionCount")
            assertEquals(LocalFoodCheckKitchenStatusV1.UNAVAILABLE, observation.status, "$tag/kitchen.status")
            assertEquals(bytes, version.encode(actual.first, actual.second), "$tag/canonical bytes retained")
        }
    }

    @Test
    fun feksL1014AbsentOptionalKitchenStaysAbsentAndRetainsExactFoodIdentity() {
        val food = emptyFood()
        for (version in ResultVersion.entries) {
            val bytes = version.encode(food, null)
            val tag = "FEKS-L1-014/OPTIONAL_KITCHEN_ABSENT/${version.name}"
            assertFalse("kitchen" in jsonObject(bytes), "$tag/no synthesized encoded observation")
            val actual = version.decode(bytes)
            assertNull(actual.second, "$tag/absence is not synthesized NO_UNSENT_WORK or positive capability")
            assertEmptyIdentity(actual.first, tag)
            assertEquals(bytes, version.encode(actual.first, actual.second), "$tag/canonical omission retained")
            // No invented null-to-UNKNOWN classifier or UI capability/permission oracle is used.
        }
    }

    private fun assertInvalidCount(variant: String, count: Int) {
        assertFails("FEKS-L1-012/$variant: actual public observation constructor") {
            LocalFoodCheckKitchenObservationV1(frozenId(1001), Revision(7), count,
                LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
        }
        val food = emptyFood()
        for (version in ResultVersion.entries) {
            val baseline = version.encode(food, validEmptyObservation())
            val changed = mutateKitchen(baseline, mapOf("unsentPortionCount" to JsonPrimitive(count)))
            assertFails("FEKS-L1-012/$variant/${version.name}: actual result decoder") {
                version.decode(changed)
            }
        }
    }

    private fun assertInvalidBindingAndDecode(variant: String, food: LocalFoodReadResultV1,
        invalid: LocalFoodCheckKitchenObservationV1, changes: Map<String, JsonElement>) {
        for (version in ResultVersion.entries) {
            val baseline = version.encode(food, validEmptyObservation())
            assertFails("FEKS-L1-012/$variant/${version.name}: actual result constructor") {
                version.bind(food, invalid)
            }
            val changed = mutateKitchen(baseline, changes)
            assertFails("FEKS-L1-012/$variant/${version.name}: actual result decoder") {
                version.decode(changed)
            }
        }
    }

    /** Mutate real encoder output; retain field order and use existing serializers for typed revisions/IDs. */
    private fun mutateKitchen(baseline: StoredBytes, changes: Map<String, JsonElement>): StoredBytes {
        val root = jsonObject(baseline)
        val fields = assertNotNull(root["kitchen"]).jsonObject
        return StoredBytes(JsonObject(root + ("kitchen" to JsonObject(fields + changes))).toString().encodeToByteArray())
    }

    private fun jsonObject(bytes: StoredBytes): JsonObject =
        localSalesJson.parseToJsonElement(bytes.copyBytes().decodeToString(throwOnInvalidSequence = true)).jsonObject

    private fun assertEmptyIdentity(food: LocalFoodReadResultV1, tag: String) {
        val detail = assertNotNull(food.detail)
        assertEquals(frozenId(1001), detail.summary.orderId, "$tag/food.orderId")
        assertEquals(Revision(7), detail.summary.revision, "$tag/food.revision")
        assertEquals(0, detail.summary.portionCount, "$tag/supplied complete member count")
        assertTrue(detail.portions.isEmpty(), "$tag/supplied empty visible page")
    }

    private fun assertEmptyObservation(actual: LocalFoodCheckKitchenObservationV1, tag: String) {
        assertEquals(frozenId(1001), actual.orderId, "$tag/kitchen.orderId")
        assertEquals(Revision(7), actual.orderStorageRevision, "$tag/kitchen.orderStorageRevision")
        assertEquals(0, actual.unsentPortionCount, "$tag/kitchen.unsentPortionCount")
        assertEquals(LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK, actual.status, "$tag/kitchen.status")
    }

    private fun validEmptyObservation(): LocalFoodCheckKitchenObservationV1 =
        LocalFoodCheckKitchenObservationV1(frozenId(1001), Revision(7), 0, LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)

    private fun emptyFood(): LocalFoodReadResultV1 = LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
        LocalFoodOrderSummaryV1(frozenId(1001), frozenId(11), Revision(7), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "EMPTY-1", ""), 0, null, null),
        emptyList(), Money(Currency.EGP, 0), false))

    private fun hiddenWorkFood(): LocalFoodReadResultV1 = LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
        LocalFoodOrderSummaryV1(frozenId(2001), frozenId(11), Revision(13), LocalSalesActivity.RESTAURANT,
            // Nonempty reference is an unspecified, neutral synthetic framing value, not an endpoint fixture.
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "NONEMPTY2", ""), 2, null, null),
        emptyList(), Money(Currency.EGP, 33_000), false))

    private enum class ResultVersion {
        V1, V2;

        fun bind(food: LocalFoodReadResultV1, kitchen: LocalFoodCheckKitchenObservationV1?) {
            when (this) {
                V1 -> LocalFoodCheckKitchenResultV1(food, kitchen)
                V2 -> LocalFoodCheckKitchenResultV2(food, kitchen, currentUnpaidPayable = null)
            }
        }

        fun encode(food: LocalFoodReadResultV1, kitchen: LocalFoodCheckKitchenObservationV1?): StoredBytes = when (this) {
            V1 -> localSalesEncode(LocalFoodCheckKitchenResultV1(food, kitchen), LocalFoodCheckKitchenResultV1.serializer())
            V2 -> localSalesEncode(LocalFoodCheckKitchenResultV2(food, kitchen, currentUnpaidPayable = null),
                LocalFoodCheckKitchenResultV2.serializer())
        }

        fun decode(bytes: StoredBytes): Pair<LocalFoodReadResultV1, LocalFoodCheckKitchenObservationV1?> = when (this) {
            V1 -> LocalFoodCheckKitchenCodecV1.result(bytes).let { it.food to it.kitchen }
            V2 -> LocalFoodCheckKitchenCodecV2.result(bytes).let { it.food to it.kitchen }
        }
    }
}
