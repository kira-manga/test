package com.ukkera.pos.database.independent.approvalretirementnoadmission

import androidx.room3.Room
import androidx.room3.useWriterConnection
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Real endpoint scheduler over disposable current Room, with no branch/history seed,
 * no accepting verifier, handler, grant, principal, preview or receipt. The ordinary
 * detached builder/callbacks are retained. This cannot qualify native Master work.
 */
internal class ArnaRefusalOnlyEndpointFixture(dispatcher: CoroutineDispatcher) {
    private val directory = Files.createTempDirectory("arna-prequeue-")
    private val database = buildDurableRoom(
        Room.databaseBuilder<MasterRoomDatabase>(directory.resolve("master.sqlite").toString(),
            MasterRoomConstructor::initialize)
            .addCallback(ProtectedLocalSchemaCallback)
            .addCallback(LocalAccessIssuanceSchemaCallback)
            .addCallback(MasterReplacementGuardsCallback)
            .addCallback(LocalAuthenticationSchemaCallback)
            .addCallback(LocalActionApprovalSchemaCallback),
    )
    private val store = AtomicMasterStore(database, ArnaV1Inputs.scope,
        AuthorityIdentity(ArnaV1Inputs.carrier(102), ArnaV1Inputs.carrier(103), Revision(1)),
        emptyList(), UnavailableTransactionAuthorization, UnqualifiedMasterRuntime,
        MasterClock { UtcInstant.parseCanonical("2026-10-23T12:00:00Z") })
    val approvals = ArnaRefusingApprovalService()
    private val authentication = ArnaRefusingAuthentication()
    private val administration = ArnaRefusingCredentialAdministration()
    val retirements = mutableListOf<LocalMasterRetirementReason>()
    // Open/snapshot disposable Room BEFORE scheduling a worker. The first actual
    // ingress starts the genuine endpoint, allowing a deterministic prequeue cut.
    private val endpointOwner = lazy {
        LocalMasterEndpoint(store, TrustedPrincipalFactory(DenyAllPrincipalVerifier),
            authentication, administration, approvals, LocalMasterRetirement { retirements += it },
            watchdogDispatcher = dispatcher, dispatcher = dispatcher, workers = 1, maximumQueuedRequests = 1)
    }
    val endpoint: LocalMasterEndpoint get() = endpointOwner.value

    suspend fun retire(attempt: LocalActionApprovalRetirementAttempt,
        request: MutationRequest): LocalActionApprovalRetireResult {
        val credentials = ArnaV1Inputs.credentials()
        return try { endpoint.retireActionApprovalAttempt(attempt, credentials, request) }
        finally { credentials.bearer.destroy() }
    }

    suspend fun retireLegacy(request: MutationRequest): LocalActionApprovalRetireResult {
        val credentials = ArnaV1Inputs.credentials()
        return try { endpoint.retireActionApproval(ArnaV1Inputs.id("approval-A"), credentials,
            request, ArnaV1Inputs.confirmation()) }
        finally { credentials.bearer.destroy() }
    }

    /** Exact row counts of every disposable application table; excludes Room/SQLite metadata. */
    suspend fun rowCounts(): Map<String, Long> = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        val tables = connection.usePrepared("SELECT name FROM sqlite_master WHERE type='table' " +
            "AND name NOT LIKE 'sqlite_%' AND name!='room_master_table' ORDER BY name") { query ->
            buildList { while (query.step()) add(query.getText(0)) }
        }
        tables.associateWith { table ->
            require(table.all { it.isLetterOrDigit() || it == '_' })
            connection.usePrepared("SELECT count(*) FROM `$table`") { query ->
                check(query.step())
                query.getLong(0).also { check(!query.step()) }
            }
        }
    }

    fun unrelatedServiceCalls(): Int = authentication.calls + administration.calls + approvals.otherCalls

    suspend fun shutdown() {
        try {
            if (endpointOwner.isInitialized()) {
                endpoint.stopAccepting()
                endpoint.awaitQuiescence()
            }
        } finally {
            try { database.close() } finally { directory.toFile().deleteRecursively() }
        }
    }
}

