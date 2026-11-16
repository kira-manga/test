package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.security.desktopmasterv2.LinuxPosixTestFiles
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Executor plumbing only, NOT an independent test or production OS adapter. Linux POSIX files,
 * actual generated JCA keys and the official SDK identity persist across independent JVMs.
 * Desktop installed-application/platform facts are explicit SOFTWARE OS stand-ins. Exportable
 * test keys and a Linux run do not qualify Keychain/CNG/DPAPI, macOS, Windows or owner devices.
 * No credential, admission, signature verdict, permission or history receipt is synthesized.
 */
internal object LanProcessNativeSupport {
    val masterIdentity = SelfOwnedMacServiceIdentityV2(
        "com.ukkera.pos.lan.test.master", "ABCDE12345", "a".repeat(64))
    val clientIdentity = SelfOwnedMacUiIdentityV2(
        "com.ukkera.pos.lan.test.client", "ABCDE12345", "b".repeat(64))

    fun privateDirectory(path: Path): Path {
        try { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
        catch (_: FileAlreadyExistsException) { /* Existing-only verification follows. */ }
        val attrs = Files.readAttributes(path, "unix:mode,uid", NOFOLLOW_LINKS)
        check(((attrs["mode"] as Int) and 0xf000) == 0x4000 && ((attrs["mode"] as Int) and 0x3f) == 0)
        check(attrs["uid"] == Files.getAttribute(path.parent, "unix:uid", NOFOLLOW_LINKS))
        return path.toRealPath(NOFOLLOW_LINKS)
    }

    fun bootId(): String = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim().also {
        check(UUID.fromString(it).toString() == it)
    }

    fun elapsedNanos(): Long = System.nanoTime().also { check(it > 0) }
    fun volume(path: Path): String = UUID.nameUUIDFromBytes(
        ("linux-test-volume:" + Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS)).toByteArray()).toString()

    fun point(pair: KeyPair): ByteArray {
        val point = (pair.public as ECPublicKey).w
        fun fixed(value: java.math.BigInteger): ByteArray = ByteArray(32).also { output ->
            val bytes = value.toByteArray().takeLast(32).toByteArray()
            bytes.copyInto(output, 32 - bytes.size)
        }
        return byteArrayOf(4) + fixed(point.affineX) + fixed(point.affineY)
    }

    private val proofCurve = org.bouncycastle.crypto.params.ECDomainParameters(
        org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256r1"))
    private val proofRandom = java.security.SecureRandom()
    /** Same actual exportable SOFTWARE test key, fresh ECDSA nonce and message on every
     * call. Reuse public curve math, never a possession result or private-key parameter. */
    fun signature(pair: KeyPair, message: ByteArray): ByteArray {
        val private = pair.private as ECPrivateKey
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        val signer = org.bouncycastle.crypto.signers.ECDSASigner()
        signer.init(true, org.bouncycastle.crypto.params.ParametersWithRandom(
            org.bouncycastle.crypto.params.ECPrivateKeyParameters(private.s, proofCurve), proofRandom))
        return try {
            val values = signer.generateSignature(digest)
            val encoded = org.bouncycastle.asn1.DERSequence(arrayOf(
                org.bouncycastle.asn1.ASN1Integer(values[0]), org.bouncycastle.asn1.ASN1Integer(values[1]))).encoded
            try { JvmP256Primitives.canonicalize(JvmP256Primitives.fromDer(encoded)) }
            finally { encoded.fill(0) }
        } finally { digest.fill(0) }
    }
}

/** Immutable files with real process locking, force and exact readback. No partial-file repair. */
internal class LanPersistentTestFiles(root: Path) {
    val directory: Path = LanProcessNativeSupport.privateDirectory(root)
    private val lockPath = directory.resolve("ownership.lock")
    private val processGate = processGates.computeIfAbsent(directory) { Any() }

    init { createLock(); FileChannel.open(directory, READ).use { it.force(true) } }

    private fun createLock() {
        try { Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
        catch (_: FileAlreadyExistsException) { }
        verifyFile(lockPath, 0)
    }

    private fun verifyFile(path: Path, maximum: Int) {
        val attrs = Files.readAttributes(path, "unix:mode,uid,nlink,size", NOFOLLOW_LINKS)
        check(((attrs["mode"] as Int) and 0xf000) == 0x8000 && ((attrs["mode"] as Int) and 0x3f) == 0)
        check((attrs["nlink"] as Int) == 1 && (attrs["size"] as Long) in 0..maximum.toLong())
        check(attrs["uid"] == Files.getAttribute(directory, "unix:uid", NOFOLLOW_LINKS))
    }

    fun <T> locked(body: () -> T): T = synchronized(processGate) {
        // JVM FileChannel locks throw on same-process overlap rather than queueing. Serialize
        // local owners first, then take the genuine cross-process lock for this exact root.
        FileChannel.open(lockPath, READ, WRITE, NOFOLLOW_LINKS).use { file -> file.lock().use { body() } }
    }

    fun name(label: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray(Charsets.UTF_8))) + ".bin"

