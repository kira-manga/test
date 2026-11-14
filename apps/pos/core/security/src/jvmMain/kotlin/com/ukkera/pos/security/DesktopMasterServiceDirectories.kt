package com.ukkera.pos.security

import java.nio.file.Path

/**
 * Private directory of the running desktop Master SERVICE for its setup-console records (intents,
 * launch/lifecycle records) and local console endpoint. A sibling of the native custody base, never
 * inside it. Windows: owned by the service account with the exact private service DACL; macOS:
 * owned by the effective user, mode 0700, ACL- and link-free. A foreign or pre-created entry refuses.
 */
public object DesktopMasterServiceDirectories {
    private const val WINDOWS_NAME = "UkkeraPOSMasterSetup"
    private const val MAC_NAME = "com.ukkera.pos.master-setup"

    public fun setup(service: SelfOwnedInstalledIdentityTargetV2): Path = when (service) {
        is SelfOwnedWindowsPackagedServiceIdentityV2, is SelfOwnedWindowsServiceIdentityV2 -> windows(service)
        is SelfOwnedMacServiceIdentityV2 -> mac(service)
        else -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }

    private fun windows(service: SelfOwnedInstalledIdentityTargetV2): Path {
        WindowsOriginalNative.requireSupported()
        WindowsOriginalNative.requireNoImpersonation()
        val (serviceSid, accountSid) = WindowsSelfOwnedSigningKey.serviceCoordinates(service)
        WindowsSelfOwnedSigningKey.requireService(service, serviceSid, accountSid)
        val programData = System.getenv("ProgramData")?.takeIf { it.length >= 3 && it[1] == ':' }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        val path = programData.trimEnd('\\') + "\\" + WINDOWS_NAME
        val files = WindowsMasterFiles(path, serviceSid, accountSid)
        if (files.observe(path) == null) WindowsOriginalNative.desktopCreateDirectory(path, accountSid, serviceSid)
        val stamp = files.observe(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (stamp.kind != DesktopEntryKind.DIRECTORY || !stamp.ownerOnly || stamp.owner != accountSid) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return Path.of(path)
    }

    private fun mac(service: SelfOwnedMacServiceIdentityV2): Path {
        MacOriginalNative.requireSupported()
        if (MacInstalledApplicationIdentity.captureService(service) !is SelfOwnedInstalledIdentityResultV2.Data) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
        val path = Path.of(System.getProperty("user.home"), "Library", "Application Support", MAC_NAME)
        if (MacOriginalNative.lstat(path) == null) MacOriginalNative.mkdir(path)
        val stamp = MacOriginalNative.lstat(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!stamp.isDirectory || stamp.uid != MacOriginalNative.effectiveUid() || (stamp.mode and 0x1ff) != MacOriginalNative.DIRECTORY_MODE) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val descriptor = MacOriginalNative.openDirectory(path)
        try { MacOriginalNative.requireNoExtendedAcl(descriptor.fd) } finally { descriptor.closeConfirmed() }
        return path
    }
}
