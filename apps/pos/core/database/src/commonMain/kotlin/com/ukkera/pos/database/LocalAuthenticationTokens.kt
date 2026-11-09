package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId

internal fun freshAuthenticationId(entropy: LocalAuthenticationEntropy): DomainId {
    val bytes = ByteArray(16)
    return try {
        entropy.fill(bytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        authenticationUuid(bytes, 0)
    } finally { bytes.fill(0) }
}

/** 122 random locator bits plus independently generated32-byte access/refresh secrets. */
internal class LocalAuthenticationTokenMaterial(entropy: LocalAuthenticationEntropy) {
    val sessionId: DomainId = freshAuthenticationId(entropy)
    private val access = ByteArray(32)
    private val refresh = ByteArray(32)
    init {
        try { entropy.fill(access); entropy.fill(refresh) }
        catch (failure: Throwable) { close(); throw failure }
    }
    val accessHash: LocalAccessTokenHash get() = LocalAccessTokenHash.hashSecret(access)
    val refreshHash: LocalRefreshTokenHash get() = LocalRefreshTokenHash.hashSecret(refresh)
    private var closed = false

    fun publish(view: LocalSessionView): LocalSessionTokens {
        check(!closed && view.sessionId == sessionId)
        val first = encode(0x41, access)
        return try { LocalSessionTokens(view, first, encode(0x52, refresh)) }
        catch (failure: Throwable) { first.destroy(); throw failure }
    }
    private fun encode(kind: Int, secret: ByteArray): OpaqueCredential {
        val bytes = ByteArray(52)
        return try {
            bytes[0] = 0x55; bytes[1] = 0x4c; bytes[2] = kind.toByte(); bytes[3] = 0x31
            val hex = sessionId.value.replace("-", "")
            for (index in 0 until 16) bytes[4 + index] = ((hex[index * 2].digitToInt(16) shl 4) or hex[index * 2 + 1].digitToInt(16)).toByte()
            secret.copyInto(bytes, 20)
            OpaqueCredential(bytes)
        } finally { bytes.fill(0) }
    }
    fun close() { access.fill(0); refresh.fill(0); closed = true }
}

/** Strict52-byte binary ULA1/ULR1 only. Neither profile accepts the other secret. */
internal class LocalAuthenticationBearer private constructor(val sessionId: DomainId, val refresh: Boolean, private val secret: ByteArray) {
    fun matches(access: LocalAccessIssuance, refreshIssuance: LocalRefreshIssuance?): Boolean =
        if (refresh) refreshIssuance?.tokenHash?.matchesSecret(secret) == true else access.tokenHash.matchesSecret(secret)
    fun close() { secret.fill(0) }
    override fun toString(): String = "LocalAuthenticationBearer([REDACTED])"
    companion object {
        fun parse(bytes: ByteArray): LocalAuthenticationBearer? {
            if (bytes.size != 52 || bytes[0] != 0x55.toByte() || bytes[1] != 0x4c.toByte() || bytes[3] != 0x31.toByte() ||
                (bytes[2] != 0x41.toByte() && bytes[2] != 0x52.toByte())) return null
            return LocalAuthenticationBearer(authenticationUuid(bytes, 4), bytes[2] == 0x52.toByte(), bytes.copyOfRange(20, 52))
        }
    }
}
private fun authenticationUuid(bytes: ByteArray, offset: Int): DomainId = DomainId(buildString(36) {
    for (index in 0 until 16) {
        if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
        val value = bytes[offset + index].toInt() and 0xff
        append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15])
    }
})
