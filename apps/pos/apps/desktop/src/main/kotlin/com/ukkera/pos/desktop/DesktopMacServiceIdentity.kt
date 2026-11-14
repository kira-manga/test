package com.ukkera.pos.desktop

import com.ukkera.pos.security.SelfOwnedMacServiceIdentityV2

/**
 * Expected macOS (test-only) Master service identity: the launchd-supervised service mode of the
 * SAME signed app, so it shares the app's signing identifier. Expected DATA only; the running
 * process's code signature (MacInstalledApplicationIdentity.captureService) is authoritative.
 *
 * The Team ID and designated-requirement digest come from the owner's Developer ID certificate.
 * They are build inputs (Gradle properties forwarded as the JVM system properties below), never
 * defaults: without them there is no expected identity and service mode must refuse.
 */
internal object DesktopMacServiceIdentity {
    /** Equals the macOS bundle ID declared in the desktop Gradle distribution. */
    const val SIGNING_IDENTIFIER = DesktopIdentityProbe.MAC_SIGNING_IDENTIFIER

    /** Per-user LaunchAgent label; matches the Master data directory name in core:security. */
    const val LAUNCH_AGENT_LABEL = "com.ukkera.pos.master"

    /** Ten-character Apple Team ID of the owner's signing certificate. */
    const val TEAM_IDENTIFIER_PROPERTY = "ukkera.mac.signing.teamIdentifier"

    /** Lowercase SHA-256 of the signed app's designated requirement (from `--identity-probe` output). */
    const val DESIGNATED_REQUIREMENT_SHA256_PROPERTY = "ukkera.mac.signing.designatedRequirementSha256"

    /** The configured expected identity, or a failure naming the missing/invalid build input. */
    fun configured(
        teamIdentifier: String? = System.getProperty(TEAM_IDENTIFIER_PROPERTY),
        designatedRequirementSha256: String? = System.getProperty(DESIGNATED_REQUIREMENT_SHA256_PROPERTY),
    ): Result<SelfOwnedMacServiceIdentityV2> {
        val team = teamIdentifier?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("$TEAM_IDENTIFIER_PROPERTY is not configured"))
        val digest = designatedRequirementSha256?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("$DESIGNATED_REQUIREMENT_SHA256_PROPERTY is not configured"))
        return runCatching { SelfOwnedMacServiceIdentityV2(SIGNING_IDENTIFIER, team, digest) }
            .recoverCatching { throw IllegalStateException("Configured macOS service identity is invalid") }
    }
}
