package com.ukkera.pos.desktop

import com.sun.jna.Platform
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.WindowsInstalledApplicationIdentity
import java.io.File

/**
 * Bounded packaging check: compares the running process's actual OS package identity with the
 * identity this build declares. Output is DATA for diagnostics; it opens no key, storage or service.
 */
internal object DesktopIdentityProbe {
    const val ARGUMENT = "--identity-probe="

    /** Exit code 0 only when the native observation matches the declared identity. */
    fun run(output: File): Int {
        val (code, line) = when {
            Platform.isWindows() -> windows()
            Platform.isMac() -> mac()
            else -> 2 to "UNSUPPORTED_PLATFORM"
        }
        output.writeText(line + "\n")
        return code
    }

    const val CLIENT_JOURNAL_ARGUMENT = "--client-journal-probe="

    /** Test-runner check of desktop journal custody (synthetic probe namespace only). */
    fun runClientJournal(output: File): Int {
        val line = if (!Platform.isWindows()) "UNSUPPORTED_PLATFORM" else
            com.ukkera.pos.app.gateway.DesktopClientJournalProbe.run(
                com.ukkera.pos.app.gateway.DesktopClientJournalStorages.windows(DesktopWindowsPackageIdentity.TEST.target()))
        output.writeText(line + "\n")
        return if (line.startsWith("DATA ")) 0 else 1
    }

    const val CLIENT_TARGET_ARGUMENT = "--client-target-probe="

    /**
     * Test-runner check of the in-app Client target custody in the isolated TRAINING_2 slot with a
     * synthetic scope: prepare, close, reopen existing-only and compare. Never touches LIVE.
     */
    fun runClientTarget(output: File): Int {
        val (code, line) = if (!Platform.isWindows()) 2 to "UNSUPPORTED_PLATFORM" else runCatching {
            val expected = DesktopWindowsPackageIdentity.TEST.target()
            val slot = com.ukkera.pos.security.LocalTrainingCompositionSlot.TRAINING_2
            val synthetic = com.ukkera.pos.types.DomainId.random()
            val scope = com.ukkera.pos.security.BranchScope(com.ukkera.pos.types.DomainId.random(),
                com.ukkera.pos.types.DomainId.random(), synthetic)
            val first = com.ukkera.pos.security.DesktopClientTargetHandle.prepareNewWindows(expected, slot, scope, setOf("probe"))
            val reference = first.original(); val descriptor = first.descriptor().copyWireBytes()
            first.closeNow()
            val reopened = com.ukkera.pos.security.DesktopClientTargetHandle.openExistingWindows(expected, slot)
            val same = reopened.original() == reference && reopened.descriptor().copyWireBytes().contentEquals(descriptor)
            reopened.closeNow()
            if (same) 0 to "DATA phase=${reference.phase} reopened=true" else 1 to "MISMATCH phase=${reference.phase}"
        }.getOrElse { failure -> 1 to "FAILED ${failure::class.simpleName}: ${failure.message?.take(200)}" }
        output.writeText(line + "\n")
        return code
    }

    /** macOS: Apple-anchored Developer ID signature with the Team ID supplied by the signing runner. */
    private fun mac(): Pair<Int, String> {
        val team = System.getenv("UKKERA_EXPECTED_TEAM_ID") ?: return 2 to "NO_EXPECTED_TEAM"
        return when (val result = com.ukkera.pos.security.MacInstalledApplicationIdentity.observeUi(MAC_SIGNING_IDENTIFIER, team)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> 0 to "DATA id=${result.value.signingIdentifier} team=${result.value.teamIdentifier} " +
                "dr=${result.value.designatedRequirementSha256}"
            is SelfOwnedInstalledIdentityResultV2.Refused -> 1 to "REFUSED ${result.code}"
        }
    }

    /** Must equal the bundle ID declared in the desktop Gradle macOS distribution. */
    const val MAC_SIGNING_IDENTIFIER = "com.ukkera.pos.local.desktop"

    private fun windows(): Pair<Int, String> {
        val expected = DesktopWindowsPackageIdentity.TEST.target()
        return when (val result = WindowsInstalledApplicationIdentity.captureUi(expected)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> {
                val observed = result.value
                0 to "DATA pfn=${observed.target.packageFamilyName} aumid=${observed.target.applicationUserModelId} " +
                    "appContainer=${observed.isAppContainer}"
            }
            is SelfOwnedInstalledIdentityResultV2.Refused -> 1 to "REFUSED ${result.code}"
        }
    }
}
