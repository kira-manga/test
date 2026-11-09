package com.ukkera.pos.sales.store

import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.time.Instant

/**
 * Values from retail-customer-receipt/v1 F-BASE, F-QUANTITY, F-TIME and F-CAS.
 * Unit UUIDs and inert source/authentication coordinates bind public representations only.
 * These controlled data objects are not a genuine paid Finalize or an authorization proof.
 */
internal object RetailCustomerReceiptFrozenV1Fixture {
    val scope = BranchScope(id("01000000", 1), id("02000000", 1), id("03000000", 1))
    val otherBranchScope = scope.copy(branchId = id("03000000", 2))
    val collector = id("04000000", 1)
    val sale = id("06000000", 1)
    val order = id("07000000", 1)
    val finalize = operation("08000000", 1)
    val eachPortion = id("09000000", 1)
    val kgPortion = id("09000000", 2)
    val unknownSale = id("06000000", 99)
    val unknownPortion = id("09000000", 99)
    val unknownEvent = operation("10000000", 99)
    val e1 = operation("10000000", 1)
    val e2 = operation("10000000", 2)
    val e3 = operation("10000000", 3)
    val raceD = operation("10000000", 4)
    val raceE = operation("10000000", 5)
    val uiOperation = operation("18000000", 1)

    // Opaque test-only bindings of the frozen each/kg/g descriptors, not catalog evidence.
    val eachUnit = id("0b000000", 1)
    val kgUnit = id("0b000000", 2)
    val gramUnit = id("0b000000", 3)
    val paidAt = instant(1_767_261_600_000)
    val e1ReceivedAt = instant(1_767_306_600_000)
    val e1RecordedAt = instant(1_767_355_200_000)
    val e2ReceivedAt = instant(1_767_694_500_000)
    val e2RecordedAt = instant(1_767_694_800_000)
    val e3ReceivedAt = instant(1_767_862_800_000)
    val e3RecordedAt = instant(1_767_863_100_000)
    const val zone = "Africa/Cairo"
    val authorization = LocalRetailCustomerReceiptAuthorizationV1(
        collector, id("11000000", 1), SessionGeneration(1), Revision(7),
    )

    val original = LocalRetailCustomerReceiptOriginalSource(
        LocalRetailCustomerReceiptSaleSourceV1(
            schema = LocalRetailCustomerReceiptProfileV1.SOURCE_SCHEMA,
            scope = LocalSalesScope.from(scope),
            saleId = sale,
            orderId = order,
            originalCollectorId = collector,
            saleGeneration = Revision(1),
            saleSchema = LocalSalesStoreProfile.schema(LocalSalesStoreProfile.SALE_KIND),
            saleDigest = digest("synthetic original sale representation"),
            finalizeOperationId = finalize,
            finalizeActorId = collector,
            finalizeCommand = StockStreamingCommandsV2.COLLECTION.name,
            finalizeVersion = StockStreamingCommandsV2.COLLECTION.version,
            finalizeSemanticDigest = digest("synthetic original Finalize representation"),
            finalizeAssignmentId = id("12000000", 1),
            finalizeAuthorityEpoch = Revision(1),
            finalizeSequence = JournalSequence(1),
            paidFinalizeAt = paidAt,
        ),
        listOf(each(3), kg(2_750_000)),
        emptySet(),
    )

    fun request(event: Int): LocalRetailCustomerReceiptRequestV1 = when (event) {
        1 -> request(0, e1ReceivedAt, listOf(each(1), kg(1_125_000)))
        2 -> request(1, e2ReceivedAt, listOf(each(2), kg(625_000)))
        3 -> request(2, e3ReceivedAt, listOf(kg(1_000_000)))
        else -> error("Only frozen E1/E2/E3 are constructed")
    }

    fun request(revision: Long, receivedAt: UtcInstant, portions: List<LocalRetailCustomerReceiptPortionV1>) =
        LocalRetailCustomerReceiptRequestV1(sale, Revision(revision), receivedAt, zone,
            LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER, portions)

