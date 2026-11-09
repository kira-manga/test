package com.ukkera.pos.app.gateway.independent.supplierreferencev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertIs

/** Actual gateway and encrypted journal, with an explicitly controlled Master boundary. */
internal class SupplierReferenceV2GatewayFixture(val host: SupplierReferenceV2EncryptedJournal) {
    val master = SupplierReferenceV2ControlledMaster()
    var loggedIn = true
    var leaseCurrent = true
    var installation = LocalWorkflowInstallation.supplierOnly(true)
    fun gateway(): LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(master, host.journal,
        LocalWorkflowCredentialSource {
            if (!loggedIn) null else LocalWorkflowCredentialLease(
                LocalGatewayCredentials(master.device, OpaqueCredential(ByteArray(16)))) { loggedIn && leaseCurrent }
        }, installation)
    suspend fun reopenClient() {
        loggedIn = false
        leaseCurrent = false
        assertIs<JvmClientJournalOpenResult.Opened>(host.reopen())
    }
    fun reauthenticateOriginalControlledOwner() { loggedIn = true; leaseCurrent = true }
}

/**
 * Executes real supplier handlers against controlled transaction rows and pure grant
 * snapshots. Reply identities come from actual Apply results, but their wrapping
 * sequence/original retention/current session are controlled, not a production
 * Master transaction, durable audit/outbox/history, native authorization or RPC.
 */
