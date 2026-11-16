package com.ukkera.pos.rpc.independent.physicalsession

import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Self-authored, NOT independent. Supporting inputs v1, fixed before implementation:
 * one original SDK scope/physical lifetime; a copied same-identifier scope; missing or
 * successor physical associations; current native lifetime loss; retired binding; and
 * a changed authoritative employee. These controlled SDK DATA fixtures qualify no native
 * admission, protected kernel authorization or TCP behavior. Existing frozen tests remain
 * unchanged. A credential loan is DATA and deliberately cannot attest current authority.
 */
class ProtectedOriginalCredentialLoanSupportingTest {
    @Test fun originalCredentialCopyDoesNotRetireTheBindingOrCallerSecret() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            val loan = assertNotNull(binding.retainedCredentialsForProtectedOriginalCheck(f.authorizationData(scope)))
            try {
                assertTrue(f.credentials.bearer.useBytes { original ->
                    loan.bearer.useBytes { copied -> original.contentEquals(copied) }
                })
            } finally { loan.bearer.destroy() }
            f.callerA.assertIntact()
            assertNotNull(f.scope(binding))
        }
    }

    @Test fun copiedScopeAndMissingOrForeignPhysicalAssociationsCannotBorrow() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            for (context in listOf(
                f.authorizationData(sameIdentifierDifferentSdkSession(scope)),
                f.authorizationData(scope, original = null, physical = null),
                f.authorizationData(scope, original = f.sdkB.capability),
                f.authorizationData(scope, physical = f.sdkB.capability),
                f.authorizationData(scope, peer = PeerIdentity(PeerId("supporting-other-peer"), f.peer.fingerprint)),
            )) assertNull(binding.retainedCredentialsForProtectedOriginalCheck(context))
            f.callerA.assertIntact()
        }
    }

    @Test fun nativeLifetimeLossAndExplicitRetirementIrreversiblyStopBorrowing() = runTest {
        for (retire in listOf(false, true)) physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            if (retire) binding.retire() else f.nativeLifetimeA.end()
            repeat(2) { assertNull(binding.retainedCredentialsForProtectedOriginalCheck(f.authorizationData(scope))) }
            f.callerA.assertIntact()
        }
    }

    @Test fun borrowingDataCannotReplaceTheFreshOriginalPrincipalCheck() = runTest {
        physicalSessionFixture { f ->
            val binding = assertNotNull(f.bind())
            val scope = assertNotNull(f.scope(binding))
            f.verifier.identity = f.successorIdentity
            val context = f.authorizationData(scope)
            val loan = assertNotNull(binding.retainedCredentialsForProtectedOriginalCheck(context))
            try { assertNull(binding.currentOriginalPrincipal(context)) }
            finally { loan.bearer.destroy() }
            f.callerA.assertIntact()
        }
    }
}
