package com.ukkera.pos.catalog.store.setup

import com.ukkera.pos.catalog.store.LocalBranchActivityReferenceV1
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.setup.SavedOriginalSetupV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Additive, versioned setup-checkpoint profile. This is the real durable BranchSetupRecord /
 * ImportBatch production path: required configuration, a resumable catalog import manifest and
 * an opening settlement manifest with stable per-row source identities and exact retained
 * operation/effect references. Delivery ACK, go-live, login and RUN remain separate profiles.
 *
 * The request may name the saved original it believes it addresses. That name is never authority:
 * the master compares every binding field with the actual saved original inside the writer
 * transaction and holds on any mismatch. The caller cannot supply readiness, effect digests,
 * actors or checkpoint identities.
 */
public object LocalSetupCheckpointProfileV1 {
    public val CONFIGURE: CommandKey = CommandKey("local.setup.configuration.record", 1)
    public val CATALOG_IMPORT_ROW: CommandKey = CommandKey("local.setup.catalog-import.row", 1)
    public val COMPLETE_CATALOG_IMPORT: CommandKey = CommandKey("local.setup.catalog-import.complete", 1)
    public val OPENING_STOCK_ROW: CommandKey = CommandKey("local.setup.opening.stock-row", 1)
    public val OPENING_FLOAT_ROW: CommandKey = CommandKey("local.setup.opening.float-row", 1)
    public val COMPLETE_OPENING: CommandKey = CommandKey("local.setup.opening.complete", 1)
    public val READ: ReadKey = ReadKey("local.setup.checkpoints.read", 1)

    public const val CONFIGURATION_KIND: String = "local.setup.configuration"
    public const val CATALOG_IMPORT_ROW_KIND: String = "local.setup.catalog-import.row"
    public const val CATALOG_IMPORT_CHECKPOINT_KIND: String = "local.setup.catalog-import.checkpoint"
    public const val OPENING_STOCK_ROW_KIND: String = "local.setup.opening.stock-row"
    public const val OPENING_FLOAT_ROW_KIND: String = "local.setup.opening.float-row"
    public const val OPENING_CHECKPOINT_KIND: String = "local.setup.opening.checkpoint"

    public const val SOURCE_CLAIM_NAMESPACE: String = "local.setup.source"

    public const val CONFIGURED_CODE: String = "setup.configuration.recorded"
    public const val CATALOG_ROW_CODE: String = "setup.catalog-import.row.recorded"
    public const val CATALOG_SETTLED_CODE: String = "setup.catalog-import.settled"
    public const val OPENING_STOCK_ROW_CODE: String = "setup.opening.stock-row.recorded"
    public const val OPENING_FLOAT_ROW_CODE: String = "setup.opening.float-row.recorded"
    public const val OPENING_SETTLED_CODE: String = "setup.opening.settled"

    public const val CONFIGURE_REASON: String = "setup.configuration.record"
    public const val CATALOG_IMPORT_REASON: String = "setup.catalog-import.settle"
    public const val OPENING_REASON: String = "setup.opening.settle"
    public const val READ_REASON: String = "setup.checkpoints.read"

    public const val MAX_REQUEST_BYTES: Int = 64 * 1024
    public const val MAX_RECORD_BYTES: Int = 60 * 1024
    public const val MAX_PROJECTION_BYTES: Int = 60 * 1024
    public const val MAX_MANIFEST_ROWS: Int = 1024
    public const val MAX_SCAN_ROWS: Int = 4096

    public fun schema(kind: String): String = "$kind/v1"
    public fun key(kind: String, installationId: DomainId): AggregateKey = AggregateKey(kind, installationId)

    public fun sourceClaim(kind: String, installationId: DomainId, sourceId: DomainId): com.ukkera.pos.database.ClaimKey =
        com.ukkera.pos.database.ClaimKey(SOURCE_CLAIM_NAMESPACE,
            "${kind}/${installationId.value}/${sourceId.value}")

