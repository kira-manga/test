package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Supplier-directory reference support, alongside the unchanged configuration V1 contracts. */
public object LocalSupplierProfileV2 {
    public val COMMAND: CommandKey = CommandKey("local.configuration.write", 2)
    public val QUERY: ReadKey = ReadKey("local.configuration.read", 2)
    public const val SCHEMA: String = "local.purchasing.supplier/v2"
    /** Canonical UTF-8 bytes of the complete page, including continuation and detail. */
    public const val MAXIMUM_PAGE_BYTES: Int = 65_536
}

/** A null reference is explicit absence, not an ID/name-derived reference or an omitted edit. */
@Serializable
public data class LocalSupplierV2(
    public val id: DomainId,
    public val names: LocalNamesV1,
    @Serializable(with = SupplierBooleanV2Serializer::class)
    public val active: Boolean,
    public val reference: Code?,
)

/** Full-state supplier edit: a null reference clears the recorded reference on an update. */
@Serializable
public data class LocalSupplierRequestV2(
    public val expectedStorageRevision: Revision?,
    public val supplier: LocalSupplierV2,
)

/** The limit is a row maximum; the byte bound can produce a smaller continued directory page. */
@Serializable
public data class LocalSupplierQueryV2(
    public val id: DomainId? = null,
    public val after: DomainId? = null,
    public val limit: Int = 50,
) {
    init { require(limit in 1..50 && (id == null || after == null)) }
}

/** The reference belongs to this original result, not a later directory read. */
@Serializable
public data class LocalSupplierResultV2(
    public val code: String,
    public val id: DomainId?,
    public val revision: Revision?,
    public val reference: Code?,
)

@Serializable
public data class LocalSupplierListItemV2(
    public val id: DomainId,
    public val revision: Revision,
    public val storageRevision: Revision,
    public val names: LocalNamesV1,
    @Serializable(with = SupplierBooleanV2Serializer::class)
    public val active: Boolean,
    public val reference: Code?,
)

/** Whole values only. A continuation is the last emitted ID, never a skipped unseen row. */
@Serializable
public data class LocalSupplierPageV2(
    public val items: List<LocalSupplierListItemV2>,
    public val nextAfter: DomainId?,
    public val detail: LocalSupplierV2? = null,
)

public object LocalSupplierCodecV2 {
    public fun encode(request: LocalSupplierRequestV2): StoredBytes =
        encodeValue(request, LocalSupplierRequestV2.serializer())
    public fun decode(request: StoredBytes): LocalSupplierRequestV2 =
        decodeValue(request, LocalSupplierRequestV2.serializer())
    public fun query(request: LocalSupplierQueryV2): StoredBytes =
        encodeValue(request, LocalSupplierQueryV2.serializer())
    public fun decodeQuery(request: StoredBytes): LocalSupplierQueryV2 =
        decodeValue(request, LocalSupplierQueryV2.serializer())
    public fun decodeResult(result: StoredBytes): LocalSupplierResultV2 =
        decodeValue(result, LocalSupplierResultV2.serializer())
    public fun decodePage(result: StoredBytes): LocalSupplierPageV2 =
        decodeValue(result, LocalSupplierPageV2.serializer())
}

/** JSON's ordinary Boolean decoder accepts quoted text even with coercion disabled.
 * V2 requires a Boolean token; the existing V1 codec and bytes are unchanged. */
private object SupplierBooleanV2Serializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("SupplierBooleanV2", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()
        val value = decoder.decodeJsonElement()
        if (value !is JsonPrimitive || value.isString) throw SerializationException("Supplier active state must be a Boolean")
        return value.booleanOrNull ?: throw SerializationException("Supplier active state must be a Boolean")
    }
}