internal class SupplierReferenceV2ControlledMaster : LocalMasterClient, CommandReadView {
    private val f = SupplierReferenceV2GatewayInputs
    override val scope = f.scope
    var actor = f.actor
    var device = f.device
    private val session = DomainId.random()
    private val clock = DomainId.random()
    private val rows = linkedMapOf<AggregateKey, StoredAggregate>()
    val originals = linkedMapOf<OperationId, MutationReply.Recorded>()
    val originalRequests = linkedMapOf<OperationId, MutationRequest>()
    val executions = mutableListOf<MutationRequest>()
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    var grants = setOf(f.read, f.edit)
    var currentResult: LocalSessionViewResult? = null
    var readOverride: (suspend (ReadRequest) -> ReadReply)? = null
    var lookupOverride: (suspend (OperationIdentity) -> LookupReply)? = null
    var executeOverride: (suspend (MutationRequest) -> MutationReply)? = null
    private val v1 = LocalConfigurationHandler(UnavailableCompanyPublicationSignatureVerifier)
    private val v2 = LocalSupplierHandlerV2()
    private val readV1 = LocalConfigurationRepository(UnavailableCompanyPublicationSignatureVerifier).readHandler()
    private val readV2 = LocalSupplierReadHandlerV2()
    val rowCount get() = rows.size
    fun storage(id: DomainId): Revision = checkNotNull(rows[LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id)]).revision
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("Health/physical attachment is not qualified by this fixture")
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = rows[key]
    override suspend fun claim(key: ClaimKey): DomainId? = error("No supplier claim boundary")
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = error("Controlled originals are not native evidence")
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        val remaining = rows.keys.filter { it.kind == kind && (after == null || it.id.value > after.value) }.sortedBy { it.id.value }
        val emitted = remaining.take(limit)
        return AggregateKeyPage(emitted, if (remaining.size > emitted.size) emitted.last().id else null)
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = currentResult ?: LocalSessionViewResult.Current(
        LocalSessionView(actor, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("Synthetic supplier operator"),
            ActivatedLocalPolicy(scope, actor, Revision(1), grants.map { PermissionGrant(it, scope, GrantEffect.ALLOW) },
                LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(clock, 1), MonotonicMoment(clock, 2)))

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        readOverride?.let { return it(request) }
        return readActual(request)
    }

    suspend fun readActual(request: ReadRequest): ReadReply {
        val handler = when (request.query) {
            LocalConfigurationProfile.QUERY -> readV1
            LocalSupplierProfileV2.QUERY -> readV2
            else -> error("Unexpected unrelated supplier read")
        }
        val normalized = handler.normalize(request.payload)
        val prepared = handler.prepare(normalized, this, ReadContext(scope, actor, handler.key,
            queryDigest(scope, actor, handler.key, normalized)))
        val actions = prepared.requirements.map { requirement ->
            (authorize(requirement) as? AuthorizationDecision.Allowed)?.authorization
                ?: return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        }
        return ReadReply.Data(prepared.project(actions, f.at), Revision(originals.size.toLong()), f.at)
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        executeOverride?.let { return it(request) }
        return commitActual(request)
    }

    suspend fun commitActual(request: MutationRequest): MutationReply.Recorded {
        check(request.operationId !in originals)
        val handler = when (request.command) {
            LocalConfigurationProfile.COMMAND -> v1
            LocalSupplierProfileV2.COMMAND -> v2
            else -> error("Unexpected unrelated supplier command")
        }
        val normalized = handler.normalize(request.payload)
        val prepared = handler.prepare(normalized, this, CommandContext(scope, actor, request.operationId,
            handler.key, semanticDigest(scope, actor, handler.key, normalized)))
        val authorized = assertIs<AuthorizationDecision.Allowed>(authorize(prepared.requirement)).authorization
        val outcome = prepared.evaluate(authorized, f.at)
        val (committed, code, result) = when (outcome) {
            is ProposedOutcome.Apply -> {
                check(outcome.changes.size == 1 && outcome.changes.single().key.kind == LocalConfigurationProfile.SUPPLIER)
                check(outcome.claims.isEmpty() && outcome.jobs.isEmpty())
                outcome.changes.forEach { change ->
                    check(rows[change.key]?.revision == change.expectedRevision)
                    rows[change.key] = StoredAggregate(change.key, (change.expectedRevision ?: Revision(0)).next(),
                        change.schema, change.payload, sha256(change.payload.copyBytes()))
                }
                Triple(true, "committed", outcome.result)
            }
            is ProposedOutcome.Reject -> Triple(false, outcome.code, outcome.result)
        }
        return MutationReply.Recorded(request.operationId, actor, committed, code, result, Revision(1),
            JournalSequence(originals.size.toLong() + 1), f.at, false).also {
            originals[request.operationId] = it
            originalRequests[request.operationId] = MutationRequest(request.operationId, request.command, normalized)
        }
    }

    suspend fun createV2(supplier: LocalSupplierV2, operation: OperationId) =
        commitActual(MutationRequest(operation, LocalSupplierProfileV2.COMMAND, LocalSupplierCodecV2.encode(LocalSupplierRequestV2(null, supplier))))

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        lookupOverride?.let { return it(identity) }
        if (identity.originalActorId != actor || identity.branchId != scope.branchId) return LookupReply.IdentityConflict(identity.operationId)
        val result = originals[identity.operationId] ?: return LookupReply.AbsentAtCurrentWriter(identity.operationId)
        val request = originalRequests.getValue(identity.operationId)
        if (identity.semanticSha256 != semanticDigest(scope, result.originalActorId, request.command, request.payload).value)
            return LookupReply.IdentityConflict(identity.operationId)
        return LookupReply.Found(result)
    }

    private suspend fun authorize(requirement: AuthorizationRequirement): AuthorizationDecision {
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials) =
                VerifiedLocalIdentity(actor, scope, device, session, SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val credential = LocalGatewayCredentials(device, OpaqueCredential(ByteArray(16)))
        val principal = try { checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(credential)) }
            finally { credential.bearer.destroy() }
        return AuthorizationEvaluator.evaluate(principal, AuthorizationSnapshot(actor, PrincipalKind.LOCAL_EMPLOYEE, scope,
            session, SessionGeneration(1), device, Revision(1), Revision(1), true, true, true, AuthenticationPurpose.INTERACTIVE,
            grants.map { PermissionGrant(it, scope, GrantEffect.ALLOW) }), requirement)
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No real login is qualified")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No real refresh is qualified")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No real logout is qualified")
}

internal suspend fun withSupplierReferenceV2Gateway(block: suspend (SupplierReferenceV2GatewayFixture) -> Unit) {
    val host = SupplierReferenceV2EncryptedJournal.create()
    try { block(SupplierReferenceV2GatewayFixture(host)) } finally { host.close() }
}
