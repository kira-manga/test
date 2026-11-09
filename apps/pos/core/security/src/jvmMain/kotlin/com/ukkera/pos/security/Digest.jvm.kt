package com.ukkera.pos.security

import java.security.MessageDigest

public actual fun sha256(bytes: ByteArray): PayloadDigest =
    MessageDigest.getInstance("SHA-256").digest(bytes).hexDigest()
