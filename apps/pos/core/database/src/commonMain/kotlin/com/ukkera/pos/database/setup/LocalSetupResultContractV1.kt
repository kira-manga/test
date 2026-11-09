package com.ukkera.pos.database.setup

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Additive setup-result grammar. These are new command/read/aggregate identities only: no
 * existing installation, policy, Android database or wire byte is widened or rewritten.
 *
 * Delivery acknowledgement, protected read, business go-live, installation, login and RUN are
 * deliberately separate. None of these profiles installs, imports, signs in, starts RUN,
 * registers centrally, posts money/stock or qualifies a native API.
 */
public object LocalSetupResultProfileV1 {
    public val ACKNOWLEDGE: CommandKey = CommandKey("local.setup.result.acknowledge", 1)
    public val GO_LIVE: CommandKey = CommandKey("local.setup.business.go-live", 1)
    public val READ: ReadKey = ReadKey("local.setup.result.read", 1)

    public const val ACKNOWLEDGEMENT_KIND: String = "local.setup.result.acknowledgement"
    public const val GO_LIVE_KIND: String = "local.setup.business.go-live"

    public const val ACKNOWLEDGEMENT_SCHEMA: String = "local.setup.result.acknowledgement/v1"
    public const val GO_LIVE_SCHEMA: String = "local.setup.business.go-live/v1"

    public const val MAX_REQUEST_BYTES: Int = 1024
    public const val MAX_RECORD_BYTES: Int = 32 * 1024
    public const val MAX_PROJECTION_BYTES: Int = 64 * 1024

    public const val ACKNOWLEDGED_CODE: String = "setup.ack.committed"
    public const val LIVE_CODE: String = "setup.go_live.committed"

    public const val ACKNOWLEDGE_REASON: String = "setup.result.acknowledge"
    public const val GO_LIVE_REASON: String = "setup.business.go-live"
    public const val READ_REASON: String = "setup.result.read"
}

/**
 * The requester may name only the saved original it believes it is addressing. The actual
 * original, actor, permission, time and current master remain server-derived. A name mismatch
 * refuses without adopting, merging or replacing the saved original.
 */
@Serializable
public data class LocalSetupResultRequestV1(public val installationId: DomainId) {
    override fun toString(): String = "LocalSetupResultRequestV1([REDACTED])"
}

/** Immutable product acknowledgement of delivery, not native-envelope retention/consumption. */
@Serializable
public data class LocalSetupAcknowledgementRecordV1(
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val branchId: DomainId,
    public val installationId: DomainId,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val definitionDigest: PayloadDigest,
    public val genesisDigest: PayloadDigest,
    public val receiptDigest: PayloadDigest,
    public val actorId: DomainId,
    public val acknowledgedAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
) {
    init { require(attemptSequence in 1 until Long.MAX_VALUE) }
    override fun toString(): String = "LocalSetupAcknowledgementRecordV1([REDACTED])"
}

/** Responsible local actor and auditable setup record. Not a central/health/fiscal result. */
@Serializable
public data class LocalSetupGoLiveRecordV1(
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val branchId: DomainId,
    public val installationId: DomainId,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val definitionDigest: PayloadDigest,
    public val genesisDigest: PayloadDigest,
    public val receiptDigest: PayloadDigest,
    public val actorId: DomainId,
    public val occurredAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
    public val prerequisites: List<LocalSetupPrerequisiteObservationV1>,
) {
    init {
        require(attemptSequence in 1 until Long.MAX_VALUE)
        LocalSetupPrerequisiteObservationV1.requireComplete(prerequisites)
        require(prerequisites.all { it.settled })
    }
    override fun toString(): String = "LocalSetupGoLiveRecordV1([REDACTED])"
}

/** The saved original projection disclosed only after the current read action is authorized. */
@Serializable
public data class LocalSetupSavedOriginalResultV1(
    public val organizationId: DomainId,
    public val companyId: DomainId,
    public val branchId: DomainId,
    public val installationId: DomainId,
    public val assignmentId: DomainId,
    public val deviceId: DomainId,
    public val firstActorId: DomainId,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val definitionDigest: PayloadDigest,
    public val genesisDigest: PayloadDigest,
    public val receiptDigest: PayloadDigest,
    public val installationAuthorityEpoch: Revision,
    public val currentAuthorityEpoch: Revision,
    public val installedAt: UtcInstant,
    public val nativeSettlement: Boolean,
) {
    init {
        require(attemptSequence in 1 until Long.MAX_VALUE)
    }
    override fun toString(): String = "LocalSetupSavedOriginalResultV1([REDACTED])"
}

