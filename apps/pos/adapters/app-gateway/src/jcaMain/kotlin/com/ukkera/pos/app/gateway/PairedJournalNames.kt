package com.ukkera.pos.app.gateway

/** Exact native-approved profile selects only a fixed container; LIVE names stay unchanged. */
internal fun pairedSuffix(profile: com.ukkera.pos.security.LocalCompositionSdkProfileV1): String = when (profile) {
    com.ukkera.pos.security.LocalMasterSdkProfileV1 -> ""
    is com.ukkera.pos.security.LocalTrainingSdkProfileV1 ->
        "-" + profile.slot.name.lowercase().replace('_', '-')
    else -> error("Unsupported paired journal composition profile")
}
internal fun pairedRoot(live: String, profile: com.ukkera.pos.security.LocalCompositionSdkProfileV1): String {
    check(live.endsWith("-v1"))
    return live.removeSuffix("-v1") + pairedSuffix(profile) + "-v1"
}
internal fun pairedAlias(live: String, profile: com.ukkera.pos.security.LocalCompositionSdkProfileV1): String {
    check(live.endsWith(".v1/"))
    return live.removeSuffix(".v1/") + pairedSuffix(profile).replace('-', '.') + ".v1/"
}
