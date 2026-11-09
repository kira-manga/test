package com.ukkera.pos.android.independent.nativefixture

import android.app.Application
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Explicit frozen-fixture inputs; none is an acquired native capability. */
internal class NativeLocalFixtureSetup(
    val companyName: String,
    val branchName: String,
    val installerLogin: String,
    val headLogin: String,
    val initialAuthorityEpoch: Revision,
    val founding: (SelfOwnedKeyProtectionV1) -> LocalCompanyFoundingFactsV1,
    val account: (SelfOwnedAllocationCoordinatesV1) -> LocalAccountProvisioning,
)

/** Test observations are separate from native authority and from case-specific assertions. */
internal interface NativeLocalFixtureEvidence {
    fun bind(symbol: String, value: Any?)
    fun event(step: String, facts: JSONObject = JSONObject())
}

/** A case composes its own observer/gateway over the exact native owner acquired below. */
internal interface NativeLocalFixtureClient {
    val original: AndroidOriginalLocalClient
    val signIns: Int
    suspend fun close()
}

/** Shared Android-test bootstrap only; all authority, opening and close remain production APIs.
 * The case supplies inputs and observations, never a verifier, Room driver or accepting history. */
internal class NativeLocalFixtureOwner<C : NativeLocalFixtureClient>(
    private val application: Application,
    private val protection: SelfOwnedKeyProtectionV1,
    private val evidence: NativeLocalFixtureEvidence,
    private val setupInputs: NativeLocalFixtureSetup,
    private val beforeClientJournals: suspend (AndroidOriginalLocalClient, LocalOriginalClientJournalAction) -> Unit = { _, _ -> },
    private val clientFactory: (AndroidOriginalLocalClient, AndroidOriginalClientJournals, LocalCompanyBusinessPublications?) -> C,
) {
    private var provisioner: LocalProvisioner? = null
    private var context: SelfOwnedLocalSecurityContext? = null
    private var master: AndroidSelfOwnedMasterService? = null
    private var nativeRead: SelfOwnedNativeHistoryCustody? = null
    private var client: C? = null
    private var unconnectedJournals: AndroidOriginalClientJournals? = null
    private var acquisitionUnconfirmed = false
    private var closeUnconfirmed = false
    private var setupAttempted = false
    private var firstClientAcquired = false
    private var clientLifetimeCount = 0
    private var material: SelfOwnedNativeMaterialView? = null
    private var receipt: LocalBranchInstallationReceiptV2? = null
    private var definition: LocalBranchInstallationSnapshotV2? = null
    private var previous: AndroidOriginalLocalClient? = null
    val coordinates: SelfOwnedAllocationCoordinatesV1 get() = checkNotNull(material).coordinates
    val currentClient: C get() = checkNotNull(client)
    /** Actual retained master for independently specified native lifecycle actions. */
    val currentMaster: AndroidSelfOwnedMasterService get() = checkNotNull(master)

    suspend fun setup(installer: CharArray, head: CharArray, employee: CharArray) {
        check(!setupAttempted && !acquisitionUnconfirmed && !closeUnconfirmed)
        setupAttempted = true
        evidence.event("NATIVE_SETUP_STARTED")
        acquisitionUnconfirmed = true
        val opened = AndroidSelfOwnedEnrollmentSource.open(application)
        val actual = (opened as? LocalProvisionerOpenResult.Opened)?.provisioner
            ?: error("Original native enrollment is unavailable")
        provisioner = actual
        acquisitionUnconfirmed = false
        val founding = nativeFixtureValue(actual.companyAuthority.prepareNewCompanyAndHead(
            setupInputs.founding(protection)))
        check(!founding.enrollmentRetained) { "An existing enrollment is not an independent fresh fixture" }
        evidence.bind("O", founding.organizationId.value)
        evidence.bind("C", founding.companyId.value)
        val company = nativeFixtureSecret(installer) { i -> nativeFixtureSecret(head) { h ->
            nativeFixtureValue(actual.companyAuthority.completeNewCompanyAndHead(founding, i, h))
        } }
        check(company.organizationId == founding.organizationId && company.companyId == founding.companyId &&
            company.rootFingerprint == founding.rootFingerprint && company.headAuthorityId == founding.headAuthorityId &&
            company.headEnrollmentId == founding.headEnrollmentId && company.headEnabled &&
            company.policyIssuanceEnabled && company.installationIssuanceEnabled)
        val prepared = actual.prepareBranchWithEnrolledCompany(SelfOwnedBranchFactsV1(
            company.rootFingerprint, setupInputs.branchName, protection)) as? LocalProvisionerPreparation.Prepared
            ?: error("Original native branch preparation is unavailable")
        val granted = nativeFixtureSecret(installer) {
            actual.issueOriginalPreparation(prepared.action, setupInputs.installerLogin, it)
        } as? LocalProvisionerGrant.Retained ?: error("Original native branch issuance is unresolved")
        provisioner = null
        closeResource { actual.close() }

        acquireContext(preparing = true)
        val initial = checkNotNull(context).materialAtOpen
        material = initial
        requireSameNativeFixtureCoordinates(granted.packet.coordinates, coordinates)
        check(coordinates.scope.organizationId == company.organizationId && coordinates.scope.companyId == company.companyId)
        evidence.bind("B", coordinates.scope.branchId.value)
        evidence.bind("M", JSONObject().put("installationId", coordinates.installationId.value)
            .put("assignmentId", coordinates.assignmentId.value).put("deviceId", coordinates.deviceId.value)
            .put("originalNativeBindingDigest", initial.originalNativeBindingDigest))
        evidence.bind("D", coordinates.deviceId.value)
        evidence.bind("E", coordinates.firstActorId.value)
        closeContext()

        acquireMaster(installationOnly = true)
        val installing = checkNotNull(master)
        check(installing.mode.value == LocalMasterLifecycleMode.ALLOCATION_ONLY)
        val before = installing.readOriginalSetup()
        check(before.definition == null && before.receipt == null)
        val requested = setupInputs.account(coordinates)
        val reviewed = installing.prepareOriginalDefinition(requested, setupInputs.initialAuthorityEpoch)
        definition = reviewed
        requireSameNativeFixtureCoordinates(coordinates, reviewed.definition.allocation.coordinates)
        requireNativeFixturePolicy(requested.policy, reviewed.definition.firstAccount.policy)
        check(reviewed.definition.firstAccount.actorId == requested.actorId &&
            reviewed.definition.firstAccount.login == requested.login &&
            reviewed.definition.firstAccount.tokenPolicy == requested.tokenPolicy &&
            reviewed.definition.firstAccount.admittedDeviceIds == requested.admittedDeviceIds)
        evidence.bind("SETUP_REQUEST", reviewed.definition.originalRequestId.value)
        evidence.bind("SETUP_DEFINITION", reviewed.definitionDigest.value)
        var result = nativeFixtureSecret(head) { h -> nativeFixtureSecret(employee) { e ->
            installing.installOriginal(reviewed.definition, setupInputs.headLogin, h, e)
        } }
        if (result == LocalMasterInstallationOutcome.AcknowledgementPending) {
            closeOwned()
            acquireMaster(installationOnly = true)
            check(checkNotNull(master).mode.value == LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING)
            evidence.event("ORIGINAL_NATIVE_INSTALLATION_ACK_RESUME_NOT_SETUP_DELIVERY_ACK")
            result = checkNotNull(master).resumeOriginalAcknowledgement()
        }
        receipt = (result as? LocalMasterInstallationOutcome.Installed)?.receipt
            ?: error("Actual original installation did not settle")
        requireSameNativeFixtureCoordinates(coordinates, checkNotNull(receipt).core.allocation.coordinates)
        check(checkNotNull(receipt).core.originalRequestId == reviewed.definition.originalRequestId &&
            checkNotNull(receipt).core.definitionDigest == reviewed.definitionDigest)
        evidence.bind("SETUP_RECEIPT", checkNotNull(receipt).digest.value)
        reobserveSetup()
        evidence.event("SETUP_DELIVERY_ACK_NOT_RUN_CONTRACT_UNSPECIFIED")
        closeOwned()
    }

    suspend fun reobserveSetup() {
        val actual = checkNotNull(master).readOriginalSetup()
        check(actual.company.organizationId == coordinates.scope.organizationId &&
            actual.company.companyId == coordinates.scope.companyId && actual.company.companyName == setupInputs.companyName &&
            actual.definition?.definitionDigest == checkNotNull(definition).definitionDigest &&
            actual.receipt?.digest == checkNotNull(receipt).digest)
        evidence.event("ORIGINAL_SETUP_REOBSERVED")
    }

    fun requireMasterStillOperational() {
        check(checkNotNull(master).mode.value == LocalMasterLifecycleMode.OPERATIONAL &&
            checkNotNull(client).original.isCurrent()) { "Interactive logout implicitly stopped the original Master" }
        evidence.event("LOGOUT_LEFT_ORIGINAL_MASTER_OPERATIONAL")
    }

    suspend fun openClient(): C {
        check(client == null && master == null && context == null && nativeRead == null &&
            provisioner == null && unconnectedJournals == null && !acquisitionUnconfirmed && !closeUnconfirmed)
        acquireMaster(installationOnly = false)
        val service = checkNotNull(master)
        check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
        val original = service.originalLocalClient()
        requireSameNativeFixtureCoordinates(coordinates, original.material.coordinates)
        check(original.material.originalNativeBindingDigest == checkNotNull(material).originalNativeBindingDigest &&
            original.material.targetSigningFingerprint == checkNotNull(material).targetSigningFingerprint &&
            original.receipt.digest == checkNotNull(receipt).digest && original.isCurrent())
        previous?.let { check(it !== original && !it.isCurrent() && it.serviceIncarnationId != original.serviceIncarnationId) }
        clientLifetimeCount += 1
        evidence.bind("LIFETIME_$clientLifetimeCount", original.serviceIncarnationId.value)
        val action = if (firstClientAcquired) LocalOriginalClientJournalAction.REOPEN_EXISTING
            else LocalOriginalClientJournalAction.ENROLL_FIRST_CLIENT
        val publications = original.originalBusinessPublications()
        // The physical gate belongs to this actual Master and must be acquired before
        // protected-journal enrollment. Nonphysical cases retain their existing order.
        beforeClientJournals(original, action)
        acquisitionUnconfirmed = true
        val journals = (AndroidOriginalClientJournals.acquire(application, original, action)
            as? AndroidOriginalClientJournalsResult.Opened)?.journals
            ?: error("The exact original native credential/intent journals are unavailable")
        // Retain the real owners before any other observation can fail.
        unconnectedJournals = journals
        acquisitionUnconfirmed = false
        // Enrollment already happened even if client composition later fails. A later
        // acquisition may only reopen those exact native journals, never enroll again.
        firstClientAcquired = true
        val root = clientFactory(original, journals, publications)
        client = root
        check(root.original === original) { "Fixture client replaced the acquired native original" }
        unconnectedJournals = null
        previous = original
        check(journals.credentials.owner == LocalClientDeviceIdentity(coordinates.scope, coordinates.deviceId))
        check(root.signIns == 0 && service.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
        evidence.event("ORIGINAL_CLIENT_OPENED_WITHOUT_AUTOMATIC_LOGIN")
        reobserveSetup()
        return root
    }

    /** Fresh existing-only native connection; never read a live Master's database by another path. */
    suspend fun <T> readClosedNativeHistory(reader: suspend (SelfOwnedNativeHistoryCustody) -> T): T {
        check(master == null && client == null && context == null && nativeRead == null &&
            provisioner == null && unconnectedJournals == null && !acquisitionUnconfirmed && !closeUnconfirmed)
        acquireContext(preparing = false)
        acquisitionUnconfirmed = true
        val claimed = AndroidSelfOwnedInstallationOwner.claimOriginalHistoryCustody(checkNotNull(context))
            as? SelfOwnedNativeHistoryClaimResult.Claimed ?: error("Original native read custody is unavailable")
        nativeRead = claimed.custody
        context = null // Actual successful affine transfer; only custody may now close it.
        acquisitionUnconfirmed = false
        val observed = reader(claimed.custody)
        nativeRead = null
        closeResource { claimed.custody.close() }
        return observed
    }

    private suspend fun acquireContext(preparing: Boolean) {
        check(context == null && master == null && nativeRead == null && !acquisitionUnconfirmed && !closeUnconfirmed)
        acquisitionUnconfirmed = true
        val opened = if (preparing) AndroidSelfOwnedInstallationOwner.prepareOriginal(application)
            else AndroidSelfOwnedInstallationOwner.openOriginal(application)
        context = when (opened) {
            is SelfOwnedNativeOpenResult.Opened -> opened.context
            is SelfOwnedNativeOpenResult.Unresolved -> {
                // Diagnostic only: unresolved acquisition still retains the ownership guard.
                evidence.event("NATIVE_CONTEXT_UNRESOLVED", JSONObject()
                    .put("preparing", preparing).put("reason", opened.reason.name))
                error("The original native context is unavailable: ${opened.reason}")
            }
        }
        acquisitionUnconfirmed = false
        material?.let {
            requireSameNativeFixtureCoordinates(it.coordinates, checkNotNull(context).materialAtOpen.coordinates)
            check(it.originalNativeBindingDigest == checkNotNull(context).materialAtOpen.originalNativeBindingDigest)
        }
    }

    private suspend fun acquireMaster(installationOnly: Boolean) {
        acquireContext(preparing = false)
        acquisitionUnconfirmed = true
        val opened = if (installationOnly) AndroidSelfOwnedMasterService.forOriginalInstallation(application, checkNotNull(context))
            else AndroidLocalBranchRuntime.openOriginalMaster(application, checkNotNull(context))
        master = (opened as? AndroidSelfOwnedMasterOpenResult.Opened)?.service
            ?: error("The original native Master did not pass production history admission")
        context = null
        acquisitionUnconfirmed = false
    }

    private suspend fun closeContext() {
        val actual = checkNotNull(context)
        context = null
        closeResource { actual.close() }
    }

    private suspend fun closeResource(block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) {
            closeUnconfirmed = true
            throw failure
        }
    }

    /** Transfers the exact current Master to a lifecycle case that owns its one permitted close.
     * The caller must consume the handle once and must not return it here afterwards. */
    fun detachMasterForExplicitClose(): AndroidSelfOwnedMasterService {
        check(master != null && context == null && nativeRead == null && !acquisitionUnconfirmed && !closeUnconfirmed)
        val actual = checkNotNull(master)
        master = null
        return actual
    }

    /** Visit every actual owner; only a normal close result permits a subsequent acquisition. */
    suspend fun closeOwned(): Unit = withContext(NonCancellable) {
        check(!closeUnconfirmed) { "An earlier owner close is unconfirmed; consumed close must not be retried" }
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { closeResource(block) } catch (caught: Throwable) {
                if (failure == null) failure = caught
                else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
            }
        }
        client?.let { value -> client = null; attempt { value.close() } }
        unconnectedJournals?.let { value -> unconnectedJournals = null; attempt { value.close() } }
        master?.let { value -> master = null; attempt {
            check(value.quiesceAndClose()) { "Actual original Master close is unconfirmed" }
        } }
        nativeRead?.let { value -> nativeRead = null; attempt { value.close() } }
        context?.let { value -> context = null; attempt { value.close() } }
        provisioner?.let { value -> provisioner = null; attempt { value.close() } }
        failure?.let { throw it }
        check(!acquisitionUnconfirmed && !closeUnconfirmed) { "An unresolved acquisition does not prove owner closure" }
        evidence.event("ACTUAL_OWNED_CLOSE_CONFIRMED_NOT_BUSINESS_SERVICE_STOP")
    }
}
