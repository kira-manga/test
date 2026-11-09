package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.LocalSignedSerialRegistrationV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Separate employee activation of a completed native original; existing stock formats stay unchanged. */
public object LocalSerialRegistrationProfileV1 {
    public val ACTIVATE: CommandKey = CommandKey("local.catalog.serial-registration.activate", 1)
    public val READ: ReadKey = ReadKey("local.catalog.serial-registration", 1)
    public const val ACTIVE: String = "serial_registration_active"
    public const val PUBLICATION: String = "serial_registration_publication"
    public const val MAX_INPUT_BYTES: Int = 64 * 1024
    public const val MAX_RECORD_BYTES: Int = 64 * 1024
    public const val MAX_QUERY_BYTES: Int = 4 * 1024
    public fun schema(kind: String): String = "$kind/v1"
}

@Serializable
public data class LocalActivateSerialRegistrationV1(
    public val publication: LocalSignedSerialRegistrationV1,
    public val expectedActiveStorageRevision: Revision?,
) {
    init { require(expectedActiveStorageRevision == null || expectedActiveStorageRevision.value >= 1) }
    override fun toString(): String = "LocalActivateSerialRegistrationV1([REDACTED])"
}

/** Exact protected identity lookup; no unauthenticated serial/code enumeration. */
@Serializable
public data class LocalSerialRegistrationQueryV1(
    public val registrationId: DomainId,
    public val publicationId: DomainId? = null,
) {
    override fun toString(): String = "LocalSerialRegistrationQueryV1([REDACTED])"
}

@Serializable
public data class LocalSerialRegistrationResultV1(
    public val code: String,
    public val registrationId: DomainId,
    public val publicationId: DomainId,
    public val storageRevision: Revision?,
) {
    init { require(storageRevision == null || storageRevision.value >= 1) }
    override fun toString(): String = "LocalSerialRegistrationResultV1([REDACTED])"
}

/** An activated original remains retained when it no longer qualifies a fresh consumer. */
@Serializable
public enum class LocalSerialRegistrationQualificationV1 {
    NOT_ACTIVATED,
    QUALIFIED,
    TIME_UNAVAILABLE,
    OUTSIDE_INTERVAL,
    SKU_SOURCE_CHANGED,
}

/**
 * Proved branch activation and current eligibility are distinct. Native/source uncertainty
 * is a failed protected read, never NOT_ACTIVATED or an empty historical default.
 */
@Serializable
public data class LocalSerialRegistrationReadReplyV1(
    public val registrationId: DomainId,
    public val activeStorageRevision: Revision?,
    public val active: LocalSignedSerialRegistrationV1?,
    public val qualification: LocalSerialRegistrationQualificationV1,
    public val publication: LocalSignedSerialRegistrationV1? = null,
) {
    init {
        require(activeStorageRevision == null || activeStorageRevision.value >= 1)
        require((active == null) == (activeStorageRevision == null))
        require((qualification == LocalSerialRegistrationQualificationV1.NOT_ACTIVATED) == (active == null))
        require(active == null || active.definition.registrationId == registrationId)
        require(publication == null || publication.definition.registrationId == registrationId)
    }
    override fun toString(): String = "LocalSerialRegistrationReadReplyV1([REDACTED])"
}