    fun read(name: String, maximum: Int): ByteArray? {
        require(name.matches(NATIVE_FILE_NAME))
        val path = directory.resolve(name)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        verifyFile(path, maximum)
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            check(channel.size() in 1..maximum.toLong())
            val bytes = ByteArray(channel.size().toInt())
            val target = ByteBuffer.wrap(bytes)
            while (target.hasRemaining()) check(channel.read(target) > 0)
            check(channel.read(ByteBuffer.allocate(1)) == -1)
            verifyFile(path, maximum)
            bytes
        }
    }

    fun create(name: String, value: ByteArray) {
        require(name.matches(NATIVE_FILE_NAME) && value.isNotEmpty())
        val path = directory.resolve(name)
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { channel ->
            val source = ByteBuffer.wrap(value)
            while (source.hasRemaining()) check(channel.write(source) > 0)
            channel.force(true)
        }
        FileChannel.open(directory, READ).use { it.force(true) }
        val actual = checkNotNull(read(name, value.size))
        try { check(actual.contentEquals(value)) } finally { actual.fill(0) }
    }

    fun delete(name: String): Boolean {
        val existing = read(name, 1_048_576) ?: return false
        existing.fill(0)
        Files.delete(directory.resolve(name))
        FileChannel.open(directory, READ).use { it.force(true) }
        check(!Files.exists(directory.resolve(name), NOFOLLOW_LINKS))
        return true
    }
    private companion object {
        val processGates = ConcurrentHashMap<Path, Any>()
        val NATIVE_FILE_NAME = Regex("[0-9a-f]{64}\\.bin")
    }
}

internal class LanPersistentIdentityStore(root: Path) : JvmSecureIdentityStore {
    private val files = LanPersistentTestFiles(root)
    override fun read(namespace: String): ByteArray? = files.locked { files.read(files.name(namespace), 1_048_576) }
    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray = files.locked {
        require(value.size in 1..1_048_576)
        val name = files.name(namespace)
        files.read(name, 1_048_576) ?: run { files.create(name, value); checkNotNull(files.read(name, 1_048_576)) }
    }
    override fun delete(namespace: String): Boolean = files.locked { files.delete(files.name(namespace)) }
}

/** Test-only exportable persistent JCA keys. Never passed as a native OS qualification result. */
internal class LanPersistentSoftwareCustody(root: Path) : DesktopKeyCustody {
    private val files = LanPersistentTestFiles(root)
    private data class KeyValue(val alias: String, val kind: Int, val first: ByteArray, val second: ByteArray)

