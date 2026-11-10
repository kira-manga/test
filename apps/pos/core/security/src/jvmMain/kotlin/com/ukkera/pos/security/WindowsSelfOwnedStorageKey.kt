package com.ukkera.pos.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.security.MessageDigest

/**
 * Native AES-256/GCM primitive for the original Windows service, with no raw-key,
 * import/export, overwrite, deletion or provider-fallback API. Creation requires
 * the original STARTED storage intent; the enclosing native owner must first force
 * that intent and retain the namespace lease. Reopening requires the COMPLETE
 * original intent and its exact possession proof. Neither operation grants history,
 * Room, employee or Master authority. This is not a supervised custody lifetime.
 *
 * Only the observed OS_ISOLATED software-KSP profile is implemented. A hardware
 * selection refuses instead of substituting a software key. PEPPER remains separate.
 */
internal class WindowsSelfOwnedStorageKey private constructor(
    private var provider: Pointer?,
    private var key: Pointer?,
    private val target: SelfOwnedInstalledIdentityTargetV2,
    private val serviceSid: String,
    private val accountSid: String,
    private val binding: ByteArray,
    private val proof: ByteArray,
) : AutoCloseable {
    val security: SelfOwnedNativeSecurityV1 get() = SelfOwnedNativeSecurityV1.SOFTWARE

    /** Opaque public DATA for the native intent, never key material or an admission permit. */
    @Synchronized fun originalPossessionProof(): ByteArray {
        requireCurrent()
        return proof.copyOf()
    }

    @Synchronized fun seal(aad: ByteArray, plaintext: ByteArray): WindowsOriginalAesGcmSealed {
        require(aad.size in 1..MAX_BYTES && plaintext.size in 1..MAX_BYTES)
        val handle = requireCurrent()
        val nonce = JvmP256Primitives.randomBytes(12)
        var encrypted: ByteArray? = null
        try {
            encrypted = crypt(handle, false, nonce, aad, plaintext)
            requireCurrent()
            return WindowsOriginalAesGcmSealed(nonce.copyOf(), encrypted.copyOf())
        } finally { nonce.fill(0); encrypted?.fill(0) }
    }

    @Synchronized fun open(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(aad.size in 1..MAX_BYTES && nonce.size == 12 && ciphertext.size in 17..MAX_BYTES + 16)
        val plaintext = crypt(requireCurrent(), true, nonce, aad, ciphertext)
        try { requireCurrent(); return plaintext }
        catch (failure: Throwable) { plaintext.fill(0); throw failure }
    }

    @Synchronized override fun close() {
        key?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng(); key = null }
        provider?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng(); provider = null }
        binding.fill(0); proof.fill(0)
    }

    private fun requireCurrent(): Pointer {
        val handle = checkNotNull(key) { "Windows original storage lifetime is closed" }
        WindowsSelfOwnedSigningKey.requireService(target, serviceSid, accountSid)
        requireKey(checkNotNull(provider), handle, serviceSid, accountSid)
        verifyPossession(handle, binding, proof)
        requireKey(checkNotNull(provider), handle, serviceSid, accountSid)
        WindowsSelfOwnedSigningKey.requireService(target, serviceSid, accountSid)
        return handle
    }

    companion object {
        private const val MAX_BYTES = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 65_536
        private const val PAD_CIPHER = 0x10

        fun createOriginal(original: SelfOwnedNativeIntentV2, profile: LocalCompositionSdkProfileV1): WindowsSelfOwnedStorageKey =
            acquire(original, profile, create = true)

        fun openExisting(original: SelfOwnedNativeIntentV2, profile: LocalCompositionSdkProfileV1): WindowsSelfOwnedStorageKey =
            acquire(original, profile, create = false)

        private fun acquire(original: SelfOwnedNativeIntentV2, profile: LocalCompositionSdkProfileV1,
            create: Boolean): WindowsSelfOwnedStorageKey {
            check(Platform.isWindows() && Native.POINTER_SIZE == 8) { "64-bit Windows is required" }
            require(original.namespace is SelfOwnedNativeNamespaceV2.Windows &&
                original.source.protection == SelfOwnedKeyProtectionV1.OS_ISOLATED) {
                "The selected Windows storage-key profile is unavailable"
            }
            val intent = original.key(SelfOwnedTargetKeyRoleV1.STORAGE)
            require(intent.phase == if (create) SelfOwnedKeyIntentPhaseV1.STARTED else SelfOwnedKeyIntentPhaseV1.COMPLETE)
            if (!create) require(intent.security == SelfOwnedNativeSecurityV1.SOFTWARE)
            val target = original.source.installedTarget.target
            val (serviceSid, accountSid) = WindowsSelfOwnedSigningKey.serviceCoordinates(target)
            WindowsSelfOwnedSigningKey.requireService(target, serviceSid, accountSid)
            val mode = when (profile) {
                LocalMasterSdkProfileV1 -> "live"
                is LocalTrainingSdkProfileV1 -> profile.slot.name.lowercase()
                else -> error("Unknown native storage composition profile")
            }
            val targetBytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)
            val targetDigest = try { JvmP256Primitives.hashHex(targetBytes) } finally { targetBytes.fill(0) }
            val name = WString("ukkera.pos.original.storage.v2:$mode:${original.source.rootBody.rootId.value}:${intent.keyId.value}:$targetDigest")
            val output = PointerByReference()
            WindowsOriginalCng.api.NCryptOpenStorageProvider(output,
                WString("Microsoft Software Key Storage Provider"), 0).checkCng()
            val provider = checkNotNull(output.value)
            var key: Pointer? = null
            var binding: ByteArray? = null
            var proof: ByteArray? = null
            try {
                WindowsOriginalCng.requireProvider(provider, SelfOwnedKeyProtectionV1.OS_ISOLATED)
                val keyOutput = PointerByReference()
                if (create) {
                    // No overwrite: a failure leaves the original intent/key unresolved.
                    WindowsOriginalCng.api.NCryptCreatePersistedKey(provider, keyOutput, WString("AES"), name, 0, 0).checkCng()
                    key = checkNotNull(keyOutput.value)
                    WindowsOriginalCng.setInt(key, "Length", 256)
                    WindowsOriginalCng.setInt(key, "Export Policy", 0)
                    WindowsOriginalCng.setInt(key, "Key Usage", 1) // NCRYPT_ALLOW_DECRYPT_FLAG: symmetric cipher use.
                    WindowsOriginalCng.setPrivateSecurity(key, serviceSid, accountSid)
                    WindowsOriginalCng.api.NCryptFinalizeKey(key, WindowsOriginalCng.SILENT).checkCng()
                } else {
                    WindowsOriginalCng.api.NCryptOpenKey(provider, keyOutput, name, 0, WindowsOriginalCng.SILENT).checkCng()
                    key = checkNotNull(keyOutput.value)
                }
                // GCM is a property of the finalized, opened handle; it is reapplied on reopen.
                setGcm(key)
                requireKey(provider, key, serviceSid, accountSid)
                binding = SelfOwnedNativeInstallationWireV2.keyBinding(original, SelfOwnedTargetKeyRoleV1.STORAGE)
                proof = if (create) makePossession(key, binding) else intent.proof.copyBytes()
                verifyPossession(key, binding, proof)
                WindowsSelfOwnedSigningKey.requireService(target, serviceSid, accountSid)
                return WindowsSelfOwnedStorageKey(provider, key, target, serviceSid, accountSid, binding, proof)
            } catch (failure: Throwable) {
                binding?.fill(0); proof?.fill(0)
                // Handle close does not delete or adopt any persisted key.
                key?.let { if (WindowsOriginalCng.api.NCryptFreeObject(it) != 0)
                    failure.addSuppressed(IllegalStateException("Windows storage-key handle cleanup failed")) }
                if (WindowsOriginalCng.api.NCryptFreeObject(provider) != 0)
                    failure.addSuppressed(IllegalStateException("Windows storage-provider handle cleanup failed"))
                throw failure
            }
        }

        private fun setGcm(key: Pointer) {
            val bytes = "ChainingModeGCM\u0000".toByteArray(Charsets.UTF_16LE)
            try { Memory(bytes.size.toLong()).use { memory ->
                memory.write(0, bytes, 0, bytes.size)
                WindowsOriginalCng.api.NCryptSetProperty(key, WString("Chaining Mode"), memory, bytes.size, 0).checkCng()
            } } finally { bytes.fill(0) }
        }

        private fun requireKey(provider: Pointer, key: Pointer, serviceSid: String, accountSid: String) {
            WindowsOriginalCng.requireProvider(provider, SelfOwnedKeyProtectionV1.OS_ISOLATED)
            check(WindowsOriginalCng.stringProperty(key, "Algorithm Name") == "AES" &&
                WindowsOriginalCng.intProperty(key, "Length") == 256 &&
                WindowsOriginalCng.intProperty(key, "Export Policy") == 0 &&
                WindowsOriginalCng.intProperty(key, "Key Usage") == 1 &&
                WindowsOriginalCng.stringProperty(key, "Chaining Mode") == "ChainingModeGCM") {
                "Windows original storage-key profile differs"
            }
            WindowsOriginalCng.requirePrivateSecurity(key, serviceSid, accountSid)
        }

        private fun makePossession(key: Pointer, binding: ByteArray): ByteArray {
            val nonce = JvmP256Primitives.randomBytes(12)
            val expected = JvmP256Primitives.hash(binding)
            var encrypted: ByteArray? = null
            return try {
                encrypted = crypt(key, false, nonce, binding, expected)
                byteArrayOf(1) + nonce + encrypted
            } finally { nonce.fill(0); expected.fill(0); encrypted?.fill(0) }
        }

        private fun verifyPossession(key: Pointer, binding: ByteArray, proof: ByteArray) {
            check(proof.size == 61 && proof[0] == 1.toByte()) { "Windows original storage-key proof differs" }
            val nonce = proof.copyOfRange(1, 13); val encrypted = proof.copyOfRange(13, 61)
            val expected = JvmP256Primitives.hash(binding)
            try {
                val actual = crypt(key, true, nonce, binding, encrypted)
                try { check(MessageDigest.isEqual(expected, actual)) { "Windows original storage-key possession differs" } }
                finally { actual.fill(0) }
            } finally { nonce.fill(0); encrypted.fill(0); expected.fill(0) }
        }

        private fun crypt(key: Pointer, decrypt: Boolean, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
            require(nonce.size == 12 && aad.size in 1..MAX_BYTES)
            val header = WindowsOriginalGcmAuthInfo().size()
            check(header == 88) // x64 SDK layout; unsupported pointer widths were refused above.
            val inputSize = if (decrypt) input.size - 16 else input.size
            require(inputSize in 0..MAX_BYTES)
            Memory((header + nonce.size + aad.size + 16).toLong()).use { memory ->
                memory.clear()
                val nonceAt = header.toLong(); val aadAt = nonceAt + nonce.size; val tagAt = aadAt + aad.size
                memory.write(nonceAt, nonce, 0, nonce.size); memory.write(aadAt, aad, 0, aad.size)
                if (decrypt) memory.write(tagAt, input, inputSize, 16)
                val auth = WindowsOriginalGcmAuthInfo(memory).apply {
                    size = header; version = 1; this.nonce = memory.share(nonceAt); nonceSize = nonce.size
                    this.aad = memory.share(aadAt); aadSize = aad.size; tag = memory.share(tagAt); tagSize = 16
                    write()
                }
                val padding = WindowsOriginalCipherPadding().apply {
                    size = size(); flags = 2; other = auth.pointer; otherSize = header; write()
                }
                val output = ByteArray(inputSize)
                val used = IntByReference()
                try {
                    val status = if (decrypt) WindowsOriginalCng.api.NCryptDecrypt(key, input, inputSize,
                        padding.pointer, output, output.size, used, PAD_CIPHER or WindowsOriginalCng.SILENT)
                    else WindowsOriginalCng.api.NCryptEncrypt(key, input, inputSize,
                        padding.pointer, output, output.size, used, PAD_CIPHER or WindowsOriginalCng.SILENT)
                    status.checkCng()
                    check(used.value == output.size) { "Windows GCM returned an unexpected output size" }
                    return if (decrypt) output.copyOf() else output + memory.getByteArray(tagAt, 16)
                } finally { output.fill(0); memory.clear(); padding.clear() }
            }
        }
    }
}

