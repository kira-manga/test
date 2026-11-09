package com.ukkera.pos.android.independent.retailhandoffhistoryv1

import com.ukkera.pos.database.AndroidLocalEmployeeEnrollmentConnection
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.inventory.store.LocalStockCostPage
import com.ukkera.pos.inventory.store.LocalStockQuantityPage
import com.ukkera.pos.inventory.store.LocalStockReceiptView
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalCashSessionViewV1
import com.ukkera.pos.sales.store.LocalProfiledOrderV2
import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyAuthorityView
import com.ukkera.pos.security.LocalCompanyFoundingAction
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalProvisionerGrant
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalProvisionerPreparation
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * In-memory preparation record. Its native results are the actual returned objects, not
 * manufactured receipts. It contains NO password or bearer. It is not a protected client
 * journal, a crash-durable checkpoint, historical admission or a whole-case test verdict.
 * A host must retain the plan/original intent before dispatch and preserve this record on
 * failure. Process-loss/restart qualification is deliberately outside this preparation helper.
 */
internal class NativeRetailFixtureCheckpoint {
    var phase: NativeRetailFixturePhase = NativeRetailFixturePhase.NEW
        internal set
    var step: String = "not_invoked"
        internal set
    var hold: NativeRetailFixtureHold? = null
        internal set

    var provisionerOpen: LocalProvisionerOpenResult? = null
        internal set
    var foundingPreparation: LocalCompanyAuthorityResult<LocalCompanyFoundingAction>? = null
        internal set
    var foundingCompletion: LocalCompanyAuthorityResult<LocalCompanyAuthorityView>? = null
        internal set
    var branchPreparation: LocalProvisionerPreparation? = null
        internal set
    var branchGrant: LocalProvisionerGrant? = null
        internal set
    var targetPreparation: SelfOwnedNativeOpenResult? = null
        internal set
    var allocatedMaterial: SelfOwnedNativeMaterialView? = null
        internal set
    var installationOpen: SelfOwnedNativeOpenResult? = null
        internal set
    var installationMasterOpen: AndroidSelfOwnedMasterOpenResult? = null
        internal set
    var installationMode: LocalMasterLifecycleMode? = null
        internal set
    var firstAccount: LocalAccountProvisioning? = null
        internal set
    var originalSetup: LocalOriginalInstallationSetup? = null
        internal set
    var installationDefinition: LocalBranchInstallationSnapshotV2? = null
        internal set
    var installationOutcome: LocalMasterInstallationOutcome? = null
        internal set
    var acknowledgementOpen: SelfOwnedNativeOpenResult? = null
        internal set
    var acknowledgementMasterOpen: AndroidSelfOwnedMasterOpenResult? = null
        internal set
    var acknowledgementOutcome: LocalMasterInstallationOutcome? = null
        internal set
    var runtimeOpen: SelfOwnedNativeOpenResult? = null
        internal set
    var runtimeMasterOpen: AndroidSelfOwnedMasterOpenResult? = null
        internal set
    var runtimeMode: LocalMasterLifecycleMode? = null
        internal set
    var currentOriginalMaterial: SelfOwnedNativeMaterialView? = null
        internal set
    var secondAccount: LocalAccountProvisioning? = null
        internal set
    var enrollmentPreparationB: LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction>? = null
        internal set
    var enrollmentApprovalB: LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection>? = null
        internal set
    var enrollmentOutcomeB: LocalCredentialAdministrationResult? = null
        internal set
    var enrollmentLookupB: LocalProvisioningStatus? = null
        internal set
    var publicationProposal: CompanyBusinessPublicationProposal? = null
        internal set
    var publicationPreparation: LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>? = null
        internal set
    var publicationApproval: LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput>? = null
        internal set
    var stockReceipt: LocalStockReceiptView? = null
        internal set
    var stockBeforeOrder: NativeRetailFixtureStockObservation? = null
        internal set
    var stockAfterOrder: NativeRetailFixtureStockObservation? = null
        internal set
    var drawerSessionA: LocalCashSessionViewV1? = null
        internal set
    var drawerSessionB: LocalCashSessionViewV1? = null
        internal set
    var orderR: LocalProfiledOrderV2? = null
        internal set

