package com.ukkera.pos.security

import java.nio.file.Files
import java.nio.file.Path

/**
 * macOS (test-only) Master custody: the current uid's owner-only Application Support directory
 * on a local APFS/HFS volume, data-protection Keychain keys and the kernel boot session UUID.
 */
internal class MacDesktopOriginalOs private constructor(
    private val expected: SelfOwnedMacServiceIdentityV2,
    private val boot: MacOriginalBootSession,
    override val files: DesktopFiles,
) : DesktopOriginalOs {
    override val platform: SelfOwnedNativePlatformV2 = SelfOwnedNativePlatformV2.MACOS
    override val custody: DesktopKeyCustody = MacDesktopKeyCustody(MacKeychainSecureIdentityStore.open(), PROTECTION)
    override val keySecurity: SelfOwnedNativeSecurityV1 = SelfOwnedNativeSecurityV1.SOFTWARE
    override val supportedProtections: Set<SelfOwnedKeyProtectionV1> = setOf(PROTECTION)

    override fun observeInstalledTarget(): SelfOwnedNativeInstalledTargetBindingV1 =
        when (val actual = MacInstalledApplicationIdentity.captureService(expected)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> SelfOwnedNativeInstalledTargetBindingV1(actual.value)
            is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
    override fun observeBootSession(): SelfOwnedNativeBootSessionV2 { boot.elapsedMillis(); return boot.identity }
    override fun principal(): SelfOwnedProcessPrincipal = SelfOwnedProcessPrincipal.MacUid(MacOriginalNative.effectiveUid())
    override fun elapsedMillis(): Long = boot.elapsedMillis()
    override fun elapsedRealtimeNanos(): Long = boot.elapsedMillis() * 1_000_000L
    override fun openSdkIdentityStore(profile: SelfOwnedNativeProfileV1): dev.p2pkit.core.security.JvmSecureIdentityStore =
        MacKeychainSecureIdentityStore.open()
    override fun toString(): String = "MacDesktopOriginalOs([REDACTED])"

    companion object {
        private val PROTECTION = SelfOwnedKeyProtectionV1.OS_ISOLATED
        private const val BASE = "com.ukkera.pos.master"

        fun observe(expected: SelfOwnedMacServiceIdentityV2): MacDesktopOriginalOs {
            MacOriginalNative.requireSupported()
            val support = Path.of(System.getProperty("user.home"), "Library", "Application Support")
            val base = support.resolve(BASE)
            if (MacOriginalNative.lstat(base) == null) MacOriginalNative.mkdir(base)
            return MacDesktopOriginalOs(expected, MacOriginalBootSession.observe(), MacMasterFiles(base.toString()))
        }
    }
}

/** POSIX descriptor custody over [MacOriginalNative]; links are never followed. */
internal class MacMasterFiles(override val baseDirectory: String) : DesktopFiles {
    override val platform: SelfOwnedNativePlatformV2 = SelfOwnedNativePlatformV2.MACOS
    override val ownerName: String = MacOriginalNative.effectiveUid().toString()

    override fun child(directory: String, name: String): String {
        desktopKnown(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name)
        return Path.of(directory, name).toString()
    }

    override fun observe(path: String): DesktopStamp? = MacOriginalNative.lstat(Path.of(path))?.let { stamp(it, aclFree = true) }

    override fun openDirectory(path: String): DesktopHandle = Handle(path, MacOriginalNative.openDirectory(Path.of(path)))
    override fun openFile(path: String, write: Boolean): DesktopHandle = Handle(path,
        if (write) MacOriginalNative.openReadWriteFile(Path.of(path)) else MacOriginalNative.openReadFile(Path.of(path)))

    override fun createFile(path: String): DesktopHandle {
        val fd = MacOriginalNative.createExclusiveFile(Path.of(path)) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        MacOriginalNative.fchmod(fd.fd, MacOriginalNative.FILE_MODE)
        return Handle(path, fd)
    }

    override fun createDirectory(path: String) {
        desktopKnown(MacOriginalNative.mkdir(Path.of(path)))
    }

    override fun names(directory: String): List<String> = try {
        Files.list(Path.of(directory)).use { stream -> stream.map { it.fileName.toString() }.toList() }
    } catch (_: java.io.IOException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }

    override fun remove(path: String) {
        val stamp = MacOriginalNative.lstat(Path.of(path)) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (stamp.isDirectory) MacOriginalNative.rmdir(Path.of(path)) else MacOriginalNative.unlink(Path.of(path))
    }

    override fun rename(from: String, to: String) = MacOriginalNative.rename(Path.of(from), Path.of(to))

    override fun volumeUuid(handle: DesktopHandle): String =
        MacOriginalNative.requireLocalVolume(Path.of((handle as Handle).path)).uuid.lowercase()

    private fun stamp(it: MacOriginalStamp, aclFree: Boolean): DesktopStamp = DesktopStamp(
        volume = it.device.toString(), fileId = it.inode.toString(), owner = it.uid.toString(),
        kind = when { it.isDirectory -> DesktopEntryKind.DIRECTORY; it.isRegularFile -> DesktopEntryKind.FILE; else -> DesktopEntryKind.OTHER },
        ownerOnly = aclFree && (it.mode and 0x3f) == 0, links = it.links.toLong(), size = it.size,
        modified = it.modifiedSeconds * 1_000_000_000L + it.modifiedNanos,
        changed = it.changedSeconds * 1_000_000_000L + it.changedNanos,
    )

    private inner class Handle(val path: String, private val descriptor: MacOriginalFd) : DesktopHandle {
        override fun stamp(): DesktopStamp {
            val fd = descriptor.fd
            MacOriginalNative.requireNoExtendedAcl(fd)
            return stamp(MacOriginalNative.fstat(fd), aclFree = true)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            MacOriginalNative.transfer(descriptor.fd, buffer, offset, length, -1, write = false)
        override fun preadAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int {
            desktopKnown(position >= 0)
            return MacOriginalNative.transfer(descriptor.fd, buffer, offset, length, position, write = false)
        }
        override fun pwriteAt(bytes: ByteArray, offset: Int, length: Int, position: Long): Int {
            desktopKnown(position >= 0)
            return MacOriginalNative.transfer(descriptor.fd, bytes, offset, length, position, write = true)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
            MacOriginalNative.transfer(descriptor.fd, bytes, offset, length, -1, write = true)
        override fun rewind() = MacOriginalNative.rewind(descriptor.fd)
        override fun sync() = MacOriginalNative.fsync(descriptor.fd)
        override fun tryLockExclusive(): Boolean = MacOriginalNative.flockExclusive(descriptor.fd)
        override fun close() = MacOriginalCloseState.releaseLease(descriptor)
        override fun toString(): String = "MacMasterFiles.Handle([REDACTED])"
    }

    override fun toString(): String = "MacMasterFiles([REDACTED])"
}
