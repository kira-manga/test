package com.ukkera.pos.app.gateway.independent.returnrightspresentationv1

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
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationProfileV1
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
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
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.test.assertEquals

/**
 * Actual common endpoint workflow, with controlled Master replies and a read-only journal seam.
 * These ports establish no Master authorization, trust, durability, RPC or native session facts.
 */
internal class ControlledReturnRightsPresentationHarness(
    val selectedScope: BranchScope = FrozenReturnRightsPresentationInputs.scope,
    val actor: DomainId = FrozenReturnRightsPresentationInputs.id("actorA"),
    installedRead: Boolean = true,
    val grants: List<PermissionGrant> = allowingHint(selectedScope),
) {
    // Unspecified connection/device/session/boot coordinates are synthetic constructor data only.
    val device = DomainId("50000000-0000-4000-8000-000000000001")
    private val connection = DomainId("50000000-0000-4000-8000-000000000002")
    private val boot = DomainId("50000000-0000-4000-8000-000000000003")
    private var session = DomainId("50000000-0000-4000-8000-000000000004")
    private var currentActor = actor
    private var leaseEpoch = 1L
    var live = true
        private set
    private val ownedCredentials = mutableListOf<LocalGatewayCredentials>()
    val journal = ReadOnlyPresentationJournal(LocalClientIdentity(selectedScope, actor, device))
    val master = ControlledReturnRightsMaster { view() }
    val gateway = LocalEndpointWorkflowGateway(master, journal, LocalWorkflowCredentialSource {
        val capturedEpoch = leaseEpoch
        // Owned placeholder bytes are never read or used as authentication/trust evidence.
        val credentials = LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(16)))
        ownedCredentials += credentials
        LocalWorkflowCredentialLease(credentials) { live && capturedEpoch == leaseEpoch }
    }, LocalWorkflowInstallation(
        setOf(LocalConfigurationProfile.COMMAND, LocalReturnRightsActivationProfileV1.ACTIVATE),
        setOf(LocalConfigurationProfile.QUERY) + if (installedRead)
            setOf(LocalReturnRightsActivationProfileV1.READ) else emptySet()))

    fun invalidate(variantId: String) {
        when (variantId) {
            "LEASE-01", "LEASE-02" -> live = false
            "LEASE-03", "LEASE-04" -> {
                leaseEpoch++
                session = DomainId("50000000-0000-4000-8000-000000000005")
                if (variantId == "LEASE-04") currentActor = FrozenReturnRightsPresentationInputs.id("actorB")
            }
            else -> error("Unknown frozen lease variant")
        }
    }

    fun close() {
        live = false
        leaseEpoch++
        ownedCredentials.forEach { it.bearer.destroy() }
    }

    fun assertNoMutationDispatch(context: String) {
        assertEquals(0, master.mutationDispatches, context)
        assertEquals(0, master.operationLookups, context)
        assertEquals(0, journal.writes, context)
    }

    private fun view(): LocalSessionView = LocalSessionView(currentActor, selectedScope, device, session,
        SessionGeneration(leaseEpoch), LocalLoginIdentifier("synthetic-return-rights-presentation"),
        ActivatedLocalPolicy(selectedScope, currentActor, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(boot, 1), MonotonicMoment(boot, 2))

    companion object {
        fun allowingHint(scope: BranchScope): List<PermissionGrant> = listOf(PermissionGrant(
            Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), scope, GrantEffect.ALLOW))

        fun targetGrants(variant: JsonObject, scope: BranchScope = FrozenReturnRightsPresentationInputs.scope): List<PermissionGrant> {
            check(variant.string("variantId") !in setOf("TARGET-05", "TARGET-09")) {
                "The frozen symbolic delegation and READ-access inputs lack executable bindings"
            }
            return variant.getValue("grants").jsonArray.map { input ->
                val grant = input.jsonObject
                val target = grant.getValue("targetId").jsonPrimitive.content.takeUnless { it == "null" }
                PermissionGrant(Permission(SecurityAction.valueOf(grant.string("action")),
                    PermissionVerb.valueOf(grant.string("access"))), scope,
                    GrantEffect.valueOf(grant.string("effect")),
                    targetIds = target?.let { setOf(DomainId(it)) })
            }
        }
    }
}

