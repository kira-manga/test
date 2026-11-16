package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import kotlinx.serialization.json.Json

/** Pure bounded DATA fingerprint, never an authentication or disclosure capability. */
public object LocalMasterChangesBindingV1 {
    private const val MAX_CURSOR_BYTES = 16_384
    private val domain = "ukkera.lan.changes-binding/v1".encodeToByteArray()
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    /**
     * Exact request order and every cursor field, including actual cut, selector and payload
     * digest. Observation time is deliberately absent: the cursor contains no observedAt.
     * The actual current Master computes this independently at each DATA release boundary.
     */
    public fun digest(cursors: List<LocalMasterChangeCursorV1>): PayloadDigest {
        require(cursors.size in 1..LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTORS_V1)
        val fields = mutableListOf(domain)
        for (cursor in cursors) {
            val encoded = json.encodeToString(LocalMasterChangeCursorV1.serializer(), cursor)
                .encodeToByteArray(throwOnInvalidSequence = true)
            require(encoded.size in 1..MAX_CURSOR_BYTES)
            fields += encoded
        }
        return sha256(frame(fields))
    }
}