public enum class LocalSetupResultStatusV1 {
    ACKNOWLEDGED,
    LIVE,
}

/** Durable retained command result for a committed setup fact. */
@Serializable
public data class LocalSetupResultOutcomeV1(
    public val status: LocalSetupResultStatusV1,
    public val code: String,
    public val acknowledgement: LocalSetupAcknowledgementRecordV1? = null,
    public val goLive: LocalSetupGoLiveRecordV1? = null,
    public val prerequisites: List<LocalSetupPrerequisiteObservationV1> = emptyList(),
) {
    init {
        require(code.length in 1..96 && code.all { it in 'a'..'z' || it in '0'..'9' || it in "._-/" })
        require((status == LocalSetupResultStatusV1.ACKNOWLEDGED) == (acknowledgement != null))
        require((status == LocalSetupResultStatusV1.LIVE) == (goLive != null))
        require(status == LocalSetupResultStatusV1.LIVE || prerequisites.isEmpty())
    }
    override fun toString(): String = "LocalSetupResultOutcomeV1([REDACTED])"
}

/** Complete read projection. It contains no secret, token, key, SQL row or native envelope. */
@Serializable
public data class LocalSetupResultProjectionV1(
    public val original: LocalSetupSavedOriginalResultV1,
    public val acknowledgement: LocalSetupAcknowledgementRecordV1? = null,
    public val goLive: LocalSetupGoLiveRecordV1? = null,
    public val prerequisites: List<LocalSetupPrerequisiteObservationV1>,
) {
    init {
        LocalSetupPrerequisiteObservationV1.requireComplete(prerequisites)
        require(acknowledgement == null || acknowledgement.originalIdentity() == original.originalIdentity())
        require(goLive == null || goLive.originalIdentity() == original.originalIdentity())
    }
    override fun toString(): String = "LocalSetupResultProjectionV1([REDACTED])"
}

/** Closed prerequisite names; unknown/meaningless readiness values fail closed. */
public enum class LocalSetupPrerequisiteCodeV1 {
    NATIVE_INSTALLATION_SETTLEMENT,
    REQUIRED_CONFIGURATION,
    CATALOG_IMPORT_SETTLEMENT,
    OPENING_SETTLEMENT,
}

/**
 * One observed prerequisite. [settled] is backed by a real production source binding; the
 * requester cannot supply it. An unsettled observation names the precise unavailable binding.
 */
@Serializable
public data class LocalSetupPrerequisiteObservationV1(
    public val code: LocalSetupPrerequisiteCodeV1,
    public val settled: Boolean,
    public val binding: String,
) {
    init { requireProtocolBinding(binding) }

    public companion object {
        public val requiredCodes: List<LocalSetupPrerequisiteCodeV1> =
            LocalSetupPrerequisiteCodeV1.entries.toList()

        public fun requireComplete(values: List<LocalSetupPrerequisiteObservationV1>) {
            require(values.size == requiredCodes.size) { "Incomplete setup go-live prerequisites" }
            require(values.map { it.code }.toSet() == requiredCodes.toSet()) {
                "Unknown or duplicate setup go-live prerequisite"
            }
            require(values.zipWithNext().all { (a, b) -> a.code.ordinal < b.code.ordinal }) {
                "Setup go-live prerequisites are not in canonical order"
            }
        }

        public fun settled(code: LocalSetupPrerequisiteCodeV1, binding: String): LocalSetupPrerequisiteObservationV1 =
            LocalSetupPrerequisiteObservationV1(code, true, binding)

        public fun unavailable(code: LocalSetupPrerequisiteCodeV1, binding: String): LocalSetupPrerequisiteObservationV1 =
            LocalSetupPrerequisiteObservationV1(code, false, binding)

        private fun requireProtocolBinding(value: String) {
            require(value.length in 1..256 && value.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._:/-"
            }) { "Use a bounded non-secret prerequisite binding" }
        }
    }
}

