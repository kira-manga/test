package com.ukkera.pos.app.gateway.independent.openingobligationv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertTrue

/** Actual gateway with explicitly controlled client/session/journal seams, never native authority. */
internal class OpeningSupplierObligationUiHarness(
    grants: List<PermissionGrant> = listOf(OpeningSupplierObligationUiInputs.hint(SecurityAction.PURCHASING_APPROVE)),
) {
    private val f = OpeningSupplierObligationUiInputs
    val journal = OpeningSupplierControlledJournal(LocalClientIdentity(f.scope, f.actor, f.device))
    val endpoint = OpeningSupplierControlledEndpoint(f.sessionView(grants))
    var currentLifetime = true
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(f.connection, OpaqueCredential(ByteArray(16) { 7 }))) { currentLifetime }
    }, LocalWorkflowInstallation(setOf(LocalOpeningSupplierObligationProfile.COMMAND),
        setOf(LocalConfigurationProfile.QUERY, LocalOpeningSupplierObligationProfile.READ)))
}

/** Memory-only staging seam. The DURABLE enum here is controlled, not crash/fsync evidence. */
internal class OpeningSupplierControlledJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    val entries = linkedMapOf<OperationId, StoredClientIntentV1>()
    var stageWrites = 0
    private var active = true
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        if (active) JournalRead.Value(entries[operationId]) else JournalRead.Unavailable
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
        if (active) JournalRead.Value(entries.values.filterNot { it.acknowledged }) else JournalRead.Unavailable
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        if (!active) return JournalWrite.UNAVAILABLE
        intent.validate(owner)
        val previous = entries[intent.operationId]
        if (previous != null && !previous.sameIntent(intent)) return JournalWrite.IDENTITY_CONFLICT
        if (previous == null) { entries[intent.operationId] = intent; stageWrites++ }
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite = change(operationId) {
        it.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        val previous = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (!active) return JournalWrite.UNAVAILABLE
        if (previous.recorded != null && previous.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        return change(operationId) { it.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result) }
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        val previous = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (!active || previous.recorded != result) return JournalWrite.UNAVAILABLE
        return change(operationId) { it.copy(acknowledged = true) }
    }
    private fun change(operationId: OperationId, mutation: (StoredClientIntentV1) -> StoredClientIntentV1): JournalWrite {
        val previous = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (!active) return JournalWrite.UNAVAILABLE
        entries[operationId] = mutation(previous)
        return JournalWrite.DURABLE
    }
    override suspend fun close() { active = false }
}

/** Supplied replies only: this client does not authenticate, authorize, host, store or run RPC. */
internal class OpeningSupplierControlledEndpoint(view: LocalSessionView) : LocalMasterClient {
    private val f = OpeningSupplierObligationUiInputs
    var currentReply: LocalSessionViewResult = LocalSessionViewResult.Current(view)
    var openingReadReply: suspend (ReadRequest) -> ReadReply = { request ->
        val query = LocalOpeningSupplierObligationCodec.decodeQuery(request.payload)
        ReadReply.Data(LocalOpeningSupplierObligationCodec.page(LocalOpeningSupplierObligationPageV1(query, emptyList(), null)),
            Revision(1), f.recordedAt)
    }
    var lookupReply: suspend (OperationIdentity) -> LookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
    var executeReply: suspend (MutationRequest) -> MutationReply = { MutationReply.Deferred(it.operationId, DeferredReason.STORAGE_UNCERTAIN) }
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    private fun unexpected(operation: String): Nothing = throw AssertionError("Unexpected controlled opening client call: $operation")
    override val health: StateFlow<LocalMasterEndpointHealth> get() = unexpected("health")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = currentReply
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        assertTrue(request.query in setOf(LocalConfigurationProfile.QUERY, LocalOpeningSupplierObligationProfile.READ),
            "Opening UI must not read actual invoices, receipts, sales, stock or cash")
        reads += request
        return when (request.query) {
            LocalConfigurationProfile.QUERY -> f.supplierReply()
            LocalOpeningSupplierObligationProfile.READ -> openingReadReply(request)
            else -> unexpected("read")
        }
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        assertTrue(request.command == LocalOpeningSupplierObligationProfile.COMMAND,
            "Opening UI must use the separate obligation command")
        executions += request
        return executeReply(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return lookupReply(identity)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = unexpected("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = unexpected("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = unexpected("logout")
}
