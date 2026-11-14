package com.ukkera.pos.security

import android.app.Application

/**
 * Android native custody for the shared original owner: the same Application/KeyStore/Os objects as
 * before the owner moved to the shared JCA source set. Narrowing casts only accept objects this
 * platform itself created; anything else is a programming error, never an alternate implementation.
 */
internal class AndroidSelfOwnedOriginalPlatform(private val application: Application) : SelfOwnedOriginalPlatform {
    override val process: SelfOwnedProcessPlatform get() = AndroidSelfOwnedProcess

    override fun captureEnvironment(work: AndroidOwnedInstallationWork, profile: SelfOwnedNativeProfileV1,
        preparation: Boolean): SelfOwnedOriginalEnvironment =
        if (preparation) AndroidSelfOwnedEnvironment.capture(application, work, profile)
        else AndroidSelfOwnedEnvironment.captureExistingOriginal(application, work, profile)

    override fun databaseNamespace(environment: SelfOwnedOriginalEnvironment, work: AndroidOwnedInstallationWork,
        ownerToken: Any): SelfOwnedOriginalDatabaseNamespace =
        AndroidSelfOwnedDatabaseNamespace(application, environment.android(), work, ownerToken)

    override fun signing(environment: SelfOwnedOriginalEnvironment, journal: AndroidSelfOwnedInstallationJournal,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalSigning =
        AndroidSelfOwnedInstallationSigning(environment.android(), journal, work)

    override fun dataKeys(environment: SelfOwnedOriginalEnvironment, journal: AndroidSelfOwnedInstallationJournal,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalDataKeys =
        AndroidSelfOwnedDataKeyMaterial(environment.android(), journal, work)

    override fun masterSdkOwner(work: AndroidOwnedInstallationWork): SelfOwnedMasterSdkOwner =
        AndroidDevicePairingSdkOwnerV2(application, work)

    override fun provisionerRegistry(environment: SelfOwnedOriginalEnvironment,
        work: AndroidOwnedInstallationWork): LocalProvisionerRegistryStore =
        AndroidLocalProvisionerRegistry(environment.android(), work)

    override fun provisionerKeys(environment: SelfOwnedOriginalEnvironment, registry: SelfOwnedOriginalRegistryStore,
        work: AndroidOwnedInstallationWork): LocalProvisionerNativeKeys =
        AndroidLocalProvisionerKeys(environment.android(), registry as AndroidLocalProvisionerRegistry, work)

    override fun historyWorkspace(source: AndroidSelfOwnedHistoryReadStorage, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, original: OriginalNativeHistorySnapshot): SelfOwnedHistoryWorkspaceStore =
        AndroidSelfOwnedHistoryWorkspace(source, work, environment.android(), original)

    override fun receiptMembership(owner: AndroidSelfOwnedInstallationLifetime, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, readIdentity: Any?): SelfOwnedReceiptMembershipStore =
        AndroidSelfOwnedReceiptMembershipData(owner, work, environment.android(), readIdentity)

    private fun SelfOwnedOriginalEnvironment.android(): AndroidSelfOwnedEnvironment = this as AndroidSelfOwnedEnvironment
}

/**
 * Actual original native target owner. Neither entry point opens SQLite/Room, creates an
 * employee, signs a complete definition, reads an installation receipt or grants writer use.
 * There is no supplied packet/root/path/key/provider/clock factory. API24+ and a nondebuggable
 * application are required; exact StrongBox protection additionally needs API31+ evidence.
 */
public object AndroidSelfOwnedInstallationOwner {
    /** Explicit preparation/resume of ONLY the original P1-retained allocation on this device. */
    public suspend fun prepareOriginal(application: Application): SelfOwnedNativeOpenResult =
        SelfOwnedInstallationOwnerCore.prepare(AndroidSelfOwnedOriginalPlatform(application), SelfOwnedNativeProfileV1.LIVE)

    /**
     * Original completed target only: no key generation, P1 registry, installer or cashier
     * renewal. Refusal is unresolved, never a safe-absence or create-new-company permission.
     * Every acquisition/current observation is existing-only; full-history/read admission is separate.
     */
    public suspend fun openOriginal(application: Application): SelfOwnedNativeOpenResult =
        SelfOwnedInstallationOwnerCore.open(AndroidSelfOwnedOriginalPlatform(application), SelfOwnedNativeProfileV1.LIVE)

    /** Explicit training-slot preparation; a distinct root/key/SDK namespace from live. */
    public suspend fun prepareTraining(application: Application,
        slot: LocalTrainingCompositionSlot): SelfOwnedNativeOpenResult =
        SelfOwnedInstallationOwnerCore.prepare(AndroidSelfOwnedOriginalPlatform(application), SelfOwnedNativeProfileV1.training(slot))

    /** Existing-only training-slot original; never creates or repairs a missing training root. */
    public suspend fun openTraining(application: Application,
        slot: LocalTrainingCompositionSlot): SelfOwnedNativeOpenResult =
        SelfOwnedInstallationOwnerCore.open(AndroidSelfOwnedOriginalPlatform(application), SelfOwnedNativeProfileV1.training(slot))

    /** Transfers a single claim from THIS actual context; never creates a second P2 lease. */
    public suspend fun claimOriginalHistoryCustody(actualContext: SelfOwnedLocalSecurityContext): SelfOwnedNativeHistoryClaimResult =
        actualContext.claimOriginalHistoryCustody()

    /** The downstream database factory gets only THIS original native driver's exact object. */
    public suspend fun openRoomDriver(custody: SelfOwnedNativeHistoryCustody): AndroidSelfOwnedRoomDriver =
        SelfOwnedInstallationOwnerCore.openRoomDriver(custody)
    public suspend fun finishRoomOpen(custody: SelfOwnedNativeHistoryCustody, driver: AndroidSelfOwnedRoomDriver): Unit =
        SelfOwnedInstallationOwnerCore.finishRoomOpen(custody, driver)

    /** Current original DATA for an already retained Host resource. This existing-only yield
     * closes no Master participant and never constructs or repairs an SDK owner. */
    public suspend fun yieldExistingMasterSdkForHost(custody: SelfOwnedNativeHistoryCustody,
        expectedCurrentSnapshot: OriginalNativeHistorySnapshot): LocalMasterSdkOriginal =
        SelfOwnedInstallationOwnerCore.yieldExistingMasterSdkForHost(custody, expectedCurrentSnapshot)
}
