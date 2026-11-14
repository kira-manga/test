package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Version selection for the shared original owner. The native platform alone selects it: Android
 * is always the unchanged V1 formats and Windows/macOS are always the additive V2 formats. There
 * is no default, negotiation or fallback between versions; iOS and any mismatched facts fail closed.
 */
internal enum class SelfOwnedOriginalFormat {
    V1, V2;

    companion object {
        fun of(target: SelfOwnedInstalledTargetBindingV1): SelfOwnedOriginalFormat = when (target.platform) {
            SelfOwnedInstalledTargetPlatformV1.ANDROID ->
                if (target is SelfOwnedAndroidInstalledTargetBindingV1) V1 else unsupported()
            SelfOwnedInstalledTargetPlatformV1.WINDOWS, SelfOwnedInstalledTargetPlatformV1.MACOS ->
                if (target is SelfOwnedNativeInstalledTargetBindingV1) V2 else unsupported()
            SelfOwnedInstalledTargetPlatformV1.IOS -> unsupported()
        }

        private fun unsupported(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }
}

/**
 * Pure consistency check of one captured environment: the installed target, OS boot mark and
 * process principal must all name the same platform, otherwise UNSUPPORTED_PLATFORM.
 */
internal fun selfOwnedOriginalFormat(target: SelfOwnedInstalledTargetBindingV1, bootMark: SelfOwnedBootMark,
    principal: SelfOwnedProcessPrincipal): SelfOwnedOriginalFormat {
    val format = SelfOwnedOriginalFormat.of(target)
    val consistent = when (target.platform) {
        SelfOwnedInstalledTargetPlatformV1.ANDROID ->
            bootMark is SelfOwnedBootMark.AndroidCount && principal is SelfOwnedProcessPrincipal.AndroidUid
        SelfOwnedInstalledTargetPlatformV1.WINDOWS -> bootMark is SelfOwnedBootMark.Session &&
            bootMark.ledger.bootSession is SelfOwnedNativeBootSessionV2.Windows && principal is SelfOwnedProcessPrincipal.WindowsSid
        SelfOwnedInstalledTargetPlatformV1.MACOS -> bootMark is SelfOwnedBootMark.Session &&
            bootMark.ledger.bootSession is SelfOwnedNativeBootSessionV2.Mac && principal is SelfOwnedProcessPrincipal.MacUid
        SelfOwnedInstalledTargetPlatformV1.IOS -> false
    }
    if (!consistent) selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    return format
}

/** The checked format of the captured environment (see [selfOwnedOriginalFormat]). */
internal fun SelfOwnedOriginalEnvironment.originalFormat(): SelfOwnedOriginalFormat =
    selfOwnedOriginalFormat(installedTarget, bootMark, principal)

/** Android V1 target only; any other platform is UNSUPPORTED_PLATFORM, never a fabricated descriptor. */
internal fun SelfOwnedOriginalEnvironment.androidTargetV1(): SelfOwnedTargetDescriptorV1 {
    if (originalFormat() != SelfOwnedOriginalFormat.V1) selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    return installedTarget.androidTarget ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
}

/**
 * OS boot observation. Android keeps its integer boot count; desktop carries the hash-chained
 * boot-ordinal ledger whose session is the actual OS boot-session UUID. [ordinal] is only for
 * throttle bookkeeping and same-boot ordering; it is never substituted for a session comparison.
 */
internal sealed interface SelfOwnedBootMark {
    val ordinal: Int
    fun sameBoot(other: SelfOwnedBootMark): Boolean
    fun bootId(installationId: DomainId): DomainId

    data class AndroidCount(val count: Int) : SelfOwnedBootMark {
        init { require(count >= 0) }
        override val ordinal: Int get() = count
        override fun sameBoot(other: SelfOwnedBootMark): Boolean = other is AndroidCount && other.count == count
        override fun bootId(installationId: DomainId): DomainId = AndroidSelfOwnedLocalSecurity.androidBootId(installationId, count)
        override fun toString(): String = "SelfOwnedBootMark.AndroidCount($count)"
    }

    data class Session(val ledger: SelfOwnedBootOrdinalLedgerV2) : SelfOwnedBootMark {
        val session: SelfOwnedNativeBootSessionV2 get() = ledger.bootSession
        override val ordinal: Int get() = ledger.ordinal
        override fun sameBoot(other: SelfOwnedBootMark): Boolean = other is Session && other.ledger == ledger
        override fun bootId(installationId: DomainId): DomainId =
            DomainId(desktopSessionBootId(installationId, ledger.bootSessionId).toString())
        override fun toString(): String = "SelfOwnedBootMark.Session([REDACTED])"
    }

