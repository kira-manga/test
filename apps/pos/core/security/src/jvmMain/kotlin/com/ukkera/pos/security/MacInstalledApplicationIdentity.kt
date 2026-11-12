package com.ukkera.pos.security

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Captures the signed packaged macOS UI process, then compares its actual signing tuple with
 * the original expected target. Generic JVM launches are refused. Native observations are DATA;
 * this API never creates keys, approves P1, or grants a service/history/employee authority.
 * Service supervision and custody use a separate native owner and are not inferred from UI facts.
 */
public object MacInstalledApplicationIdentity {
    public fun captureUi(expected: SelfOwnedMacUiIdentityV2):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedMacUiIdentityV2> =
        capture(expected.signingIdentifier, expected.teamIdentifier, expected)

    /**
     * First-install observation: the same packaged-launcher, Apple-anchor and Team-ID trust checks
     * as [captureUi], returning the ACTUAL identity (including its designated-requirement digest)
     * so the original preparation can bind it. DATA only; never a later-session substitute.
     */
    public fun observeUi(signingIdentifier: String, teamIdentifier: String):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedMacUiIdentityV2> = capture(signingIdentifier, teamIdentifier, null)

    /**
     * The running packaged app's own Team ID as recorded in its code signature, for the first-install
     * [observeUi] call. DATA only: [observeUi] still performs the Apple-anchor + Team-ID trust check.
     */
    public fun ownTeamIdentifier(): String? {
        if (!Platform.isMac() || Native.POINTER_SIZE != 8) return null
        return try {
            References().use { refs ->
                val code = refs.copy { MacSecurityNative.security.SecCodeCopySelf(0, it) }
                requirePackagedLauncher(refs)
                val static = refs.copy { MacSecurityNative.security.SecCodeCopyStaticCode(code, 0, it) }
                val info = refs.copy { MacSecurityNative.security.SecCodeCopySigningInformation(static, 2, it) }
                val team = MacSecurityNative.cf.CFDictionaryGetValue(info, MacSecurityNative.constant("kSecCodeInfoTeamIdentifier"))
                    ?: return null
                string(team).takeIf { SelfOwnedInstalledIdentityGrammarV2.teamIdentifier(it) }
            }
        } catch (_: Exception) { null }
    }

