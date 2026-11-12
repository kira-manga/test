package com.ukkera.pos.security

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * The user's login (file-based) Keychain for the macOS desktop Client. The data-protection Keychain
 * requires a provisioning-profile entitlement that test builds do not carry (observed -34018), so
 * Client keys use this OS-protected, login-encrypted store whose item ACL binds the signed app.
 * Keys are non-extractable software keys (no Secure Enclave without that entitlement).
 */
internal object MacLoginKeychain {
    private interface Api : Library {
        fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
        fun SecItemAdd(attributes: Pointer, result: PointerByReference?): Int
        fun SecItemDelete(query: Pointer): Int
        fun SecKeyCreateRandomKey(parameters: Pointer, error: PointerByReference?): Pointer?
        fun SecKeyCopyPublicKey(key: Pointer): Pointer?
        fun SecKeyCopyExternalRepresentation(key: Pointer, error: PointerByReference?): Pointer?
        fun SecKeyCreateSignature(key: Pointer, algorithm: Pointer, data: Pointer, error: PointerByReference?): Pointer?
    }
    private interface Cf : Library {
        fun CFNumberCreate(allocator: Pointer?, type: NativeLong, value: Pointer): Pointer?
        fun CFArrayGetCount(array: Pointer): NativeLong
        fun CFArrayGetValueAtIndex(array: Pointer, index: NativeLong): Pointer?
        fun CFArrayGetTypeID(): NativeLong
    }
    private val api: Api by lazy { Native.load("/System/Library/Frameworks/Security.framework/Security", Api::class.java) }
    private val cfx: Cf by lazy { Native.load(MacSecurityNative.core.name, Cf::class.java) }
    private val cf get() = MacSecurityNative.cf
    private const val SUCCESS = 0
    private const val NOT_FOUND = -25300
    private const val DUPLICATE = -25299

    fun requireSupported() { check(Platform.isMac() && Native.POINTER_SIZE == 8) { "64-bit macOS is required" } }