    companion object {
        /** UUIDv8 over `ukkera.desktop-boot-session/v2 NUL installationId NUL bootSessionUuid`. */
        fun desktopSessionBootId(installationId: DomainId, bootSessionId: String): UUID {
            require(SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(bootSessionId))
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("ukkera.desktop-boot-session/v2\u0000${installationId.value}\u0000$bootSessionId".encodeToByteArray())
            digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
            digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(digest)
            return UUID(buffer.long, buffer.long).also { digest.fill(0) }
        }
    }
}

/** The actual OS principal of the running process. Comparisons are exact and same-variant only. */
internal sealed interface SelfOwnedProcessPrincipal {
    data class AndroidUid(val uid: Int) : SelfOwnedProcessPrincipal {
        init { require(uid >= 0) }
        override fun toString(): String = "SelfOwnedProcessPrincipal.AndroidUid([REDACTED])"
    }
    data class WindowsSid(val sid: String) : SelfOwnedProcessPrincipal {
        init { require(SelfOwnedInstalledIdentityGrammarV2.accountSid(sid)) }
        override fun toString(): String = "SelfOwnedProcessPrincipal.WindowsSid([REDACTED])"
    }
    data class MacUid(val uid: Int) : SelfOwnedProcessPrincipal {
        init { require(uid >= 0) }
        override fun toString(): String = "SelfOwnedProcessPrincipal.MacUid([REDACTED])"
    }
}

/** Actual original target namespace identity: Android V1 or Windows/macOS V2. */
internal sealed interface SelfOwnedOriginalNamespaceIdentity {
    val format: SelfOwnedOriginalFormat
    data class V1(val value: SelfOwnedNativeNamespaceV1) : SelfOwnedOriginalNamespaceIdentity {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
        override fun toString(): String = "SelfOwnedOriginalNamespaceIdentity.V1([REDACTED])"
    }
    data class V2(val value: SelfOwnedNativeNamespaceV2) : SelfOwnedOriginalNamespaceIdentity {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
        override fun toString(): String = "SelfOwnedOriginalNamespaceIdentity.V2([REDACTED])"
    }
}

/** The exact P1-retained original source (root, delegation and preparation grant). */
internal sealed interface SelfOwnedOriginalSource {
    val format: SelfOwnedOriginalFormat
    val coordinates: SelfOwnedAllocationCoordinatesV1
    val root: SelfOwnedSignedDocumentV1
    val rootBody: SelfOwnedRootBodyV1
    val branchName: String
    val protection: SelfOwnedKeyProtectionV1
    /** Same exact documents and target; always false across versions. */
    fun same(other: SelfOwnedOriginalSource): Boolean
    /** The source names exactly the environment's installed target. */
    fun namesTarget(environment: SelfOwnedOriginalEnvironment): Boolean

    class V1(val value: SelfOwnedNativeSourceV1) : SelfOwnedOriginalSource {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
        override val coordinates: SelfOwnedAllocationCoordinatesV1 get() = value.preparationBody.coordinates
        override val root: SelfOwnedSignedDocumentV1 get() = value.root
        override val rootBody: SelfOwnedRootBodyV1 get() = value.rootBody
        override val branchName: String get() = value.preparationBody.branchName
        override val protection: SelfOwnedKeyProtectionV1 get() = value.preparationBody.protection
        override fun same(other: SelfOwnedOriginalSource): Boolean = other is V1 && value.same(other.value)
        override fun namesTarget(environment: SelfOwnedOriginalEnvironment): Boolean =
            environment.installedTarget.androidTarget?.let { value.preparationBody.target.same(it) } == true
        override fun toString(): String = "SelfOwnedOriginalSource.V1([REDACTED])"
    }

