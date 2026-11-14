package com.ukkera.pos.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.ShortByReference
import com.sun.jna.win32.StdCallLibrary
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest

/**
 * Concrete service-process CNG signing custody. The private key never leaves its named KSP.
 * This is a native primitive, not installation approval or a current Master. The original
 * owner must force its creation intent and hold its namespace lease before createOriginal;
 * existing-only reopen always requires the original retained public point. No overwrite,
 * implicit recreation, provider fallback, deletion or key-export path is supplied.
 */
internal class WindowsSelfOwnedSigningKey private constructor(
    private var provider: Pointer?,
    private var key: Pointer?,
    private val target: SelfOwnedInstalledIdentityTargetV2,
    private val serviceSid: String,
    private val accountSid: String,
    private val protection: SelfOwnedKeyProtectionV1,
    private val point: ByteArray,
) : AutoCloseable {
    val security: SelfOwnedNativeSecurityV1 get() = when (protection) {
        SelfOwnedKeyProtectionV1.OS_ISOLATED -> SelfOwnedNativeSecurityV1.SOFTWARE
        SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED
        SelfOwnedKeyProtectionV1.STRONGBOX -> error("StrongBox is not a Windows key policy")
    }

    @Synchronized fun publicPoint(): ByteArray {
        requireCurrent()
        return point.copyOf()
    }

    /** Called only with an owner's fixed, domain-separated original/journal message. */
    @Synchronized fun sign(message: ByteArray): ByteArray {
        require(message.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 65_536)
        val handle = requireCurrent()
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        try {
            val used = IntByReference()
            WindowsOriginalCng.api.NCryptSignHash(handle, null, digest, digest.size, null, 0, used, WindowsOriginalCng.SILENT).checkCng()
            check(used.value == 64) { "CNG returned an unexpected P-256 signature size" }
            val raw = ByteArray(64)
            val signature = try {
                WindowsOriginalCng.api.NCryptSignHash(handle, null, digest, digest.size, raw, raw.size, used, WindowsOriginalCng.SILENT).checkCng()
                check(used.value == raw.size)
                JvmP256Primitives.canonicalize(raw)
            } finally { raw.fill(0) }
            try {
                check(JvmP256Primitives.verify(point, message, signature)) { "CNG key possession readback failed" }
                requireCurrent()
                return signature
            } catch (failure: Throwable) { signature.fill(0); throw failure }
        } finally { digest.fill(0) }
    }

    private var closeStarted = false
    private var closeConfirmed = false

    @Synchronized override fun close() {
        if (closeStarted) {
            check(closeConfirmed) { "CNG signing handle close is unresolved" }
            return
        }
        // A failed native close is terminal. Never retry an identity-unknown handle
        // or allow another operation after the first close attempt.
        closeStarted = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
        }
        release { key?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng(); key = null } }
        release { provider?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng(); provider = null } }
        point.fill(0)
        failure?.let { throw it }
        closeConfirmed = true
    }

    private fun requireCurrent(): Pointer {
        check(!closeStarted) { "CNG signing lifetime is closed or unresolved" }
        val handle = checkNotNull(key) { "CNG signing lifetime is closed" }
        requireService(target, serviceSid, accountSid)
        WindowsOriginalCng.requireKey(handle, protection, serviceSid, accountSid)
        val actual = WindowsOriginalCng.publicPoint(handle)
        try { check(MessageDigest.isEqual(point, actual)) { "CNG original public key changed" } }
        finally { actual.fill(0) }
        return handle
    }

    internal enum class Role { ROOT, ISSUER, TARGET }

    companion object {
        fun createOriginal(target: SelfOwnedInstalledIdentityTargetV2, profile: LocalCompositionSdkProfileV1,
            rootId: DomainId, keyId: DomainId, role: Role,
            protection: SelfOwnedKeyProtectionV1): WindowsSelfOwnedSigningKey =
            acquire(target, profile, rootId, keyId, role, protection, null, create = true)

        fun openExisting(target: SelfOwnedInstalledIdentityTargetV2, profile: LocalCompositionSdkProfileV1,
            rootId: DomainId, keyId: DomainId, role: Role, protection: SelfOwnedKeyProtectionV1,
            originalPoint: ByteArray): WindowsSelfOwnedSigningKey {
            JvmP256Primitives.publicKey(originalPoint)
            return acquire(target, profile, rootId, keyId, role, protection, originalPoint, create = false)
        }

        private fun acquire(target: SelfOwnedInstalledIdentityTargetV2, profile: LocalCompositionSdkProfileV1,
            rootId: DomainId, keyId: DomainId, role: Role, protection: SelfOwnedKeyProtectionV1,
            originalPoint: ByteArray?, create: Boolean): WindowsSelfOwnedSigningKey {
            check(Platform.isWindows() && Native.POINTER_SIZE == 8) { "A 64-bit Windows service is required" }
            require(protection != SelfOwnedKeyProtectionV1.STRONGBOX) { "StrongBox is not a Windows key policy" }
            val (serviceSid, accountSid) = serviceCoordinates(target)
            requireService(target, serviceSid, accountSid)
            val mode = when (profile) {
                LocalMasterSdkProfileV1 -> "live"
                is LocalTrainingSdkProfileV1 -> profile.slot.name.lowercase()
                else -> error("Unknown native signing composition profile")
            }
            val targetBytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)
            val targetDigest = try { JvmP256Primitives.hashHex(targetBytes) } finally { targetBytes.fill(0) }
            val name = WString("ukkera.pos.original.signing.v2:$mode:${role.name.lowercase()}:${rootId.value}:${keyId.value}:$targetDigest")
            return acquireNamed(target, name, protection, originalPoint, create, serviceSid, accountSid)
        }

        /** Desktop Master custody: one persisted CNG key per shared-core derived alias of this service. */
        fun createDesktop(target: SelfOwnedInstalledIdentityTargetV2, alias: String,
            protection: SelfOwnedKeyProtectionV1): WindowsSelfOwnedSigningKey =
            acquireDesktop(target, alias, protection, null, create = true)

        fun openDesktop(target: SelfOwnedInstalledIdentityTargetV2, alias: String,
            protection: SelfOwnedKeyProtectionV1, originalPoint: ByteArray?): WindowsSelfOwnedSigningKey =
            acquireDesktop(target, alias, protection, originalPoint, create = false)

        /** True only when the named key exists; any status other than success/NTE_BAD_KEYSET fails. */
        fun desktopExists(target: SelfOwnedInstalledIdentityTargetV2, alias: String, protection: SelfOwnedKeyProtectionV1): Boolean {
            check(Platform.isWindows() && Native.POINTER_SIZE == 8) { "A 64-bit Windows service is required" }
            val (serviceSid, accountSid) = serviceCoordinates(target)
            requireService(target, serviceSid, accountSid)
            val provider = PointerByReference()
            WindowsOriginalCng.api.NCryptOpenStorageProvider(provider, providerName(protection), 0).checkCng()
            val providerHandle = checkNotNull(provider.value)
            try {
                val output = PointerByReference()
                val status = WindowsOriginalCng.api.NCryptOpenKey(providerHandle, output, desktopName(target, alias), 0,
                    WindowsOriginalCng.SILENT)
                output.value?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng() }
                if (status == NTE_BAD_KEYSET) return false
                status.checkCng()
                return true
            } finally { WindowsOriginalCng.api.NCryptFreeObject(providerHandle).checkCng() }
        }

        private const val NTE_BAD_KEYSET = 0x80090016.toInt()

        private fun providerName(protection: SelfOwnedKeyProtectionV1) = WString(when (protection) {
            SelfOwnedKeyProtectionV1.OS_ISOLATED -> "Microsoft Software Key Storage Provider"
            SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> "Microsoft Platform Crypto Provider"
            SelfOwnedKeyProtectionV1.STRONGBOX -> error("Unsupported key policy")
        })

        private fun desktopName(target: SelfOwnedInstalledIdentityTargetV2, alias: String): WString {
            require(alias.length in 1..200 && alias.all { it.code in 0x21..0x7e && it != '\\' && it != '/' })
            val targetBytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)
            val targetDigest = try { JvmP256Primitives.hashHex(targetBytes) } finally { targetBytes.fill(0) }
            return WString("ukkera.pos.desktop-master.p256.v2:$alias:$targetDigest")
        }

        private fun acquireDesktop(target: SelfOwnedInstalledIdentityTargetV2, alias: String,
            protection: SelfOwnedKeyProtectionV1, originalPoint: ByteArray?, create: Boolean): WindowsSelfOwnedSigningKey {
            check(Platform.isWindows() && Native.POINTER_SIZE == 8) { "A 64-bit Windows service is required" }
            require(protection != SelfOwnedKeyProtectionV1.STRONGBOX) { "StrongBox is not a Windows key policy" }
            originalPoint?.let(JvmP256Primitives::publicKey)
            val (serviceSid, accountSid) = serviceCoordinates(target)
            requireService(target, serviceSid, accountSid)
            return acquireNamed(target, desktopName(target, alias), protection, originalPoint, create, serviceSid, accountSid)
        }

        private fun acquireNamed(target: SelfOwnedInstalledIdentityTargetV2, name: WString,
            protection: SelfOwnedKeyProtectionV1, originalPoint: ByteArray?, create: Boolean,
            serviceSid: String, accountSid: String): WindowsSelfOwnedSigningKey {
            val provider = PointerByReference()
            WindowsOriginalCng.api.NCryptOpenStorageProvider(provider, WString(when (protection) {
                SelfOwnedKeyProtectionV1.OS_ISOLATED -> "Microsoft Software Key Storage Provider"
                SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> "Microsoft Platform Crypto Provider"
                SelfOwnedKeyProtectionV1.STRONGBOX -> error("Unsupported key policy")
            }), 0).checkCng()
            val providerHandle = checkNotNull(provider.value)
            var keyHandle: Pointer? = null
            try {
                WindowsOriginalCng.requireProvider(providerHandle, protection)
                val output = PointerByReference()
                if (create) {
                    // No NCRYPT_OVERWRITE_KEY_FLAG: an orphan/existing key is never replaced.
                    WindowsOriginalCng.api.NCryptCreatePersistedKey(providerHandle, output, WString("ECDSA_P256"), name, 0, 0).checkCng()
                    keyHandle = checkNotNull(output.value)
                    WindowsOriginalCng.setInt(keyHandle, "Export Policy", 0)
                    WindowsOriginalCng.setInt(keyHandle, "Key Usage", 2) // NCRYPT_ALLOW_SIGNING_FLAG only.
                    WindowsOriginalCng.setPrivateSecurity(keyHandle, serviceSid, accountSid)
                    WindowsOriginalCng.api.NCryptFinalizeKey(keyHandle, WindowsOriginalCng.SILENT).checkCng()
                } else {
                    WindowsOriginalCng.api.NCryptOpenKey(providerHandle, output, name, 0, WindowsOriginalCng.SILENT).checkCng()
                    keyHandle = checkNotNull(output.value)
                }
                WindowsOriginalCng.requireKey(keyHandle, protection, serviceSid, accountSid)
                val point = WindowsOriginalCng.publicPoint(keyHandle)
                try {
                    if (originalPoint != null) check(MessageDigest.isEqual(originalPoint, point)) {
                        "CNG key is not the retained original"
                    }
                    requireService(target, serviceSid, accountSid)
                    return WindowsSelfOwnedSigningKey(providerHandle, keyHandle, target, serviceSid, accountSid,
                        protection, point)
                } catch (failure: Throwable) { point.fill(0); throw failure }
            } catch (failure: Throwable) {
                // Retain any persisted key/intent for original recovery. Freeing a handle does not delete it.
                WindowsOriginalCng.closeAfterAcquisitionFailure(failure, keyHandle, providerHandle)
                throw failure
            }
        }

        internal fun serviceCoordinates(target: SelfOwnedInstalledIdentityTargetV2): Pair<String, String> = when (target) {
            is SelfOwnedWindowsServiceIdentityV2 -> target.serviceSid to target.serviceAccountSid
            is SelfOwnedWindowsPackagedServiceIdentityV2 -> target.serviceSid to target.serviceAccountSid
            else -> error("Original Windows signing custody requires an exact service identity")
        }

        internal fun requireService(target: SelfOwnedInstalledIdentityTargetV2, serviceSid: String, accountSid: String) {
            val actual = when (target) {
                is SelfOwnedWindowsServiceIdentityV2 -> WindowsInstalledApplicationIdentity.captureService(target)
                is SelfOwnedWindowsPackagedServiceIdentityV2 -> WindowsInstalledApplicationIdentity.capturePackagedService(target)
                else -> error("Original Windows signing custody requires a service")
            }
            val observation = (actual as? SelfOwnedInstalledIdentityResultV2.Data)?.value
                ?: error("Current Windows signing service identity is unavailable")
            check(!observation.isAppContainer) { "AppContainer signing custody requires a separate key ACL profile" }
            check(serviceCoordinates(observation.target) == (serviceSid to accountSid))
            // A process-token observation cannot authorize use under an impersonating thread.
            val threadToken = PointerByReference()
            if (WindowsOriginalCng.security.OpenThreadToken(WindowsOriginalCng.kernel.GetCurrentThread(), 0x0008, 1, threadToken) != 0) {
                threadToken.value?.let { check(WindowsOriginalCng.kernel.CloseHandle(it) != 0) }
                error("Impersonation is not an original signing service context")
            }
            check(Native.getLastError() == 1008) { "Cannot establish a non-impersonating service context" }
        }
    }
}

