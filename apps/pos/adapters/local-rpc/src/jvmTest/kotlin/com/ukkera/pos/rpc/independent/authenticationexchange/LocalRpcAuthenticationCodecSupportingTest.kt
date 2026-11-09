package com.ukkera.pos.rpc.independent.authenticationexchange

import com.ukkera.pos.rpc.LocalRpcAuthenticationAction
import com.ukkera.pos.rpc.LocalRpcAuthenticationRequest
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponse
import com.ukkera.pos.rpc.LocalRpcAuthenticationResponseSerializer
import com.ukkera.pos.rpc.LocalRpcCurrentIdentityResponse
import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.OpaqueCredential
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen REMP-C01/C02 codec slices only. The real codec and its production shape guard are
 * exercised, but no service, Host callback, native admission or database is installed here.
 * Accordingly, service invocation counts, settled revocation and absence of durable/authority
 * effects remain unbound. Symbolic credentials/display data never qualify a real employee.
 */
class LocalRpcAuthenticationCodecSupportingTest {
    @Test fun rempC01LoginRoundTripKeepsExactPasswordIdentifierAndExplicitConsent() = roundTripRequest("login")
    @Test fun rempC01RefreshRoundTripHasOneBearerAndNoIdentityAuthority() = roundTripRequest("refresh")
    @Test fun rempC01CurrentRoundTripHasOneBearerAndNoIdentityAuthority() = roundTripRequest("current")
    @Test fun rempC01LogoutRoundTripHasOneBearerAndNoIdentityAuthority() = roundTripRequest("logout")

    @Test fun rempC01MissingOrNullExchangeUuidRefusesAtCodecOrShapeGuard() = missingShape("missing_exchange_uuid")
    @Test fun rempC01MalformedExchangeUuidRefusesAtCodecOrShapeGuard() = refusedShape("malformed_exchange_uuid")
    @Test fun rempC01MissingOrNullLoginRefusesAtCodecOrShapeGuard() = missingShape("missing_login")
    @Test fun rempC01MissingOrNullPasswordRefusesAtCodecOrShapeGuard() = missingShape("missing_password")
    @Test fun rempC01MissingOrNullExplicitConsentRefusesAtCodecOrShapeGuard() = missingShape("missing_move_consent")
    @Test fun rempC01LoginBearerConflictRefusesAtCodecOrShapeGuard() = refusedShape("login_bearer_conflict")
    @Test fun rempC01RefreshMissingOrNullBearerRefusesAtCodecOrShapeGuard() = missingShape("refresh_missing_bearer")
    @Test fun rempC01CurrentLoginPasswordConflictRefusesAtCodecOrShapeGuard() = refusedShape("current_login_conflict")
    @Test fun rempC01LogoutMoveConflictRefusesAtCodecOrShapeGuard() = refusedShape("logout_move_conflict")
    @Test fun rempC01UnsupportedBusinessGrantActionRefusesAtCodecOrShapeGuard() = refusedShape("unsupported_action")

    @Test fun rempC02LoginSuccessRoundTripOwnsPairAndViewOnlyForLogin() = roundTripAuthentication(LocalRpcAuthenticationAction.LOGIN)
    @Test fun rempC02RefreshSuccessRoundTripOwnsPairAndViewOnlyForRefresh() = roundTripAuthentication(LocalRpcAuthenticationAction.REFRESH)

    @Test
    fun rempC02CurrentSuccessRoundTripHasDisplayViewAndNoCredentials() {
        val view = RemoteEmployeeAuthenticationFrozen.view()
        val outgoing = LocalRpcAuthenticationResponse.capture(LocalRpcAuthenticationAction.CURRENT, LocalSessionViewResult.Current(view))
        var decoded: LocalRpcAuthenticationResponse? = null
        try {
            val wire = Json.encodeToJsonElement(LocalRpcAuthenticationResponseSerializer, outgoing).jsonObject
            assertEquals(setOf("action", "status", "view"), wire.keys)
            assertEquals("CURRENT", wire.string("action"))
            assertEquals("CURRENT", wire.string("status"))
            val reply = Json.decodeFromJsonElement(LocalRpcAuthenticationResponseSerializer, wire).also { decoded = it }
            assertTrue(reply.matchesAction(LocalRpcAuthenticationAction.CURRENT))
            assertFalse(reply.matchesAction(LocalRpcAuthenticationAction.LOGIN))
            assertRemoteView(view, assertIs<LocalSessionViewResult.Current>(reply.sessionViewResult()).view)
            assertRemoteRedacted(outgoing, reply, view, view.policy)
        } finally { outgoing.destroy(); decoded?.destroy() }
        // CURRENT display data is not a grant or a deadline on the unrelated Client clock.
    }

