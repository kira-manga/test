package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * UI-facing installed setup closure grammar. These are presentation/data-transfer types only:
 * no request body here is authority, and the adapter maps the typed values to the public
 * production codecs while the master rechecks the saved original, grants and current state.
 */
/** Mirrors the installed checkpoint manifest bound without importing the adapter in the UI. */
const val LOCAL_SETUP_MAX_MANIFEST_ROWS: Int = 1024

enum class LocalSetupClosureAvailability { AVAILABLE, NOT_CONFIGURED, NOT_IMPLEMENTED }

enum class LocalSetupPrerequisiteKind {
    NATIVE_INSTALLATION_SETTLEMENT,
    REQUIRED_CONFIGURATION,
    CATALOG_IMPORT_SETTLEMENT,
    OPENING_SETTLEMENT,
}

data class LocalSetupPrerequisiteView(
    val kind: LocalSetupPrerequisiteKind,
    val settled: Boolean,
    val binding: String,
)

/** Protected saved-original projection. Digests are display identifiers, not signatures. */
data class LocalSetupOriginalView(
    val installationId: DomainId,
    val assignmentId: DomainId,
    val deviceId: DomainId,
    val firstActorId: DomainId,
    val originalRequestId: DomainId,
    val issuedAttemptId: DomainId,
    val attemptSequence: Long,
    val definitionDigest: String,
    val genesisDigest: String,
    val receiptDigest: String,
    val installationAuthorityEpoch: Revision,
    val currentAuthorityEpoch: Revision,
    val installedAt: UtcInstant,
    val nativeSettlement: Boolean,
)

data class LocalSetupAcknowledgementView(
    val actorId: DomainId,
    val acknowledgedAt: UtcInstant,
    val operationId: OperationId,
    val policyRevision: Revision,
)

data class LocalSetupGoLiveView(
    val actorId: DomainId,
    val occurredAt: UtcInstant,
    val operationId: OperationId,
    val policyRevision: Revision,
)

data class LocalSetupResultView(
    val original: LocalSetupOriginalView,
    val acknowledgement: LocalSetupAcknowledgementView?,
    val goLive: LocalSetupGoLiveView?,
    val prerequisites: List<LocalSetupPrerequisiteView>,
)

data class LocalSetupConfigurationView(
    val revision: Revision,
    val activities: List<String>,
    val currency: String,
    val timeZone: String,
    val businessDayCutoffMinute: Int,
    val legalName: String,
    val taxRegistrationId: String?,
    val taxRegistrationAbsent: Boolean,
    val actorId: DomainId,
    val recordedAt: UtcInstant,
    val operationId: OperationId,
)

data class LocalSetupCatalogImportView(
    val explicitNoImport: Boolean,
    val rowCount: Int,
    val manifestDigest: String?,
    val inputManifestDigest: String?,
    val actorId: DomainId,
    val recordedAt: UtcInstant,
    val operationId: OperationId,
)

data class LocalSetupOpeningView(
    val stockExplicitZero: Boolean,
    val stockRowCount: Int,
    val stockValueTotalMinor: Long,
    val floatExplicitZero: Boolean,
    val floatRowCount: Int,
    val floatTotalMinor: Long,
    val stockManifestDigest: String?,
    val floatManifestDigest: String?,
    val currency: String,
    val actorId: DomainId,
    val recordedAt: UtcInstant,
    val operationId: OperationId,
)

data class LocalSetupCheckpointView(
    val configuration: LocalSetupConfigurationView?,
    val catalogImport: LocalSetupCatalogImportView?,
    val opening: LocalSetupOpeningView?,
    val retainedRowsLoaded: Boolean = false,
    val retainedCatalogRows: List<LocalSetupCatalogRow> = emptyList(),
    val retainedStockRows: List<LocalSetupOpeningStockRow> = emptyList(),
    val retainedFloatRows: List<LocalSetupOpeningFloatRow> = emptyList(),
)

/** Explicit configuration values. The adapter maps these to the installed production codec. */
data class LocalSetupConfigurationInput(
    val expectedRevision: Revision,
    val currencyCode: String,
    val timeZone: String,
    val businessDayCutoffMinute: Int,
    val legalName: String,
    val taxRegistrationId: String?,
    val taxRegistrationAbsent: Boolean,
) {
    init {
        require(currencyCode == "EGP") { "Release one admits EGP only" }
        require(timeZone.isNotBlank() && timeZone.length <= 64)
        require(businessDayCutoffMinute in 0..1439)
        require(legalName.isNotBlank() && legalName.length <= 200)
        require((taxRegistrationId != null) xor taxRegistrationAbsent) {
            "Confirm either the exact tax registration id or its explicit absence"
        }
    }
    override fun toString(): String = "LocalSetupConfigurationInput([REDACTED])"
}

