@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.rpc.independent.authenticationcurrentrenewalv2

import com.ukkera.pos.rpc.ClientAuthenticationCodec
import com.ukkera.pos.rpc.LocalRpcAuthenticationAction
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponse
import com.ukkera.pos.rpc.authenticationCurrentProcedure
import com.ukkera.pos.rpc.authenticationExchangeProcedure
import com.ukkera.pos.security.LocalSessionViewResult
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Actual descriptor, Client codec and decoded-request custody slices of S001/S008/S010.
 * Exact frozen three-byte sentinels are codec-only inputs, not valid issued credentials.
 * No Host registration, native work, scope authority, quota release or private Current view is
 * fabricated. In particular, v2 action rejection/missing-route dispatch and all genuine cases
 * remain unwired; a selected descriptor does not qualify their invocation/refusal assertions.
 */
class AuthenticationCurrentRenewalV2CodecSupportingTest {
    @Test
    fun acr2S001ActualClientSelectsCurrentV2AndCredentialActionsV1() {
        val ids = setOf("ACR2-S001-V1-LOGIN", "ACR2-S001-V1-REFRESH", "ACR2-S001-V1-LOGOUT", "ACR2-S001-V2-CURRENT")
        AuthenticationCurrentRenewalV2Frozen.rows("routing").filter { it.id in ids }.forEach { row ->
            val request = AuthenticationCurrentRenewalV2Frozen.decodedRouteRequest(row)
            val codec = ClientAuthenticationCodec(request)
            try {
                val protocol = row.inputs.acr2String("protocol")
                assertEquals(protocol.substringBeforeLast('/'), codec.procedure.name, row.id)
                assertEquals(protocol.substringAfterLast('/').toInt(), codec.procedure.version, row.id)
                assertEquals(RpcRetrySafety.NeverReinvoke, codec.procedure.retrySafety, row.id)
            } finally { codec.destroy(); request.destroy() }
        }
        // V1 CURRENT still has its actual descriptor; the new Client explicitly selects v2.
        // No assertion here invents a successful Host route or missing-v2 fallback execution.
    }

    @Test
    fun acr2S001CurrentV2PreservesActualV1SchemasLimitsAndControlledResponseFields() {
        val row = AuthenticationCurrentRenewalV2Frozen.row("routing", "ACR2-S001-V2-CURRENT")
        val v1 = authenticationExchangeProcedure
        val v2 = authenticationCurrentProcedure
        assertEquals(v1.request.descriptor, v2.request.descriptor, row.id)
        assertEquals(v1.response.descriptor, v2.response.descriptor, row.id)
        assertEquals(v1.applicationError.descriptor, v2.applicationError.descriptor, row.id)
        assertEquals(v1.requestLimitBytes, v2.requestLimitBytes, row.id)
        assertEquals(v1.responseLimitBytes, v2.responseLimitBytes, row.id)
        assertEquals(v1.errorLimitBytes, v2.errorLimitBytes, row.id)
        assertEquals(RpcRetrySafety.NeverReinvoke, v1.retrySafety, row.id)
        assertEquals(v1.retrySafety, v2.retrySafety, row.id)

        val request = AuthenticationCurrentRenewalV2Frozen.decodedRouteRequest(row)
        try {
            assertEquals(Json.encodeToJsonElement(v1.request, request), Json.encodeToJsonElement(v2.request, request), row.id)
        } finally { request.destroy() }

        val outcomes = mapOf(
            "ACR2-S005-CLEAN-DENIED-LOST-DELIVERY" to LocalSessionViewResult.Denied,
            "ACR2-S005-CLEAN-BUSY-LOST-DELIVERY" to LocalSessionViewResult.Busy,
        )
        outcomes.forEach { (id, result) ->
            val outcome = AuthenticationCurrentRenewalV2Frozen.row("hold_and_cleanup", id)
            val response1 = LocalRpcAuthenticationResponse.capture(LocalRpcAuthenticationAction.CURRENT, result)
            val response2 = LocalRpcAuthenticationResponse.capture(LocalRpcAuthenticationAction.CURRENT, result)
            try {
                assertEquals(
                    Json.encodeToJsonElement(v1.response, response1),
                    Json.encodeToJsonElement(v2.response, response2),
                    "${row.id}: ${outcome.inputs.acr2String("nominal_service_outcome")}",
                )
            } finally { response1.destroy(); response2.destroy() }
        }
        // Controlled service envelopes do not provide native settlement or private-view proof.
    }