internal object WindowsOriginalCng {
    val api: WindowsOriginalNCrypt = Native.load("ncrypt", WindowsOriginalNCrypt::class.java)
    val security: WindowsOriginalSigningSecurity = Native.load("advapi32", WindowsOriginalSigningSecurity::class.java)
    val kernel: WindowsOriginalSigningKernel = Native.load("kernel32", WindowsOriginalSigningKernel::class.java)
    const val SILENT = 0x40
    private const val SECURITY_INFORMATION = 0x00000005 // OWNER + DACL

    /** Each certainly acquired handle gets one release attempt, even if its peer throws.
     * Persisted keys/intents remain unchanged. The original acquisition failure is retained;
     * no native pointer whose close is uncertain is retried or returned to a caller.
     */
    fun closeAfterAcquisitionFailure(failure: Throwable, key: Pointer?, provider: Pointer) {
        fun release(handle: Pointer) {
            try { api.NCryptFreeObject(handle).checkCng() }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        }
        key?.let(::release)
        release(provider)
    }

    fun requireProvider(provider: Pointer, protection: SelfOwnedKeyProtectionV1) {
        val implementation = intProperty(provider, "Impl Type")
        val expected = if (protection == SelfOwnedKeyProtectionV1.SECURE_HARDWARE) 1 else 2
        check(implementation and 3 == expected) { "CNG provider does not satisfy the exact selected key policy" }
        check(intProperty(provider, "Security Descr Support") == 1) {
            "CNG provider cannot enforce the service key ACL"
        }
    }

