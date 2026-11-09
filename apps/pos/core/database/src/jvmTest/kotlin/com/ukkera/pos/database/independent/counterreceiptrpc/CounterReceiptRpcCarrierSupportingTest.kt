package com.ukkera.pos.database.independent.counterreceiptrpc

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterCustomerReceiptRpcKeysV1
import com.ukkera.pos.database.LocalMasterCustomerReceiptRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcReadRequestV1
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptObservationV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptPortionV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptQueryV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadAccessV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptReadCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptRequestV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * SUPPORT only, frozen counter-receipt-rpc v1 CRRPC-V1-002/003/010/014/020/025.
 * These are actual selected typed carriers with unissued credential DATA, not
 * authenticated transport, admitted RPC, original custody or native durability.
 */
class CounterReceiptRpcCarrierSupportingTest {
    private val sale = id("40000000", 1)
    private val actor = id("50000000", 1)
    private val operation = OperationId(id("70000000", 1).value)
    private val scope = BranchScope(id("10000000", 1), id("20000000", 1), id("30000000", 1))
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; coerceInputValues = false }

    @Test
    fun support002And025ReceiptKeysDoNotExposeOtherBusinessBodiesOrVersions() {
        assertTrue(LocalMasterCustomerReceiptRpcKeysV1.accepts(ReadKey("sales.customer-receipt.read", 1)))
        assertTrue(LocalMasterCustomerReceiptRpcKeysV1.accepts(CommandKey("sales.customer-receipt.write", 1)))
        for (key in listOf(ReadKey("sales.customer-receipt.read", 2), ReadKey("local.configuration.read", 1),
            ReadKey("sales.order.read", 1), ReadKey("sales.payment-correction.read", 1), ReadKey("arbitrary.query", 1))) {
            assertFalse(LocalMasterCustomerReceiptRpcKeysV1.accepts(key), key.toString())
        }
        for (key in listOf(CommandKey("sales.customer-receipt.write", 2), CommandKey("sales.collection.write", 1),
            CommandKey("sales.return.write", 1), CommandKey("sales.cash.write", 1), CommandKey("sales.food.write", 1),
            CommandKey("sales.payment-correction.write", 1), CommandKey("local.configuration.write", 1),
            CommandKey("arbitrary.operation", 1))) {
            assertFalse(LocalMasterCustomerReceiptRpcKeysV1.accepts(key), key.toString())
        }
    }

    @Test
    fun support002And014SelectedExecuteCarrierPreservesTheExactE1CommandWithoutBusinessActorFields() {
        val request = e1Request()
        val command = LocalRetailCustomerReceiptCodec.command(operation, request)
        val credentials = credentials()
        val captured = LocalMasterRpcExecuteRequestV1.capture(credentials, command)
        try {
            val wire = json.encodeToString(LocalMasterCustomerReceiptRpcProtocolV1.EXECUTE.request, captured)
            val decoded = json.decodeFromString(LocalMasterCustomerReceiptRpcProtocolV1.EXECUTE.request, wire)
            try {
                assertEquals(command.operationId, decoded.request.operationId)
                assertEquals(command.command, decoded.request.command)
                assertEquals(command.payload, decoded.request.payload)
                assertEquals(request, LocalRetailCustomerReceiptCodec.decode(decoded.request.payload))
                val fields = json.parseToJsonElement(wire).jsonObject.getValue("request").jsonObject
                assertFalse(fields.containsKey("actor"))
                assertFalse(fields.containsKey("scope"))
                assertFalse(fields.containsKey("connectionId"))
            } finally { decoded.destroy() }
        } finally { captured.destroy(); credentials.bearer.destroy() }
    }

    @Test
    fun support003And020SelectedReadAndLookupCarriersKeepPurposeAndOriginalSemanticIdentity() {
        for (purpose in LocalRetailCustomerReceiptReadAccessV1.entries) {
            val query = LocalRetailCustomerReceiptQueryV1.Event(sale, operation, purpose)
            val credentials = credentials()
            val captured = LocalMasterRpcReadRequestV1.capture(credentials, LocalRetailCustomerReceiptReadCodec.request(query))
            try {
                val wire = json.encodeToString(LocalMasterCustomerReceiptRpcProtocolV1.READ.request, captured)
                val decoded = json.decodeFromString(LocalMasterCustomerReceiptRpcProtocolV1.READ.request, wire)
                try {
                    assertEquals(LocalRetailCustomerReceiptReadCodec.QUERY, decoded.request.query)
                    assertEquals(query, json.decodeFromString(LocalRetailCustomerReceiptQueryV1.serializer(),
                        decoded.request.payload.copyBytes().decodeToString()))
                } finally { decoded.destroy() }
            } finally { captured.destroy(); credentials.bearer.destroy() }
        }
        val body = LocalRetailCustomerReceiptCodec.encode(e1Request())
        val original = OperationIdentity(scope.branchId, operation, actor, 1,
            semanticDigest(scope, actor, LocalRetailCustomerReceiptCodec.COMMAND, body).value)
        val credentials = credentials()
        val captured = LocalMasterRpcLookupRequestV1.capture(credentials, original)
        try {
            val wire = json.encodeToString(LocalMasterCustomerReceiptRpcProtocolV1.LOOKUP.request, captured)
            val decoded = json.decodeFromString(LocalMasterCustomerReceiptRpcProtocolV1.LOOKUP.request, wire)
            try { assertEquals(original, decoded.identity) } finally { decoded.destroy() }
        } finally { captured.destroy(); credentials.bearer.destroy() }
    }

    private fun e1Request() = LocalRetailCustomerReceiptRequestV1(sale, Revision(0),
        UtcInstant(Instant.parse("2026-11-01T20:30:00Z")), "Africa/Cairo",
        LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
        listOf(LocalRetailCustomerReceiptPortionV1(id("60000000", 1), Quantity(id("62000000", 1), 2, 0)),
            LocalRetailCustomerReceiptPortionV1(id("60000000", 2), Quantity(id("62000000", 2), 500, 3))))

    private fun credentials() = LocalGatewayCredentials(id("80000000", 2),
        OpaqueCredential(ByteArray(LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES)))

    private fun id(prefix: String, value: Int) = DomainId("$prefix-0000-4000-8000-${value.toString().padStart(12, '0')}")
}