    private fun decode(bytes: ByteArray): KeyValue = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        check(input.readInt() == 0x4c414e32)
        val alias = input.readUTF(); check(alias.length in 1..4096)
        val kind = input.readUnsignedByte(); check(kind in 1..3)
        fun part(): ByteArray { val n = input.readInt(); check(n in 0..4096); return ByteArray(n).also { input.readFully(it) } }
        val result = KeyValue(alias, kind, part(), part()); check(input.read() == -1); result
    }

    private fun read(alias: String): KeyValue? = files.read(files.name(alias), 16_384)?.let { bytes ->
        try { decode(bytes).also { check(it.alias == alias) } } finally { bytes.fill(0) }
    }
    private fun create(alias: String, kind: Int, first: ByteArray, second: ByteArray = byteArrayOf()) = files.locked {
        check(read(alias) == null)
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use {
            it.writeInt(0x4c414e32); it.writeUTF(alias); it.writeByte(kind)
            it.writeInt(first.size); it.write(first); it.writeInt(second.size); it.write(second)
        } }.toByteArray()
        try { files.create(files.name(alias), bytes) } finally { bytes.fill(0) }
    }

    fun keyNames(prefix: String): List<String> = files.locked {
        Files.list(files.directory).use { paths -> paths.filter { it.fileName.toString() != "ownership.lock" }.map { path ->
            val name = path.fileName.toString()
            val bytes = checkNotNull(files.read(name, 16_384))
            val value = try { decode(bytes) } finally { bytes.fill(0) }
            try { check(files.name(value.alias) == name); value.alias }
            finally { value.first.fill(0); value.second.fill(0) }
        }.filter { it.startsWith(prefix) }.sorted().toList() }
    }
    fun keyExists(alias: String): Boolean = files.locked { read(alias)?.let {
        it.first.fill(0); it.second.fill(0); true
    } ?: false }
    fun deleteKey(alias: String) { files.locked { check(files.delete(files.name(alias))) } }

    fun pair(alias: String): KeyPair? = files.locked {
        val stored = read(alias) ?: return@locked null
        try {
            check(stored.kind == 1)
            KeyFactory.getInstance("EC").let { factory -> KeyPair(
                factory.generatePublic(X509EncodedKeySpec(stored.second)),
                factory.generatePrivate(PKCS8EncodedKeySpec(stored.first))) }
        } finally { stored.first.fill(0); stored.second.fill(0) }
    }

    override fun p256Point(alias: String): ByteArray? = pair(alias)?.let(LanProcessNativeSupport::point)
    override fun createP256(alias: String): ByteArray {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val privateBytes = pair.private.encoded; val publicBytes = pair.public.encoded
        try { create(alias, 1, privateBytes, publicBytes) }
        finally { privateBytes.fill(0); publicBytes.fill(0) }
        return LanProcessNativeSupport.point(checkNotNull(this.pair(alias)))
    }
    override fun signP256(alias: String, message: ByteArray): ByteArray =
        LanProcessNativeSupport.signature(checkNotNull(pair(alias)), message)
    private fun secret(alias: String, kind: Int): ByteArray = files.locked {
        val stored = checkNotNull(read(alias))
        try {
            check(stored.kind == kind && stored.first.size == 32 && stored.second.isEmpty())
            stored.first.copyOf()
        } finally { stored.first.fill(0); stored.second.fill(0) }
    }
    override fun createAes(alias: String) {
        val bytes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded
        try { create(alias, 2, bytes) } finally { bytes.fill(0) }
    }
    override fun sealAes(alias: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val bytes = secret(alias, 2); val nonce = JvmP256Primitives.randomBytes(12)
        try { return nonce to Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
        }.doFinal(plaintext) } finally { bytes.fill(0) }
    }
    override fun openAes(alias: String, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val bytes = secret(alias, 2)
        try { return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
        }.doFinal(ciphertext) } finally { bytes.fill(0) }
    }
    override fun createHmac(alias: String) {
        val bytes = JvmP256Primitives.randomBytes(32)
        try { create(alias, 3, bytes) } finally { bytes.fill(0) }
    }
    override fun hmac(alias: String, message: ByteArray): ByteArray {
        val bytes = secret(alias, 3)
        try { return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(bytes, "HmacSHA256")) }.doFinal(message) }
        finally { bytes.fill(0) }
    }
    override fun symmetricCheck(alias: String): ByteArray? {
        val kind = files.locked { read(alias)?.let { value ->
            try { value.kind } finally { value.first.fill(0); value.second.fill(0) }
        } } ?: return null
        val label = DesktopKeyCustody.CHECK_LABEL.toByteArray()
        return when (kind) {
            2 -> {
                val bytes = secret(alias, 2)
                try { MessageDigest.getInstance("SHA-256").digest(Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(bytes, "AES"), GCMParameterSpec(128, ByteArray(12))); updateAAD(label)
                }.doFinal()) } finally { bytes.fill(0) }
            }
            3 -> hmac(alias, label)
            else -> error("Not a symmetric key")
        }
    }
}

