package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalCompositionSdkProfileV1
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.LocalMasterLifecycleResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingTargetDescriptor
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Trusted composition plumbing, NOT authorization by interface. Production adapters call the
 * real native factories and retain their exact owners. The common coordinator never constructs
 * native permits, replaces a missing database or makes a caller-supplied receipt authoritative.
 * Client publication must reject handles not created by this same actual platform adapter.
 */
public interface LocalSetupNativeServices {
    /**
     * Exact fixed composition profile of this concrete native stack. A missing declaration
     * disables new pairing effects in every mode; no LIVE profile is inferred.
     */
    public val compositionProfile: LocalCompositionSdkProfileV1? get() = null
    public suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult
    public suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult
    /** A distinct fresh physical target, never the installation/cohost target above. */
    public suspend fun prepareFreshDeviceTarget(scope: BranchScope, capabilities: Set<String>): LocalSetupFreshDeviceTargetOpenResult =
        LocalSetupFreshDeviceTargetOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    /** Existing-only native target recovery; absence cannot invoke the creating factory. */
    public suspend fun openOriginalFreshDeviceTarget(): LocalSetupFreshDeviceTargetOpenResult =
        LocalSetupFreshDeviceTargetOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    public suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult
    /** Real installation-only native+Room owner for pre-start current-authority lifecycle work. */
    public suspend fun openInstalledLifecycle(context: SelfOwnedLocalSecurityContext): LocalSetupLifecycleOpenResult =
        LocalSetupLifecycleOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    public suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult
    public suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient?
    public fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean
    public fun publishClient(owner: Any, client: LocalSetupClient)
    public fun withdrawClient(owner: Any)
    /** Publish only the exact real gateway retained by this same native Client adapter. */
    public fun publishLanAuthentication(owner: Any, client: LocalSetupLanClient,
        authentication: LocalSetupLanAuthentication): Unit =
        throw UnsupportedOperationException("LAN authentication is unavailable")
    /** Native retention must precede construction; exceptions leave custody unresolved. */
    public fun retainLanHost(master: LocalSetupMaster, scope: CoroutineScope,
        settings: LocalSetupLanSettings): LocalSetupLanHost = throw UnsupportedOperationException("LAN Host is unavailable")
    /** The exact fresh target and its original native pairing action, never caller-supplied trust. */
    public fun retainLanClient(target: LocalSetupFreshDeviceTarget, scope: CoroutineScope,
        originalPairingActionId: DomainId, settings: LocalSetupLanSettings): LocalSetupLanClient =
        throw UnsupportedOperationException("LAN Client is unavailable")
}

