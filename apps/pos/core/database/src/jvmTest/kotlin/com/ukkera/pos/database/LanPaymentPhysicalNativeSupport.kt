package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.security.desktopmasterv2.LinuxPosixTestFiles
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

/** Self-authored, NOT independent. Explicit payment-only native Linux SOFTWARE source for
 * the actual approved Client pairing target. Uses real persistent keys, POSIX inode/owner/
 * volume/boot observations and directory force/readback. No physical reply/force/currentness
 * result is supplied to application production. This is not Keychain/CNG/macOS qualification.
 * Bound to frozen LAN-A07/D04/D06 and the existing immutable payment supporting mapping. */
internal object LanPaymentPhysicalNativeSupport {
    fun platform(actual: DesktopClientTargetPlatform, roleRoot: Path): DesktopClientTargetPlatform {
        check(java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
        return object : DesktopClientTargetPlatform by actual {
            override fun physicalJournalNative(action: LocalPairedDeviceCredentialJournalAction): DesktopPairedDevicePhysicalJournalNative =
                Native(actual, roleRoot, action)
        }
    }

    private class Native(private val target: DesktopClientTargetPlatform, roleRoot: Path,
        action: LocalPairedDeviceCredentialJournalAction) : DesktopPairedDevicePhysicalJournalNative {
        override val parentDirectory: Path = target.parentDirectory().toAbsolutePath().normalize()
        private val targetDirectory = parentDirectory.resolve(SelfOwnedCompositionNames.devicePairingTargetDirectory(target.slot))
        private val origin = target.origin(targetDirectory)
        override val installedApplication: SelfOwnedInstalledIdentityTargetV2 get() = origin.installed
        override val kind = DesktopPairedDevicePhysicalStorageKind.POSIX_FULL_WRITE_FORCE_V1
        private val boot = target.bootSessionId()
        private val parentStamp = stamp(parentDirectory)
        private val volume = LanProcessNativeSupport.volume(parentDirectory)
        private val keys: LanPersistentSoftwareCustody
        private val files: DesktopFiles

        init {
            val root = roleRoot.toAbsolutePath().normalize()
            check(root.toRealPath(NOFOLLOW_LINKS) == root && parentDirectory.startsWith(root) &&
                parentDirectory.toRealPath(NOFOLLOW_LINKS) == parentDirectory)
            val keyDirectory = root.resolve("physical-software-keys")
            if (action != LocalPairedDeviceCredentialJournalAction.ENROLL)
                check(Files.isDirectory(keyDirectory, NOFOLLOW_LINKS) && keyDirectory.toRealPath(NOFOLLOW_LINKS) == keyDirectory)
            keys = LanPersistentSoftwareCustody(keyDirectory)
            val linux = LinuxPosixTestFiles(parentDirectory.toString())
            files = object : DesktopFiles by linux {
                override fun volumeUuid(handle: DesktopHandle): String = LanProcessNativeSupport.volume(parentDirectory)
                override fun openDirectory(path: String): DesktopHandle {
                    val retained = linux.openDirectory(path)
                    return object : DesktopHandle by retained {
                        override fun sync() { FileChannel.open(Path.of(path), READ, NOFOLLOW_LINKS).use { it.force(true) } }
                    }
                }
            }
            requireCurrent()
        }

        override fun requireCurrent() {
            check(target.bootSessionId() == boot && target.origin(targetDirectory) == origin &&
                target.parentDirectory().toAbsolutePath().normalize() == parentDirectory &&
                parentDirectory.toRealPath(NOFOLLOW_LINKS) == parentDirectory && stamp(parentDirectory) == parentStamp &&
                LanProcessNativeSupport.volume(parentDirectory) == volume)
        }
        override fun directoryFiles(): DesktopFiles = files
        override fun createKey(name: String) = keys.createAes(name)
        override fun keyNames(prefix: String): List<String> = keys.keyNames(prefix)
        override fun inspectKey(name: String): String {
            val check = checkNotNull(keys.symmetricCheck(name))
            try { return "TEST_ONLY_SOFTWARE:" + check.joinToString("") { "%02x".format(it.toInt() and 255) } }
            finally { check.fill(0) }
        }
        override fun encrypt(name: String, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> = keys.sealAes(name, aad, plain)
        override fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
            keys.openAes(alias = name, aad = aad, nonce = nonce, ciphertext = sealed)

        private fun stamp(path: Path): List<Any?> {
            val actual = Files.readAttributes(path, "unix:*", NOFOLLOW_LINKS)
            check((actual["mode"] as Int) and 0x3f == 0)
            return listOf(actual["dev"], actual["ino"], actual["uid"], actual["mode"])
        }
    }
}
