package com.ukkera.pos.sales.store

import com.ukkera.pos.security.LocalCompositionSdkProfileV1
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalSetupCompositionMode
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.LocalTrainingSdkProfileV1

/**
 * Native physical-recording namespace bound to one already-created setup composition.
 *
 * LIVE keeps the existing `physical-journal-v1` directory and `ukkera.local.v1` Keystore alias
 * prefix byte-for-byte. TRAINING uses a separate root and alias prefix per bounded training slot
 * so a training spool/catalogue/journal cannot be opened through, or published into, a live
 * composition (or the other training slot). The installation/catalogue/allocation IDs still
 * bind every receipt to the exact original; this namespace only selects the native container.
 */
public class AndroidPhysicalCompositionNamespaceV1 private constructor(
    public val mode: LocalSetupCompositionMode,
    public val slot: LocalTrainingCompositionSlot?,
    internal val journalDirectoryName: String,
    internal val keyAliasPrefix: String,
) {
    init {
        require((mode == LocalSetupCompositionMode.LIVE) == (slot == null))
        require(journalDirectoryName.matches(Regex("[a-z0-9][a-z0-9.-]{0,63}")))
        require(keyAliasPrefix.matches(Regex("[a-z0-9][a-z0-9.-]{0,63}")))
    }

    override fun toString(): String =
        "AndroidPhysicalCompositionNamespaceV1(${mode.name}${slot?.let { ":${it.name}" } ?: ""})"

    public companion object {
        public val LIVE: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1(
            mode = LocalSetupCompositionMode.LIVE,
            slot = null,
            journalDirectoryName = "physical-journal-v1",
            keyAliasPrefix = "ukkera.local.v1",
        )

        public fun training(slot: LocalTrainingCompositionSlot): AndroidPhysicalCompositionNamespaceV1 {
            // Must remain the exact training profile keystore prefix: the physical spool reuses
            // that already-created AES key and never generates/selects an alias.
            val suffix = slot.name.lowercase().replace('_', '-')
            return AndroidPhysicalCompositionNamespaceV1(
                mode = LocalSetupCompositionMode.TRAINING,
                slot = slot,
                journalDirectoryName = "physical-journal-$suffix-v1",
                keyAliasPrefix = "ukkera.local.$suffix.v1",
            )
        }

        /** Reject an unknown future profile instead of silently falling back to LIVE. */
        public fun of(profile: LocalCompositionSdkProfileV1): AndroidPhysicalCompositionNamespaceV1 = when (profile) {
            LocalMasterSdkProfileV1 -> LIVE
            is LocalTrainingSdkProfileV1 -> training(profile.slot)
            else -> error("Unsupported physical composition profile")
        }
    }
}
