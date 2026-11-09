package com.ukkera.pos.security

/** Fixed native directory names shared by profile creation and archive-path validation. */
internal object SelfOwnedCompositionNames {
    const val LIVE_TARGET_DIRECTORY: String = "ukkera-self-owned-target-v1"
    const val LIVE_PROVISIONER_DIRECTORY: String = "ukkera-self-owned-provisioner-v1"
    const val LIVE_DEVICE_PAIRING_TARGET_DIRECTORY: String = "ukkera-device-pairing-target-v1"
    const val LIVE_DEVICE_PAIRING_ALIAS_PREFIX: String = "ukkera.device-pairing.target.v1."

    fun trainingSuffix(slot: LocalTrainingCompositionSlot): String =
        slot.name.lowercase().replace('_', '-')

    fun trainingTargetDirectory(slot: LocalTrainingCompositionSlot): String =
        "$LIVE_TARGET_DIRECTORY-${trainingSuffix(slot)}"

    fun trainingProvisionerDirectory(slot: LocalTrainingCompositionSlot): String =
        "$LIVE_PROVISIONER_DIRECTORY-${trainingSuffix(slot)}"

    /** Fresh physical target namespace, disjoint from the original Master target/provisioner. */
    fun devicePairingTargetDirectory(slot: LocalTrainingCompositionSlot?): String =
        if (slot == null) LIVE_DEVICE_PAIRING_TARGET_DIRECTORY
        else "$LIVE_DEVICE_PAIRING_TARGET_DIRECTORY-${trainingSuffix(slot)}"

    fun devicePairingAliasPrefix(slot: LocalTrainingCompositionSlot?): String =
        if (slot == null) LIVE_DEVICE_PAIRING_ALIAS_PREFIX
        else "ukkera.device-pairing.target.${trainingSuffix(slot)}.v1."

    fun isDevicePairingTargetPath(path: String): Boolean =
        path.endsWith("/$LIVE_DEVICE_PAIRING_TARGET_DIRECTORY") ||
            LocalTrainingCompositionSlot.entries.any {
                path.endsWith("/$LIVE_DEVICE_PAIRING_TARGET_DIRECTORY-${trainingSuffix(it)}")
            }

    fun isTargetPath(path: String): Boolean =
        path.endsWith("/$LIVE_TARGET_DIRECTORY") ||
            LocalTrainingCompositionSlot.entries.any { path.endsWith("/${trainingTargetDirectory(it)}") }

    fun isProvisionerPath(path: String): Boolean =
        path.endsWith("/$LIVE_PROVISIONER_DIRECTORY") ||
            LocalTrainingCompositionSlot.entries.any { path.endsWith("/${trainingProvisionerDirectory(it)}") }
}
