package com.ukkera.pos.sales.store.independent.foodemptykitchenstatusv1

import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenObservationV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenStatusV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.foodKitchenWorkStatus
import com.ukkera.pos.sales.store.localSalesDecode
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Frozen oracle: qa/pos/freezes/food-empty-kitchen-status-v1, food-empty-kitchen-status/v1.
 * L1_DIAGNOSTIC_RESULT_SCALAR_V1 only. The production classifier returns STATUS ONLY.
 * Identity, revision and count are supplied frozen scalars, separately retained by the real
 * public observation constructor/codec; they are not acquired or counted by this harness.
 * Support and history labels are synthetic premises, not installed support or authentic history.
 * No order rows, original quotations, native producer, authority or endpoint result is supplied.
 */
class FoodEmptyKitchenFrozenClassificationTest {
    @Test
    fun feksL1001RestaurantDineInSuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[0])
    }

    @Test
    fun feksL1001RestaurantCounterSuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[1])
    }

    @Test
    fun feksL1001RestaurantTakeawaySuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[2])
    }

    @Test
    fun feksL1001RestaurantDeliverySuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[3])
    }

    @Test
    fun feksL1001CafeDineInSuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[4])
    }

    @Test
    fun feksL1001CafeCounterSuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[5])
    }

    @Test
    fun feksL1001CafeTakeawaySuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[6])
    }

    @Test
    fun feksL1001CafeDeliverySuppliedEmptyWorkForEverySupportPremise() = runTest {
        assertEmpty(emptyFixtures[7])
    }

    @Test
    fun feksL1003QuotedReadySuppliedTwoUnsentRecordsYieldUnknown() = runTest {
        assertFrozenClassification("FEKS-L1-003", "QUOTED2-READY-EMPTY_PAGE", frozenId(2001),
            13, 2, LocalSalesChannel.DINE_IN, SupportPremise.READY, LocalFoodCheckKitchenStatusV1.UNKNOWN)
    }

    @Test
    fun feksL1003QuotedAbsentExecutionSuppliedTwoUnsentRecordsYieldUnavailable() = runTest {
        assertFrozenClassification("FEKS-L1-003", "QUOTED2-NO_EXECUTION-EMPTY_PAGE", frozenId(2001),
            13, 2, LocalSalesChannel.DINE_IN, SupportPremise.NO_EXECUTION, LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
    }

    @Test
    fun feksL1004SuppliedIssuedZeroUnsentWorkForReadyAndAbsentExecution() = runTest {
        for (premise in listOf(SupportPremise.READY, SupportPremise.NO_EXECUTION)) {
            assertFrozenClassification("FEKS-L1-004", "ALL_ISSUED2/${premise.name}", frozenId(2001),
                19, 0, LocalSalesChannel.DINE_IN, premise, LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
        }
    }

    @Test
    fun feksL1004SuppliedCancelledZeroUnsentWorkForReadyAndAbsentExecution() = runTest {
        for (premise in listOf(SupportPremise.READY, SupportPremise.NO_EXECUTION)) {
            assertFrozenClassification("FEKS-L1-004", "ALL_CANCELLED2/${premise.name}", frozenId(2001),
                17, 0, LocalSalesChannel.DINE_IN, premise, LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
        }
    }

    @Test
    fun feksL1005SuppliedPaidReservedTwoUnsentRecordsWithoutExecution() = runTest {
        assertFrozenClassification("FEKS-L1-005", "PAID_RESERVED2-NO_EXECUTION", frozenId(2001),
            21, 2, LocalSalesChannel.DINE_IN, SupportPremise.NO_EXECUTION, LocalFoodCheckKitchenStatusV1.UNAVAILABLE)
    }

    private suspend fun assertEmpty(fixture: EmptyFixture) {
        for (premise in SupportPremise.entries) {
            assertFrozenClassification("FEKS-L1-001", "${fixture.id}/${premise.name}", fixture.orderId,
                7, 0, fixture.service.channel, premise, LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
        }
    }

    private suspend fun assertFrozenClassification(slice: String, variant: String, orderId: DomainId,
        semanticRevision: Long, suppliedWholeOrderCount: Int, channel: LocalSalesChannel,
        premise: SupportPremise, expectedStatus: LocalFoodCheckKitchenStatusV1) {
        val tag = "$slice/$variant"
        // Complete/bound work is a supplied scalar premise, not an accepting producer fixture.
        val observedStatus = foodKitchenWorkStatus(suppliedWholeOrderCount, 0, channel) {
            premise.executionRestriction
        }
        assertEquals(expectedStatus, observedStatus, "$tag: actual production status classifier")

        val observation = LocalFoodCheckKitchenObservationV1(orderId, Revision(semanticRevision),
            suppliedWholeOrderCount, observedStatus)
        assertRetainedFields(observation, orderId, semanticRevision, suppliedWholeOrderCount, expectedStatus,
            "$tag: public observation constructor (identity/revision/count supplied)")

        val encoded = localSalesEncode(observation, LocalFoodCheckKitchenObservationV1.serializer())
        val decoded = localSalesDecode(encoded, LocalFoodCheckKitchenObservationV1.serializer())
        assertRetainedFields(decoded, orderId, semanticRevision, suppliedWholeOrderCount, expectedStatus,
            "$tag: actual observation serializer/decoder (not an endpoint)")
        assertEquals(encoded, localSalesEncode(decoded, LocalFoodCheckKitchenObservationV1.serializer()),
            "$tag: preserve the existing canonical scalar representation")
    }

    private fun assertRetainedFields(actual: LocalFoodCheckKitchenObservationV1, expectedOrderId: DomainId,
        expectedRevision: Long, expectedCount: Int, expectedStatus: LocalFoodCheckKitchenStatusV1, stage: String) {
        assertEquals(expectedOrderId, actual.orderId, "$stage/orderId")
        assertEquals(Revision(expectedRevision), actual.orderStorageRevision, "$stage/orderStorageRevision")
        assertEquals(expectedCount, actual.unsentPortionCount, "$stage/unsentPortionCount")
        assertEquals(expectedStatus, actual.status, "$stage/status")
    }

    /** These closures supply only null/SOURCE_UNAVAILABLE, never real installation evidence.
     * Binding/routing/configuration facts are not additional inputs to the scalar classifier.
     * No callback-count, invocation-order or newly added argument-guard oracle is asserted. */
    private enum class SupportPremise(val executionRestriction: PreparedMutationAdmissionRestriction?) {
        READY(null),
        NO_EXECUTION(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE),
        BINDING_UNAVAILABLE(null),
        NO_ROUTING(null),
        CONFIGURATION_ONLY(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE),
        PARTIAL_OR_CHANNEL_MISMATCH(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE),
    }

    private data class EmptyFixture(val id: String, val orderId: DomainId, val activity: LocalSalesActivity,
        val service: LocalFoodServiceV1)

    private val emptyFixtures = listOf(
        EmptyFixture("EMPTY-R-DINE_IN", frozenId(1001), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "EMPTY-1", "")),
        EmptyFixture("EMPTY-R-COUNTER", frozenId(1002), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.COUNTER, "EMPTY-2", "")),
        EmptyFixture("EMPTY-R-TAKEAWAY", frozenId(1003), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "EMPTY-3", "")),
        EmptyFixture("EMPTY-R-DELIVERY", frozenId(1004), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DELIVERY, "EMPTY-4", "")),
        EmptyFixture("EMPTY-C-DINE_IN", frozenId(1005), LocalSalesActivity.CAFE,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "EMPTY-5", "")),
        EmptyFixture("EMPTY-C-COUNTER", frozenId(1006), LocalSalesActivity.CAFE,
            LocalFoodServiceV1(LocalSalesChannel.COUNTER, "EMPTY-6", "")),
        EmptyFixture("EMPTY-C-TAKEAWAY", frozenId(1007), LocalSalesActivity.CAFE,
            LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "EMPTY-7", "")),
        EmptyFixture("EMPTY-C-DELIVERY", frozenId(1008), LocalSalesActivity.CAFE,
            LocalFoodServiceV1(LocalSalesChannel.DELIVERY, "EMPTY-8", "")),
    )
}

internal fun frozenId(suffix: Int): DomainId =
    DomainId("f3100000-0000-4000-8000-${suffix.toString().padStart(12, '0')}")
