@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.database.independent.supplierworkflow

import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationListItemV1
import com.ukkera.pos.catalog.store.LocalConfigurationPageV1
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalSupplierV1
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterClientRpcErrorV1
import com.ukkera.pos.database.LocalMasterClientRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestSerializerV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1
import com.ukkera.pos.database.LocalMasterRpcExecuteResponseV1
import com.ukkera.pos.database.LocalMasterRpcExecuteValueV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestSerializerV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupResponseV1
import com.ukkera.pos.database.LocalMasterRpcLookupValueV1
import com.ukkera.pos.database.LocalMasterRpcOwnedRequestV1
import com.ukkera.pos.database.LocalMasterRpcReadRequestSerializerV1
import com.ukkera.pos.database.LocalMasterRpcReadRequestV1
import com.ukkera.pos.database.LocalMasterRpcReadResponseV1
import com.ukkera.pos.database.LocalMasterRpcReadValueV1
import com.ukkera.pos.database.LocalMasterRpcRecordedV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * SUPPORT only: narrower codec/ownership slices of frozen supplier-rpc-workflow v1,
 * especially SWF-018/028/029/042/044/045. No method passes a whole SWF case.
 *
 * Actual public request/response serializers and supplier representation codecs are used.
 * IDs and name boundaries come from F-BASE/F-OPS/F-PAGES/F-NAMES/F-INPUTS/F-WIRE;
 * bearer buffers and encoder failures are synthetic. Scalar revisions and the opaque
 * digest below are representation inputs, not an observed R0, computed business digest,
 * native original, approval, writer qualification or durable committed result.
 *
 * Complete DTO JSON overhead is counted, including signed byte-array expansion. SDK
 * framing, qualified delivery, request correlation, current resource authority, native
 * custody/close, persistence, commit/disclosure fences, restart and APP/RPC behavior are
 * unverified. There is no public trusted reply decoder to exercise here. Valid DTOs,
 * including caller-supplied success/absence and foreign-owner fields, remain inert data.
 * SWF-046 physical lifetime/currentness/expiry and undeclared depth bounds remain unbound.
 * Public disposable-copy observations do not inspect private retained bearer storage or
 * promise erasure of immutable JSON intermediates or SDK-owned packet buffers.
 */
class LocalMasterRpcSupplierWireCodecSupportingTest {
    @Test
    fun swf045_descriptorsDeclareFiniteBudgetsAndNeverReinvoke() {
        val protocol = LocalMasterClientRpcProtocolV1
        assertEquals(65_536, protocol.MAXIMUM_PAYLOAD_BYTES)
        assertEquals(52, protocol.ACCESS_BEARER_BYTES)
        assertTrue(protocol.MAXIMUM_WIRE_BYTES > protocol.MAXIMUM_PAYLOAD_BYTES)
        val procedures = listOf(protocol.READ, protocol.EXECUTE, protocol.LOOKUP)
        assertEquals(listOf("pos.business.read", "pos.business.execute", "pos.business.lookup"), procedures.map { it.name })
        for (procedure in procedures) {
            assertEquals(1, procedure.version)
            assertEquals(RpcRetrySafety.NeverReinvoke, procedure.retrySafety)
            assertEquals(protocol.MAXIMUM_WIRE_BYTES, procedure.requestLimitBytes)
            assertEquals(protocol.MAXIMUM_WIRE_BYTES, procedure.responseLimitBytes)
            assertEquals(protocol.ERROR_LIMIT_BYTES, procedure.errorLimitBytes)
            assertTrue(procedure.requestLimitBytes > 0 && procedure.responseLimitBytes > 0 && procedure.errorLimitBytes > 0)
        }
        val error = json.encodeToString(LocalMasterClientRpcErrorV1.serializer(), LocalMasterClientRpcErrorV1.UNAVAILABLE)
        assertTrue(error.encodeToByteArray().size <= protocol.ERROR_LIMIT_BYTES)
        assertEquals(LocalMasterClientRpcErrorV1.UNAVAILABLE, json.decodeFromString(LocalMasterClientRpcErrorV1.serializer(), error))
    }