    private fun capture(expectedSigningIdentifier: String, expectedTeam: String, expected: SelfOwnedMacUiIdentityV2?):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedMacUiIdentityV2> {
        if (!Platform.isMac() || Native.POINTER_SIZE != 8) return refused(
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
        return try {
            References().use { refs ->
                val code = refs.copy { MacSecurityNative.security.SecCodeCopySelf(0, it) }
                requirePackagedLauncher(refs)
                val static = refs.copy { MacSecurityNative.security.SecCodeCopyStaticCode(code, 0, it) }
                val info = refs.copy {
                    MacSecurityNative.security.SecCodeCopySigningInformation(static, 2, it)
                }
                val signingId = string(checkNotNull(MacSecurityNative.cf.CFDictionaryGetValue(info,
                    MacSecurityNative.constant("kSecCodeInfoIdentifier"))))
                val team = string(checkNotNull(MacSecurityNative.cf.CFDictionaryGetValue(info,
                    MacSecurityNative.constant("kSecCodeInfoTeamIdentifier"))))
                if (!SelfOwnedInstalledIdentityGrammarV2.signingIdentifier(signingId) ||
                    !SelfOwnedInstalledIdentityGrammarV2.teamIdentifier(team)) return refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                if (signingId != expectedSigningIdentifier || team != expectedTeam) return refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH)
                // Grammar excludes quote/backslash injection. Still read the compiled requirement
                // back and verify its complete tokens before relying on it as an Apple-anchor check.
                val requirementText = "identifier \"$signingId\" and anchor apple generic and " +
                    "certificate leaf[subject.OU] = \"$team\""
                val text = refs.string(requirementText)
                val requirement = refs.copy {
                    MacSecurityNative.security.SecRequirementCreateWithString(text, 0, it)
                }
                val compiled = refs.copy {
                    MacSecurityNative.security.SecRequirementCopyString(requirement, 0, it)
                }
                check(tokens(string(compiled)) == tokens(requirementText)) {
                    "Compiled signing requirement differs"
                }
                if (MacSecurityNative.security.SecCodeCheckValidity(code, 0, requirement) != 0) return refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_SIGNER_TRUST)
                val designated = refs.copy {
                    MacSecurityNative.security.SecCodeCopyDesignatedRequirement(static, 0, it)
                }
                val designatedBytes = refs.copy {
                    MacSecurityNative.security.SecRequirementCopyData(designated, 0, it)
                }
                check(MacSecurityNative.cf.CFGetTypeID(designatedBytes) == MacSecurityNative.cf.CFDataGetTypeID())
                val length = MacSecurityNative.cf.CFDataGetLength(designatedBytes).toLong()
                check(length in 1..1_048_576) { "Designated requirement exceeds native observation capacity" }
                val blob = checkNotNull(MacSecurityNative.cf.CFDataGetBytePtr(designatedBytes))
                    .getByteArray(0, length.toInt())
                val digest = try { MessageDigest.getInstance("SHA-256").digest(blob)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) } } finally { blob.fill(0) }
                val actual = SelfOwnedMacUiIdentityV2(signingId, team, digest)
                if (expected != null && !SelfOwnedInstalledIdentityV2.matches(expected, actual)) return refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH)
                SelfOwnedInstalledIdentityResultV2.Data(actual)
            }
        } catch (_: UnsupportedLauncher) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROCESS_IDENTITY_UNSUPPORTED)
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        } catch (_: LinkageError) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }
    }

    private fun requirePackagedLauncher(refs: References) {
        val bundle = MacSecurityNative.cf.CFBundleGetMainBundle() ?: throw UnsupportedLauncher()
        val executable = MacSecurityNative.cf.CFBundleCopyExecutableURL(bundle) ?: throw UnsupportedLauncher()
        refs.retainCopied(executable)
        val bundlePath = ByteArray(4096)
        if (MacSecurityNative.cf.CFURLGetFileSystemRepresentation(executable, 1, bundlePath,
                NativeLong(bundlePath.size.toLong())) == 0.toByte()) throw UnsupportedLauncher()
        val processPath = ByteArray(4096)
        val proc = Native.load("proc", MacProcessPath::class.java)
        val count = proc.proc_pidpath(ProcessHandle.current().pid().toInt(), processPath, processPath.size)
        if (count <= 0 || count >= processPath.size) throw UnsupportedLauncher()
        val actual = Path.of(nulString(processPath)).toRealPath()
        val declared = Path.of(nulString(bundlePath)).toRealPath()
        val macos = actual.parent ?: throw UnsupportedLauncher()
        val contents = macos.parent ?: throw UnsupportedLauncher()
        val app = contents.parent ?: throw UnsupportedLauncher()
        if (!Files.isSameFile(actual, declared) || macos.fileName.toString() != "MacOS" ||
            contents.fileName.toString() != "Contents" || !app.fileName.toString().endsWith(".app") ||
            actual.fileName.toString().lowercase() in setOf("java", "javaw", "sh", "bash", "zsh", "python", "python3")) {
            throw UnsupportedLauncher()
        }
    }

    private fun tokens(value: String): List<String> {
        val pattern = Regex("\"[^\"\\\\]*\"|[A-Za-z0-9._-]+|[\\[\\]=()]")
        var position = 0
        val tokens = mutableListOf<String>()
        for (match in pattern.findAll(value)) {
            check(value.substring(position, match.range.first).all { it.isWhitespace() })
            val token = match.value
            if (token != "(" && token != ")") tokens += token.removeSurrounding("\"")
            position = match.range.last + 1
        }
        check(value.substring(position).all { it.isWhitespace() })
        return tokens
    }

    private fun string(value: Pointer): String {
        check(MacSecurityNative.cf.CFGetTypeID(value) == MacSecurityNative.cf.CFStringGetTypeID())
        val length = MacSecurityNative.cf.CFStringGetLength(value).toLong()
        check(length in 0..8192)
        val bytes = ByteArray(length.toInt() * 4 + 1)
        check(MacSecurityNative.cf.CFStringGetCString(value, bytes, NativeLong(bytes.size.toLong()),
            0x08000100) != 0.toByte())
        return nulString(bytes)
    }

    private fun nulString(bytes: ByteArray): String {
        val length = bytes.indexOf(0)
        check(length >= 0)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    }

    private fun refused(code: SelfOwnedInstalledIdentityRefusalV2): SelfOwnedInstalledIdentityResultV2.Refused =
        SelfOwnedInstalledIdentityResultV2.Refused(code)

    private class UnsupportedLauncher : IllegalStateException()
    private class References : AutoCloseable {
        private val values = mutableListOf<Pointer>()
        fun retainCopied(value: Pointer): Pointer { values += value; return value }
        fun copy(operation: (PointerByReference) -> Int): Pointer {
            val output = PointerByReference()
            val status = operation(output)
            val result = output.value
            if (result != null) values += result
            check(status == 0 && result != null) { "Native signing observation failed ($status)" }
            return result
        }
        fun string(text: String): Pointer {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return retainCopied(checkNotNull(MacSecurityNative.cf.CFStringCreateWithBytes(null, bytes,
                NativeLong(bytes.size.toLong()), 0x08000100, 0)))
        }
        override fun close() { values.asReversed().forEach { MacSecurityNative.cf.CFRelease(it) } }
    }
}

internal interface MacProcessPath : Library {
    fun proc_pidpath(pid: Int, buffer: ByteArray, size: Int): Int
}