/** Explicit delivery barrier; resumption is queued by the test dispatcher, with no sleeps. */
internal class ControlledDelivery<T : Any> {
    val waiting = CompletableDeferred<Unit>()
    var delivered = false
        private set
    private var pending: CancellableContinuation<T>? = null
    private var reply: T? = null

    suspend fun hold(value: T): T = suspendCancellableCoroutine { continuation ->
        check(pending == null)
        reply = value
        pending = continuation
        waiting.complete(Unit)
    }

    fun deliver() {
        check(!delivered)
        delivered = true
        checkNotNull(pending).resume(checkNotNull(reply))
    }
}

internal class ControlledReturnRightsMaster(private val currentView: () -> LocalSessionView) : LocalMasterClient {
    val requests = mutableListOf<ReadRequest>()
    val protectedRequests: List<ReadRequest> get() = requests.filter { it.query == LocalReturnRightsActivationProfileV1.READ }
    val replyScript = ArrayDeque<ReadReply>()
    var currentDelivery: ControlledDelivery<LocalSessionViewResult>? = null
    var readDelivery: ControlledDelivery<ReadReply>? = null
    var mutationDispatches = 0
        private set
    var operationLookups = 0
        private set

    fun replyFixture(name: String) { replyText(FrozenReturnRightsPresentationInputs.wire(name)) }
    fun replyText(text: String) { replyScript.addLast(ReadReply.Data(StoredBytes(text.encodeToByteArray()), Revision(1),
        UtcInstant.parseCanonical("2026-10-31T12:00:00Z"))) }
    fun replyUnavailable(reply: ReadReply.Unavailable) { replyScript.addLast(reply) }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        val reply = LocalSessionViewResult.Current(currentView())
        return currentDelivery?.also { currentDelivery = null }?.hold(reply) ?: reply
    }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        requests += request
        val reply = when (request.query) {
            LocalReturnRightsActivationProfileV1.READ -> {
                check(replyScript.isNotEmpty()) { "Every protected reply must be explicitly controlled" }
                replyScript.removeFirst()
            }
            LocalConfigurationProfile.QUERY -> {
                val query = Json.parseToJsonElement(request.payload.rrpText()).jsonObject
                val kind = query.string("kind")
                val body = "{\"kind\":${JsonPrimitive(kind)},\"items\":[],\"nextAfter\":null,\"catalogRevision\":null,\"detail\":null}"
                ReadReply.Data(StoredBytes(body.encodeToByteArray()), Revision(1),
                    UtcInstant.parseCanonical("2026-10-31T12:00:00Z"))
            }
            else -> throw AssertionError("Unbound documentary read")
        }
        return readDelivery?.also { readDelivery = null }?.hold(reply) ?: reply
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        mutationDispatches++
        throw AssertionError("Documentary navigation must not dispatch a mutation")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        operationLookups++
        throw AssertionError("A policy read must not acquire authority through an operation receipt")
    }
    override val health: StateFlow<LocalMasterEndpointHealth> get() = forbidden()
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = forbidden()
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = forbidden()
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = forbidden()
    private fun forbidden(): Nothing = throw AssertionError("Unbound controlled authentication/transport port")
}

/** This seam permits no durable write and is never a business-state or persistence substitute. */
internal class ReadOnlyPresentationJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var writes = 0
        private set
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(emptyList())
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        throw AssertionError("Documentary reads must not recover or fabricate an operation")
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite = forbiddenWrite()
    override suspend fun markDispatched(operationId: OperationId): JournalWrite = forbiddenWrite()
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = forbiddenWrite()
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = forbiddenWrite()
    override suspend fun close() = Unit
    private fun forbiddenWrite(): Nothing {
        writes++
        throw AssertionError("Documentary reads must not write the client intent journal")
    }
}

internal fun StoredBytes.rrpText(): String = copyBytes().let { bytes ->
    try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
}
