package com.ukkera.pos.security

import android.app.Application
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Actual original native target owner. Neither entry point opens SQLite/Room, creates an
 * employee, signs a complete definition, reads an installation receipt or grants writer use.
 * There is no supplied packet/root/path/key/provider/clock factory. API24+ and a nondebuggable
 * application are required; exact StrongBox protection additionally needs API31+ evidence.
 */
public object AndroidSelfOwnedInstallationOwner {
    /** Explicit preparation/resume of ONLY the original P1-retained allocation on this device. */
    public suspend fun prepareOriginal(application: Application): SelfOwnedNativeOpenResult =
        acquire(application, preparation = true, profile = SelfOwnedNativeProfileV1.LIVE)

    /**
     * Original completed target only: no key generation, P1 registry, installer or cashier
     * renewal. Refusal is unresolved, never a safe-absence or create-new-company permission.
     * Every acquisition/current observation is existing-only; full-history/read admission is separate.
     */
    public suspend fun openOriginal(application: Application): SelfOwnedNativeOpenResult =
        acquire(application, preparation = false, profile = SelfOwnedNativeProfileV1.LIVE)

    /** Explicit training-slot preparation; a distinct root/key/SDK namespace from live. */
    public suspend fun prepareTraining(application: Application,
        slot: LocalTrainingCompositionSlot): SelfOwnedNativeOpenResult =
        acquire(application, preparation = true, profile = SelfOwnedNativeProfileV1.training(slot))

    /** Existing-only training-slot original; never creates or repairs a missing training root. */
    public suspend fun openTraining(application: Application,
        slot: LocalTrainingCompositionSlot): SelfOwnedNativeOpenResult =
        acquire(application, preparation = false, profile = SelfOwnedNativeProfileV1.training(slot))

    /** Transfers a single claim from THIS actual context; never creates a second P2 lease. */
    public suspend fun claimOriginalHistoryCustody(actualContext: SelfOwnedLocalSecurityContext): SelfOwnedNativeHistoryClaimResult =
        actualContext.claimOriginalHistoryCustody()

    /** The downstream database factory gets only THIS original native driver's exact object. */
    public suspend fun openRoomDriver(custody: SelfOwnedNativeHistoryCustody): AndroidSelfOwnedRoomDriver =
        (custody.controller as? AndroidSelfOwnedInstallationLifetime ?: error("Original native custody required")).openRoomDriver()
    public suspend fun finishRoomOpen(custody: SelfOwnedNativeHistoryCustody, driver: AndroidSelfOwnedRoomDriver): Unit =
        (custody.controller as? AndroidSelfOwnedInstallationLifetime ?: error("Original native custody required")).finishRoomOpen(driver)

    /** Current original DATA for an already retained Host resource. This existing-only yield
     * closes no Master participant and never constructs or repairs an SDK owner. */
    public suspend fun yieldExistingMasterSdkForHost(custody: SelfOwnedNativeHistoryCustody,
        expectedCurrentSnapshot: OriginalNativeHistorySnapshot): LocalMasterSdkOriginalV1 =
        (custody.controller as? AndroidSelfOwnedInstallationLifetime ?: error("Original native custody required"))
            .yieldExistingMasterSdkForHost(expectedCurrentSnapshot)

    private suspend fun acquire(application: Application, preparation: Boolean,
        profile: SelfOwnedNativeProfileV1): SelfOwnedNativeOpenResult {
        val lease = AndroidProvisionerProcessLease.acquire(profile)
            ?: return SelfOwnedNativeOpenResult.Unresolved(SelfOwnedInstallationFailureV1.BUSY)
        val work = try { AndroidOwnedInstallationWork(lease) } catch (_: Throwable) {
            // Construction precedes all registry/namespace/key opens.
            lease.releaseUnusedBeforeNativeOpen()
            return SelfOwnedNativeOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        }
        val retained = AtomicReference<AndroidSelfOwnedInstallationLifetime?>(null)
        var delivered = false
        try {
            val lifetime = AndroidSelfOwnedInstallationLifetime(application, work, profile)
            retained.set(lifetime) // Retain partial ownership BEFORE dispatch/native acquisition.
            return when (val result = work.run {
                object : AndroidOwnedTask<AndroidSelfOwnedInstallationLifetime> {
                    override fun execute(): AndroidSelfOwnedInstallationLifetime {
                        if (preparation) lifetime.prepareOriginalNative() else lifetime.openOriginalNative()
                        return lifetime
                    }
                    override fun discard(value: AndroidSelfOwnedInstallationLifetime) { value.closeNative() }
                    override fun clearInputs() = Unit
                }
            }) {
                is AndroidOwnedResult.Value -> {
                    val context = SelfOwnedLocalSecurityContext(result.value)
                    val opened = SelfOwnedNativeOpenResult.Opened(context)
                    delivered = true
                    opened
                }
                is AndroidOwnedResult.Unavailable -> SelfOwnedNativeOpenResult.Unresolved(result.reason)
            }
        } finally {
            if (!delivered) {
                // Cancellation does not release a lease or forget an in-flight open/discard.
                // The fresh owner waits for actual completion and WATCHES actual cleanup.
                work.requestClose { retained.get()?.closeNative() }
            }
        }
    }
}

/**
 * One concrete process/work lifetime, one branch namespace, no second key-work pool. P1 is
 * only a temporary separately locked provenance participant during preparation. Its close
 * never releases this lifetime's process lease. All factory candidates are retained before IO.
 */
