package com.ukkera.pos.security.supporting.windowsservice

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Developer-authored supporting diagnostic, invoked only by a disposable SCM-owned service.
 * This is not independent qualification, a production owner, or a replacement for original
 * namespace/intent/possession/history admission. It never accepts a caller-supplied identity
 * in place of the actual production process-token observer.
 */
public object WindowsServiceSigningNativeProbe {
    @JvmStatic public fun main(args: Array<String>) {
        require(args.size == 2 && Platform.isWindows() && Native.POINTER_SIZE == 8)
        val output = Path.of(args[0])
        require(Files.isDirectory(output))
        val target = SelfOwnedWindowsServiceIdentityV2(args[1], "S-1-5-18")
        val rootId = DomainId(UUID.randomUUID().toString())
        val keyId = DomainId(UUID.randomUUID().toString())
        val targetBytes = SelfOwnedInstalledIdentityWireV2.encodeTarget(target)
        val digest = try { JvmP256Primitives.hashHex(targetBytes) } finally { targetBytes.fill(0) }
        val keyName = "ukkera.pos.original.signing.v2:live:target:${rootId.value}:${keyId.value}:$digest"
        val stages = output.resolve("signing-stages.txt")
        fun stage(name: String) { Files.writeString(stages, "$name\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
        var active: WindowsSelfOwnedSigningKey? = null
        var failed: Throwable? = null
        var creationAttempted = false
        try {
            stage("native-service-observation-start")
            val observation = WindowsInstalledApplicationIdentity.captureService(target)
            check(observation is SelfOwnedInstalledIdentityResultV2.Data && !observation.value.isAppContainer)
            WindowsSelfOwnedSigningKey.requireService(target, target.serviceSid, target.serviceAccountSid)
            stage("native-service-observation-pass")
            val wrongAccount = SelfOwnedWindowsServiceIdentityV2(target.serviceSid, "S-1-5-19")
            check(WindowsInstalledApplicationIdentity.captureService(wrongAccount) is SelfOwnedInstalledIdentityResultV2.Refused)
            stage("wrong-account-refused")
            creationAttempted = true
            active = WindowsSelfOwnedSigningKey.createOriginal(target, LocalMasterSdkProfileV1,
                rootId, keyId, WindowsSelfOwnedSigningKey.Role.TARGET, SelfOwnedKeyProtectionV1.OS_ISOLATED)
            stage("production-create-and-private-acl-pass")
            val point = checkNotNull(active).publicPoint()
            val message = "ukkera-pos-synthetic-service-signing-v1:${UUID.randomUUID()}".toByteArray()
            try {
                val signature = checkNotNull(active).sign(message)
                try { check(JvmP256Primitives.verify(point, message, signature)) } finally { signature.fill(0) }
                stage("production-sign-and-public-verify-pass")
                checkNotNull(active).close(); checkNotNull(active).close()
                check(runCatching { checkNotNull(active).publicPoint() }.isFailure)
                active = null
                stage("confirmed-close-denies-use-pass")
                active = WindowsSelfOwnedSigningKey.openExisting(target, LocalMasterSdkProfileV1,
                    rootId, keyId, WindowsSelfOwnedSigningKey.Role.TARGET, SelfOwnedKeyProtectionV1.OS_ISOLATED, point)
                val reopened = checkNotNull(active).publicPoint()
                try { check(reopened.contentEquals(point)) } finally { reopened.fill(0) }
                val second = checkNotNull(active).sign(message)
                try { check(JvmP256Primitives.verify(point, message, second)) } finally { second.fill(0) }
                stage("production-existing-only-reopen-and-sign-pass")
                checkNotNull(active).close(); active = null
                check(runCatching {
                    WindowsSelfOwnedSigningKey.createOriginal(target, LocalMasterSdkProfileV1,
                        rootId, keyId, WindowsSelfOwnedSigningKey.Role.TARGET, SelfOwnedKeyProtectionV1.OS_ISOLATED).use { }
                }.isFailure)
                // A duplicate-create refusal must leave the original usable and unchanged.
                active = WindowsSelfOwnedSigningKey.openExisting(target, LocalMasterSdkProfileV1,
                    rootId, keyId, WindowsSelfOwnedSigningKey.Role.TARGET, SelfOwnedKeyProtectionV1.OS_ISOLATED, point)
                val finalPoint = checkNotNull(active).publicPoint()
                try { check(finalPoint.contentEquals(point)) } finally { finalPoint.fill(0) }
                stage("duplicate-create-refused-original-preserved-pass")
            } finally { point.fill(0); message.fill(0) }
        } catch (failure: Throwable) {
            failed = failure
            stage("failure-class=${failure.javaClass.name}")
            // The production errors contain fixed diagnostic text; no key bytes are logged.
            stage("failure-message=${failure.message?.replace('\n', ' ')?.take(256)}")
        } finally {
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = failed
                    if (first == null) failed = failure else if (first !== failure) first.addSuppressed(failure)
                }
            }
            cleanup { active?.close() }
            if (creationAttempted) cleanup { deleteExactSyntheticKey(keyName) }
            Files.writeString(output.resolve("signing-result.txt"), if (failed == null)
                "status=PASS\nprofile=actual-service-token-and-production-signing-wrapper\n" else "status=FAIL\n")
        }
        failed?.let { throw it }
    }

    /** Exact random synthetic key only; never enumerates keys, overwrites, or deletes real names. */
    private fun deleteExactSyntheticKey(name: String) {
        val output = PointerByReference()
        WindowsOriginalCng.api.NCryptOpenStorageProvider(output, WString("Microsoft Software Key Storage Provider"), 0).checkCng()
        val provider = checkNotNull(output.value)
        try {
            val status = WindowsOriginalCng.api.NCryptOpenKey(provider, output, WString(name), 0, WindowsOriginalCng.SILENT)
            if (status == 0x80090016.toInt()) return // No such exact key after a refused create.
            status.checkCng()
            val key = checkNotNull(output.value)
            // Successful DeleteKey consumes the exact native key handle.
            Delete.api.NCryptDeleteKey(key, WindowsOriginalCng.SILENT).checkCng()
        } finally { WindowsOriginalCng.api.NCryptFreeObject(provider).checkCng() }
    }
    private object Delete { val api: DeleteApi by lazy { Native.load("ncrypt", DeleteApi::class.java) } }
    private interface DeleteApi : StdCallLibrary { fun NCryptDeleteKey(key: Pointer, flags: Int): Int }
}