    private class Dict : AutoCloseable {
        val ref: Pointer = checkNotNull(MacSecurityNative.cf.CFDictionaryCreateMutable(null, NativeLong(0),
            MacSecurityNative.core.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            MacSecurityNative.core.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")))
        private val owned = mutableListOf<Pointer>()
        fun put(key: String, value: Pointer) = MacSecurityNative.cf.CFDictionarySetValue(ref, MacSecurityNative.constant(key), value)
        fun constant(key: String, value: String, core: Boolean = false) = put(key, MacSecurityNative.constant(value, core))
        fun text(key: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val string = checkNotNull(MacSecurityNative.cf.CFStringCreateWithBytes(null, bytes, NativeLong(bytes.size.toLong()), 0x08000100, 0))
            owned += string; put(key, string)
        }
        fun data(key: String, value: ByteArray) { val data = cfData(value); owned += data; put(key, data) }
        fun number(key: String, value: Int) {
            Memory(4).use { memory ->
                memory.setInt(0, value)
                val number = checkNotNull((MacLoginKeychain.cfx).CFNumberCreate(null, NativeLong(3), memory)) // kCFNumberSInt32Type
                owned += number; put(key, number)
            }
        }
        fun dict(key: String, value: Dict) = put(key, value.ref)
        override fun close() { owned.forEach { MacSecurityNative.cf.CFRelease(it) }; MacSecurityNative.cf.CFRelease(ref) }
    }

    private fun cfData(value: ByteArray): Pointer = Memory(maxOf(1, value.size).toLong()).use { memory ->
        if (value.isNotEmpty()) memory.write(0, value, 0, value.size)
        try { checkNotNull(cf.CFDataCreate(null, memory, NativeLong(value.size.toLong()))) } finally { memory.clear() }
    }

    private fun bytes(data: Pointer): ByteArray {
        check(cf.CFGetTypeID(data) == cf.CFDataGetTypeID())
        val count = cf.CFDataGetLength(data).toLong()
        check(count in 0..1_048_576)
        return if (count == 0L) ByteArray(0) else checkNotNull(cf.CFDataGetBytePtr(data)).getByteArray(0, count.toInt())
    }

    private fun string(value: Pointer): String {
        check(cf.CFGetTypeID(value) == cf.CFStringGetTypeID())
        val buffer = ByteArray(1024)
        check(cf.CFStringGetCString(value, buffer, NativeLong(buffer.size.toLong()), 0x08000100) != 0.toByte())
        return String(buffer, 0, buffer.indexOf(0).let { if (it < 0) buffer.size else it }, Charsets.UTF_8)
    }

    /** Common login-Keychain item attributes: no sync, no authentication UI, file-based keychain. */
    private fun base(dict: Dict) {
        dict.constant("kSecUseDataProtectionKeychain", "kCFBooleanFalse", core = true)
        dict.constant("kSecUseAuthenticationUI", "kSecUseAuthenticationUIFail")
    }

    private fun password(service: String, account: String?, body: (Dict) -> Unit): Dict = Dict().also { dict ->
        dict.constant("kSecClass", "kSecClassGenericPassword")
        dict.text("kSecAttrService", service)
        account?.let { dict.text("kSecAttrAccount", it) }
        base(dict); body(dict)
    }

    /** Atomic create; returns false when the exact item already exists (never overwritten). */
    fun passwordAdd(service: String, account: String, value: ByteArray): Boolean {
        requireSupported()
        val status = password(service, account) { it.data("kSecValueData", value) }.use { api.SecItemAdd(it.ref, null) }
        return when (status) { SUCCESS -> true; DUPLICATE -> false; else -> error("Keychain item creation failed ($status)") }
    }

    fun passwordRead(service: String, account: String): ByteArray? {
        requireSupported()
        val result = PointerByReference()
        val status = password(service, account) {
            it.constant("kSecReturnData", "kCFBooleanTrue", core = true); it.constant("kSecMatchLimit", "kSecMatchLimitOne")
        }.use { api.SecItemCopyMatching(it.ref, result) }
        val value = result.value
        try {
            return when (status) { NOT_FOUND -> null; SUCCESS -> bytes(checkNotNull(value)); else -> error("Keychain item read failed ($status)") }
        } finally { value?.let(cf::CFRelease) }
    }

    fun passwordDelete(service: String, account: String): Boolean {
        requireSupported()
        return when (val status = password(service, account) { }.use { api.SecItemDelete(it.ref) }) {
            SUCCESS -> true; NOT_FOUND -> false; else -> error("Keychain item deletion failed ($status)")
        }
    }

    fun passwordAccounts(service: String): List<String> {
        requireSupported()
        val result = PointerByReference()
        val status = password(service, null) {
            it.constant("kSecReturnAttributes", "kCFBooleanTrue", core = true); it.constant("kSecMatchLimit", "kSecMatchLimitAll")
        }.use { api.SecItemCopyMatching(it.ref, result) }
        if (status == NOT_FOUND) return emptyList()
        check(status == SUCCESS) { "Keychain item enumeration failed ($status)" }
        val array = checkNotNull(result.value)
        try {
            check(cf.CFGetTypeID(array) == cfx.CFArrayGetTypeID())
            val count = cfx.CFArrayGetCount(array).toLong()
            check(count in 0..65_536)
            return (0 until count).map { index ->
                val item = checkNotNull(cfx.CFArrayGetValueAtIndex(array, NativeLong(index)))
                string(checkNotNull(cf.CFDictionaryGetValue(item, MacSecurityNative.constant("kSecAttrAccount"))))
            }
        } finally { cf.CFRelease(array) }
    }

    /** New permanent non-extractable P-256 private key with an exact application tag. Caller releases. */
    fun keyCreate(tag: ByteArray): Pointer {
        requireSupported()
        check(keyFind(tag)?.also(cf::CFRelease) == null) { "Client target key already exists" }
        Dict().use { privateAttributes ->
            privateAttributes.constant("kSecAttrIsPermanent", "kCFBooleanTrue", core = true)
            privateAttributes.data("kSecAttrApplicationTag", tag)
            privateAttributes.constant("kSecAttrIsExtractable", "kCFBooleanFalse", core = true)
            Dict().use { parameters ->
                parameters.constant("kSecAttrKeyType", "kSecAttrKeyTypeECSECPrimeRandom")
                parameters.number("kSecAttrKeySizeInBits", 256)
                base(parameters)
                parameters.dict("kSecPrivateKeyAttrs", privateAttributes)
                return checkNotNull(api.SecKeyCreateRandomKey(parameters.ref, null)) { "Client target key generation failed" }
            }
        }
    }

    /** The exact tagged private key, or null when absent. Caller releases. */
    fun keyFind(tag: ByteArray): Pointer? {
        requireSupported()
        val result = PointerByReference()
        val status = Dict().use { query ->
            query.constant("kSecClass", "kSecClassKey")
            query.constant("kSecAttrKeyClass", "kSecAttrKeyClassPrivate")
            query.data("kSecAttrApplicationTag", tag)
            query.constant("kSecReturnRef", "kCFBooleanTrue", core = true)
            query.constant("kSecMatchLimit", "kSecMatchLimitOne")
            base(query)
            api.SecItemCopyMatching(query.ref, result)
        }
        return when (status) { NOT_FOUND -> null; SUCCESS -> checkNotNull(result.value); else -> error("Client key lookup failed ($status)") }
    }

    /** 65-byte uncompressed public point; the private key must not be exportable. */
    fun publicPoint(privateKey: Pointer): ByteArray {
        api.SecKeyCopyExternalRepresentation(privateKey, null)?.let { exported ->
            cf.CFRelease(exported); error("Client target key is extractable")
        }
        val publicKey = checkNotNull(api.SecKeyCopyPublicKey(privateKey))
        try {
            val data = checkNotNull(api.SecKeyCopyExternalRepresentation(publicKey, null))
            try { return bytes(data).also { check(it.size == 65 && it[0] == 4.toByte()); JvmP256Primitives.publicKey(it) } }
            finally { cf.CFRelease(data) }
        } finally { cf.CFRelease(publicKey) }
    }

    /** Canonical raw 64-byte ECDSA P-256/SHA-256 signature. */
    fun sign(privateKey: Pointer, message: ByteArray): ByteArray {
        val data = cfData(message)
        try {
            val signature = checkNotNull(api.SecKeyCreateSignature(privateKey,
                MacSecurityNative.constant("kSecKeyAlgorithmECDSASignatureMessageX962SHA256"), data, null)) { "Client target signing failed" }
            try { return JvmP256Primitives.canonicalize(JvmP256Primitives.fromDer(bytes(signature))) } finally { cf.CFRelease(signature) }
        } finally { cf.CFRelease(data) }
    }

    fun release(value: Pointer) = cf.CFRelease(value)
}

/** macOS in-app Client target key (login Keychain, non-extractable P-256). */
internal class MacClientTargetKey private constructor(private var key: Pointer?, private val point: ByteArray) : DesktopClientTargetKey {
    override val security: SelfOwnedNativeSecurityV1 get() = SelfOwnedNativeSecurityV1.SOFTWARE
    @Synchronized override fun publicPoint(): ByteArray {
        val actual = MacLoginKeychain.publicPoint(checkNotNull(key))
        try { check(MessageDigest.isEqual(actual, point)) { "Client target key changed" } } finally { actual.fill(0) }
        return point.copyOf()
    }
    @Synchronized override fun sign(message: ByteArray): ByteArray {
        publicPoint().fill(0)
        val signature = MacLoginKeychain.sign(checkNotNull(key), message)
        check(JvmP256Primitives.verify(point, message, signature)) { "Client target key possession readback failed" }
        return signature
    }
    @Synchronized override fun close() { key?.let(MacLoginKeychain::release); key = null; point.fill(0) }