    class V2(val value: SelfOwnedNativeSourceV2) : SelfOwnedOriginalSource {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
        override val coordinates: SelfOwnedAllocationCoordinatesV1 get() = value.coordinates
        override val root: SelfOwnedSignedDocumentV1 get() = value.root
        override val rootBody: SelfOwnedRootBodyV1 get() = value.rootBody
        override val branchName: String get() = value.preparationBody.branchName
        override val protection: SelfOwnedKeyProtectionV1 get() = value.protection
        override fun same(other: SelfOwnedOriginalSource): Boolean = other is V2 && value.same(other.value)
        override fun namesTarget(environment: SelfOwnedOriginalEnvironment): Boolean =
            value.installedTarget.matches(environment.installedTarget)
        override fun toString(): String = "SelfOwnedOriginalSource.V2([REDACTED])"
    }
}

/**
 * Invocation-local original PREPARATION approval (see [SelfOwnedNativeApproval] and
 * [SelfOwnedNativeApprovalV2]). [owner] is compared by identity and [bootMark] with the actual
 * current boot. It is never a transferable token or accepting callback.
 */
internal class SelfOwnedOriginalApproval(
    val owner: Any,
    val source: SelfOwnedOriginalSource,
    val bootMark: SelfOwnedBootMark,
    val expiresElapsedMillis: Long,
) {
    init {
        require(expiresElapsedMillis > 0)
        require(when (source) {
            is SelfOwnedOriginalSource.V1 -> bootMark is SelfOwnedBootMark.AndroidCount
            is SelfOwnedOriginalSource.V2 -> bootMark is SelfOwnedBootMark.Session &&
                bootMark.session.platform == source.value.platform
        })
    }

    /** The unchanged Android V1 approval; any other version is UNSUPPORTED_PLATFORM. */
    fun v1(): SelfOwnedNativeApproval {
        val v1 = source as? SelfOwnedOriginalSource.V1 ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        val boot = bootMark as SelfOwnedBootMark.AndroidCount
        return SelfOwnedNativeApproval(owner, v1.value, boot.count, expiresElapsedMillis)
    }

    fun v2(): SelfOwnedNativeApprovalV2 {
        val v2 = source as? SelfOwnedOriginalSource.V2 ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        val boot = bootMark as SelfOwnedBootMark.Session
        return SelfOwnedNativeApprovalV2(owner, v2.value, boot.session, expiresElapsedMillis)
    }

    /** Same owner, same current boot, unexpired, and naming exactly the environment's target. */
    fun current(ownerToken: Any, environment: SelfOwnedOriginalEnvironment, now: () -> Long): Boolean =
        owner === ownerToken && bootMark.sameBoot(environment.bootMark) && now() < expiresElapsedMillis &&
            source.namesTarget(environment)

    override fun toString(): String = "SelfOwnedOriginalApproval([REDACTED])"
}

/** OS-private prior target intent, Android V1 or Windows/macOS V2, wrapping the exact instance. */
internal sealed interface SelfOwnedOriginalIntent {
    val format: SelfOwnedOriginalFormat
    val revision: Long
    val bindingStarted: Boolean
    val source: SelfOwnedOriginalSource
    val namespace: SelfOwnedOriginalNamespaceIdentity
    fun complete(): Boolean
    fun encode(): ByteArray
    fun stateAad(): ByteArray
    fun withBindingStarted(): SelfOwnedOriginalIntent

    class V1(val value: SelfOwnedNativeIntentV1) : SelfOwnedOriginalIntent {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
        override val revision: Long get() = value.revision
        override val bindingStarted: Boolean get() = value.bindingStarted
        override val source: SelfOwnedOriginalSource get() = SelfOwnedOriginalSource.V1(value.source)
        override val namespace: SelfOwnedOriginalNamespaceIdentity get() = SelfOwnedOriginalNamespaceIdentity.V1(value.namespace)
        override fun complete(): Boolean = value.complete()
        override fun encode(): ByteArray = SelfOwnedNativeInstallationWireV1.encodeIntent(value)
        override fun stateAad(): ByteArray = SelfOwnedNativeInstallationWireV1.stateAad(value)
        override fun withBindingStarted(): SelfOwnedOriginalIntent =
            V1(value.copy(revision = value.revision + 1L, bindingStarted = true))
        override fun toString(): String = "SelfOwnedOriginalIntent.V1([REDACTED])"
    }

    class V2(val value: SelfOwnedNativeIntentV2) : SelfOwnedOriginalIntent {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
        override val revision: Long get() = value.revision
        override val bindingStarted: Boolean get() = value.bindingStarted
        override val source: SelfOwnedOriginalSource get() = SelfOwnedOriginalSource.V2(value.source)
        override val namespace: SelfOwnedOriginalNamespaceIdentity get() = SelfOwnedOriginalNamespaceIdentity.V2(value.namespace)
        override fun complete(): Boolean = value.complete()
        override fun encode(): ByteArray = SelfOwnedNativeInstallationWireV2.encodeIntent(value)
        override fun stateAad(): ByteArray = SelfOwnedNativeInstallationWireV2.stateAad(value)
        override fun withBindingStarted(): SelfOwnedOriginalIntent =
            V2(value.copy(revision = value.revision + 1L, bindingStarted = true))
        override fun toString(): String = "SelfOwnedOriginalIntent.V2([REDACTED])"
    }

