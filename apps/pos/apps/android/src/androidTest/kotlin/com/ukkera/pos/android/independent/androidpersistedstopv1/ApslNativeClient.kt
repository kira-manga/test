package com.ukkera.pos.android.independent.androidpersistedstopv1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureClient
import com.ukkera.pos.app.gateway.AndroidOriginalClientJournals
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.sales.store.LocalCashCommandCodec
import com.ukkera.pos.sales.store.LocalCashQueryV1
import com.ukkera.pos.sales.store.LocalCashReadCodec
import com.ukkera.pos.sales.store.LocalCashReadResultV1
import com.ukkera.pos.sales.store.LocalCashRequestV1
import com.ukkera.pos.sales.store.LocalCashSessionViewV1
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalSalesAction
import com.ukkera.pos.sales.store.LocalSalesResultCodec
import com.ukkera.pos.sales.store.LocalSalesResultV1
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The exact production native client for one selected APSL case. It uses real native sign-in,
 * current-session reads, ordinary current-authorized commands and real CASH custody reads.
 * It supplies no actor, grant, result, drawer, cash balance or resolution.
 */
internal class ApslNativeClient(
    override val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    private val plan: ApslFixturePlan,
    private val evidence: ApslEvidence,
) : NativeLocalFixtureClient {
    private var tokens: LocalSessionTokens? = null
    override var signIns: Int = 0
        private set
    private var closed = false
    private var revocationUnconfirmed = false

    suspend fun signIn(password: CharArray): LocalSessionView {
        check(!closed && !revocationUnconfirmed && tokens == null && original.isCurrent() && original.peer.isCurrent())
        val copy = password.copyOf()
        val reply = try {
            original.client.signIn(original.peer.connectionId, plan.employeeLogin, copy)
        } finally { copy.fill('\u0000') }
        val authenticated = reply as? LocalAuthenticationResult.Authenticated
            ?: error("Actual native A1 sign-in was not authenticated: $reply")
        tokens = authenticated.tokens
        signIns += 1
        val view = current()
        val coordinates = original.material.coordinates
        check(view.actorId == coordinates.firstActorId && view.scope == coordinates.scope &&
            view.deviceId == coordinates.deviceId && view.login == plan.employeeLogin) {
            "The actual A1 session is not bound to this original installation"
        }
        evidence.event("FRESH_NATIVE_A1_SESSION", JSONObject().put("actorId", view.actorId.value)
            .put("deviceId", view.deviceId.value).put("sessionId", view.sessionId.value)
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        return view
    }

    suspend fun current(): LocalSessionView {
        check(!closed && !revocationUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        val actual = tokens ?: error("No current native A1 session")
        return (original.client.current(credentials(actual)) as? LocalSessionViewResult.Current)?.view
            ?: error("The actual A1 session is unavailable")
    }

    suspend fun execute(request: MutationRequest): MutationReply.Recorded {
        check(!closed && !revocationUnconfirmed && request.command in original.commandKeys)
        val before = current()
        evidence.event("COMMAND_DISPATCH", JSONObject().put("operationId", request.operationId.value)
            .put("command", request.command.name))
        val raw = original.client.execute(credentials(checkNotNull(tokens)), request)
        val reply = raw as? MutationReply.Recorded
            ?: error("The selected cash command did not commit: $raw")
        val after = current()
        check(before.sessionId == after.sessionId && before.actorId == after.actorId &&
            before.generation == after.generation) { "A cash command silently replaced the current session" }
        return reply
    }

    suspend fun read(request: ReadRequest): ReadReply.Data {
        check(!closed && !revocationUnconfirmed && request.query in original.readKeys)
        val before = current()
        val raw = original.client.read(credentials(checkNotNull(tokens)), request)
        val reply = raw as? ReadReply.Data ?: error("The selected cash read was unavailable: $raw")
        val after = current()
        check(before.sessionId == after.sessionId && before.actorId == after.actorId)
        return reply
    }

    suspend fun logout(): LocalSessionView {
        check(!closed && !revocationUnconfirmed)
        val actual = tokens ?: error("No current native A1 session")
        val view = current()
        val result = original.client.logout(credentials(actual))
        if (result != LocalSignOutResult.Revoked) {
            revocationUnconfirmed = true
            error("Actual native A1 logout was not revoked: $result")
        }
        actual.destroy()
        tokens = null
        evidence.event("REAL_A1_LOGOUT_REVOKED", JSONObject().put("actorId", view.actorId.value)
            .put("deviceId", view.deviceId.value).put("sessionId", view.sessionId.value))
        return view
    }

    suspend fun readCash(query: LocalCashQueryV1): LocalCashReadResultV1 =
        LocalCashReadCodec.decode(read(LocalCashReadCodec.request(query)).payload)

    suspend fun configureAndOpenDrawer(): LocalCashSessionViewV1 {
        val actor = original.material.coordinates.firstActorId
        cash(LocalCashRequestV1.ConfigureCustodian(actor), LocalSalesAction.CUSTODIAN_CONFIGURED, actor)
        cash(LocalCashRequestV1.ConfigureDrawer(plan.drawerId, "APSL synthetic drawer", plan.openingCash.currency),
            LocalSalesAction.DRAWER_CONFIGURED, plan.drawerId)
        val drawers = readCash(LocalCashQueryV1.Drawers()) as? LocalCashReadResultV1.Drawers
            ?: error("Actual drawer CAS projection is unavailable")
        val drawer = drawers.drawers.singleOrNull()
            ?: error("The selected drawer is not uniquely visible")
        check(drawer.id == plan.drawerId && !drawer.assigned && !drawer.awaitingClosingReceipt)
        val own = readCash(LocalCashQueryV1.OwnCash) as? LocalCashReadResultV1.OwnCash
            ?: error("Actual own CASH projection is unavailable")
        check(own.custodianId == actor && own.session == null)
        val now = UtcInstant(Clock.System.now())
        val date = plan.calendar.businessDateAt(now)
        cash(LocalCashRequestV1.Open(plan.cashSessionId, plan.drawerId, drawer.revision, own.assignmentRevision,
            date, plan.openingSourceId, plan.openingCash, LocalCashEvidenceV1(plan.openingEvidenceId, now)),
            LocalSalesAction.CASH_OPENED, plan.cashSessionId)
        val session = checkNotNull((readCash(LocalCashQueryV1.OwnCash) as? LocalCashReadResultV1.OwnCash)?.session)
        check(session.id == plan.cashSessionId && session.drawerId == plan.drawerId &&
            session.custodianId == actor && session.status == "OPEN" && session.opening == plan.openingCash)
        evidence.event("ACTUAL_OPEN_DRAWER_CUSTODY", JSONObject().put("sessionId", session.id.value)
            .put("drawerId", session.drawerId.value).put("custodianId", session.custodianId.value)
            .put("deviceId", original.material.coordinates.deviceId.value)
            .put("openingMinor", session.opening.minor).put("status", session.status))
        return session
    }

    /** Leaves a real pending closing original attributed to A1, D1 and this M1 client. */
    suspend fun beginPendingClosing(): LocalCashSessionViewV1 {
        val own = readCash(LocalCashQueryV1.OwnCash) as? LocalCashReadResultV1.OwnCash
            ?: error("Actual own CASH projection is unavailable")
        val session = checkNotNull(own.session) { "The original cash session is unavailable" }
        cash(LocalCashRequestV1.BeginClosing(session.id, session.revision, own.workRevision),
            LocalSalesAction.CASH_CLOSING, session.id)
        val pending = checkNotNull((readCash(LocalCashQueryV1.OwnCash) as? LocalCashReadResultV1.OwnCash)?.session)
        check(pending.id == session.id && pending.status == "CLOSING" && pending.drawerId == plan.drawerId &&
            pending.custodianId == session.custodianId)
        evidence.event("ACTUAL_PENDING_CLOSING_ORIGINAL", JSONObject().put("sessionId", pending.id.value)
            .put("drawerId", pending.drawerId.value).put("custodianId", pending.custodianId.value)
            .put("deviceId", original.material.coordinates.deviceId.value)
            .put("status", pending.status).put("revision", pending.revision.value)
            .put("expectedCashMinor", pending.expectedCash.minor))
        return pending
    }

    private suspend fun cash(request: LocalCashRequestV1, action: LocalSalesAction, document: DomainId) {
        val reply = execute(MutationRequest(OperationId.random(), LocalSalesStoreProfile.CASH_COMMAND,
            LocalCashCommandCodec.encode(request)))
        val result = LocalSalesResultCodec.decode(reply.result)
        check(result is LocalSalesResultV1.Applied && result.action == action && result.documentId == document) {
            "The actual CASH command did not apply the selected action: $result"
        }
    }

    private fun credentials(actual: LocalSessionTokens): LocalGatewayCredentials =
        LocalGatewayCredentials(original.peer.connectionId, actual.access)

    override suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed) return@withContext
        var failure: Throwable? = null
        if (tokens != null) {
            try { logout() } catch (caught: Throwable) {
                revocationUnconfirmed = true
                failure = caught
                tokens?.destroy()
                tokens = null
            }
        }
        try { journals.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        closed = true
        failure?.let { throw it }
    }

    override fun toString(): String = "ApslNativeClient([REDACTED])"
}