internal class AndroidSelfOwnedInstallationLifetime(
    private val application: Application,
    private val work: AndroidOwnedInstallationWork,
    private val profile: SelfOwnedNativeProfileV1,
) : SelfOwnedNativeContextController, SelfOwnedNativeHistoryController,
    SelfOwnedCompanyBusinessPublicationsOwner, SelfOwnedOriginalSerialCustodyOwnerV1,
    SelfOwnedSerialPrivatePublicationController,
    LocalCompanyBusinessPublicationController, LocalCompanySerialRegistrationController,
    SelfOwnedPurchasingPoliciesOwner,
    SelfOwnedEmployeeEnrollmentsOwner, NativeLocalEmployeeEnrollmentController, SelfOwnedEmployeeProvisioningOwner,
    SelfOwnedEmployeePolicyActivationsOwner, NativeLocalEmployeePolicyActivationController, SelfOwnedEmployeePolicyActivationFrameOwner,
    NativeLocalDevicePairingController, NativeLocalDeviceAdmissionController, SelfOwnedDeviceAdmissionFrameOwner {
    override val compositionProfile: LocalCompositionSdkProfileV1 get() = profile.sdkProfile
    private val ownerToken = Any()
    private val ceremoniesDenied = AtomicBoolean(false)
    private val dataDenied = AtomicBoolean(false)
    private val opened = AtomicBoolean(false)
    private val actuallyClosed = AtomicBoolean(false)
    private var nativeCloseStarted = false
    private enum class AcquisitionKind { PREPARATION, EXISTING_ORIGINAL }
    private var acquisitionKind: AcquisitionKind? = null
    private var environment: AndroidSelfOwnedEnvironment? = null
    private var provisionerCandidate: AndroidLocalProvisionerRegistry? = null
    private var namespace: AndroidSelfOwnedDatabaseNamespace? = null
    private var journal: AndroidSelfOwnedInstallationJournal? = null
    private var signing: AndroidSelfOwnedInstallationSigning? = null
    private var material: AndroidSelfOwnedDataKeyMaterial? = null
    // Constructed from these exact native participants, not public accepting data ports. A
    // later Room/history composition needs a separately approved closed-purpose SAME-owner loan.
    private var nativeSecurity: AndroidSelfOwnedLocalSecurity? = null
    private var acquisitionView: SelfOwnedNativeMaterialView? = null
    private enum class ContextPhase { OPEN, ACTION, CLAIMING, TRANSFERRED, CLOSED }
    private val contextGate = Any()
    private var contextPhase = ContextPhase.OPEN // Guarded by contextGate; never a native proof.
    private val historyClaimed = AtomicBoolean(false)
    private var historyAnchor: AndroidSelfOwnedHistoryAnchor? = null
    private var historyStorage: AndroidSelfOwnedHistoryReadStorage? = null
    private var receiptMembershipData: AndroidSelfOwnedReceiptMembershipData? = null
    private var receiptMembershipDataHandle: SelfOwnedNativeReceiptMembershipData? = null
    private var receiptMembershipConstructionEntered = false
    private var receiptMembershipInstallationSettlement: SelfOwnedNativeRoomSettlement? = null
    private var historyPager: AndroidSelfOwnedHistoryReadStorage.RollbackPager? = null
    private var historyClose: SelfOwnedNativeHistoryReadClosed? = null
    private var roomDriver: AndroidSelfOwnedRoomDriver? = null
    private class OriginalRoomOpen(val driver: AndroidSelfOwnedRoomDriver, val invocation: Any)
    private var originalRoomOpen: OriginalRoomOpen? = null
    private var roomFrame: SelfOwnedNativeRoomFrame? = null
    private var runtimeAdapters: SelfOwnedRuntimeSecurity? = null
    private var admittedRoomRead: OriginalNativeHistorySnapshot? = null
    private var originalPeer: LocalAuthenticatedPeer? = null
    private var initialCompanion: AndroidOriginalCompanionInitialization? = null
    // The fresh companion producer retains the actual partial Kit owner before creation.
    // Existing-original acquisitions read authenticated DATA only; they never construct it.
    private var masterSdkOwner: AndroidDevicePairingSdkOwnerV2? = null
    private var masterSdkOriginal: LocalMasterSdkOriginalV1? = null
    // One-way source-fixed construction history, retained even after actual terminal close.
    // A missing nullable owner alone cannot establish that construction was never entered.
    private var masterSdkConstructionEntered = false
    private var companyInstallation: AndroidLocalCompanyAuthority? = null
    private var companyKeys: AndroidLocalProvisionerKeys? = null
    private val companyIssuance = AndroidLocalCompanyIssuanceGate()
    private var businessPublicationsNative: AndroidLocalCompanyBusinessPublications? = null
    private var businessPublications: LocalCompanyBusinessPublications? = null
    private var serialCustodyControl: OriginalLocalSerialCustodyControlV1? = null
    private val purchasingPolicyTrust = AtomicReference<NativePurchasingPolicyTrust?>(null)
    private var purchasingPolicies: LocalCompanyPurchasingPolicies? = null
    private var employeeEnrollmentsNative: AndroidLocalCompanyEmployeeEnrollments? = null
    private var employeeEnrollments: LocalCompanyEmployeeEnrollments? = null
    private var employeePolicyActivationsNative: AndroidLocalCompanyEmployeePolicyActivations? = null
    private var employeePolicyActivations: LocalCompanyEmployeePolicyActivations? = null
    private var devicePairingsNative: AndroidLocalCompanyDevicePairings? = null
    private var devicePairings: LocalCompanyDevicePairings? = null
    private var deviceAdmissionsNative: AndroidLocalCompanyDeviceAdmissions? = null
    private var historyPagerClose: AndroidSelfOwnedHistoryReadStorage.ActualPagerClose? = null
    private val historyDeliveryGate = Any()
    private var historyDelivery: OriginalNativeHistorySnapshot? = null // Guarded by historyDeliveryGate.
    private var historyEmployeeDelivery: NativeEmployeeEnrollmentHistorySelection? = null // Same exact closed read; guarded by historyDeliveryGate.
    private var historyPolicyDelivery: NativeEmployeePolicyActivationHistorySelection? = null // Same actual EVENTS/LINEAGE/close token.
    private var historyBusinessPublicationDelivery: NativeBusinessPublicationHistorySelection? = null
    private var historyDeviceAdmissionDelivery: NativeDeviceAdmissionHistorySelection? = null
    private var historyReceiptMembershipDelivery: SelfOwnedNativeReceiptMembershipData? = null

    override val materialAtOpen: SelfOwnedNativeMaterialView get() = requireNotNull(acquisitionView)

    fun prepareOriginalNative(): Unit = acquireNative(preparation = true)
    fun openOriginalNative(): Unit = acquireNative(preparation = false)

    private fun acquireNative(preparation: Boolean) {
        check(acquisitionKind == null && environment == null && namespace == null && !opened.get() && !nativeCloseStarted)
        work.requireActive()
        acquisitionKind = if (preparation) AcquisitionKind.PREPARATION else AcquisitionKind.EXISTING_ORIGINAL
        // Preparation never becomes an existing-original data owner in place. Its actual
        // close and a separate existing-only acquisition precede the full D7/Room ceremony.
        val actualEnvironment = if (preparation) AndroidSelfOwnedEnvironment.capture(application, work, profile)
            else AndroidSelfOwnedEnvironment.captureExistingOriginal(application, work, profile)
        environment = actualEnvironment
        // This local value never leaves this one synchronous admitted invocation. Parsed UI
        // documents cannot enter this path, and ordinary reopen does not enter P1 at all.
        val approval = if (preparation) readOriginalPreparation(actualEnvironment) else null
        val ownedNamespace = AndroidSelfOwnedDatabaseNamespace(application, actualEnvironment, work, ownerToken)
        namespace = ownedNamespace
        ownedNamespace.openNative(approval)
        val ownedJournal = AndroidSelfOwnedInstallationJournal(ownedNamespace, actualEnvironment, work, ownerToken)
        journal = ownedJournal
        ownedJournal.openNative()
        val ownedSigning = AndroidSelfOwnedInstallationSigning(actualEnvironment, ownedJournal, work)
        signing = ownedSigning
        val ownedMaterial = AndroidSelfOwnedDataKeyMaterial(actualEnvironment, ownedJournal, work)
        material = ownedMaterial
        // Even an empty newly created namespace cannot adopt an orphan original-key alias.
        ownedSigning.assertAliasNamespace()
        if (ownedJournal.intentOrNull() == null) {
            val originalApproval = approval ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            requireOriginalApproval(originalApproval)
            ownedJournal.initializeOriginal(originalApproval)
        }
        if (approval != null) {
            requireOriginalApproval(approval)
            if (!ownedJournal.intent().source.same(approval.source)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        ownedSigning.verifyOriginalSource()
        if (preparation) {
            requireOriginalApproval(requireNotNull(approval))
            ownedSigning.prepareOriginalSigningKey()
            ownedMaterial.prepareOriginalDataKey(SelfOwnedTargetKeyRoleV1.STORAGE)
            ownedMaterial.prepareOriginalDataKey(SelfOwnedTargetKeyRoleV1.PEPPER)
        }
        val original = ownedJournal.intent()
        if (!original.complete()) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        ownedSigning.assertAliasNamespace(); ownedSigning.assertOriginalSigningKey(); ownedMaterial.assertOriginalDataKeys()
        val state = if (original.bindingStarted) {
            // A possibly previously produced binding is NEVER re-signed/recreated from absence,
            // even on a newer boot. P2 has no destructive repair or missing-state recovery proof.
            ownedJournal.readState(ownedSigning, ownedMaterial)
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        } else {
            if (!preparation) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            val originalApproval = requireNotNull(approval)
            requireOriginalApproval(originalApproval)
            if (ownedJournal.readState(ownedSigning, ownedMaterial) != null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            ownedJournal.retainBindingStarted() // Forced prior marker BEFORE the first binding signature.
            val binding = ownedSigning.createOriginalBinding()
            requireOriginalApproval(originalApproval)
            ownedJournal.retainState(SelfOwnedNativeJournalStateV1(0L, binding, true), ownedSigning, ownedMaterial)
        }
        if (!state.newCeremoniesAllowed) ceremoniesDenied.set(true)
        acquisitionView = view(state)
        nativeSecurity = AndroidSelfOwnedLocalSecurity(this, actualEnvironment, ownedMaterial, work)
        requireNativeLifetime()
        opened.set(true)
        requireDataNative()
        if (!original.bindingStarted && ownedNamespace.createdThisLifetime) {
            // This is the ONLY missing-companion creation edge. Existing namespaces never
            // reach it, even if a failed original creation left no complete companion.
            val initial = AndroidSelfOwnedHistoryAnchor(this, actualEnvironment, work, ownedNamespace,
                ownedSigning, ownedMaterial, ownerToken, AndroidP256Primitives.newId())
            val initialization=AndroidOriginalCompanionInitialization(this,initial,ownedNamespace,ownedSigning)
            check(initialCompanion==null)
            initialCompanion=initialization
            try { initial.initializeOriginalCompanion(initialization) }
            finally { initialCompanion=null }
        }
    }

    internal fun requireInitialCompanion(actual: AndroidOriginalCompanionInitialization) {
        check(initialCompanion===actual && actual.owner===this && actual.namespace===namespace && actual.signing===signing &&
            requireNotNull(namespace).createdThisLifetime && !historyClaimed.get() && historyAnchor==null && roomDriver==null)
        requireDataNative()
    }

    /** The exact creator capability and already forced binding/history-origin intentions
     * precede this call. No existing read or missing/partial original can enter this edge. */
    internal fun captureInitialMasterSdkOriginalNative(initialization: AndroidOriginalCompanionInitialization,
        allocation: OriginalAllocationCommitmentV2): LocalMasterSdkOriginalV1 {
        requireInitialCompanion(initialization)
        check(masterSdkOwner == null && masterSdkOriginal == null && !masterSdkConstructionEntered)
        val original = requireNotNull(journal).intent()
        check(original.complete() && original.bindingStarted)
        val actualEnvironment = requireNotNull(environment)
        if (!original.source.preparationBody.target.same(actualEnvironment.target))
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
        val actualAllocation = requireNotNull(signing).originalHistoryAllocation(
            readOriginalStateNative().originalBinding, initialization)
        val actualBytes = LocalBranchInstallationWireV2.encodeAllocation(actualAllocation)
        try {
            val expectedBytes = LocalBranchInstallationWireV2.encodeAllocation(allocation)
            try {
                if (!actualBytes.contentEquals(expectedBytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            } finally { expectedBytes.fill(0) }
        } finally { actualBytes.fill(0) }
        val acquisitionId = AndroidP256Primitives.newId()
        masterSdkConstructionEntered = true // Retain entry before even the partial owner constructor.
        val owned = AndroidDevicePairingSdkOwnerV2(application, work)
        masterSdkOwner = owned // Retain BEFORE official SDK storage or Kit construction.
        owned.createNative(compositionProfile.appId(actualAllocation.coordinates.scope),
            compositionProfile.deviceName(actualAllocation.coordinates.deviceId))
        val captured = owned.captureMasterNative(acquisitionId, actualAllocation, actualEnvironment.target)
        masterSdkOriginal = captured // This exact association must survive first force/readback.
        requireInitialMasterSdkOriginalNative(initialization, captured)
        return captured
    }

    /** Only the still-live original producer compares against its retained unstarted Kit.
     * Ordinary authenticated DATA reads never reconstruct an SDK owner to pass this check. */
    internal fun requireInitialMasterSdkOriginalNative(initialization: AndroidOriginalCompanionInitialization,
        original: LocalMasterSdkOriginalV1) {
        requireInitialCompanion(initialization)
        val retained = masterSdkOriginal ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val actualBytes = retained.copyWireBytes()
        try {
            val observedBytes = original.copyWireBytes()
            try {
                if (!actualBytes.contentEquals(observedBytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            } finally { observedBytes.fill(0) }
        } finally { actualBytes.fill(0) }
        if (original.compositionProfile != compositionProfile ||
            !original.nativeApplication.same(requireNotNull(environment).target) ||
            !original.nativeApplication.same(materialAtOpen.target) ||
            original.allocation.originalNativeBindingDigest.value != materialAtOpen.originalNativeBindingDigest)
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val owned = masterSdkOwner ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        owned.requireMatchesNative(retained)
        requireInitialCompanion(initialization)
    }

    private fun readOriginalPreparation(actualEnvironment: AndroidSelfOwnedEnvironment): SelfOwnedNativeApproval {
        val registry = AndroidLocalProvisionerRegistry(actualEnvironment, work)
        provisionerCandidate = registry
        registry.openNative()
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (!original.complete() || !original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val keys = AndroidLocalProvisionerKeys(actualEnvironment, registry, work)
        keys.assertAllOriginalKeys()
        val state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val allocation = state.allocations.singleOrNull()
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (allocation.phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        val grant = allocation.grant ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        // readState authenticates the exact native root/issuer/registry and original grant body,
        // not just a self-signed/imported chain. Retained original preparation is historical;
        // withdrawing NEW P1 issuance does not rewrite or renew this already retained action.
        val source = SelfOwnedNativeSourceV1(state.root, state.delegation, grant)
        if (!source.preparationBody.target.same(actualEnvironment.target)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        keys.assertAllOriginalKeys(); registry.assertContinuity(); actualEnvironment.requireCurrent()
        val approval = SelfOwnedNativeApproval(ownerToken, source, actualEnvironment.bootCount, work.currentDeadlineMillis())
        registry.closeNative() // Still watched by this invocation; not a second owner shutdown.
        provisionerCandidate = null // Only actual returned close permits forgetting the candidate.
        work.requireActive()
        return approval
    }

    private fun requireOriginalApproval(approval: SelfOwnedNativeApproval) {
        val actualEnvironment = requireNotNull(environment)
        actualEnvironment.requireCurrent()
        if (approval.owner !== ownerToken || approval.bootCount != actualEnvironment.bootCount ||
            work.clock() >= approval.expiresElapsedMillis || !approval.source.preparationBody.target.same(actualEnvironment.target)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        work.requireActive()
    }

    private fun beginContextAction(withdraw: Boolean = false): SelfOwnedInstallationFailureV1? = synchronized(contextGate) {
        if (contextPhase in setOf(ContextPhase.TRANSFERRED,ContextPhase.CLOSED) || dataUseDeniedNoIo())
            return@synchronized SelfOwnedInstallationFailureV1.NATIVE_RETIRED
        if (contextPhase != ContextPhase.OPEN) return@synchronized SelfOwnedInstallationFailureV1.BUSY
        contextPhase=ContextPhase.ACTION
        if (withdraw) ceremoniesDenied.set(true)
        null
    }
    private fun finishContextAction() = synchronized(contextGate) {
        if (contextPhase == ContextPhase.ACTION) contextPhase=ContextPhase.OPEN
    }
    override suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult {
        beginContextAction()?.let { return SelfOwnedNativeReadResult.Unresolved(it) }
        return try {
            when (val result = work.run { metadataTask {
                val state = readOriginalStateNative()
                SelfOwnedNativeReadResult.Retained(view(state), state.originalBinding)
            } }) {
                is AndroidOwnedResult.Value -> result.value
                is AndroidOwnedResult.Unavailable -> SelfOwnedNativeReadResult.Unresolved(result.reason)
            }
        } finally { finishContextAction() }
    }

    override suspend fun withdrawNewCeremonies(): SelfOwnedNativeWithdrawal {
        beginContextAction(withdraw=true)?.let { return SelfOwnedNativeWithdrawal.Unavailable(it) }
        return try {
            when (val result = work.run { metadataTask {
                val previous = readOriginalStateNative()
                val retained = if (!previous.newCeremoniesAllowed) previous else requireNotNull(journal).retainState(
                    previous.withdrawNewCeremonies(), requireNotNull(signing), requireNotNull(material))
                if (retained.newCeremoniesAllowed) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                Unit
            } }) {
                is AndroidOwnedResult.Value -> SelfOwnedNativeWithdrawal.Retained
                is AndroidOwnedResult.Unavailable -> SelfOwnedNativeWithdrawal.Unavailable(result.reason)
            }
        } finally { finishContextAction() }
    }

    override fun denyContextNewCeremonies() {
        synchronized(contextGate) {
            if (contextPhase != ContextPhase.TRANSFERRED) ceremoniesDenied.set(true)
        }
    }
    override fun denyContextDataUse() {
        synchronized(contextGate) {
            if (contextPhase != ContextPhase.TRANSFERRED) {
                contextPhase=ContextPhase.CLOSED
                denyDataUse()
            }
        }
    }
    override suspend fun closeContext() {
        synchronized(contextGate) {
            if (contextPhase == ContextPhase.TRANSFERRED) return
            contextPhase=ContextPhase.CLOSED
            denyDataUse()
        }
        work.requestClose { closeNative() }.await()
    }

    override suspend fun claimOriginalHistoryCustody(): SelfOwnedNativeHistoryClaimResult {
        synchronized(contextGate) {
            if (contextPhase in setOf(ContextPhase.TRANSFERRED,ContextPhase.CLOSED) || dataUseDeniedNoIo())
                return SelfOwnedNativeHistoryClaimResult.Unresolved(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            if (contextPhase != ContextPhase.OPEN || !historyClaimed.compareAndSet(false,true))
                return SelfOwnedNativeHistoryClaimResult.Unresolved(SelfOwnedInstallationFailureV1.BUSY)
            contextPhase=ContextPhase.CLAIMING
        }
        var delivered=false
        var notAdmittedBusy=false
        try {
            return when(val result=work.run {
                object: AndroidOwnedTask<SelfOwnedNativeHistoryCustody> {
                    override fun execute(): SelfOwnedNativeHistoryCustody {
                        // An actual PREPARATION context is rejected before requireDataNative's
                        // environment observation can invoke the creating framework getter.
                        requireNotNull(environment).requireExistingOriginalObservation()
                        requireDataNative()
                        check(historyStorage==null)
                        sameHistoryAnchorNative()
                        return SelfOwnedNativeHistoryCustody(this@AndroidSelfOwnedInstallationLifetime)
                    }
                    override fun discard(value: SelfOwnedNativeHistoryCustody) {
                        denyDataUse(); requestHistoryLifetimeClose()
                    }
                    override fun clearInputs() = Unit
                }
            }) {
                is AndroidOwnedResult.Value -> synchronized(contextGate) {
                    if (contextPhase != ContextPhase.CLAIMING || dataUseDeniedNoIo())
                        SelfOwnedNativeHistoryClaimResult.Unresolved(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                    else {
                        // This is the sole handoff linearization point. A context close/deny
                        // before it prevents delivery; after it the old handle cannot act.
                        val claim=SelfOwnedNativeHistoryClaimResult.Claimed(result.value)
                        contextPhase=ContextPhase.TRANSFERRED
                        delivered=true; claim
                    }
                }
                is AndroidOwnedResult.Unavailable -> {
                    synchronized(contextGate) {
                        notAdmittedBusy=result.reason==SelfOwnedInstallationFailureV1.BUSY &&
                            contextPhase==ContextPhase.CLAIMING && !dataUseDeniedNoIo()
                        if(notAdmittedBusy) { historyClaimed.set(false); contextPhase=ContextPhase.OPEN }
                    }
                    SelfOwnedNativeHistoryClaimResult.Unresolved(result.reason)
                }
            }
        } finally {
            if(!delivered && !notAdmittedBusy) {
                // Before delivery, cancellation still belongs to this producer. The original
                // work drains execute/discard before actual owner close and process-lease release.
                synchronized(contextGate) { contextPhase=ContextPhase.CLOSED; denyDataUse() }
                requestHistoryLifetimeClose()
            }
        }
    }

    override suspend fun snapshotOriginal(): OriginalNativeHistorySnapshot = historyOperation {
        requireNotNull(historyAnchor).snapshotOriginal()
    }

    /** Only the actual operational existing-only lifetime can yield its recorded SDK original.
     * Fresh preparation's retained Kit must have actually stopped before its lease permits this
     * distinct acquisition. This is not a nullable-Kit stop, a close receipt or a new SDK grant. */
    suspend fun yieldExistingMasterSdkForHost(expectedCurrentSnapshot: OriginalNativeHistorySnapshot):
        LocalMasterSdkOriginalV1 = historyOperation {
        check(acquisitionKind == AcquisitionKind.EXISTING_ORIGINAL &&
            synchronized(contextGate) { contextPhase == ContextPhase.TRANSFERRED })
        check(!masterSdkConstructionEntered && masterSdkOwner == null && masterSdkOriginal == null &&
            initialCompanion == null)
        val driver = checkNotNull(roomDriver)
        val peer = checkNotNull(originalPeer)
        check(originalRoomOpen?.driver === driver && roomFrame == null && historyStorage == null &&
            historyPager == null && peer.isCurrent())
        val actual = requireNotNull(historyAnchor).requireSameOriginalCut(expectedCurrentSnapshot)
        val record = actual.record
        if (record.recordFormat != SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT ||
            record.masterSdkOriginal == null)
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        check(record.pending == null && record.issuerAcknowledgement != null && record.database != null &&
            record.parent != null && record.receipt != null && record.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN &&
            peer.scope == record.allocation.coordinates.scope && peer.deviceId == record.allocation.coordinates.deviceId)
        val original = checkNotNull(record.masterSdkOriginal)
        // The authenticated native read above joins the complete original allocation/application
        // to the actual native participants. Branch compatibility adds no capture or repair edge.
        check(original.compositionProfile == compositionProfile &&
            original.appId == compositionProfile.appId(record.allocation.coordinates.scope))
        original
    }
    override suspend fun openOriginalReadOnly(): SelfOwnedNativeHistoryReadSession {
        val result=work.run {
            object: AndroidOwnedTask<SelfOwnedNativeHistoryReadSession> {
                override fun execute(): SelfOwnedNativeHistoryReadSession {
                    requireNotNull(environment).requireExistingOriginalObservation()
                    requireDataNative(); check(historyClaimed.get())
                    if(historyStorage!=null || historyPager!=null || receiptMembershipConstructionEntered ||
                        synchronized(historyDeliveryGate) { historyDelivery!=null })
                        selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
                    val ownedAnchor=requireNotNull(historyAnchor)
                    val original=ownedAnchor.snapshotOriginal()
                    // Existing-only original company registry/root/worker; no Room pool, peer,
                    // current employee credential, session, supplied selector or second owner.
                    val employees=selectOriginalEmployeeHistoryNative(original)
                    val policies=selectOriginalPolicyHistoryNative(original)
                    val publications=selectOriginalBusinessPublicationHistoryNative(original)
                    val admissions=selectOriginalDeviceAdmissionHistoryNative(original)
                    val candidate=AndroidSelfOwnedHistoryReadStorage(this@AndroidSelfOwnedInstallationLifetime,work,
                        requireNotNull(environment),requireNotNull(namespace),ownedAnchor,requireNotNull(material),ownerToken,original,employees,policies,publications,admissions)
                    historyStorage=candidate // Retain BEFORE the first raw pin/driver/statement open.
                    candidate.openNative()
                    return SelfOwnedNativeHistoryReadSession(candidate,original,employees,policies,publications,admissions)
                }
                override fun discard(value: SelfOwnedNativeHistoryReadSession) { denyDataUse(); requestHistoryLifetimeClose() }
                override fun clearInputs() = Unit
            }
        }
        return when(result) {
            is AndroidOwnedResult.Value -> result.value
            is AndroidOwnedResult.Unavailable -> {
                if(result.reason!=SelfOwnedInstallationFailureV1.BUSY) { denyDataUse(); requestHistoryLifetimeClose() }
                throw NativeHistoryUnavailable(result.reason)
            }
        }
    }

    fun historyReadActuallyClosedNative(storage: AndroidSelfOwnedHistoryReadStorage,close: SelfOwnedNativeHistoryReadClosed) {
        check(historyStorage===storage && storage.ownsClose(close) && historyClose==null)
        historyClose=close
    }

    override suspend fun openReceiptMembershipData(session: SelfOwnedNativeHistoryReadSession): SelfOwnedNativeReceiptMembershipData {
        return receiptMembershipOperation {
            val storage = checkNotNull(historyStorage)
            val identity = storage.receiptMembershipReadIdentityNative(session)
            check(!receiptMembershipConstructionEntered && receiptMembershipData == null && receiptMembershipDataHandle == null)
            registerReceiptMembershipDataNative(identity)
        }
    }

    override suspend fun prepareReceiptMembershipDataAfterClosedRead(afterClose: OriginalNativeHistorySnapshot,
        data: SelfOwnedNativeReceiptMembershipData) {
        receiptMembershipOperation {
            val candidate = checkNotNull(receiptMembershipData)
            synchronized(historyDeliveryGate) {
                check(!dataUseDeniedNoIo() && historyDelivery === afterClose && afterClose.ownerIdentity === ownerToken &&
                    historyReceiptMembershipDelivery === data && receiptMembershipDataHandle === data && data.controller === candidate &&
                    historyStorage == null && historyClose == null && roomDriver == null)
            }
            candidate.reopenAfterClosedReadNative()
        }
    }

    override suspend fun openEmptyReceiptMembershipDataAfterCommittedInstallation(
        settlement: SelfOwnedNativeRoomSettlement): SelfOwnedNativeReceiptMembershipData {
        return receiptMembershipOperation {
            check(settlement === receiptMembershipInstallationSettlement &&
                settlement.disposition == SelfOwnedNativeSqlDisposition.COMMITTED && settlement.original.ownerIdentity === ownerToken &&
                settlement.original.record.pending == null && settlement.original.record.parent?.historySequence == 1L &&
                settlement.original.record.receipt != null && settlement.original.record.firstChild != null &&
                roomFrame == null && historyStorage == null && historyClose == null &&
                !receiptMembershipConstructionEntered && receiptMembershipData == null && receiptMembershipDataHandle == null)
            requireNotNull(historyAnchor).requireSameOriginalCut(settlement.original)
            try { registerReceiptMembershipDataNative(null) }
            finally { receiptMembershipInstallationSettlement = null }
        }
    }

    private fun registerReceiptMembershipDataNative(identity: Any?): SelfOwnedNativeReceiptMembershipData {
        receiptMembershipConstructionEntered = true
        val candidate = AndroidSelfOwnedReceiptMembershipData(this, work, requireNotNull(environment), identity)
        val handle = SelfOwnedNativeReceiptMembershipData(candidate)
        // The complete partial owner and exact delivered facade are retained before keys/files.
        receiptMembershipData = candidate; receiptMembershipDataHandle = handle
        candidate.openNative()
        return handle
    }

    private suspend fun <T> receiptMembershipOperation(body: () -> T): T {
        val token = work.roomToken(currentCoroutineContext())
            ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        work.requireWatchedRoom(token); work.requireActive()
        return try { historyOperation(body) }
        catch (failure: Throwable) {
            // No public facade may have been returned yet. The registered partial owner
            // must park its known handles here, including failures of the final custody
            // guard. Cleanup keeps the SAME watched Room deadline after one-way denial.
            withContext(NonCancellable) {
                try { receiptMembershipData?.park() }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            denyDataUse(); requestHistoryLifetimeClose()
            throw failure
        }
    }

    internal fun requireReceiptMembershipDataNative(candidate: AndroidSelfOwnedReceiptMembershipData) {
        requireNotNull(environment).requireExistingOriginalObservation()
        requireDataNative()
        check(historyClaimed.get() && receiptMembershipData === candidate && receiptMembershipDataHandle?.controller === candidate)
        when (candidate.stateNative) {
            AndroidSelfOwnedReceiptMembershipData.State.OPENING,
            AndroidSelfOwnedReceiptMembershipData.State.BUILDING -> {
                val identity = candidate.readIdentityNative
                if (identity != null) checkNotNull(historyStorage).requireReceiptMembershipReadNative(identity)
                else check(candidate.stateNative == AndroidSelfOwnedReceiptMembershipData.State.OPENING &&
                    receiptMembershipInstallationSettlement != null && roomDriver != null && roomFrame == null)
            }
            AndroidSelfOwnedReceiptMembershipData.State.REOPENING,
            AndroidSelfOwnedReceiptMembershipData.State.PREPARED -> synchronized(historyDeliveryGate) {
                check(!dataUseDeniedNoIo() && historyDelivery != null && historyReceiptMembershipDelivery === receiptMembershipDataHandle &&
                    candidate.readIdentityNative != null && historyStorage == null && historyClose == null && roomDriver == null)
            }
            AndroidSelfOwnedReceiptMembershipData.State.ACTIVE ->
                check(roomDriver != null && originalRoomOpen?.driver === roomDriver && candidate.readIdentityNative == null)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }
        work.requireActive()
    }

    internal fun requireReceiptMembershipDataCleanupNative(candidate: AndroidSelfOwnedReceiptMembershipData, token: Any) {
        // Cleanup reuses the admitted deadline even after one-way denial; no new data work.
        check(receiptMembershipData === candidate && receiptMembershipDataHandle?.controller === candidate)
        work.requireWatchedRoom(token)
    }

    internal fun requireReceiptMembershipParkedBeforeOriginalClose(storage: AndroidSelfOwnedHistoryReadStorage, identity: Any) {
        check(historyStorage === storage)
        val candidate = receiptMembershipData ?: return
        // CLOSED is possible only after the lifetime's actual terminal disposal. That denied
        // terminal path creates no accepting read-close object and leaves no DATA handles.
        if (candidate.stateNative == AndroidSelfOwnedReceiptMembershipData.State.CLOSED) return
        try {
            check(candidate.belongsToReadNative(identity))
            candidate.requireActuallyParkedNative()
        } catch (failure: Throwable) {
            // This is a precondition refusal, not an uncertain consumed original close.
            // Terminal cleanup disposes the DATA before closing the still-retained reader.
            denyDataUse(); requestHistoryLifetimeClose()
            throw failure
        }
    }
    override suspend fun reobserveAfterActualClose(close: SelfOwnedNativeHistoryReadClosed): OriginalNativeHistorySnapshot = historyOperation {
        val storage=requireNotNull(historyStorage)
        check(synchronized(historyDeliveryGate) { historyDelivery==null })
        check(historyClose===close && storage.ownsClose(close) && close.ownerIdentity===ownerToken && !close.consumed && close.completeTraversal)
        val receiptData = receiptMembershipData
        if (receiptData != null) {
            check(receiptData.belongsToReadNative(close.sessionIdentity))
            receiptData.requireCompletedBuildParkedNative()
        }
        val actual=requireNotNull(historyAnchor).requireSameOriginalCut(close.original)
        storage.reobserveOriginalFileAfterActualClose(close)
        val employees=close.employeeSelection
        check(employees === storage.employeeSelection)
        if (employees != null) requireNotNull(employeeEnrollmentsNative).reobserveOriginalHistory(employees,close.original)
        else requireLegacyOriginalIssuerNative(requireNotNull(provisionerCandidate))
        val policies=close.policySelection
        check(policies === storage.policySelection)
        if (policies != null) requireNotNull(employeePolicyActivationsNative).reobserveOriginalHistory(policies,close.original)
        else requireLegacyOriginalIssuerNative(requireNotNull(provisionerCandidate))
        val publications=close.businessPublicationSelection
        check(publications === storage.businessPublicationSelection)
        if (publications != null) requireNotNull(businessPublicationsNative).reobserveOriginalHistory(publications,close.original)
        else requireLegacyOriginalIssuerNative(requireNotNull(provisionerCandidate))
        val admissions=close.deviceAdmissionSelection
        check(admissions === storage.deviceAdmissionSelection)
        if (admissions != null) requireNotNull(deviceAdmissionsNative).reobserveOriginalHistory(admissions,close.original)
        else requireLegacyOriginalIssuerNative(requireNotNull(provisionerCandidate))
        close.consumed=true
        historyStorage=null; historyClose=null // Forget only a genuinely closed, reobserved session.
        synchronized(historyDeliveryGate) {
            if(dataUseDeniedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            check(historyDelivery==null)
            historyDelivery=actual; historyEmployeeDelivery=employees; historyPolicyDelivery=policies
            historyBusinessPublicationDelivery=publications
            historyDeviceAdmissionDelivery=admissions
            historyReceiptMembershipDelivery=if (receiptData != null) receiptMembershipDataHandle else null
        }
        actual
    }
    override fun completeOriginalReadDelivery(afterClose: OriginalNativeHistorySnapshot) {
        synchronized(historyDeliveryGate) {
            // Bounded actual elapsed/work-denial observation only; no provider or storage IO.
            // A later denial does not change the already selected historical receipt into RUN.
            work.requireActive()
            if(dataUseDeniedNoIo() || historyDelivery !== afterClose || afterClose.ownerIdentity !== ownerToken)
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            historyDelivery=null; historyEmployeeDelivery=null; historyPolicyDelivery=null // Receipt-only delivery grants no employee/policy custody.
            historyBusinessPublicationDelivery=null
            historyDeviceAdmissionDelivery=null
            if (historyReceiptMembershipDelivery != null) receiptMembershipData?.abandonDeliveryNoIo()
            historyReceiptMembershipDelivery=null
        }
    }

    /** Retain the real paired-pager resource BEFORE its first original pin or SQLite open. */
    internal fun retainHistoryRollbackPagerNative(candidate: AndroidSelfOwnedHistoryReadStorage.RollbackPager) {
        requireNotNull(environment).requireExistingOriginalObservation(); requireDataNative()
        check(historyClaimed.get() && historyStorage==null && historyPager==null && historyPagerClose==null &&
            synchronized(historyDeliveryGate) { historyDelivery==null })
        candidate.requireOriginalCustody(this,work,requireNotNull(environment),requireNotNull(namespace),
            requireNotNull(requireNotNull(historyAnchor).snapshotOriginal().record.database))
        historyPager=candidate
    }
    internal fun historyRollbackPagerActuallyClosedNative(candidate: AndroidSelfOwnedHistoryReadStorage.RollbackPager,
        close: AndroidSelfOwnedHistoryReadStorage.ActualPagerClose) {
        check(historyPager===candidate && historyPagerClose==null)
        close.requireOriginalPager(candidate)
        historyPagerClose=close
        // Keep both exact objects until a future REAL fenced/drained F03 coordinator consumes
        // them into its stable scan. P3 has no path to consume, recover, settle or admit them.
    }
    private suspend fun <T> historyOperation(body: ()->T): T = when(val result=work.run {
        object: AndroidOwnedTask<T> {
            override fun execute(): T {
                requireNotNull(environment).requireExistingOriginalObservation()
                requireDataNative(); check(historyClaimed.get()); return body().also { requireDataNative() }
            }
            override fun discard(value: T) = Unit
            override fun clearInputs() = Unit
        }
    }) {
        is AndroidOwnedResult.Value -> result.value
        is AndroidOwnedResult.Unavailable -> throw NativeHistoryUnavailable(result.reason)
    }
    internal fun readOriginalStateForHistoryNative(): SelfOwnedNativeJournalStateV1 = readOriginalStateNative()
    internal fun historyClockSampleNative(): AndroidSelfOwnedNativeClockSample =
        requireNotNull(nativeSecurity).observeNativeOnly() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    internal fun newCeremoniesAllowedNoIo(): Boolean = !ceremoniesDenied.get() && !dataUseDeniedNoIo()
    internal fun requestHistoryLifetimeClose() { work.requestClose { closeNative() } }

    override suspend fun <T> runRoomWork(block: suspend () -> T, discard: suspend (T) -> Unit): T =
        work.runRoom(block, discard)

    suspend fun openRoomDriver(): AndroidSelfOwnedRoomDriver = historyOperation {
        check(roomDriver == null && historyStorage == null && historyPager == null &&
            synchronized(historyDeliveryGate) { historyDelivery == null })
        val anchor = requireNotNull(historyAnchor)
        val original = anchor.snapshotOriginal()
        check(original.record.pending == null)
        if(original.record.database!=null) {
            val admitted=checkNotNull(admittedRoomRead)
            requireNotNull(historyAnchor).requireSameOriginalCut(admitted)
        } else check(original.record.parent==null && original.record.attempt?.phase==NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED)
        admittedRoomRead=null
        val path = requireNotNull(namespace).prepareRoomDatabaseFile(original.record)
        AndroidSelfOwnedRoomDriver(this, work, requireNotNull(namespace), anchor, path).also { roomDriver = it }
    }
    suspend fun finishRoomOpen(driver: AndroidSelfOwnedRoomDriver) {
        check(roomDriver === driver && roomFrame == null && originalRoomOpen == null)
        work.roomCoroutines.drainBeforeNative()
        historyOperation {
            driver.finishOriginalRoomOpen()
            originalRoomOpen = OriginalRoomOpen(driver, work.roomCoroutines.currentNativeInvocation())
        }
    }

    override fun retainOriginalRoomClose(databaseIdentity: Any, closeActualRoom: () -> Unit) {
        val original = checkNotNull(originalRoomOpen)
        check(historyClaimed.get() && !dataUseDeniedNoIo() && !nativeCloseStarted &&
            roomDriver === original.driver && roomFrame == null && historyStorage == null && historyPager == null &&
            work.roomCoroutines.currentNativeInvocation() === original.invocation)
        // Only the private original constructor at THIS completed real driver-open cut can
        // register its bound database::close; a later loan cannot substitute another owner.
        work.retainOriginalRoomClose(this, original.driver, databaseIdentity, closeActualRoom)
    }

    internal fun requireOriginalRoomCloseOwner(driver: AndroidSelfOwnedRoomDriver) {
        // Denial is expected here. Check only the original retained resource identities;
        // never reopen/read data or replace a driver before executing the actual bound close.
        check(roomDriver === driver && originalRoomOpen?.driver === driver &&
            !actuallyClosed.get() && !nativeCloseStarted)
    }

    override suspend fun prepareActualRoomClose() {
        try {
            work.requireNoRetainedOriginalRoomClose()
            work.roomCoroutines.prepareActualRoomClose()
        }
        catch (failure: Throwable) { work.roomCoroutines.fault(); throw failure }
    }
    override suspend fun recoverOriginalRollbackPager(): Unit = historyOperation {
        check(roomDriver == null && roomFrame == null && historyStorage == null && historyPager == null)
        val original = requireNotNull(historyAnchor).snapshotOriginal()
        val binding = requireNotNull(original.record.database)
        val pager = AndroidSelfOwnedHistoryReadStorage.RollbackPager.retainedCandidate(this, work,
            requireNotNull(environment), requireNotNull(namespace), binding)
        val close = pager.recoverAndCloseNative()
        check(historyPager === pager && historyPagerClose === close)
        close.requireOriginalPager(pager)
        requireNotNull(historyAnchor).requireSameOriginalCut(original)
        historyPager = null; historyPagerClose = null
    }
    override suspend fun beginRoomFrame(): SelfOwnedNativeRoomFrame = beginRoomFrameForOperation(null)
    override suspend fun beginApprovedCommandRoomFrame(operationId: com.ukkera.pos.types.DomainId): SelfOwnedNativeRoomFrame =
        beginRoomFrameForOperation(operationId)
    private suspend fun beginRoomFrameForOperation(approvedOperationId: com.ukkera.pos.types.DomainId?): SelfOwnedNativeRoomFrame {
        work.roomCoroutines.drainBeforeNative()
        val frame = historyOperation {
            check(roomFrame == null && historyStorage == null && historyPager == null)
            val original = requireNotNull(historyAnchor).snapshotOriginal()
            check(original.record.pending == null && original.record.database != null)
            // Actual canonical anchor bytes + a complete pending/final-decision reservation,
            // before driver attach/BEGIN or any application SQL. No submitted child is evicted.
            try {
                if (approvedOperationId == null) NativeHistoryFinalDecisionCapacity.requireBeforeRoomSubmission(original.record)
                else NativeHistoryFinalDecisionCapacity.requireBeforeApprovedRoomSubmission(original.record)
            }
            catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED) }
            // Refresh existing company content before attach/BEGIN under this same native
            // exclusion. Catalog validation may then read immutable history without native
            // reentry, including after a lost publication response. No new owner is acquired.
            SelfOwnedNativeRoomFrame(ownerToken, AndroidP256Primitives.newId(), original, approvedOperationId).also {
                it.registeredBusinessPublicationObservation=captureOriginalBusinessPublicationObservationNative(it)
                requireNotNull(roomDriver).attach(it); roomFrame = it
            }
        }
        work.roomCoroutines.resumeQueriesAfterNative()
        return frame
    }
    override suspend fun prepareRoomChild(frame: SelfOwnedNativeRoomFrame, child: NativeHistoryRecordedHeadV2,
        intent: NativeHistoryOriginalIntentV2, installation: SelfOwnedInstallationChild?,
        finalDeadline: NativeHistoryFinalDeadlineDeclaration?, protectedFinal: NativeHistoryProtectedFinalDeclaration?,
        businessPublications: NativeCompanyBusinessPublicationObservation?, businessPublicationDependencies: ByteArray?): Unit {
        when (val result=work.run {
            // Copy only bounded application DATA in the original admitted preparation.
            // Its actual native task cleanup owns this buffer even after cancellation.
            if (businessPublicationDependencies != null &&
                businessPublicationDependencies.size > SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES)
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            val dependencies=businessPublicationDependencies?.copyOf()
            object: AndroidOwnedTask<Unit> {
                override fun execute() {
                    requireNotNull(environment).requireExistingOriginalObservation()
                    requireDataNative(); check(historyClaimed.get())
                    if (intent.purposeTag == NativeSerialPrivatePublicationRequestCodecV1.PURPOSE_TAG)
                        selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
                    check(roomFrame === frame); requireNotNull(roomDriver).requireAttached(frame)
                    requireNotNull(historyAnchor).retainRoomChild(frame, child, intent, installation, finalDeadline, protectedFinal,
                        businessPublications, dependencies)
                    requireDataNative()
                }
                override fun discard(value: Unit) = Unit
                override fun clearInputs() { dependencies?.fill(0) }
            }
        }) {
            is AndroidOwnedResult.Value -> Unit
            is AndroidOwnedResult.Unavailable -> throw NativeHistoryUnavailable(result.reason)
        }
    }
    override suspend fun prepareSerialPrivateRoomChild(frame: SelfOwnedNativeRoomFrame, child: NativeHistoryRecordedHeadV2,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1,
        declaration: NativeHistoryProtectedFinalDeclaration): Unit {
        when (val result = work.run {
            val accessInputs = access.copyNativeInputs()
            val originInputs = try { origin.copyNativeInputs() }
            catch (failure: Throwable) { accessInputs.clear(); throw failure }
            object : AndroidOwnedTask<Unit> {
                override fun execute() {
                    requireNotNull(environment).requireExistingOriginalObservation(); requireDataNative()
                    serialPrivateRequire(historyClaimed.get() && originalPeer?.isCurrent() == true && roomFrame === frame &&
                        frame.ownerIdentity === ownerToken && frame.approvedOperationId == null)
                    requireNotNull(roomDriver).requireAttached(frame)
                    val actual = requireNotNull(historyAnchor).requireSameOriginalCut(frame.original)
                    val record = actual.record
                    serialPrivateRequire(record.parent != null && record.pending == null && record.database != null &&
                        record.issuerAcknowledgement != null)
                    val requests = decodeSerialPrivateRequests(accessInputs, originInputs)
                    try {
                        requireSerialPrivateRequestPair(record, requests, accessInputs, originInputs)
                        serialPrivateRequire(record.protectedFinalDecisions.none {
                            it.declaration.originalRequestId == requests.access.requestId
                        })
                        val intent = accessInputs.intent(requests.access, declaration.actorId)
                        declaration.requireIntent(intent)
                        if (requests.access.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE) {
                            // The requester origin must predate its native record; matching old
                            // native bytes without an origin can never be adopted by a new actor.
                            val proposal = requests.origin.originalProposal()
                            try { sameBusinessPublicationsNative().requireFreshPrivateSerialOrigin(proposal) }
                            finally { proposal.discardUndeliveredNativeContent() }
                        } else {
                            val originalDecision = requireSerialPrivateDecision(record, requests.origin, originInputs)
                            serialPrivateRequire(originalDecision.declaration.actorId == declaration.actorId)
                        }
                        requireSerialPrivateAccessNow(declaration, null)
                        try { NativeHistoryFinalDecisionCapacity.requireBeforeRoomSubmission(record) }
                        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED) }
                        requireNotNull(historyAnchor).retainRoomChild(frame, child, intent, null, null, declaration, null, null)
                        requireDataNative()
                    } catch (_: IllegalArgumentException) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
                    } catch (_: LocalCompanyAuthorityRefusal) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
                    } finally { requests.clear() }
                }
                override fun discard(value: Unit) = Unit
                override fun clearInputs() { accessInputs.clear(); originInputs.clear() }
            }
        }) {
            is AndroidOwnedResult.Value -> Unit
            is AndroidOwnedResult.Unavailable -> throw NativeHistoryUnavailable(result.reason)
        }
    }

    private class SerialPrivateRequests(
        val access: NativeSerialPrivatePublicationRequestV1,
        val origin: NativeSerialPrivatePublicationRequestV1,
    ) {
        fun clear() { access.clearOriginalContent(); origin.clearOriginalContent() }
    }

    private fun decodeSerialPrivateRequests(access: NativeSerialPrivateRequestInputsV1,
        origin: NativeSerialPrivateRequestInputsV1): SerialPrivateRequests {
        var accessRequest: NativeSerialPrivatePublicationRequestV1? = null
        return try {
            val decodedAccess = NativeSerialPrivatePublicationRequestCodecV1.decode(access.requestBytes); accessRequest = decodedAccess
            SerialPrivateRequests(decodedAccess, NativeSerialPrivatePublicationRequestCodecV1.decode(origin.requestBytes))
        } catch (_: IllegalArgumentException) {
            accessRequest?.clearOriginalContent(); selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        } catch (failure: Throwable) {
            accessRequest?.clearOriginalContent(); throw failure
        }
    }

    private fun serialPrivateRequire(condition: Boolean) {
        if (!condition) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    }

    private fun requireSerialPrivateRequestPair(record: NativeHistoryAnchorRecordV2, requests: SerialPrivateRequests,
        access: NativeSerialPrivateRequestInputsV1, origin: NativeSerialPrivateRequestInputsV1) {
        serialPrivateRequire(requests.origin.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE &&
            requests.access.sameTarget(requests.origin) && requests.origin.scope == record.allocation.coordinates.scope &&
            requests.origin.originalAllocationDigest == record.allocation.digest)
        if (requests.access.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE) {
            serialPrivateRequire(access.requestBytes.contentEquals(origin.requestBytes) &&
                access.dependencyBytes.contentEquals(origin.dependencyBytes) && access.factBytes.contentEquals(origin.factBytes))
        } else serialPrivateRequire(requests.access.originRequestId == requests.origin.requestId &&
            requests.access.originRequestDigest == sha256(origin.requestBytes))
    }

    /** Select only from the reauthenticated actual anchor, never a caller decision DTO. */
    private fun requireSerialPrivateDecision(record: NativeHistoryAnchorRecordV2,
        request: NativeSerialPrivatePublicationRequestV1, inputs: NativeSerialPrivateRequestInputsV1): NativeHistoryProtectedFinalDecision {
        val decision = record.protectedFinalDecisions.singleOrNull { it.declaration.originalRequestId == request.requestId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        serialPrivateRequire(decision.scope == record.allocation.coordinates.scope &&
            decision.originalAllocationDigest == record.allocation.digest &&
            decision.declaration.originalRequestDigest == sha256(inputs.requestBytes))
        val intent = inputs.intent(request, decision.declaration.actorId)
        decision.declaration.requireIntent(intent)
        val intentBytes = SelfOwnedNativeHistoryWireV2.encodeIntent(intent)
        try { serialPrivateRequire(sha256(intentBytes) == decision.originalIntentDigest) }
        finally { intentBytes.fill(0) }
        return decision
    }

    /** Native issuer/boot/elapsed observation; historical origin expiry is deliberately not renewed. */
    private fun requireSerialPrivateAccessNow(declaration: NativeHistoryProtectedFinalDeclaration,
        finalObservation: LocalAuthenticationObservation?) {
        val sample = historyClockSampleNative()
        try { declaration.requireObservation(LocalAuthenticationObservation(sample.issuerContextId, sample.moment)) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) }
        finalObservation?.let {
            serialPrivateRequire(sample.issuerContextId == it.issuerContextId && sample.moment.bootId == it.moment.bootId &&
                sample.moment.elapsedMillis >= it.moment.elapsedMillis)
        }
    }

    override fun captureRoomFinalDeadlineNow(frame: SelfOwnedNativeRoomFrame, finalWall: com.ukkera.pos.types.UtcInstant) {
        // Only registry/immutable field comparisons. The kernel already made its real final
        // wall and denial/elapsed decisions; this is not another clock or authorization read.
        check(roomFrame === frame && frame.ownerIdentity === ownerToken && !frame.consumed && frame.finalized &&
            frame.nativeBegan && frame.nativeDisposition == SelfOwnedNativeSqlDisposition.UNKNOWN && frame.finalDeadlineDecision == null)
        val declaration = checkNotNull(frame.finalDeadline)
        frame.finalDeadlineDecision = NativeHistoryFinalDeadlineDecision(frame.original.record.allocation.coordinates.scope,
            frame.original.record.allocation.digest, checkNotNull(frame.preparedChild), checkNotNull(frame.preparedIntentDigest),
            declaration, finalWall)
    }
    override fun finishRoomFrameNow(frame: SelfOwnedNativeRoomFrame) {
        check(roomFrame === frame && frame.ownerIdentity === ownerToken && !frame.consumed && !frame.finalized &&
            frame.nativeBegan && frame.nativeDisposition == SelfOwnedNativeSqlDisposition.UNKNOWN)
        work.requireActive()
        if (dataUseDeniedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val sample = requireNotNull(nativeSecurity).observeNativeOnly() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        if (frame.original.record.parent == null) {
            val attempt = requireNotNull(frame.original.record.attempt)
            check(newCeremoniesAllowedNoIo() && sample.issuerContextId == attempt.facts.issuerContextId &&
                sample.moment.bootId == attempt.facts.monotonicBootId && sample.moment.elapsedMillis < attempt.facts.deadlineElapsedMillis)
        }
        frame.protectedFinal?.let { declaration ->
            // The existing last native observation, not a caller's success flag or another
            // environment callback. No IO, suspension or clock follows these comparisons.
            check(frame.protectedFinalDecision == null && frame.original.record.parent != null)
            if (frame.finalDeadline != null) check(frame.approvedOperationId == declaration.originalRequestId)
            val observed = LocalAuthenticationObservation(sample.issuerContextId, sample.moment)
            declaration.requireObservation(observed)
            frame.protectedFinalDecision = NativeHistoryProtectedFinalDecision(frame.original.record.allocation.coordinates.scope,
                frame.original.record.allocation.digest, checkNotNull(frame.preparedChild), checkNotNull(frame.preparedIntentDigest),
                declaration, observed)
        }
        employeeEnrollmentsNative?.finishNativeNow(frame, sample)
        employeePolicyActivationsNative?.finishNativeNow(frame, sample)
        deviceAdmissionsNative?.finishNativeNow(frame, sample)
        frame.finalized = true
    }
    override suspend fun settleRoomFrameAfterOuter(frame: SelfOwnedNativeRoomFrame): SelfOwnedNativeRoomSettlement {
        // useWriterConnection schedules refreshAsync and returns WITHOUT awaiting that Job.
        // Drain its real children/NonCancellable scopes/runnable tails before touching raw SQL
        // or publishing native settlement; never join the idle Room supervisor here.
        work.roomCoroutines.drainBeforeNative()
        return historyOperation {
            check(roomFrame === frame); requireNotNull(roomDriver).requireAttached(frame)
            requireNotNull(roomDriver).releaseAfterOuter(frame)
            val result = requireNotNull(historyAnchor).settleRoomAfterActualOuter(frame)
            employeeEnrollmentsNative?.settleAfterActualOuter(frame, result)
            employeePolicyActivationsNative?.settleAfterActualOuter(frame, result)
            deviceAdmissionsNative?.settleAfterActualOuter(frame, result)
            roomFrame = null
            receiptMembershipInstallationSettlement = if (result.disposition == SelfOwnedNativeSqlDisposition.COMMITTED &&
                frame.installationChild != null && frame.original.record.parent == null && frame.preparedChild?.historySequence == 1L &&
                result.original.record.pending == null && result.original.record.parent == frame.preparedChild) result else null
            result
        }
    }
    override fun retainUnknownRoomFrame(frame: SelfOwnedNativeRoomFrame) {
        check(roomFrame === frame && frame.ownerIdentity === ownerToken)
        // Do not clear the registered pending/frame or release the P2 exclusion.
        denyDataUse(); work.nativeClosureUnknown()
    }
    private fun sameOriginalIssuerRegistryNative(): AndroidLocalProvisionerRegistry {
        val actualEnvironment = requireNotNull(environment)
        actualEnvironment.requireExistingOriginalObservation()
        provisionerCandidate?.let { it.assertContinuity(); return it }
        val registry = AndroidLocalProvisionerRegistry(actualEnvironment, work)
        provisionerCandidate = registry // Retain before its original-only descriptor acquisition.
        registry.openExistingNative()
        return registry
    }
    private fun requireOriginalIssuerRootNative(root: SelfOwnedSignedDocumentV1) {
        val expected = requireNotNull(journal).intent().source.root.copyWireBytes()
        val actual = root.copyWireBytes()
        try { if (!actual.contentEquals(expected)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { expected.fill(0); actual.fill(0) }
    }
    private fun requireLegacyOriginalIssuerNative(registry: AndroidLocalProvisionerRegistry) {
        // A genuine old original-only bootstrap is explicitly supported. Missing/corrupt
        // namespaces, files, keys or company state NEVER become benign employee absence.
        check(registry.companyBootstrapRecord() == null)
        val keys = AndroidLocalProvisionerKeys(requireNotNull(environment), registry, work)
        keys.assertAllOriginalKeys()
        val state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireOriginalIssuerRootNative(state.root); registry.assertContinuity()
    }
    private fun selectOriginalEmployeeHistoryNative(original: OriginalNativeHistorySnapshot): NativeEmployeeEnrollmentHistorySelection? {
        val registry = sameOriginalIssuerRegistryNative()
        if (registry.companyBootstrapRecord() == null) {
            requireLegacyOriginalIssuerNative(registry)
            return null // No employee source protocol/custody on a proved legacy bootstrap.
        }
        return sameEmployeeEnrollmentsNative().selectOriginalHistory(original)
    }
    private fun selectOriginalPolicyHistoryNative(original: OriginalNativeHistorySnapshot): NativeEmployeePolicyActivationHistorySelection? {
        val registry = sameOriginalIssuerRegistryNative()
        if (registry.companyBootstrapRecord() == null) {
            requireLegacyOriginalIssuerNative(registry)
            return null
        }
        return sameEmployeePolicyActivationsNative().selectOriginalHistory(original)
    }
    private fun selectOriginalBusinessPublicationHistoryNative(original: OriginalNativeHistorySnapshot): NativeBusinessPublicationHistorySelection? {
        val registry = sameOriginalIssuerRegistryNative()
        if (registry.companyBootstrapRecord() == null) {
            requireLegacyOriginalIssuerNative(registry)
            check(original.record.businessPublicationObservations.isEmpty() && original.record.pending?.businessPublications == null)
            return null
        }
        requireNotNull(historyAnchor).requireSameOriginalCut(original)
        return sameBusinessPublicationsNative().selectOriginalHistory(original)
    }
    private fun selectOriginalDeviceAdmissionHistoryNative(original: OriginalNativeHistorySnapshot): NativeDeviceAdmissionHistorySelection? {
        val registry = sameOriginalIssuerRegistryNative()
        if (registry.companyBootstrapRecord() == null) {
            requireLegacyOriginalIssuerNative(registry)
            return null // Genuine legacy absence never enables the new admission workflow.
        }
        return sameDeviceAdmissionsNative().selectOriginalHistory(original)
    }
    private fun captureOriginalBusinessPublicationObservationNative(frame: SelfOwnedNativeRoomFrame): NativeCompanyBusinessPublicationObservation? {
        val registry = sameOriginalIssuerRegistryNative()
        if (registry.companyBootstrapRecord() == null) {
            requireLegacyOriginalIssuerNative(registry)
            return null
        }
        return sameBusinessPublicationsNative().captureRoomObservation(frame)
    }
    private fun sameCompanyKeysNative(): AndroidLocalProvisionerKeys {
        companyKeys?.let { return it }
        val registry = sameOriginalIssuerRegistryNative()
        val keys = AndroidLocalProvisionerKeys(requireNotNull(environment), registry, work)
        val state = registry.readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        // Join to the ORIGINAL target's authenticated source, not merely matching company labels/IDs.
        requireOriginalIssuerRootNative(state.original.root)
        return keys.also { companyKeys = it }
    }
    private fun sameCompanyInstallationNative(): AndroidLocalCompanyAuthority {
        companyInstallation?.let { return it }
        val keys = sameCompanyKeysNative()
        return AndroidLocalCompanyAuthority(requireNotNull(environment), requireNotNull(provisionerCandidate), keys, work,
            requireNotNull(historyAnchor), companyIssuance)
            .also { companyInstallation = it }
    }
    /** Same original worker and keys; constructing this reader grants no history custody
     * and never initializes a missing native companion. Pre-claim trust reads and the
     * later once-only custody claim must use this exact object, not competing anchors. */
    private fun sameHistoryAnchorNative(): AndroidSelfOwnedHistoryAnchor {
        historyAnchor?.let { return it }
        return AndroidSelfOwnedHistoryAnchor(this, requireNotNull(environment), work,
            requireNotNull(namespace), requireNotNull(signing), requireNotNull(material),
            ownerToken, AndroidP256Primitives.newId()).also { historyAnchor = it }
    }

    private fun sameBusinessPublicationsNative(): AndroidLocalCompanyBusinessPublications {
        businessPublicationsNative?.let { return it }
        val keys = sameCompanyKeysNative()
        return AndroidLocalCompanyBusinessPublications(requireNotNull(environment), requireNotNull(provisionerCandidate), keys, work,
            companyIssuance, sameHistoryAnchorNative()).also { businessPublicationsNative = it }
    }

    private fun sameEmployeeEnrollmentsNative(): AndroidLocalCompanyEmployeeEnrollments {
        employeeEnrollmentsNative?.let { return it }
        val keys = sameCompanyKeysNative()
        return AndroidLocalCompanyEmployeeEnrollments(this, requireNotNull(environment), requireNotNull(provisionerCandidate),
            keys, work, requireNotNull(historyAnchor), companyIssuance).also { employeeEnrollmentsNative = it }
    }
    override suspend fun readOriginalEmployeeEnrollments(): LocalCompanyAuthorityResult<LocalCompanyEmployeeEnrollments> =
        businessOperation(operational = true) {
            sameEmployeeEnrollmentsNative().retained()
            employeeEnrollments ?: LocalCompanyEmployeeEnrollments(this, NativeLocalEmployeeProvisioningAuthority(this))
                .also { employeeEnrollments = it }
        }
    override suspend fun prepareEmployee(requested: LocalEmployeeEnrollmentDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> = businessOperation(operational = true, newIssuance = true) {
            sameEmployeeEnrollmentsNative().prepare(requested, original)
        }
    override suspend fun approveEmployee(action: LocalEmployeeEnrollmentAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt> {
        try { return businessOperation(operational = true, newIssuance = true, secret = secret) { owned ->
            sameEmployeeEnrollmentsNative().approve(action, login, requireNotNull(owned))
        } } finally { secret.fill('\u0000') }
    }
    override suspend fun reviewEmployee(id: com.ukkera.pos.types.DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
        businessOperation(operational = true, newIssuance = true) { sameEmployeeEnrollmentsNative().review(id) }
    override suspend fun recoverEmployeeApproval(id: com.ukkera.pos.types.DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt> =
        businessOperation(operational = true, newIssuance = true) { sameEmployeeEnrollmentsNative().recoverApproval(id) }
    override suspend fun recoverEmployeeConnection(id: com.ukkera.pos.types.DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAuthorityReceipt> =
        businessOperation(operational = true) { sameEmployeeEnrollmentsNative().recoverConnection(id) }
    override suspend fun retainedEmployees(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> =
        businessOperation(operational = true) { sameEmployeeEnrollmentsNative().retained() }
    override suspend fun originalDefinition(requestId: com.ukkera.pos.types.DomainId,
        login: LocalLoginIdentifier): LocalEmployeeEnrollmentDefinition? = employeeOperation {
        sameEmployeeEnrollmentsNative().originalDefinition(requestId, login)
    }
    override suspend fun beginDispatch(request: LocalProvisioningRequest): NativeLocalEmployeeEnrollmentDispatch = employeeOperation {
        sameEmployeeEnrollmentsNative().beginDispatch(request)
    }
    override suspend fun authorizeEffect(dispatch: NativeLocalEmployeeEnrollmentDispatch,
        request: LocalProvisioningRequest): NativeLocalEmployeeProvisioningPermit? = employeeOperation {
        sameEmployeeEnrollmentsNative().authorizeEffect(dispatch, request)
    }
    override suspend fun lookupReceipt(request: LocalProvisioningRequest): LocalProvisioningReceiptPermit? = employeeOperation {
        sameEmployeeEnrollmentsNative().lookupReceipt(request)
    }
    override fun effectPermitCurrent(permit: NativeLocalEmployeeProvisioningPermit): Boolean =
        employeeEnrollmentsNative?.effectPermitCurrent(permit) == true
    override fun ownCommitCurrent(permit: NativeLocalEmployeeProvisioningPermit): Boolean = employeeEnrollmentsNative?.ownCommitCurrent(permit) == true
    override fun receiptPermitCurrent(permit: LocalProvisioningReceiptPermit): Boolean = employeeEnrollmentsNative?.receiptCurrent(permit) == true
    override suspend fun finishKnownNoEffect(dispatch: NativeLocalEmployeeEnrollmentDispatch): Boolean = employeeOperation {
        check(roomFrame == null); sameEmployeeEnrollmentsNative().finishKnownNoEffect(dispatch)
    }
    override suspend fun acquireConsumedEmployee(requestId: com.ukkera.pos.types.DomainId): NativeEmployeeClientCustody = employeeOperation {
        sameEmployeeEnrollmentsNative().acquireConsumedEmployee(requestId)
    }
    override fun custodyCurrent(custody: NativeEmployeeClientCustody): Boolean = employeeEnrollmentsNative?.custodyCurrent(custody) == true
    override suspend fun acquireJournalAction(custody: NativeEmployeeClientCustody,
        action: NativeEmployeeClientJournalAction): NativeEmployeeJournalAllocation = employeeOperation {
        sameEmployeeEnrollmentsNative().acquireJournalAction(custody, action)
    }
    override fun journalAllocationCurrent(allocation: NativeEmployeeJournalAllocation): Boolean =
        employeeEnrollmentsNative?.allocationCurrent(allocation) == true
    override suspend fun claimJournalOpen(allocation: NativeEmployeeJournalAllocation): Unit = employeeOperation {
        sameEmployeeEnrollmentsNative().claimJournalOpen(allocation)
    }
    override fun journalOpenClaimed(allocation: NativeEmployeeJournalAllocation): Boolean =
        employeeEnrollmentsNative?.journalOpenClaimed(allocation) == true
    private suspend fun <T> employeeOperation(body: () -> T): T = historyOperation {
        check(originalPeer?.isCurrent() == true && roomDriver != null && roomFrame == null)
        body()
    }
    override suspend fun bindEmployeeProvisioningFrame(native: SelfOwnedNativeRoomFrame,
        permit: NativeLocalEmployeeProvisioningPermit): NativeEmployeeProvisioningFrame = historyOperation {
        check(roomFrame === native && native.ownerIdentity === ownerToken && originalPeer?.isCurrent() == true)
        requireNotNull(roomDriver).requireAttached(native)
        sameEmployeeEnrollmentsNative().bindFrame(native, permit)
    }
    override suspend fun retainEmployeePreparedEffect(frame: NativeEmployeeProvisioningFrame,
        intent: NativeHistoryOriginalIntentV2, evidence: LocalEmployeeEnrollmentEffectEvidence): Unit = historyOperation {
        check(roomFrame === frame.native && frame.native.ownerIdentity === ownerToken)
        requireNotNull(roomDriver).requireAttached(frame.native)
        sameEmployeeEnrollmentsNative().retainPreparedEffect(frame, intent, evidence)
    }

    private fun sameEmployeePolicyActivationsNative(): AndroidLocalCompanyEmployeePolicyActivations {
        employeePolicyActivationsNative?.let { return it }
        val keys = sameCompanyKeysNative()
        return AndroidLocalCompanyEmployeePolicyActivations(this, requireNotNull(environment), requireNotNull(provisionerCandidate),
            keys, work, requireNotNull(historyAnchor), companyIssuance).also { employeePolicyActivationsNative = it }
    }
    override suspend fun readOriginalEmployeePolicyActivations(): LocalCompanyAuthorityResult<LocalCompanyEmployeePolicyActivations> =
        businessOperation(operational = true) {
            sameEmployeePolicyActivationsNative().retained()
            employeePolicyActivations ?: LocalCompanyEmployeePolicyActivations(this, NativeLocalEmployeePolicyActivationAuthority(this))
                .also { employeePolicyActivations = it }
        }
    override suspend fun preparePolicy(requested: LocalEmployeePolicyActivationDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = businessOperation(operational = true, newIssuance = true) {
            sameEmployeePolicyActivationsNative().prepare(requested, original)
        }
    override suspend fun approvePolicy(action: LocalEmployeePolicyActivationAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        try { return businessOperation(operational = true, newIssuance = true, secret = secret) { owned ->
            sameEmployeePolicyActivationsNative().approve(action, login, requireNotNull(owned))
        } } finally { secret.fill('\u0000') }
    }
    override suspend fun reviewPolicy(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
        businessOperation(operational = true, newIssuance = true) { sameEmployeePolicyActivationsNative().review(id) }
    override suspend fun recoverPolicyApproval(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        businessOperation(operational = true, newIssuance = true) { sameEmployeePolicyActivationsNative().recoverApproval(id) }
    override suspend fun recoverPolicyConnection(id: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        businessOperation(operational = true) { sameEmployeePolicyActivationsNative().recoverConnection(id) }
    override suspend fun retainedPolicies(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> =
        businessOperation(operational = true) { sameEmployeePolicyActivationsNative().retained() }
    override suspend fun beginPolicyDispatch(request: LocalProvisioningRequest): NativeLocalEmployeePolicyActivationDispatch = employeeOperation {
        sameEmployeePolicyActivationsNative().beginDispatch(request)
    }
    override suspend fun authorizePolicyEffect(dispatch: NativeLocalEmployeePolicyActivationDispatch,
        request: LocalProvisioningRequest): NativeLocalEmployeePolicyActivationPermit? = employeeOperation {
        sameEmployeePolicyActivationsNative().authorizeEffect(dispatch, request)
    }
    override suspend fun lookupPolicyReceipt(request: LocalProvisioningRequest): LocalEmployeePolicyActivationReceiptPermit? = employeeOperation {
        sameEmployeePolicyActivationsNative().lookupReceipt(request)
    }
    override fun policyEffectPermitCurrent(permit: NativeLocalEmployeePolicyActivationPermit): Boolean =
        employeePolicyActivationsNative?.effectPermitCurrent(permit) == true
    override fun policyOwnCommitCurrent(permit: NativeLocalEmployeePolicyActivationPermit): Boolean =
        employeePolicyActivationsNative?.ownCommitCurrent(permit) == true
    override fun policyReceiptPermitCurrent(permit: LocalEmployeePolicyActivationReceiptPermit): Boolean =
        employeePolicyActivationsNative?.receiptCurrent(permit) == true
    override suspend fun finishPolicyKnownNoEffect(dispatch: NativeLocalEmployeePolicyActivationDispatch): Boolean = employeeOperation {
        check(roomFrame == null); sameEmployeePolicyActivationsNative().finishKnownNoEffect(dispatch)
    }
    override suspend fun bindEmployeePolicyActivationFrame(native: SelfOwnedNativeRoomFrame,
        permit: NativeLocalEmployeePolicyActivationPermit): NativeEmployeePolicyActivationFrame = historyOperation {
        check(roomFrame === native && native.ownerIdentity === ownerToken && originalPeer?.isCurrent() == true)
        requireNotNull(roomDriver).requireAttached(native)
        sameEmployeePolicyActivationsNative().bindFrame(native, permit)
    }
    override suspend fun retainPolicyPreparedEffect(frame: NativeEmployeePolicyActivationFrame,
        intent: NativeHistoryOriginalIntentV2, evidence: LocalEmployeePolicyActivationEffectEvidence): Unit = historyOperation {
        check(roomFrame === frame.native && frame.native.ownerIdentity === ownerToken)
        requireNotNull(roomDriver).requireAttached(frame.native)
        sameEmployeePolicyActivationsNative().retainPreparedEffect(frame, intent, evidence)
    }

    // BEGIN isolated same-original device-pairing preparation participant V1.
    private fun sameDevicePairingsNative(): AndroidLocalCompanyDevicePairings {
        devicePairingsNative?.let { return it }
        val keys = sameCompanyKeysNative()
        return AndroidLocalCompanyDevicePairings(this, requireNotNull(environment), requireNotNull(provisionerCandidate),
            keys, work, requireNotNull(historyAnchor), companyIssuance).also { devicePairingsNative = it }
    }
    override suspend fun readOriginalDevicePairings(): LocalCompanyAuthorityResult<LocalCompanyDevicePairings> =
        businessOperation(operational = true) {
            sameDevicePairingsNative().retained()
            devicePairings ?: LocalCompanyDevicePairings(this).also { devicePairings = it }
        }
    override suspend fun prepareDevicePairing(requested: LocalDevicePairingDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> = businessOperation(operational = true, newIssuance = true) {
            sameDevicePairingsNative().prepare(requested, original)
        }
    override suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction, possession: LocalDevicePairingPossession,
        original: OriginalNativeHistorySnapshot): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        businessOperation(operational = true, newIssuance = true) { sameDevicePairingsNative().proveTarget(action, possession, original) }
    override suspend fun approveDevicePairing(action: LocalDevicePairingAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        try { return businessOperation(operational = true, newIssuance = true, secret = secret) { owned ->
            sameDevicePairingsNative().approve(action, login, requireNotNull(owned))
        } } finally { secret.fill('\u0000') }
    }
    override suspend fun reviewDevicePairing(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        businessOperation(operational = true) { sameDevicePairingsNative().review(id) }
    override suspend fun recoverDevicePairingApproval(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> =
        businessOperation(operational = true) { sameDevicePairingsNative().recoverApproval(id) }
    override suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> =
        businessOperation(operational = true) { sameDevicePairingsNative().retained() }
    override suspend fun retireKnownUnsignedDevicePairing(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        businessOperation(operational = true, newIssuance = true) { sameDevicePairingsNative().retireKnownUnsigned(id) }
    override suspend fun recoverUnsignedDevicePairingRetirement(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        businessOperation(operational = true) { sameDevicePairingsNative().recoverUnsignedRetirement(id) }
    // END isolated same-original device-pairing preparation participant V1.

    private fun sameDeviceAdmissionsNative(): AndroidLocalCompanyDeviceAdmissions {
        deviceAdmissionsNative?.let { return it }
        val keys = sameCompanyKeysNative()
        return AndroidLocalCompanyDeviceAdmissions(this, requireNotNull(environment), requireNotNull(provisionerCandidate),
            keys, work, requireNotNull(historyAnchor), companyIssuance, sameDevicePairingsNative()).also { deviceAdmissionsNative = it }
    }
    internal fun requireOriginalDeviceAdmissionCensusNative(requested: LocalDevicePairingDefinition,
        original: OriginalNativeHistorySnapshot, dispatchAction: DomainId? = null) {
        check(original.ownerIdentity === ownerToken)
        sameDeviceAdmissionsNative().requireGenuineCensus(requested, original, dispatchAction)
    }
    override suspend fun beginDeviceAdmission(id: DomainId): NativeLocalDeviceAdmissionDispatch = employeeOperation {
        sameDeviceAdmissionsNative().beginDispatch(id)
    }
    override suspend fun authorizeDeviceAdmission(dispatch: NativeLocalDeviceAdmissionDispatch,
        request: LocalProvisioningRequest): NativeLocalDeviceAdmissionPermit? = employeeOperation {
        sameDeviceAdmissionsNative().authorizeEffect(dispatch, request)
    }
    override suspend fun finishUnusedDeviceAdmission(dispatch: NativeLocalDeviceAdmissionDispatch): Boolean = employeeOperation {
        check(roomFrame == null); sameDeviceAdmissionsNative().finishKnownNoEffect(dispatch)
    }
    override suspend fun lookupOriginalDeviceAdmission(id: DomainId): LocalDeviceAdmissionReceiptPermit? = employeeOperation {
        sameDeviceAdmissionsNative().lookupReceipt(id)
    }
    override fun deviceAdmissionPermitCurrent(permit: NativeLocalDeviceAdmissionPermit): Boolean =
        deviceAdmissionsNative?.effectPermitCurrent(permit) == true
    override fun deviceAdmissionOwnCommitCurrent(permit: NativeLocalDeviceAdmissionPermit): Boolean =
        deviceAdmissionsNative?.ownCommitCurrent(permit) == true
    override fun deviceAdmissionReceiptCurrent(permit: LocalDeviceAdmissionReceiptPermit): Boolean =
        deviceAdmissionsNative?.receiptCurrent(permit) == true
    override suspend fun bindDeviceAdmissionFrame(native: SelfOwnedNativeRoomFrame,
        permit: NativeLocalDeviceAdmissionPermit): NativeDeviceAdmissionFrame = historyOperation {
        check(roomFrame === native && native.ownerIdentity === ownerToken && originalPeer?.isCurrent() == true)
        requireNotNull(roomDriver).requireAttached(native)
        sameDeviceAdmissionsNative().bindFrame(native, permit)
    }
    override suspend fun retainDeviceAdmissionEffect(frame: NativeDeviceAdmissionFrame,
        intent: NativeHistoryOriginalIntentV2, evidence: LocalDeviceAdmissionEffectEvidence): Unit = historyOperation {
        check(roomFrame === frame.native && frame.native.ownerIdentity === ownerToken)
        requireNotNull(roomDriver).requireAttached(frame.native)
        sameDeviceAdmissionsNative().retainPreparedEffect(frame, intent, evidence)
    }

    /** Existing authenticated issuer ledger only; never inside a borrowed Room frame. */
    private fun readPurchasingPolicyLedgerNative(): Pair<NativePurchasingPolicyTrust, List<LocalPurchasingPublicationReference>> = try {
        if (roomFrame != null) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val keys = sameCompanyKeysNative()
        val registry = requireNotNull(provisionerCandidate)
        val state = registry.readCompanyState(keys) ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        requireOriginalIssuerRootNative(state.original.root)
        val scope = state.original.allocations.singleOrNull()?.body?.coordinates?.scope
            ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val fingerprint = AndroidP256Primitives.rootFingerprint(keys.rootBody())
        val candidate = purchasingPolicyTrust.get() ?: NativePurchasingPolicyTrust(scope, fingerprint).also {
            check(purchasingPolicyTrust.compareAndSet(null, it))
        }
        check(candidate.scope == scope && candidate.rootFingerprint == fingerprint)
        if (dataUseDeniedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val completed = state.policies.filter { it.phase == LocalCompanyPolicyPhaseV1.ISSUED }.map { record ->
            // readCompanyState verified exact canonical originals, enrollment/delegation,
            // the predecessor sequence and BOTH actual P-256 signatures before this point.
            val envelope = checkNotNull(record.signedPolicy)
            val bytes = envelope.copyBytes()
            try { VerifiedNativePurchasingPolicy(record.policy().publicationId, sha256(bytes), envelope) }
            finally { bytes.fill(0) }
        }
        val references = state.policies.map { record ->
            val body = record.policyBody.copyBytes()
            try {
                val policy = record.policy()
                LocalPurchasingPublicationReference(record.originalActionId, policy.policyId, policy.publicationId,
                    policy.revision, sha256(body), when (record.phase) {
                        LocalCompanyPolicyPhaseV1.PREPARED -> LocalPurchasingPublicationPhase.PREPARED
                        LocalCompanyPolicyPhaseV1.SIGNING_INTENT -> LocalPurchasingPublicationPhase.SIGNING_INTENT
                        LocalCompanyPolicyPhaseV1.ISSUED -> LocalPurchasingPublicationPhase.ISSUED
                    })
            } finally { body.fill(0) }
        }
        registry.assertContinuity()
        if (dataUseDeniedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        candidate.retainVerified(completed)
        candidate to references
    } catch (failure: Throwable) {
        // A failed/unknown ledger refresh cannot leave cached positive trust usable.
        purchasingPolicyTrust.get()?.retireNoIo()
        throw failure
    }

    /** Outside-slot failures deny the native owner first; caller cancellation revokes nothing. */
    private suspend fun <T> purchasingPolicyOperation(operational: Boolean, newIssuance: Boolean = false,
        secret: CharArray? = null, body: (CharArray?) -> T): LocalCompanyAuthorityResult<T> = try {
        businessOperation(operational, newIssuance, secret, body).also { outcome ->
            if (outcome is LocalCompanyAuthorityResult.Unavailable) when (outcome.nativeReason) {
                SelfOwnedInstallationFailureV1.INVALID_APPLICATION, SelfOwnedInstallationFailureV1.USER_LOCKED,
                SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN,
                SelfOwnedInstallationFailureV1.NATIVE_RETIRED, SelfOwnedInstallationFailureV1.UNAVAILABLE ->
                    denyDataUse()
                else -> Unit
            }
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        denyDataUse()
        throw failure
    }

    override suspend fun readOriginalPurchasingPolicies(): LocalCompanyAuthorityResult<LocalCompanyPurchasingPolicies> =
        purchasingPolicyOperation(operational = false) {
            val trust = readPurchasingPolicyLedgerNative().first
            purchasingPolicies ?: LocalCompanyPurchasingPolicies(this, trust).also { purchasingPolicies = it }
        }

    override suspend fun preparePurchasingPolicy(proposal: LocalCompanyPolicyProposal): LocalCompanyAuthorityResult<LocalCompanyPolicyAction> =
        purchasingPolicyOperation(operational = true, newIssuance = true) { sameCompanyInstallationNative().preparePolicyPublication(proposal) }

    override suspend fun approvePurchasingPolicy(action: LocalCompanyPolicyAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> {
        try {
            return purchasingPolicyOperation(operational = true, newIssuance = true, secret = secret) { owned ->
                sameCompanyInstallationNative().approvePolicyPublication(action, login, requireNotNull(owned)).also {
                    readPurchasingPolicyLedgerNative()
                }
            }
        } finally { secret.fill('\u0000') }
    }

    override suspend fun recoverPurchasingPolicy(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> =
        purchasingPolicyOperation(operational = true) {
            sameCompanyInstallationNative().recoverPolicyPublication(originalActionId).also { readPurchasingPolicyLedgerNative() }
        }

    override suspend fun retainedPurchasingPolicies(): LocalCompanyAuthorityResult<List<LocalPurchasingPublicationReference>> =
        purchasingPolicyOperation(operational = true) { readPurchasingPolicyLedgerNative().second }

    override suspend fun readOriginalBusinessPublications(): LocalCompanyAuthorityResult<LocalCompanyBusinessPublications> =
        businessOperation(operational = false) {
            val trust = sameBusinessPublicationsNative().readTrust()
            businessPublications ?: LocalCompanyBusinessPublications(this, trust).also { businessPublications = it }
        }

    override suspend fun prepare(proposal: CompanyBusinessPublicationProposal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
        businessOperation(operational = true, newIssuance = true) {
            if (proposal.kind.isOriginalSerialKindV1()) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
            sameBusinessPublicationsNative().prepare(proposal)
        }

    override suspend fun approve(action: CompanyBusinessPublicationAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> {
        try {
            return businessOperation(operational = true, newIssuance = true, secret = headSecret) { owned ->
                sameBusinessPublicationsNative().approve(action, headLogin, requireNotNull(owned))
            }
        } finally { headSecret.fill('\u0000') }
    }

    override suspend fun recover(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> =
        businessOperation(operational = true) {
            val native = sameBusinessPublicationsNative()
            native.requireNonSerialFacadeAction(originalActionId)
            native.recover(originalActionId)
        }

    override suspend fun review(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
        businessOperation(operational = true) {
            val native = sameBusinessPublicationsNative()
            native.requireNonSerialFacadeAction(originalActionId)
            native.review(originalActionId)
        }

    override suspend fun retained(): LocalCompanyAuthorityResult<List<CompanyBusinessPublicationReference>> =
        businessOperation(operational = true) {
            sameBusinessPublicationsNative().retained().filterNot { it.kind.isOriginalSerialKindV1() }
        }

    override suspend fun originalSerialRegistrationAllocation(): LocalCompanyAuthorityResult<NativeLocalSerialRegistrationAllocation> =
        businessOperation(operational = true) { sameBusinessPublicationsNative().originalSerialRegistrationAllocation() }

    override suspend fun prepareOriginalSerialAuthority(): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE,
            SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)

    override suspend fun planOriginalSerialAuthority(): LocalCompanyAuthorityResult<CompanyBusinessPublicationProposal> =
        businessOperation(operational = true, newIssuance = true) {
            val record = requireNotNull(historyAnchor).snapshotOriginal().record
            val originId = NativeSerialPrivatePublicationRequestCodecV1.originRequestId(record.allocation.coordinates.scope,
                record.allocation.digest, CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1, null)
            if (record.protectedFinalDecisions.any { it.declaration.originalRequestId == originId })
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            sameBusinessPublicationsNative().planOriginalSerialAuthority()
        }

    override suspend fun prepareOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = serialPrivateOperation<CompanyBusinessPublicationAction>(settled, access, origin,
            setOf(NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE, NativeSerialPrivatePublicationOperationV1.PREPARE),
            newIssuance = true, validateOutput = { native, proposal, request, result ->
                requireSerialPrivateReturnedAction(native, proposal, request, result.originalActionId)
            }, discardOutput = { it.proposal.discardUndeliveredNativeContent() }) { native, proposal, _ ->
            native.preparePrivateSerialPublication(proposal)
        }

    override suspend fun reviewOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = serialPrivateOperation<CompanyBusinessPublicationAction>(settled, access, origin,
            setOf(NativeSerialPrivatePublicationOperationV1.REVIEW), newIssuance = true,
            validateOutput = { native, proposal, request, result ->
                requireSerialPrivateReturnedAction(native, proposal, request, result.originalActionId)
            }, discardOutput = { it.proposal.discardUndeliveredNativeContent() }) { native, proposal, request ->
            native.reviewPrivateSerialPublication(requireNotNull(request.originalActionId), proposal)
        }

    override suspend fun recoverOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> = serialPrivateOperation<CompanyBusinessPublicationOutput>(settled, access, origin,
            setOf(NativeSerialPrivatePublicationOperationV1.RECOVER), newIssuance = false,
            validateOutput = { native, proposal, request, result ->
                requireSerialPrivateReturnedAction(native, proposal, request, result.originalActionId)
            }, discardOutput = { it.proposal.discardUndeliveredNativeContent() }) { native, proposal, request ->
            native.recoverPrivateSerialPublication(requireNotNull(request.originalActionId), proposal)
        }

    override suspend fun statusOriginalPrivateSerialPublication(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationReference?> = serialPrivateOperation<CompanyBusinessPublicationReference?>(settled, access, origin,
            setOf(NativeSerialPrivatePublicationOperationV1.STATUS), newIssuance = false,
            validateOutput = { native, proposal, _, result ->
                serialPrivateRequire(native.statusPrivateSerialPublication(proposal) == result)
            }, discardOutput = {}) { native, proposal, _ -> native.statusPrivateSerialPublication(proposal) }

    private fun requireSerialPrivateReturnedAction(native: AndroidLocalCompanyBusinessPublications,
        proposal: CompanyBusinessPublicationProposal, request: NativeSerialPrivatePublicationRequestV1, actionId: DomainId) {
        native.requirePrivateSerialPublication(actionId, proposal)
        request.originalActionId?.let { serialPrivateRequire(actionId == it) }
    }

    /**
     * Closed source-authored native tasks under the same original custody. Capsule bytes
     * are authenticated only by reconstructing the intents of actual retained decisions.
     * Current access must be the exact settled tip; origin is immutable historical evidence.
     */
    private suspend fun <T> serialPrivateOperation(settled: OriginalNativeHistorySnapshot,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1,
        operations: Set<NativeSerialPrivatePublicationOperationV1>, newIssuance: Boolean,
        validateOutput: (AndroidLocalCompanyBusinessPublications, CompanyBusinessPublicationProposal, NativeSerialPrivatePublicationRequestV1, T) -> Unit,
        discardOutput: (T) -> Unit,
        body: (AndroidLocalCompanyBusinessPublications, CompanyBusinessPublicationProposal, NativeSerialPrivatePublicationRequestV1) -> T):
        LocalCompanyAuthorityResult<T> {
        if (synchronized(contextGate) { contextPhase != ContextPhase.TRANSFERRED || dataUseDeniedNoIo() })
            return LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE,
                SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val outcome = work.run {
            val accessInputs = access.copyNativeInputs()
            val originInputs = try { origin.copyNativeInputs() }
            catch (failure: Throwable) { accessInputs.clear(); throw failure }
            object : AndroidOwnedTask<LocalCompanyAuthorityResult<T>> {
                override fun execute(): LocalCompanyAuthorityResult<T> {
                    requireNotNull(environment).requireExistingOriginalObservation(); requireDataNative()
                    serialPrivateRequire(historyClaimed.get() && originalPeer?.isCurrent() == true && roomDriver != null && roomFrame == null)
                    val actual = requireNotNull(historyAnchor).requireSameOriginalCut(settled)
                    val record = actual.record
                    serialPrivateRequire(record.database != null && record.parent != null && record.pending == null &&
                        record.issuerAcknowledgement != null)
                    val requests = decodeSerialPrivateRequests(accessInputs, originInputs)
                    var proposal: CompanyBusinessPublicationProposal? = null
                    try {
                        serialPrivateRequire(requests.access.operation in operations)
                        requireSerialPrivateRequestPair(record, requests, accessInputs, originInputs)
                        val accessDecision = requireSerialPrivateDecision(record, requests.access, accessInputs)
                        val originalDecision = requireSerialPrivateDecision(record, requests.origin, originInputs)
                        serialPrivateRequire(accessDecision.child == record.parent &&
                            accessDecision.declaration.actorId == originalDecision.declaration.actorId)
                        requireSerialPrivateAccessNow(accessDecision.declaration, accessDecision.observed)
                        if (newIssuance && !newCeremoniesAllowedNoIo())
                            selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
                        val native = sameBusinessPublicationsNative()
                        val originalProposal = requests.origin.originalProposal(); proposal = originalProposal
                        val result = try { body(native, originalProposal, requests.access) }
                        catch (refusal: LocalCompanyAuthorityRefusal) { return LocalCompanyAuthorityResult.Unavailable(refusal.reason) }
                        try {
                            // A revoked/expired delivery never exports the retained review or
                            // result. The native record remains available to a later valid access.
                            requireDataNative()
                            val after = requireNotNull(historyAnchor).requireSameOriginalCut(actual).record
                            val finalAccess = requireSerialPrivateDecision(after, requests.access, accessInputs)
                            val finalOriginal = requireSerialPrivateDecision(after, requests.origin, originInputs)
                            serialPrivateRequire(after.pending == null && finalAccess.child == after.parent &&
                                finalAccess == accessDecision && finalOriginal == originalDecision &&
                                finalAccess.declaration.actorId == finalOriginal.declaration.actorId)
                            validateOutput(native, originalProposal, requests.access, result)
                            requireDataNative()
                            requireSerialPrivateAccessNow(finalAccess.declaration, finalAccess.observed)
                            return LocalCompanyAuthorityResult.Value(result)
                        } catch (failure: Throwable) { discardOutput(result); throw failure }
                    } catch (_: IllegalArgumentException) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
                    } finally { proposal?.discardUndeliveredNativeContent(); requests.clear() }
                }
                override fun discard(value: LocalCompanyAuthorityResult<T>) {
                    if (value is LocalCompanyAuthorityResult.Value) discardOutput(value.value)
                }
                override fun clearInputs() { accessInputs.clear(); originInputs.clear() }
            }
        }
        return when (outcome) {
            is AndroidOwnedResult.Value -> outcome.value
            is AndroidOwnedResult.Unavailable -> LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, outcome.reason)
        }
    }

    override suspend fun readOriginalSerialAuthority(): LocalCompanyAuthorityResult<NativeOriginalLocalSerialAuthority> =
        businessOperation(operational = true) { sameBusinessPublicationsNative().readOriginalSerialAuthority() }

    override suspend fun readOriginalSerialCustodyControl(): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyControlV1> =
        serialCustodyOperation {
            // Authenticate the mandatory reservation and actual original P2 origin,
            // without requiring or minting business trust from a pending companion.
            sameBusinessPublicationsNative().readOriginalSerialCustody()
            serialCustodyControl ?: OriginalLocalSerialCustodyControlV1(this).also { serialCustodyControl = it }
        }

    override suspend fun readOriginalSerialCustody(): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1> =
        serialCustodyOperation { sameBusinessPublicationsNative().readOriginalSerialCustody() }

    override suspend fun recoverOriginalSerialCustody(originalActionId: DomainId): LocalCompanyAuthorityResult<OriginalLocalSerialCustodyReferenceV1> =
        serialCustodyOperation { sameBusinessPublicationsNative().recoverOriginalSerialCustody(originalActionId) }

    /** Lookup-only source recovery may precede Room qualification, never run in its frame. */
    private suspend fun <T> serialCustodyOperation(body: () -> T): LocalCompanyAuthorityResult<T> =
        businessOperation(operational = false) {
            if (!historyClaimed.get() || historyAnchor == null || roomFrame != null) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
            }
            body()
        }

    /** Shared native exclusion/watchdog, never IO inside a business transaction or a second key owner. */
    private suspend fun <T> businessOperation(operational: Boolean, newIssuance: Boolean = false,
        secret: CharArray? = null, body: (CharArray?) -> T): LocalCompanyAuthorityResult<T> {
        val contextAction = synchronized(contextGate) {
            when {
                contextPhase == ContextPhase.CLOSED || dataUseDeniedNoIo() -> return LocalCompanyAuthorityResult.Unavailable(
                    LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
                contextPhase == ContextPhase.TRANSFERRED -> false
                !operational && contextPhase == ContextPhase.OPEN -> { contextPhase = ContextPhase.ACTION; true }
                else -> return LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, SelfOwnedInstallationFailureV1.BUSY)
            }
        }
        try {
            return when (val outcome = work.run {
                val owned = secret?.let {
                    if (!selfOwnedPasswordInput(it)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
                    it.copyOf().also { _ -> it.fill('\u0000') }
                }
                object : AndroidOwnedTask<LocalCompanyAuthorityResult<T>> {
                    override fun execute(): LocalCompanyAuthorityResult<T> {
                        requireNotNull(environment).requireExistingOriginalObservation(); requireDataNative()
                        if (operational) {
                            val record = requireNotNull(historyAnchor).snapshotOriginal().record
                            if (!historyClaimed.get() || originalPeer?.isCurrent() != true || roomDriver == null || roomFrame != null ||
                                record.database == null || record.parent == null || record.pending != null || record.issuerAcknowledgement == null) {
                                selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
                            }
                        }
                        if (newIssuance && !newCeremoniesAllowedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
                        val result = try { LocalCompanyAuthorityResult.Value(body(owned)) }
                        catch (refusal: LocalCompanyAuthorityRefusal) { LocalCompanyAuthorityResult.Unavailable(refusal.reason) }
                        return try { requireDataNative(); result }
                        catch (failure: Throwable) { discard(result); throw failure }
                    }
                    override fun discard(value: LocalCompanyAuthorityResult<T>) {
                        if (value is LocalCompanyAuthorityResult.Value) {
                            val output = value.value as? CompanyBusinessPublicationOutput ?: return
                            if (output.proposal.kind.isOriginalSerialKindV1()) output.discardUndeliveredSerialContent()
                        }
                    }
                    override fun clearInputs() { owned?.fill('\u0000') }
                }
            }) {
                is AndroidOwnedResult.Value -> outcome.value
                is AndroidOwnedResult.Unavailable -> LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, outcome.reason)
            }
        } finally { if (contextAction) finishContextAction() }
    }
    override suspend fun readOriginalInstallationHumanReference(): LocalInstallationHumanReferenceV2 = historyOperation {
        sameCompanyInstallationNative().readOriginalInstallationHumanReference()
    }
    override suspend fun readOriginalCompanyAuthority(): LocalCompanyAuthorityView = historyOperation {
        sameCompanyInstallationNative().inspectCompanyAuthority()
    }
    override suspend fun prepareOriginalInstallation(snapshot: LocalBranchInstallationSnapshotV2): LocalCompanyInstallationAction = historyOperation {
        check(roomDriver == null && roomFrame == null)
        val proposal = requireNotNull(historyAnchor).retainOriginalProposal(snapshot)
        sameCompanyInstallationNative().prepareOriginalInstallationDefinition(proposal)
    }
    override suspend fun approveOriginalInstallation(action: LocalCompanyInstallationAction, headLogin: String,
        headSecret: CharArray): OriginalInstallationAttempt {
        try {
            return historyOperation {
                val approval = sameCompanyInstallationNative().approveOriginalInstallationDefinition(action, headLogin, headSecret)
                val attempt = requireNotNull(historyAnchor).readOriginalInstallationAttempt()
                requireNotNull(historyAnchor).signAndRetainOriginalTarget(attempt)
                attempt
            }
        } finally { headSecret.fill('\u0000') }
    }
    override suspend fun armOriginalInstallation(attempt: OriginalInstallationAttempt): Unit = historyOperation {
        check(roomDriver == null && roomFrame == null)
        requireNotNull(historyAnchor).armOriginalSql(attempt)
    }
    override suspend fun observeOriginalInstallationCommit(attempt: OriginalInstallationAttempt): LocalInstallationCommitFactsV2 = historyOperation {
        val frame=checkNotNull(roomFrame)
        check(frame.nativeBegan && frame.nativeDisposition==SelfOwnedNativeSqlDisposition.UNKNOWN && !frame.finalized)
        val anchor=requireNotNull(historyAnchor)
        check(anchor.readOriginalInstallationAttempt(attempt)===attempt)
        val cut=anchor.snapshotOriginal(); val facts=attempt.facts; val sample=historyClockSampleNative()
        // Keep distinct static failure locations for native diagnostics. No observed identity,
        // anchor, clock value or attempt fact is logged; every original condition remains.
        check(cut.record.attempt?.phase==NativeHistoryAttemptPhaseV2.DATABASE_BOUND)
        check(cut.record.parent==null)
        check(cut.record.pending==null)
        check(sample.issuerContextId==facts.issuerContextId)
        check(sample.moment.bootId==facts.monotonicBootId)
        check(sample.moment.elapsedMillis>=facts.observedElapsedMillis)
        check(sample.moment.elapsedMillis<facts.deadlineElapsedMillis)
        val wall=System.currentTimeMillis(); check(wall>=0)
        LocalInstallationCommitFactsV2(sample.issuerContextId,facts.nativeCustodyLifetimeId,sample.moment.bootId,
            sample.moment.elapsedMillis,wall,cut.recordDigest)
    }
    override fun admitOriginalRoomAfterClosedRead(afterClose: OriginalNativeHistorySnapshot): Unit =
        admitOriginalRoomAfterClosedRead(afterClose, null)

    override fun admitOriginalRoomAfterClosedRead(afterClose: OriginalNativeHistorySnapshot,
        data: SelfOwnedNativeReceiptMembershipData?) {
        synchronized(historyDeliveryGate) {
            work.requireActive()
            check(!dataUseDeniedNoIo() && historyDelivery===afterClose && afterClose.ownerIdentity===ownerToken && admittedRoomRead==null &&
                roomDriver==null && historyStorage==null && historyPager==null && afterClose.record.pending==null &&
                afterClose.record.parent!=null && afterClose.record.issuerAcknowledgement!=null)
            check(historyReceiptMembershipDelivery === data)
            val receiptData = if (data != null) checkNotNull(receiptMembershipData).also { candidate ->
                check(receiptMembershipDataHandle === data && data.controller === candidate)
                candidate.requirePreparedForDeliveryNoIo()
            } else null
            val employees=historyEmployeeDelivery
            // The concrete startup called this only after its complete semantic scan has no
            // holds. Native additionally consumes the very same actual seed-read/close token.
            if (employees != null) requireNotNull(employeeEnrollmentsNative).admitOriginalHistory(employees,afterClose)
            else check(employeeEnrollmentsNative == null) // Proved legacy original-only bootstrap.
            val policies=historyPolicyDelivery
            if (policies != null) requireNotNull(employeePolicyActivationsNative).admitOriginalHistory(policies,afterClose)
            else check(employeePolicyActivationsNative == null)
            historyBusinessPublicationDelivery?.requireActualTraversal()
            val admissions=historyDeviceAdmissionDelivery
            if (admissions != null) requireNotNull(deviceAdmissionsNative).admitOriginalHistory(admissions,afterClose)
            else check(deviceAdmissionsNative == null)
            receiptData?.activatePreparedNoIo()
            historyDelivery=null; historyEmployeeDelivery=null; historyPolicyDelivery=null; admittedRoomRead=afterClose
            historyBusinessPublicationDelivery=null
            historyDeviceAdmissionDelivery=null
            historyReceiptMembershipDelivery=null
        }
    }
    override suspend fun originalLocalPeer(): LocalAuthenticatedPeer = historyOperation {
        originalPeer?.let { check(it.isCurrent()); return@historyOperation it }
        val record=requireNotNull(historyAnchor).snapshotOriginal().record
        val admission=checkNotNull(record.definition).definition.masterAdmission
        val c=record.allocation.coordinates
        val keyId=c.targetSigningKeyId.value
        val fingerprint=materialAtOpen.targetSigningFingerprint
        check(admission.deviceId==c.deviceId && admission.scope==c.scope && admission.admitted &&
            (admission.publicKeyIdentifier==null || admission.publicKeyIdentifier==keyId) &&
            (admission.publicFingerprint==null || admission.publicFingerprint==fingerprint))
        LocalAuthenticatedPeer(AndroidP256Primitives.newId(),c.scope,c.deviceId,keyId,fingerprint) { !dataUseDeniedNoIo() }
            .also { originalPeer=it }
    }
    override suspend fun acknowledgeOriginalInstallation(): Unit = historyOperation {
        val completion = requireNotNull(historyAnchor).completedOriginalForIssuer()
        sameCompanyInstallationNative().acknowledgeOriginalInstallationReceipt(completion)
    }
    override suspend fun prepareOriginalAcknowledgementRecovery(afterClose: OriginalNativeHistorySnapshot): Unit = historyOperation {
        consumeClosedOriginalForRecovery(afterClose)
        requireNotNull(historyAnchor).recoverSettledOriginalForIssuer(afterClose)
        requireDataNative()
    }
    override suspend fun fenceOriginalPendingInstallation(): OriginalNativeHistorySnapshot = historyOperation {
        requireUnusedOriginalRecoveryLifetime()
        val fenced = requireNotNull(historyAnchor).fencePendingOriginalInstallation()
        ceremoniesDenied.set(true) // Denial only; actual retained native read/ACK work continues.
        fenced
    }
    override suspend fun settleOriginalPendingInstallation(afterClose: OriginalNativeHistorySnapshot,
        child: SelfOwnedInstallationChild): OriginalNativeHistorySnapshot = historyOperation {
        consumeClosedOriginalForRecovery(afterClose)
        requireNotNull(historyAnchor).settlePendingOriginalInstallation(afterClose, child)
    }
    private fun requireUnusedOriginalRecoveryLifetime() = synchronized(historyDeliveryGate) {
        work.requireActive()
        check(!dataUseDeniedNoIo() && admittedRoomRead == null && roomDriver == null && roomFrame == null &&
            historyStorage == null && historyClose == null && historyPager == null && historyPagerClose == null && historyDelivery == null)
    }
    private fun consumeClosedOriginalForRecovery(afterClose: OriginalNativeHistorySnapshot) {
        // No caller-supplied current predicate or copied receipt can stand in for the actual
        // close token consumed by reobserveAfterActualClose. No Room pool is opened yet.
        synchronized(historyDeliveryGate) {
            work.requireActive()
            check(!dataUseDeniedNoIo() && historyDelivery === afterClose && afterClose.ownerIdentity === ownerToken &&
                admittedRoomRead == null && roomDriver == null && roomFrame == null &&
                historyStorage == null && historyClose == null && historyPager == null && historyPagerClose == null)
            historyDelivery = null; historyEmployeeDelivery = null; historyPolicyDelivery = null // C1 recovery grants no employee/policy custody.
            historyBusinessPublicationDelivery = null
            historyDeviceAdmissionDelivery = null
            if (historyReceiptMembershipDelivery != null) receiptMembershipData?.abandonDeliveryNoIo()
            historyReceiptMembershipDelivery = null
        }
    }
    override val runtimeSecurity: SelfOwnedRuntimeSecurity
        get() {
            check(historyClaimed.get() && !dataUseDeniedNoIo())
            runtimeAdapters?.let { return it }
            val native = requireNotNull(nativeSecurity)
            val coordinates = materialAtOpen.coordinates
            val protection = object : DeviceProtectedLocalStorage {
                override val protectionStatus: KeyProtectionStatus get() =
                    if (dataUseDeniedNoIo()) KeyProtectionStatus.UNQUALIFIED_CONTEXT else KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
                override suspend fun seal(reference: ProtectedStorageKeyReference, binding: ProtectedLocalBinding, plaintext: ByteArray): LocalSealResult =
                    native.seal(reference, binding, plaintext)
                override suspend fun open(reference: ProtectedStorageKeyReference, expected: ProtectedLocalBinding, sealed: SealedLocalRecord): LocalOpenResult =
                    native.open(reference, expected, sealed)
            }
            val passwords = object : LocalPasswordHasher {
                override suspend fun create(password: CharArray, binding: CredentialBinding, pepperReference: ProtectedPepperReference): PasswordHashingResult =
                    native.createPassword(password, binding, pepperReference)
                override suspend fun verify(password: CharArray, expectedBinding: CredentialBinding, record: PasswordHashRecord): PasswordVerificationResult =
                    native.verifyPassword(password, expectedBinding, record)
            }
            val clock = LocalAuthenticationEnvironment {
                native.observeNativeOnly()?.let { LocalAuthenticationObservation(it.issuerContextId, it.moment) }
            }
            val entropy = LocalAuthenticationEntropy { bytes ->
                check(bytes.size in 1..4096 && !dataUseDeniedNoIo()); work.requireActive()
                val fresh = AndroidP256Primitives.randomBytes(bytes.size)
                try { fresh.copyInto(bytes) } finally { fresh.fill(0) }
            }
            return SelfOwnedRuntimeSecurity(protection, passwords, clock, entropy,
                ProtectedStorageKeyReference(coordinates.storageKeyId), ProtectedPepperReference(coordinates.pepperKeyId))
                .also { runtimeAdapters = it }
        }

    override fun denyDataUse() {
        companyIssuance.deny(null)
        synchronized(historyDeliveryGate) {
            dataDenied.set(true); ceremoniesDenied.set(true)
            historyDelivery=null; historyEmployeeDelivery=null; historyPolicyDelivery=null
            historyBusinessPublicationDelivery=null
            historyDeviceAdmissionDelivery=null
            historyReceiptMembershipDelivery=null
            receiptMembershipData?.denyUseNoIo()
        }
        // Deny first. A concurrent first publication either precedes this get, or its
        // ledger read observes denial and irreversibly retires its own new handle.
        purchasingPolicyTrust.get()?.retireNoIo()
        nativeSecurity?.retireNoIo()
        work.retire() // Denial only: no descriptor close, lease release or proof of death.
    }

    /** Internal bounded denial observation. A false value is NOT native or Room custody proof. */
    fun dataUseDeniedNoIo(): Boolean = dataDenied.get() || actuallyClosed.get() || !opened.get()

    /** Only same-owner authored native tasks call this, before AND after actual data operations. */
    fun requireDataNative() {
        if (dataUseDeniedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        readOriginalStateNative()
        if (dataUseDeniedNoIo()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    }

    private fun readOriginalStateNative(): SelfOwnedNativeJournalStateV1 {
        requireNativeLifetime()
        val ownedSigning = requireNotNull(signing)
        ownedSigning.assertAliasNamespace()
        val state = requireNotNull(journal).readState(ownedSigning, requireNotNull(material))
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!state.newCeremoniesAllowed) ceremoniesDenied.set(true)
        requireNativeLifetime()
        return state
    }

    private fun requireNativeLifetime() {
        if (dataDenied.get() || actuallyClosed.get() || nativeCloseStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        work.requireActive(); requireNotNull(environment).requireCurrent(); requireNotNull(namespace).assertContinuity(); work.requireActive()
    }

    private fun view(state: SelfOwnedNativeJournalStateV1): SelfOwnedNativeMaterialView {
        val original = requireNotNull(journal).intent().source.preparationBody
        val point = requireNotNull(signing).originalPoint()
        val fingerprint = try { AndroidP256Primitives.hashHex(point) } finally { point.fill(0) }
        val binding = state.originalBinding.copyWireBytes()
        val digest = try { AndroidP256Primitives.hashHex(binding) } finally { binding.fill(0) }
        val bindingBody = state.originalBinding.body()
        val storageSecurity = try {
            when (SelfOwnedNativeInstallationWireV1.decodeBindingBody(bindingBody).storageSecurity) {
                SelfOwnedNativeSecurityV1.SOFTWARE -> SelfOwnedStorageKeySecurity.SOFTWARE
                SelfOwnedNativeSecurityV1.TRUSTED_ENVIRONMENT -> SelfOwnedStorageKeySecurity.TRUSTED_ENVIRONMENT
                SelfOwnedNativeSecurityV1.STRONGBOX -> SelfOwnedStorageKeySecurity.STRONGBOX
                SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED -> SelfOwnedStorageKeySecurity.HARDWARE_KIND_UNSPECIFIED
            }
        } finally { bindingBody.fill(0) }
        return SelfOwnedNativeMaterialView(original.coordinates, original.target, original.branchName,
            original.protection, fingerprint, digest, storageSecurity, state.newCeremoniesAllowed && !ceremoniesDenied.get())
    }

    /** Private immutable-result tasks, not an injected signer/custody/closure callback boundary. */
    private fun <T> metadataTask(body: () -> T): AndroidOwnedTask<T> = object : AndroidOwnedTask<T> {
        override fun execute(): T = try {
            requireDataNative()
            body().also { requireDataNative() }
        } catch (failure: Throwable) { denyDataUse(); throw failure }
        override fun discard(value: T) = Unit // Only pure immutable native descriptions/Unit.
        override fun clearInputs() = Unit
    }

    override suspend fun close() {
        denyDataUse()
        work.requestClose { closeNative() }.await()
    }

    /** Actual close/discard is always under the SAME independent owned-work watchdog. */
    fun closeNative() {
        denyDataUse()
        if (actuallyClosed.get()) return
        if (nativeCloseStarted) { work.nativeClosureUnknown(); selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED) }
        work.requireOriginalRoomClosedBeforeNativeClose(roomDriver)
        nativeCloseStarted = true
        try {
            // Actual SDK stop must return before ANY key/namespace participant or lease
            // is released. Failure/unreturned construction keeps this exact owner retained.
            masterSdkOwner?.let { it.closeNative(); masterSdkOwner = null }
            masterSdkOriginal = null
            // Actual raw/pager cleanup precedes every namespace/lock descriptor close.
            roomDriver?.closeNative(); roomDriver=null
            receiptMembershipData?.closeNative()
            historyStorage?.closeNative(); historyStorage=null; historyClose=null
            receiptMembershipData=null; receiptMembershipDataHandle=null; receiptMembershipInstallationSettlement=null
            historyPager?.closeNative(); historyPager=null; historyPagerClose=null
            provisionerCandidate?.closeNative()
            provisionerCandidate = null
            namespace?.closeNative()
            nativeSecurity = null
            actuallyClosed.set(true)
            // Work, not this participant, releases the process lease AFTER actual close returns.
        } catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
    }

    override fun toString(): String = "AndroidSelfOwnedInstallationLifetimeV1([REDACTED])"
}

/** Only the actual first-binding producer registers this capability, and only until its
 * first force/readback returns. It cannot authorize a normal history write or Room open. */
internal class AndroidOriginalCompanionInitialization internal constructor(
    internal val owner: AndroidSelfOwnedInstallationLifetime,
    internal val anchor: AndroidSelfOwnedHistoryAnchor,
    internal val namespace: AndroidSelfOwnedDatabaseNamespace,
    internal val signing: AndroidSelfOwnedInstallationSigning,
) {
    fun requireAnchor(actual: AndroidSelfOwnedHistoryAnchor) { check(anchor===actual); owner.requireInitialCompanion(this) }
    fun requireSigning(actual: AndroidSelfOwnedInstallationSigning) { check(signing===actual); owner.requireInitialCompanion(this) }
}