/**
 * Installed production observation source for non-native prerequisites. It runs on the actual
 * master writer lease and may return only real production evidence or an explicit unavailable
 * binding. It cannot invent readiness, consume a caller flag or substitute RUN/OPERATIONAL.
 *
 * The native installation settlement observation is supplied by the saved original binding;
 * implementations return the remaining closed prerequisite set.
 */
public fun interface LocalSetupGoLivePrerequisiteSource {
    public suspend fun observe(view: CommandReadView, original: SavedOriginalSetupV1):
        List<LocalSetupPrerequisiteObservationV1>
    /** Complete footprint derived from verified checkpoint/effect sources. Null denies go-live. */
    public suspend fun recoveryResources(view: CommandReadView, installationId: DomainId):
        Set<com.ukkera.pos.recovery.RecoveryResourceKey>? = null
    /** Re-derive non-native readiness from the genuine historical BEFORE cut; null refuses. */
    public suspend fun historicalObservations(view: CommandReadView, record: LocalSetupGoLiveRecordV1):
        List<LocalSetupPrerequisiteObservationV1>? = null
}

/**
 * Conservative installed default. The current production surface does not retain an
 * original-linked required-configuration, catalog-import or opening-checkpoint/result
 * identity. Unrelated row existence (a branch-activity row, any active SKU or any stock
 * receipt) is not substituted, so these obligations remain explicitly held. A future source
 * must bind each observation to the saved original's actual checkpoint/result identity.
 */
public object UnavailableLocalSetupGoLivePrerequisites : LocalSetupGoLivePrerequisiteSource {
    override suspend fun observe(view: CommandReadView, original: SavedOriginalSetupV1):
        List<LocalSetupPrerequisiteObservationV1> = listOf(
        LocalSetupPrerequisiteObservationV1.unavailable(
            LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION,
            "unavailable:original-setup.configuration-checkpoint"),
        LocalSetupPrerequisiteObservationV1.unavailable(
            LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT,
            "unavailable:original-setup.catalog-import-checkpoint"),
        LocalSetupPrerequisiteObservationV1.unavailable(
            LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT,
            "unavailable:original-setup.opening-checkpoint"),
    )
}

/**
 * Native-owner-backed saved original facts for this exact writer. This is a local observation,
 * not a runtime capability, custody proof or permission to install/replace anything.
 */
public data class SavedOriginalSetupV1(
    public val scope: BranchScope,
    public val installationId: DomainId,
    public val assignmentId: DomainId,
    public val deviceId: DomainId,
    public val firstActorId: DomainId,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val definitionDigest: PayloadDigest,
    public val genesisDigest: PayloadDigest,
    public val receiptDigest: PayloadDigest,
    public val installationAuthorityEpoch: Revision,
    public val currentAuthorityEpoch: Revision,
    public val installedAt: UtcInstant,
    public val nativeSettlement: Boolean,
) {
    init { require(attemptSequence in 1 until Long.MAX_VALUE) }
    override fun toString(): String = "SavedOriginalSetupV1([REDACTED])"
}

/** Public codecs for test wiring and ordinary typed clients; no authority is encoded here. */
public object LocalSetupResultCodecV1 {
    public fun acknowledge(operationId: OperationId, installationId: DomainId): MutationRequest =
        MutationRequest(operationId, LocalSetupResultProfileV1.ACKNOWLEDGE, encodeRequest(installationId))

    public fun goLive(operationId: OperationId, installationId: DomainId): MutationRequest =
        MutationRequest(operationId, LocalSetupResultProfileV1.GO_LIVE, encodeRequest(installationId))

    public fun read(installationId: DomainId): ReadRequest =
        ReadRequest(LocalSetupResultProfileV1.READ, encodeRequest(installationId))

    public fun encodeRequest(installationId: DomainId): StoredBytes =
        encode(LocalSetupResultRequestV1(installationId), LocalSetupResultRequestV1.serializer(),
            LocalSetupResultProfileV1.MAX_REQUEST_BYTES)

    public fun decodeRequest(value: StoredBytes): LocalSetupResultRequestV1 =
        decode(value, LocalSetupResultRequestV1.serializer(), LocalSetupResultProfileV1.MAX_REQUEST_BYTES)

    public fun encodeOutcome(value: LocalSetupResultOutcomeV1): StoredBytes =
        encode(value, LocalSetupResultOutcomeV1.serializer(), LocalSetupResultProfileV1.MAX_RECORD_BYTES)

