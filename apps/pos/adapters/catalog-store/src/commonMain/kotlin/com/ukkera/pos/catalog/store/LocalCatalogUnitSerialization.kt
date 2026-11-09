package com.ukkera.pos.catalog.store

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.QuantityScaleSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Adapter-owned wire representation of the existing immutable catalog unit value. */
public object LocalCatalogUnitSerializer : KSerializer<CatalogUnit> {
    override val descriptor: SerialDescriptor = CatalogUnitWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: CatalogUnit) {
        encoder.encodeSerializableValue(CatalogUnitWire.serializer(),
            CatalogUnitWire(value.id, value.code, value.dimension, value.maximumScale))
    }

    override fun deserialize(decoder: Decoder): CatalogUnit {
        val value = decoder.decodeSerializableValue(CatalogUnitWire.serializer())
        return CatalogUnit(value.id, value.code, value.dimension, value.maximumScale)
    }
}

@Serializable
private data class CatalogUnitWire(
    val id: DomainId,
    val code: Code,
    @Serializable(with = UnitDimensionSerializer::class) val dimension: UnitDimension,
    @Serializable(with = QuantityScaleSerializer::class) val maximumScale: Int,
)

private object UnitDimensionSerializer : KSerializer<UnitDimension> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalCatalogUnitDimensionV1", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UnitDimension) { encoder.encodeString(value.name) }

    override fun deserialize(decoder: Decoder): UnitDimension {
        val name = if (decoder is JsonDecoder) {
            val value = decoder.decodeJsonElement()
            if (value !is JsonPrimitive || !value.isString) throw SerializationException("Unit dimension must be an exact name")
            value.content
        } else decoder.decodeString()
        return UnitDimension.entries.singleOrNull { it.name == name }
            ?: throw SerializationException("Unsupported unit dimension")
    }
}