/** Strict additive command/read bytes. The native signed publication retains its original codec. */
public object LocalSerialRegistrationCodecV1 {
    public fun activate(operationId: OperationId, request: LocalActivateSerialRegistrationV1): MutationRequest =
        MutationRequest(operationId, LocalSerialRegistrationProfileV1.ACTIVATE, encode(request))
    public fun encode(request: LocalActivateSerialRegistrationV1): StoredBytes =
        serialRegistrationEncode(request, LocalActivateSerialRegistrationV1.serializer(), LocalSerialRegistrationProfileV1.MAX_INPUT_BYTES)
    public fun decode(bytes: StoredBytes): LocalActivateSerialRegistrationV1 =
        serialRegistrationDecode(bytes, LocalActivateSerialRegistrationV1.serializer(), LocalSerialRegistrationProfileV1.MAX_INPUT_BYTES)
    public fun query(request: LocalSerialRegistrationQueryV1): ReadRequest = ReadRequest(LocalSerialRegistrationProfileV1.READ,
        serialRegistrationEncode(request, LocalSerialRegistrationQueryV1.serializer(), LocalSerialRegistrationProfileV1.MAX_QUERY_BYTES))
    public fun decodeQuery(bytes: StoredBytes): LocalSerialRegistrationQueryV1 =
        serialRegistrationDecode(bytes, LocalSerialRegistrationQueryV1.serializer(), LocalSerialRegistrationProfileV1.MAX_QUERY_BYTES)
    public fun encodeResult(value: LocalSerialRegistrationResultV1): StoredBytes =
        serialRegistrationEncode(value, LocalSerialRegistrationResultV1.serializer())
    public fun decodeResult(bytes: StoredBytes): LocalSerialRegistrationResultV1 =
        serialRegistrationDecode(bytes, LocalSerialRegistrationResultV1.serializer())
    public fun encodeRead(value: LocalSerialRegistrationReadReplyV1): StoredBytes =
        serialRegistrationEncode(value, LocalSerialRegistrationReadReplyV1.serializer())
    public fun decodeRead(bytes: StoredBytes): LocalSerialRegistrationReadReplyV1 =
        serialRegistrationDecode(bytes, LocalSerialRegistrationReadReplyV1.serializer())
}

internal val serialRegistrationJson: Json = Json {
    encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false
}
internal fun <T> serialRegistrationEncode(value: T, serializer: KSerializer<T>,
    maximum: Int = LocalSerialRegistrationProfileV1.MAX_RECORD_BYTES): StoredBytes {
    val text = serialRegistrationJson.encodeToString(serializer, value)
    require(text.length in 1..maximum)
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    return try { require(bytes.size in 1..maximum); StoredBytes(bytes) } finally { bytes.fill(0) }
}
internal fun <T> serialRegistrationDecode(bytes: StoredBytes, serializer: KSerializer<T>,
    maximum: Int = LocalSerialRegistrationProfileV1.MAX_RECORD_BYTES): T {
    require(bytes.size in 1..maximum)
    val value = bytes.copyBytes()
    return try {
        val text = value.decodeToString(throwOnInvalidSequence = true)
        requireUnambiguousSerialJson(text)
        serialRegistrationJson.decodeFromString(serializer, text)
    }
    finally { value.fill(0) }
}

/** Reject repeated decoded member names before DTO deserialization can overwrite them.
 * The existing byte bound applies before this iterative scan; full grammar/type validation
 * remains the strict DTO decoder's responsibility. Ordinary member ordering is unchanged. */
private fun requireUnambiguousSerialJson(text: String) {
    val containers = ArrayDeque<SerialJsonContainer>()
    var index = 0
    while (index < text.length) {
        when (val character = text[index]) {
            '{', '[' -> containers.addLast(SerialJsonContainer(character,
                if (character == '{') mutableSetOf() else null))
            '}', ']' -> {
                require(containers.isNotEmpty())
                val container = containers.removeLast()
                require(container.opening == if (character == '}') '{' else '[')
            }
            '"' -> {
                val start = index
                var closed = false
                index++
                while (index < text.length) {
                    when (text[index]) {
                        '\\' -> index += 2
                        '"' -> { index++; closed = true; break }
                        else -> index++
                    }
                }
                require(closed)
                var next = index
                while (next < text.length && text[next] in " \t\r\n") next++
                val names = containers.lastOrNull()?.names
                if (next < text.length && text[next] == ':' && names != null) {
                    val name = serialRegistrationJson.decodeFromString(String.serializer(), text.substring(start, index))
                    require(names.add(name)) { "Duplicate JSON member" }
                }
                continue
            }
        }
        index++
    }
    require(containers.isEmpty())
}

private class SerialJsonContainer(val opening: Char, val names: MutableSet<String>?)