/** Exact native target owner plumbing. Public descriptors and results remain DATA. */
public interface LocalSetupFreshDeviceTarget {
    /** Exact fixed profile of this native target; null is a non-live fail-closed no-claim. */
    public val compositionProfile: LocalCompositionSdkProfileV1? get() = null
    public suspend fun original(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference>
    public suspend fun descriptor(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetDescriptor>
    public suspend fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2>
    public suspend fun recoverSdkIdentity(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2>
    public suspend fun respond(challenge: LocalDevicePairingChallenge): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession>
    public suspend fun recoverPossession(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession>
    public suspend fun importApprovedMaster(transfer: LocalDevicePairingMasterTransfer):
        LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> =
        LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    public suspend fun recoverApprovedMaster(originalPairingActionId: DomainId):
        LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> =
        LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    public suspend fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference>
    /** Exact completed current allocation DATA only; unsupported adapters supply no correlation. */
    public suspend fun recoverAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement):
        LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> =
        LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    public fun denyNewWork()
    /** Awaited actual close. An exception never releases the setup service's owner slot. */
    public suspend fun close()
}

public sealed interface LocalSetupFreshDeviceTargetOpenResult {
    public class Opened(public val target: LocalSetupFreshDeviceTarget) : LocalSetupFreshDeviceTargetOpenResult
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1,
        public val original: LocalDevicePairingTargetReference? = null) : LocalSetupFreshDeviceTargetOpenResult
}

public sealed interface LocalSetupFreshDeviceTargetResult<out T> {
    public class Value<T>(public val value: T) : LocalSetupFreshDeviceTargetResult<T>
    public data class Unavailable(public val reason: SelfOwnedInstallationFailureV1) : LocalSetupFreshDeviceTargetResult<Nothing>
}

/** Bounded full-document DATA only. Picker/I/O callbacks never invoke a native ceremony. */
public interface LocalSetupPairingDocuments {
    public suspend fun importDocument(kind: LocalSetupPairingDocumentKind,
        isCurrent: () -> Boolean): LocalSetupPairingDocumentImportResult
    /** Bytes are the exact retained native original. The adapter never generates a proof. */
    public suspend fun exportDocument(kind: LocalSetupPairingDocumentKind, bytes: ByteArray,
        isCurrent: () -> Boolean): LocalSetupPairingDocumentExportResult
}

public sealed interface LocalSetupPairingDocumentImportResult {
    public class Completed(public val kind: LocalSetupPairingDocumentKind, bytes: ByteArray) : LocalSetupPairingDocumentImportResult {
        init { require(bytes.size in 1..kind.maximumBytes) }
        private val retained = bytes.copyOf()
        public val byteCount: Int get() = retained.size
        public fun copyBytes(): ByteArray = retained.copyOf()
        override fun toString(): String = "LocalSetupPairingDocumentImportResult.Completed([REDACTED])"
    }
    public data object Cancelled : LocalSetupPairingDocumentImportResult
    public data class Unavailable(public val problem: LocalSetupProblem = LocalSetupProblem.NATIVE_UNAVAILABLE) : LocalSetupPairingDocumentImportResult
}

public sealed interface LocalSetupPairingDocumentExportResult {
    public data object Completed : LocalSetupPairingDocumentExportResult
    public data object Cancelled : LocalSetupPairingDocumentExportResult
    public data class Unavailable(public val problem: LocalSetupProblem = LocalSetupProblem.NATIVE_UNAVAILABLE) : LocalSetupPairingDocumentExportResult
}

/** Application workflow handle only. Storage/native authority remains inside its actual adapter. */
public interface LocalSetupMaster {
    /** Existing-only binding DATA observed through the actual current Master-owned custody. */
    public suspend fun readOwnedOriginalNativeBinding(): com.ukkera.pos.security.SelfOwnedNativeReadResult =
        com.ukkera.pos.security.SelfOwnedNativeReadResult.Unresolved(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    /** Desktop (Windows/macOS) V2 counterpart; Android Masters keep the unavailable default. */
    public suspend fun readOwnedOriginalNativeBindingV2(): com.ukkera.pos.security.SelfOwnedNativeReadResultV2 =
        com.ukkera.pos.security.SelfOwnedNativeReadResultV2.Unresolved(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    public val mode: StateFlow<LocalMasterLifecycleMode>
    public suspend fun permitsFirstClientEnrollment(): Boolean
    public suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning, authorityEpoch: Revision): LocalBranchInstallationSnapshotV2
    public suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
        headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome
    public suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome
    public suspend fun readOriginalSetup(): LocalOriginalInstallationSetup
    /** Explicit current-authority lifecycle action on this same operational owner. */
    public suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult =
        LocalMasterLifecycleResult.Unavailable
    public fun denyNewWork()
    public suspend fun quiesceAndClose(): Boolean
}

/**
 * Installation-only native+Room lifetime used before an operational endpoint exists. It exposes
 * no client, handler, RPC binding or business intake. Its only action is the authenticated
 * current-authority lifecycle ingress over the same protected owner/security.
 */
public interface LocalSetupLifecycleMaster {
    public val mode: StateFlow<LocalMasterLifecycleMode>
    public suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult
    public fun denyNewWork()
    public suspend fun quiesceAndClose(): Boolean
}

public sealed interface LocalSetupLifecycleOpenResult {
    public class Opened(public val lifecycle: LocalSetupLifecycleMaster) : LocalSetupLifecycleOpenResult
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1) : LocalSetupLifecycleOpenResult
}

public sealed interface LocalSetupMasterOpenResult {
    public class Opened(public val master: LocalSetupMaster) : LocalSetupMasterOpenResult
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1) : LocalSetupMasterOpenResult
}

/** These are already authenticated native outputs; the port never accepts a supplied approval. */
public interface LocalSetupEmployeeConnection {
    public val requested: LocalEmployeeEnrollmentDefinition
    public val approval: LocalEmployeeEnrollmentAuthorityReceipt
    public val originalRequestId: DomainId
    public val originalActionId: DomainId
    public val actorId: DomainId
}

public interface LocalSetupClient {
    public fun isCurrent(): Boolean
    public fun denyNewWork()
    public suspend fun close()
    public suspend fun prepareEmployeeEnrollment(requestId: DomainId, definition: LocalAccountProvisioning):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction>
    public suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<LocalSetupEmployeeConnection>
    public suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalSetupEmployeeConnection>
    public suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalSetupEmployeeConnection>
    public suspend fun reviewEmployeeEnrollment(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction>
    public suspend fun retainedEmployeeEnrollments(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>>
    public suspend fun enrollApprovedEmployee(originalRequestId: DomainId, password: CharArray): LocalEnrollmentResult
    public suspend fun lookupEmployeeEnrollment(originalRequestId: DomainId): LocalEnrollmentResult
    public suspend fun acquireEmployeeIntentJournal(originalRequestId: DomainId, action: NativeEmployeeClientJournalAction): Boolean

    /** Complete replacement DATA. Only the actual native owner validates its retained predecessor. */
    public suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Named-head approval of the exact retained definition; never activation or employee sign-in. */
    public suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        headSecret.fill('\u0000')
        return LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    }
    public suspend fun reviewEmployeePolicy(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Exact late-phase receipt DATA; cannot re-sign, dispatch or renew effect authority. */
    public suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun retainedEmployeePolicies(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** The same sole writer checks the original signed history, current authority and policy CAS. */
    public suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Unavailable
    /** Original lookup only. An unavailable implementation supplies no absence or success. */
    public suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus = LocalProvisioningStatus.Unavailable

    /** Actual retained native originals only. Defaults stay unavailable, never a generic fallback. */
    public suspend fun prepareSdkBoundDevicePairing(requestId: DomainId, identity: LocalDevicePairingSdkIdentityV2):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction, possession: LocalDevicePairingPossession):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun reviewDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun approveDevicePairing(action: LocalDevicePairingAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        headSecret.fill('\u0000')
        return LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    }
    public suspend fun recoverDevicePairingApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Separately explicit actual-owner disposition of the exact known-unsigned original. */
    public suspend fun retireKnownUnsignedDevicePairing(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    /** Existing completed terminal DATA only; never invokes retirement or renews its authority. */
    public suspend fun recoverUnsignedDevicePairingRetirement(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    public suspend fun admitOriginalDevice(approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult =
        LocalCredentialAdministrationResult.Unavailable
    public suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalProvisioningStatus = LocalProvisioningStatus.Unavailable
}

/** OS scheduling/monotonic observation only; NOT native security-clock qualification. */
public interface LocalSetupExecution {
    public val dispatcher: CoroutineDispatcher
    /** Same process lifetime, monotonic and including suspended time where the OS supports it. */
    public fun elapsedMillis(): Long
    /** Independent bounded-cadence observer; blocking storage must not occupy its scheduler. */
    public fun startWatching(tick: () -> Unit)
    public fun stopWatching()
}
