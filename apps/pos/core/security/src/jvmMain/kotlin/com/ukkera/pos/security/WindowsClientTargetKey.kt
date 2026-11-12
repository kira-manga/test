package com.ukkera.pos.security

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest

/**
 * Windows desktop Client target key inside the packaged application (owner decision: no Client
 * background service). One persisted, non-exportable, signing-only P-256 CNG key owned by the
 * current user, whose DACL grants only that user. Every use re-observes the exact packaged identity
 * and refuses impersonation. Possession is local custody, never remote attestation or authority.
 */
internal class WindowsClientTargetKey private constructor(
    private var provider: Pointer?,
    private var key: Pointer?,
    private val expected: SelfOwnedWindowsPackagedAppIdentityV2,
    private val userSid: String,
    private val point: ByteArray,
) : DesktopClientTargetKey {
    override val security: SelfOwnedNativeSecurityV1 get() = SelfOwnedNativeSecurityV1.SOFTWARE
    private var closeStarted = false

    @Synchronized override fun publicPoint(): ByteArray { requireCurrent(); return point.copyOf() }

    /** Signs the owner's fixed domain-separated message; the signature is verified before return. */
    @Synchronized override fun sign(message: ByteArray): ByteArray {
        require(message.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 65_536)
        val handle = requireCurrent()
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        try {
            val used = IntByReference()
            val raw = ByteArray(64)
            try {
                WindowsOriginalCng.api.NCryptSignHash(handle, null, digest, digest.size, raw, raw.size, used,
                    WindowsOriginalCng.SILENT).checkCng()
                check(used.value == 64) { "CNG returned an unexpected P-256 signature size" }
                val signature = JvmP256Primitives.canonicalize(raw)
                check(JvmP256Primitives.verify(point, message, signature)) { "CNG key possession readback failed" }
                return signature
            } finally { raw.fill(0) }
        } finally { digest.fill(0) }
    }

    @Synchronized override fun close() {
        if (closeStarted) return
        closeStarted = true
        var failure: Throwable? = null
        listOf({ key?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng(); key = null } },
            { provider?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng(); provider = null } }).forEach { release ->
            try { release() } catch (problem: Throwable) { failure?.addSuppressed(problem) ?: run { failure = problem } }
        }
        point.fill(0)
        failure?.let { throw it }
    }

    private fun requireCurrent(): Pointer {
        check(!closeStarted) { "Client target key is closed" }
        val handle = checkNotNull(key)
        check(requirePackagedUser(expected) == userSid) { "Windows user changed" }
        WindowsOriginalCng.requireKey(handle, SelfOwnedKeyProtectionV1.OS_ISOLATED, userSid, userSid)
        val actual = WindowsOriginalCng.publicPoint(handle)
        try { check(MessageDigest.isEqual(point, actual)) { "Client target key changed" } } finally { actual.fill(0) }
        return handle
    }

    companion object {
        /** First creation only; an existing key with the same name is never replaced. */
        fun create(expected: SelfOwnedWindowsPackagedAppIdentityV2, slot: LocalTrainingCompositionSlot?,
            preparationId: DomainId, keyId: DomainId): WindowsClientTargetKey =
            acquire(expected, slot, preparationId, keyId, null)

        /** Existing only; the opened key must equal the retained original public point. */
        fun openExisting(expected: SelfOwnedWindowsPackagedAppIdentityV2, slot: LocalTrainingCompositionSlot?,
            preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray): WindowsClientTargetKey {
            JvmP256Primitives.publicKey(originalPoint)
            return acquire(expected, slot, preparationId, keyId, originalPoint)
        }

        internal fun keyName(expected: SelfOwnedWindowsPackagedAppIdentityV2, slot: LocalTrainingCompositionSlot?,
            preparationId: DomainId, keyId: DomainId): String {
            val target = SelfOwnedInstalledIdentityWireV2.encodeTarget(expected)
            val digest = try { JvmP256Primitives.hashHex(target) } finally { target.fill(0) }
            val mode = slot?.name?.lowercase() ?: "live"
            return "ukkera.pos.client-target.v2:$mode:${preparationId.value}:${keyId.value}:$digest"
        }

        private fun acquire(expected: SelfOwnedWindowsPackagedAppIdentityV2, slot: LocalTrainingCompositionSlot?,
            preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray?): WindowsClientTargetKey {
            if (!Platform.isWindows() || Native.POINTER_SIZE != 8) selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            val userSid = requirePackagedUser(expected)
            val name = WString(keyName(expected, slot, preparationId, keyId))
            val providerRef = PointerByReference()
            WindowsOriginalCng.api.NCryptOpenStorageProvider(providerRef,
                WString("Microsoft Software Key Storage Provider"), 0).checkCng()
            val providerHandle = checkNotNull(providerRef.value)
            var keyHandle: Pointer? = null
            try {
                WindowsOriginalCng.requireProvider(providerHandle, SelfOwnedKeyProtectionV1.OS_ISOLATED)
                val output = PointerByReference()
                if (originalPoint == null) {
                    WindowsOriginalCng.api.NCryptCreatePersistedKey(providerHandle, output, WString("ECDSA_P256"), name, 0, 0).checkCng()
                    keyHandle = checkNotNull(output.value)
                    WindowsOriginalCng.setInt(keyHandle, "Export Policy", 0)
                    WindowsOriginalCng.setInt(keyHandle, "Key Usage", 2)
                    WindowsOriginalCng.setPrivateSecurity(keyHandle, userSid, userSid)
                    WindowsOriginalCng.api.NCryptFinalizeKey(keyHandle, WindowsOriginalCng.SILENT).checkCng()
                } else {
                    WindowsOriginalCng.api.NCryptOpenKey(providerHandle, output, name, 0, WindowsOriginalCng.SILENT).checkCng()
                    keyHandle = checkNotNull(output.value)
                }
                WindowsOriginalCng.requireKey(keyHandle, SelfOwnedKeyProtectionV1.OS_ISOLATED, userSid, userSid)
                val point = WindowsOriginalCng.publicPoint(keyHandle)
                try {
                    if (originalPoint != null) check(MessageDigest.isEqual(originalPoint, point)) { "Not the retained original key" }
                    return WindowsClientTargetKey(providerHandle, keyHandle, expected, userSid, point)
                } catch (failure: Throwable) { point.fill(0); throw failure }
            } catch (failure: Throwable) {
                // A persisted key is retained for original recovery; freeing a handle never deletes it.
                WindowsOriginalCng.closeAfterAcquisitionFailure(failure, keyHandle, providerHandle)
                throw failure
            }
        }

        /** Exact packaged UI identity, no impersonation; returns the process-token user SID. */
        internal fun requirePackagedUser(expected: SelfOwnedWindowsPackagedAppIdentityV2): String {
            val observed = (WindowsInstalledApplicationIdentity.captureUi(expected) as? SelfOwnedInstalledIdentityResultV2.Data)?.value
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            if (observed.isAppContainer) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            val thread = WinNT.HANDLEByReference()
            if (Advapi32.INSTANCE.OpenThreadToken(Kernel32.INSTANCE.GetCurrentThread(), WinNT.TOKEN_QUERY, true, thread)) {
                Kernel32.INSTANCE.CloseHandle(thread.value)
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            }
            val token = WinNT.HANDLEByReference()
            check(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token))
            return try {
                Advapi32Util.getTokenAccount(token.value).sidString.also {
                    if (!SelfOwnedInstalledIdentityGrammarV2.accountSid(it)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
                }
            } finally { Kernel32.INSTANCE.CloseHandle(token.value) }
        }
    }
}
