@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.physicalsession

import com.ukkera.pos.rpc.independent.identityv1.IdentityBarrier
import com.ukkera.pos.rpc.independent.identityv1.string
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.ConnectionState
import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import dev.p2pkit.rpc.RpcAuthorizationOperation
import dev.p2pkit.rpc.RpcPhysicalSession
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Frozen PHS L1 slices only: synthetic SDK data, controlled principal/native-currentness inputs. */
class LocalRpcPhysicalSessionIdentitySlicesTest {
    @Test
    fun phs001ExactSyntheticCallbackDataRetainsOriginalIdentityAndScope() = runTest {
        physicalSessionFixture { f ->
            val context = f.authenticationData()
            val binding = assertNotNull(f.bind(context))
            val first = assertNotNull(f.scope(binding, context))
            val second = assertNotNull(f.scope(binding, context))
            assertOriginalPhysicalScope(first, f)
            assertOriginalPhysicalScope(second, f)
            assertSame(first.session, second.session)
            assertSame(f.sdkA.capability, context.physicalSession)
            assertSame(f.sdkA.capability.closure, binding.physicalClosure)
            assertEquals(PhysicalSessionFrozen.sdkGeneration, context.physicalSession.generation)
            val authorization = f.authorizationData(first)
            assertSame(f.sdkA.capability, authorization.originalPhysicalSession)
            assertSame(f.sdkA.capability, authorization.physicalSession)
            assertOriginalPhysicalPrincipal(assertNotNull(binding.currentOriginalPrincipal(authorization)))
            val handler = f.handlerData(first, f.sdkA.capability)
            assertSame(f.sdkA.capability, handler.physicalSession)
            assertOriginalPhysicalPrincipal(assertNotNull(binding.currentOriginalPrincipal(handler)))
            assertTrue(f.provenance.captures.isNotEmpty())
            assertTrue(f.provenance.captures.all { it === f.sdkA.capability })
            f.callerA.assertIntact()
            f.verifier.assertOriginalLabel()
            f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test
    fun phs002AbsentNativeOrConflictingPeerDataRefusesPhysicalBinding() = runTest {
        val rows: List<Pair<String, (PhysicalSessionUnitFixture) -> Unit>> = listOf(
            "no-trusted-peer" to { f -> f.provenance.originalPeer = null },
            "wrong-fingerprint" to { f ->
                f.provenance.originalPeer = LocalAuthenticatedPeer(f.nativeA.connectionId, f.nativeA.scope,
                    f.nativeA.deviceId, f.nativeA.publicKeyIdentifier,
                    com.ukkera.pos.rpc.independent.identityv1.IdentityFrozen
                        .fingerprint("SYNTHETIC-KEY-PHS-OTHER").value, f.nativeLifetimeA::current)
            },
            "mixed-lifetimes-same-key" to { f -> f.provenance.originalPeer = f.nativeB },
            "body-or-discovery-only" to { f -> f.provenance.originalPeer = null },
        )
        val frozenRows = PhysicalSessionFrozen.fixtures.getValue("binding_refusal_variants").jsonArray
            .map { it.jsonObject.string("id") }
        for ((id, change) in rows) physicalSessionFixture { f ->
            assertTrue(id in frozenRows)
            change(f)
            assertNull(f.bind(), id)
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        physicalSessionFixture { f ->
            val other = PeerIdentity(PeerId(PhysicalSessionFrozen.original.string("device")),
                com.ukkera.pos.rpc.independent.identityv1.IdentityFrozen
                    .fingerprint("SYNTHETIC-KEY-PHS-OTHER"))
            assertNull(f.bind(f.authenticationData(peer = other)))
            f.callerA.assertIntact()
        }
        // Null lifetime is not a typed RpcAuthenticationContext. Descriptor/legacy ineligibility
        // is checked separately. Same-key native association and retained-owner erasure stay unbound.
    }

    @Test
    fun phs003EqualPeerAndGenerationSuccessorDataCannotAdoptOriginalBinding() = runTest {
        physicalSessionFixture { f ->
            assertSame(f.sdkA.peer, f.sdkB.peer)
            assertEquals(f.sdkA.capability.generation, f.sdkB.capability.generation)
            assertFalse(f.sdkA.capability === f.sdkB.capability)
            val binding = assertNotNull(f.bind())
            val original = assertNotNull(f.scope(binding))
            assertNull(f.scope(binding, f.authenticationData(f.sdkB.capability)))
            assertNull(binding.currentOriginalPrincipal(f.handlerData(original, f.sdkB.capability)))
            for (operation in listOf(RpcAuthorizationOperation.Execute, RpcAuthorizationOperation.Success,
                RpcAuthorizationOperation.Status)) {
                assertNull(binding.currentOriginalPrincipal(f.authorizationData(original,
                    physical = f.sdkB.capability, operation = operation)), operation.name)
            }
            assertOriginalPhysicalScope(assertNotNull(f.scope(binding)), f)
            assertOriginalPhysicalPrincipal(assertNotNull(binding.currentOriginalPrincipal(
                f.authorizationData(original))))
            f.verifier.assertOriginalLabel()
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        // No genuine reconnect, request/control dispatch or private payload is exercised.
    }

    @Test
    fun phs004UnscopedMissingPhysicalAndLegacyEntriesAreIneligibleForPhysicalLogin() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val original = assertNotNull(f.scope(binding))
            assertNull(binding.currentOriginalPrincipal(f.handlerData()))
            assertNull(binding.currentOriginalPrincipal(f.handlerData(original)))
            assertNull(binding.authorizationScope(f.peer, PhysicalSessionFrozen.resource))
            val repeatedLabels = sameIdentifierDifferentSdkSession(original)
            assertEquals(original.session.principal, repeatedLabels.session.principal)
            assertEquals(original.session.sessionId, repeatedLabels.session.sessionId)
            assertNull(binding.currentOriginalPrincipal(f.handlerData(repeatedLabels, f.sdkA.capability)))
            assertNull(binding.currentOriginalPrincipal(f.authorizationData(repeatedLabels)))

            val legacy = assertNotNull(f.legacyBind())
            val legacyScope = assertNotNull(legacy.authorizationScope(f.peer, PhysicalSessionFrozen.resource))
            assertNull(legacy.physicalClosure)
            assertNull(f.scope(legacy))
            assertNull(legacy.currentOriginalPrincipal(f.handlerData(legacyScope, f.sdkA.capability)))
            assertNull(binding.currentOriginalPrincipal(f.handlerData(legacyScope, f.sdkA.capability)))
            assertNull(binding.currentOriginalPrincipal(f.authorizationData(legacyScope)))
            assertOriginalPhysicalScope(assertNotNull(f.scope(binding)), f)
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        // Legacy identity-only authorization behavior is not claimed as physical admission.
    }

    @Test
    fun phs004OriginalAndOperationCapabilityAxesRefuseMixedContextData() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val original = assertNotNull(f.scope(binding))
            val mismatches: List<Pair<RpcPhysicalSession?, RpcPhysicalSession?>> = listOf(
                null to f.sdkA.capability,
                f.sdkB.capability to f.sdkA.capability,
                f.sdkA.capability to f.sdkB.capability,
                null to null,
            )
            for (operation in listOf(RpcAuthorizationOperation.Execute, RpcAuthorizationOperation.Success,
                RpcAuthorizationOperation.Status)) for ((origin, destination) in mismatches) {
                val context = f.authorizationData(original, origin, destination, operation)
                assertSame(origin, context.originalPhysicalSession)
                assertSame(destination, context.physicalSession)
                assertNull(binding.currentOriginalPrincipal(context))
            }
            assertOriginalPhysicalPrincipal(assertNotNull(binding.currentOriginalPrincipal(
                f.authorizationData(original))))
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test
    fun phs005CapturedOriginalCredentialSurvivesCallerStorageEdits() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val original = assertNotNull(f.scope(binding))
            f.callerA.substituteCaller(f.callerB)
            val editedCallerBytes = f.callerA.bytes.copyOf()
            try {
                val principal = assertNotNull(binding.currentOriginalPrincipal(f.authorizationData(original)))
                assertOriginalPhysicalPrincipal(principal)
                assertOriginalPhysicalScope(assertNotNull(f.scope(binding)), f)
                f.verifier.assertOriginalLabel()
                f.verifier.assertReleasedSourceCopies()
                assertPhysicalRepresentationsRedacted(f, listOf(binding.toString(), original.toString(),
                    original.session.toString(), f.authenticationData().toString(), f.credentials.toString()))
                binding.retire()
                assertFalse(original.session.isActive)
                assertNull(f.scope(binding))
                assertTrue(f.callerA.bytes.contentEquals(editedCallerBytes), "Retirement changed caller storage")
                f.callerB.assertIntact()
            } finally { editedCallerBytes.fill(0) }
        }
        // Only caller and source-disposable ownership are observable; private retained erasure is unbound.
    }

    @Test
    fun phs006CurrentPrincipalNullPurposeAndOriginalAxesAreRechecked() = runTest {
        val original = PhysicalSessionFrozen.originalIdentity()
        val changes: Map<String, (VerifiedLocalIdentity) -> VerifiedLocalIdentity> = mapOf(
            "wrong-purpose" to { value -> value.copy(purpose = AuthenticationPurpose.SERVICE) },
            "employee" to { value -> value.copy(actorId = PhysicalSessionFrozen.id("E-PHS-B")) },
            "session" to { value -> value.copy(sessionId = PhysicalSessionFrozen.id("S-A1")) },
            "device" to { value -> value.copy(deviceId = PhysicalSessionFrozen.id("D-PHS-2")) },
            "branch" to { value -> value.copy(scope = value.scope.copy(
                branchId = PhysicalSessionFrozen.id("B-PHS-2"))) },
            "pos-generation" to { value -> value.copy(sessionGeneration = SessionGeneration(4)) },
            "admission" to { value -> value.copy(admissionRevision = Revision(12)) },
        )
        for (value in PhysicalSessionFrozen.fixtures.getValue("principal_refusal_variants").jsonArray) {
            val id = value.jsonObject.string("id")
            physicalSessionFixture { f ->
                val binding = assertNotNull(f.bind())
                val captured = assertNotNull(f.scope(binding))
                assertOriginalPhysicalPrincipal(assertNotNull(binding.currentOriginalPrincipal(
                    f.authorizationData(captured))))
                when (id) {
                    "null", "revoked", "disabled" -> f.verifier.identity = null
                    "expired" -> f.verifier.expiry = f.clock.second
                    else -> f.verifier.identity = changes.getValue(id)(original)
                }
                assertNull(f.scope(binding), id)
                assertNull(binding.currentOriginalPrincipal(f.authorizationData(captured)), id)
                assertNull(binding.currentOriginalPrincipal(f.handlerData(captured, f.sdkA.capability)), id)
                assertEquals(original.actorId.value, captured.session.principal)
                assertEquals(original.sessionId.value, captured.session.sessionId)
                f.verifier.assertOriginalLabel()
                f.callerA.assertIntact()
                f.verifier.assertReleasedSourceCopies()
            }
        }
        // Revoked/disabled/expired are scheduled verifier refusals, not production registry or expiry proof.
    }

    @Test
    fun phs007InvalidRepresentableBoundsAndControlledCurrentnessRefuse() = runTest {
        for (bound in listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE)) physicalSessionFixture { f ->
            assertNull(f.bind(lifetime = bound))
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        val changes: List<(PhysicalSessionUnitFixture) -> Unit> = listOf(
            { f -> f.provenance.originalPeer = null },
            { f -> f.provenance.originalPeer = LocalAuthenticatedPeer(f.nativeA.connectionId, f.nativeA.scope,
                PhysicalSessionFrozen.id("D-PHS-2"), f.nativeA.publicKeyIdentifier,
                f.nativeA.publicFingerprint, f.nativeLifetimeA::current) },
            { f -> f.provenance.originalPeer = f.nativeB },
            { f -> f.nativeLifetimeA.end() },
        )
        for (change in changes) physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            assertOriginalPhysicalScope(assertNotNull(f.scope(binding)), f)
            change(f)
            assertNull(f.scope(binding))
            assertNull(f.scope(binding))
            f.callerA.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        // No absent-duration overload, separate native qualification flag or legitimate elapsed-clock
        // seam is exposed here. The frozen 19/20 and 29/30-second expiry assertions remain unbound.
    }

    @Test
    fun phs007DisconnectDuringSourceCheckSuppressesPublication() = runTest {
        for (authorization in listOf(false, true)) for (sdkDisconnect in listOf(false, true))
            physicalSessionFixture { f ->
                val binding = if (authorization) assertNotNull(f.bind()) else null
                val barrier = IdentityBarrier()
                f.verifier.barrier = barrier
                val work = async { if (binding == null) f.bind() else f.scope(binding) }
                runCurrent()
                assertTrue(barrier.entered.isCompleted)
                if (sdkDisconnect) f.sdkA.transportState.value = ConnectionState.Closed else f.nativeLifetimeA.end()
                barrier.release.complete(Unit)
                assertNull(work.await())
                f.callerA.assertIntact()
                f.verifier.assertReleasedSourceCopies()
            }
        // Synthetic state/currentness loss only; no physical disconnect or native continuity run.
    }
}
