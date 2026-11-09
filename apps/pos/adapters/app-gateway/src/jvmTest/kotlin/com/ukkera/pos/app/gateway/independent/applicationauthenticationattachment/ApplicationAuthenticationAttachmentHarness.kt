@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.applicationauthenticationattachment

import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.LocalAuthenticationOnlyConnection
import com.ukkera.pos.app.gateway.LocalBranchDisplayLabels
import com.ukkera.pos.app.gateway.LocalClientCredentialJournal
import com.ukkera.pos.app.gateway.LocalClientDeviceIdentity
import com.ukkera.pos.app.gateway.LocalEndpointBranchGateway
import com.ukkera.pos.app.gateway.StoredClientCredentials
import com.ukkera.pos.app.gateway.StoredClientSessionV1
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Deterministic schedules over real shared gateway/session code; all ports remain supporting. */
internal class AppAuthenticationHarness(test: TestScope, owner: LocalClientDeviceIdentity = AppAuthenticationFrozen.owner) {
    val events = mutableListOf<String>()
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(test.testScheduler)
    val service = ControlledAppAuthenticationService(events)
    val journal = ControlledAppCredentials(owner, events)
    var originalCurrent = true
    val original = LocalAuthenticationOnlyConnection(service, AppAuthenticationFrozen.connectionId,
        AppAuthenticationFrozen.owner, LocalBranchDisplayLabels(AppAuthenticationFrozen.scope, "BRANCH_A company DATA", "BRANCH_A DATA")) {
        originalCurrent
    }
    val connections = MutableStateFlow<LocalAuthenticationOnlyConnection?>(original)
    private val gateways = mutableListOf<LocalEndpointBranchGateway>()
    val gateway = newGateway()

    fun newGateway(): LocalEndpointBranchGateway =
        LocalEndpointBranchGateway.authenticationOnly(connections, journal, dispatcher).also(gateways::add)

    suspend fun signIn(explicitDeviceMove: Boolean = false): SignInResult<BranchUiSession> =
        gateway.signIn(AppAuthenticationFrozen.login.value, AppAuthenticationFrozen.password(), explicitDeviceMove)

    suspend fun session(): BranchUiSession = assertIs<SignInResult.Authenticated<BranchUiSession>>(signIn()).session

    fun successorOriginal(): LocalAuthenticationOnlyConnection = LocalAuthenticationOnlyConnection(service,
        AppAuthenticationFrozen.successorConnectionId, LocalClientDeviceIdentity(AppAuthenticationFrozen.scope, AppAuthenticationFrozen.otherDevice),
        original.labels) { true }

    suspend fun close() = withContext(NonCancellable) {
        journal.releaseGates()
        service.releaseGates()
        try { gateways.forEach { it.closeAndAwait() } }
        finally { journal.close(); service.destroyOwnedResults() }
    }
}

/** A controlled return cut, not a native storage commit or physical-output commitment. */
internal class AppAuthenticationGate {
    val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun await() { entered.complete(Unit); released.await() }
    fun release() { released.complete(Unit) }
}

internal enum class ControlledAppWrite { DURABLE, KNOWN_PRECOMMIT_FAILURE, COMMITTED_BUT_UNKNOWN }

/**
 * Test-only bounded snapshots. There is no disk, protected key, native fence, persistence or
 * genuine reopen here. A committed-but-unknown script records its chosen outcome independently;
 * the application receives only the uncertainty, never a fabricated durable success.
 */
internal class ControlledAppCredentials(owner: LocalClientDeviceIdentity, private val events: MutableList<String>) :
    LocalClientCredentialJournal(owner) {
    private var state = StoredClientCredentials()
    var readable = true
    var writeCount = 0
        private set
    val faults = mutableMapOf<Int, ControlledAppWrite>()
    val gates = mutableMapOf<Int, AppAuthenticationGate>()
    var beforeWrite: ((Int, StoredClientCredentials) -> Unit)? = null

    override suspend fun read(): JournalRead<StoredClientCredentials> {
        events += "credentials.read"
        return if (readable) JournalRead.Value(copyCredentials(state)) else JournalRead.Unavailable
    }

    override suspend fun replace(expectedRevision: Long, next: StoredClientCredentials): JournalWrite {
        val ordinal = ++writeCount
        events += "credentials.write.$ordinal.enter"
        beforeWrite?.invoke(ordinal, next)
        gates[ordinal]?.await()
        if (!readable) return JournalWrite.UNAVAILABLE
        assertTrue(state.revision == expectedRevision, "Supporting writer received a stale snapshot")
        next.validate(owner)
        val outcome = faults[ordinal] ?: ControlledAppWrite.DURABLE
        if (outcome == ControlledAppWrite.KNOWN_PRECOMMIT_FAILURE) return JournalWrite.UNAVAILABLE
        val replacement = copyCredentials(next)
        state.destroy()
        state = replacement
        events += "credentials.write.$ordinal.retained"
        return if (outcome == ControlledAppWrite.COMMITTED_BUT_UNKNOWN) JournalWrite.UNAVAILABLE else JournalWrite.DURABLE
    }

    fun observe(): StoredClientCredentials = copyCredentials(state)
    fun releaseGates() { gates.values.forEach { it.release() } }
    override suspend fun close() { state.destroy() }
}