    companion object {
        /**
         * Decodes only [format], never re-tried as another version. V1 keeps its unchanged decoder
         * (and its refusal); refused V2 bytes, including any V1 bytes, are STATE_UNCERTAIN.
         */
        fun decode(format: SelfOwnedOriginalFormat, bytes: ByteArray): SelfOwnedOriginalIntent = when (format) {
            SelfOwnedOriginalFormat.V1 -> V1(SelfOwnedNativeInstallationWireV1.decodeIntent(bytes))
            SelfOwnedOriginalFormat.V2 -> V2(selfOwnedKnownData(SelfOwnedNativeInstallationWireV2.decodeIntent(bytes)))
        }
    }
}

/** Authenticated original journal state wrapping the exact V1/V2 value. */
internal sealed interface SelfOwnedOriginalJournalState {
    val format: SelfOwnedOriginalFormat
    val revision: Long
    val newCeremoniesAllowed: Boolean
    val originalBinding: SelfOwnedNativeOriginalBinding
    fun withdrawNewCeremonies(): SelfOwnedOriginalJournalState
    fun encode(): ByteArray

    data class V1(val value: SelfOwnedNativeJournalStateV1) : SelfOwnedOriginalJournalState {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
        override val revision: Long get() = value.revision
        override val newCeremoniesAllowed: Boolean get() = value.newCeremoniesAllowed
        override val originalBinding: SelfOwnedNativeBindingV1 get() = value.originalBinding
        override fun withdrawNewCeremonies(): SelfOwnedOriginalJournalState = V1(value.withdrawNewCeremonies())
        override fun encode(): ByteArray = SelfOwnedNativeInstallationWireV1.encodeState(value)
        override fun toString(): String = "SelfOwnedOriginalJournalState.V1([REDACTED])"
    }

    data class V2(val value: SelfOwnedNativeJournalStateV2) : SelfOwnedOriginalJournalState {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
        override val revision: Long get() = value.revision
        override val newCeremoniesAllowed: Boolean get() = value.newCeremoniesAllowed
        override val originalBinding: SelfOwnedNativeBindingV2 get() = value.originalBinding
        override fun withdrawNewCeremonies(): SelfOwnedOriginalJournalState = V2(value.withdrawNewCeremonies())
        override fun encode(): ByteArray = SelfOwnedNativeInstallationWireV2.encodeState(value)
        override fun toString(): String = "SelfOwnedOriginalJournalState.V2([REDACTED])"
    }

    companion object {
        /** The first state for a freshly created binding; its type selects the version. */
        fun initial(binding: SelfOwnedNativeOriginalBinding): SelfOwnedOriginalJournalState = when (binding) {
            is SelfOwnedNativeBindingV1 -> V1(SelfOwnedNativeJournalStateV1(0L, binding, true))
            is SelfOwnedNativeBindingV2 -> V2(SelfOwnedNativeJournalStateV2(0L, binding, true))
        }

        fun decode(format: SelfOwnedOriginalFormat, bytes: ByteArray): SelfOwnedOriginalJournalState = when (format) {
            SelfOwnedOriginalFormat.V1 -> V1(SelfOwnedNativeInstallationWireV1.decodeState(bytes))
            SelfOwnedOriginalFormat.V2 -> V2(selfOwnedKnownData(SelfOwnedNativeInstallationWireV2.decodeState(bytes)))
        }
    }
}

/** Signed encrypted journal-state envelope (Android V1 registry-shaped, or V2 journal envelope). */
internal sealed interface SelfOwnedOriginalJournalEnvelope {
    val nonce: ByteArray
    val ciphertext: ByteArray
    val signature: ByteArray
    fun encode(): ByteArray
    fun clear()

    class V1(val value: SelfOwnedRegistryEnvelopeV1) : SelfOwnedOriginalJournalEnvelope {
        override val nonce: ByteArray get() = value.nonce
        override val ciphertext: ByteArray get() = value.ciphertext
        override val signature: ByteArray get() = value.signature
        override fun encode(): ByteArray = SelfOwnedNativeInstallationWireV1.encodeEnvelope(value)
        override fun clear() = value.clear()
        override fun toString(): String = "SelfOwnedOriginalJournalEnvelope.V1([REDACTED])"
    }

