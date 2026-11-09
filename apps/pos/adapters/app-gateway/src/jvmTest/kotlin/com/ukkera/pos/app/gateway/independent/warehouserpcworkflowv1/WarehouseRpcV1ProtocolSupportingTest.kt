package com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1

import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.Revision
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * WH-RPC-001/003/006/019/020 descriptor, carrier and normalization SUPPORT only.
 * No SDK call/registration, admitted transport, Host/Client qualification, native disposition,
 * callback census or Master authority is constructed. Synthetic 52-byte carriers are not
 * credentials. Descriptor equality is not execution at B_max or B_max+1: a genuinely
 * representable maximum outer-wire boundary remains unwired, as do whole-case outcomes.
 */
class WarehouseRpcV1ProtocolSupportingTest {
    private val frozen get() = FrozenWarehouseRpcV1

    @Test
    fun wh001WarehouseDescriptorsPreserveSupplierV1CodecsFiniteLimitsAndNeverReinvoke() {
        val base = LocalMasterClientRpcProtocolV1
        val warehouse = LocalMasterWarehouseRpcProtocolV1
        val warehouseProcedures = listOf(warehouse.READ, warehouse.EXECUTE, warehouse.LOOKUP)
        val supplierProcedures = listOf(base.READ, base.EXECUTE, base.LOOKUP)
        assertEquals(frozen.profileInputs.getValue("warehouse_routes").jsonArray.map { it.jsonPrimitive.content },
            warehouseProcedures.map { "${it.name}/${it.version}" })
        assertEquals(frozen.profileInputs.getValue("supplier_routes").jsonArray.map { it.jsonPrimitive.content },
            supplierProcedures.map { "${it.name}/${it.version}" })
        for ((actual, supplier) in warehouseProcedures.zip(supplierProcedures)) {
            assertEquals(1, actual.version)
            assertEquals(1, supplier.version)
            assertSame(supplier.request, actual.request)
            assertSame(supplier.response, actual.response)
            assertSame(supplier.applicationError, actual.applicationError)
            assertEquals(RpcRetrySafety.NeverReinvoke, actual.retrySafety)
            assertEquals(supplier.retrySafety, actual.retrySafety)
            assertEquals(base.MAXIMUM_WIRE_BYTES, actual.requestLimitBytes)
            assertEquals(base.MAXIMUM_WIRE_BYTES, actual.responseLimitBytes)
            assertEquals(base.ERROR_LIMIT_BYTES, actual.errorLimitBytes)
            assertEquals(supplier.requestLimitBytes, actual.requestLimitBytes)
            assertEquals(supplier.responseLimitBytes, actual.responseLimitBytes)
            assertEquals(supplier.errorLimitBytes, actual.errorLimitBytes)
        }
    }

