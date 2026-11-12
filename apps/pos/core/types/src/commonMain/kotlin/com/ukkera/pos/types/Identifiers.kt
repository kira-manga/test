package com.ukkera.pos.types

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Exact ASCII UUID spelling only; version/variant semantics remain unchanged. */
private fun isCanonicalUuid(value: String): Boolean {
    if (value.length != 36) return false
    for (index in value.indices) {
        val character = value[index]
        when (index) {
            8, 13, 18, 23 -> if (character != '-') return false
            else -> if (character !in '0'..'9' && character !in 'a'..'f') return false
        }
    }
    return true
}

@Serializable(with = DomainIdSerializer::class)
@JvmInline
public value class DomainId(public val value: String) {
    init {
        require(isCanonicalUuid(value)) { "Domain ID must be a canonical lowercase UUID" }
    }

    public companion object {
        /** CSPRNG-backed UUIDv4, independent of clocks. An identifier, not a secret/token. */
        public fun random(): DomainId = DomainId(Uuid.random().toString())
    }
}

/** Distinct from document ID, transport request ID and master epoch. */
@Serializable(with = OperationIdSerializer::class)
@JvmInline
public value class OperationId(public val value: String) {
    init {
        require(isCanonicalUuid(value)) { "Operation ID must be a canonical lowercase UUID" }
    }

    public companion object {
        /** The initiating application must durably save this before its first submission. */
        public fun random(): OperationId = OperationId(Uuid.random().toString())
    }
}

public object DomainIdSerializer : KSerializer<DomainId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DomainId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DomainId): Unit = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): DomainId = decodeScalar(decoder, "DomainId", 36, ::DomainId)
}

public object OperationIdSerializer : KSerializer<OperationId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OperationId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OperationId): Unit = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): OperationId = decodeScalar(decoder, "OperationId", 36, ::OperationId)
}

@Serializable(with = RevisionSerializer::class)
public data class Revision(
    public val value: Long,
) : Comparable<Revision> {
    init {
        require(value >= 0) { "Revision cannot be negative" }
    }

    public fun next(): Revision {
        if (value == Long.MAX_VALUE) throw ArithmeticException("Revision exhausted")
        return Revision(value + 1)
    }

    override fun compareTo(other: Revision): Int = value.compareTo(other.value)
}

/** Ordering within its explicitly recorded journal/authority scope, never derived from wall time. */
@Serializable(with = JournalSequenceSerializer::class)
public data class JournalSequence(public val value: Long) : Comparable<JournalSequence> {
    init {
        require(value >= 0) { "Journal sequence cannot be negative" }
    }

    public fun next(): JournalSequence {
        if (value == Long.MAX_VALUE) throw ArithmeticException("Journal sequence exhausted")
        return JournalSequence(value + 1)
    }

    override fun compareTo(other: JournalSequence): Int = value.compareTo(other.value)
}

/** Counters are scalar decimal strings, not JSON objects or floating point numbers. */
public object RevisionSerializer : KSerializer<Revision> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Revision", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Revision): Unit =
        MinorUnitsSerializer.serialize(encoder, value.value)

    override fun deserialize(decoder: Decoder): Revision {
        val value = MinorUnitsSerializer.deserialize(decoder)
        if (value < 0) throw SerializationException("Revision cannot be negative")
        return Revision(value)
    }
}

public object JournalSequenceSerializer : KSerializer<JournalSequence> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JournalSequence", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JournalSequence): Unit =
        MinorUnitsSerializer.serialize(encoder, value.value)

    override fun deserialize(decoder: Decoder): JournalSequence {
        val value = MinorUnitsSerializer.deserialize(decoder)
        if (value < 0) throw SerializationException("Journal sequence cannot be negative")
        return JournalSequence(value)
    }
}
