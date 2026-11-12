package com.ukkera.pos.security

/**
 * Native composition profile. LIVE values are the existing production namespaces unchanged.
 * TRAINING values are distinct versioned namespaces so no directory, key alias, launch/intent
 * record or SDK identity is shared with live. A slot is a bounded training composition choice,
 * never a caller-supplied path or an in-place mode flag.
 */
internal data class SelfOwnedNativeProfileV1(
    val mode: LocalSetupCompositionMode,
    val slot: LocalTrainingCompositionSlot?,
    val targetDirectoryName: String,
    val provisionerDirectoryName: String,
    val launchDirectoryName: String,
    val intentNamespaceSuffix: String,
    val p1AliasPrefix: String,
    val p2SigningPrefix: String,
    val p2DataPrefix: String,
    val keystoreAliasPrefix: String,
    val sdkProfile: LocalCompositionSdkProfileV1,
) {
    val training: Boolean get() = mode == LocalSetupCompositionMode.TRAINING

    companion object {
        val LIVE: SelfOwnedNativeProfileV1 = SelfOwnedNativeProfileV1(
            mode = LocalSetupCompositionMode.LIVE,
            slot = null,
            targetDirectoryName = SelfOwnedCompositionNames.LIVE_TARGET_DIRECTORY,
            provisionerDirectoryName = SelfOwnedCompositionNames.LIVE_PROVISIONER_DIRECTORY,
            launchDirectoryName = "ukkera.local-master-launch.v1",
            intentNamespaceSuffix = "",
            p1AliasPrefix = "ukkera.self-owned.p1.v1.",
            p2SigningPrefix = "ukkera.self-owned.p2.v1.",
            p2DataPrefix = "ukkera.local.v1/",
            keystoreAliasPrefix = "ukkera.local.v1",
            sdkProfile = LocalMasterSdkProfileV1,
        )

        fun training(slot: LocalTrainingCompositionSlot): SelfOwnedNativeProfileV1 {
            val suffix = slot.name.lowercase().replace('_', '-')
            return SelfOwnedNativeProfileV1(
                mode = LocalSetupCompositionMode.TRAINING,
                slot = slot,
                targetDirectoryName = SelfOwnedCompositionNames.trainingTargetDirectory(slot),
                provisionerDirectoryName = SelfOwnedCompositionNames.trainingProvisionerDirectory(slot),
                launchDirectoryName = "ukkera.local-master-launch.$suffix.v1",
                intentNamespaceSuffix = ".$suffix.v1",
                p1AliasPrefix = "ukkera.self-owned.p1.$suffix.v1.",
                p2SigningPrefix = "ukkera.self-owned.p2.$suffix.v1.",
                p2DataPrefix = "ukkera.local.$suffix.v1/",
                keystoreAliasPrefix = "ukkera.local.$suffix.v1",
                sdkProfile = LocalTrainingSdkProfileV1(slot),
            )
        }
    }
}

/** Public fixed-name accessors for platform composition adapters; no caller-supplied path. */
public object AndroidTrainingSetupNamespaces {
    public fun launchDirectoryName(slot: LocalTrainingCompositionSlot): String =
        SelfOwnedNativeProfileV1.training(slot).launchDirectoryName

    public fun intentStorageSuffix(slot: LocalTrainingCompositionSlot): String =
        SelfOwnedNativeProfileV1.training(slot).intentNamespaceSuffix
}