    class V2(val value: SelfOwnedNativeJournalEnvelopeV2) : SelfOwnedOriginalJournalEnvelope {
        override val nonce: ByteArray get() = value.nonce
        override val ciphertext: ByteArray get() = value.ciphertext
        override val signature: ByteArray get() = value.signature
        override fun encode(): ByteArray = SelfOwnedNativeInstallationWireV2.encodeEnvelope(value)
        override fun clear() = value.clear()
        override fun toString(): String = "SelfOwnedOriginalJournalEnvelope.V2([REDACTED])"
    }

    companion object {
        fun of(format: SelfOwnedOriginalFormat, nonce: ByteArray, ciphertext: ByteArray, signature: ByteArray):
            SelfOwnedOriginalJournalEnvelope = when (format) {
            SelfOwnedOriginalFormat.V1 -> V1(SelfOwnedRegistryEnvelopeV1(nonce, ciphertext, signature))
            SelfOwnedOriginalFormat.V2 -> V2(SelfOwnedNativeJournalEnvelopeV2(nonce, ciphertext, signature))
        }

        fun decode(format: SelfOwnedOriginalFormat, bytes: ByteArray): SelfOwnedOriginalJournalEnvelope = when (format) {
            SelfOwnedOriginalFormat.V1 -> V1(SelfOwnedNativeInstallationWireV1.decodeEnvelope(bytes))
            SelfOwnedOriginalFormat.V2 -> V2(selfOwnedKnownData(SelfOwnedNativeInstallationWireV2.decodeEnvelope(bytes)))
        }
    }
}

/** Retained provisioner bootstrap (Android V1 or Windows/macOS V2). */
internal sealed interface SelfOwnedOriginalBootstrap {
    val format: SelfOwnedOriginalFormat
    fun complete(): Boolean
    val enrollmentStarted: Boolean

    class V1(val value: SelfOwnedBootstrapV1) : SelfOwnedOriginalBootstrap {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
        override fun complete(): Boolean = value.complete()
        override val enrollmentStarted: Boolean get() = value.enrollmentStarted
        override fun toString(): String = "SelfOwnedOriginalBootstrap.V1([REDACTED])"
    }

    class V2(val value: SelfOwnedBootstrapV2) : SelfOwnedOriginalBootstrap {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
        override fun complete(): Boolean = value.complete()
        override val enrollmentStarted: Boolean get() = value.enrollmentStarted
        override fun toString(): String = "SelfOwnedOriginalBootstrap.V2([REDACTED])"
    }
}

/** Authenticated provisioner registry state (Android V1 or Windows/macOS V2). */
internal sealed interface SelfOwnedOriginalRegistryState {
    val format: SelfOwnedOriginalFormat

    /**
     * The single P1-retained original preparation source. No single allocation or a non-retained
     * grant is ORIGINAL_ACTION_REQUIRED; a retained phase without its grant is STATE_UNCERTAIN.
     */
    fun originalPreparationSource(bootstrap: SelfOwnedOriginalBootstrap): SelfOwnedOriginalSource

    class V1(val value: LocalProvisionerRegistryStateV1) : SelfOwnedOriginalRegistryState {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V1
        override fun originalPreparationSource(bootstrap: SelfOwnedOriginalBootstrap): SelfOwnedOriginalSource {
            if (bootstrap !is SelfOwnedOriginalBootstrap.V1) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val allocation = value.allocations.singleOrNull()
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            if (allocation.phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            val grant = allocation.grant ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return SelfOwnedOriginalSource.V1(SelfOwnedNativeSourceV1(value.root, value.delegation, grant))
        }
        override fun toString(): String = "SelfOwnedOriginalRegistryState.V1([REDACTED])"
    }

    class V2(val value: LocalProvisionerRegistryStateV2) : SelfOwnedOriginalRegistryState {
        override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
        override fun originalPreparationSource(bootstrap: SelfOwnedOriginalBootstrap): SelfOwnedOriginalSource {
            if (bootstrap !is SelfOwnedOriginalBootstrap.V2) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val allocation = value.allocations.singleOrNull()
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            if (allocation.phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            val grant = allocation.grant ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val source = try {
                SelfOwnedNativeSourceV2(SelfOwnedPreparationPacketV2(grant.preparation.coordinates,
                    bootstrap.value.installedTarget, value.root, value.delegation, grant))
            } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
            return SelfOwnedOriginalSource.V2(source)
        }
        override fun toString(): String = "SelfOwnedOriginalRegistryState.V2([REDACTED])"
    }
}

private fun <T> selfOwnedKnownData(result: SelfOwnedInstalledIdentityResultV2<T>): T = when (result) {
    is SelfOwnedInstalledIdentityResultV2.Data -> result.value
    is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}