    internal val commandValues: MutableList<NativeRetailFixtureCommandAttempt> = mutableListOf()
    internal val readValues: MutableList<NativeRetailFixtureReadAttempt> = mutableListOf()
    internal val authenticationValues: MutableList<NativeRetailFixtureAuthenticationAttempt> = mutableListOf()
    internal val signOutValues: MutableList<NativeRetailFixtureSignOutAttempt> = mutableListOf()
    internal val closeValues: MutableList<NativeRetailFixtureCloseAttempt> = mutableListOf()
    val commands: List<NativeRetailFixtureCommandAttempt> get() = commandValues.toList()
    val reads: List<NativeRetailFixtureReadAttempt> get() = readValues.toList()
    val authentications: List<NativeRetailFixtureAuthenticationAttempt> get() = authenticationValues.toList()
    val signOuts: List<NativeRetailFixtureSignOutAttempt> get() = signOutValues.toList()
    val closes: List<NativeRetailFixtureCloseAttempt> get() = closeValues.toList()

    override fun toString(): String = "NativeRetailFixtureCheckpoint([REDACTED])"
}

internal enum class NativeRetailFixturePhase {
    NEW, ALLOCATION_ATTEMPTED, ALLOCATED_CLOSED, INSTALLATION_ATTEMPTED, ACK_PENDING_CLOSED,
    INSTALLED_CLOSED, BASELINE_ATTEMPTED, BASELINE_PREPARED_CLOSED, HELD,
}

internal enum class NativeRetailFixtureHold {
    NATIVE_OUTCOME_REQUIRED, ORIGINAL_MODE_REQUIRED, ORIGINAL_IDENTITY_REQUIRED, COMMITTED_OUTCOME_REQUIRED,
    CURRENT_AUTHENTICATION_REQUIRED, REVOCATION_REQUIRED, CURRENT_READ_REQUIRED, FROZEN_BASELINE_MISMATCH,
    ORIGINAL_CLOSE_REQUIRED, CALL_DID_NOT_RETURN,
}

internal class NativeRetailFixtureHeld(val step: String, val reason: NativeRetailFixtureHold) :
    IllegalStateException("retail.fixture.held.$step.${reason.name}")

internal class NativeRetailFixtureCommandAttempt(
    val step: NativeRetailFixtureCommand,
    val request: MutationRequest,
    val identity: OperationIdentity,
    val sessionId: DomainId,
    val policyRevision: Revision,
    val assignmentId: DomainId,
) {
    /** Null after invocation means no reply was delivered; it is never NotRecorded. */
    var reply: MutationReply? = null
        internal set
    var callStarted: Boolean = false
        internal set
    override fun toString(): String = "NativeRetailFixtureCommandAttempt([REDACTED])"
}

internal class NativeRetailFixtureReadAttempt(val step: String, val actorId: DomainId, val request: ReadRequest) {
    var reply: ReadReply? = null
        internal set
    override fun toString(): String = "NativeRetailFixtureReadAttempt([REDACTED])"
}

internal class NativeRetailFixtureAuthenticationAttempt(val actorId: DomainId) {
    var delivery: NativeRetailFixtureAuthenticationDelivery = NativeRetailFixtureAuthenticationDelivery.NO_RETURN
        internal set
    var denial: LocalAuthenticationDenial? = null
        internal set
    var view: LocalSessionView? = null
        internal set
    override fun toString(): String = "NativeRetailFixtureAuthenticationAttempt([REDACTED])"
}

internal enum class NativeRetailFixtureAuthenticationDelivery { NO_RETURN, AUTHENTICATED, DENIED, UNAVAILABLE, STORAGE_UNCERTAIN }

internal class NativeRetailFixtureSignOutAttempt(val actorId: DomainId, val sessionId: DomainId) {
    var result: LocalSignOutResult? = null
        internal set
    override fun toString(): String = "NativeRetailFixtureSignOutAttempt([REDACTED])"
}

internal class NativeRetailFixtureCloseAttempt(val step: String) {
    /** Unit-returning close records normal completion separately from Boolean master closure. */
    var returnedNormally: Boolean = false
        internal set
    var masterClosed: Boolean? = null
        internal set
    override fun toString(): String = "NativeRetailFixtureCloseAttempt([REDACTED])"
}

internal class NativeRetailFixtureStockObservation(
    val quantities: List<LocalStockQuantityPage>,
    val costs: List<LocalStockCostPage>,
    val branchRevision: Revision,
) {
    override fun toString(): String = "NativeRetailFixtureStockObservation([REDACTED])"
}
