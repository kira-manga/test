package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.LocalEnrollmentMode
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.LocalEnrollmentUiGateway
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidLocalEmployeeEnrollmentConnection
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.readOriginalBusinessPublications
import com.ukkera.pos.security.readOriginalPurchasingPolicies
import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import com.ukkera.pos.database.AndroidEmployeeClientCustody

/**
 * Shared same-device original (cohost) client runtime of the actual Master, used by the Android
 * application and the desktop Master service. It uses the real master, protected journals and the
 * existing authentication/workflow gateway. No fixture identities, synthetic principal, loopback
 * network, storage provider or permissive signature verifier enters this composition.
 *
 * Platforms supply only their genuine cohost journal custody ([SameCohostClientJournals]) and
 * an optional real retail physical binding; missing physical acquisition remains unavailable,
 * never replacement authority or a payment/refund outcome. Employee intents require separately
 * consumed native enrollment; there is no first-actor fallback. Shared setup delegates complete
 * head/policy ceremonies to this actual owner.
 */
public open class LocalCohostBranchRuntime internal constructor(
    private val original: AndroidOriginalLocalClient,
    private val journals: SameCohostClientJournals,
    retailPhysical: LocalRetailPhysicalBinding?,
    publications: LocalCompanyBusinessPublications?,
) {
    private val connections = MutableStateFlow<LocalMasterClientConnection?>(LocalMasterClientConnection(
        original.client, original.peer, original.serviceIncarnationId,
        LocalWorkflowInstallation(original.commandKeys, original.readKeys),
        LocalBranchDisplayLabels(original.peer.scope, original.companyName, original.material.branchName),
        retailPhysicalBinding = retailPhysical, administrationClient = original.administration,
        companyPublications = publications,
        installationId = original.material.coordinates.installationId.takeIf {
            original.compositionMode == com.ukkera.pos.security.LocalSetupCompositionMode.LIVE },
        setupOriginalBinding = if (original.compositionMode != com.ukkera.pos.security.LocalSetupCompositionMode.LIVE) null
            else com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1(
            original.material.coordinates.installationId, original.receipt.core.originalRequestId,
            original.receipt.core.issuedAttemptId, original.receipt.core.attemptSequence,
            original.receipt.core.definitionDigest, original.receipt.genesis, original.receipt.digest),
        currentLifetime = original::isCurrent))
    private val gateway = LocalEndpointBranchGateway(connections, journals.credentials, journals)
    private val employeePublications = MutableStateFlow<LocalEnrollmentPublication?>(null)
    private val employeeGateway = LocalEndpointEnrollmentGateway(connections, journals.credentials, employeePublications)
    private val publicationGate = Any()
    public val branchGateway: BranchUiGateway get() = gateway
    /** Dedicated setup must use the native-retained original ID. The older minimal form
     * generates a fresh ID and is NOT connected to this explicit full-policy ceremony. */
    public val employeeEnrollmentGateway: LocalEnrollmentUiGateway get() = employeeGateway
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)

    public fun isCurrent(): Boolean = closure.get() == null && connections.value?.isCurrent() == true

    /** Immediate client unpublication; never closes the master or discards pending evidence. */
    public fun denyNewWork() {
        synchronized(publicationGate) { employeePublications.value = null; connections.value = null }
        employeeGateway.close(); gateway.close()
    }

    /** Explicit complete policy/configuration/devices, with employee-password presence.
     * The caller retains the original request/action intent before head authentication;
     * no role presets, omitted limits, head session or new device is supplied here. */
    public suspend fun prepareEmployeeEnrollment(requestId: DomainId, definition: LocalAccountProvisioning):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.prepareEmployeeEnrollment(requestId, definition, credentialWasProvided = true)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Head buffer remains caller-owned; the actual retained service task scrubs its own
     * copy. Cancellation abandons delivery only; recover the original approval/action. */
    public suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        return when (val result = original.approveEmployeeEnrollment(action, headLogin, headSecret)) {
            is LocalCompanyAuthorityResult.Value -> if (publishApprovedEmployee(result.value)) result else employeeUnavailable()
            is LocalCompanyAuthorityResult.Unavailable -> result
        }
    }

    /** Read-only recovery of the actual original APPROVED output; no new head verification,
     * signature, definition, request/action ID or employee credential operation. */
    public suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId):
        LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        return when (val result = original.recoverEmployeeEnrollmentApproval(originalActionId)) {
            is LocalCompanyAuthorityResult.Value -> if (publishApprovedEmployee(result.value)) result else employeeUnavailable()
            is LocalCompanyAuthorityResult.Unavailable -> result
        }
    }

    /** Re-publishes the exact late-phase original lookup port after lost delivery/cold open.
     * The newly recovered connection rejects enrollment/password dispatch. Native consumed
     * custody remains a separate full-history/close/admission-joined acquisition. */
    public suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId):
        LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        return when (val result = original.recoverEmployeeEnrollmentConnection(originalActionId)) {
            is LocalCompanyAuthorityResult.Value -> if (publishApprovedEmployee(result.value)) result else employeeUnavailable()
            is LocalCompanyAuthorityResult.Unavailable -> result
        }
    }

    /** Read-only original PREPARED review after lost delivery. The native owner checks the
     * complete retained definition/target/head/boot/deadline; this never re-prepares or signs. */
    public suspend fun reviewEmployeeEnrollment(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.reviewEmployeeEnrollment(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Native-owned original identities only, so UI can join its preceding durable request
     * intent after UNKNOWN. A reference/list is no authority, custody or absence proof. */
    public suspend fun retainedEmployeeEnrollments(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.retainedEmployeeEnrollments()
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Native owner delegation only. The original service independently validates the
     * complete policy/predecessor, dedicated head approval and actual writer authority. */
    public suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.prepareEmployeePolicy(requestId, nextPolicy)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** This setup port consumes its caller-owned buffer even on stale-owner/cancellation.
     * The retained native task uses its separately owned cleaned copy, not this buffer. */
    public suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return employeeUnavailable()
            val result = original.approveEmployeePolicy(action, headLogin, headSecret)
            return if (isCurrent()) result else employeeUnavailable()
        } finally { headSecret.fill('\u0000') }
    }

    public suspend fun reviewEmployeePolicy(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.reviewEmployeePolicy(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    public suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.recoverEmployeePolicyApproval(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    public suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.recoverEmployeePolicyConnection(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    public suspend fun retainedEmployeePolicies():
        LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.retainedEmployeePolicies()
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** A retired delivery cannot report an uncommitted refusal after native dispatch.
     * The original request remains lookup-only until its actual result is established. */
    public suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return LocalCredentialAdministrationResult.Unavailable
        val result = original.activateEmployeePolicy(approval, nextPolicy)
        return if (isCurrent()) result else LocalCredentialAdministrationResult.StorageUncertain
    }

    public suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return LocalProvisioningStatus.Unavailable
        val result = original.lookupEmployeePolicy(approval, nextPolicy)
        return if (isCurrent()) result else LocalProvisioningStatus.Unavailable
    }

    /** Fresh SDK-bound preparation through this same current native Master/client. */
    public suspend fun prepareSdkBoundDevicePairing(requestId: DomainId, sdkIdentity: LocalDevicePairingSdkIdentityV2):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.prepareSdkBoundDevicePairing(requestId, sdkIdentity)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Only the actual original native action can advance to its target-proved handoff. */
    public suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction,
        possession: LocalDevicePairingPossession): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.proveDevicePairingTarget(action, possession)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Retained originals on this exact native Master/client only; no target or Kit creation. */
    public suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.retainedDevicePairings()
        return if (isCurrent()) result else employeeUnavailable()
    }

    public suspend fun reviewDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.reviewDevicePairing(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Explicit original head ceremony only. Every exit consumes the caller's secret buffer. */
    public suspend fun approveDevicePairing(action: LocalDevicePairingAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return employeeUnavailable()
            val result = original.approveDevicePairing(action, headLogin, headSecret)
            return if (isCurrent()) result else employeeUnavailable()
        } finally { headSecret.fill('\u0000') }
    }

    /** Exact original approval DATA, including its original expiry; never re-signs or renews. */
    public suspend fun recoverDevicePairingApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.recoverDevicePairingApproval(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Explicit retirement remains bound to this original native Master/client. */
    public suspend fun retireKnownUnsignedDevicePairing(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.retireKnownUnsignedDevicePairing(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** Existing exact retirement DATA only; never signs or renews the original. */
    public suspend fun recoverUnsignedDevicePairingRetirement(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return employeeUnavailable()
        val result = original.recoverUnsignedDevicePairingRetirement(originalActionId)
        return if (isCurrent()) result else employeeUnavailable()
    }

    /** The native producer alone can consume the original. Stale delivery cannot prove refusal. */
    public suspend fun admitOriginalDevice(approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return LocalCredentialAdministrationResult.Unavailable
        val result = original.admitOriginalDevice(approval)
        return if (isCurrent()) result else LocalCredentialAdministrationResult.StorageUncertain
    }

    public suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return LocalProvisioningStatus.Unavailable
        val result = original.lookupOriginalDeviceAdmission(originalActionId)
        return if (isCurrent()) result else LocalProvisioningStatus.Unavailable
    }

    private fun publishApprovedEmployee(approved: AndroidLocalEmployeeEnrollmentConnection): Boolean = synchronized(publicationGate) {
        val connection = connections.value ?: return@synchronized false
        if (!isCurrent() || !approved.belongsTo(original) || !approved.requested.credentialWasProvided ||
            original.originalEmployeeEnrollment(approved.originalRequestId) !== approved) return@synchronized false
        val port = original.originalEmployeeEnrollmentClient() ?: return@synchronized false
        if (!port.belongsTo(original) || !port.contains(approved)) return@synchronized false
        val offering = LocalEnrollmentOffering(journals.credentials.owner, LocalEnrollmentMode.OWNER_AUTHORIZED_LOCAL,
            if (approved.isLookupOnly) LocalText("Original employee enrollment lookup only; dispatch cannot be repeated.",
                "استعلام عن إنشاء حساب الموظف الأصلي فقط؛ لا يمكن إعادة الإرسال.")
            else LocalText("Company head approval for the exact retained local employee definition. Enrollment is not sign-in.",
                "موافقة مسؤول الشركة على تعريف الموظف المحلي المحفوظ بالكامل. إنشاء الحساب لا يسجل دخول الموظف."),
            LocalEnrollmentDefinitionSource(approved::originalDefinition),
            currentLifetime = { isCurrent() && approved.belongsTo(original) && port.contains(approved) })
        employeePublications.value = LocalEnrollmentPublication(offering, LocalMasterEnrollmentConnection(connection, port))
        true
    }

    /** Uses the approved ORIGINAL request/login, retaining the existing encrypted complete
     * definition before password dispatch. Repeated/uncertain calls are same-ID lookup only.
     * This consumes the supplied password but creates no session or employee journal. */
    public suspend fun enrollApprovedEmployee(originalRequestId: DomainId, password: CharArray): LocalEnrollmentResult {
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return LocalEnrollmentResult.Rejected(LocalServiceProblem.UNAVAILABLE)
            val approved = original.originalEmployeeEnrollment(originalRequestId)
                ?: return LocalEnrollmentResult.Rejected(LocalServiceProblem.UNAVAILABLE)
            if (approved.isLookupOnly) return LocalEnrollmentResult.Rejected(LocalServiceProblem.UNAVAILABLE)
            return employeeGateway.enroll(originalRequestId, approved.requested.definition.login.value, "", password)
        } finally { password.fill('\u0000') }
    }

    public suspend fun lookupEmployeeEnrollment(originalRequestId: DomainId): LocalEnrollmentResult =
        employeeGateway.lookup(originalRequestId)

    /** Separate genuine consumed-child custody; opens only this employee's intent journal.
     * Device credentials stay on their original owner. It never signs in, restores a bearer,
     * inherits the head, opens a competing credential owner or creates on actor lookup. */
    public suspend fun acquireEmployeeIntentJournal(originalRequestId: DomainId,
        action: NativeEmployeeClientJournalAction): Boolean {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return false
        val enrollment = original.originalEmployeeEnrollment(originalRequestId) ?: return false
        val custody = when (val selected = enrollment.acquireEmployeeClient()) {
            is LocalCompanyAuthorityResult.Value -> selected.value
            is LocalCompanyAuthorityResult.Unavailable -> return false
        }
        if (!isCurrent() || !custody.belongsTo(original)) return false
        val acquired = journals.acquireEmployee(custody, action)
        if (acquired && isCurrent()) {
            // Physical absence/refusal cannot invalidate an independently genuine nonphysical
            // client intent journal. Only the exact private cohost entry attempts that join.
            try { journals.admitEmployeePhysical(custody) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep only physical work unavailable; never substitute custody. */ }
        }
        return acquired && isCurrent()
    }

    private fun employeeUnavailable(): LocalCompanyAuthorityResult.Unavailable =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE,
            SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)

    public suspend fun close(): Unit = withContext(NonCancellable) {
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        var failure: Throwable? = null
        fun retain(next: Throwable) {
            val first = failure
            if (first == null) failure = next else if (first !== next) first.addSuppressed(next)
        }
        // Every client-owned drain/close is visited even if an earlier one fails. This
        // releases no master/native branch custody and never reports a failed close as done.
        try { denyNewWork() } catch (next: Throwable) { retain(next) }
        try { gateway.closeAndAwait() } catch (next: Throwable) { retain(next) }
        try { employeeGateway.closeAndAwait() } catch (next: Throwable) { retain(next) }
        try { journals.close() } catch (next: Throwable) { retain(next) }
        val first = failure
        if (first == null) result.complete(Unit) else result.completeExceptionally(first)
        result.await()
    }

    override fun toString(): String = "LocalCohostBranchRuntime([REDACTED])"
}

/** Platform cohost journal custody: the original device credential/first-actor intent owners and
 * separately consumed employee intent owners. Never creates/reopens on actor lookup. */
internal interface SameCohostClientJournals : LocalClientIntentJournalSource {
    val credentials: LocalClientCredentialJournal
    suspend fun acquireEmployee(custody: AndroidEmployeeClientCustody, action: NativeEmployeeClientJournalAction): Boolean
    /** Physical participation join; platforms without physical custody return false. */
    suspend fun admitEmployeePhysical(custody: AndroidEmployeeClientCustody): Boolean
    suspend fun close()
}