    public fun catalogOperationClaim(installationId: DomainId, operationId: OperationId): com.ukkera.pos.database.ClaimKey =
        com.ukkera.pos.database.ClaimKey(SOURCE_CLAIM_NAMESPACE, "catalog-operation/${installationId.value}/${operationId.value}")

    /** Deterministic row identity from the installation and the stable source identity. */
    public fun rowId(kind: String, installationId: DomainId, sourceId: DomainId): DomainId {
        val digest = sha256("$kind/v1\n${installationId.value}\n${sourceId.value}".encodeToByteArray())
        val hex = digest.value
        return DomainId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}")
    }
}

@Serializable
public enum class LocalSetupEnvironmentV1 { LIVE, TRAINING }

@Serializable
public data class LocalSetupOriginalBindingV1(
    public val installationId: DomainId,
    public val originalRequestId: DomainId,
    public val issuedAttemptId: DomainId,
    public val attemptSequence: Long,
    public val definitionDigest: PayloadDigest,
    public val genesisDigest: PayloadDigest,
    public val receiptDigest: PayloadDigest,
) {
    init { require(attemptSequence in 1 until Long.MAX_VALUE) }
    public fun matches(value: SavedOriginalSetupV1): Boolean =
        installationId == value.installationId && originalRequestId == value.originalRequestId &&
            issuedAttemptId == value.issuedAttemptId && attemptSequence == value.attemptSequence &&
            definitionDigest == value.definitionDigest &&
            genesisDigest == value.genesisDigest && receiptDigest == value.receiptDigest

    public companion object {
        public fun of(value: SavedOriginalSetupV1): LocalSetupOriginalBindingV1 = LocalSetupOriginalBindingV1(
            value.installationId, value.originalRequestId, value.issuedAttemptId, value.attemptSequence,
            value.definitionDigest, value.genesisDigest, value.receiptDigest)
    }

    override fun toString(): String = "LocalSetupOriginalBindingV1([REDACTED])"
}

@Serializable
public data class LocalSetupCheckpointTargetV1(
    public val original: LocalSetupOriginalBindingV1,
    public val environment: LocalSetupEnvironmentV1,
) {
    init {
        require(environment == LocalSetupEnvironmentV1.LIVE) {
            "Training setup uses its separate physical/logical root; live checkpoints cannot promote it"
        }
    }
    override fun toString(): String = "LocalSetupCheckpointTargetV1([REDACTED])"
}

/** Explicit legal/tax identity. Tax registration absence is confirmed, never silently omitted. */
@Serializable
public data class LocalSetupFiscalIdentityV1(
    public val legalName: String,
    public val taxRegistrationId: String? = null,
    public val taxRegistrationAbsent: Boolean = false,
) {
    init {
        require(legalName.length in 1..200 && legalName.none { it.code < 0x20 || it.code == 0x7f })
        require((taxRegistrationId != null) xor taxRegistrationAbsent) {
            "Confirm either the exact tax registration id or its explicit absence"
        }
        taxRegistrationId?.let {
            require(it.length in 1..64 && it.none { char -> char.code < 0x20 || char.code == 0x7f })
        }
    }
    override fun toString(): String = "LocalSetupFiscalIdentityV1([REDACTED])"
}

@Serializable
public data class LocalSetupConfigurationRequestV1(
    public val target: LocalSetupCheckpointTargetV1,
    public val expectedRevision: Revision,
    public val currency: Currency,
    public val timeZone: String,
    public val businessDayCutoffMinute: Int,
    public val fiscalIdentity: LocalSetupFiscalIdentityV1,
) {
    init {
        require(currency == Currency.EGP) { "Release one admits EGP only" }
        require(timeZone.length in 1..64 && timeZone.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._+-/"
        })
        TimeZone.of(timeZone)
        require(businessDayCutoffMinute in 0..1439)
    }
    override fun toString(): String = "LocalSetupConfigurationRequestV1([REDACTED])"
}

@Serializable
public data class LocalSetupCatalogImportManifestEntryV1(
    public val sourceId: DomainId,
    public val skuId: DomainId,
    public val catalogOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupCatalogImportManifestEntryV1([REDACTED])"
}

