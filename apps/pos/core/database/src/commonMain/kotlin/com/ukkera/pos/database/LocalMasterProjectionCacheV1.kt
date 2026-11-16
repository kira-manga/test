package com.ukkera.pos.database

import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json

/**
 * Bounded inert Client cache encoding. Decoding never authenticates a Master or employee,
 * authorizes disclosure, or proves that the cached projection is still current. Native
 * protected storage must atomically replace each projection with its exact cursor, and a
 * current protected read must qualify any later exposure. No credentials are accepted here.
 */
public object LocalMasterProjectionCacheV1 {
    private const val MAX_CURSOR_BYTES = 16_384
    private const val MAX_TIME_BYTES = 64
    private const val MAX_DOMAIN_BYTES = 32

    /** Includes the four length-prefixed fields; checked before decoding or allocating fields. */
    public const val MAX_ENCODED_BYTES: Int = MAX_STORED_BYTES + MAX_CURSOR_BYTES + MAX_TIME_BYTES + MAX_DOMAIN_BYTES + 16

    private val domain = "ukkera.lan.projection/v1".encodeToByteArray()
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    /** A defensive payload copy after checking its exact inert cursor/revision pairing. */
    public fun copy(snapshot: LocalMasterChangesReplyV1.Snapshot): LocalMasterChangesReplyV1.Snapshot =
        rehydrate(snapshot.data.payload, snapshot.data.branchRevision, snapshot.data.observedAt, snapshot.cursor)

    /**
     * Reconstructs presentation DATA only. Invalid or mismatched bounded inputs are refused;
     * a successful return supplies no access grant and must not unlock a cached screen.
     */
    public fun rehydrate(payload: StoredBytes, branchRevision: Revision, observedAt: UtcInstant,
        cursor: LocalMasterChangeCursorV1): LocalMasterChangesReplyV1.Snapshot {
        require(branchRevision == cursor.branchRevision) { "Projection revision differs from its cursor" }
        require(sha256(payload.bytes()) == cursor.projectionDigest) { "Projection digest differs from its cursor" }
        requireEvidenceV1Time(observedAt)
        require(observedAt.canonicalString().length <= MAX_TIME_BYTES) { "Projection time exceeds its bound" }
        return LocalMasterChangesReplyV1.Snapshot(
            ReadReply.Data(StoredBytes(payload.bytes()), branchRevision, observedAt), cursor.copy())
    }

    /** New versioned cache envelope only; does not change original journal or intent formats. */
    public fun encode(snapshot: LocalMasterChangesReplyV1.Snapshot): ByteArray {
        val owned = copy(snapshot)
        val cursor = json.encodeToString(LocalMasterChangeCursorV1.serializer(), owned.cursor)
            .encodeToByteArray(throwOnInvalidSequence = true)
        require(cursor.size in 1..MAX_CURSOR_BYTES) { "Projection cursor exceeds its bound" }
        val time = owned.data.observedAt.canonicalString().encodeToByteArray(throwOnInvalidSequence = true)
        require(time.size in 1..MAX_TIME_BYTES) { "Projection time exceeds its bound" }
        return frame(listOf(domain, cursor, time, owned.data.payload.copyBytes())).also {
            require(it.size <= MAX_ENCODED_BYTES) { "Projection cache exceeds its bound" }
        }
    }

    /**
     * Bounded, exact version/field decoding. Malformed, oversized, trailing, noncanonical or
     * internally inconsistent DATA throws [IllegalArgumentException], with no partial reply.
     */
    public fun decode(bytes: ByteArray): LocalMasterChangesReplyV1.Snapshot {
        require(bytes.size in 16..MAX_ENCODED_BYTES) { "Invalid projection cache size" }
        return try {
            var offset = 0
            fun field(maximum: Int): ByteArray {
                require(bytes.size - offset >= 4)
                var count = 0
                repeat(4) { count = (count shl 8) or (bytes[offset++].toInt() and 255) }
                require(count in 0..maximum && count <= bytes.size - offset)
                return bytes.copyOfRange(offset, offset + count).also { offset += count }
            }
            require(field(MAX_DOMAIN_BYTES).contentEquals(domain))
            val encodedCursor = field(MAX_CURSOR_BYTES)
            require(encodedCursor.isNotEmpty())
            val cursor = json.decodeFromString(LocalMasterChangeCursorV1.serializer(),
                encodedCursor.decodeToString(throwOnInvalidSequence = true))
            // Reject duplicate fields and alternate spellings as well as unknown fields.
            require(json.encodeToString(LocalMasterChangeCursorV1.serializer(), cursor)
                .encodeToByteArray(throwOnInvalidSequence = true).contentEquals(encodedCursor))
            val observedAt = UtcInstant.parseCanonical(field(MAX_TIME_BYTES).decodeToString(throwOnInvalidSequence = true))
            val payload = StoredBytes(field(MAX_STORED_BYTES))
            require(offset == bytes.size)
            rehydrate(payload, cursor.branchRevision, observedAt, cursor)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid inert projection cache")
        }
    }
}