    @Test
    fun rempC02LogoutSuccessRoundTripPreservesScriptedSettledRevocationOnly() {
        // This scripted settled service value tests mapping only, not an actual revocation transaction.
        val outgoing = LocalRpcAuthenticationResponse.capture(LocalRpcAuthenticationAction.LOGOUT, LocalSignOutResult.Revoked)
        var decoded: LocalRpcAuthenticationResponse? = null
        try {
            val wire = Json.encodeToJsonElement(LocalRpcAuthenticationResponseSerializer, outgoing).jsonObject
            assertEquals(setOf("action", "status"), wire.keys)
            assertEquals("LOGOUT", wire.string("action"))
            assertEquals("REVOKED", wire.string("status"))
            val reply = Json.decodeFromJsonElement(LocalRpcAuthenticationResponseSerializer, wire).also { decoded = it }
            assertTrue(reply.matchesAction(LocalRpcAuthenticationAction.LOGOUT))
            assertEquals(LocalSignOutResult.Revoked, reply.signOutResult())
            assertRemoteRedacted(outgoing, reply)
        } finally { outgoing.destroy(); decoded?.destroy() }
    }

    @Test fun rempC02LoginDeniedStaysDenied() = roundTripFailure(LocalRpcAuthenticationAction.LOGIN, "denied")
    @Test fun rempC02LoginUnavailableStaysUnavailable() = roundTripFailure(LocalRpcAuthenticationAction.LOGIN, "unavailable")
    @Test fun rempC02LoginStorageUncertainStaysUncertain() = roundTripFailure(LocalRpcAuthenticationAction.LOGIN, "storage_uncertain")
    @Test fun rempC02RefreshDeniedStaysDenied() = roundTripFailure(LocalRpcAuthenticationAction.REFRESH, "denied")
    @Test fun rempC02RefreshUnavailableStaysUnavailable() = roundTripFailure(LocalRpcAuthenticationAction.REFRESH, "unavailable")
    @Test fun rempC02RefreshStorageUncertainStaysUncertain() = roundTripFailure(LocalRpcAuthenticationAction.REFRESH, "storage_uncertain")
    @Test fun rempC02CurrentDeniedStaysDenied() = roundTripFailure(LocalRpcAuthenticationAction.CURRENT, "denied")
    @Test fun rempC02CurrentUnavailableStaysUnavailable() = roundTripFailure(LocalRpcAuthenticationAction.CURRENT, "unavailable")
    @Test fun rempC02CurrentStorageUncertainStaysUncertain() = roundTripFailure(LocalRpcAuthenticationAction.CURRENT, "storage_uncertain")
    @Test fun rempC02LogoutDeniedStaysDenied() = roundTripFailure(LocalRpcAuthenticationAction.LOGOUT, "denied")
    @Test fun rempC02LogoutUnavailableStaysUnavailable() = roundTripFailure(LocalRpcAuthenticationAction.LOGOUT, "unavailable")
    @Test fun rempC02LogoutStorageUncertainStaysUncertain() = roundTripFailure(LocalRpcAuthenticationAction.LOGOUT, "storage_uncertain")

    @Test
    fun rempC02SeparateCurrentIdentityProjectionHasExactlyEightFrozenConcepts() {
        val input = RemoteEmployeeAuthenticationFrozen.identity
        val response = LocalRpcCurrentIdentityResponse(input.string("organizationId"), input.string("companyId"),
            input.string("branchId"), input.string("actorId"), input.string("deviceId"), input.string("sessionId"),
            input.number("generation"), input.number("admissionRevision"))
        val wire = Json.encodeToJsonElement(LocalRpcCurrentIdentityResponse.serializer(), response).jsonObject
        val concepts = RemoteEmployeeAuthenticationFrozen.fixtures.getValue("identity_only_result_concepts").jsonArray
            .map { it.jsonPrimitive.content }.map { if (it == "generation") "sessionGeneration" else it }.toSet()
        assertEquals(8, wire.size)
        assertEquals(concepts, wire.keys)
        for (key in concepts - "sessionGeneration" - "admissionRevision") assertEquals(input.string(key), wire.string(key))
        assertEquals(input.number("generation"), wire.number("sessionGeneration"))
        assertEquals(input.number("admissionRevision"), wire.number("admissionRevision"))
        val decoded = Json.decodeFromJsonElement(LocalRpcCurrentIdentityResponse.serializer(), wire)
        assertEquals(wire, Json.encodeToJsonElement(LocalRpcCurrentIdentityResponse.serializer(), decoded).jsonObject)
        assertRemoteRedacted(response, decoded)
        // The spelling maps the same generation concept; this is not genuine current-session disclosure.
    }