@Serializable
public data class LocalSetupCatalogImportRowRequestV1(
    public val target: LocalSetupCheckpointTargetV1,
    public val sourceId: DomainId,
    public val skuId: DomainId,
    public val catalogOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupCatalogImportRowRequestV1([REDACTED])"
}

@Serializable
public enum class LocalSetupImportModeV1 { DECLARED, EXPLICIT_NO_IMPORT }

@Serializable
public data class LocalSetupCatalogImportCompleteRequestV1(
    public val target: LocalSetupCheckpointTargetV1,
    public val mode: LocalSetupImportModeV1,
    public val expectedRowCount: Int,
    public val expectedManifestDigest: PayloadDigest? = null,
) {
    init {
        require(expectedRowCount in 0..LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS)
        when (mode) {
            LocalSetupImportModeV1.DECLARED -> require(expectedRowCount > 0 && expectedManifestDigest != null)
            LocalSetupImportModeV1.EXPLICIT_NO_IMPORT -> require(expectedRowCount == 0 && expectedManifestDigest == null)
        }
    }
    override fun toString(): String = "LocalSetupCatalogImportCompleteRequestV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningStockManifestEntryV1(
    public val sourceId: DomainId,
    public val receiptId: DomainId,
    public val openingOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupOpeningStockManifestEntryV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningFloatManifestEntryV1(
    public val sourceId: DomainId,
    public val sessionId: DomainId,
    public val openingOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupOpeningFloatManifestEntryV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningStockRowRequestV1(
    public val target: LocalSetupCheckpointTargetV1,
    public val sourceId: DomainId,
    public val receiptId: DomainId,
    public val openingOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupOpeningStockRowRequestV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningFloatRowRequestV1(
    public val target: LocalSetupCheckpointTargetV1,
    public val sourceId: DomainId,
    public val sessionId: DomainId,
    public val openingOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupOpeningFloatRowRequestV1([REDACTED])"
}

@Serializable
public enum class LocalSetupOpeningChoiceV1 { DECLARED, EXPLICIT_ZERO }

@Serializable
public data class LocalSetupOpeningCompleteRequestV1(
    public val target: LocalSetupCheckpointTargetV1,
    public val stockChoice: LocalSetupOpeningChoiceV1,
    public val expectedStockRowCount: Int,
    public val expectedStockManifestDigest: PayloadDigest? = null,
    public val floatChoice: LocalSetupOpeningChoiceV1,
    public val expectedFloatRowCount: Int,
    public val expectedFloatManifestDigest: PayloadDigest? = null,
) {
    init {
        require(expectedStockRowCount in 0..LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS)
        require(expectedFloatRowCount in 0..LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS)
        when (stockChoice) {
            LocalSetupOpeningChoiceV1.DECLARED ->
                require(expectedStockRowCount > 0 && expectedStockManifestDigest != null)
            LocalSetupOpeningChoiceV1.EXPLICIT_ZERO ->
                require(expectedStockRowCount == 0 && expectedStockManifestDigest == null)
        }
        when (floatChoice) {
            LocalSetupOpeningChoiceV1.DECLARED ->
                require(expectedFloatRowCount > 0 && expectedFloatManifestDigest != null)
            LocalSetupOpeningChoiceV1.EXPLICIT_ZERO ->
                require(expectedFloatRowCount == 0 && expectedFloatManifestDigest == null)
        }
    }
    override fun toString(): String = "LocalSetupOpeningCompleteRequestV1([REDACTED])"
}

@Serializable
public data class LocalSetupCheckpointQueryV1(public val installationId: DomainId) {
    override fun toString(): String = "LocalSetupCheckpointQueryV1([REDACTED])"
}

@Serializable
public data class LocalSetupConfigurationRecordV1(
    public val binding: LocalSetupOriginalBindingV1,
    public val environment: LocalSetupEnvironmentV1,
    public val revision: Revision,
    public val activities: List<LocalSalesActivity>,
    public val currency: Currency,
    public val timeZone: String,
    public val businessDayCutoffMinute: Int,
    public val fiscalIdentity: LocalSetupFiscalIdentityV1,
    public val branchActivityOperationId: OperationId,
    public val branchActivityReference: LocalBranchActivityReferenceV1,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
) {
    init {
        require(environment == LocalSetupEnvironmentV1.LIVE)
        require(activities.isNotEmpty() && activities.distinct().size == activities.size &&
            activities == activities.sortedBy { it.ordinal })
        require(currency == Currency.EGP)
        TimeZone.of(timeZone)
        require(businessDayCutoffMinute in 0..1439)
    }
    override fun toString(): String = "LocalSetupConfigurationRecordV1([REDACTED])"
}

@Serializable
public data class LocalSetupCatalogImportRowRecordV1(
    public val binding: LocalSetupOriginalBindingV1,
    public val environment: LocalSetupEnvironmentV1,
    public val sourceId: DomainId,
    public val skuId: DomainId,
    public val catalogOperationId: OperationId,
    public val catalogPublicationId: DomainId,
    public val catalogEffectDigest: PayloadDigest,
    public val catalogEffectRevision: Revision,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
) {
    init { require(environment == LocalSetupEnvironmentV1.LIVE) }
    override fun toString(): String = "LocalSetupCatalogImportRowRecordV1([REDACTED])"
}

@Serializable
public data class LocalSetupCatalogImportRowRefV1(
    public val sourceId: DomainId,
    public val skuId: DomainId,
    public val rowId: DomainId,
    public val catalogOperationId: OperationId,
    public val catalogEffectDigest: PayloadDigest,
    public val catalogEffectRevision: Revision,
) {
    override fun toString(): String = "LocalSetupCatalogImportRowRefV1([REDACTED])"
}

@Serializable
public data class LocalSetupCatalogImportCheckpointV1(
    public val binding: LocalSetupOriginalBindingV1,
    public val environment: LocalSetupEnvironmentV1,
    public val mode: LocalSetupImportModeV1,
    public val rowCount: Int,
    public val manifestDigest: PayloadDigest? = null,
    public val inputManifestDigest: PayloadDigest? = null,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
) {
    init {
        require(environment == LocalSetupEnvironmentV1.LIVE)
        require(rowCount in 0..LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS)
        when (mode) {
            LocalSetupImportModeV1.DECLARED -> require(rowCount > 0 && manifestDigest != null && inputManifestDigest != null)
            LocalSetupImportModeV1.EXPLICIT_NO_IMPORT ->
                require(rowCount == 0 && manifestDigest == null && inputManifestDigest == null)
        }
    }
    override fun toString(): String = "LocalSetupCatalogImportCheckpointV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningStockRowRecordV1(
    public val binding: LocalSetupOriginalBindingV1,
    public val environment: LocalSetupEnvironmentV1,
    public val sourceId: DomainId,
    public val receiptId: DomainId,
    public val openingOperationId: OperationId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val quantity: Quantity,
    public val value: Money,
    public val lotId: DomainId? = null,
    public val serialId: DomainId? = null,
    public val stockEffectDigest: PayloadDigest,
    public val stockEffectRevision: Revision,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
) {
    init {
        require(environment == LocalSetupEnvironmentV1.LIVE)
        require(quantity.scaled > 0 && value.minor >= 0)
    }
    override fun toString(): String = "LocalSetupOpeningStockRowRecordV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningFloatRowRecordV1(
    public val binding: LocalSetupOriginalBindingV1,
    public val environment: LocalSetupEnvironmentV1,
    public val sourceId: DomainId,
    public val sessionId: DomainId,
    public val openingOperationId: OperationId,
    public val amount: Money,
    public val cashEffectDigest: PayloadDigest,
    public val cashEffectRevision: Revision,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
) {
    init {
        require(environment == LocalSetupEnvironmentV1.LIVE)
        require(amount.minor >= 0)
    }
    override fun toString(): String = "LocalSetupOpeningFloatRowRecordV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningStockRowRefV1(
    public val sourceId: DomainId,
    public val receiptId: DomainId,
    public val rowId: DomainId,
    public val openingOperationId: OperationId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val quantity: Quantity,
    public val value: Money,
    public val stockEffectDigest: PayloadDigest,
    public val stockEffectRevision: Revision,
) {
    override fun toString(): String = "LocalSetupOpeningStockRowRefV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningFloatRowRefV1(
    public val sourceId: DomainId,
    public val sessionId: DomainId,
    public val rowId: DomainId,
    public val openingOperationId: OperationId,
    public val amount: Money,
    public val cashEffectDigest: PayloadDigest,
    public val cashEffectRevision: Revision,
) {
    override fun toString(): String = "LocalSetupOpeningFloatRowRefV1([REDACTED])"
}

@Serializable
public data class LocalSetupOpeningCheckpointV1(
    public val binding: LocalSetupOriginalBindingV1,
    public val environment: LocalSetupEnvironmentV1,
    public val stockChoice: LocalSetupOpeningChoiceV1,
    public val stockRowCount: Int,
    public val stockValueTotal: Money,
    public val floatChoice: LocalSetupOpeningChoiceV1,
    public val floatRowCount: Int,
    public val floatTotal: Money,
    public val stockManifestDigest: PayloadDigest? = null,
    public val stockInputManifestDigest: PayloadDigest? = null,
    public val floatManifestDigest: PayloadDigest? = null,
    public val floatInputManifestDigest: PayloadDigest? = null,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val operationId: OperationId,
    public val policyRevision: Revision,
    public val masterAuthorityEpoch: Revision,
) {
    init {
        require(environment == LocalSetupEnvironmentV1.LIVE)
        require(stockRowCount in 0..LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS)
        require(floatRowCount in 0..LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS)
        require(stockValueTotal.currency == Currency.EGP && floatTotal.currency == Currency.EGP)
        when (stockChoice) {
            LocalSetupOpeningChoiceV1.DECLARED ->
                require(stockRowCount > 0 && stockManifestDigest != null && stockInputManifestDigest != null)
            LocalSetupOpeningChoiceV1.EXPLICIT_ZERO -> require(stockRowCount == 0 &&
                stockManifestDigest == null && stockInputManifestDigest == null && stockValueTotal.minor == 0L)
        }
        when (floatChoice) {
            LocalSetupOpeningChoiceV1.DECLARED ->
                require(floatRowCount > 0 && floatManifestDigest != null && floatInputManifestDigest != null)
            LocalSetupOpeningChoiceV1.EXPLICIT_ZERO -> require(floatRowCount == 0 &&
                floatManifestDigest == null && floatInputManifestDigest == null && floatTotal.minor == 0L)
        }
    }
    override fun toString(): String = "LocalSetupOpeningCheckpointV1([REDACTED])"
}

@Serializable
public data class LocalSetupCheckpointProjectionV1(
    public val configuration: LocalSetupConfigurationRecordV1? = null,
    public val catalogImport: LocalSetupCatalogImportCheckpointV1? = null,
    public val opening: LocalSetupOpeningCheckpointV1? = null,
) {
    override fun toString(): String = "LocalSetupCheckpointProjectionV1([REDACTED])"
}

@Serializable
public data class LocalSetupCheckpointResultV1(
    public val code: String,
    public val checkpointId: DomainId,
    public val rowId: DomainId? = null,
    public val rowCount: Int = 0,
    public val manifestDigest: PayloadDigest? = null,
) {
    init {
        require(code in setOf(
            LocalSetupCheckpointProfileV1.CONFIGURED_CODE,
            LocalSetupCheckpointProfileV1.CATALOG_ROW_CODE,
            LocalSetupCheckpointProfileV1.CATALOG_SETTLED_CODE,
            LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW_CODE,
            LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW_CODE,
            LocalSetupCheckpointProfileV1.OPENING_SETTLED_CODE,
        ))
        require(rowCount in 0..(LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS * 2))
    }
    override fun toString(): String = "LocalSetupCheckpointResultV1([REDACTED])"
}

/** Public codecs. They encode only request/result DATA; the master derives all authority. */
public object LocalSetupCheckpointCodecV1 {
    public fun configure(operationId: OperationId, request: LocalSetupConfigurationRequestV1): MutationRequest =
        MutationRequest(operationId, LocalSetupCheckpointProfileV1.CONFIGURE, encode(request, LocalSetupConfigurationRequestV1.serializer()))

    public fun catalogImportRow(operationId: OperationId, request: LocalSetupCatalogImportRowRequestV1): MutationRequest =
        MutationRequest(operationId, LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW, encode(request, LocalSetupCatalogImportRowRequestV1.serializer()))

    public fun completeCatalogImport(operationId: OperationId, request: LocalSetupCatalogImportCompleteRequestV1): MutationRequest =
        MutationRequest(operationId, LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT, encode(request, LocalSetupCatalogImportCompleteRequestV1.serializer()))

    public fun openingStockRow(operationId: OperationId, request: LocalSetupOpeningStockRowRequestV1): MutationRequest =
        MutationRequest(operationId, LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW, encode(request, LocalSetupOpeningStockRowRequestV1.serializer()))

    public fun openingFloatRow(operationId: OperationId, request: LocalSetupOpeningFloatRowRequestV1): MutationRequest =
        MutationRequest(operationId, LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW, encode(request, LocalSetupOpeningFloatRowRequestV1.serializer()))

    public fun completeOpening(operationId: OperationId, request: LocalSetupOpeningCompleteRequestV1): MutationRequest =
        MutationRequest(operationId, LocalSetupCheckpointProfileV1.COMPLETE_OPENING, encode(request, LocalSetupOpeningCompleteRequestV1.serializer()))

    public fun read(installationId: DomainId): ReadRequest =
        ReadRequest(LocalSetupCheckpointProfileV1.READ, encode(LocalSetupCheckpointQueryV1(installationId), LocalSetupCheckpointQueryV1.serializer()))

    public fun decodeConfigurationRequest(value: StoredBytes): LocalSetupConfigurationRequestV1 =
        decode(value, LocalSetupConfigurationRequestV1.serializer())

    public fun decodeCatalogImportRowRequest(value: StoredBytes): LocalSetupCatalogImportRowRequestV1 =
        decode(value, LocalSetupCatalogImportRowRequestV1.serializer())

    public fun decodeCompleteCatalogImportRequest(value: StoredBytes): LocalSetupCatalogImportCompleteRequestV1 =
        decode(value, LocalSetupCatalogImportCompleteRequestV1.serializer())

    public fun decodeOpeningStockRowRequest(value: StoredBytes): LocalSetupOpeningStockRowRequestV1 =
        decode(value, LocalSetupOpeningStockRowRequestV1.serializer())

    public fun decodeOpeningFloatRowRequest(value: StoredBytes): LocalSetupOpeningFloatRowRequestV1 =
        decode(value, LocalSetupOpeningFloatRowRequestV1.serializer())

    public fun decodeCompleteOpeningRequest(value: StoredBytes): LocalSetupOpeningCompleteRequestV1 =
        decode(value, LocalSetupOpeningCompleteRequestV1.serializer())

    public fun encodeConfigurationRequest(value: LocalSetupConfigurationRequestV1): StoredBytes =
        encode(value, LocalSetupConfigurationRequestV1.serializer())

    public fun encodeCatalogImportRowRequest(value: LocalSetupCatalogImportRowRequestV1): StoredBytes =
        encode(value, LocalSetupCatalogImportRowRequestV1.serializer())

    public fun encodeCompleteCatalogImportRequest(value: LocalSetupCatalogImportCompleteRequestV1): StoredBytes =
        encode(value, LocalSetupCatalogImportCompleteRequestV1.serializer())

    public fun encodeOpeningStockRowRequest(value: LocalSetupOpeningStockRowRequestV1): StoredBytes =
        encode(value, LocalSetupOpeningStockRowRequestV1.serializer())

    public fun encodeOpeningFloatRowRequest(value: LocalSetupOpeningFloatRowRequestV1): StoredBytes =
        encode(value, LocalSetupOpeningFloatRowRequestV1.serializer())

    public fun encodeCompleteOpeningRequest(value: LocalSetupOpeningCompleteRequestV1): StoredBytes =
        encode(value, LocalSetupOpeningCompleteRequestV1.serializer())

    public fun encodeQuery(value: LocalSetupCheckpointQueryV1): StoredBytes =
        encode(value, LocalSetupCheckpointQueryV1.serializer())

    public fun decodeQuery(value: StoredBytes): LocalSetupCheckpointQueryV1 =
        decode(value, LocalSetupCheckpointQueryV1.serializer())

    public fun encodeConfigurationRecord(value: LocalSetupConfigurationRecordV1): StoredBytes =
        encode(value, LocalSetupConfigurationRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun decodeConfigurationRecord(value: StoredBytes): LocalSetupConfigurationRecordV1 =
        decode(value, LocalSetupConfigurationRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun encodeCatalogImportRow(value: LocalSetupCatalogImportRowRecordV1): StoredBytes =
        encode(value, LocalSetupCatalogImportRowRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun decodeCatalogImportRow(value: StoredBytes): LocalSetupCatalogImportRowRecordV1 =
        decode(value, LocalSetupCatalogImportRowRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun encodeCatalogImportCheckpoint(value: LocalSetupCatalogImportCheckpointV1): StoredBytes =
        encode(value, LocalSetupCatalogImportCheckpointV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun decodeCatalogImportCheckpoint(value: StoredBytes): LocalSetupCatalogImportCheckpointV1 =
        decode(value, LocalSetupCatalogImportCheckpointV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun encodeOpeningStockRow(value: LocalSetupOpeningStockRowRecordV1): StoredBytes =
        encode(value, LocalSetupOpeningStockRowRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun decodeOpeningStockRow(value: StoredBytes): LocalSetupOpeningStockRowRecordV1 =
        decode(value, LocalSetupOpeningStockRowRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun encodeOpeningFloatRow(value: LocalSetupOpeningFloatRowRecordV1): StoredBytes =
        encode(value, LocalSetupOpeningFloatRowRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun decodeOpeningFloatRow(value: StoredBytes): LocalSetupOpeningFloatRowRecordV1 =
        decode(value, LocalSetupOpeningFloatRowRecordV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun encodeOpeningCheckpoint(value: LocalSetupOpeningCheckpointV1): StoredBytes =
        encode(value, LocalSetupOpeningCheckpointV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun decodeOpeningCheckpoint(value: StoredBytes): LocalSetupOpeningCheckpointV1 =
        decode(value, LocalSetupOpeningCheckpointV1.serializer(), LocalSetupCheckpointProfileV1.MAX_RECORD_BYTES)

    public fun encodeResult(value: LocalSetupCheckpointResultV1): StoredBytes =
        encode(value, LocalSetupCheckpointResultV1.serializer())

    public fun decodeResult(value: StoredBytes): LocalSetupCheckpointResultV1 =
        decode(value, LocalSetupCheckpointResultV1.serializer())

    public fun encodeProjection(value: LocalSetupCheckpointProjectionV1): StoredBytes =
        encode(value, LocalSetupCheckpointProjectionV1.serializer(), LocalSetupCheckpointProfileV1.MAX_PROJECTION_BYTES)

    public fun decodeProjection(value: StoredBytes): LocalSetupCheckpointProjectionV1 =
        decode(value, LocalSetupCheckpointProjectionV1.serializer(), LocalSetupCheckpointProfileV1.MAX_PROJECTION_BYTES)

    public fun catalogInputManifestDigest(rows: List<LocalSetupCatalogImportManifestEntryV1>): PayloadDigest? {
        if (rows.isEmpty()) return null
        return sha256(rows.sortedBy { it.sourceId.value }.joinToString("\n", postfix = "\n") {
            "catalog-input/${it.sourceId.value}/${it.skuId.value}/${it.catalogOperationId.value}"
        }.encodeToByteArray())
    }

    public fun stockInputManifestDigest(rows: List<LocalSetupOpeningStockManifestEntryV1>): PayloadDigest? {
        if (rows.isEmpty()) return null
        return sha256(rows.sortedBy { it.sourceId.value }.joinToString("\n", postfix = "\n") {
            "opening-stock-input/${it.sourceId.value}/${it.receiptId.value}/${it.openingOperationId.value}"
        }.encodeToByteArray())
    }

    public fun floatInputManifestDigest(rows: List<LocalSetupOpeningFloatManifestEntryV1>): PayloadDigest? {
        if (rows.isEmpty()) return null
        return sha256(rows.sortedBy { it.sourceId.value }.joinToString("\n", postfix = "\n") {
            "opening-float-input/${it.sourceId.value}/${it.sessionId.value}/${it.openingOperationId.value}"
        }.encodeToByteArray())
    }

    public fun catalogManifestDigest(rows: List<LocalSetupCatalogImportRowRefV1>): PayloadDigest? {
        if (rows.isEmpty()) return null
        return sha256(rows.sortedBy { it.sourceId.value }.joinToString("\n", postfix = "\n") {
            "catalog/${it.sourceId.value}/${it.skuId.value}/${it.catalogOperationId.value}/" +
                "${it.catalogEffectRevision.value}/${it.catalogEffectDigest.value}"
        }.encodeToByteArray())
    }

    public fun stockManifestDigest(rows: List<LocalSetupOpeningStockRowRefV1>): PayloadDigest? {
        if (rows.isEmpty()) return null
        return sha256(rows.sortedBy { it.sourceId.value }.joinToString("\n", postfix = "\n") {
            "opening-stock/${it.sourceId.value}/${it.receiptId.value}/${it.openingOperationId.value}/" +
                "${it.skuId.value}/${it.warehouseId.value}/${it.quantity.unitId.value}/${it.quantity.scaled}/${it.quantity.scale}/" +
                "${it.value.minor}/${it.stockEffectRevision.value}/${it.stockEffectDigest.value}"
        }.encodeToByteArray())
    }

    public fun floatManifestDigest(rows: List<LocalSetupOpeningFloatRowRefV1>): PayloadDigest? {
        if (rows.isEmpty()) return null
        return sha256(rows.sortedBy { it.sourceId.value }.joinToString("\n", postfix = "\n") {
            "opening-float/${it.sourceId.value}/${it.sessionId.value}/${it.openingOperationId.value}/" +
                "${it.amount.minor}/${it.cashEffectRevision.value}/${it.cashEffectDigest.value}"
        }.encodeToByteArray())
    }

    private fun <T> encode(value: T, serializer: KSerializer<T>,
        maximum: Int = LocalSetupCheckpointProfileV1.MAX_REQUEST_BYTES): StoredBytes {
        val bytes = setupCheckpointJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
        try {
            require(bytes.size in 1..maximum) { "Local setup checkpoint capacity" }
            return StoredBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun <T> decode(bytes: StoredBytes, serializer: KSerializer<T>,
        maximum: Int = LocalSetupCheckpointProfileV1.MAX_REQUEST_BYTES): T {
        require(bytes.size in 1..maximum) { "Local setup checkpoint capacity" }
        val content = bytes.copyBytes()
        try {
            val text = content.decodeToString(throwOnInvalidSequence = true)
            val value = setupCheckpointJson.decodeFromString(serializer, text)
            require(setupCheckpointJson.encodeToString(serializer, value) == text) {
                "Noncanonical local setup checkpoint encoding"
            }
            return value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid local setup checkpoint encoding")
        } finally {
            content.fill(0)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal val setupCheckpointJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}