    @Test
    fun swf045_readAndExecutePayloadBoundariesIncludeWholeOuterJson() {
        for (size in payloadBoundaries) for (byte in listOf<Byte>(0, 127, -128)) {
            val payload = ByteArray(size) { byte }
            for (kind in listOf(RequestKind.READ, RequestKind.EXECUTE)) {
                val wire = validRequestWire(kind, payload)
                val expected = requestOverheadBytes(kind) + independentByteArrayJsonBytes(payload)
                assertEquals(expected, wire.encodeToByteArray().size, "Complete request envelope byte count")
                assertTrue(expected <= LocalMasterClientRpcProtocolV1.MAXIMUM_WIRE_BYTES)
                val decoded = decodeRequest(kind, wire)
                try { assertPayloadEquals(payload, decoded) }
                finally { decoded.destroy() }
            }
        }
        // Maximum syntactic headers are codec inputs, not advertised supplier selectors or approval.
        val payload = ByteArray(LocalMasterClientRpcProtocolV1.MAXIMUM_PAYLOAD_BYTES) { -128 }
        val access = ByteArray(LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES) { -128 }
        val credential = OpaqueCredential(access)
        val name = "x".repeat(96)
        val approvalReference = DomainId.random()
        val credentials = LocalGatewayCredentials(DomainId.random(), credential)
        val read = LocalMasterRpcReadRequestV1.capture(credentials, ReadRequest(ReadKey(name, 1000), StoredBytes(payload)))
        val execute = LocalMasterRpcExecuteRequestV1.capture(credentials,
            MutationRequest(operationO, CommandKey(name, 1000), StoredBytes(payload), approvalReference))
        try {
            val readPrefix = "{\"access\":${byteArrayJson(access)},\"request\":{\"queryName\":\"$name\",\"queryVersion\":1000,\"payload\":"
            val executePrefix = "{\"access\":${byteArrayJson(access)},\"request\":{\"operationId\":\"${operationO.value}\",\"commandName\":\"$name\",\"commandVersion\":1000,\"payload\":"
            val frames = listOf(read to (readPrefix + "}}"), execute to (executePrefix + ",\"actionApprovalId\":\"${approvalReference.value}\"}}"))
            for ((request, headerWithoutPayload) in frames) {
                val expected = headerWithoutPayload.encodeToByteArray().size + independentByteArrayJsonBytes(payload)
                assertEquals(expected, encodeRequest(request).encodeToByteArray().size)
                assertTrue(expected <= LocalMasterClientRpcProtocolV1.MAXIMUM_WIRE_BYTES)
            }
        } finally { read.destroy(); execute.destroy(); credential.destroy(); access.fill(0) }
    }

    @Test
    fun swf045_dataAndRecordedPayloadBoundariesIncludeWholeOuterJson() {
        for (size in payloadBoundaries) for (byte in listOf<Byte>(0, 127, -128)) {
            val payload = ByteArray(size) { byte }
            for (fixture in responseFixtures(payload)) {
                val measured = fixture.wire.encodeToByteArray().size
                assertEquals(fixture.overheadBytes + independentByteArrayJsonBytes(payload), measured,
                    "Complete response envelope byte count")
                assertTrue(measured <= LocalMasterClientRpcProtocolV1.MAXIMUM_WIRE_BYTES)
                val decoded = fixture.decode(fixture.wire)
                assertTrue(responsePayload(decoded).copyBytes().contentEquals(payload), "Response payload remains exact")
            }
        }
        val payload = ByteArray(LocalMasterClientRpcProtocolV1.MAXIMUM_PAYLOAD_BYTES) { -128 }
        val longestCode = "r".repeat(96)
        val rejectedData = recorded(payload, false).copy(committed = false, code = longestCode)
        val response = LocalMasterRpcExecuteResponseV1(actorA, LocalMasterRpcExecuteValueV1.Recorded(rejectedData))
        val wireBytes = json.encodeToString(LocalMasterRpcExecuteResponseV1.serializer(), response).encodeToByteArray().size
        val codeAndBooleanDelta = longestCode.length - "committed".length + "false".length - "true".length
        assertEquals(recordedResponseOverheadBytes("RECORDED", false) + codeAndBooleanDelta + independentByteArrayJsonBytes(payload), wireBytes)
        assertTrue(wireBytes <= LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes)
    }

