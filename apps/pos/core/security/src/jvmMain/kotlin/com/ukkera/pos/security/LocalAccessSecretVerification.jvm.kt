package com.ukkera.pos.security

import java.security.MessageDigest

internal actual fun equalLocalAccessDigests(expected: ByteArray, actual: ByteArray): Boolean {
    requireDistinctAccessDigests(expected, actual)
    return MessageDigest.isEqual(expected, actual)
}
