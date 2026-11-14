package com.ukkera.pos.security.desktopmasterv2

import com.ukkera.pos.security.DesktopEntryKind
import com.ukkera.pos.security.DesktopFiles
import com.ukkera.pos.security.DesktopHandle
import com.ukkera.pos.security.DesktopKeyCustody
import com.ukkera.pos.security.DesktopOriginalOs
import com.ukkera.pos.security.DesktopStamp
import com.ukkera.pos.security.JvmP256Primitives
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeBootSessionV2
import com.ukkera.pos.security.SelfOwnedNativeInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedNativePlatformV2
import com.ukkera.pos.security.SelfOwnedNativeProfileV1
import com.ukkera.pos.security.SelfOwnedNativeSecurityV1
import com.ukkera.pos.security.SelfOwnedProcessPrincipal
import com.ukkera.pos.security.selfOwnedFail
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * jvmTest-ONLY Linux stand-ins for the macOS OS seam of the desktop Master platform: POSIX
 * (`unix:*` attributes, O_EXCL create, 0700/0600, rename, FileChannel locks) and software key
 * custody. They prove the shared desktop platform/owner wiring, never native custody or OS
 * protection. Self-authored, NOT an independent test double. Synthetic data only.
 */
internal class LinuxPosixTestFiles(override val baseDirectory: String) : DesktopFiles {
    override val platform: SelfOwnedNativePlatformV2 = SelfOwnedNativePlatformV2.MACOS
    override val ownerName: String = (Files.getAttribute(Path.of(baseDirectory), "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int).toString()

    override fun child(directory: String, name: String): String {
        if (name.isEmpty() || '/' in name || name == "." || name == "..") selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return Path.of(directory, name).toString()
    }

    override fun observe(path: String): DesktopStamp? = if (!Files.exists(Path.of(path), LinkOption.NOFOLLOW_LINKS)) null else stamp(Path.of(path))

    fun stamp(path: Path): DesktopStamp {
        val a = Files.readAttributes(path, "unix:*", LinkOption.NOFOLLOW_LINKS)
        val mode = a["mode"] as Int
        val kind = when (mode and 0xf000) { 0x4000 -> DesktopEntryKind.DIRECTORY; 0x8000 -> DesktopEntryKind.FILE; else -> DesktopEntryKind.OTHER }
        fun nanos(key: String) = (a[key] as java.nio.file.attribute.FileTime).to(java.util.concurrent.TimeUnit.NANOSECONDS)
        return DesktopStamp((a["dev"] as Long).toString(), (a["ino"] as Long).toString(), (a["uid"] as Int).toString(), kind,
            (mode and 0x3f) == 0, (a["nlink"] as Int).toLong(), a["size"] as Long, nanos("lastModifiedTime"), nanos("ctime"))
    }

    override fun openDirectory(path: String): DesktopHandle {
        if (observe(path)?.kind != DesktopEntryKind.DIRECTORY) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return Handle(Path.of(path), FileChannel.open(Path.of(path), StandardOpenOption.READ), directory = true)
    }

    override fun openFile(path: String, write: Boolean): DesktopHandle {
        if (observe(path)?.kind != DesktopEntryKind.FILE) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val options = if (write) arrayOf<java.nio.file.OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            else arrayOf<java.nio.file.OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        return Handle(Path.of(path), FileChannel.open(Path.of(path), *options), directory = false)
    }

    override fun createFile(path: String): DesktopHandle = try {
        Files.createFile(Path.of(path), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Handle(Path.of(path), FileChannel.open(Path.of(path), StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), false)
    } catch (_: FileAlreadyExistsException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }

    override fun createDirectory(path: String) {
        try { Files.createDirectory(Path.of(path), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
        catch (_: FileAlreadyExistsException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
    }

    override fun names(directory: String): List<String> = Files.list(Path.of(directory)).use { s -> s.map { it.fileName.toString() }.toList() }
    override fun remove(path: String) = Files.delete(Path.of(path))
    override fun rename(from: String, to: String) { Files.move(Path.of(from), Path.of(to), StandardCopyOption.ATOMIC_MOVE) }
    override fun volumeUuid(handle: DesktopHandle): String = VOLUME

    private inner class Handle(val path: Path, val channel: FileChannel, val directory: Boolean) : DesktopHandle {
        private var lock: FileLock? = null
        override fun stamp(): DesktopStamp = this@LinuxPosixTestFiles.stamp(path)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = channel.read(java.nio.ByteBuffer.wrap(buffer, offset, length))
        override fun preadAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int =
            channel.read(java.nio.ByteBuffer.wrap(buffer, offset, length), position)
        override fun pwriteAt(bytes: ByteArray, offset: Int, length: Int, position: Long): Int =
            channel.write(java.nio.ByteBuffer.wrap(bytes, offset, length), position)
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int = channel.write(java.nio.ByteBuffer.wrap(bytes, offset, length))
        override fun rewind() { channel.position(0) }
        override fun sync() { if (!directory) channel.force(true) }
        override fun tryLockExclusive(): Boolean {
            if (directory) return false
            lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            return lock != null
        }
        override fun close() { lock?.release(); channel.close() }
    }

    companion object { const val VOLUME: String = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0" }
}

/** Software JCA custody keyed by alias (test only; no non-exportability). */
internal class SoftwareTestCustody : DesktopKeyCustody {
    private val p256 = mutableMapOf<String, KeyPair>()
    private val aes = mutableMapOf<String, SecretKey>()
    private val hmacs = mutableMapOf<String, ByteArray>()
    private fun fresh(alias: String) { if (alias in p256 || alias in aes || alias in hmacs) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN) }
    private fun point(pair: KeyPair): ByteArray {
        val w = (pair.public as ECPublicKey).w
        fun fixed(v: java.math.BigInteger) = v.toByteArray().let { b -> ByteArray(32 - minOf(32, b.size)) + b.takeLast(32).toByteArray() }
        return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
    }
    override fun p256Point(alias: String): ByteArray? = p256[alias]?.let(::point)
    override fun createP256(alias: String): ByteArray {
        fresh(alias)
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        p256[alias] = pair; return point(pair)
    }
    override fun signP256(alias: String, message: ByteArray): ByteArray {
        val pair = p256[alias] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        val der = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }.sign()
        return JvmP256Primitives.canonicalize(JvmP256Primitives.fromDer(der))
    }
    override fun createAes(alias: String) { fresh(alias); aes[alias] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    override fun sealAes(alias: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = aes[alias] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        val nonce = JvmP256Primitives.randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(aad) }
        return nonce to c.doFinal(plaintext)
    }
    override fun openAes(alias: String, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = aes[alias] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(aad) }.doFinal(ciphertext)
    }
    override fun createHmac(alias: String) { fresh(alias); hmacs[alias] = JvmP256Primitives.randomBytes(32) }
    override fun hmac(alias: String, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hmacs[alias] ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN), "HmacSHA256")) }.doFinal(message)
    override fun symmetricCheck(alias: String): ByteArray? {
        val label = DesktopKeyCustody.CHECK_LABEL.toByteArray()
        aes[alias]?.let { key ->
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12))); updateAAD(label) }
            return MessageDigest.getInstance("SHA-256").digest(c.doFinal())
        }
        return hmacs[alias]?.let { hmac(alias, label) }
    }
}