    @Test
    fun swf045_fiftyMaximumNamePairsRetainMultibyteBytesAndOuterOverhead() {
        val variants = listOf(LocalNamesV1("C".repeat(160), "س".repeat(160)) to 24_000,
            LocalNamesV1("中".repeat(160), "中".repeat(160)) to 48_000)
        for ((names, expectedRawBytes) in variants) {
            val rawBytes = 50 * (names.english.encodeToByteArray().size + names.arabic.encodeToByteArray().size)
            assertEquals(expectedRawBytes, rawBytes)
            val page = LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER,
                List(50) { index -> LocalConfigurationListItemV1(pageId(index + 1), maximumRevision,
                    maximumRevision, names, true, null) }, pageId(50), maximumRevision)
            val innerBytes = json.encodeToString(LocalConfigurationPageV1.serializer(), page).encodeToByteArray()
            assertTrue(innerBytes.size > rawBytes, "Supplier syntax is included, not only names")
            assertTrue(innerBytes.size <= LocalMasterClientRpcProtocolV1.MAXIMUM_PAYLOAD_BYTES)
            val dto = readResponse(innerBytes)
            val wire = json.encodeToString(LocalMasterRpcReadResponseV1.serializer(), dto)
            assertEquals(readResponseOverheadBytes() + independentByteArrayJsonBytes(innerBytes), wire.encodeToByteArray().size)
            assertTrue(wire.encodeToByteArray().size <= LocalMasterClientRpcProtocolV1.READ.responseLimitBytes)
            val decoded = json.decodeFromString(LocalMasterRpcReadResponseV1.serializer(), wire)
            val recovered = LocalConfigurationCodec.decodePage((decoded.value as LocalMasterRpcReadValueV1.Data).payload)
            assertEquals(50, recovered.items.size)
            assertEquals(page.items.map { it.id }, recovered.items.map { it.id })
            assertEquals(pageId(50), recovered.nextAfter)
            assertTrue(recovered.items.all { it.names == names && it.active == true }, "No shortened or omitted names")
        }
    }

    @Test
    fun swf045_supplierRequestNamesRetainEscapingAndBothActiveFlags() {
        val variants = listOf(LocalNamesV1("C".repeat(160), "س".repeat(160)),
            LocalNamesV1("中".repeat(160), "中".repeat(160)),
            LocalNamesV1("\"".repeat(160), "\\".repeat(160)))
        assertEquals(320, JsonPrimitive(variants.last().english).toString().encodeToByteArray().size - 2)
        assertEquals(320, JsonPrimitive(variants.last().arabic).toString().encodeToByteArray().size - 2)
        for (names in variants) for (active in listOf(true, false)) {
            // This is a typed representation, not a claim that this synthetic revision may execute.
            val configured = LocalConfigurationRequestV1.ConfigureSupplier(maximumRevision,
                LocalSupplierV1(supplierC, names, active))
            val payload = LocalConfigurationCodec.encode(configured).copyBytes()
            val wire = validRequestWire(RequestKind.EXECUTE, payload)
            assertEquals(requestOverheadBytes(RequestKind.EXECUTE) + independentByteArrayJsonBytes(payload), wire.encodeToByteArray().size)
            assertTrue(wire.encodeToByteArray().size <= LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes)
            val decoded = decodeRequest(RequestKind.EXECUTE, wire) as LocalMasterRpcExecuteRequestV1
            try {
                val recovered = LocalConfigurationCodec.decode(decoded.request.payload) as LocalConfigurationRequestV1.ConfigureSupplier
                assertEquals(configured, recovered)
                assertEquals(operationO, decoded.request.operationId)
            } finally { decoded.destroy() }
        }
    }

    @Test
    fun swf044_malformedRequestFramingHasNoReturnedRequestOrPrivateCause() {
        for (kind in RequestKind.entries) {
            val wire = validRequestWire(kind)
            for (malformed in listOf("", "[]", "null", "{", wire.dropLast(1), wire.dropLast(1) + ",}"))
                expectMalformedRequest(kind, malformed)
        }
    }

    @Test
    fun swf044_missingAccessOrBodyIsRefusedForEveryRequestKind() {
        for (kind in RequestKind.entries) {
            val root = json.parseToJsonElement(validRequestWire(kind)).jsonObject
            for (missing in listOf("access", bodyField(kind)))
                expectMalformedRequest(kind, JsonObject(root - missing).toString())
            expectMalformedRequest(kind, "{}")
        }
    }

    @Test
    fun swf044_duplicateAccessAndBodyFieldsAreRefusedIncludingEscapedAlias() {
        for (kind in RequestKind.entries) {
            val wire = validRequestWire(kind)
            val root = json.parseToJsonElement(wire).jsonObject
            for (field in listOf("access", bodyField(kind))) {
                val duplicate = wire.dropLast(1) + ",\"$field\":" + root.getValue(field) + "}"
                expectMalformedRequest(kind, duplicate)
            }
            val conflicting = wire.dropLast(1) + ",\"access\":" + byteArrayJson(ByteArray(52) { 3 }) + "}"
            expectMalformedRequest(kind, conflicting)
            expectMalformedRequest(kind, wire.dropLast(1) + ",\"\\u0061ccess\":" + root.getValue("access") + "}")
        }
    }

    @Test
    fun swf044_wrongLengthBearerFieldsAreRefusedAtBothBoundaries() {
        for (kind in RequestKind.entries) {
            val root = json.parseToJsonElement(validRequestWire(kind)).jsonObject
            for (size in listOf(0, 1, 51, 53, 8192)) {
                val altered = JsonObject(root + ("access" to JsonArray(List(size) { JsonPrimitive(1) })))
                expectMalformedRequest(kind, altered.toString())
            }
        }
    }

    @Test
    fun swf044_malformedBearerTypesAndPartialByteArraysAreRefused() {
        val nonArrays = listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonPrimitive("not-a-byte-array"), JsonObject(emptyMap()))
        val invalidBytes = listOf<JsonElement>(JsonPrimitive(128), JsonPrimitive(-129), JsonPrimitive(1.5),
            JsonPrimitive("not-a-byte"), JsonPrimitive(false), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))
        for (kind in RequestKind.entries) {
            val root = json.parseToJsonElement(validRequestWire(kind)).jsonObject
            for (value in nonArrays) expectMalformedRequest(kind, JsonObject(root + ("access" to value)).toString())
            for (position in listOf(0, 26, 51)) for (value in invalidBytes) {
                val bytes = MutableList<JsonElement>(52) { JsonPrimitive(1) }.also { it[position] = value }
                expectMalformedRequest(kind, JsonObject(root + ("access" to JsonArray(bytes))).toString())
            }
        }
    }

    @Test
    fun swf044_unexpectedCallerAuthorityFieldsAreNotBusinessRequestInputs() {
        for (kind in RequestKind.entries) {
            val root = json.parseToJsonElement(validRequestWire(kind)).jsonObject
            assertEquals(setOf("access", bodyField(kind)), root.keys)
            for (field in listOf("actorId", "deviceId", "connectionId", "permissions", "physicalSession", "lifetime")) {
                expectMalformedRequest(kind, JsonObject(root + (field to JsonPrimitive(actorB.value))).toString())
                val body = root.getValue(bodyField(kind)).jsonObject
                expectMalformedRequest(kind, JsonObject(root + (bodyField(kind) to JsonObject(body + (field to JsonPrimitive(actorB.value))))).toString())
            }
        }
    }

    @Test
    fun swf044_payloadOneAboveLimitIsRefusedByRequestAndResponseSerializers() {
        val oversized = JsonArray(List(LocalMasterClientRpcProtocolV1.MAXIMUM_PAYLOAD_BYTES + 1) { JsonPrimitive(-128) })
        for (kind in listOf(RequestKind.READ, RequestKind.EXECUTE)) {
            val root = json.parseToJsonElement(validRequestWire(kind)).jsonObject
            expectMalformedRequest(kind, replace(root, listOf("request", "payload"), oversized).toString())
        }
        for (fixture in responseFixtures(byteArrayOf(1))) {
            val root = json.parseToJsonElement(fixture.wire).jsonObject
            assertFails { fixture.decode(replace(root, fixture.payloadPath, oversized).toString()) }
        }
        assertFailsWith<IllegalArgumentException> { StoredBytes(ByteArray(LocalMasterClientRpcProtocolV1.MAXIMUM_PAYLOAD_BYTES + 1)) }
    }

    @Test
    fun swf042_captureCopiesCallerBearerAndPayloadWithoutBorrowingAnotherRequest() {
        for (kind in RequestKind.entries) {
            val input = syntheticBearer()
            val expected = input.copyOf()
            val payload = byteArrayOf(1, -2, 3)
            val expectedPayload = payload.copyOf()
            val credentials = LocalGatewayCredentials(DomainId.random(), OpaqueCredential(input))
            val first = captureRequest(kind, credentials, payload)
            val second = captureRequest(kind, credentials, payload)
            try {
                input.fill(0); payload.fill(0); credentials.bearer.destroy()
                assertEquals("LocalMasterRpcOwnedRequestV1([REDACTED])", first.toString())
                first.useBearer { assertTrue(it.contentEquals(expected), "Captured request has its own bearer") }
                if (kind != RequestKind.LOOKUP) assertPayloadEquals(expectedPayload, first)
                first.destroy()
                assertTrue(first.isDestroyed)
                assertFailsWith<IllegalStateException> { first.useBearer { } }
                second.useBearer { assertTrue(it.contentEquals(expected), "Independent capture survives retirement") }
            } finally {
                first.destroy(); second.destroy(); credentials.bearer.destroy(); input.fill(0); expected.fill(0)
            }
        }
    }

    @Test
    fun swf042_successfulSerializationConsumesOnlyTheOwnedRequest() {
        for (kind in RequestKind.entries) withCredentials { credentials, expected ->
            val captured = captureRequest(kind, credentials, byteArrayOf(1, 2))
            try {
                val wire = encodeRequest(captured)
                assertTrue(captured.isDestroyed)
                assertFalse(captured.claimForCallback())
                assertFailsWith<SerializationException> { encodeRequest(captured) }
                credentials.bearer.useBytes { assertTrue(it.contentEquals(expected), "Serialization does not consume caller credentials") }
                val decoded = decodeRequest(kind, wire)
                try {
                    assertFalse(decoded.isDestroyed)
                    decoded.useBearer { assertTrue(it.contentEquals(expected), "Decoded bearer belongs to a new request") }
                } finally { decoded.destroy() }
            } finally { captured.destroy() }
        }
    }

    @Test
    fun swf042_encoderErrorAndCancellationClearDisposableLoanAndConsumeRequest() {
        for (kind in RequestKind.entries) for (failure in listOf(SerializationException("Synthetic encoder failure"),
            CancellationException("Synthetic encoder cancellation"))) withCredentials { credentials, expected ->
            val captured = captureRequest(kind, credentials, byteArrayOf(1))
            val encoder = LoanFailureEncoder(failure)
            try {
                val caught = assertFails { serializeRequest(captured, encoder) }
                assertSame(failure, caught)
                assertTrue(captured.isDestroyed)
                assertFalse(captured.claimForCallback())
                val loan = checkNotNull(encoder.loan)
                assertTrue(loan.all { it == 0.toByte() }, "Disposable encoder loan is cleared on failure")
                assertFailsWith<IllegalStateException> { captured.useBearer { } }
                credentials.bearer.useBytes { assertTrue(it.contentEquals(expected), "Caller ownership survives encoder failure") }
            } finally { captured.destroy() }
        }
    }

    @Test
    fun swf042_terminalRetirementDuringLoanBlocksNewUseButDoesNotPartlyClearLoan() {
        for (kind in RequestKind.entries) withCredentials { credentials, expected ->
            val captured = captureRequest(kind, credentials, byteArrayOf(1))
            var loan: ByteArray? = null
            try {
                captured.useBearer { bytes ->
                    loan = bytes
                    captured.destroy()
                    assertTrue(captured.isDestroyed)
                    assertTrue(bytes.contentEquals(expected), "Active synchronous loan remains stable")
                    assertFailsWith<IllegalStateException> { captured.useBearer { } }
                }
                assertTrue(checkNotNull(loan).all { it == 0.toByte() }, "Returned disposable loan is cleared")
                assertFalse(captured.claimForCallback())
                captured.destroy(); captured.destroyIfUnclaimed()
                assertTrue(captured.isDestroyed)
            } finally { captured.destroy() }
        }
    }

    @Test
    fun swf042_callbackClaimAndUnclaimedRetirementHaveDistinctOwnership() {
        for (kind in RequestKind.entries) withCredentials { credentials, expected ->
            val unclaimed = captureRequest(kind, credentials, byteArrayOf(1))
            val claimed = captureRequest(kind, credentials, byteArrayOf(1))
            try {
                unclaimed.destroyIfUnclaimed()
                assertTrue(unclaimed.isDestroyed)
                assertFalse(unclaimed.claimForCallback())
                assertTrue(claimed.claimForCallback())
                assertFalse(claimed.claimForCallback())
                claimed.destroyIfUnclaimed()
                assertFalse(claimed.isDestroyed)
                claimed.useBearer { assertTrue(it.contentEquals(expected), "Claimed callback owns terminal cleanup") }
                claimed.destroy()
                assertTrue(claimed.isDestroyed)
                assertFalse(claimed.claimForCallback())
            } finally { unclaimed.destroy(); claimed.destroy() }
        }
    }

    @Test
    fun swf018_lookupRoundTripPreservesOriginalSelectorButContainsNoTransportLifetime() {
        val original = operationIdentity()
        withCredentials { credentials, _ ->
            val captured = LocalMasterRpcLookupRequestV1.capture(credentials, original)
            val wire = try { encodeRequest(captured) } finally { captured.destroy() }
            val root = json.parseToJsonElement(wire).jsonObject
            val identity = root.getValue("identity").jsonObject
            assertEquals(setOf("branchId", "operationId", "originalActorId", "semanticDigestVersion", "semanticSha256"), identity.keys)
            val decoded = decodeRequest(RequestKind.LOOKUP, wire) as LocalMasterRpcLookupRequestV1
            try { assertEquals(original, decoded.identity) }
            finally { decoded.destroy() }
            val otherConnection = LocalGatewayCredentials(DomainId.random(), credentials.bearer)
            val second = LocalMasterRpcLookupRequestV1.capture(otherConnection, original)
            val secondWire = try { encodeRequest(second) } finally { second.destroy() }
            assertTrue(wire == secondWire, "A transport connection is not reconstructed from body bytes")
        }
    }

    @Test
    fun swf044_wrongOwnerAndOperationRecordedDtoRoundTripRemainsOnlyCallerData() {
        val claimed = recorded(byteArrayOf(7), replayed = false).copy(operationId = operationO2, originalActorId = actorB)
        val response = LocalMasterRpcExecuteResponseV1(actorB, LocalMasterRpcExecuteValueV1.Recorded(claimed))
        val wire = json.encodeToString(LocalMasterRpcExecuteResponseV1.serializer(), response)
        val decoded = json.decodeFromString(LocalMasterRpcExecuteResponseV1.serializer(), wire)
        assertEquals(actorB, decoded.requestingActorId)
        assertEquals(claimed, (decoded.value as LocalMasterRpcExecuteValueV1.Recorded).recorded)
        // No bridge invocation, trusted result construction, display adoption or effect assertion.
        assertEquals("LocalMasterRpcExecuteResponseV1(data omitted)", decoded.toString())
    }

    @Test
    fun swf029_absenceDtoIsDistinctFromEveryDeferredReasonWithoutProvingAbsence() {
        val absent = LocalMasterRpcLookupResponseV1(actorA, LocalMasterRpcLookupValueV1.AbsentAtCurrentWriter(operationO))
        val absentWire = json.encodeToString(LocalMasterRpcLookupResponseV1.serializer(), absent)
        assertEquals("ABSENT_AT_CURRENT_WRITER", json.parseToJsonElement(absentWire).jsonObject
            .getValue("value").jsonObject.getValue("type").jsonPrimitive.content)
        val decodedAbsent = json.decodeFromString(LocalMasterRpcLookupResponseV1.serializer(), absentWire)
        assertEquals(absent, decodedAbsent)
        for (reason in DeferredReason.entries) {
            val deferred = LocalMasterRpcLookupResponseV1(actorA, LocalMasterRpcLookupValueV1.Deferred(operationO, reason))
            val wire = json.encodeToString(LocalMasterRpcLookupResponseV1.serializer(), deferred)
            assertEquals("DEFERRED", json.parseToJsonElement(wire).jsonObject.getValue("value").jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue(wire != absentWire)
            assertEquals(deferred, json.decodeFromString(LocalMasterRpcLookupResponseV1.serializer(), wire))
        }
        // Both DTO variants are inert; only qualified current lookup can establish absence.
    }

    @Test
    fun swf044_responseVariantAndRefusalEnumsRejectUnknownSyntax() {
        for (fixture in responseFixtures(byteArrayOf(1))) {
            val root = json.parseToJsonElement(fixture.wire).jsonObject
            assertFails { fixture.decode(replace(root, listOf("value", "type"), JsonPrimitive("UNSUPPORTED_SCHEMA")).toString()) }
            assertFails { fixture.decode(JsonObject(root - "requestingActorId").toString()) }
            assertFails { fixture.decode(fixture.wire.dropLast(1)) }
        }
        val unavailable = LocalMasterRpcReadResponseV1(actorA, LocalMasterRpcReadValueV1.Unavailable(ReadFailure.STORAGE_UNCERTAIN))
        val readRoot = json.parseToJsonElement(json.encodeToString(LocalMasterRpcReadResponseV1.serializer(), unavailable)).jsonObject
        assertFails { json.decodeFromString(LocalMasterRpcReadResponseV1.serializer(),
            replace(readRoot, listOf("value", "reason"), JsonPrimitive("UNKNOWN_REFUSAL")).toString()) }
        val deferred = LocalMasterRpcLookupResponseV1(actorA, LocalMasterRpcLookupValueV1.Deferred(operationO, DeferredReason.STORAGE_UNCERTAIN))
        val lookupRoot = json.parseToJsonElement(json.encodeToString(LocalMasterRpcLookupResponseV1.serializer(), deferred)).jsonObject
        assertFails { json.decodeFromString(LocalMasterRpcLookupResponseV1.serializer(),
            replace(lookupRoot, listOf("value", "reason"), JsonPrimitive("NOT_RECORDED")).toString()) }
        assertFailsWith<SerializationException> { json.decodeFromString(LocalMasterClientRpcErrorV1.serializer(), "\"INTERNAL_PRIVATE_ERROR\"") }
    }

    @Test
    fun swf044_revisionScalarsRemainExactStringsAndRecordedCodeIsCoherent() {
        for (revision in listOf(Revision(0), Revision(9_007_199_254_740_993L), maximumRevision)) {
            val response = readResponse(byteArrayOf(1)).copy(value = LocalMasterRpcReadValueV1.Data(StoredBytes(byteArrayOf(1)), revision, observedAt))
            val wire = json.encodeToString(LocalMasterRpcReadResponseV1.serializer(), response)
            val scalar = json.parseToJsonElement(wire).jsonObject.getValue("value").jsonObject.getValue("branchRevision").jsonPrimitive
            assertTrue(scalar.isString)
            assertEquals(revision.value.toString(), scalar.content)
            assertEquals(revision, (json.decodeFromString(LocalMasterRpcReadResponseV1.serializer(), wire).value as LocalMasterRpcReadValueV1.Data).branchRevision)
        }
        val root = json.parseToJsonElement(json.encodeToString(LocalMasterRpcReadResponseV1.serializer(), readResponse(byteArrayOf(1)))).jsonObject
        val invalid = listOf<JsonElement>(JsonPrimitive("-1"), JsonPrimitive("9223372036854775808"),
            JsonPrimitive("1.5"), JsonPrimitive("1e3"), JsonPrimitive("NaN"), JsonPrimitive("Infinity"),
            JsonPrimitive(9_007_199_254_740_993L), JsonNull)
        for (scalar in invalid) assertFails {
            json.decodeFromString(LocalMasterRpcReadResponseV1.serializer(), replace(root, listOf("value", "branchRevision"), scalar).toString())
        }
        val coherent = recorded(byteArrayOf(1), replayed = false)
        assertFailsWith<IllegalArgumentException> { coherent.copy(committed = false) }
        assertFailsWith<IllegalArgumentException> { coherent.copy(code = "refused") }
        assertFailsWith<IllegalArgumentException> { coherent.copy(code = "committed private") }
    }

    private enum class RequestKind { READ, EXECUTE, LOOKUP }

    private fun captureRequest(kind: RequestKind, credentials: LocalGatewayCredentials, payload: ByteArray): LocalMasterRpcOwnedRequestV1 = when (kind) {
        RequestKind.READ -> LocalMasterRpcReadRequestV1.capture(credentials, ReadRequest(LocalConfigurationProfile.QUERY, StoredBytes(payload)))
        RequestKind.EXECUTE -> LocalMasterRpcExecuteRequestV1.capture(credentials,
            MutationRequest(operationO, LocalConfigurationProfile.COMMAND, StoredBytes(payload)))
        RequestKind.LOOKUP -> LocalMasterRpcLookupRequestV1.capture(credentials, operationIdentity())
    }

    private fun encodeRequest(request: LocalMasterRpcOwnedRequestV1): String = when (request) {
        is LocalMasterRpcReadRequestV1 -> json.encodeToString(LocalMasterRpcReadRequestSerializerV1, request)
        is LocalMasterRpcExecuteRequestV1 -> json.encodeToString(LocalMasterRpcExecuteRequestSerializerV1, request)
        is LocalMasterRpcLookupRequestV1 -> json.encodeToString(LocalMasterRpcLookupRequestSerializerV1, request)
    }

    private fun serializeRequest(request: LocalMasterRpcOwnedRequestV1, encoder: Encoder): Unit = when (request) {
        is LocalMasterRpcReadRequestV1 -> LocalMasterRpcReadRequestSerializerV1.serialize(encoder, request)
        is LocalMasterRpcExecuteRequestV1 -> LocalMasterRpcExecuteRequestSerializerV1.serialize(encoder, request)
        is LocalMasterRpcLookupRequestV1 -> LocalMasterRpcLookupRequestSerializerV1.serialize(encoder, request)
    }

    private fun decodeRequest(kind: RequestKind, wire: String): LocalMasterRpcOwnedRequestV1 = when (kind) {
        RequestKind.READ -> json.decodeFromString(LocalMasterRpcReadRequestSerializerV1, wire)
        RequestKind.EXECUTE -> json.decodeFromString(LocalMasterRpcExecuteRequestSerializerV1, wire)
        RequestKind.LOOKUP -> json.decodeFromString(LocalMasterRpcLookupRequestSerializerV1, wire)
    }

    private fun bodyField(kind: RequestKind): String = if (kind == RequestKind.LOOKUP) "identity" else "request"

    private fun validRequestWire(kind: RequestKind, payload: ByteArray = byteArrayOf(1)): String = withCredentials { credentials, _ ->
        val request = captureRequest(kind, credentials, payload)
        try { encodeRequest(request) } finally { request.destroy() }
    }

    private fun expectMalformedRequest(kind: RequestKind, wire: String) {
        val error = assertFailsWith<SerializationException> { decodeRequest(kind, wire).destroy() }
        assertEquals("Malformed business request", error.message)
        assertNull(error.cause)
    }

    private fun assertPayloadEquals(expected: ByteArray, request: LocalMasterRpcOwnedRequestV1) {
        val stored = when (request) {
            is LocalMasterRpcReadRequestV1 -> request.request.payload
            is LocalMasterRpcExecuteRequestV1 -> request.request.payload
            is LocalMasterRpcLookupRequestV1 -> error("Lookup has no business payload")
        }
        assertEquals(expected.size, stored.size)
        assertTrue(stored.copyBytes().contentEquals(expected), "Exact bounded business payload")
    }

    private fun <T> withCredentials(block: (LocalGatewayCredentials, ByteArray) -> T): T {
        val bytes = syntheticBearer()
        val credential = OpaqueCredential(bytes)
        return try { block(LocalGatewayCredentials(DomainId.random(), credential), bytes) }
        finally { credential.destroy(); bytes.fill(0) }
    }

    private fun syntheticBearer(): ByteArray = ByteArray(LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES) { (it - 52).toByte() }

    private fun byteArrayJson(bytes: ByteArray): String = bytes.joinToString(",", "[", "]") { it.toString() }

    /** Count the numeric JSON array independently of either production byte serializer. */
    private fun independentByteArrayJsonBytes(bytes: ByteArray): Int =
        2 + (bytes.size - 1).coerceAtLeast(0) + bytes.sumOf { it.toString().length }

    private fun requestOverheadBytes(kind: RequestKind): Int {
        val access = syntheticBearer()
        return try {
            val prefix = when (kind) {
                RequestKind.READ -> "{\"access\":${byteArrayJson(access)},\"request\":{\"queryName\":\"${LocalConfigurationProfile.QUERY.name}\",\"queryVersion\":${LocalConfigurationProfile.QUERY.version},\"payload\":"
                RequestKind.EXECUTE -> "{\"access\":${byteArrayJson(access)},\"request\":{\"operationId\":\"${operationO.value}\",\"commandName\":\"${LocalConfigurationProfile.COMMAND.name}\",\"commandVersion\":${LocalConfigurationProfile.COMMAND.version},\"payload\":"
                RequestKind.LOOKUP -> error("Lookup has no payload array")
            }
            val suffix = if (kind == RequestKind.READ) "}}" else ",\"actionApprovalId\":null}}"
            prefix.encodeToByteArray().size + suffix.encodeToByteArray().size
        } finally { access.fill(0) }
    }

    private fun readResponse(payload: ByteArray): LocalMasterRpcReadResponseV1 = LocalMasterRpcReadResponseV1(actorA,
        LocalMasterRpcReadValueV1.Data(StoredBytes(payload), maximumRevision, observedAt))

    private fun recorded(payload: ByteArray, replayed: Boolean): LocalMasterRpcRecordedV1 = LocalMasterRpcRecordedV1(
        operationO, actorA, true, "committed", StoredBytes(payload), maximumRevision,
        JournalSequence(Long.MAX_VALUE), observedAt, replayed)

    private data class ResponseFixture(val wire: String, val payloadPath: List<String>, val overheadBytes: Int, val decode: (String) -> Any)

    private fun responseFixtures(payload: ByteArray): List<ResponseFixture> {
        val read = readResponse(payload)
        val execute = LocalMasterRpcExecuteResponseV1(actorA, LocalMasterRpcExecuteValueV1.Recorded(recorded(payload, false)))
        val lookup = LocalMasterRpcLookupResponseV1(actorA, LocalMasterRpcLookupValueV1.Found(recorded(payload, true)))
        return listOf(
            ResponseFixture(json.encodeToString(LocalMasterRpcReadResponseV1.serializer(), read), listOf("value", "payload"), readResponseOverheadBytes()) {
                json.decodeFromString(LocalMasterRpcReadResponseV1.serializer(), it)
            },
            ResponseFixture(json.encodeToString(LocalMasterRpcExecuteResponseV1.serializer(), execute), listOf("value", "recorded", "result"), recordedResponseOverheadBytes("RECORDED", false)) {
                json.decodeFromString(LocalMasterRpcExecuteResponseV1.serializer(), it)
            },
            ResponseFixture(json.encodeToString(LocalMasterRpcLookupResponseV1.serializer(), lookup), listOf("value", "recorded", "result"), recordedResponseOverheadBytes("FOUND", true)) {
                json.decodeFromString(LocalMasterRpcLookupResponseV1.serializer(), it)
            },
        )
    }

    private fun responsePayload(response: Any): StoredBytes = when (response) {
        is LocalMasterRpcReadResponseV1 -> (response.value as LocalMasterRpcReadValueV1.Data).payload
        is LocalMasterRpcExecuteResponseV1 -> (response.value as LocalMasterRpcExecuteValueV1.Recorded).recorded.result
        is LocalMasterRpcLookupResponseV1 -> (response.value as LocalMasterRpcLookupValueV1.Found).recorded.result
        else -> error("Unsupported response fixture")
    }

    private fun readResponseOverheadBytes(): Int =
        ("{\"requestingActorId\":\"${actorA.value}\",\"value\":{\"type\":\"DATA\",\"payload\":" +
            ",\"branchRevision\":\"${maximumRevision.value}\",\"observedAt\":\"$observedAt\"}}").encodeToByteArray().size

    private fun recordedResponseOverheadBytes(type: String, replayed: Boolean): Int =
        ("{\"requestingActorId\":\"${actorA.value}\",\"value\":{\"type\":\"$type\",\"recorded\":{\"operationId\":\"${operationO.value}\",\"originalActorId\":\"${actorA.value}\",\"committed\":true,\"code\":\"committed\",\"result\":" +
            ",\"authorityEpoch\":\"${maximumRevision.value}\",\"sequence\":\"${Long.MAX_VALUE}\",\"recordedAt\":\"$observedAt\",\"replayed\":$replayed}}}").encodeToByteArray().size

    private fun replace(root: JsonObject, path: List<String>, replacement: JsonElement): JsonObject {
        val field = path.first()
        val value = if (path.size == 1) replacement else replace(root.getValue(field).jsonObject, path.drop(1), replacement)
        return JsonObject(root + (field to value))
    }

    /** Public encoder fault port observes only the disposable loan supplied to serialization. */
    private class LoanFailureEncoder(private val failure: Exception) : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        var loan: ByteArray? = null
        override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
            if (value is ByteArray) loan = value
            serializer.serialize(this, value)
        }
        override fun encodeByte(value: Byte): Unit = throw failure
    }

    private fun operationIdentity(): OperationIdentity = OperationIdentity(branchId, operationO, actorA, 1, "a".repeat(64))
    private fun pageId(index: Int): DomainId = DomainId("88888888-8888-4888-8888-${index.toString().padStart(12, '0')}")

    private companion object {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
        val payloadBoundaries = listOf(1, 65_535, 65_536)
        val branchId = DomainId("33333333-3333-4333-8333-333333333331")
        val actorA = DomainId("44444444-4444-4444-8444-444444444441")
        val actorB = DomainId("44444444-4444-4444-8444-444444444442")
        val supplierC = DomainId("66666666-6666-4666-8666-666666666663")
        val operationO = OperationId("77777777-7777-4777-8777-777777777771")
        val operationO2 = OperationId("77777777-7777-4777-8777-777777777772")
        val maximumRevision = Revision(Long.MAX_VALUE)
        val observedAt = UtcInstant.parseCanonical("2030-01-02T03:04:05Z")
    }
}
