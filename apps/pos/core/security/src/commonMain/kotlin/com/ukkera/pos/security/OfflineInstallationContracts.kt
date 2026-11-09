package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** Fixed local verification profile, not a root-enrolment or central-registration protocol. */
public object OfflineInstallationFormat {
    public const val MAX_DOCUMENT_BYTES: Int = 32 * 1024
    public const val MAX_EVIDENCE_BYTES: Int = 96 * 1024
    public const val MAX_ROOT_PINS: Int = 8
    public const val MAX_ISSUERS: Int = 16
    public const val MAX_REVOCATIONS_PER_KIND: Int = 128
    public const val SIGNATURE_BYTES: Int = 64
    public const val CHALLENGE_BYTES: Int = 32
    public const val CEREMONY_MILLIS: Long = 120_000
    public const val MAX_UTC_UNCERTAINTY_MILLIS: Long = 30_000
}

/** Whole-interval UTC validity; UTC does NOT order the writer or replace boot/monotonic time. */
@Serializable
public data class OfflineInstallationUtcWindow(
    @Serializable(with = MinorUnitsSerializer::class)
    public val notBeforeEpochMillis: Long,
    @Serializable(with = MinorUnitsSerializer::class)
    public val notAfterEpochMillis: Long,
) {
    init { require(notBeforeEpochMillis >= 0 && notAfterEpochMillis > notBeforeEpochMillis) }
    internal fun contains(sample: OfflineInstallationUtcSample): Boolean =
        sample.earliestEpochMillis >= notBeforeEpochMillis && sample.latestEpochMillis < notAfterEpochMillis
    internal fun contains(other: OfflineInstallationUtcWindow): Boolean =
        other.notBeforeEpochMillis >= notBeforeEpochMillis && other.notAfterEpochMillis <= notAfterEpochMillis
    internal fun atMost(millis: Long): Boolean = notAfterEpochMillis - notBeforeEpochMillis <= millis
}

/** Supplied by the actual qualified native clock, never by the installation package or UI. */
public data class OfflineInstallationUtcSample(
    public val earliestEpochMillis: Long,
    public val latestEpochMillis: Long,
) {
    init {
        require(earliestEpochMillis >= 0 && latestEpochMillis >= earliestEpochMillis &&
            latestEpochMillis - earliestEpochMillis <= OfflineInstallationFormat.MAX_UTC_UNCERTAINTY_MILLIS)
    }
}

/** Raw syntax only. Native installation of this scoped pin, not this constructor, is authority. */
public class OfflineInstallationRootPin(
    public val keyId: DomainId,
    public val company: CompanyScope,
    public val publicKeyHex: String,
    public val validity: OfflineInstallationUtcWindow,
) {
    init { require(offlinePublicKeySyntax(publicKeyHex)) }
    override fun toString(): String = "OfflineInstallationRootPin([REDACTED])"
}

/**
 * Raw installed anchor material, NOT authenticated by its Kotlin type. Only the mandatory native
 * owner may supply it from its protected enrolment, with anti-rollback minimum trust generation.
 * Never construct it from a package, request, adjacent key file, UI selection or network reply.
 */
public class OfflineInstallationRootPins(
    public val enrollmentId: DomainId,
    public val revision: Revision,
    public val minimumTrustGeneration: Revision,
    pins: List<OfflineInstallationRootPin>,
) {
    init {
        require(revision.value < Long.MAX_VALUE && minimumTrustGeneration.value < Long.MAX_VALUE)
        require(pins.size in 1..OfflineInstallationFormat.MAX_ROOT_PINS)
    }
    private val values: List<OfflineInstallationRootPin> = pins.toList()
    public val pins: List<OfflineInstallationRootPin> get() = values.toList()
    override fun toString(): String = "OfflineInstallationRootPins([REDACTED])"
}

/**
 * An inert bounded body/signature, never a preverified DTO. Exact canonical UTF-8 body bytes are
 * hashed; signatures cover the versioned, counted/length-framed domain + role + body. No private
 * key is transported. Oversize must be rejected before a native reader allocates an input blob.
 */
public class OfflineInstallationSignedDocument(body: ByteArray, signature: ByteArray) {
    init {
        require(body.size in 1..OfflineInstallationFormat.MAX_DOCUMENT_BYTES)
        require(signature.size == OfflineInstallationFormat.SIGNATURE_BYTES)
    }
    private val bodyValue: ByteArray = body.copyOf()
    private val signatureValue: ByteArray = signature.copyOf()
    public val bodySize: Int get() = bodyValue.size
    internal fun copyBody(): ByteArray = bodyValue.copyOf()
    internal fun copySignature(): ByteArray = signatureValue.copyOf()
    override fun toString(): String = "OfflineInstallationSignedDocument([REDACTED])"
}

