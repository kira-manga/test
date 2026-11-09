package com.ukkera.pos.app.files

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.ukkera.pos.app.LocalBranchReportFilePhase
import com.ukkera.pos.app.LocalBranchReportFileReceipt
import com.ukkera.pos.types.DomainId
import java.io.FileNotFoundException

/**
 * Read-only OS access to ALREADY published files, never the receipt store/private spool. System
 * DocumentsUI/user-granted URI access is declared in the manifest; this provider never launches
 * an intent, uploads, grants a URI itself, or treats metadata lookup as permission to publish.
 * Once explicitly exported, local files do not become unreadable on a POS role/session change.
 */
class AndroidLocalBranchReportDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        addRow(columnNames.map { column -> when (column) {
            DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT
            DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT
            DocumentsContract.Root.COLUMN_TITLE -> "Ukkera POS · Local reports / تقارير محلية"
            DocumentsContract.Root.COLUMN_SUMMARY -> "Published local CSV files only / ملفات CSV المحلية المنشورة فقط"
            DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_LOCAL_ONLY
            DocumentsContract.Root.COLUMN_MIME_TYPES -> "text/csv"
            else -> null
        } }.toTypedArray<Any?>())
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        if (documentId == ROOT) addDocument(null)
        else access { disk -> addDocument(requiredPublished(disk, documentId)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != ROOT) throw FileNotFoundException()
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            access { disk ->
                disk.receipts().filter { it.phase == LocalBranchReportFilePhase.PUBLISHED }.sortedBy { it.exportId.value }.forEach { receipt ->
                    if (disk.verifyPublished(receipt)) addDocument(receipt)
                }
            }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r" || documentId == ROOT) throw FileNotFoundException()
        signal?.throwIfCanceled()
        var owned: ParcelFileDescriptor? = null
        try {
            // Retain ownership even if access's post-block custody check fails after open.
            val descriptor = access { disk ->
                disk.openPublished(requiredPublished(disk, documentId)).also { owned = it }
            }
            signal?.throwIfCanceled()
            owned = null
            return descriptor
        } finally { owned?.close() }
    }

    private fun requiredPublished(disk: AndroidReportReceiptDisk, documentId: String): LocalBranchReportFileReceipt {
        val id = try { DomainId(documentId) } catch (_: IllegalArgumentException) { throw FileNotFoundException() }
        val receipt = disk.receipt(id)
        if (receipt.phase != LocalBranchReportFilePhase.PUBLISHED || !disk.verifyPublished(receipt)) throw FileNotFoundException()
        return receipt
    }

    private fun MatrixCursor.addDocument(receipt: LocalBranchReportFileReceipt?) {
        addRow(columnNames.map { column -> when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> receipt?.exportId?.value ?: ROOT
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> receipt?.fileName ?: "Ukkera POS · Local reports / تقارير محلية"
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (receipt == null) DocumentsContract.Document.MIME_TYPE_DIR else "text/csv"
            DocumentsContract.Document.COLUMN_SIZE -> receipt?.source?.byteCount
            DocumentsContract.Document.COLUMN_FLAGS -> 0 // no write/create/delete/rename/thumbnail/search promises
            else -> null
        } }.toTypedArray<Any?>())
    }

    private fun <T> access(block: (AndroidReportReceiptDisk) -> T): T = try {
        AndroidReportFileStorage.access(checkNotNull(context).applicationContext, block)
    } catch (_: Exception) { throw FileNotFoundException("Local report file unavailable") }

    private companion object {
        const val ROOT = "ukkera-local-published-reports-v1"
        val ROOT_COLUMNS = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES)
        val DOCUMENT_COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)
    }
}
