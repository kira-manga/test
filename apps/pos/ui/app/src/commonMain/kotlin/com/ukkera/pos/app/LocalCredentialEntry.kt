package com.ukkera.pos.app

internal const val LOCAL_CREDENTIAL_ACCOUNT_MAX_LENGTH = 128
internal const val LOCAL_CREDENTIAL_SECRET_MAX_LENGTH = 256
internal const val LOCAL_CREDENTIAL_DISPLAY_NAME_MAX_LENGTH = 120

/** Entry/transport bounds only. Exact identifiers and Master authorization are unchanged. */
internal fun localSignInInputSupported(
    account: String,
    secretSize: Int,
    secretMaxLength: Int = LOCAL_CREDENTIAL_SECRET_MAX_LENGTH,
): Boolean =
    account.isNotBlank() && account.length <= LOCAL_CREDENTIAL_ACCOUNT_MAX_LENGTH &&
        secretSize in 1..secretMaxLength

internal fun localEnrollmentInputSupported(
    account: String,
    displayName: String,
    secretSize: Int,
    supportsDisplayName: Boolean,
): Boolean = localSignInInputSupported(account, secretSize) &&
    if (supportsDisplayName) displayName.isNotBlank() && displayName.length <= LOCAL_CREDENTIAL_DISPLAY_NAME_MAX_LENGTH
    else displayName.isEmpty()