    companion object {
        fun tag(slot: LocalTrainingCompositionSlot?, preparationId: DomainId, keyId: DomainId, identityDigest: String): ByteArray =
            "ukkera.pos.client-target.v2:${slot?.name?.lowercase() ?: "live"}:${preparationId.value}:${keyId.value}:$identityDigest"
                .toByteArray(Charsets.UTF_8)
        fun create(tag: ByteArray): MacClientTargetKey {
            val key = MacLoginKeychain.keyCreate(tag)
            return try { MacClientTargetKey(key, MacLoginKeychain.publicPoint(key)) } catch (failure: Throwable) { MacLoginKeychain.release(key); throw failure }
        }
        fun openExisting(tag: ByteArray, originalPoint: ByteArray): MacClientTargetKey {
            val key = checkNotNull(MacLoginKeychain.keyFind(tag)) { "Retained client target key is missing" }
            try {
                val point = MacLoginKeychain.publicPoint(key)
                check(MessageDigest.isEqual(point, originalPoint)) { "Not the retained original key" }
                return MacClientTargetKey(key, point)
            } catch (failure: Throwable) { MacLoginKeychain.release(key); throw failure }
        }
    }
}

/** Login-Keychain official SDK identity store for the macOS desktop Client (same contract as the data-protection store). */
public class MacLoginKeychainSecureIdentityStore private constructor() : JvmSecureIdentityStore {
    private fun account(namespace: String): String {
        require(namespace.isNotEmpty())
        return MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    override fun read(namespace: String): ByteArray? = MacLoginKeychain.passwordRead(SERVICE, account(namespace))
    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray {
        val created = MacLoginKeychain.passwordAdd(SERVICE, account(namespace), value)
        val winner = checkNotNull(read(namespace)) { "Keychain identity disappeared during creation" }
        if (created && !MessageDigest.isEqual(value, winner)) { winner.fill(0); error("Keychain identity creation readback differs") }
        return winner
    }
    override fun delete(namespace: String): Boolean = MacLoginKeychain.passwordDelete(SERVICE, account(namespace))
    public companion object {
        private const val SERVICE = "com.ukkera.pos.sdk.identity.login.v1"
        public fun open(): MacLoginKeychainSecureIdentityStore { MacLoginKeychain.requireSupported(); return MacLoginKeychainSecureIdentityStore() }
    }
}

/**
 * macOS client journal keys in the login Keychain: data keys are 32 random bytes (AES-256-GCM in the
 * process, never written to files); anti-rollback heads are marker items created/deleted by exact name.
 */
public class MacClientJournalKeyStore private constructor() {
    public fun keyNames(prefix: String): List<String> = MacLoginKeychain.passwordAccounts(SERVICE).filter { it.startsWith(prefix) }
    public fun keyExists(name: String): Boolean = MacLoginKeychain.passwordRead(SERVICE, name)?.also { it.fill(0) } != null
    public fun createKey(name: String) {
        val material = JvmP256Primitives.randomBytes(32)
        try { check(MacLoginKeychain.passwordAdd(SERVICE, name, material)) { "Journal key already exists" } } finally { material.fill(0) }
    }
    public fun deleteKey(name: String) { check(MacLoginKeychain.passwordDelete(SERVICE, name)) { "Journal key is absent" } }
    public fun inspectKey(name: String): String {
        val material = checkNotNull(MacLoginKeychain.passwordRead(SERVICE, name)) { "Journal key is absent" }
        try { check(material.size == 32) { "Journal key profile differs" } } finally { material.fill(0) }
        return POLICY
    }
    public fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = JvmP256Primitives.randomBytes(12)
        return nonce to cipher(name, javax.crypto.Cipher.ENCRYPT_MODE, nonce, aad, plaintext)
    }
    public fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        cipher(name, javax.crypto.Cipher.DECRYPT_MODE, nonce, aad, ciphertext)
    private fun cipher(name: String, mode: Int, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val material = checkNotNull(MacLoginKeychain.passwordRead(SERVICE, name)) { "Journal key is absent" }
        try {
            check(material.size == 32)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, javax.crypto.spec.SecretKeySpec(material, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } finally { material.fill(0) }
    }
    public companion object {
        private const val SERVICE = "com.ukkera.pos.client-journal.login.v1"
        private const val POLICY = "AES256-LOGIN-KEYCHAIN-SOFTWARE"
        public fun open(): MacClientJournalKeyStore { MacLoginKeychain.requireSupported(); return MacClientJournalKeyStore() }
    }
}

/** macOS packaged-app facts for the desktop Client target and journals. */
internal class MacClientTargetPlatform private constructor(
    private val installed: SelfOwnedMacUiIdentityV2,
    override val slot: LocalTrainingCompositionSlot?,
    private val boot: MacOriginalBootSession,
) : DesktopClientTargetPlatform {
    private val identityDigest: String = SelfOwnedInstalledIdentityWireV2.encodeTarget(installed).let { bytes ->
        try { JvmP256Primitives.hashHex(bytes) } finally { bytes.fill(0) }
    }

    override fun parentDirectory(): Path = MacDesktopFiles.applicationSupport()

    override fun origin(directory: Path): TargetOrigin.Desktop {
        requireIdentity()
        val dir = checkNotNull(MacOriginalNative.lstat(directory)) { "Client target directory is absent" }
        val lock = checkNotNull(MacOriginalNative.lstat(directory.resolve(DesktopTargetSnapshotStore.LOCK))) { "Client target lock is absent" }
        check(dir.device == lock.device && dir.uid == lock.uid)
        val canonical = directory.toRealPath(LinkOption.NOFOLLOW_LINKS).toString()
        return TargetOrigin.Desktop(installed, TargetDesktopNamespaceV2(SelfOwnedNativePlatformV2.MACOS, canonical,
            dir.uid.toString(), MacOriginalNative.requireLocalVolume(directory).uuid, dir.inode.toString(), lock.inode.toString()))
    }

    override fun bootSessionId(): String { boot.elapsedMillis(); return boot.identity.bootSessionUuid }
    override fun elapsedMillis(): Long = boot.elapsedMillis()
    private fun tag(preparationId: DomainId, keyId: DomainId) = MacClientTargetKey.tag(slot, preparationId, keyId, identityDigest)
    override fun keyExists(preparationId: DomainId, keyId: DomainId): Boolean =
        MacLoginKeychain.keyFind(tag(preparationId, keyId))?.also(MacLoginKeychain::release) != null
    override fun createKey(preparationId: DomainId, keyId: DomainId): DesktopClientTargetKey { requireIdentity(); return MacClientTargetKey.create(tag(preparationId, keyId)) }
    override fun openKey(preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray): DesktopClientTargetKey {
        requireIdentity(); return MacClientTargetKey.openExisting(tag(preparationId, keyId), originalPoint)
    }
    override fun newSdkOwner(installed: SelfOwnedInstalledIdentityTargetV2): DevicePairingTargetSdkOwner =
        desktopSdkOwner(installed) { MacLoginKeychainSecureIdentityStore.open() }

    private fun requireIdentity() {
        val result = MacInstalledApplicationIdentity.captureUi(installed)
        check(result is SelfOwnedInstalledIdentityResultV2.Data) { "Signed macOS app identity is unavailable" }
    }

    companion object {
        fun observe(slot: LocalTrainingCompositionSlot?): MacClientTargetPlatform =
            MacClientTargetPlatform(MacDesktopFiles.observeOwnIdentity(), slot, MacOriginalBootSession.observe())
    }
}

/** Public macOS observations for desktop composition (DATA; the trust check is in observeUi/captureUi). */
public object MacDesktopFiles {
    public const val BUNDLE_ID: String = "com.ukkera.pos.local.desktop"