    @Test
    fun wh001wh019ExplicitProfilesStayImmutableAndOmittedDirectoryProfileRemainsSupplierCompatible() = runTest(timeout = 5.seconds) {
        val read = ReadRequest(LocalConfigurationProfile.QUERY,
            LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE)))
        val execute = frozen.request(frozen.create())
        val identity = frozen.saved(frozen.create()).identity()
        val defaults = listOf(LocalMasterRpcBusinessAccess.Read(read), LocalMasterRpcBusinessAccess.Execute(execute),
            LocalMasterRpcBusinessAccess.Lookup(identity))
        val explicit = listOf(LocalMasterRpcBusinessAccess.Read(read, LocalMasterRpcWorkflowProfile.WAREHOUSES),
            LocalMasterRpcBusinessAccess.Execute(execute, LocalMasterRpcWorkflowProfile.WAREHOUSES),
            LocalMasterRpcBusinessAccess.Lookup(identity, LocalMasterRpcWorkflowProfile.WAREHOUSES))
        assertTrue(defaults.all { it.profile == LocalMasterRpcWorkflowProfile.SUPPLIERS })
        assertTrue(explicit.all { it.profile == LocalMasterRpcWorkflowProfile.WAREHOUSES })
        withWarehouseRpcFixture { f ->
            var originalCurrent = true
            val labels = LocalBranchDisplayLabels(frozen.scope, "Synthetic company", "Synthetic branch")
            val supplier = LocalSupplierClientConnection(f.master, frozen.connection, frozen.deviceOwner, labels) { originalCurrent }
            val warehouse = LocalDirectoryClientConnection(f.master, frozen.connection, frozen.deviceOwner, labels,
                LocalMasterRpcWorkflowProfile.WAREHOUSES) { originalCurrent }
            assertEquals(LocalMasterRpcWorkflowProfile.SUPPLIERS, supplier.profile)
            assertEquals(LocalMasterRpcWorkflowProfile.WAREHOUSES, warehouse.profile)
            assertSame(f.master, warehouse.endpoint)
            assertTrue(supplier.isCurrent())
            assertTrue(warehouse.isCurrent())
            f.master.health.value = LocalMasterEndpointHealth.STOPPING
            assertFalse(warehouse.isCurrent())
            f.master.health.value = LocalMasterEndpointHealth.ACTIVE
            originalCurrent = false
            assertFalse(supplier.isCurrent())
            assertFalse(warehouse.isCurrent())
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.reads.isEmpty())
        }
        // Route/profile carriers and ACTIVE neither authenticate a human nor publish data.
    }

    @Test
    fun wh006wh020ActualWarehouseRequestCodecsKeepExactCopiedBearerAndOriginalBodies() = runTest(timeout = 5.seconds) {
        val caller = frozen.bearer()
        val credentials = LocalGatewayCredentials(frozen.connection, OpaqueCredential(caller))
        val query = ReadRequest(LocalConfigurationProfile.QUERY,
            LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, limit = 50)))
        val request = frozen.request(frozen.rename())
        val identity = frozen.saved(frozen.rename()).identity()
        val read = LocalMasterRpcReadRequestV1.capture(credentials, query)
        val execute = LocalMasterRpcExecuteRequestV1.capture(credentials, request)
        val lookup = LocalMasterRpcLookupRequestV1.capture(credentials, identity)
        caller.fill(frozen.bearerInputs.text("caller_changed_byte_hex").toInt(16).toByte())
        credentials.bearer.destroy()
        try {
            for (owned in listOf(read, execute, lookup)) owned.useBearer { assertContentEquals(frozen.bearer(), it) }
            ownedRoundTrip(LocalMasterWarehouseRpcProtocolV1.READ.request, LocalMasterClientRpcProtocolV1.READ.request, read) {
                assertEquals(query.query, it.request.query)
                assertEquals(query.payload, it.request.payload)
            }
            ownedRoundTrip(LocalMasterWarehouseRpcProtocolV1.EXECUTE.request, LocalMasterClientRpcProtocolV1.EXECUTE.request, execute) {
                assertEquals(request.operationId, it.request.operationId)
                assertEquals(request.command, it.request.command)
                assertEquals(request.payload, it.request.payload)
                assertNull(it.request.actionApprovalId)
            }
            ownedRoundTrip(LocalMasterWarehouseRpcProtocolV1.LOOKUP.request, LocalMasterClientRpcProtocolV1.LOOKUP.request, lookup) {
                assertEquals(identity, it.identity)
            }
            assertTrue(caller.all { it == 0xaa.toByte() }, "Request disposal must not clear caller-owned input")
            for (owned in listOf(read, execute, lookup)) {
                owned.awaitCleanup()
                assertTrue(owned.isCleanupComplete)
                assertFalse(owned.claimForCallback())
                assertFailsWith<IllegalStateException> { owned.useBearer { } }
            }
        } finally { read.destroy(); execute.destroy(); lookup.destroy(); credentials.bearer.destroy(); caller.fill(0) }
        // A completed public cleanup indication is not an inspection of SDK-owned buffers.
    }

    @Test
    fun wh006AllThreeCarriersRejectFiftyOneAndFiftyThreeInsteadOfPaddingOrTruncation() {
        val query = ReadRequest(LocalConfigurationProfile.QUERY,
            LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE)))
        for (length in frozen.invalidInputs.getValue("bearer_lengths").jsonArray.map { it.jsonPrimitive.int }.filter { it != 52 }) {
            val credentials = LocalGatewayCredentials(frozen.connection, OpaqueCredential(ByteArray(length) { 0x33 }))
            try {
                assertFailsWith<IllegalArgumentException> { LocalMasterRpcReadRequestV1.capture(credentials, query) }
                assertFailsWith<IllegalArgumentException> { LocalMasterRpcExecuteRequestV1.capture(credentials, frozen.request(frozen.create())) }
                assertFailsWith<IllegalArgumentException> { LocalMasterRpcLookupRequestV1.capture(credentials, frozen.saved(frozen.create()).identity()) }
            } finally { credentials.bearer.destroy() }
        }
        val credentials = frozen.credentials()
        val original = LocalMasterRpcReadRequestV1.capture(credentials, query)
        val encoded = try { frozen.wire.encodeToJsonElement(LocalMasterWarehouseRpcProtocolV1.READ.request, original).jsonObject }
        finally { original.destroy(); credentials.bearer.destroy() }
        for (length in listOf(51, 53)) {
            val wrong = JsonObject(encoded + ("access" to JsonArray(List(length) { JsonPrimitive(0x33) })))
            assertFailsWith<SerializationException> {
                frozen.wire.decodeFromJsonElement(LocalMasterWarehouseRpcProtocolV1.READ.request, wrong)
            }
        }
    }

    @Test
    fun wh003MissingOuterObservedRevisionAndInvalidPageLimitsDoNotDecodeAsEmptySuccess() {
        for (limit in frozen.invalidInputs.getValue("page_limits_invalid").jsonArray.map { it.jsonPrimitive.int })
            assertFailsWith<IllegalArgumentException> { LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, limit = limit) }
        val response = LocalMasterClientRpcProtocolV1.captureRead(frozen.actor,
            frozen.data(LocalConfigurationPageV1(LocalConfigurationProfile.WAREHOUSE, emptyList(), null)))
        val encoded = frozen.wire.encodeToJsonElement(LocalMasterWarehouseRpcProtocolV1.READ.response, response).jsonObject
        val value = encoded.getValue("value").jsonObject
        val missing = JsonObject(encoded + ("value" to JsonObject(value - "branchRevision")))
        assertFailsWith<SerializationException> {
            frozen.wire.decodeFromJsonElement(LocalMasterWarehouseRpcProtocolV1.READ.response, missing)
        }
        val decoded = frozen.wire.decodeFromJsonElement(LocalMasterWarehouseRpcProtocolV1.READ.response, encoded)
        assertEquals(frozen.observedRevision, assertIs<LocalMasterRpcReadValueV1.Data>(decoded.value).branchRevision)
        val refusal = LocalMasterClientRpcProtocolV1.captureRead(frozen.actor, ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED))
        assertIs<LocalMasterRpcReadValueV1.Unavailable>(frozen.wire.decodeFromJsonElement(LocalMasterWarehouseRpcProtocolV1.READ.response,
            frozen.wire.encodeToJsonElement(LocalMasterWarehouseRpcProtocolV1.READ.response, refusal)).value)
    }

    @Test
    fun wh006ExistingNameAndRevisionNormalizersPreserveFullValidContentAndRejectFrozenInvalidValues() {
        val handler = LocalConfigurationHandler(UnavailableCompanyPublicationSignatureVerifier)
        val original = frozen.wire.encodeToJsonElement(LocalConfigurationRequestV1.serializer(), frozen.body(frozen.create())).jsonObject
        for (length in frozen.invalidInputs.getValue("name_length_valid").jsonArray.map { it.jsonPrimitive.int })
            for ((key, character) in listOf("english" to 'A', "arabic" to 'م')) {
                val text = character.toString().repeat(length)
                val normalized = handler.normalize(replaceName(original, key, text))
                val body = assertIs<LocalConfigurationRequestV1.ConfigureWarehouse>(LocalConfigurationCodec.decode(normalized))
                assertEquals(text, if (key == "english") body.warehouse.names.english else body.warehouse.names.arabic)
            }
        for (length in frozen.invalidInputs.getValue("name_length_invalid").jsonArray.map { it.jsonPrimitive.int })
            for ((key, character) in listOf("english" to 'A', "arabic" to 'م'))
                assertFails { handler.normalize(replaceName(original, key, character.toString().repeat(length))) }
        for (control in frozen.invalidInputs.getValue("control_characters").jsonArray) {
            val character = control.jsonPrimitive.content.removePrefix("U+").toInt(16).toChar()
            for (key in listOf("english", "arabic")) assertFails { handler.normalize(replaceName(original, key, "name${character}invalid")) }
        }
        val validRevision = JsonObject(original + ("expectedStorageRevision" to
            frozen.wire.encodeToJsonElement(Revision.serializer(), frozen.storageRevision)))
        val normalized = handler.normalize(frozen.bytes(validRevision))
        assertEquals(frozen.storageRevision, assertIs<LocalConfigurationRequestV1.ConfigureWarehouse>(
            LocalConfigurationCodec.decode(normalized)).expectedStorageRevision)
        for (revision in frozen.invalidInputs.getValue("revision_invalid").jsonArray) {
            val invalid = JsonObject(original + ("expectedStorageRevision" to JsonPrimitive(revision.jsonPrimitive.content)))
            assertFails { handler.normalize(frozen.bytes(invalid)) }
        }
    }

    private fun replaceName(request: JsonObject, key: String, text: String): StoredBytes {
        val warehouse = request.getValue("warehouse").jsonObject
        val names = warehouse.getValue("names").jsonObject
        return frozen.bytes(JsonObject(request + ("warehouse" to JsonObject(warehouse +
            ("names" to JsonObject(names + (key to JsonPrimitive(text))))))))
    }

    private fun <T : LocalMasterRpcOwnedRequestV1> ownedRoundTrip(warehouse: KSerializer<T>, supplier: KSerializer<T>,
        original: T, verify: (T) -> Unit) {
        assertSame(supplier, warehouse)
        // The real serializer owns terminal disposal, so never encode one live request twice.
        val encoded = frozen.wire.encodeToString(warehouse, original)
        assertTrue(original.isCleanupComplete)
        val decoded = frozen.wire.decodeFromString(warehouse, encoded)
        try {
            decoded.useBearer { assertContentEquals(frozen.bearer(), it) }
            verify(decoded)
            assertEquals(encoded, frozen.wire.encodeToString(supplier, decoded))
            assertTrue(decoded.isCleanupComplete)
        } finally { decoded.destroy() }
    }
}