internal class MemoryIdentityStore : JvmSecureIdentityStore {
    private val values = mutableMapOf<String, ByteArray>()
    override fun read(namespace: String): ByteArray? = synchronized(values) { values[namespace]?.copyOf() }
    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray =
        synchronized(values) { values.getOrPut(namespace) { value.copyOf() }.copyOf() }
    override fun delete(namespace: String): Boolean = synchronized(values) { values.remove(namespace) != null }
}

/** Linux stand-in for the macOS OS: fixed signed service identity, deterministic boot sessions. */
internal class LinuxTestOs(
    base: Path,
    private val target: SelfOwnedNativeInstalledTargetBindingV1,
) : DesktopOriginalOs {
    override val platform: SelfOwnedNativePlatformV2 = SelfOwnedNativePlatformV2.MACOS
    override val files: LinuxPosixTestFiles = LinuxPosixTestFiles(base.toString())
    override val custody: DesktopKeyCustody = SoftwareTestCustody()
    override val keySecurity: SelfOwnedNativeSecurityV1 = SelfOwnedNativeSecurityV1.SOFTWARE
    override val supportedProtections: Set<SelfOwnedKeyProtectionV1> = setOf(SelfOwnedKeyProtectionV1.OS_ISOLATED)
    private val identityStore = MemoryIdentityStore()
    private var boot = 0
    private val elapsed = AtomicLong(10_000L)
    var session: SelfOwnedNativeBootSessionV2 = sessionOf(0)
        private set
    fun nextBoot() { session = sessionOf(++boot) }
    private fun sessionOf(n: Int) = SelfOwnedNativeBootSessionV2.Mac("%08x-0000-4000-8000-%012x".format(0x7000 + n, n))
    override fun observeInstalledTarget(): SelfOwnedNativeInstalledTargetBindingV1 = target
    override fun observeBootSession(): SelfOwnedNativeBootSessionV2 = session
    override fun principal(): SelfOwnedProcessPrincipal = SelfOwnedProcessPrincipal.MacUid(files.ownerName.toInt())
    override fun elapsedMillis(): Long = elapsed.addAndGet(1)
    override fun elapsedRealtimeNanos(): Long = elapsed.get() * 1_000_000L
    override fun openSdkIdentityStore(profile: SelfOwnedNativeProfileV1): JvmSecureIdentityStore = identityStore
}
