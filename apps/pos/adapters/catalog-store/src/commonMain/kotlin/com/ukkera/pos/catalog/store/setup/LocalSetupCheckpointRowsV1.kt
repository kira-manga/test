package com.ukkera.pos.catalog.store.setup

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Retained input references for resuming a manifest; never a readiness or authority claim. */
@Serializable
public enum class LocalSetupCheckpointRowKindV1 { CATALOG, STOCK, FLOAT }

@Serializable
public data class LocalSetupCheckpointRowsQueryV1(
    public val installationId: DomainId,
    public val kind: LocalSetupCheckpointRowKindV1,
    public val afterSourceId: DomainId?,
) {
    override fun toString(): String = "LocalSetupCheckpointRowsQueryV1([REDACTED])"
}

/** resourceId is the SKU, opening receipt, or cash session selected by the page kind. */
@Serializable
public data class LocalSetupCheckpointRowReferenceV1(
    public val sourceId: DomainId,
    public val resourceId: DomainId,
    public val originalOperationId: OperationId,
) {
    override fun toString(): String = "LocalSetupCheckpointRowReferenceV1([REDACTED])"
}

@Serializable
public data class LocalSetupCheckpointRowsPageV1(
    public val binding: LocalSetupOriginalBindingV1,
    public val kind: LocalSetupCheckpointRowKindV1,
    public val rows: List<LocalSetupCheckpointRowReferenceV1>,
    public val nextSourceId: DomainId?,
) {
    init {
        require(rows.size <= LocalSetupCheckpointRowsV1.PAGE_SIZE)
        require(rows.map { it.sourceId.value }.let { it == it.distinct().sorted() })
        require(nextSourceId == null || nextSourceId == rows.lastOrNull()?.sourceId)
    }
    override fun toString(): String = "LocalSetupCheckpointRowsPageV1([REDACTED])"
}

/** Additive protected read; existing checkpoint request/projection and persisted bytes are unchanged. */
public object LocalSetupCheckpointRowsV1 {
    public val READ: ReadKey = ReadKey("local.setup.checkpoint-rows.read", 1)
    public const val PAGE_SIZE: Int = 64
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    public fun read(installationId: DomainId, kind: LocalSetupCheckpointRowKindV1,
        afterSourceId: DomainId? = null): ReadRequest = ReadRequest(READ,
        encodeQuery(LocalSetupCheckpointRowsQueryV1(installationId, kind, afterSourceId)))

    public fun encodeQuery(query: LocalSetupCheckpointRowsQueryV1): StoredBytes =
        encode(query, LocalSetupCheckpointRowsQueryV1.serializer())
    public fun decodeQuery(bytes: StoredBytes): LocalSetupCheckpointRowsQueryV1 =
        decode(bytes, LocalSetupCheckpointRowsQueryV1.serializer())
    public fun encodePage(page: LocalSetupCheckpointRowsPageV1): StoredBytes =
        encode(page, LocalSetupCheckpointRowsPageV1.serializer())
    public fun decodePage(bytes: StoredBytes): LocalSetupCheckpointRowsPageV1 =
        decode(bytes, LocalSetupCheckpointRowsPageV1.serializer())

    private fun <T> encode(value: T, serializer: KSerializer<T>): StoredBytes {
        val bytes = json.encodeToString(serializer, value).encodeToByteArray()
        require(bytes.size in 1..LocalSetupCheckpointProfileV1.MAX_PROJECTION_BYTES)
        return StoredBytes(bytes)
    }
    private fun <T> decode(bytes: StoredBytes, serializer: KSerializer<T>): T {
        val raw = bytes.copyBytes()
        require(raw.size in 1..LocalSetupCheckpointProfileV1.MAX_PROJECTION_BYTES)
        val value = json.decodeFromString(serializer, raw.decodeToString(throwOnInvalidSequence = true))
        require(encode(value, serializer) == bytes) { "Noncanonical setup row selection/projection" }
        return value
    }
}

internal class LocalSetupCheckpointRowsReadHandler : MasterReadHandler {
    override val key: ReadKey get() = LocalSetupCheckpointRowsV1.READ
    override fun normalize(input: StoredBytes): StoredBytes =
        LocalSetupCheckpointRowsV1.encodeQuery(LocalSetupCheckpointRowsV1.decodeQuery(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: ReadContext): PreparedRead {
        val query = LocalSetupCheckpointRowsV1.decodeQuery(normalized)
        val original = view.savedOriginalSetup() ?: throw MasterReadAuthorizationDenied()
        if (query.installationId != original.installationId) throw MasterReadAuthorizationDenied()
        val binding = LocalSetupOriginalBindingV1.of(original)
        fun requireBinding(actual: LocalSetupOriginalBindingV1, environment: LocalSetupEnvironmentV1) {
            check(actual == binding && environment == LocalSetupEnvironmentV1.LIVE) {
                "Retained setup row original binding is unavailable"
            }
        }
        // Reuse the live/history fail-closed scans, including each row's exact retained producer
        // write. Malformed rows must never disappear from a resumable manifest.
        val references = when (query.kind) {
            LocalSetupCheckpointRowKindV1.CATALOG ->
                checkNotNull(scanCatalogRows(view, context.scope, original.installationId)) { "Retained setup row provenance is unavailable" }
                    .map { (_, row) ->
                        requireBinding(row.binding, row.environment)
                        LocalSetupCheckpointRowReferenceV1(row.sourceId, row.skuId, row.catalogOperationId)
                    }
            LocalSetupCheckpointRowKindV1.STOCK ->
                checkNotNull(scanStockRows(view, context.scope, original.installationId)) { "Retained setup row provenance is unavailable" }
                    .map { (_, row) ->
                        requireBinding(row.binding, row.environment)
                        LocalSetupCheckpointRowReferenceV1(row.sourceId, row.receiptId, row.openingOperationId)
                    }
            LocalSetupCheckpointRowKindV1.FLOAT ->
                checkNotNull(scanFloatRows(view, context.scope, original.installationId)) { "Retained setup row provenance is unavailable" }
                    .map { (_, row) ->
                        requireBinding(row.binding, row.environment)
                        LocalSetupCheckpointRowReferenceV1(row.sourceId, row.sessionId, row.openingOperationId)
                    }
        }
        check(references.size <= LocalSetupCheckpointProfileV1.MAX_MANIFEST_ROWS &&
            references.map { it.sourceId }.distinct().size == references.size) {
            "Retained setup row set is unavailable"
        }
        val cursorExists = query.afterSourceId == null || references.any { it.sourceId == query.afterSourceId }
        val remaining = references.sortedBy { it.sourceId.value }
            .filter { query.afterSourceId == null || it.sourceId.value > query.afterSourceId.value }
        val pageRows = remaining.take(LocalSetupCheckpointRowsV1.PAGE_SIZE)
        val page = LocalSetupCheckpointRowsPageV1(binding, query.kind, pageRows,
            if (remaining.size > pageRows.size) pageRows.last().sourceId else null)
        val requirement = AuthorizationRequirement(
            Permission(SecurityAction.SETUP_CHECKPOINTS_READ, PermissionVerb.PERFORM), context.scope,
            ActionBinding(original.installationId, Revision(0), context.semanticDigest, null,
                LocalSetupCheckpointProfileV1.READ_REASON),
        )
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.single().isBoundTo(requirement))
                if (!cursorExists) throw MasterReadInvalidSelection()
                return LocalSetupCheckpointRowsV1.encodePage(page)
            }
        }
    }
}