internal class WindowsOriginalAesGcmSealed(val nonce: ByteArray, val ciphertext: ByteArray) {
    fun clear() { nonce.fill(0); ciphertext.fill(0) }
    override fun toString(): String = "WindowsOriginalAesGcmSealed([REDACTED])"
}

@Structure.FieldOrder("size", "version", "nonce", "nonceSize", "aad", "aadSize", "tag", "tagSize", "mac", "macSize", "aadTotal", "dataTotal", "flags")
internal class WindowsOriginalGcmAuthInfo : Structure {
    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)
    @JvmField var size: Int = 0
    @JvmField var version: Int = 0
    @JvmField var nonce: Pointer? = null
    @JvmField var nonceSize: Int = 0
    @JvmField var aad: Pointer? = null
    @JvmField var aadSize: Int = 0
    @JvmField var tag: Pointer? = null
    @JvmField var tagSize: Int = 0
    @JvmField var mac: Pointer? = null
    @JvmField var macSize: Int = 0
    @JvmField var aadTotal: Int = 0
    @JvmField var dataTotal: Long = 0
    @JvmField var flags: Int = 0
}

@Structure.FieldOrder("size", "flags", "iv", "ivSize", "other", "otherSize")
internal class WindowsOriginalCipherPadding : Structure() {
    @JvmField var size: Int = 0
    @JvmField var flags: Int = 0
    @JvmField var iv: Pointer? = null
    @JvmField var ivSize: Int = 0
    @JvmField var other: Pointer? = null
    @JvmField var otherSize: Int = 0
}
