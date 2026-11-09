package com.ukkera.pos.rpc.independent.foodfinancialrpcv1

import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterFoodCashFinancialRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupResponseV1
import com.ukkera.pos.database.LocalMasterRpcLookupValueV1
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Frozen carrier/cleanup SUPPORT. No semantic FOOD body, native fact or admitted RPC is supplied. */
class FoodFinancialRpcV1CarrierSupportingTest {
    @Test
    fun ffrc029AllRequestTypesCopyTheEntirePatternAndRemainIndependent() = runTest(timeout = 15.seconds) {
        for (kind in FoodFinancialCarrierKind.entries) for (peerKind in FoodFinancialCarrierKind.entries) {
            val fixture = FoodFinancialCarrierFixture()
            val label = "FFRC-029 " + kind.name + "/" + peerKind.name
            try {
                val caller = FoodFinancialRpcV1Frozen.bearer()
                val peerCaller = FoodFinancialRpcV1Frozen.bearer()
                val first = fixture.capture(kind, caller)
                val peer = fixture.capture(peerKind, peerCaller)
                caller.fill(127)
                assertNotSame(first, peer, label)
                assertNotSame(caller, foodRetainedBearer(first), label)
                assertNotSame(foodRetainedBearer(first), foodRetainedBearer(peer), label)
                var borrowed: ByteArray? = null
                first.useBearer { bytes ->
                    borrowed = bytes
                    assertNotSame(caller, bytes, label)
                    assertNotSame(foodRetainedBearer(first), bytes, label)
                    assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), bytes, label)
                }
                assertFoodCleared(checkNotNull(borrowed), label + ": returned disposable copy")
                first.destroy()
                first.awaitCleanup()
                assertTrue(first.isDestroyed && first.isCleanupComplete, label)
                assertFoodCleared(foodRetainedBearer(first), label + ": first retained copy")
                assertFalse(peer.isDestroyed || peer.isCleanupComplete, label)
                peer.useBearer { assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), it, label + ": independent peer") }
                assertTrue(caller.all { it == 127.toByte() }, label + ": caller mutation was overwritten")
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), peerCaller, label + ": peer caller")
                assertRepresentationsHideBearer(listOf(first.toString(), peer.toString()))
            } finally { fixture.close() }
        }
    }

    @Test
    fun ffrc029UnsupportedLengthsAreRefusedByCaptureAndTheActualLaneSerializers() = runTest(timeout = 15.seconds) {
        for (kind in FoodFinancialCarrierKind.entries) {
            val fixture = FoodFinancialCarrierFixture()
            try {
                val complete = fixture.capture(kind, FoodFinancialRpcV1Frozen.bearer())
                val wire = Json.parseToJsonElement(encodeFoodCarrier(complete)).jsonObject
                assertEquals(listOf(0, 51, 53), FoodFinancialRpcV1Frozen.unsupportedLengths)
                for (length in FoodFinancialRpcV1Frozen.unsupportedLengths) {
                    val label = "FFRC-029 " + kind.name + " length " + length
                    val caller = ByteArray(length) { index -> if (index < 52) FoodFinancialRpcV1Frozen.bearer()[index] else 0 }
                    assertFailsWith<IllegalArgumentException>(label) { captureFoodCarrier(kind, caller) }
                    val malformed = JsonObject(wire.toMutableMap().also { fields ->
                        fields["access"] = JsonArray(caller.map { JsonPrimitive(it.toInt()) })
                    })
                    val refusal = assertFailsWith<SerializationException>(label) { decodeFoodCarrier(kind, malformed.toString()) }
                    assertRepresentationsHideBearer(listOf(refusal.toString()))
                }
                assertTrue(complete.isCleanupComplete, "Serialized carrier cleanup must finish")
            } finally { fixture.close() }
        }
    }

    @Test
    fun ffrc029RetirementDeniesNewUseButCancelledWaiterCannotClearAnAcceptedBorrow() = runTest(timeout = 15.seconds) {
        for (kind in FoodFinancialCarrierKind.entries) {
            val fixture = FoodFinancialCarrierFixture()
            val label = "FFRC-029 held " + kind.name
            try {
                val caller = FoodFinancialRpcV1Frozen.bearer()
                val request = fixture.capture(kind, caller)
                caller.fill(127)
                assertTrue(request.claimForCallback(), label)
                val held = fixture.borrow(listOf(request), listOf(FoodFinancialRpcV1Frozen.bearer()), label)
                request.destroy()
                val cancelled = fixture.wait(this, label + ": cancelled waiter") { request.awaitCleanup() }
                val remaining = fixture.wait(this, label + ": retained waiter") { request.awaitCleanup() }
                cancelled.assertPending()
                remaining.assertPending()
                cancelled.cancel()
                cancelled.awaitCancellation()
                remaining.assertPending()
                assertTrue(request.isDestroyed, label)
                assertFalse(request.isCleanupComplete, label)
                assertFalse(request.claimForCallback(), label)
                var laterEntries = 0
                assertFailsWith<IllegalStateException>(label) { request.useBearer { laterEntries++ } }
                assertEquals(0, laterEntries, label)
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), held.copy(), label + ": active disposable")
                assertFoodBearer(FoodFinancialRpcV1Frozen.bearer(), foodRetainedBearer(request), label + ": active retained")
                held.release()
                held.awaitReturned()
                remaining.awaitSuccess()
                assertFoodCleared(foodRetainedBearer(request), label + ": completed retained")
                repeat(3) {
                    request.destroy()
                    request.awaitCleanup()
                    assertTrue(request.isCleanupComplete, label)
                    assertFalse(request.claimForCallback(), label)
                }
                assertTrue(caller.all { it == 127.toByte() }, label + ": caller mutation was overwritten")
            } finally { fixture.close() }
        }
    }

    @Test
    fun ffrc011And025LookupCopiesEveryRepresentableOriginalCoordinate() = runTest(timeout = 15.seconds) {
        val original = FoodFinancialRpcV1Frozen.identity()
        val variants = listOf(original,
            original.copy(branchId = FoodFinancialRpcV1Frozen.id("R2")),
            original.copy(operationId = FoodFinancialRpcV1Frozen.operation(1)),
            original.copy(originalActorId = FoodFinancialRpcV1Frozen.id("B")),
            original.copy(semanticDigestVersion = 2), original.copy(semanticSha256 = "1".repeat(64)))
        for (identity in variants) {
            val request = captureFoodCarrier(FoodFinancialCarrierKind.Lookup, FoodFinancialRpcV1Frozen.bearer(), identity = identity)
                as LocalMasterRpcLookupRequestV1
            val decoded = try { decodeFoodCarrier(FoodFinancialCarrierKind.Lookup, encodeFoodCarrier(request)) as LocalMasterRpcLookupRequestV1 }
            finally { request.destroy(); request.awaitCleanup() }
            try {
                assertEquals(identity, request.identity)
                assertNotSame(identity, request.identity)
                assertEquals(identity, decoded.identity)
                assertEquals(identity.branchId, decoded.identity.branchId)
                assertEquals(identity.originalActorId, decoded.identity.originalActorId)
                assertEquals(identity.operationId, decoded.identity.operationId)
                assertEquals(identity.semanticDigestVersion, decoded.identity.semanticDigestVersion)
                assertEquals(identity.semanticSha256, decoded.identity.semanticSha256)
            } finally { decoded.destroy(); decoded.awaitCleanup() }
        }
        // Carrier preservation does not validate a stored result, complete canonical body or recovery source.
    }

    @Test
    fun ffrc013And025UnavailableConflictAndWriterAbsenceRemainDifferentCarriers() {
        FoodFinancialRpcV1Frozen.fixtures
        val operation = FoodFinancialRpcV1Frozen.operation()
        val actor = FoodFinancialRpcV1Frozen.id("A")
        val cases: List<LocalMasterRpcLookupValueV1> = listOf(
            LocalMasterRpcLookupValueV1.Deferred(operation, DeferredReason.STORAGE_UNCERTAIN),
            LocalMasterRpcLookupValueV1.AbsentAtCurrentWriter(operation),
            LocalMasterRpcLookupValueV1.IdentityConflict(operation),
        )
        val observed = cases.map { outcome ->
            val response = LocalMasterRpcLookupResponseV1(actor, outcome)
            val wire = Json.encodeToString(LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP.response, response)
            Json.decodeFromString(LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP.response, wire)
        }
        observed.forEach { assertEquals(actor, it.requestingActorId) }
        val unavailable = observed[0].value as LocalMasterRpcLookupValueV1.Deferred
        assertEquals(operation, unavailable.operationId)
        assertEquals(DeferredReason.STORAGE_UNCERTAIN, unavailable.reason)
        assertEquals(operation, (observed[1].value as LocalMasterRpcLookupValueV1.AbsentAtCurrentWriter).operationId)
        assertEquals(operation, (observed[2].value as LocalMasterRpcLookupValueV1.IdentityConflict).operationId)
        // These are DATA outcomes. No source qualification, authoritative absence or ACK is demonstrated.
    }
}

private fun assertRepresentationsHideBearer(representations: List<String>) {
    val bytes = FoodFinancialRpcV1Frozen.bearer()
    val canaries = listOf(Base64.getEncoder().encodeToString(bytes),
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }, bytes.joinToString(",", "[", "]"))
    representations.forEach { text ->
        assertFalse(canaries.any { text.contains(it) }, "Diagnostic representation contains bearer contents")
    }
}