/** All six raw signed artifacts are mandatory; neither an optional proof nor a registration path. */
public class OfflineInstallationInstalledEvidence(
    public val roots: OfflineInstallationRootPins,
    public val trustState: OfflineInstallationSignedDocument,
    public val custodyBinding: OfflineInstallationSignedDocument,
    public val custodyLease: OfflineInstallationSignedDocument,
    public val source: OfflineInstallationSignedDocument,
    public val humanDelegation: OfflineInstallationSignedDocument,
    public val exactRequestApproval: OfflineInstallationSignedDocument,
) {
    init {
        require(listOf(trustState, custodyBinding, custodyLease, source, humanDelegation, exactRequestApproval)
            .sumOf { it.bodySize + OfflineInstallationFormat.SIGNATURE_BYTES } <= OfflineInstallationFormat.MAX_EVIDENCE_BYTES)
    }
    override fun toString(): String = "OfflineInstallationInstalledEvidence([REDACTED])"
}

@Serializable
public enum class OfflineInstallationNamespace { FRESH_LIVE, TRAINING, RESTORED, COPIED, ADOPTED, UNKNOWN }

/**
 * CURRENT native observation, not a transport or evidence DTO. IDs identify actual native-owned
 * roots, keys, namespace, exclusive lease, named-human session and qualified clock. The verifier
 * compares against immutable, cryptographically verified installed evidence; a new observation
 * may ONLY invalidate it, never refresh/re-authorize trust. Non-fresh and unknown origins fail.
 */
public class OfflineInstallationNativeObservation(
    public val scope: BranchScope,
    public val installationId: DomainId,
    public val nativeOwnerId: DomainId,
    public val rootEnrollmentId: DomainId,
    public val rootRevision: Revision,
    public val rootPinsDigest: PayloadDigest,
    public val trustGeneration: Revision,
    public val trustStateDigest: PayloadDigest,
    public val custodyBindingDigest: PayloadDigest,
    public val custodyLeaseId: DomainId,
    public val custodyLeaseDigest: PayloadDigest,
    public val storageInstanceId: DomainId,
    public val storageKeyId: DomainId,
    public val pepperKeyId: DomainId,
    public val deviceKeyId: DomainId,
    public val custodyKeyId: DomainId,
    public val namespace: OfflineInstallationNamespace,
    public val freshOriginId: DomainId,
    public val monotonicContextId: DomainId,
    public val humanId: DomainId,
    public val humanKeyId: DomainId,
    public val humanSessionId: DomainId,
    public val issuerContextId: DomainId,
    public val moment: MonotonicMoment,
    public val utc: OfflineInstallationUtcSample,
) {
    override fun toString(): String = "OfflineInstallationNativeObservation([REDACTED])"
}

/**
 * MANDATORY, presently unimplemented OS/root-custody boundary. A native host must independently
 * qualify root enrolment/rollback/revocation freshness, fresh-live origin (not merely empty SQL),
 * actual storage-instance/key binding, exclusive supervised writer lease, non-exporting device,
 * human and custody keys, qualified UTC uncertainty/monotonic continuity and physical lifetime.
 * This interface, a status enum, synthetic provider or package possession proves none of these.
 * There is deliberately no accepting/default provider or public factory taking installed flags.
 *
 * The two component references are immutable for this owner. readInstalledEvidence is bounded,
 * read-only and returns RAW documents; it must not acquire a second Room pool or writer. observe
 * is synchronous, bounded, non-reentrant and performs no blocking IO. It must reflect closure,
 * key lock/replacement, custody/human-session loss, root/trust changes and any clock uncertainty
 * immediately; unknown/dirty state returns null, never a remembered last-good observation.
 *
 * Signing operations are native-only, not remotely callable generic signing oracles. Each must
 * use the selected non-exporting key in this SAME current lease. Named approval must authenticate
 * the separately delegated human in the signed human-session lifetime and require their fresh
 * informed approval of this exact request; local employee login/name text is not a substitute.
 * Domain-framed message bytes already bind the complete request and all verified artifacts.
 * Reject a request/message/role mismatch; do not silently reinterpret or normalize messages.
 *
 * Inputs are borrowed until return; do not retain/mutate them. A returned signature transfers
 * sole ownership and is exactly 64 bytes or null. Preserve cancellation; bounded coroutine
 * deadlines cannot reap a hung native process. The real supervisor must fence/reap uncertain
 * operations and retire custody before any further application effect. No secret is exported.
 */
public interface OfflineInstallationNativeOwner {
    public val storage: DeviceProtectedLocalStorage
    public val pepper: DeviceProtectedPepper
    public suspend fun readInstalledEvidence(): OfflineInstallationInstalledEvidence?
    public fun observe(): OfflineInstallationNativeObservation?
    public suspend fun approveNamedHuman(
        humanId: DomainId,
        humanKeyId: DomainId,
        humanSessionId: DomainId,
        request: LocalBranchInstallationRequest,
        message: ByteArray,
    ): ByteArray?
    public suspend fun proveDevicePossession(
        deviceId: DomainId,
        deviceKeyId: DomainId,
        request: LocalBranchInstallationRequest,
        message: ByteArray,
    ): ByteArray?
    public suspend fun proveCustody(
        custodyKeyId: DomainId,
        custodyLeaseId: DomainId,
        request: LocalBranchInstallationRequest,
        protectionProbeDigest: PayloadDigest,
        message: ByteArray,
    ): ByteArray?
}
