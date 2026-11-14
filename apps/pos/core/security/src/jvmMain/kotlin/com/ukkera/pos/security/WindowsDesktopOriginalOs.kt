package com.ukkera.pos.security

/**
 * Windows Master service custody: the packaged service's SCM identity (service SID + service
 * account), a ProgramData directory owned by the service account with a protected DACL granting
 * only the service SID, CNG P-256 keys ACL'd to the service and DPAPI-protected symmetric secrets.
 */
internal class WindowsDesktopOriginalOs private constructor(
    private val expected: SelfOwnedInstalledIdentityTargetV2,
    private val boot: WindowsOriginalBootSession,
    private val serviceSid: String,
    private val accountSid: String,
    override val files: DesktopFiles,
) : DesktopOriginalOs {
    override val platform: SelfOwnedNativePlatformV2 = SelfOwnedNativePlatformV2.WINDOWS
    private val store = WindowsDpapiSecureIdentityStore.open()
    override val custody: DesktopKeyCustody = WindowsDesktopKeyCustody(store, expected, PROTECTION)
    override val keySecurity: SelfOwnedNativeSecurityV1 = SelfOwnedNativeSecurityV1.SOFTWARE
    override val supportedProtections: Set<SelfOwnedKeyProtectionV1> = setOf(PROTECTION)

    override fun observeInstalledTarget(): SelfOwnedNativeInstalledTargetBindingV1 {
        WindowsSelfOwnedSigningKey.requireService(expected, serviceSid, accountSid)
        return SelfOwnedNativeInstalledTargetBindingV1(expected)
    }
    override fun observeBootSession(): SelfOwnedNativeBootSessionV2 { boot.elapsedMillis(); return boot.identity }
    override fun principal(): SelfOwnedProcessPrincipal {
        WindowsSelfOwnedSigningKey.requireService(expected, serviceSid, accountSid)
        return SelfOwnedProcessPrincipal.WindowsSid(accountSid)
    }
    override fun elapsedMillis(): Long = boot.elapsedMillis()
    override fun elapsedRealtimeNanos(): Long = boot.elapsedMillis() * 1_000_000L
    override fun openSdkIdentityStore(profile: SelfOwnedNativeProfileV1): dev.p2pkit.core.security.JvmSecureIdentityStore = store
    override fun toString(): String = "WindowsDesktopOriginalOs([REDACTED])"

    companion object {
        private val PROTECTION = SelfOwnedKeyProtectionV1.OS_ISOLATED
        private const val BASE = "UkkeraPOSMaster"

        fun observe(expected: SelfOwnedInstalledIdentityTargetV2): WindowsDesktopOriginalOs {
            WindowsOriginalNative.requireSupported()
            WindowsOriginalNative.requireNoImpersonation()
            val (serviceSid, accountSid) = runCatching { WindowsSelfOwnedSigningKey.serviceCoordinates(expected) }
                .getOrElse { selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM) }
            WindowsSelfOwnedSigningKey.requireService(expected, serviceSid, accountSid)
            val programData = System.getenv("ProgramData")?.takeIf { it.length >= 3 && it[1] == ':' }
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            val base = programData.trimEnd('\\') + "\\" + BASE
            val files = WindowsMasterFiles(base, serviceSid, accountSid)
            if (files.observe(base) == null) WindowsOriginalNative.desktopCreateDirectory(base, accountSid, serviceSid)
            return WindowsDesktopOriginalOs(expected, WindowsOriginalBootSession.observe(), serviceSid, accountSid, files)
        }
    }
}

/** NTFS handle custody over [WindowsOriginalNative]; reparse points are never followed. */
internal class WindowsMasterFiles(
    override val baseDirectory: String,
    private val serviceSid: String,
    private val accountSid: String,
) : DesktopFiles {
    override val platform: SelfOwnedNativePlatformV2 = SelfOwnedNativePlatformV2.WINDOWS
    override val ownerName: String = accountSid

    override fun child(directory: String, name: String): String {
        desktopKnown(name.isNotEmpty() && name != "." && name != ".." && name.none { it in "\\/:*?\"<>|" || it.code < 0x20 })
        return directory.trimEnd('\\') + "\\" + name
    }

    override fun observe(path: String): DesktopStamp? {
        val handle = WindowsOriginalNative.desktopOpen(path, write = false, directory = true) ?: return null
        try { return WindowsOriginalNative.desktopStamp(handle.pointer, accountSid, serviceSid) } finally { handle.closeConfirmed() }
    }

    override fun openDirectory(path: String): DesktopHandle =
        Handle(path, WindowsOriginalNative.desktopOpen(path, write = false, directory = true)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN), directory = true)
    override fun openFile(path: String, write: Boolean): DesktopHandle =
        Handle(path, WindowsOriginalNative.desktopOpen(path, write, directory = false)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN), directory = false)
    override fun createFile(path: String): DesktopHandle =
        Handle(path, WindowsOriginalNative.desktopCreateFile(path, accountSid, serviceSid), directory = false)
    override fun createDirectory(path: String) = WindowsOriginalNative.desktopCreateDirectory(path, accountSid, serviceSid)
    override fun names(directory: String): List<String> = WindowsOriginalNative.directoryNames(directory)
    override fun remove(path: String) {
        val stamp = observe(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        WindowsOriginalNative.desktopRemove(path, stamp.kind == DesktopEntryKind.DIRECTORY)
    }
    override fun rename(from: String, to: String) = WindowsOriginalNative.desktopRename(from, to)
    override fun volumeUuid(handle: DesktopHandle): String =
        (handle as Handle).let { WindowsOriginalNative.desktopVolumeUuid(it.native.pointer, it.path) }

    private inner class Handle(val path: String, val native: WindowsOriginalHandle, private val directory: Boolean) : DesktopHandle {
        private var cursor = 0L
        override fun stamp(): DesktopStamp = WindowsOriginalNative.desktopStamp(native.pointer, accountSid, serviceSid)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            WindowsOriginalNative.desktopTransfer(native.pointer, buffer, offset, length, cursor, write = false).also { cursor += it }
        override fun preadAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int =
            WindowsOriginalNative.desktopTransfer(native.pointer, buffer, offset, length, position, write = false)
        override fun pwriteAt(bytes: ByteArray, offset: Int, length: Int, position: Long): Int =
            WindowsOriginalNative.desktopTransfer(native.pointer, bytes, offset, length, position, write = true)
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
            WindowsOriginalNative.desktopTransfer(native.pointer, bytes, offset, length, cursor, write = true).also { cursor += it }
        override fun rewind() { cursor = 0 }
        // NTFS directory metadata is journaled; renames use MOVEFILE_WRITE_THROUGH instead of a directory flush.
        override fun sync() { if (!directory) WindowsOriginalNative.desktopFlush(native.pointer) }
        override fun tryLockExclusive(): Boolean = WindowsOriginalNative.desktopTryLock(native.pointer)
        override fun close() = native.closeConfirmed()
        override fun toString(): String = "WindowsMasterFiles.Handle([REDACTED])"
    }

    override fun toString(): String = "WindowsMasterFiles([REDACTED])"
}
