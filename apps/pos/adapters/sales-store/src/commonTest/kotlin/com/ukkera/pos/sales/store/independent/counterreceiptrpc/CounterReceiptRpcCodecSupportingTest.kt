package com.ukkera.pos.sales.store.independent.counterreceiptrpc

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptObservationV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptPortionV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptQueryV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadAccessV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadHandler
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptRequestV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

/**
 * SUPPORT only for counter-receipt-rpc frozen v1, CRRPC-V1-002/003/004/017/018/025.
 * Typed canonical DATA is not an original sale, admission, grant, RPC or durable result.
 * Exact byte-edge qualification is deliberately unbound.
 */
class CounterReceiptRpcCodecSupportingTest {
    private val f = CounterReceiptCodecData

    @Test
    fun support003TypedDefaultAndExplicitPurposesStayDistinctAndInvalidSelectorsRefuse() {
        val read = LocalRetailCustomerReceiptReadHandler()
        val default = LocalRetailCustomerReceiptReadCodec.encode(LocalRetailCustomerReceiptQueryV1.Head(f.sale))
        val record = LocalRetailCustomerReceiptReadCodec.encode(LocalRetailCustomerReceiptQueryV1.Head(f.sale,
            LocalRetailCustomerReceiptReadAccessV1.RECORD))
        assertEquals(record, default)
        for (purpose in LocalRetailCustomerReceiptReadAccessV1.entries) {
            val query = LocalRetailCustomerReceiptQueryV1.Event(f.sale, f.e1, purpose)
            val body = LocalRetailCustomerReceiptReadCodec.encode(query)
            assertEquals(body, read.normalize(body))
            assertEquals(query, f.wire.decodeFromString(LocalRetailCustomerReceiptQueryV1.serializer(), body.text()))
        }
        for (invalid in listOf("RETURNS", "record", "RETURN", "ANY_OWNER", "", "UNKNOWN")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                read.normalize(f.replace(record, "access", JsonPrimitive(invalid)))
            }
        }
    }

    @Test
    fun support002And017E1CanonicalCommandRetainsExactIdsScalarsAndOneVersion() {
        val request = f.e1Request()
        val command = LocalRetailCustomerReceiptCodec.command(f.e1, request)
        assertEquals(CommandKey("sales.customer-receipt.write", 1), command.command)
        assertEquals(ReadKey("sales.customer-receipt.read", 1), LocalRetailCustomerReceiptReadCodec.QUERY)
        assertEquals(f.e1, command.operationId)
        assertEquals(request, LocalRetailCustomerReceiptCodec.decode(command.payload))
        assertEquals(command.payload, LocalRetailCustomerReceiptCodec.normalize(command.payload))
        assertEquals(listOf(f.itemPortion, f.kgPortion), request.portions.map { it.portionId })
        assertEquals(listOf(2L, 500L), request.portions.map { it.quantity.scaled })
        assertEquals(listOf(0, 3), request.portions.map { it.quantity.scale })
        assertEquals("2026-11-01T20:30:00Z", request.receivedAt.canonicalString())
        assertEquals("Africa/Cairo", request.receiptTimeZoneId)
        assertFalse(f.wire.parseToJsonElement(command.payload.text()).jsonObject.containsKey("actor"))
    }

    @Test
    fun support004And017CallerIdentityUnknownFieldsUnsortedAndDuplicateWirePortionsRefuse() {
        val body = LocalRetailCustomerReceiptCodec.encode(f.e1Request())
        for (field in listOf("actor", "scope", "producer", "policy", "auth")) {
            assertFailsWith<IllegalArgumentException>(field) {
                LocalRetailCustomerReceiptCodec.decode(f.replace(body, field, JsonPrimitive(f.owner.value)))
            }
        }
        val portions = f.wire.parseToJsonElement(body.text()).jsonObject.getValue("portions") as JsonArray
        for (rows in listOf(JsonArray(portions.reversed()), JsonArray(listOf(portions[0], portions[0])))) {
            assertFailsWith<IllegalArgumentException> {
                LocalRetailCustomerReceiptCodec.decode(f.replace(body, "portions", rows))
            }
        }
        for (bad in listOf(StoredBytes("{".encodeToByteArray()), StoredBytes("[]".encodeToByteArray()))) {
            assertFailsWith<IllegalArgumentException> { LocalRetailCustomerReceiptCodec.decode(bad) }
        }
    }

    @Test
    fun support017And025DirectRequestPortionCountIsOneThroughThirtyTwoWithoutTruncation() {
        fun rows(count: Int) = (1..count).map { index ->
            LocalRetailCustomerReceiptPortionV1(DomainId("61000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}"),
                Quantity(f.itemUnit, 1, 0))
        }
        for (count in listOf(1, 32)) {
            val request = f.e1Request().copy(portions = rows(count))
            assertEquals(count, LocalRetailCustomerReceiptCodec.decode(LocalRetailCustomerReceiptCodec.encode(request)).portions.size)
        }
        for (count in listOf(0, 33)) {
            assertFailsWith<IllegalArgumentException> { f.e1Request().copy(portions = rows(count)) }
        }
        for (quantity in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> {
                LocalRetailCustomerReceiptPortionV1(f.itemPortion, Quantity(f.itemUnit, quantity, 0))
            }
        }
    }

    @Test
    fun support018MissingObservationTimeAndZoneNeverAcquireClientDefaults() {
        val body = LocalRetailCustomerReceiptCodec.encode(f.e1Request())
        for (field in listOf("receivedAt", "receiptTimeZoneId")) {
            val fields = f.wire.parseToJsonElement(body.text()).jsonObject.toMutableMap().also { it.remove(field) }
            assertFailsWith<IllegalArgumentException>(field) {
                LocalRetailCustomerReceiptCodec.decode(StoredBytes(JsonObject(fields).toString().encodeToByteArray()))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            LocalRetailCustomerReceiptCodec.decode(f.replace(body, "receivedAt", JsonPrimitive("invalid UTC")))
        }
    }
}

/** Frozen semantic values; unit UUIDs bind logical ITEM/KG only and confer no source provenance. */
internal object CounterReceiptCodecData {
    val sale = DomainId("40000000-0000-4000-8000-000000000001")
    val owner = DomainId("50000000-0000-4000-8000-000000000001")
    val itemPortion = DomainId("60000000-0000-4000-8000-000000000001")
    val kgPortion = DomainId("60000000-0000-4000-8000-000000000002")
    val itemUnit = DomainId("62000000-0000-4000-8000-000000000001")
    val kgUnit = DomainId("62000000-0000-4000-8000-000000000002")
    val e1 = OperationId("70000000-0000-4000-8000-000000000001")
    val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; coerceInputValues = false }

    fun e1Request() = LocalRetailCustomerReceiptRequestV1(sale, Revision(0),
        UtcInstant(Instant.parse("2026-11-01T20:30:00Z")), "Africa/Cairo",
        LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
        listOf(LocalRetailCustomerReceiptPortionV1(itemPortion, Quantity(itemUnit, 2, 0)),
            LocalRetailCustomerReceiptPortionV1(kgPortion, Quantity(kgUnit, 500, 3))))

    fun replace(body: StoredBytes, field: String, value: kotlinx.serialization.json.JsonElement): StoredBytes =
        StoredBytes(JsonObject(wire.parseToJsonElement(body.text()).jsonObject.toMutableMap().also {
            it[field] = value
        }).toString().encodeToByteArray())
}

internal fun StoredBytes.text(): String = copyBytes().decodeToString()