    private fun roundTripRequest(id: String) {
        val fixture = RemoteEmployeeAuthenticationFrozen.request(id)
        var original: LocalRpcAuthenticationRequest? = null
        var decoded: LocalRpcAuthenticationRequest? = null
        try {
            val first = Json.decodeFromJsonElement(LocalRpcAuthenticationRequest.serializer(),
                RemoteEmployeeAuthenticationFrozen.requestWire(id)).also { original = it }
            assertTrue(first.validateShape())
            val wire = Json.encodeToJsonElement(LocalRpcAuthenticationRequest.serializer(), first).jsonObject
            val expectedFields = if (id == "login") setOf("exchangeId", "action", "login", "password", "explicitDeviceMove")
                else setOf("exchangeId", "action", "bearer")
            assertEquals(expectedFields, wire.keys)
            val copy = Json.decodeFromJsonElement(LocalRpcAuthenticationRequest.serializer(), wire).also { decoded = it }
            assertTrue(copy.validateShape())
            assertEquals(fixture.string("exchangeId"), copy.exchangeId)
            assertEquals(RemoteEmployeeAuthenticationFrozen.action(id), copy.action)
            if (id == "login") {
                assertEquals(fixture.string("loginIdentifier"), copy.login)
                assertTrue(assertNotNull(copy.password).contentEquals(fixture.string("password").toCharArray()),
                    "Codec changed the exact synthetic password")
                assertNotSame(first.password, copy.password)
                assertEquals(fixture.getValue("deviceMoveConsent").jsonPrimitive.boolean, copy.explicitDeviceMove)
                assertEquals(fixture.getValue("deviceMoveConsent"), wire.getValue("explicitDeviceMove"))
                assertNull(copy.bearer)
            } else {
                val expectedBearer = RemoteEmployeeAuthenticationFrozen.requestBearer(id)
                try { assertTrue(assertNotNull(copy.bearer).contentEquals(expectedBearer), "Codec changed the symbolic bearer carrier") }
                finally { expectedBearer.fill(0) }
                assertNotSame(first.bearer, copy.bearer)
                assertNull(copy.login)
                assertNull(copy.password)
                assertFalse(copy.explicitDeviceMove)
            }
            assertRemoteRedacted(first, copy)
        } finally { original?.destroy(); decoded?.destroy() }
        // Exact fields exclude caller actor/device/branch/connection/peer/permission authority.
        // Unknown JSON extensions have no frozen parsing expectation and are not tested.
    }

    private fun missingShape(id: String) {
        // Each representation has its own decoded instance and cleanup; neither is retried.
        assertRefused(RemoteEmployeeAuthenticationFrozen.refusalWire(id, omitMissing = true))
        assertRefused(RemoteEmployeeAuthenticationFrozen.refusalWire(id))
    }

    private fun refusedShape(id: String) = assertRefused(RemoteEmployeeAuthenticationFrozen.refusalWire(id))

    private fun assertRefused(wire: JsonObject) {
        var decoded: LocalRpcAuthenticationRequest? = null
        val refused = try {
            val request = Json.decodeFromJsonElement(LocalRpcAuthenticationRequest.serializer(), wire).also { decoded = it }
            !request.validateShape()
        } catch (_: SerializationException) { true }
        catch (_: IllegalArgumentException) { true }
        finally { decoded?.destroy() }
        assertTrue(refused, "Malformed frozen request passed both codec and production shape guard")
        // Invoking the real Host to observe zero service calls is a separate unbound obligation.
    }

    private fun roundTripAuthentication(action: LocalRpcAuthenticationAction) {
        val view = RemoteEmployeeAuthenticationFrozen.view()
        val access = RemoteEmployeeAuthenticationFrozen.originalAccess()
        val refresh = RemoteEmployeeAuthenticationFrozen.originalRefresh()
        val sourceTokens = LocalSessionTokens(view, OpaqueCredential(access), OpaqueCredential(refresh))
        var outgoing: LocalRpcAuthenticationResponse? = null
        var decoded: LocalRpcAuthenticationResponse? = null
        var received: LocalSessionTokens? = null
        try {
            val response = LocalRpcAuthenticationResponse.capture(action, LocalAuthenticationResult.Authenticated(sourceTokens))
                .also { outgoing = it }
            assertRemoteCredentialDestroyed(sourceTokens.access)
            assertRemoteCredentialDestroyed(sourceTokens.refresh)
            val wire = Json.encodeToJsonElement(LocalRpcAuthenticationResponseSerializer, response).jsonObject
            assertEquals(setOf("action", "status", "view", "access", "refresh"), wire.keys)
            assertEquals(action.name, wire.string("action"))
            assertEquals("AUTHENTICATED", wire.string("status"))
            val reply = Json.decodeFromJsonElement(LocalRpcAuthenticationResponseSerializer, wire).also { decoded = it }
            assertTrue(reply.matchesAction(action))
            val other = if (action == LocalRpcAuthenticationAction.LOGIN) LocalRpcAuthenticationAction.REFRESH
                else LocalRpcAuthenticationAction.LOGIN
            assertFalse(reply.matchesAction(other), "An explicit response action matched another request action")
            val tokens = assertIs<LocalAuthenticationResult.Authenticated>(reply.authenticationResult()).tokens.also { received = it }
            assertRemoteView(view, tokens.view)
            assertTrue(tokens.access.useBytes { it.contentEquals(access) }, "Access carrier changed")
            assertTrue(tokens.refresh.useBytes { it.contentEquals(refresh) }, "Refresh carrier changed")
            assertRemoteRedacted(response, reply, sourceTokens, tokens, tokens.access, tokens.refresh, tokens.view, tokens.view.policy)
        } finally {
            sourceTokens.destroy(); received?.destroy(); outgoing?.destroy(); decoded?.destroy()
            access.fill(0); refresh.fill(0)
        }
    }

