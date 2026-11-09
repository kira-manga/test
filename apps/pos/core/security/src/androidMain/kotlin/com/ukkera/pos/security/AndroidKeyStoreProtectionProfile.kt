package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Explicit native-key policy, not master appointment, installer authorization or service custody.
 * ANDROID_KEYSTORE deliberately permits software-backed AndroidKeyStore keys. Choosing that
 * profile is NOT an implicit fallback from either of the hardware-required profiles.
 */
internal enum class AndroidKeyStoreProtectionRequirement {
    ANDROID_KEYSTORE,
    SECURE_HARDWARE,
    STRONGBOX,
}

/** What the public KeyInfo API actually reported; never infer StrongBox from a hardware boolean. */
internal enum class AndroidKeyStoreObservedSecurity {
    SOFTWARE,
    /** API 24..30 can establish hardware backing, but not its exact kind through KeyInfo. */
    HARDWARE_KIND_UNSPECIFIED,
    TRUSTED_ENVIRONMENT,
    STRONGBOX,
}

/**
 * Selected by trusted installation configuration, never from a stored ciphertext or RPC body.
 *
 * This version deliberately supports only service keys WITHOUT per-operation user authentication,
 * user-presence or a time-limited key validity policy. The owner must explicitly approve that
 * deployment policy and the future supervisor must enforce the chosen device-unlock/storage
 * lifecycle. This object does not prove any of those external facts.
 *
 * No raw alias is accepted: the two namespaces and configured key IDs fix the only usable keys.
 */
internal data class AndroidKeyStoreConfiguration(
    val installationId: DomainId,
    val scope: BranchScope,
    val storageReference: ProtectedStorageKeyReference,
    val pepperReference: ProtectedPepperReference,
    val protectionRequirement: AndroidKeyStoreProtectionRequirement,
    val aliasPrefix: String = "ukkera.local.v1",
) {
    init {
        require(storageReference.keyId != pepperReference.keyId) { "Distinct native key identities required" }
    }

    val profile: String get() = "ukkera.android-keystore/service-no-user-auth/v1"
    val storageAlias: String get() = "$aliasPrefix/${installationId.value}/storage/${storageReference.keyId.value}"
    val pepperAlias: String get() = "$aliasPrefix/${installationId.value}/pepper/${pepperReference.keyId.value}"

    override fun toString(): String = "AndroidKeyStoreConfiguration([REDACTED])"
}

/** Native metadata only. The enclosing installation record must be independently protected. */
internal data class AndroidKeyStoreObservedKey(
    val security: AndroidKeyStoreObservedSecurity,
) {
    override fun toString(): String = "AndroidKeyStoreObservedKey([REDACTED])"
}

/**
 * Proposed durable installer output, NOT a self-authenticating receipt. The authorized installer
 * must persist/authenticate this exact configuration and both observed profiles outside ordinary
 * mutable client/backup inputs. openExisting compares them; it never learns a new accepted profile.
 *
 * An API/OS change that changes observable security evidence requires explicit requalification,
 * not silent rewriting of this record. Matching metadata is not cryptographic key-continuity or
 * rollback evidence, and this record contains neither secret key material nor master authority.
 */
internal data class AndroidKeyStoreEnrollmentRecord(
    val configuration: AndroidKeyStoreConfiguration,
    val storage: AndroidKeyStoreObservedKey,
    val pepper: AndroidKeyStoreObservedKey,
) {
    val formatVersion: Int get() = 1
    override fun toString(): String = "AndroidKeyStoreEnrollmentRecord([REDACTED])"
}

internal enum class AndroidKeyStoreFailure {
    BUSY,
    KEY_ALREADY_PRESENT,
    KEY_MISSING_OR_INVALIDATED,
    KEY_LOCKED,
    PROFILE_UNSUPPORTED,
    PROFILE_MISMATCH,
    CORRUPT_CIPHERTEXT,
    PROVIDER_FAILURE,
}

internal sealed interface AndroidKeyStoreAcquisition {
    class Opened(val material: AndroidKeyStoreOpenedMaterial) : AndroidKeyStoreAcquisition {
        override fun toString(): String = "AndroidKeyStoreAcquisition.Opened([REDACTED])"
    }

    data class Unavailable(val reason: AndroidKeyStoreFailure) : AndroidKeyStoreAcquisition
}

internal fun AndroidKeyStoreFailure.protectedFailure(): ProtectedKeyFailure = when (this) {
    AndroidKeyStoreFailure.KEY_LOCKED -> ProtectedKeyFailure.KEY_LOCKED
    AndroidKeyStoreFailure.KEY_ALREADY_PRESENT,
    AndroidKeyStoreFailure.KEY_MISSING_OR_INVALIDATED -> ProtectedKeyFailure.KEY_UNAVAILABLE
    AndroidKeyStoreFailure.PROFILE_UNSUPPORTED,
    AndroidKeyStoreFailure.PROFILE_MISMATCH -> ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED
    AndroidKeyStoreFailure.CORRUPT_CIPHERTEXT -> ProtectedKeyFailure.CORRUPT_PROTECTED_STATE
    AndroidKeyStoreFailure.BUSY,
    AndroidKeyStoreFailure.PROVIDER_FAILURE -> ProtectedKeyFailure.PROVIDER_FAILURE
}
