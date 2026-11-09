package com.ukkera.pos.rpc.independent.identityv1

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalGatewayCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Actual common binding and public official SDK scope/session; controlled sources only. */
class LocalRpcIdentityCreationSlicesTest {
    @Test
    fun rpcid001OriginalCredentialAndSessionAreRecheckedForEachScopeAttempt() = runTest {
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val first = assertNotNull(f.scope(binding))
            val second = assertNotNull(f.scope(binding))
            assertIdentityScope(first, f)
            assertIdentityScope(second, f)
            assertSame(first.session, second.session)
            assertEquals(listOf("CRED-A", "CRED-A", "CRED-A"), f.verifier.observations.map { it.label })
            assertTrue(f.verifier.observations.all { it.connection == f.connection })
            f.a.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
    }

    @Test
    fun rpcid002ScheduledAuthoritativeRefusalsAndRepresentableNoninteractivePurposesRefuseBothGates() = runTest {
        // These are scheduled verifier refusal rows, not actual registry/revocation integrations.
        val refusalRows = listOf("null authoritative principal", "not currently verified", "session revoked",
            "employee disabled", "session expired", "session/admission missing")
        for (row in refusalRows) {
            identityFixture { f ->
                f.verifier.identity = null
                assertNull(f.bind(), row)
                f.a.assertIntact()
                f.verifier.assertReleasedSourceCopies()
            }
            identityFixture { f ->
                val binding = assertNotNull(f.bind())
                f.verifier.identity = null
                assertNull(f.scope(binding), row)
                f.verifier.assertOriginalLabel()
                f.a.assertIntact()
                f.verifier.assertReleasedSourceCopies()
            }
        }
        identityFixture { f ->
            assertNull(f.bind(credentials = LocalGatewayCredentials(f.connection, f.b.bearer)), "Invalid credential")
            f.a.assertIntact(); f.b.assertIntact()
            f.verifier.assertReleasedSourceCopies()
        }
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            f.verifier.acceptedLabel = "CRED-B"
            assertNull(f.scope(binding), "Original credential is no longer verified")
            f.verifier.assertOriginalLabel()
        }
        for (purpose in listOf(AuthenticationPurpose.PRIVILEGED_APPROVAL, AuthenticationPurpose.PREPARED_LOCAL_RECOVERY,
            AuthenticationPurpose.SERVICE)) {
            identityFixture { f ->
                f.verifier.identity = IdentityFrozen.originalIdentity().copy(purpose = purpose)
                assertNull(f.bind())
                f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
            }
            identityFixture { f ->
                val binding = assertNotNull(f.bind())
                f.verifier.identity = IdentityFrozen.originalIdentity().copy(purpose = purpose)
                assertNull(f.scope(binding))
                f.verifier.assertOriginalLabel()
            }
        }
        // Missing typed credential/purpose and specific central/service identities remain unbound.
    }

    @Test
    fun rpcid004SuccessorsUseFreshOwnersAndCannotAdoptRetiredOriginalBinding() = runTest {
        for (value in IdentityFrozen.identity.getValue("successors").jsonArray) {
            val successor = IdentityFrozen.successor(value.jsonPrimitive.content)
            val device = value.jsonPrimitive.content.substringAfter(" on ")
            identityFixture { old ->
                val original = assertNotNull(old.bind())
                val originalScope = assertNotNull(old.scope(original))
                old.verifier.identity = successor
                assertNull(old.scope(original), "A current successor cannot adopt the original")
                old.verifier.assertOriginalLabel()
                original.retire()
                assertFalse(originalScope.session.isActive)
                identityFixture(IdentityUnitFixture(identity = successor, deviceLabel = device,
                    connectionLabel = if (device == "DEVICE-A") "CONNECTION-A2" else "CONNECTION-B",
                    fingerprintLabel = if (device == "DEVICE-A") "FP-A" else "FP-B", credentialLabel = "CRED-B")) { fresh ->
                    val freshBinding = assertNotNull(fresh.bind())
                    val freshScope = assertNotNull(fresh.scope(freshBinding))
                    assertIdentityScope(freshScope, fresh, successor)
                    assertNotSame(originalScope.session, freshScope.session)
                    fresh.verifier.assertOriginalLabel("CRED-B")
                    assertNull(old.scope(original))
                }
                old.verifier.identity = IdentityFrozen.originalIdentity()
                assertNull(old.scope(original), "Restoring descriptions cannot revive a retired owner")
                old.a.assertIntact()
            }
        }
    }

    @Test
    fun rpcid005PrincipalDeadlineRefusesAtAndAfterTheExactFrozenSecond() = runTest {
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val clock = IdentityFrozen.identity.getValue("clock").jsonObject
            f.clock.second = clock.number("before_principal_expiry")
            assertIdentityScope(assertNotNull(f.scope(binding)), f)
            f.clock.second = clock.number("at_principal_expiry")
            assertNull(f.scope(binding))
            f.clock.second = clock.number("after_principal_expiry")
            assertNull(f.scope(binding))
            f.verifier.assertOriginalLabel()
        }
        // Scheduled authority-clock expiry is not a real native/production session expiry result.
    }

    @Test
    fun rpcid006RepresentableInvalidSdkLifetimesRefuseAndNativeDeadlineIsIndependent() = runTest {
        for (duration in listOf(Duration.ZERO, -IdentityFrozen.sdkLifetime, Duration.INFINITE, -Duration.INFINITE)) {
            identityFixture { f ->
                assertNull(f.bind(duration = duration))
                f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
            }
        }
        identityFixture { f ->
            f.lifetime.expiry = IdentityFrozen.nativeExpiry
            val native = IdentityFrozen.peer.getValue("native_expiry").jsonObject
            f.clock.second = native.number("before")
            val binding = assertNotNull(f.bind(duration = IdentityFrozen.sdkLifetime))
            assertIdentityScope(assertNotNull(f.scope(binding)), f)
            f.clock.second = native.number("at")
            assertNull(f.scope(binding))
            f.clock.second = native.number("after")
            assertNull(f.scope(binding))
        }
        // Absent lifetime is not representable by the required argument. Actual SDK expiry remains unbound.
    }

    @Test
    fun rpcid007RepresentableMissingChangedAndNoncurrentTrustedPeerRowsRefuseCreation() = runTest {
        identityFixture { f ->
            assertNull(f.bind(peer = f.sdkPeer.copy(fingerprint = null)))
            f.a.assertIntact()
        }
        identityFixture { f -> f.provenance.current = null; assertNull(f.bind()); f.a.assertIntact() }
        identityFixture { f -> f.lifetime.end(); assertNull(f.bind()); f.a.assertIntact() }
        val changes: List<(IdentityUnitFixture) -> LocalAuthenticatedPeer> = listOf(
            { f -> LocalAuthenticatedPeer(f.connection, IdentityFrozen.scopeA, IdentityFrozen.id("DEVICE-B"),
                f.physicalPeer.publicKeyIdentifier, f.physicalPeer.publicFingerprint, f.lifetime::current) },
            { f -> LocalAuthenticatedPeer(f.connection, IdentityFrozen.scopeA, IdentityFrozen.id("DEVICE-A"),
                f.physicalPeer.publicKeyIdentifier, IdentityFrozen.fingerprint("FP-B").value, f.lifetime::current) },
            { f -> LocalAuthenticatedPeer(f.connection, IdentityFrozen.scopeA, IdentityFrozen.id("DEVICE-A"),
                f.physicalPeer.publicKeyIdentifier, null, f.lifetime::current) },
            { f -> LocalAuthenticatedPeer(f.connection, IdentityFrozen.scope(IdentityFrozen.identity.getValue("scope_other").jsonObject),
                IdentityFrozen.id("DEVICE-A"), f.physicalPeer.publicKeyIdentifier, f.physicalPeer.publicFingerprint, f.lifetime::current) },
        )
        for (change in changes) identityFixture { f ->
            f.provenance.current = change(f)
            assertNull(f.bind()); f.a.assertIntact(); f.verifier.assertReleasedSourceCopies()
        }
        // Typed nonnullable device/connection/lifetime and real unauthenticated/admission rows stay unbound.
    }

    @Test
    fun rpcid008MissingOrChangedTrustedContextNeverFallsBackToOriginalDescription() = runTest {
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            f.provenance.current = null
            assertNull(f.scope(binding))
            f.provenance.current = f.physicalPeer
            f.verifier.identity = IdentityFrozen.originalIdentity().copy(sessionId = IdentityFrozen.id("SESSION-B"))
            assertNull(f.scope(binding))
            f.verifier.assertOriginalLabel()
            f.a.assertIntact()
        }
        // Body/discovery/login/permission/TTL decoys cannot be supplied to this public unit seam.
        // Their exact injection and advisory-only invariance assertions remain unbound, not simulated.
    }

    @Test
    fun rpcid019SuccessReturnsSdkIdentityResourceMetadataNotMasterOrDurableAuthority() = runTest {
        identityFixture { f ->
            val binding = assertNotNull(f.bind())
            val metadata = assertNotNull(f.scope(binding, resource = "ORDER-B"))
            assertIdentityScope(metadata, f, resource = "ORDER-B")
            f.verifier.assertOriginalLabel()
        }
        // ORDER-B is the frozen wrong-owner resource; a resource label is NOT permission.
        // No Master/host/command is fabricated here. Real denial, effects and serving remain unbound.
    }
}