    fun requireKey(key: Pointer, protection: SelfOwnedKeyProtectionV1, serviceSid: String, accountSid: String) {
        check(protection != SelfOwnedKeyProtectionV1.STRONGBOX)
        check(stringProperty(key, "Algorithm Name") == "ECDSA_P256" && intProperty(key, "Length") == 256)
        check(intProperty(key, "Export Policy") == 0 && intProperty(key, "Key Usage") == 2) {
            "CNG signing-key export/use policy differs"
        }
        requirePrivateSecurity(key, serviceSid, accountSid)
    }

    fun requirePrivateSecurity(key: Pointer, serviceSid: String, accountSid: String) {
        val descriptor = property(key, "Security Descr", SECURITY_INFORMATION)
        try {
            Memory(descriptor.size.toLong()).use { memory ->
                memory.write(0, descriptor, 0, descriptor.size)
                check(security.IsValidSecurityDescriptor(memory) != 0)
                val control = ShortByReference(); val revision = IntByReference()
                check(security.GetSecurityDescriptorControl(memory, control, revision) != 0 &&
                    control.value.toInt() and 0x1000 != 0) { "CNG key DACL is not protected" }
                val owner = PointerByReference(); val defaulted = IntByReference()
                check(security.GetSecurityDescriptorOwner(memory, owner, defaulted) != 0 && defaulted.value == 0)
                check(sidText(checkNotNull(owner.value)) == accountSid) { "CNG key owner differs" }
                val present = IntByReference(); val acl = PointerByReference()
                check(security.GetSecurityDescriptorDacl(memory, present, acl, defaulted) != 0 &&
                    present.value != 0 && defaulted.value == 0)
                val dacl = checkNotNull(acl.value) { "CNG key has an unrestricted DACL" }
                Memory(12).use { info ->
                    check(security.GetAclInformation(dacl, info, 12, 2) != 0 && info.getInt(0) == 1) {
                        "CNG key has unexpected access entries"
                    }
                    val ace = PointerByReference()
                    check(security.GetAce(dacl, 0, ace) != 0)
                    val entry = checkNotNull(ace.value)
                    val size = entry.getShort(2).toInt() and 0xffff
                    check(entry.getByte(0).toInt() == 0 && entry.getByte(1).toInt() == 0 && size in 16..76)
                    val sid = entry.share(8)
                    check(security.IsValidSid(sid) != 0 && security.GetLengthSid(sid) == size - 8)
                    check(sidText(sid) == serviceSid && entry.getInt(4) != 0) { "CNG key grants another principal" }
                }
            }
        } finally { descriptor.fill(0) }
    }

