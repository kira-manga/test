package com.ukkera.pos.security

/** Fixed-size internal primitive only, not a general secret/credential comparator. */
internal expect fun equalLocalAccessDigests(expected: ByteArray, actual: ByteArray): Boolean

internal fun requireDistinctAccessDigests(expected: ByteArray, actual: ByteArray) {
    require(expected.size == 32 && actual.size == 32 && expected !== actual) {
        "Local access comparison requires distinct 32-byte digests"
    }
}

internal fun localAccessSecretMatches(expected: LocalAccessTokenHash, secret: ByteArray): Boolean {
    // hashSecret validates the exact secret length and preserves the established hash domain.
    val actual = LocalAccessTokenHash.hashSecret(secret)
    val expectedBytes = decodeAccessDigest(expected.canonicalSha256())
    return try {
        val actualBytes = decodeAccessDigest(actual.canonicalSha256())
        try {
            equalLocalAccessDigests(expectedBytes, actualBytes)
        } finally {
            actualBytes.fill(0)
        }
    } finally {
        expectedBytes.fill(0)
    }
}

/** Both inputs were already validated by LocalAccessTokenHash; no bearer becomes a String. */
private fun decodeAccessDigest(canonical: String): ByteArray = ByteArray(32) { index ->
    ((canonical[index * 2].digitToInt(16) shl 4) or canonical[index * 2 + 1].digitToInt(16)).toByte()
}
