package com.ukkera.pos.app.gateway.independent.returnrightsclientrecoveryv1

import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.gateway.GatewayAccess
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.LocalClientIntentJournal
import com.ukkera.pos.app.gateway.LocalEndpointWorkflowGateway
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.app.gateway.journalJson
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationProfileV1
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertEquals

/** Actual shared gateway with explicitly controlled replies/custody, never native or RPC proof. */
internal class ControlledReturnRightsClientRecoveryHarness(
    initial: StoredClientIntentV1? = null,
    owner: LocalClientIdentity = FrozenReturnRightsClientRecoveryInputs.owner,
    supported: Boolean = true,
    grants: List<PermissionGrant> = branchAllow(owner),
) {
    private val inputs = FrozenReturnRightsClientRecoveryInputs
    private val boot = DomainId("60000000-0000-4000-8000-000000000001")
    private var connection = DomainId("60000000-0000-4000-8000-000000000002")
    private val session = DomainId("60000000-0000-4000-8000-000000000003")
    private var live = true
    private val ownedCredentials = mutableListOf<LocalGatewayCredentials>()
    val journal = ControlledReturnRightsRecoveryJournal(owner, initial)
    val master = ControlledReturnRightsRecoveryMaster()
    val installed = LocalWorkflowInstallation(if (supported) setOf(LocalReturnRightsActivationProfileV1.ACTIVATE) else emptySet(),
        setOf(LocalReturnRightsActivationProfileV1.READ))
    var view = LocalSessionView(owner.actorId, owner.scope, owner.deviceId, session, SessionGeneration(1),
        LocalLoginIdentifier("synthetic-return-rights-recovery"),
        ActivatedLocalPolicy(owner.scope, owner.actorId, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(boot, 1), MonotonicMoment(boot, 2))
    val gateway = LocalEndpointWorkflowGateway(master, journal, LocalWorkflowCredentialSource { lease() }, installed)

    init { master.currentView = { view } }

    fun access(): GatewayAccess = GatewayAccess(master, lease(), view, installed)
    fun replaceGrants(grants: List<PermissionGrant>) {
        replaceSession(view.actorId, view.deviceId, view.scope, grants)
    }
    fun replaceSession(actor: DomainId, device: DomainId, scope: BranchScope = view.scope,
        grants: List<PermissionGrant> = emptyList()) {
        view = LocalSessionView(actor, scope, device, view.sessionId, view.generation, view.login,
            ActivatedLocalPolicy(scope, actor, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
            view.accessExpiresAt, view.workExpiresAt)
    }
    fun reconnect() {
        connection = DomainId("60000000-0000-4000-8000-000000000004")
        master.routingEpoch = inputs.objectValue("recoveryWorkflow").rrcString("routingEpochAfter")
        master.currentPointerRevision = inputs.objectValue("recoveryWorkflow").rrcString("currentPointerRevisionAfterReconnect")
    }
    fun close() { live = false; ownedCredentials.forEach { it.bearer.destroy() } }
    fun assertNoNewWork(context: String) {
        assertEquals(0, journal.stageCalls, context)
        assertEquals(0, master.mutations.size, context)
        assertEquals(0, master.reads.size, context)
    }
    private fun lease(): LocalWorkflowCredentialLease {
        // Owned synthetic bytes are never used as authentication or trust evidence.
        val credentials = LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(16)))
        ownedCredentials += credentials
        return LocalWorkflowCredentialLease(credentials) { live }
    }

    companion object {
        fun branchAllow(owner: LocalClientIdentity = FrozenReturnRightsClientRecoveryInputs.owner) = listOf(
            grant(owner, owner.scope.branchId, GrantEffect.ALLOW))
        fun grant(owner: LocalClientIdentity, target: DomainId, effect: GrantEffect) = PermissionGrant(
            Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), owner.scope, effect, targetIds = setOf(target))
    }
}

/** Scripted journal acknowledgements traverse the gateway; DURABLE here proves no persistence. */
internal class ControlledReturnRightsRecoveryJournal(owner: LocalClientIdentity, initial: StoredClientIntentV1?) : LocalClientIntentJournal(owner) {
    var entry: StoredClientIntentV1? = initial?.let(::roundTrip)
        private set
    var stageCalls = 0
        private set
    var dispatchCalls = 0
        private set
    var rememberCalls = 0
        private set
    var acknowledgeCalls = 0
        private set

    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
        JournalRead.Value(listOfNotNull(entry?.takeUnless { it.acknowledged }))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        throw AssertionError("Generic return-rights preparation must not stage a journal entry")
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++
        val original = checkNotNull(entry)
        check(original.operationId == operationId)
        entry = roundTrip(original.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name))
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        rememberCalls++
        val original = checkNotNull(entry)
        check(original.operationId == operationId && (original.recorded == null || original.recorded == result))
        entry = roundTrip(original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result))
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgeCalls++
        val original = checkNotNull(entry)
        check(original.operationId == operationId && original.recorded == result)
        entry = roundTrip(original.copy(acknowledged = true))
        return JournalWrite.DURABLE
    }
    override suspend fun close() = Unit
    private fun roundTrip(value: StoredClientIntentV1) = journalJson.decodeFromString(StoredClientIntentV1.serializer(),
        journalJson.encodeToString(StoredClientIntentV1.serializer(), value)).also { it.validate(owner) }
}

internal class ControlledReturnRightsRecoveryMaster : LocalMasterClient {
    lateinit var currentView: () -> LocalSessionView
    var routingEpoch = FrozenReturnRightsClientRecoveryInputs.objectValue("recoveryWorkflow").rrcString("routingEpochBefore")
    var currentPointerRevision = "0"
    var lookupReply: LookupReply = LookupReply.AbsentAtCurrentWriter(FrozenReturnRightsClientRecoveryInputs.operationId)
    var mutationReply: MutationReply = MutationReply.Deferred(FrozenReturnRightsClientRecoveryInputs.operationId, DeferredReason.HISTORY_INCOMPLETE)
    var readReply: ReadReply = ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
    val events = mutableListOf<String>()
    val lookups = mutableListOf<OperationIdentity>()
    val mutations = mutableListOf<MutationRequest>()
    val reads = mutableListOf<ReadRequest>()
    val mutationOwners = mutableListOf<Pair<DomainId, DomainId>>()
    val readOwners = mutableListOf<Pair<DomainId, DomainId>>()
    val readConnections = mutableListOf<DomainId>()
    val connections = mutableListOf<DomainId>()
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        connections += credentials.connectionId
        return LocalSessionViewResult.Current(currentView())
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        events += "lookup"
        lookups += identity
        return lookupReply
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        events += "execute"
        mutations += request
        mutationOwners += currentView().let { it.actorId to it.deviceId }
        return mutationReply
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        events += "read"
        reads += request
        readOwners += currentView().let { it.actorId to it.deviceId }
        readConnections += credentials.connectionId
        return readReply
    }
    override val health: StateFlow<LocalMasterEndpointHealth> get() = forbidden()
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = forbidden()
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = forbidden()
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = forbidden()
    private fun forbidden(): Nothing = throw AssertionError("Unbound real authentication/transport port")
}
