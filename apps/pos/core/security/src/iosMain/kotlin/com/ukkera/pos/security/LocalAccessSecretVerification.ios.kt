package com.ukkera.pos.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.timingsafe_bcmp

@OptIn(ExperimentalForeignApi::class)
internal actual fun equalLocalAccessDigests(expected: ByteArray, actual: ByteArray): Boolean {
    requireDistinctAccessDigests(expected, actual)
    return expected.usePinned { left ->
        actual.usePinned { right ->
            timingsafe_bcmp(left.addressOf(0), right.addressOf(0), 32uL) == 0
        }
    }
}