data class LocalSetupCatalogRow(
    val sourceId: DomainId,
    val skuId: DomainId,
    val catalogOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupCatalogRow([REDACTED])"
}

data class LocalSetupOpeningStockRow(
    val sourceId: DomainId,
    val receiptId: DomainId,
    val openingOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupOpeningStockRow([REDACTED])"
}

data class LocalSetupOpeningFloatRow(
    val sourceId: DomainId,
    val sessionId: DomainId,
    val openingOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupOpeningFloatRow([REDACTED])"
}

data class LocalSetupCatalogCompletionInput(
    val explicitNoImport: Boolean,
    val rows: List<LocalSetupCatalogRow>,
) {
    init {
        require(explicitNoImport == rows.isEmpty()) {
            "An explicit no-import choice is distinct from an empty declared manifest"
        }
        require(rows.size <= LOCAL_SETUP_MAX_MANIFEST_ROWS)
        require(rows.distinctBy { it.sourceId }.size == rows.size)
    }
    override fun toString(): String = "LocalSetupCatalogCompletionInput([REDACTED])"
}

data class LocalSetupOpeningCompletionInput(
    val stockExplicitZero: Boolean,
    val stockRows: List<LocalSetupOpeningStockRow>,
    val floatExplicitZero: Boolean,
    val floatRows: List<LocalSetupOpeningFloatRow>,
) {
    init {
        require(stockExplicitZero == stockRows.isEmpty()) {
            "An explicit zero stock choice is distinct from a declared stock manifest"
        }
        require(floatExplicitZero == floatRows.isEmpty()) {
            "An explicit zero float choice is distinct from a declared float manifest"
        }
        require(stockRows.size <= LOCAL_SETUP_MAX_MANIFEST_ROWS && floatRows.size <= LOCAL_SETUP_MAX_MANIFEST_ROWS)
        require(stockRows.distinctBy { it.sourceId }.size == stockRows.size)
        require(floatRows.distinctBy { it.sourceId }.size == floatRows.size)
    }
    override fun toString(): String = "LocalSetupOpeningCompletionInput([REDACTED])"
}

data class LocalSetupManifestPreview(val rowCount: Int, val digest: String?) {
    init { require(rowCount in 0..LOCAL_SETUP_MAX_MANIFEST_ROWS) }
}

data class LocalSetupOpeningManifestPreview(
    val stock: LocalSetupManifestPreview,
    val float: LocalSetupManifestPreview,
)

enum class LocalSetupOperationKind {
    ACKNOWLEDGE,
    GO_LIVE,
    CONFIGURATION,
    CATALOG_IMPORT_ROW,
    CATALOG_IMPORT_COMPLETE,
    OPENING_STOCK_ROW,
    OPENING_FLOAT_ROW,
    OPENING_COMPLETE,
    UNKNOWN,
}

sealed interface LocalSetupReadResult<out T> {
    data class Available<T>(val value: T) : LocalSetupReadResult<T>
    data class Unavailable(val problem: LocalServiceProblem) : LocalSetupReadResult<Nothing>
}

sealed interface LocalSetupMutationResult {
    data class Recorded(
        val operationId: OperationId,
        val kind: LocalSetupOperationKind,
        val committed: Boolean,
        val code: String,
        val rowId: DomainId? = null,
        val rowCount: Int = 0,
        val manifestDigest: String? = null,
        val catalogRow: LocalSetupCatalogRow? = null,
        val stockRow: LocalSetupOpeningStockRow? = null,
        val floatRow: LocalSetupOpeningFloatRow? = null,
        /** Exact submitted input fingerprints from the retained request, not the result-effect digest. */
        val catalogCompletion: LocalSetupManifestPreview? = null,
        val openingCompletion: LocalSetupOpeningManifestPreview? = null,
    ) : LocalSetupMutationResult

    /** No terminal result was observed. The caller must recover the same original operation ID. */
    data class Pending(val operationId: OperationId, val kind: LocalSetupOperationKind) : LocalSetupMutationResult
    /** A definitive pre-staging refusal: no journal original exists for this operation ID. */
    data class NotStaged(
        val operationId: OperationId,
        val problem: LocalServiceProblem,
        val kind: LocalSetupOperationKind = LocalSetupOperationKind.UNKNOWN,
    ) : LocalSetupMutationResult
    data class Unresolved(
        val operationId: OperationId,
        val problem: LocalServiceProblem,
        val kind: LocalSetupOperationKind = LocalSetupOperationKind.UNKNOWN,
    ) : LocalSetupMutationResult
}