internal class LanLinuxOriginalOs(root: Path) : DesktopOriginalOs {
    private val data = LanProcessNativeSupport.privateDirectory(root.resolve("native-data"))
    private val linuxFiles = LinuxPosixTestFiles(data.toString())
    override val files: DesktopFiles = object : DesktopFiles by linuxFiles {
        override fun volumeUuid(handle: DesktopHandle): String = LanProcessNativeSupport.volume(data)
        override fun openDirectory(path: String): DesktopHandle {
            val retained = linuxFiles.openDirectory(path)
            return object : DesktopHandle by retained {
                override fun sync() { FileChannel.open(Path.of(path), READ, NOFOLLOW_LINKS).use { it.force(true) } }
            }
        }
    }
    override val platform = SelfOwnedNativePlatformV2.MACOS
    override val custody = LanPersistentSoftwareCustody(root.resolve("software-keys"))
    override val keySecurity = SelfOwnedNativeSecurityV1.SOFTWARE
    override val supportedProtections = setOf(SelfOwnedKeyProtectionV1.OS_ISOLATED)
    private val identities = LanPersistentIdentityStore(root.resolve("sdk-identities"))
    override fun observeInstalledTarget(): SelfOwnedNativeInstalledTargetBindingV1 = when (val binding =
        SelfOwnedInstalledTargetBindingV1.accept(SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE, LanProcessNativeSupport.masterIdentity)) {
        is SelfOwnedInstalledIdentityResultV2.Data -> binding.value as? SelfOwnedNativeInstalledTargetBindingV1
            ?: error("Native installed identity required for this test OS")
        is SelfOwnedInstalledIdentityResultV2.Refused -> error("Invalid test installed identity")
    }
    override fun observeBootSession(): SelfOwnedNativeBootSessionV2 = SelfOwnedNativeBootSessionV2.Mac(LanProcessNativeSupport.bootId())
    override fun principal(): SelfOwnedProcessPrincipal = SelfOwnedProcessPrincipal.MacUid(files.ownerName.toInt())
    override fun elapsedMillis(): Long = elapsedRealtimeNanos() / 1_000_000
    override fun elapsedRealtimeNanos(): Long = LanProcessNativeSupport.elapsedNanos()
    override fun info(message: String) {
        if (java.lang.Boolean.getBoolean("ukkera.lan.profile") &&
            message.startsWith("Native data observation: stage=")) println(message)
    }
    override fun warn(message: String) {
        // These production diagnostic constructors contain only exception types,
        // fixed phase/reason names, timings and bounded static source locations.
        if (message.startsWith("Native process exclusion requested: reason=") ||
            message.startsWith("Native action refused: ")) System.err.println(message)
    }
    override fun openSdkIdentityStore(profile: SelfOwnedNativeProfileV1): JvmSecureIdentityStore = identities
}

internal class LanLinuxClientTargetPlatform(root: Path,
    override val slot: LocalTrainingCompositionSlot? = null) : DesktopClientTargetPlatform {
    private val data = LanProcessNativeSupport.privateDirectory(root.resolve("native-data"))
    private val custody = LanPersistentSoftwareCustody(root.resolve("software-keys"))
    private val identities = LanPersistentIdentityStore(root.resolve("sdk-identities"))
    override fun parentDirectory(): Path = data
    override fun origin(directory: Path): TargetOrigin.Desktop {
        val target = directory.toRealPath(NOFOLLOW_LINKS)
        val lock = target.resolve(DesktopTargetSnapshotStore.LOCK)
        return TargetOrigin.Desktop(LanProcessNativeSupport.clientIdentity, TargetDesktopNamespaceV2(
            SelfOwnedNativePlatformV2.MACOS, target.toString(), Files.getAttribute(target, "unix:uid", NOFOLLOW_LINKS).toString(),
            LanProcessNativeSupport.volume(target), Files.getAttribute(target, "unix:ino", NOFOLLOW_LINKS).toString(),
            Files.getAttribute(lock, "unix:ino", NOFOLLOW_LINKS).toString()))
    }
    override fun bootSessionId(): String = LanProcessNativeSupport.bootId()
    override fun elapsedMillis(): Long = LanProcessNativeSupport.elapsedNanos() / 1_000_000
    private fun alias(preparationId: DomainId, keyId: DomainId): String = "target.${preparationId.value}.${keyId.value}"
    override fun keyExists(preparationId: DomainId, keyId: DomainId): Boolean = custody.p256Point(alias(preparationId, keyId)) != null
    override fun createKey(preparationId: DomainId, keyId: DomainId): DesktopClientTargetKey {
        val name = alias(preparationId, keyId); custody.createP256(name)
        return key(name)
    }
    override fun openKey(preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray): DesktopClientTargetKey =
        key(alias(preparationId, keyId)).also { check(it.publicPoint().contentEquals(originalPoint)) }
    private fun key(alias: String): DesktopClientTargetKey = object : DesktopClientTargetKey {
        private var closed = false
        override val security = SelfOwnedNativeSecurityV1.SOFTWARE
        override fun publicPoint(): ByteArray { check(!closed); return checkNotNull(custody.p256Point(alias)) }
        override fun sign(message: ByteArray): ByteArray { check(!closed); return custody.signP256(alias, message) }
        override fun close() { check(!closed); closed = true }
    }
    override fun newSdkOwner(installed: SelfOwnedInstalledIdentityTargetV2): DevicePairingTargetSdkOwner =
        desktopSdkOwner(installed) { identities }
    fun identityStore(): JvmSecureIdentityStore = identities
}