    private fun roundTripFailure(action: LocalRpcAuthenticationAction, outcome: String) {
        assertTrue(RemoteEmployeeAuthenticationFrozen.fixtures.getValue("service_outcomes").jsonArray
            .any { it.jsonPrimitive.content == outcome })
        val outgoing = when (action) {
            LocalRpcAuthenticationAction.LOGIN, LocalRpcAuthenticationAction.REFRESH -> LocalRpcAuthenticationResponse.capture(action,
                when (outcome) {
                    "denied" -> LocalAuthenticationResult.Denied(LocalAuthenticationDenial.AUTHENTICATION_REFUSED)
                    "unavailable" -> LocalAuthenticationResult.Unavailable
                    else -> LocalAuthenticationResult.StorageUncertain
                })
            LocalRpcAuthenticationAction.CURRENT -> LocalRpcAuthenticationResponse.capture(action, when (outcome) {
                "denied" -> LocalSessionViewResult.Denied
                "unavailable" -> LocalSessionViewResult.Unavailable
                else -> LocalSessionViewResult.StorageUncertain
            })
            LocalRpcAuthenticationAction.LOGOUT -> LocalRpcAuthenticationResponse.capture(action, when (outcome) {
                "denied" -> LocalSignOutResult.Denied
                "unavailable" -> LocalSignOutResult.Unavailable
                else -> LocalSignOutResult.StorageUncertain
            })
        }
        var decoded: LocalRpcAuthenticationResponse? = null
        try {
            val wire = Json.encodeToJsonElement(LocalRpcAuthenticationResponseSerializer, outgoing).jsonObject
            val hasDenial = outcome == "denied" && action in setOf(LocalRpcAuthenticationAction.LOGIN, LocalRpcAuthenticationAction.REFRESH)
            assertEquals(if (hasDenial) setOf("action", "status", "denial") else setOf("action", "status"), wire.keys)
            assertEquals(action.name, wire.string("action"))
            assertEquals(outcome.uppercase(), wire.string("status"))
            val reply = Json.decodeFromJsonElement(LocalRpcAuthenticationResponseSerializer, wire).also { decoded = it }
            assertTrue(reply.matchesAction(action))
            when (action) {
                LocalRpcAuthenticationAction.LOGIN, LocalRpcAuthenticationAction.REFRESH -> when (outcome) {
                    "denied" -> assertEquals(LocalAuthenticationDenial.AUTHENTICATION_REFUSED,
                        assertIs<LocalAuthenticationResult.Denied>(reply.authenticationResult()).reason)
                    "unavailable" -> assertEquals(LocalAuthenticationResult.Unavailable, reply.authenticationResult())
                    else -> assertEquals(LocalAuthenticationResult.StorageUncertain, reply.authenticationResult())
                }
                LocalRpcAuthenticationAction.CURRENT -> assertEquals(when (outcome) {
                    "denied" -> LocalSessionViewResult.Denied
                    "unavailable" -> LocalSessionViewResult.Unavailable
                    else -> LocalSessionViewResult.StorageUncertain
                }, reply.sessionViewResult())
                LocalRpcAuthenticationAction.LOGOUT -> assertEquals(when (outcome) {
                    "denied" -> LocalSignOutResult.Denied
                    "unavailable" -> LocalSignOutResult.Unavailable
                    else -> LocalSignOutResult.StorageUncertain
                }, reply.signOutResult())
            }
            assertRemoteRedacted(outgoing, reply)
        } finally { outgoing.destroy(); decoded?.destroy() }
    }
}
