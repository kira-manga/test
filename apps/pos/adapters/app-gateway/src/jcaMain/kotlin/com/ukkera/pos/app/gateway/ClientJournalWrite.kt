package com.ukkera.pos.app.gateway

import com.ukkera.pos.types.OperationId
import java.nio.ByteBuffer

/**
 * One exact encrypted file replacement/immutable archive addition. This private write-ahead
 * record contains ciphertext, not credentials in plaintext. It is NOT authority on its own:
 * recovery requires the existing native successor head and the complete before/after file cut.
 * A missing successor never permits creation of a head from this filesystem record.
 */
internal class ClientJournalWrite(
    val previousSequence: Long,
    val previousDigest: String,
    val nextDigest: String,
    val archiveId: OperationId?,
    val ciphertext: ByteArray,
) {
    fun clear() { ciphertext.fill(0) }
    override fun toString(): String = "ClientJournalWrite([REDACTED])"
}

/** Persisted local recovery format; independent of the unchanged journal/credential formats. */
internal object ClientJournalWriteCodecV1 {
    const val FILE_NAME = "write-ahead.v1.pending"
    const val MAX_HEADER_BYTES = 117
    private const val MAGIC = 0x41435731

    fun encode(value: ClientJournalWrite, maximumCiphertext: Int): ByteArray {
        validate(value, maximumCiphertext)
        val out = ByteBuffer.allocate(MAX_HEADER_BYTES + value.ciphertext.size)
        try {
            out.putInt(MAGIC).putLong(value.previousSequence)
            decodeHex(value.previousDigest, 32).useWiped(out::put)
            decodeHex(value.nextDigest, 32).useWiped(out::put)
            out.put(if (value.archiveId == null) 0.toByte() else 1.toByte())
            value.archiveId?.value?.encodeToByteArray()?.useWiped(out::put)
            out.putInt(value.ciphertext.size).put(value.ciphertext)
            return out.array().copyOf(out.position())
        } finally { out.array().fill(0) }
    }

    fun decode(bytes: ByteArray, maximumCiphertext: Int): ClientJournalWrite {
        check(bytes.size in 82..(MAX_HEADER_BYTES + maximumCiphertext))
        val input = ByteBuffer.wrap(bytes)
        check(input.int == MAGIC)
        val sequence = input.long
        fun digest(): String = ByteArray(32).useWiped { input.get(it); encodeHex(it) }
        val previous = digest()
        val next = digest()
        val archive = when (input.get().toInt()) {
            0 -> null
            1 -> ByteArray(36).useWiped { input.get(it); OperationId(it.decodeToString(throwOnInvalidSequence = true)) }
            else -> error("client.journal.write-format")
        }
        val size = input.int
        check(size in 1..maximumCiphertext && size == input.remaining())
        val ciphertext = ByteArray(size)
        try {
            input.get(ciphertext)
            val value = ClientJournalWrite(sequence, previous, next, archive, ciphertext)
            validate(value, maximumCiphertext)
            encode(value, maximumCiphertext).useWiped { check(it.contentEquals(bytes)) }
            return value
        } catch (failure: Throwable) { ciphertext.fill(0); throw failure }
    }

    private fun validate(value: ClientJournalWrite, maximumCiphertext: Int) {
        check(value.previousSequence in 0 until Long.MAX_VALUE && value.previousDigest != value.nextDigest)
        for (digest in listOf(value.previousDigest, value.nextDigest))
            check(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' })
        value.archiveId?.let { check(CLIENT_JOURNAL_UUID_V1.matches(it.value)) }
        check(value.ciphertext.size in 1..maximumCiphertext)
    }
}
