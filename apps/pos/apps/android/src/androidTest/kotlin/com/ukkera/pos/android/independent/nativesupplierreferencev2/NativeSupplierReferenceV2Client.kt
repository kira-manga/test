package com.ukkera.pos.android.independent.nativesupplierreferencev2

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureClient
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureSecret
import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The exact native original endpoint and journals, with V1/V2 selected before any request.
 * No endpoint observer, native authority, key, journal, reader or verifier is supplied by the test. */
internal class SupplierReferenceV2Client(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    private val evidence: SupplierReferenceV2Evidence,
) : NativeLocalFixtureClient {
    private var successfulSignIns = 0
    override val signIns: Int get() = successfulSignIns
    private var closing = false
    private val sessions = mutableListOf<BranchUiSession>()
    private val revoked = mutableSetOf<BranchUiSession>()
    private val connections = MutableStateFlow<LocalDirectoryClientConnection?>(LocalDirectoryClientConnection(
        endpoint = original.client,
        connectionId = original.peer.connectionId,
        identity = LocalClientDeviceIdentity(original.material.coordinates.scope, original.material.coordinates.deviceId),
        labels = LocalBranchDisplayLabels(original.peer.scope, original.companyName, original.material.branchName),
        profile = LocalMasterRpcWorkflowProfile.SUPPLIERS,
        supplierReferences = true,
        currentLifetime = original::isCurrent))
    val gateway = LocalEndpointBranchGateway.directoryOnly(connections, journals.credentials, journals,
        LocalMasterRpcWorkflowProfile.SUPPLIERS)

    suspend fun signIn(password: CharArray): BranchUiSession {
        check(!closing)
        val result = nativeFixtureSecret(password) {
            gateway.signIn(SupplierReferenceV2NativeSetup.employeeLogin.value, it)
        }
        val session = when (result) {
            is SignInResult.Authenticated -> result.session
            is SignInResult.Rejected -> error("Genuine original employee sign-in did not settle: ${result.reason.name}")
        }
        // Retain every delivered real session before any assertion/evidence write can fail.
        sessions += session
        successfulSignIns += 1
        return session
    }

    /** Public selector over the genuinely acquired first-employee intent owner; no creation. */
    suspend fun intentOwner(): LocalClientIdentity {
        val c = original.material.coordinates
        val selected = LocalClientIdentity(c.scope, c.firstActorId, c.deviceId)
        return checkNotNull(journals.current(selected)) {
            "The exact original employee's native intent journal is unavailable"
        }.owner.also { check(it == selected) }
    }

    suspend fun logout(session: BranchUiSession) {
        check(session in sessions && session !in revoked)
        val outcome = session.logout()
        // Preserve confirmed custody even if the subsequent evidence write fails.
        if (outcome == LocalSessionEndResult.REVOKED) revoked += session
        evidence.event("ACTUAL_ORIGINAL_EMPLOYEE_LOGOUT", JSONObject().put("outcome", outcome.name))
        check(outcome == LocalSessionEndResult.REVOKED) {
            "An unresolved logout is not authoritative revocation or permission to reopen"
        }
        check(!session.isCurrent() && session.current() is LocalSessionCheckResult.Unavailable)
        val cleanup = gateway.cleanupStatus()
        evidence.event("ACTUAL_GATEWAY_LOGOUT_CLEANUP", JSONObject().put("available", cleanup.available)
            .put("pendingRevocations", cleanup.pendingRevocations).put("authenticationUnresolved", cleanup.authenticationUnresolved))
        check(cleanup.clear) { "Original credential cleanup remains unresolved" }
    }

    /** Logout, gateway drain and exact journal closure are separate real cleanup operations.
     * Every owned resource is visited even after failure; failure never qualifies a new lifetime. */
    override suspend fun close(): Unit = withContext(NonCancellable) {
        closing = true
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (caught: Throwable) {
                val previous = failure
                if (previous == null) failure = caught else if (previous !== caught) previous.addSuppressed(caught)
            }
        }
        for (session in sessions) {
            if (session !in revoked) attempt {
                val outcome = session.logout()
                check(outcome == LocalSessionEndResult.REVOKED) { "Actual owned employee logout is unconfirmed" }
                revoked += session
            }
            attempt { session.close() } // Negative interactive cleanup even when revocation failed.
        }
        connections.value = null
        attempt { gateway.closeAndAwait() }
        attempt { journals.close() }
        failure?.let { throw it }
    }
}