    /** Each state is constructed from the frozen arithmetic, never from a production result. */
    fun head(state: Int): LocalRetailCustomerReceiptHeadV1 = when (state) {
        1 -> head(1, listOf(e1), listOf(each(1), kg(1_125_000)), e1)
        2 -> head(2, listOf(e1, e2), listOf(each(3), kg(1_750_000)), e2)
        3 -> head(3, listOf(e1, e2, e3), listOf(each(3), kg(2_750_000)), e3)
        else -> error("S0 is absence, not a fabricated zero head")
    }

    fun head(revision: Long, events: List<OperationId>, portions: List<LocalRetailCustomerReceiptPortionV1>,
        latest: OperationId) = LocalRetailCustomerReceiptHeadV1(
        LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA, original.coordinates, Revision(revision), events, portions, latest,
    )

    fun headImage(state: Int): LocalRetailCustomerReceiptHeadImage = image(head(state))

    fun image(value: LocalRetailCustomerReceiptHeadV1): LocalRetailCustomerReceiptHeadImage {
        val bytes = headBytes(value)
        return LocalRetailCustomerReceiptHeadImage(value.headRevision, value.schema, bytes,
            sha256(bytes.copyBytes()), value)
    }

    fun event(state: Int): LocalRetailCustomerReceiptEventV1 {
        val request = request(state)
        val operation = listOf(e1, e2, e3)[state - 1]
        val recordedAt = listOf(e1RecordedAt, e2RecordedAt, e3RecordedAt)[state - 1]
        val predecessor = if (state == 1) null else headImage(state - 1)
        val previousEventDigest = if (state == 1) null else sha256(eventBytes(event(state - 1)).copyBytes())
        return LocalRetailCustomerReceiptEventV1(
            LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, operation, collector,
            identity(operation, request).semanticDigest, authorization, request.observation, request.portions,
            request.receivedAt, request.receiptTimeZoneId, recordedAt, predecessor?.generation, predecessor?.digest,
            predecessor?.value?.lastOperationId, previousEventDigest, emptyList(), head(state),
            LocalRetailCustomerReceiptResultV1.Applied(sale, operation, Revision(state.toLong()),
                request.receivedAt, zone, recordedAt),
        )
    }

    fun identity(operation: OperationId, request: LocalRetailCustomerReceiptRequestV1) =
        LocalRetailCustomerReceiptProductionIdentity(scope, collector, operation,
            semanticDigest(scope, collector, LocalRetailCustomerReceiptCodec.COMMAND,
                LocalRetailCustomerReceiptCodec.encode(request)))

    fun propose(operation: OperationId, request: LocalRetailCustomerReceiptRequestV1,
        state: Int = 0, recordedAt: UtcInstant = e1RecordedAt,
        before: LocalRetailCustomerReceiptHeadImage? = if (state == 0) null else headImage(state)) = retailCustomerReceiptProduction(
        identity(operation, request), request, original, before,
        if (state == 0) null else sha256(eventBytes(event(state)).copyBytes()),
    ).propose(recordedAt, authorization)

    fun each(scaled: Long, scale: Int = 0) = LocalRetailCustomerReceiptPortionV1(eachPortion,
        Quantity(eachUnit, scaled, scale))
    fun kg(scaled: Long, unit: DomainId = kgUnit, scale: Int = 6) = LocalRetailCustomerReceiptPortionV1(kgPortion,
        Quantity(unit, scaled, scale))
    fun headBytes(value: LocalRetailCustomerReceiptHeadV1): StoredBytes =
        localSalesEncode(value, LocalRetailCustomerReceiptHeadV1.serializer())
    fun eventBytes(value: LocalRetailCustomerReceiptEventV1): StoredBytes =
        localSalesEncode(value, LocalRetailCustomerReceiptEventV1.serializer())
    fun digest(value: String): PayloadDigest = sha256(value.encodeToByteArray())
    fun instant(epochMillis: Long): UtcInstant = UtcInstant(Instant.fromEpochMilliseconds(epochMillis))
    fun id(prefix: String, decimalSuffix: Int): DomainId =
        DomainId("$prefix-0000-4000-8000-${decimalSuffix.toString().padStart(12, '0')}")
    fun operation(prefix: String, decimalSuffix: Int): OperationId = OperationId(id(prefix, decimalSuffix).value)
}