    fun setPrivateSecurity(key: Pointer, serviceSid: String, accountSid: String) {
        val descriptor = PointerByReference(); val size = IntByReference()
        check(security.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            WString("O:${accountSid}D:P(A;;GA;;;${serviceSid})"), 1, descriptor, size) != 0)
        val memory = checkNotNull(descriptor.value)
        try {
            check(size.value in 20..4096)
            api.NCryptSetProperty(key, WString("Security Descr"), memory, size.value, SECURITY_INFORMATION).checkCng()
        } finally { check(kernel.LocalFree(memory) == null) }
    }

    fun setInt(handle: Pointer, name: String, value: Int) {
        Memory(4).use { memory ->
            memory.setInt(0, value)
            api.NCryptSetProperty(handle, WString(name), memory, 4, 0).checkCng()
        }
    }

    fun publicPoint(key: Pointer): ByteArray {
        val used = IntByReference()
        api.NCryptExportKey(key, null, WString("ECCPUBLICBLOB"), null, null, 0, used, 0).checkCng()
        check(used.value == 72)
        val blob = ByteArray(72)
        try {
            api.NCryptExportKey(key, null, WString("ECCPUBLICBLOB"), null, blob, blob.size, used, 0).checkCng()
            check(used.value == blob.size && blob.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x45, 0x43, 0x53, 0x31, 0x20, 0, 0, 0)))
            return (byteArrayOf(4) + blob.copyOfRange(8, 72)).also { JvmP256Primitives.publicKey(it) }
        } finally { blob.fill(0) }
    }

    fun intProperty(handle: Pointer, name: String): Int {
        val bytes = property(handle, name)
        return try {
            check(bytes.size == 4)
            (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8) or
                ((bytes[2].toInt() and 255) shl 16) or (bytes[3].toInt() shl 24)
        } finally { bytes.fill(0) }
    }

    fun stringProperty(handle: Pointer, name: String): String {
        val bytes = property(handle, name)
        return try {
            check(bytes.size in 4..512 && bytes.size % 2 == 0 && bytes[bytes.size - 1] == 0.toByte() &&
                bytes[bytes.size - 2] == 0.toByte())
            Charsets.UTF_16LE.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, bytes.size - 2))
                .toString().also { check('\u0000' !in it) }
        } finally { bytes.fill(0) }
    }

    private fun property(handle: Pointer, name: String, flags: Int = 0): ByteArray {
        val used = IntByReference()
        api.NCryptGetProperty(handle, WString(name), null, 0, used, flags).checkCng()
        check(used.value in 1..65_536)
        val bytes = ByteArray(used.value)
        try {
            api.NCryptGetProperty(handle, WString(name), bytes, bytes.size, used, flags).checkCng()
            check(used.value == bytes.size)
            return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    private fun sidText(sid: Pointer): String {
        check(security.IsValidSid(sid) != 0)
        val text = PointerByReference()
        check(security.ConvertSidToStringSidW(sid, text) != 0)
        val value = checkNotNull(text.value)
        return try { value.getWideString(0).also {
            check(SelfOwnedInstalledIdentityGrammarV2.accountSid(it) || SelfOwnedInstalledIdentityGrammarV2.serviceSid(it))
        } } finally { check(kernel.LocalFree(value) == null) }
    }
}

internal fun Int.checkCng() { check(this == 0) { "Windows original CNG operation failed (${toUInt().toString(16)})" } }

internal interface WindowsOriginalNCrypt : StdCallLibrary {
    fun NCryptOpenStorageProvider(provider: PointerByReference, name: WString, flags: Int): Int
    fun NCryptCreatePersistedKey(provider: Pointer, key: PointerByReference, algorithm: WString,
        name: WString, legacySpec: Int, flags: Int): Int
    fun NCryptOpenKey(provider: Pointer, key: PointerByReference, name: WString, legacySpec: Int, flags: Int): Int
    fun NCryptFinalizeKey(key: Pointer, flags: Int): Int
    fun NCryptSetProperty(handle: Pointer, name: WString, value: Pointer, count: Int, flags: Int): Int
    fun NCryptGetProperty(handle: Pointer, name: WString, value: ByteArray?, capacity: Int,
        used: IntByReference, flags: Int): Int
    fun NCryptExportKey(key: Pointer, wrappingKey: Pointer?, kind: WString, parameters: Pointer?,
        output: ByteArray?, capacity: Int, used: IntByReference, flags: Int): Int
    fun NCryptSignHash(key: Pointer, padding: Pointer?, hash: ByteArray, hashSize: Int,
        signature: ByteArray?, capacity: Int, used: IntByReference, flags: Int): Int
    fun NCryptEncrypt(key: Pointer, input: ByteArray, inputSize: Int, padding: Pointer,
        output: ByteArray, capacity: Int, used: IntByReference, flags: Int): Int
    fun NCryptDecrypt(key: Pointer, input: ByteArray, inputSize: Int, padding: Pointer,
        output: ByteArray, capacity: Int, used: IntByReference, flags: Int): Int
    fun NCryptFreeObject(handle: Pointer): Int
    fun NCryptEnumKeys(provider: Pointer, scope: WString?, keyName: PointerByReference, enumState: PointerByReference, flags: Int): Int
    fun NCryptDeleteKey(key: Pointer, flags: Int): Int
    fun NCryptFreeBuffer(buffer: Pointer): Int
}

internal interface WindowsOriginalSigningSecurity : StdCallLibrary {
    fun OpenThreadToken(thread: Pointer, access: Int, openAsSelf: Int, token: PointerByReference): Int
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(text: WString, revision: Int,
        descriptor: PointerByReference, count: IntByReference): Int
    fun IsValidSecurityDescriptor(descriptor: Pointer): Int
    fun GetSecurityDescriptorControl(descriptor: Pointer, control: ShortByReference, revision: IntByReference): Int
    fun GetSecurityDescriptorOwner(descriptor: Pointer, owner: PointerByReference, defaulted: IntByReference): Int
    fun GetSecurityDescriptorDacl(descriptor: Pointer, present: IntByReference,
        dacl: PointerByReference, defaulted: IntByReference): Int
    fun GetAclInformation(acl: Pointer, info: Pointer, size: Int, kind: Int): Int
    fun GetAce(acl: Pointer, index: Int, ace: PointerByReference): Int
    fun IsValidSid(sid: Pointer): Int
    fun GetLengthSid(sid: Pointer): Int
    fun ConvertSidToStringSidW(sid: Pointer, text: PointerByReference): Int
}

internal interface WindowsOriginalSigningKernel : StdCallLibrary {
    fun GetCurrentThread(): Pointer
    fun CloseHandle(handle: Pointer): Int
    fun LocalFree(memory: Pointer): Pointer?
}