/** Scripted outcomes exercise application comparisons only; no password/bearer is verified. */
internal class ControlledAppAuthenticationService(private val events: MutableList<String>) : LocalAuthenticationService {
    var loginCount = 0
        private set
    var currentCount = 0
        private set
    var refreshCount = 0
        private set
    var logoutCount = 0
        private set
    var activeView: LocalSessionView = AppAuthenticationFrozen.view()
    val suppliedPasswords = mutableListOf<CharArray>()
    val suppliedCredentials = mutableListOf<LocalGatewayCredentials>()
    val issuedResults = mutableListOf<LocalSessionTokens>()
    val suppliedMoves = mutableListOf<Boolean>()
    val suppliedLogins = mutableListOf<String>()
    var loginGate: AppAuthenticationGate? = null
    var currentGate: AppAuthenticationGate? = null
    var refreshGate: AppAuthenticationGate? = null
    var logoutGate: AppAuthenticationGate? = null
    var loginResult: (() -> LocalAuthenticationResult)? = null
    var currentResult: (() -> LocalSessionViewResult)? = null
    var refreshResult: (() -> LocalAuthenticationResult)? = null
    var logoutResult: LocalSignOutResult = LocalSignOutResult.Revoked
    var beforeCurrentReturn: (() -> Unit)? = null

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult {
        loginCount++
        events += "authentication.login.enter"
        suppliedPasswords += password
        suppliedMoves += explicitDeviceMove
        suppliedLogins += login.value
        loginGate?.await()
        return loginResult?.invoke() ?: authenticated(activeView)
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        currentCount++
        events += "authentication.current.enter"
        suppliedCredentials += credentials
        currentGate?.await()
        val result = currentResult?.invoke() ?: LocalSessionViewResult.Current(activeView)
        beforeCurrentReturn?.invoke()
        return result
    }

    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        refreshCount++
        events += "authentication.refresh.enter"
        suppliedCredentials += credentials
        refreshGate?.await()
        return refreshResult?.invoke() ?: authenticated(AppAuthenticationFrozen.successorView()).also { activeView = it.tokens.view }
    }

    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult {
        logoutCount++
        events += "authentication.logout.enter"
        suppliedCredentials += credentials
        logoutGate?.await()
        return logoutResult
    }

    private fun authenticated(view: LocalSessionView): LocalAuthenticationResult.Authenticated =
        LocalAuthenticationResult.Authenticated(AppAuthenticationFrozen.tokens(view).also(issuedResults::add))

    fun releaseGates() { listOfNotNull(loginGate, currentGate, refreshGate, logoutGate).forEach { it.release() } }
    fun destroyOwnedResults() { issuedResults.forEach { it.destroy() } }
}

internal fun copyCredentials(source: StoredClientCredentials): StoredClientCredentials {
    val sessions = mutableListOf<StoredClientSessionV1>()
    try {
        for (row in source.sessions) {
            val access = row.access.useBytes(::OpaqueCredential)
            try {
                val refresh = row.refresh.useBytes(::OpaqueCredential)
                sessions += StoredClientSessionV1(row.localId, row.identity, row.login, row.binding, row.sessionId,
                    row.generation, row.accessExpiresAt, row.workExpiresAt, row.phase, row.originalRevoked, access, refresh)
            } catch (failure: Throwable) { access.destroy(); throw failure }
        }
        return StoredClientCredentials(source.revision, source.attempts.toList(), sessions, source.enrollments.toList(),
            source.administrations.toList(), source.formatVersion)
    } catch (failure: Throwable) { sessions.forEach { it.destroy() }; throw failure }
}

internal suspend fun TestScope.withAppAuthenticationHarness(
    owner: LocalClientDeviceIdentity = AppAuthenticationFrozen.owner,
    block: suspend (AppAuthenticationHarness) -> Unit,
) {
    val harness = AppAuthenticationHarness(this, owner)
    try { block(harness) }
    finally { harness.close() }
}

internal fun <T> ControlledAppCredentials.inspect(block: (StoredClientCredentials) -> T): T {
    val observed = observe()
    try { return block(observed) }
    finally { observed.destroy() }
}