    @Test
    fun acr2S008BeforeAcceptanceActualClientDisposalClearsOnlyItsOwnedRequest() = runTest(timeout = 5.seconds) {
        val row = AuthenticationCurrentRenewalV2Frozen.row("buffer_and_cancellation_schedules", "ACR2-S008-CANCEL-BEFORE-ACCEPTANCE")
        val caller = row.inputs.acr2Bytes("caller_buffer_before")
        val request = AuthenticationCurrentRenewalV2Frozen.decodedCurrentRequest(row.inputs.acr2Bytes("owned_decoded_request_before"))
        val owned = checkNotNull(request.bearer)
        val codec = ClientAuthenticationCodec(request)
        try {
            assertContentEquals(row.inputs.acr2Bytes("owned_decoded_request_before"), owned, row.id)
            assertNotSame(caller, owned, row.id)
            codec.destroy()
            request.awaitCleanup()
            assertTrue(request.isDestroyed, "${row.id}: cleanup completion was not published")
            assertContentEquals(row.expected.acr2Bytes("owned_decoded_request_after_confirmed_cleanup"), owned, row.id)
            assertContentEquals(row.expected.acr2Bytes("caller_buffer_after"), caller, row.id)
            assertFalse(request.claimForCallback(), "${row.id}: disposed request custody reopened")
        } finally { codec.destroy(); request.destroy() }
        // This executes terminal Client custody, not cancellation of accepted endpoint work.
    }

    @Test
    fun acr2S010ActualDecodedInputsRetainLiveClaimsAndClearEachReleasedCopySeparately() = runTest(timeout = 5.seconds) {
        AuthenticationCurrentRenewalV2Frozen.rows("pre_handler_native_and_decoded_input_overlaps").forEach { row ->
            val input = row.inputs
            val expected = row.expected
            val caller = input.acr2Bytes("caller_buffer_before")
            val first = AuthenticationCurrentRenewalV2Frozen.decodedCurrentRequest(input.acr2Bytes("owned_decoded_input_1_before"))
            val second = AuthenticationCurrentRenewalV2Frozen.decodedCurrentRequest(input.acr2Bytes("owned_decoded_input_2_before"))
            val firstBuffer = checkNotNull(first.bearer)
            val secondBuffer = checkNotNull(second.bearer)
            try {
                assertNotSame(first, second, row.id)
                assertNotSame(firstBuffer, secondBuffer, row.id)
                assertNotSame(caller, firstBuffer, row.id)
                assertContentEquals(input.acr2Bytes("owned_decoded_input_1_before"), firstBuffer, row.id)
                assertContentEquals(input.acr2Bytes("owned_decoded_input_2_before"), secondBuffer, row.id)

                if (input.acr2Int("live_decoded_input_claimants") != 0) {
                    assertTrue(first.claimForCallback(), row.id)
                    first.destroyIfUnclaimed()
                    assertFalse(first.isDestroyed, "${row.id}: expiry cleared a claimed input")
                    assertContentEquals(expected.acr2Bytes("live_claimed_owned_decoded_input_1_during_overlap"), firstBuffer, row.id)
                }
                if (row.id == "ACR2-S010-PRE-HANDLER-CLAIMED-INPUT") {
                    second.destroyIfUnclaimed()
                    second.awaitCleanup()
                    assertTrue(second.isDestroyed, row.id)
                    assertContentEquals(expected.acr2Bytes("independently_released_and_cleared_owned_decoded_input_2"), secondBuffer, row.id)
                    assertContentEquals(expected.acr2Bytes("live_claimed_owned_decoded_input_1_during_overlap"), firstBuffer, row.id)
                    assertFalse(first.isDestroyed, row.id)
                }
                if (row.id == "ACR2-S010-PRE-HANDLER-UNCLEARED-COPY") {
                    first.destroyIfUnclaimed()
                    first.awaitCleanup()
                    assertTrue(first.isDestroyed, row.id)
                    assertContentEquals(expected.acr2Bytes("owned_decoded_input_1_after_its_confirmed_cleanup"), firstBuffer, row.id)
                    assertFalse(second.isDestroyed, row.id)
                    assertContentEquals(expected.acr2Bytes("owned_decoded_input_2_awaiting_cleanup"), secondBuffer, row.id)
                }
                if (input.acr2Boolean("all_owned_decoded_inputs_and_private_views_cleared")) {
                    first.destroyAndAwaitCleanup()
                    second.destroyAndAwaitCleanup()
                    assertTrue(first.isDestroyed, row.id)
                    assertTrue(second.isDestroyed, row.id)
                    assertContentEquals(expected.acr2Bytes("owned_decoded_input_1_after_confirmed_cleanup"), firstBuffer, row.id)
                    assertContentEquals(expected.acr2Bytes("owned_decoded_input_2_after_confirmed_cleanup"), secondBuffer, row.id)
                }
                assertContentEquals(expected.acr2Bytes("caller_buffer_after"), caller, row.id)
            } finally { first.destroy(); second.destroy() }
        }
        // No native callback/check is accepted here. The request's completed-wipe witness is
        // checked after actual cleanup, without inventing a concurrent native/decoder schedule.
        // Private view bytes, aggregate Host retention and replacement admission stay unwired.
    }
}
