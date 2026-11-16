package com.ukkera.pos.database

import com.ukkera.pos.security.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bounded existing-only recovery executor, NOT an independent oracle. Opens the two actual
 * failed run-02 originals through production validation. It never prepares a target, creates
 * a signing key, allocates another acquisition, rewrites/re-signs a snapshot or grants trust.
 * Exact original byte digests and input mapping are fixed BEFORE execution. The unchanged
 * Linux SOFTWARE OS stand-ins do not qualify a native desktop installation or owner devices.
 */
internal object LanGenuineOriginalRecovery {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Pass the separately frozen existing-original recovery mapping" }
        val mappingPath = Path.of(arguments.single()).toRealPath()
        val mapping = Json.parseToJsonElement(Files.readString(mappingPath)).jsonObject
        check(mapping.getValue("status").jsonPrimitive.content == "FROZEN_BEFORE_FIRST_EXECUTION")
        val root = Path.of(mapping.getValue("retained_root").jsonPrimitive.content).toRealPath()
        check(LanProcessNativeSupport.bootId() == mapping.getValue("same_os_boot").jsonPrimitive.content)
        val roles = mapping.getValue("original_files").jsonObject
        check(roles.keys == setOf("client1", "client2"))
        for ((role, entries) in roles) {
            val roleRoot = root.resolve(role)
            val expected = entries.jsonObject.mapValues { it.value.jsonPrimitive.content }
            checkOriginals(roleRoot, expected)
            val native = roleRoot.resolve("native-data/ukkera-device-pairing-target-v1")
            val intentBytes = Files.readAllBytes(native.resolve("sdk-metadata-signing-intent.v2"))
            val intent = try { TargetSdkWireV2.decodeSigningIntent(intentBytes, TargetWireVariant.DESKTOP_V2) }
                finally { intentBytes.fill(0) }
            val expectedIdentity = intent.descriptor.copyBytes()
            val platform = LanLinuxClientTargetPlatform(roleRoot)
            // EXISTING ONLY. Native core validates original origin, custody, all embedded
            // snapshots and the retained signature before disclosing SDK association DATA.
            val target = DesktopDevicePairingTarget.openExisting(platform)
            try {
                val original = target.recoverSdkOriginal()
                val actual = original.identity.copyWireBytes()
                try { check(actual.contentEquals(expectedIdentity)) { "$role original SDK descriptor changed" } }
                finally { actual.fill(0) }
                val same = target.acquireSdkIdentity(original.identity.appId, original.exactDeviceName).copyWireBytes()
                try { check(same.contentEquals(expectedIdentity)) { "$role same-original DATA acquisition changed" } }
                finally { same.fill(0) }
                // This actual production SDK owner must reopen the pre-existing opaque SDK
                // identity. Mapping checks guarantee those bytes exist before construction;
                // the exact public identity must match the native-signed original afterwards.
                val sdk = platform.newSdkOwner(checkNotNull(original.identity.installedApplication))
                try {
                    sdk.create(original.identity.appId, original.exactDeviceName)
                    sdk.requireMatches(original.identity, original.exactDeviceName)
                } finally { sdk.close() }
            } finally { expectedIdentity.fill(0); target.close() }
            checkOriginals(roleRoot, expected)
            println("$role: production original reopen/signature validation and existing SDK identity match; immutable originals unchanged")
        }
        println("Existing-only recovery completed for both retained software-OS originals; no admission/TCP/native-device acceptance claim")
    }

    private fun checkOriginals(root: Path, expected: Map<String, String>) {
        val actualNames = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .map { root.relativize(it).toString() }.filter { !it.endsWith("/ownership.lock") && !it.endsWith("/owner.lock") }
            .toList().toSet() }
        check(actualNames == expected.keys) { "Original file census changed; do not erase, adopt, re-enroll or silently replace" }
        for ((relative, digest) in expected) {
            val path = root.resolve(relative).normalize()
            check(path.startsWith(root) && Files.isRegularFile(path, NOFOLLOW_LINKS))
            val bytes = Files.readAllBytes(path)
            try { check(sha256(bytes).value == digest) { "Original file changed: $relative" } }
            finally { bytes.fill(0) }
        }
    }
}