    /** Signed app's own Apple-anchored identity: own Team ID read, then the full trust check. */
    public fun observeOwnIdentity(): SelfOwnedMacUiIdentityV2 {
        val team = checkNotNull(MacInstalledApplicationIdentity.ownTeamIdentifier()) { "The app is not signed with an Apple Team ID" }
        val result = MacInstalledApplicationIdentity.observeUi(BUNDLE_ID, team)
        check(result is SelfOwnedInstalledIdentityResultV2.Data) { "Signed macOS app identity was refused" }
        return result.value
    }

    /** `~/Library/Application Support/<bundle>`: created 0700 if missing; symlinks/foreign owners refused. */
    public fun applicationSupport(): Path {
        val home = Paths.get(checkNotNull(System.getProperty("user.home")))
        val support = home.resolve("Library").resolve("Application Support")
        check(Files.isDirectory(support, LinkOption.NOFOLLOW_LINKS))
        val app = support.resolve(BUNDLE_ID)
        if (!Files.exists(app, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(app, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        check(Files.isDirectory(app, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(app))
        val owner = checkNotNull(MacOriginalNative.lstat(app)).uid
        val homeOwner = checkNotNull(MacOriginalNative.lstat(home)).uid
        check(owner == homeOwner) { "Application Support folder has a foreign owner" }
        return app
    }

    public fun volumeUuid(directory: Path): String = MacOriginalNative.requireLocalVolume(directory).uuid
}
