package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Trusted OS/service composition only. No option here is evidence that a provider is qualified. */
public class LocalMasterSecurityDependencies(
    public val protection: DeviceProtectedLocalStorage,
    public val storageKey: ProtectedStorageKeyReference,
    public val passwords: LocalPasswordHasher,
    public val pepperReference: ProtectedPepperReference,
    public val entropy: LocalAuthenticationEntropy,
    public val environment: LocalAuthenticationEnvironment,
    public val peers: LocalAuthenticatedPeerSource,
    public val maximumConcurrentAuthentication: Int,
    public val provisioning: LocalProvisioningAuthority = UnavailableLocalProvisioningAuthority,
) {
    init { require(maximumConcurrentAuthentication in 1..64) }
    override fun toString(): String = "LocalMasterSecurityDependencies([REDACTED])"
}

/**
 * ONE concrete security store + source for verifier, auth/admin and command/read authorization.
 * The platform opener must pass [authorization] to its kernel over this exact database, not
 * build another source/store/pool. Exposed services still require the qualified clean-context
 * ingress (F35); no public method accepts a caller Room loan or claims arbitrary-context safety.
 * Lifetime retirement/OS process supervision (F36) belongs to the encompassing runtime.
 */
public class LocalMasterSecurity internal constructor(
    database: MasterRoomDatabase,
    private val scope: BranchScope,
    private val dependencies: LocalMasterSecurityDependencies,
    rpcSessionFence: LocalMasterRpcSessionFence = LocalMasterRpcSessionFence(),
) {
    init { database.runtimeHistoryOwner?.requireOwnedSecurity(dependencies) }
    private val environment = RoomLocalAuthorizationEnvironment {
        dependencies.environment.observe()?.let { RoomLocalAuthorizationObservation(it.issuerContextId, it.moment) }
    }
    // ONE atom/high-water for protected mutations, current authorization and physical delivery.
    // Internal only: neither a transport flag nor a second owner may enable the companion.
    internal val physicalInstructionRegistry = PhysicalInstructionPublicationRegistry(environment)
    private val store = RoomLocalSecurityStore(database, dependencies.protection, dependencies.storageKey,
        physicalInstructionRegistry, rpcSessionFence)
    private val source = RoomCurrentLocalAuthorizationSource(store, environment, physicalInstructionRegistry,
        handoffEntropy = dependencies.entropy)
    private val peers = RoomLocalAccessPeerSource { id ->
        dependencies.peers.capture(id)?.let { peer ->
            RoomLocalAccessPeerBinding(peer.connectionId, peer.scope, peer.deviceId,
                peer.publicKeyIdentifier, peer.publicFingerprint, peer::isCurrent)
        }
    }
    public val principalFactory: TrustedPrincipalFactory = TrustedPrincipalFactory(RoomLocalAccessPrincipalVerifier(
        scope, store, source, peers, dependencies.maximumConcurrentAuthentication))
    public val authorization: TransactionAuthorizationSource = source
    public val transactionApprovals: TransactionApprovalSource = RoomTransactionApprovalSource(store, source, dependencies.entropy)
    private val operations = RoomLocalAuthenticationService(scope, store, source, peers,
        dependencies.passwords, dependencies.entropy,
        dependencies.maximumConcurrentAuthentication)
    public val authentication: LocalAuthenticationService = operations
    private val credentialOperations = RoomLocalCredentialAdministration(scope, store, source, peers,
        principalFactory, dependencies.passwords, dependencies.pepperReference, dependencies.entropy,
        dependencies.provisioning, dependencies.maximumConcurrentAuthentication)
    public val credentials: LocalCredentialAdministration = credentialOperations
    public val administration: LocalAdministrationService = RoomLocalAdministrationService(scope, store, source, peers,
        principalFactory, dependencies.passwords, dependencies.pepperReference, credentials)
    private var approvalPreparation: LocalApprovalCommandPreparation? = null
    private var approvalService: LocalActionApprovalService? = null
    private var installationVerifier: LocalBranchInstallationVerifier? = null
    private var branchInitializer: LocalBranchInitializer? = null
    private var offlineInstallationContext: InstalledOfflineInstallationContext? = null
    private var offlineBranchInitializer: LocalBranchInitializer? = null

    internal fun originalBranchInitializer(owner: RoomRuntimeHistoryOwner): RoomSelfOwnedBranchInitializer =
        RoomSelfOwnedBranchInitializer(owner,store,source)

    internal val originalStore: RoomLocalSecurityStore get() = store
    internal val originalSource: RoomCurrentLocalAuthorizationSource get() = source
    internal fun originalSerialPublications(owner: RoomRuntimeHistoryOwner): RoomSelfOwnedSerialPublications =
        RoomSelfOwnedSerialPublications(owner, store, source, owner.originalCustody)
    internal fun originalEmployeeEnrollments(owner: RoomRuntimeHistoryOwner, native: LocalCompanyEmployeeEnrollments): RoomSelfOwnedEmployeeEnrollments =
        RoomSelfOwnedEmployeeEnrollments(owner, store, source, credentialOperations, native)
    internal fun originalEmployeePolicyActivations(owner: RoomRuntimeHistoryOwner,
        native: LocalCompanyEmployeePolicyActivations, endpoint: LocalMasterEndpoint): RoomSelfOwnedEmployeePolicyActivations =
        RoomSelfOwnedEmployeePolicyActivations(owner, store, source, credentialOperations, native, endpoint)
    internal fun originalDeviceAdmissions(owner: RoomRuntimeHistoryOwner,
        native: LocalCompanyDevicePairings, endpoint: LocalMasterEndpoint): RoomSelfOwnedDeviceAdmissions =
        RoomSelfOwnedDeviceAdmissions(owner, store, source, credentialOperations, native, endpoint)

    /** Narrow pre-start/current lifecycle ingress over the same store, source and peers. */
    internal fun originalLifecycle(owner: RoomRuntimeHistoryOwner): LocalMasterLifecycleService =
        RoomLocalMasterLifecycleService(scope, owner.originalRecord.record.allocation.coordinates, store, source, peers,
            principalFactory)

    /**
     * Trusted single-threaded native composition over a genuinely verified installed context.
     * EXACT object identity binds this owner's AEAD, factory-created same-pepper hasher and
     * current native environment; matching configuration strings/flags alone are insufficient.
     * The wrapper also matches actual admission descriptors/capabilities to the authenticated
     * device key. The unchanged delegate computes the COMPLETE definition digest and verifies
     * the exact signed request before any effect. One store/source/initializer/capacity owner.
     * No native OS/root provider, writer supervisor or independent runtime qualification here.
     */
    public fun createOfflineBranchInitializer(context: InstalledOfflineInstallationContext): LocalBranchInitializer {
        check(context.scope == scope && context.protection === dependencies.protection &&
            context.passwords === dependencies.passwords && context.environment === dependencies.environment &&
            context.storageKey == dependencies.storageKey && context.pepperReference == dependencies.pepperReference)
        offlineBranchInitializer?.let { check(offlineInstallationContext === context); return it }
        val delegate = createBranchInitializer(context.verifier)
        val service = object : LocalBranchInitializer {
            override suspend fun initialize(definition: LocalBranchInstallationDefinition, password: CharArray): LocalBranchInstallationResult {
                currentCoroutineContext().ensureActive()
                if (!context.matchesDefinition(definition)) return LocalBranchInstallationResult.Denied
                return delegate.initialize(definition, password)
            }

            override suspend fun lookup(definition: LocalBranchInstallationDefinition): LocalBranchInstallationResult {
                currentCoroutineContext().ensureActive()
                if (!context.matchesDefinition(definition)) return LocalBranchInstallationResult.Denied
                return delegate.lookup(definition)
            }
        }
        offlineInstallationContext = context
        offlineBranchInitializer = service
        return service
    }

    /**
     * Trusted single-threaded native/service composition only, never a transport-selected
     * verifier. The mandatory provider must genuinely verify human, package/device, custody,
     * fresh origin and current monotonic prerequisites; no accepting default exists here.
     * This reuses THIS owner's protected store/source and exposes no seed DAO or writer loan.
     * Creating or completing this service does not admit the independent master runtime gate.
     */
    public fun createBranchInitializer(verifier: LocalBranchInstallationVerifier): LocalBranchInitializer {
        branchInitializer?.let { check(installationVerifier === verifier); return it }
        val service = RoomLocalBranchInitializer(scope, store, source, dependencies.passwords,
            dependencies.pepperReference, dependencies.storageKey, verifier, dependencies.maximumConcurrentAuthentication)
        installationVerifier = verifier
        branchInitializer = service
        return service
    }

    /**
     * Trusted single-threaded process composition only. Supply this master's real kernel
     * preparation once; repeated access cannot create new independent capacity limiters.
     */
    public fun createApprovalService(preparation: LocalApprovalCommandPreparation): LocalActionApprovalService {
        approvalService?.let { check(approvalPreparation === preparation); return it }
        val service = RoomLocalActionApprovalService(scope, store, source, peers, principalFactory, dependencies.passwords,
            dependencies.entropy, preparation, dependencies.maximumConcurrentAuthentication)
        approvalPreparation = preparation
        approvalService = service
        return service
    }
}