    public fun decodeOutcome(value: StoredBytes): LocalSetupResultOutcomeV1 =
        decode(value, LocalSetupResultOutcomeV1.serializer(), LocalSetupResultProfileV1.MAX_RECORD_BYTES)

    public fun encodeProjection(value: LocalSetupResultProjectionV1): StoredBytes =
        encode(value, LocalSetupResultProjectionV1.serializer(), LocalSetupResultProfileV1.MAX_PROJECTION_BYTES)

    public fun decodeProjection(value: StoredBytes): LocalSetupResultProjectionV1 =
        decode(value, LocalSetupResultProjectionV1.serializer(), LocalSetupResultProfileV1.MAX_PROJECTION_BYTES)

    internal fun encodeAcknowledgement(value: LocalSetupAcknowledgementRecordV1): StoredBytes =
        encode(value, LocalSetupAcknowledgementRecordV1.serializer(), LocalSetupResultProfileV1.MAX_RECORD_BYTES)

    internal fun decodeAcknowledgement(value: StoredBytes): LocalSetupAcknowledgementRecordV1 =
        decode(value, LocalSetupAcknowledgementRecordV1.serializer(), LocalSetupResultProfileV1.MAX_RECORD_BYTES)

    internal fun encodeGoLive(value: LocalSetupGoLiveRecordV1): StoredBytes =
        encode(value, LocalSetupGoLiveRecordV1.serializer(), LocalSetupResultProfileV1.MAX_RECORD_BYTES)

    internal fun decodeGoLive(value: StoredBytes): LocalSetupGoLiveRecordV1 =
        decode(value, LocalSetupGoLiveRecordV1.serializer(), LocalSetupResultProfileV1.MAX_RECORD_BYTES)

    private fun <T> encode(value: T, serializer: KSerializer<T>, maximum: Int): StoredBytes {
        val bytes = setupResultJson.encodeToString(serializer, value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        try {
            require(bytes.size in 1..maximum) { "Local setup result capacity" }
            return StoredBytes(bytes)
        } finally { bytes.fill(0) }
    }

    /** Strict exact representation; canonical re-encoding refuses missing/unknown/defaulted data. */
    private fun <T> decode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int): T {
        require(bytes.size in 1..maximum) { "Local setup result capacity" }
        val content = bytes.copyBytes()
        try {
            val text = content.decodeToString(throwOnInvalidSequence = true)
            val value = setupResultJson.decodeFromString(serializer, text)
            require(setupResultJson.encodeToString(serializer, value) == text) {
                "Noncanonical local setup result encoding"
            }
            return value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid local setup result encoding")
        } finally { content.fill(0) }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val setupResultJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

/** Exact original identity retained on facts and checked before current projection/replay. */
internal data class SetupOriginalIdentity(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId, val installationId: DomainId, val originalRequestId: DomainId, val issuedAttemptId: DomainId, val attemptSequence: Long, val definitionDigest: PayloadDigest, val genesisDigest: PayloadDigest, val receiptDigest: PayloadDigest)
internal fun LocalSetupAcknowledgementRecordV1.originalIdentity(): SetupOriginalIdentity = SetupOriginalIdentity(organizationId, companyId, branchId, installationId, originalRequestId, issuedAttemptId, attemptSequence, definitionDigest, genesisDigest, receiptDigest)
internal fun LocalSetupGoLiveRecordV1.originalIdentity(): SetupOriginalIdentity = SetupOriginalIdentity(organizationId, companyId, branchId, installationId, originalRequestId, issuedAttemptId, attemptSequence, definitionDigest, genesisDigest, receiptDigest)
internal fun LocalSetupSavedOriginalResultV1.originalIdentity(): SetupOriginalIdentity = SetupOriginalIdentity(organizationId, companyId, branchId, installationId, originalRequestId, issuedAttemptId, attemptSequence, definitionDigest, genesisDigest, receiptDigest)
internal fun SavedOriginalSetupV1.originalIdentity(): SetupOriginalIdentity = SetupOriginalIdentity(scope.organizationId, scope.companyId, scope.branchId, installationId, originalRequestId, issuedAttemptId, attemptSequence, definitionDigest, genesisDigest, receiptDigest)
