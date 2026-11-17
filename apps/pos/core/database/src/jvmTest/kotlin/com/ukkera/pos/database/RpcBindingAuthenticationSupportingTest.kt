package com.ukkera.pos.database

import com.ukkera.pos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** Self-authored, NOT independent. Real protected Room current-session authentication
 * through the grouped endpoint; controlled keys/peer/time, no native/TCP qualification.
 * Expectations v1 were recorded before first execution. No frozen fixture is changed. */
class RpcBindingAuthenticationSupportingTest {
    @Test fun oneProtectedCurrentOperationReturnsExactIdentityAndBoundedLifetimeWithoutWrites() = runBlocking {
        fixture("identity") { f ->
            val credentials = f.access("A")
            val before = f.snapshot("before-binding").json()
            val proof = assertIs<RoomRpcBindingResult.Authenticated>(f.endpoint.authenticateRpcBinding(credentials))
            val identity = proof.identity.principal
            val view = f.tokens("A").view
            assertEquals(view.actorId, identity.actorId)
            assertEquals(view.scope, identity.scope)
            assertEquals(view.deviceId, identity.deviceId)
            assertEquals(view.sessionId, identity.sessionId)
            assertEquals(view.generation, identity.sessionGeneration)
            assertEquals(AuthenticationPurpose.INTERACTIVE, identity.authenticationPurpose)
            assertTrue(assertNotNull(proof.identity.remainingMillis()) in 1..minOf(
                view.accessExpiresAt.elapsedMillis, view.workExpiresAt.elapsedMillis))
            assertEquals(before, f.snapshot("after-binding").json())
        }
    }

    @Test fun wrongSecretRefreshAndForeignPhysicalConnectionRemainDenied() = runBlocking {
        fixture("credentials") { f ->
            val credentials = f.access("A")
            val altered = credentials.bearer.useBytes { bytes ->
                val copy = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                try { OpaqueCredential(copy) } finally { copy.fill(0) }
            }
            try {
                val before = f.snapshot("before-denials").json()
                for (value in listOf(LocalGatewayCredentials(credentials.connectionId, altered), f.refresh("A"), f.access("A", "B")))
                    assertEquals(RoomRpcBindingResult.Denied, f.endpoint.authenticateRpcBinding(value))
                assertEquals(before, f.snapshot("after-denials").json())
                assertIs<RoomRpcBindingResult.Authenticated>(f.endpoint.authenticateRpcBinding(credentials))
            } finally { altered.destroy() }
        }
    }

    @Test fun onlyExactCredentialReferenceAndOriginalAwaitingCallbackCanConsumeOnce() = runBlocking {
        fixture("consume") { f ->
            val credentials = f.access("A")
            val actual = assertIs<RoomRpcBindingResult.Authenticated>(f.endpoint.authenticateRpcBinding(credentials))
            val proof = LocalMasterRpcBindingAuthentication(credentials, actual.identity, currentCoroutineContext())
            assertNull(proof.consume(LocalGatewayCredentials(credentials.connectionId, credentials.bearer)))
            coroutineScope { assertNull(async { proof.consume(credentials) }.await()) }
            val consumed = assertNotNull(proof.consume(credentials))
            assertEquals(f.tokens("A").view.sessionId, consumed.first.sessionId)
            assertTrue(consumed.second > 0)
            assertNull(proof.consume(credentials))
        }
    }

    @Test fun actualRevocationAndCancelledOwningCallbackCannotPublishNewBinding() = runBlocking {
        fixture("revocation") { f ->
            val credentials = f.access("A")
            val actual = assertIs<RoomRpcBindingResult.Authenticated>(f.endpoint.authenticateRpcBinding(credentials))
            val cancelled = Job().also { it.cancel() }
            val proof = LocalMasterRpcBindingAuthentication(credentials, actual.identity, currentCoroutineContext() + cancelled)
            assertFailsWith<CancellationException> { proof.consume(credentials) }
            assertEquals(LocalSignOutResult.Revoked, f.endpoint.logout(credentials))
            val before = f.snapshot("before-revoked-binding").json()
            repeat(2) { assertEquals(RoomRpcBindingResult.Denied, f.endpoint.authenticateRpcBinding(credentials)) }
            assertEquals(before, f.snapshot("after-revoked-binding").json())
        }
    }

    private suspend fun fixture(name: String, block: suspend (AuthV1Fixture) -> Unit) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first {
            Files.isRegularFile(it.resolve("qa/pos/freezes/auth-v1/cases.json"))
        }
        val design = Json.parseToJsonElement(Files.readString(repository.resolve("qa/pos/freezes/auth-v1/cases.json"))).jsonObject
        val wiring = Json.parseToJsonElement(Files.readString(repository.resolve("qa/pos/wiring/auth-v1/wiring-v3.json"))).jsonObject
        val root = Files.createTempDirectory(Path.of("/var/tmp"), "pos-rpc-binding-supporting-")
        AuthV1Journal(root, UUID.randomUUID().toString().replace("-", ""), wiring).use { journal ->
            val fixture = AuthV1Fixture("binding-$name", root.resolve("master.db"), design, journal)
            try { fixture.start(); block(fixture) } finally { fixture.dispose() }
        }
    }
}
