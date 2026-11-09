package com.ukkera.pos.app.independent.credentialentry

import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.CentralUiGateway
import com.ukkera.pos.app.CentralUiSession
import com.ukkera.pos.app.LocalEnrollmentMode
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.LocalEnrollmentStatus
import com.ukkera.pos.app.LocalEnrollmentUiGateway
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SignInFailure
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal enum class CredentialGatewayAction { REFUSE, FAIL, SUSPEND }

/**
 * The actual common runtime is exercised with a refusing boundary. Only frozen expected
 * fixture scalars are retained; incoming credentials are compared during the call and never saved.
 * No boundary returns an authenticated session or a created employee.
 */
internal class CredentialEntryRuntimeFixture(
    val operation: CredentialEntryOperation,
    supportsName: Boolean,
    var expectedInput: FrozenRuntimeInput,
    private val action: CredentialGatewayAction = CredentialGatewayAction.REFUSE,
) {
    var statusSupportsName = supportsName
    var authenticationCalls = 0
        private set
    var enrollmentCalls = 0
        private set
    var submittedIdentities = 0
        private set
    var otherAuthenticationCalls = 0
        private set
    var lookupCalls = 0
        private set
    var accountMatched = true
        private set
    var passwordMatched = true
        private set
    var nameMatched = true
        private set
    val entered = CompletableDeferred<Unit>()
    private val signInReply = CompletableDeferred<SignInResult<BranchUiSession>>()
    private val enrollmentReply = CompletableDeferred<LocalEnrollmentResult>()

    private val branch = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            handleSignIn(account, secret)
        override suspend fun signIn(account: String, secret: CharArray,
            explicitDeviceMove: Boolean): SignInResult<BranchUiSession> = handleSignIn(account, secret)
    }
    private val central = object : CentralUiGateway {
        override val configured = false
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<CentralUiSession> {
            otherAuthenticationCalls++
            error("Unexpected nonlocal authentication call")
        }
    }
    private val enrollment = object : LocalEnrollmentUiGateway {
        override suspend fun status() = LocalEnrollmentStatus(LocalEnrollmentMode.LOCAL_QUALIFICATION_ONLY,
            available = true, supportsDisplayName = statusSupportsName)
        override suspend fun enroll(requestId: DomainId, account: String, displayName: String,
            password: CharArray): LocalEnrollmentResult {
            enrollmentCalls++
            submittedIdentities++
            compareIncoming(account, password, displayName)
            entered.complete(Unit)
            return when (action) {
                CredentialGatewayAction.REFUSE -> enrollmentRefusal()
                CredentialGatewayAction.FAIL -> throw IllegalStateException("Synthetic credential-entry gateway failure")
                CredentialGatewayAction.SUSPEND -> enrollmentReply.await()
            }
        }
        override suspend fun lookup(requestId: DomainId): LocalEnrollmentResult {
            lookupCalls++
            error("Unexpected automatic enrollment lookup")
        }
    }

    val runtime = UkkeraRuntime(AppDependencies(branchGateway = branch, centralGateway = central,
        platform = PlatformCapabilities(PlatformFamily.DESKTOP), enrollmentGateway = enrollment))
    private var entryRoot: SecurityRoot? = null
    private var entryGeneration: Long? = null

    suspend fun prepare() {
        when (operation) {
            CredentialEntryOperation.SIGN_IN -> runtime.openSignIn(company = false, runtime.navigator.token())
            CredentialEntryOperation.ENROLLMENT -> {
                runtime.openEnrollment(runtime.navigator.token())
                runtime.loadEnrollmentStatus(runtime.navigator.token())
                assertEquals(statusSupportsName, runtime.presentation.value.enrollment.status?.supportsDisplayName,
                    "Configured enrollment name capability must be observable")
            }
        }
        entryRoot = runtime.navigator.state.value.root
        entryGeneration = runtime.navigator.state.value.rootGeneration
        assertEquals(if (operation == CredentialEntryOperation.SIGN_IN) SecurityRoot.BRANCH_SIGN_IN
            else SecurityRoot.LOCAL_ENROLLMENT, entryRoot, "The actual entry root must be available")
        assertNull(runtime.presentation.value.display, "Entry fixture has no authenticated session")
    }

    fun invoke(scope: CoroutineScope, submitted: CharArray): Job = when (operation) {
        CredentialEntryOperation.SIGN_IN -> scope.launch {
            runtime.signIn(expectedInput.account, submitted, runtime.navigator.token(), explicitDeviceMove = true)
        }
        CredentialEntryOperation.ENROLLMENT -> runtime.launchEnrollment(scope, expectedInput.account,
            expectedInput.name, submitted, approved = true, token = runtime.navigator.token())
    }

    fun assertCalls(selectedCalls: Int, label: String) {
        assertEquals(if (operation == CredentialEntryOperation.SIGN_IN) selectedCalls else 0,
            authenticationCalls, "$label: local authentication calls")
        assertEquals(if (operation == CredentialEntryOperation.ENROLLMENT) selectedCalls else 0,
            enrollmentCalls, "$label: enrollment calls")
        assertEquals(if (operation == CredentialEntryOperation.ENROLLMENT) selectedCalls else 0,
            submittedIdentities, "$label: submitted enrollment identities")
        assertEquals(0, otherAuthenticationCalls, "$label: nonlocal authentication calls")
        assertEquals(0, lookupCalls, "$label: automatic enrollment lookups")
    }

    fun assertExactForwarding(label: String) {
        assertTrue(accountMatched, "$label: exact account code units received")
        assertTrue(passwordMatched, "$label: exact password code units received")
        if (operation == CredentialEntryOperation.ENROLLMENT)
            assertTrue(nameMatched, "$label: exact display-name code units received")
    }

    fun assertEntryUnchanged(label: String) {
        assertEquals(entryRoot, runtime.navigator.state.value.root, "$label: unauthenticated entry root unchanged")
        assertEquals(entryGeneration, runtime.navigator.state.value.rootGeneration, "$label: entry generation unchanged")
        assertNull(runtime.presentation.value.display, "$label: no accepted display installed")
        assertNull(runtime.presentation.value.localAttachment, "$label: no accepted attachment installed")
        assertFalse(runtime.presentation.value.training, "$label: no training access installed")
    }

    fun assertCleared(submitted: CharArray, label: String) {
        assertTrue(submitted.all { it == '\u0000' }, "$label: every submitted credential element cleared")
        val visibleResult: Any? = when (operation) {
            CredentialEntryOperation.SIGN_IN -> runtime.presentation.value.signInFailure
            CredentialEntryOperation.ENROLLMENT -> runtime.presentation.value.enrollment.result
        }
        assertNotSame(submitted, visibleResult, "$label: result is not the submitted credential buffer")
    }

    fun assertSentinelAbsent(failure: Throwable?, label: String) {
        assertFalse(runtime.presentation.value.toString().contains(FROZEN_FAILURE_PASSWORD),
            "$label: sentinel excluded from visible result fields")
        assertFalse(failure?.toString()?.contains(FROZEN_FAILURE_PASSWORD) == true,
            "$label: sentinel excluded from termination error")
        assertFalse(failure?.cause?.toString()?.contains(FROZEN_FAILURE_PASSWORD) == true,
            "$label: sentinel excluded from termination error cause")
    }

    fun releaseWithRefusal() {
        signInReply.complete(SignInResult.Rejected(SignInFailure.DENIED))
        enrollmentReply.complete(enrollmentRefusal())
    }

    fun close() = runtime.close()

    private suspend fun handleSignIn(account: String, secret: CharArray): SignInResult<BranchUiSession> {
        authenticationCalls++
        compareIncoming(account, secret, "")
        entered.complete(Unit)
        return when (action) {
            CredentialGatewayAction.REFUSE -> SignInResult.Rejected(SignInFailure.DENIED)
            CredentialGatewayAction.FAIL -> throw IllegalStateException("Synthetic credential-entry gateway failure")
            CredentialGatewayAction.SUSPEND -> signInReply.await()
        }
    }

    private fun compareIncoming(account: String, secret: CharArray, name: String) {
        accountMatched = accountMatched && account == expectedInput.account
        passwordMatched = passwordMatched && secret.size == expectedInput.password.length &&
            secret.indices.all { secret[it] == expectedInput.password[it] }
        nameMatched = nameMatched && name == expectedInput.name
    }

    private fun enrollmentRefusal() = LocalEnrollmentResult.Rejected(LocalServiceProblem.DENIED)
}