/** A retained setup original still owned by this client journal. */
data class LocalSetupPendingView(
    val operationId: OperationId,
    val kind: LocalSetupOperationKind,
    val phase: String,
    val recorded: Boolean,
    val recordedCode: String?,
)

/**
 * Session-owned installed setup closure port. It is available only for the actual bound full
 * branch connection with the installed registry; absent providers and restricted directory
 * profiles return an explicit unavailable state. Every read and mutation reauthorizes through
 * the current master session. Implementations own durable original staging and same-ID recovery.
 */
interface LocalSetupClosureUiGateway {
    fun availability(): LocalSetupClosureAvailability
    suspend fun readResult(): LocalSetupReadResult<LocalSetupResultView>
    suspend fun readCheckpoints(): LocalSetupReadResult<LocalSetupCheckpointView>
    suspend fun pending(): LocalSetupReadResult<List<LocalSetupPendingView>>
    suspend fun acknowledge(operationId: OperationId): LocalSetupMutationResult
    suspend fun goLive(operationId: OperationId): LocalSetupMutationResult
    suspend fun configure(operationId: OperationId, input: LocalSetupConfigurationInput): LocalSetupMutationResult
    suspend fun recordCatalogRow(operationId: OperationId, row: LocalSetupCatalogRow): LocalSetupMutationResult
    suspend fun completeCatalogImport(operationId: OperationId, input: LocalSetupCatalogCompletionInput): LocalSetupMutationResult
    suspend fun recordOpeningStockRow(operationId: OperationId, row: LocalSetupOpeningStockRow): LocalSetupMutationResult
    suspend fun recordOpeningFloatRow(operationId: OperationId, row: LocalSetupOpeningFloatRow): LocalSetupMutationResult
    suspend fun completeOpening(operationId: OperationId, input: LocalSetupOpeningCompletionInput): LocalSetupMutationResult
    suspend fun lookup(operationId: OperationId): LocalSetupMutationResult
    /** Dispatch the exact retained body only if no result exists; never a fresh operation ID. */
    suspend fun resume(operationId: OperationId): LocalSetupMutationResult
    suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult
    fun catalogManifestPreview(rows: List<LocalSetupCatalogRow>): LocalSetupManifestPreview
    fun openingManifestPreview(stock: List<LocalSetupOpeningStockRow>, float: List<LocalSetupOpeningFloatRow>): LocalSetupOpeningManifestPreview
}

object UnavailableLocalSetupClosure : LocalSetupClosureUiGateway {
    override fun availability(): LocalSetupClosureAvailability = LocalSetupClosureAvailability.NOT_CONFIGURED
    override suspend fun readResult(): LocalSetupReadResult<LocalSetupResultView> =
        LocalSetupReadResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun readCheckpoints(): LocalSetupReadResult<LocalSetupCheckpointView> =
        LocalSetupReadResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun pending(): LocalSetupReadResult<List<LocalSetupPendingView>> =
        LocalSetupReadResult.Unavailable(LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun acknowledge(operationId: OperationId): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.ACKNOWLEDGE)
    override suspend fun goLive(operationId: OperationId): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.GO_LIVE)
    override suspend fun configure(operationId: OperationId, input: LocalSetupConfigurationInput): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.CONFIGURATION)
    override suspend fun recordCatalogRow(operationId: OperationId, row: LocalSetupCatalogRow): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.CATALOG_IMPORT_ROW)
    override suspend fun completeCatalogImport(operationId: OperationId, input: LocalSetupCatalogCompletionInput): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE)
    override suspend fun recordOpeningStockRow(operationId: OperationId, row: LocalSetupOpeningStockRow): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.OPENING_STOCK_ROW)
    override suspend fun recordOpeningFloatRow(operationId: OperationId, row: LocalSetupOpeningFloatRow): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.OPENING_FLOAT_ROW)
    override suspend fun completeOpening(operationId: OperationId, input: LocalSetupOpeningCompletionInput): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED, LocalSetupOperationKind.OPENING_COMPLETE)
    override suspend fun lookup(operationId: OperationId): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun resume(operationId: OperationId): LocalSetupMutationResult =
        LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.NOT_CONFIGURED)
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult =
        LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.NOT_CONFIGURED)
    override fun catalogManifestPreview(rows: List<LocalSetupCatalogRow>): LocalSetupManifestPreview =
        LocalSetupManifestPreview(rows.size, null)
    override fun openingManifestPreview(stock: List<LocalSetupOpeningStockRow>, float: List<LocalSetupOpeningFloatRow>): LocalSetupOpeningManifestPreview =
        LocalSetupOpeningManifestPreview(LocalSetupManifestPreview(stock.size, null), LocalSetupManifestPreview(float.size, null))
}
