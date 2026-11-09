package com.ukkera.pos.app

import com.ukkera.pos.security.PurchasingEvidenceMediaV1

/** Native file access only. These ports neither grant Master access nor capture a document. */
interface LocalPurchasingDocumentFiles {
    val bound: Boolean

    suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection

    suspend fun chooseDestination(
        originalFileName: String,
        media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean,
    ): LocalPurchasingDocumentFileDestinationSelection
}

/** A complete owned snapshot, never a URI, path, decoder output or permission promise. */
sealed interface LocalPurchasingDocumentFileSelection {
    class Selected(
        bytes: ByteArray,
        val originalFileName: String,
        val media: PurchasingEvidenceMediaV1,
    ) : LocalPurchasingDocumentFileSelection {
        private var owned: ByteArray? = snapshotLocalPurchasingDocumentSource(bytes, originalFileName, media)

        val byteCount: Int get() = checkNotNull(owned) { "Document source destroyed" }.size
        fun copyBytes(): ByteArray = checkNotNull(owned) { "Document source destroyed" }.copyOf()
        fun destroy() { owned?.fill(0); owned = null }
        override fun toString(): String = "LocalPurchasingDocumentFileSelection.Selected([REDACTED])"
    }

    data object Cancelled : LocalPurchasingDocumentFileSelection
    data class Unavailable(val problem: LocalPurchasingDocumentFileProblem) : LocalPurchasingDocumentFileSelection
}

sealed interface LocalPurchasingDocumentFileDestinationSelection {
    class Chosen(val destination: LocalPurchasingDocumentFileDestination) : LocalPurchasingDocumentFileDestinationSelection {
        override fun toString(): String = "LocalPurchasingDocumentFileDestinationSelection.Chosen([REDACTED])"
    }

    data object Cancelled : LocalPurchasingDocumentFileDestinationSelection
    data class Unavailable(val problem: LocalPurchasingDocumentFileProblem) : LocalPurchasingDocumentFileDestinationSelection
}

/**
 * One native user-selected output. A successful write still needs successful close before
 * a saved-file claim. Failure/cancellation calls abort; failed cleanup must remain visible.
 * Callbacks are current shared session/lifetime reads, never a separate native permission.
 */
interface LocalPurchasingDocumentFileDestination {
    suspend fun write(bytes: ByteArray, isCurrent: () -> Boolean): LocalPurchasingDocumentFileIoResult
    suspend fun close(): LocalPurchasingDocumentFileIoResult
    suspend fun abort(): LocalPurchasingDocumentFileIoResult
}

sealed interface LocalPurchasingDocumentFileIoResult {
    data object Succeeded : LocalPurchasingDocumentFileIoResult
    data class Unavailable(val problem: LocalPurchasingDocumentFileProblem) : LocalPurchasingDocumentFileIoResult
}

enum class LocalPurchasingDocumentFileProblem {
    NOT_BOUND,
    ACCESS_CHANGED,
    BUSY,
    INVALID_FILE_NAME,
    UNSUPPORTED_MEDIA,
    TOO_LARGE,
    EMPTY_FILE,
    PERMISSION_DENIED,
    IO_FAILED,
    CLOSE_FAILED,
    OUTPUT_MISMATCH,
    CLEANUP_FAILED,
    ALREADY_USED,
}

object UnavailableLocalPurchasingDocumentFiles : LocalPurchasingDocumentFiles {
    override val bound: Boolean = false
    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection =
        LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
    override suspend fun chooseDestination(originalFileName: String, media: PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection =
        LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)
}

const val MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES: Int = 512 * 1024

private fun snapshotLocalPurchasingDocumentSource(bytes: ByteArray, name: String, media: PurchasingEvidenceMediaV1): ByteArray {
    require(bytes.size in 1..MAX_LOCAL_PURCHASING_DOCUMENT_FILE_BYTES)
    val owned = bytes.copyOf()
    try {
        require(validLocalPurchasingDocumentFileName(name))
        require(localPurchasingDocumentMediaMatches(media, owned))
        return owned
    } catch (failure: Throwable) { owned.fill(0); throw failure }
}

internal fun validLocalPurchasingDocumentFileName(value: String): Boolean {
    if (value.length !in 1..192 || value.isBlank() || value.any { it.code < 0x20 || it.code == 0x7f }) return false
    return try { value.encodeToByteArray(throwOnInvalidSequence = true).fill(0); true }
    catch (_: Exception) { false }
}

/** Same bounded documentary signatures as the Master; this makes no rendering/authenticity claim. */
internal fun localPurchasingDocumentMediaMatches(media: PurchasingEvidenceMediaV1, bytes: ByteArray): Boolean = when (media) {
    PurchasingEvidenceMediaV1.JPEG -> bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[2] == 0xff.toByte() && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
    PurchasingEvidenceMediaV1.PNG -> {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }
    }
    PurchasingEvidenceMediaV1.PDF -> bytes.size >= 8 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte() && bytes[4] == 0x2d.toByte() &&
        bytes[5] in byteArrayOf(0x31, 0x32) && bytes[6] == 0x2e.toByte() && bytes[7] in 0x30.toByte()..0x39.toByte()
}
