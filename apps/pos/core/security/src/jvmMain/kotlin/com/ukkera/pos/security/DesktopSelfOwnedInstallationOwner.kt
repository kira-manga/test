package com.ukkera.pos.security

/**
 * Actual Windows/macOS original Master installation owner, the desktop counterpart of
 * `AndroidSelfOwnedInstallationOwner` over the SAME shared [SelfOwnedInstallationOwnerCore]. The
 * OS custody is observed from the running process ([DesktopNativeOriginalOs.current]); there is no
 * supplied path, key, provider or clock. An unsupported host refuses with UNSUPPORTED_PLATFORM.
 */
public object DesktopSelfOwnedInstallationOwner {
    /** [service] is the build's fixed expected Master service identity; the process token/signature decides. */
    public suspend fun prepareOriginal(service: SelfOwnedInstalledIdentityTargetV2): SelfOwnedNativeOpenResult =
        prepare(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.LIVE)
    public suspend fun openOriginal(service: SelfOwnedInstalledIdentityTargetV2): SelfOwnedNativeOpenResult =
        open(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.LIVE)
    public suspend fun prepareTraining(service: SelfOwnedInstalledIdentityTargetV2, slot: LocalTrainingCompositionSlot): SelfOwnedNativeOpenResult =
        prepare(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.training(slot))
    public suspend fun openTraining(service: SelfOwnedInstalledIdentityTargetV2, slot: LocalTrainingCompositionSlot): SelfOwnedNativeOpenResult =
        open(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.training(slot))

    public suspend fun claimOriginalHistoryCustody(actualContext: SelfOwnedLocalSecurityContext): SelfOwnedNativeHistoryClaimResult =
        actualContext.claimOriginalHistoryCustody()
    public suspend fun openRoomDriver(custody: SelfOwnedNativeHistoryCustody): AndroidSelfOwnedRoomDriver =
        SelfOwnedInstallationOwnerCore.openRoomDriver(custody)
    public suspend fun finishRoomOpen(custody: SelfOwnedNativeHistoryCustody, driver: AndroidSelfOwnedRoomDriver): Unit =
        SelfOwnedInstallationOwnerCore.finishRoomOpen(custody, driver)
    public suspend fun yieldExistingMasterSdkForHost(custody: SelfOwnedNativeHistoryCustody,
        expectedCurrentSnapshot: OriginalNativeHistorySnapshot): LocalMasterSdkOriginal =
        SelfOwnedInstallationOwnerCore.yieldExistingMasterSdkForHost(custody, expectedCurrentSnapshot)

    internal suspend fun prepare(os: DesktopOriginalOs, profile: SelfOwnedNativeProfileV1): SelfOwnedNativeOpenResult =
        SelfOwnedInstallationOwnerCore.prepare(DesktopSelfOwnedOriginalPlatform(os), profile)
    internal suspend fun open(os: DesktopOriginalOs, profile: SelfOwnedNativeProfileV1): SelfOwnedNativeOpenResult =
        SelfOwnedInstallationOwnerCore.open(DesktopSelfOwnedOriginalPlatform(os), profile)
}

/** Selection of the running host's genuine native OS custody. */
internal object DesktopNativeOriginalOs {
    fun current(service: SelfOwnedInstalledIdentityTargetV2): DesktopOriginalOs {
        val name = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            name.startsWith("windows") && service.kind.platform == SelfOwnedInstalledIdentityPlatformV2.WINDOWS &&
                service.kind.role == SelfOwnedInstalledIdentityRoleV2.MASTER_SERVICE -> WindowsDesktopOriginalOs.observe(service)
            name.startsWith("mac") && service is SelfOwnedMacServiceIdentityV2 -> MacDesktopOriginalOs.observe(service)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
    }
}