internal data class ArnaObservedRetirement(val approvalId: DomainId, val request: MutationRequest,
    val confirmation: PayloadDigest)

internal class ArnaRefusingApprovalService : LocalActionApprovalService {
    val started = mutableListOf<ArnaObservedRetirement>()
    val completed = mutableListOf<ArnaObservedRetirement>()
    var reply: LocalActionApprovalRetireResult = LocalActionApprovalRetireResult.Denied
    var beforeReturn: suspend (ArnaObservedRetirement) -> Unit = {}
    var otherCalls = 0

    override suspend fun retire(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest): LocalActionApprovalRetireResult {
        val call = ArnaObservedRetirement(approvalId, request, confirmationDigest)
        started += call
        beforeReturn(call)
        completed += call
        return reply
    }
    override suspend fun preview(requestor: LocalGatewayCredentials, request: MutationRequest,
        retiredApprovalId: DomainId?): LocalActionApprovalPreviewResult {
        otherCalls++
        return LocalActionApprovalPreviewResult.Unavailable
    }
    override suspend fun issue(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest, approverLogin: LocalLoginIdentifier,
        approverPassword: CharArray, retiredApprovalId: DomainId?): LocalActionApprovalIssueResult {
        otherCalls++
        return LocalActionApprovalIssueResult.Unavailable
    }
    override suspend fun lookup(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus {
        otherCalls++
        return LocalActionApprovalStatus.Unavailable
    }
}

private class ArnaRefusingAuthentication : LocalAuthenticationService {
    var calls = 0
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier,
        password: CharArray, explicitDeviceMove: Boolean): LocalAuthenticationResult {
        calls++; return LocalAuthenticationResult.Unavailable
    }
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        calls++; return LocalAuthenticationResult.Unavailable
    }
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        calls++; return LocalSessionViewResult.Unavailable
    }
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult {
        calls++; return LocalSignOutResult.Unavailable
    }
}

private class ArnaRefusingCredentialAdministration : LocalCredentialAdministration {
    var calls = 0
    private fun unavailable(): LocalCredentialAdministrationResult {
        calls++; return LocalCredentialAdministrationResult.Unavailable
    }
    private fun unknown(): LocalProvisioningStatus {
        calls++; return LocalProvisioningStatus.Unavailable
    }
    override suspend fun provisionAccount(requestId: DomainId, definition: LocalAccountProvisioning,
        password: CharArray?): LocalCredentialAdministrationResult = unavailable()
    override suspend fun admitDevice(requestId: DomainId,
        admission: LocalDeviceAdmission): LocalCredentialAdministrationResult = unavailable()
    override suspend fun revokeDevice(operator: LocalGatewayCredentials, requestId: DomainId,
        deviceId: DomainId, expectedRevision: Revision): LocalCredentialAdministrationResult = unavailable()
    override suspend fun lookupAccountProvisioning(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalProvisioningStatus = unknown()
    override suspend fun lookupDeviceAdmission(requestId: DomainId,
        admission: LocalDeviceAdmission): LocalProvisioningStatus = unknown()
    override suspend fun activatePolicy(requestId: DomainId,
        nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult = unavailable()
    override suspend fun lookupPolicyActivation(requestId: DomainId,
        nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus = unknown()
    override suspend fun reactivateAccount(requestId: DomainId,
        target: LocalAccountReactivation): LocalCredentialAdministrationResult = unavailable()
    override suspend fun lookupAccountReactivation(requestId: DomainId,
        target: LocalAccountReactivation): LocalProvisioningStatus = unknown()
    override suspend fun resetCredential(operator: LocalGatewayCredentials, actorId: DomainId,
        expectedRevision: Revision, newPassword: CharArray): LocalCredentialAdministrationResult = unavailable()
    override suspend fun revokeSession(operator: LocalGatewayCredentials, actorId: DomainId,
        sessionId: DomainId, expectedRevision: Revision): LocalCredentialAdministrationResult = unavailable()
    override suspend fun disableAccount(operator: LocalGatewayCredentials, actorId: DomainId,
        expectedRevision: Revision): LocalCredentialAdministrationResult = unavailable()
}
